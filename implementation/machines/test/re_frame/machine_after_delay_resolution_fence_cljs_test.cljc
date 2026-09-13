(ns re-frame.machine-after-delay-resolution-fence-cljs-test
  "rf2-gwye.21 — an `:after` arm rechecks its captured frame incarnation
  immediately after resolving the delay, before reserving its timer-table slot.

  Delay resolution is callback-bearing even when the delay is pure: a fn-form
  delay that reads a subscription through `compute-sub` emits a synchronous
  `:rf.sub/run` trace, and a listener there can destroy frame A and publish a
  same-id B that arms its own timer at the identical key. The A continuation
  used to reserve its slot unconditionally at `[frame-id k]`, overwriting B's
  entry, and then — its later owner check correctly noticing A's loss — reclaim
  that reservation, leaving B with no entry: B's host handle orphaned, its
  timeout never dispatched, and its teardown unable to cancel it.

  Deterministic and single-threaded on both runtimes; the host clock is the
  only stub. Per Spec 005 §Delayed `:after` transitions."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; Touch the artefact so the machines registration hooks are wired even when
;; this ns runs in isolation.
(def ^:private _artefact rf.machines/machine-transition)

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private frame-id :rf2-gwye-21/frame)
(def ^:private actor :rf2-gwye-21/actor)
(def ^:private snapshot-path [:rf.runtime/machines :snapshots actor])

(defn- fresh-handle
  "A process-unique opaque host handle, distinguishable by `identical?`."
  []
  #?(:clj (Object.) :cljs #js {}))

(defn- delay-fn
  "A PURE fn-form delay: it only computes a sub from its supplied snapshot."
  [{:keys [snapshot]}]
  (rf/compute-sub [:rf2-gwye-21/delay] (:data snapshot)))

(def ^:private args
  {:rf/parent-id actor :rf/invoke-id [:waiting] :state :waiting
   :delay-key delay-fn :epoch 1 :server? false})

(def ^:private timer-key {:parent actor :spawn [:waiting] :delay delay-fn})

(defn- entry [] (get-in @rf.machines.timer/after-timers [frame-id timer-key]))

(defn- seed! [ms]
  (rf.frame/swap-runtime-db! frame-id assoc-in snapshot-path
                             {:state :waiting :data {:ms ms :rf/after-epoch {[:waiting] 1}}}))

(defn- arm-a!
  "Arm A's `:after` with the host clock stubbed. When `replace?`, a once-only
  `:rf.sub/run` observer on the delay's own resolution destroys A, publishes
  same-id B with a 7000 ms delay, and arms B at the identical key. Calls `then`
  with the observations while the host-clock stubs are still installed."
  [replace? then]
  (let [fired?  (atom false)
        b-entry (atom nil)
        arms    (atom [])
        cancels (atom [])]
    (rf/reg-sub :rf2-gwye-21/delay (fn [db _] (:ms db)))
    (rf/make-frame {:id frame-id})
    (seed! 5000)
    (rf.trace.tooling/register-listener!
      ::swap-observer
      (fn [ev]
        (when (and replace?
                   (= :rf.sub/run (:operation ev))
                   (= :rf2-gwye-21/delay (get-in ev [:tags :rf.sub/id]))
                   (compare-and-set! fired? false true))
          (rf.frame/destroy-frame! frame-id)
          (rf/make-frame {:id frame-id})
          (seed! 7000)
          (rf.machines.timer/after-schedule-fx {:frame frame-id} args)
          (reset! b-entry (entry)))))
    (try
      (with-redefs [rf.interop/schedule-after!   (fn [thunk ms]
                                                   (let [h (fresh-handle)]
                                                     (swap! arms conj {:handle h :thunk thunk :ms ms})
                                                     h))
                    rf.interop/cancel-scheduled! (fn [h] (swap! cancels conj h) nil)]
        (rf.machines.timer/after-schedule-fx {:frame frame-id} args)
        (then {:fired? @fired? :b-entry @b-entry :arms @arms :cancels cancels}))
      (finally
        (rf.trace.tooling/unregister-listener! ::swap-observer)
        (swap! rf.machines.timer/after-timers dissoc frame-id)))))

(deftest delay-resolution-loss-preserves-successor-timer
  (testing "a :rf.sub/run observer replaces A with B during A's delay
            resolution: A's stale continuation reserves nothing, so B's entry,
            token and handle survive and B's timeout still dispatches once"
    (arm-a! true
      (fn [{:keys [fired? b-entry arms cancels]}]
        (is (true? fired?) "the :rf.sub/run observer ran (seam exercised)")
        (is (some? (:handle b-entry)) "B armed and published its own timer")
        (is (= b-entry (entry))
            "B's entry — token, handle, resolved delay — is exactly as B published it")
        (is (= [7000] (mapv :ms arms))
            "only B armed a host timer; A's stale continuation armed nothing")
        (is (not-any? #(identical? (:handle b-entry) %) @cancels)
            "B's host handle was not cancelled")
        (let [dispatched (atom [])
              orig       (rf.late-bind/get-fn :router/dispatch!)]
          (rf.late-bind/set-fn! :router/dispatch! (fn [ev _opts] (swap! dispatched conj ev)))
          (try
            ((:thunk (first arms)))
            (finally
              (rf.late-bind/set-fn! :router/dispatch! orig)))
          (is (= [[actor [:rf.machine.timer/after-elapsed delay-fn 1 [:waiting]]]]
                 @dispatched)
              "B's host callback claims B's slot and dispatches its timeout once"))))))

(deftest successor-teardown-cancels-its-own-timer
  (testing "after A's stale arm stops, B's own teardown still finds its entry
            and cancels B's host handle"
    (arm-a! true
      (fn [{:keys [b-entry cancels]}]
        (rf.machines.timer/cancel-actor-timers! frame-id actor)
        (is (some #(identical? (:handle b-entry) %) @cancels)
            "B's teardown cancelled B's own host handle")
        (is (nil? (entry)) "B's slot is released")))))

(deftest live-owner-arms-exactly-once
  (testing "control: with no replacement, A resolves its delay, reserves, arms
            once and publishes"
    (arm-a! false
      (fn [{:keys [fired? arms]}]
        (is (false? fired?))
        (is (= [5000] (mapv :ms arms)) "A armed exactly one host timer at its resolved delay")
        (is (= 5000 (:resolved-ms (entry))) "A's entry is published")
        (is (identical? (:handle (first arms)) (:handle (entry)))
            "the published handle is the one armed")))))
