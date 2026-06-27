# Worked examples

These are small, complete apps you can run and read top to bottom. They range from the counter (the smallest app the pattern admits) to RealWorld (the widest surface in the repo) — each one composing the spec's primitives into real UI.

## Layout — grouped by substrate

Grouped by substrate — the rendering layer under the dataflow. Reagent is canonical and carries the full set; UIx and Helix each ship a small curated slice.

```
examples/
  scripts/                     <-- example dev runner + Story launchers + shared Playwright helpers
  reagent/                     <-- canonical (full set)
    counter/
    todomvc/
    realworld/
    realworld_resources/
    seven_guis/                <-- 7GUIs cluster (one folder per task)
      cells/
      circle_drawer/
      crud/
      flight_booker/
      temperature/
      timer/
    boot/
    state_machine_walkthrough/
    nine_states/
    routing/
    ssr/
    ssr_streaming/
    resources/
    infinite_feed/
    linearlite/
    resources_ssr/
    managed_http_counter/
    long_running_work/
    websocket/
    notebook/
    flows/
    login/
  reagent-slim/                <-- day8/reagent-slim substrate
    counter_slim_and_fast/
  uix/                         <-- curated: counter + login; dashboard design-led
    counter_uix/
    login_uix/
    dashboard_uix/
  helix/                       <-- curated: counter + login; process-monitor design-led
    counter_helix/
    login_helix/
    process_monitor_helix/
```

## Reagent

The full set — twenty-six examples (each 7GUIs task counted). Run any from `implementation/` with `shadow-cljs watch examples/<id>`.

| Example | What it demonstrates |
|---|---|
| [`reagent/counter/`](reagent/counter/) — `examples/counter` | The smallest possible app — one event, one sub, one view. The "hello world" of the pattern. **Specs:** [002 Frames](../spec/002-Frames.md), [004 Views](../spec/004-Views.md). |
| [`reagent/login/`](reagent/login/) — `examples/login` | Single-feature scaffold — schema, events, subs, views, and a machine in one file for a typical login flow. Ships an auxiliary [Story showcase](reagent/login/README.md#how-to-run) (`:examples/login-with-stories`) enumerating every reachable login state. **Specs:** [005 StateMachines](../spec/005-StateMachines.md), [014 HTTPRequests](../spec/014-HTTPRequests.md), [010 Schemas](../spec/010-Schemas.md), [008 Testing](../spec/008-Testing.md). |
| [`reagent/todomvc/`](reagent/todomvc/README.md) — `examples/todomvc` | Canonical cross-framework todo app: localStorage persistence, editing, bulk actions, remaining count, and hash-routing filters. **Specs:** [002 Frames](../spec/002-Frames.md), [004 Views](../spec/004-Views.md), [012 Routing](../spec/012-Routing.md). |
| [`reagent/routing/`](reagent/routing/) — `examples/routing` | Three-page app: `reg-route`, `:rf.route/navigate`, anchor clicks via `:rf/url-requested`, and route-not-found handling. **Specs:** [012 Routing](../spec/012-Routing.md). |
| [`reagent/ssr/`](reagent/ssr/) — `examples/ssr` | Minimal SSR + hydration. JVM-runnable; the browser hydrates against a baked `<script id="__rf_payload">` block. **Specs:** [011 SSR](../spec/011-SSR.md), [004 Views](../spec/004-Views.md). |
| [`reagent/managed_http_counter/`](reagent/managed_http_counter/) — `examples/managed-http-counter` | A counter where each button issues a `:rf.http/managed` request: success, 4xx failure, retry-recover, and abort. The compact complement to RealWorld for Spec 014. **Specs:** [014 HTTPRequests](../spec/014-HTTPRequests.md), [Pattern-AsyncEffect](../spec/Pattern-AsyncEffect.md). |
| [`reagent/state_machine_walkthrough/`](reagent/state_machine_walkthrough/) — `examples/state-machine-walkthrough` | Runnable companion to [docs/machines/concepts.md](../docs/machines/concepts.md): the chapter's login flow as code, driving the canonical lockout scenario (three failures → `:locked-out`). **Specs:** [005 StateMachines](../spec/005-StateMachines.md), [014 HTTPRequests](../spec/014-HTTPRequests.md), [008 Testing](../spec/008-Testing.md). |
| [`reagent/nine_states/`](reagent/nine_states/README.md) — `examples/nine-states` | The nine canonical UI states (nothing / loading / empty / one / some / too many / incorrect / correct / done) for one domain. Ships an auxiliary [Story showcase](reagent/nine_states/README.md#how-to-run) (`:examples/nine-states-with-stories`). **Specs:** [Pattern-NineStates](../spec/Pattern-NineStates.md), [Pattern-RemoteData](../spec/Pattern-RemoteData.md), [Pattern-Forms](../spec/Pattern-Forms.md), [005 StateMachines](../spec/005-StateMachines.md). |
| [`reagent/boot/`](reagent/boot/README.md) — `examples/boot` | A single `:app/boot` machine owns initialisation (`:configuring` → `:loading-deps` → `:hydrating` → `:ready`) with `:spawn-all` fan-out for parallel deps and a `:failed` retry path. **Specs:** [Pattern-Boot](../spec/Pattern-Boot.md), [005 StateMachines](../spec/005-StateMachines.md), [014 HTTPRequests](../spec/014-HTTPRequests.md). |
| [`reagent/long_running_work/`](reagent/long_running_work/README.md) — `examples/long-running-work` | Declarative spawn-and-join via `:spawn-all` (parent + N workers); cooperative cancellation on every exit path; per-step progress as internal self-transitions; browser-tick yielding via `:after`. **Specs:** [Pattern-LongRunningWork](../spec/Pattern-LongRunningWork.md), [005 StateMachines](../spec/005-StateMachines.md). |
| [`reagent/websocket/`](reagent/websocket/README.md) — `examples/websocket` | A connection-lifecycle machine: hierarchical `:active` parenting connect / auth / connected, a `:spawn`d socket actor, `:after` backoff, `:always` offline-queue flush, connection-epoch staleness, request/reply correlation. In-process mock socket — no network. **Specs:** [Pattern-WebSocket](../spec/Pattern-WebSocket.md), [Pattern-StaleDetection](../spec/Pattern-StaleDetection.md), [005 StateMachines](../spec/005-StateMachines.md). |
| [`reagent/seven_guis/temperature/`](reagent/seven_guis/temperature/core.cljs) — `examples/temperature` | 7GUIs #2 — Temperature converter. Bidirectional derivations; one source of truth. **Specs:** [004 Views](../spec/004-Views.md), [006 ReactiveSubstrate](../spec/006-ReactiveSubstrate.md). |
| [`reagent/seven_guis/flight_booker/`](reagent/seven_guis/flight_booker/core.cljs) — `examples/flight-booker` | 7GUIs #3 — Flight booker. Form validation; layered subs deriving the Book button's enabled state. **Specs:** [004 Views](../spec/004-Views.md), [Pattern-Forms](../spec/Pattern-Forms.md). |
| [`reagent/seven_guis/timer/`](reagent/seven_guis/timer/core.cljs) — `examples/timer` | 7GUIs #4 — Timer. `:dispatch-later` periodic tick; controlled slider; one source of truth for elapsed time. **Specs:** [002 Frames](../spec/002-Frames.md), [004 Views](../spec/004-Views.md). |
| [`reagent/seven_guis/crud/`](reagent/seven_guis/crud/core.cljs) — `examples/crud` | 7GUIs #5 — CRUD. List operations (add / update / delete), selection-as-state, derived filtered list. **Specs:** [004 Views](../spec/004-Views.md). |
| [`reagent/seven_guis/circle_drawer/`](reagent/seven_guis/circle_drawer/core.cljs) — `examples/circle-drawer` | 7GUIs #6 — Circle drawer. Undo/redo via an interceptor that snapshots `:circles`; modal dialog as state. **Specs:** [004 Views](../spec/004-Views.md), [002 Frames](../spec/002-Frames.md). |
| [`reagent/seven_guis/cells/`](reagent/seven_guis/cells/core.cljs) — `examples/cells` | 7GUIs #7 — Cells. Formula evaluation; subscription-graph propagation; cycle detection; pure parser + evaluator. **Specs:** [006 ReactiveSubstrate](../spec/006-ReactiveSubstrate.md), [004 Views](../spec/004-Views.md). |
| [`reagent/realworld/`](reagent/realworld/README.md) — `examples/realworld` | [RealWorld (Conduit)](https://github.com/gothinkster/realworld) — the cross-framework benchmark. Auth (with an `auth-guard` interceptor), feeds, routing, comments, an editor with an `:editor/can-submit?` flow, profile, favorites, settings, and SSR-hydration glue. **Specs:** [014 HTTPRequests](../spec/014-HTTPRequests.md), [012 Routing](../spec/012-Routing.md), [013 Flows](../spec/013-Flows.md), [005 StateMachines](../spec/005-StateMachines.md), [011 SSR](../spec/011-SSR.md), [Pattern-RemoteData](../spec/Pattern-RemoteData.md), [Pattern-Forms](../spec/Pattern-Forms.md). |
| [`reagent/ssr_streaming/`](reagent/ssr_streaming/) — `examples/ssr-streaming` | Streaming SSR: a dashboard whose shell renders immediately, then each slow card streams as its fetch resolves. The `:rf/suspense-boundary` marker, per-card fallback, and interleaved hydration. **Specs:** [011 SSR §Streaming](../spec/011-SSR.md#streaming-ssr), [004 Views](../spec/004-Views.md). |
| [`reagent/notebook/`](reagent/notebook/) — `examples/notebook` | Three-pane editorial layout (documents tree · markdown editor · live preview). The design-led Reagent counterpart to `dashboard_uix/` and `process_monitor_helix/` — all three share the "Editorial Warm" identity from [`_shared/css/style.css`](_shared/css/style.css). **Specs:** [002 Frames](../spec/002-Frames.md), [004 Views](../spec/004-Views.md). |
| [`reagent/flows/`](reagent/flows/) — `examples/flows` | The canonical Flows exemplar: a cart whose subtotal + total are *materialised into app-db* by registered flows (not subs). A flow-reads-flow cascade, reading a flow inside an event handler, and a runtime-toggleable discount. **Specs:** [013 Flows](../spec/013-Flows.md). |
| [`reagent/resources/`](reagent/resources/README.md) — `examples/resources` | Declarative server-state as proper re-frame2: route-driven page load, event-driven ensure under a `[:lease …]` owner, manual refresh as a cause, and a machine-owned resource. Views read through passive `[:rf.resource/*]` subs. Runs live against a per-URL canned `:rf.http/managed` stub (no backend ships). **Specs:** [016 Resources](../spec/016-Resources.md), [014 HTTPRequests](../spec/014-HTTPRequests.md), [012 Routing](../spec/012-Routing.md), [005 StateMachines](../spec/005-StateMachines.md). |
| [`reagent/resources_ssr/`](reagent/resources_ssr/README.md) — `examples/resources-ssr` | Resource SSR preload + hydration: a request-local server frame preloads the page resource under an `[:ssr …]` owner, serialises the durable `:entries` projection into `:rf/runtime-db`, and the client hydrates without a double-fetch. The resource counterpart to `reagent/ssr/`. **Specs:** [016 Resources §SSR](../spec/016-Resources.md), [011 SSR](../spec/011-SSR.md). |
| [`reagent/realworld_resources/`](reagent/realworld_resources/README.md) — `examples/realworld-resources` | [RealWorld (Conduit)](https://github.com/gothinkster/realworld) on EP-0003 **resources + mutations** — the sibling of [`reagent/realworld/`](reagent/realworld/). Reads are `reg-resource` + route `:resources` metadata; writes are `reg-mutation` whose `:invalidates` / `:populates` drive the read→write→invalidate→refetch loop. The editor pairs a mutation with a Spec 013 `:editor/can-submit?` flow and an unsaved-changes `:can-leave` guard. Runs live. **Specs:** [016 Resources](../spec/016-Resources.md), [014 HTTPRequests](../spec/014-HTTPRequests.md), [012 Routing](../spec/012-Routing.md), [013 Flows](../spec/013-Flows.md), [005 StateMachines](../spec/005-StateMachines.md). |
| [`reagent/infinite_feed/`](reagent/infinite_feed/README.md) — `examples/infinite-feed` | A load-more / infinite-scroll feed as a first-class **infinite resource** (EP-0021): `reg-resource` with `:infinite true` + a pure `:next-page-param`, read through the passive `[:rf.resource/infinite-state …]` view-model, with accumulation driven by the causal `:rf.resource/load-more` event. No app-db list, no cursor threading. Runs live against a canned stub. **Specs:** [016 Resources §Infinite](../spec/016-Resources.md#infinite-resources-and-load-more-feeds), [EP-0021](../docs/EP/EP-0021-infinite-resources.md), [014 HTTPRequests](../spec/014-HTTPRequests.md), [012 Routing](../spec/012-Routing.md). |
| [`reagent/linearlite/`](reagent/linearlite/README.md) — `examples/linearlite` | The write-side flagship: a Linearlite-class issue board where every write — create / retitle / change-status — is a `reg-mutation` with an `:optimistic` patch. The board updates at phase 1.5, then commits on `:ok` or rolls back on `:error` (the runtime records the inverse). A "Fail the next write" toggle makes rollback the headline. No app-db issue list, no `:saving?` flag, no manual undo. Runs live against a canned stub. **Specs:** [016 Resources §Optimistic](../spec/016-Resources.md#optimistic-mutations), [EP-0019](../docs/EP/EP-0019-optimistic-mutation-rollback.md), [014 HTTPRequests](../spec/014-HTTPRequests.md), [012 Routing](../spec/012-Routing.md). |

> **Auxiliary Story showcases.** Two examples ship a Story showcase layered over the example itself: [`reagent/login/`](reagent/login/) (`:examples/login-with-stories`) and [`reagent/nine_states/`](reagent/nine_states/) (`:examples/nine-states-with-stories`) — a `stories.cljs` + `stories_host.cljs` + `stories.index.html` trio that sources the example's real machine and views and enumerates its states as Story variants. See each example's README for the run command.

For the 7GUIs cluster's own narrative, see [`reagent/seven_guis/README.md`](reagent/seven_guis/README.md).

## Reagent Slim

The `day8/reagent-slim` adapter ([`implementation/adapters/reagent-slim/`](../implementation/adapters/reagent-slim/)) is a ground-up `reagent2.*` rewrite. One example — the canonical counter on the slim substrate. If you've read the canonical counter you've read this one; the interest is in what the bundle does *not* contain.

| Example | What it demonstrates |
|---|---|
| [`reagent-slim/counter_slim_and_fast/`](reagent-slim/counter_slim_and_fast/) — `examples/counter-slim-and-fast` | The canonical counter dataflow mounted on `day8/reagent-slim` (every `reagent.*` import → `reagent2.*`; `rf/init!` takes the slim adapter Var). **Specs:** [006 ReactiveSubstrate](../spec/006-ReactiveSubstrate.md), [Conventions §Adapter test matrix](../spec/Conventions.md#adapter-test-matrix-policy). |

## UIx

UIx renders through React hooks. The curated pair — counter + login — shares its dataflow byte-for-byte with the Reagent siblings; only the view layer moves. Plus `dashboard_uix`, a design-led build.

| Example | What it demonstrates |
|---|---|
| [`uix/counter_uix/`](uix/counter_uix/) — `examples/counter-uix` | The Reagent [`counter/`](reagent/counter/) dataflow through UIx — same events, subs, and `app-db`; views are `defui` components consuming subs via the `use-subscribe` hook. |
| [`uix/login_uix/`](uix/login_uix/) — `examples/login-uix` | The Reagent [`login/`](reagent/login/) example through UIx — schemas, machine, and HTTP stub unchanged; only the view layer differs. |
| [`uix/dashboard_uix/`](uix/dashboard_uix/) — `examples/dashboard-uix` | Design-led: UIx driving a substantive multi-pane layout. Shares the "Editorial Warm" identity from [`_shared/css/style.css`](_shared/css/style.css) with `notebook/` and `process_monitor_helix/`. |

## Helix

Same story as UIx, a different hooks library. The curated pair is counter + login, plus the process-monitor design-led build. The component primitive is `defnc` (vs `defui`); the dataflow underneath is unchanged.

| Example | What it demonstrates |
|---|---|
| [`helix/counter_helix/`](helix/counter_helix/) — `examples/counter-helix` | The Reagent [`counter/`](reagent/counter/) dataflow through Helix — same events, subs, and `app-db`; views are `defnc` components consuming subs via the `use-subscribe` hook. |
| [`helix/login_helix/`](helix/login_helix/) — `examples/login-helix` | The Reagent [`login/`](reagent/login/) example through Helix — schemas, machine, and HTTP stub unchanged; only the view layer differs. |
| [`helix/process_monitor_helix/`](helix/process_monitor_helix/) — `examples/process-monitor-helix` | Design-led: Helix driving a substantive multi-pane layout. Shares the "Editorial Warm" identity from [`_shared/css/style.css`](_shared/css/style.css) with `notebook/` and `dashboard_uix/`. |
