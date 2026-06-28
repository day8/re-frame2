# EP-0014: Derivation And Process Algebra

Status: final
Type: standards-track

> **Ruling recorded 2026-06-12 (Mike, in-session).** Graduated `accepted →
> final`. The implementation is complete and merged (the five algebra-view
> tooling siblings, the `re-frame.derivation.graph` composer, and the
> cross-family conformance tier), the full wave-end review battery passed
> clean (correctness ×2, testing-coverage STRONG, code-comments, and a
> final-corrective pass — the one P1 node-kind enum defect found and fixed),
> and the normative contract lives in its home —
> [`spec/Derivations.md`](../../spec/Derivations.md). This EP is now the
> **rationale record**; where it and the spec differ, the spec governs.
>
> **BUILD status (updated 2026-06-12):** complete. The post-graduation consumer
> propagation has shipped — `/skills` and `/examples`
> (a considered no-op: the algebra is a view, not an authoring
> surface) via PR #3971, `/docs/guide` via PR #3983, and the
> Xray derivation-graph panel (plus the
> redact-keep-structure coverage test) via the `feat(xray): EP-0014
> derivation-graph panel + off-box redaction` merge — all closed and on
> `main`. The action epic is therefore complete for its ruled
> slice scope and ready to close. The items that legitimately remain
> outstanding are deferred-by-criterion, not pending work: the public
> graph-accessor name + facade classification (graduation-gated on a third
> consumer beyond Xray + conformance, [issue 1](#open-issues)), the executable
> delta protocol (semantic-only by [issue 5](#open-issues)), and machine
> selectors as a source form ([issue 4](#open-issues) keeps them ordinary
> subscriptions).

> This EP defines the common model behind subscriptions, runtime
> subscriptions, flows, resources, route facts, and selected machine state:
> declared inputs, output facts, storage classes, evaluation policy, and
> lifecycle/owner. Existing APIs remain source forms; the algebra is the
> canonical view tools, specs, tests, and future source forms share.
>
> **Ruling recorded 2026-06-11 (Mike, in-session).**
> Accepted, per the EP's single decision surface: **vocabulary, spec model,
> internal metadata, and graph inspection now; no new public authoring
> primitive.** All six open issues are dispositioned in
> [§Open Issues](#open-issues), merging three convergent analyses. Headline
> sharpenings: the internal graph accessor gets a **testable structured
> shape** (incl. EP-0015 redaction metadata) rather than an Xray-private one;
> the `:remote` storage class is **split** (`:storage` always local,
> `:authority` a separate projected map); static inspection gains the
> **don't-execute rule**; the commuting law's home is **named** — a standalone
> contract document in the Managed-Effects/Runtime-Subsystems family; and the
> EP-0013 condition in issue 6 **fired** (EP-0013 accepted the same day) — the
> relocation coordinates via the shape-agreement obligation recorded on the
> EP-0013 action epic, with optional `:realm/id`/`:app/id`/`:module/id` node
> fields reserved. Implementation is tracked by the EP-0014 action epic,
> gated behind the current EP wave epics, with the standard wave tails.
>
> Normative home after acceptance: a standalone derivation contract document
> (the issue-5 disposition; working name `spec/Derivations.md`), cited by the
> subscription, flows, resource, machines, routing, runtime-model, and
> tool-inspection specs.

## Abstract

re-frame2 already has the right foundation: a frame is a value-producing fold
over causal inputs, and views, handlers, effects, routes, resources, and
machines observe or extend that fold. The project now needs one durable
vocabulary for the facts and processes built over it.

This EP proposes a **derivation/process algebra**. A derivation is a declared
way to compute a fact from inputs. A process is a derivation with state,
commands, lifecycle, and time. The positive principle: **derive, materialize,
fetch, and synchronize are storage and evaluation policies over one declared
dependency graph.** Subscriptions, runtime subscriptions, flows, resource
reads, route facts, and machine selectors are not the same runtime mechanism,
but they are all instances of this shape:

```clojure
{:id         <stable-id>
 :kind       <derivation-or-process-kind>
 :inputs     <declared-inputs>
 :output     <output-fact-or-address>
 :storage    <storage-class>
 :evaluation <evaluation-policy>
 :lifecycle  <owner-or-lifetime>
 :derive     <whole-value-function-or-reference>}
```

The proposal does not require authors to write this map directly. `reg-sub`,
`reg-flow`, `reg-resource`, route declarations, and machine declarations remain
the ergonomic source forms. The algebra is the common view those source forms
lower to, so a large SPA can answer one question consistently: "where does this
fact come from, when is it evaluated, where does it live, and who owns it?"

## Problem Statement

The current system explains related mechanisms with separate vocabularies:

- subscriptions derive ephemeral view values;
- runtime subscriptions derive ephemeral values from framework-owned
  `runtime-db`;
- flows materialize derived values into `app-db`;
- resources fetch, cache, refresh, and garbage-collect remote state;
- routes materialize match facts, params, and transition state;
- machines materialize process snapshots and expose selectors over those
  snapshots.

Each distinction is real. A resource should not pretend to be a pure
subscription, and a machine should not collapse into a flow. The problem is that
large applications need cross-cutting answers that the separate vocabularies
make harder than necessary:

- Which facts does this page depend on?
- Which event, route, resource, or machine state can change this value?
- Is this value durable frame state, an ephemeral cache entry, host-transient
  state, or server-owned data represented locally?
- Is this computation lazy, event-boundary eager, reply-driven, route-driven, or
  scheduled?
- Which owner keeps it alive, and which teardown boundary releases it?
- Can Xray or a test enumerate the graph without executing arbitrary app code?

Without one algebra, tools and maintainers must stitch together partial graphs:
subscription topology here, flow topology there, resource owners elsewhere,
route facts in routing, machine selectors in machine code. That fragmentation is
unnecessary. All of those surfaces are declared facts and processes over the
same frame fold.

## Motivation

The pre-alpha design goal is not "match re-frame v1 as closely as possible." It
is to create a simple, law-abiding architecture for building and maintaining
large SPAs. Mechanical upgrade from v1 remains valuable, especially for
`reg-sub` and `on-changes`-style flows, but compatibility is a migration
constraint, not the governing principle.

The governing principle is:

> Make the remaining ambient concepts into values with laws.

re-frame2 already makes durable state a value, effects data, route tables data,
machine transition tables data, and resource cache entries data. The missing
piece is the common graph language that says how derived facts and stateful
processes relate to the frame fold. Once that language exists, the same tooling
can inspect a subscription, a flow, a route parameter, a resource entry, or a
machine selector without learning five unrelated contracts.

This is also the right place to put future scaling work. Whole-value derivation
must remain the default because it is simple, testable, and productive. Optional
delta contracts can be added later as a law-checked optimization tier for large
collections, grids, joins, and dashboards. They should not become the default
mental model.

## Goals

- Define one canonical vocabulary for facts, derivations, processes, inputs,
  outputs, storage classes, evaluation policy, lifecycle, and owner.
- Treat subscriptions, runtime subscriptions, flows, resources, route facts,
  and machine selectors as one family of declared facts/processes over the
  frame fold.
- Preserve existing source forms as the authoring surface where they are the
  right ergonomic shape.
- Make materialized versus ephemeral outputs explicit.
- Make storage class explicit: app state, runtime state, host-transient support
  state, remote authority, or ephemeral cache.
- Make evaluation policy explicit: on demand, after event, on reply, on route,
  scheduled, manual, or process transition.
- Make lifecycle and ownership explicit: subscription cache entry, frame, route
  owner, resource key, machine instance, actor, or host root.
- Define a graph-inspection contract suitable for Xray, tests, docs, and
  AI-assisted maintenance.
- Keep whole-value derivation as the required default and define optional delta
  optimization as a law, not a new semantics.
- Describe backwards-compatible migration from v1 subscriptions and
  `on-changes`-style derived state.

## Non-Goals

- This EP does not replace `reg-sub`, `reg-flow`, `reg-resource`, route
  declarations, or machine declarations with a single public macro.
- This EP does not mint any new public authoring or accessor primitive in its
  first slice — no `reg-fact`, no `reg-derivation`, no stable public graph
  accessor name. The algebra is vocabulary, spec model, and internal metadata
  until the projection from the existing source forms has proved the shape
  (project-before-primitive). Future source forms, if any, are separate EPs.
- This EP does not require every application author to write algebra maps.
- This EP does not make flows obsolete or make subscriptions durable.
- This EP does not collapse resources into subscriptions or machines into
  flows.
- This EP does not introduce a differential-dataflow engine.
- This EP does not define app values or runtime realms. Those are natural future
  homes for algebra declarations, but they are not required for this proposal.
- This EP does not open a general third-party runtime-subsystem API.
- This EP does not change resource HTTP scope, machine snapshot shape, route
  ranking, or frame-target resolution.

## Relationships

- [EP-0003](EP-0003-resource-queries.md) (final) and
  [Spec 016](../../spec/016-Resources.md) define resource identity, cache
  scope, owners, the work ledger, and runtime-db storage for server-state
  processes. Resources are a family member of this algebra; this EP reuses
  their vocabulary (`[cache-scope resource-id canonical-params]` keys, owners,
  `:rf.resource/*` read subs) and does not fork it.
- [EP-0004](EP-0004-subscription-inputs.md) defines the `reg-sub` input
  producer shape that becomes subscription input declaration in this algebra.
- [EP-0005](EP-0005-machine-data-schema.md) and
  [Spec 005](../../spec/005-StateMachines.md) define the machine snapshot and
  process substrate this EP treats as a process kind.
- [EP-0006](EP-0006-runtime-subsystem-contract.md) defines the runtime
  subsystem contract whose per-key projection tiers (durable-serialized /
  local-subscribable / host-transient) grade the durable storage classes in
  this EP. The algebra's storage classes name where a value lives; EP-0006's
  grading table remains the authority on how each runtime-db key is projected,
  hydrated, and torn down.
- [EP-0007](EP-0007-one-name-per-fact.md) supplies the one-name-per-fact rule
  that this EP's fact-identity section applies graph-wide.
- [Spec 006](../../spec/006-ReactiveSubstrate.md) defines the reactive substrate,
  sub-cache, partition projections, and subscription topology that form the
  first concrete algebra instance.
- [Spec 012](../../spec/012-Routing.md) defines route match facts and route
  resource activation inputs.
- [Spec 013](../../spec/013-Flows.md) defines flows as materialized derivations
  into `app-db`, including the drain-integration sequencing this EP's
  `:after-event` policy summarizes.
- [EP-0010](EP-0010-causal-world-inputs.md) (final),
  [EP-0011](EP-0011-uniform-async-reply-envelope.md) (final), and
  [EP-0012](EP-0012-path-optics-and-canonical-forms.md) (final) are
  companions: causal world inputs, async replies, and canonical path/identity
  forms make the algebra more precise, but this EP can stand without them.
  Canonical node/fact identity uses EP-0012's rules directly.
- [EP-0013](EP-0013-app-values-and-runtime-realms.md) (accepted 2026-06-11,
  since superseded by [EP-0023](EP-0023-image-loaded-frames.md))
  is the natural future home for algebra declarations: the normalized node
  this EP defines is exactly the per-fact/per-process row an app value
  carries, so the algebra view moves from registrar-derived metadata to a
  section of the app value with no shape change — enforced by the
  **shape-agreement obligation** recorded on the EP-0013 action epic, which
  verifies the two shapes agree before either EP's spec
  slice lands. The relocation itself happens inside EP-0013's D2.

## Definitions

### Frame Fold

A **frame fold** is the ordered sequence of frame-state values produced by
applying causal inputs to a frame:

```text
frame-state-0
  -- causal input 1 --> frame-state-1
  -- causal input 2 --> frame-state-2
  -- causal input 3 --> frame-state-3
```

Each frame-state value has the two durable partitions defined by EP-0001:
application-owned `app-db` and framework-owned `runtime-db`. Host-transient
state, such as abort handles, timers, scroll caches, and dirty-check side tables,
may support the fold but is not durable frame state.

### Fact

A **fact** is a named readable value at a point in the frame fold. A fact may be:

- a source fact, such as an `app-db` path, a `runtime-db` path, a route param, or
  a resource key;
- a derived fact, such as a subscription value;
- a materialized fact, such as a flow output in `app-db`;
- a process fact, such as a resource entry status or a machine snapshot
  selector.

A fact has one canonical identity in the layer where it is named. That identity
is what tools use for graph edges and what docs use when explaining the source
of a value.

### Source Form

A **source form** is the API shape an author writes: `reg-sub`, `reg-flow`,
`reg-resource`, `reg-route`, `reg-machine`, or a future manifest form.
Source forms remain optimized for humans and migration.

The **algebra view** is the normalized data view tools and specs use. It may be
derived from source forms at registration time, from runtime cache entries, or
from an app value in a future architecture.

### Derivation

A **derivation** computes an output fact from declared inputs with a pure
whole-value function. A derivation has no durable private state beyond optional
memoization and, if materialized, its output value.

Subscriptions and flows are derivations.

### Process

A **process** is a derivation with state, lifecycle, and commands over time. It
may react to causal events, async replies, route entry/exit, timers, or machine
transitions. It may materialize state into `runtime-db`, hold host-transient
handles, and emit commands that later return causal replies.

Resources and machines are processes. A route transition is also process-like:
it materializes route facts, invokes loaders/resources, and suppresses stale
continuations by navigation token.

### Declared Input

A **declared input** is a data description of a dependency. It names what a
derivation or process reads without requiring a tool to inspect arbitrary
function bodies.

Static declarations can be known from registration alone. Parametric
declarations may require a concrete query vector, route match, resource params,
or machine instance before their realized edges are known.

### Output

An **output** is the fact or address a derivation/process produces.

- An ephemeral output has a fact identity but no durable frame-state address.
- A materialized output has a durable address, such as an `app-db` path or a
  `runtime-db` path.
- A process may have both: a materialized snapshot plus ephemeral selectors over
  that snapshot.

Whole-value output is the default: the derivation returns the entire next value
of the output fact. Patch or delta output is optional optimization only.

### Storage Class

A **storage class** declares where the output or supporting process state lives:

| Storage class | Meaning |
|---|---|
| `:ephemeral` | A cache/reaction value derived from frame state. It is not durable frame state and is released by its lifecycle. |
| `:app-db` | Application-owned durable frame state under the app-db partition. |
| `:runtime-db` | Framework-owned durable frame state under the runtime-db partition. |
| `:host-transient` | Host handles or caches outside durable frame state. They must be derived, cleared, or reconciled at lifecycle boundaries. |
| `:remote` | A fact whose source of truth is outside the frame. The local representation must name its local storage class, usually `:runtime-db` for resources. |

`:remote` is intentionally not a license to hide state. It says the authority is
external; the local cache entry, in-flight row, owner index, and selectors still
declare their local storage and lifecycle.

Storage classes align with the EP-0006 runtime-subsystem projection tiers
rather than replacing them: a `:runtime-db` output is still graded
durable-serialized or local-subscribable per key by its owning subsystem's
projection policy, and `:host-transient` here is the same tier EP-0006 names.
`:ephemeral` values have no subsystem row because they are not durable state.

### Evaluation Policy

An **evaluation policy** declares when the derivation/process runs:

| Policy | Meaning |
|---|---|
| `:on-demand` | Evaluated when a reader subscribes or requests the fact. |
| `:after-event` | Evaluated during an event drain after handlers have produced pending state. |
| `:on-reply` | Evaluated when an async reply envelope or equivalent causal completion arrives. |
| `:on-route` | Evaluated on route activation, egress, or route-match change. |
| `:on-transition` | Evaluated as part of a machine/process transition. |
| `:scheduled` | Evaluated because a timer or scheduler delivered a causal input. |
| `:manual` | Evaluated only when an explicit invalidation, refresh, ensure, or recompute command asks for it. |

Policies may be sets when a process has more than one trigger, such as a
resource that runs on route activation, manual refresh, and async reply.

### Lifecycle And Owner

A **lifecycle** declares who keeps the fact/process alive and who releases it:

| Lifecycle | Typical owner/release boundary |
|---|---|
| `:subscription-cache-entry` | The concrete query vector's readers keep it alive; ref-count disposal releases it. |
| `:frame` | The frame owns it; `destroy-frame!` releases it. |
| `:route` | The active route/nav-token owns it; route exit or supersession releases it. |
| `:resource-key` | A scoped resource key owns the cache entry; owners, freshness policy, and GC release it. |
| `:machine-instance` | A singleton or spawned actor snapshot owns it; machine destroy releases it. |
| `:host-root` | A host root, adapter, or runtime realm owns it; adapter/runtime disposal releases it. |

Lifecycle is part of the fact's contract. A graph that shows data dependencies
but hides ownership is incomplete.

## Proposed Solution

Adopt the derivation/process algebra as the canonical normalized view of every
declared fact and process. The authoring APIs remain plural; the inspection,
testing, and specification vocabulary becomes singular.

The proposal has three parts:

1. **Normalize source forms into algebra views.** Existing registrations produce
   metadata shaped by this EP. A source form may lower to more than one algebra
   node. For example, a resource declaration lowers to a process node for the
   resource cache entry and to derived read facts such as state, data, loading
   flag, and error projection.
2. **Expose a graph-inspection contract.** The runtime and tools can enumerate
   facts, derivations, processes, inputs, outputs, storage, evaluation policy,
   lifecycle, owners, and source coordinates. Static graph inspection must not
   pretend to know edges that only exist after a parametric input is realized;
   live graph inspection can include those realized edges.
3. **Specify whole-value semantics and optional delta law.** Every derivation
   must be correct as a whole-value function. A delta step may exist only as an
   optimization with a conformance law proving it commutes with whole-value
   recomputation.

The staging discipline is deliberate and is the EP's single decision surface:
**vocabulary now, API later.** The first slice is spec language, registration
metadata, and an internal inspection helper proved against the existing
mechanisms. Minting a public authoring primitive or a stable accessor name is
a separable later decision that this EP neither makes nor requires.

This gives re-frame2 one answer for "where does this value come from?" while
letting each mechanism keep the operational differences that make it useful.

## Specification

### Algebra Declaration Shape

Every derivation/process SHOULD be describable by an algebra view equivalent to
this shape:

```clojure
{:id          :cart/total
 :kind        :derivation
 :source-form {:kind :reg-sub
               :id   :cart/total}
 :inputs      [[:sub [:cart/items]]
               [:sub [:pricing/discounts]]]
 :output      [:fact :cart/total]
 :storage     :ephemeral
 :evaluation  :on-demand
 :lifecycle   :subscription-cache-entry
 :materialized? false
 :derive      #'app.cart/sum-cart
 :schema      :app.money/amount
 :source      {:ns "app.cart"
               :file "src/app/cart.cljs"
               :line 42}}
```

The exact public accessor name is deferred. The required information is not.
Tools must be able to recover the same facts from a conforming implementation.

Every `:kind` value is one of exactly two closed superkinds — `:derivation` or
`:process` (the graduated, closed `DerivationKind` enum). Refined kinds used in
this EP's examples — `:resource-process`, `:route-fact`, `:machine-process`,
`:machine-selector` — are informative refinements of those two superkinds,
carried on the separate `:refinement` axis, never in `:kind`: specs may add
refinements, but a tool that understands only `:derivation` and `:process` must
still be able to classify every node by reading `:kind` alone. (Graduation note:
earlier drafts placed the refined kind directly in `:kind`; the graduated
Spec-Schemas `DerivationKind` closed the enum to the two superkinds, so the
examples below carry the refinement under `:refinement`.)

Function values in graph output MAY be represented by symbols, source
coordinates, registry metadata, or opaque implementation tokens. The graph
contract is about dependencies, storage, evaluation, and ownership; it does not
require serializing executable functions.

### Fact Identity

Fact identity MUST be stable enough for tools, tests, and docs:

- a subscription fact is identified by its query vector when concrete and by its
  sub id when static;
- a flow fact is identified by its flow id and output path;
- a route fact is identified by the route slice or projection, such as
  `:rf.route/params`;
- a resource fact is identified by `[cache-scope resource-id canonical-params]` when
  concrete and by resource id when static;
- a machine fact is identified by machine id or instance id plus snapshot or
  selector identity.

When a source form is parametric, the static graph MUST mark the edge set as
parametric instead of inventing all possible concrete edges.

### Declared Input Forms

The algebra uses data forms for inputs. The following forms are normative
vocabulary; specs may add narrower aliases that lower to these forms.

```clojure
[:db path]
[:runtime path]
[:frame-state path]
[:sub query-vector]
[:param key]
[:scope scope-id-or-expression]
[:route projection]
[:resource resource-ref]
[:machine machine-ref projection]
[:fact fact-id]
[:event event-id]
[:reply reply-kind]
[:timer timer-id]
```

Examples:

```clojure
[[:db [:cart :items]]
 [:runtime [:rf.runtime/routing :current :params :slug]]
 [:sub [:pricing/discounts]]
 [:route [:params :article-id]]
 [:resource {:resource :article/by-id
             :scope    [:scope :session]
             :params   {:id [:route [:params :article-id]]}}]
 [:machine :upload/main [:data :progress]]]
```

`[:db path]` reads from the app-db partition. `[:runtime path]` reads from the
runtime-db partition. `[:frame-state path]` is reserved for framework internals
that deliberately read across the product value; application source forms should
prefer partition-specific inputs.

### Static And Live Graphs

Graph inspection has two modes:

- A **static graph** is derived from registrations and source forms. It can show
  literal `:<-` subscription edges, flow input paths, resource declarations,
  route metadata, and machine declarations.
- A **live graph** is derived from a frame at a point in time. It can include
  concrete subscription query vectors, realized parametric input edges, active
  resource keys, route owners, machine instances, in-flight work, and current
  lifecycle state.

For parametric subscriptions, the static graph reports the source form:

```clojure
{:id :article/page
 :kind :derivation
 :inputs :parametric
 :input-producer #'app.article/article-page-inputs}
```

The live graph reports realized edges per concrete query vector:

```clojure
{:id [:sub [:article/page "welcome"]]
 :kind :derivation
 :inputs [[:sub [:article/by-slug "welcome"]]
          [:sub [:comments/for-article "welcome"]]]
 :lifecycle :subscription-cache-entry}
```

Static graph tools MUST NOT pretend every possible parametric edge is known
before concrete query vectors exist.

### Outputs And Materialization

Every algebra node declares its output:

```clojure
[:fact :cart/total]
[:db [:cart :total]]
[:runtime [:rf.runtime/resources :entries scoped-key]]
[:runtime [:rf.runtime/machines :snapshots :upload/main]]
[:host-transient [:rf.http/in-flight work-id]]
[:remote {:resource :article/by-id :params {:id "a1"}}]
```

The default output contract is whole-value:

```clojure
next-output = derive(input-values, query-or-context)
```

A materialized derivation installs the whole value at its output address. An
ephemeral derivation caches or returns the whole value but does not write it to
durable frame state.

Processes may produce command outputs, but commands are not facts. A command
becomes part of the causal graph only when the host returns a reply event or a
process transition records durable state.

### Storage Classes

Each node declares the storage class of its output or local representation:

```clojure
:ephemeral
:app-db
:runtime-db
:host-transient
:remote
```

Rules:

1. `:ephemeral` values MUST be recomputable or disposable without changing
   durable frame state.
2. `:app-db` values are application-owned durable facts and are subject to app
   schema, SSR, restore, and time-travel rules.
3. `:runtime-db` values are framework-owned durable facts and are subject to
   runtime-subsystem authority, projection, SSR, restore, and teardown rules.
4. `:host-transient` values MUST declare a teardown or reconciliation boundary.
   They MUST NOT be the only copy of a fact required for replay or restore.
5. `:remote` values MUST name their local representation separately. For
   resources, the remote fact is represented by runtime-db cache entries and
   host-transient in-flight handles.

### Evaluation Policies

Each node declares when it evaluates:

```clojure
:on-demand
:after-event
:on-reply
:on-route
:on-transition
:scheduled
:manual
```

Rules:

1. `:on-demand` derivations must not cause durable state changes merely because
   a view read them.
2. `:after-event` derivations run inside the event drain and participate in the
   event's atomicity rules.
3. `:on-reply` processes run only from causal reply events or equivalent
   framework-owned completions; stale replies must be suppressible by declared
   identity.
4. `:on-route` processes must declare the route fact, nav-token, owner, or
   route lifecycle boundary they depend on.
5. `:scheduled` processes must separate durable scheduling facts from
   host-transient timer handles.
6. `:manual` processes must expose the event, command, or API that causes
   invalidation/refetch/recompute.

### Lifecycle And Owners

Every node declares lifecycle:

```clojure
{:lifecycle :subscription-cache-entry
 :owner     [:sub-query [:cart/total]]}

{:lifecycle :frame
 :owner     [:frame :checkout]}

{:lifecycle :route
 :owner     [:route :route/article nav-token]}

{:lifecycle :resource-key
 :owner     [scope :article/by-slug {:slug "welcome"}]}

{:lifecycle :machine-instance
 :owner     [:machine :upload/main]}
```

For processes, owner and cause are distinct. Owner keeps the process or cache
entry alive. Cause explains why work happened. A button click that refreshes a
resource is usually a cause; a route, machine, SSR request, or explicit lease is
usually an owner.

### Whole-Value Default

Whole-value derivation is mandatory. A conforming implementation must be correct
if every derivation recomputes its entire output from its declared inputs.

This is the semantic floor:

```clojure
(= (derive inputs context)
   (read-output-after-recompute inputs context))
```

Memoization, equality pruning, dependency tracking, dirty checks, and deltas are
implementation techniques. They may improve performance but must not change the
observable value.

### Optional Delta Contract

A derivation MAY provide a delta step as an optimization:

```clojure
{:id :large-grid/visible-rows
 :derive #'app.grid/visible-rows
 :step-delta #'app.grid/visible-rows-delta}
```

The required law is:

```text
derive(apply-input-delta(inputs, delta-in), context)
=
apply-output-delta(derive(inputs, context),
                   step-delta(derive(inputs, context), delta-in, context))
```

The exact delta representation is deliberately deferred. The important rule is
that delta execution must commute with whole-value recomputation. If the delta
path is absent, disabled, or rejected by conformance, whole-value derivation
remains correct.

### Errors And Diagnostics

A graph node SHOULD carry source coordinates, doc text, schema metadata,
sensitivity/size metadata, and runtime ownership when those are available.

Structured errors SHOULD reference algebra identities:

- unknown input fact;
- cycle in a derivation graph that requires acyclic evaluation;
- illegal storage write for the source form;
- missing lifecycle owner;
- unresolved resource scope;
- stale reply suppressed;
- delta law check failed.

The error vocabulary remains owned by the relevant specs. This EP requires that
errors be attributable to graph nodes rather than only to low-level functions.

### Tool And Xray Graph Inspection

Tools SHOULD be able to request a graph view equivalent to:

```clojure
{:frame :checkout
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
   :evaluation #{:on-route :on-reply :scheduled :manual}
   :lifecycle :resource-key
   :output [:runtime [:rf.runtime/resources :entries
                      [[:rf.scope/global] :article/by-slug
                       {:slug "welcome"}]]]}

  [:machine :upload/main]
  {:kind :process
   :storage :runtime-db
   :evaluation #{:on-transition :scheduled :on-reply}
   :lifecycle :machine-instance
   :output [:runtime [:rf.runtime/machines :snapshots :upload/main]]}}

 :edges
 [{:from [:sub [:cart/items]]
   :to   [:sub [:cart/total]]
   :role :input}
  {:from [:runtime [:rf.runtime/routing :current :params :slug]]
   :to   [:resource [[:rf.scope/global] :article/by-slug {:slug "welcome"}]]
   :role :param}
  {:from [:machine :upload/main]
   :to   [:sub [:upload/progress]]
   :role :selector}]}
```

The graph SHOULD be useful without exposing sensitive raw values. It may elide
large data, redact sensitive params/scopes, and summarize functions.

## Examples

### Subscription Source Form

Existing static-input subscription:

```clojure
(rf/reg-sub
  :cart/total
  :<- [:cart/items]
  :<- [:pricing/discounts]
  (fn [[items discounts] _query-v]
    (sum-cart items discounts)))
```

Algebra view:

```clojure
{:id :cart/total
 :kind :derivation
 :source-form {:kind :reg-sub :id :cart/total}
 :inputs [[:sub [:cart/items]]
          [:sub [:pricing/discounts]]]
 :output [:fact :cart/total]
 :storage :ephemeral
 :evaluation :on-demand
 :lifecycle :subscription-cache-entry
 :materialized? false
 :derive #'app.cart/sum-cart}
```

Layer-1 subscription:

```clojure
(rf/reg-sub
  :cart/items
  (fn [db _query-v]
    (get-in db [:cart :items])))
```

Conservative algebra view:

```clojure
{:id :cart/items
 :kind :derivation
 :inputs [[:db []]]
 :output [:fact :cart/items]
 :storage :ephemeral
 :evaluation :on-demand
 :lifecycle :subscription-cache-entry}
```

Because the source form hands the whole `app-db` value to the body, the safe
declared input is the app-db projection. A future path-aware source form or
registration metadata may narrow this to `[:db [:cart :items]]`, but correctness
does not depend on that optimization.

### Parametric Subscription

Existing source:

```clojure
(rf/reg-sub
  :article/page
  (fn [[_ slug]]
    [[:article/by-slug slug]
     [:comments/for-article slug]])
  (fn [[article comments] [_ slug]]
    {:slug slug
     :article article
     :comments comments}))
```

Static algebra view:

```clojure
{:id :article/page
 :kind :derivation
 :inputs :parametric
 :input-producer #'app.article/article-page-inputs
 :output [:fact :article/page]
 :storage :ephemeral
 :evaluation :on-demand
 :lifecycle :subscription-cache-entry}
```

Live algebra view for `[:article/page "welcome"]`:

```clojure
{:id [:sub [:article/page "welcome"]]
 :kind :derivation
 :inputs [[:sub [:article/by-slug "welcome"]]
          [:sub [:comments/for-article "welcome"]]]
 :output [:fact [:article/page "welcome"]]
 :storage :ephemeral
 :evaluation :on-demand
 :lifecycle :subscription-cache-entry}
```

### Runtime Subscription

Framework subs read the `runtime-db` projection through the same cache rules as
ordinary subscriptions. `reg-runtime-sub` is a framework-internal registration
surface — routed from the subsystem facades, not a public app-author API — but
its registrations are ordinary algebra nodes:

```clojure
;; framework-internal (subsystem facade code, not app code)
(subs/reg-runtime-sub
  :rf.route/params
  (fn [runtime-db _query-v]
    (get-in runtime-db [:rf.runtime/routing :current :params])))
```

Algebra view:

```clojure
{:id :rf.route/params
 :kind :derivation
 :inputs [[:runtime [:rf.runtime/routing :current :params]]]
 :output [:fact :rf.route/params]
 :storage :ephemeral
 :evaluation :on-demand
 :lifecycle :subscription-cache-entry}
```

The source form may still receive the full runtime-db projection. The algebra
records the intended route fact so tools can show the edge from routing state to
the subscription.

### Flow Source Form

Existing flow:

```clojure
(rf/reg-flow
  {:id     :cart/materialized-total
   :inputs [[:cart :items]
            [:pricing :discounts]]
   :output (fn [items discounts]
             (sum-cart items discounts))
   :path   [:cart :total]})
```

Algebra view:

```clojure
{:id :cart/materialized-total
 :kind :derivation
 :source-form {:kind :reg-flow :id :cart/materialized-total}
 :inputs [[:db [:cart :items]]
          [:db [:pricing :discounts]]]
 :output [:db [:cart :total]]
 :storage :app-db
 :evaluation :after-event
 :lifecycle :frame
 :materialized? true
 :derive #'app.cart/sum-cart}
```

A source-form flow input rooted at `:rf.db/runtime` lowers to `[:runtime ...]`
in the algebra view. The output still lowers to `[:db path]`: per
[Spec 013 §Input partition](../../spec/013-Flows.md#input-partition--bare--app-db-rfdbruntime---runtime-db),
flows may read runtime-db explicitly, but they never write runtime-db.

This is the same fact expressed twice: `sum-cart` is one whole-value function,
and the subscription's and the flow's algebra views differ only in output,
storage, evaluation, and lifecycle — ephemeral/on-demand/cache-entry versus
app-db/after-event/frame. The difference between the subscription and the flow
is not the mathematical function; it is policy over the same dependency graph.

**Sequencing.** `:after-event` for an already-registered flow means
same-commit materialization: per
[Spec 013 §Drain integration](../../spec/013-Flows.md#drain-integration), the
flow transform rewrites the pending `:db` effect before the event's single
atomic install, so a flow's inputs and its materialized output move together
in one commit — there is no general one-event staleness for registered flows.
The one sequencing exception is a flow registered mid-event via
`:rf.fx/reg-flow`, whose initial output appears one drain later
([Spec 013 §Sequencing — the one-event lag](../../spec/013-Flows.md#sequencing--the-one-event-lag)).
Whether to close that registration lag (a same-commit re-walk has fixpoint
character but conflicts with the one-install-per-event invariant) is Spec 013's
open question, not this EP's; the algebra only requires that the evaluation
policy and its documented sequencing be declared honestly.

### Resource Declaration

Existing resource:

```clojure
(rf/reg-resource
  :article/by-slug
  {:params-schema [:map [:slug :string]]
   :data-schema   :app/article
   :scope         :rf.scope/from-caller
   :request       (fn [{:keys [slug]} _ctx]
                    {:request {:method :get
                               :url (str "/api/articles/" slug)}
                     :decode :app/article})
   :stale-after-ms 60000
   :gc-after-ms    300000})
```

Static algebra view:

```clojure
{:id :article/by-slug
 :kind :process
 :refinement :resource-process
 :source-form {:kind :reg-resource :id :article/by-slug}
 :inputs [[:param :slug]
          [:scope :rf.scope/from-caller]]
 :output [:runtime [:rf.runtime/resources :entries]]
 :storage :runtime-db
 :remote {:authority :server
          :transport :rf.http/managed}
 :evaluation #{:on-route :on-reply :scheduled :manual}
 :lifecycle :resource-key
 :commands [{:effect :rf.http/managed
             :replies {:success :rf.resource.internal/succeeded
                       :failure :rf.resource.internal/failed}}]
 :selectors [:rf.resource/state
             :rf.resource/data
             :rf.resource/status
             :rf.resource/loading?
             :rf.resource/fetching?
             :rf.resource/stale?
             :rf.resource/error
             :rf.resource/refresh-error
             :rf.resource/has-data?
             :rf.resource/previous-data]}
```

The process node describes fetch, cache, freshness, GC, and reply lifecycle.
The public `:rf.resource/*` read subscriptions listed in `:selectors` are
separate on-demand derivations over the runtime-db entry; reading them does not
start resource work.

Live algebra view for one scoped key:

```clojure
{:id [:resource [[:rf.scope/session {:tenant-id "acme"}]
                 :article/by-slug
                 {:slug "welcome"}]]
 :kind :process
 :inputs [[:scope [:rf.scope/session {:tenant-id "acme"}]]
          [:param {:slug "welcome"}]]
 :output [:runtime [:rf.runtime/resources :entries
                    [[:rf.scope/session {:tenant-id "acme"}]
                     :article/by-slug
                     {:slug "welcome"}]]]
 :storage :runtime-db
 :remote {:authority :server}
 :evaluation #{:on-route :on-reply :scheduled :manual}
 :lifecycle {:kind :resource-key
             :owners #{[:route :route/article 17]}}
 :host-transient [[:rf.http/in-flight :work/id-123]]}
```

The remote fact is server-owned. The local representation is runtime-owned
durable state plus host-transient in-flight work.

### Route Fact Input

Route source:

```clojure
(rf/reg-route
  :route/article
  {:path "/articles/:slug"
   :params [:map [:slug :string]]
   :resources
   [{:resource :article/by-slug
     :params   (fn [route] {:slug (get-in route [:params :slug])})
     :scope    (fn [_route ctx] (:current-session-scope ctx))
     :blocking? true}]})
```

Route fact algebra — the fact id is `:rf/route`, the consumer-facing name
Spec 012 already gives the route slice (one name per fact, per EP-0007):

```clojure
{:id :rf/route
 :kind :process
 :refinement :route-fact
 :source-form {:kind :reg-route :id :route/article}
 :inputs [[:event :rf.route/navigate]
          [:event :rf.route/transitioned]
          [:event :rf.route/handle-url-change]]
 :output [:runtime [:rf.runtime/routing :current]]
 :storage :runtime-db
 :evaluation :on-route
 :lifecycle :frame}
```

The route-owned resource edge:

```clojure
{:from [:runtime [:rf.runtime/routing :current :params :slug]]
 :to   [:resource [[:scope :current-session]
                   :article/by-slug
                   {:slug [:route [:params :slug]]}]]
 :role :param}
```

A view can read the route facts, then subscribe to a view-model whose resource
state dependency is declared by its input producer:

```clojure
(rf/reg-sub
  :article/view-model
  (fn [[_ slug scope]]
    [[:rf.resource/state
      {:resource :article/by-slug
       :scope scope
       :params {:slug slug}}]])
  (fn [[state] [_ slug _scope]]
    {:slug slug
     :state state}))
```

The live graph for `[:article/view-model "welcome" session-scope]` exposes the
realized resource-state edge; it is not hidden inside the subscription body.

### Machine Process And Selector

Machine source:

```clojure
(rf/reg-machine
  :upload/main
  {:initial :idle
   :data    {:progress 0}
   :actions {:start-upload    (fn [_ctx]
                                {:fx [[:rf.http/managed {...}]]})
             :record-progress (fn [{[_ pct] :event}]
                                {:data {:progress pct}})}
   :states
   {:idle
    {:on {:upload/start {:target :uploading}}}
    :uploading
    {:entry :start-upload
     :on {:upload/progress  {:action :record-progress}
          :upload/succeeded {:target :done}
          :upload/failed    {:target :failed}}}
    :failed {}
    :done {}}})
```

Machine process algebra:

```clojure
{:id :upload/main
 :kind :process
 :refinement :machine-process
 :source-form {:kind :reg-machine :id :upload/main}
 :inputs [[:event :upload/start]
          [:event :upload/progress]
          [:event :upload/succeeded]
          [:event :upload/failed]]
 :output [:runtime [:rf.runtime/machines :snapshots :upload/main]]
 :storage :runtime-db
 :evaluation #{:on-transition :on-reply}
 :lifecycle :machine-instance
 :commands [{:effect :rf.http/managed
             :reply-to :upload/replied}]}
```

Selector source:

```clojure
(rf/reg-sub
  :upload/progress
  :<- [:rf/machine :upload/main]
  (fn [snapshot _]
    (get-in snapshot [:data :progress] 0)))
```

Selector algebra:

```clojure
{:id :upload/progress
 :kind :derivation
 :refinement :machine-selector
 :inputs [[:machine :upload/main [:data :progress]]]
 :output [:fact :upload/progress]
 :storage :ephemeral
 :evaluation :on-demand
 :lifecycle :subscription-cache-entry
 :derive #'app.upload/progress}
```

The machine is the stateful process. The selector is an ephemeral derivation over
that process's materialized snapshot.

### Graph Inspection Output

A live graph for an article page might expose:

```clojure
{:frame :main
 :route {:id :route/article
         :params {:slug "welcome"}
         :nav-token 17}
 :facts
 {[:runtime [:rf.runtime/routing :current]]
  {:kind :process
   :refinement :route-fact
   :storage :runtime-db}

  [:sub [:article/page "welcome"]]
  {:kind :derivation
   :storage :ephemeral
   :evaluation :on-demand}

  [:resource [[:rf.scope/session {:tenant-id "acme"}]
              :article/by-slug
              {:slug "welcome"}]]
  {:kind :process
   :storage :runtime-db
   :status :loaded
   :owners #{[:route :route/article 17]}}}
 :edges
 [{:from [:runtime [:rf.runtime/routing :current :params :slug]]
   :to [:sub [:article/page "welcome"]]
   :role :input}
  {:from [:runtime [:rf.runtime/routing :current :params :slug]]
   :to [:resource [[:rf.scope/session {:tenant-id "acme"}]
                   :article/by-slug
                   {:slug "welcome"}]]
   :role :param}
  {:from [:resource [[:rf.scope/session {:tenant-id "acme"}]
                    :article/by-slug
                    {:slug "welcome"}]]
   :to [:sub [:article/page "welcome"]]
   :role :input}]}
```

An Xray panel can render this as one graph even though the runtime mechanisms are
route state, resource cache, and subscription cache.

## Rationale

The proposal reduces conceptual duplication without flattening useful
differences.

A subscription and a resource are different operationally. A subscription is a
pure ephemeral derivation in a cache. A resource is a stateful process with
remote authority, owners, stale suppression, and host-transient in-flight work.
But both have declared inputs, an output fact, storage, evaluation policy, and
lifecycle. Naming that shared shape makes the system easier to explain and
inspect.

The same is true for flows. A flow should not become the default for derived
view values, because it writes `app-db`. But the function that computes a flow
output is the same kind of whole-value derivation as a subscription body. The
important difference is explicit in the algebra: `:app-db` storage,
`:after-event` evaluation, frame lifecycle, and a materialized output path.

Machines are not "just derivations." They are process definitions with
transition tables, snapshots, actions, timers, and actor lifecycle. Selectors
over machine snapshots are derivations. The algebra lets tools show both without
confusing one for the other.

The graph-inspection goal also fits re-frame2's AI-amenability posture. An agent
or tool should not have to read arbitrary handler bodies to discover the primary
shape of a feature. It should enumerate declared facts and processes, then dive
into functions only when it needs implementation detail.

## Alternatives Considered

### Keep Separate Vocabularies

The status quo avoids new terminology but leaves every cross-cutting tool to
reconstruct the same model independently. It also makes documentation harder:
users learn "subscription", "flow", "resource", "route", and "machine selector"
as separate ideas before learning the smaller truth that all are facts or
processes over the frame fold.

### Replace Existing APIs With One `reg-fact`

A single direct authoring API would be elegant on paper and hostile in practice.
Subscriptions, flows, resources, routes, and machines have different ergonomics
and migration paths. The algebra should be the normalized view, not the only
source form.

### Make Flows The General Derivation Mechanism

Flows materialize into `app-db`, which is exactly why they are useful and exactly
why they should remain narrow. Most derived view values should stay ephemeral.
Making flows general would add writes, schemas, SSR payload, and time-travel
surface to values that do not need durable state.

### Make Subscriptions Cover Resources

Subscription-driven fetching blurs reads and causes. re-frame2's model keeps
views passive: views read resource state; routes, events, machines, or explicit
ensures cause work. Resources therefore need process semantics, not only
derivation semantics.

### Make Machines The Only Process Abstraction

Machines are the most formal process kind and should remain available for
workflow and protocol logic. Requiring every resource read or route transition to
be authored as a full machine would be too heavy. The algebra lets common
processes be first-class while preserving machines for the cases that need state
charts.

### Make Delta Execution The Default

Default deltas would optimize the hard cases by making the common case harder.
Whole-value functions are simpler to write, test, migrate, and reason about.
Deltas belong behind an optional law-checked contract.

### Let v1 Compatibility Decide The Shape

v1 migration matters, but v1 compatibility cannot be the highest-order design
rule. The correct design is the common algebra over the frame fold. v1 source
forms can lower into it where possible; code that depended on hidden reaction
objects, ambient state, or side effects inside derivations should be migrated
deliberately rather than preserved as architecture.

## Backwards Compatibility And Migration

This proposal is compatibility-preserving at the source-form layer. Existing
re-frame2 source forms remain valid unless their owning specs change them.

### Subscriptions

`reg-sub` lowers directly:

- layer-1 subs become ephemeral, on-demand derivations over the app-db
  projection;
- static `:<-` subs become ephemeral, on-demand derivations over declared
  subscription inputs;
- input-function subs become parametric derivations whose realized edges appear
  in the live graph per concrete query vector.

Mechanical migration from re-frame v1 is strongest for the common shapes:

```clojure
;; v1-style signal function using subscribe
(reg-sub
  :article/page
  (fn [[_ id]]
    [(subscribe [:article/by-id id])
     (subscribe [:comments/for-article id])])
  (fn [[article comments] [_ id]]
    ...))
```

becomes the v2 input-producer shape:

```clojure
(rf/reg-sub
  :article/page
  (fn [[_ id]]
    [[:article/by-id id]
     [:comments/for-article id]])
  (fn [[article comments] [_ id]]
    ...))
```

Signal functions that return arbitrary reactions or do work other than naming
query vectors are not mechanical. They should be rewritten as declared inputs,
events, resources, flows, adapters, or machines depending on what they were
really doing.

### Runtime Subscriptions

Runtime subscriptions become the same derivation shape over the runtime-db
projection. This is an internal/framework surface first, but the graph should
represent it explicitly so route and machine facts do not look like app-db
facts.

### Flows

v1 `on-changes` and re-frame2 `reg-flow` forms lower to materialized
`:after-event` derivations. Mechanical migration is possible when the old form
was a pure function of paths and wrote one value to one app-db path.

Flows that performed side effects, depended on hidden mutable state, or encoded
process lifecycle should migrate to events/resources/machines rather than to a
flow.

### Resources

Ad hoc HTTP state in app-db is not mechanically rewritten in the general case.
The target is clear, though: resource identity, params, scope, owners,
freshness, and status become declared process metadata, and views read passive
resource facts through subscriptions.

### Routes

Route match, params, query, fragment, transition, and error become runtime-db
facts. Route `:resources` declarations become route-owned process activation
edges. Existing route declarations remain the source form.

### Machines

Machine snapshots remain runtime-db process state. Existing machine selectors
lower to ephemeral derivations over machine snapshot facts. Workflow logic stays
in machine definitions; selectors and view-models become graph-visible facts.

## Reference Implementation / Bead Plan

1. Add the derivation/process vocabulary to the normative specs: fact,
   derivation, process, declared input, output, storage class, evaluation
   policy, lifecycle, owner, materialized output, and ephemeral output.
2. Extend subscription registration metadata so `reg-sub`, static `:<-`, input
   functions, runtime subscriptions, and live sub-cache entries expose algebra
   views.
3. Extend flow metadata so `reg-flow` exposes inputs, output path, storage,
   evaluation policy, lifecycle, and source coordinates through the algebra
   view.
4. Map resource declarations and live resource entries into process nodes,
   including scope, params, owners, local storage, remote authority, work-ledger
   links, and selectors.
5. Map route declarations and live route slices into route fact nodes, including
   route-owned resource activation edges.
6. Map machine definitions, snapshots, spawned actors, and machine selectors
   into process and derivation nodes.
7. Add an internal graph-inspection helper for static and live graphs. Xray may
   consume it first; no public authoring primitive or stable graph accessor
   ships in this first slice.
8. Add documentation examples that show source forms and algebra views side by
   side.
9. Add conformance fixtures for lowering, storage/evaluation/lifecycle
   classification, graph edges, and whole-value behavior.
10. Defer executable delta optimization until a real performance use case needs
    it; land only the optional law in the first pass.

## Validation / Conformance

A conforming implementation should satisfy these checks:

- **Source lowering:** each registered source form exposes an algebra view with
  the expected id, kind, inputs, output, storage, evaluation, and lifecycle.
- **Static graph:** static graph inspection reports only registration-known
  edges and marks parametric edge sets as parametric.
- **Live graph:** live graph inspection reports concrete query vectors, active
  resource keys, route owners, machine instances, and realized parametric edges.
- **Storage classification:** ephemeral outputs do not appear in durable
  frame-state; app-db outputs appear in app-db; runtime-db outputs appear in
  runtime-db; host-transient support state declares teardown.
- **Materialization:** a materialized derivation's output path contains the same
  whole value that its derivation function computes from the same inputs.
- **Evaluation policy:** on-demand reads do not cause durable writes;
  after-event derivations obey event atomicity; reply-driven processes suppress
  stale replies by declared identity.
- **Lifecycle:** destroying a frame releases frame-owned graph nodes and
  host-transient state; subscription disposal releases cache-entry nodes;
  route exit releases route owners; machine destroy releases machine-owned
  resource leases and timers.
- **Tool redaction:** graph inspection can summarize or redact sensitive
  params, scopes, and values without losing graph structure.
- **Public API staging:** the first slice does not export a new public
  authoring primitive or stable graph accessor; any public API requires a later
  recorded ruling after the internal shape proves stable.
- **Delta law:** any provided `:step-delta` passes the commuting law against
  whole-value recomputation. Implementations with no delta support still
  conform.

## Open Issues

**All six issues were ruled 2026-06-11 (Mike, in-session), merging three
convergent analyses.** Original recommendations are kept verbatim
as the record of what was ruled; dispositions and riders are inline.

1. What is the public name and exact return shape for graph inspection?
   **Recommendation:** defer public naming; let Xray and conformance tests
   consume an internal accessor first and stabilize the name only after the
   shape survives real use (the project-before-primitive discipline this EP
   applies to source forms applies to accessors too).
   **Disposition: as recommended — internal but STRUCTURED, not Xray-private.**
   The internal accessor has a testable shape from day one: `:mode`
   (`:static` | `:live`), canonical node ids (EP-0012 identity rules),
   explicit edge records, source-form metadata, the
   storage/evaluation/lifecycle classifications, parametric markers, and
   **EP-0015 redaction metadata** (graph payloads carry source coordinates and
   value summaries — egress-bearing once any tool ships them off-box).
   Graduation gate: the public name ships when a consumer **beyond** the two
   named first consumers (Xray, conformance fixtures) needs it; facade
   classification per name at graduation. One-accessor rule: the EP-0013
   module-view demand trigger and this accessor are projections of the same
   registry — never two overlapping internal accessors.
2. Should `:remote` remain a storage class, or should the final spec split it
   into `:authority :remote` plus a required local storage class? This EP uses
   both to keep the distinction visible. **Recommendation:** split — the live
   resource example already carries `:remote {:authority :server}` alongside
   `:storage :runtime-db`, which shows authority and local storage are two
   facts; the final spec should make `:storage` always local and `:authority`
   a separate key, keeping `:remote` only as prose shorthand.
   **Disposition: split, as recommended.** `:storage` always names the local
   representation home; `:authority` is a separate map — e.g.
   `{:kind :remote :system :server :transport :rf.http/managed}` — where
   `:transport` is a **projection of the Spec 016 registration fact, never a
   second authoritative home** (mirrors are recomputable projections).
   Shipped resources are the proof case (`:authority` remote + `:storage`
   runtime-db describes them exactly). `:remote` is removed as a storage
   class before graduation; this EP's §Definitions storage-class table is
   swept to the split in the action wave.
3. How much route/resource graph information should be available statically when
   route `:resources` params are arbitrary functions? **Recommendation:** the
   same rule as parametric subscriptions — report the declaration and mark the
   edge set `:parametric`; only the live graph shows realized edges.
   **Disposition: as recommended, with two riders.** The **don't-execute
   rule**: static inspection NEVER invokes param/scope functions — no side
   effects, no nondeterminism, no hidden runtime assumptions in static
   analysis. And the **named-resolver enrichment**: an EP-0016
   `{:from-db <resolver>}` scope gives the static graph genuinely static
   facts — the resolver id and its declared inputs appear statically even
   while params remain `:parametric`.
4. Which machine selector source forms should be standardized beyond ordinary
   subscriptions over `[:rf/machine ...]`? **Recommendation:** none in the
   first slice; ordinary subscriptions over the machine snapshot sub remain
   the only standardized selector form until a concrete ergonomic gap appears.
   **Disposition: as recommended.** Machines do not become a second
   subscription system; the graph recognizes machine-derived subscriptions as
   ordinary nodes. The trigger is a demonstrated ergonomic gap, not
   anticipation.
5. Where should optional delta contracts live normatively: reactive substrate,
   flows, a new derivation spec, or the graph-inspection spec?
   **Recommendation:** state the commuting law once in whatever spec home the
   whole-value derivation vocabulary lands in, with Spec 006 and Spec 013
   referencing it — the law is about derivation semantics, not about any one
   mechanism or about inspection.
   **Disposition: as recommended, with the home NAMED:** a standalone contract
   document in the Managed-Effects/Runtime-Subsystems family (working name
   `spec/Derivations.md`) — the corpus's proven pattern for cross-cutting
   shape contracts — holding the node vocabulary, the classification tables
   (with the issue-2 split), the static/live rule, and the commuting law;
   Specs 006/013/016/012/005 cite it. The law is **semantic-only** in the
   first slice: whole-value derivation is the contract; no executable delta
   protocol ships; a mechanism that later supports deltas must satisfy the
   law.
6. How does the algebra view move from registrar-derived metadata into app
   values/runtime realms if EP-0013 is accepted? **Recommendation:** no action
   until EP-0013 is ruled; the node shape is deliberately the row an app value
   would carry (see Relationships), so the move is a relocation, not a redesign.
   **Disposition: the condition FIRED — EP-0013 was accepted the same day.**
   The disposition updates from "wait" to "coordinate": the **shape-agreement
   obligation** recorded on the EP-0013 action epic verifies
   the EP-0014 node shape and the EP-0013 descriptor shape agree before
   either EP's spec slice lands. This EP's first slice stays
   registrar-derived; the relocation lands inside EP-0013's D2 — "a
   relocation, not a redesign," now enforced rather than hoped. Rider: the
   node schema **reserves optional `:realm/id` / `:app/id` / `:module/id`
   fields** (never required in the first slice) so relocation needs no
   breaking reshape.

## Recommendation

Adopt this EP as the standards-track proposal for the derivation/process
vocabulary. It should land first as spec language, metadata, graph inspection,
and examples, not as a replacement authoring API.

The design gives re-frame2 the shared language needed for large SPAs:
subscriptions, flows, resources, route facts, and machine selectors become one
family of declared facts and processes over the frame fold, while their source
forms and runtime behavior remain appropriately distinct.
