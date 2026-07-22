# D003 — Reusable-control state model

Status: **Ruled**
Ruling: **Use semantic controllers over shared re-frame infrastructure;
generic storage verbs are only for protocol-free state.**

Horizon: **Immediate** — required before the common state contract and component
pilots are implemented

## Decision

When a reusable control genuinely owns interaction state, where does that state
live, how is it changed, and how much generic state machinery does Freehand expose?

The ordinary case is already settled: a reusable view is controlled and props-only.
It receives values and emits event intent.

```clojure
[checkbox {:checked subscribed?
           :on-change [:account/set-subscribed? account-id ::v/checked]}]
```

The open case is a control whose protocol spans several browser interactions. A
commit-on-blur field needs a draft and an editing session. A dropdown may need an
open flag and active option. A typeahead may need a draft query, a settled query,
and selection state. Requiring every caller to reproduce those state machines is
poor library ergonomics; hiding them in React hooks, ratoms, or render closures
would make them invisible to re-frame.

This decision is about the general model. The exact buffered-field protocol is
[D016](D016-buffered-and-revision-controls.md), and the namespace in which control
events live is [D017](D017-framework-control-and-policy-vocabulary.md).

## Settled constraints

These are not options in this decision:

1. **Absorption is settled.** `re-frame.ui` is donor code for Freehand's compiled
   tier and is deleted at the conformance-and-pilots gate. Its `local` form and
   placement machinery do not survive.
2. **There is one application-state system.** Application and interaction facts
   belong to re-frame state and re-frame transitions. Freehand does not add a
   second atom, hook, or component-slot state system.
3. **Controlled-first is the default.** Stateful controller machinery must be
   exceptional and earned by a reusable protocol, not the customary way to avoid
   passing props.
4. **Host facts are different.** DOM nodes, focus calls, observers, geometry,
   portal containers, and third-party instances stay behind behaviors or React
   wrappers. They are not placed in app-db.
5. **Render does not own causal lifetime.** Mount and unmount produce tooling
   facts, not application events. A route, form, resource, machine, or explicit
   control transition owns initialization and retirement.
6. **The committed-state law applies.** A delayed commit or cancel is decided by
   an event handler against the exact committed frame, never by a callback guard
   captured during render.
7. **Both execution modes have the same contract.** A controller cannot depend on
   whether its view is interpreted or compiled.
8. **State must remain data-oriented.** It must be equality-comparable,
   structurally testable, visible in traces and snapshots, and understandable by
   tools and AI without mounting React.

## What the model must support

At minimum, the model must make these cases pleasant and correct:

| Case | State owned by the control | Important lifetime rule |
|---|---|---|
| buffered field | edit phase, draft, reset generation | Escape followed by a racing blur must not commit |
| revision field | draft plus caller revision | reasserting an equal model value can still reject a draft |
| disclosure | open/closed | state may outlive a temporary render absence when its owner wants that |
| dropdown | open state and active option | delayed selection consults committed options/open state |
| typeahead | draft and settled query | debounce and stale results remain traceable policies |
| virtualized editor | draft for the semantic row/cell | windowing unmount must not silently destroy the draft |

The model must also tolerate fifty instances without collisions, HMR, keyed
reordering, alternate frames, structural JVM tests, and deterministic cleanup.

## Options

### Option A — Controlled-only; callers own every state machine

Every component remains props-only. The caller supplies the draft, open state,
active option, and every transition event.

```clojure
[buffered-field {:value amount
                 :draft draft
                 :editing? editing?
                 :on-edit [:invoice/edit-amount invoice-id]
                 :on-commit [:invoice/commit-amount invoice-id]
                 :on-cancel [:invoice/cancel-amount invoice-id]}]
```

**Consequences**

- The substrate remains extremely small and state ownership is unambiguous.
- Application-specific forms often benefit from this explicitness.
- Reusable controls cease to encapsulate their interaction protocol. Callers must
  understand and reproduce state machines that should have been library code.
- Composition becomes prop and event plumbing, particularly for dropdowns and
  typeaheads.
- Different callers can implement subtly different stale-blur, revision, IME, and
  cleanup behavior.

This is a good default, but too weak as the only answer.

### Option B — Public generic instance state

Freehand exposes a public addressable map and storage-shaped operations, similar to
the Fable dossier's `[:rf/inst address]`, `put`, `merge`, `toggle`, and `clear`.

```clojure
(v/sub [:rf/inst address :open?])
[:rf.inst/toggle address :open?]
[:rf.inst/put address :draft ::v/value]
```

**Consequences**

- It is concise, uniform, time-travelling, and easy to test on the JVM.
- Small controls such as disclosures need almost no library infrastructure.
- Storage-shaped events say where data changed but often not why. A trace of
  `put :draft` or `toggle :open?` loses the control protocol's semantic action.
- A general map plus generic mutation verbs is likely to become `local` in app-db:
  convenient, pervasive, and hard to remove.
- The public state root, cleanup operations, collision rules, schemas, and
  migration policy become permanent substrate API.
- Dead records accumulate unless ownership and clearing discipline are designed.

This maximizes immediate convenience but creates the largest long-term surface.

### Option C — Addressed semantic controllers over shared re-frame infrastructure

Freehand supplies only the minimum infrastructure needed to store, inspect, and
fence address-scoped controller records. A component library owns each controller's
schema and semantic transitions. Generic `put`/`merge`/`toggle` operations are not
the taught public view API.

An illustrative record might be:

```clojure
{:re-frame.freehand/controllers
 {[:my.ui/buffered-field [:invoice 42 :amount]]
  {:generation 7
   :phase :editing
   :draft "12."}}}
```

The corresponding rendered intent remains semantic:

```clojure
[:my.ui.field/edited [:invoice 42 :amount] 7 "12."]
[:my.ui.field/committed [:invoice 42 :amount] 7
 [:invoice/amount-committed 42]]
```

The library's registered event handlers perform transitions against committed
state. The storage representation can be shared and toolable without turning raw
map mutation into the component authoring model.

**Consequences**

- Controller state remains in re-frame epochs, snapshots, schemas, and traces.
- A semantic event explains why the state moved and can atomically produce the
  caller's application effect.
- Library authors write a controller once; application programmers receive a
  value-in/intent-out component API.
- Freehand must define a small record-location, frame, evidence, retention, and
  generation contract. That is real infrastructure, even if it is not a broad DSL.
- Each genuinely stateful control family needs explicit transitions and tests.
- Per-keystroke controller events add epoch and subscription traffic; the browser
  and performance harness must price it.

### Option D — Hide the state in the host

A React wrapper uses hooks, or a DOM leaf remains uncontrolled and reports only a
final value.

**Consequences**

- It can minimize re-frame traffic and may be appropriate for a very hot foreign
  widget or an explicitly uncontrolled grid editor.
- Drafts disappear from snapshots, time travel, structural tests, and re-frame
  traces.
- HMR, remount, virtualization, SSR, and test behavior become host-specific.
- It recreates the visibility and stale-closure problems that deleting `local` is
  intended to remove.

This remains an explicit escape at a qualified wrapper, not the Freehand model.

## Recommendation

Adopt **Option C**, with Option A remaining the normal component contract and
Option D available only at an explicit host boundary.

Concretely:

1. Reusable views are props-only by default.
2. A library may define a **semantic controller** only when the interaction spans
   events and cannot be reasonably owned by the caller.
3. Controller state is ordinary, frame-scoped re-frame data keyed by controller
   kind and an explicit semantic address. D004 decides the exact address contract.
4. The library registers a state schema and semantic event handlers. Rendered
   events name `edited`, `committed`, `cancelled`, `opened`, `moved`, or another
   protocol action. A raw storage verb is acceptable only for protocol-free state:
   no phase, generation, delayed-event decision, or caller intent. Once any of
   those exists, a library-owned semantic event must consult committed state.
5. A transition may update controller state and dispatch the caller's intent as
   effects of one semantic event. A view-side handler still produces one event or
   `nil`.
6. State persists until a semantic transition or its causal owner clears it.
   Unmount never dispatches cleanup. Development tools report orphaned or old
   controller records so retention is visible rather than magical.
7. No controller API exposes refs, effects, hooks, DOM instances, or arbitrary
   mount callbacks.
8. Keep the initial implementation narrow: buffered/revision field first, then a
   dropdown and typeahead. Do not build a controller DSL before those pilots expose
   repeated mechanics.

This is deliberately not a generic component-state facility. It is a small bridge
that lets a reusable library own a semantic state machine while preserving
re-frame's single state, transition, testing, and explanation model.

## Consequences of the recommendation

**Benefits**

- `local` dies without forcing all reusable state back onto callers.
- State, intent, generation fencing, and cleanup are inspectable together.
- The same component and tests work in interpreted and compiled views.
- Tools can show controller kind, address, generation, state, last semantic cause,
  and retention owner.
- Library authors get encapsulation without hidden mutable slots.

**Costs and risks**

- Controller records can become silt. Explicit owner cleanup and orphan evidence
  are required.
- Per-keystroke app-db updates may be too expensive in dense editors. Measure; do
  not assume.
- A poorly designed registration API could become a second reducer framework.
  Start with ordinary re-frame registrations and extract only demonstrated common
  mechanics.
- Address mistakes are application-data bugs. D004 must establish collision and
  ownership diagnostics before this is safe.
- Too many semantic controller families would blur the boundary between Freehand
  and a component library. D017 decides vocabulary ownership.

## Implementation evidence

The recommendation should be accepted only after a thin prototype demonstrates:

- two independent buffered fields with no state collision;
- same-value rejection and stale-blur safety;
- state and causal events visible in normal re-frame tooling;
- structural tests that dispatch the rendered intent without mounting React;
- no mount/unmount application events;
- frame isolation and HMR generation fencing;
- an explicit owner cleanup path plus orphan reporting;
- measured input latency under unrelated dirty work; and
- no interpreted/compiled semantic difference.

## Dependencies and unlocks

- **Depends on:** the settled absorption ruling and one-state-system constraint.
- **Must be coordinated with:** [D004](D004-state-identity-and-addressing.md).
- **Unlocks:** the common state ABI, [D016](D016-buffered-and-revision-controls.md),
  dropdown/typeahead pilots, structural controller tooling, and the relevant
  conformance rows.
- **Does not decide:** exact event namespaces or whether any control family ships
  with the framework; see [D017](D017-framework-control-and-policy-vocabulary.md).

## Sources

- [Codex design](../codex-design.md) — “State ownership”, “Event law”,
  “Re-implementing re-com”, and “Absorption and retirement of `re-frame.ui`”.
- [Fable design](../fable-design.md) — §2.4 “Instance state under the
  one-state-system pin”, §4 “The gallery”, §7.1 “Standing wounds and tensions”,
  and §8 Q8.
