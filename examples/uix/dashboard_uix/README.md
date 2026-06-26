# dashboard_uix — UIx design-led example

An analytics dashboard — "Atlas" — built on the [UIx](https://github.com/pitch-io/uix)
[substrate](../../../docs/guide/glossary.md#substrate): a grid of metric
cards with hand-drawn SVG sparklines, a row of filter chips, and a
time-range picker. There's no chart library, no HTTP, no
[state machine](../../../docs/machines/glossary.md#machine) — just a
[view](../../../docs/guide/glossary.md#view) tree reading a
[subscription](../../../docs/guide/glossary.md#subscription) graph. The
point it makes is that re-frame2's core is genuinely
substrate-agnostic: swap Reagent for UIx and your
[events](../../../docs/guide/glossary.md#event),
[subscriptions](../../../docs/guide/glossary.md#subscription), and
[app-db](../../../docs/guide/glossary.md#app-db) don't move an inch —
only the way [hiccup](../../../docs/guide/glossary.md#hiccup) reaches
React changes.

## What this demonstrates

The interesting thing here isn't the dashboard; it's how *little* glue
sits between the data and the pixels. Three ideas carry the example.

**Two independent controls feed one composed subscription.** The chips
pick which categories of metric you care about; the range picker picks
how much history to show. They look unrelated, and in the
[app-db](../../../docs/guide/glossary.md#app-db) they *are* — a
`:dashboard/active-tags` set and a `:dashboard/range` keyword, written
by two small [event handlers](../../../docs/guide/glossary.md#event-handler).
But both flow into a single derived projection,
`:dashboard/visible-metrics`, which filters the cards by tag *and*
windows each metric's series to the last N points:

```clojure
(rf/reg-sub :dashboard/visible-metrics
  :<- [:dashboard/metrics]
  :<- [:dashboard/active-tags]
  :<- [:dashboard/selected-range]
  (fn [[metrics active-tags {:keys [points]}] _]
    (->> metrics
         (filter #(contains? active-tags (:tag %)))
         (map (fn [m] (update m :series #(vec (take-last points %))))))))
```

Toggle a chip or change the range and the framework re-runs exactly
this one function — and *only* if one of its three inputs actually
moved (by `=`). The view doesn't know two things happened; it asks for
the visible metrics and gets a settled answer. That's the
[derivation graph](../../../docs/guide/glossary.md#the-derivation-graph)
doing the bookkeeping you'd otherwise do by hand.

**The UIx hook idiom, end-to-end.** UIx components are `defui` functions
that read state through a React hook —
`(uix-adapter/use-subscribe [:dashboard/visible-metrics])` at the top
of the body — instead of dereferencing a [`subscribe`](../../../docs/guide/glossary.md#subscribe--derive)
the way you would in Reagent. It's the same subscription, the same
cached derivation; the
[adapter](../../../docs/guide/glossary.md#adapter) just hands it to you
in the shape React's rules-of-hooks expect. For the imperative side —
dispatching on a click — the chips grab the frame's `:dispatch` from
[`(rf/frame-handle)`](../../../docs/guide/glossary.md#frame-handle) at
render time, which is the idiomatic way to reach
[dispatch](../../../docs/guide/glossary.md#dispatch) from inside a UIx
event callback.

**The sparklines are pure functions, not a chart widget.** Each
sparkline is one SVG `<path>` whose `d` string is computed straight
from the metric's series — find min and max, normalise into a 100×30
viewBox, emit `M…L…L…`. That's it: a dozen lines of arithmetic in
`sparkline-path`, no dependency, no canvas, no chart engine. Because
the path is a pure function of the (already-windowed) series, changing
the range redraws every sparkline as a free consequence of the
projection above — there's nothing extra to wire.

## Why this shape

This is the UIx member of a three-substrate design-led trio. Each one is
a different, substantial UI built on a different
[substrate](../../../docs/guide/glossary.md#substrate), and the trio
exists to make a single point loudly: the substrate is a swappable
back end, not the framework.

| Substrate | Example | Shape |
|---|---|---|
| Reagent | [`notebook`](../../reagent/notebook/) | Three-pane editor |
| UIx | `dashboard_uix` (this) | Cards + sparklines |
| Helix | [`process_monitor_helix`](../../helix/process_monitor_helix/) | Terminal log viewer |

Three genuinely different apps, one per substrate — yet they all wear the
same "Editorial Warm" identity from
[`examples/_shared/css/style.css`](../../_shared/css/style.css), so what
your eye sees as *different* is the layout and the interaction, never the
brand. A design-led example earns its keep by proving polished visuals
and interaction hold up on its substrate; it deliberately skips the
platform features (managed HTTP, state machines, routing) that other
examples already cover, so the dataflow stays the star.

A word on the folder name. The `_uix` suffix isn't decoration: it's the
uniform per-substrate naming convention every UIx example follows
(`counter_uix`, `login_uix`, and this one), and Clojure namespaces are
global, so the suffix keeps each UIx namespace unique. It earns its keep
on the examples that share a base name across substrates — `counter` /
`counter_uix` / `counter_helix` would otherwise all want `counter.core`
— and it's applied here for consistency even though this app's design-led
Reagent counterpart carries its own name (`notebook`), so there's no
literal `dashboard.core` to collide with today.

The mount itself is the ordinary re-frame2 boot dance —
[`init!`](../../../docs/guide/glossary.md#init) installs the UIx
adapter, [`reg-frame`](../../../docs/guide/glossary.md#registration)
registers the app [frame](../../../docs/guide/glossary.md#frame), a
[`dispatch-sync`](../../../docs/guide/glossary.md#dispatch-sync) seeds
the [app-db](../../../docs/guide/glossary.md#app-db) before the first
paint, and the tree renders inside a `frame-provider-existing` so every
`use-subscribe` and `frame-handle` resolves to that frame through React
context. Render with *no* provider and the hooks raise
`:rf.error/no-frame-context` — [identity is carried, not
found](../../../docs/guide/glossary.md#frame-identity-is-carried-not-found),
even here.

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
