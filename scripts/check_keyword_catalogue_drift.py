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
ships EXACTLY the two checks that provably catch a real bug. The dead-assertion
guard and retired-namespace guard are DEFERRED until a real miss motivates them.

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

    A reservation is a `:rf.<ns>/*` GLOB row of that table — NOT an incidental
    member mention. rf2-qriq8: the earlier extractor took EVERY `:rf.*` token
    anywhere in the doc, so a specific-member spelling like `:rf.world/inputs`
    in prose, a link, or a row body granted reservation to `:rf.world/*` — a
    namespace could then exist in code with no reserved-namespace row and the
    gate stayed green. Now only a `/*` glob within the reserved-namespace table
    reserves; a specific-member spelling never does. Retired accepted-input
    tombstones deliberately retained in source (the `:rf.world/inputs`
    did-you-mean spelling — Conventions §The tombstone rule) are classified as
    non-emitting: an accepted-input spelling, not a framework-emitted/reserved
    namespace, so they neither fire the check nor reserve `:rf.world/*`.

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
    0  no drift (every emitted id catalogued/known; every namespace reserved)
    1  at least one drift finding (printed file-agnostic, id + which check)
    2  invocation / setup error
Python stdlib only.
"""

from __future__ import annotations

import argparse
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


def reserved_namespaces(conventions_text: str) -> set[str]:
    """The set of `:rf.*` namespaces Conventions RESERVES — the namespace of
    every `:rf.<ns>/*` glob DECLARATION in the reserved-namespace table. A glob
    row is the reservation form; a specific-member spelling (`:rf.world/inputs`)
    in prose, a link, or a row body is NOT a reservation (rf2-qriq8 — that false
    green is exactly the gap this check now closes). The glob may sit in a row's
    first column or, for a framework-internal sub-namespace declared under its
    parent (e.g. `:rf.route.internal/*` in the `:rf.route/*` row), that row's
    body — a `/*` glob is an explicit reservation wherever it sits IN the table,
    whereas a member reference never reserves."""
    return {
        m.group(1)
        for m in _RF_NS_GLOB_RE.finditer(_reserved_ns_table_region(conventions_text))
    }


# --------------------------------------------------------------------------
# File iteration
# --------------------------------------------------------------------------


def iter_impl_src(impl_root: Path) -> Iterable[Path]:
    for path in sorted(impl_root.rglob("*")):
        if path.suffix not in _SOURCE_SUFFIXES:
            continue
        parts = set(path.relative_to(impl_root).parts)
        if parts & _EXCLUDE_DIR_NAMES:
            continue
        if parts & _TEST_DIR_NAMES:
            continue
        yield path


# --------------------------------------------------------------------------
# The two checks
# --------------------------------------------------------------------------


def run_checks(repo_root: Path) -> tuple[dict[str, list[str]], dict[str, list[str]]]:
    """Return (check_a_findings, check_b_findings) — each maps an offending
    token to the sorted list of impl files that emit it."""
    catalogue = catalogue_ids((repo_root / _SPEC_009).read_text(encoding="utf-8"))
    reserved = reserved_namespaces(
        (repo_root / _SPEC_CONVENTIONS).read_text(encoding="utf-8")
    )

    a_findings: dict[str, set[Path]] = {}
    b_findings: dict[str, set[Path]] = {}
    for path in iter_impl_src(repo_root / _IMPL_DIR):
        masked = mask(path.read_text(encoding="utf-8", errors="replace"))
        for kid in emitted_err_warn_ids(masked):
            if kid not in catalogue:
                a_findings.setdefault(kid, set()).add(path)
        for ns in emitted_namespaces(masked):
            if ns not in reserved:
                b_findings.setdefault(ns, set()).add(path)

    def rel(paths: set[Path]) -> list[str]:
        return sorted(
            str(p.relative_to(repo_root)).replace("\\", "/") for p in paths
        )

    return (
        {k: rel(v) for k, v in a_findings.items()},
        {k: rel(v) for k, v in b_findings.items()},
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
    "  collision protection + greppability anchor for framework-owned ids. A\n"
    "  prose / row-body mention of a specific member does NOT reserve the\n"
    "  namespace (rf2-qriq8): the reservation must be a `:rf.<ns>/*` glob row."
)


def report(a: dict[str, list[str]], b: dict[str, list[str]]) -> None:
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

    a, b = run_checks(repo_root)
    if args.verbose:
        n = sum(1 for _ in iter_impl_src(repo_root / _IMPL_DIR))
        sys.stderr.write(
            f"scanned {n} implementation source file(s); "
            f"check A findings={len(a)}, check B findings={len(b)}.\n"
        )
    if a or b:
        report(a, b)
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
        "| `:rf.route/*` | Routing; internal sub-ns `:rf.route.internal/*` | 012 |\n"
        "\n"
        "Prose AFTER the table: the retired draft opt `:rf.world/inputs` and a\n"
        "made-up `:rf.auditfake/member` mention — neither reserves a namespace.\n"
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

    # A glob-declared namespace passes — INCLUDING one declared in a row BODY
    # (the framework-internal `:rf.route.internal/*` sub-namespace under its
    # parent's row), which the live table relies on (rf.route.internal /
    # rf.mutation.internal / rf.resource.internal / rf.interceptor.path are each
    # declared under their parent's row rather than in their own first column).
    expect("B: glob-declared namespaces pass (incl body-declared sub-ns)",
           b_fire('(trace :rf.machine/transition [:rf/x] '
                  ':rf.route.internal/settle-transition)', reserved) == set())
    expect("B: unreserved namespace FIRES",
           b_fire('(trace :rf.zzznew/frobnicate 1)', reserved) == {"rf.zzznew"})

    # (1) arbitrary-prose / specific-member mention does NOT reserve — the
    #     rf2-qriq8 gap (an ordinary mention used to grant reservation).
    expect("B: prose member mention does NOT reserve (rf.auditfake)",
           "rf.auditfake" not in reserved)
    expect("B: emitting an only-prose-mentioned namespace FIRES",
           b_fire('(x :rf.auditfake/member)', reserved) == {"rf.auditfake"})

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

    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    if verbose:
        sys.stderr.write("all self-tests passed.\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
