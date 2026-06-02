# 013-Trace-Consumer

Xray reads from the **framework's per-frame trace rings** + a small
**Xray-side secondary ring** for frameless emits. This document
defines normatively what Xray's consumer-side surface adds on top of
the framework's substrate — the self-noise filter, the privacy gate,
the suppressed-events counter, the frameless secondary ring, the
microtask-coalesced mirror sync, and the retroactive-scrub-on-toggle-
off behaviour.

The framework owns the data plane: per-frame cascade-keyed rings with
B4 dedup, depth knob via `(rf/configure! :trace-buffer
{:cascades-retained N})`, oldest-first cascade vectors via
`(rf/trace-buffer frame-id opts)`. Per [Spec 009 §Per-frame trace
rings](../../../spec/009-Instrumentation.md#per-frame-trace-rings-cascade-keyed-dev-only)
and bead `rf2-g1b2m`.

This doc is the Xray-specific consumer contract on top.

## Background — what changed at rf2-43koh

Pre-rf2-43koh Xray ran its own 1000-event process-global ring (`tools/
xray/src/day8/re_frame2_xray/trace_bus.cljc`) parallel to the
framework's. The "two rings" architecture predated the per-frame
cascade-keyed rings (`rf2-g1b2m` / `rf2-8uwce`) and the B4 dedup
contract; both ate the original motivation for a separate Xray ring
(depth independence + pre-shaping). The retire bundle is:

- `trace_bus.cljc` deleted.
- The self-noise predicates live in
  [`self_noise.cljc`](../src/day8/re_frame2_xray/self_noise.cljc)
  (pure data, JVM-runnable; rf2-3g9nw D4=a).
- The listener body + privacy gate + frameless secondary ring +
  microtask coalescer + retroactive scrub live in
  [`trace_collector.cljs`](../src/day8/re_frame2_xray/trace_collector.cljs)
  (CLJS-only side effects; rf2-3g9nw D5=a).
- The Settings buffer-depth slot renamed
  `:trace-buffer/keep` → `:cascades-retained` (events → cascades;
  default 1000 → 50; rf2-3g9nw D1=a).
- Production reactive surface stays microtask-coalesced; tests get a
  synchronous `refresh-trace-rings!` entrypoint (rf2-3g9nw D3=b).

## The consumer pipeline

```
re-frame.trace/emit!              ;; framework
  → re-frame.trace.tooling/        ;; framework: per-frame ring push
      deliver-to-tooling!          ;;            + listener fan-out
        → push-to-ring!            ;;   (frame-bound events only)
        → listeners
            → trace-collector/     ;; xray-side listener body
                collect-trace!     ;;   self-noise drop → privacy gate
                                   ;;   → frameless ring push
                                   ;;     (frameless events only)
                                   ;;   → request mirror sync
```

The framework's `emit!` retains every frame-bound trace event in its
per-frame ring at source. The listener fan-out also delivers a copy
to Xray's `collect-trace!` body so Xray can:

1. Drop self-noise (events emitted under `:rf/xray`).
2. Apply the privacy gate (drop `:sensitive?` events when the flag is
   off; bump the suppressed counter).
3. Capture frameless events the framework's per-frame rings skipped
   (per the B3 ruling — frameless emits stream to listeners only).
4. Schedule a microtask-coalesced snapshot of all rings into Xray's
   app-db `:trace-buffer` slot.

## Self-noise filter

Xray's panels render INSIDE the host app. Every host dispatch
dirties the host app-db, every `:rf.xray/*` sub re-fires, every Xray
panel re-renders, and every (re-)render emits `:rf.sub/run` +
`:rf.view/render` trace events. Without a guard those self-induced
events would (a) flow through Xray's listener into the substrate
Xray itself reads from, and (b) bucket as `:ungrouped :ungrounded`
(they fire outside a host dispatch) — drowning the host event the
user actually cared about.

### Framework-side: emission suppressed at source

`:rf/xray` is registered with `:rf.trace/frame-no-emit? true` (per
[Spec 009 §Frame-level trace-emission opt-out](../../../spec/009-Instrumentation.md#frame-level-trace-emission-opt-out-rftraceframe-no-emit-frame-config)).
The framework's `emit!` / `emit-error!` short-circuit for any event
whose frame is so marked, so the bulk of Xray's self-noise never
reaches the listener at all.

### Xray-side: ingest-time predicate fallback

Two pure-data predicates in
[`self_noise.cljc`](../src/day8/re_frame2_xray/self_noise.cljc)
cover residual classes the frame gate misses:

1. **`xray-internal-event?`** — any trace event whose `:frame`
   (top-level or `:tags :frame`) resolves to `:rf/xray`. Belt-and-
   braces against reactive sub-read / view-render emits that slipped
   past the frame gate.
2. **`xray-internal-cascade?` / `xray-internal-event-id?`** —
   cascades whose `:event` vector's head is a keyword in the
   `rf.xray` namespace (`:rf.xray/focus-cascade`,
   `:rf.xray/select-tab`, etc.). These can be dispatched WITHOUT a
   `{:frame :rf/xray}` option (palette quick-actions, headless
   helpers) — the framework chain-resolves them onto `:rf/default`,
   so the trace envelope carries `:frame :rf/default` and slips past
   the frame gate + `xray-internal-event?`. The data-layer filter at
   the `:rf.xray/cascades` sub closes that hole structurally without
   forcing every call site to thread `:frame`.

Both predicates are pure-data + JVM-runnable. The privacy gate sits
*below* the self-noise drop in `collect-trace!`, so an internal
event never bumps the host's `[● REDACTED N]` counter.

### Pre-alpha posture

Both predicates drop unconditionally — no "show internals" toggle.
If Xray needs to introspect its own machinery later, that's a
separate feature (a parallel Xray-internal buffer would be the right
shape), not an opt-out on the user-facing trace feed.

## Privacy gate

Per [Spec 009 §Privacy](../../../spec/009-Instrumentation.md#privacy--sensitive-data-in-traces)
(resolved by `rf2-a32kd`) and bead `rf2-azls9`: framework-published
trace-consuming integrations MUST default-suppress `:sensitive? true`
events. Xray is a framework-published consumer.

The listener body in
[`trace_collector.cljs`](../src/day8/re_frame2_xray/trace_collector.cljs)
gates every incoming event on the `:sensitive?` flag before any
secondary-ring push or mirror dispatch:

1. If `:sensitive?` is **absent or `false`**, the event is processed
   normally.
2. If `:sensitive?` is **`true`** AND the
   `:rf.privacy/show-sensitive?` flag is `false` (the default), the
   event MUST be **dropped** before any push. The collector MUST bump
   a per-frame suppressed-events counter (keyed by the event's
   `:tags :frame`, or `:global` when no frame scope is present) so
   the shell's bottom rail can surface a `[● REDACTED N]` indicator.
3. If `:sensitive?` is `true` AND the `:rf.privacy/show-sensitive?` flag is
   `true`, the event passes through unchanged.

The flag is read **at the head of the collector body** on every
event, so toggling it via `(xray-config/configure!
{:rf.privacy/show-sensitive? true})` takes effect on the next trace
event without re-registering the listener. The default is `false`.

### Read-side gate on the snapshot (rf2-0ax6f)

The listener-body gate above stops a sensitive event from entering
Xray's *secondary* (frameless) ring and from triggering a mirror
sync — but it CANNOT stop the framework's per-frame rings from
retaining it. The framework's `push-to-ring!` retains every emitted
event with no `:sensitive?` check (the ring is a faithful record of
what the runtime emitted); the per-frame gate is a Xray-read concern,
not a framework-ring concern. A sensitive **frame-bound** event is
therefore retained in its frame's ring even though the listener body
declined to push it, and a *later* non-sensitive event's mirror sync
reads the ring back — pulling the retained sensitive event into the
snapshot.

So `snapshot-from-rings` (§Snapshot below) applies the SAME
`suppress-sensitive?` gate on the read: while `:rf.privacy/show-
sensitive?` is `false`, every retained-but-sensitive event is
scrubbed from the snapshot regardless of frame-bound vs frameless
origin. The two gates are genuinely symmetric — the listener gate
keeps sensitive events out of the secondary ring + counter path; the
read gate keeps retained-in-per-frame-ring sensitive events out of
every downstream surface (`:trace-buffer`, L2, the trace panel, the
app-db diff, the cascade export, and the MCP/snapshot surface). The
read gate covers the steady-state read while the flag stays `false`;
the [retroactive scrub](#retroactive-scrub-on-toggle-off) covers the
true → false transition by clearing the rings wholesale.

### The suppressed-events counter

The counter is keyed `frame-id → count` with a `:global` bucket for
events without a frame scope (registration-time emits, outermost-
dispatch lookup failures). Consumers MAY read either the total
(across every bucket — what the bottom-rail indicator shows) or a
per-frame count. The counter is exposed under
`:rf.xray/suppressed-sensitive-count` (a layer-1 sub reading
`:suppressed-counters` off Xray's app-db).

`config/note-suppressed!` dispatches `:rf.xray/note-sensitive-
suppressed` into `:rf/xray` so the sub fires on the standard app-db
write path — the bottom-rail indicator updates IMMEDIATELY on every
bump, with no dependency on sibling subs recomputing. The plain
`config/suppressed-counters` atom remains as the JVM-runnable data
primitive for testing.

Counters MUST reset alongside the trace surface; see
[§Retroactive scrub](#retroactive-scrub-on-toggle-off) below.

The redaction indicator's UI shape is owned by
[`007-UX-IA.md`](./007-UX-IA.md) §Bottom rail; this doc owns the
counter contract.

## Frameless secondary ring

Per the B3 ruling (rf2-g1b2m): frameless trace emits (no
`:rf.trace/dispatch-id` in scope, no `:frame`) SKIP the framework's
per-frame rings — they stream to listeners only.

These are real events the user wants to see:
- Registry-time emits (handler-registered / handler-replaced fired
  during boot before any host dispatch).
- `:rf/init` and frame-lifecycle events outside a drain.
- REPL evaluations producing `re-frame.trace/emit!` calls outside a
  dispatch.
- `:rf.ssr/hydration-mismatch` events during SSR hydration.

The `:show-ungrouped?` settings toggle (per `rf2-r9lyy`, default OFF)
surfaces these as a `:ungrouped` pseudo-cascade in the L2 event list.
For that UX to work post-rf2-43koh — when the framework's per-frame
rings deliberately skip frameless emits — Xray maintains a small
**secondary ring at the listener boundary**.

### Capacity

Default capacity is **100 events** (per the rf2-3g9nw D2=a ruling).
Frameless emits are rare in healthy apps — most happen at boot or
during a REPL session — so a deeper ring buys little signal. The
secondary ring is a bounded FIFO ring ordered by emission order:
oldest entries at the head; new events append at the tail; the
oldest evicts on overflow.

### Snapshot

`trace-collector/refresh-trace-rings!` snapshots every registered
frame's flat trace events (via `(re-frame.trace.tooling/trace-buffer
fid {:flat true})`) and concatenates the frameless ring's contents,
sorted by `:id`. The merged vector lands in Xray's app-db
`:trace-buffer` slot via a `:rf.xray/sync-trace-buffer` dispatch.

`snapshot-from-rings` applies the `suppress-sensitive?` read-side
gate (see [§Read-side gate](#read-side-gate-on-the-snapshot-rf2-0ax6f))
to the merged vector, so retained-but-sensitive events never reach
`:trace-buffer` while `:rf.privacy/show-sensitive?` is `false`.

## Reactivity — microtask-coalesced mirror sync

The buffer's reactive surface is Xray's `:rf/xray` app-db
`:trace-buffer` slot. The `:rf.xray/trace-buffer` sub reads off the
slot; layer-1 sub re-fires on the standard app-db-write reactive
path so panels re-render on the next microtask after a refresh.

### Production path

`trace-collector/request-mirror-sync!` schedules a coalesced refresh
via `re-frame.interop/next-tick` (the microtask scheduler). Every
listener callback that lands in the current JS task requests a sync;
the queued microtask runs once, calls `refresh-trace-rings!`, and
the wholesale snapshot lands in `:trace-buffer`.

The coalescer caps the mirror cascade depth at **1 regardless of
trace volume**. The router's `drain-depth-default` (= 100) can never
gate the mirror under saturation — a synthetic load of 1000 trace
events landing in one JS task produces ONE mirror dispatch, not
1000.

### Test path (rf2-3g9nw D3=b)

Tests bypass the microtask coalescer by calling
`trace-collector/refresh-trace-rings!` directly. The parallel-frames
E2E (`rf2-wq6gx`) drives this entrypoint after each host
`dispatch-sync` so the app-db slot snaps deterministically against
the framework's rings without waiting on the microtask scheduler.

`refresh-trace-rings!` is a no-op pre-mount (`:rf/xray` not yet
registered). The framework's per-frame rings keep accumulating; the
snapshot lands on first mount via the
`mount.cljs/::seed-trace-and-target-frame` first-mount hook.

## Retroactive scrub on toggle-off

Per [Spec 009 §Privacy §Retroactive-scrub on `set-show-sensitive!`
false](../../../spec/009-Instrumentation.md#privacy--sensitive-data-in-traces)
(rf2-lqmje): toggling `:rf.privacy/show-sensitive?` from true →
false MUST clear the trace surface. The flag is NOT a one-way
trapdoor — a sensitive cascade emitted while the flag was true would
otherwise remain visible after the user expected privacy restored.

`trace-collector/retroactive-scrub!` drops all four places trace
data lives:

1. The framework's per-frame rings — via
   `re-frame.trace.tooling/clear-trace-rings!`.
2. Xray's frameless secondary ring — via `clear-frameless-ring!`.
3. Xray's app-db `:trace-buffer` slot — via `:rf.xray/clear-trace-
   buffer`.
4. The suppressed-counters — via `config/reset-suppressed-count!`.

The clear is wholesale: non-sensitive history that was buffered
alongside the sensitive cascade is also lost. This is the documented
trade-off — selective scrubbing is unsafe because a single sensitive
event can have caused later non-sensitive cascades (subs, renders,
fx) whose payloads structurally reveal the redacted value.

`config.cljc`'s `set-show-sensitive!` walks the registered
`toggle-off-callbacks` on every true → false transition;
`trace_collector.cljs` registers `retroactive-scrub!` into that
atom at load time (gated on `interop/debug-enabled?`). The same
scrub fn drives:

- The privacy toggle (true → false transition).
- The Settings popup's "Clear buffer now" button.
- The command-palette's `:palette/clear-trace-buffer` action.

## Consumer contract

Panels read the buffer through layer-1 subscriptions:

- **`:rf.xray/trace-buffer`** — flat vector of trace events,
  oldest-first by `:id`. Reads `(get db :trace-buffer)` off Xray's
  app-db; populated by the coalesced microtask sync.
- **`:rf.xray/cascades`** — chained off `:rf.xray/trace-buffer`,
  composes `re-frame.trace.projection/group-cascades` and applies
  the `self-noise/xray-internal-cascade?` data-layer filter (per
  `rf2-g1pt8`). Every downstream consumer reads from this projection;
  the L2 event list, the spine, the Event / Issues / Trace / Views
  tabs.

### What consumers MAY rely on

- A **point-in-time snapshot** of the merged per-frame + frameless
  surface as a Clojure vector, oldest-first by `:id`.
- **Total ordering by `:id`** within the snapshot.
- **Pure-data shape** — every event is a plain Clojure map matching
  the `:rf/trace-event` schema; no transient state, no atoms.
- **Idempotent reads** — calling the accessor twice in the same
  drain cycle returns the same vector (the framework emits within
  drains; see [Spec 009 §Delivery semantics](../../../spec/009-Instrumentation.md#listener-invocation-rules)).

### What consumers MUST NOT rely on

- **Eventually-receiving every emitted event.** Per-frame rings are
  cascade-keyed + B4-deduped with a configurable retention; the
  secondary frameless ring is bounded at 100 events. Both are lossy-
  on-overflow.
- **`:sensitive?` events being reversible from the surface.** Once
  dropped, the event is gone; flipping `:rf.privacy/show-sensitive?`
  from `false` to `true` only affects *future* events. Suppressed
  events are counted, not retained.
- **Buffer contents surviving a clear / retroactive scrub.**
  Tooling clears the surface via `trace-collector/retroactive-scrub!`;
  consumers MUST treat a cleared buffer as empty.

### Filter vocabulary

The framework's `(rf/trace-buffer fid opts)` exposes the full
13-axis filter vocabulary directly (per [Spec 009 §Filter
vocabulary](../../../spec/009-Instrumentation.md#filter-vocabulary)).
Xray's panels MAY pass `opts` through to the framework for
per-frame queries; they MAY also walk
`:rf.xray/trace-buffer` directly when they want the cross-frame
merged view.

Pre-rf2-43koh Xray exposed a parallel consumer-side
`filter-events` function. That surface had no production caller and
is retired alongside the rest of the separate ring — the framework's
own filter vocabulary is the single source of truth.

## Settings — buffer-depth UX (rf2-3g9nw D1=a)

The Settings popup → Buffer tab exposes the framework's per-frame
ring depth as a numeric input labelled
"Cascades retained (:buffer/cascades-retained)" (default 50).
Writes through to `(rf/configure! :trace-buffer
{:cascades-retained N})` once the Settings UX wires the runtime knob.

Renamed from `:trace-buffer/keep` (events, default 1000) at
rf2-43koh: the unit changed from events to cascades when Xray's
separate ring was retired in favour of the framework's per-frame
cascade-keyed rings. No back-compat alias — pre-alpha posture.

Two sibling knobs in the same section:

- `:retained-epochs` (default 200) — count of epochs to retain in
  the Xray epoch buffer (`tools/xray/src/day8/re_frame2_xray/
  epoch.cljs`).
- `:app-db/inspector-collapse-threshold` (default 50) — branch
  factor above which the App-DB inspector collapses by default.

The Buffer tab also carries a destructive **"Clear buffer now"**
button (with a confirm modal) that fires
`trace-collector/retroactive-scrub!` (`:rf.xray/settings-clear-
buffer`).

## JVM behaviour

The self-noise predicates in
[`self_noise.cljc`](../src/day8/re_frame2_xray/self_noise.cljc)
are pure-data + JVM-runnable. The CLJS-only side-effecting bits —
the listener body, the secondary ring atom, the microtask
coalescer, the mirror dispatch — live in
[`trace_collector.cljs`](../src/day8/re_frame2_xray/trace_collector.cljs).

JVM-hosted Xray surfaces (pair-tool dashboards rendering epoch
records server-side; see
[`012-Views.md`](./012-Views.md) §JVM behaviour for the parallel
pattern) read the framework's per-frame rings via `(rf/trace-buffer
fid opts)` and apply the consumer-side predicates from
`self_noise.cljc`. The CLJS-only secondary ring + microtask
coalescer + reactive surface do not exist server-side.

## Production elision

The collector body is gated on `re-frame.interop/debug-enabled?`
(alias of `goog.DEBUG`); production builds with `:advanced` +
`goog.DEBUG=false` drop the secondary ring atom, the listener
registration, and the per-event push entirely. The framework's
per-frame rings also elide in production via the same gate. Xray
contributes a sentinel to the elision verifier so
`npm run test:elision` ([Spec 009 §Production builds](../../../spec/009-Instrumentation.md#production-builds-zero-overhead-zero-code))
blocks any leak.

## Vision — trace fattening for context-at-position

**Bug class:** "I want to replay this instance's history from epoch 47
to epoch 53 — what was the `app-db` state at each step? what subs were
cached? what was the in-flight HTTP set?"

**v1 ships:** trace events carry an opaque `:rf.trace/dispatch-id` /
`:rf.trace/parent-dispatch-id` linkage plus `:tags` payloads. Replay
from arbitrary position requires re-applying the events forward from the
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

- [Spec 009 §Per-frame trace rings](../../../spec/009-Instrumentation.md#per-frame-trace-rings-cascade-keyed-dev-only)
  — the framework's per-frame ring substrate Xray reads from
  (rf2-g1b2m / rf2-8uwce).
- [Spec 009 §Listener registration](../../../spec/009-Instrumentation.md#user-side-listener-registration)
  — the upstream `register-listener!` API the collector consumes.
- [Spec 009 §Filter vocabulary](../../../spec/009-Instrumentation.md#filter-vocabulary)
  — the filter axes the framework's per-frame ring reader exposes.
- [Spec 009 §Privacy](../../../spec/009-Instrumentation.md#privacy--sensitive-data-in-traces) —
  the `:sensitive?` semantics the collector gates on.
- [Spec-Schemas §`:rf/trace-event`](../../../spec/Spec-Schemas.md#rftrace-event)
  — the event shape consumers project from.
- [`Principles.md`](./Principles.md) §Observation only — no new
  runtime surfaces — the discipline that keeps Xray downstream.
- [`007-UX-IA.md`](./007-UX-IA.md) §Bottom rail — the
  `[● REDACTED N]` indicator the suppressed-counter feeds.
- [`API.md`](./API.md) §Trace / epoch surfaces — the consumer-facing
  surface enumeration.
- [Conventions §Reserved keyword namespaces](../../../spec/Conventions.md)
  — the `:rf.xray/` namespace the collector key sits under.
