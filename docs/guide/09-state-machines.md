# 09 — State machines

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

This is a **transition table** — pure data, read top to bottom. Five states (`:idle`, `:submitting`, `:error-shown`, `:authed`, `:locked-out`); `:idle` starts; `:auth.login/submit` from `:idle` goes to `:submitting`; from `:submitting`, success → `:authed`, failure → `:error-shown` if `:under-retry-limit` passes, otherwise `:locked-out`. Each `:action` / `:entry` is referenced by id; the named impl lives in the spec's `:guards` / `:actions` map.

The whole flow is one piece of data. You can pretty-print it, render it to a graph, check it against a schema, or hand it to an AI and say "add a two-factor state between submitting and authed" — the AI has the whole context. The runtime applies the transition rules against the current snapshot; the developer describes the transitions, not the imperative branching.

## What's in a transition

The grammar is borrowed (with adaptation) from [xstate](https://xstate.js.org/):

- **`:initial`** — the starting state.
- **`:data`** — extended state alongside the discrete state. Counters, error messages, transient data. (xstate calls this "context"; we use `:data` to avoid the existing "context" overloadings in interceptors and React.)
- **`:states`** — the state nodes, keyed by name. `:on` (events the state responds to), `:entry` / `:exit` (actions fired on enter / leave regardless of trigger), `:meta` (user annotations like `:terminal?`).
- **`:target`** — the next state name on a transition.
- **`:guard`** — a predicate that must return true for a transition to fire. A keyword references the spec's `:guards` map; an inline `(fn [data event] ...)` works for one-liners.
- **`:action`** — fires on this transition. Same shape as `:guard`.
- **`:guards` / `:actions`** (top-level) — the machine's own named impls. Resolution is machine-local; there's no global registry.

We deliberately stay close to xstate's vocabulary because it's well-thought-out and because AIs already know it — asked for "the state machine for a login flow," they produce something nearly correct in re-frame2's grammar. Four adaptations keep the pattern consistent with the rest of re-frame2:

- **Machines are event handlers, not actor objects.** A machine is "an event handler whose body interprets a transition table." All events flow through `dispatch`; there's no separate sending mechanism or `ActorRef`.
- **Effects are returned as data.** Actions return effect maps the runtime interprets — same shape as ordinary event handlers.
- **The snapshot lives in `app-db`** at `[:rf/machines <id>]`. Not in a parallel registry, not in a per-machine atom. Undo, time-travel, persistence, and SSR hydration extend to machines for free.
- **Guards and actions live with the machine.** Each spec carries its own `:guards` and `:actions` maps; cross-machine reuse is via Clojure vars, not a global registry.

## Wiring a machine into the rest of re-frame

Registering a transition table as an event handler is one line:

```clojure
(rf/reg-machine :auth.login/flow login-flow)
```

This is exactly `(reg-event-fx :auth.login/flow (create-machine-handler login-flow))`. The machine's id is `:auth.login/flow`; the snapshot lives at `[:rf/machines :auth.login/flow]` in `app-db`. **You don't pick a path — there is one canonical path.** `create-machine-handler` returns a regular `reg-event-fx`-shaped handler whose body does "look up the snapshot, call `machine-transition`, write back, return the action effects." `machine-transition` is pure, so all the testing and tooling guarantees of regular events apply.

If you need `:doc` or `:interceptors`, use the longer form:

```clojure
(rf/reg-event-fx :auth.login/flow
  {:doc "Login flow: idle → submitting → authed / error-shown / locked-out."}
  (rf/create-machine-handler login-flow))
```

`reg-machine` is a **macro**. At expansion it walks the literal spec form and stamps a dev-only coord index under `:rf.machine/source-coords` — what lets pair-tools jump from a clicked transition arrow in a state-diagram visualisation back to the source line. Production builds elide it.

**Initial-state `:entry` fires on machine birth.** When a singleton machine first receives an event — or when an actor is brought into being by `:rf.machine/spawn` / declarative `:invoke` — the runtime cascades into the `:initial` state and runs that state's `:entry` as part of birth. No self-targeting `:on :rf.machine/spawned` ceremony. For compound initial states, every state along the cascade fires its `:entry` shallowest-first. So one-shot setup work — seed `:data`, kick off an HTTP request, register a subscription — goes in the initial state's `:entry`.

## Dispatching to a machine

Sub-events route via the machine's id and an inner event vector:

```clojure
(rf/dispatch [:auth.login/flow [:auth.login/submit credentials]])
(rf/dispatch [:auth.login/flow [:auth.login/dismiss]])
```

The outer keyword is the machine; the inner vector is the event the machine sees. The runtime resolves the inner event against the current state's `:on` map, runs the guard, fires the action, and writes the new snapshot back to `[:rf/machines :auth.login/flow]`.

For HTTP / async callbacks, build a 2-element template and let the runtime conj the reply on resolve:

```clojure
[:rf.http/managed
 {:request    {:method :post :url "/api/login" :request-content-type :json}
  :on-success [:auth.login/flow [:auth.login/success]]   ;; 2-element template
  :on-failure [:auth.login/flow [:auth.login/failure]]}]

;; :rf.http/managed appends the reply payload producing
;;   [:auth.login/flow [:auth.login/success] {:kind :success :value v}]
;; The runtime folds the trailing reply onto the inner event so the action
;; sees [:auth.login/success {:kind :success :value v}].
```

This "extras-fold" makes every async callback ship a value into the machine the same way. The reply-payload envelope (`{:kind :success :value v}` / `{:kind :failure :failure m}`) is the canonical `:rf.http/managed` shape — see [chapter 10](10-doing-http-requests.md).

## Guards and actions

Guards are predicates. They live in the spec's `:guards` map and are referenced from transitions by keyword:

```clojure
:guards
{:under-retry-limit
 (fn [data _event] (< (:attempts data) 3))}
```

A guard sees `(fn [data event] boolean)` — `data` is the snapshot's `:data` slot directly. Returning truthy lets the transition fire; returning falsy makes the runtime walk to the next candidate (or fall through to the parent state, if any).

Actions are functions producing data updates and / or effects. They live in `:actions`:

```clojure
:actions
{:record-error
 (fn [data [_ {:keys [failure]}]]
   {:data (-> data
              (update :attempts inc)
              (assoc :error (or (:message failure) "Login failed.")))})}
```

An action sees `(fn [data event] effects)` and returns either `:data` (a map merged into the existing data slot — last write wins; explicit `nil` clears a key), `:fx` (effects: HTTP, navigation, follow-up dispatches), both, or `nil`. Same contract as an ordinary `reg-event-fx` return.

If a guard or action genuinely needs to branch on the current state name — rare — there's an opt-in 3-arity overload that exposes the snapshot's `:state` and `:meta` slots. Use 2-arity by default; the 3-arity form is `^:rf.machine/wants-ctx (fn [data event {:state :meta}] ...)` — the metadata flag declares intent explicitly, so the runtime delivers the introspection ctx regardless of arglist shape (variadic or fixed).

## Reading a machine: `sub-machine`

Views read a machine's current state through a sub:

```clojure
@(rf/sub-machine :auth.login/flow)
;; → {:state :submitting :data {:attempts 1 :error nil}}
```

`sub-machine` is sugar over the framework-shipped `:rf/machine` sub. It returns the snapshot, or `nil` if the machine hasn't been created yet. Views typically destructure `:state` and switch on it; complex UIs read `:data` too. This is the single supported read path — no "actor reference" object to thread around. For projections, build named reg-subs over `[:rf/machine machine-id]`:

```clojure
(rf/reg-sub :auth.login/submitting?
  :<- [:rf/machine :auth.login/flow]
  (fn [{:keys [state]} _] (= :submitting state)))
```

Reagent reactions fire on the slice you actually subscribed to.

## Tagging states

Once a machine grows past a handful of states, views start asking the same question over and over: *"is the machine in any of the loading-ish states right now?"* The 1-of-N read is well-served by `sub-machine` and a `=` check, but the *any-of-many* read isn't — most apps end up inventing one hand-rolled boolean sub per shape they care about (`(or (= state :submitting) (= state :validating) ...)`). That works until you add a fourth loading-ish state and every view that wants the spinner needs to know which sub to subscribe to. The view is asking a *predicate* question — "are we loading?" — and a `case`-style read keeps making it look like a discriminator.

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

`(rf/has-tag? machine-id tag)` is sugar over the framework-shipped `:rf/machine-has-tag?` sub. The signal flips only when the containment bit flips — adding a new loading-ish state later is one `:tags #{:loading}` on the new state node, with no view changes anywhere. The view never had to know which states carried the tag; it just asked whether the union contains `:loading`. The framework sub composes into higher-level reg-subs the usual way.

A few rules:

- **Tags are owned by the runtime.** They are a projection of `:state`. Actions can't write `:tags` in their effect map; you set the state, the tags follow.
- **Tags compose along the active path.** In a compound machine, every state along root → leaf contributes its `:tags`. A parent `:authenticated` carrying `#{:logged-in}` plus a child `:dashboard` carrying `#{:home-view}` gives a snapshot `:tags` of `#{:logged-in :home-view}`.
- **Empty tag-sets vanish.** A machine that declares no `:tags` produces a snapshot with no `:tags` key at all.
- **`:rf/*` / `:rf.*/*` namespaces are reserved.** Use your own — `:auth/...`, `:ui.state/...`, plain keywords like `:loading`.

When to reach for tags: when you're already writing two or three boolean subs that each ask "is the state one of these?" — the third one is the signal. When *not*: when the question really is "which exact state is it?" — that's a `case` on `(:state @(sub-machine ...))`. Tags also have a sweet spot in the [Parallel regions](#parallel-regions) pattern below — they carry per-axis intent so view-level queries don't have to know about the cross-product.

## What the substrate covers

The shape above — flat states, plain `:on` transitions, `:entry` / `:exit` actions, machine-local `:guards` / `:actions` — is enough for many machines. When the flow gets richer, the substrate has more to offer without changing the model:

- **Hierarchical states.** A compound state contains sub-states; entering the parent cascades to its declared `:initial` child; transitions from a deep state can target a sibling or an ancestor and the runtime computes the LCA. Useful when the auth flow has an `:authenticated` super-state with `:cart` / `:browsing` sub-states under it.
- **Eventless `:always` transitions.** A state can fire a transition on entry (or after every event) when a guard becomes true — no event needed. Useful for "drain a queue, transition when empty" or advancing through derived states. Bounded depth, microstep-loop semantics.
- **Delayed `:after` transitions.** "If no event arrives within N ms, transition to this state." Useful for retry-after-backoff, idle timeouts, debounce-shaped flows. Carries an epoch so cancelled timers don't fire late.
- **Declarative `:invoke`.** A state can spawn a child machine on entry and destroy it on exit, declared as data. The child's lifetime is bound to the parent state.

Each is opt-in per the capability matrix. The model scales — when your machine grows, the substrate has well-named answers ready.

### Capability matrix cross-reference

For port authors: each substrate feature has a **capability-flag** name (`:fsm/hierarchy`, `:fsm/always`, `:fsm/after`, `:fsm/invoke`, `:fsm/parallel-regions`, `:fsm/tags`, `:fsm/final-states`, `:actor/spawn-and-join`). A port declares its claimed set; a key the port doesn't claim raises `:rf.error/machine-grammar-not-in-v1` at registration time rather than being silently accepted. The v1 CLJS reference claims all of the above; the most chapter-relevant flags are `:fsm/hierarchy`, `:fsm/always`, and `:fsm/after`.

## Patterns that bottom out in machines

Three recurring shapes from [chapter 04](04-events-state-cycle.md) are state machines underneath. Each has a dedicated pattern doc — convention, not Spec — that walks the worked example end-to-end. The point of naming them here is that the moment you reach for `setTimeout`-driven reconnect logic, an `:app/init` event that does six things, or a `for` loop that locks the UI for two seconds, you'll recognise the shape and know where to look.

### Pattern-WebSocket — a connection as a machine

WebSocket, Server-Sent Events, WebRTC peers — anything with retry, backoff, heartbeat, subscriptions, and server-pushed events — is state-machine-shaped. The canonical machine has a `:disconnected` initial state, a compound `:active` parent containing `:connecting / :authenticating / :connected` leaves (the parent owns the `:invoke`d socket actor so its lifetime spans the success cascade), a `:reconnecting` state with fn-form `:after` exponential backoff, and a terminal `:failed` state on max retries. Messages *over* the open connection are ordinary async-effect interactions correlated through `:data :in-flight`.

### Pattern-Boot — initialisation as a machine

For trivial boots — one or two steps, no progress UI — a chain of dispatched events suffices. Once the boot graph grows to include config load, auth restoration, profile fetch, `localStorage` hydration, and route resolution, the chained-events form scatters logic across N unrelated handlers. The canonical answer is a boot state machine with named phases (`:configuring`, `:authenticating`, `:loading-profile`, `:hydrating`, `:routing`, `:ready`, plus per-phase error and `:retrying-*` states). Each phase `:invoke`s its async work, transitions on success / failure, and updates the visible-progress slice in `:data` so the boot UI can render "Loading config…" / "Signing in…" / "Almost ready…" from the snapshot. The boot machine is also the canonical seam between host-supplied static config and the running app's dynamic state.

### Pattern-LongRunningWork — CPU-bound work as a chunked machine

A handler with significant CPU-bound work — iterating over a large dataset, encoding / decoding, indexing, parsing, running a simulation step — blocks the dispatch loop and freezes the UI. There are two real answers: **offload to a Web Worker** (the preferred answer whenever the work is serialisable across the worker boundary), or **chunk and yield on the main thread**. The chunked answer is a tiny machine — `:idle / :processing / :checking-done / :yielding / :complete / :cancelled` — cycling `:processing → :checking-done → :yielding → :processing` until done. The load-bearing line is `:yielding`'s `:after 0`, which hands the thread back to the browser between chunks so progress bars repaint and the cancel button stays clickable. Cancellation is a transition (not a flag), the partial result is preserved in `:data`, and the v1 idioms (`^:flush-dom`, self-redispatching `{:dispatch [...]}` loops) are gone. See also [16 — Performance](16-performance.md#when-to-reach-for-the-chunked-work-machine) for how this sits among the other shapes of performance work.

## When to reach for a machine, and when not to

State machines aren't a hammer. Most events in most apps are simple `(state, event) → state` updates with no flow structure; don't dress them up as machines.

**Reach for one when:** the flow has named, mutually-exclusive stages (your handlers `cond` on a state field — the signal); transitions are conditional on guards (repeated `(when (some-condition? db) ...)` across handlers are guards in disguise); the flow is non-trivial enough to draw on a whiteboard (the diagram *is* the machine — encode it directly).

**Don't reach for one when:** the "state" is just data (a counter, a list of items — no machine needed); there are only two stages (a boolean `:loading?` flag is fine); you're trying to enforce a *sequence of operations* (that's a saga / workflow — re-frame2's drain semantics handle the simple cases). Reach for machines only when *named states* are the load-bearing concept, not when *named operations* are.

## Headless testing

Because `machine-transition` is pure — and guards / actions are inline-or-named-in-the-spec functions — you can test a machine without dispatching anything:

```clojure
(deftest login-flow
  (let [s0 {:state :idle :data {:attempts 0 :error nil}}
        {s1 ::result/snap} (rf/machine-transition login-flow s0
                                                  [:auth.login/submit {:email "a@b.com"
                                                                       :password "..."}])]
    (is (= :submitting (:state s1)))

    ;; attempts has already hit the retry limit; the :under-retry-limit guard
    ;; rejects the first clause, the second clause's :locked-out wins.
    (let [{s2 ::result/snap} (rf/machine-transition login-flow
                                                    {:state :submitting :data {:attempts 3}}
                                                    [:auth.login/failure {:message "wrong creds"}])]
      (is (= :locked-out (:state s2))))))
```

`machine-transition` returns a `re-frame.machines.result/Result` map — destructure `::result/snap` and `::result/fx` (or use `result/ok?` / `result/fail?` to discriminate). Per rf2-aa2rw the engine surfaces action / `:data`-fn throws via `result/fail` rather than the old `[::action-failed info]` sentinel.

JVM-side. No browser, no network, no mocks. Each test runs in microseconds; you can have hundreds. This is the testing experience for *flows that are non-trivial* — exactly the case where unit testing usually gets hard.

## Parallel regions

Some pages don't have one axis of state — they have several, all running at once. A todos page has a *data lifecycle* (nothing / loading / empty / some / error), a *form state* (neutral / invalid / valid), and a *mode* (active / archived). The axes are orthogonal; any cross-product is legal. The naïve modellings both fail: one big flat machine explodes to `3 × 3 × 3 = 27` states; three separate slices in `app-db` pushes cross-axis truths into derived subs.

Parallel regions are the substrate answer. A machine declares `:type :parallel` and a `:regions` map; each region is a full state-tree with its own `:initial` and `:states`, and **every region is active simultaneously**:

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

A dispatch into a parallel machine **broadcasts** to every region. Each region's active state checks its own `:on` map; matching states transition, non-matching states stay put. `[:todos/page [:fetch]]` advances `:data` from `:nothing` to `:loading` while `:form` and `:mode` ignore it. If no region handles the event, one `:rf.warning/machine-unhandled-event` fires; if any region handles it, the warning is suppressed.

### Tags compose across regions

The `:tags` slot is the union across every active state in every region. Setting `:data` to `:loading` while leaving the others alone produces `#{:data/loading :form/neutral :mode/active}`; `(rf/has-tag? :todos/page :data/loading)` is true and so is `(rf/has-tag? :todos/page :mode/active)`. The view asks predicate questions without knowing which region holds which tag.

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

Every region in a parallel machine shares the **same** `:data` blob — no per-region slot. Guards and actions in every region see and write the same map. That's the design constraint. If the three regions genuinely share a domain (a todos page where data, form, and mode all read and write the same `:items`), shared `:data` is what you want. If they're separately scoped features that just happen to be on the same screen, what you actually want is N separate machines coordinated through dispatch — the next section.

### When to reach for parallel regions

Reach for them when the axes are orthogonal, share a domain (one `:data` blob), and the flat cross-product would be more than ~6-8 states. *Don't* reach for them when one axis's transition depends on another (that's one axis with compound structure — use a hierarchical machine), when regions don't share data (use N machines), or for a simple "loading flag + data" pair (flat machine, two states).

The fully-worked depth example — all nine canonical UI states (`Nothing / Loading / Empty / One / Some / Too Many / Incorrect / Correct / Done`) modelled as one three-region machine — has runnable code at `examples/reagent/nine_states/`.

## Composing machines

Real apps have more than one machine — an auth machine, a checkout-wizard machine, a websocket-connection machine — coexisting in a frame, each at its own `[:rf/machines <id>]`. Machines talk to each other through dispatch (the auth machine's `:auth.login/success` action dispatches `[:user/load-profile]`, which a regular `reg-event-fx` handler picks up); there's no special inter-machine messaging.

The drain semantics matter. If an action dispatches a child event, that child runs *before* subscriptions update. So if a machine moves through `:idle → :submitting → :authed → :loading-profile → :ready` in a single user click, the view sees only `:idle` (before) and `:ready` (after) — never any in-between flicker. Atomic state changes pay off most strongly in flows like this.

### Spawning child machines: `:rf.machine/spawn` and `:rf.machine/destroy`

Some machines aren't long-lived singletons. A protocol machine that owns one HTTP request lives only as long as the request; a websocket-pump machine spawns when the connection comes up and exits when it closes; a wizard's per-step subprocess starts and stops with the step. These are **dynamic actors** — gensym'd ids, lifecycle scoped to the parent.

The canonical lifecycle fxs:

```clojure
[:rf.machine/spawn   {:machine-id :request/protocol
                      :data       {...}}]    ;; create — runtime gensyms an id
[:rf.machine/destroy actor-id]                ;; tear down by id
```

`:rf.machine/spawn` registers a fresh actor (a copy of the spec keyed by a gensym'd id), seeds its `:data`, runs `:on-spawn` against the new id, and queues the optional `:start` event. `:rf.machine/destroy` runs the actor's `:exit` action, dissociates its snapshot, and clears its handler from the frame-local registry.

For most apps you do not call these directly. The declarative `:invoke` slot on a state node spawns a child on entry and destroys it on exit:

```clojure
:states
{:authenticating
 {:invoke {:machine-id :auth/oauth-flow
           :data       {:provider :github}}
  :on {:auth.oauth/success {:target :authenticated
                            :action :store-session}}}

 :authenticated
 {;; ... no :invoke — no child to manage in this state
  }}
```

`:invoke` is **registration-time sugar.** `create-machine-handler` rewrites every `:invoke` into entry/exit actions emitting `:rf.machine/spawn` and `:rf.machine/destroy`. The runtime sees only the desugared form — no new mechanics, no new lifecycle event. The spawned actor-id is tracked internally at `[:rf/spawned <parent-id> <invoke-id>]` — **you don't track it yourself**; the runtime reads it back on exit to emit the destroy fx with the right id.

### Naming machines across the frame: `:system-id`

Sometimes a spawned actor needs to be reached by name from somewhere else — a sibling machine, an event handler, a REPL session — without threading the gensym'd id through `:data`. The opt-in `:system-id` key on a spawn (imperative `:rf.machine/spawn` fx or declarative `:invoke`) binds the actor to a frame-level reverse index at `[:rf/system-ids <name>]`:

```clojure
:states
{:requesting
 {:invoke {:machine-id :request/protocol
           :system-id  :primary-request
           :data       {...}}}}

;; Look up later:
(rf/machine-by-system-id :primary-request)
;; → :request/protocol#g42  (the gensym'd actor-id), or nil if unbound
```

Spawn writes the slot and emits `:rf.machine/system-id-bound`; destroy clears it and emits `:rf.machine/system-id-released`; rebinding emits `:rf.error/system-id-collision` (last-write-wins; the previous machine's snapshot remains, just unnamed). With `:system-id` bound, cross-machine dispatch becomes a name lookup: `{:fx [[:dispatch [(rf/machine-by-system-id :primary-request) [:protocol/cancel]]]]}`.

This is **opt-in** and **orthogonal** to gensym'd ids. Reach for it when you have a stable role — one auth flow, one primary request, one wizard — that other parts of the frame need to talk to by name.

## A runnable example

The complete login flow from this chapter — including the runnable smoke tests — lives at [`examples/reagent/state_machine_walkthrough/`](https://github.com/day8/re-frame2/tree/main/examples/reagent/state_machine_walkthrough) and is exercised on every JVM test run via `re-frame.examples-test/state-machine-walkthrough-runs-headless`. Drop it in front of you while reading; tweak the transition table and watch the smoke tests adapt.

## The deeper claim

State machines are a small example of the broader thesis [chapter 12](12-the-dynamic-model.md) makes: **constrained execution models are easier to reason about than free-form ones**. A finite state machine has, by construction, a small set of reachable states — you can enumerate them, prove things about transitions, render the whole flow as a diagram. A pile of `if` / `cond` clauses spread across event handlers has none of those properties: reachable states are implicit, transitions are scattered, "from this state, what can happen?" requires reading every handler that touches the state field.

The choice isn't a matter of style. It's a matter of which dynamic model you can hold in your head. When the flow has the shape of a machine, write a machine.

## Next

- [10 — Doing HTTP requests](10-doing-http-requests.md) — `:rf.http/managed`, the canonical request fx, end-to-end.
- [11 — The server side](11-server-side.md) — server-side rendering, hydration, and the `:platforms` story for fx that should only run in one place.
- See also: [08 — Forms](08-forms.md) — the standard form-slice convention; the login flow's machine sits on top of a form slice underneath.
