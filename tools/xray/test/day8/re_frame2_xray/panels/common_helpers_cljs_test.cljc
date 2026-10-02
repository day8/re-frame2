(ns day8.re-frame2-xray.panels.common-helpers-cljs-test
  "Tests for the shared panel-helper surfaces — the 200-row cap and
  the trace-event tag reader.

  Dual-target naming (`.cljc` + `_cljs_test`) — same pattern as every
  other panel-helper test:

    - Cognitect's test-runner picks it up via `.*-test$`.
    - Shadow's `:node-test` build picks it up via `cljs-test$`."
  (:require #?(:clj  [clojure.test :refer [are deftest is testing]]
               :cljs [cljs.test    :refer-macros [are deftest is testing]])
            [day8.re-frame2-xray.panels.common-helpers :as common]))

;; ---- tag-of -------------------------------------------------------------

(deftest tag-of-reads-tags-slot
  (is (= :ok   (common/tag-of {:tags {:k :ok}} :k)))
  (is (= :ok   (common/tag-of {:k :ok} :k))  ; flat fallback for tests
      "flat shape falls through — tolerant for test fixtures")
  (is (nil?    (common/tag-of {} :missing))))

(deftest tag-of-keeps-a-false-tag-value
  (testing "`false` under `:tags` is a value, not 'absent'"
    (is (false? (common/tag-of {:tags {:k false}} :k)))
    (is (false? (common/tag-of {:tags {:k false} :k :flat} :k))
        "the flat fallback does not shadow a present false tag")
    (is (false? (common/tag-of {:k false} :k))
        "a flat false survives too"))
  (testing "the fallback covers a nil or absent tag"
    (is (= :flat (common/tag-of {:tags {:k nil} :k :flat} :k)))
    (is (= :flat (common/tag-of {:tags {} :k :flat} :k)))
    (is (true? (common/tag-of {:tags {:k true}} :k)))
    (is (= 0 (common/tag-of {:tags {:k 0}} :k)))))

;; ---- panel-row-cap ------------------------------------------------------

(deftest panel-row-cap-is-200
  (testing "the cap matches spec/007-UX-IA.md §Performance budget"
    (is (= 200 common/panel-row-cap))))

;; ---- cap-rows -----------------------------------------------------------

(deftest cap-rows-keeps-the-head-and-reports-what-it-hides
  ;; `[capped over-cap? hidden]` either side of the default cap. Whole-value
  ;; equality pins head retention and that rows pass through unchanged.
  (let [rows (fn [n] (mapv (fn [i] {:id i}) (range n)))
        cap  common/panel-row-cap]
    (are [input expected] (= expected (common/cap-rows input))
      (rows 50)          [(rows 50) false 0]
      (rows cap)         [(rows cap) false 0]     ; exactly at the cap
      (rows (+ 50 cap))  [(rows cap) true 50]
      nil                [[] false 0]
      []                 [[] false 0])))

(deftest cap-rows-with-explicit-cap
  (let [rows (mapv (fn [i] {:id i}) (range 20))
        [capped over-cap? hidden] (common/cap-rows rows 5)]
    (is (= 5 (count capped)))
    (is (true? over-cap?))
    (is (= 15 hidden))))

(deftest cap-rows-is-pure
  (testing "repeat invocations on the same input produce equal output"
    (let [rows (mapv (fn [i] {:id i}) (range 300))]
      (is (= (common/cap-rows rows)
             (common/cap-rows rows))))))
