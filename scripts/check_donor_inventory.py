#!/usr/bin/env python3
"""Snapshot-integrity check for the ARCHIVED Freehand donor inventory.

`spec/conformance/freehand/donor-inventory.md` was the work-ledger of the
Freehand absorption programme: every `re-frame.ui` row the programme meant to
MOVE, REPLACE or DELETE, each naming the F0-F6 slice that owned it and whether
it had been disposed. EP-0036 was withdrawn on 2026-07-30, so the ledger is now
what its banner says it is — a snapshot of where absorption stood on the day the
programme stopped, preserved whole.

An archive needs a different guard from a work queue. The gate this script used
to be compared the ledger against the WORKING TREE: it partitioned
`implementation/ui/`, censused live donor consumers repo-wide, and failed a
still-pending row whose subject had moved out from under it. All three arms
measured a programme that no longer runs, and each of them enforced a rule about
TODAY's code through the historical `pending` statuses of a withdrawn one. They
are gone. A rule about today's code — that the donor must remain unpublished,
say, or that no new consumer of it may appear while EP-0038 runs — is a live
rule: it needs its own named authority, its own gate, and its own sunset.

What is left proves one thing, and reads nothing but the ledger itself: the
archive is still the archive.

  1. **No row is deleted.** `SNAPSHOT_ROWS` at the foot of this file pins the
     ledger as it stood at withdrawal. Every pinned identity must still be in
     the ledger. Deleting a row would quietly shrink the record of what the
     programme did not finish.

  2. **No row is added.** Every ledger row must be pinned. The ledger records a
     day that has passed, so a new row is either the transcription of something
     that was there — pin it in the same change; the diagnostic prints the line
     to paste — or a live claim, which does not belong in an archive.

  3. **No row drifts.** The pin is the COMPLETE normalized row — identity,
     disposition, owning slice, withdrawal-time status, and a digest of the
     description — not the identity alone. Identities alone would have left the
     interesting half of the record free to move: a status flipped to `done`, a
     MOVE quietly re-dispositioned, a description rewritten to say something the
     programme never decided.

  4. **Every row is still decidable.** Each row names a disposition from
     {MOVE, REPLACE, DELETE}, a slice (F0-F6, optional sub-slice letter), and a
     status from {pending, done}. The row grammar is still the row grammar, and
     the identities stay unique so the pin and the ledger name the same rows one
     for one.

SUNSET. HD-018 of EP-0038 (`docs/design/hicasso/decisions.md`) rules that a P2
win deletes the public `re-frame.freehand` and `re-frame.ui` surfaces outright,
leaving "no absorption programme, no donor inventory ledger". This check retires
WITH the snapshot it guards, in the change that deletes those surfaces. On a P2
loss or a null result both stay, as the historical record. Removing either
before P2 is premature, and any future donor policy is made under its own fresh
authority rather than by reviving F0-F6.

## Ledger format

Any Markdown table whose header row begins with `| Donor row |` is parsed. Its
five columns are, in order:

    | Donor row | What it is | Disposition | Slice | Status |

`Donor row` is either

  * a **path row** — a backticked repo-relative path or glob
    (`` `implementation/ui/src/re_frame/ui/tree.cljc` ``,
    `` `implementation/ui/test/re_frame/ui/reactive_*` ``); or
  * a **label row** — free prose naming a contract-level obligation that is not
    a single file (`` `local` and its placement machinery ``).

A cell is treated as a path row when its first backticked token starts with one
of the repo roots in `REPO_ROOTS`. A row's IDENTITY — the first field of its
`SNAPSHOT_ROWS` tuple — is its path pattern for a path row, and its whole cell
text for a label row. Nothing here resolves a path against the filesystem: the
patterns are the archive's own spelling of what it covered, and several of them
name files that the programme's own disposals have already removed.

Exit code:
    0  the ledger still matches the pinned snapshot, row for row
    1  the snapshot has drifted, or a row is malformed (printed; ::error::
       under --ci)
    2  invocation / setup error (repo root or ledger not found)

Dependency-light — Python stdlib only.
"""

from __future__ import annotations

import argparse
import hashlib
import re
import sys
from pathlib import Path

# --- Ledger location ----------------------------------------------------------
LEDGER_REL = "spec/conformance/freehand/donor-inventory.md"

# The day the snapshot records: EP-0036's withdrawal.
SNAPSHOT_DATE = "2026-07-30"

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

_WHITESPACE = re.compile(r"\s+")


class SetupError(Exception):
    """Repo root or ledger is unusable — not a snapshot defect."""


def normalize(text: str) -> str:
    """A description cell's canonical form: one space between words, trimmed.

    The digest is taken over this rather than the raw cell so that invisible
    whitespace churn is not a failure, while every word of the description is.
    """
    return _WHITESPACE.sub(" ", text).strip()


def digest(text: str) -> str:
    """The pinned fingerprint of a description cell.

    Twelve hex characters of SHA-256 over the normalized text: long enough that
    a collision is not a practical concern for 224 fixed strings, short enough
    that the pinned tuples stay readable. The alternative — pinning the
    descriptions verbatim — would duplicate 68 KB of the ledger inside its own
    checker and give two copies to keep in step.
    """
    return hashlib.sha256(normalize(text).encode("utf-8")).hexdigest()[:12]


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
    def key(self) -> str:
        """The row's stable identity — the first field of its pinned tuple."""
        return self.pattern if self.pattern is not None else self.cell

    @property
    def pinned(self) -> tuple[str, str, str, str, str]:
        """The complete normalized row, in the shape `SNAPSHOT_ROWS` records."""
        return (self.key, self.disposition, self.slice_, self.status,
                digest(self.what))

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
                f"DONOR-SNAPSHOT-MALFORMED: {LEDGER_REL}:{lineno} has {len(cells)} "
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
                f"DONOR-SNAPSHOT-ROW: {LEDGER_REL}:{lineno} ({cell}) — {problem}."
            )
        if row_problems:
            continue

        rows.append(
            Row(cell, _classify(cell), what, disposition, slice_, status,
                lineno, section)
        )

    return rows, problems


# --- The one check: the ledger still is the pinned snapshot -------------------
_FIELDS = ("disposition", "owning slice", "status", "description digest")


def check_snapshot(rows: list[Row],
                   snapshot: tuple[tuple[str, str, str, str, str], ...] | None
                   = None) -> list[str]:
    """The ledger and the pinned snapshot are the same rows, field for field.

    Four defect shapes, and each one is a way an archive stops being one: a row
    removed, a row added, a pinned row's content changed, and one identity used
    twice so the two sides stop pairing off. `snapshot` is injectable so the
    self-tests exercise this function rather than a restatement of it.
    """
    if snapshot is None:
        snapshot = SNAPSHOT_ROWS

    pinned = {entry[0]: entry for entry in snapshot}
    problems: list[str] = []

    present: dict[str, Row] = {}
    for row in rows:
        if row.key in present:
            first = present[row.key]
            problems.append(
                f"DONOR-SNAPSHOT-ROW-DUPLICATED: {LEDGER_REL}:{row.lineno} "
                f"({row.cell}) repeats the identity `{row.key}`, already used at "
                f"line {first.lineno}. One identity names one row, or the pinned "
                "snapshot and the ledger stop covering each other one for one."
            )
            continue
        present[row.key] = row

    for key in (entry[0] for entry in snapshot):
        if key not in present:
            problems.append(
                f"DONOR-SNAPSHOT-ROW-REMOVED: `{key}` is pinned in the "
                f"{SNAPSHOT_DATE} snapshot and is no longer in {LEDGER_REL}. "
                "The ledger is an archive: it records where absorption stood "
                "when EP-0036 was withdrawn, and removing a row shrinks the "
                "record of what the programme did not finish. Restore it."
            )

    for row in rows:
        if present.get(row.key) is not row:
            continue  # already reported as a duplicate; one report per defect
        entry = pinned.get(row.key)
        if entry is None:
            problems.append(
                f"DONOR-SNAPSHOT-ROW-UNPINNED: {LEDGER_REL}:{row.lineno} "
                f"({row.cell}) is a ledger row the {SNAPSHOT_DATE} snapshot does "
                f"not pin, under section '{row.section}'. The ledger is closed: "
                "a new row is either the transcription of something that was "
                "there — add the line  "
                f"{_format_entry(row.pinned)}  to SNAPSHOT_ROWS in "
                "scripts/check_donor_inventory.py in the same change — or a live "
                "claim, which belongs under its own authority and not in an "
                "archive of a withdrawn programme."
            )
            continue
        found = row.pinned
        drifted = [
            f"{name} pinned {want!r}, found {got!r}"
            for name, want, got in zip(_FIELDS, entry[1:], found[1:])
            if want != got
        ]
        if drifted:
            problems.append(
                f"DONOR-SNAPSHOT-ROW-DRIFTED: {LEDGER_REL}:{row.lineno} "
                f"({row.cell}) — " + "; ".join(drifted) + ". The rows of this "
                f"ledger record {SNAPSHOT_DATE} and do not change: a status is "
                "not flipped, a disposition is not revisited, and a description "
                "is not rewritten, because there is no programme left to decide "
                "any of it. Revert the edit; if you are correcting a "
                "transcription error against the withdrawal-time record, say so "
                "in the change and re-pin the row deliberately."
            )

    return problems


def _format_entry(entry: tuple[str, str, str, str, str]) -> str:
    identity, disposition, slice_, status, what = entry
    return (f'("{identity}", "{disposition}", "{slice_}", "{status}", '
            f'"{what}"),')


def summarize(rows: list[Row]) -> str:
    """One line of evidence that the check was not vacuous."""
    pending = sum(1 for row in rows if row.status == "pending")
    return (
        f"donor-inventory snapshot: {len(rows)} rows checked against "
        f"{len(SNAPSHOT_ROWS)} pinned. At the {SNAPSHOT_DATE} withdrawal "
        f"{pending} were recorded `pending` — undisposed when the programme "
        f"stopped, owned by nobody since — and {len(rows) - pending} `done`."
    )


def check(repo_root: Path, *, verbose: bool = False,
          ci: bool = False) -> tuple[int, list[Row]]:
    ledger = repo_root / LEDGER_REL
    if not ledger.is_file():
        raise SetupError(f"ledger not found at {LEDGER_REL} under {repo_root}")

    rows, problems = parse_ledger(ledger.read_text(encoding="utf-8"))
    if verbose:
        sys.stderr.write(f"parsed {len(rows)} ledger rows from {LEDGER_REL}\n")

    problems.extend(check_snapshot(rows))

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

    def classes(problems: list[str]) -> list[str]:
        return [problem.split(":", 1)[0] for problem in problems]

    # --- Parsing -------------------------------------------------------------
    full = _FIXTURE_HEADER + (
        "| `implementation/ui/src/re_frame/ui.cljc` | facade | REPLACE | F1 | pending |\n"
        "| `implementation/ui/test/*` | donor tests | MOVE | F6 | done |\n"
        "| `local` and its placement machinery | donor form | DELETE | F1 | pending |\n"
    )
    rows, malformed = parse_ledger(full)
    expect("A1 row count", len(rows), 3)
    expect("A2 no malformed rows", malformed, [])
    expect("A3 label row is not a path row", rows[2].pattern, None)
    expect("A4 label row identity is its cell",
           rows[2].key, "`local` and its placement machinery")
    expect("A5 path row identity is its pattern",
           rows[0].key, "implementation/ui/src/re_frame/ui.cljc")
    expect("A6 the pinned tuple is the whole row",
           rows[0].pinned,
           ("implementation/ui/src/re_frame/ui.cljc", "REPLACE", "F1",
            "pending", digest("facade")))

    # Column vocabulary.
    bad = _FIXTURE_HEADER + (
        "| `implementation/ui/src/*` | all | ABSORB | F1 | pending |\n"
        "| `implementation/ui/test/*` | tests | MOVE | F9 | pending |\n"
        "| `tools/story/deps.edn` | coord | MOVE | F6 | maybe |\n"
        "| `spec/004-Views.md` |  | MOVE | F0 | pending |\n"
    )
    rows, malformed = parse_ledger(bad)
    expect("B1 every bad row rejected", len(rows), 0)
    expect("B2 four defects", len(malformed), 4)
    expect("B3 bad disposition named", "ABSORB" in malformed[0], True)
    expect("B4 bad slice named", "F9" in malformed[1], True)
    expect("B5 bad status named", "maybe" in malformed[2], True)

    # Wrong arity and fenced tables.
    arity = _FIXTURE_HEADER + "| `spec/004-Views.md` | too | few |\n"
    _, malformed = parse_ledger(arity)
    expect("C1 arity defect", len(malformed), 1)
    expect("C2 arity class", malformed[0].startswith("DONOR-SNAPSHOT-MALFORMED"),
           True)

    fenced = (
        "```\n" + _FIXTURE_HEADER
        + "| `implementation/ui/src/x.cljc` | ex | MOVE | F1 | pending |\n"
        + "```\n"
    )
    rows, malformed = parse_ledger(fenced)
    expect("C3 fenced example ignored", (len(rows), len(malformed)), (0, 0))

    sectioned = (
        "## Donor sources\n\n" + _FIXTURE_HEADER
        + "| `implementation/ui/src/*` | all | MOVE | F1 | pending |\n"
        + "\n## Donor tests\n\n" + _FIXTURE_HEADER
        + "| `implementation/ui/test/*` | tests | MOVE | F6 | pending |\n"
    )
    rows, _ = parse_ledger(sectioned)
    expect("C4 sections captured",
           [row.section for row in rows], ["Donor sources", "Donor tests"])

    # --- The snapshot --------------------------------------------------------
    archive = _FIXTURE_HEADER + (
        "| `implementation/ui/src/re_frame/ui.cljc` | the donor facade | REPLACE | F1 | pending |\n"
        "| `implementation/ui/src/re_frame/ui/tree.cljc` | JVM tree builders | MOVE | F1 | pending |\n"
        "| `spec/004-Views.md` | a spec obligation | MOVE | F0 | done |\n"
        "| `local` and its placement machinery | a donor form | DELETE | F1 | pending |\n"
    )
    pins = (
        ("implementation/ui/src/re_frame/ui.cljc", "REPLACE", "F1", "pending",
         digest("the donor facade")),
        ("implementation/ui/src/re_frame/ui/tree.cljc", "MOVE", "F1", "pending",
         digest("JVM tree builders")),
        ("spec/004-Views.md", "MOVE", "F0", "done", digest("a spec obligation")),
        ("`local` and its placement machinery", "DELETE", "F1", "pending",
         digest("a donor form")),
    )

    def defects(text: str, snapshot=pins) -> list[str]:
        parsed, _ = parse_ledger(text)
        return check_snapshot(parsed, snapshot)

    expect("D1 the pristine archive is satisfied", defects(archive), [])

    # A deleted row — path and label alike.
    deleted = archive.replace(
        "| `spec/004-Views.md` | a spec obligation | MOVE | F0 | done |\n", "")
    problems = defects(deleted)
    expect("D2 deleted row is caught", classes(problems),
           ["DONOR-SNAPSHOT-ROW-REMOVED"])
    expect("D3 deleted row is named", "spec/004-Views.md" in problems[0], True)

    label_deleted = archive.replace(
        "| `local` and its placement machinery | a donor form | DELETE | F1 | pending |\n", "")
    problems = defects(label_deleted)
    expect("D4 deleted label row is caught", classes(problems),
           ["DONOR-SNAPSHOT-ROW-REMOVED"])
    expect("D5 deleted label row is named",
           "`local` and its placement machinery" in problems[0], True)

    # An added row.
    added = archive + (
        "| `tools/newcomer/deps.edn` | a claim made after the fact | MOVE | F6 | pending |\n"
    )
    problems = defects(added)
    expect("E1 an added row is caught", classes(problems),
           ["DONOR-SNAPSHOT-ROW-UNPINNED"])
    expect("E2 the diagnostic names the row's line",
           f"{LEDGER_REL}:7" in problems[0], True)
    expect("E3 the diagnostic prints the whole tuple to paste",
           f'("tools/newcomer/deps.edn", "MOVE", "F6", "pending", '
           f'"{digest("a claim made after the fact")}"),' in problems[0], True)
    expect("E4 the diagnostic names the file to paste it into",
           "SNAPSHOT_ROWS in scripts/check_donor_inventory.py" in problems[0],
           True)
    expect("E5 pinning the new row settles it",
           defects(added, pins + (("tools/newcomer/deps.edn", "MOVE", "F6",
                                   "pending",
                                   digest("a claim made after the fact")),)),
           [])

    # --- Drift: the arm identities alone could not hold ----------------------
    # The inversion the withdrawal makes: under the old programme gate a
    # pending -> done flip was progress and passed. In an archive it is drift.
    progressed = archive.replace(
        "| `implementation/ui/src/re_frame/ui.cljc` | the donor facade | REPLACE | F1 | pending |",
        "| `implementation/ui/src/re_frame/ui.cljc` | the donor facade | REPLACE | F1 | done |")
    problems = defects(progressed)
    expect("F1 a status flip is drift now", classes(problems),
           ["DONOR-SNAPSHOT-ROW-DRIFTED"])
    expect("F2 the drift names the field and both values",
           "status pinned 'pending', found 'done'" in problems[0], True)

    redispositioned = archive.replace(
        "| `implementation/ui/src/re_frame/ui/tree.cljc` | JVM tree builders | MOVE | F1 | pending |",
        "| `implementation/ui/src/re_frame/ui/tree.cljc` | JVM tree builders | DELETE | F1 | pending |")
    problems = defects(redispositioned)
    expect("F3 a re-disposition is caught", classes(problems),
           ["DONOR-SNAPSHOT-ROW-DRIFTED"])
    expect("F4 the disposition is named",
           "disposition pinned 'MOVE', found 'DELETE'" in problems[0], True)

    resliced = archive.replace(
        "| `spec/004-Views.md` | a spec obligation | MOVE | F0 | done |",
        "| `spec/004-Views.md` | a spec obligation | MOVE | F3 | done |")
    expect("F5 a re-slicing is caught", classes(defects(resliced)),
           ["DONOR-SNAPSHOT-ROW-DRIFTED"])

    rewritten = archive.replace(
        "| `local` and its placement machinery | a donor form | DELETE | F1 | pending |",
        "| `local` and its placement machinery | a donor form that Freehand still owes | DELETE | F1 | pending |")
    problems = defects(rewritten)
    expect("F6 a rewritten description is caught", classes(problems),
           ["DONOR-SNAPSHOT-ROW-DRIFTED"])
    expect("F7 the description drift names the digest field",
           "description digest pinned" in problems[0], True)

    respaced = archive.replace(
        "| `local` and its placement machinery | a donor form | DELETE | F1 | pending |",
        "| `local` and its placement machinery | a  donor   form | DELETE | F1 | pending |")
    expect("F8 whitespace churn is not drift", defects(respaced), [])

    # Two rows, one identity: the pin and the ledger stop pairing off.
    duplicated = archive + (
        "| `spec/004-Views.md` | a second claim on one identity | MOVE | F0 | done |\n"
    )
    problems = defects(duplicated)
    expect("G1 a duplicated identity is caught", classes(problems),
           ["DONOR-SNAPSHOT-ROW-DUPLICATED"])
    expect("G2 the duplicate names the first line",
           "line 5" in problems[0], True)

    # --- The real snapshot is wired to the real check ------------------------
    expect("H1 check_snapshot reads SNAPSHOT_ROWS",
           len(check_snapshot([])), len(SNAPSHOT_ROWS))
    expect("H2 the real snapshot is populated", len(SNAPSHOT_ROWS) > 100, True)
    expect("H3 pinned identities are unique",
           len({entry[0] for entry in SNAPSHOT_ROWS}), len(SNAPSHOT_ROWS))
    expect("H4 every pinned tuple is complete",
           {len(entry) for entry in SNAPSHOT_ROWS}, {5})
    expect("H5 every pinned row is decidable",
           {(entry[1] in DISPOSITIONS, bool(SLICE_RE.match(entry[2])),
             entry[3] in STATUSES) for entry in SNAPSHOT_ROWS},
           {(True, True, True)})

    if failures:
        sys.stderr.write(f"self-test: {failures} case(s) failed.\n")
        return 1
    sys.stderr.write("self-test: all cases passed.\n")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Prove the archived Freehand donor inventory still matches the "
            "snapshot pinned at the EP-0036 withdrawal: the same rows, each "
            "with the same disposition, owning slice, withdrawal-time status "
            "and description."
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

    sys.stdout.write(summarize(rows) + "\n")
    return 0 if defects == 0 else 1


# --- The pinned snapshot ------------------------------------------------------
# The ledger as it stood at the EP-0036 withdrawal, one tuple per row:
#
#     (identity, disposition, owning slice, status, description digest)
#
# Identity is the row's path pattern, or its whole cell for a label row; the
# digest is `digest()` over the normalized 'What it is' column. The whole tuple
# is pinned, not the identity alone — see "No row drifts" in the module
# docstring. Ordered as the ledger reads, and sectioned the way it is sectioned.
SNAPSHOT_ROWS = (
    # Ruled contract dispositions
    ("`local` and its placement machinery", "DELETE", "F4a", "done", "c43a540039e0"),
    ("instance state and generic storage verbs", "DELETE", "F4a", "done", "ad22b8867d7f"),
    ("refs, effects, and the React hook tier", "DELETE", "F4", "done", "eee4ffa14a79"),
    ("callable JVM view values", "REPLACE", "F1", "done", "bd3b2ceb9619"),
    ("placeholder provenance", "REPLACE", "F2", "done", "4b50ccc028e5"),
    ("compiled parent to interpreted child crossing", "MOVE", "F3", "done", "936fb4c725ae"),
    ("controlled scheduling", "MOVE", "F2", "done", "7cc9433de23a"),
    ("key-condition event maps", "DELETE", "F2", "done", "c8a01a195445"),
    ("`spread-safe`/`spread` and `render-fn`/`slot`", "MOVE", "F5", "done", "c8b8ec87b455"),
    ("presence runtime", "MOVE", "F4", "done", "7080460b5ab2"),
    ("`route-link`", "MOVE", "F5", "done", "398f918ca7a7"),
    ("analyzer, both emitters, ViewCell reactor, manifest/elision, diagnostic taxonomy, structural test surface", "MOVE", "F3", "pending", "d8d8c1c130b4"),
    # Donor sources
    ("implementation/ui/src/re_frame/ui.cljc", "REPLACE", "F1", "pending", "fb656ce41ec5"),
    ("implementation/ui/src/re_frame/ui/client.cljs", "REPLACE", "F1", "done", "a406d65390dd"),
    ("implementation/ui/src/re_frame/ui/compiler.cljc", "MOVE", "F3", "done", "8fc5dc9d4fa3"),
    ("implementation/ui/src/re_frame/ui/compiler/a11y.cljc", "MOVE", "F3", "done", "c39fcd793fa8"),
    ("implementation/ui/src/re_frame/ui/compiler/analyze.cljc", "MOVE", "F3", "done", "82a51a4a19b1"),
    ("implementation/ui/src/re_frame/ui/compiler/binding_plan.cljc", "MOVE", "F3", "done", "70e9dbd4149a"),
    ("implementation/ui/src/re_frame/ui/compiler/build.cljc", "MOVE", "F3", "done", "012d989683f1"),
    ("implementation/ui/src/re_frame/ui/compiler/build_hook.clj", "MOVE", "F3", "done", "2b8899078563"),
    ("implementation/ui/src/re_frame/ui/compiler/emit_cljs.cljc", "MOVE", "F3", "done", "bd24e248c32f"),
    ("implementation/ui/src/re_frame/ui/compiler/emit_jvm.cljc", "MOVE", "F3", "done", "8b1ae36fba0f"),
    ("implementation/ui/src/re_frame/ui/compiler/env.cljc", "MOVE", "F3", "done", "a8033eafc678"),
    ("implementation/ui/src/re_frame/ui/compiler/harvest.clj", "MOVE", "F3", "done", "be1c93c56dff"),
    ("implementation/ui/src/re_frame/ui/compiler/header.cljc", "MOVE", "F3", "done", "976289b74bbc"),
    ("implementation/ui/src/re_frame/ui/compiler/root.cljc", "MOVE", "F3", "done", "c1b49d49e123"),
    ("implementation/ui/src/re_frame/ui/eq.cljc", "MOVE", "F3", "done", "492a01582418"),
    ("implementation/ui/src/re_frame/ui/events.cljs", "MOVE", "F2", "pending", "952db5a85d75"),
    ("implementation/ui/src/re_frame/ui/fingerprint.cljc", "MOVE", "F3", "done", "a8f51dbe437a"),
    ("implementation/ui/src/re_frame/ui/frames.cljc", "MOVE", "F2", "pending", "6e7a9142b9a3"),
    ("implementation/ui/src/re_frame/ui/hooks.cljc", "DELETE", "F1", "pending", "4c8218df0439"),
    ("implementation/ui/src/re_frame/ui/presence_runtime.cljc", "MOVE", "F4", "done", "59f8b9a3ff64"),
    ("implementation/ui/src/re_frame/ui/react.cljc", "DELETE", "F5", "pending", "c680451fa655"),
    ("implementation/ui/src/re_frame/ui/reactive.cljc", "MOVE", "F2", "pending", "03cfa86172d1"),
    ("implementation/ui/src/re_frame/ui/route_link_seam.cljc", "MOVE", "F5", "pending", "4bfad84088e2"),
    ("implementation/ui/src/re_frame/ui/rules.cljc", "MOVE", "F3", "done", "ab4218330f13"),
    ("implementation/ui/src/re_frame/ui/runtime.cljs", "REPLACE", "F3", "done", "e1efa88e6c50"),
    ("implementation/ui/src/re_frame/ui/semantic.cljc", "MOVE", "F1", "pending", "0e59e30fcaa3"),
    ("implementation/ui/src/re_frame/ui/sub_overrides.cljs", "MOVE", "F2", "pending", "c4b427494642"),
    ("implementation/ui/src/re_frame/ui/substrate.cljs", "MOVE", "F2", "done", "66ae17358520"),
    ("implementation/ui/src/re_frame/ui/test.cljc", "MOVE", "F1", "done", "b48d010da136"),
    ("implementation/ui/src/re_frame/ui/tool.cljc", "REPLACE", "F4", "done", "c4e0ccf141ec"),
    ("implementation/ui/src/re_frame/ui/tool/evidence.cljc", "REPLACE", "F4", "done", "26102e5400b2"),
    ("implementation/ui/src/re_frame/ui/tree.cljc", "MOVE", "F1", "pending", "f27f88d2cf63"),
    ("implementation/ui/src/re_frame/ui/viewcell.cljs", "MOVE", "F2", "pending", "20c7aadfce02"),
    # Donor tests
    ("implementation/ui/test/re_frame/ui/a11y_*", "MOVE", "F3", "done", "d73cd3193a01"),
    ("implementation/ui/test/re_frame/ui/adapter_*", "MOVE", "F2", "pending", "e692c630bfdb"),
    ("implementation/ui/test/re_frame/ui/analyze_*", "MOVE", "F3", "done", "8a40aa6e7870"),
    ("implementation/ui/test/re_frame/ui/authored_collision_*", "MOVE", "F1", "pending", "0075305a051d"),
    ("implementation/ui/test/re_frame/ui/binding_plan_*", "MOVE", "F3", "pending", "c03ef12981f6"),
    ("implementation/ui/test/re_frame/ui/build_*", "MOVE", "F3", "pending", "4b07d55605b3"),
    ("implementation/ui/test/re_frame/ui/callbacks_*", "MOVE", "F2", "pending", "04f9b69320b7"),
    ("implementation/ui/test/re_frame/ui/committed_events_*", "MOVE", "F2", "pending", "f464b282cf8c"),
    ("implementation/ui/test/re_frame/ui/compiler_*", "MOVE", "F3", "done", "1ce527ba44da"),
    ("implementation/ui/test/re_frame/ui/conditional_root_annotation_*", "MOVE", "F3", "pending", "b265da93bc00"),
    ("implementation/ui/test/re_frame/ui/conditional_sub_*", "MOVE", "F2", "pending", "ef51ef90a682"),
    ("implementation/ui/test/re_frame/ui/custom_element_*", "MOVE", "F3", "done", "ab464c56ef6a"),
    ("implementation/ui/test/re_frame/ui/defview_grammar_*", "MOVE", "F1", "pending", "101997aaddfc"),
    ("implementation/ui/test/re_frame/ui/digest_probe/*", "MOVE", "F3", "pending", "30dc731be5bb"),
    ("implementation/ui/test/re_frame/ui/emit_cljs_*", "MOVE", "F3", "done", "7ce449add683"),
    ("implementation/ui/test/re_frame/ui/eq_*", "MOVE", "F3", "done", "95f9628ca970"),
    ("implementation/ui/test/re_frame/ui/error_roster_*", "MOVE", "F1", "pending", "efba8f1ea088"),
    ("implementation/ui/test/re_frame/ui/event_*", "MOVE", "F2", "pending", "4714b196a589"),
    ("implementation/ui/test/re_frame/ui/exact_render_capture_*", "MOVE", "F2", "pending", "7e85cedbbb9e"),
    ("implementation/ui/test/re_frame/ui/fast_refresh_shell_*", "MOVE", "F1", "pending", "b2d7da06e7d0"),
    ("implementation/ui/test/re_frame/ui/fingerprint_*", "MOVE", "F3", "done", "63ded3927ca0"),
    ("implementation/ui/test/re_frame/ui/frame_*", "MOVE", "F2", "pending", "c23b81902d35"),
    ("implementation/ui/test/re_frame/ui/g13/*", "MOVE", "F6", "pending", "34f88c130f7b"),
    ("implementation/ui/test/re_frame/ui/g14_*", "MOVE", "F3", "pending", "dc1eaa64f9b9"),
    ("implementation/ui/test/re_frame/ui/hidden_sub_macros.clj", "MOVE", "F2", "pending", "aeb2f3901fc1"),
    ("implementation/ui/test/re_frame/ui/hooks_*", "DELETE", "F1", "pending", "fd6f3d470e3c"),
    ("implementation/ui/test/re_frame/ui/local_effect_*", "DELETE", "F1", "pending", "92e49cc2bc19"),
    ("implementation/ui/test/re_frame/ui/mounted_*", "MOVE", "F4", "pending", "18e2acfd3b3f"),
    ("implementation/ui/test/re_frame/ui/parity_*", "MOVE", "F3", "pending", "90e3c87e7b72"),
    ("implementation/ui/test/re_frame/ui/passive_events_*", "MOVE", "F2", "pending", "e46679ceacb3"),
    ("implementation/ui/test/re_frame/ui/preflight_*", "MOVE", "F2", "pending", "24dde47d27a1"),
    ("implementation/ui/test/re_frame/ui/presence_*", "MOVE", "F4", "pending", "5533c23f5580"),
    ("implementation/ui/test/re_frame/ui/raw_foreign_boundary_*", "MOVE", "F5", "pending", "6730caece23e"),
    ("implementation/ui/test/re_frame/ui/react_export_bridge_*", "MOVE", "F5", "pending", "b252d6769f16"),
    ("implementation/ui/test/re_frame/ui/react_interop_*", "REPLACE", "F5", "pending", "5f53a819195a"),
    ("implementation/ui/test/re_frame/ui/react_render_*", "MOVE", "F1", "pending", "b24751cbe153"),
    ("implementation/ui/test/re_frame/ui/reactive_*", "MOVE", "F2", "pending", "9b1e495d678e"),
    ("implementation/ui/test/re_frame/ui/render_batch_*", "MOVE", "F2", "pending", "0a658394a603"),
    ("implementation/ui/test/re_frame/ui/render_capture_*", "MOVE", "F2", "pending", "f317f8134de9"),
    ("implementation/ui/test/re_frame/ui/render_key_dom_stamp_*", "MOVE", "F1", "pending", "3a1490fb327a"),
    ("implementation/ui/test/re_frame/ui/render_static_strip_*", "MOVE", "F3", "pending", "6d132f77621d"),
    ("implementation/ui/test/re_frame/ui/reserved_head_reject_*", "MOVE", "F3", "done", "9b691db17bb6"),
    ("implementation/ui/test/re_frame/ui/root_*", "MOVE", "F1", "pending", "d3df9f57beea"),
    ("implementation/ui/test/re_frame/ui/route_link_*", "MOVE", "F5", "pending", "5d8829ef0899"),
    ("implementation/ui/test/re_frame/ui/rules_*", "MOVE", "F1", "pending", "4a6413ef5be7"),
    ("implementation/ui/test/re_frame/ui/s3_*", "DELETE", "F6", "pending", "cf24b4d0a311"),
    ("implementation/ui/test/re_frame/ui/s4_*", "DELETE", "F6", "pending", "2f9844848c75"),
    ("implementation/ui/test/re_frame/ui/s5_*", "DELETE", "F6", "pending", "a18fc00369d6"),
    ("implementation/ui/test/re_frame/ui/semantic_normalize_*", "MOVE", "F1", "pending", "616a6612bac5"),
    ("implementation/ui/test/re_frame/ui/serialiser_rules_*", "MOVE", "F1", "pending", "dc2c1902ca11"),
    ("implementation/ui/test/re_frame/ui/shadow_config_*", "MOVE", "F3", "pending", "5efe29d155fb"),
    ("implementation/ui/test/re_frame/ui/skeleton_*", "MOVE", "F3", "pending", "c216bbce8ff2"),
    ("implementation/ui/test/re_frame/ui/slice_memo_*", "MOVE", "F2", "pending", "6affb868b9b0"),
    ("implementation/ui/test/re_frame/ui/slot_*", "MOVE", "F5", "done", "4ee23394fa6c"),
    ("implementation/ui/test/re_frame/ui/spread_*", "MOVE", "F5", "done", "43c5073d20a2"),
    ("implementation/ui/test/re_frame/ui/ssr_reinit_*", "MOVE", "F5", "pending", "f3f17fcef9e6"),
    ("implementation/ui/test/re_frame/ui/sub_overrides_*", "MOVE", "F2", "pending", "50573cbc46fd"),
    ("implementation/ui/test/re_frame/ui/substrate_flush_*", "MOVE", "F2", "pending", "63bfac2851e6"),
    ("implementation/ui/test/re_frame/ui/teardown_falsy_*", "MOVE", "F1", "pending", "421e259ca8e8"),
    ("implementation/ui/test/re_frame/ui/test_*", "MOVE", "F1", "pending", "3b592cb8105b"),
    ("implementation/ui/test/re_frame/ui/tool_*", "REPLACE", "F4", "done", "060264fb062d"),
    ("implementation/ui/test/re_frame/ui/tree_*", "MOVE", "F1", "pending", "d32e3f02b105"),
    ("implementation/ui/test/re_frame/ui/viewcell_*", "MOVE", "F2", "pending", "1e403f1f77b5"),
    ("implementation/ui/test/re_frame/realworld_*", "DELETE", "F6", "pending", "7b2bc33858d4"),
    # Donor fixtures, probes, and testbeds
    ("implementation/ui/bench/*", "MOVE", "F3", "pending", "fc9eaec3b9bc"),
    ("implementation/ui/cache-carrier-probe/*", "MOVE", "F3", "pending", "30398a62fd69"),
    ("implementation/ui/dev/*", "MOVE", "F1", "pending", "47ce5eb75c46"),
    ("implementation/ui/g8/*", "MOVE", "F6", "pending", "366334c685ca"),
    ("implementation/ui/g13/*", "MOVE", "F6", "pending", "76a2fedc1810"),
    ("implementation/ui/proof-pack/*", "MOVE", "F3", "pending", "cbe5bbbc558c"),
    ("implementation/ui/scaffold-smoke/*", "MOVE", "F6", "done", "88db775422ca"),
    ("implementation/ui/testbed/*", "MOVE", "F1", "pending", "045a01a9a767"),
    # Donor artifact and build wiring
    ("implementation/ui/deps.edn", "DELETE", "F6", "pending", "81060abea4cb"),
    ("implementation/deps.edn", "REPLACE", "F6", "pending", "fe6456b027c7"),
    ("implementation/shadow-cljs.edn", "REPLACE", "F6", "pending", "c2229ba254dc"),
    ("implementation/package.json", "REPLACE", "F6", "pending", "b348a5ee4a2b"),
    ("implementation/scripts/check-ui-adapter-isolation.cjs", "MOVE", "F6", "pending", "161efbdd0fc4"),
    ("implementation/scripts/check-ui-facade-isolation.cjs", "MOVE", "F6", "pending", "b00e54de875a"),
    ("implementation/scripts/check-ui-warm-watch.cjs", "MOVE", "F6", "pending", "6ce85e1e83cd"),
    ("implementation/scripts/check-ui-mounted-prod-elision.cjs", "MOVE", "F6", "pending", "b43d72270e64"),
    ("implementation/scripts/run-ui-bench.cjs", "MOVE", "F6", "pending", "a54e8c4a50b0"),
    ("implementation/scripts/run-ui-g8.cjs", "MOVE", "F6", "pending", "2b99ee8df40f"),
    ("implementation/scripts/run-ui-g13.cjs", "MOVE", "F6", "pending", "41b9562a70fe"),
    ("implementation/scripts/lib/g13-timing-evidence.cjs", "MOVE", "F6", "pending", "23a18c93620e"),
    ("implementation/scripts/lib/g8-latency-evidence.cjs", "MOVE", "F6", "pending", "6f584161e704"),
    ("implementation/scripts/bundle-isolation-positive-control/*", "MOVE", "F6", "pending", "244f1a60a21c"),
    ("implementation/scripts/_g8-latency-evidence.test.cjs", "MOVE", "F6", "pending", "d12a58669659"),
    ("implementation/scripts/_g13-timing-evidence.test.cjs", "MOVE", "F6", "pending", "c31983faba43"),
    ("implementation/scripts/_release-ui-required-gate.test.cjs", "DELETE", "F6", "done", "5184cf6dbd81"),
    ("implementation/scripts/_ui-deps-edn-boundary.test.cjs", "REPLACE", "F6", "pending", "d2e81c2ca772"),
    ("implementation/scripts/_changed-surfaces.test.cjs", "REPLACE", "F6", "pending", "f9c53d2357bb"),
    ("implementation/adapters/scripts/adapter-smoke-filter.cjs", "REPLACE", "F6", "pending", "1ded156e7e8d"),
    ("implementation/adapters/scripts/serve-and-run-adapter-smokes.cjs", "REPLACE", "F6", "pending", "e4443c400715"),
    ("implementation/adapters/scripts/run-adapter-smokes.cjs", "REPLACE", "F6", "pending", "5f0db56fa126"),
    ("implementation/scripts/_adapter-smoke-filter.test.cjs", "REPLACE", "F6", "pending", "8a31128ea0ed"),
    ("implementation/scripts/_reagent-slim-smoke-policy.test.cjs", "REPLACE", "F6", "pending", "c29eb561f4a2"),
    ("examples/scripts/examples-asset-manifest.cjs", "REPLACE", "F6", "pending", "9524dc5db63e"),
    ("implementation/scripts/_examples-staging.test.cjs", "REPLACE", "F6", "pending", "44b113982ab4"),
    ("scripts/check_ui_root_lifecycle_drift.py", "REPLACE", "F6", "pending", "610e1cf34135"),
    ("scripts/check_skill_implementor_partition_drift.py", "REPLACE", "F6", "pending", "e49ce6623c97"),
    ("scripts/test-fast-pr.sh", "REPLACE", "F6", "pending", "a488b4751c28"),
    ("scripts/test-jvm-implementation.sh", "REPLACE", "F6", "pending", "406030604efc"),
    ("implementation/scripts/check-bundle-isolation.cjs", "REPLACE", "F6", "pending", "7633fd61851d"),
    ("implementation/scripts/check-bundle-isolation.test.cjs", "REPLACE", "F6", "pending", "01159ee8f11e"),
    ("implementation/scripts/check-elision.cjs", "REPLACE", "F6", "pending", "e40af892e7ef"),
    ("scripts/check_adapter_disposition.py", "REPLACE", "F6", "pending", "14ee386fb68b"),
    (".github/scripts/preflight-story-package.sh", "REPLACE", "F6", "pending", "e41d2c0f55d0"),
    ("implementation/scripts/_preflight-story-package.test.cjs", "REPLACE", "F6", "pending", "f07f5553a46f"),
    (".github/scripts/verify-version-lockstep.sh", "DELETE", "F6", "done", "f918b952a96b"),
    (".github/scripts/report-changed-surfaces.sh", "REPLACE", "F6", "pending", "336273712271"),
    (".github/workflows/test.yml", "REPLACE", "F6", "pending", "726c27438535"),
    (".github/workflows/release.yml", "DELETE", "F6", "done", "16ef97ee8aaa"),
    (".github/workflows/lint.yml", "REPLACE", "F6", "pending", "8ed0d43b81ae"),
    (".github/workflows/portability.yml", "REPLACE", "F6", "pending", "842ff0ff5e59"),
    ("`TESTING.md`", "REPLACE", "F6", "pending", "24c88a1353e5"),
    # Donor obligations in the spec tree
    ("spec/004D-Freehand-Compiled-Grammar.md", "MOVE", "F0", "done", "cda1a3655ac1"),
    ("spec/004B-UI-Tree-and-Conversion.md", "MOVE", "F1", "done", "b0710f328e19"),
    ("spec/004C-Roots-and-Mount.md", "MOVE", "F1", "pending", "6a4b0d3e4a19"),
    ("spec/006-ReactiveSubstrate.md", "MOVE", "F2", "pending", "295304094e6b"),
    ("spec/008-Testing.md", "MOVE", "F1", "pending", "c8a0bbdfc4a6"),
    ("spec/009-Instrumentation.md", "MOVE", "F4", "pending", "17487b21902e"),
    ("spec/011-SSR.md", "MOVE", "F5", "pending", "fb08d92446b2"),
    ("spec/012-Routing.md", "MOVE", "F5", "pending", "a571485eb417"),
    ("spec/API.md", "REPLACE", "F6", "pending", "b57b82a3fce5"),
    ("spec/Ownership.md", "REPLACE", "F6", "pending", "8f9b22da38a6"),
    ("spec/Conventions.md", "REPLACE", "F6", "pending", "e777412c33cb"),
    ("spec/conformance/S3-view-conformance-profile.md", "DELETE", "F6", "pending", "848787138c0a"),
    ("spec/conformance/S4-view-conformance-profile.md", "DELETE", "F6", "pending", "2f9844848c75"),
    ("spec/conformance/S5-view-conformance-profile.md", "DELETE", "F6", "pending", "a18fc00369d6"),
    ("spec/Pattern-StatefulComponents.md", "REPLACE", "F4", "pending", "e232b5a8db68"),
    ("spec/api-manifest.edn", "REPLACE", "F6", "pending", "ff8cf18cdb8e"),
    ("spec/api-manifest-metadata.edn", "REPLACE", "F6", "pending", "74ace6c6586b"),
    # Donor consumers in the implementation tree
    ("implementation/ssr/deps.edn", "REPLACE", "F6", "pending", "79eb42ae7504"),
    ("implementation/ssr/src/re_frame/ssr/ui_tree.cljc", "MOVE", "F5", "pending", "3ec7a438e20b"),
    ("implementation/ssr/test/re_frame/ssr/emit_ui_tree_cljs_test.cljc", "MOVE", "F5", "pending", "8d1d0fc5a559"),
    ("implementation/ssr/test/re_frame/ssr/render_static_jvm_test.clj", "MOVE", "F5", "pending", "a435dd3440d5"),
    ("implementation/ssr/test/re_frame/ssr/root_manifest_cljs_test.cljc", "MOVE", "F5", "pending", "31b3b2b11348"),
    ("implementation/ssr/test/re_frame/ssr/hydrate_root_seam_dom_cljs_test.cljs", "MOVE", "F5", "pending", "26434a6dd857"),
    ("implementation/ssr/test/re_frame/ssr/*_hydration_dom_cljs_test.cljs", "MOVE", "F5", "pending", "0fc7d5430c4d"),
    ("implementation/ssr/test/re_frame/ssr/client_only_adoption_verification_dom_cljs_test.cljs", "MOVE", "F5", "pending", "8c921392cb3a"),
    ("implementation/adapters/reagent/test/re_frame/observation_port_watchable_host_*", "MOVE", "F2", "pending", "c7c9653fd07d"),
    ("implementation/core/test/re_frame/elision_probe.cljs", "MOVE", "F3", "pending", "4c7317c3739e"),
    ("implementation/scripts/api-manifest/deps.edn", "REPLACE", "F6", "pending", "65e0ee7c2246"),
    ("implementation/scripts/api-manifest/src/re_frame/api_manifest/gen.clj", "REPLACE", "F6", "pending", "1ee130e5fe62"),
    ("implementation/scripts/api-manifest/src/re_frame/api_manifest/ui_context.clj", "MOVE", "F6", "pending", "12bb8070446c"),
    ("implementation/scripts/api-manifest/test/re_frame/api_manifest/ui_context_test.clj", "MOVE", "F6", "pending", "9a47866c59c4"),
    ("implementation/scripts/api-manifest/probe/test/re_frame/api_manifest/cljs_manifest_probe_cljs_test.cljs", "MOVE", "F6", "pending", "181336a9eb9a"),
    # Donor consumers in tools
    ("tools/story/deps.edn", "REPLACE", "F6", "pending", "9ca9efb629bf"),
    ("tools/story/src/re_frame/story/late_bind.cljc", "MOVE", "F6", "done", "754225ca13cf"),
    ("tools/story/src/re_frame/story/sub_overrides.cljc", "MOVE", "F6", "done", "65532cc30107"),
    ("tools/story/src/re_frame/story/play/*", "MOVE", "F6", "done", "f2bd1bf6df92"),
    ("tools/story/test/re_frame/story/play/presence_*", "MOVE", "F6", "done", "603de82ce991"),
    ("tools/story/test/re_frame/story/view_tool*", "MOVE", "F6", "pending", "25d9617d624a"),
    ("tools/story/test/re_frame/story/realworld_ui_consumer_cljs_test.cljs", "MOVE", "F6", "pending", "ba1231b272dd"),
    ("tools/story/spec/017-Testing-Story.md", "REPLACE", "F6", "done", "d7f678dfea68"),
    ("tools/xray/deps.edn", "REPLACE", "F6", "done", "f7b3b33b3cd0"),
    ("tools/xray/src/day8/re_frame2_xray/viewcell_evidence.cljs", "MOVE", "F6", "done", "6aeeca6ce820"),
    ("tools/xray/src/day8/re_frame2_xray/panels/reactive_panel_*", "MOVE", "F6", "done", "323edaa0ffcb"),
    ("tools/xray/test/day8/re_frame2_xray/viewcell_evidence_cljs_test.cljs", "MOVE", "F6", "done", "5ec48031d9e8"),
    ("tools/xray/test/day8/re_frame2_xray/realworld_ui_evidence_cljs_test.cljs", "MOVE", "F6", "done", "54bdc11b6bb4"),
    ("tools/xray/spec/*", "REPLACE", "F6", "done", "87eab5634977"),
    ("tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/view_tool.cljs", "MOVE", "F6", "done", "c37f1891e291"),
    ("tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/descriptors_data.cljs", "REPLACE", "F6", "done", "58b52fe9c2a5"),
    ("tools/re-frame2-pair-mcp/spec/003-Tool-Catalogue.md", "REPLACE", "F6", "done", "1e69ab190be4"),
    ("tools/template/resources/day8/re_frame2_template/_ui/*", "DELETE", "F6", "done", "92bda2754c28"),
    ("tools/template/resources/day8/re_frame2_template/template.edn", "REPLACE", "F6", "done", "ad4767626464"),
    ("tools/template/spec/001-Substrate-Variants.md", "REPLACE", "F6", "done", "81274dc38ea0"),
    ("tools/mcp-conformance/test/end-to-end-re-frame2-pair.cjs", "MOVE", "F6", "done", "033a5e3fef9a"),
    # Donor consumers in examples and testbeds
    ("examples/ui/minimal-counter/*", "MOVE", "F6", "done", "d9615436daa4"),
    ("examples/real-apps/realworld_resources/ui_*", "MOVE", "F6", "pending", "793d19c87013"),
    ("tools/xray/testbeds/feature_matrix/scenarios.cjs", "MOVE", "F6", "done", "aee6392288ce"),
    # Donor material in docs and skills
    ("docs/core/re-frame.ui/*", "MOVE", "F6", "done", "462cc68d1e66"),
    ("docs/core/how-to/install-re-frame-ui.md", "MOVE", "F6", "done", "b723098a7b05"),
    ("docs/core/how-to/measure-before-paint.md", "MOVE", "F6", "done", "13379c22a78c"),
    ("docs/core/views.md", "REPLACE", "F6", "done", "b6754744e01e"),
    ("docs/api/re-frame.ui*.md", "REPLACE", "F6", "pending", "41cb3ac71378"),
    ("skills/re-frame2-ui/*", "MOVE", "F6", "pending", "481d8435ce29"),
    ("skills/reagent-migration/*", "REPLACE", "F6", "done", "a54409beb4e3"),
    ("`mkdocs.yml`", "REPLACE", "F6", "pending", "833321ec2976"),
)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
