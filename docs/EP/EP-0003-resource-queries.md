# EP-0003: Resource Queries

Status: final

> **`final` means the decisions are settled.** The HTTP-only scope graduated
> `accepted`→`final` on 2026-06-11 (Mike ruled option (a) on `rf2-9l9xs2`), once
> all P1 resources bugs had merged. The accepted HTTP-only scope has its
> normative home in [`spec/016-Resources.md`](../../spec/016-Resources.md);
> where this EP and the spec differ, the **spec governs**, and this EP remains
> the design record (rationale, prior-art benchmark, slice plan) and the home of
> the deferred GraphQL phase. The fresh-skip/cache-hit hold (`rf2-hsa0sv`) is
> resolved: the reference implementation short-circuits fresh ensures and emits
> `:rf.resource/cache-hit` exactly as Spec 016's FSM, §Restore, and §Xray prose
> describe (PR #3791). All twelve core slices (artefact skeleton, work-ledger
> substrate, runtime, managed-HTTP, invalidation/GC, route, SSR, Xray,
> focus/reconnect, mutation, docs) are complete on `main`, and both mandatory
> wave-end reviews (correctness `rf2-2wzk6g`, docs `rf2-lqpwki`) passed clean —
> every acceptance criterion and every §9 conformance fixture PASS, including the
> first-public-beta gate (mutations + focus/reconnect revalidation). Finalizing
> the *decisions* does not, on its own, assert the *implementation* was gap-free;
> the [Implementation errata](#implementation-errata) ledger below tracks that
> separately. All review-wave findings were fixed in-wave (zero open EP-0003
> errata); the remaining items there are cross-cutting hardening and
> spec-coherence follow-ups in other subsystems, not contract gaps. Slices are
> tracked per [§Bead Structure](#bead-structure).

## Implementation errata

The EP decisions are final and the twelve core slices have shipped: **every
review-wave finding was fixed in-wave, so there are zero open EP-0003 errata.**
This section is kept as a closed record of the build-completion and
review-reconciliation work that followed the decision-freeze; none of it reopens
any ruling. Finalizing the *decisions* did not, on its own, assert the
*implementation* was gap-free; this ledger tracks that separately.

### Resolved errata — review-wave findings fixed in-wave

The wave-end correctness review (`rf2-2wzk6g`) and the design-fork/lifecycle
work surfaced the findings below. **All were fixed and merged before
graduation** — they are kept here as a closed record and no longer reopen any
ruling:

- **`rf2-hsa0sv`** *(fixed — PR #3791)* — fresh-skip ensure semantics +
  `:rf.resource/cache-hit` emit. The reference `ensure-load` did not short-circuit
  a fresh `:loaded` ensure, so an ensure of an already-loaded, still-fresh entry
  re-fetched unconditionally — diverging from the FSM (a fresh `ensure` has no
  transition off `:loaded`) and from §Restore ("refetches only on the next
  `ensure` from a live owner … gated by the entry's own stale/fresh policy").
  Mike ruled (a) implement: a fresh `:loaded` ensure now attaches the owner lease,
  emits `:rf.resource/cache-hit`, drains any blocking route slot immediately (a
  fresh blocking resource settles the navigation at once — no hang), and starts no
  new generation/fetch. The Spec 016 cache-hit note flipped from
  reserved/forward-looking to **implemented**; the FSM, trace family, and Xray
  panel were updated. A stale `:loaded` entry still refetches (fresh-skip never
  swallows a stale refresh).
- **`rf2-er7qx2` / `rf2-fopuj9` / `rf2-o3d1uf`** *(fixed — PR #3794, ssr-restore
  cluster)* — three P1 SSR/restore correctness gaps:
  - **`er7qx2`** — the SSR blocking-resource drain/timeout loop was helper-only
    and not wired into the Ring and streaming render paths; a never-settling
    blocking resource could render against an unchecked loading/skeleton state.
    The drain/timeout policy is now integrated into both SSR render paths so
    current-navigation blocking resources settle before render or install a
    settled timeout error entry.
  - **`fopuj9`** — the hydration refetch planner only invoked reconciliation, and
    redacted projected data could be misclassified as usable data. Hydration now
    refetches stale/omitted/redacted entries needed by the live route while fresh
    serialized entries do not double-fetch, and the redacted sentinel is treated as
    metadata-only, not usable data.
  - **`o3d1uf`** — epoch restore reconciled dangling work-ledger rows and settled
    resource entries but left pending mutation instances holding current-work and
    generation, so a late pre-restore mutation reply could still patch/populate/
    invalidate post-restore state. Restore now terminally settles restored pending
    mutation instances, so stale pre-restore mutation replies are suppressed.
- **`rf2-tgm1xu`** *(fixed — PR #3795, xray-resources cluster)* — resource
  accessors redacted live entries *before* projection, so default
  `list-resource-instances` / `get-resource-state` could lose status, owners,
  tags, and request ids without `include-runtime-db`, contradicting the EP-0003
  tool contract that redacted summaries still expose metadata. Accessors now
  project metadata summaries *before* egress redaction, keep status/tag/owner/
  request-id filters working without exposing the raw runtime-db, derive
  `:has-data?`, and report `:missing-key` for an incomplete scoped key.

Earlier slice-era build-gaps against settled rulings (the EP-0001-style
authority/error-catalogue/reset-hook reconciliations — `rf2-7r5mc2`,
`rf2-y7lcqy`, `rf2-4hboqi`, `rf2-gzsyw3`, `rf2-yuc8o0`, `rf2-i1w1pe`) were also
fixed and merged in-wave (PRs #3768–#3776) and carry no open EP-0003 erratum.

### Cross-cutting known-issues (post-final errata, not contract gaps)

These items are **open** but are **not EP-0003 contract gaps** — there are zero
open P1s against the resources contract, and graduation does not depend on them.
They are recorded here as cross-cutting follow-ups so the ledger is honest about
the surrounding work still in flight:

- **Resources hardening (P2/P3, in flight)** — additional defensive/edge-case
  hardening across the mutations, routing, events-core, scope-registry, and SSR
  lanes (all in `implementation/resources/`). These deepen the implementation's
  robustness; none reopens or changes the locked Spec 016 contract.
- **Spec-coherence follow-ups (`rf2-cnp8pr`, `rf2-hhn4b6`, `rf2-uqwbhr`,
  `rf2-ba5acq`, `rf2-c4focn`)** — docs/spec coherence reconciliation after the
  resources + mutations slices landed: aligning the runtime-subsystem graduation
  rows' canonical home (Spec 016 vs `Runtime-Subsystems.md`), refreshing the API
  reference's landed public-beta surface, aligning the resource trace family
  across runtime/panels/EP docs, wiring a runtime-subsystem conformance-drift
  test, and syncing the EP's duplicated graduation table to the now-fuller
  canonical specs. These are **docs/spec coherence, not runtime correctness** —
  Spec 016 governs and the contract is complete; the EP prose and cross-doc
  index simply need to catch up to the landed surface. A dedicated spec-coherence
  wave (sequenced after this graduation) actions them.

## Abstract

This enhancement proposes an optional `day8/re-frame2-resources` artifact for
server-state and external-resource management. The initial scope is HTTP-only:
`reg-resource` / `reg-mutation` over managed HTTP — the re-frame2 answer to the
REST/HTTP server-state tools (TanStack Query, RTK Query, SWR) and
`shipclojure/re-frame-query`. GraphQL is a deferred later phase; see
[Deferred: GraphQL (later phase)](#deferred-graphql-later-phase). The proposal is
deliberately benchmarked against TanStack Query, RTK Query, SWR, and
`shipclojure/re-frame-query` for the HTTP core, with Apollo Client and Relay held
as benchmarks for the deferred GraphQL phase. Those libraries set the baseline
for credible resource management in modern SPA development.

The goal is not to imitate those libraries. The goal is to implement
best-in-class resource capabilities for re-frame2, matching the mature
server-state semantics that users now expect, and exceeding the benchmark where
re-frame2's architecture gives it structural advantages: event causality,
frames, route metadata, managed HTTP, SSR, state machines, Xray, trace/epoch
evidence, privacy elision, and AI-readable contracts.

The public vocabulary should be:

```clojure
(rf/reg-resource ...)
(rf/reg-mutation ...)
```

A resource is a named, cached read of remote or external state. A mutation is a
causal write that may invalidate, patch, or refetch resources. The proposal uses
"resource" as the public term because it fits route data, HTTP reads, local
persistence, and (in the deferred GraphQL phase) GraphQL reads and other future
non-HTTP sources. "Query" remains a useful prior-art term, but it should not be
the re-frame2 API name.

The core rule is:

> Resources are remote server-state as runtime-managed read models over a
> frame work ledger.

That keeps the proposal inside the re-frame2 ethos:

- views are declarative reads;
- subscriptions and flows are materialized views over state;
- events are causal;
- routes are application state and should declare the remote data they need;
- state machines model lifecycles and workflows;
- tools and AI agents need enumerable, redacted, frame-aware metadata.

The initial implementation should be a read-resource MVP over HTTP:
registration, explicit ensure/refetch/invalidate events, passive subscriptions,
active owners, cache scopes, stale/fresh policy, dedupe, GC, route integration,
SSR preload/hydration, a built-in managed-HTTP read transport, and Xray/tool
visibility. It should do this over a frame-scoped work ledger so in-flight
resource attempts, cancellation, stale suppression, SSR waiting, and tool
inspection share one async substrate instead of each feature inventing its own
bookkeeping. It should also define timer policy for stale/GC behavior and
resolve background-refresh error semantics up front. GraphQL read transport is
explicitly out of the initial scope and is sketched in
[Deferred: GraphQL (later phase)](#deferred-graphql-later-phase) as a follow-on
once the HTTP core lands.

Two distinctions are important enough to be part of the first specification:

- owners keep resources alive; causes explain why work happened;
- params identify the remote read inside a cache scope; scopes prevent
  `/api/me`, tenant, locale, impersonation, and SSR cache leaks.

Mutations and focus/reconnect revalidation formed the first public-beta gate and
have landed in the HTTP scope. A read-resource MVP is useful for route and SSR
data, but the artifact should not be presented as complete resource management
until minimal mutation invalidation and active-stale revalidation are in place.
Optimistic updates, polling, infinite resources, generic transport extension
protocols, and normalized caches are important later slices, but should not make
the first artifact too wide.

## Motivation

Every substantial SPA repeats the same server-state machinery:

- identify remote data by endpoint and params;
- fetch it;
- show first-load state;
- keep old data visible during background refresh;
- deduplicate identical in-flight requests;
- suppress or abort stale replies;
- cache successful responses;
- decide when cached data is stale;
- refetch on navigation, invalidation, focus, reconnect, or manual demand;
- garbage-collect inactive cache entries;
- mutate remote data and invalidate affected reads;
- prefetch for routes and SSR;
- hydrate without double-fetching;
- keep auth-, tenant-, locale-, and user-scoped caches from bleeding into each
  other;
- keep paginated and filtered tables from blanking on every param change;
- show tools which page, event, machine, or route is waiting on which data.

re-frame2 already has strong primitives for pieces of this:

- `:rf.http/managed` owns transport mechanics such as retry, abort, timeout,
  schema decode, frame-aware replies, test stubs, and structured failure data.
  It is the single built-in transport for the initial HTTP-only scope.
- Pattern-RemoteData gives the canonical `:loading` vs `:fetching` distinction.
- Routing owns route metadata, `:on-match`, nav-tokens, route transition state,
  and SSR route entry.
- Frames isolate app instances, story frames, tests, and SSR requests.
- State machines model long-running workflows and semantic retry.
- Xray, traces, schemas, and pair tools depend on declarative surfaces.

The gap is policy and bookkeeping: resource identity, active ownership, stale
policy, dedupe, invalidation, route graphs, hydration, GC, and tool-readable
state. Apps can build those by hand, but that produces hidden conventions and
bugs in the exact places users notice: flickering loaders, stale screens,
duplicate requests, route waterfalls, cross-user cache leaks, invalidation
storms, and optimistic UI races.

Resource management is therefore no longer an optional convenience layer. For a
framework that wants to be best-in-class for SPAs, server state must be part of
the application model rather than a set of hand-rolled effects and subscriptions
per feature.

TanStack Query has made stale/fresh state, background revalidation, dedupe,
inactive cache retention, mutation invalidation, optimistic updates, hydration,
and router prefetching table stakes. RTK Query has shown how endpoint
definitions, subscription reference counts, cache tags, and mutation-driven
refetching fit into a centralized state model. SWR has made
stale-while-revalidate behavior feel natural. `re-frame-query` shows that
re-frame applications also need this class of abstraction. (Apollo and Relay
have shown what a normalized graph cache can provide when the transport model
warrants it; that is the benchmark for the deferred GraphQL phase, not the HTTP
core — see [Deferred: GraphQL (later phase)](#deferred-graphql-later-phase).)

re-frame2 should treat the HTTP-shaped tools — TanStack Query, RTK Query, SWR,
and `re-frame-query` — as the benchmark suite for the initial scope. If the
resource artifact does not reach that level of capability, it will merely be
another local convention. If it reaches that level while preserving re-frame2's
causal, inspectable, frame-aware architecture, it can be better than the
benchmark for re-frame2 applications.

## Goals

The resource artifact should:

- make server state an explicit, named, frame-local runtime process;
- match the expected baseline from TanStack Query, RTK Query, SWR, and
  `re-frame-query` where those HTTP server-state semantics apply (Apollo/Relay
  set the bar for the deferred GraphQL phase, not the initial scope);
- preserve re-frame2's event-causal model by keeping views passive and making
  route/event/machine causes explicit;
- use a frame work ledger as the shared substrate for in-flight work, ownership
  release, cancellation attempts, stale suppression, SSR waits, and Xray
  "what is still running?" answers;
- integrate resource ownership with frames, routes, SSR, managed HTTP,
  schemas, Xray, traces, privacy elision, and AI tooling (with a transport-neutral
  core that leaves room for the deferred GraphQL transport);
- make cache identity, scope, staleness, invalidation, liveness, and causality
  visible as data;
- support route and SSR data loading without component-tree request waterfalls;
- keep the MVP narrow enough to ship, while naming the public-beta and later
  slices required for a complete resource-management story.

## Non-Goals

The initial artifact should not:

- make subscription-driven fetching the default causal model;
- replace ordinary `reg-sub`, `reg-flow`, `:rf.http/managed`, or state machines;
- ship a GraphQL read or mutation transport in the initial scope — GraphQL is a
  deferred later phase (see
  [Deferred: GraphQL (later phase)](#deferred-graphql-later-phase));
- start with a normalized graph cache;
- start with a general transport plugin protocol;
- promise offline persistence, cross-tab broadcast, infinite resources, polling,
  or optimistic rollback in the first slice;
- hide server-state behavior inside React/Reagent component lifecycle;
- generalize the work ledger to every async primitive in the first slice; the
  HTTP resource artifact is the first concrete consumer, and later slices can
  extend the same shape to timers, streams, route loaders, spawned actors, and
  machine async work.

## Relationships

This EP builds on three other EPs whose contracts have already landed. It is
written against those final/implemented contracts, not against pending
dependencies.

- **Post-final amendments arrive via [EP-0016](EP-0016-resource-mutation-completion.md)
  (final).** Per EP-0009's amendment rule, substantive changes to this final
  contract go through a new EP. EP-0016 delivered mutation completion
  continuations, per-target scoped invalidation, and named scope resolvers;
  its changes landed in `spec/016-Resources.md` (which
  governs) and this EP remains the unamended design record of the original
  scope.

- **Deferred slices delivered by successor EPs.** The future slices this EP
  enumerates as out of the first scope (optimistic rollback, polling, infinite
  resources — see §Non-Goals) each landed in a dedicated
  successor EP, all now **final**, all amending `spec/016-Resources.md` (which
  governs):
  - **[EP-0019](EP-0019-optimistic-mutation-rollback.md)** — optimistic mutation
    apply / commit / rollback / reconcile (the deferred optimistic-update slice).
  - **[EP-0020](EP-0020-active-owner-polling.md)** — active-owner polling (the
    deferred polling slice).
  - **[EP-0021](EP-0021-infinite-resources.md)** — infinite / load-more resources
    (the deferred infinite-resources slice).
  This EP remains the unamended record of the original scope; each successor back-links here.

- **Graduation graded by [EP-0006](EP-0006-runtime-subsystem-contract.md)
  (final).** EP-0006's runtime-subsystem checklist *informed EP-0003's
  graduation* (by recommendation, not hard dependency): the resource trio
  (`:rf.runtime/resources`, `:rf.runtime/work-ledger`, `:rf.runtime/mutations`)
  graduated against that grading.

- **Builds on the app/runtime partition (landed).** Resource Queries stores its
  cache in the framework-owned runtime partition (`:rf.runtime/resources`) of the
  [App/Runtime Partition EP](EP-0001-frame-partitions.md). That partition has
  landed: framework durable state lives in runtime-db (`:rf.db/runtime`,
  addressed by `:rf.runtime/*` children), and a stray `:rf/runtime` root at the
  top of app-db is now a hard error (`:rf.error/legacy-runtime-root`) rather than
  an interim fallback. Resources rely on the final partition vocabulary.
- **Builds on explicit frame target resolution (final).** Resource queries are a
  large frame-aware feature; their public API hardens against the explicit
  frame-target contract of the [Explicit Frame Target Resolution
  EP](EP-0002-frame-target-resolution.md). The ambient `:rf/default` fallback is
  gone: every resource carries its explicit frame (the carried-frame invariant),
  and a frameless operation with no resolvable context fails closed. See
  [`spec/002-Frames.md` §Frame target resolution](../../spec/002-Frames.md#frame-target-resolution--the-carried-invariant)
  and §Write authority is by convention therein.
- **Builds on parametric subscription inputs (final).** Resource subscription
  view-models use the resolved input shape of the [Parametric Subscription Inputs
  EP](EP-0004-subscription-inputs.md): static `:<-` sugar plus input functions
  that return a vector of query vectors. These resource helpers compose over that
  final grammar rather than waiting on it. See
  [`spec/006-ReactiveSubstrate.md` §Subscription input producers](../../spec/006-ReactiveSubstrate.md#subscription-input-producers--app-db-reader-static-parametric-input-fn).

## Developer and AI Use Cases

The feature should help programmers and AI maintainers answer concrete
questions:

- What remote data does this route require?
- Which resource instances are active right now, and who owns them?
- Is this screen blank because it is first-loading, background-refreshing, or
  blocked on an error?
- If this mutation succeeds, which cached reads should become stale?
- Did the stale response win a race, or was it suppressed?
- Why did this request happen: route entry, manual event, invalidation, focus,
  reconnect, polling, or SSR preload?
- Is this cache entry scoped to the current user, tenant, locale, impersonation,
  or SSR request?
- Which remote values are safe for tools and AIs to inspect?
- Can this app be server-rendered without leaking another user's cache?

Features that do not answer questions like these should be treated as later
research rather than MVP requirements.

## Benchmark Standard And Prior Art

This proposal uses prior art as a benchmark, not as decoration. The resource
artifact should be designed with the assumption that users will compare it to
the best existing server-state tools. The first stable version does not need to
match every advanced plugin or years of ecosystem hardening, but the
architecture must be capable of reaching and then exceeding that bar.

The benchmark dimensions are:

- identity: stable, serializable resource keys and params;
- freshness: stale/fresh policy, active-stale revalidation, and background
  refresh;
- liveness: active owners, inactive retention, and garbage collection;
- concurrency: in-flight dedupe, cancellation/suppression, generation checks,
  and race safety;
- mutation: invalidation, patch/populate APIs, and optimistic-update foundation;
- routing and SSR: prefetch, blocking data, hydration, and no double-fetch;
- pagination: previous data, page identity, and later infinite resources;
- cache shape: document/resource cache first, graph normalization later;
- tooling: inspectable cache entries, causes, owners, timings, and privacy
  state;
- extensibility: a transport-neutral core with a v1 built-in for managed HTTP,
  plus a path to later transports — GraphQL first (see
  [Deferred: GraphQL (later phase)](#deferred-graphql-later-phase)) — without
  weakening the core semantics.

### TanStack Query

TanStack Query is the primary external benchmark for general server-state cache
semantics. The parts re-frame2 should match are:

- structured, serializable identity keys;
- deterministic key hashing where map/object key order does not change
  identity;
- stale/fresh state and explicit stale windows;
- inactive-entry garbage collection;
- in-flight request dedupe;
- retries on failure;
- invalidation that marks matching entries stale and refetches active entries;
- optimistic update snapshots and rollback;
- SSR prefetch/dehydrate/hydrate;
- router-level prefetching to avoid component-tree request waterfalls.

The part re-frame2 should not copy is the React hook as the causal boundary.
In re-frame2, views read and events cause.

The opportunity to exceed TanStack is not raw maturity. It is architectural
integration: resources can be frame-local, route-declared, SSR-aware,
trace-explained, Xray-visible, privacy-filtered, and machine-escalatable in a
way that a view-hook library cannot provide as its default model.

### RTK Query

RTK Query shows a Redux-oriented version of the same design:

- endpoint definitions identify reads and writes;
- endpoint params form cache keys;
- active subscribers share cached data;
- unused entries are retained and then removed;
- query endpoints provide tags;
- mutation endpoints invalidate tags.

The tag model is the useful lesson. Tags are explicit, simple, and proven. They
are also fallible, so re-frame2 should later add Xray or contract-graph lint
for broad, missing, or ineffective tags.

The opportunity to exceed RTK Query is stronger causal evidence and clearer
runtime ownership. RTK Query stores server-state cache in Redux state; re-frame2
resources should live in the runtime partition, with public reads and explicit
resource events rather than app handlers editing cache internals.

### SWR

SWR's useful lesson is stale-while-revalidate UX: keep displaying previous data
while refreshing in the background. In re-frame2 terms, that is exactly the
Pattern-RemoteData `:fetching` state.

The hook-oriented fetch lifecycle is less useful for re-frame2. Subscription
lifecycle should not be the default cause of network work.

### Apollo and Relay (deferred-GraphQL benchmark)

Apollo and Relay show the normalized graph-cache path. That is powerful for
GraphQL and entity-heavy applications, but it is too much for the first resource
artifact — and GraphQL itself is out of the initial HTTP-only scope.

The HTTP core starts with a document/resource cache keyed by resource identity
and canonical params, benchmarked against TanStack Query and RTK Query rather
than Apollo or Relay. Apollo and Relay are the benchmark for the deferred GraphQL
phase: when GraphQL lands as a follow-on transport it should first deliver
operation-level resource caching (route-owned reads, SSR hydration, stale/fresh
policy, dedupe, invalidation, and Xray visibility), and only later — if the app's
data model justifies it — the normalized graph cache, entity identity policy,
fragment store, and automatic graph-derived invalidation associated with a full
GraphQL client. The full GraphQL rationale and shape is consolidated in
[Deferred: GraphQL (later phase)](#deferred-graphql-later-phase).

### shipclojure/re-frame-query

`shipclojure/re-frame-query` is important prior art inside the re-frame
ecosystem. It proves demand for this feature class and already includes many of
the right ideas:

- declarative queries and mutations;
- automatic success/failure callback wiring;
- tag invalidation with active-query refetch;
- per-query cache-time GC;
- polling;
- conditional fetching;
- prefetching;
- loading vs background-refetch status;
- transport-agnostic effects;
- infinite queries;
- mutation lifecycle hooks;
- passive route-driven use via `ensure-query`, `mark-active`,
  `mark-inactive`, and `query-state`;
- an ergonomic subscription-driven mode for simpler apps.

The route-driven option is the important convergence point: fetch from a router
or event, render through a passive subscription, and keep re-frame subscriptions
pure.

The re-frame2 artifact should learn from `re-frame-query`, but not clone it.
re-frame2 needs tighter integration with frames, route metadata, SSR,
runtime/app-db partitioning, managed HTTP, Xray, privacy egress, and AI-readable
metadata (with GraphQL integration following in the deferred phase). It should
also avoid making subscription-driven fetching the default because that weakens
event causality and makes route behavior harder to inspect.

## Design Rationale

### Passive Read, Explicit Causal Fetch

Views should read resource state:

```clojure
(rf/subscribe [:rf.resource/state
               {:resource :article/by-slug
                :params   {:slug "welcome"}}])
```

Views should not be the main cause of fetching. Route entry, events, machines,
or explicit resource events should cause resource work:

```clojure
[:rf.resource/ensure
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :params   {:slug "welcome"}
  :owner    [:route :route/article nav-token]
  :cause    [:route-entry :route/article nav-token]}]
```

A future ergonomic `:rf.resource/live` subscription can be reconsidered after
the causal route/event model has shipped. It should not be in the v1 MVP.

### Runtime-Owned, Not User-Owned

Resource state is runtime-managed process state. App code can read it through
public subscriptions and accessors, and influence it through events, but should
not hand-edit the resource runtime slice.

The target frame-state shape is:

```clojure
{:rf.db/app
 <user-app-db>

 :rf.db/runtime
 {:rf.runtime/resources
  {:entries
   {[[:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
     :article/by-slug
     {:slug "welcome"}]
    {:resource/id    :article/by-slug
     :scope          [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
     :params         {:slug "welcome"}
     :status         :loaded
     :data           {:title "Welcome"}
     :error          nil
     :refresh-error  nil
     :loaded-at      1780752000000
     :stale-at       1780752060000
     :gc-at          nil
     :generation     3
     :request-id     [:rf.resource
                      [[:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
                       :article/by-slug
                       {:slug "welcome"}]
                      3]
     :tags           #{[:article "welcome"]}
     :active-owners  #{[:route :route/article nav-token]}}}

   :tag-index
   {[:article "welcome"]
    #{[[:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
       :article/by-slug
       {:slug "welcome"}]}}

   :owner-index
   {[:route :route/article nav-token]
    #{[[:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
       :article/by-slug
       {:slug "welcome"}]}}}}}
```

In a full frame-state projection, the resource path is
`[:rf.db/runtime :rf.runtime/resources]`. Inside runtime-db itself, framework
code reads and writes `[:rf.runtime/resources]`.

There is no interim app-db location. Post-[EP-0001](EP-0001-frame-partitions.md)
a stray `:rf/runtime` root at the top of app-db is a hard error
(`:rf.error/legacy-runtime-root`, rejected by `re-frame.events`), not a fallback.
Resource cache lives only at `:rf.runtime/resources` inside the `:rf.db/runtime`
partition, so ordinary `:db` event handlers cannot accidentally wipe resource
state.

### Write Authority

`:rf.runtime/resources` and `:rf.runtime/work-ledger` are framework-owned
runtime-db children, so resource writes must mint **framework-write authority**;
ordinary app authority is not enough. The resource event handlers that return a
`:rf.db/runtime` effect — `:rf.resource/ensure`, `:rf.resource/refetch`,
`:rf.resource/invalidate-tags`, `:rf.resource/release-owner`,
`:rf.resource/clear-scope`, `:rf.resource/remove`, and the internal replies
`:rf.resource.internal/succeeded` / `:rf.resource.internal/failed` /
`:rf.resource.internal/aborted` / `:rf.resource.internal/gc-fired` /
`:rf.resource.internal/stale-suppressed` — are all framework writers of the
runtime partition.

The resources artifact therefore mints write authority through the
**generalized event-handler authority mechanism shipped under rf2-3939ig**: every
resource `reg-event-fx` registration site stamps the reserved registration-meta
key `:rf/framework-authority? true` (per
[`spec/002-Frames.md` §Minting framework-write authority](../../spec/002-Frames.md#minting-framework-write-authority)
and [`spec/Conventions.md` §Reserved registration metadata](../../spec/Conventions.md#reserved-registration-metadata-framework-owned)).
The runtime reads that stamp when assembling the event context, so a returned
`:rf.db/runtime` effect from a resource handler is in-bounds. Resource handlers
**never** write runtime-db through ordinary app authority. Without this stamp,
resources would be the *second* framework subsystem after routing to trip the
`:rf.warning/app-handler-runtime-effect` ownership diagnostic on every fetch in
dev — the exact class of gap the generalized mechanism (and the
runtime-subsystem contract that names it as clause 2) exists to close.

`:rf/framework-authority?` is a diagnostic-governing convention, not a capability
gate: the effect applies either way, and the flag governs only whether the
ownership diagnostic fires (Spec 002 Mike ruling #4). Stale/GC and host-handle
bookkeeping that the resource runtime performs **outside** the event-handler path
(privileged frame-state mutators) follows the privileged-helper authority path
instead, exactly as elision and SSR's non-event writes do.

### Runtime-Subsystem Graduation

The HTTP resources artifact adds framework-owned runtime-db children, so each
child must **graduate against the runtime-subsystem contract**: the five-clause
checklist every framework-owned durable-state subtree inherits, defined
normatively in
[`spec/Runtime-Subsystems.md`](../../spec/Runtime-Subsystems.md). The contract
names five clauses every conformant subsystem answers: (1) **subtree**,
(2) **write authority**, (3) **read API**, (4) **projection / elision**, and
(5) **teardown**. A subtree that answers fewer is ad-hoc runtime state, outside
the contract that tools, SSR projection, and AI-audit surfaces assume.

The shipped resource trio is:

- `:rf.runtime/resources` — the resource cache;
- `:rf.runtime/work-ledger` — the neutral in-flight-work ledger used by resource
  and mutation work in the HTTP scope, with non-resource future writers still
  required to settle their own authority path when they join;
- `:rf.runtime/mutations` — mutation instance state, keyed by mutation instance
  id so concurrent submissions do not clobber one another.

The live grading table now belongs in
[`spec/Runtime-Subsystems.md` §Grading table](../../spec/Runtime-Subsystems.md#grading-table--the-shipped-subsystems),
with the resource-origin contract in
[`spec/016-Resources.md` §Runtime-subsystem graduation](../../spec/016-Resources.md#runtime-subsystem-graduation).
This EP records why the resource trio needed to graduate; it intentionally does
not duplicate the table because the spec rows are the normative surface. The
important EP-level decisions are: resource and mutation handlers mint framework
write authority, host handles/timers remain transient side tables, indexes are
recomputable projections, SSR/hydration uses explicit allowlists, and work ids
plus monotonic generations are the stale-reply and restore boundary.

### Frame Work Ledger Is The Async Substrate

Resource entries are cached read-model facts. In-flight attempts are work facts.
They should be linked, but not collapsed into one map.

EP-0003 should introduce the first concrete slice of a frame work ledger for
resource work. The ledger is a frame-local runtime substrate for async work that
may outlive the event that started it. In the initial artifact only resource
work and its managed-HTTP lowering need to participate. The shape must still be
neutral enough that later slices can extend it to route loaders, timers,
streams, spawned actors, and machine async work without rewriting resource
semantics.

A resource entry may point at its current work id:

```clojure
{:resource/id   :article/by-slug
 :resource/key  [[:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
                 :article/by-slug
                 {:slug "welcome"}]
 :status        :fetching
 :data          {:title "Welcome"}
 :generation    4
 :current-work  [:rf.work/resource
                 [[:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
                  :article/by-slug
                  {:slug "welcome"}]
                 4]}
```

The ledger records the serializable attempt:

```clojure
{:work/id        [:rf.work/resource
                  [[:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
                   :article/by-slug
                   {:slug "welcome"}]
                  4]
 :work/kind      :resource
 :work/frame     frame-id
 :resource/key   [[:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
                  :article/by-slug
                  {:slug "welcome"}]
 :generation     4
 :transport      :rf.http/managed
 :status         :running
 :owners         #{[:route :route/article nav-token]}
 :causes         [[:route-entry :route/article nav-token]]
 :cancellable?   true
 :started-at     1780752000100
 :deadline-at    1780752005100}
```

Host handles remain outside durable frame-state, keyed by frame id and work id:

```clojure
[frame-id work-id] -> {:abort-controller ...
                       :timeout-handle   ...
                       :promise          ...}
```

The durable/resource split is:

- `:rf.runtime/resources` stores cache entries, tags, resource ownership
  indexes, timestamps, data, errors, and the current work id for each entry;
- `:rf.runtime/work-ledger` stores serializable work records, status, owners,
  causes, attempts, deadlines, and outcomes;
- host side tables store non-serializable cancellation and timer handles keyed
  by frame id and work id.

The correctness rule is that cancellation is opportunistic, while stale
suppression is mandatory. When an owner exits, scope is cleared, route is
superseded, or a newer generation starts, the runtime may abort the host handle
if it exists and can be cancelled. If the host cannot cancel it, the ledger and
resource generation checks must still suppress the late reply. A stale reply
must never be able to mutate a newer resource entry.

SSR and tools should observe the ledger projection, not host handles. SSR
*waits on* blocking ledger records server-side, but the hydration payload
serializes only the allowed **`:rf.runtime/resources` cache projection** —
work-ledger rows do **not** ride hydration (in-flight work belongs to the server
timeline that owns its host handles; the client has nothing to reconcile). Epoch
restore (same-frame, same host) is the boundary that carries non-terminal
work-ledger rows so the reconciler can dangle them. Xray answers "what is still
running?" from ledger records joined to resource entries and trace causes. (The
canonical statement is [Spec 016 §Frame work ledger](../../spec/016-Resources.md#frame-work-ledger)
and the work-ledger graduation row in
[Spec 016 §Runtime-subsystem graduation](../../spec/016-Resources.md#runtime-subsystem-graduation).)

### Resource Identity Is Data

A resource instance is identified by a cache scope, a resource id, and canonical
params:

```clojure
[cache-scope resource-id canonical-params]
```

For example:

```clojure
[[:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
 :article/by-slug
 {:slug "welcome"}]
```

Rules:

- cache scope must be serializable EDN data, and a scope **map** is
  canonicalized under the **same canonicalization rule as params maps** (key
  order does not affect identity; nested maps recurse) so two spellings of the
  same scope hash to one cache key — a `[:rf.scope/session {:tenant-id "acme"
  :user-id "u-42"}]` and a `[:rf.scope/session {:user-id "u-42" :tenant-id
  "acme"}]` are the same scope, never two leaking caches;
- there is **no silent default scope**: every resource declares an explicit
  scope policy at registration (see [Scope Resolution](#scope-resolution)). A
  resource that wants the global scope says so — `:scope :rf.scope/global` — as a
  deliberate, auditable claim; a resource with no declared policy is a loud
  registration error, never a silent `[:rf.scope/global]` read;
- user-, tenant-, locale-, permission-, impersonation-, and session-dependent
  reads must use an explicit scope or put those values in params;
- logout, account switch, tenant switch, and impersonation changes must have a
  causal way to clear or replace the affected scope;
- params must conform to `:params-schema`;
- params must be serializable EDN data;
- maps are canonicalized so key order does not affect identity;
- host values such as functions, promises, dates, DOM nodes, AbortControllers,
  and JS objects are rejected;
- nil vs missing must be schema-defined, not accidental;
- every variable that affects remote identity must be represented in params;
- resource request ids must include the full scoped resource key, or an
  equivalent scope-bearing value, so same params in different user/tenant scopes
  cannot supersede each other;
- avoid a separate `:cache-key` escape hatch in v1 unless it is validated,
  visible in tools, and tested heavily.

### Scope Resolution

Scope is the cache's tenant/user/permission/locale/impersonation/SSR leak
boundary, and a resolved scope can carry PII (user ids, tenant ids,
impersonation markers). A boundary that critical must **fail closed**: it must
never silently default to "shared." Every resource therefore declares an
explicit **scope policy** at registration, and scope resolution is explicit and
deterministic.

#### Every resource declares a scope policy (fail-closed)

`:scope` at `reg-resource` is **required**. It declares a *policy*, not
necessarily a concrete value, drawn from a closed reserved-keyword enum:

- `:scope :rf.scope/global` — the resource is **explicitly global**. This is a
  *claim*: "the same params produce the same data for every user, tenant,
  permission-set, locale, and impersonation state." It is an auditable assertion,
  not a convenience hideaway.
- `:scope <resolver>` — derive the scope deterministically. The resolver
  materializes as visible EDN in the resource key (see [Resource Identity Is
  Data](#resource-identity-is-data)). A resolver may be a route-resource resolver
  `(fn [route ctx] …)`, a resource-spec resolver, or — for a sub-side resolver —
  a pure data value / fn-of-nothing (see [Subscription-side scope
  resolution](#subscription-side-scope-resolution) below).
- `:scope :rf.scope/from-caller` — the scope is **required from the use site**:
  every `:rf.resource/ensure`/`:rf.resource/refetch`/`:rf.resource/state` (and
  sibling) call must supply `:scope` on the payload, or a route-resource resolver
  must supply it. Enforcement lands where the scope is actually known.
- **No declared policy** — a loud **registration error**
  (`:rf.error/resource-missing-scope-policy`). "I forgot this read is
  user-scoped" is unrepresentable at registration rather than an Xray heuristic
  about `/me`-looking URLs.

This is the per-resource scope policy ruled fail-closed for EP-0003
(`rf2-6rrz53`). It composes with the cache-scope-shape rule (scope is explicit
canonical EDN, the first element of the key): scope is **explicit-in-key** *and*
its presence is **mandatory-by-policy**.

#### Resolution precedence (for events; no global fallthrough)

For a resource **event** (`:rf.resource/ensure`, `:rf.resource/refetch`, …) the
runtime resolves the concrete scope in this order:

1. `:scope` supplied on the resource event payload;
2. the route-resource `:scope` resolver (a `(route, ctx)` function);
3. the resource-spec `:scope` resolver.

There is **no tier-4 `[:rf.scope/global]` fallthrough.** If none of the above
yields a scope, resolution fails closed:

- a resource whose policy is `:rf.scope/global` resolves to `[:rf.scope/global]`
  only because that is its **declared, explicit** policy — not because the
  precedence ran out of options;
- a `:rf.scope/from-caller` resource reached with no payload `:scope` and no
  resolver is a loud **use-time error**
  (`:rf.error/resource-scope-required-from-caller`), not a silent global read.

#### Subscription-side scope resolution

Subscriptions are **pure** — they cannot run a `(route, ctx)` resolver, because a
sub has no access to the routing match or the event context. This is exactly the
seam where a silent leak used to hide: a route ensures a resource under
`[:rf.scope/session {…}]`, but a view's `[:rf.resource/state {…}]` that omits
`:scope` would resolve to a *different* scope than the one the data was loaded
under and read `:idle` forever — a permanent skeleton with no error anywhere.
That is the silent-wrong-target bug family [EP-0002](EP-0002-frame-target-resolution.md)
exists to kill, and resource subscriptions must close it the same way:

A subscription resolves its scope from, in order:

1. `:scope` supplied on the **subscription payload**;
2. the resource spec's `:scope` **only if** that policy is one a pure sub can
   evaluate without an event context — i.e. an explicit `:rf.scope/global` claim,
   or a resolver **declared as pure data / fn-of-nothing**. A resource whose
   scope policy is a `(route, ctx)` resolver or `:rf.scope/from-caller` **cannot**
   be resolved sub-side from the spec alone.

A subscription that **cannot** resolve a scope is a **loud, structured error**
(`:rf.error/resource-sub-unresolved-scope`) carrying the resource id and the
unresolvable policy — **never** a silent `[:rf.scope/global]` read and never a
silent `:idle`. The fix the error points at is explicit: pass `:scope` on the
subscription payload (the same scope the owning route/event ensured under), or
re-declare the resource with a sub-resolvable scope policy. This is the read-side
counterpart of the write-side fail-closed gate: a read that cannot name its
principal does not fall through to the shared cache.

#### Xray scope diagnostics are defense-in-depth, not the boundary

Because every resource now carries an explicit policy, the old `/me` /
`/current-user` heuristic is no longer the boundary — it is downgraded to
**defense-in-depth**. Xray should warn about *suspicious explicit-global*
resources (an `:rf.scope/global` claim whose request looks session-dependent —
`/me`, `/current-user`, tenant-local URLs, or auth-derived params), not
"compensate for a missing scope" (a missing scope is now a loud error, not a
heuristic). The **standing audit surface** is structural: sub-topology / Xray
**enumerate every `:rf.scope/global` resource** as the security-review list — the
explicit replacement for the heuristic. See [Xray and AI
Tooling](#xray-and-ai-tooling).

`clear-scope` is a causal operation. It should:

- remove or mark unusable every entry in that scope;
- release owners in that scope;
- abort in-flight requests that have no remaining owner outside that scope;
- suppress late replies by scope + generation checks;
- emit trace rows explaining which entries were removed, aborted, or left alone.

Auth token refresh does not necessarily require clearing scope if the user,
tenant, permissions, and impersonation state are unchanged. Login, logout,
account switch, tenant switch, permission-set change, locale switch when it
affects wire data, and impersonation enter/exit do require either a new scope or
an explicit clear/replace operation.

Invalidation is scoped by default. A cross-scope invalidation must opt in
explicitly and be visible in Xray because it can refetch or stale data for
multiple users, tenants, story frames, or SSR requests.

### Active Owners, Not Component Observers

TanStack Query and RTK Query talk about active observers or subscriptions.
re-frame2 should talk about active owners.

Owners are liveness leases. They answer:

- should invalidation refetch now, or only mark stale?
- should polling continue?
- may the entry be garbage-collected?
- what should route leave release?
- which workflows are intentionally keeping this resource active?

Examples:

```clojure
[:route :route/article nav-token]
[:machine :checkout/flow machine-instance-id]
[:ssr request-id nav-token]
```

Route owners must include the navigation token. `[:route :route/article]` is
not precise enough because the same route can be entered multiple times with
different params, pending work, or SSR request frames.

Do not use ordinary event ids as durable owners unless the event creates a
releaseable lease. A manual refresh, a button click, or a one-shot dashboard
open should usually be a cause, not an owner.

#### Release authority is per owner kind

Every owner kind names *who is authoritative for releasing it* so a lease cannot
silently outlive the thing it represents (an orphaned owner pins an entry alive
and keeps it refetching on focus/reconnect — a slow leak). The release authority
for each kind is:

- **Route owners** (`[:route route-id nav-token]`) — released by **routing on
  nav-token supersession**: route leave or a superseded navigation releases the
  owner by its token, even when in-flight abort is unavailable (already specified
  in [Route Integration](#route-integration); restated here so the table is
  complete).
- **Machine owners** (`[:machine machine-id instance-id]`) — released on
  **actor destroy**: when the owning machine instance is stopped/destroyed
  ([Spec 005](../../spec/005-StateMachines.md)), its resource leases are released.
  Machine liveness is a pure function of frame-state, so a destroyed instance can
  hold no live lease.
- **SSR owners** (`[:ssr request-id nav-token]`) — released on **request
  teardown**: an SSR owner belongs to one server render and is released when that
  request's frame is torn down; it never survives as a live client-side lease (it
  is reconciled to an orphan on hydration/restore — see [§4 Owners revive or
  orphan by kind](#4-owners-revive-or-orphan-by-kind)).
- **Bare app / lease owners** (`[:lease …]`, `[:dashboard/opened …]`, and other
  app-minted kinds) — the **app is authoritative**: an event that mints such a
  lease must have a matching `:rf.resource/release-owner` release path. The
  framework does not auto-release app-minted leases. To catch the forgotten case,
  Xray surfaces an **orphaned-owner lint**: an `[:lease …]`/app-kind owner whose
  minting event has no observed release path (or that pins an entry long past its
  expected lifetime) is flagged as a candidate leak.

### Causes Explain Why Work Happened

Causes are trace and diagnostic metadata. They answer "why did this happen?"
without changing liveness, GC, polling, or refetch decisions.

Examples:

```clojure
[:route-entry :route/article nav-token]
[:manual :article/refresh]
[:invalidate {:tags #{[:article "welcome"]}}]
:focus
:reconnect
:ssr-preload
:hydration
```

Ensure/refetch events should accept both `:owner` and `:cause`. `:owner`
changes the active-owner set. `:cause` is recorded in trace/resource history.
Trace dispatch ids, event trace ids, and Xray focus state belong in cause/trace
metadata, not in durable owners.

Xray must not become an owner by observing. Opening a devtool must not pin a
resource, refetch it, extend GC, or alter polling. A future "pin this resource"
debug action would be an explicit tool mutation with its own trace, not normal
inspection.

### Sub-Resources Are Ordinary Resources

Do not introduce a separate `sub-resource` primitive in v1.

A sub-resource is usually a naming, ownership, and invalidation relationship,
not a different lifecycle. It still needs the same identity, stale/fresh state,
owners, dedupe, SSR behavior, and GC as any other resource.

Model it as an ordinary resource whose params include the parent identity:

```clojure
(rf/reg-resource
  :article/comments
  {:params-schema
   [:map [:slug :string]]

   :request
   (fn [{:keys [slug]} _]
     {:request {:method :get
                :url    (str "/api/articles/" slug "/comments")}
      :decode [:vector :app/comment]})

   :tags
   (fn [{:keys [slug]} _comments]
     #{[:article slug]
       [:comments slug]})})
```

Route metadata can then own both the parent resource and the child collection:

```clojure
:resources
[{:resource  :article/by-slug
  :params    (fn [route] {:slug (get-in route [:params :slug])})
  :blocking? true}

 {:resource  :article/comments
  :params    (fn [route] {:slug (get-in route [:params :slug])})
  :blocking? false}]
```

If Xray later needs to draw this relationship explicitly, add optional metadata
such as `:parent-resource` or `:resource/parent` for tooling. That metadata
should not change cache identity or lifecycle semantics.

### Lifecycle Is an FSM

Every resource instance has a lifecycle:

```text
:idle
  ensure/refetch without data -> :loading

:loading
  success -> :loaded
  failure -> :error

:loaded
  stale/refetch -> :fetching
  invalidate inactive -> :loaded with stale timestamps/invalidated-at

:fetching
  success -> :loaded
  failure -> :loaded with :refresh-error, preserving last-known-good data
  superseded reply -> previous stable state

:error
  refetch -> :loading
```

The default implementation should be a compact transition function, not a
spawned machine per resource entry. Semantic retry, multi-step negotiation,
streaming, and workflow-coupled reads can graduate to explicit machines.

The resource FSM describes cache-entry status. The work ledger describes the
attempt lifecycle that may currently be moving that cache entry: queued,
running, abort-requested, completed, failed, timed out, suppressed, or cancelled.
Do not overload resource `:status` with host-handle state.

Transport retry belongs to the transport adapter — managed HTTP in the initial
scope (and any later transport such as the deferred GraphQL one). Semantic retry
belongs to machines.

## Specification

Ship an optional artifact:

```clojure
day8/re-frame2-resources
```

Requiring `re-frame.resources` wires the artifact into the core facade, feature
registry, and tool metadata. Routing and SSR integration should be late-bound so
applications that do not load those optional artifacts do not carry their code.

### MVP Scope

V1 should include:

- `reg-resource`;
- `clear-resource`;
- passive resource subscriptions;
- explicit ensure/refetch/invalidate/remove events;
- active owners;
- non-liveness causes/reasons for traceability;
- explicit cache scopes and scope clearing;
- canonical params;
- stale/fresh policy;
- a resource-owned slice of the frame work ledger for in-flight attempts,
  cancellation attempts, blocking waits, and tool summaries;
- in-flight dedupe;
- stale reply suppression;
- inactive-entry GC;
- route `:resources`;
- blocking and non-blocking route resources;
- SSR preload and hydration for route resources;
- managed HTTP as the single built-in read transport;
- exact tag invalidation;
- conditional route resources and clear params-failure behavior;
- timer policy for stale/fresh and inactive GC;
- explicit `:refresh-error` semantics for failed background refresh;
- Xray/tool summaries with redaction;
- conformance tests.

First public-beta gate (landed):

- `reg-mutation`;
- `clear-mutation`;
- focus and reconnect revalidation for active stale resources;
- mutation invalidation integration.

Later slices:

- GraphQL read transport (`:rf.graphql/query`) and GraphQL mutations — the first
  follow-on phase once the HTTP core lands (see
  [Deferred: GraphQL (later phase)](#deferred-graphql-later-phase));
- optimistic rollback;
- generic transport extension protocol;
- polling and interval revalidation;
- infinite resources;
- normalized entity caches;
- automatic graph-derived invalidation;
- subscription-driven fetching;
- offline persistence and cross-tab broadcast.

The mutation/revalidation gate stayed out of the read-resource MVP to reduce
design risk; it is now part of the landed HTTP public-beta surface.

### Public API

Registration:

```clojure
(rf/reg-resource resource-id resource-spec)
(rf/clear-resource resource-id)

(rf/reg-mutation mutation-id mutation-spec)
(rf/clear-mutation mutation-id)
```

`clear-resource` is a registration-lifecycle operation, not the normal cache
invalidation API. Application code should use `:rf.resource/invalidate-tags`,
`:rf.resource/remove`, or `:rf.resource/clear-scope` for data lifecycle work.
When a resource registration is cleared, the implementation must also dispose
resource-runtime state for that resource id in each affected frame: release owner
indexes, cancel timers/host handles, abort in-flight requests where possible,
suppress late replies by generation, remove tag-index rows, and emit a trace.
`clear-mutation` follows the same distinction: it clears the mutation
registration and its runtime instances, not a form-level error reset.

Events use map payloads, not positional argument vectors:

```clojure
[:rf.resource/ensure
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :params   {:slug "welcome"}
  :owner    [:route :route/article nav-token]
  :cause    [:route-entry :route/article nav-token]}]

[:rf.resource/refetch
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :params   {:slug "welcome"}
  :cause    [:manual :article/refresh]}]

[:rf.resource/invalidate-tags
 {:scope [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :tags  #{[:article "welcome"]}
  :cause [:mutation :article/save mutation-id]}]

[:rf.resource/release-owner
 {:owner [:route :route/article nav-token]}]

[:rf.resource/clear-scope
 {:scope [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :cause :logout}]

[:rf.resource/remove
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :params   {:slug "welcome"}}]

;; First public-beta gate (landed):
[:rf.mutation/execute
 {:mutation :article/save
  :params   article}]
```

Subscriptions are passive:

```clojure
[:rf.resource/state
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :params   {:slug "welcome"}}]

[:rf.resource/data
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :params   {:slug "welcome"}}]

[:rf.resource/status
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :params   {:slug "welcome"}}]

[:rf.resource/loading?
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :params   {:slug "welcome"}}]

[:rf.resource/fetching?
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :params   {:slug "welcome"}}]

[:rf.resource/stale?
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :params   {:slug "welcome"}}]

[:rf.resource/error
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :params   {:slug "welcome"}}]

[:rf.resource/refresh-error
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :params   {:slug "welcome"}}]
```

Introspection:

```clojure
(rf/resource-meta :article/by-slug)
(rf/resource-state
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :params   {:slug "welcome"}
  :frame    :app/main})
(rf/resources {:frame :app/main})
```

`:frame` is an explicit, app-registered frame id (`:app/main` is illustrative).
Per [EP-0002](EP-0002-frame-target-resolution.md) there is no ambient
`:rf/default` fallback the runtime creates on your behalf: the frame target is
carried explicitly, and a frameless introspection call with no resolvable
context fails closed rather than silently inspecting the wrong frame.

### Resource Registration

Example:

```clojure
(rf/reg-resource
  :article/by-slug
  {:doc "Article detail by slug."

   :params-schema
   [:map [:slug :string]]

   :data-schema
   :app/article

   :request
   (fn [{:keys [slug]} _ctx]
     {:request {:method :get
                :url    (str "/api/articles/" slug)}
      :decode :app/article})

   :scope
   :rf.scope/global

   :transport
   :rf.http/managed

   :stale-after-ms
   60000

   :gc-after-ms
   300000

   :tags
   (fn [{:keys [slug]} _data]
     #{[:article slug]})

   :sensitive?
   false})
```

Required keys:

- `:params-schema` validates and canonicalizes params;
- `:scope` declares the resource's **scope policy** — one of
  `:rf.scope/global`, a resolver, or `:rf.scope/from-caller` (see [Scope
  Resolution](#scope-resolution)). It is **required**: a `reg-resource` with no
  scope policy is a loud registration error
  (`:rf.error/resource-missing-scope-policy`), so a user-scoped read can never be
  silently registered as global. Stating scope intent once, at the registration
  site, is the loud-failure ethos applied to the cache's leak boundary;
- for `:transport :rf.http/managed` (the only initial-scope transport),
  `:request` returns a Spec 014 managed-HTTP args map, including the nested
  `:request` child and top-level keys such as `:decode`, `:accept`, `:retry`,
  and sensitivity metadata;
- `:data-schema` validates successful data when transport decode supports it.

The deferred GraphQL phase adds a `:transport :rf.graphql/query` whose operation
metadata is supplied through GraphQL transport keys; see
[Deferred: GraphQL (later phase)](#deferred-graphql-later-phase).

Optional v1 keys:

- `:doc`;
- `:transport`, which in the initial scope is `:rf.http/managed` (the only
  built-in; `:rf.graphql/query` is added in the deferred GraphQL phase);
- `:stale-after-ms`;
- `:gc-after-ms`;
- `:tags`;
- `:sensitive?` / `:large?` / schema-based classification.

(`:scope` is **not** optional — it is a required key above. A resource that is
genuinely process-independent declares `:scope :rf.scope/global` explicitly;
there is no implicit default.)

Deferred keys:

- `:poll-ms`;
- `:revalidate`;
- arbitrary `:placeholder`;
- `:transport` extension protocols;
- `:cache-key`;
- `:infinite`;
- mutation-only keys such as `:invalidates`, `:optimistic`, and `:rollback`.

`:rf.http/managed` is the existing Spec 014 effect surface and the single
built-in transport for the initial scope. (The deferred GraphQL phase adds a
`:rf.graphql/query` transport whose implementation may lower to managed HTTP or a
configured GraphQL client, while the resource lifecycle remains the owner of
identity, staleness, dedupe, invalidation, SSR hydration, and tool metadata; see
[Deferred: GraphQL (later phase)](#deferred-graphql-later-phase).)

For HTTP resources, the runtime owns reply addressing and request correlation.
The managed-HTTP args returned by `:request` must not supply `:request-id`,
`:on-success`, or `:on-failure`; resource lowering supplies those from the
scoped resource key and current generation. Implementations should reject those
reserved keys at registration or dispatch rather than silently accepting a
handler that can bypass stale-suppression checks.

Do not add a TanStack-style `:select` key in v1. In re-frame2, projections are
ordinary subscriptions layered over `[:rf.resource/data ...]`. That is not a
missing feature; it is a structural advantage of the subscription graph.

### Status Semantics

Resource state should use Pattern-RemoteData semantics, but durable entries
should store facts rather than derived booleans:

```clojure
{:status     :idle | :loading | :fetching | :loaded | :error
 :data       <last-known-good-or-nil>
 :error      <first-load-error-or-nil>
 :refresh-error <background-refresh-error-or-nil>
 :loaded-at  <ms-or-nil>
 :stale-at   <ms-or-nil>
 :invalidated-at <ms-or-nil>
 :attempt    <int>
 :generation <int>
 :request-id <request-id-or-nil>
 :tags       <set>
 :active-owners <set>}
```

This deliberately refines Pattern-RemoteData's broad `:error` state. In resource
projections, `:error` is reserved for a failed first load with no usable data.
A failed background refresh returns to `:loaded`, preserves the prior data, and
records the failure in `:refresh-error`.

The important invariant:

- `:loading` means first load with no usable data;
- `:fetching` means work is in flight while prior data stays visible;
- `:error` means the resource has no usable data because the first load failed;
- freshness is orthogonal to load status. A `:loaded` entry may be stale, and a
  `:fetching` entry may be refreshing stale data;
- a failed background refresh keeps the prior `:data`, returns to `:loaded`, and
  records the failure in `:refresh-error`;
- `:refresh-error` is cleared by the next successful load or refresh;
- `:stale?`, `:loading?`, `:fetching?`, and `:has-data?` are public derived
  subscription values, not durable stored facts;
- views should not have to infer "error with stale data" from
  `(:status state)` plus `(:has-data? state)`.

The examples below show public `:rf.resource/state` projections. Durable entries
do not store derived booleans such as `:has-data?` or `:fetching?`.

First-load failure:

```clojure
{:status :error
 :data nil
 :error {:kind :rf.http/http-5xx :status 503}
 :refresh-error nil
 :has-data? false}
```

Background-refresh failure:

```clojure
{:status :loaded
 :data {:title "Welcome"}
 :error nil
 :refresh-error {:kind :rf.http/http-5xx :status 503}
 :has-data? true
 :fetching? false}
```

This keeps the `:loading` / `:fetching` promise intact: views do not guess
whether they are looking at a blank first-load failure or stale data with a
refresh warning.

### Structural Sharing

Successful resource loads should preserve the old `:data` value when the newly
decoded data is `=` to the previous data. This keeps downstream subscriptions
and views quiet when a background refresh returns identical EDN.

Large or non-EDN values may need a later explicit merge/structural-sharing hook,
but the v1 default should be the re-frame2 value model: compare values, preserve
the old value when nothing changed, and make equality decisions observable in
trace rows when they affect a resource transition.

### Stale And GC Scheduling

Because `:stale-after-ms` and `:gc-after-ms` are v1 features, their scheduling
rules are part of v1 too. They have the same hidden-tab and event-drain concerns
as later polling.

Rules:

- freshness is computed from durable timestamps such as `:loaded-at` and
  `:stale-at`, not from trusting that a timer fired exactly on time;
- a stale timer may enqueue a resource event, but the handler must re-check the
  current entry before writing;
- inactive GC may use host timers, but GC must re-check owner sets and entry
  generation after wake;
- timers and host handles live in side tables, not in frame-state;
- frame destroy cancels all resource timers for that frame;
- a hidden tab can delay timers without corrupting correctness; on focus or
  reconnect, the landed revalidation handlers scan active stale entries and
  refetch by event.

### Route Integration

Add `:resources` as route metadata:

```clojure
(rf/reg-route
  :route/article
  {:path "/articles/:slug"
   :params [:map [:slug :string]]

   :resources
   [{:resource  :article/by-slug
     :params    (fn [route]
                  {:slug (get-in route [:params :slug])})
      :scope     (fn [_route ctx]
                   (:current-session-scope ctx))
      :blocking? true}

     {:resource  :comments/list
      :params    (fn [route]
                   {:slug (get-in route [:params :slug])})
      :when      (fn [route _ctx]
                   (some? (get-in route [:params :slug])))
      :blocking? false
      :keep-previous? true}]})
```

Spec 012 currently rejects unknown bare route metadata keys at registration.
The resources artifact must therefore extend the routing accepted-key set, via a
late-bound framework extension, so `:resources` is treated like the existing
cross-feature `:head` key. Without that integration, a route containing
`:resources` is correctly rejected by the current routing artifact.

On route entry:

1. routing resolves the route and nav-token;
2. route resource `:when` predicates are evaluated;
3. route resource scopes and params are computed and validated;
4. each resource is marked active with owner `[:route route-id nav-token]`;
5. each resource is ensured with cause `[:route-entry route-id nav-token]`;
6. blocking resources are tracked under the nav-token;
7. non-blocking resources fetch in the background;
8. failures in blocking resources update route transition/error state;
9. Xray can display the route/resource graph without parsing handlers.

On route leave or superseded navigation:

1. route-owned resources are released by owner token;
2. polling for owners that went away stops in later slices;
3. in-flight work is aborted only when no remaining owner still needs it;
4. stale replies are suppressed by generation/nav-token even if abort is not
   available;
5. inactive resources become eligible for `:gc-after-ms` cleanup.

`blocking?` should be defined precisely:

- it keeps the route transition in a loading/pending state;
- it gives SSR a wait point before render;
- it does not have to block URL commit or prevent a client skeleton from
  rendering;
- if hydrated data is already fresh, it should not block.

Existing `:on-match` remains canonical for arbitrary route-entry work.
`:resources` is declarative server-state metadata layered beside it, not a
second router.

Route resources should define params-failure behavior explicitly. A failed
params schema should be a route/resource planning error visible in route state
and Xray, not a silent cache miss. Conditional resources should use `:when`
rather than sentinel nil params.

Dependent route resources should be modeled as a route plan, not a hidden view
effect. The simple v1 rule can be conservative: compute independent resources
in parallel; let a route resource declare a local `:id`, and let another route
resource declare `:after #{local-id}` when its params depend on the first
resource's data. `:after` must target route-local ids rather than resource ids
because the same resource can appear more than once with different params. Xray
should show the dependency and any waterfall.

> **Landed (rf2-xeb4l1; the spec governs, [Spec 016 §Route integration](../../spec/016-Resources.md#route-integration)):** `:after` shipped as **dispatch-order only**, narrower than this proposal's "when its params depend on the first resource's data" — the pure synchronous route planner cannot feed an earlier resource's loaded *data* into a later entry's params (a true data-waterfall is a deferred slice). `:after` guarantees ensure-dispatch order, and a missing or cyclic target is a fail-closed planning error (`:fix-after`), not silent declaration-order degradation.

Routes are not required. An app can use resources entirely from events and
machines:

```clojure
(rf/reg-event-fx
  :dashboard/opened
  (fn [_ [_ user-id]]
    {:fx [[:dispatch
           [:rf.resource/ensure
            {:resource :dashboard/summary
             :params   {:user-id user-id}
             :owner    [:lease :dashboard/opened user-id]
             :cause    [:event :dashboard/opened]}]]]}))
```

This still gets canonical identity, stale/fresh policy, dedupe, invalidation,
GC, passive subscriptions, and Xray visibility. What it does not get
automatically is route ownership, route leave release, route transition
blocking, or SSR route preload. Those can be supplied explicitly with owners
and server entry events if the app is not route-driven.

If an event only wants to refresh data and does not intend to keep it active, it
should omit `:owner` and supply only `:cause`. Event-created owners must have a
matching release path.

### Paginated and Previous Data

Paginated tables, filtered lists, search results, and cursor feeds are ordinary
resources in v1. They should not wait for the later "infinite resources" slice.

The v1 pattern is:

- include every filter, sort, page, cursor, and server-visible option in params;
- tag both the list identity and any returned item identities;
- keep old data visible while a new page/filter resource is first-loading when
  the route/resource declaration opts into `:keep-previous?`;
- mark previous data as previous/placeholder in the public resource state, not
  as cached data for the new key;
- let Xray show the previous key and the new key so near-duplicate params,
  nil-vs-missing mistakes, and accidental request duplication are obvious.

The public `:rf.resource/state` projection for a `:keep-previous?` load should
make the distinction explicit:

```clojure
{:status :loading
 :data nil
 :previous? true
 :previous-key [scope :articles/list {:page 1 :filter "recent"}]
 :previous-data [{:id 1 :title "Old page"}]
 :placeholder? false}
```

`previous-data` is a projection from the prior key; it is not inserted into the
new cache entry and must not provide tags for the new key. The new entry becomes
ordinary `:loaded` data only after its own request succeeds.

Cache growth for table/list params is controlled by the same owner and GC rules
as other resources. `:keep-previous?` must not pin old pages beyond their
owners; it only allows the current view to project the previous entry while the
new key is loading.

This keeps the common "page 2 of an admin table" case small while still
reserving infinite scrolling, max-page retention, and cursor-window policies for
a later dedicated slice.

### SSR and Hydration

SSR must use request-local frames. A process-global resource cache would leak
data between users.

Server route handling should:

1. resolve the route;
2. compute route resources;
3. enqueue blocking resource ensures;
4. drain until blocking resources for the current nav-token settle;
5. render with the settled resource state;
6. serialize only the allowed resource runtime projection;
7. record projection metadata: serialized, redacted, omitted, fresh, stale, and
   refetch-on-client decisions.

Blocking SSR resources need a timeout policy. A timeout should settle the
resource as a structured first-load failure for that SSR frame, record the route
blocking failure, and let the renderer choose between error markup, a skeleton,
or an application-specific fallback. It must not hang the request indefinitely.

Client hydration should:

1. install the allowed resource projection into the target frame-state's
   `:rf.runtime/resources` slice in runtime-db (`:rf.db/runtime`);
2. preserve hydrated resource entries;
3. avoid duplicate immediate fetches for fresh entries;
4. background-refetch stale entries according to policy;
5. maintain frame and nav-token isolation.

Do not serialize all of `:rf.db/runtime` by default. Resource hydration needs an
explicit projection hook that can redact or omit sensitive and large data.
Hydration should never cross scopes: request-local SSR frames and serialized
resource scopes must agree before a client treats hydrated data as usable. The
projection rule is scoped to the `:rf.runtime/resources` slice inside runtime-db:
serialize only the resource projection, not unrelated runtime state.

Hydration rules:

- `loaded-at`, `stale-at`, and `invalidated-at` are absolute timestamps; server
  clock skew should be surfaced in trace/hydration diagnostics when it makes
  freshness ambiguous;
- omitted or redacted entries hydrate as metadata only and refetch on the client
  if the route still needs them;
- stale hydrated entries may render their data immediately, then refetch by
  resource event according to policy;
- `refresh-error` should serialize only when the error envelope is allowed by
  the same privacy/size projection as data.

### Restore and Replay

[Benchmark Positioning](#benchmark-positioning) lists time-travel through
frame-state as a structural advantage, and [EP-0002](EP-0002-frame-target-resolution.md)
makes replay determinism the framework's decisive argument. Resources are
runtime-managed read models over an in-flight work ledger, so a claimed
"time-travel-safe" capability is not credible until it specifies what
`restore-epoch!` (EP-0001 epoch restore / time travel, and the same install path
SSR hydration uses) means for `:rf.runtime/resources` and
`:rf.runtime/work-ledger`. Without that contract, restore is a load-bearing hole:
the EP sells revertibility but leaves resource state undefined after a rewind.

Epoch restore installs both partitions wholesale — it replaces the entire
frame-state value (`:rf.db/app` plus `:rf.db/runtime`), and does not run ordinary
`:db` effect semantics ([EP-0001 §Full-frame restore](EP-0001-frame-partitions.md)).
The resource and work-ledger slices ride inside `:rf.db/runtime`, so a restore
overwrites them with their value as of the restored epoch. Host side tables
(AbortControllers, stale/GC timers, transport promises) are **not** part of
frame-state and are **not** rewound; they are transient by the EP-0001 durable/
transient boundary (decision 13: "Host handles, request slots, trace rings,
in-flight HTTP, and dirty caches stay transient... never serialized"). Restore
must therefore reconcile a freshly-installed *durable snapshot* against the
*live transient world* (host handles still attached to the pre-restore timeline,
and network replies already on the wire that the runtime cannot recall).

This section answers, per the runtime-subsystem contract's clause 5, "what does
epoch restore do to every value in this sub-tree?" The governing principle is the
**anti-recycling rule** carried from the `rf2-oosjmh` routing ruling and
generalized into [`spec/Runtime-Subsystems.md` §Derived rule 1 — the restore
question is mandatory; allocators never rewind](../../spec/Runtime-Subsystems.md#derived-rule-1--the-restore-question-is-mandatory-allocators-never-rewind)
(allocator counters "must never rewind"):

> A restored value must never let a stale generation or work-id be mistaken for
> a live one. Epoch restore must not resurrect a superseded in-flight identity,
> and must not rewind any monotonic allocator such that a post-restore allocation
> can collide with a pre-restore identity still carried by an uncancellable
> in-flight reply.

This is the resource analogue of the routing nav-token hazard. The distinguishing
factor is whether a stale identity from the old timeline can exist **outside** the
restored frame-state — specifically an in-flight HTTP reply already dispatched and
beyond cancellation — that a re-issued identity could collide with. For resources
the answer is yes, so the same anti-recycling discipline applies.

The contract has five parts, mirroring the five sub-tree questions an SSR/epoch
projection must answer.

#### 1. The generation allocator is monotonic and host-side; it does not rewind

A resource generation is the correctness boundary for stale-reply suppression
([§Race and In-Flight Semantics](#race-and-in-flight-semantics)): a reply may
write an entry only if its work-id and generation still match the live entry. If
epoch restore rewound the generation along with the rest of the entry, a
pre-restore in-flight reply — already on the wire, uncancellable — could return
carrying a generation the post-restore timeline has re-allocated, and be silently
accepted as live. That is exactly the recycle the `rf2-oosjmh` ruling forbids.

Therefore the **generation allocator is a per-frame, host-side monotonic
high-water mark**, not a value rewound by restore. It follows the routing
nav-token-counter precedent: keep the active identity durable on the entry
(restored coherently), but keep the *allocator* host-side so it only ever moves
forward across restores. After a restore, the next generation the runtime mints
for any resource key strictly exceeds every generation any pre-restore in-flight
reply could be carrying, so a stale reply's generation can never match a live
entry's generation — collision is structurally impossible rather than
probabilistically rare.

This is deliberately the *opposite* discipline from machine spawn-ids
([Spec 005 §Spawn-id allocator](../../spec/005-StateMachines.md#spawn-id-allocator--counter-location)),
and the difference is principled. Spawn-id allocation is pure and re-derived from
the restored snapshot during *deterministic replay*; recycling the same ids is
intentional there because no spawn identity escapes the frame onto an
uncancellable wire. A resource generation governs acceptance of a reply that
*has* escaped the frame, so it must never be re-issued. The rule is: **an
allocator whose identity can be carried by an out-of-frame, uncancellable
continuation must never rewind; an allocator whose identities never leave the
frame may be snapshot-local and replay-deterministic.** The work-ledger
`:work/id` (which embeds the generation) inherits the same monotonicity: a
restored ledger row's `:work/id` names a request that no longer exists, and a new
attempt always mints a strictly-greater generation, so the two identities can
never be confused.

#### 2. In-flight work does not survive restore as live work

Work-ledger rows describe attempts against the pre-restore timeline. On restore,
every non-terminal row in the installed snapshot (`:queued` / `:running` /
`:abort-requested`) references a request whose host handle no longer belongs to
the restored timeline — the AbortController, timeout, and promise were never
serialized and, after restore, are not the handles for the restored row. A
restored non-terminal row is therefore **dangling**: it must not be treated as
genuinely live work.

On install, restore reconciles non-terminal rows as follows:

- the row's `:work/id` is recorded as **dangling/superseded** (it can never again
  match a live entry, because the generation allocator has moved past it per
  part 1), and its host side-table slot is cleared;
- the linked resource entry's `:current-work` pointer is cleared, because the
  attempt it pointed at no longer exists;
- the entry's `:status` settles to its last *stable* status from the restored
  snapshot — `:loaded` if it has usable `:data`, `:error` if it was a failed
  first load with no data, `:idle` if it never loaded — never left stranded in
  `:loading` / `:fetching` pointing at a vanished request;
- any pre-restore reply that subsequently lands (the on-the-wire continuation) is
  suppressed by the ordinary work-id + generation check, because its identity is
  now dangling. **No stale reply may mutate a post-restore entry.** This needs no
  new mechanism: it is the mandatory stale-suppression boundary the EP already
  requires, and part 1's monotonic allocator is what guarantees the dangling id
  can never be re-minted.

Whether the restored entry then *re-fetches* is a freshness decision (part 3),
not an in-flight decision. Restore never silently continues old work; it settles
the entry and lets policy decide whether new work starts.

#### 3. Freshness after restore: lazy, not an eager refetch storm

Restored entries carry absolute `:loaded-at` / `:stale-at` / `:invalidated-at`
timestamps from the restored epoch. Against the live wall clock, a restore to an
older epoch makes essentially everything restore *instantly stale*, and a restore
to a future-relative epoch could make stale data look fresh. Two failure modes
must be avoided: an **eager refetch storm** (every restored entry refetches at
once, turning time-travel into a network thundering herd) and **silent
acceptance of misleadingly-fresh timestamps**.

The ruling, consistent with hydration (which faces the identical absolute-timestamp
problem):

- restore does **not** eagerly refetch. Freshness is evaluated lazily, the same
  way hydration handles it: a restored entry renders its data immediately, and
  refetches only on the next `ensure` from a live owner (route re-entry, focus/
  reconnect revalidation, or an explicit event), gated by the entry's own
  stale/fresh policy;
- a restored entry with **no active owner** after restore is never refetched on
  the strength of restore alone — it is subject to ordinary GC eligibility
  (part 5);
- absolute-timestamp ambiguity (a restored `:stale-at` that is implausible
  against the live clock) is surfaced in a restore/hydration trace diagnostic,
  exactly as clock skew is surfaced for SSR hydration, rather than silently
  trusted;
- this yields the desired property: **a restored epoch double-fetches nothing.**
  No entry refetches merely because it was restored; refetch happens only when a
  live cause demands it.

#### 4. Owners revive or orphan by kind

Restored `:active-owners` reference owner tokens from the pre-restore timeline.
Whether a restored owner is *real* after restore depends on whether the thing it
names is itself revertible:

- **Machine owners** (`[:machine machine-id instance-id]`) revive. Machine
  liveness is a pure function of the restored snapshot
  ([Spec 005](../../spec/005-StateMachines.md)) — restoring frame-state restores
  the machine and its instance id, so a machine owner the snapshot revives is a
  genuine live lease again.
- **Route owners** (`[:route route-id nav-token]`) revive **only if** the
  restored routing state names the same live nav-token. Route state restores with
  runtime-db ([EP-0001](EP-0001-frame-partitions.md)), and the active nav-token
  (`:current`) is durable; but a restored route owner whose nav-token is not the
  one the restored routing slice currently considers live is released as an
  **orphan** rather than trusted (it is the resource analogue of the nav-token
  supersession check).
- **Lease/event owners** (`[:lease ...]`, `[:dashboard/opened ...]`) revive with
  the snapshot, since they are recorded durably on the entry; their release path
  is the same explicit `:rf.resource/release-owner` it always was.
- **SSR owners** (`[:ssr request-id nav-token]`) do not survive a client-side
  restore as live leases; they belong to a settled server render and are released
  as orphans if present.

Owner reconciliation runs on install: each restored owner is checked against the
revived runtime state, surviving owners stay in `:active-owners` and the
`:owner-index`, and orphaned owners are dropped with a trace row. This prevents a
restored entry from being pinned alive (or refetched on focus/reconnect) by an
owner that no longer exists.

#### 5. Transient side tables and indexes are recomputed or cleared on install

Two classes of state must be rebuilt rather than trusted across restore, both
following established EP-0001 precedent.

**Host transients are cleared, then recomputed on demand.** Stale timers, GC
timers, AbortControllers, and transport promises are frame-scoped host handles
that restore does not rebuild (EP-0001 decision 13). On install they are cleared
for the affected frame; stale/GC scheduling is re-armed lazily from the restored
entries' durable timestamps the next time the runtime touches each entry, exactly
as [§Stale And GC Scheduling](#stale-and-gc-scheduling) already requires timers
to be advisory and re-checked against durable facts. This mirrors EP-0001
decision 12 (flow dirty-check caches are recomputed/cleared on restore) and the
`rf2-egvm4t` precedent (spawned-actor marks are rehydrated from the restored
snapshot on first dispatch rather than carried as a separate durable fact).

**Indexes are recomputed from entries, never trusted from the snapshot.** The
`:tag-index` and `:owner-index` are derived projections of the entries' `:tags`
and `:active-owners`. They are declared **recomputable-from-entries**: on restore
(and on SSR hydration) they are rebuilt from the installed `:entries` rather than
read from the serialized snapshot, so a stale or partial index can never outlive
the entries it describes (EP-0001 decision 13: runtime-db holds the serializable
*facts* needed for restore; recomputable derivations are not durable truth, and
[`spec/Runtime-Subsystems.md` §Derived rule 2 — one authoritative home per fact;
mirrors are recomputable projections](../../spec/Runtime-Subsystems.md#derived-rule-2--one-authoritative-home-per-fact-mirrors-are-recomputable-projections):
a mirror is a recomputable projection, never a second source of truth). This
single rule then also serves SSR hydration: hydration
likewise installs `:entries` and recomputes the indexes, so the durable wire
payload need not carry them at all.

#### Ledger row retention and identity

Two ledger-design points are settled here because they directly govern what rides
the restore/hydration/epoch wire.

**Terminal ledger rows are pruned; the ledger is bounded.** Work records are
created per attempt and reach a terminal status (`:completed` / `:failed` /
`:timed-out` / `:suppressed` / `:cancelled`) with an outcome summary. Left
unbounded, the ledger would be unbounded growth in serializable frame-state —
worse than trace growth, because it rides SSR, hydration, and every epoch
snapshot. The rule: a terminal row is **pruned on the linked entry's next
successful transition**, with a small bounded per-resource-key tail retained only
for Xray's recent-races view (the same bounded-history discipline the EP applies
to traces). Hydration and epoch snapshots serialize only **non-terminal rows'
summaries**; terminal rows are local Xray history, not durable wire payload. A
restored snapshot therefore installs at most the bounded set of non-terminal
rows, all of which part 2 immediately reconciles to dangling.

**One identity per work record.** The work record must not carry both a
`:work/id` `[:rf.work/resource resource-key generation]` and a near-duplicate
`:stale-key` `[:resource resource-key generation]` that differ only in their head
keyword while denormalizing the same `resource-key` + `generation` facts. That
invites drift between two spellings of one identity — the precise one-carrier-one-name
lesson of [EP-0002](EP-0002-frame-target-resolution.md) R3 and [`spec/Runtime-Subsystems.md`
§Derived rule 2](../../spec/Runtime-Subsystems.md#derived-rule-2--one-authoritative-home-per-fact-mirrors-are-recomputable-projections).
**Stale suppression keys on `:work/id`**; the separate `:stale-key` is
dropped. (If a future transport genuinely needs a transport-facing suppression
token distinct from the internal work-id, it must be justified in the normative
spec as a deliberate second identity, not left as an unexplained synonym.) This
keeps the restore contract simple: there is exactly one identity per attempt to
reconcile to dangling, and exactly one allocator (part 1) that must never rewind.

### Transport

The initial scope ships with a single built-in transport:

```clojure
:transport :rf.http/managed
```

The resource lifecycle, cache identity, owner model, stale/fresh policy,
invalidation, SSR hydration, and Xray surfaces must nonetheless be
transport-neutral. The core should not assume a URL, HTTP method, status code,
or request body — those are HTTP transport details — so that the deferred GraphQL
transport (and any later transport) can plug in without weakening the core
semantics. It also should not assume a normalized entity graph, fragment store,
or GraphQL client cache.

For HTTP, the resource runtime first creates or joins a work-ledger record, then
lowers an ensure/refetch into managed HTTP:

```clojure
[:rf.http/managed
 (assoc http-args
        :request-id request-id
        :on-success [:rf.resource.internal/succeeded
                     {:work-id      work-id
                      :resource-key resource-key
                      :scope        scope
                      :rf.frame/id  frame-id
                      :generation   generation}]
        :on-failure [:rf.resource.internal/failed
                     {:work-id      work-id
                      :resource-key resource-key
                      :scope        scope
                      :rf.frame/id  frame-id
                      :generation   generation}])]
```

These internal reply payloads stamp the intended frame with the qualified
`:rf.frame/id` key, the canonical carried frame stamp for new framework causal
tokens ([EP-0002](EP-0002-frame-target-resolution.md) R3, "one canonical frame
stamp"). This matches the qualified `:work/frame` stamp on the work-ledger record
above; the EP does not use the bare `:frame` opt for these framework-internal
tokens (`:frame` remains the public dispatch/subscribe target opt, unchanged).

The managed HTTP reply dispatch is already frame-targeted by Spec 014. The
resource metadata should still carry the intended frame id for assertion,
stale-suppression diagnostics, and trace rows. Success and failure events must
verify frame, work id, and generation before writing. Cancellation is an
optimization; stale suppression is the correctness boundary.

The deferred GraphQL phase adds a `:rf.graphql/query` transport that lowers
ensure/refetch into a GraphQL query operation while preserving these same
invariants; its lowering shape and partial-success policy are in
[Deferred: GraphQL (later phase)](#deferred-graphql-later-phase).

Generic transport extension is still desirable, and `re-frame-query`
demonstrates that demand, but it should be a later extension protocol after the
HTTP built-in (and then the GraphQL transport) have proven the resource
semantics.

### Race and In-Flight Semantics

These cases should be specified before implementation:

- `ensure` while the same scoped resource key is already in flight joins the
  existing current work record, attaches any supplied owner to both the resource
  entry and ledger row, records the new cause, and emits a dedupe trace;
- `refetch` may force a new generation. If a prior request is still in flight,
  mark the old work record superseded, abort it when possible, and otherwise
  suppress its late reply by work id and generation;
- invalidation while a request is in flight marks the entry stale and records
  the invalidation. If the in-flight request is for the current generation, its
  success may satisfy the invalidation only when policy says the request covered
  the invalidated identity; otherwise schedule a follow-up refetch;
- owner release while a request is in flight aborts only when no remaining owner
  needs that work record. Shared requests must not be cancelled just because one
  route, machine, or lease went away;
- route supersession uses both nav-token owner release and generation checks.
  The old nav-token may not write into the new route's resource state;
- stale/GC timers are advisory. A timer handler must re-read the current entry,
  scope, owners, and generation before writing because a newer event may already
  have refreshed, invalidated, removed, or re-owned the entry.

### Invalidation

V1 should support exact tag invalidation:

```clojure
[:rf.resource/invalidate-tags
 {:scope [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :tags  #{[:article "welcome"] [:article-list]}
  :cause [:mutation :article/save mutation-id]}]
```

Algorithm:

1. find entries whose provided tags intersect invalidated tags;
2. mark entries stale;
3. refetch entries with active owners;
4. leave inactive entries stale or eligible for GC;
5. emit trace records explaining matched keys and decisions.

On successful resource load, the tag index for that scoped resource key is
replaced with the tags produced by the new data. Old tags must be removed so
that stale list/detail relationships do not keep receiving invalidations after
the data has changed.

Invalidation can be batched. A single event may carry many tags, but it should
emit one decision summary plus per-entry details so Xray can show broad-tag
storms without flooding the trace. Broad invalidations are allowed, but they
should be visible and lintable.

Scoped invalidation is the default. If an invalidation has no matches, Xray
should distinguish "no match in this scope" from "no resource provides this tag
in any scope." That distinction catches tenant/user scope mistakes.

Invalidation traces should distinguish:

- no matching resource;
- matching inactive resource marked stale;
- matching active resource refetched;
- matching resource already stale;
- matching resource skipped by policy;
- broad tag matched many entries.

Later, Xray can add lint:

- mutation invalidates no tags;
- mutation invalidates tags no resource provides;
- resource provides overly broad tags;
- route depends on a resource but never owns it;
- active resource is stale but has no refetch policy.

Do not pretend invalidation can always be derived. The server is the source of
truth and the client often lacks enough semantic information.

### Focus And Reconnect Revalidation

Focus and reconnect revalidation is part of the landed first public-beta gate.

TanStack Query's most visible magic is that stale active data refreshes when
the user returns to the tab or the network reconnects. re-frame2 provides
the same user-facing behavior, but through events rather than subscription
lifecycle.

The implementation reuses v1 primitives:

- active owners decide which entries are worth refetching;
- stale/fresh timestamps decide whether refetch is needed;
- generation checks suppress stale replies;
- the selected transport adapter owns transport retry and abort;
- Xray and traces show why the refresh happened.

Public/internal events:

```clojure
:rf.resource/window-focused
:rf.resource/network-reconnected
```

Algorithm:

1. receive focus or reconnect signal;
2. scan active resource entries;
3. refetch entries that are stale or policy-marked for revalidation;
4. leave fresh entries alone;
5. emit trace records explaining the decision.

This is deliberately not subscription-driven fetching. The browser event causes
resource events; views remain passive reads.

### Mutations

Mutations were the second slice, not the read-resource MVP. They landed
alongside focus/reconnect revalidation because a read cache without a
write/invalidation story feels incomplete next to TanStack Query, RTK Query,
SWR, and `re-frame-query`.

Apps can still dispatch `:rf.resource/invalidate-tags` manually after their own
write events. That is coherent, but it is an escape hatch rather than the full
value proposition.

The landed minimal mutation slice includes:

- `reg-mutation`, `clear-mutation`, and `:rf.mutation/execute`;
- mutation pending/error/result state;
- a generated or caller-supplied mutation instance id;
- scoped execution under a hybrid scope model — the mutation's *execution*
  scope is fail-open (payload `:scope`, else spec `:scope`, else
  `:rf.scope/global`, since a causal write has no cached-read leak boundary),
  while its *invalidation* scope is fail-closed (`:rf.resource/invalidate-tags`
  requires an explicit scope, `:cross-scope? true` being the only opt-out);
- concurrency semantics for multiple submissions of the same mutation id;
- tag invalidation from success and, when useful, failure;
- explicit invalidation timing: before request, after success, after failure, or
  after settle;
- controlled resource patch/populate APIs for mutation responses;
- abort and retry policy inherited from the selected transport adapter, with
  write retries opt-in;
- failure-state lifetime and a causal clear/reset event;
- trace-visible mutation instance ids;
- instrumentation hooks for later optimistic snapshots and rollback.

Mutation runtime state should be keyed by mutation instance id, not only by
mutation id, so two concurrent `:comment/add` submissions do not overwrite each
other's pending/error/result state. Xray should group those instances under the
registered mutation id while still showing each request, invalidation, patch,
and result separately.

Example shape:

```clojure
(rf/reg-mutation
  :article/save
  {:params-schema :app/article

   :request
   (fn [{:keys [slug] :as article} _ctx]
     {:request {:method :put
                :url    (str "/api/articles/" slug)
                :body   article}
      :decode :app/article})

   :transport
   :rf.http/managed

   :invalidates
   (fn [{:keys [slug]} _result]
     #{[:article slug]
       [:article-list]})})
```

Dispatch:

```clojure
[:rf.mutation/execute
 {:mutation :article/save
  :params   article}]
```

Optimistic updates are deferred beyond the first public-beta gate, but the
mutation trace shape should reserve room for them now: affected resource keys,
patch summaries, snapshot ids, rollback result, and reconciliation refetches.
When optimistic updates land, they should initially use snapshot rollback of
affected resource entries. Epoch-diff rollback can be researched later if the
epoch subsystem becomes production-safe for this purpose.

### Machines

Resources and machines should compose, not compete.

Use resources for shared cached reads:

```clojure
[:rf.resource/ensure
 {:resource :checkout/quote
  :params   {:cart-id cart-id}
  :owner    [:machine :checkout/flow machine-instance-id]}]
```

Use machines for semantic workflows:

- a checkout quote with auth renewal and alternate payment paths;
- a websocket subscription with reconnect states;
- a multi-step upload;
- a wizard that loads resources, validates state, and performs writes.

Do not spawn a full machine per ordinary resource entry. That would make common
read caching heavier without improving correctness.

### Xray and AI Tooling

Xray should expose:

- a static resource registry: resource id, source coordinates, params/data
  schemas, request summary, stale/GC policy, tag producer, scope resolver,
  sensitivity/large classification, and declaring routes;
- a live resource instance table per frame: resource key, scope, status,
  loaded-at, stale-at, gc-at, generation, request id, attempt, active owners,
  tags,
  errors, data summary, and GC eligibility;
- a live work-ledger table per frame: work id, kind, linked resource key,
  generation, status, owners, causes, cancellable?, deadline, retry
  attempt, and outcome;
- a route/resource graph: current route/nav-token, blocking vs non-blocking
  resources, SSR wait points, active work, hydrated/fresh/stale state;
- a lifecycle timeline: ensure, owner attach, cache hit, dedupe, request
  issued, success/failure, refresh failure, invalidation, refetch decision,
  owner release, GC schedule/fire/skip, stale suppression, hydration;
- an invalidation/mutation graph: invalidated tags, matched entries, active
  refetches, inactive stale marks, no-match invalidations, broad-tag warnings,
  mutation source coordinates;
- a cache growth view: counts by resource/status/scope/owner/tag, inactive
  entries, entries past GC time, largest elided data summaries, orphaned owners,
  and retained side-table handles;
- a **scope audit surface**: the standing enumeration of every
  `:rf.scope/global` resource (the structural security-review list that replaces
  the old `/me` heuristic — see [Scope Resolution](#scope-resolution)), plus the
  suspicious-explicit-global warnings (a global claim whose request looks
  session-dependent).

A **scope-mismatch lint** is part of this surface and catches the read-side leak
the instant it happens:

> A cache **entry** exists for resource `R` with params `P` under scope `A`;
> a **live subscription** is reading the same resource `R` + params `P` under a
> **different** scope `B` and getting `:idle` (or `:loading` that never
> resolves).

That pattern is the silent skeleton the [Scope Resolution](#scope-resolution)
fail-closed rules now make a loud error at registration/use time; the lint is the
runtime tripwire for the cases that slip through — e.g. an event ensured under
`[:rf.scope/session {…}]` while a view subscribed under `[:rf.scope/global]`
(or a stale token). Xray flags the mismatched (entry-scope, sub-scope) pair with
both resource keys so the divergence is obvious at a glance, rather than the view
silently rendering a permanent skeleton. The **orphaned-owner lint** (an
app-minted `[:lease …]` owner with no observed release path — see [Active
Owners](#active-owners-not-component-observers)) rides the same cache-growth /
audit surface.

Tool APIs should prefer summaries and metadata over raw values. AIs usually
need to know "this route owns `:article/by-slug`, it is stale, and the latest
background refresh failed with a 503", not the full article body or user
profile payload.

This needs a trace/accessor contract, not only panel UI. The artefact adds a
`:rf.resource/*` trace family. The **closed, runtime-emitted** operation set is
the 28 ops below — every one is emitted by the Resources runtime. Xray defines
the family (closed op set + per-op semantic class + label) in
[Xray spec 024 §The `:rf.resource/*` trace family](../../tools/xray/spec/024-Resources-Panel.md);
the emit catalogue is in
[Spec 009 §Where trace emission lives](../../spec/009-Instrumentation.md#where-trace-emission-lives).
Those two are the canonical sources; this list mirrors them.

```clojure
;; lifecycle
:rf.resource/registered          ; first-time reg-resource (frame-agnostic)
:rf.resource/owner-attached      ; a new owner lease lands on an entry
:rf.resource/work-started        ; work-LEDGER row created (transport request started)
:rf.resource/fetch-started       ; cache ENTRY transitioned to :fetching (emitted with work-started)
:rf.resource/work-abort-requested
:rf.resource/work-completed
:rf.resource/succeeded
:rf.resource/refetch-decision    ; per-entry refetch decision
:rf.resource/revalidate-scan     ; focus/reconnect active-stale scan summary
:rf.resource/route-plan          ; route-entry resource planning summary
:rf.resource/owner-released
:rf.resource/removed
;; dedupe
:rf.resource/cache-hit           ; fresh-skip ensure (serve cached, no fetch/join)
:rf.resource/deduped             ; join in-flight work
;; failure
:rf.resource/failed              ; first-load failure → :error
:rf.resource/refresh-failed      ; background-refresh failure, data kept
;; invalidation
:rf.resource/invalidated
;; gc / stale scheduling
:rf.resource/stale-scheduled     ; stale timer armed
:rf.resource/stale-fired         ; stale timer fired
:rf.resource/gc-scheduled        ; GC timer armed
:rf.resource/gc-fired
:rf.resource/gc-skipped          ; entry re-owned
;; suppression (the SINGLE suppression op)
:rf.resource/stale-suppressed
;; hydration / restore
:rf.resource/hydrated            ; SSR hydration reconcile
:rf.resource/hydrate-refetch     ; per-entry hydrate refetch-plan row
:rf.resource/hydrate-clock-skew  ; :warning — hydrate stale-at skew
:rf.resource/restored            ; epoch/SSR restore reconcile summary
:rf.resource/restore-clock-skew  ; :warning — restore stale-at skew
```

Two ops named in earlier drafts are **NOT** members of this set:
`:rf.resource/work-suppressed` was folded into `:rf.resource/stale-suppressed`
(the runtime never emitted a distinct work-suppressed row — there is exactly one
suppression op), and `:rf.resource/ensure` (with `:rf.resource/refetch` /
`:rf.resource/remove` / `:rf.resource/window-focused` /
`:rf.resource/network-reconnected` / `:rf.resource/invalidate-tags` /
`:rf.resource/release-owner`) is a dispatched **event id**, not an emitted
`:operation` — it appears in the stream only as the `:rf.event/dispatched`
event vector, so it is not a member of the `trace-ops` family enum.
`:rf.resource/work-started` and `:rf.resource/fetch-started` are the **two
facets of a single start** (work-ledger row vs cache-entry transition), emitted
together on the one start path — not first-load vs refresh.

Every resource trace should carry, where applicable, frame, work id, scope,
resource key, resource id, params summary, generation, request id, owner, cause,
status before/after, work status, resource tags, invalidated tags, freshness
timestamps, and redaction/size markers.

Trace and history retention are part of the tool contract. Resource history must
be bounded, and params/scopes need the same privacy and size elision treatment
as data because scopes can contain user ids, tenant ids, locale, or
impersonation markers. Xray should display elided summaries for sensitive or
large params/scopes and keep enough retained history to explain recent races,
invalidations, and GC decisions without becoming its own unbounded cache.

Candidate tool accessors:

```clojure
(list-resources opts)
(list-resource-instances opts)
(get-resource-state opts)
(get-resource-history opts)
(list-resource-invalidations opts)
```

They should filter by frame, scope, resource id, tag, owner, status, stale?,
request id, and nav-token. Raw data access continues to go through existing
egress and elision rules.

## Examples

### Route-Driven Page Load

This article read is **public** — the same article for every user — so it
makes the explicit `:rf.scope/global` claim (the same `:article/by-slug`
registration as the [reference above](#resource-registration)). Because
the scope is a fixed global claim, the route declares **no** scope resolver
and the view passes **no** `:scope` — all three call sites resolve to the same
`[:rf.scope/global]` key with nothing to mismatch. (Scope is still
fail-closed: a session-dependent article would instead declare
`:scope :rf.scope/from-caller`, the route resolver would supply the concrete
session scope, and the view would pass that same scope on its
`[:rf.resource/state …]` — see [Scope Resolution](#scope-resolution). What is
never coherent is a global registration paired with a session route resolver:
pick one story.)

```clojure
(rf/reg-resource
  :article/by-slug
  {:params-schema [:map [:slug :string]]
   :data-schema   :app/article
   ;; Public read → the explicit, auditable global claim. Scope is REQUIRED
   ;; (fail-closed); :rf.scope/global is the claim "same data for every user."
   :scope         :rf.scope/global
   :request
   (fn [{:keys [slug]} _]
     {:request {:method :get
                :url    (str "/api/articles/" slug)}
      :decode :app/article})
   :stale-after-ms 60000
   :gc-after-ms    (* 5 60 1000)
   :tags
   (fn [{:keys [slug]} _]
     #{[:article slug]})})

(rf/reg-route
  :route/article
  {:path "/articles/:slug"
   :params [:map [:slug :string]]
   :resources
   ;; No :scope resolver — the resource's policy is the fixed global claim.
   [{:resource  :article/by-slug
     :params    (fn [route]
                  {:slug (get-in route [:params :slug])})
     :blocking? true}]})

(rf/reg-view article-page []
  (let [slug  (:slug @(rf/subscribe [:rf.route/params]))
        ;; No :scope — a pure sub resolves the global claim from the spec.
        state @(rf/subscribe [:rf.resource/state
                              {:resource :article/by-slug
                               :params   {:slug slug}}])]
    (cond
      (:loading? state)
      [article-skeleton]

      (and (:error state) (not (:has-data? state)))
      [article-error (:error state)]

      :else
      [:<>
       [:article-view {:article (:data state)}]
       (when (:fetching? state)
         [refresh-indicator])
       (when (:refresh-error state)
         [refresh-error (:refresh-error state)])])))
```

The view is passive. The route caused the resource ensure. The runtime owns
the resource state. The scope story is coherent end to end: one explicit
global claim, no session resolver, no per-call scope to mismatch.

(A GraphQL resource example lives in
[Deferred: GraphQL (later phase)](#deferred-graphql-later-phase).)

### Event-Driven Ensure

```clojure
(rf/reg-event-fx
  :dashboard/opened
  (fn [_ [_ user-id]]
    {:fx [[:dispatch
           [:rf.resource/ensure
            {:resource :dashboard/summary
             :params   {:user-id user-id}
             :owner    [:lease :dashboard/opened user-id]
             :cause    [:event :dashboard/opened]}]]]}))
```

### Manual Refresh

`:article/by-slug` is `:rf.scope/global`, so the refetch resolves the same
global key as the route ensure — no per-call `:scope` to supply or mismatch.
(A `:rf.scope/from-caller` resource would instead require `:scope` on the
refetch payload — the same scope the route ensured under — or fail closed with
`:rf.error/resource-scope-required-from-caller`.)

```clojure
(rf/reg-event-fx
  :article/refresh-clicked
  (fn [_ [_ slug]]
    {:fx [[:dispatch
           [:rf.resource/refetch
            {:resource :article/by-slug
             :params   {:slug slug}
             :cause    [:event :article/refresh-clicked]}]]]}))
```

### Mutation With Invalidation

The write's success-time invalidation runs against the mutation's **resolved
scope**. Unlike a resource read, a mutation's scope is **not** fail-closed —
it resolves payload `:scope` → spec `:scope` → `:rf.scope/global`. The scope
**MUST match the scope of the resources the write invalidates**: here the
article comments are public (`:rf.scope/global`), so the mutation's default
global scope is correct and nothing extra is passed. A write against
**session/tenant-scoped** entries would instead set `:scope` (statically, or
on `[:rf.mutation/execute …]` when the principal is only known at the call
site) to that scope — otherwise its `:invalidates` lands in the global cache
and silently misses the entries it just changed.

```clojure
(rf/reg-mutation
  :comment/add
  {:params-schema
   [:map
    [:slug :string]
    [:body :string]]

   :request
   (fn [{:keys [slug body]} _]
     {:request {:method :post
                :url    (str "/api/articles/" slug "/comments")
                :body   {:body body}}
      :decode :app/comment})

   ;; These comments are public (global), so the mutation's default
   ;; :rf.scope/global invalidation targets the right cache. A session/tenant
   ;; -scoped comments resource would require :scope here (or on execute).
   :invalidates
   (fn [{:keys [slug]} _]
     #{[:comments slug]})})

(rf/reg-event-fx
  :comment-form/submitted
  (fn [_ [_ params]]
    {:fx [[:dispatch
           [:rf.mutation/execute
            {:mutation :comment/add
             :params   params}]]]}))
```

(A GraphQL mutation example lives in
[Deferred: GraphQL (later phase)](#deferred-graphql-later-phase).)

### Machine-Owned Resource

```clojure
{:actions
 {:ensure-quote
  (fn [{:keys [data]}]
    {:fx [[:dispatch
           [:rf.resource/ensure
            {:resource :checkout/quote
             :params   {:cart-id (:cart-id data)}
             :owner    [:machine :checkout/flow (:instance-id data)]
             :cause    [:machine-action :checkout/quote.requested]}]]]})}

 :states
 {:idle
  {:on
   {:quote.requested
    {:target :loading
     :action :ensure-quote}}}

  :loading
  {:on
   {:quote.loaded {:target :ready}
    :quote.failed {:target :failed}}}}}
```

The machine remains the semantic workflow. The resource runtime handles cached
read mechanics.

## Deferred: GraphQL (later phase)

GraphQL is **out of the initial HTTP-only scope** and is a deferred follow-on
phase, scheduled as the first transport extension once the HTTP core (the
read-resource MVP plus the HTTP mutation slice) has landed. This section
consolidates the GraphQL rationale, transport shape, and examples so the design
reads as "HTTP now, GraphQL later" rather than treating GraphQL as erased.

The analysis below is retained, not normative for the initial scope. None of it
ships until the HTTP core is stable.

### Why GraphQL Is Deferred (Not Dropped)

GraphQL is common enough, and different enough from endpoint-shaped HTTP, to
deserve a first-class transport. But shipping it in the initial scope would widen
the first artifact's design surface (operation/variable identity, partial-success
semantics, GraphQL-client integration, and SSR hydration for GraphQL reads) while
the core resource lifecycle is still being proven against the simpler HTTP case.
Deferring GraphQL keeps the MVP closer to TanStack Query and RTK Query, lets the
transport-neutral core stabilize first, and then adds GraphQL as the first proof
that the core genuinely is transport-neutral.

Apollo and Relay are the benchmarks for this phase. They show the normalized
graph-cache path: powerful for GraphQL and entity-heavy applications, but too much
even for the first GraphQL slice. When GraphQL lands it should first deliver
operation-level resource caching — the same lifecycle the HTTP core already
provides — and only later, if an app's data model justifies it, the normalized
graph cache, entity identity policy, fragment store, and automatic graph-derived
invalidation associated with a full GraphQL client (Hasura-/Apollo-/Relay-style).
Normalized graph storage stays a separate later artifact because it needs entity
identity policy, fragment semantics, cache writes, partial data rules, and
graph-derived invalidation.

### GraphQL Transport Shape

GraphQL reads would use the **same resource lifecycle** as HTTP reads. The
proposed resources-artifact transport `:rf.graphql/query` describes the operation;
cache identity still comes from scope, resource id, and canonical params.
`:rf.graphql/query` is new resources-artifact API, not an existing core fx;
GraphQL-specific batching, persisted queries, and client-cache behavior all layer
above the transport boundary. Its implementation may lower to managed HTTP or a
configured GraphQL client/endpoint, but the resource runtime stays the owner of
identity, staleness, dedupe, invalidation, SSR hydration, and tool metadata.

The resource runtime would lower an ensure/refetch into a GraphQL operation:

```clojure
[:rf.graphql/query
 {:request-id     request-id
  :operation-name "UserProfile"
  :document       user-profile-query
  :variables      {:id "u-42"}
  :on-success     [:rf.resource.internal/succeeded
                   {:resource-key resource-key
                    :scope        scope
                    :rf.frame/id  frame-id
                    :generation   generation}]
  :on-failure     [:rf.resource.internal/failed
                   {:resource-key resource-key
                    :scope        scope
                    :rf.frame/id  frame-id
                    :generation   generation}]}]
```

Equivalently, lowered through the configured GraphQL client/endpoint:

```clojure
{:operation-kind :query
 :operation-name "UserProfile"
 :document       user-profile-query
 :variables      {:id "u-42"}
 :request-id     request-id
 :on-success     [:rf.resource.internal/succeeded
                  {:resource-key resource-key
                   :scope        scope
                   :generation   generation
                   :rf.frame/id  frame-id}]
 :on-failure     [:rf.resource.internal/failed
                  {:resource-key resource-key
                   :scope        scope
                   :generation   generation
                   :rf.frame/id  frame-id}]}
```

The GraphQL adapter must preserve the same resource invariants as HTTP:

- generation checks are the correctness boundary;
- abort is best-effort;
- stale suppression is mandatory;
- route and SSR ownership are unchanged;
- scopes and params still define resource identity;
- operation/document/variable summaries are visible to Xray with the same
  privacy and size elision as HTTP request metadata.

GraphQL support in this phase means operation-level resource caching, route
ownership, SSR hydration, stale/fresh policy, dedupe, invalidation, redaction, and
Xray visibility for GraphQL reads. It does not mean Apollo/Relay-style normalized
entity caching.

GraphQL partial success needs explicit policy. A response with both `data` and
`errors` is a distinct GraphQL partial-success case, not automatically the same as
HTTP failure: it may become loaded data plus a transport warning, a
`:refresh-error` while preserving prior data, or a first-load `:error`, depending
on resource policy. It must not be silently treated as ordinary success.

### Deferred Example: GraphQL Resource

```clojure
(rf/reg-resource
  :user/profile
  {:params-schema
   [:map [:user-id :string]]

   :data-schema
   :app/user-profile

   :transport
   :rf.graphql/query

   :operation-name
   "UserProfile"

   :document
   user-profile-query

   :variables
   (fn [{:keys [user-id]} _ctx]
     {:id user-id})

   :stale-after-ms
   30000

   :tags
   (fn [_params {:keys [user]}]
     #{[:user/id (:id user)]})})
```

Rules:

- every variable that changes the remote identity must be represented in
  resource params;
- operation name, document hash, variables, and GraphQL endpoint/client id are
  transport evidence, not a replacement for resource identity;
- `{data ..., errors ...}` is a distinct GraphQL partial-success case, not
  automatically the same as HTTP failure;
- this phase caches the operation result as a resource document. It does not
  maintain a normalized entity graph.

### Deferred Example: GraphQL Mutation

When the GraphQL phase lands (after the HTTP mutation slice), the proposed
`:rf.graphql/mutation` transport should use the same mutation runtime,
invalidation, and trace semantics as HTTP mutations.

```clojure
(rf/reg-mutation
  :user/update-name
  {:params-schema
   [:map
    [:id :string]
    [:name :string]]

   :transport
   :rf.graphql/mutation

   :operation-name
   "UpdateUserName"

   :document
   update-user-name-mutation

   :variables
   (fn [{:keys [id name]} _ctx]
     {:id id
      :name name})

   :invalidates
   (fn [{:keys [id]} _result]
     #{[:user/id id]})})
```

### Deferred GraphQL Conformance

When the GraphQL phase is built, its conformance fixtures should add to the HTTP
suite:

- GraphQL resource requests preserve generation/frame stale-suppression parity
  with HTTP;
- GraphQL partial-success (`data` + `errors`) policy resolves to loaded data,
  `:refresh-error`, or first-load `:error` per resource policy and never silent
  success;
- GraphQL operation/document/variable summaries are redacted to Xray with the
  same privacy/size elision as HTTP request metadata;
- GraphQL reads participate in route ownership, SSR hydration, dedupe, and tag
  invalidation identically to HTTP reads.

## Backwards Compatibility

This EP adds a new optional artifact (`day8/re-frame2-resources`); it does not
change existing core APIs. Apps that do not require it are unaffected, and the
existing patterns it supersedes — Pattern-RemoteData plus managed HTTP (Spec 014)
— keep working for apps that have not adopted resources.

Within the pre-alpha posture there are no compatibility shims. The artifact stores
its cache in the framework-owned runtime partition (`:rf.runtime/resources`); it
relies on the final partition vocabulary from the [App/Runtime Partition
EP](EP-0001-frame-partitions.md) rather than any interim `:rf/runtime` location.
Resource registration, subscriptions, and events are new surfaces, so there is no
prior resource API to keep compatible.

## Migration

There is no in-repo resource API to migrate from; migration is adoption.

- **From hand-rolled RemoteData.** Apps currently expressing server state with
  Pattern-RemoteData plus `:rf.http/managed` move each remote read to a
  `reg-resource` registration, replacing ad-hoc cache keys, in-flight flags, and
  staleness bookkeeping with resource identity, status, and tag invalidation.
- **From `shipclojure/re-frame-query`.** Existing re-frame apps using that library
  map query keys to resource identity (canonical params), `invalidateQueries` to
  tag invalidation, and component-level observers to active owners. The
  [Sources Consulted](#sources-consulted) prior-art mapping is the migration
  reference; a guide chapter and migration notes are part of the docs bead.
- **Route and SSR loading.** Component-tree request waterfalls move to route
  `:resources` and SSR blocking-resource drain, so adoption is per-route rather
  than all-at-once.

The slice order — read-resource MVP first, mutations at the first public-beta
gate — is in [Acceptance Criteria And Rollout](#acceptance-criteria-and-rollout)
and [Bead Structure](#bead-structure) below.

## Reference Implementation Plan

### 1. Artifact and Namespaces

Add an optional artifact:

```text
implementation/resources/
```

Likely namespaces:

```text
re_frame.resources
re_frame.resources.registry
re_frame.resources.state
re_frame.resources.work_ledger
re_frame.resources.events
re_frame.resources.transport
re_frame.resources.transport.http
re_frame.resources.subs
re_frame.resources.route
re_frame.resources.ssr
re_frame.resources.test-support
```

The deferred GraphQL phase adds a `re_frame.resources.transport.graphql`
namespace; it is not part of the initial HTTP-only build (see
[Deferred: GraphQL (later phase)](#deferred-graphql-later-phase)).

Facade integration:

- add `implementation/core/src/re_frame/core_resources.cljc`;
- expose `reg-resource`, `clear-resource`, `resource-meta`,
  `resource-state`, and `resources`;
- add feature probe `:resources/reg-resource`;
- expose the landed mutation facade: `reg-mutation`, `clear-mutation`,
  `mutation-meta`, `mutation-state`, and `mutations`.

### 2. Registrar Kinds

Add registrar kind:

```clojure
:resource
```

Add later:

```clojure
:mutation
```

Do not add `:query` as a public kind. It creates unnecessary vocabulary
collision with route query params and with prior-art implementation names.

### 3. Runtime State

Use target runtime-db child path:

```clojure
[:rf.runtime/resources]
```

In a full frame-state projection, that appears at:

```clojure
[:rf.db/runtime :rf.runtime/resources]
```

There is no interim app-db path: post-[EP-0001](EP-0001-frame-partitions.md) a
`:rf/runtime` app-db root is a hard error (`:rf.error/legacy-runtime-root`).
Runtime-db is the only location.

Store serializable state in frame-state:

- entries;
- status;
- data;
- errors after elision policy;
- timestamps;
- generations;
- active owners;
- scopes;
- causes/history summaries;
- tags;
- indexes.

Store serializable resource work records in a frame work ledger. The first
implementation may keep the namespace private to the resources artifact, but the
runtime child should be named neutrally:

```clojure
[:rf.runtime/work-ledger]
```

Work records include:

- work id;
- work kind;
- linked resource key;
- generation;
- frame id;
- status;
- owners;
- causes;
- cancellable?;
- timestamps and deadlines;
- outcome summary.

Store host handles in side tables keyed by frame and work id:

- AbortControllers;
- timeout handles;
- polling timers;
- transport-specific handles;
- promise handles.

Frame destroy must clean side tables.

### 4. Core Events

Implement public events:

```clojure
:rf.resource/ensure
:rf.resource/refetch
:rf.resource/invalidate-tags
:rf.resource/release-owner
:rf.resource/clear-scope
:rf.resource/remove
```

Implement internal events:

```clojure
:rf.resource.internal/succeeded
:rf.resource.internal/failed
:rf.resource.internal/aborted
:rf.resource.internal/gc-fired
:rf.resource.internal/stale-suppressed
```

`ensure` algorithm:

1. resolve resource metadata;
2. validate and canonicalize params;
3. resolve and validate cache scope;
4. compute `[cache-scope resource-id canonical-params]`;
5. attach owner if supplied;
6. record cause if supplied;
7. if entry is fresh, no-op after owner update and emit cache-hit trace;
8. if current work is in flight, join its ledger row after owner update and emit
   dedupe trace;
9. allocate a new generation and work id when new work is needed;
10. record a work-ledger row with status `:running`;
11. transition the resource entry to `:loading` or `:fetching`;
12. issue the selected built-in transport effect;
13. record generation, work id, request id, and trace data.

### 5. Managed HTTP Integration

For `:transport :rf.http/managed` — the only initial-scope transport — lower a
resource request into managed HTTP.

The reply event must carry enough data to verify generation and frame:

```clojure
[:rf.resource.internal/succeeded
 {:work-id      work-id
  :resource-key resource-key
  :scope        scope
  :generation   generation
  :rf.frame/id  frame-id}]
```

If work id or generation does not match, suppress the reply and emit trace
metadata. A stale reply must never overwrite newer data.

The deferred GraphQL phase adds `:transport :rf.graphql/query` integration that
lowers the resource into a GraphQL operation while preserving these same
invariants; its lowering shape and partial-success policy are in
[Deferred: GraphQL (later phase)](#deferred-graphql-later-phase).

### 6. Subscriptions

Register passive subscriptions:

```clojure
:rf.resource/state
:rf.resource/data
:rf.resource/status
:rf.resource/error
:rf.resource/refresh-error
:rf.resource/loading?
:rf.resource/fetching?
:rf.resource/stale?
:rf.resource/has-data?
:rf.resource/previous-data
```

No v1 subscription should fetch. If a future `:rf.resource/live` is added, it
must be explicitly documented as side-effecting convenience and kept separate
from the recommended route/event pattern.

Subscriptions resolve scope **purely** — from the payload `:scope`, else from a
sub-resolvable spec policy (`:rf.scope/global` or a fn-of-nothing/data resolver).
A sub that cannot resolve a scope raises `:rf.error/resource-sub-unresolved-scope`
rather than reading `[:rf.scope/global]` or returning a silent `:idle` (see
[Subscription-side scope resolution](#subscription-side-scope-resolution)). Subs
do **not** run `(route, ctx)` resolvers — they have no event/route context.

### 7. Route and SSR Integration

Routing changes:

1. reserve `:resources` in route metadata;
2. compute route resource plans after match;
3. evaluate `:when` and dependency ordering;
4. resolve scopes and params;
5. attach owners with `[:route route-id nav-token]`;
6. dispatch ensures with route-entry causes;
7. track blocking resources by nav-token;
8. release owners on route leave or superseded nav-token;
9. keep existing `:on-match` behavior.

SSR changes:

1. add a drain/wait point for blocking route resources;
2. project resource runtime state into the hydration payload;
3. redact or omit sensitive values;
4. prevent client double-fetch for fresh hydrated entries.

### 8. Xray and Tool Surfaces

Tools should receive summaries:

```clojure
{:resource-key [[:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
                :article/by-slug
                {:slug "welcome"}]
 :scope        [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
 :status       :loaded
 :has-data?    true
 :stale?       true
 :active-owners #{[:route :route/article nav-token]}
 :causes       [[:route-entry :route/article nav-token]]
 :tags         #{[:article "welcome"]}
 :refresh-error {:kind   :rf.http/http-5xx
                 :status 503}
 :data-summary {:schema :app/article
                :redacted? true
                :size 14231}}
```

Raw data access should go through existing egress and elision rules.

### 9. Tests

Initial conformance fixtures should cover:

- registration and metadata;
- params schema validation;
- params canonicalization;
- scope policy is required: a `reg-resource` with no `:scope` is a loud
  registration error (`:rf.error/resource-missing-scope-policy`);
- `:rf.scope/from-caller` resource dispatched/subscribed without a per-call
  `:scope` is a loud use-time error;
- a subscription that cannot resolve a scope raises
  `:rf.error/resource-sub-unresolved-scope` (never a silent global read / `:idle`);
- scope-mismatch lint fires when an entry under scope A and a live sub under
  scope B share resource+params;
- scope map canonicalization: key order does not change cache identity;
- cache scope validation and clearing;
- managed HTTP resource requests keep Spec 014 keys such as `:decode`,
  `:accept`, and `:retry` at the top level of the managed-HTTP args map;
- managed HTTP resource requests reject caller-supplied `:request-id`,
  `:on-success`, and `:on-failure`;
- scope switch/clear while requests are in flight;
- first load `:loading`;
- background refresh `:fetching`;
- background refresh failure records `:refresh-error` and preserves data;
- success and failure transitions;
- stale/fresh behavior;
- derived status flags do not drift from durable facts;
- work-ledger rows are serializable and do not contain host handles;
- host handles are keyed by frame and work id, and frame destroy cleans them;
- structural sharing preserves unchanged data values;
- inactive GC;
- duplicate ensure dedupe;
- dedupe traces joined owners and request id;
- duplicate ensure joins the current work-ledger row instead of allocating
  parallel work;
- forced refetch supersedes or suppresses older in-flight generations;
- stale reply suppression checks both work id and generation;
- active owner release;
- cause does not create liveness;
- Xray/tool inspection does not create an owner;
- exact tag invalidation;
- invalidation trace records matched keys and decisions;
- invalidation while a matching request is in flight;
- tag index replacement after successful reload;
- active invalidated resource refetch;
- inactive invalidated resource only marked stale;
- route entry ensure;
- route metadata validation accepts the framework-owned `:resources` key only
  when the resources route integration is loaded;
- route `:when` skips without sentinel params;
- dependent route resource ordering;
- route `:keep-previous?` reports previous data without polluting new-key cache;
- route leave owner release;
- route supersession via nav-token;
- blocking route resource failure and timeout behavior;
- SSR preload;
- hydration no-double-fetch;
- hydration omitted/redacted-data refetch behavior;
- hydration scope isolation;
- the `:tag-index` / `:owner-index` are recomputed from `:entries` on hydration
  and never trusted from the serialized snapshot;
- epoch restore settles non-terminal restored work-ledger rows to dangling,
  clears the entry's `:current-work`, and settles the entry to its last stable
  status;
- the generation allocator is monotonic across restore: a post-restore
  allocation strictly exceeds any pre-restore generation;
- a pre-restore in-flight reply that lands after `restore-epoch!` is suppressed by
  the work-id + generation check and cannot mutate a post-restore entry;
- restore does not eagerly refetch; restored entries refetch only on the next
  live-owner `ensure`, and a restored epoch double-fetches nothing;
- owner reconciliation on restore revives machine/route/lease owners that the
  restored runtime state names live and orphans the rest (stale nav-tokens,
  SSR owners);
- host timers and abort handles are cleared on restore and re-armed lazily from
  durable timestamps;
- terminal work-ledger rows are pruned on the entry's next successful transition
  and the ledger stays bounded across epoch/SSR snapshots;
- stale suppression keys on `:work/id` (no separate `:stale-key` synonym);
- frame isolation;
- mutation patch/populate then invalidation;
- cache growth and GC limits for list resources;
- trace redaction and pruning for params/scopes;
- redacted tool summaries.

## Rejected Ideas

### A. Pattern Only

Keep Pattern-RemoteData plus managed HTTP and write a stronger cookbook.

This is low risk, but it does not solve cache identity, invalidation, active
ownership, GC, route graphs, SSR hydration, or Xray visibility. It is not enough
for a best-in-class SPA library.

### B. Adopt or Recommend shipclojure/re-frame-query

This is useful prior art and could be recommended for current re-frame apps.
It should not be the re-frame2 answer because it cannot own re-frame2 frames,
runtime partitions, route metadata, SSR, Xray, privacy, and event-causal traces
without becoming a re-frame2 artifact in practice.

### C. Optional First-Class Resource Artifact

This proposal.

It adds real API surface, but it removes repeated app-level machinery and gives
tools a stable contract. Pre-alpha posture favors building the right primitive
when the problem is real and recurring.

### D. Normalized Cache First

Start with Apollo/Relay-style entity normalization.

Too heavy for v1. Keep it as a later scale-gated enhancement.

### E. Projection First

Before implementing the primitive, derive a route/resource/work graph from
existing route metadata, managed HTTP metadata, traces, and RemoteData
conventions.

This is useful as an Xray bead and can reveal app patterns, but it does not
replace the runtime primitive.

### F. Resource-Local In-Flight Registry

Keep all in-flight bookkeeping under `:rf.runtime/resources` and let resources
own their own private request registry.

This is simpler for the first HTTP slice, but it repeats the same async
ownership problem that route loaders, timers, streams, spawned actors, and
machine async work will face. EP-0003 should provide the first work-ledger
consumer without making the ledger resource-specific. Resource cache entries can
point at current work, but work attempts need their own neutral records and
side-table handles.

## Acceptance Criteria And Rollout

Build Option C in slices, starting with a read-resource MVP. The artifact should
be judged against the benchmark dimensions above, not only against whether it
removes boilerplate from current examples.

The v1 artifact should be considered acceptable when it includes:

- `reg-resource`;
- `clear-resource`;
- passive resource state subscriptions;
- map-payload ensure/refetch/invalidate/remove events;
- active owners;
- non-liveness causes;
- explicit cache scopes and scope clearing;
- frame work-ledger records for in-flight resource attempts, with host handles
  kept in side tables;
- route `:resources`;
- managed HTTP as the single built-in transport;
- canonical params;
- exact tag invalidation;
- stale/fresh policy;
- inactive GC;
- stale/GC timer policy;
- dedupe;
- stale reply suppression;
- `:refresh-error` for background-refresh failures;
- conditional route resources;
- previous-data support for ordinary paginated/filter resources;
- structural sharing for equal decoded data;
- SSR preload/hydration;
- Xray/tool metadata and resource trace operations.

The first public-beta gate has landed with:

- `reg-mutation`;
- `clear-mutation`;
- focus/reconnect revalidation;
- mutation invalidation integration.

The following capabilities are explicitly deferred beyond those first slices:

- GraphQL read/mutation transport — the first follow-on phase (see
  [Deferred: GraphQL (later phase)](#deferred-graphql-later-phase));
- optimistic rollback;
- generic transport extension protocol;
- polling/interval revalidation;
- infinite resources;
- normalized caches;
- automatic graph-derived invalidation;
- subscription-driven fetching;
- offline persistence;
- cross-tab broadcast.

## Benchmark Positioning

It should not be sold as "TanStack Query but more mature." It will not be more
mature on day one.

The honest claim is:

> TanStack Query is the gold standard for general server-state cache behavior.
> re-frame2 can match the core semantics that apply, and be better for
> re-frame2 apps because resources live inside the same event, frame, route,
> SSR, schema, trace, Xray, and privacy model as the rest of the application.

That is the standard this EP sets. Matching the benchmark means users get the
expected resource-management capabilities: stale/fresh state, cache keys,
dedupe, refetch triggers, invalidation, inactive retention, hydration, mutations,
and later optimistic and infinite-query support. Exceeding the benchmark means
the same behavior is causally explained, route-aware, frame-local, SSR-safe,
privacy-aware, AI-inspectable, and integrated with state machines rather than
hidden inside view lifecycle.

Structural advantages inside re-frame2:

- frame-local caches for app frames, story frames, test frames, and SSR request
  frames;
- route-declared data dependencies;
- event-causal traces;
- explicit owner/cause separation, so liveness and causality do not blur;
- a frame work ledger that makes in-flight work, cancellation attempts, stale
  suppression, and SSR wait points inspectable;
- cache scopes for auth, tenant, locale, impersonation, and SSR correctness;
- runtime-owned state that ordinary app-db writes cannot clobber;
- a built-in managed-HTTP transport over a transport-neutral resource lifecycle
  (with the deferred GraphQL transport plugging into the same lifecycle);
- schema-aware params and decoded data;
- derived projections through ordinary subscriptions instead of a query-local
  `:select` hook;
- time-travel and SSR through frame-state;
- Xray visibility over decisions, not just final state;
- AI-readable metadata and redacted values;
- FSM escalation for lifecycles that deserve it.

Where TanStack and alternatives remain ahead:

- maturity;
- ecosystem defaults;
- infinite query breadth;
- offline and persistence plugins;
- cross-tab broadcast;
- normalized GraphQL cache ecosystems;
- years of production edge cases.

The goal is not imitation. The goal is to make server state a first-class
re-frame2 runtime process.

## Open Issues

1. Should the file and guide title say "resources", "resource queries", or
   "server state"?
   Recommendation: API says resources; docs can use "resource queries" when
   comparing to prior art.
2. Should `reg-mutation` land with v1 or as the next slice?
   Resolved: read-resource MVP first; `reg-mutation`, `clear-mutation`, and
   mutation invalidation landed in the first public-beta gate alongside
   focus/reconnect revalidation.
3. What exact route `blocking?` behavior should client navigation expose?
   Recommendation: route transition pending and SSR wait; do not block URL
   commit.
4. How should resource hydration project data from `:rf.db/runtime`?
   Recommendation: explicit resource projection hook using schema and
   sensitivity metadata; never serialize all runtime state by default.
5. Should `:cache-key` exist?
   Recommendation: no in v1. Canonical params are the identity.
6. Should subscription-driven fetching exist?
   Recommendation: not in v1. Reconsider later as explicit convenience.
7. What exact shape should `:refresh-error` carry?
   Recommendation: use the same error envelope shape as `:error`, plus
   timestamp/attempt metadata if useful. `:status :error` is reserved for
   first-load failure with no usable data.
8. Should GraphQL be part of the initial scope, or deferred to a later phase?
   Recommendation: defer GraphQL. The initial scope is HTTP-only over managed
   HTTP; the GraphQL read transport (`:rf.graphql/query`) is the first follow-on
   phase once the HTTP core lands, ahead of the generic transport extension
   protocol. See [Deferred: GraphQL (later phase)](#deferred-graphql-later-phase).
9. What is the cache scope shape?
   Recommendation: make scope explicit EDN and the first element of the resource
   key. **Resolved (`rf2-6rrz53`, fail-closed):** there is no silent default —
   every resource declares an explicit scope **policy** at registration
   (`:rf.scope/global` | resolver | `:rf.scope/from-caller`); no policy is a loud
   registration error, and `:rf.scope/global` is an explicit, auditable claim
   rather than a framework default. Subscriptions resolve scope from the payload
   or a sub-resolvable spec policy and raise a loud structured error otherwise,
   never a silent global read. `clear-scope` remains the causal operation for
   logout/account changes. See [Scope Resolution](#scope-resolution).
10. Should owners also represent causes?
    Recommendation: no. Owners are liveness leases; causes are trace metadata.
11. Should Xray ever become an owner?
    Recommendation: no for inspection. A future explicit debug pin may be a
    tool mutation with trace evidence.
12. How much previous-data support belongs in v1?
    Recommendation: support `:keep-previous?` for ordinary route/list churn;
    keep arbitrary placeholder data deferred.
13. Should the frame work ledger be a separate EP before resources?
    Recommendation: no. EP-0003 should define the resource-owned ledger slice
    first, with a neutral `:rf.runtime/work-ledger` shape. Split it into a
    separate EP only when later work actually extends it beyond resources and
    managed HTTP to timers, streams, spawned actors, route loaders, or machine
    async work.

## Recommendation

Adopt **Option C**: build a first-class optional `day8/re-frame2-resources`
artifact for declarative server-state, starting with a read-resource MVP and
then extending it through the first public-beta mutation/revalidation gate.

This is the re-frame2 answer to TanStack Query / RTK Query / SWR /
`shipclojure/re-frame-query`: resource identity, caching, staleness, dedupe, tag
invalidation, active-owner lifecycle/GC, and route + SSR preload, built on managed
HTTP (Spec 014), a frame work ledger, and the framework-owned runtime partition.
Pattern-only cookbooks, adopting `shipclojure/re-frame-query` wholesale,
normalized-cache-first, and projection-first were each rejected because none owns
re-frame2 frames, runtime partitions, route metadata, SSR, Xray visibility,
privacy, and event-causal traces the way a first-class artifact does. The
pre-alpha posture favors building the right primitive when the problem is real
and recurring; this one is.

## Bead Structure

The initial HTTP-only scope is the bead sequence below. GraphQL beads are a
deferred follow-on phase (see
[Deferred: GraphQL (later phase)](#deferred-graphql-later-phase)).

1. EP/spec bead: landed for the HTTP-only scope; Spec 016 is the normative
   home for that scope, and this EP graduated to `final` on the
   accepted→final ruling (`rf2-9l9xs2`), retaining its role as the design
   record and the home of the deferred GraphQL phase.
2. Artifact skeleton bead: create `day8/re-frame2-resources`, facade wrappers,
   feature probes, and `:resource` registrar metadata.
3. Work-ledger substrate bead: add the resource-owned frame work ledger slice,
   serializable work records, host side tables keyed by work id, owner release
   rules, cancellation attempts, stale-reply suppression keyed by `:work/id`, frame-destroy cleanup,
   and tool/SSR summaries.
4. Resource runtime bead: entries, cache scopes, canonical params, status
   transitions, structural sharing, passive subscriptions, and frame-local
   state linked to current work ids.
5. Managed HTTP bead: ensure/refetch/success/failure over `:rf.http/managed`,
   dedupe, ledger joins, generation/work-id checks, and stale reply suppression.
6. Invalidation/GC bead: tags, active owners, owner indexes, stale marking,
   active refetch, causes, stale/GC timer policy, scope clearing, ledger owner
   release, cancellation attempts, and inactive GC.
7. Route integration bead: `:resources`, nav-token owners, blocking resources,
   `:when`, dependent route resources, `:keep-previous?`, release on leave, and
   preserved `:on-match` behavior.
8. SSR/hydration bead: blocking resource drain through ledger records, resource
   projection, redaction, scope isolation, projection metadata, work summaries,
   and hydration no-double-fetch.
9. Xray/tool/privacy bead: resource registry panel, route/resource graph,
   work-ledger table, lifecycle timeline, invalidation graph, cache growth view,
   summaries, trace operations, egress policy, and redacted accessors.
10. Focus/reconnect bead: active-stale scan on browser focus and network
   reconnect, expressed as resource events with trace records.
11. Mutation bead (HTTP): `reg-mutation`, HTTP mutation execution over
    `:rf.http/managed`, mutation instance state, patch/populate APIs,
    invalidation, and trace hooks for later optimistic rollback.
12. Docs/examples bead: guide chapter, API docs, migration notes from
    `shipclojure/re-frame-query`, route-driven example, SSR example, and HTTP and
    machine-owned resource examples.

**Upstream sequencing (both now satisfied).** Two framework prerequisites gate
the runtime beads above, and both have **landed on main**:

- **rf2-3939ig — generalized framework-write authority (✅ satisfied).** The
  work-ledger bead (3) and resource runtime bead (4) write the `:rf.db/runtime`
  effect, so they depend on the generalized `:rf/framework-authority? true`
  registration-meta mechanism that lets a non-machine subsystem mint
  event-handler authority. That mechanism shipped under rf2-3939ig (it had to,
  because routing was already tripping `:rf.warning/app-handler-runtime-effect`
  on every navigation). The resource registration sites stamp the key per the
  [Write Authority](#write-authority) section; **no rework is required** — the
  beads build on a landed mechanism.
- **rf2-6nn8bi — runtime-subsystem contract (✅ satisfied).** The graduation of
  `:rf.runtime/resources` and `:rf.runtime/work-ledger` (the
  [Runtime-Subsystem Graduation](#runtime-subsystem-graduation) tables) grades
  against the five-clause contract that landed normatively at
  [`spec/Runtime-Subsystems.md`](../../spec/Runtime-Subsystems.md) under
  rf2-6nn8bi. Acceptance review of the runtime beads should treat both clause
  conformance and the still-open clause-2 multi-writer authority question for
  `:rf.runtime/work-ledger` as gated by that contract.

Acceptance review should therefore see this coupling as **already resolved on
main**: this amendment cites both landed prerequisites rather than waiting on
them.

Deferred GraphQL-phase beads (after the HTTP core lands):

- GraphQL transport bead: `:rf.graphql/query`, operation metadata, variables,
  partial-success policy, SSR hydration, trace summaries, and parity with the
  resource lifecycle used by HTTP.
- GraphQL mutation bead: `:rf.graphql/mutation` execution reusing the HTTP
  mutation runtime, invalidation, and trace semantics.
- GraphQL docs/examples bead: GraphQL resource and mutation examples added to the
  guide and EP.

## Sources Consulted

- [TanStack Query: Important Defaults](https://tanstack.com/query/v5/docs/framework/react/guides/important-defaults)
- [TanStack Query: Query Keys](https://tanstack.com/query/v5/docs/framework/react/guides/query-keys)
- [TanStack Query: Query Invalidation](https://tanstack.com/query/v5/docs/framework/react/guides/query-invalidation)
- [TanStack Query: Optimistic Updates](https://tanstack.com/query/v5/docs/framework/react/guides/optimistic-updates)
- [TanStack Query: Prefetching and Router Integration](https://tanstack.com/query/v5/docs/framework/react/guides/prefetching)
- [TanStack Query: Server Rendering and Hydration](https://tanstack.com/query/v5/docs/framework/react/guides/ssr)
- [RTK Query: Cache Behavior](https://redux-toolkit.js.org/rtk-query/usage/cache-behavior)
- [RTK Query: Automated Re-fetching](https://redux-toolkit.js.org/rtk-query/usage/automated-refetching)
- [RTK Query: Manual Cache Updates](https://redux-toolkit.js.org/rtk-query/usage/manual-cache-updates)
- [SWR: Data Fetching](https://swr.vercel.app/docs/data-fetching)
- [SWR: Automatic Revalidation](https://swr.vercel.app/docs/revalidation)
- [SWR: Mutation and Revalidation](https://swr.vercel.app/docs/mutation)
- [Apollo Client: Caching](https://www.apollographql.com/docs/react/caching/overview)
- [Relay: Staleness of Data](https://relay.dev/docs/guided-tour/reusing-cached-data/staleness-of-data/)
- [shipclojure/re-frame-query](https://github.com/shipclojure/re-frame-query)
