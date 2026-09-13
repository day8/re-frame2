(ns re-frame.schemas-sequence-width-cljs-test
  "CLJS host half of the variable-width `:cat` / `:catn` redaction pins
  (rf2-gwye.11 / rf2-fzbj.25). Asserts the SAME shared corpus
  (`re-frame.schemas.sequence-width-fixtures`) end to end through
  `validate-app-schema!` and the real trace recorder; the JVM half lives in
  `re-frame.schemas-sensitive-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.sequence-width-fixtures :as rf.schemas.sequence-width-fixtures]
            [re-frame.test-support :as rf.test-support])
  (:require-macros [re-frame.test-support :refer [with-trace-recorder!]]))

;; Adapter-less, as in the security redaction suites: `validate-app-schema!`
;; is called directly, so a clean app-schema slate plus a carried
;; `:rf/default` scope is all the harness needs.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:clear-app-schemas? true})
  (fn [test-fn]
    (binding [rf.frame/*current-frame* :rf/default]
      (test-fn))))

(defn- failure-trace [schema value]
  (rf/reg-app-schema [:items] schema)
  (with-trace-recorder! [traces]
    (rf.schemas/validate-app-schema! {:items value} :items/bad)
    (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                   @traces))))

(deftest cljs-variable-width-sequence-never-leaks-sensitive-value
  (testing "rf2-gwye.11 — a regex element before a sensitive payload no longer
            misaligns the redaction decision"
    (doseq [{:keys [desc schema value]} rf.schemas.sequence-width-fixtures/leak-cases]
      (let [v (failure-trace schema value)]
        (is (some? v) (str desc " — a trace fired"))
        (is (true? (:sensitive? v)) (str desc " — :sensitive? stamped"))
        (is (= :rf/redacted (-> v :tags :value)) (str desc " — :value redacted"))
        (is (not (str/includes? (pr-str v) rf.schemas.sequence-width-fixtures/secret))
            (str desc " — the secret is in no trace slot"))))))

(deftest cljs-variable-width-fix-keeps-non-sensitive-values
  (testing "rf2-gwye.11 control — non-sensitive failures still report their value"
    (doseq [{:keys [desc schema value expected]} rf.schemas.sequence-width-fixtures/precise-cases]
      (let [v (failure-trace schema value)]
        (is (some? v) (str desc " — a trace fired"))
        (is (= expected (-> v :tags :value)) desc)))))
