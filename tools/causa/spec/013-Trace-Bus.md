# 013-Trace-Bus

Causa's **trace bus** is the data plane every panel reads from. It is a
ring buffer of `:rf/trace-event` records that Causa maintains alongside
the framework's own [retain-N trace ring buffer](../../../spec/009-Instrumentation.md#retain-n-trace-ring-buffer-dev-only),
fed by a collector registered against the framework's
[`register-trace-cb!`](../../../spec/009-Instrumentation.md#user-side-listener-registration)
listener API.

This doc defines the bus's substrate normatively: what it collects,
how it filters, what consumers are guaranteed to see, and how the
privacy gate from [Spec 009 §Privacy](../../../spec/009-Instrumentation.md#privacy--sensitive-data-in-traces)
folds into the collector path. The bus is the foundation under
[`001-Causality-Graph.md`](./001-Causality-Graph.md),
[`004-App-DB-Diff.md`](./004-App-DB-Diff.md),
[`005-Schema-Timeline.md`](./005-Schema-Timeline.md),
[`012-Views.md`](./012-Views.md), and every other tab content
that reads trace data; those tabs project from this buffer.

## Why a separate Causa buffer

The framework already maintains a retain-N ring at
`re-frame.trace/trace-buffer` (default depth 200; see
[Spec 009 §Retain-N trace ring buffer](../../../spec/009-Instrumentation.md#retain-n-trace-ring-buffer-dev-only)).
Causa MUST NOT reuse that buffer as its primary store. Two reasons:

1. **Depth independence.** The framework's depth is tuned for the
   framework's consumers and Spec 009 explicitly caps it conservatively
   (events accumulate during dispatch storms). Causa's panels —
   especially the causality graph and the event log — want a deeper
   history once the panel is open, without forcing the framework
   default deeper for every other consumer.
2. **Pre-shaping for panel reads.** Causa applies its own filter
   projections on push (per
   [`Principles.md`](./Principles.md) §Observation only — no new
   runtime surfaces) so the UI reads pre-shaped data rather than
   re-deriving on every render. Re-using the framework buffer would
   couple every panel-side render to the framework's raw event shape.

The Causa buffer is therefore an **independent ring** populated by a
collector registered as the trace-bus key `:rf.causa/trace-collector`.

## Inputs

The collector subscribes to **every** `:rf/trace-event` the framework
emits via `register-trace-cb!` (per
[Spec 009 §Listener registration](../../../spec/009-Instrumentation.md#user-side-listener-registration)).
No filter is applied at the listener boundary; filtering is consumer-side
(see [§Consumer contract](#consumer-contract) below).

Event shape is the canonical `:rf/trace-event` schema per
[Spec-Schemas §`:rf/trace-event`](../../../spec/Spec-Schemas.md#rftrace-event)
— `:id`, `:operation`, `:op-type`, `:tags`, `:time`, and the optional
top-level `:sensitive?` boolean (per
[Spec 009 §Privacy](../../../spec/009-Instrumentation.md#privacy--sensitive-data-in-traces)).
Causa does **not** mutate the event shape; what the framework emits is
what panels read.

## Buffer semantics

### Capacity

The buffer's default capacity is **1000 events** — five times the
framework default. The cap balances Causa's `007-UX-IA.md` §Performance
budget commitment ("the causality graph caps at the last 200
dispatches") with headroom for the non-dispatch trace events
(`:fx`, `:render`, `:sub-run`, error / warning, machine transitions)
that share the same buffer.

Hosts MAY override the default via the configuration knob (see
[§Configuration](#configuration)). Setting depth to zero MUST keep the
collector wired (so the callback can be replaced or augmented) but
flush the buffer and prevent further accumulation.

### Eviction policy

The buffer is a **bounded FIFO ring** ordered by trace-event emission
order:

- New events MUST be appended at the tail.
- When the buffer exceeds its configured depth, the oldest event MUST
  be evicted from the head.
- Eviction is **lossy-on-overflow** — a dropped event is gone. Causa
  MUST NOT raise backpressure on the framework's emit path; the
  framework's trace emission is fire-and-forget per
  [Spec 009 §Delivery semantics](../../../spec/009-Instrumentation.md#listener-invocation-rules).

The same `push`-then-evict algebra applies on both CLJS and JVM (the
collector body is CLJC-pure-data; see
[§JVM behaviour](#jvm-behaviour)).

### Ordering guarantees

Events in the buffer MUST be ordered by their `:id` field — the
framework's monotonic event counter (per
[Spec 009 §Trace event ordering](../../../spec/009-Instrumentation.md#user-side-listener-registration)).
Consumers MAY rely on:

- Oldest entries at the head of the vector (index 0).
- Newest entries at the tail.
- Strict total order: for any two events `a`, `b` in the buffer,
  `(:id a) < (:id b)` iff `a` appears before `b`.

Consumers MUST NOT rely on:

- A specific `:id` starting value (the framework's counter is
  monotonic-from-runtime-boot, not from session start).
- Contiguous `:id` values (overflow eviction creates gaps).
- Wall-clock ordering across host clock adjustments (use `:id` for
  ordering, `:time` for human-readable timestamps).

### Retention window

The buffer holds the most-recent N events where N is the configured
depth. Causa does **not** maintain time-window retention (e.g. "the
last 60 seconds"); the only retention dimension is event-count.
Panels that render a time axis (per
[`005-Schema-Timeline.md`](./005-Schema-Timeline.md),
[`007-UX-IA.md`](./007-UX-IA.md) §Trace timeline) MUST derive the
window from `:time` on the events they read, not from buffer-side
retention.

## Privacy gate

Per [Spec 009 §Privacy](../../../spec/009-Instrumentation.md#privacy--sensitive-data-in-traces)
(resolved by `rf2-a32kd`) and bead `rf2-azls9`: framework-published
trace-consuming integrations MUST default-suppress `:sensitive? true`
events. Causa is a framework-published consumer.

The collector MUST gate every incoming event on the `:sensitive?` flag
before any buffer push:

1. If `:sensitive?` is **absent or `false`**, the event is pushed
   unchanged.
2. If `:sensitive?` is **`true`** AND the
   `:trace/show-sensitive?` flag is `false` (the default), the event
   MUST be **dropped** before the buffer push. The collector MUST
   bump a per-frame suppressed-events counter (keyed by the event's
   `:tags :frame`, or `:global` when no frame scope is present) so the
   shell's bottom rail can surface a `[● REDACTED N]` indicator.
3. If `:sensitive?` is `true` AND the `:trace/show-sensitive?` flag is
   `true`, the event is pushed unchanged.

The flag is read **at the head of the collector body** on every event,
so toggling it via `(causa-config/configure! {:trace/show-sensitive?
true})` takes effect on the next trace event without re-registering
the listener. The default is `false` — suppress sensitive events.

The suppressed-events counter is keyed `frame-id → count` with a
`:global` bucket for events without a frame scope (registration-time
emits, outermost-dispatch lookup failures). Consumers MAY read either
the total (across every bucket — what the bottom-rail indicator
shows) or a per-frame count. Counters MUST reset on `clear-buffer!`
(see [§Lifecycle operations](#lifecycle-operations)).

The redaction indicator's UI shape is owned by
[`007-UX-IA.md`](./007-UX-IA.md) §Bottom rail; this doc owns the
counter contract.

## Consumer contract

Panels read the buffer through **pure-data accessors**. No re-frame
subscription layer wraps the buffer state — panels deref the buffer
atom directly through Causa's read-side fns, and Causa's reactive
plumbing (per `006-Hydration-Debugger.md` and panel-specific specs)
projects the result. This keeps the buffer hot-path cheap (no sub-cache
recomputation per push) and consistent across CLJS and JVM.

## Reactivity

The buffer is held in a plain atom (process-global, not per-frame).
The CLJS `:rf.causa/trace-buffer` sub (registered in `registry.cljs`)
is layer-1 and reads `(get db :trace-buffer)`, falling through to
`trace-bus/buffer` pre-mount.

**Current implementation (rf2-in6l2 + rf2-wq6gx).** Panels are
reg-view-wrapped and resolve to the `:rf/causa` frame via the
React-context tier; `trace-bus/request-mirror-sync!` coalesces every
same-tick push / clear / depth-shrink request into ONE
`:rf.causa/sync-trace-buffer` dispatch carrying the atom's current
snapshot. The sub re-fires on the standard app-db-write reactive path
so panels re-render on the next microtask after a flush. The coalesced
design caps the mirror cascade at depth 1 regardless of host trace-
event volume — `re-frame.router/drain-depth-default` (= 100) can never
gate the mirror under saturation (e.g. a synthetic load of 1000
trace events landing in one JS task). The pre-rf2-wq6gx per-event
mirror saturated at ~100 events and lost the rest to the router's
rollback path; the coalesced design eliminates that failure mode
structurally.

### History (rf2-iw5ym → rf2-e9s81 → rf2-in6l2 → rf2-wq6gx)

`rf2-iw5ym` briefly mirrored the buffer into Causa's app-db at
`[:rf/causa :trace-buffer]` via a parallel `:rf.causa/note-trace-event`
dispatch from `collect-trace!` — chasing the same "immediate re-fire
on every push" recipe `rf2-0vxdn` shipped for `:suppressed-counters`.
That parallel path proved untenable for the trace buffer (the
process-global construct does not fit the per-frame app-db mirror
shape — the suppressed-counters case was a genuinely per-frame map
keyed on `frame-id`, so the analogy did not transfer):

- `:rf/causa` is never registered in production. The preload runs
  at ns-load time, before the host's `rf/init!` installs a substrate
  adapter; `reg-frame` cannot run in that window. The dispatch
  silently no-op'd and step 5 of `tools/causa/testbeds/counter-driven/spec.cjs` went
  red (the `rf2-e9s81` regression).
- Chain-resolving to `:rf/default` (the recipe `rf2-higwg` applied
  to the suppressed-counter path) consumed drain-depth headroom on
  every emitted trace event and polluted the host's app-db with
  `:trace-buffer` noise — surfacing as spurious
  `:rf.error/drain-depth-exceeded` failures in conformance fixtures.

`rf2-e9s81` reverts to the pre-iw5ym shape (sub thunks the atom;
host-driven recompute). An architecturally clean reactive-on-every-
push surface for the trace buffer would require either reg-view-
wrapping every Causa panel (so plain-fn subscribes route to
`:rf/causa` via the React-context tier) or a fixed-frame sub
mechanism that lets a sub read from an arbitrary frame's app-db
independent of the subscriber's frame — both wider refactors filed
for follow-up. Until then the host-driven recompute path delivers
the same UX without the layering hazards.

### What consumers MAY rely on

- A **point-in-time snapshot** of the buffer as a Clojure vector,
  oldest-first.
- **Total ordering by `:id`** within the snapshot.
- **Pure-data shape** — every event is a plain Clojure map matching
  the `:rf/trace-event` schema; no transient state, no atoms, no
  derefs required to read fields.
- **Idempotent reads** — calling the accessor twice in the same drain
  cycle returns the same vector (the framework emits within drains;
  see [Spec 009 §Delivery semantics](../../../spec/009-Instrumentation.md#listener-invocation-rules)).

### What consumers MUST NOT rely on

- **Eventually-receiving every emitted event.** The buffer is
  lossy-on-overflow; a panel that mounts mid-storm sees only the
  most-recent N. The framework's `register-trace-cb!` is the
  zero-loss alternative; the trace bus is the late-attach affordance.
- **Causa-side filtering of `:sensitive?` events being reversible
  from the buffer.** Once dropped, the event is gone; flipping
  `:trace/show-sensitive?` from `false` to `true` only affects
  *future* events. Suppressed events are counted, not retained. (See
  the redaction-indicator follow-on bead for the design contract on
  whether this is the right trade.)
- **Buffer contents surviving a `clear-buffer!`.** Tooling clears the
  buffer between sessions (per [§Lifecycle operations](#lifecycle-operations));
  consumers MUST treat a cleared buffer as empty.

### Filter vocabulary

Panels that slice the buffer MUST use the same filter vocabulary the
framework exposes on `(rf/trace-buffer opts)` (per
[Spec 009 §Filter vocabulary](../../../spec/009-Instrumentation.md#filter-vocabulary)
and `rf2-97ah0` / `rf2-qi8au`). Causa exposes the filter algebra as a
pure-data fn against an arbitrary event vector so panel-side slicing
locks the consumer contract: when Causa renders a filtered view,
the filter axes are the framework's filter axes.

The recognised filter keys (eleven axes; compose AND-wise; absent
key = no constraint):

| Key | Type | Match against |
|---|---|---|
| `:operation` | keyword | `:operation` field |
| `:op-type` | keyword | `:op-type` field |
| `:since` | number | `:id` strictly greater than |
| `:frame` | keyword | `:frame` (top-level or `:tags :frame`) |
| `:severity` | `:error` / `:warning` / `:info` | `:op-type` (synonym restricted to those three values) |
| `:event-id` | keyword | `:tags :event-id` |
| `:handler-id` | keyword | `:tags :handler-id` |
| `:source` | keyword | `:source` (top-level, hoisted by emit) or `:tags :source` |
| `:origin` | keyword | `:tags :origin` |
| `:dispatch-id` | keyword | `:tags :dispatch-id` (cascade-wide per `rf2-g6ih4`) |
| `:since-ms` | number | `:time` strictly greater than |
| `:between` | `[t0 t1]` | `:time` falls in `[t0, t1]` inclusive |
| `:pred` | `(fn [ev] → truthy)` | Arbitrary predicate |

A `:sensitive?` filter row is **deliberately absent** from this
vocabulary. The privacy gate operates at *push time*, not at
consumer-side filter time — by the time a panel reads the buffer,
sensitive events have already been dropped (under the default
configuration). Consumers needing the raw cascade flip
`:trace/show-sensitive?` and re-drive the runtime.

Unrecognised filter keys MUST be ignored (forward-compat: panels
may probe new axes the framework gains in future spec amendments;
missing support degrades to "no filter on that axis"). This mirrors
the framework's forward-compat posture in
[Spec 009 §Filter vocabulary](../../../spec/009-Instrumentation.md#filter-vocabulary).

## Lifecycle operations

The trace bus exposes three mutation surfaces; all are no-ops in
production.

| Operation | Effect |
|---|---|
| `clear-buffer!` | Empty the buffer; reset every per-frame suppressed-sensitive counter. Tooling uses this between sessions. The counter reset is paired so the `[● REDACTED N]` indicator disappears alongside the cleared events — clearing the buffer is the natural moment to drop the "you missed N events" overhang. |
| `set-buffer-depth!` | Replace the depth. Depth zero keeps the collector wired but flushes the buffer to empty and prevents further accumulation. Depths smaller than the current buffer size MUST drop oldest events first. |
| (Buffer read) | Return the current vector contents, oldest-first. |

`clear-buffer!` is invoked from:

- The Settings panel's "Clear buffer" button (per
  [`007-UX-IA.md`](./007-UX-IA.md) §Settings).
- Test fixtures that need a clean buffer between assertions.
- (Optionally) panel-level "clear" actions where a panel wants to
  reset its view's underlying data — though Causa SHOULD prefer
  panel-scoped filter resets to global buffer clears.

`set-buffer-depth!` is invoked from:

- The Settings panel's buffer-depth slider.
- The `(causa/init! {:buffer-depths {:trace N}})` boot-time path
  (per [`API.md`](./API.md) §Installation API).

## Configuration

The buffer's runtime knobs are owned by
[`day8.re-frame2-causa.config`](./API.md#installation-api):

| Key | Type | Default | Meaning |
|---|---|---|---|
| `:trace/show-sensitive?` | boolean | `false` | When `false`, the collector drops events carrying `:sensitive? true` and bumps the suppressed counter. When `true`, every event passes through unchanged. |
| `:buffer-depths {:trace N}` | number | `1000` | Maximum buffer capacity. Zero disables accumulation. |

Both keys are read on every push (no listener re-registration is
required when they change). Setting either at boot is the typical
host pattern:

```clojure
(causa-config/configure!
  {:trace/show-sensitive? true       ;; debugging redaction policy
   :buffer-depths         {:trace 5000}})
```

The full `configure!` key surface is enumerated normatively in
[`015-Configuration.md`](./015-Configuration.md) (rf2-imw8w); this doc
enumerates only the keys that affect the bus's collector path.

## Edge cases

### Pre-mount events

The collector is registered at preload time (per
[`API.md`](./API.md) §Installation API), *before* any frame mounts. The
framework's `register-trace-cb!` listener API delivers every event
emitted after registration; events emitted between framework boot
and Causa preload (registration-time emits inside the
implementation's own boot path) MAY be missed. Panels MUST tolerate
an empty buffer at first paint.

### Dormant frames

When a frame is dormant (per [Spec 002 §Dormant frames](../../../spec/002-Frames.md)),
the framework does not emit trace events for it. The Causa buffer
neither receives nor evicts on its behalf. Panels filtering by
`:frame` for a dormant frame MUST render the "no recent activity"
empty state, not a stale snapshot.

### Hydration phase

During SSR hydration (per [Spec 011](../../../spec/011-SSR.md)), the
framework emits hydration-specific trace events
(`:rf.ssr/hydration-mismatch` and peers). These ride the same bus
under the same eviction discipline; the hydration debugger
([`006-Hydration-Debugger.md`](./006-Hydration-Debugger.md)) filters
the buffer for them rather than maintaining a separate substrate.

### Multi-frame fan-out

A single dispatch may fan out across frames (per
[Spec 002 §Cross-frame dispatch](../../../spec/002-Frames.md)). Each
emit on each frame is its own trace event with the same
`:dispatch-id` and different `:tags :frame`. The buffer stores every
event; consumers projecting "cascade across frames" group by
`:dispatch-id` (per `rf2-g6ih4`).

### Privacy gate at registration boundaries

A trace event emitted *outside* any registration's `:sensitive?`
scope carries no `:sensitive?` flag — the field is absent and the
collector treats it as `false`. This MUST NOT be confused with an
explicit `:sensitive? false` (which is also passed through but
documents that the registration affirmatively opted **out** of the
flag). Both flow through the same code path.

## JVM behaviour

The bus is **CLJC**. The pure-data ring-buffer helpers (`push` and
`filter-events`) are JVM-runnable so the JVM test suite can drive
every eviction edge and every filter axis without booting a CLJS
runtime. The CLJS-only side-effecting bits — the listener
registration, the atom-backed buffer-state and counter atoms — live
in the preload.

JVM-hosted Causa surfaces (pair-tool dashboards rendering epoch
records server-side; see
[`012-Views.md`](./012-Views.md) §JVM behaviour for
the parallel pattern) read the buffer through the same accessor
contract; the live CLJS-only sub-cache is the only differentiator
across hosts.

## Production elision

Per [`Principles.md`](./Principles.md) §Production elision is
non-negotiable: the trace bus elides entirely in production builds.
The collector body is gated on `re-frame.interop/debug-enabled?`
(alias of `goog.DEBUG`); production builds with
`:advanced` + `goog.DEBUG=false` drop the buffer atom, the listener
registration, and the per-event push entirely. The buffer accessor
returns the empty vector in production. Causa contributes a sentinel
to the elision verifier so `npm run test:elision` ([Spec 009
§Production builds](../../../spec/009-Instrumentation.md#production-builds-zero-overhead-zero-code))
blocks any leak.

## Vision — trace fattening for context-at-position

**Bug class:** "I want to replay this instance's history from epoch 47
to epoch 53 — what was the `app-db` state at each step? what subs were
cached? what was the in-flight HTTP set?"

**v1 ships:** trace events carry an opaque `:dispatch-id` /
`:parent-dispatch-id` linkage plus `:tags` payloads. Replay from
arbitrary position requires re-applying the events forward from the
last snapshot, which loses cache state and in-flight context.

**Future:** trace events grow **context-at-position** payloads — per
trace event, the runtime stamps a compact reference to the cache state,
in-flight set, and machine snapshot at the time of emission. This
enables:

- **Per-instance Phase-5 replay** — the per-instance mini-scrubber
  (003 §Per-instance mini-scrubber) can rebuild full context at any
  position in an instance's arc without re-running.
- **Side-by-side epoch diff** — pin two arbitrary epochs (004 §Pin two
  epochs side-by-side); diff their cache state and in-flight set, not
  just app-db.
- **"What was running when this fired?"** — for any trace event,
  surface the in-flight HTTP, the spawned machines, the queued
  `:dispatch-later` arrivals at the moment of emission.

The fattening is **opt-in via configure!** (`:trace/fatten? true`) and
elides in production. The runtime memory cost is significant (one
reference per event); the developer cost when the feature is needed is
prohibitive without it.

## Vision — wall-clock axis in the Trace tab

Per [`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) §1.1,
the Trace tab grows a **wall-clock axis** for timer rings, retry
waterfalls, deferred-dispatch arrivals, streaming SSR boundary
resolutions. The axis is rendered as a vertical time-strip on the
left edge of the Trace tab; trace events plot against wall-clock time
not just event sequence. Toggle via `t`-key chord or Settings →
Trace → "Show wall-clock axis."

## Cross-references

- [Spec 009 §Listener registration](../../../spec/009-Instrumentation.md#user-side-listener-registration)
  — the upstream `register-trace-cb!` API the collector consumes.
- [Spec 009 §Retain-N trace ring buffer](../../../spec/009-Instrumentation.md#retain-n-trace-ring-buffer-dev-only)
  — the framework's own ring buffer the Causa bus runs alongside.
- [Spec 009 §Filter vocabulary](../../../spec/009-Instrumentation.md#filter-vocabulary)
  — the filter axes the consumer-side filter algebra MUST honour.
- [Spec 009 §Privacy](../../../spec/009-Instrumentation.md#privacy--sensitive-data-in-traces) —
  the `:sensitive?` semantics the collector gates on.
- [Spec-Schemas §`:rf/trace-event`](../../../spec/Spec-Schemas.md#rftrace-event)
  — the event shape consumers project from.
- [`Principles.md`](./Principles.md) §Observation only — no new
  runtime surfaces — the discipline that keeps Causa downstream.
- [`007-UX-IA.md`](./007-UX-IA.md) §Bottom rail — the
  `[● REDACTED N]` indicator the suppressed-counter feeds.
- [`API.md`](./API.md) §Trace / epoch surfaces — the consumer-facing
  surface enumeration.
- [Conventions §Reserved keyword namespaces](../../../spec/Conventions.md)
  — the `:rf.causa/` namespace the collector key sits under.
