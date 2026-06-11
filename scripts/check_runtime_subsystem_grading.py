#!/usr/bin/env python3
"""Conformance drift gate: every reserved :rf.runtime/* subsystem key has a
complete five-clause grading row in spec/Runtime-Subsystems.md.

EP-0006 finalised the Runtime Subsystem Contract — the five-clause shape every
framework-owned durable-state subtree under the runtime-db partition satisfies
(subtree / write authority / read API / projection-elision / teardown).  Two
spec surfaces co-describe the shipped subsystems and MUST stay in lockstep:

    * spec/Conventions.md §Reserved runtime-db keys — the CANONICAL reserved
      `:rf.runtime/*` key set (clause 1's home; the grading table references
      it rather than duplicating).
    * spec/Runtime-Subsystems.md §Grading table — one `### :rf.runtime/<name>`
      subsection per reserved key, each carrying a five-row grading table that
      grades the subsystem against all five clauses.

They drift apart by hand: a new subsystem lands a Conventions reserved-key row
but its grading subsection is forgotten (or vice versa); a grading table loses
a clause row; a clause is left ungraded.  PR #3817 (spec-coherence) had to
hand-add the `:rf.runtime/mutations` grading row after it was reserved without
one.  This guard keeps the two in sync so future drift fails loudly.

What is checked:
    * MISSING ROW    — a reserved `:rf.runtime/*` key (Conventions) has no
                       `### :rf.runtime/<name>` grading subsection.
    * EXTRA ROW      — a grading subsection names a `:rf.runtime/*` key that is
                       not in the Conventions reserved-key set.
    * MISSING CLAUSE — a grading subsection's table omits one of the five
                       clauses (1 Subtree / 2 Write authority / 3 Read API /
                       4 Projection / elision / 5 Teardown).
    * CLAUSE ORDER   — the five clauses appear out of canonical 1..5 order.
    * UNGRADED CLAUSE— a clause row carries no grade (no ✅ in the Grade cell;
                       an empty / TODO / `-` grade is drift).

Exit code:
    0  Conventions reserved-key set and the grading table are in lockstep
    1  at least one drift defect (printed in source-anchored form)
    2  invocation / setup error

The script is dependency-light — Python stdlib only.  It is wired into the
docs gate (.github/workflows/docs.yml) and the local PR spine
(scripts/test-fast-pr.sh) alongside the other spec-coherence guards.

Tracked: rf2-ba5acq (EP-0006 open erratum — the conformance drift test).
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

# --------------------------------------------------------------------------
# The five clauses, in canonical order.  Each entry is (ordinal, canonical
# label).  The grading-table row's bold lead cell is matched leniently against
# the label so a future wording tweak ("4 Projection / elision policy") still
# resolves to clause 4 — the ORDINAL and the leading keyword are what bind.
#
# Source of truth: spec/Runtime-Subsystems.md §The five-clause contract
# (headings "### 1. Subtree" … "### 5. Teardown") and the per-subsystem
# grading tables that grade against them.
# --------------------------------------------------------------------------
FIVE_CLAUSES: tuple[tuple[int, str], ...] = (
    (1, "Subtree"),
    (2, "Write authority"),
    (3, "Read API"),
    (4, "Projection / elision"),
    (5, "Teardown"),
)
EXPECTED_ORDINALS = tuple(o for o, _ in FIVE_CLAUSES)

# A reserved-key row in Conventions §Reserved runtime-db keys.  The table is:
#   | Reserved runtime-db key | Owner | Used for | Spec |
# with the first cell a backticked `:rf.runtime/<name>` keyword.  We capture
# the key name.  Rows whose first cell is not a `:rf.runtime/*` keyword (the
# header, the `:rf.runtime/*` wildcard summary row in the partition table) do
# not match.
_RESERVED_KEY_ROW_RE = re.compile(
    r"^\|\s*`(:rf\.runtime/[a-z][a-z0-9-]*)`\s*\|"
)

# A grading subsection heading: `### \`:rf.runtime/<name>\`` — possibly with a
# trailing " — <prose> ([Spec NNN](...))".  We capture the key name.
_GRADING_HEADING_RE = re.compile(
    r"^###\s+`(:rf\.runtime/[a-z][a-z0-9-]*)`"
)

# A grading-table body row.  The table is `| Clause | Grade |`; the Clause cell
# leads with `**<ordinal> <label>**`.  We capture the ordinal, the label text,
# and the full Grade cell.  Header / separator rows do not match (they have no
# `**N ` lead).
_GRADING_ROW_RE = re.compile(
    r"^\|\s*\*\*\s*(\d+)\s+([^*]+?)\s*\*\*\s*\|\s*(.*?)\s*\|\s*$"
)

# A markdown heading at any level — used to bound a grading subsection (a
# subsection runs until the next heading of equal-or-shallower depth).
_ANY_HEADING_RE = re.compile(r"^(#{1,6})\s+")

# The grading table lives under this H2.  We only harvest `### :rf.runtime/...`
# subsections that fall inside it, so the doc's other `### N. <clause>` headings
# (the five-clause-contract prose) and any future `:rf.runtime/*`-mentioning
# prose headings elsewhere never get mistaken for grading rows.
_GRADING_SECTION_HEADING = "Grading table"


def _conventions_path(repo_root: Path) -> Path:
    return repo_root / "spec" / "Conventions.md"


def _runtime_subsystems_path(repo_root: Path) -> Path:
    return repo_root / "spec" / "Runtime-Subsystems.md"


def _strip_fences(lines: list[str]) -> list[tuple[int, str]]:
    """Return (1-based line-number, content) with fenced code blanked.

    Mirrors scripts/check_doc_slugs.py: lines inside a ``` / ~~~ fence are
    blanked (numbering preserved) so a table-shaped example inside a code
    block is never parsed as a real table row.
    """
    out: list[tuple[int, str]] = []
    in_fence = False
    marker = ""
    for i, raw in enumerate(lines, start=1):
        stripped = raw.lstrip()
        if stripped.startswith(("```", "~~~")):
            tok = stripped[:3]
            if not in_fence:
                in_fence, marker = True, tok
            elif tok == marker:
                in_fence, marker = False, ""
            out.append((i, ""))
            continue
        out.append((i, "" if in_fence else raw))
    return out


def _reserved_keys(conventions: Path) -> dict[str, int]:
    """Parse the Conventions §Reserved runtime-db keys table.

    Returns an ordered mapping `:rf.runtime/<name>` -> line-number for every
    reserved subsystem key in the *reserved-key table* (the canonical clause-1
    home).  Only the dedicated reserved-key table is harvested — its rows are
    the per-child rows whose first cell is a bare `:rf.runtime/<name>` keyword.
    The `:rf.runtime/*` wildcard summary row in the earlier partition-key table
    has a `*` (not `[a-z]`) so the regex skips it.
    """
    text = conventions.read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()

    # Bound the scan to the "## Reserved runtime-db keys" section so an
    # unrelated future table mentioning a `:rf.runtime/...` key cannot inject a
    # phantom reserved key.  The section runs from its H2 to the next H2.
    start = None
    end = len(lines)
    for idx, raw in enumerate(lines):
        if raw.strip() == "## Reserved runtime-db keys":
            start = idx
            break
    if start is None:
        # Section heading not found — treat as a setup defect; the caller
        # surfaces it.  Return empty so the caller's emptiness guard fires.
        return {}
    for idx in range(start + 1, len(lines)):
        if lines[idx].startswith("## "):
            end = idx
            break

    keys: dict[str, int] = {}
    for line_no, content in _strip_fences(lines):
        if line_no - 1 < start or line_no - 1 >= end:
            continue
        m = _RESERVED_KEY_ROW_RE.match(content)
        if m:
            key = m.group(1)
            # First occurrence wins for the line-anchor; a key should not be
            # duplicated, but be defensive.
            keys.setdefault(key, line_no)
    return keys


def _grading_subsections(
    runtime_doc: Path,
) -> dict[str, tuple[int, list[tuple[int, int, str, str]]]]:
    """Parse the Runtime-Subsystems.md grading table.

    Returns an ordered mapping `:rf.runtime/<name>` -> (heading-line,
    [(row-line, ordinal, label, grade-cell), ...]) for every grading
    subsection inside the "## Grading table" section.

    Each subsection is the `### :rf.runtime/<name>` heading plus the grading
    rows that follow it, up to the next heading of equal-or-shallower depth
    (### or ##).
    """
    text = runtime_doc.read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()
    pairs = _strip_fences(lines)

    # Find the "## Grading table" section span.
    grading_start = None
    grading_end = len(lines)
    for idx, raw in enumerate(lines):
        if raw.startswith("## ") and _GRADING_SECTION_HEADING in raw:
            grading_start = idx
            break
    if grading_start is not None:
        for idx in range(grading_start + 1, len(lines)):
            if lines[idx].startswith("## "):
                grading_end = idx
                break

    subsections: dict[str, tuple[int, list[tuple[int, int, str, str]]]] = {}
    if grading_start is None:
        return subsections

    current_key: str | None = None
    current_rows: list[tuple[int, int, str, str]] = []
    current_heading_line = 0

    def flush() -> None:
        nonlocal current_key, current_rows, current_heading_line
        if current_key is not None:
            subsections[current_key] = (current_heading_line, current_rows)
        current_key, current_rows, current_heading_line = None, [], 0

    for line_no, content in pairs:
        idx0 = line_no - 1
        if idx0 < grading_start or idx0 >= grading_end:
            continue

        hm = _ANY_HEADING_RE.match(content)
        if hm:
            depth = len(hm.group(1))
            gm = _GRADING_HEADING_RE.match(content)
            if gm:
                flush()
                current_key = gm.group(1)
                current_heading_line = line_no
                continue
            # Any other heading at depth <= 3 closes an open subsection (a
            # deeper #### inside a subsection, if ever added, would not).
            if depth <= 3:
                flush()
            continue

        if current_key is None:
            continue
        rm = _GRADING_ROW_RE.match(content)
        if rm:
            ordinal = int(rm.group(1))
            label = rm.group(2).strip()
            grade = rm.group(3).strip()
            current_rows.append((line_no, ordinal, label, grade))

    flush()
    return subsections


def _grade_satisfied(grade_cell: str) -> bool:
    """A clause is graded iff its Grade cell carries the ✅ satisfied mark.

    The grading-table legend (Runtime-Subsystems.md) defines ✅ as "the clause
    is satisfied with a named mechanism".  An empty cell, a `-`, a `TODO`, or a
    `❌`/`⚠️`-only cell is NOT a satisfied grade.  (A ⚠️ may legitimately ANNOTATE
    a satisfied clause — e.g. work-ledger clause 2's open multi-writer
    forward-flag — so we require ✅ to be PRESENT, not that the cell be ⚠️-free.)
    """
    return "✅" in grade_cell  # ✅


def check(repo_root: Path, verbose: bool = False) -> int:
    """Validate the grading table against the reserved-key set.

    Returns the number of drift defects found (0 == in lockstep).
    """
    conventions = _conventions_path(repo_root)
    runtime_doc = _runtime_subsystems_path(repo_root)

    if not conventions.is_file():
        sys.stderr.write(f"error: no Conventions doc at {conventions}\n")
        return 1
    if not runtime_doc.is_file():
        sys.stderr.write(f"error: no Runtime-Subsystems doc at {runtime_doc}\n")
        return 1

    reserved = _reserved_keys(conventions)
    grading = _grading_subsections(runtime_doc)

    defects: list[str] = []

    # Emptiness guards — if either harvester found nothing, the doc structure
    # the parser keys off has moved.  Fail loud rather than silently passing.
    if not reserved:
        defects.append(
            "  NO RESERVED KEYS: could not parse any `:rf.runtime/*` row from "
            f"{conventions.relative_to(repo_root)} §Reserved runtime-db keys "
            "(the table shape or heading changed — update the parser)"
        )
    if not grading:
        defects.append(
            "  NO GRADING ROWS: could not parse any `### :rf.runtime/<name>` "
            f"grading subsection from {runtime_doc.relative_to(repo_root)} "
            "§Grading table (the table shape or heading changed — update the "
            "parser)"
        )
    if defects:  # structural break — further comparison would be noise.
        _emit(defects, repo_root)
        return len(defects)

    if verbose:
        sys.stderr.write(
            f"checking {len(reserved)} reserved `:rf.runtime/*` key(s) against "
            f"{len(grading)} grading subsection(s)\n"
        )

    rel_conv = conventions.relative_to(repo_root)
    rel_rt = runtime_doc.relative_to(repo_root)

    # 1. Reserved key with no grading subsection.
    for key, line_no in reserved.items():
        if key not in grading:
            defects.append(
                f"  MISSING ROW: {rel_conv}:{line_no} reserves `{key}` but "
                f"{rel_rt} §Grading table has no `### {key}` grading subsection"
            )

    # 2. Grading subsection for a non-reserved key.
    for key, (heading_line, _rows) in grading.items():
        if key not in reserved:
            defects.append(
                f"  EXTRA ROW: {rel_rt}:{heading_line} grades `{key}` but it is "
                f"not in {rel_conv} §Reserved runtime-db keys"
            )

    # 3 + 4 + 5. Per-subsection clause completeness / order / grade.
    for key in grading:
        if key not in reserved:
            continue  # already flagged as EXTRA; skip clause checks.
        heading_line, rows = grading[key]
        ordinals = [ordn for (_ln, ordn, _lbl, _g) in rows]

        # MISSING CLAUSE — every expected ordinal 1..5 must be present.
        present = set(ordinals)
        for ordn in EXPECTED_ORDINALS:
            if ordn not in present:
                _label = next(lbl for (o, lbl) in FIVE_CLAUSES if o == ordn)
                defects.append(
                    f"  MISSING CLAUSE: {rel_rt}:{heading_line} `{key}` grading "
                    f"table omits clause {ordn} ({_label})"
                )

        # CLAUSE ORDER — the five clauses must appear in canonical 1..5 order.
        # Only assert order over the rows that ARE the five canonical clauses
        # (a stray extra numbered row is reported separately below).
        canonical_seq = [o for o in ordinals if o in EXPECTED_ORDINALS]
        if canonical_seq != sorted(canonical_seq):
            defects.append(
                f"  CLAUSE ORDER: {rel_rt}:{heading_line} `{key}` grading table "
                f"clauses appear out of order: {canonical_seq} (expected "
                f"ascending 1..5)"
            )

        # Unexpected ordinal (e.g. a clause "6" or "0") — fixed-five contract.
        for (ln, ordn, lbl, _g) in rows:
            if ordn not in EXPECTED_ORDINALS:
                defects.append(
                    f"  UNEXPECTED CLAUSE: {rel_rt}:{ln} `{key}` grading table "
                    f"has a clause {ordn} ({lbl!r}); the contract is exactly "
                    "five clauses (1..5)"
                )

        # UNGRADED CLAUSE — each canonical clause row must carry a ✅ grade.
        for (ln, ordn, lbl, grade) in rows:
            if ordn in EXPECTED_ORDINALS and not _grade_satisfied(grade):
                defects.append(
                    f"  UNGRADED CLAUSE: {rel_rt}:{ln} `{key}` clause {ordn} "
                    f"({lbl}) has no ✅ satisfied grade (grade cell: "
                    f"{grade[:60]!r})"
                )

    if defects:
        _emit(defects, repo_root)
    elif verbose:
        sys.stderr.write(
            "runtime-subsystem grading table and reserved-key set are in "
            "lockstep.\n"
        )

    return len(defects)


def _emit(defects: list[str], repo_root: Path) -> None:
    sys.stderr.write(
        f"\n{len(defects)} runtime-subsystem grading drift defect(s) found:\n\n"
    )
    for line in defects:
        sys.stderr.write(line + "\n")
    sys.stderr.write(
        "\nFix: every reserved `:rf.runtime/*` key (spec/Conventions.md "
        "§Reserved runtime-db keys) MUST have a matching `### :rf.runtime/<name>` "
        "grading subsection in spec/Runtime-Subsystems.md §Grading table with a "
        "complete five-clause table (1 Subtree / 2 Write authority / 3 Read API "
        "/ 4 Projection-elision / 5 Teardown), each clause graded ✅.  Add the "
        "reserved-key row, the grading subsection, or the missing clause so the "
        "two surfaces stay in lockstep (EP-0006 Runtime Subsystem Contract).\n"
    )


# --------------------------------------------------------------------------
# Self-tests — fixture-driven, generated into a temp dir so the self-test
# leaves zero scratch files in the repo (mirrors check_ep_status_sync.py).
#
# Each fixture is a minimal two-file pair (spec/Conventions.md +
# spec/Runtime-Subsystems.md) under a mkdocs.yml root, exercising the
# pass case and each drift class the guard must catch.
# --------------------------------------------------------------------------

_CLAUSE_ROWS_OK = (
    "| **1 Subtree** | ✅ named subtree |\n"
    "| **2 Write authority** | ✅ event-handler path |\n"
    "| **3 Read API** | ✅ public subs |\n"
    "| **4 Projection / elision** | ✅ allowlist |\n"
    "| **5 Teardown** | ✅ named hook |\n"
)


def _conventions_fixture(*keys: str) -> str:
    head = (
        "# Conventions\n\n"
        "## Reserved partition keys\n\n"
        "| Reserved app-db key | Owner | Used for | Spec |\n"
        "|---|---|---|---|\n"
        "| `:rf.runtime/*` | Runtime-db children | wildcard summary | 002 |\n\n"
        "## Reserved runtime-db keys\n\n"
        "| Reserved runtime-db key | Owner | Used for | Spec |\n"
        "|---|---|---|---|\n"
    )
    rows = "".join(f"| `{k}` | owner | use | 005 |\n" for k in keys)
    # A trailing H2 so the section-bounding logic has a clean close.
    return head + rows + "\n## Next section\n\nx\n"


def _grading_fixture(rows_by_key: dict[str, str]) -> str:
    head = (
        "# Runtime Subsystems\n\n"
        "## The five-clause contract\n\n"
        "### 1. Subtree\n\nprose\n\n"
        "### 5. Teardown\n\nprose\n\n"
        "## Grading table\n\n"
    )
    body = ""
    for key, clause_rows in rows_by_key.items():
        body += (
            f"### `{key}` — fixture ([Spec 005](005-StateMachines.md))\n\n"
            "| Clause | Grade |\n|---|---|\n"
            f"{clause_rows}\n"
        )
    return head + body + "## Cross-references\n\nx\n"


def _write_pair(root: Path, conventions: str, grading: str) -> None:
    spec = root / "spec"
    spec.mkdir(parents=True, exist_ok=True)
    (root / "mkdocs.yml").write_text("site_name: fixture\n", encoding="utf-8")
    (spec / "Conventions.md").write_text(conventions, encoding="utf-8")
    (spec / "Runtime-Subsystems.md").write_text(grading, encoding="utf-8")


def _build_self_test_fixtures(base: Path) -> None:
    a, b = ":rf.runtime/machines", ":rf.runtime/routing"

    # in_sync: two keys, both graded clean.
    _write_pair(
        base / "in_sync",
        _conventions_fixture(a, b),
        _grading_fixture({a: _CLAUSE_ROWS_OK, b: _CLAUSE_ROWS_OK}),
    )

    # missing_row: reserved key with no grading subsection.
    _write_pair(
        base / "missing_row",
        _conventions_fixture(a, b),
        _grading_fixture({a: _CLAUSE_ROWS_OK}),
    )

    # extra_row: grading subsection for a non-reserved key.
    _write_pair(
        base / "extra_row",
        _conventions_fixture(a),
        _grading_fixture({a: _CLAUSE_ROWS_OK, b: _CLAUSE_ROWS_OK}),
    )

    # missing_clause: a grading table omits clause 4.
    rows_no_4 = (
        "| **1 Subtree** | ✅ x |\n"
        "| **2 Write authority** | ✅ x |\n"
        "| **3 Read API** | ✅ x |\n"
        "| **5 Teardown** | ✅ x |\n"
    )
    _write_pair(
        base / "missing_clause",
        _conventions_fixture(a),
        _grading_fixture({a: rows_no_4}),
    )

    # ungraded_clause: clause 3 present but no ✅.
    rows_ungraded = (
        "| **1 Subtree** | ✅ x |\n"
        "| **2 Write authority** | ✅ x |\n"
        "| **3 Read API** | TODO |\n"
        "| **4 Projection / elision** | ✅ x |\n"
        "| **5 Teardown** | ✅ x |\n"
    )
    _write_pair(
        base / "ungraded_clause",
        _conventions_fixture(a),
        _grading_fixture({a: rows_ungraded}),
    )

    # clause_order: clauses out of 1..5 order (2 before 1).
    rows_misordered = (
        "| **2 Write authority** | ✅ x |\n"
        "| **1 Subtree** | ✅ x |\n"
        "| **3 Read API** | ✅ x |\n"
        "| **4 Projection / elision** | ✅ x |\n"
        "| **5 Teardown** | ✅ x |\n"
    )
    _write_pair(
        base / "clause_order",
        _conventions_fixture(a),
        _grading_fixture({a: rows_misordered}),
    )

    # warning_annotated_ok: a ⚠️ forward-flag ANNOTATES a ✅ clause — still
    # satisfied (work-ledger clause 2 shape).  Must PASS.
    rows_warned = (
        "| **1 Subtree** | ✅ x |\n"
        "| **2 Write authority** | ✅ for both writers ⚠️ OPEN multi-writer Q |\n"
        "| **3 Read API** | ✅ x |\n"
        "| **4 Projection / elision** | ✅ x |\n"
        "| **5 Teardown** | ✅ x |\n"
    )
    _write_pair(
        base / "warning_annotated_ok",
        _conventions_fixture(a),
        _grading_fixture({a: rows_warned}),
    )

    # fenced_table_ignored: a clause-shaped row inside a code fence in the
    # grading section must NOT be parsed as a real row (so the real, complete
    # table still passes).  Must PASS.
    grading_with_fence = (
        "# Runtime Subsystems\n\n## Grading table\n\n"
        f"### `{a}` — fixture ([Spec 005](005-StateMachines.md))\n\n"
        "```\n| **9 Bogus** | not-a-real-row |\n```\n\n"
        "| Clause | Grade |\n|---|---|\n"
        f"{_CLAUSE_ROWS_OK}\n"
        "## Cross-references\n\nx\n"
    )
    _write_pair(
        base / "fenced_table_ignored",
        _conventions_fixture(a),
        grading_with_fence,
    )


def _run_self_tests(verbose: bool = False) -> int:
    cases: list[tuple[str, int]] = [
        ("in_sync", 0),
        ("missing_row", 1),
        ("extra_row", 1),
        ("missing_clause", 1),
        ("ungraded_clause", 1),
        ("clause_order", 1),
        ("warning_annotated_ok", 0),
        ("fenced_table_ignored", 0),
    ]
    failures = 0
    with tempfile.TemporaryDirectory(prefix="runtime_grading_selftest_") as tmp:
        base = Path(tmp)
        _build_self_test_fixtures(base)
        for fixture, expected in cases:
            root = base / fixture
            saved = sys.stderr
            sys.stderr = _DevNull()
            try:
                got = check(root, verbose=False)
            finally:
                sys.stderr = saved
            if got == expected:
                if verbose:
                    sys.stderr.write(
                        f"self-test PASS: {fixture} (defects={got})\n"
                    )
            else:
                sys.stderr.write(
                    f"self-test FAIL: {fixture} expected defects={expected}, "
                    f"got {got}\n"
                )
                failures += 1
    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    if verbose:
        sys.stderr.write(f"all {len(cases)} self-tests passed.\n")
    return 0


class _DevNull:
    def write(self, *_args, **_kwargs) -> int:
        return 0

    def flush(self) -> None:  # pragma: no cover
        return None


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Check that every reserved :rf.runtime/* subsystem key has a "
            "complete five-clause grading row in spec/Runtime-Subsystems.md "
            "(rf2-ba5acq, EP-0006 Runtime Subsystem Contract)."
        ),
    )
    parser.add_argument(
        "--repo-root",
        default=None,
        help="Path to the repo root.  Defaults to the script's grandparent.",
    )
    parser.add_argument(
        "--verbose", "-v", action="store_true", help="Print progress to stderr."
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Run the bundled fixture-based self-tests and exit.",
    )
    args = parser.parse_args(argv)

    if args.self_test:
        return _run_self_tests(verbose=args.verbose)

    if args.repo_root:
        repo_root = Path(args.repo_root).resolve()
    else:
        repo_root = Path(__file__).resolve().parent.parent

    if not (repo_root / "mkdocs.yml").is_file():
        sys.stderr.write(
            f"error: {repo_root} does not look like the re-frame2 repo root "
            "(no mkdocs.yml).  Pass --repo-root explicitly.\n"
        )
        return 2

    defects = check(repo_root, verbose=args.verbose)
    return 0 if defects == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
