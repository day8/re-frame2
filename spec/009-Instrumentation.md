# Spec 009 — Instrumentation, Tracing, and Performance Integration

> The trace event stream is a **pattern-level** primitive — every implementation supplies a structured trace stream from well-defined points in the runtime. Trace events are open maps with stable required keys, consistent with the open-maps-with-schemas principle. The CLJS-specific bit — `goog-define` for production elision via `re-frame.interop/debug-enabled?` — is a reference-implementation detail. Other-language implementations resolve elision and listener delivery differently.
>
> For where the trace bus sits in relation to the runtime's other components (registrar, drain loop, sub-cache, substrate adapter), see [Runtime-Architecture](Runtime-Architecture.md).

## Abstract

re-frame2 emits a stream of **trace events** describing what's happening at runtime — dispatches, interceptor steps, effect handler calls, subscription updates, frame lifecycle, machine transitions. Tools subscribe to this stream.

The tracing surface is designed to be **stable** (required fields don't change), **extensible** (open maps; new fields are additive), **cheap on the hot path** (near-zero overhead with no listeners), and **cross-platform** (JVM-runnable for the data).

**All tracing is compile-time eliminated in production builds.** No exceptions. Production binaries contain zero trace code. Tracing is a dev-time concern only.

## The trace event model

A trace event is an immutable map describing one moment of work in the runtime — an event dispatch, a sub recomputation, a render, an fx invocation, a machine transition. Events flow into a single per-application trace stream; listeners receive each event synchronously, one at a time.

The shape is documented below.

### Core fields (required on every event)

```clojure
{:id        <int>            ;; auto-incrementing trace id; unique per process
 :operation <kw>              ;; what's being traced — namespaced keyword identifying
                              ;;   the emit site (e.g. :rf.event/dispatched, :rf.machine/transition,
                              ;;   :rf.error/no-such-sub). The event-id / sub-id / fx-id
                              ;;   that motivates the emit rides under :tags. Per
                              ;;   Spec-Schemas §:rf/trace-event.
 :op-type   <kw>             ;; discriminator: :rf.event, :rf.sub, :rf.fx, :rf.view,
                              ;;   :rf.frame, :rf.machine, :error, :warning, etc.
                              ;;   The full vocabulary is enumerated in §:op-type vocabulary
                              ;;   below and in Spec-Schemas §:rf/trace-event.
 :time      <ms>             ;; emit timestamp (host clock)
 :tags      {...}}           ;; open-ended bag for op-type-specific fields
```

The runtime emits each trace event at the *moment of interest* with the host clock time captured in `:time`. The shape is **event-at-a-time**, not span-shaped: there is no separate start/end pair, no `:duration`, and no `:child-of` parent-id. Tools that need cascade correlation use the dispatch-id correlation fields documented under [§Dispatch correlation](#dispatch-correlation-rftracedispatch-id--rftraceparent-dispatch-id) instead.

**`:op-type` versus `:operation`.** `:op-type` is the discriminator a consumer branches on — a small, stable vocabulary of ~20 values (enumerated in [§`:op-type` vocabulary](#op-type-vocabulary) below and in [Spec-Schemas §`:rf/trace-event`](Spec-Schemas.md#rftrace-event)). Tools route on `:op-type` to subscribe to a slice (e.g. `:rf.event`, `:rf.sub`, `:error`). `:operation` is the specific identity of the emit site within that slice — typically a namespaced keyword like `:rf.event/dispatched`, `:rf.machine/transition`, or `:rf.error/no-such-sub`. A consumer subscribing to all errors filters `:op-type :error`; a consumer hunting one category branches further on `:operation`.

### Re-frame2 additions (additive, optional)

```clojure
{:source   :ui              ;; :ui, :timer, :http, :machine, :repl — origin of the trigger
 :recovery :no-recovery}    ;; recovery disposition for error-shaped events
```

`:source` is hoisted to the top level of every event whose tags carry it; `:recovery` is hoisted on the error path. Both are top-level, not under `:tags`. The `:frame` field — present on most events — rides under `:tags` (every emit site that knows the frame includes it there). Tools that filter by frame read `(get-in ev [:tags :frame])`.

### Dispatch correlation: `:rf.trace/dispatch-id` / `:rf.trace/parent-dispatch-id`

Pair-shaped tools and per-event diagnostics need to correlate the *cascade* a dispatch belongs to — "this trace event fired inside the cascade started by *that* dispatch." The runtime maintains two distinct correlation channels. They are **cross-cutting** — stamped across every domino family — so they live under the trace-channel namespace `:rf.trace/*`, not under any single domino's `:rf.<family>/*` (per [Conventions §`:rf.trace/*`](Conventions.md#the-single-root-reserved-set)):

```clojure
{:tags {:rf.trace/dispatch-id        <uuid-or-counter>   ;; the cascade this event belongs to
        :rf.trace/parent-dispatch-id <uuid-or-counter>}  ;; on :rf.event/dispatched only — the cascade
                                                         ;; that caused THIS dispatch
 ...}
```

Semantics:

- **`:rf.trace/dispatch-id` is per dequeued event — the cascade of one event, not the whole drain.** It is allocated by the runtime when a dispatch is enqueued (before routing) — once per `dispatch` call, so a UI `dispatch`, each `:fx [[:dispatch …]]` child, and the frame-creation `:on-create` event each receive their own — and rides on **every** trace event emitted *inside that one event's* six-domino cascade — `:rf.event/dispatched` itself, `:rf.event/db-changed`, `:rf.fx/handled`, `:rf.sub/run`, `:rf.machine/transition`, `:rf.flow/*`, every `:rf.error/*`, and any future op-type the runtime adds. It does **not** span sibling events that merely happen to drain in the same turn: when a handler `:fx`-dispatches a child, the child is a separate dequeued event with its own `:rf.trace/dispatch-id` (and its `:rf.trace/parent-dispatch-id` points back at the parent — see below). The `:rf.trace/dispatch-id` is therefore the trace-stream face of the **epoch unit**: one `:rf.trace/dispatch-id` = one dequeued event = one [`:rf/epoch-record`](Spec-Schemas.md#rfepoch-record) (per [002 §Drain versus event](002-Frames.md#drain-versus-event--the-epoch-unit)). A machine's `:raise` sub-events and `:always` microsteps are **not** separate dequeues — they are in-memory microsteps inside the triggering event's macrostep (per [005 §Drain semantics](005-StateMachines.md#drain-semantics)), so every trace they emit carries the **triggering event's** `:rf.trace/dispatch-id` and rides its epoch; they do not allocate a new one. Consumers (Story `group-cascades`, Xray's causality graph, re-frame2-pair's `cascade-of`, schema-timeline correlation) group raw trace events by `:rf.trace/dispatch-id` directly — no inference from sequence required. The runtime carries the in-flight cascade's id through the `:rf.trace/dispatch-id` slot of the handler-scope record (`re-frame.trace/*handler-scope*`, per [§Handler-scope](#handler-scope-the-in-scope-reading-at-emit-time)), bound by `router.cljc` around each event's processing; `emit!` reads the slot and merges it into the event's `:tags` when bound and not already present. Implementations may use a process-monotonic counter, a UUID, or any opaque value with the same uniqueness contract: distinct within a single process for the lifetime of the trace surface. Tools treat it as opaque. Trace events emitted **outside** any in-flight cascade (handler registration before any dispatch, REPL evals that don't dispatch) carry no `:rf.trace/dispatch-id`. (The frame-creation initial event is itself a dequeued event and carries its own `:rf.trace/dispatch-id`; only emits genuinely outside any event — registry-time, REPL — go uncorrelated.)
- **`:rf.trace/parent-dispatch-id` is scoped to `:rf.event/dispatched` only.** It documents *cascade-from-cascade lineage* — "this dispatch was emitted as a side-effect of another event's processing" — which is a per-event-dispatch fact, not a per-trace-event fact. Concretely: when an `fx` handler running inside the do-fx phase of dispatch *D₁* invokes `(rf/dispatch ...)`, the runtime records the new dispatch's `:rf.trace/parent-dispatch-id` as *D₁*'s `:rf.trace/dispatch-id` on the new dispatch's `:rf.event/dispatched` event. If the dispatch was initiated outside any in-flight event (a timer, a UI handler, the REPL, the SSR boot path), `:rf.trace/parent-dispatch-id` is absent from `:rf.event/dispatched`. Non-`:rf.event/dispatched` trace events never carry `:rf.trace/parent-dispatch-id` — they belong to a single cascade (their `:rf.trace/dispatch-id`) and the inter-cascade lineage hangs off the cascade root.
- **Top-level dispatch.** An `:rf.event/dispatched` event with no `:rf.trace/parent-dispatch-id` is a *root* of a cascade. Pair-shaped tools draw cascade trees by walking `:rf.trace/parent-dispatch-id` upward across `:rf.event/dispatched` events; the per-cascade body (every other trace event in that cascade) is the slice of the trace stream sharing the cascade's `:rf.trace/dispatch-id`.
- **The cascade-correlation primitive.** Because the runtime emits event-at-a-time (no `:child-of` span field), `:rf.trace/dispatch-id` is the only intra-cascade correlation channel and `:rf.trace/parent-dispatch-id` is the only inter-cascade correlation channel. The pair lets tools both (a) group raw spans by cascade and (b) walk lineage between cascades, without consulting the `:rf/epoch-record` projection. Tools that prefer structured per-cascade slices read the assembled `:rf/epoch-record` (per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel)) — the raw `:rf.trace/dispatch-id` channel is the lower-level primitive.
- **Production elision.** Both fields ride the trace stream and are elided in production with the rest of the trace surface. The dispatch-id allocation counter and the `*handler-scope*` Var read sit inside the `interop/debug-enabled?` gate in `emit!`, so the whole machinery compiles out.

Tools consume these two channels to build cascade views: "show me every fx that ran in this cascade" is a filter on `:rf.trace/dispatch-id` over the raw stream; "show me all dispatches descended from `[:user/login ...]`" is a transitive walk over `:rf.trace/parent-dispatch-id` across `:rf.event/dispatched` events.

### Origin tagging: `:rf.event/origin`

When a tool (the pair tool, a story runner, the REPL, the SSR boot path) needs its own dispatches distinguishable from application dispatches, it can tag them with an `:origin` opt at dispatch time (per [002 §Dispatch origin tagging](002-Frames.md#dispatch-origin-tagging)). The runtime lifts the value onto every `:rf.event/dispatched` trace event under `:tags :rf.event/origin` (the dispatch *opt* keeps its ergonomic short name `:origin`; the trace *tag* it lifts onto is namespaced to the event family):

```clojure
{:tags {:rf.event/origin :pair        ;; tag set by the dispatching tool; default :app
        :rf.trace/dispatch-id ...
        ...}
 ...}
```

`:rf.event/origin` is unconstrained at the framework level — tools and applications agree on values (`:pair`, `:claude`, `:story`, `:test`, etc.). The default is `:app`. User application code typically omits the opt; tool surfaces set it so post-mortem filters like "show me only the dispatches I (the pair tool) issued during this session" become a one-key filter on the trace stream.

`:rf.event/origin` is **distinct from `:source`**: `:source` describes the *trigger kind* (`:ui` / `:frame-init` / `:fx` / `:machine` / `:machine-spawn` / `:always` / `:after-timer` / `:fx-dispatch` / `:fx-dispatch-later` / `:dispatch-later` / `:timer` / `:http` / `:repl` / `:ssr-hydration` / `:test` / `:unknown` / `:other`) and is essentially a "what woke the runtime?" axis; `:rf.event/origin` describes the *actor identity* (which tool or app subsystem emitted the dispatch) and is used for filtering. Tools may set both. The default `:source` is `:unknown` per [rf2-hxj0d](https://github.com/day8/re-frame2/issues/rf2-hxj0d) (previously `:ui`); substrate-internal dispatch sites (machine `:after` timer, machine spawn fx, `:dispatch` / `:dispatch-later` fx) stamp the matching specific value per [rf2-ejtpd](https://github.com/day8/re-frame2/issues/rf2-ejtpd). See [Spec-Schemas §`:rf/dispatch-envelope`](Spec-Schemas.md#rfdispatch-envelope) for the canonical enum.

### Dispatch-origin tagging: `:rf/dispatch-origin`

Every `:rf.event/dispatched` trace event carries a closed-enum classifier under `:tags :rf/dispatch-origin` answering the question **"where, functionally, did this dispatch come from?"** Per the Xray A.5 taxonomy (and surfaced as the L2 epoch-timeline row prefix + Event-panel step 1 — see [tools/xray/spec/021 §1.5](../tools/xray/spec/021-Dynamic-Panel-Designs.md)):

| Value | Set by | Meaning |
|---|---|---|
| `:user` | default (the macro / fn-form path supplies nothing else) | A user-emitted dispatch — a UI handler, a REPL call, a test body that did not opt into another tag. |
| `:router` | `re-frame.routing` internal dispatches (the routing-error emit, `:route/link` click handler) | A routing-substrate dispatch — URL events, route-link clicks, on-match-error cascades. |
| `:websocket` | application-level websocket adapters (the framework does not ship a websocket adapter) | A websocket-frame-arrived dispatch. The taxonomy slot is reserved; apps that wire websockets opt in via `{:rf/dispatch-origin :websocket}`. |
| `:http` | `re-frame.http_encoding/dispatch-reply-via-late-bind!` (managed-request reply path) | An HTTP managed-request reply dispatch — `:on-success` / `:on-failure` cascade entry. |
| `:ssr` | the user's hydration boot site (the framework does not auto-detect — see Spec 011) | The `:rf/hydrate` cascade or any other SSR-boot-time dispatch. |
| `:fx-emit` | `re-frame.fx`'s `:dispatch` / `:dispatch-later` fx handlers (override-on-cascade — child dispatches are tagged `:fx-emit` regardless of parent's origin) | A dispatch emitted by the do-fx phase of another event handler. Lineage is preserved via `:rf.trace/parent-dispatch-id`; the origin tag answers "who emitted THIS dispatch?" |
| `:timer` | `re-frame.machines.timer`'s `:after` fire site | A state-machine `:after` timer firing. (Application-level `setTimeout` / `setInterval` callers self-tag; the framework does not detect.) |
| `:test-harness` | test fixtures that opt in (the framework does not auto-detect; see Spec 015 / `re-frame.test_support`) | Reserved slot for test bodies that want their dispatches discriminated. Application-level opt-in. |
| `:tool` | tooling adapters (Xray controls, Story play scripts, the pair-MCP write surface) — they self-tag at their dispatch site | A tool-issued dispatch (operator clicking "step", a story-script driving the app). |
| `:internal` | `re-frame.machines.lifecycle_fx/spawn` (`:start` event into newly-spawned actors) and other framework-internal lifecycle dispatches | A framework-substrate-internal dispatch (actor bootstrap, lifecycle cascade). |

The default — for both the macro form (`(rf/dispatch event)` / `(rf/dispatch-sync event)`) and the fn form (`(rf/dispatch* event)`) — is `:user`. Internal callers thread `:rf/dispatch-origin` into the opts map at their emit site to override the default.

```clojure
;; user surface (default :user)
(rf/dispatch [:cart/add {:sku "abc"}])

;; explicit opt-in (per-call)
(rf/dispatch [:order/submit] {:rf/dispatch-origin :tool})
```

`:rf/dispatch-origin` is **distinct from `:source` and `:origin`**:
- `:source` (`:ui` / `:timer` / `:http` / `:machine` / `:repl` / `:ssr-hydration` / `:test` / `:other`) is the *trigger trio* — what woke the runtime — and predates the addition.
- `:origin` (`:app` / `:pair` / `:story` / `:test` / …) is the *actor identity* — which tool or app subsystem emitted the dispatch — and is **unconstrained** at the framework level.
- `:rf/dispatch-origin` is the *closed-enum functional source* — the 10-value taxonomy above. Tools branch on it to render the L2 row prefix and per-origin filter pills.

The closed enum is **closed** at the spec level. Adding a new value is a framework-level change with substrate-side consumer impact; it is not a per-app extension point. Tools and applications agree on the 10 values listed above.

The dispatch envelope's `:rf/dispatch-origin` slot rides via the `:tags :rf/dispatch-origin` axis onto every `:rf.event/dispatched` trace event. Production builds elide the trace surface entirely; the envelope's `:rf/dispatch-origin` slot remains in the production build (it is plain envelope data, not gated trace tooling) but consumers that read it sit on the dev-only trace stream and DCE alongside the rest of the trace machinery.

Per.

### `:op-type` vocabulary

Core values (the `:rf.<family>` discriminators): `:rf.event`, `:rf.sub`, `:rf.fx`, `:rf.cofx`, `:rf.view`, `:rf.registry`, `:rf.frame`, `:rf.machine`, `:warning`, `:error`, `:info`. (`:warning` / `:error` / `:info` are **severity** discriminators — not domino families — and stay bare; see [§Error contract](#error-contract).) The effects-pass marker `do-fx` rides op-type `:rf.fx`, operation `:rf.fx/do-fx` — it folds into the fx family alongside `:rf.fx/handled` (it is **not** a standalone op-type). The cofx family (`:rf.cofx`) carries the per-cofx success op `:rf.cofx/run`; the cofx skip / error events ride the `:warning` / `:error` severity discriminators (`:rf.cofx/skipped-on-platform`, `:rf.error/no-such-cofx`).

Additional values for re-frame2 concerns:

- `:rf.event/db-pending` / `:rf.event/db-pending-post-flow` — the (t1, t2) pending-`:db` snapshot pair. Both under op-type `:rf.event`. **t1 (`:rf.event/db-pending`)** fires inside the framework's outermost flows-after-interceptor BEFORE running flows, carrying the full pending `:db` the handler returned under `:tags :rf.event/db`. Fires whenever the handler returned a `:db` slot; suppressed otherwise (mirrors the `:rf.event/db-present?` gate on `:rf.fx/do-fx`). Fires regardless of whether the flows artefact is loaded. **t2 (`:rf.event/db-pending-post-flow`)** fires inside the same interceptor AFTER running flows, ONLY when the flow transform changed the value (`(not (identical? new-db pending-db))`); suppressed when t1 == t2 (no information). Both stamp the FULL value plain — same posture as `:rf.event/fx` on `:rf.fx/do-fx` per Mike's ruling; PDS structural sharing makes the cost pointer-sized and the `day8/de-dupe` wire layer collapses repeated subtrees on egress. Consumers (Xray's Handler panel, re-frame2-pair's `cascade-of`) read t1 to render the handler's returned `:db` value and read (t1, t2) together to render the t1→t2 reshape — the framework does NOT precompute a diff. On a flow-throw abort (Spec 013 §Failure semantics) t1 still fires (it ran before the throw) but t2 does NOT (the pending value was discarded). Both rides `interop/debug-enabled?` so production CLJS bundles DCE them.
- `:rf.frame/created` / `:rf.frame/re-registered` / `:rf.frame/destroyed` — frame lifecycle (all under op-type `:rf.frame`).
- `:rf.machine.lifecycle/created` / `:rf.machine.lifecycle/destroyed` — machine instance lifecycle.
- `:rf.machine/event-received` / `:rf.machine/transition` / `:rf.machine/snapshot-updated` / `:rf.machine/done` — machine activity. (`-done` per — fires when the machine enters a `:final?` state, immediately before the auto-destroy synchronously tears the actor down.)
- `:rf.machine.microstep/transition` — per-microstep transition emitted alongside the outer `:rf.machine/transition` for `:always`-driven cascades; one event per microstep with `:tags {:machine-id <id> :from <state> :to <state> :microstep-index <n>}` (per [005 §Trace events](005-StateMachines.md#trace-events) and [Spec-Schemas §`:rf/trace-event`](Spec-Schemas.md#rftrace-event)).
- `:rf.machine/spawned` / `:rf.machine/destroyed` — machine instance spawn/destroy events emitted by `fx.cljc` on the spawn / destroy fx-id paths. Distinct from `:rf.machine.lifecycle/created` / `-destroyed` (which are emitted by `frame.cljc` on the underlying registrar lifecycle); the `:rf.machine/*` pair is the fx-substrate observation, the `:rf.machine.lifecycle/*` pair is the registrar-substrate observation. Tools that just want "did a machine appear/disappear?" can subscribe to either; tools building causal graphs subscribe to both and disambiguate by the `:tags :emitted-from` axis. Per (Option A revised), payload `:tags` carry `:frame`, `:machine-id` (the spec-time machine-id), `:spawned-id` (the gensym'd actor address), `:system-id` (when set), `:parent-id` (the parent machine's registration-id, when the spawn came from declarative `:spawn`), and `:spawn-id` (the absolute prefix-path of the `:spawn`-bearing state node, when applicable) — together `:parent-id` + `:spawn-id` address the runtime spawn registry slot at `[:rf/spawned <parent-id> <invoke-id>]`, so tools can map the registry without re-deriving from app-db. the `:rf.machine/destroyed` event is **enriched** with a `:reason` tag — one of `:rf.machine/finished` (the actor entered a `:final?` state and auto-destroyed; see `:rf.machine/done` below), `:explicit` (a parent state-exit cascade, a `:spawn-all` cancel-on-decision, an imperative `[:rf.machine/destroy <id>]`, or any other runtime-initiated teardown), or `:parent-unmount-cascade` (the surrounding parent actor or frame was destroyed and the runtime walked surviving children). Existing observers that filter on `:tags` see the new key additively — no breaking change.
- `:rf.machine/done` — machine entered a `:final?` state; the runtime has invoked the parent's `:spawn :on-done` (if any) and is about to auto-destroy synchronously. **One event per finish.** `:tags {:machine-id <finishing-actor-id> :output <value-or-nil> :parent-id <parent-registration-id-or-nil>}`. `:output` is the child's `:data` slot named by the final state's `:output-key` (or `nil` when the final state has no `:output-key`). `:parent-id` is `nil` for **singleton** machines that reached `:final?` (per the singleton-symmetry rule D7 — see [005 §Final states](005-StateMachines.md#final-states-final--on-done--output-key)). Pairs with the immediately-following `:rf.machine/destroyed` event whose `:tags :reason` is `:rf.machine/finished`. Per.
- `:rf.machine/system-id-bound` / `:rf.machine/system-id-released` — `:system-id` reverse-index lifecycle (per [005 §Named addressing via `:system-id`](005-StateMachines.md#named-addressing-via-system-id)). `-bound` fires on every `:system-id`-bound spawn (including the rebound case that also emits the `:rf.error/system-id-collision` warning); `-released` fires on the matching destroy. `:tags {:frame <id> :system-id <name> :machine-id <gensym'd-id>}`.
- `:rf.machine.timer/scheduled` / `:rf.machine.timer/fired` / `:rf.machine.timer/stale-after` / `:rf.machine.timer/cancelled` / `:rf.machine.timer/skipped-on-server` — state-machine `:after` timer lifecycle (per [005 §Trace events](005-StateMachines.md#trace-events) and /). `:scheduled` fires on initial entry-time scheduling and on every subscription-driven re-resolution; its `:tags` carry `:delay-source <:literal | :sub | :fn>` to discriminate the three delay forms (per [005 §Value shape](005-StateMachines.md#value-shape) and [005 §Dynamic delay re-resolution](005-StateMachines.md#dynamic-delay-re-resolution)). `:fired` carries `:fired? <bool>` (false ⇒ guard suppressed the transition; sibling timers continue). `:cancelled` ( — one unified event id replacing the `:cancelled-on-resolution`) fires on every cancellation path; `:reason` discriminates the closed set `:on-exit / :on-destroy / :on-resolution / :on-supersede / :on-frame-destroy`. The `*/stale-*` form is the canonical naming for [§stale-detection trace events](Pattern-StaleDetection.md) — see also `:rf.route.nav-token/stale-suppressed` below. `:skipped-on-server` fires under SSR per [005 §SSR mode](005-StateMachines.md#ssr-mode).
- `:rf.machine.spawn-all/started` / `:rf.machine.spawn-all/all-completed` / `:rf.machine.spawn-all/some-completed` / `:rf.machine.spawn-all/any-failed` — state-machine `:spawn-all` spawn-and-join lifecycle (per [005 §Spawn-and-join via `:spawn-all`](005-StateMachines.md#spawn-and-join-via-spawn-all) and). `*/started` fires after all N children have been spawned on entry to the `:spawn-all`-bearing state. `*/all-completed` fires when `:join :all` resolves; `*/some-completed` fires when `:join :any` / `{:n N}` / `{:fn ...}` resolves on the success-side; `*/any-failed` fires when `:on-any-failed` resolves. `:tags {:machine-id <id> :spawn-id <prefix-path> :child-ids ... :done ... :failed ...}` (the specific subset of tags depends on which event fires; common to all is `:machine-id` + `:spawn-id`).
- `:rf.machine.spawn/cancelled-on-join-resolution` — fires once per sibling cancelled by `:cancel-on-decision? true` (the default) when a `:spawn-all` join condition resolves and surviving siblings are torn down (per [005 §Cancel-on-decision](005-StateMachines.md#cancel-on-decision-default-true) and). `:tags {:machine-id <parent-id> :spawn-id <prefix-path> :child-id <user-id> :spawned-id <gensym'd-id> :join-event <:on-all-complete | :on-some-complete | :on-any-failed | :on-timeout>}`. The trace fires per cancelled actor; observers needing one event per join resolution use `:rf.machine.spawn-all/*-completed` / `*/any-failed` / `:rf.machine.spawn/timed-out` instead.
- ~~`:rf.machine.spawn/timed-out`~~ — RETIRED. The `:timeout-ms` slot on `:spawn` / `:spawn-all` is dropped in favour of state-level `:after`; the trace event with it. Observers wanting "this `:spawn`-bearing state's wall-clock guard fired" now consume `:rf.machine.timer/fired` on the `:spawn`-bearing state's `:after` entry — same semantic, uniform substrate. Per [005 §Wall-clock timeouts on `:spawn` — use parent state's `:after`](005-StateMachines.md#wall-clock-timeouts-on-spawn--use-parent-states-after) and [MIGRATION §M-44](../migration/from-re-frame-v1/README.md#m-44-timeout-ms-removed-from-spawn--spawn-all--use-parent-states-after).
- `:rf.route.nav-token/allocated` / `:rf.route.nav-token/stale-suppressed` — navigation-token lifecycle (per [012 §Navigation tokens](012-Routing.md#navigation-tokens--stale-result-suppression)). `*-allocated` fires when a navigation cascade begins; `*-stale-suppressed` fires when an async result arrives carrying a now-superseded token. Same epoch idiom as the machine-`:after` timer events.
- `:rf.route/fragment-changed` / `:rf.route/navigation-blocked` — fragment-only URL change emission (per [012 §Fragments](012-Routing.md#fragments); renamed from `:rf.route/fragment-changed` to disambiguate from the runtime event `:rf.route/transitioned` which fires on every URL transition) and pending-nav protocol blockage (per [012 §Navigation blocking](012-Routing.md#navigation-blocking--pending-nav-protocol)). `:rf.route/fragment-changed` fires only on the fragment-only branch (the route-id / params / query did not change); the trace's `:tags` carry `:prev-fragment` and `:next-fragment` and never coincide with a `:rf.route.nav-token/allocated` event for the same drain — see the [`routing/fragment-change` conformance fixture](conformance/fixtures/route-fragment-change.edn).
- `:rf.route/registered` / `:rf.route/cleared` / `:rf.route/activated` / `:rf.route/deactivated` — route lifecycle (per [012 §Trace events](012-Routing.md#trace-events) and). `:rf.route/registered` fires on first-time `reg-route`; re-registration rides `:rf.registry/handler-replaced`. `:rf.route/cleared` fires on explicit `unregister-route!`. `:rf.route/activated` / `:rf.route/deactivated` fire on every cross-route navigation commit in that order; same-id navigation emits neither. Mirrors the flow-lifecycle symmetry (`:rf.flow/registered` / `:rf.flow/cleared` / `:rf.flow/computed`).
- `:rf.registry/handler-registered` / `:rf.registry/handler-cleared` / `:rf.registry/handler-replaced` — registration changes (hot reload). The canonical trio: `-registered` for a fresh id, `-cleared` for an explicit removal, `-replaced` when re-registration overwrote an existing id (the typical hot-reload case).
- `:flow` — flow lifecycle and evaluation events (per [013 §Flow tracing](013-Flows.md#flow-tracing)). The op-type for the whole flow trace stream; per-flow events live under `:rf.flow/*` operations (`:rf.flow/registered`, `:rf.flow/computed`, `:rf.flow/skip`, `:rf.flow/cleared`, `:rf.flow/failed` — see [§Flow trace events](#flow-trace-events) below). Tools filter `op-type :flow` to subscribe to the whole flow stream.
- `:rf.sub/run` — emitted by the sub-memo wrapper (`re-frame.subs.memo`) on a **true recompute** — the input value was NOT `=` to last-seen, so the user body re-ran (per [Spec 006 §Invalidation algorithm](006-ReactiveSubstrate.md#invalidation-algorithm) and). `:op-type :rf.sub`, `:operation :rf.sub/run`. One event per recompute. The base `:tags` are `{:frame <id> :rf.sub/id <query-id> :rf.sub/query-v <vec>}`. The pure `compute-sub` form (the snapshot-against-a-supplied-db form per [Spec 008 §Testing](008-Testing.md)) emits the same base shape from `re-frame.subs` but **omits** the attribution slots below — it bypasses the per-frame reactive cache so it has neither a prior cached value to diff nor a reactive context to attribute a cascade against.

    **Value-change + cascade attribution.** On the reactive recompute path the `:rf.sub/run` `:tags` carry, additively:

    | tag | shape | meaning | privacy |
    |---|---|---|---|
    | `:rf.sub/value-changed?` | `bool` | `(not= prev-value computed)` — `true` on the first recompute (no prior value to compare). Not wire-sensitive. | plain |
    | `:rf.sub/prev-value` | any | the prior computed value; `nil` on the first recompute. | redacted at the marks chokepoint |
    | `:rf.sub/value` | any | the freshly-computed value. | redacted at the marks chokepoint |
    | `:rf.sub/cascade?` | `bool` | `true` when an upstream **sub** drove the recompute (a layer-2+ sub); `false` for a layer-1 sub (driven by an app-db path change, not a sub). | plain |
    | `:rf.sub/cause-sub` | `[query-id args]` \| `nil` | for a cascade, the upstream `:<-` query-vector whose value changed; `nil` for a layer-1 sub OR a layer-2+ first recompute (no prior input to diff). | plain |
    | `:rf.sub/elapsed-ms` | `number` | wall-clock duration of the sub body recompute for THIS run, in fractional milliseconds (the memo wrapper brackets the body with `interop/now-ms` inside the `debug-enabled?` gate). The per-op DURATION the Trace panel's column reads — mirrors `:rf.view/elapsed-ms` for views. Present on the reactive recompute path in dev builds; absent on the pure `compute-sub` form (no timing bracket there) and DCE'd in production. | plain |

    `:rf.sub/prev-value` and `:rf.sub/value` are wire-value-sensitive app data and are redacted by the existing per-`:rf.sub/run` **marks chokepoint** (`re-frame.marks/project-sub-tags`, which `re-frame.trace/build-event` already runs for every `:rf.sub/run` emit per [Spec 015 §App-db → subs](015-Data-Classification.md)) — a sub whose output is schema-`:sensitive?` (or whose value propagates from a sensitive input) egresses BOTH slots as `:rf/redacted`, and a `:large?` sub gets the `:rf.size/large-elided` marker. `:rf.sub/value-changed?` stays a plain boolean and is always observable. **Why not `elide-wire-value` at the emit site?** The schema-first walker reads the frame's `[:rf/elision …]` registry by dereferencing the app-db container; calling it inside a sub's reaction compute fn registers a spurious reactive dependency on app-db, breaking the glitch-free `db → layer-1 → layer-2` layering (the sub would recompute on any app-db change). The marks chokepoint resolves sensitivity from process-scoped registration marks + the sub-output propagation table — never a reactive read — so it is reaction-safe. The whole attribution branch (the enriched tag map) sits inside the shared `interop/debug-enabled?` gate so Closure DCE folds it out under `:advanced` + `goog.DEBUG=false`; the unattributed base tag is emitted on the production path so the op-type vocabulary is unchanged there. Consumers: Xray's Reactive panel reads `:rf.sub/value-changed?` / `:rf.sub/prev-value` / `:rf.sub/value` to populate "SUBS WHOSE VALUE CHANGED" and `:rf.sub/cascade?` / `:rf.sub/cause-sub` for "SUBS THAT CASCADED".
- `:rf.sub/skip` — emitted by the sub-memo wrapper (`re-frame.subs.memo`) on the **memo-hit** branch — the input value was `=` to last-seen, so the user body did NOT re-run (per [Spec 006 §Invalidation algorithm](006-ReactiveSubstrate.md#invalidation-algorithm) and). `:op-type :rf.sub`, `:operation :rf.sub/skip`. One event per memo hit per recompute attempt. `:tags {:frame <id> :rf.sub/id <query-id> :rf.sub/query-v <vec> :rf.sub/reason :input-value-equal :rf.sub/input-paths-unchanged <vec-of-upstream-input-signals-or-empty>}`. The cascade-DAG consumer reads this to render the "considered, no recompute" branch of the reactive DAG dimmed alongside the recomputed `:rf.sub/run` entries. Distinct from `:rf.sub/run` (recomputed) and from the case where the sub was not considered at all (no upstream change propagated to its input).
- `:rf.sub/dispose` — emitted by the sub-cache (`re-frame.subs.cache`) at every **eviction site** — the cache slot was actually removed and the underlying reaction torn down (per [Spec 006 §Reference counting and disposal](006-ReactiveSubstrate.md#reference-counting-and-disposal) and). `:op-type :rf.sub`, `:operation :rf.sub/dispose`. One event per evicted cache slot. `:tags {:frame <id> :rf.sub/id <query-id> :rf.sub/query-v <vec> :rf.sub/reason <enum>}`. The `:rf.sub/reason` slot is a **closed enum** discriminating the eviction path:

    | `:rf.sub/reason` | Eviction path | Site |
    |---|---|---|
    | `:no-more-derefers` | The slot's ref-count dropped to 0 and the cache disposed synchronously (rf2-cmfln) — last subscriber detached. The dominant production case: a Reagent view unmounted, or a `(when X @some-sub)` flipped to false, dropping the last derefer. | `re-frame.subs.cache/dispose-entry-now!` |
    | `:hot-reload` | A `:sub` re-registration evicted every cached entry for that sub-id across every frame (per [Spec 001 §Hot-reload semantics](001-Registration.md)). Fires regardless of ref-count — the cached reaction holds the OLD body via closure and MUST be replaced. | `re-frame.subs.cache/invalidate-sub-on-replace!` |
    | `:cache-clear` | An explicit `clear-sub-cache!` call (test fixture, REPL teardown, `:rf/default` frame destroy) walked the cache and disposed every slot. Per-slot emit; fires regardless of ref-count. | `re-frame.subs.cache/clear-sub-cache!` |

    The emit is **single-fire** per actual eviction (gated on the CAS-winner check that already serialises `interop/dispose!`), so a concurrent sync-dispose + invalidate race emits exactly one `:rf.sub/dispose` for the winning evictor — not two. Disposal is **same-tick as the cascade that drove the unmount/condition-flip** (per [Spec 006 §Reference counting and disposal](006-ReactiveSubstrate.md#reference-counting-and-disposal) — synchronous on derefer-count → 0): the `:rf.trace/dispatch-id` rides via `*handler-scope*` for any eviction that fires inside a cascade's drain. Eviction fires that land OUTSIDE a cascade (an explicit `clear-sub-cache!` from a test) carry no `:rf.trace/dispatch-id` — the per-frame ring still anchors the event by `:frame` and consumers fall back to wall-clock ordering. The whole emit sits inside `interop/debug-enabled?` so production CLJS bundles DCE it.

    Consumers: the Xray Epoch panel's SUBSCRIPTIONS section consumes `:rf.sub/dispose` to surface the "disposed subs" branch alongside the per-epoch `:rf.sub/create` / `:rf.sub/run` / `:rf.sub/skip` events — the full sub-lifecycle answer to "what happened to my subs this cascade?". Pair / Story consumers use `:rf.sub/reason` to distinguish "last view unmounted" from "hot-reload" from "test teardown" without inferring from sequence.
- `:rf.cofx/run` — emitted by `inject-cofx`'s interceptor (`re-frame.cofx`) on the **success branch** — the cofx handler ran to completion (and post-validation, if any, passed). `:op-type :rf.cofx`, `:operation :rf.cofx/run`. One event per successful cofx injection. The emit rides **inside** the cofx handler's `with-handler-scope` binding, so its source-coord / `:rf.trace/trigger-handler` / `:rf.trace/call-site` ride the trace per [§Handler-scope](#handler-scope-the-in-scope-reading-at-emit-time). `:tags {:frame <id> :rf.cofx/id <cofx-id> :rf.cofx/value <per-call-injected-value> :rf.cofx/elapsed-ms <number>}`. `:rf.cofx/value` is the per-call injected value (the 2-arity `(inject-cofx :id value)` form); it is **omitted** on the no-value 1-arity path (parity with the `:rf.cofx/skipped-on-platform` / `:rf.error/no-such-cofx` branches) and is redacted at the marks chokepoint (`re-frame.marks/project-cofx-run-tags`) against the cofx's declared `:sensitive` / `:large` marks — mirroring `:rf.fx/handled`'s `:rf.fx/args` redaction. `:rf.cofx/elapsed-ms` is the dev-only wall-clock of the handler invoke. The whole emit (tag-map + `emit!`) sits inside the `interop/debug-enabled?` gate so production DCEs it. Distinct from `:rf.cofx/skipped-on-platform` (the platform-gate skip — the handler did NOT run) and `:rf.error/no-such-cofx` (no registered handler). Per.
- `:rf.view/render` — emitted by the `views.cljs` frame-aware-view wrapper at the START of every registered-view render (per [Spec 004 §Render-tree primitives](004-Views.md)). `:op-type :rf.view`, `:operation :rf.view/render`. `:tags {:frame <id> :rf.view/render-key [<view-id> <instance-token>]}`. One event per render. The substrate-agnostic wrapper composes around every adapter's user render-fn, so this rides Reagent / UIx / Helix renders uniformly. Tools consuming render-count metrics subscribe to this op.
- `:rf.view/rendered` — emitted by the same wrapper AFTER the user render-fn returns (so the per-render deref sink is fully populated), carrying cascade-attribution + per-view ACTION/REASON data for Xray's Reactive / Views panels. `:op-type :rf.view`, `:operation :rf.view/rendered`. One event per render (capped — see below). `:tags`:

    | tag | shape | meaning | since |
    |---|---|---|---|
    | `:rf.view/render-key` | `[view-id instance-token]` | the rendering instance (parity with `:rf.view/render`). | |
    | `:rf.view/id` | keyword | the registered view id. | |
    | `:frame` | keyword | the frame the render landed in. | |
    | `:rf.view/mount?` | `bool` | `true` on the instance's FIRST render, `false` on every subsequent re-render — the mount-vs-rerender discriminator (keyed off `:rf.view/render-key`, which is stable across an instance's re-renders and fresh per new instance). Always present. | |
    | `:rf.view/deref-subs` | `[[query-id args] …]` | the subscription query-vectors THIS view deref'd during the render — its OWN read-set (first-seen order, captured for EVERY deref incl. memo-hits). Absent when the view derefs no subs (a pure structural render). This is the precise PER-VIEW reactive reason; distinct from `:rf.view/cause-subs` (cascade-wide, over-reports — lists every sub that ran in the cascade regardless of whether this view reads it). | |
    | `:rf.view/triggered-by` | `query-id` | the SINGLE sub-id that caused THIS view to re-render — the first sub in `:rf.view/deref-subs` (the view's own read-set) whose value changed in the cascade (intersection resolved at emit time against the in-flight cascade buffer's value-changed `:rf.sub/run` set). The pre-computed per-view re-render cause Xray's Views panel shows directly (no consumer-side intersection needed). Absent on a *structural* re-render (none of the view's own subs changed value — `← parent re-render`) and outside a cascade. Narrower than `:rf.view/deref-subs` (full read-set, changed-or-not) and `:rf.view/cause-subs` (cascade-wide). | .1 |
    | `:rf.view/elapsed-ms` | `number` | wall-clock duration of the user render-fn for THIS render, in fractional milliseconds (measured around the performance-mark bracket). The per-view render timing Xray's Views panel shows. Always present in dev builds (the timing reads ride `interop/debug-enabled?` so production DCEs them with the rest of the emit). | .1 |
    | `:rf.view/cause-event-id` | event-id | (when in-cascade) the dispatching cascade's `:rf.event/run-start` event-id. Absent outside a cascade. | |
    | `:rf.view/cause-subs` | `[query-id …]` | (when in-cascade) distinct sub-ids that ran in the cascade, first-seen order, capped at 100. Absent outside a cascade. | |

    **The per-view "reason" classifier (the consumer's by-elimination rule).** A re-render is *reactive* when at least one sub in `:rf.view/deref-subs` changed value this cascade (intersect `:rf.view/deref-subs` with the cascade's value-changed `:rf.sub/run` set) → show those subs. The runtime pre-computes the FIRST such sub as `:rf.view/triggered-by`, so a consumer can name the cause directly off the op without re-deriving the intersection. Otherwise the render is *structural* — the view re-rendered because its parent did (new props), with none of its own subs changed → `:rf.view/triggered-by` is absent → label it **`← parent re-render`**, UNNAMED (no component-tree / props-diff capture exists or is planned; the structural parent is deliberately never named,). `:rf.view/deref-subs` is what makes the reason PER-VIEW rather than cascade-wide.

    Capped at 100 `:rf.view/rendered` per cascade with a one-shot `:rf.view/rendered-cap-reached` marker (`:tags {:frame <id> :rf.view/dropped-after 100}`) to bound the per-cascade buffer's heap budget for full-page re-render storms. The whole emit body — including the `:rf.view/mount?` discriminator and the `:rf.view/deref-subs` sink — sits inside `interop/debug-enabled?`; production DCEs it (pinned by the elision probe, [§Production builds](#production-builds-zero-overhead-zero-code)).
- `:rf.view/unmounted` — emitted when a registered-view component INSTANCE tears down. `:op-type :rf.view`, `:operation :rf.view/unmounted`. `:tags {:rf.view/render-key [<view-id> <instance-token>] :rf.view/id <keyword> :frame <keyword>}`. One event per instance teardown (NOT capped — teardown is one-shot per instance). Consumers (Xray's Views table) read this to label the `unmount` action. The whole surface sits inside `interop/debug-enabled?`; production DCEs it. **Substrate coverage: all adapters.** Two teardown seams emit the same op-shape, one per substrate family:
    - **Reagent family (stock + reagent-slim).** Rides the per-render-instance reaction-dispose mechanism (the same one `r/with-let`'s `finally` arm uses): the `views.cljs` wrapper creates a lifecycle reaction, derefs it inside the render so the substrate's per-component render reaction tracks it as a dependency, and registers an on-dispose callback firing the emit — the render reaction disposes its tracked dependencies on the component's unmount, so the callback fires exactly once.
    - **React-hook family (UIx / Helix).** Those substrates run the `views.cljs` wrapper inside a function component with no tracked render reaction (they don't publish `:adapter/make-reaction`, so the reaction-dispose arm above no-ops), so the shared React-hook spine's `wrap-view` seam (`re-frame.substrate.spine/make-wrap-view`) arms a `React.useEffect` empty-deps cleanup that fires the emit on unmount — one-shot teardown matching the Reagent path. The instance-token is minted into a `useRef` so the `:rf.view/render-key` tuple is stable across re-renders; the `:frame` tag is captured in-render so the cleanup (which runs outside the React render) reports the frame the instance rendered under. The emit reaches `re-frame.views/emit-view-unmounted!` through the `:views/emit-view-unmounted!` late-bind hook so the spine carries no static views dependency.

    See [Spec 004 §Render-tree primitives](004-Views.md).
- `:rf.cascade/captured` — focused-event-only per-epoch cascade-DAG aggregator emitted by `re-frame.trace.cascade` at end-of-epoch (after the cascade buffer has been harvested but before `:rf.epoch/snapshotted` fires). `:op-type :rf.cascade`. Captures the full per-cascade DAG — db-paths, subs recomputed and skipped, flows computed and skipped, views rendered — for the operator's currently-focused epoch only (a consumer-published focus predicate via `re-frame.trace.cascade/set-focus-predicate!` discriminates; off-focus epochs pay just the predicate call). Bounded at 50 subs / 100 views per epoch per the Xray Reactive panel render budget; cascades exceeding the cap stamp `:sub-cap-truncated? true` / `:view-cap-truncated? true` and retain the first N entries. `:tags {:frame <id> :rf.epoch/id <id> :rf.trace/event-id <id> :subs-recomputed [...] :subs-skipped [...] :flows-computed [...] :flows-skipped [...] :views-rendered [...] :sub-cap-truncated? <bool> :view-cap-truncated? <bool>}`. Per — the substrate side of the Xray Reactive panel's "full cascade detail for the focused epoch + summary for the rest" budget.
- `:error` / `:warning` — universal severity discriminators for failure events. The category-specific identity lives in `:operation` (e.g. `:rf.error/handler-exception`); see [§Error contract](#error-contract) for the authoritative model.
- `:info` — informational advisories the runtime emits without warning or error severity (e.g. `:rf.http/retry-attempt` per [014 §Retry and backoff](014-HTTPRequests.md#retry-and-backoff)). Tools that filter for issues subscribe to `:warning` / `:error`; tools that surface activity timelines subscribe to `:info` as well.
- **Frame-exit machine teardown — single emit on the lifecycle channel.** When a frame's destroy walks each surviving machine snapshot, `frame.cljc` emits **one trace event per destroyed machine instance** on the unified lifecycle channel: `:op-type :rf.machine.lifecycle/destroyed`, `:operation :rf.machine.lifecycle/destroyed`. `:tags {:frame <id> :machine-id <id> :last-state <state> :reason :parent-frame-destroyed}`. The `:reason` tag discriminates *why* the actor went away — frame-exit emits `:parent-frame-destroyed`; the fx-substrate's `:rf.machine/destroyed` emit site (`lifecycle_fx.cljc`) carries the other reasons under the same `:reason` slot (`:rf.machine/finished` for natural termination, `:explicit` for `[:rf.machine/destroy <id>]`, `:parent-unmount-cascade` for parent-cascade teardown). Tools that just want "an actor instance appeared / went away" subscribe to `:op-type :rf.machine.lifecycle/*` and branch on `:reason` only when they need cause-specific routing.

    The `:reason` enum (canonical values used by the runtime):

    | `:reason` | Emitted by | Meaning |
    |---|---|---|
    | `:parent-frame-destroyed` | `frame.cljc` (`destroy-frame!`) | The actor's owning frame was destroyed; its snapshot was reaped as part of the frame-exit cascade. |
    | `:rf.machine/finished` | `lifecycle_fx/finalize.cljc` | The actor reached a `:final?` state and the runtime auto-destroyed it after firing the parent's `:on-done`. |
    | `:explicit` | `lifecycle_fx/destroy.cljc` | The actor was destroyed by an explicit `[:rf.machine/destroy <id>]` fx. |
    | `:parent-unmount-cascade` | `lifecycle_fx/destroy.cljc` | The actor was a spawned child whose parent state exited (per [005 §Cancellation cascade](005-StateMachines.md#cancellation-cascade--in-flight-rfhttpmanaged-aborts)). |

    The enum is open per [§`:tags` is the open-ended bag](#tags-is-the-open-ended-bag); future causes are additive.

    **Two-channel teardown — what each channel sees.** The runtime emits machine-destroy traces on **two parallel channels** with deliberately distinct purposes; the channel name carries the source-of-emit, the `:reason` slot carries the cause. Per audit Finding 5:

    | Channel | Source-of-emit | What it observes | Typical consumer |
    |---|---|---|---|
    | `:rf.machine.lifecycle/destroyed` | `frame.cljc` (and re-emitted on the unified lifecycle channel for `:reason :parent-frame-destroyed`) | The **registrar-substrate** observation: the actor handler / snapshot disappeared from the registrar. One event per destroyed instance, including frame-exit reaping. | "Did a machine appear/disappear?" — observers building a live list of running actors. |
    | `:rf.machine/destroyed` | `lifecycle_fx/finalize.cljc` + `lifecycle_fx/destroy.cljc` | The **fx-substrate** observation: a destroy fx ran on the spawn / destroy fx-id path. One event per fx-driven teardown. Does NOT fire for frame-exit reaping (that is registrar-substrate only). | Causal-graph builders ("which fx caused this teardown?") — observers correlating fx emission against actor lifecycle. |

    Tools that "just want did a machine appear/disappear?" pick **either** channel and rely on the `:reason` slot for cause. Tools building causal graphs (Pair, Xray, Story) subscribe to **both** and disambiguate via `:tags :emitted-from`. The naming axis (`:rf.machine.lifecycle/*` vs `:rf.machine/*`) carries the source-of-emit distinction; the `:reason` slot carries the cause. A rename to `:rf.machine.fx/destroyed` / `:rf.machine.registrar/destroyed` was considered and rejected: the existing names align with how the rest of the spec namespaces the two substrates (`:rf.machine.lifecycle/*` is the registrar-lifecycle family, `:rf.machine/*` is the fx-substrate family), and the cost of churning every tool / fixture / docstring outweighs the marginal naming-axis clarity.
- `:rf.frame/drain-interrupted` — lifecycle event emitted by `router.cljc` when the drain loop detects `(:destroyed? (:lifecycle frame))` mid-cycle and drops remaining queued events. `:op-type :rf.frame` (the frame-lifecycle family — see `:rf.frame/created` / `:rf.frame/destroyed` siblings; **not** `:op-type :rf.event`, which is reserved for "an event was dispatched"). `:tags {:frame <id> :dropped-count <int>}`. Per [002 §Edge cases worth pinning](002-Frames.md#edge-cases-worth-pinning).
- `:rf.epoch/snapshotted` / `:rf.epoch/outcome` / `:rf.epoch/restored` / `:rf.epoch/db-replaced` — epoch-history operations under `:op-type :rf.epoch`. `-snapshotted` fires once per dequeued event when the runtime has appended a fresh `:rf/epoch-record` (one per epoch — per [002 §Drain versus event](002-Frames.md#drain-versus-event--the-epoch-unit)) and carries the **detailed cause** `:outcome` enum from `:rf/epoch-record` (`:ok` / `:halted-depth` / `:halted-destroy` / `:halted-handler-exception`, per [Spec-Schemas §`:rf/epoch-record` §Outcomes](Spec-Schemas.md#rfepoch-record)). `-outcome` fires **immediately after** `-snapshotted` at the same cascade-trailer point and carries the **consumer-facing summary** `:outcome` enum (`:ok` / `:blocked` / `:error`) — the coarse three-tier projection the Trace-panel close-row ([tools/xray/spec/023-Trace-Panel.md §13](../tools/xray/spec/023-Trace-Panel.md)) and Story outcome chips read directly. The two ops carry the same `:frame` / `:rf.epoch/id` / `:rf.trace/event-id` so consumers correlate detail ↔ summary by epoch-id; tools that want the cause read `-snapshotted`'s `:outcome`, tools that want the summary read `-outcome`'s `:outcome`. `-restored` fires after a successful `restore-epoch`; `-db-replaced` fires after a successful `reset-frame-db!` (the pair-tool write surface — see [Tool-Pair §Pair-tool writes](Tool-Pair.md#pair-tool-writes--state-injection)). Per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel). `:tags {:frame <id> :rf.epoch/id <id> :rf.trace/event-id <id>? :outcome <enum>}` (the `:outcome` tag is required on `-snapshotted` and `-outcome`, absent on the other two).

    **The consumer-facing `:outcome` mapping** (pinned in `implementation/epoch/test/re_frame/epoch_test.clj` `outcome-enum-projection-pins-mapping`; load-bearing — devtools and trace-stream consumers depend on it):

    | `:rf.epoch/snapshotted` `:outcome` (cause) | `:rf.epoch/outcome` `:outcome` (summary) | Rationale |
    |---|---|---|
    | `:ok` | `:ok` | The cascade settled cleanly. |
    | `:halted-depth` | `:blocked` | The drain hit the configured depth limit; the halting event never ran. A drain-shape stop, not an error. |
    | `:halted-destroy` | `:blocked` | The frame was destroyed mid-drain — a deliberate lifecycle stop. |
    | `:halted-handler-exception` | `:error` | Schema-reserved cause; the reference runtime currently does NOT emit this (handler throws route through the interceptor error-capture seam and settle `:ok` with the error trace under `:trace-events`, per [Spec-Schemas §`:rf/epoch-record` §Outcomes](Spec-Schemas.md#rfepoch-record)). The mapping is pinned for a future runtime that aborts the drain on a handler throw.

    The mapping rationale: `:halted-destroy` is a **deliberate lifecycle stop** (the frame's owner asked for the frame to go away mid-cascade) and `:halted-depth` is a **shape-of-the-drain stop** (a runaway re-dispatch loop tripped the configured depth limit — the runtime guarded against further work). Neither involves a thrown exception; surfacing both as `:blocked` distinguishes them from genuine errors that consumers want to flag.
- `:rf.epoch.cb/silenced-on-frame-destroy` — listener-silencing notification emitted **once per `(frame, cb-id)` pair** when a frame previously observed by a `register-epoch-listener!` callback is destroyed (per [Tool-Pair §Surface behaviour against destroyed frames](Tool-Pair.md#surface-behaviour-against-destroyed-frames) and). `:op-type :rf.epoch.cb`. `:tags {:frame <id> :cb-id <id>}`. The callback registration remains in place; the trace exists so a tool whose previously-firing cb has gone silent learns *why* without polling registry state. Repeat destroys of the same frame do not re-emit; a re-registration of a same-keyed frame followed by a fresh delivery re-arms the cb's observation set so a subsequent destroy re-emits.

Consumers filter by `:op-type` (or `:source`, or `(get-in ev [:tags :frame])`) to get the slice they care about. Adding new `:op-type` values is non-breaking — tools ignore what they don't understand.

### `:tags` is the open-ended bag

Variable per-event data goes in `:tags`. New tags can be added without breaking consumers. Use `:tags` for op-type-specific data; reserve top-level keys for fields universal across all events.

**Every framework-owned top-level `:tags` key is namespaced** under its domino family or — for the cross-cutting correlation spine — under `:rf.trace/*`, per the single-root convention ([Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)). The one deliberate carve-out is `:frame` (see the canonical-routing note below). The full key scheme:

| `:tags` key | Family | Notes |
|---|---|---|
| `:frame` | universal routing | **Bare carve-out** (see below) |
| `:rf.trace/dispatch-id`, `:rf.trace/parent-dispatch-id`, `:rf.trace/event-id`, `:rf.trace/trace-id`, `:rf.trace/phase` | cross-cutting correlation | Stamped across every domino family — the trace channel's own correlation spine; no single domino home, so they live under `:rf.trace/*` (per [Conventions §`:rf.trace/*`](Conventions.md#the-single-root-reserved-set)). |
| `:rf.event/v` (the dispatched event vector), `:rf.event/origin`, `:rf.event/sync?`, `:rf.event/fx`, `:rf.event/db-present?`, `:rf.event/db`, `:rf.event/coeffects`, `:rf.event/elapsed-ms` | event | `:rf/dispatch-origin` keeps its existing `:rf/*` form (a stable hoisted slot, not churned here). `:rf.event/elapsed-ms` is the HANDLER-BODY-only wall-clock on `:rf.event/run-end`. `:rf.event/db` is the FULL `:db` value stamped on the `:rf.event/db-pending` (t1) and `:rf.event/db-pending-post-flow` (t2) trace events; PDS structural sharing keeps the cost pointer-sized, the `day8/de-dupe` wire layer collapses repeated subtrees on egress. |
| `:rf.sub/id`, `:rf.sub/query-v`, `:rf.sub/input-signals`, `:rf.sub/value-changed?`, `:rf.sub/prev-value`, `:rf.sub/value`, `:rf.sub/cascade?`, `:rf.sub/cause-sub`, `:rf.sub/reader-render-key`, `:rf.sub/input-paths-unchanged`, `:rf.sub/reason`, `:rf.sub/elapsed-ms` | sub | `:rf.sub/elapsed-ms` is the per-recompute wall-clock. |
| `:rf.view/render-key`, `:rf.view/id`, `:rf.view/mount?`, `:rf.view/deref-subs`, `:rf.view/triggered-by`, `:rf.view/elapsed-ms`, `:rf.view/cause-event-id`, `:rf.view/cause-subs`, `:rf.view/dropped-after` | view | |
| `:rf.fx/id`, `:rf.fx/args`, `:rf.fx/from`, `:rf.fx/to`, `:rf.fx/platform`, `:rf.fx/registered-platforms`, `:rf.fx/elapsed-ms` | fx | `:rf.fx/elapsed-ms` is the per-fx-handler-invoke wall-clock on `:rf.fx/handled`. |
| `:rf.epoch/id`, `:rf.epoch/outcome` | epoch | |
| `:rf.cofx/id`, `:rf.cofx/value`, `:rf.cofx/elapsed-ms` | cofx | `:rf.cofx/elapsed-ms` is the per-cofx-handler-invoke wall-clock on `:rf.cofx/run`. |

**Nested record-map keys are NOT renamed.** Where a tag *value* is a structured map or vector-of-maps (e.g. `:rf.cascade/captured`'s `:subs-recomputed [{:sub-id _ :query-v _} …]` entries), the inner keys are internal value shape, not top-level `:tags` keys — they carry no collision surface and stay as-is. The namespace scheme governs top-level `:tags` keys only.

**Canonical per-frame routing key — the deliberate bare carve-out.** Every trace event that names a frame uses `:frame` under `:tags`. The framework MUST NOT emit `:frame-id` as a tag key — `:frame` is the single canonical name; ports that re-emit must follow suit. Consumers read `(get-in ev [:tags :frame])`. (Historical drift in v2 development used both; the alias has been retired.) `:frame` is the **one deliberate exception** to the "every framework tag is namespaced" rule above: it is the single universal routing tag stamped on every frame-qualified event, carries zero collision risk in practice, and is the one key every tool already special-cases — so it stays bare rather than becoming `:rf/frame`.

### Open shape; new fields are additive

The map is open. New fields can be added by future versions without breaking consumers — listeners read what they understand and ignore the rest. The forward-compat commitments:

- **Required top-level fields** (`:id`, `:operation`, `:op-type`, `:time`, `:tags`) are stable. Removing or renaming any is a breaking change.
- **Re-frame2 additions hoisted to top level** (`:source`, `:recovery`) are stable once shipped; they are present on every event whose tags carry them.
- **Op-type-specific fields inside `:tags`** are stable within their op-type — including `:frame`, which every emit site supplies under `:tags`. New optional tag keys are additive; existing keys don't change shape.
- **New `:op-type` values** can be added without breaking existing tools — tools filter the values they recognise.

### Canonical per-event trace sequence

A single event's cascade emits a **canonical, ordered trace sequence**. The ordering is contract — off-box monitors, Xray's Trace panel, Story, and conformance recorders rely on it to place each phase relative to the others. A conformant port MUST emit (the phases that fire for a given event; omit those whose condition is unmet) in this order:

```
:rf.event/dispatched         ;; (envelope queued; one per dispatch — may precede the drain)
:rf.event/run-start          ;; the handler's interceptor chain begins
:rf.cofx/run                 ;; per inject-cofx interceptor that ran to success, in
                             ;; :before-chain order (carries :rf.cofx/id + value +
                             ;; :rf.cofx/elapsed-ms); precedes the handler body
  … handler body + the rest of the :after chain run (reshaping the :db effect) …
:rf.event/db-pending         ;; t1 — the post-handler-chain / pre-flow-
                             ;; transform pending :db. Carries the FULL value the
                             ;; handler returned under :tags :rf.event/db (same
                             ;; posture as :rf.event/fx on :rf.fx/do-fx — Mike
                             ;; 2026-05-25). Fires ONLY when the handler returned
                             ;; a :db slot; suppressed otherwise.
:rf.flow/computed | :rf.flow/skip | :rf.flow/failed   ;; the OUTERMOST :after —
                             ;; the flow transform, per flow, in topological order.
                             ;; Fires after the rest of the :after chain (so it
                             ;; sees the fully-reshaped :db effect) and BEFORE
                             ;; :rf.event/db-changed (install).
:rf.event/db-pending-post-flow ;; t2 — the post-flow-transform / pre-
                             ;; commit pending :db. Carries the FULL flow-
                             ;; augmented value under :tags :rf.event/db. Fires
                             ;; ONLY when flows actually transformed the value
                             ;; (the substrate's identical?-by-reference guard);
                             ;; omitted otherwise (t1 == t2 carries no info).
:rf.event/db-changed         ;; the FLOW-AUGMENTED :db installs into app-db (the
                             ;; single deferred commit — the atomic boundary)
:rf.sub/run | :rf.sub/skip   ;; sub-cache recompute on the new (flow-augmented) db
:rf.fx/handled               ;; per :fx entry (reads the flow-augmented app-db)
:rf.fx/do-fx                 ;; terminating :fx-walk marker — fires AFTER the per-fx
                             ;; :rf.fx/handled entries (carries the :fx-vector +
                             ;; :db-present? shape for the Event lens)
:rf.view/render | :rf.view/rendered   ;; reactive re-render on the new db
:rf.event/run-end            ;; cascade-tail: fires LAST, after the deferred :db
                             ;; install and the :fx walk (router emits it in
                             ;; emit-cascade-trailers!, after commit-and-flow!).
                             ;; Carries :rf.event/elapsed-ms — the HANDLER-BODY-only
                             ;; wall-clock (the interceptor chain, NOT the whole
                             ;; cascade) for the Trace panel's DURATION column.
```

**The `:rf.event/db-pending` / `:rf.event/db-pending-post-flow` pair (t1 / t2).** Two trace events bracket the flow transform. **t1 (`:rf.event/db-pending`)** fires inside the framework's outermost `:after` (`flows-after-interceptor`) BEFORE running flows, when the handler returned a `:db` slot; **t2 (`:rf.event/db-pending-post-flow`)** fires inside the same interceptor AFTER running flows, when the flow transform actually changed the pending value (`(not (identical? new-db pending-db))`). Both carry the FULL `:db` value under `:tags :rf.event/db` — same payload-slot posture as `:rf.event/fx` on `:rf.fx/do-fx`, and same Mike-ruled posture (2026-05-25): full reference, no diff, no DEBUG gate. Persistent-data structural-sharing keeps the cost pointer-sized; the `day8/de-dupe` layer at the pair-mcp wire boundary collapses repeated subtrees on egress. Consumers (Xray's Handler panel, re-frame2-pair's `cascade-of`) read t1 to render the handler's returned `:db` value and read (t1, t2) together to render the t1→t2 flow reshape — the framework does NOT precompute a diff (the values are full both ends; client-side diff is cheap).

t1 fires when the handler returned `:db`, regardless of whether the flows artefact is loaded (apps that never registered a flow still get t1). t2 is by definition impossible without the flows artefact (no flow could have transformed the pending value). On a flow-throw abort (Spec 013 §Failure semantics), t1 still fires (it ran before the throw) but t2 does NOT (the cascade aborted, the pending `:db` was discarded with no install — mirrors the absence of `:rf.event/db-changed`). Both emits sit inside the shared `interop/debug-enabled?` gate so production CLJS bundles DCE them along with the rest of the trace surface.

**The flow position is the load-bearing change.** `:rf.flow/computed` is emitted **after the handler's `:after` chain** (the flow transform is the outermost `:after`, so it fires after the rest of the chain reshapes the `:db` effect) and **before `:rf.event/db-changed`**. This is the inversion from the prior design, where `:rf.event/db-changed` fired *before* flows (flows then mutated the already-installed db). Now `:rf.event/db-changed` reflects the **flow-augmented db** — the value installed already carries every flow's output. A consumer placing flows on the cascade timeline reads `:rf.flow/computed` between `:rf.event/run-start` and `:rf.event/db-changed`; the flow's write is visible in the `:rf.event/db-changed` snapshot, not applied after it.

`:rf.event/run-end` is a **cascade-tail** trace: the router emits it in `emit-cascade-trailers!` *after* `commit-and-flow!` has run, so it falls **after** the deferred `:db` install (`:rf.event/db-changed`) and **after** the `:fx` walk — it is the **last** trace of a clean cascade. (It is not emitted at the close of the interceptor chain; the chain completes inside `run-chain`, the install and `:fx` walk follow in `commit-and-flow!`, and only then does the trailer fire.) The relative order that consumers depend on is **`:rf.flow/computed` → `:rf.event/db-changed` → `:rf.fx/handled`**.

**`:rf.event/run-end`'s `:rf.event/elapsed-ms` is the HANDLER-BODY duration, not the cascade duration.** Although the trace *fires* at cascade tail, the `:rf.event/elapsed-ms` tag it carries is measured around the interceptor chain only (`run-chain`, captured before `commit-and-flow!`) — so the Trace panel's DURATION column shows the time the handler body itself took, distinct from the whole `run-start → run-end` wall-clock (which also covers the `:db` install, the flow walk, the `:fx` walk, and the resulting sub recomputes + view re-renders). A consumer wanting the whole-cascade latency reads it off the always-on event-emit record's `:elapsed-ms` (per [§Event-emit listener](#what-is-available-in-production)) or subtracts the `run-start` / `run-end` `:time` stamps; `:rf.event/elapsed-ms` answers the narrower "how long did the handler take?" question the per-op DURATION column poses. The tag rides `interop/debug-enabled?` so production DCEs it.

**Throw variant — the event aborts at the commit boundary.** A flow throw is a **pre-install throw**, so the event aborts before the `:db` install (the atomicity contract — per [013 §Failure semantics](013-Flows.md#failure-semantics) and [002 §Drain-loop pseudocode](002-Frames.md#drain-loop-pseudocode)). The canonical sequence truncates: the `:rf.flow/*` phase ends at `:rf.flow/failed`, the router emits the cascade-level `:rf.error/flow-eval-exception`, and the cascade STOPS — **NO `:rf.event/db-changed`**, no `:rf.sub/run`, no `:rf.fx/*`:

```
:rf.event/run-start
  … handler body + the rest of the :after chain run …
:rf.event/db-pending                    ;; t1 — STILL fires when the
                                        ;; handler returned :db; it ran BEFORE the
                                        ;; throw. The trace records what the
                                        ;; handler tried to write; the install
                                        ;; never happened.
:rf.flow/computed                       ;; per prior flow that ran (its WRITE is
                                        ;; discarded — the trace records the run only)
:rf.flow/failed                         ;; the throwing flow
:rf.error/flow-eval-exception           ;; cascade-level error (always-on substrate)
:rf.event/run-end                       ;; cascade-tail — fires LAST (the trailer is
                                        ;; emitted unconditionally after the aborted
                                        ;; commit, with :outcome :flow-error)
;; — NO :rf.event/db-pending-post-flow, NO :rf.event/db-changed, NO
;;   :rf.sub/run, NO :rf.fx/* (the event aborted before the deferred :db
;;   install — t2 fires only on a successful post-flow path) —
```

`:rf.event/db-changed` does NOT fire because the pending `:db` effect was discarded (no install, app-db unchanged, no partial commit); `:rf.fx/handled` does NOT fire because `:fx` is the post-install stage and the event aborted before it. This is the **same** truncated signature every other pre-install throw produces — a handler throw or an interceptor-`:after` throw emits `:rf.error/handler-exception` and then the `:rf.event/run-end` cascade-tail trailer, with no `:rf.event/db-changed` and no `:rf.fx/handled`. (As on the clean path, `:rf.event/run-end` is the **last** trace — the error event precedes the trailer.)

### Flow trace events

Five trace events constitute the flow lifecycle stream (per [013 §Flow tracing](013-Flows.md#flow-tracing)). All five carry `:op-type :flow`; consumers filter by `:op-type` to subscribe to the whole stream and branch on `:operation` to discriminate. Every event's `:tags` carries `:flow-id` and `:frame` so tools can attribute and route per-frame.

| `:operation` | When it fires | `:tags` payload (in addition to `:flow-id` and `:frame`) |
|---|---|---|
| `:rf.flow/registered` | After `reg-flow` (or `:rf.fx/reg-flow`) successfully registers a flow against a frame, including post-cycle-detection. | `:inputs` (the flow's input paths), `:path` (the flow's output path) |
| `:rf.flow/computed` | A flow's `:output` fn ran and the result was assoc-in'd into the **pending `:db` effect** at `:path` (the outermost-`:after` flow transform — before `:db` installs). Fires only when the dirty-check observed an input value-difference, and BEFORE `:rf.event/db-changed` (per [§Canonical per-event trace sequence](#canonical-per-event-trace-sequence)). Note: the trace records that the `:output` ran — if a LATER flow in the same drain throws, this write is discarded by the event abort (the trace is observational, not a commit guarantee). | `:input-values` (raw values read from the input paths), `:result` (the new output value), `:path`, `:before` (the value at `:path` immediately before this write), `:elapsed-ms` (the dev-only wall-clock of the `:output` recompute — the per-op DURATION the Trace panel reads) |
| `:rf.flow/skip` | The dirty-check found inputs `=`-equal to the previous run; the recompute was suppressed (per [013 §Dirty-check semantics](013-Flows.md#dirty-check-semantics) and value-equal recompute suppression). | `:reason` (currently `:inputs-value-equal`; the keyword is open for future skip reasons), `:input-paths-unchanged` (the flow's input db-paths whose values were stable — for a value-equal skip every input is stable by definition, so this names the full input set; consumed by the cascade-DAG aggregator). |
| `:rf.flow/cleared` | After `clear-flow` (or `:rf.fx/clear-flow`) removes the flow from the per-frame registry and dissoc-in's its output path. | `:path` (the path that was vacated) |
| `:rf.flow/failed` | The flow's `:output` fn threw during recompute. The exception is re-thrown after this trace fires so the router's outer catch emits the cascade-level `:rf.error/flow-eval-exception` (per [§Error contract](#error-contract)); tools see the per-flow detail here and the cascade abort there. Per [013 §Failure semantics](013-Flows.md#failure-semantics) (the atomicity contract), a flow throw is a pre-install throw: the event ABORTS — the pending `:db` effect is discarded (no install, app-db unchanged, no `:rf.event/db-changed`), `:fx` is skipped, and `last-inputs` is rolled back so every flow re-attempts next drain. No partial commit — neither the handler's `:db` nor any prior flow's write lands. | `:ex` (the exception), `:inputs` (the input values that were read just before the throw) |

Payload-shape decisions:

- **`:input-values` / `:result` are the actual values, not hashes.** The trace surface is dev-only (per [§Production builds](#production-builds-zero-overhead-zero-code)) and downstream tools — Xray's flow panel, custom dashboards — display the values. Hashing would force consumers to consult an out-of-band side table; raw values keep the stream self-contained.
- **`:rf.flow/skip` carries `:reason :inputs-value-equal`** rather than always being implicit. The keyword is the future extension point if additional skip reasons land (e.g. flow disabled mid-walk, frame in restore).
- **`:rf.flow/failed` re-throws** so cascade-level error-handling (Spec 009 §Error contract's `:rf.error/flow-eval-exception`) still fires; the per-flow `:rf.flow/failed` adds the per-flow attribution.

Pair-shaped tools and Xray's flow panel filter `op-type :flow` (per [Tool-Pair §How AI tools attach](Tool-Pair.md#how-ai-tools-attach)) to subscribe to the whole stream.

## Subscription / consumption

re-frame2's trace API uses **synchronous, event-at-a-time delivery** — every registered listener is invoked once per emitted trace event while the runtime is still on the emit call stack. Listener-invocation order is **not contract**; tools must not depend on the order in which sibling listeners receive a given event. There is no batching, debounce window, or background delivery loop. Listeners SHOULD do minimal work in the callback (queue, append to a buffer, mark a flag) and defer expensive work to a separate timer or animation frame they own.

### The listener API

The canonical listener API has one shape:

```clojure
(rf/register-listener! key callback-fn)
;; Subscribes callback-fn to receive every trace event as it is emitted.
;; Same key replaces any previously-registered listener under that key.
;; Returns the key.
;;
;; Arguments:
;;   key         — any comparable value identifying the listener
;;                 (replaces same-key registration)
;;   callback-fn — invoked with one trace event per call.
;;                 Signature: (fn [trace-event] ...)

(rf/unregister-listener! key)
;; Unsubscribes the listener registered under key. Returns nil.

(rf/clear-listeners!)
;; Test-time helper: drops all registered raw-trace listeners atomically.
;; Returns nil. Used by `re-frame.test-support/make-reset-runtime-fixture` to
;; restore a clean listener registry between tests; ordinary application
;; code SHOULD use `unregister-listener!` per key. The same dev-only elision
;; rules apply (production builds drop the registry entirely).
```

Conventional keys: `:my-app/recorder`, `:my-app/timing-monitor`, etc.

**Re-registration semantics.** `register-listener!` called with a key already in the registry replaces the previous callback atomically — the swap from old to new happens between two emits, never mid-emit. No trace event is emitted for the replacement (the listener registry is itself dev-only metadata; mutating it does not feed the trace stream); no events delivered to the previous callback are re-delivered to the new one, and no events emitted after the swap are dropped. Hot-reload tools that re-register their listener on every code reload see exactly one stream of events with the swap point invisible to the runtime. The same semantics apply to `register-epoch-listener!` re-registration under an existing key.

**Worked example.** A minimal recorder that prints every error trace to the console:

```clojure
(rf/register-listener!
  :my-app/error-logger
  (fn [trace-event]
    (when (= :error (:op-type trace-event))
      (println (:operation trace-event)
               (-> trace-event :tags :reason)))))
```

The same pattern with `register-epoch-listener!` to log one assembled cascade per event:

```clojure
(rf/register-epoch-listener!
  :my-app/cascade-logger
  (fn [epoch-record]
    (println (:event-id epoch-record)
             "→" (count (:effects epoch-record)) "fx"
             "/" (count (:sub-runs epoch-record)) "sub-runs")))
```

#### `register-epoch-listener!` — assembled-epoch listener

Alongside the raw trace stream, the framework exposes a parallel **assembled-epoch listener** API. Where `register-listener!` delivers each raw event as it is emitted, `register-epoch-listener!` delivers one fully-assembled `:rf/epoch-record` (per [Spec-Schemas](Spec-Schemas.md#rfepoch-record)) per **dequeued event** — one per epoch (per [002 §Drain versus event](002-Frames.md#drain-versus-event--the-epoch-unit)):

```clojure
(rf/register-epoch-listener! key callback-fn)
;; Subscribes callback-fn to receive assembled epoch records.
;;
;; Arguments:
;;   key         — any comparable value identifying the listener
;;                 (replaces same-key registration)
;;   callback-fn — invoked with one :rf/epoch-record per dequeued event
;;                 (one per epoch). Signature: (fn [epoch-record] ...)
;;
;; The record is the same shape the runtime appends to (rf/epoch-history frame-id):
;; assembled :event-id / :trigger-event / :db-before / :db-after, plus the structured
;; :sub-runs / :renders / :effects projections derived from the cascade's traces.

(rf/unregister-epoch-listener! key)
;; Unsubscribes the listener registered under key.
```

**Invocation rules** (mirrors `register-listener!`):

- **Per dequeued event, not per drain.** The callback fires once per dequeued event — its full six-domino cascade (and, for a machine event, its entire macrostep) is one epoch (per [002 §Drain versus event](002-Frames.md#drain-versus-event--the-epoch-unit)). A drain that settles a parent event and the child it `:fx`-dispatched fires the callback **twice** — once per event — not once for the drain. A machine's `:raise` sub-events and `:always` microsteps do not fire it: they ride inside the triggering event's epoch (per [005 §Drain semantics](005-StateMachines.md#drain-semantics)). The callback also fires for halted events (`:halted-depth`, `:halted-destroy`; see [Spec-Schemas §`:rf/epoch-record` §Outcomes](Spec-Schemas.md#outcomes)), discriminated by `:outcome`.
- **After commit.** The callback receives a fully-formed record with `:db-after`, `:sub-runs`, `:renders`, `:effects`, and any optional `:trace-events` populated. The record has already been appended to the frame's `epoch-history` ring buffer when the callback runs.
- **Exception isolation.** An exception thrown by an epoch callback is caught and does not propagate. One broken epoch listener cannot break the app or block other listeners (raw-trace or epoch).
- **Listener ordering** is not contract.
- **Production elision.** The epoch listener machinery is gated on the same `re-frame.interop/debug-enabled?` flag (alias of `goog.DEBUG`) as the raw-trace surface — see [§Production builds](#production-builds-zero-overhead-zero-code). Production builds elide registration, dispatch, and the epoch ring-buffer all together.

**Halted cascades.** Listeners receive epoch records for halted drains as well as clean settles. `:outcome` on the record discriminates — `:ok`, `:halted-depth`, or `:halted-destroy`. The partial record carries whatever the runtime captured up to the halt point: `:trace-events`, `:sub-runs`, `:renders`, `:effects` reflect the cascade-so-far, and `:halt-reason` carries a structured descriptor of why the drain halted. This is the **devtools surface** for failing cascades — Xray's epoch panel, re-frame2-pair's `cascade-of`, post-mortem dashboards: all route off the same listener, and `:outcome` lets them render the failure with the right shape. Consumers that only care about successful drains filter on `(= :ok (:outcome record))` at the top of their callback. `restore-epoch` refuses non-`:ok` records — see [Spec-Schemas §`:rf/epoch-record` §Outcomes](Spec-Schemas.md#outcomes).

**When to use which.** `register-listener!` is the right shape for tools that need fine-grained per-event activity (custom recorders, error-monitor forwarders, timing aggregators). `register-epoch-listener!` is the right shape for tools that route diagnostics off "what just happened in this cascade" — pair-shaped tools, post-mortem dashboards, anything that wants the structured `:sub-runs` / `:renders` / `:effects` projection without re-folding the raw trace stream.

The two listener APIs are independent: tools may register either, both, or neither. They share the production-elision gate but have separate listener registries; no listener of one kind can interfere with the other.

### Cascade projection (`group-cascades` / `domino-bucket`)

The raw trace stream is event-at-a-time; pair-shaped UIs (the Story trace panel, the Xray Epoch panel, re-frame2-pair's `cascade-of`) all want the **six-domino slice** of the stream — one record per cascade with the event vector, handler emit, fx-map emit, effects, sub-runs, and renders already split into named slots. The framework ships that projection as a pure-data function in `re-frame.trace.projection`, re-exported from `re-frame.core`:

```clojure
(rf/group-cascades trace-events)
;; -> [{:dispatch-id        <id-or-:ungrouped>
;;      :parent-dispatch-id <id or nil>      ;; causal-parent link from
;;                                           ;;   :rf.trace/parent-dispatch-id
;;                                           ;;   on the :rf.event/dispatched trace
;;                                           ;;   — the cascade that emitted this
;;                                           ;;   dispatch (an :fx :dispatch parent,
;;                                           ;;   a machine-internal dispatch, etc.);
;;                                           ;;   nil for a root / external dispatch
;;                                           ;;  
;;      :frame              <frame-id or nil> ;; the cascade's host frame, per
;;                                           ;;   002-Frames §Routing the dispatch
;;                                           ;;   envelope; nil for an :ungrouped
;;                                           ;;   slot or a cascade with no frame
;;                                           ;;   tag in any of its events
;;      :event              <event-vector | nil> ;; from :rf.event/dispatched :tags
;;                                              ;;   (the slim, common-case form)
;;      :dispatched         <trace-event | nil>  ;; the FULL :rf.event/dispatched
;;                                              ;;   trace event — preserves
;;                                              ;;   top-level hoisted slots
;;                                              ;;   (:rf.trace/call-site, :source,
;;                                              ;;   :origin) per 
;;      :handler            <trace-event | nil>  ;; :rf.event/run-start | :rf.event/run-end
;;                                              ;;   (last wins — typically :run-end)
;;      :fx                 <trace-event | nil>  ;; :rf.fx/do-fx
;;      :effects            [<trace-event> ...]  ;; :op-type :rf.fx — :rf.fx/handled,
;;                                              ;;   override-applied,
;;                                              ;;   skipped-on-platform
;;      :subs               [<trace-event> ...]  ;; :rf.sub/run + :rf.sub/skip
;;                                              ;;   + :rf.sub/create
;;      :renders            [<trace-event> ...]  ;; :op-type :rf.view /
;;                                              ;;   :operation :rf.view/render
;;      :other              [<trace-event> ...]} ;; errors, warnings, machines,
;;                                              ;;   frames, flows, registry,
;;                                              ;;   anything outside the six dominoes
;;     ...]
```

The slot order above is the projection's emit order. Each slot's wire-shape and semantics are documented alongside the per-frame ring's read surface at [Tool-Pair.md §Reading the per-frame trace ring](Tool-Pair.md#reading-the-per-frame-trace-ring--cascade-bundles--flat-opt-in) — the same cascade-bundle shape `(rf/trace-buffer frame-id)` returns by default, since the ring pre-computes this projection per-cascade. (Pair tools assembling the same projection from an `epoch-history` record route off `:rf/epoch-record`'s `:sub-runs` / `:renders` / `:effects` slots per [Spec-Schemas §`:rf/epoch-record`](Spec-Schemas.md#rfepoch-record); those structured slots derive from the same `:trace-events` stream this projection groups.)

(The cascade-record slot names above — `:dispatch-id`, `:parent-dispatch-id`, `:frame`, `:event`, `:dispatched`, `:handler`, … — are the projection's own output shape, distinct from the trace `:tags` keys it groups by.) Events without a `:rf.trace/dispatch-id` tag (registry-time emits, frame lifecycle, REPL evals outside a drain) collect under the projection's `:dispatch-id :ungrouped` slot. The returned vector is sorted by the lowest `:id` in each cascade so consumers render cascades in emission order. The projection is **pure data** — JVM and CLJS run the same code; tools wiring up post-mortem renders against `(rf/trace-buffer)` get the same output shape as live consumers reading from a `register-listener!` listener.

`(rf/domino-bucket trace-event)` is the underlying classifier — returns one of `#{:event :handler :fx :effect :sub :render :other}`. Tools that want custom rollups can call it directly per event and skip `group-cascades`.

Per (per-event correlation) the projection is robust against errors, fx, sub-runs, and renders that fire *inside* an event's cascade even though they aren't `:rf.event/dispatched` — every such event carries `:tags :rf.trace/dispatch-id` so they group into that event's cascade record automatically.

The projection is additive: new `:op-type` values that don't fit a domino slot flow through `:other` without breaking existing consumers.

### Listener invocation rules

- **Synchronous, event-at-a-time.** Every registered listener is invoked once per emitted trace event, on the runtime's emit call stack. There is no batching, debounce window, or background delivery loop. Listeners SHOULD return quickly; expensive work belongs on a tool-owned timer or rAF.
- **Events arrive in emission order.** Each listener sees trace events in the order the runtime fired them. (This is about per-listener *event* order, not order *across* listeners — see the next rule.)
- **Listener-invocation order is not contract.** When multiple listeners are registered, the order in which sibling listeners receive a given event is unspecified. Tools must not depend on order; each listener receives the same event independently. The same rule applies to `register-epoch-listener!` callbacks.
- **Exception isolation.** An exception thrown by a listener is caught and does *not* propagate to the framework or other listeners. One broken tool can't break the app or block other tools. The caught exception is logged via `re-frame.interop/log-error` (or the host equivalent) and otherwise discarded; the runtime does NOT emit a self-referential trace event for the failed listener (which would risk a re-entrant trace-emit storm). The same handling applies to exceptions thrown by an `register-epoch-listener!` callback.
- **No buffering between listeners and the runtime.** The framework does not retain a delivery buffer; the per-frame trace rings described next are independent and exist for late-attaching tools.

### Per-frame trace rings (cascade-keyed, dev-only)

In dev builds, **each frame owns its own trace ring** alongside the synchronous-delivery path. The ring's **unit of retention is the cascade** (one dispatched event = one cascade = one slot, keyed by `:rf.trace/dispatch-id`), not the individual trace event. This lets pair-shaped AI tools, REPL-attached debuggers, and post-mortem dashboards read recent activity from the frame they care about without having to be registered as a `register-listener!` listener at the time the events fire — and without their reads being polluted by trace volume from other frames.

This is the per-frame model joining what frames already own — app-db, epoch-history, the sub-cache reactive context, fx/cofx routing — extended one rung further: the trace surface, too, is partitioned by frame, with cascade-keyed eviction.

#### Architectural shift — cascade-keyed, per-frame

The design was a single process-global ring sized by raw trace-event count (default 200 events). That design had two structural problems:

1. **Frame mismatch.** Tools that mount in their own frame (Xray, re-frame2-pair-mcp, story-mcp, any future inspector) emit trace events whose volume swamps every other frame's view of the same buffer. Sub-recompute storms in an inspector's reactive substrate could evict every *application* event from the global ring within a few microtask cycles — observed under and (the motivating bug).
2. **Eviction unit mismatch.** Trace volume per cascade is wildly uneven — a single click-cascade through a machine entry can emit hundreds of `:rf.sub/skip` short-circuit events alongside the handful of `:run-start` / `:run-end` / `:db-changed` / `:fx/handled` "real" events. A flat ring sized by event count means one chatty cascade can evict every earlier cascade's traces (including its own real events) before a consumer reads them.

The fix is two compositional structural changes:

- **Per-frame ring.** Every frame owns an independent ring; emit-site routing places each trace event in the frame whose reactive chain or event-cascade is running (see [§Emit-site routing](#emit-site-routing-per-frame) below). Each frame's ring is sized independently via frame metadata. Frames are isolated by design (per [002 §Per-frame and trace surface](002-Frames.md#per-frame-and-trace-surface)); the trace surface now matches.
- **Cascade-keyed eviction.** The ring slot is the cascade. One `:rf.trace/dispatch-id` consumes one slot regardless of how many trace events that cascade emitted. When cascade #N+1 arrives, the oldest cascade (and every trace event ever emitted under it) is dropped as a unit. A cascade with 5 traces and a cascade with 50,000 traces each occupy one slot.

#### Retention contract — the single knob `:rf.trace/cascades-retained`

| API | Default | Notes |
|---|---|---|
| `:rf.trace/cascades-retained` (frame metadata) | **50** | Per-frame override. Sets the number of cascade slots retained in this frame's ring. `0` disables the ring (synchronous delivery still works). |
| `(rf/configure :trace-buffer {:cascades-retained N})` | applies to `:rf/default` | Process-default tuner — applied to frames that did not set per-frame metadata. |

**This is the entire retention surface.** There is no per-cascade trace cap, no per-trace-type cap (no `:skip`-specific budget, no `:sub`-specific budget), no per-frame override beyond the cascade count, no other knobs. Operator-facing tuning is one number: how many cascades does this frame keep?

Rationale:

- **Matches operator mental model.** Operators think "I want to see my last 50 events"; storage matches the unit they think in.
- **Predictable retention.** A chatty sub never silently evicts the cascade the operator cares about. Only newer cascades push older cascades out.
- **Naturally bounds skip noise.** Sub-skip emits stay associated with their parent cascade; the burst lives or dies as a unit with the rest of that cascade's traces.
- **Aligns with epoch-history.** `epoch-history` is already retained per-cascade (one assembled `:rf/epoch-record` per dispatch). The trace ring becomes "the diagnostic detail of the same 50 epochs" — consistent retention semantic across the per-frame model.
- **No per-trace-type tuning needed.** Eviction is cascade-driven; trace volume per cascade is incidental. A cascade with 50K traces is a slot like any other.

There is **no worst-case memory bound on a single cascade's trace volume by design.** If a cascade emits 100K traces, that cascade's slot holds 100K traces until evicted. The operator's tradeoff is in the cascade-count knob, not in trace-volume bounding. (Apps whose cascade-volume budget genuinely matters tune the per-frame cascade-count downward; this is the same control surface the global ring exposed, projected onto the new unit.)

#### Emit-site routing (per-frame)

Trace events that ride **inside** an in-flight cascade are routed to the frame whose router is processing that event. Concretely:

- The runtime carries the in-flight frame's id through `re-frame.trace/*handler-scope*` (alongside `:rf.trace/dispatch-id`, per [§Handler-scope](#handler-scope-the-in-scope-reading-at-emit-time)). `emit!` reads the slot to look up the destination ring.
- For trace events emitted from inside a frame's drain loop, fx-pass, or epoch-settle path (`:rf.event/dispatched`, `:rf.event/run-start`, `:rf.event/db-changed`, `:rf.fx/handled`, `:rf.machine/*`, `:rf.flow/*`, every `:rf.error/*` thrown inside the cascade), the destination frame is the one whose router is running — the same frame the trace event's `:frame` tag already carries.
- For trace events emitted from inside a sub's reactive recompute (`:rf.sub/run`, `:rf.sub/skip`), the destination frame is the one whose reactive chain is running — Spec 006's per-frame `sub-cache` already runs each sub against its own frame, so the per-frame ring inherits that boundary. Cross-frame sub composition is an anti-pattern (per Spec 002); subs do not reach across frames, so trace events from a frame's reactive substrate stick to that frame's ring.
- For trace events emitted from inside a view render (`:rf.view/render`, `:rf.view/rendered`, `:rf.view/unmounted`), the destination frame is the frame the view is bound to — carried on `:frame` and on the in-flight handler scope set up by the registered-view wrapper.

#### Cross-frame cascades — merge by `:dispatch-id`

Frames are isolation boundaries, not communicating agents — re-frame2 does not support one frame dispatching directly into another; the cascade of a dispatched event lives in exactly one frame. But several distinct cascades may share the same `:dispatch-id` family when consumers correlate across rings (e.g. a pair-mcp client watching every frame, an off-box trace dashboard merging cascades from a multi-frame story session).

The contract: **each frame retains the traces of cascades that EXECUTED IN IT**, keyed by their own `:rf.trace/dispatch-id`. Cross-frame consumers (pair-mcp, monitoring tools, the Xray off-box rendering tier) merge by `:dispatch-id` across rings to reconstruct a multi-frame timeline. The framework does not maintain a process-global "cross-frame index"; the merge is the consumer's job, and it is cheap because each ring already keys by `:dispatch-id`.

#### Frameless trace events — live stream only (no ring storage)

Some trace events ride **outside** any in-flight cascade — registration-time `:rf.registry/handler-registered` / `:rf.registry/handler-replaced` / `:rf.registry/handler-cleared` emits, `:rf.frame/created` / `:rf.frame/destroyed` lifecycle, REPL evals that don't dispatch, schema-validation warnings emitted at namespace load. These events have no `:rf.trace/dispatch-id` and no destination frame.

**B3+B4 ruling: frameless events SKIP the rings entirely.** They stream live to registered listeners (`register-listener!`) only; they are NEVER retained in any ring.

- **No frameless ring is allocated.** There is no `:rf/global` ring, no shared "uncorrelated bucket", no fallback slot for frameless emits. The ring exclusively holds cascades.
- **The live stream is the egress channel.** Tools that care about registration drift, hot-reload diagnostics, or REPL-eval emits subscribe to the live stream via `register-listener!` and filter by `:op-type` / `:operation` themselves. Per [§The listener API](#the-listener-api), the live stream is synchronous and event-at-a-time.
- **The registry is the source of truth for "what's registered right now".** Tools reading "the current set of registered handlers / frames / routes" consult `(rf/registrations kind)` (per [001 §The query API](001-Registration.md#the-query-api)) — not by scanning ring contents. This collapses a class of duplication: the ring need not double-bookkeep registration state.

##### Hot-reload dedup — re-emits suppressed by shape

The original (now-overturned) `:rf/global` ring proposal was rejected during the design phase because hot-reload at scale creates a memory leak: every file save re-fires the entire ns-load worth of `reg-*` traces, the ring grows without bound, and the operator never benefits from the noise (the registrations haven't *changed*). The B3 ruling alone (no ring) closes the memory leak, but it leaves the live stream itself flooded with reload-noise that no consumer wants.

**B4 ruling: hot-reload re-emits are deduplicated by shape at the emit site.** The registrar tracks the last-emitted *shape* per `(kind, id)` pair and suppresses re-emits whose shape is unchanged. The emitter compares:

- The registration's handler-fn identity (or source-coord, per the dedup mechanism's choice).
- The metadata-map's content (`:doc`, `:schema`, `:interceptors`, `:tags`, etc.).

Identical shape → re-emit suppressed; no trace event fires. Changed shape (a real edit to the handler or its metadata) → exactly one trace fires (`:rf.registry/handler-replaced`). Hot-reload of an unchanged file is a **non-event** for the trace bus.

The dedup applies symmetrically to `register` / `replace` / `clear`:

- A `register` for an id with no prior emit always fires.
- A `replace` whose new shape matches the last-emitted shape is suppressed.
- A `clear` of an id that the dedup table thinks is already absent (e.g. a double-clear) is suppressed.

The dedup table is process-scoped, dev-only (it sits inside the same `interop/debug-enabled?` gate as the rest of the trace surface), and is implicitly cleared by `clear-listeners!` / test-runtime reset fixtures. The mechanism is per-`(kind, id)` — different ids hot-reload independently; the dedup of `:user/login` does not interfere with the dedup of `:user/logout`. Per (B4 ruling, 2026-05-25).

Together (B3 + B4): rings hold cascades exclusively; the live stream filters reload-noise via shape-dedup; the registry is the source of truth for "what's registered right now". Tools wanting "the current state of the world" read `registrations`; tools wanting "what changed in the last cascade" read the per-frame ring; tools wanting fine-grained event-by-event observation register a listener and receive the (post-dedup) live stream.

#### `trace-buffer` API — per-frame, cascade bundles by default

The query surface is per-frame, with a frame-id required argument. The default return shape is **cascade bundles** (one map per cascade with the cascade's `:dispatch-id`, its trace events, and the structured projection slots); the `:flat` opt-in returns raw trace events for callers that want the pre-cascade shape.

| API | Signature | Notes |
|---|---|---|
| `(rf/trace-buffer frame-id)` | `(frame-id) → vector` | Returns the frame's cascade-bundle vector, oldest-first. Each entry is `{:dispatch-id <id> :parent-dispatch-id <id or nil> :frame <frame-id or nil> :event <event-vector or nil> :dispatched <trace-event or nil> :handler :fx :effects :subs :renders :other :trace-events [...]}` — the `group-cascades`-shape projection per [§Cascade projection](#cascade-projection-group-cascades--domino-bucket), pre-computed per-cascade by the ring. Empty when no cascades have been recorded. |
| `(rf/trace-buffer frame-id opts)` | `(frame-id, opts) → vector` | Optional filter map (see [§Filter vocabulary](#filter-vocabulary) below). Filters compose AND-wise across cascade-level fields; absent key = no constraint. The `:flat true` opt returns raw trace events instead of cascade bundles (escape hatch — see below). |
| `(rf/clear-trace-buffer! frame-id)` | `(frame-id) → nil` | Empties the named frame's ring. Tooling uses this between sessions. |
| `(rf/configure :trace-buffer {:cascades-retained N})` | `(opts) → nil` | Process-default ring depth (applies to `:rf/default` and any frame that did not set per-frame metadata). |

The `:flat` opt is the escape hatch for callers that want the pre-cascade raw stream:

```clojure
(rf/trace-buffer :step-deck)
;; → [{:dispatch-id 17 :parent-dispatch-id nil :frame :step-deck
;;     :event [:user/click ...] :dispatched {...}
;;     :handler {...} :fx {...} :effects [...] :subs [...] :renders [...] :other [...]
;;     :trace-events [...]}
;;    {:dispatch-id 18 :parent-dispatch-id 17 :frame :step-deck
;;     :event [...] :dispatched {...} ...}
;;    ...]

(rf/trace-buffer :step-deck {:flat true})
;; → [{:operation :rf.event/dispatched :tags {:rf.trace/dispatch-id 17 ...} ...}
;;    {:operation :rf.event/run-start  :tags {:rf.trace/dispatch-id 17 ...} ...}
;;    {:operation :rf.sub/skip         :tags {:rf.trace/dispatch-id 17 ...} ...}
;;    ...]
```

The default (cascade bundles) matches the storage unit; tools whose existing code shapes around the raw stream pass `{:flat true}` and re-fold via `group-cascades` themselves. (The `:flat` form is **not** a "polyfill of the old surface" — the storage is genuinely cascade-keyed, so even `:flat` reads the ring's per-cascade slots and flattens them; it is not reading a pre-cascade flat ring.)

#### Filter vocabulary

`(rf/trace-buffer frame-id opts)` recognises the following filter keys. All compose AND-wise; an absent key means "no constraint on that axis." Unrecognised keys are ignored (forward-compat: tools may probe new axes; missing support degrades to "no filter").

| Key | Type | Semantics |
|---|---|---|
| `:flat` | `true` | Return raw trace events instead of cascade bundles. The other filter keys apply to events when `:flat true`, otherwise to cascades. |
| `:operation` | keyword | (`:flat`-only) Match exact `:operation` value (e.g. `:rf.event/dispatched`, `:rf.fx/handled`). |
| `:op-type` | keyword | (`:flat`-only) Match exact `:op-type` discriminator (e.g. `:rf.event`, `:rf.fx`, `:error`). |
| `:since` | number | (`:flat`-only) Keep events whose `:id` is strictly greater than this. Cursor-based polling — read the last event's `:id`, pass on next call. |
| `:severity` | `:error` / `:warning` / `:info` | (`:flat`-only) Synonym for `:op-type` restricted to the three severity tiers. |
| `:event-id` | keyword | Match `:tags :rf.trace/event-id` (the first element of the dispatched event vector, e.g. `:user/login`). For cascade-bundle reads, matches cascades whose `:event` first element is this id; for `:flat`, matches per-event. |
| `:handler-id` | keyword | (`:flat`-only) Match `:tags :handler-id`. Present on handler-error emits. |
| `:source` | `:ui` / `:timer` / `:http` / `:repl` / `:machine` / `:ssr-hydration` | (`:flat`-only) Match the top-level `:source` slot. |
| `:origin` | `:app` / `:pair` / `:story` / `:test` / ... | Match `:tags :rf.event/origin`. For cascade-bundle reads, matches cascades whose root `:rf.event/dispatched` carries this origin. |
| `:dispatch-id` | number | Match the cascade's `:dispatch-id` (cascade-bundle reads) or `:tags :rf.trace/dispatch-id` (`:flat`). |
| `:since-ms` | number | Keep cascades / events whose `:time` (host-clock ms) is strictly greater than this. |
| `:between` | `[t0 t1]` | Two-element vector — keep cascades / events whose `:time` falls in `[t0, t1]` inclusive. |
| `:pred` | `(fn [ev-or-cascade] → truthy)` | Arbitrary predicate. Receives the cascade bundle (or raw event when `:flat true`). Returning truthy keeps the entry. Escape hatch for filters not yet promoted to named keys. |

Filters compose AND-wise — supplying both `:op-type :error` and `:flat true` keeps only error events. For cascade-bundle reads, cascade-level keys (`:event-id`, `:origin`, `:dispatch-id`, `:between`, `:pred`) apply; event-level keys (`:operation`, `:op-type`, `:severity`, `:handler-id`, `:source`) require `:flat true`.

#### Semantics

- **Ring discipline — cascade-keyed.** When the ring is full at `:rf.trace/cascades-retained` slots, the oldest cascade slot (and every trace event ever emitted under its `:dispatch-id`) is evicted as a unit as the new cascade arrives. No allocation churn beyond the slot count.
- **Per-frame isolation.** Each frame's ring is independent — a burst of `:rf.sub/skip` cascades in `:rf/xray` does not touch `:step-deck`'s ring. Tools that mount in their own frame (Xray, re-frame2-pair-mcp, story-mcp) see their own ring; the app frame they observe sees its own ring; cross-frame consumers merge by `:dispatch-id` across rings.
- **Same events as delivery.** Every event delivered to listeners also lands in its frame's ring (when in-cascade). Ring-buffer events are the same maps the listeners receive.
- **Frameless events bypass the ring.** Per B3+B4 above, frameless trace events never land in any ring; they stream live to listeners only.
- **Independent of listeners.** A tool that attaches *after* events have fired can read the most-recent N cascades from the ring to bootstrap its view; a tool that wants a continuous live feed registers a `register-listener!` listener as well.
- **Production elision.** The ring, like the rest of the trace surface, is compile-time eliminated in production builds (per [§Production builds](#production-builds-zero-overhead-zero-code)). `(rf/trace-buffer frame-id)` returns an empty vector in production, and the ring itself is not allocated.
- **Cascades-retained-zero semantics.** When configured with `{:cascades-retained 0}`, the ring is disabled but the surface remains live: `(rf/trace-buffer frame-id)` returns `[]`, `(rf/trace-buffer frame-id opts)` returns `[]`, and `(rf/clear-trace-buffer! frame-id)` is a no-op (returns `nil`). Synchronous-delivery to registered listeners continues to fire — only the queryable history is suppressed.
- **Lowering cascades-retained on a populated ring.** Applied while the ring holds more than `N` cascades, drops the oldest cascades first to fit (same eviction order as the ring discipline). Raising it keeps existing cascades and grows the slot count.
- **Reads against a destroyed / missing frame.** `(rf/trace-buffer <unknown-frame-id>)` returns `[]` (parity with `(rf/get-frame-db <unknown>)` returning `nil` and the destroyed-frame read posture in [Tool-Pair §Surface behaviour against destroyed frames](Tool-Pair.md#surface-behaviour-against-destroyed-frames)).

#### Rejected alternatives

For context — the design space the cascade-keyed per-frame ring won out against:

- **"Don't emit sub-skip trace events at all."** Sub-recompute short-circuit signal is useful diagnostic output (performance work, invalidation chasing). The fix preserves the emit by routing to the right frame's ring and bundling with the parent cascade.
- **"Filter `:skip` at the consumer level."** Too late — by the time a consumer reads the global ring, the real events the operator cares about are already evicted by the `:skip` flood. Filter-at-read is structurally late; the fix has to happen at INGEST or at the storage-partition level.
- **"Bigger flat ring (10× the current default)."** Procrastinates the architectural mismatch without fixing it. A bigger ring still gets polluted by tool-frame traces and still has no eviction semantic that respects cascade boundaries.
- **"Frameless events go to a `:rf/default` cluster (the `:rf/default` + `nil` slot)."** Original Q2 design (Mike ruled B during, then **AMENDED to B3+B4** the same day). The cluster proposal had a hot-reload memory leak: every file save re-fires the entire ns-load worth of `reg-*` traces, the cluster grows without bound, the operator never benefits from the noise. B3+B4 fixes both halves: B3 keeps the ring exclusively for cascades (no leak surface), B4 deduplicates re-emits by shape (no live-stream noise either).

Why this is a framework primitive (not a Xray-specific concern): pair-shaped tools, REPL companions, and any non-Xray consumer needs recent-history access. Locating the rings in the framework — keyed by the per-frame model that already exists — means external tools depend on a stable framework primitive rather than on Xray's internal data structures. See [Tool-Pair §How AI tools attach](Tool-Pair.md#how-ai-tools-attach) for the full consumption pattern.

**Topology note.** The public-tooling surface — `register-listener!` / `unregister-listener!` / `clear-listeners!` / `trace-buffer` / `clear-trace-buffer!` / `configure-trace-buffer!` / `configure` — and the per-frame ring + listener state live in the sibling `re-frame.trace.tooling` namespace, not `re-frame.trace` itself. `re-frame.trace` carries the always-loaded hot fast path (`emit!` / `emit-error!` / `*handler-scope*`); the tooling sibling is loaded only when a test fixture, tool (Xray / Story / re-frame2-pair-mcp), or dev preload `:require`s it. The `rf/...` public Vars and the `re-frame.trace/<surface>` wrappers delegate via the `:trace.tooling/*` late-bind hooks so existing consumer call sites are unchanged. On the JVM the tooling sibling is autoloaded by `re-frame.trace` (zero bundle cost off-bundle). On CLJS the tooling sibling is omitted from production counter bundles — the hook lookups return nil and the wrappers no-op (DCE drops the body wholesale, ~2 KB raw / ~600 B gzipped saved).

## Emitting trace events

The framework emits trace events through one entry point: `re-frame.trace/emit!`. User code may also call it (re-exported as `rf/emit-trace-event!`) to add custom events to the stream.

```clojure
(re-frame.trace/emit! op-type operation tags)
;; Emits one trace event with the given :op-type / :operation / :tags.
;; Returns nil. The runtime stamps :id and :time, hoists :source and
;; :recovery (when present in tags) to the top level, routes the event
;; into the in-flight frame's per-frame ring (cascade-keyed; frameless
;; emits bypass the ring per the B3 ruling), and synchronously invokes
;; every registered listener.
```

The shape is synchronous and side-effecting: the emit returns once every listener has been invoked. There is no span-shape machinery — events are emitted at the moment of interest with all relevant tags already populated. (For codebases migrating from a span-shaped tracing library, see [MIGRATION.md §M-26](../migration/from-re-frame-v1/README.md#m-26-drift-sweep-drops--v1-surfaces-with-no-v2-equivalent-or-absorbed-by-canonical-surfaces).)

### Compile-time elision

`emit!`'s body is wrapped in `(when re-frame.interop/debug-enabled? ...)`. `debug-enabled?` is an alias of `goog.DEBUG` on CLJS (default `true` in dev, `false` in `:advanced` production builds); when the constant is `false` the closure compiler eliminates the gated branch and the call becomes a no-op. See "Production builds" below for the full mechanism.

### Trace-emission opt-out: `:rf.trace/no-emit?` event-meta

Handlers (`reg-event-db` / `reg-event-fx` / `reg-event-ctx`, `reg-sub`, `reg-fx`, `reg-cofx`, view registrations) whose registration metadata carries `:rf.trace/no-emit? true` produce **no trace events**. The runtime short-circuits `emit!` / `emit-error!` / the queue-time `:rf.event/dispatched` emit when the in-scope handler — or, for `:rf.event/dispatched`, the target handler — opts out. The runtime publishes the handler's `:no-emit?` reading via the `:no-emit?` slot of `re-frame.trace/*handler-scope*` (alongside `:trigger-handler` and `:sensitive?`, per [§Handler-scope](#handler-scope-the-in-scope-reading-at-emit-time)); the gate sits inside the outer `interop/debug-enabled?` `when` so production elision is preserved.

```clojure
(rf/reg-event-db :rf.xray/note-trace-event
  {:rf.trace/no-emit? true}                     ;; <- opt-out
  (fn [db [_ event]]
    (assoc db :trace-buffer (conj (:trace-buffer db []) event))))
```

The flag is the framework-level escape hatch for **trace-consuming integrations** whose own bookkeeping dispatches — emitted from inside a registered `trace-cb` — would otherwise re-enter the consumer through the trace-cb fan-out and form a cb-dispatch loop. Xray, Story, re-frame2-pair-mcp, and story-mcp all have the same risk shape; without the opt-out each consumer would need its own per-dispatch guard predicate (Xray carried one as `trace-bus/self-emitted?` between and). Promoting the gate to the framework lets any consumer mark a handler internal-only and trust the runtime to suppress the cascade.

Semantics:

- **What's suppressed.** `:rf.event/dispatched` (queue-time, when the *target* handler's meta carries the flag), the cascade run markers `:rf.event/run-start` / `:rf.event/run-end`, `:rf.event/db-changed`, `:rf.fx/handled`, `:rf.machine/transition`, `:rf.sub/run`, `:rf.view/render`, and every `:rf.error/*` emit produced inside the handler's scope. The always-on event-emit substrate (``) ALSO honours the flag and drops the per-event record for `:rf.trace/no-emit?`-flagged handlers — same boundary semantics as the `:sensitive?` short-circuit, on the rationale that framework-internal bookkeeping handlers are not user-domain observable signal.
- **What's NOT suppressed.** The handler body still runs — the opt-out applies to OBSERVABILITY (trace + event-emit), not handler execution. The dispatch is queued, drained, and committed normally; the handler's db effect is committed; its fx are walked.
- **Cascade composition.** Innermost in-scope handler wins. A non-opt-out handler dispatched from inside a `:rf.trace/no-emit? true` handler emits normally — the inner binding rebinds to false and the inner cascade is visible. (Same composition rule as `:sensitive?`, per Spec 009 line 1177.)
- **Production elision.** The trace-surface gate sits inside `interop/debug-enabled?` and DCEs out in `:advanced` production builds (the trace surface is dev-only by construction — production never emits trace events at all). The event-emit short-circuit survives production builds (event-emit is always-on), so production listeners equally drop opt-out handler records.

and the framework `re-frame.trace/*handler-scope*` Var's `:no-emit?` slot (per [§Handler-scope](#handler-scope-the-in-scope-reading-at-emit-time)).

### Frame-level trace-emission opt-out: `:rf.trace/frame-no-emit?` frame-config

A frame registered with `:rf.trace/frame-no-emit? true` produces **no trace events**: `emit!` / `emit-error!` short-circuit (no envelope allocation, no delivery) for any event whose `:frame` tag matches a frame so marked. This is the frame-scoped sibling of the handler-scoped `:rf.trace/no-emit?` above — the same suppression boundary, keyed on the frame rather than the in-scope handler.

```clojure
(rf/reg-frame :rf/xray {:rf.trace/frame-no-emit? true})   ;; <- tool / inspector frame
```

The flag is the framework-level escape hatch for **inspector tools** (Xray, Story, re-frame2-pair) that render their own UI inside a dedicated frame. That UI's reactive substrate emits `:sub/run` + `:view/render` on every panel render; because the retain-N ring is process-global, an inspector's self-instrumentation would otherwise evict every *application* event from the buffer it inspects (observed under : the ring held 200/200 `:rf/xray` events and zero app events, so any other raw-buffer consumer saw only inspector noise). Marking the tool frame trace-disabled means tool frames produce no trace at all, while application frames are unaffected.

Semantics:

- **What's suppressed.** Every trace + error emit tagged with the marked frame — `:rf.event/dispatched`, `:rf.event/run-start` / `:rf.event/run-end`, `:rf.event/db-changed`, `:rf.sub/run`, `:rf.view/render`, `:rf.frame/created` for the frame itself, and every `:rf.error/*` whose `:frame` is the marked frame. The framework's emit sites already thread `:frame` onto these tags, so the gate keys on `(:frame tags)`.
- **Mechanism (one canonical predicate).** `re-frame.trace/frame-trace-disabled?` is the single source of truth; `reg-frame` reads the config flag and calls `re-frame.trace/set-frame-no-emit!`. No call site hardcodes a frame id (e.g. `:rf/xray`). Honoured on first registration *and* surgical re-registration so a hot-reload can flip it either way.
- **Production elision.** The gate sits inside the same `interop/debug-enabled?` `when` as the rest of the emit substrate, so it DCEs out of `:advanced` production builds (which emit no trace at all).

Per.

### Where trace emission lives

The framework emits trace events from these call sites:

- `events.cljc` — `:warning :rf.warning/interceptors-in-metadata-map`; `:rf.error/effect-map-shape`; `:rf.error/effect-handler-bad-return`.
- `subs.cljc` — `:rf.sub/create`, `:rf.sub/run` (the **pure `compute-sub`** form — base shape only, no value-change/cascade attribution; see the `:rf.sub/run` op-type entry above); `:rf.error/no-such-sub` and `:rf.error/sub-exception` for failure paths.
- `subs/memo.cljc` — `:rf.sub/run` per **true recompute** on the reactive path, enriched with value-change + cascade attribution (`:rf.sub/value-changed?` / `:rf.sub/prev-value` / `:rf.sub/value` / `:rf.sub/cascade?` / `:rf.sub/cause-sub`; dev-only; the wire-value slots `:rf.sub/prev-value` / `:rf.sub/value` are emitted raw and redacted downstream by the `re-frame.marks/project-sub-tags` chokepoint — NOT by `elide-wire-value`, whose container deref would break glitch-free reaction layering; and the `:rf.sub/run` op-type entry above); `:rf.sub/skip` per memo-hit (input value-equal to last-seen → user body suppressed; and [Spec 006 §Invalidation algorithm](006-ReactiveSubstrate.md#invalidation-algorithm)).
- `subs/cache.cljc` — `:rf.sub/dispose` per cache-slot eviction (closed-enum `:rf.sub/reason :no-more-derefers` / `:hot-reload` / `:cache-clear`; and the `:rf.sub/dispose` op-type entry above). Single-fire under CAS-winner contention.
- `trace/cascade.cljc` — `:rf.cascade/captured` (focused-event-only per-epoch cascade-DAG aggregator;). Fires at end-of-epoch from `epoch.cljc/settle!` via the `:trace.cascade/capture-for-epoch!` late-bind hook when the installed focus predicate matches.
- `fx.cljc` — `:rf.fx/do-fx` per drain step (op-type `:rf.fx`; the emit's `:tags` additionally carries `:rf.event/fx` (the vector the handler returned) and `:rf.event/db-present?` (boolean — was the handler's return-map's `:db` slot supplied?) so consumers can align cascade rows with handler returns without re-reading the interceptor context; the `:db` VALUE is intentionally NOT stamped — slice changes already ride `:rf.event/db-changed`. Both slots sit under `:tags` alongside `:frame`, consistent with the payload-shaped tag convention), `:rf.fx/handled` per dispatched fx, `:rf.fx/override-applied`, `:warning :rf.fx/skipped-on-platform`, `:rf.error/fx-handler-exception`, `:rf.error/no-such-fx`, plus `:rf.machine/spawned` and `:rf.machine/destroyed`.
- `cofx.cljc` — `:rf.cofx/run` (op-type `:rf.cofx`; emitted on the success branch of `inject-cofx`'s interceptor, inside the cofx handler's scope binding, carrying `:rf.cofx/id` + `:rf.cofx/value` + `:rf.cofx/elapsed-ms`), `:rf.error/no-such-cofx` (emitted by `inject-cofx` when the cofx-id has no registered handler), `:warning :rf.cofx/skipped-on-platform` (emitted when a registered cofx's `:platforms` excludes the active platform; mirrors `:rf.fx/skipped-on-platform` per [011 §Effect handling on the server](011-SSR.md#effect-handling-on-the-server)).
- `router.cljc` — `:rf.event :rf.event/run-start` and `:rf.event :rf.event/run-end` (the cascade run markers; both also carry the redundant `:rf.trace/phase :run-start` / `:run-end` tag), `:rf.event :rf.event/dispatched`, `:rf.event :rf.event/db-changed`, `:rf.error/handler-exception`, `:rf.error/drain-depth-exceeded`, `:rf.error/no-such-handler`, `:warning :rf.warning/dispatch-from-async-callback-fell-through-to-default` (emitted alongside `:rf.error/no-such-handler` when a dispatch landed on `:rf/default` purely because the resolution chain fell through and the handler is missing;), `:rf.error/dispatch-sync-in-handler`, `:rf.error/frame-destroyed`, `:rf.error/flow-eval-exception`, `:rf.frame/drain-interrupted` (lifecycle event emitted when the drain loop detects a destroyed frame mid-cycle; per [002 §Edge cases worth pinning](002-Frames.md#edge-cases-worth-pinning)).
- `frame.cljc` — `:rf.frame/created`, `:rf.frame/re-registered`, `:rf.frame/destroyed`, `:rf.machine.lifecycle/destroyed`.
- `registrar.cljc` — `:rf.registry/handler-registered`, `:rf.registry/handler-replaced`, `:rf.registry/handler-cleared`, `:warning :rf.warning/missing-doc` (emitted once per `(kind, id)` pair when a `reg-*` registration omits `:doc`; per [001 §`:doc` is dev-warned when absent](001-Registration.md#doc-is-dev-warned-when-absent) and).
- `machines.cljc` + `machines/transition.cljc` + `machines/lifecycle_fx.cljc` + `machines/timer.cljc` + `machines/parallel.cljc` (per the file split: `machines.cljc` is a thin façade and emits land in the four sub-namespaces) — `:rf.machine/event-received`, `:rf.machine/transition`, `:rf.machine.microstep/transition` (one per microstep on `:always`-driven cascades, per [005 §Trace events](005-StateMachines.md#trace-events)), `:rf.machine/snapshot-updated`, `:rf.machine.lifecycle/created`, `:rf.machine/system-id-bound`, `:rf.machine/system-id-released` (per [005 §Named addressing via `:system-id`](005-StateMachines.md#named-addressing-via-system-id)), `:rf.machine.timer/scheduled`, `:rf.machine.timer/fired`, `:rf.machine.timer/stale-after`, `:rf.machine.timer/skipped-on-server` (under SSR; per [005 §SSR mode](005-StateMachines.md#ssr-mode)), `:rf.machine/guard-evaluated` (per — emitted from the unified `evaluate-guard` helper at every user-declared guard call site in `machines/transition.cljc`; `:tags {:machine-id <id> :guard-id <kw-or-fn> :input {:data <data> :event <event-vec>} :outcome :pass | :fail}`; the synthesised always-true returned by `resolve-guard` for a nil guard-ref does NOT emit), `:rf.machine/action-ran` (per — emitted from `run-action` for every user-declared action invocation; `:tags {:machine-id <id> :action-id <kw-or-fn> :input {:data <data> :event <event-vec>} :outcome <return-value> | :ok | :rf.error/action-threw :exception <Throwable on the throw path>}`; success-with-nil-return collapses to `:ok`; the throwing path emits one trace with `:outcome :rf.error/action-threw` + `:exception` before propagating the `result/fail`), plus the machine-error categories.
- `routing.cljc` — `:rf.route/fragment-changed` (fragment-only navigation; — renamed from `:rf.route/fragment-changed`), `:rf.route/registered` / `:rf.route/cleared` / `:rf.route/activated` / `:rf.route/deactivated` (lifecycle pair), `:rf.route/navigation-blocked`, `:rf.route.nav-token/allocated`, `:rf.route.nav-token/stale-suppressed`, `:rf.fx/skipped-on-platform` (route-fx platform skips), `:warning :rf.warning/route-shadowed-by-equal-score`, `:error :rf.error/can-leave-non-boolean`.
- `flows.cljc` — `:rf.flow/registered`, `:rf.flow/computed`, `:rf.flow/skip`, `:rf.flow/cleared`, `:rf.flow/failed` (per [013 §Flow tracing](013-Flows.md#flow-tracing)). All carry `:op-type :flow`.
- `schemas.cljc` — `:rf.error/schema-validation-failure` (from `validate-app-schema!` / `validate-event!` / `validate-cofx!` / `validate-sub!`), `:warning :rf.warning/schema-validator-unavailable` (emitted once per process from `reg-app-schema` / `reg-app-schemas` when `:schemas/malli-validate` is unbound AND the framework-default validator is still installed; per [010 §Recommended soft-pass](010-Schemas.md) and), `:warning :rf.warning/schema-walker-opaque` (emitted once per process from `reg-app-schema` / `reg-app-schemas` when the registered schema is a non-vector form — registry-ref keyword, compiled `m/schema` object, or other opaque value — so the walker cannot introspect per-slot `:sensitive?` / `:large?` flags; per [010 §The `:schema` value is opaque to re-frame](010-Schemas.md) and).
- `spec.cljc` — `:rf.error/schema-validation-failure :where :event :source :boundary` (from the `:rf.schema/at-boundary` interceptor; per [010 §Production builds](010-Schemas.md#production-builds) and).
- `events.cljc` — `:rf.error/at-boundary-missing-schema` (thrown from `reg-event-*` when `:rf.schema/at-boundary` is attached to a handler whose metadata-map carries no `:schema`; per [010 §Production builds](010-Schemas.md#production-builds) and).
- `ssr.cljc` — `:rf.ssr/hydration-mismatch` (carries `:failing-id` to discriminate body-mismatch from head-mismatch; per [011 §Hydration-mismatch detection](011-SSR.md#hydration-mismatch-detection) and [011 §Mismatch detection — head](011-SSR.md#mismatch-detection--head)), `:warning :rf.warning/multiple-status-set`, `:warning :rf.warning/multiple-redirects`, `:rf.error/sanitised-on-projection`.
- `epoch.cljc` — `:rf.epoch/snapshotted` per dequeued event (one per epoch), `:rf.epoch/restored` on restore success, `:rf.epoch/db-replaced` on `reset-frame-db!` success, plus the six restore-failure categories and the two reset-frame-db! failure categories (`:rf.epoch/reset-frame-db-during-drain`, `:rf.epoch/reset-frame-db-schema-mismatch`), plus `:rf.epoch.cb/silenced-on-frame-destroy` emitted once per `(frame-id, cb-id)` pair on the destroy-cascade boundary, plus `:rf.epoch.cb/listener-exception` (op-type `:error`) emitted once per broken-listener invocation when an epoch listener throws — isolation contract still holds (sibling listeners and the runtime continue), the trace is the alarm so devtools surface the failure rather than silently dropping it.
- `views.cljs` — `:rf.view/render` per registered-view render (per [Spec 004 §Render-tree primitives](004-Views.md)). Per the same wrapper additionally fires `:rf.view/rendered` carrying cascade attribution (`:rf.view/id`, `:frame`, `:rf.view/render-key`, plus — when an in-flight cascade buffer is available — `:rf.view/cause-event-id` and `:rf.view/cause-subs`). Per `:rf.view/rendered` ALSO carries `:rf.view/mount?` (a boolean — `true` on the component instance's first render, `false` on every subsequent re-render) and, when the view derefs any subs, `:rf.view/deref-subs` (the vector of subscription query-vectors THIS view read during the render — its OWN per-view read-set, distinct from the cascade-wide `:rf.view/cause-subs` which over-reports). Per the wrapper ALSO emits the new `:rf.view/unmounted` op when a registered-view instance tears down (carrying `:rf.view/id`, `:frame`, `:rf.view/render-key`). The emit sites sit inside the substrate-agnostic `views.cljs` frame-aware-view wrapper, so every adapter (Reagent ratom watch chain, UIx hooks, Helix hooks) composes them; the new ops/fields ride the same per-render / per-instance-teardown path. `:rf.view/rendered` is capped at 100 per cascade with a one-shot `:rf.view/rendered-cap-reached` marker (carries `:frame` + `:rf.view/dropped-after`) to bound the per-cascade buffer's heap budget for full-page re-render storms (`:rf.view/unmounted` is NOT capped — one emit per instance teardown). Consumers (Xray Reactive / Views panels) walk the per-cascade buffer's `:rf.view/rendered` entries to graph cause→effect attribution and read `:rf.view/mount?` / `:rf.view/deref-subs` / `:rf.view/unmounted` to label each view's mount-vs-rerender-vs-unmount ACTION and its per-view reactive REASON; tools that already consumed `:rf.view/render` for render-count metrics continue to work unchanged.
- `adapter/context.cljs` — `:rf.error/frame-context-corrupted` (function-component `_currentValue` read observed a non-coercible shape;).
- `substrate/adapter.cljc` — `:warning :rf.warning/write-after-destroy` emitted by the `replace-container!` wrapper when called with a nil container (the frame was destroyed mid-drain or before a scheduled write fired; the underlying adapter's `replace-container!` is NOT invoked). Per [006 §`replace-container!`](006-ReactiveSubstrate.md#read-container-container--value-and-replace-container-container-new-value--nil) and.
- `std_interceptors.cljc` — `:rf.error/unwrap-bad-event-shape`.
- `http_managed.cljc` + `http_encoding.cljc` (the HTTP artefact ships eight `http_*.cljc` files; emits cited here come from `http_managed.cljc` unless noted) — `:warning :rf.http/cljs-only-key-ignored-on-jvm`, `:warning :rf.warning/decode-defaulted` (emitted from `http_encoding.cljc`), `:info :rf.http/retry-attempt`, `:info :rf.http/aborted-on-actor-destroy` (per [014 §Abort on actor destroy](014-HTTPRequests.md#abort-on-actor-destroy)), `:info :rf.http.interceptor/registered`, `:info :rf.http.interceptor/cleared`, `:error :rf.error/http-interceptor-failed` (request-side interceptor `:before` threw, per [014 §Middleware](014-HTTPRequests.md#middleware)), plus the Spec 014 failure categories.

User code can also emit traces — `re-frame.trace/emit!` is public and re-exported as `rf/emit-trace-event!`.

## Production builds: zero overhead, zero code

**All dev-side instrumentation is a development-only concern.** In production builds, the trace surface, the schema validation surface (Spec 010), and the registrar's hot-reload trace emit (`:rf.registry/handler-{registered,replaced,cleared}`) are *all* compile-time eliminated through a single shared gate. The closure compiler's dead-code elimination removes the gated branches; production binaries contain no instrumentation machinery at all.

### The mechanism: `re-frame.interop/debug-enabled?` (alias of `goog.DEBUG`)

The CLJS implementation uses one shared flag — an alias of the standard `goog.DEBUG` closure-define — for every dev-only branch:

```clojure
;; src/re_frame/interop.cljs
(def ^boolean debug-enabled? "@define {boolean}" ^boolean goog/DEBUG)
```

Every framework-internal dev branch — `trace/emit!`, `trace/emit-error!`, `schemas/validate-app-schema!`, `schemas/validate-event!`, `schemas/validate-cofx!`, `schemas/validate-sub!`, and the `registrar/{register!,unregister!,clear-kind!}` trace emits — wraps its body in `(when interop/debug-enabled? ...)`. With `:advanced` compilation and `:closure-defines {goog.DEBUG false}`, Closure constant-folds the gate and DCEs every dependent allocation: trace maps, listener iteration, malli calls, error reason strings, the Performance API bridge.

```edn
;; user's shadow-cljs.edn — production build
{:builds {:app {:target           :browser
                :output-dir       "..."
                :compiler-options {:closure-defines {goog.DEBUG false}}}}}
```

(Most production CLJS builds already set `goog.DEBUG=false`; re-frame2 piggybacks on the canonical CLJS production flag rather than introducing its own.)

The gate must be the **outermost** form of the body. `(when interop/debug-enabled? ...)` and `(if interop/debug-enabled? <body> <else>)` constant-fold reliably; `(when (and X interop/debug-enabled?) ...)` does NOT — Closure can't statically rule out `X`, and the dead branch survives into the bundle. The verifier (see [§Production-elision verification](#production-elision-verification)) catches that mistake.

A reachable but dead branch in a production bundle:

- Allocates no trace event maps.
- Holds no listener registry beyond the (small) `defonce` cells (which carry `{}` and `0`).
- Never invokes listener predicates.
- Excludes the per-frame trace rings (the cascade-keyed payload).
- Excludes the Performance API bridge.
- Excludes the schema validation entry points and their malli/explanation calls.

### How users opt in (dev builds)

CLJS dev builds default to `goog.DEBUG=true` — every gate stays live with no extra configuration. A user who wants trace machinery in a `:advanced` artefact (rare) can flip the flag explicitly:

```edn
;; shadow-cljs.edn — :advanced build with trace kept in
{:closure-defines {goog.DEBUG true}}
```

### User-side listener registration

User-side `(rf/register-listener! ...)` calls should also elide in production. Wrap them with the same predicate the framework uses:

```clojure
(when ^boolean re-frame.interop/debug-enabled?
  (rf/register-listener! :my/listener callback-fn))
```

In production (`goog.DEBUG=false`), `re-frame.interop/debug-enabled?` is the constant `false`, the `when` is dead, and the entire registration is elided.

The same pattern applies to `register-epoch-listener!`, `trace-buffer`, `clear-trace-buffer!`, and `(rf/configure :trace-buffer {:cascades-retained N})` — every dev-only call site in user code should sit under the `when ^boolean re-frame.interop/debug-enabled?` guard.

### JVM builds

> Cross-reference: see [Security.md §Production gates](Security.md#production-gates) — SSR / webhook / long-running JVMs facing untrusted input MUST set the gate `false` explicitly so dev-side trace enrichment elides in production. The runtime gate below is the JVM mirror of CLJS `goog.DEBUG`; both surfaces compose with the always-on substrates above.

JVM has no `:advanced` and no compile-time DCE. The JVM half of the interop layer:

```clojure
;; src/re_frame/interop.clj — JVM-side gate read once at ns-load
(def debug-enabled?
  (read-debug-flag))   ;; defaults to `true`; opt-out via property / env
```

…reads the gate ONCE at namespace load. The default is `true` (dev parity), but the gate is **explicitly overridable** for the SSR production posture — the JVM-side counterpart to CLJS `goog.DEBUG=false`.

**Opt-out vocabulary.** Two input sources, read in this order, with the JVM system property winning on conflict:

1. **Java system property `re-frame.debug`** — set on the JVM command line with `-Dre-frame.debug=false`.
2. **Environment variable `RE_FRAME_DEBUG`** — set in the process environment.

Both accept the conventional false-y vocabulary case-insensitively: `false`, `0`, `no`, `off`, empty string. Whitespace is trimmed. Anything else — including absent / unset — leaves the flag at its default `true`. The vocabulary is intentionally conservative: only the documented opt-out strings disable the gate, so an accidental typo (`disabled`, `nope`) leaves the dev posture alive rather than silently misconfiguring.

**What disabling the gate suppresses.** With `re-frame.debug=false` set BEFORE `re-frame.interop` loads, every JVM-side dev surface drops to its no-op floor — the same shape CLJS `:advanced` + `goog.DEBUG=false` builds achieve via Closure DCE:

- Trace emission (`emit!` / `emit-error!` / the queue-time `:rf.event/dispatched` emit) is silent.
- The per-frame trace rings accumulate nothing.
- `register-listener!` listeners receive no events.
- The epoch artefact (per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel)) records no `:db-before`/`:db-after`/`:trace-events` payloads, fires no `register-epoch-listener!` listeners, and refuses `restore-epoch` / `reset-frame-db!`.

**What remains live (always-on by construction).** Disabling the gate does NOT silence the production-survivable surfaces:

- The `register-event-listener!` substrate (per, [§Event-emit listener](#what-is-available-in-production)) keeps firing.
- The `register-error-listener!` substrate and the per-frame `:on-error` policy fn (per, [§Error-emit listener](#what-is-available-in-production)) keep firing.
- Schema validation, the registrar, and the dispatch loop itself are unaffected.

Those surfaces are explicitly always-on per their owning specs — they exist precisely for the SSR / production posture and would defeat their purpose if a debug-gate flip silenced them. They run with the `:sensitive?` substrate-level enforcement described in [§Privacy / sensitive data in traces](#privacy--sensitive-data-in-traces).

**Set the flag before re-frame loads.** The Var reads its value at ns-load time, then JIT-inlines into the per-call `when interop/debug-enabled?` checks. Late mutation via `alter-var-root!` works for tests (and is the canonical way to flip the gate within a test) but does not retroactively elide already-allocated infrastructure.

The motivating concern is the audit finding: an SSR / headless JVM process running re-frame2 should not, by default, retain user input in per-frame trace rings or epoch history. Apps that ship a JVM artefact for production should set `-Dre-frame.debug=false` in their deployment. The dev / test posture is unchanged.

### Production-elision verification

The contract above is enforced by an automated test in CI:

1. `implementation/core/test/re_frame/elision_probe.cljs` is a probe namespace that exercises every gated surface — `register-listener!`, `emit-trace-event!`, the per-frame trace rings (`trace-buffer` / `clear-trace-buffer!` / `(configure :trace-buffer {:cascades-retained N})`), `validate-{app-db,event,sub-return,cofx}!`, `register!` / `unregister!` / `clear-kind!`, the epoch surface (`register-epoch-listener!` / `epoch-history` / `restore-epoch` / `(configure :epoch-history …)`), plus a representative `dispatch-sync` flow. The probe roots the dead-code-elimination graph at every surface so a leak surfaces in the bundle.
2. `implementation/shadow-cljs.edn` declares two `:advanced` builds with `re-frame.elision-probe/run` as the entry point:
   - `:elision-probe` — `:closure-defines {goog.DEBUG false}` (production)
   - `:elision-probe-control` — `:closure-defines {goog.DEBUG true}` (control)
3. `implementation/scripts/check-elision.cjs` greps both bundles for sentinel strings drawn from the gated branches (schema reason fragments and `:rf.registry/*` trace operation keywords). The contract:
   - Production bundle: every sentinel MUST be ABSENT.
   - Control bundle: every sentinel MUST be PRESENT.
4. The CI workflow runs `npm run test:elision` (`shadow-cljs release elision-probe elision-probe-control && node scripts/check-elision.cjs`) on every push/PR.

The control build is what gives the test teeth: without it, a refactor that *moved* a sentinel string out of a gated branch would silently turn the negative assertion into a vacuous pass. With both bundles checked, any change that either breaks elision *or* loses methodology signal fails CI loudly.

When a future surface is added (e.g. epoch history per [Tool-Pair §How AI tools attach](Tool-Pair.md#how-ai-tools-attach)), it follows the same pattern:

- Wrap its dev-only body in `(when interop/debug-enabled? ...)`, outermost.
- Touch the surface from `re-frame.elision-probe` so the DCE graph reaches it.
- Add a sentinel to `DEV_ONLY_SENTINELS` in `check-elision.cjs` (a string literal or keyword name that only the gated branch contains).

## Production debugging: what remains

The elision contract above is uncompromising — in a `:advanced` build with `goog.DEBUG=false`, the entire trace surface disappears. That decision is correct for binary-size and hot-path cost, but it has consequences for post-mortem debugging that this section makes explicit so users aren't surprised when a production incident leaves them with thin tooling.

### What is NOT available in a default production CLJS build

A `:closure-defines {goog.DEBUG false}` `:advanced` build carries no trace machinery. Concretely, the following surfaces have been DCEd and the runtime cannot reach them at all:

- `register-listener!` / `unregister-listener!` — listener registration is a no-op because the gate around `trace/emit!` is constant-folded out. Even if user code registered a listener at boot (which it shouldn't, per [§User-side listener registration](#user-side-listener-registration)), nothing would ever invoke it.
- The per-frame trace rings (`trace-buffer`, `clear-trace-buffer!`, `(configure :trace-buffer {:cascades-retained N})`) — pulling "the last N cascades from a prod session" is not supported. The ring's `swap!` site is inside the same elision gate.
- `register-epoch-listener` and the per-event `:rf/epoch-record` assembly — epoch projection runs inside the trace surface and elides with it.
- Every `:rf.error/*`, `:rf.warning/*`, `:rf.info/*`, `:rf.fx/*`, `:rf.ssr/*`, and `:rf.epoch/*` trace event documented in [§Error event catalogue](#error-event-catalogue). They are not emitted, not buffered, and not deliverable to any listener. (The `:on-error` per-frame slot — per [002-Frames §`:on-error`](002-Frames.md) — is a documented exception: it rides a small always-on error-emit substrate that survives `goog.DEBUG=false`. See [§What IS available in production](#what-is-available-in-production) below.)
- Source-coord enrichment (`:rf.trace/trigger-handler`), `:rf.trace/dispatch-id` / `:rf.trace/parent-dispatch-id` correlation, `:rf.event/origin` tagging — all ride the trace event and elide with it.
- Schema validation (`:rf.error/schema-validation-failure`) and registrar hot-reload notifications (`:rf.registry/handler-registered` and siblings) — same gate, same elision.
- The Xray-MCP server and the re-frame2-pair server (per [Tool-Pair.md](Tool-Pair.md)). These are dev-only tools that attach to the trace surface; they are not designed for, and not shippable to, production. The Xray preload artefact must not be on a production build's classpath; the re-frame2-pair server lives in its own dev-only artefact for the same reason.

### What IS available in production

> Cross-reference: see [Security.md §Production gates](Security.md#production-gates) for the framework-wide threat-model entry — the three always-on substrates below are the production-survivable surface; everything in [§What is NOT available in a default production CLJS build](#what-is-not-available-in-a-default-production-cljs-build) is dev-only by design (both for performance and for security — dev-side enrichments would otherwise leak to production consumers).

Six surfaces survive elision and are the canonical production-debugging fallbacks:

1. **The per-frame `:on-error` slot** (per [002-Frames §`:on-error`](002-Frames.md) and [§Error-handler policy](#error-handler-policy-on-error-per-frame)) — runs through a small always-on error-emit substrate that is NOT gated by `re-frame.interop/debug-enabled?`. When a registered event handler throws in CLJS prod, the runtime invokes the in-scope frame's `:on-error` policy fn with the structured error event (`:operation :rf.error/handler-exception`, `:op-type :error`, `:tags {:rf.trace/event-id :frame :exception …}`). The substrate covers the handler-exception path — the primary production-monitoring case. The substrate does NOT carry dev-side enrichment: `:rf.trace/dispatch-id` correlation, `:rf.trace/trigger-handler` source-coord, and the retain-N ring buffer all remain trace-surface-only and continue to elide. Per Spec 009 §1052 policy-fn exceptions are caught inside the substrate so a buggy policy cannot break the cascade.
2. **The event-emit listener surface** (per, `register-event-listener!` / `unregister-event-listener!` — see [API.md §Event-emit](API.md#event-emit-always-on-production-survivable)) — runs through a small always-on event-emit substrate (parallel to the `:on-error` substrate in #1) that is NOT gated by `re-frame.interop/debug-enabled?`. The router fans out one record per processed event after the cascade settles. The record is intentionally tight — `{:event <vector> :event-id <kw> :frame <kw> :time <millis> :outcome <kw> :elapsed-ms <int>}` — enough discriminator for production event observability (event-id, frame, outcome, latency); not enough for causal reconstruction (`:rf.trace/dispatch-id` correlation, `:rf.trace/parent-dispatch-id`, source-coord ride the dev-only trace surface and elide with it). `:outcome` reports the dispatch result across **every** cascade-failure path, not just the interceptor-chain exception, so a dispatch that aborted is never mis-reported as a clean `:ok` to an off-box shipper: `:ok` (clean settle — db committed, flows ran, `:fx` walked), `:error` (the interceptor chain — handler or interceptor — threw), `:rolled-back` (post-commit `:db` schema validation rejected the new state and the container was restored to its pre-handler value per [010 §Per-step recovery](010-Schemas.md#per-step-recovery) row 4 — flows and `:fx` were skipped), or `:flow-error` (a flow's `:output` threw per [013 §Failure semantics](013-Flows.md#failure-semantics) rule 3 — the cascade halted before `:fx`). The chain-exception path reports `:error` even when a downstream rollback would also apply: it is the proximate, most-actionable signal, and a chain throw short-circuits the `:db` commit so there is no post-commit state to validate. The `:event` vector is passed through `re-frame.elision/elide-wire-value` ONCE before fan-out with off-box defaults (large → `:rf.size/large-elided`; sensitive → `:rf/redacted`), so listeners can ship the wire payload to a hosted observability back-end (Datadog, Honeycomb, Sentry, …) without further shaping. Per-listener exceptions are caught inside the substrate so a buggy listener cannot break the cascade or block sibling listeners. Listener registration sites SHOULD use `^boolean re-frame.interop/debug-enabled?` as a belt-and-braces gate alongside the user's explicit config flag:

   ```clojure
   (when (and (= "production" (:env config))
              (not ^boolean re-frame.interop/debug-enabled?)
              (:api-key config))
     (rf/register-event-listener!
       :datadog/forward
       (fn [event-record]
         (datadog/track event-record))))
   ```

   Catches the "accidentally deployed a dev bundle with prod config" bug class.
3. **The error-emit listener surface** (per, `register-error-listener!` / `unregister-error-listener!` — see [API.md §Error-emit](API.md#error-emit-always-on-production-survivable)) — sibling of #2, runs through the SAME always-on error-emit substrate as the per-frame `:on-error` slot (#1) but along an independent fan-out path. NOT gated by `re-frame.interop/debug-enabled?`. The router fans out one record per `:rf.error/*` event after the handler-exception path runs. The record is intentionally tight — `{:error <kw> :event <vector> :event-id <kw> :frame <kw> :time <millis> :exception <ex> :elapsed-ms <int>}` — enough discriminator for production error observability (failing event-id, frame, exception object, latency); not enough for causal reconstruction (`:rf.trace/dispatch-id`, source-coord, `:rf.trace/trigger-handler` ride the dev-only trace surface and elide with it). The `:event` vector is passed through `re-frame.elision/elide-wire-value` ONCE before fan-out with off-box defaults (large → `:rf.size/large-elided`; sensitive → `:rf/redacted`). The two paths from the substrate are mutually isolated: a buggy listener cannot block the per-frame `:on-error` policy fn, and a buggy policy fn cannot block listeners. Listener registration sites SHOULD use `^boolean re-frame.interop/debug-enabled?` as a belt-and-braces gate alongside the user's explicit config flag, symmetric with the event-emit pattern in #2:

   ```clojure
   (when (and (= "production" (:env config))
              (not ^boolean re-frame.interop/debug-enabled?)
              (:dsn config))
     (rf/register-error-listener!
       :sentry/forward
       (fn [error-record]
         (sentry/capture-exception (:exception error-record)
                                   {:tags {:event-id (:event-id error-record)
                                           :frame    (:frame error-record)}}))))
   ```

   Use #2 and #3 together to route both events and errors through one hosted observability back-end without preserving the full trace surface.
4. **The Performance API channel** (per [§Performance instrumentation](#performance-instrumentation)) — gated on the independent `re-frame.performance/enabled?` `goog-define`, default off. A production build that wants timing observability flips `{:closure-defines {re-frame.performance/enabled? true}}`; the bracket sites at the four hot paths (`:event`, `:sub`, `:fx`, `:render`) emit User-Timing measure entries that any `PerformanceObserver` — including the host APM's — reads via `performance.getEntriesByType('measure')`. This is the production observability surface re-frame2 ships and supports.
5. **The SSR error-projector boundary** (per [011 §Server error projection](011-SSR.md#server-error-projection)) — on the server (JVM/SSR), `re-frame.interop/debug-enabled?` is hardcoded `true` (per [§JVM builds](#jvm-builds)), so the trace surface is live. The runtime emits structured `:rf.error/*` traces, the registered error projector consumes them, and the locked `:rf/public-error` shape is written to the HTTP response. Apps with an SSR tier get the full trace + projection pipeline server-side independent of the client-side bundle's elision.
6. **Native browser machinery** — uncaught exceptions still reach `window.onerror` / `window.onunhandledrejection`. A re-frame2 event handler that throws in production still surfaces there; what's missing is the structured `:rf.error/handler-exception` shape, the `:rf.trace/dispatch-id` correlation, and the `:rf.trace/trigger-handler` coord — those rode the trace surface. Prefer the `:on-error` slot (#1) for structured access to the failing handler's id and the exception.

### Observability decision matrix — six surfaces × three postures

The prose above catalogues the surfaces in elision-framing — what disappears under `goog.DEBUG=false` and what survives. Users wiring up observability typically arrive with the opposite framing: *"which surface do I use for this use case?"* This subsection flips the framing and pins, for each of the six observation surfaces, the production posture, the record shape, and the use cases the surface serves.

The framework exposes **six observation surfaces**:

1. **Raw trace listener** — `register-listener!` / `unregister-listener!` ([§Listener API](#the-listener-api)).
2. **Assembled-epoch listener** — `register-epoch-listener!` / `unregister-epoch-listener!` ([§Assembled-epoch listener](#register-epoch-listener--assembled-epoch-listener), [Tool-Pair §Time-travel](Tool-Pair.md#time-travel-epoch-snapshots-and-undo)).
3. **Event-emit listener** — `register-event-listener!` / `unregister-event-listener!` ([API.md §Event-emit](API.md#event-emit-always-on-production-survivable)).
4. **Error-emit listener** — `register-error-listener!` / `unregister-error-listener!` ([API.md §Error-emit](API.md#error-emit-always-on-production-survivable)).
5. **Per-frame `:on-error` slot** — frame-registration metadata ([002-Frames](002-Frames.md), [§Error-handler policy](#error-handler-policy-on-error-per-frame)).
6. **Performance API channel** — `performance.mark` / `performance.measure` brackets ([§Performance instrumentation](#performance-instrumentation)).

Each surface sits in exactly one of **three production postures**:

- **dev-only DCE** — gated on `re-frame.interop/debug-enabled?` (alias of `goog.DEBUG`, default `true` in dev / `false` in `:advanced` prod CLJS, default `true` JVM with `-Dre-frame.debug=false` opt-out). Compile-time eliminated in production; allocates zero in the bundle. Per [§Production builds](#production-builds-zero-overhead-zero-code).
- **always-on** — runs through a small substrate that survives `goog.DEBUG=false` (and survives `-Dre-frame.debug=false` JVM-side). Tight record shape, post-elision (large → `:rf.size/large-elided`; sensitive → `:rf/redacted`), per-listener exceptions isolated. Designed for production observability without preserving the dev-only trace surface. Per.
- **opt-in goog-define** — gated on an independent compile-time flag distinct from `goog.DEBUG`. Default off; consumer flips the flag explicitly via `:closure-defines`. Production bundles that don't opt in carry zero instrumentation; those that do retain the surface even with `goog.DEBUG=false`.

#### Posture × surface matrix

| Surface | Dev (`goog.DEBUG=true`) | Nightly (`goog.DEBUG=true` + cascades-retained tuned) | Production (`goog.DEBUG=false`) | Posture |
| --- | --- | --- | --- | --- |
| 1. Raw trace listener (`register-listener!`) | **live** — full structured trace stream, every `:op-type` (`:rf.event`, `:rf.sub`, `:rf.fx`, `:error`, `:warning`, `:rf.machine/*`, `:rf.flow/*`, …), dev-side enrichments (`:rf.trace/trigger-handler` source-coord, `:rf.trace/dispatch-id` / `:rf.trace/parent-dispatch-id` correlation, `:rf.event/origin` tag) | **live** — same as dev; tune per-frame cascade retention via `(rf/configure :trace-buffer {:cascades-retained N})` (or per-frame `:rf.trace/cascades-retained` metadata) for long-tail traces | **elided** — `emit!` gate constant-folded; registration is a no-op, listener never invoked; zero allocation in bundle | dev-only DCE |
| 2. Assembled-epoch listener (`register-epoch-listener!`) | **live** — one `:rf/epoch-record` per dequeued event / epoch (per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel-epoch-snapshots-and-undo)); `:db-before` / `:db-after` / `:trace-events` payload; `(rf/configure :epoch-history {:depth N :trace-events-keep N :redact-fn fn})` controls retention and per-record redaction | **live** — bump `:depth` for longer post-mortem windows; `:redact-fn` runs at build-time before ring-append | **elided** — projection runs inside the trace surface and elides with it; epoch ring records nothing, listeners never fire, `restore-epoch` / `reset-frame-db!` refuse | dev-only DCE |
| 3. Event-emit listener (`register-event-listener!`) | **live** — one tight record per processed event: `{:event :event-id :frame :time :outcome :elapsed-ms}`, post-elision | **live** — same record shape; no tuning knobs | **live** — survives `goog.DEBUG=false`; identical record shape and elision; per-listener exceptions isolated; consumer SHOULD belt-and-braces `(when (not ^boolean re-frame.interop/debug-enabled?) …)` registration to catch dev-bundle-with-prod-config bug class | **always-on** |
| 4. Error-emit listener (`register-error-listener!`) | **live** — one tight record per `:rf.error/*` event: `{:error :event :event-id :frame :time :exception :elapsed-ms}`, post-elision | **live** — same record shape | **live** — survives `goog.DEBUG=false`; identical record shape and elision; isolated from the per-frame `:on-error` policy fn fan-out (#5); per-listener exceptions isolated | **always-on** |
| 5. Per-frame `:on-error` slot | **live** — policy fn invoked with `:operation :rf.error/handler-exception` event; return-map shape governs recovery (`{:recovery :default | :swallow | :replacement <new-event>}`) per [§Return-map contract](#return-map-contract) | **live** — same semantics; tune via the policy fn's own logic | **live** — survives `goog.DEBUG=false`; handler-exception path runs through the same always-on error-emit substrate as #4 but along an independent fan-out path; policy-fn exceptions caught inside the substrate so a buggy policy cannot break the cascade | **always-on** |
| 6. Performance API channel | **default off** — `re-frame.performance/enabled?` defaults to `false`; bracket sites elided. Apps that want timing in dev opt in via `:closure-defines {re-frame.performance/enabled? true}` and read via `performance.getEntriesByType('measure')` or DevTools Performance panel | **default off** — same as dev; opt in for nightly perf regression catches | **default off** — bracket sites DCEd. Apps that want production timing observability opt in via the same `:closure-defines` flag; brackets at the four hot paths (`:event`, `:sub`, `:fx`, `:render`) emit User-Timing measure entries readable by any `PerformanceObserver` including the host APM | **opt-in goog-define** |

**Posture-row reading.** A surface in the **dev-only DCE** row is *gone* from production bundles — no listener, no allocation, no overhead. A surface in the **always-on** row keeps firing under `goog.DEBUG=false`; the record is tight by design, post-elision, exception-isolated. A surface in the **opt-in goog-define** row is gone by default but *recoverable* in production without preserving the full trace surface — the consumer flips one independent compile-time flag.

#### Use case × surface routing

The same six surfaces map onto the canonical observability use cases. The table below pins, for each use case, the surface that fits and the surface that does NOT (with the reason — typically posture mismatch or wrong record shape).

| Use case | Recommended surface | Why this one | What to avoid |
| --- | --- | --- | --- |
| Real-time UI dashboard (re-frame-10x style) — live cascade view, per-domino timing, error highlighting in dev | #1 raw trace listener + #2 epoch listener (composed) | Need every `:op-type` event with dev-side enrichments (`:dispatch-id` correlation, source-coord, `:origin`). Tools like re-frame-10x consume both: raw stream for the timeline, epoch records for the structured per-cascade slice. Dev-only is the right posture (the dashboard isn't shipped to production). | Don't use #3 / #4 — the tight record shape strips correlation fields the dashboard needs. Don't use #6 — Performance API is timing-only, no semantics. |
| Off-box APM forwarder (Datadog, Honeycomb, New Relic) — ship event throughput + latency to a hosted backend, including in production | #3 event-emit listener + #4 error-emit listener | Always-on posture is mandatory (the forwarder MUST run in production). Tight record shape is the contract — already post-elision, already wire-shaped. Per-listener exceptions isolated. | Don't use #1 — it's elided in production. Don't use the dev-only stream then "promote" via `goog.DEBUG=true` in prod just to get APM — that ships the entire trace surface for no benefit. |
| Post-mortem error monitor (Sentry, Rollbar, Honeybadger) — capture handler exceptions with frame + event-id context, ship to hosted backend | #5 `:on-error` per-frame slot (primary) + #4 error-emit listener (secondary, for cross-frame fan-out) | `:on-error` rides the always-on error-emit substrate; fires in production. Receives `{:event-id :frame :exception …}` structured shape — enough for the monitor's `tags` / `extra` fields. `:on-error` ALSO controls recovery semantics (the return-map governs cascade continuation) which the listener surface does not. | Don't rely on `window.onerror` alone — it sees the bare exception without re-frame2's structured frame/event-id context. Don't use #1 in production — it's elided. |
| Performance budget (CI perf-regression gate, real-user monitoring) — measure event / sub / fx / render timing against a budget | #6 Performance API channel | Designed for this use case. Production-survivable via the independent `re-frame.performance/enabled?` flag; surfaces in DevTools Performance panel and the host APM's `PerformanceObserver`; zero overhead when not opted in. | Don't use #1 — it's elided in production. Don't use #3 — the `:elapsed-ms` field is event-level only; #6 brackets sub / fx / render too. |
| Custom recorder (in-app debug overlay, story-runner-style replay capture) | #2 epoch listener | Each record is a fully-projected per-cascade slice (`:db-before`, `:db-after`, `:trace-events`); the recorder appends one record per cascade with no further shaping. `(rf/configure :epoch-history {:depth N :redact-fn fn})` controls retention. Dev-only is the right posture for a debug overlay. | Don't use #1 — raw trace stream requires per-cascade grouping logic the consumer would have to reimplement. |
| Framework's own SSR error projection — turn runtime errors into the locked `:rf/public-error` HTTP-wire shape on the JVM/SSR tier | #4 error-emit listener (per [011 §Server error projection](011-SSR.md#server-error-projection)) | Production-required surface (an SSR error is by definition a production-survivable concern). Always-on substrate survives both `goog.DEBUG=false` (CLJS) and `-Dre-frame.debug=false` (JVM, when the operator opts out per [Security.md §Production gates](Security.md#production-gates)). | Don't route SSR error projection through #1 — it's gated by `interop/debug-enabled?`, which an SSR JVM facing untrusted input is explicitly directed to disable. Routing through #4 keeps the projector firing under both postures. |

#### Combining surfaces

The six surfaces are independent — registering a listener on one does NOT register on the others. Common compositions:

- **Full dev observability**: #1 + #2 + #6. Raw stream feeds the dashboard, epoch listener feeds the recorder / pair tool, Performance API feeds DevTools timing.
- **Full production observability**: #3 + #4 + #6. Event throughput + latency to APM (#3), error monitoring (#4 or #5), timing budget (#6). Add #5 inline for per-frame recovery policy.
- **Hybrid**: app with both a dev-time dashboard AND a hosted production monitor uses #1 + #2 in dev (registered under `(when ^boolean re-frame.interop/debug-enabled? …)`) and #3 + #4 + #5 always. The dev-only registrations elide in production; the always-on registrations survive.

#### Tuning knobs by posture

Each posture row has a small set of runtime knobs (orthogonal to the elision gate itself):

| Posture | Knob | Effect | Surface(s) affected |
| --- | --- | --- | --- |
| dev-only DCE | `(rf/configure :trace-buffer {:cascades-retained N})` | Per-frame cascade-keyed ring for `register-listener!` late-attach (`N=0` opts out of the ring entirely; default 50; per-frame override via `:rf.trace/cascades-retained` metadata on `reg-frame`) | #1 |
| dev-only DCE | `(rf/configure :epoch-history {:depth N :trace-events-keep N :redact-fn fn})` | Epoch ring depth, per-record trace-event budget, per-record redaction hook for sensitive payloads | #2 |
| dev-only DCE | `(rf/configure :elision {:rf.size/threshold-bytes N})` | Per-payload size threshold for `:rf.size/large-elided` marker in trace records | #1, #2 (records ride post-elision) |
| always-on | none — record shape is fixed by contract | Listeners receive identical record shapes in dev and prod | #3, #4, #5 |
| always-on | `:on-error` policy fn return-map | Per-frame recovery decision (`:default` / `:swallow` / `:replacement`); does not alter the record shape delivered to #4 | #5 |
| opt-in goog-define | `:closure-defines {re-frame.performance/enabled? true}` | Enables the four `performance.mark` / `performance.measure` bracket sites (`:event`, `:sub`, `:fx`, `:render`) | #6 |

The `goog.DEBUG` flag (CLJS) and the `-Dre-frame.debug` system property / `RE_FRAME_DEBUG` env var (JVM) are not user knobs — they're the build-time/process-start gates that select the **posture**. Apps DO NOT toggle them per-request or per-session; once the bundle is compiled (or the JVM is started), the posture is fixed.

#### Off-box egress contract

Three of the six surfaces are designed to feed off-box (hosted) backends; the contract differs:

| Surface | Off-box ready? | Record shape | Privacy guarantee |
| --- | --- | --- | --- |
| #1 raw trace listener | NO — dev-only; not present in production bundles. Apps SHOULD NOT ship trace records to a hosted backend in dev as a substitute for #3 / #4 (the record is much larger and carries dev-side fields irrelevant to APM) | Full structured trace event with `:tags` open bag, source-coord, `:dispatch-id` correlation | The dev-side `register-listener!` runs after `:sensitive?` substrate-level scrubbing per [§Privacy / sensitive data in traces](#privacy--sensitive-data-in-traces). |
| #2 epoch listener | NO — dev-only; epoch records carry full `:db-before` / `:db-after` snapshots and are not sized for hosted ingestion | Assembled `:rf/epoch-record` per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel-epoch-snapshots-and-undo) | `(rf/configure :epoch-history {:redact-fn fn})` runs at build-time before ring-append. |
| #3 event-emit listener | YES — tight record shape, post-elision, exception-isolated. Designed for direct hosted-backend forwarding | `{:event :event-id :frame :time :outcome :elapsed-ms}` | `:event` vector passed through `re-frame.elision/elide-wire-value` once before fan-out (large → `:rf.size/large-elided`; sensitive → `:rf/redacted`). |
| #4 error-emit listener | YES — same posture and shape contract as #3 | `{:error :event :event-id :frame :time :exception :elapsed-ms}` | Same elision pre-fan-out as #3; `:exception` object is the JS / JVM throwable, not a serialised string. |
| #5 `:on-error` policy fn | INDIRECT — policy fn receives the same structured shape as #4 and can forward to a backend, but the policy fn's primary role is per-frame recovery, not off-box egress. Prefer #4 for pure forwarding | `{:operation :rf.error/handler-exception :op-type :error :tags {:rf.trace/event-id :frame :exception …}}` | Same elision contract as #4 (same substrate). |
| #6 Performance API channel | INDIRECT — User-Timing measure entries are consumed by host APMs through `PerformanceObserver`; the framework does not emit to a hosted backend directly | Browser-native `PerformanceMeasure` entries with `name` / `startTime` / `duration` / `detail` | No payload — measures carry only timing, not user data. |

The off-box egress contract above is the **only documented production wire**. Apps that need richer production observability than #3 / #4 / #6 provide must either (a) keep the dev-only trace surface in production via `:closure-defines {goog.DEBUG true}` (with the bundle-size cost — see [§Production-elision verification](#production-elision-verification)) or (b) implement custom emission from their own handlers / interceptors / fx handlers.

### Wiring an external error monitor (Sentry, Rollbar, Honeybadger, etc.)

The dev-side integration documented at [§Composition with libraries](#composition-with-libraries-sentry-honeybadger-etc) routes structured trace events into the monitor:

```clojure
;; Dev: full structured trace, captured before the runtime's default recovery.
(rf/register-listener!
 :sentry/forward
 (fn [trace-event]
   (when (= :error (:op-type trace-event))
     (sentry/capture-event
      {:level      "error"
       :message    (-> trace-event :tags :reason)
       :tags       {:rf-operation  (name (:operation trace-event))
                    :rf-frame      (some-> trace-event :tags :frame name)
                    :rf-dispatch   (some-> trace-event :tags :rf.trace/dispatch-id str)
                    :rf-failing-id (some-> trace-event :tags :failing-id str)}
       :extra      (:tags trace-event)
       :fingerprint [(name (:operation trace-event))
                     (str (-> trace-event :tags :failing-id))]}))))
```

In a production CLJS build with `goog.DEBUG=false`, the `register-listener!` call and its body sit under the `(when ^boolean re-frame.interop/debug-enabled? …)` user-side guard (per [§User-side listener registration](#user-side-listener-registration)) and elide entirely. The trace-listener fan-out (`:rf.trace/dispatch-id` correlation, `:rf.trace/trigger-handler` source-coord, the per-frame trace rings) is dev-only. Three integration patterns survive elision:

- **Recommended for structured fields**: register the monitor through the per-frame `:on-error` slot (per [§Error-handler policy](#error-handler-policy-on-error-per-frame)). Per the slot rides the always-on error-emit substrate, NOT the trace surface — registered policy fns fire under `:advanced` + `goog.DEBUG=false`. The policy fn receives the structured error event (`:operation :rf.error/handler-exception`, `:op-type :error`, `:tags {:rf.trace/event-id :frame :exception …}`), forwards to the monitor, and returns nil to delegate recovery to the runtime. This is the recommended production-monitor integration. The substrate covers the handler-exception path; dev-side enrichments (`:rf.trace/dispatch-id`, source-coord, per-frame rings) are not carried.
- **Native-SDK fallback**: install the monitor's native browser SDK at the top of the bundle (`Sentry.init({...})`). It captures `window.onerror`, `window.onunhandledrejection`, and any explicit `Sentry.captureException` call wherever the app already has error-boundary plumbing. The trade-off is loss of re-frame2's structured fields — the monitor sees the bare exception, not the cascade context. Use this when the app already has wider-scope error-boundary plumbing or when handler-exception coverage alone is insufficient.
- **Opt-in to keep the trace surface**: ship `:advanced` with `:closure-defines {goog.DEBUG true}`. The trace surface is preserved, the `register-listener!` sample above runs, and the monitor receives full structured events including dev-side enrichments (`:dispatch-id`, `:rf.trace/trigger-handler`, the per-frame rings). The cost is the trace machinery's bundle size (see [§Production-elision verification](#production-elision-verification) for the size delta — the control bundle is the reference measurement). This is the explicit escape hatch for apps where post-mortem fidelity outweighs bundle weight.

## Hot path in dev builds

Dev iteration matters; you don't want trace machinery to slow ordinary feedback loops. Two hot-path costs are present in dev:

1. **Trace-event allocation** — building the trace map per emit.
2. **Listener invocation** — invoking `register-listener!` callbacks once per emitted event.

### Cheap-path discipline (dev builds only)

- **Listener registry is a single atom.** Reading it is one deref.
- **No string formatting or other expensive work** happens in framework emit code; tools format if they want to.
- **Listener invocation cost scales with listener count.** Zero registered listeners means zero per-emit dispatch overhead beyond the registry deref. The per-frame trace ring always appends to the in-flight cascade's slot (its append is `swap!` plus a slot lookup), so the floor is one map allocation, one ring append, and one deref per emit; frameless emits skip the ring entirely (per the B3 ruling above) so their floor is the listener fan-out alone.

## Performance instrumentation

The trace stream above is dev-only — too noisy for prod, gated on `re-frame.interop/debug-enabled?` (an alias of `goog.DEBUG`). Many apps still want a *separate*, default-off, prod-friendly timing channel: one that surfaces in Chrome DevTools' Performance panel alongside React renders, network, and paint, and that consumers (the host's APM, a custom `PerformanceObserver`, an in-app perf overlay) can read via the standard browser [User Timing](https://developer.mozilla.org/en-US/docs/Web/API/Performance_API/User_timing) surface.

re-frame2 ships that channel through the browser's `performance.mark` / `performance.measure`, gated on a **second** compile-time constant — `re-frame.performance/enabled?` — that is independent of `goog.DEBUG`. The default is off; consumers opt in by flipping the `goog-define` via `:closure-defines`. Closure DCE then either keeps the bracket sites or elides them entirely; production binaries that don't ask for timing carry zero User-Timing instrumentation.

This is **distinct from** the trace surface above:

| Axis | Trace stream | Performance instrumentation |
|---|---|---|
| Compile-time gate | `re-frame.interop/debug-enabled?` (alias of `goog.DEBUG`) | `re-frame.performance/enabled?` |
| Default | on in dev (`goog.DEBUG=true`), off in prod | **off** in both (`enabled?=false`) |
| Consumer | `register-listener!` listeners, the per-frame trace rings, `register-epoch-listener!` | `performance.getEntriesByType('measure')`, `PerformanceObserver`, Chrome DevTools Performance |
| Shape | structured trace events (open maps with `:operation` / `:op-type` / `:tags`) | `User Timing` measure entries (`name`, `startTime`, `duration`) |
| Where it runs | both platforms (dev) | CLJS only — JVM is a no-op |

The two flags compose: a build that wants both flips both. A typical prod build has `goog.DEBUG=false` and either `re-frame.performance/enabled?` true (perf timing kept; trace elided) or false (everything elided).

### The compile-time flag

```clojure
;; src/re_frame/performance.cljc
(goog-define ^boolean enabled? false)
```

A consumer flips it in their shadow-cljs.edn / compiler-options:

```edn
{:builds {:app {:target           :browser
                :output-dir       "..."
                :compiler-options {:closure-defines {re-frame.performance/enabled? true}}}}}
```

Like `goog.DEBUG`, `:advanced` constant-folds the value, the gated branch DCEs, and the body collapses to its un-bracketed shape — for the perf surface that means each call site becomes a direct invocation of the body it brackets.

### What gets bracketed

The reference runtime brackets four hot-path call sites. Each runs inside a `(performance/mark-and-measure :<bucket> <id> <body>)` macro form so the bracket is a compile-time decision (the macro expands to `(if enabled? <gated-bracket> (do <body>))`, which Closure constant-folds):

| Bucket | Where | Entry name |
|---|---|---|
| `:event`  | Event handler invocation (router's `process-event*` step that runs the interceptor chain) | `rf:event:<event-id>` |
| `:sub`    | Subscription recompute (the body fn inside `compute-and-cache!`'s reaction) | `rf:sub:<sub-id>` |
| `:fx`     | Per-fx walk-step (every entry processed by `handle-one-fx`, including reserved fx-ids `:dispatch` / `:dispatch-later` / `:rf.fx/reg-flow` / `:rf.fx/clear-flow` and user-registered fx) | `rf:fx:<fx-id>` |
| `:render` | Per-`reg-view` render (the wrapper emitted by `reg-view*`) | `rf:render:<view-id>` |

The bracket shape (when the flag is on at compile time):

```
performance.mark(<name>:start)
try    <body>
finally
  performance.mark(<name>:end)
  performance.measure(<name>, <name>:start, <name>:end)
```

The `try/finally` ensures a partial measure entry still lands when the body throws — observability does not become silent on the unhappy path. The thrown exception still propagates after the `:end` mark fires.

### Naming convention

Every entry name uses the shape `rf:<bucket>:<id>`, so consumers filter by the `rf:` prefix without parsing per-bucket shapes. Keyword ids preserve their namespace:

```
rf:event:user/login
rf:sub:cart/total
rf:fx:dispatch
rf:fx:rf.http/managed
rf:render:my.app/page-header
```

Tools that want a per-bucket view split on the second `:`. The shape is **stable**: new buckets adopt the `rf:<bucket>:<id>` convention and are additive.

### Consumer access

```javascript
// All re-frame entries from the most recent run.
performance.getEntriesByType('measure')
  .filter(e => e.name.startsWith('rf:'));

// Live: a PerformanceObserver fires per emitted entry.
new PerformanceObserver((list) => {
  for (const e of list.getEntriesByType('measure')) {
    if (e.name.startsWith('rf:')) {
      // entry: { name, startTime, duration, ... }
      sendToAPM(e);
    }
  }
}).observe({ type: 'measure', buffered: true });
```

Chrome DevTools' Performance panel renders the measures as named tracks alongside React renders, network, and paint — no custom UI required.

The `User Timing` entry buffer is bounded by the host (Chrome's default is 10000 entries); long-running pages that want every entry should attach a `PerformanceObserver` and offload to durable storage rather than rely on the buffer.

### Production-elision verification

The bundle-isolation contract is enforced in CI by `npm run test:perf-bundle` (the dual of `npm run test:elision`):

1. `:examples/counter` builds the standard counter example under `:advanced` with the perf flag off (the goog-define default).
2. `:examples/counter-perf` builds the same source under `:advanced` with `:closure-defines {re-frame.performance/enabled? true}`.
3. `scripts/check-perf-bundle.cjs` greps both bundles. The contract:
   - **Off bundle** MUST NOT contain `performance.mark`, `performance.measure`, or any `"rf:` entry-name fragment.
   - **On bundle** MUST contain all three.

Without the on bundle the off-bundle assertion would be vacuous — a refactor that *moved* the strings out of the gated branch would silently turn the negative grep into a false pass. The same dual-bundle methodology that gives the trace-surface elision contract its teeth (per [§Production-elision verification](#production-elision-verification)) extends to the perf surface here.

The browser smoke at `tools/xray/testbeds/perf_counter/spec.cjs` complements the grep: it serves the perf-on bundle, drives a real dispatch through the +/- buttons, and reads `performance.getEntriesByType('measure')` to confirm at least one entry per bucket lands. A passing grep is necessary but not sufficient; the smoke proves the four call sites actually fire under a real cascade.

### JVM scope

The Performance API is browser-only. The JVM half of `re-frame.performance`:

- Defines `enabled?` as `^:const false` so the macro expansion's `(if enabled? ...)` is statically dead and the JVM body runs as if instrumentation were absent.
- Expands `mark-and-measure` to `(do body...)` — pure pass-through, no instrumentation overhead.

JVM artefacts (headless tests, SSR, Pedestal/Ring services using re-frame2 for state) that want timing should reach for the host's profilers (clj-async-profiler, JFR, async-profiler).

## Forward compatibility for tools

External tools consume re-frame2 through stable surfaces. Production builds elide the entire trace surface; everything in this section is dev-only.

### Stable surfaces consumed by every tool

| Surface | Stability |
|---|---|
| `register-listener!` / `unregister-listener!` | Preserved |
| Synchronous, event-at-a-time delivery | Preserved |
| Trace event shape (`:id`, `:operation`, `:op-type`, `:time`, `:tags`) | Preserved exactly |
| `:op-type` discriminator vocabulary (`:rf.event`, `:rf.sub`, `:rf.fx`, `:rf.view`, `:rf.frame`, `:rf.machine`, `:warning`, `:error`, ...) | Preserved; new values additive |
| `:tags` for op-type-specific data (`:frame` (bare carve-out), `:rf.trace/event-id`, `:rf.event/v`, `:app-db-before`, `:app-db-after`, `:rf.trace/dispatch-id`, `:rf.trace/parent-dispatch-id`, `:rf.event/origin`, ...) | Preserved |
| Hoisted top-level fields (`:source`, `:recovery`) | Preserved |
| `re-frame.interop/debug-enabled?` (alias of `goog.DEBUG`) | Preserved |
| Compile-time elision via `goog.DEBUG=false` + `:advanced` | Preserved |
| Public registrar query API (`registrations`/`handler-meta`/`frame-ids`/`frame-meta`/`get-frame-db`/`snapshot-of`/`sub-topology`/`sub-cache`) | See [002 §The public registrar query API](002-Frames.md#the-public-registrar-query-api) |
| Hot-reload notifications (`:rf.registry/handler-registered`, `:rf.registry/handler-cleared`, `:rf.registry/handler-replaced`, `:rf.frame/created`, `:rf.frame/destroyed`) | Trace events |

### Capabilities tools depend on

- **Multi-frame UI** — frame selector; per-frame trace slicing via `(get-in ev [:tags :frame])`; per-frame app-db via `(get-frame-db id)`.
- **Epoch-per-event semantics** — each dequeued event is its own epoch (per [002 §Drain versus event](002-Frames.md#drain-versus-event--the-epoch-unit)); a drain may settle several events run-to-completion, but each (incl. an `:fx`-dispatched child or the frame-init event) yields its own record. Per-cascade correlation rides on `:dispatch-id` / `:parent-dispatch-id` (per [§Dispatch correlation](#dispatch-correlation-rftracedispatch-id--rftraceparent-dispatch-id)). The fully-assembled `:rf/epoch-record` (per [Spec-Schemas](Spec-Schemas.md#rfepoch-record)) provides the structured projection.
- **Machine trace types** — `:op-type` values (`:rf.machine/transition`, etc.) for state-machine activity.
- **Per-frame override visibility** — `:fx-overrides`/`:interceptor-overrides` are inspectable via `(frame-meta id)`.

### Programmatic interaction surfaces

- Generate test cases from trace history.
- Suggest refactors based on registry inspection.
- Drive interactions via `dispatch-sync`.
- Snapshot state, modify, restore.
- Read state in any frame; `frame-ids` enumerates them.

## JVM vs. CLJS scope

All trace functionality is **dev-build only** — production builds elide the entire trace surface on both platforms.

| Capability (dev builds) | JVM | CLJS |
|---|---|---|
| Trace event emission | ✓ | ✓ |
| `register-listener!` / `unregister-listener!` | ✓ | ✓ |
| `register-epoch-listener!` / `unregister-epoch-listener!` | ✓ | ✓ |
| Per-frame trace rings (`trace-buffer`) | ✓ | ✓ |
| Hot-reload trace events | ✓ | ✓ |
| Performance API instrumentation (`rf:event:*` / `rf:sub:*` / `rf:fx:*` / `rf:render:*` measures) | ✗ | ✓ (default-off; see [§Performance instrumentation](#performance-instrumentation)) |
| Xray panel itself | ✗ | ✓ |
| re-frame-pair attachment | ✓ | ✓ |

Trace data is just data; both platforms emit it during dev. The Performance API bridge is browser-specific; everything else works headless.

## Handler-scope: the in-scope reading at emit time

Every handler-execution boundary the runtime crosses (the router's `process-event!` step, a sub recompute, an fx dispatcher, a cofx injector, a view render wrapper) publishes the same five-slot **handler-scope reading** to the trace stream so `emit!` / `emit-error!` can hoist the relevant pieces onto each emitted event. The reading travels through ONE dynamic Var — `re-frame.trace/*handler-scope*` — bound to a `HandlerScope` record with five slots (the [§Canonical slot set](#canonical-slot-set--the-stable-contract) below is the authoritative slot vocabulary; this table is the at-a-glance summary):

| Slot | Carries | Per |
|---|---|---|
| `:trigger-handler` | Registration coord of the in-scope handler — `{:kind :id :source-coord {...}}` or nil when no source-coord is stamped. Hoisted as the top-level `:rf.trace/trigger-handler` field on every emit. | (error path) / (success path) |
| `:call-site` | Compile-time invocation coord of the surface reached through its macro form (`dispatch`, `dispatch-sync`, `subscribe`, `inject-cofx`) — `{:ns :file :line :column}` or nil for fn-form callers. Hoisted as `:rf.trace/call-site` on every emit (success and error). | (error-path landing) / (widened to success path) |
| `:dispatch-id` (HandlerScope slot) | Per-event correlation id — allocated once per dequeued event at `router.cljc`'s `process-event!` (the epoch unit; one per `dispatch`, incl. each `:fx`-dispatched child) and merged into `:tags :rf.trace/dispatch-id` of every event emitted inside that one event's cascade. `:raise`/`:always` microsteps ride the triggering event's id. (The internal scope-record slot keeps the short name; the emitted trace tag is the namespaced `:rf.trace/dispatch-id`.) | |
| `:sensitive?` | Boolean. True when the router computed a schema-derived sensitive-path overlap for the in-scope handler (the handler-meta annotation has been removed). Emitted events get a top-level `:sensitive? true` stamp; absent reads as false (per [§Privacy / sensitive data in traces](#privacy--sensitive-data-in-traces)). | |
| `:no-emit?` | Boolean. True when the in-scope handler's registration meta carries `:rf.trace/no-emit? true`. `emit!` / `emit-error!` short-circuit (no envelope allocation, no listener fan-out) when bound true. | |

### Composition

The innermost handler-scope binding wins for the meta-derived slots (`:trigger-handler` / `:sensitive?` / `:no-emit?`). The `:call-site` and `:dispatch-id` slots are **inherited** from the parent scope unless the new scope explicitly overrides them — call-site originates at macro expansion time and rides through nested scopes; dispatch-id is allocated once per cascade and survives the handler-chain → sub recompute → fx → cofx descent. The constructor and binding macros in `re-frame.trace` (`with-handler-scope`, `with-call-site`, `with-dispatch-id+call-site`) handle inheritance automatically.

For `:rf.fx/handled` specifically: the runtime rebinds `:trigger-handler` to the **fx handler's** own registration meta around the fx body's invocation and the success-path emit that follows — consumer tools jump to the `reg-fx` site, not the enclosing event handler. Reserved fx-ids (`:dispatch`, `:dispatch-later`, `:rf.fx/reg-flow`, `:rf.fx/clear-flow`) have no registration site of their own; their `:rf.fx/handled` traces carry the enclosing event handler's coord (the outer binding).

### Production elision

The whole trace surface compiles out via the outer `(when interop/debug-enabled? ...)` gate in `emit!` / `emit-error!`, so all `*handler-scope*` reads are dead code under `:advanced` + `goog.DEBUG=false`. The `:trigger-handler` slot is **not separately elided** in error traces (which survive into production via `error-emit/dispatch-on-error!`).

### Canonical slot set — the stable contract

The `HandlerScope` record's slot set is a **stable contract** consumed at every emit site (`build-event` in `re-frame.trace`) and at every binding site (the router's `process-event!`, fx / cofx dispatchers, sub recompute wrappers, view render wrappers, plus surface macros `dispatch` / `dispatch-sync` / `subscribe` / `inject-cofx`). Downstream tools (Story, Xray, re-frame2-pair, 10x) read the hoisted slots off emitted events; the table below is the authoritative slot vocabulary they may rely on.

| Slot | Value shape | Origin | Inheritance |
|---|---|---|---|
| `:trigger-handler` | `{:kind :id :source-coord {:ns :file :line :column}}` or nil. `:kind` is one of `#{:event :sub :fx :cofx :view :machine :flow :route :app-schema :error-projector}`; `:source-coord` is whatever the registrar slot's meta carried (omitted for programmatic registrations). | Read off the in-scope handler's registration meta by `handler-scope-from-meta` at scope-bind time. | Innermost wins (meta-derived). |
| `:call-site` | `{:ns :file :line :column}` or nil. Macro-expansion coord stamped by the surface form (`dispatch`, `dispatch-sync`, `subscribe`, `inject-cofx`). Nil for fn-form callers. | Stamped by the surface macro via `with-call-site` or `with-dispatch-id+call-site`. | Inherited from parent scope unless the new scope explicitly overrides. |
| `:dispatch-id` | Opaque scalar (process-monotonic counter, UUID, or any value with the [§Dispatch correlation](#dispatch-correlation-rftracedispatch-id--rftraceparent-dispatch-id) uniqueness contract). Nil outside any in-flight cascade. | Allocated once at queue time by `router.cljc`'s `enqueue!`; published into the scope by `with-dispatch-id+call-site` on entry to `process-event!`. | Inherited from parent scope unless the new scope explicitly overrides. |
| `:sensitive?` | Boolean. True iff the router computed a schema-derived sensitive-path overlap for the in-scope handler (see [§Privacy / sensitive data in traces](#privacy--sensitive-data-in-traces)). The legacy handler-meta `:sensitive?` annotation has been removed in favour of path-marked classification. | Computed in the router's `prepare-handler-ctx` and threaded onto the scope-meta as `:rf/sensitive?` for `handler-scope-from-meta` to lift into the scope's `:sensitive?` slot. | Innermost wins (scope-derived). |
| `:no-emit?` | Boolean. True iff the in-scope handler's registration meta carries `:rf.trace/no-emit? true`. | Read off the in-scope handler's registration meta by `handler-scope-from-meta` at scope-bind time. | Innermost wins (meta-derived). |

Slot values are nil when unbound. Consumers reading a slot off an event MUST treat absent and nil identically (nil-safe access).

#### Emit-side hoist contract — which slot rides which trace

`build-event` (in `re-frame.trace`) reads `*handler-scope*` once per emit and lifts the slots onto the trace envelope according to a fixed per-slot contract. The table below pins the mapping; per-slot variations live in [§The error event shape](#the-error-event-shape) and [§Privacy / sensitive data in traces](#privacy--sensitive-data-in-traces) and are summarised here:

| Slot | Hoisted as | When | Notes |
|---|---|---|---|
| `:trigger-handler` | top-level `:rf.trace/trigger-handler` | every emit (success and error) when bound | Omitted entirely when unbound (no placeholder data). Per [§`:rf.trace/trigger-handler`](#rftracetrigger-handler--naming-the-in-scope-handler). |
| `:call-site` | top-level `:rf.trace/call-site` | every emit (success and error) when bound | Per the hoist widened from error-only to all emits — the Event lens and any consumer rendering jump-to-source on success-path events (`:rf.event/dispatched`, `:rf.fx/do-fx`, `:rf.fx/handled`) needs the dispatch-site coord on the cascade entry, not just on errors. Omitted entirely when unbound. Per [§`:rf.trace/call-site`](#rftracecall-site--naming-the-invocation-line). |
| `:dispatch-id` (HandlerScope slot) | `:tags :rf.trace/dispatch-id` | every emit when bound and `:tags` does not already supply one | Caller-supplied `:tags :rf.trace/dispatch-id` wins. Per [§Dispatch correlation](#dispatch-correlation-rftracedispatch-id--rftraceparent-dispatch-id). |
| `:sensitive?` | top-level `:sensitive? true` | every emit when scope is sensitive and `:tags :sensitive?` does not supply its own reading | Caller-supplied `:tags :sensitive?` wins (queue-time `:rf.event/dispatched` computes its own reading before scope is bound). Absent reads as false. Per [§Privacy / sensitive data in traces](#privacy--sensitive-data-in-traces). |
| `:no-emit?` | **not hoisted** | — | Acts as a short-circuit signal: `emit!` / `emit-error!` skip envelope construction and listener fan-out entirely when bound true. The slot never appears on any emitted event. Per [§Trace-emission opt-out](#trace-emission-opt-out-rftraceno-emit-event-meta). |

### Extension contract — adding a new slot

The `HandlerScope` slot set is closed; adding a sixth concern (e.g. a hypothetical `:tenant-id` for multi-tenant audit) is a **coordinated edit** that crosses the implementation and this spec. To add a slot `X`, all of the following must change in the same change-set:

1. **The defrecord.** `re-frame.trace/HandlerScope`'s positional slot list gains `X` (constructors `->HandlerScope` callers update; all explicit `->HandlerScope` literals in `with-call-site` and `with-dispatch-id+call-site` add the new positional arg).
2. **The meta-derived reader** (if `X` is meta-derived). `handler-scope-from-meta` reads the slot off the registrar meta map at scope-bind time, with the same nil-when-absent convention as `:sensitive?` / `:no-emit?`.
3. **The inheritance rule** (if `X` inherits from parent scope). `inherit-scope` adds a `(nil? (:X new-scope)) (assoc :X (:X parent))` branch — mirror of the `:call-site` / `:dispatch-id` branches. Slots that are purely meta-derived (innermost-wins) need no `inherit-scope` change.
4. **The emit-side hoist** (if `X` rides emitted events). `build-event` reads the slot and stamps it on the envelope — either at the top level (with a reserved namespace, e.g. `:rf.tenancy/tenant-id`) or under `:tags`. Pin the per-slot rule in the [§Emit-side hoist contract](#emit-side-hoist-contract--which-slot-rides-which-trace) table above. Slots that are pure short-circuit signals (like `:no-emit?`) skip this step.
5. **This canonical slot list.** The two tables above ([§Canonical slot set](#canonical-slot-set--the-stable-contract) and [§Emit-side hoist contract](#emit-side-hoist-contract--which-slot-rides-which-trace)) gain a row for `X`.
6. **Reserved namespace** (if `X` is hoisted under a new namespace). Per the `:rf/*` single-root scheme in [Conventions](Conventions.md), any new top-level event field uses a reserved sub-namespace (`:rf.<area>/<slot>`); allocate the namespace in `Conventions.md` §Reserved namespaces.

Existing slots are **never repurposed** — value shape and hoist mapping are frozen. Renaming a slot or changing a slot's value shape is a breaking change to every trace consumer and is out of scope for this contract.

### History — why one record, not five Vars

The reading was originally carried by five sibling dynamic Vars (`*current-trigger-handler*`, `*current-call-site*`, `*current-dispatch-id*`, `*current-sensitive?*`, `*current-no-emit?*`) bound side-by-side at every handler-scope site. The five-Var arrangement landed across (trigger-handler / error path), (trigger-handler / success path), (call-site), (dispatch-id), (sensitive?), and (no-emit?). Per they were consolidated into one `HandlerScope` record bound to one Var: one binding-frame allocation per scope instead of five, one Var to mock in tests, one record-field edit when a sixth concern lands.

## Error contract

Errors that occur during runtime execution are emitted as **structured trace events**, with a defined `:op-type` and a Malli-schemed `:tags` payload. This satisfies AI-first property P7 (machine-readable errors) and gives every consumer of the trace stream a consistent error surface.

This section is the **authoritative model** for re-frame2's error taxonomy. Per-feature specs (010, 011, 012, etc.) reference categories defined here; the [§Error event catalogue](#error-event-catalogue) below is the single source of truth for category names, payload shapes, and recovery defaults. Two axes carry the structured information:

- **`:op-type`** — universal severity discriminator (`:error` or `:warning`). Consumers branch on severity without parsing the prefix.
- **`:operation`** — namespaced category keyword (`:rf.error/<category>`, `:rf.fx/<category>`, `:rf.ssr/<category>`, `:rf.warning/<category>`, `:rf.epoch/<category>`). The prefix carries domain provenance; the suffix names the specific category.

### The error event shape

All error trace events are open maps with these required keys:

```clojure
{:id        any                                  ;; unique trace id
 :operation :rf.error/<category>                 ;; specific category, see below
 :op-type   :error                               ;; the universal discriminator for errors
 :time      timestamp                            ;; emit time, host clock
 :source    keyword?                             ;; (when present) the trigger source — :ui, :timer, :http, ...
 :recovery  keyword?                             ;; :no-recovery, :replaced-with-default, :skipped, ...
 :rf.trace/trigger-handler                       ;; (when present) the in-scope handler at emit time
   {:kind         #{:event :sub :fx :cofx :view}
    :id           keyword
    :source-coord {:ns sym? :file string? :line int? :column int?}}
 :rf.trace/call-site                             ;; (when present) invocation coord stamped by the
   {:ns sym? :file string?                       ;; macro form. Dev-only — elided under
    :line int? :column int?}                     ;; :advanced + goog.DEBUG=false.
 :tags      {:category    :rf.error/<category>   ;; same as :operation, for consumer convenience
             :failing-id  any                    ;; the registered id that failed (event id, fx id, sub id, view id, etc.)
             :reason      string                 ;; one-sentence human description
             :frame       keyword?               ;; (when known) the frame the failure happened in
             ...}}                               ;; category-specific keys
```

`:source` and `:recovery` are top-level fields hoisted out of `:tags` by the runtime; both are present on every error event. `:frame` rides under `:tags` (every emit site that knows the frame supplies it there). The `:tags` payload's category-specific keys are documented per category below, and each category has a registered Malli schema so consumers can validate / branch on the payload safely.

### The thrown-error shape — the `:rf.error/id` ex-data contract

Most runtime failures emit a **trace event** (the shape above) and let the cascade recover. A minority are **thrown** — a registration is rejected, an optional artefact is absent, a delegation surface is reached before `(rf/init! …)`. These surface as `ex-info` rather than as trace events (the catalogue's "Surfaced as a thrown ex-info, not a trace" rows). Thrown errors carry their own canonical ex-data shape so a single consumer path — Xray's error widget, the pair-tool overlay, an `:on-error` policy, a `try`/`catch` in user code — reads one discriminator slot uniformly regardless of which surface threw.

The discriminator slot is **`:rf.error/id`** — a `:rf.error/<category>` keyword from the [§Error event catalogue](#error-event-catalogue). This is the **single normative discriminator for thrown errors**; the four-slot skeleton below is the canonical shape every `(throw (ex-info …))` site in the runtime conforms to:

```clojure
(throw (ex-info ":rf.error/<id>"               ;; message = the stringified discriminator kw
                {:rf.error/id <category-kw>     ;; CANONICAL DISCRIMINATOR — :rf.error/<category>
                 :where       'rf/<surface>     ;; the user-facing fn symbol that threw
                 :recovery    <disposition>     ;; :no-recovery / :fix-registration / :skipped / …
                 :reason      "<one sentence>"  ;; what failed + why, human-readable
                 ;; + surface-specific payload merges on top:
                 ;; :flow / :route-id / :machine-id / :received / :cycle / …
                 }))
```

Required slots on every thrown runtime error: `:rf.error/id`, `:where`, `:recovery`, `:reason`. Surface-specific payload (`:flow`, `:bad-entries`, `:cycle`, `:installed`, `:attempted`, `:received`, …) merges on top.

Two consumer pivots, both stable:

- **Message string** — the `.getMessage` / `(ex-message e)` is the stringified discriminator kw (e.g. `":rf.error/flows-artefact-missing"`). A consumer with no ex-data access (a raw stack trace, a log line) still pivots to a stable category from the message alone.
- **`:rf.error/id` slot** — `(:rf.error/id (ex-data e))` returns the discriminator as a keyword for structured branching. Tools `case` / `condp` on this slot rather than parsing the message string.

The `:where` slot names the **user-facing surface fn symbol** (`'rf/reg-flow`, `'rf/make-state-container`) so a grep-for-symbol lands on the call site in user code; `:recovery` reuses the [§Recovery contract](#recovery-contract) vocabulary plus `:fix-registration` (the caller fixes their registration map and retries); `:reason` is one human-readable sentence naming what failed and the fix.

This shape is the throw-side companion of the trace-event shape above. A category that can both throw and emit (e.g. a registration rejection that is also catalogued) uses `:operation` on the trace event and `:rf.error/id` on the thrown ex-info — the **same `:rf.error/<category>` keyword** in both slots, so a consumer reads one vocabulary across both surfaces. The pairing with `re-frame.core-artefact/defwrapper` (per [Conventions §single-import contract](Conventions.md#feature-modularity-prefix-convention)) and the missing-artefact wrappers (`:rf.error/<feature>-artefact-missing`) is the canonical reference.

#### Discriminator-slot migration — five variants collapse to one

Pre-canonicalisation, thrown-error ex-data carried the discriminator under **five different slots** depending on the surface:

| Old slot | Old shape | Where it lived |
|---|---|---|
| `:error` | `{:error :rf.error/flow-cycle …}` | flows registry / topo (the dominant variant) |
| `:type` | `{:type :rf.error/… …}` | reagent-slim React-19-removed family |
| `:kind` | `{:kind :rf.error/… …}` | http `util_json`, routing `route-too-many-keys` |
| `:reason` (overloaded) | the kw doubling as the human reason | machines-viz scxml, ai-generate |
| *(none)* | kw only in the message string, no ex-data slot | most other sites (`require-fn!`, `require-adapter!`, …) |

All five collapse to the single canonical `:rf.error/id` slot. The migration is a **rename, not a deprecation** (pre-alpha posture — no back-compat shim): the old slot is removed and `:rf.error/id` added in its place. Where the kw lived only in the message string (the *(none)* row), `:rf.error/id` is added as a new slot carrying the same keyword the message stringifies — the message string is unchanged, the structured slot is new. Where `:reason` was overloaded to carry the discriminator kw, `:reason` reverts to a human-readable sentence and `:rf.error/id` carries the keyword.

The three reference exemplars — `re-frame.flows.registry/flow-error` (was `:error`), `re-frame.late-bind/require-fn!` (was *none*), `re-frame.substrate.adapter/require-adapter!` (was *none*) — demonstrate the canonical shape on first read.

#### `:rf.trace/trigger-handler` — naming the in-scope handler

The optional top-level `:rf.trace/trigger-handler` slot names the handler whose execution produced the trace event and carries its registration-site source-coord. Tools (Xray, pair, IDE jump-to-source) render click-to-jump links from this field — given a trace event, the user lands on the line of code that defined the responsible handler.

The slot rides on **every** trace event emitted while a handler is in scope, not just errors. Success-path traces — `:rf.fx/handled`, `:rf.machine/transition`, `:rf.event/db-changed`, `:rf.fx/do-fx`, ... — carry the in-scope handler's registration coord too. Originally introduced for `:rf.error/*` only; widened so consumer tools can render jump-to-source links from any trace event in a cascade, not just errors. The error-path emit shape is unchanged — same field name, same nested map, same top-level placement.

Coverage is keyed off "is a handler currently in scope at emit time?":

| Emit context | `:rf.trace/trigger-handler` present? | Carries |
|---|---|---|
| Inside an event handler's interceptor chain | Yes | The event handler's coord |
| Inside a cofx fn body | Yes | The cofx's coord |
| Inside an fx handler body | Yes | The fx handler's coord |
| Inside a sub recompute (body fn) | Yes | The sub's coord |
| Inside a view render | Yes | The view's coord |
| Inside a machine transition (machines register as event handlers) | Yes | The machine's coord |
| At outermost dispatch with no handler resolved (`:rf.error/no-such-handler`) | No | — |
| At depth-exceeded drain rollback (`:rf.error/drain-depth-exceeded`) | No | — |
| At registration-time emits outside any handler (`:rf.registry/handler-registered`, `:rf.frame/created`) | No | — |

For `:rf.fx/handled` specifically: the slot carries the **fx handler's** own registration coord (not the enclosing event handler that produced the `:fx` vector). The runtime rebinds the handler-scope's `:trigger-handler` slot (per [§Handler-scope](#handler-scope-the-in-scope-reading-at-emit-time)) to the fx handler's meta around the fx body's invocation and the success-path emit that follows, so consumer tools jump to the `reg-fx` site — where the fx's logic actually lives — not the event handler upstream. Reserved fx-ids (`:dispatch`, `:dispatch-later`, `:rf.fx/reg-flow`, `:rf.fx/clear-flow`) have no registration site of their own; their `:rf.fx/handled` traces carry the enclosing event handler's coord (the outer binding).

The `:source-coord` payload is whatever the registrar slot's metadata holds. Macro-driven registration (`reg-event-*`, `reg-sub`, `reg-fx`, `reg-cofx`, `reg-view`, `reg-machine`, `reg-flow`, `reg-route`, `reg-app-schema`, `reg-error-projector`) stamps `:ns` / `:file` / `:line` / `:column` flat onto the meta map at compile time; the trigger-handler builder picks those keys off and re-nests them under `:source-coord`. Programmatic / REPL registrations bypass the macro path and carry no coord — in that case the entire `:rf.trace/trigger-handler` slot is omitted rather than populated with placeholder data (better no field than poison-data).

Production elision: the slot is **NOT separately elided**. The trace surface as a whole is gated by `re-frame.interop/debug-enabled?` per [§Production builds](#production-builds-zero-overhead-zero-code) — when a trace event is emitted at all, the trigger-handler field rides along on it when bound. There is no second gate that selectively drops the field while keeping the rest of the event. Apps that keep the trace surface in production (rare; opt in by setting `goog.DEBUG=true` on the `:advanced` build) get the trigger-handler coord along with every emitted event. Apps using the default `goog.DEBUG=false` `:advanced` build get neither the field nor the surrounding trace surface — the entire `(when interop/debug-enabled? ...)` branch DCEs.

Consumer access: read `(:rf.trace/trigger-handler event)` for the map, `(get-in event [:rf.trace/trigger-handler :source-coord])` for the coord, `(get-in event [:rf.trace/trigger-handler :id])` for the handler's id. No new namespace is required to read the slot.

#### `:rf.trace/call-site` — naming the invocation line

The optional top-level `:rf.trace/call-site` slot is a **sibling** of `:rf.trace/trigger-handler` (not nested) and names the **invocation line** of the user-facing surface that triggered the trace event — the `(rf/dispatch [:bad-event])` line, the `(rf/subscribe [:bad-sub])` line, the `(rf/inject-cofx :missing)` line, the `(rf/dispatch-sync [:throws])` line. Where trigger-handler answers *"where is the failing handler defined?"*, call-site answers *"where is the failing handler called?"* Tools render two clickable links per error: registration-site jump (trigger-handler) and invocation-site jump (call-site).

Shape (flat map, mirrors `:source-coord` under `:rf.trace/trigger-handler`):

```clojure
{:ns     <sym>     ;; the calling namespace
 :file   <string>  ;; the source file, per  `:file` resolution
 :line   <int>     ;; the line of the macro form
 :column <int>}    ;; optional refinement
```

The macro forms of four user-facing surfaces stamp the call-site at compile time; their `*`-suffix fn counterparts (per [Conventions §`*`-suffix naming](Conventions.md#-suffix-naming-for-fn-versions-of-macros)) do not stamp:

| Surface | Macro (stamps) | Fn-form (no stamp) |
|---|---|---|
| Dispatch (queued) | `dispatch` | `dispatch*` |
| Dispatch (sync)   | `dispatch-sync` | `dispatch-sync*` |
| Subscribe         | `subscribe` | `subscribe*` |
| Inject cofx       | `inject-cofx` | `inject-cofx*` |

For `dispatch` / `dispatch-sync`, the call-site rides through the dispatch envelope and is bound around `process-event!` so errors emitted **inside the handler chain** (handler exception, no-such-cofx, no-such-fx, schema validation failures) attach the call-site of the dispatch that triggered the cascade — the user lands on the line they wrote, not somewhere deep in framework code. For `subscribe`, the macro binds the Var around the synchronous miss path so `:rf.error/no-such-sub` and `:rf.error/frame-destroyed` carry the invocation coord. For `inject-cofx`, the macro stamps into the interceptor's closure so the `:before` body's emits carry the original `(rf/inject-cofx :id)` line — the interceptor itself may run later in the cascade, but the captured coord still points at the user's code.

Coverage:

| Reached through                  | `:rf.trace/call-site` present? |
|---|---|
| Macro form (`dispatch`, `subscribe`, `inject-cofx`, `dispatch-sync`) | Yes |
| Fn form (`dispatch*`, `subscribe*`, `inject-cofx*`, `dispatch-sync*`) | No |
| Higher-order use (`(map dispatch* xs)`) — fn form required | No |
| View-render injected `dispatch` / `subscribe` locals (per `reg-view`) | No — the wrapper delegates through `dispatch*` / `subs/subscribe` |
| Captured dispatcher / subscriber (`(rf/dispatcher)`, `(rf/bound-dispatcher)`) | No — the returned closure delegates through `dispatch*` |

Production elision (Q3=B): **dev-only**. Each macro expands to `(if interop/debug-enabled? <stamping-branch> <no-stamping-branch>)`; under `:advanced` + `goog.DEBUG=false` the closure compiler constant-folds the gate to false and the entire stamping branch DCE's — the literal `{:rf.trace/call-site {...}}` map vanishes from the bundle. Apps using `goog.DEBUG=true` builds (or any JVM build) get the field; the default `:advanced` + `goog.DEBUG=false` production build does not — the elision-probe (per [§Production builds](#production-builds-zero-overhead-zero-code)) asserts the `"rf.trace/call-site"` string fragment is absent from the production bundle. The trace surface itself is still gated; this is an additional compile-time gate that strips the call-site machinery even when the trace surface is kept live.

The mechanism is "compile-time map + handler-scope bind + emit read." The macro produces a literal map at compile time; the runtime publishes it on the `:call-site` slot of `re-frame.trace/*handler-scope*` around the underlying `*`-fn call (or threads the value through the dispatch envelope so `process-event!` binds it for the handler chain, per [§Handler-scope](#handler-scope-the-in-scope-reading-at-emit-time)); `build-event` reads the slot and hoists it onto every emitted event (success and error) when bound. The queue-time `:rf.event/dispatched` emit additionally wraps its `trace/emit!` in a `with-call-site` binding sourced from the envelope's `:call-site` slot, so the enqueue trace carries the dispatch-site coord even though `process-event!`'s cascade-wide binding hasn't fired yet. No new namespace or registry; consumer access is `(:rf.trace/call-site event)`.

the hoist widened from error-only to every emit (success and error). The Event lens redesign and any consumer rendering jump-to-source on success-path events (`:rf.event/dispatched`, `:rf.fx/do-fx`, `:rf.fx/handled`, `:rf.event/db-changed`, `:rf.sub/run`, `:rf.machine/transition`) needs the dispatch-site coord on the cascade entry, not just on errors. The semantics match the trigger-handler widening: better one consistent rule than two paths to remember.

#### `:rf/default?` — framework-auto-wrapped interceptor flag

`reg-event-db` / `reg-event-fx` / `reg-event-ctx` each wrap the user's handler into a kind-appropriate interceptor (`:rf/db-handler` / `:rf/fx-handler` / `:rf/ctx-handler`) before appending it to the user-supplied `:interceptors` chain. The wrapper appears in `(rf/handler-meta :event id) :interceptors` alongside the user's own interceptors; consumer tools (Xray, the Event lens, IDE inspectors) frequently want to surface ONLY the user's chain — the framework auto-wrapper is implementation detail, not user-authored configuration worth showing.

the auto-wrapper carries `:rf/default? true` on its interceptor map. Self-describing — tools filter without a hardcoded id allowlist:

```clojure
(->> (rf/handler-meta :event :my/event)
     :interceptors
     (remove :rf/default?))                ;; → only the user's interceptors
```

Shape and reservation:

- The flag is a top-level boolean on the interceptor map (the same map the chain stores).
- `:rf/default?` is owned by the framework under the `:rf/*` reserved namespace (per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)).
- User-supplied interceptors MUST NOT set `:rf/default?` true — the slot identifies framework-injected entries only.
- Absent (or `false`) means "user-authored." Tools that branch on the flag treat absent and `false` identically (nil-safe access).

Production elision: the flag rides on a registry-meta surface (`handler-meta`) — not on a trace event — so the trace-surface DCE gate does not apply. The flag is one keyword + one boolean per registered event, lives in process memory only, and is consumed by dev tooling that itself does not ship to production (the framework's own dispatch path does not branch on it).

#### `:rf.handler/source` — DEBUG-gated handler form-source capture

`reg-event-db` / `reg-event-fx` / `reg-event-ctx` each capture the WHOLE form the user wrote — `(reg-event-X :id ...)` — as a string at macro-expansion time and stamp it into the handler's registry metadata under `:rf.handler/source`. Tools (Xray's Event panel, the Event lens, IDE inspectors) render the captured source inline so the operator can read what code ran without leaving the browser to chase a `file:line` link. Per Xray Spec 021 §11.2 B.7 stretch (the Xray Event panel's §2.2 mockup renders the source inline immediately under step 3 "Handler invoked").

Scope: the WHOLE form — macro name, id, optional middle slot (metadata-map or interceptor-vector), and the handler-fn body — rides under one slot. The capture is mechanically `pr-str` of `&form` at expansion time, so all documented `reg-event-*` shapes round-trip without special-casing.

Consumer access:

```clojure
(->> (rf/handler-meta :event :my/event)
     :rf.handler/source)                 ;; → string or nil
```

Shape and reservation:

- Value is a string (the `pr-str` of the user-written form) or absent.
- The keyword is owned by the framework under the `:rf.handler/*` reserved namespace (per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)).
- Absent on programmatic / REPL registrations that bypass the macro path (call `re-frame.events/reg-event-db` directly as a fn).
- User-supplied `:rf.handler/source` in the registration metadata-map overrides auto-capture, mirroring the `:ns`/`:line`/`:file` override semantics of [Spec 001 §Source-coordinate capture](001-Registration.md#source-coordinate-capture-cljs-reference) so code-gen pipelines can stamp the originating source.
- Coverage is scoped to `reg-event-{db,fx,ctx}` only at the top-level registration's `:event` registry slot. Other reg-* surfaces (`reg-sub`, `reg-fx`, `reg-cofx`, `reg-flow`, `reg-route`, `reg-view`, `reg-app-schema`, `reg-error-projector`, `reg-head`, `reg-http-interceptor`) do not stamp the slot — their primary tooling surface today is `(:ns/:file/:line) → open-in-editor`. `reg-machine` is a partial exception: it does NOT carry `:rf.handler/source` on the top-level `:event` slot, but it DOES write per-(machine-id, guard-id) and per-(machine-id, action-id) entries under the `:machine-guard` / `:machine-action` registry kinds, each carrying `:rf.handler/source` for the inner fn-literal. See [Spec 005 §`:machine-guard` / `:machine-action` handler-meta surfaces](005-StateMachines.md#machine-guard--machine-action-handler-meta-surfaces). Widening to the remaining surfaces is a follow-up; the Xray Event panel and Xray Machine Inspector focused-transition lens are the load-bearing consumers.

Production elision (CLJS): **DEBUG-gated, two-layer**. The macro emission wraps the bound source-string in `(if interop/debug-enabled? <pr-str-of-form> nil)`; the registrar-side merge in `re-frame.events/merge-form-source` is wrapped in `(if-not interop/debug-enabled? m ...)`. Under `:advanced` + `goog.DEBUG=false` Closure constant-folds both gates and DCEs (a) the literal source-string bytes from the macro expansion, AND (b) the `:rf.handler/source` keyword's reachability from the merge `assoc` slot. The elision-probe (per [§Production builds](#production-builds-zero-overhead-zero-code) and `scripts/check-elision.cjs`) asserts both absences against the production bundle.

Production elision (JVM): **always-on**. `re-frame.interop/debug-enabled?` is dev-default-true on the JVM; the bundle-size argument doesn't apply to SSR / test / tooling builds. The JVM-side macro emission carries the source string into the registry meta unconditionally — JVM `clojure -M:test` users can read `(:rf.handler/source (rf/handler-meta :event id))`. The `re-frame.debug=false` JVM property (per [§JVM builds](#jvm-builds)) flips the same gate and elides capture at registration time.

The mechanism is "compile-time `pr-str` + dynamic-var thread + registrar merge." The macro produces a `pr-str` literal at compile time; the binding form publishes it on `re-frame.source-coords/*pending-form-source*` around the underlying `register-event!` call; `register-event!` reads the var via `merge-form-source` and `assoc`s it into the registered handler's metadata. No new namespace or registry slot beyond the registrar; consumer access is `(:rf.handler/source (rf/handler-meta :event id))`.

### Error namespace convention — five prefix shapes

Error categories use **five distinct namespace prefixes**:

| Prefix | Meaning | Example |
|---|---|---|
| `:rf.error/<category>` | A genuine runtime error: a contract was violated. | `:rf.error/handler-exception`, `:rf.error/no-such-sub` |
| `:rf.fx/<category>` | An fx-substrate event that rides the error envelope but is not necessarily a failure. | `:rf.fx/skipped-on-platform` |
| `:rf.cofx/<category>` | A cofx-substrate event that rides the error envelope but is not necessarily a failure. | `:rf.cofx/skipped-on-platform` |
| `:rf.ssr/<category>` | An SSR-substrate event with its own diagnostic shape (server-vs-client divergence, hash mismatches). | `:rf.ssr/hydration-mismatch` |
| `:rf.warning/<category>` | A misuse the runtime can recover from but wants surfaced. | `:rf.warning/plain-fn-under-non-default-frame` |
| `:rf.epoch/<category>` | Time-axis tooling (epoch buffer, time-travel) diagnostics. | `:rf.epoch/replay-conflict` |

The prefix carries domain provenance that consumers branch on. `:rf.fx/` marks "fx substrate emitted this"; `:rf.ssr/` marks "SSR substrate emitted this"; `:rf.warning/` marks "this is recoverable." Routing on the prefix is cheap (string-prefix dispatch) and matches how tools consume the stream.

The `:op-type` field carries the universal severity discriminator (`:error` / `:warning`) so consumers that want severity branching get it without parsing the prefix. `:op-type` answers *how serious is this?*; the prefix answers *which subsystem owns it?*.

This convention is **stable**: new error categories adopt one of the five existing prefixes. New ad-hoc prefixes are not part of the contract.

### Error event catalogue

> **Co-edit invariant.** Every `:rf.<area>/<category>` error / warning / advisory event MUST land as a row in this catalogue in the **same PR** as the owning Spec change that emits it. The vocabulary is closed: an entry referenced from a feature Spec (002, 005, 006, 010, 011, 012, 013, 014, Tool-Pair, or 009 itself) without a matching row here is a contract bug, not a deferred follow-up. Reviewers MUST reject PRs that introduce a new category without the co-edit. Per [Conventions §Error-id and warning-id grammar](Conventions.md#error-id-and-warning-id-grammar) (which reserves the prefixes; this catalogue owns the per-category grammar).

This is the **single normative catalogue** of every error / warning / advisory event the re-frame2 runtime emits. Every entry combines the five axes a consumer needs: `:operation` (the category keyword), `:op-type` (severity discriminator), trigger / meaning, default `:recovery`, and `:tags` payload keys. Each row's "Per [N]" cross-link names the owning Spec section — the **emit-site of record** — which carries the surrounding rationale and edge-case rules.

The catalogue is the union of two earlier tables (the categories table and the default-recovery table) plus the categories that were previously declared inline elsewhere in this Spec. Per [Spec-Schemas §`:rf/error-event`](Spec-Schemas.md#rferror-event) and [Spec-Schemas §Per-category `:tags` schemas](Spec-Schemas.md#per-category-tags-schemas), the per-category Malli `:tags` schemas are canonicalised in Spec-Schemas — one schema per row below. The category vocabulary is **stable**: existing categories cannot be renamed or removed; new categories are added by extending the operation namespace (per [Spec-ulation](Principles.md)).

Production-elision applies uniformly: every recovery in this catalogue applies in dev only — trace emission and schema/type validation are both production-elided per [§Production builds](#production-builds-zero-overhead-zero-code), so production builds never reach these recovery paths. See [Spec 000 §Contract C-000.35](000-Vision.md) for the production-elision-equivalence clause this section grounds.

| `:operation` | `:op-type` | Trigger / meaning | Default `:recovery` | `:tags` |
|---|---|---|---|---|
| `:rf.error/handler-exception` | `:error` | An event handler threw. | `:no-recovery` — the exception propagates; the cascade halts | `:rf.event/v` (vector), `:handler-id`, `:exception-message`, `:exception-data?` |
| `:rf.error/machine-action-exception` | `:error` | A machine action body threw during a transition (per [005 §Errors](005-StateMachines.md#errors) and [Cross-Spec-Interactions §11](Cross-Spec-Interactions.md#11-machine-action-throws)). Distinct from `:rf.error/handler-exception`: the machine layer catches the throw and emits the machine-scoped category instead, so consumers see exactly one error per failure with full machine context | `:no-recovery` — the machine cascade halts atomically: the snapshot does not commit (pre-action `[:rf/machines <id>]` slice is preserved), accumulated `:fx` from earlier slots in the same Level-2 cascade is dropped, and the `:always` microstep does not fire on the failed cascade | `:machine-id`, `:action-id`, `:state-path`, `:transition`, `:rf.event/v`, `:exception-message`, `:exception-data?` |
| `:rf.error/fx-handler-exception` | `:error` | A registered fx threw during effect resolution | `:no-recovery` — the fx is skipped; cascade continues if other fx independent | `:rf.fx/id`, `:rf.fx/args`, `:exception-message` |
| `:rf.error/sub-exception` | `:error` | A subscription's computation threw | `:replaced-with-default` — the sub returns `nil`; views see no value | `:sub-query`, `:rf.sub/id`, `:exception-message` |
| `:rf.error/no-such-sub` | `:error` | A subscription's `:<-` input refers to an unregistered sub | `:replaced-with-default` — the unresolved input is substituted with `nil`; the sub's body still runs | `:rf.sub/id`, `:unresolved-input`, `:resolved-inputs` |
| `:rf.error/schema-validation-failure` | `:error` | A `:schema`-validated value failed validation | `:no-recovery` — hard-fail to surface bugs early. Production builds elide the validation entirely (per [Spec 000 §Contract C-000.35](000-Vision.md)), so this row applies only in dev | `:where` (`:event`/`:sub-return`/`:app-db`/`:fx-args`/`:cofx`/`:on-create`/`:flow-output`), `:path`, `:value`, `:explain` (Malli explanation map) |
| `:rf.schema/violation` | `:warning` | A registered `app-db` path schema changed during hot-reload (file save re-evaluated `reg-app-schema` with a different schema) and the current `app-db` value at that path no longer validates against the **new** schema. Surfaced so dev panels highlight the stale slice; the live app continues running. Distinct from `:rf.error/schema-validation-failure` (which fires on dispatch-time validation at boundaries); this category fires at the hot-reload edge against pre-existing state. Per [010 §Schema migration on hot-reload](010-Schemas.md#schema-migration-on-hot-reload). Escalation to `:on-error` (per [§Error-handler policy](#error-handler-policy-on-error-per-frame)) is out of scope for this category — default is log-and-continue. Renamed from `:rf.spec/violation`; see [MIGRATION §M-54](../migration/from-re-frame-v1/README.md#m-54-schema-vocabulary-unification--spec--schema) | `:logged-and-skipped` — the warning fires; `app-db` is **not** auto-cleared or rewound; the live app continues | `:path` (the `reg-app-schema` registration path), `:pre-reload-schema` (the previously-registered schema form), `:post-reload-schema` (the newly-registered schema form), `:mismatching-value` (the current `app-db` value at `:path` that fails the new schema), `:frame` |
| `:rf.error/drain-depth-exceeded` | `:error` | The run-to-completion drain hit its depth limit | `:no-recovery` — always indicates a bug; halt the cascade | `:depth`, `:queue-size`, `:last-event` |
| `:rf.error/no-such-handler` | `:error` | A registrar-shaped lookup missed. Covers three distinct failure modes, discriminated by the **`:kind` tag** (mandatory on every emit): (1) `:kind :event` — a `dispatch` / `dispatch-sync` arrived with no registered event handler (emitted by router.cljc); (2) `:kind :frame` — a Tool-Pair surface (`restore-epoch`, `reset-frame-db!`) addressed a frame-id that is not in the frame registrar (emitted by epoch.cljc; see [Tool-Pair §Surface behaviour against destroyed frames](Tool-Pair.md#surface-behaviour-against-destroyed-frames)); (3) `:kind :route` — `:rf.route/handle-url-change` (or a `route-url` caller) saw a URL that matched no registered `:path` pattern (emitted by routing.cljc; see [012 §Route-not-found](012-Routing.md#route-not-found--rfroutenot-found-canonical) and the default-projector mapping at [011 §Default projector](011-SSR.md)). Consumers route on `:kind` for per-mode handling; tools that want a single "registrar miss" filter match the operation keyword alone | `:replaced-with-default` — no-op; emit the trace | `:kind` (one of `:event`, `:frame`, `:route` — mandatory), plus mode-specific keys: `:rf.event/v` + `:rf.trace/event-id` + `:frame` (`:kind :event`); `:frame` (`:kind :frame`); `:url` + `:frame` (`:kind :route`) |
| `:rf.error/dispatch-sync-in-handler` | `:error` | `dispatch-sync` was called from inside an event handler's interceptor pipeline (use `:fx [[:dispatch event]]` instead — see [002 §dispatch-sync](002-Frames.md#dispatch-sync)) | `:no-recovery` — the call is rejected. Use `:fx [[:dispatch event]]` in the effect map | `:rf.event/v`, `:enclosing-event`, `:enclosing-frame` |
| `:rf.error/effect-map-shape` | `:error` | A `reg-event-fx` handler returned a top-level effect-map key other than `:db` / `:fx` (per [MIGRATION §M-8](../migration/from-re-frame-v1/README.md#m-8-effect-map-keys-consolidated--only-db-and-fx-at-the-top-level)). The runtime drops the offending key and emits one trace per offending key; legal `:db` / `:fx` keys still apply | `:logged-and-skipped` — the offending top-level key is dropped; `:db` and `:fx` still apply. One trace per offending key | `:failing-id` (event-id), `:rf.trace/event-id`, `:rf.event/v` (vector), `:offending-key`, `:value`, `:reason` |
| `:rf.error/effect-handler-bad-return` | `:error` | A `reg-event-fx` handler returned a value that is neither a map nor `nil` (e.g. a vector, number, string, keyword — typically a typo or thinko). Without a map the runtime cannot extract `:db` / `:fx` and cannot guess the handler's intent, so the dispatch is treated as a no-op. `nil` remains the documented legal no-op and does not trigger this trace. Emitted by `events.cljc`'s `fx-handler->interceptor` | `:no-recovery` — the offending return is dropped; the dispatch is treated as a no-op | `:rf.trace/event-id` (first of the event vector, when vector-shaped), `:rf.event/v` (vector), `:returned` (the offending value), `:returned-type` (the runtime type), `:reason` |
| `:rf.error/bad-on-error-return` | `:error` | A frame's `:on-error` policy fn returned a value that did not conform to the return-map contract (per [§Error-handler policy](#error-handler-policy-on-error-per-frame)) — an unrecognised `:recovery` keyword (e.g. `:retried`), a malformed `:replacement` for the failing category (wrong shape, or a value supplied for a category with no substitutable slot), or any other contract violation. Per rf2-ciy | `:logged-and-skipped` — the policy's return is discarded; the runtime falls back to the original error's documented per-category recovery | `:original` (the input error-event's `:operation`), `:received` (the offending return value), `:reason` (one of `"unrecognised :recovery"`, `"expected an effect-map"`, `"category has no substitutable value"`, …), `:frame` |
| `:rf.error/on-error-policy-exception` | `:error` | A frame's `:on-error` policy fn itself threw while processing an error event. The runtime does NOT recursively invoke the policy on its own exception — that would risk unbounded recursion. Instead, the policy's exception is captured and the runtime falls back to the original error's documented per-category recovery. Per rf2-ciy | `:no-recovery` — the policy's exception is logged; the runtime applies the category-default recovery to the original error | `:original` (the input error-event's `:operation`), `:exception-message`, `:exception-data?`, `:frame` |
| `:rf.error/override-fallthrough` | `:error` | An override was specified but no matching id existed | `:replaced-with-default` — use the registered fx as if no override existed | `:overrides-map`, `:looked-up-id` |
| `:rf.fx/handled` | `:rf.fx` | An fx was successfully dispatched (the runtime reached the fx and either ran the registered handler without exception or completed the reserved-fx-id action). Emitted by `re-frame.fx/handle-one-fx` on the success path so the `:rf/epoch-record` `:effects` projection captures one entry per dispatched fx (per [Spec-Schemas §`:rf/epoch-record`](Spec-Schemas.md#rfepoch-record)) | n/a — success-path trace, not an error/warning | `:rf.fx/id`, `:rf.fx/args`, `:frame` |
| `:rf.fx/skipped-on-platform` | `:warning` | An fx was skipped because its `:platforms` excluded the active platform (per [011](011-SSR.md)) | `:skipped` — documented; not really an error | `:rf.fx/id`, `:rf.fx/args`, `:rf.fx/platform`, `:rf.fx/registered-platforms` |
| `:rf.cofx/skipped-on-platform` | `:warning` | A cofx injection was skipped because its `:platforms` excluded the active platform (per [011](011-SSR.md)). Mirrors `:rf.fx/skipped-on-platform`; the cofx's handler-fn is NOT invoked and no value is injected into `:coeffects`. Emitted by `re-frame.cofx/inject-cofx` after registry lookup succeeds but the platform predicate rejects | `:skipped` — the cofx's injection is skipped; the event handler still runs | `:rf.cofx/id`, `:rf.cofx/value` (only when the 2-arity `inject-cofx` supplied a per-call value), `:frame`, `:rf.fx/platform`, `:rf.fx/registered-platforms` |
| `:rf.ssr/hydration-mismatch` | `:warning` | First client render diverges from server-supplied render-tree, OR the client-computed head model differs from the server-supplied head. The `:failing-id` discriminator routes the two cases (`:rf/hydrate` for the body, `:rf.ssr/head-mismatch` for the head). Per [011 §Hydration-mismatch detection](011-SSR.md#hydration-mismatch-detection) and [011 §Mismatch detection — head](011-SSR.md#mismatch-detection--head) | `:warned-and-replaced` — body: re-render client-side; the server's HTML is replaced. Head: client renders its head; server's is replaced | `:server-hash`, `:client-hash`, `:failing-id`, `:first-diff-path?` (body), `:head-id` (head) |
| `:rf.ssr/version-mismatch` | `:warning` | The hydration payload's `:rf/version` differs from the runtime's. Emitted by the `:rf.ssr/check-version` fx dispatched from the reference `:rf/hydrate` handler. The handler still applies — degraded-but-running is the locked posture. Per [011 §The `:rf/hydrate` event](011-SSR.md#the-rfhydrate-event) and | `:warned-and-replaced` — the trace fires; hydration proceeds with the server-supplied app-db (the runtime does not abort hydration on version drift) | `:expected` (server-supplied value), `:actual` (client-side runtime value) |
| `:rf.ssr/schema-digest-mismatch` | `:warning` | The hydration payload's `:rf/schema-digest` differs from the digest of the client's currently-registered `app-schema` set. Emitted by the `:rf.ssr/check-schema-digest` fx dispatched from the reference `:rf/hydrate` handler. Useful for catching deploy drift where server and client bundles were built against different schema sets. Per [011 §The `:rf/hydrate` event](011-SSR.md#the-rfhydrate-event) and | `:warned-and-replaced` — the trace fires; hydration proceeds with the server-supplied app-db | `:expected` (server-supplied digest), `:actual` (client-computed digest) |
| `:rf.ssr/compatibility-check-skipped` | `:warning` | A compatibility-check fx (`:rf.ssr/check-version` or `:rf.ssr/check-schema-digest`) fired but no actual-value hook (`:rf2/runtime-version` for version, `:schemas/app-schemas-digest` for schema-digest) is registered, so the comparison cannot be made. The fxs never throw — degraded-but-running is the locked posture. Per [011 §The `:rf/hydrate` event](011-SSR.md#the-rfhydrate-event) and | `:logged-and-skipped` — the comparison is no-opped; hydration proceeds | `:check` (the calling fx-id, e.g. `:rf.ssr/check-version`), `:reason` (why no actual value could be resolved) |
| `:rf.warning/plain-fn-under-non-default-frame-once` | `:warning` | A plain (non-`reg-view`) Reagent fn rendered under a non-default frame; routed to `:rf/default`. Emitted at most once per `(component-id, non-default-frame-id)` pair — see [004 §Plain Reagent fns](004-Views.md). (The non-`-once` keyword `:rf.warning/plain-fn-under-non-default-frame` appears in `:rf.warning/<category>` examples but is not itself emitted — the `-once`-suffixed form is the actual runtime category) | `:warned-and-replaced` — the render proceeds, routed to `:rf/default` | `:fn-name`, `:rendered-under`, `:routed-to` |
| `:rf.warning/dispatch-from-async-callback-fell-through-to-default` | `:warning` | A `dispatch` resolved to `:rf/default` purely because the resolution chain fell through (no `:frame` opt supplied, dynamic `*current-frame*` unbound, adapter React-context value unresolvable) AND no handler for that event-id exists on `:rf/default`. The canonical trigger is a `dispatch` from an async callback (`setTimeout`, `addEventListener`, `requestAnimationFrame`, `Promise.then`) attached inside a view body — the surrounding frame-context binding does not survive the async escape (per [002 §Dispatches issued from inside a handler body](002-Frames.md#dispatches-issued-from-inside-a-handler-body)). Suppressed in single-frame apps (only `:rf/default` registered) — the footgun requires at least one non-default sibling frame. Emitted alongside the existing `:rf.error/no-such-handler` error; the warning carries the specific diagnostic with the recommended fixes. No suppression cache — the warning fires every time the conditions match. Per | `:no-recovery` — the warning is purely diagnostic; the existing `:rf.error/no-such-handler` error fires alongside and carries the canonical `:replaced-with-default` recovery for the dispatch itself | `:rf.event/v` (the dispatched event vector), `:rf.trace/event-id` (first of the vector), `:routed-to` (`:rf/default`), `:detected-at` (wall-clock ms), `:reason` |
| `:rf.warning/cross-frame-dispatch-sync-during-drain` | `:warning` | A `dispatch-sync!` was issued against a target frame while a *different* frame is mid-drain (same-frame reentry is already rejected as `:rf.error/dispatch-sync-in-handler`). The cross-frame case is not rejected — frames are independent state machines per [002 §Run-to-completion §Rules rule 1](002-Frames.md#rules) — but the cascades interleave (the target frame drains to settled while the caller's frame is still in flight, then the caller continues), which is rarely the caller's intent. Surfaced for observability tools; the dispatch proceeds. Per [002 §Cross-frame `dispatch-sync`](002-Frames.md#cross-frame-dispatch-sync-during-a-sibling-drain-warns-but-proceeds) and | `:no-recovery` — the dispatch proceeds; the warning is purely diagnostic. Frames are independent state machines so the cross-frame cascade is not a contract violation, but the interleaved ordering is rarely intentional | `:caller-frame` (the frame read from `*current-frame*`, or `:rf/none` when unbound), `:target-frame` (the `dispatch-sync!`'s target), `:other-frame` (an arbitrary mid-drain sibling — typically the caller's frame), `:rf.event/v` (the dispatched event vector), `:reason` |
| `:rf.warning/no-clock-configured` | `:warning` | A timing-sensitive substrate feature (e.g. state-machine `:after` per [005 §Delayed `:after` transitions](005-StateMachines.md#delayed-after-transitions)) was exercised on a host whose `re-frame.interop` clock primitives (`now-ms` / `schedule-after!` / `cancel-scheduled!`) weren't wired up. The runtime falls back to the host-native clock if available; this advisory surfaces so tests / agents can spot the missing wiring | `:warned-and-replaced` — fall back to the host-native clock | `:feature` (e.g. `:rf.machine/after`), `:fallback` (the host-native clock used) |
| `:rf.error/duplicate-url-binding` | `:error` | A second frame attempted `:url-bound? true` while another already owns the URL. Per [012 §Multi-frame routing](012-Routing.md#multi-frame-routing) | `:no-recovery` — the second binding is rejected; the existing URL-owning frame is unchanged | `:existing-frame`, `:offending-frame` |
| `:rf.error/system-id-collision` | `:error` | A spawn whose `:system-id` was already bound in the per-frame `[:rf/system-ids]` reverse index displaced the previous binding. Last-write-wins, matching `reg-event-fx` re-registration semantics. Per [005 §Named addressing via `:system-id`](005-StateMachines.md#named-addressing-via-system-id) and | `:warned-and-replaced` — the previous binding is displaced; the new gensym wins | `:frame`, `:system-id`, `:existing-machine` (the displaced gensym'd id), `:rebound-to` (the new gensym'd id) |
| `:rf.warning/multiple-status-set` | `:warning` | Two or more `:rf.server/set-status` calls in the same request drain. Last-write-wins; advisory for finding the conflicting handlers. Per [011 §Multiple-status policy](011-SSR.md#multiple-status-policy) | `:warned-and-replaced` — last-write wins; advisory only | `:writes` (vector of `{:status :handler-id :event}` per write), `:final-status` |
| `:rf.warning/multiple-redirects` | `:warning` | Two or more `:rf.server/redirect` calls in the same request drain. Last-write-wins. Per [011 §Redirect precedence](011-SSR.md#redirect-precedence) | `:warned-and-replaced` — last-write wins; advisory only | `:writes` (vector), `:final-redirect` |
| `:rf.warning/interceptors-in-metadata-map` | `:warning` | A `reg-event-*` registration carried `:interceptors` inside its metadata-map; the chain is silently dropped. Per [Conventions §`:interceptors` is positional, not metadata](Conventions.md#interceptors-is-positional-not-metadata-reg-event-) and | `:ignored` — the mis-placed `:interceptors` chain is dropped; registration completes with no positional interceptors | `:reg-fn` (the fn's name as a string), `:id`, `:offending-keys`, `:reason` |
| `:rf.warning/missing-doc` | `:warning` | A `reg-*` registration's metadata-map carried no `:doc` (or `:doc nil`, or `:doc ""`). The registration completes; the warning is the dev-time nudge toward documented handlers. Emitted at most once per `(kind, id)` pair within a runtime process (suppression cache resets on frame destroy, matching the other one-shot warnings). Production builds elide the check entirely via `goog.DEBUG`. Per [001 §`:doc` is dev-warned when absent](001-Registration.md#doc-is-dev-warned-when-absent) and | `:ignored` — the registration completes normally; the warning is purely diagnostic | `:kind` (one of the canonical registry kinds), `:id` (the registered id), `:source-coords` (the captured `:rf/source-coord-meta` sub-map, when available), `:reason` |
| `:rf.warning/registration-collision` | `:warning` | A `reg-*` re-registration assigned an existing id to a different fn (different source-coord pair, in CLJS reference) rather than a re-eval of the same source file. Last-write-wins by default; the warning surfaces the change so dev tools can flag accidental shadowing. Recommended on in dev. Per [001 §Re-registration of a different function — collision warning](001-Registration.md#re-registration-of-a-different-function--collision-warning) | `:warned-and-replaced` — the new fn replaces the existing slot (last-write-wins); the warning fires | `:kind`, `:id`, `:previous-coord`, `:new-coord` |
| `:rf.error/at-boundary-missing-schema` | `:error` | A `reg-event-*` call attached the `:rf.schema/at-boundary` interceptor (per [010 §Production builds](010-Schemas.md#production-builds)) but the registration's metadata-map carried no `:schema`. The boundary interceptor is structurally meaningless without a schema to validate against, so the registrar hard-rejects the call at registration time rather than waiting for the first dispatch to surface the misconfiguration. Surfaced as a thrown ex-info from `reg-event-*`, not a trace. Per [010 §Production builds](010-Schemas.md#production-builds) | `:no-recovery` — the call throws an ex-info; the offending handler is NOT registered. The two fixes: (1) attach a `:schema` to the metadata-map (recommended), or (2) remove the boundary interceptor from the positional vector | `:reg-fn` (the calling reg-fn's name as a string), `:id` (the offending event-id), `:reason`, `:recovery` |
| `:rf.warning/schema-validator-unavailable` | `:warning` | A `reg-app-schema` (or `reg-app-schemas`) call was made while the `:schemas/malli-validate` late-bind hook is unbound AND `validator-fn` is still the framework default. Per [010 §Recommended soft-pass](010-Schemas.md) the default validator returns true ("pass") when the Malli adapter ns hasn't been required at app boot — every validation site soft-passes, so boundary-validated handlers silently accept untrusted input. Emitted at most once per process from the registration sites. Suppressed when (a) `:schemas/malli-validate` is bound (Malli adapter loaded), or (b) the app explicitly registered a non-default validator via `set-schema-validator!` (apps that opted out of Malli). Production elides via `goog.DEBUG`. Per | `:ignored` — the registration completes normally; the warning is purely diagnostic | `:reason` (an actionable string that names the two fixes — require `re-frame.schemas.malli` at app boot, or call `set-schema-validator!` with a non-default fn) |
| `:rf.warning/schema-walker-opaque` | `:warning` | A `reg-app-schema` (or `reg-app-schemas`) call was made with a schema value that is NOT a Malli vector form — a registry-ref keyword (`(rf/reg-app-schema [:user] :my/user-schema)`), a compiled `m/schema` object, or any other opaque value. The schemas-walker (`re-frame.schemas.walker`) is pure data and handles only vector-form Malli EDN; per-slot `:sensitive?` / `:large?` flags inside an opaque value are silently skipped — the validation-failure trace won't redact the sensitive slot and the size-elision walker won't see the `:large?` declarations. Two workable shapes: (1) register the vector form directly so the walker can introspect it; (2) use registration-level `:sensitive?` metadata on the consuming `reg-event-*` for coarse-grained honour. Emitted at most once per process from the registration sites; symmetric with `:rf.warning/schema-validator-unavailable`. Production elides via `goog.DEBUG`. Per [010 §The `:schema` value is opaque to re-frame](010-Schemas.md) and finding #12 | `:ignored` — the registration completes normally; the warning is purely diagnostic | `:path` (the `reg-app-schema` registration path that tripped the warning), `:schema-kind` (one of `:registry-ref`, `:compiled-schema-object`, `:unknown`), `:reason` (an actionable string that names the two workable shapes) |
| `:rf.warning/large-value-unschema'd` | `:warning` | The `rf/elide-wire-value` walker observed a large string at a path with no `{:large? true}` schema metadata. Emitted at most once per `(path, frame)` pair. Advisory: add `{:large? true}` to the schema slot when the value should be elided. Per [§Size elision in traces](#size-elision-in-traces) | `:warned-and-replaced` — the warning fires; the unschema'd value is not auto-elided | `:frame`, `:path`, `:bytes`, `:hint` |
| `:rf.error/sanitised-on-projection` | `:error` | The active error projector threw or returned a non-`:rf/public-error` shape; the runtime fell back to the locked generic-500 public shape. Per [011 §Where sanitisation happens — before render](011-SSR.md#where-sanitisation-happens--before-render) | `:replaced-with-default` — runtime falls back to the locked generic-500 public-error shape | `:projector-id`, `:original-operation`, `:projection-failure-reason` |
| `:rf.error/ssr-head-resolution-failed` | `:error` | An SSR host adapter's `resolve-head` (per [011 §Head/meta contract](011-SSR.md#headmeta-contract)) caught a throw from the active route's `:head` fn (the `rf/active-head` walk or the `rf/head-model->html` emit). The host adapter degrades to an empty head fragment so the request still produces a response; the trace carries the exception for production-observability. Emitted by `re-frame.ssr.ring.lifecycle/resolve-head` in the Ring host adapter; symmetric helpers in other host adapters MUST emit the same category. Per (Mike decision, Option B — observability over silent fallback) | `:no-recovery` — the head fragment is empty (`""`); `:html-attrs` and `:body-attrs` are nil; rendering proceeds against the empty head | `:frame`, `:exception` |
| `:rf.error/ssr-render-failed` | `:error` | An SSR host adapter caught a render-time `Throwable` while building the response body (the `validate-tag-name!` rejection of e.g. `(keyword "has space")`, a view-fn `(throw (ex-info ...))`, a hiccup-walker structural error). Synthesised by `re-frame.ssr/project-render-exception!` (see `re-frame.ssr.error-listener/project-render-exception!`) so render-time and drain-time SSR failures unify under the same error projector — the wire body is the projector's `:message` / `:code` for both paths. Emitted by the JVM reference adapter's `re-frame.ssr.ring.pipeline/build-full-response` at the outer try/catch around `render-to-string`. Per [011 §View-time exceptions](011-SSR.md#view-time-exceptions) and (Mike decision Option B — unify render-time and drain-time failure surfaces) | `:projected-to-public-error` — the active error projector is driven against the synthesised trace event; the public-error's `:status` is stamped onto the response accumulator; rendering proceeds with the projector's `:message` / `:code` on the wire. Outer `:on-error` hook is reserved for transport-layer / projector-undeliverable failures | `:frame`, `:exception`, `:exception-message`, `:ex-class` |
| `:rf.error/safe-redirect-invalid-url` | `:error` | The `:rf.server/safe-redirect` fx received a `:location` string that could not be parsed as a URL (per [011 §Redirect precedence](011-SSR.md#redirect-precedence)). The redirect is rejected; the response accumulator's `:redirect` slot is unchanged. Emitted by `re-frame.ssr.response/safe-redirect-fx`. Per (Mike decision, Option A — ship safe-redirect-fx alongside redirect-fx) | `:no-recovery` — the redirect is rejected; no Location header is set | `:frame`, `:location`, `:reason` |
| `:rf.error/safe-redirect-scheme-rejected` | `:error` | The `:rf.server/safe-redirect` fx received a `:location` whose scheme is one of the rejected set (`javascript:`, `data:`, `vbscript:`) — these schemes have no safe interpretation as redirect targets (XSS via `javascript:`, data-URL phishing, IE-era `vbscript:`). Consistent with the custom-editor scheme rejection in. Emitted by `re-frame.ssr.response/safe-redirect-fx`. Per | `:no-recovery` — the redirect is rejected; no Location header is set | `:frame`, `:location`, `:scheme` |
| `:rf.error/safe-redirect-host-disallowed` | `:error` | The `:rf.server/safe-redirect` fx received a `:location` whose host is not permitted by the call's policy — either `:relative-only? true` was set and the URL carried a host, OR `:allow [...]` was set and the URL's host did not appear in the allowlist. The `:reason` tag (`:relative-only-violation` or `:not-in-allowlist`) discriminates the two modes. Mitigation for the open-redirect class (audit 2026-05-14 §P3.2): an attacker-controlled `?next=…` URL parameter cannot redirect off-origin when the application uses `:rf.server/safe-redirect` instead of `:rf.server/redirect`. Emitted by `re-frame.ssr.response/safe-redirect-fx`. Per | `:no-recovery` — the redirect is rejected; no Location header is set | `:frame`, `:location`, `:host`, `:reason` (one of `:relative-only-violation`, `:not-in-allowlist`), `:allow?` (the allowlist when supplied) |
| `:rf.epoch/restore-unknown-epoch` | `:error` | `restore-epoch` was called with an `epoch-id` that is not in the frame's current epoch history (either never recorded or aged out by `:depth`). Per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel) | `:no-recovery` — restore rejected; the frame's state is unchanged | `:frame`, `:rf.epoch/id`, `:history-size` |
| `:rf.epoch/restore-schema-mismatch` | `:error` | The recorded `:db-after` no longer validates against the currently-registered `app-schemas` set (a schema was added, tightened, or replaced since the snapshot was taken). Per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel) | `:no-recovery` — restore rejected; the frame's state is unchanged | `:frame`, `:rf.epoch/id`, `:schema-digest-recorded`, `:schema-digest-current`, `:failing-paths` |
| `:rf.epoch/restore-missing-handler` | `:error` | The recorded `app-db` references a registered-id (e.g. an active machine at `[:rf/machines <id>]`, a registered route currently in `:rf/route`) that is no longer present in the registrar. Per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel) | `:no-recovery` — restore rejected; the frame's state is unchanged | `:frame`, `:rf.epoch/id`, `:missing` (vector of `{:kind :id}`) |
| `:rf.epoch/restore-version-mismatch` | `:error` | The frame's recorded `:rf/snapshot-version` (per [Spec-Schemas §`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot)) is incompatible with the currently-loaded machine definition. Per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel) | `:no-recovery` — restore rejected; the frame's state is unchanged | `:frame`, `:rf.epoch/id`, `:machine-id`, `:version-recorded`, `:version-current` |
| `:rf.epoch/restore-during-drain` | `:error` | `restore-epoch` was called while the frame's run-to-completion drain is still in flight (per [002 §Run-to-completion dispatch](002-Frames.md#run-to-completion-dispatch-drain-semantics)). Restore is rejected; the user retries after settle. Per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel) | `:no-recovery` — restore rejected; the user retries after settle | `:frame`, `:rf.epoch/id` |
| `:rf.epoch/restore-non-ok-record` | `:error` | `restore-epoch` was called against an epoch record whose `:outcome` is not `:ok` (a halted-cascade record kept for devtools introspection; see [Spec-Schemas §`:rf/epoch-record` §Outcomes](Spec-Schemas.md#outcomes)). Restore is rejected because a halted record's `:db-after` is partial state the cascade never settled to. | `:no-recovery` — restore rejected; the frame's state is unchanged | `:frame`, `:rf.epoch/id`, `:rf.epoch/outcome`, `:halt-reason` |
| `:rf.epoch/reset-frame-db-during-drain` | `:error` | `reset-frame-db!` was called while the frame's drain was still running. Pair-tool injection is rejected; the caller retries after settle. Per [Tool-Pair §Pair-tool writes](Tool-Pair.md#pair-tool-writes--state-injection) | `:no-recovery` — pair-tool injection rejected; the caller retries after settle | `:frame` |
| `:rf.epoch/reset-frame-db-schema-mismatch` | `:error` | `reset-frame-db!` was called with a `new-db` value that fails the frame's currently-registered `app-schema` set. The injection is rejected; `app-db` is unchanged. Per [Tool-Pair §Pair-tool writes](Tool-Pair.md#pair-tool-writes--state-injection) and [010 §Per-frame schemas](010-Schemas.md#per-frame-schemas) | `:no-recovery` — pair-tool injection rejected; `app-db` is unchanged. The new-db failed the frame's registered app-schema set; the failing paths are surfaced in `:tags :failing-paths` | `:frame`, `:failing-paths` |
| `:rf.error/no-such-fx` | `:error` | A dispatched fx-id has no registered handler (and was not redirected by `:fx-overrides`). Per [002 §`:fx` ordering and atomicity guarantees](002-Frames.md#fx-ordering-and-atomicity-guarantees). Emitted by `re-frame.fx/handle-one-fx` after override resolution and reserved-id matching both miss | `:no-recovery` — the fx is dropped; cascade continues with remaining `:fx` entries | `:rf.fx/id`, `:rf.fx/args`, `:frame` |
| `:rf.error/no-such-cofx` | `:error` | An `inject-cofx` interceptor referenced a cofx-id with no registered handler. Per [002 §Effects and coeffects](002-Frames.md). Emitted by `re-frame.cofx/inject-cofx` when registry lookup misses. Sibling interceptors continue; the ctx flows through unchanged | `:no-recovery` — the cofx injection is a no-op; subsequent interceptors run with the ctx unchanged | `:rf.cofx/id`, `:rf.cofx/value` (only when the 2-arity `inject-cofx` was used), `:rf.trace/event-id` (the event that ran the offending interceptor chain, when available) |
| `:rf.error/frame-destroyed` | `:error` | A `dispatch` / `dispatch-sync` / `subscribe` arrived against a frame whose `(:lifecycle frame-record)` carries `:destroyed? true`. Per [002 §Frame lifecycle](002-Frames.md#frame-lifecycle). The router rejects the call; for `subscribe` the result is `nil`. Emitted from router.cljc and subs.cljc | `:no-recovery` — dispatch / subscribe is rejected; cascade halts (or returns nil for the subscribe path) | `:frame`, `:rf.event/v` (when called from dispatch), `:rf.sub/query-v` (when called from subscribe) |
| `:rf.error/flow-eval-exception` | `:error` | A flow's `:output` fn threw during the recompute walk inside an event handler's interceptor pipeline (per [013 §Flow tracing](013-Flows.md#flow-tracing)). Distinct from `:rf.flow/failed`, which is the per-flow op-type-`:flow` trace; this is the cascade-level error event the router emits when the throw escapes the flow walk | `:no-recovery` — the cascade halts; the snapshot is uncommitted. The per-flow `:rf.flow/failed` op-type-`:flow` event also fires for attribution | `:frame`, `:rf.event/v`, `:exception`, `:where :flow-eval`, `:flow-id` (the failing flow's id — the prod-surviving attribution; the flow id alone, as there is no real flow value to carry) |
| `:rf.error/unwrap-bad-event-shape` | `:error` | The `:rf/unwrap` interceptor saw an event vector that does not conform to the expected `[event-id payload-map]` shape (per [Conventions §Unwrap interceptor](Conventions.md)) | `:no-recovery` — the interceptor returns the original ctx unchanged; the downstream handler receives the unwrapped vector unmodified | `:rf.event/v`, `:expected` (the contract string) |
| `:rf.error/machine-raise-depth-exceeded` | `:error` | A machine action's `:raise` cascade exceeded its depth limit (default 16). Per [005 §Bounded depth](005-StateMachines.md#bounded-depth). The cascade halts; the snapshot is not committed | `:no-recovery` — the `:raise` cascade halts; the snapshot is not committed | `:machine-id`, `:depth` |
| `:rf.error/machine-always-depth-exceeded` | `:error` | A machine's `:always` microstep loop exceeded its depth limit (default 16). Per [005 §Bounded depth](005-StateMachines.md#bounded-depth). The cascade halts; the snapshot is not committed | `:no-recovery` — the `:always` microstep loop halts; the snapshot is not committed | `:machine-id`, `:depth`, `:path` (the visited-states vector) |
| `:rf.error/machine-unresolved-guard` | `:error` | A machine's `:guard` reference is a keyword that does not resolve in the machine's `:guards` map. Per [005 §Guards](005-StateMachines.md#guards) and [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table). Surfaced at registration time (registration fails) and as a fallback at transition time | `:no-recovery` — registration fails (or, at runtime fallback, the transition is rejected) | `:guard` (the unresolved keyword), `:machine-id` |
| `:rf.error/machine-unresolved-action` | `:error` | A machine's `:action` reference is a keyword that does not resolve in the machine's `:actions` map. Per [005 §Actions](005-StateMachines.md#actions) and [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table). Surfaced at registration time (registration fails) and as a fallback at transition time | `:no-recovery` — registration fails (or, at runtime fallback, the action is skipped) | `:action` (the unresolved keyword), `:machine-id` |
| `:rf.error/machine-bad-guard-form` | `:error` | A machine's `:guard` value is neither a keyword nor a fn (per [005 §Guards](005-StateMachines.md#guards)). Surfaced at registration time | `:no-recovery` — registration fails | `:guard` (the offending value) |
| `:rf.error/machine-bad-action-form` | `:error` | A machine's `:action` value is neither a keyword nor a fn (per [005 §Actions](005-StateMachines.md#actions)). Surfaced at registration time | `:no-recovery` — registration fails | `:action` (the offending value) |
| `:rf.error/machine-bad-state-form` | `:error` | A snapshot's `:state` is neither a keyword nor a vector path (per [005 §State paths](005-StateMachines.md)). Surfaced at runtime when normalising the snapshot's state | `:no-recovery` — the snapshot's state is rejected at normalisation; downstream walks halt | `:state` (the offending value) |
| `:rf.error/machine-bad-on-clause` | `:error` | A state-node's `:on <event-id>` value is not one of the four legal shapes (keyword target, vector path target, vector of guarded transition maps, or single transition map; per [005 §Transitions](005-StateMachines.md)). Surfaced at registration time | `:no-recovery` — registration fails | `:value` (the offending shape) |
| `:rf.error/machine-action-wrote-db` | `:error` | A machine action's effect map contained `:db`. Per [005 §Hard-disallow `:db`](005-StateMachines.md). The runtime drops the `:db` key; remaining effects flow through | `:logged-and-skipped` — the `:db` key is dropped from the action's effect map; remaining effects flow through | `:machine-id`, `:action-id`, `:state-path`, `:offending-value` |
| `:rf.error/machine-grammar-not-in-v1` | `:error` | A machine definition uses a v1-out-of-scope grammar feature (e.g. `:type :parallel`, `:history`) that the implementation does not claim per the [005 §Capability matrix](005-StateMachines.md#capability-matrix). Registration is rejected | `:no-recovery` — registration is rejected | `:machine-id`, `:feature` (the unsupported key), `:substitute` (pointer to the recommended pattern) |
| `:rf.error/machine-unhandled-event` | `:error` | An event arrived at a machine and no transition matched at any state along the active path. Per [005](005-StateMachines.md). Advisory; the snapshot is unchanged. (Older drafts spelled this `:rf.warning/machine-unhandled-event`; the `:rf.error/` form is canonical) | `:ignored` — advisory; the snapshot is unchanged | `:machine-id`, `:event`, `:state` |
| `:rf.error/machine-state-not-in-definition` | `:error` | A snapshot's `:state` references a state-id that is not declared in the machine's `:states` definition (e.g. a snapshot from an older version of the machine). Per [005](005-StateMachines.md). (Older drafts spelled this `:rf.warning/machine-state-not-in-definition`; the `:rf.error/` form is canonical) | `:no-recovery` — the transition is rejected | `:machine-id`, `:state` |
| `:rf.error/machine-snapshot-version-mismatch` | `:error` | A persisted machine snapshot's `:rf/snapshot-version` is incompatible with the currently-loaded machine definition (per [Spec-Schemas §`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot)). Distinct from `:rf.epoch/restore-version-mismatch`, which is the epoch-history restore path. (Older drafts spelled this `:rf.warning/machine-snapshot-version-mismatch`; the `:rf.error/` form is canonical) | `:no-recovery` — the snapshot is rejected | `:machine-id`, `:version-recorded`, `:version-current` |
| `:rf.error/machine-always-self-loop` | `:error` | An `:always` entry's `:target` resolves to the declaring state itself (keyword equal to the state's own key, or vector equal to its own path). An internal `:always` with no `:target` is permitted (the action-microstep pattern). Per [005 §Self-loop forbidden at registration](005-StateMachines.md#self-loop-forbidden-at-registration). Registration is rejected | `:no-recovery` — registration is rejected | `:state` (the declaring state-keyword), `:machine-id` |
| `:rf.error/machine-compound-state-missing-initial` | `:error` | A compound state declares `:states` but no `:initial`. Per [005 §Initial-state cascading](005-StateMachines.md#initial-state-cascading). Registration is rejected | `:no-recovery` — registration is rejected | `:machine-id`, `:state` |
| `:rf.error/machine-final-state-compound` | `:error` | A state declaring `:final? true` ALSO declares `:states` (or `:initial`). Compound states cannot themselves be final — their finality is expressed by a leaf inside them. Surfaced at registration time. Per [005 §Final states](005-StateMachines.md#final-states-final--on-done--output-key) | `:no-recovery` — registration is rejected | `:machine-id`, `:state` |
| `:rf.error/machine-final-state-has-transitions` | `:error` | A `:final?` state ALSO declares `:on`, `:always`, `:after`, `:spawn`, or `:spawn-all`. Final means final — no further transitions (`:entry` / `:exit` actions ARE permitted). Surfaced at registration time. Per [005 §Final states](005-StateMachines.md#final-states-final--on-done--output-key) | `:no-recovery` — registration is rejected | `:machine-id`, `:state`, `:offending-keys` |
| `:rf.error/machine-output-key-without-final` | `:error` | A non-final state declared `:output-key`. The key is only legal on a state with `:final? true`. Surfaced at registration time. Per [005 §Final states](005-StateMachines.md#final-states-final--on-done--output-key) | `:no-recovery` — registration is rejected | `:machine-id`, `:state`, `:output-key` |
| `:rf.error/machine-spawn-all-bad-shape` | `:error` | A child invoke-spec inside a `:spawn-all` block is missing `:id` or both `:machine-id` and `:definition`; or `:spawn-all` is not a vector; or the join-event slots are missing per the required-iff rules. Surfaced at registration time. Per [005 §Spawn-and-join via `:spawn-all`](005-StateMachines.md#spawn-and-join-via-spawn-all) | `:no-recovery` — registration is rejected | `:machine-id`, `:state`, `:reason` |
| `:rf.error/machine-spawn-all-duplicate-id` | `:error` | Two child invoke-specs inside the same `:spawn-all` block share an `:id` keyword. Each `:id` must be unique. Surfaced at registration time. Per [005 §Spawn-and-join via `:spawn-all`](005-StateMachines.md#spawn-and-join-via-spawn-all) | `:no-recovery` — registration is rejected | `:machine-id`, `:state`, `:duplicate-id` |
| `:rf.error/machine-spawn-all-with-spawn` | `:error` | A state node declares both `:spawn` and `:spawn-all`. The combination is rejected. Surfaced at registration time. Per [005 §Spawn-and-join via `:spawn-all`](005-StateMachines.md#spawn-and-join-via-spawn-all) | `:no-recovery` — registration is rejected | `:machine-id`, `:state` |
| `:rf.error/machine-parallel-nested-not-supported` | `:error` | A parallel region's own state-tree declares `:type :parallel` (nested parallel regions). Not supported in v1. Surfaced at registration time. Per [005 §Parallel regions](005-StateMachines.md) and the [005 §Capability matrix](005-StateMachines.md#capability-matrix) | `:no-recovery` — registration is rejected | `:machine-id`, `:state` |
| `:rf.error/no-such-route` | `:error` | A `route-url` call (or one of its callers) addressed a `:route-id` that is not in the routing registrar (per [012](012-Routing.md)) | `:no-recovery` — the call throws; the caller chooses how to surface the failure | `:route-id` |
| `:rf.error/missing-route-param` | `:error` | A `route-url` build-from-pattern call did not supply a value for a required path parameter (per [012 §URL building](012-Routing.md)) | `:no-recovery` — the call throws; the caller chooses how to surface the failure | `:param` (the missing param keyword), `:route-id` |
| `:rf.error/route-too-many-keys` | `:error` | A `match-url` call parsed a URL whose query string carried more than `default-max-decoded-keys` unique keys (default 10000). Symmetric routing-side companion to the HTTP `:rf.error/malformed-json :reason :too-many-keys`. Defends long-running JVM hosts against URL-driven keyword-interning DoS (per [012 §Keyword-interning cap on query keys + values](012-Routing.md#keyword-interning-cap-on-query-keys--values) and). | `:no-recovery` — the parse throws; the navigation event propagates the failure | `:url`, `:limit`, `:count` |
| `:rf.error/bad-app-schemas-arg` | `:error` | `app-schemas` was called with a non-keyword, non-map, non-nil argument (per [010 §App-db schemas](010-Schemas.md)) | `:no-recovery` — the call throws | `:received` (the offending value), `:expected` (the contract string) |
| `:rf.error/unknown-preset` | `:error` | `reg-frame` metadata's `:preset` value is not in the closed set `#{:default :test :story :ssr-server}` (per [Spec-Schemas §`:rf/preset-expansion`](Spec-Schemas.md#rfpreset-expansion)) | `:no-recovery` — the call throws; registration of the offending frame fails | `:preset` (the offending value), `:valid` (the closed set) |
| `:rf.error/adapter-already-installed` | `:error` | A second `install-adapter!` call was made without an intervening `dispose-adapter!` (per [006 §Single adapter per process](006-ReactiveSubstrate.md#single-adapter-per-process)) | `:no-recovery` — the call throws; the existing adapter remains installed | `:installed` (the existing adapter), `:attempted` (the offending second adapter) |
| `:rf.error/no-adapter-specified` | `:error` | `(rf/init! …)` was called with no args, nil, or a non-map argument (e.g. a keyword). The only legal call shape is `(rf/init! adapter-map)` — require the adapter ns and pass its `adapter` Var, e.g. `(rf/init! reagent/adapter)`. Per [006 §Adapter selection at boot](006-ReactiveSubstrate.md#adapter-selection-at-boot) and. Surfaced as a thrown ex-info, not a trace | `:no-recovery` — the call throws | `:where` (`'init!`), `:received` (when nil/keyword/non-map), `:expected`, `:reason` |
| `:rf.error/render-on-headless-adapter` | `:error` | `render` was called on the plain-atom (JVM/SSR) adapter, which only supports `render-to-string` (per [006](006-ReactiveSubstrate.md)) | `:no-recovery` — the call throws; user should use `render-to-string` on this adapter | `:reason` |
| `:rf.error/derived-container-replaced` | `:error` | `replace-container!` was called on a derived container (a `make-derived-value` result). Derived containers are read-only — there is no slot to write into — so the core's `replace-container!` choke point both emits this trace AND throws the canonical ex-info (per [§The thrown-error shape](#the-thrown-error-shape--the-rferrorid-ex-data-contract)). Per [006 §`make-derived-value`](006-ReactiveSubstrate.md#make-derived-value-source-containers-compute-fn--container) | `:no-recovery` — the call throws; the adapter `replace-container!` is not invoked. Write to the source container(s) instead | `:reason`; thrown ex-data also carries `:rf.error/id`, `:where` (`'rf/replace-container!`) |
| `:rf.error/no-hiccup-emitter-bound` | `:error` | `render-to-string` was called before the SSR namespace bound the hiccup emitter via `set-hiccup-emitter!` (per [011](011-SSR.md)) | `:no-recovery` — the call throws; SSR namespace must be required so `set-hiccup-emitter!` runs | `:reason`, `:render-tree` |
| `:rf.error/frame-context-corrupted` | `:error` | A function-component frame-id read (`_currentValue` on the shared React context) observed a value `coerce-context-value` cannot resolve to a frame keyword — nil, false, a number, an empty string, or a JS object. Real-world triggers: a subtree rendered through an unwrapped portal, a Provider authored with a non-keyword `:value`, or a library mutating `_currentValue` externally. Per [006 §Frame-provider via React context](006-ReactiveSubstrate.md) | `:replaced-with-default` — the function-component resolution chain falls through to `:rf/default`; the error event provides the diagnostic surface | `:received` (the offending value), `:type` (a short keyword tag — `:nil` / `:boolean` / `:number` / `:string` / `:empty-string` / `:keyword` / `:symbol` / `:map` / `:vector` / `:sequential` / `:collection` / `:fn` / `:js-object`), `:reason` |
| `:rf.error/flow-cycle` | `:error` | A flow registration introduced a cycle in the flow-dependency graph (per [013 §Topological ordering](013-Flows.md)). Registration is rejected | `:no-recovery` — flow registration is rejected | `:cycle` (the offending flow ids) |
| `:rf.error/flow-missing-id` | `:error` | A `reg-flow` call's flow map omitted `:id` (per [013](013-Flows.md)) | `:no-recovery` — flow registration is rejected | `:flow` (the offending map) |
| `:rf.error/flow-bad-inputs` | `:error` | A `reg-flow` call's flow `:inputs` was not a vector of paths (per [013](013-Flows.md)) | `:no-recovery` — flow registration is rejected | `:flow`, `:reason`, `:bad-entries?` (vector of the offending entries when at least one entry was malformed — the entries that were not a non-empty vector of scalar path-keys; omitted when `:inputs` itself was not a vector) |
| `:rf.error/flow-bad-output` | `:error` | A `reg-flow` call's flow `:output` was not a fn (per [013](013-Flows.md)) | `:no-recovery` — flow registration is rejected | `:flow`, `:reason` |
| `:rf.error/flow-bad-path` | `:error` | A `reg-flow` call's flow `:path` was not a vector (per [013](013-Flows.md)) | `:no-recovery` — flow registration is rejected | `:flow`, `:reason`, `:bad-elements?` (vector of the offending path elements when the failure mode was non-scalar elements — values that were not a keyword / string / integer / symbol / boolean; omitted when `:path` itself was not a vector or was empty) |
| `:rf.error/flows-artefact-missing` | `:error` | A flow API (`reg-flow`, `clear-flow`, the flow fxs) was called but the optional `day8/re-frame2-flows` artefact is not on the classpath. Per [MIGRATION §M-31 artefact splits](../migration/from-re-frame-v1/README.md). Surfaced as a thrown ex-info, not a trace | `:no-recovery` — the call throws an ex-info; user adds `day8/re-frame2-flows` to deps | `:where` (the calling fn), `:reason` |
| `:rf.error/ssr-artefact-missing` | `:error` | An SSR API (`render-to-string`, `render-tree-hash`, `reg-error-projector`, `project-error`) was called but the optional `day8/re-frame2-ssr` artefact is not on the classpath. Surfaced as a thrown ex-info, not a trace | `:no-recovery` — the call throws an ex-info; user adds `day8/re-frame2-ssr` to deps | `:where` (the calling fn), `:reason` |
| `:rf.error/routing-artefact-missing` | `:error` | A routing API (`reg-route`, `match-url`, `route-url`) was called but the optional `day8/re-frame2-routing` artefact is not on the classpath. Surfaced as a thrown ex-info, not a trace | `:no-recovery` — the call throws an ex-info; user adds `day8/re-frame2-routing` to deps | `:where` (the calling fn), `:reason` |
| `:rf.error/schemas-artefact-missing` | `:error` | A schemas API (`reg-app-schema`) was called but the optional `day8/re-frame2-schemas` artefact is not on the classpath. Surfaced as a thrown ex-info, not a trace | `:no-recovery` — the call throws an ex-info; user adds `day8/re-frame2-schemas` to deps | `:where` (the calling fn), `:path`, `:reason` |
| `:rf.error/machines-artefact-missing` | `:error` | A machines API (`reg-machine`, `reg-machine*`) was called but the optional `day8/re-frame2-machines` artefact is not on the classpath. Surfaced as a thrown ex-info, not a trace | `:no-recovery` — the call throws an ex-info; user adds `day8/re-frame2-machines` to deps | `:where` (the calling fn), `:machine-id`, `:reason` |
| `:rf.error/http-artefact-missing` | `:error` | A managed-HTTP API was called but the optional `day8/re-frame2-http` artefact (per [014 §Implementation status](014-HTTPRequests.md#implementation-status)) is not on the classpath. Surfaced as a thrown ex-info, not a trace | `:no-recovery` — the call throws an ex-info; user adds `day8/re-frame2-http` to deps | `:where` (the calling fn), `:reason` |
| `:rf.error/epoch-artefact-missing` | `:error` | The dev-only `reset-frame-db!` pair-tool write surface (per [Tool-Pair §Pair-tool writes](Tool-Pair.md#pair-tool-writes--state-injection), rf2-zq55) was called but the optional `day8/re-frame2-epoch` artefact is not on the classpath. The wrapper cannot degrade silently — its caller's invariant is "undo works after this call" — so it raises rather than returning a sentinel like the other epoch surfaces (`epoch-history` / `restore-epoch` / `register-epoch-listener!` / `projected-record` / `projected-history`, which return `[]` / `false` / `nil`). Surfaced as a thrown ex-info, not a trace | `:no-recovery` — the call throws an ex-info; user adds `day8/re-frame2-epoch` to deps | `:where` (the calling fn), `:reason` |
| `:rf.warning/route-shadowed-by-equal-score` | `:warning` | A `reg-route` registered a pattern whose [`:rf/route-rank`](Spec-Schemas.md#rfroute-rank) tuple equals an already-registered pattern's; the new route shadows the old per stable sort order (per [Spec-Schemas §`:rf/route-rank`](Spec-Schemas.md#rfroute-rank) and [012 §Route ranking algorithm](012-Routing.md#route-ranking-algorithm)) | `:warned-and-replaced` — the new route registers; equal-score sibling is shadowed by stable sort | `:route-id` (the new id), `:shadowed` (the displaced id) |
| `:rf.warning/no-not-found-route` | `:warning` | An unmatched URL arrived but no `:rf.route/not-found` route was registered. The runtime falls back to a built-in placeholder view (a minimal `<h1>Not Found</h1>` page) so the request still produces a response. Per [012 §Route-not-found](012-Routing.md#route-not-found--rfroutenot-found-canonical) | `:warned-and-replaced` — falls back to the built-in placeholder; the warning surfaces the missing registration | `:url`, `:frame`, `:reason` |
| `:rf.warning/decode-defaulted` | `:warning` | A managed-HTTP request fell through to the default `:auto` decode pipeline because no `:decode` was supplied (per [014 §Default decode](014-HTTPRequests.md)). Informational; the auto-decode is supported | `:ignored` — informational; auto-decode proceeds normally | `:request-id`, `:url`, `:content-type`, `:resolved-decoder` |
| `:rf.warning/write-after-destroy` | `:warning` | The substrate adapter's `replace-container!` was called with a nil container — the frame was likely destroyed mid-drain or before a scheduled write fired. Per [006](006-ReactiveSubstrate.md) | `:no-recovery` — the write is dropped; the substrate's `replace-container!` is not invoked | `:reason` |
| `:rf.http/cljs-only-key-ignored-on-jvm` | `:warning` | A managed-HTTP request supplied a CLJS-only request key (`:mode`, `:cache`, `:referrer`, `:integrity`) that the JVM transport cannot honour. The key is ignored. Per [014](014-HTTPRequests.md) | `:ignored` — the unsupported key is dropped; the request proceeds with the remaining keys | `:key`, `:url` |
| `:rf.http/retry-attempt` | `:info` | A managed-HTTP attempt failed with a retryable category and the runtime is scheduling another attempt. Per [014 §Retry and backoff](014-HTTPRequests.md#retry-and-backoff) | `:retried` — a new attempt is scheduled; the consumer sees the trace and the eventual final outcome via `:on-failure` / `:on-success` | `:request-id`, `:url`, `:attempt`, `:max-attempts`, `:failure` (a `:rf.http/*` failure-category map), `:next-backoff-ms` |
| `:rf.http/aborted-on-actor-destroy` | `:info` | A managed-HTTP request was aborted because the spawned state-machine actor that issued it was destroyed (parent state exit, parent's `:after` firing, `:spawn-all` cancel-on-decision, frame destroy, or imperative `[:rf.machine/destroy]`). The reply lands as a standard `:rf.http/aborted` failure with `:reason :actor-destroyed`. Per [014 §Abort on actor destroy](014-HTTPRequests.md#abort-on-actor-destroy) and [005 §Cancellation cascade — in-flight `:rf.http/managed` aborts](005-StateMachines.md#cancellation-cascade--in-flight-rfhttpmanaged-aborts) | n/a — informational lifecycle trace | `:request-id` (when set), `:actor-id` (the destroyed spawned-actor address), `:url` |
| `:rf.http.interceptor/registered` | `:info` | A `reg-http-interceptor` succeeded on a frame's request-side middleware chain. Per [014 §Middleware](014-HTTPRequests.md#middleware) | n/a — informational lifecycle trace | `:frame`, `:id` |
| `:rf.http.interceptor/cleared` | `:info` | A `clear-http-interceptor` removed an existing interceptor slot (no trace fires for clear-of-unknown-id). Per [014 §Middleware](014-HTTPRequests.md#middleware) | n/a — informational lifecycle trace | `:frame`, `:id` |
| `:rf.error/http-interceptor-failed` | `:error` | A request-side interceptor's `:before` fn threw. The runtime emits this category, then re-throws — `re-frame.fx` catches the re-throw and emits the cascade-level `:rf.error/fx-handler-exception`; the request is NOT dispatched. Per [014 §Middleware §Failure mode](014-HTTPRequests.md#failure-mode) | `:no-recovery` — the interceptor's throw propagates; the request is not dispatched | `:frame`, `:interceptor-id`, `:url`, `:cause` |
| `:rf.error/http-bad-interceptor` | `:error` | `reg-http-interceptor` was called with invalid args — non-keyword positional id, non-fn positional before, non-map opts, or non-keyword `:frame` inside opts (per the reshaped signature `(reg-http-interceptor id opts? before)` from). Surfaced as a thrown ex-info from the registration call, not a trace. Per [014 §Middleware](014-HTTPRequests.md#middleware) | `:no-recovery` — the call throws an ex-info; registration fails | `:where` (`'rf/reg-http-interceptor`), `:received` (a map of the positional + opts args), `:reason` |
| `:rf.error/http-bad-retry-on` | `:error` | A `:rf.http/managed` fx was invoked with a `:retry :on` set that contains a non-retryable or unknown category. The closed retryable set is `#{:rf.http/transport :rf.http/cors :rf.http/timeout :rf.http/http-4xx :rf.http/http-5xx}`; `:rf.http/aborted` / `:rf.http/decode-failure` / `:rf.http/accept-failure` are explicitly rejected, and any keyword outside `:rf.http/*` is rejected. Surfaced as a thrown ex-info from the fx-call site, not a trace. Per [014 §Closed-set `:retry :on` validation](014-HTTPRequests.md#closed-set-retry-on-validation) | `:no-recovery` — the call throws an ex-info; the request is not dispatched | `:where` (`':rf.http/managed`), `:bad-members` (the offending keywords from `:on`), `:retryable-set` (the closed set), `:reason` |
| `:rf.route.nav-token/stale-suppressed` | `:error` | An async result arrived carrying a `:nav-token` that no longer matches the active route's token; the result is silently suppressed. Per [012 §Navigation tokens](012-Routing.md#navigation-tokens--stale-result-suppression). (`:op-type :error` because the suppression is the failure mode the consumer needs to see) | `:logged-and-skipped` — the async reply is suppressed; the active navigation cascade continues unchanged | `:carried-token`, `:current-token`, `:event-id` |
| `:rf.frame/drain-interrupted` | `:frame` | A frame's drain loop detected `(:destroyed? (:lifecycle frame))` mid-cycle; remaining queued events are dropped. Per [002 §Edge cases worth pinning](002-Frames.md#edge-cases-worth-pinning). Lifecycle event, not error-shaped (per the `:frame/*` lifecycle family) | n/a — lifecycle event, not error-shaped. Remaining queued events are dropped silently | `:frame`, `:dropped-count` |

The `:op-type` column is the universal severity discriminator: `:error` halts or recovers a specific operation; `:warning` is an advisory the runtime emitted alongside continuing default behaviour; `:info` and `:fx` are non-failure success-path / lifecycle traces that share the trace envelope; `:frame` belongs to the `:frame/*` lifecycle family. Consumers branch on `:op-type` for severity routing and on `:operation` for category-specific handling.

`:rf.fx/skipped-on-platform` and `:rf.cofx/skipped-on-platform` are technically *warnings* not errors, but they ride the same envelope and route through the same listener path; consumers can branch on `:op-type` (`:warning` vs `:error`) if they want to distinguish.

### Schemas

Each category's `:tags` shape is registered as a Malli schema so consumers can validate without ad-hoc parsing. The full set of per-category `:tags` schemas is canonicalised in [Spec-Schemas §Per-category `:tags` schemas](Spec-Schemas.md#per-category-tags-schemas) — one schema per category enumerated in the [§Error event catalogue](#error-event-catalogue) above. Two examples (the rest follow the same shape):

```clojure
;; Conceptual; the actual registration mechanism is implementation-specific.

;; :frame is the top-level field on the trace event itself (per the error event
;; shape above), not a :tags key — it appears on every trace event, error or
;; otherwise. The schemas below describe the :tags payload only.

(def HandlerExceptionTags
  [:map
   [:category          [:= :rf.error/handler-exception]]
   [:failing-id        :keyword]
   [:reason            :string]
   [:event             [:vector :any]]
   [:handler-id        :keyword]
   [:exception-message :string]
   [:exception-data    {:optional true} :any]])

(def SchemaValidationTags
  [:map
   [:category        [:= :rf.error/schema-validation-failure]]
   [:failing-id      :keyword]
   [:reason          :string]
   [:where           [:enum :event :sub-return :app-db :fx-args :cofx :on-create :flow-output]]
   [:path            [:vector :any]]
   [:value           :any]
   [:explain         :any]                          ;; Malli explanation shape
   [:registered-path {:optional true} [:vector :any]]]) ;; (:where :app-db only) registration root; :path is the failing leaf — see Spec/010

;; ... and so on for each category — see Spec-Schemas for the full set.
```

Pattern-level: every implementation registers an equivalent set of schemas. The category vocabulary is stable and additive — new categories can be added but existing ones cannot be renamed or removed.

### Server error projection — public boundary

For SSR specifically, the structured trace event is the **internal** record (rich, full detail, monitor-bound) and a separate **public projection** is written to the HTTP response (sanitised, client-safe). The internal trace event is **never** serialised to the client. The projection mechanism is owned by [011 §Server error projection](011-SSR.md#server-error-projection); the trace stream is unchanged by it. Tools that want full error detail subscribe via `register-listener!` as usual; the response carries only the locked `:rf/public-error` shape.

The runtime emits `:rf.error/sanitised-on-projection` (above) when the projector itself fails, so monitor dashboards see when the public boundary fell back to the generic-500 shape.

### Recovery contract

The `:recovery` field on the trace event tells consumers (dev panels, error-monitor integrations, tooling) what the runtime did:

- `:no-recovery` — the error propagated; the event was not handled.
- `:replaced-with-default` — the runtime used a default value (e.g., `:no-such-handler` falling through to a no-op).
- `:retried` — the runtime retried (with an upper bound) and surfaces the result.
- `:skipped` — the runtime declined to act (`:rf.fx/skipped-on-platform`, `:rf.cofx/skipped-on-platform`).
- `:warned-and-replaced` — the runtime emitted the warning and did its default action anyway (e.g., `:rf.ssr/hydration-mismatch` warn-and-replace mode).
- `:logged-and-skipped` — the runtime emitted the trace and dropped the offending input; sibling inputs still apply (e.g., `:rf.error/effect-map-shape` drops the offending top-level effect-map key while `:db` / `:fx` still apply).

A user-registered error-handler can intercept any error category and decide policy. The default error-handler routes everything to the trace stream and proceeds with the documented per-category recovery. Error-handler policy is registered per-frame via the `:on-error` slot in `reg-frame` metadata (per [002-Frames §`:on-error`](002-Frames.md)); for cross-frame observation, `register-listener!` filtering on `:op-type :error` (or on the `:rf.error/*` `:operation` namespace) sees every error event without modifying behaviour. (The v1 process-wide `reg-event-error-handler` surface is dropped — see [MIGRATION.md §M-26](../migration/from-re-frame-v1/README.md#m-26-drift-sweep-drops--v1-surfaces-with-no-v2-equivalent-or-absorbed-by-canonical-surfaces).)

### Error-handler policy (`:on-error` per frame)

```clojure
(rf/reg-frame :rf/default
  {:on-error
   (fn handle-error [error-event]
     ;; error-event conforms to :rf/trace-event with :op-type :error.
     ;; Return a map with :recovery telling the runtime how to proceed.
     ;; Returning nil = no policy override; the runtime falls back to its default.
     (case (:operation error-event)
       :rf.error/handler-exception
       (do (log-to-monitoring error-event)
           {:recovery :no-recovery})

       :rf.error/schema-validation-failure
       (do (log-to-monitoring error-event)
           {:recovery :replaced-with-default
            :replacement (:default-value (:tags error-event))})

       :rf.error/no-such-handler
       ;; ignore — the default :replaced-with-default behaviour is fine
       nil

       ;; default: trust the runtime's per-category recovery
       nil))})
```

The error-handler:

- Receives the structured error event (an `:rf/trace-event` with `:op-type :error`).
- Returns either `nil` (no policy override; runtime applies its default per-category recovery) or a return map whose shape is pinned below.

#### Return-map contract

The return map is a **closed shape**. Three keys are recognised:

```clojure
{:recovery     <keyword>   ;; REQUIRED — one of the documented recovery keywords
 :replacement  <value>     ;; OPTIONAL — pinned to :replaced-with-default; see below
 :notes        <string>}   ;; OPTIONAL — free-form, surfaced on the resulting trace
```

Normative semantics (RFC 2119):

- The `:recovery` value MUST be one of `:no-recovery`, `:replaced-with-default`, `:skipped`, `:warned-and-replaced`, `:logged-and-skipped`, `:ignored` — the same closed set listed under [§Recovery contract](#recovery-contract). A return map whose `:recovery` is `:retried` (or any other value outside the closed set) MUST be rejected by the runtime: a `:rf.error/bad-on-error-return` trace is emitted (category, `:tags {:received <map> :reason "unrecognised :recovery"}`, `:recovery :logged-and-skipped`) and the runtime falls back to the original error's documented per-category recovery.
- `:replacement` is **only meaningful when `:recovery` is `:replaced-with-default`**. For every other `:recovery` value the runtime MUST ignore `:replacement` if present. (Implementations MAY additionally emit `:rf.warning/replacement-ignored-on-recovery` advising the caller that the key is being dropped.)
- When `:recovery` is `:replaced-with-default`, the shape of `:replacement` is **category-specific** — it is the value the runtime substitutes for whatever the failing operation would have returned. The shape SHALL match the failing operation's normal return type:
  - `:rf.error/handler-exception` — the failed `reg-event-fx` / `reg-event-db` / `reg-event-ctx` handler's return. The `:replacement` value SHALL be an effect-map (`{:db <map>}`, `{:fx [[fx args] ...]}`, or `{:db <map> :fx [...]}`) per [Spec-Schemas §`:rf/effect-map`](Spec-Schemas.md#rfeffect-map). The runtime applies the replacement *as if the handler had returned it* — the `:db` slot atomically swaps, the `:fx` slot is walked. If `:replacement` is a non-map (or a map whose shape violates the effect-map contract), the runtime MUST emit `:rf.error/bad-on-error-return` (`:tags {:received <value> :reason "expected an effect-map"}`) and fall back to `:no-recovery` (cascade halts; no substitution applied).
  - `:rf.error/schema-validation-failure` — the validated value. `:replacement` SHALL be of the same value-position the validator was checking (an event vector, a sub return, an `app-db` map, an fx-args value, a cofx value, or a machine `on-create` payload, per the `:where` axis carried on the failing trace).
  - For categories whose default `:recovery` is already `:no-recovery` and which have no "natural" substitutable value (registration-time failures, drain-depth-exceeded, adapter-already-installed, every `:rf.epoch/restore-*` rejection, every `:rf.machine/*` registration-time rejection), `:replacement` is **not honoured** — the runtime MUST emit `:rf.error/bad-on-error-return` (`:tags {:received <value> :reason "category has no substitutable value"}`) and fall back to the category's documented default recovery.
- `:retry-count` is **not** part of the return contract and never was. The framework does not implement retry semantics for failed handlers, fx, or any other operation that error-handlers see. The `:retried` recovery keyword exists in the enum but is reserved for `:rf.http/retry-attempt` traces emitted by the managed-HTTP fx — that surface owns its own backoff and attempt-counting per [014 §Retry and backoff](014-HTTPRequests.md#retry-and-backoff). An `:on-error` handler that wants a failed event to fire again MUST dispatch a fresh event (per the [§Composition with libraries](#composition-with-libraries-sentry-honeybadger-etc) idiom below); the runtime never re-runs the failing handler on the policy's behalf.
- `:notes` is unconstrained free-form text. The runtime SHALL include it under `:tags :notes` on the augmented trace event (the runtime re-emits the original error event with `:recovery` updated to the policy's decision and `:tags :notes` carrying the policy's note). Implementations MAY truncate to a reasonable upper bound (≥ 256 chars).
- **Exceptions raised by the `:on-error` handler itself.** If the policy fn throws, the runtime MUST NOT recursively invoke the policy on its own exception — that would risk an unbounded loop. Instead, the runtime emits `:rf.error/on-error-policy-exception` (`:tags {:original <the-input-error-event-vector> :exception-message <str>}`, `:recovery :no-recovery`) and falls back to the original error's documented per-category recovery. The policy fn is treated like any other host fn that throws: the cascade halts at the offending point, the exception does not propagate to user code.
- **Ordering inside the runtime.** When an error is raised, the runtime:
  1. Emits the structured error event (`:operation`, `:op-type`, `:source`, `:recovery` set to the category default, `:tags`).
  2. Invokes the in-scope frame's `:on-error` policy fn with the event from step 1.
  3. Reads the return value; validates it against the contract above. On invalid return (bad `:recovery`, malformed `:replacement`, policy-fn exception), emits the corresponding `:rf.error/bad-on-error-return` / `:rf.error/on-error-policy-exception` trace and uses the category default.
  4. Applies the recovery: either the category default (on `nil` / invalid return) or the policy-chosen recovery. For `:replaced-with-default` with a valid `:replacement`, substitutes the value into the failing slot and resumes the cascade; for `:no-recovery`, halts; for `:skipped` / `:logged-and-skipped`, drops the failing input and continues; for `:warned-and-replaced`, emits an additional `:warning` trace and substitutes (category-specific default). The runtime never retries.

Each frame has at most one `:on-error` handler. Re-registering the frame replaces the policy; the default error-handler applies until a `:on-error` is registered.

**Production elision.** Unlike the rest of the trace surface, the `:on-error` slot is NOT gated by `re-frame.interop/debug-enabled?`. It rides a small always-on error-emit substrate (`re-frame.error-emit`) that survives `:advanced` + `goog.DEBUG=false`. Registered policy fns fire on production handler exceptions; the substrate carries `:operation`, `:op-type :error`, and the category-specific `:tags`. Dev-side enrichments (`:dispatch-id`, `:rf.trace/trigger-handler`, the `:rf.error/bad-on-error-return` / `:rf.error/on-error-policy-exception` validation traces) ride the trace surface and continue to elide; policy-fn exceptions are caught silently in CLJS prod (per Spec 009 §1052 the cascade does not abort). The substrate currently covers `:rf.error/handler-exception` — the primary production-monitoring case; widening to other categories is a non-breaking follow-on.

#### Style rubric for `:reason` strings (non-normative)

The structured fields of an error trace event (`:operation`, `:failing-id`, `:frame`, category-specific `:tags`) are the *contract* — tools branch on those. The `:reason` string is the **one-sentence human-facing accompaniment** that error-monitor dashboards, dev panels, and tooling surface to a reader. The voice matters. The goal is wording that helps the reader fix the problem in one read.

A good `:reason` string:

1. **Names the failing thing** — the registered id, in backticks. `'Event handler `:cart/add-item` threw an exception.'` not `'A handler threw.'`
2. **Names the broken contract** — what was expected. `'... expected to return an effects-map; got a vector.'` not `'... bad return value.'`
3. **Suggests the fix in one clause** when the fix is unambiguous from the structured payload. `'... did you mean to wrap it in `{:fx [...]}` ?'` not `'See docs.'`
4. **Stays under ~20 words.** The structured `:tags` payload carries the detail; `:reason` is the headline.
5. **Is mechanically composable from the `:tags` payload.** Implementations build `:reason` from a category-specific template plus `:tags` substitutions; nothing in `:reason` is information not also present in structured form.

Example pairs (acceptable → preferred):

```
:rf.error/handler-exception
  acceptable: "Handler threw."
  preferred:  "Event handler `:cart/add-item` threw: TypeError: Cannot read property 'price' of undefined."

:rf.error/no-such-sub
  acceptable: "Subscription input not found."
  preferred:  "Subscription `:cart/total` depends on `:cart/items` which is not registered. Did you forget to require the cart namespace?"

:rf.error/schema-validation-failure
  acceptable: "Schema validation failed."
  preferred:  "Event vector for `:cart/add-item` failed schema at path [1 :id]: expected :uuid, got \"abc\"."

:rf.error/drain-depth-exceeded
  acceptable: "Drain depth exceeded."
  preferred:  "Drain depth limit (100) exceeded — likely a dispatch loop. Last event in queue: `[:cart/recompute]`."

:rf.error/effect-map-shape
  acceptable: "Effect-map returned a disallowed top-level key."
  preferred:  "Effect-map for `:cart/save` returned top-level key `:dispatch`; only `:db` and `:fx` are allowed at the top level — wrap as `:fx [[:dispatch event]]`."
```

Implementations that omit a `:reason` (returning the empty string) are conformant — the structured payload is the contract — but the rubric is the recommended voice for the reference implementation and for ports.

#### Composition with libraries (Sentry, Honeybadger, etc.)

Error-monitoring libraries integrate by registering an `:on-error` handler that forwards the structured error event to the monitoring service AND returns `nil` to delegate recovery:

```clojure
(rf/reg-frame :rf/default
  {:on-error
   (fn forward-and-defer [error-event]
     (sentry/capture-event (sentry-shape error-event))
     nil)})                                         ;; runtime applies default recovery
```

Multiple monitoring concerns compose in user code (one `:on-error` handler that fans out to several services). For cross-frame observation that doesn't modify recovery, prefer `register-listener!` filtered on `:op-type :error`.

## Notes

### Why this is its own Spec

Tracing is the connective tissue between the runtime and every tool that observes it. Splitting it into its own Spec:

- Locks the data shape independently of any specific tool.
- Documents the forward-compat commitments tools depend on.
- Separates "framework emits events" (002 territory) from "framework provides a tap surface" (this Spec).
- Documents the prod-side Performance API instrumentation channel (gated on `re-frame.performance/enabled?`, default-off) alongside the dev-side trace stream — two compile-time-elidable surfaces with distinct gates and distinct consumers (see [§Performance instrumentation](#performance-instrumentation)).

## Open questions

> **SA-4 classification.** Per [SPEC-AUTHORING §SA-4](SPEC-AUTHORING.md): the only item that previously lived here ("Trace allocation cost in dev when no listeners") classifies as **`:resolved`** — the `(rf/configure :trace-buffer {:cascades-retained 0})` escape hatch IS the answer. Migrated to `## Resolved decisions` below.

## Resolved decisions

### Listener ordering

Multiple listeners may register concurrently. **Listener-invocation order is not contract** — tools must not depend on the order in which sibling listeners receive a given event. Each listener receives the same event independently; nothing about the order in which the runtime walks the listener registry is guaranteed across builds, hosts, or registry implementations. The same rule applies to `register-listener!` (per [§Subscription / consumption](#subscription--consumption) and [§Listener invocation rules](#listener-invocation-rules)) and `register-epoch-listener!` (per [`register-epoch-listener!` §Invocation rules](#register-epoch-listener--assembled-epoch-listener)).

### Trace allocation cost in dev when no listeners

In dev, `interop/debug-enabled?` is true, so the emit body runs even when no listeners are registered: the runtime allocates the event map, routes it into the in-flight frame's cascade slot in the per-frame ring (or skips the ring entirely for frameless emits per the B3 ruling), and walks the (empty) listener registry. The per-frame ring's per-cascade append is the floor cost when in-cascade. Tools that want maximum dev-loop throughput can `(rf/configure :trace-buffer {:cascades-retained 0})` to disable the ring; the synchronous-delivery path still works and the user-listener fan-out remains zero-cost when no listeners are attached.

### Trace correlation across the cascade

Two cascade-wide channels ride on **every** trace event emitted inside a cascade — neither is scoped to errors:

1. **`:rf.trace/dispatch-id`** under `:tags` (per [§Dispatch correlation](#dispatch-correlation-rftracedispatch-id--rftraceparent-dispatch-id)). Grouping raw trace events by cascade is a single-key filter — `(filter #(= cascade-id (get-in % [:tags :rf.trace/dispatch-id])) events)`. Tools that need cascade *trees* walk `:rf.trace/parent-dispatch-id` upward across `:rf.event/dispatched` events (the inter-cascade lineage channel).

2. **`:rf.trace/trigger-handler`** at the top level (per [§`:rf.trace/trigger-handler` — naming the in-scope handler](#rftracetrigger-handler--naming-the-in-scope-handler)). Names the handler whose code produced the event and carries its registration coord — so jump-to-source links work from every trace event in a cascade, not just errors. Rides on `:rf.fx/handled`, `:rf.machine/transition`, `:rf.event/db-changed`, `:rf.fx/do-fx`, `:rf.sub/run`, `:rf.view/render`, and all `:rf.error/*` events whenever a handler is in scope at emit time. Omitted outside any handler scope (registration-time emits, outermost-dispatch lookup failures).

Per-cascade structured projection lives in the assembled `:rf/epoch-record` (per [Spec-Schemas](Spec-Schemas.md#rfepoch-record)) — the raw `:rf.trace/dispatch-id` / `:rf.trace/trigger-handler` channels are the lower-level primitives.

### Per-frame trace rings — cascade-keyed retention

Resolved per **** (2026-05-25). The trace surface is partitioned per-frame and sized by cascade count, not event count. Five resolved sub-questions:

1. **Cross-frame cascades.** Each frame retains the traces of cascades that executed in it, keyed by their own `:rf.trace/dispatch-id`. Cross-frame consumers (pair-mcp, monitoring tools, multi-frame story sessions) merge by `:dispatch-id` across rings; the framework does not maintain a process-global cross-frame index.
2. **Frameless trace events.** Original ruling **B** (frameless events → `:rf/default` + `nil`-id cluster) was overturned the same day by hot-reload memory-leak analysis. Amended ruling: **B3 + B4 combined.** B3 — frameless trace events skip rings entirely; they stream live to listeners only, never retained anywhere. B4 — hot-reload re-emits are deduplicated by shape at the emit site (the registrar tracks last-emitted shape per `(kind, id)` pair and suppresses unchanged re-emits). Together: rings hold cascades exclusively; the live stream filters reload-noise; the registry is the source of truth for "what's registered right now". Hot-reload is a non-event for the trace bus.
3. **Off-box streaming wire format.** Cascade bundles (not raw trace events). Matches the storage unit; off-box consumers (re-frame2-pair-mcp `trace-window` / `watch-epochs`, monitoring tools) receive one bundle per stream tick rather than reconstructing cascades from individual events.
4. **In-process `trace-buffer` API.** Per-frame, cascade bundles by default; `:flat true` opt-in for callers that want raw trace events. Signature: `(rf/trace-buffer frame-id)` / `(rf/trace-buffer frame-id opts)`.
5. **Cascade size bound.** No per-cascade trace cap by design. The operator's tuning lever is the cascade-count knob (`:rf.trace/cascades-retained`, default 50, per-frame override), not trace-volume per cascade. A cascade with 50K traces takes one slot like a cascade with 5 traces takes one slot.

Single retention knob: `:rf.trace/cascades-retained` (frame metadata, default 50). When cascade #N+1 arrives, the oldest cascade slot (and every trace event ever emitted under its `:dispatch-id`) is evicted as a unit. No per-trace-type cap, no per-cascade trace cap, no other knobs. The full surface lives at [§Per-frame trace rings (cascade-keyed, dev-only)](#per-frame-trace-rings-cascade-keyed-dev-only).

Rejected alternatives: "don't emit sub-skip" (preserves useful signal by routing instead), "filter at consumer level" (too late — real events already evicted), "bigger flat ring 10×" (procrastinates the architectural mismatch), "frameless `:rf/default` cluster" (hot-reload memory leak; overturned in favour of B3+B4).

### Trace event for app-db changes

`:db` mutations happen inside `do-fx`. The runtime emits a separate `:rf.event/db-changed` trace event on every dispatch whose handler returned a new db value. Tools that want before/after pairs read the `:rf/epoch-record`'s `:db-before` / `:db-after` slots, which the runtime captures atomically across the event's whole cascade (one epoch per event — per [002 §Drain versus event](002-Frames.md#drain-versus-event--the-epoch-unit)) rather than per individual `:rf.event/db-changed` emit.

### Privacy / sensitive data in traces

> Cross-reference: see [Security.md §Privacy / secret handling](Security.md#privacy--secret-handling) for the framework-wide pattern-level posture this section grounds — per-slot schema `:sensitive?` metadata is the canonical privacy marker. (The legacy handler-meta `:sensitive?` annotation has been removed; sensitive data marking is path-based per the upcoming data-classification mechanism — separate spec doc; in progress.)

Trace events carry dispatched event vectors, handler return values, and (under [§Trace event for app-db changes](#trace-event-for-app-db-changes)) `app-db` snapshots — any of which may contain user input that should not leave the developer's machine: passwords, auth tokens, payment details, PII captured from form fields. Tools that ship traces off-box (error-monitor forwarders per [§Wiring an external error monitor](#wiring-an-external-error-monitor-sentry-rollbar-honeybadger-etc), remote dev dashboards, the Xray-MCP / re-frame2-pair servers per [Tool-Pair.md](Tool-Pair.md)) must not emit that data verbatim.

The declaration surface is schema-first. Apps declare sensitive app-db slots with `{:sensitive? true}` on Malli schema metadata; path-scoped handlers automatically install an internal redaction interceptor that redacts matching event-payload paths for trace/error emission while the handler body still receives the raw `:event` coeffect. The complementary site is `(rf/redact-interceptor [[:password] ...])`, a positional interceptor that scrubs named payload keys on the trace surface.

> **Unified wire-elision surface.** `:sensitive?` (privacy) and `:large?` (size) are **two orthogonal predicates over the same wire-boundary elision walker** — both consumed by `rf/elide-wire-value` (per [§Size elision in traces](#size-elision-in-traces) below and [API.md §`rf/elide-wire-value`](API.md#elide-wire-value-the-wire-boundary-walker)). The walker emits the `:rf/redacted` sentinel for sensitive values and the `:rf.size/large-elided` marker for large values; when both predicates match the **sensitive drop wins** (the size marker would leak `:path` / `:bytes` and is suppressed). Same shape, two flags, one helper.

#### The `:sensitive?` registration metadata key

> **NOTE:** The handler-meta `:sensitive?` registration-metadata
> annotation has been removed. Sensitive data marking is path-based per the
> upcoming data-classification mechanism (separate spec doc; in progress) —
> sensitivity is a property of the data value at a path, not of the
> handler that touched it. The trace-event `:sensitive?` top-level stamp
> (see [§Trace-event field: `:sensitive?` at the top level](#trace-event-field-sensitive-at-the-top-level)) is now driven exclusively by the schema-derived
> overlap (see [§Schema-installed redaction](#schema-installed-redaction)).

Previously this section described an optional **boolean** `:sensitive?`
key on the `:rf/registration-metadata` map. That annotation no longer
participates in the privacy machinery. The two always-on substrate
boundaries (event-emit, error-emit) no longer drop / redact based on
handler-meta sensitivity — they rely on the per-path elision wire-walker
populated from app-schema `:sensitive?` slot meta. Schema-installed
redaction (below) and `redact-interceptor` (the positional interceptor) are
the supported declaration sites.

#### Schema-installed redaction

For handlers scoped with `rf/path`, the router compares the path interceptor's app-db focus with the frame's schema-derived sensitive declarations. When a sensitive schema path is under the handler's db focus, the router installs an internal redaction interceptor for the corresponding event-payload path.

```clojure
(rf/reg-app-schema [:auth]
  [:map
   [:username :string]
   [:password {:sensitive? true} :string]])

(rf/reg-event-db :auth/login
  [(rf/path :auth)]
  (fn [auth [_ payload]]
    ;; The handler receives the raw payload.
    (assoc auth :last-login payload)))

;; Trace/error emissions for [:auth/login {:username "ada" :password "shh"}]
;; carry [:auth/login {:username "ada" :password :rf/redacted}].
```

Behaviour:

- **Canonical declaration.** `{:sensitive? true}` on app-schema slot metadata is the canonical per-path privacy declaration. It hydrates `[:rf/elision :sensitive-declarations]` for the active frame.
- **Positional interceptor.** `(rf/redact-interceptor [[:password] ...])` scrubs named payload keys before the trace surface sees them; complementary to schema-marked paths.
- **Trace-only redaction.** The internal redaction interceptor writes the redacted event to framework trace/error emission slots. The regular `:event` coeffect stays raw so handlers can perform the requested work.
- **Sentinel keyword.** Redacted values are replaced with the framework-reserved `:rf/redacted` sentinel. Apps MUST NOT use it as a legitimate payload value.

#### Trace-event field: `:sensitive?` at the top level

The `:rf/trace-event` schema (per [Spec-Schemas §`:rf/trace-event`](Spec-Schemas.md#rftrace-event)) gains an optional top-level `:sensitive?` boolean. Tools branch on it directly:

```clojure
(rf/register-listener!
  :my-app/remote-shipper
  (fn [trace-event]
    (when-not (:sensitive? trace-event)              ;; default off-box-ship policy
      (ship-to-remote-dashboard! trace-event))))
```

Filter-shape integration: `(rf/trace-buffer :rf/default {:sensitive? false :flat true})` returns only the non-sensitive events from the default frame's ring. The filter vocabulary at [§Filter vocabulary](#filter-vocabulary) gains one row:

| Key | Type | Semantics |
|---|---|---|
| `:sensitive?` | boolean | Match the top-level `:sensitive?` field. Pass `false` to exclude sensitive events; pass `true` to select only sensitive events. Absent ⇒ no constraint. |

#### Listener filtering semantics

Listeners installed via `register-listener!` and `register-epoch-listener!` (per [§The listener API](#the-listener-api)) receive **every** trace event regardless of `:sensitive?` — the flag is a payload axis the listener inspects, not a delivery gate. Two reasons: (1) on-box developer tooling (10x, the trace panel, the in-process ring buffer) needs to see sensitive traces during local dev; (2) routing the filter into the runtime would force every consumer to opt in to seeing sensitive data and complicate the elision contract. Filtering lives in the **listener body**, not in the framework's dispatch path.

Framework-published listener integrations MUST default to suppressing `:sensitive? true` events:

- The Sentry / Honeybadger forwarder samples at [§Wiring an external error monitor](#wiring-an-external-error-monitor-sentry-rollbar-honeybadger-etc) wrap their `register-listener!` body in `(when-not (:sensitive? trace-event) ...)` by default. Apps that want the events shipped (rare; only when the monitor is itself the trust boundary, e.g. a self-hosted Sentry inside the same VPN) opt in by removing the guard.
- The re-frame2-pair server (per [Tool-Pair.md §How AI tools attach](Tool-Pair.md#how-ai-tools-attach)) MUST drop or redact `:sensitive? true` events before forwarding to the AI surface. The default policy is **drop**; apps that want sensitive cascades visible to the pair tool configure the policy explicitly.
- The Xray-MCP server (per [Tool-Pair.md](Tool-Pair.md)) MUST default-drop `:sensitive? true` events from the cascade graph it materialises.

User-side listeners (in-app recorders, dev panels, custom forwarders) have no framework-imposed policy — they receive every event and decide on a per-app basis. The recommended discipline is identical: gate any off-box egress on `(when-not (:sensitive? trace-event) …)`.

The **user-controllable config knob** each consumer exposes for the default-suppress policy follows a fixed verb convention per [Conventions §Privacy config-knob naming](Conventions.md#privacy-config-knob-naming-on-box-ui-vs-off-box-wire-egress): on-box devtools UI consumers use the `show-sensitive?` verb under the `:trace/*` ns (e.g. `:trace/show-sensitive?` — UI visibility), while off-box wire-egress consumers (the MCP triplet, the re-frame2-pair preload) use the **unqualified** `include-sensitive?` verb (e.g. `{:rf.size/include-sensitive? false}` on the elision policy map — wire egress). Both default to suppress; the verb choice tells the reader which trust boundary the knob governs without re-deriving from context.

#### Retroactive-scrub on `set-show-sensitive!` false

Resolved per ****.

The on-box `show-sensitive?` knob is **not a one-way trapdoor**. Each consumer's `(set-show-sensitive! v)` is gated at ingest time only — it decides whether the next emit lands in the consumer's downstream buffer (or in the framework's per-frame ring), not whether buffer reads see existing payloads. Without an explicit retroactive-scrub rule the toggle has a privacy hole:

```text
1. show-sensitive? = true     (engineer flips on to debug redaction policy)
2. sensitive cascade emitted  (auth/login event lands in every consumer's buffer)
3. show-sensitive? = false    (engineer flips back off, expecting privacy restored)
4. panels keep showing the buffered :sensitive? payloads forever
```

The normative rule: every on-box `:trace/show-sensitive?` consumer (Xray's `trace-bus`, Story's per-variant `ui.trace` buffer, future devtools that hold a buffer downstream of the on-box flag) MUST clear its trace buffer on the `true → false` transition. `false → false`, `false → true`, and `true → true` MUST NOT clear (no buffered sensitive risk exists for those transitions, and clearing would discard legitimate non-sensitive history without cause).

The clear MUST be **whole-buffer**, not selective. Non-sensitive history buffered alongside the sensitive cascade is intentionally lost. Selective scrubbing is unsafe because a single sensitive event can have caused later non-sensitive cascades — sub recomputes, render args, dispatched-from-fx events — whose payloads structurally reveal the redacted value via the shape of what they consumed. Clearing the whole buffer is the simplest correct semantic; any "smarter" filter risks reintroducing the leak through a derived event.

The clear MUST also reset the per-consumer `[● REDACTED N]` suppressed-events counter so the indicator drops in lockstep with the buffer (the counter is conceptually "since last clear", not "since process start"). Per Xray's `trace-bus/clear-buffer!` and Story's `ui.trace/clear-buffer!` — both already cascade through to the suppressed-counter reset.

Implementation note (non-normative): the reference implementation uses a callback-registry pattern (`config/register-toggle-off-callback!`) so the config layer can invoke the consumer's clear-buffer fn without taking a require dependency on it (the consumer requires the config; not vice versa). Callbacks run on every `true → false` transition; one callback's exception MUST NOT block the others (privacy is the load-bearing concern, and a partial clear is strictly better than no clear). Off-box wire-egress consumers (`include-sensitive?` knobs on the MCP triplet, re-frame2-pair preload) are out of scope for this rule — their flag governs wire emission, not a persistent buffer, so the transitions are stateless.

#### Production-elision behaviour

The `:sensitive?` mechanism is **dev-time only** — both pieces of it ride the trace surface and elide with it:

- The trace surface's `:advanced` + `goog.DEBUG=false` build elides `emit!` entirely (per [§Production builds](#production-builds-zero-overhead-zero-code)). No trace event is allocated, no listener body runs, no `:sensitive?` stamp is built. The privacy mechanism is moot because there is no trace to privacy-protect.
- Schema-installed redaction is internal router machinery. In production builds that retain always-on event/error substrates, the same redacted event shape is used at those boundaries; dev-only trace allocation still DCEs when the trace surface is disabled.
- The elision-probe verifier (per [§Production-elision verification](#production-elision-verification)) treats `":rf/redacted"` as a framework sentinel that may survive only where a production boundary explicitly uses schema redaction.

No registration-time privacy warning exists. Schema metadata is the canonical redaction declaration; `redact-interceptor` is the positional interceptor for ad-hoc payload scrubs. The handler-meta `:sensitive?` annotation has been removed.

### Error event catalogue (single source of truth)

Earlier drafts of this Spec carried the error vocabulary across three places: a `### Error categories (initial set)` table that listed `:operation` + meaning + `:tags`, a separate `#### Default behaviour by category` table that listed `:operation` + default `:recovery`, and inline category rows declared within feature subsections.

Consolidated into a single normative [§Error event catalogue](#error-event-catalogue) — one row per category, five columns (`:operation` · `:op-type` · trigger / meaning · default `:recovery` · `:tags`). Each row's emit-site cross-link names the owning Spec section. Per-feature Specs (002, 005, 006, 010, 011, 012, 013, 014, Tool-Pair) reference the catalogue rather than reproducing fragments. The per-category Malli `:tags` schemas remain canonicalised in [Spec-Schemas §Per-category `:tags` schemas](Spec-Schemas.md#per-category-tags-schemas) — one schema per catalogue row. Consumer cost: a single anchor (`#error-event-catalogue`) instead of three; consumers using [API.md §Error contract](API.md#error-contract) get a pointer to the catalogue rather than a partial duplicate (per, G-A closure).

### `:on-error` return-map contract — `:replacement` shape pinned, `:retry-count` struck

Earlier drafts of [§Error-handler policy](#error-handler-policy-on-error-per-frame) said only that the policy fn "returns a map with at least `:recovery` set", with `:replacement` documented loosely as "a value to use when `:recovery` is `:replaced-with-default`". The shape of `:replacement`, its interaction with non-`:replaced-with-default` recoveries, and the behaviour when the policy fn itself throws were left implicit. Separately, a stray `:retry-count` key was carried in some draft notes as if the runtime supported per-handler retry, even though the rest of the Spec was clear that no retry surface exists (only `:rf.http/retry-attempt` ships, and it is owned by the managed-HTTP fx — not by `:on-error` policy).

Resolved per rf2-ciy by pinning the contract normatively:

- The return map is a **closed shape**: `:recovery` (required, drawn from the closed recovery-keyword set), `:replacement` (optional, only honoured under `:replaced-with-default`), `:notes` (optional, free-form). Any other key is ignored; any non-conforming `:recovery` value triggers `:rf.error/bad-on-error-return` and falls back to the category default.
- `:replacement` is **category-specific**: for `:rf.error/handler-exception` it is an effect-map (`{:db ... :fx [...]}`); for `:rf.error/schema-validation-failure` it is a value of the same kind the validator was checking; for categories whose default recovery is `:no-recovery` and which have no natural substitutable slot (registration-time failures, drain-depth-exceeded, every `:rf.epoch/restore-*` rejection), `:replacement` is rejected with `:rf.error/bad-on-error-return :tags :reason "category has no substitutable value"`.
- **No retry surface for `:on-error`.** `:retry-count` is not part of the return contract and never was. The `:retried` recovery keyword exists in the enum but is reserved for `:rf.http/retry-attempt` traces (per 014); an `:on-error` handler that returns `:recovery :retried` is rejected by `:rf.error/bad-on-error-return`. Apps that want a failing event to fire again dispatch a fresh event from inside the policy fn (per [§Composition with libraries](#composition-with-libraries-sentry-honeybadger-etc)).
- **Policy-fn exceptions are caught.** If the policy fn throws while processing an error event, the runtime does NOT recursively invoke the policy on its own exception. Instead, the runtime emits `:rf.error/on-error-policy-exception` (`:recovery :no-recovery`) and falls back to the original error's documented per-category recovery. The cascade halts at the offending point; the policy's exception does not propagate to user code.
- **Two new catalogue rows** capture the contract-violation paths normatively: `:rf.error/bad-on-error-return` (`:recovery :logged-and-skipped`) for malformed return maps, and `:rf.error/on-error-policy-exception` (`:recovery :no-recovery`) for policy-fn throws.

These resolutions extend the [§Recovery contract](#recovery-contract) enum unchanged — the closed set of `:recovery` keywords (`:no-recovery`, `:replaced-with-default`, `:retried`, `:skipped`, `:warned-and-replaced`, `:logged-and-skipped`, `:ignored`) is the same. What rf2-ciy pins is the **return-contract surface** that user policy code interacts with, not the recovery vocabulary itself. The v1 process-wide `reg-event-error-handler` remains dropped per [MIGRATION §M-13 / §M-26](../migration/from-re-frame-v1/README.md#m-13-reg-event-error-handler-is-dropped--error-policy-is-per-frame-on-error); the resolution above describes the v2 `:on-error`-per-frame surface that replaces it.

### Size elision in traces

Trace events and pair-tool snapshot slices carry tree-shaped values (`app-db` snapshots under [§Trace event for app-db changes](#trace-event-for-app-db-changes), epoch-record `:db-before` / `:db-after` slots per [Tool-Pair §Time-travel](Tool-Pair.md), sub-cache reads, `get-path` returns) that can individually blow the 5K-token wire cap (`tools/re-frame2-pair-mcp/spec/Principles.md` §Wire-cap). A 5 MB base64-encoded PDF preview under `[:user :uploaded-pdf]` is 290× the cap on its own — and a `:path [:user]` drill-down returns it verbatim, bypassing the [`:rf.mcp/summary`](Tool-Pair.md) lazy-summary mechanism (which shapes the top-level response, not per-value descendants).

The contract is structurally parallel to [§Privacy / sensitive data in traces](#privacy--sensitive-data-in-traces): a per-path declarative flag (`:large?`) that the wire-boundary walker routes on, and a single normative wire marker (`:rf.size/large-elided`) the walker substitutes in place of the elided value. Apps that nominate a large path get every wire emit eliding it; consumers re-fetch on demand via the marker's `:handle` slot through the existing re-frame2-pair-mcp `get-path` tool — no new tool is needed.

Privacy and size are **two orthogonal predicates over the same elision walker**: `rf/elide-wire-value` (per [API.md §`rf/elide-wire-value`](API.md#elide-wire-value-the-wire-boundary-walker)) consumes both `:sensitive?` and `:large?` and emits the appropriate placeholder. Same shape, two flags, one helper — when both predicates match the **sensitive drop wins**, because emitting the size marker would leak `:path` / `:bytes` / `:digest` (each of which can carry structural information about the redacted slot).

DESIGN-RATIONALE — **why the two markers stay separate rather than unifying behind a single elision shape.** A unified marker is structurally tempting (one walker, one wire shape, one consumer code-path), but three properties of the privacy axis make a single shape strictly worse than the two-flag arrangement above. **(1) Path-leak risk is structurally worse with a unified marker.** Hoisting `:path` to its own field on a privacy-driven elision advertises "this slot is worth redacting" — a stronger breadcrumb than today's per-key `:rf/redacted` sentinel, which lives inside the value's parent and reveals only that *some* child got redacted, not which one. The sensitive cascade arm (`::redact-or-drop` above) deliberately keeps its evidence local to the parent map; the size arm (`::elide-with-marker`) deliberately exposes the path so consumers can re-fetch. The two shapes encode opposite policies about what's safe to advertise. **(2) The fetch-handle is redundant for sensitive.** When `:include-sensitive? true` the value rides inline (no marker, no handle needed); when false the event vanishes from the wire entirely (nothing to fetch from). There is no useful in-between state where a sensitive value should be both elided AND re-fetchable — that combination *is* the leak. The handle is asymmetrically valuable: size genuinely needs it (a 5 MB value can't ride the wire even when wanted), sensitive has no analogous size pressure (a redacted string fits inline). **(3) Defense-in-depth would collapse.** Today's two-marker arrangement is two independent boundaries: the trace stream is read-only metadata (one boundary; sensitive values never reach it), and `get-path` auth-gates retrieval (second boundary; a handle alone isn't authority). A handle-bearing sensitive marker would collapse these into one policy decision — sensitive enforcement would rely on `get-path` alone, losing the read-only-metadata boundary as a check. The composition rule (sensitive wins) is the **load-bearing wire-boundary invariant** that the separate-markers design protects; it's normative here and re-stated where the marker shape is reserved at [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned) (`:rf.size/large-elided`).

#### Nomination — schema metadata only

Implementations MUST support schema-driven nomination. The schema walker populates one in-app-db registry (`[:rf/elision :declarations]`); the wire walker consults that registry at every emit. (The shape lives in [Spec-Schemas §`:rf/elision-registry`](Spec-Schemas.md#rfelision-registry); the app-db slot is reserved per [Conventions §Reserved app-db keys](Conventions.md#reserved-app-db-keys).)

`{:large? true}` on a Malli slot in `:rf/app-schema` (per [Spec-Schemas §`:rf/app-schema-meta`](Spec-Schemas.md#rfapp-schema-meta)) is the canonical AI-discoverable entry: schemas are the AI-first surface for app shape, so an agent reading the schema sees the elision claim alongside the type. The runtime walks every registered app-schema at boot and on hot-reload and writes `{:large? true :source :schema}` entries into the registry under the path the schema slot occupies.

The walker does not auto-elide unschema'd values. In dev, when it observes a large string at an undeclared path, it emits `:rf.warning/large-value-unschema'd` once per `(frame, path)` to nudge authors toward schema metadata.

#### Wire marker — `:rf.size/large-elided`

The walker substitutes large values with a single normative marker shape:

```clojure
{:rf.size/large-elided
  {:path   [:user :uploaded-pdf]               ;; absolute path inside the slice's root
   :bytes  5242880                             ;; pr-str byte count, exact when known
   :type   :string                             ;; one of :map :vector :set :scalar :string
   :digest "sha256:abc123..."                  ;; hex digest, optional (gated on :include-digests?)
   :reason :schema
   :hint   "Upload preview blob"               ;; copied verbatim from the declaration's :hint slot
   :handle [:rf.elision/at [:user :uploaded-pdf]]}}  ;; EDN form passable to get-path
```

The shape is captured normatively at [Spec-Schemas §`:rf/elision-marker`](Spec-Schemas.md#rfelision-marker). Per-field MUST-level requirements:

- **`:path`** — REQUIRED. The **absolute** path inside the snapshot slice (NOT relative to the elision site). An agent that asked for `:path [:user]` and got a marker back at the `:uploaded-pdf` slot sees `:path [:user :uploaded-pdf]`. The handle is copy-pasteable without rebasing.
- **`:bytes`** — REQUIRED. The `pr-str` byte count of the elided value. Lets an agent decide "fetch anyway" (small enough for this turn) vs "skip" (over the per-turn budget).
- **`:type`** — REQUIRED. One of `:map`, `:vector`, `:set`, `:scalar`, `:string`. Tells the agent which access pattern to use — a `:vector` is paginatable via `get-path` with an index range; a `:string` of 5MB is not.
- **`:reason`** — REQUIRED. Always `:schema`; schema metadata is the canonical size-elision declaration source.
- **`:hint`** — REQUIRED (may be `nil`). A free-form short string copied verbatim from the Malli slot's `{:hint "..."}` metadata.
- **`:handle`** — REQUIRED. An EDN vector of shape `[:rf.elision/at <path>]` (or `[:rf.elision/at <path> :as-of-epoch <epoch-id>]` when the marker rides inside a past-epoch payload — see §Composition below). The handle is **a normal EDN vector**, not a tagged literal — agents pattern-match on the leading `:rf.elision/at` keyword without needing a reader hook. The path inside the handle is the same as the marker's `:path` field. Passing the handle to the existing re-frame2-pair-mcp `get-path` tool fetches the literal elided value, subject to that tool's own cap check (a `:rf.mcp/overflow` is the failure mode if the literal is over-cap).
- **`:digest`** — OPTIONAL. A `sha256:<hex>` content digest, computed only when `:rf.size/include-digests?` is true on the call. Default off because the digest forces a full walk of the elided value, which negates the elision's cost-saving. When enabled (debug builds, integrity-check workflows), callers compare digests across turns to detect change-without-fetch.

The marker is the **sixth wire elision mechanism** alongside the five precedents catalogued in [Tool-Pair.md](Tool-Pair.md) (`:rf.mcp/summary`, `:rf.mcp/overflow`, `:rf.mcp/diff-from`, `:rf.mcp/dedup-table`, `:rf.mcp/cache-hit`). The five pre-existing mechanisms shape the **top-level** response; `:rf.size/large-elided` substitutes **per-value** inside any tree-typed payload (`:app-db`, `:sub-cache`, every `:rf/epoch-record` `:db-before` / `:db-after` slot, every `get-path` return).

#### Consumer suppression — the elision policy

The walker accepts a per-call **elision policy** map. The vocabulary lives under the reserved `:rf.size/*` namespace (per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)) and rides into every tool that emits wire data:

```clojure
{:rf.size/elision-policy
  {:rf.size/include-large?    false   ;; default false — large values elide to markers
   :rf.size/include-digests?  false}} ;; default false — :digest slot is omitted from markers
```

Consumer-side defaults (MUST-level):

- **Framework-published off-box listener integrations** (the Sentry / Honeybadger forwarders per [§Wiring an external error monitor](#wiring-an-external-error-monitor-sentry-rollbar-honeybadger-etc), the re-frame2-pair-mcp / Xray-MCP / story-mcp servers per [Tool-Pair.md](Tool-Pair.md)) MUST default `:rf.size/include-large?` to `false` and `:rf.size/include-digests?` to `false`. Tools that ship large-payload-aware integrations (e.g. dedicated artefact-streaming) opt in per-call; the conservative default protects apps that opt into a published integration without reading its source.
- **On-box listener integrations** (Xray panel, Story panels per [Tool-Pair.md](Tool-Pair.md)) MUST default `:rf.size/include-large?` to `false` (the dev-tools UI shows a `[● ELIDED N]`-style indicator the user clicks to opt in for a single fetch). Production-trust on-box consumers MAY default to `true`; the rationale must be documented per-consumer.
- **Indicator field on tool responses.** Tools that return structured response maps (every MCP server per [Tool-Pair.md](Tool-Pair.md)) MUST carry an `:elided-large` count alongside the existing `:dropped-sensitive` count (per [§Privacy / sensitive data in traces](#privacy--sensitive-data-in-traces)) — one MUST-level row per consumer-facing tool that walks a tree-typed payload.

  The `:elided-large` slot reports the count of `:rf.size/large-elided` markers ENCOUNTERED in the tool's response payload. Tools do not invoke `elide-wire-value` themselves — markers ride through from upstream (the event-emit substrate, the error-emit substrate, schema-slot meta). The slot is omitted when the count is zero (per [Conventions.md](Conventions.md) — `:elided-large` row).

The walker MUST NOT widen the policy transitively into the underlying registry — the policy is per-call; the registry of declared paths is per-frame state.

#### Composition

With the five other wire mechanisms catalogued in [Tool-Pair.md](Tool-Pair.md), composition is the wire-boundary contract:

- **× `:sensitive?` (privacy).** Sensitive drops **before** size elides. A value matching both predicates produces a `:sensitive? true` trace event with the value already redacted; no `:rf.size/large-elided` marker is emitted (the marker itself would leak `:path` / `:bytes` / `:digest`). The walker's predicate cascade is:

  ```clojure
  (cond
    (and sensitive? large?)  ::drop                  ; no marker; emit :sensitive? true
    sensitive?               ::redact-or-drop        ; today's :rf/redacted sentinel
    large?                   ::elide-with-marker     ; :rf.size/large-elided
    :else                    ::pass-through)
  ```

- **× `:rf.mcp/diff-from` (epoch diff-encoding).** When a diff patch points at a large value, the walker substitutes the marker inside the patch's `:assoc` slot. The patch itself stays small (path + marker). The `:handle` carries `:as-of-epoch <epoch-id>` when the marker rides a past-epoch payload — `get-path` resolves against the existing epoch-record's `:db-after` snapshot so the agent sees that-epoch's value, not now's.
- **× `:rf.mcp/dedup-table`.** Marker shapes are small (~150 bytes) — a 5 MB blob referenced from N epoch records produces N markers (~150N bytes) rather than one dedup-table entry plus N references. The marker IS the dedup for large values; no extra dedup work needed. If the agent opts in (`:rf.size/include-large? true`), the underlying values ride the wire and the dedup table picks them up at the slice boundary — the two mechanisms compose cleanly because they operate at different pipeline points.
- **× `:rf.mcp/summary` (lazy summary).** Independent: summary shapes the top level of the response; large-elision substitutes per-value descendants. A `:path [:user]` drill-down may return a `:rf.mcp/summary` at the top (the slice shape) AND embed `:rf.size/large-elided` markers at any large descendant.
- **× `:rf.mcp/overflow` (cap backstop).** Elision runs **before** the cap check. After elision the slice is much smaller; the cap usually doesn't fire. When it does — the marker volume plus residual small values still exceeds 5K tokens — the cap fires with its overflow marker and the agent narrows further.

#### Production-elision behaviour

The size-elision mechanism is **dev-time only at the wire boundary**, but the registry itself ships in production:

- The `[:rf/elision :declarations]` slot survives production builds — it lives in app-db, and schema-derived declarations ship as data. Production tools that consume `app-db` (diagnostic dumps, off-box snapshot exports) MAY consult the registry to decide elision policy.
- The `rf/elide-wire-value` walker itself ships in production. Consumer-facing surfaces that call it (every tool consuming the Spec 009 instrumentation API per [Tool-Pair.md](Tool-Pair.md)) elide with the trace surface (per [§Production builds: zero overhead, zero code](#production-builds-zero-overhead-zero-code)). Production builds that wire the walker into non-tool surfaces (off-box error-monitor forwarders, Sentry-style serialisers) get the same elision contract.
- The `:rf.warning/large-value-unschema'd` warning is dev-only — it rides the trace surface and elides with it.
- The elision-probe verifier (per [§Production-elision verification](#production-elision-verification)) gains one sentinel: the string fragment `":rf.size/large-elided"` (the marker keyword) MUST survive in production bundles only when the app explicitly wires the walker into a production surface — production builds that consume the walker only from dev-only tooling have the literal DCE'd along with the trace surface.

The single shared walker is the **only** place these markers get emitted; per-tool reimplementation is prohibited. Tools consume the walker through the public `rf/elide-wire-value` surface (per [API.md](API.md#elide-wire-value-the-wire-boundary-walker)); the walker is the natural home for short-circuits (once a sub-tree is elided, don't descend into it further — a large subtree elides its children with it; recursing into a 5 MB JSON blob to find more 5 MB blobs is pure cost).

A dedicated warning category accompanies the contract: `:rf.warning/large-value-unschema'd`, catalogued in [§Error event catalogue](#error-event-catalogue), so authors notice large values that still need schema metadata.
