# Hicasso (`day8/re-frame2-hicasso`) — spec

The **operative contract** for `re-frame.hicasso`: the documents another artefact
has to obey, kept beside the artefact they govern and amended in the same PR as
any change that touches them. That is the convention the `tools/*/spec/` trees
already follow, and this tree is its first instance under `implementation/`.

Nothing here is a published page — `implementation/**` is outside `mkdocs.yml`'s
`docs_dir`, so no part of this tree is staged into the site or validated by
`scripts/check_doc_slugs.py`. Link targets and heading anchors are validated by
`scripts/check_readme_links.py --ci` instead, which reaches README and non-README
markdown beside source. Nothing validates prose, tables or rendering; whoever
edits a table verifies its column count by hand and says so.

## Files

- [`complaints.md`](complaints.md) — the index of Hicasso's diagnostic ids: one
  line per id the package raises, with the guide chapter that teaches how not to
  hit it. It does not restate what a complaint MEANS — that is
  [`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md)'s
  Hicasso section, which is also the single record of which ids exist and
  which are retired (a struck-through row is the tombstone).
- [`invariants.md`](invariants.md) — the fifteen invariants the product commits
  to, one row each, transcribed from the governing design records; where a row
  and its owner disagree the owner governs and the row is the defect.
- [`server-policy.md`](server-policy.md) — every public surface's permanent
  inventory id and its server policy, Render or Client-only, in one 43-row table.

## The gates

| Document | Checker | Where it runs |
|---|---|---|
| `complaints.md` | none of its own since rf2-6c12m.7 retired `check_complaint_catalogue.py`; the ids its rows name are the ones `../../../scripts/check_keyword_catalogue_drift.py` reconciles against Spec 009 and the emitters, in both directions | the repo-wide keyword-drift gate in `.github/workflows/test.yml`, and `scripts/test-fast-pr.sh` |
| `invariants.md` I8 | `check_optional_module_reachability.py` enforces the reachability clause against the source and cites the row | `npm run test:hicasso-invariants` |
| `server-policy.md` | none — a reference table, kept by whoever changes a policy | — |

## What is not here

The **working design records** live at
[`docs/design/hicasso/product/`](../../../docs/design/hicasso/product/README.md)
and keep their addresses: the decision brief, the specification, the lane set,
the checkpoint and correction ledgers, the verdicts and the pilots. Their value
is their provenance, they are not gated, and they are read by whoever needs the
history rather than by a checker. **Four ledgers that stood here from 2026-08-21
(`rf2-ps7ia`) joined them on 2026-08-30 (`rf2-6c12m.8`)** — `budgets.md`,
`naming-ledger.md`, `dispositions.md` and the full `invariants.md` — together
with the three checkers that policed them (`check_budget_ledger.py`,
`check_facade_inventory.py`, `check_naming_census.py`) and their CI jobs, which
were deleted: closed programme records were being gated as if live. The two
tables above are what that pass kept beside the code.

The **teaching and how-to material** is the published guide at
[`docs/core/hicasso/`](../../../docs/core/hicasso/index.md), in the MkDocs nav.

The **framework-level pattern** is root [`spec/`](../../../spec/README.md), and
product prose does not enter it. Where re-frame2's own pattern is affected, the
touchpoint is freshly authored there — `spec/009-Instrumentation.md`'s Hicasso
section is the standing example, and it is a normative statement in root `spec/`'s
own voice rather than a relocated page.

The split between those four homes was ruled by the operator on 2026-08-21
under `rf2-ps7ia`.
