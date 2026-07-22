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
| `FH-CALL-001` | A declared view cannot be successfully called: it is not a function and not a map, and a direct call raises `:rf.error/view-called-directly` naming the three legal recoveries, on both hosts and at every arity the host's call protocol declares | [004-Views.md#a-declared-view-cannot-be-called](../../004-Views.md#a-declared-view-cannot-be-called) | common jvm browser | `spec/conformance/freehand/fixtures/fh-call-001.edn` | active |
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
the atomic selected bundle.

| Id | Law | Canonical paragraph | Applicability | Fixture | Status |
|---|---|---|---|---|---|
| `FH-EVENT-001` | The event site materializes the closed `::v/value` / `::v/checked` / `::v/key` trio from the live callback payload into top-level argument positions only — every occurrence, never a nested one, position zero never a marker, an unavailable payload a typed error with no dispatch — and general re-frame dispatch gains no payload arity | [004-Views.md#event-intent-and-the-payload-materializer](../../004-Views.md#event-intent-and-the-payload-materializer) | common jvm browser | `spec/conformance/freehand/fixtures/fh-event-001.edn` | active |
| `FH-EVENT-002` | An event-producing site yields exactly one event vector or `nil` after the closed listener options are interpreted; `nil` dispatches nothing, a multi-intent vector and an unknown option key are rejected, and `:once` is site state retained across re-render | [004-Views.md#event-intent-and-the-payload-materializer](../../004-Views.md#event-intent-and-the-payload-materializer) | common jvm browser | `spec/conformance/freehand/fixtures/fh-event-002.edn` | active |
| `FH-EVENT-003` | The callback roster is closed and classification over an event position is total: each legal form resolves to exactly one role, anything else is rejected naming the roster, and `v/render-fn` / `v/raw-fn` sit outside the committed-proxy scheme | [004-Views.md#callback-roles-and-identity](../../004-Views.md#callback-roles-and-identity) | common jvm browser | `spec/conformance/freehand/fixtures/fh-event-003.edn` | active |
| `FH-EVENT-004` | Each event site owns one stable committed proxy bound to the incarnation that minted it: identity survives re-render, equal values at two sites stay independent, a never-selected candidate's proxy is never a doorway, a later commit retargets the body without changing the identity, and a retired proxy stays inert even when its key is re-used | [004-Views.md#callback-roles-and-identity](../../004-Views.md#callback-roles-and-identity) | common jvm browser | `spec/conformance/freehand/fixtures/fh-event-004.edn` | active |

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

### FH-ROUTELINK — Routing link

The framework-supplied navigation anchor: a real element with a real href, the
native-behaviour deferrals, the caller veto, the server shell, and the
absent-routing diagnostic. The href and click law itself is routing's, cited
here rather than restated.

| Id | Law | Canonical paragraph | Applicability | Fixture | Status |
|---|---|---|---|---|---|
| `FH-ROUTELINK-001` | `v/route-link` renders a real `<a>` whose href is the route's encoded URL, synthesised from `:to` plus `:params` / `:query` / `:fragment`; passthrough attributes reach the element and no route key leaks onto it | [012-Routing.md#the-freehand-route-link-descriptor](../../012-Routing.md#the-freehand-route-link-descriptor) | common jvm browser | `spec/conformance/freehand/fixtures/fh-routelink-001.edn` | active |
| `FH-ROUTELINK-002` | A modifier click, an auxiliary-button click, a `:download` anchor and a non-`_self` `:target` are NOT intercepted: the default action is left intact, nothing is dispatched, and the native attributes reach the DOM | [012-Routing.md#the-freehand-route-link-descriptor](../../012-Routing.md#the-freehand-route-link-descriptor) | common browser | `spec/conformance/freehand/fixtures/fh-routelink-002.edn` | active |
| `FH-ROUTELINK-003` | A caller-supplied `:on-click` runs before the framework's click decision and pre-empts it: if it prevents the default, the framework neither prevents nor dispatches | [012-Routing.md#the-freehand-route-link-descriptor](../../012-Routing.md#the-freehand-route-link-descriptor) | common browser | `spec/conformance/freehand/fixtures/fh-routelink-003.edn` | active |
| `FH-ROUTELINK-004` | The JVM/SSR render emits the same real anchor with the path-form href and no click handler | [012-Routing.md#the-freehand-route-link-descriptor](../../012-Routing.md#the-freehand-route-link-descriptor) | common jvm ssr | `spec/conformance/freehand/fixtures/fh-routelink-004.edn` | active |
| `FH-ROUTELINK-005` | Rendering without the routing artefact raises the named `:rf.error/routing-artefact-missing` at render — naming the view and the link's `:to` — rather than emitting a dead anchor | [012-Routing.md#the-freehand-route-link-descriptor](../../012-Routing.md#the-freehand-route-link-descriptor) | common jvm browser | `spec/conformance/freehand/fixtures/fh-routelink-005.edn` | active |
| `FH-ROUTELINK-006` | One common render produces the identical anchor on the JVM and in ClojureScript, the click closure being the only host divergence; the descriptor itself is an ordinary declared view | [012-Routing.md#the-freehand-route-link-descriptor](../../012-Routing.md#the-freehand-route-link-descriptor) | common jvm browser | `spec/conformance/freehand/fixtures/fh-routelink-006.edn` | active |

### FH-STRUCT — Structure

The versioned semantic tree, the conversion table, the explicit host policy.

| Id | Law | Canonical paragraph | Applicability | Fixture | Status |
|---|---|---|---|---|---|
| `FH-STRUCT-001` | The structural tree is plain serialisable data in five discriminated variants, discriminated in one pinned order; the root node carries the schema version and a value the closed node set cannot carry fails loud | [004B-UI-Tree-and-Conversion.md#the-node-schema--version-1](../../004B-UI-Tree-and-Conversion.md#the-node-schema--version-1) | interpreted jvm browser | `spec/conformance/freehand/fixtures/fh-struct-001.edn` | active |
| `FH-STRUCT-002` | An element node carries its tag verbatim with no case folding, `.class#id` sugar merged sugar-first, disjoint `:attrs` and `:events`, `:key` iff the site was keyed, `:ns` absent for HTML, and every optional field absent when empty | [004B-UI-Tree-and-Conversion.md#element-fields-pinned](../../004B-UI-Tree-and-Conversion.md#element-fields-pinned) | interpreted jvm browser | `spec/conformance/freehand/fixtures/fh-struct-002.edn` | active |
| `FH-STRUCT-003` | Attribute values normalize into semantic space: one canonical class string, a canonical style map under the px rule, `name` for keywords and symbols, JavaScript `ToString` for numbers on both hosts, booleans intact, nil entries dropped | [004B-UI-Tree-and-Conversion.md#attr-value-normalization-in-tree-semantic-space](../../004B-UI-Tree-and-Conversion.md#attr-value-normalization-in-tree-semantic-space) | interpreted jvm browser | `spec/conformance/freehand/fixtures/fh-struct-003.edn` | active |
| `FH-STRUCT-004` | Children canonicalise into one document-order vector: nil/false/true drop, numbers become text, seqs and forwarded children runs flatten, adjacent text coalesces, empty strings drop, and `:children` is absent when empty except on a fragment | [004B-UI-Tree-and-Conversion.md#child-normalization-canonical-form](../../004B-UI-Tree-and-Conversion.md#child-normalization-canonical-form) | interpreted jvm browser | `spec/conformance/freehand/fixtures/fh-struct-004.edn` | active |
| `FH-STRUCT-005` | Namespace context is derived at render: HTML writes no `:ns`, `<svg>`/`<math>` carry the namespace they open, descendants inherit it, and `<foreignObject>` and an HTML-island `<annotation-xml>` revert their children | [004B-UI-Tree-and-Conversion.md#namespaces](../../004B-UI-Tree-and-Conversion.md#namespaces) | interpreted jvm browser | `spec/conformance/freehand/fixtures/fh-struct-005.edn` | active |
| `FH-STRUCT-006` | The same declaration yields an equal structural tree on the JVM and in ClojureScript, with view boundaries as real nodes recording props, key, and the expansion — and the same declaration promoted with `{:compiled true}`, its call sites and this fixture untouched, yields that same tree | [004B-UI-Tree-and-Conversion.md#cross-host-equality](../../004B-UI-Tree-and-Conversion.md#cross-host-equality) | common jvm browser | `spec/conformance/freehand/fixtures/fh-struct-006.edn` | active |
| `FH-STRUCT-007` | The React emitter mounts a declared view as real DOM: elements, converted attribute names, composed classes, text, and a keyed run of child boundaries, read back off the document | [004B-UI-Tree-and-Conversion.md#the-react-emitter](../../004B-UI-Tree-and-Conversion.md#the-react-emitter) | interpreted browser | `spec/conformance/freehand/fixtures/fh-struct-007.edn` | active |
| `FH-STRUCT-008` | Numbers render with JavaScript `Number::toString(10)` on both hosts — shortest round-tripping digits, ties to even, ECMA's plain/exponential window — through text children, attribute values and CSS values alike, while a JVM integer beyond JavaScript's exactly representable range renders exactly and sits outside the cross-host claim | [004B-UI-Tree-and-Conversion.md#attr-value-normalization-in-tree-semantic-space](../../004B-UI-Tree-and-Conversion.md#attr-value-normalization-in-tree-semantic-space) | interpreted jvm browser | `spec/conformance/freehand/fixtures/fh-struct-008.edn` | active |

### FH-DIAG — Diagnostics

Versioned occurrence schema, stable ids, source and recovery, bounded retention,
provable-only static findings.

| Id | Law | Canonical paragraph | Applicability | Fixture | Status |
|---|---|---|---|---|---|
