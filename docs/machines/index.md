# Hierarchical State Machines

## They're everywhere

Finite state machines (FSMs) are hiding in plain sight all over your app:

- Your app's boot process — load config, check the session, get some codes, then go ready.
- Auth — user logged out, or logged in; and if logged in, an admin or not. And do we have a JWT yet?
- A single HTTP request — `idle → loading → timeout → retry → loaded | failed`.
- A websocket — `connecting → open → reconnecting → closed`.
- A humble dropdown — `disabled | enabled → closed → open → selecting`.

Keep looking and you'll find them at every scale — usually tangled up in a cluster of enums and boolean flags. 

But if you have a way of modelling them formally, your code becomes more explicit, robust, and easier to understand.

## First-class support

re-frame2 has first-class, **hierarchical** state machines — including nested states, parallel regions, spawned children, guards, and delayed transitions. 

The re-frame2 implementation seeks near-parity with the 500-pound gorilla in this space, [XState v6](coming-from-xstate.md) — a wonderful library which has taught me a lot.

## Deeply integrated

The machines implementation is wired into the **core** of re-frame2 — not bolted on as a side-car. And it is infused with the re-frame2 ethos. 

A machine is **expressed** via a data-oriented DSL — state, transitions, guards, etc. The whole machine is just a value. You bind that value with `defmachine` — a drop-in for `def` that also captures per-element source so tooling like Xray can jump from a live snapshot back to the guard/action/state definition — like this.

```clojure
(rf/defmachine auth-login-machine
  {:initial :idle
   :states  {:idle       {:on {:submit :submitting}}   ;; :submit → :submitting
             :submitting {:on {:ok   :authed            ;; :ok     → :authed
                               :fail :error}}           ;; :fail   → :error
             :authed     {}
             :error      {:on {:submit :submitting}}}})
```

And a singleton machine (based on this specification value) can be defined as an event handler using `reg-machine` (which is just a machine-specific `reg-event`), like this:

```clojure
(rf/reg-machine :auth-login auth-login-machine)
```

The state for all machines lives in a [frame](../core/frames.md) alongside your `app-db`. It moves on the same pipeline, can be "undone" the same way, debugged with the same tools (like Xray), and tested the same way — **there's no second runtime to learn**.

Triggers are sent to machines by normal events, dispatched the normal way, so you drive a machine straight from an ordinary handler. Dispatching `[:auth-login [:submit …]]` fires the machine's `:submit` arrow:

```clojure
(rf/reg-event :login/submit
  (fn [_ [_ credentials]]
    {:fx [[:dispatch [:auth-login [:submit credentials]]]]}))

(rf/dispatch [:login/submit credentials])   ;; fire it the normal way
```

And the state of a machine is **read** like an ordinary subscription — you get back a [snapshot](glossary.md#snapshot), `{:state … :data …}`:

```clojure
@(rf/subscribe [:rf/machine :auth-login])   ;; => {:state :submitting :data {}}
```

The snapshot moves, and the views that read it follow.
