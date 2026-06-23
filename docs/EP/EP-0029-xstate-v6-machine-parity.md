# EP-0029: XState v6 Machine Parity

Status: final
Type: standards-track

> This EP retargets re-frame2's state-machine comparison from XState v5 to the
> XState v6 alpha direction. The normative homes are `spec/005-StateMachines.md`,
> the machine implementation docs, and the machine guide material under
> `docs/guide`. The work is not "copy XState v6". It classifies each known v6
> change into: things re-frame2 embraces, things left for later consideration
> but probably not, and things re-frame2 ignores or rejects.

## Abstract

XState v6 alpha changes the shape of XState's machine API. It removes many v5
helper creators, makes schemas more central, adds explicit timeouts and choice
states, and leans harder into actor/workflow/persistence tooling.

re-frame2 should follow the useful direction, not the JavaScript runtime shape.
The right translation is: keep machines declarative and inspectable, use plain
functions at the guard/action boundary, express effects as returned data, and
keep frame dispatch/runtime-db semantics instead of copying XState actor
objects.

## Motivation

EP-0005 and the current machine guide describe re-frame2 in terms of XState v5
parity. That target is now stale. XState v6 alpha removes or redesigns several
surfaces that v5 made prominent:

- helper creators such as `assign`, `sendTo`, `raise`, `enqueueActions`, `and`,
  `or`, `not`, and `stateIn`;
- `types: {} as ...`, replaced by `schemas`;
- `setup({ actions, guards, actors, delays })`, simplified away from
  implementation registration;
- `interpret`, replaced by `createActor`;
- generic v5-style helper APIs around actions, guards, and actors.

At the same time, v6 adds ideas that fit re-frame2 well:

- explicit state and actor timeouts;
- choice states;
- internal-only events;
- a broader schema vocabulary;
- sharper actor startup/error ordering.

The risk is adopting obsolete v5 parity work. The opportunity is to make the
machine docs more honest: re-frame2 already agrees with v6 where v6 moves toward
plain functions and simpler authoring, but re-frame2 should keep its own
data-first, re-frame-native runtime model.

## Goals / Non-Goals

Goals:

- replace the current "XState v5 parity" framing with a v6-aware roadmap;
- explain each important v6 concept in simple terms;
- state how each concept should, might, or should not appear in re-frame2;
- preserve a declarative FSM surface suitable for diagrams, tools, conformance,
  model tests, and AI inspection;
- identify the docs/spec surfaces that this EP changes.

Non-goals:

- do not implement these changes in this EP;
- do not promise exact compatibility with an alpha XState release;
- do not build a JavaScript compatibility layer for XState actor objects;
- do not add a required schema dependency such as Malli to machine core;
- do not add compatibility shims merely to keep old v5 wording alive.

## Relationships

- **EP-0005** established machine `:data-schema` and recorded XState v5 parity.
  This EP amends that framing. EP-0005's validation idea remains useful, but the
  target becomes the v6 direction and schema validation must remain optional.
- **EP-0007** supplies one-name-per-fact. This EP applies it by rejecting a
  public `:invoke` alias for native `:spawn`.
- **EP-0010** and **EP-0011** define causal inputs and the uniform async reply
  envelope. Those are the right substrate if durable workflow steps ever become
  a re-frame2 feature.
- **EP-0014** names the derivation/process model behind subscriptions,
  resources, flows, and machines. It explains why listener-like work often
  belongs outside the machine grammar.
- **`spec/005-StateMachines.md`** is the primary normative home for accepted
  machine grammar and runtime semantics.
- **`docs/guide/concepts/machines.md`** is the main guide page that currently
  teaches the XState comparison.

Research basis:

- [XState `6.0.0-alpha.1` release notes](https://github.com/statelyai/xstate/releases/tag/xstate%406.0.0-alpha.1);
- [XState `6.0.0-alpha.2` release notes](https://github.com/statelyai/xstate/releases/tag/xstate%406.0.0-alpha.2);
- [the open XState v6 PR from `next` into `main`](https://github.com/statelyai/xstate/pull/5543);
- source/tests at the alpha.2 commit (`172e4a8`);
- npm dist-tags showing v6 on `alpha`, not `latest`;
- [public Stately docs still marked as XState v5 docs](https://stately.ai/docs).

## Specification

EP-0029 is **final** (graduated 2026-06-24; accepted 2026-06-23). The grouping
below is the ruled decision surface; the implementation landed across the waves
in the Bead Plan, each gated on the acceptance.

### Decision rule

re-frame2 should adopt a v6 idea when it strengthens the local model:

- declarative machine topology;
- clearer public grammar;
- inspectable data;
- deterministic event/effect semantics;
- better tooling and docs.

re-frame2 should reject a v6 surface when it mainly copies JavaScript runtime
ergonomics, hides topology in code, or duplicates an existing re-frame2 concept.

## Group A: re-frame2 should embrace

These formed the first v6 parity roadmap.

### A1. Retarget docs from XState v5 to XState v6

XState concept:

XState v6 alpha changes the recommended shape of XState machines. It removes
many v5 helper APIs and shifts the story toward plain functions, schemas, actor
startup semantics, and explicit timeouts.

re-frame2 expression:

The machine guide should stop saying v5 is the live parity target. It should say
re-frame2 tracks the useful v6 direction while v6 is alpha, and then list the
intentional divergences.

Why useful:

This avoids implementing v5 features that XState itself is leaving behind. It
also makes re-frame2's current posture clearer: a machine is a re-frame event
handler with data-first transition tables, not an XState actor object.

### A2. Plain guard/action functions with declarative topology

XState concept:

XState v6 lets transitions, actions, and guards be inline functions. In
JavaScript this is an ergonomic simplification.

re-frame2 expression:

re-frame2 should embrace plain functions for guards and actions, but keep the
transition topology declarative. Targets, candidate transitions, `:always`,
`:after`, `:timeout`, and `:choice` remain machine data.

Preferred shape:

```clojure
{:on
 {:finish [{:guard :count-complete?
            :target :done}
           {:target :still-counting}]}
 :guards
 {:count-complete? (fn [{:keys [data]}]
                     (>= (:count data) 10))}
 :actions
 {:record-finish (fn [{:keys [data]}]
                   {:data (assoc data :finished? true)})}}
```

Why useful:

This keeps edges visible to diagrams, model tests, conformance fixtures, and AI
tools. Code still exists where it belongs: predicates and effects. The graph
itself remains data.

Important boundary:

Fully function-valued transition entries are rejected in Group C. They hide the
edge topology.

### A3. Broader `:schemas`, optional validation

XState concept:

XState v6 replaces `types: {} as ...` with `schemas`. It can describe context,
events, input, output, emitted events, tags, and meta.

re-frame2 expression:

re-frame2 should add a machine-level `:schemas` section, but schema validation
must remain optional. The machine core must not require Malli or JavaScript
Standard Schema.

Simple shape:

```clojure
{:schemas
 {:data <schema>
  :events {:counter/inc <schema>
           :counter/set <schema>}
  :output <schema>
  :tags <schema>
  :meta <schema>}}
```

`<schema>` is intentionally abstract here. A Malli adapter can interpret Malli
values. Another adapter can interpret another schema representation. A project
with no schema adapter can still use the machine grammar without pulling in a
heavy dependency.

`[:schemas :input]` should be added only if the state-input maybe item is later
accepted. `[:schemas :output]` describes the completion-event payload, not a new
snapshot field; see A8.

Why useful:

The useful v6 idea is the broader machine contract. Tools and optional
validation can learn what a machine's data, events, completion payloads, tags,
and meta look like from one place.

Spelling (ruled):

`:data-schema` is retired by a clean pre-alpha break; the machine data-context
schema is declared at `[:schemas :data]`. There is no `:data-schema` shorthand.
See Resolved Decisions and the surgical retirement plan (rf2-f9nvu9).

### A4. Explicit state and spawn timeouts

XState concept:

XState v6 adds `timeout` and `onTimeout` at state and invoke level. It also
accepts readable duration strings such as `10ms`, `5s`, and ISO durations such
as `PT2M`.

re-frame2 expression:

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

Rules:

- `:timeout` requires `:on-timeout`;
- leaving the state cancels the state timeout;
- child completion cancels the spawn timeout;
- ordinary `:after` remains for general delayed transitions;
- `:after` and `:timeout` may coexist because they express different intent.

Why useful:

Timeout is a common workflow fact. `:timeout` says "this state or child must
finish before this time" more clearly than encoding every timeout as a generic
`:after` transition.

### A5. Declarative `:choice` states

XState concept:

XState v6 adds `type: "choice"` states. A choice state is an immediate routing
node: enter it, choose a target, and leave.

re-frame2 expression:

Add `:type :choice`, but keep the routing declarative with guarded candidates:

```clojure
{:states
 {:checking
  {:type :choice
   :choice [{:guard :valid?
             :target :accepted}
            {:target :rejected}]}}}
```

Rules:

- a choice state must declare `:choice`;
- `:choice` uses the same candidate semantics as normal transitions;
- a choice state should not also declare ordinary waiting-state behavior such as
  `:entry`, `:exit`, `:on`, `:after`, `:timeout`, or `:spawn`.

Why useful:

`:always` can already express immediate routing, but `:choice` names the
intent. It gives diagrams and tools a decision node and gives validation a
clearer grammar.

### A6. `:internal-events`

XState concept:

XState v6 has `internalEvents`: events the machine may raise internally, but
outside callers may not send.

re-frame2 expression:

Add a machine-level private event list:

```clojure
{:internal-events #{:tick :retry/internal}
 :states
 {:waiting
  {:entry {:raise :tick}
   :on {:tick {:target :checking}}}}}
```

Internal `:raise` may produce `:tick`. External dispatch should reject it:

```clojure
(rf/dispatch [:my-machine [:tick]])
```

Rules:

- enforce this at the machine dispatch boundary;
- internal raised events still run through normal transition selection;
- wildcard patterns may be considered, but keywords should remain ordinary
  values unless a pattern form is explicitly chosen.

Why useful:

This separates public machine API from private machine plumbing. Views, tests,
and other handlers should not accidentally depend on events meant only for the
machine's own run-to-completion logic.

### A7. Spawn ordering, failure, and restore semantics

XState concept:

XState v6 tightened actor startup semantics. A child actor's input should see
the parent's post-transition context when the transition both updates context
and enters an invoking state. Child startup errors route through the invoking
state's error path.

re-frame2 expression:

Specify and test the same ordering for `:spawn`:

```clojure
{:on {:load-user {:target :loading
                  :action :store-user-id}}
 :states
 {:loading
  {:spawn {:machine :fetch-user
           :data (fn [{:keys [data]}]
                   {:id (:user-id data)})
           :on-error {:target :failed}}}}}
```

Rules:

- `:spawn :data` sees parent `:data` after the transition action has run;
- child startup/init failure can route to the parent's declared spawn error
  path;
- restored parent/child runtime snapshots should restart active children
  consistently.

Why useful:

This is correctness, not ornament. Parent/child workflows become hard to reason
about if child initialization sees stale parent data, or if child startup
failure escapes around the parent's declared error handling.

### A8. Final output as completion event payload

XState concept:

XState v6 treats machine output as a first-class schema/output concept.

re-frame2 expression:

Do not add XState-style `snapshot.output`. re-frame2 already has a better local
model: final output is selected from the child machine's `:data` via
`:output-key` and delivered to the parent as the `:on-done` completion event
payload.

```clojure
;; child
{:states
 {:done {:final? true
         :output-key :result}}}

;; parent
{:states
 {:loading
  {:spawn {:machine :fetch-user
           :on-done {:target :loaded
                     :action :store-user}}}}}
```

Conceptually:

```clojure
(get-in child-snapshot [:data :result])
;; becomes the payload of the parent completion event / :on-done transition
```

Rules:

- keep `:output-key` as the final state's data selector;
- do not add a long-lived `:output` slot to the child snapshot;
- if `:schemas` includes `:output`, it schemas the completion-event payload;
- keep `:tags` and `:meta` as existing machine/state metadata concepts.

Why useful:

re-frame2 machines are event handlers. Completion is something that happens, so
it should flow as an event. This keeps output aligned with the existing
`:final?` / `:output-key` / `:on-done` contract.

## For Later Consideration - but Probably not

These are not outright rejected, but they need a concrete, proven use case or a
separate design surface before they should become core — and the current
expectation is that they will not. Leave them out until real examples force the
question.

### B1. State input

XState concept:

XState v6 can pass input to a state when it is entered. That input is not the
same thing as context. It is entry-specific data.

Possible re-frame2 expression:

```clojure
{:on
 {:edit-existing {:target :editing
                  :input {:mode :existing}}
  :create-new    {:target :editing
                  :input {:mode :new}}}
 :states
 {:editing
  {:entry :setup-editor}}}
```

Why maybe:

This can express "why this state was entered" without writing temporary routing
facts into durable `:data`. But it adds another data channel alongside `:event`,
`:data`, and spawn data. re-frame2 should include it only if real examples show
that those existing channels make machines less clear.

If ever needed:

State input should be explicit on transition targets and available to entry
actions through the normal context map. It should not silently persist as
machine `:data`.

### B2. Durable workflow steps

XState concept:

XState v6 `createAsyncLogic` can persist completed async steps so a restored
actor can skip work it already finished.

Possible re-frame2 expression:

This should not live inside the transition reducer. If accepted later, it should
look like managed-effects or workflow work:

```clojure
{:fx [[:rf.workflow/step
       {:id :charge-card
        :request charge-request
        :on-success [:checkout/charged]
        :on-error [:checkout/charge-failed]}]]}
```

Why maybe:

It is useful for long-running workflows where repeating a side effect is wrong,
such as payment or backend orchestration. It is also a big surface: persistence,
idempotency, retry, cancellation, and stale suppression. It should build on
EP-0010/EP-0011 if it ever lands.

## Group C: re-frame2 should not include or should ignore

These surfaces should not be part of re-frame2 machine core. Each item also
states the re-frame2 alternative if an application needs a similar result.

### C1. Opaque function-valued transitions

XState concept:

XState v6 permits transition logic to be written as inline functions.

re-frame2 decision:

Do not copy this for ordinary machines. It hides the graph in code.

Use this instead:

- declarative transition candidates for edges;
- guard functions for predicates;
- action functions for data/effect work;
- `:choice` states for immediate routing.

If ever needed:

A future escape hatch would need to be explicitly named as opaque and
tool-limiting. It should not be presented as normal parity.

### C2. v5 helper creator compatibility

XState concept:

v6 removes helper creators such as `assign`, `raise`, `sendTo`, `sendParent`,
`emit`, `log`, `cancel`, `spawnChild`, `stop`, `enqueueActions`, and guard
creators such as `and`, `or`, `not`, and `stateIn`.

re-frame2 decision:

Do not add Clojure versions of helpers XState is deleting.

Use this instead:

- actions return `{:data ... :fx ...}`;
- internal events use `:raise`;
- guards are named functions;
- cross-state questions use `:tags`, `:state`, or `:all-state`.

If ever needed:

Applications can define ordinary helper functions locally, but they should not
be framework grammar.

### C3. Imperative `enq`

XState concept:

XState v6 action/transition functions use an enqueue helper such as
`enq.raise`, `enq.sendTo`, or `enq.emit`.

re-frame2 decision:

Do not add an imperative enqueue object.

Use this instead:

```clojure
{:data updated-data
 :fx [[:http/get request]]
 :raise [[:machine/internal-event]]}
```

If ever needed:

A local helper can build return maps, but the public machine contract should
remain return-data, not command-object mutation.

### C4. JavaScript-style `setup()` registries

XState concept:

XState v6 simplifies `setup()` so it no longer registers actions, guards,
actors, or delays.

re-frame2 decision:

Do not introduce a setup-like registry layer.

Use this instead:

Keep machine-local maps such as `:guards` and `:actions`. Use normal Clojure
values and the existing image/frame registration model for application
behavior.

If ever needed:

Reusable registration bundles should be handled by image/frame composition, not
a machine-specific `setup` clone.

### C5. `interpret` / `Interpreter`

XState concept:

v6 removes the old `interpret` path in favor of `createActor`.

re-frame2 decision:

Do nothing. re-frame2 never had this API.

Use this instead:

Machines are event handlers. Start and communicate with them through the
ordinary re-frame frame/event lifecycle.

### C6. Actor trigger ergonomics

XState concept:

XState v6 adds `actor.trigger.EVENT(payload)` as shorthand for sending events.

re-frame2 decision:

Do not add `rf/machine-trigger` or a machine-specific event constructor as
framework API.

Use this instead:

```clojure
(rf/dispatch [:counter [:inc {:by 1}]])
```

If ever needed:

Tests or applications may define local helpers, but framework-level dispatch
should have one public spelling.

### C7. Separate emitted-events channel

XState concept:

XState v6 has `enq.emit` and `schemas.emitted`, separate from sending events to
actors.

re-frame2 decision:

Do not add a machine-core `:emit` channel.

Use this instead:

- `:fx` dispatch effects for application behavior;
- trace events for observability;
- managed-effect replies for async completion;
- `:on-done` / `:on-error` for child-machine completion.

If ever needed:

A future observability or domain-events design can define a channel with a real
consumer. It should not be smuggled into machine parity.

### C8. State-bound listener syntax

XState concept:

XState v6 adds `enq.listen` and `enq.subscribeTo` with automatic teardown.

re-frame2 decision:

Do not add machine-core `:listen` syntax.

Use this instead:

- views use `rf/subscribe`;
- flows derive and write declared facts;
- resources own async server-state lifecycles;
- state `:entry` can start a managed effect and `:exit` can stop/cancel it;
- a spawned child machine can own state-bound external work.

If ever needed:

Design it in resources, flows, or managed effects, where lifecycle-managed
listening already belongs.

### C9. Snapshot migration hooks

XState concept:

XState v6 leans into persisted snapshots and migration.

re-frame2 decision:

Do not add machine-level `:version` / `:migrate` hooks for v6 parity.

Use this instead:

Machine snapshots are runtime state. If an application persists machine-like
state across releases, the application owns migration at that boundary.

If ever needed:

A future persistence EP can define a migration contract. It should not be part
of machine core by default.

### C10. Canonical serialization and unserializable markers

XState concept:

XState v6 strengthens machine serialization/revival and marks values that cannot
round-trip as JSON.

re-frame2 decision:

Do not make canonical machine serialization or unserializable markers a v6
parity goal.

Use this instead:

The machine grammar remains normal Clojure data plus functions. Tools can read
the declarative parts directly.

If ever needed:

A tools-focused EP can define an export projection for diagrams or AI. That
projection can decide how to report opaque values. It should not drive core
machine semantics.

### C11. Full XState JSON import/export compatibility

XState concept:

XState v6 makes data-first JSON machine configs more important.

re-frame2 decision:

Do not distort the native grammar to round-trip XState JSON.

Use this instead:

Keep re-frame2 syntax native: frame dispatch, event vectors, runtime-db
snapshots, and `:spawn`.

If ever needed:

A boundary tool can translate a subset:

```clojure
(rf.machine.xstate/export machine)
(rf.machine.xstate/import xstate-json {:guards guards
                                       :actions actions})
```

That tool should be allowed to say "not representable" rather than weakening the
native grammar.

### C12. Reusable state config wrappers

XState concept:

XState v6 has `createStateConfig(...)` for standalone typed state configs.

re-frame2 decision:

Do not add an `rf.machine/state-config` wrapper.

Use this instead:

```clojure
(defn loading-state [{:keys [success failed]}]
  {:entry :start
   :timeout "10s"
   :on-timeout {:target failed}
   :on {:success {:target success}
        :failure {:target failed}}})

{:states
 {:loading (loading-state {:success :loaded
                           :failed :failed})}}
```

If ever needed:

Tooling can later attach metadata to ordinary Clojure values. A wrapper should
not be added just because TypeScript benefits from one.

### C13. Generic async operation timeout as machine parity

XState concept:

XState v6 includes async-logic timeout, not only state/invoke timeout.

re-frame2 decision:

Do not include generic async operation timeout in EP-0029.

Use this instead:

Use A4 for state/spawn timeout. If HTTP/resources/managed effects need
request-level timeout, define it in those APIs.

If ever needed:

It might look like this, but it belongs outside machine parity:

```clojure
{:fx [[:http/get {:url "/api/user"
                  :timeout "5s"
                  :on-timeout [:user/load-timeout]}]]}
```

### C14. Public `:invoke` alias for `:spawn`

XState concept:

XState uses `invoke` for state-bound actors.

re-frame2 decision:

Keep `:spawn` / `:spawn-all` as the public spelling.

Use this instead:

Use `:spawn` for a state-bound child machine. It names the re-frame2 model:
state-bound child machines in runtime-db, not generic XState actor logic.

If ever needed:

An XState import/export boundary may translate `invoke` to `:spawn`, but the
native grammar should not carry both names.

### C15. JavaScript Standard Schema as a core concept

XState concept:

XState v6 schemas can use Standard Schema-compatible JavaScript libraries.

re-frame2 decision:

Do not import that brand or dependency into core.

Use this instead:

Keep schema values abstract and validation optional. A Malli adapter may exist,
but Malli must not be mandatory.

### C16. Full XState actor object semantics

XState concept:

XState v6 centers actors around `createActor`, concrete `Actor`, actor refs,
mailboxes, trigger helpers, actor logic, and subscriptions.

re-frame2 decision:

Do not make core machines require actor objects.

Use this instead:

Use the frame, event cascade, runtime-db snapshots, effects, and managed-effect
reply envelopes.

If ever needed:

Interop adapters can wrap re-frame2 machines for an external actor-like API, but
the native model should stay re-frame-native.

### C17. Alpha-churn exactness

XState concept:

XState v6 is still alpha. Public Stately docs are still v5.

re-frame2 decision:

Do not chase every alpha patch as normative.

Use this instead:

Adopt stable design direction, write tests for semantics re-frame2 actually
chooses, and keep divergence notes explicit.

## Docs and spec impact

The implementation updated:

- `spec/005-StateMachines.md` for the accepted grammar and semantics;
- machine implementation docs/docstrings for timeout, choice, internal-events,
  schemas, and spawn-order behavior;
- `docs/guide/concepts/machines.md` for the XState comparison;
- conformance fixtures for choice states, internal events, timeout behavior,
  spawn ordering, and completion payload schema behavior where applicable.

The guide should teach three ideas:

1. re-frame2 follows XState v6's simpler direction where it improves machines.
2. re-frame2 keeps transition topology declarative.
3. re-frame2 uses frame dispatch/runtime-db semantics instead of XState actor
   objects.

## Rationale

The embrace/maybe/ignore split keeps parity honest. The useful v6 changes are
not the JavaScript shapes. They are the concepts that make machines clearer:
broader schemas, explicit timeouts, choice states, private internal events, and
correct parent/child ordering.

Function-valued transitions are the important rejected temptation. They look
like close v6 parity, but they move the graph into code. That is the wrong
trade-off for re-frame2. re-frame2's advantage is that a machine can be read as
data.

The `:schemas` decision takes the useful v6 shape without importing a heavy
dependency. A machine may declare schema facts. Whether those facts are
validated, and by which adapter, is separate.

Final output stays event-shaped because re-frame2 machines are event handlers.
The child final state selects a value from `:data`; the parent receives that
value as the completion payload. A persistent `snapshot.output` field is not
needed.

## Backwards Compatibility

re-frame2 is pre-alpha, so accepted changes should be clean breaks rather than
compatibility shims.

Potential breaking or clarifying changes:

- `:data-schema` is retired; the machine data schema is declared at `[:schemas :data]` (clean break, no shorthand) — see Retirement And Removal (rf2-f9nvu9);
- invalid `:timeout` without `:on-timeout` should fail loudly;
- invalid choice-state declarations should fail loudly;
- `:internal-events` should reject external dispatch of private events;
- docs should stop teaching XState v5 helper terminology as the parity target;
- no function-valued transition form should be added as an implied migration
  target.

Malli must not become mandatory. If an implementation currently assumes Malli
for machine data validation, the v6 parity work should separate the schema
declaration grammar from the optional validator adapter.

## Retirement And Removal

The v6 retarget removes more than it adds. These removals must land with the
implementation, and each is tracked by a bead.

1. **Retire machine `:data-schema` → `[:schemas :data]` (surgical).** Remove the
   EP-0005 machine-level `:data-schema` key and its handling; the machine
   data-context schema declaration moves under `[:schemas :data]`. **Be careful:**
   the `:data-schema` spelling is reused by unrelated subsystems (resources /
   classification / SSR); retire ONLY the `reg-machine` key, not those. Surfaces:
   `implementation/machines` (registration, classification, tooling), the machine
   schema tests, `spec/005-StateMachines.md`, machines-viz, the `reg-machine`
   skill, examples, and the EP-0005 cross-reference. Tracked: **rf2-f9nvu9**.

2. **Retire XState v5 parity terminology.** Remove "v5 is the live parity target"
   wording and v5 helper-creator terminology, and reframe the machine docs to the
   v6 direction with explicit divergences (A1). Surfaces:
   `spec/005-StateMachines.md`, `docs/guide/concepts/machines.md`, the
   `reg-machine` skill, the Xray Machine-Inspector spec, machines-viz, and the
   EP-0005 framing note. Tracked: **rf2-ntg9z1**.

3. **Decouple mandatory Malli.** Machine core must not require Malli or JavaScript
   Standard Schema (Non-Goals). Machine source references Malli today; audit
   whether machine-data validation hard-requires it and, if so, separate the
   `:schemas` declaration grammar from an optional validator adapter. Tracked:
   **rf2-49zxkc**.

**Vocabulary landmine (do not trip).** `:actor/spawn` is a *retired* conformance
capability id and must not be revived; any spawn/invoke capability-id work targets
`:actor/declarative-spawn`, never `:actor/spawn`. (The separate `:actor/invoke` →
declarative-spawn capability rename is an EP-0007 nit tracked as rf2-9z4mle.) C14
already keeps `:spawn` as the sole public spelling, so this EP adds no alias — but
the retirement sweep must not re-open the retired id.

## Bead Plan / Reference Implementation

This EP was not implemented as one large bead. It decomposed into ordered
waves plus two standing review beads. Each wave was gated on EP-0029 acceptance and
landed as focused PRs (running the touched-artefact slice gates locally; CI ran
the full matrix).

### Wave 1 — Docs alignment (rf2-ntg9z1)

- Rewrite `docs/guide/concepts/machines.md` so the XState comparison tracks the
  v6-alpha direction, not v5; keep a short, explicit "where re-frame2 diverges"
  list (function-valued transitions rejected, frame/runtime-db instead of actor
  objects, event-shaped completion).
- Update `spec/005-StateMachines.md`, the `reg-machine` skill, the Xray
  Machine-Inspector spec, and machines-viz to stop teaching v5-helper terminology
  as the parity target.
- Add the EP-0005 framing note that v5 parity is superseded by EP-0029's v6
  direction (EP-0005's `:data` validation idea survives; its v5 framing does not).
- No machine behaviour changes in this wave — it is wording + divergence records.

### Wave 2 — Schema grammar + `:data-schema` retirement (rf2-f9nvu9)

- Add the machine-level `:schemas` map (`:data`, `:events`, `:output`, `:tags`,
  `:meta`) as a *declaration* surface; `<schema>` values stay abstract.
- Retire the EP-0005 `:data-schema` key (surgical — see Retirement And Removal):
  move the machine data-context schema to `[:schemas :data]`; update the machine
  registration parser, machine classification, machines-viz, tests, spec, the
  `reg-machine` skill, and examples; leave the unrelated resources / classification
  / SSR `:data-schema` spellings untouched.
- Decouple the optional validator (rf2-49zxkc): separate the `:schemas` declaration
  from any validator adapter so machine core requires neither Malli nor JS Standard
  Schema. A Malli adapter may interpret Malli values; absence of an adapter is
  legal.
- Connect `[:schemas :output]` to the completion-event payload (A8), not a
  persistent snapshot field.
- Fail loud on unknown `:schemas` sub-keys, and on `[:schemas :input]` unless/until
  state input (B1, "For Later Consideration") is accepted.

### Wave 3 — Core machine grammar (timeout / choice / internal-events)

- State-level `:timeout` + `:on-timeout` and spawn-level timeout (A4): `:timeout`
  requires `:on-timeout`; leaving the state cancels the state timeout; child
  completion cancels the spawn timeout; `:after` and `:timeout` coexist. Decide the
  duration grammar (Open Issue 3: `"10ms"`/`"5s"` only, vs also ISO `"PT2M"`).
- `:type :choice` states (A5): a choice state must declare `:choice` and must not
  also declare `:entry`/`:exit`/`:on`/`:after`/`:timeout`/`:spawn`; `:choice`
  reuses normal candidate semantics.
- `:internal-events` (A6): enforce the public/private split at the machine dispatch
  boundary — an internal `:raise` may produce them; external dispatch of a private
  event is rejected; internal raised events still run normal transition selection.
- Focused conformance fixtures for each, plus the fail-loud cases listed in
  Backwards Compatibility.

### Wave 4 — Spawn correctness (A7)

- Spec text + tests for: `:spawn :data` seeing parent `:data` *after* the
  transition action runs; child startup/init failure routing to the parent's
  declared `:on-error` spawn path; spawn-timeout cancellation on child completion;
  and restored parent/child snapshots restarting active children consistently.
- This is correctness, not ornament, and is the wave most likely to surface latent
  ordering bugs — it carries the heaviest test budget.

### Wave 5 — Completion output (A8)

- Clarify `:output-key` as the final state's `:data` selector and the source of the
  parent `:on-done` completion-event payload; confirm no long-lived `:output`
  snapshot slot is added; wire `[:schemas :output]` to that payload.

### Wave 6 — "For Later Consideration" gate

- Do NOT implement state input (B1) or durable workflow steps (B2). File separate
  EPs/beads only if a concrete real machine forces the question and the operator
  approves (Open Issues 4 and 5). The default disposition is "probably not".

### Standing review beads

Every PR associated with EP-0029 gets two independent review passes before it is
considered done:

- **Correctness** — semantics match the ruled grammar, no regressions, fail-loud
  where specified. Tracked: **rf2-e2c04t**.
- **Completeness** — all in-scope Group A items and ruled retirements are covered;
  spec + guide + skills + conformance + machines-viz/Xray are updated together; no
  half-implemented surfaces; divergence notes present. Tracked: **rf2-wz4pmv**.

Guide-impact assessment:

- `docs/guide/concepts/machines.md` changed on acceptance (Wave 1).
- Tutorials comparing `context`, `assign`, `setup`, actor sending, or output
  snapshots to re-frame2 were revised.
- Choice-state and timeout examples landed with those features (Wave 3).

## Open Issues

1. **Schema spelling — RESOLVED** (see Resolved Decisions): `:data-schema` is
   retired; the machine data schema is `[:schemas :data]` (clean pre-alpha break,
   no shorthand).
2. **Validator adapter shape:** how should optional schema validators be plugged
   in without making Malli mandatory?
3. **Duration grammar:** should v1 accept only `"10ms"` / `"5s"` style strings,
   or also ISO durations such as `"PT2M"`?
4. **State input:** does a concrete real machine justify adding state-entry
   input later?
5. **Durable workflow steps:** does re-frame2 need workflow-step persistence in
   managed effects, or is that outside the SPA-focused scope?

## Resolved Decisions

- **Schema spelling (Open Issue 1) — ruled.** `:data-schema` is retired by a clean
  pre-alpha break. The machine data-context schema is declared at
  `[:schemas :data]`; there is no `:data-schema` shorthand. The retirement is
  **surgical**: the `:data-schema` spelling is reused by unrelated subsystems
  (resources / classification / SSR), and those MUST NOT be touched. See Retirement
  And Removal. Tracked: **rf2-f9nvu9**.

## Recommendation

Accept EP-0029 as a v6-parity correction, not a cloning project.

Group A should become the implementation roadmap. The "For Later Consideration -
but Probably not" items should stay out of core unless real examples force them.
Group C should be explicitly rejected or delegated to existing re-frame2
mechanisms.

The resulting model is smaller and clearer: declarative topology, plain
guard/action functions, optional schema facts, explicit timeouts, choice nodes,
private internal events, and re-frame-native parent/child completion.
