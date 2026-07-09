# re-frame.flows

A **flow** is registered, named, observable, restorable derived state materialised into `app-db`. You declare its `:inputs` (a vector of frame-state paths — not subs), a pure `:derive` computation over the values at those paths, and the `:output-path` in `app-db` to write to. The runtime watches the input paths, recomputes the output whenever any input value changes, and writes the result.

Input paths read either partition of the frame:

- A **bare** path reads `app-db` (the common case).
- A path led by `:rf.db/runtime` reads `runtime-db` (route / machine state).

Outputs are always written to `app-db`.

```clojure
(:require [re-frame.flows :as flows])
```

See [Flows: derived values your handlers can read](../core/flows.md) for the conceptual companion.

> **Note** — `reg-flow` is also exported from the `re-frame.core` facade, so the examples below register with `rf/reg-flow` (the conventional call site). `clear-flow`, the introspection accessors, and the test-support resets are called on the `flows` alias. The full `reg-flow` contract lives here.

## Registering and clearing flows

### `reg-flow`

- **Kind**: function
- **Signature**:
  ```clojure
  (reg-flow flow-id metadata derive-fn)
  ```
- **Description**: Register a flow. Returns `flow-id` (per the `reg-*` return-value convention). A rejected registration mutates nothing; the prior definition, if any, survives.

  The 3-slot grammar is `flow-id` first, the pure `derive-fn` last, and `metadata` (the reflection-config map) in the middle. `metadata` carries:

  - `:inputs` and `:output-path` — both **required**.
  - `:doc` / `:schema` — optional.
  - `:sensitive` / `:large` / `:large?` — optional output-classification keys.
  - `:frame` — optional; selects the owning frame.

  Raises:

  - `:rf.error/invalid-flow-metadata` — non-map metadata, or a `:derive` left inside the metadata map.
  - `:rf.error/flow-missing-id` / `:rf.error/flow-bad-id` / `:rf.error/flow-bad-inputs` / `:rf.error/flow-bad-output` / `:rf.error/flow-bad-path` / `:rf.error/flow-bad-marks` — shape validation of the id, `:inputs`, `derive-fn`, `:output-path`, and classification keys.
  - `:rf.error/no-frame-context` — no surrounding scope and no `:frame` key.
  - `:rf.error/flow-frame-not-live` — the target frame is absent or destroyed.
  - `:rf.error/flow-path-overlap` — the `:output-path` stands in a prefix relationship with a same-frame sibling's.
  - `:rf.error/flow-cycle` — the registration would make the frame's dependency graph cyclic.
- **Example**:
  ```clojure
  (rf/reg-flow :cart/subtotal
    {:inputs      [[:cart :items] [:tax :rate]]
     :output-path [:cart :subtotal]}
    (fn [items rate] (* (reduce + (map :price items)) (+ 1 rate))))
  ;; => :cart/subtotal
  ```

### `clear-flow`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-flow id)
  (clear-flow id opts)
  ```
- **Description**: Deregister the flow from the named frame and `dissoc-in` its `:output-path` from that frame's `app-db` only. Returns `nil`.

  - Leaf-only removal: an emptied parent map is left in place.
  - Sibling frames' state is preserved.
  - The frame resolves from the `opts` `:frame` key (a frame-id keyword), else the surrounding scope. With no scope and no `:frame`, it raises `:rf.error/no-frame-context`.
  - A no-op when `id` is not registered against the frame.
- **Example**:
  ```clojure
  ;; Deregister :cart/subtotal; clears [:cart :subtotal] in this frame's app-db.
  (flows/clear-flow :cart/subtotal)
  ```

## The 3-slot grammar

`reg-flow` is `(reg-flow flow-id metadata derive-fn)`. A minimal flow:

```clojure
(rf/reg-flow :cart/subtotal
  {:inputs [[:cart :items] [:tax :rate]]      ;; vector of frame-state paths
   :output-path [:cart :subtotal]}
  (fn [items rate]                             ;; values arrive positionally
    (let [subtotal (reduce + (map :price items))]
      (* subtotal (+ 1 rate)))))

;; Now [:cart :subtotal] in app-db is always derived. Read it via a plain
;; sub or a plain handler. Adding an item triggers recompute; updating the
;; rate triggers recompute; nothing else writes [:cart :subtotal].
```

A flow can also derive from `runtime-db` (route / machine state) by leading an
input path with `:rf.db/runtime` — the partition key is stripped before the
read. Outputs stay `app-db`-only.

```clojure
(rf/reg-flow :nav/on-checkout?
  {:inputs [[:rf.db/runtime :rf.runtime/routing :current :route-id]] ;; runtime-db input
   :output-path [:nav :on-checkout?]}                                ;; app-db output
  (fn [route-id] (= route-id :checkout)))
```

`:inputs` is a positional vector of frame-state paths. The values at those paths arrive as positional args to the `derive-fn`, in the same order. (A map-keyed `:inputs` form is a deferred design option, not v1.) Each path reads against the pending frame-state's two partitions: a bare path reads `app-db`, and a path led by `:rf.db/runtime` reads `runtime-db`. The metadata slot is pure data — no fn registration with a separate id, no interceptor wiring, no closure capture. The conformance harness validates flows by walking the registered data and applying it to a synthesised frame-state.

| Slot / key | Required | Notes |
|---|---|---|
| `flow-id` (slot 1) | yes | The registry key. Use a namespaced keyword. |
| `derive-fn` (slot 3) | yes | `(fn [in-1 in-2 ...] new-output)`. Pure; receives the input values positionally; called every time inputs change; must be deterministic. |
| `:inputs` (metadata) | yes | A vector of frame-state path vectors. Read positionally; the value at each path is passed to the `derive-fn` in order. A bare path reads `app-db`; a path led by `:rf.db/runtime` reads `runtime-db`. |
| `:output-path` (metadata) | yes | Where to write the output in `app-db`. |
| `:doc` (metadata) | no | One-sentence what-and-why; surfaces in tooling. |
| `:frame` (metadata) | no | The frame the flow registers against (the override; else the surrounding `with-frame` scope). |
| `:schema` (metadata) | no | Malli schema for the output value. Validated on every recompute in dev; elided in production, like all schema validation. On failure the runtime emits `:rf.error/schema-validation-failure :where :flow-output`. The failure is **observational**: the output is still written, and the trace surfaces the producer bug. Validation routes through the registered validator, so an app without the schemas artefact pays nothing. |
| `:sensitive` (metadata) | no | Output data-classification: a vector of output subpaths (each a vector of scalar keys; `[]` marks the whole output) declared sensitive. Installed into the frame's elision registry rooted at `:output-path`, so trace and egress projections redact those slots. There is no input→output propagation: a flow classifies its own output only. Malformed values are rejected (`:rf.error/flow-bad-marks`), as are the retired `:sensitive?` boolean spelling and `:rf.egress/output-sensitivity`. |
| `:large` (metadata) | no | Output data-classification: a vector of output subpaths (same shape as `:sensitive`) declared large for wire-size elision. Malformed values are rejected (`:rf.error/flow-bad-marks`). |
| `:large?` (metadata) | no | Boolean; `true` marks the whole output large. Non-boolean values are rejected (`:rf.error/flow-bad-marks`). |

#### Frame-scoping

Flows are single-store: the `:flow` registrar kind is reserved-but-empty (`reg-flow` does not write it). The same id can register against multiple frames with divergent definitions.

For per-frame discovery, read the encapsulated runtime registry through its public accessors: `re-frame.flows/flows-snapshot` (returns the `{frame-id {flow-id flow-map}}` shape) or the frame-scoped `re-frame.flows/flow-meta-at`. Do not reach for the private registry atom (in `re-frame.flows.registry`); it is an implementation detail behind the snapshot contract. The per-frame runtime registry is the source of truth for evaluation.

#### Frame-destroy teardown

`destroy-frame!` releases every per-frame piece of flow state — the frame's flow-registry entry, its `last-inputs` container, and any pending abandoned-output-path moves. Sibling frames' state is preserved. (There is no registrar entry to release: the `:flow` registrar kind is reserved-but-empty.)

## Keyword surfaces

Two reserved fx-ids register or clear a flow at runtime from inside an event handler:

| `[fx-id args]` | Args | Status | Intuition |
|---|---|---|---|
| `[:rf.fx/reg-flow [flow-id metadata derive-fn]]` | the 3-slot triple (same shape as `reg-flow`) | v1 | Register a flow at runtime via `:fx`. The dispatching frame threads through as the `:frame` metadata key. |
| `[:rf.fx/clear-flow id]` | flow id | v1 | Clear a registered flow at runtime via `:fx`. |

The arguments mirror `reg-flow` / `clear-flow` exactly: the same 3-slot triple, the same flow id. (An fx body's return value is not observable.) When the flows artefact is not on the classpath, both effects no-op.

```clojure
;; Clear a runtime-registered flow from inside a handler — e.g. disengaging a
;; feature gate. The flow's :output-path is dissoc'd from this frame's app-db.
(rf/reg-event :cart/remove-discount
  (fn [_ _]
    {:fx [[:rf.fx/clear-flow :cart/discount-rate]]}))
```

#### The one-event lag — the least-obvious thing about flows

> A flow registered with `:rf.fx/reg-flow` **does not compute its initial output during that event.** It first fires on the **next** drain on the same frame.

The reason is structural: the `:fx` walk is the last drain stage, and it runs after the flow transform has already evaluated this event's flows. The newly-registered flow was not in the registry when the transform walked, so it has nothing to compute on this event. It computes on the next drain on that frame.

In the common case the lag is invisible: you register a flow in an `:enter`-style handler, and the user's next interaction materialises the output. When you need the initial value now, dispatch a follow-up no-op event from the same handler to re-trigger the drain. The flow is in the registry by the time that dispatched event drains:

```clojure
(rf/reg-event :wizard/enter-step-2
  (fn [_ _]
    ;; Step 2 wants a running order total derived from the quantity and
    ;; unit price the user entered back on step 1.
    {:fx [[:rf.fx/reg-flow [:wizard/order-total
                            {:inputs      [[:wizard :qty] [:wizard :unit-price]]
                             :output-path [:wizard :order-total]}
                            (fn [qty unit-price] (* qty unit-price))]]
          [:dispatch [:wizard/settle]]]}))   ;; flow computes on THIS drain

(rf/reg-event :wizard/settle (fn [{:keys [db]} _] {:db db}))   ;; no-op; exists only to drain
```

The one-event lag before a newly registered flow first computes preserves the one-install-per-event invariant.

## Introspection and tooling

Read-only views over the per-frame flow registry. They never touch the registration write-path. Tools (Xray, re-frame2-pair) and conformance fixtures consume them. None of the three has a `re-frame.core` facade export.

### `flows-snapshot`

- **Kind**: function
- **Signature**:
  ```clojure
  (flows-snapshot)
  ```
- **Description**: Return the per-frame flow registry value: `{frame-id {flow-id flow-map}}`. This is the public seam for observing the registry shape. Read it rather than dereferencing the private `flows` atom. The return value is a snapshot; observers must not mutate it.
- **Example**:
  ```clojure
  ;; Which flows are registered, and against which frames?
  (keys (get (flows/flows-snapshot) :app))   ;; => (:cart/subtotal :nav/on-checkout?)
  ```

### `flow-meta-at`

- **Kind**: function
- **Signature**:
  ```clojure
  (flow-meta-at flow-id)
  (flow-meta-at flow-id opts)
  ```
- **Description**: Return the registration metadata map for `flow-id` in a frame, or `nil`. This is the canonical per-frame flow introspection surface.

  - Returns the full flow-map stamped at `reg-flow`: its source-coords `:ns` / `:line` / `:file`, `:inputs`, `:derive`, `:output-path`, and any output-classification keys.
  - Frame-divergent by construction: the same `flow-id` registered against two frames returns each frame's own definition.
  - The zero-`opts` arity resolves the frame from the carried-invariant scope (raising `:rf.error/no-frame-context` under no scope).
  - `opts` is a map whose `:frame` names the frame target (a keyword id or frame value). The keyword sugar `(flow-meta-at flow-id frame-id)` is accepted.
- **Example**:
  ```clojure
  ;; The :app frame's own definition of :cart/subtotal.
  (flows/flow-meta-at :cart/subtotal {:frame :app})
  ```

### `flow-algebra-view`

- **Kind**: function (JVM)
- **Signature**:
  ```clojure
  (flow-algebra-view)
  (flow-algebra-view frame-id)
  ```
- **Description**: Return the static derivation-algebra view of every registered flow. Each flow is lowered into the normalized derivation-node shape, so a tool can show subscriptions, flows, resources, route facts, and machine selectors as one family.

  Each node carries:

  - the flow's `:id`
  - `:kind` `:derivation`
  - `:source-form` `{:kind :reg-flow :id flow-id}`
  - the declared `:inputs`, each lowered to `[:db path]` or `[:runtime …rest]`
  - `:output` `[:db <output-path>]`
  - the fixed classifications: `:storage` `:app-db`, `:evaluation` `:after-event`, `:lifecycle` `:frame`, `:materialized?` `true`
  - `:owner` `[:frame frame-id]`
  - the opaque `:derive` token
  - `:source` / `:schema` / `:doc` when present

  The zero-arity form returns `{frame-id {flow-id node}}`. The one-arity form returns `{flow-id node}` for a single frame. This is a JVM convenience alias; CLJS consumers call `re-frame.flows.tooling/flow-algebra-view` directly.

## Runtime and test-support internals

The flow-transform entry point the router installs, plus the test-fixture resets. Applications do not call these directly.

### `run-flows-on-db`

- **Kind**: function
- **Signature**:
  ```clojure
  (run-flows-on-db frame-id db runtime-db)
  ```
- **Description**: The outermost-`:after` flow transform. It walks this frame's registered flows in topological order over the pending frame-state, dirty-checks each one, and `assoc-in`s each recomputed result into a transformed `app-db`. It returns the flow-augmented `app-db` value.

  - `db` is the pending `app-db` partition; `runtime-db` is the pending `runtime-db` partition (pass `nil` to resolve only bare `app-db` inputs).
  - The router installs and calls this as the outermost `:after` interceptor. It fires last, against the chain's pending `:db` effect and before the `:db` install. Applications never call it.
  - Flow outputs write `app-db` only.
  - A flow `:derive` throw halts the walk (downstream flows do not run), rolls back the frame's dirty-check bookkeeping, and re-raises as `:rf.error/flow-eval-exception` (ex-data carries `:rf.flow/failed-id`). The router discards the pending `:db` effect, so the event aborts with no partial commit.

### `reset-flows!`

- **Kind**: function
- **Signature**:
  ```clojure
  (reset-flows!)
  ```
- **Description**: Test-only. Clear the per-frame flow registry and the paired dirty-check `last-inputs` state, plus any pending abandoned-output-path moves. This ensures a re-registration after a reset cannot silently no-op against a stale `=`-equal entry. Returns `nil`.

### `reset-last-inputs!`

- **Kind**: function
- **Signature**:
  ```clojure
  (reset-last-inputs!)
  ```
- **Description**: Test-only. Clear all per-frame dirty-check `last-inputs` containers (dropping every frame's inner atom) while leaving the flow registry itself intact. Used by the reset-runtime fixture to drop stale per-flow input rows between tests. Returns `nil`.

## Failure semantics

**Production-survivable.** A throw inside a flow's `:derive` fn surfaces as `:rf.error/flow-eval-exception` on the always-on error-emit substrate. Registered `register-error-listener!` callbacks fire even under CLJS `:advanced` + `goog.DEBUG=false`. The error is not trace-only; production deployments catch it.

See [Report errors in production](../core/how-to/report-errors-in-production.md) for wiring the always-on error listeners that catch these in a deployed app.

## Flow vs sub: when to reach for which

- **Sub** when the value is computed on read and never written back to `app-db`. Cached; ref-counted; lazy.
- **Flow** when the value should live in `app-db` — because other code reads it via path, other flows depend on it via path, or you want it captured by the epoch snapshot for time-travel.

A useful rule: if you find yourself writing `(reg-sub ::derived-total (fn [...]))` and immediately needing to read its value from a plain handler via `subscribe-once`, that's a flow. If you find yourself writing a flow that no path-shaped consumer ever reads (only sub-shaped ones do), it should probably be a sub.

## See also

- [re-frame.core](re-frame.core.md) — the ergonomic facade `reg-flow` is also exported from, plus the `:fx` effect mechanism the flow fx-ids ride.
- [Flows: derived values your handlers can read](../core/flows.md) — the conceptual companion to this reference.
- [Migration reference](../../migration/from-re-frame-v1/README.md) — `on-changes` (replaced by flows) and `enrich` (replaced by flows / schemas).
