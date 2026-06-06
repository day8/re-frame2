# EP: Frame-State App/Runtime Partition

Status: proposal

Date: 2026-06-06

Related:

- [Guide 02 - app-db](../../guide/02-app-db.md)
- [Guide 04 - Events and the cascade](../../guide/04-events-and-the-cascade.md)
- [Guide 18 - Frames](../../guide/18-frames.md)
- [Guide 21 - Runtime model](../../guide/21-dynamic-model.md)
- [Spec 002 - Frames](../../spec/002-Frames.md)
- [Spec 005 - State Machines](../../spec/005-StateMachines.md)
- [Spec 006 - Reactive Substrate](../../spec/006-ReactiveSubstrate.md)
- [Spec 009 - Instrumentation](../../spec/009-Instrumentation.md)
- [Spec 011 - SSR](../../spec/011-SSR.md)
- [Spec 012 - Routing](../../spec/012-Routing.md)
- [Runtime Architecture](../../spec/Runtime-Architecture.md)
- [Conventions](../../spec/Conventions.md)

## Summary

This enhancement proposes splitting each frame's durable state into two named
partitions:

```clojure
{:rf.db/app
 <user-app-db>

 :rf.db/runtime
 {:rf.runtime/machines <machine-runtime>
  :rf.runtime/routing  <routing-runtime>
  :rf.runtime/elision  <elision-runtime>
  :rf.runtime/ssr      <ssr-runtime>}}
```

The whole value is **frame-state**. The user-owned value at `:rf.db/app` is
**app-db**. The framework-owned value at `:rf.db/runtime` is **runtime state**.

This replaces the current model where framework-owned durable state sits inside
the user app-db under `:rf/runtime`. The current model preserves time-travel and
SSR coherence, but it creates a serious ownership footgun: an ordinary
`reg-event-db` handler that returns a fresh map can accidentally delete live
machine, routing, elision, or SSR runtime state.

The target design keeps the good property:

> A frame is still one coherent, revertible durable value.

But removes the bad property:

> User app code no longer owns or replaces framework runtime state.

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

This is worse than data loss. Machine snapshots are live runtime state. Timers,
spawn registries, route state, elision declarations, SSR hydration metadata,
tooling, and epoch records all rely on the durable runtime value being coherent.
Dropping it can leave the system with a torn invariant: external handles and
queued work still exist, but the durable state they reference has vanished.

The current implementation emits `:rf.warning/runtime-state-dropped` when a
durable `:db` commit drops a live runtime subsystem. That is a useful diagnostic,
but it is not the right final shape. A warning still asks ordinary application
code to know about and preserve framework internals.

## Design Goals

1. Ordinary app handlers must not be able to accidentally delete framework
   runtime state.
2. `reg-event-db` and ordinary `:db` effects should remain ergonomic: app code
   receives and returns the app's data.
3. Runtime state must remain per-frame, inspectable, subscribable through
   framework APIs, and revertible with epochs/time-travel.
4. SSR hydration and frame restore must install one coherent frame snapshot.
5. User code must not write under runtime-owned paths.
6. Tools and AI agents must be able to inspect app state, runtime state, or the
   whole frame-state intentionally.
7. The terminology should be simple enough to teach: app-db is user land;
   runtime state is framework-owned; frame-state owns both.

## Current Contract To Supersede

The current specs say:

- a frame owns an `:app-db` container;
- `:rf/runtime` is the single reserved app-db root;
- machines store snapshots under `[:rf/runtime :machines :snapshots]`;
- routing stores the route slice under `[:rf/runtime :routing :current]`;
- elision and SSR also store durable metadata under `:rf/runtime`;
- `app-db-value` returns the whole map, including `:rf/runtime`;
- epoch records use `:db-before` and `:db-after` to snapshot that whole map.

This is coherent, but it conflates physical snapshot co-location with ownership.
The key design correction is:

> One snapshot unit does not require one flat ownership map.

Frame-state can be one value while app-db and runtime state are separate
partitions inside it.

## Proposed Solution

### Terminology

Use these terms consistently:

| Term | Meaning |
|---|---|
| frame | The runtime boundary: id, router, queue, reactive container, sub-cache, lifecycle, config, trace ring, epoch history. |
| frame-state | The durable per-frame value that can be snapshotted, restored, hydrated, and time-traveled. |
| app-db | The user-owned application data partition at `:rf.db/app`. |
| runtime state | Framework-owned durable state at `:rf.db/runtime`. |
| runtime handles | Non-serializable host handles outside frame-state: timers, AbortControllers, listeners, promises, substrate objects. |

Public docs should stop saying `:rf/runtime` is an app-db key. Instead:

```text
app-db is your data.
runtime state is re-frame2's durable bookkeeping.
frame-state is the whole snapshot containing both.
```

### Target Shape

The durable value inside a frame becomes:

```clojure
{:rf.db/app
 {:todo/items []
  :todo/filter :all}

 :rf.db/runtime
 {:rf.runtime/machines
  {:snapshots
   {:door/main {:state :open
                :data  {:opened-count 2}}}
   :system-ids {}
   :spawned    {}
   :spawn-counter {}}

  :rf.runtime/routing
  {:current {:id :route/home
             :params {}
             :query {}
             :nav-token [:rf.route.nav-token/1]}}

  :rf.runtime/elision
  {:declarations {}
   :sensitive-declarations {}}

  :rf.runtime/ssr
  {:hydration {:server-hash "abcd1234"
               :version 1}}}}
```

Runtime children should use namespaced keys such as `:rf.runtime/machines`,
not generic keys such as `:machines`. The wrapper will be inspected by Xray,
tests, migrations, and AI agents, so ownership should be visible in the data.

### User Handler Semantics

`reg-event-db` handlers receive and return only app-db:

```clojure
(rf/reg-event-db
  :something
  (fn [db _event]
    ;; db is (:rf.db/app frame-state)
    {:new 1}))
```

This commits:

```clojure
{:rf.db/app
 {:new 1}

 :rf.db/runtime
 <unchanged-runtime-state>}
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

Framework code writes runtime state through privileged runtime APIs or internal
effects, not through ordinary app `:db` effects.

Examples:

- machine handlers read and write snapshots under
  `[:rf.db/runtime :rf.runtime/machines :snapshots <id>]`;
- route events write the route slice under
  `[:rf.db/runtime :rf.runtime/routing :current]`;
- SSR hydration writes metadata under
  `[:rf.db/runtime :rf.runtime/ssr :hydration]`;
- schema-derived elision writes declarations under
  `[:rf.db/runtime :rf.runtime/elision]`;
- future resources would write under
  `[:rf.db/runtime :rf.runtime/resources]`.

These writes still participate in one atomic event commit. The event cascade
can produce both app-db changes and runtime-state changes, and the frame commits
one new frame-state value.

### Full-Frame Operations

Some operations must install the whole frame-state:

- epoch restore;
- time-travel;
- SSR hydration;
- reset-frame;
- test fixture install;
- tool-driven replay;
- frame destroy epoch records.

Those operations must use explicit full-frame APIs, not ordinary app `:db`
effects.

Proposed internal/public-tooling surfaces:

```clojure
(rf/app-db-value frame-id)
;; => (:rf.db/app frame-state)

(rf/frame-state-value frame-id)
;; => {:rf.db/app ... :rf.db/runtime ...}

(rf/runtime-state frame-id)
;; => (:rf.db/runtime frame-state)
```

Names are proposed, not final. The important split is that app-facing APIs
default to app-db, while tools and privileged runtime code can request
frame-state or runtime state explicitly.

### Subscriptions

Ordinary layer-1 app subscriptions receive app-db:

```clojure
(rf/reg-sub
  :todo/items
  (fn [db _]
    (:todo/items db)))
```

Framework subscriptions read runtime state through privileged access:

```clojure
[:rf/machine :door/main]
[:rf.route/id]
[:rf.route/params]
```

The application should not write:

```clojure
(rf/reg-sub
  :my/raw-machine
  (fn [db _]
    (get-in db [:rf/runtime :machines :snapshots :door/main])))
```

That path should no longer exist in app-db. Apps use framework subs such as
`sub-machine`, route subs, or future resource subs.

### App Schemas

App schemas should validate `:rf.db/app`, not the whole frame-state.

The old `:rf/runtime` schema should become a runtime-state schema:

```clojure
:rf/frame-state
[:map
 [:rf.db/app :any]
 [:rf.db/runtime :rf/runtime-state]]
```

Applications can still register schemas for their app paths. They do not
register app schemas under `:rf.db/runtime`.

### Mental Model: User Land And Kernel Space

The explanatory analogy is:

```clojure
{:rf.db/app     <user-land>
 :rf.db/runtime <kernel-space>}
```

App handlers, ordinary `:db` effects, app subscriptions, and app schemas
operate in user land.

Machines, routing, SSR, elision, traces, future resources, and runtime
bookkeeping live in framework-owned runtime state.

The frame is the whole running system. Epochs, SSR hydration, reset, and
time-travel operate on the whole frame-state because user land and runtime state
are causally linked. But normal app code crosses into runtime state only
through syscall-like public APIs, not raw map writes.

This analogy should stay explanatory. The formal public terms should be
`frame-state`, `app-db`, and `runtime state`.

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
;; before
{:rf.db/app
 {:session/status :authenticated
  :user/id 42}

 :rf.db/runtime
 {:rf.runtime/machines {...}
  :rf.runtime/routing  {...}}}

;; handler returns
{:session/status :anonymous}

;; after
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

 :rf.runtime/effects
 [[:rf.runtime/assoc-in
   [:rf.runtime/routing :current]
   {:id :route/account
    :params {:id 42}
    :query {}
    :nav-token nav-token}]]}
```

The exact internal effect shape is illustrative. The requirement is that the
commit installs one new frame-state, not two independently visible writes.

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
[:rf.db/runtime :rf.runtime/machines :snapshots :door/main]
```

The application no longer reaches into app-db to find
`[:rf/runtime :machines :snapshots :door/main]`.

### Full-Frame Restore

Epoch restore installs:

```clojure
{:rf.db/app
 <old-app-db>

 :rf.db/runtime
 <old-runtime-state>}
```

It does not go through ordinary `:db` effect semantics, because ordinary `:db`
effects replace only app-db.

## Options Considered

### A. Documentation Only

Keep `:rf/runtime` inside app-db and document that app handlers must preserve
it when returning a fresh map.

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
- makes `:db` no longer literally mean "the whole map";
- needs careful privileged bypasses for restore/hydration;
- still exposes runtime internals to app code.

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

### D. Frame-State Wrapper With App/Runtime Partitions

The proposed solution:

```clojure
{:rf.db/app     <user-app-db>
 :rf.db/runtime <framework-runtime-state>}
```

Pros:

- corruption is structurally impossible for ordinary app handlers;
- app-db means app data again;
- frame-state remains one coherent snapshot;
- tools can inspect app state, runtime state, or both intentionally;
- pushes runtime reads through framework APIs instead of raw map paths.

Cons:

- broad implementation and spec change;
- many current paths mention `[:rf/runtime ...]`;
- epoch, SSR, Xray, schemas, subs, machines, routes, and tests must be updated;
- requires careful terminology migration.

Verdict: recommended.

### E. Fully Separate Runtime Store

Move runtime state outside the frame's durable value.

Pros:

- clean ownership boundary.

Cons:

- loses the simple "frame-state is one value" property;
- epoch restore must coordinate two stores;
- SSR hydration must coordinate two stores;
- easier to create torn app/runtime snapshots.

Verdict: worse than the frame-state wrapper.

### F. Generic N-Partition Frame Db

Generalize the frame into arbitrary named partitions beyond app/runtime.

Pros:

- future-proof;
- explicit.

Cons:

- more ceremony than the current problem needs;
- risks making ordinary app code think about partition names;
- less teachable than app/runtime.

Verdict: do not start here. The two-partition design is enough.

## Recommendation

Adopt Option D: the frame-state wrapper with `:rf.db/app` and
`:rf.db/runtime`.

This is the cleanest long-term model and fits the project posture: pre-alpha,
correctness-first, no back-compat obligation, and aiming for an elegant
foundation.

The current `:rf.warning/runtime-state-dropped` diagnostic remains useful until
the partition lands. After the partition lands, the warning should either
disappear or become a legacy-path diagnostic for code still trying to write
`[:rf/runtime ...]`.

## Implementation Plan

### 1. Spec The New Contract

Update the normative docs:

- Frames: a frame owns frame-state, not just app-db.
- Conventions: remove `:rf/runtime` as a reserved app-db key; introduce
  `:rf.db/app`, `:rf.db/runtime`, and `:rf.runtime/*`.
- Runtime Architecture: change diagrams from `app-db` as the durable whole to
  frame-state as the durable whole.
- Reactive Substrate: adapter containers hold frame-state or expose an adapter
  operation that replaces frame-state while app subs see app-db.
- State Machines: snapshots move to
  `[:rf.db/runtime :rf.runtime/machines :snapshots]`.
- Routing: route slice moves to
  `[:rf.db/runtime :rf.runtime/routing :current]`.
- SSR: hydration installs frame-state.
- Instrumentation/Epoch: records distinguish app-db and frame-state.
- Schemas: app schemas validate `:rf.db/app`; runtime schemas validate
  `:rf.db/runtime`.

### 2. Introduce Frame-State Helpers

Add internal helpers before moving all call sites:

```clojure
(frame/frame-state-value frame-id)
(frame/app-db-value frame-id)
(frame/runtime-state-value frame-id)
(frame/replace-frame-state! frame-id frame-state)
(frame/replace-app-db! frame-id app-db)
(frame/update-runtime-state! frame-id f)
```

Names are illustrative. The design point is to stop making every call site know
where the partitions live.

### 3. Change Event Commit Semantics

Change the ordinary dispatch pipeline:

- inject `:db` coeffect as `:rf.db/app`;
- interpret ordinary `:db` effect as replacement of `:rf.db/app`;
- reject or warn if user code returns a frame-state-shaped value under `:db`;
- provide privileged internal commit paths for runtime writes and full-frame
  install;
- preserve the existing pre-install atomicity rule: handler/interceptor/flow
  throws still leave frame-state unchanged.

### 4. Move Runtime Subsystems

Move each durable runtime subsystem from the old paths:

| Old path | New path |
|---|---|
| `[:rf/runtime :machines]` | `[:rf.db/runtime :rf.runtime/machines]` |
| `[:rf/runtime :routing]` | `[:rf.db/runtime :rf.runtime/routing]` |
| `[:rf/runtime :elision]` | `[:rf.db/runtime :rf.runtime/elision]` |
| `[:rf/runtime :ssr]` | `[:rf.db/runtime :rf.runtime/ssr]` |
| future `[:rf/runtime :resources]` | `[:rf.db/runtime :rf.runtime/resources]` |

Machine snapshot-internal keys such as `:rf/history`, `:rf/after-epoch`, and
`:rf/machine-type` can remain inside machine snapshots. The partition change is
about where snapshots live, not their internal open-map shape.

### 5. Update Subscriptions And Cofx

Ordinary app subs read app-db. Framework subs read runtime state through
internal helpers.

Audit:

- layer-1 sub execution;
- `inject-cofx :db`;
- path interceptors;
- app-db schema validation;
- `sub-machine`;
- route subs;
- elision lookup during trace and wire projection;
- test helpers that assert app-db shape.

### 6. Update Epoch, SSR, Reset, And Tools

Epoch records should make the snapshot unit clear.

Possible shape:

```clojure
{:frame-state-before <frame-state>
 :frame-state-after  <frame-state>
 :app-db-before      <app-db>       ;; optional projection for convenience
 :app-db-after       <app-db>}
```

Alternatively keep `:db-before` / `:db-after` as app-db projections and add
`:frame-state-before` / `:frame-state-after` for full restore. The EP does not
settle the exact epoch record shape, but implementation must avoid ambiguity.

SSR hydration should serialize and install a redacted/allowed frame-state
projection, not a raw runtime dump.

Xray should show app-db and runtime state as separate panels inside one frame.

### 7. Add Guardrails

Add diagnostics for:

- user app-db containing a legacy `:rf/runtime` root;
- user code registering schemas under runtime paths;
- ordinary app `:db` effects returning a frame-state wrapper;
- raw reads of `[:rf/runtime ...]` in examples, docs, tests, or skills;
- full-frame install attempted through ordinary dispatch.

The guardrails should teach the new model:

```text
app-db is user data. Runtime state lives in the frame runtime partition.
Use sub-machine / route subs / tool APIs instead of raw runtime paths.
```

### 8. Update Docs, Examples, Skills, Migration

Rewrite examples that currently seed machine snapshots under `:rf/runtime`.
Update migration rules so direct `[:rf/runtime ...]` access becomes a migration
error or rewrite target.

The human guide should teach:

```text
The frame owns both user land and runtime state. Your handlers see user land.
Time travel restores the whole frame.
```

The AI/spec track should pin exact paths and commit semantics.

## Conformance Tests

Add tests for:

- `reg-event-db` receives only app-db;
- ordinary `:db` effect replaces only app-db;
- runtime partition survives fresh app-db returns;
- user app-db cannot write `:rf.db/runtime`;
- full-frame restore restores both app and runtime partitions;
- machine snapshots move with epoch restore;
- route state moves with epoch restore;
- SSR hydration installs both partitions without double-initializing runtime;
- `app-db-value` returns only user app-db;
- tool/full-frame API returns the wrapper;
- app schemas validate only `:rf.db/app`;
- runtime schemas validate runtime state;
- legacy `:rf/runtime` writes emit the planned diagnostic;
- Xray can inspect app-db and runtime state separately;
- frame destroy still records coherent before/after state.

## Open Decisions

1. Exact public names for full-frame and runtime accessors.
2. Exact epoch record shape: replace `:db-before` / `:db-after`, or add
   frame-state siblings.
3. Whether adapter containers physically hold frame-state or hold two
   containers behind a frame-state facade.
4. Whether app subs receive only app-db by default with separate framework
   internal sub execution, or whether sub execution receives a richer context.
5. How strict the legacy `:rf/runtime` diagnostic should be during pre-alpha:
   warning, error, or migration-only lint.
6. Whether `reset-frame!` resets both partitions by default or only app-db with
   a separate full reset surface.
7. Whether app-db schemas should be renamed in docs to "app partition schemas"
   while keeping the public API name `reg-app-schema`.

## Bead Structure

1. Decision bead: adopt `{:rf.db/app ... :rf.db/runtime ...}` as the target
   frame-state shape and settle terminology.
2. Spec bead: update Frames, Conventions, Runtime Architecture, Reactive
   Substrate, Machines, Routing, SSR, Instrumentation, Schemas, and API docs.
3. Helper bead: introduce frame-state/app-db/runtime helper functions without
   changing behavior.
4. Event commit bead: scope ordinary `:db` coeffects/effects to the app
   partition and add privileged full-frame install.
5. Runtime migration bead: move machines, routing, elision, SSR, and related
   schemas to `:rf.db/runtime`.
6. Epoch/SSR bead: update snapshot, restore, hydration, reset, and destroy
   semantics to use frame-state.
7. Subscriptions/tooling bead: update framework subs, Xray, pair tools, and
   egress surfaces to distinguish app-db, runtime state, and frame-state.
8. Guardrail/migration bead: reject or warn on legacy `:rf/runtime` access and
   update migration rules.
9. Docs/examples/skills bead: rewrite human docs, AI specs, examples, and
   skills to teach the new model.

## Source Findings

This EP synthesizes:

- `ai/findings/2026-06-06.app-db-claude.md`
- `ai/findings/2026-06-06.app-db-codex.md`

Both findings agree on the destination: app code owns app-db, re-frame2 owns
runtime state, and the frame owns both as one coherent frame-state snapshot.
