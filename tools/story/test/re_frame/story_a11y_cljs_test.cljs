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

;; ---- rf2-20w5i: axe-core CDN load is opt-in only ------------------------
;;
;; Per the security audit, the axe-core load is gated behind a
;; persisted opt-in. These tests cover the contract surface:
;; `cdn-opt-in?` defaults to false (or whatever localStorage holds),
;; `set-cdn-opt-in!` flips it, and `run-axe!` short-circuits to
;; `:no-consent` when the dev hasn't approved. The companion JVM
;; test (`re-frame.story-a11y-source-test`) checks the source for
;; the SRI / crossorigin attributes and the consent-prompt text.

(deftest cdn-opt-in-defaults-to-false-after-revoke
  (testing "set-cdn-opt-in! false clears the persisted approval —
            the fresh-browser shape. A subsequent cdn-opt-in? must
            read false so the consent prompt re-renders."
    (a11y/set-cdn-opt-in! false)
    (is (false? (boolean (a11y/cdn-opt-in?)))
        "after revocation the opt-in predicate must read false")))

(deftest cdn-opt-in-roundtrips
  (testing "set-cdn-opt-in! true persists the approval; set-cdn-opt-in!
            false revokes it. The persistence is `localStorage`-backed
            so a single click per browser-session is enough."
    (a11y/set-cdn-opt-in! true)
    (is (true? (boolean (a11y/cdn-opt-in?))))
    (a11y/set-cdn-opt-in! false)
    (is (false? (boolean (a11y/cdn-opt-in?))))))

(deftest run-axe-surfaces-no-consent-without-opt-in
  (testing "run-axe! short-circuits to `:no-consent` when the dev
            hasn't approved the CDN load. The panel reads this state
            to render the consent prompt instead of triggering the
            load — defence against the pre-fix shape where a single
            panel-open inadvertently fetched remote JS."
    (a11y/set-cdn-opt-in! false)
    (let [frame-id :story.never-consented/x
          ;; Pass a fake context so the call doesn't short-circuit on
          ;; the prior `:no-root` branch.
          fake-ctx #js {:nodeType 1}]
      (try
        (let [p (a11y/run-axe! frame-id fake-ctx)]
          (is (some? p) "run-axe! returns a Promise even on :no-consent")
          (is (= :no-consent (get @a11y/run-state frame-id))
              "without consent the panel must surface :no-consent —
               NOT :running or :loading — so the consent prompt has
               time to render"))
        (finally
          (a11y/drop-frame-state! frame-id))))))
