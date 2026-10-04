(ns re-frame.story-panels-cljs-test
  "CLJS smoke tests for the v1.0 story-panel set.

  Covers the panel-registration contract documented in `003-Render-Shell.md` §Panel registration contract:
  the v1 panels (a11y, layout-debug controls) register with `:placement`
  slots and the late-bind `:render` view resolves on the framework
  registry."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.ui.panels :as rf.story.ui.panels]))

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ---- the v1.0 panels --------------------------------------------------

(deftest layout-debug-panel-body
  (testing "layout-debug panel registers as :right placement"
    (let [body (rf.story/handler-meta :story-panel rf.story.ui.panels/layout-debug-panel-id)]
      (is (= :right (:placement body)))
      (is (= rf.story.ui.panels/layout-debug-render-id (:render body))))))

;; ---- placement-host enumeration ----------------------------------------

(deftest render-panels-at-placement-returns-hiccup
  (testing "render-panels-at-placement returns a hiccup vector"
    (let [out (rf.story.ui.panels/render-panels-at-placement
                :right :story.x/y {})]
      (is (vector? out))
      (is (= :div (first out))))))

(defn- rendered-panel-text [tree]
  (pr-str tree))

(deftest render-panels-honours-panel-visibility
  (testing "a panel renders when its visibility flag is absent or true, and
            is filtered out when the flag is false"
    (rf.story/reg-story-panel :Panel.vis/notes
      {:title "Notes"
       :placement :right
       :render :Panel.vis/missing-view})
    (let [render (fn [visibility]
                   (rendered-panel-text
                     (rf.story.ui.panels/render-panels-at-placement
                       :right :story.vis/v visibility)))]
      (is (re-find #":Panel\.vis/notes" (render {}))
          "absent flag: visible by default")
      (is (re-find #":Panel\.vis/notes" (render {:Panel.vis/notes true})))
      (is (not (re-find #":Panel\.vis/notes" (render {:Panel.vis/notes false})))
          "a false flag filters the panel out"))))

(deftest render-panels-respects-for-parent-story-scope
  (testing "a panel :for parent story appears for child variants only"
    (rf.story/reg-story-panel :Panel.scope/notes
      {:title "Scoped"
       :placement :right
       :render :Panel.scope/missing-view
       :for #{:story.scope}})
    (let [scoped (rendered-panel-text
                   (rf.story.ui.panels/render-panels-at-placement
                     :right :story.scope/child {}))
          other  (rendered-panel-text
                   (rf.story.ui.panels/render-panels-at-placement
                     :right :story.other/child {}))]
      (is (re-find #":Panel\.scope/notes" scoped)
          "parent-story :for scope must include child variants")
      (is (not (re-find #":Panel\.scope/notes" other))
          "parent-story :for scope must not leak into unrelated frames"))))

(deftest render-panels-respects-for-exact-variant-scope
  (testing "a panel :for exact variant appears only for that variant frame"
    (rf.story/reg-story-panel :Panel.scope/exact
      {:title "Exact"
       :placement :right
       :render :Panel.scope/missing-view
       :for #{:story.scope/exact}})
    (let [exact (rendered-panel-text
                  (rf.story.ui.panels/render-panels-at-placement
                    :right :story.scope/exact {}))
          sibling (rendered-panel-text
                    (rf.story.ui.panels/render-panels-at-placement
                      :right :story.scope/sibling {}))]
      (is (re-find #":Panel\.scope/exact" exact)
          "exact variant :for scope must include that frame")
      (is (not (re-find #":Panel\.scope/exact" sibling))
          "exact variant :for scope must not leak to siblings"))))
