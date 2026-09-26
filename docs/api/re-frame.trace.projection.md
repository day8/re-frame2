# re-frame.trace.projection

Group the development trace stream into one record per event. The raw stream arrives one trace event at a time; `group-by-event` folds it into an **event bundle** for each pipeline run (one dequeued event), with the event vector, handler, effects, subscription runs and renders already sorted into named slots. Xray's event panels and the re-frame2-pair tooling read bundles in this shape; you call it yourself in a test or a tool that has collected raw trace events and wants to check or show what one event did.

```clojure
(:require [re-frame.core :as rf]
          [re-frame.trace.projection :as projection])
```

```clojure
;; each event vector, with the number of renders it caused
(->> (rf/trace-buffer :app/main {:flat true})
     projection/group-by-event
     (map (fn [{:keys [event renders]}] [event (count renders)])))
```

These functions are not on the `re-frame.core` facade; require this namespace directly. [`rf/trace-buffer`](re-frame.core.md#trace-buffer) already returns bundles in this shape by default, plus a `:trace-events` vector of each run's raw events, so you call `group-by-event` only when you hold raw events: a flat buffer, a saved trace, or events collected by a [`:trace` listener](re-frame.core.md#register-listener).

Both functions are pure data, with no frame, registry or router, and run the same code on the JVM and in CLJS, so a tool folding a saved buffer gets the same shape as a live listener. In practice they are development-only, because their input is the development-only trace stream. [Observability](../core/observability.md) explains where the trace stream comes from.

## Projection

### `group-by-event`

- **Kind**: function
- **Signature**:
  ```clojure
  (group-by-event events) → vector of event bundles
  ```
- **Description**: Groups trace events into one event bundle per pipeline run, keyed by `[frame dispatch-id]`, in emission order (runs are sorted by their lowest trace `:id`).
    - Trace events with no `:rf.trace/dispatch-id` tag, such as registration-time emits, frame lifecycle and REPL evaluation outside an event, collect in one bundle whose `:dispatch-id` is `:ungrouped`.
    - Errors, effects, subscription runs and renders that happen during a run carry that run's dispatch id, so they land in its bundle.
    - Each bundle is a map with these keys:
        - `:dispatch-id` — the run's `:rf.trace/dispatch-id`, or `:ungrouped`.
        - `:parent-dispatch-id` — the dispatch id of the run that dispatched this event (through an `:fx` dispatch or a machine's internal dispatch), or `nil` for a root dispatch.
        - `:frame` — the run's frame id; `nil` only on the `:ungrouped` bundle. A run's trace event that carries no frame joins the run's one known frame, or `:rf/default`.
        - `:event` — the event vector.
        - `:dispatched` — the full `:rf.event/dispatched` trace event, including top-level slots such as `:rf.trace/call-site`.
        - `:handler` — the `:rf.event/run-start` or `:rf.event/run-end` trace event; the last one seen wins, usually `:run-end`.
        - `:fx` — the `:rf.fx/do-fx` trace event.
        - `:effects` — a vector of the other `:rf.fx` trace events (`:rf.fx/handled`, `:rf.fx/override-applied`, `:rf.fx/skipped-on-platform`).
        - `:subs` — a vector of the `:rf.sub` trace events (`:rf.sub/run`, `:rf.sub/skip`, `:rf.sub/create`, `:rf.sub/dispose`).
        - `:renders` — a vector of the `:rf.view/render` trace events.
        - `:other` — a vector of everything else: errors, warnings, machine transitions, frame lifecycle, flows. New kinds of trace event also land here, so existing consumers keep working.
    - A slot with no matching trace event in the input stays `nil`, or `[]` for the vector slots. `:event` is `nil` on the `:ungrouped` bundle, and on a run whose `:rf.event/dispatched` event a ring buffer has already dropped.
- **Example**:
  ```clojure
  ;; In a test: collect the raw stream while one event runs, then read its bundle.
  (let [collected (atom [])]
    (rf/register-listener! :trace ::collect #(swap! collected conj %))
    (rf/dispatch-sync [:todo/add "Buy milk"] {:frame :app/main})
    (rf/unregister-listener! :trace ::collect)
    (let [bundle (->> (projection/group-by-event @collected)
                      (filter #(= [:todo/add "Buy milk"] (:event %)))
                      first)]
      (map :operation (:effects bundle))))   ;; e.g. (:rf.fx/handled …)
  ```

### `domino-bucket`

- **Kind**: function
- **Signature**:
  ```clojure
  (domino-bucket trace-event) → #{:event :handler :fx :effect :sub :render :other}
  ```
- **Description**: Returns the bucket `group-by-event` would put a trace event in. Call it per event when you want your own rollup instead of whole bundles. `:event` fills the bundle's `:event` and `:dispatched` slots; `:handler`, `:fx` and `:other` fill the slots of the same name; `:effect`, `:sub` and `:render` fill the `:effects`, `:subs` and `:renders` vectors.
    - It is total: anything outside the six pipeline stages (errors, warnings, machine transitions, frame lifecycle, flows) returns `:other`.
    - The name comes from the "six dominoes", a mnemonic for the stages of the event pipeline.
- **Example**:
  ```clojure
  (projection/domino-bucket {:op-type :rf.view :operation :rf.view/render})  ;; => :render
  ```
