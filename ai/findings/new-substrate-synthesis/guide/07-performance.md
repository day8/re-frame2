# 07 — Performance

The performance model is mostly a list of things you *don't* do. The compiler emits what a
meticulous React engineer writes by hand — every view, every time.

*(Stage note: the compiled output and memo comparators are Stage 1; the reactive
economics below ride S2, and the CI gates that prove the production claims — bundle
absence, size budget, output shape — wire in across S3–S6.)*

## What you never write

| Habit | Here |
|---|---|
| `React.memo` / sCU | every view memoized on value-equal props, always |
| `useCallback` / stable-handler dances | handlers are vectors — values, stable by nature |
| `useMemo` / deps arrays | `effect` deps are values; templates are compiled; nothing else memoizes |
| "hoist this constant JSX" | static subtrees become module constants automatically |
| interpreter-cost worries | there is no interpreter in your bundle |
| per-subscription hook budgeting | a view has **one** React bridge no matter how many `sub`s |

Because props are CLJS values and handlers are data, value-equality memoization is
*correct*, not a heuristic — the entire manual-memoization folklore is deleted, not
taught.

## The model in one paragraph

Each queued event executes and commits its frame transition → exactly the dirty
subscriptions recompute → when the run-to-completion drain reaches quiescence, views
whose values actually changed get **one** notification for the whole drain → React
re-renders those memo boundaries in one batch → unchanged results return *identical
references*, so child comparators short-circuit and the cascade stops. A real host yield
starts a separate drain and batch. Static parts were built once at load. The profiler
exists to confirm this, not to negotiate with it.

## The little you do think about

1. **Subscription granularity.** A view reading `(sub [:orders/all])` re-renders on any
   order change; a row reading `(sub [:orders/by-id id])` re-renders on its own. Narrow
   reads on list items — the classic re-frame layering advice, unchanged.
2. **Keys.** Required anyway; correct keys are what let React reconcile lists instead of
   rebuilding them.
3. **Fn props defeat memo — and so do freshly-built children.** A raw fn compares by
   identity, and a parent that rebuilds child elements each render defeats the child's
   memo the same way (inherent to React; hand-written code pays it too). In hot lists,
   prefer data handlers, hoist fns, narrow what the parent rebuilds. The heatmap makes
   offenders visible.
4. **Broad values walk.** `rf=` short-circuits identity, so structurally-shared values are
   cheap — but a subscription that *rebuilds* a large equal collection forces walks
   downstream. Fix the producing sub (stabilize it); don't sprinkle comparators. Dev flags
   repeated hot cases.
5. **Escapes are visible costs.** `data/render` (the interpreter — wave-2, a separate
   opt-in artefact) and `ui/spread` (runtime prop conversion) are the two
   knowingly-slower spellings; they exist to be seen in profiles, not avoided in fear.

## What production builds contain

No dev checks, no source coords, no causes/manifests/histories, no interpreter, no wrapper
components — the runtime is ~4 KB gzipped over React, and each component carries only the
machinery its source implies (a props-only view is a memoized function and direct element
calls, full stop). These are CI gates — bundle scan, size budget, output-shape diff — not
intentions.

## Measuring

Dev: the Xray heatmap and causes timeline beat flame graphs for "why is this slow" —
counts, causes, and epochs, per view. Production: the React Profiler works normally (real
component names, real memo boundaries), and renders should look like the model above —
sparse, shallow, value-justified. If they don't, the timeline's cause column names the
culprit before you reach for the profiler.
