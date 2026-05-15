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

This first gate pass covers 19 matrix rows with deterministic browser
substrates and includes the 20-event/load re-check. It intentionally leaves
these rows or sub-rows for follow-up work:

- Open in Editor / Source Coordinates: unit coverage exists, but this browser
  gate does not yet click source chips across every panel.
- Pop-out, Docking, and Inline Embedding: not yet exercised by this browser
  gate.
- Schema recovery and multi-frame fan-out: the shared testbeds are staged, but
  the gate currently asserts stable substrate and panel handoff only where the
  browser build does not yet expose the full recovery/fan-out semantics.
