# Your own async effect

Managed HTTP is one async effect the framework ships. But you'll meet others it doesn't: a **promise-returning SDK** (Stripe, Firebase, WebAuthn), a callback API, an IndexedDB request, a message from a worker. The good news is there's nothing new to learn — you wrap any of them the *same way* HTTP works: as a **managed effect** whose reply comes back as an ordinary [event](../core/concepts/events-and-the-cascade.md).

> **The one rule.** An [event handler](../core/concepts/effects-and-coeffects.md) is pure — it can't `.then`, can't `await`. The **`fx` is the one seam where impurity lives**: it does the async work and *dispatches* the result as a named event. Same discipline as everything else — [name the continuation, don't await it](../core/explanation/continuations-are-data.md).

## Wrapping a promise

Three steps: register the effect once, ask for it from a handler (naming where the reply lands), and handle the reply.

```clojure
;; 1. Register the fx once, at boot. Its FIRST arg carries the frame; the
;;    second is the args map you pass from the handler.
(rf/reg-fx :payment/charge
  {:doc       "Charge via the payment SDK, then dispatch the reply event."
   :platforms #{:client}}                          ;; the SDK is browser-only
  (fn [fx-ctx {:keys [amount on-success on-failure]}]
    (let [frame (:frame fx-ctx)]                    ;; capture the frame for the deferred dispatch
      (-> (js/paymentSdk.charge amount)            ;; the promise-returning API
          (.then  (fn [result] (rf/dispatch (conj on-success result) {:frame frame})))
          (.catch (fn [err]    (rf/dispatch (conj on-failure err)    {:frame frame})))))))

;; 2. A handler asks for it, naming where the reply lands — exactly like HTTP.
(rf/reg-event :checkout/pay
  (fn [{:keys [db]} _]
    {:db (assoc db :checkout/status :charging)
     :fx [[:payment/charge {:amount     (:checkout/amount db)
                            :on-success [:checkout/charged]
                            :on-failure [:checkout/charge-failed]}]]}))

;; 3. The reply is an ordinary event — the result appended as its last arg.
(rf/reg-event :checkout/charged
  (fn [{:keys [db]} [_ result]]
    {:db (assoc db :checkout/status :paid, :checkout/receipt result)}))
```

Swap `js/paymentSdk.charge` for an IndexedDB request, a `postMessage` to a worker, or a WebAuthn challenge and the shape is identical: post the work, translate the reply into a `dispatch`.

> **Gotcha — carry the frame.** The `.then` callback fires on a *fresh stack*, long after the handler returned, with no [frame](../core/concepts/frames.md) in scope. A bare `(rf/dispatch …)` there raises `:rf.error/no-frame-context`. So read `(:frame fx-ctx)` in the fx and pass `{:frame frame}` to every deferred dispatch — that lands the reply back in the frame the request came from.

> **Keep it serializable.** Pass keywords, ids, and data across the boundary — never closures. The reply event has to survive a trace, a replay, and an SSR payload, and a closure survives none of them. (That's also why you name `:on-success`/`:on-failure` events instead of passing callbacks.)

> **Don't write `app-db` from the fx.** The fx posts work and dispatches; the *reply handler* does the state write. Keeping that split is what keeps handlers pure and replays deterministic.

## When *not* to roll your own

- **For HTTP, use [`:rf.http/managed`](http.md).** Don't hand-roll `fetch` — the managed effect already gives you retries, abort, a structured failure taxonomy, and stale-result suppression. The example above is for the async APIs that *aren't* HTTP.
- **For a long-lived connection** — a WebSocket, SSE, WebRTC peer with retry/backoff/heartbeat — the *connection* is a lifecycle, so model it with a [machine](../machines/concepts.md), not a one-shot fx. (Individual messages over an already-open socket *do* fit the one-shot shape above.)

## Going deeper

- **[Pattern — Async Effect](../../spec/Pattern-AsyncEffect.md)** — the canonical six-step shape and a catalogue of instances: workers, IndexedDB, WebAuthn, geolocation, native bridges, `requestAnimationFrame`, streaming LLM calls.
- **[No await: continuations are data](../core/explanation/continuations-are-data.md)** — why the reply is a named event in the first place.
- The [login example](../../examples/core/login) registers a hand-rolled async `fx` and drives the reply into a state machine — the pattern under real load.
