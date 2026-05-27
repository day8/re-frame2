# 7GUIs in re-frame2

[7GUIs](https://eugenkiss.github.io/7guis/) is a cross-framework UI benchmark — seven progressively complex tasks that exercise different facets of UI programming. Implementing them in re-frame2 demonstrates how the pattern handles the full range, from trivial state mutation to spreadsheet-grade formula evaluation.

| # | Task | Demonstrates | File |
|---|---|---|---|
| 1 | Counter | Smallest possible app: events, subs, view | [`../counter/core.cljs`](../counter/core.cljs) |
| 2 | Temperature Converter | Bidirectional derivations; one source of truth | [`temperature/core.cljs`](temperature/core.cljs) |
| 3 | Flight Booker | Form validation; layered subs deriving the Book button's enabled-state | [`flight_booker/core.cljs`](flight_booker/core.cljs) |
| 4 | Timer | `:dispatch-later` periodic tick; controlled slider; one source of truth for elapsed time | [`timer/core.cljs`](timer/core.cljs) |
| 5 | CRUD | List operations (add/update/delete); selection-as-state; derived filtered list | [`crud/core.cljs`](crud/core.cljs) |
| 6 | Circle Drawer | Undo/redo via an interceptor that snapshots `:circles`; modal dialog as state | [`circle_drawer/core.cljs`](circle_drawer/core.cljs) |
| 7 | Cells | Formula evaluation; subscription graph propagation; cycle detection; pure parser+evaluator | [`cells/core.cljs`](cells/core.cljs) |

Each example lives in its own self-contained sub-folder under `seven_guis/<name>/` with its CLJS source and a thin HTML host page (e.g. `cells/core.cljs` + `cells/index.html`). Per the test-free examples policy there is **no per-example `.spec.cjs`** — real-regression coverage lives in the substrate contract tests (`npm run test:cljs`) and the framework gates (`test:xray-feature-gate` / `test:bundle-isolation` / `test:perf-bundle`), not under `examples/`. The shadow-cljs build targets in `implementation/shadow-cljs.edn` wire each task up to its own bundle; to view one in a browser, watch its build (`shadow-cljs watch examples/cells`) and serve the staged `index.html`.

CLJS namespace identifiers can't start with a digit, so the on-disk directory is `seven_guis/` and the cluster namespace prefix is `seven-guis.*`. Each task follows the catalogue's `<name>.core` shape: `seven-guis.cells.core`, `seven-guis.flight-booker.core`, etc. — one consistent scope across every example in the cluster (rf2-hg45c).

## How these compare to the original 7GUIs reference

The reference implementations on the [7GUIs site](https://eugenkiss.github.io/7guis/tasks) are typically tens of lines of imperative code per task. The re-frame2 versions are slightly longer because they:

- Carry `:doc` metadata on registrations.
- Attach Malli schemas where the data shape benefits.
- Use registered views (Var-reference style, the canonical form).
- Keep every artefact (event / sub / view) named and individually
  queryable rather than inlined into an imperative update loop.

The verbosity tax is real but small. The win is that every artefact is named, queryable, schema-able, and AI-amenable — at the same scale as the imperative reference.
