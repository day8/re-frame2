# State machines

<a id="theyre-everywhere"></a>

Login moves through idle, submitting, authenticated and locked-out states.
Each stage accepts different events. A machine puts those rules in one
transition table, so every handler does not have to repeat them.

This guide teaches how to build, test and extend that login flow. Use a
machine when the legal transitions between named stages are the problem you
need to model.

```clojure
(ns app.login
  (:require [re-frame.core :as rf]
            [re-frame.machines]))

(rf/defmachine login-flow
  {:initial :idle
   :states
   {:idle       {:on {:auth.login/submit :submitting}}
    :submitting {:on {:auth.login/success :authed
                      :auth.login/failure :error-shown}}
    :error-shown {:on {:auth.login/submit :submitting}}
    :authed      {}}})

(rf/reg-machine :auth.login/flow login-flow)
```

A submit is handled only in `:idle` or `:error-shown`. While `:submitting`,
another submit leaves the snapshot unchanged. Guards can reject an invalid
submit, and actions can describe a request or update the machine's private
`:data`.

<a id="native-to-re-frame2"></a>
<a id="first-class-support"></a>
<a id="deeply-integrated"></a>

A registered machine is an event handler. Dispatch an ordinary event with
the machine id and an inner **trigger** vector:

```clojure
(rf/dispatch [:auth.login/flow [:auth.login/submit]])
@(rf/subscribe [:rf/machine :auth.login/flow])
```

The live snapshot belongs to the current frame. It stores the active state
and private data in [runtime-db](../core/glossary.md#runtime-db). Views read
it through subscriptions; actions send application changes through ordinary
events and effects.

<a id="why-nest-states"></a>

As the session grows, [hierarchical states](hierarchical-states.md) can share
logout behavior across its signed-in screens. [Parallel regions](parallel-states.md)
keep form validity and request progress active independently.

## When not to use a machine

<a id="when-not-to-use-a-machine"></a>

Keep ordinary values in app-db. A boolean or keyword is enough while the
transitions are easy to see in a few event handlers. A table becomes useful
when those checks spread or invalid combinations become possible.

| Situation | Prefer |
| --- | --- |
| A counter, list, or form field | app-db and events |
| A single loading flag | a boolean or keyword |
| Server fetch, cache and invalidation | [resources](../resources/concepts.md) |
| A fixed sequence of operations | chained events and effects |

[Where should this value live?](../core/where-state-lives.md) compares the
state choices across re-frame2.
