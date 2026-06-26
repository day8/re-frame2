# Reagent Slim — examples

Reagent Slim is a [substrate](../../docs/guide/glossary.md#substrate): a rendering layer you can run re-frame2 on. Most adapters are thin bridges over an existing library. Reagent Slim is different — it is a ground-up `reagent2.*` rewrite, its own substrate rather than a shim over stock Reagent. That is why its example lives here under `examples/reagent-slim/`, alongside the other per-substrate folders (`examples/reagent/`, `examples/uix/`, `examples/helix/`).

Background on the adapter is at [`implementation/adapters/reagent-slim/`](../../implementation/adapters/reagent-slim/) and [Spec 006 §Adapter shipping convention](../../spec/006-ReactiveSubstrate.md).

This folder makes one point: a from-scratch substrate costs you almost nothing as an app author. It holds a **single example** — the canonical counter, re-mounted on the slim substrate. Read the teaching source ([`counter_slim_and_fast/core.cljs`](counter_slim_and_fast/core.cljs)) beside the stock counter. It is the same idiomatic re-frame2 from the first line. The only thing that changes is the substrate underneath.

## Layout

```
reagent-slim/
  counter_slim_and_fast/   <-- the canonical counter dataflow, mounted on day8/reagent-slim
```

The behaviour is identical to the stock-Reagent counter at [`../reagent/counter/`](../reagent/counter/). The diff is purely mechanical: every user-facing `reagent.*` import points at `reagent2.*`, and `init!` is handed the slim adapter instead of the stock one. The namespace stays `counter-slim-and-fast.core` (build id `examples/counter-slim-and-fast`). That small diff is the whole claim the example exists to back up.

> **In-tree namespace vs the published one.** The checked-in source requires the **in-tree** namespace `re-frame.adapter.reagent-slim`, because the unrenamed monorepo build keeps both adapters on one classpath and must avoid a name clash. That is *not* the spelling adopters use. The **published** `day8/reagent-slim` jar ships the adapter at the canonical, stock-identical `re-frame.adapter.reagent` (the release workflow renames it at publication). So adopter code wires `(rf/init! re-frame.adapter.reagent/adapter)` exactly as for stock — you pick slim by deps coordinate, not by import line. See [`docs/guide/how-to/use-uix-helix-or-slim.md`](../../docs/guide/how-to/use-uix-helix-or-slim.md) and [`implementation/adapters/reagent-slim/DESIGN-RATIONALE.md`](../../implementation/adapters/reagent-slim/DESIGN-RATIONALE.md) §7. Do not copy the in-tree `-slim` namespace into a published app.

## What the example demonstrates

- **`reagent-slim/counter_slim_and_fast/`** ([build id `examples/counter-slim-and-fast`](../../implementation/shadow-cljs.edn))
  The same `:counter/*` events and subscriptions as the canonical Reagent counter, mounted on the slim substrate. The teaching source — events, subscriptions, views, and the lazy client mount — lives in [`counter_slim_and_fast/core.cljs`](counter_slim_and_fast/core.cljs) and reads as idiomatic re-frame2. Again, the only difference from the stock counter is the substrate swap.

  Sharing the `:counter/*` event and subscription ids with the stock counter breaks the example id-prefix convention on purpose. It is a **deliberate, documented exception**, and the reason is the point being made: identical ids let the two counters prove the adapters match by running the *same* handlers. The views are *not* shared — `reg-view` auto-namespaces them under `:counter-slim-and-fast.core/*`, distinct from the stock `:counter.core/*`, so they stay separate on their own.

## How to run

To iterate on the example interactively, from `implementation/`:

```bash
shadow-cljs watch examples/counter-slim-and-fast
```

Then open the example's hand-written [`counter_slim_and_fast/index.html`](counter_slim_and_fast/index.html) against the watched build.

## Cross-references

- [`spec/006-ReactiveSubstrate.md`](../../spec/006-ReactiveSubstrate.md) — the substrate contract the slim adapter satisfies.
- [`examples/reagent/counter/`](../reagent/counter/) — the canonical counter on the stock-Reagent bridge; this example's behavioural twin.
