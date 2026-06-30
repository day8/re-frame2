# Hierarchical State Machines

## They are everywhere

Finite state machines (FSMs) hide in plain sight all over your app:

- Your app's boot — load config, hydrate, check the session, then go ready.
- Auth — logged out, or logged in; and if logged in, an admin or not.
- A single HTTP request — `idle → loading → loaded | failed`.
- A websocket — `connecting → open → reconnecting → closed`.
- A humble dropdown — `closed → open → selecting`.

Keep looking and you'll see them at every scale — usually tangled up in a cluster of enums and boolean flags. Formally modelling them makes your code more explicit, robust, and easier to understand.

## First-class support

re-frame2 has first-class, **hierarchical** state machines — nested states, parallel regions, spawned children, guards, and delayed transitions. They cover much the same ground as the 500-pound gorilla in this space, [XState v6](coming-from-xstate.md) — a wonderful library from which we have learned a lot.

## Deeply integrated

They're wired right into the **core** of re-frame2 — not bolted on as a side-car, and infused with the re-frame2 ethos. A machine *is* an event handler: its state lives in the [frame](../core/concepts/frames.md) alongside your `app-db`, it moves on the same cascade, and the same Xray and tests work on it — no second runtime to learn.

They're **expressed** as a data-oriented DSL — the whole machine is just a value, so you can `def` it. Read `:states` as a transition table: in each state, `:on` maps an incoming event to the next state.

```clojure
(def auth-login-machine
  {:initial :idle
   :states  {:idle       {:on {:submit :submitting}}   ;; :submit → :submitting
             :submitting {:on {:ok   :authed            ;; :ok     → :authed
                               :fail :error}}           ;; :fail   → :error
             :authed     {}
             :error      {:on {:submit :submitting}}}})
```

They're **integrated** as an event handler — `reg-machine` is just a machine-specific `reg-event`:

```clojure
(rf/reg-machine :auth-login auth-login-machine)
```

They're **triggered** by normal events, dispatched the normal way, so you drive a machine straight from an ordinary handler. Dispatching `[:auth-login [:submit …]]` fires the machine's `:submit` arrow:

```clojure
(rf/reg-event :login/submit
  (fn [_ [_ credentials]]
    {:fx [[:dispatch [:auth-login [:submit credentials]]]]}))

(rf/dispatch [:login/submit credentials])   ;; fire it the normal way
```

And they're **read** through an ordinary subscription — you get back a [snapshot](glossary.md#snapshot), `{:state … :data …}`:

```clojure
@(rf/sub-machine :auth-login)   ;; => {:state :submitting :data {}}
```

The snapshot moves, and the views that read it follow.
