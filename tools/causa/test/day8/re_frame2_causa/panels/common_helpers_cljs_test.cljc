(ns day8.re-frame2-causa.panels.common-helpers-cljs-test
  "Tests for the shared panel-helper surfaces — the 200-row cap and
  the trace-event tag reader (rf2-1k5r1).

  Dual-target naming (`.cljc` + `_cljs_test`) — same pattern as every
  other panel-helper test:

    - Cognitect's test-runner picks it up via `.*-test$`.
    - Shadow's `:node-test` build picks it up via `cljs-test$`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.common-helpers :as common]))

;; ---- tag-of -------------------------------------------------------------

(deftest tag-of-reads-tags-slot
  (is (= :ok   (common/tag-of {:tags {:k :ok}} :k)))
  (is (= :ok   (common/tag-of {:k :ok} :k))  ; flat fallback for tests
      "flat shape falls through — tolerant for test fixtures")
  (is (nil?    (common/tag-of {} :missing))))

;; ---- panel-row-cap ------------------------------------------------------

(deftest panel-row-cap-is-200
  (testing "the cap matches spec/007-UX-IA.md §Performance budget"
    (is (= 200 common/panel-row-cap))))

;; ---- cap-rows -----------------------------------------------------------

(deftest cap-rows-under-cap-passes-through-with-flag-false
  (let [rows (mapv (fn [i] {:id i}) (range 50))
        [capped over-cap? hidden] (common/cap-rows rows)]
    (is (= rows capped))
    (is (false? over-cap?))
    (is (zero?  hidden))))

(deftest cap-rows-at-exact-cap-passes-through
  (let [rows (mapv (fn [i] {:id i}) (range common/panel-row-cap))
        [capped over-cap? hidden] (common/cap-rows rows)]
    (is (= common/panel-row-cap (count capped)))
    (is (false? over-cap?))
    (is (zero?  hidden))))

(deftest cap-rows-over-cap-truncates-and-reports-hidden
  (let [rows (mapv (fn [i] {:id i}) (range (+ 50 common/panel-row-cap)))
        [capped over-cap? hidden] (common/cap-rows rows)]
    (is (= common/panel-row-cap (count capped)))
    (is (true? over-cap?))
    (is (= 50 hidden))
    (testing "head-retained: the first row of the input is the first row out"
      (is (= {:id 0} (first capped))))))

(deftest cap-rows-with-explicit-cap
  (let [rows (mapv (fn [i] {:id i}) (range 20))
        [capped over-cap? hidden] (common/cap-rows rows 5)]
    (is (= 5 (count capped)))
    (is (true? over-cap?))
    (is (= 15 hidden))))

(deftest cap-rows-nil-and-empty-tolerant
  (testing "nil rows → empty capped, no overflow"
    (let [[capped over-cap? hidden] (common/cap-rows nil)]
      (is (= [] capped))
      (is (false? over-cap?))
      (is (zero?  hidden))))
  (testing "empty rows → empty capped, no overflow"
    (let [[capped over-cap? hidden] (common/cap-rows [])]
      (is (= [] capped))
      (is (false? over-cap?))
      (is (zero?  hidden)))))

(deftest cap-rows-preserves-row-shape
  (testing "rows pass through unchanged — no key drops or coercion"
    (let [rich [{:id 1 :payload {:deep {:value 42}}}
                {:id 2 :payload {:deep {:value 43}}}]
          [capped _ _] (common/cap-rows rich)]
      (is (= rich capped)))))

(deftest cap-rows-is-pure
  (testing "repeat invocations on the same input produce equal output"
    (let [rows (mapv (fn [i] {:id i}) (range 300))]
      (is (= (common/cap-rows rows)
             (common/cap-rows rows))))))
