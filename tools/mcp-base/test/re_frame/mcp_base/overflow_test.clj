(ns re-frame.mcp-base.overflow-test
  "Tests for the token-estimate rule and the default cap. The marker
  shape is pinned through `apply-cap` in cap_test."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.mcp-base.overflow :as rf.mcp-base.overflow]))

(deftest token-estimate-quarter-rule
  (is (zero? (rf.mcp-base.overflow/token-estimate "")))
  (is (zero? (rf.mcp-base.overflow/token-estimate "abc")))
  (is (= 1 (rf.mcp-base.overflow/token-estimate "abcd")))
  (is (= 25 (rf.mcp-base.overflow/token-estimate (apply str (repeat 100 \x))))))

(deftest default-max-tokens-pinned
  (is (= 5000 rf.mcp-base.overflow/default-max-tokens)))
