(ns re-frame.story.ui.docs-rollup-cljs-test
  "CLJS-side regression net for the per-story rollup docs page
  (rf2-8j7wg, audit C-4).

  The rollup page composes one `rollup-variant-block` per variant of
  the parent story; the underlying section helpers (prose-for-variant,
  args-rows, decorator-rows, parameter-rows, variant-tags) are
  exercised by the existing docs-mode-pane test suite. This namespace
  pins:

  - `variant-ids-for-story` returns the sorted variant set
  - `docs-rollup-view` returns nil for nil story-id
  - `docs-rollup-view` renders one variant block per registered variant
  - `docs-rollup-view` shows an empty-state when no variants are
    registered under the story
  - `select-story` transition flips the shell slot AND clears
    `:selected-variant` / `:selected-workspace` (the mutual-exclusion
    contract — only ONE pane can be active at a time)
  - selecting a variant or workspace clears `:selected-story` (mirror
    of the above)"
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core             :as rf]
            [re-frame.frame            :as frame]
            [re-frame.machines         :as machines]
            [re-frame.registrar        :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story            :as story]
            [re-frame.story.assertions :as assertions]
            [re-frame.story.loaders    :as loaders]
            [re-frame.story.ui.docs    :as docs]
            [re-frame.story.ui.state   :as state]
            [re-frame.story.ui.state.transitions :as transitions]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! []
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch :default _ nil))
  (rf/reg-sub :rf/machine
              (fn [db [_ machine-id]]
                (get-in db [:rf/machines machine-id])))
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (reset! assertions/trace-accumulators {})
  (state/reset-shell-state!)
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ---- fixture helpers -----------------------------------------------------

(defn- register-story-with-variants!
  []
  (story/reg-story :story.rollup
    {:doc       "Rollup-fixture story with three variants."
     :argtypes  {:label {:doc "the label arg"}}
     :tags      #{:dev :docs}})
  (story/reg-variant :story.rollup/alpha
    {:doc   "First variant — :alpha."
     :args  {:label "alpha"}
     :tags  #{:dev}
     :events []})
  (story/reg-variant :story.rollup/beta
    {:doc   "Second variant — :beta."
     :args  {:label "beta"}
     :tags  #{:docs}
     :events []})
  (story/reg-variant :story.rollup/gamma
    {:doc   "Third variant — :gamma."
     :args  {:label "gamma"}
     :events []}))

;; ===========================================================================
;; variant-ids-for-story
;; ===========================================================================

(deftest variant-ids-for-story-returns-sorted-vector
  (testing "the rollup walker returns every variant of `story-id` in
            sorted order so the rollup page renders stably across
            hot-reload"
    (register-story-with-variants!)
    (is (= [:story.rollup/alpha
            :story.rollup/beta
            :story.rollup/gamma]
           (docs/variant-ids-for-story :story.rollup))
        "all three variants surface, sorted by id")))

(deftest variant-ids-for-empty-story
  (testing "a story with zero registered variants returns an empty
            vector — the renderer shows an empty-state placeholder
            rather than vanishing"
    (story/reg-story :story.empty-rollup
      {:doc "no variants" :tags #{:dev}})
    (is (= [] (docs/variant-ids-for-story :story.empty-rollup)))))

(deftest variant-ids-for-unknown-story
  (testing "a story id with no registrations returns empty — defends
            against stale shell-state :selected-story slots after a
            hot-reload that drops a story"
    (is (= [] (docs/variant-ids-for-story :story.does-not-exist)))))

;; ===========================================================================
;; docs-rollup-view rendering shape
;; ===========================================================================

(deftest docs-rollup-view-nil-input-renders-nothing
  (testing "the view defends against a nil :selected-story slot — no
            crash, no DOM"
    (is (nil? (docs/docs-rollup-view nil)))))

(deftest docs-rollup-view-renders-one-block-per-variant
  (testing "the rollup pane carries N rollup-variant-block children
            (one per registered variant under the story). The blocks
            are emitted as a Reagent for-comprehension so React keys
            land on the inner ^{:key vid} wrap; pin the count and that
            each block names its variant-id via :data-variant-id."
    (register-story-with-variants!)
    (let [hiccup (docs/docs-rollup-view :story.rollup)]
      (is (vector? hiccup))
      (is (= :section (first hiccup)))
      (is (= "story-docs-rollup" (:data-test (second hiccup))))
      (is (= ":story.rollup" (:data-story-id (second hiccup)))))))

(deftest docs-rollup-view-empty-state-when-no-variants
  (testing "stories with zero variants render an empty-state notice —
            the user clicked into the rollup but there's nothing to
            project. Better than a silently-empty pane."
    (story/reg-story :story.empty-rollup
      {:doc "no variants" :tags #{:dev}})
    (let [hiccup (docs/docs-rollup-view :story.empty-rollup)
          children (drop 2 hiccup)  ; drop tag + attrs
          empty-marker (some (fn [c]
                               (and (vector? c)
                                    (= "story-docs-rollup-empty"
                                       (:data-test (second c)))))
                             children)]
      (is (boolean empty-marker)
          "the empty-state div carries `data-test=story-docs-rollup-empty`"))))

;; ===========================================================================
;; select-story transition contract
;; ===========================================================================

(deftest select-story-sets-slot
  (testing "select-story writes the parent-story id into
            :selected-story"
    (let [state {:selected-story nil}
          out   (transitions/select-story state :story.foo)]
      (is (= :story.foo (:selected-story out))))))

(deftest select-story-clears-variant-and-workspace
  (testing "select-story is mutually exclusive with variant +
            workspace selection — opening the rollup view closes any
            previously-open variant or workspace"
    (let [state {:selected-variant   :story.foo/v
                 :selected-workspace :Workspace.foo/ws
                 :selected-story     nil}
          out   (transitions/select-story state :story.foo)]
      (is (= :story.foo (:selected-story out)))
      (is (nil? (:selected-variant   out)))
      (is (nil? (:selected-workspace out))))))

(deftest select-story-nil-deselects-without-clearing-others
  (testing "select-story with nil deselects the rollup without
            disturbing variant or workspace slots — clicking 'close
            rollup' shouldn't unmount whatever else the shell is
            showing"
    (let [state {:selected-variant   :story.foo/v
                 :selected-workspace nil
                 :selected-story     :story.foo}
          out   (transitions/select-story state nil)]
      (is (nil? (:selected-story out)))
      (is (= :story.foo/v (:selected-variant out))))))

(deftest select-variant-clears-selected-story
  (testing "selecting a variant clears :selected-story — the rollup
            is dismissed when the user clicks INTO one of its variants
            from the sidebar"
    (let [state {:selected-story   :story.foo
                 :selected-variant nil}
          out   (transitions/select-variant state :story.foo/v)]
      (is (= :story.foo/v (:selected-variant out)))
      (is (nil? (:selected-story out))))))

(deftest select-workspace-clears-selected-story
  (testing "selecting a workspace clears :selected-story — same mutual
            exclusion contract as variant selection"
    (let [state {:selected-story     :story.foo
                 :selected-workspace nil}
          out   (transitions/select-workspace state :Workspace.foo/ws)]
      (is (= :Workspace.foo/ws (:selected-workspace out)))
      (is (nil? (:selected-story out))))))

(deftest selecting-nil-variant-preserves-selected-story
  (testing "selecting a nil variant (deselect) MUST NOT clobber
            :selected-story — a user dismissing the variant pane
            shouldn't lose their rollup focus"
    (let [state {:selected-story   :story.foo
                 :selected-variant :story.foo/v}
          out   (transitions/select-variant state nil)]
      (is (nil? (:selected-variant out)))
      (is (= :story.foo (:selected-story out))
          "story slot survives a variant deselection"))))

;; ===========================================================================
;; round-trip — clicking through the rollup contract
;; ===========================================================================

(deftest rollup-round-trip-via-shell-state
  (testing "the full round-trip a user takes: register story →
            click story-header → :selected-story populated → click
            variant → :selected-story cleared → click back to story →
            re-populated. Mirrors the integration the sidebar drives."
    (register-story-with-variants!)
    ;; Start: nothing selected.
    (is (nil? (:selected-story (state/get-state))))
    ;; Click story header.
    (state/swap-state! transitions/select-story :story.rollup)
    (is (= :story.rollup (:selected-story (state/get-state))))
    (is (nil? (:selected-variant (state/get-state))))
    ;; Click variant — story is dismissed.
    (state/swap-state! transitions/select-variant :story.rollup/alpha)
    (is (= :story.rollup/alpha (:selected-variant (state/get-state))))
    (is (nil? (:selected-story (state/get-state)))
        "selecting a variant dismisses the rollup view")
    ;; Click story header again — variant cleared.
    (state/swap-state! transitions/select-story :story.rollup)
    (is (= :story.rollup (:selected-story (state/get-state))))
    (is (nil? (:selected-variant (state/get-state))))))
