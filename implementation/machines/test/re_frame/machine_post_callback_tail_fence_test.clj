(ns re-frame.machine-post-callback-tail-fence-test
  "Machine SPAWN and FINALIZATION tails recheck exact-incarnation ownership
  after every LATER callback boundary, not only before install / teardown:

    - Spawn: `:rf.machine.lifecycle/spawned` fires AFTER install /
      classification / spawn-order; a listener that destroys A and publishes
      same-id B must not receive the `:start` bootstrap dispatch.
    - Finalization: the teardown tail's `:rf.machine/destroyed` trace and the
      late-bound HTTP abort hook can each publish same-id B; nothing after
      them (cancellation, classification / spawn-order drop, registrar
      unregister, `:on-error`, runtime-db / fx publication) may act on B.

  Already-entered callbacks may unwind, but loss of ownership is terminal for
  every subsequent framework-owned action. The fixtures drive the tails
  DIRECTLY under A's bound event owner, with the destroyer on the callback's
  or hook's own stack."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.lifecycle-fx.finalize :as rf.machines.lifecycle-fx.finalize]
            [re-frame.machines.lifecycle-fx.spawn :as rf.machines.lifecycle-fx.spawn]
            [re-frame.machines.spawn-order :as rf.machines.spawn-order]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; Touch the artefact so the machines registration hooks are wired even when
;; this ns runs in isolation.
(def ^:private _artefact rf.machines/machine-transition)

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- spawn-tail fence (post-install callbacks) ----------------------------

(deftest lifecycle-spawned-loss-fences-start
  (testing "a :rf.machine.lifecycle/spawned listener that destroys A and
            publishes same-id B fires after install / spawn-order (against
            live A), so only the :start dispatch is left to fence — B
            receives none"
    (rf.machines.spawn-order/reset-all!)
    (rf/reg-machine :rf2-hloj0g/spawn-child
      {:initial :running
       :states  {:running {:on {:go :done}}
                 :done    {:final? true}}})
    (let [frame-a          :rf2-hloj0g/lifecycle-frame
          fired?           (atom false)
          dispatches       (atom [])
          lifecycle-traces (atom 0)
          orig-dispatch!   (rf.late-bind/get-fn :router/dispatch!)]
      (rf/make-frame {:id frame-a})
      (rf.trace.tooling/register-listener!
        ::spawn-tail-fence
        (fn [ev]
          (when (= :rf.machine.lifecycle/spawned (:operation ev))
            (swap! lifecycle-traces inc)
            (when (compare-and-set! fired? false true)
              (rf.frame/destroy-frame! frame-a)
              (rf/make-frame {:id frame-a})))))
      (try
        ;; Capture (never route) any dispatch the cascade attempts.
        (rf.late-bind/set-fn! :router/dispatch!
                           (fn [ev opts] (swap! dispatches conj [ev opts]) nil))
        (rf.frame/call-with-event-owner-token frame-a (rf.frame/frame-incarnation-token frame-a)
          (fn [] (rf.machines.lifecycle-fx.spawn/spawn-fx {:frame frame-a}
                                 {:machine-id :rf2-hloj0g/spawn-child :start [:go]})))
        ;; The trace count proves the install ran and the loss happened;
        ;; A's spawn-order entry died with A.
        (is (= {:lifecycle-traces 1 :spawn-order [] :dispatches []}
               {:lifecycle-traces @lifecycle-traces
                :spawn-order      (rf.machines.spawn-order/frame-order frame-a)
                :dispatches       @dispatches}))
        (finally
          (rf.trace.tooling/unregister-listener! ::spawn-tail-fence)
          (when orig-dispatch!
            (rf.late-bind/set-fn! :router/dispatch! orig-dispatch!)))))))

;; ---- finalization-tail fence (teardown callbacks) -------------------------

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
  [frame-id machine-id]
  (rf.frame/swap-runtime-db!
    frame-id
    (fn [rt] (assoc-in rt [:rf.runtime/machines :snapshots machine-id] (finishing-snapshot)))))

(defn- run-teardown-tail
  "Register `machine-id` as a singleton, make frame A, seed its finishing
  snapshot, install a destroyer keyed on `trigger` (`:destroyed-trace` or
  `:http-abort`) that destroys A and re-seeds a same-id B identically, then call
  `finalize-machine` DIRECTLY under A's bound event owner."
  [frame-a machine-id trigger]
  (rf.machines.spawn-order/reset-all!)
  (rf/reg-machine machine-id (finishing-machine frame-a))
  (rf/make-frame {:id frame-a})
  (seed-finishing! frame-a machine-id)
  (let [token-a    (rf.frame/frame-incarnation-token frame-a)
        fired?     (atom false)
        destroyed  (atom 0)
        orig-abort (rf.late-bind/get-fn :http/abort-on-actor-destroy)
        destroy+B! (fn []
                     (when (compare-and-set! fired? false true)
                       (rf.frame/destroy-frame! frame-a)
                       (rf/make-frame {:id frame-a})
                       (seed-finishing! frame-a machine-id)))]
    (rf.trace.tooling/register-listener!
      ::teardown-fence
      (fn [ev]
        (when (= :rf.machine/destroyed (:operation ev))
          (swap! destroyed inc)
          (when (= trigger :destroyed-trace) (destroy+B!)))))
    (try
      (when (= trigger :http-abort)
        ;; The cascade calls the hook's frame-bearing arity.
        (rf.late-bind/set-fn! :http/abort-on-actor-destroy
                           (fn [_frame-id _actor-id] (destroy+B!) nil)))
      (let [ret (rf.frame/call-with-event-owner-token frame-a token-a
                  (fn []
                    (rf.machines.lifecycle-fx.finalize/finalize-machine
                      (finishing-machine frame-a)
                      machine-id frame-a (rf.machines.test-support/runtime-db frame-a)
                      (finishing-snapshot) [:some-completing-event] [])))]
        {:ret       ret
         :b-runtime (rf.machines.test-support/runtime-db frame-a)
         :destroyed @destroyed})
      (finally
        (rf.trace.tooling/unregister-listener! ::teardown-fence)
        (rf.late-bind/set-fn! :http/abort-on-actor-destroy orig-abort)))))

(deftest teardown-callback-loss-fences-teardown-tail
  (testing "a :rf.machine/destroyed listener, or the late-bound
            :http/abort-on-actor-destroy hook, destroys A and publishes same-id
            B: A's destroyed trace (already delivered) stands, and finalize
            returns the inert outcome — B's re-seeded runtime-db untouched, no fx"
    (doseq [[frame-a machine-id trigger]
            [[:rf2-hloj0g/destroyed-frame :rf2-hloj0g/destroyed-machine :destroyed-trace]
             [:rf2-hloj0g/abort-frame :rf2-hloj0g/abort-machine :http-abort]]]
      (let [{:keys [ret b-runtime destroyed]} (run-teardown-tail frame-a machine-id trigger)]
        ;; Without the loss, ret would carry the teardown runtime-db (snapshot
        ;; removed), so this equality also proves the destroyer ran.
        (is (= [{:rf.db/runtime b-runtime :fx []} 1] [ret destroyed])
            (str trigger))))))
