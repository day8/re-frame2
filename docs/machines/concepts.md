# State machines

Some state isn't a value you read — it's a *question*: what state are we even **in**? A login is idle, then submitting, then authed, error-shown, or locked-out. A websocket is connecting, connected, dropped, reconnecting. For flows like those the interesting fact isn't what sits in [app-db](../guide/glossary.md#app-db) — it's which of a fixed set of **named states** you occupy, and which [events](../guide/glossary.md#event) move you between them.

A **[machine](glossary.md#machine)** makes that shape first-class, so you stop reconstructing it from code scattered across handlers. This page builds up to it one idea at a time: first we spot the machine already hiding in code you write, then rewrite it as data, register it, run it live in your browser, and finally grow the grammar as flows get richer.

> **Deciding where a value should live?** A machine is the right home when a value has a *lifecycle* — named states, timers, retries, cancellation — not just a value you read. [Where should this value live?](../guide/where-state-lives.md) has the full decision procedure, and machine is the last of [the four homes](../guide/glossary.md#the-four-homes-where-state-lives) you reach for.

## The machine hiding in your `cond`s

You already write state machines. You just call them other things. The keyword you stuffed into app-db — `:idle`, `:submitting`, `:authed` — plus the rules in your head about which states can legally follow which: that's a machine, written informally. Here's a login flow the way most people write it first:

```clojure
(rf/reg-event :auth/submit
  (fn [{:keys [db]} [_ creds]]
    (cond
      (= :submitting (:auth/state db))
      {}                                          ;; ignore — already submitting

      (>= (:auth/attempts db) 3)
      {:db (assoc db :auth/state :locked-out)}

      :else
      {:db (-> db (assoc :auth/state :submitting) (update :auth/attempts inc))
       :fx [[:rf.http/managed
             {:request    {:method :post :url "/api/login" :body creds
                           :request-content-type :json}
              :on-success [:auth/login-success]
              :on-failure [:auth/login-error]}]]})))

(rf/reg-event :auth/login-error
  (fn [{:keys [db]} [_ {:keys [failure]}]]
    {:db (if (>= (:auth/attempts db) 3)
           (assoc db :auth/state :locked-out)
           (-> db (assoc :auth/state :error-shown) (assoc :auth/error failure)))}))

;; ... plus :auth/login-success, :auth/dismiss, :auth/reset ...
```

This works. It also has three problems, and each one *spreads* as the flow grows:

1. **The transition rules are scattered.** `:submitting` is reachable from `:idle` but not from `:locked-out`. That rule lives buried in cond clauses. To see the whole state graph you'd have to read every handler that touches `:auth/state`.
2. **Shared logic duplicates.** "3 attempts" appears in two handlers. Change it to 5 and you'd better remember both, because nothing connects them.
3. **Adding a state is a chore.** A `:two-factor` step between `:submitting` and `:authed` means a new keyword, a new handler, *and* edits to every handler that assumed which states were valid where.

The fix isn't better `cond` clauses. It's spotting the shape and writing it down as data.

> **Coming from re-frame v1?** Machines don't exist in v1 — the keyword-in-app-db + `cond` pattern above *is* the v1 shape this replaces. There's nothing to unlearn; you're promoting an informal pattern to first-class data. See [From re-frame v1](../guide/25-from-re-frame-v1.md).

## The same flow as a transition table

Here's that exact flow, written as one piece of data. Read it top to bottom — it tells you the whole story.

```clojure
;; Adapted from examples/reagent/state_machine_walkthrough/core.cljc
(def login-flow
  {:initial :idle
   :data    {:attempts 0 :error nil}

   :guards
   {:under-retry-limit
    (fn [{data :data}] (< (:attempts data) 3))}

   :actions
   {:clear-error
    (fn [_] {:data {:error nil}})

    :issue-request
    (fn [{[_ creds] :event}]
      {:fx [[:rf.http/managed
             {:request    {:method :post :url "/api/login" :body creds
                           :request-content-type :json}
              :decode     :json
              :on-success [:auth.login/flow [:auth.login/success]]
              :on-failure [:auth.login/flow [:auth.login/failure]]}]]})

    :record-error
    (fn [{data :data [_ {:keys [failure]}] :event}]
      {:data (-> data
                 (update :attempts inc)
                 (assoc :error (or (:message failure) "Login failed.")))})

    :store-session
    (fn [{[_ {:keys [value]}] :event}]
      {:fx [[:auth.session/store {:token (:token value)}]]})}

   :states
   {:idle
    {:on {:auth.login/submit {:target :submitting :action :clear-error}}}

    :submitting
    {:tags  #{:auth/busy}
     :entry :issue-request
     :on    {:auth.login/success {:target :authed :action :store-session}
             :auth.login/failure [{:target :error-shown
                                   :guard  :under-retry-limit
                                   :action :record-error}
                                  {:target :locked-out}]}}

    :error-shown
    {:on {:auth.login/dismiss {:target :idle}
          :auth.login/submit  {:target :submitting}}}

    ;; Persistent sinks — no outgoing transitions; :meta is tooling-only.
    ;; (:final? true would auto-destroy the machine; omit it to persist.)
    :authed     {:meta {:terminal? true}}
    :locked-out {:meta {:terminal? true}}}})
```

Five states. `:idle` starts. Submit takes `:idle` to `:submitting`. From there, success goes to `:authed`; failure goes to `:error-shown` *if* the `:under-retry-limit` guard passes, and otherwise to `:locked-out`.

Two new words there, both of them in the [glossary](glossary.md):

- A **[guard](glossary.md#guard)** is a yes/no test that gates a [transition](glossary.md#transition). `:under-retry-limit` answers "have we still got attempts left?"
- An **[action](glossary.md#action)** is the side work a transition performs. `:issue-request` fires the HTTP call.

Both are referenced from the table *by id*, and their implementations live once, up top in the `:guards` and `:actions` maps. The arrows name them; the functions sit in one place.

Now watch the three problems vanish. The transition rules are all in one place. The retry limit lives in exactly one guard. Adding `:two-factor` is one new state node plus the arrows in and out — the existing nodes don't move. And because the whole flow is *one value*, you can pretty-print it, render it as a diagram, or hand it to an AI with "add a two-factor state" and the AI gets the entire context in one form, instead of chasing logic across files.

> **Coming from XState?** This table will look deeply familiar — re-frame2's machine grammar deliberately borrows XState's vocabulary: transition tables, guards, actions, tags, `:after`, run-to-completion. Two shifts are coming. The big one: a machine isn't an actor you `send` to — it's an [event handler](../guide/glossary.md#event-handler). The small one: idiomatic spelling (a Clojure `?` instead of a JS boolean name, a set instead of an array). re-frame2 tracks the direction of **XState v6**; where the two differ on purpose, this page flags it, and there's a full delta table [at the end](#coming-from-xstate-the-five-row-delta).

## Registering and running it

Registering the table is one line:

```clojure
(rf/reg-machine :auth.login/flow login-flow)
```

And here's where people brace for a new runtime concept. There isn't one. **A machine *is* an event handler.** Its live value at any moment — which state it's in, plus its `:data` — is one small map called its **[snapshot](glossary.md#snapshot)**. [`reg-machine`](../guide/glossary.md#registration) is sugar over `reg-event` whose body interprets the table: look up the current snapshot, compute the transition, write the new snapshot back, return the action's [effects](../guide/glossary.md#effect). That one line is exactly equivalent to:

```clojure
;; make-machine-handler lives in re-frame.machines
(rf/reg-event :auth.login/flow (machines/make-machine-handler login-flow))
```

Every event reaches the machine through the same `dispatch` and the same [event cascade](../guide/glossary.md#event-cascade) as everything else. No actor object, no second messaging system — one mechanism, used everywhere.

Dispatching routes through the machine's id, wrapping an inner event vector:

```clojure
(rf/dispatch [:auth.login/flow [:auth.login/submit credentials]])
```

And you read the machine's snapshot through a [subscription](../guide/glossary.md#subscription), addressed by the machine's id:

```clojure
@(rf/subscribe [:rf/machine :auth.login/flow])
;; => {:state :submitting :data {:attempts 1 :error nil}}  (nil before the first event)
```

That's the whole loop: register the table, dispatch wrapped events into it, subscribe to read the snapshot. `[:rf/machine <id>]` is an ordinary [query vector](../guide/glossary.md#query-vector) — the same shape you'd write for any registered sub — so it's traceable and introspectable like the rest of your [derivation graph](../guide/glossary.md#the-derivation-graph). Named projections chain off it — `(rf/reg-sub :auth.login/error :<- [:rf/machine :auth.login/flow] ...)` — like any other [subscription](../guide/concepts/subscriptions.md).

> **One-time setup.** Machines ship in their own artefact, `day8/re-frame2-machines`, so an app without machines builds a bundle clean of them. Add the dep and require `re-frame.machines` once at app boot — that registers the hooks through which `rf/reg-machine` and the framework `:rf/machine` / `:rf/machine-has-tag?` subs resolve. Forget it and `reg-machine` throws `:rf.error/machines-artefact-missing`, naming the artefact to add — one more named failure mode rather than a silent dud.

> **Where the snapshot lives.** The snapshot — `{:state :submitting :data {:attempts 1 :error nil}}` — lives in the [frame](../guide/glossary.md#frame)'s **[runtime-db](../guide/glossary.md#runtime-db)** at `[:rf.runtime/machines :snapshots :auth.login/flow]`, the framework's half of [the two partitions](../guide/glossary.md#the-two-partitions), kept apart from the app data you own. The snapshot is just a value riding the frame — so [undo, time-travel](../guide/glossary.md#time-travel), persistence, and SSR [hydration](../guide/glossary.md#hydration) all work on machines for free.

> **`reg-machine` vs `reg-machine*` / `defmachine`.** This splits exactly like Clojure's `fn` / `fn*`. The `reg-machine` **macro** is what you reach for in source — it walks the literal spec at compile time and stamps source coordinates onto every guard, action, and transition, so a tool can jump from a diagram arrow back to the line of code (production builds [elide](../guide/glossary.md#elide) them). `reg-machine*` is the plain **function** underneath, for cases a macro can't serve — a REPL session, a code-gen pipeline building specs at runtime, a conformance harness loading machines from EDN. The macro lives on the `rf/` facade; the plain fn stays home as `re-frame.machines/reg-machine*`. There's also `defmachine`, a `def`-shaped macro for the "define the spec as a Var, register it elsewhere" pattern — `(rf/defmachine login-flow {…})` then `(rf/reg-machine :auth.login/flow login-flow)` — which stamps source at the *definition* site so a value-registered machine keeps its tool legibility.

### Composing with async effects

It's worth pausing on the async wiring in `:issue-request`, because it shows off something quietly excellent: an HTTP reply lands back *inside* the machine as just another event, with no glue code in between.

Look at the `:on-success` value: `[:auth.login/flow [:auth.login/success]]`. That's the same wrapped shape you dispatch by hand — the machine id `:auth.login/flow` outside, the inner event `[:auth.login/success]` inside — but written one element short on purpose. When the request returns, [managed HTTP](../resources/http.md) *appends* its reply payload to that inner event, so what actually arrives is `[:auth.login/success {:kind :success :value v}]` — exactly the event `:store-session` destructures. That's [the uniform reply](../resources/http.md) at work: a managed async surface completes by dispatching an event, so a machine and an async [effect](../guide/glossary.md#effect) compose with no adapter layer.

> **Do, then observe.** Dispatch one event with [Xray](../guide/glossary.md#xray) open. The transition shows up as an ordinary event row — snapshot before and after — riding the same [trace stream](../guide/glossary.md#trace-stream) as everything else. See [Debug with Xray](../guide/how-to/debug-with-xray.md).

## See one run

Here's a turnstile with two states and a counter riding in `:data`, live in your browser. Click into the cell and press **`Ctrl-Enter`** (**`Cmd-Enter`** on macOS) to re-evaluate after edits. This is the real `rf/reg-machine` — the same call you'd write in your own app.

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])

(def turnstile
  {:initial :locked
   :data    {:coins 0 :pushes 0}
   :actions {:take-coin  (fn [{data :data}] {:data (update data :coins  inc)})
             :count-push (fn [{data :data}] {:data (update data :pushes inc)})}
   :states
   {:locked   {:on {:coin {:target :unlocked :action :take-coin}
                    :push {:target :locked   :action :count-push}}}  ;; blocked: stays locked
    :unlocked {:on {:push {:target :locked}
                    :coin {:target :unlocked :action :take-coin}}}}})

(rf/reg-machine :turnstile/flow turnstile)

;; [:rf/machine ...] returns nil until the first event; render :initial until then.
(defn turnstile-view []
  (let [{:keys [state data]} (or @(rf/subscribe [:rf/machine :turnstile/flow])
                                 {:state (:initial turnstile) :data (:data turnstile)})
        open? (= state :unlocked)]
    [:div {:style {:font-family "sans-serif"}}
     [:p "state: " [:strong {:style {:color (if open? "green" "crimson")}} (str state)]]
     [:p "coins: " (:coins data) " · pushes: " (:pushes data)]
     [:button {:on-click #(rf/dispatch [:turnstile/flow [:coin]])} "insert coin"]
     [:button {:on-click #(rf/dispatch [:turnstile/flow [:push]])} "push"]]))

[turnstile-view]
```

> **Try it.** Push while `:locked` — nothing opens, but the push counter climbs (a self-transition running an action). Then add a third state: give `:unlocked` an `:on {:break {:target :broken}}`, add `:broken {:on {}}` to `:states`, add a button dispatching `[:turnstile/flow [:break]]`, re-evaluate. You added a reachable state by editing *one value* — no new handler, no `cond` surgery.

### One thing that *won't* throw: the unhandled event

If the current state has no transition for an event, it's a **silent no-op** — nothing throws, the snapshot doesn't move. Try it in the turnstile: dispatch `[:turnstile/flow [:wat]]`, an event no state handles, and the machine simply ignores it. (This is different from the `:push`-while-`:locked` case earlier: that state *does* declare a `:push` transition, so its action ran — here there's no transition at all.) The runtime still emits a benign `:rf.machine.event/unhandled-no-op` trace, so a debugger can show the event arrived and was dropped.

> **Coming from XState?** This matches XState, which dropped strict mode in v5 and keeps it dropped in the v6 direction: an unknown event is a no-op, not a crash. Almost everything *else* that's wrong (a guard referencing an undefined name, a target naming a missing state) [fails loud](../guide/glossary.md#fail-loud-not-silent) — but at *registration* time, not on the unlucky dispatch. More on that fail-loud / silent-no-op split below.

## Guards, actions, tags, and `:after` — the recognition kit

You've seen the core loop. The next four keys are the day-to-day grammar.

**Guards and actions both receive one context map** — `{:data :event :state :meta}` — and destructure whichever keys they need. Note what's *not* in that map: there is no `:db`. (That's deliberate, and the next section explains why it matters.)

A guard returns a boolean: take this transition, or don't. An action returns the same data-shaped [effect map](../guide/glossary.md#effect-map) a `reg-event` handler returns — `{:data ...}`, `:fx`, both, or `nil` (do nothing). The `:data` you return is *merged* into the snapshot's data slot, key by key, last write wins; returning a key as an explicit `nil` *sets* it to `nil` rather than removing it.

A transition's `:guard` and `:action` slot each take exactly one thing: a keyword referencing the machine's own `:guards` / `:actions` map (the usual form), or a bare inline fn. There's deliberately no `{:and ...}` combinator DSL for stringing guards together — compound logic goes in one named function instead, because the *name* is what a visualiser or an AI reads off the arrow. A nameless `{:and ...}` blob would render as noise.

**Tags answer the any-of-many question.** Once a machine has several "loading-ish" states, views stop asking "which exact state?" and start asking a predicate: *is it busy?* A state declares `:tags #{:auth/busy}` (as `:submitting` does above), and at every transition the runtime stamps the union of active states' tags onto the snapshot. The framework ships a derived predicate sub for the containment question — `[:rf/machine-has-tag? <machine-id> <tag>]` — that re-renders only when *this* tag's membership bit flips:

```clojure
(when @(rf/subscribe [:rf/machine-has-tag? :auth.login/flow :auth/busy])
  [spinner])
```

Add a fifth busy state later and it's one `:tags` entry on the new node — zero view changes. (*Ask, don't tell* — see [state tag](glossary.md#state-tag).) Reach for a plain `case` on `:state` when the question really is "which exact state?".

**`:after` is the declarative timer.** A state-node key maps a delay to a transition: enter the state, the timer arms; leave it, the timer cancels. (And if a timer from an earlier visit fires late — after you've already left and come back — the runtime tags each visit and ignores the stale one, so you never get a ghost transition from a previous trip through the state.) No `dispatch-later` to wire, no cancellation flag to remember:

```clojure
:reconnecting {:after {5000 {:target :connecting}}     ;; retry in 5s
               :on    {:net/give-up :failed}}
```

That one key replaces the `setTimeout`-plus-cancel-flag pattern behind most reconnect/timeout/debounce bugs. Full grammar in [Spec 005 §Delayed `:after` transitions](../../spec/005-StateMachines.md#delayed-after-transitions).

> **A machine sees only its own `:data`.** Strict encapsulation is locked: actions and guards get `{:state :data :event :meta}` and **never app-db**. That's exactly what lets a machine's whole state ride the frame and roll back with it. Returning `:db` from an action is a hard error (`:rf.error/machine-action-wrote-db` — the offending `:db` key is dropped). To touch a sibling slice, dispatch a named event: `{:fx [[:dispatch [:drawer/apply-radius id radius]]]}`. The reach is forced to be a traced, reusable event rather than a quiet write into someone else's data.

> **Gotcha — facts from the world are *declared*, not grabbed.** A guard or action that needs the time (or a random draw) must not call `(js/Date.now)`, because that buries nondeterminism where replay can't reach it — and replay is what makes time-travel and SSR hydration work. Instead, declare the fact as a [coeffect](../guide/glossary.md#coeffect) on a *named* entry and destructure it from the context map:
>
> ```clojure
> :guards
> {:within-retry-window?
>  {:rf.cofx/requires [:rf/time-ms]
>   :fn (fn [{:keys [data rf/time-ms]}]
>         (< (- time-ms (:first-attempt-at data)) 60000))}}
> ```
>
> The fact arrives recorded on the event's causal token (it's a *recordable* coeffect — see [recordable vs ambient coeffects](../guide/glossary.md#recordable-vs-ambient-coeffects)), so the decision replays identically under time-travel and SSR hydration. [Effects & coeffects](../guide/concepts/effects-and-coeffects.md) has the general mechanism — a coeffect is a fact pulled *into* a handler, the mirror of an effect pushed out.

> **Fail-loud, not silent.** Reference a `:guard`/`:action` keyword the machine's maps don't define and registration throws (`:rf.error/machine-unresolved-guard` / `…-unresolved-action`); a `:target` naming a state that isn't in `:states` is `:rf.error/machine-unresolved-target`. These are caught at `reg-machine` time, not on the unlucky dispatch that first hits the bad arrow. The one thing that's *not* an error is an event the current state has no transition for — that's the deliberate **silent no-op** above.

## Self-transitions: internal by default, external on demand

Look again at the turnstile. Pushing while `:locked` is a *self-transition* — `{:push {:target :locked :action :count-push}}` — and the push counter climbs even though the state never changes. That works because re-frame2 follows the **internal-by-default** rule. Three shapes are worth keeping straight, because they behave differently:

- **Targetless — a true internal no-op.** *Omit `:target` entirely.* The transition's `:action` runs; `:exit` and `:entry` do **not**; `:after` timers aren't restarted; `:spawn` children aren't torn down; active descendant states are left exactly as they are. This is how you mutate `:data` without disturbing anything else — `{:on {:typed {:action :record-keystroke}}}`.
- **Explicit target on the active path, no `:reenter?`.** Name yourself (`:target :same-state`, or your own state keyword) or an ancestor. Your *own* `:exit` / `:entry` still don't fire — but if you're a compound state, targeting yourself **re-resolves your descendants**: the active children below you exit and your `:initial` chain re-descends. So this is *not* a no-op for a compound; it resets the subtree to its initial child. (Reach for the targetless form when you want descendants preserved.)
- **External — `:reenter? true`.** Add `:reenter? true` to the target and the state is genuinely **exited and re-entered**: `:exit` runs, then the transition's `:action`, then `:entry`. On a compound, the whole subtree restarts — `:after` timers reset to zero, `:spawn` children tear down and respawn.

```clojure
:polling
{:entry :start-fetch                                    ;; kicks off a request
 :after {30000 {:target :polling :reenter? true}}        ;; every 30s: re-enter → :start-fetch fires again
 :on    {:got-data {:action :merge}                       ;; arrives mid-window: internal, no re-fetch
         :stop     :idle}}
```

Here the `:after` self-transition is **external** (`:reenter? true`), so re-entering `:polling` re-runs `:start-fetch` and rearms the 30-second timer — a self-rescheduling poll in two keys. The `:got-data` transition is **internal** (no target), so a reply landing mid-window merges into `:data` without resetting the clock. Picking internal vs external per transition is exactly the control this rule buys you.

> **Coming from XState v4 / SCXML?** re-frame2's internal-by-default matches XState v5+ (and the v6 direction), where `:reenter? true` is XState's `reenter: true` spelled with a Clojure `?`. But if you trained on XState v4 or hand-wrote SCXML, you expect a *targeted* self-transition to re-enter by default — re-frame2 does **not**. A self-target you *meant* to re-fire `:entry` on needs `:reenter? true`; without it, only the transition `:action` runs. Full mechanics, including ancestor restarts and the one `:always` restriction (an eventless `:always` may never self-target), are in [Spec 005 §Self-transitions](../../spec/005-StateMachines.md#self-transitions--internal-default-vs-external-reenter).

## Wildcard transitions: handle a whole class of events

Sometimes a state should react to *any* event in a family rather than enumerate each one. `:on` resolves **three tiers**, most-specific first, and you can register a handler at any tier:

1. the **exact** event id — `:mouse/down`;
2. the **namespace wildcard** `:ns/*` — `:mouse/*` matches every event in the `mouse` namespace (`:mouse/down`, `:mouse/up`, `:mouse/move`) and *only* those (it won't catch `:keyboard/down`);
3. the **total wildcard** `:*` — matches anything.

```clojure
:tracking
{:on {:mouse/down {:action :begin-drag}    ;; exact wins for :mouse/down
      :mouse/*    {:action :note-move}      ;; any other :mouse/… event
      :*          {:action :log-unknown}}}  ;; anything else
```

One subtlety worth knowing: a guard-**blocked** exact match falls through to the coarser tiers (it wasn't *handled*), but a deliberate **forbidden block** — `{:on {:E {}}}` or `{:on {:E nil}}` — is itself a handler that *consumes* the event and stops the search. That distinction is how a child state opts out of an event its parent would otherwise handle. Full precedence rules in [Spec 005 §Wildcard transitions](../../spec/005-StateMachines.md#wildcard-transitions).

> **Coming from XState?** The namespace tier is re-frame2's idiom for XState v5's prefix descriptor (`mouse.*`) — same behaviour (one prefix level between exact and catch-all), expressed on the keyword's `/` boundary instead of a dotted string. A bare, non-namespaced id like `:go` has no `:ns/*` tier; only its exact key or `:*` can catch it.

## Final states: when a machine is *done*

The login flow above parks in `:authed` and `:locked-out` forever — ordinary leaf states with no outgoing transitions, which is right when the machine *persists* (a view keeps reading the snapshot). But some machines genuinely *finish*: a spawned per-request protocol machine completes and should report back. For those, a leaf declares **`:final? true`**, and entering it **terminates the machine** — the runtime auto-destroys it.

> **Gotcha — final means final.** A `:final? true` leaf auto-destroys *even a top-level singleton machine*. If you want a state the machine rests in indefinitely (like `:authed`), use an ordinary leaf and **omit `:final?`** — exactly what the login flow does. `:final?` is for "this run is over," not "this is the last screen."

The payoff is composition. A spawned child names a result key with `:output-key`; its parent's `:spawn` declares `:on-done`, which fires the moment the child finishes and receives that value as `:result`:

```clojure
;; Child — a one-shot auth handshake that reports its token, then ends.
(rf/reg-machine :auth-flow
  {:initial :running
   :data    {}
   :states
   {:running {:on {:server-ok {:target :done
                               :action (fn [{data :data ev :event}]
                                         {:data (assoc data :token (second ev))})}}}
    :done    {:final?     true
              :output-key :token}}})       ;; report :data's :token back to the parent

;; Parent — :on-done reads the child's result and folds it into its own :data.
(rf/reg-machine :login
  {:initial :idle
   :states
   {:idle           {:on {:submit :authenticating}}
    :authenticating {:spawn {:machine-id :auth-flow
                             :on-done    (fn [{data :data result :result}]
                                           (assoc data :token result))}
                     :on    {:auth/cancelled :idle}}
    :authenticated  {}}})
```

When `:auth-flow` enters `:done`, the runtime reads its `:token`, hands it to the parent's `:on-done` as `result`, then tears the child down — no stale id left behind. A `:final?` leaf can also be flagged `:error? true` (a designated *error* terminal); if the parent's `:spawn` declares an `:on-error` transition, a failing child routes there instead. Full grammar, the singleton-symmetry rule, and the `[:rf.machine/done …]` signal that lets a *compound* state advance on its substates finishing are in [Spec 005 §Final states](../../spec/005-StateMachines.md#final-states-final--on-done--output-key).

## Validating a machine's `:data`

A machine's `:data` is just a map, and a typo there (`:cirles` for `:circles`) is the same silent rot any app-db shape is prone to. So a machine spec may declare a machine-level **`:schemas`** map whose **`:data`** entry — a [Malli schema](../guide/glossary.md#schema), the same machinery events and subscriptions use — validates the `:data` slot. The `:schemas` map is the single home for all of a machine's schema declarations; `:data` is the first of them that's actually wired up to run:

```clojure
(rf/reg-machine :drawer/editor
  {:initial :idle
   :data    {:circles [] :undo [] :redo []}
   :schemas {:data DrawerData}                      ;; validates :data at every transition
   :guards  {...}
   :actions {...}
   :states  {...}})
```

The check runs at the [commit](../guide/glossary.md#commit) — the machine's *macrostep*, the one deferred runtime-db write a transition lands — once per transition no matter how many actions fired, plus at bootstrap and at spawn time. A violation emits a structured `:rf.error/schema-validation-failure` with `:where :machine-data` and **rolls the whole transition back**, so an invalid `:data` never reaches runtime-db. Like every schema in re-frame2, it's dev-only by default: the validation site is `debug-enabled?`-gated and is [elided](../guide/glossary.md#elide) under `:advanced` production builds.

To *also* validate the inbound event vector, use the three-argument `reg-machine` arity, where the middle `opts` map carries an event `:schema` (the ordinary `:where :event` boundary that runs before the handler):

```clojure
(rf/reg-machine :auth.login/flow
  {:schema AuthLoginEvent}                  ;; validates the OUTER [:auth.login/flow [...]] event
  {:initial :idle
   :schemas {:data AuthLoginData}           ;; validates the machine's :data
   :data    {:attempts 0 :error nil}
   :states  {...}})
```

The `:schemas` map's sub-keys are a closed set. Two of them — `:data` and `:output` — are wired up to actually run a check (you've now seen both). The other three — `:events`, `:tags`, and `:meta` — are accepted so you can declare them today, but they don't validate anything yet; they're reserved for future wiring. Either way, a sub-key *outside* the set (a typo, or one you hoped was live but isn't) fails loud at registration rather than silently validating nothing.

> **Coming from TypeScript?** The `:schemas` map follows the XState v6 direction, which replaces v5's `types` with a broader `schemas` section. But there's a runtime guarantee TypeScript's typed context *can't* give you: TS types are erased before the machine ever runs, whereas `[:schemas :data]` is an *actually-running* validation in dev that rolls a bad transition back. Same declaration, plus the runtime check the type-erased layer leaves out.

> **Fail-loud guard.** Because the schema only does its job through `reg-machine`'s registration stamp, hand-rolling `(reg-event id meta (machines/make-machine-handler spec))` around a `[:schemas :data]`-bearing spec is rejected with `:rf.error/machine-schema-requires-reg-machine` — the framework refuses to let your schema sit there validating nothing. A schema-less spec is fine through either path.

### Validating a machine's completion output

The other wired `:schemas` category is **`:output`** — it validates the machine's **completion-output payload**: the value a finishing machine selects from its final state's `:data` via `:output-key` (the `result` its parent's `:on-done` receives). re-frame2 keeps completion *event-shaped* — there's no long-lived `snapshot.output` slot — so `[:schemas :output]` schemas the value *as it flows*, validated at the moment the machine finishes:

```clojure
(rf/reg-machine :auth-flow
  {:initial :running
   :data    {}
   :schemas {:output :string}                 ;; the :output-key payload must be a string
   :states  {:running {:on {:server-ok {:target :done
                                         :action (fn [{data :data ev :event}]
                                                   {:data (assoc data :token (second ev))})}}}
             :done    {:final?     true
                       :output-key :token}}})  ;; ← this :token value is what gets validated
```

Unlike the `:data` boundary, output validation is **best-effort fail-loud**: the machine has *already* reached its final state when its output is checked, so a violation emits `:rf.error/schema-validation-failure` with `:where :machine-output` **loudly** but the completion still flows — there's nothing to roll back, and a schema typo surfaces the mismatch without deadlocking a finishing machine. Same dev-only posture as `:data` (`debug-enabled?`-gated, elided in production) and the same optional validator adapter — a project with no schema library still uses the grammar and pays zero cost.

## Testing: transitions are pure function calls

This is where machines pay you back hardest. `machine-transition` runs one transition with no frame, no browser, no mocks. Table in, snapshot in, event in; result out:

```clojure
(ns my-app.login-flow-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.machines :as machines]
            [re-frame.machines.result :as result]
            [my-app.login :refer [login-flow]]))

(deftest login-flow-test
  ;; happy path: :idle --submit--> :submitting (fires the request fx)
  (let [s0 {:state :idle :data {:attempts 0 :error nil}}
        {s1 ::result/snap fx1 ::result/fx}
        (machines/machine-transition login-flow s0
                                     [:auth.login/submit {:email "a@b.com" :password "secret"}])]
    (is (= :submitting (:state s1)))
    (is (= :rf.http/managed (ffirst fx1)))      ;; :entry ran :issue-request

    ;; at the retry limit the guard rejects :error-shown; :locked-out wins
    (let [{s2 ::result/snap}
          (machines/machine-transition login-flow
                                       {:state :submitting :data {:attempts 3 :error nil}}
                                       [:auth.login/failure {:failure {:message "bad creds"}}])]
      (is (= :locked-out (:state s2))))))
```

The return value is a result map. Destructure `::result/snap` and `::result/fx`, or discriminate with `result/ok?` / `result/fail?` — a throwing action surfaces as a *failure value*, not an exception out of your test, so one assertion style covers both paths. These run on the JVM in microseconds, which is exactly the testing experience you want for the flows where testing usually gets hard. The complete login flow with these tests lives at [`examples/reagent/state_machine_walkthrough/`](https://github.com/day8/re-frame2/tree/main/examples/reagent/state_machine_walkthrough) and runs on every CI pass.

## When the machine grows

The flat grammar above carries most machines. When a flow gets richer, the grammar grows *without changing the model* — each of these is the same transition-table data, one more key. You only need to recognise them here; the contracts live in [Spec 005](../../spec/005-StateMachines.md):

- **`:raise` loops an event back into this machine.** An action can return `:fx [[:raise [:some-event …]]]` to fire an event *at its own machine*, atomically, before the transition commits — useful when one transition's outcome should immediately drive another (a wizard step that completes and re-asks "is the whole form done?"). It's the in-machine equivalent of XState's `raise`. `:raise` is a reserved fx-id, alongside `[:rf.machine/spawn …]` and `[:rf.machine/destroy …]`, the actor-lifecycle pair behind the `:spawn` sugar. Everything else in `:fx` — `:dispatch`, `:rf.http/managed`, your own effects — flows to the ordinary effects machinery untouched.
- **`:internal-events` makes an event private.** Some events are machine *plumbing* — a `:check-complete` the machine raises at itself, not for a view or test to dispatch. Declare those in a top-level `:internal-events` **set** (`#{:check-complete}`); an internal `:raise` is handled normally, but an *external* dispatch is **refused** at the machine's boundary (no transition; a `:rf.error/machine-internal-event-external-dispatch` trace fires). It's XState v6's `internalEvents`, spelled as a Clojure set because membership is what you're declaring, not order. [§Public / private `:internal-events`](../../spec/005-StateMachines.md#public--private-internal-events).
- **`:type :choice` is a decision node.** A choice state is a *transient* routing node: enter it and it resolves immediately — same step, no event — to the first guarded candidate whose `:guard` passes. It's `:always`'s decision pattern with a name, so diagrams render an explicit fork. The `:choice` value is a **declarative candidate array** (re-frame2 keeps the routing as *data*, rejecting XState v6's `choice`-*function* form); it must include an unguarded default and must not declare ordinary state keys. [§`:type :choice`](../../spec/005-StateMachines.md#type-choice-transient--choice-states).
- **`:timeout` / `:on-timeout` names a deadline.** Where `:after` is the general timer table, `:timeout` is the named-intent spelling of "this state — or its spawned child — must finish before this time," pairing a duration with an `:on-timeout` transition (it lowers onto the same `:after` timer). A duration is a positive-integer millisecond count (`5000`) or an ISO-8601 string (`"PT5S"`, `"PT1H30M"`) — re-frame2 rejects XState's `"5s"` shorthand, failing loud at registration. [§`:timeout` / `:on-timeout`](../../spec/005-StateMachines.md#timeout--on-timeout-state--spawn).
- **Hierarchical states** — a compound state contains sub-states; entering the parent cascades to its `:initial` child (an `:authenticated` super-state over `:browsing` / `:checkout`). [§Hierarchical compound states](../../spec/005-StateMachines.md#hierarchical-compound-states).
- **Eventless `:always`** — fires when a guard becomes true, no event needed. [§Eventless `:always` transitions](../../spec/005-StateMachines.md#eventless-always-transitions).
- **Parallel regions** — one machine, several orthogonal axes active at once (`:type :parallel` + `:regions`) sharing one `:data`; the snapshot's `:state` becomes a map of region → state, tags union across regions. Three axes of 3 states each is 3 regions, not 27 cross-product states. [§Parallel regions](../../spec/005-StateMachines.md#parallel-regions); when the axes *don't* share data, prefer N separate machines — the trade-off is worked in the [CP-5 guide](../../spec/CP-5-MachineGuide.md).
- **History states** — re-enter a compound at the substate it was in when you left (a paused player resumes mid-track), via a `:type :history` pseudo-state. The recording rides the snapshot, so it survives undo and hydration for free. [§History states](../../spec/005-StateMachines.md#history-states-type-history--shallow--deep--default-target).
- **Spawned actors** — machines that aren't long-lived singletons: a per-request protocol machine, a wizard's per-step subprocess. The declarative [`:spawn`](glossary.md#spawn) key starts a child on state entry and destroys it on exit (XState's `invoke`, deliberately renamed). [§Declarative `:spawn`](../../spec/005-StateMachines.md#declarative-spawn).

> **Gotcha — a runaway `:always` / `:raise` cycle is a *failed* macrostep, not a hang.** Eventless transitions and `:raise` both re-enter the transition machinery within the same macrostep, so a non-converging loop (`:a` `:always`-targets `:b`, `:b` `:always`-targets `:a`, both guards true) could spin forever. It can't: each is bounded by a depth limit (default **16**, set per-frame via `:always-depth-limit` / `:raise-depth-limit`). Trip it and the macrostep **aborts atomically** — the snapshot stays at its pre-event value, no observer sees the partial path — and a single `:rf.error/machine-always-depth-exceeded` (or `:rf.error/machine-raise-depth-exceeded`) [error record](../guide/glossary.md#error-record) fires. This is XState v5 parity (XState *throws* on such a cycle), and it is deliberately **distinct** from the silent no-op of an unhandled event: a runaway loop is a bug you want surfaced, not swallowed. There's no automatic recovery — the runtime can't guess what a non-converging cycle was *meant* to do.

> **Gotcha — an `:always` may not target itself.** `{:checking {:always {:target :checking}}}` is rejected at *registration* with `:rf.error/machine-always-self-loop`: re-entering a state to re-test the same guard either spins to the depth limit (guard stays true) or no-ops (guard flips) — neither is what you meant. The fixed-point loop you actually want is a **targetless** guarded `:always` with an `:action` that flips the guard — `{:always {:guard :more? :action :bump}}` — which runs and settles; or an intermediate distinct state if you genuinely need entry/exit to re-fire.

## Coming from XState? The five-row delta

XState's *behaviour* is the reference re-frame2 matches; the *expression* is re-frame-native. The rows below hold across XState versions, and several are exactly where the **v6 direction** is heading anyway — v6 removes the `assign` helper and the `setup()` implementation registry, leaning toward plain functions, which is the shape re-frame2 already has:

| XState | re-frame2 | The difference, and why |
|---|---|---|
| `context` (extended state) | `:data` | Same idea; "context" is already overloaded in re-frame2 (interceptor context, React context). |
| `createActor(machine).start()`, then `actor.send({type: ...})` | the machine **is an event handler**; `(rf/dispatch [machine-id [event]])` | **The big one.** No actor object, no separate send mechanism — one router queue, one cascade. (v6 replaces v5's `interpret` with `createActor`; re-frame2 has neither — machines are event handlers.) |
| actions that imperatively `assign(...)` / fire effects | actions **return** `{:data ... :fx ...}` | The same data-shaped return as any `reg-event` handler; effects are data, actioned by the runtime. v6 removes the `assign` helper creator — re-frame2 never had it. |
| state lives in the actor; `actor.getSnapshot()` | the snapshot is a value in runtime-db, read via `@(rf/subscribe [:rf/machine id])` | Time-travel, undo, persistence, and SSR hydration extend to machines for free. |
| `setup({guards, actions})` | machine-local `:guards` / `:actions` maps inside the spec | Each machine carries its own, validated at registration; cross-machine reuse is ordinary Clojure vars, not a string registry. v6 already simplifies `setup()` away from implementation registration. |

The matches go deeper than the renames: run-to-completion, transition tables as data, tags, delayed transitions, final states, and internal-by-default self-transitions. An XState author ports their intuitions directly. The full divergence ledger is in the [machine construction guide](../../spec/CP-5-MachineGuide.md).

> **Where re-frame2 diverges on purpose.** re-frame2 tracks the v6 *direction*, not a JavaScript runtime. Three divergences are worth holding in mind: (1) **function-valued transitions are rejected** — guards and actions are functions, but the transition *topology* (targets, `:always`, `:after`) stays declarative data so diagrams, tools, and AI can read the graph; (2) **frame dispatch + runtime-db snapshots instead of actor objects** — no `createActor`, no actor refs, no mailboxes; (3) **completion is event-shaped** — a child's final-state output flows to the parent's `:on-done` callback as `result`, not a long-lived `snapshot.output` slot. Several v6-direction features that motivate new grammar have already landed: the broader `:schemas` map, explicit `:timeout` / `:on-timeout`, `:type :choice` transient states, and private `:internal-events`.

## When to reach for a machine — and when not

**Reach for one when:** the flow has named, mutually-exclusive stages (handlers that `cond` on a state field are the tell); transitions are conditional (`(when (ready? db) ...)` scattered across handlers are guards in disguise); the flow is worth drawing on a whiteboard — the diagram *is* the machine.

**Don't reach for one when:** the "state" is just data (a counter, a list); there are only two stages (a `:loading?` boolean is fine); the lifecycle belongs to server data — fetching, caching, invalidation is what [resources](../resources/concepts.md) already manage, and hand-building that machine re-implements the framework; or you're enforcing a *sequence of operations* rather than a set of states — chained events handle the simple cases.

**Reach for machines when named states are the load-bearing concept — not when named operations are.** [Part 3 of the tutorial](../resources/tutorial/03-auth-and-forms.md) puts a login machine to work in a real app, and [Server state: resources](../resources/concepts.md) covers the one lifecycle you should *not* hand-build as a machine — the framework already runs it for you.
