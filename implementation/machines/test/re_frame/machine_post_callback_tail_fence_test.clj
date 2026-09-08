(ns re-frame.machine-post-callback-tail-fence-test
  "rf2-hloj0g — fence machine SPAWN and FINALIZATION tails after EVERY
  callback boundary.

  #5856 (rf2-3evq0x) fenced the spawn cascade before install and the
  completion tail before teardown, but framework-owned tails still crossed
  LATER callback boundaries the earlier fences ran ahead of:

    - Spawn: the `:rf.machine.lifecycle/spawned` trace is callback-bearing
      and fires AFTER install / classification / spawn-order, so a listener
      that destroys A + publishes same-id B must not let the `:start`
      bootstrap dispatch reach B.
    - Finalization: after the top-level completion fence, the teardown tail
      ran the `:rf.machine/destroyed` trace and the late-bound HTTP abort
      hook, and then HTTP/timer cancellation, classification/spawn-order
      drop, registrar unregister, and `:on-error` dispatch with NO fresh
      fence between them. A listener / late-abort hook that published same-id
      B let A's tail mutate B.

  The ruled policy (rf2-3evq0x, extended here): already-entered authored
  callbacks may unwind, but loss of exact-incarnation ownership is a TERMINAL
  fence for EVERY subsequent framework-owned action, rechecked after each
  callback-bearing trace or hook. These fixtures drive the tails DIRECTLY
  under a bound event owner (A's dequeue-time token) with a destroyer that
  publishes same-id B on the callback's / hook's own stack, and assert B
  stays byte-identical.

  Deterministic + single-threaded — the destroyer runs INSIDE the callback /
  hook, so the same-id successor B is published on the callback's own stack."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.lifecycle-fx.finalize :as rf.machines.lifecycle-fx.finalize]
            [re-frame.machines.lifecycle-fx.spawn :as rf.machines.lifecycle-fx.spawn]
            [re-frame.machines.spawn-order :as rf.machines.spawn-order]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; Touch the artefact so the machines registration hooks are wired even when
;; this ns runs in isolation.
(def ^:private _artefact rf.machines/machine-transition)

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- spawn-tail fence (post-install callbacks) ----------------------------
;;
;; The `:rf.machine.lifecycle/spawned` trace is callback-bearing. The caller
;; must run NO further tail (`:start`) unless install COMMITTED and the exact
;; owner is STILL current.

(def ^:private spawn-child-type :rf2-hloj0g/spawn-child)
(def ^:private spawn-child-instance-id (keyword "rf2-hloj0g" "spawn-child#1"))

(defn- run-spawn-tail
  "Register the child machine, make frame A, install a destroyer keyed on
  `trigger`, then call `spawn-fx` DIRECTLY under A's bound event owner.
  `trigger` is `:lifecycle-spawned`. Returns the observable post-spawn state
  on B."
  [frame-a trigger]
  (rf.machines.spawn-order/reset-all!)
  (rf/reg-machine spawn-child-type
    {:initial :running
     :states  {:running {:on {:go :done}}
               :done    {:final? true}}})
  (rf/make-frame {:id frame-a})
  (let [token-a          (rf.frame/frame-incarnation-token frame-a)
        fired?           (atom false)
        dispatches       (atom [])
        lifecycle-traces (atom [])
        b-birth          (atom nil)
        orig-dispatch!   (rf.late-bind/get-fn :router/dispatch!)
        destroy+B!       (fn []
                           (when (compare-and-set! fired? false true)
                             (rf.frame/destroy-frame! frame-a)   ;; destroy A
                             (rf/make-frame {:id frame-a})     ;; same-id B
                             (reset! b-birth (rf.machines.test-support/runtime-db frame-a))))]
    (rf.trace.tooling/register-listener!
      ::spawn-tail-fence
      (fn [ev]
        (case (:operation ev)
          :rf.machine.lifecycle/spawned (do (swap! lifecycle-traces conj ev)
                                            (when (= trigger :lifecycle-spawned) (destroy+B!)))
          nil)))
    (try
      ;; Capture (never route) any dispatch the cascade attempts.
      (rf.late-bind/set-fn! :router/dispatch!
                         (fn [ev opts] (swap! dispatches conj [ev opts]) nil))
      (rf.frame/call-with-event-owner-token frame-a token-a
        (fn [] (rf.machines.lifecycle-fx.spawn/spawn-fx {:frame frame-a}
                               {:machine-id spawn-child-type :start [:go]})))
      {:fired?           @fired?
       :b-birth          @b-birth
       :b-runtime        (rf.machines.test-support/runtime-db frame-a)
       :b-snapshot       (rf.machines.test-support/snapshot frame-a spawn-child-instance-id)
       :spawn-order      (rf.machines.spawn-order/frame-order frame-a)
       :dispatches       @dispatches
       :lifecycle-traces @lifecycle-traces}
      (finally
        (rf.trace.tooling/unregister-listener! ::spawn-tail-fence)
        (when orig-dispatch!
          (rf.late-bind/set-fn! :router/dispatch! orig-dispatch!))))))

(deftest lifecycle-spawned-loss-fences-start
  (testing "control (regression guard for the existing post-lifecycle-spawned
            fence): a :rf.machine.lifecycle/spawned listener that destroys A +
            publishes same-id B fires AFTER install / classification /
            spawn-order (all against live A), so only the :start dispatch is
            fenced — B receives no bootstrap dispatch."
    (let [frame-a :rf2-hloj0g/lifecycle-frame]
      (let [result (run-spawn-tail frame-a :lifecycle-spawned)]
        (is (true? (:fired? result)) "the :rf.machine.lifecycle/spawned listener ran (fence exercised)")
        (is (= 1 (count (:lifecycle-traces result)))
            "the lifecycle-spawned trace fired exactly once (it precedes the loss)")
        (is (empty? (:spawn-order result))
            "no spawn-order entry survives on same-id B (A's entry died with A)")
        (is (empty? (:dispatches result))
            "no :start dispatch fired into B after the lifecycle-spawned loss")))))

(deftest live-owner-spawn-installs-once
  (testing "control: a spawn whose install fires no destroyer completes exactly
            once — snapshot installed, spawn-order recorded,
            lifecycle-spawned emitted, :start dispatched. The fence is scoped to
            owner-loss only."
    (rf.machines.spawn-order/reset-all!)
    (rf/reg-machine spawn-child-type
      {:initial :running
       :states  {:running {:on {:go :done}}
                 :done    {:final? true}}})
    (let [frame-a        :rf2-hloj0g/live-spawn-frame
          dispatches     (atom [])
          orig-dispatch! (rf.late-bind/get-fn :router/dispatch!)]
      (rf/make-frame {:id frame-a})
      (let [token-a (rf.frame/frame-incarnation-token frame-a)]
        (try
          (rf.late-bind/set-fn! :router/dispatch!
                             (fn [ev opts] (swap! dispatches conj [ev opts]) nil))
          (rf.frame/call-with-event-owner-token frame-a token-a
            (fn [] (rf.machines.lifecycle-fx.spawn/spawn-fx {:frame frame-a}
                                   {:machine-id spawn-child-type
                                    :start      [:go]})))
          (is (some? (rf.machines.test-support/snapshot frame-a spawn-child-instance-id))
              "the live spawn installed the child snapshot")
          (is (= [spawn-child-instance-id] (vec (rf.machines.spawn-order/frame-order frame-a)))
              "the live spawn recorded exactly one spawn-order entry")
          (is (= 1 (count @dispatches))
              "the live spawn dispatched :start exactly once")
          (finally
            (when orig-dispatch!
              (rf.late-bind/set-fn! :router/dispatch! orig-dispatch!))))))))

;; ---- finalization-tail fence (teardown callbacks) -------------------------
;;
;; After the top-level completion fence, the teardown tail's `:rf.machine/
;; destroyed` trace and late-bound HTTP abort hook are each callback-bearing.
;; Ownership is rechecked
;; after each before the next framework-owned action (HTTP/timer cancellation,
;; classification/spawn-order drop, registrar unregister, `:on-error`
;; dispatch, runtime-db/fx publication).

(defn- finishing-machine
  "A runtime-stamped, finished singleton spec resting on a `:final?` leaf whose
  `:output-key` designates the completion result."
  [frame-id]
  {:initial   :done
   :rf/frame  frame-id
   :rf/cofx   {:rf/time-ms 0}
   :data      {:result 42}
   :states    {:done {:final? true :output-key :result}}})

(defn- finishing-snapshot [] {:state :done :data {:result 42}})

(defn- seed-finishing!
  "Install `machine-id`'s finishing snapshot into `frame-id`'s runtime-db."
  [frame-id machine-id]
  (rf.frame/swap-runtime-db!
    frame-id
    (fn [rt] (assoc-in rt [:rf.runtime/machines :snapshots machine-id] (finishing-snapshot)))))

(defn- run-teardown-tail
  "Register `machine-id` as a singleton, make frame A, seed its finishing
  snapshot, install a destroyer keyed on `trigger`, then call
  `finalize-machine` DIRECTLY under A's bound event owner. `trigger` is
  `:destroyed-trace` or `:http-abort`. Returns the observable post-finalize
  state."
  [frame-a machine-id trigger]
  (rf.machines.spawn-order/reset-all!)
  (rf/reg-machine machine-id (finishing-machine frame-a))
  (rf/make-frame {:id frame-a})
  (seed-finishing! frame-a machine-id)
  (let [token-a    (rf.frame/frame-incarnation-token frame-a)
        fired?     (atom false)
        destroyed  (atom [])
        orig-abort (rf.late-bind/get-fn :http/abort-on-actor-destroy)
        destroy+B! (fn []
                     (when (compare-and-set! fired? false true)
                       (rf.frame/destroy-frame! frame-a)   ;; destroy A
                       (rf/make-frame {:id frame-a})     ;; same-id B
                       (seed-finishing! frame-a machine-id)))]
    (rf.trace.tooling/register-listener!
      ::teardown-fence
      (fn [ev]
        (case (:operation ev)
          :rf.machine/destroyed          (do (swap! destroyed conj ev)
                                             (when (= trigger :destroyed-trace) (destroy+B!)))
          nil)))
    (try
      (when (= trigger :http-abort)
        ;; rf2-wjfm — the cascade calls the hook's frame-bearing arity.
        (rf.late-bind/set-fn! :http/abort-on-actor-destroy
                           (fn [_frame-id _actor-id] (destroy+B!) nil)))
      (let [ret (rf.frame/call-with-event-owner-token frame-a token-a
                  (fn []
                    (rf.machines.lifecycle-fx.finalize/finalize-machine
                      (finishing-machine frame-a)
                      machine-id frame-a (rf.machines.test-support/runtime-db frame-a)
                      (finishing-snapshot) [:some-completing-event] [])))]
        {:fired?     @fired?
         :ret        ret
         :b-runtime  (rf.machines.test-support/runtime-db frame-a)
         :b-snapshot (rf.machines.test-support/snapshot frame-a machine-id)
         :reg-entry  (rf.registrar/lookup :event machine-id)
         :destroyed  @destroyed})
      (finally
        (rf.trace.tooling/unregister-listener! ::teardown-fence)
        (rf.late-bind/set-fn! :http/abort-on-actor-destroy orig-abort)))))

(defn- assert-teardown-inert
  "Assert the finalize tail was fenced: B kept its snapshot + registrar entry,
  and finalize returned the inert outcome (runtime-db untouched, no fx)."
  [{:keys [ret b-runtime b-snapshot reg-entry]}]
  (is (= {:rf.db/runtime b-runtime :fx []} ret)
      "finalize returns the inert outcome — the A-derived teardown runtime-db + fx were dropped")
  (is (some? b-snapshot)
      "successor B's finishing snapshot survived (no A-derived teardown published)")
  (is (some? reg-entry)
      "successor B's registrar entry was NOT unregistered by the A-derived tail"))

(deftest destroyed-trace-loss-fences-teardown-tail
  (testing "a :rf.machine/destroyed trace LISTENER that destroys A + publishes
            same-id B: ownership is rechecked AFTER the destroyed trace, so the
            HTTP abort / timer cancel / classification+spawn-order drop /
            registrar unregister / :on-error / fx
            tail is fenced. Mutation tooth: without the post-destroyed recheck
            the whole tail runs against B."
    (let [frame-a    :rf2-hloj0g/destroyed-frame
          machine-id :rf2-hloj0g/destroyed-machine]
      (let [result (run-teardown-tail frame-a machine-id :destroyed-trace)]
        (is (true? (:fired? result)) "the :rf.machine/destroyed listener ran (fence exercised)")
        (is (= 1 (count (:destroyed result)))
            "the destroyed trace fired exactly once (it precedes the loss)")
        (assert-teardown-inert result)))))

(deftest http-abort-hook-loss-fences-teardown-tail
  (testing "the late-bound :http/abort-on-actor-destroy hook destroys A +
            publishes same-id B: ownership is rechecked AFTER the abort hook, so
            the timer cancel / classification+spawn-order drop /
            registrar unregister / :on-error / fx
            tail is fenced. Mutation tooth: without the post-abort recheck the
            tail runs against B."
    (let [frame-a    :rf2-hloj0g/abort-frame
          machine-id :rf2-hloj0g/abort-machine]
      (let [result (run-teardown-tail frame-a machine-id :http-abort)]
        (is (true? (:fired? result)) "the :http/abort-on-actor-destroy hook ran (fence exercised)")
        (is (= 1 (count (:destroyed result)))
            "the destroyed trace fired exactly once (it precedes the abort hook)")
        (assert-teardown-inert result)))))

(deftest live-owner-finalize-tears-down-once
  (testing "control: a completion whose teardown fires no destroyer tears down
            fully — the destroyed trace fires and finalize
            returns a runtime-db effect that dissoc'd the snapshot. The fence is
            scoped to owner-loss only."
    (rf.machines.spawn-order/reset-all!)
    (let [frame-a    :rf2-hloj0g/live-finalize-frame
          machine-id :rf2-hloj0g/live-finalize-machine
          destroyed  (atom [])]
      (rf/reg-machine machine-id (finishing-machine frame-a))
      (rf/make-frame {:id frame-a})
      (seed-finishing! frame-a machine-id)
      (let [token-a (rf.frame/frame-incarnation-token frame-a)]
        (rf.trace.tooling/register-listener!
          ::live-finalize
          (fn [ev] (case (:operation ev)
                     :rf.machine/destroyed (swap! destroyed conj ev)
                     nil)))
        (try
          (let [ret (rf.frame/call-with-event-owner-token frame-a token-a
                      (fn []
                        (rf.machines.lifecycle-fx.finalize/finalize-machine
                          (finishing-machine frame-a)
                          machine-id frame-a (rf.machines.test-support/runtime-db frame-a)
                          (finishing-snapshot) [:some-completing-event] [])))]
            (is (nil? (get-in (:rf.db/runtime ret)
                              [:rf.runtime/machines :snapshots machine-id]))
                "the live completion tore down the actor's snapshot in the returned runtime-db")
            (is (= 1 (count @destroyed)) "exactly one :rf.machine/destroyed trace fired"))
          (finally
            (rf.trace.tooling/unregister-listener! ::live-finalize)))))))
