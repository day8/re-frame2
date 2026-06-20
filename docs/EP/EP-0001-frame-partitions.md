# EP-0001: Frame App/Runtime Partitions

Status: final

> **`final` means the decisions are settled.** The two-partition frame contract
> graduated into its normative home,
> [`spec/002-Frames.md` §The two-partition frame contract](../../spec/002-Frames.md#the-two-partition-frame-contract);
> where this EP and the spec differ, the **spec governs**, and this EP remains the
> design record. (The `:rf.runtime/*` children this partition reserves are
> organized by the follow-on [Runtime Subsystem Contract EP](EP-0006-runtime-subsystem-contract.md)
> in [`spec/Runtime-Subsystems.md`](../../spec/Runtime-Subsystems.md), without
> reopening this contract.) The fourteen deferred calls were
> ruled by Mike on 2026-06-08 (see [Resolved Decisions](#resolved-decisions)).
> The design-review follow-ups were also closed before finalization: Appendix
> A's concrete recommendations are disposed by Resolved Decisions 3, 4, 7, and
> 13, the §Partition Keys naming note, and the follow-on [Runtime Subsystem
> Contract EP](EP-0006-runtime-subsystem-contract.md); Appendix B's upstream
> handler-contract premise is settled by Resolved Decision
> [15](#resolved-decisions). The partition is locked. The two implementation
> gaps tracked against those settled rulings have now also shipped — see
> [Implementation errata](#implementation-errata). Finalizing the *decisions*
> did not, on its own, assert the *implementation* was gap-free; the errata
> ledger below tracked that separately to its close. Historical proposal
> sections below still preserve superseded alternatives such as the
> multi-container representations; the Resolved Decisions — including the
> 2026-06-11 `rf2-cg7llv` errata on Decision 2, which blessed `:db-before` /
> `:db-after` as the canonical projection slot names — and the graduated specs
> are authoritative where they conflict with earlier proposal voice.

## Implementation errata

The EP decisions are final and the implementation has shipped in full: **every
tracked erratum below is closed.** This section is kept as a closed record of the
build-completion work that followed the decision-freeze; none of it reopens any
ruling. These were implementation gaps against settled rulings, not open
decisions. The EP is implementation-complete.

### Resolved errata

The two build-gaps-against-settled-rulings below are **fixed**; they are kept here
as a closed record and no longer reopen any ruling:

- **`rf2-3939ig`** *(fixed — PR #3707)* — framework-authority minting was wired
  only off `:rf/machine?`, so routing/SSR framework-authority handlers (which
  legitimately write runtime-db) tripped the `:rf.warning/app-handler-runtime-effect`
  ownership diagnostic on every navigation. The fix generalized minting to a
  reserved `:rf/framework-authority?` registration-meta key that the routing and
  SSR facades stamp, honouring Resolved Decision 4's convention-plus-diagnostics
  stance (the diagnostic now fires only on genuine app-handler runtime writes).
- **`rf2-1m6rf1`** *(fixed — PR #3714)* — the EP-0002 R3 frame-stamp rename folded
  into this partition (`:frame` → `:rf.frame/id` for runtime context) had landed
  additively: `assemble-initial-ctx` injected the bare `:frame` coeffect alongside
  `:rf.frame/id`, and internal consumers still read the retired spelling. The fix
  dropped the bare `:frame` coeffect, migrated the internal consumers to
  `:rf.frame/id`, and pinned the exact coeffect key set with a conformance test
  (the sanctioned `:frame` survivors — public dispatch/subscribe opt, fx-handler
  ctx, trace tags — are kept, per §Namespacing).

## Abstract

This proposal defines a frame as owning two durable state partitions:

1. **app-db**: user-owned application data.
2. **runtime-db**: framework-owned durable runtime data.

The event pipeline threads both partitions through one coherent cascade:

```clojure
{:coeffects
 {:db             <app-db>
  :event          [:some/event]
  :rf.db/runtime  <runtime-db>
  :rf.frame/id    <frame-id>}

 :effects
 {:db             <next-app-db>
  :rf.db/runtime  <next-runtime-db>}}
```

The ordinary `:db` key remains the app-facing app-db partition. The reserved
`:rf.db/runtime` key is the framework-owned runtime partition. Full-frame tools
such as SSR hydration, epoch restore, time travel, and Xray may project both
partitions as a single **frame-state** value:

```clojure
{:rf.db/app     <app-db>
 :rf.db/runtime <runtime-db>}
```

The purpose is to remove the current ownership footgun where framework runtime
state is stored under `:rf/runtime` inside the user app-db map, allowing an
ordinary fresh `:db` return to delete machine, routing, elision, SSR, or tool
state.

## Motivation

Today, re-frame2 stores per-frame runtime state inside app-db under
`:rf/runtime`.

That means this ordinary handler:

```clojure
(rf/reg-event-db
  :session/reset
  (fn [_db _event]
    {:session/status :anonymous}))
```

does not only replace user state. It replaces the whole app-db map, including
runtime state. If the previous value was:

```clojure
{:session/status :authenticated
 :rf/runtime
 {:machines {:snapshots {:door/main {:state :open :data {}}}}
  :routing  {:current {:id :route/home}}}}
```

the handler commits:

```clojure
{:session/status :anonymous}
```

and the runtime state disappears.

This is worse than ordinary data loss. Machine snapshots, route state, elision
declarations, SSR hydration metadata, epoch records, resource caches, and tool
state are live framework facts. Dropping them can leave external handles,
queued work, timers, subscriptions, and traces pointing at durable state that no
longer exists.

The existing `:rf.warning/runtime-state-dropped` diagnostic is useful, but it is
not the right final boundary. A warning still asks app code to preserve
framework internals. The design should make ordinary app code unable to delete
runtime state by returning app data.

The current diagnostic also fires only after a durable ordinary `:db` commit
has dropped a live `:rf/runtime` subsystem. Legitimate full replacements such
as epoch restore and tool db reset bypass that commit path today, and direct
runtime writes can bypass it as well. This confirms the warning is a
containment measure for the current layout, not the ownership model.

## Goals

This proposal aims to:

1. Make accidental runtime-state deletion structurally impossible for ordinary
   app handlers.
2. Preserve the ergonomic re-frame meaning of `:db`: app handlers receive and
   return the app's data.
3. Keep app-db and runtime-db committed, restored, hydrated, and inspected as
   one coherent frame transition.
4. Make framework ownership visible through qualified names.
5. Preserve time travel, SSR, Xray, pair tooling, schema elision, machines,
   routing, and future resource caches.
6. Give tools and AI agents intentional access to app-db, runtime-db, or the
   whole frame-state.
7. Avoid compatibility aliases while re-frame2 is pre-alpha.

## Non-Goals

This proposal does not:

- require a particular physical storage layout;
- define a generic N-partition database system;
- change ordinary app subscriptions into runtime-db readers;
- authorize application handlers to write runtime-db directly;
- move every framework-private cache, listener, in-flight handle, or tool buffer
  into runtime-db;
- settle every epoch record field name;
- redesign machine snapshot internals;
- define the resource query API, except by reserving a runtime partition for it.

## Relationships

This EP is foundational for other EPs that store framework-owned state.

- **Foundational for resource queries.** The [Resource Queries
  EP](EP-0003-resource-queries.md) stores its cache in the framework-owned runtime
  partition (`:rf.runtime/resources`) this EP introduces, so this partition
  should land — or at least its key vocabulary be fixed — before resources rely
  on it.
- **Substrate for the runtime-subsystem contract.** The [Runtime Subsystem
  Contract EP](EP-0006-runtime-subsystem-contract.md) adopts Appendix A item 5
  from this EP and grades the `:rf.runtime/*` children this partition creates.
  Its normative home is [`spec/Runtime-Subsystems.md`](../../spec/Runtime-Subsystems.md);
  it organizes runtime-db's children without reopening this EP's two-partition
  frame contract.
- **Shares a path with the machine `:data` schema EP.** The [Machine `:data`
  Schema EP](EP-0005-machine-data-schema.md) touches the same
  `[:rf/runtime :machines :snapshots]` path this EP renames to
  `[:rf.runtime/machines :snapshots]`. If both land, the EP-0005 machine `:data`
  schema redaction-bridge work sequences *after* this partition rename so it targets the
  final path. The two are otherwise independent.
- **Composes with explicit frame target resolution.** Both partitions are
  frame-owned; resolving the frame target precedes committing or projecting
  either partition. See the [Explicit Frame Target Resolution
  EP](EP-0002-frame-target-resolution.md).

## Specification

### Terminology

| Term | Meaning |
|---|---|
| frame | The runtime boundary: id, router, queue, reactive container, sub-cache, lifecycle, config, trace ring, epoch history, and durable state partitions. |
| app-db | The user-owned application data partition. It is exposed as the ordinary `:db` coeffect/effect. |
| runtime-db | The framework-owned durable, serializable runtime partition. It is exposed internally as the reserved `:rf.db/runtime` coeffect/effect. |
| frame-state | A coherent snapshot/projection containing both app-db and runtime-db. |
| transient runtime state | Framework-owned state outside frame-state because it is host-specific, cache-like, or lifecycle-only, such as HTTP in-flight registries, SSR request/response slots, trace listeners, epoch capture buffers, and sub-cache entries. |
| runtime handles | Non-serializable host handles outside frame-state, such as timers, AbortControllers, listeners, promises, and substrate objects. |

Public docs should teach:

```text
app-db is your data.
runtime-db is re-frame2's durable bookkeeping.
frame-state is the coherent snapshot containing both.
```

Transient runtime state is implementation/tooling machinery, not part of
frame-state unless a future design deliberately promotes a serializable fact into
runtime-db.

### Partition Keys

The event pipeline uses these keys:

| Key | Location | Owner | Meaning |
|---|---|---|---|
| `:db` | coeffects/effects | app | User app-db partition. Kept unqualified for re-frame compatibility and ergonomics. |
| `:event` | coeffects | framework/app | The event vector. Kept unqualified because it is inherited re-frame vocabulary. |
| `:rf.db/runtime` | coeffects/effects | framework | Managed durable runtime partition. |
| `:rf.frame/id` | coeffects | framework | Current frame id. |

The full-frame projection uses:

| Key | Meaning |
|---|---|
| `:rf.db/app` | App-db inside a frame-state value. |
| `:rf.db/runtime` | Runtime-db inside a frame-state value. |

`:rf.db/app` is not the ordinary app handler key. Ordinary handlers continue to
use `:db`. `:rf.db/app` exists so full-frame snapshots can name both
partitions without overloading `:db`.

Naming note: this table trades CLARITY's one-concept-one-name principle — app-db
now has two spellings, `:db` in handler context and `:rf.db/app` in the
frame-state projection — for reserved-namespace discipline (the frame-state
projection is a new framework-owned structure, so its keys are qualified). The
cost is real and accepted; the surviving asymmetry (`:db` unqualified,
`:rf.db/runtime` qualified) deliberately mirrors the ownership asymmetry the
partition is built on.

### Namespacing

Unqualified keys are allowed only where they are inherited from the established
re-frame contract. New framework-owned facts, coeffects, effects, and durable
paths must be qualified.

Keep inherited keys such as:

```clojure
:db
:event
:dispatch
:dispatch-n
:fx
```

Use qualified names for new framework-owned facts:

```clojure
:rf.db/runtime
:rf.db/app
:rf.frame/id
:rf.frame/epoch
:rf.route/match
:rf.ssr/request
:rf.ssr/response
:rf.trace/call-site
```

Use qualified names for runtime-db children:

```clojure
{:rf.runtime/machines  <machine-runtime>
 :rf.runtime/routing   <routing-runtime>
 :rf.runtime/elision   <elision-runtime>
 :rf.runtime/ssr       <ssr-runtime>
 :rf.runtime/resources <resource-runtime>}
```

Avoid ambiguous names such as `:runtime`, `:frame`, or `:rf/frame`, where the
value might be an id, object, state map, or context. Prefer attribute-shaped
names such as `:rf.frame/id`.

This rename is about event context, framework fx/cofx context, and other
runtime-owned maps that carry a frame id as data. It does not, by itself,
rename every existing public `:frame` option or trace tag. The current
specification deliberately keeps `:frame` as the public dispatch/subscribe
option and as the canonical trace routing tag. If those surfaces are renamed,
that is a coordinated Frames/Instrumentation change, not an accidental side
effect of this partition.

### Pre-Alpha Keyword Cleanup

This proposal intentionally chooses final vocabulary now.

The migration cost is real: implementation code, tests, examples, docs, skills,
MCP tools, and migration rules that mention old framework-owned keys must be
updated. That cost is preferable to carrying compatibility aliases into the
public language.

Concrete replacements:

| Old or ambiguous shape | Replacement |
|---|---|
| app-db root `:rf/runtime` | runtime-db coeffect/effect `:rf.db/runtime` |
| full snapshot app partition `:db` | frame-state key `:rf.db/app` |
| event-context or fx-context frame id facts such as `:frame` or `:rf/frame` | `:rf.frame/id` |
| runtime child `:machines` | `:rf.runtime/machines` |
| runtime child `:routing` | `:rf.runtime/routing` |
| runtime child `:elision` | `:rf.runtime/elision` |
| runtime child `:ssr` | `:rf.runtime/ssr` |
| future runtime child `:resources` | `:rf.runtime/resources` |

Legacy-key diagnostics should point to the replacement vocabulary. They should
not silently normalize the old names.

### Event Context

A standard event context threads both partitions:

```clojure
{:coeffects
 {:db             {:todo/items []}
  :event          [:todo/add "Write EP"]
  :rf.db/runtime  {:rf.runtime/machines {}
                   :rf.runtime/routing  {}}
  :rf.frame/id    :app/main}

 :effects
 {}}
```

An ordinary app handler may destructure only the app data it needs:

```clojure
(rf/reg-event-fx
  :todo/add
  (fn [{:keys [db]} [_ title]]
    {:db (update db :todo/items conj {:title title})}))
```

Framework interceptors and framework handlers may read or write
`:rf.db/runtime`:

```clojure
(assoc-in context
          [:effects :rf.db/runtime :rf.runtime/routing :current]
          route-match)
```

The runtime partition is in the context because it is part of the causal input
and output of a frame transition. It is not hidden from the interceptor system.
The contract is that ordinary application code treats it as reserved.

Existing ancillary coeffects such as dispatch source, trace id, call-site
metadata, and other instrumentation inputs may continue to ride in the context.
They are orthogonal to the partition split. The load-bearing change is that the
state-bearing inputs are no longer conflated: `:db` is app-db,
`:rf.db/runtime` is runtime-db, and `:rf.frame/id` names the frame whose
transition is running.

### User Handler Semantics

`reg-event-db` handlers receive and return only app-db:

```clojure
(rf/reg-event-db
  :something
  (fn [db _event]
    {:new 1}))
```

If the frame currently contains:

```clojure
{:rf.db/app
 {:session/status :authenticated
  :user/id 42}

 :rf.db/runtime
 {:rf.runtime/machines {...}
  :rf.runtime/routing  {...}}}
```

the handler return value commits as:

```clojure
{:rf.db/app
 {:new 1}

 :rf.db/runtime
 {:rf.runtime/machines {...}
  :rf.runtime/routing  {...}}}
```

The same rule applies to ordinary `:db` effects from `reg-event-fx`:

```clojure
(rf/reg-event-fx
  :something
  (fn [{:keys [db]} _event]
    {:db {:new 1}}))
```

The `:db` coeffect is app-db, not frame-state. That keeps the user handler
mental model stable: `db` means the app's data.

### Runtime Write Semantics

Framework code writes runtime-db through `:rf.db/runtime` effects, privileged
runtime APIs, or internal interceptors. It does not write runtime state through
ordinary app `:db` effects.

The top-level event effect map must therefore be widened from the current
closed `#{:db :fx}` shape to a closed set that includes the reserved
`:rf.db/runtime` state effect. Shape policing, schema descriptions, docs, and
diagnostics must be updated together. Unknown top-level effect keys should
remain errors or logged-and-skipped according to the existing effect-map
contract; `:rf.db/runtime` is the only new state-bearing top-level key this EP
introduces.

Examples:

- machine handlers read and write snapshots under
  `[:rf.runtime/machines :snapshots <id>]` inside runtime-db;
- route events write current route state under
  `[:rf.runtime/routing :current]` inside runtime-db;
- SSR hydration writes metadata under
  `[:rf.runtime/ssr :hydration]` inside runtime-db;
- schema-derived elision writes declarations under
  `[:rf.runtime/elision]` inside runtime-db;
- future resource queries write cache state under
  `[:rf.runtime/resources]` inside runtime-db.

These writes still participate in one atomic event commit. A cascade can
produce both app-db changes and runtime-db changes, and the frame installs the
combined result as one coherent transition.

Durable runtime writes must not bypass that transition boundary by directly
swapping the app-db container after `:db` has already committed. Existing
direct container-write sites, such as machine lifecycle fxs, schema-derived
elision population, SSR hydration helpers, epoch restore/reset helpers, and
test fixtures, must be classified and moved to one of three explicit surfaces:

- an app-db-only replacement;
- a runtime-db-only replacement;
- a full frame-state replacement.

Host handles remain outside frame-state. For example, timers,
AbortControllers, listeners, and promise handles are still teardown resources,
not serializable runtime-db values. The runtime-db records enough durable facts
to reconstitute or clean up those handles, but it does not store the handles
themselves.

The durability boundary is intentional. Framework-owned state that is
cache-like, host-specific, or request-lifetime remains outside runtime-db unless
the design deliberately promotes a serializable fact into frame-state. Examples
include SSR request/response accumulators, pending error buffers, head snapshots,
streaming continuation registries, HTTP in-flight registries, AbortControllers,
retry timers, epoch capture buffers, epoch listeners, trace rings, sub-cache
entries, and flow registries or last-inputs dirty-check caches. These surfaces
must still be frame-scoped and torn down correctly, but full-frame
serialization, hydration, restore, and time travel must not pull them in by
accident.

### Guardrails

Because `:rf.db/runtime` is part of `:coeffects`, app code can technically read
it:

```clojure
(rf/reg-event-fx
  :debug
  (fn [cofx _]
    (keys (:rf.db/runtime cofx))))
```

That visibility is acceptable. Interceptors, flows, extension APIs, tests, and
tools need a uniform context model.

The rule is:

> Application code does not mutate `:rf.db/runtime` directly.

re-frame2 should strengthen that rule with diagnostics:

- warn or fail when a non-framework handler returns `:rf.db/runtime`;
- warn or fail when ordinary app code registers effects under
  `:rf.db/runtime`;
- **hard-reject** when app schemas try to describe runtime-db paths — `reg-app-schema` / `reg-app-schemas` throw `:rf.error/app-schema-runtime-path` at registration when a path's first segment is a `:rf.runtime/*` keyword, the `:rf.db/runtime` container root, or the legacy `:rf/runtime` root (rf2-k0ew8n). This is a **category error**, not a warnable misuse: app schemas validate only app-db, so a runtime path either detonates every dev commit or silently installs a validator over the wrong partition — there is no behaviour to soft-land and no legitimate caller, so it fails closed. (Runtime-db validation is **framework-owned and non-public** — the framework registers a `:rf/runtime-db` validator over the partition at boot; there is no `rf/reg-runtime-schema` export to redirect the user to. The honest remedy is to drop the runtime path: declare app data under app-db schemas, and for runtime-subsystem data use the owning subsystem's public surface where one exists, e.g. machine `:data-schema`.) The earlier "warn" framing here predates the fail-closed hardening campaign and is superseded;
- warn when examples or skills teach raw runtime path access;
- provide explicit extension APIs for code that intentionally participates in
  runtime behavior.

The implementation must define how it distinguishes framework-owned writers
from application writers. Acceptable mechanisms include reserved handler ids,
registration metadata stamped by framework registrars, or a small explicit
extension marker. Namespace heuristics alone are too weak for plugins, tests,
and optional artefacts.

This is the "user land / kernel space" analogy. The data lives in the same
running system, but ownership is real.

### Full-Frame Operations

Some operations must install or inspect the whole app/runtime snapshot:

- epoch restore;
- time travel;
- SSR hydration;
- frame reset;
- test fixture install;
- tool-driven replay;
- frame destroy records.

Those operations use explicit full-frame APIs, not ordinary app `:db` effects.

Current names should be audited rather than mechanically preserved. In the
existing implementation, `reset-frame!` is a lifecycle operation
(`destroy-frame!` followed by `reg-frame`), while `reset-frame-db!` is a
dev/tool app-db replacement that bypasses dispatch and records a synthetic
epoch. After the partition split, those names must either keep their narrower
meaning in documentation or be paired with explicit full-frame replacement
surfaces. A tool API named as a db replacement should not silently replace
runtime-db.

Possible surfaces:

```clojure
(rf/app-db-value frame-id)
;; => <app-db>

(rf/runtime-db-value frame-id)
;; => <runtime-db>

(rf/frame-state-value frame-id)
;; => {:rf.db/app <app-db>
;;     :rf.db/runtime <runtime-db>}
```

Names are illustrative. The required distinction is that app-facing APIs return
app-db by default, while tools and privileged runtime code request runtime-db or
frame-state explicitly.

Full-frame APIs must state which projection they use: app-db only, runtime-db
only, frame-state, or frame diagnostics including transient runtime state. The
default for serialization, SSR, and time travel is frame-state only; transient
side channels require an explicit trusted-local tooling API.

### Subscriptions And Flows

Ordinary layer-1 app subscriptions receive app-db:

```clojure
(rf/reg-sub
  :todo/items
  (fn [db _]
    (:todo/items db)))
```

Framework subscriptions read runtime-db through framework helpers:

```clojure
[:rf/machine :door/main]
[:rf.route/id]
[:rf.route/params]
```

App code should not reach into runtime-db directly for machine or route state.
It should use public framework subscriptions.

The subscription invalidation contract must follow the partition boundary:

- app subscriptions depending on app-db rerun when app-db changes;
- framework subscriptions depending on runtime-db rerun when runtime-db
  changes;
- subscriptions composed from both partitions rerun when either input changes;
- a runtime-only commit must still update views that read route or machine
  subscriptions;
- an app-only commit must not require app authors to carry runtime paths in
  their schemas or subscription code.

This can be implemented with one physical frame-state container, two coherent
containers, or partition-aware dirty flags. The observable requirement is that
runtime-only changes are not invisible to framework subs, and app-only changes
do not make `:db` mean frame-state.

Flows follow the same partition rule:

- ordinary flow inputs over app data read app-db;
- framework flows may read runtime-db through explicit qualified inputs;
- flow-produced `:db` writes update only app-db;
- flow-produced runtime writes use `:rf.db/runtime` and remain reserved.

If a flow or framework derivation reads both partitions, its dirty-check must
key on both input sets. A runtime-db write cannot be hidden from a flow merely
because the app-db partition was value-identical.

Flow materialized outputs that model app state remain app-db values; this EP
does not move ordinary flow output paths into runtime-db. Flow definitions,
topological order, and last-inputs dirty-check caches are runtime bookkeeping.
Unless a future design intentionally makes them durable frame-state,
restore/replay/rollback must recompute or clear these caches from the restored
app/runtime partitions so stale dirty-check rows cannot suppress recomputation.

### App Schemas

App schemas validate app-db, not the whole frame-state.

The old `:rf/runtime` schema becomes a runtime-db schema:

```clojure
:rf/frame-state
[:map
 [:rf.db/app :any]
 [:rf.db/runtime :rf/runtime-db]]
```

Applications can still register schemas for app paths. They do not register app
schemas under `:rf.db/runtime`.

Current specs and source model the runtime shape as `reg-app-schema
[:rf/runtime]`. That registration must move out of the application schema
surface. Runtime-db schemas may still exist, but they are framework-owned
validators over runtime-db, not application-owned validators over app-db.

Schema-derived elision remains app-schema-driven: applications mark app paths
with `:large?` or `:sensitive?`. The extracted declaration records are runtime
bookkeeping and should be written under `:rf.runtime/elision` in runtime-db,
not under an app-db path the app can accidentally replace.

### Mental Model

The explanatory analogy is:

```clojure
{:db             <user-land app-db>
 :rf.db/runtime  <kernel-space runtime-db>}
```

App handlers, ordinary `:db` effects, app subscriptions, and app schemas
operate in user land.

Machines, routing, SSR, elision, traces, future resources, and runtime
bookkeeping live in framework-owned runtime-db.

The frame is the whole running system. Epochs, SSR hydration, reset, and
time-travel operate on the whole frame-state because app-db and runtime-db are
causally linked. Normal application code crosses into runtime-db only through
public framework APIs, not raw map writes.

The formal terms remain `frame-state`, `app-db`, and `runtime-db`.

## Examples

### Fresh app-db return

Before this proposal, a fresh app-db return could drop `:rf/runtime`:

```clojure
(rf/reg-event-db
  :session/reset
  (fn [_db _]
    {:session/status :anonymous}))
```

After this proposal, the handler replaces only app-db:

```clojure
;; before
{:rf.db/app
 {:session/status :authenticated
  :user/id 42}

 :rf.db/runtime
 {:rf.runtime/machines {...}
  :rf.runtime/routing  {...}}}

;; handler return
{:session/status :anonymous}

;; after
{:rf.db/app
 {:session/status :anonymous}

 :rf.db/runtime
 {:rf.runtime/machines {...}
  :rf.runtime/routing  {...}}}
```

No preservation code is needed in the application handler.

### App and runtime update in one cascade

A route transition may update app data and runtime route state in one commit:

```clojure
{:db
 {:page/loading? false}

 :rf.db/runtime
 (assoc-in runtime-db
           [:rf.runtime/routing :current]
           {:id :route/account
            :params {:id 42}
            :query {}
            :nav-token nav-token})}
```

The exact internal effect shape is illustrative. The requirement is one
coherent app/runtime transition, not two independently visible writes.

### Machine snapshot read

Application code should use the public machine subscription:

```clojure
@(rf/subscribe [:rf/machine :door/main])
```

The machine runtime reads:

```clojure
[:rf.runtime/machines :snapshots :door/main]
```

inside runtime-db. Application code no longer reaches into app-db at:

```clojure
[:rf/runtime :machines :snapshots :door/main]
```

### Full-frame restore

Epoch restore installs both partitions:

```clojure
{:rf.db/app
 <old-app-db>

 :rf.db/runtime
 <old-runtime-db>}
```

It does not go through ordinary `:db` effect semantics, because ordinary `:db`
effects replace only app-db.

## Rationale

The core decision is not that re-frame2 must store everything in one physical
map.

The core decision is:

> A frame has two durable partitions, and the cascade commits them coherently.

An implementation may store the partitions as two maps, two containers, or one
internal aggregate. The public and internal contract is expressed through
partition keys in the event context and frame-state projection.

This design preserves the useful property of the current model:

> A frame transition remains one coherent app/runtime state change.

It removes the harmful property:

> User app code can no longer own or replace framework runtime state through
> ordinary `:db` returns.

The result is simpler to teach:

- app-db is user data;
- runtime-db is framework bookkeeping;
- frame-state is the coherent snapshot of both;
- ordinary app code uses `:db`;
- framework code uses `:rf.db/runtime`;
- tools can ask for either partition or both.

## Backwards Compatibility

This is a breaking change.

re-frame2 is pre-alpha, and the project posture favors elegance and correctness
over compatibility shims. The proposal therefore rejects long-lived aliases
such as `:rf/runtime`, `:runtime`, `:rf/frame`, or bare `:frame` for new
framework-owned facts.

Compatibility work should be limited to:

- migration diagnostics;
- lint rules;
- codemods;
- clear docs;
- tests that catch legacy path usage;
- short-lived warnings during implementation.

The stable vocabulary should be the final vocabulary.

## Migration

Migration is in-repo only — re-frame2 ships no external alpha — and is mechanical
rather than gradual:

- **Move runtime subsystems off app-db.** Machines, routing, elision, SSR, and
  related schemas move from `:rf/runtime` paths under app-db to the framework-owned
  runtime-db (`:rf.runtime/*`). Snapshots move from
  `[:rf/runtime :machines :snapshots]` to `[:rf.runtime/machines :snapshots]`.
- **Classify write authority at call sites.** Every existing `[:rf/runtime …]`
  reader/writer is reclassified: ordinary app handlers keep returning `:db`
  (app-db only); framework subsystems write runtime-db through privileged
  framework-authority paths.
- **Reserve `:rf.db/runtime` in the effect map.** The closed top-level event
  effect map (`:db`, `:fx`) gains `:rf.db/runtime` as a deliberate reserved key
  writable only by framework-authority handlers.
- **Update full-frame operations.** Epoch records, SSR hydration, `restore-epoch!`,
  reset, and destroy switch from app-db-only snapshots to frame-state projections.
- **Drive legacy access loud.** A short-lived `:rf/runtime`-access diagnostic eases
  the in-repo migration during implementation, then is removed; the stable
  vocabulary is the final vocabulary (see Backwards Compatibility above).

The detailed staged sequence lives in [Reference Implementation](#reference-implementation)
and [Bead Plan](#bead-plan) below.

## Security And Privacy Considerations

The current `:rf/runtime` location is not only a machine/routing correctness
problem. It is also a privacy problem.

Data-classification and elision state can live under runtime state. If a fresh
app-db return drops the mark set, later trace or tool egress may fail to redact
values that should be hidden. Moving elision state into runtime-db makes it part
of the protected framework partition.

Privacy-sensitive projection should still fail closed:

- off-box egress should not default to raw values when mark state is absent;
- runtime-db should be redacted or selectively projected for SSR and tools;
- Xray and pair tools should distinguish human-local inspection from AI/log
  egress;
- app schemas should not authorize application code to write runtime-db.

The App/Runtime Partition EP should be coordinated with data-classification and
Xray egress work so elision state is treated as privacy-load-bearing runtime
state, not incidental tooling state.

## Reference Implementation

The implementation should proceed in stages.

### 1. Specify the new contract

Update the normative docs:

- Frames: a frame owns app-db and runtime-db partitions.
- Conventions: remove `:rf/runtime` as a reserved app-db key; introduce
  `:db`, `:rf.db/runtime`, `:rf.db/app`, `:rf.frame/id`, and
  `:rf.runtime/*`.
- Runtime Architecture: show app-db and runtime-db as frame-owned partitions
  committed by one cascade.
- Reactive Substrate: clarify whether adapter containers physically hold one
  frame-state value or two coherent partition containers.
- State Machines: snapshots move to
  `[:rf.runtime/machines :snapshots]` inside runtime-db.
- Routing: the route slice moves to
  `[:rf.runtime/routing :current]` inside runtime-db.
- SSR: hydration installs a coherent frame-state.
- Instrumentation/Epoch: records distinguish app-db and frame-state.
- Schemas and Spec-Schemas: app schemas validate app-db; runtime schemas
  validate runtime-db; the `:rf/effect-map` shape allows reserved
  `:rf.db/runtime` effects; flow-output validation language is reconciled with
  the current pre-install flow-throw atomicity contract.

### 2. Introduce partition helpers

Add internal helpers before moving all call sites:

```clojure
(frame/app-db-value frame-id)
(frame/runtime-db-value frame-id)
(frame/frame-state-value frame-id)
(frame/replace-app-db! frame-id app-db)
(frame/replace-runtime-db! frame-id runtime-db)
(frame/replace-frame-state! frame-id frame-state)
(frame/commit-frame-transition! frame-id {:keys [app-db runtime-db]})
```

Names are illustrative. The design point is to stop making every call site know
the physical storage shape.

These helpers should become the only durable state write boundary. Existing
uses of raw `adapter/replace-container!` and `frame/swap-frame-db!` should be
audited and either rewritten through the helpers or documented as host-handle
teardown that deliberately does not alter frame-state.

### 3. Change event context and commit semantics

Change the dispatch pipeline:

- inject `:db` coeffect as app-db;
- inject `:rf.db/runtime` coeffect as runtime-db;
- inject `:rf.frame/id`;
- remove ambiguous frame/runtime context keys instead of supporting them as
  parallel spellings;
- widen the closed top-level effect-map shape from `:db` / `:fx` to include
  reserved `:rf.db/runtime`, and update the bad-effect-map diagnostic
  accordingly;
- interpret ordinary `:db` effect as app-db replacement;
- interpret reserved `:rf.db/runtime` effects as runtime-db writes only for
  framework-owned code paths;
- reject or warn if user code returns a frame-state-shaped value under `:db`;
- preserve pre-install atomicity: handler/interceptor/flow throws leave both
  partitions unchanged.

The commit path must emit enough change information for both subscriptions and
tools. A runtime-only commit should be visible to framework route/machine subs
and to Xray or pair tooling even when app-db is unchanged. An app-only commit
should preserve the existing app-facing `:db` semantics and should not require
tools to infer runtime changes from `:rf.event/db-changed` alone.

### 4. Move runtime subsystems

Move durable runtime subsystems:

| Old path | New path inside runtime-db |
|---|---|
| `[:rf/runtime :machines]` | `[:rf.runtime/machines]` |
| `[:rf/runtime :routing]` | `[:rf.runtime/routing]` |
| `[:rf/runtime :elision]` | `[:rf.runtime/elision]` |
| `[:rf/runtime :ssr]` | `[:rf.runtime/ssr]` |
| future `[:rf/runtime :resources]` | `[:rf.runtime/resources]` |

In a frame-state projection, those paths appear under `:rf.db/runtime`.

Machine snapshot-internal keys such as `:rf/history`, `:rf/after-epoch`, and
`:rf/machine-type` can remain inside machine snapshots. This proposal changes
where snapshots live, not their internal open-map shape.

The migration must move the whole runtime-owned subsystem, not only the most
visible leaf. For machines that includes `:snapshots`, `:system-ids`,
`:spawned`, and `:spawn-counter` or any successor allocator slots. For routing
that includes `:current`, `:pending-navigation`, navigation-token counters,
scroll restoration caches, and other per-frame routing internals. For elision
that includes both large-value declarations and sensitive-value declarations.

For SSR, only durable hydration metadata and serializable SSR facts move under
`:rf.runtime/ssr`. Server-only request/response accumulators, pending-error
buffers, head snapshots, streaming continuation queues, and host adapter state
remain transient side-channel state; they are cleared on frame destroy and
excluded from hydration payloads and epoch restores. Managed-HTTP in-flight
registries, abort controllers, retry/backoff timers, and transport promises
follow the same transient rule unless a future resource-cache design promotes a
serializable fact into `:rf.runtime/resources`.

### 5. Update subscriptions, flows, and coeffects

Audit:

- layer-1 sub execution;
- `inject-cofx :db`;
- any new `inject-cofx :rf.db/runtime`;
- path interceptors;
- app-db schema validation;
- flow input resolution;
- flow registry and last-input cache alignment;
- flow write effects;
- the `[:rf/machine <id>]` sub;
- route subs;
- partition-aware sub-cache invalidation;
- elision lookup during trace and wire projection;
- transient SSR, HTTP, epoch, trace, and sub-cache side-channel teardown and
  projection;
- test helpers that assert app-db shape.

Ordinary app subscriptions continue to see app-db. Framework subs and framework
flows read runtime-db through internal helpers or qualified inputs.

### 6. Update epoch, SSR, reset, and tools

Epoch records should make the snapshot unit clear.

Possible shape:

```clojure
{:frame-state-before <frame-state>
 :frame-state-after  <frame-state>
 :db-before          <app-db>
 :db-after           <app-db>}
```

Alternatively, keep `:db-before` / `:db-after` as app-db projections and add
`:frame-state-before` / `:frame-state-after` for full restore. This proposal
does not settle the exact epoch record shape, but the implementation must avoid
ambiguity.

Existing epoch code records `:db-before` / `:db-after` as app-db values and
`restore-epoch!` rewinds to `:db-after`. Under this proposal, a restore that is
meant to revive machines, routes, elision state, or resources must restore
frame-state, not just the app-db projection. If `:db-before` / `:db-after`
remain in the record for tool diff ergonomics, they should be named and
documented as app-db projections.

SSR hydration should serialize and install an allowed frame-state projection,
not a raw runtime dump. The current hydration payload shape uses an app-db
slice plus runtime metadata stashed during `:rf/hydrate`; the partitioned
design should make the app/runtime split explicit on the wire and should
validate both partitions fail-closed before installation.

SSR hydration allowlists operate over app-db plus the serializable runtime-db
projection. They must not serialize server-only request/response accumulators,
head snapshots, streaming continuation registries, pending error buffers, or
host handles. If the client needs one SSR fact, such as a server render hash,
project that fact into `[:rf.runtime/ssr :hydration]` and validate it there.

Xray should show app-db and runtime-db as separate views inside one frame.
Xray, pair-MCP, and epoch egress should treat runtime-db as its own redactable
projection. Off-box projection must redact or omit both app-db and runtime-db
according to policy, while transient side-channel state is absent by default and
available only through explicit trusted-local diagnostic APIs.

### 7. Add guardrails

Add diagnostics for:

- user app-db containing a legacy `:rf/runtime` root;
- user code registering schemas under runtime-db paths;
- ordinary app `:db` effects returning a frame-state wrapper;
- non-framework handlers returning `:rf.db/runtime` effects;
- old framework-owned coeffect/effect/context keys such as `:rf/runtime`,
  `:runtime`, `:rf/frame`, or bare `:frame` when they mean runtime-db or frame
  id;
- raw reads of `[:rf/runtime ...]` in examples, docs, tests, or skills;
- full-frame install attempted through ordinary dispatch;
- durable runtime writes performed through raw app-db container swaps instead
  of partition helpers;
- transient runtime side-channel state serialized into frame-state, SSR
  payloads, or epoch records without an explicit projection.

The diagnostics should teach:

```text
app-db is user data. runtime-db lives in the frame runtime partition.
Use [:rf/machine <id>] / route subs / tool APIs instead of raw runtime paths.
```

### 8. Update docs, examples, skills, and migration

Rewrite examples that seed machine snapshots under `:rf/runtime`.

Update:

- human guides;
- API docs;
- specs;
- examples and testbeds;
- Xray docs;
- pair-MCP docs;
- re-frame2 skills;
- migration rules.

The human guide should teach:

```text
The frame owns user land and runtime state. Your handlers see user land through
:db. Runtime state is threaded through the context as :rf.db/runtime for
framework machinery and explicit extension points. Time travel restores the
whole frame.
```

## Test Plan

Conformance tests should verify:

- `reg-event-db` receives only app-db;
- ordinary `:db` effects replace only app-db;
- `:rf.db/runtime` is present in coeffects;
- `:rf.frame/id` is present in coeffects;
- the top-level effect-map shape accepts reserved `:rf.db/runtime` and still
  rejects unrelated unknown keys;
- ambiguous framework-owned context keys such as `:rf/frame`, `:runtime`, and
  `:rf/runtime` are not emitted as compatibility aliases;
- runtime-db survives fresh app-db returns;
- non-framework app handlers cannot write `:rf.db/runtime` without the planned
  diagnostic;
- runtime-only commits invalidate framework route and machine subscriptions;
- app-only commits do not force ordinary app subscriptions to read or preserve
  runtime-db;
- full-frame restore restores both app-db and runtime-db;
- machine snapshots move with epoch restore;
- route state moves with epoch restore;
- SSR hydration installs both partitions without double-initializing runtime;
- SSR hydration payloads exclude request/response/head/streaming side channels
  and host handles while preserving explicit hydration metadata such as a server
  render hash;
- HTTP in-flight handles, abort controllers, retry timers, epoch capture
  buffers, trace rings, flow dirty-check caches, and sub-cache entries are not
  serialized into frame-state;
- flow materialized outputs remain app-db values while dirty-check caches are
  recomputed or cleared on rollback and restore;
- `app-db-value` returns only user app-db;
- tool/full-frame APIs return the frame-state projection;
- off-box epoch, Xray, and pair projections redact or omit runtime-db according
  to projection policy;
- app schemas validate only app-db;
- runtime schemas validate runtime-db;
- legacy `:rf/runtime` writes emit the planned diagnostic;
- direct runtime writes cannot bypass the partition commit helper unnoticed;
- Xray can inspect app-db and runtime-db separately;
- frame destroy still records coherent before/after state.

## Rejected Ideas

### Documentation only

Keep `:rf/runtime` inside app-db and document that app handlers must preserve it
when returning a fresh map.

This leaves the footgun active, requires app authors to preserve framework
internals, and fails the pre-alpha correctness bar.

### Preserve `:rf/runtime` at the commit boundary

Keep the physical shape, but ordinary app `:db` commits automatically carry
forward the previous `:rf/runtime`.

This fixes the common accidental drop, but keeps runtime state inside the
user-owned app-db value and makes `:db` no longer literally mean "the app map".
It may be useful as temporary containment, but it is not the destination.

### Reject writes that drop or modify `:rf/runtime`

Keep the current shape but turn the warning into an error when ordinary app
handlers drop or modify runtime state.

This prevents silent corruption, but it makes common fresh-map handlers fail and
still asks users to know about `:rf/runtime`.

### Physical frame-state wrapper as the public design

Physically store:

```clojure
{:rf.db/app     <app-db>
 :rf.db/runtime <runtime-db>}
```

inside the frame's reactive container.

This is an acceptable implementation strategy, but the public design should be
"two durable partitions committed coherently", not "one wrapper map".

### Fully separate runtime store outside frame state

Move runtime state outside frame durable state and omit it from frame-state
snapshots.

This gives a clean ownership boundary but loses the simple property that
time-travel, SSR, and reset operate on one coherent frame-state.

### Generic N-partition frame database

Generalize frames into arbitrary named durable partitions.

This is more ceremony than the current problem needs and is harder to teach
than app-db/runtime-db.

## Resolved Decisions

The fourteen calls this EP left open were ruled by Mike on 2026-06-08 (also
recorded in bead `rf2-h0d6s6`). All fourteen are now final and authoritative; the
rulings below settle the design so the partition can be locked. Where a ruling
fixes vocabulary or shape that earlier sections describe as illustrative, the
ruling here is the binding form.

1. **Accessor names — fixed.** The frame-state readers are
   `app-db-value` / `runtime-db-value` / `frame-state-value`, and the mutators are
   `replace-app-db!` / `replace-runtime-db!` / `replace-frame-state!`. The
   illustrative names in §Full-Frame Operations and §Reference Implementation step 2
   are adopted verbatim as the public names.

2. **Epoch record shape — frame-state canonical, app-db projections optional.**
   `:frame-state-before` / `:frame-state-after` are the canonical snapshot fields;
   the optional app-db projections kept for tool diff ergonomics are
   `:db-before` / `:db-after` (see the 2026-06-11 errata note below). The
   canonical-plus-optional-projection shape from §6 is the one adopted.

   > **Errata — 2026-06-11 (Mike, bead `rf2-cg7llv`).** This decision's original
   > text named the optional app-db projection slots `:app-db-before` /
   > `:app-db-after` and stated "There is no `:db-before` / `:db-after`." That
   > naming did not land: the reference implementation, the conformance fixtures,
   > the skill docs, and most of `spec/` + `docs/guide/` ship the projection slots
   > as **`:db-before` / `:db-after`**. Per one-name-per-fact (EP-0007), the
   > de-facto short names are now blessed as canonical — renaming the corpus to the
   > originally-ruled names was the costlier path and is rejected. The
   > canonical-plus-optional-projection *shape* (frame-state canonical, app-db
   > projection optional) ruled here is unchanged; only the projection *slot names*
   > are corrected. The "alternatively, keep `:db-before` / `:db-after`" option in
   > §6 is therefore the adopted naming.

3. **Representation — one physical container plus projection reactions.** A frame
   holds **one** physical frame-state container; app-db and runtime-db are derived as
   **projection reactions** over it. This resolves the representation and
   invalidation alternatives together (see Decision 7): the "two coherent
   containers" and "explicit dirty flags" alternatives in §Subscriptions And
   Flows are not used. This is the single-container model Appendix A §3
   recommends.

4. **`:rf.db/runtime` reserved by convention, not a security boundary.**
   `:rf.db/runtime` is reserved **by convention**, not enforced as a capability
   boundary: app code can technically emit it. Docs and dev-diagnostics frame the key
   as belonging to the framework and to runtime extensions, and steer ordinary app code
   away from it, but the framework does not reject an app-authored `:rf.db/runtime`
   write at the commit boundary. (This declines the authority-at-registration hard-
   rejection posture Appendix A §2 argues for; the convention-plus-diagnostics stance is
   the ruled one.)

5. **Runtime writes — support both whole-value replacement and operation-style
   writes.** Framework runtime writes support **both** whole-value `:rf.db/runtime`
   replacement **and** operation-style (helper / op) writes. Normal subsystem writes
   prefer ops/helpers; whole-value replacement is for restore, hydration, and reset.
   This is the "both" branch of the §Runtime Write Semantics question.

6. **Change-event vocabulary — `:rf.event/db-changed` stays app-db-only; add a
   sibling.** `:rf.event/db-changed` remains **app-db-only**. A new
   `:rf.event/frame-state-changed` is added for partition-tagged frame-state commits.
   The "widen `:rf.event/db-changed` with partition tags" alternative is rejected in
   favour of the distinct-sibling event.

7. **Sub-cache invalidation — derives from the one-container/projection model.**
   Partition-aware sub-cache invalidation falls out of the one-container-plus-
   projection-reactions model (Decision 3): a partition's projection reaction recomputes
   on commit and propagates only when its slice changed by identity. No explicit dirty
   flags are introduced unless a specific adapter needs them. This is the projection-
   equality mechanism described in Appendix A §3.

8. **Legacy `:rf/runtime` — hard error in the final form.** In the final form, a legacy
   app-db root `:rf/runtime` is a **hard error**. A temporary migration **warning** is
   permitted only during the in-repo migration campaign, then removed — consistent with
   the short-lived-diagnostic posture in §Backwards Compatibility and §Migration.

9. **`reset-frame!` — resets the whole frame.** `reset-frame!` resets the **whole
   frame**: lifecycle plus **both** partitions (app-db and runtime-db). It is not an
   app-db-only reset.

10. **`reset-frame-db!` — split into app-db and frame-state surfaces.** The current
    `reset-frame-db!` is replaced by an app-db-only surface, named `reset-app-db!`
    (equivalently `replace-app-db!` per Decision 1), plus a **distinct**
    `replace-frame-state!` for full-frame replacement. A tool API named as a db
    replacement no longer silently replaces runtime-db — the concern §Full-Frame
    Operations raises.

11. **App schemas — keep `reg-app-schema` and "app-db schema"; clarify scope.** The
    public API name `reg-app-schema` and the term "app-db schema" are kept. The docs
    clarify that an app-db schema validates **only the app partition**, not the whole
    frame-state. No rename to "app partition schema".

12. **Flow defs and last-input caches — transient.** Flow definitions and last-input
    dirty-check caches are **transient** runtime bookkeeping: they are recomputed or
    cleared on restore and rollback, never serialized as durable frame-state. Durable
    flow **outputs** that model app state remain app-db values. This confirms the
    §Subscriptions And Flows treatment.

13. **runtime-db durable/transient boundary — fixed.** runtime-db holds the
    **serializable facts** needed for restore, SSR-hydration, and time-travel. Host
    handles, request slots, trace rings, in-flight HTTP, and dirty caches stay
    **transient** (frame-scoped, torn down on destroy, never serialized). This is the
    "survives epoch-restore and SSR-hydration" line Appendix A §4 names as the single
    durable/transient principle.

14. **Off-box projection policy — redacted/omitted by default.** runtime-db is
    **redacted or omitted off-box by default**. A **trusted-local** caller may request
    richer diagnostics explicitly. This is the fail-closed default in §Security And
    Privacy Considerations and §6.

15. **Handler contract — whole-db return retained, knowingly.** Appendix B asked for
    the scoped/change-describing handler-contract question to be settled before the
    partition locked. Ruled: re-frame2 keeps `reg-event-db`'s whole-db return. The
    re-frame mental model is the product; the general clobber-co-located-owners hazard
    *inside* app-db is accepted as the price of `(fn [db event] …)`, and this partition
    fences only the framework's slice. This is a decision, not a default — a future
    `scoped-event-handlers` EP may reopen it, but the partition was built knowing what
    it does not fix.

## Bead Plan

1. Decision bead: adopt app-db/runtime-db as two durable frame partitions and
   record the final key vocabulary.
2. Spec bead: update Frames, Conventions, Runtime Architecture, Reactive
   Substrate, Machines, Routing, SSR, Instrumentation, Schemas, and API docs.
3. Helper bead: introduce app-db/runtime-db/frame-state helper functions
   without changing behavior.
4. Event context bead: inject `:rf.db/runtime` and `:rf.frame/id` into
   coeffects while preserving `:db` as app-db; update effect-map schemas and
   shape policing for reserved `:rf.db/runtime`.
5. Event commit bead: scope ordinary `:db` effects to app-db and add privileged
   runtime/full-frame commit paths; emit partition-aware change signals.
6. Runtime migration bead: move machines, routing, elision, SSR, and related
   schemas from `:rf/runtime` to runtime-db.
7. Epoch/SSR bead: update snapshot, restore, hydration, reset, and destroy
   semantics to use frame-state projections.
8. Subscriptions/tooling bead: update framework subs, Xray, pair tools, and
   egress surfaces to distinguish app-db, runtime-db, and frame-state.
9. Guardrail/migration bead: reject or warn on legacy `:rf/runtime` access,
   ambiguous context-key use, and unauthorized `:rf.db/runtime` effects.
10. Docs/examples/skills bead: rewrite human docs, AI specs, examples, and
    skills to teach the new model.

## Recommendation

Adopt the two-partition frame model: a frame owns a user-owned **app-db** (`:db`)
and a framework-owned **runtime-db** (`:rf.db/runtime`), committed coherently by
one cascade and projectable as a single frame-state value for SSR, epoch restore,
time travel, and Xray.

This makes accidental runtime-state deletion structurally impossible for ordinary
app handlers while preserving the ergonomic re-frame meaning of `:db`, keeping one
coherent app+runtime snapshot, and giving app-db a pure application contract an
agent can read without framework noise. The pre-alpha posture favors the clean
break (no long-lived `:rf/runtime` aliases) over compatibility shims.

The design-review appendices below are historical review records, not open
issues. Their dispositions are recorded above: the single-container/projection
model (Resolved Decisions 3 and 7), convention-plus-diagnostics write authority
rather than a capability gate (Resolved Decision 4), the durable/transient
boundary (Resolved Decision 13), the `:rf.db/app` naming tradeoff (§Partition
Keys), the follow-on runtime-subsystem contract (EP-0006), and the retained
whole-db handler contract (Resolved Decision 15).

## Source Findings

This proposal synthesizes:

- `ai/findings/2026-06-06.app-db-claude.md`
- `ai/findings/2026-06-06.app-db-codex.md`

Both findings agree on the destination: application code owns app-db,
re-frame2 owns runtime state, and the frame owns both as one coherent
frame-state snapshot.

At proposal time, this review also checked the then-current local implementation
and specs. The findings below are historical evidence for why the migration was
needed, not current build status:

- `implementation/core/src/re_frame/router.cljc` then built event
  contexts with `:db`, `:event`, and bare `:frame`; emits
  `:rf.warning/runtime-state-dropped` only after durable ordinary `:db`
  commits; and treats `:db` as the single app-db container write boundary.
- `implementation/core/src/re_frame/events.cljc` and
  `spec/Spec-Schemas.md` then defined the top-level event effect map as
  closed to `:db` and `:fx`, so `:rf.db/runtime` must be added there as a
  deliberate reserved key.
- Machines, routing, schemas, SSR, and epoch code then read or wrote
  `[:rf/runtime ...]` paths under app-db. Some write through ordinary `:db`
  effects, while others used direct container helpers such as
  `frame/swap-frame-db!` or `adapter/replace-container!`; the migration had to
  classify those call sites before the partition was real.
- Instrumentation and schema specs deliberately used bare `:frame` in public
  opts and trace tags at the time. This EP's `:rf.frame/id` rename therefore
  had to be applied to runtime context/fx context first, with any public
  `:frame` rename left to the owning Frames and Instrumentation specs.
- Epoch records then stored `:db-before` and `:db-after` as app-db values.
  A partitioned restore needed an explicit frame-state snapshot or an
  unambiguous sibling shape.
- The SSR artefact deliberately keeps request slots, response accumulators,
  pending error buffers, head snapshots, and streaming continuation registries in
  side-channel atoms so they do not ride hydration payloads. The partition
  migration had to keep that privacy and lifecycle boundary.
- Managed HTTP keeps in-flight request handles, abort controllers, retry timers,
  and actor indexes in registries outside app-db. These are host handles or
  transient runtime state, not durable runtime-db values.
- Flows materialize outputs into app-db but keep registrations and `last-inputs`
  dirty-check rows in per-frame registries. Restore and rollback semantics had to
  keep those caches aligned or recompute them.
- Epoch, Xray, and pair tooling store histories, capture buffers, listeners,
  trace rings, and mount attribution outside app-db. This EP's "tool state"
  language had to mean explicit frame-state projections, not every
  tool-side cache.

## Appendix A — Design review: elegance, simplicity, sophistication

*Review notes (2026-06-07). Commentary on the proposal above; not normative. Each item is a "consider", not a mandate, and several interlock.*

The destination is right and the document is unusually complete. This review does not re-argue the partition (it is correct) — it asks where the design could be **more elegant, simpler, more rigorous, or more sophisticated**. Those four lenses *are* re-frame2's own primary lenses (ELEGANCE + CLARITY + CORRECTNESS + GOOD PRACTICE), so the review weighs each push against the project's distinctive ethos: AI-first / spec-is-the-artefact, effects-as-data, loud-failure / reject-misuse, reserved-namespace discipline, the value-oriented reactive substrate, and re-frame2's habit of *naming a recurring contract* (Managed-Effects, Ownership) rather than re-deriving it. Several pushes below are simply that ethos pointed back at the EP.

One framing the EP itself undersells: the partition is also an **AI-legibility / CLARITY win**, not only a correctness patch. Once app-db holds nothing but app data, `reg-app-schema` describes a *pure* application contract an agent can read without framework noise — the spec-is-the-artefact payoff. The Motivation sells the footgun fix; it should sell the legibility win too.

Five substantive pushes, then minor notes, then what should be left alone.

### 1. Elegance vs namespace discipline — give app-db one name, *or* own the two

The one aesthetic wart is that **app-db has two names**: `:db` in the event context, `:rf.db/app` in the frame-state projection. But this is a genuine collision between *two* re-frame2 values, not a simple miss:

```clojure
;; EP's projection                ;; alternative — one name for app-db
{:rf.db/app     <app-db>          {:db            <app-db>
 :rf.db/runtime <runtime-db>}      :rf.db/runtime <runtime-db>}
```

- **CLARITY (one concept, one name)** favours reusing `:db` in the projection, so `:db` *always* means app-db with nothing to keep in sync. The asymmetry (`:db` unqualified, `:rf.db/runtime` qualified) then *mirrors the ownership asymmetry* the EP is built on, and rides re-frame2's blessed exception: `:db` / `:event` / `:fx` are the inherited unqualified keys, and app-db is exactly that inherited concept.
- **Reserved-namespace discipline** favours `:rf.db/app`: the frame-state projection is itself a *new framework-owned structure*, and the EP's own rule is that new framework-owned surfaces are qualified. By that rule `:rf.db/app` is the EP applying its own discipline — not the "cosmetic symmetry" I first took it for.

So the EP's choice is principled. The cost it pays is real (two spellings for the one concept users live in); the cost the alternative pays is an unqualified key inside a framework projection. I lean mildly toward one-name `:db` — app-db is *the* inherited exception and the surface users touch most — but it is close, and which way it should go is genuinely an ethos-internal call. The actionable point is therefore smaller than "change it": **the EP should state which value it is prioritising and why** (§Partition Keys currently asserts `:rf.db/app` without naming the CLARITY cost it is trading away). Owning the tension is the re-frame2 move; leaving it implicit is the only real defect here.

### 2. Rigour — pin the write-authority mechanism; it is load-bearing

The headline guarantee is "accidental runtime deletion is **structurally impossible**." But the mechanism that makes app-writers distinguishable from framework-writers is deferred (Resolved Decision 4; §Guardrails: "the implementation must define how… namespace heuristics alone are too weak"). **Until that mechanism is named, the guarantee is only as strong as a diagnostic** — i.e. no stronger than today's `:rf.warning/runtime-state-dropped`, which the Motivation rightly rejects. The EP's strongest claim currently rests on its least-specified part.

Pin it, and pin it at the cleanest altitude: **authority is conferred at registration, by which registrar created the handler.** `reg-event-db` / `reg-event-fx` mint *app-authority* handlers; the framework's own registrars (machines, routing, resources, SSR) mint *framework-authority* handlers. Then:

- an app-authority handler that emits `:rf.db/runtime` is **rejected at the commit boundary** (a hard `:rf.error/*`), not "warned" — the footgun cannot fire;
- `:rf.db/runtime` need not be a freely-writable key in the app-facing effect map at all (it widens the closed effect set only for framework-authority handlers);
- the "user land / kernel space" analogy becomes a real capability boundary rather than a convention with diagnostics bolted on.

This also resolves the question §5's extension work raises: **a library owning a future `:rf.runtime/<lib>` child gets runtime-write authority by registering through a framework-blessed registrar** — not by namespace luck. Authority-at-registration is the one mechanism that makes both the guarantee and the extension story principled.

It is also the more on-ethos posture in two further ways. **Loud-failure / reject-misuse:** the EP hedges "warn *or* fail" (§Guardrails, §7), but pre-alpha re-frame2 rejects misuse rather than tolerating it — authority-at-registration lets these be hard `:rf.error/*` rejections, not warnings (the warning posture is a back-compat reflex the project has disowned elsewhere). **AI-legibility:** authority conferred by registrar is *enumerable registration metadata* — an agent or the AI-Audit can ask "is this handler app- or framework-authority?" exactly as it queries registrar kind today — rather than an opaque runtime check. It extends the registrar-kind model (`:event`, `:resource`, `:mutation`, machine handlers) that already exists, instead of inventing a parallel notion of trust.

### 3. Simplicity — commit to one physical frame-state value, and partition-invalidation falls out for free

The EP keeps representation open (one container / two containers / dirty flags — Resolved Decisions 3 & 7) on the principle "contract over representation." That principle is right in general, but here it **defers the single hardest sub-problem** — partition-aware reactive invalidation — into "implementation detail", and the reactive substrate is exactly what must not be hand-waved.

Committing to **one frame-state value in the reactive container, with two cached projection reactions** collapses the problem into the *existing* reaction machinery:

```clojure
frame-state  (one signal: {:db <app-db> :rf.db/runtime <runtime-db>})
   ├── app-db     = (reaction (:db @frame-state))            ; layer-1 input for app subs
   └── runtime-db = (reaction (:rf.db/runtime @frame-state)) ; layer-1 input for framework subs
```

A runtime-only commit bumps `frame-state`; the `app-db` projection reaction recomputes, finds `(:db …)` `identical?`, and **does not propagate** — app subs neither re-render nor recompute. An app-only commit is symmetric. The EP's four invalidation requirements (§Subscriptions And Flows) are then satisfied by reaction-deref equality that re-frame already has, instead of new "partition-aware dirty flags." This is both **simpler** (no new invalidation machinery) and **more concrete** (Resolved Decision 7 mostly disappears). The "two coherent containers" option, by contrast, multiplies the substrate plumbing for no contract benefit. I would commit to single-container in the spec and demote the alternatives to "ports may differ if they preserve the projection-equality semantics."

### 4. Simplicity (document) — state the durable/transient boundary once

The durable-vs-transient line is re-derived per subsystem in at least four places (Motivation, §Runtime Write Semantics, §4 Move runtime subsystems, Test Plan, Source Findings), each re-listing machines / routing / elision / ssr / HTTP / epoch. It compresses to **one principle plus one table**:

> A fact lives in runtime-db **iff it must survive epoch-restore and SSR-hydration**. Everything else (host handles, request-lifetime accumulators, caches, in-flight registries, trace rings) is transient: frame-scoped, torn down on destroy, never serialized.

State that once; make every "what moves / what stays" passage a row in a single table keyed by subsystem. The EP shrinks materially and stops risking drift between its own enumerations.

### 5. Sophistication — name the "runtime subsystem" as a contract (it is already latent)

The EP treats runtime-db's children as an ad-hoc list (`:rf.runtime/machines`, `:rf.runtime/routing`, `:rf.runtime/elision`, `:rf.runtime/ssr`, `:rf.runtime/resources`). But every one of them already shares the **same five properties**:

1. a reserved sub-tree of runtime-db;
2. a write-authority (its framework code) — §2;
3. a read API (subs: `[:rf/machine <id>]`, `:rf.route/*`, `:rf.resource/state`) — never raw paths;
4. a serialization / elision / projection policy (what hydrates, what redacts);
5. a teardown contract (durable facts vs transient handles).

That recurring shape is a **runtime-subsystem contract**, and naming it is the deepest available altitude. The payoff is threefold: it turns the ad-hoc list into *instances of one contract* (de-duplicating §4/§6/Test-Plan — see #4); it gives the §5 plugin seam and future resource/integration work a **principled home** (a library registering a new runtime child is "a new runtime subsystem", first-class, not a special case); and it makes the per-subsystem serialization/elision/teardown rules a single conformance checklist instead of prose repeated five times.

Crucially this is **not** the "generic N-partition database" the EP rightly rejects: that was *user-facing arbitrary top-level partitions*. This is an *internal organizing contract for runtime-db's own children* plus a blessed-extension seam — the same distinction as "the framework has subsystems" vs "users get arbitrary partitions." The EP rejected the wrong neighbour; this is the one worth adopting.

And it is not an imported abstraction — it is **re-frame2's own organising habit**. `Managed-Effects.md` already names a recurring shape (the eight properties) so new *effect* surfaces grade against one checklist; `Ownership.md` already maps every contract surface to its owning spec. A runtime-subsystem contract is the **durable-state analogue of Managed-Effects** — the identical "name the shape once, grade instances against it" move, applied to runtime-db's children instead of to effects. Because the AI-Audit already grades surfaces against the Managed-Effects checklist, this contract slots straight into existing tooling: machines / routing / resources / future integrations become enumerable, audit-gradeable instances rather than prose. That is spec-is-the-artefact doing what it is for.

### Minor notes

- **Two prefixes for one area.** The parent is `:rf.db/runtime` but its children are `:rf.runtime/*`. Either justify the split (the children are globally greppable when detached — a real tooling benefit) or align them; right now it reads as two naming schemes for the same region.
- **Scope creep of `:rf.frame/id`.** The `:frame` → `:rf.frame/id` *context-key* rename is really the EP-0002 (Explicit Frame Target Resolution) concern riding along because both touch the event context. Consider splitting it out so this EP is purely the partition; coupling two renames makes both harder to land and review.
- **Cost of threading runtime-db every event.** Confirm the runtime-db coeffect is injected **by reference** (persistent structure, no copy) and that an app-only commit performs **no** runtime re-commit — otherwise every pure app event pays for a partition it never touches. Worth one sentence in §Event Context.

### What I would not change

- **Two partitions, not N** — correct; resist generalizing the *partition* even while naming the *subsystem* contract (#5).
- **`:db` stays app-db for handlers** — the load-bearing ergonomic invariant; keep it (and extend it to the projection per #1).
- **Contract-over-representation as a principle** — right; #3 argues only to *commit* the representation in the reference impl, not to over-specify it for all ports.
- **Rejecting the overlay-protect stopgap as the destination** — correct; it is containment, not ownership.

### Net

The EP lands on the right design (the findings' "A2 → frame-state split"). The four pushes that would make it more elegant / simpler / more rigorous / more sophisticated, in priority order: **(2)** pin authority-at-registration so "structurally impossible" is earned; **(1)** give app-db one name (`:db` in the projection too); **(3)** commit to single-container frame-state and get partition invalidation from projection-equality; **(5)** name the runtime-subsystem contract — which also unblocks extension/plugin and resource work cleanly. (3) and (5) interlock with EP-0003 Resource Queries and future runtime-subsystem extensions; landing the authority model (2) is the prerequisite that makes all of them honest.

None of these are imports. Each argues *from* re-frame2's own values — loud-failure (2), name-the-recurring-contract (5), the value-oriented equality substrate (3), and spec-as-artefact / AI-legibility (3, 4, 5, and the partition itself). The single genuine *ethos-internal* tension is (1) — CLARITY vs namespace discipline — and the right resolution there is not for this review to crown a winner but for the EP to **name the value it trades and why**, which is the move the rest of the document already models well.

## Appendix B — Questioning the premises

*Review notes (2026-06-07). Appendix A reviewed the design **within** the EP's accepted frame ("given a partition, do it well"). This appendix steps **outside** it: is the partition the right shape at all, or a well-built workaround for an assumption that should be questioned first? Not normative — a challenge to the premises, raised because pre-alpha is the only time it is cheap to raise. The two appendices are complementary: Appendix A is safe to adopt regardless; Appendix B is a decision to make **before** the partition is locked, because the partition's shape depends on the answer.*

Separate two things that get conflated. re-frame2's **native** values — the causal model (events cause, views are passive), effects-as-data, frames as isolated contexts, AI-first / spec-is-the-artefact, loud-failure toward correctness — are right, and they *are* the elegance. This appendix does not touch them. The blockers are two **inherited** v1 assumptions that the native values quietly protect from scrutiny, and that the EP treats as fixed points while redesigning everything around them.

### Premise 1 — "a handler returns the whole db"

This is the root cause of the entire partition, and the EP is a workaround for it, not a fix of it. The chain is mechanical: handlers return a whole map → the framework needs somewhere to keep runtime state → "one value" puts it in app-db → a fresh-map return clobbers it → so we need a partition, an authority model, and reject-rules. **None of that apparatus exists if a handler never holds the whole map.**

And the partition fixes only the **framework-vs-app** instance of a **general** flaw. "Whole-map-return clobbers co-located owners" is still live *inside* app-db: in a large app, feature A's `(fn [_ _] {:fresh})` handler still wipes feature B's state. The partition fences the framework's slice and leaves the general problem standing — so it is, by Appendix A's own altitude test, a special case layered on shared infrastructure rather than a deepening of it. The general fix is the **handler contract**: a handler sees and returns *its scoped view*, or *describes* its writes, instead of replacing a shared map. Illustrative shapes (the point is the contract, not the spelling):

```clojure
;; today — replaces everything in the handler's (whole-db) scope
(rf/reg-event-db :session/reset (fn [_db _] {:session/status :anonymous}))

;; scoped view — handler only ever sees/returns its declared slice
(rf/reg-event-db :session/reset
  {:owns [:session]}
  (fn [session _] {:status :anonymous}))   ; cannot touch anything it does not own

;; change-describing — handler never holds the map at all
(rf/reg-event :session/reset
  (fn [_ _] {:db/set {[:session :status] :anonymous}}))
```

Under either, the clobber-co-located-owners bug is **structurally impossible** — for the framework *and* for app features — and the partition collapses to a degenerate case (the framework is just one more "owner"). re-frame2 already ships the seed of this: the `path` interceptor scopes a handler to a sub-tree. The elegant move is to make scoping the *model*, not opt-in plumbing. Notably, this alternative is **absent from the EP's Rejected Ideas** — not because it was weighed and dropped, but because it lives one level up from the frame the Rejected Ideas section reasons within.

The honest cost: the whole-db model's appeal is `(fn [db ev] (assoc db …))` — the cleanest possible "pure function from old state to new state." A scoped or change-describing contract is more composable and isolation-safe but adds a declaration or a small write-DSL, which is friction in the 95% case. That trade is the real decision — but it is a *different and more fundamental* decision than "how do we partition," and it should be made first.

### Premise 2 — "all rewindable state is one co-located snapshot"

Every findings doc and this EP treat the unified time-travel snapshot as a constraint to preserve — it is *why* runtime must live with app-db. But co-location is **assumed necessary, not shown to be**. If runtime state were a *fold over the event log* rather than a stored value, app-db could be purely the app's, no partition needed, and time-travel would be replay-to-N with runtime falling out for free.

This lever is weaker, and the EP should not chase it: replay collides with irreversible effects (a machine that already fired an HTTP request cannot be cleanly re-folded) — the same constraint that correctly killed full fx-rollback (see [013 §Why this asymmetry](https://github.com/day8/re-frame2/blob/main/spec/013-Flows.md)). So pure event-sourcing is not the answer. But it is worth naming, because the co-location premise is currently *assumed away* rather than *decided*, and the decision ("runtime is snapshotted, not derived, because effects make derivation unsound") is a stronger foundation than treating co-location as axiomatic.

### The real blocker is a value-precedence, not a value

Underneath both premises is an unresolved precedence between two re-frame2 values that point opposite ways here:

> **"Pre-alpha masterpiece — no back-compat shims, question everything for elegance"** vs **"preserve the re-frame mental model."**

The partition sits exactly on that fault line, and **heritage is winning by default, not by decision**: `:db` ergonomics and `reg-event-db`-returns-the-db are treated as immovable while everything else is up for redesign. The "no back-compat shims" value explicitly *grants permission* to question the handler contract; the "preserve the mental model" value is what holds it in place. Neither is wrong — but right now the conflict is being resolved silently, which is the one thing a spec-is-the-artefact project should not do with a load-bearing choice.

### What this means for this EP

The partition is a **sound local fix** and Appendix A's improvements stand. But adopting it **commits to the whole-db contract** and forecloses the general fix — so the sequencing matters:

1. The **handler-contract question is upstream of the partition.** Settle it (or consciously defer it) *before* locking the partition, because if scoped/change-describing handlers are ever adopted, this EP shrinks to a footnote.
2. If the answer is "keep the whole-db model" (a legitimate outcome), the EP should **say so, and say why** — i.e. record that the partition exists because re-frame2 chose to preserve `reg-event-db`'s whole-db return over the more general scoped contract. That turns an unexamined default into a decision.
3. This deserves its own page — a sibling `scoped-event-handlers` EP — rather than a buried decision, because it is bigger than the partition and several other surfaces (feature isolation, interceptors, flows) depend on the same contract.

### The honest counterweight

There is a real argument for keeping the heritage: the re-frame mental model is *the reason re-frame exists*. A "more elegant" framework that nobody recognizes as re-frame may win the design and lose the audience. Familiarity is itself a value, and `(fn [db ev] …)` is a large part of why people reach for re-frame at all. So preserving the whole-db contract is **defensible** — possibly even correct. The critique is not "the heritage is wrong." It is: **right now the heritage is winning by inertia, and the partition is the elaborate cost of a premise no one has decided to keep.** Decide it on purpose.
