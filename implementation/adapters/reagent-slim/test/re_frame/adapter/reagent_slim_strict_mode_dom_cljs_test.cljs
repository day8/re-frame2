(ns re-frame.adapter.reagent-slim-strict-mode-dom-cljs-test
  "rf2-a07937 — the reagent-slim React.StrictMode double-mount DOM proof for
  the bespoke class lifecycle + per-component render Reaction, under a React 19
  `createRoot`.

  STATUS: a required GREEN regression. Both assertions below must pass; a
  failure here is a real regression, not expected output.

  HISTORY (this header used to say the opposite — corrected under rf2-6r9j.32).
  This file was written under rf2-a07937 as the ACCEPTANCE GATE for a genuine
  slim production bug it surfaced, and until the repair landed its assertions
  (a) and (b) failed BY DESIGN: they encoded the correct contract, which the
  substrate did not yet honour. That state ended on 2026-07-12, when
  `2c4a87e3df` — fix(adapters/reagent-slim): re-establish render Reaction
  after StrictMode remount (rf2-6b6pex) — landed the production repair, the
  same day as this file's own commit `66e9e2af0e`. The FORMER-BUG block below
  is kept as the diagnosis that motivated the remount marker; it describes
  behaviour that no longer occurs.

  WHY THIS FILE EXISTS. reagent-slim hand-rolls the entire class lifecycle
  (`reagent2.impl.component`), the microtask render scheduler
  (`reagent2.impl.batching`), and a per-component render Reaction. Its
  `componentWillUnmount` UNCONDITIONALLY disposes + nils that render Reaction
  (`install-lifecycle-methods!`), and the render method lazily RECREATES it on
  the next render (`make-render-method`). React.StrictMode's development simulated
  unmount→remount stresses exactly that dispose/recreate + subscription re-wire.
  Stock reagent / uix all carry a StrictMode DOM scenario; slim did not
  (Finding 1 of the rf2-x76af2.37 clarity-nit review, deferred here). The
  sibling `reagent2/dom/client_cljs_test` proves the dispose by CALLING the
  prototype `componentWillUnmount` directly — but that never drives React's real
  reconciler, so the StrictMode reuse-state remount is unexercised. This file is
  the load-bearing real-DOM regression.

  ─────────────────────────────────────────────────────────────────────────────
  FORMER BUG (rf2-a07937 — a real slim bug this test surfaced; FIXED
  2026-07-12 by `2c4a87e3df`, rf2-6b6pex. Past tense throughout: this is the
  diagnosis the fix was built from, not current behaviour):

    Under React.StrictMode a slim component rendered ONCE and then went
    reactively DEAD. StrictMode's dev sequence for a slim class is:

        render ×2  →  componentDidMount  →  componentWillUnmount
                   →  componentDidMount  (NO intervening render)

    The transient `componentWillUnmount` disposes + nils the per-component
    render Reaction. The remount's second `componentDidMount` fires WITHOUT React
    calling `render()` again (StrictMode reuses the committed fiber output), so
    slim — which recreates the render Reaction only lazily inside `render()` —
    never re-established it. The remounted instance was left with
    `cljsRenderRea = nil` and NO subscription watches: it never re-rendered on an
    app-db change. A/B empirically confirmed at the time (same view, same
    harness): WITHOUT StrictMode the component was fully reactive (dispatch →
    DOM updates); WITH StrictMode a post-mount dispatch left the DOM stale.

    Impact, while it lasted: any slim app wrapped in `<React.StrictMode>` (the
    React-recommended dev default, scaffolded by CRA / Next.js) had components
    that stopped responding to state changes after mount.

  THE FIX, and what this file now guards (`reagent2.impl.component`):

    `componentWillUnmount` sets a `cljsRemountReattach` marker on the instance
    when it disposes a LIVE render Reaction, and `componentDidMount` is now
    installed UNCONDITIONALLY (even with no user `:component-did-mount`) so it
    can read that marker: seeing it, it clears it and queues a render via
    `reagent2.impl.batching/queue-render!`. `make-render-method` then recreates
    the Reaction and re-subscribes its deref'd watches. A normal first mount
    carries no marker (render() has already run, `cljsRenderRea` is live), so
    the reattach path is a no-op there. The marker lives on the instance, and
    React discards it with the instance on a genuine unmount.

    The two assertions below are the regression: (a) fails if the reattach is
    removed or the marker stops being set — the remounted instance goes
    reactively dead again and the DOM never advances past the seeded value;
    (b) fails if the transient Reaction leaks instead (watch count 2) or the
    surviving one is never re-established (watch count 0).
  ─────────────────────────────────────────────────────────────────────────────

  WHAT IT ASSERTS, under `<React.StrictMode>` and driven by `act()` (which
  drives StrictMode's mount→unmount→remount deterministically — `flushSync`
  does NOT run the StrictMode remount synchronously; only `act` does, per the
  stock frame-provider scenario-6 harness this file mirrors):

    (a) DOM RE-RENDERS with the NEW value after the strict double-mount. A
        subscribing slim reg-view is mounted under StrictMode; after the
        double-mount settles the committed DOM shows the seeded value, and a
        `dispatch` + `flush-render!` advances it to the dispatched value — i.e.
        the surviving (remounted) instance's render Reaction is live + re-wired.

    (b) The upstream RAtom's watch count RETURNS TO BASELINE across the
        lifecycle. The probe view also derefs a plain upstream `r/atom`; the
        render Reaction registers a watch on it via the same deref-capture edge
        it uses for the subscription (so this RAtom is a faithful, churn-free
        proxy for the render Reaction's watch graph — unlike the subscription
        Reaction, which the sub-cache disposes + rebuilds across the remount).
        Baseline is 0 before mount; EXACTLY 1 while mounted after the strict
        double-mount (one live render Reaction — a leaked transient would read
        2, a dead component reads 0); and 0 again after a genuine unmount.

  TEST-ONLY. ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test`
  discovers it for the real-DOM assertion; the `:node-test` runner also loads it
  (matches `cljs-test$`), where the body gates on `(browser?)` and no-ops."
  (:require [cljs.test :refer-macros [deftest is use-fixtures async]]
            [reagent2.dom.client :as rdc]
            [reagent2.core :as r]
            ["react" :as React]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]))

;; MAP-FORM fixture (`:async? true`): cljs.test requires `:each` fixtures to be
;; maps when the ns contains ANY `async` test (a fn-form fixture's teardown runs
;; before the async body's `done` fires). The deftest below is act-driven across
;; a real React StrictMode lifecycle, so it is async.
;;
;; EP-0002 (rf2-9o48ih): `:ambient-frame nil` opts out of the fixture's default
;; ambient `*current-frame*` :rf/default scope. The probe is a reg-view whose
;; `subscribe` resolves its frame from the enclosing `frame-provider` via the
;; React-context tier — an ambient :rf/default scope would shadow that tier and
;; read the wrong (empty) app-db. Mirrors the flush-render / sCU-gate DOM twins.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent-slim/adapter
     :async? true
     :ambient-frame nil}))

;; ---- browser gate ----------------------------------------------------------

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (when (browser?)
    (.createElement js/document "div")))

(defn- get-act
  "Return React's `act()` if available, else nil. React 19 promotes `act` to
  the React namespace proper (the test infra pins react/react-dom 19.2.0). Used
  to drive StrictMode's mount→unmount→remount double-invoke deterministically —
  `flushSync` does NOT run the StrictMode remount synchronously."
  []
  (when (exists? (.-act React)) (.-act React)))

(defn- watch-count
  "Number of reagent2-level watchers currently wired on RAtom `ra`. `.-watches`
  is the deftype's watch map (nil until the first watcher, then a CLJS map);
  `count` of nil is 0. Each live render Reaction that derefs `ra` inside its
  reactive context registers exactly one watch, so this counts live render
  Reactions holding `ra` as a dependency."
  [ra]
  (count (.-watches ^js ra)))

(defn- settle-macrotasks
  "Resolve after `n` macrotask turns so the StrictMode remount + the microtask
  render scheduler fully settle before asserting."
  [n]
  (js/Promise.
    (fn [resolve _]
      (letfn [(step [k] (if (zero? k) (resolve nil) (js/setTimeout #(step (dec k)) 4)))]
        (step n)))))

;; ---- the StrictMode double-mount DOM proof ---------------------------------

(deftest strict-mode-double-mount-rerenders-and-returns-watch-to-baseline
  "reagent-slim — a subscribing reg-view mounted under `<React.StrictMode>`
   re-renders with the dispatched value across the strict double-mount, and the
   upstream RAtom's watch count returns to baseline across the lifecycle
   (rf2-a07937). ACCEPTANCE GATE — see the ns FINDING block: (a)/(b) fail until
   the slim StrictMode-remount reactivity bug is fixed in production."
  (if-not (browser?)
    (is true ":node-test: no DOM — :browser-test runner exercises the assertions")
    (async done
      (let [frame-kw     :rf.reagent-slim-strict/probe-frame
            flush!       (:flush-render! rf.adapter.reagent-slim/adapter)
            ;; A plain reagent2 RAtom the probe view ALSO derefs. The render
            ;; Reaction wires a watch onto it via the SAME deref-capture edge it
            ;; uses for the subscription, so its `.-watches` count is a
            ;; churn-free proxy for the render Reaction's watch graph. An RAtom
            ;; (unlike a Reaction) never auto-disposes on empty watches, so its
            ;; count is a clean baseline probe across the sub-cache churn the
            ;; StrictMode remount causes.
            upstream     (r/atom :leak-probe-baseline)
            render-count (atom 0)
            done?        (atom false)
            ;; `done`-once guard: the act() thenable + macrotask chain has
            ;; several exit paths (success, a thrown assertion, a rejection).
            ;; Calling cljs.test's `done` twice aborts the suite, so funnel
            ;; every exit through one guarded call.
            done!        (fn [] (when (compare-and-set! done? false true) (done)))
            mount-node   (make-mount-node!)
            root         (rdc/create-root mount-node)
            cleanup!     (fn [] (try (rdc/unmount root) (catch :default _ nil)))
            act-fn       (get-act)]
        (rf/make-frame {:id frame-kw :doc "StrictMode double-mount probe frame"})
        (rf/reg-event ::seed (fn [{:keys [db]} _] {:db {:n 1}}))
        (rf/reg-event ::inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
        (rf/dispatch-sync [::seed] {:frame frame-kw})
        (rf/reg-sub ::n (fn [db _] (:n db)))
        (rf/reg-view* :rf.reagent-slim-strict/probe
                      (fn probe []
                        ;; SUBSCRIBING view (drives the DOM assertion) that ALSO
                        ;; derefs the upstream leak-probe RAtom (drives the
                        ;; watch-count assertion). Both derefs register on this
                        ;; render's Reaction.
                        (let [n      @(rf/subscribe [::n])
                              _probe @upstream]
                          (swap! render-count inc)
                          [:div "n=" n])))
        (if (nil? act-fn)
          (do (is true "act() not reachable from this runner; StrictMode scenario skipped")
              (done!))
          (let [render-fn (rf/view :rf.reagent-slim-strict/probe)]
            ;; Baseline: nothing watches the upstream RAtom before mount.
            (is (= 0 (watch-count upstream))
                "baseline: no watcher on the upstream RAtom before mount")
            (-> (js/Promise.resolve
                  (act-fn
                    (fn []
                      ;; StrictMode wraps the frame-provider'd subscribing view.
                      ;; `act` drives StrictMode's mount→unmount→remount so the
                      ;; transient first instance's componentWillUnmount (which
                      ;; disposes + nils its render Reaction) actually fires.
                      (rdc/render root
                                  [:> (.-StrictMode React)
                                   [rf/frame-provider {:frame frame-kw}
                                    [render-fn]]]))))
                (.then (fn [_] (settle-macrotasks 3)))
                (.then
                  (fn [_]
                    ;; StrictMode double-invokes the render body — the probe ran
                    ;; more than once (evidence StrictMode is actually active, so
                    ;; the assertions below are not vacuous).
                    (is (>= @render-count 2)
                        (str "StrictMode double-invoked the render body (got "
                             @render-count " renders)"))
                    ;; (a) initial: committed DOM shows the seeded value.
                    (is (= "n=1" (.-textContent mount-node))
                        "committed DOM shows the seeded value n=1 after the strict double-mount")
                    ;; (b) after the strict double-mount, EXACTLY ONE live render
                    ;; Reaction watches the upstream RAtom — the remounted
                    ;; instance is reactive and no transient watch leaked. (Reads
                    ;; 0 today: the remount leaves the render Reaction nil — the
                    ;; FINDING bug. A leak would read 2.)
                    (is (= 1 (watch-count upstream))
                        (str "exactly one live render Reaction watches the upstream RAtom "
                             "after the strict double-mount (remounted instance reactive, "
                             "no leaked transient watch); got " (watch-count upstream)))
                    ;; (a) re-render: dispatch advances the subscription; the
                    ;; surviving instance's render Reaction is re-wired, so
                    ;; flush-render! commits the new value.
                    (flush! (fn [] (rf/dispatch-sync [::inc] {:frame frame-kw})))
                    (is (= "n=2" (.-textContent mount-node))
                        "DOM re-rendered with the dispatched value n=2 (render Reaction re-wired)")
                    ;; Still exactly one watcher after the re-render — the
                    ;; recreate-on-render path did not accumulate a second watch.
                    (is (= 1 (watch-count upstream))
                        "still exactly one watcher after the post-dispatch re-render (no accretion)")
                    ;; Genuine final unmount under act.
                    (js/Promise.resolve (act-fn (fn [] (rdc/unmount root))))))
                (.then
                  (fn [_]
                    ;; After a genuine unmount the upstream watch count returns to
                    ;; its pre-mount baseline of 0 — the render Reaction's
                    ;; componentWillUnmount cleanly tore down its watch graph.
                    (is (= 0 (watch-count upstream))
                        (str "upstream watch count returned to baseline 0 after the genuine "
                             "unmount (clean teardown); got " (watch-count upstream)))
                    nil))
                ;; Reports; it does NOT finish. `done` hands
                ;; `cljs.test/run-block` a continuation that runs the WHOLE
                ;; remainder of the run synchronously, so a rejection handler
                ;; downstream of the step that finished the row claims whatever
                ;; a LATER namespace throws, prints it against this row's
                ;; label, and fires `done` a second time (rf2-e8kc).
                (.catch
                  (fn [err]
                    (is false (str "StrictMode double-mount scenario threw: " (pr-str err)))
                    nil))
                ;; Both arms cleaned up identically, so it rides the single
                ;; trailing step: written once, run once per path.
                (.then (fn [_] (cleanup!) (done!))))))))))
