# Resources & Server State glossary

re-frame2's optional server-state capability — declarative, cached reads and writes where the framework owns the cache, dedupe, staleness, and invalidation, so views read passively and never fetch. See [Server state](concepts.md).

### **resource**

A declared, cached server-state **read** (the read-side partner to a [mutation](#mutation)'s write), registered with `reg-resource`. Its [`:scope`](#scope) is a required, fail-closed leak boundary — part of the read's identity (with its `:params`) — so one user's data can't surface in another's cache.

```clojure
(rf/reg-resource :article {:scope :rf.scope/global} request-fn)
```

Related: [Server state](concepts.md). "Server state" is the category, not the noun.

### **mutation**

A declared server-state **write** — the write-side partner to a [resource](#resource)'s read. Its cache consequences (which cached reads it [invalidates](#invalidate), by [cache tag](#cache-tag)) are declared *once, on the registration*, never imperatively at the call site.

```clojure
(rf/reg-mutation :article/favorite {:scope :rf.scope/global} request-fn)
```

Related: [Server state](concepts.md). The reply field is `:value`; the instance sub field is `:result`.

### **invalidate**

A [mutation](#mutation) declares — as data on its registration, never imperatively — which cached [resource](#resource) reads it makes stale (matched by [cache tag](#cache-tag)), so they refetch.

```clojure
{:invalidates (fn [{:keys [slug]} _result]
                {:tags #{[:article slug]}})}     ;; declared once, on the mutation
```

Related: [Server state](concepts.md).

### **scope**

A [resource](#resource)'s required, fail-closed leak boundary — the declaration of *whose* data a cached read belongs to (`:rf.scope/global` for a genuinely public read, or a per-user/tenant resolver). It's part of the read's cache identity, so one principal's data can never surface in another's cache; a scope that can't resolve **raises** rather than serving the wrong data.

### **cache tag**

A structured label like `[:article slug]` a [resource](#resource) attaches to its data, declaring what the data is *about*. A [mutation](#mutation) then [invalidates](#invalidate) by tag — "I changed `[:article slug]`" — and exactly the cached reads carrying that tag refresh. (Distinct from a machine's [state tag](../machines/glossary.md#state-tag).)

### **resource status**

The lifecycle a cached read reports to a [view](../core/glossary.md#view): `:idle` → `:loading` → `:loaded` | `:error`, plus `:fetching` for a background refresh that keeps the old value on screen. `:error` is reserved for a *first*-load failure; a failed refresh stays `:loaded` and records the error separately, so a hiccup never blanks the page.

### **owner & cause**

Two facts the runtime tracks per fetch. An **owner** is a *lease* — a route, a [machine](../machines/glossary.md#machine), an explicit hold — that keeps a cached entry alive and decides whether an [invalidation](#invalidate) refetches now or merely marks the entry stale; release every owner and the entry becomes GC-eligible. A **cause** is pure provenance — *why* a fetch happened (a route entry, a click, a refresh) — recorded for the trace and never affecting liveness. *Owner = lifetime; cause = explanation.*

### **optimistic update & rollback**

Writing a [mutation](#mutation)'s expected result into the cache *before* the server confirms, so the UI responds instantly — then, when the [reply](#reply-map) settles, committing it (on success), rolling the slice back (on failure), or reconciling. A `:stale` reply changes nothing.

### **managed HTTP**

The `:rf.http/managed` [effect](../core/glossary.md#effect): you describe a request as data and the runtime owns its whole lifecycle — encode, send, decode, classify failures, retry-with-backoff, abort — then [dispatches](../core/glossary.md#dispatch) the result back as an ordinary [event](../core/glossary.md#event). You never touch `js/fetch`. (A `:request-id` lets a re-issue supersede an in-flight call; reads retry, writes don't.)

### **reply map**

The map every managed async result arrives in — `{:kind :success :value …}` / `{:kind :failure :failure …}` for HTTP, the five-status view-model (`:ok`/`:partial`/`:error`/`:cancelled`/`:stale`) for resources. It rides as the last argument of the reply [event](../core/glossary.md#event); branch on `:kind`/`:status`, never on a stringified message. (The concrete shape of [the uniform reply](../core/glossary.md#the-uniform-reply).)
