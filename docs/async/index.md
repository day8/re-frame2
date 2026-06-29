# Async (HTTP)

Some work can't finish inside one event handler — a server round-trip, or anything that answers *later*. re-frame2 handles all of it the same way: you return the request as **data** from a pure [event handler](../core/concepts/effects-and-coeffects.md), finish, and the reply arrives later as an **ordinary [event](../core/concepts/events-and-the-cascade.md)**. No `await`, no resumed stack frame, no callback nesting — the answer comes back on the same wire as everything else, with success and failure each named.

That pattern is a **managed effect**: you *describe* the side effect, the runtime *performs* it and dispatches the result back to you. The flagship — and, today, the one this section documents in full — is **managed HTTP**.

```clojure
;; Issue a request as data from a handler; the reply lands as an event.
{:fx [[:rf.http/managed {:request    {:url "/api/articles/intro"}
                         :on-success [:article/loaded]
                         :on-failure [:article/load-error]}]]}
```

> **Scope.** This is about *managed* async — effects whose completion returns as a reply event. That's narrower than "async" in general: the event loop, `dispatch-later`, and the cascade are async too, and they live in [Effects and coeffects](../core/concepts/effects-and-coeffects.md).

> **Separate artefact.** Managed HTTP ships as `day8/re-frame2-http`, so apps that never issue a request build clean of it. Require `re-frame.http.managed` once at boot and the `:rf.http/managed` effect is wired up.

## In this section

- **[Managed HTTP](http.md)** — the `:rf.http/managed` effect end to end: the smallest request that works, reading the reply, the closed set of failure categories, retries, decoding, and curing the search-box race for free.
- **[API reference](../api/re-frame.http.md)** — the `:rf.http/managed` args map, the reply contract, request interceptors, and failure categories, with signatures.

For the *cache* over server reads — staleness, invalidation, scope — that's [Resources](../resources/index.md), which rides on managed HTTP underneath.
