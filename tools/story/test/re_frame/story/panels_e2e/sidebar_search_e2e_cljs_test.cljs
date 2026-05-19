(ns re-frame.story.panels-e2e.sidebar-search-e2e-cljs-test
  "Multi-frame e2e coverage for the sidebar search-as-you-type filter
  (rf2-piucm; rf2-yngai).

  The pure helpers under `re-frame.story.ui.sidebar-search` already
  have a JVM-portable test corpus
  (`sidebar_search_test.cljc`). This namespace exercises the
  **integration** at the live sidebar hiccup level: register variants,
  render the sidebar, drive the `:on-change` handler on the search
  `<input>`, and assert the surviving variant rows match the live
  `filter-grouped-tree` projection.

  ## What this catches

  - The plumbing between the search input's `:on-change` handler and
    the closure `query-ratom` it writes to — a regression that drops
    the wire (e.g. forgotten ratom alias) would not be caught by the
    pure-helper tests.
  - `filter-grouped-tree` is wired between the live registry snapshot
    and the rendered variant rows — pinning the integration prevents
    a drift between the pure helper output and what the sidebar shows.
  - Empty / blank queries restore every registered variant (the no-op
    filter shape).

  ## Surface tested via hiccup walking

  The sidebar's `:on-change` handler is hidden inside a Reagent class-
  2 form (the function-returning-fn closure that owns the query
  ratom). We render the sidebar (`(sidebar)` returns the inner
  render fn), walk the produced hiccup to find the
  `story-sidebar-search-input`, invoke its `:on-change` with a
  synthetic event carrying `:value`, then re-render and walk to count
  surviving variant rows.

  Sub-millisecond per case; no DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.story :as story]
            [re-frame.story.ui.sidebar :as sidebar]
            [re-frame.story.ui.sidebar-search :as search]
            [re-frame.story.ui.state :as ui-state]
            [re-frame.story.test-helpers.e2e-multi-frame :as e2e]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

;; A canonical fixture: two stories, four variants. The names are
;; chosen so the AND-token discriminator has distinct hits for each
;; query the tests below issue.

(defn- register-variants! []
  (story/reg-story :story.counter
    {:doc "Counter parent story."})
  (story/reg-variant :story.counter/empty
    {:doc "empty counter" :events []})
  (story/reg-variant :story.counter/at-five
    {:doc "counter pre-seeded at five" :events []})
  (story/reg-story :story.login
    {:doc "Login parent story."})
  (story/reg-variant :story.login/blank
    {:doc "blank login form" :events []})
  (story/reg-variant :story.login/loaded
    {:doc "login pre-populated" :events []}))

;; ---- helpers: walk the sidebar's expanded hiccup to drive search --------

(defn- render-sidebar
  "`sidebar/sidebar` is a class-2 Reagent component — calling it
  returns the inner render fn. Invoking THAT returns the actual
  hiccup. Returns a tuple `[render-fn first-tree]` so the caller can
  re-render after mutating the closure-bound ratom (`on-change`'s
  side effect)."
  []
  (let [render-fn (sidebar/sidebar)
        first-tree (render-fn nil)]
    [render-fn first-tree]))

(defn- search-input-node
  "Locate the search `<input>` node by data-test attribute. Returns
  the hiccup vector."
  [tree]
  (e2e/find-by-test-id tree "story-sidebar-search-input"))

(defn- type-query!
  "Invoke the search input's `:on-change` with a synthetic event
  whose target carries `:value = q`. Side effect: mutates the
  closure ratom so the next `(render-fn nil)` call returns the
  filtered tree."
  [tree q]
  (let [input   (search-input-node tree)
        on-chg  (e2e/handler-for input :on-change)]
    (when on-chg (on-chg (e2e/fake-event {:value q})))))

(defn- variant-rows
  "Every `story-sidebar-variant-row` node in the expanded tree."
  [tree]
  (e2e/find-all-by-test-id tree "story-sidebar-variant-row"))

(defn- visible-variant-ids
  "Pull `:data-variant` strings off each variant row; this is what
  the user actually sees in the rendered sidebar."
  [tree]
  (->> (variant-rows tree)
       (map (fn [n] (get-in n [1 :data-variant])))
       (remove nil?)
       set))

;; ---- empty query: every registered variant shows ----------------------

(deftest empty-query-renders-every-variant
  (testing "with no search query, the sidebar renders all 4 variants"
    (e2e/with-story-and-causa-frames
      {:register-stories register-variants!}
      (fn []
        (let [[_ tree] (render-sidebar)
              visible  (visible-variant-ids tree)]
          (is (= #{":story.counter/empty"
                   ":story.counter/at-five"
                   ":story.login/blank"
                   ":story.login/loaded"}
                 visible)
              "every registered variant rendered when query is empty"))))))

;; ---- typed query narrows the tree -------------------------------------

(deftest typed-query-narrows-to-counter-rows
  (testing "typing `counter` narrows the tree to the counter parent +
            its variants only (login variants drop out)"
    (e2e/with-story-and-causa-frames
      {:register-stories register-variants!}
      (fn []
        (let [[render tree-before] (render-sidebar)]
          (type-query! tree-before "counter")
          (let [tree-after (render nil)
                visible    (visible-variant-ids tree-after)]
            (is (= #{":story.counter/empty"
                     ":story.counter/at-five"}
                   visible)
                "the search input's on-change wired to the closure ratom;
                 the re-render projected through filter-grouped-tree
                 and the login variants dropped from the rendered tree")))))))

(deftest typed-query-narrows-by-variant-name
  (testing "typing `five` narrows to the single variant whose id
            contains the substring"
    (e2e/with-story-and-causa-frames
      {:register-stories register-variants!}
      (fn []
        (let [[render tree-before] (render-sidebar)]
          (type-query! tree-before "five")
          (let [tree-after (render nil)
                visible    (visible-variant-ids tree-after)]
            (is (= #{":story.counter/at-five"} visible)
                "only the at-five variant survives a `five` token filter")))))))

(deftest typed-query-clears-back-to-full-tree
  (testing "Clearing the search (back to empty) restores every variant"
    (e2e/with-story-and-causa-frames
      {:register-stories register-variants!}
      (fn []
        (let [[render tree-before] (render-sidebar)]
          (type-query! tree-before "counter")
          (let [tree-mid     (render nil)
                visible-mid  (visible-variant-ids tree-mid)]
            (is (= 2 (count visible-mid))
                "filter narrowed to 2 rows during the typed query"))
          (type-query! (render nil) "")  ;; clear
          (let [tree-after  (render nil)
                visible-end (visible-variant-ids tree-after)]
            (is (= 4 (count visible-end))
                "all 4 variants back when the query empties")))))))

;; ---- live filter projection matches the pure helper -------------------

(deftest sidebar-tree-matches-pure-filter-projection
  (testing "rf2-yngai — the rendered tree's variant set MUST equal what
            `filter-grouped-tree` returns when given the same registry
            + query. Pins the integration between the live sidebar and
            the pure helper so the two cannot drift."
    (e2e/with-story-and-causa-frames
      {:register-stories register-variants!}
      (fn []
        (let [[render tree-0] (render-sidebar)]
          (type-query! tree-0 "login")
          (let [registry-snapshot (ui-state/registry-snapshot)
                grouped-all       (ui-state/group-variants-by-story
                                    (:variants registry-snapshot))
                pure-filtered     (search/filter-grouped-tree grouped-all "login")
                pure-variant-ids  (->> pure-filtered
                                       (mapcat :variants)
                                       (map (fn [[vid _]] (str vid)))
                                       set)
                tree-after        (render nil)
                visible           (visible-variant-ids tree-after)]
            (is (= pure-variant-ids visible)
                "the rendered variant rows mirror filter-grouped-tree's
                 output 1:1 — no drift between the pure helper and the
                 sidebar's actual projection")))))))
