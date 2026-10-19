# Fresco (`day8/re-frame2-fresco`) — spec

The **operative contract** for `re-frame.fresco`: the documents another artefact
has to obey, kept beside the artefact they govern and amended in the same PR as
any change that touches them. That is the convention the `tools/*/spec/` trees
already follow, and this tree is its first instance under `implementation/`.

**This tree governs.** Where a docstring, a guide chapter or a page under
`docs/design/fresco/` differs from it, this tree is right and the other text is
the defect.

Nothing here is a published page — `implementation/**` is outside `mkdocs.yml`'s
`docs_dir`, so no part of this tree is staged into the site or validated by
`scripts/check_doc_slugs.py`. Link targets and heading anchors are validated by
`scripts/check_readme_links.py --ci` instead, which reaches README and non-README
markdown beside source. Nothing validates prose, tables or rendering; whoever
edits a table verifies its column count by hand and says so.

## Files

- [`authoring-contract.md`](authoring-contract.md) — **the contract**: what the
  package promises about the boundary ABI, reads, intents and carriers, the
  controlled-input door, frame heads, roots and hydration, the interop door,
  and the incarnation rule. Start here.
- [`complaints.md`](complaints.md) — the index of Fresco's diagnostic ids: one
  line per id the package raises, with the guide chapter that teaches how not to
  hit it. It does not restate what a complaint MEANS — that is
  [`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md)'s
  Fresco section, which is also the single record of which ids exist and
  which are retired (a struck-through row is the tombstone).
- [`invariants.md`](invariants.md) — the fifteen invariants the product commits
  to, one row each, with a pointer to the design reasoning behind it.
- [`server-policy.md`](server-policy.md) — every public surface's permanent
  inventory id and its server policy, Render or Client-only, in one 43-row table.

## The gates

| Document | Checker | Where it runs |
|---|---|---|
| `authoring-contract.md` | none of its own — prose, held to the code by review; its links are `../../../scripts/check_readme_links.py --ci`'s | the `verify-readme-links` job |
| `complaints.md` | none of its own since rf2-6c12m.7 retired `check_complaint_catalogue.py`; the ids its rows name are the ones `../../../scripts/check_keyword_catalogue_drift.py` reconciles against Spec 009 and the emitters, in both directions | the repo-wide keyword-drift gate in `.github/workflows/test.yml`, and `scripts/test-fast-pr.sh` |
| `invariants.md` I8 | `check_optional_module_reachability.py` enforces the reachability clause against the source and cites the row | `npm run test:fresco-invariants` |
| `server-policy.md` | none — a reference table, kept by whoever changes a policy | — |

## What is not here

The **design record** lives at
[`docs/design/fresco/`](../../../docs/design/fresco/README.md): the decisions
and their reasoning, the specification and lanes it was built from, the
ledgers, verdicts, pilots and measurements. It explains why the contract is what
it is, and it does not govern: a ruling there reaches the package by landing
here. It is not gated, and it is read by whoever needs the history rather than
by a checker.

The **teaching and how-to material** is the published guide at
[`docs/core/fresco/`](../../../docs/core/fresco/index.md), in the MkDocs nav.

The **framework-level pattern** is root [`spec/`](../../../spec/README.md), and
product prose does not enter it. Where re-frame2's own pattern is affected, the
touchpoint is freshly authored there — `spec/009-Instrumentation.md`'s Fresco
section is the standing example, and it is a normative statement in root `spec/`'s
own voice rather than a relocated page.
