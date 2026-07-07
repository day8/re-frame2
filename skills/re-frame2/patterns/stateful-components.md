# Pattern — Stateful Components

The canonical **outer/inner** shape for a view that bridges a stateful third-party JS library — one that owns its own DOM subtree and exposes an imperative `init / update / dispose` lifecycle (D3, Mapbox, Leaflet, CodeMirror, Monaco, Three.js, ag-grid, Vega-Embed, GSAP, Framer Motion, …). **Convention, not Spec.** Browser-side only (no DOM to bridge on the JVM).

> **Mental-model anchor:** this is the **React "wrap an imperative library in a component with a ref + `useEffect`"** shape — mount the library against a ref'd node, push new props imperatively on update, dispose on unmount. re-frame2 splits that into two views because subscriptions are reactive (read at render) while the library lifecycle is imperative (runs after commit, with no reactive context).

## When to load

The prompt mentions: wrapping a chart / map / code-editor / 3D-scene / grid / animation library, "how do I integrate `<some JS lib>`", a `:ref` + lifecycle hook, "the view needs to call `.setData` / `.panTo` / `.update`", or a Form-3 / `use-effect`-bodied view. Also load for the Animations *Regime C* (library-bridged) case — animation libs are one instance of this pattern.

## The outer/inner split

```
  outer (reg-view)        reads subs, derives a JSON-shaped props map
   │
   ▼
  inner (Form-3 / use-effect)   owns the library lifecycle: mount / update / unmount
   │
   ▼
  library                 owns its DOM subtree, listeners, internal timers
```

- **Outer — pure re-frame2 view.** A standard `reg-view`. Reads subscriptions, computes one props map, renders the inner with it. Never touches the DOM; never holds an instance handle. When subs change, the outer re-renders and feeds the inner fresh props.
- **Inner — Form-3-equivalent lifecycle wrapper.** Three phases: **mount** (after first commit, read the DOM node via a ref, hand it to the library constructor, stash the instance in a per-mount closure cell), **update** (push new props into the *already-mounted* instance via its imperative API — never tear down and re-create), **unmount** (call the library's dispose/destroy API, remove listeners, null the cells). Render body is trivial — `[:div {:ref …}]`; the library fills the node.

The split is forced by reactive context: subs want render-time reads; lifecycle callbacks run after commit on a stack with no reactive context. Props are the seam.

## The one cross-adapter discipline — capture the frame at render-time

Capture `(rf/capture-frame)` in the inner's top-level `let` (Reagent: in the closure around `create-class`) and use its `:dispatch` op for any library callback. A bare `(rf/dispatch …)` from a lifecycle callback fires on a fresh stack with no `*current-frame*` binding and — under EP-0002 — fails loudly with `:rf.error/no-frame-context`. Capture the frame api while the frame scope still exists.

## Canonical declaration (Reagent — a Mapbox-shaped widget)

Pseudo-code; library calls are illustrative. Substitute D3 / Three.js / CodeMirror with no structural change.

```clojure
(ns my-app.map
  (:require [re-frame.core :as rf]
            [reagent.core  :as r]))

;; Inner — Form-3, owns the library lifecycle. Registered via reg-view*
;; (the reg-view macro rejects Form-3 bodies).
(rf/reg-view* :my-app.map/map-inner
  (fn [_initial-pos]
    (let [el-ref       (atom nil)     ;; mount-point handle
          map-instance (atom nil)     ;; library instance (per-mount, NOT defonce)
          dispatch     (:dispatch (rf/capture-frame))]   ;; captured at render — carries frame
      (r/create-class
        {:display-name "map-inner"
         :component-did-mount
         (fn [this]
           (let [[_ {:keys [lat lng zoom]}] (r/argv this)
                 m (js/mapboxgl.Map. (clj->js {:container @el-ref :center [lng lat] :zoom zoom}))]
             (reset! map-instance m)
             (.on m "moveend"                       ;; library callback → dispatch into the captured frame
               (fn [_] (let [c (.getCenter m)]
                         (dispatch [:map/user-panned (.-lat c) (.-lng c)]))))))
         :component-did-update
         (fn [this _ _ _]
           (let [[_ {:keys [lat lng]}] (r/argv this)]
             (.panTo @map-instance #js [lng lat])))   ;; push new props; do NOT rebuild
         :component-will-unmount
         (fn [_] (some-> @map-instance .remove)        ;; library dispose API — mandatory
                 (reset! map-instance nil) (reset! el-ref nil))
         :reagent-render
         (fn [_pos] [:div {:ref #(reset! el-ref %) :style {:height "400px"}}])}))))

;; Outer — Form-1, reads subs (injected `subscribe`), hands props to the inner.
(rf/reg-view map-panel []
  (let [pos @(subscribe [:current-position])]
    [(rf/view :my-app.map/map-inner) pos]))
```

Load-bearing points: the outer is trivially small (sub, deref, pass); per-mount state lives in **closure atoms**, not `defonce`/top-level `def` (those leak across mounts + hot-reloads); the render body is stable across renders so the substrate leaves the mount node alone; the library callback dispatches via the captured `dispatch`; **`:component-will-unmount` is mandatory** (skipping it leaks the WebGL context / tile cache / listeners on every navigation). Props are a **map** so `r/argv` / `r/props` can destructure them.

## Per-adapter spelling

The shape is identical; only the lifecycle surface differs.

| Adapter | Inner lifecycle surface | Registration |
|---|---|---|
| **Reagent** / **Reagent-slim** | `create-class` Form-3 (`:component-did-mount` / `-did-update` / `-will-unmount` + `:reagent-render`) | `reg-view*` |
| **UIx** | `use-effect` inside a `defui`, deps vector listing every prop read; cleanup is the returned fn | `reg-view` (plain fn) |
| **Helix** | `use-effect` inside a `defnc`, deps vector **first**, cleanup is the last expression | `reg-view` (plain fn) |

See the per-adapter README "Imperative escape hatch" sections for the hooks-shaped spelling.

## Animations are a special case

Regime C (library-bridged: Framer Motion, React-Spring, GSAP, AutoAnimate) **is** this pattern — outer derives state-driven props (target opacity, x/y, easing), inner hands them to the library, completion callbacks bridge via the captured `capture-frame`. Regimes A (CSS-driven `:class`) and B (per-frame RAF loop in a registered fx) do *not* use this pattern — no library to wrap.

## Anti-patterns

- **`addEventListener` from a render body.** Fires with no carried frame — under EP-0002 a bare `dispatch` in the callback raises `:rf.error/no-frame-context` (there is no `:rf/default` to fall open to) — and leaks. The right home is the inner's mount hook with cleanup on unmount; capture a `capture-frame` for any dispatch the listener fires after commit.
- **Owning the library lifecycle in a render body.** `(js/MyLib. el opts)` from a Form-1 builds a fresh instance every render — leaking at the rate of reactive updates. Build it once in the mount hook.
- **`@(subscribe …)` inside a lifecycle hook.** No reactive context after commit. Subscribe in the outer; pass the value as a prop.
- **Stashing the instance in `defonce` / top-level `def`.** Leaks across mounts and hot-reloads; breaks when the component mounts twice (two frames). The instance is per-mount — closure cell only.

## Worked example

No standalone example app — the per-adapter READMEs carry the worked spelling for each substrate (`implementation/adapters/<name>/README.md` §Imperative escape hatch). The Mapbox shape above is the canonical summary; substitute the library.

## Pointers

- Spec: [`spec/Pattern-StatefulComponents.md`](../../../spec/Pattern-StatefulComponents.md) — the full outer/inner rationale, the per-adapter table, the animations-as-special-case discussion, the antipattern reasons.
- Substrate: `SKILL-REDIRECT.md` → *EP — Views (004)* §View antipatterns (the normative MUST-NOTs), §Form-3, §Animations Regime C; *EP — Frames (002)* (why `capture-frame` must be captured at render-time).
- Compose: `patterns/reusable-components.md` (a `[customer-chart id]` is a reusable component that also wraps a library), `patterns/async-effect.md` (when the library exposes its own async callbacks, e.g. a tile-loaded event).

---

*Derived from `spec/Pattern-StatefulComponents.md` (Convention, not Spec) @ main. Re-verify if `reg-view*` Form-3 or the per-adapter lifecycle surfaces change.*
