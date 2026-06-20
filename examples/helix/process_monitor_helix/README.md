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
- **Recurring `:dispatch-later` tick** — `:process-monitor/tick` appends
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
npm run dev:example -- examples/process-monitor-helix
```

One command: it stages this folder's hand-written
[`index.html`](index.html) + the shared `_shared/` assets next to the
compiled `main.js`, starts `shadow-cljs watch` (edits recompile live),
serves `out/examples/process-monitor-helix/` on a free local port, and
prints the URL to open. Add `--no-watch` for a one-shot compile-and-serve.

(`npm run test:examples` does not build this example — it compiles and
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
*polished interaction*, not just that it compiles. The automated
[`test:examples-compile`](../../TESTING.md#compile-coverage-gate-testexamples-compile)
gate catches namespace / `:init-fn` / `:require` / Helix-form regressions,
but it **never serves the page** — so a blank render, a broken `_shared`
asset path, a stalled tick loop, dead filter chips, or mobile overflow can
still pass a green compile. Examples are **test-free** (no `*.spec.cjs`
under `examples/`, per [rf2-8cevm](../../README.md)), so this design-led
class of regression is guarded by the manual checklist below rather than a
per-example browser spec — the same policy the UIx
[`dashboard_uix`](../../uix/dashboard_uix/) sibling follows. The shared
stylesheet's WCAG palette-contrast + focus-ring contracts are already
enforced statically by `check-examples-assets` (`npm run test:script-policy`);
the items here are the live render + interaction that need an eye.

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
