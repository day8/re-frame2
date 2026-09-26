# re-frame.machines

Use a state machine when a feature moves through named states (idle, submitting, locked out) and the events it accepts depend on the state it is in. You write the transition table as data and register it with `rf/reg-machine`. The machine is then an ordinary event handler: dispatching `[machine-id event]` runs the table, which picks a transition, updates the machine's snapshot and returns effects through the normal event pipeline. Tracing, time-travel and overrides work for machines as they do for any other handler.

Machines ship in the optional `day8/re-frame2-machines` artefact. Require `re-frame.machines` once, from a boot or feature namespace, to load it; without it, `rf/reg-machine` throws `:rf.error/machines-artefact-missing`.

```clojure
(:require [re-frame.core     :as rf]            ;; rf/reg-machine, rf/defmachine
          [re-frame.machines :as rf.machines])  ;; everything else on this page
```

```clojure
(rf/reg-machine :auth.login/flow
  {:initial :idle
   :states  {:idle       {:on {:auth.login/submit :submitting}}
             :submitting {:on {:auth.login/success :authed
                               :auth.login/failure :idle}}
             :authed     {}}})

(rf/reg-view login-button []
  ;; The snapshot is nil until the machine handles its first event.
  (let [{:keys [state]} @(subscribe [:rf/machine :auth.login/flow])]
    (case state
      :submitting [:p "Signing in…"]
      :authed     [:p "Signed in"]
      [:button {:on-click #(dispatch [:auth.login/flow [:auth.login/submit]])}
       "Sign in"])))
```

A `:status` keyword in `app-db`, checked by ordinary event handlers, is enough while there are two or three states and each check is one line. A machine pays off when those checks spread: several events are legal only in some states, a state needs a timeout or must cancel work when it is left, or you want to test `(state, event) → next state + effects` as a pure function with [`machine-transition`](#re-framemachinesmachine-transition). For fetching and caching server data, use [resources](../resources/concepts.md) instead. [When not to use a machine](../machines/index.md#when-not-to-use-a-machine) has the fuller table.

A registered machine runs as one instance per frame, addressed by its machine id. To run several copies of one machine at once (one per upload, one per open socket), or a child machine that lives only while its parent is in one state, spawn actors: each actor has its own id and snapshot, and you dispatch to its id the same way. See [`:rf.machine/spawn`](#rfmachinespawn-spawn-spec).

`reg-machine` and `defmachine` are called on the `re-frame.core` facade. Everything else is called on this namespace as `rf.machines/…`, or addressed by keyword. Use the `rf.machines` alias and keep the bare `machines` alias for your application's own namespaces.

The machines guide teaches the model, starting from [The table](../machines/concepts.md).

## Registration

### `reg-machine`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-machine machine-id machine-spec)
  (reg-machine machine-id opts machine-spec)
  ```
- **Description**: Registers `machine-spec` as the event handler for `machine-id`. Dispatching `[machine-id event]` runs the transition table. Called as `rf/reg-machine`.
    - `opts`, the optional middle slot, is a registration-metadata map. Its `:schema` validates the dispatched outer event vector at the `:where :event` boundary; any other keys are stored on the registration metadata.
    - The macro walks the literal spec at expansion time. It attaches source (`{:fn .. :source-coords .. :source-code ..}`) to each `:guards` and `:actions` entry, and a reference-site `:source-coords` to each state node and transition map under `:states`. Xray uses these to go from a snapshot to the guard, action or state definition. The call site's own coordinates are on `handler-meta`.
    - Pass either a literal spec or a value defined with [`defmachine`](#defmachine). A value bound with plain `def` carries no source.
    - The snapshot lives in the frame's `runtime-db` (not `app-db`) at `[:rf.runtime/machines :snapshots machine-id]`. Its shape is `{:state … :data …}` plus framework-managed slots for `:after` timer epochs and tags. Read it with the [`[:rf/machine machine-id]`](#rfmachine-machine-id) subscription, or once with `subscribe-once`.
- **Errors**:
    - `:rf.error/invalid-machine-opts`: `opts` is not a map.
    - `:rf.error/machine-reserved-meta-in-opts`: `opts` carries `:rf/machine?` or `:rf/machine`, which the registration sets itself.
    - A grammar violation in the spec throws one of the ids listed under [`validate-machine!`](#re-framemachinesvalidate-machine).
- **Example**:
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

  ;; The machine is an event handler: dispatch a wrapped event at its id.
  (rf/dispatch [:session [:login {:user "alice" :pass "correct-horse"}]])
  ```

### `defmachine`

- **Kind**: macro
- **Signature**:
  ```clojure
  (defmachine name machine-spec)
  (defmachine name docstring machine-spec)
  ```
- **Description**: Defines `name` as a machine-spec value and captures its per-element source, so a later `reg-machine` of that value keeps it. Use it in place of `def` for a named spec. Called as `rf/defmachine`.
    - It walks the literal spec the same way `reg-machine` does and stores the source on the value. `(rf/handler-meta {:source :store :kind :machine-guard :id [machine-id guard-id]})` and Xray's machine source view then work for the registered value as they do for an inline spec.
    - With `(def m {…})` and `(reg-machine :id m)`, `reg-machine` sees only the symbol and captures nothing. In development the registration warns `:rf.warning/machine-source-unstamped`, once per machine id.
    - Production builds remove the development-only `:source-*` slots.
- **Example**:
  ```clojure
  (rf/defmachine door-machine
    "A door that locks."
    {:initial :locked
     :states  {:locked {:on {:unlock {:target :closed}}}
               :closed {:on {:open {:target :open}
                             :lock {:target :locked}}}
               :open   {:on {:close {:target :closed}}}}})

  (rf/reg-machine :door/main door-machine)
  ```

### Machine-root keys

Beside `:initial`, `:states`, `:data`, `:guards` and `:actions`, a machine spec's root takes the keys below. The runtime reads them only at the root: on a nested state they throw `:rf.error/machine-unknown-node-key`. [Spec 005](../../spec/005-StateMachines.md) has the long form.

| Root key | What it takes and does |
|---|---|
| `:schemas` | A map whose keys are among `:data`, `:output`, `:events`, `:tags` and `:meta`, each a schema. `:data` validates the snapshot's `:data`; see [`validate-machine-data!`](#re-framemachinesvalidate-machine-data). `:output` is the next row. `:events`, `:tags` and `:meta` are accepted and not checked. Any other key, `:input` included, throws `:rf.error/machine-bad-schemas-key`, and a non-map throws `:rf.error/machine-bad-schemas`. |
| `[:schemas :output]` | A schema for the value a root-level `:final?` leaf reports through `:output-key`, which is `nil` when the leaf has none. It is checked once, as the machine finishes, in development builds only. A failing value emits `:rf.error/schema-validation-failure` with `:where :machine-output`, `:phase :completion` and `:rollback? false`, and nothing is rolled back: the machine has already finished, so it is still destroyed and a parent's `:on-done` still receives the value. A schema the validator throws on emits `:rf.error/malformed-schema` with `:where :machine-output`, and completion proceeds the same way. See [Completion-output validation](../../spec/005-StateMachines.md#completion-output-validation). |
| `:sensitive`, `:large` | A vector of paths into the snapshot, such as `[[:data :payment :token]]`. They classify those slots of every instance: each actor's paths are registered when it is spawned, first boots or is restored, and removed when it is destroyed. Traces and the SSR hydration payload then show `:rf/redacted` for a sensitive slot and the `:rf.size/large-elided` marker for a large one; the snapshot itself is unchanged. A malformed declaration throws `:rf.error/invalid-machine-classification` at registration. A `:sensitive?` prop inside `[:schemas :data]` does not classify the snapshot; it only redacts a failed validation's trace. See [Classify subsystem data on the subsystem](../core/how-to/keep-secrets-out-of-traces.md#classify-subsystem-data-on-the-subsystem). |
| `:internal-events` | A set of keywords, such as `#{:tick}`, naming events the machine raises for itself. [`[:raise event-vec]`](#raise-event-vec) says what an external dispatch of one does. A vector, a non-keyword member or a wildcard member such as `:tick/*` throws `:rf.error/machine-bad-internal-events`, and a reserved `:rf/*` id throws `:rf.error/machine-internal-event-reserved`. See [Public / private `:internal-events`](../../spec/005-StateMachines.md#public--private-internal-events). |
| `:always-depth-limit` | An integer, 16 by default. It bounds the `:always` transitions the machine takes while it settles after an event. Exceeding it aborts the whole macrostep with `:rf.error/machine-always-depth-exceeded`, and no snapshot or effects commit. |
| `:raise-depth-limit` | An integer, 16 by default. It bounds the raised events one macrostep handles; [`[:raise event-vec]`](#raise-event-vec) has the rule. See [Drain semantics](../../spec/005-StateMachines.md#drain-semantics). |

## Keyword surfaces

These are the subscriptions and effects the machines artefact registers. They are included in every [image](../core/images.md) whatever its `:select-ns` selection, so a frame loaded from an image resolves them the same way the default frame does.

### `[:rf/machine machine-id]`

- **Kind**: subscription
- **Payload**: `machine-id`, a registered machine id or a spawned actor's id.
- **Description**: Returns the machine's snapshot `{:state :data}`, plus the framework-managed `:tags`. Returns `nil` for an unknown machine, and for a registered machine that has not yet handled its first event. To give views narrower values, register subscriptions that take this one as an input, as shown in [The table](../machines/concepts.md#register-and-drive).
    - A view that renders before the first event must handle `nil`. To create the snapshot at startup instead, dispatch the reserved trigger `[machine-id [:rf.machine/start]]`: it runs the initial state's `:entry` actions and arms its `:after` timers, and matches no `:on` transition.
- **Example**:
  ```clojure
  (let [{:keys [state data]} @(rf/subscribe [:rf/machine :auth.login/flow])]
    [:div "State: " (if state (name state) "not started")])

  ;; Or start the machine at boot, so the snapshot exists before any view reads it.
  (rf/dispatch [:auth.login/flow [:rf.machine/start]])
  ```

### `[:rf.machine/has-tag? machine-id tag]`

- **Kind**: subscription
- **Payload**: `machine-id` and `tag`.
- **Description**: Returns `true` when the `:tags` set in the machine's current snapshot contains `tag`, and `false` otherwise, including for an unknown or not-yet-started machine. It reads the tag directly rather than through `:rf/machine`, so a view that reads only this re-renders only when the answer changes.
- **Example**:
  ```clojure
  @(rf/subscribe [:rf.machine/has-tag? :auth.login/flow :auth/busy])  ;; => true / false
  ```

### `[:rf.machine/spawn spawn-spec]`

- **Kind**: effect (reserved fx-id)
- **Payload**: a `spawn-spec` map with exactly one of `:machine-id` (a registered machine to instantiate) or `:definition` (an inline spec map), plus the optional keys below.
- **Description**: Starts a new instance of a machine, called an actor. Emit it from any event handler's `:fx`, including a machine action's. A declarative `:spawn` state node emits it for you.
    - Choose by lifetime. When a child should live exactly as long as one state of a parent machine, put `:spawn` on that state: leaving the state, or destroying the parent, destroys the child. Emit this effect yourself when the actor's lifetime is not one state, such as a logger started with the session. Nothing tracks an actor you spawn this way; it lives until a [`:rf.machine/destroy`](#rfmachinedestroy-actor-id) names it.
    - `:data` replaces the machine's initial `:data`. The runtime adds the actor's own id to it as `:rf/self-id`.
    - `:id-prefix` sets the prefix of the actor's id, which is the deterministic `<prefix>#<n>` from a per-type counter. The prefix defaults to `:machine-id`.
    - `:fixed-actor-id` gives the actor an explicit id instead. Use it when the spawner needs to hold the child's address: choose a fresh keyword, store it in ordinary `:data`, and pass it here. Spawning at a `:fixed-actor-id` that a live actor already holds destroys that actor first (its `:exit` actions run) and then installs the new one.
    - `:start` is an event vector dispatched to the new actor as `[<spawned-id> <start>]`. Without it, the runtime dispatches `[<spawned-id> [:rf.machine.spawn/spawned]]`. Either way the actor's initial `:entry` actions run first.
    - A declarative `:spawn` state node accepts the same keys plus `:on-done`, `:on-error`, `:timeout` and `:on-timeout`, and stores the child's id in the parent's `:data` at `[:rf/spawned <invoke-id>]`. See [Actors](../machines/actors.md#spawn-spec-keys).
- **Errors**:
    - `:rf.error/machine-spawn-unregistered-type`: `:machine-id` names no registered machine and there is no `:definition`. Nothing is spawned.
    - `:rf.error/machine-spawn-bad-shape`: an inline `:definition` names neither `:id-prefix` nor `:fixed-actor-id`, so the actor would have no id. The effect handler throws, the effect runner reports it as `:rf.error/fx-handler-exception`, and nothing is spawned.
- **Example**:
  ```clojure
  (rf/reg-event :session/start-logger
    (fn [_ _]
      {:fx [[:rf.machine/spawn
             {:machine-id     :machines/log-shipper
              :fixed-actor-id :logger      ;; a well-known address the app holds
              :data           {:buffer []}
              :start          [:logger/connect]}]]}))

  ;; Address the actor by the id you chose.
  (rf/reg-event :session/flush-logs
    (fn [_ _]
      {:fx [[:dispatch [:logger [:logger/flush]]]]}))
  ```

### `[:rf.machine/destroy actor-id]`

- **Kind**: effect (reserved fx-id)
- **Payload**: `actor-id`.
- **Description**: Stops an actor. It runs the `:exit` actions of the actor's active states, cancels its pending `:after` timers and removes its snapshot from `[:rf.runtime/machines :snapshots actor-id]` in `runtime-db`.
    - It also aborts the actor's in-flight `:rf.http/managed` requests and releases any resources the actor owns. Hold anything else the actor uses, such as a websocket or an interval, in a custom effect handler keyed by the actor's id, and have the actor's `:exit` action return the close effect: `:exit` runs, and its effects execute, on every destroy path.
    - If the actor has its own event-handler registration, that is removed too. A spawned actor has none: it exists for as long as its snapshot does.
    - Destroying an actor that is already gone does nothing.
    - A declarative child rarely needs it: the runtime destroys it when its parent leaves the spawning state or is destroyed, and when it enters a root-level `:final?` state (see [Final states](#final-states-and-on-done)). An actor you started with `:rf.machine/spawn` has no parent state to end it, so emit this effect when you are done with it, unless it finishes by entering a root-level `:final?` state.
- **Example**:
  ```clojure
  (rf/reg-event :session/stop-logger
    (fn [_ _]
      {:fx [[:rf.machine/destroy :logger]]}))
  ```

### `[:rf.machine/update-snapshot patch]`

- **Kind**: effect (reserved fx-id)
- **Payload**: `{:rf/machine-id <id> :rf/patch {:state … :meta … :data {…}}}`, each `:rf/patch` key optional.
- **Description**: Writes a machine's snapshot directly, without taking a transition. A machine action can return only `:data` and `:fx`; emit this from the action's `:fx`, or from any event handler, when you must also set `:state` or `:meta` in the same atomic write. Prefer a transition where one will do: this effect does not check that a patched `:state` exists in the machine's definition.
    - `:state` and `:meta` replace the snapshot's values. `:data` is merged into the existing `:data`, as an action's `:data` return is, so the runtime's own `:rf/*` keys in it survive.
    - Other `:rf/patch` keys are ignored. A `:db` key emits `:rf.error/machine-action-wrote-db` and is dropped; the rest of the patch is still written.
    - Does nothing when the machine has no snapshot (not started, or destroyed).
    - The `:data` patch is validated against the machine's `[:schemas :data]` schema before it is written, by [`validate-update-snapshot-data!`](#re-framemachinesvalidate-update-snapshot-data). A patch that fails is not written, so this effect is subject to the `:where :machine-data` boundary like a transition.
- **Example**:
  ```clojure
  ;; Move :session to :anonymous and reset a counter in one write.
  {:fx [[:rf.machine/update-snapshot {:rf/machine-id :session
                                      :rf/patch      {:state :anonymous
                                                      :data  {:retries 0}}}]]}
  ```

### `[:raise event-vec]`

- **Kind**: effect (reserved fx-id, machine actions only)
- **Payload**: `event-vec`, an event for the same machine.
- **Description**: Sends `event-vec` back into the same machine within the current macrostep (the machine's handling of one dispatched event, including every raised event and `:always` step it leads to), before the snapshot commits. Only a machine action's `:fx` can use it; there is no `:raise` effect handler outside machines. See [Raise and internal events](../machines/concepts.md#raise-and-internal-events).
    - Use it when an action decides the machine's next step. `[:dispatch [machine-id event]]` handles the event later, as a separate event, after this one's snapshot has committed. A raised event is handled within this macrostep, before anything else, and the snapshot commits once.
    - An event listed in the machine's `:internal-events` can only be raised. Dispatching one from outside emits `:rf.error/machine-internal-event-external-dispatch` and changes nothing.
    - Raises are depth-bounded: 16 by default, or the machine spec's `:raise-depth-limit`. Exceeding the bound aborts the whole macrostep with `:rf.error/machine-raise-depth-exceeded`.
- **Example**:
  ```clojure
  {:actions {:kick (fn [_] {:fx [[:raise [:tick]]]})}}
  ```

## Final states and `:on-done`

A machine finishes by entering a `:final?` leaf that is a direct child of its root. The runtime then destroys it, so you do not emit `:rf.machine/destroy`. A singleton's registration survives: only its snapshot is removed, and its next event starts it again from `:initial`. Use `:final?` for work that ends, such as a request actor. A resting state that views still read, such as `:authed`, stays a plain leaf.

A child started by a `:spawn` or `:spawn-all` state reports to its parent by finishing; it dispatches nothing itself. (An actor started with a hand-emitted `:rf.machine/spawn` has no parent to report to.) The runtime sends the parent a `:rf.machine.spawn/done` event, handled in the parent's ordinary macrostep: the parent receives the result through `:on-done`, and can also transition on it with a transition-shaped `:on-done`, an `:always`, or `:on {:rf.machine.spawn/done …}`. A child that fails arrives as `:rf.machine.spawn/error` instead.

A `:final?` leaf nested inside a compound state finishes only that compound. The machine keeps running, and the compound's own `:on-done` names the state to move to; see [nested final states](../machines/hierarchical-states.md#when-a-sub-flow-finishes-nested-final-states). A `:type :parallel` machine finishes when every region's active state is `:final?`.

| State-node key | What it does |
|---|---|
| `:final?` | Marks a leaf state as terminal. Directly under the root, entering it finishes and destroys the machine; nested in a compound state, it finishes that compound. |
| `:error?` | Requires `:final?`, or registration throws `:rf.error/machine-error-flag-without-final`. Marks the terminal state as a failure: the parent's `:spawn` `:on-error` transition fires instead of `:on-done`, and under a `:spawn-all` join the child counts as failed. |
| `:output-key` | Requires `:final?`, or registration throws `:rf.error/machine-output-key-without-final`. Names the child's `:data` slot that is reported to the parent's `:on-done`. |

On a parent's `:spawn` map, `:on-done` fires when the child enters a non-error `:final?` state, and is applied on the parent's next macrostep rather than inside the child's teardown. `result` is the child's `:data` slot named by the final state's `:output-key`, or `nil`. It takes one of two forms:

- An `:on`-shaped transition that moves the parent: `{:target :loaded :action …}`, a keyword or path target, or guarded candidates (XState's `invoke onDone`). It resolves at the spawning state's level, like `:on-error`, and the result is at `(:result (nth ev 2))`.
- A function `(fn [{:keys [data result]}] new-data)` that folds the result into the parent's `:data`. On a `:spawn-all` child spec, this is the only form accepted.

Any other value throws `:rf.error/machine-bad-on-done-clause` at registration. See [Final states](../machines/concepts.md#final-states) in the guide.

## Cross-machine messaging

To send an event to another machine, dispatch it at that machine's id: `[:dispatch [<actor-id> <event>]]`. There is no separate name registry.

A child spawned declaratively has its id stored in the parent's `:data` at `[:rf/spawned <invoke-id>]`, so the parent reads the address from its own snapshot. A hand-emitted `:rf.machine/spawn` has no invoke-id, so the spawner chooses a fresh keyword, passes it as `:fixed-actor-id` and stores it in ordinary `:data`.

## Querying registered machines

There is no `machines` or `machine-meta` function. A machine is an `:event` registration carrying `:rf/machine? true`, so you list machines and read their specs with the generic registrar queries, [`registrations`](re-frame.core.md#registrations) and [`handler-meta`](re-frame.core.md#handler-meta).

- To list every registered machine id, filter the `:event` registrations on `:rf/machine?`:
  ```clojure
  (keys (into {} (filter (fn [[_ m]] (:rf/machine? m)))
              (rf/registrations {:source :store :kind :event})))
  ;; → (:session :auth.login/flow …)
  ```
  This lists registered machines. A spawned actor has no registration of its own, so read live actors from the snapshots map at `[:rf.runtime/machines :snapshots]` in the frame's `runtime-db`.
- To read one machine's spec, take the `:rf/machine` key of its registration. It holds the transition table, `:doc`, `:schemas` and per-element source coordinates, and is `nil` unless the `:event` registration is a machine:
  ```clojure
  (:rf/machine (rf/handler-meta {:source :store :kind :event :id :session}))

  ;; Just the declared :data schema:
  (get-in (rf/handler-meta {:source :store :kind :event :id :session})
          [:rf/machine :schemas :data])
  ```

## Plain-function registration and testing

### `re-frame.machines/reg-machine*`

- **Kind**: function
- **Signature**:
  ```clojure
  (re-frame.machines/reg-machine* machine-id machine-spec)
  (re-frame.machines/reg-machine* machine-id opts machine-spec)
  ```
- **Description**: Registers a machine like `reg-machine`, as a plain function that captures no source. Use it when the spec is built at runtime: generated code, the REPL, test harnesses.
    - The 3-arity takes the same `opts` map as `reg-machine`, with the same `:schema` behaviour and the same errors.
    - Because the spec carries no source, development builds warn `:rf.warning/machine-source-unstamped` once per machine id.
- **Example**:
  ```clojure
  (rf.machines/reg-machine* :traffic-light
    {:initial :red
     :states  {:red   {:on {:go {:target :green}}}
               :green {:on {:go {:target :red}}}}})
  ```

### `re-frame.machines/make-machine-handler`

- **Kind**: function
- **Signature**:
  ```clojure
  (re-frame.machines/make-machine-handler spec) → event-handler fn
  ```
- **Description**: Compiles a transition table into the event-handler function that `reg-machine` would register, and returns it without registering it.
    - A spec with a `[:schemas :data]` schema throws `:rf.error/machine-schema-requires-reg-machine`. This path does not record the `:rf/machine` registration metadata the schema check reads, so the schema would validate nothing. Register such a machine with `reg-machine` or `reg-machine*`.
- **Example**:
  ```clojure
  ;; Build the handler fn without registering it (e.g. to inspect or compose it).
  (def handler
    (rf.machines/make-machine-handler
      {:initial :idle
       :states  {:idle    {:on {:start {:target :running}}}
                 :running {}}}))
  ```

### `re-frame.machines/machine-transition`

- **Kind**: function
- **Signature**:
  ```clojure
  (re-frame.machines/machine-transition definition snapshot event)
  → {:status :ok    :snapshot next-snapshot :fx [effect …] :handled? boolean}
  | {:status :error :error {:kind error-id …}}
  ```
- **Description**: Runs one transition as a pure function: given a machine definition, a current snapshot and an event, it returns a plain map. Use it to unit-test a transition table. It runs on the JVM and needs no frame; `re-frame.machines` is the only namespace to require.
    - `:status :ok` carries the new `:snapshot` and the ordered effects vector `:fx`. The effects are described, never run. `:fx` also holds the runtime's own effects, such as one `[:rf.machine/after-schedule …]` for each `:after` timer the new state arms, so assert on the entries you care about rather than on the whole vector. An event that no transition matches returns `:ok` with the snapshot unchanged and `:fx []`. `:handled?` is `true` when the event selected a transition, even a targetless one that changed nothing, and `false` when nothing took it, so a test can tell a declined event from an accepted no-op.
    - `:status :error` reports a failed macrostep. Either a guard, action or `:data` function threw (`:kind :rf.error/machine-action-exception`, with `:exception` and the throwing ref), or a depth limit tripped (`:kind :rf.error/machine-always-depth-exceeded` or `:rf.error/machine-raise-depth-exceeded`). A failure carries no snapshot, because the macrostep is atomic.
    - Mistakes in the input, such as a malformed `:state` or a guard or action ref with no entry, throw the same `:rf.error/*` `ex-info` the registration checks throw rather than returning a result.
- **Example**: `login-flow` is the definition built in the guide's [first machine](../machines/tutorial.md#the-complete-machine).
  ```clojure
  (require '[clojure.test :refer [is]]
           '[re-frame.machines :as rf.machines])

  (let [{:keys [status snapshot fx]}
        (rf.machines/machine-transition login-flow
                                     {:state :idle :data {}}
                                     [:auth.login/submit {:email "a@b.com" :password "secret"}])]
    (is (= :ok status))
    (is (= :submitting (:state snapshot)))
    (is (= :rf.http/managed (ffirst fx))))   ;; the :submitting :entry fired the request
  ```

## Framework integration

Not for application code — used by adapters, tools and the test harness.

### Tooling (JVM)

These two functions are JVM-only aliases on `re-frame.machines` for functions in `re-frame.machines.tooling`. ClojureScript tools require `re-frame.machines.tooling` and call them there. `re-frame.machines` does not require the tooling namespace on ClojureScript, so an app that loads no tools drops that code from its build.

The static and live machine views that Xray draws have no public accessor: tools require `re-frame.machines.tooling` directly. The framework has no `machine->xstate-json`, `machine->mermaid` or Stately exporter; those are in the separate `day8/re-frame2-machines-viz` library.

#### `re-frame.machines/machine-selector?`

- **Kind**: function
- **Signature**:
  ```clojure
  (re-frame.machines/machine-selector? sub-id) → boolean
  ```
- **Description**: Returns `true` when the subscription registered under `sub-id` reads a machine: an ordinary `reg-sub` whose literal `:inputs` include a `[:rf/machine …]` or `[:rf.machine/has-tag? …]` query vector. Such subscriptions are ordinary `:derivation` subscription nodes; this lets a graph tool mark them. JVM-only.
- **Example**:
  ```clojure
  (rf.machines/machine-selector? :session/summary)  ;; => true / false
  ```

#### `re-frame.machines/machine-selector-targets`

- **Kind**: function
- **Signature**:
  ```clojure
  (re-frame.machines/machine-selector-targets sub-id) → #{machine-id …}
  ```
- **Description**: Returns the set of machine ids that the subscription registered under `sub-id` reads, taken from the second element of each `[:rf/machine machine-id …]` or `[:rf.machine/has-tag? machine-id …]` literal `:inputs` entry. A graph tool uses it to draw the edges `machine-selector?` only detects. JVM-only.
- **Example**:
  ```clojure
  (rf.machines/machine-selector-targets :session/summary)  ;; => #{:session}
  ```

### Effect handlers

These are the handlers this namespace registers for the reserved `:rf.machine/*` effect ids. Application code emits the effect (see [Keyword surfaces](#keyword-surfaces)) and does not call these. Each takes `(fx-ctx args)`, and the frame is the fx context's `:frame`. Because they are registered here, an app that does not use the artefact carries none of their code or trace strings.

#### `re-frame.machines/spawn-fx`

- **Kind**: function
- **Signature**:
  ```clojure
  (re-frame.machines/spawn-fx fx-ctx spawn-spec)
  ```
- **Description**: The handler for `:rf.machine/spawn`. Installs the new actor's snapshot at `[:rf.runtime/machines :snapshots <spawned-id>]` in the spawning frame's `runtime-db`.
    - It stores the machine type in the snapshot under `:rf/machine-type`, so the runtime and epoch restore can rebuild the actor from `runtime-db` alone. The actor has no event-handler registration of its own; it exists while that snapshot does.
    - A `:machine-id` that names no registered machine, with no inline `:definition`, emits `:rf.error/machine-spawn-unregistered-type` and installs nothing.

#### `re-frame.machines/spawn-all-init-fx`

- **Kind**: function
- **Signature**:
  ```clojure
  (re-frame.machines/spawn-all-init-fx fx-ctx args)
  ```
- **Description**: The handler for `:rf.machine/spawn-all-init`, which the runtime emits beside the per-child `:rf.machine/spawn` effects when a `:spawn-all` state is entered. It seeds the join state at `[:rf.runtime/machines :spawned <parent> <invoke-id>]` as `{:children {…} :done #{} :failed #{} :resolved? false :spec …}`. When a child reaches a final state, `:error? true` or not, the result is folded into the join state and resolves the join; children dispatch nothing to the parent themselves.

#### `re-frame.machines/destroy-machine-fx`

- **Kind**: function
- **Signature**:
  ```clojure
  (re-frame.machines/destroy-machine-fx fx-ctx args)
  ```
- **Description**: The handler for `:rf.machine/destroy`. It chooses the teardown from the shape of `args`: a single actor (the keyword form, or a single `:spawn`), or every child of a `:spawn-all`. It runs the actor's `:exit` actions, clears its `[:rf.runtime/machines :snapshots <actor-id>]` slot and removes its event-handler registration.

#### `re-frame.machines/after-schedule-fx`

- **Kind**: function
- **Signature**:
  ```clojure
  (re-frame.machines/after-schedule-fx fx-ctx args)
  ```
- **Description**: The handler for `:rf.machine/after-schedule`, which the runtime emits once per `:after` entry when a state with `:after` is entered. It resolves the delay and schedules a wall-clock timer through the clock abstraction.
    - The delay can be a literal `pos-int?`, an ISO-8601 duration string such as `"PT5S"`, a subscription vector, or `(fn [{:keys [snapshot]}] ms)`. For a subscription delay it also adds a watch that cancels and reschedules the timer when the subscription's value changes.
    - On expiry it dispatches `[<parent-id> [:rf.machine.timer/after-elapsed <delay-key> <epoch> <decl-path>]]`, which takes effect only if the scheduling state is still active and the epoch matches.

#### `re-frame.machines/after-cancel-fx`

- **Kind**: function
- **Signature**:
  ```clojure
  (re-frame.machines/after-cancel-fx fx-ctx args)
  ```
- **Description**: The handler for `:rf.machine/after-cancel`. Cancels a previously scheduled `:after` timer for a machine state.

### Validators

These run the registration-time checks and the `:data` schema checks. The three `:data` validators run only in development builds; in production builds they skip validation and return `true`.

#### `re-frame.machines/validate-machine!`

- **Kind**: function
- **Signature**:
  ```clojure
  (re-frame.machines/validate-machine! machine)
  ```
- **Description**: Runs every registration-time check on a machine definition and throws on a violation. `make-machine-handler` calls it first, so every registration runs it.
    - It checks history-state placement, the closed key set, the at-most-one-per-compound rule, `:default-target` resolution, the region shape of `:type :parallel`, and top-level dispatch plus guard and action ref resolution.
    - `:regions` is accepted only on a `:type :parallel` node, because regions only run on a `:type :parallel` root. On a flat or compound root it throws `:rf.error/machine-root-slot-not-supported` before any other check reads the root; the error's `:offending-keys` lists every root key the runtime does not read there. On a state it throws `:rf.error/machine-unknown-node-key`.
    - Errors include `:rf.error/machine-history-misplaced`, `-history-extra-keys`, `-history-duplicate` and `-history-bad-default-target`, `:rf.error/machine-unknown-node-key`, `:rf.error/machine-root-slot-not-supported`, and `:rf.error/machine-unresolved-guard` / `-unresolved-action`.
    - The conformance corpus tests its registration errors against this function.

#### `re-frame.machines/validate-machine-data!`

- **Kind**: function
- **Signature**:
  ```clojure
  (re-frame.machines/validate-machine-data! runtime-db event-id frame-id) → boolean
  (re-frame.machines/validate-machine-data! runtime-db event-id frame-id continue?)
  ```
- **Description**: Validates the `:data` of every snapshot under `[:rf.runtime/machines :snapshots]` in `runtime-db` against its machine's `[:schemas :data]` schema. This is the `:where :machine-data` boundary: the router runs it with `validate-app-schema!` against the candidate `runtime-db`, before commit.
    - Returns `true` when every snapshot conforms or has no schema or validator, and `false` otherwise. It validates every snapshot without stopping at the first failure, so each failing machine emits its own trace. On `false` the router rejects the whole candidate, as it does for a `:where :app-db` failure.
    - It finds the schema for a registered machine through the `:rf/machine` registration, and for a spawned actor through the snapshot's `:rf/machine-type`.
    - The 4-arity takes `continue?`, a predicate that is true while the owning frame is still current; the 3-arity builds it from the frame. If the owning frame is destroyed or replaced during validation, the function returns `:rf/stale-incarnation` instead of a boolean.

#### `re-frame.machines/validate-spawn-data!`

- **Kind**: function
- **Signature**:
  ```clojure
  (re-frame.machines/validate-spawn-data! spawned-id spec snapshot) → boolean
  (re-frame.machines/validate-spawn-data! spawned-id spec snapshot continue?)
  ```
- **Description**: Validates a new actor's initial snapshot `:data` against its machine's `[:schemas :data]` schema before `:rf.machine/spawn` installs it. Returns `true` on conform, no schema or no validator. Returns `false` on failure, and the spawn installs nothing. Nothing was committed, so the failure trace has `:phase :spawn` and `:rollback? false`. The 4-arity `continue?` and the `:rf/stale-incarnation` return work as for `validate-machine-data!`.

#### `re-frame.machines/validate-update-snapshot-data!`

- **Kind**: function
- **Signature**:
  ```clojure
  (re-frame.machines/validate-update-snapshot-data! machine-id merged-snapshot) → boolean
  ```
- **Description**: Validates the `:data` of the snapshot that `:rf.machine/update-snapshot` would produce, against the machine's `[:schemas :data]` schema, before the effect writes it. Returns `true` on conform, no schema or no validator, and the effect writes the patch. Returns `false` on failure, and the effect skips the write.

### Runtime and lifecycle

#### `re-frame.machines/install-machine-runtime!`

- **Kind**: function
- **Signature**:
  ```clojure
  (re-frame.machines/install-machine-runtime!)
  ```
- **Description**: Registers the machine effects and subscriptions again, in both the registrar and the framework-standard registry, so an image-loaded frame can resolve `[:rf.machine/spawn …]` and `[:rf/machine …]`. Idempotent.
    - It works from descriptors captured when the namespace loaded, so it restores them even after a test fixture has cleared the registrar. It does for machines what the standard re-seed does for `:rf/set-db`.
    - It runs when the namespace loads and from the shared reset fixture. Tests that clear the registrar call it directly.

#### `re-frame.machines/reset-timers!`

- **Kind**: function
- **Signature**:
  ```clojure
  (re-frame.machines/reset-timers!)
  (re-frame.machines/reset-timers! frame-id)
  ```
- **Description**: Cancels in-flight `:after` timers.
    - The 0-arity clears every frame's timers. Test teardown uses this form: the fixture built by [`make-reset-runtime-fixture`](re-frame.test-support.md#make-reset-runtime-fixture), and the per-artefact test fixtures.
    - The 1-arity clears one frame's timers. Destroying a frame calls this form, releasing that frame's host clock handles and subscription watches without touching other frames.
    - Spawn-id counters reset with the registrar and frame reset, so this function only clears the per-frame timer table.

#### `re-frame.machines/owning-actor-id`

- **Kind**: function
- **Signature**:
  ```clojure
  (re-frame.machines/owning-actor-id frame-id event-id) → actor-id or nil
  ```
- **Description**: Returns `event-id` when it is the address of a spawned actor whose snapshot is installed at `[:rf.runtime/machines :snapshots <event-id>]` in `frame-id`, and `nil` otherwise, meaning the event belongs to an ordinary handler or a registered machine.
    - Membership is decided by `:rf/machine-type` at the snapshot root, so it covers declarative `:spawn` / `:spawn-all` actors and actors started with `[:rf.machine/spawn …]`.
    - Managed HTTP uses it to find which actor owns a request's originating event, so it can abort the request when that actor is destroyed, without requiring this artefact. Without the machines artefact, HTTP treats every request as unowned.

## See also

- [re-frame.core](re-frame.core.md): `dispatch`, `subscribe` and `reg-event`, which drive and read a machine.
- [re-frame.schemas](re-frame.schemas.md): machines declare a `:data` schema the same way handlers declare theirs.
- [Glossary](../machines/glossary.md): the machines vocabulary in one place.
- [Coming from XState](../machines/coming-from-xstate.md): what differs from XState v6.
