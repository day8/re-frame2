# Xray Feature Matrix Gate

This directory owns the Xray browser feature/load gate scenarios for
[`tools/xray/spec/017-Test-Coverage-Matrix.md`](../../spec/017-Test-Coverage-Matrix.md).

The gate deliberately reuses the shared deterministic framework testbeds
under `testbeds/` (plus the Xray-owned routing/machine decks under
`tools/xray/testbeds/`) rather than duplicating app logic in Xray. The
live substrates are:

- `deliberate_throw` for handler/fx/machine exceptions (Epoch + Trace).
- `http_toggle` for managed HTTP success and failure categories.
- `multi_frame` for per-frame isolation (Trace + Epoch cascades).
- `deep_machine` and the `machine_epochs` deck for machine semantics
  (Machine Inspector chart render + per-track step matrix).
- the `routes_epochs` deck for routing (Routing panel: current route,
  navigation-this-epoch, nested route table, blocked nav).
- `large_dispatcher` and the SSR hydration-mismatch testbed for
  panel-specific payload shapes (large-value elision, Epoch under
  cascade scope).
- the `panel_gallery` deck for the theme-token CSS-variable resolution
  probe.

Run from `implementation/`:

```bash
npm run test:xray-feature-gate
```

The gate is occasional/pre-PR only. It is not part of default CI. Green runs
print a compact summary; failures flush browser console/page errors, Xray
state, the last trace rows, load stats when applicable, and a screenshot path.

## Current slice

After the 4-layer chrome refactor (`rf2-xy4yb`) the live L3/L4 tabs are
Epoch / App-DB / Views / Trace / Machines / Routing / Resources /
Derivation-Graph / Module-View. The dedicated Event-Detail, Time-Travel,
Schema, Flows, and Performance panels were retired, so this gate exercises
only the surviving tabs (chiefly Epoch / Trace / Routing / Machines) plus
the 20-event/load re-check:

- Open in Editor / Source Coordinates: trace source chips are clicked in
  the browser. Failures include the panel, source coordinate, expected
  editor URI, observed bridge traces, network outcome, and screenshot.
- Pop-out: the gate asserts that the inline shell leaves the host app
  (laid out to the left of Xray) clickable and that `popout!` renders a
  same-origin second-window Xray shell sharing the opener's runtime. (Per
  `rf2-sbfb7`, the dock / docked-overlay and `mount-inline-panel!` debug
  surfaces were removed; full-shell embedding lands under
  008-Embedding-Contract.)
- Deterministic exceptions: the deliberate-throw testbed surfaces thrown
  handlers inline in the Epoch panel's numbered cascade and via Trace
  source-coord chips. (Schema-failure recovery — rollback, skipped
  handlers/fx, the four `:where` surfaces — moved to CLJS unit at
  `tools/xray/test/.../panels/epoch/projection_cljs_test.cljc`.)
- Multi-frame fan-out: the multi-frame testbed uses a testbed-only bridge
  fx to dispatch into explicit frames. The gate asserts direct A/B
  isolation, fan-out into `:counter/b` and `:log`, and per-frame epoch
  history via the Trace and Epoch tabs (the per-frame cascade).
- Routing: the routes-epochs deck drives the real `reg-route` +
  `:rf.route/navigate` surface, asserting the Routing panel's current
  route, navigation-this-epoch outcome, nested route table, and blocked
  navigation.
- Machines: the deep-machine substrate asserts the Machine Inspector
  chart SVG renders; the machine-epochs deck steps a multi-machine,
  frame-isolated matrix and confirms the inspector mounts on a focused
  machine event.
- Large payload load: the large dispatcher scenario drives 20 meaningful
  host dispatches, asserting large-elision markers and app-db/trace panel
  stability under repeated size events.
- Trace row budget: the load gate saturates Xray's 1000-event trace ring,
  asserts the Trace panel keeps the DOM to the 200-row rendering budget
  with an overflow indicator, then drives 20 more host dispatches without
  growing the rendered row count.
