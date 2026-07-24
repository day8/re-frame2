# Resources & server state

Most of what a real app shows is not *its* state — it is the **server's**, borrowed
and cached: the article you are reading, the feed you are scrolling, the profile you
just edited. Manage that by hand and you rebuild the same machinery everywhere —
fetch on mount, store the result, track loading and error, dedupe in-flight calls,
decide staleness, refetch after a write, and not leak one user's data into another's
session.

**Resources** make server state declarative. You register a cached **read**
([`reg-resource`](glossary.md#resource)) and a **write**
([`reg-mutation`](glossary.md#mutation)). The framework owns the cache, dedupe,
[invalidation](glossary.md#invalidate), and a fail-closed [`:scope`](glossary.md#scope)
leak boundary. Views *read* — they never fetch.

```clojure
(:require [re-frame.core :as rf]
          [re-frame.resources]
          [re-frame.http.managed])

(rf/reg-resource :article
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}
     :decode  :json}))

;; a view only reads — a subscription NEVER fetches
@(rf/subscribe [:rf/resource {:resource :article :params {:slug "hello"}}])
;; => {:status :idle …}  — registered, but nothing has caused a load yet

;; a route or an event is the cause; here, a one-shot ownerless ensure
;; (supply a :cause, not an :owner — nothing pins the entry alive)
(rf/dispatch [:rf.resource/ensure {:resource :article
                                   :params   {:slug "hello"}
                                   :cause    [:event :article/opened]}])

;; now the same passive read progresses
@(rf/subscribe [:rf/resource {:resource :article :params {:slug "hello"}}])
;; => {:status :loading …}  then  {:status :loaded :data …}
```

## Start here

1. **[Tutorial: Build RealWorld](tutorial/index.md)** — five parts growing a Medium-style
   app (pages → resources → auth → mutations → tests). Best first path if you want a
   full app.
2. **[The model](concepts.md)** — the grammar behind Part 2 onward: register, cause,
   project, five statuses, scope, mutations.
3. **Task recipes** when you have one job: [Paginate a feed](how-to/paginate-a-feed.md),
   [Invalidate after a mutation](how-to/invalidate-after-a-mutation.md).
4. **[Testing](testing.md)** — cause with a dispatch, answer with canned HTTP, read the
   cache projection.

**Prerequisites.** The [Core introduction](../core/introduction.md). Resources plug into
events, app-db, and effects; they do not replace them. The transport underneath is
[managed HTTP](../async/index.md) (`:rf.http/managed`).

Optional later: [examples](examples.md), [TanStack mapping](coming-from-tanstack-query.md),
[glossary](glossary.md).

## Three lanes

Hold this split and the API stays clear:

| Lane | Job | Spelling |
|---|---|---|
| **Register** | Declare the handler once | `(rf/reg-resource …)` / `(rf/reg-mutation …)` |
| **Cause** | Make a fetch or write happen | route `:resources`, `[:rf.resource/ensure …]`, `[:rf.mutation/execute …]` |
| **Project** | Read state into a view | `@(subscribe [:rf/resource …])` — never fetches |

A subscription that finds no entry stays `:idle` until something **causes** a load.
The model page ends with a [complete register + route + view skeleton](concepts.md#a-complete-read-loop).

## When *not* to use resources

| Situation | Prefer |
|---|---|
| One or two uncached requests | [Managed HTTP](../async/http.md) + a small app-db slice |
| Pure client state (UI flags, form drafts) | app-db + events |
| Named lifecycle stages (login, websocket) | [machines](../machines/index.md) |
| No server yet | Tutorial [Part 1](tutorial/01-pages-and-state.md) only |

Reach for resources when **cached server reads start multiplying** — not for a
local counter. [Where should this value live?](../core/where-state-lives.md) has the full
decision table.
