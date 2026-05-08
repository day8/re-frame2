# Nine States of UI — worked example

A re-frame2 worked example demonstrating all **nine canonical UI
states** that production applications typically need to handle, for a
single small domain: a **todos list**.

## The nine states

| # | Name | What it shows | Trigger |
|---|---|---|---|
| 1 | **Nothing**   | Blank initial slate; never fetched. "Get started" CTA. | `[:app/initialise]` |
| 2 | **Loading**   | First fetch in flight; no `:data` yet. Spinner / skeleton. | `[:todos/load {:n N}]` (transient) |
| 3 | **Empty**     | Fetched, but the result is the empty list. "No results" CTA. | `[:todos/load {:n 0}]` |
| 4 | **One**       | Exactly one item; focused single-item layout. | `[:todos/load {:n 1}]` |
| 5 | **Some**      | A small, manageable list; standard list rendering. | `[:todos/load {:n 4}]` |
| 6 | **Too Many**  | Overwhelming amount; needs search / pagination / virtualisation. | `[:todos/load {:n 25}]` |
| 7 | **Incorrect** | Form submission failed validation. Per-field errors visible. | type a 1-char title, submit |
| 8 | **Correct**   | Form submission succeeded; "Todo added." confirmation. | type a 3+ char title, submit |
| 9 | **Done**      | Editor reached `:archived`; terminal, read-only. | `[:todos/editor [:todos.editor/archive {}]]` |

A small control panel in the top of the demo lets you trigger each
transition.

## What this example demonstrates

- **Pattern-RemoteData** — the 5-key slice
  `{:status :data :error :loaded-at :attempt}` carries the lifecycle
  for states 1-6. See [`spec/Pattern-RemoteData.md`](../../spec/Pattern-RemoteData.md).
- **Pattern-Forms** — the
  `{:draft :submitted :status :errors :touched}` slice carries the
  "new todo" input, surfacing the **Incorrect** (state 7) and
  **Correct** (state 8) variants. See
  [`spec/Pattern-Forms.md`](../../spec/Pattern-Forms.md).
- **State machines** — a `:todos/editor` machine with two states
  (`:editing` -> `:archived`); the `:archived` state is terminal,
  modelling the **Done** state (9). See
  [`spec/005-StateMachines.md`](../../spec/005-StateMachines.md).
- **Inspectability bias** — non-trivial guards / actions are named
  entries in the machine's machine-scoped `:guards` / `:actions`
  maps; only trivial transitions use inline fns.
- **Headless tests** — every state has a fixture at the bottom of
  `core.cljs` that drives `app-db` into that state and asserts the
  matching state-discriminator sub fires. Tests run JVM-side via
  `compute-sub` / `dispatch-sync` / `with-frame`.

## File layout

```
examples/nine_states/
  core.cljs   single-file example: schemas, events, subs, views,
              machine, control panel, headless tests, mount.
  README.md   this file.
```

For brevity the example is one file; in a real codebase it would
split per CP-6 conventions (`schema.cljc / events.cljs / subs.cljs /
views.cljs / machines.cljs / events_test.cljs`).

## How to run

```bash
# From the project root
shadow-cljs watch nine-states
# then open the served page (per the example harness setup)
```

The headless test suite runs JVM-side:

```clojure
(nine-states.core/run-all-tests)
;; => :ok
```

## Cross-references

- [`spec/Pattern-RemoteData.md`](../../spec/Pattern-RemoteData.md) — the 5-state lifecycle this example exercises end-to-end.
- [`spec/Pattern-Forms.md`](../../spec/Pattern-Forms.md) — the form lifecycle that drives the Incorrect / Correct states.
- [`spec/Pattern-NineStates.md`](../../spec/Pattern-NineStates.md) — the page-level convention this example instantiates directly.
- [`spec/005-StateMachines.md`](../../spec/005-StateMachines.md) — the machine grammar and `[:rf/machines <id>]` snapshot location used for the Done state.
- [`examples/login/core.cljs`](../login/core.cljs) and [`examples/seven_guis/circle_drawer.cljs`](../seven_guis/circle_drawer.cljs) — single-file style this example follows.
