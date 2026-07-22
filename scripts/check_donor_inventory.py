#!/usr/bin/env python3
"""Coverage gate for the Freehand donor-inventory ledger.

The ledger — `spec/conformance/freehand/donor-inventory.md` — enumerates every
`re-frame.ui` row that the Freehand programme must MOVE, REPLACE, or DELETE, and
is the artifact the "absorption completeness" release gate reads. A ledger is
only worth its exhaustiveness, so this script proves two things:

  1. **No donor file is invisible.** Every git-tracked file under the DONOR TREE
     (`implementation/ui/`) is matched by exactly one ledger row. A new donor
     file added without a ledger row fails; a row whose pattern no longer
     matches anything (and which still claims to be pending) fails as stale.

  2. **Every row is decidable.** Each row names a disposition from the closed
     set {MOVE, REPLACE, DELETE}, an owning programme slice (F0-F6), and a
     status from {pending, done}.

It also reports the number of rows not yet disposed — the count the programme
drives to zero before the standalone donor artifact is deleted.

## Ledger format

Any Markdown table whose header row begins with `| Donor row |` is parsed. Its
five columns are, in order:

    | Donor row | What it is | Disposition | Slice | Status |

`Donor row` is either

  * a **path row** — a backticked repo-relative path or glob
    (`` `implementation/ui/src/re_frame/ui/tree.cljc` ``,
    `` `implementation/ui/test/re_frame/ui/reactive_*` ``). `*` matches any
    run of characters INCLUDING `/`; or
  * a **label row** — free prose naming a contract-level obligation that is not
    a single file (`` `local` and its placement machinery ``). Label rows are
    validated for their disposition/slice/status columns only.

A cell is treated as a path row when its first backticked token starts with one
of the repo roots in `REPO_ROOTS`.

## Two coverage regimes

The DONOR TREE is a CLOSED universe: its path rows must PARTITION the tracked
file set — every tracked file matched exactly once, no double-claiming, no
stale pattern. Everything else (spec obligations, `tools/` consumers, examples,
docs) is an OPEN roster: those rows must point at something that exists, but the
ledger does not claim to enumerate every file of those trees.

A `done` row is exempt from the "must match something" rule in both regimes:
disposing a row is frequently what deletes or renames its files, and the ledger
keeps the completed row as the audit record.

Exit code:
    0  the ledger covers the donor tree and every row is decidable
    1  coverage or well-formedness defect (printed; ::error:: under --ci)
    2  invocation / setup error (repo root, ledger, or git not found)

Dependency-light — Python stdlib only.
"""

from __future__ import annotations

import argparse
import fnmatch
import re
import subprocess
import sys
from pathlib import Path

# --- Ledger location ----------------------------------------------------------
LEDGER_REL = "spec/conformance/freehand/donor-inventory.md"

# --- The closed donor universe ------------------------------------------------
# Every git-tracked file under this prefix must be claimed by exactly one row.
DONOR_TREE = "implementation/ui/"

# --- Vocabulary ---------------------------------------------------------------
DISPOSITIONS = ("MOVE", "REPLACE", "DELETE")
STATUSES = ("pending", "done")
SLICE_RE = re.compile(r"^F[0-6][a-z]?$")

# A `Donor row` cell counts as a PATH row when its backticked token starts with
# one of these. Anything else is a contract-level label row.
REPO_ROOTS = (
    ".github/",
    "docs/",
    "examples/",
    "implementation/",
    "migration/",
    "scripts/",
    "skills/",
    "spec/",
    "tools/",
)

HEADER_PREFIX = "| Donor row |"

_BACKTICKED = re.compile(r"`([^`]+)`")
_TABLE_ROW = re.compile(r"^\s*\|(.*)\|\s*$")
_DIVIDER = re.compile(r"^\s*\|[\s:|-]+\|\s*$")
_SECTION = re.compile(r"^##+\s+(.*?)\s*$")


class SetupError(Exception):
    """Repo root, ledger, or git is unusable — not a ledger defect."""


class Row:
    """One parsed ledger row."""

    __slots__ = ("cell", "pattern", "what", "disposition", "slice_", "status",
                 "lineno", "section")

    def __init__(self, cell, pattern, what, disposition, slice_, status,
                 lineno, section):
        self.cell = cell
        self.pattern = pattern          # None for a label row
        self.what = what
        self.disposition = disposition
        self.slice_ = slice_
        self.status = status
        self.lineno = lineno
        self.section = section

    @property
    def is_donor_tree(self) -> bool:
        return self.pattern is not None and self.pattern.startswith(DONOR_TREE)

    def __repr__(self) -> str:  # pragma: no cover - debugging aid
        return f"Row({self.cell!r}, {self.disposition}, {self.slice_}, {self.status})"


def _split_cells(line: str) -> list[str]:
    inner = _TABLE_ROW.match(line).group(1)
    return [cell.strip() for cell in inner.split("|")]


def _classify(cell: str) -> str | None:
    """Return the path/glob a `Donor row` cell names, or None for a label row."""
    match = _BACKTICKED.search(cell)
    if not match:
        return None
    token = match.group(1).strip()
    if token.startswith(REPO_ROOTS):
        return token
    return None


def parse_ledger(text: str) -> tuple[list[Row], list[str]]:
    """Parse every `| Donor row |` table. Returns (rows, malformed-row problems)."""
    rows: list[Row] = []
    problems: list[str] = []
    section = "(no section)"
    in_table = False
    in_fence = False

    for index, line in enumerate(text.splitlines()):
        lineno = index + 1
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue

        heading = _SECTION.match(line)
        if heading:
            section = heading.group(1)
            in_table = False
            continue

        if line.startswith(HEADER_PREFIX):
            in_table = True
            continue
        if not in_table:
            continue
        if _DIVIDER.match(line):
            continue
        if not _TABLE_ROW.match(line):
            in_table = False
            continue

        cells = _split_cells(line)
        if len(cells) != 5:
            problems.append(
                f"DONOR-LEDGER-MALFORMED: {LEDGER_REL}:{lineno} has {len(cells)} "
                "columns; a donor row needs exactly 5 "
                "(Donor row | What it is | Disposition | Slice | Status)."
            )
            continue

        cell, what, disposition, slice_, status = cells
        row_problems = []
        if disposition not in DISPOSITIONS:
            row_problems.append(
                f"disposition {disposition!r} is not one of "
                + "/".join(DISPOSITIONS)
            )
        if not SLICE_RE.match(slice_):
            row_problems.append(
                f"slice {slice_!r} is not a programme slice (F0-F6, optional "
                "lowercase sub-slice letter)"
            )
        if status not in STATUSES:
            row_problems.append(
                f"status {status!r} is not one of " + "/".join(STATUSES)
            )
        if not what:
            row_problems.append("the 'What it is' column is empty")
        for problem in row_problems:
            problems.append(
                f"DONOR-LEDGER-ROW: {LEDGER_REL}:{lineno} ({cell}) — {problem}."
            )
        if row_problems:
            continue

        rows.append(
            Row(cell, _classify(cell), what, disposition, slice_, status,
                lineno, section)
        )

    return rows, problems


def tracked_files(repo_root: Path) -> list[str]:
    """git-tracked paths, repo-root-relative, forward-slashed."""
    try:
        out = subprocess.run(
            ["git", "ls-files", "-z"],
            cwd=repo_root, capture_output=True, check=True,
        ).stdout.decode("utf-8")
    except (OSError, subprocess.CalledProcessError) as exc:
        raise SetupError(f"could not list tracked files with git: {exc}") from exc
    return [entry for entry in out.split("\0") if entry]


def check_rows(rows: list[Row], tracked: list[str]) -> list[str]:
    """Coverage problems: unclaimed donor files, double claims, stale patterns."""
    problems: list[str] = []
    donor_files = [path for path in tracked if path.startswith(DONOR_TREE)]

    # Which rows claim each donor file.
    claims: dict[str, list[Row]] = {path: [] for path in donor_files}
    for row in rows:
        if not row.is_donor_tree:
            continue
        for path in donor_files:
            if fnmatch.fnmatchcase(path, row.pattern):
                claims[path].append(row)

    for path in donor_files:
        owners = claims[path]
        if not owners:
            problems.append(
                f"DONOR-LEDGER-UNCOVERED: {path} is a tracked donor file that no "
                f"row of {LEDGER_REL} claims. Every donor file needs an explicit "
                "MOVE / REPLACE / DELETE disposition before it can silently "
                "survive (or silently vanish) at the retirement gate."
            )
        elif len(owners) > 1:
            where = ", ".join(f"line {row.lineno} ({row.cell})" for row in owners)
            problems.append(
                f"DONOR-LEDGER-DOUBLE-CLAIMED: {path} is claimed by "
                f"{len(owners)} rows — {where}. Donor-tree rows must partition "
                "the tree so each file has exactly one disposition."
            )

    # Stale patterns: a still-pending row whose pattern matches nothing.
    for row in rows:
        if row.pattern is None or row.status == "done":
            continue
        universe = donor_files if row.is_donor_tree else tracked
        if not any(fnmatch.fnmatchcase(path, row.pattern) for path in universe):
            problems.append(
                f"DONOR-LEDGER-STALE: {LEDGER_REL}:{row.lineno} claims "
                f"`{row.pattern}`, which matches no tracked file. Either the "
                "path moved (fix the row) or the row was disposed (set its "
                "status to `done`)."
            )

    return problems


def undisposed(rows: list[Row]) -> list[Row]:
    return [row for row in rows if row.status == "pending"]


def format_report(rows: list[Row]) -> str:
    """Human-readable undisposed-row report."""
    pending = undisposed(rows)
    lines = [
        f"donor inventory: {len(rows)} rows, {len(pending)} not yet disposed.",
        "",
        "undisposed by slice:",
    ]
    by_slice: dict[str, int] = {}
    for row in pending:
        by_slice[row.slice_] = by_slice.get(row.slice_, 0) + 1
    for slice_ in sorted(by_slice):
        lines.append(f"  {slice_}: {by_slice[slice_]}")
    lines.append("")
    lines.append("undisposed by disposition:")
    by_disposition: dict[str, int] = {}
    for row in pending:
        by_disposition[row.disposition] = by_disposition.get(row.disposition, 0) + 1
    for disposition in DISPOSITIONS:
        if disposition in by_disposition:
            lines.append(f"  {disposition}: {by_disposition[disposition]}")
    lines.append("")
    lines.append("undisposed by section:")
    by_section: dict[str, int] = {}
    for row in pending:
        by_section[row.section] = by_section.get(row.section, 0) + 1
    for section in sorted(by_section):
        lines.append(f"  {section}: {by_section[section]}")
    return "\n".join(lines)


def check(repo_root: Path, *, verbose: bool = False, ci: bool = False) -> tuple[int, list[Row]]:
    ledger = repo_root / LEDGER_REL
    if not ledger.is_file():
        raise SetupError(f"ledger not found at {LEDGER_REL} under {repo_root}")

    rows, problems = parse_ledger(ledger.read_text(encoding="utf-8"))
    if verbose:
        sys.stderr.write(f"parsed {len(rows)} ledger rows from {LEDGER_REL}\n")
    problems.extend(check_rows(rows, tracked_files(repo_root)))

    for problem in problems:
        prefix = "::error::" if ci else ""
        sys.stderr.write(f"{prefix}{problem}\n")

    return len(problems), rows


# --- Self-tests ---------------------------------------------------------------
_FIXTURE_HEADER = (
    "| Donor row | What it is | Disposition | Slice | Status |\n"
    "|---|---|---|---|---|\n"
)


def _run_self_tests(*, verbose: bool = False) -> int:
    failures = 0

    def expect(label: str, got, want) -> None:
        nonlocal failures
        if got != want:
            failures += 1
            sys.stderr.write(
                f"self-test FAILED [{label}]: expected {want!r}, got {got!r}\n"
            )
        elif verbose:
            sys.stderr.write(f"self-test ok [{label}]\n")

    tracked = [
        "implementation/ui/src/re_frame/ui.cljc",
        "implementation/ui/src/re_frame/ui/tree.cljc",
        "implementation/ui/test/re_frame/ui/tree_jvm_test.clj",
        "tools/story/deps.edn",
        "spec/004-Views.md",
    ]

    full = _FIXTURE_HEADER + (
        "| `implementation/ui/src/re_frame/ui.cljc` | facade | REPLACE | F1 | pending |\n"
        "| `implementation/ui/src/re_frame/ui/tree.cljc` | JVM tree | MOVE | F1 | pending |\n"
        "| `implementation/ui/test/*` | donor tests | MOVE | F6 | pending |\n"
        "| `tools/story/deps.edn` | consumer coord | MOVE | F6 | pending |\n"
        "| `local` and its placement machinery | donor form | DELETE | F1 | pending |\n"
    )
    rows, malformed = parse_ledger(full)
    expect("A1 row count", len(rows), 5)
    expect("A2 no malformed rows", malformed, [])
    expect("A3 clean coverage", check_rows(rows, tracked), [])
    expect("A4 label row is not a path row", rows[4].pattern, None)
    expect("A5 undisposed count", len(undisposed(rows)), 5)

    # A missing donor file must be named by the failure.
    missing = _FIXTURE_HEADER + (
        "| `implementation/ui/src/re_frame/ui.cljc` | facade | REPLACE | F1 | pending |\n"
        "| `implementation/ui/test/*` | donor tests | MOVE | F6 | pending |\n"
    )
    rows, _ = parse_ledger(missing)
    problems = check_rows(rows, tracked)
    expect("B1 uncovered detected", len(problems), 1)
    expect(
        "B2 uncovered names the file",
        "implementation/ui/src/re_frame/ui/tree.cljc" in problems[0],
        True,
    )
    expect("B3 uncovered is the right class", problems[0].startswith(
        "DONOR-LEDGER-UNCOVERED"), True)

    # Two rows claiming one file is a defect.
    doubled = _FIXTURE_HEADER + (
        "| `implementation/ui/src/*` | everything | MOVE | F1 | pending |\n"
        "| `implementation/ui/src/re_frame/ui/tree.cljc` | JVM tree | MOVE | F1 | pending |\n"
        "| `implementation/ui/test/*` | donor tests | MOVE | F6 | pending |\n"
    )
    rows, _ = parse_ledger(doubled)
    problems = check_rows(rows, tracked)
    expect("C1 double claim detected", len(problems), 1)
    expect("C2 double claim class", problems[0].startswith(
        "DONOR-LEDGER-DOUBLE-CLAIMED"), True)

    # A pending row matching nothing is stale; the same row marked done is not.
    stale = _FIXTURE_HEADER + (
        "| `implementation/ui/src/*` | everything | MOVE | F1 | pending |\n"
        "| `implementation/ui/test/*` | donor tests | MOVE | F6 | pending |\n"
        "| `tools/gone/deps.edn` | vanished | MOVE | F6 | pending |\n"
    )
    rows, _ = parse_ledger(stale)
    problems = check_rows(rows, tracked)
    expect("D1 stale detected", len(problems), 1)
    expect("D2 stale class", problems[0].startswith("DONOR-LEDGER-STALE"), True)

    disposed = stale.replace("| `tools/gone/deps.edn` | vanished | MOVE | F6 | pending |",
                             "| `tools/gone/deps.edn` | vanished | MOVE | F6 | done |")
    rows, _ = parse_ledger(disposed)
    expect("D3 done row is not stale", check_rows(rows, tracked), [])
    expect("D4 done row is disposed", len(undisposed(rows)), 2)

    # Column vocabulary.
    bad = _FIXTURE_HEADER + (
        "| `implementation/ui/src/*` | all | ABSORB | F1 | pending |\n"
        "| `implementation/ui/test/*` | tests | MOVE | F9 | pending |\n"
        "| `tools/story/deps.edn` | coord | MOVE | F6 | maybe |\n"
        "| `spec/004-Views.md` |  | MOVE | F0 | pending |\n"
    )
    rows, malformed = parse_ledger(bad)
    expect("E1 every bad row rejected", len(rows), 0)
    expect("E2 four defects", len(malformed), 4)
    expect("E3 bad disposition named", "ABSORB" in malformed[0], True)
    expect("E4 bad slice named", "F9" in malformed[1], True)
    expect("E5 bad status named", "maybe" in malformed[2], True)

    # Wrong arity and fenced tables.
    arity = _FIXTURE_HEADER + "| `spec/004-Views.md` | too | few |\n"
    _, malformed = parse_ledger(arity)
    expect("F1 arity defect", len(malformed), 1)
    expect("F2 arity class", malformed[0].startswith("DONOR-LEDGER-MALFORMED"), True)

    fenced = (
        "```\n" + _FIXTURE_HEADER
        + "| `implementation/ui/src/x.cljc` | ex | MOVE | F1 | pending |\n"
        + "```\n"
    )
    rows, malformed = parse_ledger(fenced)
    expect("F3 fenced example ignored", (len(rows), len(malformed)), (0, 0))

    # Sections are tracked for the report.
    sectioned = (
        "## Donor sources\n\n" + _FIXTURE_HEADER
        + "| `implementation/ui/src/*` | all | MOVE | F1 | pending |\n"
        + "\n## Donor tests\n\n" + _FIXTURE_HEADER
        + "| `implementation/ui/test/*` | tests | MOVE | F6 | pending |\n"
    )
    rows, _ = parse_ledger(sectioned)
    expect("G1 sections captured",
           [row.section for row in rows], ["Donor sources", "Donor tests"])
    expect("G2 report mentions both sections",
           "Donor sources: 1" in format_report(rows)
           and "Donor tests: 1" in format_report(rows), True)

    if failures:
        sys.stderr.write(f"self-test: {failures} case(s) failed.\n")
        return 1
    sys.stderr.write("self-test: all cases passed.\n")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Prove the Freehand donor-inventory ledger covers every tracked "
            "re-frame.ui file with an explicit MOVE/REPLACE/DELETE disposition, "
            "and report how many rows are not yet disposed."
        ),
    )
    parser.add_argument(
        "--repo-root",
        default=None,
        help="Path to the repo root. Defaults to the script's grandparent.",
    )
    parser.add_argument(
        "--verbose", "-v", action="store_true", help="Print progress to stderr."
    )
    parser.add_argument(
        "--ci", action="store_true", help="Emit GitHub-Actions ::error:: annotations."
    )
    parser.add_argument(
        "--report",
        action="store_true",
        help="Print the undisposed-row report to stdout (still fails on defects).",
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
            "(no mkdocs.yml). Pass --repo-root explicitly.\n"
        )
        return 2

    try:
        defects, rows = check(repo_root, verbose=args.verbose, ci=args.ci)
    except SetupError as exc:
        sys.stderr.write(f"error: {exc}\n")
        return 2

    if args.report or args.verbose:
        sys.stdout.write(format_report(rows) + "\n")

    return 0 if defects == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
