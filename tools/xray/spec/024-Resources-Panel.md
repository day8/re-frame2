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

## Decoupling — Resources is optional; Xray does not require it

Resources is a **post-v1 OPTIONAL artefact** (Spec 016 §Implementation
status) and is **NOT a hard dep of Xray** (`tools/xray/deps.edn` does not
list `day8/re-frame2-resources`). The panel reads everything **decoupled**
— exactly the posture the Routing tab uses for the route slice and the
Machine Inspector uses for machine snapshots:

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

Nothing in `tools/xray/` `:require`s `re-frame.resources.*`. The reserved
runtime-db key paths are small duplicated literals in
`panels/resources_helpers.cljc` — the bundle-isolation-safe price of not
adding a require edge from a tool into an optional artefact. An app that
omits the Resources artefact sees the panel render its silent-by-default
caption.

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

Resource **history is bounded** (the accessor `:limit`, default 50).

## Panel sections (top → bottom)

The Resources tab is a Dynamic L3 tab (`:rf.xray/selected-tab`
`:resources`, mnemonic `s`, order 7 — after Routing). The view
(`panels/resources.cljs`) is pure hiccup over the single composite sub
`:rf.xray/resources-tab-data`; the projection algebra is pure data in
`panels/resources_helpers.cljc` (JVM-portable, unit-tested). Eight
stacked sections:

1. **Static resource registry** — per registered resource: id, source
   coords, params/data schemas (summarized — schemas can be large),
   request summary (transport + that the request is fn-derived), stale /
   GC policy, tag-producer presence, scope policy, sensitivity/large
   class, and the **declaring routes** (cross-joined from the route
   registry's `:resources` metadata).
2. **Live instances** (per frame) — per cache entry: resource key
   (scope + params summarized), status, derived `:stale?`, `:has-data?`,
   data summary, error / refresh-error summaries, `:loaded-at` /
   `:stale-at` / `:invalidated-at`, generation, attempt, request id,
   current work id, active owners, owner count, tags, GC eligibility.
   `:stale?` / `:has-data?` are **derived** here (Spec 016 §Status
   semantics — never stored facts).
3. **Work ledger** (per frame) — per work record: work id, kind, linked
   resource key, generation, status (+ terminal flag), owners, causes
   (summarized), stale-key (= work id — one identity per record, Spec 016
   §Ledger row retention), cancellable?, deadline, attempt, transport,
   outcome. **Raw host handles** (AbortControllers, timeout handles,
   promises) are structurally inaccessible — they live in side tables
   outside durable frame-state. Non-terminal (live) rows lead; the
   terminal recent-races tail trails dimmed.
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
emitted a distinct work-suppressed row). `:rf.resource/cache-hit` is a
FRESH-SKIP ensure — an `ensure` of an already-`:loaded` entry still
fresh-by-policy serves the cached value (no fetch, no in-flight join),
which the panel colours `:dedupe`; distinct from `:rf.resource/deduped`
(joining in-flight work). The two `*-clock-skew` rows are emitted at
`:warning` level (a hydrated/restored entry's absolute `:stale-at` is
ahead of the live clock — freshness is ambiguous until the next
live-owner ensure resolves it), so the main Trace panel surfaces them
under its cross-cutting `WARNING` badge while the Resources lifecycle
timeline colours them `:hydration`.

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

## `:rf.xray/*` registry surface

Installed by `panels/resources.cljs`'s `install!` under the `:rf.xray/*`
isolation prefix (the registry-key isolation contract,
[`008-Embedding-Contract.md` §Registry-key isolation](./008-Embedding-Contract.md)).
No event registered here dispatches a `:rf.resource/*` event (read-only).

### Subscriptions

| Sub | Inputs | Returns |
|---|---|---|
| `:rf.xray/registered-resources` | `:rf.xray/trace-buffer`, override | `(rf/registrations :resource)` — the static registry map. |
| `:rf.xray/registered-scope-resolvers` | `:rf.xray/trace-buffer`, override | `(rf/registrations :resource-scope)` — the static named-scope-resolver registry map (rf2-hls77w, EP-0016 D3). |
| `:rf.xray/resource-entries` | `:rf.xray/target-frame-runtime-db`, override | the live cache entries map at `[:rf.runtime/resources :entries]`. |
| `:rf.xray/resource-work-ledger` | `:rf.xray/target-frame-runtime-db`, override | the live work-ledger map at `[:rf.runtime/work-ledger]`. |
| `:rf.xray/resource-sub-reads` | override | observed live subscription reads backing the scope-mismatch lint (empty by default). |
| `:rf.xray/resource-routing-slice` | `:rf.xray/target-frame-runtime-db`, override | the live routing-runtime subtree at `[:rf.runtime/routing]` (current route + nav-token + per-nav-token unsettled-blocking set) backing the live route/resource graph. |
| `:rf.xray/resources-tab-data` | the six above + `:rf.xray/trace-buffer` + the route registry | the view-facing composite: `{:silent? :registry :scope-resolvers :instances :work :live-work :stale-races :stale-tally :route-graph :timeline :invalidations :cache-growth :audit}`. Its `:scope-resolvers` is the projected named-scope-resolver registry (id + declared inputs + whole-db cost flag, paths summarized, NO resolved value); `:route-graph` joins the static route plan against the live instance/work rows + routing slice. The `:live-work` / `:stale-races` / `:stale-tally` slots are the UNIFORM reply-envelope reads (see below). |

### Events (test-only override hooks)

`:rf.xray/set-registered-resources-override-for-test`,
`:rf.xray/set-registered-scope-resolvers-override-for-test`,
`:rf.xray/set-resource-entries-override-for-test`,
`:rf.xray/set-resource-work-ledger-override-for-test`,
`:rf.xray/set-resource-sub-reads-override-for-test`,
`:rf.xray/set-resource-routing-slice-override-for-test` — production code
paths MUST NOT dispatch these; `nil` clears the override.

### Uniform reply-envelope reads (`:live-work` / `:stale-races`)

The composite's `:live-work`, `:stale-races`, and `:stale-tally` slots are
**not** resource-specific — they read the canonical EP-0011 work/reply
facts (`:work/id` / `:work/kind` / reply `:status` / stale-suppression
carried+current) the SAME way for **every** managed-async family, via
[`panels/reply_envelope.cljc`](../src/day8/re_frame2_xray/panels/reply_envelope.cljc).
"What is still running?" is the live (non-terminal) work-ledger rows
joined to the latest reply-envelope trace phase per `:work/id`; the
stale-races view groups every cross-family work/reply row by `:work/id`.
The resource family is the only ledger writer today, so the rows are all
`:work/kind :resource` (and `:mutation`) for now; as HTTP / route /
machine / timer families write their own ledger rows and emit their
reply-envelope trace ops, these surfaces pick them up with no panel
change. The contract is owned by
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
