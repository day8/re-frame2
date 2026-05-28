# 12 — State machines

Some flows in your app aren't "set a flag." They're "what state are we even *in*?" A login that can be idle, submitting, authed, error-shown, or locked-out. A wizard with five steps and rules about which one can follow which. A websocket that's connecting, connected, dropped, reconnecting. For those, the load-bearing question isn't what's in app-db — it's which of a fixed set of named states you're sitting in, and which events move you to which other state. That shape has a name, and the moment you notice you're writing one, your scattered `cond` clauses stop being the natural way to express the flow and start being a way of *hiding* it.

## You've been writing these the whole time

Here's the uncomfortable bit: you already write state machines. You just call them other things. The `cond` at the top of your login handler that branches on `(:auth/state db)`. The `case` in your video player deciding what `:pause` means depending on whether you're `:loading` or `:playing` or `:buffering`. The `if-let` that checks "are we already submitting? then swallow this click." The keyword you stuffed into app-db two years ago — `:idle`, `:submitting`, `:authed`, `:error-shown`, `:locked-out` — and have been quietly growing ever since, along with a set of *unwritten rules in your head* about which of those states can legally follow which.

Every one of those is the same animal: a flow whose central question is *what state are we in, and what events take us where*. Computer science has a name for it — **finite state machine** — and re-frame2 makes it a first-class pattern, not because every handler should be a machine (most shouldn't) but because when "what's the next state?" is the whole question, *naming the answer* beats smearing it across five handlers. A login walks `:idle → :submitting → :authed / :error-shown / :locked-out`. A video player cycles `:loading / :playing / :paused / :buffering / :ended`. A checkout wizard goes `:cart → :shipping → :payment → :confirmation → :processed`. These aren't exotic. They're the skeleton of most non-trivial features.

## If you've used xstate, you already know 80% of this

The dominant state-machine tool in the JavaScript world is [**xstate**](https://xstate.js.org/), and re-frame2's machine grammar borrows its vocabulary on purpose, because (a) it's genuinely well-designed and (b) every AI worth its salt already knows it — ask one for "a state machine for a login flow" and it'll hand you something nearly-correct in re-frame2's grammar. So if xstate is your mental model, here's the cheat sheet, with the deliberate divergences flagged up front so you don't trip over them:

| xstate | re-frame2 | The difference, and why |
|---|---|---|
| `context` (extended state) | `:data` | Same idea. We rename it because "context" is *already* overloaded three ways in re-frame2 (interceptor context, React context). `:data` is unambiguous. |
| actor / `interpret(machine)` / `service.send(...)` | **an event handler**; `(dispatch [machine-id [event]])` | **The big one.** A machine isn't a separate actor object with its own `send`. It's *an event handler that interprets a transition table*, and every event reaches it through the same `dispatch` as everything else. No `ActorRef`, no separate sending mechanism. |
| actions that imperatively `assign(...)` / fire effects | actions that **return effect maps** | An action returns `{:data ... :fx ...}` — the same data-shaped return as any `reg-event-fx` handler. Effects are described as data, actioned by the runtime at domino three. |
| the machine's state lives in the interpreter/service | the snapshot lives in **`app-db`** at `[:rf/runtime :machines :snapshots <id>]` | It's just a value in app-db like any other. So undo, time-travel, persistence, and SSR hydration extend to machines *for free* — no parallel registry to special-case. |
| `guards` / `actions` resolved globally or via options | machine-**local** `:guards` / `:actions` | Each spec carries its own. Cross-machine reuse is via ordinary Clojure vars, not a global string registry. |

Internalise those five rows and the rest is xstate's transition-table grammar applied to re-frame2's six-domino loop. Machine transitions dispatch through the exact same cascade as every other event ([chapter 04](04-events-and-the-cascade.md)) — they're not a side channel.

## See one run before you build one

Here's a small machine, live in your browser — a turnstile with two named states (`:locked` and `:unlocked`), a counter riding in `:data`, and per-transition `:actions`. Click into the cell and hit **`Ctrl-Enter`** (or **`Cmd-Enter`** on a Mac) to re-evaluate it after edits, then click the buttons and watch the state name change. The first run wakes the engine; after that it's instant.

This is the **real** `rf/reg-machine` from `day8/re-frame2-machines`, baked into the playground's eval bundle. Same registration call you'd write in your own app; same `rf/sub-machine` for reading the snapshot. (Live cells are functions-only, so the view is a plain `defn` with explicit `rf/dispatch` / `rf/sub-machine` — see [chapter 06](06-views.md#defn-views-and-the-reg-view-equivalence).)

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])

;; ---- The machine as pure data: the transition table ----
;; Two states. Each state's :on maps an event to {:target ... :action ...}.
;; This is exactly the shape rf/reg-machine consumes.
(def turnstile
  {:initial :locked
   :data    {:coins 0 :pushes 0}
   :actions {:take-coin  (fn [{data :data}] {:data (update data :coins  inc)})
             :count-push (fn [{data :data}] {:data (update data :pushes inc)})}
   :states
   {:locked   {:on {:coin {:target :unlocked :action :take-coin}
                    :push {:target :locked   :action :count-push}}}   ;; blocked: stays locked, counts push
    :unlocked {:on {:push {:target :locked}
                    :coin {:target :unlocked :action :take-coin}}}}}) ;; extra coin, no transition

;; ---- Register the machine as an event handler ----
;; reg-machine is sugar over (reg-event-fx :turnstile/flow (make-machine-handler turnstile)).
;; The snapshot lives at [:rf/runtime :machines :snapshots :turnstile/flow] in app-db,
;; reachable via the framework sub :rf/machine (sugar: rf/sub-machine).
(rf/reg-machine :turnstile/flow turnstile)

;; ---- The view: dispatch at the machine, sub-machine for its snapshot ----
;; sub-machine returns nil until the first event arrives; we render the :initial
;; state's data slot until then so the UI is meaningful at first paint.
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

> **Try it.** Push the turnstile while it's `:locked` — nothing opens, but the push *counter* climbs (a self-transition that runs an action without changing state). Insert a coin to go `:unlocked`, then push to go back to `:locked`. Now the experiment that proves the point: add a third state. Give `:unlocked` an `:on {:break {:target :broken}}` and add `:broken {:on {}}` to `:states`, then add a button that dispatches `[:turnstile/flow [:break]]`. Re-evaluate. You added a whole new reachable state by editing *one piece of data* — no new handler, no `cond` surgery in three places. That's the property the rest of the chapter is about.

## The flow that's hiding in your `cond`s

Let me show you the thing the live cell is the clean version of. Here's a real login flow written the way everybody writes it first — scattered across handlers, each one branching on a state keyword:

```clojure
(rf/reg-event-fx :auth/submit
  (fn [{:keys [db]} [_ creds]]
    (cond
      (= :submitting (:auth/state db))
      {}                                          ;; ignore — already submitting

      (>= (:auth/attempts db) 3)
      {:db (assoc db :auth/state :locked-out)
       :fx [[:rf.http/managed {:request {:method :post :url "/api/lock-account"}}]]}

      :else
      {:db (-> db (assoc :auth/state :submitting) (update :auth/attempts inc))
       :fx [[:rf.http/managed
             {:request    {:method :post :url "/api/login" :body creds
                           :request-content-type :json}
              :on-success [:auth/login-success]
              :on-failure [:auth/login-error]}]]})))

(rf/reg-event-db :auth/login-success
  (fn [db [_ resp]]
    (-> db (assoc :auth/state :authed) (assoc :auth/user (:user resp)))))

(rf/reg-event-db :auth/login-error
  (fn [db [_ err]]
    (cond
      (>= (:auth/attempts db) 3) (assoc db :auth/state :locked-out)
      :else (-> db (assoc :auth/state :error-shown) (assoc :auth/error err)))))

;; ... plus :auth/dismiss, plus :auth/reset, ...
```

This *works*. It's correct. And it has three diseases that all metastasize:

1. **The transition rules are scattered.** That `:submitting` is reachable from `:idle` but not from `:locked-out` is *implicit in the cond clauses*. To know the full state graph you have to read every handler that touches `:auth/state` and reconstruct the rules in your head.
2. **The retry-limit logic is duplicated.** "3 attempts" appears in `:auth/submit` *and* in `:auth/login-error`. Change it to 5 and you'd better remember both spots.
3. **Adding a state is a chore.** Want a `:two-factor` state between `:submitting` and `:authed`? That's a new keyword, a new handler — *and* edits to every existing handler, because each one carries unwritten assumptions about which states are valid where.

The fix isn't better `cond` clauses. The fix is to step back and notice: *this is a state machine*, and write it as one.

## The same flow as a transition table

```clojure
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
              :on-success [:auth.login/flow [:auth.login/success]]
              :on-failure [:auth.login/flow [:auth.login/failure]]}]]})

    :record-error
    (fn [{data :data [_ {:keys [failure]}] :event}]
      {:data (-> data
                 (update :attempts inc)
                 (assoc :error (or (:message failure) "Login failed.")))})

    :lock-account
    (fn [_] {:fx [[:rf.http/managed {:request {:method :post :url "/api/auth/lock"}}]]})

    :store-session
    (fn [{[_ {:keys [token]}] :event}]
      {:fx [[:auth.session/store {:token token}]]})}

   :states
   {:idle
    {:on {:auth.login/submit {:target :submitting :action :clear-error}}}

    :submitting
    {:entry :issue-request
     :on    {:auth.login/success {:target :authed :action :store-session}
             :auth.login/failure [{:target :error-shown
                                   :guard  :under-retry-limit
                                   :action :record-error}
                                  {:target :locked-out
                                   :action :lock-account}]}}

    :error-shown
    {:on {:auth.login/dismiss {:target :idle}
          :auth.login/submit  {:target :submitting}}}

    :authed     {:meta {:terminal? true}}
    :locked-out {:meta {:terminal? true}}}})
```

Read it top to bottom: five states; `:idle` starts; `:auth.login/submit` takes `:idle` to `:submitting`; from `:submitting`, success goes to `:authed`, failure goes to `:error-shown` *if* the `:under-retry-limit` guard passes, otherwise `:locked-out`. Each `:action` and `:entry` is referenced *by id*; the implementations live once, up top, in the `:guards` / `:actions` maps. Watch the three diseases vanish: the transition rules are all in one place (the table), the retry limit lives in exactly one guard, and adding `:two-factor` is a new state node plus the two arrows in and out — the existing nodes don't move.

The whole flow is *one piece of data*. You can pretty-print it, render it to a graph, validate it against a schema, or paste it to an AI with "add a two-factor state between submitting and authed" — and the AI has the entire context in one form. The runtime applies the table against the current snapshot; you describe transitions, not branching.

## The grammar, slot by slot

- **`:initial`** — the starting state.
- **`:data`** — extended state riding alongside the discrete state: counters, error messages, transient values. (xstate's "context.")
- **`:states`** — the state nodes by name. Each carries `:on` (the events this state responds to), `:entry` / `:exit` (actions that fire on enter / leave regardless of *which* event triggered it), and `:meta` (your annotations, like `:terminal?`).
- **`:target`** — the next state on a transition.
- **`:guard`** — a predicate that must return truthy for a transition to fire. A keyword references the spec's `:guards` map; an inline `(fn [data event] ...)` works for one-liners. When a guarded transition's guard fails, the runtime walks to the *next* candidate in the vector (that's how `:auth.login/failure` falls through from `:error-shown` to `:locked-out`).
- **`:action`** — fires on the transition. Same reference shape as `:guard`.
- **`:guards` / `:actions`** (top-level) — the machine's own named implementations. Resolution is machine-local; there's no global registry to collide in.

## Wiring it in — and what `reg-machine` actually is

Registering the table as an event handler is one line:

```clojure
(rf/reg-machine :auth.login/flow login-flow)
```

And here's the demystification, the thing the live cell built by hand: that's *exactly* `(reg-event-fx :auth.login/flow (make-machine-handler login-flow))`. `make-machine-handler` returns a regular `reg-event-fx`-shaped handler whose body does "look up the snapshot, call `machine-transition`, write it back, return the action effects" — the same four moves your `transition` function made in the cell. The machine's id is `:auth.login/flow`; its snapshot lives at `[:rf/runtime :machines :snapshots :auth.login/flow]` in app-db. **You don't pick the path; there's one canonical path.** And because `machine-transition` is pure, every testing and tooling guarantee that holds for ordinary events holds for machines too.

If you need `:doc` or `:interceptors`, use the longer form:

```clojure
(rf/reg-event-fx :auth.login/flow
  {:doc "Login flow: idle → submitting → authed / error-shown / locked-out."}
  (rf/make-machine-handler login-flow))
```

`reg-machine` is a *macro*. At expansion it walks the literal spec and stamps a dev-only coordinate index under `:rf.machine/source-coords` — that's what lets a pair tool jump from a clicked transition arrow in a state-diagram visualisation straight to the source line ([chapter 17](17-tooling.md)). Production builds elide it entirely.

One behaviour worth knowing: **the initial state's `:entry` fires on machine birth.** The first time a machine receives an event (or is spawned), the runtime cascades into the `:initial` state and runs its `:entry`. So one-shot setup — seed `:data`, kick off a fetch, register a sub — goes in the initial state's `:entry`, with no ceremonial self-targeting `:on :spawned` event to wire up.

## Dispatching to a machine, and the async fold

Events route through the machine's id wrapping an inner event vector:

```clojure
(rf/dispatch [:auth.login/flow [:auth.login/submit credentials]])
(rf/dispatch [:auth.login/flow [:auth.login/dismiss]])
```

Outer keyword is the machine; inner vector is the event it sees. The runtime resolves the inner event against the current state's `:on`, runs the guard, fires the action, writes the new snapshot back. (This is the `(dispatch [machine-id [event]])` shape from the xstate cheat-sheet — the divergence from `service.send(event)` made concrete.)

For HTTP and other async callbacks, you build a *2-element template* and let the runtime conj the reply onto it:

```clojure
[:rf.http/managed
 {:request    {:method :post :url "/api/login" :request-content-type :json}
  :on-success [:auth.login/flow [:auth.login/success]]   ;; 2-element template
  :on-failure [:auth.login/flow [:auth.login/failure]]}]

;; :rf.http/managed appends the reply, producing:
;;   [:auth.login/flow [:auth.login/success] {:kind :success :value v}]
;; The runtime folds the trailing reply onto the inner event, so the action sees:
;;   [:auth.login/success {:kind :success :value v}]
```

This "extras-fold" makes *every* async callback ship a value into the machine the same way. The reply-payload envelope (`{:kind :success :value v}` / `{:kind :failure :failure m}`) is the canonical `:rf.http/managed` shape from [chapter 10](10-http.md) — machines and managed-HTTP compose without an adapter layer between them.

## Guards and actions, precisely

Guards are predicates living in `:guards`, referenced by keyword:

```clojure
:guards
{:under-retry-limit
 (fn [{data :data}] (< (:attempts data) 3))}
```

A guard sees `(fn [data event] boolean)` — `data` is the snapshot's `:data` slot directly. Truthy lets the transition fire; falsy makes the runtime try the next candidate (or fall through to the parent state, if there is one).

Actions produce data updates and/or effects, living in `:actions`:

```clojure
:actions
{:record-error
 (fn [{data :data [_ {:keys [failure]}] :event}]
   {:data (-> data
              (update :attempts inc)
              (assoc :error (or (:message failure) "Login failed.")))})}
```

An action sees `(fn [data event] effects)` and returns one of: `:data` (a map merged into the existing data slot, last-write-wins, explicit `nil` clears a key), `:fx` (effects — HTTP, navigation, follow-up dispatches), both, or `nil`. The exact same contract as an ordinary `reg-event-fx` return. (If a guard or action genuinely needs the current state *name* — rare — there's an opt-in 3-arity form, `^:rf.machine/wants-ctx (fn [data event {:keys [state meta]}] ...)`. Default to 2-arity.)

## Reading a machine: `sub-machine`

Views read a machine through a sub:

```clojure
@(rf/sub-machine :auth.login/flow)
;; → {:state :submitting :data {:attempts 1 :error nil}}
```

`sub-machine` is sugar over the framework-shipped `:rf/machine` sub; it returns the snapshot, or `nil` if the machine hasn't been born yet. Views typically destructure `:state` and `case` on it. This is the *single* supported read path — no actor reference to thread around (the xstate divergence again). For projections, build named reg-subs over `[:rf/machine machine-id]`:

```clojure
(rf/reg-sub :auth.login/submitting?
  :<- [:rf/machine :auth.login/flow]
  (fn [{:keys [state]} _] (= :submitting state)))
```

## Tags: when "is it loading?" is the real question

Once a machine grows past a handful of states, views start asking the same *predicate* question over and over: "is the machine in any of the loading-ish states right now?" A 1-of-N read (`(= state :submitting)`) is fine; the *any-of-many* read is where apps go wrong, hand-rolling one boolean sub per shape (`(or (= state :submitting) (= state :validating) ...)`). That holds up until you add a fourth loading-ish state and every spinner view needs updating.

`:tags` flip it around. A state declares a set of intent keywords:

```clojure
{:initial :neutral
 :states
 {:neutral     {:on {:submit :submitting}}
  :submitting  {:tags #{:loading :transient}  :on {:ok :ready :err :error-shown}}
  :validating  {:tags #{:loading}             :on {:done :neutral}}
  :ready       {:tags #{:happy-path}}
  :error-shown {:tags #{:recoverable}}}}
```

At every transition the runtime stamps the **union** of every active state's tags onto the snapshot's `:tags`. The view asks the predicate question directly:

```clojure
(when @(rf/machine-has-tag? :auth.login/flow :loading)
  [view-spinner])
```

`machine-has-tag?` is sugar over the framework `:rf/machine-has-tag?` sub; the signal flips only when the containment bit flips. Add a fifth loading-ish state later and it's *one* `:tags #{:loading}` on the new node — zero view changes, because the view never knew which states carried the tag. (Rules: tags are runtime-owned — actions can't write them; empty tag-sets vanish from the snapshot; the `:rf/*` and `:rf.*/*` namespaces are reserved for the framework, so use your own.) Reach for tags when you catch yourself writing the second or third "is the state one of these?" boolean; *don't* when the question is genuinely "which exact state?" — that's a plain `case`.

## Testing a machine without a browser

Because `machine-transition` is pure, you test a machine without dispatching anything — no runtime, no network, no mocks:

```clojure
(deftest login-flow-test
  (let [s0 {:state :idle :data {:attempts 0 :error nil}}
        {s1 ::result/snap} (rf/machine-transition login-flow s0
                                                  [:auth.login/submit {:email "a@b.com"
                                                                       :password "..."}])]
    (is (= :submitting (:state s1)))

    ;; attempts already at the limit: the :under-retry-limit guard rejects the
    ;; first clause, so the second clause's :locked-out wins.
    (let [{s2 ::result/snap} (rf/machine-transition login-flow
                                                    {:state :submitting :data {:attempts 3}}
                                                    [:auth.login/failure {:message "wrong creds"}])]
      (is (= :locked-out (:state s2))))))
```

`machine-transition` returns a `re-frame.machines.result/Result` — destructure `::result/snap` and `::result/fx`, or use `result/ok?` / `result/fail?`. Action or `:data`-fn throws surface as `result/fail` rather than blowing out of the transition, so tests inspect the failure shape instead of wrapping calls in `try/catch`. JVM-side, microseconds per test, hundreds of them if you like — which is exactly the testing experience you *want* for non-trivial flows and exactly the case where unit testing usually gets hard. (The full testing story is [chapter 13](13-testing.md).)

## When the machine gets bigger

The flat shape above — plain `:on` transitions, `:entry` / `:exit`, machine-local guards and actions — carries most machines. When a flow gets richer, the substrate has more *without changing the model*:

- **Hierarchical states** — a compound state contains sub-states; entering the parent cascades to its `:initial` child; a transition from a deep state can target a sibling or ancestor and the runtime computes the least-common-ancestor. (Auth flow with an `:authenticated` super-state over `:cart` / `:browsing`.)
- **Eventless `:always` transitions** — fire on entry (or after any event) when a guard becomes true, no event needed. (Drain-a-queue-then-advance; classifying a derived state.) Bounded-depth microstep loop.
- **Delayed `:after` transitions** — "if no event arrives within N ms, transition." (Retry-after-backoff, idle timeouts, debounce.) Carries an epoch so cancelled timers don't fire late.
- **Declarative `:spawn`** — a state spawns a child machine on entry, destroys it on exit, declared as data; the child's lifetime is bound to the parent state.

Each is opt-in per the capability matrix. Port authors: each feature has a capability-flag (`:fsm/hierarchy`, `:fsm/always`, `:fsm/after`, `:fsm/invoke`, `:fsm/parallel-regions`, `:fsm/tags`, `:fsm/final-states`, `:actor/spawn-and-join`); a port declares its claimed set, and using a key the port doesn't claim raises `:rf.error/machine-grammar-not-in-v1` at registration rather than being silently ignored. The v1 CLJS reference claims all of them.

### Parallel regions: when one screen has several axes of state at once

Some pages don't have one axis of state — they have several, running simultaneously. A todos page has a *data lifecycle* (nothing / loading / empty / some / error), a *form state* (neutral / invalid / valid), and a *mode* (active / archived). The axes are orthogonal; any cross-product is legal. Both naive modellings fail: one flat machine explodes to `3 × 3 × 3 = 27` states, and three separate app-db slices push cross-axis truths into derived subs. Parallel regions are the answer — a machine declares `:type :parallel` and a `:regions` map, where every region is a full state-tree with its own `:initial` and `:states`, and **all regions are active at once**:

```clojure
(rf/reg-machine :todos/page
  {:type :parallel
   :data {:items [] :error nil}            ;; one :data, shared across all regions
   :regions
   {:data {:initial :nothing
           :states  {:nothing {:tags #{:data/idle}    :on {:fetch :loading}}
                     :loading {:tags #{:data/loading} :on {:loaded {:target :resolving :action :set-items}}}
                     :resolving {:always [{:guard :empty? :target :empty} {:target :some}]}
                     :empty   {:tags #{:data/empty}}
                     :some    {:tags #{:data/some}}}}
    :form {:initial :neutral
           :states  {:neutral   {:tags #{:form/neutral} :on {:submit-invalid :incorrect :submit-valid :correct}}
                     :incorrect {:tags #{:form/invalid} :on {:edit :neutral}}
                     :correct   {:tags #{:form/success} :on {:edit :neutral}}}}
    :mode {:initial :active
           :states  {:active {:tags #{:mode/active} :on {:archive :done}}
                     :done   {:tags #{:mode/done :mode/read-only}}}}}})
```

The snapshot's `:state` becomes a *map* of region → current state, the `:tags` is the union across every active region, and a dispatch *broadcasts* to all regions (each region's active state checks its own `:on`; matching states transition, the rest stay put). Three regions, three active states, one tag union — not twenty-seven cross-product states. The pattern that makes this sing is a single render-priority table consulted by one `case` in the root view; if no region handles an event a `:rf.warning/machine-unhandled-event` fires, and if any region does, it's suppressed. Reach for parallel regions when the axes are orthogonal, share one `:data` domain, and the flat cross-product would top ~6-8 states. *Don't* when one axis depends on another (that's a hierarchical machine) or when regions don't share data (that's N separate machines). The fully-worked depth example — all nine canonical UI states modelled as one three-region machine — lives at `examples/reagent/nine_states/`.

## Composing machines, and dynamic actors

Real apps run several machines at once — auth, checkout-wizard, websocket — each at its own `[:rf/runtime :machines :snapshots <id>]`. They talk through *dispatch*: the auth machine's `:store-session` action dispatches `[:user/load-profile]`, an ordinary `reg-event-fx` picks it up, no special inter-machine messaging. And the drain semantics matter — if an action dispatches a child event, that child runs *before* subscriptions update. So a machine that walks `:idle → :submitting → :authed → :loading-profile → :ready` in a single click shows the view only `:idle` (before) and `:ready` (after) — no in-between flicker. Atomic state changes earn their keep most in exactly these chained flows.

Some machines aren't long-lived singletons — a protocol machine that owns one HTTP request, a websocket-pump that lives only while connected, a wizard's per-step subprocess. Those are **dynamic actors** with gensym'd ids and lifetimes scoped to a parent. The canonical lifecycle fxs are `[:rf.machine/spawn {:machine-id ... :data ...}]` and `[:rf.machine/destroy actor-id]`, but you rarely call them directly: the declarative `:spawn` slot on a state node spawns a child on entry and destroys it on exit, and `make-machine-handler` desugars it into ordinary entry/exit actions emitting those fxs. The runtime tracks the spawned id for you at `[:rf/runtime :machines :spawned <parent-id> <invoke-id>]` and reads it back on exit. When a spawned actor needs a stable name other parts of the frame can reach by — a sibling machine, a REPL session — the opt-in `:system-id` key binds it into a frame-level reverse index (`[:rf/runtime :machines :system-ids <name>]`), and `(rf/machine-by-system-id :primary-request)` resolves it. Both are opt-in and orthogonal to gensym'd ids; reach for `:system-id` only when you have a stable role other code talks to by name.

## Three patterns that bottom out in machines

Three recurring shapes from [chapter 04](04-events-and-the-cascade.md) are state machines underneath. Each has a dedicated convention doc that walks the worked example. The point of naming them here is recognition: the moment you reach for `setTimeout`-driven reconnect logic, an `:app/init` event that does six things in sequence, or a `for` loop that locks the UI for two seconds, you'll know the shape and where to look.

- **Pattern-WebSocket** — a connection as a machine. Retry, backoff, heartbeat, server-pushed events: a `:disconnected` initial state, a compound `:active` parent owning the spawned socket actor (its lifetime spans the connect cascade), a `:reconnecting` state with fn-form `:after` exponential backoff, and a terminal `:failed` on max retries. Messages over the open socket are ordinary async-effect interactions correlated through `:data :in-flight`.
- **Pattern-Boot** — initialisation as a machine. A one-or-two-step boot is fine as chained dispatches; once it grows config load, auth restoration, profile fetch, localStorage hydration, and route resolution, chained events scatter the logic. The canonical answer is a boot machine with named phases (`:configuring`, `:authenticating`, `:loading-profile`, `:hydrating`, `:routing`, `:ready`, plus per-phase error and retry states) that updates a visible-progress slice in `:data` so the boot UI renders "Loading config…" / "Almost ready…" straight from the snapshot.
- **Pattern-LongRunningWork** — CPU-bound work as a chunked machine. Significant main-thread work freezes the UI. Two real answers: offload to a Web Worker (preferred when the work serialises across the boundary), or chunk-and-yield. The chunked one is a tiny machine — `:idle / :processing / :checking-done / :yielding / :complete / :cancelled` — cycling `:processing → :checking-done → :yielding → :processing` until done; the load-bearing line is `:yielding`'s `:after 0`, which hands the thread back so progress bars repaint and cancel stays clickable. Cancellation is a *transition*, not a flag, and the partial result is preserved in `:data`. See [chapter 15 on performance](15-performance.md) for where this sits among the other shapes of performance work.

## When to reach for a machine, and when not to

Machines aren't a hammer. Most events in most apps are simple `(state, event) → state` updates with no flow structure — don't dress them up.

**Reach for one when:** the flow has named, mutually-exclusive stages (your handlers `cond` on a state field — that's the tell); transitions are conditional on guards (repeated `(when (some-condition? db) ...)` across handlers are guards in disguise); the flow is non-trivial enough to draw on a whiteboard (the diagram *is* the machine — encode it directly).

**Don't reach for one when:** the "state" is just data (a counter, a list — no machine); there are only two stages (a `:loading?` boolean is fine); you're enforcing a *sequence of operations* rather than a set of states (that's a saga / workflow — re-frame2's drain semantics handle the simple cases). Reach for machines when *named states* are the load-bearing concept — not when *named operations* are.

The complete login flow from this chapter, with its runnable smoke tests, lives at [`examples/reagent/state_machine_walkthrough/`](https://github.com/day8/re-frame2/tree/main/examples/reagent/state_machine_walkthrough) and runs headless on every JVM test pass. Drop it in front of you, change the transition table, watch the smoke tests adapt.

## The deeper claim

State machines are a small instance of the broader thesis [chapter 21 on the dynamic model](21-dynamic-model.md) makes in full: **constrained execution models are easier to reason about than free-form ones.** A finite state machine has, by construction, a small enumerable set of reachable states — you can list them, prove things about transitions, render the whole flow as a diagram. A pile of `if` / `cond` spread across handlers has none of those properties: reachable states are implicit, transitions are scattered, and "from here, what can happen?" requires reading every handler that touches the state field. The choice isn't a matter of style. It's a matter of which dynamic model you can hold in your head. When the flow has the shape of a machine, write a machine.
