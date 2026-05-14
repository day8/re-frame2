# Tool-Pair — runtime contract for pair-shaped AI tools

> **Type:** Reference
> The runtime surface re-frame2 commits to so that pair-shaped tools — equivalents of [day8/re-frame-pair](https://github.com/day8/re-frame-pair) — can attach to a running re-frame2 application and let an AI agent inspect, dispatch, hot-swap, and time-travel against it.

## What this Spec is and isn't

**This Spec is** the *runtime contract* — the set of public capabilities re-frame2 exposes that pair-shaped tools rely on. It tells an implementer "ship these capabilities and a pair tool can be built against you."

> **Audit lineage.** Several surfaces below (`register-epoch-cb!`, the structured `:sub-runs` / `:renders` / `:effects` slots on `:rf/epoch-record`, `:dispatch-id` / `:parent-dispatch-id` correlation, the `:origin` dispatch opt, `app-schemas` introspection, and the §Source-mapping helper enumeration) were added to this Spec following a cross-reference audit against [day8/re-frame-pair](https://github.com/day8/re-frame-pair)'s actual source — the upstream tool consumed surfaces this contract had not yet committed to. The audit is single-sourced here; downstream Specs (009 / 010 / 002 / Spec-Schemas) carry the additive normative text without re-citing the audit.

> **Source-of-truth note:** Tool-Pair.md is the **canonical surface contract** for the time-travel / epoch-history capabilities and the trace-stream consumption shape. [API.md](API.md) reproduces these signatures (under §Epoch history) for fast lookup, but the *normative* descriptions live here; if the two drift, this Spec wins. The `epoch-history`, `restore-epoch`, and `(rf/configure :epoch-history {:depth N})` surface, plus the `:rf.epoch/snapshotted` / `:rf.epoch/restored` / `:rf.registry/handler-replaced` trace events, are pinned here and referenced from API.md.

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
| **Read `app-db`** | `(rf/get-frame-db frame-id)` returns the current `app-db` value (a plain map) | [002 §The public registrar query API](002-Frames.md#the-public-registrar-query-api) |
| **Read sub values** | `(rf/compute-sub query-v db-value)` runs a sub against an `app-db` value | [008](008-Testing.md) |
| **Read registry** | `(rf/handlers kind)`, `(rf/handler-meta kind id)`, `(rf/frame-ids)`, `(rf/frame-meta id)` | [001-Registration](001-Registration.md), [002](002-Frames.md) |
| **Dispatch** | `(rf/dispatch ev opts)`, `(rf/dispatch-sync ev opts)` with `:frame` opt | [002 §Routing](002-Frames.md#routing-the-dispatch-envelope) |
| **Trace stream** | `(rf/register-trace-cb! key callback)` plus structured trace events | [009](009-Instrumentation.md) |
| **Hot-swap handlers** | Re-registration replaces; emits `:rf.registry/handler-replaced` trace | [001 §Hot-reload semantics](001-Registration.md#hot-reload-semantics) |
| **Stub fx** | `:fx-overrides` map (id-valued at the pattern level) on `dispatch` opts or `reg-frame` metadata | [002 §Per-frame and per-call overrides](002-Frames.md#per-frame-and-per-call-overrides) |
| **Source coordinates** | `:ns`/`:line`/`:column`/`:file` on every registration's metadata (shape: `:rf/source-coord-meta` per [Spec-Schemas](Spec-Schemas.md#rfsource-coord-meta)); mandatory `data-rf2-source-coord` DOM annotation (shape: `:rf/source-coord-attr` per [Spec-Schemas](Spec-Schemas.md#rfsource-coord-attr)) per Spec 006 | [001 §Source-coordinate capture](001-Registration.md#source-coordinate-capture-cljs-reference), [006 §Source-coord annotation](006-ReactiveSubstrate.md#source-coord-annotation-mandatory-rf2-z7f7--rf2-z9n1) |
| **Inspect registered schemas** | `(rf/app-schemas frame-id)`, `(rf/app-schema-at path opts)`, `(rf/app-schemas-digest opts)` | [010 §Schemas as a tooling and agent surface](010-Schemas.md#schemas-as-a-tooling-and-agent-surface) |
| **Errors** | Structured `:rf.error/*` trace events with category + tags | [009 §Error contract](009-Instrumentation.md#error-contract) |

This much is **already specified**. A pair tool built against re-frame2 (and conforming with [day8/re-frame-pair](https://github.com/day8/re-frame-pair)) needs nothing more than these surfaces to do everything in the capability list above except time-travel.

The capability that requires *new* commitments is **time-travel**, addressed below.

<a name="time-travel"></a>
## Time-travel: epoch snapshots and undo

> **Artefact home.** Per [rf2-lt4e](#) (the seventh and final per-feature artefact split per [rf2-5vjj](#) Strategy B), the time-travel surface — the per-frame `:rf/epoch-record` ring buffer (`epoch-history`), the `(rf/configure :epoch-history {:depth N})` knob, the `register-epoch-cb!` / `remove-epoch-cb!` listener API, the `restore-epoch` rewind with its six documented failure modes, the per-cascade trace-capture buffer, the `:rf.epoch/snapshotted` / `:rf.epoch/restored` trace events, and the `:sub-runs` / `:renders` / `:effects` projections — ships in `day8/re-frame2-epoch`. Apps that consume the pair-tool / time-travel surface add the artefact alongside core and require `re-frame.epoch` at boot so the namespace's late-bind hook publications fire before the public re-exports in `re-frame.core` (`rf/epoch-history`, `rf/restore-epoch`, `rf/register-epoch-cb!`, `rf/remove-epoch-cb!`, `(rf/configure :epoch-history ...)`) reach into the hook table at call time. When the artefact is not on the classpath the re-exports degrade silently (empty vector / false / no-op) — the surface is dev-tier and gated on `interop/debug-enabled?`, so a release build that omits the artefact must not raise. See [Conventions §Packaging conventions](Conventions.md#packaging-conventions) and [MIGRATION §M-33](MIGRATION.md#m-33-epoch--time-travel-tool-pair-time-travel-ships-in-a-separate-artefact--day8re-frame2-epoch).

The runtime contract for time-travel:

**Recording.** Every event-cascade settle (drain reaching empty queue) marks an epoch boundary. The runtime records, per frame, an `:rf/epoch-record` (per [Spec-Schemas](Spec-Schemas.md#rfepoch-record)) consisting of `:epoch-id`, `:frame`, `:committed-at`, `:event-id`, `:trigger-event`, `:db-before`, `:db-after`, and (optionally) `:trace-events`, plus the structured per-epoch projections `:sub-runs`, `:renders`, and `:effects` (each pre-derived from `:trace-events`; see [Spec-Schemas §`:rf/epoch-record`](Spec-Schemas.md#rfepoch-record) for shapes). Pair tools route diagnostics off the structured slots — cache-hit-vs-rerun analysis (`:sub-runs[*].:recomputed?`), render-key attribution (`:renders[*].:render-key`, the tuple `[<view-id> <instance-token>]` per [004 §Render-tree primitives](004-Views.md#render-tree-primitives) — rf2-t5tx Option C / rf2-piag), and fx cascade outcome (`:effects[*].:outcome`) — without re-folding the raw trace stream each epoch.

**Ordering.** Epochs within a frame are totally ordered by drain-completion time. Across frames, ordering is per-frame only — there is no global epoch sequence.

**Bounded history.** The runtime keeps the last *N* epochs per frame (default 50, configurable via `(rf/configure :epoch-history {:depth N})`). Older epochs are discarded.

**Query.** `(rf/epoch-history frame-id)` returns the vector of `:rf/epoch-record` values for the frame, oldest-first.

**Restore.** `(rf/restore-epoch frame-id epoch-id)` rewinds the frame's `app-db` to the named epoch's `:db-after` value. Emits `:rf.epoch/restored`.

**Restore failure modes.** `restore-epoch` is a query against a finite per-frame history; the restore can fail for distinct, named reasons. Each is an error trace event with a stable `:operation` key under the reserved `:rf.epoch/*` namespace; the call is a no-op on failure (the frame's `app-db` is unchanged):

| Failure | `:operation` | When it fires | `:tags` |
|---|---|---|---|
| **Unknown frame** | `:rf.error/no-such-handler` (kind `:frame`) | `frame-id` does not name a registered frame. | `{:kind :frame, :frame <id>}` |
| **Unknown epoch** | `:rf.epoch/restore-unknown-epoch` | `epoch-id` is not in the frame's current epoch history (either never recorded or aged out by `:depth`). | `{:frame <id>, :epoch-id <id>, :history-size <n>}` |
| **Schema mismatch** | `:rf.epoch/restore-schema-mismatch` | The recorded `:db-after` no longer validates against the currently-registered `app-schemas` set (a schema was added, tightened, or replaced since the snapshot was taken). | `{:frame <id>, :epoch-id <id>, :schema-digest-recorded <s>, :schema-digest-current <s>, :failing-paths [<path> ...]}` |
| **Missing handler** | `:rf.epoch/restore-missing-handler` | The recorded `app-db` references a registered-id (e.g. an active machine at `[:rf/machines <id>]`, a registered route currently in `:rf/route`) that is no longer present in the registrar. Restoring would leave the frame referencing dangling ids. | `{:frame <id>, :epoch-id <id>, :missing [{:kind <kind>, :id <id>} ...]}` |
| **Version mismatch** | `:rf.epoch/restore-version-mismatch` | The frame's recorded `:rf/snapshot-version` (per [Spec-Schemas §`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot)) is incompatible with the currently-loaded machine definition. Hot-reload moved the machine forward; the older snapshot can no longer be interpreted. | `{:frame <id>, :epoch-id <id>, :machine-id <id>, :version-recorded <int>, :version-current <int>}` |
| **Concurrent-drain rejection** | `:rf.epoch/restore-during-drain` | `restore-epoch` was called while the frame's run-to-completion drain is still in flight (per [002 §Run-to-completion dispatch](002-Frames.md#run-to-completion-dispatch-drain-semantics)). Restore is rejected; the user retries after settle. | `{:frame <id>, :epoch-id <id>}` |
| **Halted-cascade target** (rf2-v0jwt) | `:rf.epoch/restore-non-ok-record` | The named epoch's `:outcome` is not `:ok` — i.e. the record was committed for a halted cascade (`:halted-depth`, `:halted-destroy`, …). Halted records carry partial state for devtools introspection and are not valid restore targets; rewinding would land `app-db` in a state the cascade never settled to. | `{:frame <id>, :epoch-id <id>, :outcome <kw>, :halt-reason <any>}` |

All seven failures have `:op-type :error` and `:recovery :no-recovery`. Pair tools display the `:operation` and `:tags` to the user; the reserved `:rf.epoch/*` namespace lets tools route restore failures distinctly from frame-lookup errors. The failure surface is closed for v1 — additional categories require a Spec-ulation increment.

> **Note on the unknown-frame row.** Six of the seven failures fire under the reserved `:rf.epoch/*` namespace; the remaining one (**Unknown frame**) rides the framework-wide `:rf.error/no-such-handler` op-type with `:kind :frame` because it is a registry-lookup failure that predates the restore call (the same op-type fires for any registrar lookup that names a missing frame). A pair tool routing restore failures should therefore match on either `:rf.epoch/*` or `(:rf.error/no-such-handler ∧ :kind = :frame)` to catch the full failure surface; the audit-found drift between the reserved-namespace prose and the table's heterogeneous first row is preserved-by-design, not a contradiction.

**Restore caveat.** Even a *successful* restore rewinds `app-db` only; effects already fired (HTTP requests sent, navigation pushed, localStorage written) are not reversed. Pair-shaped tools surface this caveat in their UI before applying a restore.

**Production elision.** Per [009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code) the trace surface, schema validation, registrar trace emit, and epoch-history machinery share a single compile-time gate (`re-frame.interop/debug-enabled?`, alias of `goog.DEBUG`); production builds (`:advanced` + `goog.DEBUG=false`) elide all of it. CI's `npm run test:elision` job (Spec 009 §Production-elision verification) asserts the contract holds for every gated surface, including the epoch-history primitives once they land.

### Worked example: walking history and restoring

A pair tool that wants to render a per-frame undo affordance walks `epoch-history`, picks a target epoch (typically by index, by `:event-id`, or by user click on a visualised list), and calls `restore-epoch`. The round-trip below covers the dev-shape consumption pattern end-to-end, including the listener wiring that catches restore failure traces:

```clojure
;; A pair tool's "rewind to before that event" affordance.
;; - Walks the per-frame epoch history (oldest-first vector).
;; - Picks the most recent epoch BEFORE a target event-id.
;; - Calls restore-epoch and listens for either
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
(rf/register-trace-cb!
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

;; Trigger the rewind. restore-epoch returns nil on failure (the failure mode
;; is delivered via the trace stream); on success, app-db has been rewound and
;; :rf.epoch/restored has fired with the new :db-after.
(when-let [target (epoch-before-event :app/main :checkout/submit)]
  (rf/restore-epoch :app/main target))
```

The walk-history-then-restore shape is the canonical pair-tool gesture; render-tree visualisers, "what did this event do?" probes, and conformance harnesses all build on the same primitives. Tools that want post-restore confirmation without registering a trace listener can re-call `(rf/get-frame-db :app/main)` and diff against the pre-restore snapshot.

### Pair-tool writes — state injection

`restore-epoch` and `dispatch` cover most of pair-tools' write needs (rewind to a recorded prior state; drive a cascade through the application's own handlers). The remaining case is **state injection** — replacing a frame's `app-db` with an arbitrary value that the runtime never recorded and that no event handler need exist to produce.

The committed surface is `(rf/reset-frame-db! frame-id new-db)`. It bypasses the dispatch loop, replaces the frame's `app-db` container directly, and records a synthetic `:rf/epoch-record` so `restore-epoch` can rewind past the injection.

**Use cases the surface covers:**

- **Evolved-state-shape probes.** A pair-tool agent rewrites a sub or handler and needs to seed an `app-db` shape that the new code expects, *without* firing a (possibly-failing) cascade through stale handlers. `dispatch` would re-trigger the broken cascade.
- **Story tools.** Fixture-shaped state injection — "render the cart in this state" — without authoring a setup event for every story.
- **Conformance harnesses.** Property-test runs that load a known `app-db`, run a single dispatch, assert post-state. Same shape as a test setup.
- **Time-travel from JSON-loaded bug repros.** A user attaches a serialised `app-db` from a saved bug; the agent loads it. `restore-epoch` covers this only when the state is in the ring buffer; arbitrary `db` injection from outside the recorded history needs a write path.

**Contract.**

- **Replaces the container.** `(rf/reset-frame-db! frame-id new-db)` calls `replace-container!` on the frame's `app-db` substrate container. Subscribers route off the post-reset value the same way they do after a `restore-epoch` happy path or a normal cascade settle.
- **Records a synthetic epoch.** A fresh `:rf/epoch-record` lands in `(rf/epoch-history frame-id)` carrying `:event-id :rf.epoch/db-replaced`, `:trigger-event [:rf.epoch/db-replaced]`, `:db-before` (the pre-reset value), and `:db-after` (`new-db`). The `:sub-runs` / `:renders` / `:effects` projections are empty — no cascade ran. `restore-epoch` of a *prior* epoch rewinds past the injection; `restore-epoch` of the synthetic record itself rewinds *to* `new-db` (i.e. a round-trip to where the reset already left things).
- **Emits `:rf.epoch/db-replaced`** on success with `:tags {:frame <id> :epoch-id <id>}`, `:op-type :rf.epoch`. Pair-tool dashboards filter on the operation to route pair-tool injections distinctly from cascade-driven epochs.
- **Fires `register-epoch-cb!` listeners.** The assembled record is delivered to every registered epoch listener after it lands in the ring buffer — same shape as a cascade-settle delivery.
- **Returns `true`** on success, `false` on any failure.

**Failure modes** (each is a no-op on `app-db` and emits a structured error trace):

| Failure | `:operation` | When it fires | `:tags` |
|---|---|---|---|
| **Unknown frame** | `:rf.error/no-such-handler` (kind `:frame`) | `frame-id` does not name a registered frame. | `{:kind :frame, :frame <id>}` |
| **Drain in flight** | `:rf.epoch/reset-frame-db-during-drain` | `reset-frame-db!` was called while the frame's run-to-completion drain is still running (per [002 §Run-to-completion dispatch](002-Frames.md#run-to-completion-dispatch-drain-semantics)). The injection is rejected; the caller retries after settle. | `{:frame <id>}` |
| **Schema mismatch** | `:rf.epoch/reset-frame-db-schema-mismatch` | `new-db` fails the frame's currently-registered `app-schema` set (per [Spec 010 §Per-frame schemas](010-Schemas.md#per-frame-schemas)). When no schemas are registered the validation is a no-op — every `new-db` is accepted. | `{:frame <id>, :failing-paths [<path> ...]}` |

All three failures have `:op-type :error` and `:recovery :no-recovery`. The closed-set v1 failure surface mirrors `restore-epoch`'s shape.

**Production elision.** Per [009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code) `reset-frame-db!` shares the universal compile-time gate (`re-frame.interop/debug-enabled?`, alias of `goog.DEBUG`); production builds (`:advanced` + `goog.DEBUG=false`) elide the body via Closure DCE. The surface is **dev-only** — pair-tool writes do not ship in production binaries. CI's `npm run test:elision` job asserts the contract holds for the success op (`:rf.epoch/db-replaced`) and both failure ops.

**Artefact home.** `reset-frame-db!` lives in `re-frame.epoch` (it records a synthetic `:rf/epoch-record`, so the surface is epoch-adjacent and naturally co-located with `restore-epoch` / `register-epoch-cb!`). The core re-export late-binds through the hook table (`:epoch/reset-frame-db!`); unlike the four read-shaped re-exports (which degrade silently when the artefact is absent), `reset-frame-db!` raises `:rf.error/epoch-artefact-missing` — the caller's invariant is "undo works after this call", and a silent no-op would lie about that invariant.

**Worked example.**

```clojure
;; A pair-tool agent has just hot-swapped a handler that operates on
;; an evolved app-db shape. Inject the new shape directly so the
;; cascade doesn't re-run through stale handlers, then dispatch a
;; single event to verify the new code works against the seeded state.

(when (rf/reset-frame-db! :app/main {:cart {:items [{:sku "abc" :qty 2}]}
                                     :checkout/state :ready})
  ;; reset-frame-db! has fired :rf.epoch/db-replaced and recorded a
  ;; synthetic epoch. Now drive a dispatch to exercise the new handler.
  (rf/dispatch [:checkout/submit] {:frame :app/main}))

;; To rewind PAST the injection (back to whatever the previous epoch
;; was), pick the epoch BEFORE the synthetic one and restore.
(let [history (rf/epoch-history :app/main)
      pre     (last (filter #(not= :rf.epoch/db-replaced (:event-id %)) history))]
  (when pre
    (rf/restore-epoch :app/main (:epoch-id pre))))
```

**What `reset-frame-db!` is not.** It is **not** a substitute for `dispatch` — handlers, interceptors, fx, and the trace stream all stay quiet during a reset. Use it only when bypass-the-cascade is *required* (the four use cases above); for any change you want the data loop to see, dispatch a real event. The synthetic epoch's empty `:sub-runs` / `:renders` / `:effects` projections are the visible signal that no cascade ran.

## Surface behaviour against destroyed frames

The Tool-Pair surfaces above (`epoch-history`, `get-frame-db`, `restore-epoch`, `reset-frame-db!`, `register-epoch-cb!`) all take a `frame-id`. A pair tool can call any of them after the frame has been destroyed (per [002 §Destroy](002-Frames.md#destroy)) — most often because the tool kept a reference to a frame whose owning component unmounted, or because a teardown sequence interleaved with an in-flight tool gesture. The runtime commits to a **closed contract** for these races so a tool can route them deterministically without inspecting registrar internals.

Pattern: **read-shaped surfaces return an empty shape** (so a defensive `(when ...)` is sufficient); **mutating-shaped surfaces raise structurally** (so a tool that intended a write learns the write did not happen); **listener fan-out emits a one-shot trace** when a previously-registered callback is silenced because its observed frame was destroyed.

| Surface | Shape | Behaviour against destroyed (or never-registered) frame |
|---|---|---|
| `(rf/epoch-history frame-id)` | read | Returns `[]` (empty vector). Identical to "no epochs yet recorded" — consumers that want to distinguish a destroyed frame from a fresh one must consult `(rf/frame-meta frame-id)` or `(rf/frame-ids)` separately. |
| `(rf/get-frame-db frame-id)` | read | Returns `nil`. Consumers that want a destroyed-vs-unknown distinction consult `(rf/frame-meta frame-id)`. |
| `(rf/restore-epoch frame-id epoch-id)` | mutate | Emits `:rf.error/no-such-handler` (kind `:frame`, tags `{:kind :frame, :frame <id>}`) and returns `false`. Same trace shape as any other registry-lookup miss — already enumerated as the **Unknown frame** row of [§Time-travel](#time-travel-epoch-snapshots-and-undo)'s restore-failure table. |
| `(rf/reset-frame-db! frame-id new-db)` | mutate | Emits `:rf.error/no-such-handler` (kind `:frame`) and returns `false`. Identical wording to `restore-epoch`'s frame-miss row — pair-tool writers can match on a single category for both surfaces. |
| `(rf/register-epoch-cb! id callback)` (process-global) | register | The current API is process-global and does not bind a callback to a specific frame; this row therefore does not apply to the existing signature. A future opts-form (`{:frame frame-id}`) would raise `:rf.error/no-such-handler` (kind `:frame`) at registration time when its target is absent — the same shape registration-against-an-absent-target uses elsewhere. The framework reserves the spelling. |
| Pre-registered `register-epoch-cb!` callback whose observed frame is later destroyed | listener silencing | The runtime emits `:rf.epoch.cb/silenced-on-frame-destroy` (`:op-type :rf.epoch.cb`) **once per `(frame, cb-id)` pair**, with `:tags {:frame <id>, :cb-id <id>}`, on the destroy-cascade boundary. Subsequent destroys of the same frame do not re-emit. The callback registration remains in place — eviction is the consumer's call (per [009 §`register-epoch-cb!` invocation rules](009-Instrumentation.md#register-epoch-cb--assembled-epoch-listener)). |

The trace event is enumerated in [009 §`:op-type` vocabulary](009-Instrumentation.md#op-type-vocabulary); its `:tags` schema is canonicalised in [Spec-Schemas](Spec-Schemas.md#per-category-tags-schemas).

**Why "silencing" is a trace and not a return value.** The `register-epoch-cb!` callback never sees a record from a destroyed frame — the runtime stops producing records for the frame the moment its destroy walks. A tool that doesn't know its observed frame was destroyed therefore sees a callback that simply *stopped firing*, with no signal it can route off. The silencing trace closes that gap: pair-tool dashboards, REPL companions, and conformance harnesses all subscribe to the trace stream already (per [§How AI tools attach](#how-ai-tools-attach)), so the existing channel carries the disambiguation. One-shot semantics keep the stream from accumulating noise on rapid frame churn (test fixtures, story tools).

**Listener-silencing trace: implementation note.** The runtime tracks, per cb-id, the set of frame-ids that cb has been delivered records for. When a frame is destroyed (per [002 §Destroy](002-Frames.md#destroy)), every cb whose observed-frame set contains that frame receives one silencing trace; the cb's entry for that frame-id is then dropped so a re-registration of a same-keyed frame (e.g. `reset-frame :app/main`) can re-arm. A cb that was never delivered any record (e.g. registered immediately before destroy) does not see a silencing trace — there is nothing to silence.

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

When the flag is off (the default), Closure DCE elides every bracket; `performance.getEntriesByType('measure')` returns no `rf:`-prefixed entries because none were ever emitted. This is by design: the perf channel is *opt-in for prod* (timing instrumentation has measurable cost on heavy hot paths and consumers should choose to pay it).

The Performance API surface is **CLJS-only**. JVM artefacts (SSR, headless tests) emit no perf entries; tools running there use the host's profilers (clj-async-profiler, JFR).

## REPL-eval

The pair tool's "execute arbitrary expression" capability is the **host's REPL** (CLJS: nREPL via cider; Python: IPython; etc.) — re-frame2 doesn't ship an evaluator, just exposes its data structures. An nREPL session attached to a running re-frame2 app can already see `re-frame.db/app-db` (or its substrate-agnostic equivalent), the registrar, and any namespace-resolvable function.

The CLJS reference's commitment: **public APIs** (everything in `re-frame.core`) are stable for pair-tool consumption. **Private namespaces** (`re-frame.db`, `re-frame.router`, `re-frame.subs`, `re-frame.events`, `re-frame.registrar`) are off-contract — they may change between versions. Per [MIGRATION §M-1](MIGRATION.md), tools that reach into private namespaces will need to migrate.

The pair tool is encouraged to use only public APIs. If it needs something not public, file a Spec issue.

## Source-mapping UI clicks back to code

The "which button is at `src/app/profile/view.cljs:84`?" capability requires every render-tree node — every registered view, every hiccup tag — to carry source coords. re-frame2's view registrations include `:ns`/`:line`/`:file`. The CLJS reference additionally:

- Captures source coords at every `reg-view` macro expansion (`:ns` / `:file` / `:line` / `:column`).
- **Annotates rendered DOM** with a `data-rf2-source-coord="<ns>:<sym>:<line>:<col>"` attribute pointing back to the registration that produced it. This is **mandatory** in re-frame2 per [Spec 006 §Source-coord annotation](006-ReactiveSubstrate.md#source-coord-annotation-mandatory-rf2-z7f7--rf2-z9n1) — every substrate adapter whose host has a DOM-attribute concept MUST inject the attribute. Annotation is dev-only and gated on `interop/debug-enabled?` (the CLJS mirror of `goog.DEBUG`); production builds elide the attribute via dead-code elimination so there is no DOM-bytes cost in shipped bundles.

With the annotation in place, a pair tool can take a click position, read the nearest annotation, and resolve back to a source coordinate. Documented exemption (per Spec 006 §Source-coord annotation): components returning React Fragments, host-component heads (`:>`), or other non-DOM roots are exempt; pair tools fall back to `(rf/handler-meta :view id)` for those nodes.

### State-machine source-coord stamping (rf2-8bp3)

The DOM-attribute annotation above maps clicked DOM nodes to view registration call sites. A complementary surface maps state-machine spec elements (guards / actions / transitions / state-nodes) back to their source positions.

Per [Spec 005 §Source-coord stamping](005-StateMachines.md#source-coord-stamping-rf2-8bp3), the `reg-machine` macro walks its literal spec form at expansion time and attaches a flat coord index under `:rf.machine/source-coords`, keyed by spec-path tuples:

```clojure
(:rf.machine/source-coords (rf/machine-meta :auth/login))
;; {[:guards :form-valid?]                {:ns ... :line ... :column ... :file ...}
;;  [:actions :commit]                    {...}
;;  [:states :form :on :submit]           {...}
;;  [:states :form :on :submit :action]   {...}}
```

Pair tools use this index for two distinct UI gestures:

- **Jump to definition.** A click on a guard/action name in the visualisation reads `[:guards <id>]` / `[:actions <id>]` / `[:on-spawn-actions <id>]` to find where the fn is implemented.
- **Jump to call site.** A click on a transition arrow or state node reads the deepest stamped path-tuple matching the node (e.g. `[:states :idle :on :submit]`); for keyword-named slots (`{:guard :form-valid?}`) the slot itself isn't stamped — the tool falls back to the enclosing transition's coord, which IS stamped.

The framework commits to **the index shape and the keyword-reference rule** (definition-site only for keyword refs, reference-site for inline-fn literals). Pair tools ship their own UI affordance over the index. Like `data-rf2-source-coord`, the stamping is gated on `interop/debug-enabled?` and elides under `:advanced` + `goog.DEBUG=false`.

### Where the DOM-to-source helpers live (re-frame2 vs tool)

The audit found the upstream pair tool ships `dom/source-at`, `dom/find-by-src`, and `dom/fire-click-at-src` helpers (it currently parses re-com's `data-rc-src` attribute, but the shape is general). Pair-shaped tools need *some* DOM-to-source bridge; the question is whether the helpers themselves are part of re-frame2's contract or live in the consuming tool.

**re-frame2's commitment is the attribute, not the helpers.** Specifically:

- The runtime emits the `data-rf2-source-coord` attribute on rendered DOM nodes when source-annotation is enabled. **The attribute's value format is a committed public contract** (per [Spec-Schemas §`:rf/source-coord-attr`](Spec-Schemas.md#rfsource-coord-attr)) — a 4-segment colon-separated string `<ns>:<sym>:<line>:<col>` where `<sym>` is the registered handler-id (not a file path). Consumers parse the four segments directly to recover `<ns>` / `<handler-id>` / `<line>` / `<col>`. To recover the full source-coord shape including `:file`, follow up with `(rf/handler-meta :view <handler-id>)` — the registration metadata returns `:rf/source-coord-meta` (per [Spec-Schemas](Spec-Schemas.md#rfsource-coord-meta)) which carries all four keys (`:ns`, `:line`, `:column`, `:file`).
- The framework does **not** ship `dom-source-at` / `find-by-src` / `fire-click-at-src` style helpers. These are tool-side: the pair tool reads the attribute via its own host's DOM access (`document.querySelector` in CLJS, `page.locator` in Playwright-driven flows, etc.) and resolves the source coordinate locally — the parse is straightforward against the committed format above.

**Why tool-side, not framework-side:** the helpers depend on host-specific DOM access that re-frame2 the framework does not assume — a pair tool driving a browser via CDP, a server-rendered diagnostic dump, or a static analyzer all want different "lookup the attribute" implementations. Pinning a single helper signature here would either over-constrain consumers or under-serve them. The framework commits to the attribute (stable, cross-host, parseable); the consuming tool ships the host-appropriate query primitives on top.

A future re-frame2 minor version may introduce framework-side helpers if the ecosystem converges on a single shape; the attribute contract is forward-compatible with that addition.

<a name="operating-frame"></a>
## Operating frame — multi-frame resolution

re-frame2 is multi-frame (per [002-Frames.md](002-Frames.md)). Every pair-tool surface that names a `frame-id` (`get-frame-db`, `epoch-history`, `restore-epoch`, `reset-frame-db!`, `dispatch`, `dispatch-sync`, `subscribe`, `snapshot-of`, `app-schemas`, `sub-cache`) is **frame-targeted** — the tool must resolve a single frame before the call. In a single-frame application the resolution is trivial: every call lands in the lone registered frame. In a multi-frame application the tool needs a deterministic "operating frame" rule so successive calls don't fan out across different frames by accident, and so a user gesture like "show me app-db" has one unambiguous answer.

Pair-shaped tools (re-frame-pair, re-frame-pair2, [re-frame-pair-improver](https://github.com/day8/re-frame-pair-improver), Causa, Story, any future companion that drives a multi-frame app) MUST implement the **hybrid-resolution** contract below. The contract is normative for pair-shaped tools — applications themselves continue to use the frame-routing rules of [002 §Routing](002-Frames.md#routing-the-dispatch-envelope); this section pins how a *tool sitting outside* the application picks which frame its read or write targets.

**Resolution order.** Every frame-targeted call resolves the operating frame by walking the following four tiers in order; the first tier that yields a frame-id wins:

| Tier | Source | When it fires |
|---|---|---|
| 1 | **Explicit per-call override** | The caller passes a frame-id with the op (e.g. `(rf/get-frame-db :stories)`, `(subs-sample [:cart/total] :stories)`, `{:frame :stories}` on dispatch opts). |
| 2 | **Session-pinned selection** | The tool's session has called `select-frame!` (or its equivalent) since the last reset; the pinned id is the resolved frame. |
| 3 | **Sole-registered frame** | The framework's `(rf/frame-ids)` returns exactly one frame. That frame is the resolved frame, regardless of whether it is `:rf/default` or some other id. |
| 4 | **Nil** (ambiguous) | More than one frame is registered, the session has not pinned a selection, and the caller did not pass an override. The resolver yields nil; the op routes via the §Ambiguity surface below. |

**Single-frame applications never reach tier 4.** A re-frame2 application with only `:rf/default` registered always resolves at tier 3; the pair tool's UX is identical to single-frame re-frame. The contract is structurally backwards-compatible — a single-frame consumer sees no ambiguity prompt.

**`:rf/default` is not a special-case fallback.** A common naïve implementation would fall back to `:rf/default` at tier 4 (since it's always pre-registered per [002 §`:rf/default`](002-Frames.md#rfdefault)). The hybrid contract **rejects** that fallback: a multi-frame app's `:rf/default` is one frame among many, and silently landing reads or writes there masks the ambiguity rather than surfacing it. Tier 3 picks `:rf/default` only when it is **uniquely** registered.

**Session-pin lifecycle.** A `select-frame!` call binds the operating frame for the session and persists across subsequent calls (the "implicit-until-reset" half of the hybrid posture). The selection is cleared by either a `reset-operating-frame!` (or equivalent) call, a runtime reload (the session sentinel changes, per [§How AI tools attach](#how-ai-tools-attach)), or destroying the pinned frame (the next resolution falls through to tier 3 or 4). Pair tools that surface a "current operating frame" indicator in their UI read the session pin directly; tools that want to show the *resolved* frame call the resolver and display its result (or "ambiguous" when nil).

**Frame destroyed mid-session.** When the session-pinned frame is destroyed (per [002 §Destroy](002-Frames.md#destroy)) the pin remains set but resolution at tier 2 yields a frame-id that no longer names a registered frame. Subsequent calls hit the destroyed-frame surface contract of [§Surface behaviour against destroyed frames](#surface-behaviour-against-destroyed-frames) — read-shaped surfaces return empty shapes, mutating-shaped surfaces emit `:rf.error/no-such-handler` (kind `:frame`). The pair tool SHOULD surface this state distinctly from the tier-4 ambiguity case so the user knows to call `reset-operating-frame!` or `select-frame!` to recover.

### Ambiguity surface — tier-4 behaviour

When resolution yields nil, the pair tool refuses the op rather than guessing. The refusal shape is **asymmetric by intent** — read-shaped and mutating-shaped ops both refuse, but pair tools may relax the read-side refusal for one-shot reads against an explicit override (tier 1), since the override IS the disambiguation.

| Op class | Examples | Behaviour at tier 4 |
|---|---|---|
| **Mutating** (writes that drive a cascade or replace `app-db`) | `pair-dispatch!`, `pair-dispatch-sync!`, `reset-frame-db!`, `restore-epoch` | **Refuse**. Return `{:ok? false :reason :ambiguous-frame :hint <message>}` (or raise `(ex-info "ambiguous frame" {:reason :ambiguous-frame})` for callers that want exceptions). The op MUST NOT silently default to `:rf/default` — a write that lands in the wrong frame is unrecoverable without `restore-epoch`, and the cascade may have already fired effects. |
| **Reading** (snapshot reads, sub samples, epoch reads, sub-cache reads) | `get-frame-db`, `snapshot-of`, `subs-sample`, `epoch-history`, `sub-cache`, `app-schemas` | **Refuse**. Same shape as mutating refusal — return `{:ok? false :reason :ambiguous-frame :hint <message>}`. A silent default to `:rf/default` would read from the wrong frame, and a multi-frame user is unlikely to want the default frame's data. The `:hint` SHOULD direct the user at `select-frame!` or the explicit-override path. |
| **Registry-wide** (no frame-id needed) | `(rf/frame-ids)`, `(rf/handlers kind)`, `(rf/machines)`, `(rf/handler-meta kind id)`, `(rf/trace-buffer opts)` (when no `:frame` filter is applied) | **Proceed**. These ops query global registry / global trace state and have no operating-frame concept; they bypass the resolver entirely. |

The **uniform refusal shape across reads and writes** is the resolution committed here (the shipped impl in `re-frame-pair2.runtime`, landed in [rf2-19xl](https://github.com/day8/re-frame2/pull/190), already follows this stricter posture). A tool MAY relax read-side refusal for ops that take an explicit override at the call site — tier 1 *is* the disambiguation, so a `(get-frame-db :stories)` call with the explicit `frame-id` argument MUST NOT refuse even when no session pin is set. The refusal applies to the *zero-arg-defaults-to-operating-frame* form, where the resolver would have to invent a frame.

### Tool-surface obligations

Pair-shaped tools that implement the operating-frame contract MUST expose three operations on their tool surface (names are illustrative; the shape is what's normative):

- **Set the operating frame.** A call that pins a frame-id for the session (`select-frame!`, `set-operating-frame!`, etc.). The op SHOULD validate that the frame-id names a currently-registered frame at call time — passing an unknown frame returns `{:ok? false :reason :no-such-frame}`. Pinning a frame that is later destroyed surfaces via the destroyed-frame contract above, not at pin time.
- **Reset the operating frame.** A call that clears the session pin (`reset-operating-frame!`, `unselect-frame!`, etc.). After reset, subsequent ops resolve at tier 3 or 4 again.
- **Inspect the operating frame.** A read returning the **resolved** operating frame (or nil when ambiguous) plus the **pinned** selection (when distinct from resolved) plus the **all-registered** frame list (so callers can pick a target). The reference impl shape:

```clojure
{:ok?       true
 :frames    [<frame-id> ...]   ;; (rf/frame-ids)
 :selected  <frame-id|nil>     ;; tier-2 session pin
 :operating <frame-id|nil>}    ;; result of full resolution (nil = ambiguous)
```

The triple-shape lets a tool UI render both "you have pinned X" (the selection) and "writes will go to X" (the resolved frame) — useful when the two diverge (e.g. tier-1 override on the current call, or sole-registered tier-3 fallthrough that the user hasn't explicitly chosen).

**MCP / RPC surfacing.** Tools that expose pair surfaces over MCP / RPC SHOULD enumerate the three ops in their tool catalogue under stable names (typically `set-operating-frame`, `reset-operating-frame`, `get-operating-frame`). The runtime contract does not pin the wire names — only the semantics — but cross-tool consistency lets a user trained on re-frame-pair2 carry the mental model to re-frame-pair-improver, Causa, or Story without relearning the resolver.

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
(get-frame-db :rf/default)               ;; reads default explicitly

;; Clear the pin; back to tier-4 refusal.
(reset-operating-frame!)
(subs-sample [:cart/total])
;; => {:ok? false :reason :ambiguous-frame ...}
```

The example exercises every tier — explicit override (tier 1), session pin (tier 2 binding and re-use, plus tier-1 supersession), and tier-4 refusal both before and after reset. Single-frame apps never reach the refusal path.

## How AI tools attach

The runtime contract above is **complete for the listed capabilities.** A pair-shaped tool — re-frame-pair, a Claude integration, a custom debug panel, a story tool, a future pair-improver — attaches to a running re-frame2 application using only the framework primitives listed below. **No re-frame-10x dependency is required**, and none should be assumed. Mutating writes (state injection, hot-swap, override, configure) are commited explicitly in the table; the full set is closed at v1 and additional mutating surfaces require a Spec-ulation increment.

The full attachment surface, from the tool's point of view:

| Need | Surface | Spec |
|---|---|---|
| Receive live trace events | `(rf/register-trace-cb! :my-tool callback)` | [009 §The listener API](009-Instrumentation.md#the-listener-api) |
| Receive per-drain assembled epoch records | `(rf/register-epoch-cb! :my-tool callback)` | [009 §The listener API](009-Instrumentation.md#the-listener-api) |
| Read recent trace history (events that already fired) | `(rf/trace-buffer)` (with optional filter map) | [009 §Retain-N trace ring buffer](009-Instrumentation.md#retain-n-trace-ring-buffer-dev-only) |
| Read epoch history per frame | `(rf/epoch-history frame-id)` | [§Time-travel](#time-travel-epoch-snapshots-and-undo) |
| Restore an epoch | `(rf/restore-epoch frame-id epoch-id)` | [§Time-travel](#time-travel-epoch-snapshots-and-undo) |
| Inject an `app-db` value (state injection / story / repro) | `(rf/reset-frame-db! frame-id new-db)` | [§Pair-tool writes](#pair-tool-writes--state-injection) |
| Configure history depth | `(rf/configure :epoch-history {:depth N})` and `(rf/configure :trace-buffer {:depth N})` | [API.md](API.md) |
| Inspect registered app-db schemas | `(rf/app-schemas frame-id)` | [010 §Schemas as a tooling and agent surface](010-Schemas.md#schemas-as-a-tooling-and-agent-surface) |
| Tag dispatches by actor (e.g. tool vs app) | `:origin` opt on `(rf/dispatch event opts)` | [002 §Dispatch origin tagging](002-Frames.md#dispatch-origin-tagging) |
| Correlate a dispatch cascade | `:dispatch-id` + `:parent-dispatch-id` on `:event/dispatched` traces | [009 §Dispatch correlation](009-Instrumentation.md#dispatch-correlation-dispatch-id--parent-dispatch-id) |
| Enumerate frames | `(rf/frame-ids)`, `(rf/frame-meta id)` | [002 §Public registrar query API](002-Frames.md#the-public-registrar-query-api) |
| Read a frame's app-db | `(rf/get-frame-db frame-id)` / `(rf/snapshot-of path opts)` | [002 §Public registrar query API](002-Frames.md#the-public-registrar-query-api) |
| Inspect the registry | `(rf/handlers kind)`, `(rf/handler-meta kind id)` | [001](001-Registration.md), [002](002-Frames.md) |
| Enumerate machines | `(rf/machines)`, `(rf/machine-meta id)` | [005 §Querying machines](005-StateMachines.md#querying-machines) |
| Inspect the sub-cache (CLJS-only) | `(rf/sub-cache frame-id)` | [002 §Public registrar query API](002-Frames.md#the-public-registrar-query-api) |
| Source coords for any registration | `:ns`/`:line`/`:column`/`:file` keys on `(handler-meta ...)` return; shape `:rf/source-coord-meta` per [Spec-Schemas](Spec-Schemas.md#rfsource-coord-meta) | [001 §Source-coordinate capture](001-Registration.md#source-coordinate-capture-cljs-reference) |
| Dispatch | `(rf/dispatch event opts)` / `(rf/dispatch-sync event opts)` | [002 §Routing](002-Frames.md#routing-the-dispatch-envelope) |
| Stub fx for an experiment | `:fx-overrides {:http stub-id}` on `dispatch` opts | [002 §Per-frame and per-call overrides](002-Frames.md#per-frame-and-per-call-overrides) |
| Hot-swap a handler | Re-call `(rf/reg-event-fx id ...)`; `:rf.registry/handler-replaced` trace fires | [001 §Hot-reload semantics](001-Registration.md#hot-reload-semantics) |
| REPL eval against the runtime | The host's REPL (nREPL+CIDER for CLJS); private namespaces are off-contract | [§REPL-eval](#repl-eval) |

> **Platform-availability note.** Rows tagged "(CLJS-only)" — `(rf/sub-cache frame-id)` is the load-bearing example — are CLJS-host-only surfaces; JVM hosts (SSR, headless tests, conformance runners) ship no equivalent. Pair tools driving JVM-side test runs MUST gate the call (e.g. `(when (cljs-host?) (rf/sub-cache frame-id))`) — JVM-host return shape is not yet specified (tracked separately) and consumers should not assume nil-vs-throw across hosts. Surfaces NOT tagged "(CLJS-only)" are portable by design.

The consumption pattern is therefore:

> **A pair-shaped tool registers as a trace listener (and/or as an epoch listener for assembled per-cascade records), reads recent history from the trace buffer, queries the registrar for shape, walks the epoch history for time-travel, and dispatches into frames to drive experiments. That's the entire surface.**

Two listener shapes coexist by design: `register-trace-cb!` is the **raw** stream — every event the runtime emits, fine-grained — used by tools that need per-emit detail (custom recorders, error-monitor forwarders, timing aggregators). `register-epoch-cb!` is the **assembled** stream — one fully-shaped `:rf/epoch-record` per drain-settle, with the structured `:sub-runs` / `:renders` / `:effects` projections already computed — used by tools that route diagnostics off "what just happened in this cascade" rather than reconstructing it from the raw trace each time. Pair-shaped tools typically prefer the assembled stream for routing and reach for the raw stream only when they need detail the projection drops.

This is **dev-only** end-to-end — every primitive listed above elides in production builds (per [009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code)). Pair-shaped tools do not ship in production binaries.

### Subscribing to a slice of the trace stream

`register-trace-cb!` callbacks see *every* trace event. Tools that only care about a single subsystem filter inside the callback by `:op-type` — the universal discriminator (per [009 §`:op-type` vocabulary](009-Instrumentation.md#op-type-vocabulary)). The pattern is one-key dispatch on the event:

```clojure
;; A tool (Causa's flow panel, a pair-tool flow inspector,
;; a custom dashboard) subscribes to JUST the flow trace stream.
;; Per Spec 009 §Flow trace events, every flow lifecycle event carries
;; :op-type :flow with the per-event identity in :operation
;; (:rf.flow/registered, :rf.flow/computed, :rf.flow/skip,
;; :rf.flow/cleared, :rf.flow/failed).

(rf/register-trace-cb!
  :my-tool/flow-panel
  (fn [ev]
    (when (= :flow (:op-type ev))
      (case (:operation ev)
        :rf.flow/registered  (track-flow-registration! ev)
        :rf.flow/computed    (record-flow-computation! ev)
        :rf.flow/skip        (note-skip! ev)               ;; (per rf2-719e value-equal recompute suppression)
        :rf.flow/cleared     (drop-flow-state! ev)
        :rf.flow/failed      (surface-flow-error! ev)
        nil))))
```

The same pattern works for any subsystem with a dedicated op-type — `:machine` for state-machine activity, `:event` for the dispatch / drain stream, `:sub/run` and `:sub/create` for subscription work, `:fx` for effect handlers. New op-types are additive (per [009 §Open shape; new fields are additive](009-Instrumentation.md#open-shape-new-fields-are-additive)); tools ignore op-types they don't understand.

For per-cascade structured projections (sub-cache hit/miss, render attribution, effect outcome), tools route off `register-epoch-cb!`'s assembled `:rf/epoch-record` instead — the §[Time-travel](#time-travel-epoch-snapshots-and-undo) projection slots already pre-fold the per-cascade trace into the `:sub-runs` / `:renders` / `:effects` shape. The raw-stream filter pattern above is the right shape for fine-grained per-event consumption.

### Subscribing to assembled epoch records

`register-epoch-cb!` callbacks fire **once per drain-settle**, with the cascade's `:sub-runs` / `:renders` / `:effects` projections already computed. Pair-shaped tools, post-mortem dashboards, and "what just happened?" probes typically consume this shape rather than re-folding the raw trace stream:

```clojure
;; A pair-tool dashboard routing diagnostics off the assembled per-cascade record.
;; - One callback per drain-settle (NOT per emitted trace event).
;; - The record is fully shaped: :db-before, :db-after, :sub-runs, :renders, :effects.
;; - The record has already been appended to (rf/epoch-history (:frame ev)).

(rf/register-epoch-cb!
  :my-tool/dashboard
  (fn [{:keys [frame event-id epoch-id sub-runs renders effects] :as record}]
    ;; Cache-hit-vs-rerun: every entry in :sub-runs is a recompute (rf2-7e2y);
    ;; cache-hit subs are absent. Counting :sub-runs answers
    ;; "how many subs moved this cascade?"
    (record-recomputes! frame event-id (count sub-runs))

    ;; Render attribution: :renders[*].:render-key is [<view-id> <instance-token>].
    ;; Aggregate by first slot to count "view X re-rendered N times this cascade."
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

- **Listener exceptions are caught.** A throw inside the callback does not propagate to the framework or other listeners (per [009 §register-epoch-cb! invocation rules](009-Instrumentation.md#register-epoch-cb--assembled-epoch-listener)). The framework does **not** auto-evict the throwing listener — repeated throws keep the registration in place; eviction is the consumer's call.
- **Re-entrant dispatch from a callback.** A callback that calls `(rf/dispatch …)` enqueues the new event; the new dispatch's drain begins on stack-unwind from the current callback fan-out, not before. Other registered epoch listeners still receive the *current* record before the re-entrant dispatch begins.
- **`(rf/configure :epoch-history {:depth 0})` and listeners.** Setting depth to 0 disables the per-frame ring buffer (so `(rf/epoch-history frame-id)` returns `[]`) but does **not** stop epoch listeners from firing — `register-epoch-cb!` callbacks continue to receive the assembled record on every drain-settle. Tools that need the assembled stream without retaining history should set depth `0` and consume via `register-epoch-cb!` only.
- **Frame-destroyed mid-observation.** Tool-Pair surface behaviour against destroyed frames (epoch-history reads, in-flight epoch-cb deliveries, restore against a now-destroyed frame, listener silencing) is closed in [§Surface behaviour against destroyed frames](#surface-behaviour-against-destroyed-frames). Read-shaped surfaces return empty shapes; mutating-shaped surfaces raise `:rf.error/no-such-handler` (kind `:frame`); a previously-firing callback whose observed frame is destroyed receives a one-shot `:rf.epoch.cb/silenced-on-frame-destroy` trace.

### Implications for downstream tools

- **re-frame-pair** (the upstream nREPL companion) consumes only the surfaces above. It depends on re-frame2; it does not depend on re-frame-10x.
- **Causa** (the structural successor to re-frame-10x; Maven coord `day8/re-frame2-causa`) is built as a renderer of the same surfaces — a registered trace listener, a consumer of `epoch-history`, a query consumer of the registrar, a UI on top. Causa and pair share the substrate; one does not depend on the other.
- **Custom debug panels, story tools (Spec 007), and pair-improver-style skills** consume the same surface. Multi-tool coexistence is the expected default — multiple `register-trace-cb!` keys, multiple readers of the trace buffer, multiple consumers of the registrar. Listener ordering is not contract (per [009 §Listener ordering](009-Instrumentation.md#listener-ordering)).

The framework is **infrastructure-complete** for AI-tool consumption: data shapes, query APIs, retention policies, configuration knobs, production elision. Downstream tools own *presentation and orchestration*; they do not need to ship infrastructure that should live in the framework.

### Wire-protocol mechanisms (MCP-tool layer, not framework)

A pair-shaped tool reaching an AI agent over MCP must shape the payload at the wire boundary — a runtime snapshot, an epoch record, or a trace slice is routinely far larger than the agent's per-call budget can absorb. The mechanisms that solve this (token-budget cap, path slicing, cursor pagination, lazy summary, structural dedup, size-elision wire markers, and — pair2-mcp-specific — diff-encoded `:db-after` and streaming subscribe byte+event budgets) live **at the MCP-server layer**, not in this Spec. Tool-Pair.md commits to the framework surfaces (data shapes, query APIs, listener APIs); how an MCP tool packages those surfaces for an agent is downstream.

The cross-MCP catalogue is normative and shared across the re-frame2 MCP triplet (pair2-mcp, causa-mcp, story-mcp). Canonical homes:

- [`spec/Cross-Cutting-Designs.md §3 Token budgets`](Cross-Cutting-Designs.md) — the cross-cutting design problem statement and the index of canonical homes below.
- [`tools/mcp-conformance/TOKEN-BUDGETS.md`](../tools/mcp-conformance/TOKEN-BUDGETS.md) — the cross-server contract: default cap (5,000 tokens), per-call `max-tokens` override slot, `{:rf.mcp/overflow ...}` reserved marker, agent-host retry contract, chained-budget rules when an agent attaches multiple servers in one session.
- [`tools/pair2-mcp/spec/Principles.md §Tight token budget per response`](../tools/pair2-mcp/spec/Principles.md) — pair2-mcp's eight-mechanism expansion (cap → path slicing → per-tool budget → diff encoding → dedup → size elision → cursor pagination → streaming subscribe byte+event budget) in pipeline order.
- [`tools/causa-mcp/spec/004-Wire-Pipeline.md §Tight token budget per response`](../tools/causa-mcp/spec/004-Wire-Pipeline.md) — causa-mcp's six-mechanism sibling lock (mechanisms 1-6 align cross-server so an agent learning a slot on one server gets the same slot on the others).
- [`spec/Conventions.md §Reserved namespaces (framework-owned)`](Conventions.md#reserved-namespaces-framework-owned) — the framework-side reservation of the `:rf.mcp/*` and `:rf.size/*` keys that appear on the wire (`:rf.mcp/overflow`, `:rf.mcp/summary`, `:rf.mcp/dedup-table`, `:rf.mcp/diff-from`, `:rf.size/large-elided`).

The framework owns the *data*; the wire-protocol layer owns the *packaging*. Findings docs and downstream Specs reaching for the mechanism catalogue link to the MCP-server homes above, not back into this Spec.

#### MCP-side wire-marker vocabulary

The mechanisms above emit a small family of namespaced wire markers — replacement envelopes that pair-shaped tools wrap a payload in when a budget / dedup / cache rule fires. Every marker rides as a top-level map keyed on a `:rf.mcp/*` or `:rf.size/*` keyword (per [Conventions.md §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)); agent hosts pattern-match on the key to branch their UI / retry / drill-in flows. Each artefact's own Spec mentions a subset; the family in one place:

| Marker key | Mechanism | Emitted by | Shape (top-level keys) |
|---|---|---|---|
| `:rf.mcp/overflow` | Wire-cap (rf2-rvyzy) | Post-eval cap step replaces an over-budget payload. | `:limit :reached`, `:token-count`, `:cap-tokens`, `:tool`, `:hint`. |
| `:rf.mcp/summary` | Lazy-summary mode (rf2-u2029) | `snapshot` replaces each `:summary`-mode rich slice. | `:type`, `:keys`, `:count`, `:bytes`. |
| `:rf.mcp/dedup-table` | Structural dedup (rf2-obpa9) | Every epoch / events vector emitter wraps post-encoded payload. | `:de-dupe.cache/cache-0`, `:de-dupe.cache/cache-1`, … (flat). |
| `:rf.mcp/diff-from` | Diff-encoded `:db-after` (rf2-1wdzp) | Each epoch's `:db-after` is replaced with an intra-record diff. | `:rf.mcp/diff-from`, `:patches`. |
| `:rf.mcp/cache-hit` | Per-session response cache (rf2-3rt1f / rf2-36xod) | Wire-boundary cache replaces a byte-identical re-emit. | `:hash`, `:unchanged-since`, `:tool`, `:via` (`:result-hash` / `:precheck`), `:hint`. |
| `:rf.size/large-elided` | Size-elision walker (rf2-urjnc, rf2-9fz64) | `rf/elide-wire-value` substitutes over-threshold leaves. | `:path`, `:bytes`, `:type`, `:reason`, `:hint`, `:handle` (`[:rf.elision/at <path>]`). |
| `:rf.mcp/cursor-stale` | Cursor pagination (rf2-kbqq3) | `trace-window` / `watch-epochs` refuse a cursor whose `:epoch-id` aged out of the ring. | `:reason :rf.mcp/cursor-stale`, `:tool`, `:requested-id`, `:head-id`. |
| `:rf/redacted` | Privacy walker (`with-redacted`, spec/009 §Privacy) | The framework-side redact walker replaces named keys at emit time. | Scalar sentinel (no map shape). |

Agents that learn the family see each new slot as one more case in the same pattern-match — the namespaced first key is the discriminator. New mechanisms add to the table; existing markers are stable (additive: new optional keys, never removed / renamed).

### Direct-read privacy posture for `sub-cache` and `get-path`

Most Tool-Pair surfaces ride the trace bus (`register-trace-cb!` / `register-epoch-cb!`) or the event-emit substrate, where `:sensitive?` stamps and size markers are applied at the emit boundary. Two surfaces in the attachment table above do **not** ride that path: `(rf/sub-cache frame-id)` and the MCP-server `get-path` tool (a direct read-by-path against `(rf/get-frame-db frame-id)`). Both are **synchronous reads** of live runtime state — the sub-cache map, an arbitrary path into `app-db` — and the `:sensitive?` trace stamp protects only the *trace* surface. A direct read returns the live value untransformed unless the wire-egress boundary scrubs it.

The framework's wire-egress walker is `rf/elide-wire-value` (per [API.md §Size-elision wire-boundary walker](API.md#elide-wire-value-the-wire-boundary-walker)) — the **single normative emission site** for `:rf/redacted` (sensitive) and `:rf.size/large-elided` (oversize) markers. This subsection pins the contract that every MCP-server (or other off-box forwarder) implementing `sub-cache` or `get-path` surfaces must honour, so a future causa-mcp / story-mcp / third-party server shipping the same tool names inherits the same posture pair2-mcp ships today.

**Normative contract.**

- A pair-shaped tool that ships a `sub-cache` surface (the direct read of `(rf/sub-cache frame-id)` per [§How AI tools attach](#how-ai-tools-attach)) **MUST** route the returned `{query-v {:value v :ref-count n}}` map through `rf/elide-wire-value` before the value crosses the wire egress. Off-box defaults apply: `:rf.size/include-sensitive?` and `:rf.size/include-large?` both default `false`, so a sub whose query-v lands on a declared-`:sensitive?` path (or whose `:value` is a slot flagged `:sensitive? true` via `[:rf/elision :declarations]`) returns the `:rf/redacted` sentinel; a `:value` exceeding `:rf.size/threshold-bytes` returns the `:rf.size/large-elided` marker. The composition rule of [API.md §Size-elision wire-boundary walker](API.md#elide-wire-value-the-wire-boundary-walker) applies — when both predicates match the **sensitive drop wins** (the size marker is suppressed because it would leak `:path` / `:bytes` / `:digest`).
- A pair-shaped tool that ships a `get-path` surface (the direct read of `(get-in (rf/get-frame-db frame-id) path)`) **MUST** route the resolved value through `rf/elide-wire-value` before egress, passing `:path path` and `:frame frame-id` in the opts so the elision marker's `:handle` slot carries `[:rf.elision/at <path>]` and the agent can drill into a non-elided child by re-calling `get-path` with a deeper segment. Off-box defaults apply identically to `sub-cache` above. The walker reads the live `[:rf/elision :declarations]` and `[:rf/elision :runtime-flagged]` registries from the named frame's `app-db` — it MUST therefore run app-side (server-side, where the registry is reachable), not in the MCP server's host process.
- Both surfaces **MUST** honour the opt-in escape hatches per the cross-MCP convention (rf2-vw4sq): `:include-sensitive? true` on the MCP call opts forwards `:sensitive? true` slots verbatim, and `:elision false` (or equivalent) bypasses the walker entirely. The escape hatches are **off by default** — a tool that omits the opts gets the elided posture. Apps that need the raw value at a sensitive path are responsible for explicitly opting in at the call site, the same posture the trace-stream forwarders take.

**Why direct reads need explicit elision.** The `:sensitive?` declaration on a registration (per [009 §Privacy / sensitive data in traces](009-Instrumentation.md#privacy--sensitive-data-in-traces)) and the `with-redacted` interceptor both shape what the *trace surface* emits — `:sensitive?` stamps the trace event, `with-redacted` overwrites named payload keys with `:rf/redacted` before the handler chain runs. Neither touches the live `app-db` or the live sub-cache. A direct read against either bypasses both mechanisms by design: the trust boundary is **the trace surface plus the MCP egress**, not the app-db itself. Without an explicit elision step at the egress, a `sub-cache` or `get-path` call would return the live value verbatim — including any sensitive slot declared `:sensitive?`, and including a `:value` larger than the wire budget can absorb. The MUST contract above closes that gap at the MCP-server layer where every direct-read surface lives.

**Reference impl: pair2-mcp.** The `tools/pair2-mcp` server already honours the `get-path` half of this contract — `get-path-tool` wraps the resolved value in a `(re-frame.core/elide-wire-value v {:path path :frame <fid> ...})` call before returning to the client (per `tools/pair2-mcp/src/re_frame_pair2_mcp/tools.cljs`, rf2-urjnc). The `snapshot` tool likewise wraps its `:app-db` slice. The `:sub-cache` slice of `snapshot` is the surface a future implementation update lands the same wrapper on (and any third-party `sub-cache` tool MUST land it from day one) — the contract here is the forward-looking pin.

**Defence in depth — what this contract does and does not displace.** This subsection commits the **wire-egress** posture for direct reads. It does **not** displace path-level privacy mechanisms upstream of the read:

- Apps that need fine-grained app-db privacy continue to use `with-redacted` on the **writing** handler so the trace stream's `:db-after` projection is redacted at write time, regardless of subsequent reads.
- Apps that need stronger guarantees keep sensitive values **out of `app-db`** entirely (host-side keychain, IndexedDB with separate-origin access, in-memory only) — the contract above protects the wire, not the in-process value.
- A future framework-side path-level privacy mechanism (potential post-v1 surface; see [009 §Privacy / sensitive data in traces](009-Instrumentation.md#privacy--sensitive-data-in-traces) for the design space) would compose under the same wire-egress contract — `elide-wire-value` is the single normative emission site, so any future tightening of the path-level surface flows through the same walker without changing the MCP-server obligation here.

**Cross-references:** [API.md §Size-elision wire-boundary walker](API.md#elide-wire-value-the-wire-boundary-walker) (the walker contract), [API.md §Privacy](API.md#privacy-spec-009-privacy--sensitive-data-in-traces) (`:sensitive?` stamp + `with-redacted` interceptor), [009 §Privacy / sensitive data in traces](009-Instrumentation.md#privacy--sensitive-data-in-traces), [Conventions.md §Reserved namespaces (framework-owned)](Conventions.md#reserved-namespaces-framework-owned) (the `:rf.size/*` / `:rf.elision/*` wire keys).

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
- **Pair-improver feedback loop.** The [re-frame-pair-improver](https://github.com/day8/re-frame-pair-improver) skill (post-v1) consumes a structured session log; format is EDN/JSON with a schema in [Spec-Schemas](Spec-Schemas.md).

## Resolved decisions

### Multi-frame operating-frame resolution — hybrid posture (rf2-guivm)

Resolved: pair-shaped tools resolve a multi-frame application's operating frame via the four-tier rule pinned at [§Operating frame — multi-frame resolution](#operating-frame). The shipped impl in `re-frame-pair2.runtime` (per [rf2-19xl](https://github.com/day8/re-frame2/pull/190)) is the canonical reference — `current-frame` walks **explicit override → session pin → sole-registered frame → nil**, and both read-shaped (`subs-sample`, `snapshot`, `epoch-history`, …) and mutating-shaped (`pair-dispatch-sync!`, `app-db-reset!`, `restore-epoch`) ops refuse with `{:ok? false :reason :ambiguous-frame}` when resolution yields nil. `:rf/default` is not a tier-4 fallback — silently routing to it would mask the ambiguity in a multi-frame session. The hybrid posture (explicit-context-set, implicit-until-reset) supersedes the earlier "Lean" note in §Design notes; single-frame applications never reach the refusal path. The tool surface MUST expose `set-operating-frame` / `reset-operating-frame` / `get-operating-frame` ops (names illustrative; semantics normative) so multi-frame users can pin a target, clear the pin, and inspect the resolver's view. See [§Operating frame — multi-frame resolution](#operating-frame) for the full contract.
