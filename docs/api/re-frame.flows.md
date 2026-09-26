# re-frame.flows

A flow keeps a derived value in `app-db`. You name the input paths, a pure function over the values at those paths, and the `app-db` path to write; whenever an input value changes, re-frame recomputes the function and writes the result, so no event handler has to remember to. Use a flow when an event handler, a schema or another flow must read the derived value as plain `app-db` data; when only views read it, use a subscription. When one handler needs the value once, compute it in that handler.

Flows ship in the optional `day8/re-frame2-flows` artefact. Require `re-frame.flows` at app boot to load it; without it, `rf/reg-flow` and `(rf/clear :flow id)` throw `:rf.error/flows-artefact-missing`.

```clojure
(:require [re-frame.core  :as rf]
          [re-frame.flows :as flows])
```

```clojure
(rf/reg-flow :cart/subtotal
  {:inputs      [[:cart :items] [:tax :rate]]   ;; app-db paths to watch
   :output-path [:cart :subtotal]               ;; where the result is written
   :frame       :app/main}                      ;; a flow belongs to one frame
  (fn [items rate]                              ;; input values, in order
    (* (reduce + (map :price items)) (+ 1 rate))))
;; => :cart/subtotal

;; [:cart :subtotal] now follows the items and the rate. Read it with a plain
;; subscription or from any handler's db; leave writing it to the flow.
```

Register and clear flows through the facade, with `rf/reg-flow` and `(rf/clear :flow id)`. There is no `clear-flow` function on either namespace. The introspection functions and the test resets are called on `flows/…`. The model is taught in [Flows: derived values your handlers can read](../core/flows.md).

## Registering and clearing flows

### `reg-flow`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-flow flow-id metadata derive-fn) → flow-id
  ```
- **Description**: Registers a flow against a frame and returns `flow-id`. Called as `rf/reg-flow`, which records the call site's source coordinates for tools. `re-frame.flows/reg-flow` is the same registration as a plain function, for code that must `apply` it; it records no source coordinates.
    - `:inputs` is the key `reg-sub` uses, but the entries differ: each is a path into `app-db` (or `runtime-db`), not a subscription query vector. `derive-fn` receives the value at each path as a separate positional argument, in order, not as one vector, and returns the output. It must be pure and deterministic; it runs whenever an input value changes.
    - Inputs are read after the event handler and its interceptors have run, from the state the event is about to commit. A bare path reads `app-db`; a path led by `:rf.db/runtime` reads `runtime-db` (route or machine state), with the partition key stripped before the read. The output always goes to `app-db`.
    - A handler reads the output as it stood after the previous event. When the handler changes an input, the new output is written after the handler returns, in the same commit.
    - Leave `:output-path` to the flow and change the inputs instead. Nothing stops an event writing the output path directly, but the flow overwrites that value the next time an input changes.
    - A flow registered this way writes its first output during the frame's next event. To have the output in place when a dispatch returns, register with [`:rf.fx/reg-flow`](#rffxreg-flow).
    - A flow belongs to one frame (see `:frame` below). The same id can register against several frames with different definitions.
    - Registering an id again in the same frame replaces the flow and recomputes it on the next event, which is how hot reload picks up a changed `derive-fn`. If the new definition moves `:output-path`, the value at the old path is removed.
    - A rejected registration changes nothing; any previous definition stays in place.
    - Destroying a frame removes its flows and their cached inputs. Sibling frames are unaffected.
- **Options** (the `metadata` map):

    | Key | Required | Notes |
    |---|---|---|
    | `:inputs` | yes | A vector of paths into `app-db`, or into `runtime-db` when a path starts with `:rf.db/runtime`. The value at each path is passed to `derive-fn` in the same order. |
    | `:output-path` | yes | Where the output is written in `app-db`. |
    | `:doc` | no | One sentence on what the flow computes and why; shown in tooling. |
    | `:frame` | no | The frame to register against (a frame-id keyword or a frame value). Defaults to the surrounding `with-frame` scope. |
    | `:schema` | no | Malli schema for the output, validated on every recompute in development builds and removed from production builds. A failure emits `:rf.error/schema-validation-failure` with `:where :flow-output`, and the output is still written; the trace points at the bug. Validation goes through the registered validator, so without `re-frame.schemas` loaded the schema is not checked. |
    | `:sensitive` | no | A vector of output subpaths (each a vector of scalar keys; `[]` is the whole output) whose values are sensitive. They are recorded rooted at `:output-path`, so trace and egress projections redact them. A flow classifies only its own output; its inputs' classification does not carry over. There is no `:sensitive?` boolean: write `:sensitive [[]]` for the whole output. `:sensitive?` and `:rf.egress/output-sensitivity` both raise `:rf.error/flow-bad-marks`. |
    | `:large` | no | A vector of output subpaths (the same shape as `:sensitive`) marked large for wire-size elision. |
    | `:large?` | no | Boolean; `true` marks the whole output large. |

    Malformed `:sensitive`, `:large` or `:large?` values raise `:rf.error/flow-bad-marks`.
- **Errors**:
    - `:rf.error/invalid-flow-metadata`: `metadata` is not a map, or a `:derive` key sits inside it (the derive function is the third argument).
    - `:rf.error/flow-missing-id`, `:rf.error/flow-bad-id`, `:rf.error/flow-bad-inputs`, `:rf.error/flow-bad-output`, `:rf.error/flow-bad-path`, `:rf.error/flow-bad-marks`: a malformed id, `:inputs`, `derive-fn`, `:output-path` or classification key.
    - `:rf.error/flow-reserved-output-path`: `:output-path` starts with `:rf.db/runtime`. That prefix is only valid in `:inputs`; outputs always write `app-db`.
    - `:rf.error/no-frame-context`: no `:frame` key and no surrounding scope.
    - `:rf.error/flow-frame-not-live`: the target frame is absent or destroyed.
    - `:rf.error/flow-path-overlap`: `:output-path` is a prefix of, or prefixed by, another flow's output path in the same frame.
    - `:rf.error/flow-cycle`: the registration would make the frame's flow dependencies cyclic.
- **Example**:
  ```clojure
  ;; A runtime-db input (the current route) feeding an app-db output.
  (rf/reg-flow :nav/on-checkout?
    {:inputs      [[:rf.db/runtime :rf.runtime/routing :current :route-id]]
     :output-path [:nav :on-checkout?]
     :frame       :app/main}
    (fn [route-id] (= route-id :checkout)))
  ```

### Clearing a flow

- **Kind**: function (the facade's `rf/clear`)
- **Signature**:
  ```clojure
  (rf/clear :flow id)
  (rf/clear :flow id {:frame target})
  ```
- **Description**: Removes a flow from one frame and removes the value at its `:output-path` from that frame's `app-db`. Returns `id`. `:flow` is one of the kinds [`clear`](re-frame.core.md#clear) accepts.
    - Only the leaf is removed (`dissoc-in`); a parent map left empty stays in place. Other frames are untouched.
    - A no-op when `id` is not registered in the frame.
    - The frame is the `:frame` opt (a frame-id keyword or a live frame value), else the surrounding scope; with neither, it raises `:rf.error/no-frame-context`. `:frame` is the only accepted key: a near-miss such as `{:fram :session}` raises `:rf.error/registrar-clear-bad-request` before any frame is resolved, so a typo never clears the ambient frame's flow.
    - Called outside an event, it recomputes the frame's other flows before it returns. Any flow that reads the cleared `:output-path` and has been evaluated since it registered has already recomputed without it, so you do not dispatch a follow-up event.
    - That recompute does not evaluate a flow registered or re-registered since the frame's last event. Such a flow, and anything derived from it, keeps its current value until the next event, as after a direct `reg-flow`.
    - If a dependent's `derive-fn` throws during that recompute, `:rf.error/flow-eval-exception` propagates to the caller. The flow stays cleared and its output stays removed.
    - Called from a handler body, the current event's flow pass does the recompute. Called from an effect (`:rf.fx/clear-flow` or one you registered), the `:fx` walk recomputes the frame's flows when it ends. The guarantee is the same in every case.
- **Example**:
  ```clojure
  ;; Removes :cart/subtotal and [:cart :subtotal] from this frame's app-db.
  (rf/clear :flow :cart/subtotal)
  ```

## Effects

Two reserved fx-ids register and clear flows from an event handler. Both act on the dispatching frame, and both are no-ops when the flows artefact is not loaded.

### `:rf.fx/reg-flow`

- **Kind**: effect (reserved fx-id)
- **Payload**: `[flow-id metadata derive-fn]`, the same three arguments `reg-flow` takes.
- **Description**: Registers a flow in the dispatching frame, which is passed as the `:frame` metadata key. The flow's initial output is written by the time the dispatch returns, so there is no follow-up event to write. Use it to switch a derived value on from an event handler, for example when a feature or a wizard step starts.
    - The flow is evaluated at once, against the state the registering event committed, so `derive-fn` receives `nil` for any input that neither this event nor an earlier one has written.
    - The `:fx` walk runs after the event's flows have been evaluated, so the walk enqueues one internal settling event on the same frame. Run-to-completion drains it before your dispatch returns.
    - The settle is a separate event with its own `app-db` install, so the registering event still installs `app-db` once.
    - If the new flow's `derive-fn` throws, the registering event's `:db` has already been committed and stays. The failure surfaces as `:rf.error/flow-eval-exception` with the usual `:phase` (see [When a flow throws](#when-a-flow-throws)).
    - The settle is idempotent: over a frame that is already settled it recomputes and installs nothing, so dispatching an extra event to update the flows is harmless.
- **Example**:
  ```clojure
  (rf/reg-event :wizard/enter-step-2
    (fn [_ _]
      ;; Step 2 shows a running total derived from the quantity and unit
      ;; price the user entered on step 1.
      {:fx [[:rf.fx/reg-flow [:wizard/order-total
                              {:inputs      [[:wizard :qty] [:wizard :unit-price]]
                               :output-path [:wizard :order-total]}
                              (fn [qty unit-price] (* qty unit-price))]]]}))
  ;; [:wizard :order-total] is populated when this dispatch returns.
  ```

### `:rf.fx/clear-flow`

- **Kind**: effect (reserved fx-id)
- **Payload**: the flow id.
- **Description**: Clears a flow in the dispatching frame, as `(rf/clear :flow id)` does. Its output path is removed by the time the dispatch returns. This is an fx-id; there is no `clear-flow` function behind it to call.
- **Example**:
  ```clojure
  ;; Turning a feature off removes its flow and the flow's output.
  (rf/reg-event :cart/remove-discount
    (fn [_ _]
      {:fx [[:rf.fx/clear-flow :cart/discount-rate]]}))
  ```

## When a flow throws

If a flow's `derive-fn` throws, or its result cannot be written at `:output-path`, the event aborts before `app-db` is installed. Nothing from that event is committed, the flows after it in the pass do not run, and the flow is tried again on the frame's next event. The failure is reported as `:rf.error/flow-eval-exception`.

The error goes through the always-on error reporting, so production builds report it too, to the frame's `:observability :errors` sinks (or the process default's). See [Report errors in production](../core/how-to/report-errors-in-production.md) to wire the listeners.

The error record carries `:where :flow-eval`, `:flow-id`, and a top-level `:phase`:

- `:derive`: your `derive-fn` threw.
- `:output-write`: `derive-fn` returned, but re-frame could not write the value at `:output-path`, usually because the pending `app-db` holds a container there that cannot take the path's last segment. Fix the `:output-path` or the shape at its parent; the `derive-fn` is not at fault.

The phase is decided by which step threw, never by reading the exception message. It sits at the top level of the record so it survives an egress profile that drops `:exception`. The thrown exception's ex-data carries `:rf.flow/failed-id`, `:rf.flow/failed-phase` and `:rf.flow/output-path`.

## Introspection and tooling

Read-only functions over the per-frame flow registry, for the REPL, for tools such as Xray and re-frame2-pair, and for conformance fixtures. None is exported from `re-frame.core`. Flows are stored per frame rather than in the registrar, so `rf/registrations` and `rf/handler-meta` do not list them: `:kind :flow` raises `:rf.error/registrar-kind-not-queryable`. Read flows with these functions instead.

### `flows-snapshot`

- **Kind**: function
- **Signature**:
  ```clojure
  (flows-snapshot) → {frame-id {flow-id flow-map}}
  ```
- **Description**: Returns every frame's registered flows. The value is a snapshot; do not mutate it.
- **Example**:
  ```clojure
  ;; Which flows are registered, and against which frames?
  (keys (get (flows/flows-snapshot) :app/main))   ;; => (:cart/subtotal :nav/on-checkout?)
  ```

### `flows`

- **Kind**: function
- **Signature**:
  ```clojure
  (flows {:frame f}) → {flow-id flow-map}
  ```
- **Description**: Returns every flow registered in one frame, or `{}` when the frame has none. This is one frame's slice of `flows-snapshot`.
    - `:frame` is required and names a frame target (a frame-id keyword or a frame value), the same targets `rf/registrations` accepts. A call without `:frame`, or with a non-map argument, raises `:rf.error/no-frame-context`.
- **Example**:
  ```clojure
  (keys (flows/flows {:frame :app/main}))   ;; => (:cart/subtotal :nav/on-checkout?)
  ```

### `flow-meta`

- **Kind**: function
- **Signature**:
  ```clojure
  (flow-meta {:frame f :id flow-id}) → flow-map or nil
  ```
- **Description**: Returns the registration map of one flow in a frame, or `nil`.
    - The map is the one `reg-flow` stored: source coordinates (`:ns`, `:line`, `:file`), `:inputs`, `:derive`, `:output-path`, and any classification keys.
    - Each frame has its own definition, so the same `flow-id` in two frames returns two different maps.
    - Both keys are required. There is no ambient frame and no positional frame argument (a live frame value is itself a map, so it could not be told apart from the opts). A call without `:frame`, or with a non-map argument, raises `:rf.error/no-frame-context`.
- **Example**:
  ```clojure
  ;; The :app/main frame's definition of :cart/subtotal.
  (flows/flow-meta {:frame :app/main :id :cart/subtotal})
  ```

A tool that draws subscriptions, flows, resources, route facts and machine selectors as one dependency graph takes each flow's graph node from `re-frame.flows.tooling`, which it requires directly. `re-frame.flows` has no function for it.

## Framework integration

Not for application code — used by adapters, tools and the test harness.

### `run-flows-on-db`

- **Kind**: function
- **Signature**:
  ```clojure
  (run-flows-on-db frame-id db runtime-db)
  (run-flows-on-db frame-id db runtime-db {:exact-owner-token token})
  ```
- **Description**: Runs one frame's flows over a pending frame-state and returns the resulting `app-db`. The router installs it as the outermost `:after` interceptor, so it runs last, against the chain's pending `:db` effect and before `app-db` is installed.
    - Flows run in dependency order. Each is dirty-checked, and each recomputed result is written with `assoc-in` into the returned `app-db`. Outputs write `app-db` only.
    - `db` is the pending `app-db`; `runtime-db` is the pending `runtime-db` (pass `nil` to resolve only bare `app-db` inputs).
    - Inside an event, the 3-arity fences the pass to the current event's owner token; outside one, it runs unfenced. The 4-arity fences the pass to an explicit `:exact-owner-token`, which the artefact's own out-of-drain settle uses.
    - A throw stops the pass, rolls back the frame's dirty-check state, and re-raises as `:rf.error/flow-eval-exception` (see [When a flow throws](#when-a-flow-throws)). The router then discards the pending `:db` effect, so the event commits nothing.

### `reset-flows!`

- **Kind**: function
- **Signature**:
  ```clojure
  (reset-flows!) → nil
  ```
- **Description**: Test-only. Clears every frame's flows, the dirty-check input cache, and any pending removals of abandoned output paths, so a flow re-registered after the reset cannot be skipped against a stale, `=`-equal entry. Returns `nil`.

### `reset-last-inputs!`

- **Kind**: function
- **Signature**:
  ```clojure
  (reset-last-inputs!) → nil
  ```
- **Description**: Test-only. Clears every frame's dirty-check input cache and leaves the flows themselves registered. `re-frame.test-support`'s `make-reset-runtime-fixture` uses it to drop stale cached inputs between tests. Returns `nil`.

## See also

- [re-frame.core](re-frame.core.md): the `rf/reg-flow` facade entry, `rf/clear`, and the `:fx` mechanism the two effects run on.
- [Migration reference](../../migration/from-re-frame-v1/README.md): re-frame v1's `on-changes` and `enrich` are replaced by flows (and, for `enrich`, schemas).
