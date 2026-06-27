# Flows

## When to load

Authoring `reg-flow` / `clear-flow`, or deciding whether a derived value should be a **flow** or a **subscription**. The prompt mentions a flow, `reg-flow`, a "materialised" / "computed" `app-db` value (`:total` from `:items`, `:area` from `:width × :height`, `:can-submit?` from validity + network state), `on-changes`, a value that must survive SSR hydration / time-travel, or a runtime-toggleable derivation (a wizard step, a feature gate).

> **Restraint first.** Flows are a focused convenience for a *small* number of cases. **When in doubt, use a subscription** — it is lighter, sub-cache-native, and pays no `app-db` write. A typical app has dozens of subs and one-to-a-handful of flows. Tens of flows is a smell that subs or machines are being misused.

## Mental-model anchor

re-frame2 flows are the v2 incarnation of **re-frame v1's `on-changes` interceptor** — same compute-on-input-change semantics. Two deliberate differences map cleanly:

| v1 `on-changes` | re-frame2 flow |
|---|---|
| Wired into a *specific event's* interceptor chain at registration time | Registered against a **frame** in the runtime; runs right after *every* event handler |
| Inputs are a positional list → output fn | `:inputs` is a vector of frame-state paths → positional `:derive` fn (same shape) — a bare path reads app-db; a `[:rf.db/runtime …]` path reads runtime-db |
| Cannot be toggled at runtime | Toggle via `:rf.fx/reg-flow` / `:rf.fx/clear-flow` from a handler |

If you can already picture it as `on-changes`, you have the model; the divergence is "registered + runtime-toggleable" instead of "baked into one event".

## Flow vs subscription — the decision

A flow's output lives in **`app-db` at a known `:output-path`**; a sub's value lives in the per-frame **sub-cache** and is consumed only by views. Reach for a flow only when **all** of these hold:

- The derived value is **part of the application's state**, not just a view-render input.
- Other event handlers, machine actions, or schemas need to read it as plain `app-db` data.
- It should **survive** SSR hydration, time-travel revert, or `app-db` serialisation (sub-cache contents do not survive the wire).
- The derivation is **stable enough to be worth registering** — not a one-off computed inside a single handler.

Use a **subscription** when the value is consumed only by views. Use a **state machine** when the value has discrete states / lifecycle. Compute **inline** when it is only relevant inside one handler. See [`../../decision-trees/slice-or-machine.md`](../../decision-trees/slice-or-machine.md) §Step 0 for the routing.

> **Same function, different policy.** A flow and a subscription can wrap the *identical* whole-value function — `(fn [items discounts] (sum-cart items discounts))` works verbatim in either. What you're choosing is **not the computation; it is policy over it**: where the output lives (the sub's ephemeral cache vs the flow's durable `app-db` `:output-path`), when it runs (on-demand-on-deref vs after-every-event), and who owns it (the sub-cache entry vs the frame). That is the whole reason the decision feels subtle — both are *derivations*, and re-frame2 names them as one shape (`SKILL-REDIRECT.md` → *Derivations and processes (the algebra)*). You don't author against that shape — it's an inspection/specification view, not a new API — but it's why the four conditions above are all *storage / durability / lifecycle* questions, never "is the math different?".

> **Author the whole-value `:derive` — no executable delta hook yet.** EP-0014 records an optional **delta law** (a derivation that *also* supplies an incremental step must commute with whole-value recomputation), but **slice-1 states the law and ships no executable delta protocol** — the delta representation is deliberately deferred until a real performance need arrives ([`spec/Derivations.md` §The optional delta law](../../../../spec/Derivations.md) — slice-1 is semantic-only). For flow authoring this is a *restraint*: write only the whole-value `:derive` function. **Do not invent a `:step-delta` (or any home-grown incremental-update hook) in app code** — there is no shipped protocol behind it, so the framework will not run it, and a future executable delta is a framework mechanism (it must satisfy the law), not an app extension point. Whole-value derivation *is* the contract; it stays correct whether or not a delta path ever lands.

## Canonical signature

```clojure
(rf/reg-flow
  {:id     :rectangle/area              ;; unique; feature-prefixed (never :rf/*)
   :inputs [[:width] [:height]]         ;; vector of frame-state paths (bare = app-db)
   :derive (fn [w h] (* w h))           ;; pure: (in-1, in-2, ...) → output value
   :output-path [:area]                 ;; where the output is written — always app-db
   :doc    "Rectangle area from :width and :height."   ;; optional
   :schema [:int]})                     ;; optional Malli schema for the output

(rf/reg-flow flow {:frame :scratch})       ;; optional 2nd arg: explicit frame
(flows/clear-flow :rectangle/area)         ;; dissoc-in's the output :output-path
(flows/clear-flow :rectangle/area {:frame :scratch})
```

`:inputs` order matches the positional args to `:derive`. `reg-flow` returns the flow's `:id` (family-wide reg-* convention). Flows ship in `day8/re-frame2-flows` — the consuming ns must `(:require [re-frame.flows :as flows])` to publish the artefact's late-bind hooks, or `rf/reg-flow` raises `:rf.error/flows-artefact-missing` (same require-to-register convention as schemas / machines / routing). The `reg-flow` **registration macro** stays on the `re-frame.core` façade (`rf/`); the `clear-flow` **lifecycle helper** lives on `re-frame.flows` — it is no longer re-exported from `re-frame.core` (front-porch shrink).

## Input partition — bare = app-db, `[:rf.db/runtime …]` = runtime-db

An `:inputs` path is read against the pending **frame-state**, which has two partitions (EP-0001). The syntax is **binary**:

```clojure
[:cart :items]                                    ;; bare path → app-db (the common case)
[:rf.db/runtime :rf.runtime/routing :current :route-id] ;; :rf.db/runtime-rooted → runtime-db
```

- A **bare** path (any leading element other than `:rf.db/runtime`) reads the pending **app-db** partition.
- A path whose **first element is `:rf.db/runtime`** reads the pending **runtime-db** partition (the partition key is stripped before the `get-in`). There is **no** `[:rf.db/app …]` explicit-app form — bare *is* app-db.

**Any** flow (user or framework) may read runtime-db this way, so a flow can derive a materialised value from route or machine state (`[:rf.db/runtime :rf.runtime/routing :current :route-id]`, `[:rf.db/runtime :rf.runtime/machines :snapshots :app/boot :state]`). But the **write side is reserved**: a flow's `:output-path` and its `:derive` always write **app-db only** — a flow never writes runtime-db. A runtime-only event (e.g. a pure route transition with no app-db change) still re-fires a flow that reads the changed runtime-db value, because the dirty-check keys on both partitions.

Never read a retired `[:rf.runtime/…]` *app-db* path — runtime state never lived in app-db under that scheme; the shipped syntax is the `[:rf.db/runtime …]` partition-qualified input above.

## Canonical mini-example

From `examples/core/flows/core.cljs` — a cart whose subtotal and total are materialised into `app-db` so the checkout handler reads `[:cart :total]` as plain data:

```clojure
(defn- line-total [{:keys [price qty]}] (* price qty))

(rf/reg-flow
  {:id     :cart/subtotal
   :inputs [[:cart :items]]
   :derive (fn [items] (reduce + 0 (map line-total items)))
   :output-path [:cart :subtotal]})

;; :cart/total reads ANOTHER flow's output ([:cart :subtotal]) plus the
;; runtime-toggleable discount rate. The runtime derives the dependency edge
;; from the path overlap and topologically sorts so :cart/subtotal always
;; runs first — both settle in one walk right after the handler.
(rf/reg-flow
  {:id     :cart/total
   :inputs [[:cart :subtotal] [:cart :discount-rate]]
   :derive (fn [subtotal discount-rate]
             (Math/round (* subtotal (- 1 (or discount-rate 0)))))
   :output-path [:cart :total]})

;; Reading a flow's output inside a handler — the central reason :total is a
;; flow and not a sub: a sub's value lives in the view-facing cache and is
;; awkward to read from a handler.
(rf/reg-event :checkout/place-order
  (fn [{:keys [db]} _]
    (let [total (get-in db [:cart :total])]
      {:fx [[:cart/order-placed total]]})))
```

A flow may read framework runtime-db state via a `[:rf.db/runtime …]` input and materialise it into app-db — e.g. a banner that depends on the active route id (runtime-db) plus a cart flag (app-db):

```clojure
;; Mixed inputs: one bare app-db path + one runtime-db-qualified path.
;; The runtime-db input reads the active route id at
;; [:rf.runtime/routing :current :route-id]; the output still writes app-db only.
(rf/reg-flow
  {:id     :cart/show-checkout-banner?
   :inputs [[:cart :items]
            [:rf.db/runtime :rf.runtime/routing :current :route-id]]
   :derive (fn [items route-id]
             (and (seq items) (= route-id :route/cart)))
   :output-path [:cart :show-checkout-banner?]})
```

## Runtime toggle via fx

Two reserved fx-ids register / clear a flow mid-event. They route to the **dispatching frame** automatically — no `:frame` arg to set. This is how feature gates and wizard steps engage / disengage a derivation v1's `on-changes` could not:

```clojure
(rf/reg-event :cart/apply-discount
  (fn [_ _]
    {:fx [[:rf.fx/reg-flow {:id          :cart/discount-rate
                            :inputs [[:cart :discount-engaged?]]
                            :derive (fn [_] 0.10)
                            :output-path [:cart :discount-rate]}]
          [:dispatch [:cart/touch true]]]}))   ;; nudge a re-walk (see Sequencing)

(rf/reg-event :cart/remove-discount
  (fn [_ _]
    {:fx [[:rf.fx/clear-flow :cart/discount-rate]   ;; dissoc-in's [:cart :discount-rate]
          [:dispatch [:cart/touch false]]]}))
```

## How flows run (drain integration)

Flow evaluation happens **right after the event handler's interceptor chain — as the framework's outermost `:after` — transforming the pending `:db` effect before the single deferred install and before `:fx` walks**, once per event, over **this frame's** registered flows only:

1. The interceptor chain runs (`:before`s, handler, then `:after`s in reverse). The flow transform is the **outermost `:after`**, so it fires last — after every other `:after` (incl. a `(path :slice)` interceptor's `:after`) has reshaped the handler's slice back into the full `:db` effect.
2. The flow walk reads the chain's **pending frame-state** — the pending `:db` effect for bare app-db inputs and the pending `:rf.db/runtime` effect for `[:rf.db/runtime …]` inputs (not the live partitions) — and walks the frame's flows in **topologically-sorted** order (dependency derived from `:output-path`/`:inputs` overlap; a runtime-db input never creates a spurious edge, since outputs are always app-db paths). Each flow recomputes only if its input values changed by `=` since last run (the first walk of a newly-registered flow always fires), `assoc-in`-ing its output into the pending **`:db`** effect (outputs are app-db only).
3. The single **deferred `:db` install** writes the flow-augmented value into `app-db`; sub-cache invalidates; `:rf.event/db-changed` fires here — after flows.
4. `:fx` walks — so an `:fx` entry that reads `app-db` sees flow outputs (e.g. `[:dispatch [:react-to-area-change]]` works cleanly).

## Common gotchas

- **Default to a sub.** Most derived values are subs. Reach for a flow only when the four conditions above all hold — flows pay an `app-db` write per recompute.
- **Flows publish ZERO framework subs.** A flow's `:output-path` IS the contract surface; consumers read it via any sub they register over the path (`(rf/reg-sub :cart/total (fn [db _] (get-in db [:cart :total])))`) or read it straight off `app-db` in a handler. There is no `:rf.flow/<id>` sub.
- **One-drain registration lag.** A flow registered via `:rf.fx/reg-flow` first runs on the *next* drain — its initial output appears one event after registration. Dispatch a synthetic nudge event if you need the value immediately (the cart example dispatches `:cart/touch`).
- **Cycles throw at registration.** If A depends on B and B on A, `reg-flow` throws `:rf.error/flow-cycle` with `:cycle` (an ordered vector with a closing repeat, e.g. `[:a :b :a]`).
- **Overlap uses the one shared path relation.** Dependency and conflict are both `rf.path/overlap?` (EP-0012): two paths overlap **exactly when either is a prefix of the other** (`[:cart]` and `[:cart :items 42]` overlap; the root `[]` overlaps *everything*; siblings `[:cart :items 42]` / `[:cart :items 43]` do not). Flow B depends on flow A when A's output overlaps one of B's inputs. A flow whose **output `:output-path` is `[]` is invalid** (it would claim the whole partition), and **two flows in one frame whose outputs overlap are a registration error** (`:rf.error/flow-path-overlap`, carrying both `:flow-ids` and `:paths`) — not a silent last-write-wins. Flows use this one shared relation, not a private one. See [`../cross-cutting/path-and-identity.md`](../cross-cutting/path-and-identity.md).
- **`clear-flow` vacates the path.** It `dissoc-in`s the `:output-path` (no opt-out). Copy the value elsewhere first if you need to keep it.
- **`:derive` must be pure and deterministic.** Same inputs → same output. The flow transform runs over the **pending frame-state** before install, so a throw is a pre-install throw and **aborts the whole pre-install cascade** (atomicity contract): nothing installs in *either* partition — the pending `:db` effect and the pending `:rf.db/runtime` effect are both discarded, `app-db` and runtime-db are left unchanged, no `:rf.event/db-changed`, `:fx` skipped — no partial commit (neither the handler's `:db`/`:rf.db/runtime` nor any prior flow's write lands). The throw surfaces as `:rf.error/flow-eval-exception`; every flow re-attempts on the next clean drain.
- **Feature-prefix the `:id` and `:output-path`.** Never `:rf/*` (reserved). The two fx-ids `:rf.fx/reg-flow` / `:rf.fx/clear-flow` are the framework's; your flow's id is yours.
- **Frame-scoped.** A flow belongs to one frame; the same id can register against two frames with different `:derive`/`:output-path`. `clear-flow` and `destroy-frame!` teardown are frame-local.

## Deeper material

Per-frame topsort + cycle-detection contract, the partition-qualified input resolution (binary `[:rf.db/runtime …]` syntax), the atomicity failure semantics (a flow throw aborts the whole pre-install cascade across both partitions — no partial commit), the `:rf.flow/*` trace taxonomy, output-path classification (a flow classifies its **own** output via registration `:sensitive` / `:large` — there is **no** input → output inheritance; a flow reading a sensitive input does not auto-classify its output, so classify the output path), frame-destroy teardown, and the v1-alpha-flows migration table: `SKILL-REDIRECT.md` → **EP — Flows (013)**, **EP — Instrumentation (009)**. The managed-external-effects umbrella `:rf.flow/*` belongs to: [`spec/Managed-Effects.md`](../../../../spec/Managed-Effects.md).

---

*Derived from `spec/013-Flows.md`, `implementation/flows/src/re_frame/` (the `day8/re-frame2-flows` artefact), and the worked example `examples/core/flows/core.cljs` @ main `89bd9c3`. Re-verify line/shape after flow-runtime changes.*
