# Hicasso design laws

These laws define the product and constrain every implementation choice.

## React and ownership

1. Render is speculative and owns nothing durable.
2. The selected commit acquires exactly its current read/host ownership; disconnect releases it.
3. A render/commit tear is detected and corrected before visible paint.
4. React owns component identity, hooks, refs, effects, context, errors, concurrency, Suspense, Activity, hydration, commit and DOM reconciliation.
5. Hicasso has one interpreted Hiccup semantics and one React implementation. Native work crosses the explicit [native-boundary law](#native-boundary); it is not another Hiccup mode.

## State and reactivity

1. re-frame2 is the application-state owner.
2. Subscriptions are the only adapter reactive source and the re-frame2 state commit is the application write clock. React commit owns UI read/host attachment; it is not a second application write clock.
3. Application-visible ephemera has an explicit re-frame2 address. High-rate host-private mechanics may remain inside a native host; DOM-owned state is an explicit interop contract.
4. `sub` is legal during direct synchronous execution of the active Hicasso body, including ordinary helpers, branches and loops. Deferred crossings refuse.
5. Dynamic reads are reconciled from the selected render without a general per-boundary dependency ledger.
6. The two-hook boundary shell is a ceiling. Optional capabilities add no universal hook or ownership graph.
7. Demand-driven resources may reuse committed read membership; they cannot acquire during abandoned render or create a second read ledger.

## Language and interop

1. Hiccup and ordinary intent are data; executable host behavior uses explicit functions.
2. A `defview` is always a boundary and an ordinary `defn` is always inline composition.
3. One props map, stable keys, value-equality memoization and explicit controlled revision are the default laws.
4. Prevention is explicit and uniform. The same handler form never changes meaning by position.
5. Foreign React positions are declared by their real ABI: event, ReactNode/slot, render callback, ref, children and server policy. Arbitrary host data is not deeply converted.
6. Unsupported behavior fails with a stable id, source, path/position, offending value and recovery.
7. Applications get one obvious facade; optional namespaces are separately reachable and erase when absent.
8. React-library interop and SSR/hydration correctness are core contracts. Every public surface renders deterministically on the server or refuses with recovery and has explicit hydration behavior; only the deployable Node service is optional.

## Native boundary

This section is the canonical owner of the native-tier laws. Other documents classify surfaces, sequence work, or define witnesses; they link here rather than restating these laws.

1. Native React is the performance and ecosystem contract. Hicasso-native, UIx, and raw React/JavaScript are supported authoring routes; UIx is a comparator and optional toolkit, never a Hicasso dependency.
2. `[...]` always means interpreted Hiccup. `n/$` compiles only its own explicit native form and never analyzes or rewrites a `defview` body.
3. Native props, callbacks, children, keys, refs, hooks, control behavior, and errors follow React semantics. The native authoring surface may normalize only top-level prop-name spelling; Hicasso intent lowering, controlled-field repair, structural inspection, and tree diagnostics stop at the boundary.
4. Native work remains under the same React root, re-frame2 frame context, and application-state owner. `n/use-sub` and `n/use-frame` reuse the substrate-neutral React spine and existing direct-React frame hook; they neither import UIx nor fork frame, store, teardown, or error semantics.
5. Element construction, `n/defcomponent`, memo, lazy loading, refs, and both embedding directions share one props/children ABI. There is no separate “ergonomic” and “fast” ABI.
6. The native namespace is separately reachable. An interpreted-only production dependency graph and bundle contain neither native-tier runtime nor UIx code.
7. Xray may name and time the native boundary and observe reads made through supported native hooks. The inner React tree is otherwise explicitly opaque.

## Economics and scope

1. Standing cost is measured before feature-rich behavior. The do-nothing and read-free boundary are permanent controls.
2. Capability pays rent where it is used. A rare facility does not burden every boundary.
3. A core proposal needs a repeated job or centralized defect class, a paying witness, and a better result than the smallest equivalent direct-React, Hicasso-native, or established-library control.
4. Optional modules need a named consumer and zero reachability when unused. Recipes become APIs only after repeated application code proves the need.
5. No compiler/AOT mode for Hiccup, second renderer, custom React renderer, automatic specialization, signals replacement, universal callback cells or generic local-state/effect DSL. A macro for an explicitly native element form does not create an alternate Hiccup mode.

## Evidence and tools

1. Every claim names its witness, runtime, substrate, instrument, estimand and population.
2. Semantic tree, canonical DOM, intent stream, React server bytes, hydrated behavior, commit and paint are distinct equalities.
3. A gate carries a positive/sabotage control and refuses to publish when its own validity checks fail.
4. Deterministic correctness/scaling/erasure gates block changes; distributional clock and heap budgets use pinned evidence runs.
5. Tool evidence is versioned, privacy-projected, loss-accounted and absent from production.
6. Unknown, opaque, capped and uncorrelated are first-class results. Timing proximity never fabricates causality or commit.
7. A tool builds retained machinery only for a named consumer; Xray and Pair share one projection rather than duplicating evidence systems.
8. Prefer a source-located, runtime-checked declaration with loud failure before purchasing static analysis; add proof only for facts a declaration cannot establish.
