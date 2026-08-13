# Machines glossary

One term, short definition, tiny code when the spelling matters. *See* points
at the teaching page.

## Core terms

### **machine**

A statechart registered as an event handler. It models a feature lifecycle as
named states and transitions.

```clojure
(rf/defmachine login-flow {…})
(rf/reg-machine :auth.login/flow login-flow)
```

See [The model](concepts.md).

### **transition table**

The map that defines a machine: `:initial`, `:data`, optional `:guards`,
optional `:actions`, optional `:schemas`, and `:states` or `:regions`.

See [The idea](concepts.md#the-idea).

### **snapshot**

The machine's live value.

```clojure
{:state :submitting
 :data  {:attempts 1}
 :tags  #{:auth/busy}}
```

It lives in [runtime-db](../core/glossary.md#runtime-db). Read it with
`[:rf/machine id]`:

```clojure
@(rf/subscribe [:rf/machine :auth.login/flow])
```

`nil` until the first event. `:state` is a keyword, a path vector, or a
region map.

See [The snapshot](concepts.md#the-snapshot).

### **:data**

A machine's private working memory. Guards and actions read it. Actions update
it by returning `{:data …}`. Merged, not replaced — `{:data {:error nil}}` sets
that key to nil.

See [The idea](concepts.md#the-idea).

### **state**

One named mode of a machine, such as `:idle`, `:submitting`, or `:authed`.

### **transition**

A move from one state to another, usually in response to an event under a
state's `:on` map.

### **guard**

A predicate that gates a transition.

```clojure
:guard :form-valid?
```

Defined in `:guards`, or written inline for a one-liner. A three-attempt
policy is `(< (:attempts data) 2)` — the guard sees the count *before* the
action increments it.

See [Guards](concepts.md#guards).

### **action**

A function that returns `{:data … :fx …}`. It may update the machine's private
data or describe effects. It never writes app-db (`:rf.error/machine-action-wrote-db`).

See [Actions](concepts.md#actions).

### **action effect map**

The return value from an action.

```clojure
{:data {:error nil}
 :fx   [[:dispatch [:session/clear]]]}
```

`:data` is merged into the machine data. `:fx` is the ordinary effects vector.

See [The effect map](concepts.md#the-effect-map-data-fx).

## State structure

### **compound state**

A state with nested `:states` and its own `:initial`. The snapshot's `:state`
becomes a vector path, such as `[:authenticated :cart :paying]`.

See [Hierarchical states](hierarchical-states.md).

### **parallel machine**

A machine with `:type :parallel` and `:regions`. All regions are active at the
same time. The snapshot's `:state` is a map of region name to region state.

See [Parallel regions](parallel-states.md).

### **region**

One orthogonal axis of a parallel machine. Each region has its own `:initial`
and `:states`, but all regions share one machine `:data`.

See [Parallel regions](parallel-states.md).

### **final state**

A leaf marked `:final? true`. At the root, it ends and destroys the machine.
Inside a compound state, it marks that sub-flow as done. A resting end-screen
(`:authed`) omits `:final?`.

See [Final states](concepts.md#final-states);
[nested finals](hierarchical-states.md#when-a-sub-flow-finishes-nested-final-states).

### **history state**

A `:type :history` pseudo-state inside a compound state. Target it to re-enter
the compound where it last exited.

See [History states](history.md).

### **state tag**

A semantic label on a state.

```clojure
:tags #{:auth/busy}
```

Read it with `[:rf.machine/has-tag? id tag]`:

```clojure
@(rf/subscribe [:rf.machine/has-tag? :auth.login/flow :auth/busy])
```

See [Tags](tags.md).

## Transition forms

### **candidate vector**

A first-match-wins list of transitions.

```clojure
:on {:auth.login/failure [{:guard :under-retry-limit :target :error-shown
                           :action :record-error}
                          {:target :locked-out :action :record-error}]}
```

### **self-transition**

A transition that stays in the same state.

Targetless self-transitions run an action without exit/entry. `:reenter? true`
forces exit and re-entry.

See [Self-transitions and wildcards](concepts.md#self-transitions-and-wildcards).

### **wildcard transition**

An `:on` key that handles a family of events:

```clojure
:mouse/*
:*
```

Resolution is exact id, namespace wildcard, then total wildcard.

See [Self-transitions and wildcards](concepts.md#self-transitions-and-wildcards).

### **forbidden transition**

A present no-op transition, such as `{:on {:logout {}}}` or
`{:on {:logout nil}}`. It consumes the event and prevents parent fallthrough.

See [Self-transitions and wildcards](concepts.md#self-transitions-and-wildcards).

### **:always**

An eventless transition checked after entry and after transitions into the
state.

```clojure
:always [{:guard :done? :target :complete}]
```

See [Automatic transitions](automatic-transitions.md).

### **choice state**

A transient decision node.

```clojure
{:type :choice
 :choice [{:guard :valid? :target :accepted}
          {:target :rejected}]}
```

See [Automatic transitions](automatic-transitions.md).

### **:after**

A delayed transition. Entering the state arms the timer; leaving cancels it.

```clojure
:after {5000 :timed-out}
```

See [Automatic transitions](automatic-transitions.md).

### **timeout**

A named deadline using `:timeout` and `:on-timeout`.

```clojure
{:timeout "PT5S"
 :on-timeout {:target :timed-out}}
```

See [Automatic transitions](automatic-transitions.md).

## Actors and composition

### **actor**

A live machine instance. A singleton machine and a spawned child are both
actors. Liveness is the presence of a snapshot in runtime-db.

See [Actors](actors.md).

### **spawn**

A state-node key that starts a child actor on entry and destroys it on exit.

```clojure
:spawn {:machine-id :worker}
```

See [Actors](actors.md).

### **spawn-all**

A state-node key that starts several children and joins on their completion.
`:join` is `:all` or `:any`.

See [Fan-out and join](actors.md#fan-out-and-join-with-spawn-all).

### **system-id**

A stable role name bound to a spawned actor. Use it to message a child without
threading its generated id.

See [Actors](actors.md).

### **:on-done**

A callback or transition that runs when a child or compound sub-flow completes.

See [When a child finishes](actors.md#when-a-child-finishes).

### **:output-key**

A key on a final state naming which value from `:data` is reported to the
parent.

See [When a child finishes](actors.md#when-a-child-finishes).

### **:raise**

A machine-only effect that loops an event back into the same machine before
the macrostep commits.

```clojure
{:fx [[:raise [:check-complete]]]}
```

See [When the table grows](concepts.md#when-the-table-grows).

### **:internal-events**

A top-level set of event ids that may be raised internally but are refused
when dispatched from outside the machine.

See [When the table grows](concepts.md#when-the-table-grows).

## Runtime terms

### **run-to-completion**

The guarantee that one machine event settles all `:always` transitions and
raised events before the next external event is observed.

### **microstep**

One internal step inside a macrostep: an `:always` transition or a raised
event.

### **macrostep**

The full processing of one machine event, including all microsteps, ending in
one committed snapshot.

### **commit**

The single runtime-db write that stores the settled snapshot.

### **LCA / LCCA**

Least common compound ancestor. The deepest state that remains active while
moving from one hierarchical path to another. Exit actions run up to it; entry
actions run down from it.

See [Entry/exit cascading](hierarchical-states.md#entryexit-cascading-along-the-lca).

### **runtime-db**

The framework-owned state partition where machine snapshots live. It is
separate from app-db.

See [runtime-db](../core/glossary.md#runtime-db).

### **unhandled event**

An event the current machine configuration does not handle. It is a no-op, not
an exception.

### **fail loud**

The design posture for invalid definitions: unresolved targets, missing
guards/actions, bad timeout shapes, invalid final states, and similar mistakes
fail at registration rather than later.

See [fail loud](../core/glossary.md#fail-loud-not-silent).
