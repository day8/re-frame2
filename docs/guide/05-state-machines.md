# 05 — State machines

Some flows are naturally a sequence of states.

A login form moves from `:idle` to `:submitting` to `:authed` (success) or `:error-shown` (try again) or `:locked-out` (too many tries). A video player goes from `:loading` to `:playing` to `:paused` to `:buffering` to `:ended`. A checkout wizard moves through `:cart` → `:shipping` → `:payment` → `:confirmation` → `:processed`. A websocket connection cycles through `:connecting`, `:connected`, `:disconnected`, `:reconnecting`.

When a flow has this shape, the load-bearing question isn't "what's in `app-db`" — it's "what state are we in, and what events take us to which state?" That question has a name in computer science: **finite state machine**.

re-frame2 supports state machines as a first-class pattern. Not because every event handler should be a machine — most shouldn't — but because when the answer to "what's the next state?" is the central question, expressing the flow as a machine is dramatically clearer than expressing it as a tree of `if`/`when`/`case`.

## A familiar example

Without state machines, the login flow looks something like this:

```clojure
(rf/reg-event-fx :auth/submit
  (fn [{:keys [db]} [_ creds]]
    (cond
      (= :submitting (:auth/state db))
      ;; ignore — already submitting
      {}

      (>= (:auth/attempts db) 3)
      {:db (assoc db :auth/state :locked-out)
       :fx [[:http {:url "/api/lock-account"}]]}

      :else
      {:db (-> db
               (assoc :auth/state :submitting)
               (update :auth/attempts inc))
       :fx [[:http {:url "/api/login"
                    :body creds
                    :on-success [:auth/login-success]
                    :on-error   [:auth/login-error]}]]})))

(rf/reg-event-db :auth/login-success
  (fn [db [_ resp]]
    (-> db
        (assoc :auth/state :authed)
        (assoc :auth/user (:user resp)))))

(rf/reg-event-db :auth/login-error
  (fn [db [_ err]]
    (cond
      (>= (:auth/attempts db) 3)
      (assoc db :auth/state :locked-out)

      :else
      (-> db
          (assoc :auth/state :error-shown)
          (assoc :auth/error err)))))

;; ... and an :auth/dismiss handler, and an :auth/reset handler, ...
```

This works. It's correct. But there are a few things to notice:

1. **The state-transition rules are scattered.** The fact that `:submitting` is reachable from `:idle` (and not from `:locked-out`) is implicit in the `cond` clauses. To know the full state graph, you have to read every event handler.

2. **The retry-limit logic is duplicated.** It appears in `:auth/submit` and again in `:auth/login-error`. If you ever need to change "3 attempts" to "5 attempts," you have to remember to change it in both places.

3. **Adding a new state is a chore.** Suppose we want to add a `:two-factor` state between `:submitting` and `:authed`. That's a new keyword in `:auth/state` and a new handler — but also revisions to every existing handler that touches `:auth/state`, because each one has implicit assumptions about which states are valid where.

The fix isn't to write better `cond` clauses. The fix is to step back and notice: **what we're trying to write is a state machine.**

## The same flow as a machine

```clojure
(def login-flow
  {:initial :idle
   :data    {:attempts 0 :error nil}
   :guards  {:under-retry-limit (fn [{:keys [data]} _]
                                  (< (:attempts data) 3))}
   :actions {:clear-error    (fn [{:keys [data]} _]
                               {:data (assoc data :error nil)})
             :issue-request  (fn [_ [_ creds]]
                               {:fx [[:http {:method :post :url "/api/login"
                                             :body creds
                                             :on-success [:auth.login [:success]]
                                             :on-error   [:auth.login [:failure]]}]]})
             :record-error   (fn [{:keys [data]} [_ err]]
                               {:data (-> data
                                          (update :attempts inc)
                                          (assoc :error (:message err)))})
             :store-session  (fn [{:keys [data]} [_ {:keys [user]}]]
                               {:data (assoc data :user user)
                                :fx   [[:localstorage/set
                                        {:key "session" :val user}]]})
             :lock-account   (fn [_ _]
                               {:fx [[:http {:url "/api/lock-account"}]]})}
   :states
   {:idle
    {:on {:submit {:target :submitting :actions [:clear-error]}}}

    :submitting
    {:entry [:issue-request]
     :on    {:success {:target :authed :actions [:store-session]}
             :failure [{:target :error-shown
                        :cond   :under-retry-limit
                        :actions [:record-error]}
                       {:target :locked-out
                        :actions [:lock-account]}]}}

    :error-shown
    {:on {:dismiss {:target :idle}
          :submit  {:target :submitting}}}

    :authed      {:meta {:terminal? true}}
    :locked-out  {:meta {:terminal? true}}}})
```

This is a **transition table**. It's pure data. You can read it top to bottom and see, immediately:

- Five states: `:idle`, `:submitting`, `:error-shown`, `:authed`, `:locked-out`.
- The starting state is `:idle`.
- From `:idle`, the event `:submit` takes us to `:submitting`.
- From `:submitting`, the event `:success` takes us to `:authed`.
- From `:submitting`, the event `:failure` takes us to `:error-shown` if the guard `:under-retry-limit` is true, otherwise to `:locked-out`.
- The actions on each transition (`:clear-error`, `:issue-request`, `:record-error`, `:lock-account`, `:store-session`) are referenced by keyword. They resolve into the machine's own `:guards` / `:actions` map — there is no global guard/action registry; each machine carries its own.

The whole flow is in one piece of data. You can pretty-print it. You can render it to a graph. You can check it against a schema. You can hand it to an AI and say "here's the auth flow, add a two-factor state between submitting and authed" — the AI has the whole context in front of it.

The runtime takes this data plus the snapshot of the machine's current state and applies the transition rules. The runtime — not the developer — is responsible for "if we're in :submitting and a :failure arrives, check the guard, fire the action, transition to the right next state." The developer is responsible only for *describing the transitions as data*.

## What's in a transition

The grammar is borrowed (with adaptation) from [xstate](https://xstate.js.org/), which is the canonical state-machine library in the JavaScript world. Specifically:

- **`:initial`** — the starting state.
- **`:data`** — extended state that lives alongside the discrete state. Counters, error messages, transient data. (xstate calls this slot `:context`; we use `:data` because re-frame already has a "context" — the interceptor pipeline's coeffect map. The pair `{:state :data}` is the snapshot.)
- **`:guards`** / **`:actions`** — machine-scoped maps of named predicate / action functions. Transitions reference them by keyword (`:cond :under-retry-limit`, `:actions [:clear-error]`); the runtime resolves the keyword against this machine's `:guards` / `:actions` map. Each machine has its own namespace; there is no global guard/action registry.
- **`:states`** — the state nodes, keyed by name.
- **`:on`** — for a state, the events it responds to and where they go.
- **`:target`** — the next state name.
- **`:cond`** — a guard: a predicate that must return true for the transition to fire.
- **`:actions`** (inside a transition) — actions that fire on this transition.
- **`:entry`/`:exit`** — actions that fire when entering or leaving a state, regardless of which event triggered the transition.
- **`:meta`** — arbitrary user-defined annotations (e.g. `:terminal?`).

xstate users will recognise all of this. We deliberately stay close to xstate's vocabulary because (a) it's well-thought-out and (b) AIs already know it. When you ask an AI "give me the state machine for a login flow," it produces something that's already nearly correct in re-frame2's grammar.

What we adapt:

- **Machines are event handlers, not actor objects.** xstate has explicit `ActorRef` runtime objects with their own mailboxes. re-frame2 doesn't. A machine is "an event handler whose body interprets a transition table." All events to that machine flow through `dispatch` like any other event; there's no separate sending mechanism.
- **Effects are returned as data.** xstate's actions can directly call out to the world. re-frame2's actions return effect maps which the runtime interprets — same shape as event handlers.
- **The actor-system boundary is the frame.** Multiple machines composed within a frame share that frame's drain, ensuring run-to-completion behaviour. Cross-frame communication is async dispatch, not in-process message passing.

These adaptations keep the pattern consistent with the rest of re-frame2. A state machine, in this codebase, is *the same kind of thing as an event handler*, just with more structure.

## What the substrate covers

The shape above — flat states, plain `:on` transitions, `:entry`/`:exit` actions — is enough for many machines. When the flow gets richer, the substrate has more to offer without changing the model. A flavour:

- **Hierarchical states.** A compound state contains sub-states; entering the parent cascades to its declared `:initial` child; transitions from a deep state can target a sibling or an ancestor and the runtime computes the LCA. Useful when the auth flow has an `:authenticated` super-state with `:cart` / `:browsing` sub-states under it.
- **Eventless `:always` transitions.** A state can fire a transition on entry (or after every event) when a guard becomes true — no event needed. Useful for "drain a queue, transition when empty" or "advance through derived states." Bounded depth, microstep-loop semantics, defined trace events.
- **Delayed `:after` transitions.** A state can declare "if no event arrives within N ms, transition to this state." Useful for retry-after-backoff, idle timeouts, debounce-shaped flows. Carries an epoch so cancelled timers don't fire late.
- **Declarative `:invoke`.** A state can spawn a child machine on entry and destroy it on exit, declared as data rather than as `:entry [:spawn ...]` / `:exit [:destroy-machine ...]`. The child's lifecycle is bound to the parent state.
- **Machine-scoped `:guards` and `:actions` maps.** Guards and actions referenced by keyword resolve into a machine-local map — `(get-in spec [:guards :under-retry-limit?])`. Named compound logic is more inspectable than inline lambdas, and it lives where the machine lives, not in the global registry.

Each of these is opt-in — implementations declare which capabilities they support, and the conformance corpus grades against the claimed set. The full grammar is in [Spec 005](../specification/005-StateMachines.md). The point of mentioning them here is that the model scales: when your machine grows, the substrate has well-named answers ready, and you don't end up smuggling state-machine logic into ordinary event handlers.

## Reading a machine: `sub-machine`

Views that need to read a machine's current state read it through a sub:

```clojure
@(rf/sub-machine :auth.login)
;; → {:state :submitting :data {:attempts 1 :error nil}}
```

`sub-machine` is sugar over the framework-shipped `:rf/machine` sub (`(rf/subscribe [:rf/machine :auth.login])` is the explicit form). It returns the snapshot — `{:state :data}` — or `nil` if the machine hasn't been initialised yet. Views typically destructure the `:state` and switch on it; complex UIs read the `:data` slot too. This is the single supported read path; there's no separate "actor reference" object to thread around.

For finer granularity, write derived subs against `:rf/machine` like any other `:<-` chain:

```clojure
(rf/reg-sub :auth/state
  :<- [:rf/machine :auth.login]
  (fn [{:keys [state]} _] state))
```

## Patterns that bottom out in machines

Two of the recurring shapes from [chapter 03](03-events-state-cycle.md) end up being state machines underneath:

- [Pattern-WebSocket](../specification/Pattern-WebSocket.md) — a long-lived connection has phases (`:disconnected`, `:connecting`, `:connected`, `:reconnecting`, `:failed`), retry-with-backoff via `:after`, queued sends while disconnected, server-pushed events. The whole connection lifecycle is naturally a machine; messages flowing over an open connection are ordinary async-effect interactions.
- [Pattern-Boot](../specification/Pattern-Boot.md) — multi-step initialisation with sequential dependencies, visible progress, fail-fatal points, and recoverable retry. For trivial boots a chained event sequence works; for non-trivial boots the canonical answer is a boot machine.

Both are pattern docs (convention), not Specs (contract). When the shape of a feature you're building is one of these, read the pattern doc and copy the shape.

## Wiring a machine into the rest of re-frame

The machine **is** the event handler. Two equivalent registration forms:

```clojure
;; Convenience form — one call, machine registered under its own id.
(rf/reg-machine :auth.login login-flow)

;; Equivalent explicit form — passes through reg-event-fx with metadata.
(rf/reg-event-fx :auth.login
  {:doc "Login flow as a state machine."}
  (rf/create-machine-handler login-flow))
```

That's the whole wiring. The runtime owns the snapshot's location — it lives at `[:rf/machines :auth.login]` in the frame's `app-db`, and you don't pick the path. The snapshot is a small map: `{:state :submitting :data {:attempts 1 :error nil}}`. `create-machine-handler` returns a pure event-handler fn that reads the snapshot, runs `machine-transition`, writes the new snapshot, and returns the action effects.

Dispatching to the machine is dispatching to its event id, with the machine event nested:

```clojure
(rf/dispatch [:auth.login [:submit {:email "..." :password "..."}]])
```

The runtime folds extra args so the machine sees the inner event `[:submit creds]` and routes it through the `:on` map.

## Guards and actions live inside the machine

Notice in the `login-flow` definition above that `:guards` and `:actions` are **maps inside the machine spec**, not separate top-level registrations. Each machine carries its own guard/action namespace — keywords resolve via `(get-in spec [:guards :under-retry-limit])` / `(get-in spec [:actions :issue-request])`. There is no `reg-machine-guard` / `reg-machine-action`; the inline form is the form.

Inline `(fn ...)` values are also first-class — `:cond (fn [...] ...)` skips the named-lookup. Use the named form for testability and tool introspection; use inline for one-off lambdas.

Guards are predicates: `(fn [snapshot event] boolean)`. Actions return effect maps:

- `:data` — updates to the extended state. The runtime writes the returned `:data` back into the snapshot's `:data` slot.
- `:fx` — effects to fire (HTTP, navigation, follow-up dispatches).

The shape mirrors `reg-event-fx`'s return: in goes a value, out goes a description of state changes plus side-effects. The runtime interprets.

**Cross-machine reuse via Clojure vars.** When a guard or action is shared across machines, define it as a `defn` and reference the var from each machine's `:guards` / `:actions` map:

```clojure
(defn user-authenticated? [{:keys [data]} _]
  (some? (:user-id data)))

(def login-flow
  {... :guards {:auth? user-authenticated?} ...})

(def cart-flow
  {... :guards {:auth? user-authenticated?} ...})
```

## When to reach for a machine, and when not to

State machines aren't a hammer. Most events in most apps are not state machines — they're simple `(state, event) → state` updates with no flow structure. Don't dress them up as machines.

Reach for a machine when:

- **The flow has named, mutually-exclusive stages.** "We're either in this stage or that stage, not both." If your event handlers use `cond` to dispatch on a state field, that's the signal.
- **Transitions are conditional on guards.** If you have repeated `(when (some-condition? db) ...)` checks across multiple handlers, the conditions are guards in disguise.
- **The flow is non-trivial enough to draw on a whiteboard.** If you've sketched the state diagram on paper to think through a feature, the diagram is the machine. Encode it directly.

Don't reach for a machine when:

- **The "state" is actually just data.** A list with items isn't a machine. A counter isn't a machine. Don't add machinery you don't need.
- **There are only two stages.** A boolean `:loading?` flag is fine. Two-state machines are formally machines but practically overkill.
- **You're trying to enforce a sequence of operations** — that's a different problem (saga / workflow), and re-frame2's drain semantics handle the simple cases without a machine. Reach for machines only when *named states* are the load-bearing concept, not when *named operations* are.

## Headless testing

Because `machine-transition` is pure — and because actions/guards are registered functions — you can test a machine without dispatching anything:

```clojure
(deftest login-flow-happy-path
  (let [snapshot {:state :idle :data {:attempts 0 :error nil}}]
    (let [[s1 _fx] (rf/machine-transition login-flow snapshot
                                          [:submit {:email "a@b.com"
                                                    :password "..."}])]
      (is (= :submitting (:state s1))))

    (let [[s2 _fx] (rf/machine-transition login-flow
                                          {:state :submitting :data {:attempts 0}}
                                          [:success {:user {:id 1}}])]
      (is (= :authed (:state s2))))))

(deftest login-flow-lockout
  (let [snapshot {:state :submitting :data {:attempts 2}}]
    (let [[s _fx] (rf/machine-transition login-flow snapshot
                                         [:failure {:message "wrong creds"}])]
      ;; 2 prior + 1 current = 3, hits the guard, transitions to :locked-out
      (is (= :locked-out (:state s))))))
```

These tests run on the JVM. No browser. No network. No mocks. Each one runs in microseconds. You can have hundreds.

This is the testing experience for *flows that are non-trivial* — exactly the case where unit testing usually gets hard. With machines, it stays easy.

## Composing machines

Real apps have more than one machine: an auth machine, a checkout-wizard machine, a websocket-connection machine. They coexist within a frame, each with its own snapshot under `[:rf/machines <id>]` in `app-db`.

When machines need to talk to each other, they do so through dispatch — the auth machine's `:store-session` action might dispatch `[:user/load-profile]`, which is handled by a regular `reg-event-fx` handler that updates the user-profile slice. There's no special inter-machine messaging. It's all events, all going through the same queue, all running to completion.

The drain semantics matter here. If an action dispatches a child event, that child event runs *before* subscriptions update. So if a state machine moves through `:idle → :submitting → :authed → :loading-profile → :ready` in a single user click, the view sees only `:idle` (before) and `:ready` (after) — never any in-between flicker. The pattern's commitment to atomic state changes pays off most strongly in flows like this.

## The deeper claim

State machines are a small example of the broader thesis [chapter 08](08-the-dynamic-model.md) makes: **constrained execution models are easier to reason about than free-form ones**.

A finite state machine has, by construction, a small set of reachable states. You can enumerate them. You can prove things about transitions. You can render the whole flow as a diagram. You can ask "from this state, what can happen?" and the answer is bounded.

A pile of `if`/`cond` clauses spread across event handlers has none of these properties. The reachable states are implicit. The transitions are scattered. There's no diagram. The answer to "from this state, what can happen?" requires reading every handler that touches the state field.

The choice between them isn't a matter of style. It's a matter of which dynamic model you can hold in your head. Machines are smaller models. Smaller models are easier to debug, refactor, extend, and reason about.

When the flow has the shape of a machine, write a machine.

## Next

- [06 — The server side](06-server-side.md) — server-side rendering, hydration, and the `:platforms` story for fx that should only run in one place.
