# Resources vs TanStack-class server-state libraries

Brief: `ai/findings/Resources/grok/prompt.md`  
Intermediates: `intermediate/landscape.md`, `closeness-test.md`, `scoreboard.md`, `advantage.md`, `evidence-ledger.md`  
Evidence pin: **`a5f883b5691b83ac258121eb5c046df87be6d03d`** (Conduit, Spec 016, JVM pins, browser walk)  
HEAD at close: **`f680007c6b7dcb63cf4dd4bdd7b39f83d8c4c9d4`** — window is Story spec + beads checkpoints only (`tools/story/spec/*`, `.beads/issues.jsonl`). **No Resources/Conduit repair in the window.** Present-tense product claims stay on `a5f883b569`.  
When: **2026-09-14**, +10:00  
Comparators: TanStack Query **v5** (npm `5.102.8`, docs `latest` 2026-09-14); RTK Query tags; SWR mutate; Apollo entity cache as **OUT**; TanStack DB **0.9.0** (2026-09-10) as a **separate package**, not Query core.

Sibling `astra/` / `fable/` reports were **not** read. This is an independent draft.

---

## 1. Answers in brief

**How we test.** A standing, ~20-job closeness test (`intermediate/closeness-test.md`): jobs not hook nouns; four fields (capability / discoverability / evidence / comparison); Conduit as **anchor not ceiling**; defaults vs matched policy; `realworld_http` as ceremony control (matched slices, shared `realworld_shared/`); negative controls (GraphQL entity cache **OUT**, process-global cache refused); positive control (in-flight dedupe, fixture + `ensure-dedupes-in-flight`). Re-run on a Query **major**, a TanStack **DB** release (local-first row only), a Resources EP, a Conduit/tutorial rewrite. No weighted percentage.

**This run’s headline.** Resources is **COMPLETE** on the keyed HTTP cache jobs Conduit actually runs, **AHEAD** of Query on fail-closed scope and on the *tenth* write’s invalidation, **MATCH** with RTK on tag invalidation, **BEHIND** on the first-hour colocated read and on official persist, **OUT** on GraphQL entity cache, **TARGET** on offline. Several translation-page **Landed** rows are **UNDERMARKETED** (infinite, SSR, polling, hover-prefetch, Xray on the flagship).

**Thesis.** Keep **register / cause / project + tags on writes**. The surviving structural advantages after steelmanning Query’s *practical* workflow (loaders, per-request clients, official Devtools, persist plugin) are: a subscription **cannot** fetch; scope **cannot** be forgotten; a write’s consequences are **data** (and scoped). Contested optimistic rollback (`:on-conflict :invalidate`) is real vs Query’s *cache* recipe, not vs SWR or Query’s *UI* recipe. Do not sell Xray-on-Conduit or SSR isolation until the examples show them.

**Do first.** Make claimed wins honest in Conduit + tutorial (Xray rail, viewer scope in Part 2/4, current API URL, “this example does not show X — see Y”). Do **not** add a hook wrapper, a second cache, or GraphQL-in-016.

---

## 2. This run’s scoreboard

Full table and walk: `intermediate/scoreboard.md`. Executed: tutorial read; Conduit Playwright at `http://127.0.0.1:8051/`; JVM pins (scope-leak 9/38, optimistic 14/124, mismatch 6/20, runtime 46/2635 — all green). Not executed: TanStack twin app, Xray UI, 5xx refresh, AbortController, red plant on a tracked fixture.

| Id | Job | Cap. | Disc. | Evidence | vs |
|---|---|---|---|---|---|
| J1 | First cached read | COMPLETE | TAUGHT | EXERCISED | **BEHIND** Query/SWR first hour |
| J2 | Dedupe / fresh-skip | COMPLETE | UNDERMARKETED | SOURCE-TRACED (no HTTP ledger) | MATCH Query |
| J3 | Stale-while-revalidate | COMPLETE | TAUGHT | SOURCE-TRACED | **DIVERGENT** defaults; MATCH at 60s |
| J4 | Invalidate after write | COMPLETE | TAUGHT | EXERCISED (comment + Favorited tab) | **AHEAD** Query lists; **MATCH** RTK |
| J5 | Optimistic + contested rollback | COMPLETE | TAUGHT | EXERCISED apply; JVM rollback | **AHEAD** Query cache recipe |
| J6 | Paginate / infinite | COMPLETE (split) | paginate TAUGHT; infinite UNDERMARKETED | EXERCISED keep-previous | MATCH, two examples |
| J7 | Leak / logout / scope | COMPLETE | TAUGHT | EXERCISED + JVM | **AHEAD** Query convention |
| J8 | Route-started fetch | COMPLETE | TAUGHT | EXERCISED | **MATCH** Query loaders |
| J9 | SSR hydrate | COMPLETE in `resources_ssr` | UNDERMARKETED | SOURCE-TRACED | MATCH if both isolate |
| J10 | Focus / reconnect / poll | COMPLETE | UNDERMARKETED | SOURCE-TRACED | MATCH when opted in |
| J11 | Refresh error keeps data | COMPLETE | TAUGHT | SOURCE-TRACED | MATCH |
| J12 | Test without network | COMPLETE | UNDERMARKETED | SOURCE-TRACED | MATCH Query+MSW |
| J13 | Which write staled which read | COMPLETE in Xray | **INTERNAL** (no Xray on Conduit page) | SOURCE-TRACED | AHEAD *if* attached |
| J14 | Agent loop | PARTIAL | INTERNAL | SOURCE-TRACED | DIVERGENT; do not infer MCP |
| J15 | Cancel / ignore stale | PARTIAL | UNDERMARKETED | SOURCE-TRACED | MATCH ignore; UNVERIFIED abort |
| J16 | GC / two instances | COMPLETE | UNDERMARKETED | SOURCE-TRACED | MATCH |
| J17 | Auth headers once | COMPLETE | TAUGHT | SOURCE-TRACED (014) | MATCH interceptor |
| J18 | `:reply-to` follow-up | COMPLETE | TAUGHT | SOURCE-TRACED | **AHEAD** view `onSuccess` |
| J19 | Leave mid-flight | COMPLETE spec | UNDERMARKETED | SOURCE-TRACED | MATCH |
| J20 | Dependent read from A’s data | PARTIAL | INTERNAL | SOURCE-TRACED (`:after` ≠ data waterfall) | **BEHIND** Query `enabled` |

Negative controls: GraphQL entity cache **OUT**; process-global cache refused; persist **TARGET**; one-shot GET → managed HTTP (ergonomic omit).

---

## 3. What the translation page got wrong or went stale

Page: `docs/resources/coming-from-tanstack-query.md`.

**“Every Landed claim is pinned by tests” — partly true.** Runtime tests/fixtures exist for most Landed *behaviours*. It is **false** if read as “Conduit exercises the user-facing job.” Infinite, SSR, polling, hover-prefetch are not in Conduit. Xray is not on Conduit’s `index.html`. Prefetch is already admitted narrower than `<Link prefetch>`.

**Caricatures (challenged against current Query docs):**

- Query **must** fetch from render — **false**. Loaders and `queryClient.query` start earlier. Honest remainder: the default hook does; a Resource sub **cannot**.
- QueryClient is **always** process-global — **false**. SSR guide forbids module-level clients. Honest remainder: isolation is discipline there, topology here.
- TanStack **always** restores `onError` context — **true of the cache recipe**, false of the UI `variables` recipe.

**Stale teaching vs trunk Conduit.** Tutorial Part 2/4 uses `:scope :rf.scope/global` for articles whose payloads carry per-viewer `favorited`. That is the leak Conduit exists to make unrepresentable. Tutorial API `api.realworld.io` vs README/current spec `api.realworld.show`.

**Landed that should read UNDERMARKETED for a Conduit cloner:** infinite, SSR, polling, route-plan prefetch, Devtools/Xray.

---

## 4. Advantage thesis (tiers)

Full disproofs: `intermediate/advantage.md`.

**BUILT.** Views never fetch. Required fail-closed scope (Conduit three-branch viewer). Causal tagged invalidation (favourite membership walked). Optimistic **apply** (runtime inverse). Frame runtime-db cache (no per-read `:loading?` in app-db). 014 interceptor for auth. Classification on JWT/password.

**SPECIFIED.** Contested `:on-conflict :invalidate` (JVM, not UI-failed favourite). SSR hydrate (`resources_ssr`). Infinite (`infinite_feed`). Xray invalidation graph. Hover prefetch. Machines as resource owners (auth machine correctly is *not* one).

**IMAGINED.** Completed agent loop from enumerable keys. User-facing epoch replay of a stale page (restore ≠ undo server writes). Offline persister.

**Steelmanned away.** “Query cannot prefetch.” “Query cannot isolate SSR.” “Only we do optimistic.” “RTK has no tag invalidation.”

**Primitives to keep:** register / cause / project + tags on writes. A hook wrapper would hide the cause. A second cache would violate one obvious way.

---

## 5. Recommendations

Ranked by job leverage per concept. **Do not file beads.** Sequence below; **stop after step 3.**

### Keep measuring

Re-run `closeness-test.md` when Query ships a React-stable major, TanStack DB cuts a release that changes the local-first job, Spec 016 lands a deferred slice, or Conduit/tutorial is rewritten. Automatable residue: the six `resources-*.edn` fixtures + named tests in the closeness-test §7. No new benchmark service.

If HEAD moves: historical defect → landed repair → evidence still needed.

### Make claimed wins honest (first)

| # | Title | Jobs | Who | Evidence | Smallest change | Removes | Size | Acceptance |
|---|---|---|---|---|---|---|---|---|
| 1 | Flagship shows Xray + an honest “not in this example” table | J6 J9 J10 J13 | Conduit cloners | No Xray host; no infinite/SSR/poll/prefetch in Conduit | Xray rail like tutorial HTML; README pointers to `infinite_feed` / `resources_ssr` | Landed-but-invisible | S | Open Conduit, favourite, see the write on the invalidation graph |
| 2 | Tutorial scope + API URL match the product | J1 J7 | Part 2/4 readers | Global articles vs viewer-scoped Conduit; `.io` vs `.show` | Viewer scope **or** a loud “illegal for RealWorld payloads”; pin `api.realworld.show` or default to the stub | Taught leak; 404 | S | Tutorial favourite does not warn-mismatch against viewer reads |

These are **inaccurate comparison prose / missing packaging**, not library defects.

### Make shipped wins discoverable

Clustered with #1: J13 is INTERNAL until Xray is on the example. Hover prefetch and polling stay UNDERMARKETED — mention in the table, don’t build chrome.

### Close real BEHINDs a TanStack user bounces on

| # | Title | Jobs | Change | Removes | Size |
|---|---|---|---|---|---|
| 3 | First-hour without a hook wrapper | J1 | Part 1 = pages/routing only (no throwaway `:status` slice); Part 2 is the first server feed; Clojars note stays honest | Deleted Part 1 RemoteData; extra concepts | S |
| — | Persist | TARGET | **Docs omit-path** (Query plugin is the honest miss; don’t hand-roll a second cache). Library persister only when 016’s fires-when hits | Silent DIVERGENT | S docs |
| — | `enabled`-style data waterfall (J20) | PARTIAL | Don’t clone `enabled`. If a consumer needs B’s params from A’s **data**, that’s a plan re-run — currently deferred in 016. Until then, `:reply-to` / a second cause | Fake DIVERGENT | none now |

No hook-shaped `useQuery`. No `:select`.

### Productize latent

Xray on Conduit **is** the productization of “which write” (O3 = O1). Machines-as-owners stay specified; Conduit auth-as-command is the right omit.

### Reaffirm refusals

GraphQL-in-016; normalized entity store; fetch-from-subscribe; process-global cache; `:select`; `:cache-key` v1.

### Do not build

- A Resources MCP to claim J14 before Conduit mounts Xray.
- TanStack DB / Electric sync engine.
- In-place infinite-item patch (016: invalidate the whole feed).
- Offline write queue / cross-tab.
- Weighted score dashboard.
- File-count “we have fewer files than `realworld_http`.”

**Stopping point.** After #1–#3 (honest flagship, tutorial, first-hour). Re-measure. Do not start persist or GraphQL.

Hot-zone: tutorial/docs only unless someone treats tutorial samples as Spec 016 (they are not). `spec/016` stays sequential if a real contract change is proposed — none recommended here.

---

## 6. How to re-run without this prompt

1. Pin SHA, date, Query **and** TanStack DB versions.  
2. Follow `intermediate/closeness-test.md` jobs + honesty checks.  
3. Execute the minimum subset (tutorial stall, Conduit walk with **request ledger or fixture**, favourite membership, logout, HTTP sibling slices, infinite **or** SSR example). Use a **wait on data identity**, not “list visible,” because keep-previous keeps the list visible.  
4. Fill four fields. Challenge the translation page.  
5. Headline categorical; no percentage.  
6. If HEAD moved: do not leave old present-tense defects under a new SHA.

Scripts to reuse: `intermediate/conduit-walk.cjs`, `conduit-walk-favorite.cjs` (Conduit on `dev:example -- examples/realworld-resources`). JVM: `implementation/resources` `clojure -M:test -n <ns>` with quoted args on PowerShell.

---

## 7. What you did not do

- Read `astra/` / `fable/` / Story reports.  
- Edit tracked files, open a PR, write beads.  
- Scaffold a greenfield tutorial project (Clojars/`local/root` cost recorded only).  
- Run a TanStack Query Conduit twin.  
- Open Xray, force a refresh 5xx, abort a real fetch, switch Alice→Bob with a delayed reply.  
- Plant a red by editing a tracked fixture (positive-control fault).  
- Browser-run `infinite_feed` or `resources_ssr`.  
- Verify UI in a second viewport (not a visual-design change). Fake-backend boundary stated.

**Stance.** Pre-alpha, elegance, not gold-plate, trust the programmer. The first move is teaching and honesty, not a larger cache.
)
