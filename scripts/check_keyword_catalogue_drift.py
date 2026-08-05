#!/usr/bin/env python3
"""rf2-1nci83 — minimal keyword drift gate (structural self-enforcement of the
keyword contract). The api-manifest pattern (spec/api-manifest-metadata.edn +
its CI drift-check) applied to KEYWORDS.

WHY THIS GATE EXISTS
--------------------
The public-VAR surface is self-enforcing (the api-manifest job regenerates the
manifest from live vars and fails on drift). KEYWORDS — a larger, more
contract-critical surface — had no equivalent, which is why two catalogue
breaches shipped undetected (rf2-720yoj: five constructed `:rf.error/reply-*`
ids; rf2-xrk4w1: the `:rf.error/cookie-invalid-*` member set) — a constructor
minted `:rf.error/id`s that no Spec 009 catalogue row covered. This gate makes
that class of drift fail CI.

Per the bead's HARD SCOPE-TRIM (Mike posture 2026-07-09, no gold-plating) this
ships ONLY checks that provably catch a real bug. The dead-assertion guard and
retired-namespace guard are DEFERRED until a real miss motivates them; CHECK C
was added under rf2-d89rs because a real miss DID motivate it.

  CHECK A — Error/warning catalogue coverage.
    Every `:rf.error/<id>` and `:rf.warning/<id>` LITERAL emitted by the
    implementation source (masked of comments + strings, so only real code
    keywords count — including the literal member tables rf2-720yoj / rf2-xrk4w1
    added so their constructed ids are greppable at the SOURCE) MUST have a row
    in the Spec 009 error/warning catalogue (spec/009-Instrumentation.md). This
    is the check that would have caught rf2-720yoj + rf2-xrk4w1; per rf2-cs0kd1
    it now defends TOTALITY — there is no known-debt ledger, so EVERY emitted
    id must be catalogued (adapter- / test-harness-internal ids included).

  CHECK B — Reserved-namespace coverage.
    Every `:rf.*` keyword NAMESPACE emitted by the implementation source MUST be
    RESERVED by a `:rf.<ns>/*` glob DECLARATION in the spec/Conventions.md
    reserved-namespace table (the "single-root reserved set" — the authority).
    This is the check that guards the class rf2-0lb6xc closed (≈14 impl-emitted
    namespaces that lacked a Conventions row).

    A reservation is a `:rf.<ns>/*` GLOB in the **Sub-namespace (first) cell**
    of a non-retired row of that table — NOT an incidental mention anywhere in
    the row. rf2-qriq8: the earliest extractor took EVERY `:rf.*` token anywhere
    in the doc, so a specific-member spelling like `:rf.world/inputs` in prose
    or a link granted reservation to `:rf.world/*`. rf2-ox5we closed the rest of
    that gap: a glob in a row's Used-for / Spec cell is an *example*, a
    *cross-reference*, or a *negative statement* ("there is no `:rf.spec/*`
    trace namespace"; "the `:rf.timer/*` reservation is deferred") — reading
    reservations from a row BODY made the checker bless namespaces the table
    explicitly says do not exist. Reservation is now STRUCTURAL: a namespace is
    reserved iff it is glob-declared in its own row's first column. Retired
    accepted-input
    tombstones deliberately retained in source (the `:rf.world/inputs`
    did-you-mean spelling — Conventions §The tombstone rule) are classified as
    non-emitting: an accepted-input spelling, not a framework-emitted/reserved
    namespace, so they neither fire the check nor reserve `:rf.world/*`.

  CHECK C — The REVERSE direction: catalogue -> source (rf2-d89rs).
    CHECK A asks "does every emitted id have a row?". It never asks the
    converse, so a row whose emitter was DELETED stays green forever — and
    three did: `:rf.error/ui-dispatch-unwired`, `:rf.error/ui-test-bad-selector`
    and `:rf.error/ui-test-frame-collision` were documented in Spec 009 and
    named nowhere else in the repo. A documented error id with no emitter is a
    promise the framework cannot keep: a consumer (or an AI) reading the
    catalogue to write a handler for it writes dead code — the same class as a
    documented example that throws. So every ACTIVE `:rf.error/*` /
    `:rf.warning/*` catalogue ROW must have a literal emitter in implementation
    source.

    RETIRED rows are excluded STRUCTURALLY, by the catalogue's own
    retire-in-place convention — a strikethrough first cell
    (``| ~~`:rf.error/frame-reset-in-handler`~~ | … |``). Spec 009 states the
    category vocabulary is stable ("existing categories cannot be renamed or
    removed"), so a category whose emitter goes away is struck, not deleted,
    and a struck row is exactly the row that MUST NOT have an emitter. Same
    posture as CHECK B's `_row_is_retired`: status is read off the row, never
    from a list kept beside it. That is also what lets the check tell a
    DELIBERATELY RETIRED emitter from one that was NEVER WRITTEN without
    consulting git history: the corpus records the disposition on the row, and
    the one active row the CLJS reference is CONTRACTED never to emit
    (`:rf.error/machine-grammar-not-in-v1`, port-relative by its own Trigger
    cell) carries the single exemption below, kept honest both ways.

FAMILY SCOPE — the two families A and C read, and the one that is OUT
----------------------------------------------------------------------
CHECKS A and C are keyed on `:rf.error/*` and `:rf.warning/*`. That is a
DECISION, not an accident of the regex, and rf2-f9v2s is why it is written
down: a reader who finds an emitted `:rf.*` id this gate never mentions
deserves the boundary in the same file as the scan, rather than having to
infer it from a prefix.

Spec 009's catalogue is the RUNTIME error-EVENT vocabulary — the ids a
consumer observes on the trace surface or catches off a thrown ex-info — and
`:rf.error/*` / `:rf.warning/*` are the two families it rows at scale (445 of
its 485 active rows). The remaining 40 rows sit in 14 other `:rf.*` families
(`:rf.ssr/*`, `:rf.epoch/*`, `:rf.http/*`, `:rf.fx/*`, `:rf.route.nav-token/*`,
…); their id-level coverage belongs to the Clojure ratchet
(`implementation/core/test/re_frame/error_catalogue_channel_conformance_test
.clj` — Channel column, emit sites, Tags column), not here. CHECK B is the one
arm that IS total over `:rf.*`: every emitted namespace must be reserved,
whatever its family, so nothing escapes the gate entirely.

OUT OF SCOPE, deliberately — `:rf.ui.compile/*`. The Freehand compiler emits
~110 analyzer-refusal ids in that namespace (`:rf.ui.compile/bad-render-fn`,
`…/bad-tag`, `…/unsupported-form`, …) and not one has a Spec 009 row. That is
correct, and the reserved-namespace table already decided it: Conventions §The
single-root reserved set says the family is the "compile-error id namespace …
(compile-time only: thrown at macroexpansion, NEVER emitted at runtime, never
a trace)", gives its spec home as "004 (rewrite)", and names its roster
authority — "the id roster is pinned by the S1b analyzer reject tables". A
never-a-trace id cannot have an error-EVENT row, so widening this scan to the
family would not find drift; it would manufacture ~110 findings against a
contract nobody wrote. The family's own roster gate is real and in flight
elsewhere: `implementation/ui/test/re_frame/ui/error_roster_*`, dispositioned
**MOVE** in `spec/conformance/freehand/donor-inventory.md` (F1, pending) — it
CROSSES into Freehand rather than dying with the donor tree, and the ids
themselves already live on the Freehand side
(`implementation/freehand/src/re_frame/freehand/compiler/`), so the pending
`implementation/ui` deletion takes the donor copy only.

SCAN SURFACE (corpus-sweep rules — same as the sibling residue guards)
----------------------------------------------------------------------
Implementation source only: `.clj` / `.cljc` / `.cljs` under `implementation/`,
EXCLUDING `test/` trees (they carry negative-test vocabulary and assertions on
deliberately-absent ids) and the generated / vendor / build dirs
(`node_modules`, `target`, `out`, `.shadow-cljs`, `.cpcache`, `scripts` — the
api-manifest generator is build tooling, not framework runtime). The two Spec
sources it READS (`spec/009-Instrumentation.md`, `spec/Conventions.md`) are the
tracked source of truth, never a generated `docs/` mirror.

Comments (`;`→EOL) AND string/docstring bodies are MASKED before extraction, so
a keyword mentioned only in prose or a docstring is not mistaken for an emitted
literal (only a real code keyword form fires either check).

TOTALITY (rf2-cs0kd1) — no known-uncatalogued ledger
----------------------------------------------------
There is NO frozen debt ledger. rf2-cs0kd1 catalogued the last 33 ledgered
error/warning ids in Spec 009 (the adapter- / test-harness-internal tier plus
the machine / resource / routing / flow / core runtime ids) and DELETED the
`KNOWN_UNCATALOGUED` construct: the gate now defends TOTALITY — every emitted
id must have a 009 row, with no known-debt concept surviving in this script.

Exit code:
    0  no drift (every emitted id catalogued; every namespace reserved; every
       active catalogue row emitted)
    1  at least one drift finding (printed file-agnostic, id + which check)
    2  invocation / setup error
Python stdlib only.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path
from typing import Iterable

# --------------------------------------------------------------------------
# Scan surface
# --------------------------------------------------------------------------

_IMPL_DIR = "implementation"
_SOURCE_SUFFIXES = (".clj", ".cljc", ".cljs")

_EXCLUDE_DIR_NAMES = frozenset({
    "node_modules", "target", "out", ".shadow-cljs", ".git", ".beads",
    ".cpcache",
    # Build/governance tooling under implementation/scripts (the api-manifest
    # generator etc.) is not framework runtime — it emits no catalogued ids.
    "scripts",
})
_TEST_DIR_NAMES = frozenset({"test", "tests"})

_SPEC_009 = "spec/009-Instrumentation.md"
_SPEC_CONVENTIONS = "spec/Conventions.md"

# --------------------------------------------------------------------------
# Keyword grammar
# --------------------------------------------------------------------------
#
# Error/warning id: `:rf.error/<kebab>` / `:rf.warning/<kebab>` — a single
# kebab-case category (Conventions §Error-id and warning-id grammar). The strict
# kebab class (`[a-z0-9]+(?:-[a-z0-9]+)*`) excludes trailing punctuation and the
# `<placeholder>` angle-bracket forms that appear only in docstrings, so a
# prose mention like `:rf.error/reply-<category>` or a sentence-final
# `:rf.error/frame-destroyed.` never fires.
#
# The two families here ARE the scope of CHECKS A and C — see §Family scope in
# the module docstring for what that excludes and why (rf2-f9v2s). Adding a
# prefix to this alternation widens both checks at once; do not do it without
# first establishing that the new family is meant to have catalogue rows.
_ERR_WARN_RE = re.compile(r":rf\.(?:error|warning)/[a-z0-9]+(?:-[a-z0-9]+)*")

# Any reserved-root keyword; group 1 is its NAMESPACE (`rf`, `rf.error`,
# `rf.machine.event`, …). Matches the bare `:rf/x` root (namespace `rf`) and any
# dotted `:rf.<seg>…/x`.
_RF_KEYWORD_RE = re.compile(r":(rf(?:\.[a-z][\w.-]*)?)/[\w.*+!?<>=-]+")

# A reserved-namespace GLOB DECLARATION as it appears in the reserved-namespace
# table — a backticked `:rf.<ns>/*` token whose NAME is literally `*` (rf2-qriq8).
# group 1 is the reserved namespace. A specific-member spelling (`:rf.world/inputs`)
# is NOT a glob, so it never reserves — only a `/*` declaration does.
_RF_NS_GLOB_RE = re.compile(r"`:(rf(?:\.[a-z][\w.-]*)?)/\*`")

# The header row of the reserved-namespace table ("The single-root reserved
# set"). Reservations are read from THIS table only — never from surrounding
# prose or an unrelated table — so a `:rf.<ns>/*` glob outside it cannot reserve.
_RESERVED_TABLE_HEADER_RE = re.compile(
    r"^\|\s*Sub-namespace\s*\|\s*Used for\s*\|\s*Spec\s*\|\s*$"
)

# A RETIRED / non-reservation row's "Used for" cell OPENS with a bold retired
# marker (`**Retired** — …`, `**RETIRED (rf2-lxwpob).** Was …`). Anchored at the
# start of the cell on purpose: an ACTIVE row may mention a retired draft
# spelling mid-prose (the live `:rf.cofx/*` row does), and such a row must keep
# its reservation (rf2-5kzwf).
_BOLD_RETIRED_RE = re.compile(r"\*\*RETIRED\b", re.IGNORECASE)


# --------------------------------------------------------------------------
# Comment + string masking (length-preserving)
# --------------------------------------------------------------------------


def mask(text: str) -> str:
    """Blank `;`→EOL comments AND "…" string/docstring bodies, preserving
    length + newlines so only REAL code keyword forms survive for extraction.
    Backslash-escaped quotes inside a string do not close it."""
    out: list[str] = []
    i = 0
    n = len(text)
    in_string = False
    in_comment = False
    while i < n:
        c = text[i]
        if in_comment:
            if c == "\n":
                in_comment = False
                out.append("\n")
            else:
                out.append(" ")
            i += 1
            continue
        if in_string:
            if c == "\\" and i + 1 < n:
                out.append("  ")
                i += 2
                continue
            if c == '"':
                in_string = False
                out.append('"')
            elif c == "\n":
                out.append("\n")
            else:
                out.append(" ")
            i += 1
            continue
        if c == "\\" and i + 1 < n:
            # Clojure char literal (`\"`, `\;`, `\space`, …) — pass the
            # backslash + next char through verbatim so a `\"` / `\;` does NOT
            # spuriously open a string / comment. (Any trailing letters of a
            # named char like `\newline` are harmless normal chars.)
            out.append(text[i:i + 2])
            i += 2
            continue
        if c == ";":
            in_comment = True
            out.append(" ")
            i += 1
            continue
        if c == '"':
            in_string = True
            out.append('"')
            i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


# --------------------------------------------------------------------------
# Extraction
# --------------------------------------------------------------------------


def emitted_err_warn_ids(masked: str) -> set[str]:
    """The `:rf.error/*` / `:rf.warning/*` id literals in masked source text."""
    return set(_ERR_WARN_RE.findall(masked))


# Retired keyword LITERALS deliberately retained in implementation source as
# accepted-input / did-you-mean tombstones (Conventions §The tombstone rule) — a
# retired DRAFT spelling kept only so a stale supplied opt earns a targeted
# did-you-mean. These are NOT framework-emitted reserved namespaces, so CHECK B
# neither requires a reserved-namespace row for them nor lets them reserve one
# (rf2-qriq8). Exact-literal set — the member matters: a real `:rf.world/other`
# would still be an emitted namespace requiring a row.
_RETIRED_ACCEPTED_INPUTS = frozenset({":rf.world/inputs"})


def emitted_namespaces(masked: str) -> set[str]:
    """The `:rf.*` keyword namespaces in masked source text (e.g. `rf.error`),
    EXCLUDING the retired accepted-input tombstone literals (an accepted-input
    spelling is not a framework-emitted namespace — rf2-qriq8)."""
    return {
        m.group(1)
        for m in _RF_KEYWORD_RE.finditer(masked)
        if m.group(0) not in _RETIRED_ACCEPTED_INPUTS
    }


def catalogue_ids(spec_009_text: str) -> set[str]:
    """The set of `:rf.error/*` / `:rf.warning/*` ids the Spec 009 catalogue
    acknowledges (anywhere in the doc — a row is a superset of a prose mention;
    the whole doc is the catalogue's home). Exact-token set (NOT substring), so
    a longer id is never spuriously covered by a shorter one."""
    return set(_ERR_WARN_RE.findall(spec_009_text))


# --------------------------------------------------------------------------
# CHECK C — the catalogue's own ROWS (rf2-d89rs)
# --------------------------------------------------------------------------
#
# CHECK A reads ids from ANYWHERE in the doc, which is right for its direction:
# a prose mention is enough to show the id is acknowledged. CHECK C runs the
# other way and must be stricter — a row is a CLAIM that the runtime emits the
# category, and only a row makes it. So this parse is scoped to the `### Error
# event catalogue` section (the doc carries a second, differently-shaped
# quick-reference table; the Clojure ratchet learned the same lesson under
# rf2-i6p308) and anchored on the first cell.

_CATALOGUE_HEADING_RE = re.compile(r"^###\s+Error event catalogue\s*$")
_SECTION_HEADING_RE = re.compile(r"^#{1,3}\s+\S")

# A catalogue row's FIRST cell: an optionally struck-through, back-ticked
# error/warning id. `~~` marks the retire-in-place tombstone.
_CATALOGUE_ROW_RE = re.compile(
    r"^\|\s*(~~)?`(:rf\.(?:error|warning)/[a-z0-9]+(?:-[a-z0-9]+)*)`"
)


class CatalogueParseError(RuntimeError):
    """The canonical catalogue section could not be read as a table of rows.

    CHECK C's population IS the parse, so a parse that finds nothing is not a
    clean run — it is a check that did not run. rf2-66czz: `catalogue_rows`
    used to answer a renamed heading with two empty sets, `run_checks` read
    that as "no active row lacks an emitter", and the gate exited 0 having
    verified nothing. This is the ninth instance of the same shape in this
    repo, so the parser fails CLOSED: a gate that can fail to RUN must exit
    non-zero when it does not run."""


def catalogue_rows(spec_009_text: str) -> tuple[set[str], set[str]]:
    """`(active, retired)` — the `:rf.error/*` / `:rf.warning/*` ids that have
    a ROW in the Spec 009 §Error event catalogue, split by the strikethrough
    retire-in-place marker on the row's first cell.

    Raises `CatalogueParseError` when the heading is absent or the section
    yields ZERO active rows. Both are the same failure — the parser lost the
    table — and neither can be reported as a finding, because a finding is
    computed FROM the rows. The live corpus carries hundreds of active rows,
    so zero is never a legitimate reading; a genuinely emptied catalogue would
    be a contract event, not a routine edit. Retired-only is caught by the
    same rule: a section of nothing but tombstones has lost its live table."""
    lines = spec_009_text.splitlines()
    start = next(
        (i for i, ln in enumerate(lines) if _CATALOGUE_HEADING_RE.match(ln)), None
    )
    if start is None:
        raise CatalogueParseError(
            "no `### Error event catalogue` heading in spec/009-Instrumentation"
            ".md — CHECK C's row population cannot be read, so the check cannot"
            " run. Restore the heading, or update _CATALOGUE_HEADING_RE in this"
            " script IN THE SAME PR that renames it."
        )
    active: set[str] = set()
    retired: set[str] = set()
    for ln in lines[start + 1:]:
        if _SECTION_HEADING_RE.match(ln):
            break
        m = _CATALOGUE_ROW_RE.match(ln)
        if m:
            (retired if m.group(1) else active).add(m.group(2))
    if not active:
        raise CatalogueParseError(
            "the `### Error event catalogue` section yielded ZERO active rows "
            f"({len(retired)} retired) — the table shape no longer matches "
            "_CATALOGUE_ROW_RE (a column reorder, a fenced/blockquoted table, "
            "an indented first cell). CHECK C would report nothing and the gate"
            " would exit 0 having verified nothing, so this fails closed."
        )
    return active, retired


# The one ACTIVE catalogue row the CLJS reference implementation is CONTRACTED
# not to emit — a PORT-RELATIVE category, not drift. `:rf.error/machine-grammar
# -not-in-v1` is the unclaimed-capability rejection from the Spec 005 capability
# matrix: the row's own Trigger cell says "The v1 CLJS reference claims
# `:fsm/history` … and parallel regions …, so it NEVER RAISES THIS for them; a
# leaner port that omits a capability rejects the corresponding key here". The
# category is live vocabulary for other ports, so striking it would be a lie;
# it simply has no reference emitter and never will.
#
# Exact-literal set, mirroring `_RETIRED_ACCEPTED_INPUTS` above, and kept honest
# the same way the sibling ratchet keeps its allow-list honest: an entry that
# stops being an active row, or that acquires a reference emitter, is REPORTED
# (see `run_checks`) so it cannot rot into a silent suppression.
_PORT_RELATIVE_CATEGORIES = frozenset({":rf.error/machine-grammar-not-in-v1"})


def check_c_findings(
    active_rows: set[str],
    retired_rows: set[str],
    emitted: set[str],
    exempt: frozenset[str] = _PORT_RELATIVE_CATEGORIES,
) -> list[str]:
    """CHECK C's findings, PURE over its four inputs — so a self-test enters at
    the layer that HOLDS the logic instead of under it. (The original CHECK C
    self-tests did their set arithmetic inline, which is why leg 2 below could
    be missing without a single case noticing.) Four legs:

      1. an ACTIVE row nothing emits (rf2-d89rs — the original direction);
      2. a RETIRED row something DOES emit (rf2-66czz). A struck row is exactly
         the row that must not have an emitter, and neither sibling check can
         see the breach: `catalogue_ids` reads ids from ANYWHERE in the doc,
         struck rows included, so CHECK A reads a reintroduced emitter as
         catalogued; leg 1 subtracts `emitted` from the ACTIVE rows and
         discards the retired set entirely. Reintroducing the emitter behind a
         tombstone therefore passed A, B and C;
      3 + 4. the two ways the port-relative exemption goes stale — no longer an
         active row, or having acquired an emitter — so the exemption cannot
         rot into a silent suppression.
    """
    findings = sorted(active_rows - emitted - exempt)
    findings += [
        f"{kid} (RETIRED ROW REINTRODUCED: implementation source emits an id "
        "whose catalogue row is STRUCK THROUGH. A struck row promises the "
        "category is gone — un-strike the row if the emitter is deliberate, "
        "or delete the emitter if the retirement stands)"
        for kid in sorted(retired_rows & emitted)
    ]
    findings += [
        f"{kid} (STALE EXEMPTION: no longer an active catalogue row — drop it "
        "from _PORT_RELATIVE_CATEGORIES)"
        for kid in sorted(exempt - active_rows)
    ]
    findings += [
        f"{kid} (STALE EXEMPTION: implementation source now emits it — drop it "
        "from _PORT_RELATIVE_CATEGORIES)"
        for kid in sorted(exempt & emitted)
    ]
    return findings


def _reserved_ns_table_region(conventions_text: str) -> str:
    """The text of the reserved-namespace table ("The single-root reserved set")
    — from its header row through the last consecutive table row. Reservations
    are read from this region only, so a `:rf.<ns>/*` glob in unrelated prose or
    a different table cannot reserve a namespace (rf2-qriq8)."""
    lines = conventions_text.splitlines()
    start = next(
        (i for i, ln in enumerate(lines) if _RESERVED_TABLE_HEADER_RE.match(ln)),
        None,
    )
    if start is None:
        return ""
    region = [lines[start]]
    for ln in lines[start + 1:]:
        if ln.lstrip().startswith("|"):
            region.append(ln)
        else:
            break
    return "\n".join(region)


def _subnamespace_cell(row: str) -> str:
    """A reserved-namespace table row's **Sub-namespace (first) cell** — the one
    cell a reservation may be declared in (rf2-ox5we). A markdown row is
    `| <sub-namespace> | <used for> | <spec> |`, so splitting on `|` puts the
    first cell at index 1. Not a Markdown parser: one positional cell read on
    rows already confined to the reserved-namespace table region."""
    cells = row.split("|")
    return cells[1] if len(cells) > 1 else ""


def _row_is_retired(row: str) -> bool:
    """True when a reserved-namespace table row is a RETIRED / non-reservation
    tombstone, so none of its globs reserve anything (rf2-5kzwf). Two authoring
    markers, either sufficient: the Sub-namespace cell is STRUCK THROUGH
    (`~~`:rf.reload/*`~~`), or the "Used for" cell OPENS with a bold retired
    marker (`**Retired** — the EP-0013 realm … vocabulary`). Deliberately NOT a
    substring test for "retired" anywhere in the row — an active row may cite a
    retired draft spelling in its prose and must keep its reservation."""
    cells = row.split("|")
    used_for = cells[2] if len(cells) > 2 else ""
    return "~~" in _subnamespace_cell(row) or bool(
        _BOLD_RETIRED_RE.match(used_for.strip())
    )


def reserved_namespaces(conventions_text: str) -> set[str]:
    """The set of `:rf.*` namespaces Conventions RESERVES — the namespace of
    every `:rf.<ns>/*` glob DECLARATION in the **Sub-namespace (first) cell** of
    an ACTIVE reserved-namespace table row. Reservation is structural: a row's
    first column IS the declaration; everything after it is descriptive prose.

    Two false greens this closes. (1) rf2-qriq8: a specific-member spelling
    (`:rf.world/inputs`) in prose or a link never reserves — only a `/*` glob
    does. (2) rf2-ox5we: a `/*` glob in a row's Used-for / Spec cell no longer
    reserves either, because a row body cannot be trusted to mean "reserved" —
    it also carries examples, cross-references, and outright NEGATIVE statements
    (the `:rf.schema/*` row says there is no `:rf.spec/*` trace namespace; the
    `:rf.work/*` row says the `:rf.timer/*` reservation is deferred). Body
    extraction reserved both of those namespaces the table denies. A genuine
    framework-internal child sub-namespace earns its OWN row instead
    (`:rf.route.internal/*`, `:rf.interceptor.path/*`, …) — the same
    parent-then-child shape the table already uses for `:rf.machine.event/*`,
    `:rf.ssr.payload/*`, and `:rf.ui.compile/*`.

    RETIRED rows contribute nothing at all (rf2-5kzwf): a namespace the contract
    has withdrawn must not keep reserving itself, or reintroducing its
    vocabulary would stay a false green."""
    return {
        m.group(1)
        for row in _reserved_ns_table_region(conventions_text).splitlines()
        if not _row_is_retired(row)
        for m in _RF_NS_GLOB_RE.finditer(_subnamespace_cell(row))
    }


# --------------------------------------------------------------------------
# File iteration
# --------------------------------------------------------------------------


def iter_impl_src(impl_root: Path) -> Iterable[Path]:
    """Yield framework source files under `impl_root`.

    PRUNED, not filtered-after (rf2-76c76; method proven by rf2-e1xx0 in
    `check_retired_image_keys.py`). `_EXCLUDE_DIR_NAMES` is dropped from
    `os.walk`'s dirnames IN PLACE, so a built checkout never descends into
    `implementation/.shadow-cljs` (34.8k entries), `out` (9.7k) or
    `node_modules` (3.4k). `rglob("*")` enumerated all 51.6k entries under
    `implementation/` and discarded them one at a time: 4.2s of this gate's
    3.8s-4.0s wall clock on a built tree.

    The surviving sequence is IDENTICAL, set and order: pruning drops only
    what the `_EXCLUDE_DIR_NAMES` test below already dropped, and the
    collected matches go through ONE GLOBAL `sorted()`, reproducing
    `sorted(rglob("*"))`'s whole-subtree ordering rather than os.walk's
    directory-grouped order.
    """
    scan_prefix_len = len(impl_root.as_posix()) + 1
    matches: list[Path] = []
    for dirpath, dirnames, filenames in os.walk(impl_root):
        dirnames[:] = [d for d in dirnames if d not in _EXCLUDE_DIR_NAMES]
        for name in filenames:
            if os.path.splitext(name)[1] in _SOURCE_SUFFIXES:
                matches.append(Path(dirpath) / name)
    for path in sorted(matches):
        # Kept as the belt to the pruning's braces, and now on string ops:
        # `Path.relative_to` was pure pathlib object churn for a prefix strip.
        parts = set(path.as_posix()[scan_prefix_len:].split("/"))
        if parts & _EXCLUDE_DIR_NAMES:
            continue
        if parts & _TEST_DIR_NAMES:
            continue
        yield path


# --------------------------------------------------------------------------
# The two checks
# --------------------------------------------------------------------------


def run_checks(
    repo_root: Path,
) -> tuple[dict[str, list[str]], dict[str, list[str]], list[str]]:
    """Return (check_a_findings, check_b_findings, check_c_findings). A and B
    map an offending token to the sorted list of impl files that emit it; C is
    the sorted list of catalogue rows nothing emits."""
    spec_009 = (repo_root / _SPEC_009).read_text(encoding="utf-8")
    catalogue = catalogue_ids(spec_009)
    active_rows, retired_rows = catalogue_rows(spec_009)
    reserved = reserved_namespaces(
        (repo_root / _SPEC_CONVENTIONS).read_text(encoding="utf-8")
    )

    a_findings: dict[str, set[Path]] = {}
    b_findings: dict[str, set[Path]] = {}
    emitted: set[str] = set()
    for path in iter_impl_src(repo_root / _IMPL_DIR):
        masked = mask(path.read_text(encoding="utf-8", errors="replace"))
        ids = emitted_err_warn_ids(masked)
        emitted |= ids
        for kid in ids:
            if kid not in catalogue:
                a_findings.setdefault(kid, set()).add(path)
        for ns in emitted_namespaces(masked):
            if ns not in reserved:
                b_findings.setdefault(ns, set()).add(path)

    # CHECK C — all four legs, computed by the pure function above so the
    # self-tests exercise the same code path this does.
    c_findings = check_c_findings(active_rows, retired_rows, emitted)

    def rel(paths: set[Path]) -> list[str]:
        return sorted(
            str(p.relative_to(repo_root)).replace("\\", "/") for p in paths
        )

    return (
        {k: rel(v) for k, v in a_findings.items()},
        {k: rel(v) for k, v in b_findings.items()},
        c_findings,
    )


# --------------------------------------------------------------------------
# Reporting
# --------------------------------------------------------------------------

_FIX_A = (
    "Each id above is emitted by implementation source but has NO row in the\n"
    "  Spec 009 error/warning catalogue (spec/009-Instrumentation.md). This is\n"
    "  the drift rf2-720yoj / rf2-xrk4w1 shipped. Fix: add the catalogue row in\n"
    "  spec/009-Instrumentation.md (co-edit invariant — do it in the SAME PR as\n"
    "  the emitting change). Per rf2-cs0kd1 there is no known-debt ledger: EVERY\n"
    "  emitted id must be catalogued (adapter- / test-harness-internal ids too)."
)
_FIX_B = (
    "Each namespace above is emitted by implementation source but has NO\n"
    "  `:rf.<ns>/*` glob row in the spec/Conventions.md reserved-namespace table\n"
    "  (the 'single-root reserved set'). Add a reserved-namespace row (this is\n"
    "  the class rf2-0lb6xc closed) — the reserved-namespace scheme is the\n"
    "  collision protection + greppability anchor for framework-owned ids. The\n"
    "  glob must sit in the row's Sub-namespace (FIRST) cell: a mention in a\n"
    "  row's Used-for / Spec prose does not reserve (rf2-qriq8 / rf2-ox5we). A\n"
    "  framework-internal child sub-namespace gets its OWN row directly under\n"
    "  its parent's, not a sentence inside the parent's body."
)


_FIX_C = (
    "Each id above has an ACTIVE row in the Spec 009 error/warning catalogue but\n"
    "  is named by NO implementation source file — a documented diagnostic the\n"
    "  framework cannot produce. A consumer (or an AI) reading the catalogue to\n"
    "  handle it writes dead code. Two honest fixes, and no third:\n"
    "    * the emitter was RETIRED — strike the row in place, the way Spec 009\n"
    "      already retires categories (`| ~~`:rf.error/x`~~ | — | n/a (retired) |\n"
    "      **RETIRED.** <why> | — | — |`). The vocabulary is stable, so the row\n"
    "      is struck, never deleted, and the strikethrough is what excludes it\n"
    "      from this check.\n"
    "    * the emitter was NEVER WRITTEN — write it, or, if the reference\n"
    "      implementation is CONTRACTED not to emit the category (a port-relative\n"
    "      row whose own Trigger cell says so), add it to\n"
    "      `_PORT_RELATIVE_CATEGORIES` with that citation. A row nothing emits\n"
    "      and nothing plans to emit is drift either way (rf2-d89rs).\n"
    "  A RETIRED-ROW-REINTRODUCED line is the mirror image: the row is struck,\n"
    "  so the catalogue promises the category is GONE, and implementation source\n"
    "  emits it anyway. CHECK A cannot see that (it reads ids from anywhere in\n"
    "  the doc, tombstones included), so it is reported here — un-strike the row\n"
    "  if the emitter is deliberate, or delete the emitter (rf2-66czz)."
)


def report(a: dict[str, list[str]], b: dict[str, list[str]], c: list[str]) -> None:
    if a:
        sys.stderr.write(
            f"\nCHECK A — {len(a)} emitted :rf.error/:rf.warning id(s) with no "
            "Spec 009 catalogue row:\n\n"
        )
        for kid in sorted(a):
            sys.stderr.write(f"  {kid}\n      emitted by: {', '.join(a[kid])}\n")
        sys.stderr.write(f"\nFix:\n  {_FIX_A}\n")
    if b:
        sys.stderr.write(
            f"\nCHECK B — {len(b)} emitted :rf.* namespace(s) with no "
            "spec/Conventions.md reserved-namespace row:\n\n"
        )
        for ns in sorted(b):
            sys.stderr.write(f"  :{ns}/*\n      emitted by: {', '.join(b[ns])}\n")
        sys.stderr.write(f"\nFix:\n  {_FIX_B}\n")
    if c:
        sys.stderr.write(
            f"\nCHECK C — {len(c)} Spec 009 catalogue row(s) whose emitter state "
            "contradicts the row (active with none, struck with one):\n\n"
        )
        for kid in c:
            sys.stderr.write(f"  {kid}\n")
        sys.stderr.write(f"\nFix:\n  {_FIX_C}\n")


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "rf2-1nci83: fail on keyword-contract drift — an emitted "
            ":rf.error/:rf.warning id with no Spec 009 catalogue row, or an "
            "emitted :rf.* namespace with no Conventions reserved-namespace row."
        ),
    )
    parser.add_argument(
        "--repo-root",
        default=None,
        help="Repo root. Defaults to the script's grandparent.",
    )
    parser.add_argument(
        "--verbose", "-v", action="store_true", help="Print scan summary."
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Run in-memory teeth self-tests (prove each check fires) and exit.",
    )
    args = parser.parse_args(argv)

    if args.self_test:
        return _run_self_tests(verbose=args.verbose)

    repo_root = (
        Path(args.repo_root).resolve()
        if args.repo_root
        else Path(__file__).resolve().parent.parent
    )
    if not (repo_root / "mkdocs.yml").is_file():
        sys.stderr.write(
            f"error: {repo_root} is not the re-frame2 repo root (no mkdocs.yml).\n"
        )
        return 2
    for rel in (_SPEC_009, _SPEC_CONVENTIONS, _IMPL_DIR):
        if not (repo_root / rel).exists():
            sys.stderr.write(f"error: expected {rel} under {repo_root}.\n")
            return 2

    # A lost catalogue parse is a SETUP error, not a clean run (rf2-66czz).
    # Exit 2 keeps it distinguishable from a drift finding while still failing
    # CI, and it is raised BEFORE any check reports, so a gate that could not
    # read its own population never gets to print a verdict.
    try:
        a, b, c = run_checks(repo_root)
        active, retired = catalogue_rows(
            (repo_root / _SPEC_009).read_text(encoding="utf-8")
        )
    except CatalogueParseError as exc:
        sys.stderr.write(f"error: {exc}\n")
        return 2
    if args.verbose:
        n = sum(1 for _ in iter_impl_src(repo_root / _IMPL_DIR))
        sys.stderr.write(
            f"scanned {n} implementation source file(s); "
            f"{len(active)} active + {len(retired)} retired catalogue row(s); "
            f"check A findings={len(a)}, check B findings={len(b)}, "
            f"check C findings={len(c)}.\n"
        )
    if a or b or c:
        report(a, b, c)
        return 1
    if args.verbose:
        sys.stderr.write("keyword contract clean: no catalogue/namespace drift.\n")
    return 0


# --------------------------------------------------------------------------
# Self-tests — prove BOTH checks fire on an injected violation (teeth), and
# stay green on the conformant counterparts. In-memory (no fixture files).
# --------------------------------------------------------------------------


def _run_self_tests(verbose: bool = False) -> int:
    failures = 0

    def expect(name: str, cond: bool) -> None:
        nonlocal failures
        if cond:
            if verbose:
                sys.stderr.write(f"self-test PASS: {name}\n")
        else:
            sys.stderr.write(f"self-test FAIL: {name}\n")
            failures += 1

    synthetic_009 = (
        "| `:rf.error/handler-exception` | ... |\n"
        "| `:rf.warning/plain-fn` | ... |\n"
    )
    synthetic_conventions = (
        "### The single-root reserved set\n"
        "\n"
        "| Sub-namespace | Used for | Spec |\n"
        "|---|---|---|\n"
        "| `:rf/*` | Pattern-level events | 002 |\n"
        "| `:rf.error/*` | Error trace ops | 009 |\n"
        "| `:rf.machine/*` | Machine lifecycle | 005 |\n"
        "| `:rf.ui/*` | UI-domino namespace | 009 |\n"
        "| `:rf.ui.tool/*` | UI-tool inspector namespace | 009 |\n"
        # Framework-internal child sub-namespaces: each has its OWN first-column
        # row (rf2-ox5we), while its parent's body still CROSS-REFERENCES it.
        # Both halves matter — the row is what reserves, the body mention is
        # inert prose that must not.
        "| `:rf.route/*` | Routing; internal sub-ns `:rf.route.internal/*` | 012 |\n"
        "| `:rf.route.internal/*` | Internal routing events | 012 |\n"
        "| `:rf.mutation/*` | Mutations; internal `:rf.mutation.internal/*` | 016 |\n"
        "| `:rf.mutation.internal/*` | Internal mutation replies | 016 |\n"
        "| `:rf.resource/*` | Resources; internal `:rf.resource.internal/*` | 016 |\n"
        "| `:rf.resource.internal/*` | Internal resource replies | 016 |\n"
        "| `:rf.interceptor/*` | Interceptors; `:rf.interceptor.path/*` | 002 |\n"
        "| `:rf.interceptor.path/*` | Path-interceptor internals | 002 |\n"
        # An ACTIVE row whose body carries a glob in a PROSE EXAMPLE — the
        # rf2-ox5we repro shape. It must NOT reserve `rf.auditfake`.
        "| `:rf.flow/*` | Flows. A prose example mentions `:rf.auditfake/*`. | 002 |\n"
        # An ACTIVE row whose body DENIES a namespace. Body extraction used to
        # reserve the very namespaces the table says do not exist — the live
        # `:rf.schema/*` row ("there is no `:rf.spec/*` trace namespace") and
        # `:rf.work/*` row ("the `:rf.timer/*` reservation is deferred").
        "| `:rf.schema/*` | Schemas. There is no `:rf.spec/*` trace namespace; "
        "the `:rf.timer/*` reservation is deferred. | 010 |\n"
        # An ACTIVE row citing a retired DRAFT spelling mid-prose — it keeps its
        # reservation (the live `:rf.cofx/*` row has exactly this shape).
        "| `:rf.cofx/*` | Coeffects. The retired draft opt is gone. | 002 |\n"
        # RETIRED row, shape 1 (live `:rf.realm/*` row): plain first cell, the
        # "Used for" cell opens with the bold marker. Multi-glob first cell.
        "| `:rf.realm/*`, `:rf.module/*`, `:rf.app/*` | **Retired** — the "
        "EP-0013 realm / app-value / module vocabulary. | EP-0024 |\n"
        # RETIRED row, shape 2 (live `:rf.reload/*` row): struck-through first
        # cell AND a bold marker; its body names a member `:rf.reload/diff`.
        "| ~~`:rf.reload/*`~~ | **RETIRED (rf2-lxwpob).** Was the hot-reload "
        "report namespace (`:rf.reload/diff`). | EP-0023 |\n"
        "\n"
        "Prose AFTER the table: the retired draft opt `:rf.world/inputs`, a\n"
        "made-up `:rf.prosefake/member` mention, and even a stray glob\n"
        "`:rf.prosefake/*` — outside the table, none of them reserves.\n"
    )
    catalogue = catalogue_ids(synthetic_009)
    reserved = reserved_namespaces(synthetic_conventions)

    # CHECK A teeth ---------------------------------------------------------
    good_src = mask('(throw-error! :rf.error/handler-exception)')
    bad_src = mask('(throw-error! :rf.error/totally-uncatalogued)')
    a_good = {k for k in emitted_err_warn_ids(good_src) if k not in catalogue}
    a_bad = {k for k in emitted_err_warn_ids(bad_src) if k not in catalogue}
    expect("A: catalogued id passes", a_good == set())
    expect("A: uncatalogued id FIRES", a_bad == {":rf.error/totally-uncatalogued"})

    # A: an id present only in a docstring/comment must NOT fire (masking).
    prose_src = mask('"doc mentions :rf.error/totally-uncatalogued"\n'
                     ';; and :rf.error/also-uncatalogued in a comment')
    expect("A: prose/docstring mention does NOT fire",
           emitted_err_warn_ids(prose_src) == set())

    # CHECK B teeth ---------------------------------------------------------
    def b_fire(src: str, reserved_set: set[str]) -> set[str]:
        """The namespaces an emitted source fires CHECK B on against reserved_set."""
        return {ns for ns in emitted_namespaces(mask(src)) if ns not in reserved_set}

    # A namespace glob-declared in its own row's FIRST COLUMN passes.
    expect("B: first-column glob-declared namespaces pass",
           b_fire('(trace :rf.machine/transition [:rf/x] '
                  ':rf.route.internal/settle-transition)', reserved) == set())
    expect("B: unreserved namespace FIRES",
           b_fire('(trace :rf.zzznew/frobnicate 1)', reserved) == {"rf.zzznew"})

    # (1) a mention OUTSIDE the reserved-namespace table never reserves — not a
    #     member spelling, not even a glob (the rf2-qriq8 gap: an ordinary
    #     mention used to grant reservation).
    expect("B: prose outside the table does NOT reserve (rf.prosefake)",
           "rf.prosefake" not in reserved)
    expect("B: emitting an only-prose-mentioned namespace FIRES",
           b_fire('(x :rf.prosefake/member)', reserved) == {"rf.prosefake"})

    # (2) row deletion turns the check red: dropping the `:rf.ui.tool/*` row
    #     un-reserves it (its glob lives only in that row).
    reserved_no_ui_tool = reserved_namespaces(
        synthetic_conventions.replace(
            "| `:rf.ui.tool/*` | UI-tool inspector namespace | 009 |\n", ""
        )
    )
    expect("B: with its row, :rf.ui.tool/* passes",
           b_fire('(x :rf.ui.tool/open)', reserved) == set())
    expect("B: deleting the :rf.ui.tool/* row makes it FIRE",
           b_fire('(x :rf.ui.tool/open)', reserved_no_ui_tool) == {"rf.ui.tool"})

    # (3) exact-namespace matching — a parent `:rf.ui/*` glob does NOT reserve
    #     the distinct child namespace `rf.ui.tool` (no prefix descent).
    expect("B: parent :rf.ui/* does not reserve child rf.ui.tool",
           "rf.ui" in reserved_no_ui_tool and "rf.ui.tool" not in reserved_no_ui_tool)

    # (4) tombstone classification — the retired `:rf.world/inputs` did-you-mean
    #     spelling is an accepted input, not a framework namespace: emitting it
    #     does NOT fire, while a real `:rf.world/other` member DOES.
    expect("B: retired :rf.world/inputs tombstone does NOT fire",
           b_fire('{:rf.world/inputs "did you mean :rf.cofx?"}', reserved) == set())
    expect("B: a non-tombstone :rf.world/* member DOES fire",
           b_fire('(x :rf.world/other)', reserved) == {"rf.world"})

    # (5) RETIRED rows grant NO reservation (rf2-5kzwf) — reintroducing the
    #     withdrawn vocabulary must turn CHECK B red, in BOTH row shapes: the
    #     bold-marker row (`:rf.realm/*`, `:rf.module/*`, `:rf.app/*`) and the
    #     struck-through row (`:rf.reload/*`). Before the fix every one of these
    #     passed, because the row's globs were extracted like any other.
    expect("B: bold-marked retired row does NOT reserve",
           {"rf.realm", "rf.module", "rf.app"}.isdisjoint(reserved))
    expect("B: struck-through retired row does NOT reserve",
           "rf.reload" not in reserved)
    expect("B: emitting retired-row vocabulary FIRES",
           b_fire('(x :rf.realm/install :rf.module/def :rf.app/boot '
                  ':rf.reload/diff)', reserved)
           == {"rf.realm", "rf.module", "rf.app", "rf.reload"})

    # (6) The retired-row predicate must not over-reach. An ACTIVE row that
    #     cites a retired draft spelling mid-prose keeps its reservation (a
    #     substring test for "retired" would silently un-reserve `:rf.cofx/*`).
    expect("B: active row citing 'retired' mid-prose STILL reserves",
           b_fire('(x :rf.cofx/declared)', reserved) == set())

    # (7) Regression guard for the four framework-internal sub-namespaces. They
    #     are legitimate ACTIVE reservations carrying real implementation
    #     vocabulary, and each now holds its OWN first-column row (rf2-ox5we
    #     moved them out of their parents' row bodies). Neither the retired-row
    #     skip nor the first-column rule may drop any of them.
    expect("B: all four internal sub-namespaces still reserve (own rows)",
           b_fire('(x :rf.route.internal/settle :rf.mutation.internal/apply '
                  ':rf.resource.internal/evict :rf.interceptor.path/get)',
                  reserved) == set())

    # (8) STRUCTURAL FIRST-COLUMN RULE (rf2-ox5we) — the remaining half of the
    #     rf2-qriq8 gap. A `:rf.<ns>/*` glob in an ACTIVE row's BODY is prose:
    #     an example, a cross-reference, or a negative statement. It must not
    #     reserve, and emitting a member of it must turn CHECK B red.
    #
    #     (8a) the bead's repro: a prose example inside the `:rf.flow/*` row.
    expect("B: a glob in an active row's BODY does NOT reserve (rf.auditfake)",
           "rf.auditfake" not in reserved)
    expect("B: emitting a body-glob-only namespace FIRES",
           b_fire('(x :rf.auditfake/member)', reserved) == {"rf.auditfake"})

    #     (8b) the live-table instances: rows that DENY a namespace used to
    #          reserve it. Body extraction blessed `:rf.spec/*` (which the
    #          `:rf.schema/*` row says does not exist) and `:rf.timer/*` (which
    #          the `:rf.work/*` row says is deferred) — a namespace could ship
    #          in code on the strength of the sentence denying it.
    expect("B: a namespace a row body DENIES does not reserve",
           {"rf.spec", "rf.timer"}.isdisjoint(reserved))
    expect("B: emitting a denied namespace FIRES",
           b_fire('(x :rf.spec/trace :rf.timer/after)', reserved)
           == {"rf.spec", "rf.timer"})

    #     (8c) the parent row that CROSS-REFERENCES its child keeps its own
    #          first-column reservation — the first-column rule must not
    #          over-reach and un-reserve the parent along with the body glob.
    expect("B: a parent row citing its child in prose STILL reserves",
           b_fire('(x :rf.route/navigate :rf.interceptor/path)', reserved)
           == set())

    # CHECK C teeth (rf2-d89rs) --------------------------------------------
    #
    # The reverse direction. The synthetic catalogue below reproduces the three
    # shapes the live doc carries: an ACTIVE row with an emitter, an ACTIVE row
    # with none (the defect), and a STRUCK row with none (correct by
    # construction). The `~~` rows are byte-for-byte the live retire-in-place
    # shape.
    synthetic_009_rows = (
        "## Preamble\n"
        "\n"
        "Prose naming `:rf.error/prose-only-mention` — NOT a row, so CHECK C\n"
        "must not demand an emitter for it.\n"
        "\n"
        "### Error event catalogue\n"
        "\n"
        "| `:operation` | `:op-type` | Channel | Trigger | Default `:recovery` | `:tags` |\n"
        "|---|---|---|---|---|---|\n"
        "| `:rf.error/handler-exception` | `:error` | always-on | … | … | … |\n"
        "| `:rf.warning/plain-fn` | `:warning` | diagnostic | … | … | … |\n"
        "| `:rf.error/emitter-was-deleted` | `:error` | diagnostic | … | … | … |\n"
        "| ~~`:rf.error/properly-retired`~~ | — | n/a (retired) | **RETIRED.** … | — | — |\n"
        "\n"
        "### Schemas\n"
        "\n"
        "| `:rf.error/row-in-a-later-table` | `:error` | diagnostic | … | … | … |\n"
    )
    active_rows, retired_rows = catalogue_rows(synthetic_009_rows)

    expect("C: parses the active rows",
           active_rows == {":rf.error/handler-exception",
                           ":rf.warning/plain-fn",
                           ":rf.error/emitter-was-deleted"})
    expect("C: strikethrough row is RETIRED, not active",
           retired_rows == {":rf.error/properly-retired"})
    # Scope discipline, both directions: a prose mention before the table is
    # not a row, and a row-shaped line in a LATER section is not this table's.
    expect("C: prose mention is not a row",
           ":rf.error/prose-only-mention" not in active_rows)
    expect("C: a row after the next `###` heading is out of scope",
           ":rf.error/row-in-a-later-table" not in active_rows)

    def c_fire(src: str,
               exempt: frozenset[str] = frozenset(),
               active: set[str] | None = None,
               retired: set[str] | None = None) -> list[str]:
        """CHECK C's findings for an emitting source — entered at
        `check_c_findings`, the same function `run_checks` calls, NOT at the
        set arithmetic beneath it. That entry point is the point: these cases
        used to compute `active_rows - emitted` inline, so they could not have
        noticed that the RETIRED set was never consulted (rf2-66czz). A case
        that enters below the defect cannot see the defect."""
        return check_c_findings(
            active_rows if active is None else active,
            retired_rows if retired is None else retired,
            emitted_err_warn_ids(mask(src)),
            exempt)

    live_src = ('(emit-error! :rf.error/handler-exception {})'
                '(emit! :warning :rf.warning/plain-fn {})')
    expect("C: a row whose emitter exists passes",
           c_fire(live_src + '(x :rf.error/emitter-was-deleted)') == [])
    expect("C: a row with NO emitter FIRES (the rf2-d89rs defect)",
           c_fire(live_src) == [":rf.error/emitter-was-deleted"])
    # Striking the row is the documented fix, so it must actually work: after
    # the retire-in-place edit the same source greens, with no other change.
    struck_active, struck_retired = catalogue_rows(
        synthetic_009_rows.replace(
            "| `:rf.error/emitter-was-deleted` | `:error` | diagnostic | … | … | … |",
            "| ~~`:rf.error/emitter-was-deleted`~~ | — | n/a (retired) | "
            "**RETIRED.** … | — | — |"))
    expect("C: striking the row silences it — the retire-in-place fix",
           c_fire(live_src, active=struck_active, retired=struck_retired) == []
           and ":rf.error/emitter-was-deleted" in struck_retired)
    # The exemption is precision, not a licence: it silences exactly its own
    # entry and nothing else.
    expect("C: the port-relative exemption silences only its own entry",
           c_fire(live_src, frozenset({":rf.error/emitter-was-deleted"})) == [])
    # …and a mention in a COMMENT / DOCSTRING is not an emitter, so it must not
    # green a row (the masking that protects CHECK A protects CHECK C too).
    expect("C: a commented-out emitter does NOT green the row",
           c_fire(live_src + ';; (x :rf.error/emitter-was-deleted)')
           == [":rf.error/emitter-was-deleted"])

    # CHECK C leg 2 — RETIRED-ROW REINTRODUCTION (rf2-66czz) -----------------
    #
    # The bead's own pure probe first, verbatim: no active rows, one RETIRED
    # row, and a source that emits it. Before the fix CHECK A reported nothing
    # (the id is in the doc, so `catalogue_ids` covers it) and CHECK C reported
    # nothing (it subtracted `emitted` from the ACTIVE rows and threw the
    # retired set away) — while `retired & emitted` held the defect all along.
    reintro = ":rf.error/retired-but-reintroduced"
    probe = check_c_findings(set(), {reintro}, {reintro}, frozenset())
    expect("C: the bead's pure probe — retired row + live emitter FIRES",
           len(probe) == 1 and probe[0].startswith(reintro)
           and "RETIRED ROW REINTRODUCED" in probe[0])
    expect("C: a retired row with NO emitter stays silent (the normal case)",
           check_c_findings(set(), {reintro}, set(), frozenset()) == [])

    # …and through the real parser, on the real row shape. `:rf.error/properly
    # -retired` is struck in `synthetic_009_rows`; give it an emitter.
    reintroduced_src = live_src + ('(x :rf.error/emitter-was-deleted)'
                                   '(throw-error! :rf.error/properly-retired {})')
    expect("C: a struck row whose emitter reappears FIRES",
           [f for f in c_fire(reintroduced_src)
            if f.startswith(":rf.error/properly-retired")])
    # CHECK A must not mask it: the struck row puts the id in the doc, so
    # `catalogue_ids` covers it and CHECK A is silent BY DESIGN. Leg 2 is
    # therefore the only arm that can see this breach — which is why it has to
    # exist rather than being folded into A.
    expect("C: CHECK A stays silent on the reintroduced id (so C must not)",
           ":rf.error/properly-retired" in catalogue_ids(synthetic_009_rows))
    expect("C: leg 1 alone would still miss it",
           active_rows - emitted_err_warn_ids(mask(reintroduced_src)) == set())

    # CHECK C — the parse FAILS CLOSED (rf2-66czz) ---------------------------
    #
    # `catalogue_rows` used to answer an unreadable section with two empty
    # sets, which `run_checks` consumed as "nothing to report". A check whose
    # population can silently collapse to zero is a check that can fail to RUN
    # while exiting 0. Focused controls: valid / renamed / malformed /
    # retired-only, so the rule is proven to fire on the three break shapes
    # WITHOUT firing on the shape it must accept.
    def parse_raises(text: str) -> bool:
        try:
            catalogue_rows(text)
            return False
        except CatalogueParseError:
            return True

    expect("C: the valid catalogue section parses (the control)",
           not parse_raises(synthetic_009_rows))
    renamed = synthetic_009_rows.replace(
        "### Error event catalogue", "### Error and warning event catalogue")
    expect("C: a RENAMED heading fails closed", parse_raises(renamed))
    # The bead's own renamed-heading probe: CHECK A keeps reading ids from the
    # whole document, so a renamed heading looks perfectly clean to it. Only
    # the fail-closed parse turns the collapse into a non-zero exit.
    expect("C: …while CHECK A still sees the id, so A cannot cover the collapse",
           ":rf.error/emitter-was-deleted" in catalogue_ids(renamed))
    expect("C: a MALFORMED table (an extra leading cell) fails closed",
           parse_raises(synthetic_009_rows.replace("| `:rf.", "| x | `:rf.")))
    expect("C: a section of nothing but tombstones fails closed",
           parse_raises("### Error event catalogue\n"
                        "\n"
                        "| `:operation` | `:op-type` | Channel |\n"
                        "|---|---|---|\n"
                        "| ~~`:rf.error/properly-retired`~~ | — | n/a (retired) |\n"))
    # …and the rule does not over-reach: an active-only section (no tombstones
    # at all) is legitimate and must parse.
    expect("C: an active-only section is legitimate and parses",
           catalogue_rows("### Error event catalogue\n"
                          "\n"
                          "| `:rf.error/only-row` | `:error` | diagnostic |\n")
           == ({":rf.error/only-row"}, set()))

    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    if verbose:
        sys.stderr.write("all self-tests passed.\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
