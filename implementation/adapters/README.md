# Adapters

This directory groups re-frame2's **substrate adapters** — implementations of the substrate contract defined in [Spec 006](../../spec/006-ReactiveSubstrate.md).

> **Naming.** Per the corpus convention: *substrate* is the abstract contract; *adapter* is each implementation. "Reagent adapter," "UIx adapter," "Helix adapter."

## Adapters that ship today

| Directory | Adapter | Maven artefact | Target |
|---|---|---|---|
| [`reagent/`](reagent/) | Reagent adapter | `day8/re-frame2-reagent` | Reagent 2.x — the canonical CLJS reference adapter |
| [`uix/`](uix/) | UIx adapter | `day8/re-frame2-uix` | UIx 2.x — modern hooks-based React layer |
| [`helix/`](helix/) | Helix adapter | `day8/re-frame2-helix` | Helix 0.2.x — minimal React wrapper |
| [`reagent-slim/`](reagent-slim/) | Reagent-slim adapter | `day8/reagent-slim` [^slim-coord] | Reagent rewrite for React 19 — Stage 4 landed (full `reagent2.*` rewrite) |

[^slim-coord]: The `re-frame2-` prefix is dropped on this coord (per IMPL-SPEC DECISION-1); it is the lone adapter artefact published as `day8/reagent-slim` rather than `day8/re-frame2-*`.

A consumer picks one (or more) by adding the matching artefact to their `deps.edn` alongside `day8/re-frame2`. Bundle isolation is **structural** — the wrong adapter is absent from the classpath, not eliminated by dead-code analysis. See [Conventions §Substrate-adapter shipping convention](../../spec/Conventions.md).

## Local-test-only adapter

| Directory | Adapter | Coordinate | Target |
|---|---|---|---|
| [`test-react/`](test-react/) | Test-React adapter | **none — local-test-only** | Pure-CLJC React class-3 lifecycle simulator — unit-testable lifecycle bug catching; carries ported regressions incl. the organic rf2-4l7t2 sync-unmount-during-render repro (rf2-gqyqv skeleton, broadened rf2-n2cuo) |

The **test-react** adapter is **not a published artefact**. It is a development/test fixture only: it has no Maven coordinate, no `:clein/build` descriptor, and is absent from the lockstep array, the release deploy matrix, and the CI JVM job set by design. Consumers never depend on it; it exists solely so the project's own unit tests can simulate React class-3 lifecycle on the JVM and Node-CLJS without a browser. Bundle isolation treats it as test-only — no example or production build should ever pull it in.

The `reagent-slim` adapter has landed Stage 4 (the full `reagent2.*` rewrite — reactive primitives, render scheduler, hiccup translation, and pure-CLJS render-to-string). It now carries its own CLJS test suite (substrate-shape contract, container round-trip, derived-value tracking, the disposal-MUST list, render Root-API call sequence, and source-coord / view-id stamping under the slim adapter) alongside the `reagent2.*` internals tests.

## What an adapter implements

Each adapter implements the surface defined in [Spec 006 §The adapter API contract](../../spec/006-ReactiveSubstrate.md):

- **Required (6):** `make-state-container`, `read-container`, `replace-container!`, `make-derived-value`, `render`, `render-to-string`.
- **Optional (3):** `subscribe-container`, `register-context-provider`, `flush-render!` — the core falls back (or no-ops) when these are absent. `flush-render!` is the production-grade synchronous render-commit (distinct from the `flush-views!` test helper), implemented by all four React-shaped adapters.
- **Lifecycle (1):** `dispose-adapter!`.

An adapter is a Clojure map carrying these fns under the matching keys plus a `:kind` discriminator keyword (e.g. `:rf.adapter/reagent-slim`). See [`re-frame.substrate.adapter`](../core/src/re_frame/substrate/adapter.cljc) for the live contract.

Plus per-adapter ergonomics — e.g. `use-subscribe` hook (UIx, Helix), source-coord wrapping, `flush-views!` test helper.

## Layout

Each adapter is its own Maven artefact with its own `deps.edn`:

```
adapters/
├── reagent/
│   ├── deps.edn              ; declares day8/re-frame2-reagent
│   ├── src/re_frame/adapter/reagent.cljs
│   └── test/...
├── uix/
│   ├── deps.edn
│   ├── src/re_frame/adapter/uix.cljs
│   └── test/...
├── helix/
│   ├── deps.edn
│   ├── src/re_frame/adapter/helix.cljs
│   └── test/...
├── reagent-slim/
│   ├── deps.edn              ; declares day8/reagent-slim (no re-frame2- prefix per DECISION-1)
│   ├── src/reagent2/...      ; the slim Reagent rewrite (Stage 4: full reagent2.* rewrite)
│   ├── src/re_frame/adapter/reagent_slim.cljs
│   └── test/...
└── test-react/              ; local-test-only — NOT published (no Maven coord, no :clein/build)
    ├── deps.edn              ; depends on core via :local/root; carries no publish descriptor
    ├── src/re_frame/adapter/test_react.cljc  ; pure-CLJC class-3 lifecycle simulator
    └── test/...
```

All four published adapters declare `day8/re-frame2 {:local/root "../../core"}`; the unpublished test-react fixture declares the same `:local/root` dep. None depend on each other.

## Where the substrate logic lives

The four React-shaped adapters (Reagent, reagent-slim, UIx, Helix) are extremely thin (~100-130 lines each) because they all delegate into [`re-frame.substrate.spine`](../core/src/re_frame/substrate/spine.cljs) in the core artefact. Two factory families:

- **Ratom family** — [`make-ratom-spine`](../core/src/re_frame/substrate/spine.cljs) + [`make-ratom-adapter`](../core/src/re_frame/substrate/spine.cljs) (Reagent + reagent-slim).
- **React-hook family** — [`make-react-spine`](../core/src/re_frame/substrate/spine.cljs) + [`make-react-adapter`](../core/src/re_frame/substrate/spine.cljs) (UIx + Helix).

Each adapter file builds a config map and hands it to the appropriate factory pair. The spine carries the epoch scheduler (glitch-freedom), the container quartet, `useSyncExternalStore` hooks, source-coord wrapping, the after-render sentinel, the unmount sentinel, and the nine-/five-hook routed late-bind tables. Reading the spine ns is the fastest path to understanding how the adapters work end-to-end.

The test-react adapter is its own quadrant — pure CLJC, no React, shares only the atom-container quartet with plain-atom.

## Per-feature artefacts vs adapters

Per-feature artefacts (`schemas/`, `machines/`, `routing/`, `flows/`, `http/`, `ssr/`, `epoch/`) sit at `implementation/<name>/` — they extend re-frame2's core capabilities. Adapters here implement the substrate contract for a specific reactive layer. The two tiers are independent: a consumer mixes one adapter with any subset of per-feature artefacts.

See:

- [Spec 006 — Reactive substrate](../../spec/006-ReactiveSubstrate.md) for the contract.
- [Conventions §Packaging conventions](../../spec/Conventions.md) for the multi-artefact model.
- [Guide how-to — Use UIx, Helix, or reagent-slim](../../docs/core/how-to/use-uix-helix-or-slim.md) for the "choose your adapter" walkthrough.
