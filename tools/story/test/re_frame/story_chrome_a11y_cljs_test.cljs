(ns re-frame.story-chrome-a11y-cljs-test
  "CLJS tests for the chrome-a11y panel's registration and scope contract,
  shaped like `re-frame.story-a11y-cljs-test`; the axe-core run itself is a
  browser concern."
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

(deftest chrome-a11y-panel-body
  (let [body (rf.story/handler-meta :story-panel rf.story.ui.chrome-a11y/panel-id)]
    (is (= [:right rf.story.ui.chrome-a11y/panel-render-id] ((juxt :placement :render) body)))
    (is (re-find #"(?i)chrome" (or (:title body) "")))))

(deftest chrome-a11y-render-view-roots-in-dom-element
  (testing "the panel-render view's hiccup roots in a DOM element: the
            source-coord annotator attaches `data-rf2-source-coord` only to
            DOM roots (Spec 006 §Source-coord annotation)"
    (let [out ((rf/view rf.story.ui.chrome-a11y/panel-render-id) :story.unknown/y)]
      (is (and (vector? out) (= :div (first out)))))))

;; ---- scope contract -----------------------------------------------------

(deftest chrome-root-selector-targets-chrome-attribute
  (testing "chrome-root-selector is the attribute selector on the
            :data-rf-story-root flag shell.cljs stamps on the chrome root"
    (is (= "[data-rf-story-root]" rf.story.ui.chrome-a11y/chrome-root-selector))))

;; ---- find-chrome-root degraded-environment safety -----------------------

(deftest find-chrome-root-handles-missing-dom
  (testing "with no shell mounted find-chrome-root is nil rather than a throw,
            and the panel surfaces :no-root"
    (is (nil? (rf.story.ui.chrome-a11y/find-chrome-root)))))
