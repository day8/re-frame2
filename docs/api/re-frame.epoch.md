# re-frame.epoch

`re-frame.epoch` is the per-frame epoch-history surface: dev-only time-travel and post-mortem. On each dequeued event, the runtime records one `:rf/epoch-record` into a per-frame ring buffer. Recording happens at the event's run-to-completion boundary, not once per drain. Each record captures before/after frame-state, the triggering event, and the harvested trace stream. Pair-shaped tools (Xray, re-frame2-pair, Story) read this history. Production builds (`:advanced` + `goog.DEBUG=false`) elide the whole surface: no allocation, no storage, no overhead.

```clojure
(:require [re-frame.epoch :as epoch])
```

Most of this surface is re-exported on the `re-frame.core` facade, so `rf/restore-epoch!` and `epoch/restore-epoch!` name the same function. Examples below use the `rf/` form for re-exported names and the `epoch/` form for the epoch-only helpers (`clear-history!`, `current-config`, `register-epoch-listener!`, `unregister-epoch-listener!`, `clear-epoch-listeners!`, `configure!`). The epoch drain-settle **listener** is NOT a per-channel facade re-export. Its app-facing route is the `:epoch` stream of the one listener verb: `(rf/register-listener! :epoch id f)` / `(rf/unregister-listener! :epoch id)`. The `epoch/register-epoch-listener!` native form below is the same underlying registry. See [Observability](../core/observability.md) for how epochs fit the broader trace model.

## Epoch history

Per-frame epoch snapshots, recorded on each dequeued event's run-to-completion in dev builds. Read by pair-shaped tools for time-travel and post-mortem. Production builds elide entirely.

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

### `clear-history!`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-history!) → nil
  ```
- **Description**: Drops every recorded epoch for every frame, plus any in-flight per-frame capture buffer. Test fixtures use it so each fixture's drain starts from a fresh capture state. Without it, a leftover mid-flight buffer would be picked up by the next fixture's first run.

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

### `register-epoch-listener!`

- **Kind**: function
- **Signature**:
  ```clojure
  (register-epoch-listener! id callback-fn) → id
  ```
- **Description**: Registers a process-global assembled-epoch listener. Returns the `id`. The app-facing route is `(rf/register-listener! :epoch id callback-fn)`, the `:epoch` stream of the one stream-parameterized listener verb. `epoch/register-epoch-listener!` is the direct epoch-namespace form of the same registry. There is no `rf/register-epoch-listener!` facade re-export; it was retired in API-shrink #4.
  - `callback-fn` is invoked once per committed record with the fully-assembled raw `:rf/epoch-record`, after it lands in the frame's ring buffer. Listeners receive every record regardless of `:outcome`.
  - `id` may be any comparable value; registering the same `id` twice replaces.
  - Listener exceptions are caught and isolated, emitting `:rf.epoch.cb/listener-exception`. One broken listener cannot block others.
  - When a frame that a callback has observed is destroyed, the framework emits a one-shot `:rf.epoch.cb/silenced-on-frame-destroy` trace for that callback.

```clojure
;; Observe each assembled epoch as frames settle (app-facing route).
(rf/register-listener! :epoch :my-app/epoch-watch
  (fn [record]
    (js/console.log (:frame record) (:epoch-id record))))

;; Equivalent direct epoch-namespace form.
(epoch/register-epoch-listener! :my-app/epoch-watch
  (fn [record]
    (js/console.log (:frame record) (:epoch-id record))))
```

### `unregister-epoch-listener!`

- **Kind**: function
- **Signature**:
  ```clojure
  (unregister-epoch-listener! id) → nil
  ```
- **Description**: The inverse of `register-epoch-listener!`: removes the listener registered under `id`. App-facing route: `(rf/unregister-listener! :epoch id)`.
- **Example**: `(epoch/unregister-epoch-listener! :my-app/epoch-watch)`

### `clear-epoch-listeners!`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-epoch-listeners!) → nil
  ```
- **Description**: Drops every registered epoch listener. Used by test fixtures to reset the process-global listener registry between runs.

```clojure
;; Reset the listener registry between test fixtures.
(epoch/clear-epoch-listeners!)
```

## Off-box egress projection

Tools that forward epoch records across a process boundary must route through these helpers at the wire boundary. That covers Xray-MCP `watch-epochs`, story / pair recorders, and hosted post-mortem forwarders. The on-box ring buffer and the `register-epoch-listener!` fan-out always deliver the raw record, so on-box devtools (Xray diff, REPL, `restore-epoch!`) can reason about exact state. See [keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md) for the projection model.

### `projected-record`

- **Kind**: function
- **Signature**:
  ```clojure
  (projected-record record)
  (projected-record record opts)
  ```
- **Description**: Projects an `:rf/epoch-record` for off-box egress. This is the single normative projection emission site for forwarding records across a process boundary. It routes the full-value payload slots (`:frame-state-before`, `:frame-state-after`, `:db-before`, `:db-after`, `:trace-events`) through the record-level egress boundary under a `:rf.egress/profile`. Sensitive paths redact to `:rf/redacted`; large paths elide to `:rf.size/large-elided` markers. The frame-state `:rf.db/runtime` partition, the structured `:effects` `:args`, and the `:trigger-event` / trace-event args all fail closed (redacted) by default. `record` may be `nil` (returns `nil`). The 2-arity threads trusted-local egress `opts`; the 1-arity is the safe, fully-redacted off-box path.
  - **Profiles** (the primary `:rf.egress/profile` selector — *"which boundary is this?"*):
    - `:rf.egress/off-box-observability` (DEFAULT) — for hosted monitoring, log shippers, Story, and pair recorders. Redacts sensitive paths, elides large ones, and omits structural digests.
    - `:rf.egress/off-box-tool` — the MCP / AI / tool wire. Same redact/elide defaults, but includes structural marker indicators (`:digest`) so a tool can reason about an elided large slot's shape. An unknown profile is rejected against the closed enum.
  - The advanced per-call `:include-*` overrides (`:include-sensitive?` / `:include-large?` / `:include-runtime-db?` / `:include-fx-args?` / `:include-event-args?`, all default `false`) compose over the selected profile.

```clojure
;; Project an epoch record before forwarding it off-box (fully redacted).
(rf/projected-record (last (rf/epoch-history :app/main)))
;; Tool wire — include structural digests for elided slots.
(rf/projected-record record {:rf.egress/profile :rf.egress/off-box-tool})
```

### `projected-history`

- **Kind**: function
- **Signature**:
  ```clojure
  (projected-history frame-id)
  (projected-history frame-id opts)
  ```
- **Description**: Returns the projected vector of records for a frame. Equivalent to `(mapv #(projected-record % opts) (epoch-history frame-id))`. Tools that egress the whole ring (an MCP `watch-epochs` initial snapshot, a recorder dumping a full session) call this once rather than walking the raw ring and re-wrapping each record. The 2-arity threads the trusted-local egress `opts` to every record; the 1-arity is the safe, fully-redacted off-box path.

```clojure
;; Project the whole ring for off-box forwarding.
(rf/projected-history :app/main)
```

## Configuration

### `configure!`

- **Kind**: function (the `:epoch-history` configuration surface)
- **Signature**:
  ```clojure
  (configure! {:depth N :trace-events-keep N :redact-fn fn}) → nil
  ;; consumer-facing, routed through the core facade:
  (rf/configure! {:epoch-history {:depth N :trace-events-keep N :redact-fn fn}})
  ```
- **Description**: Buffer-depth and redactor knobs for the epoch ring.
  - `:depth` — non-negative integer; per-frame ring-buffer depth (default 50). `0` disables recording.
  - `:trace-events-keep` — non-negative integer. Caps how many of the most-recent records per frame retain their raw `:trace-events` vector; older records keep only the cheap structured `:sub-runs` / `:renders` / `:effects` projections. Defaults to 50 (matching the default `:depth`) so trace and epoch evict atomically. Pass a smaller value to bound dev-session heap.
  - `:redact-fn` — `fn?` or `nil`; the advanced projection-side override. It is invoked once per record at the off-box egress boundary inside `projected-record`, never at storage time. The ring buffer and every listener receive the raw record, since epoch records are causal replay material. A throwing fn emits `:rf.warning/epoch-redact-fn-exception` and falls back to the projected record. `nil` clears any previously-installed fn.
  - Invalid `:depth` / `:trace-events-keep` (not a non-negative integer) and a malformed `:redact-fn` (not `fn?` / `nil`) are silently dropped at the boundary.

  Apps usually set this through `rf/configure!` with the `:epoch-history` key; see [re-frame.core.md](re-frame.core.md).

```clojure
;; Shrink the ring and bound retained raw traces for a memory-conscious host.
(rf/configure! {:epoch-history {:depth 20 :trace-events-keep 5}})
```

### `current-config`

- **Kind**: function
- **Signature**:
  ```clojure
  (current-config) → config map
  ```
- **Description**: Returns the current epoch-history configuration map (`:depth` / `:trace-events-keep` / `:redact-fn`). Public for tests and tools that display the current depth.

```clojure
;; Inspect the live epoch-history configuration.
(epoch/current-config)
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
| `:rf.epoch.cb/silenced-on-frame-destroy` | `:frame`, `:cb-id` |
| `:rf.warning/epoch-redact-fn-exception` | `:frame`, `:rf.epoch/id`, `:ex-msg` |
| `:rf.warning/restore-quiesce-hook-exception` | `:frame`, `:hook`, `:exception` |
