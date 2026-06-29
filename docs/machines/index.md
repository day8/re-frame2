# Machines

Finite state machines are hiding in plain sight, everywhere in your app. Your app's **boot** is one — load config, hydrate, check the session, go ready. So is **auth**: a user is logged out, or logged in; and if logged in, an admin or not. So is a single **HTTP request** (`idle → loading → loaded | failed`), a **websocket** (`connecting → open → reconnecting → closed`), and even a humble **dropdown** (`closed → open → selecting`). Keep looking and you'll see them at every scale. It's finite state machines all the way down.

Model one of those lifecycles with scattered boolean flags — `:loading?`, `:submitting?`, `:error?` — and it drifts out of sync and sprouts impossible states ("submitting *and* error" at once). A **machine** makes the lifecycle explicit: named states, named transitions, exactly one state at a time. The illegal combinations simply can't be represented.

re-frame2 has first-class, **hierarchical** state machines — nested states, parallel regions, spawned children, guards, delayed transitions: the full XState v6 feature set. And they're wired right into the re-frame2 substrate, **not bolted on as a side-car**. A machine *is* an event handler. Its live value is a [snapshot](glossary.md#snapshot) you read with an ordinary subscription and move with an ordinary dispatched event — same cascade, same `app-db`, same Xray, same tests. There's no second runtime to learn.

```clojure
(rf/reg-machine :auth/login
  {:initial :idle
   :states  {:idle       {:on {:submit :submitting}}
             :submitting {:on {:ok   :authed
                               :fail :error}}
             :authed     {}
             :error      {:on {:submit :submitting}}}})

@(rf/subscribe [:rf/machine :auth/login])   ;; => {:state :idle :data {}}
```

Dispatch an event, the snapshot moves, and the views reading it follow.

New to machines? The [tutorial](tutorial.md) builds one step by step. To take in the whole model at once, read [Concepts](concepts.md).
