(ns re-frame.story.ui.command-palette.view-cljs-test
  "CLJS-side regression net for Story's command palette panel.

  Pins the renderable states of
  `rf.story.ui.command-palette.view/render-palette` — the pure projection
  `command-palette-host` delegates to once `open?` flips true — against the
  `data-test` selectors tools/story/spec/014-Chrome-Features.md §Test
  affordances documents: the empty state, the result rows (with their
  `:data-kind` / `:data-id`), and the `:active` row's `aria-selected`.

  The pure-data helpers (`entries`, `search`, the active-index moves) are
  covered in `re_frame/story_ui_test.clj`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame.test-helpers :as rf.test-helpers]
            [re-frame.story.ui.command-palette.view :as rf.story.ui.command-palette.view]))

;; ---- fixtures ------------------------------------------------------------

(defn- mk-entry
  "A palette entry shaped like `palette/entries` output."
  [kind id-label]
  {:kind       kind
   :kind-label (str/capitalize (name kind))
   :id         (keyword id-label)
   :id-label   id-label
   :doc        ""})

(defn- noop [& _])

(defn- base-props
  "A baseline prop map for `render-palette` with handler slots stubbed."
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

(defn- result-rows [tree]
  (rf.test-helpers/find-all-by-attr tree :data-test "story-command-palette-result"))

;; ===========================================================================

(deftest empty-palette-carries-documented-selectors
  (testing "with `:results []` the scrim and input carry their selectors and
            the empty-state placeholder renders in place of any result row"
    (let [tree (rf.story.ui.command-palette.view/render-palette (base-props))]
      (is (some? (rf.test-helpers/find-by-attr tree :data-test "story-command-palette")))
      (is (some? (rf.test-helpers/find-by-attr tree :data-test "story-command-palette-input")))
      (is (some? (rf.test-helpers/find-by-attr tree :data-test "story-command-palette-empty")))
      (is (empty? (result-rows tree))))))

(deftest results-render-one-row-per-entry-with-the-active-one-selected
  (testing "a populated result list renders one row per entry, in order,
            carrying the entry's :data-id / :data-kind; only the row at
            `:active` is aria-selected; the empty placeholder is omitted"
    (let [entries [(mk-entry :variant   ":app/login")
                   (mk-entry :workspace ":app/sandbox")
                   (mk-entry :story     ":app/counters")]
          tree    (rf.story.ui.command-palette.view/render-palette
                    (assoc (base-props) :results entries :active 1))]
      (is (= [[":app/login"    "variant"   "false"]
              [":app/sandbox"  "workspace" "true"]
              [":app/counters" "story"     "false"]]
             (mapv (comp (juxt :data-id :data-kind :aria-selected) second)
                   (result-rows tree))))
      (is (nil? (rf.test-helpers/find-by-attr tree :data-test "story-command-palette-empty"))))))
