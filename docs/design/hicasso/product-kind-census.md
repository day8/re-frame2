# The product-record kind census — how much of `product/` is view specification

`rf2-g0vdg`, 2026-08-19. A **measurement**. It moves nothing, edits nothing and
rules nothing. Every file under [`product/`](product/README.md) is assigned one of four
kinds, with a proportion where a file carries more than one, and the counts are the
deliverable.

**Why it exists.** [`rf2-ps7ia`](product/README.md#custody-and-amendment) must decide where the
Hicasso normative set lives, and that decision reads very differently depending on how
much of the set is actually normative. The estimate in play was *roughly forty files*.
The measured population is **64 files and 17,044 lines**, so the estimate was low by
half — which is the argument for counting before deciding. **This page does not take
that decision.** It supplies the number it turns on.

## The headline

**The view-specification set is small, and it is not a set of files.** Of 17,044 lines,
about **1,136 — under 7% — are view specification**, and they do not sit in files of
their own: they are sections inside seven documents whose remaining bulk is working
record. The corpus is **89% working record by line**. Nothing here is a whole-file
substrate contract. The normative core a consumer must obey to write Hicasso views
correctly would fit in roughly **1,100 lines across three or four authored documents**,
and the largest single contributor is one section range of one file.

| Kind | Files (primary) | Lines (attributed) | Share |
|---|---|---|---|
| 1 — View specification | 7 | ~1,136 | 6.7% |
| 2 — Substrate contract | **0** | ~97 | 0.6% |
| 3 — Reader-facing answer | 4 | ~609 | 3.6% |
| 4 — Working record | 53 | ~15,202 | 89.2% |
| **Total** | **64** | **17,044** | **100%** |

Files are counted by their **primary** kind — the one holding the largest share.
Lines are **attributed**: a file that is 55% view specification contributes 55% of its
lines to kind 1 and the rest to whichever kind holds them. The two columns therefore
answer different questions, and the line column is the one the decision turns on.

**Eighteen of the 64 files are MIXED**; the other 46 are single-kind, and 46 of 46 of
those are working record. So mixture is not a rare complication here — it is the shape
of every document that carries any normative content at all. Not one file in this tree
is purely view specification. [`lanes/ergonomics-api.md`](product/lanes/ergonomics-api.md)
comes closest at about 95%.

### How the population was measured

64 tracked files, 17,044 lines, of which 13 files and 947 lines are under
[`lanes/`](product/lanes/README.md) and 5 files and 707 lines under
[`pilots/`](product/pilots/README.md). Both figures were re-derived here rather than
inherited and both agree with the bead.

### The register is written in a non-normative voice, and that is a finding

A count of RFC-2119 keywords (`MUST`, `MUST NOT`, `SHALL`, `REQUIRED`) over all 64 files
returns **one hit, in a single file**, against a control of **35** in
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
| [`invariants.md`](product/invariants.md) §7 *The two-hook ceiling, and the chosen collector substrate* | ~12 of 179 | [`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) |
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
  [`product/complaints.md`](product/complaints.md) to have a row in `spec/009`, and every
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

### Top level — 46 files, 15,390 lines

| File | Lines | Primary | Mix |
|---|---|---|---|
| [`README.md`](product/README.md) | 82 | WR | — |
| [`specification.md`](product/specification.md) | 506 | **VS 55%** | WR 45% (§6 budgets, §11 portfolio, §12 programme, §13 done); SC ~3 lines → `004C` |
| [`decision-brief.md`](product/decision-brief.md) | 164 | WR | — |
| [`invariants.md`](product/invariants.md) | 179 | **VS 53%** | WR 40%; SC 7% (§7 two-hook subsection → `006`) |
| [`facade-freeze.md`](product/facade-freeze.md) | 247 | **VS 55%** | WR 45% (§1 membership, §4 the amendment, §6 what it is not) |
| [`dispositions.md`](product/dispositions.md) | 512 | WR 60% | VS 40% (§2's two policies, §2.4 default rule, target-policy column) |
| [`complaints.md`](product/complaints.md) | 308 | WR 70% | VS 30% (stability rule, what a complaint carries) — normative half already in `spec/009` |
| [`globals.md`](product/globals.md) | 240 | WR | — |
| [`requirements-mine.md`](product/requirements-mine.md) | 130 | WR | — |
| [`naming-ledger.md`](product/naming-ledger.md) | 75 | WR | — |
| [`naming-packet.md`](product/naming-packet.md) | 364 | WR | — |
| [`naming-findings-cp2.md`](product/naming-findings-cp2.md) | 43 | WR | — |
| [`naming-findings-cp3.md`](product/naming-findings-cp3.md) | 57 | WR | — |
| [`budgets.md`](product/budgets.md) | 2204 | WR | — |
| [`release-policy.md`](product/release-policy.md) | 339 | **RF 70%** | WR 30% |
| [`release-scans.md`](product/release-scans.md) | 328 | WR | — |
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
| [`correction-ledger.md`](product/correction-ledger.md) | 131 | WR | — |
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

## What the seven view-specification carriers actually hold

Ranked by attributed view-specification lines, because this is the list a graduation
would work from:

| Carrier | VS lines | What it holds |
|---|---|---|
| [`specification.md`](product/specification.md) §§1–5, 7–10 | ~275 | Product shape, the architecture laws, the target programming model (`h/defview`, `h/sub`, `h/event`, `h/defhost`, `h/as-element`, root lifecycle, error region), events, controlled fields, host interop, the native hot path, the coverage table, React compatibility, the testing ladder, Xray's questions |
| [`dispositions.md`](product/dispositions.md) §2 | ~205 | Per-surface server/hydration policy — the *target policy* half; the inventory-id rule; §2.4's default rule and the only route out of it |
| [`facade-freeze.md`](product/facade-freeze.md) §§2–3, 5 | ~136 | Fourteen frozen laws, the four-item reserved-data vocabulary, and what is deliberately *not* on the ordinary surface |
| [`lanes/ergonomics-api.md`](product/lanes/ergonomics-api.md) | ~113 | The public language: core surface, the optional `n` surface and the provisional `n/$` grammar, nine authoring laws, the exclusions, the interop contract |
| [`invariants.md`](product/invariants.md) §§1–4 | ~95 | I1–I15, the capability/rent table, and both provisional facades — all transcribed, with *the owner governs and the row is the defect* stated at the top |
| [`lanes/react-compatibility-notes.md`](product/lanes/react-compatibility-notes.md) | ~86 | The canonical public-surface SSR/hydration matrix, Activity, Suspense, the external-store ceiling, hydration as a root-level diagnostic contract |
| [`lanes/testing-xray.md`](product/lanes/testing-xray.md) | ~47 | The L0–L4 ladder as a supported product contract, the evidence contract, the failure/privacy contract |
| [`lanes/design-laws.md`](product/lanes/design-laws.md) §§1–4 | ~44 | React and ownership, state and reactivity, language and interop, the native boundary — the canonical owner of the native-tier laws |

Two structural facts about that list:

- **It is heavily duplicative by design.** `invariants.md` says of itself that *nothing
  here is new* and that every row is transcribed from `specification.md`,
  `lanes/design-laws.md`, `lanes/ergonomics-api.md` or `decision-brief.md`.
  `facade-freeze.md` says its laws are transcribed from `specification.md` §4 and
  `lanes/ergonomics-api.md` and that it *adds none*. So the ~1,136 lines contain perhaps
  two independent statements of the same law and in places three. A graduation that
  de-duplicated would land materially under a thousand lines; a graduation that moved the
  files wholesale would carry all three copies plus the transcription-precedence rule that
  exists only to arbitrate between them.
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
   names `docs/design/hicasso/product/complaints.md` outright, and it says *"When a Hicasso
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
documents.** The normative core is about 1,136 lines, under 7% of a 17,044-line corpus,
and it is not distributed across thirty files: eight section ranges in seven documents
carry effectively all of it, and those ranges are substantially transcriptions of one
another, so a de-duplicated statement of the Hicasso view contract plausibly lands under a
thousand lines — three or four authored documents, one pass, not a sequenced programme.
What is a programme, and would be a large and thankless one, is moving *files*: 53 of the
64 are working records whose value is their provenance pins, dated measurement windows and
beside-amendment history, and every one of the seven carriers is mixed, so any file-level
move either drags working record into `spec/` or tears a document that says of itself that
*where a row and its owner disagree the owner governs*. The two cheapest facts to act on
are that the reader-facing half has already largely graduated into the 29 published pages
under `docs/core/hicasso/`, and that one of the seven carriers — `complaints.md` — is not
homeless at all, because `spec/009` already carries its normative half under a
bidirectional gate and already reserves the slot for the rest.

## What this page is not

It takes no decision on `rf2-ps7ia`. It proposes no destination, moves nothing, and
changes no gate. Its two riders are reported and deliberately not fixed.

**Where this page sits, and why.** `docs/design/` is where working records live and this is
one; it sits at `docs/design/hicasso/` rather than inside `product/` on purpose, because
`product/README.md` enumerates every file in that directory and a 65th file would both
enlarge the population this page measures and leave that index incomplete. It is gated by
`scripts/check_doc_slugs.py` for link targets and heading anchors, and by
`scripts/check_provenance_pins.py`, which has nothing to adjudicate here — this page cites
no commit hashes. `mkdocs build --strict` does **not** reach it: `mkdocs.yml`'s
`exclude_docs` block carries `design/hicasso/`. It is not added to
`docs/design/hicasso/README.md`'s table, which already omits three other top-level pages in
this directory.
