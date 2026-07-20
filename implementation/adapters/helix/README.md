# Helix adapter

Maven artefact: `day8/re-frame2-helix`. Target: Helix 0.2.x. Public ns: `re-frame.adapter.helix`.

This adapter implements re-frame2's substrate contract on top of Helix — Will Acton's minimal CLJS React wrapper. Subscriptions are read via the `use-subscribe` hook (returning a plain value, not a reaction); frame context is composed via React context, sharing the same `createContext` object as the Reagent and UIx adapters so frame-provider chains compose across substrates in mixed-substrate apps.

See [`../README.md`](../README.md) for the wider adapter tier and the substrate contract; [Spec 004 — Views](../../../spec/004-Views.md) for `reg-view` / `reg-view*` semantics; [Spec 006 — Reactive substrate](../../../spec/006-ReactiveSubstrate.md) for the contract this adapter implements.

## Adapter-specific surface

- `re-frame.adapter.helix/use-subscribe` — Helix hook returning the current value of a subscription; re-renders the calling component when the value changes. Resolves the frame from the surrounding `frame-provider` via React context. Override via the 2-arg form to pin to an explicit frame-id.
- `re-frame.adapter.helix/use-frame` — Helix hook returning the frame api for the ambient frame — the frame-locked ops map `{:frame :dispatch :dispatch-sync :subscribe}`, exactly what `(rf/capture-frame)` returns, in hook position. Resolves the surrounding `frame-provider` / `frame-root` via React context; a bare no-arg `(rf/capture-frame)` reads only the dynamic-var tier and raises `:rf.error/no-frame-context` under a context-provided frame. Pull `:dispatch` off it during render and close over it in callbacks. The returned map is reference-stable across re-renders for the same resolved frame (safe in effect deps).
- `re-frame.adapter.helix/frame-provider` — the **SCOPE-only** component (rf2-nyea0r split — *roots ensure; providers scope*). `{:frame existing-id}` provides an already-created frame's id to descendants via React context, creating / refreshing / destroying nothing (the scope-into-React counterpart to `rf/with-frame`); fails loud if the frame is absent; given an `:id` (the ENSURE key) it fails loud naming `frame-root`.
- `re-frame.adapter.helix/frame-root` — the **ENSURE** component, a commit-owned two-pass boundary. `{:id the-id}` (plus `:images` / `:initial-events` / record-config) creates the frame if absent in a client `useLayoutEffect` (a render React discards creates nothing), reuses it without re-seeding if present, and provides its id to descendants. No destroy-on-unmount.
- `re-frame.adapter.helix/flush-views!` — test helper; wraps React's `act()` to flush pending renders synchronously.
- `re-frame.adapter.helix/use-current-frame` — Helix hook returning the current frame keyword from the surrounding React context (the narrow raw `useContext` read of the one shared context both boundaries install), or the no-provider sentinel (`:rf.frame/no-provider`) when neither `frame-provider` (SCOPE) nor `frame-root` (ENSURE) sits above. For the full resolution chain, prefer `(rf/current-frame-id)`.
- `re-frame.adapter.helix/set-hiccup-emitter!` — installs a render-tree → HTML fn for `render-to-string`; idempotent. SSR consumers call this to wire the hiccup emitter explicitly.
- `re-frame.adapter.helix/wrap-view` — wraps a Helix-shape user component to inject the `data-rf2-source-coord` attribute on the rendered root DOM element when debug is enabled; elided in production builds.

## Imperative escape hatch — when you need a DOM lifecycle

Most views are pure render functions — `defnc` reads subs via `use-subscribe`, returns hiccup-shaped data, done. A small fraction of views genuinely need to own a piece of host DOM lifecycle:

- **Library bridges** — Framer Motion, GSAP, React-Spring, D3 transitions, AmCharts, Vega-Embed, Mapbox, ag-grid, CodeMirror — anything imperative that needs a DOM element handle plus mount / update / unmount hooks.
- **DOM-listener-bearing widgets** — `addEventListener` for `animationend`, `transitionend`, `resize`, `intersectionobserver`, `mutationobserver`, custom DOM protocols.
- **Subscribing to non-re-frame data sources** — websocket-driven UI state, browser APIs (`matchMedia`, `geolocation`, `online`/`offline`), third-party state stores.

The escape hatch is **`helix.hooks/use-effect`** — Helix's React `useEffect` wrapper. `use-effect` runs after commit; its body can attach imperative state to the DOM and return a cleanup function that runs on unmount / before the next effect run.

### Spelling

```clojure
(helix.hooks/use-effect
  [dep1 dep2 ...]    ;; deps come first in Helix's macro form
  ;; body — runs after commit; DOM is mounted, refs are populated
  ;; ... imperative attach ...
  (fn cleanup []
    ;; runs on unmount, and before the next effect run when deps change
    ;; ... imperative detach ...))
```

Helix's `use-effect` is a macro whose first argument is the deps vector and whose body is the effect (the inverse of UIx's fn-first / deps-last shape). The cleanup function is the last expression in the body.

### Outer / inner pattern

Compose `use-effect` with the standard outer/inner split: the **outer** `defnc` reads subs via `use-subscribe` and produces props; the **inner** `defnc` owns the library lifecycle via `use-effect`. Pull `dispatch` from the **`use-frame` hook** in a `let` above the `use-effect` call so it carries the surrounding frame into the effect body.

```clojure
(ns my-app.tiles
  (:require [re-frame.core :as rf]
            [re-frame.adapter.helix :as helix-adapter]
            [helix.core :as hx :refer [defnc $]]
            [helix.hooks :as hooks]
            [helix.dom :as d]))

;; Inner — owns the imperative lifecycle. Plain Helix defnc.
(defnc tile-inner [{:keys [tile-id]}]
  (let [ref      (hooks/use-ref nil)
        {:keys [dispatch]} (helix-adapter/use-frame)]   ;; frame api via hook — reads the surrounding provider
    (hooks/use-effect
      [tile-id]
      (let [el       (.-current ref)
            listener (fn [_evt] (dispatch [:tile/finished-merging tile-id]))]
        (.addEventListener el "animationend" listener)
        (fn cleanup []
          (.removeEventListener el "animationend" listener))))
    (d/div {:ref ref :class "tile merging"})))

;; Outer — reads subs, hands props to the inner. Plain Helix defnc.
(defnc board-panel []
  (let [active-tile-id (helix-adapter/use-subscribe [:board/active-tile])]
    ($ tile-inner {:tile-id active-tile-id})))
```

Four things matter:

1. **The frame api comes from the `use-frame` hook**, called at the top of the component body — never a bare `(rf/capture-frame)`. `use-frame` reads the surrounding `frame-provider` / `frame-root` through React context; a no-arg `(rf/capture-frame)` in a plain hooks component reads only the dynamic-var tier and raises `:rf.error/no-frame-context` under a context-provided frame. The `dispatch` you pull off it closes over the resolved frame at render-time, so the effect body — which fires after commit on a frameless stack — still routes to the right frame.
2. **The cleanup fn is mandatory.** Without it, the listener leaks across re-mounts and across hot-reloads. The cleanup runs on unmount and before each re-run when deps change.
3. **The deps vector matters.** Include every prop the effect reads so React re-runs the effect when those props change. An empty deps vector means "run once on mount, clean up on unmount."
4. **Don't call `use-subscribe` inside the effect body.** Hooks must be called at the top of the component body, not inside another hook's callback. Subscribe in the outer (or in the inner's top-level `let`) and pass the value as a dep.

### Cross-references

- [Spec 004 §Views MUST NOT attach native DOM event listeners from render bodies](../../../spec/004-Views.md#effects--the-view-side-surface) and [§Views MUST NOT own imperative library lifecycles directly](../../../spec/004-Views.md#effects--the-view-side-surface) — bare `addEventListener` in a render body leaks listeners and silently routes dispatches to `:rf/default`; library lifecycles belong in `use-effect`.
- [Spec 002 §Dispatches issued from inside a handler body](../../../spec/002-Frames.md#dispatches-issued-from-inside-a-handler-body) — async callbacks escape the dynamic frame binding; call the `use-frame` hook at render-time (capture-frame in hook position) to carry the frame into the callback.
- **Outer/inner Pattern (Pattern-OuterInner)** — the canonical home for wrapping stateful JS components (D3, Mapbox, animation libraries); the worked example above is one instance.
- [Helix 0.2.x docs — `use-effect`](https://github.com/lilactown/helix/blob/master/docs/creating-components.md) — the underlying hook's signature, deps-vector semantics, and stale-closure considerations.
