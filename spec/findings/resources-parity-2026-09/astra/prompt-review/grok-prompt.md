# Research brief: measuring Resources against TanStack Query-class server-state libraries, and imagining the win

You are being asked to **research and report**, not to implement. Do not edit tracked files, specifications, tests, workflows, or tracker state. Do not open a PR. Intermediate notes and the report itself belong only under `ai/findings/Resources/grok/` (this tree is local-only and gitignored). Leave this `prompt.md` untouched.

Sibling folders `ai/findings/Resources/fable/` and `ai/findings/Resources/astra/` may exist and may grow reports. **Do not read those reports until your own `report.md` is drafted.** Independence is load-bearing. If you read them afterwards, say so, and treat the result as a synthesis note — not as this pass.

Resolve every path against the checkout you actually inspect. Pin material observations to `git rev-parse HEAD`. If that SHA moves during the work, re-check consequential claims before concluding.

The flagship application is the Conduit RealWorld example at `examples/real-apps/realworld_resources/`. A sibling at `examples/real-apps/realworld_http/` is the **same app one layer down** (managed HTTP, hand-rolled cache slices). That pair is a control: the difference between them is what Resources claims to do for you. Use it.

---

## 0. Stance — paste, do not paraphrase

> Posture is that we are pre-alpha and focused on elegance, power and a masterpiece.
> But we are not over-engineering or gold plating. Also, we trust the programmer.
> We're trying to facilitate high productivity for them (and the AI they use) via a
> library with excellent ergonomics and low friction. We don't need to litigate every
> last fine detail and drown in the minutiae.

The first sentence is the bar. Everything after it is when to **stop**. A report that recommends a GraphQL client, a normalized entity store, and an offline write queue "because Apollo has them" has failed the second half. A report that litigates hook nouns Resources has already refused has failed it too.

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
| `examples/real-apps/realworld_resources/README.md` plus `resources.cljs`, `mutations.cljs`, `routing.cljs`, `scope.cljs`, `views.cljs` | Flagship. Every read a resource, every write a mutation. |
| `examples/real-apps/realworld_http/README.md` (orientation only) | Control: the same Conduit without Resources. |
| `docs/EP/EP-0003-resource-queries.md` (rationale + prior-art, not the whole bead plan) | Originating EP. |
| `docs/EP/EP-0019-optimistic-mutation-rollback.md` abstract | Contested rollback (`:on-conflict :invalidate`) is the claimed semantic departure. |
| `docs/EP/EP-0021-infinite-resources.md` abstract | Infinite/load-more. |
| `docs/api/re-frame.resources.md` | Public API. Spot-check vs code. |
| `docs/core/fresco/08-async-resources.md` | How the view layer is taught to consume resources. |

Do **not** read all of Spec 016 up front. Pull a section when a job requires it.

For question 2, also pull at need: `spec/002-Frames.md` (runtime partition), `spec/014-HTTPRequests.md` (transport), `spec/012-Routing.md` (route `:resources`, readiness as resource projection), `spec/011-SSR.md` (hydration), `spec/009-Instrumentation.md` / Xray resources panel if any.

### 4.2 Prior art already in-tree (re-verify; do not recopy)

- `docs/resources/coming-from-tanstack-query.md` §The full parity scorecard — exhaustive table vs TanStack / RTK / SWR. Status tags are claims.
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
- `realworld_http` vs `realworld_resources` is the before/after of the artefact.

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

**TanStack Query is the main comparison** (Query v5 / the current stable major on the run date — do not inherit v4 from our files). Cover: `useQuery` / `useMutation` / `useInfiniteQuery`, query keys, staleTime/gcTime defaults, invalidation, optimistic + context rollback, Devtools, persistence, SSR/hydration, `enabled`, `select`, `placeholderData`, focus/reconnect/interval refetch, cancellation, `notifyOnChangeProps`, the official RealWorld / docs examples.

Then a **small complementary set — four to six besides TanStack, not twenty:**

- **SWR** — smaller surface; `mutate` + `optimisticData`.
- **RTK Query** — closest cousin (declared endpoints, `providesTags` / `invalidatesTags`). Fair comparison for "invalidation as data."
- **One GraphQL client, briefly** (Apollo or urql or Relay) — only to bound GraphQL/entity-cache as `OUT` of Spec 016, not as a silent GAP. Do not score "no normalized store" as behind unless Conduit needs entities.
- **CLJS / Clojure lineage as one group:** `shipclojure/re-frame-query`, Fulcro/Pathom if still informative. Inheritances vs inventions.
- **One non-JS** with a different approach if it changes a job: Elm Http+cache conventions, Swift Observation+async, or a Rails-side fragment cache. Skip if it does not change Phase B.

Adjacent, only where they steal a job: tRPC, Next.js `fetch` cache / RSC, React `use()` + cache. Resources is a client cache; do not pretend to be a server component runtime.

For each capability distinguish **built-in / official plugin / community / custom app work.** Include TanStack Devtools and the persistence plugin as ordinary extras, with setup named. Do not compare Resources+Xray+routing+SSR against a bare `useQuery` with no Devtools.

Cite URLs and access dates. Stop when another tool would not change Phase B.

Deliver `intermediate/landscape.md`.

### Phase B — Design the closeness test (question 1)

A **re-runnable test**, not a score. Jobs, not features. Start from Conduit's actual user journeys plus `018`-style jobs a TanStack user brings. Stay under ~18 rows.

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
- **Negative control:** at least one job Resources should lose or refuse (GraphQL entity cache is `OUT`; process-global shared cache is refused).
- **Positive control:** at least one job everyone agrees Resources already does (views never fetch, or scoped identity) — verify it.
- `realworld_http` as a **within-repo control**: if Resources cannot beat the hand-rolled sibling on a job, it is not `AHEAD` of TanStack either.

Do not let many minor `MATCH`es cancel one leak or one install stall. If you compute a weighted reading, show denominator, weights, exclusions, and how `UNVERIFIED` moves it; prefer a categorical headline.

Automatable residue: name which jobs `implementation/resources/test/` already pins. Do not propose a new permanent benchmark service.

Deliver `intermediate/closeness-test.md` **before** you apply it.

### Phase C — Apply the test

Run Phase B against **this** checkout.

For every job: walk Resources from docs + spec + a spot-check of code. Walk the comparator from *current* docs. Assign the four fields + ceremony + citations.

**You must execute a small consequential subset.** Minimum, unless a named limitation blocks it:

1. **Tutorial stall log.** Follow `docs/resources/index.md` + `tutorial/01`–`02` (and as far as 04 if cheap) *literally*. Record stalls, retired spellings, missing causes.
2. **Conduit, as a user.** How to run is in `examples/real-apps/realworld_resources/README.md`. Browse the feed, open an article, favourite, follow, comment or edit if the fake backend allows. Confirm lists update without a hand-wired refresh. If the browser cannot open, compile or read the registrations and say so — UI-shaped jobs are then `UNVERIFIED` on evidence, not `ABSENT` on capability.
3. **Favourite as the optimistic/invalidation probe.** Trace `:realworld/favorite` from `mutations.cljs` through tags to which resources refetch. Compare to a TanStack `onSuccess` + `setQueryData` recipe.
4. **Logout / viewer switch.** Does scope re-key or `clear-scope` actually prevent seeing the previous user's feed? Source-trace at minimum; exercise if you can.
5. **`realworld_http` contrast.** Read enough of the HTTP sibling to say, for J4/J5, what the author still writes by hand.

Do not "fix" what you find. Produce a small scoreboard. Appendix: which rows of the translation-page scorecard went stale.

Deliver `intermediate/scoreboard.md`.

### Phase D — Advantage thesis (question 2)

Generate candidates from friction you measured and from what re-frame2 makes simple. Disprove each against TanStack's strongest practical workflow (including Devtools and RTK's tag invalidation).

Structure:

1. **Jobs comparators do poorly because of substrate.** Candidates: fetch-in-render waterfalls; user id as a forgettable key segment; imperative invalidation; optimistic inverse owned by the author; process-global SSR cache; Devtools that cannot name the *event* that staled a key. Drop any candidate RTK or TanStack already covers idiomatically.
2. **Productized vs latent** in Conduit and the tutorial. Evidence tier on every claim.
3. **The small set of primitives** in *user* language. The docs already say register / cause / project plus tags on writes. If you keep that set, say why. If you replace it, say which job it fails.
4. **Limits of the data-shaped cache.** Live I/O, clocks, websockets, GraphQL graphs, offline. Do not invent a second Apollo.
5. **A year from now**, as a handful of scenes (CRUD app author, multi-tenant SaaS, SSR, an agent) — each impossible or humiliating in TanStack *for a structural reason*, or drop it.
6. **What we should stop copying.** Hook-on-mount fetch, `queryKey` soup, `onSuccess` invalidation lists, restoring contested optimistic context.

A proposal that adds a concept must say what it removes. Select **roughly three to five** opportunities. Decline the rest. It is valid that Resources already has the right capability and needs teaching, or that TanStack is ahead on a job we care about (Devtools polish, ecosystem, `staleTime: 0` as a default a migrant expects).

Deliver `intermediate/advantage.md`.

### Phase E — Recommendations

Short, ranked. Rank by job leverage per concept added. Cluster symptoms that share a remedy. Sequence with a **stopping point**.

Categories:

1. **Keep measuring** — the standing test; when to re-run (TanStack major, Resources EP, Conduit rewrite); automatable residue.
2. **Make claimed wins honest** — `SPEC-ONLY` and translation-page Landed rows Conduit does not exercise. These outrank new features.
3. **Make shipped wins discoverable** — `UNDERMARKETED` / `INTERNAL`.
4. **Close real `BEHIND`s** a TanStack user would bounce on — smallest Resources-shaped fix, not a hook-shaped one.
5. **Productize latent advantages** — smallest productization.
6. **Reaffirm refusals** — GraphQL-in-016, entity store, fetch-from-subscribe, process-global cache, `:select`.
7. **Do not build** — explicit anti-recommendations.

Each build recommendation: title; job id(s); who hits it; evidence; smallest change; what it **removes**; size S/M; acceptance criterion. Do not file beads.

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
- Stale TanStack (v4 notes as current).
- Retired vocabulary from EP-0003 bead prose.
- Gold-plated matrix (>~18 jobs).
- A percentage over weak evidence.
- Advantage-as-inventory of runtime-db keys.
- Recommendations that add encodings (a hook wrapper, a second cache, `queryFn` promises).
- Implied execution (favourite "worked" when you only read `mutations.cljs`).
- Quiet dependence on sibling `fable/` / `astra/` reports.
- Pipeline-green theatre if you run a gate.

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

Before finishing, challenge yourself: Did you verify the loudest Landed claims against Conduit? Did you compare complete workflows, including RTK tags and TanStack Devtools? Did UI-shaped jobs see a browser or an honest `UNVERIFIED`? Did you mistake a deferred GraphQL phase for a GAP, or an existing `ensure` for a finished authoring path? Have you recommended the smallest powerful design, and declined attractive work that does not earn its cost?

If the report is long because intermediates are pasted in, it is not done. If it is short because it only restates `coming-from-tanstack-query.md`, it is not done — the test has to have been *applied*, including Conduit.
