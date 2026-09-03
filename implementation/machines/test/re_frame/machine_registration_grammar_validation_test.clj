(ns re-frame.machine-registration-grammar-validation-test
  "Two grammar rules `validate-machine!` enforces at REGISTRATION rather
  than leaving to fail at dispatch:

    - HISTORY PLACEMENT — history is first-class (Spec 005 §History states),
      but a `:type :history` node MUST have an owning compound. A machine
      root, or a flat top-level state, carrying `:type :history` is rejected
      with `:rf.error/machine-history-misplaced`.
    - `:after` REFS — a dangling `:guard` / `:action` keyword on an `:after`
      transition is rejected with `:rf.error/machine-unresolved-guard` /
      `:rf.error/machine-unresolved-action` (Spec 005:1334).

  Each rule is pinned in both directions: the malformed declaration is
  rejected, and a well-formed one validates silently."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- history placement --------------------------------------------------

(deftest history-misplaced-rejected-at-registration
  (testing "a `:type :history` node with NO owning compound (machine root,
   or a flat top-level state) is rejected with
   :rf.error/machine-history-misplaced"
    (doseq [bad [;; root `:type :history`
                 {:type :history :initial :a :states {:a {}}}
                 ;; flat top-level `:type :history` (no enclosing compound)
                 {:initial :a :states {:a {} :h {:type :history}}}]]
      (let [e (try (rf.machines/validate-machine! bad) nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (= :rf.error/machine-history-misplaced
               (:rf.error/id (ex-data e)))
            (str "rejected: " (pr-str bad)))
        (is (= :history (:feature (ex-data e))) ":feature names the history grammar"))))
  (testing "a WELL-PLACED `:type :history` node (inside a compound's :states)
   validates silently"
    (is (nil? (rf.machines/validate-machine!
                {:initial :c
                 :states  {:c {:initial :a
                               :states  {:a {} :h {:type :history :deep? true}}}}}))))
  (testing "a well-formed history-free hierarchical machine validates silently"
    (is (nil? (rf.machines/validate-machine!
                {:initial :p
                 :states  {:p {:initial :a :states {:a {} :b {}}}}})))))

;; ---- `:after` guard / action refs ----------------------------------------

(deftest after-guard-action-refs-validated-at-registration
  (testing "a dangling :after :guard ref fails registration (not at runtime)"
    (let [e (try (rf.machines/validate-machine!
                   {:initial :a
                    :states  {:a {:after {1000 {:target :b :guard :missing?}}}
                              :b {}}})
                 nil (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :rf.error/machine-unresolved-guard (:rf.error/id (ex-data e)))))
    (let [e (try (rf.machines/validate-machine!
                   {:initial :a
                    :states  {:a {:after {1000 {:target :b :action :nope}}}
                              :b {}}})
                 nil (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :rf.error/machine-unresolved-action (:rf.error/id (ex-data e)))))))
