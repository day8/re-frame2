# counter-slim-and-fast — slim-substrate counter

This is the canonical counter from [`examples/reagent/counter/`](../../reagent/counter/), running on a different substrate.

Every event, subscription, and view stays exactly as it was. Only the [substrate](../../../docs/guide/how-to/use-uix-helix-or-slim.md) underneath changes.

That swap is the whole point. re-frame2's core is substrate-agnostic: your events, subscriptions, and app-db never know which React-family rendering library is underneath them. The [`day8/reagent-slim`](../../../implementation/adapters/reagent-slim/) rewrite is a leaner substrate than stock Reagent. This example re-mounts the canonical counter on it and gets identical behaviour — same clicks, same counts, same dataflow. We call that **adapter parity**, and proving it is why this example exists.

## What changes (and how little it is)

You wire a substrate to re-frame2 with an [adapter](../../../docs/guide/how-to/use-uix-helix-or-slim.md): a small value you hand to `init!` once at boot. Switching substrate is a one-line change. So the whole diff against the canonical counter is just two things:

1. **The imports point at `reagent2.*`** instead of stock `reagent.*`, so the views render through the slim substrate.
2. **`(rf/init!)` gets the slim adapter Var** instead of the stock one.

Everything else is character-for-character the canonical counter — `:counter/initialise`, `:counter/inc`, `:counter/dec`, the `:counter/value` subscription, the two views, and the lazy mount under a `frame-provider-existing`. The same event cascade runs through a different substrate, and nothing downstream can tell. That's the demonstration.

Read [`core.cljs`](core.cljs) as the example. It's plain, idiomatic re-frame2.

## The import you see here is not the one to copy

The `re-frame.adapter.reagent-slim` require in `core.cljs` is an **in-tree** spelling. It exists for a dull reason: the monorepo build shares a classpath with the stock adapter, so the two need different namespaces to avoid a clash.

That is not the spelling an adopter uses. The published `day8/reagent-slim` jar ships its adapter Var at the canonical `re-frame.adapter.reagent` — the same name as stock Reagent (renamed at publication). In a real app you write `(rf/init! re-frame.adapter.reagent/adapter)`, the exact same line as for stock Reagent. You **pick slim by dependency coordinate, not by import line.** That's the payoff: adopting the fast substrate costs you a `deps.edn` change and nothing in your source.

So don't copy the in-tree `-slim` namespace into published code. See [`docs/guide/how-to/use-uix-helix-or-slim.md`](../../../docs/guide/how-to/use-uix-helix-or-slim.md) and [`DESIGN-RATIONALE.md`](../../../implementation/adapters/reagent-slim/DESIGN-RATIONALE.md) §7.

## Why the `:counter/*` ids are shared

This example registers the **same** `:counter/*` event and subscription ids as the canonical [`examples/reagent/counter/`](../../reagent/counter/): `:counter/initialise`, `:counter/inc`, `:counter/dec`, and `:counter/value`. Normally the example-tree id-prefix convention forbids that. Here the collision is deliberate, because identical ids are how the two examples demonstrate adapter parity: the same dataflow, run on a different substrate, by literally being the same registrations. This is one of two blessed parity exceptions, justified in [`examples/TESTING.md` § Exception 1 — the stock/slim counter `:counter/*` id share](../../TESTING.md#exception-1--the-stockslim-counter-counter-id-share).

The share is safe **only** because stock and slim build as two separate standalone bundles that must never be loaded into one runtime together. The exception covers the four event+sub ids **only**. The views are *not* shared: `reg-view` auto-namespaces them under `:counter-slim-and-fast.core/*` here, versus `:counter.core/*` in the stock example.

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
