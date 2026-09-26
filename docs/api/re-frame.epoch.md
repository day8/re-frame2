# re-frame.epoch

In development builds, re-frame records an epoch for every event a frame handles: the frame-state (the frame's `app-db` and `runtime-db`) before and after, the event that ran, and the trace it produced. That history lets you see what each event did, rewind a frame to an earlier epoch, or run a recorded event again. Production builds (`:advanced` + `goog.DEBUG=false`) elide all of it: nothing is allocated, stored or run.

Most of the time you use epochs through a tool: Xray's time-travel view, re-frame2-pair and Story read the history and call these functions for you. At a REPL the usual loop is the one below: find the event that went wrong, rewind to just before it, fix and reload its handler, and replay it.

Epoch history ships in the optional `day8/re-frame2-epoch` artefact; require `re-frame.epoch` to load it. Without it, `rf/epoch-history` returns `[]`, `rf/restore-epoch!` and `rf/replay-epoch!` return `false`, and `rf/replace-frame-state!` throws `:rf.error/epoch-artefact-missing`.

```clojure
(:require [re-frame.core  :as rf]
          [re-frame.epoch :as epoch])
```

```clojure
;; What did the recent events do? History is oldest-first.
(def history (rf/epoch-history :app/main))
(map :event-id history)                  ;; => (:cart/add :cart/add :cart/checkout)
(select-keys (peek history) [:trigger-event :db-before :db-after])

;; :cart/checkout went wrong. Rewind to the state just before it,
;; which is the previous epoch's after-state.
(rf/restore-epoch! :app/main (:epoch-id (nth history 1)))   ;; => true

;; Fix and reload the :cart/checkout handler, then run the recorded event
;; again. Its effects, such as an HTTP request, run again too.
(rf/replay-epoch! :app/main (:epoch-id (nth history 2)))
;; => {:ok? true :frame :app/main :event-id :cart/checkout …}
```

You call everything on this page through `rf/`, including listeners (`rf/register-listener! :epoch`) and configuration (`rf/configure!`, `rf/current-config`). The same-named `re-frame.epoch` vars are the implementations behind the facade; a test calls only the teardown functions `clear-history!` and `clear-epoch-listeners!` on `epoch/`. The [Observability guide](../core/observability.md#the-epoch-history-what-the-app-was) shows how epochs fit the trace model.

## Epoch history

### `epoch-history`

- **Kind**: function
- **Signature**:
  ```clojure
  (epoch-history frame-id) → vector of epoch records
  ```
- **Description**: Returns the frame's `:rf/epoch-record`s, oldest first. Returns `[]` for an unknown or destroyed frame, or when recording is off (`:depth` 0).
    - A record is added when each handled event runs to completion, not once per drain. A drain that handles a parent event and a child it queued with `:fx [[:dispatch …]]` adds two records; a machine macrostep stays one.
    - Each record holds `:epoch-id`, `:frame`, `:outcome`, `:committed-at`, `:event-id` and `:trigger-event`; the whole frame-state as `:frame-state-before` / `:frame-state-after`, with `:db-before` / `:db-after` as their `app-db` projections; the raw `:trace-events`; and the structured `:sub-runs`, `:renders` and `:effects` summaries.
    - The ring keeps the newest `:depth` records per frame (default 50); see [Configuration](#configuration).
- **Example**:
  ```clojure
  ;; All recorded epochs for a frame, oldest-first; peek the latest.
  (rf/epoch-history :app/main)
  (last (rf/epoch-history :app/main))
  ```

## Time-travel

### `restore-epoch!`

- **Kind**: function
- **Signature**:
  ```clojure
  (restore-epoch! frame-id epoch-id) → boolean
  ```
- **Description**: Rewinds the frame to the named epoch's `:frame-state-after` in one atomic write. Both partitions rewind: `app-db` and `runtime-db` alike, so machine snapshots and the route slice go back too.
    - Returns `true` on success and emits `:rf.epoch/restored`.
    - The history is kept: epochs recorded after the target stay in the ring, so you can still restore or replay them.
    - Returns `false` on any failure, leaves the frame-state unchanged, and emits one of these error traces:
        - `:rf.error/no-such-handler` (kind `:frame`): the frame is not registered, or was destroyed.
        - `:rf.epoch/restore-during-drain`: called while a drain is in flight.
        - `:rf.epoch/restore-unknown-epoch`: `epoch-id` is not in the frame's current history.
        - `:rf.epoch/restore-non-ok-record`: the epoch's `:outcome` is not `:ok`. A halted cascade's record holds partial state and cannot be restored.
        - `:rf.epoch/restore-schema-mismatch`: the recorded `app-db` no longer validates against the frame's registered app-schemas.
        - `:rf.epoch/restore-missing-handler`: a machine or route referenced from the recorded `runtime-db` is no longer registered.
        - `:rf.epoch/restore-version-mismatch`: a machine snapshot's version differs from the current definition.
- **Example**:
  ```clojure
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
- **Description**: Runs a retained epoch's recorded event through the frame's own handlers again, as a strict replay. Use it after changing a handler, usually after a `restore-epoch!` to the epoch before, to see what the new code does with the same input. It differs from dispatching the recorded event vector yourself: the handler receives the coeffects recorded the first time, such as the time, instead of fresh ones.
    - The record is read in-process and its replay material goes into the dispatch: the raw `:trigger-event` with its arguments, the recorded `:rf.cofx` under `:rf.cofx/mint-policy :strict`, and the record's own `:fx-overrides` and `:interceptor-overrides`. Because nothing is copied out by hand, an off-box tool can replay even though a projected record shows event arguments only as `:rf/redacted`.
    - Replay runs in the same frame, against its current state and code. It does not restore first (call `restore-epoch!` beforehand when the start state matters), any effect the handler emits fires again, and the replay records a new ordinary epoch whose `:committed-at` equals the original's.
    - `opts` carries only `:origin`, `:source` and `:trace-id`. A value under `:frame`, `:rf.cofx`, `:rf.cofx/mint-policy`, `:fx-overrides` or `:interceptor-overrides` is discarded.
    - On success it returns `{:ok? true :frame … :source-epoch-id … :event-id … :epoch-id …}`. `:epoch-id` names the epoch the replayed event itself committed, never a queued child's. It is `nil` when the ring did not keep that epoch: a replay may enqueue work the original did not, and at a shallow `:depth` a child can evict its parent's record.
    - On refusal it returns `{:ok? false :reason … :frame … :epoch-id … <tags>}`, decided before anything dispatches and without emitting a trace:
        - `:rf.error/no-such-handler` (kind `:frame`): the frame is not registered, or was destroyed.
        - `:rf.epoch/replay-during-drain`: called while a drain is in flight.
        - `:rf.epoch/replay-unknown-epoch` (with `:history-size`): `epoch-id` is not in the frame's current history.
        - `:rf.epoch/replay-non-replayable-record` (with `:cause`: `:halted`, `:synthetic`, `:missing-trigger-event`, `:missing-replay-token` or `:incomplete-inputs`).
        - `:rf.epoch/replay-unreplayable-fx-override` (with `:fx-ids`): a recorded `:fx-overrides` entry is the `:rf/fn-override` sentinel.
    - `:incomplete-inputs` means capture lost data. A `:sensitive` or `:large` declaration on a `reg-event` or `reg-cofx` is applied at trace capture, so the declared value reaches the record already replaced by `:rf/redacted` or a `:rf.size/large-elided` marker. Replay refuses rather than run with the substitute; `:lost` lists each `{:slot :path :loss}`.
    - A declared recordable coeffect missing from the recorded token is not a refusal: the dispatch runs and throws `:rf.error/missing-required-cofx` out of `replay-epoch!`, as any `:strict` dispatch does. No fresh value is generated in its place.
- **Example**:
  ```clojure
  ;; Replay the most recent epoch through the app's own handlers, strictly.
  (let [source (last (rf/epoch-history :app/main))]
    (rf/replay-epoch! :app/main (:epoch-id source)))
  ;; => {:ok? true :frame :app/main :source-epoch-id 41 :event-id :cart/add :epoch-id 42}
  ```

## Replacing frame-state

### `replace-frame-state!`

- **Kind**: function
- **Signature**:
  ```clojure
  (replace-frame-state! frame-id new-frame-state) → boolean
  ```
- **Description**: Writes a frame's partitions directly, without dispatching an event. Use it from a REPL or a tool to put a frame into a particular state without writing an event for it; pair tools inject state this way. Dev-only; production builds elide it.
    - `new-frame-state` is a partial frame-state map: any subset of `{:rf.db/app … :rf.db/runtime …}`. A key that is present replaces that partition; a key that is absent leaves its partition unchanged. Writing one partition never touches the other.
    - This is the only frame-state write function. There is no `replace-app-db!`, `reset-app-db!` or `replace-runtime-db!`; write the one partition you mean.
    - Each successful write records a synthetic `:rf/epoch-record`, so `restore-epoch!` can rewind past it, and emits `:rf.epoch/db-replaced`. Returns `true`.
    - Returns `false` and emits a structured trace, changing nothing, on:
        - `:rf.error/replace-frame-state-bad-keys`: the map has no recognised partition key, or has an unrecognised key. Checked before the frame is resolved.
        - `:rf.error/no-such-handler`: the frame is not registered.
        - `:rf.epoch/replace-during-drain`: called while a drain is in flight.
        - `:rf.epoch/replace-schema-mismatch`: a present `app-db` value fails the frame's registered app-schemas, or a present `runtime-db` value fails the framework's `runtime-db` validator.
        - `:rf.epoch/replace-history-disabled`: recording is off (`:depth` 0), so the synthetic epoch that makes the write undoable cannot be recorded.
- **Example**:
  ```clojure
  ;; Replace app-db only; runtime-db is kept.
  (rf/replace-frame-state! :app/main {:rf.db/app {:counter 0}})

  ;; Empty app-db while keeping machines and routes alive.
  (rf/replace-frame-state! :app/main {:rf.db/app {}})

  ;; Replace runtime-db only; app-db is kept.
  (rf/replace-frame-state! :app/main {:rf.db/runtime new-runtime-db})

  ;; Replace both partitions atomically.
  (rf/replace-frame-state! :app/main {:rf.db/app {:counter 0} :rf.db/runtime {}})
  ```

## Epoch listeners

Apps and tools receive epoch records on the `:epoch` stream: `(rf/register-listener! :epoch id callback-fn)` and `(rf/unregister-listener! :epoch id)`, documented under [`register-listener!`](re-frame.core.md#register-listener). Use a listener when you build something that must see each record as it is published, such as a recorder, a forwarder or a custom dev panel; to inspect history at a REPL, `epoch-history` is enough. What the callback receives is described here:

- `callback-fn` is called with the fully assembled, unprojected `:rf/epoch-record` each time a record is published, which is not always once per event:
    - An ordinary event publishes one record when it settles. The same record, with the same `:epoch-id`, is published again when a later render, sub-run or unmount fills in more of that settled epoch.
    - Each `replace-frame-state!` write publishes its `:rf.epoch/db-replaced` record, and a drain that hits the depth ceiling publishes a `:halted-depth` record. Both are kept in the ring when `:depth` allows.
    - An event already running when its frame is destroyed publishes a terminal `:halted-destroy` record. It goes to listeners only and is never kept, because the destroyed frame's history is already gone.
    - A dequeued event rejected before it runs (it has no handler) publishes nothing.
- Listeners are process-global, but `:epoch-id` is unique only within one frame's history. Key records on `[(:frame record) (:epoch-id record)]` and replace the entry when a record is published again, so a filled-in record corrects your copy rather than counting twice.
- `:outcome` (`:ok`, `:halted-depth` or `:halted-destroy`) is state read off the record, not part of its identity. Listeners receive every record, whatever its `:outcome`.
- Listeners receive records even when `:depth` is 0 and the ring keeps nothing.
- `id` may be any comparable value; registering the same `id` again replaces the callback.
- A listener exception is caught and isolated, emitting `:rf.epoch.cb/listener-exception`, so one broken listener cannot block the others.
- When a frame a callback has observed is destroyed, the framework emits a one-shot `:rf.epoch.cb/silenced-on-frame-destroy` trace for that callback. Call `(rf/epoch-silence-current? tags)` with the trace's tags to decide whether it still describes the current callback.

```clojure
;; Keep the latest copy of each record, keyed by [frame epoch-id].
(def epochs (atom {}))

(rf/register-listener! :epoch :my-app/epoch-watch
  (fn [record]
    (swap! epochs assoc [(:frame record) (:epoch-id record)] record)))

(rf/unregister-listener! :epoch :my-app/epoch-watch)
```

## Forwarding records off-box

The ring and the `:epoch` listeners always hold the raw record, so on-box tools (Xray's diff view, the REPL, `restore-epoch!`) see exact state. A tool that sends records across a process boundary (Xray-MCP `watch-epochs`, Story and pair recorders, hosted post-mortem forwarders) must project each one first with [`rf/project-egress`](re-frame.core.md#project-egress). [Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md) explains the projection model.

`re-frame.epoch` has no egress function of its own, and there is no `rf/projected-record`: `rf/project-egress` recognises an epoch record by its `:kind :rf/epoch-record` stamp and takes the epoch-only options.

- **Signature**: `(rf/project-egress record)` / `(rf/project-egress record opts)`.
- It projects the payload slots (`:frame-state-before`, `:frame-state-after`, `:db-before`, `:db-after`, `:trace-events`) under a `:rf.egress/profile`. Sensitive paths redact to `:rf/redacted` and large paths elide to `:rf.size/large-elided` markers. By default the `:rf.db/runtime` partition, the `:args` of `:effects` entries, and the arguments of `:trigger-event` and trace events are redacted. The 1-arity is the safe, fully redacted off-box form; the 2-arity takes trusted-local `opts`.
- An input without a `:kind` stamp (`nil`, a non-map, or an unstamped map) is projected as a plain value, by the [value path](re-frame.core.md#project-egress)'s usual rules. So `(mapv rf/project-egress ring)` over a ring with holes never throws, but a hole is not guaranteed to project as `nil`.
- The `:rf.egress/profile` value names which boundary the record is crossing. These two are the usual ones for epoch records; `rf/project-egress` lists the rest:
    - `:rf.egress/off-box-observability` (the default for epoch records): hosted monitoring, log shippers, Story and pair recorders. Redacts sensitive paths, elides large ones, and omits structural digests.
    - `:rf.egress/off-box-tool`: the MCP, AI and tool wire. The same redaction and elision, and no digests. A tool reads an elided slot's shape from the marker's `:path`, `:bytes`, `:type` and `:handle`.
    - An unknown profile throws `:rf.error/unknown-egress-profile`.
- Per-call overrides compose over the profile. `:rf.egress/include-sensitive?`, `:rf.egress/include-large?` and `:rf.egress/include-digests?` override what the profile decides. Three more apply only to epoch records and default to `false`: `:rf.egress/include-runtime-db?`, `:rf.egress/include-fx-args?` and `:rf.egress/include-event-args?`; on any other kind they are accepted and have no effect.
- `opts` is closed; [`rf/project-egress`](re-frame.core.md#project-egress) lists its keys. Any other key, including the unqualified `include-sensitive?` / `include-large?`, throws `:rf.error/bad-egress-opts` naming it.
- Without the `day8/re-frame2-epoch` artefact, an epoch record passed to `rf/project-egress` throws `:rf.error/epoch-artefact-missing` naming the kind, rather than being walked as a plain value.
- There is no post-projection scrub hook. A forwarder that needs one composes it: `(-> record (rf/project-egress opts) scrub)`.

```clojure
;; Project an epoch record before forwarding it off-box (fully redacted).
(rf/project-egress (last (rf/epoch-history :app/main)))
;; Tool wire: elided slots carry the marker's structural fields, no digest.
(rf/project-egress record {:rf.egress/profile :rf.egress/off-box-tool})
;; The whole ring.
(mapv #(rf/project-egress % opts) (rf/epoch-history :app/main))
```

## Configuration

Set the ring's size through the facade, under the `:epoch-history` key:

```clojure
(rf/configure! {:epoch-history {:depth N :trace-events-keep N}})
```

- `:depth`: a non-negative integer, the number of records kept per frame (default 50). `0` turns recording off. Lowering it trims each frame's existing history to its newest N records immediately.
- `:trace-events-keep`: a non-negative integer, how many of each frame's most recent records keep their raw `:trace-events`. Older records keep only the structured `:sub-runs`, `:renders` and `:effects`. The default is a fixed 50, equal to the default `:depth`, so trace detail and records evict together; setting `:depth` alone leaves it at 50. Lower it to bound dev-session memory.
- An invalid value (not a non-negative integer) is silently dropped.

```clojure
;; Shrink the ring and keep raw traces for only the last 5 records.
(rf/configure! {:epoch-history {:depth 20 :trace-events-keep 5}})
```

Read the live configuration with `rf/current-config`; there is no reader on `re-frame.epoch`. The `:epoch-history` key is absent altogether when the `day8/re-frame2-epoch` artefact is not loaded, rather than reporting a made-up default:

```clojure
(:epoch-history (rf/current-config))     ;; => {:depth 20 :trace-events-keep 5}
```

## Trace events

Trace events emitted by epoch history:

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

## Framework integration

Not for application code — used by adapters, tools and the test harness.

`re-frame.epoch`'s `epoch-history`, `restore-epoch!`, `replay-epoch!`, `replace-frame-state!` and `epoch-silence-current?` are the functions the same-named `rf/` vars call. `register-epoch-listener!` and `unregister-epoch-listener!` implement the `:epoch` stream of `rf/register-listener!` / `rf/unregister-listener!` (there is no `rf/register-epoch-listener!`), and `configure!` implements `(rf/configure! {:epoch-history …})`, taking the inner map. None is a second public spelling; call the `rf/` forms.

### `clear-history!`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-history!) → nil
  ```
- **Description**: Test-only. Drops every recorded epoch for every frame, and any capture buffer left mid-flight, which the next test's first event would otherwise pick up. `re-frame.test-support`'s reset fixture calls it; there is no `rf/` form.
- **Example**:
  ```clojure
  ;; Reset epoch state between test fixtures.
  (epoch/clear-history!)
  ```

### `clear-epoch-listeners!`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-epoch-listeners!)
  ```
- **Description**: Test-only. Removes every registered epoch listener. There is no `rf/` form; application code unregisters the ids it registered with `(rf/unregister-listener! :epoch id)`.

### `settle!`

- **Kind**: function
- **Signature**:
  ```clojure
  (settle! frame-id frame-state-before frame-state-after committed-at)
  (settle! frame-id frame-state-before frame-state-after committed-at settling-dispatch-id)
  (settle! frame-id frame-state-before frame-state-after committed-at outcome halt-reason)
  (settle! frame-id frame-state-before frame-state-after committed-at outcome halt-reason settling-dispatch-id)
  (settle! frame-id frame-state-before frame-state-after committed-at outcome halt-reason settling-dispatch-id {:exact-owner-token …})
  ```
- **Description**: Records one epoch. The router calls it once per dequeued event, when that event runs to completion; application and tool code never call it. Each call:
    - collects that event's trace buffer;
    - assembles the `:rf/epoch-record`, deriving the `:db-before` / `:db-after` `app-db` projections from the whole frame-state snapshots;
    - appends it to the frame's ring;
    - emits `:rf.epoch/snapshotted` with an `:outcome` tag, and its consumer-facing companion `:rf.epoch/outcome` (`:ok`, `:blocked` or `:error`);
    - calls every registered listener.

    Arities:

    - The 4-arity records a clean `:ok` epoch.
    - `settling-dispatch-id` limits the collected traces to that dispatch's own, so a rejected dispatch's error trace is dropped without consuming a queued child's marker.
    - The 6- and 7-arities take an explicit `outcome` and `halt-reason`.
    - The 8-arity, the one the router calls, adds `{:exact-owner-token …}`, which ties the commit to the frame instance the settling event ran in.

    `committed-at` is the committing event's `:rf.cofx` `:rf/time-ms`, passed in by the router rather than read from a clock, which keeps the record replayable. Every arity skips an empty harvest; a depth halt whose event never ran is recorded by a separate hook.
- **Example**:
  ```clojure
  ;; Framework-internal: the router makes these calls.
  (settle! :app/main fs-before fs-after committed-at)                              ; clean :ok settle
  (settle! :app/main fs-before fs-after committed-at :halted-destroy halt-reason)  ; explicit outcome
  ```
