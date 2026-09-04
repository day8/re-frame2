(ns re-frame.story.ui.explain-panel-cljs-test
  "CLJS-side regression net for the Explain panel (rf2-ba86n.9,
  spec/020 §4).

  Pairs with the host-free projection coverage in
  `re_frame/story/ui/explain_panel_test.cljc`. This namespace pins the
  reachability + render wiring that needs a CLJS runtime:

  - **command-palette reachability** — the synthetic `Explain variant`
    command appears in the palette corpus, ranks for an `explain`
    query, and `select-entry!` runs `rf.story.ui.explain-panel/open!` which flips
    the `:explain` panel-visibility slot on. This is the bead's
    'reachable from the command palette' acceptance.

  - **render-with-explain state** — for a registered variant the panel
    renders the section inventory (one `story-explain-section` node per
    spec slot) plus the raw-EDN / copy toolbar; absent slots carry
    `data-present=false`.

  - **render-no-variant state** — with no focused variant the panel
    renders the quiet 'select a variant' empty state.

  - **render-error state** — an unknown variant surfaces the structured
    compile error rather than blanking.

  Per the Story testing posture (CLJS unit tests, not Playwright) the
  panel render is exercised by calling the form-2 component's inner
  render fn directly and walking the hiccup with `re-frame.test-helpers`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core             :as rf]
            [re-frame.frame            :as rf.frame]
            [re-frame.registrar        :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story            :as rf.story]
            [re-frame.test-helpers     :as rf.test-helpers]
            [re-frame.story.ui.command-palette :as rf.story.ui.command-palette]
            [re-frame.story.ui.command-palette.view :as rf.story.ui.command-palette.view]
            [re-frame.story.ui.explain-panel :as rf.story.ui.explain-panel]
            [re-frame.story.ui.state   :as rf.story.ui.state]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch :default _ nil))
  (rf.story.ui.state/reset-shell-state!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all! :after reset-all!})

(defn- reg-counter! []
  ;; A minimal registered story + variant so the plan compiler resolves
  ;; the keyword target to a real plan. No `:component` needed — the
  ;; view-args-schema slot is simply absent (rendered 'not available').
  (rf.story/reg-story :story.explain {:doc "Explain probe"})
  (rf.story/reg-variant :story.explain/basic
                     {:args {:label "Hi"}
                      :tags #{:test}}))

;; ===========================================================================
;; command-palette reachability
;; ===========================================================================

(deftest command-entry-present-in-corpus
  (testing "the synthetic Explain command is built into the palette corpus"
    (let [entries  (rf.story.ui.command-palette/entries (rf.story.ui.state/registry-snapshot))
          commands (filterv #(= :command (:kind %)) entries)
          explain  (first (filter #(= :explain (:id %)) commands))]
      ;; rf2-ba86n.6 added the :save-current-as-variant command, so the
      ;; corpus now carries more than one synthetic command — assert the
      ;; Explain command is PRESENT rather than the sole entry.
      (is (some? explain) "the Explain command is in the corpus")
      (is (= :explain (:action explain))))))

(deftest command-entry-ranks-for-explain-query
  (testing "an `explain` query surfaces the command at the top of results"
    (reg-counter!)
    (let [entries (rf.story.ui.command-palette/entries (rf.story.ui.state/registry-snapshot))
          results (rf.story.ui.command-palette/search entries "explain")
          top     (first results)]
      (is (= :command (:kind top)))
      (is (= :explain (:id top))))))

(deftest select-command-opens-panel-visibility-slot
  (testing "selecting the Explain command flips :panel-visibility :explain on"
    (reg-counter!)
    ;; Start hidden so the open! flip is observable.
    (rf.story.ui.state/swap-state! assoc-in [:panel-visibility rf.story.ui.explain-panel/panel-key] false)
    (is (false? (get-in (rf.story.ui.state/get-state) [:panel-visibility rf.story.ui.explain-panel/panel-key])))
    (let [entry (->> (rf.story.ui.command-palette/entries (rf.story.ui.state/registry-snapshot))
                     (filter #(= :command (:kind %)))
                     first)
          handled (rf.story.ui.command-palette.view/select-entry! entry)]
      (is (true? handled) "command selection reports handled")
      (is (true? (get-in (rf.story.ui.state/get-state)
                         [:panel-visibility rf.story.ui.explain-panel/panel-key]))
          "open! turned the Explain panel-visibility slot on"))))

;; ===========================================================================
;; render states
;; ===========================================================================

(defn- render-panel
  "Invoke the form-2 component's inner render fn (the panel reads
  `rf.story.ui.state/shell-state-atom`, so seed selection first)."
  []
  (let [render-fn (rf.story.ui.explain-panel/explain-panel)]
    (render-fn)))

(deftest render-no-variant-shows-empty-state
  (testing "with no focused variant the panel renders the quiet empty state"
    (let [tree (render-panel)]
      (is (some? (rf.test-helpers/find-by-attr tree :data-test "story-explain-no-variant")))
      (is (nil? (rf.test-helpers/find-by-attr tree :data-test "story-explain-panel"))))))

(deftest render-with-variant-shows-section-inventory
  (testing "for a registered variant the panel renders every spec section
            plus the raw-EDN / copy toolbar"
    (reg-counter!)
    (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant :story.explain/basic)
    (let [tree     (render-panel)
          sections (rf.test-helpers/find-all-by-attr tree :data-test "story-explain-section")
          present  (mapv #(get (second %) :data-present) sections)]
      (is (some? (rf.test-helpers/find-by-attr tree :data-test "story-explain-panel")))
      (is (= (count (rf.story.ui.explain-panel/explain-sections {})) (count sections))
          "one section node per spec slot")
      (is (some #(= "true" %) present)
          "at least one slot is present (source-chain always is)")
      (is (some? (rf.test-helpers/find-by-attr tree :data-test "story-explain-toggle-raw"))
          "raw-EDN toggle present")
      (is (some? (rf.test-helpers/find-by-attr tree :data-test "story-explain-copy"))
          "copy-to-clipboard button present"))))

(deftest render-error-shows-structured-error
  (testing "an unknown variant target surfaces the compile error, not a blank"
    (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant :story.explain/does-not-exist)
    (let [tree (render-panel)]
      (is (some? (rf.test-helpers/find-by-attr tree :data-test "story-explain-error"))
          "compile error rendered")
      (is (nil? (rf.test-helpers/find-by-attr tree :data-test "story-explain-sections"))
          "no section inventory when the plan failed to compile"))))
