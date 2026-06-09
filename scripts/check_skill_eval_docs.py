#!/usr/bin/env python3
"""Eval-docs drift gate: `skills/re-frame2/evals/README.md` vs `evals.json` (rf2-r2xswa).

The eval harness README carries a human-readable coverage table, a total
eval count, and a per-dimension breakdown. Those are hand-maintained prose
that silently fell behind the JSON once before (a seventh eval —
`recipe-correctness-story-recorder-sensitive-login` — landed in `evals.json`
while the README still said "Six evals … two evals per dimension"). A stale
coverage table makes the validation story less trustworthy than the skill it
guards: a maintainer can miss an eval, misread the dimension balance, or
trust a count that no longer holds.

This gate makes that drift impossible to ship. It parses `evals.json` as the
source of truth and asserts the README agrees on three axes:

  A1  TOTAL COUNT      — the README's "<N> evals" sentence matches
                         `len(evals)`.
  A2  EVAL NAMES       — every eval `name` in the JSON appears in the README
                         coverage table, and the table names no eval the JSON
                         lacks (set equality). IDs are matched too so a row
                         can't point at the wrong id.
  A3  DIMENSION TALLY  — the README's per-dimension breakdown sentence states
                         the same counts the JSON produces (e.g. "three
                         recipe-correctness … two each for discovery and
                         routing-correctness"). Counts are read as
                         number-words OR digits.

The gate is pure-Python-stdlib (no PyYAML / Node) to stay fast and
CI-portable, mirroring the sibling `scripts/check_skill_*.py` gates. It does
NOT validate `evals.json` schema beyond what it needs (id / name / dimension)
— that is the harness runner's job.

Exit code:
    0  no drift
    1  drift detected (printed; `::error::` under CI)
    2  invocation / setup error

Usage:
    python scripts/check_skill_eval_docs.py
    python scripts/check_skill_eval_docs.py --verbose
    python scripts/check_skill_eval_docs.py --ci          # CI-shaped
    python scripts/check_skill_eval_docs.py --self-test    # built-in fixtures

rf2-r2xswa (finding 3).
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Iterable

REPO_ROOT = Path(__file__).resolve().parent.parent
EVALS_DIR = REPO_ROOT / "skills" / "re-frame2" / "evals"
EVALS_JSON = EVALS_DIR / "evals.json"
EVALS_README = EVALS_DIR / "README.md"

# Force UTF-8 on output streams — the corpus carries → / em-dash etc. and the
# default Windows console codec (cp1252) would crash on them.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")  # type: ignore[union-attr]
    except (AttributeError, ValueError):  # pragma: no cover
        pass

# Number-words 0..20 (the eval counts live well within this range). Used for
# both the total-count and the per-dimension-tally axes.
_WORD_TO_INT = {
    "zero": 0, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5,
    "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10, "eleven": 11,
    "twelve": 12, "thirteen": 13, "fourteen": 14, "fifteen": 15,
    "sixteen": 16, "seventeen": 17, "eighteen": 18, "nineteen": 19,
    "twenty": 20,
}
_INT_TO_WORD = {v: k for k, v in _WORD_TO_INT.items()}


def _count_token(n: int) -> str:
    """Regex alternation matching either the digit or the number-word for n."""
    word = _INT_TO_WORD.get(n)
    if word is None:
        return str(n)
    return rf"(?:{n}|{word})"


# ---------------------------------------------------------------------------
# JSON source-of-truth extraction.
# ---------------------------------------------------------------------------


def load_evals(path: Path) -> list[dict]:
    data = json.loads(path.read_text(encoding="utf-8"))
    evals = data.get("evals")
    if not isinstance(evals, list):
        raise ValueError(f"{path}: top-level 'evals' is not a list")
    return evals


# ---------------------------------------------------------------------------
# README parsing.
# ---------------------------------------------------------------------------

# Coverage-table rows: `| 7 | \`recipe-correctness-story-recorder-...\` | ... |`.
# Capture the id (first cell) and the name (second cell, backtick-wrapped).
_TABLE_ROW_RE = re.compile(
    r"^\|\s*(\d+)\s*\|\s*`([^`]+)`\s*\|", re.MULTILINE
)


def parse_readme_table(text: str) -> dict[int, str]:
    """Return {id: name} for every coverage-table row found in the README."""
    rows: dict[int, str] = {}
    for m in _TABLE_ROW_RE.finditer(text):
        rows[int(m.group(1))] = m.group(2)
    return rows


def find_total_count(text: str) -> int | None:
    """Pull the README's '<N> evals, covering ...' total-count assertion."""
    m = re.search(
        r"\b([A-Za-z]+|\d+)\s+evals,\s+covering",
        text,
    )
    if not m:
        return None
    tok = m.group(1).lower()
    if tok.isdigit():
        return int(tok)
    return _WORD_TO_INT.get(tok)


def dimension_tally(evals: list[dict]) -> Counter:
    return Counter(e.get("dimension", "?") for e in evals)


def _strip_table_rows(text: str) -> str:
    """Drop markdown table rows so the per-dimension PROSE check doesn't read
    a coverage-table row (e.g. `| 2 | … | discovery | …`) as the count
    statement. A row is any line whose first non-space char is `|`.
    """
    return "\n".join(
        ln for ln in text.splitlines() if not ln.lstrip().startswith("|")
    )


def check_dimension_sentence(text: str, tally: Counter) -> list[str]:
    """Return a list of human-readable problems with the per-dimension prose.

    For each dimension present in the JSON, require the README to state its
    count adjacent to the dimension name (digit or number-word). Phrasing is
    free as long as `<count> ... <dimension>` co-occurs within a short window.
    The coverage TABLE is stripped first — its rows carry both an id digit and
    a dimension name and would otherwise satisfy this check vacuously.
    """
    problems: list[str] = []
    low = _strip_table_rows(text).lower()
    for dim, n in sorted(tally.items()):
        tok = _count_token(n)
        # `<count>` then up to ~40 chars then the dimension name. The window
        # absorbs phrasing like "three recipe-correctness evals" and
        # "two each for discovery and routing-correctness".
        pat = re.compile(rf"{tok}\b[^.]{{0,60}}?{re.escape(dim)}", re.IGNORECASE)
        if not pat.search(low):
            problems.append(
                f"dimension '{dim}' has {n} eval(s) in evals.json but the "
                f"README's per-dimension breakdown does not state a count of "
                f"{n} for it (looked for '{n}'/'{_INT_TO_WORD.get(n, n)}' "
                f"near '{dim}')"
            )
    return problems


# ---------------------------------------------------------------------------
# Cross-check.
# ---------------------------------------------------------------------------


def check(evals_json: Path, readme: Path) -> list[str]:
    """Return a list of drift findings (empty == clean)."""
    findings: list[str] = []
    evals = load_evals(evals_json)
    text = readme.read_text(encoding="utf-8")

    # A1 — total count.
    json_total = len(evals)
    readme_total = find_total_count(text)
    if readme_total is None:
        findings.append(
            "A1 total-count: could not find the README's '<N> evals, "
            "covering …' sentence — the gate cannot verify the count."
        )
    elif readme_total != json_total:
        findings.append(
            f"A1 total-count: README says {readme_total} evals but "
            f"evals.json has {json_total}."
        )

    # A2 — eval names (and ids) set-equality between table and JSON.
    json_by_id = {e.get("id"): e.get("name") for e in evals}
    table = parse_readme_table(text)
    json_names = {e.get("name") for e in evals}
    table_names = set(table.values())

    for missing in sorted(json_names - table_names):
        findings.append(
            f"A2 names: eval '{missing}' is in evals.json but absent from the "
            f"README coverage table."
        )
    for extra in sorted(table_names - json_names):
        findings.append(
            f"A2 names: README coverage table lists '{extra}' but evals.json "
            f"has no eval by that name."
        )
    # id↔name agreement for rows whose id exists in both.
    for rid, rname in sorted(table.items()):
        jname = json_by_id.get(rid)
        if jname is not None and jname != rname:
            findings.append(
                f"A2 ids: README row id={rid} names '{rname}' but evals.json "
                f"id={rid} is '{jname}'."
            )

    # A3 — dimension tally.
    findings.extend(
        f"A3 dimension: {p}" for p in check_dimension_sentence(text, dimension_tally(evals))
    )

    return findings


# ---------------------------------------------------------------------------
# Self-test — synthetic README/JSON pairs exercising each axis.
# ---------------------------------------------------------------------------


def _run_self_test() -> int:
    import tempfile

    failures = 0

    good_json = {
        "evals": [
            {"id": 1, "name": "a-disc", "dimension": "discovery"},
            {"id": 2, "name": "b-disc", "dimension": "discovery"},
            {"id": 3, "name": "c-recipe", "dimension": "recipe-correctness"},
        ]
    }
    good_readme = (
        "## Coverage\n\nThree evals, covering the dimensions:\n\n"
        "| ID | Name | Dimension | What |\n|---:|---|---|---|\n"
        "| 1 | `a-disc` | discovery | x |\n"
        "| 2 | `b-disc` | discovery | y |\n"
        "| 3 | `c-recipe` | recipe-correctness | z |\n\n"
        "Two discovery evals and one recipe-correctness eval.\n"
    )

    def run(jobj: dict, rtext: str) -> list[str]:
        with tempfile.TemporaryDirectory() as td:
            jp = Path(td) / "evals.json"
            rp = Path(td) / "README.md"
            jp.write_text(json.dumps(jobj), encoding="utf-8")
            rp.write_text(rtext, encoding="utf-8")
            return check(jp, rp)

    cases: list[tuple[str, dict, str, bool]] = [
        ("clean pair", good_json, good_readme, True),
        # Stale total count.
        ("bad total", good_json,
         good_readme.replace("Three evals", "Four evals"), False),
        # Missing eval name in table (drop the recipe row).
        ("missing name", good_json,
         good_readme.replace("| 3 | `c-recipe` | recipe-correctness | z |\n", ""), False),
        # Phantom name in table.
        ("phantom name", good_json,
         good_readme.replace("| 3 | `c-recipe`", "| 3 | `c-ghost`"), False),
        # Wrong dimension tally (claim one discovery eval).
        ("bad tally", good_json,
         good_readme.replace("Two discovery evals", "One discovery eval"), False),
    ]

    for label, jobj, rtext, want_clean in cases:
        findings = run(jobj, rtext)
        is_clean = not findings
        if is_clean != want_clean:
            failures += 1
            print(
                f"SELF-TEST FAIL: {label!r} expected "
                f"{'clean' if want_clean else 'drift'}, got "
                f"{'clean' if is_clean else findings}"
            )

    if failures:
        print(f"\n{failures} self-test failure(s).")
        return 1
    print("self-test: all fixtures pass.")
    return 0


# ---------------------------------------------------------------------------
# CLI.
# ---------------------------------------------------------------------------


def _is_ci() -> bool:
    return os.environ.get("GITHUB_ACTIONS") == "true"


def main(argv: Iterable[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Verify skills/re-frame2/evals/README.md coverage table, total "
            "count, and per-dimension breakdown agree with evals.json "
            "(rf2-r2xswa)."
        ),
    )
    parser.add_argument("--verbose", "-v", action="store_true")
    parser.add_argument("--ci", action="store_true",
                        help="Emit GitHub-Actions ::error:: lines (auto-on under GITHUB_ACTIONS).")
    parser.add_argument("--self-test", action="store_true",
                        help="Run built-in fixtures and exit.")
    args = parser.parse_args(list(argv))

    if args.self_test:
        return _run_self_test()

    ci = args.ci or _is_ci()

    for p in (EVALS_JSON, EVALS_README):
        if not p.is_file():
            msg = f"required file not found: {p}"
            print(f"::error::{msg}" if ci else f"ERROR: {msg}", file=sys.stderr)
            return 2

    try:
        findings = check(EVALS_JSON, EVALS_README)
    except (ValueError, json.JSONDecodeError) as e:
        msg = f"failed to parse evals.json: {e}"
        print(f"::error::{msg}" if ci else f"ERROR: {msg}", file=sys.stderr)
        return 2

    if findings:
        print(
            f"Eval-docs drift detected ({len(findings)} "
            f"finding{'' if len(findings) == 1 else 's'}):",
            file=sys.stderr,
        )
        for f in findings:
            print(f"::error::{f}" if ci else f"ERROR: {f}", file=sys.stderr)
        print(
            "\nFix: update skills/re-frame2/evals/README.md (coverage table, "
            "the '<N> evals' count, and the per-dimension breakdown) to match "
            "skills/re-frame2/evals/evals.json. (rf2-r2xswa)",
            file=sys.stderr,
        )
        return 1

    if args.verbose:
        print("No eval-docs drift detected.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
