(ns re-frame.story.panels-e2e.chrome-fullscreen-render-e2e-cljs-test
  "Multi-frame e2e coverage for the full-screen chrome toggle (rf2-8us1r,
  replaces the Playwright `full-screen-chrome-toggle-f-hotkey` scenario).

  Per spec/014 §Chrome-visibility hotkeys (rf2-p3i0t) pressing `f`
  (no modifier, not editable target) toggles `:full-screen?` on the
  shell-state-atom; the shell's reagent-render elides every chrome
  pane (sidebar / toolbar / right-panel) when `:full-screen?` is true.

  ## Pipeline under test

      keydown `f` (no modifier, not editable)
            │
            ▼
      keybindings/dispatch!   ← capture-phase listener
            │
            ▼
      keybindings/full-screen-toggle!
            │
            ▼
      [:chrome-visibility :full-screen?] = true
            │
            ▼
      state/chrome-pane-visible? :sidebar / :rhs / :toolbar → false
            │
            ▼
      shell/:reagent-render guards `(when show-sb? [sidebar ...])` etc
            │
            ▼
      No [sidebar/sidebar] / [toolbar/toolbar-strip] / [right-panel]
      vectors in the rendered hiccup tree; the variant canvas slot
      still renders. The shell root carries
      `data-rf-chrome-fullscreen=\"true\"`.

  ## What the unit + state tests already cover

  - `keybindings_cljs_test` — pure `dispatch-key?` predicate +
    `bindings` shape + per-handler round-trip through shell-state.
  - `chrome_hotkeys_e2e_cljs_test` — `dispatch!` through the full
    discrimination pipeline (modifier-held + editable-target + Escape).
  - `chrome_visibility_test` — `chrome-pane-visible?` precedence
    (embed > full-screen > per-pane).

  ## What this e2e adds

  Drives the FULL hotkey → render seam in one process: synthetic
  keydown for `f` → dispatch! → shell hiccup tree elides chrome.
  Mirrors `embed_url_flag_e2e_cljs_test`'s URL → render seam pattern
  but exercises the keydown driver instead of the URL parser.

  Sub-millisecond per case; no DOM mount, no React, no Playwright."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.core :as r]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.story.test-helpers.e2e-multi-frame :as e2e]
            [re-frame.story.ui.keybindings :as kb]
            [re-frame.story.ui.shell :as shell]
            [re-frame.story.ui.sidebar :as sidebar]
            [re-frame.story.ui.state :as ui-state]
            [re-frame.story.ui.toolbar :as toolbar]))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory {:adapter plain-atom/adapter}))

;; The `dispatch!` fn is private — bind through the var so the test
;; reaches the same dispatcher the `keydown` capture listener routes to.
(def ^:private dispatch! @#'kb/dispatch!)

;; ---- helpers: extract shell's `:reagent-render` -------------------------
;;
;; Mirrors the harness in `embed_url_flag_e2e_cljs_test`. `shell/shell`
;; is a class-3 Reagent component built via `r/create-class`; we stub
;; create-class with identity to capture the spec map and invoke its
;; :reagent-render directly.

(defn- ^:no-doc capture-spec
  ([spec] spec)
  ([spec _compiler] spec))

(defn- shell-render-fn []
  (let [spec (with-redefs [r/create-class capture-spec] (shell/shell))]
    (:reagent-render spec)))

(defn- render-shell-tree []
  (let [render-fn (shell-render-fn)]
    (e2e/expand-tree (render-fn))))

(defn- fn-headed-nodes
  [tree pred]
  (filterv (fn [node]
             (and (vector? node)
                  (pos? (count node))
                  (fn? (first node))
                  (pred (first node))))
           (e2e/hiccup-seq tree)))

;; ---- pipeline (1): `f` keydown → shell-state flip -----------------------

(deftest f-keydown-flips-full-screen-on-shell-state
  (testing "rf2-8us1r — pressing `f` (no modifier, not editable) drives
            `dispatch!` and lands `:full-screen? true` on the live
            shell-state-atom. The state-mutation arm is covered by
            chrome_hotkeys_e2e; pinned here so the render test below
            has a known starting state."
    (e2e/with-story-and-causa-frames
      {}
      (fn []
        (is (false? (:full-screen? (e2e/chrome-visibility)))
            "default `:full-screen?` is false")
        (dispatch! (e2e/fake-event {:key "f"}))
        (is (true? (:full-screen? (e2e/chrome-visibility)))
            "`f` keydown flipped shell-state to full-screen")))))

;; ---- pipeline (2): full-screen → chrome elides at render ----------------

(deftest full-screen-render-elides-every-chrome-pane
  (testing "rf2-8us1r — with `:full-screen? true` on the shell-state-atom,
            shell's reagent-render produces a hiccup tree where the
            sidebar / toolbar / right-panel guards all elide. The
            `[main-pane]` slot (the variant canvas) still renders so
            the focused variant remains visible — full-screen hides
            chrome, not the canvas. The root wrapper's
            `data-rf-chrome-fullscreen` reflects the render's view of
            the state."
    (e2e/with-story-and-causa-frames
      {}
      (fn []
        ;; Seed full-screen directly — the state-flip arm is covered by
        ;; the test above so this separates the keydown-seam from the
        ;; render-seam (a failure points at the right layer).
        (swap! ui-state/shell-state-atom
               assoc-in [:chrome-visibility :full-screen?] true)
        (let [tree            (render-shell-tree)
              root-attrs      (when (and (vector? tree) (map? (second tree)))
                                (second tree))
              sidebar-nodes   (fn-headed-nodes tree #(= sidebar/sidebar %))
              toolbar-nodes   (fn-headed-nodes tree #(= toolbar/toolbar-strip %))
              toolbar-by-attr (e2e/find-by-test-id tree "story-toolbar")
              inspectors-node (e2e/find-by-test-id tree "story-inspectors")]
          (is (= "true" (:data-rf-chrome-fullscreen root-attrs))
              "shell wrapper carries data-rf-chrome-fullscreen=true —
               the render saw the full-screen state")
          (is (empty? sidebar-nodes)
              "no [sidebar/sidebar] vector in the tree — the sidebar
               guard elided per rf2-p3i0t")
          (is (empty? toolbar-nodes)
              "no [toolbar/toolbar-strip] vector in the tree")
          (is (nil? toolbar-by-attr)
              "no data-test=\"story-toolbar\" element — the toolbar's
               <header> never rendered")
          (is (nil? inspectors-node)
              "no data-test=\"story-inspectors\" element — the
               right-panel never rendered")
          ;; Inverse-shape check: the canvas <main> landmark must still
          ;; be in the tree. Without this assertion a render that always
          ;; drops every vector would still pass the negation checks.
          (let [main-node (e2e/find-by-data-attr tree
                                                 :aria-label "Story canvas")]
            (is (some? main-node)
                "<main aria-label=\"Story canvas\"> is still in the
                 tree — full-screen hides chrome, not canvas")))))))

;; ---- pipeline (3): default (no full-screen) → chrome present ------------

(deftest default-render-preserves-chrome-panes
  (testing "rf2-8us1r — without `:full-screen?` set (the default shape),
            shell's reagent-render produces a hiccup tree where the
            three chrome guards all admit their components. Pins the
            inverse so the elision test above can't trivially pass."
    (e2e/with-story-and-causa-frames
      {}
      (fn []
        (let [tree            (render-shell-tree)
              root-attrs      (when (and (vector? tree) (map? (second tree)))
                                (second tree))
              sidebar-nodes   (fn-headed-nodes tree #(= sidebar/sidebar %))
              toolbar-by-attr (e2e/find-by-test-id tree "story-toolbar")
              inspectors-node (e2e/find-by-test-id tree "story-inspectors")]
          (is (= "false" (:data-rf-chrome-fullscreen root-attrs))
              "shell wrapper carries data-rf-chrome-fullscreen=false")
          (is (seq sidebar-nodes)
              "[sidebar/sidebar] vector present in the tree by default")
          (is (some? toolbar-by-attr)
              "data-test=\"story-toolbar\" element present by default")
          (is (some? inspectors-node)
              "data-test=\"story-inspectors\" element present by default"))))))

;; ---- pipeline (4): full-round-trip `f` → `f` -----------------------------

(deftest f-keydown-round-trip-flips-render-twice
  (testing "rf2-8us1r — two `f` keydowns round-trip:
              press 1 → chrome elides;
              press 2 → chrome returns.
            Asserts the keydown→state→render pipeline is stable across
            both transitions (not just the activate edge)."
    (e2e/with-story-and-causa-frames
      {}
      (fn []
        (dispatch! (e2e/fake-event {:key "f"}))
        (let [tree-on (render-shell-tree)
              sidebar-on (fn-headed-nodes tree-on #(= sidebar/sidebar %))]
          (is (empty? sidebar-on)
              "after first `f` keydown, no sidebar in tree"))
        (dispatch! (e2e/fake-event {:key "f"}))
        (let [tree-off (render-shell-tree)
              sidebar-off (fn-headed-nodes tree-off #(= sidebar/sidebar %))]
          (is (seq sidebar-off)
              "after second `f` keydown, sidebar is back in tree"))))))
