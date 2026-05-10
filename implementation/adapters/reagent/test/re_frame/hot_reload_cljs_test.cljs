(ns re-frame.hot-reload-cljs-test
  "CLJS-side hot-reload coverage for Spec 001 §Hot-reload semantics.

  Three of the five contract guarantees are pinned on the JVM in
  hot_reload_test.clj. Rule 4 — view re-registration causes mounted
  views to re-render — is Reagent-side and lives here.

  The node-test runner has no DOM, so we don't mount through React.
  Instead we exercise the contract at the layer the substrate cares
  about: registry lookup of the wrapped render fn. After re-register,
  re-frame.core/view (the lookup the substrate's render-cycle uses
  to resolve a view by id) returns the new render fn — the next render
  cycle invokes that, which is exactly what Reagent does on a mounted
  view when the captured render fn changes via hot-reload.

  A render-counter atom (mutated inside each render-fn body) makes the
  observation crisp: pre-rereg renders bump the v1 counter; post-rereg
  renders bump the v2 counter.

  Fixture: per rf2-am9d this file uses the shared
  `re-frame.test-support/reset-runtime-fixture`, which snapshots the
  registrar before each test and restores it after. Snapshot/restore
  preserves ns-load-time framework / example registrations (notably
  routing's framework events and nine-states.core's view set) — those
  cannot be re-loaded under CLJS — while still rolling back this
  file's per-test `reg-view` calls so the :rf.hot-reload-test/* slots
  don't leak into other test ns runs."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views])
  (:require-macros [re-frame.views-macros :refer [reg-view]]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; ---- (4) :view re-register flips the next render to the new body --------

(deftest view-re-register-causes-rerender
  (testing "after re-registering a view, the next render uses the new body"
    (let [v1-renders (atom 0)
          v2-renders (atom 0)
          ;; v1 of :rf.hot-reload-test/widget — a render fn that bumps the v1 counter and
          ;; returns a hiccup tree tagged :v1.
          v1-fn (fn [n]
                  (swap! v1-renders inc)
                  [:span.v1 "v1-" n])
          v2-fn (fn [n]
                  (swap! v2-renders inc)
                  [:strong.v2 "v2-" n])]
      ;; Use the runtime fn form (rf/reg-view*) — the JVM-and-CLJS-shared
      ;; surface for runtime registration with a Var-ref body. The macro
      ;; form (rf/reg-view) is defn-shape only and rejects non-literal-fn
      ;; bodies at compile time (per Spec 004 §reg-view); for hot-reload
      ;; mechanics what matters is that the registrar's :view slot gets
      ;; replaced — exactly what reg-view* does.
      (rf/reg-view* :rf.hot-reload-test/widget v1-fn)
      (let [render-v1 (rf/view :rf.hot-reload-test/widget)]
        ;; First render uses v1.
        ;; Per Spec 006 §Source-coord annotation (rf2-z7f7), the
        ;; reg-view* wrapper splices :data-rf2-source-coord into the
        ;; root attrs map under interop/debug-enabled?. Match the
        ;; first / last slots structurally so the test stays focused
        ;; on the v1-vs-v2 hot-reload contract.
        (let [out (render-v1 7)]
          (is (= :span.v1 (first out)) "v1 render fn produces v1 hiccup root tag")
          (is (= ["v1-" 7] (drop 2 out)) "v1 render fn produces v1 children"))
        (is (= 1 @v1-renders) "v1 was rendered once")
        (is (= 0 @v2-renders) "v2 has not rendered")
        ;; Re-register :rf.hot-reload-test/widget with v2 body.
        (rf/reg-view* :rf.hot-reload-test/widget v2-fn)
        ;; The registry's :view slot now resolves to v2's wrapped fn.
        ;; Reagent's render cycle re-resolves the head on each render
        ;; (the captured Var or the view return), so the next render
        ;; uses v2.
        (let [render-v2 (rf/view :rf.hot-reload-test/widget)]
          (let [out (render-v2 7)]
            (is (= :strong.v2 (first out)) "post-rereg lookup returns v2's root tag")
            (is (= ["v2-" 7] (drop 2 out)) "post-rereg lookup returns v2's children"))
          (is (= 1 @v1-renders)
              "v1 was NOT re-invoked by the post-rereg render")
          (is (= 1 @v2-renders)
              "v2 was rendered once")
          ;; Render again to confirm the new body sticks.
          (render-v2 9)
          (is (= 2 @v2-renders) "v2 keeps being the active render fn")))))

  (testing "subscribed observer atom — proves the render BODY changed,
            not just the wrapper, by routing observation through the
            registered render fn rather than the captured Var"
    (let [observed (atom nil)]
      (rf/reg-view* :rf.hot-reload-test/probe
        (fn []
          (reset! observed :body-v1)
          [:p "v1"]))
      ;; Simulate the substrate's render cycle: resolve via view
      ;; (this is the canonical id-keyed lookup) and invoke.
      ;; After the call, observed reflects v1.
      ((rf/view :rf.hot-reload-test/probe))
      (is (= :body-v1 @observed))
      ;; Re-register with a DIFFERENT body.
      (rf/reg-view* :rf.hot-reload-test/probe
        (fn []
          (reset! observed :body-v2)
          [:p "v2"]))
      ;; Next "render" — fresh registry resolution — runs v2.
      ((rf/view :rf.hot-reload-test/probe))
      (is (= :body-v2 @observed)
          "after re-registration, the next render mutates observed to v2"))))

(deftest view-re-register-via-macro-also-flips
  (testing "the reg-view MACRO path also installs the new render fn into
            the registry — verifying the contract holds for both surfaces"
    ;; The macro auto-defs a local Var named after the supplied symbol
    ;; and registers under (keyword *ns* sym), or under a ^{:rf/id ...}
    ;; metadata override. The underlying registration goes through
    ;; re-frame.core/reg-view*, which calls registrar/register! with the
    ;; wrapped fn. Hot-reload semantics ride on the registrar swap, so
    ;; the macro form must respect the contract too.
    ;;
    ;; Re-evaluating the same defn-shape form (same sym → same auto-id)
    ;; is the canonical macro-side hot-reload path: the file changes,
    ;; the ns reloads, the same form re-runs, and the registry slot is
    ;; replaced.
    (let [observed (atom nil)]
      (reg-view ^{:rf/id :rf.hot-reload-test/banner} banner [t]
        (reset! observed [:m1 t])
        [:h1.m1 t])
      ((rf/view :rf.hot-reload-test/banner) "hello")
      (is (= [:m1 "hello"] @observed))
      (reg-view ^{:rf/id :rf.hot-reload-test/banner} banner [t]
        (reset! observed [:m2 t])
        [:h2.m2 t])
      ((rf/view :rf.hot-reload-test/banner) "world")
      (is (= [:m2 "world"] @observed)
          "post-rereg lookup invokes the new macro-installed body"))))
