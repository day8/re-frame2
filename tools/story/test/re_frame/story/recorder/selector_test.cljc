(ns re-frame.story.recorder.selector-test
  "Pure unit tests for the recorder's selector picker.

  Covers:
  - Priority tiers (data-test > id > aria-label > nth-of-type).
  - Attribute-value escaping (backslash + double-quote).
  - Nth-of-type fallback geometry."
  (:require [clojure.test :refer [are deftest is testing]]
            [re-frame.story.recorder.selector :as rf.story.recorder.selector]))

;; ---- priority tiers ------------------------------------------------------

(deftest pick-selector-walks-the-priority-tiers
  (testing "data-test > id > aria-label > nth-of-type: each tier wins once
            every tier above it is absent, and the fallback lower-cases the tag"
    (are [shape selector] (= selector (rf.story.recorder.selector/pick-selector shape))
      {:tag "button"
       :attrs {"data-test" "submit-btn" "id" "btn-1" "aria-label" "Submit"}
       :index-of-type 3}
      "[data-test=\"submit-btn\"]"

      {:tag "input" :attrs {"id" "counter-input" "aria-label" "Counter"} :index-of-type 2}
      "[id=\"counter-input\"]"

      {:tag "button" :attrs {"aria-label" "Close dialog"} :index-of-type 1}
      "[aria-label=\"Close dialog\"]"

      {:tag "BUTTON" :attrs {} :index-of-type 3}
      "button:nth-of-type(3)")))

(deftest positional-recognises-the-fallback-only
  (testing "`positional?` is true of exactly what the
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

(deftest attribute-values-escape-quotes-and-backslashes
  (are [id selector] (= selector (rf.story.recorder.selector/pick-selector
                                   {:tag "div" :attrs {"id" id}}))
    "he said \"hi\"" "[id=\"he said \\\"hi\\\"\"]"
    "a\\b"           "[id=\"a\\\\b\"]"))
