# Phase A — Competitive landscape

**Pin.** Trunk SHA `a5f883b5691b83ac258121eb5c046df87be6d03d` (`main`, clean working tree). Access date **2026-09-14**, timezone **+10:00 (AUSEST)**. Competitor claims dated the same day unless a page names a different publish date.

**Kind.** Source observation (in-repo) vs runtime/docs measurement (competitor docs fetched) vs inference (labelled). Marketing pages are claims.

**This file is the landscape, not the score.** Jobs and statuses live in `closeness-test.md` / `scoreboard.md`. Stopped when another tool would not change those jobs.

---

## 1. TanStack Query — current stable major is still v5

**Package.** `@tanstack/react-query` **5.102.8**, npm `latest` tag, published **2026-08-27** (https://www.npmjs.com/package/@tanstack/react-query, accessed 2026-09-14). GitHub `TanStack/query` tags include **`@tanstack/solid-query@6.0.0-rc.3`** on **2026-09-04** — v6 is **announced/RC on Solid**, not the React stable major. Official React docs at `https://tanstack.com/query/latest/...` still serve the **v5** guides (important-defaults, optimistic-updates, SSR, persist, prefetch, Devtools). **Do not inherit v4 from in-repo files. Do not treat v6 RC as released React Query.**

**Practical workflow (built-in unless noted).**

| Job | How Query ships it | Setup / extra | Defaults that bite |
|---|---|---|---|
| First cached read | `useQuery({ queryKey, queryFn })` in a component; fetch starts on mount unless `enabled: false` | `QueryClient` + `QueryClientProvider` | `staleTime: 0` — cached data is stale immediately |
| Dedupe / cache hit | in-flight queries coalesce; fresh data (`staleTime` not elapsed, not invalidated) is served without a fetch | built-in | with default `staleTime: 0`, a remount **does** refetch |
| SWR / background refetch | stale queries refetch on new observer mount, window focus, reconnect | built-in; per-query `refetchOnMount` / `refetchOnWindowFocus` / `refetchOnReconnect` | aggressive unless `staleTime` is raised |
| GC | unused queries collected after `gcTime` | built-in | **5 minutes** (`1000 * 60 * 5`) |
| Retry (reads) | silent retry with exponential backoff | built-in | **3 retries** on the client; **0 on the server** (v5 migration) |
| Invalidation | `queryClient.invalidateQueries({ queryKey })` — imperative, usually in `onSuccess` / `onSettled` | built-in | nothing connects a write to reads except the call site |
| Optimistic | two official recipes: (1) **via the UI** — render `useMutation` `variables` while pending, no cache inverse; (2) **via the cache** — `onMutate` snapshots previous data into returned context, `onError` restores it, `onSettled` invalidates | built-in; author owns the inverse in recipe (2) | contested concurrent write: restoring captured context **can clobber a newer write** — that is the official cache recipe, not a naive blog post |
| Infinite | `useInfiniteQuery` + `fetchNextPage` + `getNextPageParam` | built-in | first page only unless you prefetch more |
| Keep previous | `placeholderData: keepPreviousData` (v5 rename from `keepPreviousData` option) | built-in | — |
| Prefetch / routers | `queryClient.query(...)` (docs: `prefetchQuery` / `ensureQueryData` **deprecated**, to be removed next major); TanStack Router `loader` / `beforeLoad`; hover prefetch | built-in + official router integration | **fetch-from-render is not required.** Router loaders and `queryClient.query` start earlier than mount. Our translation page's "TanStack's render-triggered fetch can't start until React has rendered the component once" is a **caricature of the default hook path**, not of the practical workflow |
| SSR / hydration | per-request `QueryClient` (docs shout **NEVER** create one at module root — that **leaks across users**); `dehydrate` + `<HydrationBoundary>`; or `initialData` (weaker) | built-in | `staleTime` should be raised on SSR clients to avoid instant refetch; `gcTime` on server defaults to `Infinity` |
| Devtools | `@tanstack/react-query-devtools` + browser extensions (Chrome/Firefox/Edge) | **official extra package**; tree-shaken from prod unless you lazy-load production build | shows query keys, observers, stale/fresh, mutations (since v5). Does **not** name a re-frame-style causal event |
| Persistence | `@tanstack/react-query-persist-client` + `createSyncStoragePersister` / `createAsyncStoragePersister` | **official plugin**, not core | restore is async; `PersistQueryClientProvider` pauses fetches until restore; `maxAge` default 24h; `gcTime` must be ≥ `maxAge` |
| Cancellation | Query owns AbortController around `queryFn`; leaving a page unmounts the observer | built-in | — |
| Dynamic parallel reads | `useQueries` over a list | built-in | **not** the same job as a static route `:resources` vector |
| One-line colocated read | the hook *is* the product | TypeScript inference + ESLint plugin (`@tanstack/eslint-plugin-query`) | first-hour cheap; tenth-hour invalidation bookkeeping expensive |
| Writes retry | mutations default **no retry** (QueryClient `mutations.retry` typically 0) | built-in | matches Resources' "writes don't retry by default" |

**Sources.**

- Important defaults: https://tanstack.com/query/latest/docs/framework/react/guides/important-defaults (fetched 2026-09-14): stale-immediately; GC 5 min; silent retry 3×; structural sharing.
- Optimistic: https://tanstack.com/query/latest/docs/framework/react/guides/optimistic-updates (fetched 2026-09-14). Two recipes. Cache recipe restores `onMutate` snapshot on error. TkDodo's concurrent-optimistic article is linked as further reading.
- Prefetch / router: https://tanstack.com/query/latest/docs/framework/react/guides/prefetching (fetched 2026-09-14). Router integration is first-class, not a community hack.
- SSR: https://tanstack.com/query/latest/docs/framework/react/guides/ssr (fetched 2026-09-14). Module-level `QueryClient` is the documented leak. Isolation is a **discipline you opt into**, not topology.
- Persist: https://tanstack.com/query/latest/docs/framework/react/plugins/persistQueryClient (fetched 2026-09-14). Official plugin.
- Devtools: https://tanstack.com/query/latest/docs/framework/react/devtools (fetched 2026-09-14). Separate package + browser extensions.

**What this means for Phase B.** Score Query's **practical** workflow (hook + QueryClient + official Devtools + persist plugin + router loaders), not a bare `useQuery` with no extras. Score **native defaults** and **matched policy** separately. Do not credit Query core with TanStack DB.

---

## 2. TanStack DB — shipped, adjacent, **not Query core**

**Package.** `@tanstack/db@0.9.0`, published **2026-09-10** (https://www.npmjs.com/package/@tanstack/db). Docs: https://tanstack.com/db/latest/docs/overview (fetched 2026-09-14). README still says **BETA**.

**Job it steals (if any).** "Server state as a **local database**": normalized **collections**, **live queries** (differential dataflow, sub-ms joins/aggregates), **optimistic collection mutations** (`collection.update(id, draft => …)` with handler-owned persist), sync engines (ElectricSQL, PowerSync, RxDB, TrailBase), query-driven sync (`syncMode: 'on-demand'`), localStorage / in-memory collections.

It **extends** Query (`queryCollectionOptions` loads REST via Query) rather than replacing it. Entity consistency after a write is the collection's problem, not `invalidateQueries`.

**Attribution rule.** A TanStack DB release invalidates **only** the local-first / entity-consistency job, and only after capabilities are attributed to **`@tanstack/db`**, not Query. Resources is HTTP-only by Spec 016 ruling. In-repo reasoner for this job: `examples/capabilities/resources/linearlite/` (optimistic board, not a sync engine).

**Do not** require Resources to become a sync engine. **Do not** score "Linear-class live queries" as a Query-core BEHIND.

---

## 3. Complementary set (four besides Query; GraphQL is the fifth, bound)

### 3.1 SWR (Vercel) — compact API

**Job it changes.** First-hour ceremony. `useSWR(key, fetcher)` is one call. Mutations: global/bound `mutate(key, data, { optimisticData, rollbackOnError, populateCache, revalidate })` and `useSWRMutation`. Optimistic + rollback is **library-owned** (`rollbackOnError` default true) — closer to Resources' "runtime records the inverse" than Query's cache recipe, farther from Resources' contested `:on-conflict :invalidate`.

No first-party Devtools (community). Infinite: `useSWRInfinite`. Persistence/offline: external. Bundle ~5KB vs Query ~13–16KB (third-party 2026 comparisons; treat sizes as claims).

**Does not change Phase B jobs** except: J1 first-read ceremony (SWR cheaper than Query *and* Resources) and J5 rollback (SWR auto-rollback vs Query snapshot vs Resources inverse + conflict policy).

Source: https://swr.vercel.app/docs/mutation (fetched 2026-09-14).

### 3.2 RTK Query — closest cousin for "invalidation as data"

**Job it changes.** J4 (after a write, the right reads refresh **without** a remembered `onSuccess` list). `providesTags` / `invalidatesTags` on `createApi` endpoints. Tags have `type` + optional `id`; a general tag invalidates all of that type; a specific `{type, id}` is surgical. OpenAPI codegen (`@rtk-query/codegen-openapi`) is a **job** (generate endpoints + tags from a spec), not a noun to copy.

Cache lives in the Redux store. `keepUnusedDataFor` ≈ GC. Hooks are codegen'd. Optimistic: `onQueryStarted` + `updateQueryData` + `undo` patch — author still sketches the inverse.

**Closest to Resources' `:tags` / `:invalidates`.** Resources' claimed upgrade is: invalidation is on the **causal event record**, so Xray can name *which write* staled *which read*. RTK Devtools show Redux actions, not a resource/mutation graph.

Source: https://redux-toolkit.js.org/rtk-query/usage/automated-refetching (fetched 2026-09-14). Codegen tags: https://redux-toolkit.js.org/rtk-query/usage/code-generation (fetched 2026-09-14).

### 3.3 Apollo Client — GraphQL normalized cache (the entity job, scored OUT)

**Job.** "One entity in three places stays consistent after a write" **without a refetch**. Apollo `InMemoryCache` normalizes by `__typename:id`, stores a flat table of references, merges fields. A mutation that returns the entity updates every query that referenced it. Devtools inspect the normalized graph.

**Spec 016 does NOT cover this** (`spec/016-Resources.md` §What Spec 016 does NOT cover: GraphQL; normalized graph caches / fragment stores / entity-identity policy — separate artefact, gated on GraphQL phase). Transport is HTTP-only.

**How Conduit serves the same *user-visible* job:** favourite `:optimistic-tags` patches every cached entry tagged `[:article slug]` (detail envelope `{:article …}` *and* list envelope `{:articles […]}`); `:populates` seeds the detail from the reply; `:invalidates` refetches lists/feed. Cost: extra requests after the write; inconsistency window until refetch lands. A Conduit user **notices** the heart flip immediately (optimistic) and list membership after invalidate (Favorited-Articles tab is a special case — newly-favorited articles are **not** yet members of that list's tag index, so Conduit threads `[:favorited-articles username]` into `:invalidates` via mutation `:params`). That extra-request cost is real; it is not a v1 GAP.

Source: https://www.apollographql.com/docs/react/caching/overview (fetched 2026-09-14).

### 3.4 CLJS lineage (one group)

Several Resources ideas are inheritances, not inventions:

| Ancestor | What Resources kept | What it dropped |
|---|---|---|
| `shipclojure/re-frame-query` (EP-0003 prior art) | keyed cached reads in re-frame | hook-shaped / queryFn promises |
| Fulcro `load` / ident | identity as data; views don't fetch | normalized graph, Pathom, full-stack |
| Keechma dataloader | route/controller causes loads | different runtime |
| Pattern-RemoteData + Spec 014 | omit-Resources path; retry/auth live here | per-feature `{:status :data :error}` slices |

`realworld_http/` **is** the lineage control: the same Conduit one layer down.

### 3.5 One non-JS — skipped as Phase B mover

**Kotlin Store5** / **Flutter Riverpod `AsyncValue`** / **Angular `resource()` / `httpResource`** / **Phoenix `assign_async`**: Store5 would add the offline-store job already named as **Deferred** on the translation page and as a job to *evaluate* (not silent DIVERGENT). Riverpod's triad is Pattern-RemoteData. Angular `resource()` is still fetch-from-template. Phoenix is LiveView, different substrate. **None changes the job set.** One sentence and stop.

---

## 4. Adjacent (one sentence each)

- **tRPC** — typed RPC over Query; TypeScript inference job, not a cache model. Resources has Malli `:params-schema` / `:data-schema` (identity + decode), not TS.
- **Next.js `fetch` / RSC** — server components move some reads off the client. Query's advanced-SSR guide is the honest comparator for J9, not RSC as a Query replacement.
- **MSW** vs canned HTTP stubs — Query tests often wrap MSW; Resources has `re-frame.http.test-support` + Story `force-fx-stub` + Conduit `demo_backend.cljs`. J12 is "test a page without a network," not "MSW vs stub."
- **OpenAPI codegen (Orval / RTK)** — generate the *register* lane. Resources has no codegen. Score as ceremony on J1/J4 if an author would miss it; do not recommend building Orval.
- **TanStack Router / Start** — official prefetch + SSR integration. Fair comparison for J8/J9 includes this, not only `useQuery` on mount.

---

## 5. Built-in vs plugin vs community vs app work

| Capability | Query | Resources (this SHA) |
|---|---|---|
| Keyed cache, dedupe, SWR, GC, mutations | built-in | built-in (`day8/re-frame2-resources`) |
| Tag invalidation | **app work** (`onSuccess` lists) or RTK | built-in (`:invalidates` on `reg-mutation`) |
| Optimistic inverse | **app work** (snapshot in `onMutate`) or SWR option | built-in (runtime records inverse) |
| Contested rollback | **app work** / clobber | built-in default `:on-conflict :invalidate` |
| Devtools | **official extra package** + extensions | Xray Resources panel (`tools/xray/spec/024-Resources-Panel.md`) — Xray is a **dev artefact**, analogous to Query Devtools, not "bare Query" |
| Persistence | **official plugin** | **Deferred** (Spec 016) |
| Router prefetch | official with TanStack Router | `[:rf.route/prefetch]` + `route-link :prefetch :intent` (EP-0037 R3) — **Landed in spec/routing**; Conduit **does not exercise** hover prefetch (UNDERMARKETED if true) |
| SSR isolation | **discipline** (per-request client) | **topology** (one frame per request) — productized only if an example hydrates; Conduit is **client-only**; `examples/capabilities/ssr/resources_ssr/` is the worked demo |
| Scope / tenant leak | **convention** (put userId in the key) | **registration error** if omitted |
| GraphQL / entity cache | community (normalizr) or Apollo | OUT |
| OpenAPI codegen | official RTK / community Orval | none |

---

## 6. In-repo Resources snapshot (so Phase B does not invent the product)

**Flagship.** `examples/real-apps/realworld_resources/`. Run from `implementation/`: `npm run dev:example -- examples/realworld-resources` (`implementation/package.json` script `dev:example` → `examples/scripts/serve-example.cjs`). Fake backend: `examples/real-apps/realworld_shared/demo_backend.cljs` via `:fx-overrides` in `core.cljs`. **Not** AbortController / ACL / durable offline.

**Eight resources.** Six viewer-scoped (`:realworld/articles`, `article`, `comments`, `profile`, `author-articles`, `favorited-articles`); `:realworld/tags` global; `:realworld/feed` session. **No `:infinite`.** Pagination is offset `:page` in params + route `:keep-previous?`. Policy: `stale-after-ms` **60000**, `gc-after-ms` **5 min** (`resources.cljs` 41–50). Reads retry via `rh/data-fetch-retry` (014); writes do not.

**Scope.** `reg-resource-scope :realworld/viewer` has **three** branches: username → viewer; blank token → `:anonymous`; token present / username nil → **nil fail-closed** until restore + `[:rf.route/replan-resources]` (`scope.cljs` 107–119). Session resolver nil when logged out.

**Favourite.** `:optimistic-tags` patches viewer `[:article slug]` + session `[:feed]`; `:populates` seeds detail; `:invalidates` viewer `{[:article slug] [:article-list] [:favorited-articles username]?}` + session `[:feed]`; `:on-conflict :invalidate` (`mutations.cljs`).

**Ceremony control.** `examples/real-apps/realworld_http/` still writes `{:status :data :error}` slices (`articles.cljs`, `comments.cljs`, `profile.cljs`, `favorites.cljs`) and **hand-rolled optimistic snapshot + `:article/favorite-rollback`**. Shared: `realworld_shared/{schema,http,demo_backend,markdown,avatar}`. Compare **matched slices**, not file counts.

**Capability examples (Conduit is anchor, not ceiling).**

- `examples/capabilities/resources/infinite_feed/` — `:infinite true` + `load-more`. J6 infinite arm.
- `examples/capabilities/resources/linearlite/` — optimistic board. J5 productization; **not** TanStack DB.
- `examples/capabilities/ssr/resources_ssr/` — hydrate resource cache, no duplicate flash. J9.

**Conformance fixtures (six).** `spec/conformance/fixtures/resources-dedupe-join.edn`, `resources-ensure-fresh-skip.edn`, `resources-owner-release-gc.edn`, `resources-refresh-error-keeps-data.edn`, `resources-scope-fail-closed.edn`, `resources-stale-reply-suppression.edn`. Implementation-independent pins. Not a user-facing win by themselves.

**Xray 024.** Static registry, live instance table, work ledger, invalidation graph, scope audit, orphaned-owner lint. Comparator is Query Devtools. Do not assume a dedicated Resources MCP.

**Tutorial.** Part 1 is **offline app-db** (`docs/resources/tutorial/01-pages-and-state.md`). Part 2 adds `day8/re-frame2-resources` `:local/root` and `https://api.realworld.io/api`, require `re-frame.resources` (`02-server-data.md`). Tutorial articles are **`:scope :rf.scope/global`** — a teaching simplification vs Conduit's viewer-scoped articles. Stall log starts here.

**Translation page** `docs/resources/coming-from-tanstack-query.md` — starting evidence. Closing sentence "Every Landed claim is pinned by tests" is itself a **claim**. Caricatures to challenge in Phase C: fetch-from-render required; QueryClient always process-global; TanStack always restores `onError` context as *library-enforced* (it is the **documented cache recipe**, and there is a **UI recipe that needs no rollback**).

---

## 7. Defaults vs matched policy (for Phase B)

| Knob | Query documented default | Resources documented default | Conduit actual |
|---|---|---|---|
| Freshness | `staleTime: 0` (always stale) | absent `:stale-after-ms` = **never time-stale** | **60s** |
| GC | 5 min | 5 min (`300000`) | **5 min** |
| Read retry | 3× silent | 014 policy on the request | `rh/data-fetch-retry` |
| Write retry | typically 0 | none unless `:request` asks | none |
| Focus/reconnect | on, for stale queries | frame `:revalidate-on`; only **stale AND owned** | wired in Conduit `core.cljs` (README) |

Fewer requests from never-time-stale is **not** automatically an efficiency win. Score against the app's freshness requirement.

---

## 8. What would change Phase B — and what would not

**Changes the job set.** Query v5 practical workflow (already in the starter table). RTK tags (J4 cousin). SWR first-hour (J1). Apollo entity job as **OUT** with a named HTTP substitute. TanStack DB as a **separate** local-first row trigger, not a Query row. Persist plugin as J-offline **evaluate**. `useQueries` as distinct from static route plan. Router loaders as Query's J8 answer.

**Does not change it.** Store5, Angular `resource()`, Phoenix `assign_async`, tRPC, Orval, v6 Solid RC, Query's `notifyOnChangeProps` / `HydrationBoundary` **nouns**.

---

## 9. Limitations of this landscape

- npm `latest` for Query taken from npmjs/Snyk pages (5.102.8, 2026-08-27). Did not run `npm view` in this pass.
- Weak community RealWorld Query ports (fable index search, attributed): `yurisldk/realworld-react-fsd` is v5 but uses no `invalidateQueries`; `jiheon788/react-query-realworld` is v4 and unmaintained. Do not treat either as a library limitation. Grok still did not run a twin.
- Query **`mutation.scope`** serialises overlapping writes — steelman for J5, not a Resources gap.
- Did not run Conduit in Phase A — that is Phase C. Run recipe recorded above.
- This landscape was written **before** sibling reports. Synthesis (2026-09-15) is `sibling-synthesis.md`, not a rewrite of this survey.
)
