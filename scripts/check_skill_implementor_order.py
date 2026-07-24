#!/usr/bin/env python3
"""Foundation-order drift guard: re-frame2-implementor skill must keep Spec 015
inside the core-complete gate (rf2-708nm).

The `re-frame2-implementor` skill walks a port author through Phase 2 in
dependency order, and several entry points encode that order as a literal
sequence — SKILL.md cardinal rule 3, the paste-ready kickoff prompt, the
cardinal-rules leaf, README.md, and the skill's own re-authoring `spec/` notes.
The foundation cluster ends at **acceptance gate 1**: running the `:core/*`
conformance fixtures, the point at which a port may declare "v1-core-complete".

Spec 015 (Data Classification) is **v1-required** (`spec/015-Data-Classification.md`
opens "Status: Drafting. **v1-required.**") and `spec/API.md` exposes the
frame-owned `:sensitive` / `:large` classification surface plus the `project-egress`
record-level boundary primitive and `register-observability-sink!` as v1 API (EP-0015
frame-owned egress policy; the earlier imperative `add-marks` / `set-marks` path
API is removed from the public facade). It rides the 009 emission boundary, so it
MUST sit inside the foundation cluster — ahead of the `:core/*` gate, NOT among the
optional EPs. rf2-708nm was exactly this drift: SKILL.md and phase-2-impl-order.md
had been updated to `001 -> 002 -> 006 -> 004 -> 009 -> 015 -> gate`, but several
other entry points still read `001 -> 002 -> 006 -> 004 -> 009 -> optional`,
placing the first `:core/*` gate BEFORE Data Classification. A fresh session
using the kickoff prompt — or a maintainer reading the stale leaf — could ship or
declare "v1-core-complete" without the required privacy/large-payload elision
surface, leaking marked data through observability.

This guard makes that class of drift a build failure. It scans the user-facing
implementor docs (SKILL.md, README.md, references/*.md) plus the skill-internal
re-authoring notes (spec/design.md, spec/authoring-prompt.md), and for every line
that encodes the foundation ordering / core-complete boundary, asserts that 015
is present alongside it.

A line "encodes the foundation boundary" when it mentions EP `009` AND a
boundary cue:

  * an explicit ordering arrow run that reaches `009` (`... 009 -> optional`,
    `... 009 ->` something), OR
  * the parenthesised foundation-cluster enumeration `(... / 009)` /
    `(... / 009 / ...)`, OR
  * "foundation" / "core-complete" / "required core" / ":core/*" gate language
    in the same clause as a `009`-terminated sequence.

When such a line is found, `015` (or "Data Classification") must appear in it.
Section *headings* for a single EP (e.g. "## EP 009 — Instrumentation",
"5. EP 009 — Instrumentation") and pure spec-file / URL citations
(`spec/009-Instrumentation.md`, `Implementor-Checklist/#...`) are NOT boundary
statements — they name one EP, not the cluster order — and are excluded.

Second scan — the **required-foundation gate** (rf2-j538f7.36). Ordering 015
correctly is necessary but not sufficient: the FIRST conformance gate must also
run 015's (and EP-0012's) separately-tagged fixtures, not `:core/*` alone. The
skill's own capability owner (`references/conformance.md` §Capability tagging)
declares three v1-required families — `:core/*`, `:identity/*`, and
`:data-classification/*` — and D7 always claims all three. So a line that pins
the gate-1 fixture scope by naming `:core/*` in gate-completion context (an
"acceptance gate" / "gate 1" / "core-complete" / "conformance fixtures|corpus|
pass" / "corpus pass" cue) MUST name all three family roots, never `:core/*`
alone. A green gate that silently excludes the identity/path and classification
fixtures is a behavioral false-green: a port could ship with broken CEDN-1
identity or leaking classified values and still declare "v1-core-complete". The
three roots are cross-checked against the conformance owner at run time so this
guard's constant can't drift from the family set it polices.

Exit code:
    0  no drift detected
    1  drift detected (printed line-by-line; GitHub-Actions ::error:: under CI)
    2  invocation / setup error

Usage:
    python scripts/check_skill_implementor_order.py
    python scripts/check_skill_implementor_order.py --verbose
    python scripts/check_skill_implementor_order.py --ci          # tighter output
                                                                  #   (auto under
                                                                  #   GITHUB_ACTIONS)
    python scripts/check_skill_implementor_order.py --self-test   # built-in
                                                                  #   pass/fail
                                                                  #   fixtures

rf2-708nm.
"""

from __future__ import annotations

import sys as _m6kdb_proof_sys  # THROWAWAY: force the LIVE scan to fail

if "--self-test" not in _m6kdb_proof_sys.argv:
    _m6kdb_proof_sys.stderr.write("m6kdb proof: forced LIVE failure\n")
    raise SystemExit(1)

import argparse
import os
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# Force UTF-8 on output streams — the corpus carries -> / em-dash etc. and the
# default Windows console codec (cp1252) would crash on them (rf2 is maintained
# on Windows; the gate also runs on Linux CI).
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")  # type: ignore[union-attr]
    except (AttributeError, ValueError):  # pragma: no cover - non-TextIO stream
        pass

SKILL_DIR = REPO_ROOT / "skills" / "re-frame2-implementor"

# The files an author / fresh session reads for the foundation order. SKILL.md +
# README.md are the front door; references/ are the on-demand leaves; the skill's
# own spec/ notes are the re-authoring source (a future reauthor pass reads
# design.md + authoring-prompt.md and would re-encode whatever they say).
SCANNED_FILES = [
    SKILL_DIR / "SKILL.md",
    SKILL_DIR / "README.md",
    SKILL_DIR / "references" / "cardinal-rules.md",
    SKILL_DIR / "references" / "kickoff-prompt.md",
    SKILL_DIR / "references" / "phase-2-impl-order.md",
    SKILL_DIR / "references" / "decision-record.md",
    SKILL_DIR / "spec" / "design.md",
    SKILL_DIR / "spec" / "authoring-prompt.md",
]

# A "009" token that is NOT part of a spec-file / URL / anchor citation. We strip
# those citation forms before testing, so `spec/009-Instrumentation.md`,
# `009-Instrumentation/`, and `#...009...` never count as an ordering mention.
CITATION_RE = re.compile(
    r"""
    \b0?09-Instrumentation(?:\.md)?       # spec/009-Instrumentation.md / .../009-Instrumentation/
  | /009-Instrumentation
  | \#[\w-]*009[\w-]*                      # an in-URL anchor mentioning 009
    """,
    re.VERBOSE,
)

# A bare EP number 009, word-boundaried so it does not match inside 0090 etc.
EP_009_RE = re.compile(r"(?<!\d)009(?!\d)")
EP_015_RE = re.compile(r"(?<!\d)015(?!\d)")
DATA_CLASS_RE = re.compile(r"data classification", re.IGNORECASE)

# An ordering-arrow run that reaches 009 — `-> 009`, `→ 009`, or `009 ->`/`009 →`.
# Either direction proves the line is sequencing EPs, not naming one.
ARROW = r"(?:->|→|/)"
ORDER_RUN_RE = re.compile(
    rf"(?:{ARROW}\s*009)|(?:009\s*{ARROW})"
)

# Boundary / cluster language that, combined with a 009 ordering run, marks the
# line as a foundation-order statement.
BOUNDARY_CUE_RE = re.compile(
    r"foundation"
    r"|core-complete"
    r"|required core"
    r"|optional"
    r"|:core/\*"
    r"|acceptance gate",
    re.IGNORECASE,
)

# A single-EP section heading like "## EP 009 — Instrumentation" or
# "5. EP 009 — Instrumentation" or "### EP 009 ...". These name ONE EP (the
# current Phase-2 step), not the cluster order, so they are not boundary
# statements even though they contain 009.
EP_HEADING_RE = re.compile(
    r"^\s*(?:#{1,6}\s*|\d+\.\s+)?EP\s+009\b"
)

# ---------------------------------------------------------------------------
# Required-foundation gate scan (rf2-j538f7.36).
# ---------------------------------------------------------------------------

# The skill's capability owner — references/conformance.md §Capability tagging —
# is the single source for which families are v1-required. The gate-1 entry
# points must name all three; this constant is cross-checked against the owner
# at run time (see verify_owner_declares_required_roots) so it can't drift.
OWNER_FILE = SKILL_DIR / "references" / "conformance.md"

REQUIRED_ROOT_RES = {
    ":core/*": re.compile(r":core/\*"),
    ":identity/*": re.compile(r":identity/\*"),
    ":data-classification/*": re.compile(r":data-classification/\*"),
}
CORE_ROOT_RE = REQUIRED_ROOT_RES[":core/*"]

# Gate-completion language that, combined with a `:core/*` mention, marks a line
# as pinning the gate-1 FIXTURE SCOPE (as opposed to naming `:core/*` in some
# other context — the D7 claim catalog, the derivation-algebra "the :core/* sub
# fixtures", a Q6 "EP families that own them (:core/*, etc.)"). Phrases are
# adjacency-anchored so a bold "**Conformance.**" lead-in does not count.
GATE_SCOPE_CUE_RE = re.compile(
    r"acceptance gate"
    r"|\bgate[- ]1\b"
    r"|core-complete"          # covers v1-core-complete
    r"|core gate"
    r"|conformance fixtures"
    r"|conformance corpus"
    r"|conformance pass"
    r"|corpus pass",           # covers "corpus passes"
    re.IGNORECASE,
)


def _slurp(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def line_states_foundation_boundary(line: str) -> bool:
    """True iff `line` encodes the foundation order / core-complete boundary
    (and so MUST carry 015), as opposed to merely mentioning EP 009."""
    # Drop spec-file / URL / anchor citations so they never count as a 009 mention.
    scrubbed = CITATION_RE.sub("", line)
    if not EP_009_RE.search(scrubbed):
        return False
    # A single-EP section heading names one step, not the cluster order.
    if EP_HEADING_RE.match(line):
        return False
    has_order_run = bool(ORDER_RUN_RE.search(scrubbed))
    has_boundary_cue = bool(BOUNDARY_CUE_RE.search(scrubbed))
    # It is a foundation-order statement when 009 sits in an ordering run AND the
    # line carries boundary/cluster language (so "EP 009 is in the foundation
    # because ..." prose without a sequence is not flagged, but
    # "... 009 -> optional" and "(001 / ... / 009) ... required core" are).
    return has_order_run and has_boundary_cue


def line_includes_015(line: str) -> bool:
    scrubbed = CITATION_RE.sub("", line)
    return bool(EP_015_RE.search(scrubbed) or DATA_CLASS_RE.search(scrubbed))


def line_states_gate_scope(line: str) -> bool:
    """True iff `line` pins the gate-1 FIXTURE SCOPE — it names `:core/*` in
    gate-completion context (so it MUST name all three v1-required family roots,
    not `:core/*` alone). A `:core/*` mention outside that context (the D7 claim
    catalog, the derivation-algebra fixtures, a Q6 owner list) is not a gate
    statement."""
    if not CORE_ROOT_RE.search(line):
        return False
    return bool(GATE_SCOPE_CUE_RE.search(line))


def line_names_all_required_roots(line: str) -> bool:
    return all(rx.search(line) for rx in REQUIRED_ROOT_RES.values())


def missing_required_roots(line: str) -> list[str]:
    return [root for root, rx in REQUIRED_ROOT_RES.items() if not rx.search(line)]


def find_drift(files: list[Path]) -> tuple[list[str], int]:
    """Return (drift messages, number-of-boundary-lines-checked)."""
    problems: list[str] = []
    checked = 0
    for path in files:
        if not path.is_file():
            problems.append(
                f"SETUP: expected implementor-skill file missing: "
                f"{path.relative_to(REPO_ROOT)} — the guard's file list drifted "
                "from the skill layout; update SCANNED_FILES."
            )
            continue
        for lineno, line in enumerate(_slurp(path).splitlines(), start=1):
            if not line_states_foundation_boundary(line):
                continue
            checked += 1
            if line_includes_015(line):
                continue
            rel = path.relative_to(REPO_ROOT)
            problems.append(
                f"ORDER-DRIFT: {rel}:{lineno} states the foundation order / "
                "core-complete boundary but omits Spec 015. Spec 015 (Data "
                "Classification) is v1-required and rides the 009 emission "
                "boundary — it MUST sit inside the core gate "
                "(001 -> 002 -> 006 -> 004 -> 009 -> 015 -> gate), not "
                "among the optional EPs. Add 015 to the sequence.\n"
                f"    {line.strip()}"
            )
    return problems, checked


def find_gate_drift(files: list[Path]) -> tuple[list[str], int]:
    """Return (drift messages, number-of-gate-scope-lines-checked).

    A gate-1 fixture-scope statement (see line_states_gate_scope) MUST name all
    three v1-required family roots — `:core/*`, `:identity/*`,
    `:data-classification/*` — never `:core/*` alone."""
    problems: list[str] = []
    checked = 0
    for path in files:
        if not path.is_file():
            continue  # missing-file setup error already raised by find_drift
        for lineno, line in enumerate(_slurp(path).splitlines(), start=1):
            if not line_states_gate_scope(line):
                continue
            checked += 1
            missing = missing_required_roots(line)
            if not missing:
                continue
            rel = path.relative_to(REPO_ROOT)
            problems.append(
                f"GATE-DRIFT: {rel}:{lineno} pins the acceptance-gate-1 fixture "
                "scope but names only part of the v1-required foundation "
                f"(missing {', '.join(missing)}). Gate 1 is the "
                "required-foundation gate — it runs every fixture applicable to "
                "all three v1-required families (:core/* + :identity/* + "
                ":data-classification/*, per references/conformance.md "
                "§Capability tagging), not :core/* alone; :core/* alone silently "
                "skips the EP-0012 path/identity and Spec 015 classification "
                "fixtures the skill calls mandatory. Name all three families.\n"
                f"    {line.strip()}"
            )
    return problems, checked


def verify_owner_declares_required_roots() -> list[str]:
    """Cross-check the guard's REQUIRED_ROOT_RES constant against the capability
    owner (references/conformance.md). If the owner stops declaring one of the
    three roots — or drops the v1-required framing — the guard is policing a
    family set the skill no longer teaches, which is itself drift. Returns SETUP
    problems (empty when the owner and the constant agree)."""
    problems: list[str] = []
    if not OWNER_FILE.is_file():
        return [
            f"SETUP: capability owner missing: "
            f"{OWNER_FILE.relative_to(REPO_ROOT)} — the gate scan's family-root "
            "source is gone; update OWNER_FILE."
        ]
    owner = _slurp(OWNER_FILE)
    for root, rx in REQUIRED_ROOT_RES.items():
        if not rx.search(owner):
            problems.append(
                f"SETUP: {OWNER_FILE.relative_to(REPO_ROOT)} no longer declares "
                f"the v1-required family root {root} that this guard requires at "
                "gate 1. Reconcile REQUIRED_ROOT_RES with the conformance owner."
            )
    if not re.search(r"v1-required", owner, re.IGNORECASE):
        problems.append(
            f"SETUP: {OWNER_FILE.relative_to(REPO_ROOT)} no longer marks the "
            "identity/classification families as v1-required — the gate-scan "
            "premise (three v1-required families) has drifted from the owner."
        )
    return problems


def run(*, verbose: bool, ci: bool) -> int:
    if not SKILL_DIR.is_dir():
        sys.stderr.write(
            f"error: re-frame2-implementor skill not found at {SKILL_DIR}\n"
        )
        return 2

    owner_problems = verify_owner_declares_required_roots()
    if owner_problems:
        err_prefix = "::error::" if ci else ""
        for p in owner_problems:
            print(f"{err_prefix}{p}")
        return 2

    order_problems, order_checked = find_drift(SCANNED_FILES)
    gate_problems, gate_checked = find_gate_drift(SCANNED_FILES)
    problems = order_problems + gate_problems

    if verbose:
        print(
            f"implementor foundation guard: scanned {len(SCANNED_FILES)} files, "
            f"found {order_checked} foundation-boundary statement(s) and "
            f"{gate_checked} gate-1 fixture-scope statement(s)."
        )

    if not problems:
        if verbose:
            print(
                "foundation guard: every foundation-boundary statement keeps "
                "Spec 015 inside the core gate, and every gate-1 fixture-scope "
                "statement names all three v1-required families "
                "(:core/* + :identity/* + :data-classification/*)."
            )
        return 0

    err_prefix = "::error::" if ci else ""
    for p in problems:
        print(f"{err_prefix}{p}")
    print(
        f"\nfoundation guard: {len(problems)} drift issue(s) "
        f"({len(order_problems)} order, {len(gate_problems)} gate). Spec 015 is "
        "v1-required and must sit inside the core gate, and acceptance gate 1 "
        "must run all three v1-required families (:core/* + :identity/* + "
        ":data-classification/*), not :core/* alone."
    )
    return 1


# ---------------------------------------------------------------------------
# Self-test — exercises the line classifier against in-memory fixtures so the
# guard itself can't silently rot. Mirrors the --self-test convention in the
# sibling check_skill_*.py guards.
# ---------------------------------------------------------------------------

def _self_test() -> int:
    failures = 0

    def expect(line: str, *, boundary: bool, has015: bool, label: str) -> None:
        nonlocal failures
        got_boundary = line_states_foundation_boundary(line)
        got_015 = line_includes_015(line)
        if got_boundary != boundary:
            print(
                f"SELF-TEST FAIL ({label}): boundary classification "
                f"expected {boundary}, got {got_boundary} for: {line!r}"
            )
            failures += 1
        if boundary and got_015 != has015:
            print(
                f"SELF-TEST FAIL ({label}): 015-presence "
                f"expected {has015}, got {got_015} for: {line!r}"
            )
            failures += 1

    # FAIL fixtures — these are the rf2-708nm drift shapes; each is a boundary
    # statement that OMITS 015 (so the guard must flag them).
    expect(
        "3. Implement in dependency order: 001 -> 002 -> 006 -> 004 -> 009 -> optional.",
        boundary=True, has015=False, label="A authoring-prompt arrow run",
    )
    expect(
        "EP 001 → 002 → 006 → 004 → 009 Instrumentation → optional EPs per Phase 1 scope.",
        boundary=True, has015=False, label="B README arrow run",
    )
    expect(
        "the foundation cluster (001 / 002 / 006 / 004 / 009) and the optional EPs",
        boundary=True, has015=False, label="C design.md cluster enumeration",
    )
    expect(
        "| **Required core** (000 / 001 / 002 / 004 / 006 / 009) | yes | non-negotiable |",
        boundary=True, has015=False, label="D decision-record required-core row",
    )

    # PASS fixtures — boundary statements that DO carry 015 (the corrected shapes).
    expect(
        "001 → 002 → 006 → 004 → 009 → 015, then optional EPs per Phase 1 scope",
        boundary=True, has015=True, label="E corrected SKILL arrow run",
    )
    expect(
        "the foundation cluster (001 / 002 / 006 / 004 / 009 / 015) and the optional EPs",
        boundary=True, has015=True, label="F corrected cluster enumeration",
    )
    expect(
        "001 -> 002 -> 006 -> 004 -> 009 -> Data Classification -> optional",
        boundary=True, has015=True, label="G prose name instead of number",
    )

    # NOT-A-BOUNDARY fixtures — these mention 009 but are not foundation-order
    # statements, so they must NOT be flagged regardless of 015.
    expect(
        "## EP 009 — Instrumentation",
        boundary=False, has015=False, label="H single-EP section heading",
    )
    expect(
        "5. EP 009 — Instrumentation",
        boundary=False, has015=False, label="I numbered single-EP heading",
    )
    expect(
        "**Read first.** [`spec/009-Instrumentation.md`](https://day8.github.io/re-frame2/spec/009-Instrumentation/).",
        boundary=False, has015=False, label="J spec-file + URL citation",
    )
    expect(
        "009 is in the foundation because `:core/trace` and `:core/error` fixtures exercise it.",
        boundary=False, has015=False, label="K prose rationale, no sequence arrow",
    )
    expect(
        "the public API + the heart of EP 001 / 002 / 009; also core/src/...",
        boundary=False, has015=False, label="L impl-tour file map (no boundary cue)",
    )

    # -- Required-foundation gate scan (rf2-j538f7.36) --------------------
    def expect_gate(line: str, *, gate: bool, all_roots: bool, label: str) -> None:
        nonlocal failures
        got_gate = line_states_gate_scope(line)
        got_roots = line_names_all_required_roots(line)
        if got_gate != gate:
            print(
                f"SELF-TEST FAIL ({label}): gate-scope classification "
                f"expected {gate}, got {got_gate} for: {line!r}"
            )
            failures += 1
        if gate and got_roots != all_roots:
            print(
                f"SELF-TEST FAIL ({label}): all-roots-present "
                f"expected {all_roots}, got {got_roots} for: {line!r}"
            )
            failures += 1

    # FAIL fixtures — gate-1 scope statements that name :core/* alone (the
    # rf2-j538f7.36 false-green shapes); the gate scan must flag each.
    expect_gate(
        "Acceptance gate 1 — running the `:core/*` conformance fixtures — sits at the end.",
        gate=True, all_roots=False, label="M core-only cardinal-rule gate",
    )
    expect_gate(
        "The `:core/*` conformance corpus at `spec/conformance/` is the acceptance test.",
        gate=True, all_roots=False, label="N core-only README acceptance corpus",
    )
    # The exact miss the old order-only guard let through: EP 015 correctly
    # ORDERED, yet the gate runs :core/* + :data-classification/* only — the
    # separately-tagged :identity/* (EP-0012) family is absent from gate 1.
    ordered_but_no_identity = (
        "Acceptance gate 1 (001 -> 002 -> 006 -> 004 -> 009 -> 015): run every "
        "fixture applicable to `:core/*` + `:data-classification/*`."
    )
    expect(ordered_but_no_identity, boundary=True, has015=True,
           label="O order is clean (015 present) — order scan must NOT flag")
    expect_gate(ordered_but_no_identity, gate=True, all_roots=False,
                label="O gate scan must flag (identity absent from gate 1)")
    expect_gate(
        "the first acceptance gate runs the `:core/*` + `:identity/*` fixtures",
        gate=True, all_roots=False, label="P classification family absent",
    )

    # PASS fixtures — gate-1 scope statements naming all three v1-required roots.
    expect_gate(
        "Acceptance gate 1 — the required-foundation gate: run every fixture "
        "applicable to `:core/*` + `:identity/*` + `:data-classification/*`.",
        gate=True, all_roots=True, label="Q corrected three-family gate",
    )

    # NOT-A-GATE fixtures — mention :core/* but do not pin the gate-1 scope, so
    # they must NOT be flagged regardless of the other families.
    expect_gate(
        "the algebra is verified through the source-form fixtures (the `:core/*` "
        "sub fixtures, `:flow/*`, resources / routing / machines families)",
        gate=False, all_roots=False, label="R derivation-algebra fixtures (no gate cue)",
    )
    expect_gate(
        "      :core/*                 ; always — pattern-required",
        gate=False, all_roots=False, label="S D7 claim-catalog row (no gate cue)",
    )
    expect_gate(
        "gate 2 runs the full claimed-capability set, a superset of `:core/*`",
        gate=False, all_roots=False, label="T gate-2 line (gate-1 cue absent)",
    )

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
