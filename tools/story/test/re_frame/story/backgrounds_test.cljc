(ns re-frame.story.backgrounds-test
  "Tests for the backgrounds switcher's pure state model (rf2-zll4h).

  Runs on both JVM and CLJS — the CLJS arm exercises the localStorage
  round-trip; both arms exercise the preset table + resolve precedence
  + wrap-style shape.

  Coverage layers:

  - Preset lookup by id.
  - Custom hex-colour validation.
  - Selection precedence (story-override > toolbar selection > default).
  - `wrap-style` shape for flat colour + transparent / checkerboard.
  - localStorage round-trip — CLJS-only."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.backgrounds :as backgrounds]))

;; ---- preset table --------------------------------------------------------

(deftest preset-table-includes-every-bead-mandated-id
  (testing "every preset id called out by rf2-zll4h is present"
    (let [expected #{:light :dark :paper :midnight :transparent}]
      (is (= expected (set (keys backgrounds/presets))))
      (is (= expected (set backgrounds/preset-order))))))

(deftest preset-dark-has-canonical-colour
  (testing ":dark is #1a1a1a (bead-mandated)"
    (is (= {:label "Dark" :color "#1a1a1a"}
           (get backgrounds/presets :dark)))))

(deftest preset-transparent-uses-checkerboard-sentinel
  (testing ":transparent's :color is the :checkerboard keyword sentinel"
    (is (= :checkerboard
           (:color (get backgrounds/presets :transparent))))))

;; ---- pure: valid-custom? ------------------------------------------------

(deftest valid-custom-accepts-hex-strings
  (testing "valid-custom? accepts 3 / 6 / 8-digit hex colours"
    (is (true?  (backgrounds/valid-custom? "#abc")))
    (is (true?  (backgrounds/valid-custom? "#abcdef")))
    (is (true?  (backgrounds/valid-custom? "#abcdef12")))
    (is (true?  (backgrounds/valid-custom? "#ABCDEF")))
    (is (true?  (backgrounds/valid-custom? "  #abcdef  "))
        "leading/trailing whitespace tolerated")))

(deftest valid-custom-rejects-non-hex
  (testing "valid-custom? rejects non-hex shapes"
    (is (false? (backgrounds/valid-custom? "red")))
    (is (false? (backgrounds/valid-custom? "rgb(1,2,3)")))
    (is (false? (backgrounds/valid-custom? "#xyz")))
    (is (false? (backgrounds/valid-custom? "#ab")))   ;; too short
    (is (false? (backgrounds/valid-custom? "abcdef"))) ;; missing #
    (is (false? (backgrounds/valid-custom? nil)))
    (is (false? (backgrounds/valid-custom? :keyword)))))

;; ---- pure: coerce -------------------------------------------------------

(deftest coerce-preset-keyword-passes-through
  (is (= :dark (backgrounds/coerce :dark)))
  (is (= :transparent (backgrounds/coerce :transparent))))

(deftest coerce-unknown-keyword-returns-nil
  (is (nil? (backgrounds/coerce :Mode.unknown/whatever)))
  (is (nil? (backgrounds/coerce :neon))))

(deftest coerce-trims-custom-hex
  (testing "a valid hex string coerces to the trimmed form"
    (is (= "#abcdef"
           (backgrounds/coerce "  #abcdef  ")))))

(deftest coerce-bad-custom-returns-nil
  (is (nil? (backgrounds/coerce "red")))
  (is (nil? (backgrounds/coerce nil)))
  (is (nil? (backgrounds/coerce 42))))

;; ---- pure: resolve precedence -------------------------------------------

(deftest resolve-falls-back-to-light-when-empty
  (testing "no override + no selection → :light default"
    (let [r (backgrounds/resolve nil nil)]
      (is (= "Light"   (:label r)))
      (is (= "#ffffff" (:color r))))))

(deftest resolve-uses-toolbar-selection-when-no-override
  (testing "toolbar selection wins when override absent"
    (let [r (backgrounds/resolve nil :dark)]
      (is (= "Dark"    (:label r)))
      (is (= "#1a1a1a" (:color r))))))

(deftest resolve-story-override-beats-toolbar
  (testing "rf2-zll4h precedence: story-override wins"
    (let [r (backgrounds/resolve :midnight :dark)]
      (is (= "Midnight" (:label r))))))

(deftest resolve-custom-override-beats-toolbar
  (testing "a custom hex as the story-override is honoured"
    (let [r (backgrounds/resolve "#abc123" :dark)]
      (is (= "#abc123" (:color r)))
      (is (re-find #"abc123" (:label r))))))

(deftest resolve-bad-override-falls-through-to-toolbar
  (testing "an unrecognised override does NOT block the toolbar fallback"
    (let [r (backgrounds/resolve :neon :dark)]
      (is (= "Dark" (:label r))
          "unknown override fell through to the live toolbar selection"))))

(deftest resolve-id-returns-keyword-or-custom
  (is (= :light (backgrounds/resolve-id nil nil)))
  (is (= :dark  (backgrounds/resolve-id nil :dark)))
  (is (= :midnight (backgrounds/resolve-id :midnight :dark)))
  (is (= :custom (backgrounds/resolve-id "#abc123" nil))))

;; ---- pure: wrap-style ----------------------------------------------------

(deftest wrap-style-flat-colour
  (testing "a flat colour produces a :background-color CSS map"
    (is (= {:background-color "#abcdef"}
           (backgrounds/wrap-style {:color "#abcdef"})))))

(deftest wrap-style-checkerboard
  (testing "the :checkerboard sentinel produces a CSS gradient set"
    (let [s (backgrounds/wrap-style {:color :checkerboard})]
      (is (string? (:background-image s))
          "checkerboard uses background-image gradients")
      (is (= "#ffffff" (:background-color s))
          "white base for the checkerboard")
      (is (re-find #"linear-gradient"
                   (:background-image s))))))

(deftest wrap-style-nil-for-unknown
  (testing "unknown colour shape → nil (caller falls back)"
    (is (nil? (backgrounds/wrap-style {:color 42})))))

;; ---- localStorage round-trip --------------------------------------------

#?(:cljs
   (defn- browser?
     []
     (and (exists? js/window) (.-localStorage js/window))))

#?(:cljs
   (defn- clear-storage!
     []
     (when (browser?)
       (try (.removeItem (.-localStorage js/window) backgrounds/ls-key)
            (catch :default _ nil)))))

#?(:cljs
   (deftest storage-roundtrip-preset
     (when (browser?)
       (clear-storage!)
       (backgrounds/save-to-storage! :dark)
       (is (= :dark (backgrounds/load-from-storage)))
       (clear-storage!))))

#?(:cljs
   (deftest storage-roundtrip-custom
     (when (browser?)
       (clear-storage!)
       (backgrounds/save-to-storage! "#abc123")
       (is (= "#abc123" (backgrounds/load-from-storage)))
       (clear-storage!))))

#?(:cljs
   (deftest storage-save-nil-clears
     (when (browser?)
       (clear-storage!)
       (backgrounds/save-to-storage! :dark)
       (is (some? (backgrounds/load-from-storage)))
       (backgrounds/save-to-storage! nil)
       (is (nil? (backgrounds/load-from-storage)))
       (clear-storage!))))

#?(:cljs
   (deftest storage-rejects-invalid-on-save
     (when (browser?)
       (clear-storage!)
       (backgrounds/save-to-storage! :neon)
       (is (nil? (backgrounds/load-from-storage)))
       (backgrounds/save-to-storage! "rgb(1,2,3)")
       (is (nil? (backgrounds/load-from-storage))))))
