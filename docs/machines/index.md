# Hierarchical state machines

Some state is not a number in app-db — it is **which named stage you are in**, and
which events may legally leave it. Login is idle, then submitting, then authed or
error or locked-out. A websocket is connecting, open, reconnecting, closed. Those
flows usually hide as enums and booleans scattered across handlers.

A **machine** makes that shape first-class: one data table of states and
transitions, driven by ordinary `dispatch`, read by ordinary `subscribe`, living in
the same [frame](../core/frames.md) as the rest of re-frame2. No second runtime.

## What you get

- **Hierarchical** states (nested flows), **parallel** regions, **history**,
  **spawned** child machines, guards, delayed transitions — near the power of
  [XState](coming-from-xstate.md), expressed as Clojure data.
- **Deep integration** — `reg-machine` is sugar over `reg-event`. Snapshots ride
  runtime-db; undo, Xray, SSR, and tests share the same pipeline.

## How to read this section

| Job | Page |
|---|---|
| Build a real machine once | [Tutorial: login flow](tutorial.md) |
| The flat model in full | [The model](concepts.md) |
| Labels for views ("busy?") | [Tags](tags.md) |
| Nested states | [Hierarchical states](hierarchical-states.md) |
| Orthogonal axes in one machine | [Parallel regions](parallel-states.md) |
| `:always`, `:after`, choice, timeout | [Automatic transitions](automatic-transitions.md) |
| Resume where you left off | [History](history.md) |
| Child machines / workers | [Actors](actors.md) |
| Xray + pure tests | [Inspecting and testing](inspecting-machines.md) |
| Runnable apps | [Examples](examples.md) |
| XState mapping | [Coming from XState](coming-from-xstate.md) |
| Term definitions | [Glossary](glossary.md) |

**Prerequisites.** The [Core introduction](../core/introduction.md) — events,
app-db, subscriptions, and effects. Machines plug into those; they do not replace
them.

## A machine in four lines

```clojure
(:require [re-frame.core :as rf]
          [re-frame.machines])   ;; opt-in artefact

(rf/defmachine door
  {:initial :closed
   :states  {:closed {:on {:open :open}}
             :open   {:on {:close :closed}}}})

(rf/reg-machine :door door)

(rf/dispatch [:door [:open]])
@(rf/subscribe [:rf/machine :door])
;; => {:state :open :data {}}
```

The tutorial turns that idea into a login with guards, actions, HTTP, a view, and a
test. The model page explains every slot.

## When *not* to use a machine

| Situation | Prefer |
|---|---|
| A counter, list, or form field | app-db + events |
| Two stages (`:loading?` boolean) | a keyword or flag |
| Server fetch / cache / invalidate | [resources](../resources/concepts.md) |
| A fixed sequence of operations | chained events / effects |

Reach for a machine when **named stages and legal transitions** are the load-bearing
concept — not when the load-bearing concept is a value or a network cache.
