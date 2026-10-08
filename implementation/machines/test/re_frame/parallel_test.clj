(ns re-frame.parallel-test
  "Registration refuses a malformed `:type :parallel` machine (Spec 005
  §Parallel regions)."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.machines :as rf.machines]))

(defn- refusal-id [machine]
  (try (rf.machines/make-machine-handler machine) :registered
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
              {:type :parallel :regions {:r {:initial :c :states {:c {:type :parallel :regions {:in region}}}}}}]]]
      (is (= expected (refusal-id machine)) (pr-str machine)))))
