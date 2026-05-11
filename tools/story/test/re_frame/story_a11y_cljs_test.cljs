(ns re-frame.story-a11y-cljs-test
  "CLJS smoke tests for Stage 6 (rf2-zhwd) — a11y panel.

  The actual axe-core integration is a browser concern (it injects a
  `<script>` tag from a CDN); these smoke tests cover the panel's
  registration + state-management surface that's load-bearing in the
  CLJS bundle without requiring a live browser."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story :as story]
            [re-frame.story.ui.a11y :as a11y]))

(defn reset-all! []
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter) (catch :default _ nil))
  (a11y/reset-state!)
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ---- panel registration -------------------------------------------------

(deftest a11y-panel-registers
  (testing "the a11y panel registers as a story-panel"
    (let [panels (story/handlers :story-panel)]
      (is (contains? panels a11y/panel-id)))))

(deftest a11y-panel-body
  (testing "the a11y panel body declares :placement :right + :render"
    (let [body (story/handler-meta :story-panel a11y/panel-id)]
      (is (= :right (:placement body)))
      (is (= a11y/panel-render-id (:render body)))
      (is (string? (:title body))))))

(deftest a11y-render-view-registered
  (testing "the a11y panel-render view is registered against re-frame"
    (is (some? (rf/view a11y/panel-render-id)))))

;; ---- state management ---------------------------------------------------

(deftest violations-state-empty
  (testing "violations-by-frame starts empty"
    (is (= {} @a11y/violations-by-frame))))

(deftest drop-frame-state-clears
  (testing "drop-frame-state! removes per-frame state"
    (swap! a11y/violations-by-frame assoc :story.x/y [{:dummy true}])
    (swap! a11y/run-state           assoc :story.x/y :done)
    (a11y/drop-frame-state! :story.x/y)
    (is (not (contains? @a11y/violations-by-frame :story.x/y)))
    (is (not (contains? @a11y/run-state           :story.x/y)))))

(deftest violations-stylesheet-non-empty
  (testing "the violations stylesheet is a non-empty CSS string"
    (is (string? a11y/violations-stylesheet))
    (is (pos? (count a11y/violations-stylesheet)))))
