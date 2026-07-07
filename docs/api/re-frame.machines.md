# re-frame.machines

State machines, per Spec 005. A machine is registered with one macro (`reg-machine`) and *is* an event handler: the transition table — a map of `:states`, `:on`, `:entry`, `:exit`, `:after` — compiles into a `reg-event` handler at registration time. Dispatch an event at the machine's id and the table decides the transition; the resulting `:db` and `:fx` flow through the normal cascade. The same trace bus, time-travel, and override surfaces that work for plain handlers work for machines.

```clojure
(:require [re-frame.core     :as rf]        ;; reg-machine / defmachine / machine-has-tag? / sub-machine — the facade macros + sub sugar
          [re-frame.machines :as machines]) ;; engine, query, transition, tooling, runtime helpers
```

Surfaces split two ways:

- **`re-frame.core` facade exports** (reach as `rf/…`): the `reg-machine` / `defmachine` macros and the `machine-has-tag?` / `sub-machine` subscription sugar.
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
    - Walks the literal spec at expansion time; co-locates per-element source (`{:fn .. :source-coords .. :source-code ..}`) onto each `:guards` / `:actions` / `:on-spawn-actions` entry, plus a reference-site `:source-coords` onto each `:states`-tree map node (state-node / transition map). Xray uses these to navigate from a snapshot back to the guard/action definition or the state-node.
    - Top-level call-site coords land on `handler-meta`.
    - The optional `opts` registration-metadata map sits in the middle slot. Its `:schema` key validates the dispatched outer event vector at the `:where :event` boundary; any other keys ride onto the registration metadata.
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

The snapshot lives at `[:rf.runtime/machines :snapshots :session]` in the frame's **runtime-db** partition (not app-db). The shape is `{:state :anonymous :data {...}}` (plus framework-managed slots for `:after` timer epochs and tags). Read it via the [`[:rf/machine machine-id]`](#rfmachine-machine-id) subscription vector (or the `rf/sub-machine` read sugar over it), or directly with `subscribe-once`.

### `defmachine`

- **Kind**: macro
- **Signature**:
  ```clojure
  (defmachine name machine-spec)
  (defmachine name docstring machine-spec)
  ```
- **Description**: Defines a machine-spec *value* with per-element source captured — a drop-in for `def` whose body is a literal machine-spec map.
    - Walks the literal spec at expansion time; co-locates per-element source (`{:fn .. :source-coords .. :source-code ..}`) onto each `:guards` / `:actions` / `:on-spawn-actions` entry, plus a reference-site `:source-coords` onto each `:states`-tree map node.
    - The source is stamped on the value, so when it is later passed to `reg-machine`, `(rf/handler-meta :machine-guard [machine-id guard-id])` and the Xray machine-cascade source rendering light up for value-registered machines exactly as for inline ones.
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
    - The 3-arity takes the same middle-slot `opts` registration-metadata map as the macro: `:schema` validates the dispatched outer event vector at the `:where :event` boundary; the framework-owned `:rf/machine?` / `:rf/machine` keys must not appear in `opts`.
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
- **Description**: Returns the registered machine's spec map, or `nil` when `machine-id` does not name a registered machine. The spec map holds the transition table, doc, schemas, and per-element source-coords — read from the `:rf/machine` slot of the `:event` registration metadata.
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
    - The single-arity resolves the frame from the ambient scope, raising `:rf.error/no-frame-context` under no scope.
    - The opts form `(machine-by-system-id system-id {:frame target})` names a frame explicitly — the trailing `{:frame …}` opts map, the same public-frame-targeting shape `sub-machine` takes.
    - The 2-arity is shape-discriminated on the second arg: an opts map is the public form; a bare frame target is the *internal* frame-last plumbing.
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
- **Description**: Sugar over `(subscribe [:rf/machine-has-tag? machine-id tag])`. A reactive predicate over the machine's snapshot's `:tags` set. Reached on the `re-frame.core` facade as `rf/machine-has-tag?`.
- **Example**:
  ```clojure
  ;; Ask by state-tag, not exact state name — disable inputs while a request is in flight.
  (let [busy? @(rf/machine-has-tag? :auth.login/flow :auth/busy)]
    [:button {:disabled busy?} "Sign in"])
  ```

### `sub-machine`

- **Kind**: function
- **Signature**:
  ```clojure
  (sub-machine machine-id) → reaction
  (sub-machine machine-id opts) → reaction
  ```
- **Description**: Sugar over `(subscribe [:rf/machine machine-id])`. Returns a reaction whose value is the snapshot map `{:state :data :tags}`, or `nil` before the machine's first event.
    - The 2-arity `opts` map carries the `{:frame target}` capability the underlying subscription vector accepts — `target` is a frame-id keyword or a live frame value.
    - The `sub-` prefix is the subscription-family verb; it does not denote a child-machine relationship.
    - The `[:rf/machine machine-id]` vector form remains the canonical registered sub.
    - Reached on the `re-frame.core` facade as `rf/sub-machine`.
- **Example**:
  ```clojure
  (let [{:keys [state]} @(rf/sub-machine :auth.login/flow)]
    [:div "State: " (name state)])
  ```

## Keyword surfaces

The framework-registered subscription vectors and reserved effect tuples that address machines by keyword. These are unioned into every resolved image generation (a `:select-ns` image cannot reach them by namespace), so an image-loaded frame resolves them the same way a default frame does.

### `[:rf/machine machine-id]`

- **Kind**: subscription (framework-registered)
- **Signature**:
  ```clojure
  [:rf/machine machine-id]
  ```
- **Description**: The canonical machine read. Returns a reaction whose value is the snapshot `{:state :data}` (plus framework-managed `:tags`), or `nil` if the machine is not yet initialised. The `re-frame.core` `sub-machine` fn is read sugar over this vector. The [machines concept guide](../machines/concepts.md#registering-and-running-it) walks through subscribing to a snapshot and chaining named projections off it.
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
- **Description**: Returns `true` iff the named machine's current snapshot's `:tags` set contains `tag`, `false` otherwise (including unknown / not-yet-initialised machines). A *derived* sub: it reads the snapshot's containment-bit directly rather than chaining off `:rf/machine`, so a view that only cares about one tag re-renders only when that bit flips. The `re-frame.core` `machine-has-tag?` sugar fn wraps this vector.
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
- **Description**: Spawn a dynamic actor instance. Emitted from any event handler's `:fx` (including machine actions and the declarative `:spawn` desugar). `spawn-spec` carries exactly one of `:machine-id` (the registered machine type to instantiate) or `:definition` (an inline spec map), plus optional keys:
    - `:data` — overrides the type's initial `:data`.
    - `:id-prefix` — actor ids are the deterministic `<prefix>#<n>` from a per-type counter; the prefix defaults to `:machine-id`; ids are never gensym'd.
    - `:fixed-actor-id` — an explicit actor address; skips allocation.
    - `:system-id` — binds the actor in the frame's `[:rf.runtime/machines :system-ids]` reverse index; a collision emits `:rf.error/system-id-collision` and rebinds last-write-wins.
    - `:start` — a single event vector dispatched to the new actor as `[<spawned-id> <start>]`; when absent the runtime dispatches the synthetic `[<spawned-id> [:rf.machine.spawn/spawned]]`.
    - `:on-spawn` — an *advisory* callback `(fn [{:keys [data id]}] …)`; the return value is dropped; a non-nil return emits the dev-only `:rf.warning/on-spawn-return-ignored`.
    - Declarative `:spawn` state nodes accept the same keys plus `:on-done` / `:on-error` / `:timeout` / `:on-timeout`; on the declarative path the framework binds the child's allocated id into the parent's `:data` at `[:rf/spawned <invoke-id>]`.
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
    - Runs the actor's `:exit` cascade, cancels its armed `:after` timers, dissociates `[:rf.runtime/machines :snapshots <actor-id>]` (in runtime-db), releases its `:system-id` binding, and unregisters its event handler when one is registered.
    - A spawned actor has no per-instance registration — its liveness is its snapshot's presence.
    - Silent-idempotent: destroying an already-destroyed actor is a no-op.
- **Example**:
  ```clojure
  (rf/reg-event :session/stop-logger
    (fn [_ _]
      {:fx [[:rf.machine/destroy (machines/machine-by-system-id :logger)]]}))
  ```

**Final states and `:on-done`.** Leaf states marked `:final?` auto-destroy the machine on entry; the parent (if any) receives `:on-done` with the child's `:data` slot. A spawn-shaped sub-process completes, the parent receives the result through `:on-done`, and the framework destroys the child — no manual `:rf.machine/destroy` needed.

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
- **Description**: The action-side way one machine addresses its spawned child actor by *role* (`:logger`, `:websocket`, `:retry-coordinator`) instead of by allocated id.
    - Resolves `system-id` through the emitting frame's `[:rf.runtime/machines :system-ids]` reverse index (in runtime-db) and dispatches `event` to the bound actor; no-op when the `system-id` is unbound.
    - Canonical action-side surface: a machine action can't read app-db and its `:on-spawn` return is dropped, so the fx form is how an action messages a named actor.
    - Args ride as a single 2-element pair (the fx contract is a `[fx-id args]` pair).
    - Backed by [`dispatch-to-system-fx`](#re-framemachinesdispatch-to-system-fx); the call-site twin is the [`dispatch-to-system`](#re-framemachinesdispatch-to-system) fn.
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

When a child actor spawns declaratively under a parent, the framework binds the child's allocated id into the parent's `:data` at `[:rf/spawned <invoke-id>]` (an `:on-spawn` callback cannot capture it — its return is dropped); naming by `:system-id` lets the parent address the child by *role* without threading that id. The canonical action-side surface is the [`[:rf.machine/dispatch-to-system [system-id event]]`](#rfmachinedispatch-to-system-system-id-event) fx tuple (above); the helpers below are the direct-call twins.

### `re-frame.machines/dispatch-to-system`

- **Kind**: function (owned by `re-frame.machines`, implementation tier — **not a `re-frame.core` facade export**)
- **Signature**:
  ```clojure
  (re-frame.machines/dispatch-to-system system-id event)
  (re-frame.machines/dispatch-to-system system-id event frame-id)
  ```
- **Description**: The direct-call twin of the `:rf.machine/dispatch-to-system` fx — sugar over `(when-let [m (machine-by-system-id system-id)] (dispatch [m event]))`, no-op when the `system-id` is unbound.
    - The two-arity form resolves the frame from the carried scope it runs under; the three-arity form names the frame explicitly. There is no `:rf/default` fallback, and looking up under no scope raises `:rf.error/no-frame-context`.
    - Implementation-tier helper; new app code should emit the `[:rf.machine/dispatch-to-system [system-id event]]` fx instead.
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
- **Description**: The fx handler behind `:rf.machine/dispatch-to-system`.
    - Resolves `system-id` through the emitting frame's `[:rf.runtime/machines :system-ids]` reverse index and dispatches `event` to the bound actor; no-op when the `system-id` is unbound (symmetric with the `dispatch-to-system` fn's no-op fall-through).
    - The cascade-envelope frame is the fx-context `:frame`; a nil stamp is an invariant failure (`:rf.error/no-frame-context`), never a synthesised `:rf/default`.
    - App code emits the `[:rf.machine/dispatch-to-system [system-id event]]` fx rather than calling this fn.

## Machine-tooling exports (JVM)

The shipped machine-tooling exports are `re-frame.machines` aliases over `re-frame.machines.tooling`; the aliases are JVM-only (no `re-frame.core` facade export). CLJS tool consumers call `re-frame.machines.tooling/<name>` directly — the CLJS facade deliberately omits the tooling require so an app that attaches no tool DCEs the tooling body. They render the machine *algebra view* that Xray / re-frame-pair navigate. There is **no** framework-level `machine->xstate-json`, `machine->mermaid`, or Stately bridge — those exporters are owned by the separate post-v1 `day8/re-frame2-machines-viz` library, not the framework.

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
- **Description**: True iff the subscription registered under `sub-id` is a machine selector — an ordinary `reg-sub` whose static `:<-` inputs include a `[:rf/machine …]` (or `[:rf/machine-has-tag? …]`) query vector. Machine selectors stay ordinary ephemeral `:derivation` subscription nodes, not a second subscription system; this recognizer lets a graph tool flag the ones that read a machine. JVM-only.
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
- **Description**: The set of machine ids the subscription registered under `sub-id` reads as a machine selector — the second element of each accepted `[:rf/machine machine-id …]` / `[:rf/machine-has-tag? machine-id …]` static `:<-` input. Where `machine-selector?` answers only the boolean, this returns the actual target machine ids a graph tool needs to draw the edge. JVM-only.
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
- **Description**: Runs every registration-time check the machine grammar requires.
    - Covers history-state placement / closed key-set / at-most-one-per-compound / `:default-target` resolution, `:type :parallel` region shape, and top-level dispatch + guard/action ref resolution.
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
    - Returns `true` iff every snapshot conformed (or carried no schema / no validator); `false` on the first failure, with the per-snapshot trace already emitted — the router then rolls back the whole transition (same mechanism as the `:where :app-db` rollback).
    - Schema resolution covers a SINGLETON (via `machine-meta`) AND a SPAWNED actor (via the snapshot's `:rf/machine-type`).
    - The post-commit boundary the router AND-conjoins with `validate-app-schema!`.

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
- **Description**: Re-registers the machine runtime effects + subs into BOTH the regular registrar AND the framework-standard registry (so an image-loaded frame resolves `[:rf.machine/spawn …]` / `[:rf/machine …]` through its sealed generation). Idempotent.
    - Re-registers from descriptors captured at ns-load, so it works even after a `registrar/clear-all!` has wiped the registrar slots — the machine analogue of the `:rf/set-db` standard re-seed.
    - Called at ns load, from the `:machines/install-runtime!` late-bind hook the reset fixture fires, and directly by tests that wipe the registrar.

### `re-frame.machines/reset-timers!`

- **Kind**: function (owned by `re-frame.machines`, implementation tier)
- **Signature**:
  ```clojure
  (re-frame.machines/reset-timers!)
  (re-frame.machines/reset-timers! frame-id)
  ```
- **Description**: Cancel in-flight `:after` timers.
    - The 0-arity form clears every frame's timers — the fixture-teardown shape used by `re-frame.test-support`'s `reset-runtime` and per-feature artefact test fixtures.
    - The 1-arity form clears just the given frame's timers — the `frame/destroy-frame!` hook shape that releases a destroyed frame's host-clock handles and subscription watchers without touching siblings.
    - Spawn-id counters reset automatically with the registrar snapshot/restore + frame reset, so this hook handles only the frame-scoped wall-clock timer table.

### `re-frame.machines/owning-actor-id`

- **Kind**: function (owned by `re-frame.machines`, implementation tier)
- **Signature**:
  ```clojure
  (re-frame.machines/owning-actor-id frame-id event-id) → actor-id (or nil)
  ```
- **Description**: Resolve the spawned-actor-id that OWNS `event-id` in `frame-id`, or `nil`.
    - Returns `event-id` (a keyword — the spawned actor's machine address) when a SPAWNED actor's snapshot is currently installed at `[:rf.runtime/machines :snapshots <event-id>]`, otherwise `nil` (the event came from an ordinary handler or a singleton machine).
    - Set semantics are snapshot membership via the durable `:rf/machine-type`-at-root discriminator — declarative `:spawn` / `:spawn-all` AND imperative `[:rf.machine/spawn …]` actors.
    - Published as the `:machines/owning-actor-id` late-bind hook so the http artefact can ask "who owns this request's originating event?" (to abort managed HTTP on actor-destroy) without statically requiring this artefact; http falls back to `nil` when machines is absent.

## See also

- [re-frame.core.md](re-frame.core.md) — `reg-machine` / `defmachine` / `machine-has-tag?` are reached on the `re-frame.core` facade; `dispatch` / `subscribe` / `reg-event` drive and read a machine.
- [re-frame.schemas.md](re-frame.schemas.md) — machines declare schemas for their `:data` slot the same way ordinary handlers do; the `validate-*-data!` validators gate them.
- [Machines concept guide](../machines/concepts.md) — the narrative walkthrough: the transition table, guards, actions, tags, `:after`, final states, and when to reach for a machine. The v1 transition-table grammar covers a specific subset of Statechart capabilities — sequencing, `:after` timers, internal-vs-external transitions, guards, action lists, hierarchical states, parallel regions, and final-state semantics — the exact subset and its rationale are covered there.
- [Machines glossary](../machines/glossary.md) — the surface vocabulary in one place.
- [Coming from XState](../machines/coming-from-xstate.md) — the v6 parity delta for XState users.
