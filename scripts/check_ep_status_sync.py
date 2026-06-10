#!/usr/bin/env python3
"""Freshness check: docs/EP/README.md index statuses match EP metadata.

Each Enhancement Proposal under docs/EP/ carries a single source-of-truth
`Status:` line in its front-matter (e.g. `Status: accepted`).  The
docs/EP/README.md `## Index` table restates that status in its `Status`
column.  The two drift apart by hand, so the guard keeps the README as an
index rather than a second source of truth.

This guard re-derives the README index status for every EP from the per-EP
`Status:` line and validates EP-0009's process metadata grammar: statuses are a
closed set (plus `superseded-by EP-NNNN`) and new EPs carry a `Type:` line.
Wire it into a doc gate so drift fails before it ships.

What is checked:
    * STATUS MISMATCH  — README index status column != the EP file's Status: line.
    * INVALID STATUS   — Status is outside EP-0009's vocabulary.
    * INVALID TYPE     — Type is outside EP-0009's vocabulary.
    * NO TYPE          — A post-EP-0005 file has no Type: line.
    * MISSING ROW      — an EP-NNNN-*.md file has no matching README index row.
    * ORPHAN ROW       — a README index row links a file that does not exist.

Exit code:
    0  index and EP metadata are valid
    1  at least one mismatch / metadata defect / missing row / orphan row
    2  invocation / setup error

The script is dependency-light — Python stdlib only.
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

# EP source files are named EP-NNNN-<slug>.md and carry one `Status: <word>`
# line in their front-matter.  README.md is the index, not an EP.
_EP_FILE_RE = re.compile(r"^EP-(\d{4})-.+\.md$")

# The per-EP front-matter status line: `Status: accepted` (leading whitespace
# tolerated, value runs to end-of-line, trimmed).  `superseded-by EP-NNNN`
# deliberately contains a space.
_STATUS_LINE_RE = re.compile(r"^\s*Status:\s*(\S.*?)\s*$", re.MULTILINE)

_TYPE_LINE_RE = re.compile(r"^\s*Type:\s*(\S.*?)\s*$", re.MULTILINE)

_ALLOWED_STATUSES = {
    "proposal",
    "accepted",
    "final",
    "active",
    "rejected",
    "withdrawn",
    "deferred",
}
_SUPERSEDED_STATUS_RE = re.compile(r"^superseded-by EP-\d{4}$")
_ALLOWED_TYPES = {"standards-track", "process"}

# EP-0001..EP-0005 predate EP-0009's Type: header.  EP-0006 and later must
# carry Type explicitly so the process can distinguish standards-track/final
# from process/active.
_TYPE_REQUIRED_FROM_EP = 6

# A README `## Index` table body row.  The table is:
#   | EP | Title | Status | Summary |
# with the first cell a markdown link `[EP-NNNN](EP-NNNN-<slug>.md)`.  We
# capture the linked filename and the third (Status) column.  Cell contents
# are trimmed by the caller.
_INDEX_ROW_RE = re.compile(
    r"^\|\s*\[EP-(\d{4})\]\(([^)]+)\)\s*\|"   # col 1: EP link -> (number, filename)
    r"[^|]*\|"                                  # col 2: Title (ignored)
    r"\s*([^|]*?)\s*\|"                        # col 3: Status (captured)
    r".*\|\s*$"                                 # col 4+: Summary etc.
)


def _ep_dir(repo_root: Path) -> Path:
    return repo_root / "docs" / "EP"


def _read_ep_status(path: Path) -> str | None:
    """Return the EP file's declared Status: token, or None if absent."""
    text = path.read_text(encoding="utf-8", errors="replace")
    m = _STATUS_LINE_RE.search(text)
    return m.group(1).strip() if m else None


def _read_ep_type(path: Path) -> str | None:
    """Return the EP file's declared Type: token, or None if absent."""
    text = path.read_text(encoding="utf-8", errors="replace")
    m = _TYPE_LINE_RE.search(text)
    return m.group(1).strip() if m else None


def _valid_status(status: str) -> bool:
    return status in _ALLOWED_STATUSES or bool(_SUPERSEDED_STATUS_RE.match(status))


def _read_index_rows(readme: Path) -> dict[str, tuple[str, str, int]]:
    """Parse the README index table.

    Returns a mapping: filename -> (ep_number, status, line_number).
    Only rows inside fenced code blocks are skipped (defensive — the real
    index table is not fenced).
    """
    rows: dict[str, tuple[str, str, int]] = {}
    in_fence = False
    for line_no, raw in enumerate(
        readme.read_text(encoding="utf-8", errors="replace").splitlines(), start=1
    ):
        if raw.lstrip().startswith(("```", "~~~")):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        m = _INDEX_ROW_RE.match(raw)
        if not m:
            continue
        ep_number, filename, status = m.group(1), m.group(2).strip(), m.group(3).strip()
        rows[filename] = (ep_number, status, line_no)
    return rows


def check(repo_root: Path, verbose: bool = False) -> int:
    """Validate README index statuses against per-EP Status: lines.

    Returns the number of defects found (0 == in sync).
    """
    ep_dir = _ep_dir(repo_root)
    readme = ep_dir / "README.md"
    if not readme.is_file():
        sys.stderr.write(f"error: no EP index at {readme}\n")
        return 1

    ep_files = sorted(
        p for p in ep_dir.iterdir() if p.is_file() and _EP_FILE_RE.match(p.name)
    )
    index_rows = _read_index_rows(readme)

    if verbose:
        sys.stderr.write(
            f"checking {len(ep_files)} EP file(s) against "
            f"{len(index_rows)} index row(s) in {readme.relative_to(repo_root)}\n"
        )

    mismatches: list[str] = []
    metadata_defects: list[str] = []
    missing_rows: list[str] = []
    orphan_rows: list[str] = []

    indexed_filenames = set(index_rows)
    ep_filenames = {p.name for p in ep_files}

    for ep in ep_files:
        ep_match = _EP_FILE_RE.match(ep.name)
        assert ep_match is not None
        ep_number = int(ep_match.group(1))

        declared = _read_ep_status(ep)
        if declared is None:
            mismatches.append(
                f"  NO STATUS: {ep.relative_to(repo_root)} has no `Status:` line"
            )
        elif not _valid_status(declared):
            metadata_defects.append(
                f"  INVALID STATUS: {ep.relative_to(repo_root)} declares "
                f"`Status: {declared}`; expected one of "
                "`proposal`, `accepted`, `final`, `active`, `rejected`, "
                "`withdrawn`, `deferred`, or `superseded-by EP-NNNN`"
            )

        declared_type = _read_ep_type(ep)
        if declared_type is None:
            if ep_number >= _TYPE_REQUIRED_FROM_EP:
                metadata_defects.append(
                    f"  NO TYPE: {ep.relative_to(repo_root)} has no `Type:` "
                    "line; EP-0006 and later must declare `standards-track` "
                    "or `process`"
                )
        elif declared_type not in _ALLOWED_TYPES:
            metadata_defects.append(
                f"  INVALID TYPE: {ep.relative_to(repo_root)} declares "
                f"`Type: {declared_type}`; expected `standards-track` or "
                "`process`"
            )

        if declared is None:
            continue
        row = index_rows.get(ep.name)
        if row is None:
            missing_rows.append(
                f"  MISSING ROW: {ep.name} (Status: {declared}) has no README index row"
            )
            continue
        _, index_status, line_no = row
        if index_status != declared:
            mismatches.append(
                f"  STATUS MISMATCH: docs/EP/README.md:{line_no} lists "
                f"{ep.name} as '{index_status}' but the EP's Status: line is "
                f"'{declared}'"
            )

    for filename in sorted(indexed_filenames - ep_filenames):
        _, _, line_no = index_rows[filename]
        orphan_rows.append(
            f"  ORPHAN ROW: docs/EP/README.md:{line_no} links {filename} "
            "but no such EP file exists"
        )

    defects = mismatches + metadata_defects + missing_rows + orphan_rows
    if defects:
        sys.stderr.write(
            f"\n{len(defects)} EP status-sync defect(s) found:\n\n"
        )
        for line in defects:
            sys.stderr.write(line + "\n")
        sys.stderr.write(
            "\nFix: update the docs/EP/README.md `## Index` Status column to "
            "match each EP's `Status:` line (the per-EP line is the source of "
            "truth), add/remove the index row, or correct the EP front-matter "
            "to match EP-0009's status/type grammar.\n"
        )
    elif verbose:
        sys.stderr.write("EP index statuses are in sync.\n")

    return len(defects)


# --------------------------------------------------------------------------
# Self-tests — fixture-driven, mirroring scripts/check_doc_slugs.py's style
# but generated into a temp dir so the self-test leaves zero scratch files in
# the repo tree (the fixtures are trivial enough to mint in-process; there is
# nothing for a human to hand-edit, so committing static copies would just be
# a second source of truth that can drift from the generator below).
#
# Each fixture is a self-contained mini-repo: a mkdocs.yml at the root (so the
# repo-root guard accepts it) plus a docs/EP/ tree.
# --------------------------------------------------------------------------


def _write_fixture(root: Path, ep_files: dict[str, str], readme: str) -> None:
    ep_dir = root / "docs" / "EP"
    ep_dir.mkdir(parents=True, exist_ok=True)
    (root / "mkdocs.yml").write_text("site_name: fixture\n", encoding="utf-8")
    for name, body in ep_files.items():
        (ep_dir / name).write_text(body, encoding="utf-8")
    (ep_dir / "README.md").write_text(readme, encoding="utf-8")


def _build_self_test_fixtures(base: Path) -> None:
    """Generate the fixtures under base so the self-tests are hermetic."""

    def ep(num: str, status: str, ep_type: str | None = None) -> str:
        type_line = f"Type: {ep_type}\n" if ep_type is not None else ""
        return f"# EP-{num}: Fixture\n\nStatus: {status}\n{type_line}\n## Abstract\n\nx\n"

    def index(*rows: str) -> str:
        head = (
            "# EPs\n\n## Index\n\n"
            "| EP | Title | Status | Summary |\n"
            "|----|-------|--------|---------|\n"
        )
        return head + "".join(rows) + "\n"

    def row(num: str, status: str, fname: str | None = None) -> str:
        fname = fname or f"EP-{num}-fixture.md"
        return f"| [EP-{num}]({fname}) | Fixture | {status} | s. |\n"

    # in_sync: index matches each EP's Status: line.
    _write_fixture(
        base / "in_sync",
        {"EP-0001-fixture.md": ep("0001", "accepted"),
         "EP-0002-fixture.md": ep("0002", "proposal"),
         "EP-0006-fixture.md": ep("0006", "proposal", "standards-track"),
         "EP-0007-fixture.md": ep("0007", "superseded-by EP-0008", "process")},
        index(
            row("0001", "accepted"),
            row("0002", "proposal"),
            row("0006", "proposal"),
            row("0007", "superseded-by EP-0008"),
        ),
    )

    # status_mismatch: EP says accepted, index says proposal.
    _write_fixture(
        base / "status_mismatch",
        {"EP-0001-fixture.md": ep("0001", "accepted")},
        index(row("0001", "proposal")),
    )

    # missing_row: EP file exists but has no index row.
    _write_fixture(
        base / "missing_row",
        {"EP-0001-fixture.md": ep("0001", "accepted"),
         "EP-0002-fixture.md": ep("0002", "final")},
        index(row("0001", "accepted")),
    )

    # orphan_row: index links an EP file that does not exist.
    _write_fixture(
        base / "orphan_row",
        {"EP-0001-fixture.md": ep("0001", "accepted")},
        index(row("0001", "accepted"), row("0002", "final")),
    )

    # invalid_status: README matches the invalid value, but EP-0009 rejects it.
    _write_fixture(
        base / "invalid_status",
        {"EP-0006-fixture.md": ep("0006", "done", "standards-track")},
        index(row("0006", "done")),
    )

    # missing_type_new_ep: EP-0006+ must carry a Type: line.
    _write_fixture(
        base / "missing_type_new_ep",
        {"EP-0006-fixture.md": ep("0006", "proposal")},
        index(row("0006", "proposal")),
    )

    # invalid_type: Type is a closed EP-0009 vocabulary.
    _write_fixture(
        base / "invalid_type",
        {"EP-0006-fixture.md": ep("0006", "proposal", "banana")},
        index(row("0006", "proposal")),
    )


def _run_self_tests(verbose: bool = False) -> int:
    cases: list[tuple[str, int]] = [
        ("in_sync", 0),
        ("status_mismatch", 1),
        ("missing_row", 1),
        ("orphan_row", 1),
        ("invalid_status", 1),
        ("missing_type_new_ep", 1),
        ("invalid_type", 1),
    ]
    failures = 0
    with tempfile.TemporaryDirectory(prefix="ep_status_sync_selftest_") as tmp:
        base = Path(tmp)
        _build_self_test_fixtures(base)
        for fixture, expected in cases:
            root = base / fixture
            saved_stderr = sys.stderr
            sys.stderr = _DevNull()
            try:
                got = check(root, verbose=False)
            finally:
                sys.stderr = saved_stderr
            if got == expected:
                if verbose:
                    sys.stderr.write(f"self-test PASS: {fixture} (defects={got})\n")
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
            "Check docs/EP/README.md index statuses and EP metadata grammar."
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
