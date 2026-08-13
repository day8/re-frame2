# Coming from XState

This page is a translation, not a tutorial. It maps XState v5/v6 ideas onto
re-frame2.

If you know XState, the statechart ideas transfer well:

- states and transitions;
- guards and actions;
- nested states;
- parallel regions;
- tags;
- delayed and eventless transitions;
- final states;
- history;
- run-to-completion.

The biggest change is not the chart. It is where the running machine lives.

In XState, you create an actor, start it, and send to it.

In re-frame2, a machine is an event handler. You register it, dispatch to it, and
read its snapshot from the frame. There is no actor object, no `send`, and no
second event system.

```clojure
(rf/reg-machine :auth.login/flow login-flow)

(rf/dispatch [:auth.login/flow [:auth.login/submit creds]])

@(rf/subscribe [:rf/machine :auth.login/flow])
```

Require `[re-frame.machines]` once at boot. The first `reg-machine` without it is
`:rf.error/machines-artefact-missing`.

The behavioural baseline is XState v6 — plain guard and action functions, optional
schemas, explicit timeouts, choice states, private events, event-shaped
completion. v5 helpers such as `assign`, `sendTo`, `raise`, and `setup()` map
onto the data-first forms below.

## Mapping

| XState idea | re-frame2 |
|---|---|
| `createMachine(...)` / `setup().createMachine()` | `(rf/defmachine …)` then `(rf/reg-machine id …)`, or an inline `reg-machine` literal |
| `context` | `:data` |
| `state.value` | snapshot `:state` |
| `state.context` | snapshot `:data` |
| `actor.getSnapshot()` | `@(rf/subscribe [:rf/machine id])` |
| `actor.send(event)` | `(rf/dispatch [machine-id [event …]])` |
| `states` | `:states` |
| `initial` | `:initial` |
| nested states | compound states with `:initial` + `:states` |
| `type: "parallel"` | `:type :parallel` + `:regions` |
| `type: "history"` | `:type :history` |
| `type: "final"` | `:final? true` (auto-destroys; omit it on a resting leaf) |
| `reenter: true` | `:reenter? true` |
| `tags` | `:tags #{…}` |
| `state.hasTag(tag)` | `@(rf/subscribe [:rf.machine/has-tag? id tag])` |
| `guard` | `:guard` named in `:guards` |
| `actions` / `assign` | `:action` returning `{:data … :fx …}` |
| `always` | `:always` |
| `after` | `:after` |
| timeout / onTimeout | `:timeout` + `:on-timeout` |
| choice state | `:type :choice` + a declarative `:choice` vector |
| `invoke` | `:spawn` |
| `invoke` `onError` | `:spawn`'s `:on-error` transition |
| multiple invokes / fan-out | `:spawn-all` |
| `raise` | `:fx [[:raise [:tick]]]` |
| `sendTo` | `:fx [[:dispatch [other-id [:their/event]]]]` or `[:rf.machine/dispatch-to-system [system-id [:their/event]]]` |
| `output` | `:output-key` on a final state |
| `internalEvents` | `:internal-events #{…}` |
| TypeScript types / v6 `schemas` | `:schemas {:data … :output …}` |

## Machine definition

XState commonly separates machine shape from a `setup()` registry. re-frame2
keeps the table, guards, and actions in one map. Prefer `defmachine` (or an
inline `reg-machine` literal). A plain `(def m {…})` then `reg-machine` leaves
source stamps empty (`:rf.warning/machine-source-unstamped`).

```clojure
(rf/defmachine login-flow
  {:initial :idle
   :data    {:attempts 0 :error nil}

   :guards
   {:under-retry-limit (fn [{data :data}]
                         (< (:attempts data) 2))}

   :actions
   {:record-error
    (fn [{data :data [_ {:keys [error]}] :event}]
      {:data (-> data
                 (update :attempts inc)
                 (assoc  :error (or (:message error) "Login failed.")))})}

   :states
   {:idle
    {:on {:auth.login/submit :submitting}}

    :submitting
    {:on {:auth.login/failure [{:target :error-shown
                                :guard  :under-retry-limit
                                :action :record-error}
                               {:target :locked-out
                                :action :record-error}]}}}})

(rf/reg-machine :auth.login/flow login-flow)
```

The guard reads the pre-action `:attempts`, so `< 2` is three attempts. The
failure payload sits under `:error`. Cross-machine reuse is ordinary Clojure
reuse: put a guard or action function in a var and reference it from several
specs.

## Context becomes :data

The concept is the same: extended state attached to the finite state.

```clojure
{:state :submitting
 :data  {:attempts 1 :error nil}}
```

The name differs because "context" already has other meanings in Clojure,
re-frame, and React, and `:data` makes the action return shape match an event
handler's.

## Actions return effects

This is the most important behavioural difference.

An XState action may perform work or use `assign` to update context. A re-frame2
action returns a value:

```clojure
(fn [{data :data}]
  {:data {:attempts (inc (:attempts data))}
   :fx   [[:analytics/track {:event :login-failed}]]})
```

Returning `{:data …}` is the assignment. Returning `{:fx …}` describes effects
for re-frame2's effect machinery to perform. The action itself stays pure, so a
transition is easy to test and replay.

## The topology stays data

In re-frame2, functions live in guards and actions. The graph stays declarative.

That means:

- targets are data;
- candidate lists are data;
- `:choice` is a vector of guarded candidates, not a routing function;
- guard composition happens inside named guard functions, not a separate
  combinator DSL.

A declarative graph can be rendered, diffed, inspected, tested, and edited by
tools.

## Events and dispatch

XState event objects commonly look like:

```js
{ type: "SUBMIT", credentials }
```

XState's `{ type: "SUBMIT", credentials }` is one event object. re-frame2
writes that same object as a **trigger** vector:

```clojure
[:auth.login/submit credentials]
```

You do not `send` that vector to an actor. You `dispatch` it *inside* a
normal re-frame2 **event** whose id is the machine id:

```clojure
(rf/dispatch [:auth.login/flow [:auth.login/submit credentials]])
```

`:auth.login/flow` is the handler id — the same id you passed to
`reg-machine`. That id is a **singleton**. `[:auth.login/submit credentials]`
is the trigger the table matches against `:on`. The first keyword is the
`:on` key; the rest is payload.

## Reading the snapshot

```clojure
@(rf/subscribe [:rf/machine :auth.login/flow])
;; => {:state :submitting
;;     :data  {:attempts 1 :error nil}
;;     :tags  #{:auth/busy}}
```

This is an ordinary subscription, so projections are ordinary subscriptions too:

```clojure
(rf/reg-sub :auth.login/error
  :<- [:rf/machine :auth.login/flow]
  (fn [snap _]
    (get-in snap [:data :error])))
```

Because the snapshot is data in the frame, time-travel and SSR do not need a
separate actor serialization story.

## Tags

XState:

```js
state.hasTag("busy")
```

re-frame2:

```clojure
:submitting {:tags #{:auth/busy}}

@(rf/subscribe [:rf.machine/has-tag? :auth.login/flow :auth/busy])
```

Tags are sets of keywords. Use namespaced tags for intent: `:auth/busy`,
`:mode/read-only`, `:ws/connected`.

## Delays and timeouts

`after` maps to `:after`:

```clojure
:loading
{:after {5000 :timeout}
 :on    {:loaded :ready}}
```

A named deadline uses `:timeout` and `:on-timeout`:

```clojure
:waiting
{:timeout    "PT5S"
 :on-timeout {:target :timed-out}}
```

Durations are integer milliseconds or ISO-8601 strings. Shorthand strings such as
`"5s"` are `:rf.error/machine-bad-timeout-duration` at registration.

## `invoke` becomes `:spawn`

State-bound child work is `:spawn`:

```clojure
:authenticating
{:spawn {:machine-id :auth/request
         :data       {:url "/api/login"}
         :on-done    (fn [{:keys [data result]}]
                       (assoc data :token result))
         :on-error   {:target :idle}}
 :on    {:cancel :idle}}
```

The child is destroyed automatically when the parent leaves `:authenticating`.
`:on-done` folds the child's result into the parent's `:data`. `:on-error` is a
transition.

`spawn` is the same lifecycle idea as `invoke`, renamed because a child actor
exists while the state is active. The parent registered with `reg-machine` is
the **singleton**. Each `:spawn` creates a **spawned** instance of a type.
The spec heading says "dynamic actors"; this guide uses those two words.

## Completion is event-shaped

A child reports a result by reaching a root-level final state:

```clojure
:done {:final? true
       :output-key :token}
```

The parent receives that value as `result` in `:on-done`.

There is no long-lived `snapshot.output` to read later. Completion happens,
reports, and the child is destroyed. A singleton that reaches `:final?` is
destroyed too — omit `:final?` on a resting leaf such as `:authed` or
`:locked-out`.

## Schemas

XState types are mostly compile-time. re-frame2 schemas are optional runtime
checks in development.

```clojure
:schemas {:data   [:map [:attempts :int]]
          :output :string}
```

A `:data` schema violation rolls back the transition before the bad snapshot
commits. Production builds can elide the checks.

## What stays quiet and what fails loud

| Situation | What happens |
|---|---|
| An event the current state does not handle | Quiet no-op, matching modern XState. Trace: `:rf.machine.event/unhandled-no-op`. |
| Broken definition — unresolved target, missing guard or action, invalid `:choice`, `"5s"` duration, … | Fail at registration. |

