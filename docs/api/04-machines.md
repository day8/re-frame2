# 04 — State machines

A state machine in re-frame2 is registered with one call (`reg-machine`) and *is* an event handler. The transition table is data — a map of `:states`, `:on`, `:entry`, `:exit`, `:after` — that gets compiled into a `reg-event-fx` handler at registration time. Dispatch an event at the machine's id and the table decides the transition; the resulting `:db` and `:fx` flow through the normal cascade.

The point of the machine surface isn't novelty — Statecharts have been around since 1987 — it's that the same trace bus, time-travel, and override surfaces that work for plain event handlers also work for machines, *because the machine is an event handler*. There's no parallel runtime to debug, no second store to inspect, no separate event log. Causa shows the machine state alongside `app-db`; the epoch buffer captures the snapshot the same way it captures everything else.

This chapter covers the registration surface (`reg-machine`, `reg-machine*`, `make-machine-handler`), the inspection / subscription surface (`sub-machine`, `machines`, `machine-meta`, `machine-by-system-id`), the dispatch sugar (`dispatch-to-system`, `:raise`), the actor-lifecycle fx (`:rf.machine/spawn`, `:rf.machine/destroy`), and the post-v1 tooling exports (`machine->xstate-json`, `machine->mermaid`).

For the *why* — the design rationale, the v1 vs post-v1 split, the capability matrix — see [005-StateMachines.md](../../spec/005-StateMachines.md).

## Registration

| API | M/Fn | Signature | Status | Intuition |
|---|---|---|---|---|
| `reg-machine` | M | `(reg-machine machine-id machine-spec)` | v1 | The canonical macro. Walks the literal spec form at expansion time and stamps per-element source coords under `:rf.machine/source-coords` — Causa uses these to navigate from a snapshot back to the state-node definition. Top-level call-site coords land on `handler-meta`. |
| `reg-machine*` | Fn | `(reg-machine* machine-id machine-spec)` | v1 | Plain-fn surface beneath the macro. No source-coord walking. Use for code-gen pipelines, REPL workflows, or conformance harnesses that synthesise specs from data. |
| `make-machine-handler` | Fn | `(make-machine-handler spec)` → event-handler fn | v1 | Compiles a transition table into the event-handler fn that `reg-machine` would register. Useful when you want to inspect the compiled fn or compose it manually. |
| `machine-transition` | Fn | `(machine-transition definition snapshot event)` → `[next-snapshot effects]` | v1 | The pure transition fn. Given a machine definition, a current snapshot, and an event, returns the next snapshot and the effect map. JVM-runnable; the conformance harness uses this as its primary test surface for machine behaviour. |

### A minimal machine

```clojure
(rf/reg-machine :session
  {:initial :anonymous
   :states  {:anonymous     {:on {:login {:target :authenticating
                                          :data   (fn [data event]
                                                    (assoc data :credentials (second event)))}}}
             :authenticating {:after  {500 {:target :timeout}}
                              :entry  [{:fx [[:rf.http/managed {...}]]}]
                              :on     {:auth-ok   {:target :authenticated}
                                       :auth-fail {:target :anonymous}}}
             :authenticated {:on {:logout {:target :anonymous}}}
             :timeout       {:on {:retry {:target :anonymous}}}}})

;; The machine IS an event handler — dispatch at its id.
(rf/dispatch [:session [:login {:user "alice" :pass "..."}]])
```

The snapshot lives at `[:rf/machines :session]` in `app-db`. The shape is `{:state :anonymous :data {...}}` (plus framework-managed slots for `:after` timer epochs and tags). Read it via `sub-machine` or directly with `subscribe-once`.

## Inspection and subscription

| API | M/Fn | Signature | Status | Intuition |
|---|---|---|---|---|
| `sub-machine` | Fn | `(sub-machine machine-id)` → reaction over snapshot | v1 | Sugar over `(subscribe [:rf/machine machine-id])`. Use inside views — gives you a reaction over `{:state :data}`. |
| `machines` | Fn | `(machines)` → seq of machine-ids | v1 | "What machines have been registered?" Derived view over `(registrations :event)` filtered by `:rf/machine? true`. |
| `machine-meta` | Fn | `(machine-meta machine-id)` → registration-metadata map | v1 | "What did `reg-machine` stamp at this machine's id?" Returns the transition table, doc, schemas, and the per-element source-coords. Equivalent to `(handler-meta :event machine-id)`. |
| `machine-by-system-id` | Fn | `(machine-by-system-id system-id)` <br> `(machine-by-system-id system-id frame-id)` | v1 | Reverse-lookup: given a `system-id`, what's the spawned machine bound to it? Returns the spawned-machine id or `nil`. |
| `machine-has-tag?` | Fn | `(machine-has-tag? machine-id tag)` → reaction | v1 | Sugar over `(subscribe [:rf/machine-has-tag? machine-id tag])`. Reactive predicate over the machine's snapshot's `:tags` set. Use in views to render conditionally on state-tag membership. |

### Standard registered subs (machines)

| Sub | Returns | Spec |
|---|---|---|
| `[:rf/machine <machine-id>]` | The machine's snapshot `{:state :data}` (or `nil` if not yet initialised) | 005 |

`sub-machine` is sugar over this — see [005 §Subscribing to machines](../../spec/005-StateMachines.md#subscribing-to-machines-via-sub-machine).

## Cross-machine messaging

| API | M/Fn | Signature | Status | Intuition |
|---|---|---|---|---|
| `dispatch-to-system` | Fn | `(dispatch-to-system system-id event)` <br> `(dispatch-to-system system-id event frame-id)` | v1 | Sugar over `(when-let [m (machine-by-system-id system-id)] (dispatch [m event]))`. No-op when the `system-id` is unbound. The third-arity targets a non-default frame. |

When a child actor spawns under a parent, the parent's `:data` often gets the child's id stamped via `:on-spawn`. `dispatch-to-system` lets the parent name the child by *role* (`:logger`, `:websocket`, `:retry-coordinator`) instead of by gensym'd id. The per-frame `[:rf/system-ids]` reverse index resolves the name. See [005 §Named addressing via `:system-id`](../../spec/005-StateMachines.md).

## The actor-lifecycle fx

| `[fx-id args]` | Args | Status | Spec | Intuition |
|---|---|---|---|---|
| `[:rf.machine/spawn spawn-spec]` | spawn-spec map (per `:rf.fx/spawn-args`) | v1 | 005 | "Spawn a dynamic actor instance." Args carry `:machine-id` (the definition to instantiate), `:id-prefix`, `:data` (initial), `:on-spawn` (event dispatched with the gensym'd id), and `:start` (events to deliver immediately). Emitted from any event handler's `:fx` (including machine actions and the `:spawn` desugar). |
| `[:rf.machine/destroy actor-id]` | actor id (keyword) | v1 | 005 | "Tear down this actor." Runs the actor's `:exit` action, dissociates `[:rf/machines <actor-id>]`, and clears the actor's event-handler registration. Symmetric counterpart to `:rf.machine/spawn`. |
| `[:raise event-vec]` | event vector | v1 | 005 | **Machine-only.** Inside a machine action's `:fx`, routes the event back into the same machine atomically and pre-commit. Unbound outside machine actions. |

### Spawn pattern

```clojure
(rf/reg-event-fx :session/start-logger
  (fn [_ _]
    {:fx [[:rf.machine/spawn
           {:machine-id :machines/log-shipper
            :id-prefix  :logger
            :data       {:buffer []}
            :on-spawn   [:session/logger-spawned]
            :start      [[:logger/connect]]}]]}))

;; The handler at :session/logger-spawned receives the gensym'd id:
;; [:session/logger-spawned :logger.4f7c2a]
(rf/reg-event-db :session/logger-spawned
  (fn [db [_ logger-id]]
    (assoc-in db [:session :logger] logger-id)))
```

## Final states and `:on-done`

Machines support **final states** — leaf states marked `:final?` that auto-destroy the machine on entry. The parent (if any) receives `:on-done` with the child's `:data` slot.

| State-node key | What it does |
|---|---|
| `:final?` | Marks a leaf state as terminal. Entering it auto-destroys the machine. Capability axis `:fsm/final-states`. |
| `:output-key` | Requires `:final?`. Designates the child's `:data` slot reported back via the parent's `:on-done`. |
| `:on-done` (spawn-spec key) | `(fn [{:keys [data result]}] new-data)` on the parent's `:spawn` map. Fires synchronously when the spawned child enters a `:final?` state. `result` is the child's `:data` slot named by the final state's `:output-key` (or `nil`). |

The pattern: a spawn-shaped sub-process completes, the parent receives the result through `:on-done`, the framework destroys the child. No manual `:rf.machine/destroy` needed.

See [005 §Final states](../../spec/005-StateMachines.md#final-states-final--on-done--output-key).

## Post-v1 tooling exports

| API | M/Fn | Signature | Status | Intuition |
|---|---|---|---|---|
| `machine->xstate-json` | Fn | `(machine->xstate-json definition)` → JSON string | post-v1 lib | Export a machine definition to XState JSON. The XState visualiser consumes it; useful for design review and documentation. |
| `machine->mermaid` | Fn | `(machine->mermaid definition)` → string | post-v1 lib | Export to Mermaid state-diagram syntax. Drops cleanly into Markdown docs that render Mermaid (this site does). |
| `:child-machine` (transition-table key) | — | Declarative state-scoped child-machine binding. | post-v1 lib | A state node can declare a child machine that lives only while the parent is in that state. Symmetric with the imperative `:spawn` / `:destroy` cycle. |

The post-v1 surfaces live in `day8/re-frame2-machines` (the scaffolding library). The v1 foundation in `re-frame.core` covers the machine-as-event-handler primitive; the scaffolding library layers the higher-level features on top.

## Capability matrix

The v1 transition-table grammar covers a specific subset of Statechart capabilities — sequencing, `:after` timers, internal-vs-external transitions, guards, action lists, hierarchical states, parallel regions where the framework's epoch model can tolerate them, and final-state semantics. The exact subset and its rationale lives at [005 §Capability matrix](../../spec/005-StateMachines.md#capability-matrix); the schema shape is [Spec-Schemas §`:rf/transition-table`](../../spec/Spec-Schemas.md#rftransition-table).

## See also

- [01 — Core](01-core.md) — `reg-machine` rowed in registration.
- [08 — Schemas](08-schemas.md) — machines declare schemas for their `:data` slot the same way ordinary handlers do.
- [11 — Instrumentation](11-instrumentation.md) — machine snapshots are part of the epoch buffer; transitions emit trace events.
- [Spec 005 — State Machines](../../spec/005-StateMachines.md) — the normative source.
