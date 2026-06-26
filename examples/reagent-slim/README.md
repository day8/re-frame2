# Reagent Slim — examples

Most adapters in re-frame2 are bridges: a thin shim that teaches the core how to talk to an existing rendering library. Reagent Slim is not that. It is its **own substrate** — a ground-up `reagent2.*` rewrite — rather than the thin bridge over stock Reagent that the canonical Reagent adapter (`day8/re-frame2-reagent`) ships. Because it is a distinct substrate with its own adapter, its example lives here under `examples/reagent-slim/` instead of in the stock-Reagent tree, in keeping with the per-substrate grouping convention (`examples/reagent/`, `examples/uix/`, `examples/helix/`). Background on the adapter itself is at [`implementation/adapters/reagent-slim/`](../../implementation/adapters/reagent-slim/) and [Spec 006 §Adapter shipping convention](../../spec/006-ReactiveSubstrate.md).

What does a from-scratch substrate cost you, as the author of an app? Almost nothing — and that is exactly what this directory is here to show. It holds a **single example**: the canonical counter dataflow, re-mounted on the slim substrate. Read the teaching source ([`counter_slim_and_fast/core.cljs`](counter_slim_and_fast/core.cljs)) beside the stock counter and you'll find the same idiomatic re-frame2 from the first line; the *only* thing that moves is the substrate underneath. You learn the dataflow, not the test rig.

## Layout

```
reagent-slim/
  counter_slim_and_fast/   <-- the canonical counter dataflow, mounted on day8/reagent-slim
```

The dataflow is identical to the stock-Reagent counter at [`../reagent/counter/`](../reagent/counter/). What changes is purely mechanical: every user-facing `reagent.*` import points at `reagent2.*`, and `rf/init!` is handed the slim adapter Var instead of the stock one. The CLJS namespace stays `counter-slim-and-fast.core` (build id `examples/counter-slim-and-fast`). That's the whole diff — which is precisely the claim the example exists to back up.

> **In-tree namespace vs published ABI.** The checked-in source requires the **in-tree** namespace `re-frame.adapter.reagent-slim` because the unrenamed monorepo build keeps both adapters on one classpath and must avoid an ns clash. That is *not* the spelling adopters use: the **published** `day8/reagent-slim` jar ships the adapter Var at the canonical, stock-identical `re-frame.adapter.reagent` (the release workflow renames it at publication). Adopter code therefore wires `(rf/init! re-frame.adapter.reagent/adapter)` exactly as for stock — you pick slim by deps coordinate, not by import line. See [`docs/guide/how-to/use-uix-helix-or-slim.md`](../../docs/guide/how-to/use-uix-helix-or-slim.md) and [`implementation/adapters/reagent-slim/DESIGN-RATIONALE.md`](../../implementation/adapters/reagent-slim/DESIGN-RATIONALE.md) §7. Do not cargo-cult the in-tree `-slim` namespace into published-app code.

## What the example demonstrates

- **`reagent-slim/counter_slim_and_fast/`** ([build id `examples/counter-slim-and-fast`](../../implementation/shadow-cljs.edn))
  The same `:counter/*` events and subs as the canonical Reagent counter, mounted on the slim substrate. The teaching source — events, subs, views, and the lazy client mount — lives in [`counter_slim_and_fast/core.cljs`](counter_slim_and_fast/core.cljs) and reads as idiomatic re-frame2; once again the only divergence from the stock counter is the substrate swap. Sharing the `:counter/*` event and sub ids with the stock counter breaks the example id-prefix convention on purpose: it is a **deliberate, documented exception**, and the reason is precisely the point being made — identical ids let the two counters prove adapter parity by being literally the same handlers. The views, by contrast, are *not* shared — `reg-view` auto-namespaces them under `:counter-slim-and-fast.core/*` rather than the stock `:counter.core/*`, so they stay distinct without you having to think about it.

## How to run

To iterate on the example interactively, from `implementation/`:

```bash
shadow-cljs watch examples/counter-slim-and-fast
```

Then open the example's hand-written [`counter_slim_and_fast/index.html`](counter_slim_and_fast/index.html) against the watched build.

## Cross-references

- [`spec/006-ReactiveSubstrate.md`](../../spec/006-ReactiveSubstrate.md) — the substrate contract the slim adapter satisfies.
- [`examples/reagent/counter/`](../reagent/counter/) — the canonical counter on the stock-Reagent bridge; this example's behavioural twin.
