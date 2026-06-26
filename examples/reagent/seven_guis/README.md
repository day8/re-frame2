# 7GUIs in re-frame2

[7GUIs](https://eugenkiss.github.io/7guis/) is a cross-framework benchmark of seven small UI tasks. This folder builds all seven in re-frame2.

The tasks are ordered so each one adds a problem the last didn't have. A counter. Then two fields that must stay in sync. Then a form with a validation rule. Then a timer. Then a list you can edit. Then undo/redo. Then a working spreadsheet. The point isn't the widgets. Each task hides a *famous trap*, and a framework either has an honest answer for it or papers over the crack.

What's worth watching is how little the answer changes as the tasks get harder. All seven share one shape: state lives in one place ([app-db](../../../docs/guide/glossary.md#app-db)), [events](../../../docs/guide/glossary.md#event) are the only way to change it, [subscriptions](../../../docs/guide/glossary.md#subscription) derive everything the [view](../../../docs/guide/glossary.md#view) needs, and the view just reads those derivations and dispatches events.

The traps that sink the imperative versions — the update loop between two text fields, the validation path you forgot, the undo stack that drifts out of sync — mostly stop being *bugs you can write*. They become *shapes the architecture doesn't have*. That is the whole pitch of re-frame2, and the suite shows it paying off seven times over.

| # | Task | The trap, and re-frame2's answer | File |
|---|---|---|---|
| 1 | Counter | The smallest honest app: one [event handler](../../../docs/guide/glossary.md#event-handler), one subscription, one view. The baseline everything else builds on. | [`../counter/core.cljs`](../counter/core.cljs) |
| 2 | Temperature Converter | Two fields, each editing the other. Wire them together and you get an update loop or a stale value. Answer: one canonical value in app-db, one event per field; both fields read it through subscriptions. There is no two-way wiring to loop. | [`temperature/core.cljs`](temperature/core.cljs) |
| 3 | Flight Booker | The Book button enables only when three fields agree (dates parse, return ≥ start). Recompute that by hand on every keystroke and you'll miss a case. Answer: the enabled flag is a [subscription](../../../docs/guide/glossary.md#subscription) over the three field subs. Nothing re-derives it but app-db changing, so it can't go stale. | [`flight_booker/core.cljs`](flight_booker/core.cljs) |
| 4 | Timer | A clock ticks while you drag a slider that changes the duration mid-run — a classic place for a race. Answer: each tick is a `:dispatch-later` event through the normal pipeline, elapsed time is the one source of truth, and a generation token drops stale ticks so Reset reads `0.0` whatever the timing. | [`timer/core.cljs`](timer/core.cljs) |
| 5 | CRUD | A master/detail list. Keep the edit inputs in their own component state and they fall out of sync the moment selection changes. Answer: the inputs *are* a subscription over a draft slice; the list shows committed values; selection is state, not React identity. | [`crud/core.cljs`](crud/core.cljs) |
| 6 | Circle Drawer | Undo/redo — the usual excuse to hand-roll a fragile undo stack inside a component. Answer: an [interceptor](../../../docs/guide/glossary.md#interceptor) snapshots the circles before each undoable event; Undo and Redo are ordinary events that pop and push those snapshots. The dialog is just a key in app-db. | [`circle_drawer/core.cljs`](circle_drawer/core.cljs) |
| 7 | Cells | A spreadsheet: formulas reference cells, changes propagate, cycles must be caught. The trap is hand-maintained per-cell observers that go stale. Answer: each cell's value is a subscription over the whole cell map, the [derivation graph](../../../docs/guide/glossary.md#the-derivation-graph) `=`-dedups so only changed cells re-render, and a pure parser turns bad input into typed error markers instead of throwing. | [`cells/core.cljs`](cells/core.cljs) |

## What this demonstrates

Read the seven in order and you can watch the same model grow one idea at a time:

- **Counter** is the bare loop: event handler, subscription, view. Nothing else.
- **Temperature** adds the idea the whole suite leans on: *one source of truth, derived two ways*. The canonical value is Celsius. Both fields are subscriptions off it — one shows the raw text you're typing, the other shows the conversion. The trap is structurally absent.
- **Flight Booker** layers subscriptions: `start-valid?`, `return-valid?`, and `dates-coherent?` feed a `book-enabled?` sub that the button reads. "Did I cover every path?" becomes "is it in the derivation graph?", which is much harder to get wrong.
- **Timer** shows an event handler scheduling its own follow-up, via the `:fx [[:dispatch-later …]]` effect. The periodic tick rides the same [cascade](../../../docs/guide/glossary.md#event-cascade) as a button click. A generation token makes Reset observably correct without reaching outside the loop.
- **CRUD** is selection-as-state. The detail inputs read a `:draft` slice through subscriptions; Create, Update, and Delete are list operations on a vector. A `can-update?` sub even disables editing when the selected row is hidden by the filter — the master/detail edge the task exists to test, answered as derived state.
- **Circle Drawer** is the first task to reach past the core four registrations for an interceptor. A named `:undoable` interceptor (each undoable event opts in by id from its `:interceptors`) captures the prior `:circles` in its `:before` and pushes it onto an undo stack in its `:after`. The slider drag deliberately *omits* the interceptor, so a whole drag collapses into one undo step — a chore with an imperative stack, nearly free here.
- **Cells** is the payoff. It's a small interpreter — tokeniser, parser, evaluator — written as pure functions that run on the JVM. It detects cycles with a visited-set walk and emits error markers (`#PARSE`, `#CYCLE`, `#DIV/0`, `#TYPE`) that flow through arithmetic without ever throwing. Propagation leans on the [derivation graph](../../../docs/guide/glossary.md#the-derivation-graph) instead of fighting it: every cell's value sub takes the *whole* cell map as input, so any edit recomputes every mounted cell. That's fine — the evaluator is pure, the grid is small, and `=`-dedup means an unchanged result never re-renders. Correctness for free, with no per-cell dependency edges to keep in sync.

A few cross-cutting habits show up too. Three of the tasks (Flight Booker, CRUD, Circle Drawer) disable buttons by reading a subscription rather than poking `.disabled` — *ask, don't tell*. Most tasks attach a Malli [schema](../../../docs/guide/glossary.md#schema) to their app-db slice, so a malformed write [fails loud](../../../docs/guide/glossary.md#fail-loud-not-silent) in dev and is [elided](../../../docs/guide/glossary.md#elide) in production.

The two tasks that mint new ids into durable state (CRUD's people, Circle Drawer's circles) allocate them from a counter in app-db, not from `random-uuid` at the write site. So replaying the event stream reproduces exactly the same data — the property that makes time-travel and SSR hydration honest.

## Why this shape

A common worry is that "everything is an event and a subscription" gets heavy as apps grow. The suite is a good argument against it. From the counter to the spreadsheet, no task needs a second state-management idea. Two-way binding never appears. There's no observer wiring, no manual dependency tracking, and no imperative "recompute the derived bits after this change" step — the [cascade](../../../docs/guide/glossary.md#event-cascade) does that once, at the end, from settled state.

The parts that stay hard — parsing a formula, detecting a cycle, getting the timer's generation logic right — are *real* domain problems, not framework friction. They live in plain pure functions you can unit-test on the JVM, no browser needed.

Each task lives in its own sub-folder under `seven_guis/<name>/`, with its CLJS source and a thin HTML host page (e.g. `cells/core.cljs` + `cells/index.html`). Watch a task's build to view it in a browser:

```bash
shadow-cljs watch examples/cells
```

## How these compare to the original 7GUIs reference

The reference implementations on the [7GUIs site](https://eugenkiss.github.io/7guis/tasks) are typically tens of lines of imperative code per task. The re-frame2 versions run a little longer, and the extra lines buy something:

- Every registration carries `:doc` metadata, so it can describe itself to a tool or a person reading it cold.
- Malli schemas guard the app-db slices worth pinning down, turning a class of bad writes into a loud dev-time error.
- Views are registered by Var reference — the canonical form, the one tooling can find by name.
- Every artefact — each event, subscription, and view — stays named and individually queryable, rather than dissolving into one imperative update loop where nothing has a handle.

The verbosity tax is real, and small. In return, every artefact is named, queryable, schema-able, and AI-amenable — at about the scale of the imperative reference, with the famous traps engineered out rather than carefully avoided.
