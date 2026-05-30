(ns re-frame.story.ui.explain-panel-cljs-test
  "CLJS-side regression net for the Explain panel (rf2-ba86n.9,
  spec/020 §4).

  Pairs with the host-free projection coverage in
  `re_frame/story/ui/explain_panel_test.cljc`. This namespace pins the
  reachability + render wiring that needs a CLJS runtime:

  - **command-palette reachability** — the synthetic `Explain variant`
    command appears in the palette corpus, ranks for an `explain`
    query, and `select-entry!` runs `explain-panel/open!` which flips
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
            [re-frame.frame            :as frame]
            [re-frame.registrar        :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story            :as story]
            [re-frame.test-helpers     :as th]
            [re-frame.story.ui.command-palette :as palette]
            [re-frame.story.ui.command-palette.view :as palette-view]
            [re-frame.story.ui.explain-panel :as explain-panel]
            [re-frame.story.ui.state   :as state]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! []
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch :default _ nil))
  (state/reset-shell-state!)
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all! :after reset-all!})

(defn- reg-counter! []
  ;; A minimal registered story + variant so the plan compiler resolves
  ;; the keyword target to a real plan. No `:component` needed — the
  ;; view-args-schema slot is simply absent (rendered 'not available').
  (story/reg-story :story.explain {:doc "Explain probe"})
  (story/reg-variant :story.explain/basic
                     {:args {:label "Hi"}
                      :tags #{:test}}))

;; ===========================================================================
;; command-palette reachability
;; ===========================================================================

(deftest command-entry-present-in-corpus
  (testing "the synthetic Explain command is built into the palette corpus"
    (let [entries  (palette/entries (state/registry-snapshot))
          commands (filterv #(= :command (:kind %)) entries)]
      (is (= 1 (count commands)) "exactly one synthetic command")
      (is (= :explain (:id (first commands))))
      (is (= :explain (:action (first commands)))))))

(deftest command-entry-ranks-for-explain-query
  (testing "an `explain` query surfaces the command at the top of results"
    (reg-counter!)
    (let [entries (palette/entries (state/registry-snapshot))
          results (palette/search entries "explain")
          top     (first results)]
      (is (= :command (:kind top)))
      (is (= :explain (:id top))))))

(deftest select-command-opens-panel-visibility-slot
  (testing "selecting the Explain command flips :panel-visibility :explain on"
    (reg-counter!)
    ;; Start hidden so the open! flip is observable.
    (state/swap-state! assoc-in [:panel-visibility explain-panel/panel-key] false)
    (is (false? (get-in (state/get-state) [:panel-visibility explain-panel/panel-key])))
    (let [entry (->> (palette/entries (state/registry-snapshot))
                     (filter #(= :command (:kind %)))
                     first)
          handled (palette-view/select-entry! entry)]
      (is (true? handled) "command selection reports handled")
      (is (true? (get-in (state/get-state)
                         [:panel-visibility explain-panel/panel-key]))
          "open! turned the Explain panel-visibility slot on"))))

;; ===========================================================================
;; render states
;; ===========================================================================

(defn- render-panel
  "Invoke the form-2 component's inner render fn (the panel reads
  `state/shell-state-atom`, so seed selection first)."
  []
  (let [render-fn (explain-panel/explain-panel)]
    (render-fn)))

(deftest render-no-variant-shows-empty-state
  (testing "with no focused variant the panel renders the quiet empty state"
    (let [tree (render-panel)]
      (is (some? (th/find-by-attr tree :data-test "story-explain-no-variant")))
      (is (nil? (th/find-by-attr tree :data-test "story-explain-panel"))))))

(deftest render-with-variant-shows-section-inventory
  (testing "for a registered variant the panel renders every spec section
            plus the raw-EDN / copy toolbar"
    (reg-counter!)
    (state/swap-state! state/select-variant :story.explain/basic)
    (let [tree     (render-panel)
          sections (th/find-all-by-attr tree :data-test "story-explain-section")
          present  (mapv #(get (second %) :data-present) sections)]
      (is (some? (th/find-by-attr tree :data-test "story-explain-panel")))
      (is (= (count (explain-panel/explain-sections {})) (count sections))
          "one section node per spec slot")
      (is (some #(= "true" %) present)
          "at least one slot is present (source-chain always is)")
      (is (some? (th/find-by-attr tree :data-test "story-explain-toggle-raw"))
          "raw-EDN toggle present")
      (is (some? (th/find-by-attr tree :data-test "story-explain-copy"))
          "copy-to-clipboard button present"))))

(deftest render-error-shows-structured-error
  (testing "an unknown variant target surfaces the compile error, not a blank"
    (state/swap-state! state/select-variant :story.explain/does-not-exist)
    (let [tree (render-panel)]
      (is (some? (th/find-by-attr tree :data-test "story-explain-error"))
          "compile error rendered")
      (is (nil? (th/find-by-attr tree :data-test "story-explain-sections"))
          "no section inventory when the plan failed to compile"))))
