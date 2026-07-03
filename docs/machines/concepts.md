# State machines

Some state isn't a value you read — it's a *question*: what state are we even **in**? A login is idle, then submitting, then authed, error-shown, or locked-out. A websocket is connecting, connected, dropped, reconnecting. For flows like those the interesting fact isn't what sits in [app-db](../core/glossary.md#app-db) — it's which of a fixed set of **named states** you occupy, and which [events](../core/glossary.md#event) move you between them.

A **[machine](glossary.md#machine)** makes that shape first-class, so you stop reconstructing it from code scattered across handlers. This page lays out the whole model at once. To *build* a machine step by step instead — turning it on, then adding a guard, an action, an async call, a view, and a test — start with the [tutorial](tutorial.md).

!!! note "Deciding where a value should live?"

    A machine is the right home when a value has a *lifecycle* — named states, timers, retries, cancellation — not just a value you read. [Where should this value live?](../core/where-state-lives.md) has the full decision procedure, and machine is the last of [the four homes](../core/glossary.md#the-four-homes-where-state-lives) you reach for.

## A machine at a glance

You already write state machines — you just call them other things. A `:state` keyword in app-db (`:idle`, `:submitting`, `:authed`) plus the rules in your head about which states may legally follow which: that's a machine, written informally and scattered across handlers. A machine writes that shape down as *one value*, so the rules live in one place you can read, draw, test, and hand to an AI whole.

Here's a login flow as that one value — five states in a single map. Read it top to bottom; it tells the whole story.

<a id="the-same-flow-as-a-transition-table"></a>

```clojure
;; Adapted from examples/capabilities/machines/state_machine_walkthrough/core.cljc
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
    ;; the classified failure map rides under :error on the canonical reply
    (fn [{data :data [_ {:keys [error]}] :event}]
      {:data (-> data
                 (update :attempts inc)
                 (assoc :error (or (:message error) "Login failed.")))})

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

Five states. `:idle` starts. Submit takes it to `:submitting`; from there success goes to `:authed`, and a failure goes to `:error-shown` *if* the `:under-retry-limit` guard passes, otherwise to `:locked-out`. Two words carry the behaviour, both in the [glossary](glossary.md):

- A **[guard](glossary.md#guard)** is a yes/no test that gates a [transition](glossary.md#transition) — `:under-retry-limit` answers "attempts left?".
- An **[action](glossary.md#action)** is the side work a transition performs — `:issue-request` asks for the HTTP call.

Both are referenced from the table *by id*; their implementations sit once, up top, in the `:guards` / `:actions` maps. Because the whole flow is one value, you can pretty-print it, render it as a diagram, or add a `:two-factor` state with one new node and its arrows — the existing nodes don't move.

!!! note "Build it by hand"

    The [tutorial](tutorial.md) constructs exactly this machine step by step — turn machines on, add a guard, an action, a real server call, a view per state, and a pure-function test — one idea at a time.

## Registering and running it

Registering the table is one line — and there's no new runtime concept under it. **A machine *is* an event handler.** [`reg-machine`](../core/glossary.md#registration) is sugar over a `reg-event` whose body interprets the table: look up the current **[snapshot](glossary.md#snapshot)** (its live `{:state :data}` value), compute the transition, write the new snapshot back, return the action's [effects](../core/glossary.md#effect).

```clojure
(rf/reg-machine :auth.login/flow login-flow)
```

So every event reaches the machine through the same `dispatch` and the same [event pipeline](../core/glossary.md#event-pipeline) as everything else — no actor object, no second messaging system. Dispatching routes through the machine's id, wrapping an inner event vector; you read the snapshot through a [subscription](../core/glossary.md#subscription) addressed by that id:

```clojure
(rf/dispatch [:auth.login/flow [:auth.login/submit credentials]])

@(rf/sub-machine :auth.login/flow)
;; => {:state :submitting :data {:attempts 0 :error nil}}   (nil before the first event)
```

`[:rf/machine <id>]` is an ordinary [query vector](../core/glossary.md#query-vector), so it's traceable like the rest of your [derivation graph](../core/glossary.md#the-derivation-graph), and named projections chain off it — `(rf/reg-sub :auth.login/error :<- [:rf/machine :auth.login/flow] ...)` — like any other [subscription](../core/concepts/subscriptions.md).

!!! note "One-time setup"

    Machines ship in their own artefact, `day8/re-frame2-machines`, so an app without machines builds a bundle clean of them. Add the dep and require `re-frame.machines` once at app boot; forget it and `reg-machine` throws `:rf.error/machines-artefact-missing`, naming the artefact to add. (`reg-machine` is the source-stamping macro; the plain-fn `reg-machine*` and the `def`-shaped `defmachine` are in [`re-frame.machines`](../api/re-frame.machines.md).)

!!! note "Where the snapshot lives"

    The snapshot lives in the [frame](../core/glossary.md#frame)'s **[runtime-db](../core/glossary.md#runtime-db)** — the framework's half of [the two partitions](../core/glossary.md#the-two-partitions), kept apart from the app data you own. Because it's just a value riding the frame, [undo, time-travel](../core/glossary.md#time-travel), persistence, and SSR [hydration](../ssr/glossary.md#hydration) all work on machines for free.

!!! note "Async composes for free"

    `:issue-request` names its reply events machine-wrapped — `:on-success [:auth.login/flow [:auth.login/success]]`, written one element short on purpose. When the request returns, [managed HTTP](../async/http.md) *appends* its reply to that inner event, so it lands back *inside* the machine as `[:auth.login/success {:status :ok :value v …}]` — exactly the event `:store-session` destructures. A machine and an async [effect](../core/glossary.md#effect) compose with no adapter layer.

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
  (let [{:keys [state data]} (or @(rf/sub-machine :turnstile/flow)
                                 {:state (:initial turnstile) :data (:data turnstile)})
        open? (= state :unlocked)]
    [:div {:style {:font-family "sans-serif"}}
     [:p "state: " [:strong {:style {:color (if open? "green" "crimson")}} (str state)]]
     [:p "coins: " (:coins data) " · pushes: " (:pushes data)]
     [:button {:on-click #(rf/dispatch [:turnstile/flow [:coin]])} "insert coin"]
     [:button {:on-click #(rf/dispatch [:turnstile/flow [:push]])} "push"]]))

[turnstile-view]
```

!!! tip "Try it"

    Push while `:locked` — nothing opens, but the push counter climbs (a self-transition running an action). Then add a third state: give `:unlocked` an `:on {:break {:target :broken}}`, add `:broken {:on {}}` to `:states`, add a button dispatching `[:turnstile/flow [:break]]`, re-evaluate. You added a reachable state by editing *one value* — no new handler, no `cond` surgery.

### One thing that *won't* throw: the unhandled event

If the current state has no transition for an event, it's a **silent no-op** — nothing throws, the snapshot doesn't move. Try it in the turnstile: dispatch `[:turnstile/flow [:wat]]`, an event no state handles, and the machine simply ignores it. (This is different from the `:push`-while-`:locked` case earlier: that state *does* declare a `:push` transition, so its action ran — here there's no transition at all.) The runtime still emits a benign `:rf.machine.event/unhandled-no-op` trace, so a debugger can show the event arrived and was dropped.

!!! note "Fail-loud is the rule elsewhere"

    The unhandled event is the one silent no-op. Almost everything *else* that's wrong (a guard referencing an undefined name, a target naming a missing state) [fails loud](../core/glossary.md#fail-loud-not-silent) — but at *registration* time, not on the unlucky dispatch. More on that fail-loud / silent-no-op split below.

## Guards and actions

The arrows in the table named two kinds of helper — `:under-retry-limit` (a guard) and `:issue-request` (an action) — and pointed at implementations sitting up top in the `:guards` and `:actions` maps. Those two maps *are* the machine's behaviour. Here's the contract they follow.

**Every callback receives one context map and destructures what it needs.** A guard, an action, an `:entry`, an `:exit` — all of them get the *same* single-map argument:

```clojure
{:data  {:attempts 1 :error nil}      ;; the snapshot's :data slot — a plain map
 :event [:auth.login/failure {...}]   ;; the inbound event vector
 :state :submitting                   ;; the discrete state keyword
 :meta  {...}}                        ;; any user :meta on the snapshot
```

Pull out the keys you want and ignore the rest. `data` is already the `:data` slot — you read `(:attempts data)`, never `(get-in snapshot [:data :attempts])`. Note what is *not* in that map: there is no `:db`. A machine callback cannot see [app-db](../core/glossary.md#app-db) at all — only its own data plus the event that woke it. That boundary is load-bearing; **Strict encapsulation** below is the why.

### A guard is a yes/no gate

A guard returns truthy or falsey: take this transition, or fall through to the next candidate. `:under-retry-limit` answers "attempts left?":

```clojure
:guards
{:under-retry-limit
 (fn [{data :data}] (< (:attempts data) 3))}
```

You reference it from an arrow by id — `:guard :under-retry-limit` — and the runtime resolves it against this machine's `:guards` map. There is deliberately **no combinator DSL**: no `{:and [...]}`, `{:or [...]}`, `{:not ...}`. Compound logic goes in one ordinary Clojure fn, and if it's worth a name, a named entry in `:guards`:

```clojure
:guards
{:ready-to-submit?
 (fn [{:keys [data]}]
   (and (email-valid? data) (under-quota? data)))}
```

### An action returns effects

An action does the side work a transition performs — but it doesn't *perform* it. It **returns a description of effects**, exactly the way a `reg-event` handler does, and the runtime actions them. `:issue-request` never calls the HTTP client; it returns an `:fx` asking for one. Two small actions from the login flow:

```clojure
:actions
{:clear-error  (fn [_] {:data {:error nil}})

 :record-error (fn [{data :data [_ {:keys [error]}] :event}]
                 {:data (-> data
                            (update :attempts inc)
                            (assoc  :error (or (:message error) "Login failed.")))})}
```

The return shape — that `{:data ...}` map — gets a section of its own just below. Like guards, actions are referenced by id (`:action :clear-error`) against the machine's `:actions` map, or written inline.

### Name them, or inline them

Both `:guard` and `:action` accept **either** a keyword (resolved through the `:guards` / `:actions` map) **or** a bare inline fn:

```clojure
{:on {:auth.login/submit {:guard  (fn [{:keys [data]}] (some? (:email data)))  ;; inline
                          :target :submitting}}}

{:on {:auth.login/submit {:guard  :ready-to-submit?                            ;; by id — the default
                          :target :submitting}}}
```

**Reach for the keyword by default; inline only for a one-line triviality.** The id is a *name* — a stable, reusable handle that trace rows print, that a diagram arrow is labelled with, that a test can stub, that an AI can address and jump to source for. An anonymous closure has none of that. (Inline fns aren't second-class for *tooling* — in dev the `reg-machine` macro co-locates their source text, so a visualiser still renders the body — they're just unnamed.) Compound or reused logic always earns a name.

!!! note "Fail-loud, not silent"

    Reference a `:guard` / `:action` keyword the machine's maps don't define and registration throws (`:rf.error/machine-unresolved-guard` / `…-unresolved-action`); a `:target` naming a state not in `:states` is `:rf.error/machine-unresolved-target`. These are caught at `reg-machine` time, not on the unlucky dispatch that first hits the bad arrow — a [fail-loud](../core/glossary.md#fail-loud-not-silent), not silent, posture. The one thing that is genuinely *not* an error is an event the current state has no transition for — that's the silent no-op you met just above.

## The action effect map — `{:data :fx}`

An action returns a two-key map — and **it's the same shape a `reg-event` handler returns**, just scoped to the machine instead of to app-db:

```clojure
{:data {<updates to the machine's own data>}   ;; cf. reg-event's :db
 :fx   [[<fx-id> <args>] ...]}                  ;; the standard re-frame fx vector
```

Both keys are optional; returning `nil` (or `{}`) means "no effects." That symmetry is the point — you already know this shape from [event handlers](../core/glossary.md#event-handler).

**`:data` is a merge, not a replace.** What you return is merged into the snapshot's current `:data`, key by key, last-write-wins. You mention only the keys you're changing:

```clojure
;; current :data is {:attempts 1 :error nil}
(fn [{data :data}] {:data {:error "Login failed."}})
;; → :data becomes {:attempts 1 :error "Login failed."}  — :attempts untouched
```

One sharp edge: an explicit `nil` **sets** a key to `nil` (the key stays present) — the merge never *removes* keys. `{:data {:error nil}}` leaves `:error` present-and-`nil`; it doesn't drop it. There is no key-removal idiom in a `:data` write.

**`:fx` is the ordinary effects vector** — `:dispatch`, `:rf.http/managed`, your own registered effects, all flow to the normal machinery untouched. Three fx-ids are *machine-only* and intercepted before they get there: `[:raise [...]]` loops an event back into this same machine (atomically, before the transition commits), and `[:rf.machine/spawn ...]` / `[:rf.machine/destroy ...]` are the actor-lifecycle pair. You'll meet all three under [When the machine grows](#when-the-machine-grows); the reference is [`re-frame.machines`](../api/re-frame.machines.md).

!!! warning "Gotcha — `:fx` can't read this action's own `:data` write"

    The two keys are returned *together*, so at the moment the runtime reads your `:fx` vector the `:data` merge hasn't happened. To act on a freshly-computed value, bind it in a `let` and use the local in both keys:

    ```clojure
    (fn [{data :data}]
      (let [next-id (inc (:last-id data))]
        {:data {:last-id next-id}
         :fx   [[:dispatch [:thing/created next-id]]]}))   ;; the local, not (:last-id data)
    ```

    Or split the work across two slots — write in the transition `:action`, read in the target state's `:entry`.

When several slots fire in one transition (`:exit` → the transition's `:action` → `:entry`), their `:data` updates merge in that order — a later slot sees the earlier writes — and the `:fx` vectors concatenate left to right.

## Strict encapsulation — a machine sees only its own `:data`

This is the rule hovering over that context map: a guard or action gets `{:data :event :state :meta}` and **never app-db**. It cannot read app-db, and it cannot write it.

!!! note "Why it's locked"

    A machine's whole state — its `:state` and its `:data` — lives in one place, the [runtime-db](../core/glossary.md#runtime-db), so it reverts, time-travels, persists, and SSR-hydrates as a single value along with the rest of the [frame](../core/glossary.md#frame). If an action could quietly write some *other* slice of app-db, that change wouldn't live in the snapshot and wouldn't roll back with it. Encapsulation is what keeps a machine's state *all* inside the snapshot.

So how does a machine read or write things outside itself? Two disciplined moves:

**A fact from outside arrives in the event.** Whoever dispatches has the data; they pass it in. The view that knows a circle's current radius puts it in the dispatch, and the action reads it from `:event`:

```clojure
(rf/dispatch [:drawer/editor [:right-click-circle id radius]])
```

**A write to a sibling slice goes out as a named event.** An action that needs to touch app-db emits a `:dispatch` fx — so the write becomes a real, traced, reusable event *with a name*, not a quiet reach into someone else's data:

```clojure
:action (fn [{:keys [data]}]
          {:fx   [[:dispatch [:drawer/apply-radius (:circle-id data) (:preview-radius data)]]]
           :data {:circle-id nil :preview-radius nil}})
```

Two guard-rails enforce the boundary:

- **Returning `:db` is a hard error.** A `:db` key in an action's effect map surfaces `:rf.error/machine-action-wrote-db` and the `:db` key is dropped (the rest of the effects still flow). Fail loud, don't silently let a machine scribble on app-db.
- **The next state isn't in the return shape either.** An action returns `:data` and `:fx` — never a state keyword. Only the transition's `:target` moves the machine. Callbacks update working memory; they can't nudge the machine into a state the table didn't declare.

!!! warning "Gotcha — facts from the world are *declared*, not grabbed"

    A guard or action that needs the time (or a random draw) must not call `(js/Date.now)` or `(rand)`: that buries nondeterminism where replay can't reach it, and replay is what makes time-travel and SSR hydration work. Instead, declare the fact as a [coeffect](../core/glossary.md#coeffect) on a *named* entry with `:rf.cofx/requires`, and the framework threads it into the context map:

    ```clojure
    :guards
    {:within-retry-window?
     {:rf.cofx/requires [:rf/time-ms]
      :fn (fn [{:keys [data rf/time-ms]}]
            (< (- time-ms (:first-attempt-at data)) 60000))}}
    ```

    The fact rides the event's causal token (it's a *recordable* coeffect — see [recordable vs ambient coeffects](../core/glossary.md#recordable-vs-ambient-coeffects)), so the decision — and any `:data` it folds in — replays identically. [Effects & coeffects](../core/concepts/effects-and-coeffects.md) has the general mechanism: a coeffect is a fact pulled *into* a handler, the mirror of an effect pushed out.

## The snapshot — `{:state :data :tags}`

A machine's entire live value at any instant is one small map, its [snapshot](glossary.md#snapshot):

```clojure
{:state :submitting               ;; where we are
 :data  {:attempts 1 :error nil}  ;; the machine's private memory
 :tags  #{:auth/busy}}            ;; the active state's tags, projected on (optional)
```

Three slots do the work:

- **`:state`** — the discrete state. For the flat machines on this page it's a single keyword (`:idle`, `:submitting`). It grows two more shapes as machines do: a **vector path** from root to active leaf once states nest (`[:authenticated :cart :browsing]`), and a **region→state map** for parallel machines (`{:data :loading :form :neutral}`). One slot, read three ways.
- **`:data`** — the machine's working memory: a plain map, yours to shape, private to this machine (the encapsulation rule above). Optionally schema-checked — see [Validating a machine's `:data`](#validating-a-machines-data).
- **`:tags`** — a set the runtime *projects* onto the snapshot: the union of every active state's declared `:tags`. `:submitting` declares `:tags #{:auth/busy}`, so while you're there the snapshot carries it. It's how a view asks "is it busy?" rather than "which exact state?".

(A `:meta` slot carries any user metadata; the runtime additionally stamps a closed set of framework-owned `:rf/*` bookkeeping slots inside the snapshot, which you never write to.)

You read the snapshot through the framework's `[:rf/machine <id>]` [subscription](../api/re-frame.machines.md) — it returns the `{:state :data}` value (plus `:tags`), or `nil` before the first event:

```clojure
@(rf/sub-machine :auth.login/flow)
;; => {:state :submitting :data {:attempts 1 :error nil} :tags #{:auth/busy}}
```

Because the snapshot is *just a value* — no functions, no atoms, nothing unprintable in `:data` — it `pr-str` / `read-string` round-trips; and because it lives in [runtime-db](../core/glossary.md#runtime-db) it inherits [time-travel](../core/glossary.md#time-travel), undo, persistence, and SSR hydration for free, exactly as app-db does. That is the quiet dividend of "a machine is just an event handler whose state rides the frame."

## Tags and timers

Two keys round out the everyday kit, and each has earned a page of its own rather than a paragraph here:

- **Tags** — `:tags #{:auth/busy}` on a state node, read with `@(rf/machine-has-tag? :auth.login/flow :auth/busy)`. This is the *ask-don't-tell* pattern: a view tracks "is it busy?" across any number of states without hard-coding state names, and adding a sixth busy state later is one `:tags` entry with zero view changes. (See [state tag](glossary.md#state-tag).) The deep treatment is in [Tags](tags.md).
- **Automatic transitions** — `:after` (a declarative timer: arm on entry, cancel on exit, no `setTimeout` or cancel-flag to wire) and `:always` (an eventless transition that fires the moment a guard turns true). The machine's *automatic* moves — the ones no event drives. See [Automatic transitions](automatic-transitions.md).

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

!!! note "The self-target subtlety"

    A *targeted* self-transition does **not** re-enter by default. A self-target you *meant* to re-fire `:entry` on needs `:reenter? true`; without it, only the transition `:action` runs. (One related restriction: an eventless `:always` may never self-target at all — it's rejected at registration. See [Automatic transitions](automatic-transitions.md).)

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

One subtlety worth knowing: a guard-**blocked** exact match falls through to the coarser tiers (it wasn't *handled*), but a deliberate **forbidden block** — `{:on {:E {}}}` or `{:on {:E nil}}` — is itself a handler that *consumes* the event and stops the search. That distinction is how a child state opts out of an event its parent would otherwise handle. In a hierarchical machine the same three tiers run at each level of the deepest-wins walk — see [Hierarchical states](hierarchical-states.md#transition-resolution-deepest-wins-with-parent-fallthrough).

!!! note "A non-namespaced id has no wildcard tier"

    A bare id like `:go` has no `:ns/*` tier — only its exact key or `:*` can catch it. The `:ns/*` tier lives on the keyword's `/` boundary: one prefix level between the exact id and the catch-all `:*`.

## Final states: when a machine is *done*

The login flow above parks in `:authed` and `:locked-out` forever — ordinary leaf states with no outgoing transitions, which is right when the machine *persists* (a view keeps reading the snapshot). But some machines genuinely *finish*: a spawned per-request protocol machine completes and should report back. For those, a leaf declares **`:final? true`**, and entering it **terminates the machine** — the runtime auto-destroys it.

!!! warning "Gotcha — final means final"

    A `:final? true` leaf auto-destroys *even a top-level singleton machine*. If you want a state the machine rests in indefinitely (like `:authed`), use an ordinary leaf and **omit `:final?`** — exactly what the login flow does. `:final?` is for "this run is over," not "this is the last screen."

The payoff is composition, and it comes in two scopes:

- **A spawned child reports back.** The child names a result key with `:output-key`; its parent's `:spawn` declares `:on-done`, which receives that value the moment the child finishes — then the runtime tears the child down. The full protocol, including the `:on-error` failure path, is in [Actors → When a child finishes](actors.md#when-a-child-finishes).
- **A nested sub-flow advances the outer flow.** A `:final?` leaf *inside a compound state* doesn't end the machine — it signals "this sub-flow is done" to the enclosing compound's `:on-done`, and the machine keeps running. [Hierarchical states → When a sub-flow finishes](hierarchical-states.md#when-a-sub-flow-finishes-nested-final-states) owns that pattern.

## Validating a machine's `:data`

A machine's `:data` is just a map, and a typo there (`:cirles` for `:circles`) is the same silent rot any app-db shape is prone to. So a machine spec may declare a machine-level **`:schemas`** map whose **`:data`** entry — a [Malli schema](../core/glossary.md#schema), the same machinery events and subscriptions use — validates the `:data` slot. The `:schemas` map is the single home for all of a machine's schema declarations; `:data` is the first of them that's actually wired up to run:

```clojure
(rf/reg-machine :drawer/editor
  {:initial :idle
   :data    {:circles [] :undo [] :redo []}
   :schemas {:data DrawerData}                      ;; validates :data at every transition
   :guards  {...}
   :actions {...}
   :states  {...}})
```

The check runs at the [commit](../core/glossary.md#commit) — the machine's *macrostep*, the one deferred runtime-db write a transition lands — once per transition no matter how many actions fired, plus at bootstrap and at spawn time. A violation emits a structured `:rf.error/schema-validation-failure` with `:where :machine-data` and **rolls the whole transition back**, so an invalid `:data` never reaches runtime-db. Like every schema in re-frame2, it's dev-only by default: the validation site is `debug-enabled?`-gated and is [elided](../core/glossary.md#elide) under `:advanced` production builds.

To *also* validate the inbound event vector, use the three-argument `reg-machine` arity, where the middle `opts` map carries an event `:schema` (the ordinary `:where :event` boundary that runs before the handler):

```clojure
(rf/reg-machine :auth.login/flow
  {:schema AuthLoginEvent}                  ;; validates the OUTER [:auth.login/flow [...]] event
  {:initial :idle
   :schemas {:data AuthLoginData}           ;; validates the machine's :data
   :data    {:attempts 0 :error nil}
   :states  {...}})
```

The `:schemas` map's sub-keys are a closed set. Two of them — `:data` and `:output` — are wired up to actually run a check (you've now seen both). The other three — `:events`, `:tags`, and `:meta` — are accepted so you can declare them today, but they don't validate anything yet; they're accepted declaration-only, each with a recorded post-v1 wiring trigger (see [Spec 005 §The `:schemas` map](../../spec/005-StateMachines.md#the-schemas-map)). Either way, a sub-key *outside* the set (a typo, or one you hoped was live but isn't) fails loud at registration rather than silently validating nothing.

??? info "Coming from TypeScript?"

    There's a runtime guarantee TypeScript's typed context *can't* give you: TS types are erased before the machine ever runs, whereas `[:schemas :data]` is an *actually-running* validation in dev that rolls a bad transition back. Same declaration, plus the runtime check the type-erased layer leaves out.

!!! note "Fail-loud guard"

    Because the schema only does its job through `reg-machine`'s registration stamp, hand-rolling `(reg-event id meta (machines/make-machine-handler spec))` around a `[:schemas :data]`-bearing spec is rejected with `:rf.error/machine-schema-requires-reg-machine` — the framework refuses to let your schema sit there validating nothing. A schema-less spec is fine through either path.

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

This is where machines pay you back hardest. A transition is a pure function of *(definition, snapshot, event)*, and `machine-transition` runs exactly one — no frame, no browser, no mocks. Table in, snapshot in, event in; result out:

```clojure
(machines/machine-transition login-flow
                             {:state :submitting :data {:attempts 3 :error nil}}
                             [:auth.login/failure {:failure {:message "bad creds"}}])
;; => a Result — ::result/snap holds {:state :locked-out …}, ::result/fx the emitted effects
```

Feed in a snapshot and an event; assert on the value that comes back. These tests run on the JVM in microseconds — exactly the testing experience you want for the flows where testing usually gets hard. [Inspecting and testing machines](inspecting-machines.md) has the full treatment: the Result accessors, asserting effects as data, and the three test levels.

## When the machine grows

The flat grammar above carries most machines. When a flow gets richer, the grammar grows *without changing the model* — each of these is the same transition-table data, one more key. Each has its own page in this guide:

- **[Hierarchical states](hierarchical-states.md)** — a compound state contains sub-states; entering the parent cascades to its `:initial` child (an `:authenticated` super-state over `:browsing` / `:checkout`). A parent factors out transitions every descendant inherits, and a nested `:final?` leaf advances the outer flow when a sub-flow completes.
- **[Parallel regions](parallel-states.md)** — one machine, several orthogonal axes active at once (`:type :parallel` + `:regions`) sharing one `:data`; the snapshot's `:state` becomes a map of region → state, tags union across regions. Three axes of 3 states each is 3 regions, not 27 cross-product states. When the axes *don't* share data, prefer N separate machines — that page works the trade-off.
- **[History states](history.md)** — re-enter a compound at the substate it was in when you left (a paused player resumes mid-track), via a `:type :history` pseudo-state. The recording rides the snapshot, so it survives undo and hydration for free.
- **[Automatic transitions](automatic-transitions.md)** — the moves no event drives, in depth: eventless `:always` (fires when a guard becomes true), `:type :choice` (a transient decision node that routes on entry), `:after` (the declarative timer), and `:timeout` / `:on-timeout` (a named deadline that lowers onto `:after`).
- **[Spawned actors](actors.md)** — machines that aren't long-lived singletons: a per-request protocol machine, a wizard's per-step subprocess. The declarative [`:spawn`](glossary.md#spawn) key starts a child on state entry and destroys it on exit; `:spawn-all` fans out N children and joins on their completion.
- **`:raise` loops an event back into this machine.** An action can return `:fx [[:raise [:some-event …]]]` to fire an event *at its own machine*, atomically, before the transition commits — useful when one transition's outcome should immediately drive another (a wizard step that completes and re-asks "is the whole form done?"). `:raise` is a reserved fx-id, alongside `[:rf.machine/spawn …]` and `[:rf.machine/destroy …]`, the actor-lifecycle pair behind the `:spawn` sugar. Everything else in `:fx` — `:dispatch`, `:rf.http/managed`, your own effects — flows to the ordinary effects machinery untouched.
- **`:internal-events` makes an event private.** Some events are machine *plumbing* — a `:check-complete` the machine raises at itself, not for a view or test to dispatch. Declare those in a top-level `:internal-events` **set** (`#{:check-complete}`); an internal `:raise` is handled normally, but an *external* dispatch is **refused** at the machine's boundary (no transition; a `:rf.error/machine-internal-event-external-dispatch` trace fires). It's a set because membership is what you're declaring, not order.

!!! warning "Gotcha"

    Eventless transitions and `:raise` both re-enter the transition machinery within the same macrostep, so a non-converging loop (`:a` `:always`-targets `:b`, `:b` `:always`-targets `:a`, both guards true) could spin forever. It can't: each is bounded by a depth limit (default **16**, set per-frame via `:always-depth-limit` / `:raise-depth-limit`). Trip it and the macrostep **aborts atomically** — the snapshot stays at its pre-event value, no observer sees the partial path — and a single `:rf.error/machine-always-depth-exceeded` (or `:rf.error/machine-raise-depth-exceeded`) [error record](../core/glossary.md#error-record) fires. It is **distinct** from the silent no-op of an unhandled event: a runaway loop is a bug you want surfaced, not swallowed. There's no automatic recovery.

## When to reach for a machine — and when not

**Reach for one when:** the flow has named, mutually-exclusive stages (handlers that `cond` on a state field are the tell); transitions are conditional (`(when (ready? db) ...)` scattered across handlers are guards in disguise); the flow is worth drawing on a whiteboard — the diagram *is* the machine.

**Don't reach for one when:** the "state" is just data (a counter, a list); there are only two stages (a `:loading?` boolean is fine); the lifecycle belongs to server data — fetching, caching, invalidation is what [resources](../resources/concepts.md) already manage, and hand-building that machine re-implements the framework; or you're enforcing a *sequence of operations* rather than a set of states — chained events handle the simple cases.

**Reach for machines when named states are the load-bearing concept — not when named operations are.** [Part 3 of the tutorial](../resources/tutorial/03-auth-and-forms.md) puts a login machine to work in a real app, and [Server state: resources](../resources/concepts.md) covers the one lifecycle you should *not* hand-build as a machine — the framework already runs it for you.
