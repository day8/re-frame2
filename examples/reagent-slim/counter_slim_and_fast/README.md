# counter-slim-and-fast — slim-substrate counter

The canonical counter (`examples/reagent/counter/`) re-mounted on the
[`day8/reagent-slim`](../../../implementation/adapters/reagent-slim/)
rewrite rather than stock `day8/re-frame2-reagent` (the thin bridge
over stock Reagent). The user-visible behaviour is identical to the
canonical counter; the difference is the substrate beneath.

Every user-facing Reagent import points at `reagent2.*` instead of
stock `reagent.*`, and `(rf/init!)` is called with the slim adapter Var.
The same six-domino dataflow flows through a different reactive
substrate. The slim adapter is a drop-in for the bridge at the
behavioural level: same clicks, same counts.

**In-tree namespace vs published ABI.** This checked-in fixture requires
the **in-tree** namespace `re-frame.adapter.reagent-slim` only because the
unrenamed monorepo build shares a classpath with the stock adapter. That
is *not* the adopter spelling: the published `day8/reagent-slim` jar ships
the adapter Var at the canonical, stock-identical `re-frame.adapter.reagent`
(renamed at publication). Adopter code wires
`(rf/init! re-frame.adapter.reagent/adapter)` — you pick slim by deps
coordinate, not by import line. See
[`docs/guide/how-to/use-uix-helix-or-slim.md`](../../../docs/guide/how-to/use-uix-helix-or-slim.md)
and [`DESIGN-RATIONALE.md`](../../../implementation/adapters/reagent-slim/DESIGN-RATIONALE.md) §7.
Don't cargo-cult the in-tree `-slim` namespace into published-app code.

The teaching surface — events, subs, views, and the lazy client mount —
lives in [`core.cljs`](core.cljs) and reads as idiomatic re-frame2.
Read that file as the example.

## Bundle-isolation fixture (not example practice)

This build doubles as the example side of the slim adapter's
bundle-isolation gate. That plumbing is **deliberately isolated** from
the teaching source so it does not bleed into `core.cljs`:

- The SSR/sentinel exercise lives in
  [`bundle_isolation_fixture.cljs`](bundle_isolation_fixture.cljs) — it
  runs the slim's pure-CLJS `render-to-static-markup` (the DCE-anchored
  `counterSlimPrerender` host-global write plus the sub-cache teardown)
  so the gate's non-vacuity contract has signal.
- The build's `:init-fn` is the gate-owned entrypoint
  [`bundle_isolation_entry.cljs`](bundle_isolation_entry.cljs), **not**
  `core/run`. It does not re-copy the boot: both `core/run` and the entry
  call the single shared `core/boot!` helper, so the two paths cannot drift.
  The entry passes `boot!` an `on-frame` pre-mount hook that weaves the
  fixture exercise in at the one point its ordering needs (under the frame
  scope, before the client mount). This keeps `core.cljs` free of harness
  mechanics: `core/run` is plain, idiomatic re-frame2 with nothing but
  the example's own dataflow (rf2-vyl0vt, rf2-pe4u0g).

A reader studying the example can ignore both gate files and read
`core.cljs`.

The contract narrative — the four S3-008 / S3-005 contracts and the
sentinel methodology — is owned by the gate, not duplicated here. See:

- [`implementation/scripts/check-reagent-slim-bundle-isolation.cjs`](../../../implementation/scripts/check-reagent-slim-bundle-isolation.cjs)
  — the grep that enforces all four contracts (source of truth for the
  sentinels); the changed-surface CI job is
  `cljs-reagent-slim-bundle-isolation` in `.github/workflows/test.yml`.
- [`implementation/adapters/reagent-slim/IMPL-SPEC.md`](../../../implementation/adapters/reagent-slim/IMPL-SPEC.md)
  §1.4 + §1.8 + §8 — the spec the contract binds to.

## Shared `:counter/*` ids — a deliberate, documented exception

This fixture registers the **same** `:counter/*` event and subscription
ids as the canonical [`examples/reagent/counter/`](../../reagent/counter/)
— `:counter/initialise`, `:counter/inc`, `:counter/dec`, and
`:counter/value`. That id-identity is **intentional**: it is how the two
fixtures demonstrate **adapter parity** (byte-for-byte identical dataflow
on a different reactive substrate). It is one of two blessed parity
exceptions to the example-tree id-prefix convention, narrowed and justified
in [`examples/TESTING.md` § Exception 1 — the stock/slim counter
`:counter/*` id share](../../TESTING.md#exception-1--the-stockslim-counter-counter-id-share).

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
  core.cljs                          the teaching example: events/subs/views + the shared boot! + mount
  bundle_isolation_entry.cljs        gate-owned :init-fn — calls core/boot! with the SSR-exercise hook (not app practice)
  bundle_isolation_fixture.cljs      SSR/sentinel proof for the gate (not app practice)
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

The watch build emits `main.js` into
`out/examples/counter-slim-and-fast/` (the build's `:output-dir` in
`implementation/shadow-cljs.edn`). To load it in a browser, copy this
folder's hand-written [`index.html`](index.html) alongside that
`main.js`, stage the [`examples/_shared/`](../../_shared/) tree next to
it so the page's `_shared/...` references resolve, then serve
`out/examples/counter-slim-and-fast/` over HTTP.
(`npm run test:examples` does not build this standalone example — it
compiles and serves only the three adapter testbeds; see
[`examples/README.md`](../../README.md).)

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
