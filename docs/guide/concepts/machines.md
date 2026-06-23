# State machines

Some flows aren't "set a flag." They're "what state are we even *in*?" A login can be idle, submitting, authed, error-shown, or locked-out. A websocket can be connecting, connected, dropped, reconnecting. For flows like those, the interesting question isn't what value sits in app-db (your app's single state map). It's which of a fixed set of **named states** you're in, and which events — the messages your app reacts to — move you between them. re-frame2 makes that shape first-class, so you don't have to reconstruct it from scattered code.

The anchor here is [**XState**](https://stately.ai/docs) — and specifically the direction XState is heading in **v6** (still on the `alpha` dist-tag, but settled enough to track). re-frame2's machine grammar deliberately borrows its vocabulary and behaviour: transition tables, guards, actions, tags, `:after`, run-to-completion. There's one big difference, and it's worth saying up front: a machine is not an actor object you create and `send` to. It's an event handler — a function that receives an event and decides what happens next. The full delta is a table below. re-frame2 tracks the v6 *direction*, not exact compatibility with an alpha release — where the two differ on purpose, this page says so.

> **Deciding where a value should live?** A machine is the right home when a value has a *lifecycle* — named states, timers, retries, cancellation — rather than just a value you read. [Where should this value live?](../where-state-lives.md) has the full decision procedure.

## The flow hiding in your `cond`s

You already write state machines. You just call them other things. The keyword you stuffed into app-db — `:idle`, `:submitting`, `:authed` — plus the rules in your head about which states can legally follow which: that's a machine, written informally. Here's a login flow written the way most people write it first:

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

This works. It also has three problems, and the trouble is that each one spreads as the flow grows:

1. **The transition rules are scattered.** `:submitting` is reachable from `:idle` but not from `:locked-out`. That rule is buried in cond clauses. To see the full state graph you have to read every handler — every function that processes an event — that touches `:auth/state`.
2. **Shared logic duplicates.** "3 attempts" appears in two handlers. Change it to 5 and you'd better remember both, because nothing connects them.
3. **Adding a state is a chore.** A `:two-factor` step between `:submitting` and `:authed` means a new keyword, a new handler, *and* edits to every handler that assumed which states were valid where.

The fix isn't better `cond` clauses. It's spotting the shape and writing it down as data.

## The same flow as a transition table

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

You can read this one top to bottom. Five states. `:idle` starts. Submit takes `:idle` to `:submitting`. From there, success goes to `:authed`, failure goes to `:error-shown` *if* the `:under-retry-limit` guard passes, and otherwise to `:locked-out`. Guards and actions are referenced by id — a guard being a yes/no test that gates a transition, an action being the side work a transition performs — and their implementations live once, up top.

Now watch the three problems disappear. The transition rules are all in one place. The retry limit lives in exactly one guard. Adding `:two-factor` is one new state node plus the arrows in and out, so the existing nodes don't move. The whole flow is *one piece of data*, which means you can pretty-print it, render it as a diagram, or hand it to an AI with "add a two-factor state" — the AI gets the entire context in one form, instead of having to chase logic across files.

## `reg-machine`, demystified

Registering the table is one line, and this is where people sometimes brace for a new runtime concept. There isn't one:

```clojure
(rf/reg-machine :auth.login/flow login-flow)
;; exactly equivalent to (machines/make-machine-handler is in re-frame.machines):
;; (rf/reg-event :auth.login/flow (machines/make-machine-handler login-flow))
```

> **One-time setup.** Machines ship in their own artefact, `day8/re-frame2-machines`, so apps without machines build a bundle clean of them. Add the dep and require `re-frame.machines` once at app boot — that registers the hooks through which `rf/reg-machine`, `rf/machine-transition`, and the framework `:rf/machine` / `:rf/machine-has-tag?` subs resolve.

A machine **is** an event handler. It's a `reg-event` whose body interprets the transition table: look up the snapshot, compute the transition, write it back, return the action's effects (the effects being the data describing what should happen in the world — the HTTP call, the storage write). Every event reaches it through the same `dispatch` — the call that sends an event into the system — and the same [cascade](events-and-the-cascade.md) as everything else. There's no actor object and no second messaging system, which is the point: one mechanism, used everywhere. (`reg-machine` is a macro that also stamps dev-only source coordinates so tools can jump from a diagram arrow to your code; production builds elide them.)

> **`reg-machine` vs `reg-machine*`.** This splits exactly like Clojure's `fn` / `fn*`. The `reg-machine` **macro** is what you reach for in source — it walks the literal spec at compile time and co-locates source coordinates onto every guard, action, and transition so a tool can jump from a diagram arrow back to the line of code. `reg-machine*` is the plain **function** underneath, for the cases a macro can't serve — a REPL session, a code-gen pipeline that builds specs at runtime, a conformance harness loading machines from EDN. The macro lives on the `rf/` facade; the plain fn stays in its home namespace as `re-frame.machines/reg-machine*`. There's also `defmachine`, a `def`-shaped macro for the "define the spec as a Var, register it elsewhere" pattern — `(rf/defmachine login-flow {…})` then `(rf/reg-machine :auth.login/flow login-flow)` — which stamps source at the *definition* site so a value-registered machine keeps its tool legibility.

Dispatching routes through the machine's id, wrapping an inner event vector:

```clojure
(rf/dispatch [:auth.login/flow [:auth.login/submit credentials]])
```

If the current state has no transition for an event, it's a **silent no-op** — nothing throws, matching XState (which dropped strict mode in v5 and keeps it dropped in the v6 direction). The runtime emits a benign `:rf.machine.event/unhandled-no-op` trace so a debugger can still show that the event arrived and was ignored.

The snapshot — `{:state :submitting :data {:attempts 1 :error nil}}` — lives in the frame's **runtime-db** at `[:rf.runtime/machines :snapshots :auth.login/flow]`, kept apart from your app data. A frame is one isolated instance of your running app, and the snapshot is just a value riding it, so undo, time-travel, persistence, and SSR hydration all work on machines for free. Views — the functions that turn state into UI — read the snapshot through a subscription, a reactive query that recomputes when its inputs change. The canonical read is the framework-registered `:rf/machine` sub, addressed by the machine's id:

```clojure
@(rf/subscribe [:rf/machine :auth.login/flow])
;; => {:state :submitting :data {:attempts 1 :error nil}}  (nil before the first event)
```

There's nothing machine-special about that call — `[:rf/machine <id>]` is an ordinary subscription vector, the same shape you'd write for any registered sub, so it's traceable and introspectable like the rest of your signal graph. Named projections chain off it — `(rf/reg-sub :auth.login/error :<- [:rf/machine :auth.login/flow] ...)` — like any other [subscription](subscriptions.md).

It's worth pausing on the async wiring in `:issue-request`. `:on-success [:auth.login/flow [:auth.login/success]]` is a two-element template. The HTTP effect appends its reply payload and the runtime folds it onto the *inner* event, so `:store-session` sees `[:auth.login/success {:kind :success :value v}]` — exactly the payload [managed HTTP](http.md) sends. Machines and async effects compose with no adapter layer in between.

**Do, then observe.** Dispatch one event with Xray open. The transition shows up as an ordinary event row, snapshot before and after, riding the same trace stream as everything else — see [Debug with Xray](../how-to/debug-with-xray.md).

## Coming from XState? The five-row delta

XState's behaviour is the reference re-frame2 matches; the *expression* is re-frame-native. The rows below hold across XState versions, and several of them are exactly where the **v6 direction** is heading anyway — v6 removes the `assign` helper and the `setup()` implementation registry, leaning toward plain functions, which is the shape re-frame2 already has. Here are the rows that matter:

| XState | re-frame2 | The difference, and why |
|---|---|---|
| `context` (extended state) | `:data` | Same idea; "context" is already overloaded in re-frame2 (interceptor context, React context). |
| `createActor(machine).start()`, then `actor.send({type: ...})` | the machine **is an event handler**; `(rf/dispatch [machine-id [event]])` | **The big one.** No actor object, no separate send mechanism — one router queue, one cascade. (v6 replaces v5's `interpret` with `createActor`; re-frame2 has neither — machines are event handlers.) |
| actions that imperatively `assign(...)` / fire effects | actions **return** `{:data ... :fx ...}` | The same data-shaped return as any `reg-event` handler; effects are data, actioned by the runtime. v6 removes the `assign` helper creator — re-frame2 never had it. |
| state lives in the actor; `actor.getSnapshot()` | the snapshot is a value in runtime-db, read via `@(rf/subscribe [:rf/machine id])` | Time-travel, undo, persistence, and SSR hydration extend to machines for free. |
| `setup({guards, actions})` | machine-local `:guards` / `:actions` maps inside the spec | Each machine carries its own, validated at registration; cross-machine reuse is ordinary Clojure vars, not a string registry. v6 already simplifies `setup()` away from implementation registration — re-frame2's machine-local maps are the same direction. |

The matches go deeper than the renames: run-to-completion, transition tables as data, tags, delayed transitions, final states, and the internal-by-default self-transitions (re-frame2's `:reenter? true` is XState's `reenter: true`). An XState author ports their intuitions directly. The full divergence ledger is in the [machine construction guide](../../../spec/CP-5-MachineGuide.md).

> **Where re-frame2 diverges on purpose.** re-frame2 tracks the v6 *direction*, not a JavaScript runtime. Three divergences are worth holding in mind: (1) **function-valued transitions are rejected** — guards and actions are functions, but the transition *topology* (targets, `:always`, `:after`) stays declarative data so diagrams, tools, and AI can read the graph; (2) **frame dispatch + runtime-db snapshots instead of actor objects** — no `createActor`, no actor refs, no mailboxes; (3) **completion is event-shaped** — a child's final-state output flows to the parent's `:on-done` callback as `result`, not a long-lived `snapshot.output` slot. The v6-direction features that motivate new grammar (broader `:schemas`, explicit `:timeout`, `:choice` states, `:internal-events`) land in later re-frame2 work; this page teaches the model, not those not-yet-shipped slots.

> **Coming from re-frame v1?** Machines don't exist there — the keyword-in-app-db + `cond` pattern above *is* the v1 shape this replaces. Nothing to unlearn; see [From re-frame v1](../25-from-re-frame-v1.md).

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

> **Try it.** Push while `:locked` — nothing opens, but the push counter climbs (a self-transition running an action). Then add a third state: give `:unlocked` an `:on {:break {:target :broken}}`, add `:broken {:on {}}` to `:states`, add a button dispatching `[:turnstile/flow [:break]]`, re-evaluate. You added a reachable state by editing *one piece of data* — no new handler, no `cond` surgery.

## Guards, actions, tags, `:after` — the recognition kit

**Guards and actions receive one context map** — `{:data :event :state :meta}` — and destructure what they need. A guard returns a boolean. An action returns `{:data ...}` (merged into the data slot — last write wins per key; an explicit `nil` *sets* the key to `nil` rather than removing it), `:fx` (effects), both, or `nil` — the same contract as a `reg-event` return. Each slot takes one fn or one keyword reference into the machine's own `:guards` / `:actions` map. There's deliberately no `{:and ...}` combinator DSL — compound logic is a named function instead, because the *name* is what a visualiser or an AI reads on the transition arrow.

> **A machine sees only its own `:data`.** Strict encapsulation is locked: actions and guards get `{:state :data :event :meta}` and **never `app-db`**. That's what lets a machine's whole state ride the frame and roll back with it. Returning `:db` from an action is a hard error (`:rf.error/machine-action-wrote-db`, the `:db` key dropped) — to touch a sibling slice, dispatch a named event: `{:fx [[:dispatch [:drawer/apply-radius id radius]]]}`. The reach is forced to be a traced, reusable event rather than a quiet write into someone else's data.

> **Fail-loud, not silent.** Reference a `:guard`/`:action` keyword the machine's `:guards`/`:actions` map doesn't define and registration throws (`:rf.error/machine-unresolved-guard` / `…-unresolved-action`); a `:target` naming a state that isn't in `:states` is `:rf.error/machine-unresolved-target`. These are caught at `reg-machine` time, not on the unlucky dispatch that first hits the bad arrow. The one thing that is *not* an error is an event the current state has no transition for — that's a deliberate **silent no-op** (it emits a benign `:rf.machine.event/unhandled-no-op` trace), matching XState. And if you forget to require the machines artefact at boot, `reg-machine` throws `:rf.error/machines-artefact-missing` pointing you at the one-time setup above.

**Facts from the world are declared, not grabbed.** This one trips people up. A guard or action that needs the time (or a random draw) must not call `(js/Date.now)`, because that buries nondeterminism where replay can't reach it — and replay is what makes time-travel and SSR hydration work. Instead, declare the fact on a *named* entry and destructure it from the context map:

```clojure
:guards
{:within-retry-window?
 {:rf.cofx/requires [:rf/time-ms]
  :fn (fn [{:keys [data rf/time-ms]}]
        (< (- time-ms (:first-attempt-at data)) 60000))}}
```

The fact arrives recorded on the event's causal token, so the decision replays the same way under time-travel and SSR hydration — [Effects and coeffects](effects-and-coeffects.md) has the general mechanism (a coeffect being a fact pulled *into* a handler, the mirror of an effect pushed out).

**Tags answer the any-of-many question.** Once a machine has several "loading-ish" states, views stop asking "which exact state?" and start asking a predicate: *is it busy?* A state declares `:tags #{:auth/busy}` (as `:submitting` does above), and at every transition the runtime stamps the union of active states' tags onto the snapshot. The framework ships a derived predicate sub for the containment question — `[:rf/machine-has-tag? <machine-id> <tag>]` — that re-renders only when *this* tag's membership bit flips:

```clojure
(when @(rf/subscribe [:rf/machine-has-tag? :auth.login/flow :auth/busy])
  [spinner])
```

Add a fifth busy state later and it's one `:tags` entry on the new node — zero view changes. Reach for a plain `case` on `:state` when the question really is "which exact state?".

**`:after` is the declarative timer.** A state-node key maps a delay to a transition: enter the state, the timer arms; leave it, the timer cancels (stale timers from a prior visit are epoch-detected and ignored). So there's no `dispatch-later` to wire and no cancellation flag to remember:

```clojure
:reconnecting {:after {5000 {:target :connecting}}     ;; retry in 5s
               :on    {:net/give-up :failed}}
```

That one key replaces the `setTimeout`-plus-cancel-flag pattern that sits behind most reconnect/timeout/debounce bugs. Full grammar in [Spec 005 §Delayed `:after` transitions](../../../spec/005-StateMachines.md#delayed-after-transitions).

**`:raise` loops an event back into this machine.** An action can return `:fx [[:raise [:some-event …]]]` to fire an event *at its own machine*, atomically, before the macrostep commits. It's the in-machine equivalent of XState's `raise` — useful when one transition's outcome should immediately drive another (a wizard step that completes and re-asks "is the whole form done?"). `:raise` is a reserved fx-id, alongside two more the machine runtime recognises inside an action's `:fx`: `[:rf.machine/spawn <spawn-spec>]` and `[:rf.machine/destroy <actor-id>]`, the actor-lifecycle pair behind the `:spawn` sugar below. Everything else in `:fx` — `:dispatch`, `:rf.http/managed`, your own registered effects — flows to the ordinary effects machinery untouched:

```clojure
:actions
{:advance
 (fn [{data :data}]
   {:data {:step (inc (:step data))}
    :fx   [[:raise [:check-complete]]]})}      ;; re-enters THIS machine, pre-commit
```

## Self-transitions: internal by default, external on demand

Look again at the turnstile. Pushing while `:locked` is a *self-transition* — `{:push {:target :locked :action :count-push}}` — and the push counter climbs even though the state never changes. That works because re-frame2 follows XState v5's (and the v6 direction's) **internal-by-default** rule, which trips up anyone who learned XState v4 or SCXML, where targeted self-transitions re-enter by default. There are three shapes worth keeping straight, because they behave differently:

- **Targetless — a true internal no-op.** *Omit `:target` entirely.* The transition's `:action` runs; `:exit` and `:entry` do **not**; `:after` timers are not restarted; `:spawn` children are not torn down; any active descendant states are left exactly as they are. This is how you mutate `:data` without disturbing anything else — `{:on {:typed {:action :record-keystroke}}}`.
- **Explicit target on the active path, no `:reenter?`.** Name yourself (`:target :same-state`, or your own state keyword), or an ancestor. Your *own* `:exit` / `:entry` still don't fire — but if you're a compound state, targeting yourself **re-resolves your descendants**: the active children below you exit and your `:initial` chain re-descends. So this is *not* a no-op for a compound; it resets the subtree to its initial child. (Reach for the targetless form when you want descendants preserved.)
- **External — `:reenter? true`.** Add `:reenter? true` to the target and the state is genuinely **exited and re-entered**: `:exit` runs, then the transition's `:action`, then `:entry`. On a compound, the whole subtree restarts — `:after` timers reset to zero, `:spawn` children tear down and respawn. This is XState's `reenter: true`, spelled with a Clojure `?`.

```clojure
:polling
{:entry :start-fetch                                    ;; kicks off a request
 :after {30000 {:target :polling :reenter? true}}        ;; every 30s: re-enter → :start-fetch fires again
 :on    {:got-data {:action :merge}                       ;; arrives mid-window: internal, no re-fetch
         :stop     :idle}}
```

Here the `:after` self-transition is **external** (`:reenter? true`), so re-entering `:polling` re-runs `:start-fetch` and rearms the 30-second timer — a self-rescheduling poll in two keys. The `:got-data` transition is **internal** (no target), so a reply landing mid-window merges into `:data` without resetting the clock. Picking internal vs external per transition is exactly the control this rule buys you.

> **Gotcha — the v4/SCXML muscle memory.** If you trained on XState v4 or hand-wrote SCXML, you expect a targeted self-transition to re-enter. re-frame2 (like XState v5+) does **not**. A self-target that you *meant* to re-fire `:entry` on needs `:reenter? true`; without it, only the transition `:action` runs. Full mechanics, including ancestor restarts and the one `:always` restriction (an eventless `:always` may never self-target), are in [Spec 005 §Self-transitions](../../../spec/005-StateMachines.md#self-transitions--internal-default-vs-external-reenter).

## Wildcard transitions: handle a whole class of events

Sometimes a state should react to *any* event in a family, not enumerate each one. `:on` resolves **three tiers**, most-specific first, and you can register a handler at any tier:

1. the **exact** event id — `:mouse/down`;
2. the **namespace wildcard** `:ns/*` — `:mouse/*` matches every event in the `mouse` namespace (`:mouse/down`, `:mouse/up`, `:mouse/move`) and *only* those (it won't catch `:keyboard/down`);
3. the **total wildcard** `:*` — matches anything.

```clojure
:tracking
{:on {:mouse/down {:action :begin-drag}    ;; exact wins for :mouse/down
      :mouse/*    {:action :note-move}      ;; any other :mouse/… event
      :*          {:action :log-unknown}}}  ;; anything else
```

The namespace tier is re-frame2's idiom for XState v5's prefix descriptor (`mouse.*`) — same behaviour (one prefix level between exact and catch-all), expressed on the keyword's `/` boundary instead of a dotted string. A bare, non-namespaced id like `:go` has no `:ns/*` tier; only its exact key or `:*` can catch it.

One subtlety worth knowing: a guard-**blocked** exact match falls through to the coarser tiers (it wasn't *handled*), but a deliberate **forbidden block** — `{:on {:E {}}}` or `{:on {:E nil}}` — is itself a handler that *consumes* the event and stops the search. That distinction is how a child state opts out of an event its parent would otherwise handle. Full precedence rules in [Spec 005 §Wildcard transitions](../../../spec/005-StateMachines.md#wildcard-transitions).

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

When `:auth-flow` enters `:done`, the runtime reads its `:token`, hands it to the parent's `:on-done` as `result`, then tears the child down — no stale id left behind. A `:final?` leaf can also be flagged `:error? true` (a designated *error* terminal); if the parent's `:spawn` declares an `:on-error` transition, a failing child routes there instead. Full grammar, the singleton-symmetry rule, and the `[:rf.machine/done …]` signal that lets a *compound* state advance on its substates finishing are in [Spec 005 §Final states](../../../spec/005-StateMachines.md#final-states-final--on-done--output-key).

## Validating a machine's `:data`

A machine's `:data` is just a map, and a typo there (`:cirles` for `:circles`) is the same silent rot any app-db shape is prone to. So a machine spec may declare a machine-level **`:schemas`** map whose **`:data`** entry — a Malli schema (the same machinery [reg-event](../../../spec/010-Schemas.md) and subscriptions use) — validates the `:data` slot. The `:schemas` map is the single home for a machine's schema declarations; `:data` is the live, wired category:

```clojure
(rf/reg-machine :drawer/editor
  {:initial :idle
   :data    {:circles [] :undo [] :redo []}
   :schemas {:data DrawerData}                      ;; validates :data at every transition
   :guards  {...}
   :actions {...}
   :states  {...}})
```

The check runs at the macrostep commit — once per transition, regardless of how many actions fired — plus at bootstrap and at spawn time. A violation emits a structured `:rf.error/schema-validation-failure` with `:where :machine-data` and **rolls the whole transition back**, so an invalid `:data` never reaches runtime-db. Like every schema in re-frame2, it's dev-only by default: the validation site is `debug-enabled?`-gated and DCEs to nothing under `:advanced` production builds. This is exactly the runtime guarantee XState's typed context *can't* give you — TypeScript's types are erased before the machine ever runs.

To *also* validate the inbound event vector, use the three-argument `reg-machine` arity, where the middle `opts` map carries an event `:schema` (the ordinary `:where :event` boundary that runs before the handler):

```clojure
(rf/reg-machine :auth.login/flow
  {:schema AuthLoginEvent}                  ;; validates the OUTER [:auth.login/flow [...]] event
  {:initial :idle
   :schemas {:data AuthLoginData}           ;; validates the machine's :data
   :data    {:attempts 0 :error nil}
   :states  {...}})
```

The `:schemas` map follows the XState v6 direction, which replaces v5's `types` with a broader `schemas` section. Its sub-keys are a closed set — `:data` is the live, wired category; `:events`, `:output`, `:tags`, and `:meta` are accepted as declaration-only surfaces for now — so a typo'd or not-yet-adopted sub-key fails loud at registration rather than silently validating nothing.

> **Fail-loud guard.** Because the schema only does its job through `reg-machine`'s registration stamp, hand-rolling `(reg-event id meta (machines/make-machine-handler spec))` around a `[:schemas :data]`-bearing spec is rejected with `:rf.error/machine-schema-requires-reg-machine` — the framework refuses to let your schema sit there validating nothing. A schema-less spec is fine through either path.

## Testing: transitions are pure function calls

`machine-transition` runs one transition with no frame, no browser, no mocks. Table in, snapshot in, event in; result out:

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

The return value is a result map. Destructure `::result/snap` and `::result/fx`, or discriminate with `result/ok?` / `result/fail?` — a throwing action surfaces as a failure value, not an exception out of your test, which means one assertion style covers both paths. These run on the JVM in microseconds, which is exactly the testing experience you want for the flows where testing usually gets hard. The complete login flow with these tests lives at [`examples/reagent/state_machine_walkthrough/`](https://github.com/day8/re-frame2/tree/main/examples/reagent/state_machine_walkthrough) and runs on every CI pass.

## When the machine grows

The flat grammar above carries most machines. When a flow gets richer, the grammar grows without changing the model — each of these is the same transition-table data, one more key. You only need to recognise them here; the contracts live in [Spec 005](../../../spec/005-StateMachines.md):

- **Hierarchical states** — a compound state contains sub-states; entering the parent cascades to its `:initial` child. (An `:authenticated` super-state over `:browsing` / `:checkout`.) [§Hierarchical compound states](../../../spec/005-StateMachines.md#hierarchical-compound-states).
- **Eventless `:always`** — fires when a guard becomes true, no event needed. [§Eventless `:always` transitions](../../../spec/005-StateMachines.md#eventless-always-transitions).
- **Parallel regions** — one machine, several orthogonal axes active at once (`:type :parallel` + `:regions`) sharing one `:data`; the snapshot's `:state` becomes a map of region → state, tags union across regions. Three axes of 3 states each is 3 regions, not 27 cross-product states. [§Parallel regions](../../../spec/005-StateMachines.md#parallel-regions); when the axes *don't* share data, prefer N separate machines — the trade-off is worked in the [CP-5 guide](../../../spec/CP-5-MachineGuide.md).
- **History states** — re-enter a compound at the substate it was in when you left (a paused player resumes mid-track), via a `:type :history` pseudo-state. The recording rides the snapshot, so it survives undo and hydration for free. [§History states](../../../spec/005-StateMachines.md#history-states-type-history--shallow--deep--default-target).
- **Spawned actors** — machines that aren't long-lived singletons: a per-request protocol machine, a wizard's per-step subprocess. The declarative `:spawn` key spawns a child on state entry and destroys it on exit (XState's `invoke`, deliberately renamed). [§Declarative `:spawn`](../../../spec/005-StateMachines.md#declarative-spawn).

## When to reach for a machine — and when not

**Reach for one when:** the flow has named, mutually-exclusive stages (handlers that `cond` on a state field are the tell); transitions are conditional (`(when (ready? db) ...)` scattered across handlers are guards in disguise); the flow is worth drawing on a whiteboard — the diagram *is* the machine.

**Don't reach for one when:** the "state" is just data (a counter, a list); there are only two stages (a `:loading?` boolean is fine); the lifecycle belongs to server data — fetching, caching, invalidation is what [resources](server-state.md) already manage, and hand-building that machine re-implements the framework; or you're enforcing a *sequence of operations* rather than a set of states — chained events handle the simple cases.

**Reach for machines when named states are the load-bearing concept — not when named operations are.** [Part 3 of the tutorial](../tutorial/03-auth-and-forms.md) puts a login machine to work in a real app, and [Server state: resources](server-state.md) covers the one lifecycle you should *not* hand-build as a machine — the framework already runs it for you.

---

By the end of this page, you can:

- spot a state machine hiding in scattered `cond` clauses, and name the three diseases the transition-table rewrite cures
- register a machine (`reg-machine` — sugar over an event handler; `reg-machine*` / `defmachine` for the non-literal cases), dispatch into it, and read it with the `[:rf/machine <id>]` and `[:rf/machine-has-tag? <id> <tag>]` subscription vectors
- write guards, actions, tags, `:after` timers, `:raise` self-events, internal vs external (`:reenter?`) self-transitions, and `:ns/*` / `:*` wildcard transitions — and validate `:data` with `[:schemas :data]`
- finish a machine with `:final?` / `:output-key` and notify its parent via `:spawn`'s `:on-done`
- map your XState vocabulary onto re-frame2's five deltas, and name the three deliberate divergences (declarative topology, runtime-db snapshots, event-shaped completion)
- test transitions as pure function calls with `machine-transition`
- recognise the framework's fail-loud errors (unresolved guard/action/target, `:db` from an action, missing artefact) versus the deliberate silent no-op for an unhandled event
- recognise when you need hierarchy, parallel regions, history, or spawned actors — and know where their contracts live
