# 05 — Flows

A flow is a piece of derived state. You declare its inputs (a vector of frame-state **paths** — not subs), its computation (a pure fn over the values at those paths), and the path in `app-db` it should write its output to. Inputs read either partition of the frame: a **bare** path reads `app-db` (the common case), and a path led by `:rf.db/runtime` reads `runtime-db` (route / machine state). Outputs are always written to `app-db`. The runtime watches the input paths, recomputes the output when any of their values change, and writes the result. It's a sub-with-a-side-effect-on-`app-db` — useful exactly when you want derived state that other code reads via plain `get-in` (or a plain sub over the output path), without having to remember to recompute it manually.

Flows replace v1's `on-changes` interceptor — same compute-on-input-change semantics, registered in the runtime rather than wired into individual events. They also replace much of what `enrich` did. The point: derived state is now a *registered, named, observable, restorable* thing, not a closure hidden behind an interceptor.

See the [Flows concept guide](../concepts/flows.md) for the conceptual companion to this reference.

This chapter spans `re-frame.core` (the `reg-flow` macro) and `re-frame.flows` (`clear-flow`):

```clojure
(:require [re-frame.core :as rf]
          [re-frame.flows :as flows])
```

## The flow surface

### `reg-flow`

- **Kind**: function
- **Signature**:
  ```clojure
  (reg-flow flow)
  (reg-flow flow opts)
  ```
- **Description**: Register a flow. The flow map carries `:id`, `:inputs`, `:derive`, `:output-path`. `opts` is a map (currently `{:frame frame-id}`) selecting the owning frame. Returns the flow's `:id` (per the `reg-*` return-value convention).

### `clear-flow`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-flow id)
  (clear-flow id opts)
  ```
- **Description**: Deregister the flow from the named frame and `dissoc-in` its `:output-path` from that frame's `app-db` only. Sibling frames' state is preserved.
- **Example**:
  ```clojure
  ;; Deregister :cart/subtotal; clears [:cart :subtotal] in this frame's app-db.
  (flows/clear-flow :cart/subtotal)
  ```

### A minimal flow

```clojure
(rf/reg-flow
  {:id     :cart/subtotal
   :inputs [[:cart :items] [:tax :rate]]      ;; vector of frame-state paths
   :derive (fn [items rate]                    ;; values arrive positionally
             (let [subtotal (reduce + (map :price items))]
               (* subtotal (+ 1 rate))))
   :output-path [:cart :subtotal]})

;; Now [:cart :subtotal] in app-db is always derived. Read it via a plain
;; sub or a plain handler. Adding an item triggers recompute; updating the
;; rate triggers recompute; nothing else writes [:cart :subtotal].
```

A flow can also derive from `runtime-db` (route / machine state) by leading an
input path with `:rf.db/runtime` — the partition key is stripped before the
read. Outputs stay `app-db`-only.

```clojure
(rf/reg-flow
  {:id     :nav/on-checkout?
   :inputs [[:rf.db/runtime :rf.runtime/routing :current :route-id]] ;; runtime-db input
   :derive (fn [route-id] (= route-id :checkout))
   :output-path [:nav :on-checkout?]})                               ;; app-db output
```

`:inputs` is a **positional vector** of frame-state paths; the values at those paths arrive as positional args to `:derive` in the same order (a map-keyed `:inputs` form is a deferred design option, not v1). Each path reads against the pending frame-state's two partitions — a **bare** path reads `app-db`, and a path led by `:rf.db/runtime` reads `runtime-db`. The shape is data — no fn registration with a separate id, no interceptor wiring, no closure capture. The conformance harness validates flows by walking the registered data and applying it to a synthesised frame-state.

### The flow map

| Key | Required | Notes |
|---|---|---|
| `:id` | yes | The registry key. Use a namespaced keyword. |
| `:inputs` | yes | A vector of frame-state path vectors. Read positionally; the value at each path is passed to `:derive` in order. A bare path reads `app-db`; a path led by `:rf.db/runtime` reads `runtime-db`. |
| `:derive` | yes | `(fn [in-1 in-2 ...] new-output)`. Pure; receives the input values positionally; called every time inputs change; must be deterministic. |
| `:output-path` | yes | Where to write the output in `app-db`. |
| `:doc` | no | One-sentence what-and-why; surfaces in tooling. |
| `:schema` | no | Malli schema for the **output value**. Validated on every recompute in dev (and elided in production, like all schema validation). On failure the runtime emits `:rf.error/schema-validation-failure :where :flow-output` — **observational**: the output is still written (a flow output is materialised state downstream already reads), the trace surfaces the producer bug. Routes through the registered validator, so an app without the schemas artefact pays nothing. |

### Frame-scoping

The `:flow` registrar slot is **last-registration-wins across frames** — registering the same id against multiple frames shares one registrar slot keyed by `flow-id` only. For full per-frame discovery read the encapsulated runtime registry via the public snapshot accessor `re-frame.flows/flows-snapshot` (it returns the `{frame-id {flow-id flow-map}}` shape) — **not** the private `@re-frame.flows/flows` atom, which is an implementation detail behind the snapshot contract. The per-frame runtime registry is the source of truth for evaluation.

### Frame-destroy teardown

`destroy-frame!` releases every per-frame piece of flow state — registry slot, `last-inputs` rows, registrar entries for ids the destroyed frame was last owner of. Sibling frames' state is preserved.

## Runtime registration via `:fx`

Sometimes you want to register or clear a flow from inside an event handler — feature-flag gates, demand-driven registration, the SSR per-request frame setting up flows for the request and tearing them down on response. Two reserved fx-ids cover that:

| `[fx-id args]` | Args | Status | Intuition |
|---|---|---|---|
| `[:rf.fx/reg-flow flow-map]` | a flow map (same shape as `reg-flow`) | v1 | Register a flow at runtime via `:fx`. |
| `[:rf.fx/clear-flow id]` | flow id | v1 | Clear a registered flow at runtime via `:fx`. |

The signature mirrors `reg-flow` / `clear-flow` exactly — same opts, same return semantics. Use whichever surface matches your call site.

```clojure
;; Clear a runtime-registered flow from inside a handler — e.g. disengaging a
;; feature gate. The flow's :output-path is dissoc'd from this frame's app-db.
(rf/reg-event :cart/remove-discount
  (fn [_ _]
    {:fx [[:rf.fx/clear-flow :cart/discount-rate]]}))
```

### The one-event lag — the least-obvious thing about flows

> A flow registered with `:rf.fx/reg-flow` **does not compute its initial output during that event.** It first fires on the **next** drain on the same frame.

This is the single least-obvious flow behaviour, so it is worth internalising before you reach for `:rf.fx/reg-flow`. The reason is structural: the `:fx` walk is the *last* drain stage, and it runs **after** the flow transform has already evaluated this event's flows. The newly-registered flow simply was not in the registry when the transform walked, so it has nothing to compute on *this* event. It computes on the next drain on that frame.

In the common case the lag is invisible — you register a flow in an `:enter`-style handler and the user's next interaction materialises the output. When you genuinely need the initial value *now*, dispatch a follow-up no-op event from the same handler to re-trigger the drain (the flow is in the registry by the time that dispatched event drains):

```clojure
(rf/reg-event :wizard/enter-step-2
  (fn [_ _]
    ;; Step 2 wants a running order total derived from the quantity and
    ;; unit price the user entered back on step 1.
    {:fx [[:rf.fx/reg-flow {:id          :wizard/order-total
                            :inputs      [[:wizard :qty] [:wizard :unit-price]]
                            :derive      (fn [qty unit-price] (* qty unit-price))
                            :output-path [:wizard :order-total]}]
          [:dispatch [:wizard/settle]]]}))   ;; flow computes on THIS drain

(rf/reg-event :wizard/settle (fn [{:keys [db]} _] {:db db}))   ;; no-op; exists only to drain
```

This is an explicit step — not a hidden one — and most apps never need it. There is a one-event lag before a flow recomputes, which preserves the one-install-per-event invariant.

## Failure semantics

**Production-survivable.** A throw inside a flow's `:derive` fn surfaces as `:rf.error/flow-eval-exception` on the **always-on error-emit substrate** — registered `register-error-listener!` callbacks fire under CLJS `:advanced` + `goog.DEBUG=false`. The error is *not* trace-only; production deployments catch it.

See [Report errors in production](../how-to/report-errors-in-production.md) for wiring the always-on error listeners that catch these in a deployed app.

## Flow vs sub: when to reach for which

- **Sub** when the value is computed *on read* and never written back to `app-db`. Cached; ref-counted; lazy.
- **Flow** when the value should *live in `app-db`* — because other code reads it via path, other flows depend on it via path, or you want it captured by the epoch snapshot for time-travel.

A useful rule: if you find yourself writing `(reg-sub ::derived-total (fn [...]))` and immediately needing to read its value from a plain handler via `subscribe-once`, that's a flow. If you find yourself writing a flow that no path-shaped consumer ever reads (only sub-shaped ones do), it should probably be a sub.

## See also

- [01 — Core](01-core.md) — `reg-flow` and `clear-flow` rowed in registration / clearing.
- [03 — Effects and interceptors](03-effects.md) — `[:rf.fx/reg-flow ...]` rowed in `:fx` entries.
- [Flows: derived values your handlers can read](../concepts/flows.md) — the conceptual companion to this reference.
- [Migration reference](../../../migration/from-re-frame-v1/README.md) — `on-changes` (replaced by flows) and `enrich` (replaced by flows / schemas).
