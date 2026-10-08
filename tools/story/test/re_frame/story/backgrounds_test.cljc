(ns re-frame.story.backgrounds-test
  "The backgrounds switcher's pure state model. JVM only: no CLJS build
  selects a `-test` namespace, so the localStorage round-trip lives in
  `re-frame.story.backgrounds-storage-dom-cljs-test`."
  (:require [clojure.test :refer [are deftest is]]
            [re-frame.story.backgrounds :as rf.story.backgrounds]))

;; The preset ids are the ones docs/story/api/registration.md lists.
(deftest preset-table-and-order-carry-every-canonical-id
  (let [expected #{:light :dark :paper :midnight :transparent}]
    (is (= expected (set (keys rf.story.backgrounds/presets))))
    (is (= expected (set rf.story.backgrounds/preset-order)))))

(deftest valid-custom-accepts-only-3-6-8-digit-hex
  (are [s ok?] (= ok? (rf.story.backgrounds/valid-custom? s))
    "#abc"      true
    "#abcdef12" true
    "#ABCDEF"   true
    "#xyz"      false
    "#ab"       false
    "abcdef"    false))

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

(deftest resolve-falls-back-to-light-when-empty
  (is (= {:label "Light" :color "#ffffff"} (rf.story.backgrounds/resolve nil nil))))

(deftest resolve-prefers-a-usable-override-else-the-toolbar
  (are [override resolved] (= resolved (rf.story.backgrounds/resolve override :dark))
    :midnight {:label "Midnight" :color "#0a0a0a"}
    ;; a custom hex override renders under its synthetic label
    "#abc123" {:label "Custom #abc123" :color "#abc123"}
    ;; an unrecognised override does not block the live toolbar selection
    :neon     {:label "Dark" :color "#1a1a1a"}))

(deftest resolve-id-returns-keyword-or-custom
  (is (= :light (rf.story.backgrounds/resolve-id nil nil)))
  (is (= :dark  (rf.story.backgrounds/resolve-id nil :dark)))
  (is (= :midnight (rf.story.backgrounds/resolve-id :midnight :dark)))
  (is (= :custom (rf.story.backgrounds/resolve-id "#abc123" nil))))

(deftest wrap-style-paints-a-flat-colour-or-the-transparent-checkerboard
  (is (= {:background-color "#abcdef"}
         (rf.story.backgrounds/wrap-style {:color "#abcdef"})))
  (let [s (rf.story.backgrounds/wrap-style (:transparent rf.story.backgrounds/presets))]
    (is (= "#ffffff" (:background-color s)))
    (is (re-find #"linear-gradient" (:background-image s)))))
