# 04 — State machines

A state machine in re-frame2 is registered with one call (`reg-machine`) and *is* an event handler. The transition table is data — a map of `:states`, `:on`, `:entry`, `:exit`, `:after` — that gets compiled into a `reg-event` handler at registration time. Dispatch an event at the machine's id and the table decides the transition; the resulting `:db` and `:fx` flow through the normal cascade.

The point of the machine surface isn't novelty — Statecharts have been around since 1987 — it's that the same trace bus, time-travel, and override surfaces that work for plain event handlers also work for machines, *because the machine is an event handler*. There's no parallel runtime to debug, no second store to inspect, no separate event log. Xray shows the machine state alongside `app-db`; the epoch buffer captures the snapshot the same way it captures everything else.

This chapter covers the registration surface (the `reg-machine` / `defmachine` macros on `re-frame.core`, plus the `re-frame.machines`-owned `reg-machine*` / `make-machine-handler` / `machine-transition`), the inspection / subscription surface (the `[:rf/machine machine-id]` subscription vector, the `re-frame.core` facade `machine-has-tag?`, and the `re-frame.machines`-owned `machines` / `machine-meta` / `machine-by-system-id`), the in-machine dispatch sugar (`:raise`), the actor-lifecycle fx (`:rf.machine/spawn`, `:rf.machine/destroy`, `:rf.machine/dispatch-to-system`), and the JVM machine-tooling exports (`machine-algebra-view`, `machine-instance-algebra-view`).

> **Facade surface.** Only `reg-machine` / `defmachine` and the `machine-has-tag?` subscription sugar are `re-frame.core` facade exports. The plain-fn registration / engine / query helpers (`reg-machine*`, `make-machine-handler`, `machine-transition`, `machines`, `machine-meta`, `machine-by-system-id`) and the `dispatch-to-system` fn live in their owned namespace `re-frame.machines` — reach them as `re-frame.machines/<name>`, not `rf/<name>`. The canonical action-side cross-machine messaging surface is the reserved `[:rf.machine/dispatch-to-system [system-id event]]` fx tuple. `re-frame.machines` is the `day8/re-frame2-machines` artefact.

For the full treatment — the underlying model, the recognition kit, and the rationale behind the capability subset — see the [machines concept guide](concepts.md).

This chapter spans `re-frame.core` (the `reg-machine` / `defmachine` macros) and `re-frame.machines` (the engine, query, and transition surfaces):

```clojure
(:require [re-frame.core     :as rf]
          [re-frame.machines :as machines])
```

## Registration

### `reg-machine`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-machine machine-id machine-spec)
  ```
- **Description**: The canonical macro. Walks the literal spec form at expansion time and co-locates per-element source on each `:guards` / `:actions` entry (`{:fn .. :source-coords .. :source-code ..}`) plus a reference-site `:source-coords` on each `:states`-tree map node (state-node / transition map) — Xray uses these to navigate from a snapshot back to the guard/action definition or the state-node. Top-level call-site coords land on `handler-meta`.
- **In the wild**: [state_machine_walkthrough](https://github.com/day8/re-frame2/tree/main/examples/capabilities/machines/state_machine_walkthrough) · [websocket](https://github.com/day8/re-frame2/tree/main/examples/patterns/websocket)

### `re-frame.machines/reg-machine*`

- **Kind**: function (owned by `re-frame.machines` — not a `re-frame.core` facade export)
- **Signature**:
  ```clojure
  (re-frame.machines/reg-machine* machine-id machine-spec)
  ```
- **Description**: Plain-fn surface beneath the macro. No source-coord walking. Use for code-gen pipelines, REPL workflows, or conformance harnesses that synthesise specs from data.
- **Example**:
  ```clojure
  ;; Same effect as the macro, minus the source-coord walking.
  (machines/reg-machine* :traffic-light
    {:initial :red
     :states  {:red   {:on {:go {:target :green}}}
               :green {:on {:go {:target :red}}}}})
  ```

### `re-frame.machines/make-machine-handler`

- **Kind**: function (owned by `re-frame.machines` — not a `re-frame.core` facade export)
- **Signature**:
  ```clojure
  (re-frame.machines/make-machine-handler spec) → event-handler fn
  ```
- **Description**: Compiles a transition table into the event-handler fn that `reg-machine` would register. Useful when you want to inspect the compiled fn or compose it manually.
- **Example**:
  ```clojure
  ;; Build the handler fn without registering it (e.g. to inspect or compose it).
  (def handler
    (machines/make-machine-handler
      {:initial :idle
       :states  {:idle    {:on {:start {:target :running}}}
                 :running {}}}))
  ```

### `re-frame.machines/machine-transition`

- **Kind**: function (owned by `re-frame.machines` — not a `re-frame.core` facade export)
- **Signature**:
  ```clojure
  (re-frame.machines/machine-transition definition snapshot event) → [next-snapshot effects]
  ```
- **Description**: The pure transition fn. Given a machine definition, a current snapshot, and an event, returns the next snapshot and the effect map. JVM-runnable; the conformance harness uses this as its primary test surface for machine behaviour.
- **Worked example** — drive a transition and assert on the snapshot (JVM-runnable, no live frame needed):
  ```clojure
  (let [definition          (machines/machine-meta :session)
        snapshot            {:state :anonymous :data {}}
        [next-snap effects] (machines/machine-transition definition snapshot [:login {:user "alice"}])]
    (is (= :authenticating (:state next-snap)))
    (is (= "alice" (get-in next-snap [:data :credentials :user])))
    (is (= :rf.http/managed (ffirst (:fx effects)))))
  ```

### A minimal machine

```clojure
(rf/reg-machine :session
  {:initial :anonymous
   :data    {:credentials nil}

   :actions
   {:capture-credentials
    ;; Remember who's signing in so the snapshot carries it through the flow.
    (fn [{[_ creds] :event}]
      {:data {:credentials creds}})

    :issue-auth
    ;; Fire the login request; the reply loops back as :auth-ok / :auth-fail.
    (fn [{[_ creds] :event}]
      {:fx [[:rf.http/managed
             {:request    {:method :post :url "/api/login" :body creds
                           :request-content-type :json :sensitive? true}
              :decode     :json
              :on-success [:session [:auth-ok]]
              :on-failure [:session [:auth-fail]]}]]})}

   :states
   {:anonymous      {:on {:login {:target :authenticating
                                  :action :capture-credentials}}}
    :authenticating {:entry :issue-auth
                     :after {500 {:target :timeout}}      ;; ms — auth taking too long
                     :on    {:auth-ok   {:target :authenticated}
                             :auth-fail {:target :anonymous}}}
    :authenticated  {:on {:logout {:target :anonymous}}}
    :timeout        {:on {:retry  {:target :anonymous}}}}})

;; The machine IS an event handler — dispatch a wrapped event at its id.
(rf/dispatch [:session [:login {:user "alice" :pass "correct-horse"}]])
```

The snapshot lives at `[:rf.runtime/machines :snapshots :session]` in the frame's **runtime-db** partition (not app-db). The shape is `{:state :anonymous :data {...}}` (plus framework-managed slots for `:after` timer epochs and tags). Read it via the `[:rf/machine machine-id]` subscription vector, or directly with `subscribe-once`.

## Inspection and subscription

### Reading a machine's snapshot — `[:rf/machine machine-id]`

The canonical machine read is the framework-registered subscription vector `[:rf/machine machine-id]` — subscribe to it the same way you subscribe to anything else. It returns a reaction whose value is the snapshot `{:state :data}` (plus framework-managed `:tags`), or `nil` if the machine is not yet initialised.

- **Example**:
  ```clojure
  (let [{:keys [state data]} @(rf/subscribe [:rf/machine :auth.login/flow])]
    [:div "State: " (name state)])
  ```
- **In the wild**: [state_machine_walkthrough](https://github.com/day8/re-frame2/tree/main/examples/capabilities/machines/state_machine_walkthrough)

### `re-frame.machines/machines`

- **Kind**: function (owned by `re-frame.machines` — not a `re-frame.core` facade export)
- **Signature**:
  ```clojure
  (re-frame.machines/machines) → seq of machine-ids
  ```
- **Description**: "What machines have been registered?" Derived view over `(registrations :event)` filtered by `:rf/machine? true`.
- **Example**:
  ```clojure
  ;; Every registered machine-id (the registry, not a frame's live snapshots).
  (machines/machines)                              ;; → (:session :auth.login/flow …)
  (contains? (set (machines/machines)) :session)
  ```

### `re-frame.machines/machine-meta`

- **Kind**: function (owned by `re-frame.machines` — not a `re-frame.core` facade export)
- **Signature**:
  ```clojure
  (re-frame.machines/machine-meta machine-id) → registration-metadata map
  ```
- **Description**: "What did `reg-machine` stamp at this machine's id?" Returns the transition table, doc, schemas, and the per-element source-coords. Equivalent to `(handler-meta :event machine-id)`.
- **Example**:
  ```clojure
  ;; The registered spec back out — table, doc, schemas, source-coords.
  (machines/machine-meta :session)
  ;; …or read just the declared :data schema:
  (get-in (machines/machine-meta :session) [:schemas :data])
  ```

### `re-frame.machines/machine-by-system-id`

- **Kind**: function (owned by `re-frame.machines` — not a `re-frame.core` facade export)
- **Signature**:
  ```clojure
  (re-frame.machines/machine-by-system-id system-id)
  (re-frame.machines/machine-by-system-id system-id frame-id)
  ```
- **Description**: Reverse-lookup: given a `system-id`, what's the spawned machine bound to it? Returns the spawned-machine id or `nil`.
- **Example**:
  ```clojure
  ;; Resolve a :system-id-bound actor, then address it directly.
  (when-let [actor (machines/machine-by-system-id :notifier)]
    (rf/dispatch [actor [:notify "hello"]]))
  ```

### `machine-has-tag?`

- **Kind**: function
- **Signature**:
  ```clojure
  (machine-has-tag? machine-id tag) → reaction
  ```
- **Description**: Sugar over `(subscribe [:rf/machine-has-tag? machine-id tag])`. Reactive predicate over the machine's snapshot's `:tags` set. Use in views to render conditionally on state-tag membership.
- **Example**:
  ```clojure
  ;; Ask by state-tag, not exact state name — disable inputs while a request is in flight.
  (let [busy? @(rf/machine-has-tag? :auth.login/flow :auth/busy)]
    [:button {:disabled busy?} "Sign in"])
  ```

### Standard registered subs (machines)

| Sub | Returns |
|---|---|
| `[:rf/machine <machine-id>]` | The machine's snapshot `{:state :data}` (or `nil` if not yet initialised) |

This subscription vector is the canonical machine read — the [machines concept guide](concepts.md#registering-and-running-it) walks through subscribing to a snapshot and chaining named projections off it.

## Cross-machine messaging

### `[:rf.machine/dispatch-to-system [system-id event]]` — the canonical surface

The action-side way one machine addresses its spawned child actor by *role* (`:logger`, `:websocket`, `:retry-coordinator`) instead of by gensym'd id is the reserved fx tuple — emit it from a machine action's (or any event handler's) `:fx` vector:

```clojure
{:fx [[:rf.machine/dispatch-to-system [:logger [:logger/flush]]]]}
```

It resolves `system-id` through the emitting frame's `[:rf.runtime/machines :system-ids]` reverse index (in runtime-db) and dispatches `event` to the bound actor; no-op when the `system-id` is unbound. This is the surface to reach for — a **machine action** can't read app-db and its `:on-spawn` return is dropped, so the fx form is the canonical way an action sends a message to a named actor. See [The actor-lifecycle fx](#the-actor-lifecycle-fx) below and [spawn](glossary.md#spawn) in the machines glossary.

When a child actor spawns under a parent, the parent's `:data` often gets the child's id stamped via `:on-spawn`; naming by `system-id` lets the parent address the child by role without threading that id.

### `re-frame.machines/dispatch-to-system` (implementation-tier fn)

- **Kind**: function (owned by `re-frame.machines`, implementation tier — **not a `re-frame.core` facade export**)
- **Signature**:
  ```clojure
  (re-frame.machines/dispatch-to-system system-id event)
  (re-frame.machines/dispatch-to-system system-id event frame-id)
  ```
- **Description**: The direct-call twin of the fx above — sugar over `(when-let [m (machine-by-system-id system-id)] (dispatch [m event]))`, no-op when the `system-id` is unbound. The two-arity form resolves the frame from the carried scope it runs under; the three-arity form names the frame explicitly — there is no `:rf/default` fallback, and looking up under no scope raises `:rf.error/no-frame-context`. This is an implementation-tier helper; new app code should emit the `[:rf.machine/dispatch-to-system [system-id event]]` fx instead.
- **Example**:
  ```clojure
  ;; Implementation-tier direct call (prefer the fx in app code); no-op when unbound.
  (machines/dispatch-to-system :logger [:logger/flush])
  ```

## The actor-lifecycle fx

| `[fx-id args]` | Args | Status | Intuition |
|---|---|---|---|
| `[:rf.machine/spawn spawn-spec]` | spawn-spec map (per `:rf.fx/spawn-args`) | v1 | "Spawn a dynamic actor instance." Args carry `:machine-id` (the definition to instantiate), `:id-prefix`, `:data` (initial), `:on-spawn` (event dispatched with the gensym'd id), and `:start` (events to deliver immediately). Emitted from any event handler's `:fx` (including machine actions and the `:spawn` desugar). |
| `[:rf.machine/destroy actor-id]` | actor id (keyword) | v1 | "Tear down this actor." Runs the actor's `:exit` action, dissociates `[:rf.runtime/machines :snapshots <actor-id>]` (in runtime-db), and clears the actor's event-handler registration. Symmetric counterpart to `:rf.machine/spawn`. |
| `[:rf.machine/dispatch-to-system [system-id event]]` | 2-element pair `[system-id event-vector]` | v1 | "Message my spawned child actor by name." Resolves `system-id` through the emitting frame's `[:rf.runtime/machines :system-ids]` reverse index (in runtime-db) and dispatches `event` to the bound actor; no-op when unbound. The action-side counterpart to the `dispatch-to-system` fn. Args ride as a single 2-element pair (the fx contract is a `[fx-id args]` pair). |
| `[:raise event-vec]` | event vector | v1 | **Machine-only.** Inside a machine action's `:fx`, routes the event back into the same machine atomically and pre-commit. Unbound outside machine actions. |

**`:raise`** — inside a machine action, route an event back into the same machine (atomic, pre-commit):

```clojure
{:actions {:kick (fn [_] {:fx [[:raise [:tick]]]})}}
```

### Spawn pattern

```clojure
(rf/reg-event :session/start-logger
  (fn [_ _]
    {:fx [[:rf.machine/spawn
           {:machine-id :machines/log-shipper
            :id-prefix  :logger
            :data       {:buffer []}
            :on-spawn   [:session/logger-spawned]
            :start      [[:logger/connect]]}]]}))

;; The handler at :session/logger-spawned receives the gensym'd id:
;; [:session/logger-spawned :logger.4f7c2a]
(rf/reg-event :session/logger-spawned
  (fn [{:keys [db]} [_ logger-id]]
    {:db (assoc-in db [:session :logger] logger-id)}))
```

Tear the actor down with the symmetric `:rf.machine/destroy` — it runs the actor's `:exit`, clears its snapshot, and drops its event-handler registration:

```clojure
(rf/reg-event :session/stop-logger
  (fn [{:keys [db]} _]
    {:fx [[:rf.machine/destroy (get-in db [:session :logger])]]}))
```

## Final states and `:on-done`

Machines support **final states** — leaf states marked `:final?` that auto-destroy the machine on entry. The parent (if any) receives `:on-done` with the child's `:data` slot.

| State-node key | What it does |
|---|---|
| `:final?` | Marks a leaf state as terminal. Entering it auto-destroys the machine. Capability axis `:fsm/final-states`. |
| `:output-key` | Requires `:final?`. Designates the child's `:data` slot reported back via the parent's `:on-done`. |
| `:on-done` (spawn-spec key) | `(fn [{:keys [data result]}] new-data)` on the parent's `:spawn` map. Fires synchronously when the spawned child enters a `:final?` state. `result` is the child's `:data` slot named by the final state's `:output-key` (or `nil`). |

The pattern: a spawn-shaped sub-process completes, the parent receives the result through `:on-done`, the framework destroys the child. No manual `:rf.machine/destroy` needed.

See [Final states: when a machine is done](concepts.md#final-states-when-a-machine-is-done) in the machines concept guide.

## Machine-tooling exports (JVM)

The shipped machine-tooling exports live in `re-frame.machines` and are JVM-only (Xray + conformance consume them directly; no `re-frame.core` facade export). They render the machine *algebra view* that Xray / re-frame-pair navigate — there is **no** framework-level `machine->xstate-json`, `machine->mermaid`, or Stately bridge (those are owned by the separate `day8/re-frame2-machines-viz` library).

### `re-frame.machines/machine-algebra-view`

- **Kind**: function (owned by `re-frame.machines`, JVM-only — not a `re-frame.core` facade export)
- **Signature**:
  ```clojure
  (re-frame.machines/machine-algebra-view) → {machine-id node}
  (re-frame.machines/machine-algebra-view machine-id) → node (or nil)
  ```
- **Description**: The static algebra view over a machine *definition* — the structure Xray's machine inspector and the docs/visualisation lane read. JVM-only. The zero-arity form returns `{machine-id node}` for every registered machine (`{}` when none); the one-arity form returns the single node for `machine-id`, or `nil` if it is not registered.
- **Example**:
  ```clojure
  ;; The whole registry, keyed by machine-id — or one node (nil if unregistered).
  (machines/machine-algebra-view)
  (machines/machine-algebra-view :upload/main)
  ```

### `re-frame.machines/machine-instance-algebra-view`

- **Kind**: function (owned by `re-frame.machines`, JVM-only — not a `re-frame.core` facade export)
- **Signature**:
  ```clojure
  (re-frame.machines/machine-instance-algebra-view) → {actor-id node}
  (re-frame.machines/machine-instance-algebra-view frame-id) → {actor-id node}
  ```
- **Description**: The algebra view for a live machine *instance* — definition plus current snapshot, so the view can highlight the active state. JVM-only. The zero-arity form reads the ambient current frame; the one-arity form names a `frame-id`. Returns `{actor-id node}` (one node per live snapshot — singletons and spawned actors), `{}` for a frame with no live machines, and `nil` for a missing/destroyed frame or in production builds.
- **Example**:
  ```clojure
  ;; Live nodes — one per machine snapshot in the frame (singletons + spawned actors).
  (machines/machine-instance-algebra-view :rf/default)
  ```

### Post-v1 / future surfaces (not shipped)

The following are **not** part of the shipped v1 surface — they are forward-pointers, owned by the post-v1 `day8/re-frame2-machines` and `day8/re-frame2-machines-viz` libraries:

- `machine->mermaid` / `machine->xstate-json` — diagram/JSON exporters, owned by `day8/re-frame2-machines-viz` (Machines-Viz), not the framework.
- `:child-machine` — a possible future declarative state-scoped child-machine binding (desugaring to entry/exit `:rf.machine/spawn` / `:rf.machine/destroy`). Pure sugar over the v1 surface; not yet shipped. Use the imperative `:spawn` / `:destroy` cycle today.

The v1 foundation covers the machine-as-event-handler primitive (`reg-machine` on `re-frame.core`, the engine in `day8/re-frame2-machines`); the post-v1 libraries layer the higher-level features and visualisers on top.

## Capability matrix

The v1 transition-table grammar covers a specific subset of Statechart capabilities — sequencing, `:after` timers, internal-vs-external transitions, guards, action lists, hierarchical states, parallel regions where the framework's epoch model can tolerate them, and final-state semantics. The exact subset and its rationale are covered in the [machines concept guide](concepts.md); the transition-table shape itself is shown in [The same flow as a transition table](concepts.md#the-same-flow-as-a-transition-table).

## See also

- [01 — Core](../core/api/01-core.md) — `reg-machine` rowed in registration.
- [08 — Schemas](../core/api/08-schemas.md) — machines declare schemas for their `:data` slot the same way ordinary handlers do.
- [11 — Instrumentation](../core/api/11-instrumentation.md) — machine snapshots are part of the epoch buffer; transitions emit trace events.
- [Machines concept guide](concepts.md) — the narrative walkthrough: the transition table, guards, actions, tags, `:after`, final states, and when to reach for a machine.
- [Machines glossary](glossary.md) — the surface vocabulary in one place.
- [Coming from XState](coming-from-xstate.md) — the v6 parity delta for XState users.
