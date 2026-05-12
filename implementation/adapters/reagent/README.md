# Reagent adapter

Maven artefact: `day8/re-frame2-reagent`. Target: Reagent 2.x. Public ns: `re-frame.adapter.reagent`.

This is the canonical CLJS reference adapter — the substrate `reg-view` was designed against. It implements the contract from [Spec 006 — Reactive substrate](../../../spec/006-ReactiveSubstrate.md) on top of Reagent's RAtom/Reaction graph and React commit lifecycle.

See [`../README.md`](../README.md) for the wider adapter tier and the substrate contract; [Spec 004 — Views](../../../spec/004-Views.md) for `reg-view` / `reg-view*` semantics.

## Imperative escape hatch — when you need a DOM lifecycle

Most views are pure render functions — Form-1 with `reg-view` covers the canonical case. A small fraction of views genuinely need to own a piece of host DOM lifecycle:

- **Library bridges** — Framer Motion, GSAP, D3 transitions, AmCharts, Vega-Embed, Mapbox, ag-grid, CodeMirror, SpreadJS — anything imperative that needs a DOM element handle plus mount / update / unmount hooks.
- **DOM-listener-bearing widgets** — `addEventListener` for `animationend`, `transitionend`, `resize`, `intersectionobserver`, `mutationobserver`, custom protocols.
- **Error boundaries** — `componentDidCatch` is React's class-component-only contract.
- **Pre-commit DOM measurement** — scroll-position restoration via `getSnapshotBeforeUpdate`.

The escape hatch is **Form-3** via [`reagent.core/create-class`](https://reagent-project.github.io/), registered through `re-frame.core/reg-view*` (the plain-fn surface — the `reg-view` macro rejects Form-3 bodies at compile time per [Spec 004 §Form-3](../../../spec/004-Views.md#form-3-class--out-of-scope-for-the-macro)).

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

Compose Form-3 with the standard outer/inner split: the **outer** view is Form-1 (registered via `reg-view`), reads subscriptions, and produces props; the **inner** view is the Form-3 class component that owns the library's lifecycle. The outer recomputes whenever subs change and feeds new props into the inner; the inner's `:component-did-update` reacts imperatively.

```clojure
(ns my-app.charts
  (:require [re-frame.core :as rf]
            [reagent.core :as r]))

;; Inner — Form-3, owns the library lifecycle. Registered via reg-view*.
(rf/reg-view* :my-app.charts/vega-inner
  (fn [_initial-spec]
    (let [el-ref        (atom nil)
          vega-instance (atom nil)
          dispatch      (rf/dispatcher)]   ;; captured at render — carries the frame
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

Four things matter:

1. **The inner owns instance state in closure atoms.** `el-ref` and `vega-instance` are per-mount; don't use top-level `def` or `defonce` here — those leak across mounts.
2. **Cleanup is mandatory.** `:component-will-unmount` releases the library instance and event listeners. Without it, every navigation that unmounts the chart leaks the library's internal state, listeners, and tile/data caches.
3. **`(rf/dispatcher)` is captured during render**, not inside the lifecycle callback. The dispatcher carries the surrounding frame; the lifecycle callback fires after commit but the closure is established at render-time, so dispatches from the callback resolve to the right frame.
4. **Subscriptions live in the outer, not in the lifecycle callbacks.** The inner's `:reagent-render` can deref subs too, but reactive context is undefined inside `:component-did-mount` / `:component-did-update` / `:component-will-unmount` — don't `@(subscribe …)` there.

### Cross-references

- [Spec 004 §Form-3 (class — out of scope for the macro)](../../../spec/004-Views.md#form-3-class--out-of-scope-for-the-macro) — why Form-3 ships through `reg-view*` rather than the macro.
- [Spec 004 §Views MUST NOT attach native DOM event listeners from render bodies](../../../spec/004-Views.md#views-must-not-attach-native-dom-event-listeners-from-render-bodies) and [§Views MUST NOT own imperative library lifecycles directly](../../../spec/004-Views.md#views-must-not-own-imperative-library-lifecycles-directly) — bare `addEventListener` in a render body leaks listeners and silently routes dispatches to `:rf/default`; library lifecycles belong in Form-3.
- [Spec 002 §Dispatches issued from inside a handler body](../../../spec/002-Frames.md#dispatches-issued-from-inside-a-handler-body) — async callbacks escape the dynamic frame binding; capture `(rf/dispatcher)` at render-time to carry the frame.
- **Outer/inner Pattern (Pattern-OuterInner)** — the canonical home for wrapping stateful JS components (D3, Mapbox, animation libraries); the worked example above is one instance.
