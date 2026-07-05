# Your own async effect

Managed HTTP is one async effect the framework ships. But you'll meet others it doesn't: a **promise-returning SDK** (Stripe, Firebase, WebAuthn), a callback API, an IndexedDB request, a message from a worker. For those, write a small `fx`: start the host work, then dispatch a named reply [event](../core/introduction.md) when it finishes. That gives you the same continuation style as HTTP. It does not give you HTTP's managed extras — retry, abort, stale-result suppression, and the HTTP failure categories — unless you build those too.

!!! note "The one rule"

    An [event handler](../core/concepts/effects.md) is pure — it can't `.then`, can't `await`. The **`fx` is the one seam where impurity lives**: it does the async work and *dispatches* the result as a named event. Same discipline as everything else — [name the continuation, don't await it](continuations-are-data.md).

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

;; 2. A handler asks for it, naming where the reply lands.
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

!!! warning "Gotcha — carry the frame"

    The `.then` callback fires on a *fresh stack*, long after the handler returned, with no [frame](../core/concepts/frames.md) in scope. A bare `(rf/dispatch …)` there raises `:rf.error/no-frame-context`. So read `(:frame fx-ctx)` in the fx and pass `{:frame frame}` to every deferred dispatch — that lands the reply back in the frame the request came from.

!!! note "Keep it serializable"

    Pass keywords, ids, and data across the boundary — never closures. The reply event has to survive a trace, a replay, and an SSR payload, and a closure survives none of them. (That's also why you name `:on-success`/`:on-failure` events instead of passing callbacks.)

!!! note "Don't write `app-db` from the fx"

    The fx posts work and dispatches; the *reply handler* does the state write. Keeping that split is what keeps handlers pure and replays deterministic.

## When *not* to roll your own

- **For HTTP, use [`:rf.http/managed`](http.md).** Don't hand-roll `fetch` — managed HTTP already gives you retries, abort, structured failures, and stale-result suppression. The example above is for APIs that *aren't* HTTP, so it only has the guarantees you put into it.
- **For a long-lived connection** — a WebSocket, SSE, WebRTC peer with retry/backoff/heartbeat — the *connection* is a lifecycle, so model it with a [machine](../machines/concepts.md), not a one-shot fx. (Individual messages over an already-open socket *do* fit the one-shot shape above.)

## The checklist

The same recipe covers any one-shot async `fx`: register the effect, capture the frame, start the host work, dispatch a named success or failure event, keep state writes in handlers, and pass data rather than closures. The [login example](../../examples/core/login) shows it under real load — a hand-rolled async `fx` driving its reply into a state machine.
