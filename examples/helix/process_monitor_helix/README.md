# process_monitor_helix — Helix design-led example

A terminal-style two-pane process monitor: filterable process list
on the left, live log feed on the right, status tiles across the top.
Proves re-frame2 + Helix can build a substantive UI.

## What this demonstrates

- **Helix components consuming subs via `use-subscribe`** — `defnc`
  components with `(helix-adapter/use-subscribe …)` at the top of the
  body. The React-hooks idiom under Helix.
- **Signal-graph subscriptions** — filter chips → visible process
  list; process selection → relevant log slice. Two independent
  inputs into the sub graph; views read the projections.
- **Recurring `:dispatch-later` tick** — `:monitor/tick` appends
  synthetic log lines every interval. Proves a real reactive loop
  (not a static screenshot): the log pane updates live as new lines
  arrive.
- **Per-row dispatch from inside a `defnc`** — each row in the
  process list is a `defnc` component that takes `dispatch` off a
  `(rf/frame-handle)` and closes over it; clicking a row dispatches
  selection.

## Why this shape

The Helix member of the three-substrate design-led trio:

| Substrate | Example | Shape |
|---|---|---|
| Reagent | [`notebook`](../../reagent/notebook/) | Three-pane editor |
| UIx | [`dashboard_uix`](../../uix/dashboard_uix/) | Cards + sparklines |
| Helix | `process_monitor_helix` (this) | Terminal log viewer |

Three different substantive UIs, one per substrate; same shared
identity from
[`examples/_shared/css/style.css`](../../_shared/css/style.css).
Design-led examples prove polished visuals + interaction, not
platform features other examples already cover — no HTTP, no state
machines.

The folder name carries the `_helix` substrate suffix so the
top-level namespace doesn't collide with Reagent or UIx siblings.

## Files

```
process_monitor_helix/
  core.cljs    — seed data, events (including the tick loop), subs, defnc views, mount.
  index.html   — minimal host page.
```

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/process-monitor-helix
```

The watch build emits `main.js` into
`out/examples/process-monitor-helix/`; copy this folder's hand-written
[`index.html`](index.html) (and the shared assets it references under
[`../../_shared/`](../../_shared/)) alongside it, then serve
`out/examples/process-monitor-helix/` over HTTP.
(`npm run test:examples` does not build this example — it compiles and
serves only the three adapter testbeds; see
[`examples/helix/README.md`](../README.md).) Examples are test-free per
[`examples/README.md`](../../README.md).

## Cross-references

- [`spec/004-Views.md`](../../../spec/004-Views.md) — the view contract; Helix satisfies it without `reg-view`.
- [`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) — the substrate contract the Helix adapter satisfies.
- [`spec/002-Frames.md`](../../../spec/002-Frames.md) — `:dispatch-later` lives here.
- [`examples/reagent/notebook/`](../../reagent/notebook/) + [`examples/uix/dashboard_uix/`](../../uix/dashboard_uix/) — the other two design-led trio members.
- [`implementation/adapters/helix/`](../../../implementation/adapters/helix/) — the adapter implementation.
