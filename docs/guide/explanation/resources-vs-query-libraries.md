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

## The public surface, at a glance

Before the row-by-row deep dive, here is the whole API on one card — the thing a query-library reader actually wants when they sit down to type. re-frame2 keeps three lanes strictly separate, and the lane a symbol lives in tells you what it does. (This is the [Spec 016 §Public API](../../../spec/016-Resources.md#public-api) lane table, compressed.)

| Lane | What it is | The surface | Who calls it |
|---|---|---|---|
| **Registration** (functions, at boot) | Declare a handler once — it does not fetch or read | `rf/reg-resource` / `rf/clear-resource`, `rf/reg-mutation` / `rf/clear-mutation`, `rf/reg-resource-scope` / `rf/clear-resource-scope` | app code, once, at startup |
| **Commands** (causal event vectors, dispatched) | *Cause* work — they are not reads | `[:rf.resource/ensure …]`, `[:rf.resource/refetch …]`, `[:rf.resource/invalidate-tags …]`, `[:rf.resource/release-owner …]`, `[:rf.resource/clear-scope …]`, `[:rf.resource/remove …]`, `[:rf.resource/load-more …]`, `[:rf.mutation/execute …]` | routes, events, machines |
| **Reads** (passive subscription vectors) | Project runtime state — the only lane a view touches | `[:rf.resource/state …]`, `[:rf.resource/data …]`, `[:rf.resource/status …]`, `[:rf.resource/loading? …]`, `[:rf.resource/fetching? …]`, `[:rf.resource/stale? …]`, `[:rf.resource/error …]`, `[:rf.resource/refresh-error …]`, `[:rf.resource/has-data? …]`, `[:rf.resource/previous-data …]`, `[:rf.resource/items …]`, `[:rf.resource/infinite-state …]`, `[:rf.mutation/state …]` | views, via `subscribe` |

> **The whole `rf/` resource surface is the optional Resources artefact.** `reg-resource`, `reg-mutation`, and `reg-resource-scope` are facade-exported registration *macros/functions* on `re-frame.core`, but they are `advanced` post-v1 capability — late-bound by `day8/re-frame2-resources`, absent from an app that never requires it. The introspection accessors (`rf/resource-meta`, `rf/resource-state`, `rf/resources`) are the tool/test projection lane — not an app-read API; a view that reaches for them instead of a subscription is a category error (they take a one-shot snapshot and never re-render).

Three command names earn a sentence each, because a query-library reader reaches for them and the mapping is not obvious:

- **`:rf.resource/refetch`** is the imperative bypass — TanStack's `refetch()` / SWR's `mutate(key)` with no data. It forces a fetch regardless of freshness, carrying a `:cause` (`[:manual :article/refresh]`) but usually *no* `:owner` — a manual refresh keeps no lease.
- **`:rf.resource/remove`** evicts one exact entry (scope + resource + params), eagerly, regardless of GC policy — the surgical counterpart to letting GC reclaim it.
- **`:rf.resource/release-owner`** drops an app-minted lease (the matching half of an `:owner` you attached on an `ensure`). Forgetting it is the orphaned-owner leak Xray lints for.

## Where the cache lives, and why it is per-frame

A query library keeps one cache for the running app: TanStack Query's `QueryClient`, RTK Query's store slice, SWR's module cache. That is fine for a single-user SPA, and a liability the moment two principals share a process — most sharply on the server, where one Node process renders for every user at once.

re-frame2 puts the cache in the framework-owned *runtime partition* of **each frame** — one running instance of your app — at `:rf.runtime/resources`, never in your app-db. Two consequences fall out:

- **SSR cannot leak by construction.** Each server request renders in its own frame, so there is no process-global cache to bleed user A's articles into user B's render. A query library reaches the same safety only by spinning up a fresh client per request and being careful never to share it.
- **An ordinary event handler can't corrupt the cache.** It lives in the runtime partition, not app-db, so a careless `assoc` in a `:db` handler can't wipe it. You read it through subscriptions and change it only through events.

This is the [Server state: resources](../concepts/server-state.md#the-cache-you-dont-own) "cache you don't own" model. It is *different by design*, not merely a different default.

## One registration carries every policy the matrix splits into rows

The parity matrix above splits a resource's behaviour into a dozen rows — staleness, GC, polling, invalidation tags, scope — but in the source they all live on *one* `reg-resource` call. Seeing them together is worth more than any single row, because it shows that a re-frame2 resource declares its whole policy at the definition site, the way a TanStack `useQuery` config object collects `staleTime` / `gcTime` / `refetchInterval` in one place:

```clojure
(rf/reg-resource
  :article/by-slug
  {:doc            "Article detail by slug."
   :params-schema  [:map [:slug :string]]       ;; REQUIRED — identity + canonicalization
   :scope          :rf.scope/global             ;; REQUIRED — the fail-closed leak boundary
   :data-schema    :app/article                 ;; optional — validates decoded data
   :transport      :rf.http/managed             ;; the only built-in transport this phase
   :stale-after-ms 60000                         ;; TanStack staleTime — serve, refetch on next ensure when stale
   :gc-after-ms    300000                        ;; TanStack gcTime — collect N ms after the last owner leaves
   :poll-interval-ms 5000                        ;; refetchInterval — only while actively owned + tab visible
   :tags           (fn [{:keys [slug]} _data]    ;; RTK providesTags — what a mutation can invalidate
                     #{[:article slug]})
   :sensitive      [[:data :ssn]]}               ;; classification — redacted off-box (trace, SSR wire)
  ;; the :request fetch fn is the THIRD slot — NOT a metadata key
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}
     :decode  :app/article}))
```

A few keys repay attention because they differ from the query-library defaults you carry in muscle memory:

- **`:params-schema` and `:scope` are the only two required keys**, and both fail loud. A `reg-resource` with no `:scope` throws `:rf.error/resource-missing-scope-policy`; one with no `:params-schema` (or with `:request` mistakenly placed *inside* the metadata map rather than as the third slot) throws `:rf.error/invalid-resource-spec`. There is no "it'll figure out the key from the URL" — identity and the leak boundary are declared, never inferred.
- **`:data-schema` is optional and validate-only.** When present it shape-checks decoded data; when absent the response is taken as-is. It does *not* drive what gets redacted off-box — that is `:sensitive` / `:large` (below). Most resources, including the reference implementation's flagship example, omit it.
- **The three timers are a family.** `:stale-after-ms`, `:gc-after-ms`, and `:poll-interval-ms` are the staleness, GC, and polling rows respectively — all advisory, all owner-aware. An entry with *no* `:gc-after-ms` simply lingers owner-free until something re-leases or removes it, rather than vanishing; `:poll-interval-ms` only ticks while the entry is actively owned and the tab is visible, so a backgrounded or devtool-only entry never drives traffic.
- **`:tags` is a function of `(params data)`**, not a static list. It returns the set of tags this entry *provides* — the RTK `providesTags` analogue — so a mutation's `:invalidates` can address it by tag without naming its exact key.
- **`:sensitive` / `:large` classify for egress**, not validation. They are vectors of paths rooted at the instance projection (`[:data :ssn]`, `[:params :account-id]`); the coarse whole-entry claims `:sensitive?` / `:large?` are the blunt instrument. These govern what the trace bus and the SSR/hydration wire redact — never inferred from the schema. See [Spec 015 — Data Classification](../../../spec/015-Data-Classification.md#subsystem-projection-relative-classification) for the model.

> **The `:request` fn is the third slot, not a key.** This trips everyone once: the fetch function is the *third positional argument* to `reg-resource`, after the id and the metadata map. Putting `:request` inside the metadata map is a loud `:rf.error/invalid-resource-spec`. The fn receives `(params ctx)` — and `ctx` is reserved (literal `nil`) this slice, so derive everything from `params`. Its return is a [managed-HTTP](../concepts/http.md) args map; you may *not* supply `:request-id`, `:on-success`, or `:on-failure` — resource lowering owns those, keyed off the scoped resource key and generation.

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

### `{:from-db …}` and the named scope resolver

You will have noticed `{:from-db :realworld/session}` recurring through the examples on this page. That is a reference to a **named scope resolver** — registered once, reused everywhere a scope is needed (registration, route resources, event-side ensure, subscriptions, invalidation descriptors, `clear-scope`). One scope-resolution currency, not a resolver re-spelled at every call site. You register it with `rf/reg-resource-scope`:

```clojure
(rf/reg-resource-scope :realworld/session
  {:inputs  {:username [:db [:auth :user :username]]}
   :resolve (fn [{:keys [username]} _ctx]
              (when username
                [:rf.scope/session {:username username}]))})
```

Three properties make this the mechanism that the whole leak boundary rests on:

- **A resolver is pure.** It derives a scope value from declared app-db inputs and nothing else — it does not fetch, dispatch, or read ambient host state. Declaring `:inputs` (rather than reading the whole db) lets the runtime re-resolve scope *only* when a relevant input changes, and lets Xray explain which facts decide a resource's identity.
- **`nil` is fail-closed, always.** When `:username` is absent (logged out), the resolver returns `nil`, and at any scope-requiring site `nil` is the *unresolved* condition — never silent permission to read the global cache. A subscription whose scope resolves `nil` raises `:rf.error/resource-sub-unresolved-scope`; a `clear-scope` reference that resolves `nil` emits a loud diagnostic, never a silent no-op.
- **A `{:from-db …}` subscription re-keys reactively.** This is the account-switch / login / logout payoff. When the resolver's inputs change mid-session, the subscription re-points to the *new* principal's cache entry on the next reactive pass — the same subscription, now tracking the new viewer. During the transition the view reads the new key's `:idle` / `:loading`, **never** the old principal's `:data`. The leak boundary holds across the re-key, not just at first resolution.

The normative contract is [Spec 016 §Named resource-scope resolvers](../../../spec/016-Resources.md#named-resource-scope-resolvers-reg-resource-scope). For the logout idiom, `rf/resolve-resource-scope` is the helper that resolves a named scope against a handler's coeffect db — so you clear the scope the user *was* in, resolved before the db dropped them.

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

### What `:rf.resource/state` actually hands the view

`[:rf.resource/state …]` projects a **view-model of derived facts**, not the raw durable cache entry. The booleans your view branches on are computed for you — you never reconstruct "error, but I still have stale data to show" from a status enum plus a guard. The projection:

```clojure
{:status        :idle | :loading | :fetching | :loaded | :error
 :data          <last-known-good-or-nil>
 :error         <first-load-error-or-nil>      ;; only when :status is :error
 :refresh-error <background-refresh-error-or-nil>
 :loading?      <bool>   ;; first load, no usable data yet
 :fetching?     <bool>   ;; refreshing while prior :data stays visible
 :stale?        <bool>   ;; past :stale-after-ms (or invalidated)
 :has-data?     <bool>}  ;; :data is non-nil — the one to branch on for skeleton-vs-content
```

The two status words are the whole ergonomic. **`:loading`** means *first load, nothing to show* (render a skeleton). **`:fetching`** means *work is in flight but prior data is still good* (render the data, maybe a quiet spinner) — TanStack's `isFetching` while `isLoading` is false. And the error split matters: **`:error`** (in `:status` / `:error`) is a first load that produced no usable data, while **`:refresh-error`** is a *background* refresh that failed with prior data preserved — so a view shows a full-page error in the first case and a stale-data-with-a-warning banner in the second, without guessing. The error envelope is the same closed [managed-HTTP failure shape](../concepts/http.md) (`:kind` drawn from the `:rf.http/*` taxonomy), shared by `:error` and `:refresh-error` alike. The narrower single-fact subs (`:rf.resource/data`, `:rf.resource/loading?`, `:rf.resource/has-data?`, …) project one slice each when a view only needs one ([Spec 016 §Status semantics](../../../spec/016-Resources.md#status-semantics)).

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

## Running and observing a mutation

Before the optimistic deep-dive, the plain shape. `useMutation` returns a `{ mutate, isPending, data, error }` tuple; re-frame2 splits that into a causal command and a passive read, the same command/read separation as the read path. You *run* a mutation by dispatching `:rf.mutation/execute`, and *observe* it through the `:rf.mutation/*` subs — keyed by an **instance** id, not the mutation id:

```clojure
;; the command — a route, an event, or a form submit dispatches it
[:rf.mutation/execute
 {:mutation :article/save
  :params   article
  :instance :form/save-1          ;; caller-supplied (or generated) instance id
  :scope    [:rf.scope/session {:user-id "u-42"}]
  :cause    [:form-submit :article/save]
  :reply-to [:editor/save-replied]}]   ;; optional completion event (below)

;; the read — a view observes the instance
@(rf/subscribe [:rf.mutation/state {:instance :form/save-1}])
;; => {:status :result :error :pending? :success? :error? :settled? :optimistic?}
```

The instance-keying matters: two concurrent submissions of `:comment/add` keep **distinct** pending/result/error rows and never clobber each other — the thing a single `useMutation` hook can't express without one hook per in-flight write. Narrower subs (`:rf.mutation/pending?`, `:rf.mutation/result`, `:rf.mutation/error`, `:rf.mutation/status`) project one slice when a button only needs one. A mutation's failure settles `:error` (there is no `:refresh-error` analogue — a write has no last-known-good to keep), and `[:rf.mutation/clear {:instance …}]` is the causal reset that clears the instance and best-effort aborts any in-flight work.

A mutation is registered with `rf/reg-mutation`, mirroring `reg-resource`'s three-slot grammar — id, metadata map, and the `:request` *write* fn as the third slot. Its success-phase consequence keys are the heart of it:

```clojure
(rf/reg-mutation :article/save
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :invalidates   (fn [params result] [{:scope … :tags #{[:article-list]}}])  ;; RTK invalidatesTags
   :populates     (fn [params result] {scoped-key result})    ;; seed an entry from the reply (authoritative load)
   :patches       (fn [params result] {scoped-key (fn [old result] (merge old result))})  ;; transform an entry
   :removes       (fn [params _result] [target-map])}          ;; delete an exact entry
  (fn [params _ctx] {:request {:method :put :url "/api/articles" :body params}}))
```

`:populates` / `:patches` / `:removes` are the declarative twins of TanStack's imperative `setQueryData` in `onSuccess`: `:populates` seeds an entry from the reply (semantically a successful load), `:patches` transforms one in place, `:removes` evicts an exact key. They run *before* the success-time `:invalidates`, and the invalidation timing is itself explicit (`:before-request` / `:after-success` (the default) / `:after-failure` / `:after-settle`).

### The completion continuation is an event, not a callback

`useMutation`'s `onSuccess`/`onError` callbacks are where you fire a toast, navigate, or reset a form. re-frame2 routes that through a call-site **`:reply-to` event target** instead of a callback — because a verified reply is *causal input*, and causal input drives durable state only by dispatching an event (a callback returning effects would mint state outside the event tape, the interceptor chain, and replay). The reply map is appended after any static args you supply: `:reply-to [:toast/after-save {:kind :article}]` dispatches `[:toast/after-save {:kind :article} reply]`. It fires **exactly once**, and **only** for the accepted terminal reply — a stale or superseded reply (a re-execute under the same instance, an intervening `:rf.mutation/clear`) never fires it. By the time your `:reply-to` handler runs, the cache consequences and the instance state are already settled, so it sees a coherent world ([Spec 016 §Mutation completion continuations](../../../spec/016-Resources.md#mutation-completion-continuations--call-site-reply-to)).

> **Mutation scope fails *open* on absence — the one place a scope is optional.** Unlike a resource, a mutation's `:scope` is optional and defaults to `:rf.scope/global` when omitted, because a causal write has no cached-read leak boundary of its own. But the *invalidation* it triggers is still fail-closed (it needs an explicit scope), and the two must agree: if a global-defaulted mutation invalidates tags owned by a session-scoped resource, the invalidation **silently misses** — no entry matches in that scope, the read never refreshes, and no error fires. In dev the framework trips a `:rf.warning/mutation-scope-mismatch` warning at exactly that moment; in production, declare the matching `:scope` on the execute payload (or use a per-target invalidation descriptor). This is the mutation footgun worth memorising.

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
