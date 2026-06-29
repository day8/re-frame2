# Coming from TanStack Query

If you've shipped a React app in the last few years, you've almost certainly reached for TanStack Query (née React Query). You know the move: stop hand-rolling `useEffect` + loading flags, declare a keyed async read, and let a library own the cache, the dedupe, the staleness, and the refetch. SWR and RTK Query are the same instinct wearing different hats.

re-frame2's [resources](concepts.md) capability is that instinct, ported to a data-oriented Clojure world. The core idea transfers almost completely: **a keyed cache of server reads, with staleness, request deduplication, tag-based invalidation, and garbage collection.** If you hold that model, you already understand 80% of resources. This page maps the vocabulary so the remaining 20% lands fast — and then spends most of its words on the handful of places re-frame2 deliberately walked away from the TanStack design, because *that's* the interesting part.

The honest framing up front: TanStack Query is a *hook* library. Its primitives live inside a component's render and reach back out into the cache. re-frame2's primitives live in the [event](../core/glossary.md#event)/[subscription](../core/glossary.md#subscription) substrate, and the cache is just another piece of [runtime-db](../core/glossary.md#runtime-db). So the cache *behaviour* maps cleanly; the *seams* — where the cache touches your components — are drawn in a different place on purpose. Most of the friction you'll feel moving over is one fact restated three ways: **a read never causes a fetch.**

## The mapping

| TanStack Query | re-frame2 | Notes |
|---|---|---|
| `useQuery({ queryKey, queryFn })` | `reg-resource` (register) + `[:rf.resource/state …]` (read) + a *cause* (fetch) | One hook splits into three jobs. See [Where it diverges](#where-it-diverges). |
| `queryKey: ['article', slug]` | `:params` (the `{:slug …}` map) under a [`:scope`](glossary.md#scope) | Identity is `[scope resource-id canonical-params]`. The user/tenant segment is a *separate, required* axis, not just another key element. |
| `queryFn` (returns a promise) | the third slot of `reg-resource` — `(fn [params ctx] {:request … :decode …})` | Returns request *data*, not a promise. The runtime owns `fetch`. |
| `data`, `error`, `status`, `isPending`, `isFetching` | `:rf.resource/state` view-model: `:data` `:error` `:status` `:loading?` `:fetching?` `:has-data?` | Five statuses: `:idle` `:loading` `:fetching` `:loaded` `:error`. `:error` is *first-load only*. |
| `staleTime` | `:stale-after-ms` | Same semantics: fresh window, then refetch on next access. |
| `gcTime` (was `cacheTime`) | `:gc-after-ms` | Reclaim after the entry goes *owner-free* for this long. |
| an *observer* (a mounted `useQuery`) keeps data alive | an **[owner](glossary.md#owner--cause)** (a route, machine, or explicit lease) | Owner = liveness lease. Decoupled from any component mounting. |
| `enabled: false` / conditional queries | route `:resources` `:when` predicate (or simply: don't fire the cause) | A read with no cause sits at `:idle` — that's the "disabled" state, for free. |
| `select: (data) => …` | a plain [subscription](../core/glossary.md#subscription) over `[:rf.resource/data …]` | No `:select` key. You already have a memoised [derivation graph](../core/glossary.md#the-derivation-graph). |
| `placeholderData: keepPreviousData` | route `:keep-previous?` (+ `:previous-data` in the view-model) | Same anti-flash behaviour for pagination. |
| `refetchOnWindowFocus` / `refetchOnReconnect` | `(rf/install-revalidation-listeners! frame-id)` | Opt-in per [frame](../core/glossary.md#frame); refetches only stale *and* owned entries. |
| `refetchInterval` | `:poll-interval-ms` | Owner-driven, auto-pauses on hidden tab. No `setInterval`. |
| `queryClient.invalidateQueries({ queryKey })` | a [mutation](glossary.md#mutation)'s declared `:invalidates` (by [tag](glossary.md#cache-tag)) | Declared on the write, not called imperatively in `onSuccess`. |
| `queryClient.setQueryData(key, data)` | a mutation's `:populates` / `:patches` | `:populates` seeds a key; `:patches` transforms one. |
| `queryClient.removeQueries(key)` | a mutation's `:removes`, or `[:rf.resource/remove …]` | Evict an exact key. |
| `queryClient.clear()` | `[:rf.resource/clear-scope …]` | But you clear *one scope*, not the whole cache — that's the point. |
| `useMutation({ mutationFn })` | `reg-mutation` (register) + `[:rf.mutation/execute …]` (run) + `[:rf.mutation/state …]` (read) | Same three-way split as queries. Keyed by an **instance**. |
| `onMutate` + rollback `context` / `onError` | `:optimistic` / `:optimistic-tags` (forward) — runtime records the inverse | You declare the forward change only; rollback is automatic. |
| `useInfiniteQuery` | `:infinite true` on a resource | One scoped entry holding a vector of pages. |
| `getNextPageParam(lastPage, allPages)` | `:next-page-param` | Terminal is **`nil`** (not `undefined`). |
| `fetchNextPage()` | `[:rf.resource/load-more …]` (a cause) | Ownerless — it extends the entry the route already owns. |
| `data.pages.flatMap(p => p.items)` | `[:rf.resource/items …]` | The merged list is framework-owned and memoised, not re-derived in render. |
| `QueryClientProvider` (one client per app) | a [frame](../core/glossary.md#frame) (cache lives in its runtime-db) | On the server, *one frame per request* — no process-global cache to leak across users. |
| `<HydrationBoundary>` / `dehydrate` | SSR projection + [hydration](../ssr/glossary.md#hydration) under the same freshness rules | A still-fresh hydrated entry isn't refetched; scopes must agree. |

A note for the **SWR** crowd: `useSWR(key, fetcher)` is the `useQuery` row; `mutate(key)` is `invalidateQueries`; bound `mutate` with `optimisticData` + `rollbackOnError` is the `:optimistic` / rollback row; `keepPreviousData` is `:keep-previous?`. SWR's `revalidateOnFocus` is the revalidation-listeners row. The mental model is identical; SWR just gives you a smaller surface.

And for **RTK Query**: you're already closest to home, because RTK Query also makes you *declare* the cache graph up front (`createApi` with endpoints, `providesTags` / `invalidatesTags`) instead of calling `invalidateQueries` ad hoc. re-frame2's [tag](glossary.md#cache-tag) invalidation is RTK Query's `providesTags`/`invalidatesTags` with one upgrade — the invalidation is recorded on the causal [event](../core/glossary.md#event) record, so you can see *which write* staled *which read* in [Xray](../core/glossary.md#xray). RTK Query's `keepUnusedDataFor` is `:gc-after-ms`; its auto-generated hooks have no analogue (resources don't code-gen — you write the read and the cause), and its endpoint *is* roughly a resource registration.

## Where it diverges

The table gets you fluent. These five decisions are why the table isn't a one-to-one isomorphism — each is a place re-frame2 paid a small ergonomic cost for a structural property TanStack can't offer from inside a hook.

### 1. A read never fetches. One hook becomes three jobs.

This is the big one, and everything else is downstream of it. `useQuery` does three things at once: it *declares* the query, it *triggers* the fetch (on mount), and it *reads* the result (on every render). That bundling is convenient and it's why the seam lands where it does — the fetch is a side effect hiding inside your render.

re-frame2 splits those into three lanes that never blur:

- **Register** — `(rf/reg-resource …)` at boot. Teaches the runtime *how* to fetch. Fetches nothing.
- **Cause** — a route entry, an event, or a [machine](../machines/glossary.md#machine) dispatches `[:rf.resource/ensure …]`. This is what makes a fetch happen.
- **Project** — `@(subscribe [:rf.resource/state …])` in a [view](../core/glossary.md#view). Passive. Reads the cache; never triggers a fetch.

The cost: you write a cause that `useQuery` gave you for free. The payoff: the view is now a pure function of the cache. The *same* view renders on the server, in a unit test, or after a cache hit — with **no network call hiding in the render**. That's also why "I registered the resource but my view is a permanent skeleton" almost always means *you forgot the cause*, not the read. A subscription that finds no entry reads `:idle` and stays there until something causes the fetch. (TanStack's `enabled: false` is this same idea — a read that doesn't fetch — except here it's the default shape rather than a flag.)

If this sounds like extra ceremony, notice what it buys at the route layer: a page declares its data needs in route metadata (`:resources [{:resource … :blocking? true}]`), the fetch starts *before* the component mounts, and a `:blocking?` read doubles as the SSR wait point. TanStack's render-triggered fetch can't start until React has rendered the component once — the classic mount-then-fetch waterfall. Causing the fetch from the route entry sidesteps that entirely.

### 2. Scope is a required key axis, not a key segment you remember.

In TanStack, the user or tenant id is just another element you stick in the `queryKey`: `['feed', userId]`. It works — until the one call site where you write `['feed']` and forget the id. Now every user's feed shares a cache entry, and the bug is silent: the second user sees the first user's data, no error, no warning, just a quiet cross-account leak. The footgun is structural — the key is positional, untyped, and assembled by hand at every call site.

re-frame2 makes that leak *unrepresentable*. A cache entry's identity is a triple — `[scope resource-id canonical-params]` — and [`:scope`](glossary.md#scope) is a **required** registration key with no default. Params say *which* article; scope says *whose* cache. You either claim `:scope :rf.scope/global` ("everyone gets the same answer" — an explicit, auditable claim) or you name a resolver that derives the viewer from app-db:

```clojure
(rf/reg-resource-scope :realworld/session
  {:inputs  {:username [:db [:auth :user :username]]}
   :resolve (fn [{:keys [username]} _ctx]
              (when username [:rf.scope/session {:username username}]))})
```

Three properties fall out that a hand-assembled key can't give you:

- **Forgetting scope is a registration error** (`:rf.error/resource-missing-scope-policy`), not a silent shared read. "I didn't think about whether this is user-scoped" is impossible to express.
- **Subscriptions re-key on viewer change.** At login/logout/account-switch the *same* feed subscription re-points to the new viewer's entry, reading `:idle`/`:loading` during the switch — never the previous user's data.
- **Nil resolution fails closed.** Logged out, the resolver yields `nil`; the read raises "scope unresolved" rather than falling through to a global read. There is no path from "I forgot the viewer" to "I served someone else's cache."

The honest cost: more upfront declaration than typing a key element. The payoff is that the entire class of cross-account cache leaks — the one that turns into a security incident — moved from "silent bug you find in production" to "loud failure at registration." And logout follows directly: instead of TanStack's `queryClient.clear()` (which nukes the *global* cache too) or a hand-maintained list of keys to forget, you clear exactly that one scope with `[:rf.resource/clear-scope {:scope old-scope}]`. The leak boundary you declared *is* the teardown boundary.

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

The problem isn't that it's verbose — it's that it's *forgettable*. Nothing connects the write to the reads it breaks except your memory and code review. Add a third read that depends on the same data six months later and there's no compiler, no type, nothing pointing at the `onSuccess` that now needs a third line.

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

This is RTK Query's `invalidatesTags`/`providesTags`, and the divergence from *TanStack* specifically is that there's nothing to remember and nothing to forget — keeping reads honest after a write is a property of the write, visible on the event record. The cost is the indirection: invalidation happens "somewhere else" (the registration) rather than right next to the call. The payoff is that the *trace* shows you the causal chain — this write staled these reads — in Xray, and adding a new read just means tagging it; the writes that should refresh it already will, because they invalidate the tag, not an enumerated list of keys.

One sharp edge worth internalising early, because it's the inverse of TanStack's footgun: invalidation is **scoped**, so a `:rf.scope/global` mutation invalidating a `[:feed]` tag that lives under a session scope matches *nothing* and silently refreshes *nothing*. TanStack would happily invalidate `['feed']` regardless of who owns it; re-frame2 won't cross a scope boundary by accident. The fix is to name the matching scope per descriptor (as above); a dev-only `:rf.warning/mutation-scope-mismatch` fires the moment a write misses, so the silent case becomes loud. The deliberately-broad "invalidate this tag wherever it lives" is a separate, *audited* `:cross-scope? true` escape that must carry a cause.

### 4. Optimistic rollback restores the runtime's inverse, not your context.

TanStack's optimistic pattern hands you the keys and trusts you: in `onMutate` you snapshot the previous data into a `context`, write the optimistic value, and in `onError` you restore the snapshot yourself. SWR's `optimisticData` + `rollbackOnError` is the same shape with less boilerplate. It works, but you own the inverse, and a hand-rolled inverse can drift from the forward patch.

re-frame2 inverts the responsibility: you declare *only the forward change*, and the runtime records the inverse for you, so a rollback restores *exactly* the entry that existed.

```clojure
:optimistic-tags
(fn [{:keys [slug]}]
  [{:scope :rf.scope/global
    :tags  #{[:article slug]}
    :patch (fn [article] (update-favorite article true))}])
```

Two forward forms: `:optimistic` patches **exact** keys (the twin of `:populates`), `:optimistic-tags` patches **every** entry carrying a tag in its scope (the cross-view-consistency case you can't enumerate by key — the heart, the detail page, every list, and the session feed flip at once). Both fail closed: a `{:from-db …}` scope resolving to `nil` *drops* that target rather than writing globally, so an optimistic write can't leak across viewers either.

The genuinely *different* behaviour — not just a different spelling — is the **contested rollback**. Suppose a concurrent write lands on the same entry between your optimistic apply and your failure. TanStack restores your captured `context` unconditionally, which can clobber the newer write's value. re-frame2's default `:on-conflict :invalidate` *declines* to restore a now-stale inverse and instead marks the entry stale so the read path refetches the authoritative value. It compares a per-entry `:revision` recorded at apply time, so the decision is deterministic, not a wall-clock race. `:force` is the single-writer escape that restores anyway (with a tooling warning). This is a real semantic departure: re-frame2 would rather refetch the truth than restore a value it knows is contested.

### 5. The cache lives in a frame, not a process-global client.

A `QueryClient` is conceptually one cache per app. That's fine in the browser. On the server it's a hazard — a process-global cache is, by construction, a place where one request's data can surface in another's. TanStack's answer is careful per-request dehydration; you opt into isolation.

In re-frame2 the cache is a subsystem of [runtime-db](../core/glossary.md#runtime-db), which is a half of a [frame](../core/glossary.md#frame) — one running instance of your app. On the server, **each request renders in its own frame**, so request isolation isn't a discipline you maintain; it's the default topology. (It also means the cache is deliberately *not* your [app-db](../core/glossary.md#app-db) — an ordinary event handler can't reach in and corrupt it; same in / out discipline as the rest of the framework, the storage just moved next door.) Blocking route resources are the render's wait point, settled entries serialize with the page (sensitive slots redacted by [data classification](../core/glossary.md#data-classification)), and [hydration](../ssr/glossary.md#hydration) reinstalls them under the same freshness rules — a still-fresh entry isn't refetched, so there's no duplicate-fetch flash on first paint, and hydration refuses to cross scopes.

## Where do auth headers go?

Every example here hits a bare `/api/...` URL, which raises the obvious migrant question: where do auth headers, tracing headers, the base URL, and tenant headers live? **Not on the resource.** A resource's (or mutation's) `:request` fn describes the *domain* request only — method, url, params, body, `:decode`. Cross-cutting decoration belongs to the [managed-HTTP](../async/http.md) layer the resource lowers through, applied once by a frame-registered `reg-http-interceptor` that decorates *every* `:rf.http/managed` request the frame issues — reads, writes, and plain managed calls alike ([Spec 016 §Request decoration belongs to the managed-HTTP seam](../../spec/016-Resources.md#request-decoration-belongs-to-the-managed-http-seam-not-the-resource-declaration)):

```clojure
(rf/reg-http-interceptor :realworld/auth
  {:before (fn [ctx]
             (let [token (some-> (rf/app-db-value (:frame ctx)) :auth :token)]
               (cond-> ctx
                 token (assoc-in [:request :headers "Authorization"]
                                 (str "Token " token)))))})
```

Two details earn their keep. The interceptor reads frame state through `(rf/app-db-value (:frame ctx))` — the carried-frame-correct read, never an ambient `db` — so it stays SSR-safe and frame-isolated. And a resource that needs auth needs **no** per-resource opt-in: register the interceptor once and every read is decorated. (One asymmetry from TanStack, where retry is uniform: re-frame2's default retry policy is read-focused — **write retries stay opt-in**, because retrying a write can duplicate side effects, so a mutation arms `:retry` only when its own `:request` declares it.)

## When to reach for resources at all

A query library is the obvious default in React because it's the *only* server-state machinery on offer. In re-frame2 it's one tool among several, and not always the right one:

> **Simpler than a resource.** Two cases where reaching for the resources artefact is over-engineering:
>
> - **A handful of reads, no caching story.** A [managed HTTP request](../async/http.md) plus a small app-db slice is less machinery and entirely idiomatic.
> - **Login and other commands.** Auth is a state machine driving a write — model it as a [machine](../machines/concepts.md), not a cached read.

Reach for resources when cached server reads start multiplying and the per-read bookkeeping — scope, staleness, dedupe, invalidation, GC, SSR — is worth moving into the framework. [Where should this value live?](../core/where-state-lives.md) has the decision table, and resources are one of [the four homes](../core/glossary.md#the-four-homes-where-state-lives) state can take.

## The full parity scorecard

The mapping above is the vocabulary; this is the exhaustive reference card, each row tagged with parity status:

| Status | Meaning |
|---|---|
| **Landed** | Shipped in the reference implementation (`re-frame.resources`) and pinned by tests. |
| **Different by design** | A capability the query libraries have, expressed differently here on purpose — usually because re-frame2 already has a more general mechanism (the subscription graph, the re-frame2 loop) that subsumes it. |
| **Out of scope** | Deliberately not a resources concern — a different artefact, a different phase, or a non-goal. |
| **Deferred (later slice)** | A real parity gap, deliberately held for a later slice, with no shipped contract yet. |

| Dimension | TanStack Query | RTK Query | SWR | re-frame2 resources | Status |
|---|---|---|---|---|---|
| **Keyed cache** | `queryKey` array | endpoint + serialized arg | string/array key | `[scope resource-id canonical-params]` triple; params are schema-validated and canonicalized | **Landed** |
| **Cache home** | `QueryClient` (module-level, app-global) | Redux store slice | module-level `SWRConfig` cache | framework-owned **runtime-db** partition of *each frame* (`[:rf.db/runtime :rf.runtime/resources]`); never your app-db, never process-global | **Different by design** |
| **Staleness (SWR semantics)** | `staleTime`; stale-while-revalidate | `keepUnusedDataFor` + refetch triggers | always SWR; `dedupingInterval` | `:stale-after-ms`; `:loaded` entries serve immediately, refetch on next *ensure* when stale | **Landed** |
| **Request deduplication** | in-flight queries coalesce | automatic | `dedupingInterval` window | `ensure` of an in-flight key joins the existing request (one fetch, two owners) | **Landed** |
| **Fresh-skip (cache hit, no fetch)** | fresh query returns cached, no fetch | served from store | within deduping window | fresh `:loaded` `ensure` serves the cached value, attaches the owner, fetches nothing | **Landed** |
| **Garbage collection** | `gcTime` (was `cacheTime`); GC when no observer | `keepUnusedDataFor` after last subscriber | revalidation-driven; weak retention | `:gc-after-ms` after the **last owner lease** is released; timer re-checks owners before collecting | **Landed** |
| **Scope / cache identity boundary** | viewer id is one `queryKey` segment, by convention | baked into arg by convention | part of the key, by convention | **scope is a required, structural key segment**; forgetting it is a loud registration/subscription error, never a silent cross-viewer leak | **Different by design** |
| **Invalidation** | `queryClient.invalidateQueries({queryKey})`, imperative | tag-based (`providesTags` / `invalidatesTags`) | `mutate(key)`, imperative | tag-based, declared as a *consequence of a named mutation*; scoped by default; per-target scoped descriptors; cross-scope is an audited opt-in | **Landed** |
| **Mutations** | `useMutation` | `builder.mutation` | bound `mutate` / `useSWRMutation` | `reg-mutation` + `:rf.mutation/execute`; instance-keyed pending/result/error state; same managed-HTTP transport as reads | **Landed** |
| **Mutation consequences (patch/populate/seed)** | `setQueryData` in `onSuccess` | `onQueryStarted` + `updateQueryData` | `mutate` with `optimisticData`/`populateCache` | declarative `:patches` / `:populates` / `:removes` from the reply, then tag invalidation, with explicit timing | **Landed** |
| **Mutation completion continuation** | `onSuccess` / `onError` callbacks | lifecycle callbacks | promise resolution | call-site `:reply-to` **event** (not a callback); fires only for the accepted terminal reply | **Landed** |
| **Projection / `select`** | `select` option | `selectFromResult` | derived in component | no `:select` key — projections are ordinary subscriptions layered over `[:rf.resource/data …]` | **Different by design** |
| **Optimistic updates + rollback** | `onMutate` snapshot + `onError` rollback | `updateQueryData` + `undo` patch | `optimisticData` + `rollbackOnError` | `:optimistic` (exact-target) / `:optimistic-tags` (tag-addressed) plan applied pre-request; runtime records the inverse; deterministic commit / rollback / reconcile on settle, with `:on-conflict` governing a contested rollback | **Landed** |
| **Polling / refetch interval** | `refetchInterval` | `pollingInterval` | `refreshInterval` | `:poll-interval-ms` — revalidates every N ms while the entry is *actively owned* and the tab is visible | **Landed** |
| **Refetch on window focus / reconnect** | `refetchOnWindowFocus` / `refetchOnReconnect` | `refetchOnFocus` / `refetchOnReconnect` | `revalidateOnFocus` / `revalidateOnReconnect` | `install-revalidation-listeners!` per frame; refetches only entries that are *stale AND owned* | **Landed** |
| **Infinite / load-more** | `useInfiniteQuery` | `infiniteQuery` (recent) | `useSWRInfinite` | `:infinite true` + `:next-page-param` — one scoped feed entry accumulates an ordered page vector; a causal `:rf.resource/load-more` extends it; `:rf.resource/items` is the merged read | **Landed** |
| **Keep-previous-data while paging** | `placeholderData: keepPreviousData` | n/a (manual) | `keepPreviousData` | `:keep-previous?` on the route/resource; `:rf.resource/state` projects `:previous-data` / `:previous-key` | **Landed** |
| **SSR / hydration** | `dehydrate` / `HydrationBoundary` | `getRunningQueries` + preload | fallback data | per-request frames; blocking route resources are the render wait point; allowlist projection serialized + hydrated under freshness rules | **Landed** |
| **Devtools / observability** | React Query Devtools | RTK devtools (Redux) | external | Xray Resources panel + a `:rf.resource/*` / `:rf.mutation/*` trace family; static registry, live instance table, scope-audit + orphaned-owner lints | **Landed** |
| **Normalized / GraphQL cache** | normalizr (external) | partial | external | Apollo/Relay-class — not a resources concern; transport is HTTP-only this phase | **Out of scope** |
| **Offline persistence / cross-tab** | persister plugins | n/a | external | not built; held for a later slice | **Deferred (later slice)** |

Every "Landed" claim is grounded in [Spec 016 — Resources](../../spec/016-Resources.md) and the reference implementation under `implementation/resources/`.

**The honest gaps — out of scope on purpose.** The bottom two rows are the deliberate non-goals of this HTTP-only phase: **normalized / GraphQL caches** (Apollo / Relay / normalizr) are a separate later artefact gated on a GraphQL phase, not a resources gap ([Spec 016 §What Spec 016 does NOT cover](../../spec/016-Resources.md#what-spec-016-does-not-cover)); and **offline persistence / cross-tab broadcast** is a deferred later slice. Don't let the rest of this page's confidence obscure them.

## The public surface, at a glance

re-frame2 keeps three lanes strictly separate, and the lane a symbol lives in tells you what it does ([Spec 016 §Public API](../../spec/016-Resources.md#public-api)):

| Lane | What it is | The surface | Who calls it |
|---|---|---|---|
| **Registration** (functions, at boot) | Declare a handler once — it does not fetch or read | `rf/reg-resource`, `rf/reg-mutation`, `rf/reg-resource-scope` (+ their `clear-*`) | app code, once, at startup |
| **Commands** (causal event vectors, dispatched) | *Cause* work — they are not reads | `[:rf.resource/ensure …]`, `[:rf.resource/refetch …]`, `[:rf.resource/invalidate-tags …]`, `[:rf.resource/release-owner …]`, `[:rf.resource/clear-scope …]`, `[:rf.resource/remove …]`, `[:rf.resource/load-more …]`, `[:rf.mutation/execute …]` | routes, events, machines |
| **Reads** (passive subscription vectors) | Project runtime state — the only lane a view touches | `[:rf.resource/state …]`, `[:rf.resource/data …]`, `[:rf.resource/items …]`, `[:rf.resource/infinite-state …]`, `[:rf.mutation/state …]`, and the narrower single-fact subs | views, via `subscribe` |

> **The whole `rf/` resource surface is the optional Resources artefact** — late-bound by `day8/re-frame2-resources`, absent from an app that never requires it. The introspection accessors (`rf/resource-meta`, `rf/resource-state`, `rf/resources`) are the tool/test projection lane, not an app-read API; a view that reaches for them instead of a subscription is a category error (they take a one-shot snapshot and never re-render).

Three command names earn a sentence each, because a query-library reader reaches for them and the mapping isn't obvious:

- **`:rf.resource/refetch`** is the imperative bypass — TanStack's `refetch()` / SWR's `mutate(key)` with no data. It forces a fetch regardless of freshness, carrying a `:cause` but usually *no* `:owner` (a manual refresh keeps no lease).
- **`:rf.resource/remove`** evicts one exact entry (scope + resource + params), eagerly, regardless of GC policy — the surgical counterpart to letting GC reclaim it.
- **`:rf.resource/release-owner`** drops an app-minted lease (the matching half of an `:owner` you attached on an `ensure`). Forgetting it is the orphaned-owner leak [Xray](../core/glossary.md#xray) lints for.

---

The throughline: TanStack Query optimises for *getting a cached read onto the screen with one hook call*, and it's superb at that. re-frame2 optimises for *the cache being declared, inspectable data that can't leak and whose every fetch and invalidation is causally recorded* — and accepts a bit more ceremony at the call site to get there. If your app has two reads, that trade isn't worth it (the concepts page is blunt about this: reach for [managed HTTP](../async/http.md) plus a small app-db slice instead). When cached reads start multiplying — and especially when "whose data is this" and "what made this stale" start mattering — the structural version earns its keep.

For the full model built up from a single read, see [Server state: resources](concepts.md). The glossary is at [Resources & Server State glossary](glossary.md).
