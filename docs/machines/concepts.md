# The model

<a id="state-machines"></a>

This page is the **flat machine model** — everything you need for a single-level
state machine. Nested states, parallel regions, history, and actors grow the same
grammar; each has its own page.

To *build* a login machine step by step (guard → action → HTTP → view → test), use
the [tutorial](tutorial.md). Here the goal is understanding the pieces and their
contracts.

!!! note "Where should this value live?"

    A machine fits when a value has a *lifecycle* of named states — not when it is
    only data to store. See [Where should this value live?](../core/where-state-lives.md).

## The idea

<a id="a-machine-at-a-glance"></a>
<a id="the-same-flow-as-a-transition-table"></a>

You already write state machines: a `:status` keyword in app-db plus informal rules
in handlers about what may follow what. A machine writes those rules down as **one
value** — a transition table — so you can read, draw, test, and change the flow in
one place.

```clojure
(rf/defmachine turnstile
  {:initial :locked
   :data    {:coins 0}
   :actions {:take-coin (fn [{data :data}] {:data (update data :coins inc)})}
   :states
   {:locked   {:on {:coin {:target :unlocked :action :take-coin}
                    :push {:target :locked}}}     ;; blocked: stays locked
    :unlocked {:on {:push {:target :locked}
                    :coin {:target :unlocked :action :take-coin}}}}})
```

Two words do most of the work ([glossary](glossary.md)):

- A **[guard](glossary.md#guard)** — yes/no gate on a transition.
- An **[action](glossary.md#action)** — side work that returns effects, never performs them.

## Register and drive

<a id="registering-and-running-it"></a>

**A machine is an event handler.** `reg-machine` is sugar over `reg-event` whose body
interprets the table: read the live [snapshot](glossary.md#snapshot), take a
transition, write the new snapshot, return action effects.

```clojure
(:require [re-frame.core :as rf]
          [re-frame.machines])   ;; opt-in: forget this → :rf.error/machines-artefact-missing

(rf/reg-machine :turnstile turnstile)
```

Two blessed registration shapes:

| Shape | Use when |
|---|---|
| `defmachine` + `reg-machine` | Named, reusable specs (Xray click-to-source on guards/actions) |
| Inline `reg-machine` with a **literal** map | Small local machines |

Avoid `(def m {…})` then `(reg-machine :id m)` — the macro never sees the literal, so
source stamps are empty (dev warns `:rf.warning/machine-source-unstamped`).

**Dispatch** addresses the machine id; the *inner* vector is the machine event:

```clojure
(rf/dispatch [:turnstile [:coin]])
```

**Subscribe** with the framework sub:

```clojure
@(rf/subscribe [:rf/machine :turnstile])
;; => {:state :unlocked :data {:coins 1}}   ; nil before the first event
```

The snapshot lives in [runtime-db](../core/glossary.md#runtime-db) (framework half of
the frame), so undo, time-travel, and SSR hydration work without extra wiring.

!!! note "Async composes"

    Point managed HTTP replies at the machine:
    `:on-success [:auth.login/flow [:auth.login/success]]` (outer id, inner event).
    The reply is *appended* into the inner event — no adapter. Full walk-through in
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

<a id="one-thing-that-wont-throw-the-unhandled-event"></a>

## Guards and actions

<a id="guards-and-actions"></a>
<a id="guards-actions-tags-and-after--the-recognition-kit"></a>

Every callback receives one context map:

```clojure
{:data  {:attempts 1 :error nil}   ;; machine-private memory
 :event [:auth.login/failure …]    ;; inbound event vector
 :state :submitting
 :meta  {…}}
```

There is **no `:db`**. A machine cannot see [app-db](../core/app-db.md). That is
[strict encapsulation](#strict-encapsulation) — the rule that keeps the whole
machine inside one snapshot for time-travel.

### Guards

<a id="a-guard-is-a-yesno-gate"></a>

Return truthy/falsey. No combinator DSL — compound logic is ordinary Clojure:

```clojure
:guards
{:under-retry-limit (fn [{data :data}] (< (:attempts data) 2))   ;; three attempts total
 :form-valid?       (fn [{[_ creds] :event}]
                      (and (seq (:email creds)) (seq (:password creds))))}
```

A guard sees the snapshot as it stands *before* the transition's action runs, so
`:under-retry-limit` reads the count from the two failures already recorded and
the boundary sits one below the total you want — `2` for the
[tutorial](tutorial.md)'s three-attempt lockout. XState guards evaluate in the
same place, ahead of the `assign`, so the off-by-one reads the same there.

<a id="name-them-or-inline-them"></a>

Reference by id (`:guard :form-valid?`) or inline a one-liner. Prefer named ids —
trace rows and Xray can address them.

A list of transition candidates is tried in order; first guard that passes wins.

### Actions

<a id="an-action-returns-effects"></a>

Return **descriptions**, same idea as `reg-event`:

```clojure
:actions
{:clear-error  (fn [_] {:data {:error nil}})
 :issue-request
 (fn [{[_ creds] :event}]
   {:fx [[:rf.http/managed {… :on-success [:auth.login/flow [:auth.login/success]]}]]})}
```

Also: `:entry` / `:exit` on a state (run when the state is entered / left).

### The effect map `{:data :fx}`

<a id="the-action-effect-map--data-fx"></a>
<a id="the-effect-map-data-fx"></a>

| Key | Meaning |
|---|---|
| `:data` | **Merged** into the snapshot's current `:data` (not replaced). Explicit `nil` sets a key to nil; it does not remove keys. |
| `:fx` | Ordinary effects vector (`:dispatch`, `:rf.http/managed`, …). Machine-only ids: `:raise`, `:rf.machine/spawn`, `:rf.machine/destroy`. |

Both keys optional; `nil` / `{}` means no effects. Returning `:db` is an error
(`:rf.error/machine-action-wrote-db`) — machines must not scribble on app-db.

!!! warning "`:fx` cannot read this action's own `:data` write"

    Both keys are returned together. Bind fresh values in a `let` and use the local
    in both places, or write in the transition action and read in the target's
    `:entry`.

Unresolved `:guard` / `:action` / `:target` names throw at `reg-machine` time
(`:rf.error/machine-unresolved-guard`, etc.), not on first dispatch.

## Strict encapsulation

<a id="strict-encapsulation--a-machine-sees-only-its-own-data"></a>

A guard or action gets `{:data :event :state :meta}` (plus `:rf.cofx` when it
declares a coeffect, below) — never app-db. That is what keeps a machine's whole
state inside one snapshot for time-travel and SSR.

| Need | How |
|---|---|
| Fact from outside | Put it on the **event** when you dispatch |
| Write outside the machine | Return `:fx [[:dispatch […]]]` — a real, named event |
| Clock / random / host fact | Declare a [coeffect](../core/coeffects.md) on the guard or action — do **not** call `(js/Date.now)` |

A declared coeffect arrives under **`:rf.cofx`** on the callback map — the causal
token the router recorded, so the decision replays deterministically. Read it there,
`(:rf/time-ms (:rf.cofx ctx))`; it is *not* a top-level `rf/time-ms` key.

```clojure
:guards
{:within-retry-window?
 {:rf.cofx/requires [:rf/time-ms]
  :fn (fn [{:keys [data] {:keys [rf/time-ms]} :rf.cofx}]
        (< (- time-ms (:first-attempt-at data)) 60000))}}
```

Returning `:db` from an action is a hard error
(`:rf.error/machine-action-wrote-db`) — the key is dropped and the failure is
loud. Actions also never choose the next state; only the transition's `:target`
moves the machine.

## The snapshot

<a id="the-snapshot--state-data-tags"></a>

```clojure
{:state :submitting
 :data  {:attempts 1 :error nil}
 :tags  #{:auth/busy}}   ;; optional — union of active states' tags
```

| Slot | Role |
|---|---|
| `:state` | Discrete state — keyword (flat), path vector (hierarchy), or region map (parallel) |
| `:data` | Machine-private memory |
| `:tags` | Runtime-projected set of active tags (omit when empty) |

`[:rf/machine id]` is `nil` until the first event; views should fall back to
`:initial` / default `:data` if they render earlier.

Optional **`:schemas {:data …}`** (Malli) validates `:data` at commit in dev and
rolls back a bad transition.

<a id="validating-a-machines-data"></a>
<a id="validating-a-machines-completion-output"></a>

Full rules: schemas section of the
[`re-frame.machines` API](../api/re-frame.machines.md).

## Self-transitions and wildcards

<a id="self-transitions-internal-by-default-external-on-demand"></a>
<a id="wildcard-transitions-handle-a-whole-class-of-events"></a>

Self-moves **don't re-enter by default** — the turnstile's push-while-locked counts a
push without leaving `:locked`. (XState calls that non-reentering shape "internal";
re-frame2's runtime `internal?` flag is narrower — it's the **targetless** no-op alone,
never a targeted self/ancestor move.) Three shapes:

| Shape | Effect |
|---|---|
| No `:target` (targetless) | The `internal?` no-op: action only — no exit/entry; timers and spawns undisturbed; descendants preserved |
| `:target` same state, no `:reenter?` | Non-reentering: the target survives, but **compounds** re-resolve descendants to `:initial` (a leaf self-target has none, so it's action-only) |
| `:reenter? true` | Full exit → action → entry (timers reset, spawns restart) |

A self-rescheduling poll uses the external form so `:entry` re-fires:

```clojure
:polling
{:entry :start-fetch
 :after {30000 {:target :polling :reenter? true}}   ;; every 30s: re-enter → fetch again
 :on    {:got-data {:action :merge}                  ;; internal: merge without resetting the clock
         :stop     :idle}}
```

!!! note "Targeted self ≠ re-enter"

    A self-target without `:reenter? true` does **not** re-run `:entry`. If you meant
    to re-arm a timer or re-spawn a child, say so explicitly.

**Wildcards** on `:on` keys, most-specific first: exact id → `:ns/*` → `:*`.

```clojure
:tracking
{:on {:mouse/down {:action :begin-drag}   ;; exact wins for :mouse/down
      :mouse/*    {:action :note-move}     ;; any other :mouse/…
      :*          {:action :log-unknown}}} ;; anything else
```

A **forbidden** handler — `{:on {:E {}}}` or `{:on {:E nil}}` — **consumes** the
event and stops the search (how a child opts out of a parent transition). A
**missing** key is a silent no-op. A bare id like `:go` has no `:ns/*` tier — only
exact or `:*`.

## Final states

<a id="final-states"></a>
<a id="final-states-when-a-machine-is-done"></a>

- **Ordinary leaf** with no outgoing transitions — machine **persists** (login's
  `:authed`). Do **not** set `:final?`.
- **`:final? true`** — machine **terminates** and is destroyed. Use for spawned
  protocols that finish, not for "last screen of a long-lived machine."

Nested finals and parent `:on-done` live in
[Hierarchical states](hierarchical-states.md) and [Actors](actors.md).

## Tags and automatic moves (pointers)

<a id="tags-and-timers"></a>

- **Tags** — label intent on states (`:tags #{:auth/busy}`); views ask
  `[:rf.machine/has-tag? id :auth/busy]` instead of enumerating state names.
  → [Tags](tags.md)
- **`:after` / `:always` / choice / timeout** — moves the table takes without a
  user event. → [Automatic transitions](automatic-transitions.md)

## When the table grows

<a id="when-the-machine-grows"></a>
<a id="testing-transitions-are-pure-function-calls"></a>

Same model, more keys — each page assumes this one:

| Need | Page |
|---|---|
| Nested sub-flows | [Hierarchical states](hierarchical-states.md) |
| Independent axes at once | [Parallel regions](parallel-states.md) |
| Resume mid-compound | [History](history.md) |
| Per-request / worker children | [Actors](actors.md) |
| Prove the table / watch live | [Inspecting and testing](inspecting-machines.md) |

**`:raise`** in an action's `:fx` re-enters *this* machine atomically before commit.
**`:internal-events`** marks event ids that external dispatch must not send.

Eventless loops and raise storms are depth-bounded (default 16); tripping aborts the
macrostep with a loud error — not a silent no-op.

## When to reach for a machine

<a id="when-to-reach-for-a-machine--and-when-not"></a>

**Yes:** named mutually exclusive stages; conditional transitions scattered as
`when`s; the flow is worth drawing on a whiteboard.

**No:** plain data; two-state flags; server cache lifecycles ([resources](../resources/concepts.md));
mere operation sequences (chained events).

**Named states are the concept — not named operations.**
