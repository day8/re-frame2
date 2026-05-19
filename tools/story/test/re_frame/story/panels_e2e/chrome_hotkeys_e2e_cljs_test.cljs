(ns re-frame.story.panels-e2e.chrome-hotkeys-e2e-cljs-test
  "Multi-frame e2e coverage for the chrome-visibility hotkey
  registry (rf2-piucm; rf2-g8l8x / rf2-p3i0t).

  The hotkey dispatcher binds ONE capture-phase keydown listener and
  routes lowercase keys (no modifier, not editable) to chrome-
  visibility toggles:

      f → full-screen   (rf2-p3i0t)
      s → sidebar       (rf2-g8l8x)
      a → RHS / addons  (rf2-g8l8x)
      t → toolbar       (rf2-g8l8x)
      Escape → exit full-screen (when full-screen is on)

  ## What the unit test in `keybindings_cljs_test` already covers

  - Pure predicate (`dispatch-key?`)
  - Canonical bindings table shape
  - Each handler fn round-trips through shell-state-atom

  ## What this e2e test adds

  Drives the FULL `dispatch!` pipeline (not just the handler fns) —
  this is what catches the wrong-frame-dispatch class (rf2-83d4x):

  1. Synthetic keydown event with the right `key` / `target` / no
     modifier flows through the discrimination predicates AND lands
     on the registered handler.
  2. Modifier held → no flip (Cmd-K / Ctrl-S etc. pass through).
  3. Focus inside `<input>` → no flip (typing `f` in sidebar search
     doesn't toggle full-screen).
  4. Escape exits full-screen ONLY when full-screen is currently on.

  Sub-millisecond per case; no DOM / no React. The harness lives in
  this ns; no Causa pipeline is needed for this surface (the hotkeys
  are Story chrome only)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.story.ui.keybindings :as kb]
            [re-frame.story.test-helpers.e2e-multi-frame :as e2e]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

;; The `dispatch!` fn is private — bind through the var so the test
;; reaches the same dispatcher the `keydown` capture listener routes to.
(def ^:private dispatch! @#'kb/dispatch!)

;; ---- happy-path: each canonical key flips its slot ----------------------

(deftest f-key-toggles-full-screen
  (testing "rf2-p3i0t — `f` (no modifier, not editable) flips
            :full-screen?"
    (e2e/with-story-and-causa-frames
      {}
      (fn []
        (is (false? (:full-screen? (e2e/chrome-visibility)))
            "default :full-screen? is false")
        (dispatch! (e2e/fake-event {:key "f"}))
        (is (true? (:full-screen? (e2e/chrome-visibility)))
            "f keydown flipped :full-screen? to true")
        (dispatch! (e2e/fake-event {:key "f"}))
        (is (false? (:full-screen? (e2e/chrome-visibility)))
            "second f keydown flipped back to false")))))

(deftest s-key-toggles-sidebar
  (testing "rf2-g8l8x — `s` flips :sidebar?"
    (e2e/with-story-and-causa-frames
      {}
      (fn []
        (is (true? (:sidebar? (e2e/chrome-visibility))))
        (dispatch! (e2e/fake-event {:key "s"}))
        (is (false? (:sidebar? (e2e/chrome-visibility))))))))

(deftest a-key-toggles-rhs
  (testing "rf2-g8l8x — `a` (for `addons` per Storybook convention)
            flips :rhs?"
    (e2e/with-story-and-causa-frames
      {}
      (fn []
        (is (true? (:rhs? (e2e/chrome-visibility))))
        (dispatch! (e2e/fake-event {:key "a"}))
        (is (false? (:rhs? (e2e/chrome-visibility))))))))

(deftest t-key-toggles-toolbar
  (testing "rf2-g8l8x — `t` flips :toolbar?"
    (e2e/with-story-and-causa-frames
      {}
      (fn []
        (is (true? (:toolbar? (e2e/chrome-visibility))))
        (dispatch! (e2e/fake-event {:key "t"}))
        (is (false? (:toolbar? (e2e/chrome-visibility))))))))

;; ---- discrimination: modifiers + editable targets pass through ---------

(deftest modifier-held-passes-through
  (testing "Cmd-/Ctrl-/Alt-held → dispatch! is a no-op (the palette /
            browser owns modifier-bearing chords). rf2-g8l8x §exclusions"
    (e2e/with-story-and-causa-frames
      {}
      (fn []
        (let [before (e2e/chrome-visibility)]
          (dispatch! (e2e/fake-event {:key "f" :meta? true}))
          (dispatch! (e2e/fake-event {:key "f" :ctrl? true}))
          (dispatch! (e2e/fake-event {:key "f" :alt? true}))
          (is (= before (e2e/chrome-visibility))
              "no slot moved when a modifier is held"))))))

(deftest focused-input-passes-through
  (testing "target is INPUT / TEXTAREA / SELECT / contenteditable →
            dispatch! is a no-op. Typing `f` into the sidebar search
            shouldn't toggle full-screen. rf2-g8l8x §focus-discrimination"
    (e2e/with-story-and-causa-frames
      {}
      (fn []
        (let [before (e2e/chrome-visibility)]
          (dispatch! (e2e/fake-event {:key "f" :tag-name "INPUT"}))
          (dispatch! (e2e/fake-event {:key "f" :tag-name "TEXTAREA"}))
          (dispatch! (e2e/fake-event {:key "f" :tag-name "SELECT"}))
          (dispatch! (e2e/fake-event {:key "f" :content-editable? true}))
          (is (= before (e2e/chrome-visibility))
              "no slot moved when the keydown's target is editable"))))))

(deftest unbound-key-passes-through
  (testing "unbound key → dispatch! is a no-op. `g` is not in
            `bindings` so no toggle fires."
    (e2e/with-story-and-causa-frames
      {}
      (fn []
        (let [before (e2e/chrome-visibility)]
          (dispatch! (e2e/fake-event {:key "g"}))
          (dispatch! (e2e/fake-event {:key "z"}))
          (is (= before (e2e/chrome-visibility))
              "unbound keys do not move chrome-visibility"))))))

;; ---- Escape exits full-screen ------------------------------------------

(deftest escape-exits-full-screen-only-when-on
  (testing "rf2-p3i0t — Escape exits full-screen when on; no-op
            otherwise."
    (e2e/with-story-and-causa-frames
      {}
      (fn []
        ;; off → Escape → still off
        (dispatch! (e2e/fake-event {:key "Escape"}))
        (is (false? (:full-screen? (e2e/chrome-visibility))))
        ;; on → Escape → off
        (kb/full-screen-toggle!)
        (is (true? (:full-screen? (e2e/chrome-visibility))))
        (dispatch! (e2e/fake-event {:key "Escape"}))
        (is (false? (:full-screen? (e2e/chrome-visibility))))))))

(deftest escape-yields-when-editable
  (testing "Escape in an editable target does NOT exit full-screen —
            that key is the input's own cancel affordance"
    (e2e/with-story-and-causa-frames
      {}
      (fn []
        (kb/full-screen-toggle!)
        (is (true? (:full-screen? (e2e/chrome-visibility))))
        (dispatch! (e2e/fake-event {:key "Escape" :tag-name "INPUT"}))
        (is (true? (:full-screen? (e2e/chrome-visibility)))
            "Escape in INPUT does not exit full-screen")))))

;; ---- bindings table sanity (catches an additive regression) -------------

(deftest bindings-table-stays-stable
  (testing "rf2-g8l8x — the canonical 4-key registry is f / s / a / t.
            Adding a key here without spec discussion would risk
            colliding with author muscle-memory."
    (is (= #{"f" "s" "a" "t"} (set (keys kb/bindings)))
        "the 4-key chrome hotkey set is stable")))
