# 02 — Views

Views are where the cascade ends and pixels begin. The view layer in re-frame2 is **substrate-agnostic** — the shared dataflow (frames, subscriptions, dispatch, source metadata, registry ids) is uniform across Reagent, UIx, and Helix, and the same `capture-frame` carry primitive composes across all three. The substrates differ in how they emit React calls; the re-frame2 contract sits above that and stays uniform.

There are **two registration lanes**, and you almost certainly want only one of them:

- **App-facing view registration** — what application authors use to register and render their own views. This is the `reg-view` macro (Reagent) plus rendering by Var reference. Read this if you are building screens.
- **Tooling / host view registration** — what tools, dynamic hosts, and library code use to register views from *computed* ids and render them by id at runtime. This is `reg-view*` plus `(rf/view id)`. Read this only if you are embedding views you don't write at the call site — tool panels, story canvases, code-gen pipelines, plugin systems.

If you're not sure, you're an application author: use the app-facing lane and skip the tooling lane entirely.

This chapter also covers the substrate-agnostic ergonomic surface (`capture-frame`, `with-frame`, `with-new-frame`, `frame-provider`), and points at the per-adapter chapters for the substrate-specific hooks. If you want the Reagent vs UIx vs Helix conventions, see [13 — Lifecycle](13-lifecycle.md) and [14 — Adapters](14-adapters.md).

## App-facing view registration

This is the lane for application authors. You register a view with `reg-view`, render it by Var reference, and that's the whole story for the 80% case. The view registry that backs this is what `[my-view "arg"]` resolves against: every view you register lives in it, keyed by id (a keyword, conventionally `(keyword *ns* sym)` so the view's id matches its symbol).

### `reg-view`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-view sym [args] body+)
  (reg-view sym docstring [args] body+)
  (reg-view ^{:rf/id :explicit/id} sym [args] body+)
  ```
- **Description**: The defn-shape view registration. Auto-defs the symbol, auto-derives the id from `(keyword *ns* sym)`, auto-injects `dispatch` / `subscribe` as lexical bindings, and rejects non-defn-shape bodies at macroexpand. The 80% of registrations want this form.
- **Example**:
  ```clojure
  (rf/reg-view counter-buttons []
    [:div
     [:button {:on-click #(dispatch [:counter/dec])} "-"]
     [:span @(subscribe [:counter/value])]
     [:button {:on-click #(dispatch [:counter/inc])} "+"]])
  ```
- **In the wild**: [counter](https://github.com/day8/re-frame2/tree/main/examples/core/counter)

There is one app-facing exception that still lives in the macro family: Reagent **Form-3** components (`reagent.core/create-class`) aren't `defn`-shaped, so the `reg-view` macro can't wrap them. Those register through `reg-view*` — see [Tooling / host view registration](#tooling--host-view-registration) below, where the rest of the starred-form callers live.

### How `reg-view` reads

The macro accepts three shapes for the same registration. They produce the same registered view; the choice is about what you want at the source level.

```clojure
;; Bare form — id derived as :my.app.cart/cart-line
(rf/reg-view cart-line [item]
  [:tr
    [:td (:name item)]
    [:td (:qty item)]])

;; With docstring — useful for the registry's :doc field
(rf/reg-view cart-line
  "One row in the cart table; receives a normalised item map."
  [item]
  [:tr ...])

;; With explicit id via metadata — useful when the symbol shouldn't drive the id
;; (e.g. you want a stable id across rename, or you're matching an external contract).
(rf/reg-view ^{:rf/id :cart/line} cart-line [item]
  [:tr ...])
```

In all three cases the symbol `cart-line` is `def`-ed so you can write `[cart-line item]` from sibling code. The macro also injects `dispatch` and `subscribe` as lexical bindings so you can call them without the `rf/` prefix inside the body; the `rf/` prefix is conventional.

### Rendering an app-facing view

In the app-facing lane you render a view by **Var reference** — the symbol `reg-view` `def`-ed:

```clojure
[cart-line item]                ;; the canonical, source-readable form
```

Reagent / UIx / Helix all resolve a function-in-tag-position by calling it with the trailing args. Bare keyword-tagged hiccup (`[:my-view "args"]`) is **removed in v2** — it was a v1 footgun that collided with HTML tag keywords; use the Var form. The by-id lookup form `[(rf/view :id) args]` exists too, but it belongs to the tooling lane below — application authors reaching for it usually want a plain Var reference instead.

## Tooling / host view registration

This is the lane for **tools, dynamic hosts, and library code** — not application screens. Reach for it when the thing rendering a view doesn't know the view at the call site: a tool panel hosting an arbitrary registered view, a story canvas that stores a view id in data, a code-gen pipeline that emits views from a manifest, or a plugin system that late-binds by id. It is built from two surfaces: `reg-view*` (register from a computed id or a non-`defn` render fn) and `view` (resolve a registered render-fn by id at runtime). If you're writing application views, you don't need either — use `reg-view` and Var references above.

### `reg-view*`

- **Kind**: function
- **Signature**:
  ```clojure
  (reg-view* id render-fn)
  (reg-view* id metadata render-fn)
  ```
- **Description**: The plain-fn surface beneath `reg-view`. No auto-def (the caller manages the Var or computed id), no auto-inject, no compile check. The starred form is the right call when:
    - **The id is computed.** Code-gen pipelines, plugin systems, story / variant scaffolding — anywhere the id isn't a literal symbol at the call site.
    - **You don't want a Var.** Inside a `let` or a closure, or when the view is a one-off built from configuration data.
    - **You're writing a Form-3 component.** Reagent's `create-class` wraps a map of lifecycle methods around a render-fn; the call shape is `(rf/reg-view* :id (r/create-class {...}))`. This is the one app-facing reason to touch the starred form.
    - **You're consumer-side library code.** Libraries that ship registered views (a charting library, a table widget) often want to register without imposing a `def` on the consumer's namespace.
- **Note**: The `*` follows Clojure's own `let` / `let*`, `fn` / `fn*` idiom — the un-starred form is the macro shorthand; the starred form is the underlying primitive. Inside a `reg-view*` body there's no auto-injected `dispatch` / `subscribe`; capture a `(rf/capture-frame)` at render and use its ops if the view needs frame-bound dispatch.

### `view`

- **Kind**: function
- **Signature**:
  ```clojure
  (view view-id) → render-fn
  ```
- **Description**: Runtime lookup handle. Returns the **registered render-fn**, not hiccup. Use in hiccup as `[(rf/view :id) args...]` when you need to late-bind a view by id — a host that resolves a stored view id, plugin-style dispatch, dynamic chrome. This is how tool shells host an arbitrary registered view: they read a view id out of data, resolve it with `(rf/view id)`, and render the result.
- **Example**:
  ```clojure
  [(rf/view :app/header) {:title "Cart"}]   ;; resolves the registered render-fn at render time
  ```

The lookup form and the Var form produce the same render outcome — they differ only in addressing scheme. The Var form (`[my-view args]`) is the app-facing default; the lookup form (`[(rf/view :id) args]`) is for the tooling/host case where the id, not the symbol, is what the caller holds.

### Keep internal tool panels out of app-facing docs

A tool's own chrome — Story's panel grid, Xray's inspector views — is registered with `reg-view*` and hosted by id, but those panel components are *internal to the tool*, not part of the application author's view surface. When documenting an application, leave them out: an app author registers their screens with `reg-view` and never sees the host's panel registry. The tooling lane exists so that tools can host the app's views, not so that app authors adopt the tool's internals.

## The substrate-agnostic ergonomic surface

These surfaces work the same across Reagent, UIx, and Helix. They're how views interact with the running app without being tied to any single substrate's idiom. They sort into three intents: **scope or ensure a frame** (`frame-provider`, `with-frame`, `with-new-frame`), **hold** (`capture-frame` — the one public carry primitive), and **override** (the `{:frame …}` opt, rowed in [01 — Core](01-core.md)). The full design lives at [Spec 002 §The multi-frame surface](../../spec/002-Frames.md#the-multi-frame-surface--choose-by-intent).

### `frame-provider`

- **Kind**: Reagent component (one component, two config shapes)
- **Signatures**:
  ```clojure
  [rf/frame-provider {:frame :todo} & children]                     ;; SCOPE: scope an existing frame
  [rf/frame-provider {:id :todo :images [todo-image]} & children]   ;; ENSURE: create-if-absent / reuse
  ```
- **Description**: One component, two config shapes chosen by the prop map.
  - **`{:frame existing-id}` — SCOPE.** "Children inside this provider see `:todo` as their current frame." Scopes a React subtree to a frame that **already exists** (created by `make-frame` / `reg-frame`, a tool runtime, or an enclosing `frame-provider`); creates / refreshes / destroys nothing. **Fails loud** (`:rf.error/frame-provider-frame-absent`) when the named frame is absent. `:frame` must be a keyword — a `nil` `:frame` is `:rf.error/no-frame-context`, a non-keyword `:frame` is `:rf.error/bad-frame-provider-arg`. The scope-into-React counterpart to `with-frame` (which a dynamic var cannot serve across React's render boundary).
  - **`{:id the-id …}` — ENSURE.** Creates the frame if absent (via `make-frame`, taking the same constructor opts: `:id` / `:images` / record-config incl. `:initial-events`), **reuses it without re-seeding** if present (an idempotent re-mount preserves durable state and does not replay `:initial-events`), and provides its id to descendants. There is **no destroy-on-unmount**. Reach for it when a view should bring its own frame into being for as long as it is mounted (comparison pages, Story canvases, embedded widgets). `:id` is required and must be a keyword (`:rf.error/ensure-frame-provider-missing-id` otherwise). True ownership — tearing the frame down when the component unmounts — stays explicit: `make-frame` + `destroy-frame!` inside a `create-class`.

### `with-frame` / `with-new-frame`

- **Kind**: macros (sibling pair)
- **Signatures**:
  ```clojure
  (with-frame :keyword body)        ;; pin to an existing frame-id
  (with-new-frame [sym expr] body)  ;; eval, bind, run, destroy on exit
  ```
- **Description**: `with-frame` pins `*current-frame*` to an existing frame-id; the frame is not created or destroyed. `with-new-frame` evals `expr`, binds the resulting id to `sym`, runs body in that frame's dynamic context, and destroys the frame on exit. Each rejects the other's argument shape at compile time. Documented in [002 §with-frame and with-new-frame](../../spec/002-Frames.md#with-frame-and-with-new-frame).

### `capture-frame`

- **Kind**: function
- **Signature**:
  ```clojure
  (capture-frame)          → {:frame :dispatch :dispatch-sync :subscribe}
  (capture-frame frame-id) → {:frame :dispatch :dispatch-sync :subscribe}
  ```
- **Description**: The keystone affordance. Captures the active frame at CREATION time and returns an **operation bundle** whose `:dispatch` / `:dispatch-sync` / `:subscribe` ops always target the captured frame — they survive async boundaries (`Promise.then`, `setTimeout`, WebSocket `onmessage`, observer callbacks) where the ambient frame lookup would have unwound. The handle is *locked* to one frame: a per-call `:frame` opt MUST NOT override it. It's an operation bundle, not a container — read the frame's app-db value via `(rf/app-db-value (:frame handle))`, not the handle itself.
- **Example**:
  ```clojure
  (rf/reg-view stream-view []
    (let [{:keys [dispatch]} (rf/capture-frame)]          ;; captures the render frame
      (ws/subscribe! (fn [msg] (dispatch [::incoming msg]))) ;; fires LATER, but bound
      [:div "streaming…"]))
  ```

> **`frame-bound-fn` / `frame-bound-fn*` are internal.** They are not app API.
> Author async / tooling paths with `capture-frame` (or an explicit `{:frame …}`
> opt), which expresses the real use cases.

### When to reach for `capture-frame`

The verbs `dispatch` and `subscribe` read the current frame ambiently (dynamic var → React context) at call time. That's fine when the call sits *inside* an established scope — inside a render, an event handler, a sub computation, a `with-frame` block. It breaks when the call sits *outside* that scope — a Promise callback, a `setTimeout`, a WebSocket `onmessage`, an IntersectionObserver. By the time the callback fires, the ambient scope has unwound, the token carries no frame stamp, and a bare `(rf/dispatch [::foo])` fails loudly with `:rf.error/no-frame-context` — the runtime never synthesises `:rf/default` from absence; frame identity is *carried*, not *found* (see [EP-0002](../../spec/002-Frames.md#frame-target-resolution--the-carried-invariant)).

The fix is to capture the frame *at the point you have it* and carry it as a value with **`capture-frame`**: build the operation bundle inside a render body or under `with-frame`, store it, and invoke its `:dispatch` / `:subscribe` ops from any later async context.

```clojure
;; capture-frame composes inside with-frame
(rf/with-frame :tool
  (let [{:keys [dispatch]} (rf/capture-frame)]   ;; captures :tool frame
    (js/setTimeout #(dispatch [::tick]) 1000)))  ;; fires :tool even after with-frame unwinds
```

Full async-boundary contract (the four routing patterns and the React click-handler case): [Spec 002 §React click-handler routing](../../spec/002-Frames.md#react-click-handler-routing--the-canonical-pattern).

### `with-frame` and `with-new-frame` — the sibling pair

```clojure
;; Pin form — most common: bind *current-frame* to an existing id
(rf/with-frame :todo
  (rf/dispatch [::add-item ...]))

;; Pin to a computed id — pass the keyword directly, no extra binding
(let [chosen (compute-frame-id ...)]
  (rf/with-frame chosen
    (rf/dispatch [::action chosen])))

;; Eval-bind-run-destroy form — create a throwaway frame for the body
(rf/with-new-frame [f (rf/make-frame {:images [test-image]})]
  (rf/dispatch-sync [:test/initialise])   ;; seed via a setup dispatch
  (rf/dispatch [::action f]))   ;; frame destroyed on exit
```

Full semantics in [002 §with-frame and with-new-frame](../../spec/002-Frames.md#with-frame-and-with-new-frame).

## Reagent: the default substrate

The CLJS reference implementation ships against Reagent as the default substrate. There's no separate `re-frame.adapter.reagent` namespace to require — `re-frame.core` includes the Reagent adapter inline, because that's the historical default and the path of least surprise for re-frame v1 migrators.

Reagent views are plain Clojure functions returning hiccup; re-frame2's `reg-view` macro is the typed sugar over `defn` + `reg-view*`. Form-2 (a fn that returns a fn) and Form-3 (`create-class`) are both supported; the wrapping the macro emits is transparent to either pattern.

The adapter spec map — the value `(rf/init!)` consumes — lives at `re-frame.adapter.reagent/adapter` (Reagent-full) or `re-frame.adapter.reagent-slim/adapter` (Reagent without the React server-rendering tax, for SSR pipelines).

```clojure
(:require [re-frame.adapter.reagent :as reagent])

(rf/init! reagent/adapter)
```

## UIx and Helix: hooks-shaped substrates

UIx and Helix expose React's hooks model directly. The re-frame2 adapter for each ships in its own artefact (`day8/re-frame2-uix`, `day8/re-frame2-helix`) and exposes a small, parallel surface — the same shape across both, because the Helix decisions transfer the UIx decisions one-for-one.

In the entries below, `<adapter>` stands for the adapter namespace alias the consumer chose at require — typically `uix-adapter` or `helix-adapter`.

### `<adapter>/adapter`

- **Kind**: Var (map)
- **Signature**:
  ```clojure
  {:make-state-container …
   :render …
   :dispose-adapter! …}
  ```
- **Description**: The adapter spec passed to `(rf/init! ...)`.
- **Example**:
  ```clojure
  (:require [re-frame.adapter.uix :as uix-adapter])

  (rf/init! uix-adapter/adapter)
  ```
- **In the wild**: [counter_uix](https://github.com/day8/re-frame2/tree/main/examples/substrates/uix/counter) · [counter_helix](https://github.com/day8/re-frame2/tree/main/examples/substrates/helix/counter)

### `<adapter>/use-subscribe`

- **Kind**: hook (function)
- **Signature**:
  ```clojure
  (use-subscribe query-v) → value
  (use-subscribe frame-kw query-v) → value
  ```
- **Description**: The hook-shaped read. Matches the React/UIx/Helix idiom; there's no auto-injection — components call the hook and `(rf/capture-frame)` directly.
- **Example**:
  ```clojure
  (let [count    (uix-adapter/use-subscribe [:count])
        {:keys [dispatch]} (rf/capture-frame)]
    ($ :button {:on-click #(dispatch [:inc])} count))
  ```
- **In the wild**: [counter_uix](https://github.com/day8/re-frame2/tree/main/examples/substrates/uix/counter) · [counter_helix](https://github.com/day8/re-frame2/tree/main/examples/substrates/helix/counter)

### `<adapter>/use-current-frame`

- **Kind**: hook (function)
- **Signature**:
  ```clojure
  (use-current-frame) → frame-kw
  ```
- **Description**: "What frame am I in?" — for components that need to thread the frame through hand-written child callbacks.

### `<adapter>/frame-provider`

- **Kind**: component (function — one component, two config shapes)
- **Signatures**:
  ```clojure
  ($ frame-provider {:frame :session} child-1 child-2)                  ;; SCOPE an existing frame
  ($ frame-provider {:id :session :images [session-image]} child-1 child-2)  ;; ENSURE create-if-absent / reuse
  ```
- **Description**: The component-shaped equivalent of Reagent's merged `frame-provider`, dispatched on the prop map: `{:frame …}` scopes an existing frame (failing loud if absent), `{:id …}` ensures a named frame (create-if-absent / reuse-no-reseed / provide id, no destroy-on-unmount). Children ride the idiomatic `$` trailing-args channel — pass them after the prop map. The underlying React Context (`re-frame.adapter.context`) is **shared** across all three substrates, so a mixed-substrate app's provider chain composes across substrate boundaries.

### `<adapter>/wrap-view`

- **Kind**: function
- **Signature**:
  ```clojure
  (wrap-view id metadata user-fn) → wrapped fn
  ```
- **Description**: Adapter-side source-coord annotation. Most UIx / Helix users register through `reg-view*` and let the adapter wrap; `wrap-view` is exposed for code-gen and library scaffolding.

### `<adapter>/flush-views!`

- **Kind**: function
- **Signature**:
  ```clojure
  (flush-views!)
  (flush-views! f)
  ```
- **Description**: Wraps React's `act()` for tests. Drain queued state updates before assertions.

### `<adapter>/set-hiccup-emitter!`

- **Kind**: function
- **Signature**:
  ```clojure
  (set-hiccup-emitter! f)
  ```
- **Description**: Install a render-tree → HTML fn. Parity with the Reagent adapter's late-bind seam for SSR.

The UIx / Helix adapters do **not** support `reg-view` (the macro is Reagent-specific in its `defn`-shape rewriting). For UIx and Helix the app-facing lane *is* native components plus the adapter hooks — most application views need no view registration at all, because UIx and Helix components compose by Var reference like ordinary React components. `reg-view*` is the tooling/host lane here too: reach for it only when something needs registry-keyed view addressing (a tool hosting the view by id, a code-gen pipeline).

See [14 — Adapters](14-adapters.md) for the per-substrate detail.

## DOM source-coord annotations

Every adapter whose host has a DOM-attribute concept (Reagent / UIx / Helix on the browser; not Plain Atom) injects `data-rf2-source-coord="<ns>:<sym>:<line>:<col>"` on the rendered root DOM element of each registered view. The annotation is **mandatory** at the adapter contract level; it's what powers click-to-source navigation in Xray and re-frame2-pair.

Annotation is gated on `interop/debug-enabled?` (the CLJS mirror of `goog.DEBUG`). Production `:advanced` builds elide the attribute via dead-code elimination — there's no DOM-bytes cost in shipped bundles.

The JVM SSR emitter mirrors the same contract so server-rendered HTML can be clicked back to the source position before any hydration happens.

Full contract: [Spec 006 §Source-coord annotation](../../spec/006-ReactiveSubstrate.md#source-coord-annotation-mandatory) and [Spec 011 §Source-coord annotation under SSR](../../spec/011-SSR.md#source-coord-annotation-under-ssr).

## See also

- [01 — Core](01-core.md) — `dispatch`, `subscribe`, `reg-view`, and the `{:frame …}` override opt rowed in registration.
- [03 — Effects and interceptors](03-effects.md) — `with-fx-overrides` for scoping fx behaviour inside a view's event handlers.
- [14 — Adapters](14-adapters.md) — full per-substrate surface tables.
