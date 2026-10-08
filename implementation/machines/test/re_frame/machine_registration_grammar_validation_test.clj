(ns re-frame.machine-registration-grammar-validation-test
  "Two grammar rules `validate-machine!` enforces at REGISTRATION rather
  than leaving to fail at dispatch:

    - HISTORY PLACEMENT — a `:type :history` node MUST have an owning
      compound (Spec 005 §History states); on the machine root or a flat
      top-level state it is `:rf.error/machine-history-misplaced`.
    - `:after` REFS — a dangling `:guard` / `:action` keyword on an `:after`
      transition is `:rf.error/machine-unresolved-guard` /
      `:rf.error/machine-unresolved-action` (Spec 005:1334)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- refusal [machine]
  (try (rf.machines/validate-machine! machine) nil
       (catch clojure.lang.ExceptionInfo ex (ex-data ex))))

(deftest history-misplaced-rejected-at-registration
  (doseq [bad [{:type :history :initial :a :states {:a {}}}
               {:initial :a :states {:a {} :h {:type :history}}}]]
    (is (= {:rf.error/id :rf.error/machine-history-misplaced :feature :history}
           (select-keys (refusal bad) [:rf.error/id :feature]))
        (pr-str bad))))

(deftest after-guard-action-refs-validated-at-registration
  (is (= [:rf.error/machine-unresolved-guard :rf.error/machine-unresolved-action]
         (mapv #(:rf.error/id (refusal {:initial :a
                                        :states  {:a {:after {1000 (merge {:target :b} %)}}
                                                  :b {}}}))
               [{:guard :missing?} {:action :nope}]))))
