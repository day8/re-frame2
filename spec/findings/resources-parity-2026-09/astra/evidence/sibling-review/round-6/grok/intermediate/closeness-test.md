# Phase B — Closeness test (question 1)

**Standing, re-runnable method.** Not a score. Apply against a pinned SHA. This file is the test; `scoreboard.md` is one run.

**Clock.** Designed 2026-09-14 +10:00 against trunk `a5f883b5691b83ac258121eb5c046df87be6d03d`. If HEAD moves, re-check consequential claims; headline form is historical defect → landed repair → evidence still needed.

**Independence.** Written before applying it, and before reading sibling Resources reports.

---

## 0. What "close" means

An experienced TanStack Query (or SWR / RTK Query / Apollo-for-REST) user can sit down at Resources — ideally by cloning Conduit's pattern — and accomplish the **jobs** they came for, without a ceremonial map of runtime-db, at comparable or lower ceremony. The API may be EDN events rather than hooks. The cache may live in a frame. Those are not gaps.

**Unable to do the job**, or only able to do it by dropping into undocumented internals or by hand-rolling what `realworld_http` already does, **is** a gap.

Parity does not require hook count, plugin marketplace, or Devtools chrome. It does require taking the alternatives' **strongest practical workflow** seriously (official Devtools, persist plugin, router loaders) and naming those extras' cost.

---

## 1. How to re-run (without the prompt)

1. Pin `git rev-parse HEAD`, branch, porcelain, `Get-Date -Format "yyyy-MM-dd HH:mm:ss zzz"`.
2. Refresh competitor **majors**: Query `latest` docs + npm version; **TanStack DB** version (local-first row only); RTK tags page; SWR mutate page. Date-stamp URLs.
3. Walk each job below from **four artefacts**: Spec 016 (pull a section, do not recite), trunk code, user docs (`docs/resources/*`, tutorial, translation page), **and** Conduit / the named capability example. Say which artefact a verdict came from.
4. Execute the **minimum subset** in §6. A fake backend proves UI/cache coordination, not AbortController / ACL / durable offline.
5. Fill the four fields + ceremony + citations. Do not collapse into `WIN`.
6. Translation-page Landed rows: name the pinning test or fixture; say whether it exercises the **described** behaviour; count the closing "every Landed pinned" claim true / partly / false.
7. If HEAD moved during the run: preserve the original evidence baseline; do not leave old present-tense defects under a new header.

**Rerun triggers (row-specific).**

| Event | Invalidates |
|---|---|
| TanStack Query **major** (v6 stable on React) | Devtools, SSR, persist, prefetch, default `staleTime`/`gcTime`/`retry` rows (J1–J3, J8–J10, J13, J16) |
| TanStack **DB** release | **Only** the local-first / entity-consistency note — after attributing capabilities to `@tanstack/db`, not Query core |
| Resources EP / Spec 016 deferred slice landing | matching `OUT` / `TARGET` / `SPEC-ONLY` row |
| Conduit rewrite | J4–J8, J11, J17–J19 |
| Tutorial rewrite | discoverability on J1, J4, J5, J7 |
| `resources_ssr` / `infinite_feed` rewrite | J6 / J9 |

Do **not** propose a new permanent benchmark service. Automatable residue is the six conformance fixtures + named JVM/CLJS tests in §7.

---

## 2. Four independent fields

Do not collapse into a single `WIN`.

| Field | Vocabulary |
|---|---|
| **Capability** | `ABSENT` / `PARTIAL` / `COMPLETE` / `SPEC-ONLY` / `TARGET` / `OUT` |
| **Discoverability** | `TAUGHT` (tutorial **and** Conduit README) / `UNDERMARKETED` / `INTERNAL` |
| **Evidence** | `EXERCISED` (this run walked it) / `SOURCE-TRACED` / `DOCUMENTED-ONLY` / `UNVERIFIED` |
| **Comparison** | `BEHIND` / `MATCH` / `AHEAD` / `DIVERGENT` vs a **named** comparator |

Rules:

- `DIVERGENT` only when the **job is still served** another way you can name.
- `AHEAD` needs a **structural** reason (not "we have more flags").
- `SPEC-ONLY` = specced, not honestly shipped in code **and** not exercised by Conduit *or* a capability example. Absence from Conduit alone is **not** `SPEC-ONLY` if `infinite_feed` / `resources_ssr` / `linearlite` does it — that is `UNDERMARKETED` or `UNVERIFIED`.
- `OUT` = refused for this artefact (GraphQL-in-016, process-global cache, fetch-from-subscribe). Name the omit-path.
- `TARGET` = deferred with a fires-when trigger already in Spec 016.
- A `MATCH` at 2× ceremony is `BEHIND`.
- **Twice as many declarations is not twice the effort** — count *where* a change is made (one registration vs N call sites).
- Count ceremony on the **first** read *and* the **tenth** in a real app. A `AHEAD` on the tenth that is a `BEHIND` on the first is a first-hour finding, not a surplus.

**Defaults vs matched policy.** Freshness / GC / focus-reconnect: once at **documented defaults** (Query `staleTime: 0` vs our never-time-stale; both GC ~5 min) and once with **explicitly matched** policies. Conduit's actual settings: `stale-after-ms 60000`, `gc-after-ms 5 min`. Fewer requests from keeping older data is not automatically an efficiency win.

**Avoiding Resources can be the ergonomic answer** for an uncached one-shot GET (managed HTTP). Score that boundary, do not force every GET through `reg-resource`.

---

## 3. Job set (~20). Conduit is the anchor, not the ceiling

| Id | Job | Why it is in the set | Primary comparator | Where we look first |
|---|---|---|---|---|
| J1 | First cached read: register, cause, project, see data | `useQuery` on mount vs three lanes; first vs tenth | Query hook; SWR one-liner | Tutorial Part 2 + Conduit `resources.cljs` + route `:resources` |
| J2 | Dedupe identical in-flight reads; cache hit when fresh | Core cache job; **positive control** | Query | Conformance `resources-dedupe-join` + `resources-ensure-fresh-skip`; Conduit navigate-return |
| J3 | Stale-while-revalidate: show data, refetch in background | Default `staleTime 0` vs never-time-stale | Query / SWR | Conduit 60s policy; defaults vs matched |
| J4 | After a write, the right reads refresh without a remembered `onSuccess` list | Conduit favourite / follow / comment | **RTK tags** (closest); Query `onSuccess` | Conduit `mutations.cljs`; plant a missing tag |
| J5 | Optimistic update + automatic rollback; contested concurrent write | Favourite heart; EP-0019 `:on-conflict` | Query cache recipe **and** UI recipe; SWR `rollbackOnError` | Conduit favourite; `linearlite`; optimistic settle tests |
| J6 | Paginated list **and** infinite load-more | Two different jobs; Conduit only does offset | Query `useInfiniteQuery` | Conduit `:page` + `:keep-previous?`; `infinite_feed` for load-more |
| J7 | Cache leak across users / tenants / logout | Scope identity; Query's structural footgun | Query `queryKey` convention | Conduit `scope.cljs` three-branch; `resources-scope-fail-closed`; logout `clear-scope` |
| J8 | Route starts the fetch before the view; no mount-then-fetch waterfall | Do not caricature Query — it has loaders | Query **router loader** / `queryClient.query` | Conduit `routing.cljs`; Query prefetch guide |
| J9 | SSR: wait on blocking reads, hydrate without duplicate flash, no cross-request leak | Frame-per-request vs per-request `QueryClient` | Query HydrationBoundary (correctly isolated) | `resources_ssr` — **not** Conduit (client-only) |
| J10 | Focus / reconnect / poll refetch of *owned stale* entries | Opt-in vs Query defaults | Query | Conduit `core.cljs` listeners; `:poll-interval-ms` if present |
| J11 | First-load error vs refresh error (keep data, warn) | Conduit README `:refresh-error` | Query `isPending` vs `isFetching` + `error` | Conformance `resources-refresh-error-keeps-data`; Conduit if fake backend can 5xx |
| J12 | Test a page without a network: deterministic resource/mutation fixtures | Story / test_support vs MSW | Query + MSW | `force-fx-stub` / demo_backend / `http.test-support` |
| J13 | See *which write* staled *which read* | Xray vs Query Devtools | Query Devtools (official extra) | Xray 024; plant missing invalidation |
| J14 | Agent can enumerate keys, run a mutation, observe invalidation | Shape, not a bolt-on MCP | Query ESLint plugin + Devtools | Registry + traces; **do not infer a completed loop from a tool name** |
| J15 | Cancel in-flight; ignore stale/superseded replies | Work ledger | Query AbortController | Conformance `resources-stale-reply-suppression`; fake backend ≠ AbortController |
| J16 | GC after owners leave; two mutation instances at once | Owners vs observers | Query `gcTime` + mutation observers | `resources-owner-release-gc`; Conduit two favourites |
| J17 | Auth headers once, not per resource | 014 interceptors vs `queryFn` closures | Query `queryFn` / axios interceptor | Conduit `:realworld/bearer-auth` |
| J18 | Follow-up after a write without smuggling `onSuccess` into the view | `:reply-to` | Query `onSuccess` | Editor / settings `:reply-to` |
| J19 | Leave a route mid-flight; delayed reply must not commit; return reuses or refetches honestly | Owners vs cancel vs ignore | Query unmount + gcTime | Conduit navigate-away; delayed completion if we can control the stub |
| J20 | Dependent / serial read (B needs A's result) without a hidden fetch in the view | `:after` is dispatch-order only, not a data-waterfall (016). The *job* can still be served by `:reply-to` (Conduit editor seeds a draft). Score **DIVERGENT** if a continuation does it; **TARGET** for planner-derived params from A's **data**; not BEHIND merely because `enabled` is a different spelling | Query `enabled` | Spec 016 `:after`; `article_editor.cljs` `:reply-to` |

**Folded, not extra rows.**

- Changing sets of parallel reads (`useQueries` over a dynamic list): **not** covered by Conduit's static route plan. Mark **UNVERIFIED** on J8/J20 notes; do not pretend the home `:resources` vector is `useQueries`.
- One-shot uncached GET: **boundary of J1** — managed HTTP is the ergonomic answer. Not a twentieth-first row.
- Offline persistence: **TARGET** (Spec 016 deferred). Evaluate the smallest Resources-shaped answer in `advantage.md` before recommending continued deferral. Query ships this as an **official plugin**.
- GraphQL entity cache: **OUT** (negative control). Served in Conduit by populate + tag refetch (cost named on J4/J5).
- Process-global shared cache: **OUT** / refused (negative control). Query's module-level client is the documented footgun; their SSR guide already tells you not to.

**Declined as rows** (gold-plate): `notifyOnChangeProps`, `HydrationBoundary` as a noun, OpenAPI codegen, tRPC inference, TanStack DB live queries (rerun trigger only).

---

## 4. Ceremony accounting

For each job record, in user language:

- **Concepts** the author must hold (register / cause / project; or `queryKey` + `queryFn` + `QueryClient`).
- **Encodings** (EDN maps vs hook config).
- **Files / boot calls** (require artefact, interceptor, frame config).
- **Where a change is made** on the tenth feature (one `reg-mutation` vs N `onSuccess` sites).
- **Recovery from an ordinary mistake** (forgot a tag: Query silent vs our mismatch warning; forgot scope: loud).
- **First hour vs tenth hour.**

`realworld_http` is the **within-repo control**. If Resources cannot beat the hand-rolled sibling on a job, it is not `AHEAD` of TanStack either. Count what Resources **removed** (status slices, rollback events) and what it **added** (registrations, scopes, route metadata), including what both delegate to `realworld_shared/`.

---

## 5. Honesty checks (or the test fails open)

1. Spec/docs "Landed" vs Conduit (does the example *do* it?) vs capability example (if Conduit does not).
2. "We have a primitive that *could*" vs "an author *does* this without ceremony."
3. Built-in vs plugin vs paid on the comparator side. Query Devtools and persist are official extras — include them, with setup named.
4. **Negative control:** GraphQL entity cache is `OUT`; process-global shared cache is refused. Offline persistence and the one-line colocated read are **jobs to evaluate**, not silent `DIVERGENT`s.
5. **Positive control:** keyed cache + dedupe (J2), pinned by `resources-dedupe-join`. **Fault it** (break the fake backend or a canned stub / skip the join) and confirm the pin goes red. A test that stays green under the fault does not pin the row. Deliberate scope leak: `resources_scope_leak_boundary` test — run or source-trace.
6. **Planted missing invalidation** must use a tag **member tags will not already provide** (Conduit lists tag `[:article slug]`; forgetting `[:feed]` can be masked if the feed also carries article tags). The unmasked case is collection **membership** (`[:favorited-articles username]`).
7. **Keep-previous:** wait on **data identity** (first title / page param), not “list visible.”
8. **Contested optimistic:** record the **interval** between an older rejection and recovery, not only the settled UI. Steelman Query’s UI `variables` recipe and `mutation.scope`, not only the cache snapshot recipe.
9. Do not let many minor `MATCH`es cancel one leak or one install stall.
10. No weighted percentage required. If one is computed: show denominator, weights, exclusions, and how `UNVERIFIED` moves it. Prefer a **categorical headline**.
11. Challenge caricatures on `coming-from-tanstack-query.md` before scoring Query `BEHIND` on J8/J9/J5.
12. Request **ledger** (count), not a timeout, to claim "no duplicate fetch." Controlled completion / fake clock, not `sleep`, for delayed replies.
13. Do not infer an agent workflow from enumerable metadata or a tool name. Name host (JVM vs browser), skill/MCP surface, and what a planted fault still cannot discover.
14. Function-valued `:invalidates` / `:optimistic` patches are **not** statically enumerable from the registry alone.
15. Classification, hydration payload, epoch export, and restore/replay are **different consumers**. Restoring client history cannot undo a server write.

---

## 6. Minimum execution this run (Phase C)

Unless a named limitation blocks it:

1. **Tutorial stall log.** Follow `docs/resources/index.md` + tutorial `01`–`02` (and `04` if cheap) *literally*. Record stalls, retired spellings, missing causes. Part 1 is offline by design.
2. **Conduit: read, navigate, reuse.** Feed → filter/page → detail → return. Cache hit, in-flight dedupe, stale refresh if we can observe it. Force a refresh failure if the demo backend allows; else source-trace J11 and say so.
3. **Favourite across views**, including failure and overlap. Same article in detail and lists. Optimistic apply; reject a mutation; overlapping writes. Favorited-list membership, not only a boolean. Compact TanStack slice **outside this repo** if no maintained RealWorld Query app fits.
4. **Change viewer during work.** Start a scoped read, log out or Alice→Bob, release a delayed Alice reply. Cold-boot token unresolved branch (source-trace if we cannot freeze restore).
5. **Leave and return** (J19).
6. **Plant a missing invalidation** (J4/J13). Prefer Xray/public inspection. Restore the tag. Re-fault. Compare Query Devtools on the same *kind* of mistake (docs + inference if we cannot run Devtools).
7. **`realworld_http` contrast** for J4/J5.
8. **Infinite-feed and/or SSR** from capability examples, or mark `UNVERIFIED`.

**Boundary of the fake backend.** `demo_backend.cljs` is an in-process transition. It does not prove wire protocol, real AbortController cancel, durable offline, or server authorization. Scope is not server ACL.

**Limitation, not evidence:** unavailable browser, backend, or credential.

---

## 7. Automatable residue (already in-tree)

Name these as pins; do not duplicate them as a new service.

| Job | Pin |
|---|---|
| J2 dedupe | `spec/conformance/fixtures/resources-dedupe-join.edn` |
| J2 fresh-skip | `resources-ensure-fresh-skip.edn` |
| J7 fail-closed | `resources-scope-fail-closed.edn` + `resources_scope_leak_boundary*` tests |
| J11 refresh-error | `resources-refresh-error-keeps-data.edn` |
| J15 stale reply | `resources-stale-reply-suppression.edn` |
| J16 GC | `resources-owner-release-gc.edn` |
| J5 optimistic | `implementation/resources/test/re_frame/resources_optimistic_settle_cljs_test.cljc` (success commit, failure rollback, conflict invalidate, `:force`) |
| J6 infinite | `resources_infinite_*_cljs_test.cljc` + `infinite_feed_example_cljs_test.cljs` |
| J9 SSR | `resources_ssr_cljs_test.cljc` / `resources_infinite_ssr_restore_cljs_test.cljc` |

Positive-control fault: plant a second fetch on an in-flight key (or disable the join) and confirm `resources-dedupe-join` (or the unit equivalent) goes red.

---

## 8. Perspectives (do not average them into one number)

- **Framework-aware newcomer:** tutorial Part 1–2, first `reg-resource`, forgot the cause (permanent `:idle`).
- **Maintainer adding a write:** tenth mutation — one `:invalidates` vs N `onSuccess`; forgot a tag (mismatch warning vs silent).
- **Assistant collaborating:** enumerate registry, plant a missing tag, see Xray/trace connect write→read. Function-valued tags are not static.

Separate **one-time setup** (artefact require, interceptor, frame `:revalidate-on`) from **marginal feature cost**.

---

## 9. Categorical headline template (for the scoreboard)

One sentence, no percentage:

> Resources is **COMPLETE** on keyed HTTP cache jobs (J2 positive control), **AHEAD** on leak-boundary and causal invalidation where Conduit productizes them, **BEHIND** on first-hour colocated read and official persist, **OUT** on GraphQL entity cache, **TARGET** on offline. The first thing to do is <honest-claimed-win | discoverability | real BEHIND>, not a new cache.

Fill after Phase C. Many MATCH rows must not cancel one leak or one tutorial stall.
)
