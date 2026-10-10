#!/usr/bin/env python3
"""Foundation-order drift guard: re-frame2-implementor skill must keep Spec 015
inside the core-complete gate.

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
frame-owned egress policy; there is no imperative `add-marks` / `set-marks` path
API on the public facade). It rides the 009 emission boundary, so it
MUST sit inside the foundation cluster — ahead of the `:core/*` gate, NOT among the
optional EPs. The drift this guards against: some entry points read
`001 -> 002 -> 006 -> 004 -> 009 -> 015 -> gate` while others read
`001 -> 002 -> 006 -> 004 -> 009 -> optional`, placing the first `:core/*` gate
BEFORE Data Classification. A fresh session using the kickoff prompt — or a
maintainer reading the stale leaf — could then ship or declare
"v1-core-complete" without the required privacy/large-payload elision surface,
leaking marked data through observability.

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

Second scan — the **required-foundation gate**.
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

`:flow/*` is in that set because the checklist makes Flows a NON-gated
Required row, so a port cannot opt out of Spec 013. A skill that treats flows
as a "skill-local optional" capability lets a minimum port put `:flow/*` on
`known-skipped`, keep both gates green over the smaller claim, and report
itself v1-complete with no flow substrate.

**The cross-check is deliberately two-sided, because a one-sided one cannot see
that class of drift.** Checking the constant against the SKILL's own capability
leaf (`references/conformance.md`) proves only that the skill and this guard
agree — and both can omit `:flow/*` together, through the whole false-green.
So the required roots are ALSO derived from the NORMATIVE owner:
`spec/Implementor-Checklist.md` Part 3's family table, whose "always run" rows
are the contract. The derived set must equal this guard's constant exactly, in
both directions, and a derivation that matches zero rows is a SETUP failure
rather than a vacuous pass.

Third scan — the **EP-006 live sub-cache witness**. The corpus's two
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
# EP 013 (Flows) is v1-required too and closes the foundation
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
# Required-foundation gate scan.
# ---------------------------------------------------------------------------

# Two owners, deliberately. The skill's capability leaf is where a
# port author READS the family set; `spec/Implementor-Checklist.md` Part 3 is
# where the project DECIDES it. Cross-checking the constant against the skill
# alone proves only that the skill and this guard agree — and a family can go
# missing from both at once. So the constant is checked against the
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
# EP-006 live sub-cache witness scan.
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

# A line REFERENCES the witness when it names it. The misnomer shape —
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
    definition — reopens the false-green where a reference-keyed host
    reports v1 completion off canonical-identity fixtures alone."""
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
    satisfy by agreeing with a stale constant.

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
    circular: the skill and this guard can agree with each other that there
    are three required families while the checklist and conformance README
    say `:flow/*` cannot be declined.

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
# Self-test — the line classifiers against known shapes.
# ---------------------------------------------------------------------------

def _self_test() -> int:
    def order(line: str):
        """(has 015, has 013) for a foundation-boundary line, else False."""
        return line_states_foundation_boundary(line) and (
            line_includes_015(line), line_includes_013(line))

    def gate(line: str):
        """The missing roots of a gate-1 scope line, else False."""
        return line_states_gate_scope(line) and missing_required_roots(line)

    cases = (
        ("A arrow run omitting 015 and 013", order(
            "3. Implement in dependency order: 001 -> 002 -> 006 -> 004 -> 009 -> optional."),
         (False, False)),
        ("F2 cluster enumeration omitting 013", order(
            "the foundation cluster (001 / 002 / 006 / views / 009 / 015) and the optional EPs"),
         (True, False)),
        ("G prose name instead of number", order(
            "001 -> 002 -> 006 -> 004 -> 009 -> Data Classification -> 013 -> optional"),
         (True, True)),
        ("G2 013 citation alone is not a sequence mention", order(
            "001 → 002 → 006 → views → 009 → 015 are the foundation; flows live in "
            "[`spec/013-Flows.md`](https://day8.github.io/re-frame2/spec/013-Flows/) "
            "among the optional EPs"),
         (True, False)),
        ("K prose rationale, no sequence arrow", order(
            "009 is in the foundation because `:core/trace` and `:core/error` fixtures exercise it."),
         False),
        ("L impl-tour file map (no boundary cue)", order(
            "the public API + the heart of EP 001 / 002 / 009; also core/src/..."),
         False),
        ("Q1 three-family gate is incomplete", gate(
            "Acceptance gate 1 — the required-foundation gate: run every fixture "
            "applicable to `:core/*` + `:identity/*` + `:data-classification/*`."),
         [":flow/*"]),
        ("T gate-2 line (gate-1 cue absent)", gate(
            "gate 2 runs the full claimed-capability set, a superset of `:core/*`"),
         False),
        ("W fixture misnomer is not a witness reference", bool(WITNESS_REF_RE.search(
            "`:core/sub`, plus the `:identity/cedn1` sub-cache fixtures")),
         False),
    )
    failures = [(label, got, want) for label, got, want in cases if got != want]
    for label, got, want in failures:
        print(f"SELF-TEST FAIL ({label}): expected {want!r}, got {got!r}")
    if failures:
        print(f"self-test: {len(failures)} failure(s).")
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
