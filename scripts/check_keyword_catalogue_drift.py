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
    in the Spec 009 error/warning catalogue (spec/009-Instrumentation.md), OR be
    an explicitly-listed KNOWN_UNCATALOGUED id (below). This is the check that
    would have caught rf2-720yoj + rf2-xrk4w1.

  CHECK B — Reserved-namespace coverage.
    Every `:rf.*` keyword NAMESPACE emitted by the implementation source MUST be
    reserved in spec/Conventions.md (the reserved-namespace authority). This is
    the check that guards the class rf2-0lb6xc closed (≈14 impl-emitted
    namespaces that lacked a Conventions row).

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

KNOWN_UNCATALOGUED (the current, frozen debt ledger)
----------------------------------------------------
A sweep of the tree at gate-authoring time (2026-07-09) found 33 emitted
error/warning ids with no 009 row. They fall in two natures (see the two groups
below). They are enumerated here so the gate is GREEN today yet keeps its
TEETH: a NEW uncatalogued id — not in 009 AND not in this list — fails the gate.
The list is a FROZEN ledger, not an open licence: it may only SHRINK (as a
row lands in 009, or as an out-of-remit id is retired). Tracked in rf2-cs0kd1.

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

# Namespace token as it appears in the Spec (e.g. `:rf.error` inside
# `:rf.error/*`); group 0 minus the leading colon is the reserved namespace.
_RF_NS_TOKEN_RE = re.compile(r":rf(?:\.[a-z][\w.-]*)?")


# --------------------------------------------------------------------------
# KNOWN_UNCATALOGUED — frozen debt ledger (see module docstring). Tracked:
# rf2-cs0kd1. May only shrink.
# --------------------------------------------------------------------------
#
# Group 1 — substrate-adapter / test-adapter / conformance-DSL INTERNAL ids.
# Spec 009 catalogues the re-frame2 RUNTIME's error/warning events (the
# event / sub / fx / cofx / machine / flow / route / resource / http / ssr
# families). These live in a substrate adapter (reagent-slim reimplements
# Reagent; test-react is a test-only substrate) or the conformance-DSL test
# harness — arguably out of the runtime catalogue's remit. Mike to rule per id
# whether each earns a 009 row or is declared permanently out-of-catalogue.
_KNOWN_GROUP1_OUT_OF_REMIT = frozenset({
    # reagent-slim adapter internals (Reagent-domain hiccup/component errors)
    ":rf.error/as-element-fn-unregistered",
    ":rf.error/create-class-key-unsupported",
    ":rf.error/create-class-missing-render",
    ":rf.error/static-markup-bad-element",
    ":rf.error/static-markup-bad-tag",
    ":rf.error/static-markup-empty-vector",
    ":rf.error/template-bad-tag",
    ":rf.error/template-empty-vector",
    # test-react adapter (test-only substrate)
    ":rf.error/mount-child-outside-render",
    ":rf.error/sync-unmount-during-render",
    ":rf.error/test-react-not-installed",
    ":rf.error/update-after-unmount",
    # conformance-DSL test harness (siblings ARE catalogued; these are gaps)
    ":rf.error/conformance-cannot-count-nil",
    ":rf.error/conformance-cannot-count-string",
    ":rf.error/conformance-throw-step",
})

# Group 2 — framework RUNTIME error/warning ids MISSING a 009 row: genuine
# catalogue-completion debt. Each SHOULD gain a 009 row (co-edit invariant);
# remove it from this list when that row lands.
_KNOWN_GROUP2_CATALOGUE_DEBT = frozenset({
    # machine spec validation (finer ids than 009's machine-* rows)
    ":rf.error/machine-bad-after-delay",
    ":rf.error/machine-bad-after-spec",
    ":rf.error/machine-bad-on-done-clause",
    ":rf.error/machine-bad-target",
    ":rf.error/machine-parallel-bad-shape",
    ":rf.error/machine-parallel-on-done-target",
    ":rf.error/machine-parallel-root-on-bad-target",
    ":rf.error/machine-unresolved-target",
    # resources registration / scope-resolution
    ":rf.error/infinite-missing-next-page-param",
    ":rf.error/invalid-resource-scope-spec",
    ":rf.error/resource-scope-not-registered",
    ":rf.error/resource-scope-source-reserved",
    ":rf.error/resource-scope-unresolved-reference",
    # routing validation
    ":rf.error/route-decimal-unsupported",
    ":rf.error/route-url-validation",
    ":rf.warning/malformed-url",
    # flows topological-sort internal invariant
    ":rf.error/flow-cycle-extract-invariant",
    # core transducer-router handler-throw trace :operation
    ":rf.error/handler-throw",
})

KNOWN_UNCATALOGUED = _KNOWN_GROUP1_OUT_OF_REMIT | _KNOWN_GROUP2_CATALOGUE_DEBT


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


def emitted_namespaces(masked: str) -> set[str]:
    """The `:rf.*` keyword namespaces in masked source text (e.g. `rf.error`)."""
    return {m.group(1) for m in _RF_KEYWORD_RE.finditer(masked)}


def catalogue_ids(spec_009_text: str) -> set[str]:
    """The set of `:rf.error/*` / `:rf.warning/*` ids the Spec 009 catalogue
    acknowledges (anywhere in the doc — a row is a superset of a prose mention;
    the whole doc is the catalogue's home). Exact-token set (NOT substring), so
    a longer id is never spuriously covered by a shorter one."""
    return set(_ERR_WARN_RE.findall(spec_009_text))


def reserved_namespaces(conventions_text: str) -> set[str]:
    """The set of `:rf.*` namespaces Conventions reserves — every `:rf` /
    `:rf.<seg>…` token in the doc (the reserved-namespace authority)."""
    return {m.group(0)[1:] for m in _RF_NS_TOKEN_RE.finditer(conventions_text)}


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
            if kid not in catalogue and kid not in KNOWN_UNCATALOGUED:
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
    "  the drift rf2-720yoj / rf2-xrk4w1 shipped. Fix ONE of:\n"
    "    * add the catalogue row in spec/009-Instrumentation.md (co-edit\n"
    "      invariant — do it in the SAME PR as the emitting change); or\n"
    "    * if the id is genuinely out of the runtime catalogue's remit\n"
    "      (a substrate-adapter / test-harness internal), add it to\n"
    "      KNOWN_UNCATALOGUED in this script WITH a one-line rationale."
)
_FIX_B = (
    "Each namespace above is emitted by implementation source but is NOT\n"
    "  reserved in spec/Conventions.md. Add a reserved-namespace row (this is\n"
    "  the class rf2-0lb6xc closed) — the reserved-namespace scheme is the\n"
    "  collision protection + greppability anchor for framework-owned ids."
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
        "| `:rf.error/*` | ... |\n| `:rf.machine/*` | ... |\n| `:rf/*` | ... |\n"
    )
    catalogue = catalogue_ids(synthetic_009)
    reserved = reserved_namespaces(synthetic_conventions)

    # CHECK A teeth ---------------------------------------------------------
    good_src = mask('(throw-error! :rf.error/handler-exception)')
    bad_src = mask('(throw-error! :rf.error/totally-uncatalogued)')
    a_good = {k for k in emitted_err_warn_ids(good_src)
              if k not in catalogue and k not in KNOWN_UNCATALOGUED}
    a_bad = {k for k in emitted_err_warn_ids(bad_src)
             if k not in catalogue and k not in KNOWN_UNCATALOGUED}
    expect("A: catalogued id passes", a_good == set())
    expect("A: uncatalogued id FIRES", a_bad == {":rf.error/totally-uncatalogued"})

    # A: an id present only in a docstring/comment must NOT fire (masking).
    prose_src = mask('"doc mentions :rf.error/totally-uncatalogued"\n'
                     ';; and :rf.error/also-uncatalogued in a comment')
    expect("A: prose/docstring mention does NOT fire",
           emitted_err_warn_ids(prose_src) == set())

    # A: a KNOWN_UNCATALOGUED id does not fire.
    known = next(iter(KNOWN_UNCATALOGUED))
    known_src = mask(f'(throw-error! {known})')
    a_known = {k for k in emitted_err_warn_ids(known_src)
               if k not in catalogue and k not in KNOWN_UNCATALOGUED}
    expect("A: KNOWN_UNCATALOGUED id does NOT fire", a_known == set())

    # CHECK B teeth ---------------------------------------------------------
    good_ns = mask('(trace :rf.machine/transition [:rf/redacted])')
    bad_ns = mask('(trace :rf.zzznew/frobnicate 1)')
    b_good = {ns for ns in emitted_namespaces(good_ns) if ns not in reserved}
    b_bad = {ns for ns in emitted_namespaces(bad_ns) if ns not in reserved}
    expect("B: reserved namespaces pass", b_good == set())
    expect("B: unreserved namespace FIRES", b_bad == {"rf.zzznew"})

    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    if verbose:
        sys.stderr.write("all self-tests passed.\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
