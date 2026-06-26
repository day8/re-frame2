# Machines glossary

re-frame2's optional state-machine capability — modelling a feature's lifecycle as an explicit statechart rather than a scatter of boolean flags. The terms below all live within a [machine](#machine); see [the Machines guide](concepts.md) for the full picture.

### **machine**

A statechart-capable state machine, registered as an [event handler](../guide/glossary.md#event-handler) with `reg-machine`. It models a feature's lifecycle as explicit [states](#state) and [transitions](#transition) — driven by dispatched [events](../guide/glossary.md#event), with [guards](#guard), [actions](#action), timeouts, and child machines (see [spawn](#spawn)) — instead of a scatter of boolean flags in [app-db](../guide/glossary.md#app-db).

Its live value is a [snapshot](#snapshot) (the current state plus its `:data`), held in [runtime-db](../guide/glossary.md#runtime-db) and read like any other derived state.

```clojure
(rf/reg-machine :auth.login/flow login-flow)
```

Related: [Machines](concepts.md).

### **snapshot**

A [machine](#machine)'s live value at any moment — which state it's in, plus its `:data`. It lives in [runtime-db](../guide/glossary.md#runtime-db), and you read it through a [subscription](../guide/glossary.md#subscription) addressed by the machine's id.

```clojure
@(rf/subscribe [:rf/machine :auth.login/flow])   ;; {:state :authed :data {...}}
```

Related: [Machines](concepts.md).

### **state**

One of a [machine](#machine)'s fixed, named, mutually-exclusive modes — `:idle`, `:submitting`, `:authed`. A machine is always in exactly one (or, with parallel regions, one per region).

### **transition**

The move from one [state](#state) to another in response to a dispatched [event](../guide/glossary.md#event) — optionally gated by a [guard](#guard) and running an [action](#action) on the way. In the transition table it's an entry under a state's `:on` map. Transitions can also be *eventless* (`:always`, taken on entry when its guard passes) or *timed* (`:after`, taken after a delay that auto-cancels when the state exits).

### **guard**

A pure yes/no predicate that decides whether a [transition](#transition) fires. Referenced by name from the table and defined once in the machine's `:guards`, so a visualiser can read the condition right off the arrow.

### **action**

The side work a [transition](#transition) performs: it returns the same `{:data … :fx …}` shape an [event handler](../guide/glossary.md#event-handler) returns — updating the machine's own `:data` or firing [effects](../guide/glossary.md#effect) — and never writes [app-db](../guide/glossary.md#app-db) directly. Defined once in the machine's `:actions`, named from the arrow.

### **state tag**

A label like `:auth/busy` attached to several [machine](#machine) [states](#state); the active state's tags ride on the [snapshot](#snapshot), so a [view](../guide/glossary.md#view) can ask "is it busy?" (`machine-has-tag?`) instead of enumerating exact state names — *ask, don't tell*. Add a sixth busy state and no view changes.

### **spawn**

A declarative key that starts a *child* machine on entering a [state](#state) and tears it down on leaving; the child reports a result back through `:on-done`. (`:spawn-all` starts several in parallel and joins on completion — re-frame2's spelling of XState's `invoke`.)
