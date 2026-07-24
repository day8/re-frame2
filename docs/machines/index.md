# Hierarchical state machines

<a id="theyre-everywhere"></a>

Some state is not a number in app-db — it is **which named stage you are in**, and
which events may legally leave it. Login is idle, then submitting, then authed or
error or locked-out. A websocket is connecting, open, reconnecting, closed. Those
flows usually hide as enums and booleans scattered across handlers.

<a id="first-class-support"></a>

A **machine** makes that shape explicit: one data table of states and transitions,
driven by ordinary `dispatch`, read by ordinary `subscribe`, living in the same
[frame](../core/frames.md) as the rest of re-frame2. No second runtime.

## Start here

1. **[Tutorial](tutorial.md)** — build a login machine in six steps (guard, action,
   HTTP, view, pure test).
2. **[The model](concepts.md)** — the flat grammar those steps rely on: register,
   snapshot, encapsulation, self-transitions, finals.
3. Open a growth page when a need appears (tags for views, hierarchy for nested
   flows, actors for workers, …). The left nav lists them in dependency order.

**Prerequisites.** The [Core introduction](../core/introduction.md) — events, app-db,
subscriptions, effects. Machines plug into those; they do not replace them.

Optional: [examples](examples.md), [XState mapping](coming-from-xstate.md),
[glossary](glossary.md).

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

<a id="deeply-integrated"></a>

`reg-machine` is sugar over `reg-event`. The snapshot rides runtime-db — undo, Xray,
SSR, and tests share the same pipeline as the rest of the app.

## When *not* to use a machine

| Situation | Prefer |
|---|---|
| A counter, list, or form field | app-db + events |
| Two stages (`:loading?` boolean) | a keyword or flag |
| Server fetch / cache / invalidate | [resources](../resources/concepts.md) |
| A fixed sequence of operations | chained events / effects |

Reach for a machine when **named stages and legal transitions** are the thing you
are modelling — not when the thing is a value or a network cache.
