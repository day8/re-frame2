(ns re-frame.story.panels-e2e.viewport-indicator-e2e-cljs-test
  "Multi-frame e2e coverage for the viewport-px chrome indicator
  (rf2-f9xkq, replaces the Playwright
  `viewport-px-chrome-indicator-tracks-viewport-changes` scenario).

  Per spec/014 §Viewport-px indicator chip (rf2-zgu68) the canvas
  renders a bottom-right chip with `\"<W> × <H>\"` text when a sized
  viewport mode is active. The `:full` preset suppresses the chip.

  ## Pipeline under test

      toolbar viewport chip click  /  programmatic select!
            │
            ▼
      viewport-switcher/select!   ← writes shell-state-atom
            │
            ▼
      [:viewport ...] = :mobile-portrait  (or {:width :height})
            │
            ▼
      viewport-switcher/effective-viewport
            │
            ▼
      framed-canvas → [canvas/viewport-indicator vp]
            │
            ▼
      Hiccup: [:div {:data-test \"story-canvas-viewport-indicator\"
                     :data-viewport-dims \"375 × 667\"} \"375 × 667\"]

      Or, for `:full`: viewport-indicator returns nil → no chip.

  ## What the unit + state tests already cover

  - `canvas_skeleton_cljs_test` — pure `viewport-indicator-text` +
    hiccup shape for sized presets + nil for `:full`.
  - `viewport_switcher_cljs_test` — `select!` writes shell-state +
    `effective-viewport` resolution from override / toolbar / default.

  ## What this e2e adds

  Drives the FULL select → effective-viewport → indicator-chip seam in
  one process:
    1. select! a sized preset → chip renders with expected text.
    2. select! a different sized preset → chip text tracks the change.
    3. select! :full (or default) → chip absent (self-elides).
    4. select! a custom `{:width :height}` map → chip renders custom dims.

  Mirrors `embed_url_flag_e2e_cljs_test`'s state → render seam pattern
  but drives the toolbar switcher instead of the URL parser.

  Sub-millisecond per case; no DOM mount, no React, no Playwright."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.story :as story]
            [re-frame.story.test-helpers.e2e-multi-frame :as e2e]
            [re-frame.story.ui.canvas :as canvas]
            [re-frame.story.ui.state :as ui-state]
            [re-frame.story.ui.viewport-switcher :as vs]))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory {:adapter plain-atom/adapter}))

;; ---- helpers: render the indicator from effective-viewport --------------
;;
;; `framed-canvas` is `defn-` in shell.cljs; to exercise the indicator
;; render seam here we follow the same composition the shell does —
;; resolve the effective viewport, then invoke `canvas/viewport-indicator`
;; on the result. This is the exact code path the live shell drives.

(defn- render-indicator-from-shell-state
  "Drive the indicator render through `effective-viewport` (which reads
  the live shell-state-atom + registrar) — the same composition the
  shell's `framed-canvas` performs at runtime."
  []
  (let [vp (vs/effective-viewport)]
    (canvas/viewport-indicator vp)))

(defn- indicator-attrs
  "Extract the attrs map of a rendered indicator hiccup tree, or nil
  if the indicator is absent (self-elided for `:full`)."
  [hiccup]
  (when (and (vector? hiccup) (map? (second hiccup)))
    (second hiccup)))

(defn- indicator-text
  "Extract the text content of a rendered indicator hiccup tree, or
  nil if the indicator is absent."
  [hiccup]
  (when (vector? hiccup)
    (some string? hiccup)))

;; ---- pipeline (1): default :full → indicator absent ---------------------

(deftest default-full-preset-elides-indicator
  (testing "rf2-f9xkq — without any toolbar selection, `effective-viewport`
            resolves to `:full` (no width/height) and the indicator
            self-elides (returns nil). Pinning the inverse here ensures
            the later 'indicator-renders' tests can't be trivial-pass."
    (e2e/with-story-and-causa-frames
      {}
      (fn []
        (is (nil? (render-indicator-from-shell-state))
            "default :full preset → indicator chip absent")))))

;; ---- pipeline (2): toolbar select! drives indicator to sized preset -----

(deftest select-mobile-portrait-renders-indicator-with-375x667
  (testing "rf2-f9xkq — `select! :mobile-portrait` writes the shell-state
            slot; `effective-viewport` reads through it; the indicator
            renders the canonical \"375 × 667\" text with the
            `data-viewport-dims` attribute matching."
    (e2e/with-story-and-causa-frames
      {}
      (fn []
        (vs/select! :mobile-portrait)
        (let [hiccup (render-indicator-from-shell-state)
              attrs  (indicator-attrs hiccup)
              text   (indicator-text hiccup)]
          (is (some? hiccup)
              "indicator chip rendered for sized preset")
          (is (= "story-canvas-viewport-indicator" (:data-test attrs))
              "canonical data-test attribute carried")
          (is (= "375 × 667" (:data-viewport-dims attrs))
              "data-viewport-dims pins the canonical text for browser specs")
          (is (= "375 × 667" text)
              "visible chip text reads \"W × H\""))))))

;; ---- pipeline (3): switching preset tracks the dimensions ---------------

(deftest selecting-different-preset-updates-indicator-text
  (testing "rf2-f9xkq — calling `select!` with a different preset
            updates the indicator's rendered text. Catches the
            'indicator stuck on first selection' class of regression."
    (e2e/with-story-and-causa-frames
      {}
      (fn []
        (vs/select! :mobile-portrait)
        (is (= "375 × 667" (indicator-text (render-indicator-from-shell-state)))
            "first selection lands the mobile-portrait dims")
        (vs/select! :desktop)
        (is (= "1280 × 800" (indicator-text (render-indicator-from-shell-state)))
            "switching to :desktop updates the chip to 1280 × 800")
        (vs/select! :tablet)
        (is (= "768 × 1024" (indicator-text (render-indicator-from-shell-state)))
            "switching to :tablet updates the chip to 768 × 1024")))))

;; ---- pipeline (4): switching back to :full elides the indicator ---------

(deftest selecting-full-after-sized-elides-indicator
  (testing "rf2-f9xkq — after a sized preset, switching back to `:full`
            (the no-resize default) re-elides the indicator. Pins the
            'sized → unsized → no chip' transition so a regression that
            kept a stale chip after un-selection would surface."
    (e2e/with-story-and-causa-frames
      {}
      (fn []
        (vs/select! :tablet)
        (is (some? (render-indicator-from-shell-state))
            "sized preset → indicator rendered")
        (vs/select! :full)
        (is (nil? (render-indicator-from-shell-state))
            "back to :full → indicator self-elides")))))

;; ---- pipeline (5): custom {:width :height} map renders custom dims ------

(deftest custom-viewport-map-renders-indicator-with-custom-dims
  (testing "rf2-f9xkq — a custom `{:width :height}` selection drives
            the indicator with the custom dimensions. Pins the custom-
            entry path (the dropdown's Width × Height row at the
            bottom)."
    (e2e/with-story-and-causa-frames
      {}
      (fn []
        (vs/select! {:width 1024 :height 600})
        (let [hiccup (render-indicator-from-shell-state)
              text   (indicator-text hiccup)]
          (is (some? hiccup)
              "custom map → indicator rendered")
          (is (= "1024 × 600" text)
              "custom dims surface verbatim in chip text"))))))

;; ---- pipeline (6): per-variant body override beats toolbar selection ----

(deftest variant-body-viewport-override-wins-over-toolbar
  (testing "rf2-f9xkq — when a variant body declares `:viewport`, that
            override wins over the toolbar's selection at indicator-
            render time. Per spec/014 §`:viewport` body slot."
    (e2e/with-story-and-causa-frames
      {:register-stories
       (fn []
         (story/reg-story* :story.vp-override
           {:doc "viewport override fixture" :component :ignored})
         (story/reg-variant* :story.vp-override/v
           {:doc "child with :viewport override"
            :viewport :tablet}))}
      (fn []
        (vs/select! :mobile-portrait)
        (swap! ui-state/shell-state-atom
               assoc :selected-variant :story.vp-override/v)
        (is (= "768 × 1024" (indicator-text (render-indicator-from-shell-state)))
            "variant body's `:viewport :tablet` wins over toolbar's
             `:mobile-portrait`; chip reflects the override dims")))))
