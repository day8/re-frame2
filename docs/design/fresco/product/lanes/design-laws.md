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
7. Reads never acquire or release resources. A subscription is a passive read that never fetches; an explicit event, route or machine cause owns the fetch, and resource ownership is written rather than inferred from read liveness. (*This law read* "Demand-driven resources may reuse committed read membership; they cannot acquire during abandoned render or create a second read ledger" *until 2026-08-16, `rf2-h3tke`.* `rf2-hic-050` applied the frozen criteria to the typeahead witness and returned **STOP**, so committed-read demand is not adopted and nothing implements it — [`rf2-hic-054`'s async-resource recipes](../async-routing-recipes.md) are the standing answer. The [criteria](../resource-demand-criteria.md), the [witness](../resource-demand-witness.md) and the [verdict](../resource-demand-verdict.md) each cite *"design law State 7"* as the governing law and mean the prior reading, which is why it is quoted here rather than deleted.)

## Language and interop

1. Hiccup and ordinary intent are data; executable host behavior uses explicit functions.
2. A `defview` is always a boundary and an ordinary `defn` is always inline composition.
3. One props map, stable keys, value-equality memoization and explicit controlled revision are the default laws.
4. Prevention is explicit at every position except `:on-submit`, whose data spelling auto-prevents; no second auto-preventing position may be added. Ownership and prevention do not vary by position: a callback owns its own event everywhere and is never auto-prevented. The contract the `h/event` carrier is read under *does* vary, and never silently — HD-024 tabulates event, as-declared and render, and an intent dispatched at a render position refuses at that position. (This law read *"the same function form never changes meaning by position"* until 2026-08-15, `rf2-0fd3b`.)
5. Foreign React positions are declared by their real ABI: event, ReactNode/slot, render callback, ref, children and server policy. Arbitrary host data is not deeply converted.
6. Unsupported behavior fails with a stable id, source, path/position, offending value and recovery.
7. Applications get one obvious facade; optional namespaces are separately reachable and erase when absent.
8. React-library interop and SSR/hydration correctness are core contracts. Every public surface renders deterministically on the server or refuses with recovery and has explicit hydration behavior; only the deployable Node service is optional.

## Native boundary

This section is the canonical owner of the native-boundary laws. Other documents classify surfaces, sequence work, or define witnesses; they link here rather than restating these laws.

1. Native React is the performance and ecosystem contract. UIx and raw React/JavaScript are the supported authoring routes for an island; UIx is a comparator and optional toolkit, never a Hicasso dependency. (*This law listed* "Hicasso-native" *as a third route until 2026-08-29, `rf2-6c12m.3`, which retired that route.*)
2. `[...]` always means interpreted Hiccup. A React element is never interpreted; it passes through unchanged, and nothing analyzes or rewrites a `defview` body. (*This law read* "`n/$` compiles only its own explicit native form and never analyzes or rewrites a `defview` body" *until 2026-08-29, `rf2-6c12m.3`, which deleted `n/$`.*)
3. Native props, callbacks, children, keys, refs, hooks, control behavior, and errors follow React semantics. The native authoring surface may normalize only top-level prop-name spelling; Hicasso intent lowering, controlled-field repair, structural inspection, and tree diagnostics stop at the boundary.
4. Native work remains under the same React root, re-frame2 frame context, and application-state owner. `n/use-sub` and `n/use-frame` reuse the substrate-neutral React spine and existing direct-React frame hook; they neither import UIx nor fork frame, store, teardown, or error semantics.
5. Element construction, memo, lazy loading, refs, and both embedding directions share one props/children ABI, and it is React's own. There are no ABI helpers, and no separate “ergonomic” and “fast” ABI. (*This law listed `n/defcomponent` among the sharers, and the ABI helpers that preserved a tier marker through memo and lazy loading, until 2026-08-29, `rf2-6c12m.3`.*)
6. The hooks namespace, `re-frame.hicasso.native`, is separately reachable and holds exactly `n/use-sub` and `n/use-frame`. An interpreted-only production dependency graph and bundle contain neither it nor UIx code. (*This law read* "The native namespace is separately reachable. An interpreted-only production dependency graph and bundle contain neither native-tier runtime nor UIx code" *until 2026-08-29, `rf2-6c12m.3`.*)
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
