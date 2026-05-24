# Flows

## When to load

Authoring `reg-flow` / `clear-flow`, or deciding whether a derived value should be a **flow** or a **subscription**. The prompt mentions a flow, `reg-flow`, a "materialised" / "computed" `app-db` value (`:total` from `:items`, `:area` from `:width × :height`, `:can-submit?` from validity + network state), `on-changes`, a value that must survive SSR hydration / time-travel, or a runtime-toggleable derivation (a wizard step, a feature gate).

> **Restraint first.** Flows are a focused convenience for a *small* number of cases. **When in doubt, use a subscription** — it is lighter, sub-cache-native, and pays no `app-db` write. A typical app has dozens of subs and one-to-a-handful of flows. Tens of flows is a smell that subs or machines are being misused.

## Mental-model anchor

re-frame2 flows are the v2 incarnation of **re-frame v1's `on-changes` interceptor** — same compute-on-input-change semantics. Two deliberate differences map cleanly:

| v1 `on-changes` | re-frame2 flow |
|---|---|
| Wired into a *specific event's* interceptor chain at registration time | Registered against a **frame** in the runtime; runs right after *every* event handler |
| Inputs are a positional list → output fn | `:inputs` is a vector of app-db paths → positional `:output` fn (same shape) |
| Cannot be toggled at runtime | Toggle via `:rf.fx/reg-flow` / `:rf.fx/clear-flow` from a handler |

If you can already picture it as `on-changes`, you have the model; the divergence is "registered + runtime-toggleable" instead of "baked into one event".

## Flow vs subscription — the decision

A flow's output lives in **`app-db` at a known `:path`**; a sub's value lives in the per-frame **sub-cache** and is consumed only by views. Reach for a flow only when **all** of these hold:

- The derived value is **part of the application's state**, not just a view-render input.
- Other event handlers, machine actions, or schemas need to read it as plain `app-db` data.
- It should **survive** SSR hydration, time-travel revert, or `app-db` serialisation (sub-cache contents do not survive the wire).
- The derivation is **stable enough to be worth registering** — not a one-off computed inside a single handler.

Use a **subscription** when the value is consumed only by views. Use a **state machine** when the value has discrete states / lifecycle. Compute **inline** when it is only relevant inside one handler. See [`../../decision-trees/slice-or-machine.md`](../../decision-trees/slice-or-machine.md) §Step 0 for the routing.

## Canonical signature

```clojure
(rf/reg-flow
  {:id     :rectangle/area              ;; unique; feature-prefixed (never :rf/*)
   :inputs [[:width] [:height]]         ;; vector of app-db paths
   :output (fn [w h] (* w h))           ;; pure: (in-1, in-2, ...) → output value
   :path   [:area]                      ;; where the output is written in app-db
   :doc    "Rectangle area from :width and :height."   ;; optional
   :schema [:int]})                     ;; optional Malli schema for the output

(rf/reg-flow flow {:frame :scratch})    ;; optional 2nd arg: explicit frame
(rf/clear-flow :rectangle/area)         ;; dissoc-in's the output :path
(rf/clear-flow :rectangle/area {:frame :scratch})
```

`:inputs` order matches the positional args to `:output`. `reg-flow` returns the flow's `:id` (family-wide reg-* convention). Flows ship in `day8/re-frame2-flows` — the consuming ns must `(:require [re-frame.flows])` to publish the artefact's late-bind hooks, or `rf/reg-flow` raises `:rf.error/flows-artefact-missing` (same require-to-register convention as schemas / machines / routing).

## Canonical mini-example

From `examples/reagent/flows/core.cljs` — a cart whose subtotal and total are materialised into `app-db` so the checkout handler reads `[:cart :total]` as plain data:

```clojure
(defn- line-total [{:keys [price qty]}] (* price qty))

(rf/reg-flow
  {:id     :cart/subtotal
   :inputs [[:cart :items]]
   :output (fn [items] (reduce + 0 (map line-total items)))
   :path   [:cart :subtotal]})

;; :cart/total reads ANOTHER flow's output ([:cart :subtotal]) plus the
;; runtime-toggleable discount rate. The runtime derives the dependency edge
;; from the path overlap and topologically sorts so :cart/subtotal always
;; runs first — both settle in one walk right after the handler.
(rf/reg-flow
  {:id     :cart/total
   :inputs [[:cart :subtotal] [:cart :discount-rate]]
   :output (fn [subtotal discount-rate]
             (Math/round (* subtotal (- 1 (or discount-rate 0)))))
   :path   [:cart :total]})

;; Reading a flow's output inside a handler — the central reason :total is a
;; flow and not a sub: a sub's value lives in the view-facing cache and is
;; awkward to read from a handler.
(rf/reg-event-fx :checkout/place-order
  (fn [{:keys [db]} _]
    (let [total (get-in db [:cart :total])]
      {:fx [[:cart/order-placed total]]})))
```

## Runtime toggle via fx

Two reserved fx-ids register / clear a flow mid-event. They route to the **dispatching frame** automatically — no `:frame` arg to set. This is how feature gates and wizard steps engage / disengage a derivation v1's `on-changes` could not:

```clojure
(rf/reg-event-fx :cart/apply-discount
  (fn [_ _]
    {:fx [[:rf.fx/reg-flow {:id     :cart/discount-rate
                            :inputs [[:cart :discount-engaged?]]
                            :output (fn [_] 0.10)
                            :path   [:cart :discount-rate]}]
          [:dispatch [:cart/touch true]]]}))   ;; nudge a re-walk (see Sequencing)

(rf/reg-event-fx :cart/remove-discount
  (fn [_ _]
    {:fx [[:rf.fx/clear-flow :cart/discount-rate]   ;; dissoc-in's [:cart :discount-rate]
          [:dispatch [:cart/touch false]]]}))
```

## How flows run (drain integration)

Flow evaluation happens **right after the event handler's interceptor chain — as the framework's outermost `:after` — transforming the pending `:db` effect before the single deferred install and before `:fx` walks**, once per event, over **this frame's** registered flows only:

1. The interceptor chain runs (`:before`s, handler, then `:after`s in reverse). The flow transform is the **outermost `:after`**, so it fires last — after every other `:after` (incl. a `(path :slice)` interceptor's `:after`) has reshaped the handler's slice back into the full `:db` effect.
2. The flow walk reads the chain's **pending `:db` effect** (not the live `app-db`) and walks the frame's flows in **topologically-sorted** order (dependency derived from `:path`/`:inputs` overlap). Each flow recomputes only if its input values changed by `=` since last run (the first walk of a newly-registered flow always fires), `assoc-in`-ing its output into the pending `:db` effect.
3. The single **deferred `:db` install** writes the flow-augmented value into `app-db`; sub-cache invalidates; `:rf.event/db-changed` fires here — after flows.
4. `:fx` walks — so an `:fx` entry that reads `app-db` sees flow outputs (e.g. `[:dispatch [:react-to-area-change]]` works cleanly).

## Common gotchas

- **Default to a sub.** Most derived values are subs. Reach for a flow only when the four conditions above all hold — flows pay an `app-db` write per recompute.
- **Flows publish ZERO framework subs.** A flow's `:path` IS the contract surface; consumers read it via any sub they register over the path (`(rf/reg-sub :cart/total (fn [db _] (get-in db [:cart :total])))`) or read it straight off `app-db` in a handler. There is no `:rf.flow/<id>` sub.
- **One-drain registration lag.** A flow registered via `:rf.fx/reg-flow` first runs on the *next* drain — its initial output appears one event after registration. Dispatch a synthetic nudge event if you need the value immediately (the cart example dispatches `:cart/touch`).
- **Cycles throw at registration.** If A depends on B and B on A, `reg-flow` throws `:rf.error/flow-cycle` with `:cycle` (an ordered vector with a closing repeat, e.g. `[:a :b :a]`).
- **`clear-flow` vacates the path.** It `dissoc-in`s the `:path` (no opt-out). Copy the value elsewhere first if you need to keep it.
- **`:output` must be pure and deterministic.** Same inputs → same output. A throw is a pre-install throw, so it **aborts the whole event** (atomicity contract): no `:db` install, `app-db` unchanged, no `:rf.event/db-changed`, `:fx` skipped — no partial commit (neither the handler's `:db` nor any prior flow's write lands). The throw surfaces as `:rf.error/flow-eval-exception`; every flow re-attempts on the next clean drain.
- **Feature-prefix the `:id` and `:path`.** Never `:rf/*` (reserved). The two fx-ids `:rf.fx/reg-flow` / `:rf.fx/clear-flow` are the framework's; your flow's id is yours.
- **Frame-scoped.** A flow belongs to one frame; the same id can register against two frames with different `:output`/`:path`. `clear-flow` and `destroy-frame!` teardown are frame-local.

## Deeper material

Per-frame topsort + cycle-detection contract, the atomicity failure semantics (a flow throw aborts the whole event — no partial commit), the `:rf.flow/*` trace taxonomy, `:sensitive?` inheritance, frame-destroy teardown, and the v1-alpha-flows migration table: `SKILL-REDIRECT.md` → **EP — Flows (013)**, **EP — Instrumentation (009)**. The managed-external-effects umbrella `:rf.flow/*` belongs to: [`spec/Managed-Effects.md`](../../../../spec/Managed-Effects.md).

---

*Derived from `spec/013-Flows.md`, `implementation/flows/src/re_frame/` (the `day8/re-frame2-flows` artefact), and the worked example `examples/reagent/flows/core.cljs` @ main `89bd9c3`. Re-verify line/shape after flow-runtime changes.*
