# re-frame.trace.projection

`re-frame.trace.projection` is the event-bundle projection surface. The raw trace
stream is event-at-a-time; this namespace folds it into one **event bundle** per
pipeline run — one dequeued event, with the event vector, handler emit, fx-map
emit, effects, sub-runs and renders already split into named slots.

- **Pure data.** No frame, no registry, no router — JVM and CLJS run the same code.
  A post-mortem tool folding a saved buffer gets the same shape as a live listener.
- **Not on the facade.** These two fns are reached by requiring this namespace
  directly; `re-frame.core` carries no re-export.
- Dev-only in practice, because its input is the dev-only trace stream.

```clojure
(:require [re-frame.trace.projection :as projection])
```

The normative contract — slot names, ordering, the `:ungrouped` bucket and the
additivity rule — is [Spec 009 §Event-bundle projection](../../spec/009-Instrumentation.md#event-bundle-projection-group-by-event--domino-bucket).
See [Observability](../core/observability.md) for the guide-level picture and
[`re-frame.core`](re-frame.core.md) for the trace-buffer reads that feed it.

## Projection

### `group-by-event`

- **Kind**: function
- **Signature**:
  ```clojure
  (group-by-event events) → vector of event records
  ```
- **Description**: A pure data projection. It turns a list of trace events into per-event records `{:dispatch-id :parent-dispatch-id :frame :event :dispatched :handler :fx :effects :subs :renders :other}` — one record per `[frame dispatch-id]` pipeline run, sorted by emission order. JVM-runnable. Events carrying no `:rf.trace/dispatch-id` tag collect under `:dispatch-id :ungrouped`.
- **Example**:
  ```clojure
  (projection/group-by-event (rf/trace-buffer :app/main {:flat true}))
  ```

### `domino-bucket`

- **Kind**: function
- **Signature**:
  ```clojure
  (domino-bucket trace-event) → #{:event :handler :fx :effect :sub :render :other}
  ```
- **Description**: Classify a raw trace event into the pipeline-stage slot used by `group-by-event`. Pure, and **total** — anything outside the six-domino cascade (errors, warnings, machine transitions, frame lifecycle, flows) lands in `:other`. Call it directly per event when you want a custom rollup instead of the full bundle. (The "domino" name is the first-contact mnemonic for those stages.)
- **Example**:
  ```clojure
  (projection/domino-bucket {:op-type :rf.view :operation :rf.view/render})  ;; => :render
  ```
