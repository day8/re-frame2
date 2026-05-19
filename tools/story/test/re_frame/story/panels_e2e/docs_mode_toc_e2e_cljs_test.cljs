(ns re-frame.story.panels-e2e.docs-mode-toc-e2e-cljs-test
  "Multi-frame e2e coverage for the `:docs` mode pane TOC (rf2-y3w2q;
  rf2-8c7tk).

  The `:docs` mode pane composes header / prose / args / decorators /
  parameters / tags sections vertically; the TOC pane on the right
  edge of the layout (per spec/008 + rf2-8c7tk) exposes a sticky
  table-of-contents that lets the reader jump between sections.

  ## What this catches

  - **Layout wires the TOC pane**: rendering `docs/docs-view` for a
    variant produces a layout whose final child is an (unexpanded)
    `[toc-pane variant-id]` invocation. A regression that drops the
    TOC entry from `docs-view`'s flex layout would leave a section
    list with no in-page navigation — silently degraded UX.

  - **Section anchors match the canonical TOC table**: every entry
    in `docs-toc-entries` (e.g. `\"docs-args\"`, `\"docs-decorators\"`)
    appears as a `:section {:id <anchor>}` in the rendered docs view.
    A regression that renamed an anchor without updating the TOC
    table (or vice versa) would leave the jump buttons pointing at
    non-existent ids — `getElementById` returns nil and the
    `scrollIntoView` no-ops.

  - **Prose entry pruning honours the registry**: `visible-toc-entries`
    drops the `\"docs-prose\"` row when no `:prose`-layout workspace
    references the variant. The TOC visible to the user mirrors the
    surfaces actually rendered in the page (no orphan TOC entry).

  - **Entry into docs mode is the mode-tab transition**: the docs
    pane mounts when `:active-mode-tab` for the focused variant is
    set to `:docs` (via `transitions/set-active-mode-tab`). This is
    the canonical entry point exercised in the shell render path
    (`shell/main-pane`'s `case mode-tab`).

  ## Why a multi-frame e2e (not Playwright)

  The bead originally specced this as a Playwright assertion. Per
  session direction, e2e coverage is moving to multi-frame CLJS
  Node tests — sub-millisecond per case, no DOM, no browser, and
  the layered hiccup walk catches the same wiring bugs the
  browser scenario would have.

  Surface tested via hiccup walking; no DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.story :as story]
            [re-frame.story.ui.docs :as docs]
            [re-frame.story.ui.state :as ui-state]
            [re-frame.story.ui.state.transitions :as transitions]
            [re-frame.story.test-helpers.e2e-multi-frame :as e2e]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- fixture -----------------------------------------------------------

(def ^:private variant-id           :story.docs-rich/v)
(def ^:private no-prose-variant-id  :story.docs-no-prose/v)

(defn- register-variants!
  "Two variants:

   - `:story.docs-rich/v` is referenced by a `:prose`-layout workspace
     (the prose entry should appear in `visible-toc-entries`).
   - `:story.docs-no-prose/v` has no prose workspace (the prose entry
     should be pruned)."
  []
  (story/reg-story :story.docs-rich
    {:doc       "Rich variant fixture for docs-pane TOC scenarios."
     :argtypes  {:label {:doc "user-visible label"}}
     :tags      #{:dev :docs}})
  (story/reg-variant variant-id
    {:doc    "The happy-path docs variant — prose, args, tags."
     :args   {:label "Hello"}
     :tags   #{:dev :docs :test}
     :events []})
  (story/reg-workspace :Workspace.docs-rich/prose-ws
    {:layout  :prose
     :content [{:type :variant :id variant-id}
               {:type :prose   :body "Prose copy referencing the variant."}]})
  (story/reg-story :story.docs-no-prose {:doc "Variant with no prose."})
  (story/reg-variant no-prose-variant-id
    {:doc    "No-prose variant — prose TOC entry should be pruned."
     :events []}))

;; ---- helpers -----------------------------------------------------------

(defn- render-docs-view
  "Render the `:docs` mode pane for `vid`. Wrapper kept for clarity at
  call sites."
  [vid]
  (docs/docs-view vid))

(defn- section-ids
  "Walk the expanded hiccup tree and return every `:section` node's
  `:id` attribute. Used to verify every TOC anchor target has a real
  in-page element."
  [tree]
  (->> (e2e/hiccup-seq tree)
       (keep (fn [node]
               (when (and (vector? node) (= :section (first node)))
                 (let [maybe-attrs (second node)]
                   (when (map? maybe-attrs)
                     (:id maybe-attrs))))))
       (remove nil?)
       (vec)))

(defn- toc-pane-vector
  "Return the unexpanded `[toc-pane variant-id]` hiccup vector by
  walking the tree for a 2-element vector whose first element is a
  function and whose second is the variant-id.

  The TOC pane is a class-3 Reagent component (it owns an
  IntersectionObserver across mount/unmount), so the framework
  helper's `expand-tree` preserves the unexpanded `[toc-pane vid]`
  vector verbatim. Locating it proves the layout wires the TOC."
  [tree vid]
  (some (fn [node]
          (when (and (vector? node)
                     (= 2 (count node))
                     (fn? (first node))
                     (= vid (second node)))
            node))
        (e2e/hiccup-seq tree)))

;; ===========================================================================
;; rf2-y3w2q — TOC pane is wired into the docs-view layout
;; ===========================================================================

(deftest docs-view-layout-mounts-the-toc-pane
  (testing "rf2-y3w2q / rf2-8c7tk — rendering docs-view produces a
            layout whose final child is the (class-3) TOC pane vector.
            A regression that drops the TOC entry would leave the
            section list without in-page navigation."
    (e2e/with-story-and-causa-frames
      {:register-stories register-variants!}
      (fn []
        (e2e/select-variant! variant-id)
        (let [tree    (render-docs-view variant-id)
              layout  (e2e/find-by-test-id tree "story-docs-layout")
              view    (e2e/find-by-test-id tree "story-docs-view")
              toc-vec (toc-pane-vector tree variant-id)]
          (is (some? layout)
              "story-docs-layout wrapper present — the flex container
               that hosts the docs view + TOC sidebar")
          (is (some? view)
              "story-docs-view section present — the main column the
               TOC anchors point into")
          (is (some? toc-vec)
              "the docs layout includes a class-3 [toc-pane variant-id]
               invocation as a sibling of the docs-view column (rf2-8c7tk
               wires the right-edge TOC sidebar)")
          (is (= variant-id (second toc-vec))
              "the TOC pane receives the same variant-id the docs-view
               renders — its visible-toc-entries call agrees with the
               rendered sections"))))))

;; ===========================================================================
;; rf2-y3w2q — TOC anchors match the rendered sections
;; ===========================================================================

(deftest toc-targets-match-rendered-section-anchors
  (testing "rf2-8c7tk — every entry in `visible-toc-entries` has a
            matching `:section` anchor id in the rendered docs view.
            A regression that renamed a section without updating the
            TOC table (or vice versa) would point the jump buttons
            at non-existent ids."
    (e2e/with-story-and-causa-frames
      {:register-stories register-variants!}
      (fn []
        (e2e/select-variant! variant-id)
        (let [tree           (render-docs-view variant-id)
              visible        (docs/visible-toc-entries variant-id)
              visible-ids    (set (map :id visible))
              rendered-ids   (set (section-ids tree))]
          (is (pos? (count visible))
              "fixture surfaces at least one visible TOC entry")
          ;; The prose entry IS visible for the rich variant — the
          ;; workspace registration above ensures `prose-for-variant`
          ;; is non-empty.
          (is (contains? visible-ids "docs-prose")
              "rich variant carries a :prose workspace — the prose TOC
               entry is visible")
          ;; Every visible TOC id resolves to an in-page section anchor —
          ;; the contract that makes the jump-to-anchor button work.
          (doseq [tid visible-ids]
            (is (contains? rendered-ids tid)
                (str "TOC entry " (pr-str tid)
                     " has a corresponding `:section {:id ...}` anchor
                      in the rendered docs view — the jump target is
                      reachable via getElementById"))))))))

;; ===========================================================================
;; rf2-y3w2q — prose entry pruning mirrors the registry
;; ===========================================================================

(deftest prose-toc-entry-pruned-when-no-prose-workspace
  (testing "rf2-8c7tk — a variant with no referencing `:prose`-layout
            workspace produces a visible TOC list with the prose entry
            pruned. Pairs with the inverse: the rich variant DOES
            surface the prose row."
    (e2e/with-story-and-causa-frames
      {:register-stories register-variants!}
      (fn []
        ;; Rich variant: prose workspace is registered → prose IS visible.
        (let [rich (docs/visible-toc-entries variant-id)
              ids  (set (map :id rich))]
          (is (contains? ids "docs-prose")
              "rich variant: prose TOC entry surfaces because the
               registered :prose workspace references it"))
        ;; No-prose variant: no workspace → prose TOC entry pruned.
        (let [empty-prose (docs/visible-toc-entries no-prose-variant-id)
              ids         (set (map :id empty-prose))]
          (is (not (contains? ids "docs-prose"))
              "no-prose variant: prose TOC entry pruned — the renderer
               omits the prose section, so the TOC must omit the link")
          (is (= ["docs-args" "docs-decorators" "docs-parameters" "docs-tags"]
                 (mapv :id empty-prose))
              "the four unconditional TOC entries remain — in declared
               order, sorted by docs-toc-entries' source position"))))))

;; ===========================================================================
;; rf2-y3w2q — entering docs mode mounts the docs-view (incl. TOC)
;; ===========================================================================

(deftest set-active-mode-tab-docs-routes-to-docs-view-with-toc
  (testing "rf2-y3w2q — the canonical entry into docs mode is
            `transitions/set-active-mode-tab variant-id :docs`. After
            that transition `state/active-mode-tab` reports `:docs`,
            and rendering the docs-view for the focused variant yields
            a layout that includes the TOC pane invocation.

            The shell `main-pane`'s `case mode-tab` reads exactly this
            slot to choose between `:dev` / `:docs` / `:test` — so
            pinning the transition + the resulting layout shape
            covers the shell's docs-pane mount path end-to-end."
    (e2e/with-story-and-causa-frames
      {:register-stories register-variants!}
      (fn []
        (e2e/select-variant! variant-id)
        ;; Default mode-tab is :dev — verify by reading the slot pre-
        ;; transition.
        (is (not= :docs (ui-state/active-mode-tab
                          (ui-state/get-state) variant-id))
            "default active-mode-tab is :dev (or otherwise non-:docs)")
        ;; Switch to docs mode.
        (ui-state/swap-state! transitions/set-active-mode-tab
                              variant-id :docs)
        (is (= :docs (ui-state/active-mode-tab
                       (ui-state/get-state) variant-id))
            ":active-mode-tab flipped to :docs — the shell mounts the
             docs-view for this variant")
        ;; Render the docs-view (what main-pane's `:docs` branch
        ;; mounts) and assert the TOC is part of the layout.
        (let [tree    (render-docs-view variant-id)
              layout  (e2e/find-by-test-id tree "story-docs-layout")
              toc-vec (toc-pane-vector tree variant-id)]
          (is (some? layout)
              "docs-view layout renders for the focused variant")
          (is (some? toc-vec)
              "the layout includes the TOC pane — entering docs mode
               surfaces the table-of-contents alongside the section
               column (rf2-8c7tk)"))))))
