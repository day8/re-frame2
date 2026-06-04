#!/usr/bin/env python3
"""Cite-integrity guard: re-frame-migration skill M-id cites vs MIGRATION.md (rf2-1nb8k).

The migration skill (`skills/re-frame-migration/`) treats MIGRATION.md
(`migration/from-re-frame-v1/README.md`) as the source of truth and instructs
the agent to cite a rule id (`M-NN`) for every rewrite it applies (SKILL.md
cardinal rule 1). An author audits a migration by grepping MIGRATION.md for the
cited id and reading the rule.

That contract silently breaks when a skill cites an `M-NN` that names a
DIFFERENT rule in MIGRATION.md — or no rule at all. rf2-1nb8k was exactly this:
the skill cited M-66/M-67 for the listener-namespace + frame-affordance renames,
but MIGRATION.md M-66/M-67 were assigned to History-states / Xray-static-mode,
and the two renames lived under no id at all. The report became un-auditable.

This guard makes that class of drift a build failure. Three checks:

  * PHANTOM-CITE — the skill cites an `M-NN` (or `M-NNa` sub-rule) that has no
    matching heading in MIGRATION.md. Always a failure: the cite points at
    nothing the author can grep for.

  * RULE-MISMATCH — for the small set of rules whose identity is asserted by
    both the skill and MIGRATION.md (`KEYWORD_RULES` below), the MIGRATION.md
    heading for the cited id must contain the expected keyword. This catches the
    *collision* half of the rf2-1nb8k class (skill cites M-66 = "listener", but
    MIGRATION.md M-66 = "History states") without trying to diff full prose.

  * TYPE-DRIFT (rf2-640aq) — every rule MIGRATION.md classifies as **Type B** in
    its canonical "## Type-tag summary" section MUST also appear in the skill's
    "## Type A vs Type B — at a glance" **Type B** line in
    `references/breaking-changes.md`. A Type-B rule silently dropping out of (or
    never reaching) the skill's Type-B list is the rf2-640aq class: the skill
    then presents a judgment-call migration (M-34 spawn-id tracking, M-42
    `dom-node` / `force-update-all`, M-40 `init!` adapter) as automatic, and an
    agent applies it without the required human review. We assert *containment*
    (every MIGRATION.md Type-B id is in the skill's Type-B list) rather than set
    equality, because the skill legitimately splits hybrid A/B rules across both
    at-a-glance lines and MIGRATION.md's Type-B summary is the authority for what
    MUST be flagged.

MIGRATION.md "defines" an id when it carries an `### M-NN.` (H3, top-level rule)
or `#### M-NNa.` (H4, sub-rule like M-31a/M-31b) heading. The skill "cites" an
id wherever `M-NN` / `M-NNa` appears in any `references/*.md` file.

Exit code:
    0  no drift detected
    1  drift detected (printed line-by-line; GitHub-Actions ::error:: style
       under CI)
    2  invocation / setup error

Usage:
    python scripts/check_skill_migration_cites.py
    python scripts/check_skill_migration_cites.py --verbose
    python scripts/check_skill_migration_cites.py --ci         # tighter output
                                                               #   (auto under
                                                               #   GITHUB_ACTIONS)
    python scripts/check_skill_migration_cites.py --self-test  # run on built-in
                                                               #   pass/fail
                                                               #   fixtures

rf2-1nb8k.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path
from typing import Iterable

REPO_ROOT = Path(__file__).resolve().parent.parent

# Force UTF-8 on output streams — the corpus carries → / em-dash etc. and the
# default Windows console codec (cp1252) would crash on them (rf2 is maintained
# on Windows; the gate also runs on Linux CI).
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")  # type: ignore[union-attr]
    except (AttributeError, ValueError):  # pragma: no cover - non-TextIO stream
        pass

MIGRATION_MD = REPO_ROOT / "migration" / "from-re-frame-v1" / "README.md"
SKILL_REFERENCES_DIR = REPO_ROOT / "skills" / "re-frame-migration" / "references"
SKILL_BREAKING_CHANGES = SKILL_REFERENCES_DIR / "breaking-changes.md"

# TYPE-DRIFT (rf2-640aq) section anchors. MIGRATION.md's canonical Type-tag
# summary lives under a `## Type-tag summary` H2; the Type-B membership line in
# that section opens with `**Type B — flag for human review.**`. (The same
# bold phrase also opens per-rule bodies, so we only scan inside the summary
# section.) The skill's at-a-glance lives under `## Type A vs Type B` and its
# Type-B membership line opens with `**Type B — ask before applying.**`.
MIGRATION_TYPE_SUMMARY_HEADING = "## Type-tag summary"
MIGRATION_TYPE_B_MARKER = "**Type B — flag for human review.**"
SKILL_AT_A_GLANCE_HEADING = "## Type A vs Type B"
SKILL_TYPE_B_MARKER = "**Type B — ask before applying.**"

# Heading that DEFINES a rule id in MIGRATION.md. H3 for top-level rules
# (### M-26.), H4 for sub-rules (#### M-31a.). The trailing `.` is required so
# we don't treat an in-prose mention like "see M-26" as a definition.
HEADING_RE = re.compile(r"^#{3,4}\s+(M-\d+[a-z]?)\.\s", re.MULTILINE)

# Any mention of an id, in skill prose or tables. Word-boundaried so M-6 does
# not match inside M-66. The optional trailing letter captures sub-rules.
CITE_RE = re.compile(r"\bM-\d+[a-z]?\b")

# Identifies the breaking-changes.md table row that DEFINES a rule's id↔rule
# binding — the cell `| **M-NN** |`. We only run the RULE-MISMATCH keyword check
# against these rows (not free prose, and not the multi-id Type-A summary line,
# which mentions every rule's keyword on one line).
TABLE_ID_RE = re.compile(r"\|\s*\*\*(M-\d+[a-z]?)\*\*\s*\|")

# RULE-MISMATCH check. When a breaking-changes.md table-row's DESCRIPTION text
# contains a `trigger` keyword, MIGRATION.md's heading for the row's cited id
# MUST contain the paired keyword (case-insensitive). This pins the identity of
# the rules most prone to the rf2-1nb8k collision class without diffing full
# rule prose. Extend as new rename-style rules are added to the skill table.
#
#   row-description-trigger  ->  (cited-id-must-have-heading-containing, ...)
KEYWORD_RULES: dict[str, tuple[str, ...]] = {
    "frame-affordance redesign": ("frame-affordance",),
    "listener-registration namespace consolidation": (
        "listener-registration namespace",
    ),
}


def _slurp(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def defined_ids(migration_text: str) -> dict[str, str]:
    """Map every MIGRATION.md-defined id -> its full heading line."""
    out: dict[str, str] = {}
    for line in migration_text.splitlines():
        m = HEADING_RE.match(line)
        if m:
            out[m.group(1)] = line.strip()
    return out


def skill_cites(references_dir: Path) -> list[tuple[Path, int, str, str]]:
    """Every (file, line-no, cited-id, full-line) tuple across references/*.md."""
    cites: list[tuple[Path, int, str, str]] = []
    for md in sorted(references_dir.glob("*.md")):
        for lineno, line in enumerate(_slurp(md).splitlines(), start=1):
            for m in CITE_RE.finditer(line):
                cites.append((md, lineno, m.group(0), line))
    return cites


def find_drift(
    migration_text: str,
    cites: Iterable[tuple[Path, int, str, str]],
) -> list[str]:
    """Return human-readable drift messages (empty == clean)."""
    defined = defined_ids(migration_text)
    problems: list[str] = []

    # PHANTOM-CITE — every cited id must have a defining heading. Report each
    # distinct (file, id) once, not per-mention, so a heavily-cited phantom
    # doesn't bury the output.
    seen_phantom: set[tuple[Path, str]] = set()
    for path, lineno, cited, _line in cites:
        if cited in defined:
            continue
        key = (path, cited)
        if key in seen_phantom:
            continue
        seen_phantom.add(key)
        rel = path.relative_to(REPO_ROOT)
        problems.append(
            f"PHANTOM-CITE: {rel}:{lineno} cites {cited}, which has no "
            f"`### {cited}.` / `#### {cited}.` heading in MIGRATION.md."
        )

    # RULE-MISMATCH — only on breaking-changes.md table rows that bind an id to
    # a rule. The row's `| **M-NN** |` cell names the id; the row text carries
    # the rule description. When the description fires a KEYWORD_RULES trigger,
    # the cited id's MIGRATION.md heading must match the paired keyword.
    for path, lineno, _cited, line in cites:
        m = TABLE_ID_RE.search(line)
        if not m:
            continue
        row_id = m.group(1)
        if row_id not in defined:
            continue  # already reported as PHANTOM-CITE
        heading = defined[row_id].lower()
        low = line.lower()
        rel = path.relative_to(REPO_ROOT)
        for trigger, expected_any in KEYWORD_RULES.items():
            if trigger in low and not any(
                kw.lower() in heading for kw in expected_any
            ):
                expected_disp = " / ".join(repr(k) for k in expected_any)
                problems.append(
                    f"RULE-MISMATCH: {rel}:{lineno} binds {row_id} to a row "
                    f"describing '{trigger}', but MIGRATION.md's {row_id} heading "
                    f"({defined[row_id]!r}) names a different rule — expected the "
                    f"heading to contain {expected_disp}."
                )
    return problems


def _ids_in_marked_line(text: str, *, section_heading: str, marker: str) -> set[str]:
    """Collect M-ids from the single line that opens with `marker`, searched
    only AFTER `section_heading` and before the next `## ` H2. Returns the empty
    set if the section or marked line is absent (the caller turns that into a
    SETUP-style drift message — the anchors must exist for the guard to work)."""
    lines = text.splitlines()
    in_section = False
    for line in lines:
        if line.startswith("## "):
            in_section = line.strip().startswith(section_heading)
            continue
        # Tolerate an optional Markdown list bullet ('- ' or '* ') before the
        # bold marker — MIGRATION.md renders the Type-tag summary as a
        # `- **Type X ...` list, the skill's at-a-glance as a bare `**Type X ...`
        # paragraph. Strip only a bullet+space, never the `**` of the bold run.
        stripped = line.lstrip()
        if stripped[:2] in ("- ", "* "):
            stripped = stripped[2:]
        if in_section and stripped.startswith(marker):
            return set(CITE_RE.findall(line))
    return set()


def find_type_drift(migration_text: str, breaking_changes_text: str) -> list[str]:
    """TYPE-DRIFT (rf2-640aq): every MIGRATION.md Type-tag-summary Type-B id must
    appear in the skill's at-a-glance Type-B line. Returns drift messages."""
    problems: list[str] = []
    rel = SKILL_BREAKING_CHANGES.relative_to(REPO_ROOT)

    migration_type_b = _ids_in_marked_line(
        migration_text,
        section_heading=MIGRATION_TYPE_SUMMARY_HEADING,
        marker=MIGRATION_TYPE_B_MARKER,
    )
    skill_type_b = _ids_in_marked_line(
        breaking_changes_text,
        section_heading=SKILL_AT_A_GLANCE_HEADING,
        marker=SKILL_TYPE_B_MARKER,
    )

    if not migration_type_b:
        problems.append(
            "TYPE-DRIFT setup: could not locate MIGRATION.md's "
            f"'{MIGRATION_TYPE_B_MARKER}' line under '{MIGRATION_TYPE_SUMMARY_HEADING}'. "
            "The Type-B classification authority anchor moved — update the guard."
        )
        return problems
    if not skill_type_b:
        problems.append(
            f"TYPE-DRIFT setup: could not locate the skill's '{SKILL_TYPE_B_MARKER}' "
            f"line under '{SKILL_AT_A_GLANCE_HEADING}' in {rel}. "
            "The at-a-glance anchor moved — update the guard or the skill."
        )
        return problems

    missing = sorted(migration_type_b - skill_type_b, key=_id_sort_key)
    for mid in missing:
        problems.append(
            f"TYPE-DRIFT: MIGRATION.md classifies {mid} as Type B (must be "
            f"flagged for human review), but {rel}'s at-a-glance Type-B line "
            f"omits it — the skill would present {mid} as automatic. Add {mid} to "
            "the Type-B at-a-glance line (and its table row / sequencing entry)."
        )
    return problems


def run(*, verbose: bool, ci: bool) -> int:
    if not MIGRATION_MD.is_file():
        sys.stderr.write(f"error: MIGRATION.md not found at {MIGRATION_MD}\n")
        return 2
    if not SKILL_REFERENCES_DIR.is_dir():
        sys.stderr.write(
            f"error: skill references dir not found at {SKILL_REFERENCES_DIR}\n"
        )
        return 2
    if not SKILL_BREAKING_CHANGES.is_file():
        sys.stderr.write(
            f"error: breaking-changes.md not found at {SKILL_BREAKING_CHANGES}\n"
        )
        return 2

    migration_text = _slurp(MIGRATION_MD)
    cites = skill_cites(SKILL_REFERENCES_DIR)
    defined = defined_ids(migration_text)
    problems = find_drift(migration_text, cites)
    problems += find_type_drift(migration_text, _slurp(SKILL_BREAKING_CHANGES))

    if verbose:
        distinct = sorted({c[2] for c in cites}, key=_id_sort_key)
        print(
            f"MIGRATION.md defines {len(defined)} rule ids; "
            f"skill references cite {len(distinct)} distinct ids "
            f"across {len(cites)} mentions."
        )

    if not problems:
        if verbose:
            print("cite-integrity: all skill M-id cites resolve consistently.")
        return 0

    err_prefix = "::error::" if ci else ""
    for p in problems:
        print(f"{err_prefix}{p}")
    print(
        f"\ncite-integrity: {len(problems)} drift issue(s) — "
        "every skill-cited M-id must name a consistent rule in MIGRATION.md."
    )
    return 1


def _id_sort_key(mid: str) -> tuple[int, str]:
    m = re.match(r"M-(\d+)([a-z]?)", mid)
    return (int(m.group(1)), m.group(2)) if m else (10**9, mid)


# ---------------------------------------------------------------------------
# Self-test — exercises both checks against in-memory fixtures so the guard
# itself can't silently rot. Mirrors the --self-test convention in
# check_doc_slugs.py.
# ---------------------------------------------------------------------------

def _self_test() -> int:
    failures = 0

    good_migration = (
        "### M-68. Frame-affordance redesign — renames\n\n"
        "body\n\n"
        "### M-69. Listener-registration namespace consolidation\n\n"
        "body\n\n"
        "#### M-31a. Canned-stub fxs require test-support\n\n"
        "body\n"
    )

    def cite(line: str) -> list[tuple[Path, int, str, str]]:
        out = []
        for m in CITE_RE.finditer(line):
            out.append((SKILL_REFERENCES_DIR / "x.md", 1, m.group(0), line))
        return out

    # Case A — clean: table rows bind the right ids + a sub-rule cite resolves.
    probs = find_drift(
        good_migration,
        cite("| `surf` | **M-68** | A | Frame-affordance redesign (rf2-kkut0). |")
        + cite("| `x` | **M-69** | A | Listener-registration namespace consolidation. |")
        + cite("M-31a applies here"),
    )
    if probs:
        print(f"SELF-TEST FAIL (A clean): unexpected {probs}")
        failures += 1

    # Case B — phantom cite: M-67 not defined in this MIGRATION.md.
    probs = find_drift(good_migration, cite("drop them (M-67); injected locals"))
    if not any("PHANTOM-CITE" in p for p in probs):
        print(f"SELF-TEST FAIL (B phantom): expected PHANTOM-CITE, got {probs}")
        failures += 1

    # Case C — rule-mismatch: a frame-affordance ROW binds the listener id M-69.
    probs = find_drift(
        good_migration,
        cite("| `surf` | **M-69** | A | Frame-affordance redesign (rf2-kkut0). |"),
    )
    if not any("RULE-MISMATCH" in p for p in probs):
        print(f"SELF-TEST FAIL (C mismatch): expected RULE-MISMATCH, got {probs}")
        failures += 1

    # Case D — the multi-id Type-A summary line (every keyword on one line, NOT a
    # `| **M-NN** |` binding row) must NOT trip RULE-MISMATCH.
    summary = (
        "M-68 (frame-affordance redesign renames), "
        "M-69 (listener-namespace consolidation)."
    )
    probs = find_drift(good_migration, cite(summary))
    if any("RULE-MISMATCH" in p for p in probs):
        print(f"SELF-TEST FAIL (D summary): false RULE-MISMATCH, got {probs}")
        failures += 1

    # Case E — word-boundary: M-6 must not match inside M-68.
    ids = [c[2] for c in cite("see M-68 here")]
    if ids != ["M-68"]:
        print(f"SELF-TEST FAIL (E boundary): expected ['M-68'], got {ids}")
        failures += 1

    # TYPE-DRIFT fixtures (rf2-640aq).
    type_migration = (
        "## Type-tag summary\n\n"
        "- **Type A — fully mechanical.** Rules: M-1, M-35.\n"
        "- **Type B — flag for human review.** Rules: M-34, M-40, M-42.\n\n"
        "## Next section\n\n**Type B — flag for human review.** "
        "(a per-rule body line that must NOT be scanned) M-99.\n"
    )

    # Case F — clean: skill Type-B list contains every MIGRATION.md Type-B id.
    skill_clean = (
        "## Type A vs Type B — at a glance\n\n"
        "**Type A — apply automatically.** M-1, M-35.\n\n"
        "**Type B — ask before applying.** M-34, M-40, M-42.\n"
    )
    probs = find_type_drift(type_migration, skill_clean)
    if probs:
        print(f"SELF-TEST FAIL (F type clean): unexpected {probs}")
        failures += 1

    # Case G — drift: skill Type-B list drops M-42 (the rf2-640aq class).
    skill_drift = (
        "## Type A vs Type B — at a glance\n\n"
        "**Type A — apply automatically.** M-1, M-35, M-42.\n\n"
        "**Type B — ask before applying.** M-34, M-40.\n"
    )
    probs = find_type_drift(type_migration, skill_drift)
    if not any("TYPE-DRIFT" in p and "M-42" in p for p in probs):
        print(f"SELF-TEST FAIL (G type drift): expected TYPE-DRIFT on M-42, got {probs}")
        failures += 1

    # Case H — the M-99 in the NEXT section's per-rule body line must not leak
    # into the authoritative Type-B set (section scoping).
    if any("M-99" in p for p in find_type_drift(type_migration, skill_clean)):
        print("SELF-TEST FAIL (H type scope): M-99 leaked across the section boundary")
        failures += 1

    if failures:
        print(f"self-test: {failures} failure(s).")
        return 1
    print("self-test: all cases passed.")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--verbose", action="store_true", help="print summary")
    parser.add_argument(
        "--ci",
        action="store_true",
        help="GitHub-Actions ::error:: prefix (auto on under GITHUB_ACTIONS)",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="run built-in fixtures instead of scanning the repo",
    )
    args = parser.parse_args(argv)

    if args.self_test:
        return _self_test()

    ci = args.ci or bool(os.environ.get("GITHUB_ACTIONS"))
    return run(verbose=args.verbose, ci=ci)


if __name__ == "__main__":
    raise SystemExit(main())
