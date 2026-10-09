(ns re-frame.schemas.digest-parity-cljs-test
  "CLJS half of the app-schemas-digest cross-runtime parity: the same fixtures
  and literals as `re-frame.schemas.digest-parity-test`, through this host's
  `goog.crypt` SHA-256 and UTF-8 encoding."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame.schemas.digest-parity-fixtures :as rf.schemas.digest-parity-fixtures]
            [re-frame.schemas.validator :as rf.schemas.validator]))

(deftest cljs-digest-matches-canonical-literal
  (doseq [{:keys [label input expected]} rf.schemas.digest-parity-fixtures/all-fixtures]
    (is (= expected (rf.schemas.digest-parity-fixtures/compute-digest input)) label)))

(deftest cljs-fn-bearing-schema-digest-is-process-stable
  (testing "a bare predicate serialises to its name-derived `#fn` token, which
            still tells two predicates apart; this host names the function
            differently from the JVM, so no shared literal is pinned"
    (let [bytes (rf.schemas.validator/run-printer rf.schemas.digest-parity-fixtures/fn-bearing-schema)]
      (is (str/includes? bytes "#fn") bytes)
      (is (not= bytes (rf.schemas.validator/run-printer rf.schemas.digest-parity-fixtures/fn-bearing-other-schema))
          bytes))))
