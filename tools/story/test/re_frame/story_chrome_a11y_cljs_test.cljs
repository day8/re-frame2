(ns re-frame.story-chrome-a11y-cljs-test
  "CLJS smoke tests for rf2-18t6p — the chrome-a11y panel.

  Mirrors the shape of `story-a11y-cljs-test` (the variant a11y panel
  test) — registration + state-management surface that's load-bearing
  in the CLJS bundle. The actual axe-core run is a browser concern
  (script injection from a CDN); these tests cover the panel's
  contract without requiring a live browser."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.ui.a11y :as rf.story.ui.a11y]
            [re-frame.story.ui.chrome-a11y :as rf.story.ui.chrome-a11y]))

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
  (rf.story.ui.a11y/reset-state!)
  (rf.story.ui.chrome-a11y/reset-state!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ---- panel registration -------------------------------------------------

(deftest chrome-a11y-panel-registers
  (testing "the chrome-a11y panel registers as a story-panel"
    (let [panels (rf.story/registrations :story-panel)]
      (is (contains? panels rf.story.ui.chrome-a11y/panel-id)))))

(deftest chrome-a11y-panel-id-distinct-from-variant
  (testing "chrome-a11y panel-id is distinct from the variant a11y panel-id"
    (is (not= rf.story.ui.chrome-a11y/panel-id rf.story.ui.a11y/panel-id))))

(deftest chrome-a11y-panel-body
  (testing "the chrome-a11y panel body declares :placement :right + :render"
    (let [body (rf.story/handler-meta :story-panel rf.story.ui.chrome-a11y/panel-id)]
      (is (= :right (:placement body)))
      (is (= rf.story.ui.chrome-a11y/panel-render-id (:render body)))
      (is (string? (:title body)))
      (is (re-find #"(?i)chrome" (or (:title body) ""))))))

(deftest chrome-a11y-render-view-registered
  (testing "the chrome-a11y panel-render view is registered against re-frame"
    (is (some? (rf/view rf.story.ui.chrome-a11y/panel-render-id)))))

(deftest chrome-a11y-render-view-roots-in-dom-element
  (testing "the chrome-a11y panel-render view returns hiccup whose root
            is a DOM element keyword (`:div`), not a bare component
            reference.

            Per Spec 006 §Source-coord annotation the annotator can only
            attach `data-rf2-source-coord` to hiccup DOM roots; a bare
            `[panel variant-id]` root makes the panel invisible to Story
            Inspect Mode + Xray Inspect Mode (rf2-iwny7). The `[:div]`
            wrap is load-bearing."
    (let [view-fn (rf/view rf.story.ui.chrome-a11y/panel-render-id)
          out     (view-fn :story.unknown/y)]
      (is (vector? out)
          "panel-render returns a hiccup vector")
      (is (keyword? (first out))
          "hiccup root must be a keyword (DOM element), not a component ref")
      (is (= :div (first out))
          "hiccup root is specifically `:div` per the source-coord-annotator wrap"))))

;; ---- scope contract -----------------------------------------------------

(deftest chrome-root-selector-targets-chrome-attribute
  (testing "chrome-root-selector targets [data-rf-story-root]"
    (is (string? rf.story.ui.chrome-a11y/chrome-root-selector))
    ;; The chrome root is stamped by shell.cljs as :data-rf-story-root.
    (is (re-find #"data-rf-story-root" rf.story.ui.chrome-a11y/chrome-root-selector))
    (is (.startsWith rf.story.ui.chrome-a11y/chrome-root-selector "["))
    (is (.endsWith   rf.story.ui.chrome-a11y/chrome-root-selector "]"))))

(deftest chrome-frame-id-is-namespaced
  (testing "chrome-frame-id is a story-namespaced keyword distinct from any variant id"
    (is (keyword? rf.story.ui.chrome-a11y/chrome-frame-id))
    (is (= "rf.story.chrome-a11y" (namespace rf.story.ui.chrome-a11y/chrome-frame-id)))))

;; ---- state management ---------------------------------------------------

(deftest violations-state-starts-empty
  (testing "violations starts as an empty vector"
    (is (vector? @rf.story.ui.chrome-a11y/violations))
    (is (empty? @rf.story.ui.chrome-a11y/violations))))

(deftest run-state-starts-idle
  (testing "run-state starts at :idle"
    (is (= :idle (rf.story.ui.chrome-a11y/status)))))

(deftest reset-state-clears-everything
  (testing "reset-state! clears violations + resets run-state"
    (reset! rf.story.ui.chrome-a11y/violations [{:dummy true}])
    (reset! rf.story.ui.chrome-a11y/run-state {:status :done})
    (rf.story.ui.chrome-a11y/reset-state!)
    (is (empty? @rf.story.ui.chrome-a11y/violations))
    (is (= :idle (rf.story.ui.chrome-a11y/status)))))

;; ---- find-chrome-root degraded-environment safety -----------------------

(deftest find-chrome-root-handles-missing-dom
  (testing "find-chrome-root returns nil rather than throwing when no shell is mounted"
    ;; The Node-runtime test environment does not mount the Story shell,
    ;; so find-chrome-root must gracefully return nil (the panel surfaces
    ;; a :no-root state in that case).
    (is (nil? (rf.story.ui.chrome-a11y/find-chrome-root)))))
