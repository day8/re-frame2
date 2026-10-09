(ns re-frame.schemas-walker-literal-operand-cljs-test
  "CLJS half of the opacity walk's host parity: the shared corpus classifies
  here exactly as on the JVM, compiled Malli values included."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.walker-literal-operand-fixtures :as fixtures]))

(deftest cljs-opacity-walk-classifies-the-shared-corpus
  (doseq [s fixtures/not-opaque-forms]
    (is (false? (rf.schemas/schema-has-opaque-child? s)) (pr-str s)))
  (doseq [s (concat fixtures/opaque-forms fixtures/registry-ref-forms)]
    (is (true? (rf.schemas/schema-has-opaque-child? s)) (pr-str s))))
