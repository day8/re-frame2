# Tool-Pair — runtime contract for pair-shaped AI tools

> **Type:** Reference
> The runtime surface re-frame2 commits to so that pair-shaped tools — equivalents of [day8/re-frame-pair](https://github.com/day8/re-frame-pair) — can attach to a running re-frame2 application and let an AI agent inspect, dispatch, hot-swap, and time-travel against it.

## What this Spec is and isn't

**This Spec is** the *runtime contract* — the set of public capabilities re-frame2 exposes that pair-shaped tools rely on. It tells an implementer "ship these capabilities and a pair tool can be built against you."

> **Audit lineage.** Several surfaces below (`register-epoch-listener!`, the structured `:sub-runs` / `:renders` / `:effects` slots on `:rf/epoch-record`, `:dispatch-id` / `:parent-dispatch-id` correlation, the `:origin` dispatch opt, `app-schemas` introspection, and the §Source-mapping helper enumeration) were added to this Spec following a cross-reference audit against [day8/re-frame-pair](https://github.com/day8/re-frame-pair)'s actual source — the upstream tool consumed surfaces this contract had not yet committed to. The audit is single-sourced here; downstream Specs (009 / 010 / 002 / Spec-Schemas) carry the additive normative text without re-citing the audit.

> **Source-of-truth note:** Tool-Pair.md is the **canonical surface contract** for the time-travel / epoch-history capabilities and the trace-stream consumption shape. [API.md](API.md) reproduces these signatures (under §Epoch history) for fast lookup, but the *normative* descriptions live here; if the two drift, this Spec wins. The `epoch-history`, `restore-epoch!`, and `(rf/configure! {:epoch-history {:depth N}})` surface, plus the `:rf.epoch/snapshotted` / `:rf.epoch/restored` / `:rf.registry/handler-replaced` trace events, are pinned here and referenced from API.md.

**This Spec is not** the pair tool itself. The actual pair tool — the Claude integration, the prompt design, the nREPL middleware — lives outside the spec, in a separate repository (the upstream is [day8/re-frame-pair](https://github.com/day8/re-frame-pair)). re-frame2 ships *its half* of the contract; the tool ships its half.

The architecture mirrors how re-frame2 relates to [re-frame-10x](https://github.com/day8/re-frame-10x): the spec defines a stable contract (the trace stream, the registrar query API, the public envelope shape); the tool consumes it. Multiple tools can consume the same contract.

## What pair-shaped tools do

(Summarising the capability surface of [day8/re-frame-pair](https://github.com/day8/re-frame-pair) so the contract makes sense.)

A pair tool is an AI/REPL companion that attaches to a running re-frame2 app. It lets the agent:

- **Inspect** — read the current state of any frame's `app-db`, any subscription, any registration.
- **Trace** — observe the trace stream (live or historical), per-event domino-by-domino.
- **Dispatch** — fire events into any frame, synchronously or async.
- **Hot-swap** — replace a registered handler with new code, observe the effect.
- **Time-travel** — walk backward through the epoch history of a frame, snapshot state at each point, restore an earlier state.
- **Stub effects** — temporarily redirect a registered fx (e.g., `:http`) to a stub, run experiments, restore.
- **Map source** — given a registration id or a UI event, locate the source coordinates.
- **REPL-eval** — execute arbitrary expressions in the runtime's namespace context.
- **Watch / narrate** — set up subscriptions to a stream of trace events and report each one as it fires.

The "9-step empirical loop" (observe → inspect → hypothesise → probe → compare → edit) is the dominant interaction shape; pair-shaped tools are designed to make that loop fast.

## What re-frame2 commits to (the runtime contract)

Each capability the pair tool needs maps to a re-frame2 surface. The contract has two parts:

- **Existing-surface map (this section).** Every capability except time-travel is already specified in other Specs (001 / 002 / 008 / 009). The table below maps capability → surface → source-of-truth. No new commitments here.
- **Time-travel commitments (§Time-travel below).** Epoch recording, query, and restore are new in re-frame2 and locked here. This is the only section adding surface; everything else is reproduction.

The two parts together form the **consolidated contract** — the complete set of surfaces a pair-shaped tool may consume. [§How AI tools attach](#how-ai-tools-attach) reproduces the same surfaces re-organised by *what the tool needs to do* (rather than *what re-frame2 commits to*); it is a view, not additional commitments.

| Capability | re-frame2 surface | Spec |
|---|---|---|
| **Read `app-db`** | `(rf/app-db-value frame-id)` returns the current `app-db` value (a plain map) | [002 §The public registrar query API](002-Frames.md#the-public-registrar-query-api) |
| **Read sub values** | `(rf/compute-sub query-v db-value)` runs a sub against an `app-db` value | [008](008-Testing.md) |
| **Read registry** | `(rf/registrations kind)`, `(rf/handler-meta kind id)`, `(rf/frame-ids)`, `(rf/frame-meta id)` | [001-Registration](001-Registration.md), [002](002-Frames.md) |
| **Dispatch** | `(rf/dispatch ev opts)`, `(rf/dispatch-sync ev opts)` with `:frame` opt | [002 §Routing](002-Frames.md#routing-the-dispatch-envelope) |
| **Drive the render** | `flush-render!` on the installed substrate adapter — synchronously commits pending renders so a headless `dispatch → observe-DOM` loop is deterministic | [006 §`flush-render!`](006-ReactiveSubstrate.md#flush-render-f--nil), [§Driving the render](#driving-the-render-headless-view-lifecycle) |
| **Trace stream** | `(rf/register-listener! :trace key callback)` plus structured trace events | [009](009-Instrumentation.md) |
| **Hot-swap handlers** | Re-registration replaces; emits `:rf.registry/handler-replaced` trace | [001 §Hot-reload semantics](001-Registration.md#hot-reload-semantics) |
| **Stub fx** | `:fx-overrides` map (id-valued at the pattern level) on `dispatch` opts or `reg-frame` metadata | [002 §Per-frame and per-call overrides](002-Frames.md#per-frame-and-per-call-overrides) |
| **Source coordinates** | `:ns`/`:line`/`:column`/`:file` on every registration's metadata (shape: `:rf/source-coord-meta` per [Spec-Schemas](Spec-Schemas.md#rfsource-coord-meta)); mandatory `data-rf2-source-coord` DOM annotation (shape: `:rf/source-coord-attr` per [Spec-Schemas](Spec-Schemas.md#rfsource-coord-attr)) per Spec 006 | [001 §Source-coordinate capture](001-Registration.md#source-coordinate-capture-cljs-reference), [006 §Source-coord annotation](006-ReactiveSubstrate.md#source-coord-annotation-mandatory) |
| **Inspect registered schemas** | `(rf/app-schemas frame-id)`, `(rf/app-schema-at path opts)`, `(rf/app-schemas-digest opts)` | [010 §Schemas as a tooling and agent surface](010-Schemas.md#schemas-as-a-tooling-and-agent-surface) |
| **Errors** | Structured `:rf.error/*` trace events with category + tags | [009 §Error contract](009-Instrumentation.md#error-contract) |

This much is **already specified** (except the render-driving fn `flush-render!`, locked in [§Driving the render](#driving-the-render-headless-view-lifecycle) below and in [006 §`flush-render!`](006-ReactiveSubstrate.md#flush-render-f--nil)). A pair tool built against re-frame2 (and conforming with [day8/re-frame-pair](https://github.com/day8/re-frame-pair)) needs nothing more than these surfaces to do everything in the capability list above except time-travel.

The capabilities requiring *new* commitments are **driving the render** (immediately below) and **time-travel** (after it).

### Driving the render (headless view-lifecycle)

A pair tool can drive the re-frame *layer* (`dispatch`) but, without a commitment here, cannot reliably drive the *substrate render*. The reference substrates schedule re-renders through a `requestAnimationFrame`-style tick that (a) fires *after* an evaluated `dispatch` returns and (b) is throttled to ~never in a **backgrounded / unfocused tab** — exactly the state a headless or remotely-driven browser is usually in. So a tool that dispatches an event and then reads the DOM races the scheduler: the commit may not have happened, and in a backgrounded tab may never happen. Driving the view lifecycle (mount / unmount / re-render) from the MCP was correspondingly unreliable.

The commitment is the optional substrate-adapter contract fn **`flush-render!`** (per [006 §`flush-render!`](006-ReactiveSubstrate.md#flush-render-f--nil)). It runs through the host's **synchronous-commit** path (React `flushSync`; `reagent.core/flush` for the ratom family) rather than the rAF-scheduled tick, so the commit fires even headless and even when the tab is backgrounded. The 1-arity form runs a thunk and then flushes; the 0-arity form flushes already-pending work. It is no-op-safe (nothing pending → returns `nil`).

This is the framework primitive the pair MCP's **dispatch-and-settle** op builds on: dispatch the event, `flush-render!` to synchronously commit the resulting renders, then observe the settled DOM (the `data-rf2-source-coord` / `data-rf-view` annotations per [006 §Source-coord annotation](006-ReactiveSubstrate.md#source-coord-annotation-mandatory)) and the settled epoch (per [§Time-travel](#time-travel)). `flush-render!` belongs to the **framework**, not the pair-runtime: it is substrate-specific, but every re-frame2 app installs an adapter, so the capability is universal. A tool reaches it through the installed adapter's `flush-render!` (the `eval-cljs` authority class per [§MCP tool authority classes](#mcp-tool-authority-classes--named-mutation-vs-eval-cljs), or a named dispatch-and-settle op that wraps it). Adapters with no live commit (plain-atom / SSR) ship no `flush-render!`; the call no-ops.

#### The `dispatch-and-settle` contract

A conforming pair MCP exposes dispatch-and-settle as **one operation that returns the complete epoch in one call** — the `dispatch` tool's **`:settle`** mode. The op MUST:

1. dispatch the event **synchronously** (the pipeline run commits app-db before the render can settle against the new state);
2. call the **installed adapter's `flush-render!`** — resolved from the live adapter, never a hardcoded substrate API (the framework knows its own registered adapter; a tool that names `reagent.core/flush!` is non-conforming and breaks under UIx / Helix);
3. **re-read** the epoch the dispatch recorded and return it.

Because `flush-render!` is synchronous, the post-settle render emits (`:rf.view/render`) fire inside its extent and the framework back-fills them into the causing epoch (per [009 §post-settle render attribution](009-Instrumentation.md) — a render fires at substrate-commit time, *after* the pipeline run settles, and is re-attributed to the most-recently-settled epoch and re-fanned to epoch listeners). The re-read therefore returns an epoch whose `:renders` projection and `:trace-events` carry the view-lifecycle signal. The returned envelope carries `:settled? true`, the assembled `:epoch`, and the view-render / view-unmount entries (`:rf.view/render`, `:rf.view/unmounted`) folded into that epoch — so **one call = dispatch → render → complete epoch**, retiring the `dispatch` + `setTimeout` + `flush!` + re-read dance and its rAF / tab-focus dependence.

**`:settle` versus `:await-render`.** Both make `dispatch → observe` one step; they differ in flush primitive and transport. `:await-render` flushes via `re-frame.interop/after-render` (the rAF-scheduled, post-commit / pre-paint hook) and is **async** — it returns a promise the server awaits, and on a backgrounded tab the underlying commit can stall; it resolves to the dispatch **consequence** (no full epoch). `:settle` flushes via `flush-render!` (synchronous-commit) — **not** rAF-scheduled, so it commits even headless / backgrounded, is fully synchronous (no promise), and returns the **settled epoch**. When both are set, `:settle` wins (it is the most complete single-call shape).

**Scope.** "Settle" is the **synchronous** pipeline run + render flush only. Asynchronous effects (HTTP, timers, `:dispatch-later`) are **not** settled by this op — they stay observed via `watch-epochs` (per [§`watch-epochs` / `trace-window` consumer shape](#watch-epochs--trace-window-consumer-shape-mcp-layer) / [§Time-travel](#time-travel)). Substrate teardown emits (`:rf.view/unmounted`) ride the substrate's instance-disposal, which some substrates schedule as a passive teardown *after* the synchronous commit; when that teardown fires, the framework's post-settle back-fill attributes it to the epoch this op returned (the most-recently-settled one). The op's guarantee is therefore: the synchronous renders are present in the returned epoch, and any deferred unmount back-fills into *that same epoch* and re-fans to listeners.

<a name="time-travel"></a>
## Time-travel: epoch snapshots and undo

> **Artefact home.** The time-travel surface — the per-frame `:rf/epoch-record` ring buffer (`epoch-history`), the `(rf/configure! {:epoch-history {:depth N}})` knob, the `register-epoch-listener!` / `unregister-epoch-listener!` listener API, the `restore-epoch!` rewind with its seven documented failure modes, the per-cascade trace-capture buffer, the `:rf.epoch/snapshotted` / `:rf.epoch/restored` trace events, and the `:sub-runs` / `:renders` / `:effects` projections — ships in `day8/re-frame2-epoch`. Apps that consume the pair-tool / time-travel surface add the artefact alongside core and require `re-frame.epoch` at boot so the namespace's late-bind hook publications fire before the public re-exports in `re-frame.core` (`rf/epoch-history`, `rf/restore-epoch!`, `(rf/configure! {:epoch-history ...})`, and the `:epoch` stream of `rf/register-listener!`) reach into the hook table at call time. When the artefact is not on the classpath, absent-artefact behaviour splits by surface. The **read / listener / dev-observation** re-exports degrade silently with sentinels — `rf/epoch-history` (and `projected-history`) returns the empty vector, `rf/restore-epoch!` returns `false`, and the `:epoch` stream of `rf/register-listener!` / `rf/unregister-listener!` (and `projected-record`) no-op (`nil`) — so a release build that omits the artefact does not raise on those paths. The lone exception is the **write** surface — the partial-map injection mutator `rf/replace-frame-state!`: it raises `:rf.error/epoch-artefact-missing` rather than returning a sentinel, because a silent no-op would lie about the caller's undo invariant ("undo works after this call") — see the [§Pair-tool writes](#pair-tool-writes--state-injection) contract below, [009 §Trace events](009-Instrumentation.md) (`:rf.error/epoch-artefact-missing`), and [API §Conventions](API.md#conventions) (the per-artefact public-namespace table: absent artefacts surface `:rf.error/<feature>-artefact-missing`). The whole surface is dev-tier and gated on `interop/debug-enabled?`. See [Conventions §Packaging conventions](Conventions.md#packaging-conventions) and [MIGRATION §M-33](../migration/from-re-frame-v1/README.md#m-33-epoch--time-travel-tool-pair-time-travel-ships-in-a-separate-artefact--day8re-frame2-epoch).

The runtime contract for time-travel:

**Recording.** Each **dequeued event** marks an epoch boundary — one `:rf/epoch-record` per dequeued event, not per drain (per [002 §Drain versus event](002-Frames.md#drain-versus-event--the-epoch-unit)). The unit is `process-event!`: every dequeued envelope runs its own full pipeline run and yields its own epoch, irrespective of how it arrived — a UI `(rf/dispatch …)`, an `:fx [[:dispatch …]]` child queued by another handler, or a frame-creation `:initial-events` setup step each open a fresh epoch. A drain that processes a parent event and the child it `:fx`-dispatched therefore produces **two** epoch records, not one, even though both settle inside the same drain. (A machine's `:raise` sub-events and `:always` microsteps are intra-macrostep — they ride the triggering event's epoch and do **not** open a new one, per [005 §Drain semantics](005-StateMachines.md#drain-semantics).) The runtime records, per frame, an `:rf/epoch-record` (per [Spec-Schemas](Spec-Schemas.md#rfepoch-record)) consisting of `:epoch-id`, `:frame`, `:committed-at`, `:event-id`, `:trigger-event`, `:db-before`, `:db-after`, and (optionally) `:trace-events`, plus the structured per-epoch projections `:sub-runs`, `:renders`, and `:effects` (each pre-derived from `:trace-events`; see [Spec-Schemas §`:rf/epoch-record`](Spec-Schemas.md#rfepoch-record) for shapes). Pair tools route diagnostics off the structured slots — cache-hit-vs-rerun analysis (`:sub-runs[*].:recomputed?`), render-key attribution (`:renders[*].:render-key`, the tuple `[<view-id> <instance-token>]` per [004 §Render-tree primitives](004-Views.md#render-tree-primitives) — Option C /), and fx cascade outcome (`:effects[*].:outcome`) — without re-folding the raw trace stream each epoch.

**Identity spellings.** The epoch-record fields and dispatch-consequence / cursor return shapes use the **bare record/projection vocabulary** — `:epoch-id`, `:dispatch-id`, `:event-id`, `:frame` — while the trace `:tags` the runtime *reads from* to assemble them use the **qualified `:rf.*` form** — `:rf.epoch/id`, `:rf.trace/dispatch-id`, `:rf.trace/event-id` (with `:frame` the single bare carve-out tag). These are two deliberate layers with one canonical spelling each, so a consumer needs no context-specific alias within a layer: a tool reading a `:rf/epoch-record` (or a `dispatch` consequence) always uses the bare slot; a tool reading raw trace tags always uses the qualified tag. The record-layer `:epoch-id` / `:dispatch-id` are the **same correlation ids** as `:rf.epoch/id` / `:rf.trace/dispatch-id`; `:event-id` is a non-identity payload field (the head keyword of `:trigger-event`). The full normative statement lives at [Spec-Schemas §`:rf/epoch-record`](Spec-Schemas.md#rfepoch-record).

**Frame identity across the two layers — read it through the canonical accessor.** The two-layer split applies directly to *frame* identity, which is the one field present in both shapes. A **raw trace event** carries `:frame` **only** at `[:tags :frame]` — there is no public top-level `:frame` on a raw event (the bare carve-out tag, per [009 §Frame identity on the raw event](009-Instrumentation.md#frame-identity-on-the-raw-event-tags-frame-read-via-the-canonical-accessor)). A **projected record** — an event bundle (`(rf/trace-buffer frame-id)`), an `:rf/epoch-record`, a `dispatch` consequence, a cursor / summary record — carries `:frame` at **top-level**. A consumer reading a projected record reads its top-level `:frame` slot directly; a consumer reading a *raw trace event* reads frame through the trace contract's one canonical accessor — `re-frame.trace/trace-event-frame` (alias `frame-of`), implemented as `(get-in ev [:tags :frame])` — rather than hardcoding the `[:tags :frame]` path or a dual top-level-or-tag read. This replaces any notion that a raw event's frame is "opaque to consumers": it is read, once, through the canonical accessor. Per [009 §Frame identity on the raw event](009-Instrumentation.md#frame-identity-on-the-raw-event-tags-frame-read-via-the-canonical-accessor) — one shape, one reader.

**Ordering.** Epochs within a frame are totally ordered by event settle-order — the order in which dequeued events complete their pipeline runs. (A drain that settles several events back-to-back yields one epoch per event, in dequeue order.) Across frames, ordering is per-frame only — there is no global epoch sequence.

**Bounded history.** The runtime keeps the last *N* epochs per frame (default 50, configurable via `(rf/configure! {:epoch-history {:depth N}})`). Older epochs are discarded.

**Redaction hook (projection-side advanced override).** Apps that record material the schema-driven projection cannot prove (a non-schema-declared sensitive slot) can install a `:redact-fn` on the `:epoch-history` configure key — `(rf/configure! {:epoch-history {:redact-fn (fn [record] …)}})`. Per [EP-0015 §15 (Epoch Redaction) + open-issue 6 disposition (RULED, hardened)](../docs/EP/EP-0015-frame-owned-egress-policy.md) the hook is **projection-side only**: it is the advanced override layer applied *after* the frame/profile `project-egress` projection, at the off-box egress boundary inside the projected-record helper (per [Security §Epoch privacy posture](Security.md#epoch-privacy-posture--raw-in-process-records-vs-projected-egress)). The fn takes the *projected* `:rf/epoch-record` and returns a (further-redacted) record for egress. The contract:
- **Projection-side placement; storage-side mutation removed.** The framework invokes `:redact-fn` once per record at the off-box egress boundary (inside the projected-record helper, after the frame/profile projection) — **never** at storage time. The in-process ring buffer and every `register-epoch-listener!` listener deliver the **raw** record: epoch records are causal replay material, and mutating them at rest corrupts the replay contract. Ordinary redaction needs only the frame's `:sensitive?` / `:large?` classification (the frame/profile projection); the override is the rare escape.
- **Rollup is raw-signal-derived.** The `:rf.epoch/sensitive?` top-level rollup (per [Security §Epoch privacy posture](Security.md#epoch-privacy-posture--raw-in-process-records-vs-projected-egress)) is computed from the raw record's schema-declared `:sensitive?` leaves at build-time, so it is a trustworthy off-box-branch signal on the raw ring record regardless of any projection-side redaction.
- **Failure isolation.** A throwing `:redact-fn` does not break the egress: the framework catches the throw, emits `:rf.warning/epoch-redact-fn-exception` (with `:tags {:frame <id>, :rf.epoch/id <id>}`) at projection time, and falls back to the projected (frame/profile-redacted) record. The raw ring record is untouched; the registration stays in place; the next projection re-attempts.
- **`restore-epoch!` is unaffected.** The override runs only on the projected egress copy; the ring stays raw, so `restore-epoch!` always rewinds to the exact recorded `:frame-state-after`.
- **Composition with the projected-record helper.** The projected-record helper is the *two-stage* projection — the frame/profile `project-egress` walk then the `:redact-fn` override — and is idempotent under `:rf/redacted` sentinels: re-projecting an already-projected record produces the same shape, so on-box and off-box consumers can compose without re-leaking.
- **Production elision.** `:redact-fn` shares the universal `re-frame.interop/debug-enabled?` gate. Production builds (`:advanced` + `goog.DEBUG=false`) elide the epoch surface entirely — there is no separate gate and no production-time invocation cost.

**Query.** `(rf/epoch-history frame-id)` returns the vector of `:rf/epoch-record` values for the frame, oldest-first.

**Restore.** `(rf/restore-epoch! frame-id epoch-id)` rewinds the frame's whole **frame-state** to the named epoch's `:frame-state-after` value — both partitions (app-db AND runtime-db), reinstalled atomically via `replace-frame-state!` so machine snapshots, the route slice, elision declarations, and SSR metadata are revived alongside app-db, not just the app-db projection (per [Spec-Schemas §`:rf/epoch-record`](Spec-Schemas.md#rfepoch-record) decision #2 + #9). Emits `:rf.epoch/restored`.

**Restore failure modes.** `restore-epoch!` is a query against a finite per-frame history; the restore can fail for distinct, named reasons. Each is an error trace event with a stable `:operation` key under the reserved `:rf.epoch/*` namespace; the call is a no-op on failure (the frame's `app-db` is unchanged):

| Failure | `:operation` | When it fires | `:tags` |
|---|---|---|---|
| **Unknown frame** | `:rf.error/no-such-handler` (kind `:frame`) | `frame-id` does not name a registered frame. | `{:kind :frame, :frame <id>}` |
| **Unknown epoch** | `:rf.epoch/restore-unknown-epoch` | `epoch-id` is not in the frame's current epoch history (either never recorded or aged out by `:depth`). | `{:frame <id>, :rf.epoch/id <id>, :history-size <n>}` |
| **Schema mismatch** | `:rf.epoch/restore-schema-mismatch` | The recorded frame-state's app-db partition (`:db-after`, the app-db projection of `:frame-state-after`) no longer validates against the currently-registered `app-schemas` set (a schema was added, tightened, or replaced since the snapshot was taken). | `{:frame <id>, :rf.epoch/id <id>, :schema-digest-recorded <s>, :schema-digest-current <s>, :failing-paths [<path> ...]}` |
| **Missing handler** | `:rf.epoch/restore-missing-handler` | The recorded frame-state's **runtime-db partition** references a registered-id (e.g. an active machine at `[:rf.runtime/machines :snapshots <id>]`, a registered route currently in `[:rf.runtime/routing :current]`) that is no longer present in the registrar. Restoring would leave the frame referencing dangling ids. The precondition reads the recorded `:frame-state-after`'s `:rf.db/runtime` partition (machine snapshots + route slice are runtime-db state, not the app-db `[:rf/runtime …]` path). | `{:frame <id>, :rf.epoch/id <id>, :missing [{:kind <kind>, :id <id>} ...]}` |
| **Version mismatch** | `:rf.epoch/restore-version-mismatch` | The frame's recorded `:rf/snapshot-version` (per [Spec-Schemas §`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot)) is incompatible with the currently-loaded machine definition. Hot-reload moved the machine forward; the older snapshot can no longer be interpreted. The current version is resolved the SAME way dispatch resolves the live spec: a **singleton** by its snapshot key (the key IS the registered machine-id), a **spawned actor** by its snapshot's `:rf/machine-type` (a registered TYPE keyword or an inline `:definition` map, per [Spec 005 §Reserved snapshot-internal keys](005-StateMachines.md)) — so a hot-reloaded spawned-actor TYPE's drift is caught (the instance-id key never named a registered handler). | `{:frame <id>, :rf.epoch/id <id>, :machine-id <id>, :version-recorded <int>, :version-current <int>}` — plus `:machine-type <type-ref>` (the resolved `:rf/machine-type`: keyword or inline-definition map) when the drifting snapshot is a spawned actor; omitted for a singleton. |
| **Concurrent-drain rejection** | `:rf.epoch/restore-during-drain` | `restore-epoch!` was called while the frame's run-to-completion drain is still in flight (per [002 §Run-to-completion dispatch](002-Frames.md#run-to-completion-dispatch-drain-semantics)). Restore is rejected; the user retries after settle. | `{:frame <id>, :rf.epoch/id <id>}` |
| **Halted-run target** | `:rf.epoch/restore-non-ok-record` | The named epoch's `:outcome` is not `:ok` — i.e. the record was committed for a halted pipeline run (`:halted-depth`, `:halted-destroy`, …). Halted records carry partial state for devtools introspection and are not valid restore targets; rewinding would land `app-db` in a state the run never settled to. | `{:frame <id>, :rf.epoch/id <id>, :rf.epoch/outcome <kw>, :halt-reason <any>}` |

All seven failures have `:op-type :error` and `:recovery :no-recovery`. Pair tools display the `:operation` and `:tags` to the user; the reserved `:rf.epoch/*` namespace lets tools route restore failures distinctly from frame-lookup errors. The failure surface is closed for v1 — additional categories require a Spec-ulation increment.

> **Note on the unknown-frame row.** Six of the seven failures fire under the reserved `:rf.epoch/*` namespace; the remaining one (**Unknown frame**) rides the framework-wide `:rf.error/no-such-handler` op-type with `:kind :frame` because it is a registry-lookup failure that predates the restore call (the same op-type fires for any registrar lookup that names a missing frame). A pair tool routing restore failures should therefore match on either `:rf.epoch/*` or `(:rf.error/no-such-handler ∧ :kind = :frame)` to catch the full failure surface.

**Restore caveat.** Even a *successful* restore rewinds durable **frame-state** only (both app-db and runtime-db partitions); effects already fired (HTTP requests sent, navigation pushed, localStorage written) are not reversed, and transient runtime state outside the durable partitions (in-flight HTTP handles, host handles, trace rings) is not reconstructed. Pair-shaped tools surface this caveat in their UI before applying a restore.

<a id="replay-mint-policy"></a>

**Replay is strict — recorded coeffects are re-presented, never re-minted.** `restore-epoch!` reinstalls a captured frame-state; the *other* time-travel gesture is **replay**, where a tool re-drives a recorded event through the application's own handlers by `(rf/dispatch …)` / `(rf/dispatch-sync …)`-ing it with the **recorded `:rf.cofx`** supplied in the dispatch opts (so the handler folds the exact facts the original run consumed). A replay dispatch MUST be **hard-wired `:strict`**: it supplies `{:rf.cofx/mint-policy :strict}` alongside the recorded `:rf.cofx` ([002 §Mint policies](002-Frames.md#mint-policies)). The contract:

- A recorded fact **present** on the supplied `:rf.cofx` is delivered verbatim (supplied values win) — replay re-presents it.
- A declared recordable fact **absent** from the record is `:rf.error/missing-required-cofx` — the `:strict` policy runs **no generator and performs no host read**. An incomplete record fails **loudly** rather than silently minting a fresh nondeterministic value (which would diverge from the original run — the exact failure the recording discipline exists to kill). This is why replay is unconditionally strict and never `:live`: a `:live` replay of a record missing one generated fact would mint a *different* value and the replay would no longer reproduce the recorded run.

A pair tool that re-dispatches a recorded event therefore always pairs the recorded `:rf.cofx` with `:rf.cofx/mint-policy :strict`; the strictness is a property of the *replay gesture*, not of any frame preset (replay can target a production `:live` frame and still be strict, because the per-call opt is the most-specific binding point and wins over the frame config).

**Replay re-supplies the recorded envelope overrides too (rf2-yigokd).** `:interceptor-overrides` edits the pre-commit interceptor chain — it is fold-changing, exactly the same class of fact `:rf.cofx` is. A run recorded with an active `:interceptor-overrides` (say, a validator interceptor removed for a test) replays **with the validator reinstated** unless the replay dispatch re-supplies the override too — silently running a *different* effective chain than the original run and potentially committing a different `:db-after`. That violates this section's own rule: **faithful or fail-loud, never silent divergence.** A `:strict` replay therefore supplies THREE things alongside the recorded event, not two:

```clojure
(rf/dispatch trigger-event
             (merge {:rf.cofx (:rf.cofx record)
                     :rf.cofx/mint-policy :strict}
                    (select-keys record [:fx-overrides :interceptor-overrides])))
```

`:fx-overrides` / `:interceptor-overrides` on the `:rf/epoch-record` ([Spec-Schemas §`:rf/epoch-record`](Spec-Schemas.md#rfepoch-record)) are spelled BARE — identical to the `:rf/dispatch-opts` key names — precisely so `(select-keys record [:fx-overrides :interceptor-overrides])` splats straight into the dispatch opts with no key translation, and are ABSENT (not an empty map) when the original run carried no per-call/lexical override, so an override-free replay's opts map is unchanged by the merge. Two re-supply rules:

- A recorded `:interceptor-overrides` re-presents verbatim — it is EDN by construction (EP-0022 retired value-valued replacements), so there is nothing to translate.
- A recorded `:fx-overrides` entry carrying the opaque sentinel `:rf/fn-override` (a CLJS-reference fn-valued override the router could not record — fns are never EDN) makes the run **UNREPLAYABLE under `:strict`**: the replaying tool MUST fail loud on that entry, the same shape as `:rf.error/missing-required-cofx` (an incomplete record, not a silent partial replay), rather than dispatching without the fn-valued override the original run had active.

**Scope is pinned to the envelope, not the frame.** The captured `:fx-overrides` / `:interceptor-overrides` are the recorded run's OWN per-call (+ lexical, for `:fx-overrides`) keys only — never the per-frame `reg-frame` tier. A replay target's per-frame overrides (if any) are a property of the LIVE frame config at replay time, not a recorded fact; they apply exactly as they would to any ordinary dispatch against that frame, merging with the re-supplied per-call keys per the normal per-frame ⋈ per-call precedence ([002 §Per-frame and per-call overrides](002-Frames.md#per-frame-and-per-call-overrides)).

**Production elision.** Per [009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code) and [API §Tracing — `re-frame.interop/debug-enabled?` row](API.md#tracing), the trace surface, schema validation, registrar trace emit, and epoch-history machinery share a single gate — `re-frame.interop/debug-enabled?`. On CLJS the gate is an alias of `goog.DEBUG` and production builds (`:advanced` + `goog.DEBUG=false`) elide every gated branch via Closure DCE; on JVM the same name is a ns-load-time `def` driven by `-Dre-frame.debug=false` / `RE_FRAME_DEBUG=false` (per [009 §JVM builds](009-Instrumentation.md#jvm-builds)). Setting either disables epoch-history capture and the restore surface entirely. CI's `npm run test:elision` job (Spec 009 §Production-elision verification) asserts the CLJS DCE contract holds for every gated surface, including the epoch-history primitives once they land; the JVM gate is covered by `epoch_jvm_prod_gate_test.clj` and `interop_debug_gate_test.clj`.

### Worked example: walking history and restoring

A pair tool that wants to render a per-frame undo affordance walks `epoch-history`, picks a target epoch (typically by index, by `:event-id`, or by user click on a visualised list), and calls `restore-epoch!`. The round-trip below covers the dev-shape consumption pattern end-to-end, including the listener wiring that catches restore failure traces:

```clojure
;; A pair tool's "rewind to before that event" affordance.
;; - Walks the per-frame epoch history (oldest-first vector).
;; - Picks the most recent epoch BEFORE a target event-id.
;; - Calls restore-epoch! and listens for either
;;   :rf.epoch/restored (success) or any :rf.epoch/restore-* error.

(defn epoch-before-event
  "Return the epoch-id of the most recent epoch in `frame-id`'s history
   that precedes `target-event-id`, or nil if none exists."
  [frame-id target-event-id]
  (let [history (rf/epoch-history frame-id)            ;; oldest-first vector of :rf/epoch-record
        before  (take-while #(not= target-event-id (:event-id %)) history)]
    (when (seq before)
      (:epoch-id (last before)))))

;; Listener: catch restore success / failure traces and fan out to UI.
(rf/register-listener!
  :trace
  :my-tool/restore-watcher
  (fn [ev]
    (case (:operation ev)
      :rf.epoch/restored                    (notify-ui :restored ev)
      :rf.epoch/restore-unknown-epoch       (notify-ui :error ev)
      :rf.epoch/restore-schema-mismatch     (notify-ui :error ev)
      :rf.epoch/restore-missing-handler     (notify-ui :error ev)
      :rf.epoch/restore-version-mismatch    (notify-ui :error ev)
      :rf.epoch/restore-during-drain        (notify-ui :error ev)
      :rf.epoch/restore-non-ok-record       (notify-ui :error ev)
      ;; Unknown-frame rides :rf.error/no-such-handler (kind :frame); see note above.
      nil)))

;; Trigger the rewind. restore-epoch! returns false on failure (the failure mode
;; is delivered via the trace stream); on success, the whole frame-state (both
;; partitions) has been rewound to the epoch's :frame-state-after and
;; :rf.epoch/restored has fired.
(when-let [target (epoch-before-event :app/main :checkout/submit)]
  (rf/restore-epoch! :app/main target))
```

The walk-history-then-restore shape is the canonical pair-tool gesture; render-tree visualisers, "what did this event do?" probes, and conformance harnesses all build on the same primitives. Tools that want post-restore confirmation without registering a trace listener can re-call `(rf/app-db-value :app/main)` and diff against the pre-restore snapshot.

### Pair-tool writes — state injection

`restore-epoch!` and `dispatch` cover most of pair-tools' write needs (rewind to a recorded prior state; drive a pipeline run through the application's own handlers). The remaining case is **state injection** — installing an arbitrary frame value that the runtime never recorded and that no event handler need exist to produce.

Under the two-partition frame contract (per [002 §The two-partition frame contract](002-Frames.md#the-two-partition-frame-contract)) the injection surface is **ONE partial-map mutator**, `replace-frame-state!` (rf2-t3lftq — API-shrink #3 consolidated the former `replace-app-db!` / `reset-app-db!` / `replace-runtime-db!` / `replace-frame-state!` four-mutator family, which shared identical machinery and differed only in which partition keys they touched, into this ONE surface). `frame-state` is a PARTIAL frame-state map (any subset of `{:rf.db/app … :rf.db/runtime …}`): a present key replaces that partition; an absent key is preserved unchanged. A db-shaped key never silently touches the OTHER partition. API.md ([§Epoch history](API.md#epoch-history-per-tool-pair)) is the canonical row table; the contract is owned here:

| Call shape | Replaces | Use |
|---|---|---|
| `(rf/replace-frame-state! frame-id {:rf.db/app app-db})` | **only** the app-db partition; live runtime-db is preserved | app-db-only state injection (the former `replace-app-db!`) |
| `(rf/replace-frame-state! frame-id {:rf.db/app {}})` | the app-db partition with `{}`; live runtime-db is preserved | app-db-only reset (the former `reset-app-db!`; the app-db sibling of a full frame reset, `destroy-frame!` + `reg-frame`) |
| `(rf/replace-frame-state! frame-id {:rf.db/runtime runtime-db})` | **only** the runtime-db partition | privileged runtime / full-frame tool injection of subsystem state (the former `replace-runtime-db!`) |
| `(rf/replace-frame-state! frame-id {:rf.db/app app-db :rf.db/runtime runtime-db})` | **both** partitions atomically | the full-frame install for tool-driven replay / fixture install |

Every call bypasses the dispatch loop, calls `replace-container!` on the frame-state substrate container, and records a synthetic `:rf/epoch-record` so `restore-epoch!` can rewind past the injection. A present partition key never silently touches the OTHER partition; an ABSENT key is carried forward from the frame's current value, not nilled.

**Use cases the surface covers:**

- **Evolved-state-shape probes.** A pair-tool agent rewrites a sub or handler and needs to seed an `app-db` shape that the new code expects, *without* firing a (possibly-failing) pipeline run through stale handlers. `dispatch` would re-trigger the broken run. An app-only map (`{:rf.db/app app-db}`) injects app data while leaving live machines / routes (runtime-db) untouched.
- **Story tools.** Fixture-shaped state injection — "render the cart in this state" — without authoring a setup event for every story. A story that also seeds machine snapshots or a route supplies a both-key map.
- **Conformance harnesses.** Property-test runs that load a known frame value, run a single dispatch, assert post-state. Same shape as a test setup.
- **Time-travel from JSON-loaded bug repros.** A user attaches a serialised frame-state from a saved bug; the agent loads it via a both-key map. `restore-epoch!` covers this only when the state is in the ring buffer; arbitrary injection from outside the recorded history needs a write path.

**Contract.**

- **Rejects a bad-shaped map before touching the frame.** `frame-state` MUST carry at least one recognized partition key (`:rf.db/app` and/or `:rf.db/runtime`) and no unrecognized key. This check runs BEFORE frame resolution — a malformed map is malformed against any frame, and a caller's typo'd or empty map must never silently no-op the call while still returning `true` (see **Failure modes** below).
- **Replaces the container.** `(rf/replace-frame-state! frame-id frame-state)` calls `replace-container!` on the frame's ONE physical frame-state container, installing the merge of the current frame-state with the present keys of `frame-state`. Subscribers route off the post-replace value the same way they do after a `restore-epoch!` happy path or a normal pipeline-run settle.
- **Records a synthetic epoch.** A fresh `:rf/epoch-record` lands in `(rf/epoch-history frame-id)` carrying `:event-id :rf.epoch/db-replaced`, `:trigger-event [:rf.epoch/db-replaced]`, `:frame-state-before` (the pre-replace frame value), and `:frame-state-after` (the post-replace frame value — the merge result, so an absent key's PRESERVED value is visible there too). The `:sub-runs` / `:renders` / `:effects` projections are empty — no pipeline run ran. `restore-epoch!` of a *prior* epoch rewinds past the injection; `restore-epoch!` of the synthetic record itself rewinds *to* the injected value (i.e. a round-trip to where the replace already left things).
- **Emits `:rf.epoch/db-replaced`** on success with `:tags {:frame <id> :rf.epoch/id <id>}`, `:op-type :rf.epoch`. Pair-tool dashboards filter on the operation to route pair-tool injections distinctly from run-driven epochs.
- **Fires `register-epoch-listener!` listeners.** The assembled record is delivered to every registered epoch listener after it lands in the ring buffer — same shape as a pipeline-run-settle delivery.
- **Returns `true`** on success, `false` on any failure.

**Failure modes** (each is a no-op on the frame-state and emits a structured error trace):

| Failure | `:operation` | When it fires | `:tags` |
|---|---|---|---|
| **Bad keys** | `:rf.error/replace-frame-state-bad-keys` | `frame-state` carries no recognized partition key (e.g. `{}`, or a map of only unrelated keys), OR carries an unrecognized key (e.g. a typo'd `:rf.db/apps`) — checked BEFORE frame resolution. | `{:frame <id>, :reason (:no-recognized-keys or :unknown-keys), :keys [<key> ...]}` |
| **Unknown frame** | `:rf.error/no-such-handler` (kind `:frame`) | `frame-id` does not name a registered frame. | `{:kind :frame, :frame <id>}` |
| **Drain in flight** | `:rf.epoch/replace-during-drain` | the injection was called while the frame's run-to-completion drain is still running (per [002 §Run-to-completion dispatch](002-Frames.md#run-to-completion-dispatch-drain-semantics)). The injection is rejected; the caller retries after settle. | `{:frame <id>}` |
| **Schema mismatch** | `:rf.epoch/replace-schema-mismatch` | a PRESENT `:rf.db/app` value fails the frame's currently-registered `app-schema` set (per [Spec 010 §Per-frame schemas](010-Schemas.md#per-frame-schemas)); a PRESENT `:rf.db/runtime` value is checked against the framework-owned runtime-db validator (`reg-runtime-schema`, per [010 §App schemas validate the app-db partition only](010-Schemas.md#app-schemas-validate-the-app-db-partition-only)). An ABSENT key is not walked (it is preserved, not written). When no schemas are registered the validation is a no-op — every value is accepted. | `{:frame <id>, :failing-paths [<path> ...]}` |
| **History disabled** | `:rf.epoch/replace-history-disabled` | the injection was called while the epoch ring buffer is disabled (`(rf/configure! {:epoch-history {:depth 0}})`, per [§Time-travel](#time-travel-epoch-snapshots-and-undo)). The synthetic `:rf.epoch/db-replaced` undo-anchor cannot land in the (disabled) ring, so the injection's "undo works after this call" invariant is unsatisfiable — it is rejected loudly rather than returning a false `true` (the in-artefact analogue of the absent-artefact `:rf.error/epoch-artefact-missing` throw). The caller re-enables history (`:depth > 0`) before injecting if it needs undo. | `{:frame <id>}` |

All five failures have `:op-type :error` and `:recovery :no-recovery`. The closed-set v1 failure surface mirrors `restore-epoch!`'s shape.

**Production elision.** Per [009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code) the injection surface shares the universal compile-time gate (`re-frame.interop/debug-enabled?`, alias of `goog.DEBUG`); production builds (`:advanced` + `goog.DEBUG=false`) elide the body via Closure DCE. The surface is **dev-only** — pair-tool writes do not ship in production binaries. CI's `npm run test:elision` job asserts the contract holds for the success op (`:rf.epoch/db-replaced`) and the failure ops.

**Artefact home.** The injection surface lives in `re-frame.epoch` (it records a synthetic `:rf/epoch-record`, so the surface is epoch-adjacent and naturally co-located with `restore-epoch!` / `register-epoch-listener!`). The core re-export late-binds through the hook table; unlike the read-shaped re-exports (which degrade silently when the artefact is absent), the write surface raises `:rf.error/epoch-artefact-missing` — the caller's invariant is "undo works after this call", and a silent no-op would lie about that invariant.

**Worked example.**

```clojure
;; A pair-tool agent has just hot-swapped a handler that operates on
;; an evolved app-db shape. Inject the new shape directly so the
;; pipeline run doesn't re-run through stale handlers, then dispatch a
;; single event to verify the new code works against the seeded state.
;; An app-only map ({:rf.db/app v}) leaves the frame's live runtime-db
;; (machines, routes) intact; use a both-key map when the repro also
;; seeds those.

(when (rf/replace-frame-state! :app/main {:rf.db/app {:cart {:items [{:sku "abc" :qty 2}]}
                                                      :checkout/state :ready}})
  ;; replace-frame-state! has fired :rf.epoch/db-replaced and recorded a
  ;; synthetic epoch. Now drive a dispatch to exercise the new handler.
  (rf/dispatch [:checkout/submit] {:frame :app/main}))

;; To rewind PAST the injection (back to whatever the previous epoch
;; was), pick the epoch BEFORE the synthetic one and restore.
(let [history (rf/epoch-history :app/main)
      pre     (last (filter #(not= :rf.epoch/db-replaced (:event-id %)) history))]
  (when pre
    (rf/restore-epoch! :app/main (:epoch-id pre))))
```

**What the injection surface is not.** It is **not** a substitute for `dispatch` — handlers, interceptors, fx, and the trace stream all stay quiet during a replace. Use it only when bypass-the-pipeline-run is *required* (the four use cases above); for any change you want the data loop to see, dispatch a real event. The synthetic epoch's empty `:sub-runs` / `:renders` / `:effects` projections are the visible signal that no pipeline run ran.

## Surface behaviour against destroyed frames

The Tool-Pair surfaces above (`epoch-history`, `app-db-value`, `restore-epoch!`, `replace-frame-state!`, `register-epoch-listener!`) all take a `frame-id`. A pair tool can call any of them after the frame has been destroyed (per [002 §Destroy](002-Frames.md#destroy)) — most often because the tool kept a reference to a frame whose owning component unmounted, or because a teardown sequence interleaved with an in-flight tool gesture. The runtime commits to a **closed contract** for these races so a tool can route them deterministically without inspecting registrar internals.

Pattern: **read-shaped surfaces return an empty shape** (so a defensive `(when ...)` is sufficient); **mutating-shaped surfaces raise structurally** (so a tool that intended a write learns the write did not happen); **listener fan-out emits a one-shot trace** when a previously-registered callback is silenced because its observed frame was destroyed.

| Surface | Shape | Behaviour against destroyed (or never-registered) frame |
|---|---|---|
| `(rf/epoch-history frame-id)` | read | Returns `[]` (empty vector). Identical to "no epochs yet recorded" — consumers that want to distinguish a destroyed frame from a fresh one must consult `(rf/frame-meta frame-id)` or `(rf/frame-ids)` separately. |
| `(rf/app-db-value frame-id)` | read | Returns `nil`. Consumers that want a destroyed-vs-unknown distinction consult `(rf/frame-meta frame-id)`. |
| `(rf/restore-epoch! frame-id epoch-id)` | mutate | Emits `:rf.error/no-such-handler` (kind `:frame`, tags `{:kind :frame, :frame <id>}`) and returns `false`. Same trace shape as any other registry-lookup miss — already enumerated as the **Unknown frame** row of [§Time-travel](#time-travel-epoch-snapshots-and-undo)'s restore-failure table. |
| `(rf/replace-frame-state! frame-id frame-state)` | mutate | Emits `:rf.error/no-such-handler` (kind `:frame`) and returns `false`. Identical wording to `restore-epoch!`'s frame-miss row — pair-tool writers can match on a single category for both surfaces. |
| `(rf/register-listener! :epoch id callback)` (process-global) | register | The current API is process-global and does not bind a callback to a specific frame; this row therefore does not apply to the existing signature. A future opts-form (`{:frame frame-id}`) would raise `:rf.error/no-such-handler` (kind `:frame`) at registration time when its target is absent — the same shape registration-against-an-absent-target uses elsewhere. The framework reserves the spelling. |
| Pre-registered `register-epoch-listener!` callback whose observed frame is later destroyed | listener silencing | The runtime emits `:rf.epoch.cb/silenced-on-frame-destroy` (`:op-type :rf.epoch.cb`) **once per `(frame, cb-id)` pair**, with `:tags {:frame <id>, :cb-id <id>}`, on the destroy-cascade boundary. Subsequent destroys of the same frame do not re-emit. The callback registration remains in place — eviction is the consumer's call (per [009 §`register-epoch-listener!` invocation rules](009-Instrumentation.md#register-epoch-listener--assembled-epoch-listener)). |

The trace event is enumerated in [009 §`:op-type` vocabulary](009-Instrumentation.md#op-type-vocabulary); its `:tags` schema is canonicalised in [Spec-Schemas](Spec-Schemas.md#per-category-tags-schemas).

**Why "silencing" is a trace and not a return value.** The `register-epoch-listener!` callback never sees a record from a destroyed frame — the runtime stops producing records for the frame the moment its destroy walks. A tool that doesn't know its observed frame was destroyed therefore sees a callback that simply *stopped firing*, with no signal it can route off. The silencing trace closes that gap: pair-tool dashboards, REPL companions, and conformance harnesses all subscribe to the trace stream already (per [§How AI tools attach](#how-ai-tools-attach)), so the existing channel carries the disambiguation. One-shot semantics keep the stream from accumulating noise on rapid frame churn (test fixtures, story tools).

**Listener-silencing trace: implementation note.** The runtime tracks, per cb-id, the set of frame-ids that cb has been delivered records for. When a frame is destroyed (per [002 §Destroy](002-Frames.md#destroy)), every cb whose observed-frame set contains that frame receives one silencing trace; the cb's entry for that frame-id is then dropped so a re-registration of a same-keyed frame (e.g. a `destroy-frame!` + re-`reg-frame` reset of `:app/main`) can re-arm. A cb that was never delivered any record (e.g. registered immediately before destroy) does not see a silencing trace — there is nothing to silence.

**Production elision.** The destroyed-frame contract surfaces (the registry-lookup error traces, the silencing trace, the read-empty shapes) all share the universal `re-frame.interop/debug-enabled?` gate; production builds (`:advanced` + `goog.DEBUG=false`) elide the trace emit and the dev-only Tool-Pair surfaces themselves (per [§Time-travel](#time-travel-epoch-snapshots-and-undo) §Production elision and [009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code)). A shipped binary does not carry the silencing trace string.

Cross-references: [002 §Destroy](002-Frames.md#destroy) (the lifecycle event being raced), [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) (the `:rf.error/no-such-handler` row consumed here), [§How AI tools attach](#how-ai-tools-attach) (the attachment surface that consumes the silencing trace).

## Performance API consumption

The Performance API channel (per [009 §Performance instrumentation](009-Instrumentation.md#performance-instrumentation)) is the prod-friendly counterpart to the dev-only trace stream. Pair-shaped tools that want timing data — an in-app perf overlay, an APM forwarder, a custom `PerformanceObserver` watching for slow renders — read it via the standard browser User Timing surface. No re-frame2 API call is needed; the runtime emits `User Timing` `measure` entries and any consumer that knows about `performance.getEntriesByType` can read them.

Names are stable and namespaced under `rf:`:

```
rf:event:<event-id>
rf:sub:<sub-id>
rf:fx:<fx-id>
rf:render:<view-id>
```

Consumer pattern — pull every re-frame entry from the recent run:

```javascript
performance.getEntriesByType('measure')
  .filter(e => e.name.startsWith('rf:'))
  .forEach(e => {
    const [_rf, bucket, ...idParts] = e.name.split(':');
    const id = idParts.join(':');
    // e: { name, startTime, duration, ... }
    // bucket: 'event' | 'sub' | 'fx' | 'render'
  });
```

Live: `PerformanceObserver` fires per emitted entry (the canonical shape for a tool that wants to react in real time):

```javascript
new PerformanceObserver((list) => {
  for (const e of list.getEntriesByType('measure')) {
    if (e.name.startsWith('rf:')) {
      sendToAPM(e);  // or update an overlay, or buffer for a flush
    }
  }
}).observe({ type: 'measure', buffered: true });
```

The channel is gated on `re-frame.performance/enabled?` — a `goog-define` boolean that defaults to `false`. Pair tools that depend on the channel **MUST** document the consumer's responsibility to flip the flag in their build:

```edn
;; consumer's shadow-cljs.edn
{:builds {:app {:target           :browser
                :compiler-options {:closure-defines {re-frame.performance/enabled? true}}}}}
```

When the flag is off (the default), Closure DCE elides every bracket; `performance.getEntriesByType('measure')` returns no `rf:`-prefixed entries because none were ever emitted. The perf channel is *opt-in for prod* — timing instrumentation has measurable cost on heavy hot paths, so consumers choose to pay it.

The Performance API surface is **CLJS-only**. JVM artefacts (SSR, headless tests) emit no perf entries; tools running there use the host's profilers (clj-async-profiler, JFR).

## REPL-eval

The pair tool's "execute arbitrary expression" capability is the **host's REPL** (CLJS: nREPL via cider; Python: IPython; etc.) — re-frame2 doesn't ship an evaluator, just exposes its data structures. An nREPL session attached to a running re-frame2 app can already see `re-frame.db/app-db` (or its substrate-agnostic equivalent), the registrar, and any namespace-resolvable function.

The CLJS reference's commitment: **public APIs** (everything in `re-frame.core`) are stable for pair-tool consumption. **Private namespaces** (`re-frame.db`, `re-frame.router`, `re-frame.subs`, `re-frame.events`, `re-frame.registrar`) are off-contract — they may change between versions. Per [MIGRATION §M-1](../migration/from-re-frame-v1/README.md), tools that reach into private namespaces will need to migrate.

The pair tool is encouraged to use only public APIs. If it needs something not public, file a Spec issue.

## Source-mapping UI clicks back to code

The "which button is at `src/app/profile/view.cljs:84`?" capability requires every render-tree node — every registered view, every hiccup tag — to carry source coords. re-frame2's view registrations include `:ns`/`:line`/`:file`. The CLJS reference additionally:

- Captures source coords at every `reg-view` macro expansion (`:ns` / `:file` / `:line` / `:column`).
- **Annotates rendered DOM** with a `data-rf2-source-coord="<ns>:<sym>:<line>:<col>"` attribute pointing back to the registration that produced it. This is **mandatory** in re-frame2 per [Spec 006 §Source-coord annotation](006-ReactiveSubstrate.md#source-coord-annotation-mandatory) — every substrate adapter whose host has a DOM-attribute concept MUST inject the attribute. Annotation is dev-only and gated on `interop/debug-enabled?` (the CLJS mirror of `goog.DEBUG`); production builds elide the attribute via dead-code elimination so there is no DOM-bytes cost in shipped bundles.

With the annotation in place, a pair tool can take a click position, read the nearest annotation, and resolve back to a source coordinate. Documented exemption (per Spec 006 §Source-coord annotation): components returning React Fragments, host-component heads (`:>`), or other non-DOM roots are exempt; pair tools fall back to `(rf/handler-meta :view id)` for those nodes.

### State-machine source-coord stamping

The DOM-attribute annotation above maps clicked DOM nodes to view registration call sites. A complementary surface maps state-machine spec elements (guards / actions / transitions / state-nodes) back to their source positions.

Per [Spec 005 §Source-coord stamping](005-StateMachines.md#source-coord-stamping), the `reg-machine` macro walks its literal spec form at expansion time and CO-LOCATES per-element source onto each guard / action / on-spawn-action entry, plus a reference-site `:source-coords` onto each map node (state-node / transition map) inside the `:states` tree:

```clojure
;; Per-element definition coord + source — co-located on the entry:
(get-in (rf/machine-meta :auth/login) [:guards :form-valid?])
;; {:fn <fn> :source-coords {:ns ... :line ... :column ... :file ...} :source-code "(fn ...)"}

;; Reference-site coords — read directly off the map node:
(get-in (rf/machine-meta :auth/login) [:states :form :source-coords])
;; {:ns ... :line ... :column ... :file ...}
(get-in (rf/machine-meta :auth/login) [:states :form :on :submit :source-coords])
;; {:ns ... :line ... :column ... :file ...}
```

Pair tools use these for two distinct UI gestures:

- **Jump to definition.** A click on a guard/action name in the visualisation reads the co-located entry's `:source-coords` — `(get-in spec [:guards <id> :source-coords])` / `[:actions <id> :source-coords]` / `[:on-spawn-actions <id> :source-coords]` — to find where the fn is implemented.
- **Jump to call site.** A click on a transition arrow or state node reads the `:source-coords` off that node (e.g. `(get-in spec [:states :idle :on :submit :source-coords])`); for an inline-fn / keyword slot (`{:guard :form-valid?}`) the slot itself holds a value rather than a map, so the tool walks UP to the nearest enclosing map node's `:source-coords`, which IS stamped.

The framework commits to **the co-located entry shape, the per-node `:source-coords` shape, and the inline-fn / keyword rule** (inline-fn / keyword slots carry no node of their own; the nearest enclosing map node's coord stands in). Pair tools ship their own UI affordance over them. Like `data-rf2-source-coord`, the dev-only source is gated on `interop/debug-enabled?` and elides under `:advanced` + `goog.DEBUG=false`.

### Where the DOM-to-source helpers live (re-frame2 vs tool)

The audit found the upstream pair tool ships `dom/source-at`, `dom/find-by-src`, and `dom/fire-click-at-src` helpers (it currently parses re-com's `data-rc-src` attribute, but the shape is general). Pair-shaped tools need *some* DOM-to-source bridge; the question is whether the helpers themselves are part of re-frame2's contract or live in the consuming tool.

**re-frame2's commitment is the attribute, not the helpers.** Specifically:

- The runtime emits the `data-rf2-source-coord` attribute on rendered DOM nodes when source-annotation is enabled. **The attribute's value format is a committed public contract** (per [Spec-Schemas §`:rf/source-coord-attr`](Spec-Schemas.md#rfsource-coord-attr)) — a 4-segment colon-separated string `<ns>:<sym>:<line>:<col>` where `<sym>` is the registered handler-id (not a file path). Consumers parse the four segments directly to recover `<ns>` / `<handler-id>` / `<line>` / `<col>`. To recover the full source-coord shape including `:file`, follow up with `(rf/handler-meta :view <handler-id>)` — the registration metadata returns `:rf/source-coord-meta` (per [Spec-Schemas](Spec-Schemas.md#rfsource-coord-meta)) which carries all four keys (`:ns`, `:line`, `:column`, `:file`).
- The framework does **not** ship `dom-source-at` / `find-by-src` / `fire-click-at-src` style helpers. These are tool-side: the pair tool reads the attribute via its own host's DOM access (`document.querySelector` in CLJS, `page.locator` in Playwright-driven flows, etc.) and resolves the source coordinate locally — the parse is straightforward against the committed format above.

**Why tool-side, not framework-side:** the helpers depend on host-specific DOM access that re-frame2 the framework does not assume — a pair tool driving a browser via CDP, a server-rendered diagnostic dump, or a static analyzer all want different "lookup the attribute" implementations. Pinning a single helper signature here would either over-constrain consumers or under-serve them. The framework commits to the attribute (stable, cross-host, parseable); the consuming tool ships the host-appropriate query primitives on top.

A future re-frame2 minor version may introduce framework-side helpers if the ecosystem converges on a single shape; the attribute contract is forward-compatible with that addition.

### Reading rendered content + producing entity — the view→content read

The DOM-to-source bridge above answers "*where in the code* did this come from?" — a gesture/selector → source-coord. The complementary view-plane question is "*what does the thing I'm looking at SHOW, and which view produced it?*" — given a view-id (or a point / selector), return the rendered subtree **plus** the producing re-frame2 entity in one read. This rides the **same** view-id↔DOM map the source bridge does, in the other direction:

- The framework's commitment is again **the attributes**, not the helper. Every registered view's rendered root carries **both** `data-rf-view="<id>"` (the view-id, per [Spec-Schemas §`:rf/view-id-attr`](Spec-Schemas.md#rfview-id-attr)) and `data-rf2-source-coord` (the source coord). The view-id attribute is the *forward* index — given a view-id, `[data-rf-view='<id>']` locates the rendered root; given an arbitrary node (a point hit, a sub-match), the nearest `[data-rf-view]` ancestor is the producing view (the same DOM-containment rule the fallback view-walker uses, per [Spec 006 §View tagging contract](006-ReactiveSubstrate.md#view-tagging-contract-fallback)). A consuming tool needs no app-specific test ids — the substrate adapter already stamps the attribute on every view.
- The framework does **not** ship the content-read helper. Like the source bridge, it is **tool-side**: the pair tool resolves the element via its host's DOM access, reads `textContent` / attribute strings, and recovers the producing entity (view-id from `data-rf-view`; `:file`-complete source coord via `(rf/handler-meta :view <id>)`; the frame's materialised subscriptions via the sub-cache read surface). The CLJS reference's pair tool ships this as a `ui/read` op (wire name `read-ui`).
- **Privacy.** Rendered DOM text can carry user data, so a content read that egresses off-box **MUST** route the returned text through `re-frame.core/elide-wire-value` with off-box defaults — the same posture [§Direct-read privacy posture for `sub-cache` and `get-path`](#direct-read-privacy-posture-for-sub-cache-and-get-path) mandates for app-db reads. Declared-large content collapses to `:rf.size/large-elided`; raw user DOM text never rides unconditionally.

The view-plane content read is **read-only by construction** — it reads `textContent` / attribute strings / element-at-point only; it never dispatches, mutates a node, or triggers a render (the render already happened; this is a no-recompute read of its output).

### Editor URI scheme allowlist

Source-mapping clicks open the resolved coord in the developer's editor. The CLJS reference (and any port that ships editor integration) accepts **editor templates** — URI patterns parameterised by file + line that produce a clickable IDE-protocol URI. Built-in templates cover the common targets (`vscode://file/%s:%d`, `idea://open?file=%s&line=%d`, `cursor://...`, `subl://...`); custom templates let devs route to JetBrains family editors, Sublime, Emacs / org-tooling, vim, and long-tail editor schemes via a config-level slot:

```clojure
{:editor {:custom "vim://%s:%d"}}
```

An attacker-controllable scheme — `javascript:`, `data:`, `vbscript:` — that landed in the editor-template slot would be clicked, opening an attack surface in the dev's browser (script execution, embedded payload navigation). The minimum gate is a **scheme rejection list**, not an allowlist; everything other than the three known-bad schemes passes through.

**Normative contract.** Editor-template handling **MUST** reject URIs whose scheme is `javascript:`, `data:`, or `vbscript:` (case-insensitive). The three are documented XSS vectors; the rejection list is the floor. Everything else — `vim:`, `idea:`, `subl:`, `org:`, `vscode:`, `cursor:`, `code-server:`, and future editor schemes — passes through with no dev burden. The check fires at editor-template registration time **and** at click-resolution time (a registered template whose pattern produces a runtime URI on one of the three rejected schemes is also rejected at click time, so a template that interpolates user input into the scheme position is also caught). The rejection surface emits a structured warning; no silent fall-through to a default editor.

**Pragmatic stance.** Per [Security.md §Pragmatic stance](Security.md#pragmatic-stance) proposition 2 ("gate accidents, not theoretical attacks"), the rejection list is the **minimum** that closes the known-bad cases without forcing a list-maintenance burden onto every dev workflow that wants to use a long-tail editor. The audit posture is: gate `javascript:` / `data:` / `vbscript:` because those *are* known XSS vectors; do not gate `vim:` / `idea:` / `subl:` / future-editor-N because a long-tail allowlist would be friction without proportionate risk reduction. Per [Security.md §Editor URI scheme allowlist](Security.md#editor-uri-scheme-allowlist).

### Open-in-editor launch modes

Jump-to-source (a source-coord click → the developer's editor at `file:line`) has **two** launch paths. Tools that ship the affordance (Xray, Story) **PREFER the dev-server endpoint** and **FALL BACK to the `editor://` URI** above. The mechanism is **additive** — the URI path is never removed; it remains the contract for static exports, non-shadow hosts, and production-mode inspection.

1. **Dev-server endpoint (preferred) — the JS-ecosystem standard.** Vite (`/__open-in-editor`), react-dev-utils (`launchEditorEndpoint`), and Next all expose a dev-server endpoint that resolves the file on the dev machine and launches the editor server-side. re-frame2's reference equivalent is a shadow-cljs `:dev-http` Ring `:handler` (`re-frame.testbed.open-in-editor-server/handler`, a **JVM-only `.clj`**) answering `POST /__rf-open-in-editor?file=<…>&line=<n>&column=<c>` (with `OPTIONS` for the cross-port CORS preflight). The endpoint is **POST-only** — it launches the developer's editor on a local path, so a `GET`/`HEAD` drive-by (`<img>`/`<form>`/`no-cors` fetch) must never trigger a launch; non-POST requests are rejected 405 and the handler additionally requires a loopback `Host`/`Origin` (the historic Vite / react-dev-utils CVE class). It resolves the (classpath-relative) `:file` against the live source-paths **at runtime** (the runtime twin of the macro-time absolutisation in [§State-machine source-coord stamping](#state-machine-source-coord-stamping)'s sibling, `re-frame.source-coords/absolutise-file`) and launches the editor via the `launch-editor` npm package (handles every OS + editor, superseding the per-editor scheme table). The client open-seam `fetch`es the endpoint on its own origin; the configured editor keyword rides as an `editor=` hint mapped to a `launch-editor` command. Net effect: jump-to-source is **zero-config for everyone — including the JAR / in-jar / odd-classpath cases the URI path's compile-time path-bake cannot reach — with no on-disk root config and no absolute path baked into the bundle.**

2. **`editor://` URI (fallback).** When no dev server answers (static export, non-shadow host, production inspection, network error, non-2xx), the client falls back to the `editor://` scheme path above — the URI built from the editor template + the host-configured project-root, navigated via `Location.assign`. The scheme-rejection contract above applies unchanged to this path.

**Framework commitment.** re-frame2 commits to **the endpoint path + query shape** (`POST /__rf-open-in-editor?file=&line=&column=&editor=`, with `OPTIONS` only for the CORS preflight), the **POST-only + loopback-guarded** posture (a GET/HEAD drive-by must never launch an editor — see the launch-modes note above), the **additive prefer-then-fall-back** rule, and the **runtime classpath-relative `:file` resolution** semantics. The shadow-cljs `:dev-http` wiring and the `launch-editor` invocation are reference-implementation concerns (a non-shadow host ships its own endpoint at the same path + shape). The server handler is dev-only by construction (`.clj`, never in a CLJS/browser build) and the client seam DCEs out of release bundles — neither reaches a production bundle.

<a name="operating-frame"></a>
## Operating frame — multi-frame resolution

re-frame2 is multi-frame (per [002-Frames.md](002-Frames.md)). Every pair-tool surface that names a `frame-id` (`app-db-value`, `epoch-history`, `restore-epoch!`, `replace-frame-state!`, `dispatch`, `dispatch-sync`, `subscribe`, `app-schemas`, `sub-cache`) is **frame-targeted** — the tool must resolve a single frame before the call. In a single-frame application the resolution is trivial: every call lands in the lone registered frame. In a multi-frame application the tool needs a deterministic "operating frame" rule so successive calls don't fan out across different frames by accident, and so a user gesture like "show me app-db" has one unambiguous answer.

Pair-shaped tools (re-frame-pair, re-frame2-pair, [re-frame-pair-improver](https://github.com/day8/re-frame-pair-improver), Xray, Story, any future companion that drives a multi-frame app) MUST implement the **hybrid-resolution** contract below. The contract is normative for pair-shaped tools — applications themselves continue to use the frame-routing rules of [002 §Routing](002-Frames.md#routing-the-dispatch-envelope); this section pins how a *tool sitting outside* the application picks which frame its read or write targets.

**Resolution order.** Every frame-targeted call resolves the operating frame by walking the following four tiers in order; the first tier that yields a frame-id wins:

| Tier | Source | When it fires |
|---|---|---|
| 1 | **Explicit per-call override** | The caller passes a frame-id with the op (e.g. `(rf/app-db-value :stories)`, `(subs-sample [:cart/total] :stories)`, `{:frame :stories}` on dispatch opts). |
| 2 | **Session-pinned selection** | The tool's session has called `select-frame!` (or its equivalent) since the last reset; the pinned id is the resolved frame. |
| 3 | **Sole-registered app frame** | After excluding `:rf/*` reserved **tool frames** (see below), exactly one **app frame** remains registered. That frame is the resolved frame, regardless of whether it is `:rf/default` or some other id. |
| 4 | **Nil** (ambiguous) | Two or more **app frames** are registered, the session has not pinned a selection, and the caller did not pass an override. The resolver yields nil; the op routes via the §Ambiguity surface below. |

**Single-app applications never reach tier 4.** A re-frame2 application with only `:rf/default` registered always resolves at tier 3; the pair tool's UX is identical to single-frame re-frame. The contract is structurally backwards-compatible — a single-app consumer sees no ambiguity prompt.

**Reserved tool frames are excluded from the ambiguity count.** A pairing session almost always runs against an app that *also* carries a framework-reserved `:rf/*` **tool frame** — Xray's `:rf/xray` inspector frame ([009 §Per-frame trace rings](009-Instrumentation.md#per-frame-trace-rings-event-keyed-dev-only) registers it with `:rf.trace/frame-no-emit? true`), a stories build, an SSR slot. These frames live under the framework-reserved `:rf/*` root ([Conventions.md §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)); they are devtool surfaces the *tooling* mounted, not the application the operator is pairing against. The resolver is therefore **reserved-frame-aware**: tiers 3 and 4 count only **app frames** — `(rf/frame-ids)` with the `:rf/*` tool frames removed. A single-app session that *also* carries an `:rf/xray` frame (the common Xray-instrumented case) has exactly one app frame and resolves at tier 3 — it does **not** pay a tier-4 `:ambiguous-frame` tax for a choice that doesn't exist. A two-plus-app-frame session stays genuinely ambiguous at tier 4 regardless of any tool frames present.

The exclusion keys off the **reserved-namespace rule** (a frame-id whose namespace is `rf`), never a hardcoded `:rf/xray`, so it holds for every `:rf/*` tool frame any project mounts. The **sole carve-out is `:rf/default`**: under [EP-0002](../docs/EP/EP-0002-frame-target-resolution.md) `:rf/default` is an **ordinary app-frame id** (no framework privilege, not auto-created), and an app that registers it does so as its application frame, not a tool surface — so although it shares the `:rf/*` root, it is never treated as a tool frame and *is* counted as an app frame at tiers 3 and 4. A `frames/list` (or `get-operating-frame`) read surfaces both `:frames` (all registered) and `:app-frames` (the reserved-frame-aware view) so a tool UI can show the distinction.

**`:rf/default` is not a special-case fallback.** A common naïve implementation would fall back to `:rf/default` at tier 4. The hybrid contract **rejects** that fallback: `:rf/default` is not auto-registered, and even when an app explicitly registers it, it is one app frame among possibly many — silently landing reads or writes there masks ambiguity rather than surfacing it. Tier 3 picks `:rf/default` only when it is the **unique** app frame, by the same unique-resolution rule that applies to any app-frame id. (This tier-3 *unique resolution* is the operator-present discovery layer, not the strict embedded core; unique resolution is not synthesis.)

**The public address is the frame ([EP-0023](../docs/EP/EP-0023-image-loaded-frames.md) §Public API).** EP-0023 presents the architecture as `image → frame → event stream`: the **frame id is the whole public address**. There is one process-local frame-id space — a frame id is unique among live registered frames — so the operating-frame ladder above resolves on the frame id alone, with **no realm dimension and no public realm pin**. The earlier EP-0013 `(realm, frame)` two-part address — and the runtime realm container it keyed on — was removed in full ([EP-0024](../docs/EP/EP-0024-unified-frame-identity-and-lifecycle.md)); there is no realm to enumerate or pin, and a tool's operating context is frame-only.

**Session-pin lifecycle.** A `select-frame!` call binds the operating frame for the session and persists across subsequent calls (the "implicit-until-reset" half of the hybrid posture). The selection is cleared by either a `reset-operating-frame!` (or equivalent) call, a runtime reload (the session sentinel changes, per [§How AI tools attach](#how-ai-tools-attach)), or destroying the pinned frame (the next resolution falls through to tier 3 or 4). Pair tools that surface a "current operating frame" indicator in their UI read the session pin directly; tools that want to show the *resolved* frame call the resolver and display its result (or "ambiguous" when nil).

**Frame destroyed mid-session.** When the session-pinned frame is destroyed (per [002 §Destroy](002-Frames.md#destroy)) the pin remains set but resolution at tier 2 yields a frame-id that no longer names a registered frame. Subsequent calls hit the destroyed-frame surface contract of [§Surface behaviour against destroyed frames](#surface-behaviour-against-destroyed-frames) — read-shaped surfaces return empty shapes, mutating-shaped surfaces emit `:rf.error/no-such-handler` (kind `:frame`). The pair tool SHOULD surface this state distinctly from the tier-4 ambiguity case so the user knows to call `reset-operating-frame!` or `select-frame!` to recover.

### Ambiguity surface — tier-4 behaviour

When resolution yields nil, the pair tool refuses the op rather than guessing. The refusal shape is **asymmetric by intent** — read-shaped and mutating-shaped ops both refuse, but pair tools may relax the read-side refusal for one-shot reads against an explicit override (tier 1), since the override IS the disambiguation.

| Op class | Examples | Behaviour at tier 4 |
|---|---|---|
| **Mutating** (writes that drive a pipeline run or replace a frame partition) | `pair-dispatch!`, `pair-dispatch-sync!`, `replace-frame-state!`, `restore-epoch!` | **Refuse**. Return `{:ok? false :reason :ambiguous-frame :hint <message>}` (or raise `(ex-info "ambiguous frame" {:reason :ambiguous-frame})` for callers that want exceptions). The op MUST NOT silently default to `:rf/default` — a write that lands in the wrong frame is unrecoverable without `restore-epoch!`, and the cascade may have already fired effects. |
| **Reading** (snapshot reads, sub samples, epoch reads, sub-cache reads, per-frame trace-ring reads) | `app-db-value`, `subs-sample`, `epoch-history`, `sub-cache`, `app-schemas`, `trace-buffer`, `clear-trace-buffer!` | **Refuse**. Same shape as mutating refusal — return `{:ok? false :reason :ambiguous-frame :hint <message>}`. A silent default to `:rf/default` would read from the wrong frame, and a multi-frame user is unlikely to want the default frame's data. The `:hint` SHOULD direct the user at `select-frame!` or the explicit-override path. Per-frame trace-ring reads (`(rf/trace-buffer frame-id)` for event bundles, `(rf/trace-buffer frame-id {:flat true})` for raw events) take the frame-id as a required first argument — cross-frame consumers iterate `(rf/frame-ids)` and merge by `:dispatch-id` per [009 §Per-frame trace rings](009-Instrumentation.md#per-frame-trace-rings-event-keyed-dev-only). |
| **Registry-wide** (no frame-id needed) | `(rf/frame-ids)`, `(rf/registrations kind)`, `(rf/machines)`, `(rf/handler-meta kind id)` | **Proceed**. These ops query global registry / global registrar state and have no operating-frame concept; they bypass the resolver entirely. |

The **uniform refusal shape across reads and writes** is the resolution committed here (the shipped impl in `re-frame2-pair.runtime`, landed in [PR #190](https://github.com/day8/re-frame2/pull/190), already follows this stricter posture). A tool MAY relax read-side refusal for ops that take an explicit override at the call site — tier 1 *is* the disambiguation, so a `(app-db-value :stories)` call with the explicit `frame-id` argument MUST NOT refuse even when no session pin is set. The refusal applies to the *zero-arg-defaults-to-operating-frame* form, where the resolver would have to invent a frame.

**Two tool-side refusal stamps — the ladder's lower two rungs.** The tool-side resolution has its own place on the one ordered ladder reconciled in [002 §Two layers — strict core, tiered discovery](002-Frames.md#two-layers--strict-core-tiered-discovery): **absent → ambiguous → unselected**. The core's *absent* rung is `:rf.error/no-frame-context` (a frame-scoped op raised with no frame established); the tool layer adds the two below it. **`:ambiguous-frame`** (the *plural* rung) is the stamp above — tier-4 resolution found **more than one** candidate app frame and refuses rather than guess. **`:rf.tool/no-frame-selected`** (the *unselected* rung) is the distinct stamp a tool surface returns when **no target is pinned and none can be inferred** — there is no candidate to be ambiguous between, the operator simply has not selected one. A tool MAY return `:ambiguous-frame` uniformly for both the plural and unselected cases (the shipped `re-frame2-pair.runtime` does, since both refuse identically), but the `:rf.tool/no-frame-selected` stamp lets a richer surface tell the operator *"pick a frame"* apart from *"disambiguate between these frames"* — a better `:hint`. Same stamp shape, one ladder, different rung. The full ladder reconciliation is owned by [002 §Two layers](002-Frames.md#two-layers--strict-core-tiered-discovery).

### Tool-surface obligations

Pair-shaped tools that implement the operating-frame contract MUST expose three operations on their tool surface (names are illustrative; the shape is what's normative):

- **Set the operating frame.** A call that pins a frame-id for the session (`select-frame!`, `set-operating-frame!`, etc.). The op SHOULD validate that the frame-id names a currently-registered frame at call time — passing an unknown frame returns `{:ok? false :reason :no-such-frame}`. Pinning a frame that is later destroyed surfaces via the destroyed-frame contract above, not at pin time. The frame id is the whole public address ([EP-0023](../docs/EP/EP-0023-image-loaded-frames.md) §Public API) — there is no public realm pin.
- **Reset the operating frame.** A call that clears the session pin (`reset-operating-frame!`, `unselect-frame!`, etc.). After reset, subsequent ops resolve at tier 3 or 4 again.
- **Inspect the operating frame.** A read returning the **resolved** operating frame (or nil when ambiguous) plus the **pinned** selection (when distinct from resolved) plus the **all-registered** frame list (so callers can pick a target). The reference impl shape:

```clojure
{:ok?             true
 :frames          [<frame-id> ...]   ;; (rf/frame-ids) — all registered
 :app-frames      [<frame-id> ...]   ;; (rf/frame-ids) minus :rf/* tool frames
 :selected        <frame-id|nil>     ;; tier-2 session frame pin
 :operating       <frame-id|nil>}    ;; result of full resolution (nil = ambiguous)
```

The shape lets a tool UI render "you have pinned X" (the selection), "writes will go to X" (the resolved frame), and "these are the app frames vs the tool frames present" (`:app-frames` ⊆ `:frames`). When `:app-frames` holds exactly one id while `:frames` holds more, the session is single-app-plus-tool-frame and `:operating` auto-resolved to that lone app frame (no `select-frame!` was needed). The selection and resolved frame diverge on a tier-1 override on the current call, or a sole-app tier-3 fallthrough the user hasn't explicitly chosen. The operating context is **frame-only** — there is no realm dimension in the inspect shape (the EP-0013 `(realm, frame)` address and its container were removed in full by [EP-0024](../docs/EP/EP-0024-unified-frame-identity-and-lifecycle.md)).

**MCP / RPC surfacing.** Tools that expose pair surfaces over MCP / RPC SHOULD enumerate the three ops in their tool catalogue under stable names (typically `set-operating-frame`, `reset-operating-frame`, `get-operating-frame`). The runtime contract does not pin the wire names — only the semantics — but cross-tool consistency lets a user trained on re-frame2-pair carry the mental model to re-frame-pair-improver, Xray, or Story without relearning the resolver.

### Worked example — multi-frame pair session

```clojure
;; Initial state: app has two frames :rf/default and :stories.
;; Resolution at tier 4 — refuses without a hint.
(subs-sample [:cart/total])
;; => {:ok? false :reason :ambiguous-frame
;;     :hint "Multi-frame session with no selected frame — pass `frame-id` or call `select-frame!` first."}

;; One-shot read with an explicit override (tier 1) — proceeds.
(subs-sample [:cart/total] :stories)
;; => 42

;; Pin :stories for the session (tier 2 from here on).
(select-frame! :stories)
;; => {:ok? true :frame :stories}

;; Subsequent calls resolve at tier 2.
(subs-sample [:cart/total])              ;; => 42
(pair-dispatch-sync! [:cart/clear])      ;; => {:ok? true :epoch-id ... :frame :stories}

;; A different frame for one call — tier 1 wins over the session pin.
(app-db-value :rf/default)               ;; reads default explicitly

;; Clear the pin; back to tier-4 refusal.
(reset-operating-frame!)
(subs-sample [:cart/total])
;; => {:ok? false :reason :ambiguous-frame ...}
```

The example exercises every tier — explicit override (tier 1), session pin (tier 2 binding and re-use, plus tier-1 supersession), and tier-4 refusal both before and after reset. Single-app apps never reach the refusal path.

```clojure
;; Reserved-frame-aware resolution: an Xray-instrumented single-app
;; session. Two frames are registered — the app's :rf/default and Xray's
;; :rf/xray tool frame — but only ONE is an app frame, so resolution is
;; unambiguous WITHOUT a select-frame!.
(frames-list)
;; => {:ok? true
;;     :frames     [:rf/default :rf/xray]   ;; both registered
;;     :app-frames [:rf/default]            ;; :rf/xray excluded (a :rf/* tool frame)
;;     :selected   nil
;;     :operating  :rf/default}             ;; tier-3 auto-resolved the sole app frame

;; The first mutating op proceeds against :rf/default — no :ambiguous-frame tax.
(pair-dispatch-sync! [:counter/inc])
;; => {:ok? true :epoch-id ... :frame :rf/default}

;; Targeting the tool frame still works via an explicit override (tier 1).
(app-db-value :rf/xray)                    ;; reads the Xray frame explicitly
```

## How AI tools attach

The runtime contract above is **complete for the listed capabilities.** A pair-shaped tool — re-frame-pair, a Claude integration, a custom debug panel, a story tool, a future pair-improver — attaches to a running re-frame2 application using only the framework primitives listed below. **No re-frame-10x dependency is required**, and none should be assumed. Mutating writes (state injection, hot-swap, override, configure) are commited explicitly in the table; the full set is closed at v1 and additional mutating surfaces require a Spec-ulation increment.

The full attachment surface, from the tool's point of view:

| Need | Surface | Spec |
|---|---|---|
| Receive live trace events | `(rf/register-listener! :trace :my-tool callback)` | [009 §The listener API](009-Instrumentation.md#the-listener-api) |
| Receive per-event assembled epoch records | `(rf/register-listener! :epoch :my-tool callback)` | [009 §The listener API](009-Instrumentation.md#the-listener-api) |
| Read recent trace history (runs that already fired in a frame) | `(rf/trace-buffer frame-id)` returns event bundles by default; `(rf/trace-buffer frame-id {:flat true})` returns raw trace events; cross-frame consumers merge by `:dispatch-id` across rings | [009 §Per-frame trace rings](009-Instrumentation.md#per-frame-trace-rings-event-keyed-dev-only) |
| Read live stream of frameless trace events (registration, REPL, lifecycle outside any pipeline run) | `(rf/register-listener! :trace ...)` — frameless emits stream live to listeners only; they are not retained in any ring (per the B3 ruling) | [009 §Frameless trace events](009-Instrumentation.md#frameless-trace-events--live-stream-only-no-ring-storage) |
| Read epoch history per frame | `(rf/epoch-history frame-id)` | [§Time-travel](#time-travel-epoch-snapshots-and-undo) |
| Restore an epoch | `(rf/restore-epoch! frame-id epoch-id)` | [§Time-travel](#time-travel-epoch-snapshots-and-undo) |
| Inject an `app-db` value (state injection / story / repro) | `(rf/replace-frame-state! frame-id {:rf.db/app app-db})` (or supply `:rf.db/runtime` too to install both partitions) | [§Pair-tool writes](#pair-tool-writes--state-injection) |
| Configure history depth | `(rf/configure! {:epoch-history {:depth N}})` and `(rf/configure! {:trace-buffer {:events-retained N}})` (process-default); `:rf.trace/events-retained` on `reg-frame` (per-frame override) | [API.md](API.md), [009 §Per-frame trace rings](009-Instrumentation.md#per-frame-trace-rings-event-keyed-dev-only) |
| Inspect registered app-db schemas | `(rf/app-schemas frame-id)` | [010 §Schemas as a tooling and agent surface](010-Schemas.md#schemas-as-a-tooling-and-agent-surface) |
| Tag dispatches by actor (e.g. tool vs app) | `:origin` opt on `(rf/dispatch event opts)` | [002 §Dispatch origin tagging](002-Frames.md#dispatch-origin-tagging) |
| Correlate a drain (a dispatch chain) | `:rf.trace/dispatch-id` + `:rf.trace/parent-dispatch-id` on `:rf.event/dispatched` traces | [009 §Dispatch correlation](009-Instrumentation.md#dispatch-correlation-rftracedispatch-id--rftraceparent-dispatch-id) |
| Enumerate frames | `(rf/frame-ids)`, `(rf/frame-meta id)` — the public frame-id space is the whole public address ([EP-0023](../docs/EP/EP-0023-image-loaded-frames.md) §Public API) | [002 §Public registrar query API](002-Frames.md#the-public-registrar-query-api) |
| Read a frame's app-db | `(rf/app-db-value frame-id)` / `(get-in (rf/app-db-value frame-id) path)` | [002 §Public registrar query API](002-Frames.md#the-public-registrar-query-api) |
| Inspect the registry | `(rf/registrations kind)`, `(rf/handler-meta kind id)` | [001](001-Registration.md), [002](002-Frames.md) |
| Enumerate machines | `(rf/machines)`, `(rf/machine-meta id)` | [005 §Querying machines](005-StateMachines.md#querying-machines) |
| Inspect the sub-cache (CLJS-only) | `(rf/sub-cache frame-id)` | [002 §Public registrar query API](002-Frames.md#the-public-registrar-query-api) |
| Source coords for any registration | `:ns`/`:line`/`:column`/`:file` keys on `(handler-meta ...)` return; shape `:rf/source-coord-meta` per [Spec-Schemas](Spec-Schemas.md#rfsource-coord-meta) | [001 §Source-coordinate capture](001-Registration.md#source-coordinate-capture-cljs-reference) |
| Dispatch | `(rf/dispatch event opts)` / `(rf/dispatch-sync event opts)` | [002 §Routing](002-Frames.md#routing-the-dispatch-envelope) |
| Stub fx for an experiment | `:fx-overrides {:http stub-id}` on `dispatch` opts | [002 §Per-frame and per-call overrides](002-Frames.md#per-frame-and-per-call-overrides) |
| Hot-swap a handler | Re-call `(rf/reg-event id ...)`; `:rf.registry/handler-replaced` trace fires | [001 §Hot-reload semantics](001-Registration.md#hot-reload-semantics) |
| REPL eval against the runtime | The host's REPL (nREPL+CIDER for CLJS); private namespaces are off-contract | [§REPL-eval](#repl-eval) |

> **Platform-availability note.** Rows tagged "(CLJS-only)" — `(rf/sub-cache frame-id)` is the load-bearing example — are CLJS-host-only surfaces; JVM hosts (SSR, headless tests, conformance runners) ship no equivalent. Pair tools driving JVM-side test runs MUST gate the call (e.g. `(when (cljs-host?) (rf/sub-cache frame-id))`) — JVM-host return shape is not yet specified and consumers should not assume nil-vs-throw across hosts. Surfaces NOT tagged "(CLJS-only)" are portable.

The consumption pattern is therefore:

> **A pair-shaped tool registers as a trace listener (and/or as an epoch listener for assembled per-run records), reads recent history from the per-frame trace rings (event-bundle shape by default, `:flat` opt-in for raw events; cross-frame consumers merge by `:dispatch-id`), queries the registrar for shape (the source of truth for "what's registered right now" — registry queries replace any need to scan rings for frameless events per the B3+B4 ruling), walks the epoch history for time-travel, and dispatches into frames to drive experiments. That's the entire surface.**

Two listener shapes coexist: `register-listener!` is the **raw** stream — every event the runtime emits, fine-grained — used by tools that need per-emit detail (custom recorders, error-monitor forwarders, timing aggregators). `register-epoch-listener!` is the **assembled** stream — one fully-shaped `:rf/epoch-record` per dequeued event (per [002 §Drain versus event](002-Frames.md#drain-versus-event--the-epoch-unit)), with the structured `:sub-runs` / `:renders` / `:effects` projections already computed — used by tools that route diagnostics off "what just happened in this run" rather than reconstructing it from the raw trace each time. Pair-shaped tools typically prefer the assembled stream for routing and reach for the raw stream only when they need detail the projection drops.

This is **dev-only** end-to-end — every primitive listed above elides in production builds (per [009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code)). Pair-shaped tools do not ship in production binaries.

### Subscribing to a slice of the trace stream

`register-listener!` callbacks see *every* trace event. Tools that only care about a single subsystem filter inside the callback by `:op-type` — the universal discriminator (per [009 §`:op-type` vocabulary](009-Instrumentation.md#op-type-vocabulary)). The pattern is one-key dispatch on the event:

```clojure
;; A tool (Xray's flow panel, a pair-tool flow inspector,
;; a custom dashboard) subscribes to JUST the flow trace stream.
;; Per Spec 009 §Flow trace events, every flow lifecycle event carries
;; :op-type :flow with the per-event identity in :operation
;; (:rf.flow/registered, :rf.flow/computed, :rf.flow/skip,
;; :rf.flow/cleared, :rf.flow/failed).

(rf/register-listener!
  :trace
  :my-tool/flow-panel
  (fn [ev]
    (when (= :flow (:op-type ev))
      (case (:operation ev)
        :rf.flow/registered  (track-flow-registration! ev)
        :rf.flow/computed    (record-flow-computation! ev)
        :rf.flow/skip        (note-skip! ev)               ;; (per  value-equal recompute suppression)
        :rf.flow/cleared     (drop-flow-state! ev)
        :rf.flow/failed      (surface-flow-error! ev)
        nil))))
```

The same pattern works for any subsystem with a dedicated op-type — `:rf.machine` for state-machine activity, `:rf.event` for the dispatch / drain stream, `:rf.sub` for subscription work, `:rf.fx` for effect handlers. New op-types are additive (per [009 §Open shape; new fields are additive](009-Instrumentation.md#open-shape-new-fields-are-additive)); tools ignore op-types they don't understand.

For per-run structured projections (sub-cache hit/miss, render attribution, effect outcome), tools route off `register-epoch-listener!`'s assembled `:rf/epoch-record` instead — the §[Time-travel](#time-travel-epoch-snapshots-and-undo) projection slots already pre-fold the per-run trace into the `:sub-runs` / `:renders` / `:effects` shape. The raw-stream filter pattern above is the right shape for fine-grained per-event consumption.

### Subscribing to assembled epoch records

`register-epoch-listener!` callbacks fire **once per dequeued event** (one per epoch, per [002 §Drain versus event](002-Frames.md#drain-versus-event--the-epoch-unit)), with the run's `:sub-runs` / `:renders` / `:effects` projections already computed. A drain that settles several events back-to-back therefore fires the callback once per settled event, in dequeue order. Pair-shaped tools, post-mortem dashboards, and "what just happened?" probes typically consume this shape rather than re-folding the raw trace stream:

```clojure
;; A pair-tool dashboard routing diagnostics off the assembled per-run record.
;; - One callback per dequeued event / epoch (NOT per drain, NOT per emitted trace event).
;; - The record is fully shaped: :db-before, :db-after, :sub-runs, :renders, :effects.
;; - The record has already been appended to (rf/epoch-history (:frame ev)).

(rf/register-listener! :epoch
  :my-tool/dashboard
  (fn [{:keys [frame event-id epoch-id sub-runs renders effects] :as record}]
    ;; Cache-hit-vs-rerun: every entry in :sub-runs is a recompute;
    ;; cache-hit subs are absent. Counting :sub-runs answers
    ;; "how many subs moved this run?"
    (record-recomputes! frame event-id (count sub-runs))

    ;; Render attribution: :renders[*].:render-key is [<view-id> <instance-token>].
    ;; Aggregate by first slot to count "view X re-rendered N times this run."
    (doseq [{:keys [render-key elapsed-ms]} renders]
      (record-render! frame (first render-key) elapsed-ms))

    ;; Fx outcome: every dispatched fx surfaces exactly one :effects entry.
    ;; :outcome ∈ {:ok :error :skipped-on-platform}; route :error entries to UI.
    (doseq [{:keys [fx-id outcome error-trace]} effects]
      (when (= :error outcome)
        (surface-fx-error! frame epoch-id fx-id error-trace)))

    ;; The epoch is already in (rf/epoch-history frame); no need to re-query
    ;; unless the dashboard wants the full vector for context.
    nil))
```

Edge-case behaviour the example does not exercise but consumers should know about:

- **Listener exceptions are caught.** A throw inside the callback does not propagate to the framework or other listeners (per [009 §register-epoch-listener! invocation rules](009-Instrumentation.md#register-epoch-listener--assembled-epoch-listener)). The framework does **not** auto-evict the throwing listener — repeated throws keep the registration in place; eviction is the consumer's call.
- **Re-entrant dispatch from a callback.** A callback that calls `(rf/dispatch …)` enqueues the new event; the new dispatch's drain begins on stack-unwind from the current callback fan-out, not before. Other registered epoch listeners still receive the *current* record before the re-entrant dispatch begins.
- **`(rf/configure! {:epoch-history {:depth 0}})` and listeners.** Setting depth to 0 disables the per-frame ring buffer (so `(rf/epoch-history frame-id)` returns `[]`) but does **not** stop epoch listeners from firing — `register-epoch-listener!` callbacks continue to receive the assembled record once per dequeued event. Tools that need the assembled stream without retaining history should set depth `0` and consume via `register-epoch-listener!` only.
- **Frame-destroyed mid-observation.** Tool-Pair surface behaviour against destroyed frames (epoch-history reads, in-flight epoch-cb deliveries, restore against a now-destroyed frame, listener silencing) is closed in [§Surface behaviour against destroyed frames](#surface-behaviour-against-destroyed-frames). Read-shaped surfaces return empty shapes; mutating-shaped surfaces raise `:rf.error/no-such-handler` (kind `:frame`); a previously-firing callback whose observed frame is destroyed receives a one-shot `:rf.epoch.cb/silenced-on-frame-destroy` trace.

### Reading the per-frame trace ring — event bundles + `:flat` opt-in

The per-frame trace ring is the in-process companion to `epoch-history` — same per-frame model, same event-keyed retention, but the *trace detail* of each run rather than the assembled-projection. Tools route off the ring when they need the raw `:rf.sub/run` / `:rf.sub/skip` / `:rf.fx/handled` / `:rf.machine/*` event stream of recent runs (per-event timing, full sub-cascade DAG, fine-grained fx ordering) without rebuilding it from a `register-listener!` archive.

The read surface is **per-frame** and returns **event bundles by default**:

```clojure
;; Default — one entry per retained run, projection-shape per [009 §Event-bundle projection]
(rf/trace-buffer :step-deck)
;; → [{:dispatch-id 17
;;     :trace-events [...]
;;     :event [:user/click ...]
;;     :handler {:operation :rf.event/run-start ...}
;;     :fx {:operation :rf.fx/do-fx ...}
;;     :effects [...]
;;     :subs [...]
;;     :renders [...]
;;     :other [...]}
;;    {:dispatch-id 18 :trace-events [...] :event [...] ...}
;;    ...]

;; Opt-in — flatten back to raw trace events (escape hatch for existing flat-stream code)
(rf/trace-buffer :step-deck {:flat true})
;; → [{:operation :rf.event/dispatched :tags {:rf.trace/dispatch-id 17 ...} ...}
;;    {:operation :rf.event/run-start  :tags {:rf.trace/dispatch-id 17 ...} ...}
;;    {:operation :rf.sub/skip         :tags {:rf.trace/dispatch-id 17 ...} ...}
;;    ...]
```

The default matches the storage unit (one event / run = one slot); the `:flat` form reads the ring's per-run slots and flattens them at read time. Filters compose AND-wise; see [Spec 009 §Filter vocabulary](009-Instrumentation.md#filter-vocabulary).

#### Cross-frame run reconstruction — merge by `:dispatch-id`

A tool watching multiple frames (a pair-MCP session observing both `:rf/xray` and the inspected app frame; a story-MCP session walking a multi-frame variant; an off-box dashboard merging trace from N frames) reconstructs a unified timeline by reading each frame's ring and merging entries by `:dispatch-id`. Each frame retains the traces that EXECUTED IN IT — the framework does not maintain a process-global index — so the merge is the consumer's job, and it is cheap because each ring already keys by `:dispatch-id`:

```clojure
(defn watch-all-frames []
  (->> (rf/frame-ids)
       (mapcat (fn [fid] (map #(assoc % :frame fid) (rf/trace-buffer fid))))
       (sort-by :dispatch-id)))
;; → vector of {:dispatch-id N :frame <kw> :trace-events [...] ...} entries,
;;   suitable for rendering as a single timeline; entries with the same
;;   :dispatch-id are the same run observed from each frame's ring.
```

In practice, each run lives in exactly ONE frame (re-frame2 does not route a single dispatch across multiple frames per [Spec 002](002-Frames.md#routing-the-dispatch-envelope)), so the multi-frame view is interleaved rather than overlapping — `dispatch-id` ordering renders the correct turn-by-turn timeline.

#### `watch-epochs` / `trace-window` consumer shape (MCP layer)

The MCP-server surfaces that stream the per-frame ring to off-box agents (`re-frame2-pair-mcp`'s `trace-window` / `watch-epochs` / `subscribe` per [`tools/re-frame2-pair-mcp/spec/003-Tool-Catalogue.md`](../tools/re-frame2-pair-mcp/spec/003-Tool-Catalogue.md)) MUST deliver **complete event bundles per stream tick** — the storage unit on the wire. Consumers receive one `{:dispatch-id N :trace-events [...] :event :handler :fx :effects :subs :renders :other}` per tick rather than per-event chunks they would have to re-fold. The in-process zero-arg `(rf/trace-buffer frame-id)` already returns this shape; off-box delivery mirrors it.

The event-bundle wire shape DIFFERS from the in-process `:flat true` opt-in by intent: in-process callers may need raw events for fine-grained per-emit logic; off-box agents need atomic run context per stream tick to stay within token budgets and to reason about cause→effect at a granularity that matches `:dispatch-id`. The bundle is the better default for LLM-shaped consumers; the `:flat` form is the escape hatch for in-process callers that already grouped raw streams themselves.

Per cursor-pagination and per-session cache conventions in [§Wire-protocol mechanisms](#wire-protocol-mechanisms-mcp-tool-layer-not-framework) compose unchanged: a `:epoch-id`-keyed cursor identifies the next epoch slot in `epoch-history`; a `:rf.mcp/cursor-stale` marker fires when the cursor's `:epoch-id` has aged out of `epoch-history`; per-session response cache keys on app-db root identity, so the event-bundle shape doesn't change its hit semantics.

**Authoritative-ring read — no parallel capture buffer (the read-truth-each-call invariant).** The `trace-window` / `watch-epochs` surfaces **MUST** read the runtime's authoritative per-frame ring — `(rf/epoch-history frame-id)` (and `epochs-since` over it), the **same source** an arbitrary `eval-cljs` reaches directly — **NOT** a parallel session-side capture buffer that fills only while a listener is attached. A session-side buffer that mirrors the ring is a drift-prone proxy: it returns **empty while the ring holds epochs** (the buffer was attached after the epochs landed, or a reconnect reset it), producing a silent WRONG read an agent acts on. The pair session is a **thin, runtime-direct reader** — it reads truth on each call and carries liveness (see [§Freshness / liveness token](#freshness--liveness-token-mcp-layer) below) rather than maintaining a stateful copy. A tool MAY keep a *derived scalar* (e.g. an O(1) `(hash app-db)` cache the precheck consults) but MUST NOT keep a record copy that a read consults in place of the ring. When a time-windowed read (`trace-window`'s `:ms`) surfaces zero epochs while the ring is non-empty, the tool **MUST** distinguish "genuinely empty" from "events exist but fell outside the window" with an advisory naming the ring depth — an empty result is never ambiguous between "nothing happened" and "the tool isn't capturing".

#### Frameless trace events — live channel only

Frameless trace events (registrations, REPL emits, lifecycle outside any pipeline run) **bypass every ring** and stream live to listeners only. Consumers that want frameless emits — registration-drift monitors, hot-reload diagnostics, REPL-eval tracers — subscribe to the live stream via `register-listener!` and filter by `:op-type` / `:operation`:

```clojure
(rf/register-listener!
  :trace
  :my-tool/registry-monitor
  (fn [ev]
    (when (and (= :rf.registry (:op-type ev))
               (nil? (get-in ev [:tags :rf.trace/dispatch-id])))
      (record-registration-event! ev))))
```

The framework runs **hot-reload dedup by shape** (B4 ruling) at the registrar before the trace fires — unchanged re-emits are suppressed, so the live stream a listener observes is already noise-filtered. Tools wanting "the current set of registered handlers" consult `(rf/registrations kind)` (per [001 §The query API](001-Registration.md#the-query-api)) rather than reconstructing it from live-stream observation. The registry is the source of truth; the live stream is the change-log.

Off-box wire delivery of frameless events MAY ride a separate stream channel from the event-bundle stream (since the two carry structurally different payloads — event bundles vs single trace events) and consumers MUST subscribe to it explicitly rather than assuming frameless events ride the event-bundle stream. The split is the framework's; downstream MCP tooling that ships `subscribe`-style notifications shapes the channel separation in its own surface (see [`tools/re-frame2-pair-mcp/spec/003-Tool-Catalogue.md`](../tools/re-frame2-pair-mcp/spec/003-Tool-Catalogue.md) for `re-frame2-pair-mcp`'s shape).

### Implications for downstream tools

- **re-frame-pair** (the upstream nREPL companion) consumes only the surfaces above. It depends on re-frame2; it does not depend on re-frame-10x.
- **Xray** (the structural successor to re-frame-10x; Maven coord `day8/re-frame2-xray`) is built as a renderer of the same surfaces — a registered trace listener, a consumer of `epoch-history`, a query consumer of the registrar, a UI on top. Xray and pair share the substrate; one does not depend on the other.
- **Custom debug panels, story tools (Spec 007), and pair-improver-style skills** consume the same surface. Multi-tool coexistence is the expected default — multiple `register-listener!` keys, multiple readers of the trace buffer, multiple consumers of the registrar. Listener ordering is not contract (per [009 §Listener ordering](009-Instrumentation.md#listener-ordering)).

The framework is **infrastructure-complete** for AI-tool consumption: data shapes, query APIs, retention policies, configuration knobs, production elision. Downstream tools own *presentation and orchestration*; they do not need to ship infrastructure that should live in the framework.

### The Xray renderer

Xray's host-integration model is the **true-inline layout host** — the app provides a left-side `[data-rf-xray-host]` column in its normal layout, and the Xray preload mounts the rendered shell into that host once the substrate adapter is ready. The host owns sizing and layout; Xray owns the rendered shell (so the host's flex/grid rules govern the panel's geometry, and the app stays visible/clickable to the right). The normative contract — layout-host selector, mount lifecycle, hot-reload posture, popout window, missing-host diagnostic, teardown semantics — lives in [`tools/xray/spec/011-Launch-Modes.md`](../tools/xray/spec/011-Launch-Modes.md) §Mount lifecycle and §Layout host contract.

Resizing the inline panel is a one-property CSS contract: the recommended host snippet reads `var(--rf-xray-inline-width, 560px)` for its `flex-basis`, so developers override the panel width by setting `--rf-xray-inline-width` anywhere up the cascade (`:root`, an ancestor, the host itself, or a user stylesheet). The same snippet publishes `--rf-xray-accent` (default `#7C5CFF`, Xray's brand violet) on `:root` so host stylesheets can read it to tint their own dev chrome without forking the hex. Both contracts are **JS-free** and **host-owned** — Xray does not read or set either property, the host's stylesheet is the single source of truth (per [`tools/xray/spec/011-Launch-Modes.md`](../tools/xray/spec/011-Launch-Modes.md) §Resizing the inline host and §Brand-accent CSS variable, `` + ``).

The shadow-cljs dev-build preload is the canonical wiring: `:preloads [day8.re-frame2-xray.preload]` runs the foundation's idempotent side-effects (handlers registration, trace + epoch collectors, browser API, keybinding, auto-open) all gated on `interop/debug-enabled?` so Closure DCE strips them from production bundles. The AI access path is `tools/re-frame2-pair-mcp/` — raw nREPL pair-programming companion; it consumes the same Tool-Pair instrumentation as Xray, and the two surfaces are independent.

### Wire-protocol mechanisms (MCP-tool layer, not framework)

A pair-shaped tool reaching an AI agent over MCP must shape the payload at the wire boundary — a runtime snapshot, an epoch record, or a trace slice is routinely far larger than the agent's per-call budget can absorb. The mechanisms that solve this (token-budget cap, path slicing, cursor pagination, lazy summary, structural dedup, size-elision wire markers, and — re-frame2-pair-mcp-specific — diff-encoded `:db-after` and streaming subscribe byte+event budgets) live **at the MCP-server layer**, not in this Spec. Tool-Pair.md commits to the framework surfaces (data shapes, query APIs, listener APIs); how an MCP tool packages those surfaces for an agent is downstream.

The cross-MCP catalogue is normative and shared across the re-frame2 MCP servers (re-frame2-pair-mcp, story-mcp). Canonical homes:

- [`spec/Cross-Cutting-Designs.md §3 Token budgets`](Cross-Cutting-Designs.md) — the cross-cutting design problem statement and the index of canonical homes below.
- [`tools/mcp-conformance/TOKEN-BUDGETS.md`](../tools/mcp-conformance/TOKEN-BUDGETS.md) — the cross-server contract: default cap (5,000 tokens), per-call `max-tokens` override slot, `{:rf.mcp/overflow ...}` reserved marker, agent-host retry contract, chained-budget rules when an agent attaches multiple servers in one session.
- [`tools/re-frame2-pair-mcp/spec/Principles.md §Tight token budget per response`](../tools/re-frame2-pair-mcp/spec/Principles.md) — re-frame2-pair-mcp's eight-mechanism expansion (cap → path slicing → per-tool budget → diff encoding → dedup → size elision → cursor pagination → streaming subscribe byte+event budget) in pipeline order.
- [`spec/Conventions.md §Reserved namespaces (framework-owned)`](Conventions.md#reserved-namespaces-framework-owned) — the framework-side reservation of the `:rf.mcp/*` and `:rf.size/*` keys that appear on the wire (`:rf.mcp/overflow`, `:rf.mcp/summary`, `:rf.mcp/dedup-table`, `:rf.mcp/diff-from`, `:rf.size/large-elided`).

The framework owns the *data*; the wire-protocol layer owns the *packaging*. Findings docs and downstream Specs reaching for the mechanism catalogue link to the MCP-server homes above, not back into this Spec.

#### MCP-side wire-marker vocabulary

The mechanisms above emit a small family of namespaced wire markers — replacement envelopes that pair-shaped tools wrap a payload in when a budget / dedup / cache rule fires. Every marker rides as a top-level map keyed on a `:rf.mcp/*` or `:rf.size/*` keyword (per [Conventions.md §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)); agent hosts pattern-match on the key to branch their UI / retry / drill-in flows. Each artefact's own Spec mentions a subset; the family in one place:

| Marker key | Mechanism | Emitted by | Shape (top-level keys) |
|---|---|---|---|
| `:rf.mcp/overflow` | Wire-cap | Post-eval cap step replaces an over-budget payload. | `:limit :reached`, `:token-count`, `:cap-tokens`, `:tool`, `:hint`. |
| `:rf.mcp/summary` | Lazy-summary mode | `snapshot` replaces each `:summary`-mode rich slice. | `:type`, `:keys`, `:count`, `:bytes`. |
| `:rf.mcp/dedup-table` | Structural dedup | Every epoch / events vector emitter wraps post-encoded payload. | `:de-dupe.cache/cache-0`, `:de-dupe.cache/cache-1`, … (flat). |
| `:rf.mcp/diff-from` | Diff-encoded `:db-after` | Each epoch's `:db-after` is replaced with an intra-record diff projected into path-headed cluster sections. | `:rf.mcp/diff-from`, `:sections` (vector of `{:section-path :section-kind :patches}`). |
| `:rf.mcp/cache-hit` | Per-session response cache — keyed on app-db root identity; cache poisoning by mismatched session is structurally impossible (see [§Per-session app-db cache — cache-poisoning posture](#per-session-app-db-cache--cache-poisoning-posture)) | Wire-boundary cache replaces a byte-identical re-emit. | `:hash`, `:unchanged-since`, `:tool`, `:via` (`:result-hash` / `:precheck`), `:hint`. |
| `:rf.size/large-elided` | Size-elision walker | `rf/elide-wire-value` substitutes over-threshold leaves. | `:path`, `:bytes`, `:type`, `:reason`, `:hint`, `:handle` (`[:rf.elision/at <path>]`). |
| `:rf.mcp/cursor-stale` | Cursor pagination | `trace-window` / `watch-epochs` refuse a cursor whose `:epoch-id` aged out of the ring. | `:reason :rf.mcp/cursor-stale`, `:tool`, `:requested-id`, `:head-id`. |
| `:rf.mcp/result` | Wire fidelity / typed result codec (see [§Wire fidelity](#wire-fidelity-typed-result-envelope-dispatch-consequence-arg-echovalidate) below) | The runtime-side classifier wrap (`eval-cljs`, `handler-meta`) tags an eval result so a genuine `nil`, a thrown eval-error, and an unserializable value are DISTINCT outcomes. | `:rf.mcp/result` (one of `:value` / `:nil` / `:eval-error` / `:unserializable`); shape-by-tag: `:value <v>` · (no extra) · `:reason :ex :message :ex-data` · `:type :preview`. |
| `:freshness` | Freshness / liveness token (see [§Freshness / liveness token](#freshness--liveness-token-mcp-layer) below) | `discover-app` (and any read op that opts in) attaches it so a stale / disconnected / stale-BUILD runtime is obvious up front. | `:runtime-instance-id`, `:runtime-loaded-at`, `:read-at` (browser half); `:compile-cycle`, `:build-flushed-at`, `:runtime-count`, `:heartbeat-age-ms`, `:build-id` (JVM half); `:liveness` (one of `:fresh` / `:stale-build` / `:no-runtime` / `:unknown`); `:hint` when non-fresh. |
| `:rf/redacted` | Privacy walker (registration-owned `:sensitive` classification + the router's internal redaction plumbing, spec/009 §Privacy) | The framework-side redact walker replaces named keys at emit time. | Scalar sentinel (no map shape). |

Agents that learn the family see each new slot as one more case in the same pattern-match — the namespaced first key is the discriminator. New mechanisms add to the table; existing markers are stable (additive: new optional keys, never removed / renamed).

The size markers above (`:rf.mcp/overflow`, `:rf.mcp/summary`, `:rf.mcp/dedup-table`, `:rf.mcp/diff-from`, `:rf.size/large-elided`) are **wire-SIZE** mechanisms — they shrink an over-budget payload. The `:rf.mcp/result` marker is a **wire-FIDELITY** mechanism — it *types* the outcome of an evaluation so distinct results stop collapsing to a bare `null`. The two layers compose: the fidelity codec tags the outcome, and the size walker still runs over the `:value` payload it carries — fidelity never bypasses the budget machinery (see [§Wire fidelity](#wire-fidelity-typed-result-envelope-dispatch-consequence-arg-echovalidate)).

<a name="wire-fidelity-typed-result-envelope-dispatch-consequence-arg-echovalidate"></a>
#### Wire fidelity — typed result envelope, dispatch-consequence, arg echo/validate

The size mechanisms above keep a payload *small*; three further mechanisms keep the wire *honest* — they make the boundary speak re-frame2 semantics so an agent reasons about what actually happened instead of inferring it from a lossy `null`. All three are the [§No silent swallow](Conventions.md#no-silent-swallow--recognised-input-must-signal) principle applied to the MCP wire.

**1. Typed, total result envelope (the `:rf.mcp/result` marker).** A pair-shaped tool that evaluates an expression against the runtime (`eval-cljs`) or reads runtime metadata (`handler-meta`) MUST distinguish, at the wire boundary, between a value that is **genuinely `nil`**, an evaluation that **threw** (an unresolved symbol, a compile/resolve warning surfaced as a throw, any runtime exception), and a value that is **unserializable** (a `#object[...]` / `#js {…}` / Function ref that `pr-str` renders but EDN cannot read back). The codec is a single **runtime-side classifier wrap** (the live value is the only place serializability and thrown-exception detection are observable) that tags the outcome under the cross-MCP `:rf.mcp/result` key; the server projects each tag onto the tool's `:ok?` vocabulary. `handler-meta` returns the **parsed metadata map**, never the metadata smuggled as a string under `:value`. The codec is **additive** — a legacy untagged value flows through unchanged — and **composes with elision**: it tags the outcome; the size walker still runs over the `:value` payload it carries.

**2. Dispatch returns the consequence by default.** The default `dispatch` result is the re-frame2 **consequence** of the dispatch — `{:epoch-id :db-changed? :changed-paths :effects-fired :no-op?}` — not a transport ACK. The data is already assembled in the epoch the run recorded; the tool projects it so a **no-op is VISIBLE** (`:db-changed? false :effects-fired [] :no-op? true`) instead of riding back as a success-shaped ack indistinguishable from a real landing. `dispatch → verify` collapses into one call. An async/queued dispatch whose cascade has not drained returns `{:settled? false}` so the agent knows to `watch-epochs` the eventual settlement; the full assembled epoch stays an opt-in **trace** mode. Two further opt-in modes drive the *view* layer in one call: **`:await-render`** resolves once the substrate has flushed to the DOM and the next paint is scheduled (async, via the rAF-timed `after-render` hook), and **`:settle`** dispatches → synchronously commits pending renders via the installed adapter's `flush-render!` → returns the **settled epoch** including its `:rf.view/render` / `:rf.view/unmounted` entries (per [§Driving the render — The `dispatch-and-settle` contract](#the-dispatch-and-settle-contract)).

**3. Parse-once, echo, and registry-validate the call-time arguments.** Every id / event-vector / sub-vector crosses the wire as a string and is coerced to EDN. The boundary MUST parse that EDN **once**, **echo the resolved value** in the result (`:resolved <edn>`) so the agent sees exactly what the runtime parsed (catching a malformed coercion like a `::rf/xray` double-colon before it silently mis-routes), and **validate the id against the LIVE registry**. An unknown frame / event / sub MUST return a **structured error carrying nearest matches** (`"unknown :event :rf/xrayy; did you mean :rf/xray?"`) and MUST NOT silently no-op a dispatch against an unregistered id. This is **call-time VALUE validation**; it complements the descriptor-generation surface that validates tool DESCRIPTORS from the registries at attach time (the two are distinct — descriptor validation pins the *shape* an agent may call; this pins the *value* a specific call carries).

**Reference impl: re-frame2-pair-mcp.** The typed codec lives in `tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/result_envelope.cljs` (the runtime-side `wrap-form` classifier + the server-side `envelope->result` projection); `eval-cljs` and `handler-meta` route through it. The dispatch consequence + call-time validation live runtime-side in `skills/re-frame2-pair/preload/re_frame2_pair/runtime.cljs` (`dispatch-consequence!`, `validate-event-id` / `validate-registered` with edit-distance `nearest-ids`), surfaced by the `dispatch` tool. All three are framework-general — any pair-shaped server consuming this Spec inherits the contract.

<a name="freshness--liveness-token-mcp-layer"></a>
#### Freshness / liveness token (MCP layer)

A pair session reads live runtime state across a chain of moving parts — the MCP server holds an nREPL socket, shadow-cljs (or the equivalent build tool) holds a build worker, a browser tab holds the running heap. Any link can **drift from the others without failing loudly**: the tab refreshed (the heap and every cached handle reset, but the socket is up); the WebSocket to the runtime dropped (eval round-trips return blank, indistinguishable from a form that genuinely returns `nil`); or — the silent killer — **a stale incremental build is serving OLD code** (the runtime is alive and answering, but running code from *before* the operator's last edit). The third mode cost a real ~30-minute false-alarm debugging detour (a "bug" that was just a stale build).

`discover-app` (and any read op that opts in) **MUST** carry a **freshness token** that makes all three obvious **on the first call**, so an agent pattern-matches on liveness *before* trusting a read rather than inferring staleness later from a wrong conclusion. The token is the [§No silent swallow](Conventions.md#no-silent-swallow--recognised-input-must-signal) principle applied to the *connection*, not the value. It has two halves:

- **Browser half** (only the running heap knows these): `:runtime-instance-id` — a per-load identity that changes on every full page reload / heap reset, so an agent that cached an id and sees a new one knows the tab refreshed and stale handles must re-discover; `:runtime-loaded-at` — the wall-clock moment the *running code* loaded (a load-time `def`, **not** a per-call `now`); `:read-at` — the wall-clock at the moment of the read (proves the runtime answered, and with `:runtime-loaded-at` yields uptime).
- **JVM / build-tool half** (only the build worker holds these): `:compile-cycle` — a **monotonic build counter** that bumps on every successful compile (the "is my edit live?" id); `:build-flushed-at` — the wall-clock of the build's last flush (the compile that produced the bytes currently on disk); `:runtime-count` — connected runtimes for the build; `:heartbeat-age-ms` — ms since the most recent runtime's ping/pong exchange (the WS heartbeat).

The server cross-checks the two halves into a single `:liveness` verdict: **`:stale-build`** when `:build-flushed-at` is strictly newer than `:runtime-loaded-at` (the build recompiled *after* the running code loaded — the tab is serving old code; **reload**); **`:no-runtime`** when zero runtimes are connected or the heartbeat is older than a staleness threshold; **`:fresh`** when a runtime is connected, the heartbeat is recent, and the build has not recompiled since the runtime loaded; **`:unknown`** when the build-tool half can't be read (degrade to the browser half — never crash). `:no-runtime` wins over `:stale-build` (you cannot serve stale code if nothing is connected); a non-fresh verdict carries a `:hint` naming the recovery. The token is a **diagnostic signal, never a hard gate** — the tool still runs; the agent just sees the staleness. The monotonic `:compile-cycle` is the same surface a standalone "is my edit live?" build-id check would expose, so it **subsumes** a separate compile-id mechanism.

**Reference impl: re-frame2-pair-mcp.** The browser half is stamped at preload load-time in `skills/re-frame2-pair/preload/re_frame2_pair/runtime.cljs` (`session-id` / `loaded-at`, surfaced by `freshness` and merged into `health`); the JVM half + cross-check + `:liveness` verdict live in `tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/freshness.cljs` (reads the shadow-cljs build worker's `::build/build-info` `:compile-cycle` / `:flush-complete` and the worker `:runtimes` ping/pong via a JVM eval), attached by `discover-app`. Any pair-shaped server consuming this Spec inherits the contract — the build-tool half is read from whatever build tool the server drives.

### Direct-read privacy posture for `sub-cache` and `get-path`

Most Tool-Pair surfaces ride the trace bus (`register-listener!` / `register-epoch-listener!`) or the event-emit substrate, where `:sensitive?` stamps and size markers are applied at the emit boundary. Two surfaces in the attachment table above do **not** ride that path: `(rf/sub-cache frame-id)` and the MCP-server `get-path` tool (a direct read-by-path against `(rf/app-db-value frame-id)`). Both are **synchronous reads** of live runtime state — the sub-cache map, an arbitrary path into `app-db` — and the `:sensitive?` trace stamp protects only the *trace* surface. A direct read returns the live value untransformed unless the wire-egress boundary scrubs it.

The framework's wire-egress walker is `rf/elide-wire-value` (per [API.md §Size-elision wire-boundary walker](API.md#elide-wire-value-the-wire-boundary-walker)) — the **single normative emission site** for `:rf/redacted` (sensitive) and `:rf.size/large-elided` (oversize) markers. This subsection pins the contract that every MCP-server (or other off-box forwarder) implementing `sub-cache` or `get-path` surfaces must honour — re-frame2-pair-mcp rides it today; story-mcp and any third-party server shipping the same tool names inherits the same posture.

**Normative contract** ( / — the framework-wide MUST; [Security.md §Direct-read privacy posture](Security.md#direct-read-privacy-posture-for-sub-cache-and-get-path) names this surface as Tool-Pair's owned contract).

- A pair-shaped tool that ships a `sub-cache` surface (the direct read of `(rf/sub-cache frame-id)` per [§How AI tools attach](#how-ai-tools-attach)) **MUST** route the returned `{query-v {:value v :ref-count n}}` map through `rf/elide-wire-value` before the value crosses the wire egress. Off-box defaults apply: `:rf.size/include-sensitive?` and `:rf.size/include-large?` both default `false`, so a sub whose query-v lands on a declared-`:sensitive?` path (or whose `:value` is a slot flagged `:sensitive? true` via `[:rf.runtime/elision :sensitive-declarations]`) returns the `:rf/redacted` sentinel; a `:value` exceeding `:rf.size/threshold-bytes` returns the `:rf.size/large-elided` marker. The composition rule of [API.md §Size-elision wire-boundary walker](API.md#elide-wire-value-the-wire-boundary-walker) applies — when both predicates match the **sensitive drop wins** (the size marker is suppressed because it would leak `:path` / `:bytes` / `:digest`).
- A pair-shaped tool that ships a `get-path` surface (the direct read of `(get-in (rf/app-db-value frame-id) path)`) **MUST** route the resolved value through `rf/elide-wire-value` before egress, passing `:path path` and `:frame frame-id` in the opts so the elision marker's `:handle` slot carries `[:rf.elision/at <path>]` and the agent can drill into a non-elided child by re-calling `get-path` with a deeper segment. Off-box defaults apply identically to `sub-cache` above. The walker reads the live `[:rf.runtime/elision :declarations]` and `[:rf.runtime/elision :sensitive-declarations]` registries from the named frame's **runtime-db** partition (durable, serializable framework state — NOT the app-db `:rf/runtime` root) — it MUST therefore run app-side (server-side, where the registry is reachable), not in the MCP server's host process.
- Both surfaces **MUST** honour the opt-in escape hatches per the cross-MCP convention: the MCP call argument is the unqualified `:include-sensitive` (the fixed cross-server wire-key per [`tools/mcp-base/spec/sensitive.md` §Cross-server arg-vocabulary](../tools/mcp-base/spec/sensitive.md#cross-server-arg-vocabulary-convention) — the Anthropic tool-input-schema regex rejects a trailing `?`); passing `:include-sensitive true` forwards `:sensitive? true` slots verbatim, and `:elision false` (or equivalent) bypasses the walker entirely. The server resolves that wire arg into the internal `:include-sensitive? true` egress opt it threads to the walker (the `?` rides the internal opt, never the wire key). The escape hatches are **off by default** — a tool that omits the opts gets the elided posture. Apps that need the raw value at a sensitive path are responsible for explicitly opting in at the call site, the same posture the trace-stream forwarders take.

**Named-egress profile adoption (EP-0015 §10).** A pair-shaped MCP server is an **off-box tool wire**, so it expresses its egress as the *named* boundary [`:rf.egress/off-box-tool`](015-Data-Classification.md#projection-profiles--the-rfegress-enum-provisional) — not a hand-rolled `:rf.size/*` combination. The published default is `:rf.egress/off-box-tool` (sensitive redacts, large elides, the `:rf.size/large-elided` marker carries the structural digest a tool needs to reason about shape); the trusted-local `--allow-sensitive-reads` + per-call `:include-sensitive true` two-key opt-in selects `:rf.egress/local-raw` (sensitive **and** large pass through). The profile is resolved to its `:rf.size/*` floor through the **single source of truth** — `re-frame.projection/profile-size-opts` (the framework table) or, for a server that must not pull the framework runtime graph into its bundle, a pure-data mirror the conformance gate pins byte-identical (re-frame2-pair-mcp / story-mcp resolve through `re-frame.mcp-base.egress`). The server **MUST NOT** maintain a per-tool `show-sensitive?` / `include-sensitive?` *ad-hoc* toggle that re-derives the redaction posture: the **(tool, frame) visibility grain is the profile + the gate**, and revealing sensitive data is a `:rf.egress/local-raw` operator act that is itself trace-visible ([015 §Cross-tool visibility grain](015-Data-Classification.md#cross-tool-visibility-grain)). The `:elision false` escape hatch composes on top of the profile floor as the EP-0015 §10 explicit `:rf.size/include-large?` override (the override wins). Tools **MUST NOT** re-implement redaction at the sink — they hand `elide-wire-value` (bare-value direct reads) / `project-egress` / `projected-record` (record-shaped egress: epoch records, the EP-0011 uniform reply envelope, EP-0016 continuation payloads, handled-event / error observability records) the resolved profile and consume the **already-projected** result. **The opt-ins thread INTO the projection, never around it.** For record-shaped epoch egress specifically, the operator's `:include-sensitive` opt-in is passed as the `:include-sensitive? true` egress opt to `projected-record` — it lifts ONLY the app-db sensitive axis. The orthogonal `:include-fx-args?` (fx-handler `:args`), `:include-runtime-db?` (the `:rf.db/runtime` frame-state partition), and `:include-large?` axes, plus the app `:redact-fn` advanced override, stay at their fail-closed projection defaults regardless of `:include-sensitive`. A tool **MUST NOT** treat `:include-sensitive true` as a wholesale raw-epoch bypass that disables the projection — that conflates the app-db sensitive axis with every other axis and ships raw fx-args / runtime-db / un-`:redact-fn`'d records off-box. A full raw epoch read is the explicit per-axis opt combination on `projected-record`, not an `:include-sensitive`-implied side effect.

**Direct reads need explicit elision.** The per-slot schema `:sensitive?` declaration (per [009 §Privacy / sensitive data in traces](009-Instrumentation.md#privacy--sensitive-data-in-traces)) and registration-owned `:sensitive` payload classification (on `reg-event` / `reg-sub` / `reg-flow`, per [015 §Frame-owned durable classification](015-Data-Classification.md)) both shape what the *trace surface* emits — the schema declaration stamps the trace event, while registration-owned `:sensitive` drives the router's internal redaction plumbing, which overwrites named payload keys with `:rf/redacted` before the trace surface sees them (the handler body still receives the raw `:event` coeffect). Neither touches the live `app-db` or the live sub-cache. A direct read against either bypasses both mechanisms: the trust boundary is **the trace surface plus the MCP egress**, not the app-db itself. Without an explicit elision step at the egress, a `sub-cache` or `get-path` call would return the live value verbatim — including any sensitive slot declared `:sensitive?`, and including a `:value` larger than the wire budget can absorb. The MUST contract above closes that gap at the MCP-server layer where every direct-read surface lives.

**Reference impl: re-frame2-pair-mcp.** The `tools/re-frame2-pair-mcp` server honours every clause of this contract — `get-path-tool` wraps the resolved value in a `(re-frame.core/elide-wire-value v {:path path :frame <fid> ...})` call before returning to the client, and the `snapshot` tool routes BOTH its `:app-db` AND `:sub-cache` slices through the same walker server-side (see the `reduce-kv` body in `tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/snapshot.cljs` that threads each frame's slice through `elide-wire-value` with `:frame fid` so the per-frame runtime-db `[:rf.runtime/elision]` registry hits the right declarations). Any third-party `sub-cache` tool MUST land the same wrapper from day one — the contract here is normative, not aspirational. The framework-side guarantee that the walker honours sensitive / large declarations against sub-cache-shape input (rather than only app-db-shape input) is pinned in `implementation/core/test/re_frame/elision_test.clj` under the `sub-cache-shape-walker-*` family.

**Defence in depth — what this contract does and does not displace.** This subsection commits the **wire-egress** posture for direct reads. It does **not** displace path-level privacy mechanisms upstream of the read:

- Apps that need fine-grained app-db privacy classify the durable path with a commit-plane `:sensitive` classification effect (a `reg-event` returns `:sensitive` alongside `:db`, per [015 §Durable app-db — the four commit-plane effects](015-Data-Classification.md#durable-app-db--the-four-commit-plane-effects)) so the trace stream's `:db-after` projection is redacted at emit time by `project-egress`, regardless of subsequent reads.
- Apps that need stronger guarantees keep sensitive values **out of `app-db`** entirely (host-side keychain, IndexedDB with separate-origin access, in-memory only) — the contract above protects the wire, not the in-process value.
- The framework-side path-level privacy mechanism landed with EP-0025: durable app-db paths are classified from a handler's commit-plane `:sensitive` effect and subsystem instance data via projection-relative declarations, lowered into the per-frame `[:rf.runtime/elision]` registry (per [015 §Durable app-db — the four commit-plane effects](015-Data-Classification.md#durable-app-db--the-four-commit-plane-effects) and [009 §Privacy / sensitive data in traces](009-Instrumentation.md#privacy--sensitive-data-in-traces)). It composes under the same wire-egress contract — `elide-wire-value` is the single normative emission site, so any future tightening of the path-level surface flows through the same walker without changing the MCP-server obligation here.

**Cross-references:** [Security.md §Trust-boundary matrix](Security.md#trust-boundary-matrix) (surface-keyed index — see the "Direct app-db read" and "Sub-cache read" rows for the consolidated owner / default / opt-in / impl-hook / conformance / consumers projection), [API.md §Size-elision wire-boundary walker](API.md#elide-wire-value-the-wire-boundary-walker) (the walker contract), [API.md §Privacy](API.md#privacy-spec-009-privacy--sensitive-data-in-traces) (per-slot schema `:sensitive?` stamp + registration-owned `:sensitive` classification), [009 §Privacy / sensitive data in traces](009-Instrumentation.md#privacy--sensitive-data-in-traces), [Conventions.md §Reserved namespaces (framework-owned)](Conventions.md#reserved-namespaces-framework-owned) (the `:rf.size/*` / `:rf.elision/*` wire keys), [Security.md §MCP tool authority and isolation](Security.md#mcp-tool-authority-and-isolation) (the framework-wide threat-model entry).

### MCP tool authority classes — named-mutation vs eval-cljs

Pair-shaped MCP servers ship tools that read and mutate live runtime state. Two qualitatively-different authority classes, each gated differently:

- **Named-mutation tools — no extra approval gate.** Invoking a re-frame2-pair-mcp / story-mcp tool that names its operation — `dispatch`, `restore-epoch!`, `replace-app-db`, `get-app-db`, `get-path`, `snapshot`, `sub-cache` — is itself the explicit consent. The operator launched the server; the operator invoked the tool with a known event id / path / frame id; the framework does not insert a per-call confirmation prompt. The debugging surface MUST be low-friction or programmers route around it (per [Security.md §Pragmatic stance](Security.md#pragmatic-stance) proposition 1 — "trust the explicit invoker"). The threat model that gates here is **the programmer made an accident**, not a remote attacker.
- **Arbitrary-eval tools — launch-flag opt-OUT, default ON.** Tools that accept arbitrary host-language source and execute it in the runtime process (e.g., `eval-cljs`, or the host's equivalent eval surface) are the REPL primitive of a pair-debug session — arbitrary form evaluation against the live runtime is the whole reason an operator installs a pair-MCP. Published builds (re-frame2-pair-mcp) ship the eval surface **ENABLED**. The operator opts OUT via an explicit launch flag (`--no-eval` or equivalent) for the rare paranoid case (CI runs, shared dev environments). The threat-model rationale for the inversion: a default-OFF eval gate did not add a protection separable from `--allow-writes` because eval can express any write the writes-gate would block — the two gates are not independent. So the prior default-OFF eval surface added friction (every operator had to edit their MCP client config and restart Claude Code to access the REPL primitive) without adding a separable protection. The real defence is the **localhost-bind default** below and **don't expose the MCP to untrusted callers**.

The split is normative for every pair-shaped MCP server downstream of this Spec; tools that ship an arbitrary-eval surface MAY default it ON (the re-frame2-pair-mcp posture) and SHOULD expose a launch-flag opt-out for the rare paranoid case. Per : defaulting the eval surface OFF does not add a protection separable from `--allow-writes`, because eval expresses every write the writes-gate blocks; the load-bearing protections are the localhost-bind default below and the operator's trust decision at MCP install time.

### MCP servers default localhost-bind

Published MCP servers (re-frame2-pair-mcp, story-mcp, and downstream pair-shaped servers consuming this Spec) **MUST** bind to a loopback address (`127.0.0.1` / `[::1]` / equivalent) by default. Remote access is an **explicit launch-flag opt-in**. The default rules out casual cross-network reach for the surface that exposes `dispatch`, `restore-epoch!`, direct-read tools, and (when explicitly enabled) eval-cljs — none of which is shaped for unauthenticated network-exposed use.

The localhost-bind default composes with the eval-cljs launch-flag posture above: a stock install ships **eval-ON + localhost-only** (the eval-cljs default flipped to ON; the load-bearing remote-attack protection is the localhost-bind, not a redundant eval-gate that an attacker could bypass via the writes-gate-equivalent path). Operators who want a true read-only debug session compose `--no-eval` with `--allow-writes` absent. Per [Security.md §MCP tool authority and isolation](Security.md#mcp-tool-authority-and-isolation).

### Per-session app-db cache — cache-poisoning posture

The `:rf.mcp/cache-hit` wire marker (per the table in [§MCP-side wire-marker vocabulary](#mcp-side-wire-marker-vocabulary)) is emitted by a pair-shaped server's **per-session response cache** when an identical app-db root produces a byte-identical re-emit. The cache is keyed on the actual app-db root identity (a root hash or equivalent) — not on a session token, not on a client-supplied tag, not on a wall-clock window. Cache invalidation rests on identity equality: if the root hash changes, the entry is invalidated; if it does not change, the cached response is returned.

The **security property** of this design is that **cache poisoning by mismatched session is structurally impossible**. A client cannot supply a hash that aliases a different session's app-db root because the hash is computed from the live app-db on the server side, not from client input; a stale session whose app-db has mutated does not hit the cache because the hash has changed. The cache is a token-budget optimisation, not a trust boundary — but the keying rules out the cache-poisoning class by construction.

This composes with the **direct-read MUST** above ([§Direct-read privacy posture for `sub-cache` and `get-path`](#direct-read-privacy-posture-for-sub-cache-and-get-path)): a cache-hit re-emit is a re-emit of the **already-elided** response (because the cached response was the post-`rf/elide-wire-value` envelope), so a cache hit cannot regress the wire-egress posture. Per [Security.md §MCP tool authority and isolation](Security.md#mcp-tool-authority-and-isolation).

## What pair-shaped tools NOT to ship as part of re-frame2

- **The Claude integration** itself (prompts, retrieval, model selection). Lives in the pair tool.
- **The nREPL middleware** that exposes the runtime to the agent. Specific to the host environment.
- **The conversational interface** ("Tell me about every `:checkout/*` event"). The pair tool's job to prompt-engineer; re-frame2 just ships data.
- **Skill-shaped retrospective analysis.** That is a separate, post-v1 artefact — a Claude skill (not a runtime tool) that reviews pair sessions and proposes improvements to the pair tool itself. Reference: [re-frame-pair-improver](https://github.com/day8/re-frame-pair-improver).

## Future-compat commitments

Per the philosophy of [Spec-ulation](Principles.md#spec-ulation), the pair-tool runtime contract grows additively:

- **Trace event categories** are stable; new categories are added with new `:operation` keywords.
- **Registry query API** signatures are stable; new query functions are additive.
- **Epoch history fields** can grow new keys (open map), never remove.
- **The `:rf.epoch/*` op-types** are reserved for re-frame2's epoch machinery.

The pair tool can rely on all of these surviving across re-frame2 minor versions. Major versions will document any changes.

## Cross-references

- [day8/re-frame-pair (upstream)](https://github.com/day8/re-frame-pair) — the original tool this contract is shaped to support.
- [001-Registration.md](001-Registration.md) — the registrar surface for inspecting and hot-swapping.
- [002-Frames.md](002-Frames.md) — frame-targeted dispatch, frame inspection.
- [009-Instrumentation.md](009-Instrumentation.md) — the trace stream and error contract.
- [011-SSR.md](011-SSR.md) — server-side runtime is the same contract; pair tools work there too.
- [Spec-Schemas §`:rf/epoch-record`](Spec-Schemas.md#rfepoch-record) — the recorded shape.
- [Cross-Cutting-Designs §3 Token budgets](Cross-Cutting-Designs.md) — wire-protocol mechanisms index (canonical homes for cap / slice / paginate / lazy-summary / dedup / elision live at the MCP-server layer).
- [tools/mcp-conformance/TOKEN-BUDGETS.md](../tools/mcp-conformance/TOKEN-BUDGETS.md), [tools/mcp-conformance/NAMING.md](../tools/mcp-conformance/NAMING.md) — cross-MCP conformance pins (token-budget contract; tool-naming verb table).
- [re-frame-pair-improver](https://github.com/day8/re-frame-pair-improver) — post-v1 companion (Claude skill).

---

## Design notes (non-normative)

The runtime contract above is fixed; the notes below capture open design questions that do not affect the contract but inform tool authors.

- **Snapshot serialisation cost.** Persistent data structures share structure; configurable history depth lets users tune. Lazy serialisation is an optimisation.
- **Pair-improver feedback loop.** The [re-frame-pair-improver](https://github.com/day8/re-frame-pair-improver) skill (post-v1) would consume a structured session log; the format would be EDN/JSON, and the schema would land in [Spec-Schemas](Spec-Schemas.md) when the skill ships (no session-log schema is registered there yet).

## Resolved decisions

### Multi-frame operating-frame resolution — hybrid posture

Resolved: pair-shaped tools resolve a multi-frame application's operating frame via the four-tier rule pinned at [§Operating frame — multi-frame resolution](#operating-frame). The shipped impl in `re-frame2-pair.runtime` (per [PR #190](https://github.com/day8/re-frame2/pull/190)) is the canonical reference — `current-frame` walks **explicit override → session pin → sole-registered app frame → nil**, and both read-shaped (`subs-sample`, `snapshot`, `epoch-history`, …) and mutating-shaped (`pair-dispatch-sync!`, `app-db-reset!`, `restore-epoch!`) ops refuse with `{:ok? false :reason :ambiguous-frame}` when resolution yields nil. The resolver is **reserved-frame-aware**: tiers 3 and 4 count only app frames — `(rf/frame-ids)` with `:rf/*` reserved tool frames (Xray's `:rf/xray`, SSR slots) removed, the sole carve-out being `:rf/default` (an ordinary app-frame id per EP-0002 — no framework privilege, counted as an app frame when an app registers it). So a single-app session that also carries an `:rf/xray` frame resolves at tier 3 without a `frames/select`. `:rf/default` is not a tier-4 fallback — and under EP-0002 it is no longer auto-registered at all; silently routing to it would mask the ambiguity in a multi-app session. The hybrid posture (explicit-context-set, implicit-until-reset) supersedes the earlier "Lean" note in §Design notes; single-frame applications never reach the refusal path. The tool surface MUST expose `set-operating-frame` / `reset-operating-frame` / `get-operating-frame` ops (names illustrative; semantics normative) so multi-frame users can pin a target, clear the pin, and inspect the resolver's view. See [§Operating frame — multi-frame resolution](#operating-frame) for the full contract.
