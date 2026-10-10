(ns re-frame.schemas-walker-qualified-ref-cljs-test
  "CLJS half of the qualified-reference predicate's host parity: the shared
  corpus classifies here exactly as on the JVM."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.walker :as rf.schemas.walker]
            [re-frame.schemas.walker-literal-operand-fixtures :as rf.schemas.walker-literal-operand-fixtures]
            [re-frame.schemas.walker-qualified-ref-fixtures :as rf.schemas.walker-qualified-ref-fixtures]))

(deftest cljs-qualified-ref-walk-classifies-the-shared-corpus
  (doseq [s rf.schemas.walker-qualified-ref-fixtures/qualified-ref-forms]
    (is (true? (rf.schemas.walker/schema-has-qualified-ref? s)) (pr-str s)))
  (doseq [s rf.schemas.walker-qualified-ref-fixtures/no-qualified-ref-forms]
    (is (false? (rf.schemas.walker/schema-has-qualified-ref? s)) (pr-str s))))

(deftest cljs-opaque-forms-answer-without-throwing
  (doseq [s (concat rf.schemas.walker-literal-operand-fixtures/opaque-forms
                    rf.schemas.walker-literal-operand-fixtures/registry-ref-forms)]
    (is (boolean? (rf.schemas.walker/schema-has-qualified-ref? s)) (pr-str s))))

(deftest cljs-a-qualified-reference-stays-walkable-for-validation
  (is (false? (rf.schemas/schema-has-opaque-child? [:map [:user :fixture/user]]))))
