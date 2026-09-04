(ns re-frame.story.viewport-test
  "Tests for the viewport switcher's pure state model (rf2-zll4h).

  Runs on both the JVM (cognitect.test-runner under `clojure -M:test`)
  and the CLJS node-test build (shadow's `:node-test` target — the
  ns name doesn't end in `cljs-test` so the JVM gate is the primary
  surface; CLJS coverage comes from the parallel
  `viewport_switcher_cljs_test`).

  Coverage layers:

  - Preset lookup by id.
  - Custom `{:width :height}` validation.
  - Selection precedence (story-override > toolbar selection > default).
  - `wrap-style` shape (nil for `:full`, populated for sized presets).
  - localStorage round-trip — CLJS-only, gated by `browser?`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.viewport :as rf.story.viewport]))

;; ---- preset table --------------------------------------------------------

(deftest preset-table-includes-every-bead-mandated-id
  (testing "every preset id called out by rf2-zll4h is present"
    (let [expected #{:full :mobile-portrait :mobile-landscape
                     :tablet :desktop :desktop-wide}]
      (is (= expected (set (keys rf.story.viewport/presets))))
      (is (= expected (set rf.story.viewport/preset-order))))))

(deftest preset-full-has-no-dimensions
  (testing ":full preset carries nil width + height (no resize)"
    (let [p (get rf.story.viewport/presets :full)]
      (is (= "Full" (:label p)))
      (is (nil? (:width p)))
      (is (nil? (:height p))))))

(deftest preset-tablet-has-canonical-dimensions
  (testing ":tablet is 768x1024 (bead-mandated)"
    (is (= {:label "Tablet" :width 768 :height 1024}
           (get rf.story.viewport/presets :tablet)))))

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

(deftest coerce-preset-keyword-passes-through
  (testing "a recognised preset keyword coerces to itself"
    (is (= :tablet (rf.story.viewport/coerce :tablet)))
    (is (= :full   (rf.story.viewport/coerce :full)))))

(deftest coerce-unknown-keyword-returns-nil
  (testing "an unrecognised keyword is dropped to nil"
    (is (nil? (rf.story.viewport/coerce :Mode.unknown/whatever)))
    (is (nil? (rf.story.viewport/coerce :phablet)))))

(deftest coerce-custom-map-extracts-dims
  (testing "a custom map coerces to a slim {:width :height} map"
    (is (= {:width 800 :height 600}
           (rf.story.viewport/coerce {:width 800 :height 600 :label "extra"})))))

(deftest coerce-bad-custom-returns-nil
  (testing "a malformed custom map coerces to nil"
    (is (nil? (rf.story.viewport/coerce {:width "800" :height "600"})))
    (is (nil? (rf.story.viewport/coerce {:width 800})))
    (is (nil? (rf.story.viewport/coerce "800x600")))
    (is (nil? (rf.story.viewport/coerce nil)))))

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
  (testing "rf2-zll4h precedence: story-override wins over toolbar selection"
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

;; ---- localStorage round-trip --------------------------------------------
;;
;; CLJS only — JVM `load-from-storage` always returns nil.

#?(:cljs
   (defn- browser?
     []
     (and (exists? js/window) (.-localStorage js/window))))

#?(:cljs
   (defn- clear-storage!
     []
     (when (browser?)
       (try (.removeItem (.-localStorage js/window) rf.story.viewport/ls-key)
            (catch :default _ nil)))))

#?(:cljs
   (deftest storage-roundtrip-preset
     (testing "save → load returns the persisted preset id"
       (when (browser?)
         (clear-storage!)
         (rf.story.viewport/save-to-storage! :tablet)
         (is (= :tablet (rf.story.viewport/load-from-storage)))
         (clear-storage!)))))

#?(:cljs
   (deftest storage-roundtrip-custom
     (testing "save → load returns the persisted custom map"
       (when (browser?)
         (clear-storage!)
         (rf.story.viewport/save-to-storage! {:width 800 :height 600})
         (is (= {:width 800 :height 600} (rf.story.viewport/load-from-storage)))
         (clear-storage!)))))

#?(:cljs
   (deftest storage-save-nil-clears
     (testing "save-to-storage! nil clears the persisted slot"
       (when (browser?)
         (clear-storage!)
         (rf.story.viewport/save-to-storage! :tablet)
         (is (some? (rf.story.viewport/load-from-storage)))
         (rf.story.viewport/save-to-storage! nil)
         (is (nil? (rf.story.viewport/load-from-storage)))
         (clear-storage!)))))

#?(:cljs
   (deftest storage-rejects-invalid-on-save
     (testing "save-to-storage! drops invalid values to nil (no leak)"
       (when (browser?)
         (clear-storage!)
         (rf.story.viewport/save-to-storage! :phablet)         ;; unknown preset
         (is (nil? (rf.story.viewport/load-from-storage)))
         (rf.story.viewport/save-to-storage! {:width "no"})    ;; bad shape
         (is (nil? (rf.story.viewport/load-from-storage)))))))
