(ns re-frame.story.ui.command-palette.view-cljs-test
  "CLJS-side regression net for Story's command palette panel
  (rf2-9i7oj — every other Story UI panel ships a `*_view_cljs_test`
  companion; the palette is the dispatch-everything surface and
  carried zero render coverage before this).

  Pairs with the pure-data tests for `palette/entries`,
  `palette/search`, `palette/clamp-active-index`, and
  `palette/move-active-index` in `re_frame/story_ui_test.clj`
  (JVM) and `re_frame/story_ui_cljs_test.cljs` (CLJS). This
  namespace pins the renderable
  states of `view/render-palette` — the pure projection that
  `command-palette-host` delegates to once `open?` flips true:

  - **testid presence** — the root scrim carries the
    `story-command-palette` `data-test` selector spec/008 contracts
    against. Pin the selector so a future refactor that loses the
    attribute breaks loudly.

  - **render-on-empty state** — with no results the panel renders
    the `story-command-palette-empty` placeholder and NO
    `story-command-palette-result` rows.

  - **render-with-results state** — with a populated result list
    the panel renders one `story-command-palette-result` row per
    entry; each row carries the entry's `:data-kind` and `:data-id`
    so click-binding tests downstream can anchor by row.

  - **render-with-active-highlight state** — the row at index
    `:active` carries `aria-selected=true`; every other row carries
    `aria-selected=false`. This branch is the palette's analogue of
    the bead's `recents-boost` scope — the only state-distinguishing
    render path within the results list (there is no recents-boost
    feature in the current implementation; if/when one ships, add
    a fifth test pinning its render shape).

  Per spec/008 the renderer is a thin projection over the pure-data
  helpers and a prop map; calling `render-palette` directly walks
  every branch without booting reagent's class-3 lifecycle."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame.test-helpers :as th]
            [re-frame.story.ui.command-palette.view :as view]))

;; ---- fixtures ------------------------------------------------------------

(defn- mk-entry
  "Build a palette entry shaped like `palette/entries` output. Each
  entry carries the keys `render-palette` reads: `:kind`, `:kind-label`,
  `:id`, `:id-label`, `:doc`."
  [kind id-label & [doc]]
  {:kind       kind
   :kind-label (str/capitalize (name kind))
   :id         (keyword id-label)
   :id-label   id-label
   :doc        (or doc "")})

(defn- noop [& _])

(defn- base-props
  "Build a baseline prop map for `render-palette` with handler slots
  stubbed to no-ops. Tests merge their case-specific overrides on
  top."
  []
  {:query            ""
   :results          []
   :active           0
   :input-ref        noop
   :on-close         noop
   :on-input-change  noop
   :on-input-keydown noop
   :on-row-hover     noop
   :on-row-select    noop})

;; ===========================================================================
;; rf2-9i7oj — testid presence
;;
;; Pin that the root scrim carries the `story-command-palette` data-test
;; selector. Spec/008 contracts on this selector for e2e tests; a
;; refactor that loses the attribute breaks the contract silently
;; without this test.
;; ===========================================================================

(deftest root-carries-data-test-selector
  (testing "the root scrim of the rendered palette carries
            `data-test=story-command-palette` so downstream tests
            (and Causa's spine instrumentation) can anchor on it."
    (let [tree (view/render-palette (base-props))
          root (th/find-by-attr tree :data-test "story-command-palette")]
      (is (some? root)
          "root scrim node present with the spec/008-documented selector")
      (is (= :div (first root))
          "root is a :div — the scrim overlay container")
      (is (some? (th/find-by-attr tree :data-test "story-command-palette-input"))
          "input field present with its data-test selector"))))

;; ===========================================================================
;; rf2-9i7oj — render-on-empty state
;;
;; With no results the panel renders the `story-command-palette-empty`
;; placeholder and NO `story-command-palette-result` rows.
;; ===========================================================================

(deftest empty-state-renders-placeholder
  (testing "with `:results []` the panel renders the empty-state
            placeholder and zero result rows. Proves the
            `(if (seq results) ... empty)` branch."
    (let [tree     (view/render-palette (base-props))
          empty    (th/find-by-attr tree :data-test "story-command-palette-empty")
          results  (th/find-all-by-attr tree :data-test "story-command-palette-result")]
      (is (some? empty)
          "empty-state placeholder present")
      (is (= "No matching registry entries." (th/text-content empty))
          "placeholder copy matches spec/008")
      (is (zero? (count results))
          "no result rows rendered when results is empty"))))

;; ===========================================================================
;; rf2-9i7oj — render-with-results state
;;
;; With a populated result list the panel renders one
;; `story-command-palette-result` row per entry; the empty placeholder
;; is NOT rendered. Each row carries `:data-kind` and `:data-id`
;; mirroring the entry — downstream click-binding tests anchor by row.
;; ===========================================================================

(deftest results-state-renders-one-row-per-entry
  (testing "with a populated result list the panel renders one row per
            entry and omits the empty-state placeholder. Each row
            carries the entry's `:data-kind` and `:data-id` so tests
            can anchor on a specific row."
    (let [entries [(mk-entry :variant   ":app/login"     "Login variant")
                   (mk-entry :workspace ":app/sandbox"   "Workspace sandbox")
                   (mk-entry :story     ":app/counters"  "Counter family")]
          tree    (view/render-palette
                    (assoc (base-props) :results entries))
          rows    (th/find-all-by-attr tree :data-test "story-command-palette-result")
          empty   (th/find-by-attr   tree :data-test "story-command-palette-empty")]
      (is (= 3 (count rows))
          "one row per entry in the result list")
      (is (nil? empty)
          "empty-state placeholder is NOT rendered when results are present")
      (is (= [":app/login" ":app/sandbox" ":app/counters"]
             (mapv #(get (second %) :data-id) rows))
          "rows carry the entries' :id-label in order")
      (is (= ["variant" "workspace" "story"]
             (mapv #(get (second %) :data-kind) rows))
          "rows carry the entries' kind name in order"))))

;; ===========================================================================
;; rf2-9i7oj — render-with-active-highlight state
;;
;; The row at index `:active` carries `aria-selected=true`; every other
;; row carries `aria-selected=false`. This is the palette's analogue of
;; the bead's `recents-boost` scope — the only state-distinguishing
;; render path within the results list. (No recents-boost feature
;; exists in the current implementation; if/when one ships, add a
;; further test pinning its render shape.)
;; ===========================================================================

(deftest active-row-carries-aria-selected-true
  (testing "the row at `:active` carries `aria-selected=true` and the
            other rows carry `aria-selected=false`. The active row
            also picks up the `:row-active` style merged onto its
            `:style` map — pinning the aria attribute is sufficient
            to prove the active-row branch."
    (let [entries [(mk-entry :variant   ":a/one")
                   (mk-entry :variant   ":a/two")
                   (mk-entry :workspace ":a/three")]
          tree    (view/render-palette
                    (assoc (base-props)
                           :results entries
                           :active  1))
          rows    (th/find-all-by-attr tree :data-test "story-command-palette-result")
          aria    (mapv #(get (second %) :aria-selected) rows)]
      (is (= 3 (count rows)))
      (is (= ["false" "true" "false"] aria)
          "only the row at :active carries aria-selected=true")
      ;; Sanity: shifting :active shifts the highlighted row.
      (let [tree2 (view/render-palette
                    (assoc (base-props)
                           :results entries
                           :active  2))
            aria2 (mapv #(get (second %) :aria-selected)
                        (th/find-all-by-attr tree2 :data-test
                                             "story-command-palette-result"))]
        (is (= ["false" "false" "true"] aria2)
            "moving :active to 2 highlights the third row only")))))
