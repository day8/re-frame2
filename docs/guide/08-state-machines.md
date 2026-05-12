# 08 — State machines

You've been writing these all along. You just haven't been calling them that.

The `cond` at the top of your login handler that branches on `(:auth/state db)`. The `case` in your video-player event that decides what `:pause` means depending on whether we're `:loading` or `:playing` or `:buffering`. The `if-let` that checks "are we already submitting? then ignore this click." The keyword you stuffed into `app-db` and have been growing ever since — `:idle`, `:submitting`, `:authed`, `:error-shown`, `:locked-out` — together with the unwritten rules in your head about which of those can follow which.

Every one of those is the same shape: a flow whose load-bearing question isn't *what's in the frame's app-db* but *what state are we in, and what events take us to which state*. That shape has a name in computer science — **finite state machine** — and the moment you notice you're writing one, the `cond` clauses scattered across five event handlers stop being the natural way to express the flow and start being a way of hiding it.

A login form moves from `:idle` to `:submitting` to `:authed` or `:error-shown` or `:locked-out`. A video player cycles through `:loading` / `:playing` / `:paused` / `:buffering` / `:ended`. A checkout wizard walks `:cart` → `:shipping` → `:payment` → `:confirmation` → `:processed`. A websocket connection lives in `:connecting`, `:connected`, `:disconnected`, `:reconnecting`. These aren't unusual — they're the bones of most non-trivial features.

re-frame2 makes machines a first-class pattern. You register them with `reg-machine`, they live in each frame under `[:rf/machines <id>]`, and they dispatch through the same six-domino loop as every other event. Not because every handler should be a machine — most shouldn't — but because when "what's the next state?" is the central question, naming the answer is dramatically clearer than scattering it across `if`/`when`/`case`.

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
    ;; The reply lands folded into the inner event by managed-HTTP's
    ;; extras-fold — see §"Dispatching to a machine" below for the
    ;; {:kind :failure :failure <reply>} envelope shape.
    (fn [data [_ {:keys [failure]}]]
      {:data (-> data
                 (update :attempts inc)
                 (assoc :error (or (:message failure) "Login failed.")))})

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

`reg-machine` is a **macro**. At expansion time it walks the literal spec form and stamps a flat coord index under `:rf.machine/source-coords` on the machine's metadata, keyed by spec-path tuples like `[:guards :form-valid?]`, `[:actions :commit]`, `[:states :form :on :submit]`. The coord index is what lets pair-tools take a clicked transition arrow in a state-diagram visualisation and jump to the source line that wrote it. It's dev-only — production builds elide the index alongside other source-coord annotations.

In day-to-day code you will not see the coord index unless you go looking for it. `(rf/machine-meta :auth.login/flow)` returns it under the `:rf.machine/source-coords` key.

**Initial-state `:entry` fires on machine birth.** When a singleton machine first receives an event, or when an actor is brought into being by `:rf.machine/spawn` / declarative `:invoke`, the runtime cascades into the `:initial` state and runs that state's `:entry` action as part of birth — no self-targeting `:on :rf.machine/spawned` ceremony needed. For compound initial states, every state along the initial cascade fires its `:entry` shallowest-first. So if you want one-shot setup work to run when a machine wakes up — seed `:data`, kick off an HTTP request, register a subscription — put it in the initial state's `:entry`. Earlier drafts of the spec required a workaround for this; the workaround is gone.

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

This "extras-fold" makes the standard fx-callback convention work without ceremony — every async callback ships a value into the machine the same way. (The reply-payload shape — `{:kind :success :value v}` / `{:kind :failure :failure m}` — is the canonical envelope `:rf.http/managed` produces; see [chapter 10 — Doing HTTP requests](10-doing-http-requests.md).)

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
 (fn [data [_ {:keys [failure]}]]
   ;; The inner event vector arrives folded by managed-HTTP's extras-fold
   ;; as [<event-id> {:kind :failure :failure <reply-map>}] — see
   ;; §"Dispatching to a machine" above. Destructure on :failure to reach
   ;; the reply payload itself.
   {:data (-> data
              (update :attempts inc)
              (assoc :error (or (:message failure) "Login failed.")))})

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

## Tagging states

Once a machine grows past a handful of states, views start asking the same question over and over: *"is the machine in any of the loading-ish states right now?"* The 1-of-N read is well-served by `sub-machine` and a `=` check; the *any-of-many* read isn't, and most apps end up inventing one boolean sub per shape they care about:

```clojure
(rf/reg-sub :auth/loading?
  :<- [:auth.login/state]
  (fn [state _] (or (= state :submitting)
                    (= state :validating)
                    (= state :refreshing))))
```

That works until you add a fourth loading-ish state. Then the sub needs to know about it. Then every view that wants the loading indicator needs to know which sub. The view is asking a *predicate* question — "are we loading?" — and a `case`-style read keeps making it look like a discriminator question.

`:tags` flips that around. A state can declare a set of keywords describing its intent:

```clojure
{:initial :neutral
 :states
 {:neutral     {:on {:submit :submitting}}
  :submitting  {:tags #{:loading :transient}  :on {:ok :ready :err :error-shown}}
  :validating  {:tags #{:loading}             :on {:done :neutral}}
  :refreshing  {:tags #{:loading :transient}  :on {:done :ready}}
  :ready       {:tags #{:happy-path}}
  :error-shown {:tags #{:recoverable}}}}
```

At every transition, the runtime walks the active configuration (for a flat machine that's just the current state; for a compound machine it's the leaf and every ancestor it sits inside) and stamps the **union** of every active state's tags onto the snapshot at `:tags`. So a snapshot for `:submitting` looks like:

```clojure
{:state :submitting
 :data  {<...>}
 :tags  #{:loading :transient}}
```

The view asks the predicate question directly:

```clojure
(when @(rf/has-tag? :auth.login/flow :loading)
  [view-spinner])
```

`(rf/has-tag? machine-id tag)` is sugar over a framework-shipped sub, `:rf/machine-has-tag?`. The signal flips only when the containment bit flips — adding a new loading-ish state later is one `:tags #{:loading}` on the new state node, with no view changes anywhere. The view didn't need to know which states carried the tag; it just asked whether the union contains `:loading`.

For sub chains, the framework sub composes the same way any other reg-sub does — feed it into a higher-level sub that combines the predicate with other inputs:

```clojure
(rf/reg-sub :auth/show-spinner?
  :<- [:rf/machine-has-tag? :auth.login/flow :loading]
  :<- [:ui/spinner-enabled?]
  (fn [[loading? enabled?] _]
    (and loading? enabled?)))
```

A few rules worth knowing up front:

- **Tags are owned by the runtime.** They are a projection of `:state`. Actions can't write `:tags` in their effect map; you set the state, the tags follow.
- **Tags compose along the active path.** In a compound machine, every state along root → leaf contributes its `:tags`. If a parent `:authenticated` carries `#{:logged-in}` and its child `:dashboard` carries `#{:home-view}`, the snapshot's `:tags` is `#{:logged-in :home-view}`.
- **Empty tag-sets vanish.** A machine that declares no `:tags` produces a snapshot with no `:tags` key at all. Pre-tag and post-tag machines with no tag declarations are byte-identical.
- **`:rf/*` and `:rf.*/*` keyword namespaces are reserved.** Your tags go in your own namespaces — `:auth/...`, `:ui.state/...`, plain keywords like `:loading`. Don't reach for the framework prefix.

When to reach for tags: when you're already writing two or three boolean subs that each ask "is the state one of these?" — the third one is the signal. When *not* to reach for tags: when the question really is "which exact state is it?" — that's a `case` on `(:state @(sub-machine ...))`, and tags would only add a layer of indirection.

Tags also have a sweet spot in the [Parallel regions](#parallel-regions) pattern below. When a machine has truly orthogonal axes — data lifecycle, form validity, render mode — tags carry the per-axis intent so view-level queries don't have to know about the cross-product.

Full normative reference: [Spec 005 §State tags](../../spec/005-StateMachines.md#state-tags).

## What the substrate covers

The shape above — flat states, plain `:on` transitions, `:entry` / `:exit` actions, machine-local `:guards` / `:actions` — is enough for many machines. When the flow gets richer, the substrate has more to offer without changing the model. A flavour:

- **Hierarchical states.** A compound state contains sub-states; entering the parent cascades to its declared `:initial` child; transitions from a deep state can target a sibling or an ancestor and the runtime computes the LCA. Useful when the auth flow has an `:authenticated` super-state with `:cart` / `:browsing` sub-states under it.
- **Eventless `:always` transitions.** A state can fire a transition on entry (or after every event) when a guard becomes true — no event needed. Useful for "drain a queue, transition when empty" or "advance through derived states." Bounded depth, microstep-loop semantics, defined trace events.
- **Delayed `:after` transitions.** A state can declare "if no event arrives within N ms, transition to this state." Useful for retry-after-backoff, idle timeouts, debounce-shaped flows. Carries an epoch so cancelled timers don't fire late.
- **Declarative `:invoke`.** A state can spawn a child machine on entry and destroy it on exit, declared as data rather than as `:entry [:rf.machine/spawn ...]` / `:exit [:rf.machine/destroy ...]`. The child's lifecycle is bound to the parent state.

Each of these is opt-in — implementations declare which capabilities they support, and the conformance corpus grades against the claimed set. The full grammar is in [Spec 005](../../spec/005-StateMachines.md). The point of mentioning them here is that the model scales: when your machine grows, the substrate has well-named answers ready, and you don't end up smuggling state-machine logic into ordinary event handlers.

### Capability matrix cross-reference

For advanced readers: the substrate features above each have a **capability-flag** name that ports claim against. The full matrix lives at [Spec 005 §Capability matrix](../../spec/005-StateMachines.md#capability-matrix); the ones this chapter has touched on are:

- `:fsm/parallel-regions` — `:type :parallel` + the `:regions` map (the [Parallel regions](#parallel-regions) section below).
- `:fsm/tags` — the `:tags` slot on a state node and its tag-union projection on the snapshot.
- `:fsm/final-states` — `:final?` + `:on-done` + `:output-key`, for states that signal completion.
- `:fsm/hierarchy`, `:fsm/always`, `:fsm/after`, `:fsm/invoke`, `:actor/spawn-and-join` — compound states, eventless transitions, delayed transitions, child-machine `:invoke`, and `:invoke-all`.

The v1 CLJS reference claims all of the above. A port that doesn't claim a given capability raises `:rf.error/machine-grammar-not-in-v1` on the corresponding key at registration time, rather than silently accepting it.

## Patterns that bottom out in machines

Three recurring shapes from [chapter 04](04-events-state-cycle.md) are state machines underneath. All three are pattern docs (convention), not Specs (contract). The shape is worth knowing in outline even if you don't write one today — the moment you reach for `setTimeout`-driven reconnect logic, an `:app/init` event that does six things, or a `for` loop that locks the UI for two seconds, you'll recognise it.

### Pattern-WebSocket — a connection as a machine

WebSocket, Server-Sent Events, WebRTC peers — anything with retry, backoff, heartbeat, subscriptions, and server-pushed events — is state-machine-shaped:

```text
:disconnected      ─ws/connect─►   :active / :connecting
:active /:connecting ─opened─►     :active / :authenticating
:active /:authenticating ─ok─►     :active / :connected
:active / *        ─closed─►       :reconnecting ─after backoff─► :active / :connecting
:reconnecting      ─max retries─►  :failed
```

`:connecting`, `:authenticating`, and `:connected` sit under one compound parent `:active`. The parent owns the `:invoke` of the child socket actor — its lifetime spans every leaf that dispatches through it, so the actor stays alive across the success-path transitions and is destroyed only when control leaves `:active` (to `:reconnecting`, `:failed`, or `:disconnected`). `:reconnecting` carries the exponential-backoff counter in `:data` and uses a fn-form `:after` delay; subscribed topics live in `:data :subscriptions` and re-issue automatically on entry to `:connected` (so subscriptions survive reconnects). Messages flowing *over* the open connection are ordinary Pattern-AsyncEffect interactions — request-reply with a correlation id, tracked in `:data :in-flight`; the connection machine is the long-lived host that performs the correlation step.

The connection machine composes the locked substrate end-to-end: a hierarchical parent for the actor's lifetime, `:after` for backoff, `:always` for max-retries guards and queue flushing on entry, `:invoke` to spawn the child socket actor that owns the actual `WebSocket` object, and the connection-epoch idiom (the socket actor's gensym'd id IS the epoch) to suppress messages from a torn-down socket. No new mechanism — just one machine exercising every primitive at once. Full walkthrough: [`spec/Pattern-WebSocket.md`](../../spec/Pattern-WebSocket.md).

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

### Pattern-LongRunningWork — CPU-bound work as a chunked machine

A handler with significant CPU-bound work — iterating over a large dataset, encoding / decoding, indexing, parsing, running a simulation step — blocks the dispatch loop and freezes the UI. The browser repaints once per ~16 ms; if a single handler holds the thread longer, animations stutter, clicks queue up, and the app appears hung. The naïve fix — "just do it all in one event handler" — is exactly the shape that earns a *"page unresponsive"* warning.

There are two real answers, and the choice is the first decision you make:

1. **Offload to a Web Worker.** The main thread stays responsive; the work runs at full speed on another thread; progress reports flow back as events. This is the preferred answer whenever the work is serialisable across the worker boundary — see [`spec/Pattern-AsyncEffect.md`](../../spec/Pattern-AsyncEffect.md).
2. **Chunk and yield on the main thread.** When the work has to run on the main thread — DOM access, framework state, awkward-to-serialise data — split it into small batches and yield between batches. That's a state machine.

The chunked machine has a tiny, canonical shape:

| state | meaning |
|---|---|
| `:idle` | Not running. Initial state. |
| `:processing` | Entry action processes one chunk; updates `:data`. |
| `:checking-done` | Eventless `:always` decides: complete, yield, or cancel. |
| `:yielding` | `:after 0` schedules the next chunk so the browser gets a render tick. |
| `:complete` | Terminal — work finished. |
| `:cancelled` | Terminal — user requested cancel. |

The cycle is `:processing → :checking-done → :yielding → :processing → …` until `:checking-done` decides the job is done. The `:after 0` in `:yielding` is the load-bearing line — it hands the thread back to the browser between chunks so the progress bar repaints, the cancel button stays clickable, and the rest of the app keeps running.

#### A worked example — count matches across a huge vector

Stretching the counter throughline one last notch: imagine the counter is no longer "increment on click" but "scan a million-record vector and count how many records match a predicate." The result is still a single number; the work to compute it is heavy. We can't compute it inside a sub (subs are read-only and have to stay cheap), and we can't compute it inline in one event handler (the UI would freeze for a second or two). It's a chunked machine.

```clojure
(rf/reg-machine :counter/scan
  {:initial :idle
   :data    {:input      nil
             :total      0
             :processed  0
             :matches    0
             :chunk-size 5000}

   :guards
   {:done?      (fn [d _] (>= (:processed d) (:total d)))
    :more-work? (fn [d _] (<  (:processed d) (:total d)))}

   :actions
   {:start-scan
    (fn [_ [_ input opts]]
      {:data {:input      input
              :total      (count input)
              :processed  0
              :matches    0
              :chunk-size (:chunk-size opts 5000)}})

    :process-chunk
    (fn [{:keys [input chunk-size processed matches] :as d} _]
      (let [end   (min (+ processed chunk-size) (count input))
            chunk (subvec input processed end)
            hits  (count (filter big-enough? chunk))]
        {:data {:processed end
                :matches   (+ matches hits)}}))}

   :states
   {:idle
    {:on {:start {:target :processing :action :start-scan}}}

    :processing
    {:entry  :process-chunk
     :always [{:target :checking-done}]}

    :checking-done
    {:always [{:guard :done?      :target :complete}
              {:guard :more-work? :target :yielding}]}

    :yielding
    {:after {0 :processing}
     :on    {:cancel :cancelled}}

    :complete  {:on {:reset :idle}}
    :cancelled {:on {:reset :idle}}}})
```

A million records with `:chunk-size 5000` is 200 chunks. The view dispatches `[:counter/scan [:start big-vector]]`, the machine cycles through `:processing / :checking-done / :yielding` 200 times, and at the end `:complete` holds the final count in `:data :matches`. Between each chunk the browser gets one render tick — the progress bar moves, the cancel button responds.

The progress UI reads from the machine snapshot via `sub-machine`:

```clojure
(rf/reg-sub :counter.scan/progress
  :<- [:rf/machine :counter/scan]
  (fn [{:keys [data]} _]
    (let [{:keys [processed total]} data]
      (when (pos? total) (/ processed total)))))   ;; 0.0 .. 1.0

(rf/reg-sub :counter.scan/matches
  :<- [:rf/machine :counter/scan]
  (fn [snapshot _] (get-in snapshot [:data :matches])))

(rf/reg-sub :counter.scan/state
  :<- [:rf/machine :counter/scan]
  (fn [snapshot _] (:state snapshot)))
```

The view renders a progress bar from `:counter.scan/progress`, a live running count from `:counter.scan/matches`, and switches its layout on `:counter.scan/state` — a "Cancel" button while `:yielding`, a "Done" affordance while `:complete`. Each `:processing` tick advances both the progress bar and the running count; the user sees the machine *thinking*, not a frozen page followed by a sudden answer.

#### Cancellation is a transition, not a flag

The naïve approach to cancel — set a flag in `app-db` and check it on every chunk — is replaced by the machine. A `:cancel` event handled in the `:yielding` state transitions to `:cancelled`; the next chunk simply doesn't run, because the machine is no longer in `:processing`:

```clojure
(rf/dispatch [:counter/scan [:cancel]])
```

The partial result is preserved in `:data` — the view can show "Cancelled at 437,000 of 1,000,000" by reading the snapshot. For the more sophisticated case of a still-pending `:after` timer firing after cancel, [Pattern-StaleDetection](../../spec/Pattern-StaleDetection.md) composes cleanly: the machine's epoch advances on entering `:cancelled` and any in-flight timer carrying the previous epoch is suppressed on receipt.

#### When to choose a worker instead

The chunked-main-thread pattern works, but it isn't free — every browser tick is overhead, and the work runs slower than it would on a dedicated thread. For genuinely heavy CPU work (image processing, large simulations, search indexing, complex analyses), **offload to a Web Worker** via [Pattern-AsyncEffect](../../spec/Pattern-AsyncEffect.md):

- The main thread stays fully responsive — no chunking required.
- Progress reporting still works — the worker dispatches progress events back.
- Cancellation is the same epoch-based pattern.
- The work runs at full thread speed (no ~16 ms overhead between chunks).

The chunked pattern is the right answer when worker offload isn't feasible — DOM access, framework state, awkward-to-serialise data, or work that's *medium-heavy* enough that worker setup costs outweigh the gain. Roughly: if a chunk fits comfortably in 16 ms and you only need a handful of seconds total, chunked-main-thread is simpler; if you're looking at tens of seconds or genuinely thread-bound compute, hand it to a worker.

#### Pitfalls

A few traps the pattern doc calls out explicitly:

- **Computing in subscriptions.** Subs should be cheap and pure; long compute belongs in event handlers. A sub that takes seconds to settle slows every render that touches it. Compute in events, project the result via subs.
- **Multiple `assoc`s in one handler expecting interleaved renders.** re-frame2 batches per drain — only one render per drain regardless of how many `:db` updates the handler made. The chunked machine is the only way to get intermediate renders, because each chunk is its own dispatch and its own drain.
- **`:always` cycles without `:after 0` between batches.** A pure `:always` chain hits the `:rf.error/machine-always-depth-exceeded` cap (default 16). The `:yielding` state's `:after 0` resets the depth *and* yields to the browser — that's what's earning its keep.
- **Input changing mid-process.** The machine's `:data` holds the input vector that `:start-scan` snapshotted. If the source data changes while the scan runs, the machine keeps processing the original snapshot — which is usually what you want. If it isn't, `:reset` and re-start.
- **Forgetting cancel.** Long jobs need a cancel path. The state-machine shape makes this trivial; ad-hoc self-redispatch loops make it painful.

The v1 idiom for this — `^:flush-dom` event metadata, or self-redispatching `{:dispatch [...]}` "tail call" loops — is gone. The replacement is this machine: explicit states, named transitions, encapsulated `:data`, cancellation as a transition, progress as a snapshot field. Full walkthrough including the `:dispatch-later {:ms 0}` "yield once before one big block" variant: [`spec/Pattern-LongRunningWork.md`](../../spec/Pattern-LongRunningWork.md). For where this pattern sits among the other shapes of performance work in re-frame2 — and the worker-offload alternative — see [16 — Performance](16-performance.md#when-to-reach-for-the-chunked-work-machine).

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

## Parallel regions

Some pages don't have one axis of state — they have several, all running at once. A todos page has a *data lifecycle* (nothing / loading / empty / some / error), a *form state* (neutral / invalid / valid), and a *mode* (active / archived). Those three axes are orthogonal: the form can be invalid while the data is loading, the mode can be archived while the form happens to be neutral. None of those combinations is illegal; they're just *true at the same time*.

The naïve modelling — one big flat machine — explodes combinatorially. Three axes of three states each is `3 × 3 × 3 = 27` flat states, and every transition needs to remember which of the other two axes it's leaving alone. The naïve fix — keep the three axes as three separate slices in `app-db` and write derived subs that combine them — leaves you computing cross-axis truths in subscriptions, every page-level UI question becoming a hand-rolled boolean discriminator.

Parallel regions are the substrate answer. A machine can declare `:type :parallel` and a `:regions` map; each region is a full state-tree with its own `:initial` and `:states`, and **every region is active simultaneously**:

```clojure
(rf/reg-machine :todos/page
  {:type :parallel
   :data {:items [] :error nil}            ;; shared across every region

   :guards  {:empty? (fn [d _] (zero? (count (:items d))))}
   :actions {:set-items (fn [d [_ {:keys [items]}]]
                          {:data (assoc d :items (vec items))})}

   :regions
   {:data
    {:initial :nothing
     :states  {:nothing  {:tags #{:data/idle}    :on {:fetch :loading}}
               :loading  {:tags #{:data/loading} :on {:loaded {:target :resolving
                                                                :action :set-items}}}
               :resolving {:always [{:guard :empty? :target :empty}
                                    {:target :some}]}
               :empty    {:tags #{:data/empty}}
               :some     {:tags #{:data/some}}}}

    :form
    {:initial :neutral
     :states  {:neutral {:tags #{:form/neutral} :on {:submit-invalid :incorrect
                                                     :submit-valid   :correct}}
               :incorrect {:tags #{:form/invalid} :on {:edit :neutral}}
               :correct   {:tags #{:form/success} :on {:edit :neutral}}}}

    :mode
    {:initial :active
     :states  {:active {:tags #{:mode/active} :on {:archive :done}}
               :done   {:tags #{:mode/done :mode/read-only}}}}}})
```

After the machine boots, the snapshot's `:state` is a **map** of region-name to that region's current state:

```clojure
@(rf/sub-machine :todos/page)
;; → {:state {:data :nothing :form :neutral :mode :active}
;;    :data  {:items [] :error nil}
;;    :tags  #{:data/idle :form/neutral :mode/active}}
```

Three regions; three currently-active states; one tag union. Three regions, *not* twenty-seven cross-product states.

### How events flow

When you dispatch an event into a parallel machine, the runtime **broadcasts** it to every region. Each region's currently-active state checks its own `:on` map; the ones that match transition, the ones that don't stay put.

```clojure
(rf/dispatch [:todos/page [:fetch]])
;; → :data region transitions :nothing → :loading
;;   :form region's :neutral doesn't handle :fetch — stays at :neutral
;;   :mode region's :active doesn't handle :fetch — stays at :active

(rf/dispatch [:todos/page [:archive]])
;; → :mode region transitions :active → :done
;;   :data and :form regions stay put
```

If no region handles the event, the machine emits one `:rf.warning/machine-unhandled-event` — just like a flat machine would. If *any* region handles it, the warning is suppressed.

### Tags compose across regions

The `:tags` slot on a parallel snapshot is the union of every active state's tags **across every region**. Setting the data region to `:loading` while leaving the others alone gives:

```clojure
{:state {:data :loading :form :neutral :mode :active}
 :data  {:items [] :error nil}
 :tags  #{:data/loading :form/neutral :mode/active}}
```

`(rf/has-tag? :todos/page :data/loading)` returns true. So does `(rf/has-tag? :todos/page :mode/active)`. The view doesn't need to know which region holds which tag — it asks the predicate question and the runtime walks the active configuration across all regions to answer it.

### One `case`, one render-priority table

The pattern that makes parallel regions sing is a **single render selector** that consults the tag union once and returns the keyword the root view dispatches on. The render priorities live in a small table; the root view is one `case`:

```clojure
(def render-priority
  [{:tag :mode/done       :render :done}
   {:tag :form/success    :render :correct}
   {:tag :form/invalid    :render :incorrect}
   {:tag :data/loading    :render :loading}
   {:tag :data/empty      :render :empty}
   {:tag :data/some       :render :some}])

(rf/reg-sub :todos.page/render
  :<- [:rf/machine :todos/page]
  (fn [snap _]
    (some (fn [{:keys [tag render]}]
            (when (contains? (:tags snap) tag) render))
          render-priority)))

(defn root-view []
  (case @(rf/subscribe [:todos.page/render])
    :done      [view-done]
    :correct   [view-correct-banner]
    :incorrect [view-inline-errors]
    :loading   [view-spinner]
    :empty     [view-empty-state]
    :some      [view-list]))
```

The priority table is the only place the page declares "if mode is done, that wins over everything; otherwise if the form just succeeded, that wins; otherwise show the data." Each per-state view is small. The root view is one branch site. Adding a tenth state is one row in the table and one view fn.

### One `:data`, shared

Every region in a parallel machine shares the **same** `:data` blob. There is no per-region `:data` slot. Guards and actions in every region see and write the same map.

That's the design constraint to be aware of. If your three regions genuinely share a domain — a todos page where the data, the form, and the mode all read and write the same `:items` — shared `:data` is what you want. If your regions are *separately scoped* features that just happen to be on the same screen, what you actually want is N separate machines, each at its own `[:rf/machines <id>]`, coordinated through dispatch. The next section covers that pattern; the choice is by domain shape, not by code style.

The simplest test is: *do these axes read and write the same data?* If yes, one parallel machine. If no, N machines.

### When to reach for parallel regions

- The axes are *orthogonal* — any cross-product is legal, no axis can rule out a state in another axis.
- The axes *share a domain* — they read and write one `:data` blob.
- The flat alternative is a combinatorial explosion of states (more than ~6-8 in the cross-product).
- You're already inventing boolean discriminator subs to combine three independent slices for page-level rendering decisions.

When *not* to reach for parallel regions:

- Two axes that interact heavily — one transition's `:target` depends on the other axis's current state. That's actually one axis with compound structure; model it as a hierarchical machine.
- Independent features that don't share data — separate apps' worth of state on one screen. Use N machines.
- A simple "loading flag + data" pair — that's still a flat machine with a `:loading` and a `:ready` state, no regions needed.

The fully-worked depth example — including all nine of the canonical UI states modelled as one three-region machine — is [Pattern-NineStates](../../spec/Pattern-NineStates.md), with runnable code at `examples/reagent/nine_states/`. The example above is the introduction; the pattern doc is where it expands into a full page-rendering convention.

Full normative reference: [Spec 005 §Parallel regions](../../spec/005-StateMachines.md#parallel-regions).

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

Earlier drafts of the spec asked the user to write the spawned actor-id into a chosen `:data` key (e.g. `:auth-actor`) inside their `:on-spawn` action, then read that key on destroy to pass it to `:rf.machine/destroy`. That contract had a worked-example bug: if you wrote the id into `:auth-actor` but the runtime hardcoded a magic `:pending` key when destroying, your actor would silently leak on every state exit.

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

A note on the 3-arity escape hatch covered earlier: the runtime detects "this fn declared three positional parameters" by inspecting the fn's arglist. **Variadic fns** like `(constantly nil)` or `(fn [& args] ...)` are detected as 2-arity. If you actually want the introspection slot, write a real 3-arity fn rather than reaching for a variadic shorthand — the variadic case is a footgun, not a clever shortcut.

## A runnable example

The complete login flow from this chapter — including the runnable smoke tests — lives at [`examples/reagent/state_machine_walkthrough/`](https://github.com/day8/re-frame2/tree/main/examples/reagent/state_machine_walkthrough) and is exercised on every JVM test run via `re-frame.examples-test/state-machine-walkthrough-runs-headless`. Drop it in front of you while reading; tweak the transition table and watch the smoke tests adapt.

## The deeper claim

State machines are a small example of the broader thesis [chapter 12](12-the-dynamic-model.md) makes: **constrained execution models are easier to reason about than free-form ones**.

A finite state machine has, by construction, a small set of reachable states. You can enumerate them. You can prove things about transitions. You can render the whole flow as a diagram. You can ask "from this state, what can happen?" and the answer is bounded.

A pile of `if` / `cond` clauses spread across event handlers has none of these properties. The reachable states are implicit. The transitions are scattered. There's no diagram. The answer to "from this state, what can happen?" requires reading every handler that touches the state field.

The choice between them isn't a matter of style. It's a matter of which dynamic model you can hold in your head. Machines are smaller models. Smaller models are easier to debug, refactor, extend, and reason about.

When the flow has the shape of a machine, write a machine.

## Next

- [09 — Forms](09-forms.md) — the standard form-slice convention; the login flow's machine sits on top of a form slice underneath.
- [10 — Doing HTTP requests](10-doing-http-requests.md) — `:rf.http/managed`, the canonical request fx, end-to-end.
- [11 — The server side](11-server-side.md) — server-side rendering, hydration, and the `:platforms` story for fx that should only run in one place.
