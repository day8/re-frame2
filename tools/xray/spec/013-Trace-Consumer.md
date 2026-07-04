# 013-Trace-Consumer

Xray reads from the **framework's per-frame trace rings** + a small
**Xray-side secondary ring** for frameless emits. This document
defines normatively what Xray's consumer-side surface adds on top of
the framework's substrate — the self-noise filter, the privacy gate,
the suppressed-events counter, the frameless secondary ring, the
microtask-coalesced mirror sync, and the retroactive-scrub-on-toggle-
off behaviour.

The framework owns the data plane: per-frame event-keyed rings with
B4 dedup, depth knob via `(rf/configure! {:trace-buffer
{:events-retained N}})`, oldest-first event bundles via
`(rf/trace-buffer frame-id opts)`. Per [Spec 009 §Per-frame trace
rings](../../../spec/009-Instrumentation.md#per-frame-trace-rings-event-keyed-dev-only)
and bead `rf2-g1b2m`.

This doc is the Xray-specific consumer contract on top.

## Background — what changed at rf2-43koh

Pre-rf2-43koh Xray ran its own 1000-event process-global ring (`tools/
xray/src/day8/re_frame2_xray/trace_bus.cljc`) parallel to the
framework's. The "two rings" architecture predated the per-frame
event-keyed rings (`rf2-g1b2m` / `rf2-8uwce`) and the B4 dedup
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
  `:trace-buffer/keep` → `:events-retained` (one slot per event;
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
2. **`xray-internal-event-bundle?` / `xray-internal-event-id?`** —
   cascades whose `:event` vector's head is a keyword in the
   `rf.xray` namespace (`:rf.xray/focus-event`,
   `:rf.xray/select-tab`, etc.). These can be dispatched WITHOUT a
   `{:frame :rf/xray}` option (palette quick-actions, headless
   helpers) — the framework chain-resolves them onto `:rf/default`,
   so the trace envelope carries `:frame :rf/default` and slips past
   the frame gate + `xray-internal-event?`. The data-layer filter at
   the `:rf.xray/event-bundles` sub closes that hole structurally without
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
2. If `:sensitive?` is **`true`** AND the local-render egress profile
   redacts (the `:rf.egress/local-redacted` default), the
   event MUST be **dropped** before any push. The collector MUST bump
   a per-frame suppressed-events counter (keyed by the event's
   `:tags :frame`, or `:global` when no frame scope is present) so
   the shell's bottom rail can surface a `[● REDACTED N]` indicator.
3. If `:sensitive?` is `true` AND the egress profile reveals
   (`:rf.egress/local-raw`), the event passes through unchanged.

The profile is read **at the head of the collector body** on every
event, so widening it via `(xray-config/configure!
{:rf.xray/egress-profile :rf.egress/local-raw})` takes effect on the
next trace event without re-registering the listener. The default is
`:rf.egress/local-redacted`.

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
`suppress-sensitive?` gate on the read: while the egress profile
redacts (the `:rf.egress/local-redacted` default), every
retained-but-sensitive event is
scrubbed from the snapshot regardless of frame-bound vs frameless
origin. The two gates are genuinely symmetric — the listener gate
keeps sensitive events out of the secondary ring + counter path; the
read gate keeps retained-in-per-frame-ring sensitive events out of
every downstream surface (`:trace-buffer`, L2, the trace panel, the
app-db diff, the cascade export, and the MCP/snapshot surface). The
read gate covers the steady-state read while the profile redacts;
the [retroactive scrub](#retroactive-scrub-on-profile-narrowing) covers
the reveal → redact narrowing by clearing the rings wholesale.

The **runtime/MCP accessors** (`day8.re-frame2-xray.runtime/get-trace-buffer`
and `get-issues`, [API.md §Inspection band](API.md)) read the per-frame
rings DIRECTLY — they bypass `snapshot-from-rings` and the panel's
`:trace-buffer` app-db slot entirely. They therefore apply the SAME
event-level default-suppress as their OWN gate (`drop-sensitive-events`),
dropping whole `:sensitive? true` events before value-scrubbing. The
opt-back-in differs from the panel surface: the panel reads its
local-render egress profile (`:rf.xray/egress-profile`), while the seam
is per-call so the opt is the per-call `{:include-sensitive? true}`
accessor option. The
envelope — existence, `:op-type`, timing, source, handler/event ids, and
non-elided `:tags` — is what the seam gate protects; value-scrubbing
(`egress-value`) alone leaves the envelope intact, which is why the
event-level gate is load-bearing (rf2-to36uj).

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
[§Retroactive scrub](#retroactive-scrub-on-profile-narrowing) below.

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
`:trace-buffer` while the egress profile redacts (the
`:rf.egress/local-redacted` default).

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

## Retroactive scrub on profile narrowing

Per [Spec 009 §Privacy §Retroactive-scrub on the egress-profile
narrowing](../../../spec/009-Instrumentation.md#privacy--sensitive-data-in-traces)
(rf2-lqmje): narrowing `:rf.xray/egress-profile` from reveal
(`:rf.egress/local-raw`) back to the redacting default
(`:rf.egress/local-redacted`) MUST clear the trace surface. Reveal is
NOT a one-way trapdoor — a sensitive cascade emitted while the profile
revealed would otherwise remain visible after the user expected privacy
restored.

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

`config.cljc`'s `set-egress-profile!` walks the registered
`toggle-off-callbacks` on every reveal → redact narrowing;
`trace_collector.cljs` registers `retroactive-scrub!` into that
atom at load time (gated on `interop/debug-enabled?`). The same
scrub fn drives:

- The egress-profile narrowing (reveal → redact).
- The Settings popup's "Clear buffer now" button.
- The command-palette's `:palette/clear-trace-buffer` action.

## Consumer contract

Panels read the buffer through layer-1 subscriptions:

- **`:rf.xray/trace-buffer`** — flat vector of trace events,
  oldest-first by `:id`. Reads `(get db :trace-buffer)` off Xray's
  app-db; populated by the coalesced microtask sync.
- **`:rf.xray/event-bundles`** — chained off `:rf.xray/trace-buffer`,
  composes `re-frame.trace.projection/group-by-event` and applies
  the `self-noise/xray-internal-event-bundle?` data-layer filter (per
  `rf2-g1pt8`). Every downstream consumer reads from this projection;
  the L2 event list, the spine, the Event / Issues / Trace / Views
  tabs.

## One work/reply vocabulary — reading the uniform reply envelope

Every managed *async* family (HTTP, resources, mutations, route
loaders, machine async work, future managed timers) completes through
the ONE shape defined in [`spec/Managed-Effects.md` §The uniform reply
envelope](../../../spec/Managed-Effects.md) (EP-0011): a single **reply
map** carrying one closed `:status`, value/error data, `:work/id` work
correlation, `:work/kind`, `:work/status`, `:attempt`, `:rf.frame/id`,
the durable timestamps, and the cancellation/staleness facts. Property 9
/ §Tracing require each family to emit its trace rows **from those
reply-envelope facts, not from private callback facts**.

Xray reads that uniform shape — it does **not** learn each family's
private callback vocabulary. The consumer-side mirror lives in
[`panels/reply_envelope.cljc`](../src/day8/re_frame2_xray/panels/reply_envelope.cljc)
(`day8.re-frame2-xray.panels.reply-envelope`, pure data, JVM-runnable),
and gives one vocabulary across HTTP / resources / mutations / routing /
machines / timers:

- **The closed vocabularies** — `reply-statuses`
  (`:ok` / `:partial` / `:error` / `:cancelled` / `:stale`),
  `work-statuses`, and `work-kinds` — are **mirrored** from
  `re-frame.reply` as literal data (Xray never `:require`s the
  substrate; the no-tool-imports-core bundle direction holds, and Xray
  consumes the *wire facts*). The closed-vocabulary wiring test pins the
  mirror so a substrate change that drifts the set fails loud.
- **`reply-row`** projects a uniform reply map (the appended last-arg of
  a `:rf/reply-to` event, or a `:rf.http/replied` / `:rf.reply/*` trace
  summary) into ONE render-safe row keyed on `:work/id`, reading
  issuance/completion **status**, **stale-suppression** (carried+current
  correlation), **cancellation**, and **delivery-or-non-delivery**
  (`:delivered?` is `false` for a suppressed `:stale` reply unless a
  framework test/tool target opted into `:dispatch-stale?`). The
  wire-bearing slots (`:value` / `:error` / `:correlation` / `:meta`)
  are summarized — the runtime already elided sensitive/large slots on
  the wire (Managed-Effects §Tracing).
- **`phase-of`** lowers every family's emitted trace op onto ONE
  reply-envelope **phase** — `:issued` / `:retry` / `:cancel-requested`
  / `:completed` / `:stale-suppressed` / `:delivered` — so a panel
  groups by phase, not by family. An explicit op table covers the landed
  literals (`:rf.resource/work-started`, `:rf.http/replied`,
  `:rf.http/retry-attempt`, `:rf.resource/stale-suppressed`,
  `:rf.reply/suppressed`, the routing `:rf.route.nav-token/stale-suppressed`,
  the machine `:rf.machine.timer/stale-after`, and the HTTP-supersession
  `:rf.http/stale-suppressed` — rf2-waawic / rf2-azcmd3; the suffix heuristic
  catches `stale-suppress` / `suppressed` but NOT `stale-after`, so the
  machine-timer op is enumerated explicitly); a name-suffix heuristic
  classifies a not-yet-enumerated family op (e.g. a future `:rf.stream/*`
  surface) before the table learns its literal.
- **Production `:rf.reply/*` trace vocabulary (rf2-waawic).** Family
  completion / stale rows stamp the canonical reply facts ADDITIVELY as
  `:rf.reply/status` / `:rf.reply/work-id` / `:rf.reply/work-status` /
  `:rf.reply/carried` / `:rf.reply/current` alongside their bespoke
  family facts (machine `:rf.machine/done`, resource / mutation
  stale-suppression). The trace readers normalize BOTH the canonical bare
  keys (`:work/id` / `:status` / `:work/status`) AND these additive
  `:rf.reply/*` keys, preferring the canonical key and falling back to the
  `:rf.reply/*` spelling, so a row carrying only the additive vocabulary is
  still joined into the uniform work/reply rows by `:work/id` rather than
  losing its status / work-id / grouping.
- **Frame attribution — two family spellings on a reply trace row
  (rf2-l9vb09).** Two LEGITIMATE frame spellings ride a managed-async
  reply TRACE ROW, by family:
    - **resources / machines / mutations** stamp the canonical EP-0002
      **carried-frame stamp `:rf.frame/id`** in `:tags` (the
      reply-envelope facts the family emits its row FROM — Managed-Effects
      §The reply map / §Tracing; e.g. `re-frame.resources.events`);
    - **HTTP** stamps the **bare `:frame`** in `:tags` — the generic
      raw-event carve-out read by the contract-owned canonical reader
      `re-frame.trace/trace-event-frame` (`[:tags :frame]`, rf2-7737vq;
      `re-frame.http.transport`'s `:rf.http/stale-suppressed`).

  `work-event-row` therefore reads **`(or (:rf.frame/id tags)
  (trace/trace-event-frame ev))`** — `:rf.frame/id` preferred, falling
  back to the canonical raw-event reader for the bare `[:tags :frame]`. It
  uses the canonical reader where it applies (HTTP) rather than
  hand-reaching into `[:tags :frame]`. The historical defensive over-reads
  (bare `:frame-id` in `:tags` — rf2-shaa1 dropped it, no emit site
  produces it; a top-level `:frame` on the raw event — raw events carry
  frame ONLY under `:tags`) are dead and not consulted.

  The **reply MAP** layer is different: the dispatched reply map is
  UNIFORM on `:rf.frame/id` across every family ("there is no second
  frame spelling" — HTTP's reply BUILDER maps its internal `:frame` ctx
  onto `:rf.frame/id` on the map), so `reply-row` reads `:rf.frame/id`
  alone.
- **`status->class`** gives the one cross-surface colour class so a
  `:stale` HTTP reply and a `:stale` resource reply render the **same
  badge** (Cross-Cutting [F.11](019-Cross-Cutting-Insight.md) — the
  unified `STALE` rendering). A `:stale-suppressed` row preserves the
  canonical EP-0011 `:status :stale` + `:status-class :suppression` read off
  the **unambiguous `:rf.reply/status`** (NEVER the bare `:status`, which on
  a suppression row may carry the LEDGER status `:completed`/`:failed`/
  `:suppressed`) — so a suppression row renders the SAME status/badge
  contract a `:completed` row does, not just the `:stale?` phase flag.

### "What is still running?" and the stale-races view

Both questions are answered from the **work ledger joined to reply
status + trace cause, uniformly across families** — never per-family:

- **`live-work`** reads the live (non-terminal) work-ledger rows
  (`:rf.runtime/work-ledger`, the durable substrate — Managed-Effects
  §Work-ledger integration) and joins each to the **latest
  reply-envelope trace phase** for its `:work/id`, so the operator sees
  "the app is waiting on resource K (issued), HTTP req-5
  (cancel-requested), …" with one query. The join keys on the canonical
  `:work/id` **vector** carried on the ledger RECORD (`ledger-row` reads
  `(:work/id record)`, falling back to the map key only for legacy /
  nonconforming records) — the production `:rf.runtime/work-ledger` map is
  keyed on the opaque CEDN-1 byte `work-id-id` STRING, so reading the record
  field (not the map key) is what lets a live row join to the vector-keyed
  trace rows and infer `:work-kind` from the kind-preserving head.
  `live-work-tally-by-kind` counts live work per family — the active
  managed-effects dashboard headline (Cross-Cutting F-C4).
- **The stale-races view keys on `:work/id`** (Managed-Effects
  §Work-id correlation — `:work/id` is the *single* attempt identity,
  the key the ledger, stale suppression, and this view all share).
  `races-by-work-id` groups every cross-family work/reply row by work id
  into an attempt arc (`:phases`, `:terminal-status`, `:suppressed?`);
  `stale-suppressions` + `stale-tally-by-kind` surface the
  cross-surface suppression tally (Cross-Cutting F-C5).

The resources composite (`:rf.xray/resources-tab-data`) carries the
uniform reads (`:live-work` / `:stale-races` / `:stale-tally`) alongside
the resource-specific surfaces. The resource family is the only ledger
writer today; as HTTP / route / machine / timer families write their own
ledger rows and emit their reply-envelope trace ops, these surfaces pick
them up with **no panel change** — one vocabulary, many families.

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
  event-keyed + B4-deduped with a configurable retention; the
  secondary frameless ring is bounded at 100 events. Both are lossy-
  on-overflow.
- **`:sensitive?` events being reversible from the surface.** Once
  dropped, the event is gone; widening `:rf.xray/egress-profile`
  from `:rf.egress/local-redacted` to `:rf.egress/local-raw` only
  affects *future* events. Suppressed events are counted, not
  retained.
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
"Events retained (:buffer/events-retained)" (default 50).
Writes through to `(rf/configure! {:trace-buffer
{:events-retained N}})` via `settings/effects.cljs
§apply-events-retained!` — the matching `:rf.xray/settings-update
:buffer :events-retained` event applies it live, and `apply-all!`
replays the persisted value on boot (rf2-5u03ig).

Renamed from `:trace-buffer/keep` (default 1000) at
rf2-43koh when Xray's separate ring was retired in favour of the
framework's per-frame event-keyed rings — one retained slot per
event / pipeline run. No back-compat alias — pre-alpha posture.

`:events-retained` is the sole Buffer-tab knob. The earlier
`:buffer/retained-epochs` input was removed (rf2-pu9sb — no runtime
consumer; the per-frame epoch ring is sized by `:general
:epoch-history`), and the inert
`:buffer/app-db/inspector-collapse-threshold` input was removed
(rf2-5u03ig — no runtime consumer; the App-db inspector already
auto-collapses on depth/width).

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

- [Spec 009 §Per-frame trace rings](../../../spec/009-Instrumentation.md#per-frame-trace-rings-event-keyed-dev-only)
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
- [Spec 009 §Frame identity on the raw event](../../../spec/009-Instrumentation.md)
  — a raw trace event carries frame identity ONLY at `[:tags :frame]`;
  Xray's collector + trace-panel helpers read it via the contract-owned
  canonical reader `re-frame.trace/trace-event-frame` rather than
  hand-reaching into `[:tags :frame]` (rf2-7737vq).
- [`Principles.md`](./Principles.md) §Observation only — no new
  runtime surfaces — the discipline that keeps Xray downstream.
- [`007-UX-IA.md`](./007-UX-IA.md) §Bottom rail — the
  `[● REDACTED N]` indicator the suppressed-counter feeds.
- [`API.md`](./API.md) §Trace / epoch surfaces — the consumer-facing
  surface enumeration.
- [Conventions §Reserved keyword namespaces](../../../spec/Conventions.md)
  — the `:rf.xray/` namespace the collector key sits under.
