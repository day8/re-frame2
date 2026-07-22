# Freehand implementation decision register

This directory contains the implementation decision set exposed by
[the product spine](../codex-design.md) and
[the argued dossier](../fable-design.md). Each decision has one self-contained
file. D001 records the settled product name; the other dossiers remain **Open**
until Mike decides them or delegates that authority.

The register includes choices that change public authoring, semantic laws,
cross-mode conformance, host integration, tooling contracts, or a release claim.
It deliberately excludes ordinary private implementation choices that can be
changed without affecting those surfaces.

## Authority and settled constraints

Read the documents in this order:

1. explicit operator rulings;
2. `codex-design.md` as the normative product spine;
3. `fable-design.md` as evidence, worked examples, and competing recommendations;
4. these dossiers as the remaining choice set.

The following are settled and therefore do **not** get open-decision files:

- The product is Freehand, published through `re-frame.freehand` with alias `v`
  and no second public namespace. [D001](D001-product-name-and-namespace.md)
  records the ruling and its rationale.
- Freehand is one re-frame-native substrate with interpreted and compiled modes;
  the compiled mode is required.
- The named useful `re-frame.ui` machinery is absorbed as the compiled mode's
  implementation. `re-frame.ui` is in donor mode now and is deleted when internal
  conformance is green, the component/library pilots pass, and consumers migrate—a
  gate, not a date.
- There is one declaration form. Compilation is selected with
  `{:compiled true}`; `:re-frame.freehand/v1` is the versioned compiled grammar,
  not a compatibility profile between products.
- `local`, its placement machinery, and the neutral React hook tier do not survive
  absorption. React protocols remain in explicit wrappers.
- re-frame is the only reactive application-state system.
- One user action produces one semantic event vector or `nil`, never a
  vector-of-vectors event language.
- Mount/unmount and host lifecycle facts go to tools, never to domain events.
- Compilation is manual and evidence-guided; there is no automatic promotion,
  second compiler, or permanent interpreted fallback hidden inside generated code.
- Keyed presence and separate React/JVM emitters are required capabilities.

## Immediate decisions

These choices should be ruled before their associated implementation surface is
allowed to harden.

| ID | Decision | Why now |
|---|---|---|
| [D002](D002-view-boundaries-and-call-semantics.md) | declared boundaries and call syntax | determines view identity, HMR, vector resolution, and page-one teaching |
| [D003](D003-reusable-control-state-model.md) | reusable-control state model | resolves the largest remaining Codex/Fable architectural fork after `local` dies |
| [D004](D004-state-identity-and-addressing.md) | state identity and addressing | required before any stateful reusable controller can be stable across refactors/tests |
| [D005](D005-sub-outside-render.md) | `sub` outside a render | fixes whether a common mistake is a probe or an error |
| [D006](D006-event-projections-and-payload-injection.md) | projection tokens and payload injection | completes the common dispatcher contract replacing donor literal-only recognition |
| [D007](D007-key-condition-event-maps.md) | key-condition maps | determines the event grammar absorbed by both runtime and compiler |
| [D008](D008-callback-forms-and-stable-identity.md) | callback forms and foreign identity | bounds React interop without importing hook ceremony into neutral views |
| [D009](D009-controlled-input-synchronous-flush.md) | synchronous controlled-input flush scope | determines correctness and background-work latency coupling |
| [D010](D010-compiled-dynamic-markup-crossing.md) | dynamic markup at the compiled seam | decides whether compiled views ever contain an interpreter walk |
| [D011](D011-compiled-props-schemas.md) | props schemas for compiled views | determines how generative parity is proved without imposing needless ceremony |
| [D013](D013-imperative-host-behaviors-and-commands.md) | behavior registry and commands-in | fixes the host ABI that replaces refs/effects and absorbs imperative libraries |

## Upcoming decisions

These can wait for the named dependency or pilot, but should be ruled before the
feature is claimed or its API is published.

| ID | Decision | Decision trigger |
|---|---|---|
| [D012](D012-declared-reads-and-evidence-levels.md) | declared reads and manifest evidence levels | first public component-library manifest/catalogue pilot |
| [D014](D014-outward-react-bridge.md) | Freehand view as a React component value | first component-as-prop integration |
| [D015](D015-top-layer-overlays-and-portals.md) | top-layer and portal vocabulary | popup/dialog re-com pilot in a real browser |
| [D016](D016-buffered-and-revision-controls.md) | buffered/revision controller placement and acceptance | full stale-blur/reset/caret/IME harness |
| [D017](D017-framework-control-and-policy-vocabulary.md) | framework versus library control/event/effect vocabulary | two independent controls repeat the same protocol |
| [D018](D018-theming-and-parts.md) | theming and parts contract | representative re-com port and compiled controlled control |
| [D019](D019-error-boundaries-and-production-reports.md) | error boundary and production report policy | browser host/error integration and privacy review |
| [D020](D020-tool-evidence-retention-and-warning-policy.md) | tool evidence, retention, and warning strictness | Xray/tool API implementation |
| [D021](D021-performance-budgets-and-release-evidence.md) | performance evidence and release-gate policy | B1–B5 baseline results |

## Dependency shape

- D003 precedes D004 and D016. If no generic substrate state is adopted, D004
  still decides explicit identity for library-owned controllers.
- D006–D009 settle the event and controlled-input laws before D016 can be judged.
- D013 precedes D014 and D015; otherwise host integrations will establish an ABI
  accidentally.
- D002, D010, and D011 precede the compiled conformance corpus.
- D003/D004 and D019 constrain the fields and privacy boundary in D020.
- D021 defines performance evidence and release-gate policy, but it cannot weaken
  any semantic conformance or browser-correctness gate.

## Coverage of Fable's operator list

Fable Q1 (absorption) is ruled and appears above as a settled constraint. The
remaining explicit questions map as follows:

| Fable question | Decision dossier |
|---|---|
| Q2(a), declared versus bare-function boundaries | D002 |
| Q2(b), derived versus explicit state identity | D003 and D004 |
| Q3, dependency-annotated foreign `v/event` | D008 |
| Q4, top-layer intrinsics | D015 |
| Q5, `sub` outside render | D005 |
| Q6, declared reads | D012 |
| Q7, attach registry and commands | D013 |
| Q8, framework control vocabulary | D017 |

The register also captures unnumbered forks and implementation-triggered choices:
event payload provenance (D006), key-condition maps (D007), the synchronous door
(D009), `(interp ...)` at the compiled seam (D010), generative props schemas
(D011), the outward React bridge (D014), buffered-control proof (D016), theming
(D018), production errors (D019), evidence policy (D020), and performance
release-gate policy (D021).

## Closing a decision

When a ruling is made:

1. change the dossier status to **Ruled** and state the selected option plainly;
2. update `codex-design.md` so the product spine carries the decision;
3. update the relevant specification or acceptance harness when implementation
   begins;
4. remove contradictory recommendation language from the other design document or
   mark it explicitly as a losing alternative.

Do not preserve two spellings or compatibility aliases merely to avoid making the
decision.
