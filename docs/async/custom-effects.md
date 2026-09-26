# Your own async effect

You know [managed HTTP](http.md). This page is one job: **wrap a non-HTTP async host
API** (promise SDK, callback, IndexedDB, worker) so its result is a named reply
[event](../core/introduction.md).

Same continuation *style* as HTTP — not the managed extras (retry, abort, stale
suppression, HTTP failure kinds) unless you build them.

!!! note "The one rule"

    An [event handler](../core/effects.md) is pure — no `.then`, no `await`. The **`fx`
    is the impurity seam**: do host work, then `dispatch` a named event. Same discipline
    everywhere — [name the continuation, don't await it](continuations-are-data.md).

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
          (.then  (fn [result]                     ;; host objects become plain data here
                    (rf/dispatch (conj on-success (js->clj result :keywordize-keys true))
                                 {:frame frame})))
          (.catch (fn [err]
                    (rf/dispatch (conj on-failure {:message (.-message err)})
                                 {:frame frame})))))))

;; 2. A handler asks for it, naming where the reply lands.
(rf/reg-event :checkout/pay
  (fn [{:keys [db]} _]
    {:db (assoc db :checkout/status :charging)
     :fx [[:payment/charge {:amount     (:checkout/amount db)
                            :on-success [:checkout/charged]
                            :on-failure [:checkout/charge-failed]}]]}))

;; 3. Each reply is an ordinary event — its data appended as the last arg.
(rf/reg-event :checkout/charged
  (fn [{:keys [db]} [_ result]]
    {:db (assoc db :checkout/status :paid, :checkout/receipt result)}))

(rf/reg-event :checkout/charge-failed
  (fn [{:keys [db]} [_ {:keys [message]}]]
    {:db (assoc db :checkout/status :failed, :checkout/error message)}))
```

Swap `js/paymentSdk.charge` for an IndexedDB request, a `postMessage` to a worker, or a WebAuthn challenge and the shape is identical: post the work, translate the reply into a `dispatch`.

!!! warning "Gotcha — carry the frame"

    The `.then` callback fires on a *fresh stack*, long after the handler returned, with no [frame](../core/frames.md) in scope. A bare `(rf/dispatch …)` there raises `:rf.error/no-frame-context`. So read `(:frame fx-ctx)` in the fx and pass `{:frame frame}` to every deferred dispatch — that lands the reply back in the frame the request came from.

!!! note "Keep it serializable"

    Pass keywords, ids, and data across the boundary — never closures. The reply event has to survive a trace, a replay, and an SSR payload, and a closure survives none of them. (That's also why you name `:on-success`/`:on-failure` events instead of passing callbacks.)

!!! note "Don't write `app-db` from the fx"

    The fx posts work and dispatches; the *reply handler* does the state write. Keeping that split is what keeps handlers pure and replays deterministic.

!!! note "Guard against a stale reply"

    A reply can land after the user has moved on: a second charge started, or the checkout was abandoned. Write a token into `app-db` when you issue the work (a counter works) and ride it on the reply vector, `:on-success [:checkout/charged token]`, so the handler receives `[_ token result]`. It commits only when that token still matches the one in `app-db`; on a mismatch it returns no effects.

## Testing it

Each half tests on its own. The issuing handler returns the effect as data, so a [handler test](../core/testing/event-handlers.md) asserts on its `:fx`. The reply is an ordinary event, so a test can dispatch `[:checkout/charged {:id "ch_1"}]` directly. To run the whole chain, redirect the effect with `:fx-overrides` to a function that dispatches a canned reply ([Redirect any effect](../core/testing/pipeline-runs.md#redirect-any-effect-fx-overrides)):

```clojure
(deftest checkout-pays
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:checkout/pay]
                      {:fx-overrides
                       {:payment/charge (fn [{:keys [frame]} {:keys [on-success]}]
                                          (rf/dispatch (conj on-success {:id "ch_1"}) {:frame frame}))}})
    (is (= :paid (:checkout/status (rf/app-db-value f))))))
```

## When *not* to roll your own

- **For HTTP, use [`:rf.http/managed`](http.md).** Don't hand-roll `fetch` — managed HTTP already gives you retries, abort, structured failures, and stale-result suppression. The example above is for APIs that *aren't* HTTP, so it only has the guarantees you put into it.
- **For a long-lived connection** — a WebSocket, SSE, WebRTC peer with retry/backoff/heartbeat — the *connection* is a lifecycle, so model it with a [machine](../machines/concepts.md), not a one-shot fx. (Individual messages over an already-open socket *do* fit the one-shot shape above.)

## The checklist

The same recipe covers any one-shot async `fx`: register the effect, capture the frame, start the host work, dispatch a named success or failure event, keep state writes in handlers, and pass data rather than closures. The [payment snippet](#wrapping-a-promise) above is its worked example.
