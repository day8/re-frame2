(ns re-frame.adapter.reagent-slim-flush-render-dom-cljs-test
  "rf2-0bz5ah (split from rf2-ghfkkk issue 3) — the reagent-slim
  synchronous-commit proof for the substrate-adapter contract fn
  `flush-render!`, under a React 19 `createRoot`.

  WHAT IT PROVES. `(adapter/flush-render! f)` SYNCHRONOUSLY commits the render
  that `f`'s state change schedules — the rendered DOM reflects the dispatched
  value by the time `flush-render!` RETURNS, with NO wait for the rewrite's
  microtask-scheduled re-render drain. This is the framework capability the
  re-frame2-pair MCP's headless `dispatch → render → observe-DOM` loop depends
  on (the microtask drain is not guaranteed to have flushed by the time an
  eval'd dispatch returns; the synchronous flush is — see Spec 006
  §`flush-render!` + Spec Tool-Pair §Driving the render). Mirrors the
  Reagent-bridge twin `re-frame.adapter-flush-render-dom-cljs-test`; the
  UIx / Helix twins live in the shared React suite
  (`assert-flush-render-synchronously-commits`).

  WHY A reagent-slim-SPECIFIC FILE (not the shared suite helper). The shared
  `assert-flush-render-synchronously-commits` mounts its probe via
  `react-dom/client createRoot` + `.render` of a NATIVE React element (the
  UIx/Helix `$` shape). The ratom family renders hiccup THROUGH the substrate's
  own root (`reagent2.dom.client/create-root` + `reagent2.impl.template/
  as-element`), so the substrate-native mount path — the path the adapter's
  `:flush-render!` actually services — is exercised here directly, exactly as
  the Reagent-bridge twin does for stock `reagent.dom.client`.

  THE PREMISE THIS VERIFIES (rf2-0bz5ah). reagent-slim's adapter `:flush-render!`
  is `(f)` then `reagent2.impl.batching/flush!` — a synchronous drain that
  class-`forceUpdate`s every dirty component, with NO `react-dom/flushSync`
  boundary. Under React 19 `createRoot`, an update originating OUTSIDE React's
  batching (a bare `forceUpdate` from the flush! drain) commits synchronously
  by default — so the contract HOLDS without an explicit `flushSync`. This test
  is the empirical proof of that guarantee: if the bare `forceUpdate` did NOT
  commit synchronously, the post-`flush-render!` assertion would read the OLD
  value and fail, surfacing the need for a `flushSync` boundary.

  HOW THE PROOF IS RIGOROUS. After the initial mount commits (wrapped in
  `react-dom/flushSync` so React 19's otherwise-async `root.render` first pass
  is committed before `render` returns), we dispatch a state change — under the
  rewrite this only ENQUEUES the dependent component for a microtask-turn
  re-render, so the committed DOM still shows the OLD value. We then call
  `(adapter/flush-render! ...)` and assert the DOM shows the NEW value on the
  very next line: the only thing that could have committed it synchronously is
  the flush.

  ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test` discovers it for
  the real-DOM assertion; the `:node-test` runner also loads it, where the body
  gates on `(browser?)` and no-ops cleanly (no DOM)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent2.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent-slim :as reagent-slim-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views]))

;; EP-0002 (rf2-9o48ih): `:ambient-frame nil` opts out of the fixture's default
;; ambient `*current-frame*` :rf/default scope. The probe is a reg-view whose
;; `subscribe` resolves its frame from the enclosing `frame-provider` via the
;; React-context tier. The render runs synchronously inside `flushSync` /
;; `flush-render!`, i.e. inside the test body's dynamic extent — an ambient
;; :rf/default scope would shadow the React-context tier at tier 1 and the
;; subscribe would read :rf/default's (empty) app-db instead of the provider's
;; (the seeded probe frame), rendering "n=" instead of the counter value.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-slim-adapter/adapter
     :ambient-frame nil}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (when (browser?)
    (.createElement js/document "div")))

(deftest flush-render-synchronously-commits
  (testing "reagent-slim — flush-render! synchronously commits a pending render under createRoot (rf2-0bz5ah)"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [frame-kw :rf.reagent-slim-flush-render/probe-frame
            flush!   (:flush-render! reagent-slim-adapter/adapter)]
        (is (fn? flush!)
            "the reagent-slim adapter map exposes :flush-render! (rf2-0bz5ah contract slot)")
        (rf/reg-frame frame-kw {:doc "flush-render! synchronous-commit probe frame"})
        (rf/reg-event ::seed (fn [{:keys [db]} _] {:db {:n 1}}))
        (rf/reg-event ::inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
        (rf/dispatch-sync [::seed] {:frame frame-kw})
        (rf/reg-sub ::n (fn [db _] (:n db)))
        (rf/reg-view* :rf.reagent-slim-flush-render/probe
                      (fn probe []
                        [:div "n=" @(rf/subscribe [::n])]))
        (let [mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)]
          (try
            ;; Initial mount wrapped in flushSync so the first pass commits
            ;; before render returns (React 19 root.render is otherwise
            ;; async — mirrors the cross-spec DOM tests' mount shape).
            (react-dom/flushSync
              (fn []
                (rdc/render root [rf/frame-provider-existing {:frame frame-kw}
                                  [(rf/view :rf.reagent-slim-flush-render/probe)]])))
            (is (= "n=1" (.-textContent mount-node))
                "committed DOM shows the seeded value n=1")
            ;; Dispatch the change WITHOUT a manual flush — under the rewrite
            ;; this only enqueues the dependent component for the next
            ;; microtask-turn re-render, so the committed DOM still reads the
            ;; OLD value.
            (rf/dispatch-sync [::inc] {:frame frame-kw})
            ;; THE PROOF: flush-render! commits the enqueued re-render
            ;; synchronously. The DOM must reflect n=2 on the next line — no
            ;; microtask wait, no manual flush-views!. (The bare class
            ;; forceUpdate the batching drain issues commits synchronously
            ;; under React 19's default-flush-for-non-batched-updates rule;
            ;; if it did not, this assertion would read n=1 and fail —
            ;; surfacing the need for a flushSync boundary.)
            (flush!)
            (is (= "n=2" (.-textContent mount-node))
                "DOM reflects the dispatched change SYNCHRONOUSLY after
                 flush-render! returns — no microtask wait (rf2-0bz5ah)")
            (finally
              (try (.unmount root) (catch :default _ nil)))))))))
