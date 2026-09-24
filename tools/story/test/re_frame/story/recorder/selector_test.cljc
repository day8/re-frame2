(ns re-frame.story.recorder.selector-test
  "Pure unit tests for the recorder's selector picker (rf2-d5u89).

  Covers:
  - Priority tiers (data-test > id > aria-label > nth-of-type).
  - Attribute-value escaping (backslash + double-quote).
  - Nth-of-type fallback geometry."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.recorder.selector :as rf.story.recorder.selector]))

;; ---- priority tiers ------------------------------------------------------

(deftest data-test-wins
  (testing "data-test attribute beats id / aria-label / nth-of-type"
    (is (= "[data-test=\"submit-btn\"]"
           (rf.story.recorder.selector/pick-selector
             {:tag "button"
              :attrs {"data-test"  "submit-btn"
                      "id"         "btn-1"
                      "aria-label" "Submit"}
              :index-of-type 3})))))

(deftest id-when-no-data-test
  (testing "id wins when data-test is absent"
    (is (= "[id=\"counter-input\"]"
           (rf.story.recorder.selector/pick-selector
             {:tag "input"
              :attrs {"id"         "counter-input"
                      "aria-label" "Counter"}
              :index-of-type 2})))))

(deftest aria-label-when-no-data-test-or-id
  (testing "aria-label wins when data-test and id are absent"
    (is (= "[aria-label=\"Close dialog\"]"
           (rf.story.recorder.selector/pick-selector
             {:tag "button"
              :attrs {"aria-label" "Close dialog"}
              :index-of-type 1})))))

(deftest nth-of-type-fallback
  (testing "nth-of-type fires when no attribute matches"
    (is (= "button:nth-of-type(3)"
           (rf.story.recorder.selector/pick-selector
             {:tag "BUTTON"
              :attrs {}
              :index-of-type 3})))))

(deftest nth-of-type-falls-back-to-star
  (testing "nth-of-type uses * when tag is missing/blank"
    (is (= "*:nth-of-type(2)"
           (rf.story.recorder.selector/pick-selector
             {:tag nil
              :attrs {}
              :index-of-type 2})))))

(deftest positional-recognises-the-fallback-only
  (testing "rf2-3x7nj.30.5: `positional?` is true of exactly what the
            nth-of-type fallback builds, so the translator hints those steps"
    (doseq [shape [{:tag "input" :attrs {} :index-of-type 1}
                   {:tag "BUTTON" :attrs {} :index-of-type 3}
                   {:tag nil :attrs {} :index-of-type 2}
                   {:tag "my-widget" :attrs {} :index-of-type 1}]]
      (is (true? (rf.story.recorder.selector/positional?
                   (rf.story.recorder.selector/pick-selector shape)))
          (pr-str shape)))
    (testing "control: an attribute hook is not positional, even one whose
              value reads like the fallback"
      (doseq [shape [{:tag "button" :attrs {"data-test" "save"} :index-of-type 1}
                     {:tag "input" :attrs {"id" "name"} :index-of-type 1}
                     {:tag "button" :attrs {"aria-label" "Close"} :index-of-type 1}
                     {:tag "button" :attrs {"data-test" "b:nth-of-type(1)"} :index-of-type 1}]]
        (is (false? (rf.story.recorder.selector/positional?
                      (rf.story.recorder.selector/pick-selector shape)))
            (pr-str shape)))
      (is (false? (rf.story.recorder.selector/positional? nil))))))

(deftest returns-nil-when-nothing-usable
  (testing "no attributes + no index → nil"
    (is (nil? (rf.story.recorder.selector/pick-selector {:tag "div" :attrs {} :index-of-type nil})))
    (is (nil? (rf.story.recorder.selector/pick-selector {})))))

(deftest blank-attribute-values-are-ignored
  (testing "blank / whitespace attribute values fall through to next tier"
    (is (= "[id=\"x\"]"
           (rf.story.recorder.selector/pick-selector
             {:tag "input"
              :attrs {"data-test" "   "  ; blank, skip
                      "id"        "x"}})))
    (is (= "*:nth-of-type(1)"
           (rf.story.recorder.selector/pick-selector
             {:tag nil
              :attrs {"data-test" ""
                      "id"        nil
                      "aria-label" "  "}
              :index-of-type 1})))))

;; ---- escaping ------------------------------------------------------------

(deftest escapes-double-quotes
  (testing "double-quote inside value is escaped"
    (is (= "[id=\"he said \\\"hi\\\"\"]"
           (rf.story.recorder.selector/pick-selector
             {:tag "div"
              :attrs {"id" "he said \"hi\""}})))))

(deftest escapes-backslashes
  (testing "backslash inside value is escaped"
    (is (= "[id=\"a\\\\b\"]"
           (rf.story.recorder.selector/pick-selector
             {:tag "div"
              :attrs {"id" "a\\b"}})))))

;; ---- attribute-priority is the documented data ---------------------------

(deftest priority-list-is-data-test-id-aria-label
  (testing "the priority list order matches the documented contract"
    (is (= ["data-test" "id" "aria-label"]
           (mapv first rf.story.recorder.selector/attribute-priority)))))
