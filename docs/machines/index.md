# State machines

<a id="theyre-everywhere"></a>

A single-page app is full of finite state machines. Most of them are never
written down.

The session is anonymous, submitting, authed, or locked out. An HTTP request
is idle, in flight, succeeded, or failed. A dropdown is closed, open, or
disabled. Same shape at every scale: a handful of named stages, and a smaller
set of events that may leave each one.

What you usually write instead is a pile of booleans and a `cond` in every
handler — `:loading?`, `:error?`, `:open?`, `:disabled?`. The machine is still
there. It is just poorly specified. Each handler re-decides what is legal. A
new stage means another `if`. Two flags combine into a state nobody named
(`:loading?` true and `:error?` true). An event that should be impossible
gets through.

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

A flat list is enough for one process. Some stages are really a *cluster*.
`:connecting`, `:authenticating`, and `:connected` all share one live socket.
Every authenticated screen should honour `:logout` the same way. Checkout is
a sub-flow with its own start and end, inside a larger shopping flow.

A **hierarchical** machine lets a state contain child states. The parent holds
what the children share. The children hold what differs. Common transitions
live once, on the parent; a child can override or block them.

```clojure
:authenticated
{:initial :dashboard
 :on      {:logout :unauthenticated}   ;; every child inherits this
 :states  {:dashboard {}
           :settings  {}
           :cart      {:initial :browsing
                       :states  {:browsing {}
                                 :paying   {}}}}}
```

Entering `:authenticated` lands on `:dashboard`. `:logout` works from any
child. Moving from `:browsing` to `:paying` does not leave `:authenticated`.
[Hierarchical states](hierarchical-states.md) is the grammar.

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

(rf/dispatch [:auth.login/flow [:auth.login/submit]])
@(rf/subscribe [:rf/machine :auth.login/flow])
;; => {:state :submitting :data {}}
```

Look at the `dispatch` line. You `dispatch`. You do not `send`.

The outer vector is a re-frame2 **event**. `:auth.login/flow` is a normal
event id — the same slot as `:todo/add`. You registered that id with
`reg-machine`, so the handler that runs is the table.

The second element is a **trigger**. In a statechart, a trigger is the thing
that can fire a transition. Here it is another vector: `[:auth.login/submit]`.
The table matches the first keyword against the current state's `:on` map.
Anything after that keyword is payload.

Not every trigger comes from `dispatch`. A timer expiry is a trigger, and so
is an eventless `:always` step — [Automatic transitions](automatic-transitions.md).

`reg-machine` is sugar over `reg-event`: same registry, same `dispatch`. Read
the live value with an ordinary `subscribe`. That value — the snapshot — lives
in [runtime-db](../core/glossary.md#runtime-db), the framework half of the
frame, so undo, Xray, SSR, and tests see it the way they see any other event's
result.

`:auth.login/flow` is a **singleton**: one registered id, one live instance
per frame. The snapshot sits in that frame's runtime-db, so a second frame
running the same app runs its own login machine, independently.
The snapshot is `nil` until the first event. A **spawned** actor is a second
live instance of a type, created at run time with an allocated id. Login is a
singleton. An in-flight request protocol is often spawned. The spec heading
says "dynamic actors" for the second kind; that is an adjective, not a third
kind. This guide says **singleton** and **spawned**.
[Actors](actors.md) is the full treatment.

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
