(ns re-frame.spawn-destroyed-frame-atomicity-test
  "A spawn into a destroyed or never-created frame is a clean no-op: no
  `:rf.machine.spawn/spawned` trace, no install, no `:start` dispatch, nil
  returned."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.lifecycle-fx.spawn :as rf.machines.lifecycle-fx.spawn]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

;; Wires the machines registration hook when this ns runs alone.
(def ^:private _artefact rf.machines/machine-transition)

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(deftest destroyed-frame-spawn-fires-no-trace-no-dispatch
  (rf/reg-machine :rf2-g13nm2/ghost-child
    {:initial :running
     :data    {}
     :states  {:running {:on {:go :done}}
               :done    {:final? true}}})
  (let [dispatches     (atom [])
        ghost-frame    :rf2-g13nm2/never-created-frame
        orig-dispatch! (rf.late-bind/get-fn :router/dispatch!)]
    ;; Capture instead of route: a `[nil <start>]` dispatch would blow up downstream.
    (rf.late-bind/set-fn! :router/dispatch! (fn [ev opts] (swap! dispatches conj [ev opts]) nil))
    (try
      (let [ret (rf.machines.lifecycle-fx.spawn/spawn-fx {:frame ghost-frame}
                                                         {:machine-id :rf2-g13nm2/ghost-child
                                                          :start      [:go]})]
        (is (= [nil [] [] nil]
               [ret
                (rf.machines.test-support/events-of :rf.machine.spawn/spawned)
                @dispatches
                (get-in (:rf.db/runtime (rf/frame-state-value ghost-frame))
                        [:rf.runtime/machines :snapshots])])))
      (finally
        (when orig-dispatch!
          (rf.late-bind/set-fn! :router/dispatch! orig-dispatch!))))))
