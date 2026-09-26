# Resources & server state

Much of what an app shows is the **server's** state, borrowed and cached: the article
you are reading, the feed you are scrolling, the profile you just edited. Managed by
hand, that means rebuilding the same machinery for every feature — fetch, store the
result, track loading and errors, dedupe in-flight calls, decide staleness, refetch
after a write, and keep one user's data out of another's session.

**Resources** make server state declarative. You register a cached **read**
([`reg-resource`](glossary.md#resource)) and a **write**
([`reg-mutation`](glossary.md#mutation)); the framework owns the cache, dedupe,
[invalidation](glossary.md#invalidate), and a required [`:scope`](glossary.md#scope)
that keeps each user's cached data separate. Views only read — they never fetch.

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

Every part of the API sits in one of three lanes: **register** a read or write once,
**cause** a fetch or write (a route, an ensure, an execute), or **project** cached
state into a view. A subscription that finds no entry stays `:idle` until something
causes a load. [The model](concepts.md) explains each lane and ends with a
[complete register + route + view skeleton](concepts.md#a-complete-read-loop).

Resources work alongside [events](../core/introduction.md), app-db and effects rather
than replacing them, and use [managed HTTP](../async/index.md) (`:rf.http/managed`)
as their transport.

## When *not* to use resources

| Situation | Prefer |
|---|---|
| One or two uncached requests | [Managed HTTP](../async/http.md) + a small app-db slice |
| Pure client state (UI flags, form drafts) | app-db + events |
| Named lifecycle stages (login, websocket) | [machines](../machines/index.md) |
| No server yet | app-db + events |

Reach for resources when cached server reads start multiplying.
[Where should this value live?](../core/where-state-lives.md) has the full decision
table.

The [tutorial](tutorial/index.md) builds a full app with resources. If you already
know a server-cache library, start from [Coming from TanStack Query](coming-from-tanstack-query.md)
or [re-frame-query → resources](../../migration/from-re-frame-v1/re-frame-query-to-resources.md);
exact forms are in the [API reference](../api/re-frame.resources.md).
