# dashboard_uix — UIx design-led example

An analytics dashboard: a grid of metric cards with inline SVG
sparklines, filter chips by tag, and a time-range picker. Proves
re-frame2 + UIx can build a substantive UI.

## What this demonstrates

- **UIx components consuming subs via `use-subscribe`** — `defui`
  components with `(uix-adapter/use-subscribe [:dashboard/visible-metrics])`
  at the top of the body. The React-hooks idiom, end-to-end.
- **Signal-graph subscriptions composing inputs** — tag filter +
  time range compose into one re-derived projection
  (`:dashboard/visible-metrics`). The chips select a tag subset; the
  range picker selects how many trailing points each sparkline draws
  and what the header label says. One projection, two inputs,
  cleanly composed in the sub graph.
- **Inline SVG sparklines computed in pure CLJS** — no chart
  library. The sparkline path is a derived projection of each
  metric's series; pure CLJS arithmetic, hiccup-shaped output.
- **Two re-deriving controls** — chips (which cards show) and the
  time-range picker (how each card renders). Independent inputs into
  one signal graph.

## Why this shape

The UIx member of the three-substrate design-led trio:

| Substrate | Example | Shape |
|---|---|---|
| Reagent | [`notebook`](../../reagent/notebook/) | Three-pane editor |
| UIx | `dashboard_uix` (this) | Cards + sparklines |
| Helix | [`process_monitor_helix`](../../helix/process_monitor_helix/) | Terminal log viewer |

Three different substantive UIs, one per substrate; same shared
identity from
[`examples/_shared/css/style.css`](../../_shared/css/style.css).
Design-led examples prove polished visuals + interaction, not platform
features other examples already cover — no HTTP, no state machines.

The folder name carries the `_uix` substrate suffix so the
top-level namespace doesn't collide with Reagent siblings.

## Files

```
dashboard_uix/
  core.cljs    — seed data, events, signal-graph subs, sparkline computation, defui views, mount.
  index.html   — minimal host page.
```

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/dashboard-uix
```

Run `npm run test:examples` once first so the example's `index.html`
is staged. Examples are test-free per
[`examples/README.md`](../../README.md).

## Cross-references

- [`spec/004-Views.md`](../../../spec/004-Views.md) — the view contract; UIx satisfies it without `reg-view`.
- [`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) — the substrate contract the UIx adapter satisfies.
- [`examples/reagent/notebook/`](../../reagent/notebook/) + [`examples/helix/process_monitor_helix/`](../../helix/process_monitor_helix/) — the other two design-led trio members.
- [`implementation/adapters/uix/`](../../../implementation/adapters/uix/) — the adapter implementation.
