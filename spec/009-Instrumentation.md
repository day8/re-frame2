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

- **`:dispatch-id` is cascade-wide.** It is allocated by the runtime when a dispatch is enqueued (before routing) and rides on **every** trace event emitted *inside* that dispatch's run-to-completion drain — `:event/dispatched` itself, `:event/db-changed`, `:rf.fx/handled`, `:sub/run`, `:rf.machine/transition`, `:rf.flow/*`, every `:rf.error/*`, and any future op-type the runtime adds. Consumers (Story `group-cascades`, Causa's causality graph, pair2's `cascade-of`, schema-timeline correlation) group raw trace events by `:dispatch-id` directly — no inference from sequence required. The runtime carries the in-flight cascade's id through the dynamic Var `re-frame.trace/*current-dispatch-id*`, bound by `router.cljc` around each event's processing; `emit!` reads the Var and merges it into the event's `:tags` when bound and not already present. Implementations may use a process-monotonic counter, a UUID, or any opaque value with the same uniqueness contract: distinct within a single process for the lifetime of the trace surface. Tools treat it as opaque. Trace events emitted **outside** any in-flight cascade (frame creation at boot, handler registration before any dispatch, REPL evals that don't dispatch) carry no `:dispatch-id`.
- **`:parent-dispatch-id` is scoped to `:event/dispatched` only.** It documents *cascade-from-cascade lineage* — "this dispatch was emitted as a side-effect of another event's processing" — which is a per-event-dispatch fact, not a per-trace-event fact. Concretely: when an `fx` handler running inside the do-fx phase of dispatch *D₁* invokes `(rf/dispatch ...)`, the runtime records the new dispatch's `:parent-dispatch-id` as *D₁*'s `:dispatch-id` on the new dispatch's `:event/dispatched` event. If the dispatch was initiated outside any in-flight event (a timer, a UI handler, the REPL, the SSR boot path), `:parent-dispatch-id` is absent from `:event/dispatched`. Non-`:event/dispatched` trace events never carry `:parent-dispatch-id` — they belong to a single cascade (their `:dispatch-id`) and the inter-cascade lineage hangs off the cascade root.
- **Top-level dispatch.** An `:event/dispatched` event with no `:parent-dispatch-id` is a *root* of a cascade. Pair-shaped tools draw cascade trees by walking `:parent-dispatch-id` upward across `:event/dispatched` events; the per-cascade body (every other trace event in that cascade) is the slice of the trace stream sharing the cascade's `:dispatch-id`.
- **The cascade-correlation primitive.** Because the runtime emits event-at-a-time (no `:child-of` span field), `:dispatch-id` is the only intra-cascade correlation channel and `:parent-dispatch-id` is the only inter-cascade correlation channel. The pair lets tools both (a) group raw spans by cascade and (b) walk lineage between cascades, without consulting the `:rf/epoch-record` projection. Tools that prefer structured per-cascade slices read the assembled `:rf/epoch-record` (per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel)) — the raw `:dispatch-id` channel is the lower-level primitive.
- **Production elision.** Both fields ride the trace stream and are elided in production with the rest of the trace surface. The dispatch-id allocation counter and the `*current-dispatch-id*` Var read sit inside the `interop/debug-enabled?` gate in `emit!`, so the whole machinery compiles out.

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
- `:rf.machine.timer/scheduled` / `:rf.machine.timer/fired` / `:rf.machine.timer/stale-after` / `:rf.machine.timer/cancelled-on-resolution` / `:rf.machine.timer/skipped-on-server` — state-machine `:after` timer lifecycle (per [005 §Trace events](005-StateMachines.md#trace-events) and rf2-3y3y). `:scheduled` fires on initial entry-time scheduling and on every subscription-driven re-resolution; its `:tags` carry `:delay-source <:literal | :sub | :fn>` to discriminate the three delay forms (per [005 §Value shape](005-StateMachines.md#value-shape) and [005 §Dynamic delay re-resolution](005-StateMachines.md#dynamic-delay-re-resolution)). `:fired` carries `:fired? <bool>` (false ⇒ guard suppressed the transition; sibling timers continue). `:cancelled-on-resolution` fires on subscription-driven re-resolution paired with a fresh `:scheduled`. The `*/stale-*` form is the canonical naming for [§stale-detection trace events](Pattern-StaleDetection.md) — see also `:route.nav-token/stale-suppressed` below. `:skipped-on-server` fires under SSR per [005 §SSR mode](005-StateMachines.md#ssr-mode).
- `:rf.machine.invoke-all/started` / `:rf.machine.invoke-all/all-completed` / `:rf.machine.invoke-all/some-completed` / `:rf.machine.invoke-all/any-failed` — state-machine `:invoke-all` spawn-and-join lifecycle (per [005 §Spawn-and-join via `:invoke-all`](005-StateMachines.md#spawn-and-join-via-invoke-all) and rf2-6vmw). `*/started` fires after all N children have been spawned on entry to the `:invoke-all`-bearing state. `*/all-completed` fires when `:join :all` resolves; `*/some-completed` fires when `:join :any` / `{:n N}` / `{:fn ...}` resolves on the success-side; `*/any-failed` fires when `:on-any-failed` resolves. `:tags {:machine-id <id> :invoke-id <prefix-path> :child-ids ... :done ... :failed ...}` (the specific subset of tags depends on which event fires; common to all is `:machine-id` + `:invoke-id`).
- `:rf.machine.invoke/cancelled-on-join-resolution` — fires once per sibling cancelled by `:cancel-on-decision? true` (the default) when an `:invoke-all` join condition resolves and surviving siblings are torn down (per [005 §Cancel-on-decision](005-StateMachines.md#cancel-on-decision-default-true) and rf2-6vmw). `:tags {:machine-id <parent-id> :invoke-id <prefix-path> :child-id <user-id> :spawned-id <gensym'd-id> :join-event <:on-all-complete | :on-some-complete | :on-any-failed | :on-timeout>}`. The trace fires per cancelled actor; observers needing one event per join resolution use `:rf.machine.invoke-all/*-completed` / `*/any-failed` / `:rf.machine.invoke/timed-out` instead.
- ~~`:rf.machine.invoke/timed-out`~~ — RETIRED per rf2-3y3y. The pre-rf2-3y3y `:timeout-ms` slot on `:invoke` / `:invoke-all` is dropped in favour of state-level `:after`; the trace event with it. Observers wanting "this `:invoke`-bearing state's wall-clock guard fired" now consume `:rf.machine.timer/fired` on the `:invoke`-bearing state's `:after` entry — same semantic, uniform substrate. Per [005 §Wall-clock timeouts on `:invoke` — use parent state's `:after`](005-StateMachines.md#wall-clock-timeouts-on-invoke--use-parent-states-after) and [MIGRATION §M-44](MIGRATION.md#m-44-timeout-ms-removed-from-invoke--invoke-all--use-parent-states-after).
- `:route.nav-token/allocated` / `:route.nav-token/stale-suppressed` — navigation-token lifecycle (per [012 §Navigation tokens](012-Routing.md#navigation-tokens--stale-result-suppression)). `*-allocated` fires when a navigation cascade begins; `*-stale-suppressed` fires when an async result arrives carrying a now-superseded token. Same epoch idiom as the machine-`:after` timer events.
- `:rf.route/url-changed` / `:rf.route/navigation-blocked` — fragment-only URL change emission (per [012 §Fragments](012-Routing.md#fragments); distinct from the runtime event `:rf/url-changed` which fires on every URL transition) and pending-nav protocol blockage (per [012 §Navigation blocking](012-Routing.md#navigation-blocking--pending-nav-protocol)). Fragment-only navigation is reported under the same `:rf.route/url-changed` op-name as full URL changes; consumers discriminate on `:tags` (the fragment-only emission carries `:prev-fragment` and `:next-fragment` and never coincides with a `:route.nav-token/allocated` event for the same drain — see [Spec-Schemas §`:rf.route/url-changed`](Spec-Schemas.md#rftrace-event) and the [`routing/fragment-change` conformance fixture](conformance/fixtures/route-fragment-change.edn)).
- `:rf.registry/handler-registered` / `:rf.registry/handler-cleared` / `:rf.registry/handler-replaced` — registration changes (hot reload). The canonical trio: `-registered` for a fresh id, `-cleared` for an explicit removal, `-replaced` when re-registration overwrote an existing id (the typical hot-reload case).
- `:flow` — flow lifecycle and evaluation events (per [013 §Flow tracing](013-Flows.md#flow-tracing)). The op-type for the whole flow trace stream; per-flow events live under `:rf.flow/*` operations (`:rf.flow/registered`, `:rf.flow/computed`, `:rf.flow/skip`, `:rf.flow/cleared`, `:rf.flow/failed` — see [§Flow trace events](#flow-trace-events) below). Tools filter `op-type :flow` to subscribe to the whole flow stream.
- `:error` / `:warning` — universal severity discriminators for failure events. The category-specific identity lives in `:operation` (e.g. `:rf.error/handler-exception`); see [§Error contract](#error-contract) for the authoritative model.
- `:info` — informational advisories the runtime emits without warning or error severity (e.g. `:rf.http/retry-attempt` per [014 §Retry and backoff](014-HTTPRequests.md#retry-and-backoff)). Tools that filter for issues subscribe to `:warning` / `:error`; tools that surface activity timelines subscribe to `:info` as well.
- **Frame-exit machine teardown — single emit on the lifecycle channel.** When a frame's destroy walks each surviving machine snapshot, `frame.cljc` emits **one trace event per destroyed machine instance** on the unified lifecycle channel: `:op-type :rf.machine.lifecycle/destroyed`, `:operation :rf.machine.lifecycle/destroyed`. `:tags {:frame <id> :machine-id <id> :last-state <state> :reason :parent-frame-destroyed}`. The `:reason` tag discriminates *why* the actor went away — frame-exit emits `:parent-frame-destroyed`; the fx-substrate's `:rf.machine/destroyed` emit site (`lifecycle_fx.cljc`) carries the other reasons under the same `:reason` slot (`:rf.machine/finished` for natural termination, `:explicit` for `[:rf.machine/destroy <id>]`, `:parent-unmount-cascade` for parent-cascade teardown). Tools that just want "an actor instance appeared / went away" subscribe to `:op-type :rf.machine.lifecycle/*` and branch on `:reason` only when they need cause-specific routing.

    The `:reason` enum (canonical values used by the runtime):

    | `:reason` | Emitted by | Meaning |
    |---|---|---|
    | `:parent-frame-destroyed` | `frame.cljc` (`destroy-frame!`) | The actor's owning frame was destroyed; its snapshot was reaped as part of the frame-exit cascade. |
    | `:rf.machine/finished` | `lifecycle_fx.cljc` | The actor reached a `:final?` state and the runtime auto-destroyed it after firing the parent's `:on-done`. |
    | `:explicit` | `lifecycle_fx.cljc` | The actor was destroyed by an explicit `[:rf.machine/destroy <id>]` fx. |
    | `:parent-unmount-cascade` | `lifecycle_fx.cljc` | The actor was a spawned child whose parent state exited (per [005 §Cancellation cascade](005-StateMachines.md#cancellation-cascade--in-flight-rfhttpmanaged-aborts)). |

    The enum is open per [§`:tags` is the open-ended bag](#tags-is-the-open-ended-bag); future causes are additive.
- `:rf.frame/drain-interrupted` — lifecycle event emitted by `router.cljc` when the drain loop detects `(:destroyed? (:lifecycle frame))` mid-cycle and drops remaining queued events. `:op-type :frame` (the frame-lifecycle family — see `:frame/created` / `:frame/destroyed` siblings; **not** `:op-type :event`, which is reserved for "an event was dispatched"). `:tags {:frame <id> :dropped-count <int>}`. Per [002 §Edge cases worth pinning](002-Frames.md#edge-cases-worth-pinning).
- `:rf.epoch/snapshotted` / `:rf.epoch/restored` / `:rf.epoch/db-replaced` — epoch-history operations under `:op-type :rf.epoch`. `-snapshotted` fires after each drain-settle when the runtime has appended a fresh `:rf/epoch-record`; `-restored` fires after a successful `restore-epoch`; `-db-replaced` fires after a successful `reset-frame-db!` (the rf2-zq55 pair-tool write surface — see [Tool-Pair §Pair-tool writes](Tool-Pair.md#pair-tool-writes--state-injection)). Per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel). `:tags {:frame <id> :epoch-id <id> :event-id <id>?}`.
- `:rf.epoch.cb/silenced-on-frame-destroy` — listener-silencing notification emitted **once per `(frame, cb-id)` pair** when a frame previously observed by a `register-epoch-cb!` callback is destroyed (per [Tool-Pair §Surface behaviour against destroyed frames](Tool-Pair.md#surface-behaviour-against-destroyed-frames) and rf2-d656). `:op-type :rf.epoch.cb`. `:tags {:frame <id> :cb-id <id>}`. The callback registration remains in place; the trace exists so a tool whose previously-firing cb has gone silent learns *why* without polling registry state. Repeat destroys of the same frame do not re-emit; a re-registration of a same-keyed frame followed by a fresh delivery re-arms the cb's observation set so a subsequent destroy re-emits.

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
| `:rf.flow/failed` | The flow's `:output` fn threw during recompute. The exception is re-thrown after this trace fires so the router's outer catch emits the cascade-level `:rf.error/flow-eval-exception` (per [§Error contract](#error-contract)); tools see the per-flow detail here and the cascade halt there. | `:ex` (the exception), `:inputs` (the input values that were read just before the throw) |

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
(rf/register-trace-cb! key callback-fn)
;; Subscribes callback-fn to receive every trace event as it is emitted.
;; Same key replaces any previously-registered listener under that key.
;; Returns the key.
;;
;; Arguments:
;;   key         — any comparable value identifying the listener
;;                 (replaces same-key registration)
;;   callback-fn — invoked with one trace event per call.
;;                 Signature: (fn [trace-event] ...)

(rf/remove-trace-cb! key)
;; Unsubscribes the listener registered under key. Returns nil.

(rf/clear-trace-cbs!)
;; Test-time helper: drops all registered raw-trace listeners atomically.
;; Returns nil. Used by `re-frame.test-support/reset-runtime-fixture` to
;; restore a clean listener registry between tests; ordinary application
;; code SHOULD use `remove-trace-cb!` per key. The same dev-only elision
;; rules apply (production builds drop the registry entirely).
```

Conventional keys: `:my-app/recorder`, `:my-app/timing-monitor`, etc.

**Re-registration semantics.** `register-trace-cb!` called with a key already in the registry replaces the previous callback atomically — the swap from old to new happens between two emits, never mid-emit. No trace event is emitted for the replacement (the listener registry is itself dev-only metadata; mutating it does not feed the trace stream); no events delivered to the previous callback are re-delivered to the new one, and no events emitted after the swap are dropped. Hot-reload tools that re-register their listener on every code reload see exactly one stream of events with the swap point invisible to the runtime. The same semantics apply to `register-epoch-cb!` re-registration under an existing key.

**Worked example.** A minimal recorder that prints every error trace to the console:

```clojure
(rf/register-trace-cb!
  :my-app/error-logger
  (fn [trace-event]
    (when (= :error (:op-type trace-event))
      (println (:operation trace-event)
               (-> trace-event :tags :reason)))))
```

The same pattern with `register-epoch-cb!` to log one assembled cascade per drain-settle:

```clojure
(rf/register-epoch-cb!
  :my-app/cascade-logger
  (fn [epoch-record]
    (println (:event-id epoch-record)
             "→" (count (:effects epoch-record)) "fx"
             "/" (count (:sub-runs epoch-record)) "sub-runs")))
```

#### `register-epoch-cb!` — assembled-epoch listener

Alongside the raw trace stream, the framework exposes a parallel **assembled-epoch listener** API. Where `register-trace-cb!` delivers each raw event as it is emitted, `register-epoch-cb!` delivers one fully-assembled `:rf/epoch-record` (per [Spec-Schemas](Spec-Schemas.md#rfepoch-record)) per drain-settle:

```clojure
(rf/register-epoch-cb! key callback-fn)
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

(rf/remove-epoch-cb! key)
;; Unsubscribes the listener registered under key.
```

**Invocation rules** (mirrors `register-trace-cb!`):

- **Per drain-settle, not per event.** The callback fires once when the run-to-completion drain reaches an empty queue and the epoch record is committed; multi-event cascades produce one record (and one callback invocation), not one per event.
- **After commit.** The callback receives a fully-formed record with `:db-after`, `:sub-runs`, `:renders`, `:effects`, and any optional `:trace-events` populated. The record has already been appended to the frame's `epoch-history` ring buffer when the callback runs.
- **Exception isolation.** An exception thrown by an epoch callback is caught and does not propagate. One broken epoch listener cannot break the app or block other listeners (raw-trace or epoch).
- **Listener ordering** is not contract.
- **Production elision.** The epoch listener machinery is gated on the same `re-frame.interop/debug-enabled?` flag (alias of `goog.DEBUG`) as the raw-trace surface — see [§Production builds](#production-builds-zero-overhead-zero-code). Production builds elide registration, dispatch, and the epoch ring-buffer all together.

**When to use which.** `register-trace-cb!` is the right shape for tools that need fine-grained per-event activity (custom recorders, error-monitor forwarders, timing aggregators). `register-epoch-cb!` is the right shape for tools that route diagnostics off "what just happened in this cascade" — pair-shaped tools, post-mortem dashboards, anything that wants the structured `:sub-runs` / `:renders` / `:effects` projection without re-folding the raw trace stream.

The two listener APIs are independent: tools may register either, both, or neither. They share the production-elision gate but have separate listener registries; no listener of one kind can interfere with the other.

### Cascade projection (`group-cascades` / `domino-bucket`)

The raw trace stream is event-at-a-time; pair-shaped UIs (the Story trace panel, the planned Causa event-detail panel, re-frame-pair2's `cascade-of`) all want the **six-domino slice** of the stream — one record per cascade with the event vector, handler emit, fx-map emit, effects, sub-runs, and renders already split into named slots. The framework ships that projection as a pure-data function in `re-frame.trace.projection`, re-exported from `re-frame.core`:

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

Events without a `:dispatch-id` (registry-time emits, frame lifecycle, REPL evals outside a drain) collect under `:dispatch-id :ungrouped`. The returned vector is sorted by the lowest `:id` in each cascade so consumers render cascades in emission order. The projection is **pure data** — JVM and CLJS run the same code; tools wiring up post-mortem renders against `(rf/trace-buffer)` get the same output shape as live consumers reading from a `register-trace-cb!` listener.

`(rf/domino-bucket trace-event)` is the underlying classifier — returns one of `#{:event :handler :fx :effect :sub :render :other}`. Tools that want custom rollups can call it directly per event and skip `group-cascades`.

Per rf2-g6ih4 (cascade-wide `:dispatch-id`) the projection is robust against errors, fx, sub-runs, and renders that fire *inside* a drain even though they aren't `:event/dispatched` — every such event carries `:tags :dispatch-id` so they group into the cascade record automatically.

The projection is additive: new `:op-type` values that don't fit a domino slot flow through `:other` without breaking existing consumers.

### Listener invocation rules

- **Synchronous, event-at-a-time.** Every registered listener is invoked once per emitted trace event, on the runtime's emit call stack. There is no batching, debounce window, or background delivery loop. Listeners SHOULD return quickly; expensive work belongs on a tool-owned timer or rAF.
- **Events arrive in emission order.** Each listener sees trace events in the order the runtime fired them. (This is about per-listener *event* order, not order *across* listeners — see the next rule.)
- **Listener-invocation order is not contract.** When multiple listeners are registered, the order in which sibling listeners receive a given event is unspecified. Tools must not depend on order; each listener receives the same event independently. The same rule applies to `register-epoch-cb!` callbacks.
- **Exception isolation.** An exception thrown by a listener is caught and does *not* propagate to the framework or other listeners. One broken tool can't break the app or block other tools. The caught exception is logged via `re-frame.interop/log-error` (or the host equivalent) and otherwise discarded; the runtime does NOT emit a self-referential trace event for the failed listener (which would risk a re-entrant trace-emit storm). The same handling applies to exceptions thrown by an `register-epoch-cb!` callback.
- **No buffering between listeners and the runtime.** The framework does not retain a delivery buffer; the retain-N ring buffer described next is independent and exists for late-attaching tools.

### Retain-N trace ring buffer (dev-only)

In dev builds, the framework maintains a **retain-N trace ring buffer** alongside the synchronous-delivery path. The ring buffer holds the most recent N emitted trace events and is queryable. This lets pair-shaped AI tools, REPL-attached debuggers, and post-mortem dashboards read recent activity without having to be registered as a `register-trace-cb!` listener at the time the events fire.

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
- **Independent of listeners.** A tool that attaches *after* events have fired can read the most-recent N from the ring buffer to bootstrap its view; a tool that wants a continuous live feed registers a `register-trace-cb!` listener as well.
- **Production elision.** The ring buffer, like the rest of the trace surface, is compile-time eliminated in production builds (per [§Production builds](#production-builds-zero-overhead-zero-code)). `(rf/trace-buffer)` returns an empty vector in production, and the buffer itself is not allocated.
- **Depth-zero semantics.** When configured with `{:depth 0}`, the ring buffer is disabled but the surface remains live: `(rf/trace-buffer)` returns `[]`, `(rf/trace-buffer opts)` returns `[]`, and `(rf/clear-trace-buffer!)` is a no-op (returns `nil`). Synchronous-delivery to registered listeners continues to fire — only the queryable history is suppressed.
- **Lowering depth on a populated buffer.** `(rf/configure :trace-buffer {:depth N})` applied while the buffer holds more than `N` events drops the oldest events first to fit the new depth (same eviction order as the ring discipline). Raising the depth keeps existing events and grows the slot count.

Why this is a framework primitive (not a Causa-specific concern): pair-shaped tools, REPL companions, and any non-Causa consumer needs recent-history access. Locating the buffer in the framework means external tools depend on a stable framework primitive rather than on Causa's internal data structures. See [Tool-Pair §How AI tools attach](Tool-Pair.md#how-ai-tools-attach) for the full consumption pattern.

## Emitting trace events

The framework emits trace events through one entry point: `re-frame.trace/emit!`. User code may also call it (re-exported as `rf/emit-trace!`) to add custom events to the stream.

```clojure
(re-frame.trace/emit! op-type operation tags)
;; Emits one trace event with the given :op-type / :operation / :tags.
;; Returns nil. The runtime stamps :id and :time, hoists :source and
;; :recovery (when present in tags) to the top level, pushes the event
;; into the retain-N ring buffer, and synchronously invokes every
;; registered listener.
```

The shape is synchronous and side-effecting: the emit returns once every listener has been invoked. There is no span-shape machinery — events are emitted at the moment of interest with all relevant tags already populated. (For codebases migrating from a span-shaped tracing library, see [MIGRATION.md §M-26](MIGRATION.md#m-26-drift-sweep-drops--v1-surfaces-with-no-v2-equivalent-or-absorbed-by-canonical-surfaces).)

### Compile-time elision

`emit!`'s body is wrapped in `(when re-frame.interop/debug-enabled? ...)`. `debug-enabled?` is an alias of `goog.DEBUG` on CLJS (default `true` in dev, `false` in `:advanced` production builds); when the constant is `false` the closure compiler eliminates the gated branch and the call becomes a no-op. See "Production builds" below for the full mechanism.

### Where trace emission lives

The framework emits trace events from these call sites:

- `events.cljc` — `:warning :rf.warning/interceptors-in-metadata-map`; `:rf.error/effect-map-shape`; `:rf.error/effect-handler-bad-return` (per rf2-k3bj).
- `subs.cljc` — `:sub/create`, `:sub/run`; `:rf.error/no-such-sub` and `:rf.error/sub-exception` for failure paths.
- `fx.cljc` — `:event/do-fx` per drain step, `:rf.fx/handled` per dispatched fx, `:fx/override-applied`, `:warning :rf.fx/skipped-on-platform`, `:rf.error/fx-handler-exception`, `:rf.error/no-such-fx`, plus `:rf.machine/spawned` and `:rf.machine/destroyed`.
- `cofx.cljc` — `:rf.error/no-such-cofx` (emitted by `inject-cofx` when the cofx-id has no registered handler), `:warning :rf.cofx/skipped-on-platform` (emitted when a registered cofx's `:platforms` excludes the active platform; mirrors `:rf.fx/skipped-on-platform` per [011 §Effect handling on the server](011-SSR.md#effect-handling-on-the-server)).
- `router.cljc` — `:event :event` (`:run-start` and `:run-end` phases), `:event :event/dispatched`, `:event :event/db-changed`, `:rf.error/handler-exception`, `:rf.error/drain-depth-exceeded`, `:rf.error/no-such-handler`, `:warning :rf.warning/dispatch-from-async-callback-fell-through-to-default` (emitted alongside `:rf.error/no-such-handler` when a dispatch landed on `:rf/default` purely because the resolution chain fell through and the handler is missing; per rf2-o8m0), `:rf.error/dispatch-sync-in-handler`, `:rf.error/frame-destroyed`, `:rf.error/flow-eval-exception`, `:rf.frame/drain-interrupted` (lifecycle event emitted when the drain loop detects a destroyed frame mid-cycle; per [002 §Edge cases worth pinning](002-Frames.md#edge-cases-worth-pinning)).
- `frame.cljc` — `:frame/created`, `:frame/re-registered`, `:frame/destroyed`, `:rf.machine.lifecycle/destroyed`.
- `registrar.cljc` — `:rf.registry/handler-registered`, `:rf.registry/handler-replaced`, `:rf.registry/handler-cleared`, `:warning :rf.warning/missing-doc` (emitted once per `(kind, id)` pair when a `reg-*` registration omits `:doc`; per [001 §`:doc` is dev-warned when absent](001-Registration.md#doc-is-dev-warned-when-absent) and rf2-lhu1e).
- `machines.cljc` — `:rf.machine/event-received`, `:rf.machine/transition`, `:rf.machine.microstep/transition` (one per microstep on `:always`-driven cascades, per [005 §Trace events](005-StateMachines.md#trace-events)), `:rf.machine/snapshot-updated`, `:rf.machine.lifecycle/created`, `:rf.machine/system-id-bound`, `:rf.machine/system-id-released` (per [005 §Named addressing via `:system-id`](005-StateMachines.md#named-addressing-via-system-id)), `:rf.machine.timer/scheduled`, `:rf.machine.timer/fired`, `:rf.machine.timer/stale-after`, `:rf.machine.timer/skipped-on-server` (under SSR; per [005 §SSR mode](005-StateMachines.md#ssr-mode)), plus the machine-error categories.
- `routing.cljc` — `:rf.route/url-changed` (covers both full URL transitions and fragment-only navigation; consumers discriminate on `:tags`), `:rf.route/navigation-blocked`, `:route.nav-token/allocated`, `:route.nav-token/stale-suppressed`, `:rf.fx/skipped-on-platform` (route-fx platform skips), `:warning :rf.warning/route-shadowed-by-equal-score`.
- `flows.cljc` — `:rf.flow/registered`, `:rf.flow/computed`, `:rf.flow/skip`, `:rf.flow/cleared`, `:rf.flow/failed` (per [013 §Flow tracing](013-Flows.md#flow-tracing)). All carry `:op-type :flow`.
- `schemas.cljc` — `:rf.error/schema-validation-failure` (from `validate-app-db!` / `validate-event!` / `validate-cofx!` / `validate-sub-return!`).
- `spec.cljc` — `:rf.error/schema-validation-failure :where :event :source :boundary` and `:rf.warning/boundary-without-spec` (from the `:spec/validate-at-boundary` interceptor; per [010 §Production builds](010-Schemas.md#production-builds) and rf2-r2uh).
- `ssr.cljc` — `:rf.ssr/hydration-mismatch` (carries `:failing-id` to discriminate body-mismatch from head-mismatch; per [011 §Hydration-mismatch detection](011-SSR.md#hydration-mismatch-detection) and [011 §Mismatch detection — head](011-SSR.md#mismatch-detection--head)), `:warning :rf.warning/multiple-status-set`, `:warning :rf.warning/multiple-redirects`, `:rf.error/sanitised-on-projection`.
- `epoch.cljc` — `:rf.epoch/snapshotted` per drain-settle, `:rf.epoch/restored` on restore success, `:rf.epoch/db-replaced` on `reset-frame-db!` success (rf2-zq55), plus the six restore-failure categories and the two reset-frame-db! failure categories (`:rf.epoch/reset-frame-db-during-drain`, `:rf.epoch/reset-frame-db-schema-mismatch`), plus `:rf.epoch.cb/silenced-on-frame-destroy` emitted once per `(frame-id, cb-id)` pair on the destroy-cascade boundary (rf2-d656).
- `views.cljs` — `:view/render` per registered-view render (per [Spec 004 §Render-tree primitives](004-Views.md)).
- `adapter/context.cljs` — `:rf.error/frame-context-corrupted` (function-component `_currentValue` read observed a non-coercible shape; per rf2-8q66).
- `substrate/adapter.cljc` — `:warning :rf.warning/write-after-destroy` emitted by the `replace-container!` wrapper when called with a nil container (the frame was destroyed mid-drain or before a scheduled write fired; the underlying adapter's `replace-container!` is NOT invoked). Per [006 §`replace-container!`](006-ReactiveSubstrate.md#read-container-container--value-and-replace-container-container-new-value--nil) and rf2-ft2b.
- `std_interceptors.cljc` — `:rf.error/unwrap-bad-event-shape`.
- `http_managed.cljc` — `:warning :rf.http/cljs-only-key-ignored-on-jvm`, `:warning :rf.warning/decode-defaulted`, `:info :rf.http/retry-attempt`, `:info :rf.http/aborted-on-actor-destroy` (per [014 §Abort on actor destroy](014-HTTPRequests.md#abort-on-actor-destroy), rf2-wvkn), `:info :rf.http.interceptor/registered`, `:info :rf.http.interceptor/cleared`, `:error :rf.error/http-interceptor-failed` (request-side interceptor `:before` threw, per [014 §Middleware](014-HTTPRequests.md#middleware), rf2-6y3q), plus the Spec 014 failure categories.

User code can also emit traces — `re-frame.trace/emit!` is public and re-exported as `rf/emit-trace!`.

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

User-side `(rf/register-trace-cb! ...)` calls should also elide in production. Wrap them with the same predicate the framework uses:

```clojure
(when ^boolean re-frame.interop/debug-enabled?
  (rf/register-trace-cb! :my/listener callback-fn))
```

In production (`goog.DEBUG=false`), `re-frame.interop/debug-enabled?` is the constant `false`, the `when` is dead, and the entire registration is elided.

The same pattern applies to `register-epoch-cb!`, `trace-buffer`, `clear-trace-buffer!`, and `(rf/configure :trace-buffer …)` — every dev-only call site in user code should sit under the `when ^boolean re-frame.interop/debug-enabled?` guard.

### JVM builds

JVM has no `:advanced` and no compile-time DCE. The JVM half of the interop layer:

```clojure
;; src/re_frame/interop.clj
(def debug-enabled? true)
```

…hardcodes `debug-enabled?` to `true`. JVM artefacts always run the dev-side branches. Two reasons:

1. The JVM is used in re-frame2 only for headless tests, SSR, and tooling-attached REPLs (per Spec 011 §JVM-runnable view rendering and Spec 008 §JVM-runnable test suites). None of those is a production-latency hot path.
2. Trace events are how SSR errors, schema-validation failures, and hot-reload notifications surface — turning them off on the JVM would silently drop the only data channel those flows carry.

Apps that ship a production JVM artefact (a Pedestal/ring service that uses re-frame2's runtime for state) and want to disable instrumentation should rebuild the framework with `interop.clj`'s `debug-enabled?` switched to `false` (or an `alter-var-root!` at boot) — but the canonical re-frame2 dev surface is CLJS. The compile-time-elision guarantee is **CLJS-only**; on JVM the contract is "code is present, runs cheaply when its inner predicate is also off."

### Production-elision verification

The contract above is enforced by an automated test in CI:

1. `implementation/test/re_frame/elision_probe.cljs` is a probe namespace that exercises every gated surface — `register-trace-cb!`, `emit-trace!`, the trace ring buffer (`trace-buffer` / `clear-trace-buffer!` / `(configure :trace-buffer …)`), `validate-{app-db,event,sub-return,cofx}!`, `register!` / `unregister!` / `clear-kind!`, the epoch surface (`register-epoch-cb!` / `epoch-history` / `restore-epoch` / `(configure :epoch-history …)`), plus a representative `dispatch-sync` flow. The probe roots the dead-code-elimination graph at every surface so a leak surfaces in the bundle.
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

- `register-trace-cb!` / `remove-trace-cb!` — listener registration is a no-op because the gate around `trace/emit!` is constant-folded out. Even if user code registered a listener at boot (which it shouldn't, per [§User-side listener registration](#user-side-listener-registration)), nothing would ever invoke it.
- The retain-N trace ring buffer (`trace-buffer`, `clear-trace-buffer!`, `(configure :trace-buffer …)`) — pulling "the last N events from a prod session" is not supported. The buffer's `swap!` site is inside the same elision gate.
- `register-epoch-cb` and the per-drain `:rf/epoch-record` assembly — epoch projection runs inside the trace surface and elides with it.
- Every `:rf.error/*`, `:rf.warning/*`, `:rf.info/*`, `:rf.fx/*`, `:rf.ssr/*`, and `:rf.epoch/*` trace event documented in [§Error categories](#error-categories-initial-set). They are not emitted, not buffered, and not deliverable to any listener. The `:on-error` per-frame slot (per [002-Frames §`:on-error`](002-Frames.md)) is fed by the trace surface; with trace elided, registered `:on-error` callbacks do not fire in CLJS prod.
- Source-coord enrichment (`:rf.trace/trigger-handler`), `:dispatch-id` / `:parent-dispatch-id` correlation, `:origin` tagging — all ride the trace event and elide with it.
- Schema validation (`:rf.error/schema-validation-failure`) and registrar hot-reload notifications (`:rf.registry/handler-registered` and siblings) — same gate, same elision.
- The Causa-MCP server and the pair2 server (per [Tool-Pair.md](Tool-Pair.md)). These are dev-only tools that attach to the trace surface; they are not designed for, and not shippable to, production. The Causa preload artefact must not be on a production build's classpath; the pair2 server lives in its own dev-only artefact for the same reason.

### What IS available in production

Three surfaces survive elision and are the canonical production-debugging fallbacks:

1. **The Performance API channel** (per [§Performance instrumentation](#performance-instrumentation)) — gated on the independent `re-frame.performance/enabled?` `goog-define`, default off. A production build that wants timing observability flips `{:closure-defines {re-frame.performance/enabled? true}}`; the bracket sites at the four hot paths (`:event`, `:sub`, `:fx`, `:render`) emit User-Timing measure entries that any `PerformanceObserver` — including the host APM's — reads via `performance.getEntriesByType('measure')`. This is the production observability surface re-frame2 ships and supports.
2. **The SSR error-projector boundary** (per [011 §Server error projection](011-SSR.md#server-error-projection)) — on the server (JVM/SSR), `re-frame.interop/debug-enabled?` is hardcoded `true` (per [§JVM builds](#jvm-builds)), so the trace surface is live. The runtime emits structured `:rf.error/*` traces, the registered error projector consumes them, and the locked `:rf/public-error` shape is written to the HTTP response. Apps with an SSR tier get the full trace + projection pipeline server-side independent of the client-side bundle's elision.
3. **Native browser machinery** — uncaught exceptions still reach `window.onerror` / `window.onunhandledrejection`. A re-frame2 event handler that throws in production still surfaces there; what's missing is the structured `:rf.error/handler-exception` shape, the `:dispatch-id` correlation, and the `:rf.trace/trigger-handler` coord — those rode the trace surface.

### Wiring an external error monitor (Sentry, Rollbar, Honeybadger, etc.)

The dev-side integration documented at [§Composition with libraries](#composition-with-libraries-sentry-honeybadger-etc) routes structured trace events into the monitor:

```clojure
;; Dev: full structured trace, captured before the runtime's default recovery.
(rf/register-trace-cb!
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

In a production CLJS build with `goog.DEBUG=false`, the `register-trace-cb!` call and its body sit under the `(when ^boolean re-frame.interop/debug-enabled? …)` user-side guard (per [§User-side listener registration](#user-side-listener-registration)) and elide entirely. To keep production-side error reporting, an app has two options:

- **Recommended**: install the monitor's native browser SDK at the top of the bundle (`Sentry.init({...})`). It captures `window.onerror`, `window.onunhandledrejection`, and any explicit `Sentry.captureException` call wherever the app already has error-boundary plumbing. The trade-off is loss of re-frame2's structured fields (`:operation`, `:dispatch-id`, `:rf.trace/trigger-handler`, the category-specific `:tags`) — the monitor sees the bare exception, not the cascade context. This matches industry practice for non-instrumented production bundles.
- **Opt-in to keep the trace surface**: ship `:advanced` with `:closure-defines {goog.DEBUG true}`. The trace surface is preserved, the `register-trace-cb!` sample above runs, and the monitor receives full structured events. The cost is the trace machinery's bundle size (see [§Production-elision verification](#production-elision-verification) for the size delta — the control bundle is the reference measurement). This is the explicit escape hatch for apps where post-mortem fidelity outweighs bundle weight.

### Future direction (non-normative)

The asymmetry above — `:on-error` is the framework's documented runtime-error recovery slot but it rides the trace surface and elides with it — is on the open-questions list for v2. A future spec edit may carve a small always-on error-emit substrate that survives `goog.DEBUG=false` so monitor integrations get structured fields without an `:advanced`-with-`goog.DEBUG=true` build. Until then, production apps that need structured fields keep the trace surface; production apps on the elision default rely on the monitor's native capture.

## Hot path in dev builds

Dev iteration matters; you don't want trace machinery to slow ordinary feedback loops. Two hot-path costs are present in dev:

1. **Trace-event allocation** — building the trace map per emit.
2. **Listener invocation** — invoking `register-trace-cb!` callbacks once per emitted event.

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
| Consumer | `register-trace-cb!` listeners, the retain-N ring buffer, `register-epoch-cb!` | `performance.getEntriesByType('measure')`, `PerformanceObserver`, Chrome DevTools Performance |
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

The browser smoke at `examples/reagent/counter/counter-perf.spec.cjs` complements the grep: it serves the perf-on bundle, drives a real dispatch through the +/- buttons, and reads `performance.getEntriesByType('measure')` to confirm at least one entry per bucket lands. A passing grep is necessary but not sufficient; the smoke proves the four call sites actually fire under a real cascade.

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
| `register-trace-cb!` / `remove-trace-cb!` | Preserved |
| Synchronous, event-at-a-time delivery | Preserved |
| Trace event shape (`:id`, `:operation`, `:op-type`, `:time`, `:tags`) | Preserved exactly |
| `:op-type` discriminator vocabulary (`:event`, `:sub/run`, `:sub/create`, `:event/do-fx`, `:rf.machine/transition`, `:view/render`, `:fx`, `:warning`, `:error`, ...) | Preserved; new values additive |
| `:tags` for op-type-specific data (`:event-id`, `:event`, `:frame`, `:app-db-before`, `:app-db-after`, `:dispatch-id`, `:parent-dispatch-id`, `:origin`, ...) | Preserved |
| Hoisted top-level fields (`:source`, `:recovery`) | Preserved |
| `re-frame.interop/debug-enabled?` (alias of `goog.DEBUG`) | Preserved |
| Compile-time elision via `goog.DEBUG=false` + `:advanced` | Preserved |
| Public registrar query API (`handlers`/`handler-meta`/`frame-ids`/`frame-meta`/`get-frame-db`/`snapshot-of`/`sub-topology`/`sub-cache`) | See [002 §The public registrar query API](002-Frames.md#the-public-registrar-query-api) |
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
| `register-trace-cb!` / `remove-trace-cb!` | ✓ | ✓ |
| `register-epoch-cb!` / `remove-epoch-cb!` | ✓ | ✓ |
| Trace ring buffer (`trace-buffer`) | ✓ | ✓ |
| Hot-reload trace events | ✓ | ✓ |
| Performance API instrumentation (`rf:event:*` / `rf:sub:*` / `rf:fx:*` / `rf:render:*` measures) | ✗ | ✓ (default-off; see [§Performance instrumentation](#performance-instrumentation)) |
| Causa panel itself | ✗ | ✓ |
| re-frame-pair attachment | ✓ | ✓ |

Trace data is just data; both platforms emit it during dev. The Performance API bridge is browser-specific; everything else works headless.

## Error contract

Errors that occur during runtime execution are emitted as **structured trace events**, with a defined `:op-type` and a Malli-schemed `:tags` payload. This satisfies AI-first property P7 (machine-readable errors) and gives every consumer of the trace stream a consistent error surface.

This section is the **authoritative model** for re-frame2's error taxonomy. Per-feature specs (010, 011, 012, etc.) reference categories defined here; the `Error categories` table below is the single source of truth for category names, payload shapes, and recovery defaults. Two axes carry the structured information:

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

For `:rf.fx/handled` specifically: the slot carries the **fx handler's** own registration coord (not the enclosing event handler that produced the `:fx` vector). The runtime rebinds `*current-trigger-handler*` to the fx handler's meta around the fx body's invocation and the success-path emit that follows, so consumer tools jump to the `reg-fx` site — where the fx's logic actually lives — not the event handler upstream. Reserved fx-ids (`:dispatch`, `:dispatch-later`, `:rf.fx/reg-flow`, `:rf.fx/clear-flow`) have no registration site of their own; their `:rf.fx/handled` traces carry the enclosing event handler's coord (the outer binding).

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

The mechanism is "compile-time map + dynamic-var bind + emit-error read." The macro produces a literal map at compile time; the runtime binds `re-frame.trace/*current-call-site*` around the underlying `*`-fn call (or threads the value through the dispatch envelope so `process-event!` binds it for the handler chain); `emit-error!` reads the Var and hoists it onto the emitted event when bound. No new namespace or registry; consumer access is `(:rf.trace/call-site event)`.

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

### Error categories (initial set)

| `:operation` / category | Meaning | Category-specific `:tags` |
|---|---|---|
| `:rf.error/handler-exception` | An event handler threw | `:event` (vector), `:handler-id`, `:exception-message`, `:exception-data?` |
| `:rf.error/machine-action-exception` | A machine action body threw during a transition (per [005 §Errors](005-StateMachines.md#errors) and [Cross-Spec-Interactions §11](Cross-Spec-Interactions.md#11-machine-action-throws)). Distinct from `:rf.error/handler-exception`: the machine layer catches the throw and emits the machine-scoped category instead, so consumers see exactly one error per failure with full machine context | `:machine-id`, `:action-id`, `:state-path`, `:transition`, `:event`, `:exception-message`, `:exception-data?` |
| `:rf.error/fx-handler-exception` | A registered fx threw during effect resolution | `:fx-id`, `:fx-args`, `:exception-message` |
| `:rf.error/sub-exception` | A subscription's computation threw | `:sub-query`, `:sub-id`, `:exception-message` |
| `:rf.error/no-such-sub` | A subscription's `:<-` input refers to an unregistered sub | `:sub-id`, `:unresolved-input`, `:resolved-inputs` |
| `:rf.error/schema-validation-failure` | A `:spec`-validated value failed validation | `:where` (`:event`/`:sub-return`/`:app-db`/`:fx-args`/...), `:path`, `:value`, `:explanation` (Malli explanation map) |
| `:rf.error/drain-depth-exceeded` | The run-to-completion drain hit its depth limit | `:depth`, `:queue-size`, `:last-event` |
| `:rf.error/no-such-handler` | A registrar-shaped lookup missed. Covers three distinct failure modes, discriminated by the **`:kind` tag** (mandatory on every emit): (1) `:kind :event` — a `dispatch` / `dispatch-sync` arrived with no registered event handler (emitted by router.cljc); (2) `:kind :frame` — a Tool-Pair surface (`restore-epoch`, `reset-frame-db!`) addressed a frame-id that is not in the frame registrar (emitted by epoch.cljc; see [Tool-Pair §Surface behaviour against destroyed frames](Tool-Pair.md#surface-behaviour-against-destroyed-frames)); (3) `:kind :route` — `:rf.route/handle-url-change` (or a `route-url` caller) saw a URL that matched no registered `:path` pattern (emitted by routing.cljc; see [012 §Route-not-found](012-Routing.md#route-not-found--rfroutenot-found-canonical) and the default-projector mapping at [011 §Default projector](011-SSR.md)). Consumers route on `:kind` for per-mode handling; tools that want a single "registrar miss" filter match the operation keyword alone | `:kind` (one of `:event`, `:frame`, `:route` — mandatory), plus mode-specific keys: `:event` + `:event-id` + `:frame` (`:kind :event`); `:frame` (`:kind :frame`); `:url` + `:frame` (`:kind :route`). All three modes also carry `:recovery :replaced-with-default` |
| `:rf.error/dispatch-sync-in-handler` | `dispatch-sync` was called from inside an event handler's interceptor pipeline (use `:fx [[:dispatch event]]` instead — see [002 §dispatch-sync](002-Frames.md#dispatch-sync)) | `:event`, `:enclosing-event`, `:enclosing-frame` |
| `:rf.error/effect-map-shape` | A `reg-event-fx` handler returned a top-level effect-map key other than `:db` / `:fx` (per [MIGRATION §M-8](MIGRATION.md#m-8-effect-map-keys-consolidated--only-db-and-fx-at-the-top-level)). The runtime drops the offending key and emits one trace per offending key; legal `:db` / `:fx` keys still apply | `:failing-id` (event-id), `:event-id`, `:event` (vector), `:offending-key`, `:value`, `:reason` |
| `:rf.error/effect-handler-bad-return` | A `reg-event-fx` handler returned a value that is neither a map nor `nil` (e.g. a vector, number, string, keyword — typically a typo or thinko). Without a map the runtime cannot extract `:db` / `:fx` and cannot guess the handler's intent, so the dispatch is treated as a no-op. `nil` remains the documented legal no-op and does not trigger this trace. Per rf2-k3bj. Emitted by `events.cljc`'s `fx-handler->interceptor` | `:event-id` (first of the event vector, when vector-shaped), `:event` (vector), `:returned` (the offending value), `:returned-type` (the runtime type), `:reason`, `:recovery :no-recovery` |
| `:rf.error/override-fallthrough` | An override was specified but no matching id existed | `:overrides-map`, `:looked-up-id` |
| `:rf.fx/handled` | An fx was successfully dispatched (the runtime reached the fx and either ran the registered handler without exception or completed the reserved-fx-id action). Emitted by `re-frame.fx/handle-one-fx` on the success path so the `:rf/epoch-record` `:effects` projection captures one entry per dispatched fx (per [Spec-Schemas §`:rf/epoch-record`](Spec-Schemas.md#rfepoch-record)) | `:fx-id`, `:fx-args`, `:frame` |
| `:rf.fx/skipped-on-platform` | An fx was skipped because its `:platforms` excluded the active platform (per [011](011-SSR.md)) | `:fx-id`, `:fx-args`, `:platform`, `:registered-platforms` |
| `:rf.cofx/skipped-on-platform` | A cofx injection was skipped because its `:platforms` excluded the active platform (per [011](011-SSR.md)). Mirrors `:rf.fx/skipped-on-platform`; the cofx's handler-fn is NOT invoked and no value is injected into `:coeffects`. Emitted by `re-frame.cofx/inject-cofx` after registry lookup succeeds but the platform predicate rejects. `:op-type :warning`, `:recovery :skipped` | `:cofx-id`, `:cofx-value` (only when the 2-arity `inject-cofx` supplied a per-call value), `:frame`, `:platform`, `:registered-platforms` |
| `:rf.ssr/hydration-mismatch` | First client render diverges from server-supplied render-tree, OR the client-computed head model differs from the server-supplied head. The `:failing-id` discriminator routes the two cases (`:rf/hydrate` for the body, `:rf.ssr/head-mismatch` for the head). Per [011 §Hydration-mismatch detection](011-SSR.md#hydration-mismatch-detection) and [011 §Mismatch detection — head](011-SSR.md#mismatch-detection--head) | `:server-hash`, `:client-hash`, `:failing-id`, `:first-diff-path?` (body), `:head-id` (head) |
| `:rf.warning/plain-fn-under-non-default-frame-once` | A plain (non-`reg-view`) Reagent fn rendered under a non-default frame; routed to `:rf/default`. Emitted at most once per `(component-id, non-default-frame-id)` pair — see [004 §Plain Reagent fns](004-Views.md) | `:fn-name`, `:rendered-under`, `:routed-to` |
| `:rf.warning/dispatch-from-async-callback-fell-through-to-default` | A `dispatch` resolved to `:rf/default` purely because the resolution chain fell through (no `:frame` opt supplied, dynamic `*current-frame*` unbound, adapter React-context value unresolvable) AND no handler for that event-id exists on `:rf/default`. The canonical trigger is a `dispatch` from an async callback (`setTimeout`, `addEventListener`, `requestAnimationFrame`, `Promise.then`) attached inside a view body — the surrounding frame-context binding does not survive the async escape (per [002 §Dispatches issued from inside a handler body](002-Frames.md#dispatches-issued-from-inside-a-handler-body)). Suppressed in single-frame apps (only `:rf/default` registered) — the footgun requires at least one non-default sibling frame. Emitted alongside the existing `:rf.error/no-such-handler` error; the warning carries the specific diagnostic with the recommended fixes. No suppression cache — the warning fires every time the conditions match. Per rf2-o8m0 | `:event` (the dispatched event vector), `:event-id` (first of the vector), `:routed-to` (`:rf/default`), `:detected-at` (wall-clock ms), `:reason` |
| `:rf.warning/cross-frame-dispatch-sync-during-drain` | A `dispatch-sync!` was issued against a target frame while a *different* frame is mid-drain (same-frame reentry is already rejected as `:rf.error/dispatch-sync-in-handler`). The cross-frame case is not rejected — frames are independent state machines per [002 §Run-to-completion §Rules rule 1](002-Frames.md#rules) — but the cascades interleave (the target frame drains to settled while the caller's frame is still in flight, then the caller continues), which is rarely the caller's intent. Surfaced for observability tools; the dispatch proceeds. Per [002 §Cross-frame `dispatch-sync`](002-Frames.md#cross-frame-dispatch-sync-during-a-sibling-drain-warns-but-proceeds) and rf2-fp97 | `:caller-frame` (the frame read from `*current-frame*`, or `:rf/none` when unbound), `:target-frame` (the `dispatch-sync!`'s target), `:other-frame` (an arbitrary mid-drain sibling — typically the caller's frame), `:event` (the dispatched event vector), `:reason` |
| `:rf.warning/no-clock-configured` | A timing-sensitive substrate feature (e.g. state-machine `:after` per [005 §Delayed `:after` transitions](005-StateMachines.md#delayed-after-transitions)) was exercised on a host whose `re-frame.interop` clock primitives (`now-ms` / `schedule-after!` / `cancel-scheduled!`) weren't wired up. The runtime falls back to the host-native clock if available; this advisory surfaces so tests / agents can spot the missing wiring | `:feature` (e.g. `:rf.machine/after`), `:fallback` (the host-native clock used) |
| `:rf.error/duplicate-url-binding` | A second frame attempted `:url-bound? true` while another already owns the URL. Per [012 §Multi-frame routing](012-Routing.md#multi-frame-routing) | `:existing-frame`, `:offending-frame` |
| `:rf.error/system-id-collision` | A spawn whose `:system-id` was already bound in the per-frame `[:rf/system-ids]` reverse index displaced the previous binding. Last-write-wins, matching `reg-event-fx` re-registration semantics. Per [005 §Named addressing via `:system-id`](005-StateMachines.md#named-addressing-via-system-id) and rf2-suue | `:frame`, `:system-id`, `:existing-machine` (the displaced gensym'd id), `:rebound-to` (the new gensym'd id) |
| `:rf.warning/multiple-status-set` | Two or more `:rf.server/set-status` calls in the same request drain. Last-write-wins; advisory for finding the conflicting handlers. Per [011 §Multiple-status policy](011-SSR.md#multiple-status-policy) | `:writes` (vector of `{:status :handler-id :event}` per write), `:final-status` |
| `:rf.warning/multiple-redirects` | Two or more `:rf.server/redirect` calls in the same request drain. Last-write-wins. Per [011 §Redirect precedence](011-SSR.md#redirect-precedence) | `:writes` (vector), `:final-redirect` |
| `:rf.warning/interceptors-in-metadata-map` | A `reg-event-*` registration carried `:interceptors` inside its metadata-map; the chain is silently dropped. Per [Conventions §`:interceptors` is positional, not metadata](Conventions.md#interceptors-is-positional-not-metadata-reg-event-) and rf2-bbea | `:reg-fn` (the fn's name as a string), `:id`, `:offending-keys`, `:reason` |
| `:rf.warning/missing-doc` | A `reg-*` registration's metadata-map carried no `:doc` (or `:doc nil`, or `:doc ""`). The registration completes; the warning is the dev-time nudge toward documented handlers. Emitted at most once per `(kind, id)` pair within a runtime process (suppression cache resets on frame destroy, matching the other one-shot warnings). Production builds elide the check entirely via `goog.DEBUG`. Per [001 §`:doc` is dev-warned when absent](001-Registration.md#doc-is-dev-warned-when-absent) and rf2-lhu1e | `:kind` (one of the canonical registry kinds), `:id` (the registered id), `:source-coords` (the captured `:rf/source-coord-meta` sub-map, when available), `:reason` |
| `:rf.warning/boundary-without-spec` | The `:spec/validate-at-boundary` interceptor (per [010 §Production builds](010-Schemas.md#production-builds)) was attached to an event handler that carries no `:spec` metadata. The interceptor cannot validate without a schema; the dispatch passes through unchecked. Emitted at most once per `event-id` (suppression cache reset across frame destruction). Per [010 §Production builds](010-Schemas.md#production-builds) and rf2-r2uh | `:event-id`, `:event` (vector), `:reason` |
| `:rf.error/sanitised-on-projection` | The active error projector threw or returned a non-`:rf/public-error` shape; the runtime fell back to the locked generic-500 public shape. Per [011 §Where sanitisation happens — before render](011-SSR.md#where-sanitisation-happens--before-render) | `:projector-id`, `:original-operation`, `:projection-failure-reason` |
| `:rf.epoch/restore-unknown-epoch` | `restore-epoch` was called with an `epoch-id` that is not in the frame's current epoch history (either never recorded or aged out by `:depth`). Per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel) | `:frame`, `:epoch-id`, `:history-size` |
| `:rf.epoch/restore-schema-mismatch` | The recorded `:db-after` no longer validates against the currently-registered `app-schemas` set (a schema was added, tightened, or replaced since the snapshot was taken). Per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel) | `:frame`, `:epoch-id`, `:schema-digest-recorded`, `:schema-digest-current`, `:failing-paths` |
| `:rf.epoch/restore-missing-handler` | The recorded `app-db` references a registered-id (e.g. an active machine at `[:rf/machines <id>]`, a registered route currently in `:rf/route`) that is no longer present in the registrar. Per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel) | `:frame`, `:epoch-id`, `:missing` (vector of `{:kind :id}`) |
| `:rf.epoch/restore-version-mismatch` | The frame's recorded `:rf/snapshot-version` (per [Spec-Schemas §`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot)) is incompatible with the currently-loaded machine definition. Per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel) | `:frame`, `:epoch-id`, `:machine-id`, `:version-recorded`, `:version-current` |
| `:rf.epoch/restore-during-drain` | `restore-epoch` was called while the frame's run-to-completion drain is still in flight (per [002 §Run-to-completion dispatch](002-Frames.md#run-to-completion-dispatch-drain-semantics)). Restore is rejected; the user retries after settle. Per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel) | `:frame`, `:epoch-id` |
| `:rf.epoch/reset-frame-db-during-drain` | `reset-frame-db!` was called while the frame's drain was still running. Pair-tool injection is rejected; the caller retries after settle. Per [Tool-Pair §Pair-tool writes](Tool-Pair.md#pair-tool-writes--state-injection) (rf2-zq55) | `:frame` |
| `:rf.epoch/reset-frame-db-schema-mismatch` | `reset-frame-db!` was called with a `new-db` value that fails the frame's currently-registered `app-schema` set. The injection is rejected; `app-db` is unchanged. Per [Tool-Pair §Pair-tool writes](Tool-Pair.md#pair-tool-writes--state-injection) (rf2-zq55) and [010 §Per-frame schemas](010-Schemas.md#per-frame-schemas) | `:frame`, `:failing-paths` |
| `:rf.error/no-such-fx` | A dispatched fx-id has no registered handler (and was not redirected by `:fx-overrides`). Per [002 §`:fx` ordering and atomicity guarantees](002-Frames.md#fx-ordering-and-atomicity-guarantees). Emitted by `re-frame.fx/handle-one-fx` after override resolution and reserved-id matching both miss | `:fx-id`, `:fx-args`, `:frame` |
| `:rf.error/no-such-cofx` | An `inject-cofx` interceptor referenced a cofx-id with no registered handler. Per [002 §Effects and coeffects](002-Frames.md). Emitted by `re-frame.cofx/inject-cofx` when registry lookup misses. Sibling interceptors continue; the ctx flows through unchanged | `:cofx-id`, `:cofx-value` (only when the 2-arity `inject-cofx` was used), `:event-id` (the event that ran the offending interceptor chain, when available) |
| `:rf.error/frame-destroyed` | A `dispatch` / `dispatch-sync` / `subscribe` arrived against a frame whose `(:lifecycle frame-record)` carries `:destroyed? true`. Per [002 §Frame lifecycle](002-Frames.md#frame-lifecycle). The router rejects the call; for `subscribe` the result is `nil`. Emitted from router.cljc and subs.cljc | `:frame`, `:event` (when called from dispatch), `:query-v` (when called from subscribe) |
| `:rf.error/flow-eval-exception` | A flow's `:output` fn threw during the recompute walk inside an event handler's interceptor pipeline (per [013 §Flow tracing](013-Flows.md#flow-tracing)). Distinct from `:rf.flow/failed`, which is the per-flow op-type-`:flow` trace; this is the cascade-level error event the router emits when the throw escapes the flow walk | `:frame`, `:event`, `:exception` |
| `:rf.error/unwrap-bad-event-shape` | The `:rf/unwrap` interceptor saw an event vector that does not conform to the expected `[event-id payload-map]` shape (per [Conventions §Unwrap interceptor](Conventions.md)) | `:event`, `:expected` (the contract string) |
| `:rf.error/machine-raise-depth-exceeded` | A machine action's `:raise` cascade exceeded its depth limit (default 16). Per [005 §Bounded depth](005-StateMachines.md#bounded-depth). The cascade halts; the snapshot is not committed | `:machine-id`, `:depth` |
| `:rf.error/machine-always-depth-exceeded` | A machine's `:always` microstep loop exceeded its depth limit (default 16). Per [005 §Bounded depth](005-StateMachines.md#bounded-depth). The cascade halts; the snapshot is not committed | `:machine-id`, `:depth`, `:path` (the visited-states vector) |
| `:rf.error/machine-unresolved-guard` | A machine's `:guard` reference is a keyword that does not resolve in the machine's `:guards` map. Per [005 §Guards](005-StateMachines.md#guards) and [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table). Surfaced at registration time (registration fails) and as a fallback at transition time | `:guard` (the unresolved keyword), `:machine-id` |
| `:rf.error/machine-unresolved-action` | A machine's `:action` reference is a keyword that does not resolve in the machine's `:actions` map. Per [005 §Actions](005-StateMachines.md#actions) and [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table). Surfaced at registration time (registration fails) and as a fallback at transition time | `:action` (the unresolved keyword), `:machine-id` |
| `:rf.error/machine-bad-guard-form` | A machine's `:guard` value is neither a keyword nor a fn (per [005 §Guards](005-StateMachines.md#guards)). Surfaced at registration time | `:guard` (the offending value) |
| `:rf.error/machine-bad-action-form` | A machine's `:action` value is neither a keyword nor a fn (per [005 §Actions](005-StateMachines.md#actions)). Surfaced at registration time | `:action` (the offending value) |
| `:rf.error/machine-bad-state-form` | A snapshot's `:state` is neither a keyword nor a vector path (per [005 §State paths](005-StateMachines.md)). Surfaced at runtime when normalising the snapshot's state | `:state` (the offending value) |
| `:rf.error/machine-bad-on-clause` | A state-node's `:on <event-id>` value is not one of the four legal shapes (keyword target, vector path target, vector of guarded transition maps, or single transition map; per [005 §Transitions](005-StateMachines.md)). Surfaced at registration time | `:value` (the offending shape) |
| `:rf.error/machine-action-wrote-db` | A machine action's effect map contained `:db`. Per [005 §Hard-disallow `:db`](005-StateMachines.md). The runtime drops the `:db` key; remaining effects flow through | `:machine-id`, `:action-id`, `:state-path`, `:offending-value` |
| `:rf.error/machine-grammar-not-in-v1` | A machine definition uses a v1-out-of-scope grammar feature (e.g. `:type :parallel`, `:history`) that the implementation does not claim per the [005 §Capability matrix](005-StateMachines.md#capability-matrix). Registration is rejected | `:machine-id`, `:feature` (the unsupported key), `:substitute` (pointer to the recommended pattern) |
| `:rf.error/machine-unhandled-event` | An event arrived at a machine and no transition matched at any state along the active path. Per [005](005-StateMachines.md). Advisory; the snapshot is unchanged | `:machine-id`, `:event`, `:state` |
| `:rf.error/machine-state-not-in-definition` | A snapshot's `:state` references a state-id that is not declared in the machine's `:states` definition (e.g. a snapshot from an older version of the machine). Per [005](005-StateMachines.md) | `:machine-id`, `:state` |
| `:rf.error/machine-snapshot-version-mismatch` | A persisted machine snapshot's `:rf/snapshot-version` is incompatible with the currently-loaded machine definition (per [Spec-Schemas §`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot)). Distinct from `:rf.epoch/restore-version-mismatch`, which is the epoch-history restore path | `:machine-id`, `:version-recorded`, `:version-current` |
| `:rf.error/machine-always-self-loop` | An `:always` entry's `:target` resolves to the declaring state itself with the same `:guard` reference (or no guard). Per [005 §Self-loop forbidden at registration](005-StateMachines.md#self-loop-forbidden-at-registration). Registration is rejected | `:state` (the declaring state-keyword), `:machine-id` |
| `:rf.error/machine-compound-state-missing-initial` | A compound state declares `:states` but no `:initial`. Per [005 §Initial-state cascading](005-StateMachines.md#initial-state-cascading). Registration is rejected | `:machine-id`, `:state` |
| `:rf.error/no-such-route` | A `route-url` call (or one of its callers) addressed a `:route-id` that is not in the routing registrar (per [012](012-Routing.md)) | `:route-id` |
| `:rf.error/missing-route-param` | A `route-url` build-from-pattern call did not supply a value for a required path parameter (per [012 §URL building](012-Routing.md)) | `:param` (the missing param keyword), `:route-id` |
| `:rf.error/bad-app-schemas-arg` | `app-schemas` was called with a non-keyword, non-map, non-nil argument (per [010 §App-db schemas](010-Schemas.md)) | `:received` (the offending value), `:expected` (the contract string) |
| `:rf.error/unknown-preset` | `reg-frame` metadata's `:preset` value is not in the closed set `#{:default :test :story :ssr-server}` (per [Spec-Schemas §`:rf/preset-expansion`](Spec-Schemas.md#rfpreset-expansion)) | `:preset` (the offending value), `:valid` (the closed set) |
| `:rf.error/adapter-already-installed` | A second `install-adapter!` call was made without an intervening `dispose-adapter!` (per [006 §Single adapter per process](006-ReactiveSubstrate.md#single-adapter-per-process)) | `:installed` (the existing adapter), `:attempted` (the offending second adapter) |
| `:rf.error/no-adapter-specified` | `(rf/init! …)` was called with no args, nil, or a non-map argument (e.g. a keyword). The only legal call shape is `(rf/init! adapter-map)` — require the adapter ns and pass its `adapter` Var, e.g. `(rf/init! reagent/adapter)`. Per [006 §Adapter selection at boot](006-ReactiveSubstrate.md#adapter-selection-at-boot) and rf2-agql. Surfaced as a thrown ex-info, not a trace | `:where` (`'init!`), `:received` (when nil/keyword/non-map), `:expected`, `:recovery` (`:no-recovery`), `:reason` |
| `:rf.error/render-on-headless-adapter` | `render` was called on the plain-atom (JVM/SSR) adapter, which only supports `render-to-string` (per [006](006-ReactiveSubstrate.md)) | `:reason` |
| `:rf.error/no-hiccup-emitter-bound` | `render-to-string` was called before the SSR namespace bound the hiccup emitter via `set-hiccup-emitter!` (per [011](011-SSR.md)) | `:reason`, `:render-tree` |
| `:rf.error/frame-context-corrupted` | A function-component frame-id read (`_currentValue` on the shared React context) observed a value `coerce-context-value` cannot resolve to a frame keyword — nil, false, a number, an empty string, or a JS object. Real-world triggers: a subtree rendered through an unwrapped portal, a Provider authored with a non-keyword `:value`, or a library mutating `_currentValue` externally. Per [006 §Frame-provider via React context](006-ReactiveSubstrate.md) and rf2-8q66 | `:received` (the offending value), `:type` (a short keyword tag — `:nil` / `:boolean` / `:number` / `:string` / `:empty-string` / `:keyword` / `:symbol` / `:map` / `:vector` / `:sequential` / `:collection` / `:fn` / `:js-object`), `:reason` |
| `:rf.error/flow-cycle` | A flow registration introduced a cycle in the flow-dependency graph (per [013 §Topological ordering](013-Flows.md)). Registration is rejected | `:cycle` (the offending flow ids) |
| `:rf.error/flow-missing-id` | A `reg-flow` call's flow map omitted `:id` (per [013](013-Flows.md)) | `:flow` (the offending map) |
| `:rf.error/flow-bad-inputs` | A `reg-flow` call's flow `:inputs` was not a vector of paths (per [013](013-Flows.md)) | `:flow`, `:reason` |
| `:rf.error/flow-bad-output` | A `reg-flow` call's flow `:output` was not a fn (per [013](013-Flows.md)) | `:flow`, `:reason` |
| `:rf.error/flow-bad-path` | A `reg-flow` call's flow `:path` was not a vector (per [013](013-Flows.md)) | `:flow`, `:reason` |
| `:rf.error/flows-artefact-missing` | A flow API (`reg-flow`, `clear-flow`, the flow fxs) was called but the optional `day8/re-frame2-flows` artefact is not on the classpath. Per [MIGRATION §M-31 artefact splits](MIGRATION.md). Surfaced as a thrown ex-info, not a trace | `:where` (the calling fn), `:reason` |
| `:rf.error/ssr-artefact-missing` | An SSR API (`render-to-string`, `render-tree-hash`, `reg-error-projector`, `project-error`) was called but the optional `day8/re-frame2-ssr` artefact is not on the classpath. Surfaced as a thrown ex-info, not a trace | `:where` (the calling fn), `:reason` |
| `:rf.error/routing-artefact-missing` | A routing API (`reg-route`, `match-url`, `route-url`) was called but the optional `day8/re-frame2-routing` artefact is not on the classpath. Surfaced as a thrown ex-info, not a trace | `:where` (the calling fn), `:reason` |
| `:rf.error/schemas-artefact-missing` | A schemas API (`reg-app-schema`) was called but the optional `day8/re-frame2-schemas` artefact is not on the classpath. Surfaced as a thrown ex-info, not a trace | `:where` (the calling fn), `:path`, `:reason` |
| `:rf.error/machines-artefact-missing` | A machines API (`reg-machine`, `reg-machine*`) was called but the optional `day8/re-frame2-machines` artefact is not on the classpath. Surfaced as a thrown ex-info, not a trace | `:where` (the calling fn), `:machine-id`, `:reason` |
| `:rf.error/http-artefact-missing` | A managed-HTTP API was called but the optional `day8/re-frame2-http` artefact (per [014 §Implementation status](014-HTTPRequests.md#implementation-status)) is not on the classpath. Surfaced as a thrown ex-info, not a trace | `:where` (the calling fn), `:reason` |
| `:rf.warning/route-shadowed-by-equal-score` | A `reg-route` registered a pattern whose [`:rf/route-rank`](Spec-Schemas.md#rfroute-rank) tuple equals an already-registered pattern's; the new route shadows the old per stable sort order (per [Spec-Schemas §`:rf/route-rank`](Spec-Schemas.md#rfroute-rank) and [012 §Route ranking algorithm](012-Routing.md#route-ranking-algorithm)) | `:route-id` (the new id), `:shadowed` (the displaced id) |
| `:rf.warning/decode-defaulted` | A managed-HTTP request fell through to the default `:auto` decode pipeline because no `:decode` was supplied (per [014 §Default decode](014-HTTPRequests.md)). Informational; the auto-decode is supported | `:request-id`, `:url`, `:content-type`, `:resolved-decoder` |
| `:rf.warning/write-after-destroy` | The substrate adapter's `replace-container!` was called with a nil container — the frame was likely destroyed mid-drain or before a scheduled write fired. Per [006](006-ReactiveSubstrate.md) | `:reason` |
| `:rf.http/cljs-only-key-ignored-on-jvm` | A managed-HTTP request supplied a CLJS-only request key (`:mode`, `:cache`, `:referrer`, `:integrity`) that the JVM transport cannot honour. The key is ignored. Per [014](014-HTTPRequests.md). `:op-type :warning` | `:key`, `:url` |
| `:rf.http/retry-attempt` | A managed-HTTP attempt failed with a retryable category and the runtime is scheduling another attempt. Per [014 §Retry and backoff](014-HTTPRequests.md#retry-and-backoff). `:op-type :info` | `:request-id`, `:url`, `:attempt`, `:max-attempts`, `:failure` (a `:rf.http/*` failure-category map), `:next-backoff-ms` |
| `:rf.http/aborted-on-actor-destroy` | A managed-HTTP request was aborted because the spawned state-machine actor that issued it was destroyed (parent state exit, parent's `:after` firing, `:invoke-all` cancel-on-decision, frame destroy, or imperative `[:rf.machine/destroy]`). The reply lands as a standard `:rf.http/aborted` failure with `:reason :actor-destroyed`. Per [014 §Abort on actor destroy](014-HTTPRequests.md#abort-on-actor-destroy) and [005 §Cancellation cascade — in-flight `:rf.http/managed` aborts](005-StateMachines.md#cancellation-cascade--in-flight-rfhttpmanaged-aborts) (rf2-wvkn). `:op-type :info` | `:request-id` (when set), `:actor-id` (the destroyed spawned-actor address), `:url` |
| `:rf.http.interceptor/registered` | A `reg-http-interceptor` succeeded on a frame's request-side middleware chain. Per [014 §Middleware](014-HTTPRequests.md#middleware) (rf2-6y3q). `:op-type :info` | `:frame`, `:id` |
| `:rf.http.interceptor/cleared` | A `clear-http-interceptor` removed an existing interceptor slot (no trace fires for clear-of-unknown-id). Per [014 §Middleware](014-HTTPRequests.md#middleware) (rf2-6y3q). `:op-type :info` | `:frame`, `:id` |
| `:rf.error/http-interceptor-failed` | A request-side interceptor's `:before` fn threw. The runtime emits this category, then re-throws — `re-frame.fx` catches the re-throw and emits the cascade-level `:rf.error/fx-handler-exception`; the request is NOT dispatched. Per [014 §Middleware §Failure mode](014-HTTPRequests.md#failure-mode) (rf2-6y3q) | `:frame`, `:interceptor-id`, `:url`, `:cause` |
| `:rf.error/http-bad-interceptor` | `reg-http-interceptor` was called with an invalid interceptor map (missing `:id`, non-keyword `:id`, or non-fn `:before`). Surfaced as a thrown ex-info from the registration call, not a trace. Per [014 §Middleware](014-HTTPRequests.md#middleware) (rf2-6y3q) | `:where` (`'reg-http-interceptor`), `:received`, `:reason` |
| `:route.nav-token/stale-suppressed` | An async result arrived carrying a `:nav-token` that no longer matches the active route's token; the result is silently suppressed. Per [012 §Navigation tokens](012-Routing.md#navigation-tokens--stale-result-suppression). `:op-type :error` (the suppression is the failure mode the consumer needs to see) | `:carried-token`, `:current-token`, `:event-id` |
| `:rf.frame/drain-interrupted` | A frame's drain loop detected `(:destroyed? (:lifecycle frame))` mid-cycle; remaining queued events are dropped. Per [002 §Edge cases worth pinning](002-Frames.md#edge-cases-worth-pinning). Lifecycle event, not error-shaped: `:op-type :frame` (per the `:frame/*` lifecycle family), no `:recovery` | `:frame`, `:dropped-count` |

`:rf.fx/skipped-on-platform` and `:rf.cofx/skipped-on-platform` are technically *warnings* not errors, but they ride the same envelope and route through the same listener path; consumers can branch on `:op-type` (`:warning` vs `:error`) if they want to distinguish.

### Schemas

Each category's `:tags` shape is registered as a Malli schema so consumers can validate without ad-hoc parsing. The full set of per-category `:tags` schemas is canonicalised in [Spec-Schemas §Per-category `:tags` schemas](Spec-Schemas.md#per-category-tags-schemas) — one schema per category enumerated in the [§Error categories table](#error-categories-initial-set) above. Two examples (the rest follow the same shape):

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
   [:category    [:= :rf.error/schema-validation-failure]]
   [:failing-id  :keyword]
   [:reason      :string]
   [:where       [:enum :event :sub-return :app-db :fx-args :cofx-args :on-create]]
   [:path        [:vector :any]]
   [:value       :any]
   [:explanation :any]])                         ;; Malli explanation shape

;; ... and so on for each category — see Spec-Schemas for the full set.
```

Pattern-level: every implementation registers an equivalent set of schemas. The category vocabulary is stable and additive — new categories can be added but existing ones cannot be renamed or removed.

### Server error projection — public boundary

For SSR specifically, the structured trace event is the **internal** record (rich, full detail, monitor-bound) and a separate **public projection** is written to the HTTP response (sanitised, client-safe). The internal trace event is **never** serialised to the client. The projection mechanism is owned by [011 §Server error projection](011-SSR.md#server-error-projection); the trace stream is unchanged by it. Tools that want full error detail subscribe via `register-trace-cb!` as usual; the response carries only the locked `:rf/public-error` shape.

The runtime emits `:rf.error/sanitised-on-projection` (above) when the projector itself fails, so monitor dashboards see when the public boundary fell back to the generic-500 shape.

### Recovery contract

The `:recovery` field on the trace event tells consumers (dev panels, error-monitor integrations, tooling) what the runtime did:

- `:no-recovery` — the error propagated; the event was not handled.
- `:replaced-with-default` — the runtime used a default value (e.g., `:no-such-handler` falling through to a no-op).
- `:retried` — the runtime retried (with an upper bound) and surfaces the result.
- `:skipped` — the runtime declined to act (`:rf.fx/skipped-on-platform`, `:rf.cofx/skipped-on-platform`).
- `:warned-and-replaced` — the runtime emitted the warning and did its default action anyway (e.g., `:rf.ssr/hydration-mismatch` warn-and-replace mode).
- `:logged-and-skipped` — the runtime emitted the trace and dropped the offending input; sibling inputs still apply (e.g., `:rf.error/effect-map-shape` drops the offending top-level effect-map key while `:db` / `:fx` still apply).

A user-registered error-handler can intercept any error category and decide policy. The default error-handler routes everything to the trace stream and proceeds with the documented per-category recovery. Error-handler policy is registered per-frame via the `:on-error` slot in `reg-frame` metadata (per [002-Frames §`:on-error`](002-Frames.md)); for cross-frame observation, `register-trace-cb!` filtering on `:op-type :error` (or on the `:rf.error/*` `:operation` namespace) sees every error event without modifying behaviour. (The v1 process-wide `reg-event-error-handler` surface is dropped — see [MIGRATION.md §M-26](MIGRATION.md#m-26-drift-sweep-drops--v1-surfaces-with-no-v2-equivalent-or-absorbed-by-canonical-surfaces).)

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
- Returns either `nil` (no policy override; runtime applies its default per-category recovery) or a map with at least `:recovery` set to one of the documented recovery keywords.
- Optional keys in the return map: `:replacement` (a value to use when `:recovery` is `:replaced-with-default`) and `:notes` (a string surfaced in the resulting trace). The framework does not ship a `:retried` recovery — handlers cannot ask the runtime to re-execute the failed operation. If retry semantics are wanted, the user dispatches a fresh event from inside the error-handler (see "Composition with libraries" below).

Each frame has at most one `:on-error` handler. Re-registering the frame replaces the policy; the default error-handler applies until a `:on-error` is registered.

#### Default behaviour by category

(Every recovery in this table applies in dev only — trace emission and schema/type validation are both production-elided per the lead claim above, so production builds never reach these recovery paths. See [Spec 000 §Contract C-000.35](000-Vision.md) for the production-elision-equivalence clause this section grounds.)

| Category | Default `:recovery` | Notes |
|---|---|---|
| `:rf.error/handler-exception` | `:no-recovery` | The exception propagates; the cascade halts. |
| `:rf.error/machine-action-exception` | `:no-recovery` | The machine cascade halts atomically: the snapshot does not commit (pre-action `[:rf/machines <id>]` slice is preserved), accumulated `:fx` from earlier slots in the same Level-2 cascade is dropped, and the `:always` microstep does not fire on the failed cascade. |
| `:rf.error/fx-handler-exception` | `:no-recovery` | The fx is skipped; cascade continues if other fx independent. |
| `:rf.error/sub-exception` | `:replaced-with-default` | The sub returns `nil`; views see no value. |
| `:rf.error/no-such-sub` | `:replaced-with-default` | The unresolved input is substituted with `nil`; the sub's body still runs. Strict mode (`:strict-subs` config) escalates to a thrown exception. |
| `:rf.error/schema-validation-failure` | `:no-recovery` | Hard-fail to surface bugs early. Production builds elide the validation entirely (per the lead claim — see C-000.35), so this row applies only in dev. |
| `:rf.error/drain-depth-exceeded` | `:no-recovery` | Always indicates a bug; halt the cascade. |
| `:rf.error/no-such-handler` | `:replaced-with-default` | No-op; emit the trace. |
| `:rf.error/dispatch-sync-in-handler` | `:no-recovery` | The call is rejected. Use `:fx [[:dispatch event]]` in the effect map. |
| `:rf.error/effect-map-shape` | `:logged-and-skipped` | The offending top-level key is dropped; `:db` and `:fx` still apply. One trace per offending key. Per [MIGRATION §M-8](MIGRATION.md#m-8-effect-map-keys-consolidated--only-db-and-fx-at-the-top-level). |
| `:rf.error/override-fallthrough` | `:replaced-with-default` | Use the registered fx as if no override existed. |
| `:rf.fx/skipped-on-platform` | `:skipped` | Documented; not really an error. |
| `:rf.cofx/skipped-on-platform` | `:skipped` | Documented; not really an error. Mirrors `:rf.fx/skipped-on-platform` (the cofx's injection is skipped — the event handler still runs). |
| `:rf.ssr/hydration-mismatch` (body, `:failing-id :rf/hydrate`) | `:warned-and-replaced` | Re-render client-side; the server's HTML is replaced. |
| `:rf.ssr/hydration-mismatch` (head, `:failing-id :rf.ssr/head-mismatch`) | `:warned-and-replaced` | Client renders its head; server's is replaced. |
| `:rf.warning/multiple-status-set` | `:warned-and-replaced` | Last-write wins; advisory only. |
| `:rf.warning/multiple-redirects` | `:warned-and-replaced` | Last-write wins; advisory only. |
| `:rf.warning/interceptors-in-metadata-map` | `:ignored` | The mis-placed `:interceptors` chain is dropped; registration completes with no positional interceptors. |
| `:rf.warning/missing-doc` | `:ignored` | The registration completes normally; the warning is purely diagnostic. Emitted at most once per `(kind, id)` pair; production builds elide the emission entirely. Per [001 §`:doc` is dev-warned when absent](001-Registration.md#doc-is-dev-warned-when-absent) and rf2-lhu1e. |
| `:rf.warning/boundary-without-spec` | `:no-recovery` | The boundary interceptor is a no-op for this dispatch (no schema to validate against); the handler runs as if the interceptor were absent. Emitted at most once per `event-id` to flag the misconfiguration. Per [010 §Production builds](010-Schemas.md#production-builds) and rf2-r2uh. |
| `:rf.error/sanitised-on-projection` | `:replaced-with-default` | Runtime falls back to the locked generic-500 public-error shape. |
| `:rf.epoch/restore-unknown-epoch` | `:no-recovery` | Restore rejected; the frame's state is unchanged. Per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel). |
| `:rf.epoch/restore-schema-mismatch` | `:no-recovery` | Restore rejected; the frame's state is unchanged. |
| `:rf.epoch/restore-missing-handler` | `:no-recovery` | Restore rejected; the frame's state is unchanged. |
| `:rf.epoch/restore-version-mismatch` | `:no-recovery` | Restore rejected; the frame's state is unchanged. |
| `:rf.epoch/restore-during-drain` | `:no-recovery` | Restore rejected; the user retries after settle. |
| `:rf.epoch/reset-frame-db-during-drain` | `:no-recovery` | Pair-tool injection rejected; the caller retries after settle. Per [Tool-Pair §Pair-tool writes](Tool-Pair.md#pair-tool-writes--state-injection) (rf2-zq55). |
| `:rf.epoch/reset-frame-db-schema-mismatch` | `:no-recovery` | Pair-tool injection rejected; `app-db` is unchanged. The new-db failed the frame's registered app-schema set; the failing paths are surfaced in `:tags :failing-paths`. |
| `:rf.error/no-such-fx` | `:no-recovery` | The fx is dropped; cascade continues with remaining `:fx` entries. |
| `:rf.error/no-such-cofx` | `:no-recovery` | The cofx injection is a no-op; subsequent interceptors run with the ctx unchanged. |
| `:rf.error/frame-destroyed` | `:no-recovery` | Dispatch / subscribe is rejected; cascade halts (or returns nil for the subscribe path). |
| `:rf.error/flow-eval-exception` | `:no-recovery` | The cascade halts; the snapshot is uncommitted. The per-flow `:rf.flow/failed` op-type-`:flow` event also fires for attribution. |
| `:rf.error/unwrap-bad-event-shape` | `:no-recovery` | The interceptor returns the original ctx unchanged; the downstream handler receives the unwrapped vector unmodified. |
| `:rf.error/machine-raise-depth-exceeded` | `:no-recovery` | The `:raise` cascade halts; the snapshot is not committed. |
| `:rf.error/machine-always-depth-exceeded` | `:no-recovery` | The `:always` microstep loop halts; the snapshot is not committed. |
| `:rf.error/machine-unresolved-guard` | `:no-recovery` | Registration fails (or, at runtime fallback, the transition is rejected). |
| `:rf.error/machine-unresolved-action` | `:no-recovery` | Registration fails (or, at runtime fallback, the action is skipped). |
| `:rf.error/machine-bad-guard-form` | `:no-recovery` | Registration fails. |
| `:rf.error/machine-bad-action-form` | `:no-recovery` | Registration fails. |
| `:rf.error/machine-bad-state-form` | `:no-recovery` | The snapshot's state is rejected at normalisation; downstream walks halt. |
| `:rf.error/machine-bad-on-clause` | `:no-recovery` | Registration fails. |
| `:rf.error/machine-action-wrote-db` | `:logged-and-skipped` | The `:db` key is dropped from the action's effect map; remaining effects flow through. Per [005 §Hard-disallow `:db`](005-StateMachines.md). |
| `:rf.error/machine-grammar-not-in-v1` | `:no-recovery` | Registration is rejected. |
| `:rf.error/machine-unhandled-event` | `:ignored` | Advisory; the snapshot is unchanged. |
| `:rf.error/machine-state-not-in-definition` | `:no-recovery` | The transition is rejected. |
| `:rf.error/machine-snapshot-version-mismatch` | `:no-recovery` | The snapshot is rejected. |
| `:rf.error/machine-always-self-loop` | `:no-recovery` | Registration is rejected. |
| `:rf.error/machine-compound-state-missing-initial` | `:no-recovery` | Registration is rejected. |
| `:rf.error/no-such-route` | `:no-recovery` | The call throws; the caller chooses how to surface the failure. |
| `:rf.error/missing-route-param` | `:no-recovery` | The call throws; the caller chooses how to surface the failure. |
| `:rf.error/bad-app-schemas-arg` | `:no-recovery` | The call throws. |
| `:rf.error/unknown-preset` | `:no-recovery` | The call throws; registration of the offending frame fails. |
| `:rf.error/adapter-already-installed` | `:no-recovery` | The call throws; the existing adapter remains installed. |
| `:rf.error/render-on-headless-adapter` | `:no-recovery` | The call throws; user should use `render-to-string` on this adapter. |
| `:rf.error/no-hiccup-emitter-bound` | `:no-recovery` | The call throws; SSR namespace must be required so `set-hiccup-emitter!` runs. |
| `:rf.error/frame-context-corrupted` | `:replaced-with-default` | The function-component resolution chain falls through to `:rf/default`. Pre-rf2-8q66 observable behaviour preserved; the error event is the new diagnostic surface. Per [006 §Frame-provider via React context](006-ReactiveSubstrate.md). |
| `:rf.error/flow-cycle` | `:no-recovery` | Flow registration is rejected. |
| `:rf.error/flow-missing-id` | `:no-recovery` | Flow registration is rejected. |
| `:rf.error/flow-bad-inputs` | `:no-recovery` | Flow registration is rejected. |
| `:rf.error/flow-bad-output` | `:no-recovery` | Flow registration is rejected. |
| `:rf.error/flow-bad-path` | `:no-recovery` | Flow registration is rejected. |
| `:rf.error/flows-artefact-missing` | `:no-recovery` | The call throws an ex-info; user adds `day8/re-frame2-flows` to deps. |
| `:rf.error/ssr-artefact-missing` | `:no-recovery` | The call throws an ex-info; user adds `day8/re-frame2-ssr` to deps. |
| `:rf.error/routing-artefact-missing` | `:no-recovery` | The call throws an ex-info; user adds `day8/re-frame2-routing` to deps. |
| `:rf.error/schemas-artefact-missing` | `:no-recovery` | The call throws an ex-info; user adds `day8/re-frame2-schemas` to deps. |
| `:rf.error/machines-artefact-missing` | `:no-recovery` | The call throws an ex-info; user adds `day8/re-frame2-machines` to deps. |
| `:rf.error/http-artefact-missing` | `:no-recovery` | The call throws an ex-info; user adds `day8/re-frame2-http` to deps. |
| `:rf.warning/route-shadowed-by-equal-score` | `:warned-and-replaced` | The new route registers; equal-score sibling is shadowed by stable sort. |
| `:rf.warning/decode-defaulted` | `:ignored` | Informational; auto-decode proceeds normally. |
| `:rf.warning/write-after-destroy` | `:no-recovery` | The write is dropped; the substrate's `replace-container!` is not invoked. |
| `:rf.warning/dispatch-from-async-callback-fell-through-to-default` | `:no-recovery` | The warning is purely diagnostic; the existing `:rf.error/no-such-handler` error fires alongside and carries the canonical `:replaced-with-default` recovery for the dispatch itself. Per rf2-o8m0. |
| `:rf.warning/cross-frame-dispatch-sync-during-drain` | `:no-recovery` | The dispatch proceeds; the warning is purely diagnostic. Frames are independent state machines so the cross-frame cascade is not a contract violation, but the interleaved ordering is rarely intentional — the warning surfaces the pattern for observability tools. Per rf2-fp97. |
| `:rf.http/cljs-only-key-ignored-on-jvm` | `:ignored` | The unsupported key is dropped; the request proceeds with the remaining keys. |
| `:rf.http/retry-attempt` | `:retried` | A new attempt is scheduled; the consumer sees the trace and the eventual final outcome via `:on-failure` / `:on-success`. |
| `:route.nav-token/stale-suppressed` | `:logged-and-skipped` | The async reply is suppressed; the active navigation cascade continues unchanged. |
| `:rf.frame/drain-interrupted` | n/a | Lifecycle event, not error-shaped. Remaining queued events are dropped silently. |

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

Multiple monitoring concerns compose in user code (one `:on-error` handler that fans out to several services). For cross-frame observation that doesn't modify recovery, prefer `register-trace-cb!` filtered on `:op-type :error`.

## Notes

### Why this is its own Spec

Tracing is the connective tissue between the runtime and every tool that observes it. Splitting it into its own Spec:

- Locks the data shape independently of any specific tool.
- Documents the forward-compat commitments tools depend on.
- Separates "framework emits events" (002 territory) from "framework provides a tap surface" (this Spec).
- Documents the prod-side Performance API instrumentation channel (gated on `re-frame.performance/enabled?`, default-off) alongside the dev-side trace stream — two compile-time-elidable surfaces with distinct gates and distinct consumers (see [§Performance instrumentation](#performance-instrumentation)).

## Open questions

### Trace allocation cost in dev when no listeners

In dev, `interop/debug-enabled?` is true, so the emit body runs even when no listeners are registered: the runtime allocates the event map, pushes it to the retain-N ring buffer, and walks the (empty) listener registry. The ring-buffer push is the floor cost. Tools that want maximum dev-loop throughput can `(rf/configure :trace-buffer {:depth 0})` to disable the ring buffer; the synchronous-delivery path still works and the user-listener fan-out remains zero-cost when no listeners are attached.

## Resolved decisions

### Listener ordering

Multiple listeners may register concurrently. **Listener-invocation order is not contract** — tools must not depend on the order in which sibling listeners receive a given event. Each listener receives the same event independently; nothing about the order in which the runtime walks the listener registry is guaranteed across builds, hosts, or registry implementations. The same rule applies to `register-trace-cb!` (per [§Subscription / consumption](#subscription--consumption) and [§Listener invocation rules](#listener-invocation-rules)) and `register-epoch-cb!` (per [`register-epoch-cb!` §Invocation rules](#register-epoch-cb--assembled-epoch-listener)).

### Trace correlation across the cascade

Two cascade-wide channels ride on **every** trace event emitted inside a cascade — neither is scoped to errors:

1. **`:dispatch-id`** under `:tags` (per [§Dispatch correlation](#dispatch-correlation-dispatch-id--parent-dispatch-id)). Grouping raw trace events by cascade is a single-key filter — `(filter #(= cascade-id (get-in % [:tags :dispatch-id])) events)`. Tools that need cascade *trees* walk `:parent-dispatch-id` upward across `:event/dispatched` events (the inter-cascade lineage channel).

2. **`:rf.trace/trigger-handler`** at the top level (per [§`:rf.trace/trigger-handler` — naming the in-scope handler](#rftracetrigger-handler--naming-the-in-scope-handler)). Names the handler whose code produced the event and carries its registration coord — so jump-to-source links work from every trace event in a cascade, not just errors. Rides on `:rf.fx/handled`, `:rf.machine/transition`, `:event/db-changed`, `:event/do-fx`, `:sub/run`, `:view/render`, and all `:rf.error/*` events whenever a handler is in scope at emit time. Omitted outside any handler scope (registration-time emits, outermost-dispatch lookup failures).

Per-cascade structured projection lives in the assembled `:rf/epoch-record` (per [Spec-Schemas](Spec-Schemas.md#rfepoch-record)) — the raw `:dispatch-id` / `:rf.trace/trigger-handler` channels are the lower-level primitives.

### Trace event for app-db changes

`:db` mutations happen inside `do-fx`. The runtime emits a separate `:event/db-changed` trace event on every dispatch whose handler returned a new db value. Tools that want before/after pairs read the `:rf/epoch-record`'s `:db-before` / `:db-after` slots, which the runtime captures atomically across the cascade rather than per-event.

### Privacy / sensitive data in traces

Trace events carry dispatched event vectors, handler return values, and (under [§Trace event for app-db changes](#trace-event-for-app-db-changes)) `app-db` snapshots — any of which may contain user input that should not leave the developer's machine: passwords, auth tokens, payment details, PII captured from form fields. Tools that ship traces off-box (error-monitor forwarders per [§Wiring an external error monitor](#wiring-an-external-error-monitor-sentry-rollbar-honeybadger-etc), remote dev dashboards, the Causa-MCP / pair2 servers per [Tool-Pair.md](Tool-Pair.md)) must not emit that data verbatim.

The contract is **two cooperating pieces**: a per-registration declarative flag (`:sensitive?`) that tools route on, and an opt-in redaction interceptor (`with-redacted`) that overwrites named keys in flight. Apps that only need filter-out semantics declare `:sensitive?` on the affected registrations and stop; apps that need keys redacted *within* a still-emitted event reach for `with-redacted`. The two compose — a registration carrying both `:sensitive?` and `with-redacted` emits redacted-payload traces tagged `:sensitive? true`.

#### The `:sensitive?` registration metadata key

`:sensitive?` is an optional **boolean** key on the `:rf/registration-metadata` map (per [Spec 001 §Registration grammar](001-Registration.md#registration-grammar) and [Spec-Schemas §`:rf/registration-metadata`](Spec-Schemas.md#rfregistration-metadata)). Every `reg-*` kind accepts it; the conventional use sites are `reg-event-*` (event handlers whose event vectors carry user input) and `reg-sub` (subscriptions whose return values flow user input into views).

```clojure
(rf/reg-event-fx :auth/sign-in
  {:doc        "Verify credentials and start a session."
   :sensitive? true                                 ;; this handler's event vector and return carry secrets
   :spec       [:cat [:= :auth/sign-in] :string :string]}
  (fn handler-auth-sign-in [{:keys [db]} [_ username password]]
    {:db (assoc db :auth/pending? true)
     :fx [[:rf.http/managed {:url "/auth" :method :post :body {:u username :p password}}]]}))
```

Semantics:

- The registrar copies the `:sensitive?` value from the registration metadata into the registry slot's stored meta. Tools query it via `(rf/handler-meta kind id)` and see `:sensitive? true` on every registration that opted in.
- At trace-emit time, when a handler with `:sensitive? true` is in scope (per [§`:rf.trace/trigger-handler` — naming the in-scope handler](#rftracetrigger-handler--naming-the-in-scope-handler)), the runtime MUST stamp `:sensitive? true` at the top level of every trace event emitted within that handler's scope. The stamp rides alongside `:source` / `:recovery` (other top-level hoists) and is independent of `:tags`. `:sensitive?` is hoisted to top-level, not `:tags`, so a single keyword read on the event tells the consumer to filter — no nested lookup.
- A trace event whose `:rf.trace/trigger-handler` is not in scope (registration-time emits, outermost-dispatch lookup failures) carries no `:sensitive?` stamp. Consumers treat absent as `false`.
- When a cascade chains handlers (event handler → fx handler → subsequent event handler), each handler's scope contributes its own `:sensitive?` reading; the stamp on a given trace event reflects the **innermost in-scope handler's** flag at emit time. Tools that want "every trace event in a sensitive cascade" group by `:dispatch-id` and OR-reduce the per-event `:sensitive?` field — the runtime does not transitively widen the flag across handler boundaries.
- `:sensitive?` is **declarative**; setting it does NOT by itself redact any payload. The handler's event vector, return value, and the resulting `:event/db-changed` `:tags :app-db-before` / `:app-db-after` slots ride the trace stream unchanged. Tools downstream of the trace surface (the on-box error-monitor forwarder, the off-box pair2 server, dev panels) MUST consult `:sensitive?` and apply tool-side policy — drop, redact, summarise, or filter — before any data leaves the trust boundary.

The default policies that ship with the framework's published listener integrations (the Sentry / Honeybadger forwarders documented at [§Wiring an external error monitor](#wiring-an-external-error-monitor-sentry-rollbar-honeybadger-etc), the pair2 server per [Tool-Pair.md](Tool-Pair.md)) MUST suppress events carrying `:sensitive? true` by default. Apps that want them shipped opt in explicitly per integration; the conservative default protects apps that opt into a published integration without reading its source.

#### The `with-redacted` interceptor

`with-redacted` is a framework-supplied interceptor (re-exported from `re-frame.core`) that overwrites named keys in the event vector's payload map AND in the resulting `:db` / `:event` slots of every trace event emitted within the handler's scope. It is the **in-place redaction** complement to `:sensitive?`'s filter-out semantic — apps that want to keep emitting structured traces for sensitive handlers (so error-monitor dashboards still see the event taxonomy, the cascade, the exception class) but with the secret payloads scrubbed reach for `with-redacted`.

Signature:

```clojure
(rf/with-redacted paths)
;; -> an interceptor that, when present in an event handler's positional chain,
;;    redacts the named paths in the event vector AND in the dispatched-event /
;;    db-changed trace events emitted by the surrounding cascade.
;;
;; Arguments:
;;   paths — a vector of get-in-style key paths into the event vector's payload
;;           map (the conventional one-payload-map second element). Each path is
;;           itself a vector; the value at every named path is replaced with the
;;           sentinel keyword :rf/redacted before the runtime emits the
;;           :event/dispatched trace and before any :tags :event slot is built.
;;
;; Returns: an interceptor map suitable for inclusion in a reg-event-* chain.
```

Worked example:

```clojure
(rf/reg-event-fx :auth/sign-in
  {:doc "Verify credentials and start a session." :sensitive? true}
  [(rf/with-redacted [[:password] [:totp-code]])]      ;; positional interceptor chain
  (fn handler-auth-sign-in [{:keys [db]} [_ {:keys [username password totp-code] :as payload}]]
    ...))

;; A dispatch like (rf/dispatch [:auth/sign-in {:username "ada" :password "shhh" :totp-code "123456"}])
;; produces an :event/dispatched trace event whose :tags :event is:
;;
;;   [:auth/sign-in {:username "ada" :password :rf/redacted :totp-code :rf/redacted}]
```

Behaviour:

- **Redaction target.** `with-redacted` overwrites the named keys with the sentinel keyword `:rf/redacted` (a reserved keyword — apps MUST NOT use it as a legitimate payload value). The runtime never carries the original value past the interceptor's `:before` stage; the handler body itself sees the **unredacted** payload via the regular `:event` cofx slot (handlers need the real value to do their work — `with-redacted` redacts what the trace surface sees, not what the handler sees).
- **What gets redacted.** The interceptor's `:before` stage redacts every named path in:
  1. The `:event/dispatched` trace event's `:tags :event` payload.
  2. The event handler's `:rf.trace/trigger-handler` cofx-slot view of the event (so any downstream emit that copies the event vector picks up the redacted form).
  3. The `:event/db-changed` trace event's `:tags :app-db-before` and `:tags :app-db-after` slots, when the corresponding paths exist in `app-db` (the interceptor consults the same path vector against the db).
  4. Any `:rf.error/handler-exception` `:tags :event` slot the runtime emits for a throw from this handler.
- **Paths are vectors of keys.** `with-redacted` walks `get-in` semantics. The conventional event-vector shape is `[event-id payload-map]` (per [Conventions §Unwrap interceptor](Conventions.md)); paths address keys inside the payload map. Implementations are free to extend the vocabulary to accept richer path forms (e.g. wildcards, predicate-paths) — the v1 contract is the literal-keys form documented above.
- **Sentinel keyword.** `:rf/redacted` is the framework-reserved redaction sentinel. The keyword namespace `:rf/` is the framework's reserved-keyword space (per [Conventions](Conventions.md)) so the sentinel cannot collide with an app-defined value. Consumers wanting "was this redacted?" check for the sentinel; `:sensitive?` at the top level is the orthogonal "did the registration declare itself sensitive?" axis.
- **Composition with `:sensitive?`.** A handler carrying both `:sensitive? true` in its metadata-map and `[(rf/with-redacted [...])]` in its positional interceptor chain emits trace events that are BOTH stamped `:sensitive? true` AND carry redacted payloads. The two are independent — `:sensitive?` is the filter-out signal for listeners; `with-redacted` is the in-place scrub. The conservative recommended pattern for new sensitive handlers is to declare both: `:sensitive?` covers the case where a future listener forgets to redact, and `with-redacted` covers the case where a listener bypasses the filter and ships the event anyway.
- **Composition with the rest of the interceptor chain.** `with-redacted` is an ordinary interceptor — it threads through `:before` / `:after` like any other and composes with `path`, `enrich`, `inject-cofx`, and user-defined interceptors. The redaction step runs in `:before` so all downstream emits see the redacted form; the handler body sees the original `:event` via the regular cofx slot.

#### Trace-event field: `:sensitive?` at the top level

The `:rf/trace-event` schema (per [Spec-Schemas §`:rf/trace-event`](Spec-Schemas.md#rftrace-event)) gains an optional top-level `:sensitive?` boolean. Tools branch on it directly:

```clojure
(rf/register-trace-cb!
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

Listeners installed via `register-trace-cb!` and `register-epoch-cb!` (per [§The listener API](#the-listener-api)) receive **every** trace event regardless of `:sensitive?` — the flag is a payload axis the listener inspects, not a delivery gate. Two reasons: (1) on-box developer tooling (10x, the trace panel, the in-process ring buffer) needs to see sensitive traces during local dev; (2) routing the filter into the runtime would force every consumer to opt in to seeing sensitive data and complicate the elision contract. Filtering lives in the **listener body**, not in the framework's dispatch path.

Framework-published listener integrations MUST default to suppressing `:sensitive? true` events:

- The Sentry / Honeybadger forwarder samples at [§Wiring an external error monitor](#wiring-an-external-error-monitor-sentry-rollbar-honeybadger-etc) wrap their `register-trace-cb!` body in `(when-not (:sensitive? trace-event) ...)` by default. Apps that want the events shipped (rare; only when the monitor is itself the trust boundary, e.g. a self-hosted Sentry inside the same VPN) opt in by removing the guard.
- The pair2 server (per [Tool-Pair.md §How AI tools attach](Tool-Pair.md#how-ai-tools-attach)) MUST drop or redact `:sensitive? true` events before forwarding to the AI surface. The default policy is **drop**; apps that want sensitive cascades visible to the pair tool configure the policy explicitly.
- The Causa-MCP server (per [Tool-Pair.md](Tool-Pair.md)) MUST default-drop `:sensitive? true` events from the cascade graph it materialises.

User-side listeners (in-app recorders, dev panels, custom forwarders) have no framework-imposed policy — they receive every event and decide on a per-app basis. The recommended discipline is identical: gate any off-box egress on `(when-not (:sensitive? trace-event) …)`.

#### Production-elision behaviour

The `:sensitive?` mechanism is **dev-time only** — both pieces of it ride the trace surface and elide with it:

- The trace surface's `:advanced` + `goog.DEBUG=false` build elides `emit!` entirely (per [§Production builds](#production-builds-zero-overhead-zero-code)). No trace event is allocated, no listener body runs, no `:sensitive?` stamp is built. The privacy mechanism is moot because there is no trace to privacy-protect.
- The `with-redacted` interceptor itself is a regular interceptor map — it ships in production builds (it sits in the event handler's positional chain, not behind the trace gate). However, its only side-effects are *building the redacted shape for the trace emit and substituting the redacted event into downstream cofx slots*. With the trace surface elided, the substitution still runs (the handler still sees the substituted `:event` cofx) but no emit consumes it. Apps may safely leave `with-redacted` in the positional chain across dev and production builds — the redaction is correct in both, and in production the only observable effect is that the handler sees the redacted payload via the cofx slot (the unredacted payload still rides the `:before` stage and is available via the regular event-vector access path for the handler body itself).
- The `:sensitive?` registration-metadata key is **NOT elided** — it sits on the registry's stored meta and surfaces through `(handler-meta kind id)` in dev and production alike. Production tools that query the registrar (e.g., for diagnostic dumps) see the flag without depending on the trace surface.
- The elision-probe verifier (per [§Production-elision verification](#production-elision-verification)) gains one sentinel: the string fragment `":rf/redacted"` (the sentinel keyword) MUST be absent from the production bundle when no source-file declares it as a literal outside an elided branch. (Apps that use `with-redacted` declare it in their handler chains; the literal survives elision in those source-file slots — the verifier checks the *framework* surface, not user code.)

A new error category accompanies the contract:

| `:operation` / category | Meaning | Category-specific `:tags` |
|---|---|---|
| `:rf.warning/sensitive-without-redaction` | A registration carries `:sensitive? true` but its positional interceptor chain has no `with-redacted` interceptor. Emitted at registration time per `(kind, id)` pair (suppression cache reset across frame destruction). Advisory: the registration's events will be filtered by listener default-drop policy, but the in-buffer / on-box traces still carry the raw payload. The fix is to add `with-redacted` to the chain when the app wants both filter-out and in-place scrub semantics. Recovery: `:warned-and-replaced` — registration proceeds, but with the warning emitted | `:reg-fn` (e.g. `'reg-event-fx`), `:id`, `:reason` |

The default-recovery row in the [§Default behaviour by category](#default-behaviour-by-category) table gains a corresponding entry: `:rf.warning/sensitive-without-redaction` → `:warned-and-replaced` — the warning fires; registration is unchanged.

Trace events emitted under `:rf.warning/sensitive-without-redaction` ride the warning channel like every other `:rf.warning/*`: `:op-type :warning`, structured `:tags`, surfaced through `register-trace-cb!`. Tools that want a pre-flight sweep of an app's registrations consult `(rf/handlers :event)` filtered on `(:sensitive? (rf/handler-meta :event id))` and cross-check against the positional chain inspection (`(:interceptors (rf/handler-meta :event id))`).
