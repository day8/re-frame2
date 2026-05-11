# Nine States of UI — worked example

A re-frame2 worked example demonstrating all **nine canonical UI
states** that production applications typically need to handle, for a
single small domain: a **todos list**.

The example is built around a single `:type :parallel` state machine
with three regions (`:data` / `:form` / `:mode`) and `:fsm/tags` for
per-axis query intent. The root view's render decision collapses to
one `case` over a render-priority table.

## The nine states

| # | Name | What it shows | Trigger |
|---|---|---|---|
| 1 | **Nothing**   | Blank initial slate; never fetched. "Get started" CTA. | `[:nine-states.app/initialise]` |
| 2 | **Loading**   | First fetch in flight; no data yet. Spinner / skeleton. | `[:nine-states.demo/load {:n N}]` (transient) |
| 3 | **Empty**     | Fetched, but the result is the empty list. "No results" CTA. | `[:nine-states.demo/load {:n 0}]` |
| 4 | **One**       | Exactly one item; focused single-item layout. | `[:nine-states.demo/load {:n 1}]` |
| 5 | **Some**      | A small, manageable list; standard list rendering. | `[:nine-states.demo/load {:n 4}]` |
| 6 | **Too Many**  | Overwhelming amount; needs search / pagination / virtualisation. | `[:nine-states.demo/load {:n 25}]` |
| 7 | **Incorrect** | Form submission failed validation. Per-field errors visible. | type a 1-char title, submit |
| 8 | **Correct**   | Form submission succeeded; "Todo added." confirmation. | type a 3+ char title, submit |
| 9 | **Done**      | Mode region reached `:done`; terminal, read-only. | `[:ui/nine-states [:archive {}]]` |

A small control panel at the top of the demo lets you trigger each
transition.

## How the model is structured

One machine, three regions:

- **`:data` region** carries the request lifecycle plus the
  cardinality axis: `:nothing → :loading → :resolving → {:empty | :one
  | :some | :too-many} | :error`. The `:resolving` state is a transient
  bucket: an `:always`-cascade reads the item count from the machine's
  shared `:data` and picks the cardinality state on a single
  microstep.
- **`:form` region** carries Pattern-Forms' lifecycle: `:neutral →
  {:correct | :incorrect}`. The `:correct` state is transient — the
  next `:edit` returns the region to `:neutral`.
- **`:mode` region** carries the Active / Done axis: `:active →
  :done`. `:done` is terminal and tagged `:mode/read-only`; the view
  inspects that tag to disable the form and the control buttons.

Every state declares `:tags` describing its per-axis intent
(`:data/loading`, `:form/invalid`, `:mode/done`, ...). A
`render-priority` table over those tags drives a single `:ui/render`
selector sub that returns one keyword; the root view's `case` over
that keyword is the only branch site.

## What this example demonstrates

- **Parallel regions + `:fsm/tags`** — three orthogonal axes in one
  machine declaration; tags compose across regions; one selector sub
  collapses the tag union into a render keyword. See
  [`spec/Pattern-NineStates.md`](../../../spec/Pattern-NineStates.md)
  and [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md)
  §Parallel regions / §State tags.
- **Pattern-RemoteData** — the request lifecycle is folded into the
  `:data` region (the region's state-keyword IS the status). See
  [`spec/Pattern-RemoteData.md`](../../../spec/Pattern-RemoteData.md).
- **Pattern-Forms** — the `{:draft :errors :touched}` slice carries the
  form runtime; the form region's state carries the lifecycle. See
  [`spec/Pattern-Forms.md`](../../../spec/Pattern-Forms.md).
- **Inspectability bias** — non-trivial guards / actions are named
  entries in the machine's machine-scoped `:guards` / `:actions`
  maps; only trivial transitions use inline fns.
- **Headless tests** — every state has a fixture at the bottom of
  `core.cljs` that drives `app-db` into that state and asserts against
  the machine's tag union and the resolved `:ui/render` keyword. Tests
  run via `compute-sub` / `dispatch-sync` / `with-frame`.

## Legacy variant

This example is the canonical implementation of Pattern-NineStates.
For the pre-parallel-regions variant (nine boolean discriminator subs
+ priority `cond`) — supported but not recommended for new code — see
[`spec/Pattern-NineStates.md` §Appendix — Pre-parallel-regions variant](../../../spec/Pattern-NineStates.md#appendix--pre-parallel-regions-variant-deprecated-but-supported).

## File layout

```
examples/reagent/nine_states/
  core.cljs   single-file example: schemas, the :ui/nine-states
              parallel machine, demo events, the render-priority
              table + :ui/render sub, per-state views, headless tests,
              mount.
  README.md   this file.
```

For brevity the example is one file; in a real codebase it would
split per CP-6 conventions (`schema.cljc / machine.cljc / events.cljs
/ subs.cljs / views.cljs / events_test.cljs`).

## How to run

The example is wired into the canonical examples harness. From `implementation/`:

```bash
npm run test:examples
```

That compiles every example (this one builds under shadow-cljs id `examples/nine-states`), stages its `index.html` into `out/examples/nine-states/`, serves the lot, and runs the Playwright smoke spec at [`nine_states.spec.cjs`](nine_states.spec.cjs).

To iterate on the source alone, watch the build directly from `implementation/`:

```bash
shadow-cljs watch examples/nine-states
```

(Run `npm run test:examples` at least once first so `out/examples/nine-states/index.html` is staged; subsequent watch builds reuse it.)

The headless tests live in [`test/nine_states/core_test.cljs`](test/nine_states/core_test.cljs) and run as part of the framework's CLJS test suite. To run them ad-hoc from a CLJS REPL:

```clojure
(require '[nine-states.core])          ;; loads the example registrations
(require '[nine-states.core-test :as t])
(t/test-state-1-nothing)
(t/test-state-2-loading)
;; ...one per state.
```

## Cross-references

- [`spec/Pattern-NineStates.md`](../../../spec/Pattern-NineStates.md) — the page-level convention this example instantiates.
- [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md) §Parallel regions / §State tags — the substrate this example uses.
- [`spec/Pattern-RemoteData.md`](../../../spec/Pattern-RemoteData.md) — the lifecycle folded into the `:data` region.
- [`spec/Pattern-Forms.md`](../../../spec/Pattern-Forms.md) — the form lifecycle in the `:form` region.
- [`examples/reagent/login/core.cljs`](../login/core.cljs) and [`examples/reagent/7Guis/circle_drawer/circle_drawer.cljs`](../7Guis/circle_drawer/circle_drawer.cljs) — single-file style this example follows.
