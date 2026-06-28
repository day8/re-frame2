# Views

## When to load

Authoring a view with `reg-view` — the function that turns subscriptions into hiccup and wires `:on-click` etc. to `dispatch`. Load this leaf for the registration shape (`reg-view` vs `reg-view*`), the auto-injected `dispatch` / `subscribe` locals, the macroexpand error contract, and the plain-Reagent-fn trap. For the frame-resolution rules a view participates in, see [frames.md](frames.md); for wrapping a stateful JS library (a chart / map / editor), see [`../../patterns/stateful-components.md`](../../patterns/stateful-components.md).

## The one mental model

A view is a **pure function of subscriptions to hiccup**. It reads state via `subscribe` and describes side effects via `dispatch` — it never mutates `app-db` directly and never holds an instance handle. `reg-view` is the **app-facing** registration: a `defn`-shape macro that registers the render fn, defs the symbol, and makes the view frame-aware.

```clojure
(rf/reg-view sym [args] body+)
(rf/reg-view sym docstring [args] body+)
(rf/reg-view ^{:rf/id :explicit/id} sym [args] body+)   ;; override the auto-derived id
```

Verified in `spec/004-Views.md` §`reg-view` is the multi-frame contract and `implementation/core/src/re_frame/core.cljc` (the `reg-view` macro). `reg-view`:

- **Auto-defs the symbol** to the wrapped (frame-aware) fn — there is no separate `(def sym (reg-view …))` step.
- **Auto-injects two lexical bindings**, `dispatch` and `subscribe`, into every call of the render fn — these are the *frame-aware* versions bound to the view's resolved frame (no `rf/` prefix needed inside the body).
- **Auto-derives the registry id** from `*ns*` + the symbol (override with `^{:rf/id …}` metadata). The id is for runtime lookup / trace / devtools; render trees use the Var.
- **Returns the id** (family-wide `reg-*` convention); the symbol def is an additional side effect.

## Canonical mini-example

```clojure
(rf/reg-view counter [label]
  (let [n @(subscribe [:counter/value])]          ;; injected `subscribe` — no rf/ prefix, frame-aware
    [:button {:on-click #(dispatch [:counter/inc])} ;; injected `dispatch`
     (str label ": " n)]))

;; Render it by Var reference in hiccup:
[counter "Count"]
```

The injected `dispatch` / `subscribe` are why a `reg-view` body needs no frame talk — the wrapper resolves the frame from the surrounding `frame-provider` via React context (CLJS) and binds the ops to it. A *plain* `(defn …)` view gets none of this (see Common gotchas).

## Calling a registered view — Var, or `view` by id

```clojure
[counter "Count"]                 ;; canonical — the auto-def'd Var
[(rf/view :counter) "Count"]      ;; lookup by id — for computed ids, cross-module, or hot-reload
```

`(rf/view :id)` returns the **wrapped** (frame-aware) fn `reg-view` produced, re-resolved on every call (so hot-reload swaps are picked up). Reach for it when the id is computed at runtime, the Var isn't in scope, or you want hot-reload re-resolution at the call site. A `view` miss returns `nil` (no error). **Bare `[:counter "Count"]`** — a keyword tag Reagent would interpret as a registered view — is **not supported in v1**; use the Var or `(rf/view …)`.

## `reg-view*` — the plain-fn escape hatch

```clojure
(rf/reg-view* :id render-fn)
(rf/reg-view* :id metadata render-fn)
```

The runtime registration primitive beneath the macro (the standard `*`-suffix idiom): a plain function, **no** auto-def, **no** auto-inject, **no** compile-time check. Use it when:

- the id is **computed** at runtime (dynamic dispatch, tooling, library code that hosts views by id);
- the body is a Reagent **Form-3** (`reagent.core/create-class`) — out of scope for the macro (see [`../../patterns/stateful-components.md`](../../patterns/stateful-components.md) for the Form-3 lifecycle shape).

Inside a `reg-view*` body there are no injected `dispatch` / `subscribe` — capture the frame explicitly with `(rf/capture-frame)` and use its `:dispatch` / `:subscribe` ops (the exact convenience the macro provides and `reg-view*` does not). See [frames.md §Carrying the frame into async callbacks](frames.md#carrying-the-frame-into-async-callbacks).

## Loading state is explicit data, not Suspense

re-frame2 does **not** use React Suspense. Loading is plain `app-db` state — a view reads a `:status` and branches: `(if loading? [spinner] [content …])`. The canonical home is Pattern-RemoteData's `:status :loading` / `:fetching`; render every legal state via Pattern-NineStates. Don't build a Suspense-equivalent (events that "throw" until data arrives) — it collides with run-to-completion drain and with state machines. See [`../../patterns/remote-data.md`](../../patterns/remote-data.md) and [`../../patterns/nine-states.md`](../../patterns/nine-states.md).

## Per-adapter spelling

The shape is identical; the registration surface differs by adapter (cross-ref the per-adapter README "Imperative escape hatch" sections and [`../../patterns/stateful-components.md`](../../patterns/stateful-components.md) §Per-adapter spelling):

| Adapter | Ordinary (Form-1) view | Lifecycle-bearing view |
|---|---|---|
| **Reagent** / **Reagent-slim** | `reg-view` (defn-shape, Form-1) | `reg-view*` + `create-class` (Form-3) |
| **UIx** | `reg-view` (plain `defui`-style fn) | same fn + `use-effect` (deps vector) |
| **Helix** | `reg-view` (plain `defnc`-style fn) | same fn + `use-effect` (deps vector first) |

UIx / Helix read a parameterised sub through the adapter's `use-subscribe` hook rather than the injected `subscribe`; only the surrounding component wrapper differs.

## Common gotchas

- **A plain `(defn …)` view raises `:rf.error/no-frame-context` under a non-default frame.** A plain Reagent fn carries no `:contextType`, so it **cannot read the surrounding `frame-provider`'s frame** — its ambient `rf/subscribe` / `rf/dispatch` resolve to nil and fail loud (EP-0002 — no `:rf/default` fall-through; `:recovery :supply-frame`). The fix is `reg-view` (picks up the frame via the injected wiring) or capturing a `(rf/capture-frame)` at render time. See [frames.md §The merged `frame-provider` in views](frames.md#the-merged-frame-provider-in-views). (The old `:rf.warning/plain-fn-under-non-default-frame-once` warning is superseded by this error.)
- **`reg-view` rejects non-defn-shape bodies at macroexpand.** The second arg (after an optional docstring) MUST be the args vector. A Var reference (`(reg-view foo my-render)`), a Form-3 `create-class` list, or a computed body throws a stable compile-time error pointing at `reg-view*` — register those through `reg-view*` instead.
- **Don't `(def sym (reg-view …))`.** The macro already defs the symbol; `reg-view` *returns the id*, so wrapping it in a `def` binds the symbol to the id string, not the view fn. Just `(rf/reg-view sym [args] …)`.
- **`reg-view` injects `dispatch` / `subscribe` — drop the `rf/` prefix inside the body.** The injected locals are frame-aware; a `rf/subscribe` (the ambient global) inside a `reg-view` body bypasses the injection and falls back to the frame-resolution chain. Inside `reg-view*` there is no injection — capture a `capture-frame`.
- **Views are pure; no `app-db` writes, no instance handles.** State writes live in `reg-event` handlers reached via `dispatch`. A view that wraps a stateful JS library keeps the instance in a per-mount closure cell, never in `app-db` — see [`../../patterns/stateful-components.md`](../../patterns/stateful-components.md).
- **Reusable widgets take an id, not a data map.** A `[customer-card 42]` view subscribes in terms of the id; passing the whole entity map defeats the sub-cache. See [`../../patterns/reusable-components.md`](../../patterns/reusable-components.md).
- **Add a `:data-testid` so the view is walk-testable.** Use the `h/testid` authoring helper at call sites that need a test handle — see [`../cross-cutting/testing.md` §Asserting the view](../cross-cutting/testing.md).

## Deeper material

Full render-tree contract, the `:render-key` tuple, the two registration lanes, Form-1/2/3 in detail, the anonymous-fallback shape for plain fns: `SKILL-REDIRECT.md` → **EP — Views (004)**, **EP — Frames (002)** §What `reg-view` injects, **EP — Reactive substrate (006)** §Source-coord annotation.

---

*Derived from `spec/004-Views.md` and `implementation/core/src/re_frame/core.cljc` (the `reg-view` / `reg-view*` surface). Citations are symbol-level; re-verify symbol homes after view-registration or adapter-wrapper changes.*
