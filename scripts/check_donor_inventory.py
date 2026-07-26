#!/usr/bin/env python3
"""Deletion gate for the Freehand donor-inventory ledger.

The ledger — `spec/conformance/freehand/donor-inventory.md` — enumerates every
`re-frame.ui` row that the Freehand programme must MOVE, REPLACE, or DELETE, and
is the artifact the "absorption completeness" release gate reads before the
standalone donor artifact is deleted. That is a consequential job for a Markdown
table, so this script makes the table load-bearing rather than decorative. It
proves five things:

  1. **No donor file is invisible.** Every git-tracked file under the DONOR TREE
     (`implementation/ui/`) is matched by exactly one ledger row. A new donor
     file added without a ledger row fails; two rows claiming one file fail.

  2. **No live consumer is invisible.** Outside the donor tree the gate runs a
     CENSUS over git-tracked files for the two signals that would actually break
     if the donor artifact were deleted — a `re-frame.ui…` libspec, or the
     `day8/re-frame2-ui` coordinate in dependency position. Every file either
     signal finds must be claimed by a row, and while the signal is still there
     that row must still be `pending`. A row cannot claim `done` while the code
     it covers still requires the donor.

  3. **No row can be deleted.** `ESTABLISHED_ROWS` below is the roster of row
     identities the ledger has established, and it covers the ledger EXACTLY:
     every roster identity must still be in the ledger, and every ledger row
     must be in the roster. Disposing a row means flipping its status to `done`
     — the row stays as the audit record — so the pending count can only fall
     by disposition, never by deletion. Exactness is what extends that promise
     to rows written after the roster was: a row admitted without a roster
     identity could be added, disposed, and then deleted again with the gate
     reporting nothing, which is exactly the confident wrong answer a deletion
     gate must never produce.

  4. **No row goes stale.** A still-pending row whose pattern matches no tracked
     file fails. So does a still-pending row whose matched files exist but carry
     no donor material at all — the shape a row takes when its subject was
     migrated out from under it and the path was reused for something else.

  5. **Every row is decidable.** Each row names a disposition from the closed
     set {MOVE, REPLACE, DELETE}, an owning programme slice (F0-F6), and a
     status from {pending, done}.

It also reports the number of rows not yet disposed — the count the programme
drives to zero before the donor artifact is deleted.

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
of the repo roots in `REPO_ROOTS`. A row's IDENTITY — its key in
`ESTABLISHED_ROWS` — is its path pattern for a path row, and its whole cell text
for a label row.

## Two coverage regimes

The DONOR TREE is a CLOSED universe: its path rows must PARTITION the tracked
file set — every tracked file matched exactly once, no double-claiming, no
stale pattern. Everything else (spec obligations, `tools/` consumers, examples,
docs) is an OPEN roster: those rows must point at something that exists, and the
census subset of them is derived mechanically, but the ledger does not claim to
enumerate every file of those trees.

A `done` row is exempt from the "must match something" and "must still carry
donor material" rules in both regimes: disposing a row is frequently what
deletes or renames its files, and the ledger keeps the completed row as the
audit record.

## Maintaining the roster

`ESTABLISHED_ROWS` covers the ledger exactly: every identity in it must be in
the ledger, and every ledger row must be in it. Adding a row is therefore two
edits in one change — the row and its roster identity — and the failure prints
the exact line to paste. A row outside the roster is a row that can be removed
again silently, so a roster that only held the rows present when it was written
would be load-bearing for those rows alone.

Renaming a row's path edits the row and its roster identity together. That
remains the one legitimate reason to change an existing entry, and it is then a
deliberate, reviewable edit in the same change as the rename.

Exit code:
    0  the ledger covers the donor tree and its live consumers, and every row is
       established, decidable, and current
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

# --- The live-consumer census -------------------------------------------------
# Two signals, both of which name something that BREAKS when the donor artifact
# is deleted. Nothing looser: incidental prose is deliberately not policed.
#
#   require     a Clojure/EDN libspec naming a `re-frame.ui…` namespace —
#               `[re-frame.ui :as ui]`, `[re-frame.ui.tree :as tree]`, a
#               build's `:entries [re-frame.ui.g13.dev]` vector, or a
#               shadow-cljs `:build-hooks [(re-frame.ui.compiler.build-hook/hook)]`
#               entry. That last shape needs the optional `(` and the `/`
#               terminator: without them the census silently missed every
#               shadow-cljs.edn that wires the donor's build hook (rf2-vwum3).
#   coordinate  the donor artifact coordinate in DEPENDENCY position —
#               `day8/re-frame2-ui {…}` in a deps.edn, a generated scaffold, or
#               an install instruction. A bare mention in prose does not match.
CENSUS_REQUIRE_RE = re.compile(r"\[\(?re-frame\.ui[A-Za-z0-9._-]*[\s\]/]")
CENSUS_COORD_RE = re.compile(r"day8/re-frame2-ui\s+\{")

# The require signal only means anything in a file that can carry a libspec.
CENSUS_CODE_SUFFIXES = (".clj", ".cljs", ".cljc", ".edn")
# The files the census reads at all. Binary and generated-asset trees are not
# consumers of anything; skipping them by suffix keeps the scan under a second.
CENSUS_TEXT_SUFFIXES = CENSUS_CODE_SUFFIXES + (
    ".json", ".md", ".cjs", ".mjs", ".js", ".sh", ".yml", ".yaml",
)

# Historical mentions are EVIDENCE, not migration consumers: they record what was
# decided and when, and deleting the donor breaks none of them. The ledger and
# this script are excluded for the same reason — they quote the donor in order to
# retire it. This exclusion rule is stated in the ledger itself; keep the two in
# step.
CENSUS_EXEMPT_PREFIXES = (
    DONOR_TREE,      # the donor is not a consumer of itself
    "docs/EP/",      # enhancement proposals: the historical record
    "docs/design/",  # design records: the historical record
    ".beads/",       # issue tracker export
)
CENSUS_EXEMPT_PATHS = (
    "CHANGELOG.md",
    LEDGER_REL,
    "scripts/check_donor_inventory.py",
)

# --- Donor material -----------------------------------------------------------
# Looser than the census on purpose: this decides whether a pending row's target
# still has ANY donor material in it. A row whose files no longer mention the
# donor in any spelling has had its subject migrated out from under it.
DONOR_EVIDENCE_RE = re.compile(
    r"re-frame\.ui"      # the namespace, in prose or code
    r"|re_frame\.ui"     # the munged namespace
    r"|re_frame/ui"      # the source path
    r"|re-frame2-ui"     # the artifact coordinate
    r"|re_frame2_ui"     # the munged coordinate
    r"|implementation/ui"  # the donor tree
    # The donor build ids and npm entry points. A runner can couple to the donor
    # entirely through its BUILD ID and never spell `re-frame.ui` at all — that is
    # how `implementation/scripts/run-ui-g8.cjs` came to carry no recognised donor
    # material while its `:ui-g13` sibling passed on an incidental prose mention
    # (rf2-vfv8r). The donor-owned ids are enumerated in `implementation/deps.edn`
    # and `implementation/shadow-cljs.edn`; keep this alternation in step with them.
    r"|node-test-ui"
    r"|ui-bench"
    r"|ui-g8"
    r"|ui-g13"
    r"|check-ui-"
    r"|run-ui-"
    r"|test:ui"
)


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

    @property
    def key(self) -> str:
        """The row's stable identity — what `ESTABLISHED_ROWS` records."""
        return self.pattern if self.pattern is not None else self.cell

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


def make_reader(repo_root: Path):
    """A `path -> text | None` reader over the working tree."""
    def read(path: str) -> str | None:
        try:
            return (repo_root / path).read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            return None
    return read


# --- Check 1: the donor tree is partitioned -----------------------------------
def check_partition(rows: list[Row], tracked: list[str]) -> list[str]:
    """Every tracked donor file is claimed by exactly one row."""
    problems: list[str] = []
    donor_files = [path for path in tracked if path.startswith(DONOR_TREE)]

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

    return problems


# --- Check 2: live consumers are classified -----------------------------------
def live_consumers(tracked: list[str], read) -> dict[str, tuple[str, ...]]:
    """The census: tracked files outside the donor tree that still consume it."""
    found: dict[str, tuple[str, ...]] = {}
    for path in tracked:
        if path.startswith(CENSUS_EXEMPT_PREFIXES) or path in CENSUS_EXEMPT_PATHS:
            continue
        if not path.endswith(CENSUS_TEXT_SUFFIXES):
            continue
        text = read(path)
        if text is None:
            continue
        signals = []
        if path.endswith(CENSUS_CODE_SUFFIXES) and CENSUS_REQUIRE_RE.search(text):
            signals.append("require")
        if CENSUS_COORD_RE.search(text):
            signals.append("coordinate")
        if signals:
            found[path] = tuple(signals)
    return found


def check_consumers(rows: list[Row],
                    consumers: dict[str, tuple[str, ...]]) -> list[str]:
    """Every live consumer is claimed, and claimed by a row that is still open."""
    problems: list[str] = []
    path_rows = [row for row in rows if row.pattern is not None]

    for path in sorted(consumers):
        signals = "/".join(consumers[path])
        owners = [row for row in path_rows
                  if fnmatch.fnmatchcase(path, row.pattern)]
        if not owners:
            problems.append(
                f"DONOR-LEDGER-UNCOVERED-CONSUMER: {path} still consumes the "
                f"donor ({signals}) and no row of {LEDGER_REL} claims it. Add a "
                "row with an explicit MOVE / REPLACE / DELETE disposition, or "
                "remove the dependency — the donor cannot be deleted while this "
                "file is unaccounted for."
            )
        elif all(row.status == "done" for row in owners):
            where = ", ".join(f"line {row.lineno} ({row.cell})" for row in owners)
            problems.append(
                f"DONOR-LEDGER-PREMATURE-DONE: {path} still consumes the donor "
                f"({signals}), but every row claiming it is marked done — "
                f"{where}. Remove the dependency first; the row flips in the "
                "same change that removes it."
            )

    return problems


# --- Check 3: the ledger and the roster cover each other ----------------------
def check_roster(rows: list[Row],
                 roster: tuple[str, ...] | None = None) -> list[str]:
    """The ledger and the retention roster name exactly the same identities.

    Two directions, and both are load-bearing. A roster identity missing from
    the ledger is a deleted row. A ledger row missing from the roster is a row
    that could be deleted later without the first check noticing — which is how
    a roster written once stops covering everything written after it. `roster`
    is injectable so the self-tests exercise this function itself rather than a
    restatement of it.
    """
    if roster is None:
        roster = ESTABLISHED_ROWS

    present = {row.key for row in rows}
    problems = [
        f"DONOR-LEDGER-ROW-REMOVED: `{key}` is an established ledger row and it "
        f"is no longer in {LEDGER_REL}. Rows are never deleted — deleting one "
        "lowers the undisposed count without disposing of anything, which is "
        "the one failure a deletion gate must not have. Flip the row's status "
        "to `done` instead; if the row was renamed, update its identity in "
        "ESTABLISHED_ROWS in the same change."
        for key in roster if key not in present
    ]

    established = set(roster)
    seen: set[str] = set()
    for row in rows:
        if row.key in established or row.key in seen:
            continue
        seen.add(row.key)
        problems.append(
            f"DONOR-LEDGER-ROW-UNROSTERED: {LEDGER_REL}:{row.lineno} "
            f"({row.cell}) is a ledger row whose identity is not in "
            "ESTABLISHED_ROWS. Add the line  "
            f'"{row.key}",  to ESTABLISHED_ROWS in '
            "scripts/check_donor_inventory.py in the same change that adds the "
            "row. Until it is there this row can be deleted again without the "
            "gate noticing, which lowers historical coverage silently."
        )

    return problems


# --- Check 4: pending rows still point at donor material ----------------------
def check_current(rows: list[Row], tracked: list[str], read) -> list[str]:
    """A pending row matches real files, and those files are still donor-ish."""
    problems: list[str] = []
    for row in rows:
        if row.pattern is None or row.status == "done":
            continue
        universe = ([path for path in tracked if path.startswith(DONOR_TREE)]
                    if row.is_donor_tree else tracked)
        matched = [path for path in universe
                   if fnmatch.fnmatchcase(path, row.pattern)]
        if not matched:
            problems.append(
                f"DONOR-LEDGER-STALE: {LEDGER_REL}:{row.lineno} claims "
                f"`{row.pattern}`, which matches no tracked file. Either the "
                "path moved (fix the row) or the row was disposed (set its "
                "status to `done`)."
            )
            continue
        if row.is_donor_tree:
            continue  # every donor-tree file is donor material by definition
        texts = [text for text in (read(path) for path in matched)
                 if text is not None]
        if texts and not any(DONOR_EVIDENCE_RE.search(text) for text in texts):
            problems.append(
                f"DONOR-LEDGER-DRIFTED: {LEDGER_REL}:{row.lineno} claims "
                f"`{row.pattern}` is still pending, but no file it matches "
                "carries any donor material. Either the surface was migrated "
                "and the row should be `done`, or the path was reused for "
                "something else and the row must be re-keyed to wherever the "
                "donor content actually went."
            )
    return problems


def undisposed(rows: list[Row]) -> list[Row]:
    return [row for row in rows if row.status == "pending"]


def format_report(rows: list[Row]) -> str:
    """Human-readable undisposed-row report."""
    pending = undisposed(rows)
    lines = [
        f"donor inventory: {len(rows)} rows, {len(pending)} not yet disposed, "
        f"{len(rows) - len(pending)} disposed and retained as the audit record.",
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


def check(repo_root: Path, *, verbose: bool = False,
          ci: bool = False) -> tuple[int, list[Row]]:
    ledger = repo_root / LEDGER_REL
    if not ledger.is_file():
        raise SetupError(f"ledger not found at {LEDGER_REL} under {repo_root}")

    rows, problems = parse_ledger(ledger.read_text(encoding="utf-8"))
    if verbose:
        sys.stderr.write(f"parsed {len(rows)} ledger rows from {LEDGER_REL}\n")

    tracked = tracked_files(repo_root)
    read = make_reader(repo_root)
    consumers = live_consumers(tracked, read)
    if verbose:
        sys.stderr.write(
            f"census found {len(consumers)} live donor consumers outside "
            f"{DONOR_TREE}\n"
        )

    problems.extend(check_roster(rows))
    problems.extend(check_partition(rows, tracked))
    problems.extend(check_consumers(rows, consumers))
    problems.extend(check_current(rows, tracked, read))

    for problem in problems:
        prefix = "::error::" if ci else ""
        sys.stderr.write(f"{prefix}{problem}\n")

    return len(problems), rows


# --- Self-tests ---------------------------------------------------------------
_FIXTURE_HEADER = (
    "| Donor row | What it is | Disposition | Slice | Status |\n"
    "|---|---|---|---|---|\n"
)


def _fixture_reader(contents: dict[str, str]):
    return lambda path: contents.get(path)


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
    contents = {
        "implementation/ui/src/re_frame/ui.cljc": "(ns re-frame.ui)",
        "implementation/ui/src/re_frame/ui/tree.cljc": "(ns re-frame.ui.tree)",
        "implementation/ui/test/re_frame/ui/tree_jvm_test.clj": "(ns x)",
        "tools/story/deps.edn": "{:deps {day8/re-frame2-ui {:local/root \"..\"}}}",
        "spec/004-Views.md": "the re-frame.ui compiled language",
    }
    read = _fixture_reader(contents)

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
    expect("A3 clean partition", check_partition(rows, tracked), [])
    expect("A4 label row is not a path row", rows[4].pattern, None)
    expect("A5 undisposed count", len(undisposed(rows)), 5)
    expect("A6 nothing stale or drifted", check_current(rows, tracked, read), [])
    census = live_consumers(tracked, read)
    expect("A7 census finds the coordinate", census, {"tools/story/deps.edn": ("coordinate",)})
    expect("A8 census is covered", check_consumers(rows, census), [])
    expect("A9 label row identity is its cell",
           rows[4].key, "`local` and its placement machinery")

    # A missing donor file must be named by the failure.
    missing = _FIXTURE_HEADER + (
        "| `implementation/ui/src/re_frame/ui.cljc` | facade | REPLACE | F1 | pending |\n"
        "| `implementation/ui/test/*` | donor tests | MOVE | F6 | pending |\n"
    )
    rows, _ = parse_ledger(missing)
    problems = check_partition(rows, tracked)
    expect("B1 uncovered detected", len(problems), 1)
    expect(
        "B2 uncovered names the file",
        "implementation/ui/src/re_frame/ui/tree.cljc" in problems[0],
        True,
    )
    expect("B3 uncovered is the right class", problems[0].startswith(
        "DONOR-LEDGER-UNCOVERED:"), True)

    # Two rows claiming one file is a defect.
    doubled = _FIXTURE_HEADER + (
        "| `implementation/ui/src/*` | everything | MOVE | F1 | pending |\n"
        "| `implementation/ui/src/re_frame/ui/tree.cljc` | JVM tree | MOVE | F1 | pending |\n"
        "| `implementation/ui/test/*` | donor tests | MOVE | F6 | pending |\n"
    )
    rows, _ = parse_ledger(doubled)
    problems = check_partition(rows, tracked)
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
    problems = check_current(rows, tracked, read)
    expect("D1 stale detected", len(problems), 1)
    expect("D2 stale class", problems[0].startswith("DONOR-LEDGER-STALE"), True)

    disposed = stale.replace("| `tools/gone/deps.edn` | vanished | MOVE | F6 | pending |",
                             "| `tools/gone/deps.edn` | vanished | MOVE | F6 | done |")
    rows, _ = parse_ledger(disposed)
    expect("D3 done row is not stale", check_current(rows, tracked, read), [])
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

    # --- The census ----------------------------------------------------------
    # A live consumer that no row claims must fail, and be named.
    consumer_tracked = tracked + [
        "implementation/ssr/deps.edn",
        "implementation/ssr/test/re_frame/ssr/hydrate_cljs_test.cljs",
        "docs/EP/EP-0036-the-freehand-view-substrate-programme.md",
        "CHANGELOG.md",
    ]
    consumer_contents = dict(contents)
    consumer_contents.update({
        "implementation/ssr/deps.edn": "{:deps {day8/re-frame2-ui {:local/root \"../ui\"}}}",
        "implementation/ssr/test/re_frame/ssr/hydrate_cljs_test.cljs":
            "(ns t (:require [re-frame.ui :as ui]))",
        # Historical evidence, not a consumer: both are exempt by name.
        "docs/EP/EP-0036-the-freehand-view-substrate-programme.md":
            "day8/re-frame2-ui {:local/root \"ui\"} and [re-frame.ui :as ui]",
        "CHANGELOG.md": "day8/re-frame2-ui {:mvn/version \"0.1.0\"}",
    })
    consumer_read = _fixture_reader(consumer_contents)
    census = live_consumers(consumer_tracked, consumer_read)
    expect("H1 census finds both new consumers",
           sorted(census), ["implementation/ssr/deps.edn",
                            "implementation/ssr/test/re_frame/ssr/hydrate_cljs_test.cljs",
                            "tools/story/deps.edn"])
    expect("H2 historical mentions are excluded",
           "docs/EP/EP-0036-the-freehand-view-substrate-programme.md" in census
           or "CHANGELOG.md" in census, False)
    expect("H3 signals are named",
           census["implementation/ssr/test/re_frame/ssr/hydrate_cljs_test.cljs"],
           ("require",))

    # The shadow-cljs build-hook shape (rf2-vwum3): `[(re-frame.ui…/hook)]`.
    # The leading paren and the `/` terminator both used to defeat the require
    # signal, so a generated scaffold wiring the donor's build hook was invisible
    # to the census.
    hook_tracked = ["app/shadow-cljs.edn"]
    hook_read = _fixture_reader({
        "app/shadow-cljs.edn":
            "{:build-defaults {:build-hooks [(re-frame.ui.compiler.build-hook/hook)]}}",
    })
    expect("H3a census sees a wired build hook",
           live_consumers(hook_tracked, hook_read),
           {"app/shadow-cljs.edn": ("require",)})

    rows, _ = parse_ledger(full)
    problems = check_consumers(rows, census)
    expect("H4 two uncovered consumers", len(problems), 2)
    expect("H5 uncovered consumer class",
           problems[0].startswith("DONOR-LEDGER-UNCOVERED-CONSUMER"), True)
    expect("H6 uncovered consumer names the path",
           "implementation/ssr/deps.edn" in problems[0], True)

    covered = full + (
        "| `implementation/ssr/deps.edn` | ssr coord | REPLACE | F6 | pending |\n"
        "| `implementation/ssr/test/re_frame/ssr/*` | ssr tests | MOVE | F5 | pending |\n"
    )
    rows, _ = parse_ledger(covered)
    expect("H7 covered census is clean", check_consumers(rows, census), [])

    premature = covered.replace(
        "| `implementation/ssr/deps.edn` | ssr coord | REPLACE | F6 | pending |",
        "| `implementation/ssr/deps.edn` | ssr coord | REPLACE | F6 | done |")
    rows, _ = parse_ledger(premature)
    problems = check_consumers(rows, census)
    expect("H8 done-while-live detected", len(problems), 1)
    expect("H9 premature-done class",
           problems[0].startswith("DONOR-LEDGER-PREMATURE-DONE"), True)

    # --- Row retention -------------------------------------------------------
    # This is the arm the ledger was missing: a DELETED row must fail, including
    # after a legitimate pending -> done transition on that same row.
    roster_ledger = _FIXTURE_HEADER + (
        "| `implementation/ui/src/re_frame/ui.cljc` | facade | REPLACE | F1 | pending |\n"
        "| `implementation/ui/src/re_frame/ui/tree.cljc` | JVM tree | MOVE | F1 | pending |\n"
        "| `spec/004-Views.md` | spec obligation | MOVE | F0 | pending |\n"
        "| `local` and its placement machinery | donor form | DELETE | F1 | pending |\n"
    )
    roster = (
        "implementation/ui/src/re_frame/ui.cljc",
        "implementation/ui/src/re_frame/ui/tree.cljc",
        "spec/004-Views.md",
        "`local` and its placement machinery",
    )

    # The real function under the real invariant — `roster` is injected so the
    # fixtures cannot drift into testing a restatement of the check.
    def roster_defects(text: str, roster_=roster) -> list[str]:
        parsed, _ = parse_ledger(text)
        return check_roster(parsed, roster_)

    def classes(problems: list[str]) -> list[str]:
        return [problem.split(":", 1)[0] for problem in problems]

    expect("I1 pristine roster is satisfied", roster_defects(roster_ledger), [])

    deleted = roster_ledger.replace(
        "| `spec/004-Views.md` | spec obligation | MOVE | F0 | pending |\n", "")
    problems = roster_defects(deleted)
    expect("I2 deleted row is caught", classes(problems),
           ["DONOR-LEDGER-ROW-REMOVED"])
    expect("I3 deleted row is named", "spec/004-Views.md" in problems[0], True)

    label_deleted = roster_ledger.replace(
        "| `local` and its placement machinery | donor form | DELETE | F1 | pending |\n", "")
    problems = roster_defects(label_deleted)
    expect("I4 deleted label row is caught", classes(problems),
           ["DONOR-LEDGER-ROW-REMOVED"])
    expect("I5 deleted label row is named",
           "`local` and its placement machinery" in problems[0], True)

    # The composed sequence. A guard that only ever sees a pristine baseline can
    # pass every single-step test and still be inert once the ledger has moved.
    progressed = roster_ledger.replace(
        "| `spec/004-Views.md` | spec obligation | MOVE | F0 | pending |",
        "| `spec/004-Views.md` | spec obligation | MOVE | F0 | done |")
    expect("J1 pending -> done keeps the row", roster_defects(progressed), [])
    parsed, _ = parse_ledger(progressed)
    expect("J2 done row is retained in the report",
           "4 rows, 3 not yet disposed, 1 disposed" in format_report(parsed), True)
    then_deleted = progressed.replace(
        "| `spec/004-Views.md` | spec obligation | MOVE | F0 | done |\n", "")
    expect("J3 deletion AFTER disposition still fails",
           classes(roster_defects(then_deleted)), ["DONOR-LEDGER-ROW-REMOVED"])

    # --- The same sequence, for a row added AFTER the roster was written -------
    # This is the case a floor-shaped roster could not see: the row was never
    # established, so deleting it looked like nothing had happened. Each step
    # starts from the state the previous one left, not from the baseline.
    added = roster_ledger + (
        "| `tools/newcomer/deps.edn` | a consumer discovered later | MOVE | F6 | pending |\n"
    )
    problems = roster_defects(added)
    expect("L1 a new row must enter the roster", classes(problems),
           ["DONOR-LEDGER-ROW-UNROSTERED"])
    expect("L2 the diagnostic names the row's line",
           f"{LEDGER_REL}:7" in problems[0], True)
    expect("L3 the diagnostic prints the line to paste",
           '"tools/newcomer/deps.edn",' in problems[0], True)
    expect("L4 the diagnostic names the file to paste it into",
           "ESTABLISHED_ROWS in scripts/check_donor_inventory.py" in problems[0],
           True)

    # Step 1 — the row and its roster identity land together.
    grown = roster + ("tools/newcomer/deps.edn",)
    expect("L5 row + roster identity together is clean",
           roster_defects(added, grown), [])

    # Step 2 — the consumer goes, the row is disposed. The row stays.
    added_done = added.replace(
        "| `tools/newcomer/deps.edn` | a consumer discovered later | MOVE | F6 | pending |",
        "| `tools/newcomer/deps.edn` | a consumer discovered later | MOVE | F6 | done |")
    expect("L6 disposing the new row keeps it", roster_defects(added_done, grown), [])

    # Step 3 — deleting the disposed row is the failure the gate exists for.
    added_gone = added_done.replace(
        "| `tools/newcomer/deps.edn` | a consumer discovered later | MOVE | F6 | done |\n", "")
    problems = roster_defects(added_gone, grown)
    expect("L7 deleting the disposed new row fails", classes(problems),
           ["DONOR-LEDGER-ROW-REMOVED"])
    expect("L8 the deletion names the new row",
           "tools/newcomer/deps.edn" in problems[0], True)

    # A label row added later is held the same way.
    label_added = roster_ledger + (
        "| ambient `dev-only` diagnostics | a contract obligation | DELETE | F2 | pending |\n"
    )
    expect("L9 a new label row must enter the roster too",
           classes(roster_defects(label_added)), ["DONOR-LEDGER-ROW-UNROSTERED"])
    expect("L10 the label identity is its whole cell",
           '"ambient `dev-only` diagnostics",' in roster_defects(label_added)[0],
           True)

    # The real roster is wired to the real check in both directions: an empty
    # ledger loses every established row, and the shipped ledger must cover the
    # shipped roster exactly, so `check_roster` cannot be inert.
    expect("J4 check_roster reads ESTABLISHED_ROWS",
           len(check_roster([])), len(ESTABLISHED_ROWS))
    expect("J5 the real roster is populated", len(ESTABLISHED_ROWS) > 100, True)
    expect("J6 roster identities are unique",
           len(set(ESTABLISHED_ROWS)), len(ESTABLISHED_ROWS))

    # --- Semantic drift ------------------------------------------------------
    # A pending row pointing at a path that no longer carries donor material at
    # all: the shape of a row whose subject moved and whose path was reused.
    drift_tracked = ["spec/004-Views.md", "implementation/ui/src/re_frame/ui.cljc"]
    drift_read = _fixture_reader({
        "spec/004-Views.md": "the common Freehand contract; nothing donor here",
        "implementation/ui/src/re_frame/ui.cljc": "(ns re-frame.ui)",
    })
    drifted = _FIXTURE_HEADER + (
        "| `implementation/ui/src/*` | sources | MOVE | F1 | pending |\n"
        "| `spec/004-Views.md` | donor compiled language | MOVE | F0 | pending |\n"
    )
    rows, _ = parse_ledger(drifted)
    problems = check_current(rows, drift_tracked, drift_read)
    expect("K1 drifted row detected", len(problems), 1)
    expect("K2 drift class", problems[0].startswith("DONOR-LEDGER-DRIFTED"), True)
    expect("K3 drift names the path", "spec/004-Views.md" in problems[0], True)

    rekeyed = drifted.replace(
        "| `spec/004-Views.md` | donor compiled language | MOVE | F0 | pending |",
        "| `spec/004-Views.md` | donor compiled language | MOVE | F0 | done |")
    rows, _ = parse_ledger(rekeyed)
    expect("K4 a disposed row does not drift",
           check_current(rows, drift_tracked, drift_read), [])

    if failures:
        sys.stderr.write(f"self-test: {failures} case(s) failed.\n")
        return 1
    sys.stderr.write("self-test: all cases passed.\n")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Prove the Freehand donor-inventory ledger covers every tracked "
            "re-frame.ui file and every live donor consumer with an explicit "
            "MOVE/REPLACE/DELETE disposition, that the ledger and its retention "
            "roster cover each other exactly so no row can be deleted, and "
            "report how many rows are not yet disposed."
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


# --- The established-row roster -----------------------------------------------
# Row identities the ledger has established. Every one must still be present:
# a row is disposed by flipping its status to `done`, never by deletion. See
# "Maintaining the roster" in the module docstring. Ordered as the ledger reads.
ESTABLISHED_ROWS = (
    # Ruled contract dispositions
    "`local` and its placement machinery",
    "instance state and generic storage verbs",
    "refs, effects, and the React hook tier",
    "callable JVM view values",
    "placeholder provenance",
    "compiled parent to interpreted child crossing",
    "controlled scheduling",
    "key-condition event maps",
    "`spread-safe`/`spread` and `render-fn`/`slot`",
    "presence runtime",
    "`route-link`",
    "analyzer, both emitters, ViewCell reactor, manifest/elision, diagnostic taxonomy, structural test surface",
    # Donor sources
    "implementation/ui/src/re_frame/ui.cljc",
    "implementation/ui/src/re_frame/ui/client.cljs",
    "implementation/ui/src/re_frame/ui/compiler.cljc",
    "implementation/ui/src/re_frame/ui/compiler/a11y.cljc",
    "implementation/ui/src/re_frame/ui/compiler/analyze.cljc",
    "implementation/ui/src/re_frame/ui/compiler/binding_plan.cljc",
    "implementation/ui/src/re_frame/ui/compiler/build.cljc",
    "implementation/ui/src/re_frame/ui/compiler/build_hook.clj",
    "implementation/ui/src/re_frame/ui/compiler/emit_cljs.cljc",
    "implementation/ui/src/re_frame/ui/compiler/emit_jvm.cljc",
    "implementation/ui/src/re_frame/ui/compiler/env.cljc",
    "implementation/ui/src/re_frame/ui/compiler/harvest.clj",
    "implementation/ui/src/re_frame/ui/compiler/header.cljc",
    "implementation/ui/src/re_frame/ui/compiler/root.cljc",
    "implementation/ui/src/re_frame/ui/eq.cljc",
    "implementation/ui/src/re_frame/ui/events.cljs",
    "implementation/ui/src/re_frame/ui/fingerprint.cljc",
    "implementation/ui/src/re_frame/ui/frames.cljc",
    "implementation/ui/src/re_frame/ui/hooks.cljc",
    "implementation/ui/src/re_frame/ui/presence_runtime.cljc",
    "implementation/ui/src/re_frame/ui/react.cljc",
    "implementation/ui/src/re_frame/ui/reactive.cljc",
    "implementation/ui/src/re_frame/ui/route_link_seam.cljc",
    "implementation/ui/src/re_frame/ui/rules.cljc",
    "implementation/ui/src/re_frame/ui/runtime.cljs",
    "implementation/ui/src/re_frame/ui/semantic.cljc",
    "implementation/ui/src/re_frame/ui/sub_overrides.cljs",
    "implementation/ui/src/re_frame/ui/substrate.cljs",
    "implementation/ui/src/re_frame/ui/test.cljc",
    "implementation/ui/src/re_frame/ui/tool.cljc",
    "implementation/ui/src/re_frame/ui/tool/evidence.cljc",
    "implementation/ui/src/re_frame/ui/tree.cljc",
    "implementation/ui/src/re_frame/ui/viewcell.cljs",
    # Donor tests
    "implementation/ui/test/re_frame/ui/a11y_*",
    "implementation/ui/test/re_frame/ui/adapter_*",
    "implementation/ui/test/re_frame/ui/analyze_*",
    "implementation/ui/test/re_frame/ui/authored_collision_*",
    "implementation/ui/test/re_frame/ui/binding_plan_*",
    "implementation/ui/test/re_frame/ui/build_*",
    "implementation/ui/test/re_frame/ui/callbacks_*",
    "implementation/ui/test/re_frame/ui/committed_events_*",
    "implementation/ui/test/re_frame/ui/compiler_*",
    "implementation/ui/test/re_frame/ui/conditional_root_annotation_*",
    "implementation/ui/test/re_frame/ui/conditional_sub_*",
    "implementation/ui/test/re_frame/ui/custom_element_*",
    "implementation/ui/test/re_frame/ui/defview_grammar_*",
    "implementation/ui/test/re_frame/ui/digest_probe/*",
    "implementation/ui/test/re_frame/ui/emit_cljs_*",
    "implementation/ui/test/re_frame/ui/eq_*",
    "implementation/ui/test/re_frame/ui/error_roster_*",
    "implementation/ui/test/re_frame/ui/event_*",
    "implementation/ui/test/re_frame/ui/exact_render_capture_*",
    "implementation/ui/test/re_frame/ui/fast_refresh_shell_*",
    "implementation/ui/test/re_frame/ui/fingerprint_*",
    "implementation/ui/test/re_frame/ui/frame_*",
    "implementation/ui/test/re_frame/ui/g13/*",
    "implementation/ui/test/re_frame/ui/g14_*",
    "implementation/ui/test/re_frame/ui/hidden_sub_macros.clj",
    "implementation/ui/test/re_frame/ui/hooks_*",
    "implementation/ui/test/re_frame/ui/local_effect_*",
    "implementation/ui/test/re_frame/ui/mounted_*",
    "implementation/ui/test/re_frame/ui/parity_*",
    "implementation/ui/test/re_frame/ui/passive_events_*",
    "implementation/ui/test/re_frame/ui/preflight_*",
    "implementation/ui/test/re_frame/ui/presence_*",
    "implementation/ui/test/re_frame/ui/raw_foreign_boundary_*",
    "implementation/ui/test/re_frame/ui/react_export_bridge_*",
    "implementation/ui/test/re_frame/ui/react_interop_*",
    "implementation/ui/test/re_frame/ui/react_render_*",
    "implementation/ui/test/re_frame/ui/reactive_*",
    "implementation/ui/test/re_frame/ui/render_batch_*",
    "implementation/ui/test/re_frame/ui/render_capture_*",
    "implementation/ui/test/re_frame/ui/render_key_dom_stamp_*",
    "implementation/ui/test/re_frame/ui/render_static_strip_*",
    "implementation/ui/test/re_frame/ui/reserved_head_reject_*",
    "implementation/ui/test/re_frame/ui/root_*",
    "implementation/ui/test/re_frame/ui/route_link_*",
    "implementation/ui/test/re_frame/ui/rules_*",
    "implementation/ui/test/re_frame/ui/s3_*",
    "implementation/ui/test/re_frame/ui/s4_*",
    "implementation/ui/test/re_frame/ui/s5_*",
    "implementation/ui/test/re_frame/ui/semantic_normalize_*",
    "implementation/ui/test/re_frame/ui/serialiser_rules_*",
    "implementation/ui/test/re_frame/ui/shadow_config_*",
    "implementation/ui/test/re_frame/ui/skeleton_*",
    "implementation/ui/test/re_frame/ui/slice_memo_*",
    "implementation/ui/test/re_frame/ui/slot_*",
    "implementation/ui/test/re_frame/ui/spread_*",
    "implementation/ui/test/re_frame/ui/ssr_reinit_*",
    "implementation/ui/test/re_frame/ui/sub_overrides_*",
    "implementation/ui/test/re_frame/ui/substrate_flush_*",
    "implementation/ui/test/re_frame/ui/teardown_falsy_*",
    "implementation/ui/test/re_frame/ui/test_*",
    "implementation/ui/test/re_frame/ui/tool_*",
    "implementation/ui/test/re_frame/ui/tree_*",
    "implementation/ui/test/re_frame/ui/viewcell_*",
    "implementation/ui/test/re_frame/realworld_*",
    # Donor fixtures, probes, and testbeds
    "implementation/ui/bench/*",
    "implementation/ui/cache-carrier-probe/*",
    "implementation/ui/dev/*",
    "implementation/ui/g8/*",
    "implementation/ui/g13/*",
    "implementation/ui/proof-pack/*",
    "implementation/ui/scaffold-smoke/*",
    "implementation/ui/testbed/*",
    # Donor artifact and build wiring
    "implementation/ui/deps.edn",
    "implementation/deps.edn",
    "implementation/shadow-cljs.edn",
    "implementation/package.json",
    "implementation/scripts/check-ui-adapter-isolation.cjs",
    "implementation/scripts/check-ui-facade-isolation.cjs",
    "implementation/scripts/check-ui-warm-watch.cjs",
    "implementation/scripts/check-ui-mounted-prod-elision.cjs",
    "implementation/scripts/run-ui-bench.cjs",
    "implementation/scripts/run-ui-g8.cjs",
    "implementation/scripts/run-ui-g13.cjs",
    "implementation/scripts/lib/g13-timing-evidence.cjs",
    "implementation/scripts/lib/g8-latency-evidence.cjs",
    "implementation/scripts/bundle-isolation-positive-control/*",
    "implementation/scripts/_g8-latency-evidence.test.cjs",
    "implementation/scripts/_g13-timing-evidence.test.cjs",
    "implementation/scripts/_release-ui-required-gate.test.cjs",
    "implementation/scripts/_ui-deps-edn-boundary.test.cjs",
    "implementation/scripts/_changed-surfaces.test.cjs",
    "implementation/adapters/scripts/adapter-smoke-filter.cjs",
    "implementation/adapters/scripts/serve-and-run-adapter-smokes.cjs",
    "implementation/adapters/scripts/run-adapter-smokes.cjs",
    "implementation/scripts/_adapter-smoke-filter.test.cjs",
    "implementation/scripts/_reagent-slim-smoke-policy.test.cjs",
    ".github/scripts/verify-version-lockstep.sh",
    ".github/scripts/report-changed-surfaces.sh",
    ".github/workflows/test.yml",
    ".github/workflows/release.yml",
    ".github/workflows/lint.yml",
    ".github/workflows/portability.yml",
    "`TESTING.md`",
    # Donor obligations in the spec tree
    "spec/004D-Freehand-Compiled-Grammar.md",
    "spec/004B-UI-Tree-and-Conversion.md",
    "spec/004C-Roots-and-Mount.md",
    "spec/006-ReactiveSubstrate.md",
    "spec/008-Testing.md",
    "spec/009-Instrumentation.md",
    "spec/011-SSR.md",
    "spec/012-Routing.md",
    "spec/API.md",
    "spec/Ownership.md",
    "spec/Conventions.md",
    "spec/conformance/S3-view-conformance-profile.md",
    "spec/conformance/S4-view-conformance-profile.md",
    "spec/conformance/S5-view-conformance-profile.md",
    "spec/Pattern-StatefulComponents.md",
    "spec/api-manifest.edn",
    "spec/api-manifest-metadata.edn",
    # Donor consumers in the implementation tree
    "implementation/ssr/deps.edn",
    "implementation/ssr/src/re_frame/ssr/ui_tree.cljc",
    "implementation/ssr/test/re_frame/ssr/emit_ui_tree_cljs_test.cljc",
    "implementation/ssr/test/re_frame/ssr/render_static_jvm_test.clj",
    "implementation/ssr/test/re_frame/ssr/root_manifest_cljs_test.cljc",
    "implementation/ssr/test/re_frame/ssr/hydrate_root_seam_dom_cljs_test.cljs",
    "implementation/ssr/test/re_frame/ssr/*_hydration_dom_cljs_test.cljs",
    "implementation/ssr/test/re_frame/ssr/client_only_adoption_verification_dom_cljs_test.cljs",
    "implementation/adapters/reagent/test/re_frame/observation_port_watchable_host_*",
    "implementation/core/test/re_frame/elision_probe.cljs",
    "implementation/scripts/api-manifest/deps.edn",
    "implementation/scripts/api-manifest/src/re_frame/api_manifest/gen.clj",
    "implementation/scripts/api-manifest/src/re_frame/api_manifest/ui_context.clj",
    "implementation/scripts/api-manifest/test/re_frame/api_manifest/ui_context_test.clj",
    "implementation/scripts/api-manifest/probe/test/re_frame/api_manifest/cljs_manifest_probe_cljs_test.cljs",
    # Donor consumers in tools
    "tools/story/deps.edn",
    "tools/story/src/re_frame/story/late_bind.cljc",
    "tools/story/src/re_frame/story/sub_overrides.cljc",
    "tools/story/src/re_frame/story/play/*",
    "tools/story/test/re_frame/story/play/presence_*",
    "tools/story/test/re_frame/story/view_tool*",
    "tools/story/test/re_frame/story/realworld_ui_consumer_cljs_test.cljs",
    "tools/story/spec/017-Testing-Story.md",
    "tools/xray/deps.edn",
    "tools/xray/src/day8/re_frame2_xray/viewcell_evidence.cljs",
    "tools/xray/src/day8/re_frame2_xray/panels/reactive_panel_*",
    "tools/xray/test/day8/re_frame2_xray/viewcell_evidence_cljs_test.cljs",
    "tools/xray/test/day8/re_frame2_xray/realworld_ui_evidence_cljs_test.cljs",
    "tools/xray/spec/*",
    "tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/view_tool.cljs",
    "tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/descriptors_data.cljs",
    "tools/re-frame2-pair-mcp/spec/003-Tool-Catalogue.md",
    "tools/template/resources/day8/re_frame2_template/_ui/*",
    "tools/template/resources/day8/re_frame2_template/template.edn",
    "tools/template/spec/001-Substrate-Variants.md",
    "tools/mcp-conformance/test/end-to-end-re-frame2-pair.cjs",
    # Donor consumers in examples and testbeds
    "examples/ui/minimal-counter/*",
    "examples/real-apps/realworld_resources/ui_*",
    "tools/xray/testbeds/feature_matrix/scenarios.cjs",
    # Donor material in docs and skills
    "docs/core/re-frame.ui/*",
    "docs/core/how-to/install-re-frame-ui.md",
    "docs/core/how-to/measure-before-paint.md",
    "docs/core/views.md",
    "docs/api/re-frame.ui*.md",
    "skills/re-frame2-ui/*",
    "skills/reagent-migration/*",
    "`mkdocs.yml`",
)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
