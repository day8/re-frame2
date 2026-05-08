# 7GUIs in re-frame2

[7GUIs](https://eugenkiss.github.io/7guis/) is a cross-framework UI benchmark — seven progressively complex tasks that exercise different facets of UI programming. Implementing them in re-frame2 demonstrates how the pattern handles the full range, from trivial state mutation to spreadsheet-grade formula evaluation.

| # | Task | Demonstrates | File |
|---|---|---|---|
| 1 | Counter | Smallest possible app: events, subs, view | [`../counter/core.cljs`](../counter/core.cljs) |
| 2 | Temperature Converter | Bidirectional derivations; one source of truth | [`temperature.cljs`](temperature.cljs) |
| 3 | Flight Booker | Form validation; layered subs deriving the Book button's enabled-state | [`flight_booker.cljs`](flight_booker.cljs) |
| 4 | Timer | `:dispatch-later` periodic tick; controlled slider; one source of truth for elapsed time | [`timer.cljs`](timer.cljs) |
| 5 | CRUD | List operations (add/update/delete); selection-as-state; derived filtered list | [`crud.cljs`](crud.cljs) |
| 6 | Circle Drawer | Undo/redo via an interceptor that snapshots `:circles`; modal dialog as state | [`circle_drawer.cljs`](circle_drawer.cljs) |
| 7 | Cells | Formula evaluation; subscription graph propagation; cycle detection; pure parser+evaluator | [`cells.cljs`](cells.cljs) |

Each example is a single CLJS source file plus a thin HTML host page and a Playwright smoke spec (e.g. `cells.cljs` + `cells.html` + `cells.spec.cjs`). The shadow-cljs build targets in `implementation/shadow-cljs.edn` and the orchestrator under `implementation/scripts/` wire them up so they run in a real browser; locally invoke `npm run test:examples` from `implementation/`.

## How these compare to the original 7GUIs reference

The reference implementations on the [7GUIs site](https://eugenkiss.github.io/7guis/tasks) are typically tens of lines of imperative code per task. The re-frame2 versions are slightly longer because they:

- Carry `:doc` metadata on registrations.
- Attach Malli schemas where the data shape benefits.
- Use registered views (Var-reference style, the canonical form).
- Demonstrate headless tests where the task's logic is non-trivial.

The verbosity tax is real but small. The win is that every artefact is named, queryable, schema-able, and AI-amenable — at the same scale as the imperative reference.
