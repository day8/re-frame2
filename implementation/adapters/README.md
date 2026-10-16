# Adapters

This directory groups re-frame2's substrate adapters — implementations of the substrate contract defined in [Spec 006](../../spec/006-ReactiveSubstrate.md).

> Naming. Per the corpus convention: substrate is the abstract contract; adapter is each implementation. "Reagent adapter," "UIx adapter."

## Adapters that ship today

| Directory | Adapter | Maven artefact | Target |
|---|---|---|---|
| [`reagent/`](reagent/) | Reagent adapter | `day8/re-frame2-reagent` | Reagent 2.x — the canonical CLJS reference adapter |
| [`uix/`](uix/) | UIx adapter | `day8/re-frame2-uix` | UIx 2.x — modern hooks-based React layer |

You pick one (or more) by adding the matching artefact to your `deps.edn` alongside `day8/re-frame2`. Bundle isolation is structural — the wrong adapter is absent from the classpath, not eliminated by dead-code analysis. See [Conventions §Substrate-adapter shipping convention](../../spec/Conventions.md).

## What an adapter implements

Each adapter implements the surface defined in [Spec 006 §The adapter API contract](../../spec/006-ReactiveSubstrate.md):

- required (6): `make-state-container`, `read-container`, `replace-container!`, `make-derived-value`, `render`, `render-to-string`
- optional (3): `subscribe-container`, `register-context-provider`, `flush-render!` — the core falls back (or no-ops) when these are absent. `flush-render!` is the production-grade synchronous render-commit (distinct from the `flush-views!` test helper). 3 of the 5 shipped adapter kinds install it — Reagent, UIx and Fresco (the last two through `spine/make-react-adapter`, which wires the React spine's slot unconditionally). The other 2 omit it deliberately: `plain-atom` (in core) and the SSR substrate render without a live commit, so there is nothing to flush.
- lifecycle (1): `dispose-adapter!`

An adapter is a Clojure map carrying these fns under the matching keys plus a `:kind` discriminator keyword (for example `:rf.adapter/uix`). See [`re-frame.substrate.adapter`](../core/src/re_frame/substrate/adapter.cljc) for the live contract.

Plus per-adapter ergonomics — for example the `use-sub` hook (UIx), source-coord wrapping, and the `flush-views!` test helper.

## Layout

Each adapter is its own Maven artefact with its own `deps.edn`:

```
adapters/
├── reagent/
│   ├── deps.edn              ; declares day8/re-frame2-reagent
│   ├── src/re_frame/adapter/reagent.cljs
│   └── test/...
└── uix/
    ├── deps.edn
    ├── src/re_frame/adapter/uix.cljs
    └── test/...
```

All 3 published adapters declare `day8/re-frame2 {:local/root "../../core"}`. None depend on each other.

## Where the substrate logic lives

The 3 React-shaped adapter namespaces are primarily configuration and
substrate-specific public helpers because they delegate shared mechanics into
[`re-frame.substrate.spine`](../core/src/re_frame/substrate/spine.cljs) in the
core artefact. There are 2 factory families:

- ratom family — [`make-ratom-spine`](../core/src/re_frame/substrate/spine.cljs) + [`make-ratom-adapter`](../core/src/re_frame/substrate/spine.cljs) (Reagent)
- React-hook family — [`make-react-spine`](../core/src/re_frame/substrate/spine.cljs) + [`make-react-adapter`](../core/src/re_frame/substrate/spine.cljs) (UIx here; Fresco's own substrate, at `implementation/fresco/`, uses the same pair — which is why both carry `:flush-render!`)

Each adapter file builds a config map and hands it to the appropriate factory pair. The spine carries the epoch scheduler (glitch-freedom), the container quartet, `useSyncExternalStore` hooks, source-coord wrapping, the after-render sentinel, the unmount sentinel, and the nine-/five-hook routed late-bind tables. Reading the spine ns is the fastest path to understanding how the adapters work end-to-end.

## Per-feature artefacts vs adapters

Per-feature artefacts (`schemas/`, `machines/`, `routing/`, `flows/`, `http/`, `ssr/`, `epoch/`) sit at `implementation/<name>/` — they extend re-frame2's core capabilities. Adapters here implement the substrate contract for a specific reactive layer. The 2 tiers are independent: a consumer mixes one adapter with any subset of per-feature artefacts.

See:

- [Spec 006 — Reactive substrate](../../spec/006-ReactiveSubstrate.md) for the contract.
- [Conventions §Packaging conventions](../../spec/Conventions.md) for the multi-artefact model.
- [Guide how-to — Use UIx](../../docs/core/how-to/use-uix.md) for the "choose your adapter" walkthrough.
