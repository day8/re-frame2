# Story UI — Test Mode and Evidence

> The Story `:test` mode as a proof/results surface: the unified
> run-result presentation, the `:cannot-run` third state, per-assertion
> detail, schema-violation reporting, visual/a11y check results, the
> result→evidence-spine linkage, and generated-failure promotion (distinct
> from save-current-state). This spec **supersedes** the current
> result-reading contract in [`009-Test-Mode.md`](009-Test-Mode.md) once
> the substrate's unified run-result lands.

## Builds on

- [`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md) — the
  product contract, the failure-state visual hierarchy (§12.4), and the
  status vocabulary (§12.6).
- [`009-Test-Mode.md`](009-Test-Mode.md) — the **current** `:test` pane,
  the chrome-level test widget + sidebar status dots, and the play
  step-debugger. This spec carries that surface forward and supersedes
  only its result-reading shape (see §1).
- [`004-Assertions.md`](004-Assertions.md) — the seven canonical
  `:rf.assert/*` events and record-don't-throw semantics that the result
  presentation reads.
- [`017-Testing-Story.md`](017-Testing-Story.md) — the unified run
  result, the `:cannot-run` third result state, the runner-capability-set
  model, and the epoch-tape evidence projection. **Source of truth for
  the substrate; this spec presents its result.**

## Supersedes

- **[`009-Test-Mode.md`](009-Test-Mode.md) result-reading contract.**
  Once the substrate's unified run-result
  ([`017-Testing-Story.md`](017-Testing-Story.md)) lands, Test mode MUST
  migrate to that single status/result/evidence shape. The current pane's
  split `:lifecycle` / `:assertions` reading is superseded by the unified
  result; the migration is through one converged path, not a parallel
  schema. The current pane's other contracts (read-only contract, Re-run
  semantics, chrome widget + sidebar dots, play step-debugger) are carried
  forward, not superseded.

## Depends on

- The substrate's unified run-result and `:cannot-run` state (BLOCKED
  until [`017-Testing-Story.md`](017-Testing-Story.md) lands; the UI MAY
  adapt the current result shape until then — see §1).
- The evidence-spine **display** for failure investigation — owned by
  [`020-Story-UI-Inspector-And-Xray.md`](020-Story-UI-Inspector-And-Xray.md)
  §3; this spec owns the result→span **linkage** (§2).
- The Story-to-Xray focus API (`rf2-crtmq`) for focusing the relevant
  Xray panel from a failed assertion —
  [`020-Story-UI-Inspector-And-Xray.md`](020-Story-UI-Inspector-And-Xray.md)
  §2.1.
- Run-artifact promotion substrate for generated-failure promotion
  (CURRENT — `re-frame.story.promotion` + its UI `re-frame.story.ui.promotion`
  now exist; §3).

## Out of scope

- The Xray embed interior, evidence-spine display, and Explain panel —
  [`020-Story-UI-Inspector-And-Xray.md`](020-Story-UI-Inspector-And-Xray.md).
- Save-current-state-as-variant (intentional authoring; distinct from
  failure promotion) —
  [`019-Story-UI-Controls-And-View-States.md`](019-Story-UI-Controls-And-View-States.md)
  §3.
- The substrate runner execution and result schema —
  [`017-Testing-Story.md`](017-Testing-Story.md).
- Sharing/export of run artifacts and screenshot egress —
  [`022-Story-UI-Docs-And-Share.md`](022-Story-UI-Docs-And-Share.md) §3.

## Status labels

This spec uses the Story UI status labels (CURRENT / TARGET / BLOCKED /
SUPERSEDES / FUTURE / OUT) defined in
[`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md)
§"Normative language".

## 1. Test mode and the unified run result

Story pressure: S4, S5, S7.

Test mode is the "run this example as proof" surface.

It MUST show:

- overall status: pass, fail, cannot-run, error, pending;
- runner selected and runner required;
- checks grouped by check id;
- terminal assertions;
- script-checkpoint assertions;
- schema violations, including consumed expected violations;
- cannot-run rows with required and available evidence;
- source links;
- re-run and richer-run affordances.

The current test pane reads the existing runtime result shape (CURRENT;
see [`009-Test-Mode.md`](009-Test-Mode.md)). When the substrate
([`017-Testing-Story.md`](017-Testing-Story.md)) lands unified run
results, Test mode MUST migrate to that shape **without** maintaining a
parallel schema. This is the supersession recorded above; until the
migration lands, the UI MUST treat the current `:lifecycle` /
`:assertions` results as transitional/adapted input and MUST NOT pretend
the false-green class is already impossible.

`:cannot-run` is a first-class third result state, visually distinct from
pass/fail/error (see [`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md)
§12.6) — a cannot-run row says what evidence/runner was required and what
was available, rather than reading as a failure.

Test mode SHOULD support:

- failed-only filtering;
- cannot-run filtering;
- per-assertion diff/detail;
- copy repro as inline plan or run artifact;
- promote generated failure to variant (§3, CURRENT — the promotion UI
  `re-frame.story.ui.promotion` over the run-artifact promotion substrate
  now exists);
- link assertion failures to the evidence spine and Xray focus (§2) where
  the result carries enough coordinates.

## 1a. Authoring expectations onto a story (S5)

Story pressure: S5.

The S5 workflow — a useful story becomes a regression test without a
separate fixture — has a CURRENT authoring surface: the expectation-author
UI `re-frame.story.ui.author-expectations` over the pure
`re-frame.story.author-expectations` substrate (rf2-ba86n.12). It is a thin
layer; it reimplements NO assertion vocabulary and NO runner-cost model.

It MUST:

- let the author add/generate expectations for app-db, subscriptions,
  rendered hiccup/DOM, schema behaviour, and browser/a11y evidence — each
  authored expectation folds onto the canonical
  `re-frame.story.assertions` `:rf.assert/*` atom for that surface (the ONE
  vocabulary, never a parallel expectation model);
- show the runner cost / `:cannot-run` **before save** — for each
  expectation, the capability tokens it requires, the cheapest concrete
  runner that can prove it, and whether it `:cannot-run` under the default
  headless runner — read from the EXISTING
  `re-frame.story.requirements` registry (`assertion-tokens` /
  `cheapest-runner` / `select-runner`), so the cost is honest and never
  discovered only at run time;
- make the saved expectations EXPLICIT variant DATA — a `(reg-variant …
  {:assertions […]})` form whose authored atoms are merged with the source
  variant's already-declared `:assertions` (additive, round-trips through
  the registrar), authored via `:extends` of the source — NOT hidden UI
  state.

It is reachable from the Controls panel (`add expectations…`, beside
`save as new variant…`) and the command palette (`Add expectations to this
story`).

Authoring expectations is **distinct** from save-current-state-as-variant
([`019-Story-UI-Controls-And-View-States.md`](019-Story-UI-Controls-And-View-States.md)
§3 — intentional STATE authoring of the live canvas) and from
generated-failure promotion (§3 — a captured run ARTIFACT). The three flows
share the review-then-commit dialog skeleton but have separate entry points
and source semantics: save-current's source is the live args snapshot,
promotion's source is a captured artifact, and authoring's source is the
author's declared INTENT (the expectation). Conflating any two is an
explicit not-EPIC-ready condition.

The read-only display of a variant's authored (declared, un-run)
expectations — each atom with its runner-cost / `:cannot-run` flag — is the
`authored-expectation-strip` companion in
`re-frame.story.ui.assertion-strip` (distinct from the run-result strip,
which carries verdicts).

## 2. Result → evidence linkage

Story pressure: S4.

The evidence spine **display** is owned by
[`020-Story-UI-Inspector-And-Xray.md`](020-Story-UI-Inspector-And-Xray.md)
§3. This section owns the **linkage**: how a run-result row drives the
selected span and the focused Xray panel.

- A selected result row (a failed assertion, a schema violation, a
  cannot-run row) MUST be able to drive the evidence spine's selected
  span, so the failure investigation follows the failure-state hierarchy
  in [`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md) §12.4.
- Where the result carries the coordinates, a failed assertion SHOULD
  offer "open in Xray" that focuses the relevant Xray panel/epoch/path via
  the focus API
  ([`020-Story-UI-Inspector-And-Xray.md`](020-Story-UI-Inspector-And-Xray.md)
  §2.1).
- When a selected run has failures, Test mode SHOULD make the evidence
  spine the main body of the failure investigation (the T1 resolution in
  [`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md) §4), with
  result rows and assertion detail driving the selected span.

This linkage is the mechanism by which evidence can be primary during
debugging without becoming a fourth top-level mode.

## 3. Generated-failure promotion

Story pressure: S6.

Promotion turns a generated/captured run artifact (typically a failure)
into a curated named variant. The run-artifact promotion substrate
(`re-frame.story.promotion`) and its UI (CURRENT) now exist: the UI is a
thin layer over the substrate's pure `materialize-variant-plan` /
`artifact->variant-body` (read what a promotion would produce) and its
single impure `promote-run-artifact!` register path — it reimplements no
promotion logic.

Promotion MUST be **distinct** from save-current-state-as-variant
([`019-Story-UI-Controls-And-View-States.md`](019-Story-UI-Controls-And-View-States.md)
§3): save-current-state is intentional authoring of a useful state from
the live controls/canvas; promotion captures a run artifact (often a
failure surfaced by a runner or generated by a matrix) and registers it
as a regression variant. Conflating the two is an explicit
"not-EPIC-ready" condition. The two flows share the review-then-commit
dialog skeleton but have separate entry points and artifact-kind
semantics: save-current opens from the controls panel against the live
args snapshot; promotion opens from the Test pane's "promote run" button
(or the sidebar's captured-artifacts affordance) against a captured run
artifact.

Anonymous captured run artifacts MUST be openable WITHOUT cluttering the
sidebar — they surface as a bounded, collapsed "Captured artifacts"
affordance (a single section carrying a count, respecting the large-list
bounding in §10 of
[`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md)), NOT a
permanent nav row per artifact. From there the user can inspect the
artifact's evidence (the same retained tape the evidence spine displays,
§2), then promote / name / edit / tag / save it as a variant.

Promotion is **non-destructive**: registering a promoted variant copies a
trimmed `:run-artifact` provenance link into the variant body and leaves
the source artifact untouched — the original artifact REMAINS evidence,
re-inspectable and re-promotable. The saved-from-promotion variant is
explicit variant DATA (the registered four-bucket body), not hidden UI
state.

The promoted variant MUST record the fidelity of the captured state and
MUST respect the egress redaction posture
([`022-Story-UI-Docs-And-Share.md`](022-Story-UI-Docs-And-Share.md) §3)
when the artifact leaves the process.

## 4. Visual and a11y checks

Story pressure: S5, S7.

Visual and a11y checks are assertions on the same plan/result model.

The UI MUST:

- show when browser evidence is required;
- keep browser-only checks out of default headless paths unless explicitly
  requested;
- render cannot-run distinctly;
- show visual diffs beside app evidence;
- show a11y findings with selector/source/context links.

The selector/source-link requirement is met per tier (CURRENT, rf2-ffu8t):

- **axe `:rf.assert/a11y` (the `:browser` tier)** carries a real source
  link. The executor (`re-frame.story.play.browser/eval-a11y`) recovers the
  offending-element CSS selector(s) from the axe violation's `:nodes` →
  `:target` (`axe-finding`) — the SAME selectors the a11y panel's overlay
  decorates — and threads them onto the finding as `:selector` (the primary
  link) + `:targets` (every node selector). The result UI
  (`re-frame.story.ui.test-mode.visual_a11y_view`) renders the selector as
  the finding's locus/source link.
- **structural `:rf.assert/a11y-structural` (the `:hiccup` tier)** has NO
  real source coordinate to surface, and the UI does not fabricate one. The
  `:hiccup-structure` runner walks an in-memory rendered hiccup tree, not a
  DOM, so there is no CSS selector and no file/line coord on the tree to
  recover. A structural finding surfaces its offending hiccup TAG as the
  locus (the strongest honest signal the tier can prove) and offers no
  selector slot. A real selector for the same surface is the axe tier's job.

Visual-diff UX MUST define:

- baseline source: golden slice, previous run, or named visual baseline;
- update/bless semantics;
- the relation between a visual diff and the evidence/narrative beat;
- privacy/redaction behaviour for screenshots (egress posture per
  [`022-Story-UI-Docs-And-Share.md`](022-Story-UI-Docs-And-Share.md) §3).

Story owns variant/chrome a11y. Xray does **not** own a duplicate a11y
panel.

## 5. Acceptance criteria

The Test-mode and evidence-linkage contract is satisfied when:

- Test mode shows overall status including the `:cannot-run` third state,
  the runner selected vs required, checks grouped by check id, terminal +
  script-checkpoint assertions, schema violations (including consumed
  expected violations), and source links;
- the current result shape is clearly treated as transitional until the
  unified run-result lands, and the migration is one converged path with
  no parallel schema;
- cannot-run is visually distinct from pass/fail/error and explains what
  was required vs available;
- a selected result row drives the evidence spine and (where coordinates
  exist) focuses the relevant Xray panel, so failures explain themselves
  through variant → script span → epoch beat → assertion → Xray panel;
- generated-failure promotion is distinct from save-current-state, records
  fidelity, and respects egress redaction;
- visual/a11y results show browser-evidence requirements, render
  cannot-run distinctly, and present diffs/findings beside app evidence
  with source links — the axe `:browser`-tier finding carries the
  offending-element CSS selector recovered from the violation's `:nodes`
  (CURRENT, rf2-ffu8t); the structural `:hiccup`-tier finding surfaces its
  hiccup tag (no DOM selector exists at that tier — see §4);
- failed runs do not first confront the user with a raw app-db diff, trace
  tree, or EDN blob.

## Cross-references

| Concern | Source |
|---|---|
| Product contract + failure hierarchy + status vocab | [`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md) |
| Current `:test` pane / widget / step-debugger | [`009-Test-Mode.md`](009-Test-Mode.md) |
| Canonical `:rf.assert/*` events | [`004-Assertions.md`](004-Assertions.md) |
| Unified run-result + `:cannot-run` + runners | [`017-Testing-Story.md`](017-Testing-Story.md) |
| Evidence-spine display + Xray focus API | [`020-Story-UI-Inspector-And-Xray.md`](020-Story-UI-Inspector-And-Xray.md) |
| Save-current-state (distinct from promotion) | [`019-Story-UI-Controls-And-View-States.md`](019-Story-UI-Controls-And-View-States.md) |
| Egress redaction of artifacts/screenshots | [`022-Story-UI-Docs-And-Share.md`](022-Story-UI-Docs-And-Share.md) |
