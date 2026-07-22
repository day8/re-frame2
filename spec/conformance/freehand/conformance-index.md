# Freehand Conformance Index

> **Type:** Reference
> One row per Freehand executable law. The id scheme, the allocation rule, the
> column contracts, the applicability grammar, and the status vocabulary are
> defined in [README.md](README.md) — read it before adding a row.

The index is the single roster of Freehand laws. It is empty at establishment
and grows one row at a time: each slice that lands a contract appends its own
rows to its own area section, in the same change as the spec paragraph each row
cites. Nothing here is normative — every row is an address into `spec/`, plus
the fixture that proves the paragraph it names.

Row shape, for reference — a template, not an allocation:

```
| `FH-AREA-NNN` | one line stating what is proven | [00X-Doc.md#anchor](../../00X-Doc.md#anchor) | common jvm browser | `spec/conformance/freehand/fixtures/fh-area-nnn.edn` | active |
```

## Areas

### FH-CALL — Calls

The declared boundary: descriptors, plain helpers, children, `:key`, occurrence
identity, hot reload, rejected declaration forms.

| Id | Law | Canonical paragraph | Applicability | Fixture | Status |
|---|---|---|---|---|---|
| `FH-CALL-001` | A declared view var holds a non-`IFn` descriptor value; it is not a function and not a map, so a direct call raises and can never silently succeed | [004-Views.md#the-descriptor-is-a-value-not-a-callable](../../004-Views.md#the-descriptor-is-a-value-not-a-callable) | common jvm browser | `spec/conformance/freehand/fixtures/fh-call-001.edn` | active |
| `FH-CALL-002` | Vector-head classification is total: a descriptor is an internal boundary, a keyword is a DOM/custom element, a declared host descriptor is a foreign boundary, and anything else raises naming those three legal forms | [004-Views.md#vector-head-classification](../../004-Views.md#vector-head-classification) | common jvm browser | `spec/conformance/freehand/fixtures/fh-call-002.edn` | active |
| `FH-CALL-003` | The inspection projection carries exactly the public descriptor ABI; the render body and the host mount/tree entries stay private, and an undeclared props schema is reported as absent rather than `:any` | [004-Views.md#the-inspection-projection](../../004-Views.md#the-inspection-projection) | common jvm browser | `spec/conformance/freehand/fixtures/fh-call-003.edn` | active |

### FH-PROPS — Props

One props map: reserved `:children`, stripped `:key`, equality and conversion,
optional schema semantics.

| Id | Law | Canonical paragraph | Applicability | Fixture | Status |
|---|---|---|---|---|---|
| `FH-PROPS-001` | An internal boundary call carries exactly one props map and no positional arguments; a missing or non-map props slot is rejected | [004-Views.md#props-children-and-key](../../004-Views.md#props-children-and-key) | common jvm browser | `spec/conformance/freehand/fixtures/fh-props-001.edn` | active |
| `FH-PROPS-002` | Trailing children arrive as the reserved `:children` vector — absent when there are none; a caller-authored `:children` is rejected and the declared children policy is enforced at the call | [004-Views.md#props-children-and-key](../../004-Views.md#props-children-and-key) | common jvm browser | `spec/conformance/freehand/fixtures/fh-props-002.edn` | active |
| `FH-PROPS-003` | `:key` selects sibling identity, is stripped before the props map reaches the view, and is outside props equality | [004-Views.md#props-children-and-key](../../004-Views.md#props-children-and-key) | common jvm browser | `spec/conformance/freehand/fixtures/fh-props-003.edn` | active |

### FH-EVENT — Events

Projection materializer, options and key-map grammar, site and proxy lifetime,
the atomic selected bundle, route-link href and click behaviour.

| Id | Law | Canonical paragraph | Applicability | Fixture | Status |
|---|---|---|---|---|---|

### FH-INPUT — Controlled input

The exact door predicate, the frame-scoped synchronous flush, the browser
contention matrix.

| Id | Law | Canonical paragraph | Applicability | Fixture | Status |
|---|---|---|---|---|---|

### FH-SUB — Subscriptions

Render-only reads: value, resolution, invalidation, commit safety; one-shot
reads; frame-context observation and compiled-elision proof.

| Id | Law | Canonical paragraph | Applicability | Fixture | Status |
|---|---|---|---|---|---|

### FH-CTRL — Controllers

Props-only default, frame data keyed by kind plus explicit address, semantic
transitions, owner cleanup.

| Id | Law | Canonical paragraph | Applicability | Fixture | Status |
|---|---|---|---|---|---|

### FH-PRESENCE — Presence

Keyed retention, override, timeout, accessibility, and test contract.

| Id | Law | Canonical paragraph | Applicability | Fixture | Status |
|---|---|---|---|---|---|

### FH-TOPLAYER — Top layer

The popover/modal desired-state pair, commit and generation law, structural
metadata, browser matrix.

| Id | Law | Canonical paragraph | Applicability | Fixture | Status |
|---|---|---|---|---|---|

### FH-BEHAVIOR — Behaviors

Id/config/timing/optional-command protocol, commit-only connection, explicit
command target, private memory, JVM marker and fallback.

| Id | Law | Canonical paragraph | Applicability | Fixture | Status |
|---|---|---|---|---|---|

### FH-REACT — React bridges

Descriptor-only outward bridge, common frame and props semantics, the explicit
mapper, SSR policy, qualified host boundaries.

| Id | Law | Canonical paragraph | Applicability | Fixture | Status |
|---|---|---|---|---|---|

### FH-ERROR — Errors

Boundary, reset, fallback, and safe-intent contract; private frame error egress;
a failed candidate publishes nothing.

| Id | Law | Canonical paragraph | Applicability | Fixture | Status |
|---|---|---|---|---|---|

### FH-ROOT — Roots and SSR

Root Descriptor, preflight, identity, teardown, multi-root isolation, SSR
emission, hydration.

| Id | Law | Canonical paragraph | Applicability | Fixture | Status |
|---|---|---|---|---|---|

### FH-STRUCT — Structure

The versioned semantic tree, the conversion table, the explicit host policy.

| Id | Law | Canonical paragraph | Applicability | Fixture | Status |
|---|---|---|---|---|---|

### FH-DIAG — Diagnostics

Versioned occurrence schema, stable ids, source and recovery, bounded retention,
provable-only static findings.

| Id | Law | Canonical paragraph | Applicability | Fixture | Status |
|---|---|---|---|---|---|
