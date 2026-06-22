# Resources vs TanStack Query, RTK Query, and SWR

This page is the honest, feature-by-feature comparison between re-frame2 **managed resources** and the JavaScript server-state libraries you may already know — [TanStack Query](https://tanstack.com/query) (the React Query family), [RTK Query](https://redux-toolkit.js.org/rtk-query/overview), and [SWR](https://swr.vercel.app/). The conceptual model is in [Server state: resources](../concepts/server-state.md); this page is the scorecard. It tells you what is matched, what is *deliberately different*, what has *landed*, and — bluntly — what is **deliberately out of scope** for this phase.

The one sentence to carry through every row:

> **Same problem, different physics.** A query library is a cache wired to component lifecycle; a re-frame2 resource is a cache wired to *causes* — routes, events, machines — with the viewer identity promoted from convention to a required structural key.

If you want the design rationale rather than the comparison, read [Inside out: why views come last](inside-out.md) first; the choices below all fall out of that essay.

> **Optional artefact, and pre-alpha.** Server state in re-frame2 is the optional `day8/re-frame2-resources` artefact, and it is **pre-alpha**: the read path, the mutation path, optimistic mutation rollback, active-owner polling, and infinite / load-more feeds have all landed. The remaining query-library features are *deliberately out of scope* for this HTTP-only phase — normalized/GraphQL caches and offline/cross-tab persistence — and each such row below names where that line sits. Nothing here is back-compat-frozen.

## How to read the status column

Each row carries a status. They mean precisely:

| Status | Meaning |
|---|---|
| **Landed** | Shipped in the reference implementation (`re-frame.resources`) and pinned by tests. |
| **Different by design** | A capability the query libraries have, expressed differently here on purpose — usually because re-frame2 already has a more general mechanism (the subscription graph, the event loop) that subsumes it. |
| **Out of scope** | Deliberately not a resources concern — a different artefact, a different phase, or a non-goal. |
| **Deferred (later slice)** | A real parity gap, deliberately held for a later slice, with no shipped contract yet. Used here only for offline persistence / cross-tab broadcast. The earlier parity-tranche proposals — optimistic rollback (EP-0019), active-owner polling (EP-0020), and infinite resources (EP-0021) — have all since *landed* and moved to **Landed**. |

## The parity matrix

| Dimension | TanStack Query | RTK Query | SWR | re-frame2 resources | Status |
|---|---|---|---|---|---|
| **Keyed cache** | `queryKey` array | endpoint + serialized arg | string/array key | `[scope resource-id canonical-params]` triple; params are schema-validated and canonicalized | **Landed** |
| **Cache home** | `QueryClient` (module-level, app-global) | Redux store slice | module-level `SWRConfig` cache | framework-owned runtime partition of *each frame* (`:rf.runtime/resources`); never your app-db, never process-global | **Different by design** |
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
| **Polling / refetch interval** | `refetchInterval` | `pollingInterval` | `refreshInterval` | `:poll-interval-ms` — revalidates every N ms while the entry is *actively owned* and the tab is visible; the third freshness timer beside stale/GC | **Landed** |
| **Refetch on window focus / reconnect** | `refetchOnWindowFocus` / `refetchOnReconnect` | `refetchOnFocus` / `refetchOnReconnect` | `revalidateOnFocus` / `revalidateOnReconnect` | `install-revalidation-listeners!` per frame; refetches only entries that are *stale AND owned* | **Landed** |
| **Infinite / load-more** | `useInfiniteQuery` | `infiniteQuery` (recent) | `useSWRInfinite` | `:infinite true` + `:next-page-param` — one scoped feed entry accumulates an ordered page vector; a causal `:rf.resource/load-more` extends it; `:rf.resource/items` is the merged read (numbered/cursor pages stay ordinary resources with `:keep-previous?`) | **Landed** |
| **Keep-previous-data while paging** | `placeholderData: keepPreviousData` | n/a (manual) | `keepPreviousData` | `:keep-previous?` on the route/resource; `:rf.resource/state` projects `:previous-data` / `:previous-key` | **Landed** |
| **SSR / hydration** | `dehydrate` / `HydrationBoundary` | `getRunningQueries` + preload | fallback data | per-request frames; blocking route resources are the render wait point; allowlist projection serialized + hydrated under freshness rules; fresh hydrated entries are not re-fetched | **Landed** |
| **Devtools / observability** | React Query Devtools | RTK devtools (Redux) | external | Xray Resources panel + a `:rf.resource/*` / `:rf.mutation/*` trace family; static registry, live instance table, work-ledger table, route/resource graph, scope-audit + orphaned-owner lints | **Landed** |
| **Normalized / GraphQL cache** | normalizr (external) | partial | external | Apollo/Relay-class — not a resources concern; transport is HTTP-only this phase | **Out of scope** |
| **Offline persistence / cross-tab** | persister plugins | n/a | external | not built; held for a later slice | **Deferred (later slice)** |

Every "Landed" claim above is grounded in [Spec 016 — Resources](../../../spec/016-Resources.md) and the reference implementation under `implementation/resources/`. The rest of this page expands the rows that carry the most surprise for someone arriving from a query library.

## Where the cache lives, and why it is per-frame

A query library keeps one cache for the running app: TanStack Query's `QueryClient`, RTK Query's store slice, SWR's module cache. That is fine for a single-user SPA, and a liability the moment two principals share a process — most sharply on the server, where one Node process renders for every user at once.

re-frame2 puts the cache in the framework-owned *runtime partition* of **each frame** — one running instance of your app — at `:rf.runtime/resources`, never in your app-db. Two consequences fall out:

- **SSR cannot leak by construction.** Each server request renders in its own frame, so there is no process-global cache to bleed user A's articles into user B's render. A query library reaches the same safety only by spinning up a fresh client per request and being careful never to share it.
- **An ordinary event handler can't corrupt the cache.** It lives in the runtime partition, not app-db, so a careless `assoc` in a `:db` handler can't wipe it. You read it through subscriptions and change it only through events.

This is the [Server state: resources](../concepts/server-state.md#the-cache-you-dont-own) "cache you don't own" model. It is *different by design*, not merely a different default.

## Scope: the leak boundary the query libraries leave to you

This is the single biggest difference, and the row most worth internalising.

In every query library the viewer's identity is a *convention*: you remember to put the user id (or tenant, locale, impersonation marker) into the `queryKey`. Forget it once and one user silently reads another's cache — a bug with no error and no stack trace, surfacing only as a support ticket.

re-frame2 makes scope the **first, required segment of the cache key** and refuses to default it:

```clojure
;; Adapted from spec/016-Resources.md — the registration gate
(rf/reg-resource :realworld/feed
  {:params-schema [:map [:page {:optional true} [:maybe :int]]]
   :scope         {:from-db :realworld/session}   ;; REQUIRED — no default
   :tags          (fn [_ _] #{[:feed]})}
  (fn [{:keys [page]} _ctx] ...))
```

- A `reg-resource` with **no** `:scope` is a loud registration error (`:rf.error/resource-missing-scope-policy`) — "I forgot this read is user-scoped" is unrepresentable.
- A genuinely public read says so: `:scope :rf.scope/global` is an *auditable claim*, enumerated by Xray's scope-audit surface as the security-review list.
- A subscription that cannot resolve a scope raises `:rf.error/resource-sub-unresolved-scope` — it never falls through to a shared read or a silent permanent `:idle`.
- When the viewer changes mid-session (login, logout, account switch), a `{:from-db …}` subscription **re-keys** reactively to the new principal's entry, reading the new key's `:idle`/`:loading` and never the old principal's data.

The query libraries can do scoped caching; nothing stops you putting the user id in the key. re-frame2's difference is that it *fails closed* — the boundary is enforced, not advised. See [The scoped key: a leak boundary that fails closed](../concepts/server-state.md#the-scoped-key-a-leak-boundary-that-fails-closed).

## Owners and causes, not observers

Query libraries have one idea — the **observer**: a mounted hook keeps a query alive, and an unmounted one lets it become GC-eligible. That single idea is doing two jobs at once — deciding *liveness* (should this stay cached?) and standing in for *why a fetch happened* (a component appeared). re-frame2 splits it into two concepts that never blur:

- An **owner** is a liveness lease. Routes own resources for as long as the route is active; machines own them for the actor's lifetime; an app can mint an explicit `[:lease …]` with a matching release. Owners decide GC eligibility and whether invalidation refetches now or just marks stale.
- A **cause** is an explanation — "why did this fetch happen?" (route entry, click, invalidation, focus return). Causes change nothing about liveness; they exist so the trace can answer *why*.

The practical payoff: **opening a devtool never pins or refetches a resource.** A query library's devtools panel is itself an observer-ish surface; Xray, by contract, never becomes an owner by observing ([Spec 016 §Active owners and causes](../../../spec/016-Resources.md#active-owners-and-causes)). It also means a "background sync" that should keep data warm with no UI mounted is a first-class, explicit lease here, rather than a `staleTime: Infinity` + always-mounted hack.

## Views never fetch — the structural inversion

`useQuery` fetches on mount. That is the defining ergonomic of the query libraries and the defining *difference* of re-frame2: a re-frame2 view reads a subscription and **never** triggers a fetch. The fetch is caused by a route entry, an event, or a machine.

```clojure
;; The view is passive — it reads, it does not fetch.
(rf/reg-view article-page [slug]
  (let [state @(subscribe [:rf.resource/state
                           {:resource :realworld/article :params {:slug slug}}])]
    (cond
      (:loading? state)                              [article-skeleton]
      (and (:error state) (not (:has-data? state)))  [article-error (:error state)]
      :else [article-view (:data state)])))
```

The cleanest *cause* is the page itself, declared as route metadata:

```clojure
(rf/reg-route :realworld/article
  {:resources [{:resource :realworld/article
                :params   (fn [route] {:slug (get-in route [:params :slug])})
                :blocking? true}]}
  "/articles/:slug")
```

This is not a missing feature (the data still loads on navigation); it is the inversion that lets SSR get a natural wait point (`:blocking? true`), lets a route own and release the resource deterministically, and keeps views pure. If you find yourself wanting "fetch when this component appears," the re-frame2 answer is "make the route or an event the cause" — see [Routes declare what a page needs](../concepts/server-state.md#routes-declare-what-a-page-needs).

## Projection: no `select`, because subscriptions already do it

TanStack's `select` (and RTK's `selectFromResult`) lets a component derive a slice of the cached value without re-rendering on unrelated changes. re-frame2 has no `:select` key — and that is a structural advantage, not a gap. A projection over a resource is an ordinary subscription layered over `[:rf.resource/data …]`, with the full subscription graph's caching and parametric inputs behind it:

```clojure
(rf/reg-sub :article/title
  :<- [:rf.resource/data {:resource :realworld/article :params {:slug "welcome"}}]
  (fn [article _] (:title article)))
```

You get memoisation, composition with other subs, and reuse across views for free — none of which a per-call `select` function gives you ([Spec 016 §No `:select` key](../../../spec/016-Resources.md#no-select-key)).

## Invalidation is a declared consequence, not a remembered call

In TanStack and SWR, keeping reads honest after a write is an imperative call you remember to make in `onSuccess` (`invalidateQueries` / `mutate`). RTK Query is closer to re-frame2 here with its `providesTags` / `invalidatesTags`. re-frame2 takes the declarative-tag model further: invalidation is a *declared consequence of the named mutation*, recorded on the event record, scoped by default, and with a precise three-rung cross-scope lattice:

```clojure
(rf/reg-mutation :realworld/favorite
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :invalidates   (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/global         ;; global facts
                      :tags  #{[:article slug] [:article-list]}}
                     {:scope {:from-db :realworld/session}  ;; this viewer's feed
                      :tags  #{[:feed]}}])}
  (fn [{:keys [slug]} _ctx] ...))
```

The fail-closed floor matters: a bare `:rf.resource/invalidate-tags` with no scope is a loud error, not a silent global blast across every tenant. "Invalidate this tag wherever it lives" is possible — `:cross-scope? true` — but it is an *audited* operation that must carry a `:cause` and is a privacy-relevant trace event. See [Writes invalidate by tag — causally](../concepts/server-state.md#writes-invalidate-by-tag--causally) and [Spec 016 §Scoped invalidation descriptors](../../../spec/016-Resources.md#scoped-invalidation-descriptors-per-target).

## Optimistic updates with rollback — landed

The query libraries all ship optimistic UI: flip the cache immediately, snapshot the prior value, revert on server rejection (`onMutate`/`onError` rollback in TanStack, `updateQueryData` + undo patch in RTK, `optimisticData` + `rollbackOnError` in SWR). re-frame2 now matches this on the mutation path, with one deliberate divergence on contested rollback.

A mutation declares an **optimistic plan** applied *before* the request leaves, in one of two forward forms — the twins of the success-time consequence keys:

- **`:optimistic`** — `(fn [params] -> {target patch-fn})`, the exact-target twin of `:patches`. A `nil` patch-fn removes the entry optimistically; a patch over an absent key seeds it.
- **`:optimistic-tags`** — `(fn [params] -> [{:scope … :tags #{…} :patch (fn [old] new)}])`, the tag-addressed twin of `:invalidates`. It patches every cached entry carrying the tag in its scope — the cross-view-consistency case (flip a favorite and have it flip on the detail, every list, and the session feed at once) you can't enumerate by exact key.

```clojure
(rf/reg-mutation :realworld/favorite
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :optimistic-tags (fn [{:keys [slug]}]
                      [{:scope :rf.scope/global
                        :tags  #{[:article slug]}
                        :patch (fn [article] (update-favorite article true))}])
   :populates     (fn [{:keys [slug]} result]
                    {{:resource :realworld/article :params {:slug slug}} result})
   :invalidates   (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/global :tags #{[:article slug] [:article-list]}}
                     {:scope {:from-db :realworld/session} :tags #{[:feed]}}])}
  (fn [{:keys [slug]} _ctx] ...))
```

Three properties are worth holding onto, because they are where re-frame2 differs from a hand-rolled `onMutate` snapshot:

- **The runtime records the inverse — you don't.** It snapshots each touched entry verbatim before patching, so a rollback restores exactly what existed, never an author-written undo patch that can drift from the forward change.
- **Settle is deterministic, with no wall-clock race.** It is decided on recorded facts — the generation acceptance verdict and a per-entry `:revision`. An accepted `:ok` reply commits (the authoritative `:populates`/`:patches` overwrite the optimistic value, then `:invalidates` runs); an accepted `:error`/`:cancelled` rolls back; a stale/superseded reply rolls back nothing.
- **Contested rollback is governed by `:on-conflict`, and the default diverges from the query libraries.** When a concurrent write landed on the entry between the apply and a failure, the recorded inverse is a stale "before." The default `:invalidate` declines to restore it and marks the entry stale so the read path refetches the authoritative value — the read path is the recovery authority, re-frame2's deliberate divergence from TanStack/SWR's unconditional context restore. `:force` restores the inverse anyway (the single-writer escape, with a tooling warning).

A view renders the in-flight optimistic state from the instance sub's derived **`:optimistic?`** flag (true between the pre-request apply and settle). The optimistic surface is exact-key or tag-within-named-scope only, both **fail-closed** on a nil-resolving `{:from-db …}` scope — there is no scope-agnostic optimistic write, so it cannot leak across viewers, tenants, or SSR requests. The normative contract is [Spec 016 §Optimistic mutations](../../../spec/016-Resources.md#optimistic-mutations); the worked write is in [Part 4 of the tutorial](../tutorial/04-mutations-and-invalidation.md).

## Polling and infinite feeds — both landed

The two parity-relevant features once tracked as gaps here have since landed; they are recorded below for readers arriving from the query libraries.

### Polling / refetch-interval — landed (EP-0020)

TanStack's `refetchInterval`, RTK's `pollingInterval`, and SWR's `refreshInterval` make timed background refetching a one-line option. re-frame2 ships the same as **`:poll-interval-ms`** on `reg-resource` ([Spec 016 §Polling](../../../spec/016-Resources.md#polling)): a positive interval in ms that revalidates the entry while it is *actively owned* and the tab is visible — the third member of the freshness-timer family beside `:stale-after-ms` / `:gc-after-ms`. It is owner-gated by design: an owner-free entry never polls, so opening a devtool or leaving a stale tab cannot drive background traffic. (This is in addition to the focus/reconnect revalidation that landed earlier via `install-revalidation-listeners!`.)

### Infinite / load-more feeds — landed (EP-0021)

`useInfiniteQuery` (TanStack) and `useSWRInfinite` accumulate pages into one growing list with cursor management. re-frame2 ships this as a first-class **`:infinite`** resource ([Spec 016 §Infinite resources and load-more feeds](../../../spec/016-Resources.md#infinite-resources-and-load-more-feeds)): a resource registered with `:infinite true` plus a pure `:next-page-param` derivation (returning `nil` is the single terminal). One scoped feed entry accumulates an ordered page vector; a causal `:rf.resource/load-more` event extends it; the merged flat list is the headline read at `:rf.resource/items`, with `:rf.resource/pages` for boundaries and `:rf.resource/infinite-state` for the combined view-model. Numbered and cursor pagination stay exactly as before — every page is an ordinary resource keyed by its params, with `:keep-previous?` to avoid skeleton flashes ([Spec 016 §Paginated and previous data](../../../spec/016-Resources.md#paginated-and-previous-data)) — the infinite kind is the complementary accumulating-feed model, not a replacement.

## The honest gaps — out of scope, on purpose

These remain deliberately outside this HTTP-only phase. Do not let the rest of this page's confidence obscure them.

- **Normalized / GraphQL caches** (Apollo, Relay, normalizr). The transport is HTTP-only this phase; a normalized entity cache is a separate later artefact gated on a GraphQL phase, not a resources gap ([Spec 016 §What Spec 016 does NOT cover](../../../spec/016-Resources.md#what-spec-016-does-not-cover)).
- **Offline persistence and cross-tab broadcast.** Deferred later slices; not in the public-beta contract.

## When to reach for resources at all

A query library is the obvious default in React because it is the *only* server-state machinery on offer. In re-frame2 it is one tool among several, and not always the right one:

> **Simpler than a resource.** Two cases where reaching for the resources artefact is over-engineering:
>
> - **A handful of reads, no caching story.** A [managed HTTP request](../concepts/http.md) plus a small app-db slice is less machinery and entirely idiomatic.
> - **Login and other commands.** Auth is a state machine driving a write — model it as a [machine](../concepts/machines.md), not a cached read.

Reach for resources when cached server reads start multiplying and the per-read bookkeeping — scope, staleness, dedupe, invalidation, GC, SSR — is worth moving into the framework. [Where should this value live?](../where-state-lives.md) has the decision table.

---

**The summary, one more time:**

- **Matched:** keyed cache, staleness, dedupe, GC, tag invalidation, mutations + consequences, optimistic updates with rollback, focus/reconnect revalidation, active-owner polling, infinite / load-more feeds, keep-previous paging, SSR/hydration, devtools — all **landed**.
- **Different by design:** per-frame cache, required structural scope, owners-vs-observers, passive views, subscriptions-instead-of-`select`, declarative causal invalidation, `:on-conflict :invalidate` as the contested-rollback default.
- **Out of scope (deliberately):** normalized/GraphQL caches, offline/cross-tab — held for later slices.

---

**You can now:**

- map any TanStack Query / RTK Query / SWR feature onto its re-frame2 resource counterpart, and read each row's landed / different-by-design / out-of-scope status
- explain the "same problem, different physics" line — a cache wired to causes, not to component lifecycle — and why scope is a required structural key
- decide when a resource is the wrong tool, reaching instead for managed HTTP or a machine
