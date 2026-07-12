(ns reagent2.dom.client
  "Mount entry + test-flush primitive for the day8/reagent-slim artefact.

  Per IMPL-SPEC §2.4 + §4. The `flush-views!` test primitive and the
  mount entries (`create-root`, `render`, `unmount`, `hydrate-root`)
  all live here. `render` / `hydrate-root` walk the user's hiccup once
  via the hiccup interpreter `reagent2.impl.template/as-element` and
  hand the resulting React element tree to React's root.

  Public Vars (per IMPL-SPEC §2.4):

    create-root      [container] | [container options]
    render           [root el]
    unmount          [root]
    hydrate-root     [container el] | [container el options]
    flush-views!     []      ;; test-flush primitive — implemented here

  The `flush-views!` contract (IMPL-SPEC §4.6):

    After (flush-views!) returns:
      - All currently-dirty components have re-rendered.
      - All Reactions whose dependencies changed have recomputed.
      - All :after-render callbacks queued before flush-views! fired.
      - React's pending work has committed (act() has run to completion).

  Suspense-ordering choice for `flush-views!` (rf2-w6ef):

    microtask -> act -> microtask

    Step 1. Drain the microtask queue first (await Promise.resolve())
            so any in-flight rea-schedule that's already been queued
            gets to run. This converts pending reactive work into
            React-visible state changes BEFORE we hand off to act.

    Step 2. react/act wraps a synchronous (batching/flush!) call. Inside
            act, React drains its commit phase + any Suspense boundaries
            whose pending Promises have resolved.

    Step 3. Drain the microtask queue once more so post-commit
            after-render hooks + any Reaction recomputes triggered
            during commit observe their final values before
            flush-views! returns.

    Why this order: a dispatch-then-flush test does
    `(rf/dispatch-sync ...) (flush-views!)`. The dispatch may already
    have advanced pending Reactions; step 1 collects that. Step 2
    pumps React itself. Step 3 settles any tail-cascade Reactions
    that ran inside the React commit. With Suspense: a child
    component that throws a Promise during render in step 2
    causes act to await the Promise; on resolution act re-renders
    the now-ready subtree. Step 3 then catches any Reaction
    recomputes triggered by that re-render.

    Determinism guarantee: a test that dispatches an event whose
    handler (a) updates app-db, (b) causes a subscription to recompute,
    (c) re-renders a component, (d) the component throws a Promise
    that resolves synchronously — the test's post-flush-views!
    assertion sees the resolved-tree state. Demonstrated by the
    Suspense test in dom_client_cljs_test.cljs.

  Production cost: zero. `flush-views!` is gated on `js/goog.DEBUG` and
  DCEs entirely under `:advanced` + `goog.DEBUG=false`."
  (:require [reagent2.impl.batching :as batching]
            [reagent2.impl.template :as template]
            ["react" :as react]
            ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]))

;; ---------------------------------------------------------------------------
;; flush-views! — test-flush primitive (Stage 4-B)
;; ---------------------------------------------------------------------------
;;
;; Implementation note on `react/act`: under React 18.3+ `act` is a
;; top-level export of `react`. Under React 18.2 `act` lived in
;; `react-dom/test-utils`. The artefact targets React 19 (Stage 1
;; commitment); we still feature-detect to stay graceful under the
;; React 18.3 dev tree the implementation/ tests run under, since
;; that ALSO has `react/act`.
;;
;; Per IMPL-SPEC §4.2 `flush-views!` is dev-only: gated on `js/goog.DEBUG`
;; so :advanced + `goog.DEBUG=false` DCEs the body entirely.
;; ---------------------------------------------------------------------------

(defn- resolve-act
  "Look up React's `act`. Returns the fn or nil. Re-resolved on every
  call (not cached) so a test fixture that swaps the React module
  mid-run sees the swap on the next `flush-views!`.

  React-floor asymmetry (rf2-uuzkp). This SUBSTRATE-level resolver is
  React-19-floored BY INTENT: it probes only `(.-act react)` and has NO
  `react-dom/test-utils` fallback, so under a React-18.2 dev tree (where
  `act` lived in test-utils) the substrate-level `flush-views!` below
  degrades to a plain synchronous flush. This is NOT in conflict with the
  CANONICAL cross-substrate test-flush path: the adapter-ns `flush-views!`
  Var (`re-frame.adapter.reagent-slim/flush-views!`, surfaced identically
  across all four substrates per rf2-b6nm5 Decision 6) routes through the
  spine's `resolve-act-fn` (`re-frame.substrate.spine`), which DOES carry
  the test-utils fallback (rf2-jk7hr) and so works under React 18.x. The
  fallback there is load-bearing for the stock-Reagent (non-slim) adapter;
  this substrate-level resolver intentionally omits it. A future React-floor
  change, or a caller reaching this substrate-level `flush-views!` directly
  (e.g. for its Promise return / Suspense ordering), should expect the
  React-19 floor here and use the adapter-ns Var when React-18 graceful
  degradation is required."
  []
  (or (.-act react)
      ;; Fallback: pre-18.3 lived in react-dom/test-utils. We don't
      ;; require that ns up top because Stage 4-B's React floor is
      ;; 19+; the .-act check on the react module is the only path
      ;; we exercise. See the React-floor asymmetry note above for why
      ;; the canonical adapter-ns flush-views! (via the spine resolver)
      ;; still degrades gracefully under React 18.x while this one does not.
      nil))

(defn- microtask-tick
  "Return a Promise that resolves on the next microtask turn. Awaiting
  this inside `act`'s thunk lets React process pending work that's
  scheduled as a microtask continuation."
  []
  (js/Promise.resolve))

(defn flush-views!
  "Drain pending render work synchronously. Test-only primitive.

  Composes a 3-phase drain: microtask -> act(flush!) -> microtask.
  Returns a Promise so callers can `await` deterministic completion;
  the synchronous side-effects (dirty components forceUpdate'd,
  Reactions recomputed, :after-render hooks fired, React commit
  complete) happen by the time `act`'s callback returns.

  Production cost: zero — the body is gated on `goog.DEBUG` so
  `:advanced` + `goog.DEBUG=false` DCEs it entirely."
  []
  (when ^boolean js/goog.DEBUG
    (let [act (resolve-act)]
      (if (nil? act)
        ;; No `act` available — degrade to a plain synchronous flush.
        ;; Tests running under :node-test (no real React render path)
        ;; still get a valid drain of the rea-queue + dirty-set.
        (do (batching/flush!) nil)
        (act
          (fn []
            ;; Step 1: microtask tick — let any pending rea-schedule
            ;; microtask run before we drive the synchronous drain.
            ;; Step 2: synchronous flush! drains rea-queue + dirty-set.
            ;; Step 3: a second microtask tick lets React's commit-phase
            ;; settle before act returns.
            (-> (microtask-tick)
                (.then (fn [_]
                         (batching/flush!)
                         (microtask-tick))))))))))

;; ---------------------------------------------------------------------------
;; flush-render! — production synchronous render-commit (rf2-40a84 / rf2-0bz5ah)
;; ---------------------------------------------------------------------------
;;
;; The production-grade synchronous render-commit the adapter's
;; `:flush-render!` slot services (distinct from the `act`-composing,
;; goog.DEBUG-gated `flush-views!` test primitive above). Runs `f` (which
;; may mutate a ratom / dispatch an event), then drains the rea-queue +
;; forceUpdates every dirty component via `batching/flush!` — INSIDE a
;; `react-dom/flushSync` boundary so the forced re-renders COMMIT TO THE
;; DOM synchronously before this returns.
;;
;; Why the flushSync boundary is load-bearing (rf2-0bz5ah). Under React 19
;; `createRoot`, a bare `forceUpdate` issued from outside React's batching
;; context is subject to automatic batching: React SCHEDULES the re-render
;; rather than committing it synchronously, so the DOM still reflects the
;; OLD value when `batching/flush!` returns. `flushSync` forces React to
;; flush all pending work — including those `forceUpdate`-scheduled
;; re-renders — to the DOM before it returns. This mirrors the stock-Reagent
;; adapter, whose `:flush-render!` runs `(f)` then `reagent.core/flush`
;; (which itself commits via `react-dom/flushSync`). The reagent-slim
;; flush-render DOM proof (`reagent_slim_flush_render_dom_cljs_test`)
;; empirically pins this: without the boundary the post-flush assertion
;; reads the old value.
;;
;; NOT rAF-scheduled ⇒ fires even in a backgrounded / headless tab — the
;; capability the re-frame2-pair MCP's headless dispatch→render→observe-DOM
;; loop depends on (Spec 006 §flush-render! + Spec Tool-Pair §Driving the
;; render). Production-safe: `flushSync` is a stable React DOM API and this
;; fn carries no goog.DEBUG gate (unlike `flush-views!`).

(defn flush-render!
  "Run `f`, then synchronously drain + COMMIT pending render work to the
  DOM. Wraps `(do (f) (batching/flush!))` in `react-dom/flushSync` so the
  forced re-renders commit synchronously under React 19 `createRoot`
  (rf2-0bz5ah). Returns nil.

  Single component pass: `batching/flush!` drains the component queue
  exactly ONCE inside the `flushSync` boundary. `ratom/flush!` fully
  settles the subscription graph before that pass, so the normal
  dispatch→render→observe loop commits with final sub values. But a
  render-INDUCED second-order cascade — a component whose forced re-render
  ENQUEUES a further component (IMPL-SPEC §4.4: a component re-queued during
  a drain is held for the NEXT microtask turn, not flattened into this one)
  — commits on a microtask AFTER `flush-render!` returns. A headless
  Tool-Pair dispatch→render→observe-DOM caller (Spec 006 §flush-render!)
  that can construct such a render-triggers-render cascade must flush a
  SECOND time before observing the tail component's DOM."
  [f]
  (react-dom/flushSync
    (fn []
      (f)
      (batching/flush!)))
  nil)

;; ---------------------------------------------------------------------------
;; Mount entries (Stage 4-D)
;;
;; The mount-side surface per IMPL-SPEC §2.4. `render` walks the user's
;; hiccup `el` once via `reagent2.impl.template/as-element` and hands
;; the resulting React element tree to React's root. React then drives
;; its own concurrent rendering — the rewrite's microtask scheduler
;; covers the Reagent-shape (Form-1/2/3) re-render path; React's
;; reconciler handles everything downstream (children, hooks, etc.).
;;
;; `hydrate-root` is the SSR path: takes a container with pre-rendered
;; HTML and a hiccup tree; calls `react-dom-client/hydrateRoot` so
;; React reconciles against the existing DOM. Hydration mismatches
;; surface as React-19 errors — the rewrite passes them through
;; honestly per IMPL-SPEC §7.6.
;; ---------------------------------------------------------------------------

(defn create-root
  "React 19 root constructor. Wraps `react-dom-client/createRoot`.

  Returns a React 19 root object whose `.render`, `.unmount` methods
  drive subsequent operations. Pass the root to `render` /
  `hydrate-root` to push hiccup into it."
  ([container]
   (react-dom-client/createRoot container))
  ([container options]
   (react-dom-client/createRoot container options)))

(defn render
  "Render hiccup `el` into React `root`.

  Walks `el` via `reagent2.impl.template/as-element` to produce a
  React element tree, then calls `(.render root react-element)`.
  React drives its own concurrent rendering downstream; the
  rewrite's microtask scheduler covers the Reagent-shape
  (Form-1/2/3) re-render path.

  Returns nil — React 19 root.render returns void."
  [^js root el]
  (.render root (template/as-element el))
  nil)

(defn unmount
  "Detach `root`. Wraps `(.unmount root)`. No-op if `root` is nil or
  has no `.unmount` (pre-existing roots from older React versions)."
  [^js root]
  (when (and (some? root)
             (some? (.-unmount root)))
    (.unmount root)))

(defn hydrate-root
  "Hydrate `container` against pre-rendered HTML, producing a React 19
  root. Walks `el` via `reagent2.impl.template/as-element` to produce
  the React element tree React reconciles against the existing DOM.

  Returns the React 19 root so callers can hold a reference for
  later operations (re-renders via `(.render root ...)` or unmounts
  via `unmount`).

  Hydration-mismatch errors surface as React 19 errors (no
  Reagent-side suppression; the rewrite passes React's diagnostics
  through honestly per IMPL-SPEC §7.6)."
  ([container el]
   (react-dom-client/hydrateRoot container (template/as-element el)))
  ([container el options]
   (react-dom-client/hydrateRoot container (template/as-element el) options)))
