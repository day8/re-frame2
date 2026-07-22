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

### FH-PROPS — Props

One props map: reserved `:children`, stripped `:key`, equality and conversion,
optional schema semantics.

| Id | Law | Canonical paragraph | Applicability | Fixture | Status |
|---|---|---|---|---|---|

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
