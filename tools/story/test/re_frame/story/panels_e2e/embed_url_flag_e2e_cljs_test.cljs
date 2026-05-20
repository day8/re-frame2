(ns re-frame.story.panels-e2e.embed-url-flag-e2e-cljs-test
  "Multi-frame e2e coverage for the `?embed=1` URL chrome-hide flag
  (rf2-k4fds, replaces the Playwright `embed=1` smoke).

  Per spec/Conventions.md §Embed mode (rf2-pucku) the Story chrome
  (sidebar / toolbar / inspectors) elides when the page is loaded
  with `?embed=1` in `window.location.search`. The chrome-visibility
  resolver wires it through `[:chrome-visibility :embed?]` on the
  shell-state ratom; `chrome-pane-visible?` returns false for every
  pane when that slot is true.

  ## Pipeline under test

      ?embed=1 in URL
            │
            ▼
      url-state/embed-flag-from-current-url   ← URL parser
            │
            ▼
      url-state/hydrate-embed-flag!           ← seeds shell-state
            │
            ▼
      [:chrome-visibility :embed?] = true
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
      still renders.

  ## What the unit tests in `chrome_visibility_test` already cover

  Pure data:
  - `chrome-visibility-defaults` / `chrome-visibility` / `set` / `toggle`
  - `chrome-pane-visible?` precedence (embed > full-screen > per-pane)

  ## What this e2e test adds

  1. The URL→state seam: `hydrate-embed-flag!` reads from
     `embed-flag-from-current-url` and writes
     `[:chrome-visibility :embed?]` on the live shell-state-atom.
     `with-redefs` stubs the parser so we don't fight jsdom under
     the node runner — the parser itself is exercised by
     `url-state-cljs-test`.

  2. The state→render seam: invoking shell's `:reagent-render` with
     `:embed?` true produces a hiccup tree where the three chrome
     guards (`(when show-tb?)`, `(when show-sb?)`, `(when show-rhs?)`)
     all elide — no `[sidebar/sidebar]` / `[toolbar/toolbar-strip]` /
     `[right-panel]` vector survives in the tree, while the
     `[main-pane]` slot still renders (the variant viewport).

  3. The inverse seam: `:embed?` false leaves chrome present — guards
     the test fixture against trivial-pass shapes (a render that
     always drops every vector would still 'pass' assertion 2).

  No DOM, no React mount, no Playwright. Sub-millisecond per case."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.core :as r]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.story.test-helpers.e2e-multi-frame :as e2e]
            [re-frame.story.ui.state :as ui-state]
            [re-frame.story.ui.shell :as shell]
            [re-frame.story.ui.sidebar :as sidebar]
            [re-frame.story.ui.toolbar :as toolbar]
            [re-frame.story.ui.url-state :as url-state]))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory {:adapter plain-atom/adapter}))

;; ---- helpers: extract shell's `:reagent-render` -------------------------
;;
;; `shell/shell` is a class-3 Reagent component (`r/create-class` is
;; called inline; the spec map isn't exposed). To exercise the
;; render path under the node runner we capture the spec by stubbing
;; `r/create-class` with `identity` for the duration of the call —
;; the returned spec map's `:reagent-render` slot is the 0-arg fn
;; the live React class would invoke per render. We invoke it
;; directly to harvest the hiccup the renderer would see.

(defn- ^:no-doc capture-spec
  "Helper used by `shell-render-fn` below — passes the create-class
  spec through unchanged so the test harvests the same map a live
  React class would. Multi-arity to match `reagent.core/create-class`'s
  1-and-2-arg shape (the shell calls the 1-arg form, but the stub
  must define both arities so `with-redefs` doesn't drop one and
  trigger `arity$N is not a function` at the call site)."
  ([spec] spec)
  ([spec _compiler] spec))

(defn- shell-render-fn
  "Capture shell's `:reagent-render`. Single-shot — re-extract per
  test invocation so any state-atom mutation between calls is
  reflected on the next render."
  []
  (let [spec (with-redefs [r/create-class capture-spec] (shell/shell))]
    (:reagent-render spec)))

(defn- render-shell-tree
  "Render shell with the current `state/shell-state-atom`. Returns
  the expanded hiccup tree (class-1 / class-2 fns invoked where
  possible; class-3 components preserved per `expand-tree`'s
  contract)."
  []
  (let [render-fn (shell-render-fn)]
    (e2e/expand-tree (render-fn))))

(defn- fn-headed-nodes
  "Every node whose `(first node)` is a function value. Used to
  locate `[component-fn ...]` slots in the shell tree directly
  (the chrome guards' immediate children are class-2 / class-3
  vectors whose data-test attrs live INSIDE the component bodies,
  not on the vector itself)."
  [tree pred]
  (filterv (fn [node]
             (and (vector? node)
                  (pos? (count node))
                  (fn? (first node))
                  (pred (first node))))
           (e2e/hiccup-seq tree)))

;; ---- Pipeline (1): URL → shell-state hydrate ----------------------------

(deftest hydrate-embed-flag-seeds-true-when-parser-returns-true
  (testing "rf2-pucku — `hydrate-embed-flag!` writes :embed? true to
            the shell-state-atom when the URL parser reports the flag.
            The parser itself is covered by url-state-cljs-test; this
            seam test pins the parser→state hand-off."
    (e2e/with-story-and-causa-frames
      {}
      (fn []
        (with-redefs [url-state/embed-flag-from-current-url (constantly true)]
          (let [returned (url-state/hydrate-embed-flag! ui-state/shell-state-atom)]
            (is (true? returned)
                "hydrate returns the parsed value")
            (is (true? (get-in (ui-state/get-state)
                               [:chrome-visibility :embed?]))
                "shell-state-atom carries :embed? true after hydrate")))))))

(deftest hydrate-embed-flag-seeds-false-without-flag
  (testing "rf2-pucku — `hydrate-embed-flag!` writes :embed? false
            (the default-chrome shape) when no `?embed=1` is in the
            URL. Pins the 'absent param → chrome on' contract."
    (e2e/with-story-and-causa-frames
      {}
      (fn []
        (with-redefs [url-state/embed-flag-from-current-url (constantly false)]
          (let [returned (url-state/hydrate-embed-flag! ui-state/shell-state-atom)]
            (is (false? returned)
                "hydrate returns false when no flag")
            (is (false? (get-in (ui-state/get-state)
                                [:chrome-visibility :embed?]))
                "shell-state-atom carries :embed? false (the default)")))))))

;; ---- Pipeline (2): :embed? true → chrome elides at render --------------

(deftest embed-mode-hides-every-chrome-pane
  (testing "rf2-k4fds — with `:embed?` true on the shell-state-atom,
            shell's reagent-render produces a hiccup tree where
            sidebar / toolbar / right-panel guards all elide. The
            `[main-pane]` vector (which contains the variant canvas)
            still renders so the viewport remains visible."
    (e2e/with-story-and-causa-frames
      {}
      (fn []
        ;; Seed embed-mode directly — same effective state
        ;; `hydrate-embed-flag!` produces with the parser returning
        ;; true (covered above). This separates the URL-seam test
        ;; from the render-seam test so a failure points at the
        ;; right layer.
        (swap! ui-state/shell-state-atom
               assoc-in [:chrome-visibility :embed?] true)
        (let [tree (render-shell-tree)
              ;; Top-level wrapper's `data-rf-chrome-embed` reflects
              ;; the live :embed? slot — confirms the render saw the
              ;; embed-mode state.
              root-attrs (when (and (vector? tree) (map? (second tree)))
                           (second tree))
              ;; Locate any [sidebar/sidebar ...] vector in the tree —
              ;; the shell's `(when show-sb? [sidebar/sidebar ...])`
              ;; guard would elide this when :embed? is true.
              sidebar-nodes (fn-headed-nodes tree #(= sidebar/sidebar %))
              ;; Same for the toolbar — `toolbar-strip` is class-1
              ;; (expands to a `<header data-test="story-toolbar">`)
              ;; so we can also assert its data-test marker is gone.
              toolbar-nodes (fn-headed-nodes tree #(= toolbar/toolbar-strip %))
              toolbar-by-attr (e2e/find-by-test-id tree "story-toolbar")
              ;; right-panel is class-1 (returns hiccup with
              ;; data-test="story-inspectors"). Assert its
              ;; data-test marker is absent.
              inspectors-node (e2e/find-by-test-id tree "story-inspectors")]
          (is (= "true" (:data-rf-chrome-embed root-attrs))
              "shell wrapper carries data-rf-chrome-embed=true — the
               render saw the embed-mode state")
          (is (empty? sidebar-nodes)
              "no [sidebar/sidebar ...] vector in the tree — the
               sidebar guard elided per rf2-pucku")
          (is (empty? toolbar-nodes)
              "no [toolbar/toolbar-strip] vector in the tree")
          (is (nil? toolbar-by-attr)
              "no data-test=\"story-toolbar\" element — the
               toolbar's <header> never rendered")
          (is (nil? inspectors-node)
              "no data-test=\"story-inspectors\" element — the
               right-panel never rendered")
          ;; Inverse-shape check: the variant viewport's <main>
          ;; landmark must still be in the tree. Without this
          ;; assertion a render that always drops every chrome
          ;; vector would still pass the negation checks above.
          ;; `main-pane` (defn- in shell.cljs) is class-1 and
          ;; expands to `[:main {:aria-label "Story canvas" ...}]`.
          (let [main-node (e2e/find-by-data-attr tree
                                                 :aria-label "Story canvas")]
            (is (some? main-node)
                "<main aria-label=\"Story canvas\"> is still in the
                 tree — embed-mode hides chrome, not canvas")))))))

;; ---- Pipeline (3): :embed? false → chrome present (inverse) ------------

(deftest no-embed-flag-leaves-chrome-visible
  (testing "rf2-k4fds — without `:embed?` set (the default shape),
            shell's reagent-render produces a hiccup tree where the
            three chrome guards all admit their components. Pins the
            inverse so the assertions above can't trivially pass on
            a render that drops every vector."
    (e2e/with-story-and-causa-frames
      {}
      (fn []
        ;; No mutation — `with-story-and-causa-frames`'s setup
        ;; resets the shell-state-atom to its default shape, which
        ;; has every chrome pane toggled on.
        (let [tree (render-shell-tree)
              root-attrs (when (and (vector? tree) (map? (second tree)))
                           (second tree))
              sidebar-nodes (fn-headed-nodes tree #(= sidebar/sidebar %))
              toolbar-by-attr (e2e/find-by-test-id tree "story-toolbar")
              inspectors-node (e2e/find-by-test-id tree "story-inspectors")]
          (is (= "false" (:data-rf-chrome-embed root-attrs))
              "shell wrapper carries data-rf-chrome-embed=false at default")
          (is (seq sidebar-nodes)
              "[sidebar/sidebar ...] vector present in the tree — the
               sidebar guard admits the component at default")
          (is (some? toolbar-by-attr)
              "data-test=\"story-toolbar\" element present at default")
          (is (some? inspectors-node)
              "data-test=\"story-inspectors\" element present at default"))))))
