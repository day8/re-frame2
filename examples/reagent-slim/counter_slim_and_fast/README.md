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
`frame-provider-existing` — is character-for-character the canonical counter. The
same event cascade flows through a different reactive substrate and nobody
downstream can tell. That's the demonstration.

The teaching surface lives in [`core.cljs`](core.cljs) and reads as plain,
idiomatic re-frame2. **Read that file as the example.**

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
standalone bundles that must never be co-required into one runtime. The
carve-out covers the four event+sub ids **only** — the views are *not*
shared: `reg-view` auto-namespaces them under
`:counter-slim-and-fast.core/*` here vs `:counter.core/*` in the stock
fixture.

## Files

```
counter_slim_and_fast/
  core.cljs                          the teaching example: events/subs/views + the shared boot! + mount
  bundle_isolation_entry.cljs        gate-owned :init-fn — calls core/boot! with the SSR-exercise hook (not app practice)
  bundle_isolation_fixture.cljs      SSR/sentinel proof for the gate (not app practice)
  index.html                         minimal host page
  README.md                          this file
```

## How to run

Watch the build directly from `implementation/`:

```bash
shadow-cljs watch examples/counter-slim-and-fast
```

## Cross-references

- [`examples/reagent/counter/`](../../reagent/counter/) — the canonical counter
  on the stock-Reagent bridge; this example's behavioural twin.
- [`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) —
  the substrate contract the slim adapter satisfies.
