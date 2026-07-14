# Coming from Promises

You know `.then` / `.catch` / `.finally`. re-frame2 covers all three jobs, but a
settlement is a **value delivered to a handler**, not a promise you chain.

A managed effect — [HTTP](http.md), a [resource](../resources/concepts.md), a
[machine](../machines/concepts.md) — never hands you a promise. It dispatches an
ordinary [event](../core/glossary.md#event) with a canonical reply map. Question:
*which handler receives the settlement, and how does it read it?*

This page is the translation. The *why*: [Why no await](continuations-are-data.md).

## The mapping

| JS Promise | re-frame2 |
|---|---|
| `.then(onFulfilled)` | `:on-success [:ev]` (HTTP sugar), or the `:ok` branch of `(:status reply)` in one [`:reply-to`](http.md#one-handler-with-reply-to) handler |
| `.catch(onRejected)` | `:on-failure [:ev]` ([split sugar](http.md#two-handlers-with-on-success--on-failure)), or the `:error` branch of `(:status reply)` |
| `.finally(onFinally)` | shared code in **one** `:reply-to` handler — a `let` above the `case` ([example](#the-finally-shaped-example)) |
| `AbortController` cancellation | a **status you read** — `:cancelled` — not an exception type ([cancellation](http.md#cancellation-supersession-and-abort)) |
| a superseded / raced settlement | `:stale` — and it is **never delivered**, by design ([why](#why-there-is-no-on-finally)) |

The bottom two rows are where the Promise model is quietly weaker, not just spelled differently. A promise has no first-class *cancelled* outcome — you bolt on an `AbortController` and catch a thrown `AbortError` — and no notion of a *stale* settlement at all: a superseded `fetch` still resolves, still runs your `.then`, and cheerfully overwrites fresher data (the search-box race). re-frame2 makes both a **status** in a closed reply set, and suppresses the stale one before it can reach your handler.

??? info "Coming from `async/await`, not `.then`?"

    Same story, hidden better. `await` doesn't remove the two callbacks — it lets the compiler write them for you as the suspended rest of your function (a closure). The success path is the code after the `await`; the failure path is the `catch` block; the `finally` block is `finally`. re-frame2 unbundles that closure back into a named event and a reply value you branch on — so everything below reads the same whether you were writing `.then` chains or `await`. [Why no await](continuations-are-data.md) is the full account.

## The finally-shaped example

The `.finally` job — cleanup that runs whichever way the work settled — needs no dedicated key. Because the settlement arrives as one value in one handler, a `let` above the `case` already sees every branch:

```clojure
(rf/reg-event :articles/replied
  (fn [{:keys [db]} [_ reply]]
    (let [db (assoc db :loading? false)]                     ;; runs for every outcome — this is your `finally`
      (case (:status reply)
        :ok        {:db (assoc db :articles (:value reply))}  ;; `.then`
        :error     {:db (assoc db :error    (:error reply))}  ;; `.catch`
        :cancelled {:db db}))))
```

The code before the `case` **is** your `finally`; the branches are `then` and `catch`. No third callback, no ordering question — a `let` does the job, because the settlement is data and both branches share its scope.

## Where do the captured variables go?

A `.then` closure sees the locals of the function that issued the request. A reply handler doesn't — it runs on a fresh stack against the *present* [app-db](../core/glossary.md#app-db), not a snapshot from issue time. So anything the reply branch needs — a slug, an id — you **ride on the reply vector**, ahead of the appended envelope:

```clojure
:reply-to [:articles/replied slug]   ;; the handler receives [:articles/replied slug <reply-map>]
```

The split sugar carries context the same way — `:on-success [:articles/loaded slug]`. This is deliberate, not a missing feature: a captured local is a snapshot of the past — the source of [the stale-world trap](continuations-are-data.md#the-bug-this-kills-the-stale-world-trap) — while a value ridden on the vector is explicit data that lands on the record.

## Why there is no `:on-finally`

A Promise needs `finally` because settlement splits into two disjoint closures — the `.then` and the `.catch` — with no shared scope; `finally` is the one place a line can run for both. When settlement is a single value delivered to a single handler, that shared scope is just the handler body. There is nothing for an `:on-finally` to reunite.

And `finally` promises to **always run** — a guarantee re-frame2 deliberately breaks. A `:stale` reply is [suppressed, never delivered](continuations-are-data.md#one-envelope-under-every-async-surface): superseded work must not touch state, so its "finally" must *not* fire either. Cleanup like clearing the loading flag belongs to the **newer** attempt's lifecycle (generation-based cleanup), not to the older attempt's funeral (settlement-based cleanup). A faithful `:on-finally` would have to either resurrect the stale-race bug class — running cleanup on behalf of a request that was superseded — or lie about "always". re-frame2 records the refusal on the trace instead of shipping either.

**Rule of thumb:** two callbacks (`:on-success` / `:on-failure`) when the branches share nothing; one `:reply-to` handler the moment they share cleanup.

## Where to go next

- [Why no await: continuations are data](continuations-are-data.md) — the deeper *why*: named continuations, and the stale-world trap the data model closes by construction.
- [Managed HTTP](http.md) — the surface reference: every reply key, the closed `:status` set, and both addressing spellings side by side.
