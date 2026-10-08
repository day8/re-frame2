(ns re-frame.story.ui.explain-panel-cljs-test
  "CLJS coverage of the Explain panel (spec/020 §4): command-palette
  reachability and the three render states. The panel render is exercised by
  calling the form-2 component's inner render fn and walking the hiccup with
  `re-frame.test-helpers`; the pure projection is covered by
  `explain_panel_test.cljc`."
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

(deftest select-command-opens-panel-visibility-slot
  (testing "selecting the Explain command flips :panel-visibility :explain on"
    ;; Start hidden so the open! flip is observable.
    (rf.story.ui.state/swap-state! assoc-in [:panel-visibility rf.story.ui.explain-panel/panel-key] false)
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
