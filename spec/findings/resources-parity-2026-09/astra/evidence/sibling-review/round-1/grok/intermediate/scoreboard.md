# Phase C — Scoreboard (this run)

**Applied** 2026-09-14 22:41 +10:00 against evidence pin `a5f883b5691b83ac258121eb5c046df87be6d03d`. Method: `closeness-test.md`. Landscape: `landscape.md`.

**HEAD moved** before close to `f680007c6b` (`afb2343f0c` Story parity-label docs + beads checkpoints). Not a Resources/Conduit change. No historical-defect → landed-repair line for this artefact. Evidence-still-needed items (Xray on flagship, tutorial scope, persist) are unchanged.

**Comparators.** TanStack Query **v5** (`@tanstack/react-query` 5.102.8 npm latest, 2026-08-27; docs `tanstack.com/query/latest`, 2026-09-14). RTK Query tags (automated-refetching, 2026-09-14). SWR mutate (2026-09-14). Apollo cache = J-entity **OUT**. TanStack DB 0.9.0 is **not** Query core.

**Executed this run**

| What | Result |
|---|---|
| Tutorial 01–02 + 04, `docs/resources/index.md` | **Read literally** (stall log below). Did not scaffold a fresh `conduit/` project (Clojars/`local/root` cost recorded, not re-paid). |
| Conduit browser | `http://127.0.0.1:8051/` via `npm run dev:example -- examples/realworld-resources`. Playwright: `intermediate/conduit-walk.cjs` + `conduit-walk-favorite.cjs`. Fake backend `demo_backend.cljs`, 20ms delay. **Not** wire / AbortController / ACL. |
| JVM pins (`implementation/resources`, `clojure -M:test -n …`) | scope-leak 9/38 green; optimistic-settle 14/124 green; mutation-scope-mismatch 6/20 green; runtime 46/2635 green (includes `ensure-dedupes-in-flight`, `refresh-failure-keeps-data`, `stale-reply-is-suppressed`, `fresh-skip` via sibling fixtures conceptually). |
| `realworld_http` | source-traced matched slices (not file counts). |
| Infinite / SSR examples | source-traced READMEs + existing tests; **not** browser-run this pass. |
| TanStack RealWorld twin | **not built.** No maintained idiomatic Query Conduit pinned. Query side of J5 is **docs**, not a dual-app run. |
| Plant missing tag in Conduit | **not done** (would edit a tracked file). Equivalent: mismatch-warning suite (green). |
| Positive-control **red** plant | **not done** (would edit a tracked fixture). Pin content source-traced: `ensure-dedupes-in-flight` asserts same generation + two owners. |
| HEAD movement | none this run. |

**Categorical headline**

> Resources is **COMPLETE** on the keyed HTTP cache jobs Conduit actually runs (J2 positive control, J4/J5 favourite loop, J7 fail-closed scope), **AHEAD** of Query on leak-boundary and on *tenth-write* invalidation (MATCH with RTK tags; AHEAD of Query `onSuccess` lists), **BEHIND** on the first-hour colocated read and on official persist, **OUT** on GraphQL entity cache, **TARGET** on offline. Several translation-page **Landed** rows are **UNDERMARKETED** (infinite, SSR, polling, hover-prefetch, Xray-on-the-flagship). The first thing to do is make those claimed wins honest in Conduit/tutorial — not a new cache.

---

## Job table

Ceremony notes sit under the table. Comparison is vs the **named** comparator.

| Id | Capability | Discoverability | Evidence | Comparison | Notes |
|---|---|---|---|---|---|
| J1 First cached read | COMPLETE | TAUGHT (tutorial 02 + Conduit README) | EXERCISED (home list, 10 articles, Hello Conduit) | **BEHIND** Query/SWR on **first** hour; **MATCH** on tenth | Three lanes vs one hook. Index.md shows `:idle` until `ensure`. Tutorial Part 1 is offline app-db — extra concept before the job. |
| J2 Dedupe + fresh-skip | COMPLETE | UNDERMARKETED (README mentions; tutorial light) | SOURCE-TRACED (runtime `ensure-dedupes-in-flight`; fixtures `resources-dedupe-join`, `resources-ensure-fresh-skip`). Browser did **not** count a request ledger (in-process stub, no HTTP). | MATCH Query | Positive control. Fault-to-red **not planted** this run. |
| J3 SWR / background refetch | COMPLETE | TAUGHT (Conduit 60s policy in `resources.cljs`) | SOURCE-TRACED defaults; Conduit policy EXERCISED only as settings, not a 60s wait | **DIVERGENT** defaults (Query staleTime 0 vs our never-time-stale); **MATCH** at Conduit’s 60s | Native-default request counts are not an efficiency win. Matched-policy: both can do 60s. |
| J4 Invalidate after write | COMPLETE | TAUGHT (tutorial 04 + Conduit README + mutations.cljs) | EXERCISED: comment appeared after post (2 comments); Article 2 appeared on Favorited tab (`onTab: 1`); unfavorite dropped Hello from that tab | **AHEAD** Query `onSuccess` lists; **MATCH** RTK `invalidatesTags` | Structural vs Query: one registration. vs RTK: same idea; our extras are scoped descriptors + event-record. Planted missing tag → JVM mismatch warning, not Conduit edit. |
| J5 Optimistic + contested rollback | COMPLETE | TAUGHT (README + mutations) | EXERCISED apply (Article 2 0→1 with `optimistic`/`active` class; detail `btn-primary`). Rollback + `:on-conflict` **JVM only** (14 tests). Browser has no failing favourite (logged-out click **navigates to login**). | **AHEAD** Query **cache** recipe (author-owned inverse; contested restore can clobber). **MATCH** SWR `rollbackOnError` on simple rollback. Contested default is the remaining structural edge. Query **UI** recipe needs no rollback — do not caricature. | Dual-app TanStack slice **not run**. |
| J6 Paginate + infinite | COMPLETE (both kinds exist) | Paginate: TAUGHT (Conduit). Infinite: UNDERMARKETED (`infinite_feed`) | EXERCISED numbered pages + **keep-previous flash seen** (`keepingPreviousSeen: true`). Infinite: SOURCE-TRACED example + `resources_infinite_*` tests | MATCH Query numbered+infinite, **split across examples** | Conduit is offset, not `:infinite`. Absence from Conduit ≠ SPEC-ONLY. |
| J7 Cache leak / logout | COMPLETE | TAUGHT (README + `scope.cljs` three-branch) | EXERCISED logout UI; SOURCE-TRACED fail-closed + leak-boundary 9 tests. Demo backend **one-user flags for everyone** — do not read post-logout heart as a Resources leak (see walk note). | **AHEAD** Query key-segment convention | Cold-boot token/username nil → `nil` fail-closed, then `replan-resources`. Not server ACL. |
| J8 Route starts fetch | COMPLETE | TAUGHT (routing.cljs + README) | EXERCISED (home/detail load without view-side fetch). SOURCE-TRACED `:resources` metadata | **MATCH** Query **router loaders** / `queryClient.query`. **AHEAD** of *mount-only* `useQuery` | Translation page overstates Query as mount-then-fetch. Practical Query workflow has loaders. Remaining: a **subscription cannot fetch** (hook still can). |
| J9 SSR hydrate | COMPLETE in `resources_ssr` | UNDERMARKETED | SOURCE-TRACED example README + `resources_ssr_cljs_test`. Conduit **client-only**. Not browser-run | MATCH Query HydrationBoundary **when** both isolate per request. **AHEAD** on default topology (frame-per-request vs “NEVER module-level client”) | Substrate ≠ product. Query’s SSR guide already shouts the leak. |
| J10 Focus / reconnect / poll | COMPLETE | UNDERMARKETED (Conduit wires `:revalidate-on #{:focus :reconnect}`; no `:poll-interval-ms`) | SOURCE-TRACED `core.cljs` + polling tests. **Not** window-blurred this run | MATCH Query when opted in; Query **defaults on**, we **opt in** | Default divergence like J3. |
| J11 First-load vs refresh error | COMPLETE | TAUGHT (README `:refresh-error`; UI `list-refresh-warn`) | SOURCE-TRACED `refresh-failure-keeps-data` + fixture. **Not** forced 5xx in browser (stub doesn’t expose a UI to fail a refresh) | MATCH Query `isPending`/`isFetching`+error | UI branch exists. |
| J12 Test without network | COMPLETE | UNDERMARKETED (test_support; Story `force-fx-stub` is login-form, not Conduit) | SOURCE-TRACED. Conduit demo_backend **is** the networkless app | MATCH Query+MSW at different ceremony | Fake backend ≠ MSW realism. |
| J13 Which write staled which read | COMPLETE in Xray 024 | **INTERNAL** on the flagship: Conduit `index.html` has **no** Xray host. Tutorial index **does**. | SOURCE-TRACED 024. **Not** opened Xray this run | **AHEAD** Query Devtools **if** Xray is attached (causal event). **BEHIND** on “open the example and see it” | Query Devtools = official extra package. Fair comparison includes that extra. |
| J14 Agent enumerate / mutate / observe | PARTIAL | INTERNAL | SOURCE-TRACED registry + traces. **No** Resources MCP inferred. Function-valued `:invalidates` **not** statically enumerable | DIVERGENT (shape) vs Query ESLint plugin | Do not claim a completed agent loop. |
| J15 Cancel / ignore stale reply | PARTIAL | UNDERMARKETED | SOURCE-TRACED `stale-reply-is-suppressed` + fixture. Leave-and-return browser hammer was too fast (ended on page 2). **Not** AbortController | MATCH ignore-stale; **UNVERIFIED** real cancel | Stub uses `:dispatch-later`, not fetch abort. |
| J16 GC + two mutation instances | COMPLETE | UNDERMARKETED | SOURCE-TRACED owner-release-gc fixture + README instance ids. Two favourites: instance `[:favorite slug]` per article | MATCH Query gcTime / observers | Conduit 5 min GC. |
| J17 Auth headers once | COMPLETE | TAUGHT (README interceptor) | SOURCE-TRACED `:realworld/bearer-auth` in `core.cljs`. 014 seam, not a resource option | MATCH Query axios interceptor / `queryFn` closure, **AHEAD** of per-resource headers | Missing Query `retry` on Resources is **not** a GAP if 014 serves it. Conduit reads use `data-fetch-retry`. |
| J18 Follow-up without view `onSuccess` | COMPLETE | TAUGHT (README `:reply-to`; tutorial 04) | SOURCE-TRACED editor/settings. **Not** saved an article this walk | **AHEAD** Query view-colocated `onSuccess` | |
| J19 Leave mid-flight; delayed reply | COMPLETE in spec/runtime | UNDERMARKETED | SOURCE-TRACED stale-reply + route owner release. Browser hammer **inconclusive** | MATCH Query unmount | Need controlled completion, not 20ms + double-click. |
| J20 Dependent / serial read | PARTIAL | INTERNAL | SOURCE-TRACED Spec 016: `:after` is **dispatch-order**, not a data-waterfall. Conduit does not need B-from-A-data | **BEHIND** Query `enabled` for data-dependent params | Not silent DIVERGENT. A later entry cannot read an earlier entry’s **loaded data** at plan time. |

**Negative controls.** GraphQL entity cache: **OUT** (016 §What Spec 016 does NOT cover). Process-global cache: **OUT** / refused. Offline persist: **TARGET** (deferred slice). One-shot GET: **avoid Resources** (managed HTTP) — evaluated as J1 boundary, not a gap.

**Positive control.** J2 `ensure-dedupes-in-flight` + `resources-dedupe-join.edn`. Green this run. Red plant not executed (tracked-file constraint).

---

## Ceremony: first vs tenth; HTTP sibling

**First read (newcomer).** Query: `QueryClient` + `useQuery` in the component (or a loader). Resources: require `re-frame.resources` + `re-frame.http.managed`, `reg-resource` with **required scope**, route `:resources` **or** an `ensure`, then a passive sub. Tutorial Part 1 adds a whole offline app-db feed you later throw away. **First hour: Query/SWR cheaper.**

**Tenth write (maintainer).** Query: another `onSuccess` `invalidateQueries` list, easy to forget a key. RTK: another endpoint’s `invalidatesTags`. Resources: another `reg-mutation` with tags; existing reads that already tag themselves refresh. **Tenth hour: Resources ≈ RTK, cheaper than Query lists.** Twice as many declarations is not twice the effort — the change is **one registration**, not N call sites.

**`realworld_http` control (matched slices, shared `realworld_shared/`).** HTTP still writes RemoteData slices (`:status :loading|:fetching|:loaded|:error`) and load/fail/cancel triples (`articles.cljs`, `comments.cljs`, `profile.cljs`, `favorites.cljs`); route `:on-match` dispatches loads; `find-article` scans four list slices; **hand-rolled** `:article/favorite-rollback` snapshots `{favorited, favoritesCount}`. Resources **removed** those slices/rollbacks and **added** 8 `reg-resource`, 2 `reg-resource-scope`, route `:resources`, `:optimistic-tags`. Shared: schema, demo_backend, page maths, markdown, avatar. Resources **beats the sibling** on J4/J5. Therefore those rows may be AHEAD of Query. Tags machine in HTTP is a different modelling choice, not counted as a Resources win.

HTTP **optimistic-posts comments** with temp-ids; Resources **does not** — comments invalidate-and-refetch only. That is a product choice, not a missing primitive (`:optimistic` exists; linearlite uses it).

---

## Conduit walk (facts)

Ledger: `intermediate/conduit-walk/ledger.json`. Screenshots in the same folder.

- Home: 10 articles, first **Hello, Conduit**, pagination 1–3, popular tags. CSS is the repo `_shared` theme, not classic Conduit chrome.
- Page 2: URL `...?page=2`, first **Article 10**. **`list-keeping-previous` was observed.**
- Back to page 1 too fast: list still showed Article 10 while URL dropped `?page=` — keep-previous made “list visible” a bad wait. Not scored as a leak.
- Detail + comments list; comment post **walk-comment-*** appeared (invalidate comments).
- Logged-out favourite → **login page** (not a failed mutation).
- Sign-in `demo@conduit.dev` / `demo` → navbar **demo**.
- Hello was a **seed favourite** (count 1). Click **unfavourited** (0, `optimistic` class). Favorited tab then **lacked** Hello — membership in the negative.
- Second script: favourite **article-2** 0→1, `active` class, detail `btn-primary (1)`, **onTab: 1**. Membership in the positive.
- Your Feed tab **visible** when signed in (screenshot 06). Script did not switch to it (waited on profile list). **UNVERIFIED** feed-after-follow.
- Logout: heart `active` on Hello. **Inference, not a leak:** anonymous scope still held the **pre-login** seed (favourited), 60s-fresh, not invalidated by demo’s viewer-scoped mutation. Demo backend also uses one user’s flags for every principal. Do not file a Resources leak from this screenshot.

**Refresh failure, overlapping older-failure-wins, delayed Alice reply after switch:** not executed (no fail injection UI; 20ms stub; one demo user). JVM covers rollback/conflict/scope.

---

## Tutorial stall log (literal read)

| Stall | Where | Kind |
|---|---|---|
| Three runtimes (Node, JDK, Clojure CLI) + **not on Clojars** (`:local/root`) | tutorial index | packaging. Pre-alpha honesty, still a first-hour tax. |
| Part 1 is **offline app-db** with `{:status :loaded}` RemoteData | 01 | extra concept; Part 2 replaces it. Easy to copy Part 1 into production. |
| Forgot `(:require [re-frame.resources])` | 02 warning | loud `:rf.error/resources-artefact-missing` — good. |
| Tutorial articles `:scope :rf.scope/global` | 02 | **contradicts Conduit** (viewer-scoped articles because `favorited` is per-viewer). Copying tutorial into a RealWorld payload **is** the leak the flagship exists to prevent. |
| Tutorial 04 favourite invalidates **global** article tags | 04 | same mismatch. Would trip `:rf.warning/mutation-scope-mismatch` against Conduit-shaped reads. |
| Hosted API `https://api.realworld.io/api` in 02 vs README `https://api.realworld.show/api` | 02 vs Conduit README | **docs drift.** Current RealWorld OpenAPI server URL is `https://api.realworld.show/api` (openapi.yml, 2026-09-14). Offline stub path is documented. |
| Index.md sample resource is global, shows idle-until-ensure | index | cause is taught; scope is oversimplified. |
| Xray in tutorial HTML, **absent** from Conduit `index.html` | tutorial vs flagship | J13 not discoverable in the example people clone. |

No retired `useQuery`-era spellings in the tutorial path followed. Public vocabulary matches trunk (`reg-resource`, `ensure`, `:rf/resource`).

---

## Translation-page scorecard vs this run

**Closing claim** (“Every Landed claim is pinned by tests”): **partly true.**

- **True** as “a JVM/CLJS test or conformance fixture exists for the *runtime* behaviour” for keyed cache, SWR window, dedupe, fresh-skip, GC, invalidation, mutations, populate, optimistic settle, polling, focus/reconnect, prefetch, infinite, keep-previous, SSR, Xray helpers.
- **False** as “the *described user-facing job* is pinned **and** Conduit exercises it.” Infinite, SSR, polling, hover-prefetch are capability-example or unit-only. Xray is not on the flagship page. Prefetch “Landed” is narrower than `<Link prefetch>` (page already admits that).
- Did not count every test name against every row in this sitting. Rows without a named pin in §7 of `closeness-test.md` should be treated as **DOCUMENTED-ONLY** until named.

**Caricatures on that page (challenged):**

1. **Fetch-from-render required** — false. Query prefetch/SSR/router-loader guides start fetches before mount. Remaining honest claim: the *default hook* fetches from render; Resources *cannot*.
2. **QueryClient always process-global** — false as library-enforced. SSR guide: never create at module root. Remaining: isolation is **discipline**; ours is **topology**.
3. **TanStack always restores `onError` context** — true of the **cache** recipe; false of the **UI** `variables` recipe (no rollback). Contested clobber is real for the cache recipe.

**Stale rows.** Prefetch Landed overstates vs Conduit (UNDERMARKETED). Infinite/SSR Landed are accurate for the artefact, easy to misread as Conduit. Polling Landed, unused in Conduit.

---

## Honesty leftovers

- No request-ledger proof of “one fetch” in the browser (in-process stub).
- No Xray session, so J13 not EXERCISED.
- No failing favourite in the UI.
- No Alice→Bob delayed reply (one demo user).
- No TanStack twin app.
- No red plant on the dedupe fixture.
- Classification: Conduit **does** classify JWT + settings password (`:sensitive`). Hydration/export/restore are different consumers — not collapsed.
)
