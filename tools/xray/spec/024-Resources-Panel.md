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

The operation set + semantic class:

| Operation | Class | Operation | Class |
|---|---|---|---|
| `:rf.resource/registered` | lifecycle | `:rf.resource/refresh-failed` | failure |
| `:rf.resource/ensure` | lifecycle | `:rf.resource/invalidated` | invalidation |
| `:rf.resource/owner-attached` | lifecycle | `:rf.resource/refetch-decision` | lifecycle |
| `:rf.resource/cache-hit` | dedupe | `:rf.resource/owner-released` | lifecycle |
| `:rf.resource/deduped` | dedupe | `:rf.resource/gc-scheduled` | gc |
| `:rf.resource/fetch-started` | lifecycle | `:rf.resource/gc-fired` | gc |
| `:rf.resource/work-started` | lifecycle | `:rf.resource/gc-skipped` | gc |
| `:rf.resource/work-abort-requested` | lifecycle | `:rf.resource/removed` | lifecycle |
| `:rf.resource/work-completed` | success | `:rf.resource/hydrated` | hydration |
| `:rf.resource/succeeded` | success | `:rf.resource/hydrate-refetch` | hydration |
| `:rf.resource/failed` | failure | `:rf.resource/stale-scheduled` | gc |
| `:rf.resource/stale-suppressed` | suppression | `:rf.resource/stale-fired` | gc |

`:rf.resource/stale-suppressed` is the single suppression op (entry +
ledger stale/superseded-reply suppression); an earlier draft also named
`:rf.resource/work-suppressed`, now folded into it (the runtime never
emitted a distinct work-suppressed row). `:rf.resource/cache-hit` is a
FRESH-SKIP ensure — an `ensure` of an already-`:loaded` entry still
fresh-by-policy serves the cached value (no fetch, no in-flight join),
which the panel colours `:dedupe`; distinct from `:rf.resource/deduped`
(joining in-flight work). See Spec 016 §Xray and AI tooling.

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
| `:rf.xray/resource-entries` | `:rf.xray/target-frame-runtime-db`, override | the live cache entries map at `[:rf.runtime/resources :entries]`. |
| `:rf.xray/resource-work-ledger` | `:rf.xray/target-frame-runtime-db`, override | the live work-ledger map at `[:rf.runtime/work-ledger]`. |
| `:rf.xray/resource-sub-reads` | override | observed live subscription reads backing the scope-mismatch lint (empty by default). |
| `:rf.xray/resource-routing-slice` | `:rf.xray/target-frame-runtime-db`, override | the live routing-runtime subtree at `[:rf.runtime/routing]` (current route + nav-token + per-nav-token unsettled-blocking set) backing the live route/resource graph. |
| `:rf.xray/resources-tab-data` | the five above + `:rf.xray/trace-buffer` + the route registry | the view-facing composite: `{:silent? :registry :instances :work :route-graph :timeline :invalidations :cache-growth :audit}`. Its `:route-graph` joins the static route plan against the live instance/work rows + routing slice. |

### Events (test-only override hooks)

`:rf.xray/set-registered-resources-override-for-test`,
`:rf.xray/set-resource-entries-override-for-test`,
`:rf.xray/set-resource-work-ledger-override-for-test`,
`:rf.xray/set-resource-sub-reads-override-for-test`,
`:rf.xray/set-resource-routing-slice-override-for-test` — production code
paths MUST NOT dispatch these; `nil` clears the override.

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
