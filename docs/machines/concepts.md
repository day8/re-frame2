# 2. The table

<a id="concepts"></a>
<a id="the-model"></a>
<a id="state-machines"></a>

The [first machine](tutorial.md) used a handful of keys. This page explains
each part of the table and the rules the runtime follows when a transition
runs.

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

Drive it with `dispatch`, as the [first machine](tutorial.md#step-1--your-first-machine)
does: the event id is the machine id, and the second element is the trigger
the table matches. Read it with the framework subscription:

```clojure
@(rf/subscribe [:rf/machine :auth.login/flow])
;; => {:state :submitting :data {:attempts 0 :error nil} :tags #{:auth/busy}}
```

The snapshot lives in [runtime-db](../core/glossary.md#runtime-db), so undo,
time-travel, and SSR hydration work without extra wiring.

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

A live snapshot also carries framework-owned `:rf/*` keys, at its root and
inside `:data`. The snapshots printed in this guide show only those a page is
about, so compare the slots you care about rather than a whole `:data` map,
and never write one of those keys yourself.

`[:rf/machine id]` is `nil` until the first event. A view that renders earlier
should fall back to the definition's `:initial` and `:data`. To boot a
singleton eagerly instead, dispatch the reserved start marker at startup:
`(rf/dispatch [:auth.login/flow [:rf.machine/start]])`. It runs the initial
entry — `:entry` actions fire, `:after` timers arm — and stops; it never
matches an `:on` transition. The first ordinary event runs the same initial
entry before it is handled, and either way those `:entry` actions see `:event`
as `[:rf.machine/start]`, never the trigger that started the machine.

Do not build views that switch on detailed `:state` shapes unless the exact
state is the product decision. For "busy", "read-only", "connected", use
[state tags](tags.md).

`:data` must survive `pr-str` and `read-string`: no functions, atoms or host
objects. That is what lets a snapshot persist. Save the machines from
`frame-state-value` and hand them back at boot with `:rf/install-frame-state`,
which restores spawned children and re-arms `:after` timers without re-running
`:entry` ([Persist and restore](coming-from-xstate.md#persist-and-restore)).

In every frame that has an `:id`, a hot reload keeps the live snapshot and
applies the new table from the next event; a frame made without an `:id` keeps
the table it was made with. If the reload removed the current state, the
machine restarts from `:initial` before handling that event, and reports
`:rf.error/machine-state-not-in-definition`.

## Transition forms

An `:on` entry can be written in three forms.

```clojure
:on {:auth.login/submit :submitting}
```

A bare keyword is sugar for `{:target :submitting}`, and a vector of keywords
is the same sugar for a [path target](hierarchical-states.md#target-forms).

```clojure
:on {:auth.login/submit {:target :submitting
                         :guard  :form-valid?
                         :action :clear-error}}
```

A map gives the transition a guard, an action, and other options. Its keys
are `:target`, `:guard`, `:action`, `:reenter?`
([self-transitions](#self-transitions-and-wildcards)) and `:meta`.

```clojure
:on {:auth.login/failure [{:target :error-shown
                           :guard  :under-retry-limit
                           :action :record-error}
                          {:target :locked-out
                           :action :record-error}]}
```

A vector of maps is a first-match-wins **candidate vector**. The runtime tries
each candidate in order and takes the first whose guard passes. Put an
unguarded default last when the event must be handled.

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

`:state` is the state the machine was in before this transition, in every
slot, `:entry` included. `:meta` is the snapshot's `:meta`, which starts as
the machine root's `:meta`.

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

A guard sees the snapshot *before* the transition's action runs, so
`:under-retry-limit` counts only the failures already recorded: `< 2`
allows three attempts.

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

The [first machine](tutorial.md#step-4--talk-to-a-real-server) explains the
artefact this effect needs, the reply envelope, and the one-element-short
target shape.

### The effect map `{:data :fx}`

<a id="the-action-effect-map--data-fx"></a>
<a id="the-effect-map-data-fx"></a>

| Key | Meaning |
| --- | --- |
| `:data` | **Merged** into the snapshot's current `:data`, top-level keys only: a nested map you return replaces the one there. Explicit `nil` sets a key to nil; it does not remove keys. |
| `:fx` | Ordinary effects vector (`:dispatch`, `:rf.http/managed`, `:rf.machine/spawn`, …), plus the machine-only `:raise`. |

Both keys are optional; `nil` / `{}` means no effects. A returned `:db` is
dropped with `:rf.error/machine-action-wrote-db`, and the rest of the
transition commits.

!!! warning "`:fx` cannot read this action's own `:data` write"

    Both keys are returned together. Bind fresh values in a `let` and use the
    local in both places, or write in the transition action and read in the
    target's `:entry`.

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

Each slot takes one fn or one action id. A vector is refused
(`:rf.error/machine-bad-action-form`); to do two things, call both from one
action and merge what they return:

```clojure
(defn clear-error   [_] {:data {:error nil}})
(defn count-attempt [{data :data}] {:data {:attempts (inc (:attempts data))}})

:actions
{:clear-and-count
 (fn [ctx]
   (let [a (clear-error ctx)
         b (count-attempt ctx)]
     {:data (merge (:data a) (:data b))
      :fx   (into (:fx a []) (:fx b []))}))}
```

## Strict encapsulation

<a id="strict-encapsulation"></a>
<a id="strict-encapsulation--a-machine-sees-only-its-own-data"></a>

A guard or action sees only its context map, plus `:rf.cofx` when it declares
a coeffect. To reach anything else:

| Need | How |
| --- | --- |
| Fact from outside | Put it on the **event** when you dispatch |
| Write outside the machine | Return `:fx [[:dispatch […]]]` — a real, named event |
| Clock / random / host fact | Declare a [coeffect](../core/coeffects.md) on the named guard or action — do **not** call `(js/Date.now)` |
| A subscription's value | Declare `{:rf/sub [:feature/flags] :as :app/flags}` in the named guard's or action's `:rf.cofx/requires` (`:as` takes a namespaced id); it is read once, before the transition |

A declared coeffect arrives under **`:rf.cofx`** on the callback map. Read it
there, `(:rf/time-ms (:rf.cofx ctx))` — it is *not* a top-level `:rf/time-ms`
key. Inline callbacks cannot declare requirements
(`:rf.error/machine-cofx-requires-inline`). Declare every key a callback reads:
an undeclared key is not ensured, so it can read `nil`, and `reg-machine` warns
`:rf.warning/machine-cofx-consume-undeclared` in development.

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

That does not hide mistakes. Broken definitions throw at `reg-machine`, not
on first dispatch: a missing target, guard or action name
(`:rf.error/machine-unresolved-target`, `-unresolved-guard`,
`-unresolved-action`), an invalid timeout shape, an illegal `:final?`
combination. The unhandled event is the one intentionally quiet case.

Keys are checked too. A state or transition map takes a closed set of bare
keys, so a typo or an XState spelling (`:invoke`, `:cond`) throws
`:rf.error/machine-unknown-node-key`, and the message names the valid keys.
Put your own annotations under a namespaced key (`:my.app/note`) or `:meta`.
Every refusal is an `ex-info` carrying `:rf.error/id` in its `ex-data`
([Errors that throw](../core/errors.md#the-errors-that-throw-not-trace)).

## State node keys

These are the bare keys a state takes:

| Key | Meaning | Taught in |
| --- | --- | --- |
| `:on` | Transitions taken on an event | [Transition forms](#transition-forms) |
| `:entry`, `:exit` | Action run on entering or leaving the state | [Entry, exit, and transition actions](#entry-exit-and-transition-actions) |
| `:initial`, `:states` | Child states, and the one entered first | [Hierarchical states](hierarchical-states.md) |
| `:on-done` | Transition taken when a child `:final?` state is reached | [Nested final states](hierarchical-states.md#when-a-sub-flow-finishes-nested-final-states) |
| `:always` | Eventless transitions | [Automatic transitions](automatic-transitions.md#eventless-always) |
| `:after` | Delayed transitions | [Delayed `:after`](automatic-transitions.md#delayed-after) |
| `:timeout`, `:on-timeout` | A deadline and the transition it takes | [`:timeout` and `:on-timeout`](automatic-transitions.md#timeout-and-on-timeout) |
| `:type` | `:choice` or `:history` on a state; `:parallel` on the root | [Choice states](automatic-transitions.md#choice-states), [History](history.md), [Parallel regions](parallel-states.md) |
| `:choice` | A choice state's candidate vector | [Choice states](automatic-transitions.md#choice-states) |
| `:deep?`, `:default-target` | A history pseudo-state's options | [History](history.md#the-keys) |
| `:spawn`, `:spawn-all` | Child actors that live while the state is active | [Actors](actors.md) |
| `:tags` | A set of labels projected onto the snapshot | [Tags](tags.md) |
| `:final?`, `:output-key`, `:error?` | A finishing leaf and what it reports | [Final states](#final-states) |
| `:meta` | Your own static metadata, such as `{:terminal? true}` | [Final states](#final-states) |

The root also takes `:data`, `:guards`, `:actions`, `:schemas`,
`:internal-events` and the two depth limits, and a parallel root takes
`:regions` and `:region-order`; the
[API reference](../api/re-frame.machines.md#machine-root-keys) lists them.

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

A `:final?` state is a leaf with no way out: it may run `:entry` and `:exit`,
but `:on`, `:always`, `:after`, `:spawn` and `:spawn-all` there are refused
(`:rf.error/machine-final-state-has-transitions`), and so are child `:states`
(`:rf.error/machine-final-state-compound`). `:output-key` and `:error?` belong
only beside `:final?`.

Nested finals and parent `:on-done` live in
[Hierarchical states](hierarchical-states.md) and [Actors](actors.md).

## Schemas

<a id="validating-a-machines-data"></a>
<a id="validating-a-machines-completion-output"></a>

A machine can validate its private `:data` in development. Require
`[re-frame.schemas]` once at boot; without it, `:schemas` checks nothing and
says nothing:

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
`:output-key`. The machine has already finished, so a failure is reported
(`:where :machine-output`) and the value is delivered anyway.

A schema does not hide a value from traces. To redact a secret in `:data`,
name its path on the machine, starting from the snapshot:
`{:sensitive [[:data :token]]}`. Machine traces redact that slot for every
instance, spawned ones included — see [Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md#classify-subsystem-data-on-the-subsystem).
A `[:rf/machine id]` subscription carries the same classification: its
`:rf.sub/run` trace, and an off-box read of it by query vector, redact those
slots (and size-mark any declared `:large` paths) exactly as machine traces do,
while reading the sub in-process returns the real values.

## Testing

<a id="testing-transitions-are-pure-function-calls"></a>

The table is a value, so `(rf.machines/machine-transition definition snapshot trigger)`
returns the next snapshot and the described effects as data. The
[first machine](tutorial.md#step-6--test-it-a-transition-is-a-pure-function)
tests login this way; [Inspecting and testing](inspecting-machines.md) covers
the rest.

## Raise and internal events

<a id="when-the-table-grows"></a>
<a id="when-the-machine-grows"></a>
<a id="tags-and-timers"></a>

**`:raise`** in an action's `:fx` re-enters *this* machine atomically before
commit. **`:internal-events`** is the set of event ids that external
`dispatch` must not send
(`:rf.error/machine-internal-event-external-dispatch`). Eventless loops and
raise storms are depth-bounded — 16 by default, or the root's
`:always-depth-limit` / `:raise-depth-limit`;
`:rf.error/machine-always-depth-exceeded` /
`:rf.error/machine-raise-depth-exceeded` abort the whole step — not a silent
no-op.

A raise is exactly `[:raise event-vec]`. It takes no options, so a delayed
raise is an `:after` on a state; `[:raise event-vec opts]` throws
`:rf.error/machine-bad-raise`.

## When to reach for a machine

<a id="when-to-reach-for-a-machine--and-when-not"></a>

Reach for a machine when named, mutually exclusive stages are what you are
modelling — legal and illegal triggers, timers, cancellation, retries or
cleanup. The [landing page](index.md#when-not-to-use-a-machine) lists when
not to.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| First `reg-machine` throws `:rf.error/machines-artefact-missing` | `[re-frame.machines]` not required | Require it once at boot |
| Dev warning `:rf.warning/machine-source-unstamped` | `(def m {…})` then `reg-machine` | Use `defmachine`, or pass a literal map |
| Registration throws `:rf.error/machine-unresolved-guard` (or `-action`, `-target`) | Named ref missing from the table | Add the name, or fix the typo |
| Registration throws `:rf.error/machine-unknown-node-key` | A misspelt or XState key (`:invoke`, `:cond`), or `:on-done` on a leaf | Use a key the message lists; namespace your own |
| Registration throws `:rf.error/machine-bad-action-form` | `:entry`, `:exit` or `:action` is a vector | One fn or action id; call several from one fn |
| Action reports `:rf.error/machine-action-wrote-db` | Returned `:db`, which is dropped | Update the snapshot via `:data`; write app-db through a named event in `:fx` |
| Dispatch does nothing | Current state has no matching `:on` | Expected no-op (`:rf.machine.event/unhandled-no-op`). Bad names fail at registration |
| `:rf.error/no-such-fx` on `:rf.http/managed` | HTTP artefact not loaded | Require `[re-frame.http.managed]` |
| External dispatch of a private event is refused | Id is in `:internal-events` | Raise it from an action, or drop it from the set |
| Macrostep fails `:rf.error/machine-always-depth-exceeded` or `-raise-depth-exceeded` | Eventless / `:raise` loop did not settle | Break the cycle; default bound is 16 |
