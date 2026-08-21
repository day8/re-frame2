# The product-record kind census — how much of `product/` is view specification

`rf2-g0vdg`, 2026-08-19; re-derived 2026-08-20. A **measurement**. It moves nothing,
edits nothing and rules nothing. Every file under [`product/`](product/README.md) is
assigned one of four kinds, with a proportion where a file carries more than one, and the
counts are the deliverable.

**Why it exists.** [`rf2-ps7ia`](product/README.md#custody-and-amendment) must decide where the
Hicasso normative set lives, and that decision reads very differently depending on how
much of the set is actually normative. The estimate in play was *roughly forty files*.
The measured population is **64 files and 17,054 lines**, so the estimate was low by
half — which is the argument for counting before deciding. **This page does not take
that decision.** It supplies the number it turns on.

## The headline

**The view-specification set is small, and it is not a set of files.** Of 17,054 lines,
about **1,138 — under 7% — are view specification**, and they do not sit in files of
their own: they are sections inside **twelve** documents whose remaining bulk is working
record. The corpus is **89% working record by line**. Nothing here is a whole-file
substrate contract. The normative core a consumer must obey to write Hicasso views
correctly would fit in roughly **1,100 lines across three or four authored documents**,
and the largest single contributor is one section range of one file.

| Kind | Files (primary) | Files (any share) | Lines (attributed) | Share |
|---|---|---|---|---|
| 1 — View specification | 7 | **12** | ~1,138 | 6.7% |
| 2 — Substrate contract | **0** | 3 | ~97 | 0.6% |
| 3 — Reader-facing answer | 4 | 6 | ~608 | 3.6% |
| 4 — Working record | 53 | 64 | ~15,211 | 89.2% |
| **Total** | **64** | — | **17,054** | **100%** |

**Two file counts, and they answer different questions.** *Files (primary)* counts a file
once, under the kind holding its largest share; the column therefore sums to 64 and says
how many documents are *chiefly* of a kind. *Files (any share)* counts every file carrying
**any** attributed content of that kind; it double-counts mixed files by design, does not
sum to 64, and says how many documents a graduation of that kind would have to **read
from**. For view specification the two numbers are **7 and 12**, and the gap is the whole
of the mixture finding: five further documents carry view specification as a minority
share. Neither number is wrong and neither substitutes for the other — the earlier
revision of this page quoted 7 in the headline while its carrier table listed a
non-primary carrier, which is what `AUDIT-REOPEN #8522` caught.

Lines are **attributed**: a file that is 55% view specification contributes 55% of its
lines to kind 1 and the rest to whichever kind holds them. The line column is the one the
decision turns on. Shares are rounded independently to one decimal and need not sum to
exactly 100.

**Eighteen of the 64 files are MIXED**; the other 46 are single-kind, and 46 of 46 of
those are working record. So mixture is not a rare complication here — it is the shape
of every document that carries any normative content at all. Not one file in this tree
is purely view specification. [`lanes/ergonomics-api.md`](product/lanes/ergonomics-api.md)
comes closest at about 95%.

### How the population was measured

64 tracked files, 17,054 lines, of which 13 files and 947 lines are under
[`lanes/`](product/lanes/README.md) and 5 files and 707 lines under
[`pilots/`](product/pilots/README.md), leaving 46 files and 15,400 lines at top level.

**Pinned to a commit, because the line total moves.** These figures were re-derived at
commit `5858e9317a83e3ed0c34e95389457d83afd1e654`. The file count and the classification
have not changed since the first pass; the line total has, from 17,044 to 17,054, entirely
inside two single-kind working records — `correction-ledger.md` 131 → 133 and
`release-scans.md` 328 → 336. No carrier of any normative kind changed length, so every
kind-1, kind-2 and kind-3 figure on this page is unmoved and only the kind-4 residual
absorbed the ten lines. The surrounding tree moved further: `docs/design/hicasso/` as a
whole is now **135** files against the 131 the bead recorded, that growth being this page
plus `studio/` additions, none of it under `product/`.

**Two in-flight branches will move the total again**, both in working records and neither
touching a carrier: one is editing `product/correction-ledger.md` and one
`product/release-scans.md`. A reader reconciling a later count against this page should
expect the kind-4 line to differ and the rest to hold.

**A measurement caveat worth recording.** On the authoring host, combining a positive
pathspec with a `:!` exclusion in `git ls-files` returned **zero** rather than the
64 the positive pathspec alone returns — a false-empty that would have read as *nothing
there*. Every census on this page was therefore run without an exclusion pathspec, with an
explicit control confirming no `.beads` path falls under the measured roots (it does not:
the roots are entirely inside `docs/`). Each sweep was also run once against something it
should find, so an empty result here means absence rather than a mis-spelled pattern.

### The register is written in a non-normative voice, and that is a finding

A count of RFC-2119 keywords (`MUST`, `MUST NOT`, `SHALL`, `REQUIRED`) over all 64 files
returns **one hit, in a single file** — `REQUIRED`, in
[`dispositions.md`](../../../implementation/hicasso/spec/dispositions.md) — against a control of **35** in
[`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) and 3 in
[`spec/004B-UI-Tree-and-Conversion.md`](../../../spec/004B-UI-Tree-and-Conversion.md) taken
with the same expression. The corpus states its laws in the indicative — *"a `defview` is
always a boundary"*, *"deferred crossings refuse"* — not in the imperative. That is a real
register difference from `spec/`, and it means graduating this material is a **rewrite in
voice**, not only a move. A move that preserved the prose would put a document into `spec/`
that does not read like the rest of `spec/`.

## The four kinds, and what decides one

1. **View specification** — normative statements about how a view is *authored* in
   Hicasso; what a consumer must obey. This is the kind with no current home.
2. **Substrate contract** — normative statements about what a *substrate* must provide.
   These already have homes: `004B` (structural render-tree ABI), `004C` (root identity
   and mount grammar), `006` (substrate contract).
3. **Reader-facing answer** — content a Hicasso user needs, belonging on a guide page
   under `docs/core/hicasso/`.
4. **Working record** — provenance, measurement windows, budgets, decision history.
   These stay exactly where they are.

## Kind 2 is empty at file granularity, and that is the second finding

**No file under `product/` is a substrate contract.** The distinction that decides it:
Hicasso is a *view substrate*, so almost everything normative here is a statement about
what Hicasso provides to a view author (kind 1), not about what a substrate must provide
to Hicasso (kind 2). The substrate-facing material exists but is fragmentary — three
minority slices, each nameable to its `spec/` neighbour:

| Slice | Lines | Belongs beside |
|---|---|---|
| [`substrate-decision.md`](product/substrate-decision.md) — the collector's subscription substrate, the adapter Hicasso installs, the two-hook ceiling | ~82 of 546 | [`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) |
| [`invariants.md`](../../../implementation/hicasso/spec/invariants.md) §7 *The two-hook ceiling, and the chosen collector substrate* | ~12 of 179 | [`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) |
| [`specification.md`](product/specification.md) §4 root-lifecycle and server/hydration contract rows | ~3 of 506 | [`spec/004C-Roots-and-Mount.md`](../../../spec/004C-Roots-and-Mount.md) |

Nothing in the tree is `004B` material as a *statement*: Hicasso's test kit **consumes**
the structural tree ABI rather than specifying it, which is a consumer relationship and
not a contract this corpus owns.

## `spec/` already carries Hicasso, and already points here

The premise that `spec/` carries no view specification **holds** —
[`spec/README.md`](../../../spec/README.md) states in terms that *"There is no 004 view
contract"*. But `spec/` is not Hicasso-free, and the difference matters to the split
ruling:

- **`spec/009-Instrumentation.md` carries a Hicasso section**, `#### Hicasso — the
  interpreted hiccup substrate (EXPERIMENTAL)`, and it is the **normative statement of the
  meaning, payload and recovery of every live complaint id**. It says so of itself.
- That section **already contains the pointer** rider B asks about. It reads *"Its design
  record lives under `docs/design/hicasso/`; it has no normative feature Spec yet … **When
  a Hicasso Spec lands, these rows gain their link and lose nothing else.**"* — so `spec/`
  both points into this tree today and has a designed slot reserved for a future Hicasso
  Spec.
- The binding runs **both ways and is gated**:
  `implementation/hicasso/scripts/check_complaint_catalogue.py` requires every live row in
  [`product/complaints.md`](../../../implementation/hicasso/spec/complaints.md) to have a row in `spec/009`, and every
  reserved or retired id to have none.

So one document in this tree — `complaints.md` — is **not homeless at all**. Its normative
half already lives in `spec/`; what stays here is precisely the status ledger that
`spec/009` deliberately cannot carry, because a `009` row means *the runtime raises this
today*.

## The classification, file by file

Proportions are rounded; a blank second column means the file is single-kind.
**VS** = view specification, **SC** = substrate contract, **RF** = reader-facing,
**WR** = working record.

### `lanes/` — 13 files, 947 lines

| File | Lines | Primary | Mix |
|---|---|---|---|
| [`lanes/README.md`](product/lanes/README.md) | 57 | WR | — |
| [`lanes/adversarial-risks.md`](product/lanes/adversarial-risks.md) | 43 | WR 65% | VS 35% (the *required contract* column) |
| [`lanes/completeness-audit.md`](product/lanes/completeness-audit.md) | 65 | WR | — |
| [`lanes/corpus-insights.md`](product/lanes/corpus-insights.md) | 83 | WR | — |
| [`lanes/delivery-programme.md`](product/lanes/delivery-programme.md) | 23 | WR | — |
| [`lanes/design-laws.md`](product/lanes/design-laws.md) | 63 | **VS 70%** | WR 30% (*Economics and scope*, *Evidence and tools*) |
| [`lanes/ergonomics-api.md`](product/lanes/ergonomics-api.md) | 119 | **VS 95%** | WR 5% |
| [`lanes/evidence-baseline.md`](product/lanes/evidence-baseline.md) | 88 | WR | — |
| [`lanes/hot-path-architecture.md`](product/lanes/hot-path-architecture.md) | 103 | WR 50% | RF 30% (the ladder, read-topology guidance), VS 20% (semantic fence) |
| [`lanes/left-field-ideas.md`](product/lanes/left-field-ideas.md) | 84 | WR | — |
| [`lanes/react-compatibility-notes.md`](product/lanes/react-compatibility-notes.md) | 114 | **VS 75%** | WR 25% (witness columns) |
| [`lanes/testing-xray.md`](product/lanes/testing-xray.md) | 79 | **VS 60%** | WR 40% |
| [`lanes/use-cases.md`](product/lanes/use-cases.md) | 26 | WR 75% | VS 25% |

### `pilots/` — 5 files, 707 lines

Every one is working record: a prep package, not a contract. The two briefs are written
*to a pilot agent* and are deliberately free of in-tree references.

| File | Lines | Primary | Mix |
|---|---|---|---|
| [`pilots/README.md`](product/pilots/README.md) | 48 | WR | — |
| [`pilots/workspace.md`](product/pilots/workspace.md) | 174 | WR | — |
| [`pilots/brief-linearlite.md`](product/pilots/brief-linearlite.md) | 177 | WR | — |
| [`pilots/brief-realworld.md`](product/pilots/brief-realworld.md) | 148 | WR | — |
| [`pilots/friction-log.md`](product/pilots/friction-log.md) | 160 | WR | — |

### Top level — 46 files, 15,400 lines

| File | Lines | Primary | Mix |
|---|---|---|---|
| [`README.md`](product/README.md) | 82 | WR | — |
| [`specification.md`](product/specification.md) | 506 | **VS 55%** | WR 45% (§6 budgets, §11 portfolio, §12 programme, §13 done); SC ~3 lines → `004C` |
| [`decision-brief.md`](product/decision-brief.md) | 164 | WR | — |
| [`invariants.md`](../../../implementation/hicasso/spec/invariants.md) | 179 | **VS 53%** | WR 40%; SC 7% (§7 two-hook subsection → `006`) |
| [`facade-freeze.md`](product/facade-freeze.md) | 247 | **VS 55%** | WR 45% (§1 membership, §4 the amendment, §6 what it is not) |
| [`dispositions.md`](../../../implementation/hicasso/spec/dispositions.md) | 512 | WR 60% | VS 40% (§2's two policies, §2.4 default rule, target-policy column) |
| [`complaints.md`](../../../implementation/hicasso/spec/complaints.md) | 308 | WR 70% | VS 30% (stability rule, what a complaint carries) — normative half already in `spec/009` |
| [`globals.md`](product/globals.md) | 240 | WR | — |
| [`requirements-mine.md`](product/requirements-mine.md) | 130 | WR | — |
| [`naming-ledger.md`](../../../implementation/hicasso/spec/naming-ledger.md) | 75 | WR | — |
| [`naming-packet.md`](product/naming-packet.md) | 364 | WR | — |
| [`naming-findings-cp2.md`](product/naming-findings-cp2.md) | 43 | WR | — |
| [`naming-findings-cp3.md`](product/naming-findings-cp3.md) | 57 | WR | — |
| [`budgets.md`](../../../implementation/hicasso/spec/budgets.md) | 2204 | WR | — |
| [`release-policy.md`](product/release-policy.md) | 339 | **RF 70%** | WR 30% |
| [`release-scans.md`](product/release-scans.md) | 336 | WR | — |
| [`per-keystroke.md`](product/per-keystroke.md) | 659 | WR 80% | RF 20% (§6 asks for it as explanatory documentation *as well as* a witness) |
| [`topology-tournament.md`](product/topology-tournament.md) | 1001 | WR | — |
| [`prototype-suite-triage.md`](product/prototype-suite-triage.md) | 962 | WR | — |
| [`tool-consumer-census.md`](product/tool-consumer-census.md) | 630 | WR | — |
| [`architecture-census.md`](product/architecture-census.md) | 246 | WR | — |
| [`substrate-decision.md`](product/substrate-decision.md) | 546 | WR 85% | **SC 15% → `006`** |
| [`k3-disposition.md`](product/k3-disposition.md) | 488 | WR | — |
| [`k1-price-acceptance.md`](product/k1-price-acceptance.md) | 186 | WR | — |
| [`checkpoint-1-kernel.md`](product/checkpoint-1-kernel.md) | 360 | WR | — |
| [`checkpoint-2-slice.md`](product/checkpoint-2-slice.md) | 413 | WR | — |
| [`checkpoint-3-native.md`](product/checkpoint-3-native.md) | 420 | WR | — |
| [`checkpoint-4-coverage.md`](product/checkpoint-4-coverage.md) | 461 | WR | — |
| [`correction-ledger.md`](product/correction-ledger.md) | 133 | WR | — |
| [`post-rename-recertification.md`](product/post-rename-recertification.md) | 280 | WR | — |
| [`post-rename-recertification-2026-08-18.md`](product/post-rename-recertification-2026-08-18.md) | 291 | WR | — |
| [`authoring-report-slice.md`](product/authoring-report-slice.md) | 176 | WR | — |
| [`authoring-report-todo.md`](product/authoring-report-todo.md) | 188 | WR | — |
| [`mcp-runtime-query-spike.md`](product/mcp-runtime-query-spike.md) | 174 | WR | — |
| [`readset-group-census.md`](product/readset-group-census.md) | 172 | WR | — |
| [`resource-demand-criteria.md`](product/resource-demand-criteria.md) | 168 | WR | — |
| [`resource-demand-witness.md`](product/resource-demand-witness.md) | 191 | WR | — |
| [`resource-demand-verdict.md`](product/resource-demand-verdict.md) | 332 | WR | — |
| [`capsule-replay-criteria.md`](product/capsule-replay-criteria.md) | 90 | WR | — |
| [`capsule-replay-verdict.md`](product/capsule-replay-verdict.md) | 113 | WR | — |
| [`callback-identity-verdict.md`](product/callback-identity-verdict.md) | 80 | WR | — |
| [`pull-shaped-reads-verdict.md`](product/pull-shaped-reads-verdict.md) | 151 | WR | — |
| [`counterfactual-topology-prediction.md`](product/counterfactual-topology-prediction.md) | 347 | WR | — |
| [`forms-recipes.md`](product/forms-recipes.md) | 93 | **RF 55%** | WR 45% |
| [`virtualizer-recipe.md`](product/virtualizer-recipe.md) | 119 | **RF 60%** | WR 40% |
| [`async-routing-recipes.md`](product/async-routing-recipes.md) | 144 | **RF 60%** | WR 40% |

## What the twelve view-specification carriers actually hold

**All twelve are listed, and the column sums to the headline.** Ranked by attributed
view-specification lines, because this is the list a graduation would work from. The
**Primary** column marks the seven whose largest share is view specification — the seven
counted in the *Files (primary)* column above — and distinguishes them from the five that
carry view specification as a minority share while remaining working records overall.
Excluding those five would understate the reading a graduation must do, so none is
excluded.

| Carrier | Primary? | VS lines | What it holds |
|---|---|---|---|
| [`specification.md`](product/specification.md) §§1–5, 7–10 | **VS** | ~278 | Product shape, the architecture laws, the target programming model (`h/defview`, `h/sub`, `h/event`, `h/defhost`, `h/as-element`, root lifecycle, error region), events, controlled fields, host interop, the native hot path, the coverage table, React compatibility, the testing ladder, Xray's questions |
| [`dispositions.md`](../../../implementation/hicasso/spec/dispositions.md) §2 | WR | ~205 | Per-surface server/hydration policy — the *target policy* half; the inventory-id rule; §2.4's default rule and the only route out of it. Also the tree's only RFC-2119 keyword |
| [`facade-freeze.md`](product/facade-freeze.md) §§2–3, 5 | **VS** | ~136 | Fourteen frozen laws, the four-item reserved-data vocabulary, and what is deliberately *not* on the ordinary surface |
| [`lanes/ergonomics-api.md`](product/lanes/ergonomics-api.md) | **VS** | ~113 | The public language: core surface, the optional `n` surface and the provisional `n/$` grammar, nine authoring laws, the exclusions, the interop contract |
| [`invariants.md`](../../../implementation/hicasso/spec/invariants.md) §§1–4 | **VS** | ~95 | I1–I15, the capability/rent table, and both provisional facades — all transcribed, with *the owner governs and the row is the defect* stated at the top |
| [`complaints.md`](../../../implementation/hicasso/spec/complaints.md) §§*What every complaint carries*, *The stability rule*, *Rulings this catalogue owns* | WR | ~92 | The four guaranteed `ex-data` slots and the rule that `:view`/`:source` are context and never branchable; the id-stability rule. **Its normative half already lives in `spec/009` under a bidirectional gate**, which is why this carrier is the least homeless of the twelve |
| [`lanes/react-compatibility-notes.md`](product/lanes/react-compatibility-notes.md) | **VS** | ~86 | The canonical public-surface SSR/hydration matrix, Activity, Suspense, the external-store ceiling, hydration as a root-level diagnostic contract |
| [`lanes/testing-xray.md`](product/lanes/testing-xray.md) | **VS** | ~47 | The L0–L4 ladder as a supported product contract, the evidence contract, the failure/privacy contract |
| [`lanes/design-laws.md`](product/lanes/design-laws.md) §§1–4 | **VS** | ~44 | React and ownership, state and reactivity, language and interop, the native boundary — the canonical owner of the native-tier laws |
| [`lanes/hot-path-architecture.md`](product/lanes/hot-path-architecture.md) §*Owned native surface and semantic fence*, §*Explicit refusals* | WR | ~21 | The semantic fence: no macro rewrites interpreted Hiccup and only an explicit `n/$` form expands to direct React construction; the standing refusals. The rest of the file is workflow, ladder and acceptance evidence |
| [`lanes/adversarial-risks.md`](product/lanes/adversarial-risks.md) — *Required contract* column | WR | ~15 | Per-risk required contracts stated normatively: ambient-read extent, controlled-input portability, callback retirement, hydration isolation, speculative-render residue. The other three columns are witnesses and remedies, which are working record |
| [`lanes/use-cases.md`](product/lanes/use-cases.md) — *Hicasso consequence* column | WR | ~6 | A handful of standing authoring rules stated as consequences — preserve one interpreted mode, keep read-anywhere during direct synchronous boundary execution, add no generic local-state DSL. The *Job* and *Design pressure* columns are requirements-mine material |
| **Total** | — | **~1,138** | Seven primary carriers hold ~799; the five minority carriers hold ~339 |

**Why the split matters more than either number.** A graduation that took only the seven
primary carriers would move about 70% of the view specification and leave ~339 lines of it
behind in documents that would still read as working records — including the two largest
single omissions, `dispositions.md` §2 at ~205 lines and `complaints.md` at ~92. Those two
alone are more than a quarter of the corpus's normative content. So the *seven* is the
right answer to *how many documents are chiefly specification* and the wrong answer to
*how many documents must be read*, and only the second question bears on `rf2-ps7ia`.

Three structural facts about that list:

- **It is heavily duplicative by design.** `invariants.md` says of itself that *nothing
  here is new* and that every row is transcribed from `specification.md`,
  `lanes/design-laws.md`, `lanes/ergonomics-api.md` or `decision-brief.md`.
  `facade-freeze.md` says its laws are transcribed from `specification.md` §4 and
  `lanes/ergonomics-api.md` and that it *adds none*. So the ~1,138 lines contain perhaps
  two independent statements of the same law and in places three. A graduation that
  de-duplicated would land materially under a thousand lines; a graduation that moved the
  files wholesale would carry all three copies plus the transcription-precedence rule that
  exists only to arbitrate between them.
- **The five minority carriers are the ones a file-level move would strand**, and they are
  not the small ones. `dispositions.md` and `complaints.md` are 512 and 308 lines of which
  ~205 and ~92 are normative; moving either wholesale drags a per-surface inventory and a
  live status ledger into `spec/`, and moving neither leaves a quarter of the view contract
  outside it. This is the concrete form of the mixture finding: the tree has no file whose
  boundaries match the contract's boundaries.
- **The reader-facing half has largely graduated already.** 29 pages stand under
  `docs/core/hicasso/` and the ladder in `lanes/hot-path-architecture.md` is already
  published as `escape-ladder.md`; `05-forms.md` already stands as the draft spec for the
  forms module by operator ruling. The four RF-primary files are recipes and a release
  policy whose reader-facing conclusions have a live home to be *stated on*, which is the
  shape `rf2-75q3l` settled.

## Rider A — the gate gap, confirmed at source

**Confirmed. A normative tree at `implementation/hicasso/spec/` would be ungated for
links and anchors from day one.**

- `scripts/check_doc_slugs.py:93-98` sets `DEFAULT_ROOTS = ("docs", "spec", "skills",
  "migration")`. `:100-101` adds `TOOLS_ROOT = "tools"`, and `:716-724` walks
  `DEFAULT_ROOTS` plus `tools/<tool>/spec` for each tool that has one. **`implementation`
  appears nowhere in that roster** — its only two occurrences in the file, at `:637` and
  `:967`, are prose comments about source-comment links and about the fence model, neither
  of which adds a root.
- `scripts/check_readme_links.py` covers `README.md` beside source, repo-root markdown and
  `.claude/commands/*.md` (its module docstring and `:210`, `:390`). It walks
  `rglob("README.md")` at `:253`. So it would reach an `implementation/hicasso/spec/README.md`
  and **nothing else in that directory**.
- `mkdocs.yml`'s `docs_dir` is `docs`, so `implementation/**` was never a candidate for the
  site build either.

**The smallest correct fix, not built.** Add `tools`-style deep-walking for
`implementation/<artefact>/spec` alongside the existing `TOOLS_ROOT` arm in
`scripts/check_doc_slugs.py` — the same seven lines at `:716-724`, given a second root
constant. It is the minimal change because the precedent, the walk and the anchor model
are all already there; only the roster is short. Two riders on it: the gate is already
required on every PR through `test.yml`'s `verify-readme-links` job, so widening the
roster gates the new tree with no workflow change; and the new arm needs its own
self-test row, because a walk that finds no directory exits 0 and would look green
forever if the path were spelled wrong.

## Rider B — the ruling collision, and there is not one

**`rf2-0yp7w.11` does not forbid a "see hicasso" pointer. It forbids re-aiming retired
donor material at hicasso, which is a different thing, and it explicitly anticipates a
future Hicasso spec.** Its ruling text:

> Do NOT rename or re-aim it at hicasso: hicasso's actual contract is a different,
> interpreted-Hiccup native model … and re-aiming would launder donor-era normative text
> into hicasso's contract — the opposite of the spec-is-the-artefact discipline. Git
> history is the raw material **if whoever later writes a hicasso-native grammar spec**
> wants it; that judgement is theirs, then.

Three things follow, and the third is decisive:

1. The prohibition's subject is the **document** — `spec/004D-Freehand-Compiled-Grammar.md`
   — and the act prohibited is **renaming or re-aiming it**. A pointer telling a reader
   where the view contract lives moves no donor text anywhere.
2. The ruling **contemplates a hicasso-native grammar spec being written later** and
   reserves that judgement rather than foreclosing it. A rule that forbade pointing at a
   future spec could not also invite one.
3. **A pointer of exactly that kind already exists in `spec/`, landed and gated.**
   `spec/009-Instrumentation.md`'s Hicasso section points at `docs/design/hicasso/` and
   names `implementation/hicasso/spec/complaints.md` outright, and it says *"When a Hicasso
   Spec lands, these rows gain their link and lose nothing else."* Whatever `rf2-0yp7w.11`
   forbids, it evidently does not forbid that, because that is on `main` and green.

**One correction to how the ruling is being cited.** `rf2-h89ri`'s close reason calls it
*"Mike's own rf2-0yp7w.11 ruling"*. `rf2-0yp7w.11`'s own description records it as **taken
under the `rf2-0yp7w` landing-order ruling of 2026-08-16, a delegated decision, operator-
reversible** — not a personal operator ruling. That does not change what it says; it
changes what it would take to revisit it, which is worth knowing before treating it as a
fixed constraint on the split.

So `rf2-h89ri`'s decision to write no pointer stands on its own merits — a pointer to a
destination nobody had ruled would have invented one — but **it was not compelled by
`rf2-0yp7w.11`**, and once a destination exists nothing in that ruling stands in the way of
saying so in `spec/`.

## Is the view-specification set a small job or a programme?

**A small job, and smaller than it looks — but only if answers graduate rather than
documents.** The normative core is about 1,138 lines, under 7% of a 17,054-line corpus,
and it is not distributed across thirty files: **twelve documents carry it, seven of them
chiefly**, and the top four carriers alone hold ~732 of the 1,138. Those ranges are
substantially transcriptions of one another, so a de-duplicated statement of the Hicasso
view contract plausibly lands under a thousand lines — three or four authored documents,
one pass, not a sequenced programme. **Twelve is the number to plan the reading against
and three or four is the number to plan the writing against**, and conflating them is the
one way to get this wrong in either direction: a reader who takes *seven* as the reading
list silently drops ~339 normative lines, and a reader who takes *twelve* as the writing
list builds a programme the material does not justify.

What is a programme, and would be a large and thankless one, is moving *files*: 53 of the
64 are working records whose value is their provenance pins, dated measurement windows and
beside-amendment history, and **every one of the twelve carriers is mixed** — not one file
in this tree is purely view specification — so any file-level move either drags working
record into `spec/` or tears a document that says of itself that *where a row and its owner
disagree the owner governs*. The two cheapest facts to act on are that the reader-facing
half has already largely graduated into the 29 published pages under `docs/core/hicasso/`,
and that one of the twelve carriers — `complaints.md` — is not homeless at all, because
`spec/009` already carries its normative half under a bidirectional gate and already
reserves the slot for the rest.

**On the register.** Whichever number is used, this is a rewrite and not a move: the corpus
states its laws in the indicative and returns exactly one RFC-2119 keyword across all 64
files, against 35 in `spec/006` alone. That cost falls on the ~1,138 lines, not on the
17,054.

## What this page is not

It takes no decision on `rf2-ps7ia`. It proposes no destination, moves nothing, and
changes no gate. Its two riders are reported and deliberately not fixed.

**What the 2026-08-20 revision changed, and what it did not.** `AUDIT-REOPEN #8522` found
this page internally inconsistent about how many documents carry view specification: the
headline said seven while the carrier table listed eight rows, one of them a non-primary
carrier, and four further attributed carriers were silently absent — so the table summed to
~1,001 against a headline of ~1,136 with nothing explaining the gap. **No classification
changed.** Every file holds the kind and proportion it was first assigned; what changed is
that the page now states both counts, says which question each answers, lists all twelve
carriers so the column reconciles with the headline, and carries the ten lines of working-
record growth since the first pass. The headline total moved from ~1,136 to ~1,138 for one
reason only: `specification.md`'s attributed lines are now carried as ~278 — 55% of 506 —
where the earlier carrier table rounded them to ~275.

**Where this page sits, and why.** `docs/design/` is where working records live and this is
one; it sits at `docs/design/hicasso/` rather than inside `product/` on purpose, because
`product/README.md` enumerates every file in that directory and a 65th file would both
enlarge the population this page measures and leave that index incomplete. It is gated by
`scripts/check_doc_slugs.py` for link targets and heading anchors, and by
`scripts/check_provenance_pins.py`, which has exactly one token to adjudicate: the single
commit this page's measurement is pinned to, under *How the population was measured*. That
commit was on `origin/main` when it was written and stays reachable, so the pin resolves
rather than stranding. `mkdocs build --strict` does **not** reach it: `mkdocs.yml`'s
`exclude_docs` block carries `design/hicasso/`. It is not added to
`docs/design/hicasso/README.md`'s table, which already omits three other top-level pages in
this directory.
