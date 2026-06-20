# flows — Spec 013 worked example

A shopping cart whose subtotal and total are *materialised computed
state* — registered flows that derive from `app-db` and write their
results back into `app-db`. This is THE canonical Flows exemplar: the
companion to [`spec/013-Flows.md`](../../../spec/013-Flows.md).

## What this demonstrates

- **`rf/reg-flow`** — registered, runtime-toggleable computed-state
  declaration. The two production flows here are `:cart/subtotal` and
  `:cart/total`; the runtime-toggleable third is `:cart/discount-rate`
  (a feature gate).
- **Flow-reads-flow topological cascade** — `:cart/total` reads
  `[:cart :subtotal]` (another flow's output) plus
  `[:cart :discount-rate]`. The runtime sorts the two flows so
  `:cart/subtotal` always runs first; both settle in one walk, right
  after the handler and before the `:db` install.
- **`:rf.fx/reg-flow` / `:rf.fx/clear-flow`** — registering and
  clearing a flow from inside an event handler. The discount gate
  toggles on/off mid-event; v1's `on-changes` interceptor (the closest
  v1 equivalent) cannot do this — it wires into specific events at
  registration time. Flows are runtime-registered and
  runtime-clearable.
- **Reading flow output from a handler** — `:checkout/place-order`
  reads `[:cart :total]` directly out of `db`, with no subscribe
  ceremony. Materialised state IS app-db state.
- **Reading flow output via a plain sub** — a sub over the flow's
  `:path` is just a sub; nothing special about reading materialised
  state.

## Why a flow and not a sub

The load-bearing question Spec 013 §When (and when not) to use a flow
answers. Most derived values should be SUBSCRIPTIONS — lighter, in the
per-frame sub-cache, no `app-db` write. Reach for a flow ONLY when the
derived value is part of the application's *state*:

- other event handlers read it as plain `app-db` data,
- it should survive SSR hydration / time-travel revert / app-db
  serialisation (sub-cache contents do not survive the wire),
- the derivation is stable enough to be worth registering.

The cart's subtotal + total tick all three boxes. Most things don't.

## Files

```
flows/
  core.cljs    — :cart slice schema, the three flows, demo events,
                 view, mount.
  index.html   — minimal host page.
```

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/flows
```

The watch build emits `main.js` into `out/examples/flows/`; copy this
folder's hand-written [`index.html`](index.html) (and the shared
assets it references under [`../../_shared/`](../../_shared/))
alongside it, then serve `out/examples/flows/` over HTTP.
(`npm run test:adapter-smokes` does not build this example — it compiles and
serves only the three adapter testbeds; see
[`examples/reagent/README.md`](../README.md).) Examples are test-free per
[`examples/README.md`](../../README.md); flow contract testing lives
in `implementation/flows/test/`.

## Cross-references

- [`spec/013-Flows.md`](../../../spec/013-Flows.md) — the normative spec; every section title in the docstring matches a section there.
- [`spec/013-Flows.md` §When (and when not) to use a flow](../../../spec/013-Flows.md) — the load-bearing decision this example illustrates.
- [`spec/Pattern-LongRunningWork.md`](../../../spec/Pattern-LongRunningWork.md) — separate but related pattern (long-running work, not materialised state).
