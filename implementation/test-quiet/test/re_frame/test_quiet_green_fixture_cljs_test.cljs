(ns re-frame.test-quiet-green-fixture-cljs-test
  "Two green vars distinguish namespace selection from qualified selection."
  (:require [cljs.test :refer-macros [deftest is]]))

(deftest a-passing-test
  (is (= 1 1)))

(deftest another-passing-test
  (is (= 2 2)))
