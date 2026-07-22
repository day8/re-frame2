# Spec 004 — Views — the common Freehand contract

> Status: Drafting. **v1-required.** This Spec owns the **common** view contract of
> **Freehand**, re-frame2's one re-frame-native view substrate: how a view is
> **declared**, how it is **authored and called**, what its **semantics** mean, and
> where the **host boundary** sits. Freehand has two execution modes over one
> semantic model — the **interpreted** mode is the paved path, and the **compiled**
> mode is the hot tier selected by `{:compiled true}` on the same declaration.
> Every clause here holds in both modes unless the clause says otherwise. The
> finite, versioned `:re-frame.freehand/v1` compiled grammar — its analyzer, its two
> emitters, and the static evidence they produce — is owned by
> [004D-Freehand-Compiled-Grammar](004D-Freehand-Compiled-Grammar.md), referenced
> here and never restated. The public namespace is `re-frame.freehand`,
> conventionally aliased `v`.
>
> **This document is a skeleton.**
> [EP-0036](../docs/EP/EP-0036-the-freehand-view-substrate-programme.md) cut the
> ownership so that no two Specs claim one surface, and it delivers the substrate in
> vertical slices rather than as one prose waterfall. Each semantic heading below
> carries a **Lands in** marker naming the slice that authors it. A marked heading
> with no body is a **declared vacancy**, not an undocumented contract: until that
> slice lands, the ratified target is described by the Freehand design record
> (`docs/design/freehand/`) and the donor-era shipped behaviour continues to be
> described by the Spec that shipped it. No slice may author its surface anywhere
> but under its own heading here, and none may leave two owners standing.

## Abstract

A **view** is a declared, vector-called boundary: `v/defview` binds a var to a
non-`IFn` **descriptor**, and a call site writes `[the-view props]`. Rendering that
boundary is a pure computation from the ambient frame and one props map to a
semantic tree. The pattern-level commitments:

1. **One declaration, two modes.** The same `v/defview` form declares an interpreted
   view and a compiled one; `{:compiled true}` changes the lowering, not the public
   view model. Promotion changes one declaration and no call site.
2. **Passive render.** A render may run, restart, or be abandoned. It reads values
   and builds a *candidate* bundle; it MUST NOT dispatch, acquire ownership, mutate
   committed state, publish evidence, or create or seed frames. Per-mount work
   belongs to the frame's `:initial-events` or to an ordinary re-frame event, never
   to a render body.
3. **Atomic selection.** Only a render selected for commit publishes its frame
   incarnation, dependencies, event sites, and evidence — as one bundle. An
   abandoned or stale render publishes none of it.
4. **One reactive state system.** Application and interaction state is re-frame
   data. Host objects — DOM nodes, React elements, third-party instances — stay
   private behind qualified host boundaries.
5. **Intent is data.** One user action yields exactly one semantic event vector or
   `nil`. Mount, unmount, and host lifecycle are tool facts, not domain events.
6. **Frame-explicit, carried never guessed.** A view scopes live frames; it never
   creates them. Frames are created at host preflight (per [002](002-Frames.md)).

## Governing laws

These are the seven laws ratified by
[EP-0036 §Governing laws](../docs/EP/EP-0036-the-freehand-view-substrate-programme.md#governing-laws).
They bind every section of this Spec, every clause of
[004D](004D-Freehand-Compiled-Grammar.md), and every Freehand slice. A slice that
cannot satisfy one of them amends the law explicitly; it does not ship a second
answer beside it.

1. **One declaration.** Every mounted boundary is a vector-called `v/defview`; plain
   helpers are direct-called functions and never vector heads.
2. **One semantic model.** Props, children, keys, events, frames, controlled
   scheduling, structural output, errors, and evidence mean the same in both modes.
3. **Passive render and atomic selection.** A speculative render owns nothing; the
   selected commit publishes its frame, dependencies, events, and evidence as one
   bundle.
4. **One reactive state system.** Application and interaction state is re-frame
   data. Host objects remain private at qualified host boundaries.
5. **Intent is data.** One user action yields one semantic event vector or `nil`.
   Mount, unmount, and host lifecycle are tool facts, not domain events.
6. **Compilation is explicit.** No automatic promotion, second compiler, or hidden
   interpreted walker inside compiled markup.
7. **Proof is honest.** Separate React and JVM emitters may share normalizers but
   prove parity through common conformance values and fixtures.

The detailed rulings behind these laws are the Freehand decision register
(`docs/design/freehand/decisions/`, D001–D021). They are all ruled; a slice cites
them, it does not reopen them.

## What this Spec owns, and what it defers

Exactly one Spec owns each surface. This table is the map; the owning document is
the contract.

| Surface | Owner |
|---|---|
| Declaration, authoring, call convention, props/children/keys, common semantics, the host boundary | **this Spec** |
| The finite `:re-frame.freehand/v1` compiled grammar, its analyzer, both emitters, static manifests and elision, the compiled checker | [004D-Freehand-Compiled-Grammar](004D-Freehand-Compiled-Grammar.md) |
| The semantic UI tree ABI and the one DOM conversion table both emitters consume | [004B-UI-Tree-and-Conversion](004B-UI-Tree-and-Conversion.md) |
| Root identity, the Root Descriptor, mount, hydration, and teardown | [004C-Roots-and-Mount](004C-Roots-and-Mount.md) |
| Frame creation, identity, lifecycle, and preflight | [002-Frames](002-Frames.md) |
| The observation-port contract the shared reactor consumes | [006-ReactiveSubstrate](006-ReactiveSubstrate.md) |
| Structural and mounted testing, the host/mode matrix, the executable cross-mode conformance contract | [008-Testing](008-Testing.md) |
| Diagnostic ids, evidence and retention fields, lifecycle facts, error egress | [009-Instrumentation](009-Instrumentation.md) |
| SSR consumption, hydration, fallback, and server-error projection | [011-SSR](011-SSR.md) |
| Route-link href and click semantics over the late-bound routing seam | [012-Routing](012-Routing.md) |
| Reserved namespaces, id derivation, and packaging conventions | [Conventions](Conventions.md) |
| The published var roster and its per-var reference rows | [API](API.md) |

## Declaration and authoring

### The descriptor and `v/defview`

**Lands in:** F1 — paved-path spine. Carries the declaration form, the non-`IFn`
descriptor value and its public inspection projection, the direct-call error, and
the helper-versus-view call convention.

### Props, children, and `:key`

**Lands in:** F1 — paved-path spine. Carries the one-props-map contract, the
reserved trailing `:children` vector, sibling identity via `:key`, the
children-policy declaration, and the props-schema seam.

### Vector-head classification

**Lands in:** F1 — paved-path spine. Carries the total classification of a vector
head — declared view, element keyword, declared host descriptor — and the didactic
error for anything else.

### Identity, hot reload, and remount

**Lands in:** F1 — paved-path spine. Carries mounted occurrence identity, the
internal revision/generation vocabulary, and the compatible-shell versus clean-remount
hot-reload contract.

### Selecting the compiled mode

**Lands in:** F3 — compiled absorption. Carries the `{:compiled true}` selection, the
checker-first workflow that precedes it, and the interpreted↔compiled crossing rules;
the grammar itself is [004D](004D-Freehand-Compiled-Grammar.md).

## Common semantics

### Passive render and atomic selection

**Lands in:** F2 — reactive intent. Carries the candidate bundle, the selection and
publication rule, acquire-before-release, and the fate of an abandoned render.

### The ambient frame

**Lands in:** F2 — reactive intent. Carries frame observation and rebinding at a view
boundary, and the loud failure when no live frame is in scope.

### Reactive reads — `v/sub`

**Lands in:** F2 — reactive intent. Carries the render-only law, same-render-thread
capture, stabilized return values, invalidation ownership, and the non-reactive
one-shot alternative.

### Event intent and the payload materializer

**Lands in:** F2 — reactive intent. Carries the event-vector form, listener options,
the reserved scalar projections and the one pure materializer, and the exactly-one-
event-or-`nil` rule.

### Callback roles and identity

**Lands in:** F2 — reactive intent. Carries the closed roster of callback forms, each
form's phase and identity contract, and per-site committed proxy ownership.

### Controlled inputs

**Lands in:** F2 — reactive intent. Carries the controlled-node predicate, the door
event props, the synchronous same-tick scheduling guarantee, and the forwarding rules
that preserve it.

### Semantic controllers

**Lands in:** F4 — data and host lifecycle. Carries caller-supplied semantic
addressing, generation/reset vocabulary, ownership rules, and the boundary between
framework laws and component-library widget vocabulary.

### Presence

**Lands in:** F4 — data and host lifecycle. Carries the keyed enter/exit plan, the
phase set and attribute overrides, the mandatory terminal bound, and the structural
projection.

### Error boundaries and error egress

**Lands in:** F4 — data and host lifecycle. Carries containment scope, reset, the
fallback contract, the once-per-generation safe summary, and the private frame error
egress path.

### Diagnostics and evidence

**Lands in:** F4 — data and host lifecycle. Carries the occurrence-keyed evidence
model and the scope/basis/completeness/loss statement; the ids and retention axis are
[009](009-Instrumentation.md).

## Composition

### Children, compound children, and parameterized content

**Lands in:** F5 — composition and integration. Carries the default child region,
compound child views, and the pure parameterized-render pair.

### Props forwarding

**Lands in:** F5 — composition and integration. Carries the safe-forwarding form, its
denied-prop set and class composition, and the visible open-props escape at a foreign
boundary.

### Theming and semantic parts

**Lands in:** F5 — composition and integration. Carries the bounded per-instance part
override and the styling/structural plane split.

### Framework-supplied views

**Lands in:** F5 — composition and integration. Carries the rule that framework views
are ordinary descriptors, and the route-link view over the [012](012-Routing.md) seam.

## The host boundary

### Qualified host leaves

**Lands in:** F4 — data and host lifecycle. Carries the value-in/callback-out foreign
component boundary and its structural/SSR policy.

### Registered behaviors and commands

**Lands in:** F4 — data and host lifecycle. Carries the connect/update/disconnect
protocol, the closed timing set, node opacity, the bounded command map, and the
structural marker.

### The DOM top layer

**Lands in:** F4 — data and host lifecycle. Carries the closed qualified
desired-state properties, commit-time host reconciliation, and the structural
projection.

### The outward React bridge

**Lands in:** F5 — composition and integration. Carries descriptor-only acceptance,
prop mapping, caching and frame selection, and the structural-host failure mode.

### Structural rendering, roots, and SSR

**Lands in:** F5 — composition and integration. Carries what the structural host
retains at a view boundary; root identity and mount are
[004C](004C-Roots-and-Mount.md) and server rendering is [011](011-SSR.md).

## Normative absences

**Lands in:** F1–F6, incrementally; consolidated at F6 — proof and retirement. Each
slice records the donor forms its surface retires and the Freehand replacement for
each, so the absence is a stated contract rather than an omission.

## Resolved decisions

- **The product is Freehand.** One re-frame-native substrate published through
  `re-frame.freehand` (alias `v`), with no second public door. The interpreted mode
  is the paved path and the compiled mode is required.
- **Compilation is manual and evidence-guided.** There is no automatic promotion, no
  second compiler, and no hidden interpreted fallback inside compiled markup.
- **re-frame is the only reactive application-state system.** There is no
  view-local application-state tier, no public renderer-derived state handle, and no
  neutral hook/ref/effect/portal surface; React-owned protocols live behind explicit
  host boundaries.
- **Contract ownership is a migration, not a new family.** Freehand extends the
  existing canonical Specs; there is no `spec/0XX-Freehand` family, and the compiled
  grammar's home is fixed at [004D](004D-Freehand-Compiled-Grammar.md).
- **The donor is deleted at a gate, not a date.** `re-frame.ui` is donor-only; its
  standalone artifact is removed when internal conformance, the pilots, and consumer
  migration are complete.

The full rulings D001–D021 and their rationale are the Freehand decision register
(`docs/design/freehand/decisions/`); the programme topology, migration map, and
release gates are
[EP-0036](../docs/EP/EP-0036-the-freehand-view-substrate-programme.md).

## Cross-references

- [004D-Freehand-Compiled-Grammar](004D-Freehand-Compiled-Grammar.md) — the compiled
  tier: the finite grammar, the analyzer, both emitters, manifests and elision.
- [004B-UI-Tree-and-Conversion](004B-UI-Tree-and-Conversion.md) — the semantic tree
  ABI and the DOM conversion table.
- [004C-Roots-and-Mount](004C-Roots-and-Mount.md) — root identity, the Root
  Descriptor, mount, hydration, teardown.
- [002-Frames](002-Frames.md) — frame creation, identity, and preflight.
- [006-ReactiveSubstrate](006-ReactiveSubstrate.md) — the observation port the shared
  reactor consumes.
- [008-Testing](008-Testing.md) — structural and mounted testing, the host/mode
  matrix, cross-mode conformance.
- [009-Instrumentation](009-Instrumentation.md) — diagnostic ids, evidence retention,
  lifecycle facts, error egress.
- [011-SSR](011-SSR.md) — server rendering, hydration, and server-error projection.
- [012-Routing](012-Routing.md) — href and click semantics behind the route-link view.
- [Ownership](Ownership.md) — the corpus-wide surface-to-owner map.
- [EP-0036](../docs/EP/EP-0036-the-freehand-view-substrate-programme.md) — the
  ratified programme: topology, governing laws, migration, slices, and gates.
