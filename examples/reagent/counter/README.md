# counter — the smallest possible re-frame2 app

A minimal counter against the re-frame2 API: one slice of `app-db`,
three event handlers, one subscription, one view. This is the
canonical first example — everything else in the catalogue layers on
top of this dataflow shape.

## What this demonstrates

- **`reg-event`** — three handlers (`:counter/initialise`,
  `:counter/inc`, `:counter/dec`). Each returns `{:db …}`.
- **`reg-sub`** — one subscription (`:counter/value`) deriving the
  count from the slice.
- **`reg-view`** — the canonical Form-1 view-registration macro
  (Var-reference style), with `dispatch` and `subscribe` auto-injected
  as lexical bindings. The injected bindings resolve at render time to
  the frame in scope (the default frame here).
- **`rf/init!` + the Reagent adapter** — the load-bearing wiring step:
  every app passes the adapter spec map directly; no default-adapter
  registry, no implicit substrate selection.

Cross-substrate twins live at
[`examples/uix/counter_uix/`](../../uix/counter_uix/) and
[`examples/helix/counter_helix/`](../../helix/counter_helix/) — the
same dataflow rendered through hooks instead of Reagent's RAtom
substrate. Use the three side-by-side to see exactly which layer is
substrate-agnostic and which is substrate-specific.

## Why this shape

The pedagogical entry point. CP-1 (event handler) + CP-2
(subscription) + CP-4 (registered view) introduced in one ~40-line
file with no schemas, no machines, no HTTP, no routing — just the six
dominoes of re-frame2's dataflow with nothing distracting from them.

## Files

```
counter/
  core.cljs    — events, sub, view, mount
  index.html   — minimal host page
```

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/counter
```

The watch build emits `main.js` into `out/examples/counter/`; copy
this folder's hand-written [`index.html`](index.html) (and the shared
assets it references under [`../../_shared/`](../../_shared/))
alongside it, then serve `out/examples/counter/` over HTTP.
(`npm run test:adapter-smokes` does not build this example — it compiles and
serves only the three adapter testbeds; see
[`examples/reagent/README.md`](../README.md).) Examples are test-free per
[`examples/README.md`](../../README.md); real-regression coverage
lives in `npm run test:cljs` and the framework gates.

## Cross-references

- [Construction Prompts CP-1, CP-2, CP-4](../../../spec/Construction-Prompts.md) — the prompts this example instantiates.
- [`spec/002-Frames.md`](../../../spec/002-Frames.md) — the dispatch / drain semantics under the buttons.
- [`spec/004-Views.md`](../../../spec/004-Views.md) §`reg-view` — the Form-1 view-registration macro.
- [`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) — the Reagent reactive substrate the subscription runs on.
- [`examples/reagent-slim/counter_slim_and_fast/`](../../reagent-slim/counter_slim_and_fast/) — the same counter re-mounted on `day8/reagent-slim` (bundle-isolation contrast pair).
- [`examples/uix/counter_uix/`](../../uix/counter_uix/) + [`examples/helix/counter_helix/`](../../helix/counter_helix/) — UIx and Helix substrate variants.
