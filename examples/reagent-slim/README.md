# Reagent Slim — examples

The `day8/reagent-slim` adapter (see [`implementation/adapters/reagent-slim/`](../../implementation/adapters/reagent-slim/) and [Spec 006 §Adapter shipping convention](../../spec/006-ReactiveSubstrate.md)). Reagent Slim is its **own substrate** — a ground-up `reagent2.*` rewrite rather than the thin bridge over stock Reagent that the canonical Reagent adapter (`day8/re-frame2-reagent`) ships. Because it is a distinct substrate/adapter, its example lives here under `examples/reagent-slim/` rather than in the stock-Reagent tree, mirroring the per-substrate grouping convention (`examples/reagent/`, `examples/uix/`, `examples/helix/`).

This directory holds a **single adapter-owned fixture**, not a tutorial set: the canonical counter dataflow re-mounted on the slim substrate. It exists to bind the slim adapter's bundle-isolation contract, not to teach a new pattern.

## Layout

```
reagent-slim/
  counter_slim_and_fast/   <-- the canonical counter dataflow, mounted on day8/reagent-slim
```

The example sits in its own folder with the CLJS source (`core.cljs`) and a hand-written `index.html`. The `examples/` tree is **test-free**: no example ships a Playwright spec — see [Testing](#testing) below for where the real regression coverage lives. The CLJS namespace stays `counter-slim-and-fast.core` (build id `examples/counter-slim-and-fast`); the dataflow is identical to the stock-Reagent [`../reagent/counter/`](../reagent/counter/), but every user-facing `reagent.*` import points at `reagent2.*` and `rf/init!` is called with the slim adapter Var `re-frame.adapter.reagent-slim/adapter`.

## What the example demonstrates

- **`reagent-slim/counter_slim_and_fast/`** ([build id `examples/counter-slim-and-fast`](../../implementation/shadow-cljs.edn))
  The same `:counter/*` events and subs as the canonical Reagent counter, mounted on the slim substrate. (Sharing the `:counter/*` event+sub ids with the stock fixture is a **deliberate, documented exception** to the example id-prefix convention — it demonstrates adapter parity; see [`examples/TESTING.md` § Exception 1 — the stock/slim counter `:counter/*` id share](../TESTING.md#exception-1--the-stockslim-counter-counter-id-share). The views are *not* shared: `reg-view` auto-namespaces them under `:counter-slim-and-fast.core/*` vs the stock `:counter.core/*`.) Its `run` fn deliberately exercises `reagent2.dom.server/render-to-static-markup` at boot so the pure-CLJS SSR contract is non-vacuous. The paired verifier asserts these bundle-isolation contracts on the `:advanced` bundle:

  1. **Stock-Reagent impl isolation** (Contract 2, S3-008) — no `reagent.impl.*` symbols in the slim bundle (the slim rewrite has its own `reagent2.impl.*` substrate; the bridge's impl tree must be entirely absent).
  2. **Pure-CLJS SSR** (Contract 3, S3-005) — no `react-dom/server` symbols in the slim bundle, even though the example runs `reagent2.dom.server/render-to-static-markup` at boot (per IMPL-SPEC §8 + S3-005).
  3. **SSR-absence non-vacuity** (Contract 4, S3-005) — the slim bundle *positively* contains the SSR boot/serializer presence sentinels (the `counterSlimPrerender` host-global the boot writes plus a `reagent2.dom.server` serializer-owned literal), so the (2) absence is not a vacuous proof against an SSR-free bundle.

  The methodology control (Contract 1) is the stock-Reagent `examples/counter` bundle: it must still contain the **stock-Reagent impl sentinels only** — that proves the (1) grep has signal. The `react-dom/server` proof is not controlled by the stock bundle but by the positive slim-side presence check in (3).

## Testing

The `examples/` tree carries no tests. Reagent Slim's regression coverage is the **adapter-owned bundle-isolation gate** at [`implementation/scripts/check-reagent-slim-bundle-isolation.cjs`](../../implementation/scripts/check-reagent-slim-bundle-isolation.cjs), wired to the `cljs-reagent-slim-bundle-isolation` CI job in [`.github/workflows/test.yml`](../../.github/workflows/test.yml). From `implementation/`:

```bash
# Release both bundles (stock + slim), then grep the slim bundle.
npm run test:reagent-slim:bundle-isolation
```

Broader substrate regressions are caught by the contract tests (`npm run test:cljs`), the Xray feature-matrix gate (`npm run test:xray-feature-gate`), and the production bundle-isolation gate (`npm run test:bundle-isolation`) — not by per-example specs.

To iterate on the example interactively, from `implementation/`:

```bash
shadow-cljs watch examples/counter-slim-and-fast
```

The build emits `main.js` into `out/examples/counter-slim-and-fast/`; copy the example's hand-written [`counter_slim_and_fast/index.html`](counter_slim_and_fast/index.html) (and the shared assets it references under [`../_shared/`](../_shared/)) alongside it to load the watched build in a browser.

## Cross-references

- [`implementation/adapters/reagent-slim/IMPL-SPEC.md`](../../implementation/adapters/reagent-slim/IMPL-SPEC.md) §1.4 + §1.8 + §8 — the spec the bundle-isolation contract binds to.
- [`spec/006-ReactiveSubstrate.md`](../../spec/006-ReactiveSubstrate.md) — the substrate contract the slim adapter satisfies.
- [`spec/Conventions.md` §Adapter test matrix policy](../../spec/Conventions.md#adapter-test-matrix-policy) — why the slim build sits alongside the stock bridge build in CI.
- [`examples/reagent/counter/`](../reagent/counter/) — the canonical counter on the stock-Reagent bridge; this example's behavioural twin.
