# Examples map — when to point at which `examples/**`

> **Audience:** authors writing re-frame2 ClojureScript application code.
> **Use when:** a task lands on a pattern or a primitive whose shape needs to be cross-checked against a known-shipping worked example.

re-frame2's examples are the canonical authoring substrate — per SKILL.md cardinal rule 5, when an example exists for a pattern, **prefer the example's shape over a synthesised one**. Examples reflect the implementation as shipped; spec/EP docs describe *why*, the example *what*.

A one-paragraph-per-example index: what each demonstrates and when to point at it, naming the *patterns and primitives* it exercises so a routing decision lands on the right directory in one hop. It does **not** explain internals — read the source.

The full catalogue (with maturity, build ids, and end-to-end Playwright coverage) lives at [`examples/README.md`](../../examples/README.md). The substrate policy (Reagent is canonical; UIx ships a smoke-pair) lives at [`spec/Conventions.md` §Adapter shipping convention](../../spec/Conventions.md).

## counter — `examples/core/counter/`

The smallest possible re-frame2 app. Three `reg-event`s (an `:initialise` plus inc/dec), one `reg-sub`, two `reg-view` Vars, an `:initial-events` boot dispatch, and a single click. Point at this example when authoring the first event/sub/view of a greenfield feature, when verifying the canonical macro-shapes (`reg-event`, `reg-sub`, `reg-view` Form-1 with a Var reference), or when checking the minimum-viable `app-db` schema attachment. Exercises 002 Frames. The pedagogical "hello world" — its shape sets the bar for every other example.

## flows — `examples/core/flows/`

The canonical Flows exemplar (Spec 013) — a three-line-item shopping cart whose subtotal and total are **materialised into `app-db` by registered flows**, not derived in subs. Shows the three declarations a flow makes (`:inputs`, a pure `:derive`, an `:output-path`), a flow-reads-flow cascade (total reads subtotal), reading a flow's output from inside an event handler as plain data, and a runtime-toggleable discount. Point at this example when the question is "should this derived value be a sub or a flow?" — a flow's result lives in `app-db`, so it comes back under time-travel and survives the wire, where a sub's result stays in the view-facing cache. Exercises 013 Flows. [`references/fundamentals/flows.md`](references/fundamentals/flows.md) cites this example's `core.cljs` as its mini-example source.

## counter_slim_and_fast — `examples/substrates/reagent_slim/counter/`

The counter dataflow mounted on the slim Reagent rewrite (`day8/reagent-slim`) — the `reagent2.*` substrate that excludes `reagent.impl.*` and `react-dom/server`. Same six-domino dataflow as `counter/`, but every Reagent import points at `reagent2.*` and `(rf/init!)` takes the slim adapter Var. Point at this fixture only when the task is about substrate-swap, the adapter-owned bundle-isolation contract (`check-reagent-slim-bundle-isolation.cjs`), or proving that the slim adapter is API-shape-compatible with the stock Reagent adapter. It is not a human-facing teaching example; it is the live isolation fixture for the slim epic.

## counter_with_stories — `tools/story/testbeds/counter_with_stories/`

The canonical worked example / testbed for the Story epic (`tools/story/`, the `day8/re-frame2-story` artefact). Tool-owned testbed: it lives alongside the tool it exercises rather than under `examples/`. The counter with seven `reg-*` Story macros wired end-to-end — `reg-tag`, `reg-mode`, `reg-decorator`, `reg-story-panel`, `reg-story`, `reg-variant`, `reg-workspace` — thirty variants exercising five of the canonical `:rf.assert/*` events (`caused`, `dispatched`, `effect-emitted`, `no-cascade-rerender`, `sub-equals`) plus the built-in `force-fx-stub` decorator. URL-hash-routed: `#/` renders the live counter; `#/stories` mounts the Story playground shell. Point at this example when authoring any Story-substrate code: tag definitions, modes, decorators, variants, workspaces, or the `:rf.assert/*` family. The Stage 8 worked example for the Story epic; exercises 007 Stories, 002 Frames, and 008 Testing.

## boot — `examples/patterns/boot/`

The canonical Pattern-Boot worked example — a four-state `:app/boot` machine (`:configuring → :loading-deps → :hydrating → :ready`, plus terminal `:failed`) that drives the initialisation graph. `:configuring` `:spawn`s one reusable `:boot/loader` child for `/config`; `:loading-deps` fans out THREE parallel `:boot/loader` children via `:spawn-all` for routes / flags / user; `:hydrating` applies the staged payloads to top-level app-db slices via one consolidated `:enter-hydrating` action and self-transitions to `:ready`. Point at this example when authoring a multi-step boot sequence, when verifying the canonical `:spawn` + `:spawn-all` composition, or when wiring boundary schemas for hydration payloads. Exercises Pattern-Boot, 005 StateMachines, and 010 Schemas. The single-purpose narrower instance lives at `examples/core/login/`.

## login — `examples/core/login/`

The single-feature scaffold: everything a typical login flow needs, in one file. Events + subs + views + a state machine + a managed-HTTP demo stub + Malli validation on the machine's `[:schemas :data]` (machine snapshots live in runtime-db, so there is no app-db schema here) + a Story variant surface in `stories.cljs`. Point at this example when authoring **any** feature that combines a state machine with HTTP, or when verifying the shapes of `:auth/busy`, `:auth/authenticated`, and other `:tags`-based view queries (the canonical replacement for boolean-discriminator subs). The canonical home of Pattern-Forms; also the canonical CP-5 / CP-6 / CP-8 worked example. Exercises 005 StateMachines, 014 HTTPRequests, 010 Schemas, and 007 Stories. If you only read one machine-based example, read this one.

## managed_http_counter — `examples/core/managed_http_counter/`

A compact Spec 014 demo — a counter where each button issues a `:rf.http/managed` request: success, 4xx failure, retry-recover (canned-stub), and abort. Includes a tiny `/api/` directory served as canned JSON so the example runs without a backend. Point at this example when verifying the canonical shape of an `:rf.http/managed` call, the eight-category `:rf.http/*` failure taxonomy, the retry-with-backoff configuration, the abort-token wiring, or the encode/decode pipeline. The compact, single-feature complement to RealWorld for Spec 014; the canonical Pattern-ManagedHTTP example. Exercises 014 HTTPRequests and Pattern-AsyncEffect.

## nine_states — `examples/patterns/nine_states/`

The nine canonical UI states (Nothing / Loading / Empty / One / Some / Too Many / Incorrect / Correct / Done) for a single domain. One parallel-region machine with three orthogonal regions (data / form / mode), state tags on every state, a render-priority selector sub, and a single `case` in the root view. Pedagogically exhaustive — exercises every machine grammar concept (parallel regions, tags, guards, actions, `:always`, `:after`) inside one focused example. Point at this example when authoring **any** parallel-region machine, or when designing a page that needs to render every legal lifecycle state distinctly. The canonical Pattern-NineStates example; the worked reference for parallel-region tagging and render-priority collapsing. Exercises Pattern-NineStates, Pattern-RemoteData, Pattern-Forms, and 005 StateMachines.

## realworld — `examples/real-apps/realworld_http/`

The de-facto cross-framework benchmark — [RealWorld (Conduit)](https://github.com/gothinkster/realworld). The widest API-surface example in the repo: auth, feeds, routing, comments, editor, profile, favorites, settings, and SSR-hydration glue all sketched on the current API surface. Maturity is **worked scaffold** — it covers breadth, not depth. Point at this example when verifying how the conventions hold up across many features composed in one app (feature-prefix discipline, schema attachment at HTTP boundaries, route-driven data loads, the SSR `:rf/hydrate` client bootstrap). Not a teaching example — read individual files (`auth.cljs`, `articles.cljs`, `routing.cljs`) for the relevant cross-cutting shape. Exercises 014 HTTPRequests, 012 Routing, 005 StateMachines, 011 SSR, Pattern-RemoteData, and Pattern-Forms.

## resources — `examples/capabilities/resources/resources/`

The focused **read-side** Pattern-Resources example for the optional `day8/re-frame2-resources` artefact (Spec 016). Route-driven page load via the route's `:resources` metadata, event-driven `:rf.resource/ensure` under an app-minted event owner (`[:dashboard/opened …]`-style) with a release path, a manual refresh wired as a *cause* (not an owner), and a machine-owned resource — all read through passive `[:rf/resource]` (and the sibling single-fact `[:rf.resource/*]`) subscriptions, with scope as the fail-closed leak boundary (an explicit `:rf.scope/global` claim). Point at this example when authoring the read half of a server-state cache — `reg-resource`, ensure/refetch/release-owner, owners-vs-causes, fresh-skip/staleness — without the write side. Exercises Pattern-Resources, 016 Resources, 014 HTTPRequests, and 012 Routing. The focused complement to the full `realworld_resources/` dogfood; read it for the lifecycle shapes, not for mutations.

## realworld_resources — `examples/real-apps/realworld_resources/`

RealWorld (Conduit) rebuilt on resources + mutations end-to-end — the write-side counterpart the focused `resources/` example omits. It exercises the completion surface the [`patterns/resources-mutations.md`](patterns/resources-mutations.md) leaf teaches: `reg-mutation` writes, call-site **`:reply-to`** continuations (settings save, article create/edit/delete, the social controls), **per-target scoped invalidation descriptors** (one favourite/save stales the viewer-scoped article tags *and* the session-scoped `[:feed]` in one mutation), **populate-as-authoritative-load** (`:populates` returning the canonical `{target value}` map form), and — the sharpest lesson — the **viewer-representation scope** boundary. This is the canonical demonstration that **"public" is an access policy, not a cache-identity proof**: Conduit's article/profile reads allow anonymous access but embed the *current viewer's* `favorited` / `following` flags, so they are keyed by a named `reg-resource-scope :realworld/viewer` resolver (`[:rf.scope/viewer {:username …}]` / `[:rf.scope/viewer :anonymous]` / nil-fail-closed-while-restoring), NOT `:rf.scope/global` — only the truly-invariant popular-tags read stays global, only the private feed is `:realworld/session`. The viewer resolver, the six reads' `:scope`, the mutation descriptors, logout's dual `clear-scope`, and the cold-boot re-plan (the framework's `[:rf.route/replan-resources {:cause …}]`, both restore outcomes — there is no app-level re-plan event) all reference the same two `{:from-db …}` names. Point at this example when authoring **any** resource mutation, mixed-scope invalidation, post-write continuation, optional-auth cache identity, or named-scope-resolver wiring. The sibling of the managed-HTTP `examples/real-apps/realworld_http/` (kept intact as the Spec 014 counterpart) — read the two side by side to see what resources buy you. Exercises Pattern-Resources, 016 Resources, 014 HTTPRequests, 012 Routing, and Pattern-Forms.

## infinite_feed — `examples/capabilities/resources/infinite_feed/`

The load-more / infinite-scroll feed as a first-class **infinite resource** (EP-0021) — `reg-resource` with `:infinite true` plus a pure `:next-page-param`, read through the passive `[:rf.resource/infinite-state …]` view-model, with accumulation driven by the causal `:rf.resource/load-more` event. The lesson is what is *absent*: no app-db list, no cursor threading, no hand-rolled page accumulator — the view reads a list and renders it, and the paging lives in the runtime. Runs live against a canned stub. Point at this example when authoring any paged or infinite feed. Exercises 016 Resources §Infinite, 014 HTTPRequests, and 012 Routing.

## linearlite — `examples/capabilities/resources/linearlite/`

The **write-side flagship** for optimistic mutations (EP-0019) — a Linearlite-class issue board where create / retitle / change-status are each a `reg-mutation` carrying an `:optimistic` patch. The board updates at phase 1.5, then commits on `:ok` or rolls back on `:error` using the inverse the runtime recorded; a "Fail the next write" toggle makes rollback the headline behaviour. Again the lesson is the absence: no app-db issue list, no `:saving?` flag, no hand-written undo. Runs live against a canned stub. Point at this example when authoring optimistic writes, rollback, or a board/list whose mutations must land instantly. Exercises 016 Resources §Optimistic, 014 HTTPRequests, and 012 Routing. Read it beside `realworld_resources/` — that one shows mutations composed across a whole app, this one shows the rollback contract in isolation.

## routing — `examples/capabilities/routing/routing/`

The three-page worked example for Spec 012 — `reg-route`, `:rf.route/navigate`, anchor clicks via `:rf.route/url-requested`, route-not-found handling, and the `:can-leave?` guard. The CP-7 worked example. Point at this example when authoring routes, navigating between them, gating navigation with `:can-leave?`, or wiring an anchor's `href` to dispatch a navigation event instead of a browser-default page load. Exercises 012 Routing. Compact and single-purpose; the canonical home of the routing primitives.

## ssr — `examples/capabilities/ssr/ssr/`

The CP-9 worked example for Spec 011 — minimal SSR + hydration walkthrough. JVM-runnable; the browser side hydrates against a baked `<script id="__rf_payload">` block in the static `index.html` (standing in for a real Clojure server in front). Point at this example when authoring server-rendered views, `:rf/server-init` events, the hydration payload shape, or the SSR-vs-hydration parity check. Exercises 011 SSR. The smallest possible SSR demo — read it alongside `realworld/ssr.cljc` for the broader scaffold.

## resources_ssr — `examples/capabilities/ssr/resources_ssr/`

Resource SSR preload + hydration — the resource counterpart to the minimal `ssr/` walkthrough. A request-local server frame preloads the page's resource under an `[:ssr …]` owner, serialises the durable `:entries` projection into `:rf/runtime-db`, and the client hydrates from it **without a double-fetch**: the articles are on screen at first paint and the browser does not go back for them. The idea worth taking away is that the hydrated list is not a frozen server value but a cache the client rebuilds. Single `core.cljc`; `index.html` ships a pre-baked payload so no running Clojure server is needed. Point at this example when authoring SSR for a page whose data is a resource. Exercises 016 Resources §SSR and 011 SSR.

## state_machine_walkthrough — `examples/capabilities/machines/state_machine_walkthrough/`

The runnable companion to `docs/machines/concepts.md` — the guide chapter's login flow rendered as live code, driving the canonical lockout scenario (three failures → `:locked-out`) in the browser. Four files, no tests: examples are test-free by ruling, so read it as a worked shape, not as a coverage source. Point at this example when teaching the machine grammar from scratch, when verifying the chapter's worked shape against the live implementation, or when authoring an event-driven sequence of FSM transitions. Exercises 005 StateMachines and 014 HTTPRequests. Pedagogical sibling of `login/` — same domain, different aim (`login/` is the "single-feature scaffold"; this is the chapter's walkthrough).

## todomvc — `examples/core/todomvc/`

The canonical cross-framework benchmark — persistence (localStorage), in-place editing, bulk actions (mark-all-done, clear-completed), remaining-count derivation, and hash-routing filters (`#/`, `#/active`, `#/completed`). Point at this example when verifying a slice-shaped feature with a list of items, a derivation-heavy subscription graph (the filtered list, the remaining count, the all-completed flag), an fx/cofx-based localStorage persistence pattern (`reg-fx :todo.storage/save` on the write side, a recordable `reg-cofx` on the read side — no interceptor), or the integration of `reg-route` with a list-filtering view. Exercises 002 Frames and 012 Routing. The classic shape benchmark; if a feature looks like "manage a list with filters", this is the shape reference.

## 7GUIs — `examples/core/seven_guis/`

A cluster of six small benchmark apps from the [7GUIs](https://eugenkiss.github.io/7guis/) suite — `temperature/`, `flight_booker/`, `timer/`, `crud/`, `circle_drawer/`, `cells/`. Each app is a focused stress on one shape: bidirectional derivations (`temperature`), form-validity-driven button enablement (`flight_booker`), `:dispatch-later` periodic ticks (`timer`), list-CRUD with selection-as-state (`crud`), undo/redo via a snapshot-on-write interceptor and modal-as-state (`circle_drawer`), and a full formula-graph subscription substrate with cycle detection (`cells`). Point at the 7GUIs cluster when picking the right shape for a small focused concern: a controlled input pair, a Book-button-enables-only-when-valid flow, a periodic-tick UI, list operations with selection, undo/redo, or formula-driven cell propagation. Exercises 002 Frames, 006 ReactiveSubstrate, and Pattern-Forms. See `examples/core/seven_guis/README.md` for the cluster's own narrative.

## websocket — `examples/patterns/websocket/`

The canonical Pattern-WebSocket worked example — a connection lifecycle machine where a hierarchical compound `:active` state parents `:connecting` / `:authenticating` / `:connected` and owns a `:spawn`d socket actor whose lifetime spans all three child leaves; `:reconnecting` rides `:after` exponential backoff; `:always` flushes the offline send-queue on `:connected`; `:fsm/tags` carry queryable connection-state predicates for the view; the live `:socket-id` doubles as the connection epoch for staleness checks; request/reply correlation is wired end-to-end. Runs against an in-process mock WebSocket — no network needed. Point at this example when authoring any long-lived-connection feature, when verifying the `:spawn`-at-parent-level lifetime idiom, the connection-epoch staleness pattern, or `:after`-backoff reconnection. Source spans `connection.cljs` (the machine), `messages.cljs`, `schema.cljs`, and `views.cljs`. Exercises Pattern-WebSocket, Pattern-StaleDetection, and 005 StateMachines. The worked-source companion to `patterns/websocket.md`.

## long_running_work — `examples/patterns/long_running_work/`

The canonical Pattern-LongRunningWork worked example — a `:work/flow` parent coordinator spawns N `:work/processor` worker children via `:spawn-all` and joins on `:all`; each child processes its shard in chunks, yielding between chunks via `:after` so the browser stays responsive, and dispatches a `:progress` event back to the parent on every chunk (the parent's internal self-transition updates `:data :progress`, which the `:work/progress-fraction` sub recomputes). Cooperative cancellation is uniform: every exit path (user `:cancel`, `:on-all-complete`, frame destroy, `:after`) fires one `:rf.machine/destroy` whose cascade tears down every in-flight child timer/request. Point at this example when authoring a CPU-bound batch job, a parent/child fan-out-and-join via `:spawn-all`, progress reporting through `:data`, or the destroy-cascade cancellation contract. Source: `worker.cljs` (the `:work/flow` + `:work/processor` machines), `core.cljs`, `schema.cljs`, `views.cljs`. Exercises Pattern-LongRunningWork and 005 StateMachines. The worked-source companion to `patterns/long-running-work.md`.

## ssr_streaming — `examples/capabilities/ssr/ssr_streaming/`

The streaming-SSR worked example for [Spec 011 §Streaming](../../spec/011-SSR.md#streaming-ssr) — a dashboard with three slow cards where the page shell + header render immediately on the server, then each card streams its content as its own data fetch resolves. Demonstrates the `:rf/suspense-boundary` hiccup marker, per-card fallback hiccup, inline-fallback failure semantics, and interleaved per-subtree hydration. Point at this example when authoring streaming server-rendered views, suspense boundaries, or per-subtree hydration. Lives in a single `core.cljc` (cross-platform JVM/browser). Exercises 011 SSR §Streaming. The streaming complement to the minimal `ssr/` walkthrough.

## notebook — `examples/core/notebook/`

The design-led Reagent example — a three-pane editorial layout (documents tree · markdown editor · live preview) that proves the substrate drives a substantive multi-pane UI. The design-led counterpart to `examples/substrates/uix/dashboard/`; the two share the "Editorial Warm" identity from `examples/_shared/css/style.css`. A tiny pure-CLJS markdown parser keeps the bundle small. Point at this example when authoring a multi-pane layout, a master-detail editor shape, or when verifying the shared design-system identity across substrates. Single `core.cljs`. Exercises 002 Frames. Not a pattern-teaching example — read it for layout/identity shape, not for a primitive.

## Adapter smoke-pairs

Per [`spec/Conventions.md` §Adapter shipping convention](../../spec/Conventions.md#adapter-shipping-convention), the UIx substrate ships a curated set rather than a 1:1 mirror of the Reagent set: `examples/substrates/uix/counter/` and `examples/substrates/uix/login/` mirror their `core/` siblings, and `examples/substrates/uix/dashboard/` is the design-led multi-pane layout that shares the "Editorial Warm" identity with `core/notebook/`. Point at these only when authoring against UIx specifically. The dataflow is identical to the Reagent siblings; only the view layer differs (`defui` plus the `use-subscribe` hook).

The Hicasso substrate ships one: `examples/substrates/hicasso/login/` — the `core/login/` app over the identical `login.model`, with the views rewritten as `h/defview` boundaries that read with `h/sub` and state their handlers as data (`{:on-change [:auth.login/edit-field :email ::h/value]}`). Point at it to see what the same app looks like in re-frame2's own view layer. **Authoring Hicasso views is not this skill's surface** — the porting verbs and judgment calls live in the [`reagent-migration`](../reagent-migration) skill (see [`references/fundamentals/views.md`](references/fundamentals/views.md) §Hicasso).

## How to use this map during an authoring task

1. Pick the primary pattern from [`decision-trees/pick-a-pattern.md`](decision-trees/pick-a-pattern.md).
2. Pick the state shape from [`decision-trees/slice-or-machine.md`](decision-trees/slice-or-machine.md).
3. Find the example above whose paragraph names the same pattern + shape combination.
4. Read the example's source — match its shape; do not re-derive (per SKILL.md cardinal rule 2).
5. If the example contradicts the pattern leaf, **the example wins** (per SKILL.md cardinal rule 1).

## Cross-references

- [`SKILL.md`](SKILL.md) — router skill; cardinal rules; loading map.
- [`examples/README.md`](../../examples/README.md) — the full example catalogue with maturity, build ids, and end-to-end coverage.
- [`spec/Conventions.md` §Adapter shipping convention](../../spec/Conventions.md) — Reagent-canonical / UIx-smoke-pair policy.
- [`patterns/`](patterns) — pattern leaves; each names the worked example for its pattern.

---

*Derived from `examples/**` and `examples/README.md` @ main. Re-verify whenever a new worked example lands.*
