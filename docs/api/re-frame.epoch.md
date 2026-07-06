# re-frame.epoch

`re-frame.epoch` is the per-frame **epoch-history** surface — re-frame2's dev-only time-travel and post-mortem layer. The runtime records one `:rf/epoch-record` per dequeued event (at its run-to-completion boundary, not once per drain) into a per-frame ring buffer, capturing the before/after frame-state, the triggering event, and the harvested trace stream. Pair-shaped tools (Xray, re-frame2-pair, Story) read this history to time-travel (`restore-epoch!`), inject state directly (`replace-app-db!` and friends), observe assembled epochs (`register-epoch-listener!`), and project records safely across a process boundary (`projected-record`). The entire surface is gated on dev builds — production builds (`:advanced` + `goog.DEBUG=false`) elide it entirely, with no allocation, storage, or overhead.

```clojure
(:require [re-frame.epoch :as epoch])
```

Most of this surface is also re-exported on the `re-frame.core` facade, so `rf/restore-epoch!` and `epoch/restore-epoch!` name the same function. The examples below use the `rf/` form for the re-exported names and the `epoch/` form for the epoch-only helpers (`clear-history!`, `current-config`, `clear-epoch-listeners!`, `configure!`). See [Observability](../core/concepts/observability.md) for how epochs fit the broader trace model.

## Epoch history

Per-frame epoch snapshots, recorded on each dequeued event's run-to-completion in dev builds. Used by pair-shaped tools for time-travel and post-mortem analysis. **Production builds elide entirely.**

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
- **Description**: Drop every recorded epoch for every frame, and any in-flight per-frame capture buffer. Test fixtures use this so each fixture's drain observes a fresh capture state (a buffer left over from a previous fixture's mid-flight emit would otherwise be picked up by the next fixture's first run).

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
- **Description**: Restore the frame's whole **frame-state** — both the app-db and runtime-db partitions — to the named epoch's `:frame-state-after`, reinstalled in one atomic write (so machine snapshots, the route slice, and other runtime-db material rewind alongside app-db, not just the application slice). Emits `:rf.epoch/restored` on success. Returns `true` on success, `false` on any failure. Each failure is a no-op on the frame-state and emits a structured error trace:
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

State-injection surfaces that replace a frame's partitions directly, bypassing the dispatch loop. Each records a synthetic `:rf/epoch-record` (so `restore-epoch!` can rewind past the injection) and emits `:rf.epoch/db-replaced` on success. All are dev-only — gated on dev builds; production builds elide them.

### `replace-app-db!`

- **Kind**: function
- **Signature**:
  ```clojure
  (replace-app-db! frame-id new-db) → boolean
  ```
- **Description**: Pair-tool write surface (state injection). Replace the frame's `app-db` partition with `new-db`, bypassing the dispatch loop; the runtime-db partition is preserved unchanged. No-ops returning `false` (each emitting a structured trace) on `:rf.error/no-such-handler` (frame not registered), `:rf.epoch/replace-during-drain`, `:rf.epoch/replace-schema-mismatch` (`new-db` fails the frame's registered app-schema set), and `:rf.epoch/replace-history-disabled` (ring disabled at depth 0, so the synthetic undo-anchor cannot land). Returns `true` on success.

```clojure
;; State injection — direct app-db write (bypasses the pipeline).
(rf/replace-app-db! :app/main {:counter 0})
```

### `reset-app-db!`

- **Kind**: function
- **Signature**:
  ```clojure
  (reset-app-db! frame-id) → boolean
  ```
- **Description**: Reset the frame's `app-db` partition to `{}`, bypassing the dispatch loop, while preserving live runtime-db (machines / routes / elision / SSR survive). The app-db-only sibling of the whole-frame `reset-frame!`; a thin wrapper over `replace-app-db!` with the empty map, so it shares the same synthetic-epoch recording, gating, and failure modes. Returns `true` on success, `false` on any failure.

```clojure
;; Clear the application slice, keeping machines / routes alive.
(rf/reset-app-db! :app/main)
```

### `replace-runtime-db!`

- **Kind**: function
- **Signature**:
  ```clojure
  (replace-runtime-db! frame-id new-runtime-db) → boolean
  ```
- **Description**: The runtime-db sibling of `replace-app-db!` — replace the frame's `runtime-db` partition (the framework-owned subsystem state: machine snapshots, route slice, elision declarations, SSR metadata), bypassing the dispatch loop; the app-db partition is preserved unchanged. No-ops returning `false` (each emitting a structured trace) on `:rf.error/no-such-handler` (frame not registered), `:rf.epoch/replace-during-drain`, `:rf.epoch/replace-schema-mismatch` (fails the framework-owned runtime-db validator), and `:rf.epoch/replace-history-disabled` (ring disabled at depth 0, so the synthetic undo-anchor cannot land). Returns `true` on success.

```clojure
;; Inject framework-owned subsystem state (runtime-db partition only).
(rf/replace-runtime-db! :app/main new-runtime-db)
```

### `replace-frame-state!`

- **Kind**: function
- **Signature**:
  ```clojure
  (replace-frame-state! frame-id new-frame-state) → boolean
  ```
- **Description**: Replace **both** of the frame's partitions atomically with `new-frame-state` (`{:rf.db/app … :rf.db/runtime …}`), bypassing the dispatch loop — the full-frame install for tool-driven replay / fixture loading. A db-shaped name never silently replaces runtime-db, so this is the explicit whole-frame surface; a missing partition key installs `nil` for that partition (a full-frame replace is whole-value by contract). Same failure modes as `replace-runtime-db!` (`:rf.error/no-such-handler`, `:rf.epoch/replace-during-drain`, `:rf.epoch/replace-schema-mismatch`, `:rf.epoch/replace-history-disabled`). Returns `true` on success.

```clojure
;; Full-frame install — both partitions at once.
(rf/replace-frame-state! :app/main {:rf.db/app {:counter 0} :rf.db/runtime {}})
```

## Epoch listeners

### `register-epoch-listener!`

- **Kind**: function
- **Signature**:
  ```clojure
  (register-epoch-listener! id callback-fn) → id
  ```
- **Description**: Process-global assembled-epoch listener. `callback-fn` is invoked once per committed record with the fully-assembled **raw** `:rf/epoch-record`, after it lands in the frame's ring buffer; listeners receive every record regardless of `:outcome`. `id` may be any comparable value; registering the same `id` twice replaces. Listener exceptions are caught and isolated (emitting `:rf.epoch.cb/listener-exception`) — one broken listener cannot block others. When a frame a callback has observed is destroyed, a one-shot `:rf.epoch.cb/silenced-on-frame-destroy` trace is emitted for that callback. Returns the `id`.

```clojure
;; Observe each assembled epoch as frames settle.
(rf/register-epoch-listener! :my-app/epoch-watch
  (fn [record]
    (js/console.log (:frame record) (:epoch-id record))))
```

### `unregister-epoch-listener!`

- **Kind**: function
- **Signature**:
  ```clojure
  (unregister-epoch-listener! id) → nil
  ```
- **Description**: The inverse.
- **Example**: `(rf/unregister-epoch-listener! :my-app/epoch-watch)`

### `clear-epoch-listeners!`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-epoch-listeners!) → nil
  ```
- **Description**: Drop every registered epoch listener. Test fixtures use this to reset the process-global listener registry between runs.

```clojure
;; Reset the listener registry between test fixtures.
(epoch/clear-epoch-listeners!)
```

## Off-box egress projection

Tools that forward epoch records across a process boundary (Xray-MCP `watch-epochs`, story / pair recorders, hosted post-mortem forwarders) **must** route through these helpers at the wire boundary. The on-box ring buffer and `register-epoch-listener!` fan-out always deliver the **raw** record so on-box devtools (Xray diff, REPL, `restore-epoch!`) can reason about exact state. See [keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md) for the projection model.

### `projected-record`

- **Kind**: function
- **Signature**:
  ```clojure
  (projected-record record)
  (projected-record record opts)
  ```
- **Description**: Project an `:rf/epoch-record` for off-box egress — the single normative projection emission site for forwarding records across a process boundary. Routes the full-value payload slots (`:frame-state-before`, `:frame-state-after`, `:db-before`, `:db-after`, `:trace-events`) through the record-level egress boundary under a `:rf.egress/profile`, redacting sensitive paths to `:rf/redacted` and eliding large paths to `:rf.size/large-elided` markers. The frame-state `:rf.db/runtime` partition, the structured `:effects` `:args`, and the `:trigger-event` / trace-event args all fail closed (redacted) by default. `record` may be `nil` (returns `nil`). The 2-arity threads trusted-local egress `opts`; the **1-arity is the safe, fully-redacted off-box path**.
  - **Profiles** (the primary `:rf.egress/profile` selector — answers *"which boundary is this?"*):
    - `:rf.egress/off-box-observability` (DEFAULT) — hosted monitoring / log shippers / Story / pair recorders (redact sensitive, elide large, omit structural digests).
    - `:rf.egress/off-box-tool` — the MCP / AI / tool wire. Same redact/elide defaults, but includes structural marker indicators (`:digest`) so a tool can reason about an elided large slot's shape. An unknown profile is rejected against the closed enum.
  - The advanced per-call `:include-*` overrides (`:include-sensitive?` / `:include-large?` / `:include-runtime-db?` / `:include-fx-args?` / `:include-event-args?`, all default `false`) compose **over** the selected profile.

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
- **Description**: Convenience — return the projected vector of records for a frame. Equivalent to `(mapv #(projected-record % opts) (epoch-history frame-id))`. Tools that egress the whole ring (an MCP `watch-epochs` initial snapshot, a recorder dumping a full session) call this once rather than walking the raw ring and re-wrapping each record. The 2-arity threads the trusted-local egress `opts` to every record; the 1-arity is the safe, fully-redacted off-box path.

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
  - `:trace-events-keep` — non-negative integer; caps how many of the most-recent records per frame retain their raw `:trace-events` vector (older records keep only the cheap structured `:sub-runs` / `:renders` / `:effects` projections). Defaults to 50 (matching the default `:depth`) so trace + epoch evict atomically; pass a smaller value to bound dev-session heap.
  - `:redact-fn` — `fn?` or `nil`; the advanced projection-side override, invoked once per record at the off-box egress boundary inside `projected-record`, **never at storage time** (the ring buffer and every listener receive the raw record, since epoch records are causal replay material). A throwing fn emits `:rf.warning/epoch-redact-fn-exception` and falls back to the projected record. Passing `nil` clears any previously-installed fn.

  Invalid `:depth` / `:trace-events-keep` (not a non-negative integer) and a malformed `:redact-fn` (not `fn?` / `nil`) are silently dropped at the boundary. Apps usually set this through `rf/configure!` with the `:epoch-history` key; see [re-frame.core.md](re-frame.core.md).

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
- **Description**: Return the current epoch-history configuration map (`:depth` / `:trace-events-keep` / `:redact-fn`). Public for tests and tools that want to display the current depth.

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
- **Description**: The hook the router calls once per **dequeued event** — at each event's run-to-completion boundary, NOT once per drain. It harvests that event's trace buffer, assembles the `:rf/epoch-record` (deriving the `:db-before` / `:db-after` app-db projections from the whole-frame-state snapshots), appends it to the per-frame ring buffer, emits `:rf.epoch/snapshotted` with an `:outcome` tag plus its consumer-facing companion `:rf.epoch/outcome` (`:ok` / `:blocked` / `:error`), and fans out to every registered listener. A drain that processes a parent event and an `:fx [[:dispatch …]]` child it queued therefore commits **two** records (one per event); a machine macrostep stays one epoch. `committed-at` is the committing causal token's `:rf.cofx` `:rf/time-ms`, threaded down by the router (not an ambient assembly-time clock read), which keeps the record replayable. The 4-arity is the clean `:ok` settle (skipped when the captured buffer is empty); the 6-arity is the drain-boundary commit with an explicit outcome (`:ok` / `:halted-depth` / `:halted-destroy`). **Framework-internal** — the router invokes this; application and tool code never call it directly.

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
