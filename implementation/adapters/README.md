# Adapters

This directory groups re-frame2's **substrate adapters** — implementations of the substrate contract defined in [Spec 006](../../spec/006-ReactiveSubstrate.md).

> **Naming.** Per the corpus convention: *substrate* is the abstract contract; *adapter* is each implementation. "Reagent adapter," "UIx adapter," "Helix adapter."

## Adapters that ship today

| Directory | Adapter | Maven artefact | Target |
|---|---|---|---|
| [`reagent/`](reagent/) | Reagent adapter | `day8/re-frame2-reagent` | Reagent 2.x — the canonical CLJS reference adapter |
| [`uix/`](uix/) | UIx adapter | `day8/re-frame2-uix` | UIx 2.x — modern hooks-based React layer |
| [`helix/`](helix/) | Helix adapter | `day8/re-frame2-helix` | Helix 0.2.x — minimal React wrapper |
| [`reagent-slim/`](reagent-slim/) | Reagent-slim adapter | `day8/re-frame2-reagent-slim` (NB: artefact coord is `day8/reagent-slim` per IMPL-SPEC DECISION-1) | Reagent rewrite for React 19 — Stage 4 landed (full `reagent2.*` rewrite) |
| [`test-react/`](test-react/) | Test-React adapter | `day8/re-frame2-test-react` | Pure-CLJC React class-3 lifecycle simulator — unit-testable lifecycle bug catching (rf2-gqyqv placeholder skeleton) |

A consumer picks one (or more) by adding the matching artefact to their `deps.edn` alongside `day8/re-frame2`. Bundle isolation is **structural** — the wrong adapter is absent from the classpath, not eliminated by dead-code analysis. See [Conventions §Substrate-adapter shipping convention](../../spec/Conventions.md).

The `reagent-slim` adapter has landed Stage 4 (the full `reagent2.*` rewrite — reactive primitives, render scheduler, hiccup translation, and pure-CLJS render-to-string). It now carries its own CLJS test suite (substrate-shape contract, container round-trip, derived-value tracking, the disposal-MUST list, render Root-API call sequence, and source-coord / view-id stamping under the slim adapter) alongside the `reagent2.*` internals tests.

## What an adapter implements

Each adapter implements the surface defined in [Spec 006 §The adapter API contract](../../spec/006-ReactiveSubstrate.md):

- **Required (6):** `make-state-container`, `read-container`, `replace-container!`, `make-derived-value`, `render`, `render-to-string`.
- **Optional (2):** `subscribe-container`, `register-context-provider` — the core falls back when these are absent.
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
└── test-react/
    ├── deps.edn              ; declares day8/re-frame2-test-react
    ├── src/re_frame/adapter/test_react.cljc  ; pure-CLJC class-3 lifecycle simulator
    └── test/...
```

All five depend on `day8/re-frame2 {:local/root "../../core"}`. None depend on each other.

## Per-feature artefacts vs adapters

Per-feature artefacts (`schemas/`, `machines/`, `routing/`, `flows/`, `http/`, `ssr/`, `epoch/`) sit at `implementation/<name>/` — they extend re-frame2's core capabilities. Adapters here implement the substrate contract for a specific reactive layer. The two tiers are independent: a consumer mixes one adapter with any subset of per-feature artefacts.

See:

- [Spec 006 — Reactive substrate](../../spec/006-ReactiveSubstrate.md) for the contract.
- [Conventions §Packaging conventions](../../spec/Conventions.md) for the multi-artefact model.
- [Guide chapter 03 — Your first app](../../docs/guide/03-first-app.md) for the "choose your adapter" walkthrough.
