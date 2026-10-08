(ns re-frame.story-a11y-cljs-test
  "CLJS tests for the a11y panel's registration and state surface; the
  axe-core scan itself runs in a browser. `re-frame.story-a11y-source-test`
  checks the loader source for SRI, crossorigin and the version pin."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.ui.a11y :as rf.story.ui.a11y]))

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
  (rf.story.ui.a11y/reset-state!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ---- panel registration -------------------------------------------------

(deftest a11y-panel-body
  (let [body (rf.story/handler-meta :story-panel rf.story.ui.a11y/panel-id)]
    (is (= [:right rf.story.ui.a11y/panel-render-id true]
           [(:placement body) (:render body) (string? (:title body))]))))

(deftest a11y-render-view-roots-in-dom-element
  (testing "the panel-render view's hiccup roots in a DOM element: the
            source-coord annotator attaches `data-rf2-source-coord` only to
            DOM roots (Spec 006 §Source-coord annotation), so a bare component
            root would hide the panel from Story and Xray Inspect Mode"
    (let [out ((rf/view rf.story.ui.a11y/panel-render-id) :story.unknown/y)]
      (is (and (vector? out) (= :div (first out)))))))

;; ---- variant-root scoping -----------------------------------------------

(deftest variant-root-selector-targets-data-attribute
  (testing "variant-root-selector returns the CSS attribute selector on the
            data attribute the canvas / workspace stamp, keyed on the
            pr-str'd variant id in single quotes so `querySelector` accepts it"
    (is (= "[data-rf-story-variant-root=':story.counter/loaded']"
           (rf.story.ui.a11y/variant-root-selector :story.counter/loaded)))))

(deftest run-axe-handles-no-variant-root
  (testing "with no variant root (find-variant-root is nil outside a browser
            DOM) run-axe! still returns a Promise and records :no-root, never
            scanning the wrong tree"
    (let [frame-id  :story.never-mounted/x
          orig-warn js/console.warn]
      (set! js/console.warn (fn [& _] nil))
      (try
        (let [p (rf.story.ui.a11y/run-axe! frame-id)]
          (is (some? p))
          (is (= :no-root (rf.story.ui.a11y/status-for frame-id))))
        (finally
          (set! js/console.warn orig-warn)
          (rf.story.ui.a11y/drop-frame-state! frame-id))))))

;; The axe-core CDN load is gated behind a persisted opt-in.
(deftest cdn-opt-in-roundtrips
  (testing "set-cdn-opt-in! grants and revokes the approval cdn-opt-in? reads"
    (rf.story.ui.a11y/set-cdn-opt-in! true)
    (is (true? (boolean (rf.story.ui.a11y/cdn-opt-in?))))
    (rf.story.ui.a11y/set-cdn-opt-in! false)
    (is (false? (boolean (rf.story.ui.a11y/cdn-opt-in?))))))

(deftest run-axe-surfaces-no-consent-without-opt-in
  (testing "without consent run-axe! records :no-consent — the state the panel
            renders its consent prompt from — so opening the panel never
            fetches remote JS"
    (rf.story.ui.a11y/set-cdn-opt-in! false)
    (let [frame-id :story.never-consented/x
          ;; A context, so the call gets past the :no-root branch.
          fake-ctx #js {:nodeType 1}]
      (try
        (let [p (rf.story.ui.a11y/run-axe! frame-id fake-ctx)]
          (is (some? p))
          (is (= :no-consent (rf.story.ui.a11y/status-for frame-id))))
        (finally
          (rf.story.ui.a11y/drop-frame-state! frame-id))))))
