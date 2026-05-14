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

;; ---- rf2-qgms1: variant-root scoping ------------------------------------

(deftest variant-root-selector-targets-data-attribute
  (testing "variant-root-selector returns a CSS attribute selector keyed on the variant id"
    (let [sel (a11y/variant-root-selector :story.counter/loaded)]
      (is (string? sel))
      ;; Must use the data attribute the canvas / workspace stamp.
      (is (re-find #"data-rf-story-variant-root=" sel))
      ;; pr-str of a namespaced keyword includes the leading colon.
      (is (re-find #":story.counter/loaded" sel))
      ;; Closing-bracket selector form so `querySelector` accepts it.
      (is (.startsWith sel "[data-rf-story-variant-root="))
      (is (.endsWith   sel "]")))))

(deftest variant-root-selector-distinct-per-variant
  (testing "different variant-ids yield distinct selectors"
    (is (not= (a11y/variant-root-selector :story.counter/loaded)
              (a11y/variant-root-selector :story.counter/clicked-three-times)))))

(deftest run-axe-handles-no-variant-root
  (testing "run-axe! sets :no-root state when no variant root resolves
            — the default arity uses find-variant-root which returns nil
            outside a browser DOM (node-runtime test env), so calling
            run-axe! with just the frame-id must short-circuit cleanly
            and surface a :no-root status to the panel."
    (let [frame-id :story.never-mounted/x
          ;; Mute the warn so test output stays clean.
          orig-warn js/console.warn]
      (set! js/console.warn (fn [& _] nil))
      (try
        (let [p (a11y/run-axe! frame-id)]
          (is (some? p) "run-axe! returns a Promise even on the no-root path")
          (is (= :no-root (get @a11y/run-state frame-id))
              "run-state for an unmounted variant must be :no-root, NOT :running or :done — surfacing that the scan was not run against the wrong tree"))
        (finally
          (set! js/console.warn orig-warn)
          (a11y/drop-frame-state! frame-id))))))
