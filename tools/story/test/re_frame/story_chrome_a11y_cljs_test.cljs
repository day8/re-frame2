(ns re-frame.story-chrome-a11y-cljs-test
  "CLJS smoke tests for rf2-18t6p — the chrome-a11y panel.

  Mirrors the shape of `story-a11y-cljs-test` (the variant a11y panel
  test) — registration + state-management surface that's load-bearing
  in the CLJS bundle. The actual axe-core run is a browser concern
  (script injection from a CDN); these tests cover the panel's
  contract without requiring a live browser."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story :as story]
            [re-frame.story.ui.a11y :as a11y]
            [re-frame.story.ui.chrome-a11y :as chrome-a11y]))

(defn reset-all! []
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter) (catch :default _ nil))
  (a11y/reset-state!)
  (chrome-a11y/reset-state!)
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ---- panel registration -------------------------------------------------

(deftest chrome-a11y-panel-registers
  (testing "the chrome-a11y panel registers as a story-panel"
    (let [panels (story/registrations :story-panel)]
      (is (contains? panels chrome-a11y/panel-id)))))

(deftest chrome-a11y-panel-id-distinct-from-variant
  (testing "chrome-a11y panel-id is distinct from the variant a11y panel-id"
    (is (not= chrome-a11y/panel-id a11y/panel-id))))

(deftest chrome-a11y-panel-body
  (testing "the chrome-a11y panel body declares :placement :right + :render"
    (let [body (story/handler-meta :story-panel chrome-a11y/panel-id)]
      (is (= :right (:placement body)))
      (is (= chrome-a11y/panel-render-id (:render body)))
      (is (string? (:title body)))
      (is (re-find #"(?i)chrome" (or (:title body) ""))))))

(deftest chrome-a11y-render-view-registered
  (testing "the chrome-a11y panel-render view is registered against re-frame"
    (is (some? (rf/view chrome-a11y/panel-render-id)))))

;; ---- scope contract -----------------------------------------------------

(deftest chrome-root-selector-targets-chrome-attribute
  (testing "chrome-root-selector targets [data-rf-story-root]"
    (is (string? chrome-a11y/chrome-root-selector))
    ;; The chrome root is stamped by shell.cljs as :data-rf-story-root.
    (is (re-find #"data-rf-story-root" chrome-a11y/chrome-root-selector))
    (is (.startsWith chrome-a11y/chrome-root-selector "["))
    (is (.endsWith   chrome-a11y/chrome-root-selector "]"))))

(deftest chrome-frame-id-is-namespaced
  (testing "chrome-frame-id is a story-namespaced keyword distinct from any variant id"
    (is (keyword? chrome-a11y/chrome-frame-id))
    (is (= "rf.story.chrome-a11y" (namespace chrome-a11y/chrome-frame-id)))))

;; ---- state management ---------------------------------------------------

(deftest violations-state-starts-empty
  (testing "violations starts as an empty vector"
    (is (vector? @chrome-a11y/violations))
    (is (empty? @chrome-a11y/violations))))

(deftest run-state-starts-idle
  (testing "run-state starts at :idle"
    (is (= :idle @chrome-a11y/run-state))))

(deftest reset-state-clears-everything
  (testing "reset-state! clears violations + resets run-state"
    (reset! chrome-a11y/violations [{:dummy true}])
    (reset! chrome-a11y/run-state :done)
    (chrome-a11y/reset-state!)
    (is (empty? @chrome-a11y/violations))
    (is (= :idle @chrome-a11y/run-state))))

;; ---- find-chrome-root degraded-environment safety -----------------------

(deftest find-chrome-root-handles-missing-dom
  (testing "find-chrome-root returns nil rather than throwing when no shell is mounted"
    ;; The Node-runtime test environment does not mount the Story shell,
    ;; so find-chrome-root must gracefully return nil (the panel surfaces
    ;; a :no-root state in that case).
    (is (nil? (chrome-a11y/find-chrome-root)))))
