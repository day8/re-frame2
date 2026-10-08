(ns re-frame.machine-action-throw-rollback-test
  "A throwing pre-commit machine action emits one frame-tagged
  `:rf.error/machine-action-exception`, never the generic
  `:rf.error/handler-exception`, and commits no transition."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            ;; Installs the late-bind hooks `rf/reg-machine` resolves through.
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest throwing-entry-routes-to-machine-action-exception-and-rolls-back
  (rf/reg-machine :sup/entry-throw
    {:initial :idle
     :actions {:boom (fn [_] (throw (ex-info "entry boom" {:why :test})))}
     :states  {:idle    {:on {:start :working}}
               :working {:entry :boom}}})
  (rf.machines.test-support/with-trace-capture traces
    (rf/dispatch-sync [:sup/entry-throw [:start]])
    (is (= [[[:sup/entry-throw :rf/default true]] [] true]
           [(for [ev    @traces
                  :when (= [:error :rf.error/machine-action-exception] ((juxt :op-type :operation) ev))]
              ((juxt :actor-id :frame (comp some? :exception)) (:tags ev)))
            (filterv #(= :rf.error/handler-exception (:operation %)) @traces)
            ;; A machine born by this event rolls back to no snapshot at all.
            (contains? #{nil :idle} (:state (rf.machines.test-support/snapshot :sup/entry-throw)))]))))
