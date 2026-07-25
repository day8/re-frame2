# re-frame.ui.react

!!! warning "Donor code — `re-frame.ui` is not published, and there is no coordinate to add"

    `day8/re-frame2-ui` is **not** a Maven coordinate and never will be. The
    compiled-view substrate is donor-only code being absorbed into
    [Freehand](../core/freehand/index.md); the standalone artefact is deleted at
    the EP-0036 F6e gate. The [release process](../release-process.md#policy) is
    normative on that, and the artefact is absent from the release workflow's
    deploy matrix. **There is nothing here you can put in a `deps.edn`.**

    This page is retained as the contract record for view code an app *already*
    carries on the substrate — the audience of the
    [`re-frame2-ui` skill](../skills/re-frame2-ui.md). New view work starts at
    [Freehand](../core/freehand/index.md); its host-interop story is
    [Freehand host boundaries](../core/freehand/host-boundaries.md).

`re-frame.ui.react` is the **frozen React-interop tier** of the compiled-view
substrate — a deliberately tiny, closed set of
six wrappers for views that must participate in a *foreign* React world: an exported
`defview` living inside a legacy parent, or a foreign widget embedded inside a `defview`
whose API demands contexts, ids, effects, or code-splitting.

The everyday DOM-node ref is **not** here — it is the substrate-native
[`re-frame.ui/ref`](re-frame.ui.md#ref) (promoted out of this tier, rf2-u53yy.9; its
lowering target stays the `re-frame.ui.hooks/use-ref` runtime fn).

It is **not** a second state or reactivity model. `use-state` is `local`;
`use-memo`/`use-callback` are the compiler's job (per-site identity is owned for you);
`use-reducer`/`use-sync-external-store`/`use-transition`/`use-deferred-value` and
Suspense-as-authoring do not exist. Full call shapes and host behaviour live in
Spec 004 §The React interop tier.

```clojure
(:require [re-frame.ui :as ui :refer [defview local]]
          [re-frame.ui.react :as react])
```

**The position law.** The five host hooks (`use-effect` … `use-id`) — and the substrate
[`ui/ref`](re-frame.ui.md#ref) — are recognised and
lowered by the compiler exactly like `sub`/`local`/`effect`, and are legal **only where
they evaluate unconditionally, exactly once per render** — the straight-line top of a
view body (an outer `let` binding), never inside a branch, a loop, or a callback.
React's hook order must be static; a misplaced hook is a compile error. Adding, removing,
or reordering any wrapper changes the view's hook signature (a clean remount); editing a
deps vector or an effect body preserves state.

## Host hooks

### `use-effect`

`(react/use-effect setup)` / `(react/use-effect setup deps)` → nil (`useEffect`, passive,
after paint). `setup` is a zero-arg fn returning a cleanup fn or nil, honoured on dep
change, disconnect, and unmount; StrictMode dev replay is expected and must be
idempotent-safe. The one-arg form runs after every commit; a `deps` vector is compared
by **`rf=`** (VALUE equality) against the previous render's deps — the one equality
doctrine shared with the native `effect` and the memo/prop comparator, never a second
per-tier regime. A distinct-but-value-equal CLJS deps value is **not** a change (it does
not rerun); a value-different one does; host/foreign values fall through to identity. `[]`
deps ⇒ connect-only. Effects do not run on the JVM (capability metadata only).

### `use-layout-effect`

`(react/use-layout-effect setup)` / `(… setup deps)` → nil (`useLayoutEffect`, after DOM
mutation, **before paint**) for measure-then-mutate work that would flicker under passive
timing — the native component-library measure-before-paint door
([recipe](../core/how-to/measure-before-paint.md)). Same setup/cleanup/`rf=`-value-deps
contract as `use-effect`.

### `use-effect-event`

`(react/use-effect-event f)` → fn (`useEffectEvent`, React 19.2). The returned fn's body
always sees the **latest** render's values; its identity is **not stable** (React
allocates a fresh function every render — do not rely on stability, and do not put it in a
deps vector). It must not be called during render (host throws). App intent still goes
through event vectors / `ui/event`; this is for the foreign pattern of a latest-values
callback invoked from an effect. On the JVM the returned fn raises `:rf.error/jvm-host-op`
if invoked.

### `use-context`

`(react/use-context ctx)` → the context's current value (`useContext`). `ctx` is a
**foreign** Context object (`React.createContext` in foreign code); the value is handed
through uncoerced. Internal state never rides React context — frames have
`frame-root`/`frame-provider`, app state has `sub`. On the JVM it resolves a
test-provided value or fails loud.

### `use-id`

`(react/use-id)` → string (`useId`): a host-generated tree-positional token prefixed by
the root's `identifierPrefix`. Determinism under SSR rides the root contract. On the JVM
it is a deterministic inert string — safe for static roots; a hydrating root is a mismatch
risk.

## Code-splitting

### `lazy`

`(react/lazy load-thunk)` / `(react/lazy load-thunk {:fallback tpl})` — **def-level only**.
`React.lazy` over a foreign component-loading `load-thunk` (a zero-arg fn returning a
Promise of a foreign component). The result is a foreign component reference, legal
exactly where foreign heads are: `[HeavyChart {…}]`. A `{:fallback tpl}` declares
capability-free per-site loading content the emitter renders inside a minimal host
Suspense boundary. Code-splitting interop for *foreign* components — **not** a
loading-orchestration surface (loading state is explicit application state via `sub`).
Calling `react/lazy` inside a view body is a compile error. On the JVM it renders its
declared fallback (else nothing) and never invokes the thunk.

## See also

- [Measure before paint](../core/how-to/measure-before-paint.md) — the sanctioned
  `ui/ref` + `use-layout-effect` recipe for component-library geometry.
- [`re-frame.ui`](re-frame.ui.md) — the compiled-view surface these wrappers live inside.
- Spec 004 §The React interop tier — the full normative contract.
