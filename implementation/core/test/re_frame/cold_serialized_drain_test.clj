(ns re-frame.cold-serialized-drain-test
  "JVM-only concurrency tests for the COLD `rf.frame/call-serialized-with-drain!`
  critical section's interaction with the single-drainer release protocol
  (rf2-x76af2.22). A cold section (out-of-drain flows lifecycle ops,
  `destroy-frame!`'s liveness flip, Tool-Pair state writes) takes the SAME
  per-frame `:drain-lock` as the event drainer but is NOT a drainer, so the
  drainer's release protocol had two holes:

    (a) PERMANENT QUEUE STRAND. A `dispatch!` arriving during the cold hold
        set `:scheduled?` true and scheduled a `drain-try!` that CAS-lost to
        the cold holder and gave up; the cold release did not re-check the
        queue, so the queue stranded (`:scheduled?` stuck true no-ops every
        later `ensure-drain-scheduled!`). Fix: the cold release mirrors the
        drainer's `try-release-on-empty!` — snapshot the queue and, if
        non-empty, re-kick a fresh async drain.

    (b) SAME-THREAD SELF-DEADLOCK. A `dispatch-sync!` issued from INSIDE a
        cold serialized thunk on the same thread routed through
        `drain-block!`, whose spin-CAS-acquire deadlocked on the `:drain-lock`
        the thread already held. Fix: `dispatch-sync!` detects the cold
        `:serialized-holder` (reentrant-cold?) and runs the seed-push + drain
        DIRECTLY via `drain-reentrant!` — no re-acquire, no release.

  Both symptoms are CLJS-immune (single-threaded) but authoritative on the
  JVM. The interleaving is forced deterministically:
    - (a) via the single-thread executor's FIFO ordering (a barrier task
      submitted AFTER the `drain-try!` strictly follows it, so awaiting the
      barrier proves the `drain-try!` fired-and-CAS-lost) — NOT a wall-clock
      sleep.
    - (b) via a bounded thread join that distinguishes 'returned immediately'
      from 'hung' (the standard deadlock-detector shape).

  Pattern follows `router_drain_race_test.clj` / `sub_cache_concurrency_test.clj`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.registrar :as rf.registrar]
            [re-frame.interop :as rf.interop]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (rf.frame/ensure-default-frame!)
  (binding [rf.frame/*current-frame* :rf/default]
    (test-fn)))

(use-fixtures :each reset-runtime)

;; A barrier over the runtime executor: submit a countdown task via the SAME
;; `rf.interop/next-tick` seam the router schedules `drain-try!` through. The JVM
;; executor is single-threaded FIFO, so this task runs strictly AFTER any
;; `drain-try!` already submitted — awaiting it deterministically proves the
;; earlier `drain-try!` completed (no wall-clock sleep). The 5s bound is only a
;; test-hang guard, never the coordination mechanism.
(defn- executor-barrier! []
  (let [latch (CountDownLatch. 1)]
    (rf.interop/next-tick (fn [] (.countDown latch)))
    (is (.await latch 5 TimeUnit/SECONDS) "executor barrier task ran")))

;; ---- (a) PERMANENT QUEUE STRAND -------------------------------------------

(deftest cold-serialized-release-rekicks-stranded-queue
  (testing "a cold call-serialized-with-drain! release re-kicks a queue stranded by a CAS-lost drain-try!"
    (let [ran      (atom 0)
          frame-id :rf/default]
      (rf/reg-event :bump
        (fn [{:keys [db]} _]
          (swap! ran inc)
          {:db (update db :n (fnil inc 0))}))
      (let [router (:router (rf.frame/frame frame-id))]
        ;; Cold serialized section on THIS thread. Inside it (holding
        ;; :drain-lock), dispatch! an event: enqueue + ensure-drain-scheduled!
        ;; sets :scheduled? true and schedules a drain-try! on the executor,
        ;; which CAS-loses to us and gives up.
        (rf.frame/call-serialized-with-drain! frame-id
          (fn []
            (rf/dispatch [:bump] {:frame frame-id})
            ;; Force the scheduled drain-try! to have FIRED and CAS-LOST.
            (executor-barrier!)
            ;; The strand precondition now holds deterministically.
            (is (= 1 (count (:queue @router)))
                "event queued while the cold section holds the lock")
            (is (true? (:scheduled? @router)) "a drain was scheduled")
            (is (zero? @ran)
                "the handler has NOT run — the cold section holds the lock and the drain-try! CAS-lost")))
        ;; Cold section released. Post-fix the release re-kicked a fresh
        ;; drain-try!; await it (FIFO after the re-kick) and assert the
        ;; stranded event drained. Pre-fix this stays stranded (ran = 0).
        (executor-barrier!)
        (is (= 1 @ran) "the cold release re-kicked the drain; the stranded event ran")
        (is (empty? (:queue @router)) "the queue drained")
        (is (false? (:scheduled? @router)) ":scheduled? reset after the drain settled")
        ;; And a FRESH dispatch recovers (pre-fix, :scheduled? stuck true made
        ;; ensure-drain-scheduled! no-op, so a new dispatch never drained).
        (rf/dispatch [:bump] {:frame frame-id})
        (executor-barrier!)
        (is (= 2 @ran) "a fresh dispatch after the cold section drains normally")))))

;; ---- (b) SAME-THREAD SELF-DEADLOCK ----------------------------------------

(deftest dispatch-sync-inside-cold-serialized-thunk-reenters
  (testing "dispatch-sync! from inside a cold serialized thunk re-enters instead of self-deadlocking"
    (let [ran      (atom 0)
          result   (atom ::unset)
          frame-id :rf/default]
      (rf/reg-event :inner
        (fn [{:keys [db]} _]
          (swap! ran inc)
          {:db (assoc db :inner? true)}))
      ;; Run the cold section on a WORKER thread so a self-deadlock manifests
      ;; as a JOIN TIMEOUT rather than hanging the whole test JVM.
      (let [worker (Thread.
                     ^Runnable
                     (fn []
                       (rf.frame/call-serialized-with-drain! frame-id
                         (fn []
                           ;; SAME thread already holds :drain-lock via the cold
                           ;; section. Pre-fix: dispatch-sync! → drain-block! →
                           ;; spin-CAS on the held lock → hangs forever.
                           ;; Post-fix: reentrant-cold? routes to drain-reentrant!
                           ;; which drains without re-acquiring the lock.
                           (rf/dispatch-sync [:inner] {:frame frame-id})
                           (reset! result :returned)))))]
        (.start worker)
        (.join worker 5000)
        (is (not (.isAlive worker))
            "the cold serialized thunk's dispatch-sync returned (no self-deadlock)")
        (is (= :returned @result) "the thunk completed past the nested dispatch-sync")
        (is (= 1 @ran) "the nested event actually ran (re-entered the drain)")
        (is (true? (:inner? (rf/app-db-value frame-id)))
            "the nested event's :db effect committed")))))
