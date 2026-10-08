(ns re-frame.story.recorder.selector-test
  (:require [clojure.test :refer [are deftest is]]
            [re-frame.story.recorder.selector :as rf.story.recorder.selector]))

(deftest pick-selector-walks-the-priority-tiers
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
    "button:nth-of-type(3)"

    {:tag "div" :attrs {} :index-of-type nil}
    nil))

(deftest positional-recognises-the-fallback-only
  (doseq [shape [{:tag "input" :attrs {} :index-of-type 1}
                 {:tag nil :attrs {} :index-of-type 2}
                 {:tag "my-widget" :attrs {} :index-of-type 1}]]
    (is (true? (rf.story.recorder.selector/positional?
                 (rf.story.recorder.selector/pick-selector shape)))
        (pr-str shape)))
  ;; An attribute hook whose value reads like the fallback is still not positional.
  (is (false? (rf.story.recorder.selector/positional?
                (rf.story.recorder.selector/pick-selector
                  {:tag "button" :attrs {"data-test" "b:nth-of-type(1)"} :index-of-type 1}))))
  (is (false? (rf.story.recorder.selector/positional? nil))))

(deftest blank-attribute-values-are-ignored
  (is (= "[id=\"x\"]"
         (rf.story.recorder.selector/pick-selector
           {:tag "input" :attrs {"data-test" "   " "id" "x"}})))
  (is (= "*:nth-of-type(1)"
         (rf.story.recorder.selector/pick-selector
           {:tag nil :attrs {"data-test" "" "id" nil "aria-label" "  "} :index-of-type 1}))))

(deftest attribute-values-escape-quotes-and-backslashes
  (are [id selector] (= selector (rf.story.recorder.selector/pick-selector
                                   {:tag "div" :attrs {"id" id}}))
    "he said \"hi\"" "[id=\"he said \\\"hi\\\"\"]"
    "a\\b"           "[id=\"a\\\\b\"]"))
