(ns re-frame.parallel-test
  "Registration refuses a malformed `:type :parallel` machine, and a
  `:type :parallel` state anywhere below the machine root (Spec 005 §Parallel
  regions: parallel is root-only)."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.machines]
            [re-frame.machines.lifecycle-fx.registration :as rf.machines.lifecycle-fx.registration]))

(defn- refusal-id [machine]
  (try (rf.machines.lifecycle-fx.registration/make-machine-handler machine) :registered
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

(deftest parallel-registration-time-validation
  (let [region {:initial :s :states {:s {}}}]
    (doseq [[expected machine]
            [[:rf.error/machine-parallel-bad-shape {:type :parallel}]
             [:rf.error/machine-parallel-bad-shape {:type :parallel :initial :foo :regions {:r region}}]
             [:rf.error/machine-parallel-bad-shape {:type :parallel :regions {:r {:states {:s {}}}}}]
             [:rf.error/machine-parallel-nested-not-supported
              {:type :parallel :regions {:outer {:type :parallel :regions {:inner region}}}}]
             [:rf.error/machine-parallel-nested-not-supported
              {:type :parallel :regions {:r {:initial :c :states {:c {:type :parallel :regions {:in region}}}}}}]
             [:rf.error/machine-parallel-nested-not-supported
              {:initial :a :states {:a {} :p {:type :parallel :regions {:r region}}}}]
             [:rf.error/machine-parallel-nested-not-supported
              {:initial :a :states {:a {:initial :p :states {:p {:type :parallel :regions {:r region}}}}}}]]]
      (is (= expected (refusal-id machine)) (pr-str machine)))))
