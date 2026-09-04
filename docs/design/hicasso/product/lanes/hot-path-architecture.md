# Hot-path architecture

## Principle

Hicasso remains one interpreted Hiccup language. Native React is the explicit local boundary, not a compiler tier or hidden fast mode. Hicasso supplies a small optional native namespace so an application can cross that boundary without acquiring UIx; UIx and raw React/JavaScript remain supported peer routes. Zero to two percent native code is a plausible outcome to measure, never a quota: a small amount of source can dominate the application's work.

## The performance ladder

### 1. Ordinary Hicasso

Use interpreted Hiccup, ambient reads, data intents, controlled-input semantics, structural tests, and full diagnostics. Start every feature here.

### 2. Tune topology without changing language

Place boundaries around independently changing or expensive regions; inline helpers that always travel with the parent; stabilize keys and props; choose fine, coarse, chunked, or visible-window subscriptions to match the workload; virtualize genuinely large collections. Do not split per element mechanically because each boundary retains a React fiber, memo wrapper, and Hicasso shell.

### 3. Return a direct React element

A Hicasso `defview` can return an existing React element, built with `react/createElement` (or a `.jsx` file) or by UIx. This retains the same frame, Hicasso subscription shell, props ABI, and component identity while skipping Hiccup lowering for that boundary. It is the narrowest useful escape for a measured codec/markup hotspot.

The returned subtree follows native React rules: Hicasso intent lowering, controlled-field normalization, key diagnostics, and structural inspection do not apply. Use native props and callbacks. Keep hooks in a separately defined native component rather than making hook order depend on a Hicasso body's data paths.

### 4. Use a named native React island

Use a top-level native component behind the named host seam for a virtualizer, editor, animation surface, canvas/WebGL coordinator, retained vendor widget, or hook-intensive hot collection. The component is written in React — raw or UIx — and mounted through `h/defhost`, or `[:>]` for a one-off; it reaches Hicasso state, when it needs any, through `n/use-frame` and `n/use-sub`. JavaScript/TypeScript uses the same host bridge. Each remains under the same React root and shared re-frame2 frame context. It is not a second store or adapter root.

One-off raw elements are for migration and genuinely one-off interop; they should not be sold as a performance tier. Repeated hot crossings become a named host or native island with an explicit server policy and contract tests.

### 5. Choose a native screen only when the screen is React-shaped

An intrinsically React-first surface may implement its view tree with the Hicasso-native namespace, UIx, or raw React/JavaScript under the single installed Hicasso adapter, root, and shared frame contract. This is a local view-implementation choice, not another adapter or Hicasso mode. A substantial React-first surface may reasonably choose UIx instead of expanding Hicasso's deliberately narrow native facade.

## Owned native surface and semantic fence

The [native-boundary law](design-laws.md#native-boundary) is the single semantic owner, and the [public-language lane](ergonomics-api.md#optional-native-surface) owns the surface. This lane owns only the performance workflow, implementation implications, and acceptance evidence.

The implementation must factor the substrate-neutral React spine and direct-React frame hook before publishing the namespace if the existing seams cannot be consumed without UIx. Tests, build behavior and error quality count as product cost. Nothing in the tier rewrites interpreted Hiccup, and since `rf2-6c12m.3` nothing in it is a macro at all: `re-frame.hicasso.native` is two hooks, and an island reaches direct React construction through React's own API rather than through a Hicasso form.

## Canonical native-tier acceptance checklist

This is the only release checklist for the native tier. Other documents link here instead of maintaining variant gate lists.

**Two rows below describe a surface that no longer ships, and are marked `RETIRED` in place rather than deleted.** `rf2-6c12m.3` ruled Option A on 2026-08-29: `re-frame.hicasso.native` shrank to exactly two public hooks, `n/use-sub` and `n/use-frame`, and the `n/$` element grammar, `n/props`, `n/defcomponent` and the marker-preserving ABI helpers were deleted — none of them occurs anywhere in `implementation/` today. **A `RETIRED` row is not scored and cannot block publication**; it stays because it is the record of what the tier was once required to prove, and re-scoring it is not in the gift of this table. **What the surviving tier owes is on the live rows**, unchanged in substance and now bearing the whole of the checklist: the *Frame and store lifecycle*, *Same-root interop*, *Server and hydration*, *Dependency and rent*, *Diagnostics* and *Performance* rows each apply to the two hooks and to islands written in raw React or UIx, which is what the tier is. Marked 2026-09-04 under `rf2-aunp`; the rows' original text is unchanged beneath the marker.

| Area | Required result | Deciding evidence |
|---|---|---|
| Native-form grammar **— RETIRED** | **Retired 2026-08-29 under `rf2-6c12m.3`; not scored.** The `n/$` grammar this row required is deleted, and no direct-construction form is published: an island calls React's own API. *As written:* the provisional `n/$` grammar handles omitted and literal props, explicit `n/props` dynamic maps/objects, dynamic React-element children, trailing and nested children, keyword/string/component heads, keys and refs, SVG and custom elements, canonical-slot collisions, and source-located refusal of Hiccup/intent semantics | *Was:* macro-expansion fixtures plus client DOM and React server witnesses against handwritten `createElement`. The direct-return escape those fixtures also covered survives as rung 3 and is measured by budgets row S8, not by this row |
| Component ABI and identity **— RETIRED** | **Retired 2026-08-29 under `rf2-6c12m.3`; not scored.** There is no Hicasso-minted component to carry an ABI: `n/defcomponent`, `n/memo` and `n/lazy` are deleted, and an island's props/children ABI, identity and HMR behaviour are React's own. *As written:* one raw-JavaScript props/children ABI survives `n/defcomponent`, memo, lazy loading, ref forwarding, CLJS/JavaScript component heads, display/source metadata and HMR; there is no parallel fast ABI | *Was:* one matched component exercised through every wrapper and one HMR replacement cycle. What survives of this obligation is the hooks' behaviour across frame replacement, StrictMode and HMR, which the *Frame and store lifecycle* row owns |
| Frame and store lifecycle | `n/use-sub` and `n/use-frame` use the shared substrate implementation across no-provider failure, two frames, frame replacement, StrictMode, retry/abandonment, Suspense, Activity hide/reveal, unmount and exact cleanup | Matched Hicasso-native and UIx consumers plus a dependency check proving no UIx import |
| Same-root interop | Hicasso-native, UIx and raw React parents can render Hicasso, and Hicasso can render each native route, without another root, frame or state owner | Both embedding directions across two frames, retained callbacks, provider/compound components, errors and teardown |
| Server and hydration | Every native surface satisfies its row and declared policy in the canonical SSR/hydration matrix, including server bytes or source refusal, matching hydration, mismatch attribution and multiple roots. **Mismatch attribution is owed only on roots a re-frame2 door opens** — `h/hydrate!`, or the Spec 006 client mount entry — because `onRecoverableError` is an option of an individual root, so a root the consumer opened and adopted themselves carries no framework reporter and nothing rendered inside it is attributed ([Spec 011's hydration-mismatch detection](../../../../../spec/011-SSR.md#hydration-mismatch-detection)) | The per-surface witness ledger, [`dispositions.md` §2.1](../dispositions.md#21-surface-inventory-and-dispositions): each inventory id's *operative disposition (today)* cell names the witness namespace that decides it, and the native tier is HS-24 to HS-30 **plus HS-23's raw-React-element route**, which is native-tier conduct recorded on a declared-host row: that route carries no declaration, so no Hicasso policy reaches it and React's own server semantics govern — bytes witnessed (`client-only-arms-ssr-cljs-test`, *through-raw-react-a-visible-activity-subtree-reaches-the-response*), no hydration claim made, and only the first of [§2.4](../dispositions.md#24-the-default-rule-and-how-a-row-is-upgraded)'s five clauses is measured, so the route is **not** upgraded to Render (`rf2-fdg4w`, ruled 2026-08-15). *This clause named the `n/$` route and its witness* through-n-dollar-… *until 2026-09-04 (`rf2-aunp`). `rf2-6c12m.3` deleted `n/$`, which had handed every non-intrinsic head unchanged to `React.createElement`; the route survives as that call written directly, the deftest was renamed to match, and [`dispositions.md`](../dispositions.md#21-surface-inventory-and-dispositions) HS-23 already records it under the new spelling. The disposition and the ruling are unchanged — only the spelling of the route moved.* The [SSR/hydration matrix](react-compatibility-notes.md#public-surface-ssrhydration-matrix) is the policy this row is measured against — it states the obligation per surface class and cites no witness, so it settles what is owed and never that it is discharged |
| Dependency and rent | Interpreted-only dependency graphs and production bundles contain neither native-tier runtime nor UIx; native bundles contain no UIx unless the application imports it | Dependency-graph assertions and unique production sentinels with reachable positive controls |
| Diagnostics | Xray names and times the boundary, reports supported-hook reads, labels the inner tree opaque and adds no production evidence machinery | One causal trace taken over a native-tier subject — an island, raw React or UIx, read through `n/use-sub`, with a foreign React subtree beside it — showing the boundary named and timed, the inner tree absent from every roster, and the host-opaque links proved SUBJECT-INVARIANT; plus production-erasure proof. *The subject read `n/defcomponent` until `rf2-6c12m.3`; the trace's obligation is unchanged, only its authoring route* |
| Performance | Equivalent Hicasso-native, handwritten-React and UIx components share behavior, read shape, DOM and instrument; the Hicasso-native route meets the ratified island parity budget, and each retained escape meets the benefit threshold | Interleaved three-way client and server runs with the instrument controls from the primary performance contract |

A red row blocks publication of the native namespace. The remedy is to fix or shrink the surface; no row is waived by success on another row.

## Read-topology guidance

- Fine row reads trade mount/heap for sparse invalidation.
- A coarse view-model read trades sparse precision for cheap mount and bulk replacement.
- Chunked or visible-window reads are the useful middle for large mixed workloads.
- Large oscillating read sets are suspect because whole-set reconciliation can become proportional to the current read count.
- A pull-shaped subscription is worth a bounded experiment if it can give one invalidation unit and one retained read while preserving a declarative, colocated query. It must not grow a per-leaf ledger.

Xray should expose boundary count, reads per boundary, fan-out, and read-set churn so this is observable rather than folklore.

## Boundary substrate and hook budget

The normal shell already spends its two hooks on frame context and the external-store bridge. Optional facilities cannot add another hook to every boundary. The underlying collector can currently ride materially different subscription substrates; select that substrate before the ABI freezes using correctness, retained heap, clock, teardown, and migration evidence. It is an architectural choice, not an inherited implementation detail.

## Budget protocol

The primary [performance contract](../specification.md#6-performance-contract) owns every numeric threshold; the [evidence baseline](evidence-baseline.md) owns measured facts. Hot-path decisions apply them as follows:

- Calibrate on named low- and mid-tier hardware in Chromium, Firefox, and WebKit.
- Compare the same behavior, read shape, host crossing and DOM result, or state every deliberate capability difference.
- Attribute body, lowering, React, commit and paint pressure before selecting a remedy.
- Require topology changes and native islands to preserve DOM/intent, focus/selection, frame, hydration and cleanup contracts.
- Keep an escape only when it clears the primary's benefit threshold; otherwise remove it.
- Use deterministic blockers for correctness, scaling, residue and production erasure; distributional clocks/heaps use pinned interleaved runs with explicit estimands, populations and sabotage controls.
- Refuse a verdict from an unhealthy instrument and keep comparator, witness, hardware, revision and confidence attached.

## Xray-guided workflow

1. Name a slow interaction and reproduce it with a stable script.
2. Correlate event, changed reads, invalidated boundaries, body work, commit, and paint.
3. Tune read and boundary topology first.
4. If codec work dominates, compare direct React output in the same boundary.
5. If hooks, reconciliation, vendor behavior, or high-rate local work dominate, isolate a named native component and choose Hicasso-native, UIx, or raw React according to its needs.
6. Run behavioral parity, focus/selection, frame routing, SSR/hydration, cleanup, and performance scripts.
7. Keep the escape only if it clears a declared budget.

## Decisive experiments

- Run the canonical native-tier acceptance checklist as one controlled publication protocol rather than separate partial parity programmes.
- Direct-return delta: identical body, reads, data, and DOM with Hiccup output versus direct native React output.
- Topology tournament: fine, coarse, chunked/windowed, and native-virtualized tables at 100, 300, and 1,000 rows across sparse, bulk, reorder, and controlled-edit operations.
- Host identity: generated intent, stable handler, and native island against a memoized retaining host, including delayed callbacks and frame replacement.
- Collector substrate: like-for-like correctness, clock, retained heap, teardown, and migration comparison before runtime ABI freeze.
- One complete causal Xray slice with mutation-tested links and production erasure.

## Explicit refusals

No `:fast` flag, compiler fork, automatic promotion, profiling-dependent semantics, per-boundary callback-cell table, second state owner, or performance claim based only on render timing. Keep context-based frame propagation unless a materially larger measured result justifies weakening native-island composition.
