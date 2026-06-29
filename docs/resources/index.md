# Resources & Server State

Most of what a real app shows isn't *its* state — it's the **server's**, borrowed and cached: the article you're reading, the feed you're scrolling, the profile you just edited. Manage that by hand and you rebuild the same machinery in every component — fetch on mount, store the result, track loading and error, dedupe in-flight calls, decide when it's stale, refetch after a write, and try not to leak one user's data into another's session. It's the bulk of the bugs in a typical SPA.

re-frame2's **resources** capability makes server state declarative. You register a cached **read** ([`reg-resource`](glossary.md#resource)) and a **write** ([`reg-mutation`](glossary.md#mutation)); the framework owns the cache, the dedupe, the [staleness and invalidation](glossary.md#invalidate), and a fail-closed [`:scope`](glossary.md#scope) leak boundary. Views *read* — they never fetch. A mutation declares — once, on its registration — which reads it [invalidates](glossary.md#invalidate), and exactly those refresh.

```clojure
(rf/reg-resource :article
  {:scope :rf.scope/global}
  (fn [{:keys [slug]}] {:method :get :url (str "/api/articles/" slug)}))

;; a view just reads — no fetching, no loading flags to wire
@(rf/subscribe [:rf/resource :article {:slug "hello"}])
;; => {:status :loading}  …then {:status :loaded :value {...}}
```

Underneath sits [**managed HTTP**](../async/index.md) (`:rf.http/managed`) — you describe a request as data and the runtime drives its whole lifecycle, dispatching the result back as an ordinary event. Resources are the high-level cache; managed HTTP — now its own section, [Async (HTTP)](../async/index.md) — is the transport they ride on, and it's there for the requests that aren't cached reads.
