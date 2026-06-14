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
                "(001 -> 002 -> 006 -> 004 -> 009 -> 015 -> :core/* gate), not "
                "among the optional EPs. Add 015 to the sequence.\n"
                f"    {line.strip()}"
            )
    return problems, checked


def run(*, verbose: bool, ci: bool) -> int:
    if not SKILL_DIR.is_dir():
        sys.stderr.write(
            f"error: re-frame2-implementor skill not found at {SKILL_DIR}\n"
        )
        return 2

    problems, checked = find_drift(SCANNED_FILES)

    if verbose:
        print(
            f"implementor foundation-order guard: scanned {len(SCANNED_FILES)} "
            f"files, found {checked} foundation-boundary statement(s)."
        )

    if not problems:
        if verbose:
            print(
                "foundation-order: every foundation-boundary statement keeps "
                "Spec 015 inside the core gate."
            )
        return 0

    err_prefix = "::error::" if ci else ""
    for p in problems:
        print(f"{err_prefix}{p}")
    print(
        f"\nfoundation-order: {len(problems)} drift issue(s) — Spec 015 is "
        "v1-required and must sit inside the implementor core gate, not among "
        "the optional EPs."
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
