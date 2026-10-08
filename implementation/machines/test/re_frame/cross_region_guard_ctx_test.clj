(ns re-frame.cross-region-guard-ctx-test
  "Per Spec 005 §Cross-region coordination — tags as `stateIn`: a parallel
  region's guard reads a sibling region through the machine-wide `:tags`
  union, and its `:state` is its own region's value. `:all-state` / `:tags`
  are ctx-only keys, never committed onto the snapshot."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private snapshot rf.machines.test-support/snapshot)

(deftest spec-005-stateIn-substitute-worked-example
  (rf/reg-machine :xreg/checkout
    {:type    :parallel
     :guards  {:form-valid? (fn [{:keys [tags]}] (contains? tags :form/valid))}
     :regions {:form     {:initial :editing
                          :states  {:editing {:tags #{:form/editing} :on {:complete :valid}}
                                    :valid   {:tags #{:form/valid}}}}
               :checkout {:initial :idle
                          :states  {:idle       {:on {:submit {:target :submitting :guard :form-valid?}}}
                                    :submitting {}}}}})
  (rf/dispatch-sync [:xreg/checkout [:submit]])
  (is (= {:form :editing :checkout :idle} (:state (snapshot :xreg/checkout)))
      ":submit blocked while :form is :editing")
  (rf/dispatch-sync [:xreg/checkout [:complete]])
  (rf/dispatch-sync [:xreg/checkout [:submit]])
  (let [s (snapshot :xreg/checkout)]
    (is (= {:form :valid :checkout :submitting} (:state s)))
    (is (not (contains? s :all-state)) ":all-state is ctx-only, never committed")))

(deftest region-guard-sees-own-state
  (rf/reg-machine :xreg/own
    {:type    :parallel
     :guards  {:own-idle? (fn [{:keys [state]}] (= :idle state))}
     :regions {:a {:initial :idle
                   :states  {:idle {:on {:advance {:target :busy :guard :own-idle?}}}
                             :busy {}}}
               :b {:initial :x
                   :states  {:x {}}}}})
  (rf/dispatch-sync [:xreg/own [:advance]])
  (is (= :busy (get-in (snapshot :xreg/own) [:state :a]))
      "the guard's :state is region :a's own value, not the region map"))
