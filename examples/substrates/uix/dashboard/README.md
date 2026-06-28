# An analytics dashboard with sparklines

This example is an analytics dashboard called "Atlas". It's a grid of
metric cards, a row of filter chips along the top, and a time-range
picker; each card carries a small hand-drawn SVG sparkline. Click a
chip to show or hide a category of metric; pick a range to set how
much history each sparkline draws — everything redraws the instant you
click. That's the whole app: a [view](../../../../docs/core/glossary.md#view)
tree reading a [subscription](../../../../docs/core/glossary.md#subscription)
graph, on the [UIx](https://github.com/pitch-io/uix)
[substrate](../../../../docs/core/glossary.md#substrate). No chart
library, no HTTP, and no
[state machine](../../../../docs/machines/glossary.md#machine).

The point: re-frame2's core is substrate-agnostic. Swap Reagent for UIx
and your [events](../../../../docs/core/glossary.md#event),
[subscriptions](../../../../docs/core/glossary.md#subscription), and
[app-db](../../../../docs/core/glossary.md#app-db) stay exactly the same.
Only the way [hiccup](../../../../docs/core/glossary.md#hiccup) reaches
React changes.

## What this demonstrates

The lesson isn't the dashboard. It's how *little* glue sits between the
data and the pixels. Three ideas carry the example.

**Two separate controls feed one subscription.** The chips pick which
categories of metric to show. The range picker picks how much history to
show. In the [app-db](../../../../docs/core/glossary.md#app-db) they're
unrelated — a `:dashboard/active-tags` set and a `:dashboard/range`
keyword, written by two small
[event handlers](../../../../docs/core/glossary.md#event-handler). But both
feed a single derived [subscription](../../../../docs/core/glossary.md#subscription),
`:dashboard/visible-metrics`, which filters the cards by tag *and* trims
each metric's series to the last N points:

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

Toggle a chip or change the range, and the framework re-runs just this
one function — and only if one of its three inputs actually changed (by
`=`). The view never sees two events. It asks for the visible metrics
and gets one settled answer. That's the
[derivation graph](../../../../docs/core/glossary.md#the-derivation-graph)
doing the bookkeeping you'd otherwise do by hand.

**You read state through a React hook.** UIx components are `defui`
functions, and inside one you call
`(uix-adapter/use-subscribe [:dashboard/visible-metrics])` at the top of
the body — instead of dereferencing a
[`subscribe`](../../../../docs/core/glossary.md#subscribe--derive) the way
you would in Reagent. It's the same subscription and the same cached
value. The [adapter](../../../../docs/core/glossary.md#adapter) just hands
it to you in the shape React's rules-of-hooks expect.

To [dispatch](../../../../docs/core/glossary.md#dispatch) on a click, the
chips read the frame's `:dispatch` from
[`(rf/capture-frame)`](../../../../docs/core/glossary.md#capture-frame) at
render time. That's the idiomatic way to dispatch from inside a UIx
event callback.

**The sparklines are pure functions, not a chart widget.** Each
sparkline is one SVG `<path>`. Its `d` string is computed straight from
the metric's series: find min and max, scale into a 100×30 viewBox, emit
`M…L…L…`. That's it — a dozen lines of arithmetic in `sparkline-path`,
with no dependency, no canvas, and no chart engine. The path is a pure
function of the (already-trimmed) series, so changing the range redraws
every sparkline for free. There's nothing extra to wire.

## Why this shape

This is the UIx member of a design-led trio. Each member is a different,
substantial UI built on a different
[substrate](../../../../docs/core/glossary.md#substrate). Together they
make one point: the substrate is a swappable back end, not the
framework.

| Substrate | Example | Shape |
|---|---|---|
| Reagent | [`notebook`](../../../core/notebook/) | Three-pane editor |
| UIx | `dashboard_uix` (this) | Cards + sparklines |
| Helix | [`process_monitor_helix`](../../helix/process_monitor/) | Terminal log viewer |

Three different apps, one per substrate — yet they all wear the same
"Editorial Warm" identity from
[`examples/_shared/css/style.css`](../../../_shared/css/style.css). So what
looks *different* is the layout and the interaction, never the brand. A
design-led example earns its keep by showing that polished visuals and
interaction hold up on its substrate. It skips the platform features
(managed HTTP, state machines, routing) that other examples cover, so
the dataflow stays the star.

The mount is the ordinary re-frame2 boot.
[`init!`](../../../../docs/core/glossary.md#init) installs the UIx adapter.
Then the tree renders inside a
[`frame-provider`](../../../../docs/core/glossary.md#frame-provider) given
`{:id app-frame :initial-events [[:dashboard/initialise]]}`: the `:id`
stands the app [frame](../../../../docs/core/glossary.md#frame) up —
creating it on the first mount, reusing it untouched on a hot reload — and
`:initial-events` fires once on creation to seed the
[app-db](../../../../docs/core/glossary.md#app-db) before the first paint.
With the tree inside the provider, every `use-subscribe` and
`capture-frame` resolves to that frame through React context; render with
*no* provider and the hooks raise `:rf.error/no-frame-context` —
[identity is carried, not
found](../../../../docs/core/glossary.md#frame-identity-is-carried-not-found),
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

Edits recompile live, and the command prints a local URL to open. Add
`--no-watch` for a one-shot compile-and-serve.

## Accessibility + responsive — what to copy

This is the UIx example meant to model a *polished* multi-pane app, so
its controls and layout show the accessibility + responsive shape a real
UIx app should copy:

- **Tag filter chips are multi-select toggles** — each chip is a real
  `<button>` carrying `aria-pressed`, and the row is a `role="group"`
  with an `aria-label`. Assistive tech reads the on/off state, not just
  a visual class.
- **The range picker is a single-select mode control** — it implements
  the full WAI-ARIA radio-group idiom, not just the roles. The row is a
  `role="radiogroup"`, and each chip is a `role="radio"` carrying
  `aria-checked`. So it announces as a one-of-N choice, not as
  independent buttons. It also honours the radio-group **keyboard
  contract**. A *roving tabindex* puts only the checked radio in the tab
  order, so Tab lands on the current selection. Arrow keys (Left/Up,
  Right/Down, with wrap) move the selection — and the focus with it,
  since in a radio group selection follows focus.
- **Sparklines are decorative** (`aria-hidden="true"`) — the card's
  eyebrow, value, and label already carry the metric name + value as
  text, so the SVG is a visual restatement, not a separate nameless
  graphic to announce.
- **Layout stays within its box at every width** — the card grid floor
  is `minmax(min(100%, 280px), 1fr)`, so a narrow viewport collapses to
  one full-width column instead of overflowing. Two `@media` breakpoints
  stack the header and shrink the oversized H1 and card padding on
  tablet + phone.

## Cross-references

- [`examples/core/notebook/`](../../../core/notebook/) + [`examples/substrates/helix/process_monitor/`](../../helix/process_monitor/) — the other two design-led trio members.
