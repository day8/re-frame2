# State machines

<a id="theyre-everywhere"></a>

Some state is not a number in app-db — it is **which named stage you are in**,
and what may legally move it. Login is idle, then submitting, then authed or
error or locked-out. A websocket is connecting, open, reconnecting, closed.
Those flows usually hide as enums and booleans scattered across handlers.

<a id="first-class-support"></a>

A **machine** makes that shape explicit: one data table of states and
transitions, driven by ordinary `dispatch`, read by ordinary `subscribe`,
living in the same [frame](../core/frames.md) as the rest of re-frame2.

**Prerequisites.** The [Core introduction](../core/introduction.md) — events,
app-db, subscriptions, effects. Machines plug into those; they do not replace
them. This page says when a machine fits and shows the smallest working form.

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

(rf/dispatch [:auth.login/flow [:auth.login/submit]])
@(rf/subscribe [:rf/machine :auth.login/flow])
;; => {:state :submitting :data {}}
```

The outer id routes to the machine. The inner vector is the event the table
handles.

<a id="deeply-integrated"></a>

`reg-machine` is sugar over `reg-event`. There is one event system — you
`dispatch`, you do not `send` — and one state model: the snapshot rides
[runtime-db](../core/glossary.md#runtime-db), so undo, Xray, SSR, and tests
share the same pipeline as the rest of the app.

A keyword in app-db is enough for two stages. Use a machine when the table is
the source of truth — which events are legal where, and what happens next.

## When *not* to use a machine

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

Already using XState? [Coming from XState](coming-from-xstate.md) is the
translation.
