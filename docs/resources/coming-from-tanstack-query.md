# Coming from TanStack Query

This page translates TanStack Query (and SWR and RTK Query) into re-frame2
[resources](concepts.md): the vocabulary, then the places the design differs on
purpose. To build a first resource, use the [RealWorld tutorial](tutorial/index.md)
(from Part 2) and [the model](concepts.md).

The core idea carries over: **a keyed cache of server reads, with staleness, request
deduplication, tag-based invalidation, and garbage collection.** What moves is where
the cache meets your components. TanStack Query is a *hook* library: a query lives
inside a component's render and fetches from there. In re-frame2 the cache is part of
[runtime-db](../core/glossary.md#runtime-db), reached through
[events](../core/glossary.md#event) and [subscriptions](../core/glossary.md#subscription),
and most of the differences below come from one rule: **a read never causes a fetch.**

**Two defaults to change first.** A migrated app behaves differently from its TanStack original in two places:

- **A resource with no `:stale-after-ms` is never time-stale.** TanStack's `staleTime` defaults to `0`, stale at once; here, absent the key, only an invalidation or a mutation makes an entry stale. The key is per registration — there is no frame-wide default.
- **Focus and reconnect revalidation are off** unless the frame declares `:revalidate-on #{:focus :reconnect}`; TanStack's `refetchOnWindowFocus` / `refetchOnReconnect` are on by default. That revalidation refetches only entries that are stale *and* owned, so it depends on the first line.

So the migration profile is two lines, in two places:

```clojure
(rf/reg-resource :realworld/article {:scope … :stale-after-ms 60000} request-fn)  ;; on each migrated registration
(rf/make-frame {:id :rf/default :revalidate-on #{:focus :reconnect}})            ;; once, on the frame
```

## The mapping

| TanStack Query | re-frame2 | Notes |
|---|---|---|
| `useQuery({ queryKey, queryFn })` | `reg-resource` (register) + `[:rf/resource …]` (read) + a *cause* (fetch) | One hook splits into three jobs. See [Where it diverges](#where-it-diverges). |
| `queryKey: ['article', slug]` | `:params` (the `{:slug …}` map) under a [`:scope`](glossary.md#scope) | Identity is `[scope resource-id canonical-params]`. The user/tenant segment is a *separate, required* axis, not just another key element. |
| `queryFn` (returns a promise) | the third slot of `reg-resource` — `(fn [params ctx] {:request … :decode …})` | Returns request *data*, not a promise. The runtime owns `fetch`. |
| `data`, `error`, `status`, `isPending`, `isFetching` | `:rf/resource` view-model: `:data` `:error` `:status` `:loading?` `:fetching?` `:has-data?` | Five statuses: `:idle` `:loading` `:fetching` `:loaded` `:error`. `:error` is *first-load only*. |
| `staleTime` | `:stale-after-ms` | Same semantics: fresh window, then refetch on the next ensure. **Default diverges**: TanStack's `staleTime` defaults to `0` (stale-immediately); re-frame2's `:stale-after-ms` defaults to *never* time-stale (freshness is explicit-invalidation-driven, not wall-clock). |
| `gcTime` (was `cacheTime`) | `:gc-after-ms` | The interval of the GC check that reclaims an *owner-free*, idle entry. **The default value matches** — `gcTime` is 5 minutes, `:gc-after-ms` is `300000`, and `:gc-after-ms :never` is the explicit opt-out (TanStack's `Infinity`) — **but the retention guarantee doesn't**: the check's clock is armed when the entry settles, not when its last owner leaves. See the [scorecard row](#the-full-parity-scorecard). |
| an *observer* (a mounted `useQuery`) keeps data alive | an **[owner](glossary.md#owner--cause)** (a route, machine, or app-event owner) | Owner = liveness hold. Decoupled from any component mounting. |
| `enabled: false` / conditional queries | route `:resources` `:when` predicate (or simply: don't fire the cause) | A read with no cause sits at `:idle` — that's the "disabled" state, for free. |
| `select: (data) => …` | a plain [subscription](../core/glossary.md#subscription) over `[:rf.resource/data …]` | No `:select` key. You already have a memoised [derivation graph](../core/glossary.md#the-derivation-graph). |
| `placeholderData: keepPreviousData` | route `:keep-previous?` (+ `:previous-data` in the view-model) | Same anti-flash behaviour for pagination. |
| `refetchOnWindowFocus` / `refetchOnReconnect` | the frame's `:revalidate-on #{:focus :reconnect}` config key | Opt-in per [frame](../core/glossary.md#frame), declared on the frame rather than called; refetches only stale *and* owned entries. |
| `refetchInterval` | `:poll-interval-ms` | Owner-driven, auto-pauses on hidden tab. No `setInterval`. |
| `queryClient.invalidateQueries({ queryKey })` | a [mutation](glossary.md#mutation)'s declared `:invalidates` (by [tag](glossary.md#cache-tag)) | Declared on the write, not called imperatively in `onSuccess`. |
| `queryClient.setQueryData(key, data)` | a mutation's `:populates` / `:patches` | `:populates` seeds a key; `:patches` transforms one. |
| `queryClient.removeQueries(key)` | a mutation's `:removes`, or `[:rf.resource/remove …]` | Evict an exact key. |
| `queryClient.clear()` | `[:rf.resource/clear-scope …]` | You clear *one scope* — the departing user's — not the whole cache. |
| `useMutation({ mutationFn })` | `reg-mutation` (register) + `[:rf.mutation/execute …]` (run) + `[:rf/mutation …]` (read) | Same three-way split as queries. Keyed by an **instance**. |
| `onMutate` + rollback `context` / `onError` | `:optimistic` / `:optimistic-tags` (forward) — runtime records the inverse | You declare the forward change only; rollback is automatic. |
| `useInfiniteQuery` | `:infinite true` on a resource | One scoped entry holding a vector of pages. |
| `getNextPageParam(lastPage, allPages)` | `:next-page-param` | Terminal is **`nil`** (not `undefined`). |
| `fetchNextPage()` | `[:rf.resource/load-more …]` (a cause) | Ownerless — it extends the entry the route already owns. |
| `data.pages.flatMap(p => p.items)` | `[:rf.resource/items …]` | The merged list is framework-owned and memoised, not re-derived in render. |
| `QueryClientProvider` (one client per app) | a [frame](../core/glossary.md#frame) (cache lives in its runtime-db) | On the server, *one frame per request* — no process-global cache to leak across users. |
| `<HydrationBoundary>` / `dehydrate` | SSR projection + [hydration](../ssr/glossary.md#hydration) under the same freshness rules | A still-fresh hydrated entry isn't refetched; scopes must agree. |

A note for the **SWR** crowd: `useSWR(key, fetcher)` is the `useQuery` row; `mutate(key)` is `invalidateQueries`; bound `mutate` with `optimisticData` + `rollbackOnError` is the `:optimistic` / rollback row; `keepPreviousData` is `:keep-previous?`. SWR's `revalidateOnFocus` is the `:revalidate-on` row. The mental model is identical; SWR just gives you a smaller surface.

And for **RTK Query**: you're closest to home, because RTK Query also declares the cache graph up front (`createApi` with endpoints, `providesTags` / `invalidatesTags`). re-frame2's [tag](glossary.md#cache-tag) invalidation is the same idea; [§3](#3-invalidation-is-a-declared-consequence-not-a-remembered-call) notes what it adds. RTK Query's `keepUnusedDataFor` is `:gc-after-ms`, an endpoint is roughly a resource registration, and the generated hooks have no analogue — you write the read and the cause yourself.

## Where it diverges

Five design decisions explain the rows above that don't map one to one. Each costs a little ceremony and buys a property a hook can't offer.

### 1. A read never fetches. One hook becomes three jobs.

`useQuery` does three things at once: it *declares* the query, *triggers* the fetch (on mount), and *reads* the result (on every render). The fetch is a side effect of rendering.

re-frame2 splits those into three lanes:

- **Register** — `(rf/reg-resource …)` at boot. Teaches the runtime *how* to fetch. Fetches nothing.
- **Cause** — a route entry, an event, or a [machine](../machines/glossary.md#machine) dispatches `[:rf.resource/ensure …]`. This is what makes a fetch happen.
- **Project** — `@(subscribe [:rf/resource …])` in a [view](../core/glossary.md#view). It reads the cache and never triggers a fetch.

The cost: you write a cause that `useQuery` gave you for free. The payoff: the view is a pure function of the cache, so the *same* view renders on the server, in a unit test, or after a cache hit, with no network call hiding in the render. It's also why "I registered the resource but my view is a permanent skeleton" almost always means *you forgot the cause*, not the read: a subscription that finds no entry reads `:idle` and stays there until something causes the fetch. (TanStack's `enabled: false` is the same idea — a read that doesn't fetch — except here it's the default shape rather than a flag.)

It also pays off at the route layer. A page declares its data needs in route metadata (`:resources [{:resource … :blocking? true}]`), the fetch starts *before* the component mounts, and a `:blocking?` read doubles as the SSR wait point. TanStack can start reads before render too — a TanStack Router loader does, and so does `queryClient.query`, which TanStack Query 5.102.0 added as the imperative read, deprecating `fetchQuery` / `prefetchQuery` / `ensureQueryData` — so the mount-then-fetch waterfall is avoidable there. The difference is the default: a bare `useQuery` still fetches from render, so avoiding the waterfall is a pattern you adopt, whereas a resources subscription cannot fetch at all, and a cause outside the view is the only shape there is.

### 2. Scope is a required key axis, not a key segment you remember.

In TanStack, the user or tenant id is one more element in the `queryKey`: `['feed', userId]`. It works until one call site writes `['feed']` and forgets the id. Then every user's feed shares a cache entry, and the second user silently sees the first user's data. The key is positional, untyped and assembled by hand at every call site, so nothing catches it.

re-frame2 makes that leak *unrepresentable*. A cache entry's identity is a triple — `[scope resource-id canonical-params]` — and [`:scope`](glossary.md#scope) is a **required** registration key with no default. Params say *which* article; scope says *whose* cache. You either claim `:scope :rf.scope/global` ("everyone gets the same answer" — an explicit, auditable claim) or you name a resolver that derives the viewer from app-db:

```clojure
(rf/reg-resource-scope :realworld/session
  {:inputs {:username [:db [:auth :user :username]]}}
  (fn [{:keys [username]} _ctx]
    (when username [:rf.scope/session {:username username}])))
```

That gives you three things a hand-assembled key can't:

- **Forgetting scope is a registration error** (`:rf.error/resource-missing-scope-policy`), not a silent shared read.
- **Subscriptions re-key on viewer change.** At login, logout or an account switch, the *same* feed subscription points at the new viewer's entry, reading `:idle`/`:loading` during the switch — never the previous user's data.
- **Nil resolution fails closed.** Logged out, the resolver yields `nil`, and the read raises "scope unresolved" rather than falling through to a global read. There is no path from "I forgot the viewer" to "I served someone else's cache."

The cost is more declaration up front than typing a key element. In exchange, a cross-account cache leak becomes an error at registration instead of a silent bug in production. Logout follows directly: instead of TanStack's `queryClient.clear()` (which clears every cached query) or a hand-maintained list of keys to forget, you clear exactly the departing user's scope with `[:rf.resource/clear-scope {:scope old-scope}]`.

### 3. Invalidation is a declared consequence, not a remembered call.

TanStack's invalidation is imperative and lives at the call site: after a write succeeds, you reach into the client and tell it what to forget.

```js
// TanStack: you must remember to do this, in every onSuccess
useMutation({
  mutationFn: favorite,
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['article', slug] })
    queryClient.invalidateQueries({ queryKey: ['feed'] })  // ...and don't forget this one
  },
})
```

The trouble is that it's *forgettable*. Nothing connects the write to the reads it breaks except your memory and code review. Add a third read that depends on the same data six months later, and nothing points at the `onSuccess` that now needs a third line.

re-frame2 declares the consequence *on the mutation registration*, by [tag](glossary.md#cache-tag), once:

```clojure
(rf/reg-mutation :realworld/favorite
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :invalidates   (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/global
                      :tags  #{[:article slug] [:article-list]}}
                     {:scope {:from-db :realworld/session}
                      :tags  #{[:feed]}}])}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :post :url (str "/api/articles/" slug "/favorite")} :decode :json}))
```

Against **RTK Query** this is a match: `providesTags` / `invalidatesTags` is the same declared idea, and re-frame2 adds per-target **scoped descriptors** (the second map above refreshes `[:feed]` only in the session scope) and records the invalidation on the event that caused it. Against *TanStack* the difference is real: keeping reads correct after a write is a property of the write, with nothing to remember at the call site. The cost is indirection — invalidation is declared on the registration, not next to the call. In exchange, Xray shows which write staled which reads, and adding a new read only means tagging it: the writes that should refresh it already invalidate that tag.

One sharp edge, the inverse of TanStack's: invalidation is **scoped**, so a `:rf.scope/global` mutation invalidating a `[:feed]` tag that lives in a session scope matches *nothing* and refreshes *nothing*. TanStack invalidates `['feed']` regardless of whose it is; re-frame2 won't cross a scope boundary by accident. Name the matching scope per descriptor (as above); in dev builds `:rf.warning/mutation-scope-mismatch` fires when a write misses this way. For a deliberate "invalidate this tag wherever it lives", a descriptor sets `:cross-scope? true`, which must carry a cause and is marked in the trace.

### 4. Optimistic rollback restores the runtime's inverse, not your context.

In TanStack's optimistic pattern you own the rollback: in `onMutate` you snapshot the previous data into a `context` and write the optimistic value, and in `onError` you restore the snapshot yourself. SWR's `optimisticData` + `rollbackOnError` is the same shape with less boilerplate. It works, but a hand-written inverse can drift from the forward patch.

In re-frame2 you declare *only the forward change*, and the runtime records the inverse, so a rollback restores *exactly* the entry that existed:

```clojure
:optimistic-tags
(fn [{:keys [slug]}]
  [{:scope :rf.scope/global
    :tags  #{[:article slug]}
    :patch (fn [data] (favorite-patch true slug data))}])
```

(`favorite-patch` flips the heart inside a cached entry; [Part 4 of the tutorial](tutorial/04-mutations-and-invalidation.md#make-the-heart-flip-before-the-reply) defines it.) There are two forward forms: `:optimistic` patches **exact** keys (like `:patches`, without the `result` argument), and `:optimistic-tags` patches **every** entry carrying a tag in its scope — the favorite control, the detail page, every list and the session feed flip at once, without enumerating keys. Both fail closed: a `{:from-db …}` scope resolving to `nil` *drops* that target rather than writing globally, so an optimistic write can't leak across viewers either.

The behaviour that is genuinely different is the **contested rollback**: a concurrent write lands on the same entry between your optimistic apply and your failure.

TanStack's documented cache recipe restores your captured `context` regardless. In a headless run on `@tanstack/query-core` 5.102.8 with two overlapping favorite mutations, each with an `onMutate` snapshot and an `onError` restore, the newer one settles and its refetch returns a count of 7; the older one then fails, and its `onError` writes the snapshot count of 1 back over the 7 until its own `onSettled` refetch repairs it. TanStack has one way around that: the **`variables` pattern** renders the pending mutation's variables in the UI and writes nothing to the cache, so there is nothing to clobber. **`scope: { id }`** on `useMutation` doesn't close the window. It runs the mutation *functions* sharing that id one at a time, so the second request waits for the first to settle, but the second mutation's `onMutate` still runs straight away: its snapshot captures the first one's optimistic value, and if it fails, its `onError` writes that stale snapshot over the newer value all the same.

re-frame2 keeps the optimistic write in the cache and settles the contest there. The default `:on-conflict :invalidate` declines to restore a snapshot that is now stale; it marks the entry stale instead, so the read path refetches the server's value. It compares a per-entry `:revision` recorded at apply time, so the decision is deterministic, not a wall-clock race. `:force` restores anyway (with a tooling warning), for a single writer. re-frame2 would rather refetch the truth than restore a value it knows is contested.

### 5. The cache lives in a frame, not a process-global client.

A `QueryClient` is one cache per app. That's fine in the browser. On the server it's a hazard: a process-global cache is a place where one request's data can surface in another's. TanStack's answer is careful per-request dehydration; you opt into isolation.

In re-frame2 the cache lives in [runtime-db](../core/glossary.md#runtime-db), one half of a [frame](../core/glossary.md#frame) — one running instance of your app. On the server, **each request renders in its own frame**, so request isolation is the default rather than a discipline you maintain. (The cache is not in your [app-db](../core/glossary.md#app-db), so an ordinary event handler can't overwrite it by accident.) Blocking route resources are the render's wait point, settled entries serialize with the page (sensitive slots redacted by [data classification](../core/glossary.md#data-classification)), and [hydration](../ssr/glossary.md#hydration) reinstalls them under the same freshness rules — a still-fresh entry isn't refetched, so there's no duplicate-fetch flash on first paint, and hydration refuses to cross scopes.

## Where do auth headers go?

Every example here hits a bare `/api/...` URL, so where do auth headers, tracing headers, the base URL and tenant headers go? **Not on the resource.** A resource's (or mutation's) request fn describes the domain request only — method, url, params, body, `:decode`. Cross-cutting decoration belongs to the [managed-HTTP](../async/http.md) layer underneath: a `reg-http-interceptor` registered once on the frame decorates *every* `:rf.http/managed` request it issues — reads, writes and plain managed calls alike ([Interceptors: stamp every request once](../async/http-going-further.md#interceptors-stamp-every-request-once)):

```clojure
(rf/reg-http-interceptor :realworld/auth
  {:before (fn [ctx]
             (let [token (some-> (rf/app-db-value (:frame ctx)) :auth :token)]
               (cond-> ctx
                 token (assoc-in [:request :headers "Authorization"]
                                 (str "Token " token)))))})
```

The interceptor reads frame state through `(rf/app-db-value (:frame ctx))`, the frame the request belongs to, so it works under SSR and with several frames. And a resource that needs auth needs **no** per-resource opt-in: register the interceptor once and every read is decorated.

One more difference from TanStack, whose queries retry by default: in re-frame2 **retries are opt-in for reads and writes alike**. A resource or mutation retries only when the args its request fn returns declare `:retry`. That matters most for writes, because retrying a write can duplicate its side effects.

## When to reach for resources at all

In React a query library is the default because it's the only server-state tool on offer. In re-frame2 it's one of several: a handful of uncached reads want a plain [managed HTTP request](../async/http.md) and a small app-db slice, and login-style commands want a [machine](../machines/concepts.md) driving a write. Reach for resources when cached server reads start multiplying — [When not to use resources](index.md#when-not-to-use-resources) has the short table, and [Where should this value live?](../core/where-state-lives.md) the full decision table.

## The full parity scorecard

The mapping above is the vocabulary; this is the full reference card, each row tagged with its status:

| Status | Meaning |
|---|---|
| **Supported** | Shipped in the reference implementation (`re-frame.resources`) and pinned by tests. |
| **Different by design** | A capability the query libraries have, expressed differently here on purpose — usually because re-frame2 already has a more general mechanism (the subscription graph, the event pipeline) that covers it. |
| **Out of scope** | Not a resources concern — a different artefact, or a non-goal. |
| **Not built** | A real parity gap, with no shipped contract. |

| Dimension | TanStack Query | RTK Query | SWR | re-frame2 resources | Status |
|---|---|---|---|---|---|
| **Keyed cache** | `queryKey` array | endpoint + serialized arg | string/array key | `[scope resource-id canonical-params]` triple; params are canonicalized, and schema-validated once the schemas artefact is loaded | **Supported** |
| **Cache home** | `QueryClient` (module-level, app-global) | Redux store slice | module-level `SWRConfig` cache | framework-owned **runtime-db** partition of *each frame* (`[:rf.db/runtime :rf.runtime/resources]`); never your app-db, never process-global | **Different by design** |
| **Staleness (SWR semantics)** | `staleTime`; stale-while-revalidate | `keepUnusedDataFor` + refetch triggers | always SWR; `dedupingInterval` | `:stale-after-ms`; `:loaded` entries serve immediately, refetch on next *ensure* when stale. **Default diverges**: TanStack's `staleTime` defaults to `0`; `:stale-after-ms` absent defaults to *never* time-stale | **Supported** |
| **Request deduplication** | in-flight queries coalesce | automatic | `dedupingInterval` window | `ensure` of an in-flight key joins the existing request (one fetch, two owners) | **Supported** |
| **Fresh-skip (cache hit, no fetch)** | fresh query returns cached, no fetch | served from store | within deduping window | fresh `:loaded` `ensure` serves the cached value, attaches the owner, fetches nothing | **Supported** |
| **Garbage collection** | `gcTime` (was `cacheTime`); a fresh `gcTime` timer starts when the last observer leaves | `keepUnusedDataFor` after last subscriber | revalidation-driven; weak retention | `:gc-after-ms` is the interval of an advisory GC check, armed when the entry settles (and re-armed when a new owner revives an owner-free entry). On fire it removes the entry only if it is still owner-free and not in flight; otherwise it re-arms for another interval. Releasing the last owner leaves the pending check armed, so an owner-free entry goes at that next check — at most one interval after the release, rather than a full interval from it. **Default value matches**: absent `:gc-after-ms` is `300000` (5 min, TanStack's `gcTime` default); `:gc-after-ms :never` is the explicit unowned-pinning opt-out. **The retention guarantee does not** | **Supported** |
| **Scope / cache identity boundary** | viewer id is one `queryKey` segment, by convention | baked into arg by convention | part of the key, by convention | **scope is a required, structural key segment**; forgetting it is a loud registration/subscription error, never a silent cross-viewer leak | **Different by design** |
| **Invalidation** | `queryClient.invalidateQueries({queryKey})`, imperative | tag-based (`providesTags` / `invalidatesTags`) | `mutate(key)`, imperative | tag-based, declared as a *consequence of a named mutation*; scoped by default; per-target scoped descriptors; cross-scope is an audited opt-in | **Supported** |
| **Mutations** | `useMutation` | `builder.mutation` | bound `mutate` / `useSWRMutation` | `reg-mutation` + `:rf.mutation/execute`; instance-keyed pending/result/error state; same managed-HTTP transport as reads | **Supported** |
| **Mutation consequences (patch/populate/seed)** | `setQueryData` in `onSuccess` | `onQueryStarted` + `updateQueryData` | `mutate` with `optimisticData`/`populateCache` | declarative `:patches` / `:populates` / `:removes` from the reply, then tag invalidation, with explicit timing | **Supported** |
| **Mutation completion continuation** | `onSuccess` / `onError` callbacks | lifecycle callbacks | promise resolution | call-site `:reply-to` **event** (not a callback); fires only for the accepted terminal reply | **Supported** |
| **Projection / `select`** | `select` option | `selectFromResult` | derived in component | no `:select` key — projections are ordinary subscriptions layered over `[:rf.resource/data …]` | **Different by design** |
| **Optimistic updates + rollback** | `onMutate` snapshot + `onError` rollback | `updateQueryData` + `undo` patch | `optimisticData` + `rollbackOnError` | `:optimistic` (exact-target) / `:optimistic-tags` (tag-addressed) plan applied pre-request; runtime records the inverse; deterministic commit / rollback / reconcile on settle, with `:on-conflict` governing a contested rollback | **Supported** |
| **Polling / refetch interval** | `refetchInterval` | `pollingInterval` | `refreshInterval` | `:poll-interval-ms` — revalidates every N ms while the entry is *actively owned* and the tab is visible | **Supported** |
| **Refetch on window focus / reconnect** | `refetchOnWindowFocus` / `refetchOnReconnect` | `refetchOnFocus` / `refetchOnReconnect` | `revalidateOnFocus` / `revalidateOnReconnect` | the frame's `:revalidate-on #{:focus :reconnect}` config key — the frame lifecycle installs, reconciles and removes the host listeners; refetches only entries that are *stale AND owned* | **Supported** |
| **Prefetch / route-plan preload** | `queryClient.prefetchQuery` / `<Link prefetch>` (Router) | `prefetch` endpoint action | `preload` | a single resource warms with an ownerless `ensure`; `[:rf.route/prefetch {:to :route/article :params {…}}]` warms a whole destination, running its **effective** parent-to-leaf plan in warm mode — every ensure ownerless under cause `[:route-prefetch <route-id>]`, `:blocking?` inert, no route state, guards, or `:on-match`. Frame-scoped, under ordinary freshness / dedupe / GC, so a later activation joins the warmed work and attaches the real owner. `[route-link {… :prefetch :intent}]` dispatches it on hover, focus, or touch | **Supported** |
| **Infinite / load-more** | `useInfiniteQuery` | `infiniteQuery` (recent) | `useSWRInfinite` | `:infinite true` + `:next-page-param` — one scoped feed entry accumulates an ordered page vector; a causal `:rf.resource/load-more` extends it; `:rf.resource/items` is the merged read | **Supported** |
| **Keep-previous-data while paging** | `placeholderData: keepPreviousData` | n/a (manual) | `keepPreviousData` | `:keep-previous?` on a route's resource entry or an `:rf.resource/ensure` payload — a property of the ensure, not a `reg-resource` key; `:rf/resource` projects `:previous-data` / `:previous-key` | **Supported** |
| **SSR / hydration** | `dehydrate` / `HydrationBoundary` | `getRunningQueries` + preload | fallback data | per-request frames; blocking route resources are the render wait point; allowlist projection serialized + hydrated under freshness rules | **Supported** |
| **Devtools / observability** | React Query Devtools | RTK devtools (Redux) | external | Xray Resources panel + a `:rf.resource/*` / `:rf.mutation/*` trace family; static registry, live instance table, scope-audit + orphaned-owner lints | **Supported** |
| **Normalized / GraphQL cache** | normalizr (external) | partial | external | Apollo/Relay-class — not a resources concern; resources use HTTP only | **Out of scope** |
| **Offline persistence / cross-tab** | persister plugins | n/a | external | not built | **Not built** |

Every "Supported" row is pinned by tests in the reference implementation.

**Prefetch is narrower than `<Link prefetch>`, on purpose.** `:intent` is the
only mode: there is no global default, no render or viewport preloading, no
hover-delay setting, no separate preload cache, and no prefetch-stale clock. Router
libraries need those modes because a preload there has its own lifetime to manage;
a warmed entry here *is* an ordinary resource entry, so `:stale-after-ms` and
`:gc-after-ms` already decide whether the work is still worth having, and an
unclaimed warm entry has no owner, so GC collects it. [Warming a destination before
the click](../routing/concepts.md#warming-a-destination-before-the-click) covers the
routing side.

**The gaps.** The bottom two rows are not covered. A **normalized / GraphQL cache**
(Apollo, Relay, normalizr) is not a resources concern: resources cache HTTP reads by
key. **Offline persistence and cross-tab broadcast** are not built.

## The public API, at a glance

Every symbol sits in one of three lanes, and the lane tells you what it does (the same split [the model](concepts.md#three-lanes--registering-causing-projecting) teaches):

| Lane | What it is | Symbols | Who calls it |
|---|---|---|---|
| **Registration** (functions, at boot) | Declare a handler once — it does not fetch or read | `rf/reg-resource`, `rf/reg-mutation`, `rf/reg-resource-scope`; each is undone by the kind-keyed `(rf/clear :resource id)`, `(rf/clear :mutation id)`, `(rf/clear :resource-scope id)` | app code, once, at startup |
| **Commands** (causal event vectors, dispatched) | *Cause* work — they are not reads | `[:rf.resource/ensure …]`, `[:rf.resource/refetch …]`, `[:rf.resource/invalidate-tags …]`, `[:rf.resource/release-owner …]`, `[:rf.resource/clear-scope …]`, `[:rf.resource/remove …]`, `[:rf.resource/load-more …]`, `[:rf.mutation/execute …]`, `[:rf.mutation/clear …]` | routes, events, machines |
| **Reads** (passive subscription vectors) | Project runtime state — the only lane a view touches | `[:rf/resource …]`, `[:rf.resource/data …]`, `[:rf.resource/items …]`, `[:rf.resource/infinite-state …]`, `[:rf/mutation …]`, and the narrower single-fact subs | views, via `subscribe` |

All of it comes from the optional `day8/re-frame2-resources` artefact, and is absent from an app that never requires it. A view reads through the subscriptions only. `rf/resource-state` and the `:rf/resource` entry of `rf/handler-meta` are for tools and tests: they return a one-shot snapshot and never re-render.

Three commands need a sentence each, because a query-library user reaches for them and the mapping isn't obvious:

- **`:rf.resource/refetch`** is the imperative bypass — TanStack's `refetch()` / SWR's `mutate(key)` with no data. It forces a fetch regardless of freshness, carrying a `:cause` but usually *no* `:owner` (a manual refresh keeps no owner).
- **`:rf.resource/remove`** evicts one exact entry (scope + resource + params), eagerly, regardless of GC policy — the surgical counterpart to letting GC reclaim it.
- **`:rf.resource/release-owner`** drops an owner your app attached on an `ensure`. Forgetting it leaves an orphaned owner, which [Xray](../core/glossary.md#xray) flags.
