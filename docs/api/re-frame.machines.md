# re-frame.machines

State machines, per Spec 005. You register a machine with one macro (`reg-machine`), and the machine *is* an event handler. Its transition table — a map of `:states`, `:on`, `:entry`, `:exit`, `:after` — compiles into a `reg-event` handler at registration time. Dispatch an event at the machine's id, and the table decides the transition. The resulting `:db` and `:fx` flow through the normal cascade. The same trace bus, time-travel, and override surfaces that work for plain handlers also work for machines.

```clojure
(:require [re-frame.core     :as rf]        ;; reg-machine / defmachine — the facade registration macros
          [re-frame.machines :as machines]) ;; engine, query, transition, tooling, runtime helpers
```

Read a machine's snapshot with the ordinary `subscribe`, naming its framework sub vector. Use `@(rf/subscribe [:rf/machine machine-id])` for the snapshot, and `@(rf/subscribe [:rf.machine/has-tag? machine-id tag])` for a `:tags`-membership predicate. There is no named-read-sugar fn: every runtime-db framework read is a subscription vector, one grammar.

Surfaces split two ways:

- **`re-frame.core` facade exports** (reach as `rf/…`): the `reg-machine` / `defmachine` registration macros.
- **Owned by `re-frame.machines`** (reach as `re-frame.machines/<name>`, not `rf/<name>`): the plain-fn registration / engine / query helpers (`reg-machine*`, `make-machine-handler`, `machine-transition`, `machines`, `machine-meta`, `machine-by-system-id`) and the implementation-tier runtime helpers. This namespace is the `day8/re-frame2-machines` optional artefact.

The canonical action-side cross-machine messaging surface is the reserved `[:rf.machine/dispatch-to-system [system-id event]]` fx tuple.

For the full treatment — the underlying model, the recognition kit, and the rationale behind the capability subset — see the [machines concept guide](../machines/concepts.md).

## Registration

### `reg-machine`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-machine machine-id machine-spec)
  (reg-machine machine-id opts machine-spec)
  ```
- **Description**: The canonical registration macro. Compiles the spec into a `reg-event` handler and captures per-element source for Xray.
    - Walks the literal spec at expansion time. It attaches per-element source (`{:fn .. :source-coords .. :source-code ..}`) to each `:guards` / `:actions` / `:on-spawn-actions` entry, and a reference-site `:source-coords` to each `:states`-tree map node (state-node or transition map). Xray uses these to navigate from a snapshot back to the guard or action definition, or to the state-node.
    - Top-level call-site coords land on `handler-meta`.
    - The optional `opts` registration-metadata map sits in the middle slot. Its `:schema` key validates the dispatched outer event vector at the `:where :event` boundary. Any other keys ride onto the registration metadata.
    - The framework-owned `:rf/machine?` / `:rf/machine` keys are stamped by the registration home and must **not** appear in `opts`.
    - Reached on the `re-frame.core` facade as `rf/reg-machine`.

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

The snapshot lives at `[:rf.runtime/machines :snapshots :session]` in the frame's **runtime-db** partition (not app-db). The shape is `{:state :anonymous :data {...}}` (plus framework-managed slots for `:after` timer epochs and tags). Read it via the [`[:rf/machine machine-id]`](#rfmachine-machine-id) subscription vector — `@(rf/subscribe [:rf/machine machine-id])` — or directly with `subscribe-once`.

### `defmachine`

- **Kind**: macro
- **Signature**:
  ```clojure
  (defmachine name machine-spec)
  (defmachine name docstring machine-spec)
  ```
- **Description**: Defines a machine-spec *value* with per-element source captured. It is a drop-in for `def` whose body is a literal machine-spec map.
    - Walks the literal spec at expansion time. It attaches per-element source (`{:fn .. :source-coords .. :source-code ..}`) to each `:guards` / `:actions` / `:on-spawn-actions` entry, and a reference-site `:source-coords` to each `:states`-tree map node.
    - The source is stamped on the value itself. When the value is later passed to `reg-machine`, `(rf/handler-meta :machine-guard [machine-id guard-id])` and the Xray machine-cascade source rendering light up for value-registered machines exactly as for inline ones.
    - Needed because a plain `(def m {…})` + `(reg-machine :id m)` hands `reg-machine` only the symbol, so its literal-walk captures nothing. `defmachine` captures at the definition site.
    - The dev-only `:source-*` slots DCE under `:advanced` + `goog.DEBUG=false`.
    - Reached on the `re-frame.core` facade as `rf/defmachine`.
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
  (re-frame.machines/reg-machine* machine-id opts machine-spec)
  ```
- **Description**: Plain-fn surface beneath the macro. No source-coord walking.
    - For code-gen pipelines, REPL workflows, or conformance harnesses that synthesise specs from data.
    - The 3-arity takes the same middle-slot `opts` registration-metadata map as the macro. `:schema` validates the dispatched outer event vector at the `:where :event` boundary. The framework-owned `:rf/machine?` / `:rf/machine` keys must not appear in `opts`.
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
- **Description**: Compiles a transition table into the event-handler fn that `reg-machine` would register. Returns the fn; does not register it.
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
  (re-frame.machines/machine-transition definition snapshot event) → result/Result
  ```
- **Description**: The pure transition fn. Given a machine definition, a current snapshot, and an event, returns a `re-frame.machines.result/Result`.
    - The `Result` carries the new snapshot under `::result/snap` and the effects vector under `::result/fx`, or a *failure* value if a guard / action / `:data`-fn threw.
    - Discriminate with `result/ok?` / `result/fail?`; destructure `::result/snap` / `::result/fx`.
    - JVM-runnable; no live frame needed.
- **Worked example** — drive a transition and assert on the snapshot:
  ```clojure
  (require '[re-frame.machines :as machines]
           '[re-frame.machines.result :as result])

  (let [{snap ::result/snap fx ::result/fx}
        (machines/machine-transition login-flow
                                     {:state :idle :data {}}
                                     [:auth.login/submit {:email "a@b.com" :password "secret"}])]
    (is (= :submitting (:state snap)))
    (is (= :rf.http/managed (ffirst fx))))   ;; the :submitting :entry fired the request
  ```

## Inspection and queries

### `re-frame.machines/machines`

- **Kind**: function (owned by `re-frame.machines` — not a `re-frame.core` facade export)
- **Signature**:
  ```clojure
  (re-frame.machines/machines) → vector of machine-ids
  ```
- **Description**: Returns the vector of registered machine-ids. A derived view over `(registrations :event)` filtered by `:rf/machine? true`.
- **Example**:
  ```clojure
  ;; Every registered machine-id (the registry, not a frame's live snapshots).
  (machines/machines)                              ;; → [:session :auth.login/flow …]
  (contains? (set (machines/machines)) :session)
  ```

### `re-frame.machines/machine-meta`

- **Kind**: function (owned by `re-frame.machines` — not a `re-frame.core` facade export)
- **Signature**:
  ```clojure
  (re-frame.machines/machine-meta machine-id) → registration-metadata map
  ```
- **Description**: Returns the registered machine's spec map, or `nil` when `machine-id` does not name a registered machine. The spec map holds the transition table, doc, schemas, and per-element source-coords. It is read from the `:rf/machine` slot of the `:event` registration metadata.
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
  (re-frame.machines/machine-by-system-id system-id {:frame target})
  ```
- **Description**: Reverse-lookup: given a `system-id`, returns the spawned-machine id bound to it, or `nil`.
    - The single-arity resolves the frame from the ambient scope. Under no scope it raises `:rf.error/no-frame-context`.
    - The opts form `(machine-by-system-id system-id {:frame target})` names a frame explicitly. The trailing `{:frame …}` opts map is the same public frame-targeting shape `subscribe` takes.
    - The 2-arity is shape-discriminated on the second arg. An opts map is the public form; a bare frame target is the *internal* frame-last plumbing.
- **Example**:
  ```clojure
  ;; Resolve a :system-id-bound actor, then address it directly.
  (when-let [actor (machines/machine-by-system-id :notifier)]
    (rf/dispatch [actor [:notify "hello"]]))
  ```

## Keyword surfaces

The framework-registered subscription vectors and reserved effect tuples that address machines by keyword. These are unioned into every resolved image generation, since a `:select-ns` image cannot reach them by namespace. An image-loaded frame therefore resolves them the same way a default frame does.

### `[:rf/machine machine-id]`

- **Kind**: subscription (framework-registered)
- **Signature**:
  ```clojure
  [:rf/machine machine-id]
  ```
- **Description**: The canonical machine read. Returns a reaction whose value is the snapshot `{:state :data}` (plus framework-managed `:tags`), or `nil` if the machine is not yet initialised. [The table](../machines/concepts.md#register-and-drive) walks through subscribing to a snapshot and chaining named projections off it.
- **Example**:
  ```clojure
  (let [{:keys [state data]} @(rf/subscribe [:rf/machine :auth.login/flow])]
    [:div "State: " (name state)])
  ```

### `[:rf.machine/has-tag? machine-id tag]`

- **Kind**: subscription (framework-registered)
- **Signature**:
  ```clojure
  [:rf.machine/has-tag? machine-id tag]
  ```
- **Description**: Returns `true` iff the `:tags` set in the named machine's current snapshot contains `tag`. Returns `false` otherwise, including for unknown or not-yet-initialised machines. This is a *derived* sub: it reads the snapshot's containment-bit directly rather than chaining off `:rf/machine`. A view that only cares about one tag therefore re-renders only when that bit flips.
- **Example**:
  ```clojure
  @(rf/subscribe [:rf.machine/has-tag? :auth.login/flow :auth/busy])  ;; => true / false
  ```

### `[:rf.machine/spawn spawn-spec]`

- **Kind**: effect (reserved fx-id)
- **Signature**:
  ```clojure
  [:rf.machine/spawn spawn-spec]
  ```
- **Description**: Spawn a dynamic actor instance. Emitted from any event handler's `:fx` (including machine actions and the declarative `:spawn` desugar). `spawn-spec` carries exactly one of `:machine-id` (the registered machine type to instantiate) or `:definition` (an inline spec map), plus optional keys:
    - `:data` — overrides the type's initial `:data`.
    - `:id-prefix` — actor ids are the deterministic `<prefix>#<n>` from a per-type counter. The prefix defaults to `:machine-id`. Ids are never gensym'd.
    - `:fixed-actor-id` — an explicit actor address; skips allocation.
    - `:system-id` — binds the actor in the frame's `[:rf.runtime/machines :system-ids]` reverse index. A collision emits `:rf.error/system-id-collision` and rebinds last-write-wins.
    - `:start` — a single event vector dispatched to the new actor as `[<spawned-id> <start>]`. When absent, the runtime dispatches the synthetic `[<spawned-id> [:rf.machine.spawn/spawned]]`.
    - `:on-spawn` — an *advisory* callback `(fn [{:keys [data id]}] …)`. The return value is dropped. A non-nil return emits the dev-only `:rf.warning/on-spawn-return-ignored`.
    - Declarative `:spawn` state nodes accept the same keys plus `:on-done` / `:on-error` / `:timeout` / `:on-timeout`. On the declarative path, the framework binds the child's allocated id into the parent's `:data` at `[:rf/spawned <invoke-id>]`.
    - Fails closed when `:machine-id` names an unregistered type and no `:definition` is supplied (`:rf.error/machine-spawn-unregistered-type`). Backed by [`spawn-fx`](#re-framemachinesspawn-fx).
- **Example**:
  ```clojure
  (rf/reg-event :session/start-logger
    (fn [_ _]
      {:fx [[:rf.machine/spawn
             {:machine-id :machines/log-shipper
              :id-prefix  :logger          ;; actor id allocates as :logger#1, :logger#2, …
              :data       {:buffer []}
              :system-id  :logger          ;; name the actor by role
              :start      [:logger/connect]}]]}))

  ;; Address the actor by role — no id threading needed.
  (rf/reg-event :session/flush-logs
    (fn [_ _]
      {:fx [[:rf.machine/dispatch-to-system [:logger [:logger/flush]]]]}))
  ```

### `[:rf.machine/destroy actor-id]`

- **Kind**: effect (reserved fx-id)
- **Signature**:
  ```clojure
  [:rf.machine/destroy actor-id]
  ```
- **Description**: Tear down an actor. Symmetric counterpart to `:rf.machine/spawn`; backed by [`destroy-machine-fx`](#re-framemachinesdestroy-machine-fx).
    - Runs the actor's `:exit` cascade and cancels its armed `:after` timers. Dissociates `[:rf.runtime/machines :snapshots <actor-id>]` (in runtime-db) and releases its `:system-id` binding. Unregisters its event handler when one is registered.
    - A spawned actor has no per-instance registration — its liveness is its snapshot's presence.
    - Silent-idempotent: destroying an already-destroyed actor is a no-op.
- **Example**:
  ```clojure
  (rf/reg-event :session/stop-logger
    (fn [_ _]
      {:fx [[:rf.machine/destroy (machines/machine-by-system-id :logger)]]}))
  ```

**Final states and `:on-done`.** Leaf states marked `:final?` auto-destroy the machine on entry. The parent (if any) receives `:on-done` with the child's `:data` slot. So a spawn-shaped sub-process completes, the parent receives the result through `:on-done`, and the framework destroys the child. No manual `:rf.machine/destroy` is needed.

| State-node key | What it does |
|---|---|
| `:final?` | Marks a leaf state as terminal. Entering it auto-destroys the machine. Capability axis `:fsm/final-states`. |
| `:output-key` | Requires `:final?`. Designates the child's `:data` slot reported back via the parent's `:on-done`. |
| `:on-done` (spawn-spec key) | `(fn [{:keys [data result]}] new-data)` on the parent's `:spawn` map. Fires synchronously when the spawned child enters a `:final?` state. `result` is the child's `:data` slot named by the final state's `:output-key` (or `nil`). |

See [Final states](../machines/concepts.md#final-states) in [The table](../machines/concepts.md).

### `[:rf.machine/dispatch-to-system [system-id event]]`

- **Kind**: effect (reserved fx-id)
- **Signature**:
  ```clojure
  [:rf.machine/dispatch-to-system [system-id event]]
  ```
- **Description**: The action-side way one machine addresses its spawned child actor by *role* (`:logger`, `:websocket`, `:retry-coordinator`) instead of by allocated id.
    - Resolves `system-id` through the emitting frame's `[:rf.runtime/machines :system-ids]` reverse index (in runtime-db) and dispatches `event` to the bound actor. A no-op when the `system-id` is unbound.
    - This is the canonical action-side surface. A machine action can't read app-db, and its `:on-spawn` return is dropped, so the fx form is how an action messages a named actor.
    - Args ride as a single 2-element pair (the fx contract is a `[fx-id args]` pair).
    - Backed by [`dispatch-to-system-fx`](#re-framemachinesdispatch-to-system-fx). Retained for XState v6 actor-system parity (systemId addressing); zero in-repo consumers as of 2026-07-10.
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
- **Description**: Snapshot-level escape hatch. Emit from a callback's (or any event handler's) `:fx` vector to touch a machine's `:state` / `:meta` / `:data` atomically. The `:data` patch is gated by [`validate-update-snapshot-data!`](#re-framemachinesvalidate-update-snapshot-data) against the actor's `[:schemas :data]` schema *before* the fx writes it. The escape hatch is therefore not exempt from the `:where :machine-data` boundary. Per Spec 005 §Snapshot-level escape hatch.
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

When a child actor spawns declaratively under a parent, the framework binds the child's allocated id into the parent's `:data` at `[:rf/spawned <invoke-id>]`. An `:on-spawn` callback cannot capture that id, because its return is dropped. Naming by `:system-id` lets the parent address the child by *role* without threading the id around. The action-side surface is the [`[:rf.machine/dispatch-to-system [system-id event]]`](#rfmachinedispatch-to-system-system-id-event) fx tuple (above) — a parked named-addressing escape retained for XState v6 actor-system parity, with zero in-repo consumers. The everyday cross-machine send is plain dispatch to the id you hold (a machine IS an event handler). The fx handler below backs that tuple.

### `re-frame.machines/dispatch-to-system-fx`

- **Kind**: function (owned by `re-frame.machines`, implementation tier — the fx handler for `:rf.machine/dispatch-to-system`)
- **Signature**:
  ```clojure
  (re-frame.machines/dispatch-to-system-fx fx-ctx [system-id event])
  ```
- **Description**: The fx handler behind `:rf.machine/dispatch-to-system`.
    - Resolves `system-id` through the emitting frame's `[:rf.runtime/machines :system-ids]` reverse index and dispatches `event` to the bound actor. A no-op when the `system-id` is unbound.
    - The cascade-envelope frame is the fx-context `:frame`. A nil stamp is an invariant failure (`:rf.error/no-frame-context`), never a synthesised `:rf/default`.
    - App code emits the `[:rf.machine/dispatch-to-system [system-id event]]` fx rather than calling this fn.

## Machine-tooling exports (JVM)

The shipped machine-tooling exports are `re-frame.machines` aliases over `re-frame.machines.tooling`. The aliases are JVM-only; there is no `re-frame.core` facade export. CLJS tool consumers call `re-frame.machines.tooling/<name>` directly. The CLJS facade deliberately omits the tooling require, so an app that attaches no tool DCEs the tooling body. These exports render the machine *algebra view* that Xray and re-frame-pair navigate. There is **no** framework-level `machine->xstate-json`, `machine->mermaid`, or Stately bridge. Those exporters are owned by the separate post-v1 `day8/re-frame2-machines-viz` library, not the framework.

### `re-frame.machines/machine-algebra-view`

- **Kind**: function (owned by `re-frame.machines`, JVM-only — not a `re-frame.core` facade export)
- **Signature**:
  ```clojure
  (re-frame.machines/machine-algebra-view) → {machine-id node}
  (re-frame.machines/machine-algebra-view machine-id) → node (or nil)
  ```
- **Description**: The static algebra view over a machine *definition*. This is the structure Xray's machine inspector and the docs/visualisation lane read. JVM-only. The zero-arity form returns `{machine-id node}` for every registered machine, or `{}` when none are registered. The one-arity form returns the single node for `machine-id`, or `nil` if it is not registered.
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
- **Description**: The algebra view for a live machine *instance*: definition plus current snapshot, so the view can highlight the active state. JVM-only. The zero-arity form reads the ambient current frame; the one-arity form names a `frame-id`. Returns:
    - `{actor-id node}` — one node per live snapshot, covering singletons and spawned actors.
    - `{}` — the frame has no live machines.
    - `nil` — the frame is missing or destroyed, or this is a production build.
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
- **Description**: True iff the subscription registered under `sub-id` is a machine selector: an ordinary `reg-sub` whose static `:<-` inputs include a `[:rf/machine …]` (or `[:rf.machine/has-tag? …]`) query vector. Machine selectors stay ordinary ephemeral `:derivation` subscription nodes, not a second subscription system. This recognizer lets a graph tool flag the ones that read a machine. JVM-only.
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
- **Description**: The set of machine ids the subscription registered under `sub-id` reads as a machine selector. Each id is the second element of an accepted `[:rf/machine machine-id …]` / `[:rf.machine/has-tag? machine-id …]` static `:<-` input. Where `machine-selector?` answers only the boolean, this returns the actual target machine ids a graph tool needs to draw the edge. JVM-only.
- **Example**:
  ```clojure
  (machines/machine-selector-targets :session/summary)  ;; => #{:session}
  ```

## Implementation-tier effect handlers

The fx handlers behind the reserved `:rf.machine/*` effect ids. This namespace registers them via `reg-fx`, so an app that doesn't pull in `day8/re-frame2-machines` carries neither the trace strings nor the handler symbols on its production-elision bundle. **App code emits the effect tuple (Keyword surfaces, above) — it does not call these fns directly.** Each takes the standard `(handler fx-ctx args)` shape. The cascade-envelope frame is the fx-context `:frame`.

### `re-frame.machines/spawn-fx`

- **Kind**: function (owned by `re-frame.machines`, implementation tier — the fx handler for `:rf.machine/spawn`)
- **Signature**:
  ```clojure
  (re-frame.machines/spawn-fx fx-ctx spawn-spec)
  ```
- **Description**: Installs the spawned actor's snapshot at `[:rf.runtime/machines :snapshots <spawned-id>]` in the spawning frame's runtime-db. It stamps the revertible `:rf/machine-type` TYPE reference so the lazy resolver and epoch restore can rebuild the actor. The actor's liveness IS that snapshot's presence; there is no per-instance event-handler registration. Fails closed when `:machine-id` names an unregistered machine type and the spawn carries no inline `:definition`: it emits the always-on `:rf.error/machine-spawn-unregistered-type` and installs nothing.

### `re-frame.machines/spawn-all-init-fx`

- **Kind**: function (owned by `re-frame.machines`, implementation tier — the fx handler for `:rf.machine/spawn-all-init`)
- **Signature**:
  ```clojure
  (re-frame.machines/spawn-all-init-fx fx-ctx args)
  ```
- **Description**: On entry to a `:spawn-all`-bearing state, the runtime emits this fx alongside the per-child `:rf.machine/spawn` fxs. It seeds the join state at `[:rf.runtime/machines :spawned <parent> <invoke-id>]` with the shape `{:children {…} :done #{} :failed #{} :resolved? false :spec …}`. Subsequent `:on-child-done` / `:on-child-error` events resolve the join. Machine-internal — not for direct application use.

### `re-frame.machines/destroy-machine-fx`

- **Kind**: function (owned by `re-frame.machines`, implementation tier — the fx handler for `:rf.machine/destroy`)
- **Signature**:
  ```clojure
  (re-frame.machines/destroy-machine-fx fx-ctx args)
  ```
- **Description**: Picks the teardown path from the `args` shape: the keyword-form / single-`:spawn` teardown, or the `:spawn-all` children-iteration teardown. It runs the actor's `:exit` cascade, clears its `[:rf.runtime/machines :snapshots <actor-id>]` slot, and drops its event-handler registration.

### `re-frame.machines/after-schedule-fx`

- **Kind**: function (owned by `re-frame.machines`, implementation tier — the fx handler for `:rf.machine/after-schedule`)
- **Signature**:
  ```clojure
  (re-frame.machines/after-schedule-fx fx-ctx args)
  ```
- **Description**: On entry to an `:after`-bearing state node, the runtime emits one of these per `:after` entry. It resolves the delay (a literal `pos-int?`, a subscription vector, or a `(fn [snapshot] ms)`) and schedules a real wall-clock timer via the clock abstraction. For subscription delays it also installs an add-watch that cancels and reschedules when the sub's value changes. The synthetic expiry event is `[<parent-id> [:rf.machine.timer/after-elapsed <delay-key> <epoch> <decl-path>]]`. It fires only when the scheduling node is still active and the carried epoch matches. Machine-internal — not for direct application use.

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
- **Description**: Runs every registration-time check the machine grammar requires.
    - Covers history-state placement, the closed key-set, the at-most-one-per-compound rule, `:default-target` resolution, `:type :parallel` region shape, and top-level dispatch plus guard/action ref resolution.
    - Composed at the top of `make-machine-handler` so the registered handler fn's body is exclusively request processing.
    - Throws the `:rf.error/machine-*` taxonomy on a grammar violation (e.g. `:rf.error/machine-history-misplaced` / `-history-extra-keys` / `-history-duplicate` / `-history-bad-default-target`, `:rf.error/machine-unknown-node-key`, `:rf.error/machine-unresolved-guard` / `-unresolved-action`).
    - The conformance corpus's `:reg-machine` Mode-B op pins the registration-error taxonomy against this leaf fn.

### `re-frame.machines/validate-machine-data!`

- **Kind**: function (owned by `re-frame.machines`, implementation tier)
- **Signature**:
  ```clojure
  (re-frame.machines/validate-machine-data! runtime-db event-id frame-id) → boolean
  ```
- **Description**: Walks every snapshot under `[:rf.runtime/machines :snapshots]` in `runtime-db` and validates its `:data` against the resolved machine's `[:schemas :data]` schema.
    - Returns `true` iff every snapshot conformed, or carried no schema / no validator. Returns `false` on the first failure, with the per-snapshot trace already emitted. The router then rolls back the whole transition — the same mechanism as the `:where :app-db` rollback.
    - Schema resolution covers a SINGLETON (via `machine-meta`) AND a SPAWNED actor (via the snapshot's `:rf/machine-type`).
    - This is the post-commit boundary the router AND-conjoins with `validate-app-schema!`.

### `re-frame.machines/validate-spawn-data!`

- **Kind**: function (owned by `re-frame.machines`, implementation tier)
- **Signature**:
  ```clojure
  (re-frame.machines/validate-spawn-data! spawned-id spec snapshot) → boolean
  ```
- **Description**: Sibling of `validate-machine-data!` for the `:rf.machine/spawn` install path. Validates a freshly-built initial snapshot's `:data` against the spawned actor's machine `[:schemas :data]` schema BEFORE the snapshot lands in runtime-db. Returns `true` on conform, no schema, or no validator. Returns `false` on failure, and the caller skips the install. A spawn failure does not commit, so there is nothing to roll back (`:phase :spawn` emits with `:rollback? false`).

### `re-frame.machines/validate-update-snapshot-data!`

- **Kind**: function (owned by `re-frame.machines`, implementation tier)
- **Signature**:
  ```clojure
  (re-frame.machines/validate-update-snapshot-data! machine-id merged-snapshot) → boolean
  ```
- **Description**: Sibling validator for the `:rf.machine/update-snapshot` escape-hatch fx. Validates the would-be-merged snapshot's `:data` against the actor's resolved `[:schemas :data]` schema BEFORE the fx writes the patch into runtime-db. Returns `true` on conform, no schema, or no validator; the fx proceeds with the write. Returns `false` on failure; the fx SKIPS the write so the invalid `:data` never installs. The escape hatch is therefore not exempt from the `:where :machine-data` boundary.

## Runtime and lifecycle helpers

### `re-frame.machines/install-machine-runtime!`

- **Kind**: function (owned by `re-frame.machines`, implementation tier)
- **Signature**:
  ```clojure
  (re-frame.machines/install-machine-runtime!)
  ```
- **Description**: Re-registers the machine runtime effects and subs into BOTH the regular registrar AND the framework-standard registry. An image-loaded frame can therefore resolve `[:rf.machine/spawn …]` / `[:rf/machine …]` through its sealed generation. Idempotent.
    - Re-registers from descriptors captured at ns-load, so it works even after a `registrar/clear-all!` has wiped the registrar slots. It is the machine analogue of the `:rf/set-db` standard re-seed.
    - Called at ns load, from the `:machines/install-runtime!` late-bind hook the reset fixture fires, and directly by tests that wipe the registrar.

### `re-frame.machines/reset-timers!`

- **Kind**: function (owned by `re-frame.machines`, implementation tier)
- **Signature**:
  ```clojure
  (re-frame.machines/reset-timers!)
  (re-frame.machines/reset-timers! frame-id)
  ```
- **Description**: Cancel in-flight `:after` timers.
    - The 0-arity form clears every frame's timers. This is the fixture-teardown shape used by `re-frame.test-support`'s `reset-runtime` and per-feature artefact test fixtures.
    - The 1-arity form clears just the given frame's timers. This is the `frame/destroy-frame!` hook shape: it releases a destroyed frame's host-clock handles and subscription watchers without touching siblings.
    - Spawn-id counters reset automatically with the registrar snapshot/restore + frame reset, so this hook handles only the frame-scoped wall-clock timer table.

### `re-frame.machines/owning-actor-id`

- **Kind**: function (owned by `re-frame.machines`, implementation tier)
- **Signature**:
  ```clojure
  (re-frame.machines/owning-actor-id frame-id event-id) → actor-id (or nil)
  ```
- **Description**: Resolve the spawned-actor-id that OWNS `event-id` in `frame-id`, or `nil`.
    - Returns `event-id` (a keyword — the spawned actor's machine address) when a SPAWNED actor's snapshot is currently installed at `[:rf.runtime/machines :snapshots <event-id>]`. Otherwise returns `nil`: the event came from an ordinary handler or a singleton machine.
    - Set semantics are snapshot membership via the durable `:rf/machine-type`-at-root discriminator. This covers declarative `:spawn` / `:spawn-all` actors AND imperative `[:rf.machine/spawn …]` actors.
    - Published as the `:machines/owning-actor-id` late-bind hook. The http artefact uses it to ask "who owns this request's originating event?" (to abort managed HTTP on actor-destroy) without statically requiring this artefact. http falls back to `nil` when machines is absent.

## See also

- [re-frame.core.md](re-frame.core.md) — `reg-machine` / `defmachine` / `machine-has-tag?` are reached on the `re-frame.core` facade; `dispatch` / `subscribe` / `reg-event` drive and read a machine.
- [re-frame.schemas.md](re-frame.schemas.md) — machines declare schemas for their `:data` slot the same way ordinary handlers do; the `validate-*-data!` validators gate them.
- [The table](../machines/concepts.md) — the flat-table contract: guards, actions, encapsulation, finals, schemas. The numbered Machines pages grow one login machine through tags, automatic transitions, hierarchy, parallel regions, history, and actors.
- [Glossary](../machines/glossary.md) — the surface vocabulary in one place.
- [Coming from XState](../machines/coming-from-xstate.md) — the v6 parity delta for XState users.
