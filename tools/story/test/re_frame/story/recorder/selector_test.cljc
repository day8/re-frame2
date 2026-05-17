(ns re-frame.story.recorder.selector-test
  "Pure unit tests for the recorder's selector picker (rf2-d5u89).

  Covers:
  - Priority tiers (data-test > id > aria-label > nth-of-type).
  - Attribute-value escaping (backslash + double-quote).
  - Nth-of-type fallback geometry.
  - `selector-kind` diagnostic returns the right tier."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.recorder.selector :as sel]))

;; ---- priority tiers ------------------------------------------------------

(deftest data-test-wins
  (testing "data-test attribute beats id / aria-label / nth-of-type"
    (is (= "[data-test=\"submit-btn\"]"
           (sel/pick-selector
             {:tag "button"
              :attrs {"data-test"  "submit-btn"
                      "id"         "btn-1"
                      "aria-label" "Submit"}
              :index-of-type 3})))))

(deftest id-when-no-data-test
  (testing "id wins when data-test is absent"
    (is (= "[id=\"counter-input\"]"
           (sel/pick-selector
             {:tag "input"
              :attrs {"id"         "counter-input"
                      "aria-label" "Counter"}
              :index-of-type 2})))))

(deftest aria-label-when-no-data-test-or-id
  (testing "aria-label wins when data-test and id are absent"
    (is (= "[aria-label=\"Close dialog\"]"
           (sel/pick-selector
             {:tag "button"
              :attrs {"aria-label" "Close dialog"}
              :index-of-type 1})))))

(deftest nth-of-type-fallback
  (testing "nth-of-type fires when no attribute matches"
    (is (= "button:nth-of-type(3)"
           (sel/pick-selector
             {:tag "BUTTON"
              :attrs {}
              :index-of-type 3})))))

(deftest nth-of-type-falls-back-to-star
  (testing "nth-of-type uses * when tag is missing/blank"
    (is (= "*:nth-of-type(2)"
           (sel/pick-selector
             {:tag nil
              :attrs {}
              :index-of-type 2})))))

(deftest returns-nil-when-nothing-usable
  (testing "no attributes + no index → nil"
    (is (nil? (sel/pick-selector {:tag "div" :attrs {} :index-of-type nil})))
    (is (nil? (sel/pick-selector {})))))

(deftest blank-attribute-values-are-ignored
  (testing "blank / whitespace attribute values fall through to next tier"
    (is (= "[id=\"x\"]"
           (sel/pick-selector
             {:tag "input"
              :attrs {"data-test" "   "  ; blank, skip
                      "id"        "x"}})))
    (is (= "*:nth-of-type(1)"
           (sel/pick-selector
             {:tag nil
              :attrs {"data-test" ""
                      "id"        nil
                      "aria-label" "  "}
              :index-of-type 1})))))

;; ---- escaping ------------------------------------------------------------

(deftest escapes-double-quotes
  (testing "double-quote inside value is escaped"
    (is (= "[id=\"he said \\\"hi\\\"\"]"
           (sel/pick-selector
             {:tag "div"
              :attrs {"id" "he said \"hi\""}})))))

(deftest escapes-backslashes
  (testing "backslash inside value is escaped"
    (is (= "[id=\"a\\\\b\"]"
           (sel/pick-selector
             {:tag "div"
              :attrs {"id" "a\\b"}})))))

;; ---- selector-kind diagnostic --------------------------------------------

(deftest selector-kind-reports-tier
  (is (= :data-test  (sel/selector-kind {:attrs {"data-test" "x"}})))
  (is (= :id         (sel/selector-kind {:attrs {"id" "x"}})))
  (is (= :aria-label (sel/selector-kind {:attrs {"aria-label" "x"}})))
  (is (= :nth-of-type (sel/selector-kind {:attrs {} :index-of-type 2})))
  (is (= :none       (sel/selector-kind {:attrs {} :index-of-type nil}))))

;; ---- attribute-priority is the documented data ---------------------------

(deftest priority-list-is-data-test-id-aria-label
  (testing "the priority list order matches the documented contract"
    (is (= ["data-test" "id" "aria-label"]
           (mapv first sel/attribute-priority)))))
