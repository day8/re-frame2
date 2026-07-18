# 10 — Performance

The performance model is mostly a list of things you *do not* do. The compiler emits
what a meticulous React engineer writes by hand — every view, every time.

## What you never write

| Habit | Here |
|---|---|
| `React.memo` / sCU | every view memoised on value-equal props, always |
| `useCallback` / stable-handler dances | handlers are vectors — values, stable by nature |
| `useMemo` / deps arrays | `effect` deps are values *(lands S3)*; templates are compiled; nothing else memoises |
| "hoist this constant JSX" | static subtrees become module constants automatically |
| interpreter-cost worries | there is no interpreter in your bundle |
| per-subscription hook budgeting | a view has **one** React bridge no matter how many `sub`s |

Because props are CLJS values and handlers are data, value-equality memoisation is
*correct*, not a heuristic — the entire manual-memoisation folklore is deleted, not
taught.

## The model in one paragraph

Each queued event executes and commits its frame transition → exactly the dirty
subscriptions recompute → views whose values actually changed get **one** notification
for the pending render batch, which closes at the next host checkpoint → React re-renders
those memo boundaries in one batch → unchanged results return *identical references*, so
child comparators short-circuit and the cascade stops. A whole drain always lands in one
batch; back-to-back drains with nothing yielding between them may share it, and a real
host yield is what forces a new one. Static parts were built once at load. The profiler
exists to confirm this, not to negotiate with it.

## The little you do think about

1. **Subscription granularity.** A view reading `(sub [:orders/all])` re-renders on
   any order change; a row reading `(sub [:orders/by-id id])` re-renders on its own.
   Narrow reads on list items — classic re-frame layering, unchanged.
2. **Keys.** Required anyway; correct keys let React reconcile lists instead of
   rebuilding them.
3. **Fn props defeat memo — and so do freshly-built children.** A raw fn compares by
   identity, and a parent that rebuilds child elements each render defeats the
   child's memo the same way. In hot lists, prefer data handlers, hoist fns, narrow
   what the parent rebuilds. The S3 cause vector on the existing Xray Views row names
   the identity-caused render.
4. **Broad values walk.** `rf=` short-circuits identity, so structurally-shared values
   are cheap — but a subscription that *rebuilds* a large equal collection forces
   walks downstream. Fix the producing sub; do not sprinkle comparators.
5. **Escapes are visible costs.** `data/render` (wave-2 interpreter) and `ui/spread`
   (runtime prop conversion) are the two knowingly-slower spellings; they exist to be
   seen in profiles, not avoided in fear.

## What production builds contain

No dev checks, no source coords, no causes/manifests/histories, no interpreter, no
wrapper components — the kernel budget is ≤ 4 KB gzipped over React, and each
component carries only the machinery its source implies (a props-only view is a
memoised function and direct element calls, full stop). These are CI gates — bundle
scan, size budget, output-shape diff — not intentions.

## Measuring

**Dev:** cause vectors and cause chips on Xray's existing Views rows *(land S3)* answer
"why is this slow" per view. S3 adds neither a causes-timeline lane nor a heatmap; a
heatmap remains conditional on a post-S3 information-architecture review.

**Production:** the React Profiler works normally (real component names, real memo
boundaries). Renders should look like the model above — sparse, shallow,
value-justified. If they do not, the existing Views row's cause vector names the
culprit before you reach for the profiler.

Measure your own build the way the library measures itself:

```bash
npx shadow-cljs run shadow.cljs.build-report <build-id> report.html
```

The library's contribution should read as the kernel plus your compiled views and
nothing else. If your app dragged the dev tier in, the report shows it.

Why these economics hold mechanically: [12](12-how-it-works.md).
