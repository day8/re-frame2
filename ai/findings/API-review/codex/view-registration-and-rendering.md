# View Registration And Rendering

Status: draft finding.

## Crowding Signal

The view surface mixes several distinct concerns:

- Reagent-specific macro authoring;
- programmatic registered views for tools;
- runtime lookup by id;
- plain UIx/Helix components and hooks;
- frame capture for plain functions and callbacks.

Current similar or confusing spellings:

- `(rf/reg-view counter [] body+)`
- `(rf/reg-view* :counter render-fn)`
- `[(rf/view :counter) args...]`
- direct Var hiccup, e.g. `[counter args...]`
- plain functions using `(rf/frame-handle)`
- UIx `defui` / Helix components with `use-subscribe`

Implementation evidence:

- `implementation/core/src/re_frame/core.cljc:627-665` implements `reg-view*`,
  `view`, and the `reg-view` macro.
- `spec/004-Views.md:171-215` says `reg-view` injects frame-bound
  `dispatch` / `subscribe`, while explicit `rf/dispatch` with `{:frame ...}`
  is the cross-frame escape hatch.
- `spec/004-Views.md:287-335` documents the plain function footgun and the
  `frame-handle` escape hatch.
- `docs/api/14-adapters.md:33-104` says UIx/Helix users use hooks and plain
  components; `reg-view` is not their normal surface.
- `docs/api/02-views.md:3-5` describes `reg-view` as substrate-agnostic, then
  later narrows UIx/Helix. That is a documentation-level crowding signal.

## Observed Use Cases

1. Reagent app views want source coordinates, frame injection, and ordinary
   Var hiccup. The tutorial and Reagent examples use `reg-view`.

2. Tool panels want registry-addressed views generated at runtime. Story and
   Xray use `reg-view*` and `(rf/view id)`.

3. Dynamic hosts want late binding by id, for example story canvases and
   workspaces that store a view id in data.

4. UIx and Helix examples use native component forms and adapter hooks; most do
   not need view registration at all.

5. Plain helper components need to remain ordinary functions. If they dispatch
   or subscribe, they capture `(rf/frame-handle)` from render.

6. Cross-frame controls need an explicit target and should use the operation
   opts map, not hidden view injection.

## Proposed Cleanup

Document two lanes:

1. Registered Reagent view lane:

```clojure
(rf/reg-view counter []
  ...)

[counter]
[(rf/view :my.ns/counter)]
```

Use `reg-view` when the user wants a registered Reagent view with injected
frame-aware locals and source-coordinate metadata.

2. Programmatic tooling lane:

```clojure
(rf/reg-view* :tool/panel render-fn)
[(rf/view :tool/panel) props]
```

Use `reg-view*` only when the caller has a computed id, generated view, Form-3
value, or tool registry need.

For UIx/Helix, teach native components plus adapter hooks as the default. They
should use `reg-view*` only if they need registry addressing.

Keep `frame-handle` out of the view registration story. It is a frame-capture
primitive for plain functions and callbacks, not another way to register or
render views.

## Why This Is Better

The current model is trying to be substrate-agnostic and Reagent-ergonomic at
the same time. Those are different promises. Reagent needs a macro because
hiccup Var calls and lexical injection are valuable. UIx and Helix already have
native component and hook idioms.

The simpler API respects host idiom instead of forcing one view abstraction to
pretend it covers every substrate. The shared re-frame2 contract remains the
dataflow: frames, subscriptions, dispatch, source metadata, and registry ids.
