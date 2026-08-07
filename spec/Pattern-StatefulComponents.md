# Pattern — Stateful Components

> **Type:** Pattern
> The canonical outer/inner wrapping shape for views that bridge a stateful third-party JavaScript component (D3, Mapbox, CodeMirror, Three.js, GSAP, Framer Motion, ag-grid, Vega-Embed, AmCharts, SpreadJS, …). Convention, not Spec.

> **Code samples are in ClojureScript** (the CLJS reference). The pattern itself is host-agnostic where the host has a component-lifecycle equivalent; on the JVM there is no DOM to bridge, so the pattern is browser-side.

> **What this pattern classifies as.** The outer/inner shape below is spelled on **today's shipping view adapters** — the **stock-Reagent compatibility/interop tier** (Form-3 via `reg-view*`, render-time `capture-frame`) and the **UIx and reagent-slim adapters**. All three are first-class and actively supported — they live on (see [Spec 006 §CLJS reference scope](006-ReactiveSubstrate.md#cljs-reference-scope) for each adapter's lifecycle role; Helix was removed at S7/W13 — rf2-d6epb, 2026-07-22). It is the **current** guidance for bridging a stateful JS component.
>
> The **new, experimental** first-party view substrate is the compiled `re-frame.ui`, whose one component form is `ui/defview` over a single props map ([Spec 004D §`ui/defview`](004D-Freehand-Compiled-Grammar.md#uidefview--the-one-component-form)) and whose stateful-bridge equivalent — component refs, `ui/effect`, and observation handles ([Spec 004D §Effects](004D-Freehand-Compiled-Grammar.md#effects--the-view-side-surface)) — is being specified through the re-frame.ui S1–S7 program and is **not yet the shipping bridge**. When it lands, this pattern's compiled form is specified in [Spec 004](004-Views.md) alongside the shipping adapters — no `spec/004A` compatibility appendix lands and nothing relocates. Until then, the Form-3 / `reg-view*` / `capture-frame` shape documented here is the one to use.

## Role

A **named pattern**, not a Spec. Re-frame2's view substrate ([Spec 004 — Views](004-Views.md)) is built around pure render functions that compute hiccup from state. A small but unavoidable fraction of real-world views need to wrap a third-party JS library that **owns its own DOM** and exposes an imperative `init / update / dispose` lifecycle.

This doc names the wrapping shape — **outer/inner split** — so feature code, adapter READMEs, and the [Animations Regime C](#regime-c--library-bridged-animations) discussion cite a single canonical description rather than re-deriving the rationale per library.

The runtime contract for the lifecycle hooks themselves is owned per-adapter ([§Per-adapter spelling](#per-adapter-spelling) below); this doc owns the **shape** that composes those hooks with the framework's reactive flow.

## Why the pattern exists

Third-party JS components — D3 charts, Mapbox/Leaflet maps, CodeMirror / Monaco editors, Three.js scenes, animation libraries — share three properties that put them in tension with re-frame2's view layer:

- **They own a DOM subtree.** They build, mutate, and tear down their own elements. The view layer cannot describe what they render with hiccup; it can only declare a mount point and hand them props.
- **They have an imperative lifecycle.** "Initialise against this DOM node," "you have new data, please re-draw," "you're going away, please clean up." None of those phases is a pure function of props.
- **They register their own listeners and timers.** Map pans, chart hovers, editor selection-change events, animation completions — all attached on the library's side, all needing teardown on unmount or they leak.

A re-frame2 view body, on the other hand, **MUST NOT** dispatch from render, **MUST NOT** `addEventListener` from render, and **MUST NOT** own an imperative library lifecycle directly (per [Spec 004D §Effects — the view-side surface](004D-Freehand-Compiled-Grammar.md#effects--the-view-side-surface)). The render body computes hiccup; the work that violates "pure render" lives somewhere else.

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

The inner view is **not** a Form-1. It is whatever the active adapter exposes as its Form-3 equivalent — a Reagent `create-class` for Reagent / Reagent-slim, a `use-effect` body for UIx. The inner owns three lifecycle phases:

1. **Mount** — after first commit, the DOM mount point exists. Read it via a ref, hand it to the library's constructor, stash the resulting instance handle in a per-mount closure cell (a plain `atom` for Reagent; a `ui/ref` for hooks-based adapters). Apply the initial props.
2. **Update** — when props change, the library's instance is **already mounted**; it is not torn down and re-created. Push the new props into the instance via whatever imperative API the library exposes (`.setData`, `.setView`, `.setOptions`, `.update`, `.panTo`, …).
3. **Unmount** — release the instance handle. Call the library's dispose / destroy API if it has one; remove any listeners the library was unable to clean up itself; null out the closure cells so the GC can reclaim them.

The inner's render body itself is **trivial** — usually just `[:div {:ref …}]` (or the substrate-equivalent), describing the mount point but no content. The library fills that node; React/Reagent must see consistent hiccup across renders so the substrate doesn't tear the mount node out from under the library.

### Why split outer/inner

The split is forced by **reactive context**:

- Subscriptions are reactive — they want to be read at render time, from a view that the substrate can re-render when the value changes.
- The library lifecycle is imperative — `:component-did-mount`, `:component-did-update`, `use-effect` bodies all run **after commit**, on a stack with no reactive context. Reading subs from inside the lifecycle callback is undefined behaviour on every adapter ([Reagent README §95](../implementation/adapters/reagent/README.md), [UIx README §72](../implementation/adapters/uix/README.md)). (The lone disciplined exception — Reagent's captured-`:subscribe` + a **per-mount `r/track!` owner** + a balanced frame-first `(rf/unsubscribe frame query-v)`, the migration recipe's §M-11 *exceptional imperative-subscription Form-3* — is the deliberate, ref-count-balanced case, not the undefined bare deref this rules out. The tracker is what makes it disciplined: a subscription on the ratom family is a Reaction built without `:auto-run`, so it learns its sources only through `deref-capture` — the tracker supplies that, where a plain hook deref plus `add-watch` would leave the widget fed once and deaf.)

The outer handles the reactive read; the inner handles the imperative lifecycle. Props are the seam.

## Per-adapter spelling

The shape is identical across adapters; only the lifecycle-hook surface differs. Cross-link to each adapter's README for the exact API surface and per-adapter worked example. Per the classification callout above, the **Reagent** and **Reagent-slim** rows are the stock-Reagent compatibility/interop tier and the **UIx** row is a first-class, actively-supported adapter — all three live on and remain the shipping spelling today.

| Adapter | Inner lifecycle surface | Registration | Reference |
|---|---|---|---|
| **Reagent** | `reagent.core/create-class` Form-3 (`:reagent-render` + `:component-did-mount` + `:component-did-update` + `:component-will-unmount`) | `reg-view*` (the plain-fn surface — the `reg-view` macro rejects Form-3 bodies per [Spec 004D §Removed forms — normative absences](004D-Freehand-Compiled-Grammar.md#removed-forms--normative-absences)) | [Reagent adapter README](../implementation/adapters/reagent/README.md) |
| **Reagent-slim** | `reagent2.core/create-class` Form-3, **7-key cap** (the six lifecycle keys plus `:display-name`) | `reg-view*` | [Reagent-slim adapter README](../implementation/adapters/reagent-slim/README.md) and [`FORM-3.md`](../implementation/adapters/reagent-slim/FORM-3.md) — the slim adapter's single source of truth for Form-3 |
| **UIx** | `uix.core/use-effect` inside a `defui`, with a deps vector listing every prop the effect reads. Cleanup is the fn the effect body returns | ordinary `defui` (a plain fn) — read subs with `use-subscribe`, carry the frame with the `use-frame` hook; `reg-view*` is **optional**, only when the component must be addressable by id (registry addressing) | [UIx adapter README](../implementation/adapters/uix/README.md) |

The three things that are identical across adapters:

- **Mount runs after commit.** The DOM node exists when the hook fires; refs are populated; library constructors can read element dimensions.
- **Update receives the new props.** Inside the hook body, you can read the current props (via `reagent/argv this` on Reagent or the captured fn parameter on hooks-based adapters) and push them to the library instance.
- **Cleanup is mandatory.** Unmount fires before the DOM node is removed. Skipping cleanup leaks the library instance, its listeners, and any tile / data caches it holds, across every navigation that re-mounts the component.

The one cross-adapter discipline: **carry the frame from render-time into the after-commit callback** — never a bare `(rf/dispatch […])` from inside a lifecycle callback (it escapes the frame scope, carries no frame stamp, and fails loudly with `:rf.error/no-frame-context` — no `:rf/default` fall-through; per [Spec 002 §Frame target resolution](002-Frames.md#frame-target-resolution--the-carried-invariant)). The carried value is the same primitive on every adapter — a `(rf/capture-frame)` frame api, whose `:dispatch` op resolves to the right frame after commit — but the **spelling is per-adapter**:

- **Reagent / Reagent-slim** — capture `(rf/capture-frame)` at render-time, in the closure around `create-class`, and use its `:dispatch` op.
- **UIx** — call the **`use-frame` hook** at the top of the `defui` body: the hook-position spelling of `capture-frame`, returning the same `{:frame :dispatch :dispatch-sync :subscribe}` frame api. The hook reads the surrounding `frame-provider` / `frame-root` through React context, which a bare render-time `(rf/capture-frame)` in a plain hooks component **cannot** — no-arg capture reads only the dynamic-var tier, so under a context-only frame it raises `:rf.error/no-frame-context`. Read subs in the outer with **`use-subscribe`** (not `@(subscribe …)`).

## Worked example — a Mapbox-shaped widget

A small map view, parameterised by a current position from `app-db`. The shape is library-agnostic; substitute D3, Three.js, CodeMirror, etc. with no structural change. Pseudo-code — the library calls are illustrative, not runnable. The spelling below is on the **stock-Reagent compatibility/interop** tier (Form-3 via `reg-view*`); it is the current bridge until the compiled `re-frame.ui` equivalent lands (see the classification callout above).

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
          dispatch     (:dispatch (rf/capture-frame))]  ;; captured at render — carries frame
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

The hooks-based adapter (UIx) compresses the lifecycle into a single `use-effect` body: the outer reads subs with **`use-subscribe`**, and the inner is an ordinary `defui` that carries the frame with the **`use-frame` hook** (never a bare render-time `capture-frame`, which cannot read the `frame-provider` from React context) and owns the library in `use-effect`. The structural pattern — outer reads subs, inner owns the library — is identical; only the keystrokes differ. `reg-view*` is optional here, needed only when the inner must be addressable by id. See the per-adapter READMEs linked above for the hooks-shaped worked example.

## Animations are a special case of this pattern

Animation is a view-layer concern, but views are derivative — they compute a template from state. The portable principle: **state is the truth; the view animates the transition; animation completion is silent unless explicitly modelled in state.** Three regimes cover the space. This doc is the canonical home for the regime taxonomy; [Spec 004D §Effects](004D-Freehand-Compiled-Grammar.md#effects--the-view-side-surface) owns the RAF-as-fx and effect-plus-ref *mechanics* those regimes use. Choose the regime by what the state actually needs to know.

### Regime A — Transition animations

The 95% case. State changes; the view re-renders with a different `:class` or `:style`; CSS (or the substrate's animation engine) completes the visual transition silently. **No completion dispatch is needed** — by the time the animation kicks off, `app-db` has already moved on and the visual is catching up. Opacity fades, slide in/out, accordion expand, list reorder, modal scrim, route transitions. Sequencing belongs in CSS (`animation-delay`, keyframes) or a small `:dispatch-later` chain that advances a `:phase` key at known intervals. No outer/inner; no library to wrap.

### Regime B — Continuous animations (RAF loops)

Per-frame state mutation IS the truth. The right shape is a registered fx (e.g. `:ui/raf-loop`) that owns the `requestAnimationFrame` cycle and dispatches a per-frame event carrying delta-time; the fx captures the frame at registration (per [Pattern-AsyncEffect](Pattern-AsyncEffect.md)), the handler updates state, the view renders it, and a sibling fx cancels the RAF handle. This is Pattern-AsyncEffect with `requestAnimationFrame` substituted for HTTP — particle systems, scroll inertia, physics, game loops all fit. No outer/inner; no library to wrap.

### Regime C — Library-bridged animations

Framer Motion, React-Spring, GSAP, AutoAnimate — the animation library is component-shaped: it owns its own imperative timing inside its own component tree. **The wrapping shape is exactly this pattern.** Animation libraries are not a separate category from stateful JS components — they are one instance of it. The outer reads subs and produces state-derived props (target opacity, target x/y, easing curve, target colour); the inner is a Form-3 / `use-effect` wrapper that hands the library those props; the library's internal completion callbacks (e.g. Framer Motion's `onAnimationComplete`) are bridged at the inner boundary, dispatching via the same captured `(rf/capture-frame)` discipline as the outer/inner split above. **Use this pattern** for Regime C.

## What NOT to do

The shapes that look tempting but compose badly with re-frame2's reactive flow. Each is owned (with the full reason) by [Spec 004D §Effects — the view-side surface](004D-Freehand-Compiled-Grammar.md#effects--the-view-side-surface); listed here so the trap is visible from the pattern doc.

- **Attaching `addEventListener` from a render body** ([Spec 004D §Views MUST NOT attach native DOM event listeners from render bodies](004D-Freehand-Compiled-Grammar.md#effects--the-view-side-surface)) — the listener fires on a fresh stack with no `*current-frame*` binding; a bare `(rf/dispatch …)` from inside it carries no frame stamp and fails loudly with `:rf.error/no-frame-context` (no `:rf/default` fall-through). The listener also leaks: nothing detaches it on re-render or unmount. The right home for `addEventListener` is the **inner's** lifecycle hook, with a cleanup that removes the listener on unmount.
- **Owning a library lifecycle directly in a render body** ([Spec 004D §Views MUST NOT own imperative library lifecycles directly](004D-Freehand-Compiled-Grammar.md#effects--the-view-side-surface)) — `(js/MyLib. el opts)` from a Form-1 body builds a fresh library instance every render. The library was built to be instantiated **once** at mount; building it on every render leaks instances at the rate of every reactive update. The right home is the **inner's** `:component-did-mount` (or `use-effect` mount phase).
- **Calling `@(subscribe …)` inside a lifecycle hook body.** Subscriptions need reactive context; `:component-did-mount`, `:component-did-update`, `:component-will-unmount`, and `use-effect` bodies all run after commit with no reactive context. Subscribe in the **outer** (or, on Reagent adapters, in `:reagent-render`); pass the value as a prop to the inner. (The one deliberate exception is a rare imperative widget re-fed from a hook — Reagent's captured-`:subscribe` + a **per-mount `r/track!` owner** + a balanced frame-first `(rf/unsubscribe frame query-v)`, the migration recipe's §M-11 *exceptional imperative-subscription Form-3*; it is ref-count-balanced and does not relax this default. Note what the exception is *not*: a bare `add-watch` on the acquired reaction. Nothing would ever activate that reaction, so the watch could not fire and the widget would go deaf after mount — the tracker is the reactive owner that supplies the missing `deref-capture`.)
- **Stashing the library instance in `defonce` or a top-level `def`.** Top-level cells leak across mounts and across hot-reloads, and they break when the component mounts twice (e.g. in two different frames simultaneously). The library instance is **per-mount** state; the closure inside the inner is the right home.

## Cross-references

- [Spec 004D §Effects — the view-side surface](004D-Freehand-Compiled-Grammar.md#effects--the-view-side-surface) — the normative "MUST NOT" rules that make this pattern the right answer.
- [Spec 004D §Removed forms — normative absences](004D-Freehand-Compiled-Grammar.md#removed-forms--normative-absences) — why Form-3 ships through `reg-view*` rather than the `reg-view` macro.
- [§Regime C — Library-bridged animations](#regime-c--library-bridged-animations) — animation libraries as a special case of this pattern.
- [Spec 002 §Dispatches issued from inside a handler body](002-Frames.md#dispatches-issued-from-inside-a-handler-body) — why `(rf/capture-frame)` must be captured at render-time, not inside the lifecycle callback.
- [Pattern — Async Effect](Pattern-AsyncEffect.md) — the sibling pattern for "external work + dispatched reply" outside the view layer (HTTP, IndexedDB, WebSocket, RAF loops); composes with this pattern when the library exposes its own async callbacks (e.g. a tile-loaded event from a map library).
- [Reagent adapter README §Imperative escape hatch](../implementation/adapters/reagent/README.md), [Reagent-slim adapter README §Imperative escape hatch](../implementation/adapters/reagent-slim/README.md) and [`FORM-3.md`](../implementation/adapters/reagent-slim/FORM-3.md), [UIx adapter README §Imperative escape hatch](../implementation/adapters/uix/README.md) — the three per-adapter spellings.
