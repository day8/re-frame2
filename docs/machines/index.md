# State machines

<a id="theyre-everywhere"></a>

Login moves through idle, submitting, authenticated and locked-out states.
Each state accepts different events. A state machine puts those rules in one
place, so every handler does not have to repeat them.

A **machine** is that process written as one table: the stages, and which
triggers may leave them.

```clojure
{:initial :closed
 :states  {:closed   {:on {:open :open}}
           :open     {:on {:close :closed
                           :pick  :closed}}
           :disabled {}}}
```

From `:closed`, only `:open` moves you. From `:disabled`, nothing does. You
cannot open a disabled dropdown by forgetting a branch — the branch is not
there.

The same table shape covers a login flow or a request. Only the names change.

Machines plug into [events](../core/introduction.md), app-db, subscriptions,
and effects. They do not replace them.

## Why nest states

Use [hierarchical states](hierarchical-states.md) when several stages share
behavior: every authenticated screen handles logout, or one socket stays open
while a connection moves from connecting to authenticating to connected. Put
the shared transitions and lifecycle work on their parent state.

Use [parallel regions](parallel-states.md) when one feature has independent
axes, such as form validity and request progress, that are active together.

## Native to re-frame2

<a id="first-class-support"></a>
<a id="deeply-integrated"></a>

re-frame2 does not add a second runtime. Other chart libraries give you an
actor: you start it and `send` it messages. re-frame2 already has that job —
events go on one queue via `dispatch`. So a machine is not a new object. It is
an event handler. The id you register is the event id you dispatch to.

```clojure
(:require [re-frame.core :as rf]
          [re-frame.machines])   ;; opt-in; forget this → :rf.error/machines-artefact-missing

(rf/defmachine login-flow
  {:initial :idle
   :states  {:idle        {:on {:auth.login/submit :submitting}}
             :submitting  {:on {:auth.login/success :authed
                                :auth.login/failure :error-shown}}
             :error-shown {:on {:auth.login/dismiss :idle}}
             :authed      {}}})

(rf/reg-machine :auth.login/flow login-flow)

(rf/dispatch-sync [:auth.login/flow [:auth.login/submit]])
@(rf/subscribe [:rf/machine :auth.login/flow])
;; => {:state :submitting :data {}}
```

The outer vector is an ordinary event whose id is the machine id; the inner
vector is the **trigger** the table matches. The [first machine](tutorial.md)
builds this flow step by step.

`reg-machine` is sugar over `reg-event`: same registry, same `dispatch`. Read
the live value with an ordinary `subscribe`. That value — the snapshot — lives
in [runtime-db](../core/glossary.md#runtime-db), the framework half of the
frame, so undo, Xray, SSR, and tests see it the way they see any other event's
result.

Already using XState? [Coming from XState](coming-from-xstate.md) is the
translation.

## When *not* to use a machine

<a id="when-not-to-use-a-machine"></a>

A two-state flag is already a tiny machine. Leave it as a boolean. Write a
table when the stages multiply, or when illegal combinations start appearing.

| Situation | Prefer |
|---|---|
| A counter, list, or form field | app-db + events |
| Two stages (`:loading?` boolean) | a keyword or flag |
| Server fetch / cache / invalidate | [resources](../resources/concepts.md) |
| A fixed sequence of operations | chained events / effects |

Reach for a machine when **named stages and legal transitions** are the thing
you are modelling — not when the thing is a value or a network cache.
[Where should this value live?](../core/where-state-lives.md) has the full
decision table.
