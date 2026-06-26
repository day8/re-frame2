# process_monitor_helix — Helix design-led example

A terminal-style two-pane process monitor: a filterable process list on
the left, a live log feed scrolling on the right, status tiles across the
top. The point of the thing is to show that re-frame2 and **Helix** —
the most React-native of the three substrates, all `defnc` and hooks —
build a substantive, *moving* UI without giving up any of re-frame2's
shape. Filter chips, row selection, and a log pane that gains a fresh
line every ~1.8s on its own, all driven through the ordinary loop.

The load-bearing idea, and the reason this example is worth reading
rather than just running: that self-advancing tick is the trickiest
honest thing to get right in a UI like this, and it's done here without a
single `setTimeout` or cancel flag in sight. More on that below — it's
the interesting part.

## What this demonstrates

The fun of this example isn't the terminal aesthetic; it's how
unremarkable the wiring stays even as the UI starts moving on its own.
Four things carry it — and the live tick loop, third below, is where the
real care went.

- **Helix views read state through a hook.** Each pane is a `defnc`
  component that pulls its slice of state off a [subscription](../../../docs/guide/glossary.md#subscription)
  at the top of its body — `(helix-adapter/use-subscribe [:process-monitor/totals])`
  in `tiles`, `[:process-monitor/visible-logs]` in `log-stream`, and so
  on. That's the React-hooks idiom end-to-end: a sub recomputes, the hook
  re-renders the component, nothing else moves. No `reg-view`, no
  Reagent-style reactive deref — Helix satisfies the [view](../../../docs/guide/glossary.md#view)
  contract on its own terms.

- **Two controls feed one projection.** The interesting [subscription](../../../docs/guide/glossary.md#subscription)
  is `:process-monitor/visible-logs`: it folds the raw log list, the set
  of active levels, and the selected process into the one slice the log
  pane actually renders. Clicking a filter chip changes which levels pass;
  clicking a process row narrows the feed to that process's pid. Two
  independent inputs, one re-derived projection — this is the
  [derivation graph](../../../docs/guide/glossary.md#the-derivation-graph)
  earning its keep. The view just reads the answer; it never filters
  anything itself.

- **A live tick loop that is *lifecycle-owned*, not fire-and-forget.**
  A recurring [event](../../../docs/guide/glossary.md#event),
  `:process-monitor/tick`, appends a synthetic log line and reschedules
  itself via the [`:dispatch-later`](../../../docs/guide/glossary.md#effect)
  effect — so the pane keeps moving with no interaction, proving this is a
  real reactive loop and not a screenshot. Here's the catch.
  `:dispatch-later` has *no cancel API*, so a naive self-rescheduling
  chain has no off switch: re-init the app (a hot reload, a re-mount) and
  the old chain doesn't stop — it just gains a sibling, and now two of
  them append lines forever into a [frame](../../../docs/guide/glossary.md#frame)
  that may not even be on screen. The fix is a **generation guard**, and
  it's pleasingly small: every scheduled tick carries the
  `:process-monitor/tick-gen` it was armed under, and a tick from a retired
  generation simply no-ops when it lands — no cancellation needed, because
  a stale tick just declines to do anything. `:process-monitor/initialise`
  bumps the generation to retire any old chain; `:process-monitor/stop`
  bumps it *without* rescheduling, which kills the chain dead. Both hang
  off the `monitor` component's `use-effect` — mount arms the loop,
  unmount retires it — so you get exactly one live tick per interval no
  matter how many times the component churns, and zero once it unmounts.
  (This is the same move the 7GUIs timer makes with its `:tick-gen`
  guard — the local precedent for living without a cancel API.)

- **Per-row dispatch, captured the right way across the hook
  boundary.** Each process row and each filter chip is a `defnc` that
  grabs `dispatch` off a [`frame-handle`](../../../docs/guide/glossary.md#frame-handle)
  — `(:dispatch (rf/frame-handle))` — and closes over it, so clicking a
  row [dispatches](../../../docs/guide/glossary.md#dispatch)
  `[:process-monitor/select-process id]` into the right frame. Same trick the
  `monitor` `use-effect` uses to carry the frame into its mount/unmount
  callbacks: grab the handle while the frame is in scope, use it later.

## Why this shape

Everything above the `SUBSTRATE BOUNDARY` divider in `core.cljs` — the
seed data, the [events](../../../docs/guide/glossary.md#event) (tick loop
included), and the [subscriptions](../../../docs/guide/glossary.md#subscription)
— never mentions Helix. It's plain [app-db](../../../docs/guide/glossary.md#app-db),
events, and subs, and it would run unchanged on any substrate. Only the
`defnc` views and the mount below the divider are Helix-specific. That's
the lesson the divider is there to teach: in re-frame2 the
[substrate](../../../docs/guide/glossary.md#substrate) is a thin rendering
choice at the edge, and the interesting machinery lives in the
substrate-agnostic core. (Unlike the counter/login pair, this isn't a
cross-substrate parity twin — the three design-led examples are
genuinely different apps, not one dataflow ported three ways. The
boundary they teach is the same regardless.)

This is the Helix member of the three-substrate design-led trio:

| Substrate | Example | Shape |
|---|---|---|
| Reagent | [`notebook`](../../reagent/notebook/) | Three-pane editor |
| UIx | [`dashboard_uix`](../../uix/dashboard_uix/) | Cards + sparklines |
| Helix | `process_monitor_helix` (this) | Terminal log viewer |

Three different substantive UIs, one per substrate, sharing one visual
identity from
[`examples/_shared/css/style.css`](../../_shared/css/style.css). Design-led
examples exist to prove polished visuals and interaction, not to replay
platform features other examples already cover — so there's deliberately
no HTTP and no state machines here.

## Files

```
process_monitor_helix/
  core.cljs    — seed data, events (including the tick loop), subs, defnc views, mount.
  index.html   — minimal host page.
```

## How to run

```bash
# From implementation/:
npm run dev:example -- examples/process-monitor-helix
```

This starts a live-reloading `shadow-cljs watch` and prints a local URL to
open. Add `--no-watch` for a one-shot compile-and-serve.
