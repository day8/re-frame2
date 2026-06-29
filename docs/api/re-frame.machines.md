# re-frame.machines

State machines, per Spec 005. A machine in re-frame2 is registered with one macro (`reg-machine`) and *is* an event handler — the transition table is data (a map of `:states`, `:on`, `:entry`, `:exit`, `:after`) compiled into a `reg-event` handler at registration time. Dispatch an event at the machine's id and the table decides the transition; the resulting `:db` and `:fx` flow through the normal cascade. Because the machine is an event handler, the same trace bus, time-travel, and override surfaces that work for plain handlers work for machines — there is no parallel runtime to debug, no second store to inspect, no separate event log. The registration macros (`reg-machine` / `defmachine`) and the `machine-has-tag?` subscription sugar live on the `re-frame.core` facade; the engine, query, transition, and tooling surfaces live in this namespace, `re-frame.machines`, which is the `day8/re-frame2-machines` optional artefact.

```clojure
(:require [re-frame.core     :as rf]        ;; reg-machine / defmachine / machine-has-tag? — the facade macros + sub sugar
          [re-frame.machines :as machines]) ;; engine, query, transition, tooling, runtime helpers
```

> **Facade surface.** Only `reg-machine` / `defmachine` and the `machine-has-tag?` subscription sugar are `re-frame.core` facade exports — reach them as `rf/…`. The plain-fn registration / engine / query helpers (`reg-machine*`, `make-machine-handler`, `machine-transition`, `machines`, `machine-meta`, `machine-by-system-id`) and the implementation-tier runtime helpers live in their owned namespace `re-frame.machines` — reach them as `re-frame.machines/<name>`, not `rf/<name>`. The canonical action-side cross-machine messaging surface is the reserved `[:rf.machine/dispatch-to-system [system-id event]]` fx tuple.

For the full treatment — the underlying model, the recognition kit, and the rationale behind the capability subset — see the [machines concept guide](../machines/concepts.md).

## Registration

### `reg-machine`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-machine machine-id machine-spec)
  ```
- **Description**: The canonical macro. Walks the literal spec form at expansion time and co-locates per-element source on each `:guards` / `:actions` entry (`{:fn .. :source-coords .. :source-code ..}`) plus a reference-site `:source-coords` on each `:states`-tree map node (state-node / transition map) — Xray uses these to navigate from a snapshot back to the guard/action definition or the state-node. Top-level call-site coords land on `handler-meta`. Reached on the `re-frame.core` facade as `rf/reg-machine`.

A minimal machine:

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

The snapshot lives at `[:rf.runtime/machines :snapshots :session]` in the frame's **runtime-db** partition (not app-db). The shape is `{:state :anonymous :data {...}}` (plus framework-managed slots for `:after` timer epochs and tags). Read it via the [`[:rf/machine machine-id]`](#rfmachine-machine-id) subscription vector, or directly with `subscribe-once`.

### `defmachine`

- **Kind**: macro
- **Signature**:
  ```clojure
  (defmachine name machine-spec)
  (defmachine name docstring machine-spec)
  ```
- **Description**: Define a machine-spec *value* with per-element source captured — a drop-in for `def` whose body is a literal machine-spec map. Walks the literal spec at expansion time and co-locates per-element source (`{:fn .. :source-coords .. :source-code ..}`) onto each `:guards` / `:actions` / `:on-spawn-actions` entry, plus a reference-site `:source-coords` onto each `:states`-tree map node. When that value is later passed to `reg-machine`, the source is already present on the stamped spec, so `(rf/handler-meta :machine-guard [machine-id guard-id])` (and the Xray machine-cascade source rendering) light up for value-registered machines exactly as for inline ones. The common app shape is `(def door-machine {…}) … (reg-machine :door/main door-machine)` — there `reg-machine` sees only the `door-machine` symbol and its compile-time literal-walk captures nothing, so `defmachine` is the `def`-replacement that captures the source at the definition site. The dev-only `:source-*` slots DCE under `:advanced + goog.DEBUG=false`. Reached on the `re-frame.core` facade as `rf/defmachine`.
- **Example**:
  ```clojure
  ;; Capture per-element source at the def site, then register the value.
  (rf/defmachine door-machine
    "A door that locks."
    {:initial :locked
     :states  {:locked {:on {:unlock {:target :closed}}}
               :closed {:on {:open {:target :open}
                             :lock {:target :locked}}}
               :open   {:on {:close {:target :closed}}}}})

  (rf/reg-machine :door/main door-machine)
  ```

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

## Inspection and queries

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
- **Description**: Sugar over `(subscribe [:rf/machine-has-tag? machine-id tag])`. Reactive predicate over the machine's snapshot's `:tags` set. Use in views to render conditionally on state-tag membership. Reached on the `re-frame.core` facade as `rf/machine-has-tag?`.
- **Example**:
  ```clojure
  ;; Ask by state-tag, not exact state name — disable inputs while a request is in flight.
  (let [busy? @(rf/machine-has-tag? :auth.login/flow :auth/busy)]
    [:button {:disabled busy?} "Sign in"])
  ```

## Keyword surfaces

The framework-registered subscription vectors and reserved effect tuples that address machines by keyword. These are unioned into every resolved image generation (a `:select-ns` image cannot reach them by namespace), so an image-loaded frame resolves them the same way a default frame does.

### `[:rf/machine machine-id]`

- **Kind**: subscription (framework-registered)
- **Signature**:
  ```clojure
  [:rf/machine machine-id]
  ```
- **Description**: The canonical machine read — subscribe to it the same way you subscribe to anything else. Returns a reaction whose value is the snapshot `{:state :data}` (plus framework-managed `:tags`), or `nil` if the machine is not yet initialised. The [machines concept guide](../machines/concepts.md#registering-and-running-it) walks through subscribing to a snapshot and chaining named projections off it.
- **Example**:
  ```clojure
  (let [{:keys [state data]} @(rf/subscribe [:rf/machine :auth.login/flow])]
    [:div "State: " (name state)])
  ```

### `[:rf/machine-has-tag? machine-id tag]`

- **Kind**: subscription (framework-registered)
- **Signature**:
  ```clojure
  [:rf/machine-has-tag? machine-id tag]
  ```
- **Description**: Returns `true` iff the named machine's current snapshot's `:tags` set contains `tag`, `false` otherwise (including unknown / not-yet-initialised machines). A *derived* sub — it reads the snapshot's containment-bit directly rather than chaining off `:rf/machine`, so a view that only cares about one tag re-renders only when that bit flips. The `re-frame.core` `machine-has-tag?` sugar fn wraps this vector.
- **Example**:
  ```clojure
  @(rf/subscribe [:rf/machine-has-tag? :auth.login/flow :auth/busy])  ;; => true / false
  ```

### `[:rf.machine/spawn spawn-spec]`

- **Kind**: effect (reserved fx-id)
- **Signature**:
  ```clojure
  [:rf.machine/spawn spawn-spec]
  ```
- **Description**: "Spawn a dynamic actor instance." `spawn-spec` carries `:machine-id` (the definition to instantiate), `:id-prefix`, `:data` (initial), `:on-spawn` (event dispatched with the gensym'd id), and `:start` (events to deliver immediately). Emitted from any event handler's `:fx` (including machine actions and the declarative `:spawn` desugar). Backed by [`spawn-fx`](#re-framemachinesspawn-fx).
- **Example**:
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

### `[:rf.machine/destroy actor-id]`

- **Kind**: effect (reserved fx-id)
- **Signature**:
  ```clojure
  [:rf.machine/destroy actor-id]
  ```
- **Description**: "Tear down this actor." Runs the actor's `:exit` action, dissociates `[:rf.runtime/machines :snapshots <actor-id>]` (in runtime-db), and clears the actor's event-handler registration. Symmetric counterpart to `:rf.machine/spawn`. Backed by [`destroy-machine-fx`](#re-framemachinesdestroy-machine-fx).
- **Example**:
  ```clojure
  (rf/reg-event :session/stop-logger
    (fn [{:keys [db]} _]
      {:fx [[:rf.machine/destroy (get-in db [:session :logger])]]}))
  ```

**Final states and `:on-done`.** Machines support **final states** — leaf states marked `:final?` that auto-destroy the machine on entry. The parent (if any) receives `:on-done` with the child's `:data` slot. No manual `:rf.machine/destroy` is needed: a spawn-shaped sub-process completes, the parent receives the result through `:on-done`, and the framework destroys the child.

| State-node key | What it does |
|---|---|
| `:final?` | Marks a leaf state as terminal. Entering it auto-destroys the machine. Capability axis `:fsm/final-states`. |
| `:output-key` | Requires `:final?`. Designates the child's `:data` slot reported back via the parent's `:on-done`. |
| `:on-done` (spawn-spec key) | `(fn [{:keys [data result]}] new-data)` on the parent's `:spawn` map. Fires synchronously when the spawned child enters a `:final?` state. `result` is the child's `:data` slot named by the final state's `:output-key` (or `nil`). |

See [Final states: when a machine is done](../machines/concepts.md#final-states-when-a-machine-is-done) in the machines concept guide.

### `[:rf.machine/dispatch-to-system [system-id event]]`

- **Kind**: effect (reserved fx-id)
- **Signature**:
  ```clojure
  [:rf.machine/dispatch-to-system [system-id event]]
  ```
- **Description**: The action-side way one machine addresses its spawned child actor by *role* (`:logger`, `:websocket`, `:retry-coordinator`) instead of by gensym'd id. Resolves `system-id` through the emitting frame's `[:rf.runtime/machines :system-ids]` reverse index (in runtime-db) and dispatches `event` to the bound actor; no-op when the `system-id` is unbound. This is the canonical surface — a **machine action** can't read app-db and its `:on-spawn` return is dropped, so the fx form is the way an action sends a message to a named actor. Args ride as a single 2-element pair (the fx contract is a `[fx-id args]` pair). Backed by [`dispatch-to-system-fx`](#re-framemachinesdispatch-to-system-fx); the call-site twin is the [`dispatch-to-system`](#re-framemachinesdispatch-to-system) fn.
- **Example**:
  ```clojure
  {:fx [[:rf.machine/dispatch-to-system [:logger [:logger/flush]]]]}
  ```

### `[:rf.machine/update-snapshot patch]`

- **Kind**: effect (reserved fx-id)
- **Signature**:
  ```clojure
  [:rf.machine/update-snapshot {:rf/machine-id <id> :rf/patch {:data {...}}}]
  ```
- **Description**: Snapshot-level escape hatch. Emit from a callback's (or any event handler's) `:fx` vector to touch a machine's `:state` / `:meta` / `:data` atomically. The `:data` patch is gated by [`validate-update-snapshot-data!`](#re-framemachinesvalidate-update-snapshot-data) against the actor's `[:schemas :data]` schema *before* the fx writes it, so the escape hatch is not exempt from the `:where :machine-data` boundary. Per Spec 005 §Snapshot-level escape hatch.
- **Example**:
  ```clojure
  {:fx [[:rf.machine/update-snapshot {:rf/machine-id :session
                                      :rf/patch      {:data {:retries 0}}}]]}
  ```

### `[:raise event-vec]`

- **Kind**: effect (reserved fx-id, machine-only)
- **Signature**:
  ```clojure
  [:raise event-vec]
  ```
- **Description**: **Machine-only.** Inside a machine action's `:fx`, routes the event back into the same machine atomically and pre-commit. Unbound outside machine actions.
- **Example**:
  ```clojure
  {:actions {:kick (fn [_] {:fx [[:raise [:tick]]]})}}
  ```

## Cross-machine messaging

When a child actor spawns under a parent, the parent's `:data` often gets the child's id stamped via `:on-spawn`; naming by `system-id` lets the parent address the child by *role* without threading that id. The canonical action-side surface is the [`[:rf.machine/dispatch-to-system [system-id event]]`](#rfmachinedispatch-to-system-system-id-event) fx tuple (above); the helpers below are the direct-call twins.

### `re-frame.machines/dispatch-to-system`

- **Kind**: function (owned by `re-frame.machines`, implementation tier — **not a `re-frame.core` facade export**)
- **Signature**:
  ```clojure
  (re-frame.machines/dispatch-to-system system-id event)
  (re-frame.machines/dispatch-to-system system-id event frame-id)
  ```
- **Description**: The direct-call twin of the `:rf.machine/dispatch-to-system` fx — sugar over `(when-let [m (machine-by-system-id system-id)] (dispatch [m event]))`, no-op when the `system-id` is unbound. The two-arity form resolves the frame from the carried scope it runs under; the three-arity form names the frame explicitly — there is no `:rf/default` fallback, and looking up under no scope raises `:rf.error/no-frame-context`. This is an implementation-tier helper; new app code should emit the `[:rf.machine/dispatch-to-system [system-id event]]` fx instead.
- **Example**:
  ```clojure
  ;; Implementation-tier direct call (prefer the fx in app code); no-op when unbound.
  (machines/dispatch-to-system :logger [:logger/flush])
  ```

### `re-frame.machines/dispatch-to-system-fx`

- **Kind**: function (owned by `re-frame.machines`, implementation tier — the fx handler for `:rf.machine/dispatch-to-system`)
- **Signature**:
  ```clojure
  (re-frame.machines/dispatch-to-system-fx fx-ctx [system-id event])
  ```
- **Description**: The fx handler behind `:rf.machine/dispatch-to-system`. Resolves `system-id` through the emitting frame's `[:rf.runtime/machines :system-ids]` reverse index and dispatches `event` to the bound actor; no-op when the `system-id` is unbound (symmetric with the `dispatch-to-system` fn's no-op fall-through). The cascade-envelope frame is the fx-context `:frame`; a nil stamp is an invariant failure (`:rf.error/no-frame-context`), never a synthesised `:rf/default`. App code emits the `[:rf.machine/dispatch-to-system [system-id event]]` fx rather than calling this fn.

## Machine-tooling exports (JVM)

The shipped machine-tooling exports live in `re-frame.machines` and are JVM-only (Xray + conformance consume them directly; no `re-frame.core` facade export). They render the machine *algebra view* that Xray / re-frame-pair navigate. There is **no** framework-level `machine->xstate-json`, `machine->mermaid`, or Stately bridge — those exporters are owned by the separate post-v1 `day8/re-frame2-machines-viz` library, not the framework. (A possible future declarative `:child-machine` binding would be pure sugar over the v1 `:spawn` / `:destroy` cycle; not yet shipped — use the imperative cycle today.)

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

### `re-frame.machines/machine-selector?`

- **Kind**: function (owned by `re-frame.machines`, JVM-only — not a `re-frame.core` facade export)
- **Signature**:
  ```clojure
  (re-frame.machines/machine-selector? sub-id) → boolean
  ```
- **Description**: True iff the subscription registered under `sub-id` is a MACHINE SELECTOR — an ordinary `reg-sub` whose static `:<-` inputs include a `[:rf/machine …]` (or `[:rf/machine-has-tag? …]`) query vector. Machine selectors are not a second subscription system — they stay ordinary ephemeral `:derivation` subscription nodes; this recognizer lets a graph tool flag the ones that read a machine. JVM-only.
- **Example**:
  ```clojure
  (machines/machine-selector? :session/summary)  ;; => true / false
  ```

### `re-frame.machines/machine-selector-targets`

- **Kind**: function (owned by `re-frame.machines`, JVM-only — not a `re-frame.core` facade export)
- **Signature**:
  ```clojure
  (re-frame.machines/machine-selector-targets sub-id) → #{machine-id …}
  ```
- **Description**: The SET of machine ids the subscription registered under `sub-id` reads as a machine selector — the second element of each accepted `[:rf/machine machine-id …]` / `[:rf/machine-has-tag? machine-id …]` static `:<-` input. Where `machine-selector?` answers only the boolean "is this a selector?", this returns the actual target machine ids — what a graph tool needs to draw the edge. JVM-only.
- **Example**:
  ```clojure
  (machines/machine-selector-targets :session/summary)  ;; => #{:session}
  ```

## Implementation-tier effect handlers

The fx handlers behind the reserved `:rf.machine/*` effect ids. They are registered by this namespace via `reg-fx` so an app that doesn't pull in `day8/re-frame2-machines` carries neither the trace strings nor the handler symbols on its production-elision bundle. **App code emits the effect tuple (Keyword surfaces, above) — it does not call these fns directly.** Each takes the standard `(handler fx-ctx args)` shape; the cascade-envelope frame is the fx-context `:frame`.

### `re-frame.machines/spawn-fx`

- **Kind**: function (owned by `re-frame.machines`, implementation tier — the fx handler for `:rf.machine/spawn`)
- **Signature**:
  ```clojure
  (re-frame.machines/spawn-fx fx-ctx spawn-spec)
  ```
- **Description**: Installs the spawned actor's snapshot at `[:rf.runtime/machines :snapshots <spawned-id>]` in the spawning frame's runtime-db, stamping the revertible `:rf/machine-type` TYPE reference so the lazy resolver and epoch restore can rebuild the actor; the actor's liveness IS that snapshot's presence (there is no per-instance event-handler registration). Fails closed when `:machine-id` names an unregistered machine type and the spawn carries no inline `:definition` (emits the always-on `:rf.error/machine-spawn-unregistered-type` and installs nothing).

### `re-frame.machines/spawn-all-init-fx`

- **Kind**: function (owned by `re-frame.machines`, implementation tier — the fx handler for `:rf.machine/spawn-all-init`)
- **Signature**:
  ```clojure
  (re-frame.machines/spawn-all-init-fx fx-ctx args)
  ```
- **Description**: On entry to a `:spawn-all`-bearing state the runtime emits this fx (alongside per-child `:rf.machine/spawn` fxs) to seed the join state at `[:rf.runtime/machines :spawned <parent> <invoke-id>]` — `{:children {…} :done #{} :failed #{} :resolved? false :spec …}`. Subsequent `:on-child-done` / `:on-child-error` events resolve the join. Machine-internal — not for direct application use.

### `re-frame.machines/destroy-machine-fx`

- **Kind**: function (owned by `re-frame.machines`, implementation tier — the fx handler for `:rf.machine/destroy`)
- **Signature**:
  ```clojure
  (re-frame.machines/destroy-machine-fx fx-ctx args)
  ```
- **Description**: Dispatches to the keyword-form / single-`:spawn` teardown or the `:spawn-all` children-iteration teardown per the `args` shape. Runs the actor's `:exit` cascade, clears its `[:rf.runtime/machines :snapshots <actor-id>]` slot, and drops its event-handler registration.

### `re-frame.machines/after-schedule-fx`

- **Kind**: function (owned by `re-frame.machines`, implementation tier — the fx handler for `:rf.machine/after-schedule`)
- **Signature**:
  ```clojure
  (re-frame.machines/after-schedule-fx fx-ctx args)
  ```
- **Description**: On entry to an `:after`-bearing state node the runtime emits one of these per `:after` entry. Resolves the delay (literal `pos-int?` / subscription vector / `(fn [snapshot] ms)`), schedules a real wall-clock timer via the clock abstraction, and (for subscription delays) installs an add-watch that cancels-and-reschedules on sub-value change. The synthetic expiry event is `[<parent-id> [:rf.machine.timer/after-elapsed <delay-key> <epoch> <decl-path>]]` and fires only when the scheduling node is still active and the carried epoch matches. Machine-internal — not for direct application use.

### `re-frame.machines/after-cancel-fx`

- **Kind**: function (owned by `re-frame.machines`, implementation tier — the fx handler for `:rf.machine/after-cancel`)
- **Signature**:
  ```clojure
  (re-frame.machines/after-cancel-fx fx-ctx args)
  ```
- **Description**: Cancels a previously-scheduled `:after` timer for a machine state. Machine-internal — not for direct application use.

## Validators

The registration-time and `:data`-schema-boundary validators. The three `:data` validators live inside a `(when interop/debug-enabled? …)` gate, so production builds (`goog.DEBUG=false`) skip them and return `true`.

### `re-frame.machines/validate-machine!`

- **Kind**: function (owned by `re-frame.machines`, implementation tier — the pure registration-time grammar validator)
- **Signature**:
  ```clojure
  (re-frame.machines/validate-machine! machine)
  ```
- **Description**: Runs every registration-time check the machine grammar requires — history-state placement / closed key-set / at-most-one-per-compound / `:default-target` resolution, `:type :parallel` region shape, and top-level dispatch + guard/action ref resolution. Composed at the top of `make-machine-handler` so the registered handler fn's body is exclusively request processing. Throws the `:rf.error/machine-*` taxonomy (e.g. `-history-misplaced` / `-extra-keys` / `-duplicate` / `-bad-default-target`) on a grammar violation. The conformance corpus's `:reg-machine` Mode-B op pins the registration-error taxonomy against this leaf fn.

### `re-frame.machines/validate-machine-data!`

- **Kind**: function (owned by `re-frame.machines`, implementation tier)
- **Signature**:
  ```clojure
  (re-frame.machines/validate-machine-data! runtime-db event-id frame-id) → boolean
  ```
- **Description**: Walks every snapshot under `[:rf.runtime/machines :snapshots]` in `runtime-db` and validates its `:data` against the resolved machine's `[:schemas :data]` schema. Returns `true` iff every snapshot conformed (or carried no schema / no validator); `false` on the first failure, with the per-snapshot trace already emitted — the router then rolls back the whole transition (same mechanism as the `:where :app-db` rollback). Schema resolution covers a SINGLETON (via `machine-meta`) AND a SPAWNED actor (via the snapshot's `:rf/machine-type`). The post-commit boundary the router AND-conjoins with `validate-app-schema!`.

### `re-frame.machines/validate-spawn-data!`

- **Kind**: function (owned by `re-frame.machines`, implementation tier)
- **Signature**:
  ```clojure
  (re-frame.machines/validate-spawn-data! spawned-id spec snapshot) → boolean
  ```
- **Description**: Sibling of `validate-machine-data!` for the `:rf.machine/spawn` install path. Validates a freshly-built initial snapshot's `:data` against the spawned actor's machine `[:schemas :data]` schema BEFORE the snapshot lands in runtime-db. Returns `true` on conform / no schema / no validator; `false` on failure (the caller skips the install). A spawn failure does not commit, so there is nothing to roll back (`:phase :spawn` emits with `:rollback? false`).

### `re-frame.machines/validate-update-snapshot-data!`

- **Kind**: function (owned by `re-frame.machines`, implementation tier)
- **Signature**:
  ```clojure
  (re-frame.machines/validate-update-snapshot-data! machine-id merged-snapshot) → boolean
  ```
- **Description**: Sibling validator for the `:rf.machine/update-snapshot` escape-hatch fx. Validates the would-be-merged snapshot's `:data` against the actor's resolved `[:schemas :data]` schema BEFORE the fx writes the patch into runtime-db. Returns `true` on conform / no schema / no validator (the fx proceeds with the write); `false` on failure (the fx SKIPS the write so the invalid `:data` never installs). The escape hatch is therefore not exempt from the `:where :machine-data` boundary.

## Runtime and lifecycle helpers

### `re-frame.machines/install-machine-runtime!`

- **Kind**: function (owned by `re-frame.machines`, implementation tier)
- **Signature**:
  ```clojure
  (re-frame.machines/install-machine-runtime!)
  ```
- **Description**: Re-registers the machine runtime effects + subs into BOTH the regular registrar AND the framework-standard registry (so an image-loaded frame resolves `[:rf.machine/spawn …]` / `[:rf/machine …]` through its sealed generation). Idempotent. Re-registers from descriptors captured at ns-load, so it works even after a `registrar/clear-all!` has wiped the registrar slots — the machine analogue of the `:rf/set-db` standard re-seed. Called at ns load, from the `:machines/install-runtime!` late-bind hook the reset fixture fires, and directly by tests that wipe the registrar.

### `re-frame.machines/reset-timers!`

- **Kind**: function (owned by `re-frame.machines`, implementation tier)
- **Signature**:
  ```clojure
  (re-frame.machines/reset-timers!)
  (re-frame.machines/reset-timers! frame-id)
  ```
- **Description**: Cancel in-flight `:after` timers. The 0-arity form clears every frame's timers — the fixture-teardown shape used by `re-frame.test-support`'s `reset-runtime` and per-feature artefact test fixtures. The 1-arity form clears just the given frame's timers — the `frame/destroy-frame!` hook shape that releases a destroyed frame's host-clock handles and subscription watchers without touching siblings. Spawn-id counters reset automatically with the registrar snapshot/restore + frame reset, so this hook handles only the frame-scoped wall-clock timer table.

### `re-frame.machines/owning-actor-id`

- **Kind**: function (owned by `re-frame.machines`, implementation tier)
- **Signature**:
  ```clojure
  (re-frame.machines/owning-actor-id frame-id event-id) → actor-id (or nil)
  ```
- **Description**: Resolve the spawned-actor-id that OWNS `event-id` in `frame-id`, or `nil`. Returns `event-id` (a keyword — the spawned actor's machine address) when a SPAWNED actor's snapshot is currently installed at `[:rf.runtime/machines :snapshots <event-id>]`, otherwise `nil` (the event came from an ordinary handler or a singleton machine). Set semantics are snapshot membership via the durable `:rf/machine-type`-at-root discriminator — declarative `:spawn` / `:spawn-all` AND imperative `[:rf.machine/spawn …]` actors. Published as the `:machines/owning-actor-id` late-bind hook so the http artefact can ask "who owns this request's originating event?" (to abort managed HTTP on actor-destroy) without statically requiring this artefact; http falls back to `nil` when machines is absent.

## See also

- [re-frame.core.md](re-frame.core.md) — `reg-machine` / `defmachine` / `machine-has-tag?` are reached on the `re-frame.core` facade; `dispatch` / `subscribe` / `reg-event` drive and read a machine.
- [re-frame.schemas.md](re-frame.schemas.md) — machines declare schemas for their `:data` slot the same way ordinary handlers do; the `validate-*-data!` validators gate them.
- [Machines concept guide](../machines/concepts.md) — the narrative walkthrough: the transition table, guards, actions, tags, `:after`, final states, and when to reach for a machine. The v1 transition-table grammar covers a specific subset of Statechart capabilities — sequencing, `:after` timers, internal-vs-external transitions, guards, action lists, hierarchical states, parallel regions, and final-state semantics — the exact subset and its rationale are covered there.
- [Machines glossary](../machines/glossary.md) — the surface vocabulary in one place.
- [Coming from XState](../machines/coming-from-xstate.md) — the v6 parity delta for XState users.
