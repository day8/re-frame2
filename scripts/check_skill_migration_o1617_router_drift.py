#!/usr/bin/env python3
"""O-16 / O-17 router-leaf drift guard for the re-frame-migration skill.

The migration corpus (`migration/from-re-frame-v1/`) is the single source of
truth (SKILL.md cardinal rule 1; design.md L1). For the two v1 add-on
conversions, the **full** guides are owned once, in the corpus companions the
README's O-16 / O-17 sections point to:

  * O-16  migration/from-re-frame-v1/async-flow-fx-to-reg-machine.md
  * O-17  migration/from-re-frame-v1/http-fx-to-managed-http.md

The packaged skill leaves are meant to be **router leaves** — framing +
"load the author-pinned corpus" + spec/sibling links, nothing more:

  * skills/re-frame-migration/references/async-flow-to-machines.md
  * skills/re-frame-migration/references/http-fx-to-managed-http.md

Before this was enforced, both leaves had **re-grown into parallel full
guides** and drifted from the corpus — most sharply on the load-bearing
contract: the corpus companions claimed a retired add-on could *remain / still
load*, while the README + skill said it *fails to compile and must be
removed/replaced*. This guard makes that whole class a build failure.

Two symmetric assertions:

  ROUTER LEAVES (must stay thin + resolve):
    * LEAF-RESOLVE   — each leaf links to its corpus companion, and that
                       companion file exists on disk (the router points at a
                       real target).
    * SHADOW-GUIDE   — the leaf must NOT re-grow a full-guide section: no
                       `## Detection` / `## …mapping` / `## Worked` /
                       `## Before` / `## Escalat…` / `## Reporting` /
                       `## Out of scope` / `## Summary` heading, no fenced
                       ```clojure``` worked example, no mapping-table row.
    * LEAF-SIZE      — a router leaf stays under a small line cap (a coarse
                       "no shadow guide" backstop).
    * NO-TRACKER-ID  — no `rf2-…` bead id in the user-facing leaf prose.

  CORPUS COMPANIONS (must be the forced-framed full owners):
    * STALE-FRAMING  — the companion must NOT carry the superseded "can
                       remain / still loads" claim (`still loads`, `nothing in
                       re-frame2 breaks when a project keeps using it`, the
                       `## Why the rewrite is opt-in` heading). The corrected
                       framing states the add-on **fails to compile**.
    * FORCED-PRESENT — the companion asserts the add-on `fails to compile`
                       (the forced compile-gate framing the README carries).
    * OWNER-SECTIONS — the companion still owns the full guide: it carries a
                       `## Detection` and a `## Reporting` section and at least
                       one mapping table.

Scan surface: the two O-16 / O-17 skill leaves + the two corpus companions.
The corpus README already carries the forced framing and is validated by the
sibling `check_skill_migration_contract_drift.py`; design.md is a re-authoring
meta-doc and is deliberately out of scope (it *describes* the past drift).

Exit code:
    0  no drift detected
    1  drift detected (printed line-by-line; GitHub-Actions ::error:: under CI)
    2  invocation / setup error

Usage:
    python scripts/check_skill_migration_o1617_router_drift.py
    python scripts/check_skill_migration_o1617_router_drift.py --verbose
    python scripts/check_skill_migration_o1617_router_drift.py --ci
    python scripts/check_skill_migration_o1617_router_drift.py --self-test
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# Force UTF-8 on output streams — the corpus carries em-dash / arrows etc. and
# the default Windows console codec (cp1252) would crash on them (rf2 is
# maintained on Windows; the gate also runs on Linux CI).
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")  # type: ignore[union-attr]
    except (AttributeError, ValueError):  # pragma: no cover - non-TextIO stream
        pass

SKILL_REFERENCES = REPO_ROOT / "skills" / "re-frame-migration" / "references"
CORPUS_DIR = REPO_ROOT / "migration" / "from-re-frame-v1"

# leaf basename -> corpus-companion basename (the O-16 / O-17 pairing).
PAIRS: dict[str, str] = {
    "async-flow-to-machines.md": "async-flow-fx-to-reg-machine.md",
    "http-fx-to-managed-http.md": "http-fx-to-managed-http.md",
}

# A router leaf stays well under this line cap. The two leaves are ~19 lines
# each; 60 leaves head-room for a link footer without licensing a full guide.
MAX_LEAF_LINES = 60

# Full-guide H2 sections a router leaf must NOT carry (these belong to the
# corpus companion). Matched at the start of a heading line, case-insensitive.
# NOTE: the leaf's own `## Where the full … guide lives` router heading is
# deliberately NOT in this set.
SHADOW_HEADING_RE = re.compile(
    r"^#{2,3}\s+("
    r"detection"
    r"|summary"
    r"|construct mapping"
    r"|.*concept mapping"
    r"|mapping notes"
    r"|.*→.*"                       # a "X → Y concept mapping" heading
    r"|worked\b"
    r"|before\b"
    r"|escalat"
    r"|out of scope"
    r"|reporting"
    r"|the upgrades"
    r")",
    re.IGNORECASE,
)

CLOJURE_FENCE_RE = re.compile(r"^\s*```+\s*clojure\b", re.IGNORECASE)
# A markdown table data/separator row: at least two pipes framing a cell.
TABLE_ROW_RE = re.compile(r"^\s*\|.*\|.*\|")
# A bead id in user-facing prose (the stance ban). gh-NNNN upstream refs are a
# different class and are not matched here.
TRACKER_ID_RE = re.compile(r"\brf2-[0-9a-z]{4,}\b", re.IGNORECASE)

# Stale "can remain / still loads" claims the reconciliation removed from the
# companions. Any hit == the superseded framing crept back.
STALE_FRAMING_RES = (
    re.compile(r"still loads", re.IGNORECASE),
    re.compile(
        r"nothing in re-frame2 breaks when a project keeps using it",
        re.IGNORECASE,
    ),
    re.compile(r"^#{2,3}\s+why the rewrite is opt-in", re.IGNORECASE),
)
# The corrected forced-compile framing the companion MUST assert.
FORCED_PRESENT_RE = re.compile(r"fails to compile", re.IGNORECASE)


def _slurp(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _rel(path: Path) -> str:
    """Repo-relative posix path, falling back to the bare name for fixtures
    that live outside REPO_ROOT (the self-test tempdirs)."""
    try:
        return path.relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return path.name


def _fenced_line_flags(lines: list[str]) -> list[bool]:
    """Return a per-line 'inside a fenced code block' flag (delimiter lines
    included), so table-row / heading scans ignore fenced sample content."""
    flags: list[bool] = []
    in_fence = False
    marker = ""
    fence_open = re.compile(r"^\s*(```+|~~~+)")
    for line in lines:
        m = fence_open.match(line)
        if m:
            tok = m.group(1)
            if not in_fence:
                in_fence, marker = True, tok
            elif line.strip().startswith(marker):
                in_fence, marker = False, ""
            flags.append(True)
            continue
        flags.append(in_fence)
    return flags


def check_leaf(leaf: Path, companion_basename: str) -> list[str]:
    """Router-leaf assertions for one O-16 / O-17 skill leaf."""
    problems: list[str] = []
    rel = _rel(leaf)
    if not leaf.is_file():
        return [
            f"SETUP: expected router leaf missing: {rel} — the O-16 / O-17 "
            "layout drifted; update PAIRS."
        ]
    text = _slurp(leaf)
    lines = text.splitlines()

    # LEAF-RESOLVE — the leaf must reference the corpus companion path, and
    # that companion file must exist on disk.
    companion = CORPUS_DIR / companion_basename
    ref_token = f"migration/from-re-frame-v1/{companion_basename}"
    if ref_token not in text:
        problems.append(
            f"LEAF-RESOLVE: {rel} does not route to its corpus companion "
            f"`{ref_token}` — a router leaf must link to the full-guide owner."
        )
    if not companion.is_file():
        problems.append(
            f"LEAF-RESOLVE: {rel} routes to `{ref_token}`, but that corpus "
            "companion file does not exist — the router points at nothing."
        )

    # LEAF-SIZE — coarse "no shadow guide" backstop.
    if len(lines) > MAX_LEAF_LINES:
        problems.append(
            f"LEAF-SIZE: {rel} is {len(lines)} lines (> {MAX_LEAF_LINES}) — a "
            "router leaf must stay thin; the full guide lives in the corpus "
            "companion, not here."
        )

    fenced = _fenced_line_flags(lines)
    for i, line in enumerate(lines):
        lineno = i + 1
        # SHADOW-GUIDE — full-guide sections / worked examples / mapping tables.
        if not fenced[i] and SHADOW_HEADING_RE.match(line):
            problems.append(
                f"SHADOW-GUIDE: {rel}:{lineno} re-grows a full-guide section "
                f"heading — that content is owned by the corpus companion "
                f"`{companion_basename}`, not the router leaf.\n    {line.strip()}"
            )
        if CLOJURE_FENCE_RE.match(line):
            problems.append(
                f"SHADOW-GUIDE: {rel}:{lineno} carries a fenced ```clojure``` "
                "worked example — worked examples belong to the corpus "
                f"companion `{companion_basename}`."
            )
        if not fenced[i] and TABLE_ROW_RE.match(line):
            problems.append(
                f"SHADOW-GUIDE: {rel}:{lineno} carries a mapping-table row — "
                f"mapping tables belong to the corpus companion "
                f"`{companion_basename}`.\n    {line.strip()}"
            )
        # NO-TRACKER-ID — bead ids must not enter the user-facing leaf.
        if not fenced[i]:
            m = TRACKER_ID_RE.search(line)
            if m:
                problems.append(
                    f"NO-TRACKER-ID: {rel}:{lineno} carries a bead id "
                    f"`{m.group(0)}` — tracker ids must not appear in "
                    "user-facing skill prose.\n    " + line.strip()
                )
    return problems


def check_companion(companion: Path, leaf_basename: str) -> list[str]:
    """Full-owner assertions for one O-16 / O-17 corpus companion."""
    problems: list[str] = []
    rel = _rel(companion)
    if not companion.is_file():
        return [
            f"SETUP: expected corpus companion missing: {rel} — the sole full "
            "owner of the O-16 / O-17 guide is gone; update PAIRS."
        ]
    text = _slurp(companion)
    lines = text.splitlines()

    # STALE-FRAMING — the superseded "can remain / still loads" claim.
    for i, line in enumerate(lines):
        for rx in STALE_FRAMING_RES:
            if rx.search(line):
                problems.append(
                    f"STALE-FRAMING: {rel}:{i + 1} carries the superseded "
                    "'can remain / still loads' framing — the add-on FAILS TO "
                    "COMPILE on v2; removal-or-replacement is forced (align "
                    "with MIGRATION.md's O-16 / O-17 sections and the router "
                    f"leaf `{leaf_basename}`).\n    {line.strip()}"
                )

    # FORCED-PRESENT — the corrected forced-compile framing must be present.
    if not FORCED_PRESENT_RE.search(text):
        problems.append(
            f"FORCED-PRESENT: {rel} never states the add-on 'fails to compile' "
            "— the forced compile-gate framing (matching the README) is the "
            "reconciled contract and must be asserted here."
        )

    # OWNER-SECTIONS — the companion must still own the full guide.
    if not re.search(r"^#{2,3}\s+detection\b", text, re.IGNORECASE | re.MULTILINE):
        problems.append(
            f"OWNER-SECTIONS: {rel} has no `## Detection` section — the corpus "
            "companion is the sole full owner and must carry the guide."
        )
    if not re.search(r"^#{2,3}\s+reporting\b", text, re.IGNORECASE | re.MULTILINE):
        problems.append(
            f"OWNER-SECTIONS: {rel} has no `## Reporting` section — the corpus "
            "companion is the sole full owner and must carry the guide."
        )
    if not any(re.match(r"^\s*\|[-:\s|]+\|\s*$", ln) for ln in lines):
        problems.append(
            f"OWNER-SECTIONS: {rel} has no mapping table — the slot/construct "
            "mapping is a full-owner contract that lives here."
        )
    return problems


def find_drift() -> list[str]:
    problems: list[str] = []
    for leaf_name, companion_name in PAIRS.items():
        problems += check_leaf(SKILL_REFERENCES / leaf_name, companion_name)
        problems += check_companion(CORPUS_DIR / companion_name, leaf_name)
    return problems


def run(*, verbose: bool, ci: bool) -> int:
    if not SKILL_REFERENCES.is_dir():
        sys.stderr.write(f"error: skill references dir not found: {SKILL_REFERENCES}\n")
        return 2
    if not CORPUS_DIR.is_dir():
        sys.stderr.write(f"error: migration corpus dir not found: {CORPUS_DIR}\n")
        return 2

    problems = find_drift()

    if verbose:
        print(
            f"O-16 / O-17 router-leaf guard: checked {len(PAIRS)} leaf/companion "
            "pair(s)."
        )

    if not problems:
        if verbose:
            print(
                "router-leaf: O-16 / O-17 leaves are thin routers that resolve; "
                "companions are the forced-framed full owners."
            )
        return 0

    err_prefix = "::error::" if ci else ""
    for p in problems:
        print(f"{err_prefix}{p}")
    print(
        f"\nrouter-leaf: {len(problems)} drift issue(s) — the O-16 / O-17 skill "
        "leaves must stay thin routers into the corpus companions, and the "
        "companions must carry the forced 'fails to compile' framing (no "
        "'can remain / still loads' claim)."
    )
    return 1


# ---------------------------------------------------------------------------
# Self-test — exercises the leaf/companion classifiers against in-memory
# fixtures so the guard can't silently rot. Mirrors the --self-test convention
# in the sibling scripts/check_skill_*.py guards.
# ---------------------------------------------------------------------------

def _self_test() -> int:
    failures = 0

    def expect_leaf(text: str, *, dirty: bool, label: str) -> None:
        nonlocal failures
        import tempfile

        with tempfile.TemporaryDirectory() as td:
            # Point CORPUS_DIR check at a real existing companion by writing one.
            comp = Path(td) / "async-flow-fx-to-reg-machine.md"
            comp.write_text("# c\n", encoding="utf-8")
            leaf = Path(td) / "async-flow-to-machines.md"
            leaf.write_text(text, encoding="utf-8")
            # Temporarily point module globals at the fixture dirs.
            global SKILL_REFERENCES, CORPUS_DIR
            saved_ref, saved_corpus = SKILL_REFERENCES, CORPUS_DIR
            SKILL_REFERENCES, CORPUS_DIR = Path(td), Path(td)
            try:
                got = bool(
                    check_leaf(leaf, "async-flow-fx-to-reg-machine.md")
                )
            finally:
                SKILL_REFERENCES, CORPUS_DIR = saved_ref, saved_corpus
        if got != dirty:
            print(f"SELF-TEST FAIL ({label}): expected dirty={dirty}, got {got}")
            failures += 1

    def expect_companion(text: str, *, dirty: bool, label: str) -> None:
        nonlocal failures
        import tempfile

        with tempfile.TemporaryDirectory() as td:
            comp = Path(td) / "async-flow-fx-to-reg-machine.md"
            comp.write_text(text, encoding="utf-8")
            got = bool(check_companion(comp, "async-flow-to-machines.md"))
        if got != dirty:
            print(f"SELF-TEST FAIL ({label}): expected dirty={dirty}, got {got}")
            failures += 1

    # A clean router leaf (thin, routes, no shadow sections, no tracker id).
    clean_leaf = (
        "# O-16 — translate `async-flow-fx` to state machines\n\n"
        "Intro prose. `reg-machine` is the successor.\n\n"
        "> **Forced, not optional.** Fails to compile; convert or remove.\n\n"
        "## Where the full O-16 guide lives\n\n"
        "The full guide lives in "
        "[`async-flow-fx-to-reg-machine.md`]"
        "(../../../migration/from-re-frame-v1/async-flow-fx-to-reg-machine.md).\n"
    )
    expect_leaf(clean_leaf, dirty=False, label="LEAF clean router")

    # Shadow guide — a re-grown `## Detection` section.
    expect_leaf(
        clean_leaf + "\n## Detection\n\nGrep for the coord.\n",
        dirty=True,
        label="LEAF shadow ## Detection",
    )
    # Shadow guide — a mapping table row.
    expect_leaf(
        clean_leaf + "\n| a | b | c |\n",
        dirty=True,
        label="LEAF mapping-table row",
    )
    # Shadow guide — a fenced clojure worked example.
    expect_leaf(
        clean_leaf + "\n```clojure\n(rf/reg-machine :x {})\n```\n",
        dirty=True,
        label="LEAF clojure fence",
    )
    # Tracker id in leaf prose.
    expect_leaf(
        clean_leaf + "\nSee rf2-abcde for context.\n",
        dirty=True,
        label="LEAF tracker id",
    )
    # Missing corpus-companion route.
    expect_leaf(
        "# O-16\n\nNo route here.\n",
        dirty=True,
        label="LEAF no route",
    )

    # A clean, forced-framed full owner.
    clean_companion = (
        "# O-16. Convert async-flow-fx flows to reg-machine\n\n"
        "## Acting is forced; the conversion path is the opt-in part\n\n"
        "It **fails to compile** the moment re-frame2 is on the classpath.\n\n"
        "## Detection\n\nGrep the coord.\n\n"
        "| a | b |\n|---|---|\n| x | y |\n\n"
        "## Reporting\n\nList the sites.\n"
    )
    expect_companion(clean_companion, dirty=False, label="COMPANION clean owner")

    # Stale framing — the 'still loads' claim.
    expect_companion(
        clean_companion.replace(
            "It **fails to compile**",
            "the `:async-flow` fx still loads, so it **fails to compile**",
        ),
        dirty=True,
        label="COMPANION still-loads",
    )
    # Stale framing — the old opt-in heading.
    expect_companion(
        clean_companion.replace(
            "## Acting is forced; the conversion path is the opt-in part",
            "## Why the rewrite is opt-in",
        ),
        dirty=True,
        label="COMPANION opt-in heading",
    )
    # Missing forced framing.
    expect_companion(
        clean_companion.replace("It **fails to compile** the moment re-frame2 is on the classpath.", "It is superseded."),
        dirty=True,
        label="COMPANION no forced framing",
    )
    # Missing owner section (no Reporting).
    expect_companion(
        clean_companion.replace("## Reporting\n\nList the sites.\n", ""),
        dirty=True,
        label="COMPANION no Reporting",
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
