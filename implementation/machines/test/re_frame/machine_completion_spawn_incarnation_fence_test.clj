(ns re-frame.machine-completion-spawn-incarnation-fence-test
  "Losing the exact frame incarnation inside a completion or spawn callback —
  the completion-output validator, a `:rf.machine/done` listener, a
  `:rf.machine.spawn/spawned` listener — terminally fences the framework-owned
  tail: a same-id successor B published on that callback's stack is left
  untouched."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines]
            [re-frame.machines.lifecycle-fx.finalize :as rf.machines.lifecycle-fx.finalize]
            [re-frame.machines.lifecycle-fx.spawn :as rf.machines.lifecycle-fx.spawn]
            [re-frame.machines.spawn-order :as rf.machines.spawn-order]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- completion-tail fence (finalize-machine) -----------------------------

(defn- finishing-machine
  "A runtime-stamped singleton resting on a `:final?` leaf; `schema?` adds the
  `[:schemas :output]` completion validator."
  [frame-id schema?]
  (cond-> {:initial   :done
           :rf/frame  frame-id
           :rf/cofx   {:rf/time-ms 0}
           :data      {:result 42}
           :states    {:done {:final? true :output-key :result}}}
    schema? (assoc :schemas {:output ::output-schema})))

(defn- seed-finishing-snapshot! [frame-id machine-id]
  (rf.frame/swap-runtime-db!
    frame-id
    #(assoc-in % [:rf.runtime/machines :snapshots machine-id] {:state :done :data {:result 42}})))

(defn- assert-completion-fenced
  "Make frame A, then call `finalize-machine` DIRECTLY under A's bound event
  owner with a destroyer — the completion-output validator (`:validator`) or a
  `:rf.machine/done` listener (`:done-trace`) — that destroys A and publishes a
  same-id B on its own stack."
  [frame-a machine-id trigger]
  (let [schema? (= trigger :validator)]
    (rf/reg-machine machine-id (finishing-machine frame-a schema?))
    (rf/make-frame {:id frame-a})
    (seed-finishing-snapshot! frame-a machine-id)
    (let [token-a       (rf.frame/frame-incarnation-token frame-a)
          fired?        (atom false)
          destroyed     (atom [])
          orig-validate (rf.late-bind/get-fn :schemas/validate-with-registered-fn)
          destroy+B!    (fn []
                          (when (compare-and-set! fired? false true)
                            (rf.frame/destroy-frame! frame-a)
                            (rf/make-frame {:id frame-a})
                            (seed-finishing-snapshot! frame-a machine-id)))]
      (rf.trace.tooling/register-listener!
        ::completion-fence
        (fn [ev]
          (case (:operation ev)
            :rf.machine/done      (when (= trigger :done-trace) (destroy+B!))
            :rf.machine/destroyed (swap! destroyed conj ev)
            nil)))
      (try
        (when schema?
          (rf.late-bind/set-fn! :schemas/validate-with-registered-fn
            (fn [schema _result]
              (when (= schema ::output-schema) (destroy+B!))
              false)))
        (let [ret (rf.frame/call-with-event-owner-token frame-a token-a
                    #(rf.machines.lifecycle-fx.finalize/finalize-machine
                       (finishing-machine frame-a schema?)
                       machine-id frame-a (rf.machines.test-support/runtime-db frame-a)
                       {:state :done :data {:result 42}} [:some-completing-event] []))]
          ;; Without the fence finalize tears the actor down, so `ret` would
          ;; drop the snapshot and a :rf.machine/destroyed trace would fire.
          (is (= {:rf.db/runtime (rf.machines.test-support/runtime-db frame-a) :fx []} ret)
              "finalize returns the inert outcome: runtime-db untouched, no fx")
          (is (empty? @destroyed) "no :rf.machine/destroyed trace for the A-lost completion"))
        (finally
          (rf.trace.tooling/unregister-listener! ::completion-fence)
          (rf.late-bind/set-fn! :schemas/validate-with-registered-fn orig-validate))))))

(deftest completion-output-validator-loss-fences-teardown
  (assert-completion-fenced :rf2-3evq0x/completion-validator-frame
                            :rf2-3evq0x/completion-validator
                            :validator))

(deftest done-trace-listener-loss-fences-teardown
  (assert-completion-fenced :rf2-3evq0x/done-trace-frame
                            :rf2-3evq0x/done-trace
                            :done-trace))

;; ---- spawn-tail fence -----------------------------------------------------

(deftest spawn-trace-listener-loss-fences-install
  (rf.machines.spawn-order/reset-all!)
  (rf/reg-machine :rf2-3evq0x/spawn-child
    {:initial :running
     :states  {:running {:on {:go :done}}
               :done    {:final? true}}})
  (let [frame-a        :rf2-3evq0x/spawn-frame
        fired?         (atom false)
        dispatches     (atom [])
        b-birth        (atom nil)
        orig-dispatch! (rf.late-bind/get-fn :router/dispatch!)]
    (rf/make-frame {:id frame-a})
    (let [token-a (rf.frame/frame-incarnation-token frame-a)]
      (rf.trace.tooling/register-listener!
        ::spawn-trace-fence
        (fn [ev]
          (when (and (= :rf.machine.spawn/spawned (:operation ev))
                     (compare-and-set! fired? false true))
            (rf.frame/destroy-frame! frame-a)
            (rf/make-frame {:id frame-a})
            (reset! b-birth (rf.machines.test-support/runtime-db frame-a)))))
      (try
        (rf.late-bind/set-fn! :router/dispatch! (fn [ev opts] (swap! dispatches conj [ev opts]) nil))
        (rf.frame/call-with-event-owner-token frame-a token-a
          #(rf.machines.lifecycle-fx.spawn/spawn-fx {:frame frame-a}
                                                    {:machine-id :rf2-3evq0x/spawn-child :start [:go]}))
        (is (= @b-birth (rf.machines.test-support/runtime-db frame-a))
            "B's runtime-db is byte-identical to its birth value (no A-derived install)")
        (is (empty? (rf.machines.spawn-order/frame-order frame-a))
            "no A-derived spawn-order entry recorded against B")
        (is (empty? @dispatches) "no :start dispatch fired into B")
        (finally
          (rf.trace.tooling/unregister-listener! ::spawn-trace-fence)
          (when orig-dispatch!
            (rf.late-bind/set-fn! :router/dispatch! orig-dispatch!)))))))
