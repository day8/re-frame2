# 02 — Views

Views are where the cascade ends and pixels begin. The view layer in re-frame2 is **substrate-agnostic** — the same `reg-view` registration works against Reagent, UIx, and Helix; the same `dispatcher` and `subscriber` helpers compose across all three. The substrates differ in how they emit React calls; the re-frame2 contract sits above that and stays uniform.

This chapter covers the registration surface (`reg-view`, `reg-view*`, `view`), the substrate-agnostic ergonomic surface (`dispatcher`, `subscriber`, `with-frame`, `bound-fn`, `frame-provider`), and points at the per-adapter chapters for the substrate-specific hooks. If you want the Reagent vs UIx vs Helix conventions, see [13 — Lifecycle](13-lifecycle.md) and [14 — Adapters](14-adapters.md).

## The view registry

The view registry is what `[my-view "arg"]` resolves against. Every view you register lives in it; every view that emits hiccup ends up rendered through it. The registry is keyed by id (a keyword, conventionally `(keyword *ns* sym)` so the view's id matches its symbol).

| API | M/Fn | Signature | Status | Intuition |
|---|---|---|---|---|
| `reg-view` | M | `(reg-view sym [args] body+)` <br> `(reg-view sym docstring [args] body+)` <br> `(reg-view ^{:rf/id :explicit/id} sym [args] body+)` | v1 | The defn-shape view registration. Auto-defs the symbol, auto-derives the id from `(keyword *ns* sym)`, auto-injects `dispatch` / `subscribe` as lexical bindings, and rejects non-defn-shape bodies at macroexpand. The 80% of registrations want this form. |
| `reg-view*` | Fn | `(reg-view* id render-fn)` <br> `(reg-view* id metadata render-fn)` | v1 | The plain-fn surface beneath `reg-view`. No auto-def (the caller manages the Var or computed id), no auto-inject, no compile check. Reach for it when you need: computed ids, library-generated views, Reagent Form-3 (`create-class`), or registration without a Var. |
| `view` | Fn | `(view view-id)` → render-fn | v1 | Runtime lookup handle. Returns the **registered render-fn**, not hiccup. Use in hiccup as `[(rf/view :id) args...]` when you need to late-bind a view by id (computed id, plugin-style dispatch, dynamic chrome). |

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

In all three cases the symbol `cart-line` is `def`-ed so you can write `[cart-line item]` from sibling code. The macro also injects `dispatch` and `subscribe` as lexical bindings so you can call them without the `rf/` prefix inside the body — this matters less than it used to (the `rf/` prefix is conventional) but the seam is preserved for muscle-memory and macro composition.

### When to reach for `reg-view*`

The starred form is the right call when:

- **The id is computed.** Code-gen pipelines, plugin systems, story / variant scaffolding — anywhere the id isn't a literal symbol at the call site.
- **You don't want a Var.** Inside a `let` or a closure, or when the view is a one-off built from configuration data.
- **You're writing a Form-3 component.** Reagent's `create-class` wraps a map of lifecycle methods around a render-fn; the call shape is `(rf/reg-view* :id (r/create-class {...}))`.
- **You're consumer-side library code.** Libraries that ship registered views (a charting library, a table widget) often want to register without imposing a `def` on the consumer's namespace.

The `*` follows Clojure's own `let` / `let*`, `fn` / `fn*` idiom — the un-starred form is the macro shorthand; the starred form is the underlying primitive.

### The `view` lookup form

```clojure
[(rf/view :app/header) {:title "Cart"}]   ;; resolves the registered render-fn at render time
```

Two shapes coexist in hiccup:

- **Var form** `[my-view arg1 arg2]` — the canonical, source-readable form. Reagent / UIx / Helix all resolve a function-in-tag-position by calling it with the trailing args.
- **Lookup form** `[(rf/view :my/id) arg1 arg2]` — for late-binding by id. Same render outcome; different addressing scheme.

Bare keyword-tagged hiccup (`[:my-view "args"]`) is **removed in v2** — it was a v1 footgun that collided with HTML tag keywords. Use the Var form or the lookup form.

## The substrate-agnostic ergonomic surface

These five surfaces work the same across Reagent, UIx, and Helix. They're how views interact with the running app without being tied to any single substrate's idiom.

| API | M/Fn | Signature | Status | Intuition |
|---|---|---|---|---|
| `frame-provider` | Component (Reagent) | `[rf/frame-provider {:frame :todo} & children]` | v1 | "Children inside this provider see `:todo` as their current frame." Lexical scope for the implicit frame; nestable; pairs with `with-frame` for non-component code. |
| `with-frame` | M | `(with-frame :keyword body)` *or* `(with-frame [sym expr] body)` | v1 | "Run this code as if the current frame were `:keyword`." Two shapes — bare keyword vs let-binding — documented in [002 §with-frame](../../spec/002-Frames.md#with-frame). |
| `bound-fn` | M | `(bound-fn [args] body)` | v1 | "Capture the current dynamic-var bindings (frame, registrar, etc.) into a closure that will be invoked later, possibly after the calling stack has unwound." CLJS-only macro. Use for event-callback wiring where Promise / setTimeout / IntersectionObserver / WebSocket message handlers run outside the original render call. |
| `dispatcher` | Fn | `(dispatcher)` → `(fn [event] ...)` | v1 | "Hand me a frame-bound dispatch function I can call later." Captures the current frame at call time and returns a closure that dispatches against that frame. Safe to call during render AND from async callbacks where the dynamic-var binding has unwound. |
| `subscriber` | Fn | `(subscriber)` → `(fn [query-v] ...)` | v1 | "Hand me a frame-bound subscribe function I can call later." Companion to `dispatcher` for the subscribe side. |

### When to use `dispatcher` / `subscriber` instead of `dispatch` / `subscribe`

The verbs `dispatch` and `subscribe` read the current frame from the dynamic-var stack at call time. That's fine when the call sits *inside* the cascade — inside a render, an event handler, a sub computation. It breaks when the call sits *outside* the cascade — a Promise callback, a `setTimeout`, a WebSocket `onmessage`, an IntersectionObserver. By the time the callback fires, the dynamic-var binding has unwound, and `(rf/dispatch [::foo])` will hit the default frame (or throw, depending on the substrate).

The fix is to capture the frame *at the point you have it* and use the captured closure later:

```clojure
(rf/reg-view alert-widget []
  (let [dispatch (rf/dispatcher)]                          ;; captures NOW
    [:button {:on-click
              (fn [_]
                (.then (fetch "/notify")
                       (fn [_] (dispatch [::notified]))))} ;; runs LATER, but bound
     "Notify"]))
```

The pattern composes inside `with-frame`:

```clojure
(rf/with-frame :tool
  (let [dispatch (rf/dispatcher)]              ;; captures :tool frame
    (js/setTimeout #(dispatch [::tick]) 1000))) ;; fires :tool even after with-frame unwinds
```

`bound-fn` is the broader hammer — it captures *every* dynamic-var binding into the closure, not just the frame. Use `dispatcher` / `subscriber` for the common frame-capture case; reach for `bound-fn` when the closure needs to honour other bindings (interceptor overrides, the dev-time trace context, etc.).

### `with-frame`'s two shapes

```clojure
;; Bare keyword — most common
(rf/with-frame :todo
  (rf/dispatch [::add-item ...]))

;; Let-binding — when you want the keyword in a local
(let [f (compute-frame-id ...)]
  (rf/with-frame [chosen f]
    (rf/dispatch [::action chosen])))
```

Full semantics in [002 §with-frame](../../spec/002-Frames.md#with-frame).

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

| API | M/Fn | Signature | Status | Intuition |
|---|---|---|---|---|
| `<adapter>/adapter` | Var (map) | `{:make-state-container … :render … :dispose-adapter! …}` | v1 | The adapter spec passed to `(rf/init! ...)`. |
| `<adapter>/use-subscribe` | Fn (hook) | `(use-subscribe query-v)` / `(use-subscribe frame-kw query-v)` → value | v1 | The hook-shaped read. Matches the React/UIx/Helix idiom; there's no auto-injection — components call the hook and `(rf/dispatcher)` directly. |
| `<adapter>/use-current-frame` | Fn (hook) | `(use-current-frame)` → frame-kw | v1 | "What frame am I in?" — for components that need to thread the frame through hand-written child callbacks. |
| `<adapter>/frame-provider` | Fn (component) | `($ frame-provider {:frame :session :children […]})` | v1 | The component-shaped equivalent of Reagent's `frame-provider`. The underlying React Context (`re-frame.adapter.context`) is **shared** across all three substrates, so a mixed-substrate app's frame-provider chain composes across substrate boundaries. |
| `<adapter>/wrap-view` | Fn | `(wrap-view id metadata user-fn)` → wrapped fn | v1 | Adapter-side source-coord annotation. Most UIx / Helix users register through `reg-view*` and let the adapter wrap; `wrap-view` is exposed for code-gen and library scaffolding. |
| `<adapter>/flush-views!` | Fn | `(flush-views!)` / `(flush-views! f)` | v1 | Wraps React's `act()` for tests. Drain queued state updates before assertions. |
| `<adapter>/set-hiccup-emitter!` | Fn | `(set-hiccup-emitter! f)` | v1 | Install a render-tree → HTML fn. Parity with the Reagent adapter's late-bind seam for SSR. |

The UIx / Helix adapters do **not** support `reg-view` (the macro is Reagent-specific in its `defn`-shape rewriting). UIx and Helix users register with `rf/reg-view*` when they need registry-keyed view addressing — most don't, because UIx and Helix components compose by Var reference like ordinary React components.

See [14 — Adapters](14-adapters.md) for the per-substrate detail.

## DOM source-coord annotations

Every adapter whose host has a DOM-attribute concept (Reagent / UIx / Helix on the browser; not Plain Atom) injects `data-rf2-source-coord="<ns>:<sym>:<line>:<col>"` on the rendered root DOM element of each registered view. The annotation is **mandatory** at the adapter contract level; it's what powers click-to-source navigation in Causa and re-frame2-pair.

Annotation is gated on `interop/debug-enabled?` (the CLJS mirror of `goog.DEBUG`). Production `:advanced` builds elide the attribute via dead-code elimination — there's no DOM-bytes cost in shipped bundles.

The JVM SSR emitter mirrors the same contract so server-rendered HTML can be clicked back to the source position before any hydration happens.

Full contract: [Spec 006 §Source-coord annotation](../../spec/006-ReactiveSubstrate.md#source-coord-annotation-mandatory-rf2-z7f7--rf2-z9n1) and [Spec 011 §Source-coord annotation under SSR](../../spec/011-SSR.md#source-coord-annotation-under-ssr).

## See also

- [01 — Core](01-core.md) — `dispatch`, `subscribe`, `reg-view` rowed in registration.
- [03 — Effects and interceptors](03-effects.md) — `with-fx-overrides` for scoping fx behaviour inside a view's event handlers.
- [14 — Adapters](14-adapters.md) — full per-substrate surface tables.
