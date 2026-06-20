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
npm run dev:example -- examples/dashboard-uix
```

One command: it stages this folder's hand-written
[`index.html`](index.html) + the shared `_shared/` assets next to the
compiled `main.js`, starts `shadow-cljs watch` (edits recompile live),
serves `out/examples/dashboard-uix/` on a free local port, and prints
the URL to open. Add `--no-watch` for a one-shot compile-and-serve.

(`npm run test:adapter-smokes` does not build this example — it compiles and
serves only the three adapter testbeds; see
[`examples/uix/README.md`](../README.md).) Examples are test-free per
[`examples/README.md`](../../README.md).

<details><summary>Advanced: raw <code>shadow-cljs watch</code></summary>

`npm run dev:example` wraps the raw watch + manual staging recipe. To
drive shadow-cljs directly: `shadow-cljs watch examples/dashboard-uix`
emits `main.js` into `out/examples/dashboard-uix/`; you then copy this
folder's [`index.html`](index.html) (and the shared assets under
[`../../_shared/`](../../_shared/)) alongside it and serve the output
dir yourself.

</details>

## Accessibility + responsive — what to copy, and a manual checklist

Because this is the UIx example meant to model a *polished* multi-pane
app, its controls and layout demonstrate the accessibility +
responsive shape a real UIx app should copy:

- **Tag filter chips are multi-select toggles** — each chip is a real
  `<button>` carrying `aria-pressed`, and the row is a `role="group"`
  with an `aria-label`. Assistive tech reads the on/off state, not just
  a visual class.
- **The range picker is a single-select mode control** — it implements
  the full WAI-ARIA radio-group idiom, not just the roles. The row is a
  `role="radiogroup"`; each chip is a `role="radio"` carrying
  `aria-checked`, so it announces as a one-of-N choice rather than
  independent buttons. It also honours the radio-group **keyboard
  contract**: a *roving tabindex* (only the checked radio is in the tab
  order) so Tab lands on the current selection, and arrow keys
  (Left/Up, Right/Down, with wrap) move the selection — with focus, since
  in a radio group selection follows focus.
- **Sparklines are decorative** (`aria-hidden="true"`) — the card's
  eyebrow, value, and label already carry the metric name + value as
  text, so the SVG is a visual restatement, not a separate nameless
  graphic to announce.
- **Layout stays within its box at every width** — the card grid floor
  is `minmax(min(100%, 280px), 1fr)` so a narrow viewport collapses to
  one full-width column instead of overflowing; two `@media`
  breakpoints stack the header and shrink the oversized H1 / card
  padding on tablet + phone.

Examples are **test-free** (no `*.spec.cjs` under `examples/`), so this
class of design-led polish is guarded by the manual checklist below
rather than a per-example browser spec. Run it when you touch this
example's markup or CSS (the WCAG palette-contrast + focus-ring
contracts on the shared stylesheet are already enforced statically by
`check-examples-assets`; the items here are the layout/semantics that
need an eye):

1. **Keyboard** — Tab reaches every tag chip; Enter/Space toggles each.
   The range picker is a radio group: Tab lands on the *currently
   selected* range only (roving tabindex), then Left/Up and Right/Down
   move the selection (with wrap) and the focus ring follows it. The
   focus ring is visible on every focused control.
2. **Screen reader** (VoiceOver / NVDA) — tag chips announce
   "pressed/not pressed"; range chips announce as a radio group with
   the selected one "checked", and arrowing through them re-announces
   the new selection; the sparklines are NOT announced.
3. **Narrow viewport** (≈360px, DevTools device toolbar) — no
   horizontal scrollbar; the grid is one column; the header stacks;
   the H1 does not overrun the edge.
4. **Wide viewport** (≥1180px) — the grid is multi-column; the header
   row aligns title left / controls right.

## Cross-references

- [`spec/004-Views.md`](../../../spec/004-Views.md) — the view contract; UIx satisfies it without `reg-view`.
- [`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) — the substrate contract the UIx adapter satisfies.
- [`examples/reagent/notebook/`](../../reagent/notebook/) + [`examples/helix/process_monitor_helix/`](../../helix/process_monitor_helix/) — the other two design-led trio members.
- [`implementation/adapters/uix/`](../../../implementation/adapters/uix/) — the adapter implementation.
