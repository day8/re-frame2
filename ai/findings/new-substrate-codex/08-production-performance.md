# Production performance contract

## “Exceptionally efficient” has a definition

The target is not “faster than Reagent in a demo.” It is:

1. Direct literal UI should be within measurement noise of hand-written React JSX-runtime code.
2. re-frame2 integration cost should scale with real dependencies, not with wrapper artefacts.
3. One settled framework transaction should schedule a given component at most once.
4. Debugging should add zero production code, data, attributes, or allocations after advanced compilation.
5. Unsupported dynamic behavior should be explicit rather than silently moving a view onto a slower runtime.

All numeric thresholds below are prototype gates, not measured results. They are falsifiable targets.

## Work removed by construction

### Browser bundle absence roster

The core production bundle contains none of these:

- Hiccup vector walker;
- runtime tag parser or selector regex;
- generic attr-map camelizer;
- arbitrary child sequence flattener;
- Form-1/Form-2/Form-3 detector;
- Reagent reaction or render scheduler;
- per-subscription Hook wrapper layer;
- compatibility namespaces or warning stubs;
- source-coordinate tree walker/element cloner;
- runtime props schema engine when assertions are elided;
- Xray manifests, histories, source strings, or trace construction;
- SSR HTML renderer or `react-dom/server` in a browser entry;
- proxy/signal/atom/form/query-cache runtime.

Bundle inspection enforces absence. Tree shaking is not accepted as an assumption.

### Compile-time work

The macro process pays for:

- tag and DOM prop conversion;
- template normalization;
- props and Hook analysis;
- site assignment;
- source manifest creation;
- static subtree detection;
- dual host code generation.

Those costs affect builds, not user interactions. Compiler caches should keep incremental rebuilds fast, but build-time optimization never justifies moving work back into render.

## Production paths

### Pure view

```text
React calls component
  → direct JS prop reads
  → application value computation
  → direct jsx/jsxs calls
```

Runtime substrate overhead: generated prop comparator on parent renders and no ViewCell.

### Reactive view

```text
React calls component
  → one ViewCell snapshot Hook
  → one compact render capture allocation
  → N fixed-index subscription reads
  → direct jsx/jsxs calls
  → one layout reconciliation on commit
```

N is the number of actual derivation dependencies. React-facing Hook/subscriber count is one.

### Event update

```text
stable DOM callback
  → evaluate event from committed slots
  → re-frame2 event drain
  → derivations recompute once per graph node/epoch
  → affected cells enter one dirty set
  → each dirty cell advances once
  → React renders affected components
```

No callback recreation, per-read force update, Reagent render queue, or timed debounce occurs.

## Cost model

Let:

- `V` be committed reactive view instances;
- `Dᵥ` be active subscription sites in view `v`;
- `Eᵥ` be event sites in `v`;
- `Lᵥ` be active resource lease sites in `v`;
- `C` be cells affected by one re-frame2 epoch;
- `Kᵥ` be changed derivation sites in an affected cell.

Steady mounted memory is:

```text
O(V) ViewCells
+ O(ΣDᵥ) real derivation leases
+ O(ΣEᵥ) stable callback slots
+ O(ΣLᵥ) real resource owners
```

An update epoch schedules:

```text
O(changed derivation graph)
+ O(Σ Kᵥ) constant dirty marks
+ O(C) ViewCell revision/notification work
+ React work for C affected view roots
```

It does not schedule `ΣKᵥ` component renders. Reads remain linear in dependencies because eliminating real data dependencies would be dishonest.

Per reactive render allocates one compact capture plus React element/prop objects that dynamic output requires. It does not allocate one Hook closure/ref/deps array/watch key per subscription.

## Query stability

Fresh Clojure literals are a subtle hot-path cost because React dependency arrays use identity and the current sub cache warns about equal-but-fresh nonprimitive arguments. The compiler removes the normal cases:

- fully literal queries are module constants;
- each parameterized site retains its prior query while args are `rf=`;
- site values retain their prior exact reference while results are `rf=`;
- direct event functions retain identity while their committed slots change.

The goal is a stable identity pipeline:

```text
unchanged app fact
  → unchanged derivation publication
  → unchanged site value identity
  → unchanged child prop identity/value
  → generated comparator skips child render
```

## Static output

The compiler hoists proven-static React elements, prop objects, and child arrays. It also selects `jsx` for zero/one-child nodes and `jsxs` for multiple static children.

Hoisting is local and sound. The compiler does not attempt to cache a subtree that contains a ref, event slot, context read, key-dependent list item, source-instance annotation, or foreign value it cannot classify.

Development output may intentionally hoist less because source and instance evidence matters more than dev microbenchmarks.

Hydrating roots with `ui/client-only` sites perform one intentional root phase update after the matching fallback commits. There is no per-site effect or mismatch recovery. Non-hydrating roots start in client mode and pay no phase update; the compiler report counts client-only context reads as an explicit boundary cost.

## Props

Internal view calls emit JS props directly. Component entry performs direct slot reads. The generated comparator is straight-line and allocation-free.

The default `rf=` comparator is chosen for Clojure ergonomics and subscription semantics. It short-circuits identity, so persistent values following structural-sharing discipline are cheap. A fresh large-but-equal collection can require a walk; development diagnostics identify repeated hot cases. The remedy is to stabilize the producing subscription/value, not to add arbitrary comparator functions throughout the view tree.

Foreign components use React/JS identity semantics at their boundary. `ui/spread` performs an explicit runtime map walk and is counted in compiler cost reports.

## Forms and high-frequency input

A generic substrate cannot make every keystroke cheap if the application intentionally sends every draft through a broad app-db projection. The guide uses three rules:

1. Committed form data that handlers, schemas, tools, or replay need belongs in app-db.
2. Uncommitted IME composition and DOM-only mechanics stay uncontrolled/local.
3. Field and validation views subscribe to narrow projections, not an entire form map when avoidable.

The event compiler removes handler closure churn. Epoch coalescing removes duplicate renders from one input event. It does not hide an application subscription that recomputes the world.

## Lists

The compiler's direct JS array `for` path avoids lazy-seq realization inside React and makes keys mandatory. The performance strategy for large lists remains:

- stable semantic keys;
- child views that receive IDs or stable item values;
- leaf-level subscription reads when independent updates matter;
- a registered derived subscription for shared filtering/sorting;
- virtualization for collections whose DOM itself is too large.

The substrate will not ship a virtualizer. It should interoperate cleanly with one as a foreign React component.

## Scheduling and priority

re-frame2 mutations are external-store updates. React's `useSyncExternalStore` documentation notes that non-blocking Transitions cannot be relied on for mutable external-store changes. The substrate therefore does not wrap app-db updates in `startTransition` or pretend to assign scheduler priority to individual subscriptions.

It performs exact work reduction instead:

- derivations settle coherently;
- equal results do not notify;
- cells coalesce once per epoch;
- React sees cached snapshots;
- application code may use React-local deferred presentation for a genuinely local expensive view.

`flush-render!` exists for deterministic tooling boundaries, not normal scheduling.

## Provisional benchmark gates

### B-1: Direct render parity

For equivalent production components containing representative DOM props, conditionals, fragments, and lists, median and p95 render CPU for a pure `defview` must be within 10% of hand-written CLJS calling `react/jsx-runtime` directly. Generated output is inspected alongside the timing.

Failure means the compiler or props ABI is adding work to the pure path.

### B-2: AOT peer comparison

The same fixtures compare against current UIx AOT and Helix DOM constructors. A pure view should be no slower than UIx AOT outside a 5% noise band. A reactive one-read view may pay the one ViewCell boundary, but must not regress update p95 by more than 15% versus an equivalent correct `useSyncExternalStore` integration.

### B-3: Multi-read scaling

For components with 1, 4, 8, and 16 subscription sites:

- React external-store Hook count remains one;
- one epoch invokes the component at most once;
- notification count remains one;
- additional CPU is attributable to the extra derivation reads/reconciliation, not repeated Hook scaffolding.

Compare to the current UIx/Helix adapter to quantify removed per-read refs, callbacks, deps arrays, and watch operations.

### B-4: Equality no-op

An event that changes app-db but leaves a view's derived results `rf=` produces:

- zero ViewCell revision changes;
- zero React renders for that cell;
- stable site value references on a later unrelated parent render.

### B-5: Epoch fan-in

If eight dependencies of one view change in one event, the view receives one revision and one render. If eight events run in separately committed epochs, eight updates are allowed; the substrate does not merge distinct transactions by time.

### B-6: Abandonment and disposal

After 10,000 deliberately suspended/abandoned first renders:

- subscription cache owner/ref counts return to baseline;
- resource owner indexes return to baseline;
- mounted view indexes return to baseline;
- no growing warning/handler registry remains.

After 10,000 mount/unmount cycles, retained cell/lease count is zero and heap growth after forced GC is within harness noise.

After 10,000 Activity hide/reveal cycles, hidden trees have zero live subscription/resource owners, revealed trees settle to exactly one owner per site, and preserved local state/instance identity remains correct.

### B-7: Input latency

A representative text field with narrow app-db state measures event-to-commit latency against UIx and hand-written React on the same browser/hardware. The new path must be within 10% of hand-written React at p95 and show one framework/React commit per input epoch.

IME composition and caret fixtures verify correctness before speed.

### B-8: List updates

In a keyed 1,000-row list:

- changing one entity renders its affected row and any genuinely dependent aggregate, not all rows;
- parent filtering produces the expected list work but stable unchanged rows skip;
- event function identities remain stable;
- no lazy sequence is retained after commit.

Virtualized 10,000-row interop is a separate fixture.

### B-9: Bundle size

Build equivalent minimal, counter, dashboard, forms, foreign-component, and SSR client entries under Closure `:advanced`.

Measure incremental gzip/brotli bytes after subtracting identical React and re-frame2 core chunks. The proposed substrate should meet both relative targets:

- no more than 60% of UIx core+DOM+current re-frame2 UIx-adapter incremental bytes for the counter/dashboard fixtures;
- no more than 40% of reagent-slim+adapter incremental bytes for the same compiled-source feature set.

These are prototype kill gates, not forecasts. If shared chunk subtraction is unstable, use module metafiles and symbol reachability as the primary evidence.

### B-10: Debug erasure

Production output is scanned for every item in the absence roster and the [debug roster](06-debugging-and-observability.md#production-elision-proof). The gate is exact absence, not a small timing delta.

## Benchmark methodology

### Fixtures

Every substrate implements identical visible behavior and state transitions:

- static tree;
- counter;
- conditional multi-sub view;
- fan-in dashboard;
- keyed entity list;
- controlled/uncontrolled form fields;
- resource lease mount/retarget/unmount;
- foreign chart/virtualizer boundary;
- SSR/hydration tree.

No fixture may give one substrate precomputed props while another performs the derivation inside render.

### Modes

- optimized production build with debug defines false;
- development build for separate DX overhead measurements;
- cold mount and warmed steady state;
- Chrome stable pinned in CI plus one Firefox/WebKit smoke lane;
- foreground and headless/background flush fixtures;
- Strict Mode correctness runs separated from production timing.

### Measurements

- bundle module bytes and reachable symbols;
- component invocation and commit counts;
- event-to-paint and event-to-commit latency;
- JS CPU profiles;
- allocation/retained heap;
- subscription cache hits, nodes, owners, and churn;
- ViewCell dirty marks, revisions, and notifications;
- React Performance tracks/Profiler where instrumentation does not distort the production path.

Report distributions, hardware/browser versions, warm-up, sample counts, and confidence/noise bands. Do not publish a single fastest run.

## Failure interpretation

| Failed gate | Likely response |
|---|---|
| Pure render parity | Inspect emitted JS/prop ABI; remove helper calls or allocations. Do not blame React. |
| Multi-read scaling | Profile capture/reconcile arrays and observation port; keep one-hook invariant. |
| Bundle target | Inspect reachable symbols and split optional interop/dev code; do not add hand-maintained minified forks. |
| Input latency | Separate event router, derivation, and React time; narrow fixture subscriptions; verify sync semantics. |
| Abandonment leak | Stop release-ledger patching and restore commit-only ownership invariant. |
| SSR parity | Fix shared AST/codegen; never add mismatch suppression. |
| Debug erasure | Move the entire data/control path behind compile-time branches and add symbol/string gates. |

## Honest comparison baseline

The local reagent-slim rationale estimates meaningful bundle reduction and development wins but only a marginal production runtime improvement because it retains Reagent semantics. UIx already compiles normal DOM forms close to plain JS, so beating UIx's pure render materially is neither expected nor a useful claim.

The new substrate's credible production wins are elsewhere:

- smaller framework-specific runtime by absence;
- no client tree interpreter;
- direct internal props ABI;
- one React bridge per view instead of per read;
- stable event callbacks without author memoization;
- exact epoch coalescing;
- identity stabilization across subscription and prop boundaries;
- debug code proven absent.

If benchmarks show those mechanisms do not produce the target differences, the design documents must be revised before the library is described as exceptional.
