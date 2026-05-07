# Spec 013 — Flows

> Status: Drafting. **v1-required.** Builds on the registration grammar in [001-Registration](001-Registration.md), the drain in [002-Frames §Run-to-completion](002-Frames.md#run-to-completion-dispatch-drain-semantics), and the reserved-namespace policy in [Conventions](Conventions.md).
>
> **The minimum claim:** flows are *registered, runtime-toggleable computed-state declarations that materialise their output into `app-db`*. They are the v2 incarnation of v1's `on-changes` interceptor — same compute-on-input-change semantics — but registered in the runtime (not on individual events) and toggleable via two reserved fx-ids.
>
> **Restraint.** Flows are a **convenience** for a small number of small use-cases. They are not a replacement for subscriptions, not a new dataflow paradigm, not a substitute for state machines, and not the default for derived state. The architecture's main load-bearing pieces are events, subs, machines, and effects; flows sit alongside them as a focused tool for a narrow set of problems. **When in doubt, use a subscription** — flows pay an `app-db` write per recomputation and add a small piece of registered runtime; that cost is only worthwhile when the [reasons in §When (and when not) to use a flow](#when-and-when-not-to-use-a-flow) below apply.

## Abstract

A **flow** is a registered rule that says: "when these `app-db` paths change, run this pure function and write the result to that `app-db` path." Flows are evaluated automatically after every event drain, in topological order over their static input/output dependency graph.

Flows differ from subscriptions in *where the value lives*. A sub's value lives in the per-frame sub-cache and is consumed by views. A flow's value lives in `app-db` at a known path, where it survives SSR / hydration / time-travel revert, is visible in the app-db inspector, can be read by downstream event handlers and other flows, and is covered by registered schemas. When the derived value is part of the application's *state* (as opposed to part of a view's render input), use a flow.

## When (and when not) to use a flow

Flows are the right tool when **all** of the following apply:

- The derived value is **part of the application's state**, not just a view-render input.
- Other event handlers, machine actions, or schemas need to read the value as plain `app-db` data.
- The value should **survive** SSR hydration, time-travel revert, or app-db serialisation.
- The derivation is **stable enough to be worth registering** — it isn't a one-off computation inside a single handler.

Flows are the **wrong** tool when:

- The derived value is consumed only by views → use a **subscription** (lighter, sub-cache native, no `app-db` write).
- The derivation has discrete states or lifecycle (entry/exit, transitions) → use a **state machine** (per [005](005-StateMachines.md)).
- The value is only relevant inside one event handler → just compute it inline; no registration needed.
- "I want a reactive value somewhere" → almost always a sub.

The expected v1 deployment volume is **small** — a typical app has dozens of subscriptions and one to perhaps a handful of flows. If a codebase grows tens of flows, that is a smell that subscriptions or machines are being misused.

## Why flows

Three use cases the reference implementation has hit repeatedly:

- **Materialised computed state.** `:area` from `:width × :height`. `:total` from `:items`. `:can-submit?` from form validity, network state, and feature flags. The derived value is part of the app's state and downstream code reads it as plain `app-db`.
- **State that survives the wire.** SSR hydration carries `app-db`; computed values written by flows arrive on the client without re-computation. Sub-cache contents do not survive hydration.
- **Toggleable derivation.** A wizard step, a feature gate, an "advanced mode" — a derivation that should only run while a feature is engaged. v1's `on-changes` interceptor cannot do this because interceptors are wired into specific events at registration time. Flows are runtime-registered and runtime-clearable.

## The registration shape

```clojure
(rf/reg-flow
  {:id     :rectangle/area
   :inputs [[:width] [:height]]            ;; vector of app-db paths
   :output (fn [w h] (* w h))               ;; pure: (in-1, in-2, ...) → output
   :path   [:area]                          ;; where the result is written
   :doc    "Rectangle area computed from :width and :height."})
```

Required keys:

| Key | Meaning |
|---|---|
| `:id` | Unique flow identifier. Per [Conventions §Feature-modularity prefix convention](Conventions.md#feature-modularity-prefix-convention), namespace by feature. |
| `:inputs` | Vector of app-db paths. The order matches positional args to `:output`. |
| `:output` | Pure function. Receives input values positionally. Must be deterministic (same inputs → same output). |
| `:path` | App-db path to write the output to. |

Optional keys (per the [001-Registration §Registration grammar](001-Registration.md#registration-grammar) standard):

| Key | Meaning |
|---|---|
| `:doc` | One-sentence what-and-why; surfaces in tooling. |
| `:spec` | Malli schema for the output value (dynamic-host validation in dev). |
| `:ns`, `:line`, `:file` | Source coordinates (auto-captured by the registration macro per [001 §Source-coordinate capture](001-Registration.md#source-coordinate-capture-cljs-reference)). |

`:inputs` is a positional vector matching `on-changes`. The vector form is short for the common 2–4-input case and the destructure-by-position is straightforward. (A map-keyed alternative was considered — see [§Open questions](#open-questions).)

## Drain integration

Flow evaluation happens **after `:db` commits and before `:fx` walks** on every event drain. Per [002 §Drain-loop pseudocode](002-Frames.md#drain-loop-pseudocode), the per-event drain inserts one step:

```
process-event! (revised, with flows):
  1. Resolve handler.
  2. Run interceptor chain.
  3. Apply :db. Sub-cache invalidates.
  4. Walk registered flows in topologically-sorted order.    ← NEW
       For each flow:
         new-inputs ← read input paths from new app-db
         if new-inputs ≠ last-inputs[flow-id]:
           new-output ← (apply :output new-inputs)
           app-db ← (assoc-in app-db (:path flow) new-output)
           last-inputs[flow-id] ← new-inputs
       Sub-cache invalidates again if any flow wrote.
  5. Walk :fx in source order.
```

Three properties this gives:

1. **`:fx` entries see flow outputs.** An `:fx` entry that reads `app-db` after the handler returns sees flow-computed values. This is what makes `[:dispatch [:react-to-area-change]]` work cleanly.
2. **Single pass per event.** Each flow runs at most once per drain. The topological order ensures multi-layer flows settle in one walk.
3. **Run-to-completion is preserved.** Views never observe an intermediate state where some flows have updated and others haven't.

## Topological sort and cycle detection

Flows form a static dependency graph derivable from their `:path` and `:inputs` declarations.

**Dependency rule.** Flow B depends on flow A iff A's `:path` and any of B's `:inputs` share a path prefix in either direction:

- Exact match: `A.path = [:foo]`, `B.inputs = [[:foo]]` — B reads exactly what A writes.
- A's path is a prefix of B's input: `A.path = [:foo]`, `B.inputs = [[:foo :bar]]` — B reads inside A's value.
- B's input is a prefix of A's path: `A.path = [:foo :bar]`, `B.inputs = [[:foo]]` — A's write is part of B's input map.

The runtime topologically sorts the registry by this dependency relation. The sort is **memoised** and invalidated only when a flow is registered, cleared, or re-registered (rare relative to event volume).

**Cycle detection.** If A depends on B and B depends on A (any indirection), `reg-flow` throws `:rf.error/flow-cycle` at registration time with the cycle path in the error's `:tags`. The error fires before any snapshot is created — caught at registration, not at runtime.

```clojure
;; This will throw at registration:
(rf/reg-flow {:id :a :inputs [[:b]] :output identity :path [:a]})
(rf/reg-flow {:id :b :inputs [[:a]] :output identity :path [:b]})
;; → :rf.error/flow-cycle {:cycle [:a :b :a]}
```

Cycles can also form *during* flow registration if the new flow completes a cycle that was incomplete before it was registered. The detection runs every `reg-flow` call.

## Dirty-check semantics

A flow recomputes only when its inputs change by **`=`-equality** since its last evaluation:

```
new-inputs ← (mapv #(get-in app-db %) (:inputs flow))
if new-inputs ≠ last-inputs[flow-id]:
  recompute and write
```

Three implications:

1. **No-op `app-db` writes don't trigger.** A handler that writes the same value back to `:width` does not re-fire flows that depend on `:width`.
2. **Path-overlap is sufficient, not necessary, for re-firing.** A flow whose inputs sit at `[:user :profile :name]` does not re-fire when an unrelated path like `[:cart :items]` changes. The dirty-check is per-flow, not per-app-db-change.
3. **First evaluation always fires.** A newly-registered flow's `last-inputs` is uninitialised; its first walk recomputes unconditionally and produces the initial output value.

## Dynamic toggle via fx

Two reserved fx-ids let event handlers register and clear flows during normal event processing:

| Fx-id | Args | Effect |
|---|---|---|
| `:rf.fx/reg-flow` | A flow map (same shape as `reg-flow`'s argument) | Register the flow. Topsort cache invalidated. |
| `:rf.fx/clear-flow` | A flow id | Clear the flow. `dissoc-in` on its `:path`. Topsort cache invalidated. |

```clojure
(rf/reg-event-fx :wizard/enter-step-2
  (fn [_ _]
    {:fx [[:rf.fx/reg-flow {:id     :step-2/computed
                             :inputs [[:step-2 :foo] [:step-2 :bar]]
                             :output (fn [foo bar] (compute foo bar))
                             :path   [:step-2 :result]}]]}))

(rf/reg-event-fx :wizard/leave-step-2
  (fn [_ _]
    {:fx [[:rf.fx/clear-flow :step-2/computed]]}))
```

**Sequencing.** `:rf.fx/reg-flow` and `:rf.fx/clear-flow` run during the standard `:fx` walk (per [002 §`:fx` ordering and atomicity guarantees](002-Frames.md#fx-ordering-and-atomicity-guarantees)) — *after* the flow after-interceptor has already evaluated for the current event. A flow registered mid-event therefore first runs on the *next* event drain. Its initial output appears one event after registration. Apps that need the value immediately can dispatch a synthetic re-walk event after registration.

**`clear-flow` cleanup.** Default behaviour is `dissoc-in` on the flow's `:path` — the slot is vacated when the flow goes away. Stale derived values left behind would confuse downstream consumers. Apps that want to preserve the value should copy it elsewhere before clearing.

## Re-registration

`reg-flow` with an already-registered `:id` performs a **surgical update** — same semantics as every other `reg-*` per [001-Registration §Hot-reload semantics](001-Registration.md#hot-reload-semantics). The new flow's definition replaces the old; `last-inputs` is reset (the new flow re-evaluates on the next event regardless of input change); the topsort cache invalidates. In-flight events finish against the resolved handler at the time they entered the drain.

## What flows are NOT

Three near-neighbours flows are *not*:

| Concept | Difference |
|---|---|
| **Subscription** ([006](006-ReactiveSubstrate.md)) | Subs live in the sub-cache; consumed by views. Flows live in `app-db`; consumed by everything (handlers, other flows, schemas, SSR payload). When the value is part of the application's *state*, use a flow; when it's part of view rendering only, use a sub. |
| **State machine** ([005](005-StateMachines.md)) | Machines have transitions, hierarchical states, `:always`/`:after`/`:invoke`, snapshots at `[:rf/machines <id>]`. Flows have one pure function and one output path. Use a machine when there are discrete states; use a flow when the value is a pure function of inputs. |
| **`on-changes` interceptor** (v1) | `on-changes` is wired into specific events' interceptor chains. Flows are registered globally and toggleable via `:rf.fx/reg-flow` / `:rf.fx/clear-flow`. The compute-on-change semantics are identical; the registration shape and lifecycle are different. |

Flows are also explicitly *not*:

- **A second runtime.** Flows participate in the standard event drain via one after-interceptor implicit on every event; there is no parallel scheduler. Compare the v1 alpha bardo state machine, lifecycle policies, and per-flow `:fx` mechanism — all gone.
- **A side-effect mechanism.** Flows compute values; they don't fire fx. If a derived value's change should trigger an effect, dispatch a follow-up event whose handler reads the flow's output and emits the effect.
- **A subscription replacement.** Most derived values are still subs. Flows pay an `app-db` write per recomputation; the value is more visible but slightly more expensive than a sub-cache hit.

## Migration from v1 alpha flows

| v1 alpha | v2 |
|---|---|
| `:id` | `:id` (unchanged) |
| `:inputs` (map of keyword → path-or-`flow<-`) | `:inputs` (vector of paths). Map-keyed inputs that referenced other flows via `flow<-` collapse to plain paths — the topological sort handles dependency ordering automatically. |
| `:output` (function of resolved-inputs map) | `:output` (function of positional inputs) |
| `:path` | `:path` (unchanged) |
| `:live?`, `:live-inputs` | Dropped. Use `:rf.fx/clear-flow` to toggle off; `:rf.fx/reg-flow` to toggle on. |
| `:cleanup` | Dropped. Default is `dissoc-in` on `:path`; opt-out is not provided. |
| Per-flow `:fx` | Dropped. Dispatch an event from a handler if you need fx on flow output change. |
| Lifecycle policies (`:safe`, `:no-cache`, `:reactive`, `:forever`) | Not applicable. Lifecycle policies are a sub-cache concern; flows have one cache state (registered-or-not). |
| `flow<-` reified flow-to-flow input | Dropped. Flow B reads flow A by listing A's `:path` in its `:inputs`. |
| `:reg-flow` / `:clear-flow` (unprefixed fx-ids) | Renamed to `:rf.fx/reg-flow` / `:rf.fx/clear-flow` per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned). |

The migration agent rewrites mechanically; flow definitions that used `:live?` lift to a wrapping event-handler that calls `:rf.fx/clear-flow` when the predicate flips false.

## Conformance fixtures (planned)

- `flow-basic.edn` — single flow, two inputs, one output. Verify dirty-check on input change.
- `flow-toggle.edn` — `:rf.fx/reg-flow` and `:rf.fx/clear-flow` from event handlers. Verify lifecycle.
- `flow-topsort.edn` — multi-layer flows; verify A runs before B when B depends on A's output.
- `flow-cycle.edn` — registering a cycle throws `:rf.error/flow-cycle`.
- `flow-no-recompute-equal.edn` — `app-db` write that produces `=`-equal value does not re-fire dependent flows.

## Open questions

### Map-keyed `:inputs` instead of vector

The vector form (`:inputs [[:width] [:height]] :output (fn [w h] ...)`) matches `on-changes` and is short. A map-keyed alternative (`:inputs {:w [:width] :h [:height]} :output (fn [{:keys [w h]}] ...)`) matches [Principles §Name over place](Principles.md#name-over-place). The vector is the v1 default; the map is the principled default. v2 ships the vector form for migration ergonomics; revisit if the map form proves preferable in practice.

### Synchronous re-walk after `:rf.fx/reg-flow`

A flow registered mid-event first fires on the next event drain (one-event lag for the initial value). An opt-in "register and run immediately" effect could close the lag at the cost of mid-event re-walking. Defer until a real use case forces it.

## Resolved decisions

### Topological sort over registration order (RESOLVED)

Earlier sketch leaned on registration order; topological sort selected because dynamic registration via `:rf.fx/reg-flow` makes registration order dispatch-time-dependent and an unreliable contract. The dependency graph is statically derivable from each flow's `:path` and `:inputs`; the cost of memoised topsort is small relative to event volume.

### One-pass evaluation, not fixed-point iteration (RESOLVED)

Topological sort lets every flow settle in one walk. Fixed-point iteration was considered as an alternative for cases where flows form mutual dependencies — but mutual dependencies are exactly cycles, which the topsort rejects at registration. With cycles forbidden, one pass suffices.

### Vector `:inputs`, not map (RESOLVED for v1; revisit later)

Per [§Open questions §Map-keyed `:inputs`](#map-keyed-inputs-instead-of-vector), the vector form ships in v1 for migration ergonomics. The map-keyed alternative remains a design option for a future iteration.

### `clear-flow` always `dissoc-in`s the output path (RESOLVED)

No opt-out. Stale derived values are confusing; vacating the slot is the natural toggle-off semantics. Apps that want to preserve the value should copy it elsewhere before clearing.

## Cross-references

- [001-Registration](001-Registration.md) — registration grammar (`reg-flow` is a kind under `:flow`).
- [002-Frames §Drain-loop pseudocode](002-Frames.md#drain-loop-pseudocode) — where the flow after-interceptor sits.
- [006-ReactiveSubstrate](006-ReactiveSubstrate.md) — sub-cache invalidation; flows trigger sub-cache invalidation when they write.
- [009-Instrumentation §Error contract](009-Instrumentation.md#error-contract) — `:rf.error/flow-cycle` namespace.
- [Conventions](Conventions.md) — `:rf.fx/reg-flow` and `:rf.fx/clear-flow` reserved fx-ids.
- [MIGRATION §M-19](MIGRATION.md) — generic call-shape migration; `:inputs` is positional vector matching the v1 `on-changes` form.
