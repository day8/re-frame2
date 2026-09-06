(ns re-frame.adapter.reagent-slim-scu-argv-gate-dom-cljs-test
  "rf2-5al9d7 — the reagent-slim framework-default `shouldComponentUpdate`
  DOM regression proof, under a React 19 `createRoot`.

  WHAT IT PROVES. Every reagent-slim class carries a framework-INTERNAL
  argv-equality `shouldComponentUpdate` (restored from stock Reagent). When a
  parent re-renders but a child's argv is `=`, the child's render fn AND its
  `:component-did-update` lifecycle do NOT run — the signature fine-grained
  re-render property. When the child's argv CHANGES, they DO run. This is the
  real-React proof that the gate governs parent propagation: the unit tests in
  `component_cljs_test` call prototype methods directly and cannot observe
  React's reconciliation bailout, so this DOM-level test is the load-bearing
  regression.

  It also proves the gate does NOT globally suppress work: the child in the
  changed-argv case commits through ordinary reconciliation. The complementary
  guarantee — that same-argv SUBSCRIPTION invalidation still commits (because
  the reactive drain uses `forceUpdate`, which React specifies bypasses
  `shouldComponentUpdate`) — is proven by the sibling
  `reagent_slim_flush_render_dom_cljs_test`, which stays green alongside this
  file.

  WHY A reagent-slim-SPECIFIC DOM FILE. The gate is a class-prototype
  `shouldComponentUpdate` React only consults during real reconciliation. It
  cannot be exercised by invoking prototype methods directly (no reconciler in
  the loop); only a `react-dom/client createRoot` mount + a genuine parent
  re-render surfaces the bailout. So the probe mounts through the substrate's
  own root (`reagent2.dom.client/create-root` + hiccup), exactly the path a
  slim app runs.

  ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test` discovers it
  for the real-DOM assertion; the `:node-test` runner also loads it, where the
  body gates on `(browser?)` and no-ops cleanly (no DOM)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent2.dom.client :as rdc]
            [reagent2.core :as r]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]))

;; EP-0002 (rf2-9o48ih): `:ambient-frame nil` opts out of the fixture's
;; default ambient `*current-frame*` :rf/default scope so the probe's
;; subscribes resolve their frame from the enclosing `frame-provider` via the
;; React-context tier (mirrors the flush-render DOM twin).
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent-slim/adapter
     :ambient-frame nil}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (when (browser?)
    (.createElement js/document "div")))

(deftest scu-argv-gate-skips-equal-argv-child-renders-changed-argv
  (testing "reagent-slim — the framework-default sCU gates parent-propagated child re-renders by argv `=` (rf2-5al9d7)"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [frame-kw     :rf.reagent-slim-scu/probe-frame
            flush!       (:flush-render! rf.adapter.reagent-slim/adapter)
            render-count (atom 0)
            update-count (atom 0)
            ;; The child's ONLY input is the arg its parent passes down — it
            ;; subscribes to nothing, so the only cause that can re-render it
            ;; is parent propagation, which the sCU gates. `:reagent-render`
            ;; receives the user-args slice of the argv (NOT `this`), so it
            ;; takes the passed value directly; it counts renders.
            ;; `:component-did-update` counts commits AFTER the first.
            child (r/create-class
                    {:display-name "scu-probe-child"
                     :reagent-render
                     (fn [v]
                       (swap! render-count inc)
                       [:span "child-v=" v])
                     :component-did-update
                     (fn [_this _prev-argv _prev-state _snapshot]
                       (swap! update-count inc))})]
        (rf/make-frame {:id frame-kw :doc "sCU argv-gate probe frame"})
        (rf/reg-event ::seed       (fn [_ _] {:db {:tick 0 :child-v 1}}))
        (rf/reg-event ::bump-tick  (fn [{:keys [db]} _] {:db (update db :tick inc)}))
        (rf/reg-event ::bump-child (fn [{:keys [db]} _] {:db (update db :child-v inc)}))
        (rf/dispatch-sync [::seed] {:frame frame-kw})
        (rf/reg-sub ::tick    (fn [db _] (:tick db)))
        (rf/reg-sub ::child-v (fn [db _] (:child-v db)))
        ;; The parent subscribes to BOTH :tick and :child-v so it re-renders
        ;; on either — but it only passes :child-v down to the child. Bumping
        ;; :tick re-renders the parent WITHOUT changing the child's argv;
        ;; bumping :child-v re-renders the parent AND changes the child's argv.
        (rf/reg-view* :rf.reagent-slim-scu/parent
                      (fn parent []
                        (let [tick @(rf/subscribe [::tick])
                              cv   @(rf/subscribe [::child-v])]
                          [:div
                           [child cv]
                           [:span "tick=" tick]])))
        (let [mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)]
          (try
            ;; Initial mount wrapped in flushSync so the first pass commits
            ;; before render returns (React 19 root.render is otherwise async).
            (react-dom/flushSync
              (fn []
                (rdc/render root [rf/frame-provider {:frame frame-kw}
                                  [(rf/view :rf.reagent-slim-scu/parent)]])))
            ;; Initial mount: child rendered once; no update lifecycle yet
            ;; (React never calls sCU or componentDidUpdate on the first pass).
            (is (= 1 @render-count) "child render fn ran once on initial mount")
            (is (= 0 @update-count) "no :component-did-update on the initial mount")
            (is (= "child-v=1tick=0" (.-textContent mount-node))
                "committed DOM shows the seeded child-v=1 tick=0")

            ;; ── EQUAL ARGV. Bump :tick: the parent re-renders (its :tick sub
            ;; fired), producing a fresh child element whose argv is `=` to the
            ;; live one. The sCU returns false → the child's render fn AND
            ;; :component-did-update MUST NOT run. The parent's own "tick="
            ;; text still updates (the parent re-renders via forceUpdate, which
            ;; bypasses ITS sCU).
            (rf/dispatch-sync [::bump-tick] {:frame frame-kw})
            (flush!)
            (is (= 1 @render-count)
                "EQUAL-ARGV parent re-render: child render fn did NOT run (sCU skipped it)")
            (is (= 0 @update-count)
                "EQUAL-ARGV parent re-render: child :component-did-update did NOT run (sCU skipped it)")
            (is (= "child-v=1tick=1" (.-textContent mount-node))
                "parent's own text advanced to tick=1 while the child DOM stayed child-v=1")

            ;; ── CHANGED ARGV. Bump :child-v: the parent re-renders (its
            ;; :child-v sub fired) and the child's argv CHANGES. The sCU
            ;; returns true → the child's render fn AND :component-did-update
            ;; DO run.
            (rf/dispatch-sync [::bump-child] {:frame frame-kw})
            (flush!)
            (is (= 2 @render-count)
                "CHANGED-ARGV parent re-render: child render fn ran (sCU allowed the render)")
            (is (= 1 @update-count)
                "CHANGED-ARGV parent re-render: child :component-did-update ran (sCU allowed the render)")
            (is (= "child-v=2tick=1" (.-textContent mount-node))
                "child DOM reflects the changed arg child-v=2")
            (finally
              (try (.unmount root) (catch :default _ nil)))))))))
