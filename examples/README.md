# Worked examples

These are small, complete apps you can run and read top to bottom — each one composing the spec's primitives into real UI. They're organised by concept:

- **Core** — the fundamentals (the dataflow concepts in [docs/core/concepts](../docs/core/concepts/)).
- **Capabilities** — one framework subsystem per folder (each has its own `docs/<capability>/` guide).
- **Patterns** — composition recipes built from the capabilities (the `spec/Pattern-*` docs).
- **Real-apps** — full applications that put it all together.
- **Substrates** — the same apps rendered on other substrates (UIx, Helix, reagent-slim).

They range from the counter (the smallest app the pattern admits) to RealWorld (the widest surface in the repo). Run any from `implementation/` with `shadow-cljs watch <build-id>` (the build-id is in each row).

## Layout

```
examples/
  scripts/                     <-- example dev runner + Story launchers + shared Playwright helpers
  _shared/                     <-- shared CSS + images (the "Editorial Warm" identity)
  core/                        <-- the fundamentals
    counter/
    login/
    todomvc/
    flows/
    managed_http_counter/
    notebook/                  <-- design-led
    seven_guis/                <-- 7GUIs cluster (one folder per task)
      cells/
      circle_drawer/
      crud/
      flight_booker/
      temperature/
      timer/
  capabilities/                <-- one subsystem per folder
    machines/
      state_machine_walkthrough/
    routing/
      routing/
    resources/
      resources/
      infinite_feed/
      linearlite/
    ssr/
      ssr/
      ssr_streaming/
      resources_ssr/
  patterns/                    <-- composition recipes
    boot/
    long_running_work/
    websocket/
    nine_states/
  real-apps/                   <-- full applications
    realworld_http/
    realworld_resources/
    realworld_shared/          <-- shared by both RealWorld apps
  substrates/                  <-- the same apps on other substrates
    uix/
      counter/
      login/
      dashboard/
    helix/
      counter/
      login/
      process_monitor/
    reagent_slim/
      counter/
```

## Core

The fundamentals — the dataflow concepts every other example builds on.

| Example | What it demonstrates |
|---|---|
| [`core/counter/`](core/counter/) — `examples/counter` | The smallest app in the set — three one-line events, one sub, two tiny views. The "hello world" of the pattern. **Specs:** [002 Frames](../spec/002-Frames.md), [004 Views](../spec/004-Views.md). |
| [`core/login/`](core/login/) — `examples/login` | Single-feature scaffold — schema, events, subs, views, and a machine in one file for a typical login flow. Ships an auxiliary [Story showcase](core/login/README.md#how-to-run) (`:examples/login-with-stories`) enumerating every reachable login state. **Specs:** [005 StateMachines](../spec/005-StateMachines.md), [014 HTTPRequests](../spec/014-HTTPRequests.md), [010 Schemas](../spec/010-Schemas.md), [008 Testing](../spec/008-Testing.md). |
| [`core/todomvc/`](core/todomvc/README.md) — `examples/todomvc` | Canonical cross-framework todo app: localStorage persistence, editing, bulk actions, remaining count, and hash-routing filters. **Specs:** [002 Frames](../spec/002-Frames.md), [004 Views](../spec/004-Views.md), [012 Routing](../spec/012-Routing.md). |
| [`core/flows/`](core/flows/) — `examples/flows` | The canonical Flows exemplar: a cart whose subtotal + total are *materialised into app-db* by registered flows (not subs). A flow-reads-flow cascade, reading a flow inside an event handler, and a runtime-toggleable discount. **Specs:** [013 Flows](../spec/013-Flows.md). |
| [`core/managed_http_counter/`](core/managed_http_counter/) — `examples/managed-http-counter` | A counter where each button issues a `:rf.http/managed` request: success, 4xx failure, retry-recover, and abort. The compact complement to RealWorld for Spec 014. **Specs:** [014 HTTPRequests](../spec/014-HTTPRequests.md), [Pattern-AsyncEffect](../spec/Pattern-AsyncEffect.md). |
| [`core/notebook/`](core/notebook/) — `examples/notebook` | Three-pane editorial layout (documents tree · markdown editor · live preview). The design-led Reagent counterpart to `substrates/uix/dashboard/` and `substrates/helix/process_monitor/` — all three share the "Editorial Warm" identity from [`_shared/css/style.css`](_shared/css/style.css). **Specs:** [002 Frames](../spec/002-Frames.md), [004 Views](../spec/004-Views.md). |
| [`core/seven_guis/temperature/`](core/seven_guis/temperature/core.cljs) — `examples/temperature` | 7GUIs #2 — Temperature converter. Bidirectional derivations; one source of truth. **Specs:** [004 Views](../spec/004-Views.md), [006 ReactiveSubstrate](../spec/006-ReactiveSubstrate.md). |
| [`core/seven_guis/flight_booker/`](core/seven_guis/flight_booker/core.cljs) — `examples/flight-booker` | 7GUIs #3 — Flight booker. Form validation; layered subs deriving the Book button's enabled state. **Specs:** [004 Views](../spec/004-Views.md), [Pattern-Forms](../spec/Pattern-Forms.md). |
| [`core/seven_guis/timer/`](core/seven_guis/timer/core.cljs) — `examples/timer` | 7GUIs #4 — Timer. `:dispatch-later` periodic tick; controlled slider; one source of truth for elapsed time. **Specs:** [002 Frames](../spec/002-Frames.md), [004 Views](../spec/004-Views.md). |
| [`core/seven_guis/crud/`](core/seven_guis/crud/core.cljs) — `examples/crud` | 7GUIs #5 — CRUD. List operations (add / update / delete), selection-as-state, derived filtered list. **Specs:** [004 Views](../spec/004-Views.md). |
| [`core/seven_guis/circle_drawer/`](core/seven_guis/circle_drawer/core.cljs) — `examples/circle-drawer` | 7GUIs #6 — Circle drawer. Undo/redo via an interceptor that snapshots `:circles`; modal dialog as state. **Specs:** [004 Views](../spec/004-Views.md), [002 Frames](../spec/002-Frames.md). |
| [`core/seven_guis/cells/`](core/seven_guis/cells/core.cljs) — `examples/cells` | 7GUIs #7 — Cells. Formula evaluation; subscription-graph propagation; cycle detection; pure parser + evaluator. **Specs:** [006 ReactiveSubstrate](../spec/006-ReactiveSubstrate.md), [004 Views](../spec/004-Views.md). |

For the 7GUIs cluster's own narrative, see [`core/seven_guis/README.md`](core/seven_guis/README.md).

## Capabilities

Each folder is one framework subsystem, paired with its `docs/<capability>/` guide.

### Machines

| Example | What it demonstrates |
|---|---|
| [`capabilities/machines/state_machine_walkthrough/`](capabilities/machines/state_machine_walkthrough/) — `examples/state-machine-walkthrough` | Runnable companion to [docs/machines/concepts.md](../docs/machines/concepts.md): the chapter's login flow as code, driving the canonical lockout scenario (three failures → `:locked-out`). **Specs:** [005 StateMachines](../spec/005-StateMachines.md), [014 HTTPRequests](../spec/014-HTTPRequests.md), [008 Testing](../spec/008-Testing.md). |

### Routing

| Example | What it demonstrates |
|---|---|
| [`capabilities/routing/routing/`](capabilities/routing/routing/) — `examples/routing` | Three-page app: `reg-route`, `:rf.route/navigate`, anchor clicks via `:rf/url-requested`, and route-not-found handling. **Specs:** [012 Routing](../spec/012-Routing.md). |

### Resources

| Example | What it demonstrates |
|---|---|
| [`capabilities/resources/resources/`](capabilities/resources/resources/README.md) — `examples/resources` | Declarative server-state as proper re-frame2: route-driven page load, event-driven ensure under a `[:lease …]` owner, manual refresh as a cause, and a machine-owned resource. Views read through passive `[:rf.resource/*]` subs. Runs live against a per-URL canned `:rf.http/managed` stub (no backend ships). **Specs:** [016 Resources](../spec/016-Resources.md), [014 HTTPRequests](../spec/014-HTTPRequests.md), [012 Routing](../spec/012-Routing.md), [005 StateMachines](../spec/005-StateMachines.md). |
| [`capabilities/resources/infinite_feed/`](capabilities/resources/infinite_feed/README.md) — `examples/infinite-feed` | A load-more / infinite-scroll feed as a first-class **infinite resource** (EP-0021): `reg-resource` with `:infinite true` + a pure `:next-page-param`, read through the passive `[:rf.resource/infinite-state …]` view-model, with accumulation driven by the causal `:rf.resource/load-more` event. No app-db list, no cursor threading. Runs live against a canned stub. **Specs:** [016 Resources §Infinite](../spec/016-Resources.md#infinite-resources-and-load-more-feeds), [EP-0021](../docs/EP/EP-0021-infinite-resources.md), [014 HTTPRequests](../spec/014-HTTPRequests.md), [012 Routing](../spec/012-Routing.md). |
| [`capabilities/resources/linearlite/`](capabilities/resources/linearlite/README.md) — `examples/linearlite` | The write-side flagship: a Linearlite-class issue board where every write — create / retitle / change-status — is a `reg-mutation` with an `:optimistic` patch. The board updates at phase 1.5, then commits on `:ok` or rolls back on `:error` (the runtime records the inverse). A "Fail the next write" toggle makes rollback the headline. No app-db issue list, no `:saving?` flag, no manual undo. Runs live against a canned stub. **Specs:** [016 Resources §Optimistic](../spec/016-Resources.md#optimistic-mutations), [EP-0019](../docs/EP/EP-0019-optimistic-mutation-rollback.md), [014 HTTPRequests](../spec/014-HTTPRequests.md), [012 Routing](../spec/012-Routing.md). |

### SSR

| Example | What it demonstrates |
|---|---|
| [`capabilities/ssr/ssr/`](capabilities/ssr/ssr/) — `examples/ssr` | Minimal SSR + hydration. JVM-runnable; the browser hydrates against a baked `<script id="__rf_payload">` block. **Specs:** [011 SSR](../spec/011-SSR.md), [004 Views](../spec/004-Views.md). |
| [`capabilities/ssr/ssr_streaming/`](capabilities/ssr/ssr_streaming/) — `examples/ssr-streaming` | Streaming SSR: a dashboard whose shell renders immediately, then each slow card streams as its fetch resolves. The `:rf/suspense-boundary` marker, per-card fallback, and interleaved hydration. **Specs:** [011 SSR §Streaming](../spec/011-SSR.md#streaming-ssr), [004 Views](../spec/004-Views.md). |
| [`capabilities/ssr/resources_ssr/`](capabilities/ssr/resources_ssr/README.md) — `examples/resources-ssr` | Resource SSR preload + hydration: a request-local server frame preloads the page resource under an `[:ssr …]` owner, serialises the durable `:entries` projection into `:rf/runtime-db`, and the client hydrates without a double-fetch. The resource counterpart to `capabilities/ssr/ssr/`. **Specs:** [016 Resources §SSR](../spec/016-Resources.md), [011 SSR](../spec/011-SSR.md). |

## Patterns

Composition recipes built from the capabilities — the `spec/Pattern-*` docs as runnable code.

| Example | What it demonstrates |
|---|---|
| [`patterns/boot/`](patterns/boot/README.md) — `examples/boot` | A single `:app/boot` machine owns initialisation (`:configuring` → `:loading-deps` → `:hydrating` → `:ready`) with `:spawn-all` fan-out for parallel deps and a `:failed` retry path. **Specs:** [Pattern-Boot](../spec/Pattern-Boot.md), [005 StateMachines](../spec/005-StateMachines.md), [014 HTTPRequests](../spec/014-HTTPRequests.md). |
| [`patterns/long_running_work/`](patterns/long_running_work/README.md) — `examples/long-running-work` | Declarative spawn-and-join via `:spawn-all` (parent + N workers); cooperative cancellation on every exit path; per-step progress as internal self-transitions; browser-tick yielding via `:after`. **Specs:** [Pattern-LongRunningWork](../spec/Pattern-LongRunningWork.md), [005 StateMachines](../spec/005-StateMachines.md). |
| [`patterns/websocket/`](patterns/websocket/README.md) — `examples/websocket` | A connection-lifecycle machine: hierarchical `:active` parenting connect / auth / connected, a `:spawn`d socket actor, `:after` backoff, `:always` offline-queue flush, connection-epoch staleness, request/reply correlation. In-process mock socket — no network. **Specs:** [Pattern-WebSocket](../spec/Pattern-WebSocket.md), [Pattern-StaleDetection](../spec/Pattern-StaleDetection.md), [005 StateMachines](../spec/005-StateMachines.md). |
| [`patterns/nine_states/`](patterns/nine_states/README.md) — `examples/nine-states` | The nine canonical UI states (nothing / loading / empty / one / some / too many / incorrect / correct / done) for one domain. Ships an auxiliary [Story showcase](patterns/nine_states/README.md#how-to-run) (`:examples/nine-states-with-stories`). **Specs:** [Pattern-NineStates](../spec/Pattern-NineStates.md), [Pattern-RemoteData](../spec/Pattern-RemoteData.md), [Pattern-Forms](../spec/Pattern-Forms.md), [005 StateMachines](../spec/005-StateMachines.md). |

## Real-apps

Full applications that exercise the widest surface in the repo.

| Example | What it demonstrates |
|---|---|
| [`real-apps/realworld_http/`](real-apps/realworld_http/README.md) — `examples/realworld` | [RealWorld (Conduit)](https://github.com/gothinkster/realworld) — the cross-framework benchmark, on `:rf.http/managed`. Auth (with an `auth-guard` interceptor), feeds, routing, comments, an editor with an `:editor/can-submit?` flow, profile, favorites, settings, and SSR-hydration glue. **Specs:** [014 HTTPRequests](../spec/014-HTTPRequests.md), [012 Routing](../spec/012-Routing.md), [013 Flows](../spec/013-Flows.md), [005 StateMachines](../spec/005-StateMachines.md), [011 SSR](../spec/011-SSR.md), [Pattern-RemoteData](../spec/Pattern-RemoteData.md), [Pattern-Forms](../spec/Pattern-Forms.md). |
| [`real-apps/realworld_resources/`](real-apps/realworld_resources/README.md) — `examples/realworld-resources` | [RealWorld (Conduit)](https://github.com/gothinkster/realworld) on EP-0003 **resources + mutations** — the sibling of [`real-apps/realworld_http/`](real-apps/realworld_http/). Reads are `reg-resource` + route `:resources` metadata; writes are `reg-mutation` whose `:invalidates` / `:populates` drive the read→write→invalidate→refetch loop. The editor pairs a mutation with a Spec 013 `:editor/can-submit?` flow and an unsaved-changes `:can-leave` guard. Runs live. **Specs:** [016 Resources](../spec/016-Resources.md), [014 HTTPRequests](../spec/014-HTTPRequests.md), [012 Routing](../spec/012-Routing.md), [013 Flows](../spec/013-Flows.md), [005 StateMachines](../spec/005-StateMachines.md). |

Both apps share read-only helpers (avatars, markdown rendering) from `real-apps/realworld_shared/`.

## Substrates

The same dataflow, rendered on a different substrate — the proof the adapter swaps cleanly. UIx and Helix each ship the curated **counter + login** pair plus one design-led example; reagent-slim ships the counter.

| Example | What it demonstrates |
|---|---|
| [`substrates/uix/counter/`](substrates/uix/counter/) — `examples/counter-uix` | The [`core/counter/`](core/counter/) dataflow through UIx — same events, subs, and `app-db`; views are `defui` components consuming subs via the `use-subscribe` hook. |
| [`substrates/uix/login/`](substrates/uix/login/) — `examples/login-uix` | The [`core/login/`](core/login/) example through UIx — schemas, machine, and HTTP stub unchanged; only the view layer differs. |
| [`substrates/uix/dashboard/`](substrates/uix/dashboard/) — `examples/dashboard-uix` | Design-led: UIx driving a substantive multi-pane layout. Shares the "Editorial Warm" identity from [`_shared/css/style.css`](_shared/css/style.css) with `core/notebook/` and `substrates/helix/process_monitor/`. |
| [`substrates/helix/counter/`](substrates/helix/counter/) — `examples/counter-helix` | The [`core/counter/`](core/counter/) dataflow through Helix — same events, subs, and `app-db`; views are `defnc` components consuming subs via the `use-subscribe` hook. |
| [`substrates/helix/login/`](substrates/helix/login/) — `examples/login-helix` | The [`core/login/`](core/login/) example through Helix — schemas, machine, and HTTP stub unchanged; only the view layer differs. |
| [`substrates/helix/process_monitor/`](substrates/helix/process_monitor/) — `examples/process-monitor-helix` | Design-led: Helix driving a substantive multi-pane layout. Shares the "Editorial Warm" identity from [`_shared/css/style.css`](_shared/css/style.css) with `core/notebook/` and `substrates/uix/dashboard/`. |
| [`substrates/reagent_slim/counter/`](substrates/reagent_slim/counter/) — `examples/counter-slim-and-fast` | The [`core/counter/`](core/counter/) dataflow on `day8/reagent-slim` (a ground-up `reagent2.*` rewrite; every `reagent.*` import → `reagent2.*`; `rf/init!` takes the slim adapter Var). The interest is in what the bundle does *not* contain. **Specs:** [006 ReactiveSubstrate](../spec/006-ReactiveSubstrate.md), [Conventions §Adapter test matrix](../spec/Conventions.md#adapter-test-matrix-policy). |

> **Auxiliary Story showcases.** Two examples ship a Story showcase layered over the example itself: [`core/login/`](core/login/) (`:examples/login-with-stories`) and [`patterns/nine_states/`](patterns/nine_states/) (`:examples/nine-states-with-stories`) — a `stories.cljs` + `stories_host.cljs` + `stories.index.html` trio that sources the example's real machine and views and enumerates its states as Story variants. See each example's README for the run command.
