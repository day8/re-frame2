# Worked examples

> **Type:** Reference
> Demonstrates the spec's primitives composed into real UI work. Read after the guide; refer to alongside the specification.

> **Status reminder.** These examples target the current `re-frame2` API. Their maturity varies: some are aligned closely enough to run against the reference implementation, some are pedagogical sketches, and the RealWorld scaffold is now a broad worked sketch rather than a partially empty placeholder set. Treat the per-example README or docstring as the source of truth for how complete each one is.

## Layout — grouped by substrate

Examples are organised under per-substrate top-level directories. Reagent is the canonical substrate; UIx and Helix each ship a curated smoke-test subset (counter + login) rather than a 1:1 mirror.

```
examples/
  scripts/                              <-- orchestrator + Playwright helpers
    serve-and-run-examples-tests.cjs    <-- compiles, stages, serves, runs (entry point of `npm run test:examples`)
    run-examples-tests.cjs              <-- Playwright runner (walks the tree, picks up *.spec.cjs)
    spec-helpers.cjs                    <-- shared assertion helpers used by every spec
  reagent/                              <-- canonical substrate (full set)
    counter/
      core.cljs
      counter.spec.cjs
      index.html
    counter_slim_and_fast/              <-- same dataflow, mounted on day8/reagent-slim
    todomvc/
    realworld/
    7Guis/                              <-- 7GUIs benchmark cluster (one sub-folder per task)
      cells/
      circle_drawer/
      crud/
      flight_booker/
      temperature/
      timer/
    boot/                               <-- Pattern-Boot worked example
    state_machine_walkthrough/
    nine_states/
    routing/
    ssr/
    managed_http_counter/
    long_running_work/                  <-- Pattern-LongRunningWork worked example
    websocket/                          <-- Pattern-WebSocket worked example
    login/
  uix/                                  <-- UIx adapter smoke-test set (counter + login)
    counter_uix/                        <-- folder name carries the namespace suffix so it
    login_uix/                              doesn't collide with reagent/{counter,login}/ on the classpath
  helix/                                <-- Helix adapter smoke-test set (counter + login)
    counter_helix/                      <-- folder name carries the namespace suffix so it
    login_helix/                            doesn't collide with reagent/ or uix/ siblings on the classpath
```

The orchestrator and the runner consume `playwright` and `http-server` out of `implementation/node_modules/` — there is no separate `examples/package.json` by design; the implementation tree owns the npm dependency surface for the whole repo.

## Reagent

The full set of worked examples — nineteen in total (counting each 7GUIs task individually), each paired with a Playwright smoke spec (`<name>.spec.cjs`) and a shadow-cljs build id. The orchestrator at [`scripts/serve-and-run-examples-tests.cjs`](scripts/serve-and-run-examples-tests.cjs) compiles every build, stages the example's hand-written `index.html`, serves the lot, and runs the specs against a real Chromium. Run the full sweep from `implementation/`:

```bash
npm run test:examples
```

| # | Example | Maturity | Build id | Spec(s) it exercises | What it demonstrates |
|---|---|---|---|---|---|
| 1 | [`reagent/counter/`](reagent/counter/) | Pedagogical sketch | `examples/counter` | [002 Frames](../spec/002-Frames.md), [004 Views](../spec/004-Views.md) | The smallest possible app — one event, one sub, one view. The "hello world" of the pattern. |
| 2 | [`reagent/counter_slim_and_fast/`](reagent/counter_slim_and_fast/) | Adapter fixture | `examples/counter-slim-and-fast` | [006 ReactiveSubstrate](../spec/006-ReactiveSubstrate.md), [Conventions §Adapter test matrix](../spec/Conventions.md#adapter-test-matrix-policy) | The same counter dataflow as entry 1, but mounted on the `day8/reagent-slim` rewrite (every user-facing `reagent.*` import → `reagent2.*`; `rf/init!` takes the slim adapter Var). The paired `scripts/check-reagent-slim-bundle-isolation.cjs` asserts the advanced bundle contains no `reagent.impl.*` and no `react-dom/server` symbols — the slim adapter's bundle-isolation contract. |
| 3 | [`reagent/login/`](reagent/login/) | Pedagogical sketch | `examples/login` | [005 StateMachines](../spec/005-StateMachines.md), [014 HTTPRequests](../spec/014-HTTPRequests.md), [010 Schemas](../spec/010-Schemas.md), [008 Testing](../spec/008-Testing.md) | Single-feature scaffold — events + subs + views + machine + tests, all in one file, for a typical login flow. |
| 4 | [`reagent/todomvc/`](reagent/todomvc/README.md) | Benchmark | `examples/todomvc` | [002 Frames](../spec/002-Frames.md), [004 Views](../spec/004-Views.md), [012 Routing](../spec/012-Routing.md) | Canonical cross-framework todo app: persistence (localStorage), editing, bulk actions, remaining count, and hash-routing filters. |
| 5 | [`reagent/routing/`](reagent/routing/) | Pedagogical sketch | `examples/routing` | [012 Routing](../spec/012-Routing.md) | Three-page app demonstrating `reg-route`, `:rf.route/navigate`, anchor clicks via `:rf/url-requested`, and route-not-found handling. The CP-7 worked example. |
| 6 | [`reagent/ssr/`](reagent/ssr/) | Pedagogical sketch | `examples/ssr` | [011 SSR](../spec/011-SSR.md), [004 Views](../spec/004-Views.md) | Minimal SSR + hydration walkthrough. The CP-9 worked example. JVM-runnable; the browser side hydrates against a baked `<script id="__rf_payload">` block in the static `index.html` (standing in for a real Clojure server in front). |
| 7 | [`reagent/managed_http_counter/`](reagent/managed_http_counter/) | Pedagogical sketch | `examples/managed-http-counter` | [014 HTTPRequests](../spec/014-HTTPRequests.md), [Pattern-AsyncEffect](../spec/Pattern-AsyncEffect.md) | A counter where each button issues a `:rf.http/managed` request: success, 4xx failure, retry-recover (canned-stub), and abort. The compact, single-feature complement to RealWorld for Spec 014. |
| 8 | [`reagent/state_machine_walkthrough/`](reagent/state_machine_walkthrough/) | Pedagogical sketch | `examples/state-machine-walkthrough` | [005 StateMachines](../spec/005-StateMachines.md), [014 HTTPRequests](../spec/014-HTTPRequests.md), [008 Testing](../spec/008-Testing.md) | Runnable companion to [docs/guide/09-state-machines.md](../docs/guide/09-state-machines.md). The chapter's login flow as code; the browser layer drives the canonical lockout scenario (three failures → `:locked-out`). |
| 9 | [`reagent/nine_states/`](reagent/nine_states/README.md) | Benchmark | `examples/nine-states` | [Pattern-NineStates](../spec/Pattern-NineStates.md), [Pattern-RemoteData](../spec/Pattern-RemoteData.md), [Pattern-Forms](../spec/Pattern-Forms.md), [005 StateMachines](../spec/005-StateMachines.md) | The nine canonical UI states (nothing / loading / empty / one / some / too many / incorrect / correct / done) for a single domain. Pedagogically exhaustive. |
| 10 | [`reagent/boot/`](reagent/boot/README.md) | Pedagogical sketch | `examples/boot` | [Pattern-Boot](../spec/Pattern-Boot.md), [005 StateMachines](../spec/005-StateMachines.md), [014 HTTPRequests](../spec/014-HTTPRequests.md) | A single `:app/boot` machine owns the application's initialisation graph (`:configuring` → `:loading-deps` → `:hydrating` → `:ready`) with `:invoke-all` fan-out for parallel deps and a `:failed` retry path. The canonical Pattern-Boot worked example; demo HTTP stubs via `:rf.http/managed-canned-success`. |
| 11 | [`reagent/long_running_work/`](reagent/long_running_work/README.md) | Pedagogical sketch | `examples/long-running-work` | [Pattern-LongRunningWork](../spec/Pattern-LongRunningWork.md), [005 StateMachines](../spec/005-StateMachines.md) | Declarative spawn-and-join via `:invoke-all` (parent coordinator + N worker children); cooperative cancellation cascade on every exit path (user cancel, parent-unmount, completion); per-step progress reporting as internal self-transitions; browser-tick yielding via `:after`. |
| 12 | [`reagent/websocket/`](reagent/websocket/README.md) | Pedagogical sketch | `examples/websocket` | [Pattern-WebSocket](../spec/Pattern-WebSocket.md), [Pattern-StaleDetection](../spec/Pattern-StaleDetection.md), [005 StateMachines](../spec/005-StateMachines.md) | A connection lifecycle machine — hierarchical compound `:active` parenting `:connecting` / `:authenticating` / `:connected`; an `:invoke`d socket actor; `:after` exponential backoff; `:always` offline-queue flush; `:fsm/tags` for queryable state; connection-epoch staleness; request/reply correlation. In-process mock WebSocket — no network needed. |
| 13 | [`reagent/7Guis/temperature/`](reagent/7Guis/temperature/temperature.cljs) | Benchmark | `examples/temperature` | [004 Views](../spec/004-Views.md), [006 ReactiveSubstrate](../spec/006-ReactiveSubstrate.md) | 7GUIs #2 — Temperature converter. Bidirectional derivations; one source of truth. |
| 14 | [`reagent/7Guis/flight_booker/`](reagent/7Guis/flight_booker/flight_booker.cljs) | Benchmark | `examples/flight-booker` | [004 Views](../spec/004-Views.md), [Pattern-Forms](../spec/Pattern-Forms.md) | 7GUIs #3 — Flight booker. Form validation; layered subs deriving the Book button's enabled state. |
| 15 | [`reagent/7Guis/timer/`](reagent/7Guis/timer/timer.cljs) | Benchmark | `examples/timer` | [002 Frames](../spec/002-Frames.md), [004 Views](../spec/004-Views.md) | 7GUIs #4 — Timer. `:dispatch-later` periodic tick; controlled slider; one source of truth for elapsed time. |
| 16 | [`reagent/7Guis/crud/`](reagent/7Guis/crud/crud.cljs) | Benchmark | `examples/crud` | [004 Views](../spec/004-Views.md) | 7GUIs #5 — CRUD. List operations (add / update / delete), selection-as-state, derived filtered list. |
| 17 | [`reagent/7Guis/circle_drawer/`](reagent/7Guis/circle_drawer/circle_drawer.cljs) | Benchmark | `examples/circle-drawer` | [004 Views](../spec/004-Views.md), [002 Frames](../spec/002-Frames.md) | 7GUIs #6 — Circle drawer. Undo/redo via an interceptor that snapshots `:circles`; modal dialog as state. |
| 18 | [`reagent/7Guis/cells/`](reagent/7Guis/cells/cells.cljs) | Benchmark | `examples/cells` | [006 ReactiveSubstrate](../spec/006-ReactiveSubstrate.md), [004 Views](../spec/004-Views.md) | 7GUIs #7 — Cells. Formula evaluation; subscription-graph propagation; cycle detection; pure parser+evaluator. |
| 19 | [`reagent/realworld/`](reagent/realworld/README.md) | Worked scaffold | `examples/realworld` | [014 HTTPRequests](../spec/014-HTTPRequests.md), [012 Routing](../spec/012-Routing.md), [005 StateMachines](../spec/005-StateMachines.md), [011 SSR](../spec/011-SSR.md), [Pattern-RemoteData](../spec/Pattern-RemoteData.md), [Pattern-Forms](../spec/Pattern-Forms.md) | [RealWorld (Conduit)](https://github.com/gothinkster/realworld) — the de-facto cross-framework benchmark. Auth, feeds, routing, comments, editor, profile, favorites, settings, and SSR-hydration glue are all sketched on the current API surface. |

> Story Stage 8 (`tools/story` end-to-end on the canonical counter — seven `reg-*` macros, four variants, two workspaces, plus the privacy + size elision demo) lives as a **tool-owned testbed** at [`tools/story/testbeds/counter_with_stories/`](../tools/story/testbeds/counter_with_stories/). It still builds under `:examples/counter-with-stories` and is exercised by the `npm run test:examples` runner — but it's catalogued with the tool that owns it rather than with the tutorial examples. Same for [`tools/causa/testbeds/`](../tools/causa/) (counter-driven Causa smokes, the rigorous mount/keybinding sweep, and the perf-instrumented variant).

For the 7GUIs cluster's own narrative (entries 13–18 above plus the counter from entry 1), see the cluster README at [`reagent/7Guis/README.md`](reagent/7Guis/README.md).

## UIx

The UIx adapter ships a curated smoke-test subset rather than a 1:1 mirror of the Reagent set. Per [Spec 006 §Adapter shipping convention](../spec/006-ReactiveSubstrate.md) Decision 7, the smoke trio (counter + login + realworld) is reduced to **counter + login** for UIx — realworld is heavy with Reagent-flavoured idioms and is deferred until a UIx user wants it.

| # | Example | Maturity | Build id | What it demonstrates |
|---|---|---|---|---|
| 1 | [`uix/counter_uix/`](uix/counter_uix/) | Pedagogical sketch | `examples/counter-uix` | The Reagent [`counter/`](reagent/counter/) dataflow rendered through the UIx adapter — same events, subs, and `app-db` shape; the view layer is `defui` components consuming subs via the `use-subscribe` hook. |
| 2 | [`uix/login_uix/`](uix/login_uix/) | Pedagogical sketch | `examples/login-uix` | The Reagent [`login/`](reagent/login/) example through UIx — schemas, machine, and managed-HTTP stub are unchanged (substrate-agnostic); only the view layer differs. |

## Helix

The Helix adapter ships the same smoke pair as UIx — counter + login. The eight UIx decisions transferred unchanged because Helix and UIx share the React + hooks substrate model; only the component-shape primitive (`defnc` rather than `defui`) and the target version (Helix 0.2.x rather than UIx 2.x) differ.

| # | Example | Maturity | Build id | What it demonstrates |
|---|---|---|---|---|
| 1 | [`helix/counter_helix/`](helix/counter_helix/) | Pedagogical sketch | `examples/counter-helix` | The Reagent [`counter/`](reagent/counter/) dataflow rendered through the Helix adapter — same events, subs, and `app-db` shape; the view layer is `defnc` components consuming subs via the `use-subscribe` hook. |
| 2 | [`helix/login_helix/`](helix/login_helix/) | Pedagogical sketch | `examples/login-helix` | The Reagent [`login/`](reagent/login/) example through Helix — schemas, machine, and managed-HTTP stub are unchanged (substrate-agnostic); only the view layer differs. |

The bundle-isolation grep at `implementation/scripts/check-bundle-isolation.cjs` runs against the Reagent `examples/counter` bundle — separate per-example shadow-cljs builds per substrate let CI verify a Reagent-substrate example carries no UIx or Helix code, a UIx-substrate example carries no Reagent or Helix code, and a Helix-substrate example carries no Reagent or UIx code.

## Reading order

If you've finished the guide and want to see code:

1. **Start with [`reagent/counter/`](reagent/counter/)** — the smallest possible app. Establishes the basic shape.
2. **Then [`reagent/login/`](reagent/login/)** — adds a state machine, async effects, and form handling. Single-feature scope; full shape.
3. **Then [`reagent/todomvc/`](reagent/todomvc/README.md)** — classic benchmark shape: persistence, editing, filters, and browser routing pressure.
4. **Then [`reagent/routing/`](reagent/routing/)** or [`reagent/ssr/`](reagent/ssr/) — pick whichever is closer to your interest.
5. **Then [`reagent/managed_http_counter/`](reagent/managed_http_counter/)** — the smallest possible Spec 014 demo, before the broader RealWorld surface.
6. **Then [`reagent/state_machine_walkthrough/`](reagent/state_machine_walkthrough/)** — the ch.09 prose as runnable code, with smoke tests for every section.
7. **Then [`reagent/7Guis/`](reagent/7Guis/)** — survey of the pattern across many UI shapes.
8. **Then [`reagent/nine_states/`](reagent/nine_states/README.md)** — the page-level cardinality / lifecycle conventions wired together.
9. **Then [`reagent/realworld/`](reagent/realworld/)** — substantial-app shape across the widest surface in the repo.

If you're building on UIx, read [`uix/counter_uix/`](uix/counter_uix/) and [`uix/login_uix/`](uix/login_uix/) alongside their Reagent siblings — the dataflow is identical; the view layer differs. If you're building on Helix, read [`helix/counter_helix/`](helix/counter_helix/) and [`helix/login_helix/`](helix/login_helix/) the same way.

## End-to-end verification

Every example listed above is verified end-to-end by a Playwright spec — each spec navigates a real browser to the example's URL, asserts the initial render, drives at least one interaction, and asserts the post-interaction user-visible state. The orchestrator at [`scripts/serve-and-run-examples-tests.cjs`](scripts/serve-and-run-examples-tests.cjs) compiles every example, stages its `index.html`, serves the output over HTTP on port 8030, and runs the spec runner at [`scripts/run-examples-tests.cjs`](scripts/run-examples-tests.cjs). Specs sit alongside each example as `<name>.spec.cjs`. Run the full sweep with `npm run test:examples` from `implementation/` (the orchestrator and runner share the `playwright` and `http-server` devDependencies declared in `implementation/package.json`).

### How the orchestrator wires an example up

The orchestrator file declares one entry per example and walks through three steps for the whole set:

1. **Compile.** A single `shadow-cljs compile <build-id> ...` invocation runs every build at once, sharing one JVM warmup. Each build's `:output-dir` is `out/examples/<name>/`.
2. **Stage.** Each example's hand-written `index.html` (and any extra static assets — TodoMVC's CSS, the managed-HTTP counter's `/api/inc.json`) is copied next to the compiled `main.js` under `out/examples/<name>/`.
3. **Serve and run.** `http-server` is launched against `out/examples` on port 8030, then `run-examples-tests.cjs` walks the tree, picks up every `*.spec.cjs`, and runs each spec against `http://127.0.0.1:8030/<mount>/`.

Each spec module exports `{name, url, run}`; `run` is an `async (page) => ...` that issues Playwright assertions. URL mounts match the orchestrator's `outDir` directory name (e.g. `/counter/`, `/managed-http-counter/`, `/state-machine-walkthrough/`).

### Adding a new example

1. Create `examples/<substrate>/<name>/` with the source, a hand-written `index.html`, and a Playwright spec `<name>.spec.cjs`.
2. Add a shadow-cljs build target to `implementation/shadow-cljs.edn` under the existing `:examples/...` block.
3. Append an entry to the `EXAMPLES` array in `examples/scripts/serve-and-run-examples-tests.cjs` declaring `{build, htmlSrc, outDir, extraFiles?}`.
4. Update this catalogue and any per-Spec cross-references that the new example exercises.

The spec runner discovers specs by filesystem walk under `examples/`, `tools/`, and `testbeds/` (rf2-p8f2s), so no additional registration is needed beyond the orchestrator entry. Tool-owned testbeds at `tools/<tool>/testbeds/<scenario>/spec.cjs` are picked up the same way as in-tree example specs.

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
