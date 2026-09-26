# Resources glossary

re-frame2's optional server-state capability — declarative, cached reads and writes where the framework owns the cache, dedupe, staleness, and invalidation, so views read passively and never fetch. See [the model](concepts.md).

### **resource**

A declared, cached server-state **read** (the read-side partner to a [mutation](#mutation)'s write), registered with `reg-resource`. Its [`:scope`](#scope) is a required, fail-closed leak boundary — part of the read's identity (with its `:params`) — so one user's data can't surface in another's cache.

```clojure
(rf/reg-resource :article
  {:params-schema [:map [:slug :string]]   ;; required — the read's identity
   :scope         :rf.scope/global}        ;; required — whose cache?
  request-fn)
```

Related: [the model](concepts.md).

### **mutation**

A declared server-state **write** — the write-side partner to a [resource](#resource)'s read. Its cache consequences (which cached reads it [invalidates](#invalidate), by [cache tag](#cache-tag)) are declared *once, on the registration*, never imperatively at the call site.

```clojure
(rf/reg-mutation :article/favorite
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :invalidates   (fn [{:keys [slug]} _result] #{[:article slug]})}
  request-fn)
```

Related: [the model](concepts.md). A `:reply-to` reply carries the result as `:value`; the instance subscription carries it as `:result`.

### **mutation instance**

The id one run of a [mutation](#mutation) is tracked under — the `:instance` on `[:rf.mutation/execute …]`, such as `[:favorite slug]`. Its lifecycle (`:idle`, `:pending`, `:success`, `:error`) is kept per instance rather than per mutation id, so two article cards saving at once don't mix up their state; a view reads it with `[:rf/mutation {:instance …}]`. Executing again under the same instance supersedes the earlier attempt and suppresses its late reply.

### **invalidate**

A [mutation](#mutation) declares — as data on its registration, never imperatively — which cached [resource](#resource) reads it makes stale (matched by [cache tag](#cache-tag)), so they refetch.

```clojure
{:invalidates (fn [{:keys [slug]} _result]
                #{[:article slug]})}     ;; bare tag-set; or [{:scope … :tags #{…}}]
```

Related: [the model](concepts.md).

### **scope**

A [resource](#resource)'s required, fail-closed leak boundary — the declaration of *whose* data a cached read belongs to (`:rf.scope/global` for a genuinely public read, or a per-user/tenant [scope resolver](#scope-resolver)). It's part of the read's cache identity, so one principal's data can never surface in another's cache; a scope that can't resolve **raises** rather than serving the wrong data.

### **scope resolver**

A named, pure function registered with `reg-resource-scope` that answers "whose cache?" from app-db. It declares the app-db paths it reads as `:inputs` and returns a scope value such as `[:rf.scope/session {:username "ada"}]`, or `nil` when it can't tell. A resource points at it with `:scope {:from-db <resolver-id>}`, and `rf/resolve-resource-scope` runs it against a db value, as a logout handler does before clearing the departing user's cache. A `nil` answer fails closed.

### **cache tag**

A structured label like `[:article slug]` a [resource](#resource) attaches to its data, declaring what the data is *about*. A [mutation](#mutation) then [invalidates](#invalidate) by tag — "I changed `[:article slug]`" — and exactly the cached reads carrying that tag refresh. (Distinct from a machine's [state tag](../machines/glossary.md#state-tag).)

### **resource status**

The lifecycle a cached read reports to a [view](../core/glossary.md#view): `:idle` → `:loading` → `:loaded` | `:error`, plus `:fetching` for a background refresh that keeps the old value on screen. `:error` is reserved for a *first*-load failure; a failed refresh stays `:loaded` and records the error separately (`:refresh-error`), so the page keeps showing its data.

### **owner & cause**

Two facts the runtime tracks per fetch. An **owner** is a *hold* — a route, a [machine](../machines/glossary.md#machine), an app event — that keeps a cached entry alive and decides whether an [invalidation](#invalidate) refetches now or merely marks the entry stale; release every owner and the entry becomes GC-eligible. A **cause** is pure provenance — *why* a fetch happened (a route entry, a click, a refresh) — recorded for the trace and never affecting liveness. *Owner = lifetime; cause = explanation.*

### **ensure**

The ordinary way to cause a read: `[:rf.resource/ensure {:resource … :params … :cause …}]`, which a route's `:resources` entry also dispatches for you. It makes sure a fresh-enough load exists — a cache hit when the entry is fresh, joining the request already in flight for the same key, and a fetch otherwise — and attaches the [owner](#owner--cause) it carries, if any. `[:rf.resource/refetch …]` is the forced version: it always sends a new request.

### **infinite resource & load-more**

A [resource](#resource) registered with `:infinite true`: one cache entry whose value is an ordered vector of pages, with the next page's cursor derived by `:next-page-param`. `[:rf.resource/load-more {:resource … :params … :cause …}]` appends the next page to that entry, and `:rf.resource/items` reads the merged list. See [Paginate a feed](how-to/paginate-a-feed.md#load-more-an-infinite-resource-is-one-growing-entry).

### **optimistic update & rollback**

Writing a [mutation](#mutation)'s expected result into the cache *before* the server confirms, so the UI responds instantly — then, when the [reply](#reply-map) settles, committing it (on success), rolling the slice back (on failure), or reconciling. A `:stale` reply changes nothing.

### **managed HTTP**

The `:rf.http/managed` [effect](../core/glossary.md#effect): you describe a request as data and the runtime owns its whole lifecycle — encode, send, decode, classify failures, retry-with-backoff, abort — then [dispatches](../core/glossary.md#dispatch) the result back as an ordinary [event](../core/glossary.md#event). You never touch `js/fetch`. (A `:request-id` lets a re-issue supersede an in-flight call; retries are opt-in per request, via `:retry`, for reads and writes alike.)

### **reply map**

The one map every managed async result arrives in — a closed `:status` (`:ok`/`:error`/`:cancelled`/`:stale`), with `:value` on `:ok` and the failure map under `:error` — the *same* envelope for HTTP and resources alike. It rides as the last argument of the reply [event](../core/glossary.md#event); branch on `:status`, never on a stringified message. (The concrete shape of [the uniform reply](../core/glossary.md#the-uniform-reply).)

### **reply-to continuation**

The event a caller names to run once a write or read settles: `:reply-to [:editor/replied]` on `[:rf.mutation/execute …]`, `[:rf.resource/ensure …]` or `[:rf.resource/refetch …]`. The runtime appends the [reply map](#reply-map) and dispatches it once, for the accepted terminal reply only — a superseded reply never reaches it. A mutation's continuation runs after its cache consequences have been applied. It is where workflow goes (navigate, toast); the cache consequences stay on the registration.
