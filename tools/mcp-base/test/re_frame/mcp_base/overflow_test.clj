(ns re-frame.mcp-base.overflow-test
  "Tests for the overflow-marker shape (rf2-rvyzy / rf2-vw4sq)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.mcp-base.overflow :as overflow]
            [re-frame.mcp-base.vocab :as vocab]))

(deftest token-estimate-quarter-rule
  (is (zero? (overflow/token-estimate "")))
  (is (zero? (overflow/token-estimate "abc")))
  (is (= 1 (overflow/token-estimate "abcd")))
  (is (= 25 (overflow/token-estimate (apply str (repeat 100 \x))))))

(deftest overflow-payload-shape
  (let [payload (overflow/overflow-payload {:tool "snapshot" :token-count 6000 :cap 5000})]
    (is (contains? payload vocab/overflow-key))
    (let [body (get payload vocab/overflow-key)]
      (is (= :reached (:limit body)))
      (is (= 6000 (:token-count body)))
      (is (= 5000 (:cap-tokens body)))
      (is (= "snapshot" (:tool body)))
      (is (string? (:hint body))))))

(deftest overflow-payload-uses-fallback-hint-when-absent
  (let [p (overflow/overflow-payload {:tool "unknown-tool" :token-count 6000 :cap 5000})]
    (is (= overflow/overflow-hint-fallback
           (get-in p [vocab/overflow-key :hint])))))

(deftest overflow-payload-uses-explicit-hint
  (let [p (overflow/overflow-payload {:tool "snapshot" :token-count 6000 :cap 5000
                                      :hint "tighten the path"})]
    (is (= "tighten the path"
           (get-in p [vocab/overflow-key :hint])))))

(deftest default-max-tokens-pinned
  (is (= 5000 overflow/default-max-tokens)))
