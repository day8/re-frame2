# The model

<a id="state-machines"></a>

This page is the **flat machine model** — a single-level transition table, how
you register and drive it, and the contracts guards and actions live under.
Nested states, parallel regions, history, and actors grow the same grammar;
each has its own page.

To *build* a login machine step by step (guard → action → HTTP → view → test),
use the [tutorial](tutorial.md).

!!! note "Where should this value live?"

    A machine fits when a value has a *lifecycle* of named states — not when it
    is only data to store. See [Where should this value live?](../core/where-state-lives.md).

## The idea

<a id="a-machine-at-a-glance"></a>
<a id="the-same-flow-as-a-transition-table"></a>

You already write state machines: a `:status` keyword in app-db plus informal
rules in handlers about what may follow what. A machine writes those rules down
as **one value** — a transition table — so you can read, test, and change the
flow in one place.

A table has five everyday parts:

```clojure
;; cf. examples/capabilities/machines/state_machine_walkthrough/
(rf/defmachine login-flow
  {:initial :idle
   :data    {:attempts 0 :error nil}

   :guards
   {:form-valid?
    (fn [{[_ creds] :event}]
      (and (seq (:email creds)) (seq (:password creds))))
    :under-retry-limit
    (fn [{data :data}]
      (< (:attempts data) 2))}          ;; three attempts; see Guards

   :actions
   {:clear-error
    (fn [_] {:data {:error nil}})
    :record-error
    (fn [{data :data [_ {:keys [error]}] :event}]
      {:data (-> data
                 (update :attempts inc)
                 (assoc  :error (or (:message error) "Login failed.")))})
    :store-session
    (fn [{[_ {:keys [value]}] :event}]
      {:fx [[:dispatch [:auth.session/store {:token (:token value)}]]]})}

   :states
   {:idle
    {:on {:auth.login/submit {:target :submitting
                              :guard  :form-valid?
                              :action :clear-error}}}

    :submitting
    {:tags #{:auth/busy}
     :on   {:auth.login/success {:target :authed :action :store-session}
            :auth.login/failure [{:target :error-shown
                                  :guard  :under-retry-limit
                                  :action :record-error}
                                 {:target :locked-out
                                  :action :record-error}]}}

    :error-shown
    {:on {:auth.login/dismiss {:target :idle}
          :auth.login/submit  {:target :submitting
                               :guard  :form-valid?
                               :action :clear-error}}}

    :authed     {:meta {:terminal? true}}
    :locked-out {:meta {:terminal? true}}}})
```

- `:initial` — where the machine starts.
- `:data` — private working memory.
- `:guards` — yes/no predicates.
- `:actions` — return `{:data … :fx …}`; they never perform side effects.
- `:states` — the nodes and their outgoing transitions.

`:authed` and `:locked-out` are resting leaves. They keep the snapshot around
so a view can still render them. They are **not** `:final?` — that flag
destroys the machine ([Final states](#final-states)).

## Register and drive

<a id="register-and-drive"></a>
<a id="registering-and-running-it"></a>

A machine **is** an event handler. `reg-machine` compiles the table into a
`reg-event` whose body reads the live [snapshot](glossary.md#snapshot), takes a
transition, writes the new snapshot, and returns the action's effects.

```clojure
(:require [re-frame.core :as rf]
          [re-frame.machines])   ;; forget this → :rf.error/machines-artefact-missing

(rf/reg-machine :auth.login/flow login-flow)
```

Two registration shapes:

| Shape | Use when |
| --- | --- |
| `defmachine` + `reg-machine` | Named, reusable specs (Xray click-to-source on guards and actions) |
| Inline `reg-machine` with a **literal** map | Small local machines |

Avoid `(def m {…})` then `(reg-machine :id m)`. The macro never sees the
literal, so source stamps are empty and dev warns
`:rf.warning/machine-source-unstamped`.

**Dispatch** addresses the machine id; the *inner* vector is the machine event:

```clojure
(rf/dispatch [:auth.login/flow [:auth.login/submit credentials]])
```

**Subscribe** with the framework sub — there is no function sugar:

```clojure
@(rf/subscribe [:rf/machine :auth.login/flow])
;; => {:state :submitting :data {:attempts 0 :error nil} :tags #{:auth/busy}}
;;    nil before the first event
```

The snapshot lives in [runtime-db](../core/glossary.md#runtime-db), so undo,
time-travel, and SSR hydration work without extra wiring.

!!! note "Async composes"

    Point managed HTTP replies at the machine:
    `:on-success [:auth.login/flow [:auth.login/success]]` (outer id, inner
    event). The reply is *appended* into the inner event. Full walk-through in
    the [tutorial](tutorial.md#step-4--talk-to-a-real-server).

## See one run

Click into the cell and press **`Ctrl-Enter`** (**`Cmd-Enter`** on macOS):

```cljs-rf2
(require '[re-frame.core :as rf])

(rf/reg-machine :turnstile/flow
  {:initial :locked
   :data    {:coins 0 :pushes 0}
   :actions {:take-coin  (fn [{data :data}] {:data (update data :coins  inc)})
             :count-push (fn [{data :data}] {:data (update data :pushes inc)})}
   :states
   {:locked   {:on {:coin {:target :unlocked :action :take-coin}
                    :push {:target :locked   :action :count-push}}}
    :unlocked {:on {:push {:target :locked}
                    :coin {:target :unlocked :action :take-coin}}}}})

(rf/reg-view turnstile-view []
  (let [{:keys [state data]} (or @(subscribe [:rf/machine :turnstile/flow])
                                 {:state :locked :data {:coins 0 :pushes 0}})
        open? (= state :unlocked)]
    [:div {:style {:font-family "sans-serif"}}
     [:p "state: " [:strong {:style {:color (if open? "green" "crimson")}} (str state)]]
     [:p "coins: " (:coins data) " · pushes: " (:pushes data)]
     [:button {:on-click #(dispatch [:turnstile/flow [:coin]])} "insert coin"]
     [:button {:on-click #(dispatch [:turnstile/flow [:push]])} "push"]]))

[rf/frame-root {:id :demo}
 [turnstile-view]]
```

!!! tip "Try it"

    Push while locked — the door stays locked, but the push counter climbs (a
    **self-transition** with an action). Dispatch an unknown event
    `[:turnstile/flow [:wat]]` — silent no-op (benign
    `:rf.machine.event/unhandled-no-op` trace). Almost every *other* mistake
    (bad target, missing guard name) fails loud at **registration**.

## The snapshot

<a id="the-snapshot--state-data-tags"></a>

```clojure
{:state :submitting
 :data  {:attempts 1 :error nil}
 :tags  #{:auth/busy}}   ;; omitted when no active state declares tags
```

| Slot | Role |
| --- | --- |
| `:state` | Discrete state — keyword (flat), path vector (hierarchy), or region map (parallel) |
| `:data` | Machine-private memory |
| `:tags` | Runtime-projected union of active states' tags |

`[:rf/machine id]` is `nil` until the first event. A view that renders earlier
should fall back to the definition's `:initial` and `:data`.

Do not build views that switch on detailed `:state` shapes unless the exact
state is the product decision. For "busy", "read-only", "connected", use
[state tags](tags.md).

## Transition forms

An `:on` entry can be written in three forms.

```clojure
:on {:auth.login/submit :submitting}
```

A bare keyword is sugar for `{:target :submitting}`.

```clojure
:on {:auth.login/submit {:target :submitting
                         :guard  :form-valid?
                         :action :clear-error}}
```

A map gives the transition a guard, an action, and other options.

```clojure
:on {:auth.login/failure [{:target :error-shown
                           :guard  :under-retry-limit
                           :action :record-error}
                          {:target :locked-out
                           :action :record-error}]}
```

A vector is a first-match-wins **candidate list**. The runtime tries each
candidate in order and takes the first whose guard passes. Put an unguarded
default last when the event must be handled. The lockout candidate also runs
`:record-error`, so the terminal failure is counted.

## Guards and actions

<a id="guards-and-actions"></a>
<a id="guards-actions-tags-and-after--the-recognition-kit"></a>

Every callback receives one context map:

```clojure
{:data  {:attempts 1 :error nil}
 :event [:auth.login/failure …]
 :state :submitting
 :meta  {…}}
```

There is **no `:db`**. A machine cannot see [app-db](../core/app-db.md). That
is [strict encapsulation](#strict-encapsulation).

### Guards

<a id="a-guard-is-a-yesno-gate"></a>

Return truthy or falsey. There is no combinator DSL — compound logic is
ordinary Clojure:

```clojure
:guards
{:under-retry-limit (fn [{data :data}] (< (:attempts data) 2))
 :form-valid?       (fn [{[_ creds] :event}]
                      (and (seq (:email creds)) (seq (:password creds))))}
```

A guard sees the snapshot *before* the transition's action runs. On a
three-attempt lockout, `:under-retry-limit` therefore reads the count from
failures already recorded, and the boundary sits one below the total you
want — `< 2` for three attempts. The first two failures pass and land in
`:error-shown`; the third fails the guard and the fallback candidate locks
out.

<a id="name-them-or-inline-them"></a>

Reference by id (`:guard :form-valid?`) or inline a one-liner. Prefer named
ids — traces and Xray can address them.

### Actions

<a id="an-action-returns-effects"></a>

Return **descriptions**, the same idea as `reg-event`:

```clojure
:actions
{:clear-error  (fn [_] {:data {:error nil}})
 :issue-request
 (fn [{[_ creds] :event}]
   {:fx [[:rf.http/managed
          {:request    {:method :post :url "/api/login" :body creds
                        :request-content-type :json}
           :decode     :json
           :on-success [:auth.login/flow [:auth.login/success]]
           :on-failure [:auth.login/flow [:auth.login/failure]]}]]})}
```

Require `[re-frame.http.managed]` wherever `:rf.http/managed` appears, or the
effect is `:rf.error/no-such-fx`. `:on-success` / `:on-failure` are one
element short on purpose — managed HTTP **appends** the reply envelope:

```clojure
[:auth.login/success {:status :ok    :value {:token "…"} …}]
[:auth.login/failure {:status :error :error {:message "…"} …}]
```

`:record-error` reads `:error`. `:store-session` reads `:value`.

### The effect map `{:data :fx}`

<a id="the-action-effect-map--data-fx"></a>
<a id="the-effect-map-data-fx"></a>

| Key | Meaning |
| --- | --- |
| `:data` | **Merged** into the snapshot's current `:data` (not replaced). Explicit `nil` sets a key to nil; it does not remove keys. |
| `:fx` | Ordinary effects vector (`:dispatch`, `:rf.http/managed`, …). Machine-only ids: `:raise`, `:rf.machine/spawn`, `:rf.machine/destroy`. |

Both keys are optional; `nil` / `{}` means no effects. Returning `:db` is
`:rf.error/machine-action-wrote-db`.

!!! warning "`:fx` cannot read this action's own `:data` write"

    Both keys are returned together. Bind fresh values in a `let` and use the
    local in both places, or write in the transition action and read in the
    target's `:entry`.

Unresolved `:guard` / `:action` / `:target` names throw at `reg-machine`
(`:rf.error/machine-unresolved-guard`,
`:rf.error/machine-unresolved-action`,
`:rf.error/machine-unresolved-target`), not on first dispatch.

### Entry, exit, and transition actions

A transition can run up to three action slots, in this order:

1. source state's `:exit`
2. transition's `:action`
3. target state's `:entry`

Their `:data` updates accumulate in order; their `:fx` vectors concatenate in
order.

```clojure
:submitting
{:tags  #{:auth/busy}
 :entry :issue-request
 :on    {:auth.login/success {:target :authed :action :store-session}
         :auth.login/failure […]}}
```

Use `:entry` for work that should happen whenever the state is entered —
issuing the request, so every path into `:submitting` fires it. Use `:exit`
for cleanup.

## Strict encapsulation

<a id="strict-encapsulation"></a>
<a id="strict-encapsulation--a-machine-sees-only-its-own-data"></a>

A guard or action gets `{:data :event :state :meta}` (plus `:rf.cofx` when it
declares a coeffect) — never app-db. Parallel regions also see `:tags` /
`:all-state`; that is a later page.

| Need | How |
| --- | --- |
| Fact from outside | Put it on the **event** when you dispatch |
| Write outside the machine | Return `:fx [[:dispatch […]]]` — a real, named event |
| Clock / random / host fact | Declare a [coeffect](../core/coeffects.md) on the named guard or action — do **not** call `(js/Date.now)` |

A declared coeffect arrives under **`:rf.cofx`** on the callback map. Read it
there, `(:rf/time-ms (:rf.cofx ctx))` — it is *not* a top-level `:rf/time-ms`
key. Inline callbacks cannot declare requirements
(`:rf.error/machine-cofx-requires-inline`).

```clojure
:guards
{:within-retry-window?
 {:rf.cofx/requires [:rf/time-ms]
  :fn (fn [{:keys [data] {:keys [rf/time-ms]} :rf.cofx}]
        (< (- time-ms (:first-attempt-at data)) 60000))}}
```

Actions never choose the next state. Only the transition's `:target` moves
the machine.

## Unhandled events are no-ops

<a id="one-thing-that-wont-throw-the-unhandled-event"></a>

If the current state has no transition for an event, the machine ignores it.
The snapshot does not move. A benign `:rf.machine.event/unhandled-no-op`
trace records the drop.

That does not hide mistakes. Broken definitions fail at registration: missing
targets, undefined guards or actions, invalid timeout shapes, illegal
`:final?` combinations. The unhandled event is the one intentionally quiet
case.

## Self-transitions and wildcards

<a id="self-transitions-and-wildcards"></a>
<a id="self-transitions-internal-by-default-external-on-demand"></a>
<a id="wildcard-transitions-handle-a-whole-class-of-events"></a>

Self-moves do not re-enter by default. The turnstile's push-while-locked
counts a push without leaving `:locked`. Three shapes:

| Shape | Effect |
| --- | --- |
| No `:target` (targetless) | Action only — no exit/entry; timers and spawns undisturbed |
| `:target` the same state, no `:reenter?` | Same on a leaf (action only). A compound re-resolves descendants to `:initial` |
| `:reenter? true` | Full exit → action → entry (timers reset, spawns restart) |

A self-rescheduling poll uses the external form so `:entry` re-fires:

```clojure
:polling
{:entry :start-fetch
 :after {30000 {:target :polling :reenter? true}}
 :on    {:got-data {:action :merge}
         :stop     :idle}}
```

A self-target without `:reenter? true` does **not** re-run `:entry`. If you
meant to re-arm a timer, say so.

**Wildcards** on `:on` keys, most-specific first: exact id → `:ns/*` → `:*`.

```clojure
:tracking
{:on {:mouse/down {:action :begin-drag}
      :mouse/*    {:action :note-move}
      :*          {:action :log-unknown}}}
```

A **forbidden** handler — `{:on {:E {}}}` or `{:on {:E nil}}` — **consumes**
the event and stops the search (how a child opts out of a parent
transition). A **missing** key is a silent no-op. A bare id like `:go` has
no `:ns/*` tier — only exact or `:*`. A guard-blocked exact match can fall
through to a wildcard.

## Final states

<a id="final-states"></a>
<a id="final-states-when-a-machine-is-done"></a>

- **Ordinary leaf** with no outgoing transitions — the machine **persists**
  (login's `:authed`). Do **not** set `:final?`. Optional
  `{:meta {:terminal? true}}` is documentation for you and for tools; it
  does not destroy anything.
- **`:final? true`** — the machine **terminates** and is destroyed. Use for
  spawned protocols that finish, not for "last screen of a long-lived
  machine."

Nested finals and parent `:on-done` live in
[Hierarchical states](hierarchical-states.md) and [Actors](actors.md).

## Schemas

<a id="validating-a-machines-data"></a>
<a id="validating-a-machines-completion-output"></a>

A machine can validate its private `:data` in development:

```clojure
(rf/reg-machine :auth.login/flow
  {:initial :idle
   :data    {:attempts 0 :error nil}
   :schemas {:data [:map
                    [:attempts :int]
                    [:error [:maybe :string]]]}
   :states  {…}})
```

A failed data validation rolls the transition back before the bad snapshot
reaches runtime-db (`:where :machine-data`).

`:schemas {:output …}` validates the value a `:final?` leaf reports through
`:output-key`. Full rules: [`re-frame.machines` API](../api/re-frame.machines.md).

## Testing

<a id="testing-transitions-are-pure-function-calls"></a>

The table is a value. Drive it with no frame, no browser, no network:

```clojure
(ns app.login-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.machines :as machines]
            [re-frame.machines.result :as result]
            [app.login :refer [login-flow]]))

(deftest login-flow-test
  (let [r (machines/machine-transition
            login-flow
            {:state :idle :data {:attempts 0 :error nil}}
            [:auth.login/submit {:email "a@b.com" :password "secret"}])]
    (is (result/ok? r))
    (is (= :submitting (:state (result/snap r)))))

  ;; Two failures already recorded; the third is terminal.
  (let [r (machines/machine-transition
            login-flow
            {:state :submitting :data {:attempts 2 :error nil}}
            [:auth.login/failure {:error {:message "bad creds"}}])]
    (is (result/ok? r))
    (is (= :locked-out (:state (result/snap r))))
    (is (= 3 (get-in (result/snap r) [:data :attempts])))
    (is (= "bad creds" (get-in (result/snap r) [:data :error])))))
```

Discriminate with `result/ok?` / `result/fail?`; read the next snapshot and
the emitted effects with `result/snap` / `result/fx`. More, including Xray:
[Inspecting and testing](inspecting-machines.md).

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| First `reg-machine` throws `:rf.error/machines-artefact-missing` | `[re-frame.machines]` not required | Require it once at boot |
| Dev warning `:rf.warning/machine-source-unstamped` | `(def m {…})` then `reg-machine` | Use `defmachine`, or pass a literal map |
| Registration throws `:rf.error/machine-unresolved-guard` (or `-action`, `-target`) | Named ref missing from the table | Add the name, or fix the typo |
| Action fails `:rf.error/machine-action-wrote-db` | Returned `:db` | Update the snapshot via `:data`; write app-db through a named event in `:fx` |
| Dispatch does nothing | Current state has no matching `:on` | Expected no-op (`:rf.machine.event/unhandled-no-op`). Bad names fail at registration |
| `:rf.error/no-such-fx` on `:rf.http/managed` | HTTP artefact not loaded | Require `[re-frame.http.managed]` |

## When the table grows

<a id="when-the-machine-grows"></a>
<a id="tags-and-timers"></a>

Same model, more keys — each page assumes this one:

| Need | Page |
| --- | --- |
| Label intent so views don't enumerate states | [Tags](tags.md) — `@(rf/subscribe [:rf.machine/has-tag? id :auth/busy])` |
| Moves the table takes without a user event | [Automatic transitions](automatic-transitions.md) (`:after` / `:always` / choice / timeout) |
| Nested sub-flows | [Hierarchical states](hierarchical-states.md) |
| Independent axes at once | [Parallel regions](parallel-states.md) |
| Resume mid-compound | [History](history.md) |
| Per-request / worker children | [Actors](actors.md) |
| Prove the table / watch live | [Inspecting and testing](inspecting-machines.md) |

**`:raise`** in an action's `:fx` re-enters *this* machine atomically before
commit. **`:internal-events`** is the set of event ids that external
`dispatch` must not send
(`:rf.error/machine-internal-event-external-dispatch`). Eventless loops and
raise storms are depth-bounded (default 16);
`:rf.error/machine-always-depth-exceeded` /
`:rf.error/machine-raise-depth-exceeded` abort the whole step — not a silent
no-op.

## When to reach for a machine

<a id="when-to-reach-for-a-machine--and-when-not"></a>

**Yes:** named mutually exclusive stages; legal and illegal events;
timers, cancellation, retries, or cleanup; the flow is easier to draw than
to describe; tests should assert `(state, event) → next state + effects`.

**No:**

| Situation | Prefer |
| --- | --- |
| A counter, list, or form field | app-db + events |
| A two-state flag | a keyword or boolean |
| Server cache lifecycle | [resources](../resources/concepts.md) |
| A fixed sequence of operations | chained events |

Named states are the concept — not named operations.
