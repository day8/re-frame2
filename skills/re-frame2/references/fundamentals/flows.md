# Flows

## When to load

Authoring `reg-flow` / `(rf/clear :flow …)`, or deciding whether a derived value should be a **flow** or a **subscription**. The prompt mentions a flow, `reg-flow`, a "materialised" / "computed" `app-db` value (`:total` from `:items`, `:area` from `:width × :height`, `:can-submit?` from validity + network state), `on-changes`, a frame's `:flows` config, a value that must survive SSR hydration / time-travel, or a derivation that should run only sometimes (a wizard step, a feature gate).

> **Restraint first.** Flows are a focused convenience for a *small* number of cases. **When in doubt, use a subscription** — it is lighter, sub-cache-native, and pays no `app-db` write. A typical app has dozens of subs and one-to-a-handful of flows. Tens of flows is a smell that subs or machines are being misused.

## Mental-model anchor

re-frame2 flows carry the **same compute-on-input-change semantics as re-frame v1's `on-changes` interceptor**. If you know `on-changes`, two differences map cleanly:

| v1 `on-changes` | re-frame2 flow |
|---|---|
| Wired into a *specific event's* interceptor chain at registration time | Registered once at the top level; runs right after *every* event handler in each frame whose `:flows` names it |
| Inputs are a positional list → output fn | `:inputs` is a vector of frame-state paths → positional `:derive` fn (same shape) — a bare path reads app-db; a `[:rf.db/runtime …]` path reads runtime-db |
| Always on for its events | A conditional derivation reads its switch from app-db as one of its `:inputs` |

If you can already picture it as `on-changes`, you have the model; the divergence is "registered once, activated per frame" instead of "baked into one event".

## Flow vs subscription — the decision

A flow's output lives in **`app-db` at a known `:output-path`**; a sub's value lives in the per-frame **sub-cache** and is consumed only by views. Reach for a flow only when **all** of these hold:

- The derived value is **part of the application's state**, not just a view-render input.
- Other event handlers, machine actions, or schemas need to read it as plain `app-db` data.
- It should **survive** SSR hydration, time-travel revert, or `app-db` serialisation (sub-cache contents do not survive the wire).
- The derivation is **stable enough to be worth registering** — not a one-off computed inside a single handler.

Use a **subscription** when the value is consumed only by views. Use a **state machine** when the value has discrete states / lifecycle. Compute **inline** when it is only relevant inside one handler. See [`../../decision-trees/slice-or-machine.md`](../../decision-trees/slice-or-machine.md) §Step 0 for the routing.

> **Same function, different policy.** A flow and a subscription can wrap the *identical* whole-value function — `(fn [items discounts] (sum-cart items discounts))` works verbatim in either. You're choosing **not the computation but policy over it**: where the output lives (the sub's ephemeral cache vs the flow's durable `app-db` `:output-path`), when it runs (on-deref vs after-every-event), who owns it (sub-cache entry vs frame). That's why the decision feels subtle — both are *derivations*, named as one shape (`SKILL-REDIRECT.md` → *Derivations and processes (the algebra)*; an inspection view, not a new API), and why the four conditions are all *storage / durability / lifecycle* questions, never "is the math different?".

> **Author the whole-value `:derive` — no executable delta hook yet.** EP-0014 records an optional **delta law** (a derivation that *also* supplies an incremental step must commute with whole-value recomputation), but **slice-1 states the law and ships no executable delta protocol** ([`spec/Derivations.md` §The optional delta law](https://github.com/day8/re-frame2/blob/main/spec/Derivations.md) — semantic-only). For authoring this is a *restraint*: write only the whole-value `:derive`. **Do not invent a `:step-delta` (or any home-grown incremental-update hook)** — there's no protocol behind it so the framework won't run it, and a future executable delta is a framework mechanism (must satisfy the law), not an app extension point.

## Canonical signature

```clojure
(rf/reg-flow :rectangle/area            ;; flow-id first; feature-prefixed (never :rf/*)
  {:inputs [[:width] [:height]]         ;; REQUIRED: vector of frame-state paths (bare = app-db)
   :output-path [:area]                 ;; REQUIRED: where the output is written — always app-db
   :doc    "Rectangle area from :width and :height."}  ;; optional
  (fn [w h] (* w h)))                   ;; pure derive fn: (in-1, in-2, ...) → output value

(rf/make-frame {:id :scratch :flows [:rectangle/area]})
                                         ;; a frame runs the flows its :flows names
(rf/clear :flow :rectangle/area)         ;; each frame running it vacates :output-path on its next event
```

The 3-slot grammar is `(reg-flow flow-id metadata derive-fn)` — id first, the pure derive fn last, `metadata` carrying `:inputs` / `:output-path` (both required) plus optional `:doc` and the classification keys. A `:derive`, `:frame` or `:schema` left inside the metadata map is rejected loudly (`:rf.error/invalid-flow-metadata`); the output's shape is declared with `reg-app-schema` at `:output-path`. `:inputs` order matches the positional args to the derive fn. `reg-flow` returns the flow-id (family-wide reg-* convention). Flows ship in `day8/re-frame2-flows` — the consuming ns must `(:require [re-frame.flows :as flows])` to publish the artefact's late-bind hooks, or `rf/reg-flow` raises `:rf.error/flows-artefact-missing` (same require-to-register convention as schemas / machines / routing). The `reg-flow` **registration macro** stays on the `re-frame.core` façade (`rf/`), and so does clearing — as the one kind-keyed `(rf/clear :flow id)`, not a flows-specific name, and it takes no opts. There is no `flows/clear-flow` public var.

## Activation — a frame runs the flows it names

`reg-flow` is a **top-level registration** under the registrar's `:flow` kind: it reads no frame and needs no live frame, so register at namespace load like `reg-event`. A frame runs exactly the flows its `:flows` config names; a frame that names none runs none:

```clojure
[rf/frame-root {:id             :rf/default
                :flows          [:cart/subtotal :cart/total]
                :initial-events [[:cart/initialise]]}
 [cart-view]]
;; or (rf/make-frame {:id :rf/default :flows [:cart/subtotal :cart/total]})
```

- **Checked at construction.** An id the frame's view doesn't resolve raises `:rf.error/unregistered-flow`; a cycle or overlapping output paths among the frame's flows raise `:rf.error/flow-cycle` / `:rf.error/flow-path-overlap`. Both checks are per frame, so two flows may write one path when no frame runs both.
- **From the first event.** A frame evaluates every flow it names on its first event, so a seed in `:initial-events` already writes their outputs.
- **Hot reload needs nothing.** Saving the namespace re-runs the top-level `reg-flow`; each frame running it uses the edited definition from its next event. A moved `:output-path` vacates the old path on that event. No `^:dev/after-load` re-registration hook.
- **Changing a frame's set** is re-making the frame (`make-frame` with the same `:id`); an added flow runs from the next event, a dropped one has its output vacated. A mounted `frame-root` refuses a changed `:flows` (`:rf.error/frame-root-reconfigured`).
- **Introspection is the registrar.** `(rf/registrations {:source :store :kind :flow})`, `(rf/handler-meta {:frame f :kind :flow :id id})`, and `(:flows (rf/frame-meta f))` for the ids a frame runs. There is no flows-specific read API.

## Input partition — bare = app-db, `[:rf.db/runtime …]` = runtime-db

An `:inputs` path is read against the pending **frame-state**, which has two partitions (EP-0001). The syntax is **binary**:

```clojure
[:cart :items]                                    ;; bare path → app-db (the common case)
[:rf.db/runtime :rf.runtime/routing :current :route-id] ;; :rf.db/runtime-rooted → runtime-db
```

- A **bare** path (any leading element other than `:rf.db/runtime`) reads the pending **app-db** partition.
- A path whose **first element is `:rf.db/runtime`** reads the pending **runtime-db** partition (the partition key is stripped before the `get-in`). There is **no** `[:rf.db/app …]` explicit-app form — bare *is* app-db.

**Any** flow (user or framework) may read runtime-db this way, so a flow can derive a materialised value from route or machine state (`[:rf.db/runtime :rf.runtime/routing :current :route-id]`, `[:rf.db/runtime :rf.runtime/machines :snapshots :app/boot :state]`). But the **write side is reserved**: a flow's `:output-path` and its `:derive` always write **app-db only** — a flow never writes runtime-db. A runtime-only event (e.g. a pure route transition with no app-db change) still re-fires a flow that reads the changed runtime-db value, because the dirty-check keys on both partitions.

Never read a `[:rf.runtime/…]` *app-db* path — there is no such path; runtime state is not in app-db. The syntax is the `[:rf.db/runtime …]` partition-qualified input above.

## Canonical mini-example

From `examples/core/flows/core.cljs` — a cart whose subtotal and total are materialised into `app-db` so the checkout handler reads `[:cart :total]` as plain data:

```clojure
(defn- line-total [{:keys [price qty]}] (* price qty))

(rf/reg-flow :cart/subtotal
  {:inputs [[:cart :items]]
   :output-path [:cart :subtotal]}
  (fn [items] (reduce + 0 (map line-total items))))

;; :cart/total reads ANOTHER flow's output ([:cart :subtotal]) plus the
;; discount rate, a plain app-db value two events set and remove. The runtime
;; derives the dependency edge from the path overlap and topologically sorts
;; so :cart/subtotal always runs first — both settle in one walk right after
;; the handler.
(rf/reg-flow :cart/total
  {:inputs [[:cart :subtotal] [:cart :discount-rate]]
   :output-path [:cart :total]}
  (fn [subtotal discount-rate]
    (Math/round (* subtotal (- 1 (or discount-rate 0))))))

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
(rf/reg-flow :cart/show-checkout-banner?
  {:inputs [[:cart :items]
            [:rf.db/runtime :rf.runtime/routing :current :route-id]]
   :output-path [:cart :show-checkout-banner?]}
  (fn [items route-id]
    (and (seq items) (= route-id :route/cart))))
```

## A feature gate is data

A derivation that should run only sometimes (a feature gate, a wizard step) reads the switch as one of its `:inputs`; events set and clear the switch like any other state:

```clojure
(rf/reg-event :cart/apply-discount
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:cart :discount-rate] 0.10)}))   ;; :cart/total recomputes in this event

(rf/reg-event :cart/remove-discount
  (fn [{:keys [db]} _]
    {:db (update db :cart dissoc :discount-rate)}))     ;; and climbs back here
```

One click, one event, one commit: the switch and the output it feeds change together. Because the gate is app-db, it rewinds with everything else under an epoch restore and arrives with app-db over SSR hydration. There is no flow-registering effect; do not reach for one.

## How flows run (drain integration)

Flow evaluation happens **right after the event handler's interceptor chain — as the framework's outermost `:after` — transforming the pending `:db` effect before the single deferred install and before `:fx` walks**, once per event, over the flows **this frame's** `:flows` names:

1. The interceptor chain runs (`:before`s, handler, then `:after`s in reverse). The flow transform is the **outermost `:after`**, so it fires last — after every other `:after` (incl. a `[:rf.interceptor/path [:slice]]` ref's `:after`) has reshaped the handler's slice back into the full `:db` effect.
2. The flow walk reads the chain's **pending frame-state** — the pending `:db` effect for bare app-db inputs and the pending `:rf.db/runtime` effect for `[:rf.db/runtime …]` inputs (not the live partitions) — and walks the frame's flows in **topologically-sorted** order (dependency derived from `:output-path`/`:inputs` overlap; a runtime-db input never creates a spurious edge, since outputs are always app-db paths). Each flow recomputes only if its input values changed by `=` since last run (a frame's first walk of a flow, and its first walk after a re-registration, always fires), `assoc-in`-ing its output into the pending **`:db`** effect (outputs are app-db only).
3. The single **deferred `:db` install** writes the flow-augmented value into `app-db`; sub-cache invalidates; `:rf.event/db-changed` fires here — after flows.
4. `:fx` walks — so an `:fx` entry that reads `app-db` sees flow outputs (e.g. `[:dispatch [:react-to-area-change]]` works cleanly).

## Common gotchas

- **Default to a sub.** Most derived values are subs. Reach for a flow only when the four conditions above all hold — flows pay an `app-db` write per recompute.
- **Flows publish ZERO framework subs.** A flow's `:output-path` IS the contract surface; consumers read it via any sub they register over the path (`(rf/reg-sub :cart/total (fn [db _] (get-in db [:cart :total])))`) or read it straight off `app-db` in a handler. There is no `:rf.flow/<id>` sub.
- **Name it in `:flows`.** A registered flow that no frame names never runs: its output path stays absent. Add the id to the frame's `:flows` (on `frame-root` or `make-frame`).
- **Cycles throw at activation.** If A depends on B and B on A, making a frame whose `:flows` holds both throws `:rf.error/flow-cycle` with `:cycle` (an ordered vector with a closing repeat, e.g. `[:a :b :a]`).
- **Overlap uses the one shared path relation.** Dependency and conflict are both `rf.path/overlap?` (EP-0012): two paths overlap **exactly when either is a prefix of the other** (`[:cart]` and `[:cart :items 42]` overlap; the root `[]` overlaps *everything*; siblings `[:cart :items 42]` / `[:cart :items 43]` do not). Flow B depends on flow A when A's output overlaps one of B's inputs. A flow whose **output `:output-path` is `[]` is invalid** (it would claim the whole partition), and **two flows in one frame's `:flows` whose outputs overlap are refused when the frame is made** (`:rf.error/flow-path-overlap`, carrying both `:flow-ids` and `:paths`) — not a silent last-write-wins. Flows use this one shared relation, not a private one. See [`../cross-cutting/path-and-identity.md`](../cross-cutting/path-and-identity.md).
- **Clearing vacates the path.** After `(rf/clear :flow id)`, each frame that names the id `dissoc-in`s its `:output-path` on its next event (no opt-out) and warns `:rf.warning/unregistered-flow`. Copy the value elsewhere first if you need to keep it.
- **`:derive` must be pure and deterministic.** Same inputs → same output. The flow transform runs over the **pending frame-state** before install, so a throw is a pre-install throw and **aborts the whole pre-install cascade** (atomicity contract): nothing installs in *either* partition — the pending `:db` effect and the pending `:rf.db/runtime` effect are both discarded, `app-db` and runtime-db are left unchanged, no `:rf.event/db-changed`, `:fx` skipped — no partial commit (neither the handler's `:db`/`:rf.db/runtime` nor any prior flow's write lands). The throw surfaces as `:rf.error/flow-eval-exception`; every flow re-attempts on the next clean drain.
- **Feature-prefix the `:id` and `:output-path`.** Never `:rf/*` (reserved).
- **One definition, many frames.** A definition is shared by every frame that names it; each frame keeps only its own evaluation memo, which `destroy-frame!` drops. Two different derivations are two ids (or, with `:images`, two image compositions).

## Deeper material

Per-frame topsort + cycle-detection, partition-qualified input resolution (binary `[:rf.db/runtime …]`), atomicity failure semantics (a flow throw aborts the whole pre-install cascade across both partitions — no partial commit), the `:rf.flow/*` trace taxonomy, output-path classification (a flow classifies its **own** output via registration `:sensitive` / `:large` — **no** input → output inheritance, so classify the output path), frame-destroy teardown, and the v1-alpha-flows migration table: `SKILL-REDIRECT.md` → **EP — Flows (013)**, **EP — Instrumentation (009)**. The managed-external-effects umbrella `:rf.flow/*`: [`spec/Managed-Effects.md`](https://github.com/day8/re-frame2/blob/main/spec/Managed-Effects.md).

---

*Derived from `spec/013-Flows.md`, `implementation/flows/src/re_frame/` (the `day8/re-frame2-flows` artefact), and the worked example `examples/core/flows/core.cljs` @ main. Re-verify line/shape after flow-runtime changes.*
