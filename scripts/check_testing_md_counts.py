#!/usr/bin/env python3
"""Ratchet TESTING.md's hand-maintained Xray feature-matrix counts against source.

TESTING.md's "Kinds of tests" table describes the Xray feature gate with four
*hand-maintained* counts, all derived from one source-of-truth file — the Xray
feature-matrix substrate ``tools/xray/testbeds/feature_matrix/scenarios.cjs``:

  * the full scenario count,
  * the ``smoke: true`` scenario count,
  * the full-sweep staged-surface count, and
  * the count of *distinct* surfaces the smoke scenarios stage.

These counts drifted silently once already (rf2-dvwvyk): PR 4163 (rf2-fxnszc)
removed staged surfaces from ``scenarios.cjs`` but did not update TESTING.md in
the same PR, because a change under ``tools/xray/testbeds/`` fires the
``story_xray_browser`` surface, not a TESTING.md re-check — so nothing forced
the prose counts to be re-verified. TESTING.md's tables are explicitly
hand-maintained and these prose counts had no automated guard.

This script closes that gap. It derives the live counts from ``scenarios.cjs``,
extracts every count TESTING.md *claims*, and asserts they agree. It is wired
into the always-on ``verify-skill-mcp-drift`` PR job in
``.github/workflows/test.yml`` (rf2-o23pim), so it runs on every PR regardless
of which surface changed — a scenario add or a prose edit that desyncs the two
is caught before merge.

Scope note (rf2-o23pim). An earlier revision of this guard also pinned an
"MCP conformance ×N" job-fan-out count against ``.github/workflows/test.yml``.
The 2026-07-04 TESTING.md reshape (643 -> 162 lines, commit 12bfeae5a) replaced
the classifier-outputs inventory that carried that count with *pointers to the
executable classifier script* — leaving no reshape-compatible home for a
hardcoded job count. That check was therefore retired here, and the now-unused
``test.yml`` derivation removed with it. The guard now pins the Xray
feature-matrix counts only, which keep a natural home in the Kinds-of-tests
"Xray gates" row (the reshape kept that row, only dropping its numbers).

Ground truth derivation (deterministic, line-oriented — no JS parser):

  * scenario count       = object-literal ``name: '...'`` fields in
                           scenarios.cjs (a leading-comment ``name:`` is
                           excluded because the regex requires a quoted
                           string value at field indentation).
  * smoke scenario count = ``smoke: true`` fields.
  * full surface count   = ``servedPath: '...'`` fields in STAGED_SURFACES.
  * smoke surface count  = distinct surfaces the ``smoke: true`` scenarios
                           stage, computed exactly as the gate runner's
                           ``surfaceForScenario`` does: take each smoke
                           scenario's ``url``, drop the ``#``/``?`` tail and
                           surrounding slashes, and match against the set of
                           ``servedPath`` values.

Claimed counts in TESTING.md are matched by a small registry of regexes
(``CLAIM_PATTERNS`` below). Each pattern names which ground-truth value it
must equal; every match is checked, so a count repeated on several lines is
all verified. A pattern that *never* matches is itself a failure — that
catches the case where a TESTING.md rewrite drops or rewords a sentence the
guard was pinning, so the guard can be re-aimed rather than silently passing.

Exit code:
    0  all claimed counts agree with source
    1  at least one mismatch, or a claim pattern matched nothing
    2  invocation / setup error (a source file is missing/unreadable)

The script is stdlib-only (no third-party deps) and cross-platform: it reads
files via pathlib and never shells out, so it runs identically on the Windows
dev box and the Linux CI runners.

Usage:
    python scripts/check_testing_md_counts.py [--verbose]
    python scripts/check_testing_md_counts.py --self-test [--verbose]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

TESTING_MD = REPO_ROOT / "TESTING.md"
SCENARIOS_CJS = REPO_ROOT / "tools" / "xray" / "testbeds" / "feature_matrix" / "scenarios.cjs"

# Source-derivation regexes (line-oriented; see module docstring).
RE_SCENARIO_NAME = re.compile(r"^\s+name:\s*'")
RE_SMOKE_TRUE = re.compile(r"^\s+smoke:\s*true\b")
RE_SERVED_PATH = re.compile(r"^\s+servedPath:\s*'([^']*)'")

# For the smoke-surface derivation: a scenario object opens with a
# ``name: '...'`` line and (somewhere before the next ``name:``) may carry a
# ``smoke: true`` and a ``url: '...'``. We walk scenario blocks rather than
# trying to parse JS objects.
RE_URL = re.compile(r"^\s+url:\s*'([^']*)'")


def fail_setup(msg: str) -> "None":
    sys.stderr.write(f"check_testing_md_counts: setup error: {msg}\n")
    raise SystemExit(2)


def _surface_for_url(url: str, served_paths: set) -> "str | None":
    """Mirror the gate runner's surfaceForScenario(): strip hash + query +
    surrounding slashes, then match against servedPath values."""
    path_only = url.split("#")[0].split("?")[0]
    url_path = path_only.strip("/")
    return url_path if url_path in served_paths else None


def derive_ground_truth(scenarios_text: str) -> dict:
    """Return the four live counts derived from scenarios.cjs."""
    scenario_count = 0
    smoke_count = 0
    served_paths = []

    for line in scenarios_text.splitlines():
        if RE_SCENARIO_NAME.match(line):
            scenario_count += 1
        if RE_SMOKE_TRUE.match(line):
            smoke_count += 1
        m = RE_SERVED_PATH.match(line)
        if m:
            served_paths.append(m.group(1))

    served_set = set(served_paths)

    # Smoke-surface count: walk scenario blocks, and for each block that
    # carries `smoke: true`, resolve its `url` to a staged surface.
    smoke_surfaces = set()
    cur_url = None
    cur_is_smoke = False
    have_block = False

    def _flush():
        nonlocal cur_url, cur_is_smoke
        if cur_is_smoke and cur_url is not None:
            surface = _surface_for_url(cur_url, served_set)
            if surface is not None:
                smoke_surfaces.add(surface)
        cur_url = None
        cur_is_smoke = False

    for line in scenarios_text.splitlines():
        if RE_SCENARIO_NAME.match(line):
            if have_block:
                _flush()
            have_block = True
            continue
        if not have_block:
            continue
        if RE_SMOKE_TRUE.match(line):
            cur_is_smoke = True
        m = RE_URL.match(line)
        if m and cur_url is None:
            cur_url = m.group(1)
    if have_block:
        _flush()

    return {
        "scenarios": scenario_count,
        "smoke_scenarios": smoke_count,
        "full_surfaces": len(served_set),
        "smoke_surfaces": len(smoke_surfaces),
    }


# Each entry: (human label, regex with one capturing group per count, the
# ground-truth key(s) it must equal). A pattern that matches zero lines is a
# failure, so a TESTING.md reword that drops a pinned sentence re-surfaces
# here rather than silently passing. All three patterns pin the single
# Kinds-of-tests "Xray gates" row, whose sentence reads:
#   "smoke = the N highest-signal scenarios over M staged surfaces,
#    nightly = all P scenarios across Q surfaces."
CLAIM_PATTERNS = [
    ("smoke-scenario prose (Xray gates row)", re.compile(r"smoke = the (\d+) highest-signal scenarios"), "smoke_scenarios"),
    ("smoke-surface prose (Xray gates row)", re.compile(r"highest-signal scenarios over (\d+) staged surfaces"), "smoke_surfaces"),
    ("full-sweep scenario/surface (Xray gates row)", re.compile(r"nightly = all (\d+) scenarios across (\d+) surfaces"), ("scenarios", "full_surfaces")),
]


def check_claims(testing_text: str, truth: dict, verbose: bool) -> "list[str]":
    """Return a list of violation strings (empty == all good)."""
    violations = []
    lines = testing_text.splitlines()

    for label, pattern, keys in CLAIM_PATTERNS:
        key_tuple = keys if isinstance(keys, tuple) else (keys,)
        match_count = 0
        for lineno, line in enumerate(lines, start=1):
            m = pattern.search(line)
            if not m:
                continue
            match_count += 1
            claimed = m.groups()
            if len(claimed) != len(key_tuple):
                violations.append(
                    f"TESTING.md:{lineno}: claim pattern for '{label}' captured "
                    f"{len(claimed)} group(s) but expects {len(key_tuple)} — pattern bug"
                )
                continue
            for claimed_str, key in zip(claimed, key_tuple):
                claimed_n = int(claimed_str)
                expected = truth[key]
                if claimed_n != expected:
                    violations.append(
                        f"TESTING.md:{lineno}: '{label}' claims {claimed_n} for "
                        f"'{key}' but source has {expected}"
                    )
                elif verbose:
                    sys.stderr.write(
                        f"  ok  TESTING.md:{lineno}  {label}: {key}={claimed_n}\n"
                    )
        if match_count == 0:
            violations.append(
                f"TESTING.md: claim pattern '{label}' matched no line — the "
                f"sentence it pins may have been reworded; re-aim CLAIM_PATTERNS "
                f"in scripts/check_testing_md_counts.py"
            )
    return violations


def run_real(verbose: bool) -> int:
    for p in (TESTING_MD, SCENARIOS_CJS):
        if not p.is_file():
            fail_setup(f"missing source file: {p}")

    testing_text = TESTING_MD.read_text(encoding="utf-8")
    scenarios_text = SCENARIOS_CJS.read_text(encoding="utf-8")

    truth = derive_ground_truth(scenarios_text)
    if verbose:
        sys.stderr.write(f"ground truth: {truth}\n")

    violations = check_claims(testing_text, truth, verbose)
    if violations:
        sys.stderr.write("check_testing_md_counts: FAIL\n")
        for v in violations:
            sys.stderr.write(f"  {v}\n")
        return 1
    sys.stderr.write(
        "check_testing_md_counts: OK — TESTING.md Xray counts match "
        "scenarios.cjs "
        f"(scenarios={truth['scenarios']}, smoke={truth['smoke_scenarios']}, "
        f"full-surfaces={truth['full_surfaces']}, smoke-surfaces={truth['smoke_surfaces']})\n"
    )
    return 0


# --------------------------------------------------------------------------
# Self-test: exercise derivation + claim-checking on synthetic fixtures so
# the guard's own parsing logic is covered without depending on the live
# (and intentionally mutable) repo counts.
# --------------------------------------------------------------------------

_SELFTEST_SCENARIOS = """\
const STAGED_SURFACES = [
  {
    servedPath: 'counter',
  },
  {
    servedPath: 'testbeds/deliberate-throw',
  },
  {
    servedPath: 'testbeds/panel-gallery',
  },
  {
    servedPath: 'testbeds/extra',
  },
];
// name: this is a comment, not a scenario field
const SCENARIOS = [
  {
    name: 'alpha',
    url: '/counter/',
    smoke: true,
  },
  {
    name: 'beta',
    url: '/testbeds/deliberate-throw/',
    smoke: true,
  },
  {
    name: 'gamma palette',
    url: '/counter/',
    smoke: true,
  },
  {
    name: 'delta gallery',
    url: '/testbeds/panel-gallery/#/stories',
    smoke: true,
  },
  {
    name: 'epsilon (nightly only)',
    url: '/testbeds/extra/',
  },
];
"""

# Expected from the fixture above:
#   scenarios=5, smoke=4, full-surfaces=4, smoke-surfaces=3 (counter,
#   deliberate-throw, panel-gallery — gamma reuses counter).
_SELFTEST_TRUTH = {
    "scenarios": 5,
    "smoke_scenarios": 4,
    "full_surfaces": 4,
    "smoke_surfaces": 3,
}

_SELFTEST_TESTING_GOOD = """\
The Xray feature matrix; smoke = the 4 highest-signal scenarios over 3 staged surfaces, nightly = all 5 scenarios across 4 surfaces.
"""

# Flip the smoke-scenario count and the full-sweep scenario count; both pinned
# claims must fire (the surface counts stay correct, proving per-group checking).
_SELFTEST_TESTING_BAD = _SELFTEST_TESTING_GOOD.replace(
    "smoke = the 4 highest-signal", "smoke = the 9 highest-signal"
).replace("all 5 scenarios", "all 13 scenarios")


def run_self_test(verbose: bool) -> int:
    failures = []

    truth = derive_ground_truth(_SELFTEST_SCENARIOS)
    if truth != _SELFTEST_TRUTH:
        failures.append(f"derivation mismatch: got {truth}, want {_SELFTEST_TRUTH}")

    good_violations = check_claims(_SELFTEST_TESTING_GOOD, _SELFTEST_TRUTH, verbose)
    if good_violations:
        failures.append(
            "GOOD fixture should pass but reported violations:\n    "
            + "\n    ".join(good_violations)
        )

    bad_violations = check_claims(_SELFTEST_TESTING_BAD, _SELFTEST_TRUTH, verbose)
    if not any("smoke-scenario prose" in v for v in bad_violations):
        failures.append("BAD fixture: smoke-scenario-count mismatch was not caught")
    if not any("full-sweep scenario/surface" in v for v in bad_violations):
        failures.append("BAD fixture: full-sweep scenario-count mismatch was not caught")

    if failures:
        sys.stderr.write("check_testing_md_counts --self-test: FAIL\n")
        for f in failures:
            sys.stderr.write(f"  {f}\n")
        return 1
    sys.stderr.write("check_testing_md_counts --self-test: OK\n")
    return 0


def main(argv: "list[str]") -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true", help="run on synthetic fixtures")
    parser.add_argument("--verbose", action="store_true", help="print per-claim detail")
    args = parser.parse_args(argv)

    if args.self_test:
        return run_self_test(args.verbose)
    return run_real(args.verbose)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
