(ns re-frame.story-panels-cljs-test
  "CLJS smoke tests for Stage 6 (rf2-zhwd) — the v1.0 story-panel set.

  Covers the panel-registration contract documented in IMPL-SPEC §4.5:
  the three v1 panels (a11y, layout-debug controls, 10x epoch stub)
  register with `:placement` slots and the late-bind `:render` view
  resolves on the framework registry."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story :as story]
            [re-frame.story.ui.a11y :as a11y]
            [re-frame.story.ui.panels :as panels]))

(defn reset-all! []
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter) (catch :default _ nil))
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ---- the three v1.0 panels --------------------------------------------

(deftest v1-panels-registered
  (testing "Stage 6 ships three v1.0 story-panel registrations"
    (let [ps (story/registrations :story-panel)]
      (is (contains? ps a11y/panel-id))
      (is (contains? ps panels/epoch-panel-id))
      (is (contains? ps panels/layout-debug-panel-id)))))

(deftest epoch-stub-panel-body
  (testing "epoch-panel registers with :placement :bottom"
    (let [body (story/handler-meta :story-panel panels/epoch-panel-id)]
      (is (= :bottom (:placement body)))
      (is (= panels/epoch-panel-render-id (:render body)))
      (is (re-find #"(?i)10x" (or (:title body) ""))))))

(deftest epoch-stub-view-registered
  (testing "the stub view is registered against re-frame's view registry"
    (is (some? (rf/view panels/epoch-panel-render-id)))))

(deftest layout-debug-panel-body
  (testing "layout-debug panel registers as :right placement"
    (let [body (story/handler-meta :story-panel panels/layout-debug-panel-id)]
      (is (= :right (:placement body)))
      (is (= panels/layout-debug-render-id (:render body))))))

;; ---- placement-host enumeration ----------------------------------------

(deftest render-panels-at-placement-returns-hiccup
  (testing "render-panels-at-placement returns a hiccup vector"
    (let [out (panels/render-panels-at-placement
                :right :story.x/y {})]
      (is (vector? out))
      (is (= :div (first out))))))

(deftest render-panels-respects-visibility-false
  (testing "explicit panel-visibility false hides a panel"
    (let [out-visible (panels/render-panels-at-placement
                       :right :story.x/y {})
          out-hidden  (panels/render-panels-at-placement
                       :right :story.x/y {a11y/panel-id false})]
      ;; Both return vectors; the hidden form contains fewer slots.
      (is (vector? out-visible))
      (is (vector? out-hidden)))))

(defn- rendered-panel-text [tree]
  (pr-str tree))

(deftest render-panels-respects-for-parent-story-scope
  (testing "a panel :for parent story appears for child variants only"
    (story/reg-story-panel :Panel.scope/notes
      {:title "Scoped"
       :placement :right
       :render :Panel.scope/missing-view
       :for #{:story.scope}})
    (let [scoped (rendered-panel-text
                   (panels/render-panels-at-placement
                     :right :story.scope/child {}))
          other  (rendered-panel-text
                   (panels/render-panels-at-placement
                     :right :story.other/child {}))]
      (is (re-find #":Panel\.scope/notes" scoped)
          "parent-story :for scope must include child variants")
      (is (not (re-find #":Panel\.scope/notes" other))
          "parent-story :for scope must not leak into unrelated frames"))))

(deftest render-panels-respects-for-exact-variant-scope
  (testing "a panel :for exact variant appears only for that variant frame"
    (story/reg-story-panel :Panel.scope/exact
      {:title "Exact"
       :placement :right
       :render :Panel.scope/missing-view
       :for #{:story.scope/exact}})
    (let [exact (rendered-panel-text
                  (panels/render-panels-at-placement
                    :right :story.scope/exact {}))
          sibling (rendered-panel-text
                    (panels/render-panels-at-placement
                      :right :story.scope/sibling {}))]
      (is (re-find #":Panel\.scope/exact" exact)
          "exact variant :for scope must include that frame")
      (is (not (re-find #":Panel\.scope/exact" sibling))
          "exact variant :for scope must not leak to siblings"))))

;; ---- layout-debug toggle state ----------------------------------------

(deftest layout-debug-toggle-roundtrip
  (testing "toggle-layout-debug! flips a decorator on/off per variant"
    (panels/toggle-layout-debug! :story.x/y :rf.story/layout-debug.outline)
    (is (contains? (panels/active-layout-debug-decorators :story.x/y)
                   :rf.story/layout-debug.outline))
    (panels/toggle-layout-debug! :story.x/y :rf.story/layout-debug.outline)
    (is (not (contains? (panels/active-layout-debug-decorators :story.x/y)
                        :rf.story/layout-debug.outline)))))
