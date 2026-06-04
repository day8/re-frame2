# counter_helix — Helix substrate counter

The canonical counter, rendered through the Helix adapter. Same six
dominoes as [`examples/reagent/counter/`](../../reagent/counter/);
different substrate.

## What this demonstrates

- **`rf/init!` with the Helix adapter** — `(rf/init! helix-adapter/adapter)`.
  No default-adapter registry; the adapter spec map is passed
  explicitly.
- **`reg-event-db` / `reg-event-fx` / `reg-sub`** —
  *substrate-agnostic*. The exact same registrations as the Reagent
  and UIx counters; the artefact layer doesn't know which substrate
  is below.
- **`use-subscribe` hook (Helix idiomatic)** — components call
  `(helix-adapter/use-subscribe [:counter/value])` directly.
- **`(:dispatch (rf/frame-handle))` for click handlers** — Helix
  components take `dispatch` off a `(rf/frame-handle)` and close over
  it. The handle captures the render-time frame, so the closed-over
  `dispatch` keeps targeting that frame even from an async callback.
  There is no auto-injection in Helix — `reg-view` stays Reagent-only;
  Helix users write `defnc` directly.
- **Shared frame-context** — the same React Context object the
  Reagent and UIx adapters consume. Cross-substrate parity is at the
  runtime layer.

## Why this shape

The smoke-test pair for the Helix adapter per [Spec 006 §Adapter
shipping convention Decision 7](../../../spec/006-ReactiveSubstrate.md).
Pair with [`examples/reagent/counter/`](../../reagent/counter/) and
[`examples/uix/counter_uix/`](../../uix/counter_uix/) to see the
substrate boundary cleanly.

The folder name carries the `_helix` namespace suffix so the
top-level namespace doesn't collide with Reagent or UIx siblings on
the classpath.

## Files

```
counter_helix/
  core.cljs    — events, sub, defnc view, mount.
  index.html   — minimal host page.
```

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/counter-helix
```

The watch build emits `main.js` into `out/examples/counter-helix/`;
copy this folder's hand-written [`index.html`](index.html) (and the
shared assets it references under [`../../_shared/`](../../_shared/))
alongside it, then serve `out/examples/counter-helix/` over HTTP.
(`npm run test:examples` does not build this example — it compiles and
serves only the three adapter testbeds; see
[`examples/helix/README.md`](../README.md).) Examples are
test-free per [`examples/README.md`](../../README.md); the Helix
adapter smoke lives at
[`implementation/adapters/helix/testbed/spec.cjs`](../../../implementation/adapters/helix/testbed/spec.cjs).

## Cross-references

- [`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) — the substrate contract the Helix adapter satisfies.
- [`spec/Conventions.md` §Adapter test matrix policy](../../../spec/Conventions.md#adapter-test-matrix-policy) — why all three substrate counters exist.
- [`examples/reagent/counter/`](../../reagent/counter/) + [`examples/uix/counter_uix/`](../../uix/counter_uix/) — the other two substrate variants.
- [`implementation/adapters/helix/`](../../../implementation/adapters/helix/) — the adapter implementation.
