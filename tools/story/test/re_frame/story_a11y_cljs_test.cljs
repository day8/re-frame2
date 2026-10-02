(ns re-frame.story-a11y-cljs-test
  "CLJS smoke tests for the a11y panel.

  The actual axe-core integration is a browser concern (it injects a
  `<script>` tag from a CDN); these smoke tests cover the panel's
  registration + state-management surface that's load-bearing in the
  CLJS bundle without requiring a live browser."
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
  (testing "the a11y panel body declares :placement :right + :render"
    (let [body (rf.story/handler-meta :story-panel rf.story.ui.a11y/panel-id)]
      (is (= :right (:placement body)))
      (is (= rf.story.ui.a11y/panel-render-id (:render body)))
      (is (string? (:title body))))))

(deftest a11y-render-view-roots-in-dom-element
  (testing "the a11y panel-render view returns hiccup whose root is a DOM
            element keyword (`:div`), not a bare component reference.

            Per Spec 006 §Source-coord annotation the annotator can only
            attach `data-rf2-source-coord` to hiccup DOM roots; a bare
            `[panel variant-id]` root makes the panel invisible to Story
            Inspect Mode + Xray Inspect Mode. The `[:div]`
            wrap is load-bearing."
    (let [view-fn (rf/view rf.story.ui.a11y/panel-render-id)
          out     (view-fn :story.unknown/y)]
      (is (vector? out)
          "panel-render returns a hiccup vector")
      (is (= :div (first out))
          "hiccup root is the DOM element `:div` per the source-coord-annotator
           wrap, not a component ref"))))

;; ---- violations stylesheet ----------------------------------------------

(deftest violations-stylesheet-non-empty
  (testing "the violations stylesheet is a non-empty CSS string"
    (is (string? rf.story.ui.a11y/violations-stylesheet))
    (is (pos? (count rf.story.ui.a11y/violations-stylesheet)))))

;; ---- variant-root scoping -----------------------------------------------

(deftest variant-root-selector-targets-data-attribute
  (testing "variant-root-selector returns the CSS attribute selector on the
            data attribute the canvas / workspace stamp, keyed on the
            pr-str'd variant id in single quotes so `querySelector` accepts it"
    (is (= "[data-rf-story-variant-root=':story.counter/loaded']"
           (rf.story.ui.a11y/variant-root-selector :story.counter/loaded)))))

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
        (let [p (rf.story.ui.a11y/run-axe! frame-id)]
          (is (some? p) "run-axe! returns a Promise even on the no-root path")
          (is (= :no-root (rf.story.ui.a11y/status-for frame-id))
              "run-state for an unmounted variant must be :no-root, NOT :running or :done — surfacing that the scan was not run against the wrong tree"))
        (finally
          (set! js/console.warn orig-warn)
          (rf.story.ui.a11y/drop-frame-state! frame-id))))))

;; ---- axe-core CDN load is opt-in only -----------------------------------
;;
;; The axe-core load is gated behind a persisted opt-in. These tests cover the contract surface:
;; `set-cdn-opt-in!` grants and revokes the approval `cdn-opt-in?` reads,
;; and `run-axe!` short-circuits to
;; `:no-consent` when the dev hasn't approved. The companion JVM
;; test (`re-frame.story-a11y-source-test`) checks the source for
;; the SRI / crossorigin attributes and the consent-prompt text.

(deftest cdn-opt-in-roundtrips
  (testing "set-cdn-opt-in! true persists the approval; set-cdn-opt-in!
            false revokes it. The persistence is `localStorage`-backed
            so a single click per browser-session is enough."
    (rf.story.ui.a11y/set-cdn-opt-in! true)
    (is (true? (boolean (rf.story.ui.a11y/cdn-opt-in?))))
    (rf.story.ui.a11y/set-cdn-opt-in! false)
    (is (false? (boolean (rf.story.ui.a11y/cdn-opt-in?))))))

(deftest run-axe-surfaces-no-consent-without-opt-in
  (testing "run-axe! short-circuits to `:no-consent` when the dev
            hasn't approved the CDN load. The panel reads this state
            to render the consent prompt instead of triggering the
            load, so a single panel-open never fetches remote JS."
    (rf.story.ui.a11y/set-cdn-opt-in! false)
    (let [frame-id :story.never-consented/x
          ;; Pass a fake context so the call doesn't short-circuit on
          ;; the prior `:no-root` branch.
          fake-ctx #js {:nodeType 1}]
      (try
        (let [p (rf.story.ui.a11y/run-axe! frame-id fake-ctx)]
          (is (some? p) "run-axe! returns a Promise even on :no-consent")
          (is (= :no-consent (rf.story.ui.a11y/status-for frame-id))
              "without consent the panel must surface :no-consent —
               NOT :running or :loading — so the consent prompt has
               time to render"))
        (finally
          (rf.story.ui.a11y/drop-frame-state! frame-id))))))
