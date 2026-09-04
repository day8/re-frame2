# Reagent adapter

Maven artefact: `day8/re-frame2-reagent`. Target: Reagent 2.x on React 19. Public ns: `re-frame.adapter.reagent`.

This is the stock-Reagent adapter — a first-class, actively-supported view substrate for re-frame2, and the canonical adapter the reference test suite runs against. (Hicasso — re-frame2's own native view layer, [EP-0038](../../../docs/EP/EP-0038-the-hicasso-view-layer-programme.md) — sits alongside the adapter tier, not as a replacement for it.) It implements the contract from [Spec 006 — Reactive substrate](../../../spec/006-ReactiveSubstrate.md) on top of Reagent's RAtom/Reaction graph and React commit lifecycle.

See [`../README.md`](../README.md) for the wider adapter tier and the substrate contract; [Spec 002 — Frames §What `reg-view` injects](../../../spec/002-Frames.md#what-reg-view-injects) for `reg-view` / `reg-view*` semantics.

## Floor: Reagent 2.x + React 19

The bridge supports exactly one combination: Reagent 2.x (currently pinned to `2.0.1` in [this adapter's own `deps.edn`](deps.edn)) running against React 19. This artefact is the sole owner of the stock `reagent/reagent` coordinate — [`implementation/core/deps.edn`](../../core/deps.edn) deliberately carries no stock-Reagent dep, so a UIx or `reagent-slim` consumer gets a Reagent-free core classpath. There is no Reagent 1.x compat path and no React 17/18 fallback.

Rationale, mirroring the `reagent-slim` `DECISION-5` framing ([`../reagent-slim/IMPL-SPEC.md`](../reagent-slim/IMPL-SPEC.md) §Hard constraints, Stage 1 §2.3a):

- Reagent 1.x is end-of-life for re-frame2. Reagent 2.0 (released 29 October 2025) added React 19 support, dropped the deprecated `reagent.dom/dom-node`, and removed the second arity of `reagent.core/force-update`. Re-frame2 targets the modern Reagent surface; carrying a 1.x compat layer would freeze design choices that pre-date concurrent rendering.
- React 19 is the React floor. The slim rewrite (`day8/reagent-slim`) sets a React 19 floor as a hard DECISION; the bridge follows the same floor so that adopters can move between bridge and slim without changing their `package.json`. Reagent 2.0.1's own `devDependencies` pin `react`/`react-dom` to `19.2.0`, so this is the version Reagent itself is tested against.
- No back-compat shims. Per pre-alpha posture: there is no v1-Reagent compat, no React-18 fallback, no `reagent.dom`-legacy-mount support. Apps that need any of those stay on re-frame v1.

The migration story for React-19-removed Reagent surfaces (`reagent.dom/render`, `reagent.dom/dom-node` and so on) lives in [`migration/from-re-frame-v1/README.md#m-42-react-19-removed-reagent-surfaces-are-absent-under-day8reagent-slim-compile-time-unresolved-var`](../../../migration/from-re-frame-v1/README.md) — that section is the canonical reference for both bridge and slim consumers because the removal is React's, not the rewrite's.

`dom-node` is the one that catches bridge adopters out: the pinned stock floor (Reagent `2.0.1`) has **already** deleted `reagent.dom/dom-node`, so a v1 call site — historically written `(rdom/dom-node this)` against `[reagent.dom :as rdom]`, never `reagent.core/dom-node`, which never existed — fails to compile on the bridge, not only on slim. Sweep it before the first build, on either target.

## Imperative escape hatch — when you need a DOM lifecycle

Most views are pure render functions — Form-1 with `reg-view` covers the canonical case. A small fraction of views genuinely need to own a piece of host DOM lifecycle:

- library bridges — Framer Motion, GSAP, D3 transitions, AmCharts, Vega-Embed, Mapbox, ag-grid, CodeMirror, SpreadJS — anything imperative that needs a DOM element handle plus mount / update / unmount hooks
- DOM-listener-bearing widgets — `addEventListener` for `animationend`, `transitionend`, `resize`, `intersectionobserver`, `mutationobserver`, custom protocols
- error boundaries — `componentDidCatch` is React's class-component-only contract
- pre-commit DOM measurement — scroll-position restoration via `getSnapshotBeforeUpdate`

The escape hatch is Form-3 via [`reagent.core/create-class`](https://reagent-project.github.io/), registered through `re-frame.core/reg-view*` (the plain-fn surface — the `reg-view` macro rejects Form-3 bodies at compile time; [`spec/API.md`](../../../spec/API.md) names `create-class` bodies as a `reg-view*` use).

### Spelling

```clojure
(reagent.core/create-class
  {:display-name           "<name>"
   :reagent-render         (fn [props] ...)
   :component-did-mount    (fn [this] ...)
   :component-did-update   (fn [this prev-props prev-state snapshot] ...)
   :component-will-unmount (fn [this] ...)})
```

`(reagent.core/argv this)` returns `[component-fn & user-args]` inside the lifecycle callbacks — destructure to get the current props.

### Outer / inner pattern

Compose Form-3 with the standard outer/inner split: the outer view is Form-1 (registered via `reg-view`), reads subscriptions, and produces props; the inner view is a Form-3 class component that owns the library's lifecycle. Its `reg-view*` callable is a one-shot per-mounted-instance factory: capture the immutable frame handle and allocate instance state there, then return `create-class`. The outer recomputes whenever subs change and feeds new props into the inner; the inner's `:component-did-update` reacts imperatively.

```clojure
(ns my-app.charts
  (:require [re-frame.core :as rf]
            [reagent.core :as r]))

;; Inner — Form-3, owns the library lifecycle. Registered via reg-view*.
(rf/reg-view* :my-app.charts/vega-inner
  (fn [_initial-spec]
    (let [el-ref        (atom nil)
          vega-instance (atom nil)
          {:keys [frame dispatch]} (rf/capture-frame)] ; captured once by this mount's factory
      (r/create-class
        {:display-name "vega-inner"

         :component-did-mount
         (fn [this]
           (let [[_ spec] (r/argv this)]
             (-> (js/vegaEmbed @el-ref (clj->js spec))
                 (.then (fn [result] (reset! vega-instance (.-view result)))))))

         :component-did-update
         (fn [this _ _ _]
           (let [[_ new-spec] (r/argv this)]
             (some-> @vega-instance .finalize)
             (-> (js/vegaEmbed @el-ref (clj->js new-spec))
                 (.then (fn [result] (reset! vega-instance (.-view result)))))))

         :component-will-unmount
         (fn [_this]
           (some-> @vega-instance .finalize)
           (reset! vega-instance nil)
           (reset! el-ref nil))

         :reagent-render
         (fn [_spec]
           [:div {:ref (fn [el] (reset! el-ref el))}])}))))

;; Outer — Form-1, reads subs, hands props to the inner. Registered via reg-view.
(rf/reg-view chart-panel []
  (let [spec @(subscribe [:dashboard/current-spec])]
    [(rf/view :my-app.charts/vega-inner) spec]))
```

6 things matter:

- The inner owns instance state in closure atoms. `el-ref` and `vega-instance` are per-mount; don't use top-level `def` or `defonce` here — those leak across mounts.
- Cleanup is mandatory. `:component-will-unmount` releases the library instance and event listeners. Without it, every navigation that unmounts the chart leaks the library's internal state, listeners, and tile/data caches. Cleanup that *fails* is audible rather than silent: when a teardown hook throws during `rf/destroy-adapter!`, the adapter still attempts every remaining Reaction and root and clears its ownership, and then rethrows that first failure to the caller — so a test fixture or hot-reload cycle sees the error instead of a clean `nil` over a library instance that never released. Later failures in the same teardown arrive attached to it as `rfAdapterTeardownSecondaryErrors`. See [Spec 006 §Adapter disposal lifecycle](../../../spec/006-ReactiveSubstrate.md#adapter-disposal-lifecycle).
- Capture once in the registered outer callable, before `create-class`. Do not recapture or replace the handle in `:reagent-render` or a lifecycle callback. The callable runs once for each mounted instance under the registered view's frame scope. Lifecycle callbacks fire after that scope has unwound, but the captured operations remain locked to the mount's frame.
- Lifecycle reads and teardown name the captured frame explicitly. A bare call in a hook raises `:rf.error/no-frame-context`. For a one-shot current value use `(rf/subscribe-once query-v {:frame frame})`. Ordinary reactive values should come from the registered Form-1 outer and arrive as props. If the Form-3 renderer itself must retain a captured reaction, acquire it with `r/with-let` inside `:reagent-render`, deref it there, and release it from `with-let`'s `finally` — not from the class's `:component-will-unmount`. Stock Reagent deliberately preserves that render owner across React StrictMode's transient will-unmount/did-mount replay. A genuinely imperative hook-owned subscription is different: acquire it in `:component-did-mount` through captured `subscribe`, own it in that same hook with a per-mount `(r/track! …)`, and tear both down in `:component-will-unmount` — `r/dispose!` the tracker first, then `(rf/unsubscribe frame query-v)`; the acquire/release pair then balances on every replay as well as on a real unmount. The tracker is mandatory, not decoration. A subscription on this adapter is a `reagent.ratom/Reaction` built without `:auto-run`, and a Reaction learns its sources only through `deref-capture`; a deref taken in a lifecycle hook runs the body raw and leaves the node watching nothing, so it is in no watcher set and cannot be notified. `add-watch` on such a reaction is therefore a trap — the watch is registered and can never fire, and the widget is fed once at mount and deaf thereafter. `r/track!` supplies the missing ownership: its eager first run is both the seed and the `deref-capture`, and re-runs land on the next Reagent flush. Each mount owns its own tracker, so 2 instances observing one shared cached reaction stay independent with no watch keys at all.
- A captured handle is invariant for the mount. If a surrounding provider retargets from frame A to B, key the Form-3 child by frame so React invokes A's `:component-will-unmount` before mounting a fresh factory that captures B. Stock Reagent may finish its render-owner cleanup in the following microtask, but that cleanup remains locked to A and cannot act through B. Never mutate A's closed-over handle into B.
- A top-level singleton class is only for frame-independent components. If an existing singleton reads, dispatches, subscribes, or tears down against a frame, rewrite it as the per-mount outer callable above so sibling instances do not share frame capture or mutable lifecycle state.

### Cross-references

- [`spec/API.md`](../../../spec/API.md) `reg-view*` — the plain-fn surface (no auto-def, no auto-inject, no compile check), listed there for exactly this Form-3 / `create-class` case.
- Views MUST NOT attach native DOM event listeners from render bodies, and MUST NOT own imperative library lifecycles directly — bare `addEventListener` in a render body leaks listeners, and a later ambient dispatch with no carried frame raises `:rf.error/no-frame-context`; library lifecycles belong in Form-3.
- [Spec 002 §Dispatches issued from inside a handler body](../../../spec/002-Frames.md#dispatches-issued-from-inside-a-handler-body) — async callbacks escape the dynamic frame binding; capture the frame handle once in the registered outer callable to carry it into the class lifecycle.
- Outer/inner Pattern (Pattern-OuterInner) — the canonical home for wrapping stateful JS components (D3, Mapbox, animation libraries); the worked example above is one instance.
