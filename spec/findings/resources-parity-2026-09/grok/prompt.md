# Research brief: measuring Resources against TanStack Query-class server-state libraries, and imagining the win

You are being asked to **research and report**, not to implement. Do not edit tracked files, specifications, tests, workflows, or tracker state. Do not open a PR. Intermediate notes and the report itself belong only under `ai/findings/Resources/grok/` (this tree is local-only and gitignored). Leave this `prompt.md` untouched.

Sibling folders `ai/findings/Resources/fable/` and `ai/findings/Resources/astra/` may exist and may grow reports. **Do not read those reports until your own `report.md` is drafted.** Independence is load-bearing. If you read them afterwards, say so, and treat the result as a synthesis note — not as this pass. **Do not read `ai/findings/Story/*/report.md` either** — that is a different subject. You MAY reuse its *method* (jobs not nouns; four layers; `SPEC-ONLY` / `UNDERMARKETED` / `DIVERGENT`; clock two SHAs if the tree moves). Do not import its scores, journeys, or recommendations.

Resolve every path against the checkout you actually inspect. Pin material observations to `git rev-parse HEAD`. If that SHA moves during the work, re-check consequential claims before concluding.

The flagship application is the Conduit RealWorld example at `examples/real-apps/realworld_resources/`. A sibling at `examples/real-apps/realworld_http/` is the **same app one layer down** (managed HTTP, hand-rolled cache slices). That pair is a **ceremony control**, not a TanStack proxy: compare **matched slices**, including what each delegates to `realworld_shared/`, rather than total file counts. If Resources cannot beat the HTTP sibling on a job, that is evidence about *this pair*, not an automatic TanStack verdict. A capability missing from Conduit may still be implemented in a capability example — that is `UNDERMARKETED` or `UNVERIFIED`, not `SPEC-ONLY` by itself.

---

## 0. Stance — paste, do not paraphrase

> Posture is that we are pre-alpha and focused on elegance, power and a masterpiece.
> But we are not over-engineering or gold plating. Also, we trust the programmer.
> We're trying to facilitate high productivity for them (and the AI they use) via a
> library with excellent ergonomics and low friction. We don't need to litigate every
> last fine detail and drown in the minutiae.

The first sentence is the bar. Everything after it is when to **stop**. A report that recommends a GraphQL client, a normalized entity store, and an offline write queue "because Apollo has them" has failed the second half. A report that litigates hook nouns Resources has already refused has failed it too. (Mike restated the stance on 2026-09-13 adding "excellent tools" and a "minimal, ergonomic API" — same bar, same stop.)

Apply the stance to both the analysis and the recommendations. The goal is an excellent way to borrow server state into an SPA: a small computational model, useful defaults, short feedback loops. Pre-alpha permits simplifying a surface. **It does not justify an unreliable cache or a silent cross-user leak.** Look for improvements that *remove* `:loading?` fields, `invalidateQueries` call sites, and remembered `onSuccess` lists — not only ones that add capability. Do not recommend a second cache beside runtime-db, a hook that fetches from render, or a large infrastructure project when a small primitive, a better integration, or a clearer path would do.

---

## 1. The two questions — they are co-equal

re-frame2's Resources (`day8/re-frame2-resources`, `implementation/resources/`, Spec 016) should have **feature parity** with TanStack Query and similar JavaScript (and other) alternatives — **except** that it should leverage re-frame2's strengths, idioms, and ethos rather than impersonate those libraries.

Answer both, in this order:

1. **How do we *test* how close we are to that objective?**
   Not "give us a score this week." A **standing, re-runnable method** for telling whether Resources can do the jobs a TanStack-class server-state library is for, whether those jobs are honestly shipped (not merely specced or claimed on a translation page), and where we have chosen a different shape on purpose. The method has to survive TanStack Query shipping a new major and Resources landing a new EP. A one-shot matrix dated the day you write it is a finding, not a test.

2. **How do we imagine being *better*** — more powerful, more ergonomic, more AI-amenable — than those alternatives, because of re-frame2 rather than in spite of it?
   Not "list every runtime-db slot." A **product thesis**: which jobs TanStack-class tools do poorly *because of their substrate* (hooks that fetch, process-global clients, imperative invalidation, user id as another key segment), which of those jobs re-frame2 makes cheap, which of those cheap things Resources has actually productized (a Conduit author can do them without knowing the spec), and which remain latent. Then the smallest set of moves that would make a demanding TanStack user *prefer* Resources, not merely tolerate it. **Try to disprove each advantage** before you keep it.

Recommendations follow from those two answers. They are not a third question.

---

## 2. What this is not

- **Not a hook-noun checklist.** Scoring "do we have `useQuery` / `keepPreviousData` / `queryClient.setQueryData` / `HydrationBoundary` / `notifyOnChangeProps`." TanStack's surface is hook-shaped. Resources' surface is register / cause / project. Scoring nouns will undercount Resources' wins (scope as identity, causal invalidation, views that never fetch) and overcount TanStack's. Parity is a quality bar on *jobs*.
- **Not a recitation of `docs/resources/coming-from-tanstack-query.md`.** That page already claims a mapping, five divergences, and a "full parity scorecard" with Landed / Different by design / Out of scope / Deferred. Treat it as **starting evidence, not the answer.** Re-verify every "Landed" row against **today's** spec, code, tests, *and* Conduit. A Landed row that Conduit does not exercise is `UNDERMARKETED` or `SPEC-ONLY`.
- **Not a correctness review of `implementation/resources/`.** The test tree is large. Do not re-audit every `resources_*_cljs_test.cljc`. Cite tests when a job's status depends on them. Use Conduit and a small number of targeted probes for the jobs that matter.
- **Not a licence to reopen locked refusals** unless the refusal now *costs a job*. Spec 016 already names: GraphQL transport, normalized entity/fragment caches, automatic graph-derived invalidation, subscription-driven fetching, offline write queues, cross-tab broadcast, a `:select` key, a `:cache-key` escape hatch in v1, process-global cache, views that fetch. A recommendation to reverse one of these must say which *job* is blocked, not which competitor ships the noun.
- **Not permission to quietly narrow parity.** Do not score only the jobs Resources already looks strong on. Do not label an inconvenient omission `DIVERGENT` without naming the other way the *job* is served. Refusing GraphQL is not a pass on "the app talks to a query-language API" unless Conduit (HTTP) is the intended product; for Conduit it is `OUT`. For a GraphQL shop it is `OUT` of *this* artefact, with Pattern-RemoteData + managed HTTP as the documented omit-path — say so.
- **Not an implementation plan or bead-filing session.** Propose; do not build. Read existing beads so you do not recommend already-resolved work; do not create a speculative backlog.

---

## 3. The objective, stated so it can be tested

**Parity** means: an experienced TanStack Query (or SWR / RTK Query / Apollo-for-REST) user can sit down at Resources — ideally by cloning Conduit's pattern — and accomplish the jobs they came for, without a ceremonial map of runtime-db, at comparable or lower ceremony. The API may be EDN events rather than hooks. The cache may live in a frame rather than a `QueryClient`. Those are not gaps. *Being unable to do the job*, or only being able to do it by dropping into undocumented internals or by hand-rolling what `realworld_http` already does, is a gap.

Parity does **not** require reproducing another library's hook count, plugin marketplace, or Devtools chrome. It *does* require taking the alternatives' strongest *practical* workflow seriously — including official Devtools, persistence plugins, and the RealWorld/TanStack examples people actually copy — and naming the dependency, setup, and cost of those extras.

**Better** means: some of those jobs are cheaper, more honest, or newly possible because a read is data, a write declares its consequences as data, a view never fetches, a scope leak is unrepresentable, and Xray can show *which mutation staled which resource*. "Better" is not "more status flags." It is fewer concepts doing more work, and a cache decision an agent can enumerate.

**Ethos constraints** (from `spec/000-Vision.md`, `spec/Principles.md`, Spec 016, and the stance):

- Data before magic. Resource and mutation bodies are data; request fns return request maps, not promises.
- Views are passive. A subscription that fetches is a defect, not a convenience.
- One obvious way. Do not recommend a second cache, a second invalidation style, or a hook-shaped wrapper over `ensure`.
- Named, addressable things. Cache keys, owners, causes, mutation instances, tags.
- Trust the programmer. Do not recommend mandatory ceremony for a one-shot uncached GET (that job belongs to managed HTTP).
- AI-first is a property of the *shape* (enumerable keys, causal invalidation on the event record, Xray), not a bolt-on.
- Resources is an **optional** artefact. Spec 016 wins the contract. **For "how close are we *today*," trunk code wins when spec and code disagree.** Conduit wins when spec/code claim a job the example cannot do.
- Pre-alpha: current implementation is grounding evidence, not a compatibility constraint. Superseding a surface is in scope if it makes the model clearer. Layering a second query API over the first is out.

Use the **current public vocabulary**. Investigate `reg-resource`, `reg-mutation`, `[:rf.resource/ensure …]`, `[:rf.mutation/execute …]`, `[:rf/resource …]`, `[:rf/mutation …]`, route `:resources`, `:invalidates`, `:optimistic` / `:optimistic-tags`, `:infinite`, `[:rf.resource/load-more …]`, `[:rf.resource/clear-scope …]` at source before copying examples from older EPs. Distinguish **Pattern-RemoteData + managed HTTP** (the omit-Resources path, exhibited by `realworld_http`) from Resources itself.

---

## 4. Starting evidence — claims, not findings

Verify at source where a claim determines a recommendation. A contradiction is a valuable outcome.

### 4.1 Orientation reads (before competitors)

| Read | Why |
|---|---|
| `spec/016-Resources.md` — Abstract, Role, identity, scope, mutations, optimistic, infinite, deferred, "what Spec 016 does NOT cover" | Normative contract. GraphQL/entity-cache/offline are deferred or out. |
| `docs/resources/index.md` | What a new user is taught first. Three lanes: register / cause / project. |
| `docs/resources/concepts.md` | Model page. |
| `docs/resources/coming-from-tanstack-query.md` | Translation + claimed scorecard. **Re-verify.** |
| `docs/resources/tutorial/index.md` and `01`–`05` | Tutorial stall log starts here. |
| `examples/real-apps/realworld_resources/README.md` plus `resources.cljs`, `mutations.cljs`, `routing.cljs`, `scope.cljs`, `core.cljs`, `views.cljs`, `article_editor.cljs` | Flagship. Fake backend: `examples/real-apps/realworld_shared/demo_backend.cljs`. |
| `examples/real-apps/realworld_http/` | **Within-repo control**, not a TanStack proxy: the same Conduit on managed HTTP. Count what Resources removed (flags, invalidation sites) and what it added (registrations, scopes, route metadata). |
| `implementation/core/src/re_frame/core_resources.cljc` | Public facade vs the optional artefact that supplies it. |
| `spec/Pattern-RemoteData.md` + `spec/014-HTTPRequests.md` | The omit-Resources path. **Retry, auth headers, request decoration live on this seam** (`016` §Request decoration) — a missing TanStack `retry` option is not a Resources GAP if 014 serves it; count the ceremony of reaching it. |
| `spec/015-Data-Classification.md` | Candidate differentiator *and* ceremony. No query library in the set has an equivalent; prove whether a Conduit author meets it. |
| `tools/xray/spec/024-Resources-Panel.md` | Comparator is TanStack Query Devtools. Follow links; do not assume a dedicated Resources MCP. |
| `spec/conformance/fixtures/resources-*.edn` | Six implementation-independent pins. Stronger than a unit test for a Landed claim; still not a user-facing win by themselves. |
| `docs/EP/EP-0003-resource-queries.md` (rationale + prior-art, not the whole bead plan) | Originating EP. |
| `docs/EP/EP-0019-optimistic-mutation-rollback.md` abstract | Contested rollback (`:on-conflict :invalidate`) is the claimed semantic departure. |
| `docs/EP/EP-0021-infinite-resources.md` abstract | Infinite/load-more. |
| `docs/api/re-frame.resources.md` | Public API. Spot-check vs code. |
| `docs/core/fresco/08-async-resources.md` | How the view layer is taught to consume resources. |

Do **not** read all of Spec 016 up front. Pull a section when a job requires it.

For question 2, also pull at need: `spec/002-Frames.md`, `spec/012-Routing.md` (the routing spec itself names TanStack — read why), `spec/011-SSR.md`, `spec/005-StateMachines.md` (a machine can **own** a resource), `spec/009-Instrumentation.md`, `spec/010-Schemas.md` (`:params-schema` is identity, not a `:select` hook). Skills leaves: `skills/re-frame2/patterns/resources.md`, `resources-mutations.md`, `remote-data.md`. Extra examples when a job Conduit does not exercise: `examples/capabilities/resources/infinite_feed/`, `examples/capabilities/resources/linearlite/`, `examples/capabilities/ssr/resources_ssr/`. Story `force-fx-stub` is in scope only as "can a variant show every server state of a view without a network?"

A capability supplied by managed HTTP, the router, SSR, Xray, or the app is **not** a Resources win unless Resources is the public route to it. Separate those.

### 4.2 Prior art already in-tree (re-verify; do not recopy)

- `docs/resources/coming-from-tanstack-query.md` §The full parity scorecard. Status tags are claims. The closing sentence that **every Landed row is pinned by tests** is itself a row: true, partly true, or false, with the count. For each Landed row name the pinning test (or conformance fixture) and say whether it exercises the *described* behaviour. **Challenge caricatures on that page:** does TanStack actually *require* fetch-from-render, or can a router loader / `prefetchQuery` start earlier? Can a `QueryClient` be per-provider / per-request? Is contested rollback the library's enforced behaviour or a naive `onError` recipe? Verify; do not reproduce our own marketing.
- EP-0003's prior-art benchmark (TanStack, RTK Query, SWR, `shipclojure/re-frame-query`).
- Conduit README's three deliberate differences: views never fetch; scope is identity; invalidation is causal.

### 4.3 Substrate advantages the docs already claim (verify productized)

Separate **substrate potential**, **specced contract**, **shipped behaviour**, and **discoverable in Conduit / tutorial**:

- A read never fetches. Register / cause / project.
- Identity is `[scope resource-id canonical-params]`. Forgetting scope is a registration error, not a silent leak.
- Invalidation is declared on `reg-mutation` (`:invalidates` by tag), not remembered in `onSuccess`.
- Optimistic: author the forward patch; runtime records the inverse. Contested rollback defaults to invalidate, not restore.
- Cache lives in the frame's runtime-db, not app-db, not a process-global client. One frame per SSR request.
- Route `:resources` starts the fetch before the view mounts; `:blocking?` is the SSR wait.
- Mutation instances are per-call, so two favourites can fly at once.
- Writes do not retry by default.
- `:reply-to` is a causal follow-up (navigate after save), not `onSuccess` in the view.
- Xray / trace can show which write staled which read.
- `realworld_http` vs `realworld_resources` is the before/after of the artefact (matched slices; shared code named).
- Retry has two homes: **transport** retry (014) vs **semantic** retry (a machine). Do not score a missing Resources `:retry` as a GAP if those seams serve the job.
- Classification, hydration payload, epoch export, and restore/replay are **different consumers**. Restoring client history cannot undo a server write. A substrate replay test is not a complete user-facing reproduction workflow.

For question 2, label each kept claim **BUILT** / **SPECIFIED** / **IMAGINED**. Mixing those tiers in one paragraph is a failure mode.

Specific probes the docs imply:

- Logout / account-switch: `clear-scope` vs `queryClient.clear()`.
- Favourite in Conduit: optimistic heart + count across detail, lists, and feed, then invalidate.
- Scope mismatch warning when a mutation invalidates the wrong scope.
- Infinite / load-more: does Conduit use it, or only offset pagination?
- SSR: does Conduit actually hydrate resources, or only claim the contract?

### 4.4 Spec vs code vs docs vs Conduit — four artefacts

A closeness test that reads only Spec 016 will congratulate us for contracts. A test that reads only the translation page will miss Conduit gaps. A test that greps only `implementation/resources/src` will count FSMs. You need spec, code, user docs, **and the example**, and you need to say which one a verdict came from.

An unavailable browser, backend, or credential is a **limitation**, not evidence of parity and not evidence of a defect.

---

## 5. Method

Work in phases. Commit each phase under `intermediate/` **before** starting the next. Date-stamp competitor claims. Distinguish **source observation**, **runtime/docs measurement**, and **inference**.

### Phase A — Competitive landscape (current, not EP-0003's pin)

Survey the field **now**. Identify experimental/announced capabilities separately from released ones. Treat marketing as a claim.

**TanStack Query is the main comparison** (current stable major on the run date — do not inherit v4 from our files). Cover the *practical* workflow: queries and mutations, keys, staleTime/gcTime **defaults**, invalidation, optimistic recipes, Devtools, persistence plugin, SSR/hydration, prefetch / router loaders, cancellation, infinite queries. Check whether **TanStack DB** has shipped and what it changes for the "server state as a local database" job — compare the *job* (`linearlite` is the in-repo reasoner), not the architecture. Resources is HTTP-only by ruling.

Then a **small complementary set — four to six besides TanStack, not twenty:**

- **SWR** — compact API; `mutate` + `optimisticData`.
- **RTK Query** — closest cousin for "invalidation as data." Also OpenAPI codegen as a *job*, not a noun.
- **One GraphQL / normalized-cache client, briefly** (Apollo, urql, or Relay) — bound entity consistency as `OUT` of Spec 016. Score the *job* "one entity in three places stays consistent after a write": Conduit serves it by populate-from-reply plus tag refetch — say the extra-request / inconsistency-window cost, and whether a Conduit user notices.
- **CLJS lineage as one group:** `re-frame-query`, Fulcro `load`, Keechma dataloader if still informative. Several Resources ideas are inheritances; say which.
- **One non-JS** that changes a job: Kotlin **Store5**, Flutter **Riverpod** `AsyncValue`, Angular `resource()` / `httpResource`, or Phoenix `assign_async`. Skip if it does not change Phase B.

Adjacent, one sentence each unless they steal a job: tRPC, Next.js `fetch` / RSC, MSW vs canned HTTP stubs, OpenAPI codegen (Orval / RTK).

For each capability distinguish **built-in / official plugin / community / custom app work.** Include TanStack Devtools and the persistence plugin as ordinary extras, with setup named. Do not compare Resources+Xray+routing+SSR against a bare `useQuery` with no Devtools.

Cite URLs and access dates. Stop when another tool would not change Phase B.

Deliver `intermediate/landscape.md`.

### Phase B — Design the closeness test (question 1)

A **re-runnable test**, not a score. Jobs, not features. Start from Conduit's actual user journeys, then the translation-page dimensions, then add or cut from Phase A. Stay under ~20 rows. Conduit is the **anchor**, not the ceiling: it does not exercise infinite-feed, SSR, or persistence — use the capability examples for those, or mark `UNVERIFIED`.

Count ceremony on the **first** read *and* the **tenth** in a real app. TanStack's colocated `useQuery` is cheap on read one and expensive on invalidation bookkeeping later; Resources' three lanes are the inverse. A `AHEAD` on the tenth that is a `BEHIND` on the first is a first-hour finding, not a surplus. **Twice as many declarations is not twice the effort** — also count *where* a change is made (one registration vs N call sites). Do not pick a "data-only" control designed to make re-frame2 win.

**Defaults vs matched policy.** Run freshness / GC / focus-reconnect once at **documented defaults** (TanStack `staleTime: 0` vs our never-time-stale; both GC ~5 minutes) and once with **explicitly matched policies**. Fewer requests earned by keeping older data is not automatically an efficiency win — score against the application's freshness requirement and what the user sees. Record Conduit's actual settings.

Include the boundary where an uncached one-shot GET is better served by managed HTTP: **avoiding Resources can be the ergonomic answer.**

Changing sets of parallel reads (TanStack `useQueries` over a dynamic list) is a different job from a static route `:resources` vector. Fold it into J8/J20 or say `UNVERIFIED`; do not pretend Conduit's fixed plan covers it.

Perspectives: a framework-aware newcomer, a maintainer adding a write, an assistant collaborating with that maintainer. Separate one-time setup from marginal feature cost.

A plausible starter set — refine it:

| Id | Job | Why it is in the set |
|---|---|---|
| J1 | First cached read: register, cause, project, see data | `useQuery` on mount vs three lanes |
| J2 | Dedupe identical in-flight reads; cache hit when fresh | Core cache job |
| J3 | Stale-while-revalidate: show data, refetch in background | SWR semantics; **default staleTime 0 vs our never-time-stale** is a real divergence to score honestly |
| J4 | After a write, the right reads refresh without a remembered `onSuccess` list | Conduit favourite / follow / comment |
| J5 | Optimistic update + automatic rollback; contested concurrent write | Favourite heart; EP-0019 `:on-conflict` |
| J6 | Paginated list and/or infinite load-more | Conduit offset pages vs `useInfiniteQuery` |
| J7 | Cache leak across users/tenants/logout | Scope identity; TanStack's structural footgun |
| J8 | Route starts the fetch before the view; no mount-then-fetch waterfall | Conduit `routing.cljs` `:resources` |
| J9 | SSR: wait on blocking reads, hydrate without a duplicate flash, no cross-request leak | Frame-per-request vs `QueryClient` |
| J10 | Focus / reconnect / poll refetch of *owned stale* entries | `:revalidate-on`, `:poll-interval-ms` |
| J11 | First-load error vs refresh error (keep data, warn) | Conduit README's `:refresh-error` |
| J12 | Test a page without a network: deterministic resource/mutation fixtures | `test_support` vs MSW / QueryClient in tests |
| J13 | See *which write* staled *which read* | Xray / trace vs TanStack Devtools |
| J14 | Agent can enumerate keys, run a mutation, observe invalidation | Shape, not a bolt-on MCP |
| J15 | Cancel in-flight; ignore stale/superseded replies | Work ledger |
| J16 | GC after owners leave; two mutation instances at once | Owners vs observers; instance ids |
| J17 | Auth headers once, not per resource | HTTP interceptors vs `queryFn` closures |
| J18 | Follow-up after a write (navigate to saved article) without smuggling `onSuccess` into the view | `:reply-to` |
| J19 | Leave a route mid-flight; delayed reply must not commit; return reuses or refetches honestly | Owners vs observers; cancel vs ignore |
| J20 | Dependent / serial read (B needs A's result) without a hidden fetch in the view | `:reply-to` vs route plan vs TanStack `enabled` |

**Three independent fields per job** — do not collapse into a single `WIN`:

| Field | Vocabulary |
|---|---|
| **Capability** | `ABSENT` / `PARTIAL` / `COMPLETE` / `SPEC-ONLY` / `TARGET` / `OUT` |
| **Discoverability** | `TAUGHT` (tutorial + Conduit README) / `UNDERMARKETED` / `INTERNAL` |
| **Evidence** | `EXERCISED` / `SOURCE-TRACED` / `DOCUMENTED-ONLY` / `UNVERIFIED` |
| **Comparison** | `BEHIND` / `MATCH` / `AHEAD` / `DIVERGENT` vs a **named** comparator |

`DIVERGENT` only when the *job* is still served another way you can name. `AHEAD` needs a structural reason. Ceremony: concepts, encodings, files, boot calls. A `MATCH` at 2× ceremony is `BEHIND`. Trust the programmer — "they might forget a tag" is a gap only if the design makes forgetting silent (TanStack `onSuccess`) or loud (our mismatch warning). Measure what helps a programmer decide: setup, repeated declarations, time to first useful list, recovery from an ordinary mistake, reproducibility, clarity of a failed favourite, human/agent handoff.

Honesty checks or the test fails open:

- Spec/docs "Landed" vs Conduit (does the example *do* it?).
- "We have a primitive that *could*" vs "an author *does* this without ceremony."
- Built-in vs plugin vs paid on the comparator side.
- **Negative control:** at least one job Resources should lose or refuse (GraphQL entity cache is `OUT`; process-global shared cache is refused). Offline persistence and the one-line colocated read are *jobs to evaluate*, not silent `DIVERGENT`s.
- **Positive control:** at least one job everyone agrees Resources already does (keyed cache + dedupe, pinned by a conformance fixture) — **fault it** (break the fake backend or a canned stub) and confirm the pin goes red. A test that stays green under the fault does not pin the row. Deliberate scope leak: there is a `resources_scope_leak_boundary` test — run it or source-trace it.
- `realworld_http` as a **within-repo control**: if Resources cannot beat the hand-rolled sibling on a job, it is not `AHEAD` of TanStack either.

Do not let many minor `MATCH`es cancel one leak or one install stall. If you compute a weighted reading, show denominator, weights, exclusions, and how `UNVERIFIED` moves it; prefer a categorical headline.

Automatable residue: name which jobs `implementation/resources/test/` already pins. Do not propose a new permanent benchmark service.

Deliver `intermediate/closeness-test.md` **before** you apply it.

### Phase C — Apply the test

Run Phase B against **this** checkout.

For every job: walk Resources from docs + spec + a spot-check of code. Walk the comparator from *current* docs. Assign the four fields + ceremony + citations.

**You must execute a small consequential subset.** A browser-local fake backend (`demo_backend.cljs`) proves UI/cache coordination; it does **not** by itself prove wire protocol, real AbortController cancel, durable offline, or server authorization. Say which boundary you are on. Use a **request ledger** (count), not a timeout, to claim "no duplicate fetch." Use a **controlled completion / fake clock**, not `sleep`, for delayed replies. Do not mutate a shared production service.

Minimum, unless a named limitation blocks it:

1. **Tutorial stall log.** Follow `docs/resources/index.md` + `tutorial/01`–`02` (and 04 if cheap) *literally*. Record stalls, retired spellings, missing causes.
2. **Conduit: read, navigate, reuse.** Open/filter/page the feed, visit a detail, return. Show cache hit, in-flight dedupe, stale refresh. Force a refresh failure; existing data and a useful error must remain (J11).
3. **Favourite across views, including failure and overlap.** Same article in detail and lists/feed. Optimistic apply; reject a mutation and watch rollback; then order two overlapping operations so an *older* failure or stale reply could overwrite newer accepted work. Include favorited-list membership, not only a boolean patch. Prefer a **maintained idiomatic TanStack/RTK RealWorld** if one fits — pin its commit and assess quality; a weak community app does not establish a library limitation. Otherwise a compact TanStack slice of the same fixture (session scratchpad, **never inside this repo**). Do not rebuild all of Conduit in three frameworks.
4. **Change viewer during work.** Start a scoped read, log out or switch Alice→Bob, then release Alice's delayed reply. Include a cold-boot token whose identity is unresolved. Correct behaviour is not "suppress every fetch." Scope is not server ACL.
5. **Leave and return** (J19). Start route-owned work, navigate away, allow delayed completion, revisit. Distinguish canceling the network from declining to commit the result.
6. **Plant a missing invalidation** (J4/J13). Remove one tag, use Xray/public inspection to connect the stale view to the write, restore the tag, re-fault to prove the test detects it. Compare TanStack Devtools on the same kind of mistake.
7. **`realworld_http` contrast** for J4/J5: what the author still writes by hand.
8. **Infinite-feed and/or SSR** from the capability examples if Conduit does not exercise them — or mark those jobs `UNVERIFIED`.

If the tree moves while you work: preserve the original evidence baseline. Headline form is **historical defect → landed repair → evidence still needed**. Do not leave old present-tense claims under a new header, and do not infer a whole job done from a merged patch.

Do not "fix" what you find. Produce a small scoreboard. Appendix: which rows of the translation-page scorecard went stale. The page's "every Landed row is pinned" claim gets a true/partly/false count.

Deliver `intermediate/scoreboard.md`.

### Phase D — Advantage thesis (question 2)

Generate candidates from friction you measured and from what re-frame2 makes simple. Disprove each against TanStack's strongest practical workflow (including Devtools and RTK's tag invalidation).

Structure:

1. **Jobs comparators do poorly because of substrate.** Candidates: fetch-in-render waterfalls; user id as a forgettable key segment; imperative invalidation; optimistic inverse owned by the author; process-global SSR cache; Devtools that cannot name the *event* that staled a key. Drop any candidate RTK or TanStack already covers idiomatically.
2. **Productized vs latent** in Conduit and the tutorial. Evidence tier on every claim.
3. **The small set of primitives** in *user* language. The docs already say register / cause / project plus tags on writes. If you keep that set, say why. If you replace it, say which job it fails.
4. **Limits of the data-shaped cache.** Live I/O, clocks, websockets, GraphQL graphs, offline. Do not invent a second Apollo.
5. **A year from now**, as a handful of scenes (CRUD app author, multi-tenant SaaS, SSR, an agent, a tester rendering every server state as a Story variant with no network). Each needs a **structural** reason TanStack is worse, or drop it. A meaningful ergonomic win need not be *impossible* in another ecosystem.

If you try an assistant-authored change, use public docs, skill leaves, and registry inspection on an isolated fixture; give TanStack its ordinary docs and ESLint plugin. Record discover / guess / repair / verify. Function-valued `:invalidates` / `:optimistic` patches are not statically enumerable from the registry alone.

Classification: use a synthetic sensitive field and inspect what reaches a trace vs a hydration payload. Do not treat "classified" as one blob.
6. **What we should stop copying.** Hook-on-mount fetch, `queryKey` soup, `onSuccess` invalidation lists, restoring contested optimistic context.

Also probe, with tiers: owners vs observers (machines as owners); causes on the trace bus; epoch replay of a stale-page bug; data classification as something a Conduit author meets; conformance fixtures as user-facing vs hygiene; `:params-schema` driving anything besides identity.

Challenge: a TanStack user *does* miss the one-line colocated read, TypeScript inference, `useQueries` over a dynamic list, Suspense, persisters, and the ecosystem. Which of those a re-frame2 SPA author actually misses? Evaluate the **smallest Resources-shaped answer** to the one-line read and to offline persistence *before* recommending continued deferral — Mike asked about "and other" jobs, even if the project's instinct is to refuse them.

Do not infer a completed agent loop from enumerable metadata or a tool name. Name the host (JVM vs browser), the exact skill/MCP surface, and what a planted fault still cannot discover.

A proposal that adds a concept must say what it removes. Select **roughly three to five** opportunities. Decline the rest. It is valid that Resources already has the right capability and needs teaching, or that TanStack is ahead on a job we care about.

Deliver `intermediate/advantage.md`.

### Phase E — Recommendations

Short, ranked. Rank by job leverage per concept added. Cluster symptoms that share a remedy. Sequence with a **stopping point**.

Categories:

1. **Keep measuring** — the standing test; when to re-run (TanStack major **or TanStack DB release**, Resources EP, Conduit rewrite, tutorial rewrite); automatable residue (conformance fixtures, named pinning tests). If HEAD moved during the work, the headline is historical defect → landed repair → evidence still needed.
2. **Make claimed wins honest** — `SPEC-ONLY` and translation-page Landed rows Conduit does not exercise. These outrank new features.
3. **Make shipped wins discoverable** — `UNDERMARKETED` / `INTERNAL`.
4. **Close real `BEHIND`s** a TanStack user would bounce on — smallest Resources-shaped fix, not a hook-shaped one.
5. **Productize latent advantages** — smallest productization.
6. **Reaffirm refusals** — GraphQL-in-016, entity store, fetch-from-subscribe, process-global cache, `:select`.
7. **Do not build** — explicit anti-recommendations.

Each build recommendation: title; job id(s); who hits it; evidence; smallest change; what it **removes**; size S/M; acceptance criterion. Distinguish **library defect / example bug / inaccurate comparison prose / missing packaging / unexecuted acceptance**. Cluster symptoms that share a remedy and name the owning layer. Do not file beads.

**Rerun triggers are row-specific** where you can: a TanStack major invalidates Devtools/SSR rows; a Conduit rewrite invalidates J4–J8; a `016` deferred slice landing invalidates the corresponding `OUT`/`TARGET` row. A TanStack DB release invalidates only the local-first job, and only after you attribute capabilities to **that package**, not Query core.

If a recommendation would touch a hot-zone file (`spec/016` is sequential with other Spec 016 work; `spec/API.md` + manifests; `.github/workflows/*`), say so.

---

## 6. Report shape

Write as if a sceptical maintainer will use it, in one sitting, to decide what to measure and what to build next. Lead with the answers. Do not restate the tutorial. Do not write a README for Resources. Link intermediate files.

```
ai/findings/Resources/grok/
  prompt.md
  report.md
  intermediate/
    landscape.md
    closeness-test.md
    scoreboard.md
    advantage.md
    evidence-ledger.md
```

`report.md` must include:

1. **Answers in brief** — how we test; this run's categorical headline; the thesis; the one thing to do first.
2. **This run's scoreboard** — job table with the four fields. Date, timezone, trunk SHA, comparator versions/URLs, executed vs only read.
3. **What the translation-page scorecard got wrong or went stale.**
4. **Advantage thesis** — claims tagged `BUILT` / `SPECIFIED` / `IMAGINED`.
5. **Recommendations** — ranked, including do-not-build, sequence, stopping point.
6. **How to re-run the test** without this prompt.
7. **What you did not do.**

House style: repo path citations; competitor URL + access date; Phase B status words (no `WIN` as a row status); no AI attribution; timestamp with timezone; this brief's path at the top of the report.

---

## 7. Failure modes

- Noun matching (`useQuery`, `HydrationBoundary`, `notifyOnChangeProps`).
- Quiet narrowing; `DIVERGENT` for a missing *outcome*.
- Unfair ecosystem (Resources+routing+SSR+Xray vs bare `useQuery`).
- Spec or translation-page as reality.
- Substrate as product ("we have frames, therefore SSR isolation") without a Conduit/SSR path.
- GraphQL/entity-cache scored as a v1 GAP.
- Treating absence from Conduit as `SPEC-ONLY` when a capability example exists.
- File-count "savings" vs `realworld_http` without naming shared code.
- Crediting Query core with TanStack DB, or requiring Resources to become a sync engine.
- Native-default request counts as an efficiency win without a matched-policy run.
- Stale TanStack (v4 notes as current).
- Retired vocabulary from EP-0003 bead prose.
- Gold-plated matrix (>~18 jobs).
- A percentage over weak evidence.
- Advantage-as-inventory of runtime-db keys.
- Recommendations that add encodings (a hook wrapper, a second cache, `queryFn` promises).
- Implied execution (favourite "worked" when you only read `mutations.cljs`).
- Quiet dependence on sibling `fable/` / `astra/` reports, or on Story research findings.
- Pipeline-green theatre if you run a gate.
- **Clock two SHAs if the tree moves**; leaving present-tense defects under a "landed" header.
- Overstating consensus with siblings after you read them.
- A timeout or unmatched stub as proof that no duplicate request occurred.
- Inferring a whole job done from a merged patch, or an agent workflow from a tool name.
- Caricaturing TanStack from our own migration page.

---

## 8. Practical constraints

- Read-only on the git-tracked tree. Scratch only under `ai/findings/Resources/grok/`.
- No `bd` writes, no commits, no `git add` under `ai/`. Reading beads is in scope.
- You may web-search and open competitor docs. You may run Conduit and small JVM/CLJS probes.
- You may not start a multi-hour suite "for completeness." Prefer existing tests as pins.
- Worker-worktree guard before any edit outside `ai/` — this brief should not require those edits.
- Windows/MSYS traps in `CLAUDE.md`: ripgrep-backed search, not bash `grep`; no piped censuses through `head`; `jq` CRLF; positive control on zeros.

---

## 9. Done when

A maintainer can:

- Re-run the closeness test later without this prompt.
- See, in one table, capability, discoverability, evidence, and comparison — including `SPEC-ONLY` pretence.
- Repeat the advantage thesis with tiers, name three-to-five opportunities plus the declined list.
- Act on a short ranked list that includes do-not-build, a sequence, and a stopping point.

Before finishing, challenge yourself: Did you verify the loudest Landed claims against Conduit *and* a pinning test? Did you compare complete workflows, including the tenth read, RTK tags, and TanStack Devtools — not a caricature? Did UI-shaped jobs see a browser or an honest `UNVERIFIED`? Did a deliberate fault turn the claimed pin red? Did you mistake a deferred GraphQL phase for a GAP, a resolved refusal for an oversight, a conformance fixture for a user-facing win, or an existing `ensure` for a finished authoring path? If HEAD moved, does the headline still describe the pin? Have you recommended the smallest powerful design, and declined attractive work that does not earn its cost?

If the report is long because intermediates are pasted in, it is not done. If it is short because it only restates `coming-from-tanstack-query.md`, it is not done — the test has to have been *applied*, including Conduit.
