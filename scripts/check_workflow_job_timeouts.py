#!/usr/bin/env python3
"""Assert that every job in `.github/workflows/` carries a job-level `timeout-minutes`.

WHY THIS EXISTS.  A job with no `timeout-minutes` inherits GitHub
Actions' 360-minute default.  A wedged runner then produces a check that never
TERMINATES — not a red check, no check at all for six hours.  That is a
different and worse thing than a failure: this project's merge loop reads CI
verdicts to decide what to merge, and a verdict that never arrives gives it
nothing to read and no remedy but a hand cancel.  The cap is a circuit breaker,
not a performance budget; it converts a hang into a legible failure.

WHY A RATCHET RATHER THAN REVIEW.  An uncapped job looks like nothing in
review; the omission shows only when a run wedges and somebody happens to be
watching.  So a convention held by review alone erodes one job at a time until
uncapped is the house majority, and the next detection is the next hang.  This
closes it at the cheapest possible point: on arrival.

WHY IT PARSES RATHER THAN GREPS — THE WHOLE POINT OF THE FILE.  A
`timeout-minutes` under a STEP is not a job cap.  It bounds one step and leaves
the job free to hang in any other, so a job carrying only a step-level key is
exactly as unprotected as one carrying none.  The two are indistinguishable to
a line-oriented search: both produce a `timeout-minutes:` hit inside the job's
text block, differing only in indentation, which is not a fact a grep can
reason about.  This tree carries real step-level keys (Playwright installs, MCP
conformance runs), so the confusion is live here and not hypothetical.
Reading the parsed document makes the
distinction structural: a job cap is a key on the job mapping, a step cap is a
key on an element of its `steps` sequence, and nothing has to infer one from
the other.

WHAT IT DOES NOT DO.  It does not judge the VALUE.  Any cap is accepted, and no
opinion is offered about whether a number is too generous or too tight — that
is a per-job measurement question, and folding it in here would turn a
mechanical one-bit check into a heuristic that argues with its reader.  Presence only:
one bit per job, exactly one correct answer, nothing to tune.

WHY IT IS NOT FOLDED INTO check_workflow_yaml.py.  Two reasons, and the second
is decisive.  (1) That script declares a deliberately single-question charter
in its own docstring — "is the file YAML?" — and explicitly disclaims
validating the Actions grammar.  (2) It is wired only into
`scripts/test-fast-pr.sh`, the LOCAL pre-checkin spine, and into no workflow
job at all.  A ratchet living there would not run in CI, which is the one place
it has to run to ratchet anything — the "gate that runs nowhere" shape that
this repo's own `check_gate_scheduling.py` exists to catch.  So this is a
sibling of that script, wired into test.yml's `verify-readme-links` job, which
installs `requirements.txt` and therefore has PyYAML.  It is NOT in the
always-on `Repo invariant checks` job (`verify-skill-mcp-drift`) alongside the
rest of the family: that job installs no pip packages, so both of this
checker's steps would exit 3 there — the exit-3 contract below turns a
misplacement into one visible failure rather than a silent green.

EXEMPTION: REUSABLE-WORKFLOW CALLS.  A job whose body is a job-level `uses:`
calls a reusable workflow, and GitHub REJECTS `timeout-minutes` on it outright
— the cap belongs to the called workflow's own jobs.  Requiring the key there
would demand invalid YAML.  The self-test covers that arm, since the live sweep
need not meet such a job; it is here so a reusable-workflow call is not greeted
with an impossible instruction.

Usage (from anywhere — the root is derived from this file's location, or given):
    python scripts/check_workflow_job_timeouts.py [--repo-root DIR] [--verbose]
    python scripts/check_workflow_job_timeouts.py --self-test [--verbose]

Exit codes:
    0  every job carries a job-level timeout-minutes (or is an exempt `uses:` job)
    1  at least one job has no job-level cap
    2  usage error, or the workflow directory holds no jobs to check
       (a sweep that examined nothing is not a pass)
    3  PyYAML is not importable — NOT CHECKED, and the caller must say so
"""
from __future__ import annotations

import argparse
import pathlib
import sys

WORKFLOW_DIR = pathlib.Path(".github") / "workflows"
SUFFIXES = (".yml", ".yaml")
KEY = "timeout-minutes"


def audit_document(doc) -> tuple[list[tuple[str, str]], int]:
    """Return ([(job_id, why)], jobs_examined) for one parsed workflow document.

    `why` names the shape rather than restating the rule, because the two
    failing shapes want different fixes and a reader who is told which one they
    have does not need to go and look.  A step-level cap in particular reads, at
    a glance in the file, like the job is covered.
    """
    if not isinstance(doc, dict):
        return [], 0

    jobs = doc.get("jobs")
    if not isinstance(jobs, dict):
        return [], 0

    offenders = []
    for job_id, job in jobs.items():
        if not isinstance(job, dict):
            # Not a shape GitHub accepts; check_workflow_yaml.py owns
            # well-formedness, so this file declines to have an opinion.
            continue

        # A reusable-workflow call cannot carry the key at all.
        if "uses" in job:
            continue

        cap = job.get(KEY, None)
        if KEY in job and cap is not None:
            continue

        # Distinguish the two failing shapes.  `steps` may be absent entirely.
        steps = job.get("steps")
        step_capped = isinstance(steps, list) and any(
            isinstance(step, dict) and step.get(KEY) is not None for step in steps
        )

        if KEY in job:
            why = f"`{KEY}:` is present but empty, which caps nothing"
        elif step_capped:
            why = (
                f"only a STEP carries `{KEY}`; the JOB is uncapped and can still "
                "hang in any other step"
            )
        else:
            why = f"no job-level `{KEY}`"

        offenders.append((str(job_id), why))

    return offenders, len(jobs)


# ---------------------------------------------------------------------------
# Self-test: one workflow holding every job shape.  The capped job comes first
# and the offenders after it, so a checker that stops at the first job or the
# first offender reports the wrong list.
# ---------------------------------------------------------------------------
_SELF_TEST_WORKFLOW = """\
jobs:
  capped:
    timeout-minutes: 30
  call:
    uses: ./.github/workflows/other.yml
  step-only:
    steps:
      - timeout-minutes: 8
  empty:
    timeout-minutes:
  uncapped:
    runs-on: ubuntu-latest
"""
_SELF_TEST_OFFENDERS = ["step-only", "empty", "uncapped"]


def run_self_test(verbose: bool) -> int:
    import yaml

    offenders, examined = audit_document(yaml.safe_load(_SELF_TEST_WORKFLOW))
    got = [job_id for job_id, _ in offenders]
    if got != _SELF_TEST_OFFENDERS:
        print(
            "FAIL check_workflow_job_timeouts self-test: expected offenders "
            f"{_SELF_TEST_OFFENDERS}, got {got}",
            file=sys.stderr,
        )
        return 1
    if verbose:
        for job_id, why in offenders:
            print(f"  ok  {job_id}: {why}")

    print(f"PASS check_workflow_job_timeouts self-test ({examined} job shapes)")
    return 0


def run_check(root: pathlib.Path, verbose: bool) -> int:
    import yaml

    directory = root / WORKFLOW_DIR
    if not directory.is_dir():
        print(
            f"ERROR: no workflow directory at {directory}. Wrong --repo-root?",
            file=sys.stderr,
        )
        return 2

    files = sorted(p for p in directory.iterdir() if p.suffix in SUFFIXES and p.is_file())
    if not files:
        print(f"ERROR: no {'/'.join(SUFFIXES)} files under {directory}.", file=sys.stderr)
        return 2

    total_jobs = 0
    offenders = []
    for path in files:
        try:
            doc = yaml.safe_load(path.read_text(encoding="utf-8"))
        except yaml.YAMLError as exc:
            # check_workflow_yaml.py is the gate that reports this properly; fail
            # rather than skip, so an unparseable file cannot pass vacuously here.
            print(
                f"ERROR: {path.relative_to(root).as_posix()} is not well-formed YAML "
                f"({exc.__class__.__name__}). Run check_workflow_yaml.py.",
                file=sys.stderr,
            )
            return 2

        found, examined = audit_document(doc)
        total_jobs += examined
        rel = path.relative_to(root).as_posix()
        for job_id, why in found:
            offenders.append((rel, job_id, why))
        if verbose and examined:
            print(f"  ok  {rel}: {examined - len(found)}/{examined} job(s) capped")

    if total_jobs == 0:
        # A sweep that examined nothing is not a sweep that passed.
        print(f"ERROR: no jobs found under {directory}.", file=sys.stderr)
        return 2

    if offenders:
        print(f"FAIL {len(offenders)} workflow job(s) have no job-level {KEY}:", file=sys.stderr)
        for rel, job_id, why in offenders:
            print(f"  {rel}: {job_id} — {why}", file=sys.stderr)
        print(
            f"\nAn uncapped job inherits GitHub's 360-minute default, so a wedged\n"
            f"runner yields a check that never terminates — nothing for the merge\n"
            f"loop to read. Add `{KEY}:` to the JOB (a step-level key does not\n"
            "count), sized generously above the job's measured worst case.",
            file=sys.stderr,
        )
        return 1

    print(
        f"PASS every workflow job carries a job-level {KEY} "
        f"({total_jobs} jobs across {len(files)} files under {WORKFLOW_DIR.as_posix()})"
    )
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

    # ONE probe, at the top, so both arms below can assume the import.  PyYAML is
    # not in requirements.txt in its own right; it arrives through mkdocs.
    try:
        import yaml  # noqa: F401
    except ImportError:
        print(
            "NOT CHECKED: workflow job timeouts. PyYAML is not importable on this\n"
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
