#!/usr/bin/env python3
"""Validate the Freehand conformance index — ids, citations, fixtures, sections,
and the census that says every active row is actually PROVEN where it claims.

The Freehand view substrate proves its two-mode / multi-host parity row by row.
Each law carries a permanent `FH-<AREA>-<NNN>` id, and the index at
`spec/conformance/freehand/conformance-index.md` binds that id to the canonical
spec paragraph that OWNS the law, the modes and hosts it binds, the fixture that
proves it, and its status.  The scheme, the allocation rule, and the column
grammar are documented in `spec/conformance/freehand/README.md`.

Two independent guards live here, and the difference between them matters:

    * The STRUCTURAL check (`check`) validates the index as a document — that
      every id is well-formed, unique, filed under its own area, cites a real
      spec anchor, and names a fixture file that exists.
    * The EXECUTION CENSUS (`census`) reconciles the index against the suites
      that RUN it — that every active row's fixture is actually read by a test,
      and that every host the row claims is served by a lane one of those tests
      runs in.

Neither executes a fixture; running a law is the harness's job
(`spec/conformance/freehand/README.md` §What this is not).  What the census adds
is the missing edge between the ledger and the runs: without it a row can name a
real fixture nobody ever reads, and the structural check will call that green.

An id is an address.  An address that resolves to nothing is worse than no
address at all, so this guard fails the build when:

    * ILL-FORMED ID        — the id is not `FH-<AREA>-<NNN>` with a roster AREA
                             and a three-digit ordinal.
    * DUPLICATE ID         — the same id appears on two rows (an id is a
                             permanent citation; two rows make it ambiguous).
    * AREA MISMATCH        — the row's AREA is not the section it sits in.
    * OUT-OF-ORDER ID      — ordinals within a section are dense and ascending;
                             appending out of order means the allocation rule was
                             not followed and a collision is one merge away.
    * MISSING CITATION     — the canonical-paragraph cell is not a markdown link
                             with an anchor.
    * BROKEN CITATION      — the cited spec file does not exist, the anchor does
                             not resolve, or the target is not under `spec/`.
    * MISSING FIXTURE      — an `active` row names a fixture file that does not
                             exist, or names none at all.
    * MISPLACED FIXTURE    — a `planned` / `retired` row names a fixture.
    * BAD APPLICABILITY    — an unknown token, no mode token or two, or no host.
    * BAD STATUS           — a status outside the closed vocabulary.
    * BAD ROW              — a table row whose column count is not six.
    * ORPHAN ROW           — a row before the first area section.
    * BAD TABLE HEADER     — a section's table header is not the six columns.
    * UNKNOWN / DUPLICATE / MISSING AREA SECTION — the section roster no longer
                             matches the area roster.

and, from the execution census:

    * UNPROVEN ROW         — an `active` row whose fixture no test in
                             `implementation/freehand/test/` reads.  The row
                             names a file, the file exists, and nothing asserts
                             against it: a law proven by nobody.
    * UNEXECUTED HOST      — an `active` row claims a host no lane among its
                             proving tests serves.  A `common jvm browser` row
                             read only from a `.cljs` suite is a claim about the
                             JVM that the JVM never sees.
    * DANGLING PROOF       — a test reads a fixture for an id the index does not
                             carry as an `active` row (a deleted or retired law
                             whose suite outlived it).
    * ORPHAN FIXTURE       — a fixture file on disk no `active` row names.  A
                             deletion that removes the row but leaves the bytes
                             is how a resurrected id acquires a stale meaning.

Every defect names the offending row (its id, or its raw first cell when the id
itself is the defect) and its line number.

Exit code:
    0  the index is valid
    1  at least one defect
    2  invocation / setup error

Anchor resolution reuses the slug index from `check_doc_slugs.py` rather than
re-deriving it, so a citation resolves under exactly the rule that governs every
other link in the corpus — one slugifier, one authority.  That import is the
script's only dependency beyond the stdlib (it pulls in pymdown-extensions,
already pinned in requirements.txt).
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

# One slugifier for the whole corpus (see module docstring).  check_doc_slugs
# lives beside this file and exits(2) itself if pymdown-extensions is absent.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_doc_slugs import _slug_index  # noqa: E402

INDEX_REL = Path("spec/conformance/freehand/conformance-index.md")
FIXTURES_REL = Path("spec/conformance/freehand/fixtures")
TESTS_REL = Path("implementation/freehand/test")

# The area roster, in index order.  Closed at any given moment — an id or a
# section naming an area that is not here fails — but the programme EP
# introduces the roster with "Areas include ...", so it is extended by a
# recorded ruling in the change that needs it, never by an author reaching for
# a token mid-allocation.  Keep in step with the table in
# spec/conformance/freehand/README.md.
AREAS: tuple[str, ...] = (
    "CALL",
    "PROPS",
    "EVENT",
    "INPUT",
    "SUB",
    "CTRL",
    "PRESENCE",
    "TOPLAYER",
    "BEHAVIOR",
    "REACT",
    "ERROR",
    "ROOT",
    # Routing link.  The programme's own parity tables treat the routing link
    # as its own surface; it is not an EVENT law wearing a different hat.
    "ROUTELINK",
    "STRUCT",
    "DIAG",
)

COLUMNS: tuple[str, ...] = (
    "Id",
    "Law",
    "Canonical paragraph",
    "Applicability",
    "Fixture",
    "Status",
)

STATUSES = frozenset({"planned", "active", "retired"})

# Applicability is two axes: exactly one mode token, one or more host tokens.
MODE_TOKENS = frozenset({"common", "interpreted", "compiled"})
HOST_TOKENS = frozenset({"jvm", "browser", "ssr"})
_QUALIFIED_HOST_RE = re.compile(r"^host:[a-z0-9]+(?:-[a-z0-9]+)*$")

# The em dash is the "no fixture" cell.  Nothing else stands in for it.
NO_FIXTURE = "—"

_ID_RE = re.compile(r"^FH-([A-Z]+)-(\d{3})$")
# `### FH-CALL — Calls`; the token is what binds, the prose name is for readers.
_SECTION_RE = re.compile(r"^###\s+FH-([A-Z]+)\b")
_FENCE_RE = re.compile(r"^\s*(```|~~~)")
_LINK_RE = re.compile(r"^\[[^\]]+\]\(([^)\s]+)\)$")
_SEPARATOR_CELL_RE = re.compile(r"^:?-{3,}:?$")

# --------------------------------------------------------------------------
# The execution census
# --------------------------------------------------------------------------
#
# A proof site is a call to the fixture-loading macro,
# `re-frame.freehand.conformance/fixture`, under any alias:
#
#     (def props-001 (conf/fixture :FH-PROPS-001))
#     [(conformance/fixture :FH-STRUCT-001) ...]
#
# Reading the SITE rather than a hand-kept manifest is the whole point: the
# manifest is DERIVED, so it cannot drift from the tests, and a second copy of
# the mapping — which is what a hand-kept manifest is — cannot go stale because
# it does not exist.
_PROOF_SITE_RE = re.compile(r"\(\s*(?:[A-Za-z0-9.*+!_'?<>=-]+/)?fixture\s+(:FH-[A-Z]+-\d{3})\b")

_CLJ_SUFFIXES = (".clj", ".cljc", ".cljs")

# Which lane runs a test file, derived from the three discovery rules in force —
# not asserted here, mirrored from where they are actually configured:
#
#   jvm      `clojure -M:test` in implementation/freehand.  cognitect-test-runner
#            discovers namespaces ending `-test` over the `:clj` platform, which
#            is `.clj` + `.cljc`.
#   node     `npm run test:freehand` — the :node-test-freehand build's ns-regexp
#            `^re-frame\.freehand\..+-cljs-test$`, which matches `-cljs-test` AND
#            `-dom-cljs-test`, over `.cljs` + `.cljc`.
#   browser  `npm run test:browser` — the :browser-test build's ns-regexp
#            `-dom-cljs-test$`, `.cljs` only.  The mounted tier: real DOM, real
#            listeners.
LANES: tuple[str, ...] = ("jvm", "node", "browser")

# Which lanes serve a host token, from the host/mode matrix in
# `spec/008-Testing.md` §The host/mode matrix.  Two readings there are load
# bearing and neither is this script's invention:
#
#   * The `browser` column's STRUCTURAL cell "is the host-neutral tree proven in
#     the node runtime; it needs no real DOM".  So the node lane serves
#     `browser`, and the Chromium lane serves the mounted cell of the same
#     column.
#   * The `ssr` column is "structural tree -> 011": the runner proves the tree —
#     and for Freehand the JVM render IS the server shell — while 011 owns
#     emission and hydration.  So the JVM lane serves `ssr`.
#   * A qualified host is opaque to the structural render and is "proven by
#     connecting the real behaviour or wrapper in a browser", so `host:<name>`
#     is served by the browser lane alone.
HOST_LANES: dict[str, frozenset[str]] = {
    "jvm": frozenset({"jvm"}),
    "browser": frozenset({"node", "browser"}),
    "ssr": frozenset({"jvm"}),
}
_QUALIFIED_HOST_LANES = frozenset({"browser"})


def _cells(line: str) -> list[str]:
    """Split a markdown table row into trimmed cells (outer pipes dropped)."""
    return [c.strip() for c in line.strip().strip("|").split("|")]


def _unquote(cell: str) -> str:
    """Strip the backticks an index cell wraps a literal in."""
    return cell.strip().strip("`").strip()


def _check_citation(
    cell: str,
    index_dir: Path,
    repo_root: Path,
) -> str | None:
    """Return None when the canonical-paragraph cell resolves, else the reason."""
    m = _LINK_RE.match(cell)
    if not m:
        return "not a markdown link - expected [text](../../00X-Doc.md#anchor)"
    dest = m.group(1)
    path_part, _, anchor = dest.partition("#")
    if not anchor:
        return f"`{dest}` has no #anchor - a row cites a paragraph, not a document"
    if not path_part.endswith(".md"):
        return f"`{dest}` does not point at a markdown file"

    target = (index_dir / path_part).resolve()
    try:
        rel = target.relative_to(repo_root.resolve())
    except ValueError:
        return f"`{dest}` resolves outside the repository"
    if rel.parts[0] != "spec":
        return (
            f"`{dest}` resolves to {rel.as_posix()} - a canonical paragraph "
            "lives under spec/, not in a design record or guide"
        )
    if not target.is_file():
        return f"`{dest}` - no such file ({rel.as_posix()})"
    if anchor not in _slug_index(target):
        return f"`{dest}` - {rel.as_posix()} has no anchor #{anchor}"
    return None


def _check_applicability(cell: str) -> str | None:
    """Return None when the applicability cell is well-formed, else the reason."""
    tokens = cell.split()
    if not tokens:
        return "empty - name one mode token and at least one host token"
    unknown = [
        t
        for t in tokens
        if t not in MODE_TOKENS
        and t not in HOST_TOKENS
        and not _QUALIFIED_HOST_RE.match(t)
    ]
    if unknown:
        return (
            f"unknown token(s) {', '.join(unknown)} - expected one of "
            "common/interpreted/compiled plus jvm/browser/ssr/host:<name>"
        )
    modes = [t for t in tokens if t in MODE_TOKENS]
    if len(modes) != 1:
        return (
            f"{len(modes)} mode token(s) ({', '.join(modes) or 'none'}) - "
            "exactly one of common/interpreted/compiled is required"
        )
    hosts = [t for t in tokens if t not in MODE_TOKENS]
    if not hosts:
        return "no host token - name at least one of jvm/browser/ssr/host:<name>"
    return None


def _check_fixture(cell: str, status: str, repo_root: Path) -> str | None:
    """Return None when the fixture cell agrees with the row's status."""
    raw = cell.strip()
    if status == "active":
        if raw == NO_FIXTURE or not raw:
            return "an `active` row must name the fixture that proves it"
        path = _unquote(raw)
        if not (repo_root / path).is_file():
            return f"`{path}` - no such file"
        return None
    if raw != NO_FIXTURE:
        return (
            f"a `{status}` row must leave the fixture cell as {NO_FIXTURE} "
            f"(found `{_unquote(raw)}`)"
        )
    return None


def check(repo_root: Path, verbose: bool = False) -> int:
    """Validate the Freehand conformance index.  Return the defect count."""
    index = repo_root / INDEX_REL
    if not index.is_file():
        sys.stderr.write(f"error: no Freehand conformance index at {index}\n")
        return 1
    index_dir = index.parent

    defects: list[str] = []

    def defect(line_no: int, kind: str, row: str, detail: str) -> None:
        defects.append(f"  {kind}: {INDEX_REL.as_posix()}:{line_no} [{row}] {detail}")

    seen_ids: dict[str, int] = {}
    sections_seen: list[str] = []
    section_area: str | None = None
    section_has_header = False
    last_ordinal: dict[str, int] = {}
    row_count = 0

    in_fence = False
    for line_no, raw in enumerate(
        index.read_text(encoding="utf-8").splitlines(), start=1
    ):
        if _FENCE_RE.match(raw):
            in_fence = not in_fence
            continue
        if in_fence:
            continue

        section = _SECTION_RE.match(raw)
        if section:
            section_area = section.group(1)
            section_has_header = False
            sections_seen.append(section_area)
            continue

        if not raw.lstrip().startswith("|"):
            continue

        cells = _cells(raw)

        # Separator rows carry no data.
        if cells and all(_SEPARATOR_CELL_RE.match(c) for c in cells):
            continue

        if section_area is None:
            defect(line_no, "ORPHAN ROW", cells[0] if cells else "",
                   "table row before the first area section")
            continue

        # The first table row in a section is its header, and it is pinned so a
        # later slice cannot quietly add, drop, or rename a column.
        if not section_has_header:
            section_has_header = True
            if tuple(cells) != COLUMNS:
                defect(
                    line_no,
                    "BAD TABLE HEADER",
                    f"FH-{section_area}",
                    f"expected | {' | '.join(COLUMNS)} |",
                )
            continue

        if len(cells) != len(COLUMNS):
            defect(
                line_no,
                "BAD ROW",
                cells[0] if cells else "",
                f"{len(cells)} column(s), expected {len(COLUMNS)}",
            )
            continue

        row_count += 1
        id_cell, law, citation, applicability, fixture, status = cells
        row_id = _unquote(id_cell)
        label = row_id or "<empty id>"

        m = _ID_RE.match(row_id)
        if not m:
            defect(
                line_no,
                "ILL-FORMED ID",
                label,
                "expected FH-<AREA>-<NNN> with a roster AREA and three digits",
            )
            continue
        area, ordinal_text = m.group(1), m.group(2)
        ordinal = int(ordinal_text)

        if area not in AREAS:
            defect(line_no, "ILL-FORMED ID", label,
                   f"`{area}` is not in the area roster ({', '.join(AREAS)})")
            continue

        duplicate = row_id in seen_ids
        if duplicate:
            defect(line_no, "DUPLICATE ID", label,
                   f"already allocated at line {seen_ids[row_id]}")
        else:
            seen_ids[row_id] = line_no

        if area != section_area:
            defect(line_no, "AREA MISMATCH", label,
                   f"filed under the FH-{section_area} section")
        elif not duplicate:
            # Ordering is only meaningful for a fresh id — a duplicate has
            # already been reported and would otherwise report twice.
            previous = last_ordinal.get(area)
            if previous is not None and ordinal <= previous:
                defect(line_no, "OUT-OF-ORDER ID", label,
                       f"follows {area}-{previous:03d}; ids ascend within an area")
            last_ordinal[area] = max(ordinal, previous or 0)

        if not law:
            defect(line_no, "BAD ROW", label, "empty Law cell")

        reason = _check_citation(citation, index_dir, repo_root)
        if reason:
            kind = "MISSING CITATION" if not _LINK_RE.match(citation) else "BROKEN CITATION"
            defect(line_no, kind, label, reason)

        reason = _check_applicability(applicability)
        if reason:
            defect(line_no, "BAD APPLICABILITY", label, reason)

        if status not in STATUSES:
            defect(line_no, "BAD STATUS", label,
                   f"`{status}` - expected one of {', '.join(sorted(STATUSES))}")
        else:
            reason = _check_fixture(fixture, status, repo_root)
            if reason:
                kind = "MISSING FIXTURE" if status == "active" else "MISPLACED FIXTURE"
                defect(line_no, kind, label, reason)

    for area in sections_seen:
        if area not in AREAS:
            defects.append(
                f"  UNKNOWN AREA SECTION: {INDEX_REL.as_posix()} has a "
                f"### FH-{area} section; the roster is closed "
                f"({', '.join(AREAS)})"
            )
    for area in sorted({a for a in sections_seen if sections_seen.count(a) > 1}):
        defects.append(
            f"  DUPLICATE AREA SECTION: {INDEX_REL.as_posix()} has more than one "
            f"### FH-{area} section; one section per area keeps allocation honest"
        )
    for area in AREAS:
        if area not in sections_seen:
            defects.append(
                f"  MISSING AREA SECTION: {INDEX_REL.as_posix()} has no "
                f"### FH-{area} section; every roster area carries one"
            )

    if defects:
        sys.stderr.write(
            f"\n{len(defects)} Freehand conformance-index defect(s) found:\n\n"
        )
        for line in defects:
            sys.stderr.write(line + "\n")
        sys.stderr.write(
            "\nFix: see spec/conformance/freehand/README.md - an FH id is a "
            "permanent address, so it must be well-formed, unique, filed under "
            "its own area, and resolve to a real spec paragraph and (when "
            "active) a real fixture.\n"
        )
    elif verbose:
        sys.stderr.write(
            f"Freehand conformance index OK: {row_count} row(s) across "
            f"{len(sections_seen)} area section(s).\n"
        )

    return len(defects)


# --------------------------------------------------------------------------
# The execution census
# --------------------------------------------------------------------------


def _active_rows(index: Path) -> list[tuple[int, str, str, str]]:
    """`(line_no, id, applicability, fixture_path)` for each `active` row.

    A second, deliberately forgiving pass over the index: the census answers a
    different question from `check`, and a row too malformed to parse here has
    already been reported there.  Reporting it twice, in two vocabularies,
    would bury the one defect that matters under its own echo.
    """
    rows: list[tuple[int, str, str, str]] = []
    in_fence = False
    for line_no, raw in enumerate(
        index.read_text(encoding="utf-8").splitlines(), start=1
    ):
        if _FENCE_RE.match(raw):
            in_fence = not in_fence
            continue
        if in_fence or not raw.lstrip().startswith("|"):
            continue
        cells = _cells(raw)
        if len(cells) != len(COLUMNS):
            continue
        row_id = _unquote(cells[0])
        if not _ID_RE.match(row_id) or cells[5] != "active":
            continue
        rows.append((line_no, row_id, cells[3], _unquote(cells[4])))
    return rows


def _lanes_for(path: Path) -> frozenset[str]:
    """The lanes that run the test file at `path` (see LANES)."""
    name = path.name
    if name.endswith("_test.clj"):
        return frozenset({"jvm"})
    if name.endswith("_test.cljc"):
        return frozenset({"jvm", "node"} if name.endswith("_cljs_test.cljc")
                         else {"jvm"})
    if name.endswith("_dom_cljs_test.cljs"):
        return frozenset({"node", "browser"})
    if name.endswith("_cljs_test.cljs"):
        return frozenset({"node"})
    return frozenset()


def _scan_proof_sites(repo_root: Path) -> dict[str, list[Path]]:
    """Map each `FH` id to the test files whose source reads its fixture."""
    sites: dict[str, list[Path]] = {}
    test_root = repo_root / TESTS_REL
    if not test_root.is_dir():
        return sites
    for path in sorted(test_root.rglob("*")):
        if path.suffix not in _CLJ_SUFFIXES or not _lanes_for(path):
            continue
        text = path.read_text(encoding="utf-8")
        for keyword in sorted(set(_PROOF_SITE_RE.findall(text))):
            sites.setdefault(keyword[1:], []).append(path)
    return sites


def _host_lanes(host: str) -> frozenset[str]:
    return HOST_LANES.get(host, _QUALIFIED_HOST_LANES)


def census(repo_root: Path, verbose: bool = False, report: bool = False) -> int:
    """Reconcile the index against the suites that run it.  Return the defect
    count.

    The manifest is DERIVED — from the `(conf/fixture :FH-…)` sites in
    `implementation/freehand/test/` and the lane each file runs in — so there is
    no second copy of the mapping to keep in step.  Three facts fall out, and
    each is a defect shape: a row nothing reads, a row read only from lanes that
    do not serve the hosts it claims, and a fixture or a proof site left behind
    by a row that no longer exists.
    """
    index = repo_root / INDEX_REL
    if not index.is_file():
        sys.stderr.write(f"error: no Freehand conformance index at {index}\n")
        return 1

    rows = _active_rows(index)
    sites = _scan_proof_sites(repo_root)
    defects: list[str] = []
    table: list[tuple[str, str, str, str, str]] = []

    for line_no, row_id, applicability, fixture in rows:
        tokens = applicability.split()
        mode = next((t for t in tokens if t in MODE_TOKENS), "?")
        hosts = [t for t in tokens if t not in MODE_TOKENS]
        proving = sites.get(row_id, [])
        lanes: set[str] = set()
        for path in proving:
            lanes |= _lanes_for(path)

        if not proving:
            defects.append(
                f"  UNPROVEN ROW: {INDEX_REL.as_posix()}:{line_no} [{row_id}] "
                f"no test under {TESTS_REL.as_posix()}/ reads `{fixture}` - an "
                "active row names the fixture that PROVES it, so a fixture "
                "nobody reads is a law nobody proves"
            )
        else:
            for host in hosts:
                serving = _host_lanes(host)
                if not (lanes & serving):
                    defects.append(
                        f"  UNEXECUTED HOST: {INDEX_REL.as_posix()}:{line_no} "
                        f"[{row_id}] claims host `{host}`, served by the "
                        f"{'/'.join(sorted(serving))} lane(s), but its fixture "
                        f"is read only from the {'/'.join(sorted(lanes))} "
                        "lane(s) - narrow the applicability cell or prove the "
                        "law where it claims to bind"
                    )
        table.append((
            row_id,
            mode,
            " ".join(hosts),
            ",".join(l for l in LANES if l in lanes) or "-",
            ", ".join(p.name for p in proving) or "-",
        ))

    active_ids = {row_id for _, row_id, _, _ in rows}
    for row_id in sorted(set(sites) - active_ids):
        where = ", ".join(
            p.relative_to(repo_root).as_posix() for p in sites[row_id]
        )
        defects.append(
            f"  DANGLING PROOF: {where} reads a fixture for [{row_id}], which "
            f"is not an `active` row in {INDEX_REL.as_posix()} - a suite "
            "outlived its law"
        )

    fixtures_dir = repo_root / FIXTURES_REL
    if fixtures_dir.is_dir():
        named = {fixture for _, _, _, fixture in rows}
        for path in sorted(fixtures_dir.glob("*.edn")):
            rel = path.relative_to(repo_root).as_posix()
            if rel not in named:
                defects.append(
                    f"  ORPHAN FIXTURE: {rel} [{path.stem.upper()}] is named by "
                    f"no `active` row in {INDEX_REL.as_posix()} - a deletion "
                    "that removes the row and leaves the bytes is how a re-used "
                    "id acquires a stale meaning"
                )

    if report:
        widths = [max(len(r[i]) for r in ([("Id", "Mode", "Hosts", "Lanes", "Proving tests")] + table))
                  for i in range(5)]
        header = ("Id", "Mode", "Hosts", "Lanes", "Proving tests")
        sys.stdout.write(
            "  ".join(h.ljust(w) for h, w in zip(header, widths)).rstrip() + "\n"
        )
        sys.stdout.write("  ".join("-" * w for w in widths) + "\n")
        for row in table:
            sys.stdout.write(
                "  ".join(c.ljust(w) for c, w in zip(row, widths)).rstrip() + "\n"
            )
        sys.stdout.write(
            f"\n{len(table)} active row(s); "
            f"{sum(1 for r in table if r[3] != '-')} with an executed "
            "applicable arm.\n"
        )

    if defects:
        sys.stderr.write(
            f"\n{len(defects)} Freehand conformance-census defect(s) found:\n\n"
        )
        for line in defects:
            sys.stderr.write(line + "\n")
        sys.stderr.write(
            "\nFix: an `active` row is a claim that a law is PROVEN on the "
            "modes and hosts its applicability cell names.  Either write the "
            "proof, or narrow the cell to what is proven - see "
            "spec/008-Testing.md #the-hostmode-matrix.\n"
        )
    elif verbose:
        sys.stderr.write(
            f"Freehand conformance census OK: {len(table)} active row(s), each "
            "read by a test in every lane its hosts name.\n"
        )

    return len(defects)


# --------------------------------------------------------------------------
# Self-tests — hermetic fixtures generated into a temp dir, mirroring
# scripts/check_ep_status_sync.py's style.  Each fixture is a mini-repo: an
# mkdocs.yml at the root (the repo-root guard), a spec/ doc with a real
# heading to cite, an existing fixture file, and one index.
#
# The clean case and the EMPTY case both pass; every other case injects one
# defect shape and must fail.  An index this file's production copy is empty
# would otherwise be validated by a guard that has never said no.
# --------------------------------------------------------------------------

_HEADER = "| " + " | ".join(COLUMNS) + " |\n|---|---|---|---|---|---|\n"

_GOOD_CITATION = "[004-Views.md#template-grammar](../../004-Views.md#template-grammar)"


def _row(
    row_id: str = "FH-CALL-001",
    law: str = "a declared view is a vector-called descriptor",
    citation: str = _GOOD_CITATION,
    applicability: str = "common jvm browser",
    fixture: str = "`spec/conformance/freehand/fixtures/fh-call-001.edn`",
    status: str = "active",
) -> str:
    return (
        f"| `{row_id}` | {law} | {citation} | {applicability} | "
        f"{fixture} | {status} |\n"
    )


def _write_fixture(root: Path, sections: dict[str, str]) -> None:
    """Write a mini-repo whose index carries `sections` (area -> row block)."""
    (root / "mkdocs.yml").write_text("site_name: fixture\n", encoding="utf-8")
    spec = root / "spec"
    (spec / "conformance" / "freehand" / "fixtures").mkdir(parents=True, exist_ok=True)
    (spec / "004-Views.md").write_text(
        "# Views\n\n## Template grammar\n\nx\n", encoding="utf-8"
    )
    (root / "docs").mkdir(exist_ok=True)
    (root / "docs" / "note.md").write_text("# Note\n\n## Template grammar\n\nx\n",
                                           encoding="utf-8")
    (spec / "conformance" / "freehand" / "fixtures" / "fh-call-001.edn").write_text(
        "{}\n", encoding="utf-8"
    )
    body = ["# Freehand Conformance Index\n"]
    for area in AREAS:
        body.append(f"\n### FH-{area} — {area.title()}\n\n")
        body.append(_HEADER)
        body.append(sections.get(area, ""))
    (root / INDEX_REL).write_text("".join(body), encoding="utf-8")


def _write_census_fixture(
    root: Path,
    sections: dict[str, str],
    tests: dict[str, str],
    extra_fixtures: tuple[str, ...] = (),
) -> None:
    """A census mini-repo: an index, plus a test tree that reads (or fails to
    read) its fixtures.  `tests` maps a filename under
    `implementation/freehand/test/re_frame/freehand/` to its source."""
    _write_fixture(root, sections)
    test_dir = root / TESTS_REL / "re_frame" / "freehand"
    test_dir.mkdir(parents=True, exist_ok=True)
    for name, source in tests.items():
        (test_dir / name).write_text(source, encoding="utf-8")
    for name in extra_fixtures:
        (root / FIXTURES_REL / name).write_text("{}\n", encoding="utf-8")


def _proof(row_id: str = "FH-CALL-001") -> str:
    return f"(ns x)\n(def fx (conf/fixture :{row_id}))\n"


def _build_census_fixtures(base: Path) -> None:
    """One mini-repo per census rule.  Each pins exactly one reading of the
    host/mode matrix, so a change to `HOST_LANES` or `_lanes_for` that is not
    also a change to Spec 008 fails here first."""
    cases: dict[str, tuple[dict[str, str], dict[str, str], tuple[str, ...]]] = {
        # Green: a `common jvm browser` row read from a `.cljc` suite, which
        # runs in the JVM lane AND the node lane.
        "census_clean": ({"CALL": _row()}, {"a_cljs_test.cljc": _proof()}, ()),
        # Green: `ssr` is served by the JVM lane — the JVM render IS the server
        # shell, and 011 owns emission (Spec 008 §The host/mode matrix).
        "census_ssr_served_by_jvm": (
            {"CALL": _row(applicability="common jvm ssr")},
            {"a_cljs_test.cljc": _proof()},
            (),
        ),
        # Green: a qualified host is proven by connecting the real wrapper in a
        # browser, so a mounted `-dom-cljs-test` suite serves it.
        "census_qualified_host": (
            {"CALL": _row(applicability="compiled host:ag-grid")},
            {"a_dom_cljs_test.cljs": _proof()},
            (),
        ),
        # Green: only an `active` row is a claim, so the `planned` row beside a
        # proven one needs no proof of its own.
        "census_planned_row_is_not_a_claim": (
            {"CALL": _row() + _row(row_id="FH-CALL-002",
                                   fixture=NO_FIXTURE, status="planned")},
            {"a_cljs_test.cljc": _proof()},
            (),
        ),
        # Red: the row names a real fixture nobody reads.
        "census_unproven_row": ({"CALL": _row()}, {}, ()),
        # Red: a `common jvm browser` row read only from a mounted `.cljs`
        # suite — node and browser, never the JVM it claims.
        "census_unexecuted_host": (
            {"CALL": _row()},
            {"a_dom_cljs_test.cljs": _proof()},
            (),
        ),
        # Red: a qualified host claimed by a suite that never enters a browser.
        "census_qualified_host_needs_a_browser": (
            {"CALL": _row(applicability="compiled host:ag-grid")},
            {"a_cljs_test.cljc": _proof()},
            (),
        ),
        # Red: a suite that outlived its law.
        "census_dangling_proof": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": _proof() + _proof("FH-CALL-002")},
            (),
        ),
        # Red: a deletion that took the row and left the bytes.
        "census_orphan_fixture": (
            {"CALL": _row()},
            {"a_cljs_test.cljc": _proof()},
            ("fh-call-002.edn",),
        ),
    }
    for name, (sections, tests, extra) in cases.items():
        root = base / name
        root.mkdir(parents=True, exist_ok=True)
        _write_census_fixture(root, sections, tests, extra)


def _build_self_test_fixtures(base: Path) -> None:
    cases: dict[str, dict[str, str]] = {
        # Both green cases: a real row, and the empty index this slice ships.
        "clean": {"CALL": _row()},
        "empty": {},
        "clean_planned": {"CALL": _row(fixture=NO_FIXTURE, status="planned")},
        "clean_qualified_host": {
            "CALL": _row(applicability="compiled host:ag-grid")
        },
        # One defect each.
        "duplicate_id": {"CALL": _row() + _row(law="a second row, same id")},
        "ill_formed_id": {"CALL": _row(row_id="FH-CALL-1")},
        "unknown_area_in_id": {"CALL": _row(row_id="FH-BANANA-001")},
        "area_mismatch": {"PROPS": _row()},
        "out_of_order_id": {
            "CALL": _row(row_id="FH-CALL-002") + _row(row_id="FH-CALL-001")
        },
        "citation_not_a_link": {"CALL": _row(citation="004-Views.md#template-grammar")},
        "citation_no_anchor": {"CALL": _row(citation="[v](../../004-Views.md)")},
        "citation_missing_file": {
            "CALL": _row(citation="[v](../../004Z-Gone.md#template-grammar)")
        },
        "citation_missing_anchor": {
            "CALL": _row(citation="[v](../../004-Views.md#no-such-anchor)")
        },
        "citation_outside_spec": {
            "CALL": _row(citation="[v](../../../docs/note.md#template-grammar)")
        },
        "fixture_missing": {
            "CALL": _row(fixture="`spec/conformance/freehand/fixtures/nope.edn`")
        },
        "fixture_absent_on_active": {"CALL": _row(fixture=NO_FIXTURE)},
        "fixture_on_planned": {"CALL": _row(status="planned")},
        "applicability_unknown_token": {"CALL": _row(applicability="common node")},
        "applicability_two_modes": {
            "CALL": _row(applicability="common compiled browser")
        },
        "applicability_no_host": {"CALL": _row(applicability="common")},
        "applicability_no_mode": {"CALL": _row(applicability="browser jvm")},
        "bad_status": {"CALL": _row(status="green")},
        "bad_row_shape": {"CALL": "| `FH-CALL-001` | law | too few |\n"},
        "empty_law": {"CALL": _row(law="")},
    }
    for name, sections in cases.items():
        root = base / name
        root.mkdir(parents=True, exist_ok=True)
        _write_fixture(root, sections)

    # Section-roster defects need surgery on the rendered index, not a row.
    dropped = base / "missing_area_section"
    dropped.mkdir(parents=True, exist_ok=True)
    _write_fixture(dropped, {})
    text = (dropped / INDEX_REL).read_text(encoding="utf-8")
    (dropped / INDEX_REL).write_text(
        text.replace(f"### FH-{AREAS[-1]} — {AREAS[-1].title()}\n\n" + _HEADER, ""),
        encoding="utf-8",
    )

    unknown = base / "unknown_area_section"
    unknown.mkdir(parents=True, exist_ok=True)
    _write_fixture(unknown, {})
    text = (unknown / INDEX_REL).read_text(encoding="utf-8")
    (unknown / INDEX_REL).write_text(
        text + "\n### FH-BANANA — Banana\n\n" + _HEADER, encoding="utf-8"
    )

    duplicate = base / "duplicate_area_section"
    duplicate.mkdir(parents=True, exist_ok=True)
    _write_fixture(duplicate, {})
    text = (duplicate / INDEX_REL).read_text(encoding="utf-8")
    (duplicate / INDEX_REL).write_text(
        text + f"\n### FH-{AREAS[0]} — Again\n\n" + _HEADER, encoding="utf-8"
    )

    header = base / "bad_table_header"
    header.mkdir(parents=True, exist_ok=True)
    _write_fixture(header, {})
    text = (header / INDEX_REL).read_text(encoding="utf-8")
    (header / INDEX_REL).write_text(
        text.replace("| Law |", "| Rule |", 1), encoding="utf-8"
    )

    orphan = base / "orphan_row"
    orphan.mkdir(parents=True, exist_ok=True)
    _write_fixture(orphan, {})
    text = (orphan / INDEX_REL).read_text(encoding="utf-8")
    (orphan / INDEX_REL).write_text(
        text.replace("# Freehand Conformance Index\n",
                     "# Freehand Conformance Index\n\n" + _HEADER + _row(), 1),
        encoding="utf-8",
    )


def _run_self_tests(verbose: bool = False) -> int:
    cases: list[tuple[str, int]] = [
        ("clean", 0),
        ("empty", 0),
        ("clean_planned", 0),
        ("clean_qualified_host", 0),
        ("duplicate_id", 1),
        ("ill_formed_id", 1),
        ("unknown_area_in_id", 1),
        ("area_mismatch", 1),
        ("out_of_order_id", 1),
        ("citation_not_a_link", 1),
        ("citation_no_anchor", 1),
        ("citation_missing_file", 1),
        ("citation_missing_anchor", 1),
        ("citation_outside_spec", 1),
        ("fixture_missing", 1),
        ("fixture_absent_on_active", 1),
        ("fixture_on_planned", 1),
        ("applicability_unknown_token", 1),
        ("applicability_two_modes", 1),
        ("applicability_no_host", 1),
        ("applicability_no_mode", 1),
        ("bad_status", 1),
        ("bad_row_shape", 1),
        ("empty_law", 1),
        ("missing_area_section", 1),
        ("unknown_area_section", 1),
        ("duplicate_area_section", 1),
        ("bad_table_header", 1),
        # A block before the first section orphans both its header and its row.
        ("orphan_row", 2),
    ]
    census_cases: list[tuple[str, int]] = [
        ("census_clean", 0),
        ("census_ssr_served_by_jvm", 0),
        ("census_qualified_host", 0),
        ("census_planned_row_is_not_a_claim", 0),
        ("census_unproven_row", 1),
        ("census_unexecuted_host", 1),
        ("census_qualified_host_needs_a_browser", 1),
        ("census_dangling_proof", 1),
        ("census_orphan_fixture", 1),
    ]
    failures = 0
    with tempfile.TemporaryDirectory(prefix="fh_index_selftest_") as tmp:
        base = Path(tmp)
        _build_self_test_fixtures(base)
        _build_census_fixtures(base)
        for guard, guard_cases in ((check, cases), (census, census_cases)):
            for fixture, expected in guard_cases:
                root = base / fixture
                saved_stderr = sys.stderr
                sys.stderr = _DevNull()
                try:
                    got = guard(root, verbose=False)
                finally:
                    sys.stderr = saved_stderr
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
        sys.stderr.write(
            f"all {len(cases) + len(census_cases)} self-tests passed.\n"
        )
    return 0


class _DevNull:
    def write(self, *_args, **_kwargs) -> int:
        return 0

    def flush(self) -> None:  # pragma: no cover
        return None


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Validate the Freehand conformance index (FH-<AREA>-<NNN>).",
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
    parser.add_argument(
        "--report",
        action="store_true",
        help=(
            "Print the per-row applicable-arm table to stdout: every active "
            "row, its mode and hosts, the lanes that prove it, and the tests "
            "that read its fixture."
        ),
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
    defects += census(repo_root, verbose=args.verbose, report=args.report)
    return 0 if defects == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
