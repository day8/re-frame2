# EP: Frame App/Runtime Partitions

Status: proposal

Date: 2026-06-06

Related:

- [Guide 02 - app-db](../../guide/02-app-db.md)
- [Guide 04 - Events and the cascade](../../guide/04-events-and-the-cascade.md)
- [Guide 18 - Frames](../../guide/18-frames.md)
- [Guide 21 - Runtime model](../../guide/21-dynamic-model.md)
- [Spec 002 - Frames](https://github.com/day8/re-frame2/blob/main/spec/002-Frames.md)
- [Spec 005 - State Machines](https://github.com/day8/re-frame2/blob/main/spec/005-StateMachines.md)
- [Spec 006 - Reactive Substrate](https://github.com/day8/re-frame2/blob/main/spec/006-ReactiveSubstrate.md)
- [Spec 009 - Instrumentation](https://github.com/day8/re-frame2/blob/main/spec/009-Instrumentation.md)
- [Spec 011 - SSR](https://github.com/day8/re-frame2/blob/main/spec/011-SSR.md)
- [Spec 012 - Routing](https://github.com/day8/re-frame2/blob/main/spec/012-Routing.md)
- [Runtime Architecture](https://github.com/day8/re-frame2/blob/main/spec/Runtime-Architecture.md)
- [Conventions](https://github.com/day8/re-frame2/blob/main/spec/Conventions.md)

## Summary

This enhancement proposes that every frame owns two durable state partitions:

1. **app-db**: user-owned application state.
2. **runtime-db**: framework-owned durable runtime state.

The event pipeline threads both partitions through the normal interceptor,
handler, flow, and effect machinery:

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

The ordinary `:db` key remains the user app-db partition. The reserved
`:rf.db/runtime` key is the managed runtime partition. Both are present in the
event context, but ordinary application code treats `:rf.db/runtime` as
framework-owned.

This replaces the current model where framework-owned durable state sits inside
the user app-db under `:rf/runtime`. That model keeps snapshots coherent, but it
creates a serious ownership footgun: an ordinary `reg-event-db` handler that
returns a fresh map can accidentally delete live machine, routing, elision, or
SSR runtime state.

The target design keeps the good property:

> A frame transition still commits one coherent app/runtime state change.

But removes the bad property:

> User app code no longer owns or replaces framework runtime state through
> ordinary `:db` returns.

## Key Decision

The important decision is **not** that re-frame2 must store everything inside
one physical map.

The important decision is:

> A frame has two durable partitions, and the cascade commits them coherently.

An implementation may store the partitions as two maps, two containers, or one
internal aggregate. The public and internal contract should be expressed through
partition keys in the event context:

```clojure
:db             ;; user app state
:rf.db/runtime  ;; managed runtime state
```

When tools, SSR, epoch restore, or tests need a full durable snapshot, they can
project those two partitions into a **frame-state** value:

```clojure
{:rf.db/app     <app-db>
 :rf.db/runtime <runtime-db>}
```

`frame-state` is therefore the coherent snapshot/projection of a frame's durable
state. It is not necessarily the physical storage shape used by the runtime.

## Problem

Today, re-frame2 stores framework-owned per-frame runtime state inside app-db
under `:rf/runtime`.

That means this ordinary app handler:

```clojure
(rf/reg-event-db
  :something
  (fn [_db _event]
    {:new 1}))
```

does not only replace user app state. It replaces the entire app-db map,
including `:rf/runtime`.

If the old app-db contained:

```clojure
{:todo/items []
 :rf/runtime
 {:machines {:snapshots {:door/main {:state :open :data {}}}}
  :routing  {:current {:id :route/home}}}}
```

the handler commits:

```clojure
{:new 1}
```

and the runtime state is gone.

This is worse than ordinary data loss. Machine snapshots are live runtime state.
Timers, spawn registries, route state, elision declarations, SSR hydration
metadata, tooling, and epoch records all rely on the durable runtime value being
coherent. Dropping it can leave the system with a torn invariant: external
handles and queued work still exist, but the durable state they reference has
vanished.

The current implementation emits `:rf.warning/runtime-state-dropped` when a
durable `:db` commit drops a live runtime subsystem. That diagnostic is useful,
but it is not the right final shape. A warning still asks ordinary application
code to know about and preserve framework internals.

## Design Goals

1. Ordinary app handlers must not be able to accidentally delete framework
   runtime state.
2. `reg-event-db` and ordinary `:db` effects should remain ergonomic: app code
   receives and returns the app's data.
3. Runtime state must remain per-frame, inspectable, subscribable through
   framework APIs, and revertible with epochs/time-travel.
4. SSR hydration and frame restore must install one coherent app/runtime
   snapshot.
5. The event pipeline must be able to thread runtime state through interceptors,
   handlers, flows, and framework effects.
6. User code should not write runtime state directly except through explicit
   extension APIs.
7. Tools and AI agents must be able to inspect app state, runtime state, or the
   whole frame-state intentionally.
8. Namespacing should make ownership visible: inherited re-frame keys can remain
   unqualified, new framework-owned keys must be qualified.
9. The terminology should be simple enough to teach: app-db is user land;
   runtime-db is framework-owned; frame-state is the coherent snapshot of both.

## Current Contract To Supersede

The current specs say:

- a frame owns an `:app-db` container;
- `:rf/runtime` is the single reserved app-db root;
- machines store snapshots under `[:rf/runtime :machines :snapshots]`;
- routing stores the route slice under `[:rf/runtime :routing :current]`;
- elision and SSR also store durable metadata under `:rf/runtime`;
- `app-db-value` returns the whole map, including `:rf/runtime`;
- epoch records use `:db-before` and `:db-after` to snapshot that whole map.

This is coherent, but it conflates snapshot coherence with ownership.

The key design correction is:

> One coherent transition does not require one user-owned map.

The frame can own app-db and runtime-db as separate durable partitions while
still committing, restoring, hydrating, and inspecting them as one coherent
frame state.

## Proposed Solution

### Terminology

Use these terms consistently:

| Term | Meaning |
|---|---|
| frame | The runtime boundary: id, router, queue, reactive container, sub-cache, lifecycle, config, trace ring, epoch history. |
| app-db | The user-owned application data partition. It is exposed as the ordinary `:db` coeffect/effect. |
| runtime-db | The framework-owned durable runtime partition. It is exposed internally as the reserved `:rf.db/runtime` coeffect/effect. |
| frame-state | A coherent durable snapshot/projection containing app-db and runtime-db. |
| runtime handles | Non-serializable host handles outside frame-state: timers, AbortControllers, listeners, promises, substrate objects. |

Public docs should stop saying `:rf/runtime` is an app-db key. Instead:

```text
app-db is your data.
runtime-db is re-frame2's durable bookkeeping.
frame-state is the coherent snapshot containing both.
```

### Partition Keys

Use these keys for the event pipeline:

| Key | Location | Owner | Meaning |
|---|---|---|---|
| `:db` | coeffects/effects | app | User app-db partition. Kept unqualified for re-frame compatibility and ergonomics. |
| `:event` | coeffects | framework/app | The event vector. Kept unqualified because it is inherited re-frame vocabulary. |
| `:rf.db/runtime` | coeffects/effects | framework | Managed durable runtime partition. |
| `:rf.frame/id` | coeffects | framework | Current frame id. |

The full-frame snapshot projection uses:

| Key | Meaning |
|---|---|
| `:rf.db/app` | App-db inside a frame-state value. |
| `:rf.db/runtime` | Runtime-db inside a frame-state value. |

`:rf.db/app` is not the ordinary app handler key. Ordinary handlers continue to
use `:db`. `:rf.db/app` exists so full-frame snapshots can name both partitions
without ambiguity.

### Namespacing Rule

The namespacing rule should be explicit:

> Unqualified keys are allowed only where they are inherited from the public
> re-frame contract. New framework-owned facts, coeffects, and effects must be
> qualified.

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

Avoid ambiguous names such as `:runtime`, `:frame`, or `:rf/frame` where the
value might be an id, object, state map, or context. Prefer attribute names such
as `:rf.frame/id`.

### Event Context Shape

The standard interceptor context should thread both partitions:

```clojure
{:coeffects
 {:db             {:todo/items []}
  :event          [:todo/add "Write EP"]
  :rf.db/runtime  {:rf.runtime/machines {}
                   :rf.runtime/routing  {}}
  :rf.frame/id    :rf/default}

 :effects
 {}}
```

An ordinary app handler can destructure only what it needs:

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

The runtime partition is present in the event context because it is part of the
causal input and output of a frame transition. It is not hidden from the
interceptor system. The contract is that ordinary app code treats it as
reserved.

### User Handler Semantics

`reg-event-db` handlers receive and return only app-db:

```clojure
(rf/reg-event-db
  :something
  (fn [db _event]
    ;; db is the user app-db partition
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
mental model unchanged: `db` means the app's data.

### Runtime Write Semantics

Framework code writes runtime-db through `:rf.db/runtime` effects, privileged
runtime APIs, or internal interceptors. It does not write runtime state through
ordinary app `:db` effects.

Examples:

- machine handlers read and write snapshots under
  `[:rf.runtime/machines :snapshots <id>]` inside `:rf.db/runtime`;
- route events write the route slice under
  `[:rf.runtime/routing :current]` inside `:rf.db/runtime`;
- SSR hydration writes metadata under
  `[:rf.runtime/ssr :hydration]` inside `:rf.db/runtime`;
- schema-derived elision writes declarations under
  `[:rf.runtime/elision]` inside `:rf.db/runtime`;
- future resources write under
  `[:rf.runtime/resources]` inside `:rf.db/runtime`.

These writes still participate in one atomic event commit. A cascade can produce
both app-db changes and runtime-db changes, and the frame installs the combined
result as one new coherent transition.

### Convention And Guardrails

Because `:rf.db/runtime` is part of `:coeffects`, app code can technically see
it:

```clojure
(rf/reg-event-fx
  :debug
  (fn [cofx _]
    (keys (:rf.db/runtime cofx))))
```

That visibility is acceptable. Interceptors, flows, extension APIs, tests, and
tools need a uniform context model.

The rule is social and architectural:

> Application code does not mutate `:rf.db/runtime` directly.

In pre-alpha, re-frame2 can strengthen that convention with diagnostics:

- warn or fail when a non-framework handler returns `:rf.db/runtime`;
- warn or fail when ordinary app code registers effects under
  `:rf.db/runtime`;
- warn when app schemas try to describe runtime-db paths;
- warn when examples or skills teach raw runtime path access;
- offer explicit extension APIs for code that is intentionally participating in
  runtime behavior.

This is similar to kernel space and user land. The data is in the same running
system, but the ownership boundary is real.

### Full-Frame Operations

Some operations must install or inspect the whole app/runtime snapshot:

- epoch restore;
- time-travel;
- SSR hydration;
- reset-frame;
- test fixture install;
- tool-driven replay;
- frame destroy epoch records.

Those operations use explicit full-frame APIs, not ordinary app `:db` effects.

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

Names are proposed, not final. The important split is that app-facing APIs
default to app-db, while tools and privileged runtime code can request
runtime-db or frame-state explicitly.

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

App code should not reach into runtime-db directly to ask for machine or route
state. It should use public framework subscriptions.

Flows should follow the same partition rule as events:

- ordinary flow inputs over app data read app-db;
- framework flows may read runtime-db through explicit qualified inputs;
- any flow-produced `:db` write updates only app-db;
- any flow-produced runtime write uses `:rf.db/runtime` and remains reserved.

### App Schemas

App schemas validate app-db, not the whole frame-state.

The old `:rf/runtime` schema should become a runtime-db schema:

```clojure
:rf/frame-state
[:map
 [:rf.db/app :any]
 [:rf.db/runtime :rf/runtime-db]]
```

Applications can still register schemas for their app paths. They do not
register app schemas under `:rf.db/runtime`.

### Mental Model: User Land And Kernel Space

The explanatory analogy is:

```clojure
{:db             <user-land app-db>
 :rf.db/runtime  <kernel-space runtime-db>}
```

App handlers, ordinary `:db` effects, app subscriptions, and app schemas operate
in user land.

Machines, routing, SSR, elision, traces, future resources, and runtime
bookkeeping live in framework-owned runtime-db.

The frame is the whole running system. Epochs, SSR hydration, reset, and
time-travel operate on the whole frame-state because app-db and runtime-db are
causally linked. But normal app code crosses into runtime-db only through
syscall-like public APIs, not raw map writes.

This analogy should stay explanatory. The formal public terms should be
`frame-state`, `app-db`, and `runtime-db`.

## Examples

### Ordinary Fresh App-db Return

Current behavior:

```clojure
(rf/reg-event-db
  :session/reset
  (fn [_db _]
    {:session/status :anonymous}))
```

If machines or routes were live, this could drop `:rf/runtime`.

Proposed behavior:

```clojure
;; before frame-state projection
{:rf.db/app
 {:session/status :authenticated
  :user/id 42}

 :rf.db/runtime
 {:rf.runtime/machines {...}
  :rf.runtime/routing  {...}}}

;; handler returns ordinary app-db
{:session/status :anonymous}

;; after frame-state projection
{:rf.db/app
 {:session/status :anonymous}

 :rf.db/runtime
 {:rf.runtime/machines {...}
  :rf.runtime/routing  {...}}}
```

No special preservation code is needed in the app handler.

### Event Updating App And Runtime State

A route transition may update app data and runtime route state in the same
cascade:

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

The exact internal effect shape is illustrative. The requirement is that the
commit installs one coherent app/runtime transition, not two independently
visible writes.

### Machine Snapshot Read

App code should use:

```clojure
@(rf/sub-machine :door/main)
```

or:

```clojure
@(rf/subscribe [:rf/machine :door/main])
```

The machine runtime reads:

```clojure
[:rf.runtime/machines :snapshots :door/main]
```

inside the `:rf.db/runtime` partition.

The application no longer reaches into app-db to find:

```clojure
[:rf/runtime :machines :snapshots :door/main]
```

### Full-Frame Restore

Epoch restore installs both partitions:

```clojure
{:rf.db/app
 <old-app-db>

 :rf.db/runtime
 <old-runtime-db>}
```

It does not go through ordinary `:db` effect semantics, because ordinary `:db`
effects replace only app-db.

## Options Considered

### A. Documentation Only

Keep `:rf/runtime` inside app-db and document that app handlers must preserve it
when returning a fresh map.

Pros:

- no implementation churn;
- preserves the current physical state shape.

Cons:

- leaves the footgun active;
- requires app authors to preserve framework internals;
- a warning is not enough for a destructive production path;
- fails the pre-alpha correctness/elegance bar.

Verdict: not acceptable as the final design.

### B. Preserve `:rf/runtime` At The Commit Boundary

Keep the physical shape, but ordinary app `:db` commits automatically carry
forward the previous `:rf/runtime`.

Pros:

- small change;
- fixes the common accidental drop;
- keeps most existing tooling paths stable.

Cons:

- keeps runtime state inside the user-owned app-db value;
- makes `:db` no longer literally mean "the app map";
- needs careful privileged bypasses for restore/hydration;
- still exposes runtime internals as if they are app state.

Verdict: useful as a temporary containment strategy if needed, but not the
destination.

### C. Reject Writes That Drop Or Modify `:rf/runtime`

Keep the current shape but turn the warning into an error when ordinary app
handlers drop or modify runtime state.

Pros:

- prevents silent corruption;
- keeps the existing one-map shape.

Cons:

- common fresh-map handlers now fail;
- users must still know about `:rf/runtime`;
- safe reset code becomes awkward;
- this protects the conflation rather than removing it.

Verdict: better than a warning, but still poor ergonomics.

### D. Two Frame-Owned Partitions Threaded Through Context

The proposed solution:

```clojure
{:coeffects {:db            <app-db>
             :rf.db/runtime <runtime-db>}
 :effects   {:db            <next-app-db>
             :rf.db/runtime <next-runtime-db>}}
```

and, when a full snapshot is needed:

```clojure
{:rf.db/app     <app-db>
 :rf.db/runtime <runtime-db>}
```

Pros:

- corruption is structurally impossible for ordinary `reg-event-db` returns;
- app-db means app data again;
- runtime-db is available to interceptors, flows, framework machinery, and
  extension APIs;
- frame-state remains one coherent snapshot/projection;
- tools can inspect app state, runtime state, or both intentionally;
- runtime reads move through framework APIs instead of raw app-db paths.

Cons:

- broad implementation and spec change;
- many current paths mention `[:rf/runtime ...]`;
- epoch, SSR, Xray, schemas, subs, machines, routes, and tests must be updated;
- requires careful terminology migration.

Verdict: recommended.

### E. Frame-State Wrapper As The Physical Store

Physically store:

```clojure
{:rf.db/app     <app-db>
 :rf.db/runtime <runtime-db>}
```

inside the frame's reactive container.

Pros:

- simple representation;
- snapshots and app/runtime coherence are obvious;
- tooling can inspect one value.

Cons:

- risks over-teaching "one wrapper map" as the design instead of "two
  partitions";
- ordinary app paths need projection on every handler/subscription boundary;
- not necessary if the runtime can commit two containers coherently.

Verdict: acceptable implementation strategy, but not the core contract.

### F. Fully Separate Runtime Store Outside Frame State

Move runtime state outside the frame's durable state and do not include it in
frame-state snapshots.

Pros:

- clean ownership boundary.

Cons:

- loses the simple "frame-state is one coherent snapshot" property;
- epoch restore must coordinate two stores without a single snapshot value;
- SSR hydration must coordinate two stores without a single snapshot value;
- easier to create torn app/runtime restore behavior.

Verdict: worse than two durable frame-owned partitions.

### G. Generic N-Partition Frame Db

Generalize the frame into arbitrary named durable partitions beyond app/runtime.

Pros:

- future-proof;
- explicit.

Cons:

- more ceremony than the current problem needs;
- risks making ordinary app code think about partition names;
- less teachable than app/runtime.

Verdict: do not start here. The two-partition design is enough.

## Recommendation

Adopt Option D: a frame owns two durable partitions, `app-db` and `runtime-db`,
and the event context threads both as `:db` and `:rf.db/runtime`.

Use `frame-state` as the name for a coherent snapshot/projection:

```clojure
{:rf.db/app     <app-db>
 :rf.db/runtime <runtime-db>}
```

Do not make the EP depend on storing both partitions inside one physical map.
That can be an implementation choice. The contract is the partition boundary,
the coeffect/effect keys, and the atomic frame commit.

The current `:rf.warning/runtime-state-dropped` diagnostic remains useful until
the partition lands. After the partition lands, the warning should either
disappear or become a legacy-path diagnostic for code still trying to write
`[:rf/runtime ...]`.

## Implementation Plan

### 1. Spec The New Contract

Update the normative docs:

- Frames: a frame owns app-db and runtime-db partitions.
- Conventions: remove `:rf/runtime` as a reserved app-db key; introduce
  `:db`, `:rf.db/runtime`, `:rf.db/app`, `:rf.frame/id`, and
  `:rf.runtime/*` vocabulary.
- Runtime Architecture: show app-db and runtime-db as frame-owned partitions
  committed by one cascade.
- Reactive Substrate: clarify whether adapter containers physically hold one
  frame-state value or two coherent partition containers.
- State Machines: snapshots move to
  `[:rf.db/runtime :rf.runtime/machines :snapshots]` in frame-state projection,
  or `[:rf.runtime/machines :snapshots]` inside runtime-db.
- Routing: route slice moves to
  `[:rf.db/runtime :rf.runtime/routing :current]` in frame-state projection.
- SSR: hydration installs a coherent frame-state.
- Instrumentation/Epoch: records distinguish app-db and frame-state.
- Schemas: app schemas validate app-db; runtime schemas validate runtime-db.

### 2. Introduce Partition Helpers

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

### 3. Change Event Context And Commit Semantics

Change the ordinary dispatch pipeline:

- inject `:db` coeffect as app-db;
- inject `:rf.db/runtime` coeffect as runtime-db;
- inject `:rf.frame/id` instead of ambiguous frame keys;
- interpret ordinary `:db` effect as replacement of app-db;
- interpret reserved `:rf.db/runtime` effects as replacement/update of
  runtime-db only for framework-owned code paths;
- reject or warn if user code returns a frame-state-shaped value under `:db`;
- preserve the existing pre-install atomicity rule: handler/interceptor/flow
  throws still leave both partitions unchanged.

### 4. Move Runtime Subsystems

Move each durable runtime subsystem from the old app-db paths:

| Old path | New path inside runtime-db |
|---|---|
| `[:rf/runtime :machines]` | `[:rf.runtime/machines]` |
| `[:rf/runtime :routing]` | `[:rf.runtime/routing]` |
| `[:rf/runtime :elision]` | `[:rf.runtime/elision]` |
| `[:rf/runtime :ssr]` | `[:rf.runtime/ssr]` |
| future `[:rf/runtime :resources]` | `[:rf.runtime/resources]` |

In a full frame-state projection, those same paths appear under
`:rf.db/runtime`.

Machine snapshot-internal keys such as `:rf/history`, `:rf/after-epoch`, and
`:rf/machine-type` can remain inside machine snapshots. The partition change is
about where snapshots live, not their internal open-map shape.

### 5. Update Subscriptions, Flows, And Cofx

Audit:

- layer-1 sub execution;
- `inject-cofx :db`;
- any new `inject-cofx :rf.db/runtime` path;
- path interceptors;
- app-db schema validation;
- flow input resolution and flow write effects;
- `sub-machine`;
- route subs;
- elision lookup during trace and wire projection;
- test helpers that assert app-db shape.

Ordinary app subs should continue to see app-db. Framework subs and framework
flows should read runtime-db through internal helpers or qualified inputs.

### 6. Update Epoch, SSR, Reset, And Tools

Epoch records should make the snapshot unit clear.

Possible shape:

```clojure
{:frame-state-before <frame-state>
 :frame-state-after  <frame-state>
 :app-db-before      <app-db>
 :app-db-after       <app-db>}
```

Alternatively keep `:db-before` / `:db-after` as app-db projections and add
`:frame-state-before` / `:frame-state-after` for full restore. The EP does not
settle the exact epoch record shape, but implementation must avoid ambiguity.

SSR hydration should serialize and install a redacted/allowed frame-state
projection, not a raw runtime dump.

Xray should show app-db and runtime-db as separate panels inside one frame.

### 7. Add Guardrails

Add diagnostics for:

- user app-db containing a legacy `:rf/runtime` root;
- user code registering schemas under runtime-db paths;
- ordinary app `:db` effects returning a frame-state wrapper;
- non-framework handlers returning `:rf.db/runtime` effects;
- raw reads of `[:rf/runtime ...]` in examples, docs, tests, or skills;
- full-frame install attempted through ordinary dispatch.

The guardrails should teach the new model:

```text
app-db is user data. runtime-db lives in the frame runtime partition.
Use sub-machine / route subs / tool APIs instead of raw runtime paths.
```

### 8. Update Docs, Examples, Skills, Migration

Rewrite examples that currently seed machine snapshots under `:rf/runtime`.
Update migration rules so direct `[:rf/runtime ...]` access becomes a migration
error or rewrite target.

The human guide should teach:

```text
The frame owns user land and runtime state. Your handlers see user land through
:db. Runtime state is threaded through the context as :rf.db/runtime for
framework machinery and explicit extension points. Time travel restores the
whole frame.
```

The AI/spec track should pin exact paths, namespacing, and commit semantics.

## Conformance Tests

Add tests for:

- `reg-event-db` receives only app-db;
- ordinary `:db` effect replaces only app-db;
- `:rf.db/runtime` is present in coeffects;
- `:rf.frame/id` is present in coeffects;
- runtime-db survives fresh app-db returns;
- non-framework app handlers cannot write `:rf.db/runtime` without the planned
  diagnostic;
- full-frame restore restores both app-db and runtime-db;
- machine snapshots move with epoch restore;
- route state moves with epoch restore;
- SSR hydration installs both partitions without double-initializing runtime;
- `app-db-value` returns only user app-db;
- tool/full-frame API returns the frame-state projection;
- app schemas validate only app-db;
- runtime schemas validate runtime-db;
- legacy `:rf/runtime` writes emit the planned diagnostic;
- Xray can inspect app-db and runtime-db separately;
- frame destroy still records coherent before/after state.

## Open Decisions

1. Exact public names for runtime-db and frame-state accessors.
2. Exact epoch record shape: replace `:db-before` / `:db-after`, or add
   frame-state siblings.
3. Whether adapter containers physically hold one frame-state value or two
   partition containers.
4. Whether ordinary user interceptors may intentionally emit
   `:rf.db/runtime`, or whether that requires a registered extension marker.
5. Whether framework runtime writes should use whole-value `:rf.db/runtime`
   effects, operation effects, or both.
6. How strict the legacy `:rf/runtime` diagnostic should be during pre-alpha:
   warning, error, or migration-only lint.
7. Whether `reset-frame!` resets both partitions by default or only app-db with
   a separate full reset surface.
8. Whether app-db schemas should be renamed in docs to "app partition schemas"
   while keeping the public API name `reg-app-schema`.

## Bead Structure

1. Decision bead: adopt app-db/runtime-db as two durable frame partitions and
   settle key names: `:db`, `:rf.db/runtime`, `:rf.db/app`,
   `:rf.frame/id`, and `:rf.runtime/*`.
2. Spec bead: update Frames, Conventions, Runtime Architecture, Reactive
   Substrate, Machines, Routing, SSR, Instrumentation, Schemas, and API docs.
3. Helper bead: introduce app-db/runtime-db/frame-state helper functions
   without changing behavior.
4. Event context bead: inject `:rf.db/runtime` and `:rf.frame/id` into
   coeffects while preserving `:db` as app-db.
5. Event commit bead: scope ordinary `:db` effects to app-db and add privileged
   runtime/full-frame commit paths.
6. Runtime migration bead: move machines, routing, elision, SSR, and related
   schemas from `:rf/runtime` to runtime-db.
7. Epoch/SSR bead: update snapshot, restore, hydration, reset, and destroy
   semantics to use frame-state projections.
8. Subscriptions/tooling bead: update framework subs, Xray, pair tools, and
   egress surfaces to distinguish app-db, runtime-db, and frame-state.
9. Guardrail/migration bead: reject or warn on legacy `:rf/runtime` access and
   unauthorized `:rf.db/runtime` effects.
10. Docs/examples/skills bead: rewrite human docs, AI specs, examples, and
    skills to teach the new model.

## Source Findings

This EP synthesizes:

- `ai/findings/2026-06-06.app-db-claude.md`
- `ai/findings/2026-06-06.app-db-codex.md`

Both findings agree on the destination: app code owns app-db, re-frame2 owns
runtime state, and the frame owns both as one coherent frame-state snapshot.
