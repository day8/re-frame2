(ns re-frame.story-layout-debug-cljs-test
  "CLJS tests for the layout-debug overlays' per-variant toggle panel.
  The decorators' public ids, registration, wrap shape and resolution
  are pure `.cljc` and are covered on the JVM in
  `re-frame.story-layout-debug-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.layout-debug :as rf.story.layout-debug]
            [re-frame.story.ui.panels :as rf.story.ui.panels]
            [re-frame.test-helpers :as rf.test-helpers]))

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
  (rf.story.layout-debug/reset-wrap-counter!)
  (reset! rf.story.ui.panels/layout-debug-toggles {})
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; spec/015 asks each layout-debug overlay to toggle its DOM state while the
;; variant's own state stays unchanged. The active set lives in
;; `layout-debug-toggles`, a per-process ratom keyed by variant id and
;; independent of every frame, and the panel renders one checkbox per overlay.

(defn- render-layout-debug-panel
  "Render the form-2 layout-debug panel for `variant-id` to hiccup."
  [variant-id]
  (rf.test-helpers/expand-tree [rf.story.ui.panels/layout-debug-view variant-id]))

(defn- overlay-checkboxes
  "The three overlay checkbox nodes in render order (measure / outline /
  pseudo)."
  [tree]
  (rf.test-helpers/find-all-by-attr tree :type "checkbox"))

(deftest each-overlay-toggles-independently
  (testing "toggling one overlay flips only that id; a second toggles
            independently; re-toggling clears it"
    (let [vid    :story.x/probe
          active #(rf.story.ui.panels/active-layout-debug-decorators vid)
          toggle #(do (rf.story.ui.panels/toggle-layout-debug! vid %) (active))]
      (is (= [#{}
              #{rf.story.layout-debug/id-outline}
              #{rf.story.layout-debug/id-outline rf.story.layout-debug/id-measure}
              #{rf.story.layout-debug/id-measure}]
             [(active)
              (toggle rf.story.layout-debug/id-outline)
              (toggle rf.story.layout-debug/id-measure)
              (toggle rf.story.layout-debug/id-outline)])))))

(deftest overlay-toggles-are-per-variant-isolated
  (testing "toggling variant A's overlay does not leak into variant B's set"
    (rf.story.ui.panels/toggle-layout-debug! :story.x/a rf.story.layout-debug/id-outline)
    (is (= #{rf.story.layout-debug/id-outline}
           (rf.story.ui.panels/active-layout-debug-decorators :story.x/a)))
    (is (= #{} (rf.story.ui.panels/active-layout-debug-decorators :story.x/b))
        "sibling variant's overlay set is untouched")))

(deftest overlay-checkboxes-reflect-active-set-in-dom
  (testing "one checkbox per overlay (measure, outline, pseudo), whose :checked
            mirrors the active set"
    (let [vid     :story.x/probe
          checked #(mapv (fn [box] (boolean (get (second box) :checked)))
                         (overlay-checkboxes (render-layout-debug-panel vid)))]
      (is (= [false false false] (checked)))
      (rf.story.ui.panels/toggle-layout-debug! vid rf.story.layout-debug/id-outline)
      (is (= [false true false] (checked))))))

(deftest overlay-toggles-do-not-mutate-variant-state
  (testing "toggling every overlay on and off leaves the registered variant
            body identical (spec/015 'variant state unchanged')"
    (let [vid :story.x/counted
          ids [rf.story.layout-debug/id-measure
               rf.story.layout-debug/id-outline
               rf.story.layout-debug/id-pseudo]]
      (rf.story/reg-variant* vid {:args {:n 7} :setup [[:set-thing 1]]})
      (let [body-before (rf.story/handler-meta :variant vid)]
        (doseq [id (concat ids ids)]
          (rf.story.ui.panels/toggle-layout-debug! vid id))
        (is (= [#{} body-before]
               [(rf.story.ui.panels/active-layout-debug-decorators vid)
                (rf.story/handler-meta :variant vid)]))))))
