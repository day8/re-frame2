# Reagent Slim — examples

The `day8/reagent-slim` adapter (see [`implementation/adapters/reagent-slim/`](../../implementation/adapters/reagent-slim/) and [Spec 006 §Adapter shipping convention](../../spec/006-ReactiveSubstrate.md)). Reagent Slim is its **own substrate** — a ground-up `reagent2.*` rewrite rather than the thin bridge over stock Reagent that the canonical Reagent adapter (`day8/re-frame2-reagent`) ships. Because it is a distinct substrate/adapter, its example lives here under `examples/reagent-slim/` rather than in the stock-Reagent tree, mirroring the per-substrate grouping convention (`examples/reagent/`, `examples/uix/`, `examples/helix/`).

This directory holds a **single example**: the canonical counter dataflow re-mounted on the slim substrate. The teaching source ([`counter_slim_and_fast/core.cljs`](counter_slim_and_fast/core.cljs)) reads as idiomatic re-frame2 — same pattern as the stock counter, differing only in the substrate swap. The build additionally binds the slim adapter's bundle-isolation contract, but that fixture plumbing is kept separate (see below) so the example stays clean rather than teaching harness mechanics.

## Layout

```
reagent-slim/
  counter_slim_and_fast/   <-- the canonical counter dataflow, mounted on day8/reagent-slim
```

The example sits in its own folder with the teaching CLJS source (`core.cljs`), the adapter-owned bundle-isolation fixture (`bundle_isolation_fixture.cljs`) plus its gate-owned entrypoint (`bundle_isolation_entry.cljs`, the build's `:init-fn`) — both kept apart from the teaching source (see below) — and a hand-written `index.html`. The `examples/` tree is **test-free**: no example ships a Playwright spec — see [Testing](#testing) below for where the real regression coverage lives. The CLJS namespace stays `counter-slim-and-fast.core` (build id `examples/counter-slim-and-fast`); the dataflow is identical to the stock-Reagent [`../reagent/counter/`](../reagent/counter/), but every user-facing `reagent.*` import points at `reagent2.*` and `rf/init!` is called with the slim adapter Var.

> **In-tree namespace vs published ABI.** The checked-in fixture requires the **in-tree** namespace `re-frame.adapter.reagent-slim` because the unrenamed monorepo build keeps both adapters on one classpath and must avoid an ns clash. That is *not* the spelling adopters use: the **published** `day8/reagent-slim` jar ships the adapter Var at the canonical, stock-identical `re-frame.adapter.reagent` (the release workflow renames it at publication). Adopter code therefore wires `(rf/init! re-frame.adapter.reagent/adapter)` exactly as for stock — you pick slim by deps coordinate, not by import line. See [`docs/guide/how-to/use-uix-helix-or-slim.md`](../../docs/guide/how-to/use-uix-helix-or-slim.md) and [`implementation/adapters/reagent-slim/DESIGN-RATIONALE.md`](../../implementation/adapters/reagent-slim/DESIGN-RATIONALE.md) §7. Do not cargo-cult the in-tree `-slim` namespace into published-app code.

## What the example demonstrates

- **`reagent-slim/counter_slim_and_fast/`** ([build id `examples/counter-slim-and-fast`](../../implementation/shadow-cljs.edn))
  The same `:counter/*` events and subs as the canonical Reagent counter, mounted on the slim substrate. The teaching source — events, subs, views, the lazy client mount — lives in [`counter_slim_and_fast/core.cljs`](counter_slim_and_fast/core.cljs) and reads as idiomatic re-frame2; the only divergence from the stock counter is the substrate swap. (Sharing the `:counter/*` event+sub ids with the stock fixture is a **deliberate, documented exception** to the example id-prefix convention — it demonstrates adapter parity; see [`examples/TESTING.md` § Exception 1 — the stock/slim counter `:counter/*` id share](../TESTING.md#exception-1--the-stockslim-counter-counter-id-share). The views are *not* shared: `reg-view` auto-namespaces them under `:counter-slim-and-fast.core/*` vs the stock `:counter.core/*`.)

  The build doubles as the example side of the slim adapter's bundle-isolation gate, but that plumbing is **isolated** from the teaching source so it does not bleed into `core.cljs`: the SSR/sentinel exercise lives in [`counter_slim_and_fast/bundle_isolation_fixture.cljs`](counter_slim_and_fast/bundle_isolation_fixture.cljs) (fixture code, not app practice), and the build's `:init-fn` is the gate-owned entrypoint [`counter_slim_and_fast/bundle_isolation_entry.cljs`](counter_slim_and_fast/bundle_isolation_entry.cljs) — **not** `core/run` — which boots the same app and weaves the fixture exercise in (rf2-vyl0vt). The fixture exercises `reagent2.dom.server/render-to-static-markup` at boot so the pure-CLJS SSR contract is non-vacuous. The contract narrative and the sentinel methodology are owned by the gate, not duplicated here — see [`implementation/scripts/check-reagent-slim-bundle-isolation.cjs`](../../implementation/scripts/check-reagent-slim-bundle-isolation.cjs) (the four S3-008 / S3-005 contracts) and [`implementation/adapters/reagent-slim/IMPL-SPEC.md`](../../implementation/adapters/reagent-slim/IMPL-SPEC.md) §1.4 + §1.8 + §8.

## Testing

The `examples/` tree carries no tests. Reagent Slim's regression coverage lives across four adapter-owned layers, none of them under `examples/`:

1. **Compile gate** — `npm run test:examples-compile` proves the slim example builds.
2. **Bundle-isolation grep** — the **adapter-owned bundle-isolation gate** at [`implementation/scripts/check-reagent-slim-bundle-isolation.cjs`](../../implementation/scripts/check-reagent-slim-bundle-isolation.cjs), wired to the `cljs-reagent-slim-bundle-isolation` CI job in [`.github/workflows/test.yml`](../../.github/workflows/test.yml).
3. **Headless substrate tests** — the slim adapter's CLJS node-tests under `implementation/adapters/reagent-slim/test/` (`npm run test:cljs`), including the pure-CLJS SSR contract test that mirrors this example's value-5 inc dataflow with no DOM.
4. **Client-runtime smoke** — the day8/reagent-slim adapter testbed at `implementation/adapters/reagent-slim/testbed/`, a standalone counter mounted through `reagent2.dom.client` that proves the live mount/inject/click path in headless Chromium (`npm run test:reagent-slim:smoke`). This is a dedicated slim gate, separate from the shared Reagent/UIx/Helix examples adapter-smoke set.

From `implementation/`:

```bash
# Release both bundles (stock + slim), then grep the slim bundle.
npm run test:reagent-slim:bundle-isolation
# Drive the slim substrate end-to-end in a headless browser.
npm run test:reagent-slim:smoke
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
