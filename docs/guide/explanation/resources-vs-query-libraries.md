# Resources vs TanStack Query, RTK Query, and SWR

This page is the honest, feature-by-feature comparison between re-frame2 **managed resources** and the JavaScript server-state libraries you may already know — [TanStack Query](https://tanstack.com/query) (the React Query family), [RTK Query](https://redux-toolkit.js.org/rtk-query/overview), and [SWR](https://swr.vercel.app/). The conceptual model is in [Server state: resources](../concepts/server-state.md); this page is the scorecard. It tells you what is matched, what is *deliberately different*, what has *landed*, and — bluntly — what is **not built yet**.

The one sentence to carry through every row:

> **Same problem, different physics.** A query library is a cache wired to component lifecycle; a re-frame2 resource is a cache wired to *causes* — routes, events, machines — with the viewer identity promoted from convention to a required structural key.

If you want the design rationale rather than the comparison, read [Inside out: why views come last](inside-out.md) first; the choices below all fall out of that essay.

!!! note "Resources are an optional artefact, and pre-alpha"

    Server state in re-frame2 is the optional `day8/re-frame2-resources` artefact, and it is **pre-alpha**: the read path and the mutation path have landed, but three parity-relevant features (optimistic rollback, polling, infinite feeds) are not built yet and are tracked as design proposals. Every "proposed" row below names its tracking proposal so you can see exactly where the edge is. Nothing here is back-compat-frozen.

## How to read the status column

Each row carries a status. They mean precisely:

| Status | Meaning |
|---|---|
| **Landed** | Shipped in the reference implementation (`re-frame.resources`) and pinned by tests. |
| **Different by design** | A capability the query libraries have, expressed differently here on purpose — usually because re-frame2 already has a more general mechanism (the subscription graph, the event loop) that subsumes it. |
| **Proposed (EP-00NN)** | A real parity gap, *deferred*, with an open enhancement-proposal PR working out the design. Not in the shipped contract. The proposal numbers below — EP-0019 (optimistic rollback), EP-0020 (active-owner polling), EP-0021 (infinite resources) — are landing as proposal PRs and are tracked under the [pre-alpha resource parity tranche] beads `rf2-byl7bk.1` / `.2` / `.3`. |
| **Out of scope** | Deliberately not a resources concern — a different artefact, a different phase, or a non-goal. |

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
| **Optimistic updates + rollback** | `onMutate` snapshot + `onError` rollback | `updateQueryData` + `undo` patch | `optimisticData` + `rollbackOnError` | **not built** — write a managed-HTTP write whose failure handler restores prior app-db | **Proposed (EP-0019)** |
| **Polling / refetch interval** | `refetchInterval` | `pollingInterval` | `refreshInterval` | **not built** — focus/reconnect revalidation has landed; *timed* polling has not | **Proposed (EP-0020)** |
| **Refetch on window focus / reconnect** | `refetchOnWindowFocus` / `refetchOnReconnect` | `refetchOnFocus` / `refetchOnReconnect` | `revalidateOnFocus` / `revalidateOnReconnect` | `install-revalidation-listeners!` per frame; refetches only entries that are *stale AND owned* | **Landed** |
| **Infinite / load-more** | `useInfiniteQuery` | `infiniteQuery` (recent) | `useSWRInfinite` | **not built** — numbered/cursor pages are ordinary resources with `:keep-previous?`; a canonical accumulating-feed model is deferred | **Proposed (EP-0021)** |
| **Keep-previous-data while paging** | `placeholderData: keepPreviousData` | n/a (manual) | `keepPreviousData` | `:keep-previous?` on the route/resource; `:rf.resource/state` projects `:previous-data` / `:previous-key` | **Landed** |
| **SSR / hydration** | `dehydrate` / `HydrationBoundary` | `getRunningQueries` + preload | fallback data | per-request frames; blocking route resources are the render wait point; allowlist projection serialized + hydrated under freshness rules; fresh hydrated entries are not re-fetched | **Landed** |
| **Devtools / observability** | React Query Devtools | RTK devtools (Redux) | external | Xray Resources panel + a `:rf.resource/*` / `:rf.mutation/*` trace family; static registry, live instance table, work-ledger table, route/resource graph, scope-audit + orphaned-owner lints | **Landed** |
| **Normalized / GraphQL cache** | normalizr (external) | partial | external | Apollo/Relay-class — not a resources concern; transport is HTTP-only this phase | **Out of scope** |
| **Offline persistence / cross-tab** | persister plugins | n/a | external | not built; deferred | **Proposed (later slice)** |

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
   :request       (fn [{:keys [page]} _ctx] ...)
   :tags          (fn [_ _] #{[:feed]})})
```

- A `reg-resource` with **no** `:scope` is a loud registration error (`:rf.error/resource-missing-scope-policy`) — "I forgot this read is user-scoped" is unrepresentable.
- A genuinely public read says so: `:scope :rf.scope/global` is an *auditable claim*, enumerated by Xray's scope-audit surface as the security-review list.
- A subscription that cannot resolve a scope raises `:rf.error/resource-sub-unresolved-scope` — it never falls through to a shared read or a silent permanent `:idle`.
- When the viewer changes mid-session (login, logout, account switch), a `{:from-db …}` subscription **re-keys** reactively to the new principal's entry, reading the new key's `:idle`/`:loading` and never the old principal's data.

The query libraries can do scoped caching; nothing stops you putting the user id in the key. re-frame2's difference is that it *fails closed* — the boundary is enforced, not advised. See [The scoped key: a leak boundary that fails closed](../concepts/server-state.md#the-scoped-key-a-leak-boundary-that-fails-closed).

## Owners and causes, not observers

Query libraries have one idea — the **observer**: a mounted hook keeps a query alive, and an unmounted one lets it become GC-eligible. re-frame2 splits that single idea into two that never blur:

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
  {:path "/articles/:slug"
   :resources [{:resource :realworld/article
                :params   (fn [route] {:slug (get-in route [:params :slug])})
                :blocking? true}]})
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
   :request       (fn [{:keys [slug]} _ctx] ...)
   :invalidates   (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/global         ;; global facts
                      :tags  #{[:article slug] [:article-list]}}
                     {:scope {:from-db :realworld/session}  ;; this viewer's feed
                      :tags  #{[:feed]}}])})
```

The fail-closed floor matters: a bare `:rf.resource/invalidate-tags` with no scope is a loud error, not a silent global blast across every tenant. "Invalidate this tag wherever it lives" is possible — `:cross-scope? true` — but it is an *audited* operation that must carry a `:cause` and is a privacy-relevant trace event. See [Writes invalidate by tag — causally](../concepts/server-state.md#writes-invalidate-by-tag--causally) and [Spec 016 §Scoped invalidation descriptors](../../../spec/016-Resources.md#scoped-invalidation-descriptors-per-target).

## The honest gaps

These are real parity gaps. They are deferred, tracked, and have open design proposals. Do not let the rest of this page's confidence obscure them.

### Optimistic updates with rollback — proposed (EP-0019)

The query libraries all ship optimistic UI: flip the cache immediately, snapshot the prior value, revert on server rejection (`onMutate`/`onError` rollback in TanStack, `updateQueryData` + undo patch in RTK, `optimisticData` + `rollbackOnError` in SWR). re-frame2's mutation path is **not** optimistic today — a mutation settles its instance state and applies cache consequences only on the accepted reply, and the success trace *reserves* the rollback shape (`:affected-keys`, patch/snapshot/reconciliation slots) but rollback itself is deferred ([Spec 016 §Mutations](../../../spec/016-Resources.md#mutations-first-public-beta-gate-rf2-dwme29), [§Deferred slices](../../../spec/016-Resources.md#deferred-slices)).

**Until it lands:** use a managed-HTTP write whose failure handler restores the prior app-db value (the optimistic flip lives in your app-db, not the cache). This is the [When resources are the wrong tool](../concepts/server-state.md#when-resources-are-the-wrong-tool) guidance. The design is being worked under EP-0019 / bead `rf2-byl7bk.1`; getting deterministic rollback right under overlapping mutations, stale/superseded replies, and scoped invalidation is exactly why it is a proposal rather than a quick add.

### Polling / refetch-interval — proposed (EP-0020)

TanStack's `refetchInterval`, RTK's `pollingInterval`, and SWR's `refreshInterval` make timed background refetching a one-line option. re-frame2 has the *adjacent* capability — focus/reconnect revalidation has landed (`install-revalidation-listeners!`, refetching stale-and-owned entries) — but **timed polling is not built**. There is no `:poll-ms` key; it is an explicitly rejected v1 key ([Spec 016 §Resource registration spec](../../../spec/016-Resources.md#resource-registration-spec)).

**Until it lands:** drive a refetch from an interval you own (an event dispatched on a timer, ensuring under an explicit owner). The design — how to express interval revalidation while preserving owner leases and stale-only refresh discipline — is under EP-0020 / bead `rf2-byl7bk.2`.

### Infinite / load-more feeds — proposed (EP-0021)

`useInfiniteQuery` (TanStack) and `useSWRInfinite` accumulate pages into one growing list with cursor management. re-frame2 handles *numbered and cursor pagination* well today — every page is an ordinary resource keyed by its params, with `:keep-previous?` to avoid skeleton flashes ([Spec 016 §Paginated and previous data](../../../spec/016-Resources.md#paginated-and-previous-data)) — but there is **no canonical accumulating-feed primitive**. A load-more feed currently falls back to app-db composition around the per-page resources.

**Until it lands:** compose pages in app-db (a subscription concatenating the per-page resource data). The canonical model — whether infinite feeds get a dedicated primitive or a documented composition pattern — is under EP-0021 / bead `rf2-byl7bk.3`.

### Out of scope, on purpose

- **Normalized / GraphQL caches** (Apollo, Relay, normalizr). The transport is HTTP-only this phase; a normalized entity cache is a separate later artefact gated on a GraphQL phase, not a resources gap ([Spec 016 §What Spec 016 does NOT cover](../../../spec/016-Resources.md#what-spec-016-does-not-cover)).
- **Offline persistence and cross-tab broadcast.** Deferred later slices; not in the public-beta contract.

## When to reach for resources at all

A query library is the obvious default in React because it is the *only* server-state machinery on offer. In re-frame2 it is one tool among several, and not always the right one:

!!! note "Simpler than a resource"

    - **A handful of reads, no caching story.** A [managed HTTP request](../concepts/http.md) plus a small app-db slice is less machinery and entirely idiomatic.
    - **Login and other commands.** Auth is a state machine driving a write — model it as a [machine](../concepts/machines.md), not a cached read.
    - **Optimistic UI with rollback.** Not expressible as a mutation today (above) — use a managed HTTP write with a restoring failure handler.

Reach for resources when cached server reads start multiplying and the per-read bookkeeping — scope, staleness, dedupe, invalidation, GC, SSR — is worth moving into the framework. [Where should this value live?](../where-state-lives.md) has the decision table.

---

**The summary, one more time:**

- **Matched:** keyed cache, staleness, dedupe, GC, tag invalidation, mutations + consequences, focus/reconnect revalidation, keep-previous paging, SSR/hydration, devtools — all **landed**.
- **Different by design:** per-frame cache, required structural scope, owners-vs-observers, passive views, subscriptions-instead-of-`select`, declarative causal invalidation.
- **Not built yet:** optimistic rollback (EP-0019), polling (EP-0020), infinite feeds (EP-0021).
- **Out of scope:** normalized/GraphQL caches, offline/cross-tab.

[pre-alpha resource parity tranche]: ../concepts/server-state.md
