(ns re-frame.reprojection-install-race-jvm-test
  "rf2-9c2jf (the MERGED-PR-#7114 audit reopen) — a PARTIALLY-INSTALLED
  reprojection must never be observable as INSTALLED.

  THE DEFECT. `re-frame.live-frame/ensure-reprojection-installed!` used to
  publish its once-flag with `compare-and-set!` FIRST and perform the three side
  effects the flag PROMISES afterwards:

      (when (compare-and-set! reprojection-installed? false true)   ;; publish
        (registrar/add-registration-hook! reproject-on-registration-change!)
        (late-bind/set-fn! :live-frame/mark-projection-dirty! …)
        (late-bind/set-fn! :live-frame/flush-projection!      …))

  `compare-and-set!` ELECTS one installer; it does not make the LOSERS WAIT for
  that installer to finish. A second `make-frame` on another thread reads the
  flag as `true` while the elected installer is still between the CAS and the
  hook, concludes the wiring is in place, and proceeds to SEAL ITS GENERATION —
  the seal `registrar/lookup` resolves every `(kind, id)` through
  (`call-with-frame-resolution`). A `reg-event` issued in that window fires NO
  hook, because no hook exists yet. When the elected installer finally finishes
  there is no dirty mark left for anything to flush, so that second frame stays
  PERMANENTLY STALE: `dispatch` reports `:rf.error/no-such-handler` for a
  handler `registrar/lookup` is holding at that very moment. That is the
  original rf2-9c2jf release blocker, recreated WITHOUT the debug gate.

  THE INVARIANT, stated as this namespace tests it: *completion is observable
  only after every side effect the flag promises is in place.* The repair
  publishes the flag LAST and serializes the whole once-body under a JVM
  monitor, so a concurrent caller either does the install or BLOCKS until the
  installer has finished it — it can never pass the boundary early.

  THE HARNESS. Both tests open the window DETERMINISTICALLY with a
  `with-redefs` barrier on one of the three side effects, so the installer
  thread parks INSIDE the once-body with the flag in whatever state the
  implementation put it in. No sleeps decide anything: the negative assertion
  (\"the second caller has NOT returned\") is the one the fix makes IMPOSSIBLE
  to violate — with the fix the second caller is blocked on a monitor the
  installer holds until the test releases it, so it cannot return at any
  timeout; without the fix it returns in microseconds.

    * `install-boundary-not-passable-before-the-registration-hook` parks at the
      FIRST side effect (the registrar hook) and pins the full stale-frame end
      state — the exact reproduction the audit recorded.
    * `install-boundary-not-passable-before-the-flush-late-bind` parks at the
      LAST side effect (`:live-frame/flush-projection!`, the read-time flush
      consult whose removal twin `:live-frame/mark-projection-dirty!` the
      registrar's `unregister!` / `clear-kind!` / `clear-all!` paths consult) and
      pins that even the final publication precedes observability.

  CLJS IS UNAFFECTED and has no counterpart here: it is single-threaded, so no
  second caller can observe the once-body mid-flight. The repair keeps the CLJS
  path lock-free (publish-last only).

  THE FIXTURE REWINDS THE ONCE-FLAG. `reprojection-installed?` is a process-wide
  `defonce`, so by the time this namespace runs some earlier `make-frame` has
  usually installed the wiring already. The fixture snapshots and restores the
  three pieces of process state the once-body writes — the flag, the registrar's
  registration-hook vector, and `late-bind/hooks` — so each test opens the SAME
  window a fresh JVM's very first `make-frame` opens, and leaves the process
  exactly as it found it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.live-frame :as live-frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

;; ---------------------------------------------------------------------------
;; White-box handles on the once-initialization's process state.
;;
;; These are deliberate private-var reads: the whole point of the test is that
;; the once-body's INTERMEDIATE state must never be observable, and observing it
;; is how we prove it. `reproject-on-registration-change!` is public, so hook
;; presence is an ordinary identity check.
;; ---------------------------------------------------------------------------

(def ^:private installed-flag       #'live-frame/reprojection-installed?)
(def ^:private registration-hooks   #'registrar/registration-hooks)

(defn- reprojection-hook-installed? []
  (boolean (some #{live-frame/reproject-on-registration-change!}
                 @@registration-hooks)))

(def ^:private published-keys
  [:live-frame/mark-projection-dirty! :live-frame/flush-projection!])

(defn- reset-runtime [test-fn]
  (let [hooks-before     @@registration-hooks
        late-bind-before @late-bind/hooks
        flag-before      @@installed-flag]
    (try
      (registrar/clear-all!)
      (reset! frame/frames {})
      (flows/reset-flows!)
      (schemas/clear-schemas-by-frame!)
      (trace/clear-listeners!)
      (rf/init! plain-atom/adapter)
      ;; Framework registrations live at namespace-load time; `clear-all!` wiped
      ;; them. Re-eval so the rest of the suite is not left short.
      (require 're-frame.routing :reload)
      (require 're-frame.ssr :reload)
      ;; Rewind the once-initialization AFTER those reloads (a reload re-adds
      ;; the artefact's own registration hooks): drop ONLY the reprojection
      ;; hook, un-publish ONLY the two late-bind keys the once-body owns, and
      ;; clear the flag. Anything else in the vector / hook map is left alone.
      (swap! @registration-hooks
             (fn [hs] (vec (remove #{live-frame/reproject-on-registration-change!} hs))))
      (swap! late-bind/hooks #(apply dissoc % published-keys))
      (run! late-bind/invalidate-cache! published-keys)
      (reset! @installed-flag false)
      (test-fn)
      (finally
        (reset! @registration-hooks hooks-before)
        (reset! late-bind/hooks late-bind-before)
        (run! late-bind/invalidate-cache! published-keys)
        (reset! @installed-flag flag-before)
        (registrar/clear-all!)
        (reset! frame/frames {})))))

(use-fixtures :each reset-runtime)

;; A latch we expect to FIRE waits this long; the fix makes every such wait
;; return promptly, so the number only bounds a hang.
(def ^:private ^:const settle-ms 10000)

;; A latch we expect NOT to fire waits this long. With the fix the second caller
;; is parked on a monitor the barriered installer holds, so no value of this
;; number can produce a false failure; without the fix it returns immediately,
;; so no value can produce a false pass either.
(def ^:private ^:const window-ms 2000)

(defn- await! [^CountDownLatch latch ^long ms]
  (.await latch ms TimeUnit/MILLISECONDS))

;; ---------------------------------------------------------------------------
;; 1. The audit's reproduction: park at the FIRST side effect (the registrar
;;    registration hook) and show the second frame go permanently stale.
;; ---------------------------------------------------------------------------

(deftest install-boundary-not-passable-before-the-registration-hook
  (testing "a second make-frame cannot observe the reprojection as installed
            while the elected installer is still adding the registrar hook"
    (let [add-hook!  registrar/add-registration-hook!
          at-barrier (CountDownLatch. 1)   ;; installer A has parked mid-install
          release    (CountDownLatch. 1)   ;; …and may now finish
          b-started  (CountDownLatch. 1)   ;; contender B's thread is running
          b-returned (CountDownLatch. 1)   ;; …and its make-frame has returned
          b-saw      (atom nil)
          runs       (atom 0)
          dispatch-throw (atom nil)]
      (rf/reg-event :audit/early (fn [{:keys [db]} _] {:db (assoc db :early true)}))
      (with-redefs [registrar/add-registration-hook!
                    (fn [f]
                      ;; Park INSIDE the once-body, before the first of the
                      ;; three side effects lands.
                      (.countDown at-barrier)
                      (await! release settle-ms)
                      (add-hook! f))]
        (let [a (future (rf/make-frame {:id :audit/a :doc "the elected installer"}))
              _ (is (await! at-barrier settle-ms)
                    "the elected installer parked inside ensure-reprojection-installed!")
              b (future
                  (.countDown b-started)
                  (rf/make-frame {:id :audit/b :doc "the concurrent contender"})
                  ;; What the contender can SEE the instant its frame exists —
                  ;; i.e. the instant its generation is sealed and resolvable.
                  (reset! b-saw {:hook?  (reprojection-hook-installed?)
                                 :mark?  (some? (late-bind/get-fn :live-frame/mark-projection-dirty!))
                                 :flush? (some? (late-bind/get-fn :live-frame/flush-projection!))})
                  ;; The `reg-*` that must reach the just-sealed generation.
                  (rf/reg-event :audit/late
                    (fn [{:keys [db]} _]
                      (swap! runs inc)
                      {:db (assoc db :late :ran)}))
                  (.countDown b-returned)
                  :done)]
          (is (await! b-started settle-ms) "the contender thread started")

          ;; THE INVARIANT. The contender must NOT be able to complete
          ;; construction while the installer is parked mid-install.
          (is (not (await! b-returned window-ms))
              (str "a concurrent make-frame passed the install boundary while the "
                   "elected installer was still between the once-flag and the "
                   "registrar hook — it sealed a generation with no hook behind it"))

          (.countDown release)
          (is (await! b-returned settle-ms) "the contender completed once released")
          (is (= :done (deref b settle-ms ::timeout)) "contender thread finished")
          (is (some? (deref a settle-ms ::timeout)) "installer thread finished")

          ;; Everything the flag promises was in place when the contender
          ;; observed it — including the removal twin the registrar's
          ;; `unregister!` / `clear-kind!` / `clear-all!` paths consult.
          (is (= {:hook? true :mark? true :flush? true} @b-saw)
              "the contender observed the FULL wiring, not a partial install")

          ;; And the end state: the contender's frame is NOT stale. A handler
          ;; registered after its construction resolves on the very next
          ;; dispatch into it.
          (try
            (rf/dispatch-sync [:audit/late] {:frame :audit/b})
            (catch Throwable t (reset! dispatch-throw t)))
          (is (nil? @dispatch-throw)
              (str "dispatch into the contender's frame threw: " @dispatch-throw))
          (is (= 1 @runs)
              "the handler registered after the contender's make-frame ran exactly once")
          (is (= :ran (:late (rf/app-db-value :audit/b)))
              "the contender's frame is not permanently stale"))))))

;; ---------------------------------------------------------------------------
;; 2. Park at the LAST side effect — the `:live-frame/flush-projection!`
;;    publication, whose twin `:live-frame/mark-projection-dirty!` is the
;;    registrar REMOVAL late-bind. Even the final publication must precede
;;    observability.
;; ---------------------------------------------------------------------------

(deftest install-boundary-not-passable-before-the-flush-late-bind
  (testing "a second make-frame cannot observe the reprojection as installed
            while the elected installer is still publishing the late-bind keys"
    (let [set-fn!    late-bind/set-fn!
          at-barrier (CountDownLatch. 1)
          release    (CountDownLatch. 1)
          b-started  (CountDownLatch. 1)
          b-returned (CountDownLatch. 1)
          b-saw      (atom nil)]
      (with-redefs [late-bind/set-fn!
                    (fn [k f]
                      ;; Park just before the LAST of the three side effects:
                      ;; the hook is added and the removal twin is published,
                      ;; only the read-time flush consult is outstanding.
                      (when (= k :live-frame/flush-projection!)
                        (.countDown at-barrier)
                        (await! release settle-ms))
                      (set-fn! k f))]
        (let [a (future (rf/make-frame {:id :audit.flush/a}))
              _ (is (await! at-barrier settle-ms)
                    "the elected installer parked before the last publication")
              b (future
                  (.countDown b-started)
                  (rf/make-frame {:id :audit.flush/b})
                  (reset! b-saw {:hook?  (reprojection-hook-installed?)
                                 :mark?  (some? (late-bind/get-fn :live-frame/mark-projection-dirty!))
                                 :flush? (some? (late-bind/get-fn :live-frame/flush-projection!))})
                  (.countDown b-returned)
                  :done)]
          (is (await! b-started settle-ms) "the contender thread started")
          (is (not (await! b-returned window-ms))
              (str "a concurrent make-frame passed the install boundary with the "
                   ":live-frame/flush-projection! consult still unpublished — its "
                   "frame would resolve without ever flushing a dirty projection"))
          (.countDown release)
          (is (await! b-returned settle-ms) "the contender completed once released")
          (is (= :done (deref b settle-ms ::timeout)) "contender thread finished")
          (is (some? (deref a settle-ms ::timeout)) "installer thread finished")
          (is (= {:hook? true :mark? true :flush? true} @b-saw)
              "the contender observed the FULL wiring, not a partial install"))))))
