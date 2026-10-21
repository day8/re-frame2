# re-frame.flows

A flow keeps a derived value in `app-db`. You name the input paths, a pure function over the values at those paths, and the `app-db` path to write; whenever an input value changes, re-frame recomputes the function and writes the result, so no event handler has to remember to. Use a flow when an event handler, a schema or another flow must read the derived value as plain `app-db` data; when only views read it, use a subscription. When one handler needs the value once, compute it in that handler.

Flows ship in the optional `day8/re-frame2-flows` artefact. Require `re-frame.flows` at app boot to load it; without it, `rf/reg-flow`, `(rf/clear :flow id)` and a frame whose `:flows` is not empty throw `:rf.error/flows-artefact-missing`.

```clojure
(:require [re-frame.core  :as rf]
          [re-frame.flows])
```

```clojure
(rf/reg-flow :cart/subtotal
  {:inputs      [[:cart :items] [:tax :rate]]   ;; app-db paths to watch
   :output-path [:cart :subtotal]}              ;; where the result is written
  (fn [items rate]                              ;; input values, in order
    (* (reduce + (map :price items)) (+ 1 (or rate 0)))))
;; => :cart/subtotal

(rf/make-frame {:id :rf/default :flows [:cart/subtotal]})  ;; this frame runs it

;; [:cart :subtotal] now follows the items and the rate. Read it with a plain
;; subscription or from any handler's db; leave writing it to the flow.
```

A flow is a top-level registration under the registrar's `:flow` kind, and a frame runs the flows its `:flows` config names. Register and clear flows through the facade, with `rf/reg-flow` and `(rf/clear :flow id)`; read them with the ordinary registrar queries ([Reading flows](#reading-flows)). The model is taught in [Flows: derived values your handlers can read](../core/flows.md).

## Registering and clearing flows

### `reg-flow`

- **Kind**: macro (`rf/reg-flow`); also a function, `re-frame.flows/reg-flow`, which records no source coordinates
- **Signature**:
  ```clojure
  (reg-flow flow-id metadata derive-fn) → flow-id
  ```
- **Description**: Registers a flow definition and returns `flow-id`. It reads no frame, so it needs no scope and no live frame; registering at namespace load is the normal case.
    - Unlike `reg-sub`'s `:inputs`, each entry is a path (see **Options**), and `derive-fn` receives the values as separate positional arguments, in order. `derive-fn` returns the output; it must be pure and deterministic, and runs whenever an input value changes.
    - The flow runs inside each event of every frame whose `:flows` names it, after the handler and before the new `app-db` installs. [When flows run](#when-flows-run) gives the order.
    - A bare path in `:inputs` reads `app-db`; a path led by `:rf.db/runtime` reads `runtime-db` (route or machine state), with the partition key stripped before the read. The output always goes to `app-db`. See [Deriving from route or machine state](../core/flows.md#deriving-from-route-or-machine-state).
    - Leave `:output-path` to the flow and change the inputs instead. Nothing stops an event writing the output path directly, but the flow overwrites that value the next time an input changes.
    - Registering an id again replaces the definition, which is how hot reload picks up a changed `derive-fn`. Each frame running it recomputes on its next event; if the new definition moves `:output-path`, that event removes the value at the old path. See [Re-registering a flow](../core/flows.md#re-registering-a-flow-and-hot-reload).
    - A rejected registration changes nothing; any previous definition stays in place.
- **Options** (the `metadata` map):

    | Key | Required | Notes |
    |---|---|---|
    | `:inputs` | yes | A vector of paths into `app-db`, or into `runtime-db` when a path starts with `:rf.db/runtime`. Each path is a non-empty vector of concrete segments (keywords, strings, symbols, integers, booleans, UUIDs, instants or `nil`), so a flow cannot read the whole `app-db` through `[]`. The value at each path is passed to `derive-fn` in the same order. |
    | `:output-path` | yes | Where the output is written in `app-db`: a non-empty vector of concrete segments, not starting with `:rf.db/runtime`. It may not overlap this flow's own `:inputs`, or another flow's output path in a frame that runs both ([Activation](#activation)). |
    | `:doc` | no | One sentence on what the flow computes and why; shown in tooling. |
    | `:sensitive` | no | A vector of output subpaths (each a vector of scalar keys; `[]` is the whole output) whose values are sensitive. They are recorded rooted at `:output-path` in each frame that runs the flow, so trace and egress projections redact them. A flow classifies only its own output; its inputs' classification does not carry over. There is no `:sensitive?` boolean: write `:sensitive [[]]` for the whole output. |
    | `:large` | no | A vector of output subpaths (the same shape as `:sensitive`) marked large for wire-size elision. |
    | `:large?` | no | Boolean; `true` marks the whole output large. |
- **Errors**:
    - `:rf.error/invalid-flow-metadata`: `metadata` is not a map, or a `:derive`, `:frame` or `:schema` key sits inside it (the derive function is the third argument, which frames run a flow is each frame's `:flows`, and the output's shape is declared with [`reg-app-schema`](re-frame.schemas.md#reg-app-schema) at `:output-path`). The ex-data's `:value` carries the offending value.
    - `:rf.error/flow-missing-id` / `:rf.error/flow-bad-id`: `flow-id` is `nil` / not a keyword.
    - `:rf.error/flow-bad-inputs`: `:inputs` is not a vector, or an entry is not a non-empty vector of concrete segments; the ex-data's `:bad-entries` lists them.
    - `:rf.error/flow-bad-output`: `derive-fn` is not a function.
    - `:rf.error/flow-bad-path`: `:output-path` is not a non-empty vector of concrete segments; `:bad-elements` lists any bad segments.
    - `:rf.error/flow-bad-marks`: a malformed `:sensitive`, `:large` or `:large?`, or a `:sensitive?` or `:rf.egress/output-sensitivity` key; `:bad-key` names it.
    - `:rf.error/flow-reserved-output-path`: `:output-path` starts with `:rf.db/runtime`. That prefix is only valid in `:inputs`; outputs always write `app-db`.
    - Cycles and output overlaps depend on which flows a frame runs together, so they are checked when a frame is made ([Activation](#activation)).
- **Example**:
  ```clojure
  ;; A runtime-db input (the current route) feeding an app-db output.
  (rf/reg-flow :nav/on-checkout?
    {:inputs      [[:rf.db/runtime :rf.runtime/routing :current :route-id]]
     :output-path [:nav :on-checkout?]}
    (fn [route-id] (= route-id :checkout)))
  ```

### Activation

- **Kind**: frame config key (`:flows` on [`make-frame`](re-frame.core.md#make-frame) and `frame-root`)
- **Signature**:
  ```clojure
  (rf/make-frame {:id frame-id :flows [flow-id …]})
  ```
- **Description**: A frame runs the flows its `:flows` vector names, and no others; omitting it, or passing `[]`, runs none. The order carries no meaning: flows run in dependency order.
    - Each id resolves through the frame's own view of the registrations: live for a frame made without `:images`, through its sealed image for one made with them.
    - A frame runs its flows from its first event, so a seed in `:initial-events` already writes their outputs.
    - Re-making a frame with the same `:id` and a different `:flows` updates it in place: an added flow runs from the next event, and a dropped one has its output removed on that event. A mounted `frame-root` refuses a changed `:flows` with `:rf.error/frame-root-reconfigured`.
    - Destroying a frame drops its evaluation memo. The definitions stay registered, and sibling frames are unaffected.
- **Errors** (the frame is not made):
    - `:rf.error/unregistered-flow`: an id the frame's view does not resolve, or a `:flows` value that is not a vector of keywords. The ex-data carries `:frame`, `:flow-id` and `:flows`.
    - `:rf.error/ambiguous-registration`: an id two namespaces register, in a frame made without `:images`.
    - `:rf.error/flow-path-overlap`: two of the frame's flows have output paths that are equal or one a prefix of the other. The ex-data's `:overlap` is `{:flow-ids [a b] :paths [path-a path-b]}`. Two flows may write one path as long as no frame runs both.
    - `:rf.error/flow-cycle`: the frame's flows depend on each other in a cycle. Flow B depends on flow A, and runs after it in each pass, when one of B's `:inputs` and A's `:output-path` is a prefix of the other. A flow whose own `:inputs` overlap its `:output-path` is a cycle of one, so `:inputs [[:cart]]` with `:output-path [:cart :subtotal]` is refused; read the specific keys instead. The ex-data's `:cycle` names the chain with its first id repeated at the end: `[:a :b :a]`, or `[:cart/subtotal :cart/subtotal]` for a cycle of one.
    - `:rf.error/flows-artefact-missing`: `:flows` is not empty and the flows artefact is not loaded.
    - A re-registration that creates a cycle or an overlap in a running frame's set is caught on that frame's next event, which aborts with `:rf.error/flow-eval-exception` (see [When a flow throws](#when-a-flow-throws)); every later event does the same until a further registration repairs the set.
- **Example**:
  ```clojure
  [rf/frame-root {:id             :rf/default
                  :flows          [:cart/subtotal :nav/on-checkout?]
                  :initial-events [[:cart/initialise]]}
   [cart-view]]
  ```

### Clearing a flow

- **Kind**: function (the facade's `rf/clear`)
- **Signature**:
  ```clojure
  (rf/clear :flow id) → id
  ```
- **Description**: Removes the flow definition `id` from the registrar and returns `id`. `:flow` is one of the kinds [`clear`](re-frame.core.md#clear) accepts.
    - Each frame whose `:flows` names `id` removes the value at its `:output-path` on its next event and warns `:rf.warning/unregistered-flow`, naming the frame and the id. The frame keeps running its other flows, and registering the id again resumes it.
    - Only the leaf is removed. A map parent loses the key, and a parent map left empty stays in place. A vector parent keeps its length and the slot is set to `nil`, so later elements do not shift.
    - A no-op when `id` is not registered.
    - It takes no opts: a trailing opts map raises `:rf.error/registrar-clear-bad-request`. To stop one frame running a flow, re-make that frame without it.
- **Example**:
  ```clojure
  ;; Every frame running :cart/subtotal drops [:cart :subtotal] on its next event.
  (rf/clear :flow :cart/subtotal)
  ```

## When flows run

Flows run inside the event pipeline, in a pass the router makes once per event:

- **After the handler, before the install.** Every event a frame handles runs the flow pass over the flows its `:flows` names as the router's outermost `:after` interceptor: after the handler and every other interceptor, so a `path` interceptor has already spliced its slice back into the whole `app-db`, and before the new `app-db` installs and the event's `:fx` run. An event whose handler returns no `:db` still runs the pass, over the current `app-db`.
- **Against the pending state.** Inputs are read from the state the event is about to commit, its pending `app-db` and `runtime-db`. Each recomputed output is written into the pending `app-db`, so the event installs `app-db` once, with the handler's write and the flows' outputs together. A handler therefore reads a flow's output as it stood after the previous event.
- **In dependency order, dirty-checked.** A flow that reads another flow's output runs after it and sees the value computed in the same pass. A flow whose input values are `=` to those of its last run skips `derive-fn` and emits `:rf.flow/skip`.
- **Checked with the event.** The frame's app-db schemas validate the pending `app-db` with the flows' outputs in it ([What is validated](re-frame.schemas.md#what-is-validated)). When they reject it, nothing installs and the frame's evaluation memo does not advance, since it advances only with the install, so the flows recompute on the next event.
- **Not after a failure.** When the handler or an interceptor throws, the pass does not run. When a flow throws, the event commits nothing; see [When a flow throws](#when-a-flow-throws).
- **Writes made by `:fx`.** The pass has already run when the `:fx` walk starts, so an effect that writes `app-db` or `runtime-db`, such as a machine spawn or a user effect calling `swap-runtime-db!`, lands after it. When the walk changed the frame's state, the event runs the pass again over what the walk left and installs the outputs before it ends, so the flows see the write in the same event and epoch, before any event the handler dispatched. A throw in that second pass installs nothing of it and reports `:rf.error/flow-eval-exception` against the event; the walk's writes stand.
- **Never outside an event.** Registering, re-registering or clearing a flow runs nothing; each frame running it reconciles the change on its next event.
- **Re-registration.** A frame whose memo row was computed with a different definition recomputes that flow on its next event even when its inputs are unchanged. When the new definition moves `:output-path`, that pass removes the value at the old path in the same pending `app-db`.

## When a flow throws

If a flow's `derive-fn` throws, or its result cannot be written at `:output-path`, the event aborts before `app-db` is installed. Nothing from that event is committed, the flows after it in the pass do not run, and the flow is tried again on the frame's next event. A throw in the second pass an event runs after its `:fx` wrote the frame's state installs nothing of that pass; the event's own install and its `:fx` writes stand. The failure is reported as `:rf.error/flow-eval-exception`. [What happens when a derive throws](../core/flows.md#what-happens-when-a-derive-throws) walks through an example.

The error goes through the always-on error reporting, so production builds report it too, to the frame's `:observability :errors` sinks (or the process default's). See [Report errors in production](../core/how-to/report-errors-in-production.md) to wire the listeners.

The error record carries `:where :flow-eval`, `:flow-id`, and a top-level `:phase`:

- `:derive`: your `derive-fn` threw.
- `:output-write`: `derive-fn` returned, but re-frame could not write the value at `:output-path`, usually because the pending `app-db` holds a container there that cannot take the path's last segment. Fix the `:output-path` or the shape at its parent; the `derive-fn` is not at fault.

The phase is decided by which step threw, never by reading the exception message. It sits at the top level of the record so it survives an egress profile that drops `:exception`. The thrown exception's ex-data carries `:rf.flow/failed-id`, `:rf.flow/failed-phase` and `:rf.flow/output-path`.

## Tracing

Development builds trace each flow's lifecycle. Evaluation events carry `:flow-id` and `:frame` in their tags, and their input and output values are elided according to the frame's `:sensitive` / `:large` classification. Registration events name the flow and no frame, because a registration belongs to none.

| Operation | When | Other tags |
|---|---|---|
| `:rf.flow/registered` | A flow id is registered for the first time. | `:inputs`, `:path` (no `:frame`) |
| `:rf.registry/handler-replaced` | A registration replaces a flow (`:kind :flow`, the flow id under `:id`). | `:different-fn?`, comparing the `derive-fn` (no `:frame`) |
| `:rf.flow/computed` | `derive-fn` ran and its result was written. | `:input-values`, `:before`, `:result`, `:path`, `:elapsed-ms` |
| `:rf.flow/skip` | The inputs were `=` to the last run, so `derive-fn` did not run. | `:reason :inputs-value-equal`, `:input-paths-unchanged`; `:repaired? true` when the visit wrote the flow's remembered output back |
| `:rf.flow/cleared` | `(rf/clear :flow id)` removed a registration. | `:path` (no `:frame`) |
| `:rf.warning/unregistered-flow` | A frame's pass found an id its `:flows` names without a registration, and removed that flow's output. | `:frame`, `:path`, `:reason` |
| `:rf.flow/failed` | The flow threw; see [When a flow throws](#when-a-flow-throws). | `:phase`, `:path`, `:exception-message`, `:exception-data`, `:inputs` |

## Reading flows

A flow definition is an ordinary registration, so the registrar queries in [re-frame.core](re-frame.core.md#registrar-queries) read it; `re-frame.flows` has no read API of its own.

```clojure
(rf/registrations {:kind :flow})                 ;; every definition
(rf/handler-meta {:kind :flow :id :cart/subtotal}) ;; one, from the store
(rf/handler-meta {:frame :rf/default :kind :flow :id :cart/subtotal}) ;; as that frame resolves it
(:flows (rf/frame-meta :rf/default))                            ;; the ids a frame runs
```

The map is the one `reg-flow` stored: `:id`, `:inputs`, `:output-path`, `:derive` (the derive function), the other metadata keys (`:doc`, the classification keys), and, for an `rf/reg-flow` call in a development build, source coordinates (`:ns`, `:line`, `:column`, `:file`).

A tool that draws subscriptions, flows, resources, route facts and machine selectors as one dependency graph takes each flow's graph node from `re-frame.flows.tooling`, which it requires directly. `re-frame.flows` has no function for it.

## Framework integration

Not for application code — used by adapters, tools and the test harness.

### `run-flows-on-db`

- **Kind**: function
- **Signature**:
  ```clojure
  (run-flows-on-db frame-id db runtime-db) → {:db app-db, :publish! thunk?, :claims f?}
  ```
- **Description**: Runs the flows a frame's `:flows` names over a pending frame-state and returns the candidate: the resulting `app-db` under `:db`, plus what the caller publishes once that candidate installs. The router runs it as the outermost `:after` interceptor, so it runs last, against the chain's pending `:db` effect and before `app-db` is installed, and again after an `:fx` walk that wrote the frame's state; [When flows run](#when-flows-run) places it in the event.
    - Flows run in dependency order. Each is dirty-checked, and each recomputed result is written with `assoc-in` into the returned `app-db`. Outputs write `app-db` only.
    - `db` is the pending `app-db`; `runtime-db` is the pending `runtime-db` (pass `nil` to resolve only bare `app-db` inputs).
    - The pass does not advance the frame's evaluation memo. When the memo would change, the result carries `:publish!`, a no-argument function the caller invokes once the candidate has installed; when a flow's output classification changes, it carries `:claims`, a function of `runtime-db` the caller applies to the candidate `runtime-db` it installs.
    - A throw stops the pass and re-raises as `:rf.error/flow-eval-exception` (see [When a flow throws](#when-a-flow-throws)). The router then discards the pending `:db` effect, so the event commits nothing and the memo stays as it was.

### `reset-flows!`

- **Kind**: function
- **Signature**:
  ```clojure
  (reset-flows!) → nil
  ```
- **Description**: Test-only. Drops every frame's evaluation memo, so no flow is skipped against a stale, `=`-equal entry. Definitions are registrations, which a test fixture snapshots and restores with the rest of the registrar. `re-frame.test-support`'s `make-reset-runtime-fixture` calls it around each test. Returns `nil`.

### `reset-last-inputs!`

- **Kind**: function
- **Signature**:
  ```clojure
  (reset-last-inputs!) → nil
  ```
- **Description**: Test-only. Drops every frame's evaluation memo, as `reset-flows!` does. Call it between tests that keep their frames but must not skip a recompute against a stale, `=`-equal entry. Returns `nil`.

## See also

- [re-frame.core](re-frame.core.md): the `rf/reg-flow` facade entry, `rf/clear`, `make-frame`'s `:flows`, and the registrar queries.
- [Migration reference](../../migration/from-re-frame-v1/README.md): re-frame v1's `on-changes` and `enrich` are replaced by flows (and, for `enrich`, schemas).
