# Worked examples

> **Type:** Reference
> Demonstrates the spec's primitives composed into real UI work. Read after the guide; refer to alongside the specification.

> **Status reminder.** These examples target the current `re-frame2` API. Their maturity varies: some are aligned closely enough to run against the reference implementation, some are pedagogical sketches, and the RealWorld scaffold is now a broad worked sketch rather than a partially empty placeholder set. Treat the per-example README or docstring as the source of truth for how complete each one is.

## Layout — grouped by substrate

Examples are organised under per-substrate top-level directories. Reagent is the canonical substrate; UIx and Helix each ship a curated set rather than a 1:1 mirror — the **Decision-7 curated pair is counter + login** (carrying compile coverage), plus one design-led example per non-canonical substrate (dashboard for UIx, process-monitor for Helix) that is a documented build. None of these are browser-smoke surfaces: browser smoke coverage is exactly the three adapter-level smokes (see below).

```
examples/
  scripts/                              <-- orchestrator + Playwright helpers
    serve-and-run-examples-tests.cjs    <-- compiles, stages, serves, runs (entry point of `npm run test:examples`)
    run-examples-tests.cjs              <-- Playwright runner (runs the EXAMPLES manifest's specs; reconciles it against the spec.cjs on disk under SPEC_ROOTS)
    spec-helpers.cjs                    <-- shared assertion helpers used by the adapter testbed + Story/Xray specs (examples/ is test-free)
  reagent/                              <-- canonical substrate (full set)
    counter/
      core.cljs
      index.html
    todomvc/
    realworld/
    realworld_resources/                <-- RealWorld on EP-0003 resources + mutations (sibling of realworld/)
    seven_guis/                         <-- 7GUIs benchmark cluster (one sub-folder per task)
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
    ssr_streaming/                      <-- streaming SSR worked example (Spec 011 §Streaming)
    resources/                          <-- Spec 016 Resources worked example (route/event/machine-owned + manual refresh)
    infinite_feed/                      <-- EP-0021 infinite resource (load-more / infinite-scroll feed)
    linearlite/                         <-- EP-0019 optimistic mutation + rollback (Linearlite issue board)
    resources_ssr/                      <-- Spec 016 §SSR resource preload + hydration
    managed_http_counter/
    long_running_work/                  <-- Pattern-LongRunningWork worked example
    websocket/                          <-- Pattern-WebSocket worked example
    notebook/                           <-- design-led example (Editorial Warm identity)
    flows/                              <-- Spec 013 Flows exemplar (cart with materialised totals)
    login/
  reagent-slim/                         <-- day8/reagent-slim substrate (its own adapter)
    counter_slim_and_fast/              <-- same dataflow, mounted on day8/reagent-slim
  uix/                                  <-- UIx adapter examples (curated pair counter + login; dashboard design-led; all compile-only)
    counter_uix/                        <-- folder name carries the namespace suffix so it
    login_uix/                              doesn't collide with reagent/{counter,login}/ on the classpath
    dashboard_uix/                      <-- design-led example; documented build (compile-only)
  helix/                                <-- Helix adapter examples (curated pair counter + login; process-monitor design-led; all compile-only)
    counter_helix/                      <-- folder name carries the namespace suffix so it
    login_helix/                            doesn't collide with reagent/ or uix/ siblings on the classpath
    process_monitor_helix/              <-- design-led example; documented build (compile-only)
```

> **The `examples/` tree is test-free.** No `*.spec.cjs` may live under `examples/`. Browser smoke coverage is exactly 3 adapter-level smokes (Reagent / UIx / Helix) at [`implementation/adapters/<name>/testbed/spec.cjs`](../implementation/adapters/). Real-regression coverage lives in substrate contract tests (`npm run test:cljs`), the Xray feature-matrix gate (`npm run test:xray-feature-gate`), bundle-isolation (`npm run test:bundle-isolation`), the perf-bundle gate (`npm run test:perf-bundle`), and mcp-conformance. Framework testbeds at [`tools/xray/testbeds/`](../tools/xray/testbeds/) and the top-level [`testbeds/`](../testbeds/) stay in-tree as Xray observation targets and carry no paired Playwright `spec.cjs` files — their assertions live as CLJS/JVM unit tests under `implementation/{core,epoch,flows,http,machines,ssr}/test/`.

The orchestrator and the runner consume `playwright` and `http-server` out of `implementation/node_modules/` — there is no separate `examples/package.json` by design; the implementation tree owns the npm dependency surface for the whole repo.

## Reagent

The full set of worked examples — twenty-six in total (counting each 7GUIs task individually), each paired with a shadow-cljs build id. There is no per-example Playwright spec — adapter-level smoke coverage lives at [`implementation/adapters/reagent/testbed/spec.cjs`](../implementation/adapters/reagent/testbed/spec.cjs) and the broader contract coverage lives in `npm run test:cljs` / `test:xray-feature-gate` / `test:bundle-isolation` / `test:perf-bundle`.

Build any example directly via shadow-cljs:

```bash
# from implementation/
shadow-cljs watch examples/counter
```

| # | Example | Maturity | Build id | Spec(s) it illustrates | What it demonstrates |
|---|---|---|---|---|---|
| 1 | [`reagent/counter/`](reagent/counter/) | Pedagogical sketch | `examples/counter` | [002 Frames](../spec/002-Frames.md), [004 Views](../spec/004-Views.md) | The smallest possible app — one event, one sub, one view. The "hello world" of the pattern. |
| 2 | [`reagent/login/`](reagent/login/) | Pedagogical sketch | `examples/login` (+ auxiliary `:examples/login-with-stories`) | [005 StateMachines](../spec/005-StateMachines.md), [014 HTTPRequests](../spec/014-HTTPRequests.md), [010 Schemas](../spec/010-Schemas.md), [008 Testing](../spec/008-Testing.md) | Single-feature scaffold — schema + events + subs + views + machine, all in one file, for a typical login flow. Test-free per the examples policy; the login flow's machine-transition coverage lives in the sibling `state_machine_walkthrough` (whose `state-machine-walkthrough-runs-headless` deftest in `implementation/core/test/re_frame/examples_test.clj` drives the same `:auth.login/flow` table) and in the substrate contract gates. Ships an auxiliary [Story showcase](reagent/login/README.md#how-to-run) (`stories*` trio, build `:examples/login-with-stories`) enumerating every reachable login state as a variant. |
| 3 | [`reagent/todomvc/`](reagent/todomvc/README.md) | Benchmark | `examples/todomvc` | [002 Frames](../spec/002-Frames.md), [004 Views](../spec/004-Views.md), [012 Routing](../spec/012-Routing.md) | Canonical cross-framework todo app: persistence (localStorage), editing, bulk actions, remaining count, and hash-routing filters. |
| 4 | [`reagent/routing/`](reagent/routing/) | Pedagogical sketch | `examples/routing` | [012 Routing](../spec/012-Routing.md) | Three-page app demonstrating `reg-route`, `:rf.route/navigate`, anchor clicks via `:rf/url-requested`, and route-not-found handling. The CP-7 worked example. |
| 5 | [`reagent/ssr/`](reagent/ssr/) | Pedagogical sketch | `examples/ssr` | [011 SSR](../spec/011-SSR.md), [004 Views](../spec/004-Views.md) | Minimal SSR + hydration walkthrough. The CP-9 worked example. JVM-runnable; the browser side hydrates against a baked `<script id="__rf_payload">` block in the static `index.html` (standing in for a real Clojure server in front). |
| 6 | [`reagent/managed_http_counter/`](reagent/managed_http_counter/) | Pedagogical sketch | `examples/managed-http-counter` | [014 HTTPRequests](../spec/014-HTTPRequests.md), [Pattern-AsyncEffect](../spec/Pattern-AsyncEffect.md) | A counter where each button issues a `:rf.http/managed` request: success, 4xx failure, retry-recover (canned-stub), and abort. The compact, single-feature complement to RealWorld for Spec 014. |
| 7 | [`reagent/state_machine_walkthrough/`](reagent/state_machine_walkthrough/) | Pedagogical sketch | `examples/state-machine-walkthrough` | [005 StateMachines](../spec/005-StateMachines.md), [014 HTTPRequests](../spec/014-HTTPRequests.md), [008 Testing](../spec/008-Testing.md) | Runnable companion to [docs/guide/concepts/machines.md](../docs/guide/concepts/machines.md). The chapter's login flow as code; the browser layer drives the canonical lockout scenario (three failures → `:locked-out`). |
| 8 | [`reagent/nine_states/`](reagent/nine_states/README.md) | Benchmark | `examples/nine-states` (+ auxiliary `:examples/nine-states-with-stories`) | [Pattern-NineStates](../spec/Pattern-NineStates.md), [Pattern-RemoteData](../spec/Pattern-RemoteData.md), [Pattern-Forms](../spec/Pattern-Forms.md), [005 StateMachines](../spec/005-StateMachines.md) | The nine canonical UI states (nothing / loading / empty / one / some / too many / incorrect / correct / done) for a single domain. Pedagogically exhaustive. Ships an auxiliary [Story showcase](reagent/nine_states/README.md#how-to-run) (`stories*` trio, build `:examples/nine-states-with-stories`) enumerating each canonical render state as a variant. |
| 9 | [`reagent/boot/`](reagent/boot/README.md) | Pedagogical sketch | `examples/boot` | [Pattern-Boot](../spec/Pattern-Boot.md), [005 StateMachines](../spec/005-StateMachines.md), [014 HTTPRequests](../spec/014-HTTPRequests.md) | A single `:app/boot` machine owns the application's initialisation graph (`:configuring` → `:loading-deps` → `:hydrating` → `:ready`) with `:spawn-all` fan-out for parallel deps and a `:failed` retry path. The canonical Pattern-Boot worked example; demo HTTP stubs via `:rf.http/managed-canned-success`. |
| 10 | [`reagent/long_running_work/`](reagent/long_running_work/README.md) | Pedagogical sketch | `examples/long-running-work` | [Pattern-LongRunningWork](../spec/Pattern-LongRunningWork.md), [005 StateMachines](../spec/005-StateMachines.md) | Declarative spawn-and-join via `:spawn-all` (parent coordinator + N worker children); cooperative cancellation cascade on every exit path (user cancel, parent-unmount, completion); per-step progress reporting as internal self-transitions; browser-tick yielding via `:after`. |
| 11 | [`reagent/websocket/`](reagent/websocket/README.md) | Pedagogical sketch | `examples/websocket` | [Pattern-WebSocket](../spec/Pattern-WebSocket.md), [Pattern-StaleDetection](../spec/Pattern-StaleDetection.md), [005 StateMachines](../spec/005-StateMachines.md) | A connection lifecycle machine — hierarchical compound `:active` parenting `:connecting` / `:authenticating` / `:connected`; a `:spawn`d socket actor; `:after` exponential backoff; `:always` offline-queue flush; `:fsm/tags` for queryable state; connection-epoch staleness; request/reply correlation. In-process mock WebSocket — no network needed. |
| 12 | [`reagent/seven_guis/temperature/`](reagent/seven_guis/temperature/core.cljs) | Benchmark | `examples/temperature` | [004 Views](../spec/004-Views.md), [006 ReactiveSubstrate](../spec/006-ReactiveSubstrate.md) | 7GUIs #2 — Temperature converter. Bidirectional derivations; one source of truth. |
| 13 | [`reagent/seven_guis/flight_booker/`](reagent/seven_guis/flight_booker/core.cljs) | Benchmark | `examples/flight-booker` | [004 Views](../spec/004-Views.md), [Pattern-Forms](../spec/Pattern-Forms.md) | 7GUIs #3 — Flight booker. Form validation; layered subs deriving the Book button's enabled state. |
| 14 | [`reagent/seven_guis/timer/`](reagent/seven_guis/timer/core.cljs) | Benchmark | `examples/timer` | [002 Frames](../spec/002-Frames.md), [004 Views](../spec/004-Views.md) | 7GUIs #4 — Timer. `:dispatch-later` periodic tick; controlled slider; one source of truth for elapsed time. |
| 15 | [`reagent/seven_guis/crud/`](reagent/seven_guis/crud/core.cljs) | Benchmark | `examples/crud` | [004 Views](../spec/004-Views.md) | 7GUIs #5 — CRUD. List operations (add / update / delete), selection-as-state, derived filtered list. |
| 16 | [`reagent/seven_guis/circle_drawer/`](reagent/seven_guis/circle_drawer/core.cljs) | Benchmark | `examples/circle-drawer` | [004 Views](../spec/004-Views.md), [002 Frames](../spec/002-Frames.md) | 7GUIs #6 — Circle drawer. Undo/redo via an interceptor that snapshots `:circles`; modal dialog as state. |
| 17 | [`reagent/seven_guis/cells/`](reagent/seven_guis/cells/core.cljs) | Benchmark | `examples/cells` | [006 ReactiveSubstrate](../spec/006-ReactiveSubstrate.md), [004 Views](../spec/004-Views.md) | 7GUIs #7 — Cells. Formula evaluation; subscription-graph propagation; cycle detection; pure parser+evaluator. |
| 18 | [`reagent/realworld/`](reagent/realworld/README.md) | Worked scaffold | `examples/realworld` | [014 HTTPRequests](../spec/014-HTTPRequests.md), [012 Routing](../spec/012-Routing.md), [013 Flows](../spec/013-Flows.md), [005 StateMachines](../spec/005-StateMachines.md), [011 SSR](../spec/011-SSR.md), [Pattern-RemoteData](../spec/Pattern-RemoteData.md), [Pattern-Forms](../spec/Pattern-Forms.md) | [RealWorld (Conduit)](https://github.com/gothinkster/realworld) — the de-facto cross-framework benchmark. Auth (with an `auth-guard` interceptor), feeds, routing, comments, editor (with an `:editor/can-submit?` flow), profile, favorites, settings, and SSR-hydration glue are all sketched on the current API surface. |
| 19 | [`reagent/ssr_streaming/`](reagent/ssr_streaming/) | Pedagogical sketch | `examples/ssr-streaming` | [011 SSR §Streaming](../spec/011-SSR.md#streaming-ssr), [004 Views](../spec/004-Views.md) | Streaming SSR walkthrough. A dashboard with three slow cards: the page's shell + header render immediately on the server, then each card streams its content as its data fetch resolves. Demonstrates the `:rf/suspense-boundary` hiccup marker, per-card fallback hiccup, inline-fallback failure semantics, and interleaved per-subtree hydration. |
| 20 | [`reagent/notebook/`](reagent/notebook/) | Design-led | `examples/notebook` | [002 Frames](../spec/002-Frames.md), [004 Views](../spec/004-Views.md) | Three-pane editorial layout (documents tree · markdown editor · live preview). The design-led Reagent counterpart to `uix/dashboard_uix/` and `helix/process_monitor_helix/` — all three substrates share the "Editorial Warm" identity from [`examples/_shared/css/style.css`](_shared/css/style.css). Tiny pure-CLJS markdown parser keeps the bundle small. |
| 21 | [`reagent/flows/`](reagent/flows/) | Pedagogical sketch | `examples/flows` | [013 Flows](../spec/013-Flows.md) | The canonical Spec 013 Flows exemplar: a shopping cart whose subtotal + total are *materialised into app-db* by registered flows (not subs). Shows a flow-reads-flow topological cascade, reading a flow's output inside an event handler (`:checkout/place-order`), and a runtime-toggleable discount engaged/cleared via `:rf.fx/reg-flow` / `:rf.fx/clear-flow`. Leads with the load-bearing "why a flow and not a sub?" framing. |
| 22 | [`reagent/resources/`](reagent/resources/README.md) | Worked example (landed runtime) | `examples/resources` | [016 Resources](../spec/016-Resources.md), [014 HTTPRequests](../spec/014-HTTPRequests.md), [012 Routing](../spec/012-Routing.md), [005 StateMachines](../spec/005-StateMachines.md) | Declarative server-state (resources) composed as proper re-frame2: **route-driven page load** (`:resources` route metadata), **event-driven ensure** under an app `[:lease …]` owner with a release path, **manual refresh** as a cause (not an owner), and a **machine-owned resource**. Views read through passive `[:rf.resource/*]` subs; scope is the fail-closed leak boundary (explicit `:rf.scope/global` claim). The read-resource runtime has landed (EP-0003) — all four patterns are wired into the UI and run live against a per-URL canned `:rf.http/managed` stub (no backend ships). Read-side only; mutations are covered in the guide + migration walkthrough, and GraphQL is deferred (see the README). |
| 23 | [`reagent/resources_ssr/`](reagent/resources_ssr/README.md) | Worked scaffold (skeleton-slice) | `examples/resources-ssr` | [016 Resources §SSR](../spec/016-Resources.md), [011 SSR](../spec/011-SSR.md) | Resource SSR preload + hydration: a request-local server frame preloads the page resource under an `[:ssr …]` owner, serializes only the durable `:entries` projection into `:rf/runtime-db`, and the client hydrates without a double-fetch. The static `index.html` bakes the resource projection (stand-in for a real server), as `ssr/` does for plain SSR. The resource counterpart to `reagent/ssr/`. |
| 24 | [`reagent/realworld_resources/`](reagent/realworld_resources/README.md) | Worked scaffold (landed runtime) | `examples/realworld-resources` | [016 Resources](../spec/016-Resources.md), [014 HTTPRequests](../spec/014-HTTPRequests.md), [012 Routing](../spec/012-Routing.md), [013 Flows](../spec/013-Flows.md), [005 StateMachines](../spec/005-StateMachines.md) | [RealWorld (Conduit)](https://github.com/gothinkster/realworld) ported onto EP-0003 **resources + mutations** — the sibling of [`reagent/realworld/`](reagent/realworld/) (the `:rf.http/managed` counterpart, kept intact). Reads are `reg-resource` + route `:resources` metadata read passively via `[:rf.resource/*]` subs (no `:status` app-db fields); writes are `reg-mutation` whose `:invalidates` / `:populates` drive the **read→write→invalidate→refetch loop** end-to-end (favourite / follow / comment / settings / article create-edit-delete). The **article editor** pairs a create/edit `reg-mutation` with a **Spec 013 flow** (`:editor/can-submit?` — valid-AND-dirty materialised into app-db) and a navigation `:can-leave` unsaved-changes guard. Scope is the fail-closed leak boundary — a **named `reg-resource-scope` resolver** (`:realworld/session`) decides the session feed's scope once, referenced everywhere as `{:from-db :realworld/session}` (the feed resource, the home route resource, and the favourite/save/delete mutations' per-target session-scoped `:invalidates` descriptor); public reads claim `:rf.scope/global`, and logout resolves + clears the concrete session scope. Auth stays a Spec 005 machine. Save-success continuations are call-site **`:reply-to` event targets** (EP-0016 D1) — dispatched once when the runtime accepts the reply, after the `:invalidates` reconciled the cache and the instance settled — so the render bodies stay pure Form-1 (only the seed-on-load resource read keeps a Form-3 reaction). The runtime has landed (EP-0003 + EP-0016), so it runs live. |
| 25 | [`reagent/infinite_feed/`](reagent/infinite_feed/README.md) | Worked example (landed runtime) | `examples/infinite-feed` | [016 Resources §Infinite](../spec/016-Resources.md#infinite-resources-and-load-more-feeds), [EP-0021](../docs/EP/EP-0021-infinite-resources.md), [014 HTTPRequests](../spec/014-HTTPRequests.md), [012 Routing](../spec/012-Routing.md) | A load-more / infinite-scroll feed as a first-class **infinite resource** (EP-0021): a `reg-resource` with `:infinite true` + a pure `:next-page-param` derivation, owned by the route (route entry ensures **page 0**), read entirely through the passive `[:rf.resource/infinite-state …]` view-model (`:items` merged list, `:has-next-page?`, `:fetching-next?`, `:page-error`), with accumulation driven by the **causal** `:rf.resource/load-more` event (a `:cause`, no `:owner` — the route owns the feed). No app-db list slice, no cursor threading, no append reducer — the runtime owns the page vector, the cursor, the in-flight dedupe, the `nil` terminal, and the third error channel. Enveloped pages declare the required `:page->items` accessor (loud over guessing). The complement to numbered pagination (`:keep-previous?` in `realworld_resources`). Runs live against a per-cursor canned `:rf.http/managed` stub (no backend ships). |
| 26 | [`reagent/linearlite/`](reagent/linearlite/README.md) | Worked example (landed runtime) | `examples/linearlite` | [016 Resources §Optimistic](../spec/016-Resources.md#optimistic-mutations), [EP-0019](../docs/EP/EP-0019-optimistic-mutation-rollback.md), [014 HTTPRequests](../spec/014-HTTPRequests.md), [012 Routing](../spec/012-Routing.md) | The **write-side flagship** optimistic dogfood (the counterpart of `infinite_feed`'s read-side load-more): a Linearlite-class issue board where every write — **create / retitle / change-status** — is a `reg-mutation` with an **`:optimistic`** exact-target patch over the board resource. The board updates at **phase 1.5** (before the request lowers), then **commits** (`:populates` overwrites with the server's authoritative board) on `:ok` or **rolls back** (the runtime-recorded snapshot inverse is restored verbatim) on `:error`. The author writes only the *forward* patch — the runtime records the inverse + the per-entry revision. A **"Fail the next write"** toggle arms the canned `:rf.http/managed` stub to answer the next write with a 503, so the **rollback** (the optimistic change visibly reverts) is the headline demonstration; the watched `[:rf.mutation/state …]` `:optimistic?` flag (Rider 1) renders the "saving…" badge while pending and `:on-conflict :invalidate` (the default) governs a contested rollback. Idiomatic re-frame2: the board is a single managed resource, every write a named mutation — **no app-db issue list, no `:saving?` flag, no manual undo**. Runs live against a canned `:rf.http/managed` stub (no backend ships). |

> Story Stage 8 (`tools/story` end-to-end on the canonical counter — seven `reg-*` macros, four variants, two workspaces, plus the privacy + size elision demo) lives as a **tool-owned testbed** at [`tools/story/testbeds/counter_with_stories/`](../tools/story/testbeds/counter_with_stories/). It builds under `:examples/counter-with-stories` and is exercised by `npm run test:story-feature-load` — but it's catalogued with the tool that owns it rather than with the tutorial examples. Same for [`tools/xray/testbeds/`](../tools/xray/) (the canonical multi-frame `two_frame_isolation` demo, the deterministic `feature_matrix` sweep, the Panel-view `panel_gallery`, and the baseline `standard_epochs` testbed).

> **Auxiliary Story showcases on two Reagent examples.** Separately from the tool-owned counter testbed, two of the Reagent examples below ship an **auxiliary Story showcase** layered over the example itself: [`reagent/login/`](reagent/login/) (build `:examples/login-with-stories`, rf2-p8v0q) and [`reagent/nine_states/`](reagent/nine_states/) (build `:examples/nine-states-with-stories`, rf2-rgyia). Each is a `stories.cljs` + `stories_host.cljs` + `stories.index.html` trio in the example folder that sources the example's real machine/views and enumerates its view-states as Story variants with Xray wired — a runnable inspection surface, not a test (the example tree stays test-free per rf2-8cevm). See the [Reagent catalogue](#reagent) rows and each example's README for the showcase run command.

For the 7GUIs cluster's own narrative (entries 12–17 above plus the counter from entry 1), see the cluster README at [`reagent/seven_guis/README.md`](reagent/seven_guis/README.md).

## Reagent Slim

The `day8/reagent-slim` adapter ([`implementation/adapters/reagent-slim/`](../implementation/adapters/reagent-slim/)) is its own substrate — a ground-up `reagent2.*` rewrite, not the thin bridge over stock Reagent. It carries a single example: the canonical counter dataflow re-mounted on the slim substrate, kept as the adapter's bundle-isolation fixture rather than a tutorial.

| # | Example | Maturity | Build id | Spec(s) it illustrates | What it demonstrates |
|---|---|---|---|---|---|
| 1 | [`reagent-slim/counter_slim_and_fast/`](reagent-slim/counter_slim_and_fast/) | Adapter fixture | `examples/counter-slim-and-fast` | [006 ReactiveSubstrate](../spec/006-ReactiveSubstrate.md), [Conventions §Adapter test matrix](../spec/Conventions.md#adapter-test-matrix-policy) | The same counter dataflow as the canonical Reagent counter, but mounted on the `day8/reagent-slim` rewrite (every user-facing `reagent.*` import → `reagent2.*`; `rf/init!` takes the slim adapter Var). The paired `implementation/scripts/check-reagent-slim-bundle-isolation.cjs` asserts the advanced bundle contains no `reagent.impl.*` and no `react-dom/server` symbols — the slim adapter's bundle-isolation contract. |

## UIx

The UIx adapter ships a curated set rather than a 1:1 mirror of the Reagent set. Per [Spec 006 §Adapter shipping convention](../spec/006-ReactiveSubstrate.md) Decision 7, the **curated example subset is counter + login** — the representative pair that shares its substrate-agnostic dataflow with the Reagent siblings; realworld is heavy with Reagent-flavoured idioms and is deferred until a UIx user wants it. Because the `examples/` tree is test-free, that pair carries **compile coverage only** (`test:examples-compile`): the *runtime* substrate-contract smoke that proves the UIx adapter wires up end-to-end is the single mount+dispatch+assert smoke at the adapter testbed [`implementation/adapters/uix/testbed/spec.cjs`](../implementation/adapters/uix/testbed/spec.cjs), not the example pages. Alongside the curated pair the tree also ships `dashboard_uix`, a design-led example: a documented build (it carries compile coverage) but **not** part of the Decision-7 curated subset.

| # | Example | Maturity | Build id | What it demonstrates |
|---|---|---|---|---|
| 1 | [`uix/counter_uix/`](uix/counter_uix/) | Pedagogical sketch | `examples/counter-uix` | The Reagent [`counter/`](reagent/counter/) dataflow rendered through the UIx adapter — same events, subs, and `app-db` shape; the view layer is `defui` components consuming subs via the `use-subscribe` hook. |
| 2 | [`uix/login_uix/`](uix/login_uix/) | Pedagogical sketch | `examples/login-uix` | The Reagent [`login/`](reagent/login/) example through UIx — schemas, machine, and managed-HTTP stub are unchanged (substrate-agnostic); only the view layer differs. |
| 3 | [`uix/dashboard_uix/`](uix/dashboard_uix/) | Design-led (compile-only) | `examples/dashboard-uix` | Design-led example proving UIx can drive a substantive multi-pane layout. Documented build (carries compile coverage) but not part of the Decision-7 curated pair. Shares the "Editorial Warm" identity from [`examples/_shared/css/style.css`](_shared/css/style.css) with the Reagent `notebook/` and Helix `process_monitor_helix/` siblings. |

## Helix

The Helix adapter ships the same shape as UIx — the **curated pair is counter + login** per Decision 7, plus the process-monitor design-led example (a documented build). All three carry compile coverage only. The eight UIx decisions transferred unchanged because Helix and UIx share the React + hooks substrate model; only the component-shape primitive (`defnc` rather than `defui`) and the target version (Helix 0.2.x rather than UIx 2.x) differ. The runtime browser-smoke that proves the Helix adapter wires up end-to-end is the single adapter-testbed smoke at [`implementation/adapters/helix/testbed/spec.cjs`](../implementation/adapters/helix/testbed/spec.cjs), not the example pages.

| # | Example | Maturity | Build id | What it demonstrates |
|---|---|---|---|---|
| 1 | [`helix/counter_helix/`](helix/counter_helix/) | Pedagogical sketch | `examples/counter-helix` | The Reagent [`counter/`](reagent/counter/) dataflow rendered through the Helix adapter — same events, subs, and `app-db` shape; the view layer is `defnc` components consuming subs via the `use-subscribe` hook. |
| 2 | [`helix/login_helix/`](helix/login_helix/) | Pedagogical sketch | `examples/login-helix` | The Reagent [`login/`](reagent/login/) example through Helix — schemas, machine, and managed-HTTP stub are unchanged (substrate-agnostic); only the view layer differs. |
| 3 | [`helix/process_monitor_helix/`](helix/process_monitor_helix/) | Design-led (compile-only) | `examples/process-monitor-helix` | Design-led example proving Helix can drive a substantive multi-pane layout. Documented build (carries compile coverage) but not part of the Decision-7 curated pair. Shares the "Editorial Warm" identity from [`examples/_shared/css/style.css`](_shared/css/style.css) with the Reagent `notebook/` and UIx `dashboard_uix/` siblings. |

The bundle-isolation grep at `implementation/scripts/check-bundle-isolation.cjs` runs against the Reagent `examples/counter` bundle — separate per-example shadow-cljs builds per substrate let CI verify a Reagent-substrate example carries no UIx or Helix code, a UIx-substrate example carries no Reagent or Helix code, and a Helix-substrate example carries no Reagent or UIx code.

## Reading order

If you've finished the guide and want to see code:

1. **Start with [`reagent/counter/`](reagent/counter/)** — the smallest possible app. Establishes the basic shape.
2. **Then [`reagent/login/`](reagent/login/)** — adds a state machine, async effects, and form handling. Single-feature scope; full shape.
3. **Then [`reagent/todomvc/`](reagent/todomvc/README.md)** — classic benchmark shape: persistence, editing, filters, and browser routing pressure.
4. **Then [`reagent/routing/`](reagent/routing/)** or [`reagent/ssr/`](reagent/ssr/) — pick whichever is closer to your interest.
5. **Then [`reagent/managed_http_counter/`](reagent/managed_http_counter/)** — the smallest possible Spec 014 demo, before the broader RealWorld surface.
6. **Then [`reagent/state_machine_walkthrough/`](reagent/state_machine_walkthrough/)** — the ch.11 prose as runnable code; its headless scenarios run via the `state-machine-walkthrough-runs-headless` gate deftest in [`implementation/core/test/re_frame/examples_test.clj`](../implementation/core/test/re_frame/examples_test.clj).
7. **Then [`reagent/seven_guis/`](reagent/seven_guis/)** — survey of the pattern across many UI shapes.
8. **Then [`reagent/nine_states/`](reagent/nine_states/README.md)** — the page-level cardinality / lifecycle conventions wired together.
9. **Then [`reagent/realworld/`](reagent/realworld/)** — substantial-app shape across the widest surface in the repo.

If you're building on UIx, read [`uix/counter_uix/`](uix/counter_uix/) and [`uix/login_uix/`](uix/login_uix/) alongside their Reagent siblings — the dataflow is identical; the view layer differs. If you're building on Helix, read [`helix/counter_helix/`](helix/counter_helix/) and [`helix/login_helix/`](helix/login_helix/) the same way.

## End-to-end verification

The `examples/` tree is **test-free** — no `*.spec.cjs` lives under `examples/`. Real-regression coverage lives in:

- **`npm run test:cljs`** — substrate contract tests (events, subs, handlers, machines, schemas) across every artefact under `npm run test:cljs`'s node-runtime CLJS suite.
- **`npm run test:examples`** — adapter-level smokes only. Compiles + serves the 3 adapter testbeds (`implementation/adapters/<name>/testbed/`) and runs their paired `spec.cjs`. The framework + top-level testbeds do not carry Playwright specs; their assertions live as CLJS/JVM unit tests.
- **`npm run test:xray-feature-gate`** — 14-scenario Xray feature-matrix gate. The canonical browser sweep for cross-cutting feature regressions.
- **`npm run test:bundle-isolation`** — production bundle grep contract for the per-feature artefact split.
- **`npm run test:perf-bundle`** — static perf-flag bundle-isolation grep (the live perf-API counterpart at `implementation/core/test/re_frame/performance_emit_nightly_test.cljs` runs in the nightly CLJS suite).
- **`npm run test:script-policy`** — JS-harness self-tests, including the **static examples asset-contract gate** ([`examples/scripts/check-examples-assets.cjs`](scripts/check-examples-assets.cjs)). For every example `index.html` it verifies each referenced asset (`_shared/*` css/img + transitive `@import` targets) resolves to a real file and that every page carries the required shared assets — favicon, OG card, and `_shared/css/style.css` — unless explicitly allowlisted (TodoMVC opts out of the shared stylesheet but still ships the shared favicon + OG). This catches a broken/renamed/missing `_shared` asset that the adapter-smoke harness cannot, since the testbed pages it serves link none of `_shared`.
- **`npm run test:story-feature-load`** — Story tool feature/load gate (occasional).

### Building examples interactively

If you want to iterate on one example:

```bash
# From implementation/ — pick the build id from the catalogue above.
shadow-cljs watch examples/counter
```

The build's `:output-dir` (see `implementation/shadow-cljs.edn`) is where `main.js` lands. Stage the example's hand-written `index.html` next to that `main.js`, copy the `examples/_shared/` tree alongside it so the `_shared/...` hrefs resolve, then serve that directory over HTTP. (The `npm run test:examples` smoke harness does this automatically for the three adapter testbeds, staging into `implementation/out/examples/adapter-testbeds/<name>/`; the per-build `:output-dir` for a standalone `:examples/*` build is whatever that build declares.)

For TodoMVC, also copy the two vendored CSS files next to `main.js` (its `index.html` links them flat instead of the shared stylesheet — see [`reagent/todomvc/README.md`](reagent/todomvc/README.md) §Official assets).

### Adding a new example

1. Create `examples/<substrate>/<name>/` with the source and a hand-written `index.html`.
2. Add a shadow-cljs build target to `implementation/shadow-cljs.edn` under the existing `:examples/...` block.
3. Update this catalogue and any per-Spec cross-references that the new example exercises.

**Do NOT add a `*.spec.cjs` under `examples/`.** If the new example proves a new framework contract that isn't already covered by `test:cljs` / `test:xray-feature-gate` / bundle-isolation / perf-bundle, file a follow-up bead to extend the appropriate gate (or, for a genuinely new cross-cutting surface, add a top-level `testbeds/<surface>/` with its own `spec.cjs`).

### Banner-comment style — size-keyed

Section dividers in `core.cljs` files use one of two styles, keyed by file size:

- **Light banner** (`;; -- Events / subs --`) — for sketch-sized files (~60-100 lines). Used by `counter/`, `counter_uix/`, `counter_helix/` and similar small pedagogical examples.
- **Heavy ASCII box** (a 76-char `;; ===…===` rule above and below a `;; SECTION NAME` line) — for design-led and machine-bearing files (~200-300+ lines). Used by `dashboard_uix/`, `process_monitor_helix/`, the three `login*` examples, `notebook/`, `realworld/`, etc.

The heavy box earns its visual weight in files where the section markers must be scannable across multiple pages. The light style would disappear in a long file; the heavy style overwhelms a short one. Prefer the light style by default; promote to the heavy style when section markers become navigation aids rather than pretty hairlines.

## What examples are *not*

- **Not a substitute for the [specification](../spec/).** Examples illustrate; the specification defines.
- **Not all uniformly polished.** The Pedagogical-sketch examples are deliberately small. The Worked-scaffold (RealWorld) prioritises breadth of API coverage over production polish.
