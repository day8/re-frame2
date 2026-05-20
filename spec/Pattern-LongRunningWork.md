# Pattern — Long-Running Work

> **Type:** Pattern
> Modernised guidance for handling CPU-intensive work without freezing the UI. Successor to the v1 article *"Solve the CPU hog problem."*

> **Code samples are in ClojureScript** (the CLJS reference). The pattern itself is host-agnostic.

## The problem

A handler with significant CPU-bound work — iterating over a large dataset, encoding / decoding, indexing, parsing, simulation steps — blocks the dispatch loop and freezes the UI. The browser repaints once per ~16 ms; if a single handler holds the thread longer, animations stutter, click handlers queue up, and the app appears hung.

Two options:

1. **Offload to a different thread.** A Web Worker, a Service Worker, or any other process. The main thread stays responsive; the work returns asynchronously.
2. **Chunk and yield on the main thread.** Process work in small batches, yielding to the browser between batches.

The right choice depends on whether the work can be serialised across the thread boundary and how much progress reporting is needed.

## Decision tree — which pattern to use

1. **Does the work involve I/O (HTTP, IndexedDB, file system)?** → Use [Pattern-AsyncEffect](Pattern-AsyncEffect.md). The host already yields during I/O; no chunking needed.
2. **Is the work CPU-bound *and* serialisable across a worker boundary?** → Use a Web Worker via [Pattern-AsyncEffect](Pattern-AsyncEffect.md) (one-shot computation) or [Pattern-WebSocket](Pattern-WebSocket.md) (long-lived computational worker). **This is the preferred modern answer** for non-trivial CPU work.
3. **Must the work run on the main thread (DOM access, framework state, awkward-to-serialise data)?** → Use the chunked state-machine pattern below.

## The chunked state-machine pattern

A state machine that processes one batch per state transition, yields to the browser between batches via `:after 0`, and reports progress via the machine's extended state (`:data`).

### Canonical states

| state | meaning |
|---|---|
| `:idle` | Not running. Initial state. |
| `:processing` | Actively computing one chunk. Entry action processes the chunk and updates `:data`. |
| `:checking-done` | Eventless transition decides: complete, yield, or cancel. |
| `:yielding` | `:after 0` schedules the next chunk; the browser gets a render tick. |
| `:complete` | Terminal — work finished. |
| `:cancelled` | Terminal — user requested cancel. |

### Worked example — process N items in chunks of 100

```clojure
(rf/reg-event-fx :compute/batch-job
  {:doc "Long-running batch processing with progress reporting and cancel support."}
  (rf/create-machine-handler
    {:initial :idle
     :data    {:total       0
               :processed   0
               :chunk-size  100
               :input       nil
               :result      []}
     :guards
     {:done?      (fn [data _] (>= (:processed data) (:total data)))
      :more-work? (fn [data _] (<  (:processed data) (:total data)))}

     :actions
     {:start-job
      ;; opts is an optional map; :chunk-size falls back to the default declared in :data above.
      (fn [_ [_ input opts]]
        {:data {:total      (count input)
                :input      input
                :chunk-size (:chunk-size opts 100)
                :processed  0
                :result     []}})

      :process-chunk
      (fn [data _]
        (let [{:keys [input chunk-size processed result]} data
              chunk    (subvec input processed (min (+ processed chunk-size) (count input)))
              outputs  (mapv expensive-fn chunk)]
          {:data {:processed (+ processed (count chunk))
                  :result    (into result outputs)}}))}

     :states
     {:idle
      {:on {:start    {:target :processing
                       :action :start-job}}}

      :processing
      {:entry  :process-chunk
       :always [{:target :checking-done}]}    ;; immediately re-evaluate

      :checking-done
      {:always [{:guard :done?      :target :complete}
                {:guard :more-work? :target :yielding}]}

      :yielding
      {:after {0 :processing}                 ;; one browser tick, then next chunk
       :on    {:cancel :cancelled}}           ;; cancel only meaningful while yielding

      :complete   {:on {:reset :idle}}
      :cancelled  {:on {:reset :idle}}}}))
```

Walk-through for a 1000-item job with chunk-size 100:

1. View dispatches `[:compute/batch-job [:start input-vector]]` (or, to override the default, `[:compute/batch-job [:start input-vector {:chunk-size 50}]]`).
2. Machine transitions `:idle → :processing` via `:start` action; `:data` initialised with `total=1000`, `chunk-size=100` (or the supplied override), `processed=0`.
3. `:processing` entry processes chunk 1 (items 0..99); `:data.processed` becomes 100. `:always` advances to `:checking-done`.
4. `:checking-done` evaluates: `done?` false, `more-work?` true → `:yielding`.
5. `:yielding`'s `:after 0` schedules a return to `:processing` after one browser tick. Browser renders the progress bar.
6. Loop repeats for chunks 2..10.
7. After chunk 10, `done?` true → `:complete`.

The whole sequence is one logical operation, but each chunk is processed within ~16 ms and the browser gets a render tick between chunks.

### Parameters

`:chunk-size` shown above is the canonical example of a per-invocation parameter: declared as a default in `:data`, overridable via the dispatched event's opts map. The receiving action reads `(:chunk-size opts 100)` so callers who want the default omit opts; callers who want a smaller (or larger) batch supply it. The same shape covers `:max-attempts`, throttle windows, and any other knob a caller might want to tune per job.

For the full menu of parameter-passing mechanisms — event payload (used here), spawn-spec `:data` fn (used when the batch machine is `:spawn`d from a parent), and boot-time host config — see [Pattern-AsyncEffect §Parameter passing across the boundary](Pattern-AsyncEffect.md#parameter-passing-across-the-boundary).

## Cancellation

The user can cancel mid-job. The naïve approach — set a flag in `app-db` and check it on every chunk — is replaced by the state machine. A `:cancel` event handled in the `:yielding` state transitions to `:cancelled`; the next chunk doesn't run because the machine is no longer in `:processing`.

```clojure
;; user clicks "Cancel" button
(rf/dispatch [:compute/batch-job [:cancel]])
```

After this, the machine is in `:cancelled`; the partial result is preserved in `:data`; the view can show "Cancelled at 4 of 10 chunks" by reading the machine's snapshot.

For more sophisticated cases (e.g., a still-pending `:after` timer firing after cancel), [Pattern-StaleDetection](Pattern-StaleDetection.md) composes naturally — the machine's epoch advances on entering `:cancelled`; any in-flight timer carrying the previous epoch is suppressed on receipt.

## Progress UI

The machine's `:data` slot holds the progress fields. Register subs and views read from it:

```clojure
(rf/reg-sub :compute.job/progress
  :<- [:rf/machine :compute/batch-job]
  (fn [snapshot _]
    (let [{:keys [processed total]} (:data snapshot)]
      (when (pos? total)
        (/ processed total)))))         ;; 0.0 .. 1.0

(rf/reg-sub :compute.job/state
  :<- [:rf/machine :compute/batch-job]
  (fn [snapshot _] (:state snapshot)))  ;; :idle / :processing / :yielding / :complete / :cancelled
```

The view renders a progress bar from `:compute.job/progress` and shows different UI per `:compute.job/state`. The progress bar updates between chunks because each chunk advances `:data.processed` and the next browser tick renders the new value.

## One-shot heavy work — replaces `^:flush-dom`

re-frame v1 had a `^:flush-dom` event-vector metadata that forced a DOM flush before the next dispatch. This was used for the "show modal, then do one big synchronous block" pattern. re-frame2 doesn't carry this metadata; the modern equivalent uses `:dispatch-later` with `{:ms 0}`:

```clojure
(rf/reg-event-fx :process/start
  (fn [{:keys [db]} _]
    {:db (assoc db :processing? true)                          ;; modal renders next tick
     :fx [[:dispatch-later {:ms 0 :dispatch [:process/run]}]]}));; yield, then run

(rf/reg-event-fx :process/run
  (fn [{:keys [db]} _]
    {:db (assoc db :processing? false :result (heavy-block-fn db))}))
```

The `:dispatch-later {:ms 0}` schedules through the host clock primitive (via `re-frame.interop`); the browser gets one render tick between `:process/start` and `:process/run`. The modal appears before the heavy block runs.

## Anti-patterns

- **Computing in subscriptions.** Subs should be cheap and pure; long compute belongs in event handlers. A sub that takes seconds slows every render that touches it.
- **Multiple `assoc`s in one handler expecting interleaved renders.** re-frame2 batches per drain — only one render per drain regardless of how many `:db` updates within. Splitting into chunks via the state-machine pattern is the only way to get intermediate renders.
- **Manual chunk-state in app-db.** A state machine fits the chunked-progression structure more cleanly: explicit states, named transitions, encapsulated `:data`. App-db flags work but are harder to reason about.
- **Forgetting cancellation.** Long jobs need a cancel path. The state-machine pattern makes this trivial; ad-hoc loops make it painful.
- **`:always` cycles without `:after 0` between batches.** A pure `:always` chain hits the `:rf.error/machine-always-depth-exceeded` cap (default 16). The `:yielding` state's `:after 0` resets the depth and yields to the browser.
- **Trying to make subscriptions "incremental"** instead of computing in events. Subs are read-only projections; chunked work is a write operation that should produce data the subs then read.
- **Input changing mid-process.** The machine's `:data` holds the input the start-action snapshotted. If the source data changes while the job runs, the machine keeps processing the original snapshot — which is usually what you want. If it isn't, send `:reset` and restart.

## When to choose worker offload instead

The chunked-main-thread pattern works but isn't free — every browser tick is overhead. For genuinely heavy CPU work (image processing, large simulations, search indexing, complex analyses), offloading to a Web Worker via [Pattern-AsyncEffect](Pattern-AsyncEffect.md) is preferred:

- The main thread stays fully responsive — no chunked yielding required.
- Progress reporting still works (the worker dispatches progress events back).
- Cancellation is the same epoch-based pattern.
- The work runs at full thread speed (no 16 ms overhead per chunk).

The chunked pattern is for cases where worker offload isn't feasible (DOM access, awkward-to-serialise data, framework state that can't cross the boundary cheaply).

## Composition with other patterns

- **[Pattern-AsyncEffect](Pattern-AsyncEffect.md)** — preferred answer for offloadable work; this pattern is the fallback when offload isn't feasible.
- **[Pattern-WebSocket](Pattern-WebSocket.md)** — long-lived computational worker.
- **[Pattern-StaleDetection](Pattern-StaleDetection.md)** — cancellation when in-flight timers may fire after the user cancels.
- **State machines (005)** — the substrate this pattern uses.
- **`:after` delayed transitions** — yielding via `:after 0`.
- **`:always` eventless transitions** — batch progression.

## Migration from v1

| v1 pattern | re-frame2 replacement |
|---|---|
| Self-redispatch (`{:dispatch [:count-to false new-so-far finish-at]}`) | State machine with `:processing` / `:yielding` states |
| `:abandonment-required` app-db flag | `:cancel` event transitioning to `:cancelled` state |
| `^:flush-dom` event metadata | `:dispatch-later {:ms 0}` |
| Progress as a `:we-are-working` boolean | Progress as `:data {:processed :total}` read via `sub-machine` |

The state-machine version replaces re-entrant manual dispatch with explicit states; cancellation becomes a state transition; progress is the machine's `:data`.

## Cross-references

- [Pattern-AsyncEffect](Pattern-AsyncEffect.md) — preferred when work can be offloaded.
- [Pattern-WebSocket](Pattern-WebSocket.md) — long-lived computational workers.
- [Pattern-StaleDetection](Pattern-StaleDetection.md) — cancellation epoch idiom.
- [005-StateMachines](005-StateMachines.md) — the substrate.
- [`:after` transitions](005-StateMachines.md#delayed-after-transitions) — yielding mechanism.
- [`:always` transitions](005-StateMachines.md#eventless-always-transitions) — batch progression.

