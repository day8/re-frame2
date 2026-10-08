(ns re-frame.machine-after-delay-resolution-fence-cljs-test
  "An `:after` arm rechecks its captured frame incarnation immediately after
  resolving the delay, before reserving its timer-table slot.

  Delay resolution is callback-bearing even when the delay is pure: a fn-form
  delay that reads a subscription through `compute-sub` emits a synchronous
  `:rf.sub/run` trace, and a listener there can destroy frame A and publish a
  same-id B that arms its own timer at the identical key. An A continuation
  that reserved its slot unconditionally would overwrite B's entry and then —
  its later owner check noticing A's loss — reclaim that reservation, leaving
  B's host handle orphaned and its timeout never dispatched.

  Deterministic and single-threaded on both runtimes; the host clock is the
  only stub. Per Spec 005 §Delayed `:after` transitions."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private frame-id :delay-fence/frame)
(def ^:private actor :delay-fence/actor)

(defn- fresh-handle
  "A process-unique opaque host handle, distinguishable by `identical?`."
  []
  #?(:clj (Object.) :cljs #js {}))

(defn- delay-fn
  "A PURE fn-form delay: it only computes a sub from its supplied snapshot."
  [{:keys [snapshot]}]
  (rf/compute-sub [:delay-fence/delay] (:data snapshot)))

(def ^:private args
  {:rf/parent-id actor :rf/invoke-id [:waiting] :state :waiting
   :delay-key delay-fn :epoch 1 :server? false})

(defn- entry []
  (get-in @rf.machines.timer/after-timers
          [frame-id {:parent actor :spawn [:waiting] :delay delay-fn}]))

(defn- seed! [ms]
  (rf.frame/swap-runtime-db! frame-id assoc-in [:rf.runtime/machines :snapshots actor]
                             {:state :waiting :data {:ms ms :rf/after-epoch {[:waiting] 1}}}))

(deftest delay-resolution-loss-preserves-successor-timer
  (testing "a :rf.sub/run observer replaces A with B during A's delay
            resolution: A's stale continuation reserves nothing, so B's entry
            survives and B's timeout still dispatches once"
    (rf/reg-sub :delay-fence/delay (fn [db _] (:ms db)))
    (rf/make-frame {:id frame-id})
    (seed! 5000)
    (let [fired?     (atom false)
          b-entry    (atom nil)
          arms       (atom [])
          dispatched (atom [])
          orig       (rf.late-bind/get-fn :router/dispatch!)]
      ;; Once only: B's own arm resolves the same delay.
      (rf.trace.tooling/register-listener!
        ::swap-observer
        (fn [ev]
          (when (and (= :rf.sub/run (:operation ev))
                     (= :delay-fence/delay (get-in ev [:tags :rf.sub/id]))
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
                      rf.interop/cancel-scheduled! (fn [_h] nil)]
          (rf.machines.timer/after-schedule-fx {:frame frame-id} args)
          (is (= [7000] (mapv :ms @arms))
              "only B armed a host timer; A's stale continuation armed nothing")
          (is (= @b-entry (entry))
              "B's entry — token, handle, resolved delay — is exactly as B published it")
          (rf.late-bind/set-fn! :router/dispatch! (fn [ev _opts] (swap! dispatched conj ev)))
          ((:thunk (first @arms)))
          (is (= [[actor [:rf.machine.timer/after-elapsed delay-fn 1 [:waiting]]]] @dispatched)
              "B's host callback claims B's slot and dispatches its timeout once"))
        (finally
          (rf.late-bind/set-fn! :router/dispatch! orig)
          (rf.trace.tooling/unregister-listener! ::swap-observer))))))
