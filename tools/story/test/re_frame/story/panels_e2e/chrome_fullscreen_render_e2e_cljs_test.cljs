(ns re-frame.story.panels-e2e.chrome-fullscreen-render-e2e-cljs-test
  "The hotkey-to-render seam: full-screen hides chrome while keeping the canvas.
  Two keydowns restore the sidebar. Hiccup-level Node tests."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.core :as r]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.story.test-helpers.e2e-multi-frame :as rf.story.test-helpers.e2e-multi-frame]
            [re-frame.story.ui.keybindings :as rf.story.ui.keybindings]
            [re-frame.story.ui.shell :as rf.story.ui.shell]
            [re-frame.story.ui.sidebar :as rf.story.ui.sidebar]
            [re-frame.story.ui.state :as rf.story.ui.state]
            [re-frame.story.ui.toolbar :as rf.story.ui.toolbar]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; The `dispatch!` fn is private — bind through the var so the test
;; reaches the same dispatcher the `keydown` capture listener routes to.
(def ^:private dispatch! @#'rf.story.ui.keybindings/dispatch!)

;; ---- helpers: extract shell's `:reagent-render` -------------------------
;;
;; Mirrors the harness in `embed_url_flag_e2e_cljs_test`. `rf.story.ui.shell/shell`
;; is a class-3 Reagent component built via `r/create-class`; we stub
;; create-class with identity to capture the spec map and invoke its
;; :reagent-render directly.

(defn- ^:no-doc capture-spec
  ([spec] spec)
  ([spec _compiler] spec))

(defn- shell-render-fn []
  (let [spec (with-redefs [r/create-class capture-spec] (rf.story.ui.shell/shell))]
    (:reagent-render spec)))

(defn- render-shell-tree []
  (let [render-fn (shell-render-fn)]
    (rf.story.test-helpers.e2e-multi-frame/expand-tree (render-fn))))

(defn- fn-headed-nodes
  [tree pred]
  (filterv (fn [node]
             (and (vector? node)
                  (pos? (count node))
                  (fn? (first node))
                  (pred (first node))))
           (rf.story.test-helpers.e2e-multi-frame/hiccup-seq tree)))

;; ---- pipeline (2): full-screen → chrome elides at render ----------------

(deftest full-screen-render-elides-every-chrome-pane
  (testing "with `:full-screen? true` on the shell-state-atom,
            shell's reagent-render produces a hiccup tree where the
            sidebar / toolbar / right-panel guards all elide. The
            `[main-pane]` slot (the variant canvas) still renders so
            the focused variant remains visible — full-screen hides
            chrome, not the canvas. The root wrapper's
            `data-rf-chrome-fullscreen` reflects the render's view of
            the state."
    (rf.story.test-helpers.e2e-multi-frame/with-story-and-xray-frames
      {}
      (fn []
        ;; chrome_hotkeys_e2e covers the dispatcher; this case isolates render.
        (swap! rf.story.ui.state/shell-state-atom
               assoc-in [:chrome-visibility :full-screen?] true)
        (let [tree            (render-shell-tree)
              root-attrs      (when (and (vector? tree) (map? (second tree)))
                                (second tree))
              sidebar-nodes   (fn-headed-nodes tree #(= rf.story.ui.sidebar/sidebar %))
              toolbar-nodes   (fn-headed-nodes tree #(= rf.story.ui.toolbar/toolbar-strip %))
              toolbar-by-attr (rf.story.test-helpers.e2e-multi-frame/find-by-test-id tree "story-toolbar")
              inspectors-node (rf.story.test-helpers.e2e-multi-frame/find-by-test-id tree "story-inspectors")]
          (is (= "true" (:data-rf-chrome-fullscreen root-attrs))
              "shell wrapper carries data-rf-chrome-fullscreen=true —
               the render saw the full-screen state")
          (is (empty? sidebar-nodes)
              "no [rf.story.ui.sidebar/sidebar] vector in the tree — the sidebar
               guard elided")
          (is (empty? toolbar-nodes)
              "no [rf.story.ui.toolbar/toolbar-strip] vector in the tree")
          (is (nil? toolbar-by-attr)
              "no data-test=\"story-toolbar\" element — the toolbar's
               <header> never rendered")
          (is (nil? inspectors-node)
              "no data-test=\"story-inspectors\" element — the
               right-panel never rendered")
          ;; Inverse-shape check: the canvas <main> landmark must still
          ;; be in the tree. Without this assertion a render that always
          ;; drops every vector would still pass the negation checks.
          (let [main-node (rf.story.test-helpers.e2e-multi-frame/find-by-data-attr tree
                                                 :aria-label "Story canvas")]
            (is (some? main-node)
                "<main aria-label=\"Story canvas\"> is still in the
                 tree — full-screen hides chrome, not canvas")))))))

;; ---- pipeline (3): default (no full-screen) → chrome present ------------

(deftest default-render-preserves-chrome-panes
  (testing "without `:full-screen?` set (the default shape),
            shell's reagent-render produces a hiccup tree where the
            three chrome guards all admit their components. Pins the
            inverse so the elision test above can't trivially pass."
    (rf.story.test-helpers.e2e-multi-frame/with-story-and-xray-frames
      {}
      (fn []
        (let [tree            (render-shell-tree)
              root-attrs      (when (and (vector? tree) (map? (second tree)))
                                (second tree))
              sidebar-nodes   (fn-headed-nodes tree #(= rf.story.ui.sidebar/sidebar %))
              toolbar-by-attr (rf.story.test-helpers.e2e-multi-frame/find-by-test-id tree "story-toolbar")
              inspectors-node (rf.story.test-helpers.e2e-multi-frame/find-by-test-id tree "story-inspectors")]
          (is (= "false" (:data-rf-chrome-fullscreen root-attrs))
              "shell wrapper carries data-rf-chrome-fullscreen=false")
          (is (seq sidebar-nodes)
              "[rf.story.ui.sidebar/sidebar] vector present in the tree by default")
          (is (some? toolbar-by-attr)
              "data-test=\"story-toolbar\" element present by default")
          (is (some? inspectors-node)
              "data-test=\"story-inspectors\" element present by default"))))))

;; ---- pipeline (4): full-round-trip `f` → `f` -----------------------------

(deftest f-keydown-round-trip-flips-render-twice
  (testing "two `f` keydowns round-trip:
              press 1 → chrome elides;
              press 2 → chrome returns.
            Asserts the keydown→state→render pipeline is stable across
            both transitions (not just the activate edge)."
    (rf.story.test-helpers.e2e-multi-frame/with-story-and-xray-frames
      {}
      (fn []
        (dispatch! (rf.story.test-helpers.e2e-multi-frame/fake-event {:key "f"}))
        (let [tree-on (render-shell-tree)
              sidebar-on (fn-headed-nodes tree-on #(= rf.story.ui.sidebar/sidebar %))]
          (is (empty? sidebar-on)
              "after first `f` keydown, no sidebar in tree"))
        (dispatch! (rf.story.test-helpers.e2e-multi-frame/fake-event {:key "f"}))
        (let [tree-off (render-shell-tree)
              sidebar-off (fn-headed-nodes tree-off #(= rf.story.ui.sidebar/sidebar %))]
          (is (seq sidebar-off)
              "after second `f` keydown, sidebar is back in tree"))))))
