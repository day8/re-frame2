# counter-slim-and-fast — slim-substrate counter

Take the canonical counter from [`examples/reagent/counter/`](../../reagent/counter/),
keep every event, subscription, and view exactly as it was, and swap the
ground it stands on. That's this example in one sentence.

The point worth pausing on is what *didn't* change. re-frame2's core is
[substrate](../../../docs/guide/how-to/use-uix-helix-or-slim.md)-agnostic
by design: your events, subscriptions, and app-db never know which
React-family rendering library is underneath them. The
[`day8/reagent-slim`](../../../implementation/adapters/reagent-slim/)
rewrite is a leaner reactive substrate than stock Reagent — and this
fixture proves the promise by re-mounting the canonical counter on it and
getting *byte-for-byte identical* behaviour. Same clicks, same counts,
same dataflow. We call that **adapter parity**, and it's the whole reason
this example exists.

## What changes (and how little it is)

You wire a substrate to re-frame2 with an [adapter](../../../docs/guide/how-to/use-uix-helix-or-slim.md) —
a small value you hand to `init!` once at boot. Switching substrate is, by
construction, a one-line move. So the entire diff against the canonical
counter is exactly two things:

1. **The imports point at `reagent2.*`** instead of stock `reagent.*` —
   so the views render through the slim substrate's seams.
2. **`(rf/init!)` is handed the slim adapter Var** instead of the stock
   one.

Everything else — `:counter/initialise`, `:counter/inc`, `:counter/dec`,
the `:counter/value` subscription, the two views, the lazy mount under a
`frame-provider` — is character-for-character the canonical counter. The
same event cascade flows through a different reactive substrate and nobody
downstream can tell. That's the demonstration.

The teaching surface lives in [`core.cljs`](core.cljs) and reads as plain,
idiomatic re-frame2. **Read that file as the example.** (Its `boot!` helper
also leaves a seam for the gate plumbing described below, but you can
ignore that on a first read — it does nothing the app itself needs.)

## In-tree namespace vs published ABI — don't cargo-cult the import

One wrinkle that trips people up, so it's worth being explicit. The
`re-frame.adapter.reagent-slim` require you'll see in `core.cljs` is an
**in-tree** spelling, and it exists for a boring reason: the unrenamed
monorepo build shares a classpath with the stock adapter, so the two need
distinct namespaces to avoid clashing.

That is *not* the spelling an adopter uses. The published
`day8/reagent-slim` jar ships its adapter Var at the canonical,
stock-identical `re-frame.adapter.reagent` (renamed at publication). In a
real app you write `(rf/init! re-frame.adapter.reagent/adapter)` — the
exact same line as for stock Reagent — and you **pick slim by dependency
coordinate, not by import line**. That symmetry is the point: adopting the
fast substrate costs you a `deps.edn` change and nothing in your source.

So: enjoy the in-tree `-slim` namespace here, but don't copy it into
published code. See
[`docs/guide/how-to/use-uix-helix-or-slim.md`](../../../docs/guide/how-to/use-uix-helix-or-slim.md)
and [`DESIGN-RATIONALE.md`](../../../implementation/adapters/reagent-slim/DESIGN-RATIONALE.md) §7.

## Bundle-isolation fixture (not example practice)

This build pulls double duty: it's also the example side of the slim
adapter's bundle-isolation gate. A gate like that needs the production
bundle to actually *exercise* the slim's server-rendering path — otherwise
"this bundle contains no `react-dom/server`" is a vacuously true claim
about a bundle that never renders on the server in the first place. So the
fixture renders the counter to a static string to give the gate something
real to check.

The important design decision is that none of this leaks into the teaching
source. The plumbing is **deliberately quarantined** so `core.cljs` stays
clean:

- The SSR/sentinel exercise lives in
  [`bundle_isolation_fixture.cljs`](bundle_isolation_fixture.cljs) — it
  runs the slim's pure-CLJS `render-to-static-markup`, writes the result
  onto a `counterSlimPrerender` host-global (a DCE anchor that keeps the
  call alive under `:advanced`), and tears down the orphaned subscription
  the static render leaves in the cache. That's what gives the gate's
  non-vacuity contract a signal to read.
- The build's `:init-fn` is the gate-owned entrypoint
  [`bundle_isolation_entry.cljs`](bundle_isolation_entry.cljs), **not**
  `core/run`. Crucially, it doesn't re-copy the boot: both `core/run` and
  the entry call the single shared `core/boot!` helper, so the two paths
  *cannot* drift. The entry simply passes `boot!` an `on-frame` pre-mount
  hook that weaves the fixture exercise in at the one point its ordering
  needs — under the frame scope, before the client mount. The result:
  `core/run` is plain re-frame2 with nothing but the example's own
  dataflow (rf2-vyl0vt, rf2-pe4u0g).

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

Here's a thing that should make you twitch, with a reason it shouldn't.
This fixture registers the **same** `:counter/*` event and subscription
ids as the canonical [`examples/reagent/counter/`](../../reagent/counter/)
— `:counter/initialise`, `:counter/inc`, `:counter/dec`, and
`:counter/value`. Normally the example-tree id-prefix convention would
forbid that collision. Here it's the *entire point*: identical ids are how
the two fixtures demonstrate adapter parity — the same dataflow, proven
on a different substrate by literally being the same registrations. It's
one of two blessed parity exceptions, narrowed and justified in
[`examples/TESTING.md` § Exception 1 — the stock/slim counter
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
(`npm run test:adapter-smokes` does not build this standalone example — it
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
