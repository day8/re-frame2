#!/usr/bin/env python3
"""Authority guard: the re-frame.ui synthesis plan's S0-coverage disposition.

`ai/findings/new-substrate-synthesis/12-implementation-plan.md` is the
authoritative, handoff-ready program plan for the re-frame.ui substrate. Its
§4 handoff checklist carries a ONE-TIME, durable disposition — the "S0 COVERAGE
PASS (2026-07-12) — SHIP verdict" — whose authority lives in the epic
`rf2-vxgfnd` NOTES. That disposition does not drift: it records a review that
ran on a fixed date with a fixed verdict, plus the named implementer-question ->
bead mappings the coverage pass produced.

Two failure modes this guard pins (rf2-vs60jg / rf2-vxgfnd.235):

  1. MISSING / UNCHECKED S0 ANCHOR — the durable disposition is deleted, its
     checkbox flipped from `[x]` to `[ ]`, its epic `rf2-vxgfnd` authority
     attribution dropped, or one of the named question -> bead mappings removed
     so a fresh worker can no longer resolve it from the plan alone.

  2. REINTRODUCED UNQUALIFIED LIVE-STAGE CLAIM — a volatile progress snapshot
     (e.g. "S3-S7 not started") re-enters the plan. Live stage/progress state is
     owned by the epic and its beads, never by this plan; the only acceptable
     form of such a sentence here is one explicitly marked as a dated snapshot
     ("as of <date>", "historical", "snapshot").

This is a NARROW, single-anchor guard — not a general documentation framework
and not a Markdown parser. It pins a handful of literal durable tokens in one
named list item and forbids one drift-prone sentence shape. It has no live
Beads-API dependency (it reads the tracked plan file only).

Exit code:
    0  the plan's S0 authority is intact and no unqualified live-stage claim
    1  the anchor is missing/unchecked/incomplete, or a live-stage claim drifted
    2  invocation / setup error (repo root or plan file not found)

Dependency-light — Python stdlib only.
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

# The authoritative plan, relative to the repo root.
PLAN_REL = "ai/findings/new-substrate-synthesis/12-implementation-plan.md"

# --- Durable S0 anchor tokens -------------------------------------------------
# The disposition's stable name + date. Appears on the checked list-item line.
ANCHOR_TOKEN = "S0 COVERAGE PASS (2026-07-12)"
# The verdict word, also on the anchor line.
ANCHOR_VERDICT = "SHIP"
# The durable authority: the epic whose NOTES hold the coverage-pass record.
ANCHOR_AUTHORITY = "rf2-vxgfnd"

# A Markdown checklist item line. `box` captures the checkbox state (` ` or `x`).
_CHECKLIST_ITEM_RE = re.compile(r"^\s*-\s*\[(?P<box>[ xX])\]")

# The named implementer-question -> owning-bead mappings the coverage pass
# produced. Each (label, bead) pair MUST co-occur inside the S0 anchor item so a
# fresh worker can resolve every unresolved item from the plan alone. The
# `:activity-hidden` mapping is the one this guard's source bead (rf2-vs60jg)
# de-fuzzed from the non-identifying phrase "the S2 evidence/Xray slice" to its
# exact owner rf2-vxgfnd.8 (S2b ViewCell, per 03 §4).
REQUIRED_MAPPINGS: list[tuple[str, str]] = [
    ("Q49", "rf2-vxgfnd.9"),
    ("Q51", "rf2-vxgfnd.10"),
    ("[S2-CONFIRM]", "rf2-vxgfnd.7"),
    ("static-override-lease Tier-3", "rf2-vxgfnd.8"),
    (":activity-hidden", "rf2-vxgfnd.8"),
]

# --- Forbidden live-stage drift ----------------------------------------------
# A live implementation stage token (S1..S7). S0 is the paper stage and its
# "complete"/coverage disposition is durable, so S0 is deliberately excluded.
_LIVE_STAGE_TOKEN_RE = re.compile(r"\bS[1-7]\b")
# The canonical drift shape: asserting live stages are "(not yet) started".
_NOT_STARTED_RE = re.compile(r"not\s+(?:yet\s+)?started", re.IGNORECASE)
# A sentence carrying one of these qualifiers is an explicitly-dated historical
# snapshot, which the plan may keep; it does not read as current authority.
_SNAPSHOT_QUALIFIER_RE = re.compile(r"as[\s-]of|snapshot|historical", re.IGNORECASE)


def _plan_path(repo_root: Path) -> Path:
    return repo_root / PLAN_REL


def _find_anchor_block(lines: list[str]) -> tuple[str | None, str, int]:
    """Locate the S0 coverage-pass checklist item.

    Returns (box_char, block_text, line_no):
      * box_char  — the checkbox state of the anchor item ('x'/'X'/' '),
                    or None if no anchor item is present at all.
      * block_text — the anchor item joined with its wrapped continuation
                    lines (up to the next checklist item / section / EOF).
      * line_no   — 1-based line number of the anchor item (0 if absent).

    The anchor item is the checklist line that carries both ANCHOR_TOKEN and
    ANCHOR_VERDICT — a single, self-contained line by construction, so no
    Markdown parsing is required.
    """
    anchor_idx = None
    box_char: str | None = None
    for idx, raw in enumerate(lines):
        m = _CHECKLIST_ITEM_RE.match(raw)
        if not m:
            continue
        if ANCHOR_TOKEN in raw and ANCHOR_VERDICT in raw:
            anchor_idx = idx
            box_char = m.group("box").lower()
            break

    if anchor_idx is None:
        return None, "", 0

    # Extend to the wrapped continuation of this list item: subsequent lines
    # until the next checklist item, a new section heading, or EOF.
    block = [lines[anchor_idx]]
    for raw in lines[anchor_idx + 1:]:
        if _CHECKLIST_ITEM_RE.match(raw) or raw.startswith("## "):
            break
        block.append(raw)
    return box_char, "\n".join(block), anchor_idx + 1


def check(repo_root: Path, verbose: bool = False) -> int:
    """Validate the plan's S0 authority. Returns the defect count (0 == OK)."""
    plan = _plan_path(repo_root)
    if not plan.is_file():
        sys.stderr.write(f"error: no synthesis plan at {plan}\n")
        return 1

    text = plan.read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()

    if verbose:
        sys.stderr.write(
            f"checking S0 authority in {plan.relative_to(repo_root)} "
            f"({len(lines)} lines)\n"
        )

    defects: list[str] = []

    # (1) The durable S0 coverage-pass anchor must exist and be CHECKED.
    box_char, block, line_no = _find_anchor_block(lines)
    if box_char is None:
        defects.append(
            f"  MISSING S0 ANCHOR: no checked list item carrying "
            f'"{ANCHOR_TOKEN}" + "{ANCHOR_VERDICT}" was found. The one-time S0 '
            "coverage-pass disposition must remain a checked, named anchor."
        )
        block = ""  # nothing to check the mappings against
    elif box_char != "x":
        defects.append(
            f"  UNCHECKED S0 ANCHOR: line {line_no} carries the S0 "
            f'coverage-pass disposition but its checkbox is "[{box_char}]", '
            'not "[x]". The SHIP disposition is durable and must stay checked.'
        )

    # (2) The durable authority attribution must be intact.
    if block and ANCHOR_AUTHORITY not in block:
        defects.append(
            f"  MISSING AUTHORITY: the S0 anchor no longer references its "
            f'durable authority "{ANCHOR_AUTHORITY}" (the epic NOTES entry '
            f'"{ANCHOR_TOKEN}").'
        )

    # (3) Every named implementer-question -> bead mapping must co-occur inside
    #     the anchor item so it stays resolvable from the plan alone.
    if block:
        for label, bead in REQUIRED_MAPPINGS:
            if label not in block or bead not in block:
                defects.append(
                    f"  MISSING MAPPING: the named unresolved-item mapping "
                    f'"{label}" -> "{bead}" is not resolvable inside the S0 '
                    "anchor item."
                )

    # (4) No unqualified live-stage progress claim anywhere in the plan.
    for idx, raw in enumerate(lines, start=1):
        if (
            _NOT_STARTED_RE.search(raw)
            and _LIVE_STAGE_TOKEN_RE.search(raw)
            and not _SNAPSHOT_QUALIFIER_RE.search(raw)
        ):
            defects.append(
                f"  LIVE-STAGE DRIFT: line {idx} states an unqualified live-stage "
                f"progress claim: {raw.strip()!r}. Live stage/progress state is "
                "owned by the epic and its beads; remove it, source it from that "
                'durable authority, or mark it as a dated snapshot ("as of '
                '<date>").'
            )

    if defects:
        sys.stderr.write(
            f"\n{len(defects)} synthesis-plan authority defect(s) in "
            f"{plan.relative_to(repo_root)}:\n\n"
        )
        for line in defects:
            sys.stderr.write(line + "\n")
        sys.stderr.write(
            "\nFix: restore the checked "
            f'"{ANCHOR_TOKEN} — {ANCHOR_VERDICT}" anchor with its {ANCHOR_AUTHORITY} '
            "authority and the named question -> bead mappings intact, and keep "
            "volatile live-stage status out of the plan (the epic + its children "
            "own it). See the DURABLE ANCHOR comment in the plan.\n"
        )
    elif verbose:
        sys.stderr.write("S0 coverage-pass authority is intact.\n")

    return len(defects)


# --------------------------------------------------------------------------
# Self-tests — hermetic, generated into a temp dir (mirrors the style of
# scripts/check_ep_status_sync.py). Each fixture is a mini-repo: an mkdocs.yml
# at the root (so a repo-root guard would accept it) plus the plan file under
# ai/findings/new-substrate-synthesis/.
# --------------------------------------------------------------------------

# A minimal but faithful S0 anchor block — the shape the real plan carries.
_GOOD_ANCHOR = (
    "- [x] **S0 COVERAGE PASS (2026-07-12) — SHIP verdict** — durable "
    "disposition; authority: the epic `rf2-vxgfnd` NOTES entry "
    '"S0 COVERAGE PASS (2026-07-12)". Named mappings: Q49 → rf2-vxgfnd.9; '
    "Q51 → rf2-vxgfnd.10; the four [S2-CONFIRM] items → rf2-vxgfnd.7; the "
    "static-override-lease Tier-3 fixture → rf2-vxgfnd.8 / .12; the "
    "`:activity-hidden` retroactive-annotation evidence schema → rf2-vxgfnd.8 "
    "(S2b ViewCell, per 03 §4).\n"
)

_PLAN_HEAD = "# 12 — Implementation plan\n\n## 4. Handoff checklist\n\n"


def _write_fixture(root: Path, plan_body: str) -> None:
    plan = root / PLAN_REL
    plan.parent.mkdir(parents=True, exist_ok=True)
    (root / "mkdocs.yml").write_text("site_name: fixture\n", encoding="utf-8")
    plan.write_text(plan_body, encoding="utf-8")


def _build_self_test_fixtures(base: Path) -> None:
    # good: checked anchor, all mappings, no live-stage drift -> passes.
    _write_fixture(base / "good", _PLAN_HEAD + _GOOD_ANCHOR)

    # unchecked: the durable disposition's box flipped to [ ] -> fails.
    _write_fixture(
        base / "unchecked",
        _PLAN_HEAD + _GOOD_ANCHOR.replace("- [x]", "- [ ]", 1),
    )

    # missing_anchor: the whole S0 disposition deleted -> fails.
    _write_fixture(
        base / "missing_anchor",
        _PLAN_HEAD + "- [x] Some other unrelated checklist item.\n",
    )

    # missing_mapping: the :activity-hidden owner mapping stripped -> fails.
    _write_fixture(
        base / "missing_mapping",
        _PLAN_HEAD
        + _GOOD_ANCHOR.replace(
            "the `:activity-hidden` retroactive-annotation evidence schema "
            "→ rf2-vxgfnd.8 (S2b ViewCell, per 03 §4).",
            "the evidence schema landed with S2.",
        ),
    )

    # dropped_authority: the rf2-vxgfnd authority attribution removed -> fails.
    _write_fixture(
        base / "dropped_authority",
        _PLAN_HEAD + _GOOD_ANCHOR.replace("rf2-vxgfnd", "the-epic"),
    )

    # live_stage_drift: an unqualified "S3-S7 not started" claim reintroduced.
    _write_fixture(
        base / "live_stage_drift",
        _PLAN_HEAD
        + _GOOD_ANCHOR
        + "\nS1 is complete and S2 core is verified S3-ready. S3–S7 not started.\n",
    )

    # dated_snapshot: the same shape but explicitly dated as historical -> OK.
    _write_fixture(
        base / "dated_snapshot",
        _PLAN_HEAD
        + _GOOD_ANCHOR
        + "\nSnapshot as of 2026-07-12 (historical, not current): S3–S7 not "
        "started.\n",
    )


def _run_self_tests(verbose: bool = False) -> int:
    cases: list[tuple[str, int]] = [
        ("good", 0),
        ("unchecked", 1),
        ("missing_anchor", 1),
        ("missing_mapping", 1),
        ("dropped_authority", 1),
        ("live_stage_drift", 1),
        ("dated_snapshot", 0),
    ]
    failures = 0
    with tempfile.TemporaryDirectory(prefix="synthesis_plan_authority_selftest_") as tmp:
        base = Path(tmp)
        _build_self_test_fixtures(base)
        for fixture, expected_ok in cases:
            root = base / fixture
            saved_stderr = sys.stderr
            sys.stderr = _DevNull()
            try:
                defects = check(root, verbose=False)
            finally:
                sys.stderr = saved_stderr
            got_ok = 0 if defects == 0 else 1
            if got_ok == expected_ok:
                if verbose:
                    sys.stderr.write(
                        f"self-test PASS: {fixture} (defects={defects})\n"
                    )
            else:
                sys.stderr.write(
                    f"self-test FAIL: {fixture} expected "
                    f"{'clean' if expected_ok == 0 else 'defects'}, "
                    f"got defects={defects}\n"
                )
                failures += 1
    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    if verbose:
        sys.stderr.write(f"all {len(cases)} self-tests passed.\n")
    return 0


class _DevNull:
    def write(self, *_args, **_kwargs) -> int:
        return 0

    def flush(self) -> None:  # pragma: no cover
        return None


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Guard the re-frame.ui synthesis plan's S0 coverage-pass authority "
            "against status drift."
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

    defects = check(repo_root, verbose=args.verbose)
    return 0 if defects == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
