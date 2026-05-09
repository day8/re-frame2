# Worked examples

> **Type:** Reference
> Demonstrates the spec's primitives composed into real UI work. Read after the guide; refer to alongside the specification.

> **Status reminder.** These examples target the current `re-frame-2` API. Their maturity varies: some are aligned closely enough to run against the reference implementation, some are pedagogical sketches, and the RealWorld scaffold is now a broad worked sketch rather than a partially empty placeholder set. Treat the per-example README or docstring as the source of truth for how complete each one is.

## Maturity

Each example carries one of three maturity tags. New readers should pick examples by what they want to learn:

| Tag | What it means |
|---|---|
| **Pedagogical sketch** | Single-file, one-mechanism focused. Smallest possible demonstration. The first stop after reading the guide. |
| **Benchmark** | Exhaustive demonstration of a class of UI tasks. Useful for "what does the pattern look like across a wide surface?" |
| **Worked scaffold** | Substantial app. Shows how the primitives compose into a real-world codebase, even when some parts are still sketch-level rather than production-polished. The README inside each scaffold is the source of truth for what is demonstrated concretely. |

## Layout

```
examples/
  scripts/                              <-- orchestrator + Playwright helpers
    serve-and-run-examples-tests.cjs    <-- compiles, stages, serves, runs (entry point of `npm run test:examples`)
    run-examples-tests.cjs              <-- Playwright runner (walks the tree, picks up *.spec.cjs)
    spec-helpers.cjs                    <-- shared assertion helpers used by every spec
  counter/
    core.cljs
    counter.spec.cjs
    index.html
  todomvc/
    ...
  realworld/
    ...
  7Guis/                                <-- 7GUIs benchmark cluster (one sub-folder per task)
    cells/
      cells.cljs
      cells.html
      cells.spec.cjs
    circle_drawer/
    crud/
    flight_booker/
    temperature/
    timer/
  ...
```

The orchestrator and the runner consume `playwright` and `http-server` out of `implementation/node_modules/` — there is no separate `examples/package.json` by design; the implementation tree owns the npm dependency surface for the whole repo.

## The catalogue

Fifteen examples ship in this directory. Each is paired with a Playwright smoke spec (`<name>.spec.cjs`) and a shadow-cljs build id; the orchestrator at [`scripts/serve-and-run-examples-tests.cjs`](scripts/serve-and-run-examples-tests.cjs) compiles every build, stages the example's hand-written `index.html`, serves the lot, and runs the specs against a real Chromium. Run the full sweep from `implementation/`:

```bash
npm run test:examples
```

| # | Example | Maturity | Build id | Spec(s) it exercises | What it demonstrates |
|---|---|---|---|---|---|
| 1 | [`counter/`](counter/) | Pedagogical sketch | `examples/counter` | [002 Frames](../spec/002-Frames.md), [004 Views](../spec/004-Views.md) | The smallest possible app — one event, one sub, one view. The "hello world" of the pattern. |
| 2 | [`login/`](login/) | Pedagogical sketch | `examples/login` | [005 StateMachines](../spec/005-StateMachines.md), [014 HTTPRequests](../spec/014-HTTPRequests.md), [010 Schemas](../spec/010-Schemas.md), [008 Testing](../spec/008-Testing.md) | Single-feature scaffold — events + subs + views + machine + tests, all in one file, for a typical login flow. |
| 3 | [`todomvc/`](todomvc/README.md) | Benchmark | `examples/todomvc` | [002 Frames](../spec/002-Frames.md), [004 Views](../spec/004-Views.md), [012 Routing](../spec/012-Routing.md) | Canonical cross-framework todo app: persistence (localStorage), editing, bulk actions, remaining count, and hash-routing filters. |
| 4 | [`routing/`](routing/) | Pedagogical sketch | `examples/routing` | [012 Routing](../spec/012-Routing.md) | Three-page app demonstrating `reg-route`, `:rf.route/navigate`, anchor clicks via `:rf/url-requested`, and route-not-found handling. The CP-7 worked example. |
| 5 | [`ssr/`](ssr/) | Pedagogical sketch | `examples/ssr` | [011 SSR](../spec/011-SSR.md), [004 Views](../spec/004-Views.md) | Minimal SSR + hydration walkthrough. The CP-9 worked example. JVM-runnable; the browser side hydrates against a baked `<script id="__rf_payload">` block in the static `index.html` (standing in for a real Clojure server in front). |
| 6 | [`managed_http_counter/`](managed_http_counter/) | Pedagogical sketch | `examples/managed-http-counter` | [014 HTTPRequests](../spec/014-HTTPRequests.md), [Pattern-AsyncEffect](../spec/Pattern-AsyncEffect.md) | A counter where each button issues a `:rf.http/managed` request: success, 4xx failure, retry-recover (canned-stub), and abort. The compact, single-feature complement to RealWorld for Spec 014. |
| 7 | [`state_machine_walkthrough/`](state_machine_walkthrough/) | Pedagogical sketch | `examples/state-machine-walkthrough` | [005 StateMachines](../spec/005-StateMachines.md), [014 HTTPRequests](../spec/014-HTTPRequests.md), [008 Testing](../spec/008-Testing.md) | Runnable companion to [docs/guide/05-state-machines.md](../docs/guide/05-state-machines.md). The chapter's login flow as code; the browser layer drives the canonical lockout scenario (three failures → `:locked-out`). |
| 8 | [`nine_states/`](nine_states/README.md) | Benchmark | `examples/nine-states` | [Pattern-NineStates](../spec/Pattern-NineStates.md), [Pattern-RemoteData](../spec/Pattern-RemoteData.md), [Pattern-Forms](../spec/Pattern-Forms.md), [005 StateMachines](../spec/005-StateMachines.md) | The nine canonical UI states (nothing / loading / empty / one / some / too many / incorrect / correct / done) for a single domain. Pedagogically exhaustive. |
| 9 | [`7Guis/temperature/`](7Guis/temperature/temperature.cljs) | Benchmark | `examples/temperature` | [004 Views](../spec/004-Views.md), [006 ReactiveSubstrate](../spec/006-ReactiveSubstrate.md) | 7GUIs #2 — Temperature converter. Bidirectional derivations; one source of truth. |
| 10 | [`7Guis/flight_booker/`](7Guis/flight_booker/flight_booker.cljs) | Benchmark | `examples/flight-booker` | [004 Views](../spec/004-Views.md), [Pattern-Forms](../spec/Pattern-Forms.md) | 7GUIs #3 — Flight booker. Form validation; layered subs deriving the Book button's enabled state. |
| 11 | [`7Guis/timer/`](7Guis/timer/timer.cljs) | Benchmark | `examples/timer` | [002 Frames](../spec/002-Frames.md), [004 Views](../spec/004-Views.md) | 7GUIs #4 — Timer. `:dispatch-later` periodic tick; controlled slider; one source of truth for elapsed time. |
| 12 | [`7Guis/crud/`](7Guis/crud/crud.cljs) | Benchmark | `examples/crud` | [004 Views](../spec/004-Views.md) | 7GUIs #5 — CRUD. List operations (add / update / delete), selection-as-state, derived filtered list. |
| 13 | [`7Guis/circle_drawer/`](7Guis/circle_drawer/circle_drawer.cljs) | Benchmark | `examples/circle-drawer` | [004 Views](../spec/004-Views.md), [002 Frames](../spec/002-Frames.md) | 7GUIs #6 — Circle drawer. Undo/redo via an interceptor that snapshots `:circles`; modal dialog as state. |
| 14 | [`7Guis/cells/`](7Guis/cells/cells.cljs) | Benchmark | `examples/cells` | [006 ReactiveSubstrate](../spec/006-ReactiveSubstrate.md), [004 Views](../spec/004-Views.md) | 7GUIs #7 — Cells. Formula evaluation; subscription-graph propagation; cycle detection; pure parser+evaluator. |
| 15 | [`realworld/`](realworld/README.md) | Worked scaffold | `examples/realworld` | [014 HTTPRequests](../spec/014-HTTPRequests.md), [012 Routing](../spec/012-Routing.md), [005 StateMachines](../spec/005-StateMachines.md), [011 SSR](../spec/011-SSR.md), [Pattern-RemoteData](../spec/Pattern-RemoteData.md), [Pattern-Forms](../spec/Pattern-Forms.md) | [RealWorld (Conduit)](https://github.com/gothinkster/realworld) — the de-facto cross-framework benchmark. Auth, feeds, routing, comments, editor, profile, favorites, settings, and SSR-hydration glue are all sketched on the current API surface. |

For the 7GUIs cluster's own narrative (entries 9–14 above plus the counter from entry 1), see the cluster README at [`7Guis/README.md`](7Guis/README.md).

## Reading order

If you've finished the guide and want to see code:

1. **Start with [`counter/`](counter/)** — the smallest possible app. Establishes the basic shape.
2. **Then [`login/`](login/)** — adds a state machine, async effects, and form handling. Single-feature scope; full shape.
3. **Then [`todomvc/`](todomvc/README.md)** — classic benchmark shape: persistence, editing, filters, and browser routing pressure.
4. **Then [`routing/`](routing/)** or [`ssr/`](ssr/) — pick whichever is closer to your interest.
5. **Then [`managed_http_counter/`](managed_http_counter/)** — the smallest possible Spec 014 demo, before the broader RealWorld surface.
6. **Then [`state_machine_walkthrough/`](state_machine_walkthrough/)** — the ch.05 prose as runnable code, with smoke tests for every section.
7. **Then [`7Guis/`](7Guis/)** — survey of the pattern across many UI shapes.
8. **Then [`nine_states/`](nine_states/README.md)** — the page-level cardinality / lifecycle conventions wired together.
9. **Then [`realworld/`](realworld/)** — substantial-app shape across the widest surface in the repo.

## End-to-end verification

Every example listed above is verified end-to-end by a Playwright spec — each spec navigates a real browser to the example's URL, asserts the initial render, drives at least one interaction, and asserts the post-interaction user-visible state. The orchestrator at [`scripts/serve-and-run-examples-tests.cjs`](scripts/serve-and-run-examples-tests.cjs) compiles every example, stages its `index.html`, serves the output over HTTP on port 8030, and runs the spec runner at [`scripts/run-examples-tests.cjs`](scripts/run-examples-tests.cjs). Specs sit alongside each example as `<name>.spec.cjs`. Run the full sweep with `npm run test:examples` from `implementation/` (the orchestrator and runner share the `playwright` and `http-server` devDependencies declared in `implementation/package.json`).

### How the orchestrator wires an example up

The orchestrator file declares one entry per example and walks through three steps for the whole set:

1. **Compile.** A single `shadow-cljs compile <build-id> ...` invocation runs every build at once, sharing one JVM warmup. Each build's `:output-dir` is `out/examples/<name>/`.
2. **Stage.** Each example's hand-written `index.html` (and any extra static assets — TodoMVC's CSS, the managed-HTTP counter's `/api/inc.json`) is copied next to the compiled `main.js` under `out/examples/<name>/`.
3. **Serve and run.** `http-server` is launched against `out/examples` on port 8030, then `run-examples-tests.cjs` walks the tree, picks up every `*.spec.cjs`, and runs each spec against `http://127.0.0.1:8030/<mount>/`.

Each spec module exports `{name, url, run}`; `run` is an `async (page) => ...` that issues Playwright assertions. URL mounts match the orchestrator's `outDir` directory name (e.g. `/counter/`, `/managed-http-counter/`, `/state-machine-walkthrough/`).

### Adding a new example

1. Create `examples/<name>/` with the source, a hand-written `index.html`, and a Playwright spec `<name>.spec.cjs`.
2. Add a shadow-cljs build target to `implementation/shadow-cljs.edn` under the existing `:examples/...` block.
3. Append an entry to the `EXAMPLES` array in `examples/scripts/serve-and-run-examples-tests.cjs` declaring `{build, htmlSrc, outDir, extraFiles?}`.
4. Update this catalogue and any per-Spec cross-references that the new example exercises.

The spec runner discovers specs by filesystem walk under `examples/`, so no additional registration is needed beyond the orchestrator entry.

### Running a single example interactively

If you want to iterate on one example without re-running the whole sweep:

```bash
# From implementation/ — pick the build id from the catalogue above.
shadow-cljs watch examples/counter
```

Then serve the same `out/examples/counter/` directory (after staging its `index.html` once by hand, or by running `npm run test:examples` once first to populate the layout). The orchestrator is the canonical end-to-end runner; this loop is purely for source-code iteration.

## What examples are *not*

- **Not a substitute for the [specification](../spec/).** Examples illustrate; the specification defines.
- **Not all uniformly polished.** The Pedagogical-sketch examples are deliberately small. The Worked-scaffold (RealWorld) prioritises breadth of API coverage over production polish.
