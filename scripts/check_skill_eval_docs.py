#!/usr/bin/env python3
"""Eval-docs drift gate: a packaged skill's `evals/README.md` vs its `evals.json`.

The eval harness README of a skill carries a human-readable coverage table, a
total eval count, and one or more per-axis tallies (per-dimension, per-kind,
or over a boolean field). Those are hand-maintained prose that silently falls
behind the JSON: an eval lands in `evals.json` while the README still states
the old total and the old per-dimension tally, or a tally keeps the old number
and silently drops a newly added guard.

A stale coverage table makes the validation story less trustworthy than the
skill it guards: a maintainer can miss an eval, misread the dimension balance,
or trust a count that no longer holds; a re-authoring pass fed the stale
authoring prompt would under-weight the missing fixtures.

This gate makes that drift impossible to ship. It is **multi-target**: a
`TARGETS` registry lists every packaged skill whose `evals/README.md` carries a
coverage table, and for each one it parses `evals.json` as the source of truth
and asserts the README agrees on three axes:

  A1  TOTAL COUNT      — the README's total-count sentence matches the number
                         of evals the JSON declares.
  A2  EVAL NAMES       — every eval that the target expects in the coverage
                         table appears there, the table names no eval the JSON
                         lacks (set equality), and each table row's id matches
                         the JSON id for that name.
  A3  TALLIES          — every tally axis the target declares (per-dimension,
                         per-kind, or a boolean field) states, in README
                         prose, the same counts the JSON produces. Counts are
                         read as number-words OR digits.

Beyond the per-target README↔JSON axes above, the gate also enforces one
corpus-wide structural invariant over EVERY skill's evals.json (not only the
doc-table targets):

  A4  IDENTITY UNIQUE   — within one evals.json, every `name` slug and every
                         `id` is unique. Both key per-fixture identity (the
                         schema's unique `id`, and the `name` slug used as the
                         per-run directory name), so a duplicate silently
                         collides per-run directories / name-keyed reports and
                         hides the intended scenario distinction.

Each target declares its own conventions (which evals appear in the table, how
its total-count sentence reads, which tally axes it asserts), so harnesses with
different README shapes are all gated correctly. The `re-frame2` target uses
single-axis (per-dimension) semantics; the `re-frame2-xray`
target tabulates only its Layer-2 answer-quality evals (those carrying
`expectations[]`) and tallies a boolean `should_trigger` axis rendered to prose
(positives / negatives).

The `re-frame2-improver` harness is intentionally NOT a doc-table target: its
`evals/README.md` carries no coverage table, total count, or per-axis
tallies — `evals.json` is that skill's sole fixture inventory, so there is no
coverage prose that could drift against the JSON. Its evals.json is covered
corpus-wide by the A4 identity-uniqueness invariant below. The gate carries
the multi-axis (per-kind + per-behavioural-dimension) machinery a two-kind
harness needs for any harness with that README shape.

The gate is pure-Python-stdlib (no PyYAML / Node) to stay fast and
CI-portable, mirroring the sibling `scripts/check_skill_*.py` gates. It does
NOT validate `evals.json` schema beyond what it needs (id / name / kind /
dimension) — that is the harness runner's job.

Exit code:
    0  no drift across all targets
    1  drift detected (printed; `::error::` under CI)
    2  invocation / setup error

Usage:
    python scripts/check_skill_eval_docs.py
    python scripts/check_skill_eval_docs.py --verbose
    python scripts/check_skill_eval_docs.py --ci          # CI-shaped
    python scripts/check_skill_eval_docs.py --self-test    # built-in fixtures
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Iterable

REPO_ROOT = Path(__file__).resolve().parent.parent
SKILLS_DIR = REPO_ROOT / "skills"

# Force UTF-8 on output streams — the corpus carries → / em-dash etc. and the
# default Windows console codec (cp1252) would crash on them.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")  # type: ignore[union-attr]
    except (AttributeError, ValueError):  # pragma: no cover
        pass

# Number-words 0..30 (the eval counts live well within this range). Used for
# both the total-count and the tally axes.
_WORD_TO_INT = {
    "zero": 0, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5,
    "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10, "eleven": 11,
    "twelve": 12, "thirteen": 13, "fourteen": 14, "fifteen": 15,
    "sixteen": 16, "seventeen": 17, "eighteen": 18, "nineteen": 19,
    "twenty": 20, "twenty-one": 21, "twenty-two": 22, "twenty-three": 23,
    "twenty-four": 24, "twenty-five": 25, "twenty-six": 26,
    "twenty-seven": 27, "twenty-eight": 28, "twenty-nine": 29, "thirty": 30,
}
_INT_TO_WORD = {v: k for k, v in _WORD_TO_INT.items()}


def _count_token(n: int) -> str:
    """Regex alternation matching either the digit or the number-word for n."""
    word = _INT_TO_WORD.get(n)
    if word is None:
        return str(n)
    return rf"(?:{n}|{re.escape(word)})"


# ---------------------------------------------------------------------------
# Target configuration.
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class TallyAxis:
    """One per-axis count breakdown the README must state in prose.

    `field_name` is the eval field this axis tallies (e.g. "dimension",
    "kind", or a boolean like "should_trigger"). `eval_filter`, if given,
    restricts the tally to a subset of evals (e.g. only the behavioural evals
    for the improver's dimension axis). The axis is matched against README
    prose as `<count> … <item-name>` within a short window; the coverage TABLE
    is stripped first so a table row (which carries a digit id AND a
    dimension/kind name) cannot satisfy the prose check vacuously.

    `value_label`, if given, maps a raw axis value to the noun the README uses
    for it — needed when the field's raw values are not themselves the prose
    item (e.g. a boolean `should_trigger`: `True`→"positive", `False`→
    "negative", so the gate looks for "21 … positive" not "21 … True"). Values
    absent from the map fall back to their stringified form.
    """

    field_name: str
    label: str = ""
    eval_filter: Callable[[dict], bool] | None = None
    # Items intentionally NOT asserted in prose (e.g. an axis value with no
    # narrative count sentence). Empty == assert every item the JSON produces.
    skip_items: frozenset[str] = field(default_factory=frozenset)
    # Map raw axis values → the prose noun the README states for them. Default
    # (empty) == use the value's stringified form directly (the dimension/kind
    # case, where the value IS the prose item).
    value_label: dict = field(default_factory=dict)

    def item_text(self, value) -> str:
        """The prose token the README is expected to use for an axis value."""
        return self.value_label.get(value, str(value))


@dataclass(frozen=True)
class Target:
    """A packaged skill whose evals/README.md is gated against its evals.json."""

    slug: str
    # README total-count sentence. A single capture group holds the count
    # token (digit or number-word). Verified == len(evals).
    total_count_re: re.Pattern
    # Which evals are expected to appear as coverage-table rows. Default: all.
    table_filter: Callable[[dict], bool] | None = None
    # Per-axis count breakdowns asserted in prose.
    tally_axes: tuple[TallyAxis, ...] = ()

    @property
    def evals_dir(self) -> Path:
        return SKILLS_DIR / self.slug / "evals"

    @property
    def evals_json(self) -> Path:
        return self.evals_dir / "evals.json"

    @property
    def evals_readme(self) -> Path:
        return self.evals_dir / "README.md"


# The `re-frame2` authoring harness: a single coverage table over ALL evals,
# the "<N> evals, covering …" total sentence, and a per-`dimension` tally
# ("four recipe-correctness … two each for discovery and routing-correctness").
_REFRAME2 = Target(
    slug="re-frame2",
    total_count_re=re.compile(r"\b([A-Za-z]+|\d+)\s+evals,\s+covering"),
    table_filter=None,
    tally_axes=(TallyAxis(field_name="dimension", label="dimension"),),
)

# NOTE: `re-frame2-improver` is deliberately absent from TARGETS. Its
# evals/README.md carries no coverage table / total count / tally prose —
# evals.json is that skill's sole fixture inventory — so there is nothing for
# a doc-table target to cross-check. Its evals.json is gated corpus-wide by
# the A4 identity-uniqueness pass.

# The `re-frame2-xray` tour harness: a single "<N> evals, covering …" total
# sentence (the `re-frame2` shape), a coverage table that individually tabulates
# only the Layer-2 answer-quality entries (those carrying `expectations[]`; the
# trigger-only positives and the negatives are listed in collapsed multi-id
# rows the parser intentionally ignores), and a per-`should_trigger` tally
# (positives / negatives). The boolean axis is rendered to prose via
# `value_label` (`True`→"positive", `False`→"negative").
_XRAY = Target(
    slug="re-frame2-xray",
    total_count_re=re.compile(r"\b([A-Za-z]+|\d+)\s+evals,\s+covering"),
    table_filter=lambda e: bool(e.get("expectations")),
    tally_axes=(
        TallyAxis(
            field_name="should_trigger",
            label="trigger class",
            value_label={True: "positive", False: "negative"},
        ),
    ),
)

TARGETS: tuple[Target, ...] = (_REFRAME2, _XRAY)


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
# Eval-identity uniqueness (A4) — name/id collisions within one evals.json.
# ---------------------------------------------------------------------------
#
# Every eval harness keys per-fixture identity off two fields the schema (and
# each harness README) calls unique: `id` (the unique integer) and `name`
# (the short kebab-case slug, used as the per-run directory name). A duplicate
# `name` silently collides those per-run directories — a later run overwrites
# the earlier, or a report keyed by name merges two distinct cases — and hides
# the intended scenario distinction from a human reading the corpus. A
# duplicate `id` breaks the same identity contract. Neither the README↔JSON
# drift axes (A1–A3) nor the schema runner catch this; this gate catches it
# generically for EVERY skill that ships an evals.json, not only the
# doc-table TARGETS above.


def find_eval_identity_problems(evals: list[dict]) -> list[str]:
    """Return identity-uniqueness problems for one evals.json (empty == clean).

    Flags any `name` slug shared by two or more evals, and any `id` shared by
    two or more evals. Both are per-fixture identity keys the schema declares
    unique.
    """
    problems: list[str] = []
    for keyfield, fmt in (("name", repr), ("id", str)):
        seen: dict = {}
        for e in evals:
            if keyfield not in e:
                continue
            seen.setdefault(e[keyfield], []).append(e)
        for value, group in seen.items():
            if len(group) > 1:
                ids = ", ".join(str(g.get("id", "?")) for g in group)
                problems.append(
                    f"A4 uniqueness: {keyfield} {fmt(value)} is shared by "
                    f"{len(group)} evals (ids {ids}); each {keyfield} must be "
                    f"unique (it keys per-fixture identity / the per-run "
                    f"directory)."
                )
    return problems


def find_all_eval_files() -> list[Path]:
    """Every `skills/<name>/evals/evals.json` on disk, sorted by skill slug."""
    if not SKILLS_DIR.is_dir():
        return []
    return sorted(
        SKILLS_DIR.glob("*/evals/evals.json"),
        key=lambda p: p.parent.parent.name,
    )


# ---------------------------------------------------------------------------
# README parsing.
# ---------------------------------------------------------------------------

# Coverage-table rows: `| 7 | \`recipe-correctness-story-recorder-...\` | … |`.
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


def find_total_count(text: str, total_re: re.Pattern) -> int | None:
    """Pull the README's total-count assertion via the target's pattern."""
    m = total_re.search(text)
    if not m:
        return None
    tok = m.group(1).lower()
    if tok.isdigit():
        return int(tok)
    return _WORD_TO_INT.get(tok)


def _strip_table_rows(text: str) -> str:
    """Drop markdown table rows so the per-axis PROSE check doesn't read a
    coverage-table row (e.g. `| 2 | … | discovery | …`) as the count
    statement. A row is any line whose first non-space char is `|`.
    """
    return "\n".join(
        ln for ln in text.splitlines() if not ln.lstrip().startswith("|")
    )


def axis_tally(evals: list[dict], axis: TallyAxis) -> Counter:
    subset = (
        [e for e in evals if axis.eval_filter(e)]
        if axis.eval_filter
        else evals
    )
    return Counter(e.get(axis.field_name, "?") for e in subset)


def check_axis_sentence(text: str, axis: TallyAxis, tally: Counter) -> list[str]:
    """Return a list of human-readable problems with one axis's prose.

    For each item present in the JSON (minus `skip_items`), require the README
    to state its count adjacent to the item name (digit or number-word).
    Phrasing is free as long as the item's count is the NEAREST count token
    before the item name within a short window — so a packed sentence like
    "2 trigger fixtures and 1 behavioural fixtures" cannot let the `2` satisfy
    the `behavioural` check (the intervening `1` is the nearer, governing
    count). The coverage TABLE is stripped first — its rows carry both an id
    digit and a dimension/kind name and would otherwise satisfy this check
    vacuously.
    """
    problems: list[str] = []
    low = _strip_table_rows(text).lower()
    label = axis.label or axis.field_name
    # Any count token (digit or number-word) — used to forbid a NEARER count
    # between the asserted count and the item name.
    any_count = r"(?:\d+|" + "|".join(re.escape(w) for w in _WORD_TO_INT) + r")"
    for item, n in sorted(tally.items(), key=lambda kv: str(kv[0])):
        if item in axis.skip_items:
            continue
        prose_item = axis.item_text(item)
        tok = _count_token(n)
        # `<count>` then up to ~60 chars (containing NO other count token) then
        # the item name. The window absorbs phrasing like "three
        # recipe-correctness evals" and "two each for discovery and
        # routing-correctness" while rejecting a count that belongs to a
        # neighbouring item.
        pat = re.compile(
            rf"{tok}\b(?:(?!{any_count}\b)[^.]){{0,60}}?{re.escape(prose_item.lower())}",
            re.IGNORECASE,
        )
        if not pat.search(low):
            problems.append(
                f"{label} '{prose_item}' has {n} eval(s) in evals.json but the "
                f"README's per-{label} breakdown does not state a count of "
                f"{n} for it (looked for '{n}'/'{_INT_TO_WORD.get(n, n)}' "
                f"near '{prose_item}')"
            )
    return problems


# ---------------------------------------------------------------------------
# Cross-check.
# ---------------------------------------------------------------------------


def _cross_check(evals: list[dict], text: str, target: Target) -> list[str]:
    """Core cross-check of an in-memory (evals, README text) pair against a
    target's conventions. Used by `check_target` (live files) and the
    self-test (synthetic fixtures).
    """
    findings: list[str] = []

    # A1 — total count.
    json_total = len(evals)
    readme_total = find_total_count(text, target.total_count_re)
    if readme_total is None:
        findings.append(
            "A1 total-count: could not find the README's total-eval-count "
            "sentence — the gate cannot verify the count."
        )
    elif readme_total != json_total:
        findings.append(
            f"A1 total-count: README says {readme_total} evals but "
            f"evals.json has {json_total}."
        )

    # A2 — eval names (and ids): set-equality between the coverage table and
    # the evals the target expects to be tabulated.
    table_evals = (
        [e for e in evals if target.table_filter(e)]
        if target.table_filter
        else evals
    )
    json_by_id = {e.get("id"): e.get("name") for e in table_evals}
    table = parse_readme_table(text)
    json_names = {e.get("name") for e in table_evals}
    table_names = set(table.values())

    for missing in sorted(json_names - table_names):
        findings.append(
            f"A2 names: eval '{missing}' is in evals.json but absent from the "
            f"README coverage table."
        )
    for extra in sorted(table_names - json_names):
        findings.append(
            f"A2 names: README coverage table lists '{extra}' but evals.json "
            f"has no (tabulated) eval by that name."
        )
    # id↔name agreement for rows whose id exists in both.
    for rid, rname in sorted(table.items()):
        jname = json_by_id.get(rid)
        if jname is not None and jname != rname:
            findings.append(
                f"A2 ids: README row id={rid} names '{rname}' but evals.json "
                f"id={rid} is '{jname}'."
            )

    # A3 — tally axes.
    for axis in target.tally_axes:
        findings.extend(
            f"A3 {axis.label or axis.field_name}: {p}"
            for p in check_axis_sentence(text, axis, axis_tally(evals, axis))
        )

    return findings


def check_target(target: Target) -> list[str]:
    """Return a list of drift findings for one target (empty == clean)."""
    evals = load_evals(target.evals_json)
    text = target.evals_readme.read_text(encoding="utf-8")
    return _cross_check(evals, text, target)


# ---------------------------------------------------------------------------
# Self-test — one planted drift per axis into a clean README/JSON pair.
# ---------------------------------------------------------------------------


def _run_self_test() -> int:
    evals = [
        {"id": 1, "name": "a-disc", "dimension": "discovery"},
        {"id": 2, "name": "b-disc", "dimension": "discovery"},
        {"id": 3, "name": "c-recipe", "dimension": "recipe-correctness"},
    ]
    readme = (
        "Three evals, covering the dimensions:\n\n"
        "| ID | Name | Dimension | What |\n|---:|---|---|---|\n"
        "| 1 | `a-disc` | discovery | x |\n"
        "| 2 | `b-disc` | discovery | y |\n"
        "| 3 | `c-recipe` | recipe-correctness | z |\n\n"
        "Two discovery evals and one recipe-correctness eval.\n"
    )
    # The live READMEs state counts as digits, so this pair is the only guard
    # of the number-word total and tallies.
    clean = _cross_check(evals, readme, _REFRAME2)
    if clean:
        print(f"SELF-TEST FAIL: 'clean pair' expected no findings, got {clean}")
    cases = [
        ("A1 stale total", _cross_check(
            evals, readme.replace("Three evals", "Four evals"), _REFRAME2)),
        ("A2 missing name", _cross_check(
            evals, readme.replace("| 3 | `c-recipe` | recipe-correctness | z |\n", ""),
            _REFRAME2)),
        ("A2 phantom name", _cross_check(
            evals, readme.replace("| 3 | `c-recipe`", "| 3 | `c-ghost`"), _REFRAME2)),
        # `Two` is not the nearest count before `discovery`, so it cannot vouch
        # for it.
        ("A3 stale tally", _cross_check(
            evals, readme.replace("Two discovery evals", "Two of them: 1 discovery eval"),
            _REFRAME2)),
        ("A4 duplicate name", find_eval_identity_problems(
            [{"id": 1, "name": "dup"}, {"id": 2, "name": "dup"}])),
    ]
    failures = 1 if clean else 0
    for label, findings in cases:
        axis = label.split()[0]
        if not any(f.startswith(axis) for f in findings):
            failures += 1
            print(f"SELF-TEST FAIL: {label!r} expected an {axis} finding, got {findings}")
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
            "Verify every packaged skill's evals/README.md coverage table, "
            "total count, and per-axis tallies agree with its "
            "evals.json."
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

    # Setup check: every target's files must exist.
    for target in TARGETS:
        for p in (target.evals_json, target.evals_readme):
            if not p.is_file():
                msg = f"required file not found: {p} (target '{target.slug}')"
                print(f"::error::{msg}" if ci else f"ERROR: {msg}", file=sys.stderr)
                return 2

    all_findings: list[tuple[str, str]] = []
    for target in TARGETS:
        try:
            findings = check_target(target)
        except (ValueError, json.JSONDecodeError) as e:
            msg = f"failed to parse {target.evals_json}: {e}"
            print(f"::error::{msg}" if ci else f"ERROR: {msg}", file=sys.stderr)
            return 2
        for f in findings:
            all_findings.append((target.slug, f))
        if args.verbose and not findings:
            print(f"  [{target.slug}] no eval-docs drift.")

    # A4 — eval-identity uniqueness over EVERY skill's evals.json (not just the
    # doc-table TARGETS): a duplicate `name`/`id` collides per-fixture identity.
    for eval_file in find_all_eval_files():
        slug = eval_file.parent.parent.name
        try:
            evals = load_evals(eval_file)
        except (ValueError, json.JSONDecodeError) as e:
            msg = f"failed to parse {eval_file}: {e}"
            print(f"::error::{msg}" if ci else f"ERROR: {msg}", file=sys.stderr)
            return 2
        id_problems = find_eval_identity_problems(evals)
        for p in id_problems:
            all_findings.append((slug, p))
        if args.verbose and not id_problems:
            print(f"  [{slug}] eval identity (name/id) unique.")

    if all_findings:
        print(
            f"Eval-docs drift detected ({len(all_findings)} "
            f"finding{'' if len(all_findings) == 1 else 's'}):",
            file=sys.stderr,
        )
        for slug, f in all_findings:
            line = f"[{slug}] {f}"
            print(f"::error::{line}" if ci else f"ERROR: {line}", file=sys.stderr)
        print(
            "\nFix: update the named skill's evals/README.md (coverage table, "
            "the total-eval-count sentence, and the per-axis breakdowns) to "
            "match its evals/evals.json.",
            file=sys.stderr,
        )
        return 1

    if args.verbose:
        print(f"No eval-docs drift detected across {len(TARGETS)} target(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
