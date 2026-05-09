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

Pair-shaped tools and per-event diagnostics need to correlate the *cascade* a dispatch belongs to — "this `:event/dispatched` was emitted by an effect that ran inside the cascade started by *that* dispatch." The runtime stamps two fields onto every `:event/dispatched` trace event under the top-level `:tags`:

```clojure
{:tags {:dispatch-id        <uuid-or-counter>   ;; this dispatch's identity
        :parent-dispatch-id <uuid-or-counter>}  ;; the dispatch that caused this one (if any)
 ...}
```

Semantics:

- **`:dispatch-id`** is allocated by the runtime when a dispatch is enqueued, before routing. Implementations may use a process-monotonic counter, a UUID, or any opaque value with the same uniqueness contract: distinct within a single process for the lifetime of the trace surface. Tools treat it as opaque.
- **`:parent-dispatch-id`** is set when the dispatch was emitted as a side-effect of another event's processing — typically inside an `fx` handler. Concretely: when an `fx` handler running inside the do-fx phase of dispatch *D₁* invokes `(rf/dispatch ...)`, the runtime records the new dispatch's `:parent-dispatch-id` as *D₁*'s `:dispatch-id`. If the dispatch was initiated outside any in-flight event (a timer, a UI handler, the REPL, the SSR boot path), `:parent-dispatch-id` is absent.
- **Top-level dispatch.** A dispatch with no `:parent-dispatch-id` is a *root* of a cascade. Pair-shaped tools draw cascade trees by following `:parent-dispatch-id` upward.
- **The cascade-correlation primitive.** Because the runtime emits event-at-a-time (no `:child-of` span field), `:dispatch-id` / `:parent-dispatch-id` is the only cascade-correlation channel: events queued through the router carry the chain. Tools that want to group raw spans by cascade key off `:dispatch-id` from the relevant `:event/dispatched` event and correlate sibling traces via the `:rf/epoch-record` projections (per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel)).
- **Production elision.** Both fields ride the trace stream and are elided in production with the rest of the trace surface.

Tools consume these two fields to build dispatch-cascade views: "show me all dispatches descended from `[:user/login ...]`" is a transitive walk over `:parent-dispatch-id`.

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
- `:rf.machine/event-received` / `:rf.machine/transition` / `:rf.machine/snapshot-updated` — machine activity.
- `:rf.machine.microstep/transition` — per-microstep transition emitted alongside the outer `:rf.machine/transition` for `:always`-driven cascades; one event per microstep with `:tags {:machine-id <id> :from <state> :to <state> :microstep-index <n>}` (per [005 §Trace events](005-StateMachines.md#trace-events) and [Spec-Schemas §`:rf/trace-event`](Spec-Schemas.md#rftrace-event)).
- `:rf.machine/spawned` / `:rf.machine/destroyed` — machine instance spawn/destroy events emitted by `fx.cljc` on the spawn / destroy fx-id paths. Distinct from `:rf.machine.lifecycle/created` / `-destroyed` (which are emitted by `frame.cljc` on the underlying registrar lifecycle); the `:rf.machine/*` pair is the fx-substrate observation, the `:rf.machine.lifecycle/*` pair is the registrar-substrate observation. Tools that just want "did a machine appear/disappear?" can subscribe to either; tools building causal graphs subscribe to both and disambiguate by the `:tags :emitted-from` axis.
- `:rf.machine/system-id-bound` / `:rf.machine/system-id-released` — `:system-id` reverse-index lifecycle (per [005 §Named addressing via `:system-id`](005-StateMachines.md#named-addressing-via-system-id)). `-bound` fires on every `:system-id`-bound spawn (including the rebound case that also emits the `:rf.error/system-id-collision` warning); `-released` fires on the matching destroy. `:tags {:frame <id> :system-id <name> :machine-id <gensym'd-id>}`.
- `:rf.machine.timer/scheduled` / `:rf.machine.timer/fired` / `:rf.machine.timer/stale-after` — state-machine `:after` timer lifecycle (per [005 §Trace events](005-StateMachines.md#trace-events)). The `*/stale-*` form is the canonical naming for [§stale-detection trace events](Pattern-StaleDetection.md) — see also `:route.nav-token/stale-suppressed` below.
- `:route.nav-token/allocated` / `:route.nav-token/stale-suppressed` — navigation-token lifecycle (per [012 §Navigation tokens](012-Routing.md#navigation-tokens--stale-result-suppression)). `*-allocated` fires when a navigation cascade begins; `*-stale-suppressed` fires when an async result arrives carrying a now-superseded token. Same epoch idiom as the machine-`:after` timer events.
- `:rf.route/url-changed` / `:rf.route/navigation-blocked` — fragment-only URL change emission (per [012 §Fragments](012-Routing.md#fragments); distinct from the runtime event `:rf/url-changed` which fires on every URL transition) and pending-nav protocol blockage (per [012 §Navigation blocking](012-Routing.md#navigation-blocking--pending-nav-protocol)). Fragment-only navigation is reported under the same `:rf.route/url-changed` op-name as full URL changes; consumers discriminate on `:tags` (the fragment-only emission carries `:prev-fragment` and `:next-fragment` and never coincides with a `:route.nav-token/allocated` event for the same drain — see [Spec-Schemas §`:rf.route/url-changed`](Spec-Schemas.md#rftrace-event) and the [`routing/fragment-change` conformance fixture](conformance/fixtures/route-fragment-change.edn)).
- `:rf.registry/handler-registered` / `:rf.registry/handler-cleared` / `:rf.registry/handler-replaced` — registration changes (hot reload). The canonical trio: `-registered` for a fresh id, `-cleared` for an explicit removal, `-replaced` when re-registration overwrote an existing id (the typical hot-reload case).
- `:flow` — flow lifecycle and evaluation events (per [013 §Flow tracing](013-Flows.md#flow-tracing)). The op-type for the whole flow trace stream; per-flow events live under `:rf.flow/*` operations (`:rf.flow/registered`, `:rf.flow/computed`, `:rf.flow/skip`, `:rf.flow/cleared`, `:rf.flow/failed` — see [§Flow trace events](#flow-trace-events) below). Tools filter `op-type :flow` to subscribe to the whole flow stream.
- `:error` / `:warning` — universal severity discriminators for failure events. The category-specific identity lives in `:operation` (e.g. `:rf.error/handler-exception`); see [§Error contract](#error-contract) for the authoritative model.
- `:info` — informational advisories the runtime emits without warning or error severity (e.g. `:rf.http/retry-attempt` per [014 §Retry semantics](014-HTTPRequests.md#retry-semantics)). Tools that filter for issues subscribe to `:warning` / `:error`; tools that surface activity timelines subscribe to `:info` as well.
- `:rf.machine/destroyed-on-frame-exit` — operation emitted by `frame.cljc` when a frame's destroy walks each surviving machine instance and tears it down. Pairs with `:rf.machine.lifecycle/destroyed` (which carries the same per-machine teardown signal under the lifecycle op-type); both fire from the same destroy walk so consumers see the registrar substrate's observation and the frame substrate's observation as a coherent pair. `:tags {:frame <id> :machine-id <id> :last-state <state>}`.
- `:rf.frame/drain-aborted` — lifecycle event emitted by `router.cljc` when the drain loop detects `(:destroyed? (:lifecycle frame))` mid-cycle and drops remaining queued events. `:op-type :event`. `:tags {:frame <id> :dropped-count <int>}`. Per [002 §Edge cases worth pinning](002-Frames.md#edge-cases-worth-pinning).
- `:rf.epoch/snapshotted` / `:rf.epoch/restored` / `:rf.epoch/db-replaced` — epoch-history operations under `:op-type :rf.epoch`. `-snapshotted` fires after each drain-settle when the runtime has appended a fresh `:rf/epoch-record`; `-restored` fires after a successful `restore-epoch`; `-db-replaced` fires after a successful `reset-frame-db!` (the rf2-zq55 pair-tool write surface — see [Tool-Pair §Pair-tool writes](Tool-Pair.md#pair-tool-writes--state-injection)). Per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel). `:tags {:frame <id> :epoch-id <id> :event-id <id>?}`.

Consumers filter by `:op-type` (or `:source`, or `(get-in ev [:tags :frame])`) to get the slice they care about. Adding new `:op-type` values is non-breaking — tools ignore what they don't understand.

### `:tags` is the open-ended bag

Variable per-event data goes in `:tags`. Existing examples: `:event-id`, `:event`, `:frame`, `:phase`, `:dispatch-id`, `:parent-dispatch-id`, `:origin`, `:app-db-before`, `:app-db-after`, `:sub-id`, `:query-v`, `:input-signals`, `:fx-id`, `:fx-args`. New tags can be added without breaking consumers. Use `:tags` for op-type-specific data; reserve top-level keys for fields universal across all events.

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

- **`:input-values` / `:result` are the actual values, not hashes.** The trace surface is dev-only (per [§Production builds](#production-builds-zero-overhead-zero-code)) and downstream tools — re-frame-10x's flow panel, custom dashboards — display the values. Hashing would force consumers to consult an out-of-band side table; raw values keep the stream self-contained.
- **`:rf.flow/skip` carries `:reason :inputs-value-equal`** rather than always being implicit. The keyword is the future extension point if additional skip reasons land (e.g. flow disabled mid-walk, frame in restore).
- **`:rf.flow/failed` re-throws** so cascade-level error-handling (Spec 009 §Error contract's `:rf.error/flow-eval-exception`) still fires; the per-flow `:rf.flow/failed` adds the per-flow attribution.

Pair-shaped tools and 10x v2's flow panel filter `op-type :flow` (per [Tool-Pair §How AI tools attach](Tool-Pair.md#how-ai-tools-attach)) to subscribe to the whole stream.

## Subscription / consumption

re-frame2's trace API uses **synchronous, event-at-a-time delivery** — every registered listener is invoked once per emitted trace event, in registration order, while the runtime is still on the emit call stack. There is no batching, debounce window, or background delivery loop. Listeners SHOULD do minimal work in the callback (queue, append to a buffer, mark a flag) and defer expensive work to a separate timer or animation frame they own.

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
```

Conventional keys: `:my-app/recorder`, `:my-app/timing-monitor`, etc.

**Re-registration semantics.** `register-trace-cb!` called with a key already in the registry replaces the previous callback atomically — the swap from old to new happens between two emits, never mid-emit. No trace event is emitted for the replacement (the listener registry is itself dev-only metadata; mutating it does not feed the trace stream); no events delivered to the previous callback are re-delivered to the new one, and no events emitted after the swap are dropped. Hot-reload tools that re-register their listener on every code reload see exactly one stream of events with the swap point invisible to the runtime. The same semantics apply to `register-epoch-cb` re-registration under an existing key.

**Worked example.** A minimal recorder that prints every error trace to the console:

```clojure
(rf/register-trace-cb!
  :my-app/error-logger
  (fn [trace-event]
    (when (= :error (:op-type trace-event))
      (println (:operation trace-event)
               (-> trace-event :tags :reason)))))
```

The same pattern with `register-epoch-cb` to log one assembled cascade per drain-settle:

```clojure
(rf/register-epoch-cb
  :my-app/cascade-logger
  (fn [epoch-record]
    (println (:event-id epoch-record)
             "→" (count (:effects epoch-record)) "fx"
             "/" (count (:sub-runs epoch-record)) "sub-runs")))
```

#### `register-epoch-cb` — assembled-epoch listener

Alongside the raw trace stream, the framework exposes a parallel **assembled-epoch listener** API. Where `register-trace-cb!` delivers each raw event as it is emitted, `register-epoch-cb` delivers one fully-assembled `:rf/epoch-record` (per [Spec-Schemas](Spec-Schemas.md#rfepoch-record)) per drain-settle:

```clojure
(rf/register-epoch-cb key callback-fn)
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

(rf/remove-epoch-cb key)
;; Unsubscribes the listener registered under key.
```

**Invocation rules** (mirrors `register-trace-cb!`):

- **Per drain-settle, not per event.** The callback fires once when the run-to-completion drain reaches an empty queue and the epoch record is committed; multi-event cascades produce one record (and one callback invocation), not one per event.
- **After commit.** The callback receives a fully-formed record with `:db-after`, `:sub-runs`, `:renders`, `:effects`, and any optional `:trace-events` populated. The record has already been appended to the frame's `epoch-history` ring buffer when the callback runs.
- **Exception isolation.** An exception thrown by an epoch callback is caught and does not propagate. One broken epoch listener cannot break the app or block other listeners (raw-trace or epoch).
- **Listener ordering** is not contract.
- **Production elision.** The epoch listener machinery is gated on the same `re-frame.interop/debug-enabled?` flag (alias of `goog.DEBUG`) as the raw-trace surface — see [§Production builds](#production-builds-zero-overhead-zero-code). Production builds elide registration, dispatch, and the epoch ring-buffer all together.

**When to use which.** `register-trace-cb!` is the right shape for tools that need fine-grained per-event activity (custom recorders, error-monitor forwarders, timing aggregators). `register-epoch-cb` is the right shape for tools that route diagnostics off "what just happened in this cascade" — pair-shaped tools, post-mortem dashboards, anything that wants the structured `:sub-runs` / `:renders` / `:effects` projection without re-folding the raw trace stream.

The two listener APIs are independent: tools may register either, both, or neither. They share the production-elision gate but have separate listener registries; no listener of one kind can interfere with the other.

### Listener invocation rules

- **Synchronous, event-at-a-time.** Every registered listener is invoked once per emitted trace event, on the runtime's emit call stack. There is no batching, debounce window, or background delivery loop. Listeners SHOULD return quickly; expensive work belongs on a tool-owned timer or rAF.
- **In-order.** Listeners see events in emission order — i.e. the order the runtime fired them.
- **Exception isolation.** An exception thrown by a listener is caught and does *not* propagate to the framework or other listeners. One broken tool can't break the app or block other tools. The caught exception is logged via `re-frame.interop/log-error` (or the host equivalent) and otherwise discarded; the runtime does NOT emit a self-referential trace event for the failed listener (which would risk a re-entrant trace-emit storm). The same handling applies to exceptions thrown by an `register-epoch-cb` callback.
- **No buffering between listeners and the runtime.** The framework does not retain a delivery buffer; the retain-N ring buffer described next is independent and exists for late-attaching tools.

### Retain-N trace ring buffer (dev-only)

In dev builds, the framework maintains a **retain-N trace ring buffer** alongside the synchronous-delivery path. The ring buffer holds the most recent N emitted trace events and is queryable. This lets pair-shaped AI tools, REPL-attached debuggers, and post-mortem dashboards read recent activity without having to be registered as a `register-trace-cb!` listener at the time the events fire.

Contract:

| API | Signature | Notes |
|---|---|---|
| `(rf/trace-buffer)` | `() → vector` | Returns the buffer's current contents, oldest-first. Empty when no events have been recorded. |
| `(rf/trace-buffer opts)` | `(opts) → vector` | Optional filter: `{:operation kw}`, `{:op-type kw}`, `{:since trace-id}`, `{:frame frame-id}`. Filters compose. |
| `(rf/clear-trace-buffer!)` | `() → nil` | Empties the buffer. Tooling uses this between sessions. |
| `(rf/configure :trace-buffer {:depth N})` | `(N) → nil` | Configure depth; default 200 events. `0` disables the ring buffer (synchronous delivery still works). |

Semantics:

- **Ring discipline.** When the buffer is full, the oldest event is evicted as a new one is pushed. No allocation churn beyond the slot count.
- **Same events as delivery.** Every event delivered to listeners also lands in the ring buffer. Ring-buffer events are the same maps the listeners receive.
- **Independent of listeners.** A tool that attaches *after* events have fired can read the most-recent N from the ring buffer to bootstrap its view; a tool that wants a continuous live feed registers a `register-trace-cb!` listener as well.
- **Production elision.** The ring buffer, like the rest of the trace surface, is compile-time eliminated in production builds (per [§Production builds](#production-builds-zero-overhead-zero-code)). `(rf/trace-buffer)` returns an empty vector in production, and the buffer itself is not allocated.
- **Depth-zero semantics.** When configured with `{:depth 0}`, the ring buffer is disabled but the surface remains live: `(rf/trace-buffer)` returns `[]`, `(rf/trace-buffer opts)` returns `[]`, and `(rf/clear-trace-buffer!)` is a no-op (returns `nil`). Synchronous-delivery to registered listeners continues to fire — only the queryable history is suppressed.
- **Lowering depth on a populated buffer.** `(rf/configure :trace-buffer {:depth N})` applied while the buffer holds more than `N` events drops the oldest events first to fit the new depth (same eviction order as the ring discipline). Raising the depth keeps existing events and grows the slot count.

Why this is a framework primitive (not a 10x-specific concern): pair-shaped tools, REPL companions, and any non-10x consumer needs recent-history access. Locating the buffer in the framework means external tools depend on a stable framework primitive rather than on 10x's internal data structures. See [Tool-Pair §How AI tools attach](Tool-Pair.md#how-ai-tools-attach) for the full consumption pattern.

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

- `events.cljc` — `:warning :rf.warning/interceptors-in-metadata-map`; `:rf.error/effect-map-shape`.
- `subs.cljc` — `:sub/create`, `:sub/run`; `:rf.error/no-such-sub` and `:rf.error/sub-exception` for failure paths.
- `fx.cljc` — `:event/do-fx` per drain step, `:rf.fx/handled` per dispatched fx, `:fx/override-applied`, `:warning :rf.fx/skipped-on-platform`, `:rf.error/fx-handler-exception`, `:rf.error/no-such-fx`, plus `:rf.machine/spawned` and `:rf.machine/destroyed`.
- `router.cljc` — `:event :event` (`:run-start` and `:run-end` phases), `:event :event/dispatched`, `:event :event/db-changed`, `:rf.error/handler-exception`, `:rf.error/drain-depth-exceeded`, `:rf.error/no-such-handler`, `:rf.error/dispatch-sync-in-handler`, `:rf.error/frame-destroyed`, `:rf.error/flow-eval-exception`, `:rf.frame/drain-aborted` (lifecycle event emitted when the drain loop detects a destroyed frame mid-cycle; per [002 §Edge cases worth pinning](002-Frames.md#edge-cases-worth-pinning)).
- `frame.cljc` — `:frame/created`, `:frame/re-registered`, `:frame/destroyed`, `:rf.machine.lifecycle/destroyed`.
- `registrar.cljc` — `:rf.registry/handler-registered`, `:rf.registry/handler-replaced`, `:rf.registry/handler-cleared`.
- `machines.cljc` — `:rf.machine/event-received`, `:rf.machine/transition`, `:rf.machine.microstep/transition` (one per microstep on `:always`-driven cascades, per [005 §Trace events](005-StateMachines.md#trace-events)), `:rf.machine/snapshot-updated`, `:rf.machine.lifecycle/created`, `:rf.machine/system-id-bound`, `:rf.machine/system-id-released` (per [005 §Named addressing via `:system-id`](005-StateMachines.md#named-addressing-via-system-id)), `:rf.machine.timer/scheduled`, `:rf.machine.timer/fired`, `:rf.machine.timer/stale-after`, `:rf.machine.timer/skipped-on-server` (under SSR; per [005 §SSR mode](005-StateMachines.md#ssr-mode)), plus the machine-error categories.
- `routing.cljc` — `:rf.route/url-changed` (covers both full URL transitions and fragment-only navigation; consumers discriminate on `:tags`), `:rf.route/navigation-blocked`, `:route.nav-token/allocated`, `:route.nav-token/stale-suppressed`, `:rf.fx/skipped-on-platform` (route-fx platform skips), `:warning :rf.warning/route-shadowed-by-equal-score`.
- `flows.cljc` — `:rf.flow/registered`, `:rf.flow/computed`, `:rf.flow/skip`, `:rf.flow/cleared`, `:rf.flow/failed` (per [013 §Flow tracing](013-Flows.md#flow-tracing)). All carry `:op-type :flow`.
- `schemas.cljc` — `:rf.error/schema-validation-failure` (from `validate-app-db!` / `validate-event!` / `validate-cofx!` / `validate-sub-return!`).
- `ssr.cljc` — `:warning :rf.warning/multiple-status-set`, `:warning :rf.warning/multiple-redirects`, `:rf.error/sanitised-on-projection`.
- `epoch.cljc` — `:rf.epoch/snapshotted` per drain-settle, `:rf.epoch/restored` on restore success, `:rf.epoch/db-replaced` on `reset-frame-db!` success (rf2-zq55), plus the six restore-failure categories and the two reset-frame-db! failure categories (`:rf.epoch/reset-frame-db-during-drain`, `:rf.epoch/reset-frame-db-schema-mismatch`).
- `views.cljs` — `:view/render` per registered-view render (per [Spec 004 §Render-tree primitives](004-Views.md)).
- `std_interceptors.cljc` — `:rf.error/unwrap-bad-event-shape`.
- `http_managed.cljc` — `:warning :rf.http/cljs-only-key-ignored-on-jvm`, `:warning :rf.warning/decode-defaulted`, `:info :rf.http/retry-attempt`, plus the Spec 014 failure categories.

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

The same pattern applies to `register-epoch-cb`, `trace-buffer`, `clear-trace-buffer!`, and `(rf/configure :trace-buffer …)` — every dev-only call site in user code should sit under the `when ^boolean re-frame.interop/debug-enabled?` guard.

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

1. `implementation/test/re_frame/elision_probe.cljs` is a probe namespace that exercises every gated surface — `register-trace-cb!`, `emit-trace!`, the trace ring buffer (`trace-buffer` / `clear-trace-buffer!` / `(configure :trace-buffer …)`), `validate-{app-db,event,sub-return,cofx}!`, `register!` / `unregister!` / `clear-kind!`, the epoch surface (`register-epoch-cb` / `epoch-history` / `restore-epoch` / `(configure :epoch-history …)`), plus a representative `dispatch-sync` flow. The probe roots the dead-code-elimination graph at every surface so a leak surfaces in the bundle.
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
| Consumer | `register-trace-cb!` listeners, the retain-N ring buffer, `register-epoch-cb` | `performance.getEntriesByType('measure')`, `PerformanceObserver`, Chrome DevTools Performance |
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
| `register-epoch-cb` / `remove-epoch-cb` | ✓ | ✓ |
| Trace ring buffer (`trace-buffer`) | ✓ | ✓ |
| Hot-reload trace events | ✓ | ✓ |
| Performance API instrumentation (`rf:event:*` / `rf:sub:*` / `rf:fx:*` / `rf:render:*` measures) | ✗ | ✓ (default-off; see [§Performance instrumentation](#performance-instrumentation)) |
| 10x panel itself | ✗ | ✓ |
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
 :tags      {:category    :rf.error/<category>   ;; same as :operation, for consumer convenience
             :failing-id  any                    ;; the registered id that failed (event id, fx id, sub id, view id, etc.)
             :reason      string                 ;; one-sentence human description
             :frame       keyword?               ;; (when known) the frame the failure happened in
             ...}}                               ;; category-specific keys
```

`:source` and `:recovery` are top-level fields hoisted out of `:tags` by the runtime; both are present on every error event. `:frame` rides under `:tags` (every emit site that knows the frame supplies it there). The `:tags` payload's category-specific keys are documented per category below, and each category has a registered Malli schema so consumers can validate / branch on the payload safely.

### Error namespace convention — five prefix shapes

Error categories use **five distinct namespace prefixes**:

| Prefix | Meaning | Example |
|---|---|---|
| `:rf.error/<category>` | A genuine runtime error: a contract was violated. | `:rf.error/handler-exception`, `:rf.error/no-such-sub` |
| `:rf.fx/<category>` | An fx-substrate event that rides the error envelope but is not necessarily a failure. | `:rf.fx/skipped-on-platform` |
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
| `:rf.error/no-such-handler` | A dispatch arrived with no registered handler | `:event`, `:kind` |
| `:rf.error/dispatch-sync-in-handler` | `dispatch-sync` was called from inside an event handler's interceptor pipeline (use `:fx [[:dispatch event]]` instead — see [002 §dispatch-sync](002-Frames.md#dispatch-sync)) | `:event`, `:enclosing-event`, `:enclosing-frame` |
| `:rf.error/effect-map-shape` | A `reg-event-fx` handler returned a top-level effect-map key other than `:db` / `:fx` (per [MIGRATION §M-8](MIGRATION.md#m-8-effect-map-keys-consolidated--only-db-and-fx-at-the-top-level)). The runtime drops the offending key and emits one trace per offending key; legal `:db` / `:fx` keys still apply | `:failing-id` (event-id), `:event-id`, `:event` (vector), `:offending-key`, `:value`, `:reason` |
| `:rf.error/override-fallthrough` | An override was specified but no matching id existed | `:overrides-map`, `:looked-up-id` |
| `:rf.fx/handled` | An fx was successfully dispatched (the runtime reached the fx and either ran the registered handler without exception or completed the reserved-fx-id action). Emitted by `re-frame.fx/handle-one-fx` on the success path so the `:rf/epoch-record` `:effects` projection captures one entry per dispatched fx (per [Spec-Schemas §`:rf/epoch-record`](Spec-Schemas.md#rfepoch-record)) | `:fx-id`, `:fx-args`, `:frame` |
| `:rf.fx/skipped-on-platform` | An fx was skipped because its `:platforms` excluded the active platform (per [011](011-SSR.md)) | `:fx-id`, `:fx-args`, `:platform`, `:registered-platforms` |
| `:rf.ssr/hydration-mismatch` | First client render diverges from server-supplied render-tree (per [011](011-SSR.md)) | `:server-hash`, `:client-hash`, `:first-diff-path?` |
| `:rf.warning/plain-fn-under-non-default-frame-once` | A plain (non-`reg-view`) Reagent fn rendered under a non-default frame; routed to `:rf/default`. Emitted at most once per `(component-id, non-default-frame-id)` pair — see [004 §Plain Reagent fns](004-Views.md) | `:fn-name`, `:rendered-under`, `:routed-to` |
| `:rf.warning/no-clock-configured` | A timing-sensitive substrate feature (e.g. state-machine `:after` per [005 §Delayed `:after` transitions](005-StateMachines.md#delayed-after-transitions)) was exercised on a host whose `re-frame.interop` clock primitives (`now-ms` / `schedule-after!` / `cancel-scheduled!`) weren't wired up. The runtime falls back to the host-native clock if available; this advisory surfaces so tests / agents can spot the missing wiring | `:feature` (e.g. `:rf.machine/after`), `:fallback` (the host-native clock used) |
| `:rf.error/duplicate-url-binding` | A second frame attempted `:url-bound? true` while another already owns the URL. Per [012 §Multi-frame routing](012-Routing.md#multi-frame-routing) | `:existing-frame`, `:offending-frame` |
| `:rf.error/system-id-collision` | A spawn whose `:system-id` was already bound in the per-frame `[:rf/system-ids]` reverse index displaced the previous binding. Last-write-wins, matching `reg-event-fx` re-registration semantics. Per [005 §Named addressing via `:system-id`](005-StateMachines.md#named-addressing-via-system-id) and rf2-suue | `:frame`, `:system-id`, `:existing-machine` (the displaced gensym'd id), `:rebound-to` (the new gensym'd id) |
| `:rf.warning/multiple-status-set` | Two or more `:rf.server/set-status` calls in the same request drain. Last-write-wins; advisory for finding the conflicting handlers. Per [011 §Multiple-status policy](011-SSR.md#multiple-status-policy) | `:writes` (vector of `{:status :handler-id :event}` per write), `:final-status` |
| `:rf.warning/multiple-redirects` | Two or more `:rf.server/redirect` calls in the same request drain. Last-write-wins. Per [011 §Redirect precedence](011-SSR.md#redirect-precedence) | `:writes` (vector), `:final-redirect` |
| `:rf.warning/head-mismatch` | Client-computed head model differs from server-supplied head; client re-renders and replaces. Per [011 §Mismatch detection — head](011-SSR.md#mismatch-detection--head) | `:server-hash`, `:client-hash`, `:head-id` |
| `:rf.warning/interceptors-in-metadata-map` | A `reg-event-*` registration carried `:interceptors` inside its metadata-map; the chain is silently dropped. Per [Conventions §`:interceptors` is positional, not metadata](Conventions.md#interceptors-is-positional-not-metadata-reg-event-) and rf2-bbea | `:reg-fn` (the fn's name as a string), `:id`, `:offending-keys`, `:reason` |
| `:rf.error/sanitised-on-projection` | The active error projector threw or returned a non-`:rf/public-error` shape; the runtime fell back to the locked generic-500 public shape. Per [011 §Where sanitisation happens — before render](011-SSR.md#where-sanitisation-happens--before-render) | `:projector-id`, `:original-operation`, `:projection-failure-reason` |
| `:rf.epoch/restore-unknown-epoch` | `restore-epoch` was called with an `epoch-id` that is not in the frame's current epoch history (either never recorded or aged out by `:depth`). Per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel) | `:frame`, `:epoch-id`, `:history-size` |
| `:rf.epoch/restore-schema-mismatch` | The recorded `:db-after` no longer validates against the currently-registered `app-schemas` set (a schema was added, tightened, or replaced since the snapshot was taken). Per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel) | `:frame`, `:epoch-id`, `:schema-digest-recorded`, `:schema-digest-current`, `:failing-paths` |
| `:rf.epoch/restore-missing-handler` | The recorded `app-db` references a registered-id (e.g. an active machine at `[:rf/machines <id>]`, a registered route currently in `:route`) that is no longer present in the registrar. Per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel) | `:frame`, `:epoch-id`, `:missing` (vector of `{:kind :id}`) |
| `:rf.epoch/restore-version-mismatch` | The frame's recorded `:rf/snapshot-version` (per [Spec-Schemas §`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot)) is incompatible with the currently-loaded machine definition. Per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel) | `:frame`, `:epoch-id`, `:machine-id`, `:version-recorded`, `:version-current` |
| `:rf.epoch/restore-during-drain` | `restore-epoch` was called while the frame's run-to-completion drain is still in flight (per [002 §Run-to-completion dispatch](002-Frames.md#run-to-completion-dispatch-drain-semantics)). Restore is rejected; the user retries after settle. Per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel) | `:frame`, `:epoch-id` |
| `:rf.epoch/reset-frame-db-during-drain` | `reset-frame-db!` was called while the frame's drain was still running. Pair-tool injection is rejected; the caller retries after settle. Per [Tool-Pair §Pair-tool writes](Tool-Pair.md#pair-tool-writes--state-injection) (rf2-zq55) | `:frame` |
| `:rf.epoch/reset-frame-db-schema-mismatch` | `reset-frame-db!` was called with a `new-db` value that fails the frame's currently-registered `app-schema` set. The injection is rejected; `app-db` is unchanged. Per [Tool-Pair §Pair-tool writes](Tool-Pair.md#pair-tool-writes--state-injection) (rf2-zq55) and [010 §Per-frame schemas](010-Schemas.md#per-frame-schemas) | `:frame`, `:failing-paths` |
| `:rf.error/no-such-fx` | A dispatched fx-id has no registered handler (and was not redirected by `:fx-overrides`). Per [002 §`:fx` ordering and atomicity guarantees](002-Frames.md#fx-ordering-and-atomicity-guarantees). Emitted by `re-frame.fx/handle-one-fx` after override resolution and reserved-id matching both miss | `:fx-id`, `:fx-args`, `:frame` |
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
| `:rf.error/no-adapter-registered` | `(rf/init!)` was called with no args but no substrate adapter has registered as a default. Consumer must require a substrate ns (`re-frame.substrate.reagent` / `re-frame.substrate.uix` — registration happens at ns-load time) or pass an adapter explicitly. Per [006 §Adapter selection at boot](006-ReactiveSubstrate.md#adapter-selection-at-boot) and rf2-84po. Surfaced as a thrown ex-info, not a trace | `:where` (`'init!`), `:reason` |
| `:rf.error/multiple-default-adapters` | `(rf/init!)` was called with no args but more than one substrate adapter has registered as a default — most often a mixed-substrate app post [rf2-3yij](#) where both Reagent and UIx are required. Consumer must disambiguate via `(rf/init! :reagent)` / `(rf/init! :uix)`. Per [006 §Adapter selection at boot](006-ReactiveSubstrate.md#adapter-selection-at-boot) and rf2-84po. Surfaced as a thrown ex-info, not a trace | `:where` (`'init!`), `:keys` (the registered adapter keys), `:reason` |
| `:rf.error/unknown-adapter-key` | `(rf/init! :keyword)` was called with a key that is not in the default-adapter registry. Either require the substrate ns (which registers itself at ns-load time) or pass the adapter spec map directly. Per [006 §Adapter selection at boot](006-ReactiveSubstrate.md#adapter-selection-at-boot) and rf2-84po. Surfaced as a thrown ex-info, not a trace | `:where` (`'init!`), `:key` (the offending keyword), `:known` (the registered keys), `:reason` |
| `:rf.error/render-on-headless-adapter` | `render` was called on the plain-atom (JVM/SSR) adapter, which only supports `render-to-string` (per [006](006-Substrate.md)) | `:reason` |
| `:rf.error/no-hiccup-emitter-bound` | `render-to-string` was called before the SSR namespace bound the hiccup emitter via `set-hiccup-emitter!` (per [011](011-SSR.md)) | `:reason`, `:render-tree` |
| `:rf.error/flow-cycle` | A flow registration introduced a cycle in the flow-dependency graph (per [013 §Topological ordering](013-Flows.md)). Registration is rejected | `:cycle` (the offending flow ids) |
| `:rf.error/flow-missing-id` | A `reg-flow` call's flow map omitted `:id` (per [013](013-Flows.md)) | `:flow` (the offending map) |
| `:rf.error/flow-bad-inputs` | A `reg-flow` call's flow `:inputs` was not a vector of paths (per [013](013-Flows.md)) | `:flow`, `:reason` |
| `:rf.error/flow-bad-output` | A `reg-flow` call's flow `:output` was not a fn (per [013](013-Flows.md)) | `:flow`, `:reason` |
| `:rf.error/flow-bad-path` | A `reg-flow` call's flow `:path` was not a vector (per [013](013-Flows.md)) | `:flow`, `:reason` |
| `:rf.error/flows-artefact-missing` | A flow API (`reg-flow`, `clear-flow`, the flow fxs) was called but the optional `day8/re-frame-2-flows` artefact is not on the classpath. Per [MIGRATION §M-31 artefact splits](MIGRATION.md). Surfaced as a thrown ex-info, not a trace | `:where` (the calling fn), `:reason` |
| `:rf.error/ssr-artefact-missing` | An SSR API (`render-to-string`, `render-tree-hash`, `reg-error-projector`, `project-error`) was called but the optional `day8/re-frame-2-ssr` artefact is not on the classpath. Surfaced as a thrown ex-info, not a trace | `:where` (the calling fn), `:reason` |
| `:rf.error/routing-artefact-missing` | A routing API (`reg-route`, `match-url`, `route-url`) was called but the optional `day8/re-frame-2-routing` artefact is not on the classpath. Surfaced as a thrown ex-info, not a trace | `:where` (the calling fn), `:reason` |
| `:rf.error/schemas-artefact-missing` | A schemas API (`reg-app-schema`) was called but the optional `day8/re-frame-2-schemas` artefact is not on the classpath. Surfaced as a thrown ex-info, not a trace | `:where` (the calling fn), `:path`, `:reason` |
| `:rf.error/machines-artefact-missing` | A machines API (`reg-machine`, `reg-machine*`) was called but the optional `day8/re-frame-2-machines` artefact is not on the classpath. Surfaced as a thrown ex-info, not a trace | `:where` (the calling fn), `:machine-id`, `:reason` |
| `:rf.error/http-artefact-missing` | A managed-HTTP API was called but the optional `day8/re-frame-2-http` artefact (per [014 §Implementation status](014-HTTPRequests.md#implementation-status)) is not on the classpath. Surfaced as a thrown ex-info, not a trace | `:where` (the calling fn), `:reason` |
| `:rf.warning/route-shadowed-by-equal-score` | A `reg-route` registered a pattern whose [`:rf/route-rank`](Spec-Schemas.md#rfroute-rank) tuple equals an already-registered pattern's; the new route shadows the old per stable sort order (per [Spec-Schemas §`:rf/route-rank`](Spec-Schemas.md#rfroute-rank) and [012 §Route ranking algorithm](012-Routing.md#route-ranking-algorithm)) | `:route-id` (the new id), `:shadowed` (the displaced id) |
| `:rf.warning/decode-defaulted` | A managed-HTTP request fell through to the default `:auto` decode pipeline because no `:decode` was supplied (per [014 §Default decode](014-HTTPRequests.md)). Informational; the auto-decode is supported | `:request-id`, `:url`, `:content-type`, `:resolved-decoder` |
| `:rf.warning/write-after-destroy` | The substrate adapter's `replace-container!` was called with a nil container — the frame was likely destroyed mid-drain or before a scheduled write fired. Per [006](006-Substrate.md) | `:reason` |
| `:rf.http/cljs-only-key-ignored-on-jvm` | A managed-HTTP request supplied a CLJS-only request key (`:mode`, `:cache`, `:referrer`, `:integrity`) that the JVM transport cannot honour. The key is ignored. Per [014](014-HTTPRequests.md). `:op-type :warning` | `:key`, `:url` |
| `:rf.http/retry-attempt` | A managed-HTTP attempt failed with a retryable category and the runtime is scheduling another attempt. Per [014 §Retry semantics](014-HTTPRequests.md#retry-semantics). `:op-type :info` | `:request-id`, `:url`, `:attempt`, `:max-attempts`, `:failure` (a `:rf.http/*` failure-category map), `:next-backoff-ms` |
| `:route.nav-token/stale-suppressed` | An async result arrived carrying a `:nav-token` that no longer matches the active route's token; the result is silently suppressed. Per [012 §Navigation tokens](012-Routing.md#navigation-tokens--stale-result-suppression). `:op-type :error` (the suppression is the failure mode the consumer needs to see) | `:carried-token`, `:current-token`, `:event-id` |
| `:rf.frame/drain-aborted` | A frame's drain loop detected `(:destroyed? (:lifecycle frame))` mid-cycle; remaining queued events are dropped. Per [002 §Edge cases worth pinning](002-Frames.md#edge-cases-worth-pinning). Lifecycle event, not error-shaped: `:op-type :event`, no `:recovery` | `:frame`, `:dropped-count` |

`:rf.fx/skipped-on-platform` is technically a *warning* not an error, but it rides the same envelope and routes through the same listener path; consumers can branch on `:op-type` (`:warning` vs `:error`) if they want to distinguish.

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
- `:skipped` — the runtime declined to act (`:rf.fx/skipped-on-platform`).
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

| Category | Default `:recovery` | Notes |
|---|---|---|
| `:rf.error/handler-exception` | `:no-recovery` | The exception propagates; the cascade halts. |
| `:rf.error/machine-action-exception` | `:no-recovery` | The machine cascade halts atomically: the snapshot does not commit (pre-action `[:rf/machines <id>]` slice is preserved), accumulated `:fx` from earlier slots in the same Level-2 cascade is dropped, and the `:always` microstep does not fire on the failed cascade. |
| `:rf.error/fx-handler-exception` | `:no-recovery` | The fx is skipped; cascade continues if other fx independent. |
| `:rf.error/sub-exception` | `:replaced-with-default` | The sub returns `nil`; views see no value. |
| `:rf.error/no-such-sub` | `:replaced-with-default` | The unresolved input is substituted with `nil`; the sub's body still runs. Strict mode (`:strict-subs` config) escalates to a thrown exception. |
| `:rf.error/schema-validation-failure` | `:no-recovery` (dev) / `:replaced-with-default` (prod) | Dev: hard-fail to surface bugs early. Prod: log and proceed with the offending value (validation at boundaries should already have rejected it). |
| `:rf.error/drain-depth-exceeded` | `:no-recovery` | Always indicates a bug; halt the cascade. |
| `:rf.error/no-such-handler` | `:replaced-with-default` | No-op; emit the trace. |
| `:rf.error/dispatch-sync-in-handler` | `:no-recovery` | The call is rejected. Use `:fx [[:dispatch event]]` in the effect map. |
| `:rf.error/effect-map-shape` | `:logged-and-skipped` | The offending top-level key is dropped; `:db` and `:fx` still apply. One trace per offending key. Per [MIGRATION §M-8](MIGRATION.md#m-8-effect-map-keys-consolidated--only-db-and-fx-at-the-top-level). |
| `:rf.error/override-fallthrough` | `:replaced-with-default` | Use the registered fx as if no override existed. |
| `:rf.fx/skipped-on-platform` | `:skipped` | Documented; not really an error. |
| `:rf.ssr/hydration-mismatch` | `:warned-and-replaced` | Re-render client-side; the server's HTML is replaced. |
| `:rf.warning/multiple-status-set` | `:warned-and-replaced` | Last-write wins; advisory only. |
| `:rf.warning/multiple-redirects` | `:warned-and-replaced` | Last-write wins; advisory only. |
| `:rf.warning/head-mismatch` | `:warned-and-replaced` | Client renders its head; server's is replaced. |
| `:rf.warning/interceptors-in-metadata-map` | `:ignored` | The mis-placed `:interceptors` chain is dropped; registration completes with no positional interceptors. |
| `:rf.error/sanitised-on-projection` | `:replaced-with-default` | Runtime falls back to the locked generic-500 public-error shape. |
| `:rf.epoch/restore-unknown-epoch` | `:no-recovery` | Restore rejected; the frame's state is unchanged. Per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel). |
| `:rf.epoch/restore-schema-mismatch` | `:no-recovery` | Restore rejected; the frame's state is unchanged. |
| `:rf.epoch/restore-missing-handler` | `:no-recovery` | Restore rejected; the frame's state is unchanged. |
| `:rf.epoch/restore-version-mismatch` | `:no-recovery` | Restore rejected; the frame's state is unchanged. |
| `:rf.epoch/restore-during-drain` | `:no-recovery` | Restore rejected; the user retries after settle. |
| `:rf.epoch/reset-frame-db-during-drain` | `:no-recovery` | Pair-tool injection rejected; the caller retries after settle. Per [Tool-Pair §Pair-tool writes](Tool-Pair.md#pair-tool-writes--state-injection) (rf2-zq55). |
| `:rf.epoch/reset-frame-db-schema-mismatch` | `:no-recovery` | Pair-tool injection rejected; `app-db` is unchanged. The new-db failed the frame's registered app-schema set; the failing paths are surfaced in `:tags :failing-paths`. |
| `:rf.error/no-such-fx` | `:no-recovery` | The fx is dropped; cascade continues with remaining `:fx` entries. |
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
| `:rf.error/flow-cycle` | `:no-recovery` | Flow registration is rejected. |
| `:rf.error/flow-missing-id` | `:no-recovery` | Flow registration is rejected. |
| `:rf.error/flow-bad-inputs` | `:no-recovery` | Flow registration is rejected. |
| `:rf.error/flow-bad-output` | `:no-recovery` | Flow registration is rejected. |
| `:rf.error/flow-bad-path` | `:no-recovery` | Flow registration is rejected. |
| `:rf.error/flows-artefact-missing` | `:no-recovery` | The call throws an ex-info; user adds `day8/re-frame-2-flows` to deps. |
| `:rf.error/ssr-artefact-missing` | `:no-recovery` | The call throws an ex-info; user adds `day8/re-frame-2-ssr` to deps. |
| `:rf.error/routing-artefact-missing` | `:no-recovery` | The call throws an ex-info; user adds `day8/re-frame-2-routing` to deps. |
| `:rf.error/schemas-artefact-missing` | `:no-recovery` | The call throws an ex-info; user adds `day8/re-frame-2-schemas` to deps. |
| `:rf.error/machines-artefact-missing` | `:no-recovery` | The call throws an ex-info; user adds `day8/re-frame-2-machines` to deps. |
| `:rf.error/http-artefact-missing` | `:no-recovery` | The call throws an ex-info; user adds `day8/re-frame-2-http` to deps. |
| `:rf.warning/route-shadowed-by-equal-score` | `:warned-and-replaced` | The new route registers; equal-score sibling is shadowed by stable sort. |
| `:rf.warning/decode-defaulted` | `:ignored` | Informational; auto-decode proceeds normally. |
| `:rf.warning/write-after-destroy` | `:no-recovery` | The write is dropped; the substrate's `replace-container!` is not invoked. |
| `:rf.http/cljs-only-key-ignored-on-jvm` | `:ignored` | The unsupported key is dropped; the request proceeds with the remaining keys. |
| `:rf.http/retry-attempt` | `:retried` | A new attempt is scheduled; the consumer sees the trace and the eventual final outcome via `:on-failure` / `:on-success`. |
| `:route.nav-token/stale-suppressed` | `:logged-and-skipped` | The async reply is suppressed; the active navigation cascade continues unchanged. |
| `:rf.frame/drain-aborted` | n/a | Lifecycle event, not error-shaped. Remaining queued events are dropped silently. |

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

### Privacy / sensitive data in traces

Trace events contain dispatched event vectors, which may include user input (passwords, PII). Tools that ship traces (e.g., to monitoring services) must redact. Recommendation: provide a `:sensitive?` tag that handlers can set; a `with-redacted` interceptor pattern that strips sensitive args before emit.

## Resolved decisions

### Listener ordering

Multiple listeners may register concurrently. Order is registration order (a `swap! assoc` on a single atom). Tools must not depend on order — they each receive the same event independently.

### Trace correlation across the cascade

Use the `:dispatch-id` / `:parent-dispatch-id` fields under `:tags` on `:event/dispatched` events (per [§Dispatch correlation](#dispatch-correlation-dispatch-id--parent-dispatch-id)). Tools that need cascade trees walk `:parent-dispatch-id` upward. Per-cascade structured projection lives in the assembled `:rf/epoch-record` (per [Spec-Schemas](Spec-Schemas.md#rfepoch-record)).

### Trace event for app-db changes

`:db` mutations happen inside `do-fx`. The runtime emits a separate `:event/db-changed` trace event on every dispatch whose handler returned a new db value. Tools that want before/after pairs read the `:rf/epoch-record`'s `:db-before` / `:db-after` slots, which the runtime captures atomically across the cascade rather than per-event.
