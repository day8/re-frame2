# Resources: practical parity and a better re-frame2 server-state experience

You are conducting independent product and engineering research on **re-frame2 Resources**. Produce an evidence-based report and actionable recommendations in `ai/findings/Resources/astra/`.

The user's objective is feature parity with **TanStack Query and comparable JavaScript and non-JavaScript alternatives**, expressed through re-frame2's strengths, idioms and ethos. Use the **Conduit RealWorld example** as the central application case. Establish how to test closeness to that objective and imagine credible ways Resources could be more powerful, more ergonomic and more productive for programmers and their AI assistants.

This is a research assignment. Inspect, run bounded experiments and write the report. Do not implement product changes, rewrite specifications or create a speculative implementation backlog. Follow the repository's applicable instructions for task tracking, checkouts, validation and handoff. Research artifacts belong in this folder; preserve this prompt.

## 1. Product posture

Apply the project's stance, quoted verbatim from `CLAUDE.md`:

> Posture is that we are pre-alpha and focused on elegance, power and a masterpiece.
> But we are not over-engineering or gold plating. Also, we trust the programmer.
> We're trying to facilitate high productivity for them (and the AI they use) via a
> library with excellent ergonomics and low friction. We don't need to litigate every
> last fine detail and drown in the minutiae.

Interpret parity as accomplishing the programmer's job with comparable reliability, usability and reasonable effort. Matching API names, option counts or implementation architecture is unnecessary. A deliberate difference counts as a successful alternative only when the relevant job is served. A deferred feature remains a gap against a broader parity objective when that job matters; document the tradeoff rather than making it disappear from the comparison.

Favor a minimal API, good defaults, progressive disclosure, clear documentation and excellent existing tools. Prefer removing repetitive application code and repairing incomplete connections between existing capabilities. A new abstraction needs a concrete task that the existing model handles poorly. Trust competent programmers; distinguish a necessary invariant from an unnecessary restriction or diagnostic.

## 2. Questions the report must answer

1. What do developers reasonably expect from a modern server-state/query library, and which expectations matter most for re-frame2 SPAs?
2. What does Resources currently deliver, through what public route, with what evidence and limitations?
3. Where does Conduit demonstrate that capability, hide missing infrastructure through application glue, or leave a workflow untested?
4. How can we repeatably measure parity, correctness and ergonomics without constructing a permanent benchmarking project?
5. Which advantages are already demonstrated, which are implemented but unproved as user benefits, and which are imagined?
6. What small, coherent changes or integrations would most improve the experience? What should explicitly wait, and what should we avoid building?

The answer must be free to conclude that a competitor is better for a particular job, that Resources already suffices, or that evidence is insufficient.

## 3. Establish the actual Resources surface

Record the research date, repository SHA and relevant environment. Discover applicable local instructions before working. Read these starting points and follow their implementation/test references selectively:

- `spec/016-Resources.md`; `docs/EP/EP-0003-resource-queries.md`, `docs/EP/EP-0016-resource-mutation-completion.md`, `docs/EP/EP-0021-infinite-resources.md`.
- `implementation/resources/src/re_frame/resources.cljc`, its neighboring implementation namespaces, `implementation/resources/deps.edn`, and `implementation/resources/test/`.
- `implementation/core/src/re_frame/core_resources.cljc`, to distinguish the public facade from the optional artifact that supplies it.
- `docs/resources/`: introduction, concepts, glossary, testing, migration from TanStack Query, how-tos and the five-part RealWorld tutorial. Also `docs/api/re-frame.resources.md`.
- `examples/real-apps/realworld_resources/`, especially `README.md`, `core.cljs`, `resources.cljs`, `mutations.cljs`, `scope.cljs`, `routing.cljs`, `auth.cljs`, `views.cljs`, `article_editor.cljs` and `settings.cljs`.
- `examples/real-apps/realworld_http/` and `examples/real-apps/realworld_shared/`. The HTTP sibling is a useful within-project comparison for what Resources removes; it is not a proxy for TanStack Query.
- `examples/capabilities/resources/`, including the simple example, LinearLite and infinite feed; `examples/capabilities/ssr/resources_ssr/`.
- Relevant Conduit tests under `implementation/adapters/reagent/test/re_frame/`, resource conformance fixtures under `spec/conformance/fixtures/`, and actual build targets in `implementation/shadow-cljs.edn`.
- `tools/xray/spec/024-Resources-Panel.md`, the Resources panel implementation/tests, and relevant public Pair/tooling surfaces. Follow links rather than assuming a dedicated Resources MCP interface exists.

Consult the frame, HTTP, routing, machines, reactive substrate, SSR and instrumentation contracts only as needed to understand ownership and integration. Separate capability supplied by Resources from capability supplied by managed HTTP, the router, SSR, Xray, an adapter, the backend or the application.

Treat spec prose, migration pages, prior audits, tests and bead descriptions as claims or leads. Trace important claims to source and discriminating execution. Read correction/closure history before turning an old bead into a current defect. Do not infer absence from one failed search, presence from one symbol, or working public ergonomics from an internal helper alone.

## 4. Research credible alternatives fairly

Use current primary documentation, release metadata and selected source/tests. Pin exact released versions used in experiments and distinguish stable releases from previews. Recheck moving documentation against the installed release. Upstream marketing and comparison tables are leads, not findings.

Use **TanStack Query as the principal comparator**, including its framework-independent client/core and a supported browser adapter. Execute an idiomatic React/TypeScript slice where appropriate; do not equate TanStack Query with only `useQuery` inside a component. Include:

- **RTK Query**, especially endpoint declarations, tag invalidation and Redux tooling.
- **SWR**, for a compact public API, mutation/revalidation behavior and cache ergonomics.
- **One normalized-cache alternative**, such as Apollo Client or urql, to illuminate entity consistency and the cost/benefit of normalization. Keep GraphQL-specific capabilities distinguishable from ordinary HTTP query parity.
- **At least one relevant non-JavaScript alternative**, such as Riverpod in Dart. Select it for an instructive async-data/lifecycle/testing model, not merely to fill a language slot.

A Clojure/ClojureScript reference such as Fulcro or `shipclojure/re-frame-query` may add an idiomatic comparison if current evidence supports it. Keep the comparison bounded; a few well-supported alternatives are more useful than a shallow ecosystem census.

Primary starting points, to verify afresh at execution time:

- [TanStack Query overview](https://tanstack.com/query/latest/docs/framework/react/overview), [optimistic updates](https://tanstack.com/query/latest/docs/framework/react/guides/optimistic-updates), and its linked guides/API/source.
- [RTK Query automated refetching](https://redux-toolkit.js.org/rtk-query/usage/automated-refetching).
- [SWR mutation documentation](https://swr.vercel.app/docs/mutation); if unavailable, inspect the official repository/released documentation.
- The official documentation and released implementation of the selected additional alternatives.

Challenge tempting superiority claims explicitly. Does the competitor actually require fetching from render? Can its client/cache be scoped per provider or request? Does it already support declarative invalidation, dependent/prefetched queries, automatic rollback, mutation serialization or race-aware patterns? Is a dangerous rollback the library's enforced behavior or one naive application recipe? Does its tooling expose the relevant cause through another integration? Verify the answers; do not reproduce caricatures from our own migration page.

Credit supported integrations, with their setup and ownership identified. Do not impose a score ceiling merely because a provider is community-maintained. Separate a native default, an ordinary documented composition, custom application code and a hypothetical workaround.

## 5. Define the closeness test before making judgments

Create `evaluation-plan.md` with stable job IDs and importance before completing the comparison matrix. Use the following candidate families; combine related cases to keep the instrument useful:

| Job family | Outcomes and questions to cover |
|---|---|
| First useful read | Install in an ordinary consumer, register/fetch/project data, show loading/error/success, change code and find the relevant docs. What must the author know or configure? |
| Identity, cache and reactive reads | Parameter/scoped keys, deduplication, freshness, stale data during refresh/error, selection/derived views, structural sharing and render work. Can two consumers share a read without duplicating work? |
| Demand and lifetime | Conditional/dependent reads, route/event/machine ownership, prefetch, navigation, cancellation, stale-reply suppression, release and GC. Does a passive read require discoverable additional wiring? |
| Revalidation and unreliable networks | Focus/reconnect, polling, retry/backoff, offline/paused states and recovery. Identify behavior owned by transport and distinguish abort from ignoring a late result. |
| Writes and cross-view consistency | Mutation instances, pending/error/success, completion continuations, response population, tag/key invalidation and active/inactive reads. What changes automatically and what is application policy? |
| Optimistic concurrency | Immediate feedback, rejection/rollback, overlapping writes, contested rollback, stale reads arriving during a mutation, ordering and correction from authoritative server data. |
| Pagination and infinite data | Parameters/cursors, previous data, load more, end-of-feed, refresh/invalidation, memory/page retention and mutation effects across pages. |
| Viewer/request isolation | Anonymous, unresolved-session and authenticated states, user/tenant changes, in-flight work and scope clearing. Distinguish cache identity from backend authorization. |
| SSR and hydration | Request isolation, preload/wait boundaries, payload projection, scope agreement, freshness and avoiding an unintended duplicate fetch. Include streaming/Suspense only at their actual applicable layer. |
| Persistence and live updates | Persisted caches, offline mutation recovery, cross-tab synchronization, push subscriptions and normalized entity updates where relevant. Distinguish expected parity jobs from optional adjacent architecture. |
| Testing, explanation and human/AI use | Deterministic network/time fixtures, inspectable pending work and causes, Xray/DevTools, source discovery, diagnosing a stale or inconsistent view and preserving a regression. |

Mark jobs essential, important or optional with brief reasons grounded in ordinary SPAs and the user's objective. Conduit anchors the investigation but does not define the ceiling of the library; it does not exercise every persistence, infinite-data or SSR expectation.

For each matrix row record the job, public route/provider, implementation status, documentation/discoverability, observed behavior, evidence strength and limitation. Keep **specified / implemented / exercised / ergonomic** separate. Use categorical findings such as demonstrated, partial, gap, deliberate alternative and unknown; explain their scope. Comparative advantage is a separate judgment.

Do not report one weighted parity percentage or forecast a post-fix score. Essential failures cannot be offset by unrelated capabilities. Do not choose a “data-only” job as a control designed to make re-frame2 win. Controls establish that known defects are detected and correct behavior passes; comparative outcomes remain open.

## 6. Make Conduit the main executable case

Read how the example starts and which backend it uses. Preserve a deterministic fixture/seed. A browser-local simulated backend can prove UI/cache coordination; it cannot by itself establish wire protocol, real cancellation, durable offline support or server authorization. Identify each boundary. Do not mutate a shared production service to run the study.

Build a compact TanStack Query counterpart for the same representative slices, using idiomatic supported patterns and equivalent responses/failure timing. Reuse the same domain fixtures where practical. Do not rebuild all of Conduit in several frameworks merely to compare a cache workflow. Inspect other alternatives deeply enough to support the claims made about them; label unexecuted comparisons.

Execute a bounded subset that includes ordinary success and the consequential failure cases:

1. **Read, navigate, reuse.** Open/filter/page the article feed, visit a detail and return. Show cache hit, in-flight dedupe and stale refresh. Force a refresh failure and observe whether existing data and useful error information remain. Count actual requests and inspect the rendered result.
2. **Favorite across views.** Show the same article in detail and relevant lists/feed. Apply a favorite mutation, observe optimistic feedback, response population and subsequent invalidation/refetch. Reject a mutation. Then control the order of two overlapping operations so an older failure or stale response could overwrite newer accepted work. Verify the final user-visible state and eventual convergence to the authoritative fixture. Include relevant favorited-list membership/count effects; do not pretend patching a boolean is the entire product job.
3. **Change viewer during work.** Start a scoped read, log out or switch from Alice to Bob, then release Alice's delayed reply. Include a cold-boot token whose user identity is unresolved. Observe keys, requests, cache projections and the UI. Ensure correct behavior does not simply suppress every fetch. A cache scope cannot substitute for server access control.
4. **Leave and return.** Start route-owned work, navigate away, allow a delayed completion, and revisit. Observe ownership, stale suppression, reuse and cleanup. Use controlled completion or a fake clock rather than flaky sleeps. Distinguish canceling network work from declining to commit its result.
5. **Explain and repair one real defect.** Introduce a small application mistake such as a missing invalidation target. Use Xray/public inspection to connect a stale view to the responsible write/cache decision. Correct application behavior while retaining the expected outcome, rerun, and reintroduce the fault to prove the regression test detects it. Compare the corresponding supported TanStack Query DevTools/application debugging route.

Use existing focused tests/examples for an additional **infinite-feed** and **SSR/hydration** check where practical. At minimum, trace and specify their discriminating experiments. Probe persistence/offline features only far enough to establish the supported route or a meaningful gap. If a build or host is unavailable, retain the exact failure and continue independent research; absence of an executed result is not evidence that a capability is absent.

Record commands, source revision, relevant dependencies, fixture contents, request/response order, expected and actual results, runner exit codes and browser screenshots where the UI matters. Existing tests count only for what they assert. A timeout or unmatched stub is not proof that no duplicate request occurred; use an explicit request ledger/count. A helper round trip is not a complete UI workflow. Reading a peer's result is not executing it yourself.

Use absolute paths and repository-required checkout verification for gates. Store logs and exit files on ignored paths with the checkout name and a fresh attempt number. Keep production source unchanged; isolate temporary fixtures and stop only processes you started and identified.

## 7. Evaluate ergonomics and credible superiority

Use three perspectives: a framework-aware newcomer, a maintainer adding/changing a feature, and an assistant collaborating with that maintainer. Compare equivalent outcomes and knowledge assumptions. Count mandatory concepts, repeated declarations, duplicated state, files touched, manual cache coordination, context switches, wrong turns and recovery effort. Separate one-time setup from marginal feature cost. LOC, request counts and elapsed time are supporting observations, not interchangeable quality scores.

Measure timings only under stated comparable conditions. Separate cold setup, warm iteration and actual network latency. Avoid a broad performance platform: one representative read/mutation workload plus relevant existing tests is enough unless a measured concern justifies more.

Explore these hypotheses without presuming their truth:

- **Declare a consequence once.** Resource/mutation declarations, scopes and tags keep several screens correct with less repeated coordination. Compare fairly with RTK Query's declarations, normalized caches and idiomatic TanStack Query invalidation.
- **Demand has an owner and a reason.** Passive views plus routes/events/machines make fetching, cancellation and release easier to understand. Determine whether this also makes the first read harder or creates avoidable ownership ceremony.
- **Optimism remains correct under overlap.** Recorded inverse patches and conflict handling reduce application rollback code while preserving newer work. Compare actual supported concurrency strategies, including their UX and refetch costs.
- **One explanation connects the app.** Frames, the work ledger, epochs and Xray explain which interaction changed which cache entry and why a view refreshed. Demonstrate faster diagnosis or fewer translations; a larger trace alone is not an advantage.
- **The same case serves human, test and assistant.** Reuse public declarations and deterministic evidence to reproduce, inspect and repair a bug. State the actual host/frame and transport. Do not infer a completed agent workflow from enumerable metadata or an MCP tool name.

Add a small number of better ideas from the research. For each proposed advantage give a concrete before/after workflow, the existing primitive it uses, its smallest coherent implementation or documentation change, its costs, a falsification test and a stopping point. Classify it as demonstrated, implemented but unproved, or imagined. Prefer a worked example or an existing integration over new machinery when that achieves the goal.

## 8. Recommendations and editorial discipline

Rank a short list by user value, evidence and effort. Each recommendation must identify:

- the specific job/problem and affected source/docs/example surface;
- evidence or an explicit unresolved assumption;
- the smallest useful change and what repeated work it removes;
- an acceptance experiment, rough effort and relevant dependencies/tradeoffs;
- whether it is a correctness repair, ergonomic improvement, documentation/example gap, existing-tool integration or exploratory idea.

Distinguish defects in the library from example bugs, inaccurate comparison prose, missing packaging and unexecuted acceptance. Retain demonstrated good behavior and design tradeoffs. Include a concise “do not build now” discussion with reasons and concrete triggers for revisiting deferred ideas. Do not silently exclude a parity outcome merely because a custom implementation would be expensive; first investigate a supported integration.

Before finalizing, inspect relevant changes since the research pin and re-read current bead dispositions. Preserve the original evidence baseline. If fixes landed, revise the headline, matrix and recommendation sequence throughout: **historical defect → landed repair → evidence still needed**. Do not leave old present-tense claims underneath a new header or infer whole-job completion from a merged patch.

Write an independent draft before consulting sibling Resources research. If sibling reports are available for a subsequent synthesis, preserve that draft, read them critically, verify consequential additions and attribute imported evidence. Record actual agreement and substantive disagreement. Repeated citations are not independent confirmation, and consensus does not settle a disputed empirical claim.

## 9. Deliverables

Write these files under `ai/findings/Resources/astra/`:

1. **`report.md`** — a decision-oriented assessment, approximately 2,500–4,000 words if sufficient. Lead with the current conclusion, practical gaps and strongest advantage hypothesis. Include the Conduit findings, restrained ranked recommendations, remaining uncertainty and what would change the conclusion.
2. **`parity-matrix.md`** — job-level comparison with source/provider, importance, evidence, current status and scope. Link supporting receipts. No composite parity score.
3. **`evaluation-plan.md`** — reproducible fixtures, commands, success/failure controls, coverage actually executed, unexecuted portions, expected setup/runtime cost and a lightweight rerun cadence. Keep initial criteria distinguishable from subsequent refinements.
4. **`evidence.md`** and, as useful, **`evidence/`** — primary-source links with versions/dates, local source pins, selected test/log/response/screenshot receipts, environment details and attribution. Keep large transcripts out of the main report.

Use direct primary-source links near claims, source paths pinned to a revision, and accurate labels for measured behavior, inspected implementation, documentation, inference and imagination. Validate local links/tables and check that the main report, matrix and protocol agree. Avoid invented numbers, stale competitive claims, unearned “ahead” verdicts and exhaustive feature inventories that obscure the decision.

Finish with a brief handoff linking the report and naming the most consequential findings, what was actually exercised and the important remaining limits. The intended result is a clear answer to **how close Resources is, how to find out reliably, and how to make it exceptional without making it complicated**.
