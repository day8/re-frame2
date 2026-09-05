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
;; Host commit boundary for the ORDINARY scheduled queue (rf2-cdoo)
;; ---------------------------------------------------------------------------
;;
;; `reagent2.core/after-render` promises to run its callback after the next
;; React commit, and the slim adapter publishes it at `:adapter/after-render`,
;; so `re-frame.interop/after-render` lands on that queue. But
;; `reagent2.impl.batching`'s microtask drain called each dirty component's
;; bare `forceUpdate` and then ran the callbacks immediately — and under React
;; 19 `createRoot` a bare `forceUpdate` from outside React's batching context
;; is SCHEDULED, not committed (the same fact `flush-render!` below documents
;; and relies on). The callback therefore read the OLD DOM. Measured on the
;; real-DOM ordinary path: the callback saw `n=1` after a dispatch to `n=2`.
;;
;; The repair is the seam stock Reagent already uses — the queue asks the HOST
;; to bracket its dirty-component pass — with the direction of the dependency
;; kept as it was: `batching` requires nothing of react-dom, and this ns, which
;; is the DOM host by definition, installs the boundary into it at ns-load.
;; A consumer that never loads this ns (Node, SSR through
;; `reagent2.dom.server`) keeps the bare drain and pulls no react-dom.
;;
;; `batching` applies it only on turns with a pending after-render callback, so
;; an ordinary reactive re-render keeps React 19's concurrent scheduling and is
;; not silently promoted to discrete priority.

(defonce ^:private render-commit-installed?
  (do
    (reset! batching/render-commit
            (fn commit-render [drain] (react-dom/flushSync drain)))
    true))

;; ---------------------------------------------------------------------------
;; flush-views! — test-flush primitive (Stage 4-B)
;; ---------------------------------------------------------------------------
;;
;; Implementation note on `react/act`: `act` is a top-level export of
;; `react` from 18.3 onward, and the repository's React floor is 19
;; (`implementation/package.json` + its lock pin react / react-dom
;; 19.2.0; generated consumers are pinned to the same version by
;; `tools/template/src/day8/re_frame2_template/hooks.clj`, held in
;; lockstep by `version_lockstep_test.clj`). So `(.-act react)` is the
;; ONE lookup on every supported tree, and the pre-18.3
;; `react-dom/test-utils` location is below the floor.
;;
;; The probe stays a probe rather than a direct call because React's
;; PRODUCTION bundle deliberately omits `act`; `flush-views!` degrades to
;; a plain synchronous flush there (see below) rather than calling an
;; absent export.
;;
;; Per IMPL-SPEC §4.2 `flush-views!` is dev-only: gated on `js/goog.DEBUG`
;; so :advanced + `goog.DEBUG=false` DCEs the body entirely.
;; ---------------------------------------------------------------------------

(defn- resolve-act
  "Look up React's `act`. Returns the fn, or nil when the export is absent
  (a missing JS property reads as `nil?` in CLJS, so `flush-views!`'s
  `nil?` branch covers it). Re-resolved on every call (not cached) so a
  test fixture that swaps the React module mid-run sees the swap on the
  next `flush-views!`.

  React-19 floor (rf2-uuzkp, rf2-6r9j.35). `(.-act react)` is the ONE
  lookup: the repository pins react / react-dom 19.2.0 and generated
  consumers with it, so the pre-18.3 `react-dom/test-utils` location is
  below the floor and probing it would buy nothing. A nil result therefore
  means React's PRODUCTION bundle (which omits `act` by design), and
  `flush-views!` degrades to a plain synchronous flush — the documented
  safe behaviour, not a compatibility path.

  The canonical cross-substrate test-flush entry point remains the
  adapter-ns Var `re-frame.adapter.reagent-slim/flush-views!` (surfaced
  identically across substrates per rf2-b6nm5 Decision 6), which routes
  through the spine's `resolve-act-fn`. Reach this substrate-level
  `flush-views!` directly only for its Promise return / Suspense
  ordering."
  []
  (.-act react))

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
  complete) happen by the time `act`'s callback returns. With act()
  unreachable the drain still runs, synchronously and to completion,
  but there is nothing to await and the return is nil.

  NOT the canonical cross-substrate hook. The adapter-ns Var
  `re-frame.adapter.reagent-slim/flush-views!` is the converged
  nil-returning contract (rf2-b6nm5, Decision 6), surfaced under the
  same name from every adapter ns and routed through the spine. Reach
  THIS one only for the Promise return / deterministic Suspense
  ordering.

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
