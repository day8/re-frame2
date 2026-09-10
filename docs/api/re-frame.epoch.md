# re-frame.epoch

`re-frame.epoch` is the per-frame epoch-history surface: dev-only time-travel and post-mortem. On each handled event, the runtime records one `:rf/epoch-record` into a per-frame ring buffer. Recording happens at the event's run-to-completion boundary, not once per drain. Each record captures before/after frame-state, the triggering event, and the harvested trace stream. Pair-shaped tools (Xray, re-frame2-pair, Story) read this history. Production builds (`:advanced` + `goog.DEBUG=false`) elide the whole surface: no allocation, no storage, no overhead.

```clojure
(:require [re-frame.epoch :as epoch])
```

**There is one public grammar for the epoch ring: the `rf/` facade spellings.** Every var in `re-frame.epoch` is an implementation seam — a late-bind hook target the facade reaches through — so `rf/restore-epoch!` and `epoch/restore-epoch!` name the same function and the `rf/` form is the one to write. Examples below use it throughout. Three seams have no same-named facade twin and are named here in the `epoch/` form because that is their only spelling: `clear-history!` and `clear-epoch-listeners!` (fixture-grade teardown, no facade door and none wanted) and `configure!` (whose door is `(rf/configure! {:epoch-history …})`). Reading the live configuration back is the facade's job alone — `(:epoch-history (rf/current-config))`; there is no `epoch/current-config`. Listeners attach on the `:epoch` stream of the one listener verb: `(rf/register-listener! :epoch id f)` / `(rf/unregister-listener! :epoch id)`. See [Observability](../core/observability.md) for how epochs fit the broader trace model.

## Epoch history

Per-frame epoch snapshots, recorded on each handled event's run-to-completion in dev builds. Read by pair-shaped tools for time-travel and post-mortem. Production builds elide entirely.

### `epoch-history`

- **Kind**: function
- **Signature**:
  ```clojure
  (epoch-history frame-id) → vector of epoch records
  ```
- **Description**: Returns the frame's recorded `:rf/epoch-record` vector, oldest-first. Returns `[]` for an unknown / destroyed frame, or when recording is disabled (`:depth` 0).

```clojure
;; All recorded epochs for a frame, oldest-first; peek the latest.
(rf/epoch-history :app/main)
(last (rf/epoch-history :app/main))
```

### Resetting the ring between tests

`(epoch/clear-history!)` drops every recorded epoch for every frame, plus any in-flight per-frame capture buffer, so each fixture's drain starts from a fresh capture state — without it, a leftover mid-flight buffer is picked up by the next fixture's first run. It is **test-support only**: an implementation seam with no facade door and none wanted, fired by `re-frame.test-support`'s reset-hook table (`:epoch/clear-history!`). Applications have no reason to call it.

```clojure
;; Reset epoch state between test fixtures.
(epoch/clear-history!)
```

## Time-travel

### `restore-epoch!`

- **Kind**: function
- **Signature**:
  ```clojure
  (restore-epoch! frame-id epoch-id) → boolean
  ```
- **Description**: Restores the frame's whole frame-state to the named epoch's `:frame-state-after` in one atomic write. Both partitions rewind, app-db and runtime-db alike. Machine snapshots, the route slice, and other runtime-db material travel back too, not just the application slice.
    - Returns `true` on success and emits `:rf.epoch/restored`.
    - Returns `false` on any failure. Each failure is a no-op on frame-state and emits a structured error trace:
        - `:rf.error/no-such-handler` (kind `:frame`) — frame not registered / destroyed
        - `:rf.epoch/restore-during-drain` — called while a drain is in flight
        - `:rf.epoch/restore-unknown-epoch` — epoch-id not in the frame's current history
        - `:rf.epoch/restore-non-ok-record` — target epoch's `:outcome` is not `:ok` (halted-cascade records carry partial state and are not valid restore targets)
        - `:rf.epoch/restore-schema-mismatch` — the recorded app-db no longer validates against the frame's registered app-schemas
        - `:rf.epoch/restore-missing-handler` — a machine / route referenced from the recorded runtime-db is no longer registered
        - `:rf.epoch/restore-version-mismatch` — machine snapshot version drift against the current definition

```clojure
;; Time-travel: rewind a frame's whole frame-state to a recorded epoch.
(let [target (last (rf/epoch-history :app/main))]
  (rf/restore-epoch! :app/main (:epoch-id target)))
```

### `replay-epoch!`

- **Kind**: function
- **Signature**:
  ```clojure
  (replay-epoch! frame-id epoch-id)      → envelope map (false when elided)
  (replay-epoch! frame-id epoch-id opts) → envelope map (false when elided)
  ```
- **Description**: Re-drives the named retained epoch's recorded event through the frame's own handlers in one call, as a strict replay. The record is resolved in-process and its replay material is folded into the dispatch: the raw argument-bearing `:trigger-event`, the recorded post-generation `:rf.cofx` under `:rf.cofx/mint-policy :strict`, and the record's own `:fx-overrides` / `:interceptor-overrides`. Nothing is exported or copied by hand — which is what makes replay available to an off-box tool, since the projected record only ever shows event arguments as `:rf/redacted`. Same frame in and out. Replay runs against the frame's current state and code, so it does not restore first (compose with `restore-epoch!` when the start state matters), any effect the handler emits fires again, and the replayed dispatch records a new ordinary epoch; re-presenting the recorded `:rf/time-ms` makes that epoch's `:committed-at` equal the original's.
    - Returns `{:ok? true :frame … :source-epoch-id … :event-id … :epoch-id <the new epoch>}` on success. `:epoch-id` names the epoch the replayed event itself committed — never a queued child's record — and is `nil` when the ring did not retain it (a replay may enqueue work the recorded run did not, and at a shallow `:depth` a child can evict its own parent's record).
    - Returns `{:ok? false :reason … :frame … :epoch-id … <tags>}`, decided before anything dispatches and without a trace emit, for:
        - `:rf.error/no-such-handler` (kind `:frame`) — frame not registered / destroyed
        - `:rf.epoch/replay-during-drain` — called while a drain is in flight
        - `:rf.epoch/replay-unknown-epoch` (`:history-size`) — epoch-id not in the frame's current history
        - `:rf.epoch/replay-non-replayable-record` (`:cause` — `:halted`, `:synthetic`, `:missing-trigger-event`, `:missing-replay-token` or `:incomplete-inputs`)
        - `:rf.epoch/replay-unreplayable-fx-override` (`:fx-ids`) — a recorded `:fx-overrides` entry is the `:rf/fn-override` sentinel
    - `:incomplete-inputs` is the capture-loss refusal: a `reg-event` / `reg-cofx` `:sensitive` / `:large` declaration is applied at trace capture, so a declared event argument or recordable fact reaches the retained record already carrying `:rf/redacted` or a `:rf.size/large-elided` marker. Replay is faithful-or-fail-loud, so the record is refused rather than re-driven with the substitution in place of the value the original run consumed; `:lost` names each `{:slot :path :loss}`.
    - A declared recordable fact absent from the recorded token is not a refusal: the dispatch fails loud with the canonical `:rf.error/missing-required-cofx`, exactly as any `:strict` dispatch does. Nothing is minted.
    - `opts` carries only the slots replay does not own (`:origin`, `:source`, `:trace-id`); a value under `:frame`, `:rf.cofx`, `:rf.cofx/mint-policy`, `:fx-overrides` or `:interceptor-overrides` is discarded.

```clojure
;; Replay the most recent epoch through the app's own handlers, strictly.
(let [source (last (rf/epoch-history :app/main))]
  (rf/replay-epoch! :app/main (:epoch-id source)))
;; => {:ok? true :frame :app/main :source-epoch-id 41 :event-id :cart/add :epoch-id 42}
```

## Pair-tool writes

`replace-frame-state!` is the ONE state-injection surface. It replaces a frame's partitions directly, bypassing the dispatch loop. It records a synthetic `:rf/epoch-record`, so `restore-epoch!` can rewind past the injection, and emits `:rf.epoch/db-replaced` on success. Dev-only; production builds elide it.

### `replace-frame-state!`

- **Kind**: function
- **Signature**:
  ```clojure
  (replace-frame-state! frame-id new-frame-state) → boolean
  ```
- **Description**: The ONE frame-state write surface. API-shrink #3 (rf2-t3lftq) consolidated the former four-mutator family — `replace-app-db!` / `reset-app-db!` / `replace-runtime-db!` / `replace-frame-state!` — into this single fn. Those four shared identical machinery and differed only in which partition keys they touched. `new-frame-state` is a PARTIAL frame-state map: any subset of `{:rf.db/app … :rf.db/runtime …}`. A present key replaces that partition; an ABSENT key is preserved unchanged. A db-shaped key never silently touches the other partition. Bypasses the dispatch loop. Returns `true` on success. No-ops returning `false` (each emitting a structured trace) on:
    - `:rf.error/replace-frame-state-bad-keys` — the map carries no recognized partition key, or an unrecognized key (checked BEFORE frame resolution)
    - `:rf.error/no-such-handler` — frame not registered
    - `:rf.epoch/replace-during-drain`
    - `:rf.epoch/replace-schema-mismatch` — a PRESENT app-db value fails the frame's registered app-schema set, or a PRESENT runtime-db value fails the framework-owned runtime-db validator
    - `:rf.epoch/replace-history-disabled` — ring disabled at depth 0, so the synthetic undo-anchor cannot land

```clojure
;; App-only state injection — direct app-db write, runtime-db preserved
;; (the former replace-app-db!).
(rf/replace-frame-state! :app/main {:rf.db/app {:counter 0}})

;; App-only reset to {}, keeping machines / routes alive
;; (the former reset-app-db!).
(rf/replace-frame-state! :app/main {:rf.db/app {}})

;; Runtime-only injection — app-db untouched (the former replace-runtime-db!).
(rf/replace-frame-state! :app/main {:rf.db/runtime new-runtime-db})

;; Full-frame install — both partitions atomically.
(rf/replace-frame-state! :app/main {:rf.db/app {:counter 0} :rf.db/runtime {}})
```

## Epoch listeners

Assembled-epoch records are delivered through the `:epoch` stream of the one
listener verb — `(rf/register-listener! :epoch id callback-fn)` /
`(rf/unregister-listener! :epoch id)`, documented on
[re-frame.core.md](re-frame.core.md#register-listener). Apps and tools alike
attach there. `re-frame.epoch`'s own `register-epoch-listener!` /
`unregister-epoch-listener!` / `clear-epoch-listeners!` are the implementation
seam the stream delegates to (hook targets for `:epoch/register-epoch-listener!`
and siblings), not a second public spelling; the last of the three is
fixture-grade teardown. There is no `rf/register-epoch-listener!` facade
re-export — it was retired in API-shrink #4.

What the callback receives is a property of the RECORD rather than of the verb,
so it is stated here:

- `callback-fn` is invoked with the fully-assembled **raw** `:rf/epoch-record`. The callback is a record **publication** notification, not a once-per-event clock. An ordinary handled event publishes one initial record when it settles; the SAME record re-publishes — carrying the same `:epoch-id` — when a post-settle render / sub-run / unmount back-fills into that already-settled epoch (a corrected record). Non-ordinary records publish too: `:rf.epoch/db-replaced` for each `replace-frame-state!` write and a `:halted-depth` record when a drain hits the depth ceiling (both ring-retained when depth permits), plus the terminal `:halted-destroy` — an already-started event interrupted by frame destruction, delivered to listeners only and never retained (the destroyed frame's history is already gone). A dequeued event rejected before it runs (no handler) publishes nothing.
- The listener is **process-global**, but `:epoch-id` is unique only within one frame's history. Reconcile on the pair `[(:frame record) (:epoch-id record)]`: cache each record under that key and REPLACE it on re-publication, so a same-identity backfill corrects the snapshot rather than double-counting. `:outcome` is record STATE (`:ok` / `:halted-depth` / `:halted-destroy`) read off the record — not part of its identity and not an event counter. Listeners receive every record regardless of `:outcome`.
- `id` may be any comparable value; registering the same `id` twice replaces.
- Listener exceptions are caught and isolated, emitting `:rf.epoch.cb/listener-exception`. One broken listener cannot block others.
- When a frame that a callback has observed is destroyed, the framework emits a one-shot `:rf.epoch.cb/silenced-on-frame-destroy` trace for that callback. Decide whether a received signal still names a current fact with `(rf/epoch-silence-current? tags)`.

```clojure
;; Reconcile a per-[frame epoch-id] cache as records publish. Re-publication
;; (a backfilled same-identity record) REPLACES its entry, so it corrects the
;; snapshot rather than double-counting.
(def epochs (atom {}))

(rf/register-listener! :epoch :my-app/epoch-watch
  (fn [record]
    (swap! epochs assoc [(:frame record) (:epoch-id record)] record)))

(rf/unregister-listener! :epoch :my-app/epoch-watch)
```

## Off-box egress projection

Tools that forward epoch records across a process boundary must project at the wire boundary. That covers Xray-MCP `watch-epochs`, story / pair recorders, and hosted post-mortem forwarders. The on-box ring buffer and the `register-epoch-listener!` fan-out always deliver the raw record, so on-box devtools (Xray diff, REPL, `restore-epoch!`) can reason about exact state. See [keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md) for the projection model.

### The door is `rf/project-egress`

**`re-frame.epoch` publishes no egress var of its own.** Record-level egress has ONE public door — [`rf/project-egress`](re-frame.core.md#project-egress) — and an epoch record reaches its projector through its stamped `:kind :rf/epoch-record`, not through a second name. The per-kind projector is a late-bind seam (`:epoch/project-record`) inside this artefact and is never public: the door names the *boundary*, `:kind` names the *record kind*. (The former `rf/projected-record` spelling was retired 2026-09-09 under rf2-bv1p, ruling rf2-kuky.9 option A; its three epoch-only opts moved onto the door.)

- **Signature**: `(rf/project-egress record)` / `(rf/project-egress record opts)`.
- It routes the full-value payload slots (`:frame-state-before`, `:frame-state-after`, `:db-before`, `:db-after`, `:trace-events`) through the record-level egress boundary under a `:rf.egress/profile`. Sensitive paths redact to `:rf/redacted`; large paths elide to `:rf.size/large-elided` markers. The frame-state `:rf.db/runtime` partition, the structured `:effects` `:args`, and the `:trigger-event` / trace-event args all fail closed (redacted) by default. The 2-arity threads trusted-local egress `opts`; the 1-arity is the safe, fully-redacted off-box path.
- **A kindless input is a VALUE, not a no-op.** `nil`, a non-map, or a map carrying no `:kind` stamp is not short-circuited — it falls to the door's [tree-shaped value path](re-frame.core.md#project-egress) and is projected under that path's ordinary frame-resolution and classification rules. So `(mapv rf/project-egress ring)` over a ring with holes still never throws, but what a hole egresses as is whatever those rules give it — never a guaranteed `nil`. (The retired `projected-record` door short-circuited non-map input to `nil`; the one door does not.)
- **Profiles** (the primary `:rf.egress/profile` selector — *"which boundary is this?"*):
    - `:rf.egress/off-box-observability` (the epoch arm's DEFAULT) — for hosted monitoring, log shippers, Story, and pair recorders. Redacts sensitive paths, elides large ones, and omits structural digests.
    - `:rf.egress/off-box-tool` — the MCP / AI / tool wire. Same redact/elide defaults, but includes structural marker indicators (`:digest`) so a tool can reason about an elided large slot's shape. An unknown profile is rejected against the closed enum.
- The advanced per-call inclusion overrides (`:rf.egress/include-sensitive?` / `:rf.egress/include-large?` / `:rf.egress/include-runtime-db?` / `:rf.egress/include-fx-args?` / `:rf.egress/include-event-args?`, all default `false`) compose over the selected profile. All five take the `:rf.egress/*` spelling every egress door reads; the last three govern keyspaces only an `:rf/epoch-record` has, so on any other kind they are accepted and inert.
- `opts` is a **closed** twelve-key map. Any other key throws `:rf.error/bad-egress-opts` naming it, the unqualified `include-sensitive?` / `include-large?` spellings included. With the `day8/re-frame2-epoch` artefact absent, an epoch record handed to the door throws `:rf.error/epoch-artefact-missing` naming the kind rather than being bare-walked.

```clojure
;; Project an epoch record before forwarding it off-box (fully redacted).
(rf/project-egress (last (rf/epoch-history :app/main)))
;; Tool wire — include structural digests for elided slots.
(rf/project-egress record {:rf.egress/profile :rf.egress/off-box-tool})
;; The whole ring is ordinary composition.
(mapv #(rf/project-egress % opts) (rf/epoch-history :app/main))
```

## Configuration

Buffer-depth knobs for the epoch ring are set through the facade, under the `:epoch-history` key:

```clojure
(rf/configure! {:epoch-history {:depth N :trace-events-keep N}})
```

`re-frame.epoch/configure!` is the implementation seam behind it (hook target for `:epoch/configure!`); it takes the inner map directly and is not a second public spelling. The keys:

- `:depth` — non-negative integer; per-frame ring-buffer depth (default 50). `0` disables recording.
- `:trace-events-keep` — non-negative integer. Caps how many of the most-recent records per frame retain their raw `:trace-events` vector; older records keep only the cheap structured `:sub-runs` / `:renders` / `:effects` projections. Defaults to 50 (matching the default `:depth`) so trace and epoch evict atomically. Pass a smaller value to bound dev-session heap.
- There is no post-projection scrub hook. A forwarder that wants one composes it: `(-> record (rf/project-egress opts) scrub)`.
- Invalid `:depth` / `:trace-events-keep` (not a non-negative integer) are silently dropped at the boundary.

```clojure
;; Shrink the ring and bound retained raw traces for a memory-conscious host.
(rf/configure! {:epoch-history {:depth 20 :trace-events-keep 5}})
```

Read the live configuration back through the facade's own read twin, `rf/current-config` — there is no `re-frame.epoch` reader (rf2-kuky.73 deleted it in favour of the one door). The epoch map arrives under the `:epoch-history` key, and is **absent entirely** when the `day8/re-frame2-epoch` artefact is not on the classpath, rather than reported as a fabricated default:

```clojure
;; Inspect the live epoch-history configuration.
(:epoch-history (rf/current-config))     ;; => {:depth 20 :trace-events-keep 5 ...}
```

## Runtime hook

### `settle!`

- **Kind**: function (framework-internal runtime hook)
- **Signature**:
  ```clojure
  (settle! frame-id frame-state-before frame-state-after committed-at)
  (settle! frame-id frame-state-before frame-state-after committed-at outcome halt-reason)
  ```
- **Description**: The hook the router calls once per dequeued event, at each event's run-to-completion boundary — not once per drain. Framework-internal: the router invokes it; application and tool code never call it directly. Per call it:
    - harvests that event's trace buffer;
    - assembles the `:rf/epoch-record` (deriving the `:db-before` / `:db-after` app-db projections from the whole-frame-state snapshots);
    - appends it to the per-frame ring buffer;
    - emits `:rf.epoch/snapshotted` with an `:outcome` tag plus its consumer-facing companion `:rf.epoch/outcome` (`:ok` / `:blocked` / `:error`);
    - fans out to every registered listener.

    A drain that processes a parent event and an `:fx [[:dispatch …]]` child it queued commits two records, one per event. A machine macrostep stays one epoch. `committed-at` is the committing causal token's `:rf.cofx` `:rf/time-ms`, threaded down by the router rather than read from an ambient assembly-time clock; this keeps the record replayable. The 4-arity is the clean `:ok` settle, skipped when the captured buffer is empty. The 6-arity is the drain-boundary commit with an explicit outcome (`:ok` / `:halted-depth` / `:halted-destroy`).

```clojure
;; Framework-internal — the router invokes this through the :epoch/settle! late-bind hook.
(settle! :app/main fs-before fs-after committed-at)                              ; clean :ok settle
(settle! :app/main fs-before fs-after committed-at :halted-destroy halt-reason)  ; explicit-outcome commit
```

## Trace events

Trace events emitted by the epoch-history machinery:

| `:operation` | Tags |
|---|---|
| `:rf.epoch/snapshotted` | `:frame`, `:rf.epoch/id`, `:rf.trace/event-id`, `:outcome` |
| `:rf.epoch/outcome` | `:frame`, `:rf.epoch/id`, `:rf.trace/event-id`, `:outcome` (consumer-facing `:ok` / `:blocked` / `:error`) |
| `:rf.epoch/restored` | `:frame`, `:rf.epoch/id` |
| `:rf.epoch/db-replaced` | `:frame`, `:rf.epoch/id` |
| `:rf.epoch/restore-unknown-epoch` | `:frame`, `:rf.epoch/id`, `:history-size` |
| `:rf.epoch/restore-schema-mismatch` | `:frame`, `:rf.epoch/id`, `:schema-digest-recorded`, `:schema-digest-current`, `:failing-paths` |
| `:rf.epoch/restore-missing-handler` | `:frame`, `:rf.epoch/id`, `:missing` |
| `:rf.epoch/restore-version-mismatch` | `:frame`, `:rf.epoch/id`, `:machine-id`, `:version-recorded`, `:version-current`, `:machine-type` (spawned actors only) |
| `:rf.epoch/restore-during-drain` | `:frame`, `:rf.epoch/id` |
| `:rf.epoch/restore-non-ok-record` | `:frame`, `:rf.epoch/id`, `:outcome`, `:halt-reason` |
| `:rf.epoch/replace-during-drain` | `:frame` |
| `:rf.epoch/replace-schema-mismatch` | `:frame`, `:failing-paths` |
| `:rf.epoch/replace-history-disabled` | `:frame` |
| `:rf.epoch.cb/listener-exception` | `:frame`, `:cb-id`, `:rf.epoch/id`, `:message` |
| `:rf.epoch.cb/silenced-on-frame-destroy` | `:frame`, `:cb-id`, `:observed-gen` |
| `:rf.warning/restore-quiesce-hook-exception` | `:frame`, `:hook`, `:exception` |
