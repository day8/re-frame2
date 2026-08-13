# 2. The table

<a id="concepts"></a>
<a id="the-model"></a>
<a id="state-machines"></a>

The [first machine](tutorial.md) filled the slots. This page is the rest of
the **flat table** contract — registration, the snapshot, transition forms,
encapsulation, self-transitions, finals, schemas, and `:raise`. It does not
rebuild the login walk-through.

!!! note "Where should this value live?"

    A machine fits when a value has a *lifecycle* of named states — not when it
    is only data to store. See [Where should this value live?](../core/where-state-lives.md).

## The idea

<a id="a-machine-at-a-glance"></a>
<a id="the-same-flow-as-a-transition-table"></a>
<a id="the-idea"></a>

A table has five everyday parts:

- `:initial` — where the machine starts.
- `:data` — private working memory.
- `:guards` — yes/no predicates.
- `:actions` — return `{:data … :fx …}`; they never perform side effects.
- `:states` — the nodes and their outgoing transitions.

Resting leaves such as `:authed` keep the snapshot around so a view can still
render them. They are **not** `:final?` — that flag destroys the machine
([Final states](#final-states)).

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

You drive the machine with `dispatch`, not `send`. The outer vector is a
re-frame2 **event** whose id is the machine id. The second element is the
**trigger** the table matches:

```clojure
(rf/dispatch [:auth.login/flow [:auth.login/submit credentials]])
```

`:auth.login/submit` is the `:on` key. `credentials` is payload, read from
`:event` in a guard or action. The [landing page](index.md#first-class-support)
introduces this split; the [first machine](tutorial.md) drives it.

**Subscribe** with the framework sub — there is no function sugar:

```clojure
@(rf/subscribe [:rf/machine :auth.login/flow])
;; => {:state :submitting :data {:attempts 0 :error nil} :tags #{:auth/busy}}
;;    nil before the first event — this is a singleton
```

The snapshot lives in [runtime-db](../core/glossary.md#runtime-db), so undo,
time-travel, and SSR hydration work without extra wiring.

A singleton is the registered id. A spawned actor is a second live instance
of a type, with an allocated id — [Actors](actors.md).

!!! note "Async composes"

    Point managed HTTP replies at the machine:
    `:on-success [:auth.login/flow [:auth.login/success]]` (outer event id,
    inner trigger). The reply is *appended* onto the trigger. Full
    walk-through in the [first machine](tutorial.md#step-4--talk-to-a-real-server).

## See one run

A tiny clickable machine, not the login flow. Use it to feel a
self-transition. Click into the cell and press **`Ctrl-Enter`**
(**`Cmd-Enter`** on macOS):

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
should fall back to the definition's `:initial` and `:data`. To boot a
singleton eagerly instead, dispatch the reserved start marker at startup:
`(rf/dispatch [:auth.login/flow [:rf.machine/start]])`. It runs the initial
entry — `:entry` actions fire, `:after` timers arm — and stops; it never
matches an `:on` transition.

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
effect is `:rf.error/no-such-fx`. The reply envelope and the one-element-short
target shape are in the [first machine](tutorial.md#step-4--talk-to-a-real-server).

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

The table is a value. `(machines/machine-transition definition snapshot trigger)`
returns the next snapshot and the effects the action described. The
[first machine](tutorial.md#step-6--test-it-a-transition-is-a-pure-function)
has the login cases. [Inspecting and testing](inspecting-machines.md) is the
full surface.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| First `reg-machine` throws `:rf.error/machines-artefact-missing` | `[re-frame.machines]` not required | Require it once at boot |
| Dev warning `:rf.warning/machine-source-unstamped` | `(def m {…})` then `reg-machine` | Use `defmachine`, or pass a literal map |
| Registration throws `:rf.error/machine-unresolved-guard` (or `-action`, `-target`) | Named ref missing from the table | Add the name, or fix the typo |
| Action fails `:rf.error/machine-action-wrote-db` | Returned `:db` | Update the snapshot via `:data`; write app-db through a named event in `:fx` |
| Dispatch does nothing | Current state has no matching `:on` | Expected no-op (`:rf.machine.event/unhandled-no-op`). Bad names fail at registration |
| `:rf.error/no-such-fx` on `:rf.http/managed` | HTTP artefact not loaded | Require `[re-frame.http.managed]` |
| External dispatch of a private event is refused | Id is in `:internal-events` | Raise it from an action, or drop it from the set |
| Macrostep fails `:rf.error/machine-always-depth-exceeded` or `-raise-depth-exceeded` | Eventless / `:raise` loop did not settle | Break the cycle; default bound is 16 |

## Raise and internal events

<a id="when-the-table-grows"></a>
<a id="when-the-machine-grows"></a>
<a id="tags-and-timers"></a>

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

Use a machine when named mutually exclusive stages are the load-bearing
concept: legal and illegal triggers, timers, cancellation, retries, or
cleanup; the flow is easier to draw than to describe; tests should assert
`(state, trigger) → next state + effects`.

The [landing page](index.md#when-not-to-use-a-machine) has the when-not
table.
