(ns re-frame.flows-vector-parent-vacation-test
  "A flow output under a vector parent is vacated by `assoc`-ing nil at its
  index (Spec 013 §clear-flow cleanup): a `dissoc` would shift every later
  element, and leaving a non-map parent unchanged would strand the derived
  value with no live flow. Clear and the re-registration path move share
  the one vacation helper."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest clear-flow-vacates-vector-index-output
  (rf/reg-event :seed (fn [_ _] {:db {:cells [10 20 30 40] :src 99}}))
  (rf/reg-flow :cell3 {:inputs [[:src]] :output-path [:cells 3]} identity)
  (rf/dispatch-sync [:seed])
  (is (= [10 20 30 99] (:cells (rf/app-db-value :rf/default))))
  (rf/clear :flow :cell3)
  (is (= [[10 20 30 nil] {}]
         [(:cells (rf/app-db-value :rf/default)) (rf.flows/flows-snapshot)])))
