# Causa Feature Matrix Gate

This directory owns the Causa browser feature/load gate scenarios for
[`tools/causa/spec/017-Test-Coverage-Matrix.md`](../../spec/017-Test-Coverage-Matrix.md).

The gate deliberately reuses the shared deterministic framework testbeds
under `testbeds/` rather than duplicating app logic in Causa:

- `deliberate_throw` for handler/fx/flow/machine exceptions.
- `schema_violation` for event, cofx, app-db, and fx-args schema failures.
- `http_toggle` for managed HTTP success and failure categories.
- `multi_frame`, `deep_machine`, `long_flow_w_failure`, and
  `drain_depth_trigger` for frame, machine, flow, and load semantics.
- `non_trivial_app_db`, `sensitive_dispatcher`, `large_dispatcher`, and SSR
  hydration testbeds for panel-specific payload shapes.

Run from `implementation/`:

```bash
npm run test:causa-feature-gate
```

The gate is occasional/pre-PR only. It is not part of default CI. Green runs
print a compact summary; failures flush browser console/page errors, Causa
state, the last trace rows, load stats when applicable, and a screenshot path.

## Current slice

This gate covers the deterministic browser substrates plus the 20-event/load
re-check. It now exercises the follow-up surfaces directly:

- Open in Editor / Source Coordinates: trace and issues source chips are
  clicked in the browser. Failures include the panel, source coordinate,
  expected editor URI, observed bridge traces, network outcome, and screenshot.
- Pop-out, Docking, and Inline Embedding: the current shell exposes the overlay
  surface only. The gate asserts that the right-side overlay leaves the left
  host app clickable, and records explicit current-build observations for
  pop-out, docking, and inline public-mount availability.
- Schema recovery: the schema testbed loads the Malli adapter so browser runs
  assert rollback, skipped handlers, skipped fx, and the Causa schema panel's
  current row/empty-state projection.
- Multi-frame fan-out: the multi-frame testbed uses a testbed-only bridge fx to
  dispatch into explicit frames. The gate asserts direct A/B isolation, fan-out
  into `:counter/b` and `:log`, per-frame epoch history, trace selection,
  event-detail projection/orphan-state behavior, causality graph visibility,
  and the time-travel panel's current `:counter/b` target-frame projection.
- Sensitive and large payload load: the sensitive and large dispatcher
  scenarios now drive 20 meaningful host dispatches each, asserting redaction
  indicators, absence of raw secret payloads, large-elision markers, and
  app-db/trace panel stability under repeated privacy/size events.
- Trace row budget: the load gate saturates Causa's 1000-event trace ring,
  asserts the Trace panel keeps the DOM to the 200-row rendering budget with an
  overflow indicator, then drives 20 more host dispatches without growing the
  rendered row count.
