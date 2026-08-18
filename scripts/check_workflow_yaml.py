#!/usr/bin/env python3
"""Assert that every file under `.github/workflows/` is well-formed YAML.

WHY THIS EXISTS (rf2-cb7hs).  Four scripts in this repo READ the workflow files
— `check_gate_scheduling.py`, `check_fast_pr_gap.py`, `check_jvm_lane_rosters.py`
and `check_ci_reproduce_commands.py` — and every one of them is a hand-rolled
line reader.  Measured, not assumed: with an unmatched quote planted on line 1
of `.github/workflows/test.yml`, PyYAML refuses the file and ALL FOUR still
exit 0.  `check_gate_scheduling.py` cannot see the class at all, because it
strips comments by design (rf2-6ckzl).  `actionlint` appears nowhere in the
tracked tree.  So until this file landed, nothing at any tier asserted that a
workflow even PARSED.

WHAT IT IS NOT.  It is not `actionlint`, and it deliberately does not validate
the GitHub Actions grammar — no job wiring, no `needs:` graph, no `if:`
conditions, no schema.  Those are separate claims with separate costs; this one
answers exactly one question, in milliseconds: is the file YAML?

It is also not a SAFETY gate, and the bead says so plainly.  A workflow that
fails to parse does not run, so the PR's rollup comes back short of the required
band and the merge criterion refuses it.  What this buys is the round trip: a
local error naming the file and the line, instead of a push, a CI wait, and a
confusing partial rollup.

WHY IT HAS NO CI HOME, AND WHY THAT IS NOT A HOLE (rf2-ni5sg).  This script is
scheduled only from `scripts/test-fast-pr.sh`, the local pre-checkin spine,
which is skippable by construction — normally the "gate that runs nowhere"
shape.  It is not one here, because the CLAIM has a scheduled home even though
the SCRIPT does not.  `scripts/check_workflow_job_timeouts.py` walks the same
`.github/workflows/*.{yml,yaml}` set through the same `yaml.safe_load`, and its
parse-error arm RETURNS 2 rather than skipping — deliberately, so an unparseable
file cannot pass vacuously there.  It runs unconditionally on every PR from
`test.yml`'s `verify-readme-links`, which is in `all-required-passed`'s
`needs:`.  Verified in CI rather than locally, on main run 32105342995, where
that step reported success: PyYAML imported on the runner (or it would have
exited 3) and every workflow file parsed.  So CI already refuses a malformed
workflow, and a second CI parse of the same files, asserting a strict subset of
the same claim, would buy nothing but a second thing to keep in step.  What is
left here is the round trip above — a line number, and every broken file rather
than the first — which is why this stays a spine lane instead of being deleted.

That arm is therefore LOAD-BEARING BEYOND ITS OWN CHARTER: narrowing it to a
skip would silently remove the only CI-side assertion that the workflows parse,
and would reopen rf2-ni5sg without touching this file.  `test.yml`'s comment on
those two steps says so at the wiring, which is where somebody about to narrow
it would be reading.

Usage (from anywhere — the root is derived from this file's location, or given):
    python scripts/check_workflow_yaml.py [--repo-root DIR] [--verbose]
    python scripts/check_workflow_yaml.py --self-test [--verbose]

Exit codes:
    0  every workflow file parsed
    1  at least one workflow file is not well-formed YAML
    2  usage error, or the workflow directory holds no files to check
       (a sweep that examined nothing is not a pass)
    3  PyYAML is not importable — NOT CHECKED, and the caller must say so
"""
from __future__ import annotations

import argparse
import pathlib
import sys

WORKFLOW_DIR = pathlib.Path(".github") / "workflows"
SUFFIXES = (".yml", ".yaml")


def _diagnose(text: str):
    """Return a one-line diagnosis of `text`, or None if it is well-formed YAML.

    PyYAML's exception already carries the position; pulling `problem_mark` out
    of it is what turns "the workflows are broken" into a line number a reader
    can go to.  `str(exc)` alone is a four-line block whose first line is the
    least useful part of it.
    """
    import yaml  # imported by the caller's guarantee; see main()

    try:
        yaml.safe_load(text)
    except yaml.YAMLError as exc:
        mark = getattr(exc, "problem_mark", None)
        where = (
            f"line {mark.line + 1}, column {mark.column + 1}"
            if mark is not None
            else "position unreported"
        )
        problem = getattr(exc, "problem", None) or str(exc).splitlines()[0].strip()
        context = getattr(exc, "context", None)
        return f"{where}: {context}, {problem}" if context else f"{where}: {problem}"
    return None


# ---------------------------------------------------------------------------
# Self-test.  A green live sweep over a tree that happens to be clean cannot
# tell you the difference between "the workflows parse" and "the checker has
# stopped looking" — the same argument the residue sweeps in this directory
# make for their own self-test arms.  So drive it red on four break shapes that
# are each a plausible hand-edit, and green on a valid workflow.
# ---------------------------------------------------------------------------
_VALID = """\
name: tests
on:
  pull_request:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - run: echo "hello"
"""

_BREAKS = (
    ("unmatched quote", 'name: "tests\non:\n  pull_request:\n'),
    ("tab indentation", "name: tests\njobs:\n\tbuild:\n"),
    ("unclosed flow sequence", "name: tests\njobs: [build, lint\n"),
    ("two colons in one key", "name: tests\non: push: true\n"),
)


def run_self_test(verbose: bool) -> int:
    failures = []

    diagnosis = _diagnose(_VALID)
    if diagnosis is not None:
        failures.append(f"a VALID workflow was rejected: {diagnosis}")
    elif verbose:
        print("  ok  valid workflow accepted")

    for label, text in _BREAKS:
        diagnosis = _diagnose(text)
        if diagnosis is None:
            failures.append(f"break shape not detected: {label}")
        elif verbose:
            print(f"  ok  {label} -> {diagnosis}")

    if failures:
        print("FAIL check_workflow_yaml self-test:", file=sys.stderr)
        for problem in failures:
            print(f"  - {problem}", file=sys.stderr)
        return 1

    print(f"PASS check_workflow_yaml self-test ({1 + len(_BREAKS)} cases)")
    return 0


def run_check(root: pathlib.Path, verbose: bool) -> int:
    directory = root / WORKFLOW_DIR
    if not directory.is_dir():
        print(
            f"ERROR: no workflow directory at {directory}. Wrong --repo-root?",
            file=sys.stderr,
        )
        return 2

    files = sorted(p for p in directory.iterdir() if p.suffix in SUFFIXES and p.is_file())
    if not files:
        # A sweep that found nothing is not a sweep that passed.
        print(f"ERROR: no {'/'.join(SUFFIXES)} files under {directory}.", file=sys.stderr)
        return 2

    broken = []
    for path in files:
        try:
            text = path.read_text(encoding="utf-8")
        except OSError as exc:
            broken.append((path, f"unreadable: {exc}"))
            continue
        diagnosis = _diagnose(text)
        if diagnosis is None:
            if verbose:
                print(f"  ok  {path.relative_to(root).as_posix()}")
        else:
            broken.append((path, diagnosis))

    if broken:
        print("FAIL workflow YAML is not well-formed:", file=sys.stderr)
        for path, diagnosis in broken:
            print(f"  {path.relative_to(root).as_posix()}: {diagnosis}", file=sys.stderr)
        print(
            "\nGitHub will refuse to run a workflow it cannot parse, so the PR's\n"
            "rollup comes back SHORT rather than red. Fix the line above.",
            file=sys.stderr,
        )
        return 1

    print(f"PASS workflow YAML well-formed ({len(files)} files under {WORKFLOW_DIR.as_posix()})")
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--self-test", action="store_true", help="run the checker's own cases")
    parser.add_argument("--verbose", action="store_true", help="name every file checked")
    parser.add_argument(
        "--repo-root",
        default=None,
        help="repository root (default: this script's parent directory's parent)",
    )
    args = parser.parse_args(argv)

    # ONE probe, at the top, so the two arms below can assume the import.  The
    # message names the remedy rather than the traceback: PyYAML is not in
    # requirements.txt in its own right, it arrives as a dependency of mkdocs.
    try:
        import yaml  # noqa: F401
    except ImportError:
        print(
            "NOT CHECKED: workflow YAML. PyYAML is not importable on this\n"
            "  interpreter, so nothing here examined .github/workflows/.  This is\n"
            "  NOT a pass.  Install it (pip install -r requirements.txt, which\n"
            "  pulls PyYAML in through mkdocs) and re-run.",
            file=sys.stderr,
        )
        return 3

    if args.self_test:
        return run_self_test(args.verbose)

    root = (
        pathlib.Path(args.repo_root).resolve()
        if args.repo_root
        else pathlib.Path(__file__).resolve().parent.parent
    )
    return run_check(root, args.verbose)


if __name__ == "__main__":
    sys.exit(main())
