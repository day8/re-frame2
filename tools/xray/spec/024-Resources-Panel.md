# 024-Resources-Panel

The Xray surface for **declarative server-state** — the consumer-side
panel + tool-accessor + trace-family contract for the framework's
optional Resources artefact ([`spec/016-Resources.md`](../../../spec/016-Resources.md)
§Xray and AI tooling). The "where is my server state, what owns it, and
is it stale?" lens.

> **Owning framework spec.** [`spec/016-Resources.md`](../../../spec/016-Resources.md)
> is the normative source for *what a resource IS* (identity, scope
> policy, status FSM, owners vs causes, the work ledger, invalidation,
> GC, SSR/restore). This doc is the Xray-side consumer contract: the
> panel sections, the `:rf.xray/*` registry surface, the `:rf.resource/*`
> trace family Xray colours/filters, the five tool accessors, and the
> privacy posture Xray applies. Where this doc and Spec 016 differ, Spec
> 016 governs the server-state semantics; this doc governs Xray's
> presentation of them.

## Bug class

> **Bug class:** "My page shows a stale article / a permanent skeleton /
> a refetch storm and I cannot see WHY. Which route owns this read? Is it
> stale or just fetching? Did the last background refresh fail? Is this
> cache growing without bound? Is a `/me` read accidentally global?"
> **Insight Xray provides:** the resource cache, the in-flight work
> ledger, the route/resource ownership graph, the lifecycle timeline, the
> invalidation graph, the cache-growth view, and the scope audit — all as
> data, all summarized (never raw), all read-only.

## Decoupling — the PANEL reads decoupled; the ARTEFACT hard-depends for the Derivation Graph

Two distinct facts that must not be conflated:

- **The Resources PANEL (this doc, 024) reads the runtime/registry data
  decoupled** — it does NOT `:require` `re-frame.resources.*`; it reads
  the static registry, the live runtime-db cache/ledger slice, and the
  trace family through the spine seams the way the Routing tab reads the
  route slice and the Machine Inspector reads machine snapshots. An app
  that omits the Resources artefact still sees the panel render its
  silent-by-default caption.
- **The Xray ARTEFACT hard-depends on `day8/re-frame2-resources`**
  (`tools/xray/deps.edn` declares it under the rf2-1fc459 rationale). The
  dep exists for a SEPARATE surface — the Derivation-Graph tab (025),
  EP-0014's named first consumer — which `:require`s
  `re-frame.resources.tooling` (the `resource-algebra-view` /
  `resource-cache-algebra-view` projections) to feed the `:resources`
  contributor of the cross-family graph composer. Resources is no longer
  dependency-optional for Xray; the resources artefact depends only on
  core, its tooling body is `interop/debug-enabled?`-gated + DCE'd, and
  Xray is dev-only via `:devtools/preloads`, so the dep never reaches
  production bundles (the bundle-isolation gate confirms this). The
  Derivation-Graph tab needs the algebra-view PROJECTION; the panel below
  needs only the raw runtime-db slice — hence the panel's read path stays
  decoupled even though the artefact pulls the dep.

The panel reads everything **decoupled** — exactly the posture the
Routing tab uses for the route slice and the Machine Inspector uses for
machine snapshots:

- the **static registry** via `(rf/registrations :resource)` (the
  process-global registrar);
- the **named resource-scope resolver registry** via
  `(rf/registrations :resource-scope)` (rf2-hls77w, EP-0016 D3) — the
  resolver id, its declared `{:inputs … }` (input names + `[:db <rf-path>]`
  sources, paths summarized), and the whole-db-sugar cost flag. Per-resolution
  input **values** and the resolved scope are **never** read from the
  registry; they surface only via the egress-projected
  `:rf.resource/scope-resolved` trace op (a scope carries PII — see below);
- the **live per-frame instance table** from the runtime-db partition
  slice at `[:rf.runtime/resources :entries]` (EP-0001 — the resource
  cache is framework-owned runtime-db state, never app-db), sourced from
  `:rf.xray/target-frame-runtime-db`;
- the **live per-frame work ledger** from `[:rf.runtime/work-ledger]`;
- the **trace stream** (`:rf.resource/*` rows) from
  `:rf.xray/trace-buffer`.

The Resources panel surface (`panels/resources.cljs`,
`panels/resources_helpers.cljc`, the `runtime.cljs` Resources accessors)
does **not** `:require` `re-frame.resources.*` — the reserved runtime-db
key paths are small duplicated literals in
`panels/resources_helpers.cljc`, the price of keeping the panel's read
path free of a require edge into the resources artefact. (The
Derivation-Graph tab `panels/derivation_graph.cljs` DOES `:require
re-frame.resources.tooling` for its algebra-view projection — see the
section above; that is a separate surface, and the panel's read path
stays decoupled regardless.) An app that omits the Resources artefact
sees the panel render its silent-by-default caption.

## Read-only — Xray MUST NOT become an owner by observing

Per Spec 016 §Active owners and causes, opening this panel pins
**nothing**. Every section is a pure read; the panel registers **no**
`:rf.resource/*` event and dispatches none. Inspection never attaches an
owner, refetches, extends GC, or alters polling. (A future explicit "pin
this resource" debug action would be its own traced tool mutation, not
normal inspection.)

## PRIVACY — summaries, never raw values

Spec 016 §Xray and AI tooling is load-bearing here: tool surfaces
**prefer summaries over raw values**, and **params, scopes, AND data get
the SAME privacy + size elision** — a scope carries user ids, tenant ids,
locale, and impersonation markers, so it is exactly as sensitive as data.

Two elision layers compose:

1. **In-panel summary** (`resources-helpers/summarize`, always applied).
   Every param / scope / data / cause / outcome value renders as a
   `{:type :size :preview :elided? :redacted? :large?}` shape — a bounded
   `pr-str` preview (tail elided past the budget), never the raw value. A
   value the runtime already redacted/elided upstream (the framework
   `:rf/redacted` / `:rf.size/large-elided` sentinels emitted for
   `:sensitive?` / `:large?` slots via `elide-wire-value`) keeps its
   sentinel status and renders `[redacted]` / `[large — elided]` with no
   raw preview.
2. **Off-box egress** (the tool accessors). Per-slot, NOT per-entry. A
   resource cache entry mixes **payload-bearing** slots (`:data` /
   `:error` / `:refresh-error`, and the key's scope/params — the only
   slots that can carry PII or a large blob) with **metadata** slots
   (`:status`, `:generation`, `:attempt`, `:request-id`, `:current-work`,
   `:active-owners`, `:tags`, `:loaded-at` / `:stale-at` /
   `:invalidated-at`) — non-PII runtime bookkeeping. The accessors project
   the **metadata BEFORE egress redaction** and route **only the payload
   values** through the framework `egress-runtime-db-value` walker (the
   resource cache is runtime-db state, so the off-box default REDACTS those
   payload values per Spec 011 §Off-box redaction, ruling #14; a
   trusted-local caller opts in with `:include-runtime-db? true`, and even
   then per-slot `:sensitive?` / `:large?` declarations still elide, and a
   value already redacted/elided upstream keeps its sentinel). **A redacted
   summary therefore STILL exposes the metadata** (the EP-0003 tool
   contract; Spec 016 §Xray, "Xray sees redacted summaries, not raw
   values"): the `:status` / `:tag` / `:owner` / `:request-id` filters
   (which filter the projected rows) work on the default path **without**
   `:include-runtime-db?`, and the rows are useful even when the payload is
   redacted. The **raw scoped key is the row identity** (never egressed in
   place) so two entries whose scope/params redact to the same sentinel
   cannot collapse into one. `get-resource-state` requires the **full**
   scoped key (`:resource-id` + `:scope` + `:params`); any missing part
   fails closed with `:reason :missing-key` (a partial key cannot address
   an entry).

3. **Off-box egress for the TRACE-borne accessors** (`get-resource-history` /
   `list-resource-invalidations`, rf2-e0mq7a). These two project off the
   **trace ring**, not the live cache, so their value-bearing fields are
   trace-event values — the scoped key's scope/params, the `:cause` (a mutation
   may carry data), and the invalidation's `:scope` + `:matched` keys. They get
   the **same per-slot egress posture** as the live-cache accessors, via the
   trace-buffer peer of `resource-egress-fn`: the runtime threads
   `egress-value` (off-box `:sensitive?` / `:large?` defaults baked in) into
   `lifecycle-timeline` / `invalidation-graph`, which apply it to those
   value-bearing fields **before** `summarize`. A declared-`:sensitive?` /
   `:large?` value therefore redacts / elides **by default** and reveals only
   under the trusted-local `:include-sensitive?` / `:include-large?` opt-ins.
   Because these are trace values (not runtime-db `:entries` payloads) the
   walker is the plain `egress-value`, NOT `egress-runtime-db-value` — there is
   no runtime-db partition default to gate; the per-slot declaration posture is
   the boundary. This composes **on top of** the upstream whole-envelope
   default-suppress (`drop-sensitive-events` drops `:sensitive? true`
   ENVELOPES; this scrubs the VALUES inside the survivors). The **non-PII
   metadata** — the lifecycle shape (`:operation` / `:class` / `:resource-id` /
   `:generation` / `:owner` / `:status`) and the invalidation identity
   (`:tags`, `:match-count`, `:refetched`) — is **never egressed**, so the
   `:tag` filter axis and the storm / zero-match distinction stay useful on the
   default-redacted path (the same "redacted summary still exposes metadata"
   contract as the live-cache accessors).

The same `instance-row` per-slot egress seam carries the EP-0015 **on-box
local-render** default (`:rf.egress/local-redacted`, Spec 015 §Projection
profiles + §The graduation gate; `panels/local-render`). When a panel routes
the payload values (scope / params / `:data` / `:error`) through
`local-render-value` keyed on the **observed frame** before `summarize`, the
frame's declared `:sensitive` LEAVES become `:rf/redacted` in place (the local
operator still sees large values — the `include-large?` overlay) while the
metadata projects from the raw entry; an **unreachable observed frame fails
closed** (the whole value redacts, so the summary preview is `[redacted]`)
rather than ship raw under no policy. Pinned by
`resources_local_render_cljs_test` — the Resources-arm complement to the
App-DB arm's `local_render_cljs_test` (rf2-t55hxg.15).

Resource **history is bounded** (the accessor `:limit`, default 50).

## Panel sections (top → bottom)

The Resources tab is a Dynamic L3 tab (`:rf.xray/selected-tab`
`:resources`, mnemonic `s`, order 7 — after Routing). The view
(`panels/resources.cljs`) is pure hiccup over the single composite sub
`:rf.xray/resources-tab-data`; the projection algebra is pure data in
`panels/resources_helpers.cljc` (JVM-portable, unit-tested). Thirteen
stacked sections:

1. **Static resource registry** — per registered resource: id, source
   coords, params/data schemas (summarized — schemas can be large),
   request summary (transport + that the request is fn-derived), stale /
   GC policy, tag-producer presence, scope policy, sensitivity/large
   class, and the **declaring routes** (cross-joined from the route
   registry's `:resources` metadata).
1b. **Named scope resolvers** (EP-0016 D3) — per `reg-resource-scope`
   resolver: its id, its **declared inputs** (the `[:db path]` source per
   input name, paths summarized — the structural fact that explains which
   app facts decide a resource identity), and the **whole-db cost flag**
   (the explicit-cost fn-sugar marker — EP-0015 disposition 8: whole-db
   sugar degrades both narrow re-resolution and sensitivity-inheritance
   precision). Per-resolution input VALUES + the resolved scope are NOT
   here (a static declaration carries no PII) — they surface in the scope
   resolution timeline (§6b) off the egress-projected
   `:rf.resource/scope-resolved` trace.
2. **Live instances** (per frame) — per cache entry: resource key
   (scope + params summarized), status, derived `:stale?`, `:has-data?`,
   data summary, error / refresh-error summaries, `:loaded-at` /
   `:stale-at` / `:invalidated-at`, generation, attempt, request id,
   current work id, active owners, owner count, tags, GC eligibility.
   `:stale?` / `:has-data?` are **derived** here (Spec 016 §Status
   semantics — never stored facts). **The infinite-feed surface (EP-0021,
   R1/R2/R3):** an `:infinite?` entry additionally carries `:page-count`
   (the accumulated page-vector length), the runtime-owned `:cursor`
   (the `:next-page-param` — egress-projected, since a cursor can carry
   record ids), `:terminal?` (the derived `:has-next-page?` complement —
   `nil` cursor is the single terminal), and `:page-error` (the THIRD
   error channel — a load-more failure that KEPT the feed, summarized,
   distinct from `:error` / `:refresh-error`). All are **pure functions
   of the durable entry**. The `:fetching-next?` distinction (a load-more
   in flight vs a whole-feed `:fetching?` refresh) is NOT a pure function
   of the entry — both leave the feed at `:fetching` (no 6th FSM state);
   the distinguishing durable fact is the in-flight work record's
   **`:page-index`** (a positive tail index = a load-more APPEND), surfaced
   on the §3 work-ledger row, where the panel reads it.
3. **Work ledger** (per frame) — per work record: work id (the single
   attempt identity — one durable `:work/id` per record, no separate
   stale-suppression synonym; Spec 016 §Ledger row retention), kind, linked
   resource key, generation, status (+ terminal flag), owners, causes
   (summarized), cancellable?, deadline, attempt, transport, **`:page-index`**
   (EP-0021 — an infinite-feed work record's page index: `0` for a page-0
   first-load / whole-feed refetch, a positive tail index for a load-more
   APPEND; a LIVE positive-index row is the durable fact behind the
   `:fetching-next?` derived sub; `nil` for a non-infinite record),
   outcome. **Raw host handles** (AbortControllers, timeout handles,
   promises) are structurally inaccessible — they live in side tables
   outside durable frame-state. Non-terminal (live) rows lead; the
   terminal recent-races tail trails dimmed.
3a. **What is still running?** (EP-0011 live-work) — the cross-family
   **uniform** reply-envelope read (NOT resource-private; see §Uniform
   reply-envelope reads below): the live (non-terminal) work-ledger rows
   joined to their latest reply-envelope trace **phase + emitting op** per
   `:work/id`, each row carrying work-kind, ledger status, the joined
   latest phase/op, attempt, owner count, and the canonical `:work/id`.
   Below the rows, the **per-kind suppression tally** headline (the
   active-effects dashboard count of stale suppressions per family).
   **Silent-by-default**: the section renders nothing when there is no live
   work AND no suppression data (a settled app shows no dashboard).
3b. **Stale races** (EP-0011 stale-suppression / supersession) — the
   cross-family stale-races view: every work/reply attempt arc that hit the
   **stale-suppression correctness boundary** (carried ≠ current — one
   attempt superseded another), grouped by `:work/id` and rendered with its
   terminal `:stale` status, the phases the arc passed through, and the
   `:work/id`. Only **suppressed** arcs render. **Silent-by-default**: the
   section renders nothing when no arc was suppressed.
4. **Route / resource graph** — per route declaring `:resources`: the
   route id + path, its declared resources, the **blocking vs
   non-blocking** split (blocking = **SSR wait point**), keep-previous
   flags, and any `:after` dependency waterfall. Resolvers
   (`:params` / `:scope` fns) are recorded as declared without being
   invoked. The graph is **live, not just static** (rf2-m5u3gt): it joins
   the static route plan against the live instance rows, work ledger, and
   routing slice — each resource node carries a `:live` freshness rollup
   (`:fresh` / `:stale` / `:loading` / `:idle` / `:none`, plus the active
   work count) over its cache entries, and the **currently-active route** is
   flagged `:current?` with its live `:nav-token` and `:blocking-live` (the
   declared blocking resources whose scoped keys are still in the
   per-nav-token unsettled-blocking set — the SSR/route wait points that
   have not yet settled). The bare static projection (no live inputs)
   remains available for the SSR/JVM path.
5. **Lifecycle timeline** — the ordered `:rf.resource/*` trace rows
   (oldest-first), each carrying op label + semantic class colour,
   resource id, summarized resource key, generation, owner, summarized
   cause, and status before/after.
6. **Invalidation / mutation graph** — the `:rf.resource/invalidated`
   rows: summarized scope, the invalidated tags, summarized cause, the
   matched scoped keys, the match count (distinguishes a broad-tag storm
   and a zero-match "no match in this scope"), and the refetch count.
6b. **Scope resolution timeline** (EP-0016 D3) — the
   `:rf.resource/scope-resolved` rows: which named resolver ran, its
   declared input names, the resolved scope (summarized — a scope carries
   PII), and the **fail-closed nil evidence** (`:resolved-nil?` — the
   scope-requiring site got nil and produced NO global fallback). The
   visible proof that derived scope is explicit and inspectable, and that
   nil fails closed (Spec 016 §Named resource-scope resolvers — scope is a
   leak boundary).
6c. **Mutation continuations + scoped invalidation** (EP-0016 D1/D2) — two
   stacked surfaces making the EP-0016 doctrine visible ("reply-to is for
   workflow; populate/patch/invalidate are for cache"):
   - the **descriptor-level invalidation evidence** off the
     `:rf.mutation/succeeded` / `:rf.mutation/failed` settlement traces
     (the `:invalidation` plan-trace): the descriptor count, the
     per-descriptor **resolved scope** (summarized) + tags +
     `:cross-scope?` (the audited scope-agnostic escape) +
     `:refetch-populated?` (the Rider-1 partial-reply opt-in) + that
     descriptor's own **`:exempt-keys`** (summarized — the populated keys
     THAT pass spared, empty when the descriptor opted into
     `:refetch-populated? true`), the **fail-closed `:unresolved`**
     `{:from-db …}` ids (descriptors that resolved nil and produced NO
     invalidation — never an implicit global blast), and the Rider-1
     **`:populate-exempt`** keys (the **union** of every descriptor's
     spared keys — the keys this mutation populated that are exempt from
     its own refetch). The per-descriptor `:exempt-keys` is the truthful
     evidence in a **mixed** `:refetch-populated?` plan (rf2-fi6tda.3
     finding 2): one descriptor opting in no longer collapses the row's
     observed exemption to the top-level union alone — each pass shows
     exactly which populated key it spared. Surfaces the
     favorite/unfavorite global+session shape precisely;
   - the **`:reply-to` continuation dispatch** off the
     `:rf.mutation/replied` trace (mutation phase 6, after cache
     consequences + instance settlement): the mutation id, instance, work
     id, the accepted reply `:status`, and the call-site `:reply-to` event
     **target**. The runtime emits this row from the settlement boundary
     **after** the `:rf.mutation/succeeded` / `:rf.mutation/failed`
     settlement trace (rf2-ru73k6 F2), so the row's stream position
     truthfully follows settlement — it is post-settlement evidence, not a
     row built while the continuation effect is still being assembled. A
     row here is positive evidence the accepted reply continued into app
     workflow; a stale/superseded reply never fires one (it appears as
     `:rf.mutation/stale-suppressed` instead).
6d. **Optimistic mutations** (EP-0019) — the optimistic-mutation lifecycle,
   surfacing the parity surface re-frame2 had deferred against TanStack
   Query / RTK Query / SWR. Each `:rf.mutation/optimistic-applied` (a
   forward optimistic patch applied to the cache BEFORE the request settles,
   phase 1.5) is **paired by `:snapshot-id`** with its terminal settle and
   rendered as one row carrying the bug-class answer "I saw an optimistic
   apply — did it COMMIT or ROLL BACK, and was there a conflict?":
   - the **`:outcome` chip** — `:pending` (still in flight; the optimistic
     value is live on the cache), `:reconciled` (the mutation SUCCEEDED and
     the authoritative `:populates` / `:patches` COMMITTED over the
     optimistic value — the recorded inverse was discarded), or
     `:rolled-back` (the mutation FAILED / was cancelled / restore-dangled
     and the recorded inverse was replayed, conflict-aware);
   - the apply facts — the mutation id, instance, work id, generation, the
     **snapshot id**, the optimistically-patched **affected keys**
     (summarized — scope/params carry PII), the `:optimistic-tags`
     tag-matched keys, and the **fail-closed `:target-unresolved`**
     `{:from-db …}` ids (a tag/target reference that resolved nil and
     touched NO entry, never an implicit global apply);
   - **on reconcile** — the count of **committed** keys (the authoritative
     write owned them) + the `:reconciliation-refetches` (optimistic keys
     this mutation's invalidation marked stale → the read path refetches);
   - **on rollback** — the resolved **`:on-conflict`** rule
     (`:invalidate` default / `:force`) + the per-key **restored-vs-conflict
     disposition** (an UNMOVED `:revision` `· restored` the recorded
     `:before` verbatim; a MOVED one `· refetch` deferred to the read path
     under `:invalidate`, or `· conflict (:force)` restored the stale
     inverse anyway), the restored / conflicted / refetched key counts.

   Beneath the lifecycle rows, the **`:rf.warning/optimistic-force-clobber`**
   warnings render loud (red): an `:on-conflict :force` rollback restored a
   now-stale inverse OVER a concurrent authoritative write — the deliberate
   single-writer last-write-wins escape, surfaced so an unexpected clobber is
   visible (the forced keys + the recovery hint `:review-on-conflict`). A
   STALE / superseded mutation reply produces NEITHER op (the inverse is
   discarded, never replayed — it appears as `:rf.mutation/stale-suppressed`
   instead), so an apply with no terminal settle stays `:pending`. The
   distinction mirrors slice-4a's `:optimistic?` derived sub at the lifecycle
   level (Spec 016 §Optimistic mutations / §Surfacing to tooling).
7. **Cache growth** — per-resource aggregate of entry count, owned
   count, and GC-eligible count, plus the totals + live-work count.
   Surfaces unbounded list-param growth (many entries, few owners).
8. **Scope audit + lints** — the **standing global-scope security-review
   list** (every `:rf.scope/global` resource — the structural replacement
   for the old `/me` heuristic), the **suspicious-explicit-global**
   warnings (defense-in-depth: an explicit-global resource whose id/doc
   looks session-dependent), the **scope-mismatch lint** (an entry under
   scope A while a live sub reads the same R+P under a different scope B —
   matched on the **canonical** `[resource-id params]` + `scope` identities
   read off each row's raw `:scoped-key`, NOT the truncated/redacted display
   previews, so long-or-colliding params and distinct redacted scopes
   cannot false-trip or be missed; the surfaced output is summarized for
   privacy), and the **orphaned-owner lint** (an app-minted `[:lease …]` owner
   pinning an entry with no observed release; route / machine / ssr
   owners are framework-released and not linted).

When the host has **no resources registered AND no live instances**, the
panel renders the silent-by-default caption.

> **Sibling audit — derived-output declassification (EP-0015 §10).** The
> standing global-scope list is one of **two** standing
> security-review audits in re-frame2. Its declassification analogue lives
> on the Subscriptions / Views (Reactive) panel: the **Declassified
> Outputs** list enumerates every derived sub / flow carrying
> `:rf.egress/output-sensitivity :rf.egress/public` — an author's assertion
> that a derived-from-sensitive value is safe to surface (see
> [012-Views §Declassification audit](012-Views.md#declassification-audit-rfegresspublic-claims)).
> A `:rf.egress/public` claim is to derived-output egress what
> `:rf.scope/global` is to resource scope — a reviewer reads both lists to
> see every place an author opted out of a fail-closed default.

## The `:rf.resource/*` trace family

The framework Resources runtime **emits** the `:rf.resource/*` trace rows
(`:rf.event`-op-type events; the emit seams live in
`re-frame.resources.events`). Xray **defines the family** — its closed
operation set, per-op semantic class, and a human label — in
`panels/resources_helpers.cljc` (`trace-ops` / `resource-trace-op?` /
`op-class` / `op-label`) so the Resources tab and the Trace tab colour,
group, and filter resource rows without re-deriving the vocabulary. Any
keyword in the reserved `rf.resource` namespace is recognised as a family
member even before the enum is extended.

The operation set + semantic class (lifecycle order; this is the closed
enumeration — every op listed is EMITTED by the runtime, cross-checked
against [Spec 009 §Where trace emission lives](../../../spec/009-Instrumentation.md#where-trace-emission-lives)):

| Operation | Class | Emit site |
|---|---|---|
| `:rf.resource/registered` | lifecycle | `registry.cljc` (first-time `reg-resource`) |
| `:rf.resource/scope-resolved` | lifecycle | `scope_registry.cljc` (a named `reg-resource-scope` resolver resolved a `{:from-db …}` reference — EP-0016 D3; carries the resolver id, declared input names + values, whole-db flag, the resolved scope, and `:resolved-nil?`) |
| `:rf.resource/owner-attached` | lifecycle | `events.cljc` (ensure — a new lease lands) |
| `:rf.resource/cache-hit` | dedupe | `events.cljc` (ensure — fresh-skip cache serve) |
| `:rf.resource/deduped` | dedupe | `events.cljc` (ensure — join in-flight work) |
| `:rf.resource/work-started` | lifecycle | `events.cljc` (work-LEDGER row created — the transport request started; carries `:status :running` + `:superseded`) |
| `:rf.resource/fetch-started` | lifecycle | `events.cljc` (the cache ENTRY transitioned — carries the entry's `:status`, `:fetching` first-load or stale-revalidate; emitted alongside `work-started` on the same start) |
| `:rf.resource/work-abort-requested` | lifecycle | `events.cljc` (abort/cancel of in-flight work) |
| `:rf.resource/work-completed` | success | `events.cljc` (work row settled terminal) |
| `:rf.resource/succeeded` | success | `events.cljc` (reply landed, entry `:loaded`) |
| `:rf.resource/failed` | failure | `events.cljc` (first-load failure → `:error`) |
| `:rf.resource/refresh-failed` | failure | `events.cljc` (background-refresh failure, data kept) |
| `:rf.resource/load-more` | lifecycle | `events.cljc` (EP-0021 — a load-more issued the next-page fetch; an APPEND at a positive page index; carries `:page-param` `:page-index` `:page-count`) |
| `:rf.resource/page-appended` | success | `events.cljc` (EP-0021 — a load-more page fetch succeeded and was appended; carries `:page-index` `:page-count` `:next-page-param` `:terminal?`) |
| `:rf.resource/page-failed` | failure | `events.cljc` (EP-0021 — a load-more page fetch FAILED — the THIRD error channel; the feed is KEPT at `:loaded` and records `:page-error`; distinct from first-load `:failed` / background `:refresh-failed`) |
| `:rf.resource/load-more-skipped` | dedupe | `events.cljc` (EP-0021 — a load-more no-op; carries `:reason` `:no-feed` / `:no-next-page` (terminal) / `:in-flight` (joined a live page fetch); no new work, like a `:cache-hit`) |
| `:rf.resource/invalidated` | invalidation | `events.cljc` (tag invalidation) |
| `:rf.resource/refetch-decision` | lifecycle | `events.cljc` (per-entry refetch decision) |
| `:rf.resource/revalidate-scan` | lifecycle | `events.cljc` (focus/reconnect scan summary) |
| `:rf.resource/route-plan` | lifecycle | `route.cljc` (route-entry resource planning) |
| `:rf.resource/owner-released` | lifecycle | `events.cljc` + `ssr.cljc` (lease released) |
| `:rf.resource/stale-scheduled` | gc | `timers.cljc` (stale timer armed) |
| `:rf.resource/stale-fired` | gc | `events.cljc` (stale timer fired) |
| `:rf.resource/gc-scheduled` | gc | `timers.cljc` (GC timer armed) |
| `:rf.resource/gc-fired` | gc | `events.cljc` (GC timer fired) |
| `:rf.resource/gc-skipped` | gc | `events.cljc` (GC skipped — entry re-owned) |
| `:rf.resource/poll-scheduled` | gc | `timers.cljc` (poll timer armed — EP-0020) |
| `:rf.resource/poll-fired` | gc | `events.cljc` (poll timer fired — carries `:decision` `:polled` / `:coalesced` / `:paused-hidden` / `:no-owner` / `:no-entry`; EP-0020) |
| `:rf.resource/removed` | lifecycle | `events.cljc` + `registry.cljc` (entry removed) |
| `:rf.resource/stale-suppressed` | suppression | `events.cljc` (stale/superseded reply suppressed) |
| `:rf.resource/hydrated` | hydration | `ssr.cljc` (SSR hydration reconcile) |
| `:rf.resource/hydrate-refetch` | hydration | `ssr.cljc` (per-entry hydrate refetch-plan row) |
| `:rf.resource/hydrate-clock-skew` | hydration | `ssr.cljc` (`:warning` — hydrate stale-at skew) |
| `:rf.resource/restored` | hydration | `ssr.cljc` (epoch/SSR restore reconcile summary) |
| `:rf.resource/restore-clock-skew` | hydration | `ssr.cljc` (`:warning` — restore stale-at skew) |

`:rf.resource/stale-suppressed` is the single suppression op (entry +
ledger stale/superseded-reply suppression); an earlier draft also named
`:rf.resource/work-suppressed`, now folded into it (the runtime never
emitted a distinct work-suppressed row). It carries the canonical
reply-envelope vocabulary ADDITIVELY (rf2-mn4j89) — `:rf.reply/status
:stale`, `:rf.reply/work-status :suppressed`, `:rf.reply/work-id`,
`:rf.reply/stale-reason` (`:rf.resource/superseded`), and
`:rf.reply/correlation` (the carried-vs-current generation gate,
`:generation {:carried N :current M}`) — produced through the shared
`re-frame.reply` substrate, so a suppressed late resource reply classifies
the SAME way the HTTP / mutation / machine stale paths do (the bespoke
`:resource/key` / `:work/id` / `:generation` / `:outcome` facts are
preserved alongside). The mutation analogue `:rf.mutation/stale-suppressed`
carries the identical `:rf.reply/*` shape (`:rf.reply/stale-reason
:rf.mutation/superseded`, correlation `:instance/id` + the generation
gate). `:rf.resource/cache-hit` is a
FRESH-SKIP ensure — an `ensure` of an already-`:loaded` entry still
fresh-by-policy serves the cached value (no fetch, no in-flight join),
which the panel colours `:dedupe`; distinct from `:rf.resource/deduped`
(joining in-flight work). The two `*-clock-skew` rows are emitted at
`:warning` level (a hydrated/restored entry's absolute `:stale-at` is
ahead of the live clock — freshness is ambiguous until the next
live-owner ensure resolves it), so the main Trace panel surfaces them
under its cross-cutting `WARNING` badge while the Resources lifecycle
timeline colours them `:hydration`.

### The `:rf.mutation/optimistic-*` lifecycle ops (EP-0019)

The optimistic-mutation surface rides on three `:rf.mutation/*` ops the
framework emits from `re-frame.resources.mutation-events` (NOT members of
the `:rf.resource/*` `trace-ops` family — they are mutation ops, recognised
here by their literal op keys via `optimistic-mutation-op?` in
`panels/resources_helpers.cljc`):

| Operation | Emit site | Carries |
|---|---|---|
| `:rf.mutation/optimistic-applied` | `mutation-events.cljc` (phase 1.5, before the request lowers) | `:mutation` `:instance` `:work/id` `:generation` `:scope` `:snapshot-id` `:affected-keys` `:revisions` (per-key `{:resource/key :revision :forward}` at apply time — the conflict-check basis) `:tag-matched-keys` `:target-unresolved` `:cause` |
| `:rf.mutation/optimistic-reconciled` | `mutation-events.cljc` (mutation SUCCESS — commit) | `:instance` `:mutation` `:work/id` `:generation` `:snapshot-id` `:optimistic-keys` `:committed` `:reconciliation-refetches` `:cause` |
| `:rf.mutation/optimistic-rolled-back` | `mutation-events.cljc` (mutation FAILURE / cancel / restore-dangle) | `:instance` `:mutation` `:work/id` `:generation` `:snapshot-id` `:on-conflict` `:dispositions` (per-key `{:resource/key :restored :conflict :on-conflict}`) `:restored` `:conflicted` `:refetched` `:cause` |

plus the `:warning`-level `:rf.warning/optimistic-force-clobber` (emitted
alongside a `:force` rollback that clobbered a concurrent write — carries
`:mutation` `:instance` `:forced-keys` `:recovery :review-on-conflict`
`:reason`). The consumer (`optimistic-lifecycle` / `optimistic-force-clobbers`)
pairs each `:applied` with its terminal settle by `:snapshot-id` to drive the
§6d **Optimistic mutations** section above. The settle is keyed on the
recorded `:revision` + the work-id/generation acceptance verdict, never a
wall-clock race; a STALE / superseded reply emits NEITHER terminal op (the
inverse is discarded, the apply row stays `:pending`).

`:rf.resource/ensure` / `:rf.resource/refetch` / `:rf.resource/remove` /
`:rf.resource/window-focused` / `:rf.resource/network-reconnected` /
`:rf.resource/invalidate-tags` / `:rf.resource/release-owner` are
dispatched **EVENT IDs**, NOT emitted trace operations — they appear in
the trace stream only as the `:rf.event/dispatched` event vector, never
as a `:rf.resource/*` `:operation`, so they are NOT members of the
`trace-ops` family enum. See Spec 016 §Xray and AI tooling.

Each row carries, where applicable: frame, work id, scope, resource
key/id, params summary, generation, request id, owner, cause, status
before/after, work status, resource/invalidated tags, freshness
timestamps, and redaction/size markers (Spec 016 §Xray and AI tooling).
Sensitive trace events are default-suppressed at the Xray consumer seam
(Spec 009 §Privacy — the whole-envelope gate); the surviving values are
summarized.

## Tool accessors (the AI / MCP read API)

Five accessors on `day8.re-frame2-xray.runtime` (the Xray↔MCP read seam),
matching the candidate set in Spec 016 §Xray and AI tooling. All are
**read-only** and apply the two-layer privacy elision above.

| Accessor | Returns | Filter axes |
|---|---|---|
| `list-resources` | the static registry rows | `:resource-id` |
| `list-resource-instances` | the live per-frame instance rows | `:frame` `:scope` `:resource-id` `:params` `:status` `:stale?` `:tag` `:owner` `:request-id` |
| `get-resource-state` | one instance's durable state row | `:frame` `:resource-id` `:scope` `:params` (the scoped key) |
| `get-resource-history` | bounded lifecycle rows | `:frame` `:resource-id` `:nav-token` `:limit` (default 50) |
| `list-resource-invalidations` | the invalidation graph rows | `:frame` `:tag` |

`:scope` / `:resource-id` / `:params` filter against the raw cache key
**before** projection (so an accessor can scope its read by the cache
key); the remaining axes filter the already-projected rows. Per EP-0002
the frame target is carried explicitly — a frameless call with no
resolvable context fails closed (`{:ok? false :reason
:no-frame-resolved}`).

The live `:rf.runtime/resources :entries` map is keyed on the **opaque
CEDN-1 byte `key-id` string** (rf2-9e0tyq), with the kind-preserving
`[scope resource-id params]` scoped-key vector carried on each entry as
`:resource/key`. The upstream `:scope` / `:resource-id` / `:params` key
filter therefore matches against the entry's **`:resource/key` stamp**
(falling back to the map key only for a legacy entry that lacks it) — never
the byte map-key, which would match nothing for live runtime data. The
selected map preserves its byte map-keys as the row identity. Symmetrically,
the work-ledger projection reads each row's kind-preserving `:work/id`
vector from the record, not its byte `work-id-id` map-key.

## `:rf.xray/*` registry surface

Installed by `panels/resources.cljs`'s `install!` under the `:rf.xray/*`
isolation prefix (the registry-key isolation contract,
[`008-Embedding-Contract.md` §Registry-key isolation](./008-Embedding-Contract.md)).
No event registered here dispatches a `:rf.resource/*` event (read-only).

### Subscriptions

In production registration these subs read their live source directly —
they carry NO override input (rf2-e8330v). The test-override seam
(`resources/install-test-overrides!`) re-registers them with the
override input layered on top; see §Events below.

| Sub | Inputs (production) | Returns |
|---|---|---|
| `:rf.xray/registered-resources` | `:rf.xray/trace-buffer` | `(rf/registrations :resource)` — the static registry map. |
| `:rf.xray/registered-scope-resolvers` | `:rf.xray/trace-buffer` | `(rf/registrations :resource-scope)` — the static named-scope-resolver registry map (rf2-hls77w, EP-0016 D3). |
| `:rf.xray/resource-entries` | `:rf.xray/target-frame-runtime-db` | the live cache entries map at `[:rf.runtime/resources :entries]`. |
| `:rf.xray/resource-work-ledger` | `:rf.xray/target-frame-runtime-db` | the live work-ledger map at `[:rf.runtime/work-ledger]`. |
| `:rf.xray/resource-sub-reads` | (none) | observed live subscription reads backing the scope-mismatch lint (empty by default). |
| `:rf.xray/resource-routing-slice` | `:rf.xray/target-frame-runtime-db` | the live routing-runtime subtree at `[:rf.runtime/routing]` (current route + nav-token + per-nav-token unsettled-blocking set) backing the live route/resource graph. |
| `:rf.xray/resources-tab-data` | the six above + `:rf.xray/trace-buffer` + the route registry | the view-facing composite: `{:silent? :registry :scope-resolvers :instances :work :live-work :stale-races :stale-tally :route-graph :timeline :invalidations :scope-resolutions :mutation-invalidations :continuations :optimistic-mutations :optimistic-force-clobbers :cache-growth :audit}`. Its `:scope-resolvers` is the projected named-scope-resolver registry (id + declared inputs + whole-db cost flag, paths summarized, NO resolved value); `:scope-resolutions` is the `:rf.resource/scope-resolved` resolution timeline (resolver id + resolved scope summarized + fail-closed nil evidence — EP-0016 D3); `:mutation-invalidations` is the descriptor-level invalidation evidence off the mutation settlement traces (per-descriptor resolved scope + fail-closed `:unresolved` + Rider-1 `:populate-exempt` — EP-0016 D2); `:continuations` is the `:rf.mutation/replied` call-site `:reply-to` dispatch evidence (EP-0016 D1); `:optimistic-mutations` is the EP-0019 optimistic-mutation lifecycle (each `:rf.mutation/optimistic-applied` paired by `:snapshot-id` with its terminal `:reconciled` / `:rolled-back` settle, carrying the per-key restored-vs-conflict disposition); `:optimistic-force-clobbers` is the `:rf.warning/optimistic-force-clobber` rows (a `:force` rollback over a concurrent write). `:route-graph` joins the static route plan against the live instance/work rows + routing slice. The `:live-work` / `:stale-races` / `:stale-tally` slots are the UNIFORM reply-envelope reads (see below). |

### Events (test-only override seam — rf2-e8330v / xxo3zz F3)

These are **NOT** installed by `register-xray-handlers!`. They live behind
`resources/install-test-overrides!` (orchestrated by `test-support/
install-test-overrides!`), which a test opts into AFTER
`register-xray-handlers!`. The seam also re-registers the six production
subs above with their `*-override` input layered on top. Production code
paths never dispatch these; `nil` clears the override.

`:rf.xray/set-registered-resources-override-for-test`,
`:rf.xray/set-registered-scope-resolvers-override-for-test`,
`:rf.xray/set-resource-entries-override-for-test`,
`:rf.xray/set-resource-work-ledger-override-for-test`,
`:rf.xray/set-resource-sub-reads-override-for-test`,
`:rf.xray/set-resource-routing-slice-override-for-test`.

### Uniform reply-envelope reads (`:live-work` / `:stale-races`)

The composite's `:live-work`, `:stale-races`, and `:stale-tally` slots are
**not** resource-specific — they read the canonical EP-0011 work/reply
facts (`:work/id` / `:work/kind` / reply `:status` / stale-suppression
carried+current) the SAME way for **every** managed-async family, via
[`panels/reply_envelope.cljc`](../src/day8/re_frame2_xray/panels/reply_envelope.cljc).
They are **rendered** by §3a ("What is still running?") and §3b ("Stale
races") above — silent-by-default so a settled app surfaces neither.
"What is still running?" is the live (non-terminal) work-ledger rows
joined to the latest reply-envelope trace phase per `:work/id`; the
stale-races view groups every cross-family work/reply row by `:work/id`.
The live-work join keys on the canonical `:work/id` **vector** carried on
the ledger record (`reply_envelope/ledger-row` reads `(:work/id record)`,
falling back to the opaque CEDN-1 byte map key only for legacy records),
so a live ledger row correctly joins to the vector-keyed reply-envelope
trace rows. A `:stale-suppressed` row preserves the canonical EP-0011
`:status :stale` + `:status-class :suppression` read off the unambiguous
`:rf.reply/status` (never misreading a bare ledger `:status`), the same
cross-surface badge contract a `:completed` row renders.
The resource family is the only ledger writer today, so the rows are all
`:work/kind :resource` (and `:mutation`) for now; as HTTP / route /
machine / timer families write their own ledger rows and emit their
reply-envelope trace ops, these surfaces pick them up with no panel
change. The mutation phase ops are explicitly classified onto the
reply-envelope phases (EP-0016 D1): `:rf.mutation/started` → `:issued`,
`:rf.mutation/succeeded` / `:rf.mutation/failed` → `:completed`,
`:rf.mutation/stale-suppressed` → `:stale-suppressed`, and
`:rf.mutation/replied` (the `:reply-to` continuation dispatch) →
`:delivered`. The contract is owned by
[`013-Trace-Consumer.md` §One work/reply vocabulary](013-Trace-Consumer.md#one-workreply-vocabulary--reading-the-uniform-reply-envelope);
this panel is one consumer.

## Mountable surface

The Resources tab is independently mountable per the
[`007-UX-IA.md`](./007-UX-IA.md) §Mountable surface inventory contract:

```clojure
(day8.re-frame2-xray.panels/mount-resources! mount-point opts) → unmount-fn
```

enumerated in `day8.re-frame2-xray.panel-enum/panel-enum` (the
single-source-of-truth guarded by `panel_enum_guard_cljs_test.cljs`).

## Cross-references

- [`spec/016-Resources.md`](../../../spec/016-Resources.md) — the owning
  framework contract (§Xray and AI tooling, §Status semantics, §Frame
  work ledger, §Scope resolution, §Active owners and causes,
  §Invalidation, §Route integration).
- [`007-UX-IA.md`](./007-UX-IA.md) §Mountable surface inventory — the
  panel's tier-1 L3-tab row + mount fn.
- [`014-Registry-Catalogue.md`](./014-Registry-Catalogue.md) §Resources
  panel — the `:rf.xray/*` registration enumeration.
- [`013-Trace-Consumer.md`](./013-Trace-Consumer.md) — the consumer
  pipeline + privacy gate the `:rf.resource/*` family rides.
- [`008-Embedding-Contract.md`](./008-Embedding-Contract.md) — the
  registry-key isolation + frame-provider contract.
