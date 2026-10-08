(ns re-frame.story-panels-cljs-test
  "CLJS tests for the story-panel registration contract
  (`003-Render-Shell.md` §Panel registration contract): the shipped panels
  register with a `:placement` slot and a late-bind `:render` view, and the
  placement host honours visibility flags and `:for` scope."
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
  (let [body (rf.story/handler-meta :story-panel rf.story.ui.panels/layout-debug-panel-id)]
    (is (= [:right rf.story.ui.panels/layout-debug-render-id] ((juxt :placement :render) body)))))

;; ---- placement-host enumeration ----------------------------------------

(deftest render-panels-at-placement-returns-hiccup
  (let [out (rf.story.ui.panels/render-panels-at-placement :right :story.x/y {})]
    (is (and (vector? out) (= :div (first out))))))

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
  (testing "a panel :for a parent story appears for its child variants and a
            panel :for an exact variant only for that variant, neither leaking
            into other frames"
    (rf.story/reg-story-panel :Panel.scope/notes
      {:title "Scoped" :placement :right :render :Panel.scope/missing-view :for #{:story.scope}})
    (rf.story/reg-story-panel :Panel.scope/exact
      {:title "Exact" :placement :right :render :Panel.scope/missing-view :for #{:story.scope/exact}})
    (let [shown (fn [frame]
                  (let [text (rendered-panel-text
                               (rf.story.ui.panels/render-panels-at-placement :right frame {}))]
                    (mapv #(boolean (re-find % text)) [#":Panel\.scope/notes" #":Panel\.scope/exact"])))]
      (is (= [true true] (shown :story.scope/exact)))
      (is (= [true false] (shown :story.scope/sibling)))
      (is (= [false false] (shown :story.other/child))))))
