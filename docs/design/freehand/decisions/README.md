# Freehand decision register

All twenty-two Freehand product decisions are **Ruled**. The files in this
directory explain the problem, alternatives, consequences, and rationale; their
header states the operative ruling so an implementer does not have to infer it
from a recommendation section.

The register contains choices that affect public authoring, semantic laws,
cross-mode conformance, host integration, tooling, or release claims. Ordinary
private implementation choices remain with the programmer.

## Authority and document roles

Use these sources according to the question being answered:

1. An explicit operator ruling controls.
2. [EP-0036](../../../EP/EP-0036-the-freehand-view-substrate-programme.md)
   controls product topology, programme ownership, migration, and gates.
3. The accepted
   [product-completion setpoint](../product-completion-setpoint.md) records the
   2026-07-26 completion rulings until their vertical slices graduate.
4. A canonical specification controls a surface once its Freehand migration has
   landed. Until then, the existing specification describes the donor-era shipped
   contract and [`codex-design.md`](../codex-design.md) describes the ratified
   Freehand target.
5. This register records the individual rulings and their rationale.
6. [`fable-design.md`](../fable-design.md) and the
   [fitness harness](../studio/fitness-harness.md) supply worked examples,
   evidence, failure modes, and acceptance pressure. They do not add API.

The distinction in item 3 is temporal, not permission to choose between two
contracts. Each implementation slice migrates its canonical spec before or with
code; after that migration there is one owner.

## Settled foundations

- The product is Freehand, published through `re-frame.freehand` with alias `v`
  and no second product root or alternative primary facade. Qualified edge
  namespaces such as `.test`, `.form`, and `.controls` remain part of Freehand.
- Freehand is one re-frame-native substrate with interpreted and compiled modes;
  the compiled mode is required.
- The useful `re-frame.ui` machinery is absorbed as the compiled mode's
  implementation. `re-frame.ui` is donor-only now and is deleted when internal
  conformance, pilots, and consumer migration are complete—a gate, not a date.
- There is one declaration form. `{:compiled true}` selects compilation, and
  `:re-frame.freehand/v1` versions the compiled grammar.
- `local`, its placement machinery, and the neutral React hook tier do not
  survive absorption. React protocols remain behind explicit host wrappers.
- re-frame is the only reactive application-state system.
- One user action produces one semantic event vector or `nil`, never an event
  vector-of-vectors language.
- Mount/unmount and other host lifecycle facts are tool evidence, not domain
  events.
- Compilation is manual and evidence-guided. There is no second compiler,
  automatic promotion, or hidden interpreted fallback in compiled markup.
- Keyed presence and separate React/JVM emitters are required capabilities.

## Rulings

| ID | Operative ruling |
|---|---|
| [D001](D001-product-name-and-namespace.md) | Freehand; `re-frame.freehand`, alias `v`; no second public door |
| [D002](D002-view-boundaries-and-call-semantics.md) | every mounted boundary is a vector-called `v/defview`; helpers are direct-called functions |
| [D003](D003-reusable-control-state-model.md) | semantic controllers over shared re-frame infrastructure; generic storage only for protocol-free state |
| [D004](D004-state-identity-and-addressing.md) | explicit caller-supplied `:control` addresses for writable state; occurrence identity remains tool-plane evidence |
| [D005](D005-sub-outside-render.md) | `v/sub` is render-only; `rf/subscribe-once` is the one-shot read |
| [D006](D006-event-projections-and-payload-injection.md) | the Freehand adapter materializes the closed projection trio; general dispatch has no payload arity |
| [D007](D007-key-condition-event-maps.md) | gate discharged DELETE (2026-07-25): the pilots used the form zero times, so a map at an event position is listener options and nothing else |
| [D008](D008-callback-forms-and-stable-identity.md) | closed callback roster with committed per-site slots; no generic dispatcher helper |
| [D009](D009-controlled-input-synchronous-flush.md) | narrow controlled-input door with a frame-scoped synchronous flush |
| [D010](D010-compiled-dynamic-markup-crossing.md) | no dynamic-markup valve in v1; use the declared `v/markup` boundary |
| [D011](D011-compiled-props-schemas.md) | schemas optional in grammar, mandatory for public library/catalogue and generated-parity surfaces |
| [D012](D012-declared-reads-and-evidence-levels.md) | no `:reads` language in v1; evidence always states scope, basis, completeness, and loss |
| [D013](D013-imperative-host-behaviors-and-commands.md) | registered behaviors with closed passive/layout timing, bounded commands, and semantic-id targets |
| [D014](D014-outward-react-bridge.md) | descriptor-only React bridge with shallow props, reserved frame, one mapper, and stable caching |
| [D015](D015-top-layer-overlays-and-portals.md) | qualified popover/modal desired-state intrinsics; no neutral portal |
| [D016](D016-buffered-and-revision-controls.md) | one generation-fenced buffered controller with required reset revision |
| [D017](D017-framework-control-and-policy-vocabulary.md) | control families are first-party library vocabulary; policy graduates only when repeated |
| [D018](D018-theming-and-parts.md) | CSS tokens, semantic part addresses, and bounded safe spreads; no portable transform seam |
| [D019](D019-error-boundaries-and-production-reports.md) | resettable error boundary, once-per-generation safe intent, and private frame error egress |
| [D020](D020-tool-evidence-retention-and-warning-policy.md) | one occurrence-keyed evidence schema and the existing Spec 009 retention axis |
| [D021](D021-performance-budgets-and-release-evidence.md) | deterministic release gates plus mandatory published timing/byte evidence without fixed thresholds |
| [D022](D022-public-react-host-door.md) | sole inward React door is one declared `v/defhost` descriptor kind; no runtime `v/host` or `v/react-el` |

## Implementation horizons

“Immediate” and “upcoming” now describe implementation order, not unresolved
design.

- **Immediate common-contract work:** D002–D011 and D013 establish boundaries,
  state, reads, events, controlled scheduling, the compiled crossing, schemas,
  and the host behavior protocol.
- **Capability slices and pilots:** D012 and D014–D020 become executable with the
  evidence, React bridge, top-layer, buffered-control, library-policy, theming,
  error, and tooling slices.
- **Continuous release evidence:** D021 applies from the first runnable B1–B5
  fixture and cannot weaken a semantic or browser-correctness gate.
- **Product-completion slices:** D022 and the accepted completion setpoint add the
  declared React host, forms/controls, executable fixture spine, and integration
  witnesses without reopening the original topology.

Dependency constraints remain small and explicit: D003 precedes the controller
work governed by D004/D016; D006–D009 precede the controlled-input proof; D013
precedes bridge/behavior integration; D002/D010/D011 precede generated cross-mode
parity; D019 constrains D020's privacy boundary.

## Graduation discipline

Each implementation slice must:

1. migrate the canonical specification and API/ownership indexes for its surface;
2. cite the relevant decision ids in its acceptance criteria;
3. add common, interpreted, compiled, JVM, browser, or host proof only where that
   surface requires it; and
4. remove the corresponding donor obligation or record why it has not yet crossed.

If implementation reveals a genuine contradiction, amend the one affected ruling
explicitly. Do not preserve two spellings, two semantics, or a compatibility alias
merely to avoid resolving it.
