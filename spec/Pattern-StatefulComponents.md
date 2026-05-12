# Pattern — Stateful Components

> **Type:** Pattern
> The canonical outer/inner wrapping shape for views that bridge a stateful third-party JavaScript component (D3, Mapbox, CodeMirror, Three.js, GSAP, Framer Motion, ag-grid, Vega-Embed, AmCharts, SpreadJS, …). Convention, not Spec.

> **Code samples are in ClojureScript** (the CLJS reference). The pattern itself is host-agnostic where the host has a component-lifecycle equivalent; on the JVM there is no DOM to bridge, so the pattern is browser-side.

## Role

A **named pattern**, not a Spec. Re-frame2's view substrate ([Spec 004 — Views](004-Views.md)) is built around pure render functions that compute hiccup from state. A small but unavoidable fraction of real-world views need to wrap a third-party JS library that **owns its own DOM** and exposes an imperative `init / update / dispose` lifecycle.

This doc names the wrapping shape — **outer/inner split** — so feature code, adapter READMEs, and the [Animations Regime C](004-Views.md#regime-c--library-bridged-animations-framer-motion-react-spring-gsap-autoanimate) discussion cite a single canonical description rather than re-deriving the rationale per library.

The runtime contract for the lifecycle hooks themselves is owned per-adapter ([§Per-adapter spelling](#per-adapter-spelling) below); this doc owns the **shape** that composes those hooks with the framework's reactive flow.

## Why the pattern exists

Third-party JS components — D3 charts, Mapbox/Leaflet maps, CodeMirror / Monaco editors, Three.js scenes, animation libraries — share three properties that put them in tension with re-frame2's view layer:

- **They own a DOM subtree.** They build, mutate, and tear down their own elements. The view layer cannot describe what they render with hiccup; it can only declare a mount point and hand them props.
- **They have an imperative lifecycle.** "Initialise against this DOM node," "you have new data, please re-draw," "you're going away, please clean up." None of those phases is a pure function of props.
- **They register their own listeners and timers.** Map pans, chart hovers, editor selection-change events, animation completions — all attached on the library's side, all needing teardown on unmount or they leak.

A re-frame2 view body, on the other hand, **MUST NOT** dispatch from render, **MUST NOT** `addEventListener` from render, and **MUST NOT** own an imperative library lifecycle directly (per [Spec 004 §View antipatterns](004-Views.md#view-antipatterns)). The render body computes hiccup; the work that violates "pure render" lives somewhere else.

The pattern in this doc is *where else*.

## The outer/inner pattern

Two views, composed:

```
  outer (registered view)
   │   reads subs, derives props
   ▼
  inner (Form-3 / use-effect view)
   │   owns the library lifecycle: mount / update / unmount
   ▼
  library (owns its DOM subtree, listeners, internal timers)
```

### Outer — pure re-frame2 view

A standard registered view (`reg-view`). Its job is **derivation**:

- Reads subscriptions for the data the library needs to render.
- Computes a single, JSON-shaped props map describing what the library should show.
- Renders an inner component with those props.

Nothing more. The outer is pure render; it never touches the DOM; it never holds an instance handle. When subs change, the outer re-renders, and re-frame2's reactive substrate feeds the inner a new props map.

### Inner — Form-3-equivalent lifecycle wrapper

The inner view is **not** a Form-1. It is whatever the active adapter exposes as its Form-3 equivalent — a Reagent `create-class` for Reagent / Reagent-slim, a `use-effect` body for UIx / Helix. The inner owns three lifecycle phases:

1. **Mount** — after first commit, the DOM mount point exists. Read it via a ref, hand it to the library's constructor, stash the resulting instance handle in a per-mount closure cell (a plain `atom` for Reagent; a `use-ref` for hooks-based adapters). Apply the initial props.
2. **Update** — when props change, the library's instance is **already mounted**; it is not torn down and re-created. Push the new props into the instance via whatever imperative API the library exposes (`.setData`, `.setView`, `.setOptions`, `.update`, `.panTo`, …).
3. **Unmount** — release the instance handle. Call the library's dispose / destroy API if it has one; remove any listeners the library was unable to clean up itself; null out the closure cells so the GC can reclaim them.

The inner's render body itself is **trivial** — usually just `[:div {:ref …}]` (or the substrate-equivalent), describing the mount point but no content. The library fills that node; React/Reagent must see consistent hiccup across renders so the substrate doesn't tear the mount node out from under the library.

### Why split outer/inner

The split is forced by **reactive context**:

- Subscriptions are reactive — they want to be read at render time, from a view that the substrate can re-render when the value changes.
- The library lifecycle is imperative — `:component-did-mount`, `:component-did-update`, `use-effect` bodies all run **after commit**, on a stack with no reactive context. Reading subs from inside the lifecycle callback is undefined behaviour on every adapter ([Reagent README §95](../implementation/adapters/reagent/README.md), [UIx README §72](../implementation/adapters/uix/README.md), [Helix README §74](../implementation/adapters/helix/README.md)).

The outer handles the reactive read; the inner handles the imperative lifecycle. Props are the seam.

## Per-adapter spelling

The shape is identical across adapters; only the lifecycle-hook surface differs. Cross-link to each adapter's README for the exact API surface and per-adapter worked example.

| Adapter | Inner lifecycle surface | Registration | Reference |
|---|---|---|---|
| **Reagent** | `reagent.core/create-class` Form-3 (`:reagent-render` + `:component-did-mount` + `:component-did-update` + `:component-will-unmount`) | `reg-view*` (the plain-fn surface — the `reg-view` macro rejects Form-3 bodies per [Spec 004 §Form-3](004-Views.md#form-3-class--out-of-scope-for-the-macro)) | [Reagent adapter README](../implementation/adapters/reagent/README.md) |
| **Reagent-slim** | `reagent2.core/create-class` Form-3, **7-key cap** (the six lifecycle keys plus `:display-name`) | `reg-view*` | [Reagent-slim adapter README](../implementation/adapters/reagent-slim/README.md) and [`FORM-3.md`](../implementation/adapters/reagent-slim/FORM-3.md) — the slim adapter's single source of truth for Form-3 |
| **UIx** | `uix.core/use-effect` inside a `defui`, with a deps vector listing every prop the effect reads. Cleanup is the fn the effect body returns | `reg-view` (UIx components are plain fns) | [UIx adapter README](../implementation/adapters/uix/README.md) |
| **Helix** | `helix.hooks/use-effect` inside a `defnc`. Deps vector comes **first** in Helix's macro form; the body is the effect; cleanup is the last expression | `reg-view` (Helix components are plain fns) | [Helix adapter README](../implementation/adapters/helix/README.md) |

The three things that are identical across adapters:

- **Mount runs after commit.** The DOM node exists when the hook fires; refs are populated; library constructors can read element dimensions.
- **Update receives the new props.** Inside the hook body, you can read the current props (via `reagent/argv this` on Reagent or the captured fn parameter on hooks-based adapters) and push them to the library instance.
- **Cleanup is mandatory.** Unmount fires before the DOM node is removed. Skipping cleanup leaks the library instance, its listeners, and any tile / data caches it holds, across every navigation that re-mounts the component.

The one cross-adapter discipline: **capture `(rf/dispatcher)` at render-time**, in the inner's top-level `let` (or, on Reagent, in the closure around `create-class`). The dispatcher carries the surrounding frame; the lifecycle callback fires after commit but the closure is established at render-time, so dispatches from inside the callback resolve to the right frame. A bare `(rf/dispatch […])` from inside a lifecycle callback escapes the dynamic frame binding and silently routes to `:rf/default` (per [Spec 002 §Dispatches issued from inside a handler body](002-Frames.md#dispatches-issued-from-inside-a-handler-body)).

## Worked example — a Mapbox-shaped widget

A small map view, parameterised by a current position from `app-db`. The shape is library-agnostic; substitute D3, Three.js, CodeMirror, etc. with no structural change. Pseudo-code — the library calls are illustrative, not runnable.

```clojure
(ns my-app.map
  (:require [re-frame.core :as rf]
            [reagent.core  :as r]))

;; Inner — Form-3, owns the library lifecycle. Registered via reg-view*.
(rf/reg-view* :my-app.map/map-inner
  (fn [_initial-pos]
    (let [el-ref       (atom nil)            ;; mount-point handle
          map-instance (atom nil)            ;; library instance handle
          marker       (atom nil)            ;; library-owned marker
          dispatch     (rf/dispatcher)]      ;; captured at render — carries frame
      (r/create-class
        {:display-name "map-inner"

         :component-did-mount
         (fn [this]
           (let [[_ {:keys [lat lng zoom]}] (r/argv this)
                 m (js/mapboxgl.Map. (clj->js {:container @el-ref
                                               :center    [lng lat]
                                               :zoom      zoom}))]
             (reset! map-instance m)
             (reset! marker (-> (js/mapboxgl.Marker.)
                                (.setLngLat #js [lng lat])
                                (.addTo m)))
             ;; Library callback → dispatch into the captured frame.
             (.on m "moveend"
               (fn [_evt]
                 (let [c (.getCenter m)]
                   (dispatch [:map/user-panned (.-lat c) (.-lng c)]))))))

         :component-did-update
         (fn [this _ _ _]
           (let [[_ {:keys [lat lng]}] (r/argv this)]
             (.setLngLat @marker      #js [lng lat])
             (.panTo     @map-instance #js [lng lat])))

         :component-will-unmount
         (fn [_this]
           (some-> @map-instance .remove)    ;; library's dispose API
           (reset! map-instance nil)
           (reset! marker       nil)
           (reset! el-ref       nil))

         :reagent-render
         (fn [_pos]
           [:div {:ref   (fn [el] (reset! el-ref el))
                  :style {:height "400px" :width "100%"}}])}))))

;; Outer — Form-1, reads subs, hands props to the inner.
(rf/reg-view map-panel []
  (let [pos @(rf/subscribe [:current-position])]
    [(rf/view :my-app.map/map-inner) pos]))
```

Things worth noting:

1. **The outer is trivially small** — sub, deref, pass. All the complexity lives in the inner, behind a stable interface.
2. **Per-mount state lives in closure atoms.** `el-ref`, `map-instance`, `marker` are `(atom)` cells inside the inner's outer fn — one set per mount. **Don't** use top-level `def` or `defonce`; those leak across mounts and across hot-reloads.
3. **The render body is consistent across renders.** `[:div {:ref …}]` doesn't change shape when props change; React/Reagent leaves the mount node alone, so the library's DOM subtree survives intact between renders. The work of reacting to new props happens in `:component-did-update`, not in the render.
4. **The library callback (`m.on "moveend"`) dispatches via the captured `dispatch`.** The dispatcher closure was built during render, so the library callback — which fires on a fresh stack with no `*current-frame*` binding — still routes the dispatch to the right frame.
5. **`:component-will-unmount` is mandatory.** Without `(.remove map-instance)`, every navigation that unmounts the map leaks Mapbox's WebGL context, tile cache, and event listeners. Multiply by 10 navigations across a session and the tab is a memory swamp.
6. **Props are a map.** The vector form `[(rf/view :my-app.map/map-inner) pos]` — where `pos` is a map — is what `r/argv` destructures inside the lifecycle callbacks. Per Reagent's contract, `(reagent.core/props comp)` only works when props are a map; v1's `Using-Stateful-JS-Components.md` documents the same trap.

The hooks-based adapters (UIx, Helix) compress the lifecycle into a single `use-effect` body. The structural pattern — outer reads subs, inner owns the library — is identical; only the keystrokes differ. See the per-adapter READMEs linked above for the hooks-shaped spelling.

## Animations are a special case of this pattern

[Spec 004 §Regime C — Library-bridged animations](004-Views.md#regime-c--library-bridged-animations-framer-motion-react-spring-gsap-autoanimate) describes animation libraries (Framer Motion, React-Spring, GSAP, AutoAnimate) that own their own imperative timing inside their own component tree. **The wrapping shape is exactly this pattern.** Animation libraries are not a separate category from stateful JS components — they are one instance of it. The outer reads subs and produces state-derived props (target opacity, target x/y, easing curve, target colour); the inner is a Form-3 / `use-effect` wrapper that hands the library those props; the library's internal completion callbacks (e.g. Framer Motion's `onAnimationComplete`) are bridged at the inner boundary, dispatching via the same captured `(rf/dispatcher)` discipline.

Regimes A (CSS-driven transitions) and B (per-frame RAF loops in a registered fx) of [Spec 004 §Animations](004-Views.md#animations) do **not** use this pattern. Choose the regime by what the state needs to know:

- **Regime A** — state is the truth; view re-renders with a new `:class`; CSS animates. No outer/inner; no library to wrap.
- **Regime B** — state advances per-frame; a registered fx owns the RAF loop and dispatches a per-frame event. No outer/inner; no library to wrap.
- **Regime C** — a third-party library owns the imperative timing. **Use this pattern.**

## What NOT to do

The shapes that look tempting but compose badly with re-frame2's reactive flow. Each is owned (with the full reason) by [Spec 004 §View antipatterns](004-Views.md#view-antipatterns); listed here so the trap is visible from the pattern doc.

- **Attaching `addEventListener` from a render body** ([Spec 004 §Views MUST NOT attach native DOM event listeners from render bodies](004-Views.md#views-must-not-attach-native-dom-event-listeners-from-render-bodies)) — the listener fires on a fresh stack with no `*current-frame*` binding; a bare `(rf/dispatch …)` from inside it silently routes to `:rf/default`. The listener also leaks: nothing detaches it on re-render or unmount. The right home for `addEventListener` is the **inner's** lifecycle hook, with a cleanup that removes the listener on unmount.
- **Owning a library lifecycle directly in a render body** ([Spec 004 §Views MUST NOT own imperative library lifecycles directly](004-Views.md#views-must-not-own-imperative-library-lifecycles-directly)) — `(js/MyLib. el opts)` from a Form-1 body builds a fresh library instance every render. The library was built to be instantiated **once** at mount; building it on every render leaks instances at the rate of every reactive update. The right home is the **inner's** `:component-did-mount` (or `use-effect` mount phase).
- **Calling `@(subscribe …)` inside a lifecycle hook body.** Subscriptions need reactive context; `:component-did-mount`, `:component-did-update`, `:component-will-unmount`, and `use-effect` bodies all run after commit with no reactive context. Subscribe in the **outer** (or, on Reagent adapters, in `:reagent-render`); pass the value as a prop to the inner.
- **Stashing the library instance in `defonce` or a top-level `def`.** Top-level cells leak across mounts and across hot-reloads, and they break when the component mounts twice (e.g. in two different frames simultaneously). The library instance is **per-mount** state; the closure inside the inner is the right home.

## Cross-references

- [Spec 004 §View antipatterns](004-Views.md#view-antipatterns) — the normative "MUST NOT" rules that make this pattern the right answer.
- [Spec 004 §Form-3 (class — out of scope for the macro)](004-Views.md#form-3-class--out-of-scope-for-the-macro) — why Form-3 ships through `reg-view*` rather than the `reg-view` macro.
- [Spec 004 §Animations — Regime C](004-Views.md#regime-c--library-bridged-animations-framer-motion-react-spring-gsap-autoanimate) — animation libraries as a special case of this pattern.
- [Spec 002 §Dispatches issued from inside a handler body](002-Frames.md#dispatches-issued-from-inside-a-handler-body) — why `(rf/dispatcher)` must be captured at render-time, not inside the lifecycle callback.
- [Pattern — Async Effect](Pattern-AsyncEffect.md) — the sibling pattern for "external work + dispatched reply" outside the view layer (HTTP, IndexedDB, WebSocket, RAF loops); composes with this pattern when the library exposes its own async callbacks (e.g. a tile-loaded event from a map library).
- [Reagent adapter README §Imperative escape hatch](../implementation/adapters/reagent/README.md), [Reagent-slim adapter README §Imperative escape hatch](../implementation/adapters/reagent-slim/README.md) and [`FORM-3.md`](../implementation/adapters/reagent-slim/FORM-3.md), [UIx adapter README §Imperative escape hatch](../implementation/adapters/uix/README.md), [Helix adapter README §Imperative escape hatch](../implementation/adapters/helix/README.md) — the four per-adapter spellings.
