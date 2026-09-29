(ns re-frame.story.viewport-test
  "Tests for the viewport switcher's pure state model.

  Runs on the JVM (cognitect.test-runner under `clojure -M:test`) and
  NOWHERE ELSE. This namespace ends `-test` rather than `cljs-test`, so
  no CLJS build selects it: `:node-test`'s `:ns-regexp` is `cljs-test$`
  and `:browser-test`'s is `.*-dom-cljs-test$`, and nothing else
  requires it. The localStorage rows, which need a real host, live in
  `re-frame.story.viewport-storage-dom-cljs-test`.

  Coverage layers:

  - Preset lookup by id.
  - Custom `{:width :height}` validation.
  - Selection precedence (story-override > toolbar selection > default).
  - `wrap-style` shape (nil for `:full`, populated for sized presets).
  - localStorage round-trip — in the dom sibling named above."
  (:require [clojure.test :refer [are deftest is testing]]
            [re-frame.story.viewport :as rf.story.viewport]))

;; ---- preset table --------------------------------------------------------

(deftest preset-table-and-order-carry-every-canonical-id
  (testing "every preset id is present"
    (let [expected #{:full :mobile-portrait :mobile-landscape
                     :tablet :desktop :desktop-wide}]
      (is (= expected (set (keys rf.story.viewport/presets))))
      (is (= expected (set rf.story.viewport/preset-order))))))

;; ---- pure: valid-custom? ------------------------------------------------

(deftest valid-custom-requires-positive-integer-dimensions
  (testing "valid-custom? accepts only positive-integer width + height"
    (is (true?  (rf.story.viewport/valid-custom? {:width 800 :height 600})))
    (is (true?  (rf.story.viewport/valid-custom? {:width 1 :height 1})))
    (is (false? (rf.story.viewport/valid-custom? {:width 0 :height 600}))
        "zero is not positive")
    (is (false? (rf.story.viewport/valid-custom? {:width -100 :height 600})))
    (is (false? (rf.story.viewport/valid-custom? {:width "800" :height "600"}))
        "strings are not integers")
    (is (false? (rf.story.viewport/valid-custom? {:width 800})))
    (is (false? (rf.story.viewport/valid-custom? {})))
    (is (false? (rf.story.viewport/valid-custom? nil)))
    (is (false? (rf.story.viewport/valid-custom? "tablet")))))

;; ---- pure: coerce -------------------------------------------------------

(deftest coerce-keeps-presets-and-valid-dims-and-drops-the-rest
  (are [slot coerced] (= coerced (rf.story.viewport/coerce slot))
    :tablet                                  :tablet
    :full                                    :full
    ;; a custom map coerces to a slim {:width :height} map
    {:width 800 :height 600 :label "extra"}  {:width 800 :height 600}
    :Mode.unknown/whatever                   nil
    :phablet                                 nil
    {:width "800" :height "600"}             nil
    {:width 800}                             nil
    "800x600"                                nil
    nil                                      nil))

;; ---- pure: resolve precedence -------------------------------------------

(deftest resolve-falls-back-to-full-when-empty
  (testing "neither override nor selection → :full (no-resize) preset"
    (let [r (rf.story.viewport/resolve nil nil)]
      (is (= "Full" (:label r)))
      (is (nil? (:width r)))
      (is (nil? (:height r))))))

(deftest resolve-uses-toolbar-selection-when-no-override
  (testing "toolbar selection wins when no per-story override is set"
    (let [r (rf.story.viewport/resolve nil :tablet)]
      (is (= "Tablet" (:label r)))
      (is (= 768 (:width r)))
      (is (= 1024 (:height r))))))

(deftest resolve-story-override-beats-toolbar
  (testing "precedence: story-override wins over toolbar selection"
    (let [r (rf.story.viewport/resolve :mobile-portrait :tablet)]
      (is (= "Mobile portrait" (:label r))
          "override (:mobile-portrait) beat the toolbar (:tablet)"))))

(deftest resolve-custom-override-beats-toolbar
  (testing "a custom map as the story-override is honoured"
    (let [r (rf.story.viewport/resolve {:width 500 :height 300} :tablet)]
      (is (= 500 (:width r)))
      (is (= 300 (:height r)))
      (is (re-find #"500" (:label r)))
      (is (re-find #"300" (:label r))))))

(deftest resolve-bad-override-falls-through-to-toolbar
  (testing "an unrecognised override does NOT block the toolbar fallback"
    (let [r (rf.story.viewport/resolve :phablet :tablet)]
      (is (= "Tablet" (:label r))
          "unknown override fell through to the live toolbar selection"))))

(deftest resolve-id-returns-keyword-or-custom
  (testing "resolve-id surfaces the resolved id for data-* attributes"
    (is (= :full    (rf.story.viewport/resolve-id nil nil)))
    (is (= :tablet  (rf.story.viewport/resolve-id nil :tablet)))
    (is (= :desktop (rf.story.viewport/resolve-id :desktop :tablet)))
    (is (= :custom  (rf.story.viewport/resolve-id {:width 500 :height 300} nil)))))

;; ---- pure: wrap-style ----------------------------------------------------

(deftest wrap-style-nil-for-full
  (testing ":full → nil wrapper (no sizing div needed)"
    (is (nil? (rf.story.viewport/wrap-style (get rf.story.viewport/presets :full))))))

(deftest wrap-style-populated-for-sized-preset
  (testing "a sized preset produces width + height CSS"
    (let [s (rf.story.viewport/wrap-style (get rf.story.viewport/presets :tablet))]
      (is (= "768px" (:width s)))
      (is (= "1024px" (:height s)))
      (is (string? (:border s))
          "the wrap carries a visible border")
      (is (= "0 auto" (:margin s))
          "centred horizontally"))))

;; ---- localStorage round-trip: see the dom sibling ----------------------
;;
;; The `storage-*` rows live in `re-frame.story.viewport-storage-dom-cljs-test`:
;; they need a real `window.localStorage`, and THIS namespace ends `-test`
;; rather than `cljs-test`, so `:node-test`'s `:ns-regexp` (`cljs-test$`)
;; does not select it, `:browser-test`'s (`.*-dom-cljs-test$`) does not
;; match it, and nothing else requires it — `#?(:cljs (deftest ...))` rows
;; here would never be compiled by any CLJS build.
;;
;; Every row above is a bare unconditional `deftest` and this file carries
;; no `#?(:clj ...)` form, so all of them run on the JVM.
