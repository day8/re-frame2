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

The folder name carries the `_helix` substrate suffix so the top-level
namespace doesn't collide with its Reagent or UIx siblings.

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

One command: it stages this folder's hand-written
[`index.html`](index.html) + the shared `_shared/` assets next to the
compiled `main.js`, starts `shadow-cljs watch` (edits recompile live),
serves `out/examples/process-monitor-helix/` on a free local port, and
prints the URL to open. Add `--no-watch` for a one-shot compile-and-serve.

(`npm run test:adapter-smokes` does not build this example — it compiles and
serves only the three adapter testbeds; see
[`examples/helix/README.md`](../README.md).) Examples are test-free per
[`examples/README.md`](../../README.md).

<details><summary>Advanced: raw <code>shadow-cljs watch</code></summary>

`npm run dev:example` wraps the raw watch + manual staging recipe. To
drive shadow-cljs directly: `shadow-cljs watch examples/process-monitor-helix`
emits `main.js` into `out/examples/process-monitor-helix/`; you then copy
this folder's [`index.html`](index.html) (and the shared assets under
[`../../_shared/`](../../_shared/)) alongside it and serve the output dir
yourself.

</details>

## Design-led runtime — what to copy, and a manual checklist

This is the Helix member of the design-led trio, so its job is to prove
*polished interaction*, not merely that it compiles — and those are two
different claims. The automated
[`test:examples-compile`](../../TESTING.md#compile-coverage-gate-testexamples-compile)
gate catches namespace / `:init-fn` / `:require` / Helix-form regressions,
but it **never serves the page**. So everything that only goes wrong once
the page is actually live — a blank render, a broken `_shared` asset path,
a stalled tick loop, dead filter chips, mobile overflow — sails straight
past a green compile without a murmur.

That gap is covered by hand on purpose. Examples are **test-free** (no
`*.spec.cjs` under `examples/`, per [rf2-8cevm](../../README.md)), so this
design-led class of regression is guarded by the manual checklist below
rather than a per-example browser spec — the same policy the UIx
[`dashboard_uix`](../../uix/dashboard_uix/) sibling follows. The shared
stylesheet's WCAG palette-contrast + focus-ring contracts are already
enforced statically by `check-examples-assets` (`npm run test:script-policy`);
the items here are the live render + interaction that still need a human
eye.

Run it (`npm run dev:example -- examples/process-monitor-helix`) whenever
you touch this example's markup, CSS, or dataflow:

1. **Nonblank render** — the page paints the two-pane shell: status tiles
   across the top, the process pane on the left, the log pane on the right.
   Neither pane is empty; the `_shared` "Editorial Warm" stylesheet is
   applied (warm paper background, not unstyled white).
2. **Live tick loop** — the log pane gains a fresh line every ~1.8s
   without interaction (`:process-monitor/tick`). It updates live; it is not a
   static screenshot.
3. **Filter chips interact** — clicking the `info` / `warn` / `error`
   chips toggles them (`is-on` class) and removes/restores matching log
   lines. Toggling all three off empties the log list; restoring one
   brings its lines back.
4. **Row selection filters** — clicking a process row selects it
   (`is-selected`) and narrows the log pane to that process's pid; clicking
   it again clears the selection and restores the full feed.
5. **Narrow viewport** (≈360–560px, DevTools device toolbar) — no
   document-level horizontal scrollbar; the panes stack (≤900px) and the
   summary tiles + row/log tracks collapse cleanly (≤560px). The declared
   `width=device-width` viewport renders without overflow on phones.
6. **Wide viewport** (≥1180px) — the canonical desktop two-pane shell:
   process pane beside log pane, tiles in a single row across the head.

Capture a screenshot at one narrow and one wide width when changing layout
CSS — that DOM/visual evidence is what catches the broken-asset-path /
broken-render regressions a compile-only check cannot see.

## Cross-references

- [`spec/004-Views.md`](../../../spec/004-Views.md) — the view contract; Helix satisfies it without `reg-view`.
- [`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) — the substrate contract the Helix adapter satisfies.
- [`spec/002-Frames.md`](../../../spec/002-Frames.md) — `:dispatch-later` lives here.
- [`examples/reagent/notebook/`](../../reagent/notebook/) + [`examples/uix/dashboard_uix/`](../../uix/dashboard_uix/) — the other two design-led trio members.
- [`implementation/adapters/helix/`](../../../implementation/adapters/helix/) — the adapter implementation.
