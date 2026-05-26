(ns day8.re-frame2-xray.panels.epoch.badge-cljs-test
  "Pure-data tests for the Epoch panel's badge taxonomy (rf2-sc3r1).

  ## Under test

    1. Every badge in `projection/badge-set` resolves to a non-blank
       CSS-variable string via `badge/colour`.
    2. Every badge resolves to a non-blank uppercase label via
       `badge/label`.
    3. `token-key` produces a known theme-token keyword for every
       badge.
    4. Fibonacci spacing scale produces stable px strings."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [day8.re-frame2-xray.panels.epoch.badge :as badge]
            [day8.re-frame2-xray.panels.epoch.projection :as proj]))

(deftest badge-colours-resolve-test
  (testing "every badge in the inventory resolves to a non-blank CSS-var string"
    (doseq [b proj/badge-set]
      (let [c (badge/colour b)]
        (is (string? c) (str "colour for " b))
        (is (re-find #"var\(--rf-xray-" c)
            (str "expected CSS variable for " b ", got " c))))))

(deftest badge-labels-uppercase-test
  (testing "every badge label is the uppercase keyword name"
    (doseq [b proj/badge-set]
      (let [l (badge/label b)]
        (is (string? l))
        (is (= (str/upper-case l) l)
            (str "badge label for " b " not uppercase: " l))))))

(deftest token-key-fallback-test
  (testing "unknown badge falls back to :text-tertiary"
    (is (= :text-tertiary (badge/token-key :NOT-A-BADGE))))
  (testing "known badges resolve to specific token keys"
    (is (= :accent (badge/token-key :HANDLER)))
    (is (= :accent (badge/token-key :FLOW)))
    (is (= :orange (badge/token-key :FX)))
    (is (= :success (badge/token-key :VIEWS)))))

(deftest fib-px-test
  (testing "fibonacci helper resolves to px strings"
    (is (= "3px"  (badge/fib-px :f3)))
    (is (= "5px"  (badge/fib-px :f5)))
    (is (= "8px"  (badge/fib-px :f8)))
    (is (= "13px" (badge/fib-px :f13)))
    (is (= "21px" (badge/fib-px :f21)))
    (is (= "34px" (badge/fib-px :f34)))
    (is (= "55px" (badge/fib-px :f55)))
    (is (= "89px" (badge/fib-px :f89))))
  (testing "unknown key returns '0'"
    (is (= "0" (badge/fib-px :nope)))))

(deftest numbered-cascade-geometry-test
  (testing "the geometry constants exposed for the view are the ones the spec commits to"
    (is (= 21  badge/step-numbered-circle-diameter-px))
    (is (= 13  badge/vertical-line-offset-px))
    (is (= -44 badge/circle-left-offset-px))
    (is (= -34 badge/line-left-offset-px))))
