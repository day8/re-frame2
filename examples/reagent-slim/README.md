# Reagent Slim — examples

Most adapters in re-frame2 are bridges: a thin shim that teaches the core how to talk to an existing rendering library. Reagent Slim is not that. It is its **own substrate** — a ground-up `reagent2.*` rewrite — rather than the thin bridge over stock Reagent that the canonical Reagent adapter (`day8/re-frame2-reagent`) ships. Because it is a distinct substrate with its own adapter, its example lives here under `examples/reagent-slim/` instead of in the stock-Reagent tree, in keeping with the per-substrate grouping convention (`examples/reagent/`, `examples/uix/`, `examples/helix/`). Background on the adapter itself is at [`implementation/adapters/reagent-slim/`](../../implementation/adapters/reagent-slim/) and [Spec 006 §Adapter shipping convention](../../spec/006-ReactiveSubstrate.md).

What does a from-scratch substrate cost you, as the author of an app? Almost nothing — and that is exactly what this directory is here to show. It holds a **single example**: the canonical counter dataflow, re-mounted on the slim substrate. Read the teaching source ([`counter_slim_and_fast/core.cljs`](counter_slim_and_fast/core.cljs)) beside the stock counter and you'll find the same idiomatic re-frame2 from the first line; the *only* thing that moves is the substrate underneath. The build pulls double duty as the slim adapter's bundle-isolation fixture, but that harness plumbing is fenced off (see below) so the lesson reads clean — you learn the dataflow, not the test rig.

## Layout

```
reagent-slim/
  counter_slim_and_fast/   <-- the canonical counter dataflow, mounted on day8/reagent-slim
```

Inside that folder, three CLJS files share the space but not the job. The teaching source `core.cljs` is the one you read to learn the example. The other two belong to the test rig: the adapter-owned bundle-isolation fixture `bundle_isolation_fixture.cljs` and its gate-owned entrypoint `bundle_isolation_entry.cljs` (the build's `:init-fn`) — both deliberately kept apart from the teaching source (see below). A hand-written `index.html` rounds it out. The `examples/` tree is **test-free**: no example ships a Playwright spec — see [Testing](#testing) below for where the real regression coverage lives.

The dataflow is identical to the stock-Reagent counter at [`../reagent/counter/`](../reagent/counter/). What changes is purely mechanical: every user-facing `reagent.*` import points at `reagent2.*`, and `rf/init!` is handed the slim adapter Var instead of the stock one. The CLJS namespace stays `counter-slim-and-fast.core` (build id `examples/counter-slim-and-fast`). That's the whole diff — which is precisely the claim the example exists to back up.

> **In-tree namespace vs published ABI.** The checked-in fixture requires the **in-tree** namespace `re-frame.adapter.reagent-slim` because the unrenamed monorepo build keeps both adapters on one classpath and must avoid an ns clash. That is *not* the spelling adopters use: the **published** `day8/reagent-slim` jar ships the adapter Var at the canonical, stock-identical `re-frame.adapter.reagent` (the release workflow renames it at publication). Adopter code therefore wires `(rf/init! re-frame.adapter.reagent/adapter)` exactly as for stock — you pick slim by deps coordinate, not by import line. See [`docs/guide/how-to/use-uix-helix-or-slim.md`](../../docs/guide/how-to/use-uix-helix-or-slim.md) and [`implementation/adapters/reagent-slim/DESIGN-RATIONALE.md`](../../implementation/adapters/reagent-slim/DESIGN-RATIONALE.md) §7. Do not cargo-cult the in-tree `-slim` namespace into published-app code.

## What the example demonstrates

- **`reagent-slim/counter_slim_and_fast/`** ([build id `examples/counter-slim-and-fast`](../../implementation/shadow-cljs.edn))
  The same `:counter/*` events and subs as the canonical Reagent counter, mounted on the slim substrate. The teaching source — events, subs, views, and the lazy client mount — lives in [`counter_slim_and_fast/core.cljs`](counter_slim_and_fast/core.cljs) and reads as idiomatic re-frame2; once again the only divergence from the stock counter is the substrate swap. Sharing the `:counter/*` event and sub ids with the stock fixture breaks the example id-prefix convention on purpose: it is a **deliberate, documented exception**, and the reason is precisely the point being made — identical ids let the two counters prove adapter parity by being literally the same handlers. (See [`examples/TESTING.md` § Exception 1 — the stock/slim counter `:counter/*` id share](../TESTING.md#exception-1--the-stockslim-counter-counter-id-share).) The views, by contrast, are *not* shared — `reg-view` auto-namespaces them under `:counter-slim-and-fast.core/*` rather than the stock `:counter.core/*`, so they stay distinct without you having to think about it.

  This build also pulls a second shift as the example side of the slim adapter's bundle-isolation gate — but that machinery is **walled off** from the teaching source so none of it leaks into `core.cljs`. The SSR/sentinel exercise lives in [`counter_slim_and_fast/bundle_isolation_fixture.cljs`](counter_slim_and_fast/bundle_isolation_fixture.cljs) (fixture code, not app practice), and the build's `:init-fn` is the gate-owned entrypoint [`counter_slim_and_fast/bundle_isolation_entry.cljs`](counter_slim_and_fast/bundle_isolation_entry.cljs) — **not** `core/run`. That entrypoint boots the very same app and then weaves the fixture exercise in around it (rf2-vyl0vt), driving `reagent2.dom.server/render-to-static-markup` at boot so the pure-CLJS SSR contract is exercised for real rather than asserted into a vacuum. The full contract narrative and the sentinel methodology belong to the gate, not to this README, so they are not duplicated here — read them at [`implementation/scripts/check-reagent-slim-bundle-isolation.cjs`](../../implementation/scripts/check-reagent-slim-bundle-isolation.cjs) (the four S3-008 / S3-005 contracts) and [`implementation/adapters/reagent-slim/IMPL-SPEC.md`](../../implementation/adapters/reagent-slim/IMPL-SPEC.md) §1.4 + §1.8 + §8.

## Testing

The `examples/` tree carries no tests, and this example is no exception — a from-scratch substrate has a lot to get right, but none of that proof load sits here. Reagent Slim's regression coverage lives across four adapter-owned layers, none of them under `examples/`, each guarding a different seam:

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
