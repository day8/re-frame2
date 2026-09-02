#!/usr/bin/env python3
"""Guard the two SKILL.md description limits Claude Code actually ENFORCES (rf2-w9p2).

WHY THIS EXISTS, and why it does not check the number everybody quotes.

rf2-cupfi observed that six of nine `skills/*/SKILL.md` descriptions exceeded
1,024 characters and asked whether that truncates at runtime.  It ruled: *do not
add a gate unless truncation is confirmed*.  A worker then confirmed truncation
against the SHIPPED runtime rather than against documentation, and found the
1,024 figure is **not the enforced one**:

  * 1,024 is the Agent Skills PACKAGING spec's figure.  Claude Code's SKILL.md
    frontmatter validator checks only that `description` is a string ("description
    must be a string, got <type>.  At runtime this value is dropped.") and applies
    NO length check whatsoever.  The one enforced 1,024 in the runtime is a
    `.max(1024)` on the *plugin marketplace manifest* description — a different
    field on a different surface, and this repo's nine `plugin.json` descriptions
    run 146-418 characters, nowhere near it.

  * The enforced per-description cap is **1,536** (`skillListingMaxDescChars`),
    applied as a HARD SLICE — `.slice(0, 1536)`, no ellipsis, no warning in the
    listing the model reads, and the cut lands mid-word.  So the TAIL is always
    what is lost.

  * A SECOND mechanism has no per-skill spelling at all: the listing carries a
    TOTAL budget, and past it Claude Code stops trimming tails and starts dropping
    ENTIRE descriptions, lowest-priority first, rendering those skills as a bare
    `- name` with NOTHING for the model to route on.

A gate written against 1,024 would therefore red six descriptions that are fine
and miss the mechanism that actually degrades this family.  This gate is written
against what is enforced.

THE RUNTIME MECHANISM, transcribed from the shipped bundle (Claude Code 2.1.258,
`claude.exe`; the listing planner and its renderer):

    var z2o = 0.01,        // skillListingBudgetFraction default
        cjn = 4,           // bytes per token
        q2o = 200000;      // default context window
    var V2o = 1536;        // skillListingMaxDescChars default

    budget      = floor((contextWindow ?? 200000) * 4 * 0.01)          // = 8000
    resolved(s) = s.whenToUse ? `${s.description} - ${s.whenToUse}` : s.description
    entryLen(s) = s.name.length + 4 + min(resolved(s).length, 1536)
    total       = sum(entryLen) + (entryCount - 1)

    if total <= budget      -> "fits";  every description renders in full (to 1536)
    else                    -> "priority" mode: every non-exempt entry is costed at
                               its bare `- name` floor, the remaining budget is
                               spent on descriptions in DESCENDING priority order,
                               and every entry that no longer fits renders as
                               `- name` alone.

    Skills BUNDLED with Claude Code (type "prompt", source "bundled") are EXEMPT
    from that dropping pass.  Consumer-installed skills — every skill in this
    repo — are NOT.

WHAT THIS GATE CHECKS, and why each is the severity it is.

  C1  PER-DESCRIPTION CAP (hard fail).  Every skill's resolved description must
      be <= 1,536 characters.  This is a real cliff, it is local, and its remedy
      is local: shorten or reorder ONE description.  All nine currently pass, so
      the gate lands green and stays a regression guard rather than a backlog.

  C2  FAMILY LISTING FOOTPRINT (hard fail, RATCHETED — not the absolute).  The
      family's contribution to the listing is computed with the runtime's own
      formula and compared against a pinned ceiling.

      The absolute 8,000 budget is NOT the fail threshold, deliberately.  The
      family already costs 10,727 characters — 134% of the entire listing budget
      — before the consumer installs a single skill of their own and before
      Claude Code's own bundled commands take their share.  Failing at 8,000
      would red this repo on day one with no in-repo remedy, because the remedy
      is not "shorten a description": nine skills averaging ~1,200 characters
      cannot all fit in 8,000 no matter how they are written.  That is a product
      decision (ship fewer skills, or accept that the lowest-priority ones render
      bare), and a gate cannot make it.

      What a gate CAN do here is stop the family getting worse, so C2 is a
      ratchet against the measured footprint, in the idiom this repo already uses
      for `check-ai-tracking-ratchet.sh`.  The absolute comparison against 8,000
      is PRINTED on every run, passing or failing, so the number is never lost
      behind a green tick.

WHAT THIS GATE DOES NOT CHECK — stated because a gate's silence reads as coverage.

  DISQUALIFIER-FIRST ORDERING IS NOT MECHANICALLY CHECKABLE HERE, and pretending
  otherwise would be worse than omitting it.  Ordering is the half that makes a
  truncation survivable — whatever is last is what the slice removes, which is
  why PR #9051 moved re-frame2-xray's "**Do not use** ... re-frame2-pair" clause
  to the FRONT.  But detecting the two halves requires agreed markers, and there
  are none: measured across the nine live descriptions, only six contain any
  "trigger" marker at all, only eight contain any recognisable disqualifier
  phrasing (spelled variously "Do not use", "Not for", "Never", "Activates only
  on explicit pull", "... instead"), and among the six carrying both, the
  disqualifier follows the trigger list in five.  A mechanical assertion would
  red four to five compliant descriptions on a heuristic nobody agreed to.

  What IS mechanical, and what this gate prints instead, is HEADROOM: how many
  characters each description has before the slice starts eating its tail.  That
  is the actionable form of the same concern — reagent-migration currently sits
  49 characters from the cliff — and it needs no guess about phrasing.

CAVEAT, carried deliberately.  1,536 and 0.01 are DEFAULTS of user-configurable
settings (`skillListingMaxDescChars`, `skillListingBudgetFraction`), and the
budget also scales with the context window and can be overridden outright by
`SLASH_COMMAND_TOOL_CHAR_BUDGET`.  Both were read out of ONE shipped version,
pinned below with that version cited.  They are the documented defaults, not
constants of the platform; re-measure before trusting them against a new release.

Author-facing prose for the same mechanism lives in `skills/README.md`
"§SKILL.md frontmatter description — the cap that actually bites".  This script
is only the machine-checked half.

Usage:

    python scripts/check_skill_description_budget.py                # check
    python scripts/check_skill_description_budget.py --verbose      # + the table
    python scripts/check_skill_description_budget.py --ci           # ::error:: lines
    python scripts/check_skill_description_budget.py --self-test    # built-in fixtures

`--verbose` prints the repo root the gate resolved, so a run can be shown to have
read the intended checkout rather than a sibling worktree's copy.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

try:
    import yaml
except ImportError:  # pragma: no cover - CI installs requirements.txt
    print(
        "ERROR: PyYAML is required (it arrives with mkdocs via requirements.txt).\n"
        "       Install it with: python -m pip install -r requirements.txt",
        file=sys.stderr,
    )
    raise SystemExit(2)

REPO_ROOT = Path(__file__).resolve().parent.parent
SKILLS_DIR = REPO_ROOT / "skills"

# ---------------------------------------------------------------------------
# Pinned runtime constants.  Read from the shipped bundle, not from docs.
# ---------------------------------------------------------------------------

#: Version of Claude Code these numbers were read out of.
PINNED_RUNTIME_VERSION = "2.1.258"

#: `skillListingMaxDescChars` default.  Applied as a hard `.slice(0, N)`.
LISTING_MAX_DESC_CHARS = 1536

#: `skillListingBudgetFraction` default.
LISTING_BUDGET_FRACTION = 0.01

#: Bytes-per-token the listing planner assumes.
BYTES_PER_TOKEN = 4

#: Context window the listing planner falls back to.
DEFAULT_CONTEXT_WINDOW = 200_000

#: `"- "` + `": "` — the fixed per-entry overhead around name and description.
ENTRY_OVERHEAD_CHARS = 4

#: Ratchet ceiling for the family's listing footprint (see C2 above).
#: Measured 2026-09-03 over the nine live SKILL.md files at 10,727.  LOWER this
#: when descriptions shrink; RAISING it is a deliberate act that says the family
#: now costs the listing more, and wants a reason in the commit message.
FAMILY_FOOTPRINT_CEILING = 10_727


def listing_budget(context_window: int = DEFAULT_CONTEXT_WINDOW) -> int:
    """The whole-listing character budget, by the runtime's own formula."""
    return max(1, int(context_window * BYTES_PER_TOKEN * LISTING_BUDGET_FRACTION))


# ---------------------------------------------------------------------------
# Reading the family.
# ---------------------------------------------------------------------------

_FRONTMATTER_RE = re.compile(r"\A---\r?\n(.*?)\r?\n---\r?\n", re.DOTALL)


@dataclass(frozen=True)
class SkillEntry:
    """One SKILL.md, costed the way the listing planner costs it."""

    path: Path
    name: str
    resolved: str

    @property
    def resolved_len(self) -> int:
        return len(self.resolved)

    @property
    def capped_len(self) -> int:
        return min(self.resolved_len, LISTING_MAX_DESC_CHARS)

    @property
    def entry_len(self) -> int:
        return len(self.name) + ENTRY_OVERHEAD_CHARS + self.capped_len

    @property
    def over_cap(self) -> bool:
        return self.resolved_len > LISTING_MAX_DESC_CHARS

    @property
    def headroom(self) -> int:
        """Characters left before the hard slice starts removing the tail."""
        return LISTING_MAX_DESC_CHARS - self.resolved_len

    @property
    def rel(self) -> str:
        try:
            return self.path.relative_to(REPO_ROOT).as_posix()
        except ValueError:
            return self.path.as_posix()


def _resolve_description(frontmatter: dict) -> str:
    """Reproduce the runtime's `resolved(s)`.

    `description`, or `"<description> - <when_to_use>"` when a when-to-use field
    is present.  Both spellings are accepted because the packaging spec uses the
    snake_case one and the runtime reads it as `whenToUse`.
    """
    description = frontmatter.get("description")
    if not isinstance(description, str):
        raise ValueError(
            f"description must be a string, got "
            f"{'array' if isinstance(description, list) else type(description).__name__}"
        )
    when_to_use = frontmatter.get("when_to_use") or frontmatter.get("whenToUse")
    if isinstance(when_to_use, str) and when_to_use:
        return f"{description} - {when_to_use}"
    return description


def read_skill(path: Path) -> SkillEntry:
    """Parse one SKILL.md into a costed listing entry."""
    text = path.read_text(encoding="utf-8")
    match = _FRONTMATTER_RE.match(text)
    if match is None:
        raise ValueError("no YAML frontmatter block")
    frontmatter = yaml.safe_load(match.group(1))
    if not isinstance(frontmatter, dict):
        raise ValueError("frontmatter must be a YAML mapping (key: value pairs)")
    name = frontmatter.get("name")
    if not isinstance(name, str) or not name:
        raise ValueError("frontmatter has no string `name`")
    return SkillEntry(path=path, name=name, resolved=_resolve_description(frontmatter))


def collect_skills(skills_dir: Path) -> tuple[list[SkillEntry], list[str]]:
    """Read every `*/SKILL.md` under `skills_dir`.  Returns (entries, errors)."""
    entries: list[SkillEntry] = []
    errors: list[str] = []
    for path in sorted(skills_dir.glob("*/SKILL.md")):
        try:
            entries.append(read_skill(path))
        except (ValueError, yaml.YAMLError) as exc:
            rel = path.relative_to(skills_dir.parent).as_posix()
            errors.append(f"{rel}: {exc}")
    return entries, errors


def family_footprint(entries: Iterable[SkillEntry]) -> int:
    """Total listing cost of these entries, by the runtime's own formula."""
    entries = list(entries)
    if not entries:
        return 0
    return sum(e.entry_len for e in entries) + (len(entries) - 1)


# ---------------------------------------------------------------------------
# Checking.
# ---------------------------------------------------------------------------


def check(entries: list[SkillEntry], ceiling: int = FAMILY_FOOTPRINT_CEILING) -> list[str]:
    """Return a list of failure messages; empty means clean."""
    failures: list[str] = []

    # C1 - per-description hard cap.
    for entry in sorted(entries, key=lambda e: e.name):
        if entry.over_cap:
            lost = entry.resolved_len - LISTING_MAX_DESC_CHARS
            failures.append(
                f"{entry.rel}: resolved description is {entry.resolved_len} chars, "
                f"over the enforced {LISTING_MAX_DESC_CHARS}-char cap "
                f"(skillListingMaxDescChars, Claude Code {PINNED_RUNTIME_VERSION}). "
                f"The listing hard-slices it, so the LAST {lost} char"
                f"{'' if lost == 1 else 's'} are dropped mid-word with no ellipsis "
                f"and no warning. Shorten it, or move whatever must survive "
                f"(a disqualifier clause especially) ahead of the cut."
            )

    # C2 - family footprint ratchet.
    total = family_footprint(entries)
    if total > ceiling:
        failures.append(
            f"skills/: the family's listing footprint is {total} chars, over the "
            f"pinned ceiling of {ceiling}. This is a RATCHET, not the absolute "
            f"budget: the listing drops ENTIRE descriptions past "
            f"{listing_budget()} chars (lowest priority first, rendering a skill "
            f"as a bare '- name' with nothing to route on), and consumer-installed "
            f"skills are not exempt from that pass. Shrink a description, or raise "
            f"FAMILY_FOOTPRINT_CEILING in scripts/check_skill_description_budget.py "
            f"deliberately and say in the commit message why the family should cost "
            f"the listing more."
        )

    return failures


def render_table(entries: list[SkillEntry]) -> str:
    """The per-skill measurement table."""
    lines = [
        f"{'skill':<24} {'resolved':>8} {'capped':>7} {'entry':>6} {'headroom':>9}",
        f"{'-' * 24} {'-' * 8} {'-' * 7} {'-' * 6} {'-' * 9}",
    ]
    for entry in sorted(entries, key=lambda e: -e.resolved_len):
        flag = "  OVER CAP" if entry.over_cap else ""
        lines.append(
            f"{entry.name:<24} {entry.resolved_len:>8} {entry.capped_len:>7} "
            f"{entry.entry_len:>6} {entry.headroom:>9}{flag}"
        )
    return "\n".join(lines)


def render_summary(entries: list[SkillEntry], ceiling: int = FAMILY_FOOTPRINT_CEILING) -> str:
    """The family-level summary.  Always names the absolute budget."""
    total = family_footprint(entries)
    budget = listing_budget()
    pct = (100.0 * total / budget) if budget else 0.0
    over = total > budget
    return "\n".join(
        [
            f"family entries              : {len(entries)}",
            f"family listing footprint    : {total} chars",
            f"pinned ratchet ceiling      : {ceiling} chars",
            f"whole-listing budget        : {budget} chars "
            f"({DEFAULT_CONTEXT_WINDOW} window x {BYTES_PER_TOKEN} bytes/token "
            f"x {LISTING_BUDGET_FRACTION})",
            f"family share of that budget : {pct:.1f}%"
            + (
                "  <- OVER: past this, whole descriptions are dropped, "
                "lowest priority first"
                if over
                else ""
            ),
        ]
    )


# ---------------------------------------------------------------------------
# Self-test.  Both directions, on synthetic fixtures.
# ---------------------------------------------------------------------------


def _write_skill(root: Path, name: str, description: str, when_to_use: str | None = None) -> None:
    d = root / name
    d.mkdir(parents=True, exist_ok=True)
    lines = ["---", f"name: {name}", f"description: {description!r}"]
    if when_to_use is not None:
        lines.append(f"when_to_use: {when_to_use!r}")
    lines += ["---", "", "# body", ""]
    (d / "SKILL.md").write_text("\n".join(lines), encoding="utf-8")


def run_self_test() -> int:
    """Exercise every rule in BOTH directions.  Returns 0 when all cases hold."""
    failures: list[str] = []

    def expect(label: str, condition: bool, detail: str = "") -> None:
        if condition:
            print(f"  ok   {label}")
        else:
            print(f"  FAIL {label}{(': ' + detail) if detail else ''}")
            failures.append(label)

    print(f"self-test: check_skill_description_budget (repo root {REPO_ROOT})")

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp) / "skills"
        root.mkdir()

        # --- C1 negative: a compliant description passes. -------------------
        _write_skill(root, "compliant", "x" * (LISTING_MAX_DESC_CHARS - 1))
        entries, errors = collect_skills(root)
        expect("C1 fixture parses", not errors and len(entries) == 1, str(errors))
        expect(
            "C1 PASSES a description one char under the cap",
            check(entries, ceiling=10**9) == [],
        )

        # --- C1 positive: one char over the cap reds it. --------------------
        _write_skill(root, "compliant", "x" * (LISTING_MAX_DESC_CHARS + 1))
        entries, _ = collect_skills(root)
        failures_seen = check(entries, ceiling=10**9)
        expect(
            "C1 FAILS a description one char over the cap",
            len(failures_seen) == 1 and "over the enforced" in failures_seen[0],
            str(failures_seen),
        )
        expect(
            "C1 boundary: exactly AT the cap passes",
            (
                _write_skill(root, "compliant", "x" * LISTING_MAX_DESC_CHARS),
                check(collect_skills(root)[0], ceiling=10**9),
            )[1]
            == [],
        )

        # --- when_to_use is folded into the measured length. ----------------
        _write_skill(root, "compliant", "x" * 1000, when_to_use="y" * 600)
        entries, _ = collect_skills(root)
        entry = entries[0]
        expect(
            "resolved length includes ' - ' + when_to_use",
            entry.resolved_len == 1000 + 3 + 600,
            f"got {entry.resolved_len}",
        )
        expect(
            "C1 FAILS on description+when_to_use crossing the cap",
            check(entries, ceiling=10**9) != [],
        )

        # --- entry/footprint arithmetic matches the runtime formula. --------
        _write_skill(root, "compliant", "x" * 100)
        entries, _ = collect_skills(root)
        entry = entries[0]
        expect(
            "entryLen == len(name) + 4 + min(desc, 1536)",
            entry.entry_len == len("compliant") + ENTRY_OVERHEAD_CHARS + 100,
            f"got {entry.entry_len}",
        )
        _write_skill(root, "second", "y" * 200)
        entries, _ = collect_skills(root)
        expected_total = (
            (len("compliant") + 4 + 100) + (len("second") + 4 + 200) + 1
        )  # + (n-1) separators
        expect(
            "footprint == sum(entryLen) + (n - 1)",
            family_footprint(entries) == expected_total,
            f"got {family_footprint(entries)} want {expected_total}",
        )

        # --- C2 both directions against an explicit ceiling. ----------------
        total = family_footprint(entries)
        expect("C2 PASSES at the ceiling", check(entries, ceiling=total) == [])
        red = check(entries, ceiling=total - 1)
        expect(
            "C2 FAILS one char over the ceiling",
            len(red) == 1 and "listing footprint" in red[0],
            str(red),
        )

        # --- capping is a slice, not a rejection: over-cap entries still
        #     cost only 1536 toward the footprint. -------------------------
        _write_skill(root, "huge", "z" * 5000)
        entries, _ = collect_skills(root)
        huge = next(e for e in entries if e.name == "huge")
        expect(
            "an over-cap entry costs the listing only the capped length",
            huge.entry_len == len("huge") + 4 + LISTING_MAX_DESC_CHARS,
            f"got {huge.entry_len}",
        )

        # --- malformed frontmatter is reported, not silently skipped. ------
        bad = root / "broken"
        bad.mkdir()
        (bad / "SKILL.md").write_text("no frontmatter here\n", encoding="utf-8")
        _, errors = collect_skills(root)
        expect(
            "malformed SKILL.md is reported as an error",
            any("broken/SKILL.md" in e for e in errors),
            str(errors),
        )

        # --- a non-string description is reported (the one thing the
        #     runtime validator itself checks). ---------------------------
        listy = root / "listy"
        listy.mkdir()
        (listy / "SKILL.md").write_text(
            "---\nname: listy\ndescription:\n  - a\n  - b\n---\n\n# body\n",
            encoding="utf-8",
        )
        _, errors = collect_skills(root)
        expect(
            "a non-string description is reported",
            any("listy/SKILL.md" in e and "must be a string" in e for e in errors),
            str(errors),
        )

    # --- the pinned budget arithmetic. -------------------------------------
    expect("listing budget is 8000 at a 200K window", listing_budget() == 8000)

    print()
    if failures:
        print(f"self-test FAILED: {len(failures)} case(s): {', '.join(failures)}")
        return 1
    print("self-test PASSED")
    return 0


# ---------------------------------------------------------------------------
# CLI.
# ---------------------------------------------------------------------------


def _is_ci() -> bool:
    return os.environ.get("GITHUB_ACTIONS") == "true"


def main(argv: Iterable[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Guard the enforced SKILL.md description limits: the 1,536-char "
            "per-description hard slice, and the family's listing footprint "
            "against a pinned ratchet (rf2-w9p2)."
        ),
    )
    parser.add_argument("--verbose", "-v", action="store_true",
                        help="Print the repo root, the per-skill table and the summary.")
    parser.add_argument("--ci", action="store_true",
                        help="Emit GitHub-Actions ::error:: lines (auto-on under GITHUB_ACTIONS).")
    parser.add_argument("--self-test", action="store_true",
                        help="Run built-in fixtures and exit.")
    args = parser.parse_args(list(argv))

    if args.self_test:
        return run_self_test()

    ci = args.ci or _is_ci()

    if not SKILLS_DIR.is_dir():
        msg = f"skills directory not found: {SKILLS_DIR}"
        print(f"::error::{msg}" if ci else f"ERROR: {msg}", file=sys.stderr)
        return 2

    entries, errors = collect_skills(SKILLS_DIR)

    if errors:
        for err in errors:
            msg = f"unreadable SKILL.md frontmatter: {err}"
            print(f"::error::{msg}" if ci else f"ERROR: {msg}", file=sys.stderr)
        return 2

    if not entries:
        msg = f"no skills/*/SKILL.md found under {SKILLS_DIR}"
        print(f"::error::{msg}" if ci else f"ERROR: {msg}", file=sys.stderr)
        return 2

    if args.verbose:
        print(f"repo root: {REPO_ROOT}")
        print(f"scanned {len(entries)} SKILL.md file(s) under {SKILLS_DIR.name}/")
        print()
        print(render_table(entries))
        print()
        print(render_summary(entries))
        print()

    failures = check(entries)
    if failures:
        print(
            f"SKILL.md description budget: {len(failures)} "
            f"failure{'' if len(failures) == 1 else 's'}.",
            file=sys.stderr,
        )
        for line in failures:
            print(f"::error::{line}" if ci else f"ERROR: {line}", file=sys.stderr)
        if not args.verbose:
            print(render_summary(entries), file=sys.stderr)
        return 1

    if args.verbose:
        print(
            f"OK: all {len(entries)} descriptions are within the "
            f"{LISTING_MAX_DESC_CHARS}-char cap, and the family footprint is "
            f"within the pinned ceiling."
        )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
