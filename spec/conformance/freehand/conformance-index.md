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
| `FH-CALL-004` | Interpreted and compiled descriptors mount as children in either direction through the ordinary named-descriptor boundary; all four pairings of parent mode and child mode over one body yield one structural tree | [004-Views.md#cross-mode-children](../../004-Views.md#cross-mode-children) | common jvm browser | `spec/conformance/freehand/fixtures/fh-call-004.edn` | active |
| `FH-CALL-005` | `v/markup` mounts markup held as a value through an ordinary declared interpreted child: the value renders, and the child owns its own boundary node, recorded props and expansion rather than being inlined into the compiled parent | [004-Views.md#the-vmarkup-boundary](../../004-Views.md#the-vmarkup-boundary) | common jvm browser | `spec/conformance/freehand/fixtures/fh-call-005.edn` | active |

### FH-PROPS — Props

One props map: reserved `:children`, stripped `:key`, equality and conversion,
optional schema semantics.

| Id | Law | Canonical paragraph | Applicability | Fixture | Status |
|---|---|---|---|---|---|
| `FH-PROPS-001` | An internal boundary call carries exactly one props map and no positional arguments; a missing or non-map props slot is rejected | [004-Views.md#props-children-and-key](../../004-Views.md#props-children-and-key) | common jvm browser | `spec/conformance/freehand/fixtures/fh-props-001.edn` | active |
| `FH-PROPS-002` | Trailing children arrive as the reserved `:children` vector — absent when there are none; a caller-authored `:children` is rejected and the declared children policy is enforced at the call | [004-Views.md#props-children-and-key](../../004-Views.md#props-children-and-key) | common jvm browser | `spec/conformance/freehand/fixtures/fh-props-002.edn` | active |
| `FH-PROPS-003` | `:key` selects sibling identity, is stripped before the props map reaches the view, and is outside props equality | [004-Views.md#props-children-and-key](../../004-Views.md#props-children-and-key) | common jvm browser | `spec/conformance/freehand/fixtures/fh-props-003.edn` | active |
| `FH-PROPS-004` | A props schema is OPTIONAL — a declaration without one compiles, renders, and reports its schema as absent rather than as `:any` — and a declared one closes the props map to the keys it names, returning the same accept/reject verdict over the same props in both execution modes, with `:key` and `:children` never schema slots and `{:closed false}` the one explicit escape; the modes differ only in when a breach is reported, the compiled tier at build time and the boundary at render | [004D-Freehand-Compiled-Grammar.md#props-schemas](../../004D-Freehand-Compiled-Grammar.md#props-schemas) | common jvm browser | `spec/conformance/freehand/fixtures/fh-props-004.edn` | active |
| `FH-PROPS-005` | Every view the public door publishes declares a props schema, and the gate that says so names any published view that omits one — the mandate falling on published surfaces and generated-parity claims rather than on the grammar, and decided by the publishing surface rather than by a declaration option | [004D-Freehand-Compiled-Grammar.md#where-a-schema-is-mandatory](../../004D-Freehand-Compiled-Grammar.md#where-a-schema-is-mandatory) | common jvm | `spec/conformance/freehand/fixtures/fh-props-005.edn` | active |

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
| `FH-INPUT-001` | The controlled-input door is one exact predicate over five final-normalized facts — a supported native control tag, `value`/`checked` present by PRESENCE, a firing attribute normalizing to `onInput`/`onChange`, a synchronously-known event-vector-or-`nil` outcome, and no `:capture`/`:passive` lane — read as normalized SLOTS so every spelling of one emitted prop is judged alike; `:on-before-input` is outside, each near-miss is one named fact from inside, and both execution modes ask this predicate rather than a copy of it | [004-Views.md#controlled-inputs](../../004-Views.md#controlled-inputs) | common jvm browser | `spec/conformance/freehand/fixtures/fh-input-001.edn` | active |
| `FH-INPUT-002` | A site inside the door fires through a SYNCHRONOUS dispatcher bound to the exact frame its commit published — the event drains and the cells observing that frame are flushed before the call returns — while a site outside it takes the ordinary batched dispatcher into the same frame; the flush is FRAME-scoped, so a pending cell observing another frame is left pending with no revision advanced, a `nil` outcome reaches neither lane, a boundary under no frame has no door, and the verdict rides the committed plan so a re-commit moves it without changing one callback identity | [004-Views.md#controlled-inputs](../../004-Views.md#controlled-inputs) | common jvm browser | `spec/conformance/freehand/fixtures/fh-input-002.edn` | active |
| `FH-INPUT-003` | In a real browser the controlled round trip holds as a user-visible fact: sustained and rapid typing produces a final value equal to the typed string character for character, the caret and a range selection survive the round trip unmoved, an IME composition completes intact across `compositionstart`/`compositionupdate`/`compositionend`, and none of it degrades while a heavy sibling on the same frame is already dirty | [004-Views.md#controlled-inputs](../../004-Views.md#controlled-inputs) | interpreted browser | `spec/conformance/freehand/fixtures/fh-input-003.edn` | active |

### FH-SUB — Subscriptions

Render-only reads: value, resolution, invalidation, commit safety; one-shot
reads; frame-context observation and compiled-elision proof.

| Id | Law | Canonical paragraph | Applicability | Fixture | Status |
|---|---|---|---|---|---|
| `FH-SUB-001` | The selected commit publishes the boundary's frame, dependencies, event site table and evidence as one bundle; a render itself owns nothing, an unchanged site is retained untouched, and every newly-observed or retargeted target is acquired before anything is released | [006-ReactiveSubstrate.md#the-selected-render-bundle](../../006-ReactiveSubstrate.md#the-selected-render-bundle) | common jvm browser | `spec/conformance/freehand/fixtures/fh-sub-001.edn` | active |
| `FH-SUB-002` | A candidate the host never selects publishes nothing at all, and a candidate whose reconcile fails releases everything its staging acquired and leaves the prior committed set installed | [006-ReactiveSubstrate.md#abandoned-and-failed-candidates](../../006-ReactiveSubstrate.md#abandoned-and-failed-candidates) | common jvm browser | `spec/conformance/freehand/fixtures/fh-sub-002.edn` | active |
| `FH-SUB-003` | A candidate rendered against a body revision the cell has since replaced is stale and publishes nothing — checked at commit entry and again at the narrowest publication boundary — and a redeclared view remounts its boundary, releasing exactly what the old body owned | [006-ReactiveSubstrate.md#body-authority-across-a-live-cell](../../006-ReactiveSubstrate.md#body-authority-across-a-live-cell) | common jvm browser | `spec/conformance/freehand/fixtures/fh-sub-003.edn` | active |
| `FH-SUB-004` | The interpreted shell observes frame context unconditionally, so a provider retarget rebinds a props-equal child: dependencies are acquired against the new frame before the old are released, the committed event destination moves with them, no callback identity changes, and a render whose exact frame incarnation is gone publishes nothing | [006-ReactiveSubstrate.md#frame-binding-and-retarget](../../006-ReactiveSubstrate.md#frame-binding-and-retarget) | interpreted jvm browser | `spec/conformance/freehand/fixtures/fh-sub-004.edn` | active |
| `FH-SUB-005` | Each occurrence owns its own bundle and a commit publishes into that occurrence only; a keyed reorder moves occurrences rather than rebuilding them, and a reorder inside one boundary disposes neither node | [006-ReactiveSubstrate.md#occurrence-identity-across-a-reorder](../../006-ReactiveSubstrate.md#occurrence-identity-across-a-reorder) | common jvm browser | `spec/conformance/freehand/fixtures/fh-sub-005.edn` | active |
| `FH-SUB-006` | Disconnect releases every dependency, retires every published callback and drops the cell from the pending window; it is not terminal, and a host's replay of the same committed render reconnects from a clean slate rather than resurrecting released ownership | [006-ReactiveSubstrate.md#disconnect-and-replay](../../006-ReactiveSubstrate.md#disconnect-and-replay) | common jvm browser | `spec/conformance/freehand/fixtures/fh-sub-006.edn` | active |
| `FH-SUB-007` | Capture is same-render-thread only: a read with no active render is refused, and a read reached through a conveyed child thread fails before it probes, so a fork records nothing | [006-ReactiveSubstrate.md#same-render-thread-capture](../../006-ReactiveSubstrate.md#same-render-thread-capture) | common jvm browser | `spec/conformance/freehand/fixtures/fh-sub-007.edn` | active |
| `FH-SUB-008` | `v/sub` during render returns the subscription's current value and records a render-owned read; the selected commit publishes those reads as the bundle's dependency set — exactly the queries read, in render order, each an owned dependency | [006-ReactiveSubstrate.md#a-render-owned-value](../../006-ReactiveSubstrate.md#a-render-owned-value) | common jvm browser | `spec/conformance/freehand/fixtures/fh-sub-008.edn` | active |
| `FH-SUB-009` | `v/sub` is legal only during an active declared render; a read with no render to own it is refused loudly with a stable diagnostic id, raised before the target is resolved, rather than probed and dropped to a silent nil | [006-ReactiveSubstrate.md#the-render-only-rule](../../006-ReactiveSubstrate.md#the-render-only-rule) | common jvm browser | `spec/conformance/freehand/fixtures/fh-sub-009.edn` | active |
| `FH-SUB-010` | The capture rides the active render, not the call depth: a `v/sub` inside an ordinary defn helper called from the body is owned by the calling render exactly as an inline read is, and the same helper called outside a render is refused loudly | [006-ReactiveSubstrate.md#capture-through-helper-functions](../../006-ReactiveSubstrate.md#capture-through-helper-functions) | common jvm browser | `spec/conformance/freehand/fixtures/fh-sub-010.edn` | active |
| `FH-SUB-011` | When an input a committed `v/sub` depends on changes value, a re-render recomputes it and the commit republishes the whole bundle atomically: the committed bundle is unchanged until the next commit, and the recommitted dependency then carries the new value against the same retained handle, flipping as a unit | [006-ReactiveSubstrate.md#invalidation-and-atomic-recommit](../../006-ReactiveSubstrate.md#invalidation-and-atomic-recommit) | common jvm browser | `spec/conformance/freehand/fixtures/fh-sub-011.edn` | active |

### FH-CTRL — Controllers

Props-only default, frame data keyed by kind plus explicit address, semantic
transitions, owner cleanup.

| Id | Law | Canonical paragraph | Applicability | Fixture | Status |
|---|---|---|---|---|---|
| `FH-CTRL-001` | A controller record is keyed by the controller kind plus the caller-supplied `:control` address, so two occurrences of one view given DIFFERENT addresses hold independent records: an edit at one occurrence moves only its own record, and the other keeps showing its baseline | [004-Views.md#controller-identity](../../004-Views.md#controller-identity) | common jvm browser | `spec/conformance/freehand/fixtures/fh-ctrl-001.edn` | active |
| `FH-CTRL-002` | Two occurrences of one view given the SAME `:control` address share ONE record deliberately — an edit at either occurrence is read by both, no diagnostic fires, and the sharing is a consequence of the caller supplying the address rather than an accident of render position | [004-Views.md#controller-identity](../../004-Views.md#controller-identity) | common jvm browser | `spec/conformance/freehand/fixtures/fh-ctrl-002.edn` | active |
| `FH-CTRL-003` | A writable controller rendered with no `:control` address is refused loudly with `:rf.error/view-control-address-missing` naming the kind and the recovery — no default address, no synthesised one — while the same view given an address renders, and a props-only view that never asks for a record key is untouched | [004-Views.md#controller-identity](../../004-Views.md#controller-identity) | common jvm browser | `spec/conformance/freehand/fixtures/fh-ctrl-003.edn` | active |
| `FH-CTRL-004` | The causal owner's ordinary event clears the addresses it owns and the records are gone, while a record whose owner has not cleared it survives; the substrate exposes NO lifecycle cleanup hook — no unmount callback, dispose registration or per-occurrence teardown slot on the public surface or in the declared-view ABI — so retention follows the owner, never the render | [004-Views.md#semantic-transitions-and-owner-cleanup](../../004-Views.md#semantic-transitions-and-owner-cleanup) | common jvm browser | `spec/conformance/freehand/fixtures/fh-ctrl-004.edn` | active |
| `FH-CTRL-005` | Controller state is ordinary frame data: the record a controller wrote is read back through the normal re-frame path — a plain subscription over app-db, with no view mounted and no controller-specific reader — and a semantic transition is an ordinary registered event | [004-Views.md#controller-state-is-ordinary-frame-data](../../004-Views.md#controller-state-is-ordinary-frame-data) | common jvm browser | `spec/conformance/freehand/fixtures/fh-ctrl-005.edn` | active |

### FH-PRESENCE — Presence

Keyed retention, override, timeout, accessibility, and test contract.

| Id | Law | Canonical paragraph | Applicability | Fixture | Status |
|---|---|---|---|---|---|
| `FH-PRESENCE-001` | A presence boundary projects the `:rf.ui/presence {:phase :present :timeout-ms n}` fragment with its retained children `:present`, identically from an interpreted `(v/presence …)` and its `{:compiled true}` twin and on both hosts; the marker survives view-boundary adoption when presence is a view's root | [004-Views.md#presence](../../004-Views.md#presence) | common jvm browser | `spec/conformance/freehand/fixtures/fh-presence-001.edn` | active |
| `FH-PRESENCE-002` | The keyed retention machine both modes lower to derives the three-phase plan — new keys mount, present keys hold, a departed key is retained `:unmounting`, a re-entered key flips back to `:present`, and first-appearance order is frozen against an incoming reorder — and claims one pair per key, the first claimant winning and duplicates reported | [004-Views.md#presence](../../004-Views.md#presence) | common jvm browser | `spec/conformance/freehand/fixtures/fh-presence-002.edn` | active |
| `FH-PRESENCE-003` | In a real browser a departed key's child is retained `:unmounting` and owns its exit accessibility (`aria-hidden` read from `(v/presence-phase)`); the deterministic timeout removes it terminally and exactly once, and a re-entry before the flush cancels the removal and restores `:present` | [004-Views.md#presence](../../004-Views.md#presence) | interpreted browser | `spec/conformance/freehand/fixtures/fh-presence-003.edn` | active |

### FH-TOPLAYER — Top layer

The popover/modal desired-state pair, commit and generation law, structural
metadata, browser matrix.

| Id | Law | Canonical paragraph | Applicability | Fixture | Status |
|---|---|---|---|---|---|
| `FH-TOPLAYER-001` | A qualified desired-state property leaves `:attrs` and projects as the reserved `:rf.ui/top-layer` fact on the semantic element — the same value from an interpreted declaration and its `{:compiled true}` twin, on both hosts — while a nil value expresses no desired state and records nothing | [004-Views.md#the-structural-projection](../../004-Views.md#the-structural-projection) | common jvm browser | `spec/conformance/freehand/fixtures/fh-toplayer-001.edn` | active |
| `FH-TOPLAYER-002` | The desired-state pair is closed and qualified: `popover-open?` is legal only on an element with a valid `:popover` mode, `modal-open?` only on `<dialog>`, both together never, and a non-boolean value never — each refused at render with `:rf.error/ui-tree-malformed` on both hosts | [004-Views.md#the-closed-pair-and-where-each-half-is-legal](../../004-Views.md#the-closed-pair-and-where-each-half-is-legal) | common jvm browser | `spec/conformance/freehand/fixtures/fh-toplayer-002.edn` | active |
| `FH-TOPLAYER-003` | In a real browser the pair yields the platform's own behaviour: a modal dialog takes initial focus, inerts the background and returns focus on close; two non-nested `:auto` popovers are one-at-a-time while a nested pair opened in ONE commit stacks and closes together in one call; and a browser-initiated dismissal reaches the author's handler without the substrate writing any application state | [004-Views.md#native-behaviour-is-the-whole-point](../../004-Views.md#native-behaviour-is-the-whole-point) | interpreted browser | `spec/conformance/freehand/fixtures/fh-toplayer-003.edn` | active |
| `FH-TOPLAYER-004` | Host work happens only at a selected commit and only when the desired state differs from the node's live state: building elements and detaching perform zero operations, a disconnected node is skipped, the operation lands before the first frame after the commit, N re-commits of an unchanged desired state perform zero further operations, and a full mount/open/close/unmount cycle retains exactly zero listeners, observers, intervals and pending operations | [004-Views.md#commit-order-and-the-declared-tracking-frequency](../../004-Views.md#commit-order-and-the-declared-tracking-frequency) | interpreted browser | `spec/conformance/freehand/fixtures/fh-toplayer-004.edn` | active |

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
| `FH-ERROR-001` | An error boundary contains a render-class throw below it — the boundary shows its fallback while every sibling keeps rendering — and the failed candidate publishes nothing at all: the atomic bundle is absent, the cell owns no dependencies and carries no evidence | [004-Views.md#containment-and-the-abandoned-candidate](../../004-Views.md#containment-and-the-abandoned-candidate) | common jvm browser | `spec/conformance/freehand/fixtures/fh-error-001.edn` | active |
| `FH-ERROR-002` | A changed :reset-key by rf= clears the captured failure and re-mounts the child; under repeated failures within one generation the safe intent and the private egress fire exactly once, and a new failure generation after a reset reports exactly once again | [004-Views.md#reset-and-the-once-per-generation-safe-intent](../../004-Views.md#reset-and-the-once-per-generation-safe-intent) | common jvm browser | `spec/conformance/freehand/fixtures/fh-error-002.edn` | active |
| `FH-ERROR-003` | A caught render failure produces two representations by exact field set: a safe public summary an application receives, and a private egress record an off-box shipper receives that adds the opaque exception and a capped host stack — and neither carries app-db or event history | [004-Views.md#the-safe-summary-and-the-private-frame-egress](../../004-Views.md#the-safe-summary-and-the-private-frame-egress) | common jvm browser | `spec/conformance/freehand/fixtures/fh-error-003.edn` | active |

### FH-ROOT — Roots and SSR

Root Descriptor, preflight, identity, teardown, multi-root isolation, SSR
emission, hydration.

| Id | Law | Canonical paragraph | Applicability | Fixture | Status |
|---|---|---|---|---|---|
| `FH-ROOT-001` | The minimal one-root mount `(v/mount [app {…}] node)` derives the root's identity from the mounted view's registered id (root-id and view-id that id, provenance `:derived`) and renders that declared view — the same root form putting real DOM on a browser page and answering one versioned structural tree on the JVM | [004C-Roots-and-Mount.md#the-minimal-one-root-mount](../../004C-Roots-and-Mount.md#the-minimal-one-root-mount) | interpreted jvm browser | `spec/conformance/freehand/fixtures/fh-root-001.edn` | active |
| `FH-ROOT-002` | A root's occurrence identity is its derived root-id — the mounted view's qualified id, which a redefinition does not move — so a COMPATIBLE redefinition re-renders the existing host root through the boundary the host already mounted: the reloaded body renders while the mounted occurrence beneath it survives, proven by DOM state only a stable boundary can carry (an uncontrolled input's node, its typed value, its focus), with one cached boundary per view id and a body revision that advances at the reload seam; a redefinition whose hook skeleton moved is never served that boundary | [004C-Roots-and-Mount.md#hot-reload-keeps-the-root-identity-and-the-mounted-occurrence](../../004C-Roots-and-Mount.md#hot-reload-keeps-the-root-identity-and-the-mounted-occurrence) | interpreted browser | `spec/conformance/freehand/fixtures/fh-root-002.edn` | active |
| `FH-ROOT-003` | Two roots of ONE view on one page hold independent identity and independent state — distinguished by `:disambiguator` or an authored `:root-id`, each with its own host root, its own occurrence and its own injective identifierPrefix — and the three admission claims (id, container, prefix) each fail loud BEFORE any render, leaving every root already on the page rendering and untouched | [004C-Roots-and-Mount.md#several-roots-on-one-page](../../004C-Roots-and-Mount.md#several-roots-on-one-page) | interpreted browser | `spec/conformance/freehand/fixtures/fh-root-003.edn` | active |
| `FH-ROOT-004` | A root's frame plan runs to completion BEFORE the host root exists — the frame is live and its initial events drained by the time the first render reads a subscription — the ENSURE and SCOPE spellings own and borrow the frame's lifetime respectively, and a plan meeting one frame under a different config fingerprint fails THAT root with `:rf.error/frame-payload-conflict` before install and before React, with the installed frame and its sibling roots untouched | [004C-Roots-and-Mount.md#preflight-runs-before-react](../../004C-Roots-and-Mount.md#preflight-runs-before-react) | interpreted browser | `spec/conformance/freehand/fixtures/fh-root-004.edn` | active |
| `FH-ROOT-005` | Teardown is TOTAL by exact count: after `v/unmount!` the registry holds zero claims for that root, its container holds zero child nodes, the frame it ENSURED is destroyed and its ledger record gone, and the subscription its view held is released from the live sub-cache — while a frame the root merely SCOPED survives, and a stale or superseded handle is a guarded no-op rather than a teardown of the successor | [004C-Roots-and-Mount.md#total-teardown](../../004C-Roots-and-Mount.md#total-teardown) | interpreted browser | `spec/conformance/freehand/fixtures/fh-root-005.edn` | active |
| `FH-ROOT-006` | `v/hydrate-root` ADOPTS server-rendered markup — the server's own DOM nodes survive the mount — and a divergence React recovers from is REPORTED as `:rf.ssr/hydration-mismatch` carrying the root-id, composed over any host `:on-recoverable-error` and bounded to the adoption window, while identity opts supplied client-side are refused with `:rf.error/root-manifest-invalid` | [011-SSR.md#hydration-on-the-freehand-paved-path](../../011-SSR.md#hydration-on-the-freehand-paved-path) | interpreted browser | `spec/conformance/freehand/fixtures/fh-root-006.edn` | active |
| `FH-ROOT-007` | A hydrating root whose container carries nothing to adopt takes the FALLBACK — an ordinary client mount, reported as the mount kind rather than as adoption errors — while a container carrying markup that merely disagrees stays on the adoption path and takes the mismatch | [011-SSR.md#the-fallback--a-container-with-nothing-to-adopt](../../011-SSR.md#the-fallback--a-container-with-nothing-to-adopt) | interpreted browser | `spec/conformance/freehand/fixtures/fh-root-007.edn` | active |

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
| `FH-ROUTELINK-007` | `:on-click` is the imperative pre-navigation seam and its grammar is closed: a plain function or a `v/handler` runs exactly once before the navigation decision and may veto; an event vector, an options map or a `v/event` is rejected at render on both hosts, naming the recovery | [012-Routing.md#the-freehand-route-link-descriptor](../../012-Routing.md#the-freehand-route-link-descriptor) | common jvm browser | `spec/conformance/freehand/fixtures/fh-routelink-007.edn` | active |

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
| `FH-STRUCT-009` | The React emitter spells attributes as React's canonical props at every depth and across declared-view boundaries, the structural tree keeps author-space names, the reserved and rejected spellings are refused by both emitters rather than reaching React, and an alias of an accepted slot-owning key is routed to that key's slot rather than beside it | [004B-UI-Tree-and-Conversion.md#attribute-names](../../004B-UI-Tree-and-Conversion.md#attribute-names) | interpreted jvm browser | `spec/conformance/freehand/fixtures/fh-struct-009.edn` | active |
| `FH-STRUCT-010` | A compiled declaration's manifest carries the finite subscription, event, slot, frame-op, HTML-site, crossing and capability rosters its analysis makes statically knowable, each roster a function of the body's lexical sites and empty rather than absent when the body carries none, together with the capability set and the ViewCell-elision verdict those rosters decide; every entry of every roster carries a whole, positive `{:file :line :column}` source coordinate, proven over a per-roster census of declared cardinality so no roster's shape row is asserted over zero entries | [004D-Freehand-Compiled-Grammar.md#the-finite-manifest](../../004D-Freehand-Compiled-Grammar.md#the-finite-manifest) | compiled jvm browser | `spec/conformance/freehand/fixtures/fh-struct-010.edn` | active |

### FH-DIAG — Diagnostics

Versioned occurrence schema, stable ids, source and recovery, bounded retention,
provable-only static findings.

| Id | Law | Canonical paragraph | Applicability | Fixture | Status |
|---|---|---|---|---|---|
| `FH-DIAG-001` | The checker analyses a declaration read-only against the versioned grammar and answers a stable report — view id, source, current lowering, target grammar, eligibility, and findings each carrying a stable id, source coordinates, the offending form, a reason, and a non-empty recovery ladder ending in `:keep-interpreted` | [004D-Freehand-Compiled-Grammar.md#the-read-only-checker](../../004D-Freehand-Compiled-Grammar.md#the-read-only-checker) | compiled jvm | `spec/conformance/freehand/fixtures/fh-diag-001.edn` | active |
| `FH-DIAG-002` | A compiled view proven to carry no reactive site omits the reactive ViewCell shell and one carrying a reactive site retains it; the verdict is a deterministic function of the analyzed sites, so the number of views that omit the shell over a census of known cardinality is an exact integer, never a threshold | [004D-Freehand-Compiled-Grammar.md#the-capability-elision-verdict](../../004D-Freehand-Compiled-Grammar.md#the-capability-elision-verdict) | compiled jvm browser | `spec/conformance/freehand/fixtures/fh-diag-002.edn` | active |
