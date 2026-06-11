# Spec 016 — Resources

> Status: Drafting. **v1-optional capability (post-v1 artefact).** Implementations MAY ship `day8/re-frame2-resources`; when they do, the contract below is fixed. The CLJS reference ships it as the optional `re-frame.resources` artefact (slices land per [EP-0003 §Bead Structure](../docs/EP/EP-0003-resource-queries.md#bead-structure)). Builds on the registration grammar in [001-Registration](001-Registration.md), the two-partition frame contract and frame-target resolution in [002-Frames](002-Frames.md), the runtime-subsystem contract in [Runtime-Subsystems](Runtime-Subsystems.md), parametric subscription inputs in [006-ReactiveSubstrate](006-ReactiveSubstrate.md), managed HTTP in [014-HTTPRequests](014-HTTPRequests.md), routing in [012-Routing](012-Routing.md), SSR/hydration in [011-SSR](011-SSR.md), the trace contract in [009-Instrumentation](009-Instrumentation.md), and the reserved-namespace policy in [Conventions](Conventions.md).
>
> **The minimum claim:** *if* an implementation ships declarative server-state, it ships `reg-resource` and the `:rf.resource/*` surface per this spec — resource identity, fail-closed cache scopes, active owners, a compact lifecycle FSM, a frame work ledger, stale/fresh policy, dedupe, stale-reply suppression, inactive GC, route `:resources`, SSR preload/hydration, and managed HTTP as the single built-in read transport. The contract being uniform is what lets Xray, SSR projection, restore/time-travel, and the AI-Audit reason about server state without per-app reinvention.
>
> **Scope is HTTP-only.** GraphQL is a deferred later phase and is **out of this contract** ([EP-0003 §Deferred: GraphQL](../docs/EP/EP-0003-resource-queries.md#deferred-graphql-later-phase)). `:rf.http/managed` (Spec 014) is the single built-in transport.
>
> `:rf.runtime/resources` and `:rf.runtime/work-ledger` are **runtime subsystems** — per [Runtime-Subsystems](Runtime-Subsystems.md), each MUST answer the five clauses (subtree, write authority, read API, projection/elision, teardown). `:rf.runtime/resources` is the contract's first graduating instance outside the four shipped subsystems (see [§Runtime-subsystem graduation](#runtime-subsystem-graduation)).
>
> **Code samples are in ClojureScript** (the CLJS reference). The contract is host-agnostic; identity, scope, status, and ownership are pattern-level, while AbortControllers, timer handles, and the Fetch transport are host details.

## Abstract

A **resource** is a named, cached read of remote or external state — server-state as a runtime-managed read model over a frame work ledger. `reg-resource` registers it; views read it through passive subscriptions; route entry, events, and machines *cause* it to fetch. The resource runtime owns identity, cache scope, staleness, dedupe, invalidation, garbage collection, in-flight ownership, SSR hydration, and tool metadata, so an application stops re-implementing that bookkeeping per feature.

This is the re-frame2 answer to the HTTP server-state tools — TanStack Query, RTK Query, SWR, and `shipclojure/re-frame-query` — re-expressed in the re-frame2 model: views are passive reads, events are causal, server state lives in the framework-owned runtime partition (not app-db), and every cache decision is data an AI agent or devtool can enumerate. The full rationale, prior-art benchmark, and slice plan live in [EP-0003](../docs/EP/EP-0003-resource-queries.md); this spec is the normative contract for the HTTP-only initial scope.

Two distinctions are load-bearing and appear throughout:

- **owners keep resources alive; causes explain why work happened** (see [§Active owners and causes](#active-owners-and-causes));
- **params identify the remote read inside a cache scope; scope is the tenant/user/locale/impersonation/SSR leak boundary** and is **mandatory** (see [§Scope resolution](#scope-resolution)).

## Implementation status

Spec 016 is an **optional capability** in the [000-Vision §Capability matrix](000-Vision.md) sense. Implementations MAY:

- **Ship `day8/re-frame2-resources` per this spec.** Then the contract below applies — resource identity, scope policy, status semantics, the work ledger, dedupe/suppression, route `:resources`, SSR hydration, restore behaviour, and the `:rf.resource/*` surface are all locked. Tools and conformance fixtures key off the canonical surface.
- **Omit it.** Applications express server state with [Pattern-RemoteData](Pattern-RemoteData.md) plus `:rf.http/managed` (Spec 014) directly. The omission is a conformance-set difference, not a defect — the patterns Resources supersedes keep working.

The **CLJS reference ships `day8/re-frame2-resources`** as a post-v1 optional artefact. Requiring `re-frame.resources` wires the artefact into the core facade, feature registry, and tool metadata; routing and SSR integration are late-bound so an app that does not load those optional artefacts does not carry their code. A port that omits Resources MUST NOT register the `:rf.resource/*` / `:rf.scope/*` / `:rf.work/*` namespaces for any other purpose (they are reserved for this Spec; see [Conventions](Conventions.md#reserved-namespaces-framework-owned)).

The slice order — read-resource MVP first, mutations and focus/reconnect at the first public-beta gate — is normative in [EP-0003 §Acceptance Criteria And Rollout](../docs/EP/EP-0003-resource-queries.md#acceptance-criteria-and-rollout). **The first public-beta surface is now landed and complete:** the read-resource MVP, `reg-mutation` / `:rf.mutation/execute` (see [§Mutations](#mutations-first-public-beta-gate-rf2-dwme29)), and focus/reconnect active-stale revalidation (see [§Stale and GC scheduling](#stale-and-gc-scheduling)). Optimistic rollback, polling, and GraphQL remain later slices (see [§Deferred slices](#deferred-slices)).

## Role

`reg-resource`, when an implementation ships it, is **framework-provided**: the artefact registers the resource registrar kind, the `:rf.resource/*` events and subs, and the managed-HTTP lowering; applications register resources and read them the way they read any sub. Resource state is **runtime-managed process state** — app code reads it through public subscriptions and accessors and influences it through events, but MUST NOT hand-edit the resource runtime slice. This is what makes Resources a Spec rather than a convention: the public contract is locked, the cache lives in a known runtime partition, Xray introspects the same shapes, and SSR/restore project the same allowlist across applications.

### Relationship to landed EPs

Resources is written against three EP contracts that have **landed on main**, not against pending dependencies:

- **App/Runtime partition ([EP-0001](../docs/EP/EP-0001-frame-partitions.md), landed).** Resource cache lives only in the framework-owned runtime partition `:rf.runtime/resources` inside `:rf.db/runtime` ([002 §The two-partition frame contract](002-Frames.md#the-two-partition-frame-contract)). There is **no interim app-db location**: a stray `:rf/runtime` root at the top of app-db is a hard error (`:rf.error/legacy-runtime-root`, per [Conventions §The legacy `:rf/runtime` root](Conventions.md#the-legacy-rfruntime-root-hard-error-in-final-form)), not a fallback. Ordinary `:db` event handlers cannot accidentally wipe resource state.
- **Explicit frame-target resolution ([EP-0002](../docs/EP/EP-0002-frame-target-resolution.md), final).** Resources are a frame-aware feature; **every resource carries its explicit frame** (the carried-frame invariant). The ambient `:rf/default` fallback is gone: a frameless resource operation with no resolvable context fails closed (`:rf.error/no-frame-context`) rather than touching the wrong frame ([002 §Frame target resolution](002-Frames.md#frame-target-resolution--the-carried-invariant)). The internal reply tokens stamp the qualified `:rf.frame/id` (the canonical carried frame stamp — EP-0002 R3), never the bare public `:frame` opt.
- **Parametric subscription inputs ([EP-0004](../docs/EP/EP-0004-subscription-inputs.md), final).** Resource subscription view-models compose over the resolved input shape — static `:<-` sugar plus input functions returning a vector of query vectors ([006 §Subscription input producers](006-ReactiveSubstrate.md#subscription-input-producers--app-db-reader-static-parametric-input-fn)). A projection over `[:rf.resource/data …]` is an ordinary subscription, not a resource-local `:select` hook (see [§No `:select` key](#no-select-key)).

## Resource identity

A resource **instance** is identified by a triple — a cache scope, a resource id, and canonical params:

```clojure
[cache-scope resource-id canonical-params]
```

For example:

```clojure
[[:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
 :article/by-slug
 {:slug "welcome"}]
```

This **scoped resource key** is the cache key, the request-correlation token's payload, and the unit Xray and SSR enumerate.

Identity rules (MUST):

- **Cache scope is serializable EDN data** and is the first element of the key. A scope **map** is canonicalized under the **same canonicalization rule as params maps** — key order does not affect identity and nested maps recurse — so two spellings of the same scope hash to one cache key. `[:rf.scope/session {:tenant-id "acme" :user-id "u-42"}]` and `[:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]` are the **same scope**, never two leaking caches.
- **There is no silent default scope.** Every resource declares an explicit scope **policy** at registration (see [§Scope resolution](#scope-resolution)). A resource that wants the global scope says so — `:scope :rf.scope/global` — as a deliberate, auditable claim; a resource with no declared policy is a loud registration error, never a silent `[:rf.scope/global]` read.
- **User-, tenant-, locale-, permission-, impersonation-, and session-dependent reads** MUST use an explicit scope, or put those values in params. Logout, account switch, tenant switch, and impersonation change MUST have a causal way to clear or replace the affected scope (see [§clear-scope](#clear-scope-is-causal)).
- **Params conform to `:params-schema`** and are serializable EDN data; maps are canonicalized so key order does not affect identity. Host values — functions, promises, dates, DOM nodes, AbortControllers, JS objects — are rejected. `nil` vs missing MUST be schema-defined, not accidental. Every variable that affects remote identity MUST be represented in params.
- **Request-correlation ids MUST include the full scoped resource key** (or an equivalent scope-bearing value) so the same params in different user/tenant scopes cannot supersede each other. **They MUST also be frame-qualified** — the transport request-id the runtime hands a process-global transport registry includes the issuing frame id (see [§Transport](#transport) and the [frame-qualified transport request-id](#ledger-row-retention-and-identity) rule), so the same scoped key issued from two frames cannot supersede or abort across the frame boundary.
- **No `:cache-key` escape hatch in v1** unless it is validated, visible in tools, and tested heavily — canonical params are the identity (see [§Deferred slices](#deferred-slices)).

### Canonicalization rule

Canonicalization is a pure function over EDN: a map is normalized so member order is irrelevant to identity and equality; nested maps recurse; sets and vectors keep their value semantics. The **same** rule applies to params maps and scope maps, so a scope and a param map spelled two ways collapse to one cache identity. Canonicalization happens once, at the scope/params resolution boundary, before the scoped resource key is computed; the canonical form is what is stored on the entry, indexed, traced (post-elision), and serialized.

## Scope resolution

Scope is the cache's tenant / user / permission / locale / impersonation / SSR leak boundary, and a resolved scope can carry PII (user ids, tenant ids, impersonation markers). **A boundary that critical MUST fail closed: it never silently defaults to "shared."** This is the per-resource scope policy ruled fail-closed for EP-0003 (`rf2-6rrz53`); it composes with the cache-scope-shape rule — scope is **explicit-in-key** *and* its presence is **mandatory-by-policy**.

### Every resource declares a scope policy (required, fail-closed)

`:scope` at `reg-resource` is **REQUIRED**. It declares a *policy*, not necessarily a concrete value, drawn from a closed reserved enum:

- **`:scope :rf.scope/global`** — the resource is **explicitly global**. This is a *claim*: "the same params produce the same data for every user, tenant, permission-set, locale, and impersonation state." It is an auditable assertion, not a convenience hideaway.
- **`:scope <resolver>`** — derive the scope deterministically. The resolver materializes as visible EDN in the resource key. A resolver may be a route-resource resolver `(fn [route ctx] …)`, a resource-spec resolver, or — for a sub-side resolver — a pure data value / fn-of-nothing (see [§Subscription-side scope resolution](#subscription-side-scope-resolution)).
- **`:scope :rf.scope/from-caller`** — the scope is **required from the use site**: every `:rf.resource/ensure` / `:rf.resource/refetch` / `:rf.resource/state` (and sibling) call MUST supply `:scope` on the payload, or a route-resource resolver MUST supply it. Enforcement lands where the scope is actually known.
- **No declared policy** — a loud **registration error** (`:rf.error/resource-missing-scope-policy`). "I forgot this read is user-scoped" is unrepresentable at registration rather than an Xray heuristic about `/me`-looking URLs.

> **There is no `:rf.scope/global` default.** A user-scoped read can never be silently registered as global. Stating scope intent once, at the registration site, is the loud-failure ethos applied to the cache's leak boundary.

### Resolution precedence (for events; no global fallthrough)

For a resource **event** (`:rf.resource/ensure`, `:rf.resource/refetch`, …) the runtime resolves the concrete scope in this order:

1. `:scope` supplied on the resource event payload;
2. the route-resource `:scope` resolver (a `(route, ctx)` function);
3. the resource-spec `:scope` resolver.

There is **no tier-4 `[:rf.scope/global]` fallthrough.** If none of the above yields a scope, resolution **fails closed**:

- a resource whose policy is `:rf.scope/global` resolves to `[:rf.scope/global]` **only because that is its declared, explicit policy** — not because the precedence ran out of options;
- a `:rf.scope/from-caller` resource reached with no payload `:scope` and no resolver is a loud **use-time error** (`:rf.error/resource-scope-required-from-caller`), not a silent global read.

### Subscription-side scope resolution

Subscriptions are **pure** — they cannot run a `(route, ctx)` resolver, because a sub has no access to the routing match or the event context. This is exactly the seam where a silent leak used to hide: a route ensures a resource under `[:rf.scope/session {…}]`, but a view's `[:rf.resource/state {…}]` that omits `:scope` would resolve to a *different* scope than the one the data was loaded under and read `:idle` forever — a permanent skeleton with no error anywhere. That is the silent-wrong-target bug family [EP-0002](../docs/EP/EP-0002-frame-target-resolution.md) exists to kill, and resource subscriptions MUST close it the same way.

A subscription resolves its scope from, in order:

1. `:scope` supplied on the **subscription payload**;
2. the resource spec's `:scope` **only if** that policy is one a pure sub can evaluate without an event context — i.e. an explicit `:rf.scope/global` claim, or a resolver **declared as pure data / fn-of-nothing**. A resource whose scope policy is a `(route, ctx)` resolver or `:rf.scope/from-caller` **cannot** be resolved sub-side from the spec alone.

A subscription that **cannot** resolve a scope is a **loud, structured error** (`:rf.error/resource-sub-unresolved-scope`) carrying the resource id and the unresolvable policy — **never** a silent `[:rf.scope/global]` read and **never** a silent `:idle`. The fix the error points at is explicit: pass `:scope` on the subscription payload (the same scope the owning route/event ensured under), or re-declare the resource with a sub-resolvable scope policy. This is the read-side counterpart of the write-side fail-closed gate: a read that cannot name its principal does not fall through to the shared cache.

### Xray scope diagnostics are defense-in-depth, not the boundary

Because every resource now carries an explicit policy, the old `/me` / `/current-user` URL heuristic is **no longer the boundary** — it is downgraded to defense-in-depth. Xray SHOULD warn about *suspicious explicit-global* resources (an `:rf.scope/global` claim whose request looks session-dependent — `/me`, `/current-user`, tenant-local URLs, or auth-derived params), not "compensate for a missing scope" (a missing scope is now a loud error, not a heuristic). The **standing audit surface** is structural: sub-topology / Xray **enumerate every `:rf.scope/global` resource** as the security-review list — the explicit replacement for the heuristic (see [§Xray and AI tooling](#xray-and-ai-tooling)).

### `clear-scope` is causal

`clear-scope` is a causal operation (the `:rf.resource/clear-scope` event). It MUST:

- remove or mark unusable every entry in that scope;
- release owners in that scope;
- abort in-flight requests that have no remaining owner outside that scope;
- suppress late replies by scope + generation checks;
- emit trace rows explaining which entries were removed, aborted, or left alone.

Auth-token refresh does **not** necessarily require clearing scope if the user, tenant, permissions, and impersonation state are unchanged. Login, logout, account switch, tenant switch, permission-set change, locale switch that affects wire data, and impersonation enter/exit **do** require either a new scope or an explicit clear/replace operation.

Invalidation is **scoped by default**. A cross-scope invalidation MUST opt in explicitly and be visible in Xray because it can refetch or stale data for multiple users, tenants, story frames, or SSR requests.

## Active owners and causes

TanStack Query and RTK Query talk about active observers or subscriptions. re-frame2 talks about **active owners** (liveness leases) and **causes** (trace/diagnostic metadata). The two are never blurred.

### Owners are liveness leases

Owners answer: should invalidation refetch now or only mark stale? Should polling continue? May the entry be garbage-collected? What should route-leave release? Which workflows intentionally keep this resource active?

```clojure
[:route   :route/article  nav-token]
[:machine :checkout/flow  machine-instance-id]
[:ssr     request-id      nav-token]
[:lease   :dashboard/opened user-id]
```

- **Route owners MUST include the navigation token.** `[:route :route/article]` is not precise enough — the same route can be entered multiple times with different params, pending work, or SSR request frames.
- **Ordinary event ids MUST NOT be durable owners** unless the event creates a releaseable lease. A manual refresh, a button click, or a one-shot dashboard open should usually be a **cause**, not an owner. If an event only wants to refresh data and does not intend to keep it active, it omits `:owner` and supplies only `:cause`.
- **Event-created owners MUST have a matching release path** (`:rf.resource/release-owner`).

### Release authority is per owner kind

Every owner kind names *who is authoritative for releasing it* so a lease cannot silently outlive the thing it represents (an orphaned owner pins an entry alive and keeps it refetching on focus/reconnect — a slow leak):

| Owner kind | Form | Release authority |
|---|---|---|
| **Route** | `[:route route-id nav-token]` | **Routing on nav-token supersession** — route leave or a superseded navigation releases the owner by its token, even when in-flight abort is unavailable (see [§Route integration](#route-integration)). |
| **Machine** | `[:machine machine-id instance-id]` | **Actor destroy** — when the owning machine instance is stopped/destroyed ([005-StateMachines](005-StateMachines.md)), its resource leases are released. Machine liveness is a pure function of frame-state, so a destroyed instance can hold no live lease. |
| **SSR** | `[:ssr request-id nav-token]` | **Request teardown** — an SSR owner belongs to one server render and is released when that request's frame is torn down; it never survives as a live client-side lease (it is reconciled to an orphan on hydration/restore — see [§Restore and replay](#restore-and-replay)). |
| **App / lease** | `[:lease …]`, `[:dashboard/opened …]`, and other app-minted kinds | **The app is authoritative** — an event that mints such a lease MUST have a matching `:rf.resource/release-owner` path. The framework does not auto-release app-minted leases. Xray surfaces an **orphaned-owner lint** for an app-kind owner whose minting event has no observed release path (or that pins an entry past its expected lifetime). |

### Causes explain why work happened

Causes are trace and diagnostic metadata. They answer "why did this happen?" without changing liveness, GC, polling, or refetch decisions:

```clojure
[:route-entry :route/article nav-token]
[:manual :article/refresh]
[:invalidate {:tags #{[:article "welcome"]}}]
:focus
:reconnect
:ssr-preload
:hydration
```

Ensure/refetch events accept both `:owner` and `:cause`. `:owner` changes the active-owner set; `:cause` is recorded in trace/resource history. Trace dispatch ids, event trace ids, and Xray focus state belong in cause/trace metadata, not in durable owners.

**Xray MUST NOT become an owner by observing.** Opening a devtool MUST NOT pin a resource, refetch it, extend GC, or alter polling. A future "pin this resource" debug action would be an explicit tool mutation with its own trace, not normal inspection.

### Sub-resources are ordinary resources

There is **no separate `sub-resource` primitive in v1**. A sub-resource is a naming, ownership, and invalidation relationship, not a different lifecycle — it still needs the same identity, status, owners, dedupe, SSR behaviour, and GC as any other resource. Model it as an ordinary resource whose params include the parent identity (`{:slug slug}` for `:article/comments`). Route metadata can then own both the parent resource and the child collection. If Xray later needs the relationship drawn, optional metadata (`:parent-resource` / `:resource/parent`) may be added for tooling; it MUST NOT change cache identity or lifecycle semantics.

## Lifecycle is an FSM

Every resource instance has a lifecycle. The **default implementation MUST be a compact transition function over the cache entry, not a spawned machine per resource entry.** Spawning a full machine per ordinary resource entry is prohibited in v1 — it would make common read caching heavier without improving correctness. Semantic retry, multi-step negotiation, streaming, and workflow-coupled reads graduate to explicit machines ([005-StateMachines](005-StateMachines.md)).

The transition function over the five states:

```text
:idle
  ensure/refetch without data    -> :loading

:loading
  success                        -> :loaded
  failure                        -> :error

:loaded
  stale/refetch                  -> :fetching
  fresh ensure                   -> :loaded  (fresh-skip: cache hit, no fetch; :rf.resource/cache-hit)
  invalidate (inactive)          -> :loaded  (stale timestamps / invalidated-at set)

:fetching
  success                        -> :loaded
  failure                        -> :loaded  (:refresh-error set; last-known-good :data preserved)
  superseded reply               -> previous stable state

:error
  refetch                        -> :loading
```

The resource FSM describes **cache-entry status**. The **work ledger** describes the **attempt lifecycle** that may currently be moving that cache entry: queued, running, abort-requested, completed, failed, timed out, suppressed, or cancelled (see [§Frame work ledger](#frame-work-ledger)). Resource `:status` MUST NOT be overloaded with host-handle state.

Transport retry belongs to the transport adapter — managed HTTP in the initial scope. **Semantic retry belongs to machines.**

## Status semantics

Resource state uses [Pattern-RemoteData](Pattern-RemoteData.md) semantics, but durable entries store **facts, not derived booleans**:

```clojure
{:status         :idle | :loading | :fetching | :loaded | :error
 :data           <last-known-good-or-nil>
 :error          <first-load-error-or-nil>
 :refresh-error  <background-refresh-error-or-nil>
 :loaded-at      <ms-or-nil>
 :stale-at       <ms-or-nil>
 :invalidated-at <ms-or-nil>
 :attempt        <int>
 :generation     <int>
 :request-id     <request-id-or-nil>
 :tags           <set>
 :active-owners  <set>}
```

This deliberately **refines** Pattern-RemoteData's broad `:error` state. The load-bearing invariants (MUST):

- **`:loading`** means **first load with no usable data**.
- **`:fetching`** means **work is in flight while prior data stays visible** (refresh / stale-while-revalidate).
- **`:error`** means the resource has **no usable data because the first load failed**.
- **`:refresh-error`** records a **failed background refresh** — the entry returns to `:loaded`, preserves the prior `:data`, and records the failure in `:refresh-error`. `:refresh-error` is cleared by the next successful load or refresh.
- **Freshness is orthogonal to load status.** A `:loaded` entry may be stale; a `:fetching` entry may be refreshing stale data.
- **`:stale?`, `:loading?`, `:fetching?`, and `:has-data?` are public derived subscription values, NOT durable stored facts.** Views MUST NOT have to infer "error with stale data" from `(:status state)` plus `(:has-data? state)`.

Worked projections (public `:rf.resource/state`, not durable entries):

First-load failure:

```clojure
{:status :error
 :data nil
 :error {:kind :rf.http/http-5xx :status 503}
 :refresh-error nil
 :has-data? false}
```

Background-refresh failure (prior data kept, refresh warning surfaced):

```clojure
{:status :loaded
 :data {:title "Welcome"}
 :error nil
 :refresh-error {:kind :rf.http/http-5xx :status 503}
 :has-data? true
 :fetching? false}
```

This keeps the `:loading` / `:fetching` promise intact: views never guess whether they are looking at a blank first-load failure or at stale data with a refresh warning. The `:error` envelope shape is the same [014-HTTPRequests](014-HTTPRequests.md) failure shape (`:kind` is one of the closed `:rf.http/*` taxonomy); `:refresh-error` carries the same envelope.

### Structural sharing

A successful load MUST preserve the old `:data` value when the newly decoded data is `=` to the previous data, so downstream subscriptions and views stay quiet when a background refresh returns identical EDN. This is the re-frame2 value model: compare values, preserve the old value when nothing changed, and make equality decisions observable in trace rows when they affect a resource transition. Large or non-EDN values may need a later explicit merge/structural-sharing hook; the v1 default is value-equality preservation.

## Cache home and write authority

Resource cache lives **only** at `:rf.runtime/resources` inside the runtime-db partition (`:rf.db/runtime`). The target frame-state shape:

```clojure
{:rf.db/app     <user-app-db>
 :rf.db/runtime
 {:rf.runtime/resources
  {:entries     {<scoped-resource-key> <entry>}
   :tag-index   {<tag> #{<scoped-resource-key> …}}
   :owner-index {<owner> #{<scoped-resource-key> …}}}

  :rf.runtime/work-ledger
  {<work-id> <work-record>}}}
```

Inside a full frame-state projection the resource path is `[:rf.db/runtime :rf.runtime/resources]`; inside runtime-db itself, framework code reads and writes `[:rf.runtime/resources]`. Both `:rf.runtime/resources` and `:rf.runtime/work-ledger` are reserved runtime-db keys (see [Conventions §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys)), allocated lazily and per-frame isolated.

### Write authority

`:rf.runtime/resources` and `:rf.runtime/work-ledger` are framework-owned runtime-db children, so resource writes MUST mint **framework-write authority**; ordinary app authority is not enough. Two paths carry resource writes (per [002 §Minting framework-write authority](002-Frames.md#minting-framework-write-authority)):

- **Event-handler authority.** Every resource `reg-event-fx` registration site stamps the reserved registration-meta key `:rf/framework-authority? true` (per [Conventions §Reserved registration metadata](Conventions.md#reserved-registration-metadata-framework-owned)). The runtime reads the stamp when assembling the event context, so a returned `:rf.db/runtime` effect from a resource handler is in-bounds. The handlers that carry it: `:rf.resource/ensure`, `:rf.resource/refetch`, `:rf.resource/invalidate-tags`, `:rf.resource/release-owner`, `:rf.resource/clear-scope`, `:rf.resource/remove`, and the internal replies `:rf.resource.internal/succeeded` / `:rf.resource.internal/failed` / `:rf.resource.internal/aborted` / `:rf.resource.internal/stale-fired` / `:rf.resource.internal/gc-fired` / `:rf.resource.internal/stale-suppressed`. Without this stamp, resources would be the *second* framework subsystem after routing to trip the `:rf.warning/app-handler-runtime-effect` ownership diagnostic on every fetch in dev — exactly the gap the generalized authority mechanism (rf2-3939ig, landed) and the runtime-subsystem contract's clause 2 exist to close.
- **Privileged-helper authority.** Stale/GC and host-handle bookkeeping that the resource runtime performs **outside** the event-handler path (scheduling timers, clearing host handles) goes through the privileged frame-state mutators (`swap-runtime-db!` / `replace-frame-state!`), bypassing the event-handler diagnostic — exactly as elision and SSR's non-event writes do.

`:rf/framework-authority?` is a **diagnostic-governing convention, not a capability gate** ([002](002-Frames.md) Mike ruling #4): the effect applies either way, and the flag governs only whether the ownership diagnostic fires. Resource handlers **never** write runtime-db through ordinary app authority.

### Runtime-subsystem graduation

`:rf.runtime/resources` is the runtime-subsystem contract's **first graduating instance and proof-case** outside the four shipped subsystems (machines / routing / elision / SSR). Each new runtime-db child MUST graduate against the five-clause contract defined normatively in [Runtime-Subsystems](Runtime-Subsystems.md).

This section is the **canonical home for the resource-trio grading rows** — `:rf.runtime/resources`, `:rf.runtime/work-ledger`, and (with the mutation slice) `:rf.runtime/mutations`. [Runtime-Subsystems §Grading table](Runtime-Subsystems.md#grading-table--the-shipped-subsystems) mirrors these three rows into its catalogue of all shipped subsystems; where the two differ, this section governs the resource-trio content.

#### `:rf.runtime/resources` — resource cache

| Clause | Grade |
|---|---|
| **1 Subtree** | ✅ `:rf.runtime/resources` with the closed slot set `:entries` / `:tag-index` / `:owner-index` ([Conventions §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys)). Allocated lazily — absent until the first resource write — and per-frame isolated. |
| **2 Write authority** | ✅ Event-handler path — every resource `reg-event-fx` stamps `:rf/framework-authority? true` (the rf2-3939ig mechanism); the internal reply handlers carry it too. Stale/GC side-table writes go through privileged frame-state helpers. See [§Write authority](#write-authority). |
| **3 Read API** | ✅ The `:rf.resource/*` sub family (`:rf.resource/state`, `:rf.resource/data`, `:rf.resource/status`, `:rf.resource/loading?`, `:rf.resource/fetching?`, `:rf.resource/stale?`, `:rf.resource/error`, `:rf.resource/refresh-error`, `:rf.resource/has-data?`, `:rf.resource/previous-data`) plus tool accessors (`resource-meta`, `resource-state`, `resources`, the `list-resource-instances` / `get-resource-state` family). App code never reads raw `[:rf.runtime/resources …]` paths. |
| **4 Projection / elision** | ✅ Allowlist-shaped — only the durable resource projection rides the `:rf/hydration-payload` `:rf/runtime-db` slice via the explicit projection hook ([§SSR and hydration](#ssr-and-hydration)); `:tag-index` / `:owner-index` are recomputable-from-`:entries` and need not ride the wire. Params, scopes, and data carry `:sensitive?` / `:large?` classification through the shared `rf/elide-wire-value` walker; Xray sees redacted summaries, not raw values. |
| **5 Teardown** | ✅ Side tables are keyed by frame id and work id; frame destroy cancels all resource timers and clears host handles for that frame ([§Stale and GC scheduling](#stale-and-gc-scheduling), [§Restore and replay](#restore-and-replay)). **Durable kept:** `:entries` (cache facts ride restore/SSR). **Transient dropped:** AbortControllers, stale/GC timers, transport promises (never serialized); `:tag-index` / `:owner-index` are recomputed from `:entries` on install. |

#### `:rf.runtime/work-ledger` — frame work ledger

| Clause | Grade |
|---|---|
| **1 Subtree** | ✅ `:rf.runtime/work-ledger` with serializable work records keyed by `:work/id` ([Conventions §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys)). Allocated lazily; per-frame isolated. Named **neutrally** by design — resources are its first writer, but later slices extend it to timers, streams, route loaders, spawned actors, and machine async work. |
| **2 Write authority** | ✅ *for the resource writer* — in the initial scope the ledger is written only through the resource event handlers (which stamp `:rf/framework-authority? true`). ⚠️ **OPEN multi-writer question.** The ledger is deliberately a **multi-writer** subsystem: when timers, streams, route loaders, spawned actors, and machine async work join as writers in later slices, who mints authority for each additional writer is an open clause to resolve **per writer**. Machines already imply authority via `:rf/machine? true`; non-machine future writers will each need to stamp `:rf/framework-authority? true` or write through the privileged helpers. This spec names the ledger neutrally and flags the multi-writer authority question as **unresolved, to be settled when the first non-resource writer lands** (see [§Open questions](#open-questions)). |
| **3 Read API** | ✅ Read by framework code and tools only — Xray's live work-ledger table per frame, SSR's blocking-drain wait point, and the resource runtime's join/dedupe logic. **No app-facing read sub by design** — app code observes work indirectly through `:rf.resource/*` subs (`:rf.resource/fetching?` etc.), never the ledger directly. |
| **4 Projection / elision** | ✅ Allowlist-shaped — only **non-terminal rows' summaries** ride the hydration/epoch wire; terminal rows are pruned to a bounded local Xray tail and are not durable wire payload ([§Ledger row retention and identity](#ledger-row-retention-and-identity)). Causes, owners, and deadlines carry the same privacy/size elision as resource metadata through `rf/elide-wire-value`. |
| **5 Teardown** | ✅ Host handles (AbortControllers, timeout/poll handles, promises) live in side tables keyed by `[frame-id work-id]`, cleared on frame destroy. **Durable kept:** the bounded set of non-terminal serializable records. **Transient dropped:** host handles; restored non-terminal rows are immediately reconciled to **dangling** (their `:work/id` can never re-match a live entry — the generation allocator is monotonic and host-side, [§Restore and replay](#restore-and-replay)). |

#### `:rf.runtime/mutations` — mutation-instance runtime

The causal-write counterpart of `:rf.runtime/resources`, shipped with the first public-beta gate (rf2-dwme29, [§Mutations](#mutations-first-public-beta-gate-rf2-dwme29)). It owns its own subtree (instance rows keyed by mutation **instance** id) but its in-flight attempt **rides the neutral `:rf.runtime/work-ledger`** (work-kind `:mutation`) rather than minting a fourth subtree — so clause 1 is the instance map and clause 2 reuses the work-ledger transport. Present only when the app registers a mutation.

| Clause | Grade |
|---|---|
| **1 Subtree** | ✅ `:rf.runtime/mutations` with serializable mutation **instance** rows keyed by instance id (`:mutation/id` / `:instance/id` / `:status` / `:result` / `:error` / `:scope` / `:params` / `:generation` / `:current-work` / `:started-at` / `:settled-at` / `:affected-keys` / `:patch-summary`; schema `MutationInstance` in [Spec-Schemas](Spec-Schemas.md#rfscoped-resource-key-rfresource-entry-rfresource-work-record-resources-spec-016)). Keyed by instance id (NOT mutation id) so concurrent submissions never clobber each other. Allocated lazily — absent in an app that registers no mutations ([Conventions §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys)). |
| **2 Write authority** | ✅ Event-handler path — `:rf.mutation/execute` / `:rf.mutation/clear` (and the internal reply handlers) stamp `:rf/framework-authority? true`; the in-flight write lowers through the **same managed-HTTP transport as resources**, joining a `:rf.runtime/work-ledger` record (work-kind `:mutation`) rather than a private side-table. Generation + work-id stale suppression is the correctness boundary as for resources (per [§Mutations](#mutations-first-public-beta-gate-rf2-dwme29)). |
| **3 Read API** | ✅ The passive `:rf.mutation/*` sub family — `:rf.mutation/state`, `:rf.mutation/status`, `:rf.mutation/pending?`, `:rf.mutation/result`, `:rf.mutation/error` — keyed by instance id, projecting the instance view-model. App code reads these, never raw `[:rf.runtime/mutations …]` paths. |
| **4 Projection / elision** | ✅ The instance rows store **facts, not derived booleans** (`:pending?` / `:success?` / `:settled?` are computed in the subs layer), so the durable row is a small projectable fact; `:affected-keys` / `:patch-summary` reserve the optimistic-rollback trace shape (optimistic itself DEFERRED). Params, result, and error carry `:sensitive?` / `:large?` classification through `rf/elide-wire-value`; the `:error` envelope is the closed `:rf.http/*` failure shape. |
| **5 Teardown** | ✅ Host handles live in the shared `[frame-id work-id]` side tables (cleared on frame destroy with the resource hook `:resources/on-frame-destroyed!`); `:rf.mutation/clear` is the causal instance reset (clears the runtime instance and best-effort aborts in-flight work). **Durable kept:** the instance rows (facts). **Transient dropped:** host handles; the in-flight work record reconciles to dangling on restore exactly as the resource writer's does (the generation allocator is monotonic and host-side — [§Restore and replay](#restore-and-replay)). |

## Frame work ledger

Resource entries are cached read-model facts. In-flight attempts are **work facts**. They are linked but not collapsed into one map. EP-0003 introduces the first concrete slice of a frame work ledger; in the landed surface **two writers** participate — the resource runtime (work-kind `:resource`) and, since the first public-beta gate (rf2-dwme29), mutations (work-kind `:mutation`, see [§Mutations](#mutations-first-public-beta-gate-rf2-dwme29)). Both lower through the same managed-HTTP transport. The shape is neutral enough that later slices extend it to route loaders, timers, streams, spawned actors, and machine async work without rewriting resource semantics. (Clause 2 of the [work-ledger grading row](#rfruntimework-ledger--frame-work-ledger) is satisfied for both these in-artefact writers, which both stamp `:rf/framework-authority? true`; the open multi-writer authority question concerns the *first writer outside the Resources artefact* — see [§Work-ledger multi-writer authority](#work-ledger-multi-writer-authority--still-blocking-for-the-multi-writer-slice-post-v1-tracked-for-v1).)

A resource entry points at its current work id:

```clojure
{:resource/id  :article/by-slug
 :status       :fetching
 :data         {:title "Welcome"}
 :generation   4
 :current-work [:rf.work/resource <scoped-resource-key> 4]}
```

The ledger records the serializable attempt:

```clojure
{:work/id      [:rf.work/resource <scoped-resource-key> 4]
 :work/kind    :resource
 :work/frame   frame-id
 :resource/key <scoped-resource-key>
 :generation   4
 :transport    :rf.http/managed
 :status       :running
 :owners       #{[:route :route/article nav-token]}
 :causes       [[:route-entry :route/article nav-token]]
 :cancellable? true
 :started-at   1780752000100
 :deadline-at  1780752005100}
```

Host handles remain **outside** durable frame-state, keyed by frame id and work id:

```clojure
[frame-id work-id] -> {:abort-controller … :timeout-handle … :promise …}
```

The durable/transient split (MUST):

- `:rf.runtime/resources` stores cache entries, tags, owner indexes, timestamps, data, errors, and the current work id for each entry;
- `:rf.runtime/mutations` stores serializable mutation **instance** rows (the causal-write counterpart), each pointing at its current work id;
- `:rf.runtime/work-ledger` stores serializable work records — status, owners, causes, attempts, deadlines, and outcomes — written by **both** the resource (`:work/kind :resource`) and mutation (`:work/kind :mutation`) writers;
- host side tables store non-serializable cancellation and timer handles keyed by frame id and work id, and are never serialized.

### Cancellation is opportunistic; stale suppression is mandatory

This is the correctness rule: **cancellation is opportunistic, while stale suppression is mandatory.** When an owner exits, a scope is cleared, a route is superseded, or a newer generation starts, the runtime MAY abort the host handle if it exists and can be cancelled. If the host cannot cancel it, the ledger and resource **generation checks MUST still suppress the late reply.** A stale reply MUST NEVER be able to mutate a newer resource entry.

SSR and tools observe the ledger **projection**, not host handles. SSR *waits on* blocking ledger records server-side, but the hydration payload serializes only the allowed **`:rf.runtime/resources` cache projection** — work-ledger rows do not ride hydration (in-flight work belongs to the server timeline that owns its host handles; the client has nothing to reconcile). Epoch restore (same-frame, same host) is the boundary that carries non-terminal work-ledger rows, so the reconciler can dangle them. Xray answers "what is still running?" from ledger records joined to resource entries and trace causes.

### Ledger row retention and identity

Two ledger-design points govern what rides the restore/hydration/epoch wire:

- **Terminal ledger rows are pruned; the ledger is bounded.** A work record reaches a terminal status (`:completed` / `:failed` / `:timed-out` / `:suppressed` / `:cancelled`) with an outcome summary. Left unbounded, the ledger would be unbounded growth in serializable frame-state — worse than trace growth, because it rides every epoch snapshot. The rule: a terminal row is **pruned on the linked entry's next successful transition**, with a small bounded per-resource-key tail retained only for Xray's recent-races view. Epoch snapshots carry only **non-terminal rows' summaries** so the post-restore reconciler can settle them to dangling ([§Restore and replay](#restore-and-replay)); terminal rows are local Xray history, not durable wire payload. **The SSR hydration payload is narrower than the epoch snapshot:** the landed SSR projector ([§SSR and hydration](#ssr-and-hydration)) ships only the durable `:rf.runtime/resources` `:entries` cache facts — it does **not** carry any work-ledger rows. In-flight work is meaningful only on the timeline that owns its host handles, so a freshly hydrated client has no dangling rows to reconcile; epoch restore (same-frame, same host) does, which is why work-ledger non-terminal rows are a **restore-snapshot reconciliation** concern, not a hydration payload.
- **One identity per work record.** The work record MUST NOT carry both a `:work/id` `[:rf.work/resource resource-key generation]` and a near-duplicate `:stale-key` `[:resource resource-key generation]` that differ only in their head keyword while denormalizing the same `resource-key` + `generation` facts. **Stale suppression keys on `:work/id`**; the separate `:stale-key` is dropped. There is exactly one identity per attempt to reconcile, and exactly one allocator (the generation allocator) that must never rewind.
  - **The frame-qualified transport request-id is the one sanctioned second identity.** The work-id is **frame-local** (its `resource-key` + `generation` carry no frame id), so it is *not* a safe process-global transport correlation token: the managed-HTTP in-flight registry keys by `:request-id` process-globally and supersedes/aborts by **equal** request-id ([Spec 014](014-HTTPRequests.md) §`:request-id`). Two frames issuing the same resource (or the same mutation instance) at the same generation mint the **same** work-id, so a bare-work-id request-id would let frame B supersede, abort, or suppress frame A's in-flight transport request. The runtime therefore lowers a **frame-qualified transport request-id** — `[:rf.req frame-id work-id]` — as the **deliberate second identity** this rule anticipates. It governs **only** transport-level in-flight correlation (registry keying, supersede-on-lower, opportunistic abort); intra-frame stale suppression still keys on `:work/id` + `:generation` (the durable identity). The opportunistic abort (`:rf.http/managed-abort`) MUST carry the **same** qualified token the lower registered, or it would miss the request (or, across frames, resolve a sibling frame's colliding request). This is **not** a `:stale-key`-style unexplained synonym: it is a justified transport-facing token with a distinct job (process-global uniqueness) the frame-local work-id structurally cannot fill.

## Public API

### Registration

```clojure
(rf/reg-resource resource-id resource-spec)
(rf/clear-resource resource-id)
```

`clear-resource` is a **registration-lifecycle** operation, not the normal cache invalidation API. Application code uses `:rf.resource/invalidate-tags`, `:rf.resource/remove`, or `:rf.resource/clear-scope` for data-lifecycle work. When a resource registration is cleared, the implementation MUST also dispose resource-runtime state for that resource id in each affected frame: release owner indexes, cancel timers/host handles, abort in-flight requests where possible, suppress late replies by generation, remove tag-index rows, and emit a trace.

A `:resource` registrar kind is added; do **not** add a `:query` public kind (it collides with route query params and prior-art names).

### Events (map payloads, not positional argument vectors)

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
```

The internal replies — `:rf.resource.internal/succeeded` / `:rf.resource.internal/failed` / `:rf.resource.internal/aborted` / `:rf.resource.internal/stale-fired` / `:rf.resource.internal/gc-fired` / `:rf.resource.internal/stale-suppressed` — are framework-internal and carry the verification payload (`:work-id`, `:resource-key`, `:scope`, `:generation`, `:rf.frame/id`); user code MUST NOT dispatch them directly.

### Subscriptions (passive)

```clojure
[:rf.resource/state         {:resource … :scope … :params …}]
[:rf.resource/data          {:resource … :scope … :params …}]
[:rf.resource/status        {:resource … :scope … :params …}]
[:rf.resource/loading?      {:resource … :scope … :params …}]
[:rf.resource/fetching?     {:resource … :scope … :params …}]
[:rf.resource/stale?        {:resource … :scope … :params …}]
[:rf.resource/error         {:resource … :scope … :params …}]
[:rf.resource/refresh-error {:resource … :scope … :params …}]
[:rf.resource/has-data?     {:resource … :scope … :params …}]
[:rf.resource/previous-data {:resource … :scope … :params …}]
```

**No v1 subscription fetches.** A subscription is a pure passive read; it resolves scope per [§Subscription-side scope resolution](#subscription-side-scope-resolution) and raises `:rf.error/resource-sub-unresolved-scope` rather than reading global or returning a silent `:idle`. A future `:rf.resource/live` side-effecting convenience, if added, MUST be explicitly documented as side-effecting and kept separate from the recommended route/event pattern.

### Introspection

```clojure
(rf/resource-meta :article/by-slug)
(rf/resource-state {:resource … :scope … :params … :frame :app/main})
(rf/resources      {:frame :app/main})
```

`:frame` is an explicit, app-registered frame id (`:app/main` is illustrative). Per [EP-0002](../docs/EP/EP-0002-frame-target-resolution.md) there is no ambient `:rf/default` fallback: the frame target is carried explicitly, and a frameless introspection call with no resolvable context fails closed rather than silently inspecting the wrong frame.

### Resource registration spec

```clojure
(rf/reg-resource
  :article/by-slug
  {:doc "Article detail by slug."

   :params-schema [:map [:slug :string]]
   :data-schema   :app/article

   :request
   (fn [{:keys [slug]} _ctx]
     {:request {:method :get :url (str "/api/articles/" slug)}
      :decode :app/article})

   :scope          :rf.scope/global   ;; REQUIRED — an explicit, auditable claim
   :transport      :rf.http/managed
   :stale-after-ms 60000
   :gc-after-ms    300000
   :tags           (fn [{:keys [slug]} _data] #{[:article slug]})
   :sensitive?     false})
```

**Required keys** (MUST):

- **`:params-schema`** — validates and canonicalizes params.
- **`:scope`** — the resource's **scope policy**, one of `:rf.scope/global`, a resolver, or `:rf.scope/from-caller` (see [§Scope resolution](#scope-resolution)). It is **required**: a `reg-resource` with no scope policy is a loud registration error (`:rf.error/resource-missing-scope-policy`). A genuinely process-independent resource declares `:scope :rf.scope/global` explicitly; there is no implicit default.
- **`:request`** — for `:transport :rf.http/managed` (the only initial-scope transport), returns a Spec 014 managed-HTTP args map, including the nested `:request` child and top-level keys such as `:decode`, `:accept`, `:retry`, and sensitivity metadata. The args map MUST NOT supply `:request-id`, `:on-success`, or `:on-failure` — resource lowering supplies those from the scoped resource key and current generation (see [§Transport](#transport)); implementations reject those reserved keys at registration or dispatch.

These three are the registration gate (`:scope` fail-closed first, then `:params-schema` and `:request`); a `reg-resource` missing any of them throws (`:rf.error/resource-missing-scope-policy` for `:scope`, `:rf.error/invalid-resource-spec` for `:params-schema` / `:request`). The set mirrors `reg-mutation`'s (`:params-schema` + `:request` + `:scope`).

**Optional v1 keys:** `:doc`, `:data-schema`, `:transport` (initial scope: `:rf.http/managed`, the only built-in), `:stale-after-ms`, `:gc-after-ms`, `:tags`, `:sensitive?` / `:large?` / schema-based classification.

`:data-schema` is **optional** — when present it validates successful data wherever transport decode supports it (and contributes per-slot `:sensitive?` / `:large?` redaction marks); when absent, response data is not shape-validated. Unlike `:params-schema` (the resource's identity, REQUIRED) and `:scope` (the fail-closed security boundary, REQUIRED), a resource is well-formed without `:data-schema`, so the registration gate does not enforce it — matching the shipped reference implementation and the flagship example, which omit it.

**Deferred keys** (rejected / unused in v1): `:poll-ms`, `:revalidate`, `:placeholder`, transport extension protocols, `:cache-key`, `:infinite`, and mutation-only keys (`:invalidates`, `:optimistic`, `:rollback`).

### No `:select` key

Do **not** add a TanStack-style `:select` key in v1. In re-frame2, projections are ordinary subscriptions layered over `[:rf.resource/data …]` ([EP-0004](../docs/EP/EP-0004-subscription-inputs.md) parametric inputs). That is not a missing feature; it is a structural advantage of the subscription graph.

### Mutations (first public-beta gate, rf2-dwme29)

A **mutation** is a named, causal WRITE to remote state that, on success, invalidates / patches / populates cached resource reads — the write counterpart of the read-resource grammar. The full normative contract lives in [EP-0003 §Mutations](../docs/EP/EP-0003-resource-queries.md#mutations); this section names the landed surface.

```clojure
(rf/reg-mutation
  :article/save
  {:params-schema :app/article          ;; REQUIRED — validates + canonicalizes params
   :request                             ;; REQUIRED — the Spec 014 managed-HTTP write
   (fn [{:keys [slug] :as article} _ctx]
     {:request {:method :put :url (str "/api/articles/" slug) :body article}
      :decode  :app/article})
   :invalidates  (fn [{:keys [slug]} _result] #{[:article slug] [:article-list]})
   :patches      (fn [params result] {scoped-key (fn [old result] (merge old result))})
   :populates    (fn [params result] {scoped-key result})
   :scope        :rf.scope/global       ;; the cache scope invalidation/patch defaults to
   :invalidate-timing :after-success})  ;; | :before-request | :after-failure | :after-settle

(rf/clear-mutation :article/save)        ;; registration-lifecycle removal (NOT a form-error reset)
```

Run a mutation with the `:rf.mutation/execute` event and observe it through the passive `:rf.mutation/*` subs, keyed by an **instance** id:

```clojure
[:rf.mutation/execute
 {:mutation :article/save
  :params   article
  :instance :form/save-1        ;; caller-supplied (or generated) instance id
  :scope    [:rf.scope/session {:user-id "u-42"}]
  :cause    [:form-submit :article/save]}]

[:rf.mutation/clear {:instance :form/save-1}]   ;; the causal instance reset

[:rf.mutation/state    {:instance :form/save-1}]   ;; {:status :result :error :pending? :success? :error? :settled?}
[:rf.mutation/status   {:instance :form/save-1}]
[:rf.mutation/pending? {:instance :form/save-1}]
[:rf.mutation/result   {:instance :form/save-1}]
[:rf.mutation/error    {:instance :form/save-1}]
```

A `:mutation` registrar kind is added (the causal-write counterpart of `:resource`). The load-bearing invariants (MUST):

- **Runtime state is keyed by mutation INSTANCE id, not mutation id** — two concurrent submissions of `:comment/add` keep distinct `:pending` / `:success` / `:error` rows and never clobber each other ([EP-0003 §Mutations](../docs/EP/EP-0003-resource-queries.md#mutations)). The instance id is caller-supplied or generated (the generated id closes over the monotone generation, so concurrent generated submissions differ).
- **The write lowers through the SAME managed-HTTP transport as resources** — the runtime owns reply addressing (`:request-id` / `:on-success` / `:on-failure` are supplied from the instance + generation; an app `:request` that supplies them is rejected). Generation + work-id **stale suppression** is the correctness boundary exactly as for resources: a superseded reply (a re-execute under the same instance, or an `:rf.mutation/clear`) NEVER overwrites a newer instance. Abort+retry are inherited from the transport; **write retries are OPT-IN** (a mutation arms `:retry` only when its `:request` declares it).
- **Success patches/populates resource entries, then invalidates tags** — the controlled `:patches` / `:populates` transform / seed resource entries (through the same durable entry shape + structural sharing the read path uses) BEFORE the success-time invalidation; `:invalidates` then composes with the landed `:rf.resource/invalidate-tags` (scoped). **Invalidation timing** is explicit (`:before-request` / `:after-success` (default) / `:after-failure` / `:after-settle`).
- **Failure settles `:error`** (no `:refresh-error` analogue — a write has no last-known-good to keep); `:rf.mutation/clear` is the causal reset that clears the runtime instance (and best-effort aborts in-flight work).
- **Trace-visible instance ids** — the `:rf.mutation/*` trace family (`started` / `succeeded` / `failed` / `cleared` / `stale-suppressed`) carries the instance id; the success trace reserves the optimistic-rollback shape (affected keys, patch summary; snapshot/rollback/reconciliation slots) — **optimistic rollback itself is DEFERRED**.

## Transport

The initial scope ships a **single built-in transport**:

```clojure
:transport :rf.http/managed
```

The resource lifecycle, cache identity, owner model, stale/fresh policy, invalidation, SSR hydration, and Xray surfaces MUST nonetheless be **transport-neutral**: the core does not assume a URL, HTTP method, status code, or request body — those are HTTP transport details — so the deferred GraphQL transport (and any later transport) can plug in without weakening the core semantics. The core also does not assume a normalized entity graph, fragment store, or GraphQL client cache.

For HTTP, the resource runtime first creates or joins a work-ledger record, then lowers an ensure/refetch into managed HTTP:

```clojure
[:rf.http/managed
 (assoc http-args
        :request-id [:rf.req frame-id work-id]        ; frame-QUALIFIED transport correlation token
        :on-success [:rf.resource.internal/succeeded
                     {:work-id work-id :resource-key resource-key
                      :scope scope :rf.frame/id frame-id :generation generation}]
        :on-failure [:rf.resource.internal/failed
                     {:work-id work-id :resource-key resource-key
                      :scope scope :rf.frame/id frame-id :generation generation}])]
```

**The transport `:request-id` is the frame-qualified token `[:rf.req frame-id work-id]`, not the bare work-id** (the same shape for the resource and mutation writers). The managed-HTTP in-flight registry keys by `:request-id` process-globally and supersedes/aborts by **equal** request-id ([Spec 014](014-HTTPRequests.md) §`:request-id`), and the work-id is frame-local, so the bare work-id would collide across frames (frame B superseding frame A's in-flight request for the same scoped key + generation). The qualified token isolates frames; the matching opportunistic abort (`:rf.http/managed-abort`) carries the **same** token. This is the [deliberate second identity](#ledger-row-retention-and-identity) — it governs only transport-level in-flight correlation, while intra-frame stale suppression continues to key on `:work/id` + `:generation`. The reply payloads carry the bare `:work-id` (the durable identity the receiving frame verifies against its entry/instance), independent of the transport correlation token.

The internal reply payloads stamp the intended frame with the qualified `:rf.frame/id` key — the canonical carried frame stamp for new framework causal tokens ([EP-0002](../docs/EP/EP-0002-frame-target-resolution.md) R3, "one canonical frame stamp") — matching the qualified `:work/frame` stamp on the ledger record. The bare `:frame` opt remains the public dispatch/subscribe target opt, unchanged. The managed-HTTP reply dispatch is already frame-targeted by Spec 014; the resource metadata still carries the intended frame id for assertion, stale-suppression diagnostics, and trace rows. **Success and failure events MUST verify frame, work id, and generation before writing.** Cancellation is an optimization; stale suppression is the correctness boundary.

The runtime owns reply addressing and request correlation; an app `:request` that bypasses stale-suppression by supplying `:request-id` / `:on-success` / `:on-failure` is rejected. Generic transport extension is desirable but is a later extension protocol after the HTTP built-in proves the resource semantics.

## Race and in-flight semantics

These cases are normative:

- **`ensure` while the same scoped key is already in flight** joins the existing current work record, attaches any supplied owner to both the resource entry and ledger row, records the new cause, and emits a dedupe trace.
- **`refetch` may force a new generation.** If a prior request is still in flight, mark the old work record superseded, abort it when possible, and otherwise suppress its late reply by work id and generation.
- **Invalidation while a request is in flight** marks the entry stale and records the invalidation. If the in-flight request is for the current generation, its success may satisfy the invalidation only when policy says the request covered the invalidated identity; otherwise schedule a follow-up refetch.
- **Owner release while a request is in flight** aborts only when no remaining owner needs that work record. Shared requests MUST NOT be cancelled just because one route, machine, or lease went away.
- **Route supersession uses both nav-token owner release and generation checks.** The old nav-token MUST NOT write into the new route's resource state.
- **Stale/GC timers are advisory.** A timer handler MUST re-read the current entry, scope, owners, and generation before writing — a newer event may already have refreshed, invalidated, removed, or re-owned the entry.

## Stale and GC scheduling

`:stale-after-ms` and `:gc-after-ms` are v1 features, so their scheduling is part of v1. Rules (MUST):

- freshness is computed from **durable timestamps** (`:loaded-at`, `:stale-at`), not from trusting that a timer fired exactly on time;
- a stale timer may enqueue a resource event, but the handler MUST re-check the current entry before writing;
- inactive GC may use host timers, but GC MUST re-check owner sets and entry generation after wake;
- timers and host handles live in side tables, not in frame-state;
- **frame destroy cancels all resource timers for that frame**;
- a hidden tab can delay timers without corrupting correctness; on focus or reconnect, the active-stale revalidation scan (the `:rf.resource/window-focused` / `:rf.resource/network-reconnected` events, rf2-vtblcq) scans the frame's active-owner stale entries and refetches them by event (cause `:focus` / `:reconnect`, never an owner — it creates no liveness; generation + stale-suppression protect late replies). The host `window` focus / online listeners are installed per-frame by `install-revalidation-listeners!` and cancelled on frame destroy via the single `:resources/on-frame-destroyed!` hook (composed with the work-ledger / timer / generation host-cache release — one teardown path).

## Invalidation

V1 supports **exact tag invalidation**:

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

On successful load, the tag index for that scoped resource key is **replaced** with the tags produced by the new data; old tags MUST be removed so stale list/detail relationships do not keep receiving invalidations after the data changed.

Invalidation can be batched: a single event may carry many tags, but it emits one decision summary plus per-entry details so Xray shows broad-tag storms without flooding the trace. Broad invalidations are allowed but MUST be visible and lintable. **Scoped invalidation is the default**; a cross-scope invalidation opts in explicitly. If an invalidation has no matches, Xray distinguishes "no match in this scope" from "no resource provides this tag in any scope." Invalidation does not pretend to be derivable — the server is the source of truth and the client often lacks enough semantic information.

## Route integration

`:resources` is added as **route metadata**:

```clojure
(rf/reg-route
  :route/article
  {:path "/articles/:slug"
   :params [:map [:slug :string]]
   :resources
   [{:resource  :article/by-slug
     :params    (fn [route] {:slug (get-in route [:params :slug])})
     :scope     (fn [_route ctx] (:current-session-scope ctx))
     :blocking? true}

    {:resource  :comments/list
     :params    (fn [route] {:slug (get-in route [:params :slug])})
     :when      (fn [route _ctx] (some? (get-in route [:params :slug])))
     :blocking? false
     :keep-previous? true}]})
```

[012-Routing](012-Routing.md) currently rejects unknown bare route-metadata keys at registration. The resources artefact MUST therefore extend the routing accepted-key set, via a late-bound framework extension, so `:resources` is treated like the existing cross-feature `:head` key. Without that integration, a route containing `:resources` is correctly rejected by the routing artefact.

**On route entry:** routing resolves the route and nav-token; `:when` predicates are evaluated; scopes and params are computed and validated; each resource is marked active with owner `[:route route-id nav-token]`; each is ensured with cause `[:route-entry route-id nav-token]`; blocking resources are tracked under the nav-token; non-blocking resources fetch in the background; failures in blocking resources update route transition/error state; Xray can display the route/resource graph without parsing handlers.

**On route leave or superseded navigation:** route-owned resources are released by owner token; in-flight work is aborted only when no remaining owner still needs it; stale replies are suppressed by generation/nav-token even when abort is unavailable; inactive resources become eligible for `:gc-after-ms` cleanup.

`blocking?` is defined precisely: it keeps the route transition in a loading/pending state; it gives SSR a wait point before render; it does **not** have to block URL commit or prevent a client skeleton from rendering; if hydrated data is already fresh, it does not block. Existing `:on-match` remains canonical for arbitrary route-entry work — `:resources` is declarative server-state metadata layered beside it, not a second router.

Route resources MUST define **params-failure behaviour explicitly**: a failed params schema is a route/resource planning error visible in route state and Xray, not a silent cache miss. Conditional resources use `:when` rather than sentinel `nil` params. **Dependent** route resources are modeled as a route plan, not a hidden view effect: a route resource may declare a local `:id`, and another may declare `:after #{local-id}` to order their ensure-dispatch. **`:after` is dispatch-order only, not a data-waterfall** (landed semantics, rf2-xeb4l1): the route plan is a *pure synchronous planner* that resolves every entry's params and scope at route entry — before any resource can settle — so a later entry's params CANNOT depend on an earlier entry's loaded **data**; that would require re-running the plan after each settle, a deferred slice. What `:after` DOES guarantee is **ensure-dispatch order**: a dependent entry's `:rf.resource/ensure` is dispatched after every entry it names, so the dependency's fetch is kicked off first (the params still come from the *route*, not the dependency's data). `:after` MUST target route-local `:id`s (the same resource can appear more than once with different params), and ordering is **fail-closed**: an `:after` target naming an undeclared local id, or an `:after` cycle, is a route/resource planning error (`:recovery :fix-after`, surfaced on the route slice + Xray), never a silent fall-back to declaration order. Xray reads the declared `:after` edges to show the dependency graph. (A true data-waterfall — an entry's params computed from another's loaded data — is a deferred slice, [§Deferred slices](#deferred-slices).)

Routes are **not required** — an app can use resources entirely from events and machines (with explicit owners and a matching release path); it then gets canonical identity, stale/fresh policy, dedupe, invalidation, GC, passive subscriptions, and Xray visibility, but not route ownership, route-leave release, route transition blocking, or SSR route preload.

### Paginated and previous data

Paginated tables, filtered lists, search results, and cursor feeds are ordinary resources in v1 (they do not wait for "infinite resources"). The pattern: include every filter, sort, page, cursor, and server-visible option in params; tag both the list identity and any returned item identities; keep old data visible while a new page/filter resource is first-loading when the route/resource declaration opts into `:keep-previous?`. The public `:rf.resource/state` projection makes the distinction explicit:

```clojure
{:status :loading
 :data nil
 :previous? true
 :previous-key [scope :articles/list {:page 1 :filter "recent"}]
 :previous-data [{:id 1 :title "Old page"}]
 :placeholder? false}
```

`previous-data` is a projection from the prior key; it is **not** inserted into the new cache entry and MUST NOT provide tags for the new key. The new entry becomes ordinary `:loaded` data only after its own request succeeds. Cache growth for list params is controlled by the same owner and GC rules; `:keep-previous?` MUST NOT pin old pages beyond their owners.

## SSR and hydration

SSR MUST use request-local frames — a process-global resource cache would leak data between users.

**Server route handling:** resolve the route; compute route resources; enqueue blocking resource ensures; drain until blocking resources for the current nav-token settle; render with the settled resource state; serialize only the allowed resource runtime projection; record projection metadata (serialized, redacted, omitted, fresh, stale, refetch-on-client decisions).

Blocking SSR resources need a **timeout policy**: a timeout settles the resource as a structured first-load failure for that SSR frame, records the route blocking failure, and lets the renderer choose error markup, a skeleton, or an application fallback. It MUST NOT hang the request indefinitely.

**Client hydration:** install the allowed resource projection into the target frame-state's `:rf.runtime/resources` slice in runtime-db (`:rf.db/runtime`); preserve hydrated resource entries; avoid duplicate immediate fetches for fresh entries; background-refetch stale entries according to policy; maintain frame and nav-token isolation.

Do **not** serialize all of `:rf.db/runtime` by default. Resource hydration uses an **explicit projection hook** (the allowlist-by-subsystem-child `project-runtime-db` of [011-SSR](011-SSR.md)) that can redact or omit sensitive and large data. Hydration MUST NEVER cross scopes: request-local SSR frames and serialized resource scopes MUST agree before a client treats hydrated data as usable.

Hydration rules (MUST): `loaded-at` / `stale-at` / `invalidated-at` are absolute timestamps, and server clock skew is surfaced in trace/hydration diagnostics when it makes freshness ambiguous; omitted or redacted entries hydrate as metadata only and refetch on the client if the route still needs them; stale hydrated entries may render their data immediately, then refetch by resource event according to policy; `refresh-error` serializes only when the error envelope is allowed by the same privacy/size projection as data.

## Restore and replay

Resources are runtime-managed read models over an in-flight work ledger, so a "time-travel-safe" claim is not credible until `restore-epoch` (the EP-0001 epoch restore / time travel, sharing the same install path SSR hydration uses) is defined for `:rf.runtime/resources` and `:rf.runtime/work-ledger`. Epoch restore installs **both partitions wholesale** — it replaces the entire frame-state value (`:rf.db/app` plus `:rf.db/runtime`) and does not run ordinary `:db` effect semantics ([EP-0001 §Full-frame restore](../docs/EP/EP-0001-frame-partitions.md)). Host side tables (AbortControllers, stale/GC timers, transport promises) are **not** frame-state and are **not** rewound; they are transient by the EP-0001 durable/transient boundary. Restore must reconcile a freshly-installed *durable snapshot* against the *live transient world* (host handles still attached to the pre-restore timeline, and network replies already on the wire the runtime cannot recall).

The governing principle is the **anti-recycling rule** (the routing nav-token discipline, generalized): a restored value MUST NEVER let a stale generation or work-id be mistaken for a live one. Epoch restore MUST NOT resurrect a superseded in-flight identity, and MUST NOT rewind any monotonic allocator such that a post-restore allocation can collide with a pre-restore identity still carried by an uncancellable in-flight reply.

The contract has five parts.

### 1. The generation allocator is monotonic and host-side; it does not rewind

A resource generation is the correctness boundary for stale-reply suppression: a reply may write an entry only if its work-id and generation still match the live entry. If restore rewound the generation, a pre-restore in-flight reply — already on the wire, uncancellable — could return carrying a generation the post-restore timeline has re-allocated, and be silently accepted as live.

Therefore the **generation allocator is a per-frame, host-side monotonic high-water mark**, not a value rewound by restore (the routing nav-token-counter precedent: keep the active identity durable on the entry, restored coherently, but keep the *allocator* host-side so it only moves forward across restores). After a restore, the next generation strictly exceeds every generation any pre-restore in-flight reply could carry, so a stale reply's generation can never match a live entry's — collision is structurally impossible.

This is deliberately the *opposite* discipline from machine spawn-ids ([005 §Spawn-id allocator](005-StateMachines.md#spawn-id-allocator--counter-location)), and the difference is principled: **an allocator whose identity can be carried by an out-of-frame, uncancellable continuation must never rewind; an allocator whose identities never leave the frame may be snapshot-local and replay-deterministic.** A spawn-id never escapes the frame; a resource generation governs acceptance of a reply that *has* escaped, so it must never be re-issued. The work-ledger `:work/id` (which embeds the generation) inherits the same monotonicity.

### 2. In-flight work does not survive restore as live work

Every non-terminal row in the installed snapshot (`:queued` / `:running` / `:abort-requested`) references a request whose host handle no longer belongs to the restored timeline. A restored non-terminal row is therefore **dangling**. On install, restore reconciles non-terminal rows:

- the row's `:work/id` is recorded as **dangling/superseded** (it can never again match a live entry, because the allocator has moved past it per part 1), and its host side-table slot is cleared;
- the linked resource entry's `:current-work` pointer is cleared, because the attempt it pointed at no longer exists;
- the entry's `:status` settles to its last *stable* status from the restored snapshot — `:loaded` if it has usable `:data`, `:error` if it was a failed first load with no data, `:idle` if it never loaded — never left stranded in `:loading` / `:fetching` pointing at a vanished request;
- any pre-restore reply that subsequently lands is suppressed by the ordinary work-id + generation check, because its identity is now dangling. **No stale reply may mutate a post-restore entry** — this is the mandatory stale-suppression boundary, not a new mechanism.

Whether the restored entry then *re-fetches* is a freshness decision (part 3), not an in-flight decision. Restore never silently continues old work.

### 3. Freshness after restore: lazy, not an eager refetch storm

Restored entries carry absolute timestamps from the restored epoch. Two failure modes must be avoided: an **eager refetch storm** (every restored entry refetches at once) and **silent acceptance of misleadingly-fresh timestamps**. The ruling (consistent with hydration, which faces the identical absolute-timestamp problem):

- restore does **not** eagerly refetch — freshness is evaluated lazily, exactly as hydration handles it: a restored entry renders its data immediately and refetches only on the next `ensure` from a live owner (route re-entry, focus/reconnect revalidation, or an explicit event), gated by the entry's own stale/fresh policy;
- a restored entry with **no active owner** is never refetched on the strength of restore alone — it is subject to ordinary GC eligibility (part 5);
- absolute-timestamp ambiguity (a restored `:stale-at` implausible against the live clock) is surfaced in a restore/hydration trace diagnostic, exactly as clock skew is for SSR hydration, rather than silently trusted;
- this yields the desired property: **a restored epoch double-fetches nothing.** Refetch happens only when a live cause demands it.

### 4. Owners revive or orphan by kind

Restored `:active-owners` reference owner tokens from the pre-restore timeline. Whether a restored owner is *real* depends on whether the thing it names is itself revertible:

- **Machine owners** (`[:machine machine-id instance-id]`) revive — machine liveness is a pure function of the restored snapshot ([005](005-StateMachines.md)), so a machine owner the snapshot revives is a genuine live lease again.
- **Route owners** (`[:route route-id nav-token]`) revive **only if** the restored routing state names the same live nav-token (`:current` is durable). A restored route owner whose nav-token is not the one the restored routing slice currently considers live is released as an **orphan**.
- **Lease/event owners** (`[:lease …]`, `[:dashboard/opened …]`) revive with the snapshot (recorded durably on the entry); their release path is the same explicit `:rf.resource/release-owner`.
- **SSR owners** (`[:ssr request-id nav-token]`) do not survive a client-side restore as live leases; they belong to a settled server render and are released as orphans if present.

Owner reconciliation runs on install: each restored owner is checked against the revived runtime state, surviving owners stay in `:active-owners` and the `:owner-index`, and orphaned owners are dropped with a trace row.

### 5. Transient side tables and indexes are recomputed or cleared on install

- **Host transients are cleared, then recomputed on demand.** Stale timers, GC timers, AbortControllers, and transport promises are frame-scoped host handles restore does not rebuild (EP-0001 decision 13). On install they are cleared for the affected frame; stale/GC scheduling is re-armed lazily from the restored entries' durable timestamps the next time the runtime touches each entry (timers are advisory and re-checked against durable facts).
- **Indexes are recomputed from entries, never trusted from the snapshot.** `:tag-index` and `:owner-index` are derived projections of the entries' `:tags` and `:active-owners`. They are **recomputable-from-entries**: on restore (and on SSR hydration) they are rebuilt from the installed `:entries` rather than read from the serialized snapshot, so a stale or partial index can never outlive the entries it describes. This single rule also serves SSR hydration: hydration likewise installs `:entries` and recomputes the indexes, so the durable wire payload need not carry them at all.

## Xray and AI tooling

Resources need a trace/accessor contract, not only panel UI. Xray exposes: a static resource registry (id, source coordinates, params/data schemas, request summary, stale/GC policy, tag producer, scope resolver, sensitivity classification, declaring routes); a live resource-instance table per frame (key, scope, status, timestamps, generation, request id, attempt, active owners, tags, errors, data summary, GC eligibility); a live work-ledger table per frame (work id, kind, linked resource key, generation, status, owners, causes, cancellable?, deadline, retry attempt, outcome); a route/resource graph; a lifecycle timeline; an invalidation/mutation graph; a cache-growth view; and a **scope audit surface** — the standing enumeration of every `:rf.scope/global` resource (the structural security-review list that replaces the old `/me` heuristic) plus the suspicious-explicit-global warnings.

Two lints ride the cache-growth / audit surface:

- **Scope-mismatch lint** — a cache **entry** exists for resource `R` + params `P` under scope `A` while a **live subscription** reads the same `R` + `P` under a **different** scope `B` and gets `:idle` (or `:loading` that never resolves). The fail-closed scope rules make a missing scope a loud error; this lint is the runtime tripwire for the cases that slip through (e.g. an event ensured under `[:rf.scope/session {…}]` while a view subscribed under `[:rf.scope/global]`). Xray flags the mismatched (entry-scope, sub-scope) pair so the divergence is obvious rather than a permanent silent skeleton.
- **Orphaned-owner lint** — an app-minted `[:lease …]` owner with no observed release path (see [§Release authority is per owner kind](#active-owners-and-causes)).

Tool APIs prefer summaries and metadata over raw values — an AI usually needs "this route owns `:article/by-slug`, it is stale, and the latest background refresh failed with a 503", not the full article body. Resource history MUST be bounded, and params/scopes get the same privacy and size elision as data (scopes can contain user ids, tenant ids, locale, or impersonation markers) through the shared `rf/elide-wire-value` walker. Candidate tool accessors — `list-resources`, `list-resource-instances`, `get-resource-state`, `get-resource-history`, `list-resource-invalidations` — filter by frame, scope, resource id, tag, owner, status, stale?, request id, and nav-token; raw data access continues to go through existing egress and elision rules.

The artefact adds a `:rf.resource/*` trace family with operations such as `:rf.resource/registered` (one row per FIRST-TIME `reg-resource`, frame-agnostic — the registration anchor of the family; symmetric with `:rf.route/registered` / `:rf.flow/registered`), `:rf.resource/ensure`, `:rf.resource/owner-attached` (a NEW owner lease landing on an entry — both on a fresh load and on a dedupe join; symmetric with `:rf.resource/owner-released`), `:rf.resource/cache-hit` (a *fresh-skip* ensure — an `ensure` of an already-`:loaded` entry still fresh-by-policy serves the cached value, neither fetching nor joining in-flight work; distinct from `:rf.resource/deduped`), `:rf.resource/deduped`, `:rf.resource/fetch-started`, `:rf.resource/work-started`, `:rf.resource/work-abort-requested`, `:rf.resource/work-completed`, `:rf.resource/succeeded`, `:rf.resource/failed`, `:rf.resource/refresh-failed`, `:rf.resource/invalidated`, `:rf.resource/refetch-decision`, `:rf.resource/owner-released`, `:rf.resource/stale-scheduled`, `:rf.resource/stale-fired`, `:rf.resource/gc-scheduled`, `:rf.resource/gc-fired`, `:rf.resource/gc-skipped`, `:rf.resource/removed`, `:rf.resource/stale-suppressed` (the entry + ledger stale/superseded-reply suppression — the single suppression op the runtime emits; an earlier draft named a separate `:rf.resource/work-suppressed`, now folded into this one — there is exactly one suppression op, not two), `:rf.resource/route-plan` (the route `:resources` plan summary on route entry — route id, nav-token, ensured count, blocking scoped keys; the route/resource graph signal), `:rf.resource/revalidate-scan` (the focus/reconnect active-stale scan summary — the revalidation signal, the `:focus` / `:reconnect` cause, the scanned-entry count, and the refetched scoped keys; the per-entry refetch decisions ride the ordinary refetch traces), `:rf.resource/hydrated`, and `:rf.resource/hydrate-refetch` (one per hydration refetch-plan entry — the per-entry decision that a hydrated entry was not sufficient on its own, `:reason` `:no-data` / `:stale` / `:metadata-only`, distinct from the ordinary refetch the route slice then dispatches). Each carries, where applicable, frame, work id, scope, resource key/id, params summary, generation, request id, owner, cause, status before/after, work status, resource/invalidated tags, freshness timestamps, and redaction/size markers.

> **Fresh-skip op — `:rf.resource/cache-hit`.** The family emits `:rf.resource/cache-hit` for a *fresh-skip* ensure — an `ensure` of an already-`:loaded` entry that is still fresh-by-policy, so it neither dedupes (no in-flight work to join) nor starts a fetch (the cached value is sufficient). That is genuinely distinct from `:rf.resource/deduped` (joining an in-flight request). The fresh-skip *behaviour* is mandated by the FSM (a `:loaded` entry transitions to `:fetching` only on `stale/refetch`; a fresh `ensure` has no transition) and by [§Restore and replay](#restore-and-replay) ("refetches only on the next `ensure` from a live owner … gated by the entry's own stale/fresh policy"). The reference implementation short-circuits a fresh `:loaded` ensure: it attaches the supplied owner lease (a `:rf.resource/owner-attached` row covers a newly-attached owner), emits `:rf.resource/cache-hit`, drains any blocking route slot immediately (a fresh blocking resource settles the navigation at once — it treats the fresh entry as already-`:success`, so a route blocked on a fresh resource never hangs), and starts no new generation / fetch / work record. A `refetch` is never a fresh-skip (it always forces a new generation); a STALE `:loaded` entry still refetches on the next `ensure` (fresh-skip never swallows a stale refresh). The cache-hit needs no `:previous-key` projection (the entry has its own fresh data), arms no timers, and supersedes nothing.

## Examples

### Route-driven page load

```clojure
(rf/reg-resource
  :article/by-slug
  {:params-schema [:map [:slug :string]]
   :data-schema   :app/article
   :scope         :rf.scope/global
   :request (fn [{:keys [slug]} _]
              {:request {:method :get :url (str "/api/articles/" slug)}
               :decode :app/article})
   :stale-after-ms 60000
   :gc-after-ms    (* 5 60 1000)
   :tags (fn [{:keys [slug]} _] #{[:article slug]})})

(rf/reg-route
  :route/article
  {:path "/articles/:slug"
   :params [:map [:slug :string]]
   :resources
   [{:resource  :article/by-slug
     :params    (fn [route] {:slug (get-in route [:params :slug])})
     :scope     (fn [_route ctx] (:current-session-scope ctx))
     :blocking? true}]})

(rf/reg-view article-page []
  (let [slug  (:slug @(rf/subscribe [:rf.route/params]))
        scope @(rf/subscribe [:session/resource-scope])
        state @(rf/subscribe [:rf.resource/state
                              {:resource :article/by-slug :scope scope :params {:slug slug}}])]
    (cond
      (:loading? state)                         [article-skeleton]
      (and (:error state) (not (:has-data? state))) [article-error (:error state)]
      :else [:<>
             [:article-view {:article (:data state)}]
             (when (:fetching? state)      [refresh-indicator])
             (when (:refresh-error state)  [refresh-error (:refresh-error state)])])))
```

The view is passive; the route caused the ensure; the runtime owns the state. The declared `:scope :rf.scope/global` on the registration is the resource's auditable claim; the route's `:scope` resolver supplies the concrete session scope for this entry.

### Event-driven ensure

```clojure
(rf/reg-event-fx
  :dashboard/opened
  (fn [_ [_ user-id]]
    {:fx [[:dispatch [:rf.resource/ensure
                      {:resource :dashboard/summary
                       :params   {:user-id user-id}
                       :owner    [:lease :dashboard/opened user-id]
                       :cause    [:event :dashboard/opened]}]]]}))
```

The `[:lease …]` owner is app-minted, so the app owns its release: a matching `:rf.resource/release-owner {:owner [:lease :dashboard/opened user-id]}` MUST exist on dashboard close.

### Machine-owned resource

```clojure
{:actions
 {:ensure-quote
  (fn [{:keys [data]}]
    {:fx [[:dispatch [:rf.resource/ensure
                      {:resource :checkout/quote
                       :params   {:cart-id (:cart-id data)}
                       :owner    [:machine :checkout/flow (:instance-id data)]
                       :cause    [:machine-action :checkout/quote.requested]}]]]})}}
```

The machine remains the semantic workflow; the resource runtime handles cached read mechanics. The `[:machine …]` owner is released on actor destroy.

## Deferred slices

The following are named here but their full contract lands with their slice (per [EP-0003 §Acceptance Criteria And Rollout](../docs/EP/EP-0003-resource-queries.md#acceptance-criteria-and-rollout)) and is **out of the read-resource MVP contract**:

- **First public-beta gate (LANDED):** ~~`reg-mutation` / `clear-mutation` / `:rf.mutation/execute` (causal writes that invalidate/patch/refetch resources, keyed by mutation **instance** id)~~ — **mutations have LANDED** (rf2-dwme29): `reg-mutation` / `clear-mutation` register a causal write under the `:mutation` registrar kind; `:rf.mutation/execute` mints a per-submission instance row at `:rf.runtime/mutations` (keyed by instance id, so concurrent submissions don't clobber), creates a `:rf.runtime/work-ledger` record (work-kind `:mutation`), and lowers the write through the SAME managed-HTTP transport (runtime-owned reply addressing; generation + work-id stale suppression as for resources); on success it patches/populates resource entries then invalidates the `:invalidates` tags (explicit `:before-request` / `:after-success` / `:after-failure` / `:after-settle` timing); `:rf.mutation/clear` is the causal instance reset; the `:rf.mutation/*` passive subs project the instance view-model. Write retries are OPT-IN; optimistic rollback is DEFERRED (the success trace reserves its shape). See [§Mutations (first public-beta gate)](#mutations-first-public-beta-gate-rf2-dwme29). ~~focus/reconnect revalidation for active stale resources (`:rf.resource/window-focused`, `:rf.resource/network-reconnected`) expressed as resource events, not subscription-driven fetching~~ — **focus/reconnect revalidation has LANDED** (rf2-vtblcq): the `:rf.resource/window-focused` / `:rf.resource/network-reconnected` events scan the frame's active-owner stale entries and refetch them by policy (cause `:focus` / `:reconnect`, never an owner; generation + stale-suppression respected); the host `window` focus / online listeners are installed per-frame by `install-revalidation-listeners!` and cancelled on frame destroy via the `:resources/on-frame-destroyed!` hook (Spec 016 §Stale and GC scheduling). The first public-beta gate is now complete.
- **Later slices:** GraphQL read/mutation transport (`:rf.graphql/query`, the first transport-extension proof — see [EP-0003 §Deferred: GraphQL](../docs/EP/EP-0003-resource-queries.md#deferred-graphql-later-phase)); optimistic rollback; generic transport extension protocol; polling/interval revalidation; infinite resources; normalized entity caches; automatic graph-derived invalidation; subscription-driven fetching; offline persistence; cross-tab broadcast.

Mutations were the second slice (the first public-beta gate), not the MVP; with mutation invalidation and active-stale revalidation now **landed**, the first public-beta surface — the threshold for "complete-enough resource management" — is complete. What remains (optimistic rollback, polling, GraphQL, the generalized work ledger) is genuinely later-slice work, not a gap in the public-beta contract.

## What Spec 016 does NOT cover

- **GraphQL** — out of scope; deferred phase ([EP-0003 §Deferred: GraphQL](../docs/EP/EP-0003-resource-queries.md#deferred-graphql-later-phase)).
- **A generalized work ledger for all async primitives** — the ledger is named neutrally (`:rf.runtime/work-ledger`) and already carries two in-artefact writers (resource and mutation work), but no writer **outside** the Resources artefact participates yet; a general work-ledger EP is deferred until non-resource consumers (timers, streams, route loaders, spawned actors, machine async work) need it. The multi-writer authority question for those future out-of-artefact writers is explicitly open (see [§Open questions](#open-questions)).
- **Normalized graph caches, fragment stores, entity-identity policy** — Apollo/Relay-style; a separate later artefact, gated on the GraphQL phase and a justifying data model.
- **A `:select` projection key, a `:cache-key` escape hatch, subscription-driven fetching** — projections are ordinary subscriptions; canonical params are the identity; views stay passive.

## Open questions

> Per [SPEC-AUTHORING §SA-4](SPEC-AUTHORING.md), each item is classified `:resolved` / `:host-choice` / `:post-v1 tracked` / `:still-blocking`.

### Work-ledger multi-writer authority — `:still-blocking` for the multi-writer slice (`:post-v1 tracked` for v1)

`:rf.runtime/work-ledger` is designed as a **multi-writer** subsystem. Its two **landed** writers — the resource event handlers (work-kind `:resource`) and the mutation event handlers (work-kind `:mutation`) — both live in the Resources artefact and both mint authority via `:rf/framework-authority? true`, so clause 2 is satisfied for both. When the first writer **outside the Resources artefact** (timers / streams / route loaders / spawned actors / machine async work) joins, **who mints authority for each additional writer is unresolved** and MUST be settled per writer at that point — machines imply authority via `:rf/machine? true`; non-machine writers will each need to stamp `:rf/framework-authority? true` at their own registration sites or write through the privileged helpers. This is a deliberate forward-flag, not a v1 blocker: the contract is complete for both shipped writers. Tracking lands when the general work-ledger EP is opened (deferred until an out-of-artefact consumer needs it).

## Resolved decisions

### Cache scope is fail-closed — `:resolved` (rf2-6rrz53)

There is **no silent default scope**. Every resource declares an explicit scope **policy** at registration (`:rf.scope/global` | resolver | `:rf.scope/from-caller`); no policy is a loud registration error (`:rf.error/resource-missing-scope-policy`); `:rf.scope/global` is an explicit, auditable claim, never a framework default. Event precedence is 3-tier (payload `:scope` → route resolver → spec resolver) with **no `[:rf.scope/global]` fallthrough**. Subscriptions resolve scope from the payload or a sub-resolvable spec policy and raise `:rf.error/resource-sub-unresolved-scope` otherwise — never a silent global read or `:idle`. Load-bearing prose: [§Scope resolution](#scope-resolution). (Supersedes the earlier proposal's `[:rf.scope/global]` tier-4 fallthrough.)

### Resource cache lives in runtime-db, not app-db — `:resolved` ([EP-0001](../docs/EP/EP-0001-frame-partitions.md))

Cache lives only at `:rf.runtime/resources` inside `:rf.db/runtime`; there is no interim app-db location, and a stray `:rf/runtime` app-db root is a hard error. Load-bearing prose: [§Cache home and write authority](#cache-home-and-write-authority).

### Lifecycle is a compact transition fn, not a spawned machine — `:resolved`

The default implementation is a transition function over the cache entry, not a spawned machine per resource entry; semantic retry/workflows graduate to explicit machines. Load-bearing prose: [§Lifecycle is an FSM](#lifecycle-is-an-fsm).

### Owners vs causes are distinct — `:resolved`

Owners are liveness leases (each kind names its release authority); causes are trace metadata that never change liveness/GC/polling. Xray never becomes an owner by observing. Load-bearing prose: [§Active owners and causes](#active-owners-and-causes).

### `:status :error` is reserved for first-load failure — `:resolved`

`:error` means no usable data because the first load failed; a failed background refresh returns to `:loaded`, keeps prior data, and records `:refresh-error`. Load-bearing prose: [§Status semantics](#status-semantics).

### Single built-in transport: managed HTTP — `:resolved`

`:rf.http/managed` (Spec 014) is the only initial-scope transport; the resource lifecycle stays transport-neutral so the deferred GraphQL transport can plug in. Load-bearing prose: [§Transport](#transport).

### Stale suppression keys on `:work/id` — `:resolved`

One identity per work record; the separate `:stale-key` synonym is dropped; the generation allocator is monotonic and host-side and never rewinds across restore. Load-bearing prose: [§Ledger row retention and identity](#ledger-row-retention-and-identity) and [§Restore and replay](#restore-and-replay).

### EP graduation status — `:resolved`

This Spec is the named normative home of the [EP-0003](../docs/EP/EP-0003-resource-queries.md) HTTP-only scope (slice 1). Where the EP and this Spec differ, the Spec governs. The implementation slices (artefact skeleton, work-ledger substrate, runtime, managed-HTTP, invalidation/GC, route, SSR, Xray, focus/reconnect, mutation, docs) and the per-category [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) rows + [Spec-Schemas](Spec-Schemas.md) shapes for the resource surfaces (`:rf.error/resource-*`, the resource entry / work record / scoped-key shapes) land **with their implementation slices** (the same staging the EP applies to conformance fixtures) — the `:rf.error/*` / `:rf.resource/*` / `:rf.scope/*` / `:rf.work/*` prefixes are reserved in [Conventions](Conventions.md) now.

## Cross-references

- [EP-0003 — Resource Queries](../docs/EP/EP-0003-resource-queries.md) — the originating enhancement proposal; full rationale, prior-art benchmark (TanStack Query / RTK Query / SWR / `re-frame-query`), slice plan, and the deferred GraphQL phase.
- [EP-0001 — Frame App/Runtime Partitions](../docs/EP/EP-0001-frame-partitions.md) / [002 §The two-partition frame contract](002-Frames.md#the-two-partition-frame-contract) — the runtime-db partition the cache lives in; full-frame restore.
- [EP-0002 — Explicit Frame Target Resolution](../docs/EP/EP-0002-frame-target-resolution.md) / [002 §Frame target resolution](002-Frames.md#frame-target-resolution--the-carried-invariant) — carried-frame invariant; the canonical `:rf.frame/id` stamp.
- [EP-0004 — Parametric Subscription Inputs](../docs/EP/EP-0004-subscription-inputs.md) / [006 §Subscription input producers](006-ReactiveSubstrate.md#subscription-input-producers--app-db-reader-static-parametric-input-fn) — the resolved input shape resource view-models compose over.
- [Runtime-Subsystems](Runtime-Subsystems.md) — the five-clause contract `:rf.runtime/resources` and `:rf.runtime/work-ledger` graduate against.
- [014-HTTPRequests](014-HTTPRequests.md) — the `:rf.http/managed` transport and the `:rf.http/*` failure taxonomy the `:error` / `:refresh-error` envelopes carry.
- [012-Routing](012-Routing.md) — the route-metadata accepted-key extension for `:resources`; nav-token ownership.
- [011-SSR](011-SSR.md) — the `project-runtime-db` allowlist projection and hydration install path.
- [005-StateMachines](005-StateMachines.md) — machine owners, actor destroy release, and the spawn-id allocator contrast in [§Restore and replay](#restore-and-replay).
- [009-Instrumentation](009-Instrumentation.md) — the trace contract, the `rf/elide-wire-value` walker, and the [§Error event catalogue](009-Instrumentation.md#error-event-catalogue) the resource error categories join with their implementation slice.
- [Conventions](Conventions.md) — reserved `:rf.resource/*` / `:rf.scope/*` / `:rf.work/*` namespaces, `:rf.runtime/resources` + `:rf.runtime/work-ledger` runtime-db keys, and the `:rf/framework-authority?` registration-meta stamp.
- [Pattern-RemoteData](Pattern-RemoteData.md) — the `:loading` / `:fetching` lifecycle slice this Spec refines.
- [Ownership](Ownership.md) — the canonical-home matrix row for the Resources surface.
