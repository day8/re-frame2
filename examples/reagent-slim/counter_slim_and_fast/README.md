# counter-slim-and-fast — slim-substrate counter

The canonical counter (`examples/reagent/counter/`) re-mounted on the
[`day8/reagent-slim`](../../../implementation/adapters/reagent-slim/)
rewrite rather than stock `day8/re-frame2-reagent` (the thin bridge
over stock Reagent). The user-visible behaviour is identical to the
canonical counter; the difference is the substrate beneath.

Every user-facing Reagent import points at `reagent2.*` instead of
stock `reagent.*`, and `(rf/init!)` is called with
`re-frame.adapter.reagent-slim/adapter`. The same six-domino
dataflow flows through a different reactive substrate.

## What this fixture verifies

The S3-008 + S3-005 contract from
[`implementation/adapters/reagent-slim/IMPL-SPEC.md`](../../../implementation/adapters/reagent-slim/IMPL-SPEC.md)
§1.4 + §1.8 + §8 — a binding adapter-owned bundle-isolation claim
about the slim substrate:

1. **Stock-Reagent impl isolation.** The advanced-compiled bundle for
   this example contains NO `reagent.impl.*` symbols. The slim
   rewrite has its own `reagent2.impl.*` substrate; the bridge's
   `reagent.impl.*` internals must be entirely absent.
2. **Pure-CLJS SSR.** The bundle contains NO `react-dom/server`
   symbols, even though `run` exercises
   `reagent2.dom.server/render-to-static-markup` at boot. The slim's
   SSR seam is pure-CLJS (per IMPL-SPEC §8.7), so the bundle has no
   compiled-in path to `react-dom/server`.
3. **Methodology control.** The stock-Reagent counter bundle
   (`examples/counter`) MUST still contain both groups of symbols —
   that proves the grep has signal. If a future stock-Reagent upgrade
   DCEs them out of the stock bundle too, the test fails loudly and
   the sentinel set gets re-derived.

The grep that enforces all three invariants lives at
[`implementation/scripts/check-reagent-slim-bundle-isolation.cjs`](../../../implementation/scripts/check-reagent-slim-bundle-isolation.cjs);
the changed-surface CI job is `cljs-reagent-slim-bundle-isolation` in
`.github/workflows/test.yml`.

The slim adapter is also a drop-in for the bridge at the
behavioural level: same clicks, same counts.

## Shared `:counter/*` ids — a deliberate, documented exception

This fixture registers the **same** `:counter/*` event and subscription
ids as the canonical [`examples/reagent/counter/`](../../reagent/counter/)
— `:counter/initialise`, `:counter/inc`, `:counter/dec`, and
`:counter/value`. That id-identity is **intentional**: it is how the two
fixtures demonstrate **adapter parity** (byte-for-byte identical dataflow
on a different reactive substrate). It is the one blessed exception to the
example-tree id-prefix convention, narrowed and justified in
[`examples/TESTING.md` § Named exception — the stock/slim counter
`:counter/*` id share](../../TESTING.md#named-exception--the-stockslim-counter-counter-id-share).

The share is safe **only** because stock and slim build as two separate
standalone bundles that must never be co-required into one runtime; the
`npm run test:reagent-slim:bundle-isolation` gate is the regression
surface that keeps that boundary honest. The carve-out covers the four
event+sub ids **only** — the views are *not* shared: `reg-view`
auto-namespaces them under `:counter-slim-and-fast.core/*` here vs
`:counter.core/*` in the stock fixture. If either fixture is ever folded
into a shared wrapper/showcase/`test:browser` bundle, the ids must be
prefixed first.

## Files

```
counter_slim_and_fast/
  core.cljs                          mount + events/subs/view + SSR exercise
  index.html                         minimal host page
  README.md                          this file
```

The bundle-isolation verifier is adapter-owned rather than a general
human-facing example test and lives under `implementation/scripts/`.

Per the test-free examples policy there is no per-example
Playwright spec; real-regression coverage lives in the substrate
contract tests (`npm run test:cljs`) and the framework gates (see
[`examples/README.md`](../../README.md)).

## How to run

To iterate against the source, watch the build directly from
`implementation/`:

```bash
shadow-cljs watch examples/counter-slim-and-fast
```

The Reagent Slim bundle-isolation contract is exercised separately
when slim-related paths change, and in the nightly/manual expensive
workflow. To run it locally:

```bash
# From implementation/ — release both bundles, then grep.
npm run test:reagent-slim:bundle-isolation
```

## Cross-references

- [`examples/reagent/counter/`](../../reagent/counter/) — the canonical counter
  on the stock-Reagent bridge; this example's behavioural twin.
- [`implementation/adapters/reagent-slim/IMPL-SPEC.md`](../../../implementation/adapters/reagent-slim/IMPL-SPEC.md)
  §1.4 + §1.8 + §8 — the spec the bundle-isolation contract binds
  to.
- [`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) —
  the substrate contract the slim adapter satisfies.
- [`spec/Conventions.md` §Adapter test matrix policy](../../../spec/Conventions.md#adapter-test-matrix-policy) —
  why the slim build sits alongside the bridge build in CI.
