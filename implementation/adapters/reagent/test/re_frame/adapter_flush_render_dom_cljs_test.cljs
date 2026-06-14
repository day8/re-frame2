(ns re-frame.adapter-flush-render-dom-cljs-test
  "rf2-40a84 — the Reagent synchronous-commit proof for the substrate-adapter
  contract fn `flush-render!`.

  WHAT IT PROVES. `(adapter/flush-render! f)` SYNCHRONOUSLY commits the render
  that `f`'s state change schedules — the rendered DOM reflects the dispatched
  value by the time `flush-render!` RETURNS, with NO wait for Reagent's
  `requestAnimationFrame`-scheduled `next-tick` re-render drain. This is the
  framework capability the re-frame2-pair MCP's headless `dispatch → render →
  observe-DOM` loop depends on (the rAF drain is throttled to ~never in a
  backgrounded tab; flushSync-class commits are not — see Spec 006
  §`flush-render!` + Spec Tool-Pair §Driving the render). The sibling
  rf2-vk79g dispatch-and-settle op consumes this.

  HOW THE PROOF IS RIGOROUS. After the initial mount commits, we dispatch a
  state change — under Reagent this only ENQUEUES the dependent component for a
  re-render on the next rAF/`next-tick` turn, so the committed DOM still shows
  the OLD value. We then call `(adapter/flush-render! ...)` and assert the DOM
  shows the NEW value on the very next line: the only thing that could have
  committed it synchronously is the flush. (`reagent.core/flush` drains the
  render queue immediately and, on React 19, commits via `react-dom/flushSync`
  — neither is rAF-scheduled.) The UIx / Helix twins of this proof live in the
  shared React suite (`assert-flush-render-synchronously-commits`).

  ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test` discovers it;
  the `:node-test` runner also loads it, where the body gates on `(browser?)`
  and no-ops cleanly (no DOM)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views]))

;; EP-0002 (rf2-9o48ih): `:ambient-frame nil` opts out of the fixture's default
;; ambient `*current-frame*` :rf/default scope. The probe is a reg-view whose
;; `subscribe` resolves its frame from the enclosing `frame-provider` via the
;; React-context tier. The render runs synchronously inside `flushSync` /
;; `flush-render!`, i.e. inside the test body's dynamic extent — an ambient
;; :rf/default scope would shadow the React-context tier at tier 1 and the
;; subscribe would read :rf/default's (empty) app-db instead of the provider's.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter
     :ambient-frame nil}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (when (browser?)
    (.createElement js/document "div")))

(deftest flush-render-synchronously-commits
  (testing "Reagent — flush-render! synchronously commits a pending render (rf2-40a84)"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [frame-kw :rf.reagent-flush-render/probe-frame
            flush!   (:flush-render! reagent-adapter/adapter)]
        (is (fn? flush!)
            "the Reagent adapter map exposes :flush-render! (rf2-40a84 contract slot)")
        (rf/reg-frame frame-kw {:doc "flush-render! synchronous-commit probe frame"})
        (rf/reg-event ::seed (fn [{:keys [db]} _] {:db {:n 1}}))
        (rf/reg-event ::inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
        (rf/dispatch-sync [::seed] {:frame frame-kw})
        (rf/reg-sub ::n (fn [db _] (:n db)))
        (rf/reg-view* :rf.reagent-flush-render/probe
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
                (rdc/render root [rf/frame-provider {:frame frame-kw}
                                  [(rf/view :rf.reagent-flush-render/probe)]])))
            (is (= "n=1" (.-textContent mount-node))
                "committed DOM shows the seeded value n=1")
            ;; Dispatch the change WITHOUT a manual flush — under Reagent this
            ;; only enqueues the dependent component for the next rAF/next-tick
            ;; re-render, so the committed DOM still reads the OLD value.
            (rf/dispatch-sync [::inc] {:frame frame-kw})
            ;; THE PROOF: flush-render! commits the enqueued re-render
            ;; synchronously. The DOM must reflect n=2 on the next line — no
            ;; rAF wait, no manual r/flush.
            (flush!)
            (is (= "n=2" (.-textContent mount-node))
                "DOM reflects the dispatched change SYNCHRONOUSLY after
                 flush-render! returns — no rAF wait (rf2-40a84)")
            (finally
              (try (.unmount root) (catch :default _ nil)))))))))
