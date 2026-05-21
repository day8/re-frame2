# 05 — Flows

A flow is a piece of derived state. You declare its inputs (other paths or subs), its computation (a pure fn over those inputs), and the path in `app-db` it should write its output to. The runtime watches the inputs, recomputes the output when any of them change, and writes the result. It's a sub-with-a-side-effect-on-`app-db` — useful exactly when you want derived state that other code reads via plain `get-in` (or a plain sub), without having to remember to recompute it manually.

Flows replace v1's `on-changes` interceptor and a chunk of what `reg-sub-raw` was used for. They also replace much of what `enrich` did. The point: derived state is now a *registered, named, observable, restorable* thing, not a closure hidden behind an interceptor.

The normative source is [013-Flows.md](../../spec/013-Flows.md).

## The flow surface

| API | M/Fn | Signature | Status | Intuition |
|---|---|---|---|---|
| `reg-flow` | Fn | `(reg-flow flow)` <br> `(reg-flow flow opts)` | v1 | Register a flow. The flow map carries `:id`, `:inputs`, `:output`, `:path`. `opts` is a map (currently `{:frame frame-id}`) selecting the owning frame. Returns the flow's `:id` (per the `reg-*` return-value convention). |
| `clear-flow` | Fn | `(clear-flow id)` <br> `(clear-flow id opts)` | v1 | Deregister the flow from the named frame and `dissoc-in` its `:path` from that frame's `app-db` only. Sibling frames' state is preserved. |

### A minimal flow

```clojure
(rf/reg-flow
  {:id     :cart/total
   :inputs {:items [:cart :items]
            :rate  [:tax :rate]}
   :output (fn [{:keys [items rate]}]
             (let [subtotal (reduce + (map :price items))]
               {:subtotal subtotal
                :tax      (* subtotal rate)
                :total    (* subtotal (+ 1 rate))}))
   :path   [:cart :total]})

;; Now [:cart :total] in app-db is always derived. Read it via a plain sub
;; or with subscribe-once. Adding an item triggers recompute; updating the
;; rate triggers recompute; nothing else writes [:cart :total].
```

The shape is data — no fn registration with a separate id, no interceptor wiring, no closure capture. The conformance harness validates flows by walking the registered data and applying it to a synthesised `app-db`.

### The flow map

| Key | Required | Notes |
|---|---|---|
| `:id` | yes | The registry key. Use a namespaced keyword. |
| `:inputs` | yes | A map of `{slot-name path-or-sub-vector}`. Path vectors read from `app-db`; sub-vectors subscribe through the sub graph. |
| `:output` | yes | `(fn [inputs-map] new-output)`. Pure; called every time inputs change; must be deterministic. |
| `:path` | yes | Where to write the output in `app-db`. |
| `:live?` | no | Predicate fn — when present, the flow only fires while it returns true. Used to short-circuit expensive flows when their consumer isn't on-screen. |

### Frame-scoping

The `:flow` registrar slot is **last-registration-wins across frames** — registering the same id against multiple frames shares one registrar slot keyed by `flow-id` only. For full per-frame discovery use `@re-frame.flows/flows` directly; the per-frame runtime registry is the source of truth for evaluation. See [013 §Frame-scoping](../../spec/013-Flows.md#frame-scoping).

### Frame-destroy teardown

`destroy-frame!` releases every per-frame piece of flow state — registry slot, `last-inputs` rows, registrar entries for ids the destroyed frame was last owner of. Sibling frames' state is preserved. See [013 §Frame-destroy teardown](../../spec/013-Flows.md#frame-destroy-teardown).

## Runtime registration via `:fx`

Sometimes you want to register or clear a flow from inside an event handler — feature-flag gates, demand-driven registration, the SSR per-request frame setting up flows for the request and tearing them down on response. Two reserved fx-ids cover that:

| `[fx-id args]` | Args | Status | Intuition |
|---|---|---|---|
| `[:rf.fx/reg-flow flow-map]` | a flow map (same shape as `reg-flow`) | v1 | Register a flow at runtime via `:fx`. |
| `[:rf.fx/clear-flow id]` | flow id | v1 | Clear a registered flow at runtime via `:fx`. |

The signature mirrors `reg-flow` / `clear-flow` exactly — same opts, same return semantics. Use whichever surface matches your call site.

## Failure semantics

**Production-survivable.** A throw inside a flow's `:output` fn surfaces as `:rf.error/flow-eval-exception` on the **always-on error-emit substrate** — registered `:on-error` policy fns and `register-error-listener!` callbacks fire under CLJS `:advanced` + `goog.DEBUG=false`. The error is *not* trace-only; production deployments catch it.

See [Spec 013 §Failure semantics](../../spec/013-Flows.md#failure-semantics) rule 4 and [Spec 009 §Production builds](../../spec/009-Instrumentation.md#production-builds-zero-overhead-zero-code).

## Flow vs sub: when to reach for which

- **Sub** when the value is computed *on read* and never written back to `app-db`. Cached; ref-counted; lazy.
- **Flow** when the value should *live in `app-db`* — because other code reads it via path, other flows depend on it via path, or you want it captured by the epoch snapshot for time-travel.

A useful rule: if you find yourself writing `(reg-sub ::derived-total (fn [...]))` and immediately needing to read its value from a plain handler via `subscribe-once`, that's a flow. If you find yourself writing a flow that no path-shaped consumer ever reads (only sub-shaped ones do), it should probably be a sub.

## See also

- [01 — Core](01-core.md) — `reg-flow` and `clear-flow` rowed in registration / clearing.
- [03 — Effects and interceptors](03-effects.md) — `[:rf.fx/reg-flow ...]` rowed in `:fx` entries.
- [Spec 013 — Flows](../../spec/013-Flows.md) — the normative source.
- [16 — Removed](16-removed.md) — `on-changes` (replaced by flows) and `enrich` (replaced by flows / schemas).
