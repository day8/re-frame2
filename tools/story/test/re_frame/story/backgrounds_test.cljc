(ns re-frame.story.backgrounds-test
  "Tests for the backgrounds switcher's pure state model.

  Runs on the JVM under `clojure -M:test`. This namespace ends `-test`
  rather than `cljs-test`, so NO CLJS build selects it: `:node-test`'s
  `:ns-regexp` is `cljs-test$` and `:browser-test`'s is
  `.*-dom-cljs-test$`, and nothing else requires it.

  The localStorage round-trip rows need a real `window.localStorage`, so
  they live in `re-frame.story.backgrounds-storage-dom-cljs-test`, which
  the browser lane loads.

  Coverage layers:

  - Preset lookup by id.
  - Custom hex-colour validation.
  - Selection precedence (story-override > toolbar selection > default).
  - `wrap-style` shape for flat colour + transparent / checkerboard.
  - localStorage round-trip — see the dom sibling named above."
  (:require [clojure.test :refer [are deftest is testing]]
            [re-frame.story.backgrounds :as rf.story.backgrounds]))

;; ---- preset table --------------------------------------------------------

(deftest preset-table-and-order-carry-every-canonical-id
  (testing "every canonical preset id is present"
    (let [expected #{:light :dark :paper :midnight :transparent}]
      (is (= expected (set (keys rf.story.backgrounds/presets))))
      (is (= expected (set rf.story.backgrounds/preset-order))))))

(deftest preset-transparent-uses-checkerboard-sentinel
  (testing ":transparent's :color is the :checkerboard keyword sentinel"
    (is (= :checkerboard
           (:color (get rf.story.backgrounds/presets :transparent))))))

;; ---- pure: valid-custom? ------------------------------------------------

(deftest valid-custom-accepts-hex-strings
  (testing "valid-custom? accepts 3 / 6 / 8-digit hex colours"
    (is (true?  (rf.story.backgrounds/valid-custom? "#abc")))
    (is (true?  (rf.story.backgrounds/valid-custom? "#abcdef")))
    (is (true?  (rf.story.backgrounds/valid-custom? "#abcdef12")))
    (is (true?  (rf.story.backgrounds/valid-custom? "#ABCDEF")))
    (is (true?  (rf.story.backgrounds/valid-custom? "  #abcdef  "))
        "leading/trailing whitespace tolerated")))

(deftest valid-custom-rejects-non-hex
  (testing "valid-custom? rejects non-hex shapes"
    (is (false? (rf.story.backgrounds/valid-custom? "red")))
    (is (false? (rf.story.backgrounds/valid-custom? "rgb(1,2,3)")))
    (is (false? (rf.story.backgrounds/valid-custom? "#xyz")))
    (is (false? (rf.story.backgrounds/valid-custom? "#ab")))   ;; too short
    (is (false? (rf.story.backgrounds/valid-custom? "abcdef"))) ;; missing #
    (is (false? (rf.story.backgrounds/valid-custom? nil)))
    (is (false? (rf.story.backgrounds/valid-custom? :keyword)))))

;; ---- pure: coerce -------------------------------------------------------

(deftest coerce-keeps-presets-and-valid-hex-and-drops-the-rest
  (are [slot coerced] (= coerced (rf.story.backgrounds/coerce slot))
    :dark                  :dark
    :transparent           :transparent
    ;; a valid hex string coerces to its trimmed form
    "  #abcdef  "          "#abcdef"
    :Mode.unknown/whatever nil
    :neon                  nil
    "red"                  nil
    nil                    nil
    42                     nil))

;; ---- pure: resolve precedence -------------------------------------------

(deftest resolve-falls-back-to-light-when-empty
  (testing "no override + no selection → :light default"
    (let [r (rf.story.backgrounds/resolve nil nil)]
      (is (= "Light"   (:label r)))
      (is (= "#ffffff" (:color r))))))

(deftest resolve-uses-toolbar-selection-when-no-override
  (testing "toolbar selection wins when override absent"
    (let [r (rf.story.backgrounds/resolve nil :dark)]
      (is (= "Dark"    (:label r)))
      (is (= "#1a1a1a" (:color r))))))

(deftest resolve-prefers-a-usable-override-else-the-toolbar
  (testing "a usable story-override beats the toolbar selection; an unusable one falls through"
    (are [override resolved] (= resolved (rf.story.backgrounds/resolve override :dark))
      :midnight {:label "Midnight" :color "#0a0a0a"}
      ;; a custom hex override renders under its synthetic label
      "#abc123" {:label "Custom #abc123" :color "#abc123"}
      ;; an unrecognised override does not block the live toolbar selection
      :neon     {:label "Dark" :color "#1a1a1a"})))

(deftest resolve-id-returns-keyword-or-custom
  (is (= :light (rf.story.backgrounds/resolve-id nil nil)))
  (is (= :dark  (rf.story.backgrounds/resolve-id nil :dark)))
  (is (= :midnight (rf.story.backgrounds/resolve-id :midnight :dark)))
  (is (= :custom (rf.story.backgrounds/resolve-id "#abc123" nil))))

;; ---- pure: wrap-style ----------------------------------------------------

(deftest wrap-style-flat-colour
  (testing "a flat colour produces a :background-color CSS map"
    (is (= {:background-color "#abcdef"}
           (rf.story.backgrounds/wrap-style {:color "#abcdef"})))))

(deftest wrap-style-checkerboard
  (testing "the :checkerboard sentinel produces a CSS gradient set"
    (let [s (rf.story.backgrounds/wrap-style {:color :checkerboard})]
      (is (= "#ffffff" (:background-color s))
          "white base for the checkerboard")
      (is (re-find #"linear-gradient" (:background-image s))
          "checkerboard uses background-image gradients"))))

(deftest wrap-style-nil-for-unknown
  (testing "unknown colour shape → nil (caller falls back)"
    (is (nil? (rf.story.backgrounds/wrap-style {:color 42})))))

;; ---- localStorage round-trip: see the dom sibling ----------------------
;;
;; The `storage-*` rows live in
;; `re-frame.story.backgrounds-storage-dom-cljs-test`. A `#?(:cljs
;; (deftest ...))` row here would be UNREACHABLE — not guarded-false, but
;; never compiled by any CLJS build at all — because THIS namespace ends
;; `-test` rather than `cljs-test`: `:node-test`'s `:ns-regexp`
;; (`cljs-test$`) does not select it, `:browser-test`'s
;; (`.*-dom-cljs-test$`) does not match it, and nothing else requires it.
;;
;; Every row above is a bare unconditional `deftest` and this file carries
;; no `#?(:clj ...)` form.
