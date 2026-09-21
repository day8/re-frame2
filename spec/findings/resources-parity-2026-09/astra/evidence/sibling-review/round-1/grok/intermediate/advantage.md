# Phase D — Advantage thesis (question 2)

**Try to disprove each advantage before keeping it.** Tiers: **BUILT** (Conduit/tutorial author can do it), **SPECIFIED** (contract + tests, example thin), **IMAGINED** (substrate could, product does not).

Pin `a5f883b569`. Date 2026-09-14 +10:00.

---

## 1. Jobs comparators do poorly *because of substrate*

Candidates, then the disproof.

### 1.1 Fetch hidden in render → waterfalls, untestable views

**Claim.** Hooks that fetch couple declaration, cause, and read. Views are not pure; SSR/tests need extra seams.

**Disproof.** Query’s **practical** workflow already has router loaders, `queryClient.query`, `usePrefetchQuery`, RSC. Fetch-from-render is the *default hook path*, not the only path. RTK hooks also subscribe-and-fetch.

**What remains.** A Resource **subscription cannot fetch**. That is unrepresentable, not a lint. Query can still grow a mount-fetch in a leaf. **Keep, narrower:** “a read is not a cause” is BUILT. “Query cannot prefetch” is false.

### 1.2 User id as a forgettable key segment → silent cross-account cache

**Claim.** `['feed', userId]` omitted is a silent leak.

**Disproof.** Careful teams use key factories + ESLint. Query SSR guide already isolates clients per request. Forgetting is a convention failure, not inevitable.

**What remains.** Forgetting scope is a **registration error**. Nil resolver is fail-closed. Conduit’s three-branch viewer (anonymous / signed-in / token-without-user) is BUILT. **Keep.** This is the one security-shaped advantage that survives a steelman.

### 1.3 Imperative invalidation (`onSuccess` lists)

**Claim.** Writes forget reads.

**Disproof.** **RTK Query already declares tags.** SWR `mutate` filter functions exist. Query users who copy RTK’s pattern are fine.

**What remains vs Query-the-hook-library:** registration-time `:invalidates` is AHEAD of `onSuccess`. **vs RTK:** MATCH on the job; extras are **scoped** descriptors (fail-closed across principals) and the **event record** (which write). Mismatch warning is BUILT (JVM). **Keep vs Query; do not claim vs RTK without the scope/trace extras.**

### 1.4 Author-owned optimistic inverse; contested restore clobbers

**Claim.** `onMutate` snapshot + `onError` restore is a bug farm; contested restore overwrites a newer write.

**Disproof.** SWR `rollbackOnError` records/rolls back for you. Query’s **UI** recipe (render `variables`) needs no cache inverse. TkDodo has a concurrent-optimistic guide.

**What remains.** Runtime-recorded inverse + default `:on-conflict :invalidate` is BUILT (tests + Conduit apply). Query cache recipe still clobbers if you follow the docs literally. **Keep the contested default; do not claim “only we do optimistic.”**

### 1.5 Process-global SSR cache

**Claim.** Module-level `QueryClient` leaks across users.

**Disproof.** Official SSR guide: **NEVER DO THIS.** Per-request client is documented. Isolation is expected professional practice.

**What remains.** Ours is **topology** (one frame per request) so the footgun is unrepresentable. Productized in `resources_ssr`, not Conduit. **SPECIFIED / UNDERMARKETED.** Keep as default-safe, not as “Query cannot SSR safely.”

### 1.6 Devtools cannot name the *event* that staled a key

**Claim.** Query Devtools show keys and observers, not a causal write.

**Disproof.** Redux Devtools + RTK show the action. Query Devtools show the mutation that last touched a query if you correlate by time.

**What remains.** Xray 024’s invalidation graph + `:rf.mutation/*` traces are **specified**. Flagship Conduit **does not mount Xray**. **SPECIFIED, not BUILT for a Conduit cloner.** Do not sell this until the example shows it.

### Dropped

- “We have frames, therefore SSR isolation” without `resources_ssr`.
- “AI-first because keys are enumerable” without a host + planted-fault loop (J14 PARTIAL).
- TanStack DB live queries as a Query gap (wrong package).
- GraphQL entity consistency as a v1 GAP (OUT; Conduit pays extra refetch).

---

## 2. Productized vs latent (Conduit / tutorial)

| Claim | Tier | Evidence |
|---|---|---|
| Views never fetch | **BUILT** | Conduit views subscribe only; routes cause. Walked. |
| Three lanes | **BUILT** | Tutorial 02 + index.md idle-until-ensure. |
| Scope required + fail-closed | **BUILT** | `scope.cljs` three-branch; leak-boundary tests; logout `clear-scope`. Tutorial **undersells** (global articles). |
| Causal `:invalidates` by tag | **BUILT** | Favourite / comment walked; membership on Favorited tab. |
| Optimistic forward-only | **BUILT** apply; **SPECIFIED** rollback/conflict | Browser apply; JVM settle suite. |
| `:reply-to` continuation | **BUILT** in editor/settings | Not walked; source-traced. |
| Frame cache, not app-db | **BUILT** | No `:loading?` in Conduit app-db for reads. |
| Route `:resources` / `:blocking?` | **BUILT** client; **SPECIFIED** SSR wait | Conduit client-only. |
| Auth header once (014 interceptor) | **BUILT** | Not a Resources primitive; Resources is the route to “don’t put it on the resource.” |
| Classification (JWT, password) | **BUILT** in Conduit | Distinct from hydration/export/restore. |
| Xray which-write | **SPECIFIED** | Not on Conduit page. |
| Infinite load-more | **SPECIFIED** | `infinite_feed`, not Conduit. |
| SSR hydrate without flash | **SPECIFIED** | `resources_ssr`. |
| Hover prefetch | **SPECIFIED** | EP-0037; Conduit unused. |
| Machines as resource owners | **SPECIFIED** | `resources_machine_owner_release` test; Conduit auth is a machine that is **not** a resource owner (correct: auth is a command). |
| Agent loop | **IMAGINED** | Registry is enumerable; function-valued tags are not; no Resources MCP. |
| Epoch replay of a stale-page bug | **IMAGINED** as user-facing | Restore tests exist; restoring client history cannot undo a server write. |
| Offline persist | **IMAGINED** / TARGET | Query has official plugin. |

---

## 3. The small set of primitives (user language)

**Keep register / cause / project + tags on writes.**

- **Register** — teach the runtime how (once).
- **Cause** — route, event, or machine makes work happen.
- **Project** — views read.
- **Tags on writes** — a write names what it broke.

That set already does J1–J5, J7, J8, J16–J18. Adding a hook-shaped `useQuery` wrapper would **re-hide the cause** and undo 1.1. Adding a second cache would undo “one obvious way.”

If a primitive must be added later, it should **remove** something: persist should remove hand-rolled localStorage+ensure, not sit beside runtime-db as a second store.

---

## 4. Limits of the data-shaped cache

Live I/O (websockets), clocks, GraphQL graphs, offline write queues, cross-tab: **out or deferred** in 016. Do not invent a second Apollo. Entity consistency in Conduit is populate + refetch (extra request, inconsistency window). A Conduit user notices the heart immediately (optimistic) and list membership after refetch (~20ms in the demo). That is good enough for HTTP CRUD; it is not Linear-class sync (TanStack DB / Electric).

---

## 5. A year from now — scenes (structural or drop)

| Scene | Structural reason Query is worse, or drop |
|---|---|
| CRUD app author (Conduit-class) | **Keep:** tenth write is one mutation; views stay dumb. **Drop as unique:** first hour, TS inference, ecosystem. |
| Multi-tenant SaaS | **Keep:** fail-closed scope. Query *can* do this; teams forget. |
| SSR | **Keep only as default-safe.** Query already documents per-request clients. Productize `resources_ssr` or drop the marketing. |
| An agent | **Drop unless** a planted missing tag is found via public docs + registry without reading 016. Function-valued tags block static enum. IMAGINED. |
| Tester / Story every server state, no network | **Keep as SPECIFIED:** `force-fx-stub` + resource view-model. Not Conduit-productized. Query+MSW is the competitor, not worse structurally. |

---

## 6. What we should stop copying

- Hook-on-mount fetch as the teaching default.
- `queryKey` soup (user id as another segment).
- `onSuccess` invalidation lists (Query; RTK already left this).
- Restoring contested optimistic context.
- Translation-page caricatures of Query (mount-only, always-global client, only-one optimistic recipe).
- Counting file-count “savings” vs `realworld_http` without naming `realworld_shared/`.

---

## 7. Jobs a TanStack user actually misses — smallest Resources-shaped answers

| Miss | Does a re-frame2 SPA author miss it? | Smallest answer | Defer? |
|---|---|---|---|
| One-line colocated read | **Yes**, first hour | Do **not** wrap `ensure` in a view. Teach copy-paste of Conduit’s route `:resources` + one sub. Tutorial Part 1 should not invent a RemoteData feed you delete in Part 2. | Teaching, not a primitive |
| TypeScript inference | **No** for this audience (EDN/Malli). | `:params-schema` / `:data-schema` already identity+decode | No TS layer |
| `useQueries` dynamic list | **Maybe** | Unverified. Static route plan ≠ dynamic set. Smallest: an event that maps ids to `ensure`s, views still project. | Don’t build `useQueries` |
| Suspense | **Maybe** | Substrate/adapter, not Resources | Out of 016 |
| Persisters | **Sometimes** | Official Query plugin exists. Smallest Resources-shaped: dehydrate allowlisted **fresh** entries to storage on hide, rehydrate as SSR does — **one cache**. Do not start a second store. Evaluate when a consumer needs reload survival (016 fires-when). | **Defer with a named how-to**, don’t silently DIVERGENT |
| Ecosystem / Devtools chrome | **Yes** if Conduit doesn’t show Xray | Mount Xray on the flagship. Don’t clone Query Devtools UI. | Packaging |

---

## 8. Opportunities (select 4). Decline the rest

A proposal that adds a concept must say what it removes.

### O1. Make claimed wins honest in the examples (S)

**Jobs:** J6, J9, J10, J13, translation Landed.  
**Who:** anyone cloning Conduit as “full parity.”  
**Evidence:** Conduit has no `:infinite`, no SSR, no Xray host, no hover prefetch, no poll; tutorial scope is global.  
**Change:** Conduit `index.html` Xray rail (tutorial already has the pattern); tutorial 02/04 use viewer scope **or** a loud note that global is illegal for RealWorld payloads; README table of “this example does not show X — see Y.”  
**Removes:** translation-page overclaim; “Landed but the flagship doesn’t.”  
**Acceptance:** a cloner can open Conduit and see a write stale a read in Xray; tutorial does not teach a leak.

### O2. First-hour path without a hook wrapper (S)

**Jobs:** J1.  
**Who:** TanStack migrant, Part 2.  
**Evidence:** three runtimes, `:local/root`, Part 1 throwaway RemoteData, API URL drift (`.io` vs `.show`).  
**Change:** Part 2 as the first *server* page; Part 1 stays routing-only without a fake `:status` slice; pin the hosted API to the current RealWorld server URL **or** default the tutorial to the in-repo stub.  
**Removes:** a deleted feed implementation; a 404 on `api.realworld.io`.  
**Does not add** a `useQuery`-shaped helper.

### O3. Productize “which write” on the flagship (S) — clusters with O1

Same remedy as O1’s Xray rail. Do not build a Resources MCP to claim J14.

### O4. Named omit-path for persist and for one-shot GET (S, docs)

**Jobs:** offline TARGET; J1 boundary.  
**Change:** one page: when **not** to use Resources (already in index) plus “reload survival is 016 deferred; Query’s persist plugin is the honest miss; do not hand-roll a second cache.”  
**Removes:** silent DIVERGENT on persist; pressure to gold-plate a persister now.

### Declined

- GraphQL transport / entity cache / automatic graph invalidation (OUT until a consumer’s HTTP surface is measurably insufficient).
- Hook wrapper / `useQuery` facade.
- Process-global cache.
- `:select` key.
- `useQueries` clone.
- TanStack DB-class sync engine.
- In-place patch of infinite-feed items (016 R4: invalidate the whole feed).
- Cross-tab broadcast.
- Offline write queue.
- Weighted score dashboard.
- Dedicated Resources MCP (J14) before Conduit even mounts Xray.

---

## 9. Sequence and stopping point

1. O1 + O3 (honest flagship + Xray).  
2. O2 (tutorial first hour).  
3. O4 (docs omit-path).  
**Stop.** Do not start persist, GraphQL, or a hook helper. Re-run the closeness test when Query v6 is stable on React, when Conduit is rewritten, or when a 016 deferred slice lands.

Hot-zone if O2/O1 touch `docs/resources/tutorial/**` — not Spec 016 / API.md unless the tutorial’s global-scope sample is treated as normative (it must not be).
)
