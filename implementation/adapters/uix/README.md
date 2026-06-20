# UIx adapter

Maven artefact: `day8/re-frame2-uix`. Target: UIx 2.x (hooks-based). Public ns: `re-frame.adapter.uix`.

> **UIx product version vs Maven coordinate version.** "UIx 2.x" is the *product/API-family* name — the hooks-based generation hosted at [pitch-io/uix](https://github.com/pitch-io/uix). It is **not** a Maven version number. UIx 2 is published on Clojars as `com.pitch/uix.core` with version numbers in the **1.x.x** series, so the `com.pitch/uix.core {:mvn/version "1.4.4"}` pin in [`deps.edn`](deps.edn) *is* UIx 2.x. The legacy UIx 1 generation (roman01la/uix) is a separate, pre-hooks codebase and is explicitly out of scope. There is no `2.x.y` Maven coordinate to bump to.

This adapter implements re-frame2's substrate contract on top of UIx — Pitch's modern, hooks-based CLJS React wrapper. Subscriptions are read via the `use-subscribe` hook (returning a plain value, not a reaction); frame context is composed via React context.

See [`../README.md`](../README.md) for the wider adapter tier and the substrate contract; [Spec 004 — Views](../../../spec/004-Views.md) for `reg-view` / `reg-view*` semantics; [Spec 006 — Reactive substrate](../../../spec/006-ReactiveSubstrate.md) for the contract this adapter implements.

## Adapter-specific surface

- `re-frame.adapter.uix/use-subscribe` — UIx hook returning the current value of a subscription; re-renders the calling component when the value changes. Resolves the frame from the surrounding frame provider (`frame-provider` or `frame-provider-existing`) via React context. Override via the 2-arg form to pin to an explicit frame-id.
- `re-frame.adapter.uix/frame-provider` — **owned** frame lifecycle boundary: takes `:id` (plus `:images` / `:initial-db` / record-config), creates the frame on mount, provides its id to descendants, and destroys it on unmount.
- `re-frame.adapter.uix/frame-provider-existing` — **scope-only** provider: takes `:frame` (an already-created frame's id) and provides it to descendants via React context — creates, refreshes, and destroys nothing. Use this to scope a tree to a frame owned elsewhere (the scope-into-React counterpart to `rf/with-frame`).
- `re-frame.adapter.uix/flush-views!` — test helper; wraps React's `act()` to flush pending renders synchronously.
- `re-frame.adapter.uix/use-current-frame` — UIx hook returning the current frame keyword from the surrounding React context (the narrow raw `useContext` read), or the no-provider sentinel when no frame provider (`frame-provider` or `frame-provider-existing`) sits above. For the full resolution chain, prefer `(rf/current-frame-id)`.
- `re-frame.adapter.uix/set-hiccup-emitter!` — installs a render-tree → HTML fn for `render-to-string`; idempotent. SSR consumers call this to wire the hiccup emitter explicitly.
- `re-frame.adapter.uix/wrap-view` — wraps a UIx-shape user component to inject the `data-rf2-source-coord` attribute on the rendered root DOM element when debug is enabled; elided in production builds.

## Imperative escape hatch — when you need a DOM lifecycle

Most views are pure render functions — `defui` reads subs via `use-subscribe`, returns hiccup, done. A small fraction of views genuinely need to own a piece of host DOM lifecycle:

- **Library bridges** — Framer Motion, GSAP, React-Spring, D3 transitions, AmCharts, Vega-Embed, Mapbox, ag-grid, CodeMirror — anything imperative that needs a DOM element handle plus mount / update / unmount hooks.
- **DOM-listener-bearing widgets** — `addEventListener` for `animationend`, `transitionend`, `resize`, `intersectionobserver`, `mutationobserver`, custom DOM protocols.
- **Subscribing to non-re-frame data sources** — websocket-driven UI state, browser APIs (`matchMedia`, `geolocation`, `online`/`offline`), third-party state stores.

The escape hatch is **`uix.core/use-effect`** — UIx's React `useEffect` wrapper. `use-effect` runs after commit; its body can attach imperative state to the DOM and return a cleanup function that runs on unmount / before the next effect run.

### Spelling

```clojure
(uix.core/use-effect
  (fn []
    ;; runs after commit — DOM is mounted, refs are populated
    ;; ... imperative attach ...
    (fn cleanup []
      ;; runs on unmount, and before the next effect run when deps change
      ;; ... imperative detach ...))
  [dep1 dep2 ...])   ;; dependency vector — re-runs the effect when these change
```

### Outer / inner pattern

Compose `use-effect` with the standard outer/inner split: the **outer** `defui` reads subs via `use-subscribe` and produces props; the **inner** `defui` owns the library lifecycle via `use-effect`. Capture `(:dispatch (rf/frame-handle))` in a `let` above the `use-effect` call so the dispatcher carries the surrounding frame into the effect body.

```clojure
(ns my-app.tiles
  (:require [re-frame.core :as rf]
            [uix.core :as uix :refer [defui $]]))

;; Inner — owns the imperative lifecycle. Plain UIx defui.
(defui tile-inner [{:keys [tile-id]}]
  (let [ref      (uix/use-ref)
        dispatch (:dispatch (rf/frame-handle))]   ;; captured at render — carries the frame
    (uix/use-effect
      (fn []
        (let [el       (.-current ref)
              listener (fn [_evt] (dispatch [:tile/finished-merging tile-id]))]
          (.addEventListener el "animationend" listener)
          (fn cleanup []
            (.removeEventListener el "animationend" listener))))
      [tile-id])
    ($ :div {:ref ref :class "tile merging"})))

;; Outer — reads subs, hands props to the inner. Registered via reg-view.
(rf/reg-view board-panel []
  (let [active-tile-id (re-frame.adapter.uix/use-subscribe [:board/active-tile])]
    ($ tile-inner {:tile-id active-tile-id})))
```

Four things matter:

1. **`(:dispatch (rf/frame-handle))` is captured in the `let` above `use-effect`**, not inside the effect body. The dispatcher closes over the frame at render-time; the effect body fires after commit but the closure is already established. Inside the effect body, `dispatch` carries the right frame.
2. **The cleanup fn is mandatory.** Without it, the listener leaks across re-mounts and across hot-reloads. The cleanup runs on unmount and before each re-run when deps change.
3. **The deps vector matters.** Include every prop the effect reads so React re-runs the effect when those props change. An empty deps vector means "run once on mount, clean up on unmount."
4. **Don't call `use-subscribe` inside the effect body.** Hooks must be called at the top of the component body, not inside another hook's callback. Subscribe in the outer (or in the inner's top-level `let`) and pass the value as a dep.

### Cross-references

- [Spec 004 §Views MUST NOT attach native DOM event listeners from render bodies](../../../spec/004-Views.md#views-must-not-attach-native-dom-event-listeners-from-render-bodies) and [§Views MUST NOT own imperative library lifecycles directly](../../../spec/004-Views.md#views-must-not-own-imperative-library-lifecycles-directly) — bare `addEventListener` in a render body leaks listeners and silently routes dispatches to `:rf/default`; library lifecycles belong in `use-effect`.
- [Spec 002 §Dispatches issued from inside a handler body](../../../spec/002-Frames.md#dispatches-issued-from-inside-a-handler-body) — async callbacks escape the dynamic frame binding; capture `(:dispatch (rf/frame-handle))` at render-time to carry the frame.
- **Outer/inner Pattern (Pattern-OuterInner)** — the canonical home for wrapping stateful JS components (D3, Mapbox, animation libraries); the worked example above is one instance.
- [UIx 2.x docs — `use-effect`](https://github.com/pitch-io/uix) — the underlying hook's signature, deps-vector semantics, and stale-closure considerations.
