# counter-slim-and-fast — slim-substrate counter

The canonical counter (`examples/reagent/counter/`) re-mounted on the
[`day8/reagent-slim`](https://github.com/day8/reagent-slim) rewrite
rather than stock `day8/re-frame2-reagent` (the thin bridge over
stock Reagent). The user-visible behaviour is identical to the
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
behavioural level: same clicks, same counts. The Playwright spec
runs the exact assertions as the canonical counter's spec —
initial render shows 5, `+` → 6, two `-` → 4.

## Files

```
counter_slim_and_fast/
  core.cljs                          mount + events/subs/view + SSR exercise
  index.html                         minimal host page
  counter_slim_and_fast.spec.cjs     Playwright smoke (drop-in parity with counter)
  README.md                          this file
```

The bundle-isolation verifier is adapter-owned rather than a general
human-facing example test and lives under `implementation/scripts/`.

## How to run

The example is wired into the canonical examples harness. From
`implementation/`:

```bash
npm run test:examples
```

That compiles every example (this one builds under shadow-cljs id
`examples/counter-slim-and-fast`), stages its `index.html` into
`out/examples/counter-slim-and-fast/`, serves the lot on port 8030,
and runs the Playwright smoke spec.

The Reagent Slim bundle-isolation contract is exercised separately
when slim-related paths change, and in the nightly/manual expensive
workflow. To run it locally:

```bash
# From implementation/ — release both bundles, then grep.
npm run test:reagent-slim:bundle-isolation
```

To iterate on the source alone, watch the build directly from
`implementation/`:

```bash
shadow-cljs watch examples/counter-slim-and-fast
```

(Run `npm run test:examples` once first so
`out/examples/counter-slim-and-fast/index.html` is staged; subsequent
watch builds reuse it.)

## Cross-references

- [`examples/reagent/counter/`](../counter/) — the canonical counter
  on the stock-Reagent bridge; this example's behavioural twin.
- [`implementation/adapters/reagent-slim/IMPL-SPEC.md`](../../../implementation/adapters/reagent-slim/IMPL-SPEC.md)
  §1.4 + §1.8 + §8 — the spec the bundle-isolation contract binds
  to.
- [`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) —
  the substrate contract the slim adapter satisfies.
- [`spec/Conventions.md` §Adapter test matrix policy](../../../spec/Conventions.md#adapter-test-matrix-policy) —
  why the slim build sits alongside the bridge build in CI.
