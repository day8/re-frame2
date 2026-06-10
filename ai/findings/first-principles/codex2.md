# First-Principles CLJS Library Architecture Review

Date: 2026-06-10

Scope: the re-frame2 library implementation, with emphasis on the CLJS
reference implementation under `implementation/core`,
`implementation/flows`, `implementation/http`, `implementation/machines`,
`implementation/routing`, `implementation/schemas`, `implementation/ssr`, and
the adapter packages. This review deliberately excludes tools, skills,
examples, and product-facing documentation except where `/spec` constrains the
implementation.

## Executive View

The core re-frame2 idea still looks right: application state is data, event
handlers describe transitions, effects are data at the boundary, subscriptions
are derived values, and frames make state identity explicit. The best piece of
the current implementation is the two-partition frame-state model: one physical
container holds `{:rf.db/app ... :rf.db/runtime ...}`, with app-db and
runtime-db as projections. That design gives the runtime an atomic commit
boundary without letting app code clobber framework state.

The largest architectural smell is that several things which should be values
or frame/root-owned capabilities are still process-wide singletons:

- the handler registrar
- the installed reactive adapter
- the late-bind function registry
- host-transient subsystem tables such as HTTP in-flight requests, routing
  counters, scroll positions, flow last-input caches, machine timers, and spawn
  order

Some of those singletons are deliberate and pragmatic. They simplify hot
reload, keep re-frame-v1 migration familiar, and avoid making early API surface
too abstract. But from first principles, large SPAs want isolated products,
feature modules, test realms, white-label shells, coexisting render roots, and
predictable teardown. In that setting, process-wide singletons are not just
implementation detail; they become architectural gravity.

The most important spec-level change would be to introduce an explicit
**Runtime Environment** or **Realm** concept: a value that owns the registrar,
adapter choice, feature capabilities, and host-transient subsystem registry.
Frames would belong to a realm. The current global realm could remain the
default compatibility surface, but the spec would no longer make global process
state the only shape.

The second most important change would be to describe subscriptions, flows,
resource reads, and selected machine state as variants of a single
**derivation/process algebra**. Users should not have to learn unrelated
mechanisms for "derive this value", "materialize this value", "fetch and cache
this value", and "keep this process state synchronized". Internally, those are
different storage classes and lifecycle policies over declared dependencies.

The third most important change would be a first-class **feature module
manifest**: data that declares a coherent product slice's events, subs, flows,
machines, routes, resources, schemas, views, and ownership. The existing
`reg-*` functions are a good compilation target, but scattered registration is
not the highest-productivity source form for a large SPA.

## First Principles

A high-productivity SPA architecture should optimize for these properties:

1. **State is a value.** The durable state of the application must be inspectable,
   replayable, serializable where appropriate, and refactorable.
2. **Transitions are pure until the boundary.** Event handlers should be close
   to `state -> plan`, where a plan contains a state delta, commands, and
   diagnostics. Interpreting commands is a separate boundary.
3. **Effects are data, not hidden calls.** This is already a re-frame strength.
   The improvement opportunity is to make effect capabilities more explicit.
4. **Derivations form a graph.** Subscriptions, flows, route matches, resource
   caches, and selected machine state are all graph nodes with inputs, output,
   invalidation, and lifecycle.
5. **Processes are state machines.** Long-running UI behavior, remote work,
   route egress, retry, cancellation, and actor lifecycles should be modelled
   as stateful processes rather than ad hoc callback storage.
6. **Capabilities are explicit dependencies.** A component or feature should
   depend on a runtime capability map, not on temporal namespace load order.
7. **Composition is algebraic.** Feature modules should compose like data:
   combine, validate collisions, attach ownership, and install into a realm.
8. **The theory should not leak into the user API.** Category-theory and
   algebraic lenses are useful design tools, not terminology most app teams
   should see while shipping UI.

In category-theory terms, the event system wants to be a small Kleisli-like
category: handlers are arrows from state plus event to a transition plan in a
context that can accumulate diagnostics and commands or return failure. Effects
are a free command algebra interpreted by the host. Interceptors, flows,
validation, and machine macrosteps are plan transformations. That framing is
useful because it separates pure composition from interpretation. The current
implementation largely does this operationally, but the spec does not yet
name the denotational shape strongly enough.

## What Is Strong

### One Physical Frame-State Container

`implementation/core/src/re_frame/frame.cljc` is clear that a frame has one
physical durable container and two projections:

- `:frame-state` holds both `:rf.db/app` and `:rf.db/runtime`
- `:app-db` is a read-only projection
- `:runtime-db` is a read-only projection
- `commit-frame-transition!` installs app/runtime partition changes in one
  atomic write

This is the right direction. It makes app-db and runtime-db coherent at event
boundaries, prevents `:db` from dropping runtime state, and lets trace/epoch
records talk about whole-frame state. It is a good Clojure design: ordinary
data, one state value, explicit projection.

### The Carried Frame Invariant

`spec/002-Frames.md` is right to remove silent `:rf/default` fallback. Frame
identity should be carried, scoped, held, or explicit. A large SPA cannot
afford an accidental event landing in a hidden default frame. The loud
`:rf.error/no-frame-context` posture is correct.

There is one spec drift bug: `spec/013-Flows.md` still says a bare
`(reg-flow flow)` can fall through to `:rf/default` (`spec/013-Flows.md:93`
and `spec/013-Flows.md:125`). That contradicts the frame contract in
`spec/002-Frames.md`, including the "no default floor" language around
`spec/002-Frames.md:889`, `spec/002-Frames.md:933`, and
`spec/002-Frames.md:1172`. The implementation and frame spec are right; the
flows spec text should be fixed.

### Effects As Data

The `:fx` vector and closed top-level effect map are still one of the most
important re-frame ideas. `events.cljc` policing of final effects and
`router.cljc`'s commit boundary are exactly the kind of machinery that lets
large systems stay debuggable.

The smell is not "effects are maps". Maps are a strength. The smell is that
some effect capabilities are only distinguished by keywords and registrar
metadata. As runtime subsystems grow, capability ownership should become more
first-class while staying data-oriented.

### Schema Encapsulation

The schemas implementation avoids exporting raw mutable cells and instead
publishes functions through the public/late-bound surface. That posture is
right. Public atoms in the API would be a smell because they let users bind to
storage rather than behavior. The larger concern is not raw atoms in schemas;
it is that many subsystems use hidden module-level cells and late-bound hooks
as the composition mechanism.

## Smells And Recommendations

### 1. The Global Registrar Is Too Large A Primitive

Current posture:

- `spec/002-Frames.md:306` says handlers are not in the frame; the registrar is
  global and frames isolate state, not behavior.
- `implementation/core/src/re_frame/registrar.cljc` documents the same posture.
- `registrar.cljc` stores registrations in a process-wide `kind->id->metadata`
  atom.

This is coherent and probably useful for re-frame-v1 migration. But for large
SPAs it is too much globalness to bake into the architecture. State isolation
without behavior isolation works for multiple instances of the same app. It
does not work as cleanly for:

- a product shell embedding multiple independently shipped app slices
- white-label tenants that need separate handler graphs in the same browser
- migration windows where old and new feature packs use the same ids
- tests that want hermetic handler registries without global cleanup
- micro-frontend-like deployment without iframe isolation
- experiments that need two versions of a feature mounted side by side

Recommended spec freedom:

Introduce a `Runtime Environment`, `Runtime`, or `Realm` as a pattern concept.
A realm owns a registrar. Frames belong to a realm. The existing global
registrar becomes the default realm's registrar.

Possible shape:

```clojure
(def runtime (rf/make-runtime {:adapter reagent-adapter}))

(rf/with-runtime runtime
  (rf/reg-event-db :cart/add ...))

(rf/reg-frame :shop/cart {:runtime runtime})
```

The public `reg-*` functions can keep their current one-arg/ambient forms, but
the spec should allow an explicit realm target. Tooling should eventually take
both realm and frame identity.

This change does not require making app authors pass a runtime everywhere. It
requires the spec to stop declaring "one process-wide registrar" as the only
valid model.

### 2. Single Adapter Per Process Over-Constrains CLJS Roots

Current posture:

- `spec/006-ReactiveSubstrate.md:1166` says only one adapter is ever installed.
- `spec/006-ReactiveSubstrate.md:1168` says later installs without disposal
  raise `:rf.error/adapter-already-installed`.
- `implementation/core/src/re_frame/substrate/adapter.cljc:57` stores the
  installed adapter in a process-wide atom.

Again, this is simple and coherent. It also fights the way large CLJS apps
evolve. Real teams may need to run Reagent for legacy views while introducing
UIx or Helix for new surfaces. A shell may host two render roots with different
substrate choices. Story and production surfaces may share a page. Tests may
want two adapters in the same process without total teardown.

The current implementation already has cross-adapter routing complexity:
adapter namespaces publish hooks, and `adapter.cljc` routes some hooks through
the installed adapter token so the last-loaded namespace does not win. That is
a symptom: the true dependency is not "the process has an adapter"; it is "this
root/frame/realm has an adapter capability".

Recommended spec freedom:

Relax "single adapter per process" to "single adapter per runtime/root" at the
pattern level. The CLJS reference can keep process-singleton install as a v1
implementation strategy, but the spec should permit:

- one adapter per realm
- one adapter per render root
- a shared React frame context for cross-substrate frame routing where
  intentional

The important invariant is not process uniqueness. The important invariant is
that a render subtree's reactive substrate is explicit, disposable, and used
consistently by the derived values created under it.

### 3. `late_bind` Has Become A Service Locator

Current posture:

- `implementation/core/src/re_frame/late_bind.cljc` provides a global hook
  table for cross-namespace and optional-artifact references.
- Flows, machines, schemas, routing, SSR, adapters, and core artefact wrappers
  publish many functions by hook key.

The problem is not that `late_bind` exists. In CLJS, optional artifacts,
Closure compilation, and cyclic namespace pressure are real. A hook directory
is a pragmatic bridge.

The smell is that `late_bind` is doing architectural composition. A subsystem's
dependencies are not visible as arguments or fields on a runtime value; they
are discovered by keyword after namespace load. That creates temporal
semantics:

- what has been required?
- which namespace loaded last?
- which hooks were chained rather than replaced?
- which optional artifact is absent but tolerated?

For small apps this is fine. For large SPA maintenance and AI-assisted change,
hidden dependencies are expensive. They make "what does this feature need?"
harder to answer mechanically.

Recommended spec freedom:

Define feature artifacts as capability maps. Core should compose a runtime
environment from those maps. `late_bind` can remain as the compatibility and
optional-load mechanism, but the conceptual model becomes:

```clojure
{:re-frame.capability/events   ...
 :re-frame.capability/subs     ...
 :re-frame.capability/flows    ...
 :re-frame.capability/machines ...
 :re-frame.capability/schemas  ...
 :re-frame.capability/adapter  ...}
```

The implementation can still publish hooks internally, but the spec should
prefer "runtime has capabilities" over "process has hooks".

### 4. Host-Transient State Is Correct But Under-Generalized

Current examples:

- `implementation/http/src/re_frame/http_registry.cljc` has `in-flight` and
  `actor-in-flight` atoms for request handles.
- `implementation/routing/src/re_frame/routing/nav_counters.cljc` has a
  `nav-counters-cache`.
- `implementation/routing/src/re_frame/routing/scroll.cljc` has a
  `scroll-positions-cache`.
- `implementation/machines/src/re_frame/machines/timer.cljc` has
  `after-timers`.
- `implementation/machines/src/re_frame/machines/spawn_order.cljc` has
  `spawn-order`.
- `implementation/flows/src/re_frame/flows/registry.cljc` has per-frame
  `frame-last-inputs`.

These choices are individually reasonable. Timer handles, AbortControllers,
scroll positions, and monotone navigation counters are not app-db. Some are not
even durable runtime-db. They are host resources or transient coordination
state.

The smell is repetition. Every subsystem must remember how to:

- key by frame
- reset for tests
- release on frame destroy
- classify for trace/security/SSR
- snapshot or deliberately not snapshot
- avoid stale continuations after restore

Recommended spec freedom:

Extend the runtime-subsystem contract to include **host-transient subsystems**,
not just durable runtime-db subsystems. A subsystem descriptor should declare:

```clojure
{:id               :rf.http/in-flight
 :storage-class    :host-transient
 :scope            :frame
 :durability       :none
 :teardown         teardown-fn
 :test-reset       reset-fn
 :snapshot         nil
 :classification   {:sensitive? false
                    :egress? true}}
```

This does not mean exposing third-party runtime subsystem registration yet.
It means the framework should have one internal table and one lifecycle
protocol for all its own side tables. That would reduce teardown bugs and make
the implementation easier to audit.

### 5. Subscriptions, Flows, Resources, And Machine Selectors Are One Family

Current posture:

- Subscriptions are reactive, ephemeral derived values.
- Runtime subscriptions (`reg-runtime-sub`) read runtime-db with the same
  shape as layer-1 app-db subscriptions.
- Flows are frame-scoped materialized derivations that write app-db after event
  processing.
- Machines hold durable process snapshots in runtime-db and expose selectors.
- HTTP/resources are managed effects with in-flight host state and replies.

From first principles, these are not separate ideas. They are points in a
design space:

- inputs: app-db, runtime-db, route, params, remote data, time
- output: ephemeral value, app-db path, runtime-db path, command, diagnostic
- lifecycle: per subscribe, per frame, per route, per actor, per resource key
- evaluation: lazy, eager after event, scheduled, remote invalidated
- storage: none, app-db, runtime-db, host-transient cache

The current API asks users to pick among mechanisms. In small apps, that is
fine. In large SPAs, the boundary between "sub", "flow", "resource query", and
"machine selector plus effect" becomes a design burden.

Recommended spec freedom:

Introduce a unified **Derivation** or **Fact** algebra. Subscriptions and flows
become storage/evaluation policies over the same declared dependency graph:

```clojure
{:id          :cart/total
 :inputs      [[:db [:cart/items]]
               [:db [:pricing/discounts]]]
 :derive      (fn [{:keys [items discounts]}] ...)
 :storage     :ephemeral       ;; or :app-db, :runtime-db, :host-cache
 :path        [:cart/total]    ;; required only when materialized
 :evaluation  :on-demand       ;; or :after-event, :on-route, :interval
 :lifecycle   :frame}
```

This need not replace `reg-sub` or `reg-flow` immediately. Those can compile
to this representation. But naming the common algebra would make the library
more explainable and would let tooling answer "where does this fact come from?"
across subscriptions, flows, remote resources, and machine state.

### 6. The Event Pipeline Wants A More Denotational Spec

`implementation/core/src/re_frame/router.cljc` does a lot of careful work:
build an envelope, assemble context, run interceptors, validate effects,
materialize flows, run post-commit validation, emit traces, handle rollback,
settle epochs, drain cascades, and integrate machines. Operationally, it is
sound and well-tested.

The smell is that the architecture is easier to understand by reading the
router than by reading a small denotational model. That is a maintainability
cost. A new subsystem has to ask "where do I hook into the router?" rather
than "which plan transformation do I implement?"

Recommended spec freedom:

Specify event processing as:

```text
event + frame-state + runtime capabilities
  -> initial transaction plan
  -> interceptor transforms
  -> subsystem transforms
  -> validation
  -> atomic frame-state commit
  -> command interpretation
  -> trace/epoch emission
```

In code terms, a handler produces a plan:

```clojure
{:state-delta {:rf.db/app     ...
               :rf.db/runtime ...}
 :commands    [[:dispatch ...]
               [:http ...]]
 :diagnostics [...]
 :authority   #{:runtime-db/write}}
```

Interceptors, flows, machine macrosteps, schema validation, and routing egress
then become transformations over this plan. The current effect map can remain
the public source form; this is about the implementation and spec model.

This would also clarify rollback semantics: validation failures reject a plan
or undo a committed plan at one named boundary, rather than being learned from
the router's control flow.

### 7. Runtime-DB Write Authority Is A Convention, Not A Capability

Current posture:

- `events.cljc` reserves `:rf.db/runtime` by convention for framework-authority
  handlers.
- Non-framework handlers returning runtime-db effects are warned about, but the
  write is still applied.
- `framework-authority?` is derived from reserved metadata.

For the current maturity level, this is probably acceptable. But if runtime-db
continues to accumulate machines, routing, SSR, flows, epochs, resources, and
other framework state, convention will become too soft.

Recommended spec freedom:

Keep runtime-db non-public for application authors, but make framework writes
use explicit internal capabilities or constructors:

```clojure
(runtime-write :rf.routing/state new-routing-state)
(runtime-delete :rf.machine/actor actor-id)
```

The output can still compile to `{:rf.db/runtime ...}` before commit. The
point is that the producer carries authority and ownership in data. This would
make it easier to audit which subsystem wrote which runtime paths and to
prevent accidental cross-subsystem clobbering.

### 8. Large SPA Productivity Needs Feature Module Manifests

The current system is fundamentally registration-oriented. A feature's shape is
distributed across `reg-event-*`, `reg-sub`, `reg-flow`, `reg-machine`,
`reg-route`, schema registration, view registration, and effect handlers.

That is idiomatic re-frame, but it is not the highest-productivity form for a
large application with many teams and AI-assisted maintenance. Large SPAs need
answers to questions such as:

- What state does this feature own?
- Which events can mutate it?
- Which routes activate it?
- Which resources does it fetch?
- Which views render it?
- Which effects can it emit?
- What are its privacy and egress boundaries?
- Can I mount two copies?
- Can I remove it?
- What will collide if I install it?

Scattered registration makes those answers emergent.

Recommended spec freedom:

Add a feature/module manifest as data:

```clojure
{:id        :billing/invoices
 :owns      {:app-db    [[:billing :invoices]]
             :runtime   []
             :resources [:invoice/list]}
 :schemas   [...]
 :events    [...]
 :subs      [...]
 :facts     [...]
 :machines  [...]
 :routes    [...]
 :views     [...]
 :effects   [...]
 :privacy   {:egress [:invoice/list]
             :sensitive-paths [[:billing :invoices :items :customer-email]]}}
```

The manifest should compile to today's registrations. That preserves the
existing runtime while creating a better source-of-truth for humans, AI tools,
test generation, documentation, collision detection, and dead-code removal.

Category-theory lens: feature modules should compose monoidally. Combining two
modules should produce either a larger module or a precise collision/error
value. That is a useful design constraint even if the public docs never use the
word "monoid".

### 9. App-DB Paths Need Optics, Not Just Vectors

The library correctly treats app-db as data. But large app-db maps accumulate
path strings/vectors across handlers, subs, flows, schemas, tests, and docs.
Raw vectors are simple, but they are weak refactoring handles.

Recommended spec freedom:

Allow named path specs or optics as data:

```clojure
{:id      :invoice/customer-email
 :path    [:billing :invoices :by-id '?invoice-id :customer :email]
 :schema  :email
 :owner   :billing/invoices
 :privacy :sensitive}
```

Handlers and derivations could refer to path ids or compiled optics. This
would support:

- schema-aware `get-in` and `assoc-in`
- privacy/redaction by ownership
- rename/refactor tooling
- generated tests
- safer materialized flows
- clearer dependency graphs

This can stay lightweight. It does not require importing a heavy optics
library. The important shift is from anonymous paths to named, owned, typed
accessors.

### 10. Machines Are Powerful But May Be Too Isolated As A Concept

The machine implementation is a serious asset: it gives actors, hierarchy,
timers, spawn order, and durable snapshots a principled model. The concern is
not correctness. The concern is product architecture: many SPA problems are
"remote resource plus retry plus cancellation plus route egress plus UI state".
Today that may be expressed across HTTP managed effects, routing egress,
machines, flows, and subs.

Recommended spec freedom:

Define a broader `Process` abstraction where machines are the most formal
process type, not the only one:

```clojure
{:id          :invoice/list-loader
 :kind        :resource-process
 :inputs      [[:route :invoice/list]
               [:db [:auth :tenant-id]]]
 :states      ...
 :commands    ...
 :storage     :runtime-db
 :cancellation {:on-route-exit true
                :on-frame-destroy true}}
```

The implementation can still compile serious cases to the existing machine
runtime. The API would let common data-loading workflows feel first-class
without asking every team to hand-author a full statechart.

## Concrete `/spec` Changes That Would Free The Implementation

1. **Spec 000 / Vision:** name `Runtime Environment` or `Realm` as a pattern
   concept. State that globals are a CLJS reference default, not the essence of
   the architecture.

2. **Spec 001 / Registration:** registrations belong to a realm. The process
   global registrar is the default realm. Add explicit realm-targeted
   registration as a permitted shape.

3. **Spec 002 / Frames:** frames carry or reference their realm. Change
   "frames isolate state, not behavior" from a pattern law to a default
   compatibility posture. Multi-frame same-behavior apps remain easy, but
   multi-realm same-process apps become legal.

4. **Spec 006 / Reactive Substrate:** relax "single adapter per process" to
   "adapter per realm/root" at the pattern level. Keep current singleton
   install as the initial CLJS implementation if desired.

5. **Spec 006 or a new runtime-subsystem spec:** add host-transient subsystem
   descriptors for timers, in-flight requests, scroll caches, nav counters,
   flow dirty-check caches, and similar state.

6. **Spec 013 / Flows:** fix the stale `:rf/default` fallback wording. Bare
   `reg-flow` should follow the carried frame invariant and fail without a
   frame scope, matching Spec 002.

7. **Spec 013 plus Specs 014/005/subscriptions:** introduce a common
   derivation/process vocabulary. Position `reg-sub`, `reg-runtime-sub`,
   `reg-flow`, resource queries, and machine selectors as specialized source
   forms over the same dependency graph.

8. **Runtime architecture spec:** describe event handling as transaction-plan
   production and transformation before commit/interpretation. Make this the
   conceptual model for router extensions.

9. **Spec 010 / Schemas and Spec 015 / Data Classification:** allow named path
   specs/optics with ownership, schema, and privacy metadata. Let app-db paths
   become durable refactoring handles.

10. **New feature-module spec:** define a data manifest for a feature slice.
    It should compile to existing registration functions but become the
    preferred high-productivity source form for large apps.

## What I Would Not Change

- Do not abandon events, effects, and subscriptions as data. That is still the
  core strength.
- Do not put raw atoms in the public API. Public storage cells couple users to
  implementation strategy.
- Do not force category-theory vocabulary into app-facing docs. Use it to
  design clean composition laws, then expose simple Clojure data.
- Do not publish an unrestricted third-party runtime subsystem API yet. First,
  make the internal framework subsystem model coherent.
- Do not make non-React substrates a near-term priority. The current spec is
  right to treat React context as CLJS reference machinery and keep the pattern
  level focused on explicit frame addressing.
- Do not undo the no-ambient-default frame posture. It is painful in places,
  but it is the right pain.

## Recommended Work Sequence

1. **Small correctness bead:** fix `spec/013-Flows.md` stale `:rf/default`
   wording.

2. **EP: Runtime Environment / Realm.** Define realm ownership of registrar,
   adapter, late-bound capabilities, and frames. Keep default global realm for
   compatibility.

3. **EP: Host-Transient Runtime Subsystems.** Generalize the internal lifecycle
   table for HTTP in-flight, routing counters, scroll caches, machine timers,
   spawn order, and flow last-inputs.

4. **EP: Derivation Algebra.** Unify the mental model behind subs, runtime
   subs, flows, resources, and selected machine state. Initially this can be
   a spec/documentation EP with compatibility compilation.

5. **EP: Feature Module Manifests.** Design the data form that lets a large SPA
   express a feature slice as one composable value, with collision detection and
   ownership.

6. **Implementation spike:** build a non-public runtime environment record and
   move one easy singleton behind it in tests. The point is to validate whether
   the realm idea simplifies code before committing broad API surface.

## Bottom Line

re-frame2's strongest direction is "state as data, frame as explicit identity,
effects as commands, runtime state as a separate partition". That should be
kept.

The architecture will better serve large SPAs if it moves one level more
functional: not just app-db as a value, but the runtime environment itself as a
composable value. Registrars, adapters, capabilities, derivations, host
resources, and feature modules should be explicit data or records that can be
composed, validated, installed, inspected, and disposed. The current singleton
surfaces can remain as convenient defaults, but the spec should stop requiring
them as the only possible shape.
