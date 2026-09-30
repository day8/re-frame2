# Resources & server state

Resources manage cached server reads: articles, feeds and profiles that several
views may need. You declare how to fetch them and when they become stale;
routes or events request loads, and views read the cached result. Mutations
declare how server writes update or invalidate those reads.

```clojure
(:require [re-frame.core :as rf]
          [re-frame.resources]
          [re-frame.http.managed])

(rf/reg-resource :article/by-slug
  {:params-schema [:map [:slug :string]]
   :scope :rf.scope/global}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}
     :decode :json}))
```

Registration sends no request. A route or event dispatches
`[:rf.resource/ensure {:resource :article/by-slug :params {:slug "hello"}}]`;
a view reads `[:rf/resource {:resource :article/by-slug :params {:slug "hello"}}]`.
[The model](concepts.md) connects these forms and explains loading, errors,
owners and scope. The [tutorial](tutorial/index.md) applies them in a small
publishing app.

The `:scope` declaration is part of cache identity. Use `:rf.scope/global`
only when every viewer gets the same answer; a named scope resolver separates
viewer-dependent data. Merely subscribing neither fetches nor keeps an entry
alive.

## When not to use resources

| Situation | Prefer |
|---|---|
| One or two uncached requests | [Managed HTTP](../async/http.md) and app-db |
| Form drafts, filters and other client state | app-db and events |
| A workflow with several named stages | [Machines](../machines/index.md), using resources when a stage needs cached data |

[Where should this value live?](../core/where-state-lives.md) explains the
choice. For exact forms and options, use the
[API reference](../api/re-frame.resources.md); for an existing query-library
application, [Coming from TanStack Query](coming-from-tanstack-query.md) maps
the migration decisions.
