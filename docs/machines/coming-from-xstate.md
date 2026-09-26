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
completion. v5 helper creators such as `assign`, `sendTo`, `raise`, and
`enqueueActions` map onto the data-first forms below; v6's `setup()` /
`createMachine({ guards, actions })` registries map onto the machine-local
`:guards` / `:actions` maps.

## Mapping

| XState idea | re-frame2 |
|---|---|
| `createMachine(...)` / `setup().createMachine()` | `(rf/defmachine …)` then `(rf/reg-machine id …)`, or an inline `reg-machine` literal |
| `context` | `:data` |
| `state.value` | snapshot `:state` |
| `state.context` | snapshot `:data` |
| `actor.getSnapshot()` | `@(rf/subscribe [:rf/machine id])` |
| `actor.getPersistedSnapshot()` | `(rf/frame-state-value frame)` — the machines, spawned children and spawn registry are its `[:rf.db/runtime :rf.runtime/machines]` subtree; see [Persist and restore](#persist-and-restore) |
| `createActor(machine, { snapshot })` | `(rf/dispatch-sync [:rf/install-frame-state saved] {:frame f})` at boot — no entry replay, children restored, live `:after` timers re-armed; see [Persist and restore](#persist-and-restore) |
| `actor.send(event)` | `(rf/dispatch [machine-id [event …]])` |
| `createActor(machine).start()` | nothing to create: the first event, or `(rf/dispatch [machine-id [:rf.machine/start]])`, starts a registered machine |
| `actor.stop()` | `:fx [[:rf.machine/destroy machine-id]]` — runs the active states' `:exit` actions before teardown, where `stop()` runs none, and destroys the children its `:spawn` / `:spawn-all` states track, as `stop()` stops children |
| `snapshot.status` / `snapshot.output` after completion | the `:rf.machine/done` trace carries `:output`; the snapshot is gone, so `[:rf/machine id]` reads `nil` for a finished, destroyed or never-started machine alike |
| `actor.subscribe({ complete })` | the `:rf.machine/done` trace, and `:rf.machine/destroyed` with its `:reason` |
| sending to a done or stopped actor (a dead letter) | a singleton is re-born from `:initial` and handles the event; a destroyed spawned actor answers `:rf.error/no-such-handler` |
| an action throws (the actor's status becomes `'error'`) | the macrostep rolls back, `:rf.error/machine-action-exception` is emitted, and the machine keeps handling events |
| `states` | `:states` |
| `initial` | `:initial` |
| nested states | compound states with `:initial` + `:states` |
| root `entry` / `exit` / `tags` / `invoke` | root `:entry` (once at birth) / `:exit` (once at teardown, including destroy) / `:tags`; a root `invoke` is the root `:spawn`, one child spawned at birth after the root `:entry` and destroyed at teardown after the root `:exit`. A root `always` is refused at registration — route `:initial` into a state whose `:always` fires |
| `type: "parallel"` | `:type :parallel` + `:regions` |
| `type: "history"` | `:type :history` (`:default-target` is optional and falls back to the compound's `:initial`; v6 requires a history `target`) |
| `type: "final"` | `:final? true` (auto-destroys, and a later event restarts it from `:initial`; omit it on a resting leaf) |
| `reenter: true` | `:reenter? true` |
| `tags` | `:tags #{…}` |
| `state.hasTag(tag)` | `@(rf/subscribe [:rf.machine/has-tag? id tag])` |
| a condition inside the transition function (v5 `guard`) | `:guard` named in `:guards` |
| the transition function's `{ context }` return + `enq(...)` (v5 `actions` / `assign`) | `:action` returning `{:data … :fx …}` |
| `always` | `:always` |
| `after` | `:after` |
| timeout / onTimeout | `:timeout` + `:on-timeout` |
| choice state | `:type :choice` + a declarative `:choice` vector |
| `invoke` | `:spawn` |
| `invoke` `onDone` | `:spawn`'s `:on-done` transition |
| `invoke` `onError` | `:spawn`'s `:on-error` transition |
| multiple invokes / fan-out | `:spawn-all` |
| `enq.raise` (v5 `raise`) | `:fx [[:raise [:tick]]]` |
| `enq.sendTo` (v5 `sendTo`) | `:fx [[:dispatch [other-id [:their/event]]]]` — the id you hold IS the address |
| `output` | `:output-key` on a final state |
| `schemas.internalEvents` (a map; the top-level `internalEvents` array is deprecated) | `:internal-events #{…}` |
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
                                :action :record-error}]}}

    :error-shown {:on {:auth.login/submit :submitting}}
    :locked-out  {}}})

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

An XState v5 action performs work or uses `assign` to update context; in v6 an
entry/exit function returns a `{ context }` patch and queues effects through
`enq`. A re-frame2 action returns a value:

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
(rf/reg-sub :auth.login/error {:inputs [[:rf/machine :auth.login/flow]]}
  (fn [[snap] _]
    (get-in snap [:data :error])))
```

Because the snapshot is data in the frame, time-travel and SSR do not need a
separate actor serialization story.

## Persist and restore

XState persists with `actor.getPersistedSnapshot()` and restores with
`createActor(machine, { snapshot })`. In re-frame2 the machines are part of the
frame-state, so you read the frame, keep what you want, and install it back with
one event:

```clojure
(ns app.persist
  (:require [cljs.reader]
            [re-frame.core :as rf]))

;; Save: any time. The app decides what to keep.
(defn save! [frame]
  (let [{app :rf.db/app runtime :rf.db/runtime} (rf/frame-state-value frame)]
    (.setItem js/localStorage "checkout"
              (pr-str {:rf.db/app     app
                       :rf.db/runtime (select-keys runtime [:rf.runtime/machines])}))))

;; Load: at boot, before the app's own boot events.
(defn load! [frame]
  (when-let [stored (.getItem js/localStorage "checkout")]
    (when-let [saved (try (cljs.reader/read-string stored)
                          (catch :default _ nil))]
      (rf/dispatch-sync [:rf/install-frame-state saved] {:frame frame}))))
```

`:rf/install-frame-state` replaces app-db with `:rf.db/app` and replaces only the
runtime-db subtrees you saved, so the route slice and anything else the frame
booted with stay as they are. As with XState's `snapshot` option, entry actions
are not re-run and spawned children come back with their state. Each restored
machine's live `:after` timer is armed again for its full delay, and its
`:sensitive` / `:large` declarations are re-derived from its machine definition,
so you save no classification alongside the snapshots and a restored secret
redacts exactly as the live one did.

The app owns the rest:

- **Storage and selection.** Where the string goes and which subtrees it keeps
  are yours. Leave the resource runtime (`:rf.runtime/resources`,
  `:rf.runtime/work-ledger`, `:rf.runtime/mutations`) out: the install refuses
  it, because a resource cache is not persisted. Resources refetch.
- **Versioning and migration.** A saved snapshot whose `:state` a later deploy
  removed restarts from `:initial` on its first event, with
  `:rf.error/machine-state-not-in-definition`; a changed
  `:meta :rf/snapshot-version` does the same, with
  `:rf.error/machine-snapshot-version-mismatch`. Migrating old data forward is
  the app's job, as it is in XState.
- **Reading it back.** `read-string` on stored text is your code, so guard it,
  as `load!` does. A payload that is not a map, or that carries a non-map
  partition, is refused with `:rf.error/handler-exception` and changes
  nothing.

The full contract is [Spec 002 §Installing a persisted frame-state](../../spec/002-Frames.md#installing-a-persisted-frame-state).

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
         :on-done    {:target :authenticated
                      :action (fn [{data :data ev :event}]
                                {:data (assoc data :token (:result (nth ev 2)))})}
         :on-error   {:target :idle}}
 :on    {:cancel :idle}}
```

The child is destroyed automatically when the parent leaves `:authenticating`
or is destroyed.
`:on-done` and `:on-error` are transitions, like XState's `onDone` and
`onError`: each resolves at the spawning state's level, so `:authenticated` and
`:idle` are its siblings. The child's result rides the transition's event as
`(:result (nth ev 2))`. A fn `:on-done` —
`(fn [{:keys [data result]}] (assoc data :token result))` — instead folds the
result into the parent's `:data` without moving the parent.

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

The parent receives that value as `(:result (nth ev 2))` in an `:on-done`
transition, or as `result` in an `:on-done` fold.

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
| An XState spelling — `invoke`, `cond`, `entry: [a, b]` | Fail at registration (`:rf.error/machine-unknown-node-key`, `:rf.error/machine-bad-action-form`); the message says what to write instead. |

