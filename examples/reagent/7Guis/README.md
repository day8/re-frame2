# 7GUIs in re-frame2

[7GUIs](https://eugenkiss.github.io/7guis/) is a cross-framework UI benchmark — seven progressively complex tasks that exercise different facets of UI programming. Implementing them in re-frame2 demonstrates how the pattern handles the full range, from trivial state mutation to spreadsheet-grade formula evaluation.

| # | Task | Demonstrates | File |
|---|---|---|---|
| 1 | Counter | Smallest possible app: events, subs, view | [`../counter/core.cljs`](../counter/core.cljs) |
| 2 | Temperature Converter | Bidirectional derivations; one source of truth | [`temperature/temperature.cljs`](temperature/temperature.cljs) |
| 3 | Flight Booker | Form validation; layered subs deriving the Book button's enabled-state | [`flight_booker/flight_booker.cljs`](flight_booker/flight_booker.cljs) |
| 4 | Timer | `:dispatch-later` periodic tick; controlled slider; one source of truth for elapsed time | [`timer/timer.cljs`](timer/timer.cljs) |
| 5 | CRUD | List operations (add/update/delete); selection-as-state; derived filtered list | [`crud/crud.cljs`](crud/crud.cljs) |
| 6 | Circle Drawer | Undo/redo via an interceptor that snapshots `:circles`; modal dialog as state | [`circle_drawer/circle_drawer.cljs`](circle_drawer/circle_drawer.cljs) |
| 7 | Cells | Formula evaluation; subscription graph propagation; cycle detection; pure parser+evaluator | [`cells/cells.cljs`](cells/cells.cljs) |

Each example lives in its own self-contained sub-folder under `7Guis/<name>/` with its CLJS source and a thin HTML host page (e.g. `cells/cells.cljs` + `cells/cells.html`). Per the test-free examples policy there is **no per-example `.spec.cjs`** — real-regression coverage lives in the substrate contract tests (`npm run test:cljs`) and the framework gates (`test:xray-feature-gate` / `test:bundle-isolation` / `test:perf-bundle`), not under `examples/`. The shadow-cljs build targets in `implementation/shadow-cljs.edn` wire each task up to its own bundle; to view one in a browser, watch its build (`shadow-cljs watch examples/cells`) and serve the staged `index.html`.

CLJS namespace identifiers can't start with a digit, so the on-disk parent directory `7Guis` and the namespace tree diverge: each example is its own top-level namespace pair (`temperature.temperature`, `cells.cells`, etc.) under the dedicated `examples/reagent/7Guis` shadow-cljs source root. The directory name matches the original [7GUIs.com](https://eugenkiss.github.io/7guis/) capitalisation.

## How these compare to the original 7GUIs reference

The reference implementations on the [7GUIs site](https://eugenkiss.github.io/7guis/tasks) are typically tens of lines of imperative code per task. The re-frame2 versions are slightly longer because they:

- Carry `:doc` metadata on registrations.
- Attach Malli schemas where the data shape benefits.
- Use registered views (Var-reference style, the canonical form).
- Keep every artefact (event / sub / view) named and individually
  queryable rather than inlined into an imperative update loop.

The verbosity tax is real but small. The win is that every artefact is named, queryable, schema-able, and AI-amenable — at the same scale as the imperative reference.
