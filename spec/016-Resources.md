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

The slice order — read-resource MVP first, mutations and focus/reconnect at the first public-beta gate — is normative in [EP-0003 §Acceptance Criteria And Rollout](../docs/EP/EP-0003-resource-queries.md#acceptance-criteria-and-rollout). **The first public-beta surface is complete:** the read-resource MVP, `reg-mutation` / `:rf.mutation/execute` (see [§Mutations](#mutations-first-public-beta-gate)), focus/reconnect active-stale revalidation (see [§Stale and GC scheduling](#stale-and-gc-scheduling)), optimistic rollback ([§Optimistic mutations](#optimistic-mutations)), and polling ([§Polling](#polling)). GraphQL is a later slice (see [§Deferred slices](#deferred-slices)).

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

**One name per fact** ([EP-0007](../docs/EP/EP-0007-one-name-per-fact.md)): the scoped resource key has exactly **one spelling on data shapes — `:resource/key`** — wherever it appears as a field or payload value (the durable cache-entry / work-record field, the internal-reply verification payload, the `:correlation` map, the error-tag payload, and resource trace rows). The schema is `:rf/scoped-resource-key` ([Spec-Schemas](Spec-Schemas.md#rfscoped-resource-key-rfresource-entry-rfresource-work-record-resources-spec-016)); the storage **tuple** `[scope resource-id canonical-params]` and the map-form authoring **input** are an input-vs-storage distinction (EP-0007 rule 3), not two spellings of one fact. `:resource/key` (the concrete scoped key) is **distinct** from `:resource/id` / `:resource-id` (the **registered resource id**, a bare keyword) — the two are different facts and MUST NOT be conflated. The derivation-algebra **lifecycle category** that means "a scoped resource key owns this entry" is the unqualified kind `:scoped-resource-key` (sibling to `:frame` / `:route` / `:machine-instance`, [Derivations §Lifecycle and owner](Derivations.md#lifecycle-and-owner)) — a category tag, not the key value, so it does not collide with the `:resource/key` data field.

Identity rules (MUST):

- **Cache scope is serializable EDN data** and is the first element of the key. A scope **map** is canonicalized under the **same canonicalization rule as params maps** — key order does not affect identity and nested maps recurse — so two spellings of the same scope hash to one cache key. `[:rf.scope/session {:tenant-id "acme" :user-id "u-42"}]` and `[:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]` are the **same scope**, never two leaking caches.
- **There is no silent default scope.** Every resource declares an explicit scope **policy** at registration (see [§Scope resolution](#scope-resolution)). A resource that wants the global scope says so — `:scope :rf.scope/global` — as an explicit, auditable claim; a resource with no declared policy is a loud registration error, never a silent `[:rf.scope/global]` read.
- **User-, tenant-, locale-, permission-, impersonation-, and session-dependent reads** MUST use an explicit scope, or put those values in params. Logout, account switch, tenant switch, and impersonation change MUST have a causal way to clear or replace the affected scope (see [§clear-scope](#clear-scope-is-causal)).
- **Params conform to `:params-schema`** and are a portable **CEDN-1** identity value ([Conventions §Canonical EDN identity](Conventions.md#canonical-edn-identity)); maps are canonicalized so key order does not affect identity. The CEDN-1 domain — not a resource-private list — decides what is in-bounds: **raw host date objects are rejected (coerce to an EDN `#inst` instant at the boundary — instants ARE in-domain)**; functions, promises, DOM nodes, AbortControllers, and raw JS objects are rejected; and **floating-point values, ratios, NaN, and infinities fail closed** (`:rf.error/non-edn-identity`) exactly as any other out-of-domain value does. `nil` vs missing MUST be schema-defined, not accidental. Every variable that affects remote identity MUST be represented in params. A `:params-schema` may legally admit a `:double` (Malli validates it), so a float-shaped param passes validation and then fails identity closed at the cache-key boundary — **float-shaped params MUST be encoded to a portable form (fixed-point integer, or a string) before they reach identity**, e.g. `{:min-price 999}` (cents) or `{:min-price "9.99"}` rather than `{:min-price 9.99}`. This is Conventions' deliberate fail-closed stance (a host-layer design), not a resource-local rule.
- **Request-correlation ids MUST include the full scoped resource key** (or an equivalent scope-bearing value) so the same params in different user/tenant scopes cannot supersede each other. **They MUST also be frame-qualified** — the transport request-id the runtime hands a process-global transport registry includes the issuing frame id (see [§Transport](#transport) and the [frame-qualified transport request-id](#ledger-row-retention-and-identity) rule), so the same scoped key issued from two frames cannot supersede or abort across the frame boundary.
- **No `:cache-key` escape hatch in v1** unless it is validated, visible in tools, and tested heavily — canonical params are the identity (see [§Deferred slices](#deferred-slices)).

### Canonicalization rule

**Canonicalization and identity are CEDN-1** ([Conventions §Canonical EDN identity](Conventions.md#canonical-edn-identity) / [§Canonical byte encoding](Conventions.md#canonical-byte-encoding-cedn-1)) — this artefact does **not** define a second canonicalizer. The params/scope domain **is** the CEDN-1 domain: a map is normalized so member order is irrelevant to identity and equality; nested maps recurse; sets and vectors keep their value semantics; two spellings collapse to one cache identity. Notably — the two corners a resource author trips over — **floats fail closed** (`:rf.error/non-edn-identity`; encode to fixed-point ints or strings at the boundary) and **host date objects must be coerced to an EDN `#inst` at the boundary** (instants are in-domain facts, host dates are not). The **same** CEDN-1 rule applies to params maps and scope maps. Canonicalization happens once, at the scope/params resolution boundary, before the scoped resource key is computed; the canonical form is what is stored on the entry (keyed by its CEDN-1 byte `key-id`), indexed, traced (post-elision), and serialized.

## Scope resolution

Scope is the cache's tenant / user / permission / locale / impersonation / SSR leak boundary, and a resolved scope can carry PII (user ids, tenant ids, impersonation markers). **A boundary that critical MUST fail closed: it never silently defaults to "shared."** This is the per-resource scope policy ruled fail-closed for EP-0003; it composes with the cache-scope-shape rule — scope is **explicit-in-key** *and* its presence is **mandatory-by-policy**.

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

Subscriptions are **pure** — they cannot run a `(route, ctx)` resolver, because a sub has no access to the routing match or the event context. This is exactly the seam where a silent leak can hide: a route ensures a resource under `[:rf.scope/session {…}]`, but a view's `[:rf.resource/state {…}]` that omits `:scope` would resolve to a *different* scope than the one the data was loaded under and read `:idle` forever — a permanent skeleton with no error anywhere. That is the silent-wrong-target bug family [EP-0002](../docs/EP/EP-0002-frame-target-resolution.md) exists to kill, and resource subscriptions MUST close it the same way.

A subscription resolves its scope from, in order:

1. `:scope` supplied on the **subscription payload**;
2. the resource spec's `:scope` **only if** that policy is one a pure sub can evaluate without an event context — i.e. an explicit `:rf.scope/global` claim, or a resolver **declared as pure data / fn-of-nothing**. A resource whose scope policy is a `(route, ctx)` resolver or `:rf.scope/from-caller` **cannot** be resolved sub-side from the spec alone.

A subscription that **cannot** resolve a scope is a **loud, structured error** (`:rf.error/resource-sub-unresolved-scope`) carrying the resource id and the unresolvable policy — **never** a silent `[:rf.scope/global]` read and **never** a silent `:idle`. The fix the error points at is explicit: pass `:scope` on the subscription payload (the same scope the owning route/event ensured under), or re-declare the resource with a sub-resolvable scope policy. This is the read-side counterpart of the write-side fail-closed gate: a read that cannot name its principal does not fall through to the shared cache.

#### Dev-mode likely-mismatch warning (`:rf.warning/resource-sub-scope-mismatch`)

The unresolvable case is loud; the **resolvable-but-wrong** case is the silent footgun. When a `:rf.scope/from-caller` resource is subscribed with a `:scope` that *does* resolve — but to a **different** concrete scope than the one the owning route/event ensured under — the sub resolves a perfectly valid, but **wrong**, cache key. That key has no entry (or an entry no owner ever attached to), so the sub reads `:idle` **forever** — a silent permanent skeleton with no error anywhere. Fail-closed is still correct (the read never reaches a wrong-principal entry); the ergonomics of getting the two scopes to match are the roughest edge in the session-scoped pattern (the EP-0003 dogfooding finding).

The framework emits a **dev-only** warning at the moment of the mismatched read — the read/sub-side complement of the Xray write-side scope-mismatch lint (see [§Xray and AI tooling](#xray-and-ai-tooling)). The heuristic: a `:rf.scope/from-caller` resource is subscribed at a scope key with **zero active owners**, while a **different** scope key for the **same** resource id **is** active (has at least one active owner). The trace op is `:rf.warning/resource-sub-scope-mismatch`, carrying `:resource-id`, the resolved `:sub-scope`, and the `:active-scope` the read likely meant — its `:hint` names the fix (pass the same `:scope` the owning route/event ensured under).

The warning is scoped narrowly:

- **Only `:rf.scope/from-caller` resources are checked.** Every other policy (`:rf.scope/global`, a pure-data / fn-of-nothing resolver) resolves the **same** concrete scope sub-side and ensure-side, so a sub can never land on a different key than the ensure did — there is nothing to warn about.
- **A genuinely empty cache does not warn.** If no *other* scope for the resource is active, the read is simply an un-ensured resource, which the documented `:idle` empty-state projection already explains — not a likely mismatch.
- **Dev-only and production-elided.** The whole heuristic — the gate, the registry lookup, the cache scan, and the emit — sits behind `re-frame.interop/debug-enabled?` (an alias of `goog.DEBUG`) and rides `re-frame.trace/emit!` (gated by the same flag), so Closure DCE strips it entirely under `:advanced` + `goog.DEBUG=false`, exactly like every other framework dev warning. It is **one-shot idempotent** per distinct `[resource-id sub-scope active-scope]`, so a reactively re-running subscription warns **once** per genuine mismatch rather than on every render.

### Xray scope diagnostics are defense-in-depth, not the boundary

Because every resource carries an explicit policy, there is **no `/me` / `/current-user` URL heuristic** boundary — URL-pattern matching is only defense-in-depth. Xray SHOULD warn about *suspicious explicit-global* resources (an `:rf.scope/global` claim whose request looks session-dependent — `/me`, `/current-user`, tenant-local URLs, or auth-derived params), not "compensate for a missing scope" (a missing scope is a loud error, not a heuristic). The **standing audit surface** is structural: sub-topology / Xray **enumerate every `:rf.scope/global` resource** as the security-review list — the explicit replacement for the heuristic (see [§Xray and AI tooling](#xray-and-ai-tooling)).

### `clear-scope` is causal

`clear-scope` is a causal operation (the `:rf.resource/clear-scope` event). It MUST:

- remove or mark unusable every entry in that scope;
- release owners in that scope;
- abort in-flight requests that have no remaining owner outside that scope;
- suppress late replies by scope + generation checks;
- emit trace rows explaining which entries were removed, aborted, or left alone.

Auth-token refresh does **not** necessarily require clearing scope if the user, tenant, permissions, and impersonation state are unchanged. Login, logout, account switch, tenant switch, permission-set change, locale switch that affects wire data, and impersonation enter/exit **do** require either a new scope or an explicit clear/replace operation.

Invalidation is **scoped by default**. A cross-scope invalidation MUST opt in explicitly and be visible in Xray because it can refetch or stale data for multiple users, tenants, story frames, or SSR requests.

#### `clear-scope` resolves the concrete scope from the coeffect db (not a snapshot)

A common boundary — logout, account switch, tenant switch — wants to clear the scope the user was *in* after current db has already removed the user. The canonical idiom is **resolve the concrete scope from the handler's coeffect db** (pre-transition by definition — the cofx db is the causal input, the EP-0010-coherent answer) and pass it to `clear-scope` **concretely**:

```clojure
(rf/reg-event :auth/logout
  (fn [{:keys [db]} _]
    (let [old-scope (rf/resolve-resource-scope db :realworld/session)]   ;; resolver helper, resolved against cofx db
      {:db (dissoc db :auth)
       :fx [[:dispatch [:rf.resource/clear-scope
                        {:scope old-scope
                         :cause :logout}]]]})))
```

The `resolve-resource-scope` helper used here is a **pure** data helper over the resolver registry — not an effect, no app-state / dispatch side effects, and no `:rf.resource/scope-resolved` trace (the full rationale is in [§Registration](#registration)). There is **no `:snapshot-db` payload key**: a whole-db snapshot riding an event vector is an egress-bearing record on traces and epoch history — unacceptable under [EP-0015](../docs/EP/EP-0015-frame-owned-egress-policy.md) (EP-0016 issue 7). A `{:from-db …}` reference *may* still appear on a `clear-scope` payload; the single use-time resolution rule applies, and a reference that resolves **nil** at a clear-scope site emits a **loud diagnostic** (`:rf.warning/resource-clear-scope-unresolved`), **never** a silent no-op.

## Named resource-scope resolvers (`reg-resource-scope`)

One scope-resolution currency beats local seams: the same named resolver should work wherever current viewer identity determines resource identity — resource registration, route resources, event-side ensure, subscriptions, invalidation descriptors, populate/patch/remove targets, and `clear-scope`. [EP-0016](../docs/EP/EP-0016-resource-mutation-completion.md) Decision 3 adds a registry of **named resource-scope resolvers**.

```clojure
(rf/reg-resource-scope :realworld/session
  {:inputs {:username [:db [:auth :user :username]]}
   :resolve
   (fn [{:keys [username]} _ctx]
     (when username
       [:rf.scope/session {:username username}]))})

(rf/clear-resource-scope :realworld/session)   ;; registration-lifecycle removal
```

A resolver is **pure**. It derives a resource scope; it does **not** fetch, dispatch, mutate state, read ambient host state, or perform transport work. The runtime evaluates declared inputs and calls `:resolve` with the resolved input map.

### The `{:inputs … :resolve …}` grammar

The primary form **declares its inputs**: names on the left, source descriptors on the right — the same shape as other derivation input declarations. This lets tools explain which app facts decide a resource identity, and lets the runtime re-resolve scope only when a relevant input changes.

- **`:inputs`** — a map `{name source-descriptor}`. The shipped source descriptor is **`[:db <rf-path>]`**, where `<rf-path>` is an [EP-0012](../docs/EP/EP-0012-path-optics-and-canonical-forms.md) concrete `:rf/path` (see [Conventions §The `:rf/path` algebra](Conventions.md#the-rfpath-algebra)). The whole `{:inputs … :resolve …}` declaration shape is pinned **forward-compatible** by EP-0012's reserved named-declaration shape (disposition 3).
- **`:resolve`** — `(fn [inputs ctx] …)` returning a **canonical scope value** (e.g. `[:rf.scope/session {…}]`), `:rf.scope/global`, or **`nil`**. `nil` is **fail-closed**: at a scope-requiring site it is the unresolved condition, never permission to read global. The resolved scope is routed through the shared scope-canonicalization path, so a misspelled `:rf.scope/*` keyword or an opaque host value is rejected loudly.

> **The `ctx` argument is reserved, currently `nil`.** `:resolve` is invoked `(resolve-fn inputs nil)` — `ctx` is **literal nil** in this slice, reserved for a future declared resolver context and **not to be relied on**. This is the same reservation discipline the resource `:request` fn carries (see [§The `ctx` argument is reserved across resource/mutation fn surfaces](#the-ctx-argument-is-reserved-across-resourcemutation-fn-surfaces) below); a resolver MUST derive scope from its **declared `:inputs`**, not from `ctx`.

#### Whole-db function sugar (explicit-cost)

An implementation MAY support a function sugar:

```clojure
(rf/reg-resource-scope :realworld/session
  (fn [db _ctx]
    (when-let [username (get-in db [:auth :user :username])]
      [:rf.scope/session {:username username}])))
```

The sugar lowers to an **explicit whole-db dependency**. The declared-inputs form is the recommended path (it marks the whole-db cost on the narrow-re-resolution axis); the sugar is a marked convenience, not a peer.

##### No derived-sensitivity propagation

A named scope resolver **does not propagate** its `:db` inputs' classification to its derived scope. Sensitivity does not propagate (subs / flows / the resource scope-resolver arm) — classification does not flow input → output (see [EP-0025](../docs/EP/EP-0025-data-classification.md), [Spec 015 §No propagation, no taint](015-Data-Classification.md#no-propagation-no-taint)). A resolver does not carry a `:rf.egress/output-sensitivity` declassification claim: **the key is absent and silently ignored if present** (not validated fail-closed). A resource's egress classification rides its **own** projection-relative `:sensitive` / `:large` declarations (lowered per instance into the per-frame elision registry — [§Resource registration spec](#resource-registration-spec)), never sensitivity inherited from the paths its scope resolver read. If a derived scope is itself sensitive, the resource declares the scope sensitive directly (e.g. `:sensitive [[:scope …]]` / the coarse `:sensitive?` claim). The off-box trace egress of a resolved scope's identity-bearing values is **unconditionally fail-closed** (the resolver derives a tenant / user identity that must not ride raw) — that is conservative redaction of resolver-owned values copied into trace tags, not a propagation engine.

<a id="resolver-references--from-db-id"></a>

### Resolver references — `{:from-db <id>}`

A named resolver is referenced by `{:from-db <resolver-id>}`. The reference may appear wherever this artefact allows **derived** resource scope:

- resource registration `:scope`;
- route resource entries (`:resources … :scope`, [§Route integration](#route-integration) / [012 §Per-route data loading](012-Routing.md#per-route-data-loading));
- event-side ensure/refetch payloads;
- resource subscriptions;
- invalidation descriptors ([§Scoped invalidation descriptors](#scoped-invalidation-descriptors-per-target));
- map-form populate/patch/remove targets ([§Map-form exact resource targets](#map-form-exact-resource-targets));
- `clear-scope` helpers for logout / account switch / tenant switch.

A `{:from-db …}` reference is resolved **at use time** against the frame db — the single use-time resolution rule, uniform across every site. **Nil at a scope-requiring site is fail-closed**: route planning MUST NOT substitute global; a subscription is explainable as "scope unresolved" rather than quietly reading a different cache entry; a `clear-scope` reference that resolves nil emits the loud diagnostic above. The db a reference resolves against is the **causal db of its site**: a route entry resolves against the navigation handler's app-db coeffect (before planning the resource work); an event-side ensure/refetch resolves against its handler's app-db coeffect; a subscription resolves against the frame's app-db **read from the same coherent frame-state snapshot it reads the cache entry from** (see below).

<a id="from-db-subscription-re-keying"></a>

#### A `{:from-db …}` subscription re-keys when the resolver's inputs change

A live resource subscription whose scope is a `{:from-db <id>}` reference (or whose resource declares a `{:from-db …}` spec `:scope`) **re-resolves its scoped key reactively** when the resolver's declared app-db inputs change mid-session — the account-switch / impersonation / login / logout boundaries the named-resolver mechanism exists to serve (EP-0016). The contract:

- **Re-pointing is reactive and automatic.** The subscription observes the **whole frame-state** (both the app-db partition the resolver reads its `:inputs` from and the runtime-db partition the cache lives in). When a relevant app-db input changes, the resolver yields a **different** scoped key, and the subscription re-points to **that** key's cache entry on the next reactive pass. The view does not re-subscribe; the same subscription tracks the new principal. (Implementation note: this is why the resource subs are a **frame-state** subscription, not a runtime-db-only one — a runtime-db-only sub would be inert to an app-db-only commit and would keep reading the old principal's entry.)
- **The transition state the view observes is the NEW key's state.** During the transition the subscription reads the **new** scoped key — typically `:idle` (no entry yet) or `:loading` (a route/event is ensuring it under the new scope), **never** the old principal's `:data`. A scoped read never shows one principal's data to another; the fail-closed leak boundary holds across the re-key, not only at first resolution. If the new key resolves **nil** (the resolver's inputs are absent — e.g. logged-out), the subscription is the loud **"scope unresolved"** condition (`:rf.error/resource-sub-unresolved-scope`), the same fail-closed diagnostic an initially-unresolvable `{:from-db …}` sub raises — never a silent fall-through to the old entry or to global.
- **Owner-lease handoff is the existing causal machinery, not a sub concern.** Re-pointing the *read* does not move any *owner lease* — subscriptions are passive reads ([§Subscriptions (passive)](#subscriptions-passive)). Attaching an owner under the new scope is the job of whatever **causes** the new-scope load: a route entry attaches `[:route …]` under the resolved new scope and route leave releases the old; an event-side ensure attaches its lease; `clear-scope` (the logout/account-switch idiom) releases the old scope's entries and owners. The old principal's entry becomes GC-eligible when its last owner is released, exactly as for any unowned entry — the re-key does not strand it.

> **Conformance.** This reactive re-keying is the consequence of registering the resource subs as **frame-state** subscriptions (`reg-frame-state-sub`), whose single signal source is the whole frame-state — so a sub re-runs on a commit to *either* the app-db partition the resolver reads or the runtime-db partition the cache lives in, and re-resolves its scoped key (`resolve-scoped-key`, raising `:rf.error/resource-sub-unresolved-scope` on a nil resolution) on each pass. The reference implementation is `re-frame.resources.subs`; the mid-session account-switch contract — the live sub re-points to the new scope, the view observes the new key's `:idle`/`:loading` state and never the old principal's `:data` — is pinned by `sub-re-keys-on-mid-session-account-switch` in `implementation/resources/test/re_frame/resources_from_db_scope_cljs_test.cljc`.

### Route-derived scope is reserved (`[:runtime path]`, not shipped)

Some applications derive viewer identity from the **route** (tenant in a path segment). This slice's primary mechanism is **db-derived** scope because it closes the session/feed gap and composes across event and subscription sites. The route-derived case is **reserved, not shipped**: the route match is **already mirrored into the fold** — `[:rf.runtime/routing :current]` is durable runtime-db — so the reserved resolver input source is **`[:runtime <path>]`** (already in [EP-0014](../docs/EP/EP-0014-derivation-and-process-algebra.md)'s input vocabulary). No new mirroring, no anonymous route-context functions as a second public scope-resolution currency. The selection rule for when the source ships: viewer identity that is **app state** → `[:db …]`; a pure **route fact** → the reserved `[:runtime …]`. The un-defer consumer is the tenant-switcher testbed.

### Example — session feed as a route resource

```clojure
(rf/reg-resource :realworld/feed
  {:scope {:from-db :realworld/session}
   :params (fn [{:keys [page]}] {:page page})
   :tags (fn [_params _value] #{[:feed] [:article-list]})}
  ;; the :request handler is the THIRD slot (rf2-wvh95f F1)
  (fn [{:keys [page]} _ctx]
    {:request {:method :get :url "/articles/feed"
               :params {:limit 20 :offset (* 20 (dec page))}}
     :decode :json}))

(rf/reg-route :realworld/home
  {:resources
   [{:resource  :realworld/feed
     :params    {:page 1}
     :scope     {:from-db :realworld/session}
     :blocking? true}]}
  "/")
```

Route ownership, route-leave release, subscriptions, invalidation descriptors, and logout `clear-scope` now all use the same named resolver.

## Active owners and causes

TanStack Query and RTK Query talk about active observers or subscriptions. re-frame2 talks about **active owners** (liveness leases) and **causes** (trace/diagnostic metadata). The two are never blurred.

### Owners are liveness leases

Owners answer: should invalidation refetch now or only mark stale? Should polling continue? May the entry be garbage-collected? What should route-leave release? Which workflows intentionally keep this resource active?

```clojure
[:route   :route/article  nav-token]
[:machine actor-id]        ;; actor-id = the runtime instance id (singleton machine-id / spawned <type>#<n>)
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
| **Machine** | `[:machine actor-id]` | **Actor destroy** — when the owning machine instance is stopped/destroyed ([005-StateMachines §What auto-cancels on destroy](005-StateMachines.md#what-auto-cancels-on-destroy--exactly-three-framework-managed-resource-kinds-divergence-from-xstate)), the machine teardown dispatches `:rf.resource/release-owner` for this owner — so a machine that `ensure`d a resource under its `[:machine actor-id]` owner does NOT leak the lease (a leaked lease pins the entry alive and keeps it refetching/polling on focus/reconnect — the slow leak this release closes). The `actor-id` is the runtime-derivable instance identity the machine runtime owns: the registered machine-id for a singleton, the `<type>#<n>` for a spawned actor — the same id the [Derivations §Lifecycle](Derivations.md) algebra names as the `:machine-instance` owner (`[:machine :upload/main]`). Apps mint machine-owned leases under exactly this key so the destroy releases them. (A finer-grained app-minted variant `[:machine machine-id instance-id]` that encodes a domain `instance-id` distinct from the runtime actor-id is app-authoritative — its release path is the app's, like any `[:lease …]`; the framework auto-releases the runtime-owned `[:machine actor-id]` key only.) Machine liveness is a pure function of frame-state, so a destroyed instance can hold no live lease. |
| **SSR** | `[:ssr request-id nav-token]` | **Request teardown** — an SSR owner belongs to one server render and is released when that request's frame is torn down; it never survives as a live client-side lease (it is reconciled to an orphan on hydration/restore — see [§Restore and replay](#restore-and-replay)). |
| **App / lease** | `[:lease …]`, `[:dashboard/opened …]`, and other app-minted kinds | **The app is authoritative** — an event that mints such a lease MUST have a matching `:rf.resource/release-owner` path. The framework does not auto-release app-minted leases. Xray surfaces an **orphaned-owner lint** for an app-kind owner whose minting event has no observed release path (or that pins an entry past its expected lifetime). |

<a id="the-scoped-cache-lease-lifecycle"></a>

### The scoped-cache lease lifecycle (acquire → hold → release → GC)

An owner is a **lease on one scoped cache entry**, never on a resource id in the abstract. The lease lifecycle and its composition with scope resolution are the structural mechanism behind the leak boundary (see the guide's "Scope — the leak boundary other libraries do not have" positioning): because a lease names a fully *resolved* scoped key, holding a lease on one principal's entry can neither pin nor release another principal's entry. The lifecycle is a precise four-phase sequence (MUST):

1. **Acquire — under the resolved scoped key.** An `ensure`/`refetch` (or a route entry, machine, or SSR render) that carries an `:owner` attaches it to the target entry's `:active-owners` set and records it in the owner-index (`owner → #{scoped-key …}`). When the `:scope` is a `{:from-db <id>}` reference or a named resolver, the **scoped key is resolved first** ([§Scope resolution](#scope-resolution), [§Resolver references — `{:from-db <id>}`](#resolver-references--from-db-id)) and the lease is attached to **that** resolved key — never to the literal reference, never to a global fallback. Acquiring a lease on `[:rf.scope/session {…tenant-A…}]` and acquiring one on `[:rf.scope/session {…tenant-B…}]` are two independent leases on two independent entries; neither can be reached through the other's scoped key. A fresh-skip cache hit also attaches the lease (`:rf.resource/owner-attached`, `joined-in-flight? false`); joining an in-flight request attaches it and joins the existing work record.
2. **Hold — the lease pins the entry alive.** While an entry has any active owner it is **not** GC-eligible: a fired GC re-check observes the owner and skips collection (re-arming, per [§Stale and GC scheduling](#stale-and-gc-scheduling)). A held lease also keeps the entry eligible for active-owner stale revalidation on focus/reconnect and for refetch-on-invalidate. A lease pins **only its own scoped key** — an admin impersonating tenant A holds a lease on tenant A's entry, while tenant B's separately-leased entry stays cached under its own scoped key, untouched by anything happening to A.
3. **Release — `:rf.resource/release-owner` drops the lease.** Releasing drops the owner from **every** entry it owned (`:active-owners disj`) and removes it from the owner-index, emitting `:rf.resource/owner-released`. Route-leave / nav-token supersession release route owners by token; actor destroy releases machine owners; request teardown releases SSR owners; app-minted leases release through their matching `:rf.resource/release-owner` path; `clear-scope` (the logout / account-switch / tenant-switch idiom) releases a resolved scope's entries and owners together. A release is **scoped by construction**: releasing the lease on scope A's key removes the owner only from scope A's entry, leaving scope B's separately-leased entry — and its lease — intact.
4. **GC on last release — the entry becomes collectable, then is collected.** Dropping the **last** lease leaves the entry **owner-free**. An owner-free, work-free (no `:current-work`) entry is **GC-eligible**, and an inactive-GC timer re-check (`:rf.resource.internal/gc-fired`) removes it deterministically (`:rf.resource/gc-fired`), recomputing the reverse indexes. The release itself does not synchronously remove the entry — the entry transitions to GC-eligible and is collected when the GC re-check runs (a release that crosses an already-passed `:gc-after-ms` deadline is covered by the GC-skip reschedule, [§Stale and GC scheduling](#stale-and-gc-scheduling)). An entry that arms no `:gc-after-ms` policy simply lingers owner-free until something re-leases or removes it; `:rf.resource/remove` and `clear-scope` remove it eagerly regardless of GC policy. GC of scope A's entry on its last-lease drop never touches scope B's still-leased entry.

This is the same lifecycle the `{:from-db …}` re-key clause leans on ([§A `{:from-db …}` subscription re-keys](#from-db-subscription-re-keying)): re-pointing a subscription's *read* moves no lease; the old principal's entry becomes GC-eligible only when *its* causal owner is released, exactly as for any unowned entry. **Conformance.** The scoped-cache lease lifecycle — a held lease on scope A keeps A's entry while scope B's last-lease release GCs B's entry and only B's; the lease attaches/releases under the `{:from-db …}`-resolved scoped key — is pinned by `implementation/resources/test/re_frame/resources_scoped_lease_lifecycle_cljs_test.cljc`. The single-scope acquire/hold/release/GC mechanics (GC re-check, owned/in-flight skip, skip-reschedule) are pinned by `resources_invalidation_gc_cljs_test.cljc`; the owner-lease handoff across a mid-session re-key by `resources_from_db_scope_cljs_test.cljc`.

> **Kinship with subscription disposal (and the timing asymmetry).** Resource `:active-owners` GC and subscription ref-count disposal ([006-ReactiveSubstrate §Reference counting and disposal](006-ReactiveSubstrate.md#reference-counting-and-disposal)) are the *same* refcount-on-a-shared-cache-entry liveness lifecycle: a set/count of live holders pins the entry, and the entry becomes collectable when the last holder releases. They differ on **zero-disposal timing**. A subscription disposes **synchronously, in-tick** the instant ref-count hits 0 (no warm window — v2 carries no deferred-grace-period timer for subs). A resource keeps the owner-free entry **warm** until its advisory `:gc-after-ms` deadline (and an arm-less entry lingers owner-free indefinitely). A subscription is a cheap in-process reaction whose recompute is local; a resource entry wraps a *remote* read whose re-acquisition costs a network round-trip, so the warm window lets a quick re-lease (a route bounce, a remount) reuse the cached data instead of refetching. Do **not** unify the two mechanisms — the timing difference is load-bearing.

### Causes explain why work happened

Causes are trace and diagnostic metadata. They answer "why did this happen?" without changing liveness, GC, polling, or refetch decisions:

```clojure
[:route-entry :route/article nav-token]
[:manual :article/refresh]
[:invalidate {:tags #{[:article "welcome"]}}]
:focus
:reconnect
:poll
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
  superseded reply               -> previous stable state  (suppressed; the entry's last
                                                            stable status from before the
                                                            superseded attempt — :loaded if
                                                            it has :data, else :idle/:error —
                                                            never written by the stale reply)

:error
  refetch                        -> :loading
```

**Totality.** The transition function is total over the **closed signal set** `:start-load` (the `ensure`/`refetch` start) / `:success` / `:failure`, with `has-data?` selecting the `:loading` vs `:fetching` start. A signal with no row for the current status is a **no-op** (the status is unchanged) — e.g. an `invalidate` of an `:idle` entry, or a fresh-skip `ensure` of a `:loaded` entry ([§Xray and AI tooling](#xray-and-ai-tooling) `:rf.resource/cache-hit`), leaves the status where it is rather than forcing an edge. Invalidation and freshness mutate the **timestamp facts** (`:invalidated-at` / `:stale-at`), which are orthogonal to load status ([§Status semantics](#status-semantics)), so they need no FSM edge of their own. There is **no `:fetching -> :error` edge**: a background-refresh failure returns to `:loaded` and records `:refresh-error` (the last-known-good `:data` is preserved); `:error` is reserved for a first load that never produced usable data.

The resource FSM describes **cache-entry status**. The **work ledger** describes the **attempt lifecycle** that may currently be moving that cache entry: queued, running, abort-requested, completed, failed, timed out, suppressed, or cancelled (see [§Frame work ledger](#frame-work-ledger)). Resource `:status` MUST NOT be overloaded with host-handle state.

Transport retry belongs to the transport adapter — managed HTTP in the initial scope. **Semantic retry belongs to machines.**

## Status semantics

Resource state uses [Pattern-RemoteData](Pattern-RemoteData.md) semantics, but durable entries store **facts, not derived booleans**:

```clojure
{:resource/id    <registered-resource-id>
 :resource/key   <scoped-resource-key-or-nil>
 :status         :idle | :loading | :fetching | :loaded | :error
 :data           <last-known-good-or-nil>
 :error          <first-load-error-or-nil>
 :refresh-error  <background-refresh-error-or-nil>
 :loaded-at      <ms-or-nil>
 :stale-at       <ms-or-nil>
 :invalidated-at <ms-or-nil>
 :attempt        <int>
 :generation     <int>
 :revision        <int>
 :request-id     <request-id-or-nil>
 :current-work   <work-id-or-nil>
 :tags           <set>
 :active-owners  <set>}
```

`:resource/id` is the registered resource id (a bare keyword); `:resource/key` is the entry's own scoped resource key (the `[scope resource-id params]` tuple, [§Resource identity](#resource-identity)); `:current-work` points at the in-flight attempt's `:work/id` (the join to the [work ledger](#frame-work-ledger), cleared when no attempt is live — [§Restore and replay](#restore-and-replay)). The `:keep-previous?` projection pointer `:previous-key` rides the entry too ([§Paginated and previous data](#paginated-and-previous-data)). The durable entry stores **facts**; `:loading?` / `:fetching?` / `:stale?` / `:has-data?` are derived, never stored.

`:revision` (base `0`) is the per-entry **write identity** — a monotone counter bumped on **every authoritative durable entry write** (load success, controlled patch, populate, an invalidation-driven freshness settle, **and a failure / accepted-cancellation settle** — a first-load / background-refresh failure, a load-more page failure, or an accepted abort all clear `:current-work` and record terminal facts, so they move the write identity exactly as a success does), **unconditionally** and never gated on whether `:data` changed. The failure / cancel settle is load-bearing for rollback: a snapshot taken while the attempt was **in-flight** (`:fetching` + `:current-work` set) is a stale "before" once the attempt settles, and a settle that did **not** move `:revision` would let a later rollback's conflict check miss the move and **resurrect** the cleared in-flight state over the terminal settled state. It is **distinct** from `:generation`: `:generation` is the work / stale-suppression identity bumped at load **start**, whereas `:revision` moves only when an authoritative write actually **settles** the entry. The distinction is load-bearing for [EP-0019](../docs/EP/EP-0019-optimistic-mutation-rollback.md) optimistic-mutation rollback, whose settle-time conflict check compares a recorded `:revision` (observed at optimistic-apply time) against the entry's current `:revision` to decide whether a recorded inverse is still a truthful "before" — a value-gated token would miss a freshness-only settle (`:loaded-at` / `:stale-at` re-stamped while `:data` is `=`-shared, [§Structural sharing](#structural-sharing)) and let a later rollback silently clobber newer freshness with a stale snapshot.

This **refines** Pattern-RemoteData's broad `:error` state. The load-bearing invariants (MUST):

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

- **Event-handler authority.** Every resource `reg-event` registration site stamps the reserved registration-meta key `:rf/framework-authority? true` (per [Conventions §Reserved registration metadata](Conventions.md#reserved-registration-metadata-framework-owned)). The runtime reads the stamp when assembling the event context, so a returned `:rf.db/runtime` effect from a resource handler is in-bounds. The handlers that carry it: `:rf.resource/ensure`, `:rf.resource/refetch`, `:rf.resource/invalidate-tags`, `:rf.resource/release-owner`, `:rf.resource/clear-scope`, `:rf.resource/remove`, and the internal replies `:rf.resource.internal/succeeded` / `:rf.resource.internal/failed` / `:rf.resource.internal/aborted` / `:rf.resource.internal/stale-fired` / `:rf.resource.internal/gc-fired` / `:rf.resource.internal/stale-suppressed`. Without this stamp, resources would be the *second* framework subsystem after routing to trip the `:rf.warning/app-handler-runtime-effect` ownership diagnostic on every fetch in dev — exactly the gap the generalized authority mechanism and the runtime-subsystem contract's clause 2 exist to close.
- **Privileged-helper authority.** Stale/GC and host-handle bookkeeping that the resource runtime performs **outside** the event-handler path (scheduling timers, clearing host handles) goes through the privileged frame-state mutators (`swap-runtime-db!` / `replace-frame-state!`), bypassing the event-handler diagnostic — exactly as elision and SSR's non-event writes do.

`:rf/framework-authority?` is a **diagnostic-governing convention, not a capability gate** ([002](002-Frames.md) Mike ruling #4): the effect applies either way, and the flag governs only whether the ownership diagnostic fires. Resource handlers **never** write runtime-db through ordinary app authority.

### Runtime-subsystem graduation

`:rf.runtime/resources` is the runtime-subsystem contract's **first graduating instance and proof-case** outside the four shipped subsystems (machines / routing / elision / SSR). Each new runtime-db child MUST graduate against the five-clause contract defined normatively in [Runtime-Subsystems](Runtime-Subsystems.md).

This section is the **canonical home for the resource-trio grading rows** — `:rf.runtime/resources`, `:rf.runtime/work-ledger`, and (with the mutation slice) `:rf.runtime/mutations`. [Runtime-Subsystems §Grading table](Runtime-Subsystems.md#grading-table--the-shipped-subsystems) mirrors these three rows into its catalogue of all shipped subsystems; where the two differ, this section governs the resource-trio content.

#### `:rf.runtime/resources` — resource cache

| Clause | Grade |
|---|---|
| **1 Subtree** | ✅ `:rf.runtime/resources` with the closed slot set `:entries` / `:tag-index` / `:owner-index` ([Conventions §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys)). Allocated lazily — absent until the first resource write — and per-frame isolated. |
| **2 Write authority** | ✅ Event-handler path — every resource `reg-event` stamps `:rf/framework-authority? true`; the internal reply handlers carry it too. Stale/GC side-table writes go through privileged frame-state helpers. See [§Write authority](#write-authority). |
| **3 Read API** | ✅ The `:rf.resource/*` sub family (`:rf.resource/state`, `:rf.resource/data`, `:rf.resource/status`, `:rf.resource/loading?`, `:rf.resource/fetching?`, `:rf.resource/stale?`, `:rf.resource/error`, `:rf.resource/refresh-error`, `:rf.resource/has-data?`, `:rf.resource/previous-data`) plus tool accessors (`resource-meta`, `resource-state`, `resources`, the `list-resource-instances` / `get-resource-state` family). App code never reads raw `[:rf.runtime/resources …]` paths. |
| **4 Projection / elision** | ✅ Allowlist-shaped — only the durable resource projection rides the `:rf/hydration-payload` `:rf/runtime-db` slice via the explicit projection hook ([§SSR and hydration](#ssr-and-hydration)); `:tag-index` / `:owner-index` are recomputable-from-`:entries` and need not ride the wire. Params, scopes, and data carry `:sensitive?` / `:large?` classification owned by the resource definition and projected through the merged frame-owned `rf/project-egress` over the shared `rf/elide-wire-value` walker ([EP-0015 §6](../docs/EP/EP-0015-frame-owned-egress-policy.md), [015-Data-Classification §Resource and mutation durable classification](015-Data-Classification.md#resource-and-mutation-durable-classification)); Xray sees redacted summaries, not raw values. |
| **5 Teardown** | ✅ Side tables are keyed by frame id and work id; frame destroy cancels all resource timers and clears host handles for that frame ([§Stale and GC scheduling](#stale-and-gc-scheduling), [§Restore and replay](#restore-and-replay)). **Durable kept:** `:entries` (cache facts ride restore/SSR). **Transient dropped:** AbortControllers, stale/GC timers, transport promises (never serialized); `:tag-index` / `:owner-index` are recomputed from `:entries` on install. |

#### `:rf.runtime/work-ledger` — frame work ledger

| Clause | Grade |
|---|---|
| **1 Subtree** | ✅ `:rf.runtime/work-ledger` with serializable work records keyed by `:work/id` ([Conventions §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys)). Allocated lazily; per-frame isolated. Named **neutrally** — resources are its first writer, but later slices extend it to timers, streams, route loaders, spawned actors, and machine async work. |
| **2 Write authority** | ✅ *for the resource writer* — in the initial scope the ledger is written only through the resource event handlers (which stamp `:rf/framework-authority? true`). ⚠️ **OPEN multi-writer question.** The ledger is a **multi-writer** subsystem: when timers, streams, route loaders, spawned actors, and machine async work join as writers in later slices, who mints authority for each additional writer is an open clause to resolve **per writer**. Machines already imply authority via `:rf/machine? true`; non-machine future writers will each need to stamp `:rf/framework-authority? true` or write through the privileged helpers. This spec names the ledger neutrally and flags the multi-writer authority question as **unresolved, to be settled when the first non-resource writer lands** (see [§Open questions](#open-questions)). |
| **3 Read API** | ✅ Read by framework code and tools only — Xray's live work-ledger table per frame, SSR's blocking-drain wait point, and the resource runtime's join/dedupe logic. **No app-facing read sub** — app code observes work indirectly through `:rf.resource/*` subs (`:rf.resource/fetching?` etc.), never the ledger directly. |
| **4 Projection / elision** | ✅ Allowlist-shaped — only **non-terminal rows' summaries** ride the hydration/epoch wire; terminal rows are pruned to a bounded local Xray tail and are not durable wire payload ([§Ledger row retention and identity](#ledger-row-retention-and-identity)). Causes, owners, and deadlines carry the same privacy/size elision as resource metadata through `rf/elide-wire-value`. |
| **5 Teardown** | ✅ Host handles (AbortControllers, timeout/poll handles, promises) live in side tables keyed by `[frame-id work-id]`, cleared on frame destroy. **Durable kept:** the bounded set of non-terminal serializable records. **Transient dropped:** host handles; restored non-terminal rows are immediately reconciled to **dangling** (their `:work/id` can never re-match a live entry — the generation allocator is monotonic and host-side, [§Restore and replay](#restore-and-replay)). |

#### `:rf.runtime/mutations` — mutation-instance runtime

The causal-write counterpart of `:rf.runtime/resources`, shipped with the first public-beta gate (see [§Mutations](#mutations-first-public-beta-gate)). It owns its own subtree (instance rows keyed by mutation **instance** id) but its in-flight attempt **rides the neutral `:rf.runtime/work-ledger`** (work-kind `:mutation`) rather than minting a fourth subtree — so clause 1 is the instance map and clause 2 reuses the work-ledger transport. Present only when the app registers a mutation.

| Clause | Grade |
|---|---|
| **1 Subtree** | ✅ `:rf.runtime/mutations` with serializable mutation **instance** rows keyed by instance id (`:mutation/id` / `:instance/id` / `:status` / `:result` / `:error` / `:scope` / `:params` / `:generation` / `:current-work` / `:started-at` / `:settled-at` / `:affected-keys` / `:patch-summary`; schema `MutationInstance` in [Spec-Schemas](Spec-Schemas.md#rfscoped-resource-key-rfresource-entry-rfresource-work-record-resources-spec-016)). Keyed by instance id (NOT mutation id) so concurrent submissions never clobber each other. Allocated lazily — absent in an app that registers no mutations ([Conventions §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys)). |
| **2 Write authority** | ✅ Event-handler path — `:rf.mutation/execute` / `:rf.mutation/clear` (and the internal reply handlers) stamp `:rf/framework-authority? true`; the in-flight write lowers through the **same managed-HTTP transport as resources**, joining a `:rf.runtime/work-ledger` record (work-kind `:mutation`) rather than a private side-table. Generation + work-id stale suppression is the correctness boundary as for resources (per [§Mutations](#mutations-first-public-beta-gate)). |
| **3 Read API** | ✅ The passive `:rf.mutation/*` sub family — `:rf.mutation/state`, `:rf.mutation/status`, `:rf.mutation/pending?`, `:rf.mutation/result`, `:rf.mutation/error` — keyed by instance id, projecting the instance view-model. App code reads these, never raw `[:rf.runtime/mutations …]` paths. |
| **4 Projection / elision** | ✅ The instance rows store **facts, not derived booleans** (`:pending?` / `:success?` / `:settled?` are computed in the subs layer), so the durable row is a small projectable fact; `:affected-keys` / `:patch-summary` carry the optimistic-rollback trace shape ([§Optimistic mutations](#optimistic-mutations), [EP-0019](../docs/EP/EP-0019-optimistic-mutation-rollback.md)). Params, result, and error carry `:sensitive?` / `:large?` classification through `rf/elide-wire-value`; the `:error` envelope is the closed `:rf.http/*` failure shape. |
| **5 Teardown** | ✅ Host handles live in the shared `[frame-id work-id]` side tables (cleared on frame destroy with the resource hook `:resources/on-frame-destroyed!`); `:rf.mutation/clear` is the causal instance reset (clears the runtime instance and best-effort aborts in-flight work). **Durable kept:** the instance rows (facts). **Transient dropped:** host handles; the in-flight work record reconciles to dangling on restore exactly as the resource writer's does (the generation allocator is monotonic and host-side — [§Restore and replay](#restore-and-replay)). |

## Frame work ledger

Resource entries are cached read-model facts. In-flight attempts are **work facts**. They are linked but not collapsed into one map. EP-0003 introduces the first concrete slice of a frame work ledger; in the landed surface **two writers** participate — the resource runtime (work-kind `:resource`) and, since the first public-beta gate, mutations (work-kind `:mutation`, see [§Mutations](#mutations-first-public-beta-gate)). Both lower through the same managed-HTTP transport. The shape is neutral enough that later slices extend it to route loaders, timers, streams, spawned actors, and machine async work without rewriting resource semantics. (Clause 2 of the [work-ledger grading row](#rfruntimework-ledger--frame-work-ledger) is satisfied for both these in-artefact writers, which both stamp `:rf/framework-authority? true`; the open multi-writer authority question concerns the *first writer outside the Resources artefact* — see [§Work-ledger multi-writer authority](#work-ledger-multi-writer-authority--still-blocking-for-the-multi-writer-slice-post-v1-tracked-for-v1).)

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
  - **The frame-qualified transport request-id is the one sanctioned second identity.** The work-id is **frame-local** (its `resource-key` + `generation` carry no frame id), so it is *not* a safe process-global transport correlation token: the managed-HTTP in-flight registry keys by `:request-id` process-globally and supersedes/aborts by **equal** request-id ([Spec 014](014-HTTPRequests.md) §`:request-id`). Two frames issuing the same resource (or the same mutation instance) at the same generation mint the **same** work-id, so a bare-work-id request-id would let frame B supersede, abort, or suppress frame A's in-flight transport request. The runtime therefore lowers a **frame-qualified transport request-id** — `[:rf.req frame-id work-id]` — as the **second identity** this rule anticipates. It governs **only** transport-level in-flight correlation (registry keying, supersede-on-lower, opportunistic abort); intra-frame stale suppression still keys on `:work/id` + `:generation` (the durable identity). The opportunistic abort (`:rf.http/managed-abort`) MUST carry the **same** qualified token the lower registered, or it would miss the request (or, across frames, resolve a sibling frame's colliding request). It is a transport-facing token with a distinct job (process-global uniqueness) the frame-local work-id structurally cannot fill.

## Public API

The surface splits into **four lanes**, and they must not be read as competing read APIs. Keep them distinct:

| Lane | Spelling | Who calls it |
|---|---|---|
| **Registration** | `reg-resource` / `clear-resource`, `reg-mutation` / `clear-mutation`, `reg-resource-scope` / `clear-resource-scope` (functions) | Author, once, at boot. Declares a handler; does **not** fetch or read state. |
| **Commands (causal)** | `[:rf.resource/ensure …]`, `[:rf.resource/refetch …]`, `[:rf.mutation/execute …]`, … (dispatched event vectors) | App events / routes / machines. These **cause** work; they are not reads. |
| **App reads (passive)** | `[:rf.resource/state …]`, `[:rf.resource/data …]`, `[:rf.mutation/state …]`, … (subscription vectors) | Views, via `subscribe`. The **only** lane app UI uses to project runtime state. |
| **Tool/test projections** | `resource-meta`, `resource-state`, `resources`, `mutation-meta`, `mutation-state`, `mutations` (direct functions) | Tools (Xray), tests, SSR plumbing — an explicit-frame snapshot read. **Not** an app-UI alternative to the subscription lane. |

The load-bearing distinction is between **registration** (the function that records a `:resource` / `:mutation` / `:resource-scope` handler in the registrar — analogous to `reg-event` / `reg-sub`) and **projection** (reading the runtime state that handler produces). Registering a resource never reads its cache, and projecting its state never registers anything. App code projects through the **passive subscription** lane; the direct `resource-state` / `mutation-state` functions are the **tool/test projection** of the same runtime state at an explicit frame, used where there is no reactive subscription context (Xray, a unit test, an SSR serializer) — they are **not** a second app-read API competing with the subs.

### Registration

Per the canonical Spec 001 3-slot grammar the `:request`
handler fn is the third VALUE slot; the middle slot is the reflection +
config metadata map:

```clojure
(rf/reg-resource resource-id metadata request-fn)
(rf/clear-resource resource-id)

(rf/reg-mutation mutation-id metadata request-fn)
(rf/clear-mutation mutation-id)

(rf/reg-resource-scope scope-id resolver-spec)   ;; named db-derived scope resolver (EP-0016 D3)
(rf/clear-resource-scope scope-id)               ;; registration-lifecycle removal
(rf/resolve-resource-scope db scope-id)          ;; resolver helper: PURELY resolve a named scope against a db value (no trace)
```

`clear-resource` is a **registration-lifecycle** operation — the `clear-` registrar-removal inverse of `reg-resource`, per the [Conventions tear-down verb axis](Conventions.md#tear-down-verb-axis--clear--vs-destroy-) — **not** the normal cache-invalidation API. Application code uses the data-lifecycle **events** `:rf.resource/invalidate-tags`, `:rf.resource/remove`, or `:rf.resource/clear-scope` for cache work. The vocabulary overlap is settled: `clear-resource` (a registration-lifecycle **function**, no bang) and `:rf.resource/clear-scope` (a causal cache **event vector**) live in different registers — a registrar-removal verb vs. a dispatched event — so they never collide at a call site, and the registration trio keeps the same `clear-*` spelling as the rest of the registrar family (`clear-event`, `clear-sub`, `clear-fx`, …). When a resource registration is cleared, the implementation MUST also dispose resource-runtime state for that resource id in each affected frame: release owner indexes, cancel timers/host handles, abort in-flight requests where possible, suppress late replies by generation, remove tag-index rows, and emit a trace.

Three registrar kinds belong to this artefact: **`:resource`** (`reg-resource` / `clear-resource`), **`:mutation`** (`reg-mutation` / `clear-mutation`), and **`:resource-scope`** (`reg-resource-scope` / `clear-resource-scope`) — each a distinct kind in the [Spec 001 kind taxonomy](001-Registration.md#registry-model--the-canonical-kind-keyword-set), late-bound by the optional Resources artefact (an app that omits the artefact registers none of them), enumerable via `(rf/registrations :resource)` / `(rf/registrations :mutation)` / `(rf/registrations :resource-scope)` and inspectable via `(rf/handler-meta :resource-scope <id>)`. Do **not** add a `:query` public kind (it collides with route query params and prior-art names).

`reg-resource-scope` registers a **pure** named scope resolver (see [§Named resource-scope resolvers](#named-resource-scope-resolvers-reg-resource-scope)); `clear-resource-scope` is its `clear-` counterpart (the registrar decrement, per [Conventions §Tear-down verb axis](Conventions.md#tear-down-verb-axis--clear--vs-destroy-)). `resolve-resource-scope` is a **pure resolver helper** (a plain function over the resolver registry, resolving a named scope against a supplied db value) — it is **not** an effect, has **no app-state / dispatch side effects**, and emits **no** `:rf.resource/scope-resolved` trace either, because it is a passive read advertised as pure: a helper that resolves a scope from a db value must not mutate observability state. The `:rf.resource/scope-resolved` dev-trace evidence is emitted at the **causal** resolution boundaries — a resource event's `{:from-db …}` scope, route entry, and mutation settle — where the resolution is part of a recorded causal step; subscription key resolution, like `resolve-resource-scope`, resolves trace-free (a sub re-keys on every frame-state change, so a traced read would flood the trace bus). Its canonical use is the logout/account-switch idiom of [§`clear-scope` resolves the concrete scope from the coeffect db](#clear-scope-resolves-the-concrete-scope-from-the-coeffect-db-not-a-snapshot). Both `reg-resource-scope` and `resolve-resource-scope` are facade exports classified at [API §Resources](API.md#resources-spec-016).

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

The internal replies — `:rf.resource.internal/succeeded` / `:rf.resource.internal/failed` / `:rf.resource.internal/aborted` / `:rf.resource.internal/stale-fired` / `:rf.resource.internal/gc-fired` / `:rf.resource.internal/stale-suppressed` — are framework-internal and carry the verification payload (`:work/id`, `:resource/key`, `:scope`, `:generation`, `:rf.frame/id`); user code MUST NOT dispatch them directly. They **receive the canonical uniform reply map** ([Managed-Effects §The uniform reply envelope](Managed-Effects.md#the-uniform-reply-envelope)) — one closed `:status`, value-or-error, `:work/id`, `:work/kind :resource`, `:work/status`, `:rf.frame/id`, causal completion metadata, and `:correlation`. The verification work identity is the qualified `:work/id` (the single spelling the ledger row, the entry's `:current-work`, and the reply envelope all use — [EP-0007](../docs/EP/EP-0007-one-name-per-fact.md): one attempt, one work id, one name; the unqualified `:work-id` is retired). See [§The uniform reply envelope and the canonical reply map](#the-uniform-reply-envelope-and-the-canonical-reply-map).

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

For the common top-level state read, the artefact ships **`sub-resource`** as named read sugar over the canonical `[:rf.resource/state <query>]` vector — `@(rf/sub-resource query)` is `@(rf/subscribe [:rf.resource/state query])`, returning a reaction over the resource view-model `{:status :data :error :refresh-error :loading? :fetching? :stale? :has-data?}` (plus the `:previous?` previous-data projection). `query` is the `{:resource :params}` map (with the optional `:scope` the sub-side scope resolution reads) the vector form takes; the sugar passes it through unchanged, so it resolves the SAME scoped key — including the fail-closed `:rf.error/resource-sub-unresolved-scope` boundary. The 2-arity `(sub-resource query opts)` carries `{:frame <target>}` (a frame-id keyword or a live frame object, lowered to `subscribe`'s frame-first arity) to read an explicit frame from outside an established scope. `sub-resource` is exported on the **`re-frame.resources` façade — not `re-frame.core`** — so a non-resources app's production-elision bundle carries no resource keyword strings (the resources bundle-isolation invariant, peer of routing's `sub-route`); the `[:rf.resource/state <query>]` vector stays canonical and the sugar coexists with it.

### Introspection and projection (tool/test only)

```clojure
(rf/resource-meta :article/by-slug)                                  ;; registration projection: the registered spec
(rf/resource-state {:resource … :scope … :params … :frame :app/main}) ;; runtime projection: one entry, explicit frame
(rf/resources      {:frame :app/main})                                ;; registry + live entries for a frame
```

These direct functions are the **tool/test projection lane**, not an app-read API. `resource-meta` projects the **registration** (the registered spec), while `resource-state` / `resources` project **runtime state** (the live cache entries) at an explicit frame — the same runtime state the `[:rf.resource/*]` subscriptions derive, read here without a reactive subscription context. Their callers are Xray, unit tests, and SSR/serialization plumbing. **App views MUST use the passive subscription lane** ([§Subscriptions](#subscriptions-passive)) — these functions take a one-shot, non-reactive snapshot and do not re-render on change, so reaching for them in a view is a category error (registering vs. projecting vs. subscribing are three different jobs; see the [lane table](#public-api)).

`:frame` is an explicit, app-registered frame id (`:app/main` is illustrative). Per [EP-0002](../docs/EP/EP-0002-frame-target-resolution.md) there is no ambient `:rf/default` fallback: the frame target is carried explicitly, and a frameless introspection call with no resolvable context fails closed rather than silently inspecting the wrong frame.

### Resource registration spec

Per the canonical Spec 001 3-slot grammar the `:request` fetch
fn is the third VALUE slot; the middle slot is the reflection + config metadata
map:

```clojure
(rf/reg-resource
  :article/by-slug
  {:doc "Article detail by slug."

   :params-schema [:map [:slug :string]]
   :data-schema   :app/article

   :scope          :rf.scope/global   ;; REQUIRED — an explicit, auditable claim
   :transport      :rf.http/managed
   :stale-after-ms 60000
   :gc-after-ms    300000
   :poll-interval-ms 5000             ;; (optional) revalidate every 5s while actively owned + visible
   :tags           (fn [{:keys [slug]} _data] #{[:article slug]})
   :sensitive?     false}

  ;; the :request handler is the THIRD slot (rf2-wvh95f F1)
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}
     :decode :app/article}))
```

**Required keys** (MUST):

- **`:params-schema`** — validates and canonicalizes params.
- **`:scope`** — the resource's **scope policy**, one of `:rf.scope/global`, a resolver, or `:rf.scope/from-caller` (see [§Scope resolution](#scope-resolution)). It is **required**: a `reg-resource` with no scope policy is a loud registration error (`:rf.error/resource-missing-scope-policy`). A genuinely process-independent resource declares `:scope :rf.scope/global` explicitly; there is no implicit default.
- **`:request`** — the resource's HANDLER, supplied as the **third VALUE slot** of `reg-resource` (not a metadata key; declaring `:request` inside the metadata map is a loud `:rf.error/resource-bad-spec`). For `:transport :rf.http/managed` (the only initial-scope transport), it returns a Spec 014 managed-HTTP args map, including the nested `:request` child and top-level keys such as `:decode`, `:accept`, `:retry`, and sensitivity metadata. The args map MUST NOT supply `:request-id`, `:on-success`, or `:on-failure` — resource lowering supplies those from the scoped resource key and current generation (see [§Transport](#transport)); implementations reject those reserved keys at registration or dispatch.

These three are the registration gate (`:scope` fail-closed first, then `:params-schema` and `:request`); a `reg-resource` missing any of them throws (`:rf.error/resource-missing-scope-policy` for `:scope`, `:rf.error/resource-bad-spec` for `:params-schema` / `:request`). The set mirrors `reg-mutation`'s (`:params-schema` + `:request` + `:scope`).

**Optional v1 keys:** `:doc`, `:data-schema`, `:transport` (initial scope: `:rf.http/managed`, the only built-in), `:stale-after-ms`, `:gc-after-ms`, `:poll-interval-ms` (the active-owner poll interval — see [§Polling](#polling)), `:tags`, and classification — the projection-relative `:sensitive` / `:large` path-vector declarations (EP-0025 §subsystems, the canonical surface) plus the coarse root-prop `:sensitive?` / `:large?` whole-entry claims.

`:data-schema` is **optional** — when present it validates successful data wherever transport decode supports it; when absent, response data is not shape-validated. Unlike `:params-schema` (the resource's identity, REQUIRED) and `:scope` (the fail-closed security boundary, REQUIRED), a resource is well-formed without `:data-schema`, so the registration gate does not enforce it — matching the shipped reference implementation and the flagship example, which omit it. Per [EP-0025](../docs/EP/EP-0025-data-classification.md) a `:data-schema` / `:params-schema` **VALIDATES only** — it does **not** drive durable egress classification; its per-slot `:sensitive?` / `:large?` props serve solely the schema's own **validation-failure-trace** redaction ([015-Data-Classification §Schemas describe shape](015-Data-Classification.md#schemas-describe-shape-not-durable-app-db-egress-policy)).

Classification is **owned by the resource / mutation definition** (a resource *is* defined at `reg-resource`, so it declares its own statically-known sensitive / large fields there — "classify at the fact's definition site"). Per [EP-0025 §subsystems](../docs/EP/EP-0025-data-classification.md) the **single fine-grained surface** is **projection-relative `:sensitive` / `:large` declarations** on the spec — a vector of `:rf/path` vectors rooted at the instance projection (the matrix root: entry's `:params` / `:data`):

```clojure
(rf/reg-resource :user-profile
  {:scope         :rf.scope/global
   :params-schema [:map [:account-id :string]]
   :sensitive     [[:data :ssn] [:params :account-id]]
   :large         [[:data :avatar-bytes]]}
  (fn [{:keys [account-id]} _] {:request {:method :get :url (str "/u/" account-id)}}))
```

A `:data`-rooted path classifies the data value, a `:params`-rooted (or `:scope`-rooted) path classifies the scoped key (Spec 016 clause 4: "params, scopes, and data carry the same classification"), and a bare-rooted path defaults to the data projection (the `{:sensitive [[:ssn]]}` shorthand). The declaration is **value-independent** and **standing** — it redacts whatever later occupies the slot on **every instance** (the per-instance application the matrix names: every minted scoped key / landed data value redacts under the one owner declaration, no per-instance author code, no storage paths in app code). A malformed declaration (a non-vector axis, a non-path entry) is rejected **fail-loud at registration** (`:rf.error/resource-bad-spec` / `:rf.error/mutation-bad-spec`). The same axis vocabulary the machine / app-db / transient cases use — one name per fact.

The runtime **lowers** each declaration into the per-frame elision registry **per instance** — re-rooting the `:data`-rooted paths under the entry's absolute runtime path (`[:rf.runtime/resources :entries <key-id> :data …]`) and the `:params` / `:scope`-rooted paths under the entry's scoped key, written `:source :resource` — exactly as machines (`:source :machine`) and routes (`:source :route`) lower theirs (Spec 015 §Subsystem projection-relative classification). A generic registry-reading consumer (Xray "what is classified", an MCP registry view, the SSR registry-projection defence-in-depth) therefore SEES resource classification, and an evicted entry's declarations drop with it. The coarse whole-entry `:sensitive?` / `:large?` claims on the spec remain as the degenerate **root-prop** case (the whole resource is the classification unit, driving the metadata-only redact / omit shape). Projection applies all of these at every egress boundary (SSR, tool, trace, epoch, observability) through the merged frame-owned `rf/project-egress` over the shared `rf/elide-wire-value` walker — never a family-private elider. See [015-Data-Classification §Resource and mutation durable classification](015-Data-Classification.md#resource-and-mutation-durable-classification).

**Deferred keys** (rejected / unused in v1): `:revalidate`, `:placeholder`, transport extension protocols, `:cache-key`. (Interval polling is `:poll-interval-ms` — see [§Polling](#polling).) The `:infinite` registration kind — the ordered-page-sequence / load-more feed — is specified: see [§Infinite resources and load-more feeds](#infinite-resources-and-load-more-feeds) ([EP-0021](../docs/EP/EP-0021-infinite-resources.md)). The mutation-only keys (`:invalidates`, `:patches`, `:populates`, `:removes`, `:optimistic`, `:optimistic-tags`, `:on-conflict`) are **not resource-registration keys** — they live on `reg-mutation` (`:optimistic` / `:optimistic-tags` / `:on-conflict` land via [§Optimistic mutations](#optimistic-mutations)).

### No `:select` key

Do **not** add a TanStack-style `:select` key in v1. In re-frame2, projections are ordinary subscriptions layered over `[:rf.resource/data …]` ([EP-0004](../docs/EP/EP-0004-subscription-inputs.md) parametric inputs). That is not a missing feature; it is a structural advantage of the subscription graph.

### Mutations (first public-beta gate)

A **mutation** is a named, causal WRITE to remote state that, on success, invalidates / patches / populates cached resource reads — the write counterpart of the read-resource grammar. The full normative contract lives in [EP-0003 §Mutations](../docs/EP/EP-0003-resource-queries.md#mutations); this section names the landed surface.

Per the canonical Spec 001 3-slot grammar the `:request` write
fn is the third VALUE slot; the middle slot is the reflection + config metadata:

```clojure
(rf/reg-mutation
  :article/save
  {:params-schema :app/article          ;; REQUIRED — validates + canonicalizes params
   :invalidates  (fn [{:keys [slug]} _result] #{[:article slug] [:article-list]})
   :patches      (fn [params result] {scoped-key (fn [old result] (merge old result))})
   :populates    (fn [params result] {scoped-key result})
   :removes      (fn [params _result] [target-map])   ;; controlled exact-key REMOVALS (a delete write)
   :scope        :rf.scope/global       ;; the cache scope invalidation/patch defaults to
   :invalidate-timing :after-success}   ;; | :before-request | :after-failure | :after-settle

  ;; the :request write handler is the THIRD slot (rf2-wvh95f F1) — the
  ;; Spec 014 managed-HTTP write, REQUIRED
  (fn [{:keys [slug] :as article} _ctx]
    {:request {:method :put :url (str "/api/articles/" slug) :body article}
     :decode  :app/article}))

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

[:rf.mutation/state    {:instance :form/save-1}]   ;; {:status :result :error :pending? :success? :error? :settled? :optimistic?}
[:rf.mutation/status   {:instance :form/save-1}]
[:rf.mutation/pending? {:instance :form/save-1}]
[:rf.mutation/result   {:instance :form/save-1}]
[:rf.mutation/error    {:instance :form/save-1}]
```

For the common top-level state read, the artefact ships **`sub-mutation`** as named read sugar over the canonical `[:rf.mutation/state {:instance <instance>}]` vector — `@(rf/sub-mutation instance)` is `@(rf/subscribe [:rf.mutation/state {:instance instance}])`, returning a reaction over the mutation view-model `{:status :result :error :affected-keys :pending? :success? :error? :settled? :optimistic?}` (the idle empty-state shape until the instance's first `:rf.mutation/execute`). The `{:instance …}` wrapping lives INSIDE the sugar, so callers pass just the **instance** id — the key a view scopes one form submission's state to. The 2-arity `(sub-mutation instance opts)` carries `{:frame <target>}` (a frame-id keyword or a live frame object, lowered to `subscribe`'s frame-first arity) to read an explicit frame from outside an established scope. Like `sub-resource` it is exported on the **`re-frame.resources` façade — not `re-frame.core`** (the resources bundle-isolation invariant); the `[:rf.mutation/state {:instance …}]` vector stays canonical and the sugar coexists with it.

A `:mutation` registrar kind is added (the causal-write counterpart of `:resource`). The load-bearing invariants (MUST):

- **Runtime state is keyed by mutation INSTANCE id, not mutation id** — two concurrent submissions of `:comment/add` keep distinct `:pending` / `:success` / `:error` rows and never clobber each other ([EP-0003 §Mutations](../docs/EP/EP-0003-resource-queries.md#mutations)). The instance id is caller-supplied or generated (the generated id closes over the monotone generation, so concurrent generated submissions differ).
- **The write lowers through the SAME managed-HTTP transport as resources** — the runtime owns reply addressing (`:request-id` / `:on-success` / `:on-failure` are supplied from the instance + generation; an app `:request` that supplies them is rejected). The internal mutation replies (`:rf.mutation.internal/succeeded` / `:rf.mutation.internal/failed`) **receive the canonical uniform reply map** with `:work/kind :mutation` (see [§The uniform reply envelope and the canonical reply map](#the-uniform-reply-envelope-and-the-canonical-reply-map)); the decoded result rides as `:value` on the reply and is stored under the instance's durable `:result`. Generation + work-id **stale suppression** is the correctness boundary exactly as for resources: a superseded reply (a re-execute under the same instance, or an `:rf.mutation/clear`) NEVER overwrites a newer instance. Abort+retry are inherited from the transport; **write retries are OPT-IN** (a mutation arms `:retry` only when its `:request` declares it).
- **Success patches/populates/removes resource entries, then invalidates tags** — the controlled `:patches` / `:populates` transform / seed resource entries (through the same durable entry shape + structural sharing the read path uses), then `:removes` drops exact entries (a delete write — the resolved key is dissociated and its in-flight attempt best-effort aborted, mirroring `:rf.resource/remove`), all BEFORE the success-time invalidation; `:invalidates` then composes with the landed `:rf.resource/invalidate-tags` (scoped). A `:removes` arm is `(fn [params result] -> [target …])` over the same map-form exact targets as `:populates` / `:patches`. **Invalidation timing** is explicit (`:before-request` / `:after-success` (default) / `:after-failure` / `:after-settle`).
- **The accepted reply's `:affected-keys` carries every touched key** — populated, patched, removed, **and** the keys the success-time (or `:after-failure`) invalidation marks stale. The mutation runtime pre-computes the stale-marked keys through the **same shared match** the dispatched `:rf.resource/invalidate-tags` uses, so the reply contract ([§Mutation completion continuations](#mutation-completion-continuations--call-site-reply-to)) and the engine never disagree.
- **Failure settles `:error`** (no `:refresh-error` analogue — a write has no last-known-good to keep); `:rf.mutation/clear` is the causal reset that clears the runtime instance (and best-effort aborts in-flight work).
- **Trace-visible instance ids** — the `:rf.mutation/*` trace family (`started` / `succeeded` / `failed` / `cleared` / `stale-suppressed` / `replied` / `optimistic-applied` / `optimistic-rolled-back` / `optimistic-reconciled`) carries the instance id; the success trace records the cache-consequence shape (affected keys, patch summary). The `:rf.mutation/optimistic-applied` op (EP-0019 phase 1.5) records the **forward optimistic apply**: the `:snapshot-id`, the touched `:affected-keys`, the per-key `:revision` + forward op shape, the `:tag-matched-keys`, and the fail-closed `:target-unresolved` ids; the instance row's `:patch-summary` `:snapshot-id` / `:rollback` slots carry the recorded snapshot inverse (the settle ops `:rf.mutation/optimistic-rolled-back` / `:rf.mutation/optimistic-reconciled` are its companions — see [§Optimistic mutations](#optimistic-mutations)). The `:rf.mutation/replied` op (EP-0016 D1) records a **call-site `:reply-to` continuation dispatch**: it carries the continuation target, work id, mutation id, instance, status, and `:cause [:mutation <id> <instance>]` — emitted only for an accepted terminal reply, never for a stale/suppressed one (the delivery rule of [§Mutation completion continuations](#mutation-completion-continuations--call-site-reply-to)).

#### Mutation scope is two distinct scopes (hybrid)

Unlike a resource — whose single scope policy is uniformly fail-closed (§[Scope resolution](#scope-resolution)) — a mutation carries **two distinct scopes** with **opposite default policies**, because a causal write and a cached read have different safety boundaries.

- **Execution scope is FAIL-OPEN on *absence*, not on a *wrong* value.** The scope a mutation's invalidation / patch / populate *defaults to* resolves in precedence order **execute-payload `:scope` → mutation-spec `:scope` → `:rf.scope/global`**. A mutation is a causal write, not a cached read, so it has **no cached-read leak boundary of its own** — defaulting to `:rf.scope/global` when no scope is supplied leaks nothing, so `:scope` is OPTIONAL at `reg-mutation` and on the execute payload. Fail-open governs **only** the absent-scope case: a scope that **is** supplied is still routed through the shared scope-canonicalization path, so a misspelled `:rf.scope/*` keyword or an opaque host value is rejected loudly. The framework never silently accepts a *wrong* scope value — it only supplies global when *none* was named.
- **Invalidation scope is FAIL-CLOSED.** The success-time invalidation a mutation triggers composes with `:rf.resource/invalidate-tags`, which **requires an explicit scope**: it throws `:rf.error/resource-invalidate-scope-required` when none is supplied, and `:cross-scope? true` is the **only** scope-agnostic opt-out (which MUST be visible and lintable in Xray, as for any broad invalidation). A blind invalidation across all scopes would stale or refetch data for other users, tenants, story frames, or SSR requests — exactly the leak boundary the read path protects.

Because the mutation supplies its resolved execution scope as the invalidation scope, the two compose: the fail-open execution default *becomes* the concrete scope the fail-closed invalidation runs in.

> **Scope-match guidance (the footgun).** A mutation's resolved scope MUST match the scope of the resources it intends to invalidate. `:invalidates` matches only entries **in the resolved scope**; if a `:rf.scope/global`-defaulted mutation invalidates tags owned by a `:rf.scope/session`-scoped resource (or vice versa), the invalidation **silently misses** — no entry matches, the cached read is never refreshed, and no error is raised (it is a legitimate "no match in this scope"). When a write affects session- (or tenant-) scoped reads, the mutation MUST explicitly declare the matching invalidation scope (typically via the execute payload `:scope`), or — better — use the per-target [§Scoped invalidation descriptors](#scoped-invalidation-descriptors-per-target) below, which lets a single mutation invalidate across more than one named scope target without resorting to a blanket cross-scope sweep.
>
> **Dev-mode write-side tripwire (`:rf.warning/mutation-scope-mismatch`).** Because this miss is silent by construction, the framework surfaces it as a **dev-only** warning at the moment of the mismatched invalidation — the **write-side complement** of the read-side [§Dev-mode likely-mismatch warning](#dev-mode-likely-mismatch-warning-rfwarningresource-sub-scope-mismatch). The heuristic, applied per dispatched `:invalidates` descriptor at mutation settlement (`:after-success` / `:after-settle` / `:after-failure`, and on `:before-request` timing): the descriptor matched **zero** entries in its resolved scope **while the same tags DO match an entry in a different scope** (the shared `match-invalidation-keys` `:other-scope-hit?` signal — "no match in THIS scope" rather than "no resource provides this tag in ANY scope"). A `:cross-scope? true` descriptor — the audited escape — is never flagged, and a tag with no entry in any scope (a true nothing-to-invalidate) does not warn. The trace op is `:rf.warning/mutation-scope-mismatch`, carrying `:mutation`, `:instance`, the `:descriptor-scope` it invalidated in, the `:mutation-scope` it resolved, the `:other-scope` that DID hold a matching entry, and the `:tags`; its `:hint` names the fix (declare the matching `:scope` on the execute payload, or use a per-target descriptor). Dedupe-keyed (fires once per `[mutation-id descriptor-scope other-scope tags]`) and `interop/debug-enabled?`-gated, so it is DCE'd from production builds. The warning is the runtime tripwire for the cases the fail-open execution default cannot catch fail-closed (the invalidation DID resolve a scope; it resolved the WRONG one).

#### Mutation completion continuations — call-site `:reply-to`

A verified mutation reply is a **causal token** ([EP-0016](../docs/EP/EP-0016-resource-mutation-completion.md) Decision 1): when the runtime accepts a reply as current, that reply is ordinary causal input, and it can drive durable app state only by dispatching an event. The continuation mechanism is therefore **not a callback** — it is a call-site **event target**. A callback that returned effects would mint effects outside the event tape, the interceptor chain, and replay; that is rejected (see [§Resolved decisions](#resolved-decisions)).

`:rf.mutation/execute` accepts an **optional call-site `:reply-to`** event target:

```clojure
[:rf.mutation/execute
 {:mutation :article/save
  :params   {:slug slug :draft draft}
  :instance [:editor/save slug]
  :reply-to [:editor/save-replied]}]
```

When the reply is accepted as current for the frame, mutation instance, work id, and generation, the runtime dispatches the target with **one reply map appended** as the final event argument. The reply map is the **canonical uniform reply map** of [§The uniform reply envelope and the canonical reply map](#the-uniform-reply-envelope-and-the-canonical-reply-map) (the `:rf/reply-map` shape, [Spec-Schemas](Spec-Schemas.md#rfreply-map-rfreply-target-uniform-reply-envelope-ep-0011)), so a continuation observes the same closed `:status`, `:value`/`:error`, `:work/id`, `:work/kind :mutation`, `:rf.frame/id`, and `:completed-at` (EP-0010 causal completion time) every managed-async family produces — plus the mutation-specific facts a continuation needs:

| Field | Required | Meaning |
|---|---|---|
| `:status` | yes | The EP-0011 closed reply status (`:rf/reply-status`); see the delivery rule below for which members a continuation can carry. |
| `:mutation` | yes | Mutation id. |
| `:params` | yes | Canonical mutation params used for the accepted attempt. |
| `:instance` | yes | Mutation instance id. |
| `:scope` | yes | Resolved (execution) mutation scope. |
| `:value` | for `:ok` | Decoded accepted value (the reply-map spelling — `:value` everywhere, [EP-0007](../docs/EP/EP-0007-one-name-per-fact.md); the durable instance row keeps `:result`, per the layering above). |
| `:error` | for `:error` | Structured `:rf.http/*` error envelope. |
| `:affected-keys` | yes | Resource keys populated, patched, removed, or marked stale by the accepted reply (the same `:rf/scoped-resource-key` shape; rides `:correlation`/family `:meta`). |
| `:work/id` | yes | The work-ledger identity for the accepted attempt — the mutation head `[:rf.work/resource [:rf.mutation instance-id] generation]` (one spelling, [§The uniform reply envelope](#the-uniform-reply-envelope-and-the-canonical-reply-map)). |
| `:rf.frame/id` | yes | The carried frame stamp (EP-0002). |
| `:completed-at` | yes when completion time can affect durable state | EP-0010 causal completion time — never a fresh ambient read. |
| `:cause` | yes | Data explaining the mutation/instance that caused the continuation: `[:mutation <mutation-id> <instance-id>]`. |

Static call-site arguments are preserved; the reply map is appended **after** them — `:reply-to [:toast/after-save {:kind :article}]` dispatches `[:toast/after-save {:kind :article} reply]` (the `:rf/reply-target` `:append` delivery, the only public mode).

> **`:status` is the EP-0011 enum, not a divergent subset.** The reply `:status` is exactly the canonical `:rf/reply-status` member set [Managed-Effects §Status taxonomy](Managed-Effects.md#status-taxonomy) defines (`:ok` / `:partial` / `:error` / `:cancelled` / `:stale`) — there is no private subset. A `:reply-to` continuation can carry only the **accepted terminal** members: `:ok`, `:error`, and an accepted terminal `:cancelled` (and `:partial` where the family produces it). It **never** carries `:stale` — a stale/suppressed reply does not dispatch the continuation at all (the delivery rule below).

##### Delivery rule — keyed on acceptance, not on a status enumeration

The continuation fires **for any accepted terminal reply** and **never** for a stale or suppressed one. This is the single rule (no per-status table to drift):

- a reply the mutation runtime accepts as current for the frame, mutation instance, work id, and generation dispatches the `:reply-to` target **exactly once**;
- a **stale or superseded** reply (a re-execute under the same instance, or an `:rf.mutation/clear`) is traced (`:rf.mutation/stale-suppressed`) and **does not** dispatch the continuation — it is exactly the mandatory stale-suppression boundary the reply envelope already enforces ([§The uniform reply envelope](#the-uniform-reply-envelope-and-the-canonical-reply-map)), so the continuation inherits it for free;
- **cancellation** dispatches the continuation only when the runtime owns a **terminal cancellation result** (an accepted `:status :cancelled`); a host-level best-effort abort alone does not guarantee a reply, so it does not guarantee a continuation. This settles the cancellation edge the bare stale-suppression rule left implicit.

##### Phase order (normative)

A mutation attempt has this deterministic runtime-owned order; it is normative because it determines what a continuation observes:

1. **Resolve** canonical params and the mutation (execution) scope.
1.5. **Optimistic apply** *(only when an [`:optimistic` / `:optimistic-tags`](#optimistic-mutations) plan is present and the call did not opt out)* — snapshot each touched entry (the recorded inverse + its `:revision`), apply the forward patch, bump each entry's `:revision`, and emit `:rf.mutation/optimistic-applied`. A purely-pessimistic mutation skips this phase entirely.
2. **Send** — issue the managed request under runtime-owned `:work/id` / reply addressing.
3. **Accept / suppress** — receive a host reply and stale-suppress or accept it.
4. **Cache consequences** — apply the accepted reply's `:patches`, `:populates`, `:invalidates`, and removes (the populate-as-authoritative-load rule below governs the populate/invalidate net effect); for an optimistic write this phase also **settles** the optimistic apply (commit / rollback / reconcile — the settle protocol).
5. **Instance settlement** — settle the mutation instance row and the work-ledger row.
6. **Continuation** — dispatch the `:reply-to` target, if present, with the reply map appended.

A handler reached by `:reply-to` therefore sees cache consequences **and** mutation instance state already settled for the accepted reply.

##### Registration-level continuations are deferred

This slice does **not** add `:reply-to` to `reg-mutation`. The registration-level success plan already exists as declarative data — `:patches`, `:populates`, `:invalidates`. An invariant workflow continuation is spelled by every call site passing the same event target; a registration-level workflow target would hide app behaviour inside the remote-write definition. If a later consumer proves invariant non-cache workflow is common and cannot be cleanly expressed at call sites, a future EP may add a registration-level **event target** with this same reply-map shape — it MUST NOT be an effect-returning callback (EP-0016 issue 1).

#### Scoped invalidation descriptors (per-target)

The bare tag-set shorthand on `:invalidates` (`#{[:article slug] [:article-list]}`) remains valid; it means **invalidate those tags in the mutation's resolved scope** (`:rf.scope/same`). A **tag is a vector** (`[:article slug]`, `[:article-list]`) — a structured name for a remote fact — so a tag-set is a set or sequence *of vectors*. A **lone vector tag written directly** (`:invalidates (fn [_ _] [:article slug])`) is treated as the **one tag** `#{[:article slug]}`, not split into the scalar set `#{:article slug}` (which would name nothing and silently match nothing). The single tag-set shorthand and the direct [`:rf.resource/invalidate-tags`](#events-map-payloads-not-positional-argument-vectors) `:tags` value share **one** input-normalization contract, so a lone vector tag has exactly one meaning across the cache. [EP-0016](../docs/EP/EP-0016-resource-mutation-completion.md) Decision 2 adds a **per-target descriptor form** so one mutation can precisely invalidate facts living in *different* scopes — the structural problem the bare form cannot express (tags name remote *facts*; scopes name *viewers*; stale is a property of a `(fact, viewer)` pair):

```clojure
:invalidates
(fn [{:keys [slug]} _result]
  [{:scope :rf.scope/global
    :tags  #{[:article slug] [:article-list]}}
   {:scope {:from-db :realworld/session}
    :tags  #{[:feed]}}])
```

Each descriptor carries **its own scope**. A descriptor `:scope` is one of:

- **`:rf.scope/same`** — the mutation's resolved (execution) scope. This is the **default** when a descriptor omits `:scope`, and the meaning of the bare tag-set shorthand.
- **`:rf.scope/global`**.
- a **concrete canonical scope value** such as `[:rf.scope/session {:username "jake"}]`.
- a **named scope resolver reference** `{:from-db :realworld/session}` (see [§Named resource-scope resolvers](#named-resource-scope-resolvers-reg-resource-scope) below) — resolved against db **at settle time** (phase 4), the single use-time resolution rule for `{:from-db …}` references.
- a future `{:from-route …}` / `{:from-frame …}` reference reserved for a later EP (the `[:runtime path]` source named below).

Bare shorthand and descriptor form **both lower to the same scoped invalidation engine** — there is exactly **one invalidation implementation** (the landed `:rf.resource/invalidate-tags`, scoped). The descriptor is only the public data telling that engine which `(tags, scope)` pairs to mark stale; it does not add a second engine. The `:invalidates` and `:populates` callbacks share **one canonical signature**, `(params result)` (see [§Cache-consequence callback signatures](#cache-consequence-callback-signatures) below) — db-derived scope is expressed **only** through named resolver references, never by threading `db` / `ctx` into the callback.

##### The cross-scope lattice — three precise rungs

Per-target descriptors do not retire the existing `:cross-scope?` escape; the two answer different questions, and the full lattice is fail-closed at the bottom:

| Form | What it does | When |
|---|---|---|
| bare `:invalidate-tags` with **no scope** | **loud error** (`:rf.error/resource-invalidate-scope-required`) | never silently global — the fail-closed floor |
| **descriptors** (`{:scope … :tags …}`) | invalidate named `(tags, scope)` pairs the call site **knows** | the precise, ordinary path — global facts + viewer-relative facts in one mutation |
| **`:cross-scope? true`** | invalidate a tag **in every scope currently holding it** — scopes the call site **cannot enumerate** but the cache **can** (admin tooling, cache-poisoning response, migration) | the explicit **audited escape** |

`:cross-scope? true` is a genuinely different operation from a descriptor: a descriptor can only name scopes the author already knows; "invalidate this tag wherever it lives" targets scopes unenumerable at the call site. Because it can stale or refetch data across users, tenants, story frames, and SSR requests, it is an **audited** operation:

- it **MUST** carry `:cause` evidence (a cross-scope invalidation with no `:cause` is rejected);
- it is a **privacy-relevant trace event** ([EP-0015](../docs/EP/EP-0015-frame-owned-egress-policy.md)) — the runtime records that a mutation invalidated entries outside its own resolved scope;
- dev/Xray **warns** when a descriptor would be more precise than a broad `:cross-scope?` sweep.

##### Cache-consequence callback signatures

`:invalidates`, `:populates`, `:patches`, and `:removes` are **registration-level success-phase data plans**, each a fn of the **one canonical signature** `(params result)`:

- **`params`** — the canonical mutation params used for the accepted attempt (post-`:params-schema`).
- **`result`** — the **decoded accepted value** of the reply (the `:value` of the reply map; the resource's stored shape, not a sub-projection).

There is **no `ctx` / `db` argument** on these callbacks. Db-derived scope is reached only through named resolver references (`{:from-db …}`) inside a descriptor — threading `db` / `ctx` into the callback would reintroduce the anonymous-db-function path EP-0016 §Alternatives rejects (it hides the dependency that determines scope, defeats tooling naming, and gives invalidation descriptors no stable reference). A descriptor's `{:from-db …}` is resolved by the runtime at settle time against the frame db — the callback returns *data* naming a resolver, never reads db itself.

##### Trace evidence for invalidation

The runtime records the invalidation evidence the [§Xray and AI tooling](#xray-and-ai-tooling) trace family carries, in two complementary places:

- **per-pass, on `:rf.resource/invalidated`** — one decision summary per invalidation pass through the engine: the resolved `:scope`, the requested `:tags`, the `:cause`, the `:cross-scope?` flag, the `:matched` scoped keys (so a broad-tag storm and a zero-match "no match in this scope" are distinguishable), the `:refetched` / `:left-stale` counts, the `:exempt` keys a same-mutation populate kept authoritative (spared from this pass — [§Populate is an authoritative load](#populate-is-an-authoritative-load)), and `:any-tag-match-other-scope?` (whether the tags match an entry in **another** scope — "no match HERE" vs "no resource provides this tag in any scope").
- **per-descriptor, on the mutation settlement op** (`:rf.mutation/succeeded` / `:rf.mutation/failed`, under the `:invalidation` facet) — when a mutation's `:invalidates` plan drives the invalidation: the `:descriptor-count`; the `:dispatched` descriptors each carrying the **resolved scope**, `:cross-scope?` (the audited scope-agnostic escape), `:tags`, `:refetch-populated?` (the Rider-1 partial-reply opt-in), **and that descriptor's own `:exempt-keys`** — the populated keys *that* pass spared (empty when the descriptor opted into `:refetch-populated? true`); the fail-closed `:unresolved` `{:from-db …}` ids (descriptors that resolved **nil** and produced no invalidation — never an implicit global blast); and the top-level `:populate-exempt` keys (the **union** of every descriptor's spared keys — entries this same mutation populated and exempted from same-mutation refetch by [§Populate is an authoritative load](#populate-is-an-authoritative-load)). The per-descriptor `:exempt-keys` is the truthful evidence in a **mixed plan** where one descriptor opts into `:refetch-populated? true` and another default descriptor matches the same populated key: the top-level `:populate-exempt` does not collapse to empty just because one descriptor opted in. The descriptor-level evidence rides the settlement op rather than a new trace op (one mutation = one descriptor-level evidence record); a `{:from-db …}` resolver's own resolution is recorded separately on `:rf.resource/scope-resolved` (its `:resolved-nil?` flag is the fail-closed nil evidence).

#### Populate is an authoritative load

For a key it targets, `:populates` is semantically equivalent to a **successful resource load** produced by the accepted mutation reply ([EP-0016](../docs/EP/EP-0016-resource-mutation-completion.md) Rider 1). Therefore, for a populated key:

- it becomes `:loaded`;
- the populated value becomes the current `:data`;
- freshness/staleness timers are armed as if the key had loaded normally;
- it is **exempt from immediate refetch by the same mutation's invalidation pass** (so a mutation that populates an article detail and then invalidates a broad article tag matching that entry does **not** immediately refetch the key it just learned from the write reply);
- it may still be invalidated/refetched by later events, later mutations, focus/reconnect policy, or explicit refetch.

The populated value **MUST be the resource's stored shape** — the same value a successful load of that key produces (e.g. the full decoded envelope), **not** a sub-projection of the reply — so a populated entry reads **identically** to a fetched one. Populating an unwrapped inner value where the resource stores the whole envelope is a cache-coherence bug, not an option.

If a reply is partial relative to the full resource GET, the author opts a descriptor into a same-mutation refetch:

```clojure
:invalidates
[{:scope :rf.scope/global
  :tags  #{[:article slug]}
  :refetch-populated? true}]
```

`:refetch-populated?` changes **exactly** whether a key populated by *this* mutation may be **immediately refetched by this same mutation's invalidation pass**. The default is **no** same-mutation refetch for keys this mutation populated.

#### Map-form exact resource targets

Exact resource targets (`:populates`, `:patches`, removes) have **one canonical public source shape** — the **target map** ([EP-0016](../docs/EP/EP-0016-resource-mutation-completion.md) Rider 2):

```clojure
{:resource :realworld/article
 :params   {:slug slug}
 :scope    :rf.scope/global}
```

`:scope` may be concrete, `:rf.scope/same`, `:rf.scope/global`, or a named resolver reference. Rules:

- **populate** creates or replaces exactly one key;
- **patch** updates an **existing** exact key only — patch does **not** target tags in this slice;
- every exact target is **scoped after resolution**;
- the map form is the **only accepted public input form** — there is **no migration window** ([EP-0016](../docs/EP/EP-0016-resource-mutation-completion.md) issue 4; pre-alpha, no external consumers). The hand-built scoped-key **tuple** `[scope resource-id params]` remains the documented **internal / storage** representation (the `:rf/scoped-resource-key` shape, [§Resource identity](#resource-identity)) — this is an **input-form vs storage-form** distinction recorded per [EP-0007](../docs/EP/EP-0007-one-name-per-fact.md) rule 3, **not** two public spellings of one fact. The spec, guide, traces, and examples use the map form; the in-repo tuple writers are swept in the implementation slice (not this spec slice).

This rider is smaller than a general cache-operation language: internal normalization into private operation records is allowed, but this slice introduces **no public `{:op …}` cache-operation maps**.

**Settle-time target validation is split — recoverable targets skip-with-warn, corruption-class throws.** The success-time `:populates` / `:patches` / removes run at **settle** (phase 4), *after* the server write has already committed. A bad target there is split by class:

- A **recoverable** target — an **unregistered** resource id, or a non-map / non-keyword-`:resource` target — is **dropped-and-warned, not thrown**: the runtime applies the valid siblings in the same arm, records the dropped target on the instance row's `:patch-summary` `:target-skipped` evidence, and emits a dev-visible `:rf.warning/mutation-target-skipped` warning (DCE'd in production). This mirrors the `:optimistic-tags` warn-and-skip reasoning (above) and the asymmetry the cache already has — a patch on a key with **no entry** no-ops — so one typo'd sibling does **not** strand the whole committed mutation, dropping the good work after an irreversible server write.
- A **cache-identity-corruption** target — a reserved-scope **typo** (a bare `:rf.scope/*` keyword outside the closed enum), or a **non-EDN** scope / params — **still throws** the whole arm. No relaxed policy may swallow a target that would silently write the cache under a **wrong identity**.

This relaxation is **post-write only**. The **pre-write** exact-target callers — the **optimistic** `:optimistic` apply (phase 1.5, before the request is sent) — still **whole-arm-reject** *every* bad target (recoverable or corruption-class): there is no committed write to be inconsistent with, so the stricter fail-closed-before-apply boundary is correct there. A `{:from-db …}` target whose scope resolves nil remains separately **fail-closed-dropped** with `:target-unresolved` evidence under both policies (never an implicit global write).

#### Optimistic mutations

A mutation MAY declare an **optimistic plan** applied to the resource cache *before* the request is sent (**phase 1.5** of the [§Phase order](#phase-order-normative)), so a write's effect shows on the record immediately — the heart flips on click, the count increments, the card disappears — and is deterministically **committed**, **rolled back**, or **reconciled** when the reply settles ([EP-0019](../docs/EP/EP-0019-optimistic-mutation-rollback.md)). This subsection defines the **apply + recording** half (the forward plan, the snapshot inverse, the per-entry revision the conflict check compares against), and the **settle protocol** ([§Optimistic settle](#optimistic-settle--commit--rollback--reconcile) below — commit / rollback / reconcile, the `:on-conflict` conflict rule).

The optimistic plan has two forward forms, the twins of the success-time `:patches` and tag-addressed `:invalidates`:

- **`:optimistic`** — `(fn [params] -> {target patch-fn})`, the **exact-target** twin of `:patches`. Each KEY is the same [map-form exact target](#map-form-exact-resource-targets) `{:resource :params :scope}`; each `patch-fn` is `(fn [old-data] -> new-data)`. There is **no `result`** — the optimistic apply runs *before* the request is sent, so no reply exists yet (this is the one cache-consequence callback that drops `result` from the [canonical signature](#cache-consequence-callback-signatures)). A `nil` patch-fn value is an **optimistic remove** (the entry vanishes immediately, restored on rollback); a patch-fn over an **absent** key is an **optimistic seed** (it creates a `:loaded` entry). Both fall out of the snapshot inverse below at no extra mechanism.
- **`:optimistic-tags`** — `(fn [params] -> [{:scope … :tags #{…} :patch (fn [old-data] new-data)}])`, the **tag-addressed** twin. Each descriptor optimistically patches **every** cached entry carrying its `:tags` in its resolved `:scope` — the cross-view-consistency demand (flip a favorite and have it flip on the detail, every list, and the session feed at once) the author cannot enumerate by exact key. It reuses the **same tag index** the [invalidation engine](#scoped-invalidation-descriptors-per-target) matches against (one tag index, [EP-0014](../docs/EP/EP-0014-derivation-and-process-algebra.md)) and the **same per-target scope descriptor** grammar — `:scope` is `:rf.scope/same` (default), `:rf.scope/global`, a concrete scope, or a `{:from-db …}` reference. There is **no `:cross-scope?` optimistic form** (the optimistic surface is exact-key or tag-within-named-scope only — [§Security](#optimistic-writes-are-fail-closed-and-scope-bounded)).

Each optimistic target's scope is **fail-closed**: a `{:from-db …}` that resolves nil **drops** that target (never an implicit global write), unlike the fail-open execution scope ([§Mutation scope is two distinct scopes](#mutation-scope-is-two-distinct-scopes-hybrid)) — because an optimistic apply **writes the cache**, so it has the same leak boundary a read does ([EP-0019](../docs/EP/EP-0019-optimistic-mutation-rollback.md) Rider 2). A dropped target is recorded as `:target-unresolved` evidence on the instance row.

A **malformed `:optimistic-tags` descriptor** — a non-map entry, a non-collection `:tags`, or a missing `:patch` fn — is **warn-and-skipped, not thrown**: the runtime drops that one descriptor with a dev-visible `:rf.warning/optimistic-tags-descriptor-skipped` warning (DCE'd in production) and the well-formed descriptors in the same plan still apply. This descriptor normalization runs inline at execute time, **before the request lowers**, so a throw here would abort the whole `:rf.mutation/execute` event and the authoritative write would never fire — strictly worse than `:invalidates`, which validates post-write at settle. The optimistic paint is reversible best-effort, so skipping a malformed descriptor corrupts nothing: the authoritative reply still settles the cache via `:populates` / `:invalidates`. This is **only** descriptor-shape recovery; the fail-closed scope boundary above is untouched (a nil-resolving `{:from-db …}` target is still dropped with `:target-unresolved` evidence — never a global write).

**The runtime records the inverse — the author does not.** For each touched key the runtime captures, on the mutation [instance row](#mutations-first-public-beta-gate)'s `:patch-summary` `:rollback` slot:

- `:before` — the **whole entry** as it stood immediately before the forward patch (`:data`, `:status`, freshness timers, tags), by reference (structural sharing — the cache already shares structure on `=`), or the **`:rf.optimistic/absent`** sentinel for a key with no entry (a rollback then removes it). This makes the inverse **truthful by construction**: a rollback restores exactly the entry that existed, never a reconstructed approximation, and an author-written inverse (which drifts from the forward patch) is never required.
- `:revision` — the entry's per-entry [`:revision`](#structural-sharing) **at apply time**. The settle-time conflict check compares this recorded value against the entry's *current* `:revision` to decide whether the recorded inverse is still a truthful "before" (an unmoved revision) or has been overtaken by a concurrent authoritative write (a moved revision). A canonical-identity comparison, never a value diff.

The optimistic apply itself **is an authoritative durable write**: applying the forward patch **bumps** each touched entry's `:revision` (so the recorded `:revision`, observed *before* the bump, lets the settle protocol detect a competing write that landed in between). The whole apply is keyed by an opaque `:snapshot-id` (derived from the instance id + generation — replay-stable), filling the reserved `:patch-summary` `:snapshot-id` slot.

`:optimistic` / `:optimistic-tags` are **incompatible** with [`:invalidate-timing :before-request`](#mutations-first-public-beta-gate): a `:before-request` invalidation stales the touched entries before the request, and an optimistic apply immediately re-populates them — contradictory (stale-then-optimistic-fresh). This is a **loud registration error** (`:rf.error/mutation-optimistic-before-request`), not a silent precedence rule; optimistic writes use the default `:after-success` timing. A per-call **opt-out** `{:optimistic? false}` on `:rf.mutation/execute` forces the pessimistic path for one call (the registration plan is otherwise always-on) — a boolean disable, never a per-call forward plan (call-site cache logic stays off the call site).

The optimistic lifecycle is **trace-visible**: `:rf.mutation/optimistic-applied` (phase 1.5) carries the `:snapshot-id`, the touched `:affected-keys`, the per-key `:revision` + forward op shape (`:patch` / `:seed` / `:remove`), the `:tag-matched-keys`, and the fail-closed `:target-unresolved` ids; the settle ops `:rf.mutation/optimistic-rolled-back` / `:rf.mutation/optimistic-reconciled` ([§Optimistic settle](#optimistic-settle--commit--rollback--reconcile)) are its companions. Every recorded `:before` snapshot and trace value passes through the [EP-0015](../docs/EP/EP-0015-frame-owned-egress-policy.md) egress projection (snapshots are prime `:large?` candidates — a full entry per touched key).

##### Optimistic settle — commit / rollback / reconcile

When an optimistic write's reply settles (**phase 4**), the runtime **deterministically** disposes the recorded apply, keyed on the work-id + generation acceptance verdict and the per-entry `:revision` — both canonical recorded facts, so there is **no wall-clock race** in the decision:

- an accepted **`:ok`** reply **commits** — the authoritative `:populates` / `:patches` / removes overwrite the optimistic value with the server's, then `:invalidates` runs; the recorded inverse is **discarded** (the commit superseded it). The instance row's reserved `:patch-summary` slots fill (`:snapshot-id`, the recorded `:rollback`, the `:committed` optimistic keys the authoritative write owned, and `:reconciliation-refetches` — the optimistic keys this mutation's invalidation marked stale, which the read path refetches; a populated key is exempt per [§Populate is an authoritative load](#populate-is-an-authoritative-load), so it never lands there). `:rf.mutation/optimistic-reconciled` fires.
- an accepted **`:error`** / `:cancelled` reply **rolls back** — for each recorded inverse, the **conflict-aware** rule below; `:rf.mutation/optimistic-rolled-back` fires (per-key restored-vs-conflict disposition + the refetched keys).
- a **stale / superseded** reply rolls back nothing — its inverse is **discarded**, never replayed; the current generation owns the entry (the newer apply already recorded the truthful inverse). This is the existing mandatory stale-suppression boundary.

**The conflict rule (`:on-conflict`).** A rollback first checks the touched entry's **current `:revision`** against the value the apply **left it at** (the apply's own bump is expected — only a write landing *beyond* it is a conflict):

| Condition | Action |
|---|---|
| revision **unmoved** (no competing write since the apply) | **restore** the recorded `:before` entry verbatim (structural-shared; `:rf.optimistic/absent` removes a seeded key) — the truthful, conflict-free rollback |
| revision **moved** (a concurrent mutation / refetch / populate landed, **or an in-flight attempt the apply snapshotted then settled** — a failure / accepted cancellation) | `:on-conflict` governs — `:invalidate` (default): the recorded inverse is a **stale "before"**, so the runtime marks the entry stale (a scoped `:rf.resource/invalidate-tags` of the entry's own tags in its own scope) and lets the read path refetch the authoritative value, **never** restoring a stale inverse; `:force`: restore the (stale) inverse anyway (single-writer last-write-wins), with a `:rf.warning/optimistic-force-clobber` tooling warning |

`:on-conflict` is a registration-level option — `:invalidate` (default, recommended — the read path is the recovery authority on a contested rollback, re-frame2's divergence from TanStack/SWR's unconditional context restore) | `:force` (the single-writer escape). An out-of-enum value is a loud `reg-mutation` error.

**Epoch-restore dangle (Q3).** A `:pending` optimistic write **dangles** to a terminal `:error` on epoch restore (the [§Restore and replay](#restore-and-replay) `:dangling-on-restore` path) — the entry shows the optimistic value with no in-flight write to confirm it, an accepted-error-shaped terminal that triggers the **same conflict-aware rollback**. The rollback runs **inside the restore reconciler's single pure pass**, *not* as a second post-restore dispatched event (which could race a fresh load the restored timeline issues): an unmoved revision restores the recorded `:before` verbatim; a conflict marks the entry **durably stale in place** (`:invalidated-at` stamped from the restore's causal time — the read path refetches on the next live-owner ensure, no dispatch, no race), unless `:on-conflict :force` restores the inverse anyway.

**The `:optimistic?` derived flag (Rider 1).** The instance-keyed [`:rf.mutation/state`](#mutations-first-public-beta-gate) sub gains a derived **`:optimistic?`** boolean — true while a live optimistic apply is showing (between phase 1.5 and settle), false otherwise — so a view can render "pending, but showing my optimistic value." It is **derived, never stored**: `:optimistic?` is true iff the instance is non-terminally `:pending` **and** its `:patch-summary` carries a `:snapshot-id` (an optimistic apply landed and has not yet committed / rolled back). A purely-pessimistic write is `:optimistic? false` throughout (no snapshot id); a committed or rolled-back write is `:optimistic? false` once it settles (no longer `:pending`). The optimistic apply is **not a reply** — it dispatches no [`:reply-to`](#mutation-completion-continuations--call-site-reply-to) continuation; the continuation fires exactly once, after settle, for the accepted terminal reply only.

##### Optimistic writes are fail-closed and scope-bounded

A `:cross-scope?`-style broad optimistic apply is **not** offered — optimistic patching is exact-key or tag-addressed-**within-named-scopes** only, both fail-closed on a nil-resolving `{:from-db …}`. There is no scope-agnostic optimistic write by construction, so the optimistic surface cannot leak a write across users / tenants / SSR requests the way an audited `:cross-scope?` invalidation can.

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
                     {:work/id work-id :resource/key resource-key
                      :scope scope :rf.frame/id frame-id :generation generation}]
        :on-failure [:rf.resource.internal/failed
                     {:work/id work-id :resource/key resource-key
                      :scope scope :rf.frame/id frame-id :generation generation}])]
```

**The transport `:request-id` is the frame-qualified token `[:rf.req frame-id work-id]`, not the bare work-id** (the same shape for the resource and mutation writers). The managed-HTTP in-flight registry keys by `:request-id` process-globally and supersedes/aborts by **equal** request-id ([Spec 014](014-HTTPRequests.md) §`:request-id`), and the work-id is frame-local, so the bare work-id would collide across frames (frame B superseding frame A's in-flight request for the same scoped key + generation). The qualified token isolates frames; the matching opportunistic abort (`:rf.http/managed-abort`) carries the **same** token. This is the [second identity](#ledger-row-retention-and-identity) — it governs only transport-level in-flight correlation, while intra-frame stale suppression continues to key on `:work/id` + `:generation`. The reply payloads carry the qualified `:work/id` (the durable identity the receiving frame verifies against its entry/instance — one attempt one work id one name, [EP-0007](../docs/EP/EP-0007-one-name-per-fact.md)), independent of the transport correlation token.

The internal reply payloads stamp the intended frame with the qualified `:rf.frame/id` key — the canonical carried frame stamp for new framework causal tokens ([EP-0002](../docs/EP/EP-0002-frame-target-resolution.md) R3, "one canonical frame stamp") — matching the qualified `:work/frame` stamp on the ledger record. The bare `:frame` opt remains the public dispatch/subscribe target opt, unchanged. The managed-HTTP reply dispatch is already frame-targeted by Spec 014; the resource metadata still carries the intended frame id for assertion, stale-suppression diagnostics, and trace rows. **Success and failure events MUST verify frame, work id, and generation before writing.** Cancellation is an optimization; stale suppression is the correctness boundary.

The runtime owns reply addressing and request correlation; an app `:request` that bypasses stale-suppression by supplying `:request-id` / `:on-success` / `:on-failure` is rejected. Generic transport extension is desirable but is a later extension protocol after the HTTP built-in proves the resource semantics.

### The `ctx` argument is reserved across resource/mutation fn surfaces

Every author-supplied function this artefact calls receives a trailing `ctx` argument, and the contract for it is **uniform**: `ctx` is **reserved and currently `nil`** — declared so the surface is forward-compatible, but **not to be relied on** in this slice. A function MUST derive its result from its own declared/positional inputs, never from `ctx`:

| Fn surface | Signature | `ctx` today |
|---|---|---|
| resource `:request` | `(fn [params ctx] → managed-http-args)` | **literal nil** (`(req-fn cparams nil)`) — derive the request from `params` |
| resource `:scope` resolver (spec-side) | `(fn [params ctx] → scope)` | reserved nil |
| `reg-resource-scope` `:resolve` | `(fn [inputs ctx] → scope-or-nil)` | reserved nil — derive scope from declared `:inputs` |
| `:invalidates` / `:populates` / `:patches` | `(fn [params result])` | **no `ctx` arg** — the canonical signature is `(params result)`; db-derived scope is reached only via `{:from-db …}` references (see [§Cache-consequence callback signatures](#cache-consequence-callback-signatures)) |

The route-resource `:params` / `:scope` / `:when` functions are the one site that carries a **populated** context — `(fn [route ctx] …)` / `(fn [route _ctx] …)` — because route-entry planning has a real route match and planning context to thread (see [§Route integration](#route-integration) / [012 §Per-route data loading](012-Routing.md#per-route-data-loading)). Everywhere else the trailing `ctx` is the reserved-nil slot above: the resource `:request` is invoked with a literal-nil `ctx`, which is formally **reserved**.

### Request decoration belongs to the managed-HTTP seam, not the resource declaration

Resources and mutations lower through [Spec 014](014-HTTPRequests.md) managed HTTP, so **cross-cutting request decoration** — auth headers, tracing headers, API base URLs, tenant headers, and default retry policy — belongs in the **managed-HTTP interceptor/defaults seam** ([§Middleware](014-HTTPRequests.md#middleware)), not copied into every resource/mutation `:request` ([EP-0016](../docs/EP/EP-0016-resource-mutation-completion.md) Rider 3). The doctrine (MUST):

- a resource/mutation `:request` function describes the **domain request** only (method, url, params, body, `:decode`);
- auth headers, tracing headers, API base URLs, tenant headers, and retry **defaults** are **frame/application managed-HTTP policy**, applied by a frame-registered `reg-http-interceptor` that decorates *every* `:rf.http/managed` request the frame issues;
- the interceptor reads frame state through `(rf/app-db-value (:frame ctx))` (the EP-0002 carried-frame-correct read), **not** an ambient `db`, and returns `ctx` unchanged when the decoration does not apply (e.g. no token present);
- **default retry policy is read-focused**; **mutation retry defaults MUST be conservative** — retrying a write can duplicate side effects, so write retries stay opt-in (a mutation arms `:retry` only when its own `:request` declares it, per [§Mutations](#mutations-first-public-beta-gate));
- resource/mutation traces make applied decoration **visible without leaking sensitive header values** — a trace reports that an auth interceptor applied, never the bearer token itself.

```clojure
(rf/reg-http-interceptor :realworld/auth
  {:before (fn [ctx]
             (let [token (some-> (rf/app-db-value (:frame ctx)) :auth :token)]
               (cond-> ctx
                 token (assoc-in [:request :headers "Authorization"]
                                 (str "Token " token)))))})
```

Registered once per frame, it decorates resource reads, mutations, and plain managed calls alike — a resource that needs auth needs **no** per-resource opt-in. The interceptor registration names are illustrative; the **normative rule is ownership**: transport decoration belongs to managed-HTTP policy and is reused by resources/mutations. Per-resource interceptor *selection* is out of scope for this slice (it would be a new, separately-specified feature, not a per-resource `:transport` key).

## The uniform reply envelope and the canonical reply map

Resources and mutations are managed *async* surfaces, so their completions report back through the framework-wide **uniform reply envelope** — the property-9 contract whose canonical normative home is [Managed-Effects §The uniform reply envelope](Managed-Effects.md#the-uniform-reply-envelope) ([EP-0011](../docs/EP/EP-0011-uniform-async-reply-envelope.md) is the rationale record). Because the read/write transport is managed HTTP (Spec 014, itself lowered onto the same envelope), the transport delivers its **canonical reply envelope** (`{:status :ok :value …}` / `{:status :error :error …}`, rf2-ibksxg — there is no longer a `{:kind :success/:failure}` reshape) appended to the runtime-owned internal reply event; the resource family **re-lifts** that (reading `:value` on success, `:error` on failure), plus the verification payload, into the **one canonical reply map** stamped with the resource-family `:work/id` / `:work/kind` / `:correlation`. The internal reply targets are framework-internal (`:rf.resource.internal/*` / `:rf.mutation.internal/*`), so they receive the canonical reply map — `:on-success` / `:on-failure` are `:rf.http/managed`'s own routing sugar, not a resource/mutation reshape.

The canonical reply map a resource/mutation reply handler receives:

```clojure
{:status       :ok | :error | :cancelled | :stale
 :value        decoded-result            ; :ok only
 :error        <:rf.http/* envelope>     ; :error / :cancelled
 :work/id      [:rf.work/resource scoped-key generation]
               ; or [:rf.work/resource [:rf.mutation instance-id] generation]
 :work/kind    :resource | :mutation
 :work/status  :completed | :failed | :cancelled | :suppressed
 :rf.frame/id  frame-id
 :completed-at <causal epoch-ms>
 :correlation  {:scope … :generation … :resource/key …}        ; resource
               ; {:mutation/id … :instance/id … :scope … :generation …} ; mutation
 :stale?       <bool>            ; :stale only
 :stale/reason <keyword>}        ; :stale only
```

Three load-bearing rules follow from the envelope contract:

- **One closed `:status`.** A resource/mutation completion is exactly one of `:ok` / `:error` / `:cancelled` / `:stale` ([Managed-Effects §Status taxonomy](Managed-Effects.md#status-taxonomy)). An `:rf.http/aborted` envelope lowers to `:status :cancelled` (an intentional cancellation is **not** a user-visible `:error`); a superseded/vanished completion lowers to `:status :stale` / `:work/status :suppressed` and **never** dispatches the app target.
- **One work id, one spelling.** Ledger-backed completions correlate by `:work/id`; the resource head is `[:rf.work/resource scoped-key generation]` and the mutation head reuses it with a mutation-instance key `[:rf.work/resource [:rf.mutation instance-id] generation]`, the ledger row distinguishing the writer via `:work/kind :mutation`. The verification payload's work identity is the qualified `:work/id`, not the unqualified `:work-id` — one attempt, one work id, one name ([EP-0007](../docs/EP/EP-0007-one-name-per-fact.md)). Scope, generation, and the resource/mutation keys ride as `:correlation` metadata, never a second stale-suppression key.

- **The decoded result is `:value` on the reply; `:data` / `:result` on the durable layer.** The decoded result on the *reply map* is `:value` for every managed-async family — there is no per-family synonym ([EP-0007](../docs/EP/EP-0007-one-name-per-fact.md) / [Managed-Effects §The reply map](Managed-Effects.md#the-reply-map)). The durable **resource entry** stores that same result under `:data`, and the durable **mutation instance** stores it under `:result`, as distinct facts: the entry / instance row is a *queryable durable status record* — a different **layer** from the transient causal reply — so the two spellings name two facts living in two layers. The success reply handler reads `(:value reply)` and installs it under the layer-appropriate durable key (`:data` for an entry, `:result` for an instance). The reply-map spelling is `:value`; the instance sub keeps `:result` because it answers "what is the durable state of this write?", not "what did the continuation carry?". (Coordinates with [EP-0016](../docs/EP/EP-0016-resource-mutation-completion.md)'s call-site mutation `:reply-to`, which is the concrete forward slice of this same envelope.)

Stale suppression is the **correctness boundary** (cancellation is only an optimization): before any durable write the reply handler verifies the carried `:work/id` + generation still match the live entry/instance `:current-work`. On mismatch the app target MUST NOT run, the ledger row reaches `:suppressed`, a stale-suppression trace row carries the carried-vs-current correlation, and no user-visible app-db / runtime-db mutation is produced beyond framework-owned ledger/trace bookkeeping ([Managed-Effects §Stale suppression](Managed-Effects.md#stale-suppression)).

## Race and in-flight semantics

These cases are normative:

- **`ensure` while the same scoped key is already in flight** joins the existing current work record, attaches any supplied owner to both the resource entry and ledger row, records the new cause, and emits a dedupe trace.
- **`refetch` may force a new generation.** If a prior request is still in flight, mark the old work record superseded, abort it when possible, and otherwise suppress its late reply by work id and generation.
- **Invalidation while a request is in flight** marks the entry stale and records the invalidation. If the in-flight request is for the current generation, its success may satisfy the invalidation only when policy says the request covered the invalidated identity; otherwise schedule a follow-up refetch.
- **Owner release while a request is in flight** aborts only when no remaining owner needs that work record. Shared requests MUST NOT be cancelled just because one route, machine, or lease went away.
- **Route supersession uses both nav-token owner release and generation checks.** The old nav-token MUST NOT write into the new route's resource state.
- **Stale/GC timers are advisory.** A timer handler MUST re-read the current entry, scope, owners, and generation before writing — a newer event may already have refreshed, invalidated, removed, or re-owned the entry.

## Stale and GC scheduling

`:stale-after-ms` and `:gc-after-ms` are v1 features, so their scheduling is part of v1.

### Freshness clock contract

Freshness is decided against **durable timestamp facts**, never against an ambient clock the runtime happens to read at decision time. The contract (MUST):

- **`:stale-at` derivation.** On every load / refresh settle, `:loaded-at` is stamped from the reply's **`:completed-at`** — the causal completion time carried on the canonical reply, sourced from the settling event's framework-stamped `:rf/time-ms` ([EP-0010](../docs/EP/EP-0010-causal-world-inputs.md) causal time), **read once** at the transport-finalisation boundary and threaded as data, not re-read here. `:stale-at` is then `:loaded-at + :stale-after-ms`. A mutation-success settle (patch / populate) ages the entry the same way through the same derivation, so a patched entry is exactly as fresh as a fetched one.
- **Absent `:stale-after-ms` ⇒ `:stale-at nil` ⇒ never time-stale.** A resource that declares no `:stale-after-ms` never ages out on a timer; its only path to `:stale?` is an explicit `:invalidated-at` (invalidation / mutation). **This diverges deliberately from TanStack Query / SWR**, whose `staleTime` defaults to `0` (stale-immediately, refetch on every re-mount / focus): re-frame2 makes refetch cadence an **explicit author decision** (`:stale-after-ms`, `:poll-interval-ms`, or invalidation) with no implicit `0`. There is no "refetch on every route re-entry unless you opt out"; there is "declare when you want to age."
- **The staleness predicate.** An entry is stale iff `:invalidated-at` is set **OR** (`:stale-at` is set **AND** `now ≥ :stale-at`). The single shared derivation (`state/entry-stale?`) is what every reader consults, so freshness never drifts between the fresh-skip gate, the SSR projection, the stale-timer re-check, and the `:stale?` sub.
- **Each consumer's `now` is pinned.** **Causal** consumers — the fresh-skip / ensure gate, the focus/reconnect active-stale scan, the GC/stale timer re-check, and route ensure — read `now` from their handler's causal **`:rf/time-ms`** (declared via `:rf.cofx/requires [:rf/time-ms]`, consumed flat), so the durable freshness decision is **replay-stable**. The public **`:rf.resource/stale?` subscription** is the one exception: it compares `:stale-at` against the live **wall-clock** (`re-frame.interop/epoch-now-ms` — `Date.now` / `System.currentTimeMillis`, the same clock the absolute `:stale-at`/`:loaded-at` facts are stamped in; NOT the origin-relative perf clock). A sub is a passive display read, not a durable causal write — it may read the wall clock. Two consequences follow and are **by design**: (1) the sub re-runs on **frame commits**, not on wall-clock boundaries, so a stale boundary that passes with no intervening commit does **not** by itself re-render a stale badge — a view that needs a live badge arms `:poll-interval-ms` or an app timer; (2) the sub's value is not epoch-replay-deterministic (it reads real time), which is acceptable precisely because it drives no durable state — every durable freshness decision routes through the causal `:rf/time-ms` path above.
- **Hydration and clock skew.** `:loaded-at` / `:stale-at` / `:invalidated-at` serialize as **absolute** timestamps; on hydration the client installs them as-is and treats server-stamped freshness at face value (fresh entries are not immediately refetched; stale entries render then refetch by policy). Server↔client clock skew is therefore observable — a skewed fleet can hydrate entries that read stale-early or fresh-late — and is surfaced in trace / hydration diagnostics when it makes freshness ambiguous. **Whether hydration should rebase server absolutes to elapsed-time semantics** (shift by the `:rf/ssr-rendered-at` offset so freshness becomes skew-immune) is a genuine design choice, **deferred to the retention-default decision bead**, not decided here; the shipped contract is trust-absolutes.

Scheduling rules (MUST):

- freshness is computed from **durable timestamps** (`:loaded-at`, `:stale-at`) per the [§Freshness clock contract](#freshness-clock-contract) above, not from trusting that a timer fired exactly on time;
- a stale timer may enqueue a resource event, but the handler MUST re-check the current entry before writing;
- inactive GC may use host timers, but GC MUST re-check owner sets and entry generation after wake;
- timers and host handles live in side tables, not in frame-state;
- **frame destroy cancels all resource timers for that frame**;
- a hidden tab can delay timers without corrupting correctness; on focus or reconnect, the active-stale revalidation scan (the `:rf.resource/window-focused` / `:rf.resource/network-reconnected` events) scans the frame's active-owner stale entries and refetches them by event (cause `:focus` / `:reconnect`, never an owner — it creates no liveness; generation + stale-suppression protect late replies). The host `window` focus / online listeners are installed per-frame by `install-revalidation-listeners!` and cancelled on frame destroy via the single `:resources/on-frame-destroyed!` hook (composed with the work-ledger / timer / generation host-cache release — one teardown path).

## Polling

Interval polling is the third member of the cache-freshness family, beside `:stale-after-ms` and the focus/reconnect active-stale scan (EP-0020). A resource may declare an optional **`:poll-interval-ms`** policy; while an entry has at least one **active owner** and the document is **visible**, the runtime re-runs the entry's load on that interval — without any component-side fetch call. This is the re-frame2 counterpart of TanStack Query's `refetchInterval`, SWR's `refreshInterval`, and RTK Query's `pollingInterval`, with one divergence: polling is **owner-driven, not component-observer-driven** (a view is a passive read — see [§Active owners and causes](#active-owners-and-causes); the active-owner lease already is the framework's "is this entry live and worth keeping fresh" concept, and owners answer "should polling continue?"). Polling reuses the landed substrate wholesale — the advisory host-timer side-table (a new `:poll` kind beside `:stale` / `:gc`) and the `:rf.resource/refetch` causal path — so it adds no new race surface, transport, or work-ledger writer.

`:poll-interval-ms` is a positive integer of milliseconds. A non-positive or absent value means **no polling** (the same disarm rule a non-positive stale/GC delay already follows). Rules (MUST):

- **Owner-driven.** A `:poll` timer is armed for `:poll-interval-ms` after each load settle **only while the entry has at least one active owner**. Polling creates **no liveness** and extends **no GC** — a poll tick carries cause `:poll`, **never an owner**. The instant the last owner releases, polling **stops** (the release path cancels the entry's `:poll` timer; an owner-free entry never arms / never keeps a poll). A poll never pins an owner-free entry alive.
- **Advisory re-check on fire.** The fired `:poll` timer dispatches a re-checking internal event (`:rf.resource.internal/poll-fired`) — identical advisory discipline to `:stale` / `:gc`. The handler re-checks the live durable entry before acting and **re-arms** the next interval (cancel-then-arm) whenever polling should continue.
- **Unconditional active-owner tick.** A poll tick refetches **by the interval**, NOT gated on `:stale?` — the consumer who declared a poll interval asked for "re-read every N ms" (the prior-art tools are all effectively interval-as-cadence). `:stale-after-ms` remains the orthogonal knob ("don't refetch on focus/route-entry unless older than X"); the two are independent. The [structural-sharing rule](#structural-sharing) means an unchanged poll response preserves the old `:data` value, so views stay quiet when nothing changed.
- **Default-pause-when-hidden.** A poll tick is **suppressed while the document is hidden** (`document.visibilityState != "visible"`) and resumes on tab return (which also fires the focus revalidation scan). The host visibility signal is read at the host boundary (the timer thunk) and carried on the re-check event, so the durable decision is replay-stable. This matches the SWR / RTK / TanStack `refetchIntervalInBackground:false` defaults; a true background-monitor opt-in (`:poll-when-hidden?`) is **reserved** for the first consumer that needs it.
- **Coalescing — no overlap on a slow endpoint.** A poll tick that finds a **live in-flight refetch** (`:current-work` pointing at a non-terminal, non-`:abort-requested` record — the same in-flight gate the focus/reconnect scan uses) **skips** the refetch (no second generation, no overlap) and re-arms; the interval effectively backs off to the response time. A tab return that fires both the focus scan and a poll tick double-fetches nothing — whichever starts work first sets `:current-work`, the other becomes a no-op.
- **Stale-suppression respected.** A poll tick that starts work goes through the ordinary refetch path (force-new generation, work-ledger record, managed-HTTP lowering); a poll reply that lands after the entry was superseded (a manual refetch, an invalidation, a clear-scope) is suppressed by the single stale-suppression boundary, never written. An invalidation / mutation refetch of an actively-owned entry resets its load timestamp, which **reschedules** the poll (cancel-then-arm) rather than stacking.
- **Background-refresh failure keeps polling.** A failed poll tick is a background refresh failure — the entry stays `:loaded`, keeps prior `:data`, records `:refresh-error` — and the **next poll still fires** (a transient failure never permanently stops a monitor). Repeated-failure back-off is the transport adapter's concern, not the poll timer's.
- **SSR / restore-safe.** A `:poll` timer is a host-transient advisory handle (never on the SSR / hydration / epoch wire), armed lazily client-side, skipped under SSR via the carried `:server?` flag, and cancelled on frame destroy / entry removal / last-owner release via the single `:resources/on-frame-destroyed!` teardown hook + the `:rf.resource/cancel-timers` / `:rf.resource/cancel-poll-timers` fx.

A poll-enabled resource that polls but has no natural route / machine owner needs *some* owner to keep the poll alive (the existing `[:lease …]` app-minted owner with a matching `:rf.resource/release-owner`). An adapter-level mount-lifecycle lease helper is the recommended ergonomic for a "just polling" view; it is an adapter concern, not a runtime-contract one (the only place the component-observer model legitimately re-enters). Per-use route/ensure interval override and data-derived dynamic intervals are reserved later slices.

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
     :scope     {:from-db :realworld/session}
     :blocking? true}

    {:resource  :comments/list
     :params    (fn [route] {:slug (get-in route [:params :slug])})
     :when      (fn [route _ctx] (some? (get-in route [:params :slug])))
     :blocking? false
     :keep-previous? true}]})
```

The route entry's `:scope` is a named-resolver reference `{:from-db :realworld/session}` — the **one** scope-resolution currency ([§Named resource-scope resolvers](#named-resource-scope-resolvers-reg-resource-scope)), resolved against the route-entry app-db coeffect at use time. It is **not** an anonymous `(fn [_route ctx] …)` that reads viewer identity out of the planning `ctx`: the route-planning `ctx` is the reserved trailing context (currently the empty map, [§The `ctx` argument is reserved across resource/mutation fn surfaces](#the-ctx-argument-is-reserved-across-resourcemutation-fn-surfaces)), so reading session scope from it would hide the dependency, defeat tooling naming, and resolve nil (fail-closed). The named resolver is the recommended form everywhere viewer identity decides resource identity; the `(fn [route ctx] …)` resolver tier exists for route facts available on the `route` argument (e.g. a path-segment param), not for db-derived viewer scope.

[012-Routing](012-Routing.md) currently rejects unknown bare route-metadata keys at registration. The resources artefact MUST therefore extend the routing accepted-key set, via a late-bound framework extension, so `:resources` is treated like the existing cross-feature `:head` key. Without that integration, a route containing `:resources` is correctly rejected by the routing artefact.

**On route entry:** routing resolves the route and nav-token; `:when` predicates are evaluated; scopes and params are computed and validated; each resource is marked active with owner `[:route route-id nav-token]`; each is ensured with cause `[:route-entry route-id nav-token]`; blocking resources are tracked under the nav-token; non-blocking resources fetch in the background; failures in blocking resources update route transition/error state; Xray can display the route/resource graph without parsing handlers.

**On route leave or superseded navigation:** route-owned resources are released by owner token; in-flight work is aborted only when no remaining owner still needs it; stale replies are suppressed by generation/nav-token even when abort is unavailable; inactive resources become eligible for `:gc-after-ms` cleanup.

`blocking?` is defined precisely: it keeps the route transition in a loading/pending state; it gives SSR a wait point before render; it does **not** have to block URL commit or prevent a client skeleton from rendering; if hydrated data is already fresh, it does not block. Existing `:on-match` remains canonical for arbitrary route-entry work — `:resources` is declarative server-state metadata layered beside it, not a second router.

Route resources MUST define **params-failure behaviour explicitly**: a failed params schema is a route/resource planning error visible in route state and Xray, not a silent cache miss. Conditional resources use `:when` rather than sentinel `nil` params. **Dependent** route resources are modeled as a route plan, not a hidden view effect: a route resource may declare a local `:id`, and another may declare `:after #{local-id}` to order their ensure-dispatch. **`:after` is dispatch-order only, not a data-waterfall** (landed semantics): the route plan is a *pure synchronous planner* that resolves every entry's params and scope at route entry — before any resource can settle — so a later entry's params CANNOT depend on an earlier entry's loaded **data**; that would require re-running the plan after each settle, a deferred slice. What `:after` DOES guarantee is **ensure-dispatch order**: a dependent entry's `:rf.resource/ensure` is dispatched after every entry it names, so the dependency's fetch is kicked off first (the params still come from the *route*, not the dependency's data). `:after` MUST target route-local `:id`s (the same resource can appear more than once with different params), and ordering is **fail-closed**: an `:after` target naming an undeclared local id, or an `:after` cycle, is a route/resource planning error (`:recovery :fix-after`, surfaced on the route slice + Xray), never a silent fall-back to declaration order. Xray reads the declared `:after` edges to show the dependency graph. (A true data-waterfall — an entry's params computed from another's loaded data — is a deferred slice, [§Deferred slices](#deferred-slices).)

Routes are **not required** — an app can use resources entirely from events and machines (with explicit owners and a matching release path); it then gets canonical identity, stale/fresh policy, dedupe, invalidation, GC, passive subscriptions, and Xray visibility, but not route ownership, route-leave release, route transition blocking, or SSR route preload.

### Paginated and previous data

Paginated tables, filtered lists, search results, and cursor feeds are ordinary resources in v1 (they do not wait for "infinite resources"). The pattern: include every filter, sort, page, cursor, and server-visible option in params; tag both the list identity and any returned item identities; keep old data visible while a new page/filter resource is first-loading when the route/resource declaration opts into `:keep-previous?`. The public `:rf.resource/state` projection makes the distinction explicit:

```clojure
{:status :loading
 :data nil
 :previous? true
 :previous-key [scope :articles/list {:page 1 :filter "recent"}]
 :previous-data [{:id 1 :title "Old page"}]}
```

`previous-data` is a projection from the prior key; it is **not** inserted into the new cache entry and MUST NOT provide tags for the new key. The new entry becomes ordinary `:loaded` data only after its own request succeeds. Cache growth for list params is controlled by the same owner and GC rules; `:keep-previous?` MUST NOT pin old pages beyond their owners.

#### `:keep-previous?` lifecycle

`:keep-previous?` is a **property of the ensure**, not of the registration — it is not a `reg-resource` key. A route resource sets it from its declaration (threaded onto the ensure by the route planner); an event-driven ensure may pass `:keep-previous? true` on the `:rf.resource/ensure` payload. Its full lifecycle (MUST):

- **When the pointer is set.** On an ensure whose entry is a **genuine first load** (no usable `:data` of its own yet) under `:keep-previous? true`, the runtime records a `:previous-key` projection **pointer** on the new entry. An ensure of an entry that already has data (a refresh) records **no** pointer — a refresh keeps showing its own data, so there is nothing to bridge.
- **Which key is pointed at.** The previous key is the **prior loaded sibling**: among the cache's entries, the one sharing the new key's `[scope resource-id]` but with **different params** and currently-usable `:data`, picking the **most recently loaded** (`:loaded-at`). This is recency **inference over siblings of the same resource id**, not a same-ensure-site record. Two consequences: (1) a resource used at only one site behaves as expected ("the page I was just looking at"); (2) because the link is by resource-id + recency, an app that runs several *unrelated* instances of the same resource id could in principle bridge from a non-adjacent sibling — apps that need strict linkage should scope or partition params so siblings are unambiguous. *(A caller-supplied explicit `:previous {:params …}` link is a reserved refinement, [§Deferred slices](#deferred-slices); v1 ships the sibling-recency inference.)*
- **The projection is live and best-effort.** `:previous-data` is read from the previous entry **at subscription time**, never copied into the new entry and never contributing tags. If that previous entry is **GC'd** while still displayed (its owners released — `:keep-previous?` pins nothing, by the rule above), the projection reads `nil`: the pointer **never keeps an old page alive**, and the honest price is a possible flicker to empty rather than a leak. `:previous?` stays `true` while the pointer rides the entry; `:previous-data` may still go `nil` under GC.
- **When the pointer clears.** On the new key's **first successful settle** the entry gains its own `:data` and the `:previous-key` pointer is dropped (the projection collapses to `{:previous? false}`) — the view swaps atomically from old data to new. **On a first-load *failure*** the entry settles `:status :error` with no data; in the shipped v1 runtime the `:previous-key` pointer is **not** cleared on that path, so a `:keep-previous?` error entry can still project a neighbour's `:previous-data` alongside its `:error`. See the note below.
- **Pointers move, never chain.** Each new entry's `:previous-key` points at its immediate prior sibling by recency; it does not transitively chain through a sibling that itself never loaded. K3's previous is the most-recently-loaded sibling (K2 if K2 loaded), read live — the runtime holds one pointer per entry, not a history list.

> **Open point (surfaced to the mayor, rf2-40bnmo).** The first-load-**failure** clearing behaviour above is a spec-vs-impl gap, not merely under-documented: the shipped `entry-failed` transition clears `:previous-key` on a background-refresh failure path (via the success path) but **retains** it on a first-load failure, and no test pins the error case. Whether an errored new key should show its own error only (clear the pointer — the TanStack-parity reading) or is permitted to keep flashing a neighbour's data is a **design decision** for the retention-default ruling, deferred here rather than papered over. The text above pins the shipped behaviour; the decision bead owns the resolution.

## Infinite resources and load-more feeds

> **Scope.** This section is the normative home of the `:infinite` registration kind ([EP-0021](../docs/EP/EP-0021-infinite-resources.md), accepted 2026-06-17; the Resolved Decisions R1–R8 are the binding rulings). Numbered/keyed pagination ([§Paginated and previous data](#paginated-and-previous-data)) is untouched and orthogonal: that model keeps each page an **independent** entry (the page is in `:params`, the identity is the page), addressable as "go to page N". An **infinite** resource is the complementary **load-more / infinite-scroll feed** — the user accumulates pages (page 1, then 1+2, then 1+2+3), rendered as one growing list, with the *next* page param **derived from the last page's data**. Both are legitimate; an app picks per feed.

An **infinite resource** is a resource registered with `:infinite true`. It reuses every existing resource contract — identity, fail-closed scope, the five-state FSM, the work ledger, owners/causes, stale/GC policy, SSR, restore, egress projection — and adds exactly one new fact: an ordered, growing **sequence of pages** held as the one feed entry's durable value, accumulated by repeated `:rf.resource/load-more` events.

### Registration — `:infinite`

```clojure
(rf/reg-resource
  :feed/timeline
  {:doc "Infinite home timeline (load-more)."

   :infinite true

   ;; The feed-IDENTITY params (filter / sort / search) — what makes two feeds
   ;; distinct cache instances. The per-page cursor is NOT here (it is the
   ;; resolved page-param the runtime threads through :request's reserved ctx).
   :params-schema    [:map [:filter :keyword]]
   :scope            {:from-db :app/session}     ;; a named scope resolver, like any resource
   :page-data-schema :app/timeline-page          ;; validates ONE page (the decode target + egress contract)

   ;; :request keeps its settled (params ctx) shape; the RESERVED ctx now
   ;; carries the resolved page context for THIS page (nil/empty for a
   ;; non-infinite resource). NO new arity.
   :request
   (fn [{:keys [filter]} {:rf.resource/keys [page-param page-index]}]
     {:request {:method :get
                :url    "/api/timeline"
                :params (cond-> {:filter filter :limit 20}
                          page-param (assoc :cursor page-param))}
      :decode  :app/timeline-page})

   ;; REQUIRED for :infinite. Derive the NEXT page param from the last loaded
   ;; page + all pages so far. Returns nil to signal "no more pages" (the single
   ;; terminal). Pure. TanStack getNextPageParam(lastPage, allPages) analogue.
   :next-page-param
   (fn [last-page _all-pages]
     (get-in last-page [:page-info :next-cursor]))   ;; nil => end of feed

   ;; OPTIONAL — the bidirectional mirror. Defined now (R7), but the prepend
   ;; event (:rf.resource/load-prev) is DEFERRED — see below.
   :prev-page-param
   (fn [first-page _all-pages]
     (get-in first-page [:page-info :prev-cursor]))

   ;; REQUIRED when a page is NOT already a vector — the merge accessor (R3).
   :page->items      :items                       ;; or (fn [page] …) — loud over guessing

   ;; OPTIONAL — the refetch policy (R6). Default is window-preserving.
   :refetch          {:refetch-all-pages? false}

   :tags             (fn [{:keys [filter]} _data] #{[:feed filter]})
   :stale-after-ms   60000
   :gc-after-ms      300000})
```

Registration rules (MUST):

- **`:infinite true` makes `:next-page-param` REQUIRED.** A `reg-resource` with `:infinite true` and no `:next-page-param` is a loud registration error (`:rf.error/infinite-missing-next-page-param`), in the same registration gate as `:scope` / `:params-schema` / `:request`.
- **`:next-page-param` is pure** `(last-page all-pages) -> next-param-or-nil`. Returning **`nil` is the single canonical terminal** ("no more pages"); the derived `:has-next-page?` is `(some? next-page-param)`. (Both gold standards diverge here — TanStack returns `undefined`, SWR ends on an empty page; re-frame2 standardises on `nil` and additionally exposes `:has-next-page?` so a view never re-derives the terminal.)
- **The `:request` reserved ctx is the page extension point (R8).** `:request` keeps its settled `(params ctx)` shape; for an infinite resource the **reserved `ctx`** is `{:rf.resource/page-param p :rf.resource/page-index i}`. A **non-infinite** resource's `:request` still receives a `nil`/empty ctx unchanged. **No new 3-arity is introduced** — the page context rides the already-reserved context slot ([§The `ctx` argument is reserved across resource/mutation fn surfaces](#the-ctx-argument-is-reserved-across-resourcemutation-fn-surfaces)).
- **The first page is fetched with `:page-param nil`** (the TanStack `initialPageParam` analogue; the framework default is `nil`, overridable via an optional `:initial-page-param`) and `:page-index 0`.
- **The page param is internal sequencing state, NOT part of the feed's cache identity.** Two `load-more` calls on the same feed do **not** produce two cache keys — they extend one entry. Only the **identity params** (`:params`, schema-validated, canonical: filter / sort / search) name the feed; changing them yields a **different scoped resource key** → a different feed instance (the old accumulation is a separate, GC-eligible entry; the new feed first-loads page 0). This is the divergence from numbered pagination, where the page *is* in params and *is* the identity. ([EP-0012](../docs/EP/EP-0012-path-optics-and-canonical-forms.md): the feed identity params are canonical-EDN identities; the per-page param is not part of the key.)
- **`:page-data-schema` validates ONE page (R5)** — the per-page decode target **and** the per-page egress/classification contract. SSR / tool / trace projection apply it **per page** so sensitive page fields are classified ([EP-0015](../docs/EP/EP-0015-frame-owned-egress-policy.md)); the accumulated `:data` is a framework-owned vector of pages and **must not** bypass per-page classification. **`:data-schema` is not used** for the accumulated vector.
- **`:page->items` is REQUIRED for any feed whose page is non-vector / enveloped (R3)** — see [§Subscription contract](#subscription-contract-the-merged-list-and-page-metadata-r3) below.
- **`:tags` tag the feed identity** and SHOULD also be derivable per item so item-level invalidation can reach the feed (see [§Refetch and invalidation of an infinite feed](#refetch-and-invalidation-of-an-infinite-feed)).

The `:infinite`-only optional keys (`:next-page-param` REQUIRED, `:prev-page-param`, `:page->items`, `:initial-page-param`, `:page-data-schema`, `:refetch`) join the optional-v1-key set; the closed registration args-map is `:rf/infinite-resource-args` ([Spec-Schemas §`:rf/infinite-resource-args`](Spec-Schemas.md#rfinfinite-resource-args-the-infinite-resource-registration-args-map-spec-016--ep-0021)).

### Durable cache shape (R1)

**One scoped resource entry per feed**, pages stored as an **ordered vector** inside it, in the existing `:rf.runtime/resources` partition — **not** N per-page entries and **not** an app-db slice. The feed reuses the single-resource entry shape and refines `:data` to be the page sequence, plus a small set of infinite-only facts:

```clojure
;; A durable infinite-feed entry (a refinement of the §Status semantics entry).
{:resource/id     :feed/timeline
 :resource/key    [scope :feed/timeline {:filter :recent}]
 :infinite?       true
 :status          :loaded            ;; the existing FSM, unchanged semantics
 ;; :data is the ORDERED PAGE SEQUENCE — the durable fact. One element per
 ;; accumulated page, in load order. The merged list is DERIVED in the subs
 ;; layer (never stored — derived values are not durable facts).
 :data            [<page-0-decoded> <page-1-decoded> …]
 :page-params     [nil <param-1> <param-2> …]   ;; one per page (page-0 = nil)
 :next-page-param <param-or-nil>                ;; recomputed after each load; nil = terminal
 :prev-page-param <param-or-nil>                ;; bidirectional only (derivation defined; load-prev deferred)
 :error           nil                ;; first-load (page 0) failure envelope
 :refresh-error   nil                ;; whole-feed background refresh failure (data kept)
 :page-error      nil                ;; a LOAD-MORE (page N>0) failure — see below
 :loaded-at <ms> :stale-at <ms> :invalidated-at nil
 :attempt 1 :generation 4 :revision 5 :request-id … :current-work …
 :tags #{[:feed :recent]} :active-owners #{[:route :route/home nav-token]}}
```

The whole feed has **one** owner set, one `:loaded-at` / `:stale-at`, one GC clock, one SSR-restore unit, and one Xray row. Structural sharing ([§Structural sharing](#structural-sharing)) keeps unchanged pages identical across a load-more (only the appended page is new). The page param is stored on the entry as `:page-params` (one per accumulated page, `page-0 = nil`) and is **never** part of the cache key.

This matches both references' own internal model (TanStack's `{pages, pageParams}` is one cache entry; SWR's collection is one infinite hook instance). The cost it accepts — a feed entry's `:data` grows, so GC and egress projection reason about a growing durable value, and item-level invalidation reaches inside the vector — is the trade-off this model accepts.

### Causal event — `:rf.resource/load-more` (R2)

A new resource event extends the feed by one page. It is **causal** ([§Public API](#public-api) — views stay passive), reuses the work ledger (one work-ledger row per page fetch, `:work/kind :resource`), and respects generation + stale suppression exactly as `ensure` does.

```clojure
[:rf.resource/load-more
 {:resource :feed/timeline
  :scope    {:from-db :app/session}     ;; resolved like any resource event
  :params   {:filter :recent}           ;; the FEED identity (not a page)
  :cause    [:user :feed/load-more]}]   ;; OWNERLESS — carries a :cause, not an :owner
```

**Ownerless (MUST).** A `load-more` MUST be **ownerless**: it carries a `:cause`, never an `:owner`. The route (or whatever owner first-loaded the feed) already *owns* the one feed entry for its lifetime, keeping it alive; a load-more *extends* that one owned entry — it does not intend to keep anything alive on its own, so it omits `:owner` and supplies only `:cause` (owner keeps alive; cause explains why). This is the same owner-vs-cause distinction a manual refresh makes ([§Owners are liveness leases](#owners-are-liveness-leases) — an event that only extends data without intending to keep it active is a cause, not an owner). A **supplied `:owner` is warn-and-ignored**: the runtime drops it and emits `:rf.warning/resource-load-more-owner-ignored` (DCE'd in production) rather than minting a stray lease that would pin the feed alive past its real owner — a load-more never changes the active-owner set.

FSM interaction (MUST) — **no 6th FSM state (R2)**; the five states are untouched:

- **`load-more` on a `:loaded` feed that has a next page** computes the next `:page-param` from the entry's tail (via `:next-page-param`), issues the managed request for that page with the resolved page ctx, and transitions the feed to **`:fetching` (the existing refresh-class transition)** — because the feed already has data. The accumulated pages **stay visible** (no skeleton).
- A page-fetch **success** appends the decoded page to `:data`, appends its param to `:page-params`, recomputes `:next-page-param` / `:prev-page-param`, and returns the feed to `:loaded`. Structural sharing preserves all prior pages.
- A page-fetch **failure** is a **load-more failure**, not a feed first-load failure: the feed returns to `:loaded`, **keeps all accumulated pages**, and records `:page-error` — a **third error channel** beside `:error` (first-load) and `:refresh-error` (whole-feed refresh) — so a view shows "couldn't load more — retry" without losing the feed. `:page-error` is cleared by the next successful load-more or whole-feed load.
- **`load-more` with no next page** (`:next-page-param` is `nil`) is a **no-op** that emits a trace (`:rf.resource/load-more-skipped`, `:reason :no-next-page`); it never fires a request.
- A **`load-more` while a page fetch is already in flight** dedupes against the in-flight work ([§Race and in-flight semantics](#race-and-in-flight-semantics)), exactly as a duplicate `ensure` does (trace `:rf.resource/load-more-skipped`, `:reason :in-flight`).

A load-more in flight is the derived **`:fetching-next?`** subscription (below) — distinct from `:fetching?` (a whole-feed refresh). The distinction is a derived value, not a new entry status.

**`:rf.resource/ensure` on an infinite resource fetches page 0 only** (the first load); it does not re-fetch the whole accumulation. A route entry ensures **page 0**; `:blocking?` blocks on page 0 only. Load-more is a user-caused event during the route's lifetime, not a route plan step. Refetch is [§Refetch and invalidation of an infinite feed](#refetch-and-invalidation-of-an-infinite-feed).

> **Bidirectional (R7).** The `:prev-page-param` derivation mirror is **defined now** (it is free — the same machinery as `:next-page-param`, computed from the first page). The **prepend event `:rf.resource/load-prev` is DEFERRED** until a consumer needs it; v1 ships **next-direction `load-more` only**. `:has-prev-page?` is exposed (so the derivation is observable) but a feed with no `load-prev` event simply never advances backward in v1.

### Subscription contract: the merged list and page metadata (R3)

The existing `:rf.resource/state` / `:data` / `:status` / `:loading?` / `:fetching?` / `:stale?` / `:error` / `:refresh-error` / `:has-data?` family all apply to an infinite feed — but `:rf.resource/data` returns the **raw page vector**, which is rarely what a feed view wants. The artefact adds a small infinite-specific projection family, all passive, all derived (never stored), and **framework-owned + memoised** (the merge is a pure derivation the framework owns once, not an ordinary app sub over `:pages`):

```clojure
;; The merged / flattened list — the everyday feed read (the HEADLINE).
;; DERIVED from :data (the page vector) by concatenating each page's items.
[:rf.resource/items          {:resource :feed/timeline :scope … :params …}]

;; The raw page sequence (the TanStack `pages` analogue) — for views that need
;; page boundaries (e.g. "—— page break ——" or per-page headers).
[:rf.resource/pages          {:resource … :scope … :params …}]

;; Page metadata — the load-more UI state.
[:rf.resource/has-next-page? {:resource … :scope … :params …}]   ;; (some? next-page-param)
[:rf.resource/has-prev-page? {:resource … :scope … :params …}]
[:rf.resource/fetching-next? {:resource … :scope … :params …}]   ;; a load-more in flight (R2)
[:rf.resource/page-count     {:resource … :scope … :params …}]
[:rf.resource/page-error     {:resource … :scope … :params …}]   ;; last load-more failure
```

And a combined infinite view-model (the feed analogue of `:rf.resource/state`):

```clojure
@(rf/subscribe [:rf.resource/infinite-state {:resource :feed/timeline :scope … :params …}])
;; =>
{:status         :loaded
 :items          [<item> <item> …]   ;; merged list (the everyday read)
 :pages          [<page-0> <page-1> …]
 :page-count     3
 :has-next-page? true
 :has-prev-page? false
 :loading?       false               ;; first load (page 0), no data yet
 :fetching-next? false               ;; a load-more in flight (pages stay visible)
 :fetching?      false               ;; whole-feed refresh in flight
 :stale?         false
 :has-data?      true
 :error          nil                 ;; page-0 first-load failure
 :refresh-error  nil
 :page-error     nil}                ;; last load-more failure
```

Rules (MUST):

- **`:rf.resource/items` is the headline read** — most feed views want the flat list (TanStack users immediately `.flatMap(p => p.items)`). The merge is a pure derivation the framework owns and memoises once; `:rf.resource/pages` is available when page boundaries matter.
- **The flatten rule is loud, not magic (R3).** A page that **is already a vector** flattens by identity. A feed whose page is **non-vector / enveloped** (e.g. `{:items [...] :page-info {…}}`) **MUST** declare a **`:page->items`** accessor (a keyword key or a `(fn [page] …)`) — the framework does **not** guess `:items` / `:data`. `:rf.resource/items` is then `(into [] (mapcat page->items) pages)`. A non-vector page with no `:page->items` is a registration error (`:rf.error/infinite-missing-page-accessor`).
- **`:rf.resource/items`, `:rf.resource/pages`, and `:rf.resource/infinite-state` are framework-owned memoised subscriptions** — not ordinary app subs over `:pages`. (This is the divergence from the `:select` precedent — for the *merge*, the framework owns the memoised projection because it is the headline read of every feed.)

A worked feed view reads `:rf.resource/infinite-state`, renders `:items`, shows a spinner when `:fetching-next?`, a "Load more" button (dispatching `:rf.resource/load-more`) when `:has-next-page?`, an end-of-feed marker otherwise, and a "couldn't load more — retry" affordance when `:page-error`. The view is passive: it reads the merged list + page metadata and dispatches a causal `load-more`.

### Refetch and invalidation of an infinite feed

- **`:rf.resource/refetch` of a feed** is governed by an explicit per-resource `:refetch` policy. The **ruled default is conservative: preserve the visible window (R6)** — the accumulated pages stay rendered until their replacement succeeds, so a focus/reconnect/invalidation-driven refetch **never collapses a loaded feed back to page 0** (the failure mode of a hard discard-tail default). Two opt-ins ship **from day one**: **`:refetch-all-pages?`** re-fetches every accumulated page param in sequence (TanStack parity), and **`:refetch-window`** bounds how much of the accumulation is refreshed. Pages persist by *accumulation*, not by being independently cached, so the window-preserving swap is coherent. *(This supersedes the EP's earlier discard-tail recommendation — the Codex-flagged safer behaviour is the ruled default.)*
- **Tag invalidation** (`:rf.resource/invalidate-tags`, and an [EP-0016](../docs/EP/EP-0016-resource-mutation-completion.md) mutation `:invalidates`) marks the feed entry stale by its **feed tag** → the feed refetches per the refetch rule above on the next ensure.
- **Item-inside-the-feed mutation invalidates the WHOLE feed (R4).** A mutation that touches one item inside the feed invalidates the feed (coarse, correct, v1) rather than patching one element in place. **In-place patching** of a single item inside the page vector is **deferred** to the optimistic/patch axis ([EP-0016 issue 9](../docs/EP/EP-0016-resource-mutation-completion.md#open-issues)). A feed `:tags` fn MAY be evaluated per item so an item tag maps to the feeds containing it (enabling targeted feed invalidation), but reaching into the vector to patch is out of scope for v1.
- **Scope invalidation** (`clear-scope` on logout) drops the feed entry like any scoped entry — the whole accumulation goes, correctly, with the user.

### SSR, hydration, restore

The infinite feed entry is an ordinary durable resource entry, so it rides the existing [§SSR and hydration](#ssr-and-hydration) and [§Restore and replay](#restore-and-replay) contracts unchanged — **no new SSR/restore rules are required** (a direct benefit of the one-entry-in-runtime-db shape, R1):

- **SSR / hydration.** A blocking route serializes the accumulated pages (typically just page 0 from the server) through the same allowlist projection + egress walker, applying `:page-data-schema` **per page** (R5). The client hydrates the page vector; load-more continues from the hydrated tail's `:next-page-param`.
- **Restore / replay.** The page vector is durable and restores wholesale; an in-flight load-more is a non-terminal work-ledger row reconciled to dangling exactly as any in-flight resource fetch ([§Restore and replay part 2](#2-in-flight-work-does-not-survive-restore-as-live-work)); the monotonic generation allocator ([part 1](#1-the-generation-allocator-is-monotonic-and-host-side-it-does-not-rewind)) stays monotonic so a late page reply cannot append to a post-restore feed.

### Trace surfacing

The `:rf.resource/*` trace family ([§Xray and AI tooling](#xray-and-ai-tooling)) carries four infinite-specific ops (catalogued in [009 §resources](009-Instrumentation.md) and in the Xray panel spec [`tools/xray/spec/024-Resources-Panel.md`](../tools/xray/spec/024-Resources-Panel.md)):

- **`:rf.resource/load-more`** — a load-more was dispatched: feed key, resolved `:page-param`, current `:page-count`, work id.
- **`:rf.resource/page-appended`** — a page-fetch succeeded and was appended: page index, new `:page-count`, derived `:next-page-param` (or `:terminal? true`).
- **`:rf.resource/load-more-skipped`** — a load-more that fired no request: `:reason :no-next-page` (terminal) or `:reason :in-flight` (deduped).
- **`:rf.resource/page-failed`** — a load-more page fetch failed (the `:page-error` channel; distinct from `:rf.resource/failed` first-load and `:rf.resource/refresh-failed`).

Xray's resource-instance table shows, for an infinite entry: page count, next-page-param presence (`has-next?`), per-page params (egress-projected, since cursors can carry ids), and the accumulated-size growth view.

## SSR and hydration

SSR MUST use request-local frames — a process-global resource cache would leak data between users.

**Server route handling:** resolve the route; compute route resources; enqueue blocking resource ensures; drain until blocking resources for the current nav-token settle; render with the settled resource state; serialize only the allowed resource runtime projection; record projection metadata (serialized, redacted, omitted, fresh, stale, refetch-on-client decisions).

**Every durable entry present at serialize time rides the projection, not just the blocking ones.** A non-blocking route resource that happened to settle before render serializes exactly like a blocking one (per-entry redacted/omitted by its classification); a non-blocking resource still in flight simply has no usable `:data` to serialize yet (its entry is `:loading` / `:idle`), and the client refetches it on hydration if the route still needs it. Blocking only governs the **server wait point** (drain-before-render), not whether an entry is eligible for serialization.

Blocking SSR resources need a **timeout policy**: a timeout settles the resource as a structured first-load failure for that SSR frame, records the route blocking failure, and lets the renderer choose error markup, a skeleton, or an application fallback. It MUST NOT hang the request indefinitely. The settled failure's `:error` envelope uses the **closed `:rf.http/*` taxonomy** ([014-HTTPRequests](014-HTTPRequests.md)) — `{:kind :rf.http/timeout :reason :ssr-blocking-timeout}` — so the `:reason` lets a renderer distinguish an SSR-deadline failure from a genuine upstream timeout while the `:kind` stays inside the one error taxonomy resource `:error` / `:refresh-error` envelopes carry everywhere.

**Client hydration:** install the allowed resource projection into the target frame-state's `:rf.runtime/resources` slice in runtime-db (`:rf.db/runtime`); preserve hydrated resource entries; avoid duplicate immediate fetches for fresh entries; background-refetch stale entries according to policy; maintain frame and nav-token isolation.

Do **not** serialize all of `:rf.db/runtime` by default. Resource hydration uses an **explicit projection hook** (the allowlist-by-subsystem-child `project-runtime-db` of [011-SSR](011-SSR.md)) that can redact or omit sensitive and large data. The owner classification governs the projection: the coarse whole-entry `:sensitive?` / `:large?` claim (the degenerate root-prop case, [EP-0015 §6](../docs/EP/EP-0015-frame-owned-egress-policy.md)) drives the metadata-only redact / omit shape, and a serialized entry's data slice rides through the merged frame-owned `rf/project-egress` (under the `:rf.egress/ssr-hydration` boundary profile) over the shared `rf/elide-wire-value` walker, so any per-slot `:data-schema` mark the frame classification carries composes as defense-in-depth. Hydration MUST NEVER cross scopes: request-local SSR frames and serialized resource scopes MUST agree before a client treats hydrated data as usable.

Hydration rules (MUST): `loaded-at` / `stale-at` / `invalidated-at` are absolute timestamps, and server clock skew is surfaced in trace/hydration diagnostics when it makes freshness ambiguous; omitted or redacted entries hydrate as metadata only and refetch on the client if the route still needs them; stale hydrated entries may render their data immediately, then refetch by resource event according to policy; `refresh-error` serializes only when the error envelope is allowed by the same privacy/size projection as data.

## Restore and replay

Resources are runtime-managed read models over an in-flight work ledger, so a "time-travel-safe" claim is not credible until `restore-epoch!` (the EP-0001 epoch restore / time travel, sharing the same install path SSR hydration uses) is defined for `:rf.runtime/resources` and `:rf.runtime/work-ledger`. Epoch restore installs **both partitions wholesale** — it replaces the entire frame-state value (`:rf.db/app` plus `:rf.db/runtime`) and does not run ordinary `:db` effect semantics ([EP-0001 §Full-frame restore](../docs/EP/EP-0001-frame-partitions.md)). Host side tables (AbortControllers, stale/GC timers, transport promises) are **not** frame-state and are **not** rewound; they are transient by the EP-0001 durable/transient boundary. Restore must reconcile a freshly-installed *durable snapshot* against the *live transient world* (host handles still attached to the pre-restore timeline, and network replies already on the wire the runtime cannot recall).

The governing principle is the **anti-recycling rule** (the routing nav-token discipline, generalized): a restored value MUST NEVER let a stale generation or work-id be mistaken for a live one. Epoch restore MUST NOT resurrect a superseded in-flight identity, and MUST NOT rewind any monotonic allocator such that a post-restore allocation can collide with a pre-restore identity still carried by an uncancellable in-flight reply.

The contract has five parts.

### 1. The generation allocator is monotonic and host-side; it does not rewind

A resource generation is the correctness boundary for stale-reply suppression: a reply may write an entry only if its work-id and generation still match the live entry. If restore rewound the generation, a pre-restore in-flight reply — already on the wire, uncancellable — could return carrying a generation the post-restore timeline has re-allocated, and be silently accepted as live.

Therefore the **generation allocator is a per-frame, host-side monotonic high-water mark**, not a value rewound by restore (the routing nav-token-counter precedent: keep the active identity durable on the entry, restored coherently, but keep the *allocator* host-side so it only moves forward across restores). After a restore, the next generation strictly exceeds every generation any pre-restore in-flight reply could carry, so a stale reply's generation can never match a live entry's — collision is structurally impossible.

This is the *opposite* discipline from machine spawn-ids ([005 §Spawn-id allocator](005-StateMachines.md#spawn-id-allocator--counter-location)): **an allocator whose identity can be carried by an out-of-frame, uncancellable continuation must never rewind; an allocator whose identities never leave the frame may be snapshot-local and replay-deterministic.** A spawn-id never escapes the frame; a resource generation governs acceptance of a reply that *has* escaped, so it must never be re-issued. The work-ledger `:work/id` (which embeds the generation) inherits the same monotonicity.

> **Restore-safety and replay-recordability are both settled.** Parts 1–5 of this section establish that the host-side monotone allocator is correct for **epoch restore** (time-travel install of a durable snapshot); the **recordable generation allocation** (below) closes the remaining **replay-determinism** boundary (re-deriving durable state by re-folding a recorded event log). The `generation` is a **durable join key** — written into the entry/instance and stamped onto the reply token as the **stale-suppression** correlation — so per [002 §Durable join keys are recordable](002-Frames.md#durable-join-keys-are-recordable-even-when-their-allocator-is-host-transient) the minted *value* MUST be recordable even though its *allocator* stays host-transient. There is no ambient `:rf.resource/generation` coeffect — an ambient host-cache read would re-mint a *different* generation on replay (a recorded accepted reply replaying as stale-suppressed, or vice versa), so the value rides a recordable generator instead.
>
> The seam is the **generator-backed recordable** `:rf.resource/generation-allocation` coeffect ([EP-0017 §The minting ladder](../docs/EP/EP-0017-recordable-coeffects.md) — a fold-internal identity no recorded state or event payload can supply, so it rides the last rung as a recordable generator):
>
> - the generation allocator stays the **per-frame, host-side monotone high-water mark** of part 1 (`generation-cache`, advanced with `max` via the `:rf.resource/commit-generation` fx); it is **not** recorded into runtime-db (recording the counter would rewind it on restore and recycle a live identity — the part-1 anti-recycling property);
> - the `:rf.resource/generation-allocation` cofx **generator** reads that host high-water at **processing-start** and produces the next monotone allocation `{:generation N :counter N}`; under `:live` the runtime **records that value on the causal token** (`:rf.cofx`) so replay re-presents it, under `:strict` (replay / the `:test` preset) the generator does not run and an absent allocation is `:rf.error/missing-required-cofx` rather than a silently re-minted divergent value;
> - the `:rf.resource/ensure` / refetch / `:rf.mutation/execute` handlers **declare** `:rf.cofx/requires [:rf.resource/generation-allocation :rf/time-ms]`, read the `:generation` value flat, and write **only** that value durably — they do not re-mint `(inc snapshot)` from an ambient read at the write site; the co-declared `:rf/time-ms` is the framework-stamped causal time fact (next clause);
> - the `:work/id` / `:instance/id` (which embed the generation) then reproduce for free, since both are *purely derived* from `generation` plus the already-recordable scoped key / mutation id / caller-supplied instance. The mutation writer mints from the **same** allocation root (one join key — `generation`), so resources and mutations are covered by the one seam.

### 2. In-flight work does not survive restore as live work

Every non-terminal row in the installed snapshot (`:queued` / `:running` / `:abort-requested`) references a request whose host handle no longer belongs to the restored timeline. A restored non-terminal row is therefore **dangling**. On install, restore reconciles non-terminal rows:

- the row's `:work/id` is recorded as **dangling/superseded** (it can never again match a live entry, because the allocator has moved past it per part 1), and its host side-table slot is cleared;
- the linked resource entry's `:current-work` pointer is cleared, because the attempt it pointed at no longer exists;
- the entry's `:status` settles to its last *stable* status from the restored snapshot — `:loaded` if it has usable `:data`, `:error` if it was a failed first load with no data, `:idle` if it never loaded — never left stranded in `:loading` / `:fetching` pointing at a vanished request;
- any pre-restore reply that subsequently lands is suppressed by the ordinary work-id + generation check, because its identity is now dangling. **No stale reply may mutate a post-restore entry** — this is the mandatory stale-suppression boundary, not a new mechanism.

A restored **pending mutation instance** (`:rf.runtime/mutations`, whose reply gate keys off the *instance's* `:current-work` + `:generation`, not the linked entry's) is reconciled the same way: it is terminally settled to `:error` with the `:dangling-on-restore` envelope and its `:current-work` is cleared, so a late pre-restore mutation reply cannot patch / populate / invalidate post-restore state. The durable `:settled-at` that terminal settle stamps is sourced from the **restore's causal time** — the restored epoch's `:committed-at` ([EP-0010](../docs/EP/EP-0010-causal-world-inputs.md): the committing token's `:rf.cofx` `:rf/time-ms`, replay-stable) — **not the live install wall clock**. Per the restore-clock discipline a durable frame-state field MUST come from a causal input, never an ambient world read at install; the live clock is read **only** for the freshness skew *diagnostic* (part 3), which changes no durable value.

Whether the restored entry then *re-fetches* is a freshness decision (part 3), not an in-flight decision. Restore never silently continues old work.

### 3. Freshness after restore: lazy, not an eager refetch storm

Restored entries carry absolute timestamps from the restored epoch. Two failure modes must be avoided: an **eager refetch storm** (every restored entry refetches at once) and **silent acceptance of misleadingly-fresh timestamps**. The ruling (consistent with hydration, which faces the identical absolute-timestamp problem):

- restore does **not** eagerly refetch — freshness is evaluated lazily, exactly as hydration handles it: a restored entry renders its data immediately and refetches only on the next `ensure` from a live owner (route re-entry, focus/reconnect revalidation, or an explicit event), gated by the entry's own stale/fresh policy;
- a restored entry with **no active owner** is never refetched on the strength of restore alone — it is subject to ordinary GC eligibility (part 5);
- absolute-timestamp ambiguity (a restored `:stale-at` implausible against the live clock) is surfaced in a restore/hydration trace diagnostic, exactly as clock skew is for SSR hydration, rather than silently trusted;
- this yields the desired property: **a restored epoch double-fetches nothing.** Refetch happens only when a live cause demands it.

### 4. Owners revive or orphan by kind

Restored `:active-owners` reference owner tokens from the pre-restore timeline. Whether a restored owner is *real* depends on whether the thing it names is itself revertible:

- **Machine owners** (`[:machine actor-id]`) revive — machine liveness is a pure function of the restored snapshot ([005](005-StateMachines.md)), so a machine owner the snapshot revives is a genuine live lease again. (If the restored snapshot does NOT revive the actor, its lease is released on the actor's teardown like any other destroy — see [§Release authority is per owner kind](#release-authority-is-per-owner-kind).)
- **Route owners** (`[:route route-id nav-token]`) revive **only if** the restored routing state names the same live nav-token (`:current` is durable). A restored route owner whose nav-token is not the one the restored routing slice currently considers live is released as an **orphan**.
- **Lease/event owners** (`[:lease …]`, `[:dashboard/opened …]`) revive with the snapshot (recorded durably on the entry); their release path is the same explicit `:rf.resource/release-owner`.
- **SSR owners** (`[:ssr request-id nav-token]`) do not survive a client-side restore as live leases; they belong to a settled server render and are released as orphans if present.

Owner reconciliation runs on install: each restored owner is checked against the revived runtime state, surviving owners stay in `:active-owners` and the `:owner-index`, and orphaned owners are dropped with a trace row.

### 5. Transient side tables and indexes are recomputed or cleared on install

- **Host transients are cleared, then recomputed on demand.** Stale timers, GC timers, AbortControllers, and transport promises are frame-scoped host handles restore does not rebuild (EP-0001 decision 13). On install they are cleared for the affected frame; stale/GC scheduling is re-armed lazily from the restored entries' durable timestamps the next time the runtime touches each entry (timers are advisory and re-checked against durable facts).
- **Indexes are recomputed from entries, never trusted from the snapshot.** `:tag-index` and `:owner-index` are derived projections of the entries' `:tags` and `:active-owners`. They are **recomputable-from-entries**: on restore (and on SSR hydration) they are rebuilt from the installed `:entries` rather than read from the serialized snapshot, so a stale or partial index can never outlive the entries it describes. This single rule also serves SSR hydration: hydration likewise installs `:entries` and recomputes the indexes, so the durable wire payload need not carry them at all.

## Xray and AI tooling

Resources need a trace/accessor contract, not only panel UI. Xray exposes: a static resource registry (id, source coordinates, params/data schemas, request summary, stale/GC/poll policy, tag producer, scope resolver, sensitivity classification, declaring routes); a live resource-instance table per frame (key, scope, status, timestamps, generation, request id, attempt, active owners, tags, errors, data summary, GC eligibility, poll interval / next-tick estimate / paused-reason); a live work-ledger table per frame (work id, kind, linked resource key, generation, status, owners, causes, cancellable?, deadline, retry attempt, outcome); a route/resource graph; a lifecycle timeline; an invalidation/mutation graph; a cache-growth view; and a **scope audit surface** — the standing enumeration of every `:rf.scope/global` resource (the structural security-review list that replaces the old `/me` heuristic) plus the suspicious-explicit-global warnings.

Two lints ride the cache-growth / audit surface:

- **Scope-mismatch lint** — a cache **entry** exists for resource `R` + params `P` under scope `A` while a **live subscription** reads the same `R` + `P` under a **different** scope `B` and gets `:idle` (or `:loading` that never resolves). The fail-closed scope rules make a missing scope a loud error; this lint is the runtime tripwire for the cases that slip through (e.g. an event ensured under `[:rf.scope/session {…}]` while a view subscribed under `[:rf.scope/global]`). Xray flags the mismatched (entry-scope, sub-scope) pair so the divergence is obvious rather than a permanent silent skeleton. This lint has **two in-framework dev-only complements**, both DCE'd in production: the **read-side** `:rf.warning/resource-sub-scope-mismatch` warning the subscription path emits at the moment of the mismatched read (see [§Dev-mode likely-mismatch warning](#dev-mode-likely-mismatch-warning-rfwarningresource-sub-scope-mismatch)), and the **write-side** `:rf.warning/mutation-scope-mismatch` warning the mutation-settlement path emits when a mutation's `:invalidates` descriptor invalidates in a scope that holds no matching entry while a different scope does (see [§Dev-mode write-side tripwire](#mutation-scope-is-two-distinct-scopes-hybrid)) — the framework surfaces the footgun in the trace stream from BOTH ends (the read that lands on the wrong scope AND the write that invalidates the wrong scope) without the developer having to open Xray, and Xray's offline lint catches the cases the live heuristics narrow away (e.g. a global-scope sub reading a session-scoped entry).
- **Orphaned-owner lint** — an app-minted `[:lease …]` owner with no observed release path (see [§Release authority is per owner kind](#active-owners-and-causes)).

Tool APIs prefer summaries and metadata over raw values — an AI usually needs "this route owns `:article/by-slug`, it is stale, and the latest background refresh failed with a 503", not the full article body. Resource history MUST be bounded, and params/scopes get the same privacy and size elision as data (scopes can contain user ids, tenant ids, locale, or impersonation markers) through the shared `rf/elide-wire-value` walker. Candidate tool accessors — `list-resources`, `list-resource-instances`, `get-resource-state`, `get-resource-history`, `list-resource-invalidations` — filter by frame, scope, resource id, tag, owner, status, stale?, request id, and nav-token; raw data access continues to go through existing egress and elision rules.

### Error and warning tag roster

The artefact's `:rf.error/resource-*` error tags and `:rf.warning/resource-*` / `:rf.warning/mutation-*` dev warnings are named at their raising sites throughout this Spec; the roster below is the consolidated index. **Each tag's structured payload shape lands in [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) and [Spec-Schemas](Spec-Schemas.md) with its implementation slice** (the same staging the EP applies to conformance fixtures — see [§EP graduation status](#ep-graduation-status--resolved)); this table is the loud-failure surface and where the contract is stated, not the payload contract.

| Tag | Severity | Raised when | Section |
|---|---|---|---|
| `:rf.error/resource-missing-scope-policy` | error (registration) | a `reg-resource` declares no `:scope` policy | [§Every resource declares a scope policy](#every-resource-declares-a-scope-policy-required-fail-closed) |
| `:rf.error/resource-bad-spec` | error (registration) | a `reg-resource` is missing `:params-schema` or `:request` | [§Resource registration spec](#resource-registration-spec) |
| `:rf.error/resource-scope-required-from-caller` | error (use-time) | a `:rf.scope/from-caller` resource event supplies no `:scope` and no resolver yields one | [§Resolution precedence](#resolution-precedence-for-events-no-global-fallthrough) |
| `:rf.error/resource-sub-unresolved-scope` | error (use-time) | a subscription cannot resolve a scope (including a mid-session `{:from-db …}` re-key to nil) | [§Subscription-side scope resolution](#subscription-side-scope-resolution) |
| `:rf.error/resource-invalidate-scope-required` | error (use-time) | a bare `:rf.resource/invalidate-tags` supplies no scope (the fail-closed floor) | [§The cross-scope lattice](#the-cross-scope-lattice--three-precise-rungs) |
| `:rf.error/resource-cross-scope-cause-required` | error (use-time) | a `:cross-scope? true` invalidation carries no `:cause` evidence | [§The cross-scope lattice](#the-cross-scope-lattice--three-precise-rungs) |
| `:rf.error/resource-route-plan` | error (route planning) | a route-resource entry's params/scope/`:when`/`:after` fails to resolve (fail-closed, surfaced on the route slice + Xray) | [§Route integration](#route-integration) |
| `:rf.error/resource-ssr-blocking-timeout` | error (SSR) | a blocking SSR resource exceeds the render deadline | [§SSR and hydration](#ssr-and-hydration) |
| `:rf.error/infinite-missing-next-page-param` | error (registration) | a `reg-resource` declares `:infinite true` but no `:next-page-param` | [§Infinite resources and load-more feeds](#infinite-resources-and-load-more-feeds) |
| `:rf.error/infinite-missing-page-accessor` | error (registration) | an `:infinite` resource whose page is non-vector / enveloped declares no `:page->items` accessor | [§Subscription contract](#subscription-contract-the-merged-list-and-page-metadata-r3) |
| `:rf.warning/resource-sub-scope-mismatch` | dev warning (DCE'd) | a `:rf.scope/from-caller` sub resolves a valid-but-wrong scope (read-side tripwire) | [§Dev-mode likely-mismatch warning](#dev-mode-likely-mismatch-warning-rfwarningresource-sub-scope-mismatch) |
| `:rf.warning/mutation-scope-mismatch` | dev warning (DCE'd) | a mutation's `:invalidates` descriptor matches zero entries in its scope while another scope holds them (write-side tripwire) | [§Dev-mode write-side tripwire](#mutation-scope-is-two-distinct-scopes-hybrid) |
| `:rf.warning/resource-clear-scope-unresolved` | dev warning (DCE'd) | a `{:from-db …}` reference resolves nil at a `clear-scope` site | [§`clear-scope` resolves the concrete scope from the coeffect db](#clear-scope-resolves-the-concrete-scope-from-the-coeffect-db-not-a-snapshot) |
| `:rf.warning/optimistic-tags-descriptor-skipped` | dev warning (DCE'd) | a malformed `:optimistic-tags` descriptor (non-map / non-coll `:tags` / missing `:patch`) is warn-and-skipped at execute time rather than aborting the write | [§Optimistic mutations](#optimistic-mutations) |
| `:rf.warning/mutation-target-skipped` | dev warning (DCE'd) | a recoverable settle-time `:populates` / `:patches` / removes target (unregistered resource / non-map / non-keyword `:resource`) is dropped-and-warned post-write rather than stranding the committed mutation; cache-identity corruption still throws | [§Map-form exact resource targets](#map-form-exact-resource-targets) |

(`:rf.error/no-frame-context`, `:rf.error/legacy-runtime-root`, and `:rf.error/non-edn-identity` are framework-wide tags this artefact composes with, owned by [002-Frames](002-Frames.md) / [Conventions](Conventions.md), not minted here. `:rf.error/non-edn-identity` fails a resource identity closed when a params or scope value is outside the CEDN-1 domain — a float / ratio / NaN / infinity, an out-of-safe-range integer, or a host handle such as a raw date object — at the scope/params resolution boundary, before a cache entry, work id, or tag row is minted; see [§Resource identity](#resource-identity) / [§Canonicalization rule](#canonicalization-rule).)

The artefact adds a `:rf.resource/*` trace family with operations such as `:rf.resource/registered` (one row per FIRST-TIME `reg-resource`, frame-agnostic — the registration anchor of the family; symmetric with `:rf.route/registered` / `:rf.flow/registered`), `:rf.resource/ensure`, `:rf.resource/owner-attached` (a NEW owner lease landing on an entry — both on a fresh load and on a dedupe join; symmetric with `:rf.resource/owner-released`), `:rf.resource/cache-hit` (a *fresh-skip* ensure — an `ensure` of an already-`:loaded` entry still fresh-by-policy serves the cached value, neither fetching nor joining in-flight work; distinct from `:rf.resource/deduped`), `:rf.resource/deduped`, `:rf.resource/work-started` (a work-LEDGER row was created — the transport request started; carries `:status :running` + `:superseded`) and `:rf.resource/fetch-started` (the cache ENTRY's status transition — carries the entry's `:status`, `:fetching` on a first load or stale-revalidate), `:rf.resource/work-abort-requested`, `:rf.resource/work-completed`, `:rf.resource/succeeded`, `:rf.resource/failed`, `:rf.resource/refresh-failed`, `:rf.resource/invalidated`, `:rf.resource/refetch-decision`, `:rf.resource/owner-released`, `:rf.resource/stale-scheduled`, `:rf.resource/stale-fired`, `:rf.resource/gc-scheduled`, `:rf.resource/gc-fired`, `:rf.resource/gc-skipped`, `:rf.resource/removed`, `:rf.resource/stale-suppressed` (the entry + ledger stale/superseded-reply suppression — the single suppression op the runtime emits; there is exactly one suppression op, not two), `:rf.resource/route-plan` (the route `:resources` plan summary on route entry — route id, nav-token, ensured count, blocking scoped keys; the route/resource graph signal), `:rf.resource/revalidate-scan` (the focus/reconnect active-stale scan summary — the revalidation signal, the `:focus` / `:reconnect` cause, the scanned-entry count, and the refetched scoped keys; the per-entry refetch decisions ride the ordinary refetch traces), `:rf.resource/hydrated`, `:rf.resource/hydrate-refetch` (one per hydration refetch-plan entry — the per-entry decision that a hydrated entry was not sufficient on its own, `:reason` `:no-data` / `:stale` / `:metadata-only`, distinct from the ordinary refetch the route slice then dispatches), and the **infinite-resource** ops `:rf.resource/load-more` (a load-more dispatched — resolved `:page-param`, current `:page-count`, work id), `:rf.resource/page-appended` (a page-fetch succeeded and was appended — page index, new `:page-count`, derived `:next-page-param` or `:terminal? true`), `:rf.resource/load-more-skipped` (a load-more that fired no request — `:reason :no-next-page` terminal or `:reason :in-flight` deduped), and `:rf.resource/page-failed` (a load-more page fetch failed — the `:page-error` channel, distinct from `:rf.resource/failed` first-load and `:rf.resource/refresh-failed`; see [§Infinite resources and load-more feeds](#infinite-resources-and-load-more-feeds)). Each carries, where applicable, frame, work id, scope, resource key/id, params summary, generation, request id, owner, cause, status before/after, work status, resource/invalidated tags, freshness timestamps, and redaction/size markers.

> **Fresh-skip op — `:rf.resource/cache-hit`.** The family emits `:rf.resource/cache-hit` for a *fresh-skip* ensure — an `ensure` of an already-`:loaded` entry that is still fresh-by-policy, so it neither dedupes (no in-flight work to join) nor starts a fetch (the cached value is sufficient). That is genuinely distinct from `:rf.resource/deduped` (joining an in-flight request). The fresh-skip *behaviour* is mandated by the FSM (a `:loaded` entry transitions to `:fetching` only on `stale/refetch`; a fresh `ensure` has no transition) and by [§Restore and replay](#restore-and-replay) ("refetches only on the next `ensure` from a live owner … gated by the entry's own stale/fresh policy"). The reference implementation short-circuits a fresh `:loaded` ensure: it attaches the supplied owner lease (a `:rf.resource/owner-attached` row covers a newly-attached owner), emits `:rf.resource/cache-hit`, drains any blocking route slot immediately (a fresh blocking resource settles the navigation at once — it treats the fresh entry as already-`:success`, so a route blocked on a fresh resource never hangs), and starts no new generation / fetch / work record. A `refetch` is never a fresh-skip (it always forces a new generation); a STALE `:loaded` entry still refetches on the next `ensure` (fresh-skip never swallows a stale refresh). The cache-hit needs no `:previous-key` projection (the entry has its own fresh data), arms no timers, and supersedes nothing.

> **Start-pair ops — `:rf.resource/work-started` + `:rf.resource/fetch-started`.** A load start emits *both* ops, together and unconditionally, on the one start path — they are **two facets of a single start, not two kinds of start** (in particular NOT first-load vs refresh). `:rf.resource/work-started` records the **work-ledger** facet: a new work-ledger row was created for the transport request — it carries the row's `:status :running` and whether this work `:superseded` an earlier in-flight attempt. `:rf.resource/fetch-started` records the **cache-entry** facet: the entry's status transitioned into `:fetching` — it carries the entry's resulting `:status`, which distinguishes a first load (no prior data) from a stale-revalidate (data retained while the background refresh runs). A consumer wanting one fact reads the matching op; a consumer wanting the whole start reads the pair. (This split is the closed-op-set contract; Xray's per-op semantic class lives in [Xray spec 024 §The `:rf.resource/*` trace family](../tools/xray/spec/024-Resources-Panel.md), and the emit catalogue in [009 §resources](009-Instrumentation.md).)

> **EP-0016 trace additions (Decisions 1–3).** The action wave names three new trace evidences in the closed op set:
> - **`:rf.mutation/replied`** (mutation family) — a call-site `:reply-to` continuation was dispatched for an accepted terminal reply: carries the continuation target, work id, mutation id, instance, status, and `:cause [:mutation <id> <instance>]` (D1; never emitted for a stale/suppressed reply).
> - **descriptor-level invalidation evidence** (D2) — when a mutation's `:invalidates` plan drives invalidation, the per-descriptor evidence rides the **mutation settlement op** (`:rf.mutation/succeeded` / `:rf.mutation/failed`) under an **`:invalidation`** facet (one mutation = one descriptor-level evidence record), carrying `:descriptor-count`, `:dispatched` (each descriptor's **resolved scope**, `:cross-scope?`, `:tags`, and `:refetch-populated?`), `:unresolved` (the fail-closed `{:from-db …}` ids that resolved nil and produced no invalidation — never an implicit global blast), and `:populate-exempt` (keys this mutation populated and exempted from same-mutation refetch by Rider 1). The per-PASS decision summary stays on the existing `:rf.resource/invalidated` op (`:scope`, `:tags`, `:cause`, `:cross-scope?`, `:matched`, `:refetched`, `:left-stale`, `:exempt`, `:any-tag-match-other-scope?`). A `:cross-scope? true` invalidation is a **privacy-relevant** trace ([EP-0015](../docs/EP/EP-0015-frame-owned-egress-policy.md)) and MUST carry its `:cause` — a cross-scope invalidation with no `:cause` is rejected (`:rf.error/resource-cross-scope-cause-required`, [§The cross-scope lattice](#the-cross-scope-lattice--three-precise-rungs)).
> - **`:rf.resource/scope-resolved`** (resource family) — a named `reg-resource-scope` resolver evaluated: carries the resolver id, the declared input names and their current values (egress-projected), and the resolved scope (or `:resolved-nil? true` at a fail-closed site). Tooling reads the declared inputs to avoid unnecessary whole-db re-resolution and to mark the whole-db-sugar cost ([EP-0015](../docs/EP/EP-0015-frame-owned-egress-policy.md) disposition 8). The companion dev diagnostic `:rf.warning/resource-clear-scope-unresolved` fires when a `{:from-db …}` reference resolves nil at a `clear-scope` site.
>
> Every dispatch touching `tools/xray/` or `tools/machines-viz/` updates `tools/xray/spec/*` in the same PR; this spec slice introduces **no** Xray-tool source change (no `tools/xray/` files touched), so the Xray spec is updated by the implementation slice that emits these ops (slice 8), not here — the op names are reserved now so the emit catalogue and the Xray panel spec land against a fixed roster.

## Examples

### Route-driven page load

```clojure
(rf/reg-resource-scope :realworld/session
  {:inputs {:username [:db [:auth :user :username]]}
   :resolve (fn [{:keys [username]} _ctx]
              (when username [:rf.scope/session {:username username}]))})

(rf/reg-resource
  :article/by-slug
  {:params-schema [:map [:slug :string]]
   :data-schema   :app/article
   :scope         {:from-db :realworld/session}   ;; named resolver — viewer-scoped
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
     :scope     {:from-db :realworld/session}
     :blocking? true}]})

(rf/reg-view article-page []
  (let [slug  (:slug @(rf/subscribe [:rf.route/params]))
        state @(rf/subscribe [:rf.resource/state
                              {:resource :article/by-slug
                               :scope    {:from-db :realworld/session}
                               :params   {:slug slug}}])]
    (cond
      (:loading? state)                         [article-skeleton]
      (and (:error state) (not (:has-data? state))) [article-error (:error state)]
      :else [:<>
             [:article-view {:article (:data state)}]
             (when (:fetching? state)      [refresh-indicator])
             (when (:refresh-error state)  [refresh-error (:refresh-error state)])])))
```

The view is passive; the route caused the ensure; the runtime owns the state. The registration `:scope`, the route entry `:scope`, and the subscription `:scope` all reference the **same** named resolver `{:from-db :realworld/session}` — the one scope-resolution currency. The subscription re-resolves its scoped key reactively when the resolver's `:db` inputs change ([§A `{:from-db …}` subscription re-keys](#from-db-subscription-re-keying)), so the view tracks the current principal without re-subscribing. The booleans the `cond` reads — `:loading?` / `:fetching?` / `:has-data?` — are derived values projected onto the aggregate `:rf.resource/state` map (and are also available as the scalar `:rf.resource/loading?` / `:rf.resource/fetching?` / … subs, [§Subscriptions (passive)](#subscriptions-passive)); the durable entry stores only the underlying facts ([§Status semantics](#status-semantics)).

### Event-driven ensure

```clojure
(rf/reg-event
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
    ;; Mint the owner under the actor's RUNTIME id so the machine teardown
    ;; auto-releases it. A spawned actor knows its own id via `:rf/self-id`
    ;; (stamped on its `:data`); a singleton machine's actor-id IS its
    ;; registered machine-id (`:checkout/flow` here), so either form yields the
    ;; same `[:machine <actor-id>]` key the destroy releases.
    (let [actor-id (:rf/self-id data :checkout/flow)]
      {:fx [[:dispatch [:rf.resource/ensure
                        {:resource :checkout/quote
                         :params   {:cart-id (:cart-id data)}
                         :owner    [:machine actor-id]
                         :cause    [:machine-action :checkout/quote.requested]}]]]}))}}
```

The machine remains the semantic workflow; the resource runtime handles cached read mechanics. The `[:machine <actor-id>]` owner is released on actor destroy — the machine teardown dispatches `:rf.resource/release-owner` for it on **every** destroy trigger (explicit destroy, exit cascade, `:spawn-all` teardown, frame destroy, `:final?` auto-destroy), so the lease never outlives the actor (per [§Release authority is per owner kind](#release-authority-is-per-owner-kind) and [005-StateMachines §What auto-cancels on destroy](005-StateMachines.md#what-auto-cancels-on-destroy--exactly-three-framework-managed-resource-kinds-divergence-from-xstate)).

## Deferred slices

The following are named here but their full contract lands with their slice (per [EP-0003 §Acceptance Criteria And Rollout](../docs/EP/EP-0003-resource-queries.md#acceptance-criteria-and-rollout)) and is **out of the read-resource MVP contract**:

- **First public-beta gate — LANDED, now complete.** Two slices graduated past the read-resource MVP and are fully specified above, not deferred:
  - **Mutations** — `reg-mutation` / `clear-mutation` register a causal write under the `:mutation` registrar kind; `:rf.mutation/execute` mints a per-submission instance row at `:rf.runtime/mutations` (keyed by instance id, so concurrent submissions don't clobber), creates a `:rf.runtime/work-ledger` record (work-kind `:mutation`), and lowers the write through the same managed-HTTP transport (runtime-owned reply addressing; generation + work-id stale suppression as for resources); on success it patches/populates resource entries then invalidates the `:invalidates` tags (explicit `:before-request` / `:after-success` / `:after-failure` / `:after-settle` timing); `:rf.mutation/clear` is the causal instance reset; the `:rf.mutation/*` passive subs project the instance view-model. Write retries are opt-in; optimistic rollback landed via [EP-0019](../docs/EP/EP-0019-optimistic-mutation-rollback.md) (the success trace shape it reserved is now filled — [§Optimistic mutations](#optimistic-mutations)). See [§Mutations (first public-beta gate)](#mutations-first-public-beta-gate).
  - **Focus/reconnect active-stale revalidation** — the `:rf.resource/window-focused` / `:rf.resource/network-reconnected` events scan the frame's active-owner stale entries and refetch them by policy (cause `:focus` / `:reconnect`, never an owner; generation + stale-suppression respected); the host `window` focus / online listeners are installed per-frame by `install-revalidation-listeners!` and cancelled on frame destroy via the `:resources/on-frame-destroyed!` hook ([§Stale and GC scheduling](#stale-and-gc-scheduling)).
- **Later slices:** GraphQL read/mutation transport (`:rf.graphql/query`, the first transport-extension proof — see [EP-0003 §Deferred: GraphQL](../docs/EP/EP-0003-resource-queries.md#deferred-graphql-later-phase)); generic transport extension protocol; normalized entity caches; automatic graph-derived invalidation; subscription-driven fetching; offline persistence; cross-tab broadcast. (Optimistic rollback ([EP-0019](../docs/EP/EP-0019-optimistic-mutation-rollback.md)), polling/interval revalidation ([§Polling](#polling)), and **infinite resources / load-more feeds** ([§Infinite resources and load-more feeds](#infinite-resources-and-load-more-feeds), [EP-0021](../docs/EP/EP-0021-infinite-resources.md)) are specified — next-direction load-more, coarse feed-level invalidation, a window-preserving refetch default; prepend / in-place item patching / streaming remain deferred.)

Mutations are the second slice (the first public-beta gate), not the MVP; with mutation invalidation and active-stale revalidation **in place**, the first public-beta surface — the threshold for "complete-enough resource management" — is complete. Optimistic rollback ([EP-0019](../docs/EP/EP-0019-optimistic-mutation-rollback.md)) and polling (EP-0020) sit on top of it. What remains (GraphQL, the generalized work ledger) is genuinely later-slice work, not a gap in the public-beta contract.

## What Spec 016 does NOT cover

- **GraphQL** — out of scope; deferred phase ([EP-0003 §Deferred: GraphQL](../docs/EP/EP-0003-resource-queries.md#deferred-graphql-later-phase)).
- **A generalized work ledger for all async primitives** — the ledger is named neutrally (`:rf.runtime/work-ledger`) and already carries two in-artefact writers (resource and mutation work), but no writer **outside** the Resources artefact participates yet; a general work-ledger EP is deferred until non-resource consumers (timers, streams, route loaders, spawned actors, machine async work) need it. The multi-writer authority question for those future out-of-artefact writers is explicitly open (see [§Open questions](#open-questions)).
- **Normalized graph caches, fragment stores, entity-identity policy** — Apollo/Relay-style; a separate later artefact, gated on the GraphQL phase and a justifying data model.
- **A `:select` projection key, a `:cache-key` escape hatch, subscription-driven fetching** — projections are ordinary subscriptions; canonical params are the identity; views stay passive.

## Open questions

> Per [SPEC-AUTHORING §SA-4](SPEC-AUTHORING.md), each item is classified `:resolved` / `:host-choice` / `:post-v1 tracked` / `:still-blocking`.

### Work-ledger multi-writer authority — `:still-blocking` for the multi-writer slice (`:post-v1 tracked` for v1)

`:rf.runtime/work-ledger` is designed as a **multi-writer** subsystem. Its two **landed** writers — the resource event handlers (work-kind `:resource`) and the mutation event handlers (work-kind `:mutation`) — both live in the Resources artefact and both mint authority via `:rf/framework-authority? true`, so clause 2 is satisfied for both. When the first writer **outside the Resources artefact** (timers / streams / route loaders / spawned actors / machine async work) joins, **who mints authority for each additional writer is unresolved** and MUST be settled per writer at that point — machines imply authority via `:rf/machine? true`; non-machine writers will each need to stamp `:rf/framework-authority? true` at their own registration sites or write through the privileged helpers. This is a deliberate forward-flag, not a v1 blocker: the contract is complete for both shipped writers. Tracking lands when the general work-ledger EP is opened (deferred until an out-of-artefact consumer needs it).

### Warm-mode prefetch — a route-plan preload verb over ownerless `ensure` — `:post-v1 tracked`

The resource machinery already supports the primitive a **pre-navigation prefetch** needs: an **ownerless, cause-only `ensure`** (per [§Active owners and causes](#active-owners-and-causes) — the focus/reconnect scans warm entries with a cause and no owner; unowned entries stay GC-eligible). What is missing is not resource machinery but a **route-plan-level** verb that runs a whole route's `:resources` plan in this warm mode before the user commits to navigating — so that hovering a link warms the destination's data (React Router's `<Link prefetch>`, TanStack Router's `preload`/intent). App-space hover-`ensure` works per-resource today; a route-plan preload in app space would force every link site to re-derive the destination's params / `:when` / scope precedence, the exact duplication the plan machinery exists to prevent, so the verb belongs to the routing layer, not app code.

**Design to flip post-v1 (attached verbatim so the flip is cheap).** A public `[:rf.route/prefetch target]` event (owned by [012 §Route-plan prefetch](012-Routing.md#route-plan-prefetch--warm-mode-on-hover-preload-post-v1)) that runs the target route's `:resources` plan in **WARM mode** through this artefact's `ensure`: each entry `ensure`s **ownerless** (cause-only — the warmed value is GC-eligible if the navigation never happens), `:blocking?` is **ignored** (a prefetch never blocks), and the route's `:on-match` is **deliberately NOT run** (prefetch warms data only; it does not activate the route or run side effects). Paired surfaces: a `route-link` `:prefetch :intent` opt, one trace op (`:rf.route/prefetched`), and a scorecard row. Deferred — not shipped in v1; the resources side is already warm-capable, and the routing side would add only the plan-level verb. Recorded here (and in 012) so a post-v1 flip is a small additive surface that does not disturb the activation-time `:resources` contract or the `ensure` owner/cause model above.

## Resolved decisions

### Cache scope is fail-closed — `:resolved`

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

### EP-0016 action wave — mutation completion, scoped invalidation, named scope resolvers — `:resolved`

The three [EP-0016](../docs/EP/EP-0016-resource-mutation-completion.md) decisions and their riders are graduated into this Spec (the EP's §Open Issues dispositions govern; accepted 2026-06-11). The decisions, their normative homes here, and their semantic riders:

- **D1 — mutation completion continuations.** Call-site `:reply-to` on `:rf.mutation/execute`; the appended reply map is the canonical `:rf/reply-map` plus mutation-specific facts; delivery is keyed on **acceptance** (any accepted terminal reply fires; stale/suppressed never do); the deterministic phase order is resolve-scope → send → accept/suppress → cache-consequences → instance-settlement → continuation; the cause is `[:mutation <id> <instance>]`. Load-bearing prose: [§Mutation completion continuations](#mutation-completion-continuations--call-site-reply-to). Registration-level `:reply-to` is deferred (EP-0016 issue 1).
- **D2 — per-target scoped invalidation.** `:invalidates` descriptors `{:scope … :tags …}` with per-target scope (`:rf.scope/same` default, `:rf.scope/global`, concrete, or `{:from-db …}`); one invalidation engine; the three-rung lattice (bare-no-scope = loud error, descriptors = the precise path, `:cross-scope? true` = the audited escape requiring `:cause` + privacy-relevant trace + dev/Xray warning). Load-bearing prose: [§Scoped invalidation descriptors](#scoped-invalidation-descriptors-per-target).
- **D3 — named resource-scope resolvers.** `reg-resource-scope` with declared `{:inputs … :resolve …}`; whole-db fn = tooling-marked explicit-cost sugar; `nil` = fail-closed at scope-requiring sites; `{:from-db …}` references resolve at use time; the `[:runtime path]` source is **reserved, not shipped**. Load-bearing prose: [§Named resource-scope resolvers](#named-resource-scope-resolvers-reg-resource-scope).
- **Riders.** R1 populate-as-authoritative-load + `:refetch-populated?` opt-out ([§Populate is an authoritative load](#populate-is-an-authoritative-load)); R2 map-form exact targets as the only public input form, tuple = internal/storage, no migration window ([§Map-form exact resource targets](#map-form-exact-resource-targets)); R3 request-decoration in the Spec 014 managed-HTTP seam ([§Request decoration belongs to the managed-HTTP seam](#request-decoration-belongs-to-the-managed-http-seam-not-the-resource-declaration)). The one-name-per-fact items are resolved inline: the reply `:status` references the [EP-0011](../docs/EP/EP-0011-uniform-async-reply-envelope.md) canonical enum, and the reply `:value` vs durable mutation-instance `:result` layering is documented at [§The uniform reply envelope](#the-uniform-reply-envelope-and-the-canonical-reply-map).

> **EP-0016 related work** (the slice-1 absorption context). The dogfood findings and adjacent work this slice composes with: the feed-invalidation bug (the D2 dogfood acceptance case the descriptor mechanism fixes); the global-scope spelling ambiguity Rider 2's map-form input eliminates at the API boundary; the invalidate-tags fail-closed scope strictness D2 composes with (the bottom rung of the cross-scope lattice); the sub-side scope-mismatch dev warning adjacent to D3's fail-closed nil; the hybrid mutation scope-resolution rule D2's `:rf.scope/same` default cites, now homed in [§Mutation scope is two distinct scopes](#mutation-scope-is-two-distinct-scopes-hybrid); live sub re-keying when resolver inputs change mid-session (answered by the slice-3 route/event/sub integration, not this spec slice); and the tenant-switcher testbed (the un-defer consumer for the reserved `[:runtime path]` source).

### EP-0021 infinite resources — the `:infinite` load-more feed — `:resolved`

The [EP-0021](../docs/EP/EP-0021-infinite-resources.md) Resolved Decisions R1–R8 (Mike, 2026-06-17) are graduated into this Spec as the `:infinite` registration kind. The rulings and their normative homes here:

- **R1 — one scoped entry per feed.** Pages are the ordered durable `:data` vector inside one `:rf.runtime/resources` entry (plus `:page-params` / `:next-page-param` / `:page-error`), **not** N per-page entries and **not** an app-db slice. Load-bearing prose: [§Durable cache shape (R1)](#durable-cache-shape-r1).
- **R2 — no 6th FSM state.** A load-more reuses the existing `:fetching` (refresh-class) transition; a derived **`:fetching-next?`** sub distinguishes a load-more in flight from a whole-feed `:fetching?` refresh. Load-bearing prose: [§Causal event — `:rf.resource/load-more` (R2)](#causal-event--rfresourceload-more-r2).
- **R3 — framework-owned merged list.** `:rf.resource/items` (the headline merged read), `:rf.resource/pages`, `:rf.resource/infinite-state`, and the page-metadata subs are framework-owned + memoised; **`:page->items` is REQUIRED** for any non-vector / enveloped page (loud over guessing). Load-bearing prose: [§Subscription contract](#subscription-contract-the-merged-list-and-page-metadata-r3).
- **R4 — coarse item-mutation invalidation.** A mutation touching an item inside a feed **invalidates the whole feed**; in-place patching is deferred. Load-bearing prose: [§Refetch and invalidation of an infinite feed](#refetch-and-invalidation-of-an-infinite-feed).
- **R5 — `:page-data-schema` is the per-page contract.** It validates one page (the decode target) **and** is the per-page egress/classification contract; `:data-schema` is not used for the accumulated vector. Load-bearing prose: [§Registration — `:infinite`](#registration--infinite).
- **R6 — window-preserving refetch default.** `:refetch` is an explicit per-resource policy; the conservative default preserves the visible window until replacement succeeds; `:refetch-all-pages?` and `:refetch-window` ship as opt-ins from day one. *(Supersedes the EP body's earlier discard-tail default.)* Load-bearing prose: [§Refetch and invalidation of an infinite feed](#refetch-and-invalidation-of-an-infinite-feed).
- **R7 — next-only v1.** The `:next-page-param` / `:prev-page-param` derivation mirror is defined now; the prepend event `:rf.resource/load-prev` is deferred. Load-bearing prose: [§Causal event — `:rf.resource/load-more` (R2)](#causal-event--rfresourceload-more-r2).
- **R8 — `:request` reserved-ctx page extension.** The page context rides the already-reserved `:request` ctx (`{:rf.resource/page-param p :rf.resource/page-index i}`); non-infinite requests still get a nil/empty ctx; **no new 3-arity**. Load-bearing prose: [§Registration — `:infinite`](#registration--infinite).

## Cross-references

- [EP-0003 — Resource Queries](../docs/EP/EP-0003-resource-queries.md) — the originating enhancement proposal; full rationale, prior-art benchmark (TanStack Query / RTK Query / SWR / `re-frame-query`), slice plan, and the deferred GraphQL phase.
- [EP-0021 — Infinite Resources And Load-More Feeds](../docs/EP/EP-0021-infinite-resources.md) — the `:infinite` registration kind; the page-param model, the durable page-vector entry, the `:rf.resource/load-more` causal event, the merged-list + page-metadata subscription family, and the Resolved Decisions R1–R8 this spec's [§Infinite resources and load-more feeds](#infinite-resources-and-load-more-feeds) encodes.
- [Managed-Effects](Managed-Effects.md) — the nine-property managed-effect contract; **§The uniform reply envelope** is the canonical normative home of the reply map, the closed `:status` taxonomy, the `:work/id` correlation rule, and the mandatory stale-suppression boundary resources + mutations complete through (see [§The uniform reply envelope and the canonical reply map](#the-uniform-reply-envelope-and-the-canonical-reply-map)).
- [EP-0011 — Uniform Async Reply Envelope](../docs/EP/EP-0011-uniform-async-reply-envelope.md) — the rationale record for the reply envelope; §Resource Reply And Work Ledger / §Mutation Reply are the resource + mutation lowering slices. [EP-0007 — One Name Per Fact](../docs/EP/EP-0007-one-name-per-fact.md) — the rule behind one-attempt-one-`:work/id` and the `:value`-everywhere reply-result spelling (the `:value` / `:data` / `:result` layering).
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
