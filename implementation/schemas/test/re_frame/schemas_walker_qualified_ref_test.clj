(ns re-frame.schemas-walker-qualified-ref-test
  "The qualified-reference predicate the off-box HTTP export fails closed on: a
  schema naming a registry schema by qualified keyword, in a real child
  position or as an implicit `:map` entry, answers true; data positions and
  unqualified names answer false. The opacity walk does not consult it, so
  validation surfaces keep such a reference walkable. The CLJS half asserts the
  same shared corpus."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.walker :as rf.schemas.walker]
            [re-frame.schemas.walker-literal-operand-fixtures :as rf.schemas.walker-literal-operand-fixtures]
            [re-frame.schemas.walker-qualified-ref-fixtures :as rf.schemas.walker-qualified-ref-fixtures]))

(deftest qualified-ref-walk-classifies-the-shared-corpus
  (doseq [s rf.schemas.walker-qualified-ref-fixtures/qualified-ref-forms]
    (is (true? (rf.schemas.walker/schema-has-qualified-ref? s)) (pr-str s)))
  (doseq [s rf.schemas.walker-qualified-ref-fixtures/no-qualified-ref-forms]
    (is (false? (rf.schemas.walker/schema-has-qualified-ref? s)) (pr-str s))))

(deftest opaque-forms-answer-without-throwing
  (doseq [s (concat rf.schemas.walker-literal-operand-fixtures/opaque-forms
                    rf.schemas.walker-literal-operand-fixtures/registry-ref-forms)]
    (is (boolean? (rf.schemas.walker/schema-has-qualified-ref? s)) (pr-str s))))

(deftest a-qualified-reference-stays-walkable-for-validation
  (is (false? (rf.schemas/schema-has-opaque-child? [:map [:user :fixture/user]]))))
