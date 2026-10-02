# Why no await: continuations are data

An event handler returns effects and finishes. When an async effect completes,
it dispatches another event. That reply handler receives the current app-db, and
its state change passes through the same [event pipeline](../core/events.md) as a
button click.

## Name the receiving event

```clojure
(:require [re-frame.core :as rf]
          [re-frame.http.managed])

{:fx [[:rf.http/managed
       {:request    {:url "/api/articles/intro"}
        :on-success [:article/loaded]
        :on-failure [:article/load-error]}]]}
```

The **continuation** is what should happen after the result arrives. Here its
address is `[:article/loaded]` or `[:article/load-error]`. The runtime appends a
reply map and dispatches that event. You can put context in the vector too:
`:on-success [:article/loaded slug]` delivers `[:article/loaded slug reply]`.

<a id="what-an-await-quietly-hides"></a>
<a id="the-bug-this-kills-the-stale-world-trap"></a>

## Decisions use the current app-db

A callback can accidentally close over the `db` that existed when work started.
By the time its result arrives, the user may have selected another article.
A separate reply handler gets the app-db at delivery time:

```clojure
(rf/reg-event :article/load
  (fn [{:keys [db]} [_ slug]]
    {:db (assoc db :article/viewing slug)
     :fx [[:rf.http/managed
           {:request    {:url (str "/api/articles/" slug)}
            :request-id :article/load
            :on-success [:article/loaded slug]
            :on-failure [:article/load-error slug]}]]}))

(rf/reg-event :article/loaded
  (fn [{:keys [db]} [_ slug {:keys [value]}]]
    (if (= slug (:article/viewing db))
      {:db (assoc db :article/data value :article/error nil)}
      {})))

(rf/reg-event :article/load-error
  (fn [{:keys [db]} [_ slug {:keys [error]}]]
    (if (= slug (:article/viewing db))
      {:db (assoc db :article/error error)}
      {})))
```

The slug records which article the request asked for; `db` tells the handler
which article is selected now. The stable `:request-id` additionally supersedes
an earlier load in this frame, so an older request cannot overwrite a newer load
of the *same* slug. Navigating away without another request can still change
`:article/viewing`, which the handlers check.

The event model does not make stale data impossible. You can still pass an old
snapshot in an event and misuse it. Make decisions from the receiving handler's
current coeffects, and carry only the request context it needs. Managed HTTP
provides [request-id and lifetime checks](http.md#cancellation-supersession-and-abort);
[a custom async effect](custom-effects.md) needs its own correlation rules.

<a id="what-a-named-continuation-can-do-that-a-closure-cant"></a>

## What the runtime can inspect

A request description and its reply address are ordinary data. The runtime can
record which effect was issued, show the request in [Xray](../xray/index.md), and
record the reply event in [the ledger](../core/coeffects.md#the-ledger). Tests can
supply the reply without resuming a suspended function.

The event id is resolved when the reply is handled, so re-registering that handler
during development changes how an in-flight request's reply is processed. This
does not persist pending work across a page reload. Sockets, timers and requests
are host work; serializing an event vector does not recreate them. Restoring an
epoch or destroying a frame aborts its managed HTTP work and suppresses the reply.

<a id="one-envelope-under-every-async-surface"></a>

## One reply map under every async surface

Managed HTTP and [resources and mutations](../resources/concepts.md) use the
[uniform reply](../core/glossary.md#the-uniform-reply). Its status vocabulary is:

| `:status` | Meaning |
|---|---|
| `:ok` | Success, with the result in `:value`. |
| `:partial` | Usable data with structured problems. Managed HTTP does not emit this status. |
| `:error` | Failure, with details in `:error`. A timeout is an error, not a separate status. |
| `:cancelled` | Cancellation of current work; a live managed reply names the cancel reason. |
| `:stale` | Obsolete completion. Recorded by the runtime and suppressed before app dispatch. |

An HTTP reply handler therefore handles `:ok`, `:error` and `:cancelled`. A
superseded request never reaches it. The [HTTP reference](../api/re-frame.http.md#reply-shape)
lists the identity and timing fields of a live reply; stubs omit those fields.

This common runtime model does not make every public completion payload identical.
A [managed HTTP child machine](http-machines.md) sends its parent
`[:succeeded value]` or `[:failed failure]`. A machine's `:on-done` callback receives
its declared result. Use the documented completion form of the surface you call.

Record completion time from the reply's `:completed-at` or HTTP's recorded
[`:rf/time-ms` coeffect](http.md#timestamps-come-from-a-coeffect), rather than
reading a new clock value during replay.

## Coming from Promises

| Promise operation | Event-based equivalent |
|---|---|
| `.then(onFulfilled)` | HTTP `:on-success [:loaded]`, or the `:ok` branch of a `:reply-to` handler. |
| `.catch(onRejected)` | HTTP `:on-failure [:load-error]`, or the `:error` branch. |
| `.finally(onFinally)` | Shared code in the reply handler, for every outcome actually delivered. |
| `AbortController` | `:abort-signal` in the browser, or `:rf.http/managed-abort` by request id on either host. |
| `Promise.all` | HTTP child machines under [`:spawn-all` with `:join :all`](http-machines.md). |

### The `.finally` job

Use `:reply-to` when success, failure and cancellation share cleanup:

```clojure
(rf/reg-event :article/replied
  (fn [{:keys [db]} [_ reply]]
    (let [db (assoc-in db [:article :loading?] false)]
      (case (:status reply)
        :ok        {:db (assoc-in db [:article :data] (:value reply))}
        :error     {:db (assoc-in db [:article :error] (:error reply))}
        :cancelled {:db db}))))
```

The request supplies `:reply-to [:article/replied]`. A reply target need not be the
issuing event: separate send and receive events are often easier to read. When
you do [use one event for both](http.md#one-handler-with-reply-to), test explicitly
for the absence of a reply before issuing work, so cancellation cannot start it
again.

### Why there is no `:on-finally`

A stale completion must not clear a loading flag that now belongs to a newer
request. Its reply is suppressed, including any cleanup the handler would have
done. Frame teardown similarly delivers no reply. Put cleanup that must happen
on teardown in the owning lifecycle rather than in a reply handler.

## The honest trade

A short `async/await` function can express several dependent steps in one place.
Events give each step a name and a handler, which costs more registrations. For a
single request that is usually manageable. For a workflow with branching,
cancellation or dependent requests, a [state machine](../machines/concepts.md)
keeps the states and transitions together while effects still return through events.

Promises remain useful inside effect implementations. The separation is between
host work in the effect and state changes in the event handler.
