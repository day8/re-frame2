# EP-0029: XState v6 Machine Parity

Status: proposal
Type: standards-track

> This EP proposes how re-frame2 should move its state-machine parity target
> from XState v5 to the XState v6 alpha direction. If accepted, the normative
> homes are `spec/005-StateMachines.md`, the machine implementation docs, and
> the machine guide material under `docs/guide`. The proposal deliberately
> classifies v6 changes into three groups: features re-frame2 should embrace,
> features it might include after separate rulings, and JavaScript/XState
> surfaces it should ignore.

## Abstract

XState v6 is not a small v5 cleanup. The current alpha removes many helper
creator APIs, makes functions and schemas more central, adds explicit timeout
and choice-state primitives, and expands the JSON/workflow/persistence story.

re-frame2 is already aligned with much of this direction because it prefers
plain Clojure functions, effect data, and runtime-db snapshots over XState's v5
helper-heavy style. This EP proposes adopting the v6 ideas that strengthen
re-frame2's machine model, considering the ideas that may be useful but are not
core, and rejecting surfaces that would make re-frame2 look like JavaScript
XState rather than a Clojure/re-frame statechart system.

## Motivation

EP-0005 and the current machine guide describe re-frame2 in terms of XState v5
parity. That was useful when v5 was the stable reference point, but the v6 alpha
direction changes the target:

- helper creators such as `assign`, `sendTo`, `raise`, `enqueueActions`, `and`,
  `or`, `not`, and `stateIn` are removed or replaced;
- `schemas` replaces the old `types: {} as ...` style;
- `setup()` no longer owns action/guard/actor/delay implementations;
- `interpret` is removed in favor of `createActor`;
- state and invoke timeouts become first-class;
- `choice` states are added;
- JSON serialization/revival and durable async workflow steps receive more
  attention.

The risk is that "XState parity" becomes a stale phrase that points at v5
surfaces XState itself is abandoning. The opportunity is that v6 moves closer to
re-frame2's existing strengths: plain functions, data-first configuration,
effect descriptions, and explicit runtime state.

## Goals / Non-Goals

Goals:

- replace the current "XState v5 parity" framing with a v6-aware machine
  roadmap;
- list the known v6 alpha changes that affect re-frame2;
- classify each change as "embrace", "might include", or "ignore";
- give simple translations from XState terms to plausible re-frame2 syntax;
- name the docs/spec surfaces that would change if the EP is accepted.

Non-goals:

- do not implement the features in this EP;
- do not promise compatibility with every XState v6 alpha detail before v6
  stabilizes;
- do not build a JavaScript compatibility layer for XState actor objects;
- do not reopen unrelated machine decisions unless v6 directly pressures them;
- do not preserve pre-alpha compatibility shims merely to keep old v5 parity
  wording alive.

## Relationships

- **EP-0005** established machine `:data-schema` and recorded XState v5 parity.
  This EP amends that framing. If accepted, EP-0005's schema validation
  principle remains valuable, but the XState comparison target becomes v6 and
  `:data-schema` may become part of a broader `:schemas` map.
- **EP-0007** supplies the one-name-per-fact rule. This matters for whether
  re-frame2 should add XState spellings such as `:invoke` beside existing
  `:spawn`.
- **EP-0010** and **EP-0011** define causal inputs and uniform async reply
  envelopes. Those are the local foundations for any durable workflow-step
  feature inspired by XState v6 `createAsyncLogic`.
- **EP-0014** names the common derivation/process model behind subscriptions,
  resources, flows, and machines. That model is relevant to any v6-inspired
  `listen`/`subscribeTo` equivalent.
- **`spec/005-StateMachines.md`** is the primary normative home for accepted
  machine grammar and runtime semantics.
- **`docs/guide/concepts/machines.md`** is the main human-facing guide material
  that currently teaches the XState comparison.

Research basis:

- XState `6.0.0-alpha.1` and `6.0.0-alpha.2` release notes;
- the open XState v6 PR from `next` into `main`;
- source/tests at the alpha.2 commit (`172e4a8`);
- npm dist-tags showing v6 on `alpha`, not `latest`;
- public Stately docs still marked as XState v5 docs.

## Specification

At `proposal` status, this is not yet a shipped contract. The grouping below is
the proposed decision surface for operator ruling.

### Decision rule

re-frame2 should adopt an XState v6 change when it strengthens the local model:

- clearer machine grammar;
- more inspectable data;
- better replay/persistence behavior;
- better tooling and docs;
- fewer special helper APIs.

re-frame2 should not adopt an XState v6 surface merely because it exists in
JavaScript. Parity means matching the statechart capability and the design
direction, not copying every actor API shape.

## Group A: re-frame2 should embrace

These changes fit re-frame2's direction and should be part of the v6 parity
roadmap if the EP is accepted.

### A1. Retarget the docs from XState v5 to XState v6

XState:

XState v6 removes many v5 helper APIs and resets the recommended authoring
style. Public Stately docs are still v5, but the v6 alpha release notes and PR
show a clear direction.

re-frame2:

The machine guide should stop saying re-frame2 is chasing v5 as the live target.
It should say re-frame2 is aligned with the v6 direction while v6 is alpha, and
it should keep an explicit "not copied from XState" divergence list.

Usefulness:

This prevents the project from implementing obsolete v5 parity features. It also
turns re-frame2's current Clojure style into a strength rather than a divergence
to apologize for.

### A2. Plain functions for transitions, guards, and actions

XState:

v6 makes transitions, actions, and guards plain inline functions. A transition
or action can return updated context and use an enqueue helper for side effects.

re-frame2:

Guards and actions already use Clojure functions. The new decision is whether a
transition entry itself can be a function:

```clojure
{:states
 {:counting
  {:on
   {:inc (fn [{:keys [data]}]
           {:data (update data :count inc)})
    :finish (fn [{:keys [data]}]
              (when (>= (:count data) 10)
                {:target :done
                 :fx [[:analytics/track {:event :finished}]]}))}}}}
```

The function returns the same kind of transition result a map transition would
describe: `:target`, `:data`, `:fx`, `:raise`, and related machine-result keys.
re-frame2 should not add an imperative JavaScript `enq` object for this; return
maps and effect vectors are the local idiom.

Usefulness:

This closes the most visible v6 authoring gap while keeping the existing
data-first transition grammar. It gives authors a compact form for "the event
is mostly code" without inventing helper creators.

### A3. A broader `:schemas` machine contract

XState:

v6 replaces `types: {} as ...` with `schemas` for context, events, input,
output, emitted events, tags, and meta. It can use Standard Schema-compatible
JavaScript libraries or type-only declarations.

re-frame2:

Use a Malli-shaped `:schemas` map rather than importing the JavaScript Standard
Schema brand:

```clojure
{:schemas
 {:data [:map [:count :int]]
  :events {:counter/inc [:map]
           :counter/set [:map [:value :int]]}
  :input [:map [:initial-count :int]]
  :output [:map [:count :int]]
  :tags [:enum :busy :done]
  :meta [:map]}}
```

The open ruling is whether `:data-schema` becomes a shorthand for
`[:schemas :data]` or is retired by a clean pre-alpha break.

Usefulness:

This preserves EP-0005's runtime validation strength while aligning the grammar
with v6's broader type surface. It also gives tools a single place to discover
machine data, event payloads, final output, tags, and metadata.

### A4. Explicit state and spawn timeouts

XState:

v6 adds `timeout` and `onTimeout` at state and invoke level. It also accepts
duration strings such as `10ms`, `5s`, and ISO durations such as `PT2M`.

re-frame2:

Add state-level `:timeout` / `:on-timeout` and spawn-level timeout semantics:

```clojure
{:states
 {:waiting
  {:timeout "5s"
   :on-timeout {:target :timed-out}}

  :loading
  {:spawn {:machine :fetch-user
           :data {:id 42}
           :timeout "10s"
           :on-timeout {:target :timed-out}}}}}
```

Existing `:after` remains the general delayed-transition primitive. `:timeout`
is the named semantic form: it requires `:on-timeout`, is cancelled on state
exit, and spawn timeout is cancelled when the child completes.

Usefulness:

Timeouts are common in real workflows and are more legible when named directly
instead of encoded as another anonymous `:after` transition. Duration strings
also improve authoring ergonomics without weakening integer millisecond support.

### A5. First-class `:choice` states

XState:

v6 adds `type: "choice"` states that immediately choose a target through a
resolver function. A choice state cannot also behave like a normal state with
entry/exit/on/after/invoke behavior.

re-frame2:

Add `:type :choice`:

```clojure
{:states
 {:route
  {:type :choice
   :choice (fn [{:keys [data]}]
             (if (:valid? data) :accepted :rejected))}}}
```

The state can likely lower to existing always-transition machinery, but the
grammar should validate the v6-style restrictions: a choice state must have a
choice resolver and must not also declare normal state behavior.

Usefulness:

This names an important modeling concept. `:always` can express the behavior,
but `:choice` explains the intent to readers, tools, diagrams, and diagnostics.

### A6. `:internal-events`

XState:

v6 has `internalEvents`: events that can be raised internally but are rejected
when sent from outside the actor.

re-frame2:

Add a machine-level admission gate:

```clojure
{:internal-events #{:tick :retry/internal}
 :states ...}
```

If wildcard parity is accepted, use an explicit pattern form rather than making
ordinary keywords magical:

```clojure
{:internal-events [:tick "change.*"]}
```

The check belongs at the external machine dispatch boundary. Internally raised
events still run through the transition reducer.

Usefulness:

This protects private machine protocol events. It also clarifies the difference
between public events a caller may dispatch and internal events produced by
machine logic.

### A7. Canonical serialization with explicit unserializable markers

XState:

v6 strengthens `serializeMachine`, `createMachineFromConfig`, JSON revival, and
explicit markers for functions, schemas, and actor logic that cannot round-trip
as plain JSON.

re-frame2:

Define a canonical serializable machine form. It may be EDN-first, but it should
round-trip without silent drops and should mark non-portable values explicitly:

```clojure
{:actions {:save {:rf.machine/unserializable true
                  :reason :function}}
 :states ...}
```

The exact marker key is open; the rule is not. A serializer must not pretend an
anonymous function, schema object, or host function survived if it did not.

Usefulness:

This is critical for AI tools, machines-viz, snapshot review, generated docs,
and possible XState JSON export. It also fits re-frame2's data-first posture.

### A8. Spawn ordering, failure, and rehydration semantics

XState:

v6 changes actor startup ordering so child start belongs to the transition that
creates the child. It routes synchronous child startup failure to the invoking
state's error path. Alpha.2 also fixed invoke input/dynamic source so they see
post-transition context when a transition both updates context and enters an
invoking state.

re-frame2:

Verify and, if needed, specify these spawn rules:

- a parent action's updated `:data` is visible to `:spawn :data` functions when
  the same transition enters a spawn-bearing state;
- child initialization/start failure can route to the parent's spawn error
  transition rather than escaping as an unhandled exception;
- restored active child snapshots restart consistently when the parent runtime
  snapshot is restored.

Usefulness:

This is not cosmetic parity. It is transition-order correctness. Bugs here
create confusing parent/child data races and brittle recovery behavior.

### A9. Explicit final output, tags, and meta preservation

XState:

v6 schemas include `output`, `tags`, and `meta`; serialization preserves tags
and output.

re-frame2:

If `:schemas` lands, include `:output`, `:tags`, and `:meta`. Final output
should be a snapshot fact rather than only an implicit convention over `:data`:

```clojure
{:schemas {:output [:map [:user-id :uuid]]
           :tags [:enum :loading :complete]
           :meta [:map]}
 :states
 {:done {:type :final
         :output (fn [{:keys [data]}]
                   {:user-id (:user-id data)})}}}
```

Usefulness:

This helps spawned parent/child workflows, tools, final-state inspection, and
serialization. It is small if built on existing final-state behavior.

## Group B: re-frame2 might include

These v6 features may be useful, but they either need separate design pressure
or fit a subsystem outside the core machine grammar.

### B1. State input

XState:

v6 can pass input to a state when it is entered, and snapshots can expose inputs
by state node.

re-frame2:

Possible shape:

```clojure
{:initial {:target :editing
           :input {:source :new}}
 :states
 {:editing
  {:schemas {:input [:map [:source [:enum :new :existing]]]}
   :entry :hydrate-editor}}}
```

Usefulness:

State input is different from durable `:data`: it describes why this state entry
happened. That can be useful, but it adds another data channel and may overlap
with event payloads, transition data, and parent spawn data. Include only if
real examples show cleaner models.

### B2. Actor trigger ergonomics

XState:

v6 adds `actor.trigger.EVENT(payload)`, shorthand for sending a typed event.

re-frame2:

Possible helper:

```clojure
(rf/dispatch (rf/machine-event :counter :counter/inc {:by 1}))
```

or:

```clojure
((rf/machine-trigger :counter) :counter/inc {:by 1})
```

Usefulness:

This might reduce noisy event-vector construction in examples. It is not core
parity because re-frame2 callers already have `rf/dispatch` and no actor object.

### B3. Emitted events as a separate channel

XState:

v6 has `enq.emit` and `schemas.emitted`, separate from sending events to actors.

re-frame2:

Possibilities:

- treat emitted events as trace/observability facts;
- treat them as ordinary re-frame dispatch effects;
- add a machine-local listener channel.

Usefulness:

A separate emit channel can be useful for instrumentation and parent/host
integration, but it risks duplicating re-frame dispatch, traces, and managed
effect replies. It should not be added until the consumer is clear.

### B4. Durable async workflow steps

XState:

v6 `createAsyncLogic` can persist completed `enq.step` results so rehydration
skips already-completed steps.

re-frame2:

This would likely live in managed effects or workflows rather than in the small
machine reducer:

```clojure
{:fx [[:rf.workflow/step {:id :charge-card
                          :request charge-request
                          :on-success [:checkout/charged]}]]}
```

Usefulness:

This is powerful for backend/workflow orchestration. It is probably not needed
for ordinary frontend statechart parity, and it should build on EP-0010/0011
causal/reply semantics if accepted.

### B5. State-bound subscriptions/listeners

XState:

v6 adds `enq.listen` and `enq.subscribeTo` to subscribe to actors or atoms with
automatic teardown.

re-frame2:

Possible local shape is a state-bound resource/listener declaration, not an
imperative enqueue call:

```clojure
{:states
 {:watching
  {:listen [{:source [:sub [:clock/tick]]
             :on-value :clock/ticked}]}}}
```

Usefulness:

This may help external resource lifecycles, but it overlaps with re-frame
subscriptions, resources, flows, and component lifecycles. It belongs in a
separate design if concrete pressure appears.

### B6. Snapshot versioning and migration

XState:

v6 continues the push toward persisted snapshots and machine migration.

re-frame2:

Possible shape:

```clojure
{:version 3
 :migrate (fn [snapshot from-version]
            ...)}
```

Usefulness:

This is useful if re-frame2 promises long-lived persisted machine snapshots. If
snapshots remain development/runtime artifacts for now, loud version mismatch
may be enough.

### B7. Full XState JSON import/export compatibility

XState:

v6's data-first support makes JSON machine configs more important.

re-frame2:

Canonical re-frame2 serialization should be embraced. Exact XState JSON import
and export could be a tooling layer:

```clojure
(rf.machine.xstate/export machine)
(rf.machine.xstate/import xstate-json {:actions actions
                                       :guards guards})
```

Usefulness:

This may help interoperability with Stately tools, but exact compatibility can
distort the native machine grammar. It should be tooling-owned unless a strong
product use case appears.

### B8. Reusable state config fragments

XState:

v6 includes `createStateConfig(...)` for standalone typed state-node configs.

re-frame2:

A local equivalent could be plain data/functions or a helper that validates a
state fragment before insertion:

```clojure
(def loading-state
  (rf.machine/state-config
    {:entry :start
     :timeout "10s"
     :on-timeout :failed}))
```

Usefulness:

This can reduce repetition in large machines, but ordinary Clojure data
composition may already be enough. Include only if validation/tooling benefits
are real.

### B9. Generic async-logic timeout

XState:

v6 includes timeout behavior for async logic, not only state/invoke timeout.

re-frame2:

Managed effects may eventually accept timeout policy directly:

```clojure
{:fx [[:http/get {:url "/api/user"
                  :timeout "5s"
                  :on-timeout [:user/load-timeout]}]]}
```

Usefulness:

Useful, but not a machine-only feature. It belongs with managed effects and
resources rather than EP-0029's core machine grammar.

## Group C: re-frame2 should not include or should ignore

These v6-adjacent surfaces should not be copied into re-frame2 core.

### C1. v5 helper creator compatibility

XState:

v6 removes helper creators such as `assign`, `raise`, `sendTo`, `sendParent`,
`emit`, `log`, `cancel`, `spawnChild`, `stop`, `enqueueActions`, and guard
creators such as `and`, `or`, `not`, and `stateIn`.

re-frame2:

Do not add Clojure versions of those helpers. Use functions, return maps,
effect vectors, named guards/actions, tags, and `:all-state`.

Reason:

Adding helper APIs now would copy the part of v5 that v6 is deleting.

### C2. JavaScript-style `setup()` implementation registries

XState:

v6 simplifies `setup()` so it no longer registers action/guard/actor/delay
implementations.

re-frame2:

Do not introduce a `setup`-like registry layer. Keep machine-local `:guards`,
`:actions`, and related implementation maps as the data/config mechanism.

Reason:

The old v5 analogy is obsolete, and a new registry concept would duplicate
ordinary Clojure maps and image/frame registration machinery.

### C3. An imperative `enq` object

XState:

v6 action/transition functions use `enq.raise`, `enq.sendTo`, `enq.emit`, and
similar calls for side effects.

re-frame2:

Do not add an imperative enqueue object. Use return values:

```clojure
{:data updated-data
 :fx [[:http/get request]]
 :raise [[:machine/internal-event]]}
```

Reason:

Return maps are easier to inspect, test, serialize, trace, and replay in
Clojure. They are also more consistent with re-frame event handlers.

### C4. `interpret` or `Interpreter` compatibility

XState:

v6 removes the deprecated `interpret` path in favor of `createActor`.

re-frame2:

Do nothing. re-frame2 never had this API.

Reason:

There is no migration surface and no value in simulating removed JavaScript
classes.

### C5. A public `:invoke` rename or stable alias for `:spawn`

XState:

XState uses `invoke` for state-bound actors.

re-frame2:

Keep `:spawn` / `:spawn-all` as the public spelling unless a later EP explicitly
reopens that naming decision. An XState import/export tool may translate
`invoke` at the boundary, but the native grammar should not carry both names.

Reason:

EP-0007 argues against accepted synonyms. The current `:spawn` term reflects
re-frame2's model: state-bound child machines in runtime-db, not generic actor
logic with an XState mailbox.

### C6. JavaScript Standard Schema as a project dependency or brand

XState:

v6 schemas can use Standard Schema-compatible JavaScript libraries.

re-frame2:

Do not import that brand into the Clojure API. Use Malli-shaped schemas or the
project's existing schema conventions.

Reason:

The useful idea is the schema categories, not the JavaScript ecosystem marker.

### C7. Full XState actor object semantics in core

XState:

v6 centers actors around `createActor`, concrete `Actor`, actor refs, triggers,
mailboxes, emitted events, subscriptions, and actor logic creators.

re-frame2:

Do not make core machines require actor objects. Keep the frame dispatch,
runtime-db snapshot, and effect-reply model.

Reason:

Actor objects are the JavaScript runtime vehicle. re-frame2's runtime vehicle is
the frame and event cascade. Copying the vehicle would obscure the local design.

### C8. Alpha-churn exactness

XState:

v6 is alpha. The docs are still v5 and the release surface may change.

re-frame2:

Do not chase every alpha patch as normative. Track stable direction, add tests
for adopted semantics, and record differences clearly.

Reason:

The point is a better re-frame2 machine model, not a moving-target clone.

## Proposed docs and spec impact

If accepted, implementation should update:

- `spec/005-StateMachines.md` for new grammar and semantics;
- machine implementation docs/docstrings for transition-result and timeout
  behavior;
- `docs/guide/concepts/machines.md` for the XState comparison;
- machines-viz/tooling docs once serialization and `:choice` affect diagrams;
- any conformance fixture capability names affected by new grammar.

The guide should teach three ideas:

1. re-frame2 follows XState v6's plain-function, schema-aware direction;
2. re-frame2 expresses side effects as data returned from handlers;
3. re-frame2 intentionally keeps frame dispatch/runtime-db semantics instead of
   copying XState actor objects.

## Rationale

The embrace/maybe/ignore split keeps parity honest. Some v6 changes are plainly
better machine design for re-frame2: `:schemas`, timeouts, `:choice`, internal
events, serialization, and spawn ordering rules. Some are promising but belong
elsewhere or need examples: durable workflow steps, emitted-event channels,
state input, and listener lifecycles. Some are JavaScript implementation shapes
or v5 leftovers that re-frame2 should not copy.

Function-valued transitions are the most important open authoring decision.
They align with v6, but they should not replace data transition maps. The clean
model is additive: data maps remain the ordinary inspectable form, and function
entries exist for transitions whose behavior is naturally computed.

The `:schemas` proposal intentionally keeps EP-0005's core win: machine data is
declared and validated. v6 broadens the schema surface; re-frame2 should broaden
the categories without giving up runtime Malli validation.

The `:spawn` recommendation applies EP-0007. XState's `invoke` is understandable
inside XState, but re-frame2 already gave the local state-bound child-machine
relationship a name. Carrying both names would make docs and diagnostics worse.

## Backwards Compatibility

re-frame2 is pre-alpha, so accepted changes should be clean breaks rather than
compatibility shims.

Potential breaking changes:

- `:data-schema` may move under `:schemas :data`;
- transition grammar may add function-valued transition entries;
- invalid choice-state/timeout/internal-event declarations should fail loudly;
- docs and examples should stop using v5 helper terminology;
- serialization may reject or mark machines that previously printed with silent
  loss of functions or schemas.

If the operator rules that `:data-schema` should be retired, the implementation
wave should migrate existing examples/specs/tests in one pass rather than keep a
long-lived alias.

## Bead Plan / Reference Implementation

This EP should not be implemented as one large bead. Suggested waves:

1. **Docs alignment.** Rewrite machine guide/spec references from XState v5 to
   v6-alpha direction. Record deliberate divergences.
2. **Core grammar.** Add function-valued transition entries, `:schemas`,
   `:timeout`/`:on-timeout`, `:type :choice`, and `:internal-events`, with
   conformance tests.
3. **Spawn correctness.** Add focused tests for post-transition data visibility,
   child startup error routing, timeout cancellation on child completion, and
   restored child restart behavior.
4. **Serialization/tooling.** Define canonical machine serialization and
   unserializable markers, then update machines-viz/export tooling.
5. **Maybe bucket review.** File separate EPs or beads only for the maybe items
   that receive operator approval.

Guide-impact assessment:

- `docs/guide/concepts/machines.md` changes immediately on acceptance.
- Any machine tutorial that compares `context`, `assign`, `setup`, or actor
  sending to re-frame2 should be revised.
- Serialization and choice-state guide examples should land when those features
  land, not before.

## Open Issues

1. **Function-valued transitions:** should they be accepted as first-class
   transition entries, or should re-frame2 keep functions limited to guards and
   actions?
2. **Schema spelling:** should `:data-schema` remain as shorthand for
   `:schemas :data`, or should pre-alpha re-frame2 make the clean break to
   `:schemas` only?
3. **State input:** is v6-style state input useful enough to justify a second
   state-entry data channel?
4. **Serialization target:** should canonical machine serialization be EDN-first
   with optional JSON export, or should XState-compatible JSON be a first-class
   goal?
5. **Emitted events:** should re-frame2 add a separate machine emission channel,
   or treat emissions as trace/dispatch effects?
6. **Durable workflow steps:** is backend/workflow orchestration in scope for
   the machine subsystem, or should it remain a managed-effects/resource concern?
7. **Snapshot migration:** do persisted machine snapshots need a framework-level
   `:version`/`:migrate` hook now, or is loud mismatch enough?

## Recommendation

Accept EP-0029 in principle, with Group A as the implementation roadmap, Group B
as explicitly undecided future work, and Group C rejected as non-native or stale
parity.

This gives re-frame2 a current XState reference point without turning it into an
XState clone. The result should be a clearer Clojure machine grammar: plain
functions where code is useful, data where inspection matters, schemas where
tools need facts, and explicit divergences where re-frame2's runtime model is
stronger.
