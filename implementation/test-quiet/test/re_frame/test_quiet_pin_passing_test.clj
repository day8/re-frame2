(ns re-frame.test-quiet-pin-passing-test
  "Known-green target for the stdout pin."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.test-quiet]))

(deftest trivially-passing
  (is (= 1 1)))
