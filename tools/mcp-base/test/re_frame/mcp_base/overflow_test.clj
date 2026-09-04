(ns re-frame.mcp-base.overflow-test
  "Tests for the overflow-marker shape."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.mcp-base.overflow :as rf.mcp-base.overflow]
            [re-frame.mcp-base.vocab :as rf.mcp-base.vocab]))

(deftest token-estimate-quarter-rule
  (is (zero? (rf.mcp-base.overflow/token-estimate "")))
  (is (zero? (rf.mcp-base.overflow/token-estimate "abc")))
  (is (= 1 (rf.mcp-base.overflow/token-estimate "abcd")))
  (is (= 25 (rf.mcp-base.overflow/token-estimate (apply str (repeat 100 \x))))))

(deftest overflow-payload-shape
  (let [payload (rf.mcp-base.overflow/overflow-payload {:tool "snapshot" :token-count 6000 :cap 5000})]
    (is (contains? payload rf.mcp-base.vocab/overflow-key))
    (let [body (get payload rf.mcp-base.vocab/overflow-key)]
      (is (= :reached (:limit body)))
      (is (= 6000 (:token-count body)))
      (is (= 5000 (:cap-tokens body)))
      (is (= "snapshot" (:tool body)))
      (is (string? (:hint body))))))

(deftest overflow-payload-uses-fallback-hint-when-absent
  (let [p (rf.mcp-base.overflow/overflow-payload {:tool "unknown-tool" :token-count 6000 :cap 5000})]
    (is (= rf.mcp-base.overflow/overflow-hint-fallback
           (get-in p [rf.mcp-base.vocab/overflow-key :hint])))))

(deftest overflow-payload-uses-explicit-hint
  (let [p (rf.mcp-base.overflow/overflow-payload {:tool "snapshot" :token-count 6000 :cap 5000
                                      :hint "tighten the path"})]
    (is (= "tighten the path"
           (get-in p [rf.mcp-base.vocab/overflow-key :hint])))))

(deftest default-max-tokens-pinned
  (is (= 5000 rf.mcp-base.overflow/default-max-tokens)))
