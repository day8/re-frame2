(ns re-frame.router-drain-race-test
  "Per rf2-ynk7 §single-drainer invariant: stress-test the JVM-only
  race between the executor thread's `next-tick` drain callback and a
  main-thread `dispatch-sync!` drain. Before the fix, the
  `[:sync-only :sync-only]` corruption (double-peek of one envelope,
  drop of another) reproduced at ~4 fails / 1000 iters; after the fix
  the property should hold across many thousands of iterations.

  This file is the production-side companion to rf2-lmkk #442's test-
  only stabilisation. The rf2-lmkk fix wrapped
  `async-dispatch-resolves-after-current-drain` in a
  `with-redefs [interop/next-tick ...]` that bypasses the executor; this
  file targets the SAME failure shape without bypassing the executor —
  exercising the actual production code path the bead's fix lives in."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; The stress iteration count is set to keep CI under ~60s on the JVM
;; while staying well above the per-1000 failure-rate threshold the
;; pre-fix code exhibited (~4/1000). 5000 iterations on the fixed code
;; should land at 0/5000 with high confidence — the new lower bound on
;; the failure-rate-after-fix is 0.
(def ^:private stress-iters
  (or (some-> (System/getenv "RF2_YNK7_STRESS_ITERS") Long/parseLong)
      5000))

(deftest single-drainer-invariant-stress
  ;; rf2-ynk7 — Spec 002 §Run-to-completion §single-drainer invariant.
  ;; Pre-fix the same scenario produced ~4 fails / 1000 iters with the
  ;; race producing `[:sync-only :sync-only]` (the async envelope was
  ;; peek'd twice and a different envelope was dropped by the trailing
  ;; pop). Post-fix the CAS on `:drain-lock` admits a single drainer at
  ;; a time; the spin-CAS-wait in `drain-block!` keeps `dispatch-sync`'s
  ;; cascade-settled-before-return contract intact even when the
  ;; executor is mid-drain.
  ;;
  ;; This test does NOT use `with-redefs [interop/next-tick ...]` — the
  ;; real JVM executor MUST be in the picture for the race window to
  ;; appear. rf2-lmkk #442's test variant uses the with-redefs form
  ;; because it pins a different property (the executor's callback
  ;; ordering); this test pins the underlying race fix.
  (testing (str "no [:sync-only :sync-only] corruption across "
                stress-iters " iterations")
    (rf/reg-event-db :outside-async
      (fn [db _] (assoc db :outside? true)))
    (rf/reg-event-db :sync-only
      (fn [db _] (assoc db :sync? true)))

    (let [failures (atom [])
          ;; Capture the per-iter `order` atom through a global indirection
          ;; so we don't have to re-register handlers each iter (which
          ;; can race with leftover envelopes from a prior iter).
          current-order (atom (atom []))
          done-promise  (atom (promise))]
      (rf/reg-event-db :outside-async-stress
        (fn [db _]
          (swap! @current-order conj :outside-async)
          (deliver @done-promise :ok)
          (assoc db :outside? true)))
      (rf/reg-event-db :sync-only-stress
        (fn [db _]
          (swap! @current-order conj :sync-only)
          (assoc db :sync? true)))
      (dotimes [i stress-iters]
        (let [order (atom [])
              done  (promise)]
          (reset! current-order order)
          (reset! done-promise done)
          ;; Schedule the async dispatch FIRST. Its drain callback rides
          ;; the real `interop/next-tick` → JVM single-thread executor.
          (rf/dispatch [:outside-async-stress])
          ;; Then run a sync drain. Pre-fix: race window between the
          ;; executor's wake-up and main thread's drain on the SAME
          ;; queue. Both threads could peek the same envelope.
          (rf/dispatch-sync [:sync-only-stress])
          ;; Wait for the async cascade to settle. If we time out, the
          ;; async event was dropped (the original race shape).
          (let [d (deref done 5000 :timeout)]
            (when (= :timeout d)
              (swap! failures conj {:iter i :reason :timeout :order @order})))
          ;; Validate: both events ran exactly once.
          (let [final-order @order
                sync-count   (count (filter #{:sync-only} final-order))
                async-count  (count (filter #{:outside-async} final-order))]
            (when-not (and (= 1 sync-count) (= 1 async-count))
              (swap! failures conj {:iter        i
                                    :order       final-order
                                    :sync-count  sync-count
                                    :async-count async-count})))))
      (is (zero? (count @failures))
          (str "Expected zero failures across " stress-iters
               " iterations; got " (count @failures)
               (when (pos? (count @failures))
                 (str ". First few: " (pr-str (vec (take 5 @failures))))))))))

(deftest concurrent-dispatch-stress
  ;; A second stress shape: many submitter threads concurrently
  ;; `dispatch`-ing to one frame while another thread runs
  ;; `dispatch-sync`. Tests that the single-drainer invariant holds
  ;; under high contention — every dispatched event runs exactly once,
  ;; no envelope is dropped, no envelope is double-processed.
  ;;
  ;; Per rf2-ynk7 this is the broader correctness property the bead
  ;; named: at most one thread inside `drain!` at any instant; the
  ;; orphan window (envelope queued between empty-check and lock
  ;; release) closed by the release-under-locking-router seam.
  (testing "N submitter threads + sync drain — no events lost or duplicated"
    (let [n-submitters 8
          per-thread   200
          total        (* n-submitters per-thread)]
      ;; Use a dedicated frame with a high :drain-depth so the cascade
      ;; doesn't trip the default 100-event limit and roll back the
      ;; partial cascade per Spec 002 §Run-to-completion §Rules rule 3.
      ;; This test is about the single-drainer invariant, not depth-
      ;; limit behaviour.
      (rf/reg-frame :stress.race/main {:drain-depth (* 4 (+ total 2))})
      (rf/reg-event-db :bump
        (fn [db _] (update db :n (fnil inc 0))))
      ;; Spin up submitter threads. They all dispatch concurrently.
      (let [latch   (java.util.concurrent.CountDownLatch. 1)
            futures (vec (for [_ (range n-submitters)]
                           (future
                             (.await latch)
                             (dotimes [_ per-thread]
                               (rf/dispatch [:bump] {:frame :stress.race/main})))))]
        (.countDown latch)
        ;; While submitters are running, the main thread fires a sync
        ;; drain. The single-drainer invariant must keep both safe.
        (rf/dispatch-sync [:bump] {:frame :stress.race/main})
        ;; Wait for all submitters to finish dispatching.
        (doseq [f futures] @f)
        ;; Now drain anything still queued by riding one more sync
        ;; drain. The drain-block! contract is "spin-CAS until the
        ;; lock is free, then drain everything until empty".
        (rf/dispatch-sync [:bump] {:frame :stress.race/main}))
      ;; Total = N submitters * per-thread + 2 sync drains.
      (let [expected (+ total 2)
            actual   (:n (rf/get-frame-db :stress.race/main))]
        (is (= expected actual)
            (str "Expected " expected " events processed (no drops/dups); "
                 "got " actual))))))
