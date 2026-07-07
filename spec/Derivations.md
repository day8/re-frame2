# Derivations And Processes

> **Type:** Reference
> The unifying conceptual frame for every **declared fact and process over the frame fold** in re-frame2 — subscriptions, runtime subscriptions, flows, resources, route facts, and machine selectors. Names the one vocabulary those five plural source forms lower to: declared inputs, an output fact, a storage class, an evaluation policy, and a lifecycle/owner. This is the **derivation/process analogue of [Managed-Effects.md](Managed-Effects.md) (effect surfaces) and [Runtime-Subsystems.md](Runtime-Subsystems.md) (durable-state surfaces)** — Managed-Effects names the shape every framework-owned *effect* satisfies, Runtime-Subsystems names the shape every framework-owned *durable-state subtree* satisfies, and this doc names the shape every *declared derived value or stateful process* satisfies.
>
> Specified by [EP-0014](../docs/EP/EP-0014-derivation-and-process-algebra.md). **Slice-1 scope: vocabulary, the spec model, and internal registration metadata — no new public authoring or accessor primitive.** The graph-inspection contract here is the *shape* an internal accessor produces; the public name is deferred (see [§Graph inspection — internal but structured](#graph-inspection--internal-but-structured)).

## Why this doc exists

re-frame2 already explains six related mechanisms with six separate vocabularies:

- **subscriptions** derive ephemeral view values ([006-ReactiveSubstrate](006-ReactiveSubstrate.md));
- **runtime subscriptions** derive ephemeral values from framework-owned `runtime-db` ([006](006-ReactiveSubstrate.md), [Runtime-Subsystems](Runtime-Subsystems.md));
- **flows** materialize derived values into `app-db` ([013-Flows](013-Flows.md));
- **resources** fetch, cache, refresh, and GC remote state ([016-Resources](016-Resources.md));
- **routes** materialize match facts, params, and transition state ([012-Routing](012-Routing.md));
- **machines** materialize process snapshots and expose selectors over them ([005-StateMachines](005-StateMachines.md)).

Each distinction is real. A resource is not a pure subscription; a machine is not a flow. The problem is not the distinctions — it is that a large SPA needs *cross-cutting* answers the separate vocabularies make harder than they should be:

- Which facts does this page depend on?
- Which event, route, resource, or machine state can change this value?
- Is this value durable frame state, an ephemeral cache entry, host-transient state, or server-owned data represented locally?
- Is this computation lazy, event-boundary eager, reply-driven, route-driven, or scheduled?
- Which owner keeps it alive, and which teardown boundary releases it?
- Can [Xray](../tools/xray/spec/README.md) or a conformance test enumerate the graph without executing arbitrary app code?

Without one vocabulary, tools and maintainers stitch together partial graphs — subscription topology here, flow topology there, resource owners elsewhere, route facts in routing, machine selectors in machine code. That fragmentation is unnecessary: **all six surfaces are declared facts and processes over the same [frame fold](#the-frame-fold).**

**A derivation/process is a declared way to compute a fact from inputs — with `derive`, `materialize`, `fetch`, and `synchronize` understood as storage and evaluation policies over one declared dependency graph, not as separate runtime kinds.** The authoring APIs stay plural and ergonomic; the inspection, testing, and specification vocabulary becomes singular.

Naming the shape turns six ad-hoc cross-references into **gradeable instances**: a new declared-fact surface is admitted by answering the same five questions every node answers (inputs, output, storage, evaluation, lifecycle/owner), and a tool that understands only two superkinds — `:derivation` and `:process` — can classify every node in the graph.

## Scope — vocabulary, not a new authoring API

This doc is the named normative home for the **vocabulary, the node/edge model, the classification tables, the static/live rule, and the whole-value law**. It does **not** mint a public authoring primitive.

- There is **no** `reg-fact`, **no** `reg-derivation`, and **no** stable public graph-accessor name in this slice. The plural source forms — `reg-sub`, `reg-flow`, `reg-resource`, `reg-route`, `reg-machine` — remain the authoring surface (the *project-before-primitive* discipline: prove the projection from existing forms before minting a primitive). A future source form, if any, is a separate EP.
- This doc does **not** change subscription cache semantics ([006](006-ReactiveSubstrate.md)), flow drain integration ([013](013-Flows.md)), resource HTTP scope or snapshot shape ([016](016-Resources.md)), route ranking ([012](012-Routing.md)), or machine snapshot shape ([005](005-StateMachines.md)). It names the **common view** those owners' surfaces lower to. Each owning Spec remains canonical for its mechanism; this doc is canonical only for the shared vocabulary and the whole-value law.
- The runtime shapes (the node/fact/edge/storage-class/evaluation-policy/lifecycle Malli forms) are **projected** into [Spec-Schemas §`:rf/derivation-node`](Spec-Schemas.md#rfderivation-node-rffact-rfderivation-edge-rfstorage-class-rfevaluation-policy-rflifecycle-the-derivationprocess-algebra-ep-0014), which defers here for semantics.

This is downstream of, and complementary to, the [Runtime-Subsystems](Runtime-Subsystems.md) contract: Runtime-Subsystems organizes *where durable framework state lives* (the children of `runtime-db`); this doc organizes *how every declared fact — durable or ephemeral, app or framework — relates to the frame fold*. A `:runtime-db`-storage node here is still graded by its owning runtime subsystem's projection policy there; this doc names the storage class, Runtime-Subsystems / [EP-0006](../docs/EP/EP-0006-runtime-subsystem-contract.md) remains the authority on how each `runtime-db` key is projected, hydrated, and torn down.

## The frame fold

A **frame fold** is the ordered sequence of frame-state values produced by applying causal inputs to a frame:

```text
frame-state-0
  -- causal input 1 --> frame-state-1
  -- causal input 2 --> frame-state-2
  -- causal input 3 --> frame-state-3
```

Each frame-state value has the two durable partitions of [002-Frames §The two partitions](002-Frames.md): application-owned `app-db` (`:rf.db/app`) and framework-owned `runtime-db` (`:rf.db/runtime`). Host-transient state — abort handles, timers, scroll caches, dirty-check side tables — may *support* the fold but is **not** durable frame state ([002 §Durable vs transient](002-Frames.md#durable-vs-transient)).

Every fact and process this doc names is a declared relationship over this fold: a value read at a point in it, a value derived from other values in it, or a stateful process that materializes into it and emits commands that later return causal inputs to it.

## The vocabulary

### Fact

A **fact** is a named readable value at a point in the frame fold. A fact may be:

- a **source fact** — an `app-db` path, a `runtime-db` path, a route param, or a resource key;
- a **derived fact** — a subscription value;
- a **materialized fact** — a flow output in `app-db`;
- a **process fact** — a resource entry status or a machine snapshot selector.

A fact has **one canonical identity** in the layer where it is named (the one-name-per-fact rule of [EP-0007](../docs/EP/EP-0007-one-name-per-fact.md), applied graph-wide). That identity is what tools use for graph edges and what docs use when explaining the source of a value. Canonical node/fact identity uses the [EP-0012](../docs/EP/EP-0012-path-optics-and-canonical-forms.md) path/identity rules directly (see [Conventions §The `:rf/path` algebra](Conventions.md#the-rfpath-algebra) and [Spec-Schemas §`:rf/path`](Spec-Schemas.md#rfpath-rfpath-template-the-path-algebra-ep-0012)).

### Source form and algebra view

A **source form** is the API shape an author writes: `reg-sub`, `reg-flow`, `reg-resource`, `reg-route`, `reg-machine`, or a future manifest form. Source forms remain optimized for humans and migration.

The **algebra view** is the normalized data view tools and specs use. It is derived from source forms at registration time (the static graph) and from runtime cache entries (the live graph). The algebra view is **registrar-derived** — assembled from registration metadata, never from re-executing a source form (see [§The registrar-derived discipline](#the-registrar-derived-discipline)).

A single source form MAY lower to **more than one** algebra node. A resource declaration, for example, lowers to a process node for the cache entry **and** to derived read facts (state, data, loading flag, error projection) over that entry.

### Derivation

A **derivation** computes an output fact from declared inputs with a pure whole-value function. A derivation has no durable private state beyond optional memoization and, if materialized, its output value.

**Subscriptions and flows are derivations.** The difference between them is not the function — it is policy (storage, evaluation, lifecycle) over the same dependency graph (see [§Worked equivalence](#worked-equivalence--one-function-two-policies)).

### Process

A **process** is a derivation with state, lifecycle, and commands over time. It may react to causal events, async replies, route entry/exit, timers, or machine transitions. It may materialize state into `runtime-db`, hold host-transient handles, and emit commands that later return causal replies.

**Resources and machines are processes.** A route transition is also process-like: it materializes route facts, invokes loaders/resources, and suppresses stale continuations by navigation token.

Processes may produce **command** outputs, but commands are **not facts**. A command becomes part of the causal graph only when the host returns a reply event ([EP-0011](../docs/EP/EP-0011-uniform-async-reply-envelope.md)) or a process transition records durable state.

### Declared input

A **declared input** is a data description of a dependency. It names *what* a derivation or process reads without requiring a tool to inspect arbitrary function bodies.

Inputs are written as data forms. The following are the normative vocabulary; specs MAY add narrower aliases that lower to these:

```clojure
[:db path]                  ;; read the app-db partition at path
[:runtime path]             ;; read the runtime-db partition at path
[:frame-state path]         ;; framework-internal cross-partition read (see below)
[:sub query-vector]         ;; depend on another subscription's value
[:param key]                ;; a query/route/resource/machine parameter
[:scope scope-id-or-expr]   ;; a resource cache scope
[:route projection]         ;; a slice of the route fact
[:resource resource-ref]    ;; a resource read-fact dependency
[:machine machine-ref proj] ;; a machine snapshot selector
[:fact fact-id]             ;; a named fact
[:event event-id]           ;; a causal-event trigger (process inputs)
[:reply reply-kind]         ;; an async-reply trigger (process inputs)
[:timer timer-id]           ;; a scheduled trigger (process inputs)
```

`[:db path]` reads the app-db partition; `[:runtime path]` reads the runtime-db partition. `[:frame-state path]` is **reserved for framework internals** that read across the product value; application source forms SHOULD prefer the partition-specific inputs.

A declared input is either **static** (known from registration alone) or **parametric** (its realized edges require a concrete query vector, route match, resource params, or machine instance before they are known). See [§Static and live graphs](#static-and-live-graphs).

### Output and materialization

An **output** is the fact or address a derivation/process produces:

```clojure
[:fact :cart/total]
[:db [:cart :total]]
[:runtime [:rf.runtime/resources :entries scoped-key]]
[:runtime [:rf.runtime/machines :snapshots :upload/main]]
[:host-transient [:rf.http/in-flight work-id]]
```

- An **ephemeral output** has a fact identity but **no durable frame-state address** — it is cached or returned, never written to durable frame state.
- A **materialized output** has a durable address — an `app-db` path or a `runtime-db` path. A materialized derivation installs the *whole value* at its output address (the whole-value default, [§The whole-value law](#the-whole-value-law)).
- A **process** may have both: a materialized snapshot plus ephemeral selectors over that snapshot.

`:materialized?` is `true` exactly when the node has a durable output address.

### Storage class

A **storage class** declares **where the output or supporting process state lives locally**. Storage is *always the local representation home*; remote authority is a separate axis (see [§Authority — the remote axis](#authority--the-remote-axis)).

| Storage class | Meaning |
|---|---|
| `:ephemeral` | A cache/reaction value derived from frame state. Not durable frame state; released by its lifecycle. |
| `:app-db` | Application-owned durable frame state under the `:rf.db/app` partition. |
| `:runtime-db` | Framework-owned durable frame state under the `:rf.db/runtime` partition. |
| `:host-transient` | Host handles or caches outside durable frame state. They must be derived, cleared, or reconciled at lifecycle boundaries. |

Rules:

1. `:ephemeral` values MUST be recomputable or disposable without changing durable frame state.
2. `:app-db` values are application-owned durable facts, subject to app schema, SSR, restore, and time-travel rules ([002](002-Frames.md), [010](010-Schemas.md), [011](011-SSR.md)).
3. `:runtime-db` values are framework-owned durable facts, subject to runtime-subsystem authority, projection, SSR, restore, and teardown rules ([Runtime-Subsystems](Runtime-Subsystems.md), [EP-0006](../docs/EP/EP-0006-runtime-subsystem-contract.md)).
4. `:host-transient` values MUST declare a teardown or reconciliation boundary. They MUST NOT be the only copy of a fact required for replay or restore ([002 §Durable vs transient](002-Frames.md#durable-vs-transient)).

The storage classes align with — and do not replace — the [EP-0006](../docs/EP/EP-0006-runtime-subsystem-contract.md) runtime-subsystem projection tiers: a `:runtime-db` output is still graded durable-serialized or local-subscribable per key by its owning subsystem's projection policy, and `:host-transient` here is the same tier EP-0006 names. `:ephemeral` values have no subsystem row because they are not durable state.

> **`:remote` is not a storage class.** `:storage` always names the *local* representation home (one of the four above), and `:authority` is a separate axis (next section). "Remote" is only prose shorthand for "a fact whose authority is external"; it never names where a value is stored.

### Authority — the remote axis

A node whose source of truth lives **outside the frame** declares an `:authority` map *alongside* its local `:storage`:

```clojure
{:kind      :remote
 :system    :server
 :transport :rf.http/managed}
```

- `:storage` always answers "where does the local representation live?" — for resources, `:runtime-db` (the cache entry) plus `:host-transient` (in-flight handles).
- `:authority` answers "whose fact is this, really?" — `{:kind :remote …}` says the authority is external.
- `:transport` inside `:authority` is a **projection of the [Spec 016](016-Resources.md) registration fact, never a second authoritative home** — it mirrors the registered transport so a graph reader can see it without re-resolving the registry. A mirror is a recomputable projection of its source (the [Runtime-Subsystems derived rule 2](Runtime-Subsystems.md) one-authoritative-home discipline applied to graph metadata), not a co-equal home that can drift.

`:authority :remote` is **not a license to hide state**. The local cache entry, in-flight row, owner index, and selectors still declare their local storage and lifecycle. Resources are the proof case: `:authority {:kind :remote …}` + `:storage :runtime-db` describes a shipped resource exactly.

### Evaluation policy

An **evaluation policy** declares *when* the derivation/process runs:

| Policy | Meaning |
|---|---|
| `:on-demand` | Evaluated when a reader subscribes or requests the fact. |
| `:after-event` | Evaluated during an event drain after handlers have produced pending state. |
| `:on-reply` | Evaluated when an async reply envelope (or equivalent causal completion) arrives. |
| `:on-route` | Evaluated on route activation, egress, or route-match change. |
| `:on-transition` | Evaluated as part of a machine/process transition. |
| `:scheduled` | Evaluated because a timer or scheduler delivered a causal input. |
| `:manual` | Evaluated only when an explicit invalidation, refresh, ensure, or recompute command asks for it. |

A node's `:evaluation` is a **single policy or a set** — a process with more than one trigger (e.g. a resource that runs on route activation, manual refresh, *and* async reply) carries the set `#{:on-route :manual :on-reply}`.

Rules:

1. `:on-demand` derivations MUST NOT cause durable state changes merely because a view read them. (Reading a resource selector does not start resource work.)
2. `:after-event` derivations run inside the event drain and participate in the event's atomicity rules ([013 §Drain integration](013-Flows.md#drain-integration)). For an already-registered flow this means **same-commit materialization** — there is no general one-event staleness (the one exception is a flow registered mid-event, [013 §Sequencing — the one-event lag](013-Flows.md#sequencing--the-one-event-lag)).
3. `:on-reply` processes run only from causal reply events or equivalent framework-owned completions; **stale replies MUST be suppressible by declared identity** ([016](016-Resources.md), [EP-0011](../docs/EP/EP-0011-uniform-async-reply-envelope.md)).
4. `:on-route` processes MUST declare the route fact, nav-token, owner, or route lifecycle boundary they depend on ([012](012-Routing.md)).
5. `:scheduled` processes MUST separate durable scheduling facts from host-transient timer handles.
6. `:manual` processes MUST expose the event, command, or API that causes invalidation/refetch/recompute.

### Lifecycle and owner

A **lifecycle** declares who keeps the fact/process alive and who releases it:

| Lifecycle | Typical owner / release boundary |
|---|---|
| `:subscription-cache-entry` | The concrete query vector's readers keep it alive; ref-count disposal releases it. |
| `:frame` | The frame owns it; `destroy-frame!` releases it. |
| `:route` | The active route/nav-token owns it; route exit or supersession releases it. |
| `:scoped-resource-key` | A scoped resource key owns the cache entry; owners, freshness policy, and GC release it. (The lifecycle **category**; the concrete key value is the `:resource/key` data field — one name per fact.) |
| `:machine-instance` | A singleton or spawned actor snapshot owns it; machine destroy releases it. |
| `:host-root` | A host root or adapter owns it; adapter/host disposal releases it. |

Lifecycle is part of the fact's contract — **a graph that shows data dependencies but hides ownership is incomplete.** Every node declares lifecycle, optionally with an explicit `:owner`:

```clojure
{:lifecycle :subscription-cache-entry :owner [:sub-query [:cart/total]]}
{:lifecycle :frame                    :owner [:frame :checkout]}
{:lifecycle :route                    :owner [:route :route/article nav-token]}
{:lifecycle :scoped-resource-key      :owner [scope :article/by-slug {:slug "welcome"}]}
{:lifecycle :machine-instance         :owner [:machine :upload/main]}
```

For processes, **owner and cause are distinct**. The *owner* keeps the process or cache entry alive; the *cause* explains why work happened. A button click that refreshes a resource is usually a cause; a route, machine, SSR request, or explicit lease is usually an owner. (Resources formalize this as active owners vs causes — [016](016-Resources.md).)

## The node shape

Every derivation/process SHOULD be describable by an algebra view equivalent to:

```clojure
{:id          :cart/total
 :kind        :derivation                      ;; superkind: :derivation | :process
 :source-form {:kind :reg-sub :id :cart/total} ;; which source form it lowered from
 :inputs      [[:sub [:cart/items]]
               [:sub [:pricing/discounts]]]    ;; declared inputs, or :parametric
 :output      [:fact :cart/total]
 :storage     :ephemeral                        ;; the LOCAL storage class
 :authority   nil                               ;; the remote axis (present only when external)
 :evaluation  :on-demand                        ;; policy or set of policies
 :lifecycle   :subscription-cache-entry
 :materialized? false
 :derive      #'app.cart/sum-cart               ;; opaque fn token — see below
 :schema      :app.money/amount
 :source      {:ns "app.cart" :file "src/app/cart.cljs" :line 42}}
```

**Two superkinds, refinable.** Every `:kind` is one of exactly two closed superkinds: `:derivation` or `:process` (the closed `DerivationKind` enum — [Spec-Schemas §`:rf/derivation-node`](Spec-Schemas.md#rfderivation-node-rffact-rfderivation-edge-rfstorage-class-rfevaluation-policy-rflifecycle-the-derivationprocess-algebra-ep-0014)). The refined kinds — `:resource-process`, `:route-fact`, `:machine-process`, `:machine-selector` — are *informative refinements*, carried on the separate **`:refinement`** axis, **never in `:kind`**: specs MAY add refinements, but a tool that understands only the two superkinds MUST still be able to classify every node by reading `:kind` alone. A refinement always refines its node's superkind (`:resource-process` / `:route-fact` / `:machine-process` refine `:process`; `:machine-selector` refines `:derivation`).

**Functions are opaque.** Function values (`:derive`, an input-producer) MAY be represented by symbols, source coordinates, registry metadata, or opaque implementation tokens. The graph contract is about dependencies, storage, evaluation, and ownership — it does **not** require serializing executable functions.

### Fact identity

Fact identity MUST be stable enough for tools, tests, and docs:

- a **subscription** fact is identified by its query vector when concrete, and by its sub id when static;
- a **flow** fact is identified by its flow id and output path;
- a **route** fact is identified by the route slice or projection, e.g. `:rf/route` / `:rf.route/params` ([012](012-Routing.md));
- a **resource** fact is identified by `[cache-scope resource-id canonical-params]` when concrete, and by resource id when static ([016](016-Resources.md), [Spec-Schemas §`:rf/scoped-resource-key`](Spec-Schemas.md#rfscoped-resource-key-rfresource-entry-rfresource-work-record-resources-spec-016));
- a **machine** fact is identified by machine id or instance id plus snapshot or selector identity ([005](005-StateMachines.md)).

When a source form is parametric, the **static graph MUST mark the edge set `:parametric`** instead of inventing all possible concrete edges.

## Static and live graphs

Graph inspection has two modes.

- A **static graph** (`:mode :static`) is derived from registrations and source forms. It can show literal `:<-` subscription edges, flow input paths, resource declarations, route metadata, and machine declarations.
- A **live graph** (`:mode :live`) is derived from a frame at a point in time. It can include concrete subscription query vectors, realized parametric input edges, active resource keys, route owners, machine instances, in-flight work, and current lifecycle state.

For a parametric subscription, the static graph reports the source form rather than guessing edges:

```clojure
{:id :article/page
 :kind :derivation
 :inputs :parametric
 :input-producer #'app.article/article-page-inputs}
```

and the live graph reports the realized edges per concrete query vector:

```clojure
{:id [:sub [:article/page "welcome"]]
 :kind :derivation
 :inputs [[:sub [:article/by-slug "welcome"]]
          [:sub [:comments/for-article "welcome"]]]
 :lifecycle :subscription-cache-entry}
```

### The don't-execute rule (EP-0014 issue-3 disposition)

**Static inspection NEVER invokes a node's param functions or scope functions.** It reads declarations; it does not run them. No side effects, no nondeterminism, and no hidden runtime assumptions may enter static analysis. Static graph tools MUST NOT pretend every possible parametric edge is known before concrete query vectors exist — a parametric edge set is reported as `:parametric`, and only the live graph (which observes realized inputs, not executed declarations speculatively) shows concrete edges.

### Named-resolver enrichment (EP-0014 issue-3 disposition)

A **named resolver** turns part of a parametric declaration into a *static fact*. When a resource scope is an [EP-0016](../docs/EP/EP-0016-resource-mutation-completion.md) `{:from-db <resolver-id>}` reference rather than an inline function, the static graph reports the **resolver id and its declared inputs** even while the params stay `:parametric`:

```clojure
{:id :article/by-slug
 :kind :process                                          ;; the closed superkind
 :refinement :resource-process                           ;; the informative refinement
 :inputs [[:param :slug]
          [:scope {:from-db :session/current-tenant}]]   ;; named resolver — static!
 :scope-resolver {:id :session/current-tenant
                  :inputs [[:db [:session :tenant-id]]]}  ;; declared inputs are static facts
 :params :parametric}
```

The resolver's declared inputs ([016 §Named resource-scope resolvers](016-Resources.md#named-resource-scope-resolvers-reg-resource-scope)) appear in the static graph because they are declared, not executed — this is exactly the static visibility the don't-execute rule otherwise costs an inline function.

## Graph inspection — internal but structured

Slice-1 ships **no public graph-accessor name**. It ships a *structured shape* an internal accessor produces, so [Xray](../tools/xray/spec/README.md) and the conformance fixtures can consume it from day one and the public name can be stabilized only after the shape survives real use (the *project-before-primitive* discipline applied to accessors).

The shape carries:

- `:mode` — `:static` or `:live`;
- **canonical node ids** under the [EP-0012](../docs/EP/EP-0012-path-optics-and-canonical-forms.md) identity rules;
- **explicit edge records** — `{:from <node-id> :to <node-id> :role <role>}` (roles: `:input`, `:param`, `:selector`, …);
- **source-form metadata** — `{:kind :reg-sub :id …}`;
- the **storage / evaluation / lifecycle classifications** named above;
- **parametric markers** for unrealized edge sets;
- **redaction metadata** (next section).

A representative graph view:

```clojure
{:mode :live
 :frame :checkout
 :nodes
 {[:sub [:cart/total]]
  {:kind :derivation
   :storage :ephemeral
   :evaluation :on-demand
   :lifecycle :subscription-cache-entry
   :source-form {:kind :reg-sub :id :cart/total}}

  [:flow :cart/materialized-total]
  {:kind :derivation
   :storage :app-db
   :evaluation :after-event
   :lifecycle :frame
   :output [:db [:cart :total]]}

  [:resource [[:rf.scope/global] :article/by-slug {:slug "welcome"}]]
  {:kind :process
   :storage :runtime-db
   :authority {:kind :remote :system :server :transport :rf.http/managed}
   :evaluation #{:on-route :on-reply :scheduled :manual}
   :lifecycle :scoped-resource-key
   :output [:runtime [:rf.runtime/resources :entries
                      [[:rf.scope/global] :article/by-slug {:slug "welcome"}]]]}}

 :edges
 [{:from [:sub [:cart/items]]                                   :to [:sub [:cart/total]] :role :input}
  {:from [:runtime [:rf.runtime/routing :current :params :slug]] :to [:resource [[:rf.scope/global] :article/by-slug {:slug "welcome"}]] :role :param}
  {:from [:machine :upload/main]                                 :to [:sub [:upload/progress]] :role :selector}]}
```

An Xray panel renders this as **one** graph even though the underlying runtime mechanisms are route state, resource cache, and subscription cache.

### One accessor, two projections (EP-0014 issue-1 disposition)

The static graph accessor and the live graph accessor are **projections of one registry** — never two overlapping internal accessors. The graduation gate: the public name ships only when a consumer **beyond** the two named first consumers (Xray, conformance fixtures) needs it, with [API](API.md)-facade classification recorded per name at graduation (the diff-time facade-export rule).

### Redaction metadata (EP-0014 issue-1 disposition; EP-0015)

Graph payloads carry source coordinates and value summaries, which are **egress-bearing once any tool ships them off-box**. The graph SHOULD be useful **without** exposing sensitive raw values: it MAY elide large data, redact sensitive params/scopes, and summarize functions, composing through the single shared `rf/elide-wire-value` walker ([009 §Privacy](009-Instrumentation.md#privacy--sensitive-data-in-traces), [015-Data-Classification](015-Data-Classification.md), [Managed-Effects §5](Managed-Effects.md#5-sensitive--large-composition)). Redaction MUST NOT lose graph *structure* — a redacted param is still an edge.

A live **resource** node carries its sensitive scope/params not only in a value-bearing summary field but in its **identity** — the concrete scoped key `[cache-scope resource-id canonical-params]` ([§Fact identity](#fact-identity)) that is the node key (`[:resource <scoped-key>]`), the node `:id`, and is embedded in the `:output` runtime path, the realized `:inputs` `[:scope …]` / `[:param …]`, and the in-flight `:work-ledger :record :resource/key`, with route-owned activations naming it on a `:param` edge endpoint. A value-path egress walk (which matches a frame's declared `:sensitive` app-db *paths*) is structurally blind to these identity-embedded secrets. So a tool shipping the **live** graph off-box MUST additionally project each scoped key's secret-bearing **scope** and **params** components into **stable opaque handles** (deterministic so the same key maps to the same handle — connectivity survives; one-way so the raw scope/params never cross the wire), preserving the non-sensitive registration `resource-id` so a tool still sees *which* resource the node is, and applying the **same** projection consistently to the node keys, the `:output` / `:inputs` / `:work-ledger` identity positions, **and** every edge endpoint that names a resource node — a redacted resource node is still a node, and the edges naming it still connect.

### The graph-assembly composer (EP-0014 slice-7)

The five algebra-view siblings each project **one family** of nodes (the [subscriptions](#subscriptions-expose-algebra-views), [flows](#flows-expose-algebra-views), [resources](#resources-expose-process-nodes), [routes](#routes-expose-algebra-views), and [machines](#machines-expose-algebra-views) sections above). The graph-inspection helper is the **composer** that stitches those per-family projections into the single `{:mode :nodes :edges}` view a tool reads — the last reference-implementation step (`re-frame.derivation.graph` in the reference implementation; [EP-0014 §Reference Implementation / Bead Plan](../docs/EP/EP-0014-derivation-and-process-algebra.md#reference-implementation--bead-plan) item 7). The composer is a **projection over the five sibling projections**; it adds no new fact identity and re-runs no source form (the [registrar-derived](#the-registrar-derived-discipline) discipline composes upward).

The assembly is mechanical and deterministic:

- **Nodes** are the union of every present family's projection, each keyed by its **canonical node id** ([§Fact identity](#fact-identity)) lifted into the family-tagged form a tool draws edges between — `[:sub …]` / `[:flow …]` / `[:resource …]` / `[:machine …]` and the route fact `:rf/route` (a static route node is keyed by its source-form id so per-route resource edges are not collapsed onto the one slice). Each node is the sibling's algebra view **verbatim**, carrying a `:rf/family` tag so a tool can group or filter.
- **Edges** are derived from the nodes' declared projection, never by executing anything (the [don't-execute rule](#the-dont-execute-rule-ep-0014-issue-3-disposition)): a node's `[:sub …]` declared inputs become `:input` edges (resolved to the upstream subscription node — by sub-id in the static graph, by concrete query vector in the live graph); a route's `:resource-edges` ride through as `:param` edges; and a machine `:process` draws a `:selector` edge **only** to the machine-selector subscription nodes that read **that** machine — the selector's target machine ids are mined from its static `[:rf/machine machine-id …]` / `[:rf/machine-has-tag? machine-id …]` inputs (the `machine-selector-targets` extractor), so a multi-machine app draws each `:selector` edge from exactly the machine the selector names, never the cross product of every machine against every selector. A **`:parametric`** input set contributes **no static edge** — its realized `:input` edges appear only in the live graph (where the live sub-cache node's inputs are concrete `[:sub q]` forms — exactly the edges the static graph cannot enumerate).

**Composition is present-family-only.** The four optional siblings live in separate artefacts core does not depend on, so a static cross-artefact `:require` would defeat their bundle-isolation (and break a core-only build). The composer reaches each optional family through a **contributor seam** — a `{family {:static-fn :live-fn :live-shape}}` map. On the JVM the default contributors auto-resolve each sibling on the classpath; on CLJS the consuming tool (which already `:require`s the siblings it has) supplies the map. A family whose artefact is **absent** simply contributes no nodes — the same no-flows / no-resources story each sibling already honours. The composer is itself bundle-isolated (production never loads it; its body DCEs), and like the siblings' CLJS-side fns it ships **no public accessor** and **no `re-frame.core` facade export** — it is the internal structured shape the deferred public accessor will one day produce, consumed first by [Xray](../tools/xray/spec/README.md) and the conformance fixtures (the [graduation gate](#one-accessor-two-projections-ep-0014-issue-1-disposition) governs when a public name ships).

## The whole-value law

**Whole-value derivation is mandatory.** A conforming implementation MUST be correct if every derivation recomputes its entire output from its declared inputs. This is the semantic floor:

```clojure
(= (derive inputs context)
   (read-output-after-recompute inputs context))
```

Memoization, equality pruning, dependency tracking, dirty checks, and deltas are **implementation techniques**. They MAY improve performance but MUST NOT change the observable value. Whole-value output is the default for both ephemeral and materialized outputs: the derivation returns the entire next value of the output fact; a materialized derivation installs that whole value at its output address.

### The optional delta law (semantic-only in slice-1)

A derivation MAY one day provide a delta step as an optimization:

```clojure
{:id :large-grid/visible-rows
 :derive     #'app.grid/visible-rows
 :step-delta #'app.grid/visible-rows-delta}
```

The required law is that delta execution **commutes** with whole-value recomputation:

```text
derive(apply-input-delta(inputs, delta-in), context)
=
apply-output-delta(derive(inputs, context),
                   step-delta(derive(inputs, context), delta-in, context))
```

**Slice-1 states this law and ships no executable delta protocol.** Whole-value derivation *is* the contract; the delta representation is deferred. The rule: **any mechanism that later supports deltas MUST satisfy this law.** If the delta path is absent, disabled, or rejected by conformance, whole-value derivation remains correct. The home for the law is here, cited by [006](006-ReactiveSubstrate.md) and [013](013-Flows.md); the law is about derivation *semantics*, not about any one mechanism or about inspection.

## Errors and diagnostics

A graph node SHOULD carry source coordinates, doc text, schema metadata, sensitivity/size metadata, and runtime ownership when available. Structured errors SHOULD be **attributable to algebra identities** rather than only to low-level functions:

- unknown input fact;
- cycle in a derivation graph that requires acyclic evaluation;
- illegal storage write for the source form;
- missing lifecycle owner;
- unresolved resource scope;
- stale reply suppressed;
- delta law check failed.

The error *vocabulary* remains owned by the relevant specs (the `:rf.error/*` / `:rf.warning/*` catalogue is owned by [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue)). This doc requires only that errors be attributable to graph nodes.

## Worked equivalence — one function, two policies

The clearest illustration of the algebra: the *same* whole-value function, expressed as a subscription and as a flow, differs only in policy.

```clojure
;; Source form A — a subscription
(rf/reg-sub
  :cart/total
  :<- [:cart/items]
  :<- [:pricing/discounts]
  (fn [[items discounts] _] (sum-cart items discounts)))

;; Source form B — a flow, same function
(rf/reg-flow :cart/materialized-total
  {:inputs [[:cart :items] [:pricing :discounts]]
   :output-path [:cart :total]}
  (fn [items discounts] (sum-cart items discounts)))
```

Their algebra views differ only in output, storage, evaluation, and lifecycle:

| | Subscription | Flow |
|---|---|---|
| `:kind` | `:derivation` | `:derivation` |
| `:output` | `[:fact :cart/total]` | `[:db [:cart :total]]` |
| `:storage` | `:ephemeral` | `:app-db` |
| `:evaluation` | `:on-demand` | `:after-event` |
| `:lifecycle` | `:subscription-cache-entry` | `:frame` |
| `:materialized?` | `false` | `true` |
| `:derive` | `#'app.cart/sum-cart` | `#'app.cart/sum-cart` |

`sum-cart` is one whole-value function. The difference between the subscription and the flow is **not the mathematical function; it is policy over the same dependency graph.** That is the whole point of naming the algebra. The full worked source-form/algebra-view pairs for every member — parametric subscriptions, runtime subscriptions, resources (static + live), route facts, machine processes and selectors — are catalogued [below](#side-by-side-catalogue--every-source-form-and-its-algebra-view) and in [EP-0014 §Examples](../docs/EP/EP-0014-derivation-and-process-algebra.md#examples).

## Side-by-side catalogue — every source form and its algebra view

This section is **illustrative, not normative** — the binding rules are the per-member sections below and the [§Conformance](#conformance) list. It collects, in one scannable place, the source form an author writes next to the algebra view it lowers to, for every member of the algebra. Read it as the worked companion to the [node shape](#the-node-shape): each pair shows which axes are *fixed* for that member (the member's identity in the algebra) and which *vary* per registration (its declared `:inputs` and `:output`). A reader-first walkthrough of the same mapping, anchored to the four-homes mental model, lives in the guide chapter [One graph: derivations and algebra views](../docs/core/derivations-and-algebra-views.md).

### Subscription (static `:<-`) → ephemeral derivation

```clojure
;; SOURCE FORM                              ;; ALGEBRA VIEW
(rf/reg-sub :cart/total                     {:id          :cart/total
  :<- [:cart/items]                          :kind        :derivation
  :<- [:pricing/discounts]                   :source-form {:kind :reg-sub :id :cart/total}
  (fn [[items discounts] _]                  :inputs      [[:sub [:cart/items]]
    (sum-cart items discounts)))                           [:sub [:pricing/discounts]]]
                                             :output      [:fact :cart/total]
                                             :storage     :ephemeral
                                             :evaluation  :on-demand
                                             :lifecycle   :subscription-cache-entry
                                             :materialized? false
                                             :derive      #'app.cart/sum-cart}
```

Each literal `:<-` input lowers to a `[:sub query-vector]` edge in declaration order. A **layer-1** `:db` reader, which is handed the whole `app-db` value, lowers conservatively to the app-db projection root `[[:db []]]` ([§Subscriptions expose algebra views](#subscriptions-expose-algebra-views)).

### Parametric subscription → static `:parametric` / live realized edges

```clojure
;; SOURCE FORM
(rf/reg-sub :article/page
  (fn [[_ slug]]                              ;; input function — edges depend on `slug`
    [[:article/by-slug slug]
     [:comments/for-article slug]])
  (fn [[article comments] [_ slug]]
    {:slug slug :article article :comments comments}))
```

```clojure
;; STATIC VIEW                               ;; LIVE VIEW for [:article/page "welcome"]
{:id :article/page                           {:id [:sub [:article/page "welcome"]]
 :kind :derivation                            :kind :derivation
 :inputs :parametric                          :inputs [[:sub [:article/by-slug "welcome"]]
 :input-producer                                       [:sub [:comments/for-article "welcome"]]]
   #'app.article/article-page-inputs          :output [:fact [:article/page "welcome"]]
 :output [:fact :article/page]                :storage :ephemeral
 :storage :ephemeral                          :evaluation :on-demand
 :evaluation :on-demand                       :lifecycle :subscription-cache-entry}
 :lifecycle :subscription-cache-entry}
```

The static graph reports the `:parametric` marker plus the opaque `:input-producer` token — it never runs the input function ([§The don't-execute rule](#the-dont-execute-rule-ep-0014-issue-3-disposition)). The live sub-cache view reports the realized `[:sub q]` edges the static graph cannot enumerate.

### Runtime subscription → ephemeral derivation over `runtime-db`

```clojure
;; SOURCE FORM (framework-internal — subsystem facade, not app code)
(subs/reg-runtime-sub :rf.route/params
  (fn [runtime-db _]
    (get-in runtime-db [:rf.runtime/routing :current :params])))
```

```clojure
;; ALGEBRA VIEW
{:id         :rf.route/params
 :kind       :derivation
 :inputs     [[:runtime [:rf.runtime/routing :current :params]]]
 :output     [:fact :rf.route/params]
 :storage    :ephemeral
 :evaluation :on-demand
 :lifecycle  :subscription-cache-entry}
```

The same ephemeral-derivation classifications as an ordinary subscription; the only difference is the partition the conservative input names — `[:runtime …]` for `reg-runtime-sub`, `[:frame-state …]` for the cross-partition framework-internal `reg-frame-state-sub`.

### Flow → materialized derivation

```clojure
;; SOURCE FORM                              ;; ALGEBRA VIEW
(rf/reg-flow :cart/materialized-total        {:id          :cart/materialized-total
  {:inputs [[:cart :items]                    :kind        :derivation
            [:pricing :discounts]]            :source-form {:kind :reg-flow
   :output-path [:cart :total]}                             :id   :cart/materialized-total}
  (fn [items discounts]                       :inputs      [[:db [:cart :items]]
    (sum-cart items discounts)))                            [:db [:pricing :discounts]]]
                                              :output      [:db [:cart :total]]
                                              :storage     :app-db
                                              :evaluation  :after-event
                                              :lifecycle   :frame
                                              :materialized? true
                                              :derive      #'app.cart/sum-cart}
```

The subscription's exact policy twin — same whole-value function, materialized instead of ephemeral ([§Worked equivalence](#worked-equivalence--one-function-two-policies)). Each `:inputs` path lowers to a `[:db path]` edge (a partition-qualified `[:rf.db/runtime …]` input lowers to `[:runtime …]`); the `:output-path` lowers to the `[:db …]` output address.

### Resource → process (static, then live)

```clojure
;; SOURCE FORM — the :request handler is the THIRD slot (rf2-wvh95f F1)
(rf/reg-resource :article/by-slug
  {:params-schema  [:map [:slug :string]]
   :data-schema    :app/article
   :scope          :rf.scope/from-caller
   :stale-after-ms 60000
   :gc-after-ms    300000}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}
     :decode  :app/article}))
```

```clojure
;; STATIC ALGEBRA VIEW
{:id          :article/by-slug
 :kind        :process                       ;; the closed superkind
 :refinement  :resource-process              ;; the informative refinement
 :source-form {:kind :reg-resource :id :article/by-slug}
 :inputs      [[:param :slug]
               [:scope :rf.scope/from-caller]]
 :output      [:runtime [:rf.runtime/resources :entries]]
 :storage     :runtime-db                     ;; the LOCAL cache home
 :authority   {:kind :remote :system :server  ;; the source of truth is external
               :transport :rf.http/managed}
 :evaluation  #{:on-route :on-reply :scheduled :manual}
 :lifecycle   :scoped-resource-key
 :materialized? true
 :selectors   [:rf.resource/state :rf.resource/data :rf.resource/status
               :rf.resource/loading? :rf.resource/error :rf.resource/has-data?]}
```

```clojure
;; LIVE ALGEBRA VIEW for one scoped key
{:id     [:resource [[:rf.scope/session {:tenant-id "acme"}] :article/by-slug {:slug "welcome"}]]
 :kind   :process
 :inputs [[:scope [:rf.scope/session {:tenant-id "acme"}]] [:param {:slug "welcome"}]]
 :output [:runtime [:rf.runtime/resources :entries
                    [[:rf.scope/session {:tenant-id "acme"}] :article/by-slug {:slug "welcome"}]]]
 :storage :runtime-db
 :authority {:kind :remote :system :server}
 :status  :loaded
 :lifecycle {:kind :scoped-resource-key :owners #{[:route :route/article 17]}}
 :host-transient [[:rf.http/in-flight :work/id-123]]}
```

The split the [§Authority](#authority--the-remote-axis) section names is visible here: `:storage` always names the *local* representation home (`:runtime-db` for the cache entry, `:host-transient` for the live in-flight handle), and `:authority` is the separate external-truth axis. One declaration lowers to **more than one** node — the process node plus its `:selectors` read facts — and reading a selector starts no work ([§Evaluation policy](#evaluation-policy) rule 1).

### Route → route fact (process-like) with an owned resource edge

```clojure
;; SOURCE FORM
(rf/reg-route :route/article
  {:path "/articles/:slug"
   :params [:map [:slug :string]]
   :resources [{:resource :article/by-slug
                :params   (fn [route] {:slug (get-in route [:params :slug])})
                :blocking? true}]})
```

```clojure
;; ALGEBRA VIEW
{:id          :rf/route                       ;; the one slice name; every route shares it
 :kind        :process                        ;; the closed superkind
 :refinement  :route-fact                     ;; the informative refinement
 :source-form {:kind :reg-route :id :route/article}
 :inputs      [[:event :rf.route/navigate]
               [:event :rf.route/transitioned]
               [:event :rf.route/handle-url-change]]
 :output      [:runtime [:rf.runtime/routing :current]]
 :storage     :runtime-db
 :evaluation  :on-route
 :lifecycle   :frame
 :materialized? true
 :resource-edges
 [{:from   [:runtime [:rf.runtime/routing :current :params]]
   :to     [:resource :article/by-slug]
   :role   :param
   :target :parametric                        ;; concrete key needs a live match + scope
   :blocking? true}]}
```

Every route materializes the *same* slice fact `:rf/route`; the per-route id is recorded under `:source-form`. The `:resources` declaration lowers to a route-owned [resource activation edge](#route-owned-resource-activation-edges) whose `:target` stays `:parametric` (the don't-execute rule forbids running the entry's `:params`/`:scope` functions statically).

### Machine → process; its selector → derivation

```clojure
;; SOURCE FORM
(rf/reg-machine :upload/main
  {:initial :idle
   :data    {:progress 0}
   :states  {:idle      {:on {:upload/start {:target :uploading}}}
             :uploading {:entry :start-upload
                         :on {:upload/progress  {:action :record-progress}
                              :upload/succeeded {:target :done}
                              :upload/failed    {:target :failed}}}
             :failed {} :done {}}})
```

```clojure
;; MACHINE PROCESS VIEW                      ;; SELECTOR (a reg-sub over [:rf/machine …])
{:id          :upload/main                   (rf/reg-sub :upload/progress
 :kind        :process                         :<- [:rf/machine :upload/main]
 :refinement  :machine-process                 (fn [snapshot _]
 :source-form {:kind :reg-machine                (get-in snapshot [:data :progress] 0)))
               :id   :upload/main}
 :inputs      [[:event :upload/start]        ;; SELECTOR ALGEBRA VIEW
               [:event :upload/progress]     {:id         :upload/progress
               [:event :upload/succeeded]     :kind       :derivation
               [:event :upload/failed]]       :refinement :machine-selector  ;; a :derivation refinement
 :output  [:runtime [:rf.runtime/machines     :inputs     [[:machine :upload/main [:data :progress]]]
           :snapshots :upload/main]]          :output     [:fact :upload/progress]
 :storage :runtime-db                         :storage    :ephemeral
 :evaluation #{:on-transition}                :evaluation :on-demand
 :lifecycle  :machine-instance                :lifecycle  :subscription-cache-entry
 :materialized? true}                         :derive     #'app.upload/progress}
```

The machine is the stateful **process**; the selector is an ephemeral **derivation** over its materialized snapshot. `:inputs` are every `:on` event key across the state tree, de-duplicated; `:evaluation` gains `:scheduled` if the spec declares any `:after` transition and `:on-reply` if it spawns children. A selector is an ordinary subscription node ([§Subscriptions expose algebra views](#subscriptions-expose-algebra-views)) the [machines tooling](#machines-expose-algebra-views) merely labels with the `:machine-selector` refinement and edges back to its machine with a `:selector` role — machines do **not** become a second subscription system (EP-0014 issue-4).

## Subscriptions expose algebra views

Subscriptions are the first concrete algebra member to expose its view. Every subscription registration — `reg-sub` (the layer-1 `:db` reader, the static `:<-` chain, and the parametric input-fn form), the framework-internal `reg-runtime-sub` and `reg-frame-state-sub`, and every live sub-cache entry — projects to the [node shape](#the-node-shape). The projection is **registrar-derived** (see [§The registrar-derived discipline](#the-registrar-derived-discipline)): the reactive substrate ([006](006-ReactiveSubstrate.md)) keeps the cache, ref-counting, and disposal semantics; the algebra view is assembled from the registration metadata that already exists, never from re-executing a source form.

A subscription is always a **`:derivation`** (never a process — [§Derivation](#derivation)), and every subscription node carries the same five fixed classifications, because a subscription is the canonical ephemeral member of the algebra:

| Axis | Value | Why |
|---|---|---|
| `:kind` | `:derivation` | a pure whole-value computation; no durable private state |
| `:storage` | `:ephemeral` | a cache/reaction value, never written to durable frame state |
| `:evaluation` | `:on-demand` | evaluated when a reader subscribes — a read never causes a durable write ([§Evaluation policy](#evaluation-policy) rule 1) |
| `:lifecycle` | `:subscription-cache-entry` | the concrete query vector's readers keep it alive; ref-count disposal releases it |
| `:materialized?` | `false` | an ephemeral output has a fact identity but no durable address |

The two axes that **vary** per subscription are the declared **inputs** and the **output** fact id:

- **`:output`** is `[:fact <id>]` — the sub id for a static node, the concrete query vector for a live cache entry.
- **`:inputs`** lowers from the registered input-producer kind ([§Declared input](#declared-input); the [006](006-ReactiveSubstrate.md) input-producer discriminator):
  - a layer-1 `:db` reader hands the *whole* `app-db` value to its body, so the conservative declared input is the app-db projection root `[[:db []]]` (a future path-aware source form MAY narrow it; correctness does not depend on the narrowing);
  - a `reg-runtime-sub` reads the runtime-db partition — `[[:runtime []]]`;
  - a `reg-frame-state-sub` reads across both partitions (framework-internal) — `[[:frame-state []]]`;
  - a static `:<-` sub lowers each literal input query-vector to a `[:sub query-vector]` edge, in declaration order (args preserved);
  - a parametric input-fn sub reports the **`:parametric`** marker plus an opaque `:input-producer` token — its realized edge set depends on a concrete query vector and is *not statically enumerable* (the [don't-execute rule](#the-dont-execute-rule-ep-0014-issue-3-disposition); the static graph never runs the input-fn). The **live** sub-cache view reports the realized `[:sub query-vector]` edges per concrete entry — exactly the edges the static graph cannot enumerate.

The node additionally carries the opaque `:derive` body token (never serialized — [§The node shape](#the-node-shape)), the `:source-form` `{:kind :reg-sub :id <id>}`, and the `:source` coordinates / `:schema` / doc when the registration carried them. A live cache-entry node also carries its current `:value` (a value summary, redacted by the graph-inspection helper before egress — [§Redaction metadata](#redaction-metadata-ep-0014-issue-1-disposition-ep-0015)) and its `:ref-count` (the lifecycle evidence the cache-entry owner is kept alive by its readers).

This exposure is *internal registration metadata*, consistent with the slice scope: it ships **no public accessor**. The static and live subscription views live in the bundle-isolated subscription tooling sibling (`re-frame.subs.tooling/sub-algebra-view` and `…/sub-cache-algebra-view` in the reference implementation; production CLJS bundles DCE the bodies), consumed by [Xray](../tools/xray/spec/README.md) and the conformance fixtures — the two named first consumers. They feed the later internal graph-inspection helper ([EP-0014 §Reference Implementation / Bead Plan](../docs/EP/EP-0014-derivation-and-process-algebra.md#reference-implementation--bead-plan) item 7); the public name is deferred until a third consumer needs it (the [graduation gate](#one-accessor-two-projections-ep-0014-issue-1-disposition)).

## Flows expose algebra views

Flows are the second concrete algebra member to expose its view, and the subscription's exact policy twin: the *same* whole-value function, materialized instead of ephemeral (the [§Worked equivalence](#worked-equivalence--one-function-two-policies) above). Every `reg-flow` registration projects to the [node shape](#the-node-shape). The projection is **registrar-derived** (see [§The registrar-derived discipline](#the-registrar-derived-discipline)): [013](013-Flows.md) keeps the per-frame registry, the topological-sort engine, the dirty-check, and the drain-integration semantics; the algebra view is assembled from the flow-map metadata that already exists, never from re-running the `:derive` function.

A flow is always a **`:derivation`** (never a process — [§Derivation](#derivation)), and every flow node carries the same five fixed classifications, because a flow is the canonical *materialized* member of the algebra:

| Axis | Value | Why |
|---|---|---|
| `:kind` | `:derivation` | a pure whole-value computation; the materialized output is its only durable state |
| `:storage` | `:app-db` | the output is materialized into the application-owned app-db partition (flow writes are app-db only — [013 §Input partition](013-Flows.md#input-partition--bare--app-db-rfdbruntime---runtime-db)) |
| `:evaluation` | `:after-event` | evaluated inside the event drain, as the outermost `:after` transform, participating in the event's atomicity ([013 §Drain integration](013-Flows.md#drain-integration); [§Evaluation policy](#evaluation-policy) rule 2) |
| `:lifecycle` | `:frame` | flows are frame-scoped; the frame owns the flow and `destroy-frame!` releases it ([013 §Frame-scoping](013-Flows.md)) |
| `:materialized?` | `true` | the output has a durable app-db address, not just a fact identity |

The two axes that **vary** per flow are the declared **inputs** and the **output** path:

- **`:output`** is `[:db <:output-path>]` — the flow's `:output-path`, the app-db address its whole value materializes into.
- **`:inputs`** lowers each declared `:inputs` path ([§Declared input](#declared-input)), in declaration order:
  - a bare path is an app-db read — `[:db path]`;
  - a partition-qualified `[:rf.db/runtime …rest]` path is a runtime-db read — `[:runtime …rest]` (the binary input syntax — any flow may *read* runtime-db, only the *write* side is reserved to app-db; [EP-0001 §535-551](013-Flows.md#input-partition--bare--app-db-rfdbruntime---runtime-db)).

  Flow inputs are always concrete paths — never `:sub` edges and never the `:parametric` marker — because a flow declares its dependency graph statically as paths, not as an input-producer over a query vector.

Because flows are frame-scoped — the same flow-id may register against two frames with different `:inputs` / `:derive` / `:output-path` — the flow view preserves the frame dimension: it is keyed `{frame-id {flow-id <node>}}`, the same shape as the flow registry it reads. Each node additionally records its owning frame under `:owner` `[:frame <frame-id>]` (the lifecycle owner, distinct from the cause of any one evaluation — [§Lifecycle and owner](#lifecycle-and-owner)), the opaque `:derive` body token (the `:derive` fn, never serialized), the `:source-form` `{:kind :reg-flow :id <id>}`, and the `:source` coordinates / `:schema` / doc when the registration carried them.

This exposure is *internal registration metadata*, consistent with the slice scope: it ships **no public accessor**. The flow view lives in the bundle-isolated flows tooling sibling (`re-frame.flows.tooling/flow-algebra-view` in the reference implementation; a CLJS app that loads the flows artefact but attaches no tool DCEs the body, and the whole flows artefact is already absent from a no-flows app's bundle), consumed by [Xray](../tools/xray/spec/README.md) and the conformance fixtures — the two named first consumers. It reads the per-frame flow registry through the existing public `flows-snapshot` seam and touches neither the registrar write-path nor the flow registration signatures. It feeds the later internal graph-inspection helper ([EP-0014 §Reference Implementation / Bead Plan](../docs/EP/EP-0014-derivation-and-process-algebra.md#reference-implementation--bead-plan) item 7); the public name is deferred until a third consumer needs it (the [graduation gate](#one-accessor-two-projections-ep-0014-issue-1-disposition)).

## Resources expose process nodes

Resources are the third concrete algebra member to expose its view, and the **first `:process`** — the canonical member with remote authority, owners, stale suppression, and host-transient in-flight work over time. Where subscriptions and flows are [derivations](#derivation) (one whole-value function, ephemeral vs materialized policy), a resource is a [process](#process): a derivation *with state, lifecycle, and commands*. Every `reg-resource` registration projects to the [node shape](#the-node-shape). The projection is **registrar-derived** (see [§The registrar-derived discipline](#the-registrar-derived-discipline)): [016](016-Resources.md) keeps the cache, the lifecycle FSM, the scope resolution, the work ledger, and the reply lowering; the algebra view is assembled from the resource-spec metadata that already exists, never from re-running the `:request` function or executing a scope/param resolver (the [don't-execute rule](#the-dont-execute-rule-ep-0014-issue-3-disposition)).

A single resource declaration lowers to **more than one** algebra node ([§Source form and algebra view](#source-form-and-algebra-view)): a `:process` node for the cache entry, **plus** the derived read facts (state, data, loading flag, error projection) over that entry. The read facts are the `:rf.resource/*` passive subscriptions ([016 §Subscriptions](016-Resources.md)); they are ordinary on-demand derivations (already covered by the [subscription view](#subscriptions-expose-algebra-views)), so the resource process node lists them under `:selectors` rather than re-minting them — and reading a selector does **not** start resource work ([§Evaluation policy](#evaluation-policy) rule 1).

A resource's superkind is always **`:process`**; its informative refinement is **`:resource-process`**, carried under `:refinement` ([§Two superkinds, refinable](#the-node-shape): the `:kind` is the closed superkind enum, so a tool that understands only the two superkinds classifies the node by `:kind` alone). Every resource node carries the same fixed classifications, because a resource is the canonical *runtime-db / remote-authority* member of the algebra:

| Axis | Value | Why |
|---|---|---|
| `:kind` | `:process` | a derivation with state, lifecycle, and commands over time ([§Process](#process)); refinement `:resource-process` under `:refinement` |
| `:refinement` | `:resource-process` | the informative refinement of `:process` ([§The node shape](#the-node-shape)) — never a third superkind in `:kind` |
| `:storage` | `:runtime-db` | the **local** cache entry lives in the framework-owned runtime-db partition ([016 §Cache home](016-Resources.md)) — storage always names the local home |
| `:authority` | `{:kind :remote :system :server :transport <id>}` | the source of truth is **external** ([§Authority](#authority--the-remote-axis)); `:transport` mirrors the registered transport (a recomputable projection, never a second home) |
| `:evaluation` | `#{:on-route :on-reply :scheduled :manual}` | a multi-trigger process — route activation, async reply, scheduled stale/GC timers, and manual refresh/ensure ([§Evaluation policy](#evaluation-policy)) |
| `:lifecycle` | `:scoped-resource-key` | a scoped resource key owns the cache entry; owners, freshness policy, and GC release it ([§Lifecycle and owner](#lifecycle-and-owner)) |
| `:materialized?` | `true` | the cache entry has a durable runtime-db address |

The axes that **vary** per resource are the declared **inputs**, the `:authority` transport, and (for a named-resolver scope) the `:scope-resolver` enrichment:

- **`:output`** is `[:runtime [:rf.runtime/resources :entries]]` — the resource materializes its cache entries into the runtime-db partition (the concrete per-key entry address `[:runtime [:rf.runtime/resources :entries <scoped-key>]]` is a live-graph fact).
- **`:inputs`** lower from the resource identity `[cache-scope resource-id canonical-params]` ([§Declared input](#declared-input)): `[:param :rf.params]` (the params, validated by the `:params-schema`) and `[:scope <policy>]` naming the registered scope policy. A `{:from-db <id>}` named-resolver scope appears as the **reference shape verbatim** (`[:scope {:from-db <id>}]`) — a genuinely *static* fact, with the resolver id and its declared `[:db <rf-path>]` inputs surfaced under `:scope-resolver` (the [named-resolver enrichment](#named-resolver-enrichment-ep-0014-issue-3-disposition)). An **inline fn** scope (`(route, ctx)` / fn-of-nothing) is reported as the opaque marker `[:scope :rf.scope/resolver]` — the fn is **never run** (the don't-execute rule). An explicit `:rf.scope/global` / `:rf.scope/from-caller` / literal data-value scope is reported verbatim.
- **`:authority`** carries the `:transport` (defaulting to `:rf.http/managed`, the only initial-scope transport — [016](016-Resources.md)).

The node additionally carries the `:selectors` read-fact ids, the `:commands` transport descriptors and their framework-internal reply targets (`:rf.resource.internal/succeeded` / `…/failed` — commands are **not** facts, [§Process](#process)), the opaque `:derive` `:request` body token (never serialized), the `:source-form` `{:kind :reg-resource :id <id>}`, and the `:source` coordinates / `:schema` (the `:data-schema`) / doc when the registration carried them.

### The live resource cache view

The live counterpart reports one process node **per concrete cache entry**, keyed by its scoped resource key `[cache-scope resource-id canonical-params]` — the resource's [fact identity](#fact-identity) when concrete. Where the static view reports the resource id and a generic params input, the live view reports the **realized** `[[:scope <scope>] [:param <params>]]` edges, the concrete `:output` entry address, and the live lifecycle state:

- **`:lifecycle`** becomes the map `{:kind :scoped-resource-key :owners <active-owners>}` — the live owner set keeping the entry alive ([§Lifecycle and owner](#lifecycle-and-owner): owner keeps it alive, distinct from the cause of any one fetch).
- **`:status`** is the entry's lifecycle-FSM status (`:idle` / `:loading` / `:fetching` / `:loaded` / `:error`).
- **`:work-ledger`** is present when an attempt is in flight: the entry's `:current-work` id plus a small serializable summary of the linked work-ledger record (identity, owners, causes, transport, status — the work-ledger link, [016 §Frame work ledger](016-Resources.md)).
- **`:host-transient`** is present when an attempt is in flight: the in-flight handle address `[[:rf.http/in-flight <work-id>]]` — the abortable handle lives **outside** durable frame-state, in the `[frame-id work-id]` side table ([§Output and materialization](#output-and-materialization); [016](016-Resources.md)). The remote fact is server-owned; the local representation is runtime-owned durable state **plus** host-transient in-flight work.

This exposure is *internal registration metadata*, consistent with the slice scope: it ships **no public accessor**. The resource views live in the bundle-isolated resources tooling sibling (`re-frame.resources.tooling/resource-algebra-view` and `…/resource-cache-algebra-view` in the reference implementation; a CLJS app that loads the resources artefact but attaches no tool DCEs the bodies, and the whole resources artefact is already absent from a no-resources app's bundle), consumed by [Xray](../tools/xray/spec/README.md) and the conformance fixtures — the two named first consumers. The static view reads the `:resource` registry through the existing `resource-meta` / `resource-ids` introspection seams; the live view reads the per-frame `:rf.runtime/resources` entries + `:rf.runtime/work-ledger` records through the existing `frame-runtime-db-value` read seam. Both touch **neither** the resource registrar write-path **nor** the resource events / mutation write-path. They feed the later internal graph-inspection helper ([EP-0014 §Reference Implementation / Bead Plan](../docs/EP/EP-0014-derivation-and-process-algebra.md#reference-implementation--bead-plan) item 7); the public name is deferred until a third consumer needs it (the [graduation gate](#one-accessor-two-projections-ep-0014-issue-1-disposition)).
## Routes expose algebra views

Routes are the fourth concrete algebra member to expose its view, and the **second `:process`** (after [resources](#resources-expose-process-nodes)). A route transition is not a pure derivation: it *materializes* the route slice (match facts, params, query, transition state), invokes loaders/resources, and suppresses stale continuations by navigation token ([§Process](#process)). Every `reg-route` registration projects to the [node shape](#the-node-shape). The projection is **registrar-derived** (see [§The registrar-derived discipline](#the-registrar-derived-discipline)): [012](012-Routing.md) keeps the route table, the rank/pattern-compile machinery, the nav-token allocator, and the transition handlers; the algebra view is assembled from the route registrar metadata that already exists, never from re-running a `:params` / `:scope` / `:when` function.

A route fact's superkind is **`:process`** — a route transition is process-like: it materializes a slice, invokes loaders, and suppresses stale continuations ([§Process](#process)). Its informative refinement is **`:route-fact`**, carried under `:refinement` ([§Two superkinds, refinable](#the-node-shape): the `:kind` is the closed superkind enum; a tool that understands only the two superkinds classifies the node by `:kind`). Every registered route projects to a node carrying the same fixed classifications, because the route fact is the canonical *runtime-db / on-route / frame* member of the algebra:

| Axis | Value | Why |
|---|---|---|
| `:kind` | `:process` | a route transition is process-like — it materializes a slice, invokes loaders, and suppresses stale continuations (refinement `:route-fact` under `:refinement`) |
| `:storage` | `:runtime-db` | the route slice is framework-owned durable state under the runtime-db partition ([012 §The route slice](012-Routing.md); [Spec-Schemas §`:rf/runtime-db`](Spec-Schemas.md#rfruntime-db)) |
| `:evaluation` | `:on-route` | materialized on route activation, egress, or route-match change ([§Evaluation policy](#evaluation-policy) rule 4) |
| `:lifecycle` | `:frame` | the frame owns the route slice; `destroy-frame!` releases it |
| `:materialized?` | `true` | the route slice has a durable runtime-db address, not just a fact identity |

The route fact's **`:id`** is **`:rf/route`** — the one consumer-facing name [012](012-Routing.md) gives the route slice (one name per fact, per [EP-0007](../docs/EP/EP-0007-one-name-per-fact.md)). Every registered route materializes the *same* slice, so every route node carries the *same* fact id; the per-route registration id is recorded under `:source-form` `{:kind :reg-route :id <route-id>}`, not under `:id`. The **`:output`** is `[:runtime [:rf.runtime/routing :current]]` — the runtime-db address the transition installs the slice at. The **`:inputs`** are the route-transition causal events — `[:event :rf.route/navigate]`, `[:event :rf.route/transitioned]`, `[:event :rf.route/handle-url-change]` — the `:on-route` triggers the route fact depends on (the route fact / nav-token / lifecycle boundary an `:on-route` process MUST declare — [§Evaluation policy](#evaluation-policy) rule 4). These are fixed across routes: the SAME framework events materialize the slice regardless of which route matched.

### Route-owned resource activation edges

A route MAY own resource activation: its `:resources` route-metadata ([016 §Route integration](016-Resources.md), the cross-feature key the Resources artefact owns and routing accepts via the late-bound `:routing/extra-route-keys` extension) declares the resources the route ensures on entry and releases on exit. Each `:resources` entry lowers to a route-owned **resource activation edge** under the node's `:resource-edges`:

```clojure
{:from   [:runtime [:rf.runtime/routing :current :params]]
 :to     [:resource :article/by-slug]
 :role   :param
 :target :parametric}   ;; concrete scoped key requires a live match + scope
```

The edge runs **from** the route fact's params slot **to** the resource the route activates, with `:role :param` — the route's matched params flow into the resource's canonical params. The resource `:target` is **`:parametric`**: by the [don't-execute rule](#the-dont-execute-rule-ep-0014-issue-3-disposition), static inspection NEVER invokes the entry's `:params` / `:scope` / `:when` functions, so the realized scoped key `[cache-scope resource-id canonical-params]` ([§Fact identity](#fact-identity)) — which only exists after a concrete route match and scope resolution — is not a static fact. The edge carries the static `:resource` id (so a tool shows *which* resource the route owns even while the concrete key stays parametric) and surfaces `:blocking?` when declared (static route-declaration metadata — whether the transition stays `:loading` until the resource settles). A route declaring no `:resources` carries no `:resource-edges` key. The realized resource owner edge (`[:route route-id nav-token]` → the concrete scoped key) is **live** state, surfaced by the live view below.

### The live route slice

The live view reports the route fact materialized in a frame's runtime-db at `[:rf.runtime/routing :current]` — the concrete matched route id, its params, query, transition state, and nav-token (the live route **owner**, `[:route route-id nav-token]` — [§Lifecycle and owner](#lifecycle-and-owner): route exit or supersession releases it). Where the static node reports the registration-known fact, the live node reports what a navigation actually committed: the matched `:route-id` (correlating the live fact to the `:source-form` of the static node it realized), the live `:params` / `:query` (value summaries, redacted by the graph-inspection helper before egress — [§Redaction metadata](#redaction-metadata-ep-0014-issue-1-disposition-ep-0015)), the `:transition` (`:idle` / `:loading` / `:error` — lifecycle evidence of an in-flight or settled transition), and the `:nav-token` (the live owner identity). It returns `nil` for a missing/destroyed frame or an unmaterialized slice (no navigation has committed).

This exposure is *internal registration metadata*, consistent with the slice scope: it ships **no public accessor**. The route views live in the bundle-isolated routing tooling sibling (`re-frame.routing.tooling/route-algebra-view` and `…/route-slice-algebra-view` in the reference implementation; a CLJS app that loads the routing artefact but attaches no tool DCEs the bodies, and the whole routing artefact is already absent from a no-routing app's bundle), consumed by [Xray](../tools/xray/spec/README.md) and the conformance fixtures — the two named first consumers. The static view reads the `:route` registrar kind; the live view reads a frame's runtime-db through the existing `frame-runtime-db-value` read seam — touching neither the routing registrar write-path (`reg-route` / `clear-route`) nor the route registration signatures. They feed the later internal graph-inspection helper ([EP-0014 §Reference Implementation / Bead Plan](../docs/EP/EP-0014-derivation-and-process-algebra.md#reference-implementation--bead-plan) item 7); the public name is deferred until a third consumer needs it (the [graduation gate](#one-accessor-two-projections-ep-0014-issue-1-disposition)).

## Machines expose algebra views

Machines are the algebra's canonical **`:process`** member — the surface that motivates the [§Process](#process) superkind at all. Where subscriptions and flows are **derivations** (a pure whole-value function with no durable private state beyond its output), a machine is a *derivation WITH state, lifecycle, and commands over time*: it materializes a snapshot, reacts to events and replies and timers, spawns and destroys child actors, and is kept alive by an instance owner until machine destroy releases it. A machine's superkind is **`:process`**; its informative refinement is **`:machine-process`**, carried under `:refinement` ([§Two superkinds, refinable](#the-node-shape): the `:kind` is the closed superkind enum, so a tool that understands only the two superkinds classifies the node by `:kind` alone). Every `reg-machine` registration projects to the [node shape](#the-node-shape). The projection is **registrar-derived** (see [§The registrar-derived discipline](#the-registrar-derived-discipline)): [005](005-StateMachines.md) keeps the transition engine, the snapshot shape, the spawn/destroy lifecycle, and the timer table; the algebra view is assembled from the machine spec metadata that already exists, never from running a transition.

Every machine-process node carries the same fixed classifications, because a machine is the canonical *runtime-db process* of the algebra:

| Axis | Value | Why |
|---|---|---|
| `:kind` | `:process` | a process — a derivation with state, lifecycle, and commands over time ([§Process](#process)); refinement `:machine-process` under `:refinement` |
| `:refinement` | `:machine-process` | the informative refinement of `:process` ([§The node shape](#the-node-shape)) — never a third superkind in `:kind` |
| `:storage` | `:runtime-db` | the snapshot is materialized into the framework-owned runtime-db partition (machine snapshots are durable runtime-db state — [EP-0001](../docs/EP/EP-0001-frame-partitions.md); [005 §Reserved snapshot-internal keys](005-StateMachines.md)) |
| `:lifecycle` | `:machine-instance` | a singleton or spawned-actor snapshot owns it; machine destroy (and frame teardown) releases it |
| `:materialized?` | `true` | the snapshot has a durable runtime-db address, not just a fact identity |

The axes that **vary** per machine are the declared **inputs**, the **evaluation** policy set, and the **output** snapshot path:

- **`:output`** is `[:runtime [:rf.runtime/machines :snapshots <id>]]` — the runtime-db address the machine's whole snapshot materializes into.
- **`:inputs`** are the author-declared event triggers the machine reacts to, each lowered to `[:event <event-id>]` ([§Declared input](#declared-input)): every `:on` key across the whole state tree — flat, compound, hierarchical, and parallel-region — de-duplicated and stably ordered. Reserved framework triggers (`:rf.machine/*`, `:rf.machine.timer/*`, `:rf/*`) and the `:*` wildcard are the runtime's own plumbing, not declared dependency edges. The walk is **structural only** — it never invokes a guard, action, or `:after` fn (the [don't-execute rule](#the-dont-execute-rule-ep-0014-issue-3-disposition)).
- **`:evaluation`** is a *set* ([§Evaluation policy](#evaluation-policy)): always `:on-transition` (a transition is the only thing that advances a snapshot); plus `:scheduled` when the spec declares any `:after` delayed transition (a scheduler delivers the synthetic timer event — [005 §Timed transitions](005-StateMachines.md)); plus `:on-reply` when the spec spawns child actors (a spawning parent reacts to its children's reply events — [005 §Cross-machine messaging](005-StateMachines.md)).

The node additionally records its `:owner` `[:machine <id>]` (the lifecycle owner, distinct from the cause of any one transition — [§Lifecycle and owner](#lifecycle-and-owner)), a `:spawns?` flag (true iff the spec declares `:spawn` / `:spawn-all` — this process is an actor parent), the `:source-form` `{:kind :reg-machine :id <id>}`, and the `:source` coordinates / `:schema` (the machine's `:data-schema`) / `:doc` when the registration carried them. Function values stay opaque — a machine's logic is its `:states` / `:actions` / `:guards`, never serialized.

**Static vs live; spawned actors.** The static view reports one node per registered machine *type*. The **live** view ([§Static and live graphs](#static-and-live-graphs)) reports one node per concrete snapshot materialized at `[:rf.runtime/machines :snapshots <actor-id>]` in a frame's runtime-db — the realized instances the static graph cannot enumerate. A **spawned actor** has *no per-instance registration*: its liveness *is* the presence of its snapshot ([005 §Liveness is derived from runtime-db](005-StateMachines.md)), so the live view resolves its TYPE spec from the snapshot's reserved `:rf/machine-type` discriminator (a registered-type keyword read back from the registrar, or an inline `:definition` spec carried verbatim) and projects the same node shape, additionally surfacing the instance's current `:state` and a `:spawned?` flag. The snapshot value itself is *not* inlined — a graph tool reads it from the slot and redacts at egress ([§Redaction metadata](#redaction-metadata-ep-0014-issue-1-disposition-ep-0015)).

**Selectors are ordinary derivations, not a second subscription system.** A machine *selector* — an ordinary `reg-sub` over `[:rf/machine …]` — stays an ephemeral **`:derivation`** node classified by [§Subscriptions expose algebra views](#subscriptions-expose-algebra-views), exactly like any other subscription (EP-0014 issue-4: machines do **not** become a second subscription system). The machines tooling exposes a `machine-selector?` *recognizer* (the boolean "is this a selector?", for the `:machine-selector` refinement label) and a `machine-selector-targets` *extractor* (the SET of machine ids the selector reads, mined from its static `[:rf/machine …]` / `[:rf/machine-has-tag? …]` inputs). A graph tool labels such a subscription node with the `:machine-selector` refinement and draws the `:selector`-role edge from the **specific** machine `:process` the selector reads to the selector `:derivation` — the machine is the stateful process; the selector is an ephemeral derivation over its materialized snapshot. The extractor (not the boolean) is what lets the graph target precisely: a selector that reads `[:rf/machine :upload/main]` draws its edge from `[:machine :upload/main]` alone, never from an unrelated machine.

This exposure is *internal registration metadata*, consistent with the slice scope: it ships **no public accessor**. The machine views live in the bundle-isolated machines tooling sibling (`re-frame.machines.tooling/machine-algebra-view`, `…/machine-instance-algebra-view`, `…/machine-selector?`, and `…/machine-selector-targets` in the reference implementation; a CLJS app that loads the machines artefact but attaches no tool DCEs the body, and the whole machines artefact is already absent from a no-machines app's bundle), consumed by [Xray](../tools/xray/spec/README.md) and the conformance fixtures — the two named first consumers. They read the machine spec through the existing public `machines` / `machine-meta` query API and live snapshots off the frame's runtime-db, touching neither the registrar write-path nor the machine registration signatures. They feed the later internal graph-inspection helper ([EP-0014 §Reference Implementation / Bead Plan](../docs/EP/EP-0014-derivation-and-process-algebra.md#reference-implementation--bead-plan) item 7); the public name is deferred until a third consumer needs it (the [graduation gate](#one-accessor-two-projections-ep-0014-issue-1-disposition)).

## The registrar-derived discipline

The algebra view is **registrar-derived** — assembled from the registration metadata at the surface that registers each source form, never from re-executing the source form. The static graph is projected from registrations; the live graph reads the runtime cache entries a frame already holds. Every per-family projection ([subscriptions](#subscriptions-expose-algebra-views), [flows](#flows-expose-algebra-views), [resources](#resources-expose-process-nodes), [routes](#routes-expose-algebra-views), [machines](#machines-expose-algebra-views)) and the [graph-assembly composer](#the-graph-assembly-composer-ep-0014-slice-7) that stitches them obey this rule: they add no new fact identity and run no `:derive`, `:request`, transition, param, or scope function (the [don't-execute rule](#the-dont-execute-rule-ep-0014-issue-3-disposition)).

This keeps the algebra view a pure **projection of one registry** — there is one registration registry per frame, and every accessor (static, live, and the composer) is a view over it, never a second authoritative home. The composition model the registry lives under is `image → frame → event stream` ([002-Frames](002-Frames.md)): an [`rf/image`](API.md) selects the registration sources, a frame holds the resolved registrar, and the algebra view is the normalized projection of what that registrar carries.

## Conformance

A conforming implementation should satisfy these checks (the [EP-0014 §Validation / Conformance](../docs/EP/EP-0014-derivation-and-process-algebra.md#validation--conformance) list, restated as the slice-1 contract):

- **Source lowering** — each registered source form exposes an algebra view with the expected id, kind, inputs, output, storage, evaluation, and lifecycle.
- **Static graph** — static inspection reports only registration-known edges, marks parametric edge sets `:parametric`, and **never executes** param/scope functions (the don't-execute rule); named-resolver inputs appear statically.
- **Live graph** — live inspection reports concrete query vectors, active resource keys, route owners, machine instances, and realized parametric edges — including the realized **route-owned resource edge** (a live route node's `[:route route-id nav-token]` owner → the concrete `[:resource <scoped-key>]` node it keeps alive, the live resolution of the static graph's `:parametric` route-resource marker).
- **Selector refinement** — a machine-selector subscription node is enriched with `:refinement :machine-selector` (it remains an ordinary `:derivation` superkind; the refinement is the colour axis, not a third superkind) and is the `:to` end of a `:selector` edge from the specific machine it reads.
- **Storage classification** — ephemeral outputs do not appear in durable frame-state; app-db outputs appear in app-db; runtime-db outputs appear in runtime-db; host-transient state declares teardown. No node uses `:remote` as a storage class — external authority is the separate `:authority` axis.
- **Materialization** — a materialized derivation's output path contains the same whole value its derivation function computes from the same inputs.
- **Evaluation policy** — on-demand reads cause no durable writes; after-event derivations obey event atomicity; reply-driven processes suppress stale replies by declared identity.
- **Lifecycle** — destroying a frame releases frame-owned graph nodes and host-transient state; subscription disposal releases cache-entry nodes; route exit releases route owners; machine destroy releases machine-owned leases and timers.
- **Tool redaction** — graph inspection can summarize or redact sensitive params, scopes, and values without losing graph structure, *including* a live resource node's **identity-embedded** scope/params (the scoped key in the node key, `:id`, `:output`, `:inputs`, `:work-ledger`, and edge endpoints), which the value-path egress walk cannot reach and a tool projects into stable opaque handles ([§Redaction metadata](#redaction-metadata-ep-0014-issue-1-disposition-ep-0015)).
- **Public-API staging** — slice-1 exports **no** new public authoring primitive or stable graph-accessor name; any public API requires a later recorded ruling after the internal shape proves stable.
- **Whole-value / delta law** — every derivation is correct as a whole-value function; any provided `:step-delta` passes the commuting law against whole-value recomputation. Implementations with no delta support still conform.

The node/fact/edge/storage-class/evaluation-policy/lifecycle Malli shapes are in [Spec-Schemas §`:rf/derivation-node`](Spec-Schemas.md#rfderivation-node-rffact-rfderivation-edge-rfstorage-class-rfevaluation-policy-rflifecycle-the-derivationprocess-algebra-ep-0014).

## Cross-references

- [EP-0014](../docs/EP/EP-0014-derivation-and-process-algebra.md) — the source proposal; §Examples carries the full per-member source-form/algebra-view pairs, §Open Issues the six ruled dispositions this doc applies.
- [006-ReactiveSubstrate](006-ReactiveSubstrate.md) — subscriptions and runtime subscriptions, the first concrete derivation instance; cites the whole-value law here.
- [013-Flows](013-Flows.md) — flows as materialized `:after-event` derivations into `app-db`; the `:after-event` sequencing this doc summarizes is owned there.
- [016-Resources](016-Resources.md) — resources as processes (`:authority :remote` + `:storage :runtime-db`); the `[cache-scope resource-id canonical-params]` identity, owners, work ledger, `{:from-db}` named resolvers, and `:rf.resource/*` selectors this doc reuses.
- [012-Routing](012-Routing.md) — route facts (`:rf/route`), route-owned resource activation edges.
- [005-StateMachines](005-StateMachines.md) — machines as processes; selectors as ephemeral derivations over machine snapshots.
- [Managed-Effects](Managed-Effects.md) — the effect-surface analogue; `:rf.flow/*` is its internal-effect cousin.
- [Runtime-Subsystems](Runtime-Subsystems.md) — the durable-state analogue; grades the `:runtime-db` storage class's owning subtrees. EP-0006 owns the per-key projection tiers the storage classes align with.
- [EP-0006](../docs/EP/EP-0006-runtime-subsystem-contract.md) — runtime-subsystem projection tiers the durable storage classes align with.
- [EP-0007](../docs/EP/EP-0007-one-name-per-fact.md) — one-name-per-fact, applied graph-wide to fact identity.
- [EP-0011](../docs/EP/EP-0011-uniform-async-reply-envelope.md) — the reply envelope `:on-reply` processes consume.
- [EP-0012](../docs/EP/EP-0012-path-optics-and-canonical-forms.md) — canonical path/node identity rules.
- [002-Frames](002-Frames.md) — the `image → frame → event stream` composition model the registrar-derived algebra view projects over (the registry a frame holds).
- [EP-0015](../docs/EP/EP-0015-frame-owned-egress-policy.md) — the frame-owned egress posture the redaction metadata composes through.
- [Spec-Schemas §`:rf/derivation-node`](Spec-Schemas.md#rfderivation-node-rffact-rfderivation-edge-rfstorage-class-rfevaluation-policy-rflifecycle-the-derivationprocess-algebra-ep-0014) — the projected Malli shapes.
- [Conventions §The `:rf/path` algebra](Conventions.md#the-rfpath-algebra) — the path shape node/fact identity normalizes to.
- [Ownership](Ownership.md) — the contract-surface → owning-doc matrix; this doc is the canonical home for the derivation/process vocabulary and the whole-value law.
