# Hicasso (`day8/re-frame2-hicasso`) — spec

The **operative contract** for `re-frame.hicasso`: the documents another artefact
has to obey, kept beside the artefact they govern and amended in the same PR as
any change that touches them. That is the convention the `tools/*/spec/` trees
already follow, and this tree is its first instance under `implementation/`.

**Every document here is machine-gated, and that is what earned it a place.** A
row deleted, renumbered or renamed under this directory reds a named CI job on
the same pull request. Nothing here is a working design record, and nothing here
is a published page — `implementation/**` is outside `mkdocs.yml`'s `docs_dir`,
so no part of this tree is staged into the site or validated by
`scripts/check_doc_slugs.py`. Link targets and heading anchors are validated by
`scripts/check_readme_links.py --ci` instead, which reaches README and non-README
markdown beside source. Nothing validates prose, tables or rendering; whoever
edits a table verifies its column count by hand and says so.

## Files

- [`dispositions.md`](dispositions.md) — the two ledgers Phase 0 owes: which
  layer answers each use case, and the server/hydration disposition of every
  proposed public surface. It mints the permanent inventory id each surface
  carries, so no surface can be added, shipped or forgotten without a row.
  Sections 2.1 and 2.2 are the gated inventory; §3 is the append protocol that
  keeps concurrent beads out of each other's way.
- [`complaints.md`](complaints.md) — the index of Hicasso's diagnostic ids: one
  line per id the package raises, with the guide chapter that teaches how not
  to hit it. It does not restate what a complaint MEANS — that is
  [`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md)'s
  Hicasso section, which is also the single record of which ids exist and
  which are retired (a struck-through row is the tombstone).
- [`invariants.md`](invariants.md) — one page to check a change against: every
  invariant the product commits to, every capability and the rent it pays, and
  the provisional `h` and `n` facades. Wholly transcribed from the governing
  records, so where a row and its owner disagree the owner governs and the row
  is the defect.
- [`naming-ledger.md`](naming-ledger.md) — every public name in the package,
  with its naming question and disposition. Live and append-only: a public name
  that reaches the package without a row here is an unrostered name, and the
  census gate says so.
- [`budgets.md`](budgets.md) — the named reference profiles, the pinned
  baselines, and §9's reconciliation ledger: one row per registered budget line,
  each stating its verdict, its population, the instrument that read it, who
  owns it and where it was dispositioned. Ruled operative contract because it is
  the artefact's live performance ledger.

## The gates

Each document's gate is a self-proving Python checker in
[`../scripts/`](../scripts/). Every one runs its `--self-test` first, so a green
live run is a verdict rather than a checker that has quietly stopped firing.

| Document | Checker | Where it runs |
|---|---|---|
| `dispositions.md` §§2.1, 2.2 | `check_facade_inventory.py` | `npm run test:hicasso-invariants`, and the unconditional `hicasso-facade-inventory` job in `.github/workflows/test.yml` |
| `complaints.md` | none of its own since rf2-6c12m.7 retired `check_complaint_catalogue.py`; the ids its rows name are the ones `../../../scripts/check_keyword_catalogue_drift.py` reconciles against Spec 009 and the emitters, in both directions | the repo-wide keyword-drift gate in `.github/workflows/test.yml`, and `scripts/test-fast-pr.sh` |
| `invariants.md` §1 | `check_optional_module_reachability.py` | `npm run test:hicasso-invariants` |
| `naming-ledger.md` | `check_naming_census.py` | the unconditional `hicasso-naming-census` job, and `scripts/test-fast-pr.sh`'s always-on block |
| `budgets.md` §9 | `check_budget_ledger.py` | `npm run test:hicasso-invariants`, and the unconditional `hicasso-budget-ledger` job |
| `budgets.md` §9 row S7 | `../../../scripts/check_allocation_non_claim.py` | its own `docs.yml` job — it reads the S7 row as the PREMISE for refusing unqualified allocation claims elsewhere |

**A path under this tree arms the hicasso CLJS lanes**, which is the second half
of what the move bought: while these documents sat under `docs/design/`, a
document-only edit measured `false` at every classifier output, so an edit that
broke a contract ran its own gate nowhere and next reddened on somebody else's
source PR. The unconditional jobs above were each split out to repair exactly
that shape, and they stay — a gate that runs on every PR cannot be un-armed by
the next path decision.

## What is not here

The **working design records** stayed at
[`docs/design/hicasso/product/`](../../../docs/design/hicasso/product/README.md)
and keep their addresses: the decision brief, the specification, the lane set,
the checkpoint and correction ledgers, the verdicts and the pilots. Their value
is their provenance, they are not gated, and they are read by whoever needs the
history rather than by a checker.

The **teaching and how-to material** is the published guide at
[`docs/core/hicasso/`](../../../docs/core/hicasso/index.md), in the MkDocs nav.

The **framework-level pattern** is root [`spec/`](../../../spec/README.md), and
product prose does not enter it. Where re-frame2's own pattern is affected, the
touchpoint is freshly authored there — `spec/009-Instrumentation.md`'s Hicasso
section is the standing example, and it is a normative statement in root `spec/`'s
own voice rather than a relocated page.

The split between those four homes was ruled by the operator on 2026-08-21 under
`rf2-ps7ia`.
