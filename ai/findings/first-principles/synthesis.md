# First-Principles Synthesis

Date: 2026-06-10

Inputs:

- `ai/findings/first-principles/codex2.md`
- `ai/findings/first-principles/fable.md`

Scope: the re-frame2 CLJS library architecture and the spec freedom needed to
improve it. This synthesis deliberately excludes tools, skills, and examples
except where they illuminate the library contract.

## Executive View

Both analyses converge on the same first-principles judgment: re-frame2's core
model is sound. A frame is a Moore-machine-like fold over causal inputs; state
is ordinary data; effects are data at the boundary; derived values form a graph;
and the app/runtime partition gives the framework a coherent commit boundary.
Those ideas should be protected.

The remaining high-impact smells are not in the fold. They are in the ambient
things folded with it:

- the program is still assembled by process-wide registration side effects;
- time and other world inputs can still leak into durable state from ambient
  calls;
- async effects use several callback vocabularies for one continuation idea;
- paths, params, scopes, and route patterns share an algebra that is not named;
- subscriptions, flows, resources, and machine selectors are taught as separate
  mechanisms rather than points in one derivation/process design space.

The shared principle is simple: **make the remaining ambient concepts into
values with laws**. The public API does not need category-theory vocabulary, but
the design should behave as if its pieces compose algebraically.

## Concepts To Carry Forward

### 1. App/Program As A Value

The strongest long-term idea is that the application program should be a value,
not only a side effect of namespace load order. Today the registrar, adapter,
late-bind hooks, and several capability tables are process-global mutable
state. That is pragmatic for re-frame-v1 migration, but it is the main obstacle
to hermetic tests, multiple products in one browser process, white-label
variants, static contract graphs, and precise hot-reload invalidation.

The useful direction is an app value installed into a runtime realm. Existing
`reg-*` calls can remain as default-realm sugar, but the spec should stop
treating process-global registration as the only architectural shape.

### 2. World Inputs Must Be Causal Inputs

Replay determinism only holds literally if time, randomness, UUIDs, and similar
host facts enter durable state as data on the causal token, not through ambient
reads during the transition. Diagnostic timestamps and host scheduling may still
read the clock; durable frame-state may not.

The concrete payoff is high: resource freshness timestamps, work-ledger rows,
epoch metadata, replay, restore, and tests all become computations on explicit
input data.

### 3. Async Reply Is One Continuation Shape

Managed HTTP, route loaders, resources, timers, and machines all need the same
concept: "when this host work finishes, dispatch a causal reply with the
outcome, correlation, and stale-suppression context". Repeating that shape under
`:on-success`, `:on-failure`, timer callbacks, route settle events, and resource
reply payloads makes cancellation, dedupe, tracing, stale suppression, and work
ledger integration more complex than they need to be.

The useful direction is a uniform reply envelope. Existing callback styles can
lower onto it, so the change can be internal-first and compatibility preserving.

### 4. Path/Identity Algebra

Many surfaces already depend on path-like structure: app-db paths, runtime-db
subtrees, flow inputs and outputs, schema paths, redaction paths, projection
policies, route params, resource identity, and work-ledger keys. Each subsystem
currently repeats pieces of the same rules: root path, missing versus nil,
canonical map ordering, path overlap, and round trips.

The useful direction is a small `:rf/path` and canonical-form vocabulary with
laws. This does not require importing a heavy optics library. It requires one
place to say what a path means and which round-trip properties must hold.

### 5. Derivation/Process Algebra

Subscriptions, runtime subscriptions, flows, resources, and selected machine
state are one family: declared inputs, an output, a storage class, an evaluation
policy, and a lifecycle. The existing APIs can remain. The improvement is to
name the common model so humans and tools can answer "where does this fact come
from?" across the whole system.

This model also creates the right home for optional future scaling work such as
delta contracts. Deltas should be a law-checked optimization tier, not a new
default mental model.

### 6. Host-Transient Runtime State Belongs Under EP-0006

HTTP in-flight handles, routing counters, scroll caches, machine timers, spawn
order, and flow last-input caches are not app-db and often not durable
runtime-db. They are host-transient runtime subsystems. The idea is important,
but it should not become a separate first-principles EP because EP-0006 already
owns the runtime-subsystem contract. The action is to ensure EP-0006's contract
grades durable and host-transient state explicitly.

## Ideas Dropped Or Deferred

- **A broad claim that ordinary flows are one event stale.** The current CLJS
  implementation already runs ordinary flow propagation inside the event
  transition path before install. There is still a specific late-registration
  caveat around flow registration by effect, but that is a narrower Spec 013
  issue, not a first-principles reason for a new EP.
- **Interceptor redesign.** The implementation has already moved away from the
  old queue/stack model. A spec reconciliation bead may be useful, but this is
  not a new architecture decision.
- **Machine snapshot product split.** This is a valid algebraic cleanup, but it
  should ride the next deliberate snapshot-version bump rather than create a
  standalone proposal now.
- **Standalone differential-dataflow EP.** Optional delta contracts are worth
  mentioning under the derivation algebra, but whole-value derivation remains
  the correct default for a productive SPA library.
- **Unrestricted third-party runtime subsystem API.** The internal contract
  should become coherent before the framework promises a public extension
  surface.

## Proposed EP Map

| EP | Action |
|---|---|
| EP-0010 | Define causal world inputs: time/random/host facts that affect durable state must arrive on envelopes or coeffects. |
| EP-0011 | Define a uniform async reply envelope and lower existing async callback vocabularies onto it. |
| EP-0012 | Define `:rf/path`, canonical forms, and routing prism/path round-trip laws. |
| EP-0013 | Define app values and runtime realms as the long-term shape behind registration, adapters, and capabilities. |
| EP-0014 | Define the derivation/process algebra behind subscriptions, flows, resources, and machine selectors. |

This ordering is intentional. EP-0010 and EP-0012 are small, law-like
constraints. EP-0011 is the natural companion to the work-ledger/resource
campaign. EP-0013 is the largest architectural shift and should remain
proposal-stage until the project has validated the migration strategy. EP-0014
names the conceptual bridge that keeps the user-facing model coherent while
existing APIs continue to work.
