# counter_uix — UIx substrate counter

The canonical counter, rendered through the UIx adapter. Same six
dominoes as [`examples/reagent/counter/`](../../reagent/counter/);
different substrate.

## What this demonstrates

- **`rf/init!` with the UIx adapter** — `(rf/init! uix-adapter/adapter)`.
  No default-adapter registry; the adapter spec map is passed
  explicitly.
- **`reg-event-db` / `reg-event-fx` / `reg-sub`** —
  *substrate-agnostic*. The exact same registrations as the Reagent
  counter; the artefact layer doesn't know which substrate is below.
- **`use-subscribe` hook (UIx idiomatic)** — components call
  `(uix-adapter/use-subscribe [:counter/value])` directly. The hook
  is the React idiom; no Reagent-style RAtom indirection.
- **`rf/dispatcher` for click handlers** — UIx components call
  `(rf/dispatcher)` to obtain `dispatch`, then close over it. There is
  no auto-injection in UIx — `reg-view` stays Reagent-only;
  UIx users write `defui` directly.
- **Shared frame-context** — the same React Context object the
  Reagent and Helix adapters consume. Cross-substrate parity is
  at the runtime layer.

## Why this shape

The smoke-test pair for the UIx adapter per [Spec 006 §Adapter
shipping convention Decision 7](../../../spec/006-ReactiveSubstrate.md).
Pair with [`examples/reagent/counter/`](../../reagent/counter/) and
[`examples/helix/counter_helix/`](../../helix/counter_helix/) to see
the substrate boundary cleanly.

The folder name carries the `-uix` namespace suffix so the
top-level namespace doesn't collide with `examples/reagent/counter/`
on the classpath.

## Files

```
counter_uix/
  core.cljs    — events, sub, defui view, mount.
  index.html   — minimal host page.
```

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/counter-uix
```

Run `npm run test:examples` once first so
`out/examples/counter-uix/index.html` is staged. Examples are
test-free per [`examples/README.md`](../../README.md); the UIx adapter
smoke lives at
[`implementation/adapters/uix/testbed/spec.cjs`](../../../implementation/adapters/uix/testbed/spec.cjs).

## Cross-references

- [`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) — the substrate contract the UIx adapter satisfies.
- [`spec/Conventions.md` §Adapter test matrix policy](../../../spec/Conventions.md#adapter-test-matrix-policy) — why all three substrate counters exist.
- [`examples/reagent/counter/`](../../reagent/counter/) + [`examples/helix/counter_helix/`](../../helix/counter_helix/) — the other two substrate variants.
- [`implementation/adapters/uix/`](../../../implementation/adapters/uix/) — the adapter implementation.
