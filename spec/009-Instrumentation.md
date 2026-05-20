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
                              ;;   the emit site (e.g. :event/dispatched, :rf.machine/transition,
                              ;;   :rf.error/no-such-sub). The event-id / sub-id / fx-id
                              ;;   that motivates the emit rides under :tags. Per
                              ;;   Spec-Schemas §:rf/trace-event.
 :op-type   <kw>             ;; discriminator: :event, :sub/run, :sub/create, :event/do-fx,
                              ;;   :view/render, :rf.machine/transition, :error, :warning, etc.
                              ;;   The full vocabulary is enumerated in §:op-type vocabulary
                              ;;   below and in Spec-Schemas §:rf/trace-event.
 :time      <ms>             ;; emit timestamp (host clock)
 :tags      {...}}           ;; open-ended bag for op-type-specific fields
```

The runtime emits each trace event at the *moment of interest* with the host clock time captured in `:time`. The shape is **event-at-a-time**, not span-shaped: there is no separate start/end pair, no `:duration`, and no `:child-of` parent-id. Tools that need cascade correlation use the dispatch-id correlation fields documented under [§Dispatch correlation](#dispatch-correlation-dispatch-id--parent-dispatch-id) instead.

**`:op-type` versus `:operation`.** `:op-type` is the discriminator a consumer branches on — a small, stable vocabulary of ~20 values (enumerated in [§`:op-type` vocabulary](#op-type-vocabulary) below and in [Spec-Schemas §`:rf/trace-event`](Spec-Schemas.md#rftrace-event)). Tools route on `:op-type` to subscribe to a slice (e.g. `:event`, `:sub/run`, `:error`). `:operation` is the specific identity of the emit site within that slice — typically a namespaced keyword like `:event/dispatched`, `:rf.machine/transition`, or `:rf.error/no-such-sub`. A consumer subscribing to all errors filters `:op-type :error`; a consumer hunting one category branches further on `:operation`.

### Re-frame2 additions (additive, optional)

```clojure
{:source   :ui              ;; :ui, :timer, :http, :machine, :repl — origin of the trigger
 :recovery :no-recovery}    ;; recovery disposition for error-shaped events
```

`:source` is hoisted to the top level of every event whose tags carry it; `:recovery` is hoisted on the error path. Both are top-level, not under `:tags`. The `:frame` field — present on most events — rides under `:tags` (every emit site that knows the frame includes it there). Tools that filter by frame read `(get-in ev [:tags :frame])`.

### Dispatch correlation: `:dispatch-id` / `:parent-dispatch-id`

Pair-shaped tools and per-event diagnostics need to correlate the *cascade* a dispatch belongs to — "this trace event fired inside the cascade started by *that* dispatch." The runtime maintains two distinct correlation channels:

```clojure
{:tags {:dispatch-id        <uuid-or-counter>   ;; the cascade this event belongs to
        :parent-dispatch-id <uuid-or-counter>}  ;; on :event/dispatched only — the cascade
                                                ;; that caused THIS dispatch
 ...}
```

Semantics:

- **`:dispatch-id` is cascade-wide.** It is allocated by the runtime when a dispatch is enqueued (before routing) and rides on **every** trace event emitted *inside* that dispatch's run-to-completion drain — `:event/dispatched` itself, `:event/db-changed`, `:rf.fx/handled`, `:sub/run`, `:rf.machine/transition`, `:rf.flow/*`, every `:rf.error/*`, and any future op-type the runtime adds. Consumers (Story `group-cascades`, Causa's causality graph, re-frame2-pair's `cascade-of`, schema-timeline correlation) group raw trace events by `:dispatch-id` directly — no inference from sequence required. The runtime carries the in-flight cascade's id through the `:dispatch-id` slot of the handler-scope record (`re-frame.trace/*handler-scope*`, per [§Handler-scope](#handler-scope-the-in-scope-reading-at-emit-time)), bound by `router.cljc` around each event's processing; `emit!` reads the slot and merges it into the event's `:tags` when bound and not already present. Implementations may use a process-monotonic counter, a UUID, or any opaque value with the same uniqueness contract: distinct within a single process for the lifetime of the trace surface. Tools treat it as opaque. Trace events emitted **outside** any in-flight cascade (frame creation at boot, handler registration before any dispatch, REPL evals that don't dispatch) carry no `:dispatch-id`.
- **`:parent-dispatch-id` is scoped to `:event/dispatched` only.** It documents *cascade-from-cascade lineage* — "this dispatch was emitted as a side-effect of another event's processing" — which is a per-event-dispatch fact, not a per-trace-event fact. Concretely: when an `fx` handler running inside the do-fx phase of dispatch *D₁* invokes `(rf/dispatch ...)`, the runtime records the new dispatch's `:parent-dispatch-id` as *D₁*'s `:dispatch-id` on the new dispatch's `:event/dispatched` event. If the dispatch was initiated outside any in-flight event (a timer, a UI handler, the REPL, the SSR boot path), `:parent-dispatch-id` is absent from `:event/dispatched`. Non-`:event/dispatched` trace events never carry `:parent-dispatch-id` — they belong to a single cascade (their `:dispatch-id`) and the inter-cascade lineage hangs off the cascade root.
- **Top-level dispatch.** An `:event/dispatched` event with no `:parent-dispatch-id` is a *root* of a cascade. Pair-shaped tools draw cascade trees by walking `:parent-dispatch-id` upward across `:event/dispatched` events; the per-cascade body (every other trace event in that cascade) is the slice of the trace stream sharing the cascade's `:dispatch-id`.
- **The cascade-correlation primitive.** Because the runtime emits event-at-a-time (no `:child-of` span field), `:dispatch-id` is the only intra-cascade correlation channel and `:parent-dispatch-id` is the only inter-cascade correlation channel. The pair lets tools both (a) group raw spans by cascade and (b) walk lineage between cascades, without consulting the `:rf/epoch-record` projection. Tools that prefer structured per-cascade slices read the assembled `:rf/epoch-record` (per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel)) — the raw `:dispatch-id` channel is the lower-level primitive.
- **Production elision.** Both fields ride the trace stream and are elided in production with the rest of the trace surface. The dispatch-id allocation counter and the `*handler-scope*` Var read sit inside the `interop/debug-enabled?` gate in `emit!`, so the whole machinery compiles out.

Tools consume these two channels to build cascade views: "show me every fx that ran in this cascade" is a filter on `:dispatch-id` over the raw stream; "show me all dispatches descended from `[:user/login ...]`" is a transitive walk over `:parent-dispatch-id` across `:event/dispatched` events.

### Origin tagging: `:origin`

When a tool (the pair tool, a story runner, the REPL, the SSR boot path) needs its own dispatches distinguishable from application dispatches, it can tag them with an `:origin` opt at dispatch time (per [002 §Dispatch origin tagging](002-Frames.md#dispatch-origin-tagging)). The runtime lifts the value onto every `:event/dispatched` trace event under `:tags :origin`:

```clojure
{:tags {:origin :pair        ;; tag set by the dispatching tool; default :app
        :dispatch-id ...
        ...}
 ...}
```

`:origin` is unconstrained at the framework level — tools and applications agree on values (`:pair`, `:claude`, `:story`, `:test`, etc.). The default is `:app`. User application code typically omits the opt; tool surfaces set it so post-mortem filters like "show me only the dispatches I (the pair tool) issued during this session" become a one-key filter on the trace stream.

`:origin` is **distinct from `:source`**: `:source` describes the *trigger kind* (`:ui` / `:timer` / `:http` / `:machine` / `:repl` / `:ssr-hydration`) and is essentially a "what woke the runtime?" axis; `:origin` describes the *actor identity* (which tool or app subsystem emitted the dispatch) and is used for filtering. Tools may set both.

### `:op-type` vocabulary

Core values: `:event`, `:sub/run`, `:sub/create`, `:event/do-fx`, `:fx`, `:view/render`, `:registry`, `:machine`, `:warning`, `:error`, `:info`.

Additional values for re-frame2 concerns:

- `:frame/created` / `:frame/re-registered` / `:frame/destroyed` — frame lifecycle.
- `:rf.machine.lifecycle/created` / `:rf.machine.lifecycle/destroyed` — machine instance lifecycle.
- `:rf.machine/event-received` / `:rf.machine/transition` / `:rf.machine/snapshot-updated` / `:rf.machine/done` — machine activity. (`-done` per rf2-gn80 — fires when the machine enters a `:final?` state, immediately before the auto-destroy synchronously tears the actor down.)
- `:rf.machine.microstep/transition` — per-microstep transition emitted alongside the outer `:rf.machine/transition` for `:always`-driven cascades; one event per microstep with `:tags {:machine-id <id> :from <state> :to <state> :microstep-index <n>}` (per [005 §Trace events](005-StateMachines.md#trace-events) and [Spec-Schemas §`:rf/trace-event`](Spec-Schemas.md#rftrace-event)).
- `:rf.machine/spawned` / `:rf.machine/destroyed` — machine instance spawn/destroy events emitted by `fx.cljc` on the spawn / destroy fx-id paths. Distinct from `:rf.machine.lifecycle/created` / `-destroyed` (which are emitted by `frame.cljc` on the underlying registrar lifecycle); the `:rf.machine/*` pair is the fx-substrate observation, the `:rf.machine.lifecycle/*` pair is the registrar-substrate observation. Tools that just want "did a machine appear/disappear?" can subscribe to either; tools building causal graphs subscribe to both and disambiguate by the `:tags :emitted-from` axis. Per rf2-t07u (Option A revised), payload `:tags` carry `:frame`, `:machine-id` (the spec-time machine-id), `:spawned-id` (the gensym'd actor address), `:system-id` (when set), `:parent-id` (the parent machine's registration-id, when the spawn came from declarative `:invoke`), and `:invoke-id` (the absolute prefix-path of the `:invoke`-bearing state node, when applicable) — together `:parent-id` + `:invoke-id` address the runtime spawn registry slot at `[:rf/spawned <parent-id> <invoke-id>]`, so tools can map the registry without re-deriving from app-db. Per rf2-gn80, the `:rf.machine/destroyed` event is **enriched** with a `:reason` tag — one of `:rf.machine/finished` (the actor entered a `:final?` state and auto-destroyed; see `:rf.machine/done` below), `:explicit` (a parent state-exit cascade, an `:invoke-all` cancel-on-decision, an imperative `[:rf.machine/destroy <id>]`, or any other runtime-initiated teardown), or `:parent-unmount-cascade` (the surrounding parent actor or frame was destroyed and the runtime walked surviving children). Existing observers that filter on `:tags` see the new key additively — no breaking change.
- `:rf.machine/done` — machine entered a `:final?` state; the runtime has invoked the parent's `:invoke :on-done` (if any) and is about to auto-destroy synchronously. **One event per finish.** `:tags {:machine-id <finishing-actor-id> :output <value-or-nil> :parent-id <parent-registration-id-or-nil>}`. `:output` is the child's `:data` slot named by the final state's `:output-key` (or `nil` when the final state has no `:output-key`). `:parent-id` is `nil` for **singleton** machines that reached `:final?` (per the singleton-symmetry rule D7 — see [005 §Final states](005-StateMachines.md#final-states-final--on-done--output-key)). Pairs with the immediately-following `:rf.machine/destroyed` event whose `:tags :reason` is `:rf.machine/finished`. Per rf2-gn80.
- `:rf.machine/system-id-bound` / `:rf.machine/system-id-released` — `:system-id` reverse-index lifecycle (per [005 §Named addressing via `:system-id`](005-StateMachines.md#named-addressing-via-system-id)). `-bound` fires on every `:system-id`-bound spawn (including the rebound case that also emits the `:rf.error/system-id-collision` warning); `-released` fires on the matching destroy. `:tags {:frame <id> :system-id <name> :machine-id <gensym'd-id>}`.
- `:rf.machine.timer/scheduled` / `:rf.machine.timer/fired` / `:rf.machine.timer/stale-after` / `:rf.machine.timer/cancelled-on-resolution` / `:rf.machine.timer/skipped-on-server` — state-machine `:after` timer lifecycle (per [005 §Trace events](005-StateMachines.md#trace-events) and rf2-3y3y). `:scheduled` fires on initial entry-time scheduling and on every subscription-driven re-resolution; its `:tags` carry `:delay-source <:literal | :sub | :fn>` to discriminate the three delay forms (per [005 §Value shape](005-StateMachines.md#value-shape) and [005 §Dynamic delay re-resolution](005-StateMachines.md#dynamic-delay-re-resolution)). `:fired` carries `:fired? <bool>` (false ⇒ guard suppressed the transition; sibling timers continue). `:cancelled-on-resolution` fires on subscription-driven re-resolution paired with a fresh `:scheduled`. The `*/stale-*` form is the canonical naming for [§stale-detection trace events](Pattern-StaleDetection.md) — see also `:rf.route.nav-token/stale-suppressed` below. `:skipped-on-server` fires under SSR per [005 §SSR mode](005-StateMachines.md#ssr-mode).
- `:rf.machine.invoke-all/started` / `:rf.machine.invoke-all/all-completed` / `:rf.machine.invoke-all/some-completed` / `:rf.machine.invoke-all/any-failed` — state-machine `:invoke-all` spawn-and-join lifecycle (per [005 §Spawn-and-join via `:invoke-all`](005-StateMachines.md#spawn-and-join-via-invoke-all) and rf2-6vmw). `*/started` fires after all N children have been spawned on entry to the `:invoke-all`-bearing state. `*/all-completed` fires when `:join :all` resolves; `*/some-completed` fires when `:join :any` / `{:n N}` / `{:fn ...}` resolves on the success-side; `*/any-failed` fires when `:on-any-failed` resolves. `:tags {:machine-id <id> :invoke-id <prefix-path> :child-ids ... :done ... :failed ...}` (the specific subset of tags depends on which event fires; common to all is `:machine-id` + `:invoke-id`).
- `:rf.machine.invoke/cancelled-on-join-resolution` — fires once per sibling cancelled by `:cancel-on-decision? true` (the default) when an `:invoke-all` join condition resolves and surviving siblings are torn down (per [005 §Cancel-on-decision](005-StateMachines.md#cancel-on-decision-default-true) and rf2-6vmw). `:tags {:machine-id <parent-id> :invoke-id <prefix-path> :child-id <user-id> :spawned-id <gensym'd-id> :join-event <:on-all-complete | :on-some-complete | :on-any-failed | :on-timeout>}`. The trace fires per cancelled actor; observers needing one event per join resolution use `:rf.machine.invoke-all/*-completed` / `*/any-failed` / `:rf.machine.invoke/timed-out` instead.
- ~~`:rf.machine.invoke/timed-out`~~ — RETIRED per rf2-3y3y. The pre-rf2-3y3y `:timeout-ms` slot on `:invoke` / `:invoke-all` is dropped in favour of state-level `:after`; the trace event with it. Observers wanting "this `:invoke`-bearing state's wall-clock guard fired" now consume `:rf.machine.timer/fired` on the `:invoke`-bearing state's `:after` entry — same semantic, uniform substrate. Per [005 §Wall-clock timeouts on `:invoke` — use parent state's `:after`](005-StateMachines.md#wall-clock-timeouts-on-invoke--use-parent-states-after) and [MIGRATION §M-44](../migration/from-re-frame-v1/README.md#m-44-timeout-ms-removed-from-invoke--invoke-all--use-parent-states-after).
- `:rf.route.nav-token/allocated` / `:rf.route.nav-token/stale-suppressed` — navigation-token lifecycle (per [012 §Navigation tokens](012-Routing.md#navigation-tokens--stale-result-suppression)). `*-allocated` fires when a navigation cascade begins; `*-stale-suppressed` fires when an async result arrives carrying a now-superseded token. Same epoch idiom as the machine-`:after` timer events.
- `:rf.route/fragment-changed` / `:rf.route/navigation-blocked` — fragment-only URL change emission (per [012 §Fragments](012-Routing.md#fragments); rf2-cj9fn renamed from `:rf.route/url-changed` to disambiguate from the runtime event `:rf/url-changed` which fires on every URL transition) and pending-nav protocol blockage (per [012 §Navigation blocking](012-Routing.md#navigation-blocking--pending-nav-protocol)). `:rf.route/fragment-changed` fires only on the fragment-only branch (the route-id / params / query did not change); the trace's `:tags` carry `:prev-fragment` and `:next-fragment` and never coincide with a `:rf.route.nav-token/allocated` event for the same drain — see the [`routing/fragment-change` conformance fixture](conformance/fixtures/route-fragment-change.edn).
- `:rf.route/registered` / `:rf.route/cleared` / `:rf.route/activated` / `:rf.route/deactivated` — route lifecycle (per [012 §Trace events](012-Routing.md#trace-events) and rf2-dn26r). `:rf.route/registered` fires on first-time `reg-route`; re-registration rides `:rf.registry/handler-replaced`. `:rf.route/cleared` fires on explicit `unregister-route!`. `:rf.route/activated` / `:rf.route/deactivated` fire on every cross-route navigation commit in that order; same-id navigation emits neither. Mirrors the flow-lifecycle symmetry (`:rf.flow/registered` / `:rf.flow/cleared` / `:rf.flow/computed`).
- `:rf.registry/handler-registered` / `:rf.registry/handler-cleared` / `:rf.registry/handler-replaced` — registration changes (hot reload). The canonical trio: `-registered` for a fresh id, `-cleared` for an explicit removal, `-replaced` when re-registration overwrote an existing id (the typical hot-reload case).
- `:flow` — flow lifecycle and evaluation events (per [013 §Flow tracing](013-Flows.md#flow-tracing)). The op-type for the whole flow trace stream; per-flow events live under `:rf.flow/*` operations (`:rf.flow/registered`, `:rf.flow/computed`, `:rf.flow/skip`, `:rf.flow/cleared`, `:rf.flow/failed` — see [§Flow trace events](#flow-trace-events) below). Tools filter `op-type :flow` to subscribe to the whole flow stream.
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

    **Two-channel teardown — what each channel sees (rf2-5h879).** The runtime emits machine-destroy traces on **two parallel channels** with deliberately distinct purposes; the channel name carries the source-of-emit, the `:reason` slot carries the cause. Per audit rf2-a2xhr Finding 5:

    | Channel | Source-of-emit | What it observes | Typical consumer |
    |---|---|---|---|
    | `:rf.machine.lifecycle/destroyed` | `frame.cljc` (and re-emitted on the unified lifecycle channel for `:reason :parent-frame-destroyed`) | The **registrar-substrate** observation: the actor handler / snapshot disappeared from the registrar. One event per destroyed instance, including frame-exit reaping. | "Did a machine appear/disappear?" — observers building a live list of running actors. |
    | `:rf.machine/destroyed` | `lifecycle_fx/finalize.cljc` + `lifecycle_fx/destroy.cljc` | The **fx-substrate** observation: a destroy fx ran on the spawn / destroy fx-id path. One event per fx-driven teardown. Does NOT fire for frame-exit reaping (that is registrar-substrate only). | Causal-graph builders ("which fx caused this teardown?") — observers correlating fx emission against actor lifecycle. |

    Tools that "just want did a machine appear/disappear?" pick **either** channel and rely on the `:reason` slot for cause. Tools building causal graphs (Pair, Causa, Story) subscribe to **both** and disambiguate via `:tags :emitted-from`. The naming axis (`:rf.machine.lifecycle/*` vs `:rf.machine/*`) carries the source-of-emit distinction; the `:reason` slot carries the cause. A rename to `:rf.machine.fx/destroyed` / `:rf.machine.registrar/destroyed` was considered and rejected: the existing names align with how the rest of the spec namespaces the two substrates (`:rf.machine.lifecycle/*` is the registrar-lifecycle family, `:rf.machine/*` is the fx-substrate family), and the cost of churning every tool / fixture / docstring outweighs the marginal naming-axis clarity.
- `:rf.frame/drain-interrupted` — lifecycle event emitted by `router.cljc` when the drain loop detects `(:destroyed? (:lifecycle frame))` mid-cycle and drops remaining queued events. `:op-type :frame` (the frame-lifecycle family — see `:frame/created` / `:frame/destroyed` siblings; **not** `:op-type :event`, which is reserved for "an event was dispatched"). `:tags {:frame <id> :dropped-count <int>}`. Per [002 §Edge cases worth pinning](002-Frames.md#edge-cases-worth-pinning).
- `:rf.epoch/snapshotted` / `:rf.epoch/restored` / `:rf.epoch/db-replaced` — epoch-history operations under `:op-type :rf.epoch`. `-snapshotted` fires after each drain-settle when the runtime has appended a fresh `:rf/epoch-record`; `-restored` fires after a successful `restore-epoch`; `-db-replaced` fires after a successful `reset-frame-db!` (the rf2-zq55 pair-tool write surface — see [Tool-Pair §Pair-tool writes](Tool-Pair.md#pair-tool-writes--state-injection)). Per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel). `:tags {:frame <id> :epoch-id <id> :event-id <id>?}`.
- `:rf.epoch.cb/silenced-on-frame-destroy` — listener-silencing notification emitted **once per `(frame, cb-id)` pair** when a frame previously observed by a `register-epoch-listener!` callback is destroyed (per [Tool-Pair §Surface behaviour against destroyed frames](Tool-Pair.md#surface-behaviour-against-destroyed-frames) and rf2-d656). `:op-type :rf.epoch.cb`. `:tags {:frame <id> :cb-id <id>}`. The callback registration remains in place; the trace exists so a tool whose previously-firing cb has gone silent learns *why* without polling registry state. Repeat destroys of the same frame do not re-emit; a re-registration of a same-keyed frame followed by a fresh delivery re-arms the cb's observation set so a subsequent destroy re-emits.

Consumers filter by `:op-type` (or `:source`, or `(get-in ev [:tags :frame])`) to get the slice they care about. Adding new `:op-type` values is non-breaking — tools ignore what they don't understand.

### `:tags` is the open-ended bag

Variable per-event data goes in `:tags`. Existing examples: `:event-id`, `:event`, `:frame`, `:phase`, `:dispatch-id`, `:parent-dispatch-id`, `:origin`, `:app-db-before`, `:app-db-after`, `:sub-id`, `:query-v`, `:input-signals`, `:fx-id`, `:fx-args`. New tags can be added without breaking consumers. Use `:tags` for op-type-specific data; reserve top-level keys for fields universal across all events.

**Canonical per-frame routing key (rf2-shaa1).** Every trace event that names a frame uses `:frame` under `:tags`. The framework MUST NOT emit `:frame-id` as a tag key — `:frame` is the single canonical name; ports that re-emit must follow suit. Consumers read `(get-in ev [:tags :frame])`. (Historical drift in v2 development used both; the alias has been retired.)

### Open shape; new fields are additive

The map is open. New fields can be added by future versions without breaking consumers — listeners read what they understand and ignore the rest. The forward-compat commitments:

- **Required top-level fields** (`:id`, `:operation`, `:op-type`, `:time`, `:tags`) are stable. Removing or renaming any is a breaking change.
- **Re-frame2 additions hoisted to top level** (`:source`, `:recovery`) are stable once shipped; they are present on every event whose tags carry them.
- **Op-type-specific fields inside `:tags`** are stable within their op-type — including `:frame`, which every emit site supplies under `:tags`. New optional tag keys are additive; existing keys don't change shape.
- **New `:op-type` values** can be added without breaking existing tools — tools filter the values they recognise.

### Flow trace events

Five trace events constitute the flow lifecycle stream (per [013 §Flow tracing](013-Flows.md#flow-tracing)). All five carry `:op-type :flow`; consumers filter by `:op-type` to subscribe to the whole stream and branch on `:operation` to discriminate. Every event's `:tags` carries `:flow-id` and `:frame` so tools can attribute and route per-frame.

| `:operation` | When it fires | `:tags` payload (in addition to `:flow-id` and `:frame`) |
|---|---|---|
| `:rf.flow/registered` | After `reg-flow` (or `:rf.fx/reg-flow`) successfully registers a flow against a frame, including post-cycle-detection. | `:inputs` (the flow's input paths), `:path` (the flow's output path) |
| `:rf.flow/computed` | A flow's `:output` fn ran and the result was assoc-in'd at `:path`. Fires only when the dirty-check observed an input value-difference. | `:input-values` (raw values read from the input paths), `:result` (the new output value), `:path` |
| `:rf.flow/skip` | The dirty-check found inputs `=`-equal to the previous run; the recompute was suppressed (per [013 §Dirty-check semantics](013-Flows.md#dirty-check-semantics) and rf2-719e value-equal recompute suppression). | `:reason` (currently `:inputs-value-equal`; the keyword is open for future skip reasons) |
| `:rf.flow/cleared` | After `clear-flow` (or `:rf.fx/clear-flow`) removes the flow from the per-frame registry and dissoc-in's its output path. | `:path` (the path that was vacated) |
| `:rf.flow/failed` | The flow's `:output` fn threw during recompute. The exception is re-thrown after this trace fires so the router's outer catch emits the cascade-level `:rf.error/flow-eval-exception` (per [§Error contract](#error-contract)); tools see the per-flow detail here and the cascade halt there. Per [013 §Failure semantics](013-Flows.md#failure-semantics), prior successful flows' writes in the same drain are flushed to `app-db` before the throw propagates; the failing flow's own `:path` is not written and its `last-inputs` is not advanced; downstream flows do not run on this drain. | `:ex` (the exception), `:inputs` (the input values that were read just before the throw) |

Payload-shape decisions:

- **`:input-values` / `:result` are the actual values, not hashes.** The trace surface is dev-only (per [§Production builds](#production-builds-zero-overhead-zero-code)) and downstream tools — Causa's flow panel, custom dashboards — display the values. Hashing would force consumers to consult an out-of-band side table; raw values keep the stream self-contained.
- **`:rf.flow/skip` carries `:reason :inputs-value-equal`** rather than always being implicit. The keyword is the future extension point if additional skip reasons land (e.g. flow disabled mid-walk, frame in restore).
- **`:rf.flow/failed` re-throws** so cascade-level error-handling (Spec 009 §Error contract's `:rf.error/flow-eval-exception`) still fires; the per-flow `:rf.flow/failed` adds the per-flow attribution.

Pair-shaped tools and Causa's flow panel filter `op-type :flow` (per [Tool-Pair §How AI tools attach](Tool-Pair.md#how-ai-tools-attach)) to subscribe to the whole stream.

## Subscription / consumption

re-frame2's trace API uses **synchronous, event-at-a-time delivery** — every registered listener is invoked once per emitted trace event while the runtime is still on the emit call stack. Listener-invocation order is **not contract**; tools must not depend on the order in which sibling listeners receive a given event. There is no batching, debounce window, or background delivery loop. Listeners SHOULD do minimal work in the callback (queue, append to a buffer, mark a flag) and defer expensive work to a separate timer or animation frame they own.

### The listener API

The canonical listener API has one shape:

```clojure
(rf/register-trace-listener! key callback-fn)
;; Subscribes callback-fn to receive every trace event as it is emitted.
;; Same key replaces any previously-registered listener under that key.
;; Returns the key.
;;
;; Arguments:
;;   key         — any comparable value identifying the listener
;;                 (replaces same-key registration)
;;   callback-fn — invoked with one trace event per call.
;;                 Signature: (fn [trace-event] ...)

(rf/unregister-trace-listener! key)
;; Unsubscribes the listener registered under key. Returns nil.

(rf/clear-trace-listeners!)
;; Test-time helper: drops all registered raw-trace listeners atomically.
;; Returns nil. Used by `re-frame.test-support/reset-runtime-fixture` to
;; restore a clean listener registry between tests; ordinary application
;; code SHOULD use `unregister-trace-listener!` per key. The same dev-only elision
;; rules apply (production builds drop the registry entirely).
```

Conventional keys: `:my-app/recorder`, `:my-app/timing-monitor`, etc.

**Re-registration semantics.** `register-trace-listener!` called with a key already in the registry replaces the previous callback atomically — the swap from old to new happens between two emits, never mid-emit. No trace event is emitted for the replacement (the listener registry is itself dev-only metadata; mutating it does not feed the trace stream); no events delivered to the previous callback are re-delivered to the new one, and no events emitted after the swap are dropped. Hot-reload tools that re-register their listener on every code reload see exactly one stream of events with the swap point invisible to the runtime. The same semantics apply to `register-epoch-listener!` re-registration under an existing key.

**Worked example.** A minimal recorder that prints every error trace to the console:

```clojure
(rf/register-trace-listener!
  :my-app/error-logger
  (fn [trace-event]
    (when (= :error (:op-type trace-event))
      (println (:operation trace-event)
               (-> trace-event :tags :reason)))))
```

The same pattern with `register-epoch-listener!` to log one assembled cascade per drain-settle:

```clojure
(rf/register-epoch-listener!
  :my-app/cascade-logger
  (fn [epoch-record]
    (println (:event-id epoch-record)
             "→" (count (:effects epoch-record)) "fx"
             "/" (count (:sub-runs epoch-record)) "sub-runs")))
```

#### `register-epoch-listener!` — assembled-epoch listener

Alongside the raw trace stream, the framework exposes a parallel **assembled-epoch listener** API. Where `register-trace-listener!` delivers each raw event as it is emitted, `register-epoch-listener!` delivers one fully-assembled `:rf/epoch-record` (per [Spec-Schemas](Spec-Schemas.md#rfepoch-record)) per drain-settle:

```clojure
(rf/register-epoch-listener! key callback-fn)
;; Subscribes callback-fn to receive assembled epoch records.
;;
;; Arguments:
;;   key         — any comparable value identifying the listener
;;                 (replaces same-key registration)
;;   callback-fn — invoked with one :rf/epoch-record per drain-settle.
;;                 Signature: (fn [epoch-record] ...)
;;
;; The record is the same shape the runtime appends to (rf/epoch-history frame-id):
;; assembled :event-id / :trigger-event / :db-before / :db-after, plus the structured
;; :sub-runs / :renders / :effects projections derived from the cascade's traces.

(rf/unregister-epoch-listener! key)
;; Unsubscribes the listener registered under key.
```

**Invocation rules** (mirrors `register-trace-listener!`):

- **Per drain-boundary, not per event.** The callback fires once at every drain boundary — both clean settles AND halted drains (`:halted-depth`, `:halted-destroy`; see [Spec-Schemas §`:rf/epoch-record` §Outcomes](Spec-Schemas.md#outcomes-rf2-v0jwt)). Multi-event cascades produce one record (and one callback invocation), not one per event.
- **After commit.** The callback receives a fully-formed record with `:db-after`, `:sub-runs`, `:renders`, `:effects`, and any optional `:trace-events` populated. The record has already been appended to the frame's `epoch-history` ring buffer when the callback runs.
- **Exception isolation.** An exception thrown by an epoch callback is caught and does not propagate. One broken epoch listener cannot break the app or block other listeners (raw-trace or epoch).
- **Listener ordering** is not contract.
- **Production elision.** The epoch listener machinery is gated on the same `re-frame.interop/debug-enabled?` flag (alias of `goog.DEBUG`) as the raw-trace surface — see [§Production builds](#production-builds-zero-overhead-zero-code). Production builds elide registration, dispatch, and the epoch ring-buffer all together.

**Halted cascades (rf2-v0jwt).** Listeners receive epoch records for halted drains as well as clean settles. `:outcome` on the record discriminates — `:ok`, `:halted-depth`, or `:halted-destroy`. The partial record carries whatever the runtime captured up to the halt point: `:trace-events`, `:sub-runs`, `:renders`, `:effects` reflect the cascade-so-far, and `:halt-reason` carries a structured descriptor of why the drain halted. This is the **devtools surface** for failing cascades — Causa's epoch panel, re-frame2-pair's `cascade-of`, post-mortem dashboards: all route off the same listener, and `:outcome` lets them render the failure with the right shape. Consumers that only care about successful drains filter on `(= :ok (:outcome record))` at the top of their callback. `restore-epoch` refuses non-`:ok` records — see [Spec-Schemas §`:rf/epoch-record` §Outcomes](Spec-Schemas.md#outcomes-rf2-v0jwt).

**When to use which.** `register-trace-listener!` is the right shape for tools that need fine-grained per-event activity (custom recorders, error-monitor forwarders, timing aggregators). `register-epoch-listener!` is the right shape for tools that route diagnostics off "what just happened in this cascade" — pair-shaped tools, post-mortem dashboards, anything that wants the structured `:sub-runs` / `:renders` / `:effects` projection without re-folding the raw trace stream.

The two listener APIs are independent: tools may register either, both, or neither. They share the production-elision gate but have separate listener registries; no listener of one kind can interfere with the other.

### Cascade projection (`group-cascades` / `domino-bucket`)

The raw trace stream is event-at-a-time; pair-shaped UIs (the Story trace panel, the Causa event-detail panel, re-frame2-pair's `cascade-of`) all want the **six-domino slice** of the stream — one record per cascade with the event vector, handler emit, fx-map emit, effects, sub-runs, and renders already split into named slots. The framework ships that projection as a pure-data function in `re-frame.trace.projection`, re-exported from `re-frame.core`:

```clojure
(rf/group-cascades trace-events)
;; -> [{:dispatch-id <id-or-:ungrouped>
;;      :event       <event-vector | nil>   ;; from :event/dispatched
;;      :handler     <trace-event | nil>    ;; the :op-type :event / :operation :event emit
;;      :fx          <trace-event | nil>    ;; :event/do-fx
;;      :effects     [<trace-event> ...]    ;; :op-type :fx — :rf.fx/handled, override-applied, …
;;      :subs        [<trace-event> ...]    ;; :sub/run + :sub/create
;;      :renders     [<trace-event> ...]    ;; :op-type :view / :operation :view/render
;;      :other       [<trace-event> ...]}   ;; errors, warnings, machines, frames, flows,
;;                                          ;;   registry, anything outside the six dominoes
;;     ...]
```

Events without a `:dispatch-id` (registry-time emits, frame lifecycle, REPL evals outside a drain) collect under `:dispatch-id :ungrouped`. The returned vector is sorted by the lowest `:id` in each cascade so consumers render cascades in emission order. The projection is **pure data** — JVM and CLJS run the same code; tools wiring up post-mortem renders against `(rf/trace-buffer)` get the same output shape as live consumers reading from a `register-trace-listener!` listener.

`(rf/domino-bucket trace-event)` is the underlying classifier — returns one of `#{:event :handler :fx :effect :sub :render :other}`. Tools that want custom rollups can call it directly per event and skip `group-cascades`.

Per rf2-g6ih4 (cascade-wide `:dispatch-id`) the projection is robust against errors, fx, sub-runs, and renders that fire *inside* a drain even though they aren't `:event/dispatched` — every such event carries `:tags :dispatch-id` so they group into the cascade record automatically.

The projection is additive: new `:op-type` values that don't fit a domino slot flow through `:other` without breaking existing consumers.

### Listener invocation rules

- **Synchronous, event-at-a-time.** Every registered listener is invoked once per emitted trace event, on the runtime's emit call stack. There is no batching, debounce window, or background delivery loop. Listeners SHOULD return quickly; expensive work belongs on a tool-owned timer or rAF.
- **Events arrive in emission order.** Each listener sees trace events in the order the runtime fired them. (This is about per-listener *event* order, not order *across* listeners — see the next rule.)
- **Listener-invocation order is not contract.** When multiple listeners are registered, the order in which sibling listeners receive a given event is unspecified. Tools must not depend on order; each listener receives the same event independently. The same rule applies to `register-epoch-listener!` callbacks.
- **Exception isolation.** An exception thrown by a listener is caught and does *not* propagate to the framework or other listeners. One broken tool can't break the app or block other tools. The caught exception is logged via `re-frame.interop/log-error` (or the host equivalent) and otherwise discarded; the runtime does NOT emit a self-referential trace event for the failed listener (which would risk a re-entrant trace-emit storm). The same handling applies to exceptions thrown by an `register-epoch-listener!` callback.
- **No buffering between listeners and the runtime.** The framework does not retain a delivery buffer; the retain-N ring buffer described next is independent and exists for late-attaching tools.

### Retain-N trace ring buffer (dev-only)

In dev builds, the framework maintains a **retain-N trace ring buffer** alongside the synchronous-delivery path. The ring buffer holds the most recent N emitted trace events and is queryable. This lets pair-shaped AI tools, REPL-attached debuggers, and post-mortem dashboards read recent activity without having to be registered as a `register-trace-listener!` listener at the time the events fire.

Contract:

| API | Signature | Notes |
|---|---|---|
| `(rf/trace-buffer)` | `() → vector` | Returns the buffer's current contents, oldest-first. Empty when no events have been recorded. |
| `(rf/trace-buffer opts)` | `(opts) → vector` | Optional filter map (see [§Filter vocabulary](#filter-vocabulary) below). Filters compose AND-wise; absent key = no constraint on that axis. |
| `(rf/clear-trace-buffer!)` | `() → nil` | Empties the buffer. Tooling uses this between sessions. |
| `(rf/configure :trace-buffer {:depth N})` | `(N) → nil` | Configure depth; default 200 events. `0` disables the ring buffer (synchronous delivery still works). |

#### Filter vocabulary

`(rf/trace-buffer opts)` recognises the following filter keys. All compose AND-wise; an absent key means "no constraint on that axis." Unrecognised keys are ignored (forward-compat: tools may probe new axes; missing support degrades to "no filter").

| Key | Type | Semantics |
|---|---|---|
| `:operation` | keyword | Match exact `:operation` value (e.g. `:event/dispatched`, `:rf.fx/handled`). |
| `:op-type` | keyword | Match exact `:op-type` discriminator (e.g. `:event`, `:fx`, `:error`). |
| `:since` | number | Keep events whose `:id` is strictly greater than this. Cursor-based polling — read the last event's `:id`, pass on next call. |
| `:frame` | keyword | Match `:tags :frame` (or top-level `:frame` fallback). |
| `:severity` | `:error` / `:warning` / `:info` | Synonym for `:op-type` restricted to the three severity tiers. Use this when filtering for the issues feed. |
| `:event-id` | keyword | Match `:tags :event-id` — the first element of the dispatched event vector (e.g. `:user/login`). Present on `:event/*`, `:rf.error/handler-exception`, `:event/db-changed`, and other event-scoped emits. |
| `:handler-id` | keyword | Match `:tags :handler-id` — the registered handler's id. Present on handler-error emits. |
| `:source` | `:ui` / `:timer` / `:http` / `:repl` / `:machine` / `:ssr-hydration` | Match the top-level `:source` slot (hoisted from `:tags :source` by `emit!`). Identifies the trigger origin. |
| `:origin` | `:app` / `:pair` / `:story` / `:test` / ... | Match `:tags :origin` per [Spec 002 §Dispatch origin tagging](002-Frames.md). Lets tools filter "only my dispatches." |
| `:dispatch-id` | number | Match `:tags :dispatch-id` — the cascade-wide correlation key. Post rf2-g6ih4, every emit inside a drain carries the in-flight cascade's id, so this filter narrows the buffer to one cascade. |
| `:since-ms` | number | Keep events whose `:time` (host-clock ms) is strictly greater than this. Pair with the scrubber's "drag time-range" gesture. |
| `:between` | `[t0 t1]` | Two-element vector — keep events whose `:time` falls in `[t0, t1]` inclusive. |
| `:pred` | `(fn [ev] → truthy)` | Arbitrary predicate. Receives the full event map. Returning truthy keeps the event. Escape hatch for filters not yet promoted to named keys. |

Filters compose AND-wise — supplying both `:op-type :error` and `:frame :tb/scope` keeps only error events on that frame.

Semantics:

- **Ring discipline.** When the buffer is full, the oldest event is evicted as a new one is pushed. No allocation churn beyond the slot count.
- **Same events as delivery.** Every event delivered to listeners also lands in the ring buffer. Ring-buffer events are the same maps the listeners receive.
- **Independent of listeners.** A tool that attaches *after* events have fired can read the most-recent N from the ring buffer to bootstrap its view; a tool that wants a continuous live feed registers a `register-trace-listener!` listener as well.
- **Production elision.** The ring buffer, like the rest of the trace surface, is compile-time eliminated in production builds (per [§Production builds](#production-builds-zero-overhead-zero-code)). `(rf/trace-buffer)` returns an empty vector in production, and the buffer itself is not allocated.
- **Depth-zero semantics.** When configured with `{:depth 0}`, the ring buffer is disabled but the surface remains live: `(rf/trace-buffer)` returns `[]`, `(rf/trace-buffer opts)` returns `[]`, and `(rf/clear-trace-buffer!)` is a no-op (returns `nil`). Synchronous-delivery to registered listeners continues to fire — only the queryable history is suppressed.
- **Lowering depth on a populated buffer.** `(rf/configure :trace-buffer {:depth N})` applied while the buffer holds more than `N` events drops the oldest events first to fit the new depth (same eviction order as the ring discipline). Raising the depth keeps existing events and grows the slot count.

Why this is a framework primitive (not a Causa-specific concern): pair-shaped tools, REPL companions, and any non-Causa consumer needs recent-history access. Locating the buffer in the framework means external tools depend on a stable framework primitive rather than on Causa's internal data structures. See [Tool-Pair §How AI tools attach](Tool-Pair.md#how-ai-tools-attach) for the full consumption pattern.

**Topology note (rf2-qwm0a).** The public-tooling surface — `register-trace-listener!` / `unregister-trace-listener!` / `clear-trace-listeners!` / `trace-buffer` / `clear-trace-buffer!` / `configure-trace-buffer!` / `configure` — and the buffer + listener state live in the sibling `re-frame.trace.tooling` namespace, not `re-frame.trace` itself. `re-frame.trace` carries the always-loaded hot fast path (`emit!` / `emit-error!` / `*handler-scope*`); the tooling sibling is loaded only when a test fixture, tool (Causa / Story / re-frame2-pair-mcp), or dev preload `:require`s it. The `rf/...` public Vars and the `re-frame.trace/<surface>` wrappers delegate via the `:trace.tooling/*` late-bind hooks so existing consumer call sites are unchanged. On the JVM the tooling sibling is autoloaded by `re-frame.trace` (zero bundle cost off-bundle). On CLJS the tooling sibling is omitted from production counter bundles — the hook lookups return nil and the wrappers no-op (DCE drops the body wholesale, ~2 KB raw / ~600 B gzipped saved).

## Emitting trace events

The framework emits trace events through one entry point: `re-frame.trace/emit!`. User code may also call it (re-exported as `rf/emit-trace-event!`) to add custom events to the stream.

```clojure
(re-frame.trace/emit! op-type operation tags)
;; Emits one trace event with the given :op-type / :operation / :tags.
;; Returns nil. The runtime stamps :id and :time, hoists :source and
;; :recovery (when present in tags) to the top level, pushes the event
;; into the retain-N ring buffer, and synchronously invokes every
;; registered listener.
```

The shape is synchronous and side-effecting: the emit returns once every listener has been invoked. There is no span-shape machinery — events are emitted at the moment of interest with all relevant tags already populated. (For codebases migrating from a span-shaped tracing library, see [MIGRATION.md §M-26](../migration/from-re-frame-v1/README.md#m-26-drift-sweep-drops--v1-surfaces-with-no-v2-equivalent-or-absorbed-by-canonical-surfaces).)

### Compile-time elision

`emit!`'s body is wrapped in `(when re-frame.interop/debug-enabled? ...)`. `debug-enabled?` is an alias of `goog.DEBUG` on CLJS (default `true` in dev, `false` in `:advanced` production builds); when the constant is `false` the closure compiler eliminates the gated branch and the call becomes a no-op. See "Production builds" below for the full mechanism.

### Trace-emission opt-out: `:rf.trace/no-emit?` event-meta

Handlers (`reg-event-db` / `reg-event-fx` / `reg-event-ctx`, `reg-sub`, `reg-fx`, `reg-cofx`, view registrations) whose registration metadata carries `:rf.trace/no-emit? true` produce **no trace events**. The runtime short-circuits `emit!` / `emit-error!` / the queue-time `:event/dispatched` emit when the in-scope handler — or, for `:event/dispatched`, the target handler — opts out. The runtime publishes the handler's `:no-emit?` reading via the `:no-emit?` slot of `re-frame.trace/*handler-scope*` (alongside `:trigger-handler` and `:sensitive?`, per [§Handler-scope](#handler-scope-the-in-scope-reading-at-emit-time)); the gate sits inside the outer `interop/debug-enabled?` `when` so production elision is preserved.

```clojure
(rf/reg-event-db :rf.causa/note-trace-event
  {:rf.trace/no-emit? true}                     ;; <- opt-out
  (fn [db [_ event]]
    (assoc db :trace-buffer (conj (:trace-buffer db []) event))))
```

The flag is the framework-level escape hatch for **trace-consuming integrations** whose own bookkeeping dispatches — emitted from inside a registered `trace-cb` — would otherwise re-enter the consumer through the trace-cb fan-out and form a cb-dispatch loop. Causa, Story, re-frame2-pair-mcp, and story-mcp all have the same risk shape; without the opt-out each consumer would need its own per-dispatch guard predicate (Causa carried one as `trace-bus/self-emitted?` between rf2-nk01x and rf2-qsjda). Promoting the gate to the framework lets any consumer mark a handler internal-only and trust the runtime to suppress the cascade.

Semantics:

- **What's suppressed.** `:event/dispatched` (queue-time, when the *target* handler's meta carries the flag), `:event :run-start` / `:run-end`, `:event/db-changed`, `:rf.fx/handled`, `:rf.machine/transition`, `:sub/run`, `:view/render`, and every `:rf.error/*` emit produced inside the handler's scope. The always-on event-emit substrate (`rf2-rirbq`) ALSO honours the flag and drops the per-event record for `:rf.trace/no-emit?`-flagged handlers — same boundary semantics as the `:sensitive?` short-circuit per rf2-6hklf, on the rationale that framework-internal bookkeeping handlers are not user-domain observable signal.
- **What's NOT suppressed.** The handler body still runs — the opt-out applies to OBSERVABILITY (trace + event-emit), not handler execution. The dispatch is queued, drained, and committed normally; the handler's db effect is committed; its fx are walked.
- **Cascade composition.** Innermost in-scope handler wins. A non-opt-out handler dispatched from inside a `:rf.trace/no-emit? true` handler emits normally — the inner binding rebinds to false and the inner cascade is visible. (Same composition rule as `:sensitive?`, per Spec 009 line 1177.)
- **Production elision.** The trace-surface gate sits inside `interop/debug-enabled?` and DCEs out in `:advanced` production builds (the trace surface is dev-only by construction — production never emits trace events at all). The event-emit short-circuit survives production builds (event-emit is always-on per rf2-rirbq), so production listeners equally drop opt-out handler records.

Per rf2-nk01x / rf2-qsjda and the framework `re-frame.trace/*handler-scope*` Var's `:no-emit?` slot (per [§Handler-scope](#handler-scope-the-in-scope-reading-at-emit-time)).

### Where trace emission lives

The framework emits trace events from these call sites:

- `events.cljc` — `:warning :rf.warning/interceptors-in-metadata-map`; `:rf.error/effect-map-shape`; `:rf.error/effect-handler-bad-return` (per rf2-k3bj).
- `subs.cljc` — `:sub/create`, `:sub/run`; `:rf.error/no-such-sub` and `:rf.error/sub-exception` for failure paths.
- `fx.cljc` — `:event/do-fx` per drain step (per rf2-twt7m the emit's `:tags` additionally carries `:fx` (the vector the handler returned) and `:db-present?` (boolean — was the handler's return-map's `:db` slot supplied?) so consumers can align cascade rows with handler returns without re-reading the interceptor context; the `:db` VALUE is intentionally NOT stamped — slice changes already ride `:event/db-changed`. Both slots sit under `:tags` alongside `:frame`, consistent with the payload-shaped tag convention), `:rf.fx/handled` per dispatched fx, `:fx/override-applied`, `:warning :rf.fx/skipped-on-platform`, `:rf.error/fx-handler-exception`, `:rf.error/no-such-fx`, plus `:rf.machine/spawned` and `:rf.machine/destroyed`.
- `cofx.cljc` — `:rf.error/no-such-cofx` (emitted by `inject-cofx` when the cofx-id has no registered handler), `:warning :rf.cofx/skipped-on-platform` (emitted when a registered cofx's `:platforms` excludes the active platform; mirrors `:rf.fx/skipped-on-platform` per [011 §Effect handling on the server](011-SSR.md#effect-handling-on-the-server)).
- `router.cljc` — `:event :event` (`:run-start` and `:run-end` phases), `:event :event/dispatched`, `:event :event/db-changed`, `:rf.error/handler-exception`, `:rf.error/drain-depth-exceeded`, `:rf.error/no-such-handler`, `:warning :rf.warning/dispatch-from-async-callback-fell-through-to-default` (emitted alongside `:rf.error/no-such-handler` when a dispatch landed on `:rf/default` purely because the resolution chain fell through and the handler is missing; per rf2-o8m0), `:rf.error/dispatch-sync-in-handler`, `:rf.error/frame-destroyed`, `:rf.error/flow-eval-exception`, `:rf.frame/drain-interrupted` (lifecycle event emitted when the drain loop detects a destroyed frame mid-cycle; per [002 §Edge cases worth pinning](002-Frames.md#edge-cases-worth-pinning)).
- `frame.cljc` — `:frame/created`, `:frame/re-registered`, `:frame/destroyed`, `:rf.machine.lifecycle/destroyed`.
- `registrar.cljc` — `:rf.registry/handler-registered`, `:rf.registry/handler-replaced`, `:rf.registry/handler-cleared`, `:warning :rf.warning/missing-doc` (emitted once per `(kind, id)` pair when a `reg-*` registration omits `:doc`; per [001 §`:doc` is dev-warned when absent](001-Registration.md#doc-is-dev-warned-when-absent) and rf2-lhu1e).
- `machines.cljc` + `machines/transition.cljc` + `machines/lifecycle_fx.cljc` + `machines/timer.cljc` + `machines/parallel.cljc` (per the rf2-5hnn file split: `machines.cljc` is a thin façade and emits land in the four sub-namespaces) — `:rf.machine/event-received`, `:rf.machine/transition`, `:rf.machine.microstep/transition` (one per microstep on `:always`-driven cascades, per [005 §Trace events](005-StateMachines.md#trace-events)), `:rf.machine/snapshot-updated`, `:rf.machine.lifecycle/created`, `:rf.machine/system-id-bound`, `:rf.machine/system-id-released` (per [005 §Named addressing via `:system-id`](005-StateMachines.md#named-addressing-via-system-id)), `:rf.machine.timer/scheduled`, `:rf.machine.timer/fired`, `:rf.machine.timer/stale-after`, `:rf.machine.timer/skipped-on-server` (under SSR; per [005 §SSR mode](005-StateMachines.md#ssr-mode)), `:rf.machine/guard-evaluated` (per rf2-2nwfd — emitted from the unified `evaluate-guard` helper at every user-declared guard call site in `machines/transition.cljc`; `:tags {:machine-id <id> :guard-id <kw-or-fn> :input {:data <data> :event <event-vec>} :outcome :pass | :fail}`; the synthesised always-true returned by `resolve-guard` for a nil guard-ref does NOT emit), `:rf.machine/action-ran` (per rf2-2nwfd — emitted from `run-action` for every user-declared action invocation; `:tags {:machine-id <id> :action-id <kw-or-fn> :input {:data <data> :event <event-vec>} :outcome <return-value> | :ok | :rf.error/action-threw :exception <Throwable on the throw path>}`; success-with-nil-return collapses to `:ok`; the throwing path emits one trace with `:outcome :rf.error/action-threw` + `:exception` before propagating the `result/fail`), plus the machine-error categories.
- `routing.cljc` — `:rf.route/fragment-changed` (fragment-only navigation; rf2-cj9fn — renamed from `:rf.route/url-changed`), `:rf.route/registered` / `:rf.route/cleared` / `:rf.route/activated` / `:rf.route/deactivated` (lifecycle pair, rf2-dn26r), `:rf.route/navigation-blocked`, `:rf.route.nav-token/allocated`, `:rf.route.nav-token/stale-suppressed`, `:rf.fx/skipped-on-platform` (route-fx platform skips), `:warning :rf.warning/route-shadowed-by-equal-score`, `:error :rf.error/can-leave-non-boolean` (rf2-5pyyl — closed `:can-leave` contract).
- `flows.cljc` — `:rf.flow/registered`, `:rf.flow/computed`, `:rf.flow/skip`, `:rf.flow/cleared`, `:rf.flow/failed` (per [013 §Flow tracing](013-Flows.md#flow-tracing)). All carry `:op-type :flow`.
- `schemas.cljc` — `:rf.error/schema-validation-failure` (from `validate-app-db!` / `validate-event!` / `validate-cofx!` / `validate-sub-return!`), `:warning :rf.warning/schema-validator-unavailable` (emitted once per process from `reg-app-schema` / `reg-app-schemas` when `:schemas/malli-validate` is unbound AND the framework-default validator is still installed; per [010 §Recommended soft-pass](010-Schemas.md) and rf2-fq7d2), `:warning :rf.warning/schema-walker-opaque` (emitted once per process from `reg-app-schema` / `reg-app-schemas` when the registered schema is a non-vector form — registry-ref keyword, compiled `m/schema` object, or other opaque value — so the walker cannot introspect per-slot `:sensitive?` / `:large?` flags; per [010 §The `:schema` value is opaque to re-frame](010-Schemas.md) and rf2-jsokn).
- `spec.cljc` — `:rf.error/schema-validation-failure :where :event :source :boundary` (from the `:rf.schema/at-boundary` interceptor; per [010 §Production builds](010-Schemas.md#production-builds) and rf2-r2uh).
- `events.cljc` — `:rf.error/at-boundary-missing-schema` (thrown from `reg-event-*` when `:rf.schema/at-boundary` is attached to a handler whose metadata-map carries no `:schema`; per [010 §Production builds](010-Schemas.md#production-builds) and rf2-iftj4).
- `ssr.cljc` — `:rf.ssr/hydration-mismatch` (carries `:failing-id` to discriminate body-mismatch from head-mismatch; per [011 §Hydration-mismatch detection](011-SSR.md#hydration-mismatch-detection) and [011 §Mismatch detection — head](011-SSR.md#mismatch-detection--head)), `:warning :rf.warning/multiple-status-set`, `:warning :rf.warning/multiple-redirects`, `:rf.error/sanitised-on-projection`.
- `epoch.cljc` — `:rf.epoch/snapshotted` per drain-settle, `:rf.epoch/restored` on restore success, `:rf.epoch/db-replaced` on `reset-frame-db!` success (rf2-zq55), plus the six restore-failure categories and the two reset-frame-db! failure categories (`:rf.epoch/reset-frame-db-during-drain`, `:rf.epoch/reset-frame-db-schema-mismatch`), plus `:rf.epoch.cb/silenced-on-frame-destroy` emitted once per `(frame-id, cb-id)` pair on the destroy-cascade boundary (rf2-d656), plus `:rf.epoch.cb/listener-exception` (op-type `:error`) emitted once per broken-listener invocation when an epoch listener throws — isolation contract still holds (sibling listeners and the runtime continue), the trace is the alarm so devtools surface the failure rather than silently dropping it (rf2-i5khp).
- `views.cljs` — `:view/render` per registered-view render (per [Spec 004 §Render-tree primitives](004-Views.md)).
- `adapter/context.cljs` — `:rf.error/frame-context-corrupted` (function-component `_currentValue` read observed a non-coercible shape; per rf2-8q66).
- `substrate/adapter.cljc` — `:warning :rf.warning/write-after-destroy` emitted by the `replace-container!` wrapper when called with a nil container (the frame was destroyed mid-drain or before a scheduled write fired; the underlying adapter's `replace-container!` is NOT invoked). Per [006 §`replace-container!`](006-ReactiveSubstrate.md#read-container-container--value-and-replace-container-container-new-value--nil) and rf2-ft2b.
- `std_interceptors.cljc` — `:rf.error/unwrap-bad-event-shape`.
- `http_managed.cljc` + `http_encoding.cljc` (the HTTP artefact ships eight `http_*.cljc` files; emits cited here come from `http_managed.cljc` unless noted) — `:warning :rf.http/cljs-only-key-ignored-on-jvm`, `:warning :rf.warning/decode-defaulted` (emitted from `http_encoding.cljc`), `:info :rf.http/retry-attempt`, `:info :rf.http/aborted-on-actor-destroy` (per [014 §Abort on actor destroy](014-HTTPRequests.md#abort-on-actor-destroy), rf2-wvkn), `:info :rf.http.interceptor/registered`, `:info :rf.http.interceptor/cleared`, `:error :rf.error/http-interceptor-failed` (request-side interceptor `:before` threw, per [014 §Middleware](014-HTTPRequests.md#middleware), rf2-6y3q), plus the Spec 014 failure categories.

User code can also emit traces — `re-frame.trace/emit!` is public and re-exported as `rf/emit-trace-event!`.

## Production builds: zero overhead, zero code

**All dev-side instrumentation is a development-only concern.** In production builds, the trace surface, the schema validation surface (Spec 010), and the registrar's hot-reload trace emit (`:rf.registry/handler-{registered,replaced,cleared}`) are *all* compile-time eliminated through a single shared gate. The closure compiler's dead-code elimination removes the gated branches; production binaries contain no instrumentation machinery at all.

### The mechanism: `re-frame.interop/debug-enabled?` (alias of `goog.DEBUG`)

The CLJS implementation uses one shared flag — an alias of the standard `goog.DEBUG` closure-define — for every dev-only branch:

```clojure
;; src/re_frame/interop.cljs
(def ^boolean debug-enabled? "@define {boolean}" ^boolean goog/DEBUG)
```

Every framework-internal dev branch — `trace/emit!`, `trace/emit-error!`, `schemas/validate-app-db!`, `schemas/validate-event!`, `schemas/validate-cofx!`, `schemas/validate-sub-return!`, and the `registrar/{register!,unregister!,clear-kind!}` trace emits — wraps its body in `(when interop/debug-enabled? ...)`. With `:advanced` compilation and `:closure-defines {goog.DEBUG false}`, Closure constant-folds the gate and DCEs every dependent allocation: trace maps, listener iteration, malli calls, error reason strings, the Performance API bridge.

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
- Excludes the trace buffer payload.
- Excludes the Performance API bridge.
- Excludes the schema validation entry points and their malli/explanation calls.

### How users opt in (dev builds)

CLJS dev builds default to `goog.DEBUG=true` — every gate stays live with no extra configuration. A user who wants trace machinery in a `:advanced` artefact (rare) can flip the flag explicitly:

```edn
;; shadow-cljs.edn — :advanced build with trace kept in
{:closure-defines {goog.DEBUG true}}
```

### User-side listener registration

User-side `(rf/register-trace-listener! ...)` calls should also elide in production. Wrap them with the same predicate the framework uses:

```clojure
(when ^boolean re-frame.interop/debug-enabled?
  (rf/register-trace-listener! :my/listener callback-fn))
```

In production (`goog.DEBUG=false`), `re-frame.interop/debug-enabled?` is the constant `false`, the `when` is dead, and the entire registration is elided.

The same pattern applies to `register-epoch-listener!`, `trace-buffer`, `clear-trace-buffer!`, and `(rf/configure :trace-buffer …)` — every dev-only call site in user code should sit under the `when ^boolean re-frame.interop/debug-enabled?` guard.

### JVM builds

> Cross-reference: see [Security.md §Production gates](Security.md#production-gates) — SSR / webhook / long-running JVMs facing untrusted input MUST set the gate `false` explicitly so dev-side trace enrichment elides in production. The runtime gate below is the JVM mirror of CLJS `goog.DEBUG`; both surfaces compose with the always-on substrates above.

JVM has no `:advanced` and no compile-time DCE. The JVM half of the interop layer:

```clojure
;; src/re_frame/interop.clj — JVM-side gate read once at ns-load
(def debug-enabled?
  (read-debug-flag))   ;; defaults to `true`; opt-out via property / env
```

…reads the gate ONCE at namespace load. The default is `true` (dev parity), but the gate is **explicitly overridable** for the SSR production posture — the JVM-side counterpart to CLJS `goog.DEBUG=false` (per rf2-vnjfg / rf2-0la4f).

**Opt-out vocabulary.** Two input sources, read in this order, with the JVM system property winning on conflict:

1. **Java system property `re-frame.debug`** — set on the JVM command line with `-Dre-frame.debug=false`.
2. **Environment variable `RE_FRAME_DEBUG`** — set in the process environment.

Both accept the conventional false-y vocabulary case-insensitively: `false`, `0`, `no`, `off`, empty string. Whitespace is trimmed. Anything else — including absent / unset — leaves the flag at its default `true`. The vocabulary is intentionally conservative: only the documented opt-out strings disable the gate, so an accidental typo (`disabled`, `nope`) leaves the dev posture alive rather than silently misconfiguring.

**What disabling the gate suppresses.** With `re-frame.debug=false` set BEFORE `re-frame.interop` loads, every JVM-side dev surface drops to its no-op floor — the same shape CLJS `:advanced` + `goog.DEBUG=false` builds achieve via Closure DCE:

- Trace emission (`emit!` / `emit-error!` / the queue-time `:event/dispatched` emit) is silent.
- The retain-N trace ring buffer accumulates nothing.
- `register-trace-listener!` listeners receive no events.
- The epoch artefact (per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel)) records no `:db-before`/`:db-after`/`:trace-events` payloads, fires no `register-epoch-listener!` listeners, and refuses `restore-epoch` / `reset-frame-db!`.

**What remains live (always-on by construction).** Disabling the gate does NOT silence the production-survivable surfaces:

- The `register-event-emit-listener!` substrate (per rf2-rirbq, [§Event-emit listener](#what-is-available-in-production)) keeps firing.
- The `register-error-emit-listener!` substrate and the per-frame `:on-error` policy fn (per rf2-bacs4 / rf2-hqbeh, [§Error-emit listener](#what-is-available-in-production)) keep firing.
- Schema validation, the registrar, and the dispatch loop itself are unaffected.

Those surfaces are explicitly always-on per their owning specs — they exist precisely for the SSR / production posture and would defeat their purpose if a debug-gate flip silenced them. They run with the `:sensitive?` substrate-level enforcement described in [§Privacy / sensitive data in traces](#privacy--sensitive-data-in-traces).

**Set the flag before re-frame loads.** The Var reads its value at ns-load time, then JIT-inlines into the per-call `when interop/debug-enabled?` checks. Late mutation via `alter-var-root!` works for tests (and is the canonical way to flip the gate within a test) but does not retroactively elide already-allocated infrastructure.

The motivating concern is the audit finding (rf2-vnjfg / rf2-0la4f): an SSR / headless JVM process running re-frame2 should not, by default, retain user input in trace ring buffers or epoch history. Apps that ship a JVM artefact for production should set `-Dre-frame.debug=false` in their deployment. The dev / test posture is unchanged.

### Production-elision verification

The contract above is enforced by an automated test in CI:

1. `implementation/core/test/re_frame/elision_probe.cljs` is a probe namespace that exercises every gated surface — `register-trace-listener!`, `emit-trace-event!`, the trace ring buffer (`trace-buffer` / `clear-trace-buffer!` / `(configure :trace-buffer …)`), `validate-{app-db,event,sub-return,cofx}!`, `register!` / `unregister!` / `clear-kind!`, the epoch surface (`register-epoch-listener!` / `epoch-history` / `restore-epoch` / `(configure :epoch-history …)`), plus a representative `dispatch-sync` flow. The probe roots the dead-code-elimination graph at every surface so a leak surfaces in the bundle.
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

- `register-trace-listener!` / `unregister-trace-listener!` — listener registration is a no-op because the gate around `trace/emit!` is constant-folded out. Even if user code registered a listener at boot (which it shouldn't, per [§User-side listener registration](#user-side-listener-registration)), nothing would ever invoke it.
- The retain-N trace ring buffer (`trace-buffer`, `clear-trace-buffer!`, `(configure :trace-buffer …)`) — pulling "the last N events from a prod session" is not supported. The buffer's `swap!` site is inside the same elision gate.
- `register-epoch-listener` and the per-drain `:rf/epoch-record` assembly — epoch projection runs inside the trace surface and elides with it.
- Every `:rf.error/*`, `:rf.warning/*`, `:rf.info/*`, `:rf.fx/*`, `:rf.ssr/*`, and `:rf.epoch/*` trace event documented in [§Error event catalogue](#error-event-catalogue). They are not emitted, not buffered, and not deliverable to any listener. (The `:on-error` per-frame slot — per [002-Frames §`:on-error`](002-Frames.md) — is a documented exception: it rides a small always-on error-emit substrate that survives `goog.DEBUG=false`. See [§What IS available in production](#what-is-available-in-production) below.)
- Source-coord enrichment (`:rf.trace/trigger-handler`), `:dispatch-id` / `:parent-dispatch-id` correlation, `:origin` tagging — all ride the trace event and elide with it.
- Schema validation (`:rf.error/schema-validation-failure`) and registrar hot-reload notifications (`:rf.registry/handler-registered` and siblings) — same gate, same elision.
- The Causa-MCP server and the re-frame2-pair server (per [Tool-Pair.md](Tool-Pair.md)). These are dev-only tools that attach to the trace surface; they are not designed for, and not shippable to, production. The Causa preload artefact must not be on a production build's classpath; the re-frame2-pair server lives in its own dev-only artefact for the same reason.

### What IS available in production

> Cross-reference: see [Security.md §Production gates](Security.md#production-gates) for the framework-wide threat-model entry — the three always-on substrates below are the production-survivable surface; everything in [§What is NOT available in a default production CLJS build](#what-is-not-available-in-a-default-production-cljs-build) is dev-only by design (both for performance and for security — dev-side enrichments would otherwise leak to production consumers).

Six surfaces survive elision and are the canonical production-debugging fallbacks:

1. **The per-frame `:on-error` slot** (per [002-Frames §`:on-error`](002-Frames.md) and [§Error-handler policy](#error-handler-policy-on-error-per-frame)) — runs through a small always-on error-emit substrate (per rf2-hqbeh) that is NOT gated by `re-frame.interop/debug-enabled?`. When a registered event handler throws in CLJS prod, the runtime invokes the in-scope frame's `:on-error` policy fn with the structured error event (`:operation :rf.error/handler-exception`, `:op-type :error`, `:tags {:event-id :frame :exception …}`). The substrate covers the handler-exception path — the primary production-monitoring case (rf2-hqbeh validation criterion). The substrate does NOT carry dev-side enrichment: `:dispatch-id` correlation, `:rf.trace/trigger-handler` source-coord, and the retain-N ring buffer all remain trace-surface-only and continue to elide. Per Spec 009 §1052 policy-fn exceptions are caught inside the substrate so a buggy policy cannot break the cascade.
2. **The event-emit listener surface** (per rf2-rirbq, `register-event-emit-listener!` / `unregister-event-emit-listener!` — see [API.md §Event-emit](API.md#event-emit-always-on-production-survivable)) — runs through a small always-on event-emit substrate (parallel to the `:on-error` substrate in #1) that is NOT gated by `re-frame.interop/debug-enabled?`. The router fans out one record per processed event after the cascade settles. The record is intentionally tight — `{:event <vector> :event-id <kw> :frame <kw> :time <millis> :outcome :ok|:error :elapsed-ms <int>}` — enough discriminator for production event observability (event-id, frame, outcome, latency); not enough for causal reconstruction (`:dispatch-id` correlation, `:parent-dispatch-id`, source-coord ride the dev-only trace surface and elide with it). The `:event` vector is passed through `re-frame.elision/elide-wire-value` ONCE before fan-out with off-box defaults (large → `:rf.size/large-elided`; sensitive → `:rf/redacted`), so listeners can ship the wire payload to a hosted observability back-end (Datadog, Honeycomb, Sentry, …) without further shaping. Per-listener exceptions are caught inside the substrate so a buggy listener cannot break the cascade or block sibling listeners. Listener registration sites SHOULD use `^boolean re-frame.interop/debug-enabled?` as a belt-and-braces gate alongside the user's explicit config flag:

   ```clojure
   (when (and (= "production" (:env config))
              (not ^boolean re-frame.interop/debug-enabled?)
              (:api-key config))
     (rf/register-event-emit-listener!
       :datadog/forward
       (fn [event-record]
         (datadog/track event-record))))
   ```

   Catches the "accidentally deployed a dev bundle with prod config" bug class.
3. **The error-emit listener surface** (per rf2-bacs4, `register-error-emit-listener!` / `unregister-error-emit-listener!` — see [API.md §Error-emit](API.md#error-emit-always-on-production-survivable)) — sibling of #2, runs through the SAME always-on error-emit substrate as the per-frame `:on-error` slot (#1) but along an independent fan-out path. NOT gated by `re-frame.interop/debug-enabled?`. The router fans out one record per `:rf.error/*` event after the handler-exception path runs. The record is intentionally tight — `{:error <kw> :event <vector> :event-id <kw> :frame <kw> :time <millis> :exception <ex> :elapsed-ms <int>}` — enough discriminator for production error observability (failing event-id, frame, exception object, latency); not enough for causal reconstruction (`:dispatch-id`, source-coord, `:rf.trace/trigger-handler` ride the dev-only trace surface and elide with it). The `:event` vector is passed through `re-frame.elision/elide-wire-value` ONCE before fan-out with off-box defaults (large → `:rf.size/large-elided`; sensitive → `:rf/redacted`). The two paths from the substrate are mutually isolated: a buggy listener cannot block the per-frame `:on-error` policy fn, and a buggy policy fn cannot block listeners. Listener registration sites SHOULD use `^boolean re-frame.interop/debug-enabled?` as a belt-and-braces gate alongside the user's explicit config flag, symmetric with the event-emit pattern in #2:

   ```clojure
   (when (and (= "production" (:env config))
              (not ^boolean re-frame.interop/debug-enabled?)
              (:dsn config))
     (rf/register-error-emit-listener!
       :sentry/forward
       (fn [error-record]
         (sentry/capture-exception (:exception error-record)
                                   {:tags {:event-id (:event-id error-record)
                                           :frame    (:frame error-record)}}))))
   ```

   Use #2 and #3 together to route both events and errors through one hosted observability back-end without preserving the full trace surface.
4. **The Performance API channel** (per [§Performance instrumentation](#performance-instrumentation)) — gated on the independent `re-frame.performance/enabled?` `goog-define`, default off. A production build that wants timing observability flips `{:closure-defines {re-frame.performance/enabled? true}}`; the bracket sites at the four hot paths (`:event`, `:sub`, `:fx`, `:render`) emit User-Timing measure entries that any `PerformanceObserver` — including the host APM's — reads via `performance.getEntriesByType('measure')`. This is the production observability surface re-frame2 ships and supports.
5. **The SSR error-projector boundary** (per [011 §Server error projection](011-SSR.md#server-error-projection)) — on the server (JVM/SSR), `re-frame.interop/debug-enabled?` is hardcoded `true` (per [§JVM builds](#jvm-builds)), so the trace surface is live. The runtime emits structured `:rf.error/*` traces, the registered error projector consumes them, and the locked `:rf/public-error` shape is written to the HTTP response. Apps with an SSR tier get the full trace + projection pipeline server-side independent of the client-side bundle's elision.
6. **Native browser machinery** — uncaught exceptions still reach `window.onerror` / `window.onunhandledrejection`. A re-frame2 event handler that throws in production still surfaces there; what's missing is the structured `:rf.error/handler-exception` shape, the `:dispatch-id` correlation, and the `:rf.trace/trigger-handler` coord — those rode the trace surface. Prefer the `:on-error` slot (#1) for structured access to the failing handler's id and the exception.

### Wiring an external error monitor (Sentry, Rollbar, Honeybadger, etc.)

The dev-side integration documented at [§Composition with libraries](#composition-with-libraries-sentry-honeybadger-etc) routes structured trace events into the monitor:

```clojure
;; Dev: full structured trace, captured before the runtime's default recovery.
(rf/register-trace-listener!
 :sentry/forward
 (fn [trace-event]
   (when (= :error (:op-type trace-event))
     (sentry/capture-event
      {:level      "error"
       :message    (-> trace-event :tags :reason)
       :tags       {:rf-operation  (name (:operation trace-event))
                    :rf-frame      (some-> trace-event :tags :frame name)
                    :rf-dispatch   (some-> trace-event :tags :dispatch-id str)
                    :rf-failing-id (some-> trace-event :tags :failing-id str)}
       :extra      (:tags trace-event)
       :fingerprint [(name (:operation trace-event))
                     (str (-> trace-event :tags :failing-id))]}))))
```

In a production CLJS build with `goog.DEBUG=false`, the `register-trace-listener!` call and its body sit under the `(when ^boolean re-frame.interop/debug-enabled? …)` user-side guard (per [§User-side listener registration](#user-side-listener-registration)) and elide entirely. The trace-listener fan-out (`:dispatch-id` correlation, `:rf.trace/trigger-handler` source-coord, the retain-N ring buffer) is dev-only. Three integration patterns survive elision:

- **Recommended for structured fields**: register the monitor through the per-frame `:on-error` slot (per [§Error-handler policy](#error-handler-policy-on-error-per-frame)). Per rf2-hqbeh the slot rides the always-on error-emit substrate, NOT the trace surface — registered policy fns fire under `:advanced` + `goog.DEBUG=false`. The policy fn receives the structured error event (`:operation :rf.error/handler-exception`, `:op-type :error`, `:tags {:event-id :frame :exception …}`), forwards to the monitor, and returns nil to delegate recovery to the runtime. This is the recommended production-monitor integration. The substrate covers the handler-exception path; dev-side enrichments (`:dispatch-id`, source-coord, retain-N) are not carried.
- **Native-SDK fallback**: install the monitor's native browser SDK at the top of the bundle (`Sentry.init({...})`). It captures `window.onerror`, `window.onunhandledrejection`, and any explicit `Sentry.captureException` call wherever the app already has error-boundary plumbing. The trade-off is loss of re-frame2's structured fields — the monitor sees the bare exception, not the cascade context. Use this when the app already has wider-scope error-boundary plumbing or when handler-exception coverage alone is insufficient.
- **Opt-in to keep the trace surface**: ship `:advanced` with `:closure-defines {goog.DEBUG true}`. The trace surface is preserved, the `register-trace-listener!` sample above runs, and the monitor receives full structured events including dev-side enrichments (`:dispatch-id`, `:rf.trace/trigger-handler`, the retain-N buffer). The cost is the trace machinery's bundle size (see [§Production-elision verification](#production-elision-verification) for the size delta — the control bundle is the reference measurement). This is the explicit escape hatch for apps where post-mortem fidelity outweighs bundle weight.

## Hot path in dev builds

Dev iteration matters; you don't want trace machinery to slow ordinary feedback loops. Two hot-path costs are present in dev:

1. **Trace-event allocation** — building the trace map per emit.
2. **Listener invocation** — invoking `register-trace-listener!` callbacks once per emitted event.

### Cheap-path discipline (dev builds only)

- **Listener registry is a single atom.** Reading it is one deref.
- **No string formatting or other expensive work** happens in framework emit code; tools format if they want to.
- **Listener invocation cost scales with listener count.** Zero registered listeners means zero per-emit dispatch overhead beyond the registry deref. The retain-N ring buffer always pushes (its append is `swap!` plus a slot check), so the floor is one map allocation, one buffer push, and one deref per emit.

## Performance instrumentation

The trace stream above is dev-only — too noisy for prod, gated on `re-frame.interop/debug-enabled?` (an alias of `goog.DEBUG`). Many apps still want a *separate*, default-off, prod-friendly timing channel: one that surfaces in Chrome DevTools' Performance panel alongside React renders, network, and paint, and that consumers (the host's APM, a custom `PerformanceObserver`, an in-app perf overlay) can read via the standard browser [User Timing](https://developer.mozilla.org/en-US/docs/Web/API/Performance_API/User_timing) surface.

re-frame2 ships that channel through the browser's `performance.mark` / `performance.measure`, gated on a **second** compile-time constant — `re-frame.performance/enabled?` — that is independent of `goog.DEBUG`. The default is off; consumers opt in by flipping the `goog-define` via `:closure-defines`. Closure DCE then either keeps the bracket sites or elides them entirely; production binaries that don't ask for timing carry zero User-Timing instrumentation.

This is **distinct from** the trace surface above:

| Axis | Trace stream | Performance instrumentation |
|---|---|---|
| Compile-time gate | `re-frame.interop/debug-enabled?` (alias of `goog.DEBUG`) | `re-frame.performance/enabled?` |
| Default | on in dev (`goog.DEBUG=true`), off in prod | **off** in both (`enabled?=false`) |
| Consumer | `register-trace-listener!` listeners, the retain-N ring buffer, `register-epoch-listener!` | `performance.getEntriesByType('measure')`, `PerformanceObserver`, Chrome DevTools Performance |
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

The browser smoke at `tools/causa/testbeds/perf_counter/spec.cjs` (rf2-p8f2s — tool-owned perf testbed) complements the grep: it serves the perf-on bundle, drives a real dispatch through the +/- buttons, and reads `performance.getEntriesByType('measure')` to confirm at least one entry per bucket lands. A passing grep is necessary but not sufficient; the smoke proves the four call sites actually fire under a real cascade.

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
| `register-trace-listener!` / `unregister-trace-listener!` | Preserved |
| Synchronous, event-at-a-time delivery | Preserved |
| Trace event shape (`:id`, `:operation`, `:op-type`, `:time`, `:tags`) | Preserved exactly |
| `:op-type` discriminator vocabulary (`:event`, `:sub/run`, `:sub/create`, `:event/do-fx`, `:rf.machine/transition`, `:view/render`, `:fx`, `:warning`, `:error`, ...) | Preserved; new values additive |
| `:tags` for op-type-specific data (`:event-id`, `:event`, `:frame`, `:app-db-before`, `:app-db-after`, `:dispatch-id`, `:parent-dispatch-id`, `:origin`, ...) | Preserved |
| Hoisted top-level fields (`:source`, `:recovery`) | Preserved |
| `re-frame.interop/debug-enabled?` (alias of `goog.DEBUG`) | Preserved |
| Compile-time elision via `goog.DEBUG=false` + `:advanced` | Preserved |
| Public registrar query API (`registrations`/`handler-meta`/`frame-ids`/`frame-meta`/`get-frame-db`/`snapshot-of`/`sub-topology`/`sub-cache`) | See [002 §The public registrar query API](002-Frames.md#the-public-registrar-query-api) |
| Hot-reload notifications (`:rf.registry/handler-registered`, `:rf.registry/handler-cleared`, `:rf.registry/handler-replaced`, `:frame/created`, `:frame/destroyed`) | Trace events |

### Capabilities tools depend on

- **Multi-frame UI** — frame selector; per-frame trace slicing via `(get-in ev [:tags :frame])`; per-frame app-db via `(get-frame-db id)`.
- **Drain semantics in epochs** — multiple events drain into a single epoch (run-to-completion); per-cascade correlation rides on `:dispatch-id` / `:parent-dispatch-id` (per [§Dispatch correlation](#dispatch-correlation-dispatch-id--parent-dispatch-id)). The fully-assembled `:rf/epoch-record` (per [Spec-Schemas](Spec-Schemas.md#rfepoch-record)) provides the structured projection.
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
| `register-trace-listener!` / `unregister-trace-listener!` | ✓ | ✓ |
| `register-epoch-listener!` / `unregister-epoch-listener!` | ✓ | ✓ |
| Trace ring buffer (`trace-buffer`) | ✓ | ✓ |
| Hot-reload trace events | ✓ | ✓ |
| Performance API instrumentation (`rf:event:*` / `rf:sub:*` / `rf:fx:*` / `rf:render:*` measures) | ✗ | ✓ (default-off; see [§Performance instrumentation](#performance-instrumentation)) |
| Causa panel itself | ✗ | ✓ |
| re-frame-pair attachment | ✓ | ✓ |

Trace data is just data; both platforms emit it during dev. The Performance API bridge is browser-specific; everything else works headless.

## Handler-scope: the in-scope reading at emit time

Every handler-execution boundary the runtime crosses (the router's `process-event!` step, a sub recompute, an fx dispatcher, a cofx injector, a view render wrapper) publishes the same five-slot **handler-scope reading** to the trace stream so `emit!` / `emit-error!` can hoist the relevant pieces onto each emitted event. The reading travels through ONE dynamic Var — `re-frame.trace/*handler-scope*` — bound to a `HandlerScope` record with five slots (the [§Canonical slot set](#canonical-slot-set--the-stable-contract) below is the authoritative slot vocabulary; this table is the at-a-glance summary):

| Slot | Carries | Per |
|---|---|---|
| `:trigger-handler` | Registration coord of the in-scope handler — `{:kind :id :source-coord {...}}` or nil when no source-coord is stamped. Hoisted as the top-level `:rf.trace/trigger-handler` field on every emit. | rf2-3nn8 (error path) / rf2-lf84g (success path) |
| `:call-site` | Compile-time invocation coord of the surface reached through its macro form (`dispatch`, `dispatch-sync`, `subscribe`, `inject-cofx`) — `{:ns :file :line :column}` or nil for fn-form callers. Hoisted as `:rf.trace/call-site` on every emit (success and error). | rf2-ts1a (error-path landing) / rf2-twt7m (widened to success path) |
| `:dispatch-id` | Cascade-wide correlation id — allocated once on entry to the drain (`router.cljc`'s `process-event!`) and merged into `:tags :dispatch-id` of every event emitted inside the cascade. | rf2-g6ih4 |
| `:sensitive?` | Boolean. True when the router computed a schema-derived sensitive-path overlap for the in-scope handler (the handler-meta annotation has been removed — rf2-hjs2d). Emitted events get a top-level `:sensitive? true` stamp; absent reads as false (per [§Privacy / sensitive data in traces](#privacy--sensitive-data-in-traces)). | rf2-isdwf |
| `:no-emit?` | Boolean. True when the in-scope handler's registration meta carries `:rf.trace/no-emit? true`. `emit!` / `emit-error!` short-circuit (no envelope allocation, no listener fan-out) when bound true. | rf2-qsjda |

### Composition

The innermost handler-scope binding wins for the meta-derived slots (`:trigger-handler` / `:sensitive?` / `:no-emit?`). The `:call-site` and `:dispatch-id` slots are **inherited** from the parent scope unless the new scope explicitly overrides them — call-site originates at macro expansion time and rides through nested scopes; dispatch-id is allocated once per cascade and survives the handler-chain → sub recompute → fx → cofx descent. The constructor and binding macros in `re-frame.trace` (`with-handler-scope`, `with-call-site`, `with-dispatch-id+call-site`) handle inheritance automatically.

For `:rf.fx/handled` specifically: the runtime rebinds `:trigger-handler` to the **fx handler's** own registration meta around the fx body's invocation and the success-path emit that follows — consumer tools jump to the `reg-fx` site, not the enclosing event handler. Reserved fx-ids (`:dispatch`, `:dispatch-later`, `:rf.fx/reg-flow`, `:rf.fx/clear-flow`) have no registration site of their own; their `:rf.fx/handled` traces carry the enclosing event handler's coord (the outer binding).

### Production elision

The whole trace surface compiles out via the outer `(when interop/debug-enabled? ...)` gate in `emit!` / `emit-error!`, so all `*handler-scope*` reads are dead code under `:advanced` + `goog.DEBUG=false`. The `:trigger-handler` slot is **not separately elided** in error traces (which survive into production via `error-emit/dispatch-on-error!`).

### Canonical slot set — the stable contract

The `HandlerScope` record's slot set is a **stable contract** consumed at every emit site (`build-event` in `re-frame.trace`) and at every binding site (the router's `process-event!`, fx / cofx dispatchers, sub recompute wrappers, view render wrappers, plus surface macros `dispatch` / `dispatch-sync` / `subscribe` / `inject-cofx`). Downstream tools (Story, Causa, re-frame2-pair, 10x) read the hoisted slots off emitted events; the table below is the authoritative slot vocabulary they may rely on.

| Slot | Value shape | Origin | Inheritance |
|---|---|---|---|
| `:trigger-handler` | `{:kind :id :source-coord {:ns :file :line :column}}` or nil. `:kind` is one of `#{:event :sub :fx :cofx :view :machine :flow :route :app-schema :error-projector}`; `:source-coord` is whatever the registrar slot's meta carried (omitted for programmatic registrations). | Read off the in-scope handler's registration meta by `handler-scope-from-meta` at scope-bind time. | Innermost wins (meta-derived). |
| `:call-site` | `{:ns :file :line :column}` or nil. Macro-expansion coord stamped by the surface form (`dispatch`, `dispatch-sync`, `subscribe`, `inject-cofx`). Nil for fn-form callers. | Stamped by the surface macro via `with-call-site` or `with-dispatch-id+call-site`. | Inherited from parent scope unless the new scope explicitly overrides. |
| `:dispatch-id` | Opaque scalar (process-monotonic counter, UUID, or any value with the [§Dispatch correlation](#dispatch-correlation-dispatch-id--parent-dispatch-id) uniqueness contract). Nil outside any in-flight cascade. | Allocated once at queue time by `router.cljc`'s `enqueue!`; published into the scope by `with-dispatch-id+call-site` on entry to `process-event!`. | Inherited from parent scope unless the new scope explicitly overrides. |
| `:sensitive?` | Boolean. True iff the router computed a schema-derived sensitive-path overlap for the in-scope handler (see [§Privacy / sensitive data in traces](#privacy--sensitive-data-in-traces)). The legacy handler-meta `:sensitive?` annotation has been removed (rf2-hjs2d) in favour of path-marked classification. | Computed in the router's `prepare-handler-ctx` and threaded onto the scope-meta as `:rf/sensitive?` for `handler-scope-from-meta` to lift into the scope's `:sensitive?` slot. | Innermost wins (scope-derived). |
| `:no-emit?` | Boolean. True iff the in-scope handler's registration meta carries `:rf.trace/no-emit? true`. | Read off the in-scope handler's registration meta by `handler-scope-from-meta` at scope-bind time. | Innermost wins (meta-derived). |

Slot values are nil when unbound. Consumers reading a slot off an event MUST treat absent and nil identically (nil-safe access).

#### Emit-side hoist contract — which slot rides which trace

`build-event` (in `re-frame.trace`) reads `*handler-scope*` once per emit and lifts the slots onto the trace envelope according to a fixed per-slot contract. The table below pins the mapping; per-slot variations live in [§The error event shape](#the-error-event-shape) and [§Privacy / sensitive data in traces](#privacy--sensitive-data-in-traces) and are summarised here:

| Slot | Hoisted as | When | Notes |
|---|---|---|---|
| `:trigger-handler` | top-level `:rf.trace/trigger-handler` | every emit (success and error) when bound | Omitted entirely when unbound (no placeholder data). Per [§`:rf.trace/trigger-handler`](#rftracetrigger-handler--naming-the-in-scope-handler). |
| `:call-site` | top-level `:rf.trace/call-site` | every emit (success and error) when bound | Per rf2-twt7m the hoist widened from error-only to all emits — the Event lens and any consumer rendering jump-to-source on success-path events (`:event/dispatched`, `:event/do-fx`, `:rf.fx/handled`) needs the dispatch-site coord on the cascade entry, not just on errors. Omitted entirely when unbound. Per [§`:rf.trace/call-site`](#rftracecall-site--naming-the-invocation-line-rf2-ts1a). |
| `:dispatch-id` | `:tags :dispatch-id` | every emit when bound and `:tags` does not already supply one | Caller-supplied `:tags :dispatch-id` wins. Per [§Dispatch correlation](#dispatch-correlation-dispatch-id--parent-dispatch-id). |
| `:sensitive?` | top-level `:sensitive? true` | every emit when scope is sensitive and `:tags :sensitive?` does not supply its own reading | Caller-supplied `:tags :sensitive?` wins (queue-time `:event/dispatched` computes its own reading before scope is bound). Absent reads as false. Per [§Privacy / sensitive data in traces](#privacy--sensitive-data-in-traces). |
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

The reading was originally carried by five sibling dynamic Vars (`*current-trigger-handler*`, `*current-call-site*`, `*current-dispatch-id*`, `*current-sensitive?*`, `*current-no-emit?*`) bound side-by-side at every handler-scope site. The five-Var arrangement landed across rf2-3nn8 (trigger-handler / error path), rf2-lf84g (trigger-handler / success path), rf2-ts1a (call-site), rf2-g6ih4 (dispatch-id), rf2-isdwf (sensitive?), and rf2-qsjda (no-emit?). Per rf2-ryri7 they were consolidated into one `HandlerScope` record bound to one Var: one binding-frame allocation per scope instead of five, one Var to mock in tests, one record-field edit when a sixth concern lands.

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
   {:ns sym? :file string?                       ;; macro form (rf2-ts1a). Dev-only — elided under
    :line int? :column int?}                     ;; :advanced + goog.DEBUG=false.
 :tags      {:category    :rf.error/<category>   ;; same as :operation, for consumer convenience
             :failing-id  any                    ;; the registered id that failed (event id, fx id, sub id, view id, etc.)
             :reason      string                 ;; one-sentence human description
             :frame       keyword?               ;; (when known) the frame the failure happened in
             ...}}                               ;; category-specific keys
```

`:source` and `:recovery` are top-level fields hoisted out of `:tags` by the runtime; both are present on every error event. `:frame` rides under `:tags` (every emit site that knows the frame supplies it there). The `:tags` payload's category-specific keys are documented per category below, and each category has a registered Malli schema so consumers can validate / branch on the payload safely.

#### `:rf.trace/trigger-handler` — naming the in-scope handler

The optional top-level `:rf.trace/trigger-handler` slot names the handler whose execution produced the trace event and carries its registration-site source-coord. Tools (Causa, pair, IDE jump-to-source) render click-to-jump links from this field — given a trace event, the user lands on the line of code that defined the responsible handler.

The slot rides on **every** trace event emitted while a handler is in scope, not just errors. Success-path traces — `:rf.fx/handled`, `:rf.machine/transition`, `:event/db-changed`, `:event/do-fx`, ... — carry the in-scope handler's registration coord too. Originally introduced (rf2-3nn8) for `:rf.error/*` only; widened (rf2-lf84g) so consumer tools can render jump-to-source links from any trace event in a cascade, not just errors. The error-path emit shape is unchanged — same field name, same nested map, same top-level placement.

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
| At registration-time emits outside any handler (`:rf.registry/handler-registered`, `:frame/created`) | No | — |

For `:rf.fx/handled` specifically: the slot carries the **fx handler's** own registration coord (not the enclosing event handler that produced the `:fx` vector). The runtime rebinds the handler-scope's `:trigger-handler` slot (per [§Handler-scope](#handler-scope-the-in-scope-reading-at-emit-time)) to the fx handler's meta around the fx body's invocation and the success-path emit that follows, so consumer tools jump to the `reg-fx` site — where the fx's logic actually lives — not the event handler upstream. Reserved fx-ids (`:dispatch`, `:dispatch-later`, `:rf.fx/reg-flow`, `:rf.fx/clear-flow`) have no registration site of their own; their `:rf.fx/handled` traces carry the enclosing event handler's coord (the outer binding).

The `:source-coord` payload is whatever the registrar slot's metadata holds. Macro-driven registration (`reg-event-*`, `reg-sub`, `reg-fx`, `reg-cofx`, `reg-view`, `reg-machine`, `reg-flow`, `reg-route`, `reg-app-schema`, `reg-error-projector`) stamps `:ns` / `:file` / `:line` / `:column` flat onto the meta map at compile time; the trigger-handler builder picks those keys off and re-nests them under `:source-coord`. Programmatic / REPL registrations bypass the macro path and carry no coord — in that case the entire `:rf.trace/trigger-handler` slot is omitted rather than populated with placeholder data (better no field than poison-data).

Production elision: the slot is **NOT separately elided**. The trace surface as a whole is gated by `re-frame.interop/debug-enabled?` per [§Production builds](#production-builds-zero-overhead-zero-code) — when a trace event is emitted at all, the trigger-handler field rides along on it when bound. There is no second gate that selectively drops the field while keeping the rest of the event. Apps that keep the trace surface in production (rare; opt in by setting `goog.DEBUG=true` on the `:advanced` build) get the trigger-handler coord along with every emitted event. Apps using the default `goog.DEBUG=false` `:advanced` build get neither the field nor the surrounding trace surface — the entire `(when interop/debug-enabled? ...)` branch DCEs.

Consumer access: read `(:rf.trace/trigger-handler event)` for the map, `(get-in event [:rf.trace/trigger-handler :source-coord])` for the coord, `(get-in event [:rf.trace/trigger-handler :id])` for the handler's id. No new namespace is required to read the slot.

#### `:rf.trace/call-site` — naming the invocation line (rf2-ts1a)

The optional top-level `:rf.trace/call-site` slot is a **sibling** of `:rf.trace/trigger-handler` (not nested) and names the **invocation line** of the user-facing surface that triggered the trace event — the `(rf/dispatch [:bad-event])` line, the `(rf/subscribe [:bad-sub])` line, the `(rf/inject-cofx :missing)` line, the `(rf/dispatch-sync [:throws])` line. Where trigger-handler answers *"where is the failing handler defined?"*, call-site answers *"where is the failing handler called?"* Tools render two clickable links per error: registration-site jump (trigger-handler) and invocation-site jump (call-site).

Shape (flat map, mirrors `:source-coord` under `:rf.trace/trigger-handler`):

```clojure
{:ns     <sym>     ;; the calling namespace
 :file   <string>  ;; the source file, per rf2-mdjp `:file` resolution
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

The mechanism is "compile-time map + handler-scope bind + emit read." The macro produces a literal map at compile time; the runtime publishes it on the `:call-site` slot of `re-frame.trace/*handler-scope*` around the underlying `*`-fn call (or threads the value through the dispatch envelope so `process-event!` binds it for the handler chain, per [§Handler-scope](#handler-scope-the-in-scope-reading-at-emit-time)); `build-event` reads the slot and hoists it onto every emitted event (success and error) when bound. The queue-time `:event/dispatched` emit additionally wraps its `trace/emit!` in a `with-call-site` binding sourced from the envelope's `:call-site` slot, so the enqueue trace carries the dispatch-site coord even though `process-event!`'s cascade-wide binding hasn't fired yet. No new namespace or registry; consumer access is `(:rf.trace/call-site event)`.

Per rf2-twt7m the hoist widened from error-only to every emit (success and error). The Event lens redesign (rf2-zh2qc) and any consumer rendering jump-to-source on success-path events (`:event/dispatched`, `:event/do-fx`, `:rf.fx/handled`, `:event/db-changed`, `:sub/run`, `:rf.machine/transition`) needs the dispatch-site coord on the cascade entry, not just on errors. The semantics match the trigger-handler widening (rf2-lf84g): better one consistent rule than two paths to remember.

#### `:rf/default?` — framework-auto-wrapped interceptor flag (rf2-twt7m)

`reg-event-db` / `reg-event-fx` / `reg-event-ctx` each wrap the user's handler into a kind-appropriate interceptor (`:rf/db-handler` / `:rf/fx-handler` / `:rf/ctx-handler`) before appending it to the user-supplied `:interceptors` chain. The wrapper appears in `(rf/handler-meta :event id) :interceptors` alongside the user's own interceptors; consumer tools (Causa, the Event lens, IDE inspectors) frequently want to surface ONLY the user's chain — the framework auto-wrapper is implementation detail, not user-authored configuration worth showing.

Per rf2-twt7m the auto-wrapper carries `:rf/default? true` on its interceptor map. Self-describing — tools filter without a hardcoded id allowlist:

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
| `:rf.error/handler-exception` | `:error` | An event handler threw. | `:no-recovery` — the exception propagates; the cascade halts | `:event` (vector), `:handler-id`, `:exception-message`, `:exception-data?` |
| `:rf.error/machine-action-exception` | `:error` | A machine action body threw during a transition (per [005 §Errors](005-StateMachines.md#errors) and [Cross-Spec-Interactions §11](Cross-Spec-Interactions.md#11-machine-action-throws)). Distinct from `:rf.error/handler-exception`: the machine layer catches the throw and emits the machine-scoped category instead, so consumers see exactly one error per failure with full machine context | `:no-recovery` — the machine cascade halts atomically: the snapshot does not commit (pre-action `[:rf/machines <id>]` slice is preserved), accumulated `:fx` from earlier slots in the same Level-2 cascade is dropped, and the `:always` microstep does not fire on the failed cascade | `:machine-id`, `:action-id`, `:state-path`, `:transition`, `:event`, `:exception-message`, `:exception-data?` |
| `:rf.error/fx-handler-exception` | `:error` | A registered fx threw during effect resolution | `:no-recovery` — the fx is skipped; cascade continues if other fx independent | `:fx-id`, `:fx-args`, `:exception-message` |
| `:rf.error/sub-exception` | `:error` | A subscription's computation threw | `:replaced-with-default` — the sub returns `nil`; views see no value | `:sub-query`, `:sub-id`, `:exception-message` |
| `:rf.error/no-such-sub` | `:error` | A subscription's `:<-` input refers to an unregistered sub | `:replaced-with-default` — the unresolved input is substituted with `nil`; the sub's body still runs | `:sub-id`, `:unresolved-input`, `:resolved-inputs` |
| `:rf.error/schema-validation-failure` | `:error` | A `:schema`-validated value failed validation | `:no-recovery` — hard-fail to surface bugs early. Production builds elide the validation entirely (per [Spec 000 §Contract C-000.35](000-Vision.md)), so this row applies only in dev | `:where` (`:event`/`:sub-return`/`:app-db`/`:fx-args`/...), `:path`, `:value`, `:explain` (Malli explanation map) |
| `:rf.schema/violation` | `:warning` | A registered `app-db` path schema changed during hot-reload (file save re-evaluated `reg-app-schema` with a different schema) and the current `app-db` value at that path no longer validates against the **new** schema. Surfaced so dev panels highlight the stale slice; the live app continues running. Distinct from `:rf.error/schema-validation-failure` (which fires on dispatch-time validation at boundaries); this category fires at the hot-reload edge against pre-existing state. Per [010 §Schema migration on hot-reload](010-Schemas.md#schema-migration-on-hot-reload). Escalation to `:on-error` (per [§Error-handler policy](#error-handler-policy-on-error-per-frame)) is out of scope for this category — default is log-and-continue. Renamed from `:rf.spec/violation` at rf2-ieu0i; see [MIGRATION §M-54](../migration/from-re-frame-v1/README.md#m-54-schema-vocabulary-unification--spec--schema-rf2-ieu0i) | `:logged-and-skipped` — the warning fires; `app-db` is **not** auto-cleared or rewound; the live app continues | `:path` (the `reg-app-schema` registration path), `:pre-reload-schema` (the previously-registered schema form), `:post-reload-schema` (the newly-registered schema form), `:mismatching-value` (the current `app-db` value at `:path` that fails the new schema), `:frame` |
| `:rf.error/drain-depth-exceeded` | `:error` | The run-to-completion drain hit its depth limit | `:no-recovery` — always indicates a bug; halt the cascade | `:depth`, `:queue-size`, `:last-event` |
| `:rf.error/no-such-handler` | `:error` | A registrar-shaped lookup missed. Covers three distinct failure modes, discriminated by the **`:kind` tag** (mandatory on every emit): (1) `:kind :event` — a `dispatch` / `dispatch-sync` arrived with no registered event handler (emitted by router.cljc); (2) `:kind :frame` — a Tool-Pair surface (`restore-epoch`, `reset-frame-db!`) addressed a frame-id that is not in the frame registrar (emitted by epoch.cljc; see [Tool-Pair §Surface behaviour against destroyed frames](Tool-Pair.md#surface-behaviour-against-destroyed-frames)); (3) `:kind :route` — `:rf.route/handle-url-change` (or a `route-url` caller) saw a URL that matched no registered `:path` pattern (emitted by routing.cljc; see [012 §Route-not-found](012-Routing.md#route-not-found--rfroutenot-found-canonical) and the default-projector mapping at [011 §Default projector](011-SSR.md)). Consumers route on `:kind` for per-mode handling; tools that want a single "registrar miss" filter match the operation keyword alone | `:replaced-with-default` — no-op; emit the trace | `:kind` (one of `:event`, `:frame`, `:route` — mandatory), plus mode-specific keys: `:event` + `:event-id` + `:frame` (`:kind :event`); `:frame` (`:kind :frame`); `:url` + `:frame` (`:kind :route`) |
| `:rf.error/dispatch-sync-in-handler` | `:error` | `dispatch-sync` was called from inside an event handler's interceptor pipeline (use `:fx [[:dispatch event]]` instead — see [002 §dispatch-sync](002-Frames.md#dispatch-sync)) | `:no-recovery` — the call is rejected. Use `:fx [[:dispatch event]]` in the effect map | `:event`, `:enclosing-event`, `:enclosing-frame` |
| `:rf.error/effect-map-shape` | `:error` | A `reg-event-fx` handler returned a top-level effect-map key other than `:db` / `:fx` (per [MIGRATION §M-8](../migration/from-re-frame-v1/README.md#m-8-effect-map-keys-consolidated--only-db-and-fx-at-the-top-level)). The runtime drops the offending key and emits one trace per offending key; legal `:db` / `:fx` keys still apply | `:logged-and-skipped` — the offending top-level key is dropped; `:db` and `:fx` still apply. One trace per offending key | `:failing-id` (event-id), `:event-id`, `:event` (vector), `:offending-key`, `:value`, `:reason` |
| `:rf.error/effect-handler-bad-return` | `:error` | A `reg-event-fx` handler returned a value that is neither a map nor `nil` (e.g. a vector, number, string, keyword — typically a typo or thinko). Without a map the runtime cannot extract `:db` / `:fx` and cannot guess the handler's intent, so the dispatch is treated as a no-op. `nil` remains the documented legal no-op and does not trigger this trace. Per rf2-k3bj. Emitted by `events.cljc`'s `fx-handler->interceptor` | `:no-recovery` — the offending return is dropped; the dispatch is treated as a no-op | `:event-id` (first of the event vector, when vector-shaped), `:event` (vector), `:returned` (the offending value), `:returned-type` (the runtime type), `:reason` |
| `:rf.error/bad-on-error-return` | `:error` | A frame's `:on-error` policy fn returned a value that did not conform to the return-map contract (per [§Error-handler policy](#error-handler-policy-on-error-per-frame)) — an unrecognised `:recovery` keyword (e.g. `:retried`), a malformed `:replacement` for the failing category (wrong shape, or a value supplied for a category with no substitutable slot), or any other contract violation. Per rf2-ciy | `:logged-and-skipped` — the policy's return is discarded; the runtime falls back to the original error's documented per-category recovery | `:original` (the input error-event's `:operation`), `:received` (the offending return value), `:reason` (one of `"unrecognised :recovery"`, `"expected an effect-map"`, `"category has no substitutable value"`, …), `:frame` |
| `:rf.error/on-error-policy-exception` | `:error` | A frame's `:on-error` policy fn itself threw while processing an error event. The runtime does NOT recursively invoke the policy on its own exception — that would risk unbounded recursion. Instead, the policy's exception is captured and the runtime falls back to the original error's documented per-category recovery. Per rf2-ciy | `:no-recovery` — the policy's exception is logged; the runtime applies the category-default recovery to the original error | `:original` (the input error-event's `:operation`), `:exception-message`, `:exception-data?`, `:frame` |
| `:rf.error/override-fallthrough` | `:error` | An override was specified but no matching id existed | `:replaced-with-default` — use the registered fx as if no override existed | `:overrides-map`, `:looked-up-id` |
| `:rf.fx/handled` | `:fx` | An fx was successfully dispatched (the runtime reached the fx and either ran the registered handler without exception or completed the reserved-fx-id action). Emitted by `re-frame.fx/handle-one-fx` on the success path so the `:rf/epoch-record` `:effects` projection captures one entry per dispatched fx (per [Spec-Schemas §`:rf/epoch-record`](Spec-Schemas.md#rfepoch-record)) | n/a — success-path trace, not an error/warning | `:fx-id`, `:fx-args`, `:frame` |
| `:rf.fx/skipped-on-platform` | `:warning` | An fx was skipped because its `:platforms` excluded the active platform (per [011](011-SSR.md)) | `:skipped` — documented; not really an error | `:fx-id`, `:fx-args`, `:platform`, `:registered-platforms` |
| `:rf.cofx/skipped-on-platform` | `:warning` | A cofx injection was skipped because its `:platforms` excluded the active platform (per [011](011-SSR.md)). Mirrors `:rf.fx/skipped-on-platform`; the cofx's handler-fn is NOT invoked and no value is injected into `:coeffects`. Emitted by `re-frame.cofx/inject-cofx` after registry lookup succeeds but the platform predicate rejects | `:skipped` — the cofx's injection is skipped; the event handler still runs | `:cofx-id`, `:cofx-value` (only when the 2-arity `inject-cofx` supplied a per-call value), `:frame`, `:platform`, `:registered-platforms` |
| `:rf.ssr/hydration-mismatch` | `:warning` | First client render diverges from server-supplied render-tree, OR the client-computed head model differs from the server-supplied head. The `:failing-id` discriminator routes the two cases (`:rf/hydrate` for the body, `:rf.ssr/head-mismatch` for the head). Per [011 §Hydration-mismatch detection](011-SSR.md#hydration-mismatch-detection) and [011 §Mismatch detection — head](011-SSR.md#mismatch-detection--head) | `:warned-and-replaced` — body: re-render client-side; the server's HTML is replaced. Head: client renders its head; server's is replaced | `:server-hash`, `:client-hash`, `:failing-id`, `:first-diff-path?` (body), `:head-id` (head) |
| `:rf.ssr/version-mismatch` | `:warning` | The hydration payload's `:rf/version` differs from the runtime's. Emitted by the `:rf.ssr/check-version` fx dispatched from the reference `:rf/hydrate` handler. The handler still applies — degraded-but-running is the locked posture. Per [011 §The `:rf/hydrate` event](011-SSR.md#the-rfhydrate-event) and rf2-69ad2 | `:warned-and-replaced` — the trace fires; hydration proceeds with the server-supplied app-db (the runtime does not abort hydration on version drift) | `:expected` (server-supplied value), `:actual` (client-side runtime value) |
| `:rf.ssr/schema-digest-mismatch` | `:warning` | The hydration payload's `:rf/schema-digest` differs from the digest of the client's currently-registered `app-schema` set. Emitted by the `:rf.ssr/check-schema-digest` fx dispatched from the reference `:rf/hydrate` handler. Useful for catching deploy drift where server and client bundles were built against different schema sets. Per [011 §The `:rf/hydrate` event](011-SSR.md#the-rfhydrate-event) and rf2-69ad2 | `:warned-and-replaced` — the trace fires; hydration proceeds with the server-supplied app-db | `:expected` (server-supplied digest), `:actual` (client-computed digest) |
| `:rf.ssr/compatibility-check-skipped` | `:warning` | A compatibility-check fx (`:rf.ssr/check-version` or `:rf.ssr/check-schema-digest`) fired but no actual-value hook (`:rf2/runtime-version` for version, `:schemas/app-schemas-digest` for schema-digest) is registered, so the comparison cannot be made. The fxs never throw — degraded-but-running is the locked posture. Per [011 §The `:rf/hydrate` event](011-SSR.md#the-rfhydrate-event) and rf2-69ad2 | `:logged-and-skipped` — the comparison is no-opped; hydration proceeds | `:check` (the calling fx-id, e.g. `:rf.ssr/check-version`), `:reason` (why no actual value could be resolved) |
| `:rf.warning/plain-fn-under-non-default-frame-once` | `:warning` | A plain (non-`reg-view`) Reagent fn rendered under a non-default frame; routed to `:rf/default`. Emitted at most once per `(component-id, non-default-frame-id)` pair — see [004 §Plain Reagent fns](004-Views.md). (The non-`-once` keyword `:rf.warning/plain-fn-under-non-default-frame` appears in `:rf.warning/<category>` examples but is not itself emitted — the `-once`-suffixed form is the actual runtime category) | `:warned-and-replaced` — the render proceeds, routed to `:rf/default` | `:fn-name`, `:rendered-under`, `:routed-to` |
| `:rf.warning/dispatch-from-async-callback-fell-through-to-default` | `:warning` | A `dispatch` resolved to `:rf/default` purely because the resolution chain fell through (no `:frame` opt supplied, dynamic `*current-frame*` unbound, adapter React-context value unresolvable) AND no handler for that event-id exists on `:rf/default`. The canonical trigger is a `dispatch` from an async callback (`setTimeout`, `addEventListener`, `requestAnimationFrame`, `Promise.then`) attached inside a view body — the surrounding frame-context binding does not survive the async escape (per [002 §Dispatches issued from inside a handler body](002-Frames.md#dispatches-issued-from-inside-a-handler-body)). Suppressed in single-frame apps (only `:rf/default` registered) — the footgun requires at least one non-default sibling frame. Emitted alongside the existing `:rf.error/no-such-handler` error; the warning carries the specific diagnostic with the recommended fixes. No suppression cache — the warning fires every time the conditions match. Per rf2-o8m0 | `:no-recovery` — the warning is purely diagnostic; the existing `:rf.error/no-such-handler` error fires alongside and carries the canonical `:replaced-with-default` recovery for the dispatch itself | `:event` (the dispatched event vector), `:event-id` (first of the vector), `:routed-to` (`:rf/default`), `:detected-at` (wall-clock ms), `:reason` |
| `:rf.warning/cross-frame-dispatch-sync-during-drain` | `:warning` | A `dispatch-sync!` was issued against a target frame while a *different* frame is mid-drain (same-frame reentry is already rejected as `:rf.error/dispatch-sync-in-handler`). The cross-frame case is not rejected — frames are independent state machines per [002 §Run-to-completion §Rules rule 1](002-Frames.md#rules) — but the cascades interleave (the target frame drains to settled while the caller's frame is still in flight, then the caller continues), which is rarely the caller's intent. Surfaced for observability tools; the dispatch proceeds. Per [002 §Cross-frame `dispatch-sync`](002-Frames.md#cross-frame-dispatch-sync-during-a-sibling-drain-warns-but-proceeds) and rf2-fp97 | `:no-recovery` — the dispatch proceeds; the warning is purely diagnostic. Frames are independent state machines so the cross-frame cascade is not a contract violation, but the interleaved ordering is rarely intentional | `:caller-frame` (the frame read from `*current-frame*`, or `:rf/none` when unbound), `:target-frame` (the `dispatch-sync!`'s target), `:other-frame` (an arbitrary mid-drain sibling — typically the caller's frame), `:event` (the dispatched event vector), `:reason` |
| `:rf.warning/no-clock-configured` | `:warning` | A timing-sensitive substrate feature (e.g. state-machine `:after` per [005 §Delayed `:after` transitions](005-StateMachines.md#delayed-after-transitions)) was exercised on a host whose `re-frame.interop` clock primitives (`now-ms` / `schedule-after!` / `cancel-scheduled!`) weren't wired up. The runtime falls back to the host-native clock if available; this advisory surfaces so tests / agents can spot the missing wiring | `:warned-and-replaced` — fall back to the host-native clock | `:feature` (e.g. `:rf.machine/after`), `:fallback` (the host-native clock used) |
| `:rf.error/duplicate-url-binding` | `:error` | A second frame attempted `:url-bound? true` while another already owns the URL. Per [012 §Multi-frame routing](012-Routing.md#multi-frame-routing) | `:no-recovery` — the second binding is rejected; the existing URL-owning frame is unchanged | `:existing-frame`, `:offending-frame` |
| `:rf.error/system-id-collision` | `:error` | A spawn whose `:system-id` was already bound in the per-frame `[:rf/system-ids]` reverse index displaced the previous binding. Last-write-wins, matching `reg-event-fx` re-registration semantics. Per [005 §Named addressing via `:system-id`](005-StateMachines.md#named-addressing-via-system-id) and rf2-suue | `:warned-and-replaced` — the previous binding is displaced; the new gensym wins | `:frame`, `:system-id`, `:existing-machine` (the displaced gensym'd id), `:rebound-to` (the new gensym'd id) |
| `:rf.warning/multiple-status-set` | `:warning` | Two or more `:rf.server/set-status` calls in the same request drain. Last-write-wins; advisory for finding the conflicting handlers. Per [011 §Multiple-status policy](011-SSR.md#multiple-status-policy) | `:warned-and-replaced` — last-write wins; advisory only | `:writes` (vector of `{:status :handler-id :event}` per write), `:final-status` |
| `:rf.warning/multiple-redirects` | `:warning` | Two or more `:rf.server/redirect` calls in the same request drain. Last-write-wins. Per [011 §Redirect precedence](011-SSR.md#redirect-precedence) | `:warned-and-replaced` — last-write wins; advisory only | `:writes` (vector), `:final-redirect` |
| `:rf.warning/interceptors-in-metadata-map` | `:warning` | A `reg-event-*` registration carried `:interceptors` inside its metadata-map; the chain is silently dropped. Per [Conventions §`:interceptors` is positional, not metadata](Conventions.md#interceptors-is-positional-not-metadata-reg-event-) and rf2-bbea | `:ignored` — the mis-placed `:interceptors` chain is dropped; registration completes with no positional interceptors | `:reg-fn` (the fn's name as a string), `:id`, `:offending-keys`, `:reason` |
| `:rf.warning/missing-doc` | `:warning` | A `reg-*` registration's metadata-map carried no `:doc` (or `:doc nil`, or `:doc ""`). The registration completes; the warning is the dev-time nudge toward documented handlers. Emitted at most once per `(kind, id)` pair within a runtime process (suppression cache resets on frame destroy, matching the other one-shot warnings). Production builds elide the check entirely via `goog.DEBUG`. Per [001 §`:doc` is dev-warned when absent](001-Registration.md#doc-is-dev-warned-when-absent) and rf2-lhu1e | `:ignored` — the registration completes normally; the warning is purely diagnostic | `:kind` (one of the canonical registry kinds), `:id` (the registered id), `:source-coords` (the captured `:rf/source-coord-meta` sub-map, when available), `:reason` |
| `:rf.warning/registration-collision` | `:warning` | A `reg-*` re-registration assigned an existing id to a different fn (different source-coord pair, in CLJS reference) rather than a re-eval of the same source file. Last-write-wins by default; the warning surfaces the change so dev tools can flag accidental shadowing. Recommended on in dev. Per [001 §Re-registration of a different function — collision warning](001-Registration.md#re-registration-of-a-different-function--collision-warning) | `:warned-and-replaced` — the new fn replaces the existing slot (last-write-wins); the warning fires | `:kind`, `:id`, `:previous-coord`, `:new-coord` |
| `:rf.error/at-boundary-missing-schema` | `:error` | A `reg-event-*` call attached the `:rf.schema/at-boundary` interceptor (per [010 §Production builds](010-Schemas.md#production-builds)) but the registration's metadata-map carried no `:schema`. The boundary interceptor is structurally meaningless without a schema to validate against, so the registrar hard-rejects the call at registration time rather than waiting for the first dispatch to surface the misconfiguration. Pre-rf2-iftj4 the runtime emitted `:rf.warning/boundary-without-spec` from the boundary interceptor at first dispatch in production builds only (dev was silent); the registration-time rejection replaces that warning across dev and prod uniformly. Surfaced as a thrown ex-info from `reg-event-*`, not a trace. Per [010 §Production builds](010-Schemas.md#production-builds) and rf2-iftj4 (audit rf2-ycqtv finding #8) | `:no-recovery` — the call throws an ex-info; the offending handler is NOT registered. The two fixes: (1) attach a `:schema` to the metadata-map (recommended), or (2) remove the boundary interceptor from the positional vector | `:reg-fn` (the calling reg-fn's name as a string), `:id` (the offending event-id), `:reason`, `:recovery` |
| `:rf.warning/schema-validator-unavailable` | `:warning` | A `reg-app-schema` (or `reg-app-schemas`) call was made while the `:schemas/malli-validate` late-bind hook is unbound AND `validator-fn` is still the framework default. Per [010 §Recommended soft-pass](010-Schemas.md) the default validator returns true ("pass") when the Malli adapter ns hasn't been required at app boot — every validation site soft-passes, so boundary-validated handlers silently accept untrusted input. Emitted at most once per process from the registration sites. Suppressed when (a) `:schemas/malli-validate` is bound (Malli adapter loaded), or (b) the app explicitly registered a non-default validator via `set-schema-validator!` (apps that opted out of Malli). Production elides via `goog.DEBUG`. Per rf2-fq7d2 | `:ignored` — the registration completes normally; the warning is purely diagnostic | `:reason` (an actionable string that names the two fixes — require `re-frame.schemas.malli` at app boot, or call `set-schema-validator!` with a non-default fn) |
| `:rf.warning/schema-walker-opaque` | `:warning` | A `reg-app-schema` (or `reg-app-schemas`) call was made with a schema value that is NOT a Malli vector form — a registry-ref keyword (`(rf/reg-app-schema [:user] :my/user-schema)`), a compiled `m/schema` object, or any other opaque value. The schemas-walker (`re-frame.schemas.walker`) is pure data and handles only vector-form Malli EDN; per-slot `:sensitive?` / `:large?` flags inside an opaque value are silently skipped — the validation-failure trace won't redact the sensitive slot and the size-elision walker won't see the `:large?` declarations. Two workable shapes: (1) register the vector form directly so the walker can introspect it; (2) use registration-level `:sensitive?` metadata on the consuming `reg-event-*` for coarse-grained honour. Emitted at most once per process from the registration sites; symmetric with `:rf.warning/schema-validator-unavailable`. Production elides via `goog.DEBUG`. Per [010 §The `:schema` value is opaque to re-frame](010-Schemas.md) and rf2-jsokn / rf2-ycqtv finding #12 | `:ignored` — the registration completes normally; the warning is purely diagnostic | `:path` (the `reg-app-schema` registration path that tripped the warning), `:schema-kind` (one of `:registry-ref`, `:compiled-schema-object`, `:unknown`), `:reason` (an actionable string that names the two workable shapes) |
| `:rf.warning/large-value-unschema'd` | `:warning` | The `rf/elide-wire-value` walker observed a large string at a path with no `{:large? true}` schema metadata. Emitted at most once per `(path, frame)` pair. Advisory: add `{:large? true}` to the schema slot when the value should be elided. Per [§Size elision in traces](#size-elision-in-traces) | `:warned-and-replaced` — the warning fires; the unschema'd value is not auto-elided | `:frame`, `:path`, `:bytes`, `:hint` |
| `:rf.error/sanitised-on-projection` | `:error` | The active error projector threw or returned a non-`:rf/public-error` shape; the runtime fell back to the locked generic-500 public shape. Per [011 §Where sanitisation happens — before render](011-SSR.md#where-sanitisation-happens--before-render) | `:replaced-with-default` — runtime falls back to the locked generic-500 public-error shape | `:projector-id`, `:original-operation`, `:projection-failure-reason` |
| `:rf.error/ssr-head-resolution-failed` | `:error` | An SSR host adapter's `resolve-head` (per [011 §Head/meta contract](011-SSR.md#headmeta-contract)) caught a throw from the active route's `:head` fn (the `rf/active-head` walk or the `rf/head-model->html` emit). The host adapter degrades to an empty head fragment so the request still produces a response; the trace carries the exception for production-observability. Emitted by `re-frame.ssr.ring.lifecycle/resolve-head` in the Ring host adapter; symmetric helpers in other host adapters MUST emit the same category. Per rf2-bof8i (Mike decision, Option B — observability over silent fallback) | `:no-recovery` — the head fragment is empty (`""`); `:html-attrs` and `:body-attrs` are nil; rendering proceeds against the empty head | `:frame`, `:exception` |
| `:rf.error/safe-redirect-invalid-url` | `:error` | The `:rf.server/safe-redirect` fx received a `:location` string that could not be parsed as a URL (per [011 §Redirect precedence](011-SSR.md#redirect-precedence)). The redirect is rejected; the response accumulator's `:redirect` slot is unchanged. Emitted by `re-frame.ssr.response/safe-redirect-fx`. Per rf2-zfm8v (Mike decision, Option A — ship safe-redirect-fx alongside redirect-fx) | `:no-recovery` — the redirect is rejected; no Location header is set | `:frame`, `:location`, `:reason` |
| `:rf.error/safe-redirect-scheme-rejected` | `:error` | The `:rf.server/safe-redirect` fx received a `:location` whose scheme is one of the rejected set (`javascript:`, `data:`, `vbscript:`) — these schemes have no safe interpretation as redirect targets (XSS via `javascript:`, data-URL phishing, IE-era `vbscript:`). Consistent with the custom-editor scheme rejection in rf2-vwcsq. Emitted by `re-frame.ssr.response/safe-redirect-fx`. Per rf2-zfm8v | `:no-recovery` — the redirect is rejected; no Location header is set | `:frame`, `:location`, `:scheme` |
| `:rf.error/safe-redirect-host-disallowed` | `:error` | The `:rf.server/safe-redirect` fx received a `:location` whose host is not permitted by the call's policy — either `:relative-only? true` was set and the URL carried a host, OR `:allow [...]` was set and the URL's host did not appear in the allowlist. The `:reason` tag (`:relative-only-violation` or `:not-in-allowlist`) discriminates the two modes. Mitigation for the open-redirect class (audit 2026-05-14 §P3.2): an attacker-controlled `?next=…` URL parameter cannot redirect off-origin when the application uses `:rf.server/safe-redirect` instead of `:rf.server/redirect`. Emitted by `re-frame.ssr.response/safe-redirect-fx`. Per rf2-zfm8v | `:no-recovery` — the redirect is rejected; no Location header is set | `:frame`, `:location`, `:host`, `:reason` (one of `:relative-only-violation`, `:not-in-allowlist`), `:allow?` (the allowlist when supplied) |
| `:rf.epoch/restore-unknown-epoch` | `:error` | `restore-epoch` was called with an `epoch-id` that is not in the frame's current epoch history (either never recorded or aged out by `:depth`). Per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel) | `:no-recovery` — restore rejected; the frame's state is unchanged | `:frame`, `:epoch-id`, `:history-size` |
| `:rf.epoch/restore-schema-mismatch` | `:error` | The recorded `:db-after` no longer validates against the currently-registered `app-schemas` set (a schema was added, tightened, or replaced since the snapshot was taken). Per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel) | `:no-recovery` — restore rejected; the frame's state is unchanged | `:frame`, `:epoch-id`, `:schema-digest-recorded`, `:schema-digest-current`, `:failing-paths` |
| `:rf.epoch/restore-missing-handler` | `:error` | The recorded `app-db` references a registered-id (e.g. an active machine at `[:rf/machines <id>]`, a registered route currently in `:rf/route`) that is no longer present in the registrar. Per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel) | `:no-recovery` — restore rejected; the frame's state is unchanged | `:frame`, `:epoch-id`, `:missing` (vector of `{:kind :id}`) |
| `:rf.epoch/restore-version-mismatch` | `:error` | The frame's recorded `:rf/snapshot-version` (per [Spec-Schemas §`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot)) is incompatible with the currently-loaded machine definition. Per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel) | `:no-recovery` — restore rejected; the frame's state is unchanged | `:frame`, `:epoch-id`, `:machine-id`, `:version-recorded`, `:version-current` |
| `:rf.epoch/restore-during-drain` | `:error` | `restore-epoch` was called while the frame's run-to-completion drain is still in flight (per [002 §Run-to-completion dispatch](002-Frames.md#run-to-completion-dispatch-drain-semantics)). Restore is rejected; the user retries after settle. Per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel) | `:no-recovery` — restore rejected; the user retries after settle | `:frame`, `:epoch-id` |
| `:rf.epoch/restore-non-ok-record` | `:error` | `restore-epoch` was called against an epoch record whose `:outcome` is not `:ok` (a halted-cascade record kept for devtools introspection; see [Spec-Schemas §`:rf/epoch-record` §Outcomes](Spec-Schemas.md#outcomes-rf2-v0jwt)). Restore is rejected because a halted record's `:db-after` is partial state the cascade never settled to. | `:no-recovery` — restore rejected; the frame's state is unchanged | `:frame`, `:epoch-id`, `:outcome`, `:halt-reason` |
| `:rf.epoch/reset-frame-db-during-drain` | `:error` | `reset-frame-db!` was called while the frame's drain was still running. Pair-tool injection is rejected; the caller retries after settle. Per [Tool-Pair §Pair-tool writes](Tool-Pair.md#pair-tool-writes--state-injection) (rf2-zq55) | `:no-recovery` — pair-tool injection rejected; the caller retries after settle | `:frame` |
| `:rf.epoch/reset-frame-db-schema-mismatch` | `:error` | `reset-frame-db!` was called with a `new-db` value that fails the frame's currently-registered `app-schema` set. The injection is rejected; `app-db` is unchanged. Per [Tool-Pair §Pair-tool writes](Tool-Pair.md#pair-tool-writes--state-injection) (rf2-zq55) and [010 §Per-frame schemas](010-Schemas.md#per-frame-schemas) | `:no-recovery` — pair-tool injection rejected; `app-db` is unchanged. The new-db failed the frame's registered app-schema set; the failing paths are surfaced in `:tags :failing-paths` | `:frame`, `:failing-paths` |
| `:rf.error/no-such-fx` | `:error` | A dispatched fx-id has no registered handler (and was not redirected by `:fx-overrides`). Per [002 §`:fx` ordering and atomicity guarantees](002-Frames.md#fx-ordering-and-atomicity-guarantees). Emitted by `re-frame.fx/handle-one-fx` after override resolution and reserved-id matching both miss | `:no-recovery` — the fx is dropped; cascade continues with remaining `:fx` entries | `:fx-id`, `:fx-args`, `:frame` |
| `:rf.error/no-such-cofx` | `:error` | An `inject-cofx` interceptor referenced a cofx-id with no registered handler. Per [002 §Effects and coeffects](002-Frames.md). Emitted by `re-frame.cofx/inject-cofx` when registry lookup misses. Sibling interceptors continue; the ctx flows through unchanged | `:no-recovery` — the cofx injection is a no-op; subsequent interceptors run with the ctx unchanged | `:cofx-id`, `:cofx-value` (only when the 2-arity `inject-cofx` was used), `:event-id` (the event that ran the offending interceptor chain, when available) |
| `:rf.error/frame-destroyed` | `:error` | A `dispatch` / `dispatch-sync` / `subscribe` arrived against a frame whose `(:lifecycle frame-record)` carries `:destroyed? true`. Per [002 §Frame lifecycle](002-Frames.md#frame-lifecycle). The router rejects the call; for `subscribe` the result is `nil`. Emitted from router.cljc and subs.cljc | `:no-recovery` — dispatch / subscribe is rejected; cascade halts (or returns nil for the subscribe path) | `:frame`, `:event` (when called from dispatch), `:query-v` (when called from subscribe) |
| `:rf.error/flow-eval-exception` | `:error` | A flow's `:output` fn threw during the recompute walk inside an event handler's interceptor pipeline (per [013 §Flow tracing](013-Flows.md#flow-tracing)). Distinct from `:rf.flow/failed`, which is the per-flow op-type-`:flow` trace; this is the cascade-level error event the router emits when the throw escapes the flow walk | `:no-recovery` — the cascade halts; the snapshot is uncommitted. The per-flow `:rf.flow/failed` op-type-`:flow` event also fires for attribution | `:frame`, `:event`, `:exception` |
| `:rf.error/unwrap-bad-event-shape` | `:error` | The `:rf/unwrap` interceptor saw an event vector that does not conform to the expected `[event-id payload-map]` shape (per [Conventions §Unwrap interceptor](Conventions.md)) | `:no-recovery` — the interceptor returns the original ctx unchanged; the downstream handler receives the unwrapped vector unmodified | `:event`, `:expected` (the contract string) |
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
| `:rf.error/machine-always-self-loop` | `:error` | An `:always` entry's `:target` resolves to the declaring state itself with the same `:guard` reference (or no guard). Per [005 §Self-loop forbidden at registration](005-StateMachines.md#self-loop-forbidden-at-registration). Registration is rejected | `:no-recovery` — registration is rejected | `:state` (the declaring state-keyword), `:machine-id` |
| `:rf.error/machine-compound-state-missing-initial` | `:error` | A compound state declares `:states` but no `:initial`. Per [005 §Initial-state cascading](005-StateMachines.md#initial-state-cascading). Registration is rejected | `:no-recovery` — registration is rejected | `:machine-id`, `:state` |
| `:rf.error/machine-final-state-compound` | `:error` | A state declaring `:final? true` ALSO declares `:states` (or `:initial`). Compound states cannot themselves be final — their finality is expressed by a leaf inside them. Surfaced at registration time. Per [005 §Final states](005-StateMachines.md#final-states-final--on-done--output-key) | `:no-recovery` — registration is rejected | `:machine-id`, `:state` |
| `:rf.error/machine-final-state-has-transitions` | `:error` | A `:final?` state ALSO declares `:on`, `:always`, `:after`, `:invoke`, or `:invoke-all`. Final means final — no further transitions (`:entry` / `:exit` actions ARE permitted). Surfaced at registration time. Per [005 §Final states](005-StateMachines.md#final-states-final--on-done--output-key) | `:no-recovery` — registration is rejected | `:machine-id`, `:state`, `:offending-keys` |
| `:rf.error/machine-output-key-without-final` | `:error` | A non-final state declared `:output-key`. The key is only legal on a state with `:final? true`. Surfaced at registration time. Per [005 §Final states](005-StateMachines.md#final-states-final--on-done--output-key) | `:no-recovery` — registration is rejected | `:machine-id`, `:state`, `:output-key` |
| `:rf.error/machine-invoke-all-bad-shape` | `:error` | A child invoke-spec inside an `:invoke-all` block is missing `:id` or both `:machine-id` and `:definition`; or `:invoke-all` is not a vector; or the join-event slots are missing per the required-iff rules. Surfaced at registration time. Per [005 §Spawn-and-join via `:invoke-all`](005-StateMachines.md#spawn-and-join-via-invoke-all) | `:no-recovery` — registration is rejected | `:machine-id`, `:state`, `:reason` |
| `:rf.error/machine-invoke-all-duplicate-id` | `:error` | Two child invoke-specs inside the same `:invoke-all` block share an `:id` keyword. Each `:id` must be unique. Surfaced at registration time. Per [005 §Spawn-and-join via `:invoke-all`](005-StateMachines.md#spawn-and-join-via-invoke-all) | `:no-recovery` — registration is rejected | `:machine-id`, `:state`, `:duplicate-id` |
| `:rf.error/machine-invoke-all-with-invoke` | `:error` | A state node declares both `:invoke` and `:invoke-all`. The combination is rejected. Surfaced at registration time. Per [005 §Spawn-and-join via `:invoke-all`](005-StateMachines.md#spawn-and-join-via-invoke-all) | `:no-recovery` — registration is rejected | `:machine-id`, `:state` |
| `:rf.error/machine-parallel-nested-not-supported` | `:error` | A parallel region's own state-tree declares `:type :parallel` (nested parallel regions). Not supported in v1. Surfaced at registration time. Per [005 §Parallel regions](005-StateMachines.md) and the [005 §Capability matrix](005-StateMachines.md#capability-matrix) | `:no-recovery` — registration is rejected | `:machine-id`, `:state` |
| `:rf.error/no-such-route` | `:error` | A `route-url` call (or one of its callers) addressed a `:route-id` that is not in the routing registrar (per [012](012-Routing.md)) | `:no-recovery` — the call throws; the caller chooses how to surface the failure | `:route-id` |
| `:rf.error/missing-route-param` | `:error` | A `route-url` build-from-pattern call did not supply a value for a required path parameter (per [012 §URL building](012-Routing.md)) | `:no-recovery` — the call throws; the caller chooses how to surface the failure | `:param` (the missing param keyword), `:route-id` |
| `:rf.error/route-too-many-keys` | `:error` | A `match-url` call parsed a URL whose query string carried more than `default-max-decoded-keys` unique keys (default 10000). Symmetric routing-side companion to the HTTP `:rf.error/malformed-json :reason :too-many-keys` (per rf2-wu1n5). Defends long-running JVM hosts against URL-driven keyword-interning DoS (per [012 §Keyword-interning cap on query keys + values](012-Routing.md#keyword-interning-cap-on-query-keys--values-rf2-3k3o7) and rf2-3k3o7). | `:no-recovery` — the parse throws; the navigation event propagates the failure | `:url`, `:limit`, `:count` |
| `:rf.error/bad-app-schemas-arg` | `:error` | `app-schemas` was called with a non-keyword, non-map, non-nil argument (per [010 §App-db schemas](010-Schemas.md)) | `:no-recovery` — the call throws | `:received` (the offending value), `:expected` (the contract string) |
| `:rf.error/unknown-preset` | `:error` | `reg-frame` metadata's `:preset` value is not in the closed set `#{:default :test :story :ssr-server}` (per [Spec-Schemas §`:rf/preset-expansion`](Spec-Schemas.md#rfpreset-expansion)) | `:no-recovery` — the call throws; registration of the offending frame fails | `:preset` (the offending value), `:valid` (the closed set) |
| `:rf.error/adapter-already-installed` | `:error` | A second `install-adapter!` call was made without an intervening `dispose-adapter!` (per [006 §Single adapter per process](006-ReactiveSubstrate.md#single-adapter-per-process)) | `:no-recovery` — the call throws; the existing adapter remains installed | `:installed` (the existing adapter), `:attempted` (the offending second adapter) |
| `:rf.error/no-adapter-specified` | `:error` | `(rf/init! …)` was called with no args, nil, or a non-map argument (e.g. a keyword). The only legal call shape is `(rf/init! adapter-map)` — require the adapter ns and pass its `adapter` Var, e.g. `(rf/init! reagent/adapter)`. Per [006 §Adapter selection at boot](006-ReactiveSubstrate.md#adapter-selection-at-boot) and rf2-agql. Surfaced as a thrown ex-info, not a trace | `:no-recovery` — the call throws | `:where` (`'init!`), `:received` (when nil/keyword/non-map), `:expected`, `:reason` |
| `:rf.error/render-on-headless-adapter` | `:error` | `render` was called on the plain-atom (JVM/SSR) adapter, which only supports `render-to-string` (per [006](006-ReactiveSubstrate.md)) | `:no-recovery` — the call throws; user should use `render-to-string` on this adapter | `:reason` |
| `:rf.error/no-hiccup-emitter-bound` | `:error` | `render-to-string` was called before the SSR namespace bound the hiccup emitter via `set-hiccup-emitter!` (per [011](011-SSR.md)) | `:no-recovery` — the call throws; SSR namespace must be required so `set-hiccup-emitter!` runs | `:reason`, `:render-tree` |
| `:rf.error/frame-context-corrupted` | `:error` | A function-component frame-id read (`_currentValue` on the shared React context) observed a value `coerce-context-value` cannot resolve to a frame keyword — nil, false, a number, an empty string, or a JS object. Real-world triggers: a subtree rendered through an unwrapped portal, a Provider authored with a non-keyword `:value`, or a library mutating `_currentValue` externally. Per [006 §Frame-provider via React context](006-ReactiveSubstrate.md) and rf2-8q66 | `:replaced-with-default` — the function-component resolution chain falls through to `:rf/default`. Pre-rf2-8q66 observable behaviour preserved; the error event is the new diagnostic surface | `:received` (the offending value), `:type` (a short keyword tag — `:nil` / `:boolean` / `:number` / `:string` / `:empty-string` / `:keyword` / `:symbol` / `:map` / `:vector` / `:sequential` / `:collection` / `:fn` / `:js-object`), `:reason` |
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
| `:rf.warning/route-shadowed-by-equal-score` | `:warning` | A `reg-route` registered a pattern whose [`:rf/route-rank`](Spec-Schemas.md#rfroute-rank) tuple equals an already-registered pattern's; the new route shadows the old per stable sort order (per [Spec-Schemas §`:rf/route-rank`](Spec-Schemas.md#rfroute-rank) and [012 §Route ranking algorithm](012-Routing.md#route-ranking-algorithm)) | `:warned-and-replaced` — the new route registers; equal-score sibling is shadowed by stable sort | `:route-id` (the new id), `:shadowed` (the displaced id) |
| `:rf.warning/no-not-found-route` | `:warning` | An unmatched URL arrived but no `:rf.route/not-found` route was registered. The runtime falls back to a built-in placeholder view (a minimal `<h1>Not Found</h1>` page) so the request still produces a response. Per [012 §Route-not-found](012-Routing.md#route-not-found--rfroutenot-found-canonical) | `:warned-and-replaced` — falls back to the built-in placeholder; the warning surfaces the missing registration | `:url`, `:frame`, `:reason` |
| `:rf.warning/decode-defaulted` | `:warning` | A managed-HTTP request fell through to the default `:auto` decode pipeline because no `:decode` was supplied (per [014 §Default decode](014-HTTPRequests.md)). Informational; the auto-decode is supported | `:ignored` — informational; auto-decode proceeds normally | `:request-id`, `:url`, `:content-type`, `:resolved-decoder` |
| `:rf.warning/write-after-destroy` | `:warning` | The substrate adapter's `replace-container!` was called with a nil container — the frame was likely destroyed mid-drain or before a scheduled write fired. Per [006](006-ReactiveSubstrate.md) | `:no-recovery` — the write is dropped; the substrate's `replace-container!` is not invoked | `:reason` |
| `:rf.http/cljs-only-key-ignored-on-jvm` | `:warning` | A managed-HTTP request supplied a CLJS-only request key (`:mode`, `:cache`, `:referrer`, `:integrity`) that the JVM transport cannot honour. The key is ignored. Per [014](014-HTTPRequests.md) | `:ignored` — the unsupported key is dropped; the request proceeds with the remaining keys | `:key`, `:url` |
| `:rf.http/retry-attempt` | `:info` | A managed-HTTP attempt failed with a retryable category and the runtime is scheduling another attempt. Per [014 §Retry and backoff](014-HTTPRequests.md#retry-and-backoff) | `:retried` — a new attempt is scheduled; the consumer sees the trace and the eventual final outcome via `:on-failure` / `:on-success` | `:request-id`, `:url`, `:attempt`, `:max-attempts`, `:failure` (a `:rf.http/*` failure-category map), `:next-backoff-ms` |
| `:rf.http/aborted-on-actor-destroy` | `:info` | A managed-HTTP request was aborted because the spawned state-machine actor that issued it was destroyed (parent state exit, parent's `:after` firing, `:invoke-all` cancel-on-decision, frame destroy, or imperative `[:rf.machine/destroy]`). The reply lands as a standard `:rf.http/aborted` failure with `:reason :actor-destroyed`. Per [014 §Abort on actor destroy](014-HTTPRequests.md#abort-on-actor-destroy) and [005 §Cancellation cascade — in-flight `:rf.http/managed` aborts](005-StateMachines.md#cancellation-cascade--in-flight-rfhttpmanaged-aborts) (rf2-wvkn) | n/a — informational lifecycle trace | `:request-id` (when set), `:actor-id` (the destroyed spawned-actor address), `:url` |
| `:rf.http.interceptor/registered` | `:info` | A `reg-http-interceptor` succeeded on a frame's request-side middleware chain. Per [014 §Middleware](014-HTTPRequests.md#middleware) (rf2-6y3q) | n/a — informational lifecycle trace | `:frame`, `:id` |
| `:rf.http.interceptor/cleared` | `:info` | A `clear-http-interceptor` removed an existing interceptor slot (no trace fires for clear-of-unknown-id). Per [014 §Middleware](014-HTTPRequests.md#middleware) (rf2-6y3q) | n/a — informational lifecycle trace | `:frame`, `:id` |
| `:rf.error/http-interceptor-failed` | `:error` | A request-side interceptor's `:before` fn threw. The runtime emits this category, then re-throws — `re-frame.fx` catches the re-throw and emits the cascade-level `:rf.error/fx-handler-exception`; the request is NOT dispatched. Per [014 §Middleware §Failure mode](014-HTTPRequests.md#failure-mode) (rf2-6y3q) | `:no-recovery` — the interceptor's throw propagates; the request is not dispatched | `:frame`, `:interceptor-id`, `:url`, `:cause` |
| `:rf.error/http-bad-interceptor` | `:error` | `reg-http-interceptor` was called with an invalid interceptor map (missing `:id`, non-keyword `:id`, or non-fn `:before`). Surfaced as a thrown ex-info from the registration call, not a trace. Per [014 §Middleware](014-HTTPRequests.md#middleware) (rf2-6y3q) | `:no-recovery` — the call throws an ex-info; registration fails | `:where` (`'reg-http-interceptor`), `:received`, `:reason` |
| `:rf.error/http-bad-retry-on` | `:error` | A `:rf.http/managed` fx was invoked with a `:retry :on` set that contains a non-retryable or unknown category. The closed retryable set is `#{:rf.http/transport :rf.http/cors :rf.http/timeout :rf.http/http-4xx :rf.http/http-5xx}`; `:rf.http/aborted` / `:rf.http/decode-failure` / `:rf.http/accept-failure` are explicitly rejected, and any keyword outside `:rf.http/*` is rejected. Surfaced as a thrown ex-info from the fx-call site, not a trace. Per [014 §Closed-set `:retry :on` validation](014-HTTPRequests.md#closed-set-retry-on-validation--rf2-apwkm) (rf2-apwkm) | `:no-recovery` — the call throws an ex-info; the request is not dispatched | `:where` (`':rf.http/managed`), `:bad-members` (the offending keywords from `:on`), `:retryable-set` (the closed set), `:reason` |
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
   [:where           [:enum :event :sub-return :app-db :fx-args :cofx :on-create]]
   [:path            [:vector :any]]
   [:value           :any]
   [:explain         :any]                          ;; Malli explanation shape
   [:registered-path {:optional true} [:vector :any]]]) ;; (:where :app-db only) registration root; :path is the failing leaf — see Spec/010

;; ... and so on for each category — see Spec-Schemas for the full set.
```

Pattern-level: every implementation registers an equivalent set of schemas. The category vocabulary is stable and additive — new categories can be added but existing ones cannot be renamed or removed.

### Server error projection — public boundary

For SSR specifically, the structured trace event is the **internal** record (rich, full detail, monitor-bound) and a separate **public projection** is written to the HTTP response (sanitised, client-safe). The internal trace event is **never** serialised to the client. The projection mechanism is owned by [011 §Server error projection](011-SSR.md#server-error-projection); the trace stream is unchanged by it. Tools that want full error detail subscribe via `register-trace-listener!` as usual; the response carries only the locked `:rf/public-error` shape.

The runtime emits `:rf.error/sanitised-on-projection` (above) when the projector itself fails, so monitor dashboards see when the public boundary fell back to the generic-500 shape.

### Recovery contract

The `:recovery` field on the trace event tells consumers (dev panels, error-monitor integrations, tooling) what the runtime did:

- `:no-recovery` — the error propagated; the event was not handled.
- `:replaced-with-default` — the runtime used a default value (e.g., `:no-such-handler` falling through to a no-op).
- `:retried` — the runtime retried (with an upper bound) and surfaces the result.
- `:skipped` — the runtime declined to act (`:rf.fx/skipped-on-platform`, `:rf.cofx/skipped-on-platform`).
- `:warned-and-replaced` — the runtime emitted the warning and did its default action anyway (e.g., `:rf.ssr/hydration-mismatch` warn-and-replace mode).
- `:logged-and-skipped` — the runtime emitted the trace and dropped the offending input; sibling inputs still apply (e.g., `:rf.error/effect-map-shape` drops the offending top-level effect-map key while `:db` / `:fx` still apply).

A user-registered error-handler can intercept any error category and decide policy. The default error-handler routes everything to the trace stream and proceeds with the documented per-category recovery. Error-handler policy is registered per-frame via the `:on-error` slot in `reg-frame` metadata (per [002-Frames §`:on-error`](002-Frames.md)); for cross-frame observation, `register-trace-listener!` filtering on `:op-type :error` (or on the `:rf.error/*` `:operation` namespace) sees every error event without modifying behaviour. (The v1 process-wide `reg-event-error-handler` surface is dropped — see [MIGRATION.md §M-26](../migration/from-re-frame-v1/README.md#m-26-drift-sweep-drops--v1-surfaces-with-no-v2-equivalent-or-absorbed-by-canonical-surfaces).)

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

**Production elision (rf2-hqbeh).** Unlike the rest of the trace surface, the `:on-error` slot is NOT gated by `re-frame.interop/debug-enabled?`. It rides a small always-on error-emit substrate (`re-frame.error-emit`) that survives `:advanced` + `goog.DEBUG=false`. Registered policy fns fire on production handler exceptions; the substrate carries `:operation`, `:op-type :error`, and the category-specific `:tags`. Dev-side enrichments (`:dispatch-id`, `:rf.trace/trigger-handler`, the `:rf.error/bad-on-error-return` / `:rf.error/on-error-policy-exception` validation traces) ride the trace surface and continue to elide; policy-fn exceptions are caught silently in CLJS prod (per Spec 009 §1052 the cascade does not abort). The substrate currently covers `:rf.error/handler-exception` — the primary production-monitoring case (rf2-hqbeh validation criterion); widening to other categories is a non-breaking follow-on.

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

Multiple monitoring concerns compose in user code (one `:on-error` handler that fans out to several services). For cross-frame observation that doesn't modify recovery, prefer `register-trace-listener!` filtered on `:op-type :error`.

## Notes

### Why this is its own Spec

Tracing is the connective tissue between the runtime and every tool that observes it. Splitting it into its own Spec:

- Locks the data shape independently of any specific tool.
- Documents the forward-compat commitments tools depend on.
- Separates "framework emits events" (002 territory) from "framework provides a tap surface" (this Spec).
- Documents the prod-side Performance API instrumentation channel (gated on `re-frame.performance/enabled?`, default-off) alongside the dev-side trace stream — two compile-time-elidable surfaces with distinct gates and distinct consumers (see [§Performance instrumentation](#performance-instrumentation)).

## Open questions

> **SA-4 classification (rf2-p6xyh).** Per [SPEC-AUTHORING §SA-4](SPEC-AUTHORING.md): the only item that previously lived here ("Trace allocation cost in dev when no listeners") classifies as **`:resolved`** — the `(rf/configure :trace-buffer {:depth 0})` escape hatch IS the answer. Migrated to `## Resolved decisions` below.

## Resolved decisions

### Listener ordering

Multiple listeners may register concurrently. **Listener-invocation order is not contract** — tools must not depend on the order in which sibling listeners receive a given event. Each listener receives the same event independently; nothing about the order in which the runtime walks the listener registry is guaranteed across builds, hosts, or registry implementations. The same rule applies to `register-trace-listener!` (per [§Subscription / consumption](#subscription--consumption) and [§Listener invocation rules](#listener-invocation-rules)) and `register-epoch-listener!` (per [`register-epoch-listener!` §Invocation rules](#register-epoch-listener--assembled-epoch-listener)).

### Trace allocation cost in dev when no listeners

In dev, `interop/debug-enabled?` is true, so the emit body runs even when no listeners are registered: the runtime allocates the event map, pushes it to the retain-N ring buffer, and walks the (empty) listener registry. The ring-buffer push is the floor cost. Tools that want maximum dev-loop throughput can `(rf/configure :trace-buffer {:depth 0})` to disable the ring buffer; the synchronous-delivery path still works and the user-listener fan-out remains zero-cost when no listeners are attached.

### Trace correlation across the cascade

Two cascade-wide channels ride on **every** trace event emitted inside a cascade — neither is scoped to errors:

1. **`:dispatch-id`** under `:tags` (per [§Dispatch correlation](#dispatch-correlation-dispatch-id--parent-dispatch-id)). Grouping raw trace events by cascade is a single-key filter — `(filter #(= cascade-id (get-in % [:tags :dispatch-id])) events)`. Tools that need cascade *trees* walk `:parent-dispatch-id` upward across `:event/dispatched` events (the inter-cascade lineage channel).

2. **`:rf.trace/trigger-handler`** at the top level (per [§`:rf.trace/trigger-handler` — naming the in-scope handler](#rftracetrigger-handler--naming-the-in-scope-handler)). Names the handler whose code produced the event and carries its registration coord — so jump-to-source links work from every trace event in a cascade, not just errors. Rides on `:rf.fx/handled`, `:rf.machine/transition`, `:event/db-changed`, `:event/do-fx`, `:sub/run`, `:view/render`, and all `:rf.error/*` events whenever a handler is in scope at emit time. Omitted outside any handler scope (registration-time emits, outermost-dispatch lookup failures).

Per-cascade structured projection lives in the assembled `:rf/epoch-record` (per [Spec-Schemas](Spec-Schemas.md#rfepoch-record)) — the raw `:dispatch-id` / `:rf.trace/trigger-handler` channels are the lower-level primitives.

### Trace event for app-db changes

`:db` mutations happen inside `do-fx`. The runtime emits a separate `:event/db-changed` trace event on every dispatch whose handler returned a new db value. Tools that want before/after pairs read the `:rf/epoch-record`'s `:db-before` / `:db-after` slots, which the runtime captures atomically across the cascade rather than per-event.

### Privacy / sensitive data in traces

> Cross-reference: see [Security.md §Privacy / secret handling](Security.md#privacy--secret-handling) for the framework-wide pattern-level posture this section grounds — per-slot schema `:sensitive?` metadata is the canonical privacy marker. (The legacy handler-meta `:sensitive?` annotation has been removed per rf2-hjs2d; sensitive data marking is path-based per the upcoming data-classification mechanism — separate spec doc; in progress.)

Trace events carry dispatched event vectors, handler return values, and (under [§Trace event for app-db changes](#trace-event-for-app-db-changes)) `app-db` snapshots — any of which may contain user input that should not leave the developer's machine: passwords, auth tokens, payment details, PII captured from form fields. Tools that ship traces off-box (error-monitor forwarders per [§Wiring an external error monitor](#wiring-an-external-error-monitor-sentry-rollbar-honeybadger-etc), remote dev dashboards, the Causa-MCP / re-frame2-pair servers per [Tool-Pair.md](Tool-Pair.md)) must not emit that data verbatim.

The declaration surface is schema-first. Apps declare sensitive app-db slots with `{:sensitive? true}` on Malli schema metadata; path-scoped handlers automatically install an internal redaction interceptor that redacts matching event-payload paths for trace/error emission while the handler body still receives the raw `:event` coeffect. The complementary site is `(rf/with-redacted [[:password] ...])`, a positional interceptor that scrubs named payload keys on the trace surface.

> **Unified wire-elision surface.** `:sensitive?` (privacy) and `:large?` (size) are **two orthogonal predicates over the same wire-boundary elision walker** — both consumed by `rf/elide-wire-value` (per [§Size elision in traces](#size-elision-in-traces) below and [API.md §`rf/elide-wire-value`](API.md#elide-wire-value-the-wire-boundary-walker)). The walker emits the `:rf/redacted` sentinel for sensitive values and the `:rf.size/large-elided` marker for large values; when both predicates match the **sensitive drop wins** (the size marker would leak `:path` / `:bytes` and is suppressed). Same shape, two flags, one helper.

#### The `:sensitive?` registration metadata key

> **NOTE (rf2-hjs2d):** The handler-meta `:sensitive?` registration-metadata
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
redaction (below) and `with-redacted` (the positional interceptor) are
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
- **Positional interceptor.** `(rf/with-redacted [[:password] ...])` scrubs named payload keys before the trace surface sees them; complementary to schema-marked paths.
- **Trace-only redaction.** The internal redaction interceptor writes the redacted event to framework trace/error emission slots. The regular `:event` coeffect stays raw so handlers can perform the requested work.
- **Sentinel keyword.** Redacted values are replaced with the framework-reserved `:rf/redacted` sentinel. Apps MUST NOT use it as a legitimate payload value.

#### Trace-event field: `:sensitive?` at the top level

The `:rf/trace-event` schema (per [Spec-Schemas §`:rf/trace-event`](Spec-Schemas.md#rftrace-event)) gains an optional top-level `:sensitive?` boolean. Tools branch on it directly:

```clojure
(rf/register-trace-listener!
  :my-app/remote-shipper
  (fn [trace-event]
    (when-not (:sensitive? trace-event)              ;; default off-box-ship policy
      (ship-to-remote-dashboard! trace-event))))
```

Filter-shape integration: `(rf/trace-buffer {:sensitive? false})` returns only the non-sensitive events from the ring buffer. The filter vocabulary at [§Filter vocabulary](#filter-vocabulary) gains one row:

| Key | Type | Semantics |
|---|---|---|
| `:sensitive?` | boolean | Match the top-level `:sensitive?` field. Pass `false` to exclude sensitive events; pass `true` to select only sensitive events. Absent ⇒ no constraint. |

#### Listener filtering semantics

Listeners installed via `register-trace-listener!` and `register-epoch-listener!` (per [§The listener API](#the-listener-api)) receive **every** trace event regardless of `:sensitive?` — the flag is a payload axis the listener inspects, not a delivery gate. Two reasons: (1) on-box developer tooling (10x, the trace panel, the in-process ring buffer) needs to see sensitive traces during local dev; (2) routing the filter into the runtime would force every consumer to opt in to seeing sensitive data and complicate the elision contract. Filtering lives in the **listener body**, not in the framework's dispatch path.

Framework-published listener integrations MUST default to suppressing `:sensitive? true` events:

- The Sentry / Honeybadger forwarder samples at [§Wiring an external error monitor](#wiring-an-external-error-monitor-sentry-rollbar-honeybadger-etc) wrap their `register-trace-listener!` body in `(when-not (:sensitive? trace-event) ...)` by default. Apps that want the events shipped (rare; only when the monitor is itself the trust boundary, e.g. a self-hosted Sentry inside the same VPN) opt in by removing the guard.
- The re-frame2-pair server (per [Tool-Pair.md §How AI tools attach](Tool-Pair.md#how-ai-tools-attach)) MUST drop or redact `:sensitive? true` events before forwarding to the AI surface. The default policy is **drop**; apps that want sensitive cascades visible to the pair tool configure the policy explicitly.
- The Causa-MCP server (per [Tool-Pair.md](Tool-Pair.md)) MUST default-drop `:sensitive? true` events from the cascade graph it materialises.

User-side listeners (in-app recorders, dev panels, custom forwarders) have no framework-imposed policy — they receive every event and decide on a per-app basis. The recommended discipline is identical: gate any off-box egress on `(when-not (:sensitive? trace-event) …)`.

The **user-controllable config knob** each consumer exposes for the default-suppress policy follows a fixed verb convention per [Conventions §Privacy config-knob naming](Conventions.md#privacy-config-knob-naming-on-box-ui-vs-off-box-wire-egress): on-box devtools UI consumers use the `show-sensitive?` verb under the `:trace/*` ns (e.g. `:trace/show-sensitive?` — UI visibility), while off-box wire-egress consumers (the MCP triplet, the re-frame2-pair preload) use the **unqualified** `include-sensitive?` verb (e.g. `{:rf.size/include-sensitive? false}` on the elision policy map — wire egress). Both default to suppress; the verb choice tells the reader which trust boundary the knob governs without re-deriving from context.

#### Retroactive-scrub on `set-show-sensitive!` false

Resolved per **rf2-lqmje**.

The on-box `show-sensitive?` knob is **not a one-way trapdoor**. Each consumer's `(set-show-sensitive! v)` is gated at ingest time only — it decides whether the next emit lands in the consumer's ring buffer, not whether buffer reads see existing payloads. Without an explicit retroactive-scrub rule the toggle has a privacy hole:

```text
1. show-sensitive? = true     (engineer flips on to debug redaction policy)
2. sensitive cascade emitted  (auth/login event lands in every consumer's buffer)
3. show-sensitive? = false    (engineer flips back off, expecting privacy restored)
4. panels keep showing the buffered :sensitive? payloads forever
```

The normative rule: every on-box `:trace/show-sensitive?` consumer (Causa's `trace-bus`, Story's per-variant `ui.trace` buffer, future devtools that hold a buffer downstream of the on-box flag) MUST clear its trace buffer on the `true → false` transition. `false → false`, `false → true`, and `true → true` MUST NOT clear (no buffered sensitive risk exists for those transitions, and clearing would discard legitimate non-sensitive history without cause).

The clear MUST be **whole-buffer**, not selective. Non-sensitive history buffered alongside the sensitive cascade is intentionally lost. Selective scrubbing is unsafe because a single sensitive event can have caused later non-sensitive cascades — sub recomputes, render args, dispatched-from-fx events — whose payloads structurally reveal the redacted value via the shape of what they consumed. Clearing the whole buffer is the simplest correct semantic; any "smarter" filter risks reintroducing the leak through a derived event.

The clear MUST also reset the per-consumer `[● REDACTED N]` suppressed-events counter so the indicator drops in lockstep with the buffer (the counter is conceptually "since last clear", not "since process start"). Per Causa's `trace-bus/clear-buffer!` and Story's `ui.trace/clear-buffer!` — both already cascade through to the suppressed-counter reset.

Implementation note (non-normative): the reference implementation uses a callback-registry pattern (`config/register-toggle-off-callback!`) so the config layer can invoke the consumer's clear-buffer fn without taking a require dependency on it (the consumer requires the config; not vice versa). Callbacks run on every `true → false` transition; one callback's exception MUST NOT block the others (privacy is the load-bearing concern, and a partial clear is strictly better than no clear). Off-box wire-egress consumers (`include-sensitive?` knobs on the MCP triplet, re-frame2-pair preload) are out of scope for this rule — their flag governs wire emission, not a persistent buffer, so the transitions are stateless.

#### Production-elision behaviour

The `:sensitive?` mechanism is **dev-time only** — both pieces of it ride the trace surface and elide with it:

- The trace surface's `:advanced` + `goog.DEBUG=false` build elides `emit!` entirely (per [§Production builds](#production-builds-zero-overhead-zero-code)). No trace event is allocated, no listener body runs, no `:sensitive?` stamp is built. The privacy mechanism is moot because there is no trace to privacy-protect.
- Schema-installed redaction is internal router machinery. In production builds that retain always-on event/error substrates, the same redacted event shape is used at those boundaries; dev-only trace allocation still DCEs when the trace surface is disabled.
- The elision-probe verifier (per [§Production-elision verification](#production-elision-verification)) treats `":rf/redacted"` as a framework sentinel that may survive only where a production boundary explicitly uses schema redaction.

No registration-time privacy warning exists. Schema metadata is the canonical redaction declaration; `with-redacted` is the positional interceptor for ad-hoc payload scrubs. The handler-meta `:sensitive?` annotation has been removed (rf2-hjs2d).

### Error event catalogue (single source of truth)

Earlier drafts of this Spec carried the error vocabulary across three places: a `### Error categories (initial set)` table that listed `:operation` + meaning + `:tags`, a separate `#### Default behaviour by category` table that listed `:operation` + default `:recovery`, and inline category rows declared within feature subsections.

Consolidated into a single normative [§Error event catalogue](#error-event-catalogue) — one row per category, five columns (`:operation` · `:op-type` · trigger / meaning · default `:recovery` · `:tags`). Each row's emit-site cross-link names the owning Spec section. Per-feature Specs (002, 005, 006, 010, 011, 012, 013, 014, Tool-Pair) reference the catalogue rather than reproducing fragments. The per-category Malli `:tags` schemas remain canonicalised in [Spec-Schemas §Per-category `:tags` schemas](Spec-Schemas.md#per-category-tags-schemas) — one schema per catalogue row. Consumer cost: a single anchor (`#error-event-catalogue`) instead of three; consumers using [API.md §Error contract](API.md#error-contract) get a pointer to the catalogue rather than a partial duplicate (per rf2-wfbn3, G-A closure).

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

Trace events and pair-tool snapshot slices carry tree-shaped values (`app-db` snapshots under [§Trace event for app-db changes](#trace-event-for-app-db-changes), epoch-record `:db-before` / `:db-after` slots per [Tool-Pair §Time-travel](Tool-Pair.md), sub-cache reads, `get-path` returns) that can individually blow the 5K-token wire cap (`tools/re-frame2-pair-mcp/spec/Principles.md` §Wire-cap, rf2-rvyzy). A 5 MB base64-encoded PDF preview under `[:user :uploaded-pdf]` is 290× the cap on its own — and a `:path [:user]` drill-down returns it verbatim, bypassing the [`:rf.mcp/summary`](Tool-Pair.md) lazy-summary mechanism (which shapes the top-level response, not per-value descendants).

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

- **Framework-published off-box listener integrations** (the Sentry / Honeybadger forwarders per [§Wiring an external error monitor](#wiring-an-external-error-monitor-sentry-rollbar-honeybadger-etc), the re-frame2-pair-mcp / Causa-MCP / story-mcp servers per [Tool-Pair.md](Tool-Pair.md)) MUST default `:rf.size/include-large?` to `false` and `:rf.size/include-digests?` to `false`. Tools that ship large-payload-aware integrations (e.g. dedicated artefact-streaming) opt in per-call; the conservative default protects apps that opt into a published integration without reading its source.
- **On-box listener integrations** (Causa panel, Story panels per [Tool-Pair.md](Tool-Pair.md)) MUST default `:rf.size/include-large?` to `false` (the dev-tools UI shows a `[● ELIDED N]`-style indicator the user clicks to opt in for a single fetch). Production-trust on-box consumers MAY default to `true`; the rationale must be documented per-consumer.
- **Indicator field on tool responses.** Tools that return structured response maps (every MCP server per [Tool-Pair.md](Tool-Pair.md)) MUST carry an `:elided-large` count alongside the existing `:dropped-sensitive` count (per [§Privacy / sensitive data in traces](#privacy--sensitive-data-in-traces)) — one MUST-level row per consumer-facing tool that walks a tree-typed payload.

  The `:elided-large` slot reports the count of `:rf.size/large-elided` markers ENCOUNTERED in the tool's response payload. Tools do not invoke `elide-wire-value` themselves — markers ride through from upstream (the event-emit substrate per rf2-rirbq, the error-emit substrate per rf2-bacs4, schema-slot meta). The slot is omitted when the count is zero (per [Conventions.md](Conventions.md) — `:elided-large` row).

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
