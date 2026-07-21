(ns re-frame.story.view-tool-tree-jvm-test
  "rf2-vxgfnd.95.8 — the JVM half of Story's view-tool consumer: REAL compiled
  views rendered through `re-frame.ui.test/render` (Tier-1 structural render),
  the `:sub-overrides` OBSERVATION door (the JVM host-specific mechanism), and
  the tool tier read back off the SAME emitted manifest.

  Proves the honest-non-ownership story end to end on the JVM:

    - `ui.test/render` produces a versioned STRUCTURAL TREE whose node vocabulary
      Story summarises (`view-tool/tree-vocabulary`) — view id + JVM tree
      visibility, no DOM, no live root;
    - a `:sub-overrides` pin flows THROUGH the render into the tree (Story
      observes a pinned value) while `view-evidence` labels that override
      `:owned? false` — Story never owns the reactive handle core lowered to a
      static `:story-override` target;
    - `view-evidence` reads the REAL emitted manifest (`:compiled-view? true`),
      and tolerates an uncompiled view (`:compiled-view? false`).

  JVM-only (`.clj`): `ui.test/render` is a compile-time macro that errors in a
  CLJS build (the client emitter targets React directly), so the structural
  tree is a JVM Tier-1 surface. The host-neutral consumer logic is graft-checked
  on both hosts by the sibling `view-tool-cljs-test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview sub]]
            [re-frame.ui.test :as ui.test]
            [re-frame.story.view-tool :as view-tool]))

;; Real compiled views. Each registers its manifest into the process-global
;; `:view` registry at namespace load (the JVM emitter, host-shared with CLJS).

(defview greeting-card
  "A view that reads a subscribed value — the Tier-1 headless read path (03 §3)."
  []
  [:div.card
   [:h1.title (sub [:greeting/text])]
   [:button {:on-click [:greeting/reset]} "reset"]])

(defn- view-id-of [v] (:rf.ui/view-id (meta v)))
(defn- manifest-of [id] (:rf.ui/manifest (registrar/lookup :view id)))

(def ^:private greeting-id (view-id-of #'greeting-card))

;; The reset-runtime fixture clears the registrar between tests; capture the
;; REAL emitted manifest at load and re-register it per test (the same guard the
;; tool tier's own JVM companion uses) so a sibling's clearing fixture cannot
;; strand it.
(def ^:private captured-manifest (manifest-of greeting-id))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (registrar/register! :view greeting-id
                         {:rf/id greeting-id :rf.ui/compiled? true
                          :rf.ui/manifest captured-manifest})
    (f)))

;; ===========================================================================
;; JVM structural tree — view id + tree vocabulary
;; ===========================================================================

(deftest tree-vocabulary-summarises-a-real-rendered-tree
  (rf/reg-sub :greeting/text (fn [db _] (:greeting db)))
  (rf/with-new-frame [f (rf/make-frame {:initial-events [[:rf/set-db {:greeting "hello"}]]})]
    (let [tree (ui.test/render [greeting-card])
          voc  (view-tool/tree-vocabulary tree)]
      (is (some? (:rf.ui/tree-version voc)) "the versioned tree ABI is surfaced")
      (is (contains? (:view-ids voc) greeting-id)
          "the rendered view's boundary id is visible in the tree")
      (is (pos? (:element (:kinds voc))) "the element nodes (div/h1/button) are counted"))))

;; ===========================================================================
;; The `:sub-overrides` observation door — a pin flows through, non-owning
;; ===========================================================================

(deftest sub-overrides-observation-door-is-honest-non-owning
  (rf/reg-sub :greeting/text (fn [db _] (:greeting db)))
  (rf/with-new-frame [f (rf/make-frame {:initial-events [[:rf/set-db {:greeting "real"}]]})]
    (let [tree (ui.test/render [greeting-card]
                               {:sub-overrides {[:greeting/text] "pinned"}})]
      (testing "the pinned value is OBSERVED in the tree (the JVM override door)"
        (is (= "pinned" (ui.test/text (some #(when (= :h1 (:tag %)) %)
                                            (tree-seq map? :children tree))))
            "Story observes the pinned value through ui.test/render :sub-overrides"))
      (testing "Story surfaces that override as NON-OWNING (:owned? false)"
        (let [ev (view-tool/view-evidence greeting-id {[:greeting/text] "pinned"})]
          (is (false? (:owned? (:observation ev)))
              "Story observes; core owns the static :story-override handle")
          (is (= [:greeting/text] (:query (first (:overrides (:observation ev))))))
          (is (= "pinned" (:value (first (:overrides (:observation ev)))))))))))

;; ===========================================================================
;; view-evidence reads the REAL emitted manifest + tolerates absence
;; ===========================================================================

(deftest view-evidence-reads-the-real-emitted-manifest
  (let [ev (view-tool/view-evidence greeting-id)]
    (is (true? (:compiled-view? ev)) "the REAL emitted manifest is recognised")
    (is (= greeting-id (:view-id (:manifest ev))))
    (is (= view-tool/consumed-schema-version (:rf.ui.tool/version ev)))
    (testing "the declared event site rides through the framework projection"
      (is (= '[:greeting/reset]
             (:handler (first (:handlers (:event-sites ev)))))
          "the literal on-click event vector is inspectable"))))

(deftest view-evidence-tolerates-an-uncompiled-view-on-the-jvm
  (let [ev (view-tool/view-evidence :story.plain/not-a-view)]
    (is (false? (:compiled-view? ev)))
    (is (nil? (:manifest ev)))
    (is (true? (:tier-available? ev)) "the tier stays live — absence is the view")
    (is (= [] (:mounted ev)))))
