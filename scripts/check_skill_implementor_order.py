#!/usr/bin/env python3
"""Foundation-order drift guard: re-frame2-implementor skill must keep Spec 015
inside the core-complete gate (rf2-708nm).

The `re-frame2-implementor` skill walks a port author through Phase 2 in
dependency order, and several entry points encode that order as a literal
sequence — SKILL.md cardinal rule 3 and its §Kickoff prompt, the
cardinal-rules leaf, README.md, the port-profile and EP-loop leaves, and the
skill's own re-authoring `spec/` notes.
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

The order scan requires both v1-required tail EPs — `015` (Data
Classification) and `013` (Flows) — on any line that encodes the sequence, so
neither can quietly fall out of the foundation and back among the optional EPs.

A line "encodes the foundation boundary" when it mentions EP `009` AND a
boundary cue:

  * an explicit ordering arrow run that reaches `009` (`... 009 -> optional`,
    `... 009 ->` something), OR
  * the parenthesised foundation-cluster enumeration `(... / 009)` /
    `(... / 009 / ...)`, OR
  * "foundation" / "core-complete" / "required core" / ":core/*" gate language
    in the same clause as a `009`-terminated sequence.

When such a line is found, `015` (or "Data Classification") and `013` must
both appear in it.
Section *headings* for a single EP (e.g. "## EP 009 — Instrumentation",
"5. EP 009 — Instrumentation") and pure spec-file / URL citations
(`spec/009-Instrumentation.md`, `Implementor-Checklist/#...`) are NOT boundary
statements — they name one EP, not the cluster order — and are excluded.

Second scan — the **required-foundation gate** (rf2-j538f7.36, rf2-k2r1).
Ordering 015 correctly is necessary but not sufficient: the FIRST conformance
gate must also run the separately-tagged fixtures of every other v1-required
family, not `:core/*` alone. Those families are `:core/*`, `:identity/*`,
`:flow/*` and `:data-classification/*`. So a line that pins the gate-1 fixture
scope by naming `:core/*` in gate-completion context (an "acceptance gate" /
"gate 1" / "core-complete" / "conformance fixtures|corpus|pass" / "corpus pass"
cue) MUST name all four family roots, never `:core/*` alone. A green gate that
silently excludes the identity/path, flow or classification fixtures is a
behavioral false-green: a port could ship with broken CEDN-1 identity, no flow
substrate at all, or leaking classified values and still declare
"v1-core-complete".

`:flow/*` joined that set in rf2-k2r1, which is the drift this scan's
cross-check was rebuilt for. The skill had inferred that flows, managed HTTP
and resources were "skill-local optional" capabilities because the checklist
numbered only Q1-Q7; the checklist then grew Q8 (Managed HTTP) and Q9
(Resources) and made Flows a NON-gated Required row precisely so a port could
not opt out of Spec 013. The skill never consumed that change, so a minimum
port put `:flow/*` on `known-skipped`, kept both gates green over the smaller
claim, and reported itself v1-complete with no flow substrate.

**The cross-check is deliberately two-sided, because a one-sided one cannot see
that class of drift.** Checking the constant against the SKILL's own capability
leaf (`references/conformance.md`) proves only that the skill and this guard
agree — which they did, both omitting `:flow/*`, all the way through the
false-green. So the required roots are ALSO derived from the NORMATIVE owner:
`spec/Implementor-Checklist.md` Part 3's family table, whose "always run" rows
are the contract. The derived set must equal this guard's constant exactly, in
both directions, and a derivation that matches zero rows is a SETUP failure
rather than a vacuous pass.

Third scan — the **EP-006 live sub-cache witness** (rf2-3758j). The corpus's two
`:identity/cedn1` cache-key fixtures call the canonical-identity primitive
directly: they prove the cache-KEY prerequisite, never live cache wiring, and
the corpus subscribes each query once (the owning Spec's
conformance-observability note, `spec/006-ReactiveSubstrate.md` §Value-keyed
cache-key contract). A port whose live sub-cache keys by host reference
identity therefore passes every required fixture while equal freshly-allocated
queries create separate derived containers forever — a corpus-green /
runtime-red false completion. The skill closes that hole by requiring a
port-owned live witness (one query through two distinct host allocations, one
cache-slot creation, exactly-once disposal, a non-rf= negative control) on the
completion surfaces: `SKILL.md` §Done, the EP-loop leaf (which owns the witness
definition), and the conformance leaf (which owns scoring/reporting). This scan
makes removing — or hollowing — that requirement a build failure: each
completion surface must still reference the witness, and the owner's definition
must keep its observable elements. It pins the skill's own contract language,
never a fixture catalogue or an implementation token.

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
    SKILL_DIR / "references" / "phase-1-decisions.md",
    SKILL_DIR / "references" / "phase-2-impl-order.md",
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
  | \b0?13-Flows(?:\.md)?                  # spec/013-Flows.md / .../013-Flows/
  | /013-Flows
  | \#[\w-]*013[\w-]*
  | \b0?15-Data-Classification(?:\.md)?    # spec/015-Data-Classification.md
  | /015-Data-Classification
  | \#[\w-]*015[\w-]*
    """,
    re.VERBOSE,
)

# A bare EP number 009, word-boundaried so it does not match inside 0090 etc.
EP_009_RE = re.compile(r"(?<!\d)009(?!\d)")
EP_015_RE = re.compile(r"(?<!\d)015(?!\d)")
DATA_CLASS_RE = re.compile(r"data classification", re.IGNORECASE)
# EP 013 (Flows) is v1-required too (rf2-k2r1) and closes the foundation
# cluster. Unlike 015 there is no prose alias accepted here: the sequences are
# numeric, so a bare `013` (citations already scrubbed above) is the token.
EP_013_RE = re.compile(r"(?<!\d)013(?!\d)")

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

# Two owners, deliberately (rf2-k2r1). The skill's capability leaf is where a
# port author READS the family set; `spec/Implementor-Checklist.md` Part 3 is
# where the project DECIDES it. Cross-checking the constant against the skill
# alone proves only that the skill and this guard agree — the exact state that
# let `:flow/*` go missing from both. So the constant is checked against the
# skill leaf (it must still teach every required root) AND derived from the
# normative table (which arbitrates what the set actually is).
OWNER_FILE = SKILL_DIR / "references" / "conformance.md"
NORMATIVE_OWNER_FILE = REPO_ROOT / "spec" / "Implementor-Checklist.md"

REQUIRED_ROOT_RES = {
    ":core/*": re.compile(r":core/\*"),
    ":identity/*": re.compile(r":identity/\*"),
    ":flow/*": re.compile(r":flow/\*"),
    ":data-classification/*": re.compile(r":data-classification/\*"),
}

# A Part 3 family-table row whose "Gated by" cell is exactly "nothing — always
# run". The exact cell is the discriminator, not the word "nothing":
# `:derivation/*` reads "nothing declares it — but every current fixture is
# cross-tagged, so in practice Q1", which is a GATED family in practice and
# must not be pulled in here.
ALWAYS_RUN_ROW_RE = re.compile(
    r"^\|\s*`(:[a-z][a-z0-9.-]*/\*)`\s*\|\s*(?:\*\*)?nothing\s*[\u2014\u2013-]\s*always run",
    re.IGNORECASE | re.MULTILINE,
)
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


# ---------------------------------------------------------------------------
# EP-006 live sub-cache witness scan (rf2-3758j).
# ---------------------------------------------------------------------------

# The completion surfaces that must carry the witness requirement: the
# front-door Done gate, the EP-loop leaf (the witness definition's owner), and
# the conformance leaf (scoring/reporting — where a fixture N/N could otherwise
# read as whole-port completion).
WITNESS_OWNER_FILE = SKILL_DIR / "references" / "phase-2-impl-order.md"
WITNESS_REQUIRED_FILES = [
    SKILL_DIR / "SKILL.md",
    WITNESS_OWNER_FILE,
    SKILL_DIR / "references" / "conformance.md",
]

# A line REFERENCES the witness when it names it. The pre-fix drift shape —
# calling the :identity/cedn1 fixtures themselves "sub-cache fixtures" — does
# NOT match: the witness term is "live sub-cache witness".
WITNESS_REF_RE = re.compile(r"live sub-cache witness", re.IGNORECASE)

# The owner's definition must keep the observable elements — a "run a live
# test" sentence with no observed outcome is exactly the false-green being
# policed. Each regex pins the skill's own contract language (markdown bold
# tolerated), not fixture ids or implementation tokens.
WITNESS_ELEMENT_RES = {
    "two distinct host allocations": re.compile(
        r"distinct host allocations", re.IGNORECASE
    ),
    "exactly one cache-slot creation": re.compile(
        r"\*{0,2}one\*{0,2} cache-?slot creation", re.IGNORECASE
    ),
    "exactly-once disposal": re.compile(
        r"disposal fires \*{0,2}exactly once\*{0,2}|exactly-once disposal",
        re.IGNORECASE,
    ),
    "non-rf= negative control": re.compile(r"negative control", re.IGNORECASE),
    "score honesty (beside, never inside/folded)": re.compile(
        r"never (?:inside|folded into)", re.IGNORECASE
    ),
}


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


def line_includes_013(line: str) -> bool:
    return bool(EP_013_RE.search(CITATION_RE.sub("", line)))


def missing_required_eps(line: str) -> list[str]:
    """Which v1-required tail EPs a foundation-order statement fails to name."""
    missing = []
    if not line_includes_015(line):
        missing.append("015 (Data Classification)")
    if not line_includes_013(line):
        missing.append("013 (Flows)")
    return missing


def line_states_gate_scope(line: str) -> bool:
    """True iff `line` pins the gate-1 FIXTURE SCOPE — it names `:core/*` in
    gate-completion context (so it MUST name all four v1-required family roots,
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
            missing_eps = missing_required_eps(line)
            if not missing_eps:
                continue
            rel = path.relative_to(REPO_ROOT)
            problems.append(
                f"ORDER-DRIFT: {rel}:{lineno} states the foundation order / "
                "core-complete boundary but omits "
                f"{' and '.join(missing_eps)}. Both are v1-required: Spec 015 "
                "rides the 009 emission boundary, and Spec 013 (Flows) stands "
                "on every step before it — each MUST sit inside the "
                "foundation cluster ahead of the first completion gate "
                "(001 -> 002 -> 006 -> views -> 009 -> 015 -> 013 -> gate), "
                "never among the optional EPs. Add the missing step(s) to the "
                "sequence.\n"
                f"    {line.strip()}"
            )
    return problems, checked


def find_gate_drift(files: list[Path]) -> tuple[list[str], int]:
    """Return (drift messages, number-of-gate-scope-lines-checked).

    A gate-1 fixture-scope statement (see line_states_gate_scope) MUST name all
    four v1-required family roots — `:core/*`, `:identity/*`, `:flow/*`,
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
                "all four v1-required families (:core/* + :identity/* + "
                ":flow/* + :data-classification/*, per references/conformance.md "
                "§Capability tagging), not :core/* alone; :core/* alone silently "
                "skips the EP-0012 path/identity, Spec 013 flow and Spec 015 "
                "classification fixtures the skill calls mandatory. Name all "
                "four families.\n"
                f"    {line.strip()}"
            )
    return problems, checked


def find_witness_drift() -> tuple[list[str], int]:
    """Return (drift messages, number-of-witness-reference-lines-found).

    Every completion surface must reference the EP-006 live sub-cache witness,
    and the owner's definition must keep its observable elements (see
    WITNESS_ELEMENT_RES). Removing the requirement — or hollowing the
    definition — restores the rf2-3758j false-green, where a reference-keyed
    host reports v1 completion off canonical-identity fixtures alone."""
    problems: list[str] = []
    referenced = 0
    for path in WITNESS_REQUIRED_FILES:
        if not path.is_file():
            problems.append(
                f"SETUP: expected implementor-skill file missing: "
                f"{path.relative_to(REPO_ROOT)} — the witness scan's file list "
                "drifted from the skill layout; update WITNESS_REQUIRED_FILES."
            )
            continue
        hits = sum(
            1
            for line in _slurp(path).splitlines()
            if WITNESS_REF_RE.search(line)
        )
        if hits == 0:
            rel = path.relative_to(REPO_ROOT)
            problems.append(
                f"WITNESS-DRIFT: {rel} never references the EP-006 live "
                "sub-cache witness. The :identity/cedn1 cache-key fixtures "
                "prove canonical-identity only — the corpus subscribes each "
                "query once, so it cannot see a reference-keyed live cache "
                "(spec/006-ReactiveSubstrate.md §Value-keyed cache-key "
                "contract, conformance-observability note). Each completion "
                "surface must require the port-owned live witness before "
                "EP-006 / foundation / v1 completion is declared."
            )
        referenced += hits
    if WITNESS_OWNER_FILE.is_file():
        owner = _slurp(WITNESS_OWNER_FILE)
        for element, rx in WITNESS_ELEMENT_RES.items():
            if not rx.search(owner):
                problems.append(
                    f"WITNESS-DRIFT: "
                    f"{WITNESS_OWNER_FILE.relative_to(REPO_ROOT)} defines the "
                    f"live sub-cache witness without its `{element}` element. "
                    "The witness is only a proof while it observes one query "
                    "through two distinct host allocations resolving to one "
                    "cache-slot creation with exactly-once disposal, a non-rf= "
                    "negative control, and a score reported beside — never "
                    "inside — the corpus fraction. Restore the element."
                )
    return problems, referenced


def derive_normative_always_run() -> tuple[set[str], list[str]]:
    """Derive the always-run (v1-required) family roots from the NORMATIVE
    owner — spec/Implementor-Checklist.md Part 3's family table — rather than
    from the skill. This is the half of the cross-check a stale skill cannot
    satisfy by agreeing with a stale constant (rf2-k2r1).

    Returns (roots, SETUP problems). A parse that matches zero rows is a
    problem, never an empty-and-green answer: the table's shape changing must
    fail loud, exactly as the skill's own harness owes a non-vacuous-run
    floor."""
    if not NORMATIVE_OWNER_FILE.is_file():
        return set(), [
            f"SETUP: normative family owner missing: "
            f"{NORMATIVE_OWNER_FILE.relative_to(REPO_ROOT)} — the required-root "
            "derivation has no source; update NORMATIVE_OWNER_FILE."
        ]
    roots = {
        m.group(1) for m in ALWAYS_RUN_ROW_RE.finditer(_slurp(NORMATIVE_OWNER_FILE))
    }
    if not roots:
        return set(), [
            f"SETUP: {NORMATIVE_OWNER_FILE.relative_to(REPO_ROOT)} yielded ZERO "
            "always-run family rows. The Part 3 family table's shape has "
            "changed (its 'Gated by' cell no longer reads `nothing — always "
            "run`), so the required-root derivation is running vacuously. Fix "
            "ALWAYS_RUN_ROW_RE against the table as it now stands — do not "
            "treat an empty derivation as agreement."
        ]
    return roots, []


def verify_owner_declares_required_roots() -> list[str]:
    """Two-sided cross-check of the guard's REQUIRED_ROOT_RES constant.

    Side 1 — the SKILL's capability leaf (references/conformance.md) must still
    teach every required root, else the guard polices a family set the skill no
    longer describes.

    Side 2 — the NORMATIVE owner (spec/Implementor-Checklist.md Part 3) must
    agree with the constant EXACTLY, in both directions. Side 1 alone is
    circular: through the whole rf2-k2r1 false-green the skill and this guard
    agreed with each other that there were three required families, while the
    checklist and conformance README said `:flow/*` could not be declined.

    Returns SETUP problems (empty when constant, skill and spec agree)."""
    problems: list[str] = []
    normative, normative_problems = derive_normative_always_run()
    problems.extend(normative_problems)
    if normative:
        constant = set(REQUIRED_ROOT_RES)
        for root in sorted(normative - constant):
            problems.append(
                f"SETUP: {NORMATIVE_OWNER_FILE.relative_to(REPO_ROOT)} marks "
                f"{root} as always-run (v1-required), but this guard's "
                "REQUIRED_ROOT_RES omits it — so gate-1 statements naming only "
                "the other families would pass. Add it to REQUIRED_ROOT_RES "
                "and to the skill's gate-1 surfaces."
            )
        for root in sorted(constant - normative):
            problems.append(
                f"SETUP: this guard requires {root} at gate 1, but "
                f"{NORMATIVE_OWNER_FILE.relative_to(REPO_ROOT)} no longer marks "
                "it always-run. Reconcile REQUIRED_ROOT_RES with the normative "
                "family table before relaxing any skill surface."
            )
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
            "identity/flow/classification families as v1-required — the "
            "gate-scan premise (four v1-required families) has drifted from "
            "the owner."
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
    witness_problems, witness_refs = find_witness_drift()
    problems = order_problems + gate_problems + witness_problems

    if verbose:
        print(
            f"implementor foundation guard: scanned {len(SCANNED_FILES)} files, "
            f"found {order_checked} foundation-boundary statement(s), "
            f"{gate_checked} gate-1 fixture-scope statement(s), and "
            f"{witness_refs} live-witness reference(s) across "
            f"{len(WITNESS_REQUIRED_FILES)} completion surfaces."
        )

    if not problems:
        if verbose:
            print(
                "foundation guard: every foundation-boundary statement keeps "
                "Spec 015 and Spec 013 inside the core gate, every gate-1 "
                "fixture-scope statement names all four v1-required families "
                "(:core/* + :identity/* + :flow/* + :data-classification/*), "
                "and every completion surface requires the EP-006 live "
                "sub-cache witness with its observable elements intact."
            )
        return 0

    err_prefix = "::error::" if ci else ""
    for p in problems:
        print(f"{err_prefix}{p}")
    print(
        f"\nfoundation guard: {len(problems)} drift issue(s) "
        f"({len(order_problems)} order, {len(gate_problems)} gate, "
        f"{len(witness_problems)} witness). Spec 015 and Spec 013 are both "
        "v1-required and must sit inside the core gate; acceptance gate 1 must "
        "run all four v1-required families (:core/* + :identity/* + :flow/* + "
        ":data-classification/*), not :core/* alone; and completion requires "
        "the port-owned EP-006 live sub-cache witness, not canonical-identity "
        "fixtures alone."
    )
    return 1


# ---------------------------------------------------------------------------
# Self-test — exercises the line classifier against in-memory fixtures so the
# guard itself can't silently rot. Mirrors the --self-test convention in the
# sibling check_skill_*.py guards.
# ---------------------------------------------------------------------------

def _self_test() -> int:
    failures = 0

    def expect(
        line: str,
        *,
        boundary: bool,
        has015: bool,
        label: str,
        has013: bool | None = None,
    ) -> None:
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
        if boundary and has013 is not None:
            got_013 = line_includes_013(line)
            if got_013 != has013:
                print(
                    f"SELF-TEST FAIL ({label}): 013-presence "
                    f"expected {has013}, got {got_013} for: {line!r}"
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
        "001 → 002 → 006 → 004 → 009 → 015 → 013, then optional EPs per Phase 1 scope",
        boundary=True, has015=True, has013=True, label="E corrected SKILL arrow run",
    )
    expect(
        "the foundation cluster (001 / 002 / 006 / 004 / 009 / 015 / 013) and the optional EPs",
        boundary=True, has015=True, has013=True, label="F corrected cluster enumeration",
    )
    expect(
        "001 -> 002 -> 006 -> 004 -> 009 -> Data Classification -> 013 -> optional",
        boundary=True, has015=True, has013=True, label="G prose name instead of number",
    )

    # rf2-k2r1 FAIL fixtures — 015 is correctly ordered but 013 (Flows, equally
    # v1-required) has fallen out of the sequence back among the optional EPs.
    # This is the exact pre-fix shape: the order reads complete, and the port
    # ships no flow substrate.
    expect(
        "001 → 002 → 006 → views → 009 → 015 are the foundation; optional EPs sit downstream.",
        boundary=True, has015=True, has013=False,
        label="E2 015 present, 013 missing from the foundation run",
    )
    expect(
        "the foundation cluster (001 / 002 / 006 / views / 009 / 015) and the optional EPs",
        boundary=True, has015=True, has013=False,
        label="F2 cluster enumeration omitting 013",
    )
    # A bare spec-file citation must NOT satisfy the 013 requirement — only a
    # real sequence token does (citations are scrubbed before the test).
    expect(
        "001 → 002 → 006 → views → 009 → 015 are the foundation; flows live in "
        "[`spec/013-Flows.md`](https://day8.github.io/re-frame2/spec/013-Flows/) among the optional EPs",
        boundary=True, has015=True, has013=False,
        label="G2 013 citation alone is not a sequence mention",
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
        "Acceptance gate 1 (001 -> 002 -> 006 -> 004 -> 009 -> 015 -> 013): run "
        "every fixture applicable to `:core/*` + `:data-classification/*`."
    )
    expect(ordered_but_no_identity, boundary=True, has015=True, has013=True,
           label="O order is clean (015 + 013 present) — order scan must NOT flag")
    expect_gate(ordered_but_no_identity, gate=True, all_roots=False,
                label="O gate scan must flag (identity absent from gate 1)")
    expect_gate(
        "the first acceptance gate runs the `:core/*` + `:identity/*` fixtures",
        gate=True, all_roots=False, label="P classification family absent",
    )

    # rf2-k2r1 — the before/after pair. The three-family statement was the
    # CORRECT shape until Spec 013 joined the required set; it is now a
    # false-green (a port with no flow substrate clears it), so the gate scan
    # must flag it. Restoring `:flow/*` returns green.
    expect_gate(
        "Acceptance gate 1 — the required-foundation gate: run every fixture "
        "applicable to `:core/*` + `:identity/*` + `:data-classification/*`.",
        gate=True, all_roots=False,
        label="Q1 three-family gate is now incomplete (:flow/* absent)",
    )
    expect_gate(
        "Acceptance gate 1 — the required-foundation gate: run every fixture "
        "applicable to `:core/*` + `:identity/*` + `:flow/*` + "
        "`:data-classification/*`.",
        gate=True, all_roots=True, label="Q2 corrected four-family gate",
    )
    # The flavour the bead actually produced: flows conceded in prose while the
    # gate statement still runs the old three families.
    expect_gate(
        "Acceptance gate 1 green: every fixture applicable to `:core/*` + "
        "`:identity/*` + `:data-classification/*` at the pin (flows claimed "
        "separately per the profile).",
        gate=True, all_roots=False,
        label="Q3 flows named in prose does not discharge the gate scope",
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

    # -- EP-006 live sub-cache witness scan (rf2-3758j) --------------------
    def expect_witness_ref(line: str, *, ref: bool, label: str) -> None:
        nonlocal failures
        got = bool(WITNESS_REF_RE.search(line))
        if got != ref:
            print(
                f"SELF-TEST FAIL ({label}): witness-reference classification "
                f"expected {ref}, got {got} for: {line!r}"
            )
            failures += 1

    def expect_element(
        element: str, text: str, *, present: bool, label: str
    ) -> None:
        nonlocal failures
        got = bool(WITNESS_ELEMENT_RES[element].search(text))
        if got != present:
            print(
                f"SELF-TEST FAIL ({label}): element `{element}` presence "
                f"expected {present}, got {got} for: {text!r}"
            )
            failures += 1

    # Witness REFERENCES — the completion-surface shapes must match; the
    # pre-fix drift shape (calling the fixtures themselves "sub-cache
    # fixtures") shares the sub-cache token and must NOT.
    expect_witness_ref(
        "- [ ] EP-006 live sub-cache witness green "
        "([`references/phase-2-impl-order.md`](...)) — required whenever ...",
        ref=True, label="U Done-checklist witness item",
    )
    expect_witness_ref(
        "live cache wiring is proved by the [live sub-cache witness](#...) below",
        ref=True, label="V EP-006 row witness pointer",
    )
    expect_witness_ref(
        "`:core/sub`, plus the `:identity/cedn1` sub-cache fixtures",
        ref=False, label="W pre-fix fixture misnomer is not a witness reference",
    )
    expect_witness_ref(
        "each frame holds one sub-cache, keyed by the query vector",
        ref=False, label="X plain sub-cache prose is not a witness reference",
    )

    # Witness DEFINITION elements — each positive is the owner's contract
    # shape (markdown bold included); each negative shares surface tokens.
    expect_element(
        "two distinct host allocations",
        "Build `q1` and `q2` as two distinct host allocations of the same query value",
        present=True, label="Y1 allocations element present",
    )
    expect_element(
        "two distinct host allocations",
        "two distinct cache entries are created",
        present=False, label="Y2 distinct-entries prose is not the element",
    )
    expect_element(
        "exactly one cache-slot creation",
        "exactly **one** cache-slot creation, one derived container / first-run computation",
        present=True, label="Z1 one-slot element present (bold tolerated)",
    )
    expect_element(
        "exactly one cache-slot creation",
        "two query vectors share one cache key",
        present=False, label="Z2 one-cache-KEY prose is not the element",
    )
    expect_element(
        "exactly-once disposal",
        "the slot is removed and disposal fires **exactly once**",
        present=True, label="AA1 disposal element present (bold tolerated)",
    )
    expect_element(
        "exactly-once disposal",
        "under reference keying, disposal never fires",
        present=False, label="AA2 disposal-never-fires prose is not the element",
    )
    expect_element(
        "non-rf= negative control",
        "**Negative control.** A third query that is *not* `rf=` to `q1`",
        present=True, label="AB1 negative-control element present",
    )
    expect_element(
        "non-rf= negative control",
        "a control run against the reference implementation",
        present=False, label="AB2 generic control prose is not the element",
    )
    expect_element(
        "score honesty (beside, never inside/folded)",
        "**beside** the conformance score, never inside it",
        present=True, label="AC1 score-honesty element present",
    )
    expect_element(
        "score honesty (beside, never inside/folded)",
        "Reported beside the corpus score, never folded into it",
        present=True, label="AC2 score-honesty alternate phrasing present",
    )
    expect_element(
        "score honesty (beside, never inside/folded)",
        "the score is never below the fixture count",
        present=False, label="AC3 never-below prose is not the element",
    )

    # -- Normative required-root derivation (rf2-k2r1) ---------------------
    # These read spec/Implementor-Checklist.md, because the derivation IS the
    # unit under test: the point of the two-sided cross-check is that the
    # constant is answerable from the spec rather than from the skill.
    normative, normative_problems = derive_normative_always_run()
    for problem in normative_problems:
        print(f"SELF-TEST FAIL (AD normative derivation): {problem}")
        failures += 1
    if normative and normative != set(REQUIRED_ROOT_RES):
        print(
            "SELF-TEST FAIL (AE constant vs normative): the family table "
            f"derives {sorted(normative)} but REQUIRED_ROOT_RES holds "
            f"{sorted(REQUIRED_ROOT_RES)} — the guard and the spec disagree."
        )
        failures += 1
    if normative and ":flow/*" not in normative:
        print(
            "SELF-TEST FAIL (AF): spec/Implementor-Checklist.md no longer marks "
            ":flow/* as always-run — rf2-k2r1's premise has moved; re-read the "
            "family table before relaxing the skill."
        )
        failures += 1
    # The row regex keys on the exact `nothing — always run` cell. :derivation/*
    # reads "nothing declares it — but ... in practice Q1" and is gated in
    # practice, so it must NOT be pulled into the required set.
    if ALWAYS_RUN_ROW_RE.search(
        "| `:derivation/*` | nothing declares it — but **every current fixture "
        "is cross-tagged**, so in practice Q1 | Graph inspection. |"
    ):
        print(
            "SELF-TEST FAIL (AG): a 'nothing declares it' row was read as "
            "always-run; the derivation would over-claim the required set."
        )
        failures += 1
    if not ALWAYS_RUN_ROW_RE.search(
        "| `:flow/*` | nothing — always run | [013](013-Flows.md) is v1-required. |"
    ):
        print(
            "SELF-TEST FAIL (AH): a genuine always-run row failed to match; the "
            "derivation would run vacuously."
        )
        failures += 1

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
