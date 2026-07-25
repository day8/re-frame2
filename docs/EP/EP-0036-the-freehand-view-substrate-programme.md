# EP-0036: The Freehand View-Substrate Programme

Status: accepted
Type: standards-track
Created: 2026-07-22
Resolution: accepted 2026-07-22 (D001–D021 ratified and folded)

## Abstract

re-frame2's view layer becomes one re-frame-native substrate: **Freehand**,
published through `re-frame.freehand` and conventionally aliased as `v`. It has
two required execution modes over one semantic model:

- the interpreted mode is the paved path; a view body uses unrestricted Clojure,
  `v/sub` returns a value, event intent is data, and Hiccup is interpreted; and
- the compiled mode is the hot tier; `{:compiled true}` on the same `v/defview`
  declaration selects the finite, versioned `:re-frame.freehand/v1` grammar.

A declared view var holds a descriptor that **cannot be successfully called** in
either mode: a direct call raises a didactic error naming the three legal
recoveries (D002, amended 2026-07-22 — the rule is that property, and how a host
achieves it is an implementation detail; the reference implementation implements
the host call protocol solely in order to throw, so `(ifn? the-view)` is true).
Promotion changes one declaration and no call site or structural test.

The compiled mode is built by **absorption**, not reinvention. The useful
`re-frame.ui` code—the analyzer, React and JVM emitters, ViewCell reactor,
presence runtime, manifest/elision and diagnostics machinery, and test
surface—moves into Freehand. `re-frame.ui` is donor-only now: no new standalone
surface. Its artifact is deleted when internal conformance, the component and
React-library pilots, and consumer migration are complete. That is a gate, not a
date.

Implementation extends the repository's existing canonical spec owners. It does
not create a parallel `spec/0XX-Freehand` family or a spec-only waterfall. Each
vertical slice migrates its contract, implementation, fixtures, evidence, and
donor disposition together.

## Motivation

EP-0030 proved the compiled substrate end to end, including direct lowering,
reactivity, JVM structure, SSR, evidence, and tooling. Its finite grammar is an
asset for hot and library-owned boundaries, but it cannot be the low-friction
default for unrestricted application composition. Preserving an interpreted
paved path as a separate product would duplicate declarations, semantics, tests,
and debugging.

Freehand therefore keeps one programmer model and two implementations. It also
keeps one reactive state system: re-frame. Controlled values and ordinary
re-frame state cover the common case; narrow semantic library controllers cover
genuine cross-event control protocols. React-shaped protocols remain at explicit
host boundaries.

The alternatives and their evidence remain in
`docs/design/freehand/fable-design.md`. The accepted product is summarized in
`docs/design/freehand/codex-design.md`, and
`docs/design/freehand/studio/fitness-harness.md` supplies the hard browser and
component-library acceptance pressure.

## Specification

### Authority and working posture

The project is pre-alpha. Prefer an elegant, powerful whole with excellent
programmer and AI ergonomics, but do not gold-plate speculative abstractions.
Trust the programmer, keep the paved path small, and require extra machinery only
where a concrete semantic law, pilot, or measurement needs it.

Authority is purpose-specific:

1. explicit operator rulings control;
2. this EP controls product topology, programme ownership, migration, and gates;
3. an owning spec controls a surface once its Freehand migration lands;
4. until that migration, the product spine states the ratified Freehand target
   while the existing spec continues to describe donor-era shipped behavior; and
5. the decision files, argued dossier, and fitness harness retain rationale and
   evidence without adding API.

The gap in item 4 is transitional and directional. It is not a choice between two
supported contracts.

### Product topology

| Surface | Relationship to re-frame | Role |
|---|---|---|
| interpreted Freehand | native; assumes re-frame | unrestricted default authoring and execution mode |
| compiled Freehand | native; assumes re-frame | finite-site lowering over the same declarations and semantic ABI |
| `re-frame.ui` | native; donor only | temporary code source and in-tree migration surface, never published; deleted at the gate |
| Reagent, UIx, and Helix adapters | independent | external-renderer escape hatches retained under their existing programme rulings |
| Replicant | independent | whole-state architectural comparison point; not co-mounted as part of Freehand |

There is no permanent unprofiled donor variant. `local`, its placement machinery,
the neutral compiled React-hook tier, and the donor's standalone product contract
do not cross. Semantic controllers take the legitimate cross-event state job;
registered behaviors and explicit wrappers take the bounded ref/effect/React jobs.

The profile name `:re-frame.freehand/v1` is simply the compiled grammar version,
not compatibility negotiation between products. Compiled-only donor forms that do
not fit the common model disappear rather than opting a view out of parity.

### Governing laws

1. **One declaration.** Every mounted boundary is a vector-called `v/defview`;
   plain helpers are direct-called functions and never vector heads.
2. **One semantic model.** Props, children, keys, events, frames, controlled
   scheduling, structural output, errors, and evidence mean the same in both
   modes.
3. **Passive render and atomic selection.** A speculative render owns nothing;
   the selected commit publishes its frame, dependencies, events, and evidence as
   one bundle.
4. **One reactive state system.** Application and interaction state is re-frame
   data. Host objects remain private at qualified host boundaries.
5. **Intent is data.** One user action yields one semantic event vector or `nil`.
   Mount, unmount, and host lifecycle are tool facts, not domain events.
6. **Compilation is explicit.** No automatic promotion, second compiler, or
   hidden interpreted walker inside compiled markup.
7. **Proof is honest.** Separate React and JVM emitters may share normalizers but
   prove parity through common conformance values and fixtures.

The detailed D001–D022 rulings are indexed in
`docs/design/freehand/decisions/README.md`.

The accepted 2026-07-26 product-completion delta is recorded in
`docs/design/freehand/product-completion-setpoint.md`. It extends these laws with
the sole inward React host door, pure forms and first-party controls, executable
fixture/tooling authority, and ownership/evidence witnesses. It is additional
vertical work under this EP, not a second programme.

### Canonical contract migration

Freehand reuses the existing ownership layout. The first contract slice performs
the following map atomically enough that no merged state has two canonical owners
for the same surface:

| Canonical home | Freehand disposition |
|---|---|
| `spec/004-Views.md` | becomes the common Freehand declaration, authoring, semantic, and host-boundary contract |
| current donor-era compiled content of Spec 004 | moves by rename to `spec/004D-Freehand-Compiled-Grammar.md`, then evolves into the v1 compiled-tier grammar; it is not copied into a second owner |
| `spec/004B-UI-Tree-and-Conversion.md` | remains the one semantic tree and conversion-table owner, generalized from donor names to both modes |
| `spec/004C-Roots-and-Mount.md` | remains the Root Descriptor, identity, mount, hydration, and teardown owner, with Freehand names and paved-path examples |
| `spec/006-ReactiveSubstrate.md` | owns the observation-port contract consumed by the shared reactor; packaging and sole-consumer text migrates from donor to Freehand |
| `spec/008-Testing.md` | owns structural and mounted testing, the host/mode matrix, and the executable cross-mode conformance contract |
| `spec/009-Instrumentation.md` | owns Freehand diagnostic ids, evidence/retention fields, lifecycle facts, and error egress |
| `spec/011-SSR.md` | owns SSR consumption, hydration, fallback, and server-error projection |
| `spec/012-Routing.md` | continues to own route-link href and click semantics; Freehand supplies the ordinary descriptor over its late-bound seam |
| `spec/API.md`, `spec/Conventions.md`, `spec/Ownership.md`, implementor indexes | change in the same slices as the surfaces they index; they never lead or duplicate the owning contract |

The move of donor Spec 004, the new common Spec 004 skeleton, ownership rows, and
link repairs are one reviewable contract cut. Subsequent semantic sections may land
in smaller vertical slices. Filename `004D-Freehand-Compiled-Grammar.md` is fixed by
this EP so implementation work does not reopen numbering or ownership.

Executable Freehand laws live under `spec/conformance/freehand/` and use stable
`FH-<AREA>-<NNN>` ids. The index maps each id to its canonical spec paragraph,
host/mode applicability, fixture, and status. Areas include calls, props, events,
controlled input, subscriptions, controllers, presence, top layer, behaviors,
React bridges, errors, roots/SSR, structure, and diagnostics. Existing donor stage
profiles remain useful evidence during migration but do not become a second
Freehand conformance authority.

### Vertical implementation slices

The unit of delivery is a thin, runnable vertical slice—not a prose document and
not an entire architectural layer. A slice includes the owning spec change,
implementation, tests, conformance rows, relevant evidence, and donor disposition.

| Slice | Outcome | Depends on |
|---|---|---|
| **F0 — ownership cut** | rename donor Spec 004 to 004D; establish common 004, conformance index/id scheme, ownership/API/index updates, and explicit donor inventory | accepted EP |
| **F1 — paved-path spine** | the uncallable descriptor, `v/defview`, props/children/key, minimal interpreted React and JVM tree, simple `v/mount`, HMR identity, structural render | F0 |
| **F2 — reactive intent** | shared ViewCell/atomic shell, frame binding, render-only `v/sub`, event materializer/options/key maps, controlled-input door, failure-safe capture | F1 |
| **F3 — compiled absorption** | analyzer and both emitters under Freehand, `{:compiled true}`, common descriptor calls, static manifests/elision, checker, interpreted↔compiled crossings | F1; joins F2 for reactive/event parity |
| **F4 — data and host lifecycle** | semantic controllers, buffered/revision control, presence, top layer, error boundary, behaviors with timing/commands, evidence and teardown | F2; F3 only where compiled parity is claimed |
| **F5 — composition and integration** | slots/spreads/parts/schemas, `v/->react`, `v/route-link`, roots/SSR completion, component and React-library pilots | relevant F2–F4 capabilities |
| **F6 — proof and retirement** | all conformance rows green, B1–B5 evidence current, browser matrices and pilots passed, tools/docs/consumers migrated, standalone donor removed | F1–F5 gates |

This table specifies dependency direction, not one giant task per row. Independent
work inside a slice may proceed in parallel when it does not edit the same canonical
owner. A later slice may start on an independent surface as soon as its stated
dependency is green; F0 is not followed by a seven-document barrier.

Every implementation work item must state:

- the exact user-visible outcome and non-goals;
- decision ids and canonical spec/conformance ids affected;
- host/mode applicability (common, interpreted, compiled, JVM, browser, SSR,
  qualified host);
- acceptance criteria and the command or artifact that proves each one;
- the donor code/test/spec disposition for the slice; and
- any deterministic gate and any evidence-only measurement.

The initial tracker entries created with the bootstrap reflected the superseded
seven-spec waterfall. They are programme inventory only until the mayor reshapes
their descriptions and dependencies around F0–F6. This EP does not mutate tracker
state.

### Fitness and integration obligations

The following are release work, not unresolved product questions:

- the controlled and buffered fields pass same-tick, same-value reset,
  caret/selection/IME, HMR, JVM, retry, and reset-revision tests;
- asynchronous controls prove debounce cancellation, correlation, supersession,
  stale completion, retry, and unmount behavior headlessly;
- B4 publishes per-keystroke event, write, subscription-recompute, and render-commit
  counts for a four-field form and a 100-cell editing grid in both modes;
- overlays prove before-paint measurement, declared tracking frequency, native
  top-layer focus/dismiss/nesting behavior, and total observer/listener cleanup;
- accessibility diagnostics are provable-only; complete-tree and real-browser
  tests own dynamic names, roles, keyboard behavior, focus, and inertness;
- `v/route-link` proves real hrefs, native modifier/middle/download/target behavior,
  caller veto, SSR output, and missing-routing diagnostics;
- root tests cover the minimal one-root spelling, the same form in structural
  rendering, explicit multi-root identity, frame preflight, hydration, failed-root
  isolation, and total teardown;
- direct Vega and SpreadJS-class behaviors, D022-declared Radix and TanStack
  wrappers, and an AG-Grid-style `v/->react` cell prove the behavior, inward-host,
  and outward-bridge routes without adding neutral hooks, refs, portals, or
  arbitrary lifecycle callbacks.

### Conformance and donor deletion gate

The row-complete table in `docs/design/freehand/codex-design.md` §6 is Freehand's
internal two-mode contract and the donor deletion gate. It is not a compatibility
profile between two products. Release requires:

| Gate | Required evidence |
|---|---|
| semantic conformance | every applicable `FH-*` row green across interpreted/compiled and React/JVM/SSR hosts |
| browser correctness | controlled input, presence, top layer, behaviors, errors, routing, roots, and hydration matrices green |
| component/library fitness | named component and React-library pilots pass their structural, unit, mounted, and cleanup proofs |
| performance honesty | B1–B5 deterministic properties green and current timing/byte distributions published with named baselines |
| absorption completeness | every donor inventory row is moved, deliberately replaced, or deleted; no Freehand dependency on the donor remains |
| migration completeness | code, tests, tools, guides, examples, specs, API/ownership indexes, and consumers use Freehand names and contracts |

Deterministic properties—equal output, zero dropped input, exact attributable
counts, manifest/cell elision, and bundle reachability—are CI gates. Wall-clock and
byte distributions are mandatory evidence, not fixed numerical thresholds. An
adverse trend requires attribution and an explicit disposition.

When all rows are green, the standalone `day8/re-frame2-ui` artifact and its
`re-frame.ui` public surface are deleted. EP-0030 and its companion EPs remain the
historical record of the donor programme.

### Design-record posture

`docs/design/freehand/` is a durable, frozen supporting record. It remains after
spec graduation because it preserves evidence, alternatives, and rationale that
the tighter canonical specs intentionally omit. It is excluded from the mkdocs
site because its links are source-tree-oriented, not because it is temporary.

Keep it accurate but lean. Correct stale status, broken links, factual errors, and
contradictions with rulings; do not grow it into a second API manual or tracker.
Implementation work migrates citations to stable spec/conformance ids as they
land. Deleting or archiving the record would require a later explicit decision;
F6 does not do it automatically.

### Non-goals

- a second compiler, automatic promotion, or a hidden interpreted fallback;
- a permanent `re-frame.ui` sibling, unprofiled variant, or compatibility facade;
- `local`, generic component-local storage, a public `v/self`, or a second reactive
  state system;
- a `:reads` declaration language in v1;
- multi-intent handler vectors, a recursive event DSL, or payload-aware general
  dispatch;
- neutral hooks, refs, effects, portals, or arbitrary lifecycle callbacks;
- serialization of DOM nodes, React elements, callbacks, cleanup functions, or
  third-party instances;
- a layout DSL, framework widget vocabulary, or portable tree-transform theming;
- a separate simulation language beside structural tests and real mounted tests;
- numerical performance folklore presented as a release threshold.

## Backwards Compatibility

This is pre-alpha, so migration is direct. `re-frame.ui` is never published — it
is in-tree donor code for the whole of its life, so no consumer ever depends on a
`day8/re-frame2-ui` coordinate and there is no external surface to keep
compatible. The in-tree surface continues only during coexistence. Freehand never
depends on it. Any temporary forwarding facade lives in the donor artifact, gains
no new semantics, and is removed at the gate.

Donor names and reserved placeholders are mechanically renamed rather than kept as
aliases. Forms rejected by the common model (`local` and neutral ref/effect/hook
forms) are replaced by controlled props, semantic controllers, registered
behaviors, or explicit React wrappers according to their actual job.

External renderer adapters remain independent and are not renamed into Freehand.

## Resolved Decisions

- **Product and namespace:** Freehand; `re-frame.freehand`, alias `v`; no second
  public door.
- **Topology:** one substrate with interpreted and compiled modes; compilation is
  required and manually selected on the one declaration.
- **Absorption:** the useful donor machinery becomes the compiled implementation;
  the standalone donor is deleted at the evidence gate.
- **State and host boundaries:** re-frame is the only reactive state system;
  semantic controllers, behaviors, and wrappers replace the donor forms that do
  not cross.
- **D001–D022:** all are ruled;
  `docs/design/freehand/decisions/README.md` states each ruling.
- **Product completion:** DC-01–DC-09 and ER-01–ER-08 are accepted in
  `docs/design/freehand/product-completion-setpoint.md`; implementation stays
  inside this programme.
- **Contract ownership:** existing specs are migrated; `004D` owns the compiled
  grammar; there is no new numbered Freehand family.
- **Programme shape:** vertical F0–F6 slices replace the seven-spec waterfall.
- **Design retention:** the supporting record is durable and frozen, not deleted
  automatically at graduation.

## Open Issues

There are no open product decisions blocking the accepted implementation work. If
a slice discovers a genuine contradiction, it must propose one explicit amendment
to the affected ruling or canonical contract; it must not silently preserve both
answers.

One operator-level architecture choice is deliberately deferred: the commissioned
`v/$` comparison may justify replacing compiled Hiccup, but the experiment itself
does not make that change. `v/->element`, behavior outlets, a reusable async
helper, and scheduling/equality vocabulary remain evidence-gated rather than open
prerequisites.

Programme graduation remains open until F6: conformance green, pilots passed,
evidence current, consumers migrated, and the donor artifact deleted.

## References

- Freehand design record: `docs/design/freehand/README.md`
- Product-completion setpoint:
  `docs/design/freehand/product-completion-setpoint.md`
- Product spine: `docs/design/freehand/codex-design.md`
- Argued dossier: `docs/design/freehand/fable-design.md`
- Fitness harness: `docs/design/freehand/studio/fitness-harness.md`
- Decision register D001–D022: `docs/design/freehand/decisions/README.md`
- [EP-0030 — donor programme](EP-0030-the-compiled-view-substrate-program.md)
- [EP-0031 — donor programming model](EP-0031-re-frame-ui-programming-model.md)
- [EP-0032 — donor reactivity and ownership](EP-0032-re-frame-ui-reactivity-and-ownership.md)
- [EP-0033 — donor view evidence](EP-0033-re-frame-ui-view-evidence.md)
- [EP-0034 — donor production, SSR, and testing](EP-0034-re-frame-ui-production-ssr-testing.md)
- [EP-0035 — donor component-library readiness](EP-0035-component-library-readiness.md)
- [`spec/004-Views.md`](../../spec/004-Views.md)
- [`spec/004B-UI-Tree-and-Conversion.md`](../../spec/004B-UI-Tree-and-Conversion.md)
- [`spec/004C-Roots-and-Mount.md`](../../spec/004C-Roots-and-Mount.md)
- [`spec/006-ReactiveSubstrate.md`](../../spec/006-ReactiveSubstrate.md)
- [`spec/008-Testing.md`](../../spec/008-Testing.md)
- [`spec/009-Instrumentation.md`](../../spec/009-Instrumentation.md)
- [`spec/011-SSR.md`](../../spec/011-SSR.md)
- [`spec/012-Routing.md`](../../spec/012-Routing.md)
