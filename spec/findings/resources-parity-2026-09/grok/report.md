# Resources vs TanStack-class server-state libraries

Brief: `ai/findings/Resources/grok/prompt.md`  
Intermediates: `intermediate/landscape.md`, `closeness-test.md`, `scoreboard.md`, `advantage.md`, `evidence-ledger.md`, **`sibling-synthesis.md`**  
Evidence pin: **`a5f883b5691b83ac258121eb5c046df87be6d03d`** (Conduit walk, JVM pins, landscape)  
Tutorial repair: **`19dffcdb42`…`cc26e2d900`** (2026-09-15 00:37 +10) — Parts 1–5 docs. Conduit example / runtime / translation scorecard **unchanged**.  
HEAD at this revision: **`f2f9ffd654b70da97e907758cc73daa3bd205d57`** (2026-09-15 03:08 +10)  
When: independent pass 2026-09-14; **synthesis 2026-09-15 +10:00**  
Comparators: TanStack Query **v5** (npm `5.102.8`, docs `latest` 2026-09-14); RTK Query tags; SWR mutate; Apollo entity cache as **OUT**; TanStack DB **0.9.0** as a **separate package**.

**Independence then synthesis.** The scoreboard and Conduit walk were written **before** reading `astra/` or `fable/` reports. This revision **did** read them, source-checked the leads, and is labelled synthesis — not a second independent pass. Sibling request-ledger numbers and WIN labels are **not** this run’s measurements. Narrow consensus and declined leads: `intermediate/sibling-synthesis.md`.

---

## 1. Answers in brief

**How we test.** A standing, ~20-job closeness test (`intermediate/closeness-test.md`): jobs not hook nouns; four fields (capability / discoverability / evidence / comparison); Conduit as **anchor not ceiling**; defaults vs matched policy; `realworld_http` as ceremony control (matched slices, shared `realworld_shared/`); negative controls (GraphQL entity cache **OUT**, process-global cache refused); positive control (in-flight dedupe, fixture + `ensure-dedupes-in-flight`). Re-run on a Query **major**, a TanStack **DB** release (local-first row only), a Resources EP, a Conduit/tutorial rewrite. No weighted percentage.

**This run’s headline.** Resources is **COMPLETE** on the keyed HTTP cache jobs Conduit actually runs, **AHEAD** of Query on fail-closed scope and on the *tenth* write’s invalidation, **MATCH** with RTK on tag invalidation, **BEHIND** on the first-hour colocated read and on official persist, **OUT** on GraphQL entity cache, **TARGET** on offline. Several translation-page **Landed** rows are **UNDERMARKETED** (infinite, SSR, polling, hover-prefetch, Xray on the flagship).

**Overnight (historical defect → landed repair).** Independent stall log’s *blocking* tutorial items — unnamed `day8/re-frame2-http`, `api.realworld.io`, Part 4 populating a **global** article from an authenticated favourite, Part 5 testing a machine Part 3 didn’t build — **landed as docs** (`19dffcdb42` and siblings). Part 2’s global articles are now a timed lesson until Part 3. **Evidence still needed:** a fresh-consumer compile at HEAD (not re-run here); Conduit still has no Xray host; translation page still says `:keep-previous?` is “on the route/resource”; tutorial still never names `:revalidate-on` or the **absent** `:stale-after-ms` = never-stale default.

**Thesis.** Keep **register / cause / project + tags on writes**. Surviving structural advantages after steelmanning Query’s *practical* workflow (loaders, per-request clients, official Devtools, persist plugin, **`mutation.scope`**, UI `variables` recipe): a subscription **cannot** fetch; scope **cannot** be forgotten; a write’s consequences are **data** (and scoped). Contested `:on-conflict :invalidate` is real vs Query’s *cache* snapshot recipe (sibling-executed clobber window — not grok-timed). It is **not** unique vs SWR `rollbackOnError` or Query’s UI recipe. Do not sell Xray-on-Conduit until the example mounts it.

**Do first (after the tutorial repair).** (1) Translation-page honesty (`:keep-previous?` is a route/ensure key; name loaders + `variables` + `mutation.scope`). (2) One tutorial sentence on absent stale default + `:revalidate-on`. (3) Mount Xray on Conduit’s **dev** build. Do **not** add a hook wrapper, a second cache, GraphQL-in-016, or re-do the HTTP-dep / `.show` URL work that already landed.

---

## 2. This run’s scoreboard

Full table and walk: `intermediate/scoreboard.md`. Executed: tutorial read; Conduit Playwright at `http://127.0.0.1:8051/`; JVM pins (scope-leak 9/38, optimistic 14/124, mismatch 6/20, runtime 46/2635 — all green). Not executed: TanStack twin app, Xray UI, 5xx refresh, AbortController, red plant on a tracked fixture.

| Id | Job | Cap. | Disc. | Evidence | vs |
|---|---|---|---|---|---|
| J1 | First cached read | COMPLETE | TAUGHT | EXERCISED | **BEHIND** Query/SWR first hour |
| J2 | Dedupe / fresh-skip | COMPLETE | UNDERMARKETED | SOURCE-TRACED (no HTTP ledger) | MATCH Query |
| J3 | Stale-while-revalidate | COMPLETE | TAUGHT | SOURCE-TRACED | **DIVERGENT** defaults; MATCH at 60s |
| J4 | Invalidate after write | COMPLETE | TAUGHT | EXERCISED (comment + Favorited tab) | **AHEAD** Query lists; **MATCH** RTK |
| J5 | Optimistic + contested rollback | COMPLETE | TAUGHT | EXERCISED apply; JVM rollback | **AHEAD** Query **cache** recipe; **MATCH** SWR / Query **UI** `variables`; Query `mutation.scope` is another answer |
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
| J20 | Dependent read from A’s data | PARTIAL | INTERNAL | SOURCE-TRACED | **DIVERGENT** (`:reply-to` can chain; Conduit editor seeds a draft) + **TARGET** (planner cannot take B’s params from A’s **loaded data**; 016 `:after` is dispatch-order only). Not BEHIND the *job* if a continuation serves it |

Negative controls: GraphQL entity cache **OUT**; process-global cache refused; persist **TARGET**; one-shot GET → managed HTTP (ergonomic omit).

---

## 3. What the translation page got wrong or went stale

Page: `docs/resources/coming-from-tanstack-query.md`.

**“Every Landed claim is pinned by tests” — partly true.** Runtime tests/fixtures exist for most Landed *behaviours*. It is **false** if read as “Conduit exercises the user-facing job.” Infinite, SSR, polling, hover-prefetch are not in Conduit. Xray is not on Conduit’s `index.html`. Prefetch is already admitted narrower than `<Link prefetch>`.

**Caricatures (challenged against current Query docs):**

- Query **must** fetch from render — **false**. Loaders and `queryClient.query` start earlier. Honest remainder: the default hook does; a Resource sub **cannot**.
- QueryClient is **always** process-global — **false**. SSR guide forbids module-level clients. Honest remainder: isolation is discipline there, topology here.
- TanStack **always** restores `onError` context — **true of the cache recipe**, false of the UI `variables` recipe. Query also documents **`mutation.scope`** to serialise overlapping writes — a third answer, not named on the page.
- **`:keep-previous?` “on the route/resource”** (`coming-from-tanstack-query.md:194`) — **wrong.** It is a **route-entry / `ensure` payload** key. Conduit sets it on routes (`routing.cljs`); `reg-resource` does not take it.

**Stale teaching vs trunk — clocked.** At evidence pin `a5f883b569`, tutorial Part 2/4 used global article scopes and `api.realworld.io`. **Landed repair** `19dffcdb42`…`abc3ae0fc1`: HTTP artefact named; `.show` + local Bun backend; Part 3 moves reads to `:conduit/viewer`; Part 4 populate/invalidate uses viewer + session descriptors. Part 2 global remains **only until Part 3**, with that sentence on the page. Do not leave the old present-tense leak under this SHA.

**Still stale at HEAD.** `:keep-previous?` wording (above). Tutorial never mentions `:revalidate-on`. Samples always set `:stale-after-ms 60000`, so a migrant never meets the **absent = never-stale** default the scorecard names.

**Landed that should read UNDERMARKETED for a Conduit cloner:** infinite, SSR, polling, route-plan prefetch, Devtools/Xray.

---

## 4. Advantage thesis (tiers)

Full disproofs: `intermediate/advantage.md`.

**BUILT.** Views never fetch. Required fail-closed scope (Conduit three-branch viewer). Causal tagged invalidation (favourite membership walked). Optimistic **apply** (runtime inverse). Frame runtime-db cache (no per-read `:loading?` in app-db). 014 interceptor for auth. Classification on JWT/password.

**SPECIFIED.** Contested `:on-conflict :invalidate` (JVM, not UI-failed favourite). SSR hydrate (`resources_ssr`). Infinite (`infinite_feed`). Xray invalidation graph. Hover prefetch. Machines as resource owners (auth machine correctly is *not* one).

**IMAGINED.** Completed agent loop from enumerable keys. User-facing epoch replay of a stale page (restore ≠ undo server writes). Offline persister. **Write-reach lint** (Xray): set-difference of optimistic keys vs settlement `:affected-keys` / `:rf.mutation/optimistic-reconciled` — a tool lint, never a runtime warning; `:affected-keys` is **not** a history of every optimistic touch.

**Steelmanned away.** “Query cannot prefetch.” “Query cannot isolate SSR.” “Only we do optimistic.” “RTK has no tag invalidation.” “Comparators have no isolation boundary” (Query key factories, providers, per-request clients **are** boundaries; ours is louder). “Query cannot handle overlap” (`mutation.scope` serialises; UI `variables` never write the cache).

**Primitives to keep:** register / cause / project + tags on writes. A hook wrapper would hide the cause. A second cache would violate one obvious way.

---

## 5. Recommendations

Ranked by job leverage per concept. **Do not file beads.** Sequence below; **stop after #4.** Tutorial HTTP-dep / `.show` / Part 4 global populate are **done on trunk** — do not re-propose them.

### Keep measuring

Re-run `closeness-test.md` when Query ships a React-stable major, TanStack DB cuts a release that changes the local-first job, Spec 016 lands a deferred slice, or Conduit/tutorial is rewritten. Automatable residue: the six `resources-*.edn` fixtures + named tests in the closeness-test §7. No new benchmark service.

If HEAD moves: historical defect → landed repair → evidence still needed. This revision is that form for the tutorial.

**Methodology (from siblings, now in the test).** Wait on **data identity**, not “list visible” (keep-previous). Plant missing invalidation on a tag **member tags will not mask**. Record the **interval** between a rejected older write and recovery, not only the settled UI. Attribute competitor ledgers you did not run.

### Make claimed wins honest (first)

| # | Title | Jobs | Who | Evidence | Smallest change | Removes | Size | Acceptance |
|---|---|---|---|---|---|---|---|---|
| 1 | Translation page: keep-previous site + fair Query portraits | J6 J5 J8 | Migrants | Line 194 “on the route/resource”; caricatures of mount-fetch and only-one optimistic recipe | Quote route/ensure; name loaders, `variables`, `mutation.scope` | Unfair page a Query user bounces on | S | Those three sentences match current Query docs and Conduit’s route keys |
| 2 | Tutorial: name the **absent** stale default and `:revalidate-on` | J3 J10 | Part 2 readers | Samples always set 60s; 0 hits for `revalidate-on` under `tutorial/` | One paragraph + optional “migration profile” map (stale-at-once, focus/reconnect on) | Silent never-stale surprise | S | A migrant who omits `:stale-after-ms` is told what happens |
| 3 | Flagship shows Xray + “not in this example” table | J6 J9 J10 J13 | Conduit cloners | No Xray host; no infinite/SSR/poll/prefetch in Conduit | Dev-only Xray rail; README pointers | Landed-but-invisible | S | Favourite in dev, Resources panel names the write and the two reads. **shadow-cljs.edn is a hot zone** if a preload is required |

These are **docs / packaging**, not library defects. Item 2 in the independent draft (scope + API URL) **landed** — do not re-open.

### Make shipped wins discoverable

Clustered with #3. Hover prefetch and polling stay UNDERMARKETED — mention in the table, don’t add them to Conduit.

### Close real BEHINDs a TanStack user bounces on

| # | Title | Jobs | Change | Removes | Size |
|---|---|---|---|---|---|
| — | First-hour colocated read | J1 | **Do not** wrap `ensure` in a view. Remaining cost is three lanes + Clojars, not the repaired compile stalls. Soften “delete Part 1 RemoteData”: it is taught-then-replaced on purpose | — | none now |
| 4 | Persist omit-path | TARGET | Query plugin is the honest miss; don’t hand-roll a second cache | Silent DIVERGENT | S docs |
| — | Planner data waterfall (J20) | TARGET | Don’t clone `enabled`. `:reply-to` already serves “then do this.” A plan re-run is 016 deferred | Fake BEHIND | none now |

No hook-shaped `useQuery`. No `:select`. No new `handler-meta` arity (map form is the public API; tutorial 05 already uses it for events).

### Productize latent

Xray on Conduit **is** the productization of “which write.” After #3 is visible: an Xray **write-reach lint** (optimistic keys not in settlement reach) — tool only, trust the programmer. Machines-as-owners stay specified; Conduit auth-as-command is the right omit. A request-ledger assertion in test-support: **docs first** if canned stubs already record issues; no helper until that is grepped.

### Reaffirm refusals

GraphQL-in-016; normalized entity store; fetch-from-subscribe; process-global cache; `:select`; `:cache-key` v1.

### Do not build

- A Resources MCP to claim J14 before Conduit mounts Xray.
- TanStack DB / Electric sync engine.
- In-place infinite-item patch (016: invalidate the whole feed).
- Offline write queue / cross-tab.
- Weighted score dashboard.
- File-count “we have fewer files than `realworld_http`.”
- Re-adding `day8/re-frame2-http` / `.show` / Part 4 viewer populate (already on trunk).
- Automatic member tags on every list (masking is the TanStack failure in reverse).
- Serialising writes by scope (`mutation.scope` clone) — revision conflict already converges.

**Stopping point.** After #1–#3 (translation page, remaining tutorial nits, Conduit Xray). Re-measure. Lint (# after 3) only if a real “feed kept my guess” bug shows up. Do not start persist or GraphQL.

Hot-zone: tutorial/docs only unless someone treats tutorial samples as Spec 016 (they are not). `spec/016` stays sequential if a real contract change is proposed — none recommended here.

---

## 6. How to re-run without this prompt

1. Pin SHA, date, Query **and** TanStack DB versions.  
2. Follow `intermediate/closeness-test.md` jobs + honesty checks.  
3. Execute the minimum subset (tutorial stall **at HEAD**, Conduit walk with **request ledger or fixture**, favourite membership, logout, HTTP sibling slices, infinite **or** SSR example). Use a **wait on data identity**, not “list visible.” Plant missing invalidation on a tag **member tags will not already provide**. Record the **interval** after an older failure, not only the settled UI.  
4. Fill four fields. Challenge the translation page. Re-read tutorial if HEAD moved under `docs/resources/tutorial/`.  
5. Headline categorical; no percentage. Do not import sibling WIN labels.  
6. If HEAD moved: historical defect → landed repair → evidence still needed. This file’s tutorial section is the template.

Scripts to reuse: `intermediate/conduit-walk.cjs`, `conduit-walk-favorite.cjs` (Conduit on `dev:example -- examples/realworld-resources`). JVM: `implementation/resources` `clojure -M:test -n <ns>` with quoted args on PowerShell.

---

## 7. What you did not do

Independent pass: did not read siblings; did not edit tracked files; no PR; no beads; no greenfield tutorial scaffold; no TanStack twin; no Xray UI; no 5xx refresh; no AbortController; no Alice→Bob delayed reply; no red plant on a tracked fixture; no browser `infinite_feed` / `resources_ssr`.

**This synthesis pass:** read astra/fable reports; source-checked tutorial at HEAD; did **not** re-serve Conduit or re-run JVM pins; did **not** re-scaffold a consumer after `19dffcdb42`. Sibling-executed request ledgers and clobber windows are **attributed**, not claimed as grok measurements.

**Narrow consensus with siblings (not every ranking):** no persist/GraphQL/hook wrapper now; RTK tags MATCH; translation-page caricatures; Conduit is not the ceiling; Xray is missing on the flagship. **Disagreement remains** on WIN vs AHEAD vocabulary, J20 BEHIND vs DIVERGENT (this revision moved to DIVERGENT+TARGET), and how large the ergonomic win is (nobody timed a diagnosis). Fake-backend boundary stated.

**Stance.** Pre-alpha, elegance, not gold-plate, trust the programmer. After the tutorial repair, the first remaining move is honest comparison prose and making Xray visible on the flagship — not a larger cache.
)
