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
       :fx [[:rf.http/managed
             {:request {:method :post :url "/api/lock-account"}}]]}

      :else
      {:db (-> db
               (assoc :auth/state :submitting)
               (update :auth/attempts inc))
       :fx [[:rf.http/managed
             {:request    {:method :post :url "/api/login" :body creds
                           :request-content-type :json}
              :on-success [:auth/login-success]
              :on-failure [:auth/login-error]}]]})))

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

   :guards
   {:under-retry-limit
    (fn [data _event] (< (:attempts data) 3))}

   :actions
   {:clear-error
    (fn [_ _] {:data {:error nil}})

    :issue-request
    (fn [_data [_ creds]]
      {:fx [[:rf.http/managed
             {:request    {:method :post
                           :url    "/api/login"
                           :body   creds
                           :request-content-type :json}
              :on-success [:auth.login/flow [:auth.login/success]]
              :on-failure [:auth.login/flow [:auth.login/failure]]}]]})

    :record-error
    (fn [data [_ err]]
      {:data (-> data
                 (update :attempts inc)
                 (assoc :error (or (:message err) "Login failed.")))})

    :lock-account
    (fn [_ _]
      {:fx [[:rf.http/managed
             {:request {:method :post :url "/api/auth/lock"}}]]})

    :store-session
    (fn [_data [_ {:keys [token]}]]
      {:fx [[:auth.session/store {:token token}]]})}

   :states
   {:idle
    {:on {:auth.login/submit {:target :submitting
                              :action :clear-error}}}

    :submitting
    {:entry :issue-request
     :on    {:auth.login/success {:target :authed
                                  :action :store-session}
             :auth.login/failure [{:target :error-shown
                                   :guard  :under-retry-limit
                                   :action :record-error}
                                  {:target :locked-out
                                   :action :lock-account}]}}

    :error-shown
    {:on {:auth.login/dismiss {:target :idle}
          :auth.login/submit  {:target :submitting}}}

    :authed      {:meta {:terminal? true}}
    :locked-out  {:meta {:terminal? true}}}})
```

This is a **transition table**. It's pure data. You can read it top to bottom and see, immediately:

- Five states: `:idle`, `:submitting`, `:error-shown`, `:authed`, `:locked-out`.
- The starting state is `:idle`.
- From `:idle`, the event `:auth.login/submit` takes us to `:submitting`.
- From `:submitting`, the event `:auth.login/success` takes us to `:authed`.
- From `:submitting`, the event `:auth.login/failure` takes us to `:error-shown` if the guard `:under-retry-limit` passes, otherwise to `:locked-out`.
- Each transition's `:action` and each state's `:entry` are referenced by id; the named guard / action lives in the spec's `:guards` / `:actions` map and runs when the runtime applies the transition.

The whole flow is in one piece of data. You can pretty-print it. You can render it to a graph. You can check it against a schema. You can hand it to an AI and say "here's the auth flow, add a two-factor state between submitting and authed" — the AI has the whole context in front of it.

The runtime takes this data, plus the snapshot of the machine's current state, and applies the transition rules. The runtime — not the developer — is responsible for "if we're in `:submitting` and a `:failure` arrives, check the guard, fire the action, transition to the right next state." The developer is responsible only for *describing the transitions as data*.

## What's in a transition

The grammar is borrowed (with adaptation) from [xstate](https://xstate.js.org/), which is the canonical state-machine library in the JavaScript world. Specifically:

- **`:initial`** — the starting state.
- **`:data`** — extended state that lives alongside the discrete state. Counters, error messages, transient data. (xstate calls this slot "context"; we use `:data` to avoid the existing "context" overloading in re-frame's interceptor pipeline and React-context idioms.)
- **`:states`** — the state nodes, keyed by name.
- **`:on`** — for a state, the events it responds to and where they go.
- **`:target`** — the next state name.
- **`:guard`** — a predicate that must return true for the transition to fire. A keyword references the spec's `:guards` map; an inline `(fn [data event] ...)` is fine for a one-liner.
- **`:action`** — fires on this transition. Same shape as `:guard`: keyword reference into `:actions`, or inline fn.
- **`:entry` / `:exit`** — actions that fire when entering or leaving a state, regardless of which event triggered the transition. Same shape — keyword or inline fn.
- **`:meta`** — arbitrary user-defined annotations (e.g. `:terminal?`).
- **`:guards` / `:actions`** (top-level on the spec) — the machine's own named guard / action implementations. Resolution is machine-local; there's no global registry.

xstate users will recognise all of this. We deliberately stay close to xstate's vocabulary because (a) it's well-thought-out and (b) AIs already know it. When you ask an AI "give me the state machine for a login flow," it produces something that's already nearly correct in re-frame2's grammar.

What we adapt:

- **Machines are event handlers, not actor objects.** xstate has explicit `ActorRef` runtime objects with their own mailboxes. re-frame2 doesn't. A machine is "an event handler whose body interprets a transition table." All events to that machine flow through `dispatch` like any other event; there's no separate sending mechanism.
- **Effects are returned as data.** xstate's actions can directly call out to the world. re-frame2's actions return effect maps which the runtime interprets — same shape as event handlers.
- **The snapshot lives in `app-db`.** Every machine's runtime state — its `:state` and `:data` — sits at `[:rf/machines <id>]` in the frame's `app-db`. Not in a parallel registry, not in a per-machine atom. Undo, time-travel, persistence, SSR hydration all extend to machines for free, because their state is in the db.
- **Guards and actions live with the machine.** Each `create-machine-handler` spec carries its own `:guards` and `:actions` maps. Cross-machine reuse is via Clojure vars, not a global registry. A machine is a self-contained piece of data; reading its spec tells you everything its guards and actions do.

These adaptations keep the pattern consistent with the rest of re-frame2.

## Wiring a machine into the rest of re-frame

Once the transition table is defined, registering it as an event handler is one line:

```clojure
(rf/reg-event-fx :auth.login/flow
  {:doc "Login flow: idle → submitting → authed / error-shown / locked-out."}
  (rf/create-machine-handler login-flow))
```

The machine's id is the surrounding `reg-event-fx` id (`:auth.login/flow`). The snapshot lives at `[:rf/machines :auth.login/flow]` in `app-db`; the runtime reads / writes it there. **You don't pick a path — there is one canonical path.**

`(rf/create-machine-handler spec)` returns a regular `reg-event-fx`-shaped handler. It does the work of "look up the snapshot, call `machine-transition`, write the new snapshot, return the action effects." `machine-transition` is pure, so all the testing and tooling guarantees of regular events apply to machines.

If you don't need any other registration metadata (no `:doc`, no `:interceptors`), there's a one-step convenience:

```clojure
(rf/reg-machine :auth.login/flow login-flow)
```

This is exactly `(reg-event-fx machine-id (create-machine-handler machine))` — same effect, less ceremony.

`reg-machine` is a **macro** (since [rf2-8bp3](../../spec/005-StateMachines.md#source-coord-stamping-rf2-8bp3)). At expansion time it walks the literal spec form and stamps a flat coord index under `:rf.machine/source-coords` on the machine's metadata, keyed by spec-path tuples like `[:guards :form-valid?]`, `[:actions :commit]`, `[:states :form :on :submit]`. The coord index is what lets pair-tools take a clicked transition arrow in a state-diagram visualisation and jump to the source line that wrote it. It's dev-only — production builds elide the index alongside other source-coord annotations.

In day-to-day code you will not see the coord index unless you go looking for it. `(rf/machine-meta :auth.login/flow)` returns it under the `:rf.machine/source-coords` key.

## Dispatching to a machine

Sub-events route via the machine's id and an inner event vector:

```clojure
(rf/dispatch [:auth.login/flow [:auth.login/submit credentials]])
(rf/dispatch [:auth.login/flow [:auth.login/dismiss]])
```

The outer keyword is the machine; the inner vector is the event the machine sees. The runtime resolves the inner event against the current state's `:on` map, runs the guard, fires the action, and writes the new snapshot back to `[:rf/machines :auth.login/flow]`.

For HTTP / async callbacks, the convention "build a 2-element template, conj the reply on resolve" works as in any other re-frame fx:

```clojure
;; The :issue-request action returns this fx vector:
[:rf.http/managed
 {:request    {:method :post :url "/api/login"
               :request-content-type :json}
  :on-success [:auth.login/flow [:auth.login/success]]   ;; 2-element template
  :on-failure [:auth.login/flow [:auth.login/failure]]}]

;; :rf.http/managed appends the reply payload as the last argument, producing
;;   [:auth.login/flow [:auth.login/success] {:kind :success :value v}]
;; The runtime folds the trailing reply onto the inner event so the action
;; sees [:auth.login/success {:kind :success :value v}].
```

This "extras-fold" makes the standard fx-callback convention work without ceremony — every async callback ships a value into the machine the same way. (The reply-payload shape — `{:kind :success :value v}` / `{:kind :failure :failure m}` — is the canonical envelope `:rf.http/managed` produces; see [chapter 06 — Doing HTTP requests](06-doing-http-requests.md).)

## Guards and actions

Guards are predicates. They live in the spec's `:guards` map and are referenced from transitions by keyword:

```clojure
:guards
{:under-retry-limit
 (fn [data _event] (< (:attempts data) 3))}
```

A guard sees `(fn [data event] boolean)` — `data` is the snapshot's `:data` slot directly. Returning truthy lets the transition fire; returning falsy makes the runtime walk to the next candidate (or fall through to the parent state, if any).

Actions are functions that produce data updates and / or effects. They live in `:actions`:

```clojure
:actions
{:record-error
 (fn [data [_ err]]
   {:data (-> data
              (update :attempts inc)
              (assoc :error (or (:message err) "Login failed.")))})

 :issue-request
 (fn [_data [_ creds]]
   {:fx [[:rf.http/managed
          {:request    {:method :post
                        :url    "/api/login"
                        :body   creds
                        :request-content-type :json}
           :on-success [:auth.login/flow [:auth.login/success]]
           :on-failure [:auth.login/flow [:auth.login/failure]]}]]})}
```

An action sees `(fn [data event] effects)` and returns either:

- `:data` — a map merged into the existing data slot (last write wins on key collision; explicit `nil` clears a key).
- `:fx` — effects to fire (HTTP, navigation, follow-up dispatches).
- both, or `nil` for no effects.

Just like ordinary `reg-event-fx` returns. The contract is consistent.

> **Why `:data` is the parameter, not a destructure key.** Most guards and actions only care about the machine's own data — not about its discrete state, not about its meta. Passing `:data` directly keeps the 99% case monomorphic and stops `(fn [{:keys [data]} _] ...)` boilerplate from spreading through your codebase. The body reads `(:attempts data)`, not `(get-in snapshot [:data :attempts])`.

### When a guard or action needs the discrete state

The 3-arity escape hatch — `(fn [data event {:keys [state meta]}] ...)` — is opt-in. Declaring a third parameter signals to the runtime that this fn wants the introspection slot. `state` is the snapshot's discrete state; `meta` is any user `:meta`.

```clojure
(fn capture-browsing-position [_data _event {:keys [state]}]
  {:data {:last-browsing-state state}})        ;; the FSM keyword
```

Unless your guard or action needs to branch on the current state name (genuinely rare), stay with 2-arity. The 3-arity is the explicit signal "I'm doing introspection." The runtime arity-detects on the fn itself — no metadata, no flag.

## Reading a machine: `sub-machine`

Views that need to read a machine's current state read it through a sub:

```clojure
@(rf/sub-machine :auth.login/flow)
;; → {:state :submitting :data {:attempts 1 :error nil}}
```

`sub-machine` is sugar over the framework-shipped `:rf/machine` sub. It returns the snapshot — `{:state :data}` — or `nil` if the machine hasn't been created yet. Views typically destructure the `:state` and switch on it; complex UIs read the `:data` slot too. This is the single supported read path; there's no separate "actor reference" object to thread around.

For convenience, you can build named reg-subs over `[:rf/machine machine-id]` to project the bits you care about:

```clojure
(rf/reg-sub :auth.login/state
  (fn [db _] (get-in db [:rf/machines :auth.login/flow :state])))

(rf/reg-sub :auth.login/submitting?
  :<- [:auth.login/state]
  (fn [state _] (= :submitting state)))
```

These read the machine's snapshot through normal sub plumbing — Reagent reactions fire on the slice you actually subscribed to.

## What the substrate covers

The shape above — flat states, plain `:on` transitions, `:entry` / `:exit` actions, machine-local `:guards` / `:actions` — is enough for many machines. When the flow gets richer, the substrate has more to offer without changing the model. A flavour:

- **Hierarchical states.** A compound state contains sub-states; entering the parent cascades to its declared `:initial` child; transitions from a deep state can target a sibling or an ancestor and the runtime computes the LCA. Useful when the auth flow has an `:authenticated` super-state with `:cart` / `:browsing` sub-states under it.
- **Eventless `:always` transitions.** A state can fire a transition on entry (or after every event) when a guard becomes true — no event needed. Useful for "drain a queue, transition when empty" or "advance through derived states." Bounded depth, microstep-loop semantics, defined trace events.
- **Delayed `:after` transitions.** A state can declare "if no event arrives within N ms, transition to this state." Useful for retry-after-backoff, idle timeouts, debounce-shaped flows. Carries an epoch so cancelled timers don't fire late.
- **Declarative `:invoke`.** A state can spawn a child machine on entry and destroy it on exit, declared as data rather than as `:entry [:rf.machine/spawn ...]` / `:exit [:rf.machine/destroy ...]`. The child's lifecycle is bound to the parent state.

Each of these is opt-in — implementations declare which capabilities they support, and the conformance corpus grades against the claimed set. The full grammar is in [Spec 005](../../spec/005-StateMachines.md). The point of mentioning them here is that the model scales: when your machine grows, the substrate has well-named answers ready, and you don't end up smuggling state-machine logic into ordinary event handlers.

## Patterns that bottom out in machines

Two recurring shapes from [chapter 03](03-events-state-cycle.md) are state machines underneath. Both are pattern docs (convention), not Specs (contract). The shape is worth knowing in outline even if you don't write one today — the moment you reach for `setTimeout`-driven reconnect logic or an `:app/init` event that does six things, you'll recognise it.

### Pattern-WebSocket — a connection as a machine

WebSocket, Server-Sent Events, WebRTC peers — anything with retry, backoff, heartbeat, subscriptions, and server-pushed events — is state-machine-shaped:

```text
:disconnected ─open─►   :connecting
:connecting   ─opened─► :authenticating
:authenticating ─ok─►   :connected ◄─┐
:connected    ─close─►  :reconnecting ─after backoff─► :connecting
:reconnecting ─max retries─► :failed
```

`:connected` typically has sub-states (`:idle / :sending / :receiving`); `:reconnecting` carries the exponential-backoff counter in `:data`; subscribed topics live in `:data :subscriptions` and re-issue automatically on entry to `:connected` (so subscriptions survive reconnects). Messages flowing *over* the open connection are ordinary Pattern-AsyncEffect interactions — request-reply with a correlation id; the connection machine is the long-lived host.

The connection machine composes the locked substrate end-to-end: hierarchical states for `:connected`, `:after` for backoff, `:always` for max-retries guards and queue flushing on entry, `:invoke` to spawn a child socket actor that owns the actual `WebSocket` object. No new mechanism — just one machine exercising every primitive at once. Full walkthrough: [`spec/Pattern-WebSocket.md`](../../spec/Pattern-WebSocket.md).

### Pattern-Boot — initialisation as a machine

For trivial boots — one or two steps, no error states, no progress UI — a chain of dispatched events is fine:

```clojure
(rf/reg-event-fx :app/init
  (fn [_ _]
    {:fx [[:dispatch [:config/load]]]}))

(rf/reg-event-fx :config/load
  (fn [_ _]
    {:fx [[:rf.http/managed
           {:request    {:url "/config"}
            :on-success [:config/loaded]
            :on-failure [:app/init-failed]}]]}))

(rf/reg-event-fx :config/loaded
  (fn [{:keys [db]} [_ {:keys [value]}]]
    {:db (assoc db :config (:body value))
     :fx [[:dispatch [:app/ready]]]}))
```

The frame's `:on-create` fires `:app/init`, the chain runs to completion, the UI renders.

Once the boot graph has more than a few steps — auth restoration, profile load, hydration from `localStorage`, route resolution, real-time connect — chained events scatter logic across N unrelated handlers. The canonical answer is a **boot state machine** with named phases:

| State | Meaning |
|---|---|
| `:configuring` | Reading static config. |
| `:authenticating` | Restoring session token; refreshing if needed. |
| `:loading-profile` | Fetching user profile / feature flags. |
| `:hydrating` | Applying client-side persistent state. |
| `:routing` | Resolving the initial route. |
| `:ready` | Boot complete. (Terminal.) |
| `:auth-failed` / `:fatal-error` | Per-phase error states. |
| `:retrying-auth` | Recovery state with `:after` backoff. |

Each phase `:invoke`s the async work; transitions on success / failure; entry actions update the visible-progress slice in `:data`. The boot UI subscribes to the snapshot and renders "Loading config…" / "Signing in…" / "Almost ready…" by state. The whole sequence is one inspectable, testable, traceable machine.

The boot machine is also the canonical seam between **host-supplied static config** (a `/config` endpoint, build-time env vars) and the **running app's dynamic state** — `:configuring` lands the config into `:data`; subsequent states read from `:data :config` and thread values into the next phase's spawn-spec rather than reaching into globals. Full walkthrough including the auth-machine's transport-vs-semantic retry boundary: [`spec/Pattern-Boot.md`](../../spec/Pattern-Boot.md).

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

Because `machine-transition` is pure — and because guards and actions are inline-or-named-in-the-spec functions — you can test a machine without dispatching anything:

```clojure
(deftest login-flow-happy-path
  (let [s0 {:state :idle :data {:attempts 0 :error nil}}
        [s1 _fx] (rf/machine-transition login-flow s0
                                        [:auth.login/submit {:email "a@b.com"
                                                             :password "..."}])]
    (is (= :submitting (:state s1)))

    (let [[s2 _fx] (rf/machine-transition login-flow
                                          {:state :submitting :data {:attempts 0}}
                                          [:auth.login/success {:user {:id 1}}])]
      (is (= :authed (:state s2))))))

(deftest login-flow-lockout
  (let [snapshot {:state :submitting :data {:attempts 3}}
        [s _fx] (rf/machine-transition login-flow snapshot
                                       [:auth.login/failure {:message "wrong creds"}])]
    ;; attempts has already hit the retry limit; the :under-retry-limit guard
    ;; rejects the first clause, the second clause's :locked-out wins.
    (is (= :locked-out (:state s)))))
```

These tests run on the JVM. No browser. No network. No mocks. Each one runs in microseconds. You can have hundreds.

This is the testing experience for *flows that are non-trivial* — exactly the case where unit testing usually gets hard. With machines, it stays easy.

## Composing machines

Real apps have more than one machine: an auth machine, a checkout-wizard machine, a websocket-connection machine. They coexist within a frame, each at its own `[:rf/machines <id>]`.

When machines need to talk to each other, they do so through dispatch — the auth machine's `:auth.login/success` action might dispatch `[:user/load-profile]`, which is handled by a regular `reg-event-fx` handler that updates the user-profile slice. There's no special inter-machine messaging. It's all events, all going through the same queue, all running to completion.

The drain semantics matter here. If an action dispatches a child event, that child event runs *before* subscriptions update. So if a state machine moves through `:idle → :submitting → :authed → :loading-profile → :ready` in a single user click, the view sees only `:idle` (before) and `:ready` (after) — never any in-between flicker. The pattern's commitment to atomic state changes pays off most strongly in flows like this.

### Spawning child machines: `:rf.machine/spawn` and `:rf.machine/destroy`

Some machines aren't long-lived singletons. A protocol machine that owns one HTTP request lives only as long as the request. A websocket-pump machine spawns when the connection comes up and exits when it closes. A wizard's per-step subprocess starts and stops with the step. These are **dynamic actors** — gensym'd ids, lifecycle scoped to the parent.

The canonical fxs for the lifecycle are:

```clojure
[:rf.machine/spawn   {:machine-id :request/protocol
                      :data       {...}}]    ;; create — runtime gensyms an id
[:rf.machine/destroy actor-id]                ;; tear down by id
```

`:rf.machine/spawn` registers a fresh actor (a copy of the spec keyed by a gensym'd id), seeds its `:data`, runs `:on-spawn` against the new id, and queues the optional `:start` event to it. `:rf.machine/destroy` runs the actor's `:exit` action, dissociates its snapshot at `[:rf/machines <actor-id>]`, and clears its handler from the frame-local registry.

These are the **only** spelling for lifecycle fx since [PR #175](https://github.com/day8/re-frame2/pull/175). The earlier internal-only `:spawn` / `:destroy-machine` ids are gone — every spawn and destroy uses the `:rf.machine/...` namespace, in alignment with the framework's `:rf.<feature>/...` convention. (If you have a code-search habit of finding `:spawn` strings, switch to `:rf.machine/spawn` — `:spawn` no longer matches.)

For most apps, you do not call `:rf.machine/spawn` directly. The declarative `:invoke` slot on a state node spawns a child on entry and destroys it on exit:

```clojure
:states
{:authenticating
 {:invoke {:machine-id :auth/oauth-flow
           :data       {:provider :github}
           :on-spawn   :stash-actor-id}
  :on {:auth.oauth/success {:target :authenticated
                            :action :store-session}}}

 :authenticated
 {:entry :clear-actor-id
  ;; ... no :invoke — no child to manage in this state
  }}
```

`:invoke` is **registration-time sugar.** `create-machine-handler` walks the spec at construction time and rewrites every `:invoke` slot into entry/exit actions emitting `:rf.machine/spawn` and `:rf.machine/destroy`. The runtime sees only the desugared form — no new mechanics, no new lifecycle event.

#### The runtime-tracked spawn registry

Earlier drafts of the spec asked the user to write the spawned actor-id into a chosen `:data` key (e.g. `:auth-actor`) inside their `:on-spawn` action, then read that key on destroy to pass it to `:rf.machine/destroy`. That contract had a worked-example bug ([rf2-t07u](#)): if you wrote the id into `:auth-actor` but the runtime hardcoded a magic `:pending` key when destroying, your actor would silently leak on every state exit.

That is fixed (per PR #172). The runtime now keeps an internal **spawn registry** at `[:rf/spawned <parent-id> <invoke-id>]` in the spawning frame's `app-db`. When a state with `:invoke` is entered, the runtime writes the spawned actor-id there. On exit, the runtime reads it back and emits the destroy fx with the right id. **You don't have to track the actor-id yourself.**

`:on-spawn` is now **advisory** — you can still write the id into `:data` if your transition table needs it for something, but the runtime no longer relies on you doing so. The auth-flow worked example in this chapter — and in [Spec 005](../../spec/005-StateMachines.md) — works as written, no per-user-bookkeeping required.

```clojure
;; The runtime tracks this for you. Visible via:
(get-in (rf/get-frame-db) [:rf/spawned :auth.login/flow :auth-actor])
;; → :auth/oauth-flow#g42  (the gensym'd actor id)
```

The slot is sibling to `[:rf/system-ids]` (next section): same per-frame isolation, same revertibility (the slot walks back atomically with `app-db` on a frame revert), same lazy allocation (absent until the first declarative `:invoke` spawn).

### Naming machines across the frame: `:system-id`

Sometimes a spawned actor needs to be reached by name from somewhere else — a sibling machine, an event handler, a REPL session — without threading the gensym'd id through `:data`. The opt-in `:system-id` key on a spawn binds the actor to a frame-level reverse index:

```clojure
;; Imperative spawn (action :fx) with a :system-id binding.
{:fx [[:rf.machine/spawn {:machine-id :request/protocol
                          :data       {...}
                          :system-id  :primary-request}]]}

;; The same key works on declarative :invoke:
:states
{:requesting
 {:invoke {:machine-id :request/protocol
           :system-id  :primary-request
           :data       {...}}}}

;; Look it up later:
(rf/machine-by-system-id :primary-request)
;; → :request/protocol#g42  (the gensym'd actor-id), or nil if no actor is currently bound
```

`:system-id` is a **frame-level reverse index** that resolves to whichever spawned actor currently owns the name. It lives at `[:rf/system-ids <name>]` in the spawning frame's `app-db` — same place as the snapshot, so it inherits frame revertibility for free.

Lifecycle:

- **On spawn** with a `:system-id`, the runtime writes `[:rf/system-ids <name>] = <gensym'd-id>` and emits `:rf.machine/system-id-bound`.
- **On destroy**, the runtime clears the slot AND emits `:rf.machine/system-id-released`.
- **A spawn under an already-bound name rebinds** (last-write-wins) and emits `:rf.error/system-id-collision` so observers can see the displacement. The previously-bound machine's snapshot stays at its `[:rf/machines <id>]` slot — just unnamed.

The standard cross-machine pattern still works — `[:fx [[:dispatch [<other-id> [:event]]]]]` addresses any registered id. With `:system-id` bound, the addressing call site becomes a name lookup:

```clojure
{:fx [[:dispatch [(rf/machine-by-system-id :primary-request)
                  [:protocol/cancel]]]]}
```

This is **opt-in** and **orthogonal** to gensym'd ids. Most spawns won't need it. Reach for `:system-id` when you have a stable role (one auth flow, one primary request, one wizard) that other parts of the frame need to talk to by name.

A note on the 3-arity escape hatch covered earlier: the runtime detects "this fn declared three positional parameters" by inspecting the fn's arglist. **Variadic fns** like `(constantly nil)` or `(fn [& args] ...)` are detected as 2-arity (per [rf2-1e0n](#) and follow-up rf2-l04j). If you actually want the introspection slot, write a real 3-arity fn rather than reaching for a variadic shorthand — the variadic case is a footgun, not a clever shortcut.

## A runnable example

The complete login flow from this chapter — including the runnable smoke tests — lives at [`examples/reagent/state_machine_walkthrough/`](https://github.com/day8/re-frame2/tree/main/examples/reagent/state_machine_walkthrough) and is exercised on every JVM test run via `re-frame.examples-test/state-machine-walkthrough-runs-headless`. Drop it in front of you while reading; tweak the transition table and watch the smoke tests adapt.

## The deeper claim

State machines are a small example of the broader thesis [chapter 09](09-the-dynamic-model.md) makes: **constrained execution models are easier to reason about than free-form ones**.

A finite state machine has, by construction, a small set of reachable states. You can enumerate them. You can prove things about transitions. You can render the whole flow as a diagram. You can ask "from this state, what can happen?" and the answer is bounded.

A pile of `if` / `cond` clauses spread across event handlers has none of these properties. The reachable states are implicit. The transitions are scattered. There's no diagram. The answer to "from this state, what can happen?" requires reading every handler that touches the state field.

The choice between them isn't a matter of style. It's a matter of which dynamic model you can hold in your head. Machines are smaller models. Smaller models are easier to debug, refactor, extend, and reason about.

When the flow has the shape of a machine, write a machine.

## Next

- [06 — Doing HTTP requests](06-doing-http-requests.md) — `:rf.http/managed`, the canonical request fx, end-to-end.
- [07 — The server side](07-server-side.md) — server-side rendering, hydration, and the `:platforms` story for fx that should only run in one place.
