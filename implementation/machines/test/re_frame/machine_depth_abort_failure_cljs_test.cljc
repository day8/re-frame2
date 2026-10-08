(ns re-frame.machine-depth-abort-failure-cljs-test
  "A runaway `:raise` cycle that trips the depth limit is a FAILED macrostep: one
  error-grade depth-exceeded trace, no benign no-op, and the snapshot rolled back."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            ;; Installs the late-bind hooks + reserved fxs `reg-machine` relies on.
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            #?(:clj  [re-frame.substrate.plain-atom :as substrate-adapter]
               :cljs [re-frame.adapter.reagent :as substrate-adapter]))
  ;; `with-trace-capture` is a `#?(:clj (defmacro …))` in the `.cljc` support ns.
  #?(:cljs (:require-macros [re-frame.machines.test-support :as rf.machines.test-support])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter substrate-adapter/adapter}))

(deftest raise-cycle-surfaces-as-failed-macrostep-not-no-op
  (rf/reg-machine :rf2-y3jv8q/raise
    {:initial           :start
     :data              {}
     :raise-depth-limit 5
     :actions           {:tick (fn [{:keys [data]}] {:data data :fx [[:raise [:tick]]]})}
     :states            {:start {:on {:go   {:action :tick}
                                      :tick {:action :tick}}}}})
  (rf/dispatch-sync [:rf2-y3jv8q/raise [:rf.machine/start]])
  (let [evs      (rf.machines.test-support/with-trace-capture seen
                   (rf/dispatch-sync [:rf2-y3jv8q/raise [:go]])
                   @seen)
        op-types (fn [op] (into [] (comp (filter #(= op (:operation %))) (map :op-type)) evs))]
    (is (= [[:error] [] :start]
           [(op-types :rf.error/machine-raise-depth-exceeded)
            (op-types :rf.machine.event/unhandled-no-op)
            (rf.machines.test-support/machine-state :rf2-y3jv8q/raise)]))))
