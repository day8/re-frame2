(ns re-frame.story.ui.docs-rollup-cljs-test
  "CLJS coverage of the per-story rollup docs page (spec/008): the sorted
  variant set, the root and empty-state selectors, and the selection
  transitions that make the rollup mutually exclusive with variant and
  workspace focus."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core             :as rf]
            [re-frame.frame            :as rf.frame]
            [re-frame.machines         :as rf.machines]
            [re-frame.registrar        :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story            :as rf.story]
            [re-frame.story.loaders    :as rf.story.loaders]
            [re-frame.story.ui.docs    :as rf.story.ui.docs]
            [re-frame.story.ui.state   :as rf.story.ui.state]
            [re-frame.story.ui.state.transitions :as rf.story.ui.state.transitions]
            [re-frame.subs             :as rf.subs]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch :default _ nil))
  ;; Re-register the framework `:rf/machine` sub after the registrar clear.
  ;; EP-0001: a runtime-db sub reading
  ;; [:rf.runtime/machines :snapshots <id>] — mirror `re-frame.machines`.
  (rf.subs/reg-runtime-sub :rf/machine
    (fn [runtime-db [_ machine-id]]
      (get-in runtime-db [:rf.runtime/machines :snapshots machine-id])))
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story.ui.state/reset-shell-state!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

(defn- register-story-with-variants! []
  (rf.story/reg-story :story.rollup {:doc "Rollup-fixture story with three variants."})
  (rf.story/reg-variant :story.rollup/alpha {:setup []})
  (rf.story/reg-variant :story.rollup/beta  {:setup []})
  (rf.story/reg-variant :story.rollup/gamma {:setup []}))

;; ===========================================================================
;; variant-ids-for-story + docs-rollup-view
;; ===========================================================================

(deftest variant-ids-for-story-returns-sorted-vector
  (testing "every variant of the story, sorted so the page renders stably across hot-reload"
    (register-story-with-variants!)
    (is (= [:story.rollup/alpha :story.rollup/beta :story.rollup/gamma]
           (rf.story.ui.docs/variant-ids-for-story :story.rollup)))))

(deftest docs-rollup-view-roots-in-a-section-naming-its-story
  (testing "the root carries the spec/008 selectors: data-test and :data-story-id"
    (register-story-with-variants!)
    (let [hiccup (rf.story.ui.docs/docs-rollup-view :story.rollup)]
      (is (= :section (first hiccup)))
      (is (= "story-docs-rollup" (:data-test (second hiccup))))
      (is (= ":story.rollup" (:data-story-id (second hiccup)))))))

(deftest docs-rollup-view-empty-state-when-no-variants
  (testing "a story with zero variants renders the empty-state notice rather than an empty pane"
    (rf.story/reg-story :story.empty-rollup {:doc "no variants" :tags #{:dev}})
    (is (some #(= "story-docs-rollup-empty" (:data-test (second %)))
              (drop 2 (rf.story.ui.docs/docs-rollup-view :story.empty-rollup))))))

;; ===========================================================================
;; selection transitions — only ONE pane is active at a time
;; ===========================================================================

(deftest select-story-clears-variant-and-workspace-unless-nil
  (testing "opening the rollup closes any open variant or workspace"
    (is (= {:selected-story :story.foo :selected-variant nil :selected-workspace nil}
           (rf.story.ui.state.transitions/select-story
             {:selected-variant :story.foo/v :selected-workspace :Workspace.foo/ws}
             :story.foo))))
  (testing "deselecting the rollup (nil) leaves the other slots alone"
    (is (= {:selected-story nil :selected-variant :story.foo/v}
           (rf.story.ui.state.transitions/select-story
             {:selected-story :story.foo :selected-variant :story.foo/v}
             nil)))))

(deftest selecting-a-variant-or-workspace-dismisses-the-rollup
  (let [state {:selected-story :story.foo}]
    (is (= {:selected-story nil :selected-workspace :Workspace.foo/ws}
           (rf.story.ui.state.transitions/select-workspace state :Workspace.foo/ws)))
    (is (= {:selected-story nil :selected-variant :story.foo/v}
           (rf.story.ui.state.transitions/select-variant state :story.foo/v)))
    (is (= {:selected-story :story.foo :selected-variant nil}
           (rf.story.ui.state.transitions/select-variant state nil))
        "deselecting a variant (nil) keeps the rollup focus")))
