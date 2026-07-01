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

## In this section

- **[Tutorial: Build RealWorld](tutorial/index.md)** — five parts that grow a real Medium-style app: pages and state, real server data, auth and forms, writes and invalidation, tests and a production build. Start here.
- **[Concepts](concepts.md)** — the whole model, built up from a single read: registration, causes, the view-model, scope, owners, mutations, optimistic writes, infinite feeds, and every named failure.
- **How-to** — [Paginate a feed](how-to/paginate-a-feed.md) and [Invalidate after a mutation](how-to/invalidate-after-a-mutation.md): one task each, complete code.
- **[Examples](examples.md)** — runnable apps, from a focused read lifecycle to the full read → write → invalidate → refetch loop.
- **[Glossary](glossary.md)** — the section's vocabulary, one definition each.
- **[Coming from TanStack Query](coming-from-tanstack-query.md)** — the vocabulary mapping and the five deliberate divergences, for query-library refugees.

Underneath sits [**managed HTTP**](../async/index.md) (`:rf.http/managed`) — you describe a request as data and the runtime drives its whole lifecycle, dispatching the result back as an ordinary event. Resources are the high-level cache; [Async (HTTP)](../async/index.md) is the transport they ride on, and it's there for the requests that aren't cached reads.
