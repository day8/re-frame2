# Spec 013 — Flows

> Status: v1-required. Builds on the registration grammar in [001-Registration](001-Registration.md), the drain in [002-Frames §Run-to-completion](002-Frames.md#run-to-completion-dispatch-drain-semantics), and the reserved-namespace policy in [Conventions](Conventions.md).
>
> **The minimum claim:** flows are *registered, runtime-toggleable computed-state declarations that materialise their output into `app-db`*. They are the v2 incarnation of v1's `on-changes` interceptor — same compute-on-input-change semantics — but registered in the runtime (not on individual events) and toggleable via two reserved fx-ids.
>
> **Restraint.** Flows are a **convenience** for a small number of small use-cases. They are not a replacement for subscriptions, not a new dataflow paradigm, not a substitute for state machines, and not the default for derived state. The architecture's main load-bearing pieces are events, subs, machines, and effects; flows sit alongside them as a focused tool for a narrow set of problems. **When in doubt, use a subscription** — flows pay an `app-db` write per recomputation and add a small piece of registered runtime; that cost is only worthwhile when the [reasons in §When (and when not) to use a flow](#when-and-when-not-to-use-a-flow) below apply.
>
> `:rf.flow/*` is the **internal-effect cousin** of the managed external effects — per [Managed-Effects](Managed-Effects.md), the surface MUST satisfy the eight properties applied to derived computation (effect-as-data via the registration map, framework-owned scheduler, structured failure taxonomy under `:rf.flow/*`, trace-bus observability, `:sensitive?` / `:large?` composition on flow outputs, runtime-toggleable retry / abort / teardown via `:rf.fx/reg-flow` / `:rf.fx/clear-flow`, in-flight flow registry, per-frame scoping).

## Abstract

A **flow** is a registered rule that says: "when these `app-db` paths change, run this pure function and write the result to that `app-db` path." Flows are evaluated automatically after every event drain, in topological order over their static input/output dependency graph.

Flows differ from subscriptions in *where the value lives*. A sub's value lives in the per-frame sub-cache and is consumed by views. A flow's value lives in `app-db` at a known path, where it survives SSR / hydration / time-travel revert, is visible in the app-db inspector, can be read by downstream event handlers and other flows, and is covered by registered schemas. When the derived value is part of the application's *state* (as opposed to part of a view's render input), use a flow.

**Flows are frame-scoped.** A flow belongs to one frame: its registration, evaluation, output `app-db` path, and undo / time-travel boundaries are all frame-local. The same flow id can register against two different frames with two different `:output` functions and two different `:path` slots; clearing the flow on one frame leaves the other untouched. See [§Frame-scoping](#frame-scoping) for the rationale and API.

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

`reg-flow` returns the flow's `:id` — the primary id under which the flow registers in the `:flow` kind — per the family-wide [`reg-*` return-value convention](Conventions.md#reg--return-value-convention). The id is carried by the flow-map rather than as a separate positional arg, but the return-value contract is the same as the rest of the `reg-*` family.

`reg-flow` accepts an optional second argument carrying a `:frame` opt — the frame the flow registers against. Default is `(current-frame)` (per [002 §How `:frame` gets attached](002-Frames.md#how-frame-gets-attached), usually `:rf/default` unless the call sits inside a `with-frame` form or under a frame-providing context):

```clojure
(rf/reg-flow flow)                    ;; defaults frame to (current-frame)
(rf/reg-flow flow {:frame :scratch})  ;; explicit frame
```

`clear-flow` mirrors the shape:

```clojure
(rf/clear-flow :rectangle/area)
(rf/clear-flow :rectangle/area {:frame :scratch})
```

## Frame-scoping

Flows are **frame-scoped**: registration, evaluation, and `clear-flow`'s `dissoc-in` all belong to one frame. The runtime registry shape is:

```
{frame-id {flow-id flow-map}}
```

Three consequences follow:

1. **Per-frame undo / time-travel boundaries.** Time-travel is a frame-local primitive (per [002 §Frames](002-Frames.md)). A flow's `:path` write is part of the owning frame's `app-db` history; reverting frame `:left` does not disturb flow outputs in frame `:right`.
2. **Same flow-id, multiple frames, independent definitions.** Registering `:compute` against `:left` with `(fn [x] (* 2 x))` and against `:right` with `(fn [x] (* 100 x))` produces two independent flows. Each frame's `run-flows!` walks only its own slot of the registry.
3. **`clear-flow` is frame-local.** `(clear-flow :compute {:frame :left})` removes the flow's definition from frame `:left` and `dissoc-in`s its `:path` from `:left`'s `app-db` only. Frame `:right`'s `:compute` and its output keep working. The shared `:flow` registrar slot is only unregistered when the *last* frame holding the id releases it (so hot-reload tracking survives multi-frame setups).

**Registrar slot semantics under multi-frame registration.** The `:flow` registrar kind (per [001-Registration §Registration grammar](001-Registration.md#registration-grammar)) is keyed by `flow-id` only — the same flow id registered against multiple frames shares one registrar slot. The per-frame runtime registry `{frame-id {flow-id flow-map}}` is the source of truth for evaluation; the registrar slot is metadata used for hot-reload tracking and tooling introspection. When the same flow id is registered against two frames with different `:output` fns and `:path` slots, **the registrar slot carries the most-recently-registered frame's flow-map** with `:frame frame-id` stamped into the metadata. Callers reading the slot via the registrar (e.g. a Causa flow panel that wants to enumerate "all flows in the runtime") observe last-registration-wins on the metadata view while every frame's `run-flows!` continues to evaluate against its own slot of the runtime registry unaffected.

This asymmetry is **intentional for v1**: the runtime correctness (each frame's flows compute independently) is the load-bearing property; the registrar metadata is a tooling convenience. A future spec revision MAY introduce a frame-aware query surface (`flow-meta` / `(flows {:frame ...})`) or migrate the registrar slot to a frame-indexed shape, but doing so today would inflate the registrar API surface for a use case (tools enumerating cross-frame flows) that has not surfaced in v1. Apps targeting multi-tenant frames with shared flow ids and per-frame-different definitions should rely on the runtime registry (`@re-frame.flows/flows`), not the registrar slot, for full per-frame discovery.

Frame defaulting matches the rest of the API: a bare `(reg-flow flow)` resolves the frame via `(current-frame)`, picking up `with-frame` bindings or falling through to `:rf/default`. Tests and per-tenant runtimes that need an explicit frame pass `{:frame ...}` as the second arg.

## Frame-destroy teardown

Flows are frame-scoped, so `destroy-frame!` is the boundary at which every per-frame piece of flow state MUST clear. This is a **normative requirement** — the frame-isolation contract from [002 §Destroy](002-Frames.md#destroy) is only honoured if flow registrations, the dirty-check `last-inputs` cache, and any `:flow` registrar slot whose last owning frame was the destroyed one all release in lockstep with the frame. Without lockstep teardown a long-running JVM SSR host (per-request frame churn), a pair-tool time-travel cycle, or any `make-frame` ephemeral usage leaks flow definitions and cached input vectors indefinitely.

Three teardown invariants apply on `(destroy-frame! frame-id)`:

1. **Per-frame flow registry.** `(get @flows frame-id)` clears in full — every flow registered against `frame-id` is dropped. Sibling frames' slots are unaffected (per [§Frame-scoping](#frame-scoping)).
2. **Dirty-check cache.** Every `last-inputs[flow-id][frame-id]` row for the destroyed frame is dissoc'd. The whole `last-inputs[flow-id]` key is dropped when no other frame still holds an entry. Sibling frames' rows for the same flow id are preserved (a flow id registered against frames `:left` and `:right`, with frame `:left` destroyed, keeps `last-inputs[flow-id][:right]` intact).
3. **`:flow` registrar slot.** The cross-kind registrar entry for each flow id the destroyed frame owned is `unregister!`'d **iff no surviving frame still registers that id**. When a sibling frame still holds the same flow id, the registrar slot survives (other frames need it for hot-reload tracking).

Teardown is idempotent against a frame the registry never recorded — a `destroy-frame!` on a freshly-`reg-frame`'d frame with no flows ever registered against it leaves the flow registry, `last-inputs`, and registrar entries unchanged.

The teardown contract is symmetric with the machines artefact's `:machines/teardown-on-frame-destroy!` hook and the schemas artefact's `:schemas/on-frame-destroyed!` hook — every per-feature artefact that holds frame-scoped state hangs its cleanup off the single normative `destroy-frame!` teardown boundary documented at [002 §Destroy](002-Frames.md#destroy). A new feature artefact MUST add its hook to the destroy cascade; a feature that holds frame-scoped state without one leaks on every `destroy-frame!`.

## Drain integration

Flow evaluation happens **after `:db` commits and before `:fx` walks** on every event drain. Per [002 §Drain-loop pseudocode](002-Frames.md#drain-loop-pseudocode), the per-event drain inserts one step:

```
process-event! (revised, with flows):
  1. Resolve handler.
  2. Run interceptor chain.
  3. Apply :db. Sub-cache invalidates.
  4. run-flows! frame-id                                     ← NEW
       Walk THIS FRAME'S registered flows in topologically-
       sorted order — i.e. (get @flows frame-id) only;
       sibling frames' flows are not visited.
       For each flow in this frame's slot:
         new-inputs ← read input paths from new app-db
         if new-inputs ≠ last-inputs[[frame-id flow-id]]:
           new-output ← (apply :output new-inputs)
           app-db ← (assoc-in app-db (:path flow) new-output)
           last-inputs[[frame-id flow-id]] ← new-inputs
       Sub-cache invalidates again if any flow wrote.
  5. Walk :fx in source order.
```

Four properties this gives:

1. **`:fx` entries see flow outputs.** An `:fx` entry that reads `app-db` after the handler returns sees flow-computed values. This is what makes `[:dispatch [:react-to-area-change]]` work cleanly.
2. **Single pass per event.** Each flow runs at most once per drain. The topological order ensures multi-layer flows settle in one walk.
3. **Run-to-completion is preserved.** Views never observe an intermediate state where some flows have updated and others haven't.
4. **Frame isolation.** An event dispatched on frame `:left` only walks flows registered against `:left`. Flows on frame `:right` are dormant from `:left`'s perspective — they walk only when `:right`'s drain calls its own `run-flows!`. This is what makes multi-tenant frames safe to colocate without cross-talk in derived state.

## Topological sort and cycle detection

Flows form a static dependency graph derivable from their `:path` and `:inputs` declarations. The graph is **per-frame** — flows in different frames cannot depend on each other (their inputs read different `app-db`s). Each frame's topsort is computed independently over `(get @flows frame-id)`.

**Dependency rule.** Flow B depends on flow A iff A's `:path` and any of B's `:inputs` share a path prefix in either direction:

- Exact match: `A.path = [:foo]`, `B.inputs = [[:foo]]` — B reads exactly what A writes.
- A's path is a prefix of B's input: `A.path = [:foo]`, `B.inputs = [[:foo :bar]]` — B reads inside A's value.
- B's input is a prefix of A's path: `A.path = [:foo :bar]`, `B.inputs = [[:foo]]` — A's write is part of B's input map.

The runtime topologically sorts the registry by this dependency relation. The sort is **not memoised** in v1 — the per-frame flow map is tiny (a handful of nodes) and Kahn's algorithm over it is cheaper than the bookkeeping a memo would need. An earlier sketch carried a memoised topsort with explicit invalidation on every `reg-flow` / `clear-flow`; the memo was removed (per rf2-cd00) once measurement confirmed the unmemoised call is the cheapest correct option at the per-frame node counts v1 targets. Implementations that observe a real bottleneck in topsort cost MAY add a `core.memoize`-style cache keyed on the flow-registry identity, but the contract is just: deterministic order over the dependency graph each drain.

**Cycle detection.** If A depends on B and B depends on A (any indirection), `reg-flow` throws `:rf.error/flow-cycle` at registration time. The thrown `ex-info`'s `ex-data` carries `:cycle` — an ordered vector of flow ids with a **closing repeat** that names the offending chain (e.g. `[:a :b :a]` for the two-flow cycle `:a → :b → :a`). The error fires before any snapshot is created — caught at registration, not at runtime.

```clojure
;; This will throw at registration:
(rf/reg-flow {:id :a :inputs [[:b]] :output identity :path [:a]})
(rf/reg-flow {:id :b :inputs [[:a]] :output identity :path [:b]})
;; → ex-info ":rf.error/flow-cycle" {:cycle [:a :b :a]}
```

The closing repeat is the contract: tools rendering the cycle (e.g. Causa) display the path verbatim. For an n-flow cycle the `:cycle` vector has `(inc n)` elements, with `(first cycle) = (last cycle)`. The starting node is implementation-defined (deterministic but unspecified) — multiple cycles can yield any one of them as the reported chain.

Cycles can also form *during* flow registration if the new flow completes a cycle that was incomplete before it was registered. The detection runs every `reg-flow` call.

## Dirty-check semantics

A flow recomputes only when its inputs change by **`=`-equality** since its last evaluation:

```
new-inputs ← (mapv #(get-in app-db %) (:inputs flow))
if new-inputs ≠ last-inputs[[frame-id flow-id]]:
  recompute and write
```

The `last-inputs` table is keyed by `[frame-id flow-id]` so the same flow id registered against two frames maintains two independent dirty-check windows.

Three implications:

1. **No-op `app-db` writes don't trigger.** A handler that writes the same value back to `:width` does not re-fire flows that depend on `:width`.
2. **Path-overlap is sufficient, not necessary, for re-firing.** A flow whose inputs sit at `[:user :profile :name]` does not re-fire when an unrelated path like `[:cart :items]` changes. The dirty-check is per-flow, not per-app-db-change.
3. **First evaluation always fires.** A newly-registered flow's `last-inputs` is uninitialised; its first walk recomputes unconditionally and produces the initial output value.

## Failure semantics

When a flow's `:output` fn throws during `run-flows!`, the runtime applies these four rules atomically (rf2-wyt97 / rf2-hrqvg):

1. **Prior-flow writes are preserved.** Flows scheduled earlier in the same drain that successfully computed and produced dirty writes have their outputs flushed to `app-db` (one `replace-container!` against the loop accumulator) BEFORE the exception propagates. Earlier flows' work is never silently lost.
2. **The failing flow's own output is not written.** The exception happened during `:output`; there is no usable new-output to assoc-in. Its `last-inputs` slot is NOT advanced, so the flow re-attempts on the next drain.
3. **The cascade halts at the failing flow.** Downstream flows scheduled later in topo order do NOT run on this drain. They re-attempt naturally on the next drain (with whatever inputs the prior flush left in `app-db`).
4. **The exception surfaces at the router's outer catch** as `:rf.error/flow-eval-exception` (per [009 §Error contract](009-Instrumentation.md#error-contract)). The cascade-level error is emitted onto the **always-on production error-emit substrate** ([009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code)) — the per-frame `:on-error` policy fn fires, every `register-error-emit-listener!` callback fires, and both fan-out paths are mutually isolated. The substrate is NOT gated by `re-frame.interop/debug-enabled?`, so `:rf.error/flow-eval-exception` survives CLJS `:advanced` + `goog.DEBUG=false` elision: a flow-eval failure in a production build reaches every registered off-box error monitor (Sentry / Honeybadger / Rollbar / hosted observability) at full fidelity. The per-flow `:rf.flow/failed` trace event ALSO fires first with the flow-attributed detail, but that trace rides the dev-only trace surface and DCEs in production — production attribution is preserved on the always-on path via `:flow-id` / `:flow` slots stamped onto the cascade-level error's `:tags`.

Worked example. Three flows in topo order — `:A`, `:B`, `:C`. Inputs change for all three. `:B` throws. After the drain:

- `:A`'s output is written to `app-db` at its `:path` (rule 1).
- `:A`'s `last-inputs` is advanced (rule 1 — `:A` computed successfully).
- `:B`'s `:path` is unchanged from before the drain (rule 2).
- `:B`'s `last-inputs` is unchanged (rule 2).
- `:C` did not run; its `:path` is unchanged and its `last-inputs` is unchanged (rule 3).
- Two trace events fired in order: `:rf.flow/computed` for `:A`, then `:rf.flow/failed` for `:B`. Then the router's outer catch emitted `:rf.error/flow-eval-exception` (rule 4).

**Rationale.** This is the strongest 'no work is silently lost' guarantee compatible with surfacing flow failures as cascade-level errors. The alternative (per-flow isolation: `:C` runs anyway) would prevent the cascade halt that downstream `:fx` and tooling rely on to skip work that depended on a now-invalid derived state. The opposite alternative (discard prior writes too) would silently drop `:A`'s output while its `:rf.flow/computed` trace claimed the write happened — an observability lie. Preserving prior writes and halting the cascade keeps both 'completed work is visible in app-db' and 'failures surface as errors' true at once.

## Flow tracing

Every flow lifecycle event emits a structured trace event under op-type `:flow`. The full taxonomy lives in [009 §Flow trace events](009-Instrumentation.md#flow-trace-events); the summary:

| `:operation` | Fires when |
|---|---|
| `:rf.flow/registered` | `reg-flow` (or `:rf.fx/reg-flow`) successfully registers a flow against a frame, after cycle detection passes. |
| `:rf.flow/computed` | A flow's `:output` fn ran and the result was written to `:path` (dirty-check observed input value-difference). |
| `:rf.flow/skip` | The dirty-check found inputs `=`-equal to the previous run; the recompute was suppressed (§[Dirty-check semantics](#dirty-check-semantics) above; rf2-719e value-equal recompute suppression). |
| `:rf.flow/cleared` | `clear-flow` (or `:rf.fx/clear-flow`) removed the flow from the per-frame registry and dissoc-in'd its output path. |
| `:rf.flow/failed` | A flow's `:output` fn threw during recompute. The exception is re-thrown after the trace fires; see [§Failure semantics](#failure-semantics) for the four-rule contract (prior writes preserved, failing-flow's `last-inputs` not advanced, cascade halts, router emits `:rf.error/flow-eval-exception` per [009 §Error contract](009-Instrumentation.md#error-contract)). |

Every event carries `:flow-id` and `:frame` under `:tags`. Pair-shaped tools, Causa's flow panel, and custom dashboards filter `op-type :flow` to subscribe to the whole flow stream — see [Tool-Pair §How AI tools attach](Tool-Pair.md#how-ai-tools-attach) and [009 §Flow trace events](009-Instrumentation.md#flow-trace-events) for the consumer-side pattern.

**`:sensitive?` inheritance.** A flow's `:output` fn runs inside the after-interceptor of the surrounding handler scope; the dirty-check write and any thrown exception are framework-owned but the resolved input values and computed output ride from the **handler whose event triggered the drain**. The runtime therefore stamps `:sensitive? true` at the top level of every `:rf.flow/*` trace event when the in-scope handler's registration meta carries `:sensitive? true` — per the inheritance rule at [009 §The `:sensitive?` registration metadata key](009-Instrumentation.md#the-sensitive-registration-metadata-key). The flow itself does not declare `:sensitive?` directly; the marker rides the cascade. An auth-handler dispatching `[:auth/signed-in token]` whose drain re-evaluates the `:auth/derived-user` flow emits a `:rf.flow/computed` carrying `:sensitive? true`, and the framework-published forwarders (Sentry / Honeybadger / pair2 / Causa-MCP) default-drop it. Apps that need finer-grained per-flow privacy reach for `with-redacted` on the surrounding handler or scrub the `:output` fn's return value at the source.

The whole flow trace surface, like the rest of trace, is compile-time eliminated in production builds (per [009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code)).

## Dynamic toggle via fx

Two reserved fx-ids let event handlers register and clear flows during normal event processing:

| Fx-id | Args | Effect |
|---|---|---|
| `:rf.fx/reg-flow` | A flow map (same shape as `reg-flow`'s argument) | Register the flow against the dispatching frame. Next drain's topsort observes the new node (no cache to invalidate; per [§Topological sort and cycle detection](#topological-sort-and-cycle-detection)). |
| `:rf.fx/clear-flow` | A flow id | Clear the flow from the dispatching frame. `dissoc-in` on its `:path` in that frame's `app-db`. Next drain's topsort observes the removal. |

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

**Frame routing.** Both fx run inside the standard `:fx` walk and receive the `{:frame frame-id}` cofx from the dispatching frame. They thread the frame through to `reg-flow` / `clear-flow` as the `:frame` opt — there is no explicit `:frame` to set in the fx args. A flow registered via `:rf.fx/reg-flow` from an event dispatched on frame `:left` is registered against `:left`; the same fx invoked from a `:right` dispatch routes to `:right`. This makes fx-driven flow lifecycle (wizard step in / out, feature gating) automatically frame-correct without ceremony.

**Sequencing.** `:rf.fx/reg-flow` and `:rf.fx/clear-flow` run during the standard `:fx` walk (per [002 §`:fx` ordering and atomicity guarantees](002-Frames.md#fx-ordering-and-atomicity-guarantees)) — *after* the flow after-interceptor has already evaluated for the current event. A flow registered mid-event therefore first runs on the *next* event drain on the same frame. Its initial output appears one event after registration. Apps that need the value immediately can dispatch a synthetic re-walk event after registration.

**`clear-flow` cleanup.** Default behaviour is `dissoc-in` on the flow's `:path` in the owning frame's `app-db` — the slot is vacated when the flow goes away. Stale derived values left behind would confuse downstream consumers. Apps that want to preserve the value should copy it elsewhere before clearing. Sibling frames are unaffected.

## Re-registration

`reg-flow` with an already-registered `:id` (against the same frame) performs a **surgical update** — same semantics as every other `reg-*` per [001-Registration §Hot-reload semantics](001-Registration.md#hot-reload-semantics). The new flow's definition replaces the old in `(get @flows frame-id)`; `last-inputs` for `[frame-id flow-id]` is reset (the new flow re-evaluates on the next event regardless of input change); the next drain's topsort observes the new dependency edges automatically (per [§Topological sort and cycle detection](#topological-sort-and-cycle-detection); v1 does not memoise the sort). In-flight events finish against the resolved handler at the time they entered the drain. Re-registering the same flow id against a *different* frame is not a replacement — it adds an independent definition to the second frame's slot.

## What flows are NOT

Three near-neighbours flows are *not*:

| Concept | Difference |
|---|---|
| **Subscription** ([006](006-ReactiveSubstrate.md)) | Subs live in the sub-cache; consumed by views. Flows live in `app-db`; consumed by everything (handlers, other flows, schemas, SSR payload). When the value is part of the application's *state*, use a flow; when it's part of view rendering only, use a sub. |
| **State machine** ([005](005-StateMachines.md)) | Machines have transitions, hierarchical states, `:always`/`:after`/`:invoke`, snapshots at `[:rf/machines <id>]`. Flows have one pure function and one output path. Use a machine when there are discrete states; use a flow when the value is a pure function of inputs. |
| **`on-changes` interceptor** (v1) | `on-changes` is wired into specific events' interceptor chains. Flows are registered against a frame and toggleable via `:rf.fx/reg-flow` / `:rf.fx/clear-flow`. The compute-on-change semantics are identical; the registration shape and lifecycle are different. |

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
- `flow-frame-scoped.edn` — same flow id registered against two frames with different `:output` fns produces two independent results on the same input; `clear-flow` on one frame leaves the other intact.

## Open questions

### Map-keyed `:inputs` instead of vector

The vector form (`:inputs [[:width] [:height]] :output (fn [w h] ...)`) matches `on-changes` and is short. A map-keyed alternative (`:inputs {:w [:width] :h [:height]} :output (fn [{:keys [w h]}] ...)`) matches [Principles §Name over place](Principles.md#name-over-place). The vector is the v1 default; the map is the principled default. v2 ships the vector form for migration ergonomics; revisit if the map form proves preferable in practice.

### Synchronous re-walk after `:rf.fx/reg-flow`

A flow registered mid-event first fires on the next event drain (one-event lag for the initial value). An opt-in "register and run immediately" effect could close the lag at the cost of mid-event re-walking. Defer until a real use case forces it.

## Resolved decisions

### Topological sort over registration order (RESOLVED)

Earlier sketch leaned on registration order; topological sort selected because dynamic registration via `:rf.fx/reg-flow` makes registration order dispatch-time-dependent and an unreliable contract. The dependency graph is statically derivable from each flow's `:path` and `:inputs`; recomputing the sort once per drain is cheap at v1's per-frame node counts (a handful of flows, Kahn's algorithm over them is cheaper than memo bookkeeping). The sort is not memoised — per [§Topological sort and cycle detection](#topological-sort-and-cycle-detection); an earlier memoised variant was removed under rf2-cd00 after measurement.

### One-pass evaluation, not fixed-point iteration (RESOLVED)

Topological sort lets every flow settle in one walk. Fixed-point iteration was considered as an alternative for cases where flows form mutual dependencies — but mutual dependencies are exactly cycles, which the topsort rejects at registration. With cycles forbidden, one pass suffices.

### Vector `:inputs`, not map (RESOLVED for v1; revisit later)

Per [§Open questions §Map-keyed `:inputs`](#map-keyed-inputs-instead-of-vector), the vector form ships in v1 for migration ergonomics. The map-keyed alternative remains a design option for a future iteration.

### `clear-flow` always `dissoc-in`s the output path (RESOLVED)

No opt-out. Stale derived values are confusing; vacating the slot is the natural toggle-off semantics. Apps that want to preserve the value should copy it elsewhere before clearing.

### Frame-destroy teardown is mandatory (RESOLVED rf2-0q0du)

`destroy-frame!` MUST release every per-frame piece of flow state — the per-frame flow-registry slot, all `last-inputs` rows for the destroyed frame, and every `:flow` registrar entry the destroyed frame was the last owner of. Sibling frames' rows and shared-id registrar slots are preserved. Per [§Frame-destroy teardown](#frame-destroy-teardown). Without this, long-running SSR JVM hosts (per-request frame churn), pair-tool time-travel, and `make-frame` ephemeral usage leak flow definitions and cached input vectors indefinitely. Symmetric with the machines / schemas / SSR teardown hooks the per-feature artefacts publish off the single normative `destroy-frame!` boundary at [002 §Destroy](002-Frames.md#destroy).

### `:rf.error/flow-eval-exception` rides the always-on error substrate (RESOLVED rf2-0q0du)

Flow evaluation failures MUST surface on the always-on production error-emit substrate (per [009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code)), NOT on the dev-only trace surface alone. Both fan-out paths — the per-frame `:on-error` policy fn and the corpus-wide `register-error-emit-listener!` registry — fire under CLJS `:advanced` + `goog.DEBUG=false`. The per-flow `:rf.flow/failed` trace still fires first with full flow-attributed detail, but it rides the dev-only trace surface and DCEs in production; flow attribution survives on the always-on path via `:flow-id` / `:flow` slots stamped onto the cascade-level error's `:tags`. Without this routing, a production-build flow-eval failure was silently dropped — no `:on-error` fire, no off-box monitor record. Per [§Failure semantics](#failure-semantics) rule 4.

## Cross-references

- [001-Registration](001-Registration.md) — registration grammar (`reg-flow` is a kind under `:flow`).
- [002-Frames §Drain-loop pseudocode](002-Frames.md#drain-loop-pseudocode) — where the flow after-interceptor sits.
- [002-Frames §Destroy](002-Frames.md#destroy) — the normative teardown boundary `:rf.flow/*` state hangs off; cross-referenced from [§Frame-destroy teardown](#frame-destroy-teardown).
- [006-ReactiveSubstrate](006-ReactiveSubstrate.md) — sub-cache invalidation; flows trigger sub-cache invalidation when they write.
- [009-Instrumentation §Error contract](009-Instrumentation.md#error-contract) — `:rf.error/flow-cycle` and `:rf.error/flow-eval-exception` namespaces.
- [009-Instrumentation §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code) — the always-on error-emit substrate `:rf.error/flow-eval-exception` rides; cross-referenced from [§Failure semantics](#failure-semantics) rule 4.
- [009-Instrumentation §Flow trace events](009-Instrumentation.md#flow-trace-events) — full taxonomy and payloads for the `:rf.flow/*` event vocabulary; cross-referenced from [§Flow tracing](#flow-tracing) above.
- [009-Instrumentation §The `:sensitive?` registration metadata key](009-Instrumentation.md#the-sensitive-registration-metadata-key) — `:rf.flow/*` trace events inherit `:sensitive?` from the in-scope handler at drain time; cross-referenced from [§Flow tracing](#flow-tracing) above.
- [Conventions](Conventions.md) — `:rf.fx/reg-flow` and `:rf.fx/clear-flow` reserved fx-ids.
- [MIGRATION §M-19](MIGRATION.md) — generic call-shape migration; `:inputs` is positional vector matching the v1 `on-changes` form.
