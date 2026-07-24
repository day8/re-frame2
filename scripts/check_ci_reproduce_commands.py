#!/usr/bin/env python3
"""Verify the repo-invariant job's per-failure "reproduce with" command matches
each checker step's real invocation.

Provenance: rf2-m6kdb. The aggregated invariant job
(`.github/workflows/test.yml`, job id `verify-skill-mcp-drift`, displayed as
"Repo invariant checks (...)") runs ~35 `python scripts/check_*.py` checkers,
each with `continue-on-error: true` and an `id`; a final step prints every
failure with a local "reproduce with" command. The first cut derived that
command from the step id alone:

    script="scripts/${id%_selftest}.py"
    ... reproduce with: python $script

For the 17 ids ending `_selftest` this stripped the suffix from the FILENAME but
OMITTED the required `--self-test` flag, so the printed command ran the LIVE scan
instead of the self-test. A developer copying it would see the live scan pass
while the self-test stayed red, and wrongly conclude the failure was spurious.

The durable fix has two halves, both in `.github/workflows/test.yml`:

  1. DERIVE FROM THE REAL `run:`. The aggregator calls this script's `--emit`
     mode, which reads the checked-out workflow and prints the step's exact
     single-line `run:` command. The reproduce line then IS the real command --
     it cannot drift from what the step runs, because it is read from the step.

  2. STRUCTURAL GUARD (this script's default `--check` mode, wired as its own
     checker in the same job). It asserts, over every checker step, that:
       * the `run:` is a single-line `python scripts/<name>.py ...` invocation
         (so `--emit` can always resolve it -- a block-scalar run would leave the
         aggregator unable to derive a command);
       * the referenced `scripts/<name>.py` exists on disk;
       * `id` (minus any `_selftest` suffix) equals the script stem;
       * an id ends in `_selftest` IFF its real `run:` carries `--self-test`
         (the mode convention -- this is exactly the rf2-m6kdb mismatch);
       * the aggregator's FALLBACK id->command reconstruction selects the same
         live-vs-self-test mode as the real `run:` (so even the fallback, used
         only if `--emit` cannot parse a step, is mode-correct).

Run from the repo root:

    python3 scripts/check_ci_reproduce_commands.py            # audit the workflow
    python3 scripts/check_ci_reproduce_commands.py --self-test # prove the guard fires
    python3 scripts/check_ci_reproduce_commands.py --emit <id> # print a step's real command

Exits non-zero on any mismatch (default mode) or self-test failure.
"""
from __future__ import annotations

import argparse
import io
import pathlib
import re
import sys

WORKFLOW = pathlib.Path(".github/workflows/test.yml")

# The job id branch protection keys on (its display `name:` is free to change,
# but this id must stay stable -- see the workflow comment above the job).
JOB_ID = "verify-skill-mcp-drift"

CHECKER_ID_PREFIX = "check_"
SELFTEST_SUFFIX = "_selftest"

# A job header line: two-space indent, the job id, a colon. Nothing after.
_JOB_RE = re.compile(r"^  " + re.escape(JOB_ID) + r":\s*$")
# Any top-level job key (two-space indent). Ends the job block.
_TOPKEY_RE = re.compile(r"^  [A-Za-z0-9_-]+:\s*$")
# A step begins with a six-space indent then "- ".
_STEP_RE = re.compile(r"^      - ")
# Step keys sit at eight-space indent.
_ID_RE = re.compile(r"^        id:\s*(\S+)\s*$")
_RUN_INLINE_RE = re.compile(r"^        run:\s*(\S.*?)\s*$")
_RUN_BLOCK_RE = re.compile(r"^        run:\s*[|>][-+0-9]*\s*$")

# scripts/<name>.py somewhere in a run command.
_SCRIPT_RE = re.compile(r"\bscripts/([A-Za-z0-9_]+)\.py\b")


def _slurp_job_lines(text: str) -> list[str] | None:
    """Return the lines of the invariant job block, or None if it is absent."""
    lines = text.splitlines()
    start = None
    for i, line in enumerate(lines):
        if _JOB_RE.match(line):
            start = i
            break
    if start is None:
        return None
    end = len(lines)
    for i in range(start + 1, len(lines)):
        if _TOPKEY_RE.match(lines[i]):
            end = i
            break
    return lines[start:end]


def parse_checker_steps(text: str) -> tuple[bool, list[dict]]:
    """Parse the invariant job's checker steps.

    Returns (job_found, steps). Each step is a dict with keys `id`, `run`
    (the inline command string, or None), and `run_is_block` (True when the
    step used a block-scalar `run:` we deliberately refuse to flatten).

    Only steps whose `id` starts with `check_` are returned -- those are the
    invariant checkers. The final report step has no `id`, so it is excluded.
    """
    job = _slurp_job_lines(text)
    if job is None:
        return (False, [])

    steps: list[dict] = []
    cur: dict | None = None
    for line in job:
        if _STEP_RE.match(line):
            if cur is not None:
                steps.append(cur)
            cur = {"id": None, "run": None, "run_is_block": False}
            continue
        if cur is None:
            continue
        m = _ID_RE.match(line)
        if m:
            cur["id"] = m.group(1)
            continue
        if _RUN_BLOCK_RE.match(line):
            cur["run_is_block"] = True
            cur["run"] = None
            continue
        m = _RUN_INLINE_RE.match(line)
        if m:
            cur["run"] = m.group(1)
            continue
    if cur is not None:
        steps.append(cur)

    checkers = [
        s for s in steps if s["id"] and s["id"].startswith(CHECKER_ID_PREFIX)
    ]
    return (True, checkers)


def emit_command(step_id: str, text: str) -> str | None:
    """The exact single-line `run:` command for `step_id`, or None if it has no
    single-line checker run (unknown id, or a block-scalar run)."""
    found, steps = parse_checker_steps(text)
    if not found:
        return None
    for s in steps:
        if s["id"] == step_id:
            if s["run_is_block"] or not s["run"]:
                return None
            return s["run"]
    return None


def _has_selftest_flag(cmd: str) -> bool:
    return re.search(r"(?:^|\s)--self-test(?:\s|=|$)", cmd) is not None


def fallback_command(step_id: str) -> str:
    """The id-only reconstruction the aggregator uses IF `--emit` cannot parse a
    step. Mode-correct by construction: `_selftest` ids get `--self-test`."""
    if step_id.endswith(SELFTEST_SUFFIX):
        stem = step_id[: -len(SELFTEST_SUFFIX)]
        return f"python scripts/{stem}.py --self-test"
    return f"python scripts/{step_id}.py"


def audit_steps(steps: list[dict], exists=None) -> list[str]:
    """Return a list of human-readable problems (empty == clean)."""
    if exists is None:
        exists = lambda p: pathlib.Path(p).exists()

    problems: list[str] = []
    for s in steps:
        sid = s["id"]
        run = s["run"]

        if s["run_is_block"] or not run:
            problems.append(
                f"{sid}: checker step has a block-scalar/empty `run:`; the "
                f"aggregator cannot derive a reproduce command from it. Make it "
                f"a single-line `run: python scripts/<x>.py ...`, or teach "
                f"scripts/check_ci_reproduce_commands.py the new shape."
            )
            continue

        if not run.startswith("python scripts/"):
            problems.append(
                f"{sid}: `run:` is not a `python scripts/...` invocation: {run!r}"
            )
            continue

        m = _SCRIPT_RE.search(run)
        if not m:
            problems.append(
                f"{sid}: no scripts/<name>.py found in `run:` {run!r}"
            )
            continue
        script_stem = m.group(1)
        script_path = f"scripts/{script_stem}.py"
        if not exists(script_path):
            problems.append(f"{sid}: referenced {script_path} does not exist")

        id_base = (
            sid[: -len(SELFTEST_SUFFIX)] if sid.endswith(SELFTEST_SUFFIX) else sid
        )
        if id_base != script_stem:
            problems.append(
                f"{sid}: id base {id_base!r} != script stem {script_stem!r}; "
                f"the reproduce line (and fallback reconstruction) would name "
                f"the wrong script"
            )

        id_is_selftest = sid.endswith(SELFTEST_SUFFIX)
        run_is_selftest = _has_selftest_flag(run)
        if id_is_selftest != run_is_selftest:
            problems.append(
                f"{sid}: mode mismatch -- id "
                f"{'ends' if id_is_selftest else 'does not end'} in `_selftest` "
                f"but `run:` {'has' if run_is_selftest else 'lacks'} "
                f"`--self-test`. The reproduce command would run the wrong "
                f"live-vs-self-test mode (the rf2-m6kdb defect)."
            )

        synth = fallback_command(sid)
        if _has_selftest_flag(synth) != run_is_selftest:
            problems.append(
                f"{sid}: fallback reconstruction {synth!r} selects a different "
                f"live-vs-self-test mode than the real `run:` {run!r}"
            )

    return problems


def run_live(verbose: bool) -> int:
    if not WORKFLOW.exists():
        sys.stderr.write(f"ERROR: {WORKFLOW} not found (run from the repo root)\n")
        return 1
    text = WORKFLOW.read_text(encoding="utf-8")
    found, steps = parse_checker_steps(text)
    if not found:
        sys.stderr.write(
            f"ERROR: job id '{JOB_ID}' not found in {WORKFLOW}; the parser "
            f"cannot locate the invariant checkers.\n"
        )
        return 1
    if len(steps) < 2:
        sys.stderr.write(
            f"ERROR: found only {len(steps)} checker step(s) under '{JOB_ID}'; "
            f"the parser is almost certainly broken.\n"
        )
        return 1

    problems = audit_steps(steps)
    if problems:
        sys.stderr.write(
            f"{len(problems)} reproduce-command mismatch(es) in {WORKFLOW}:\n"
        )
        for p in problems:
            sys.stderr.write(f"  - {p}\n")
        return 1

    if verbose:
        print(
            f"{len(steps)} checker steps; every reproduce command matches its "
            f"step's script + live-vs-self-test mode:"
        )
        for s in steps:
            print(f"  {s['id']}: {s['run']}")
    else:
        print(
            f"reproduce-command contract clean: {len(steps)} checker steps in sync."
        )
    return 0


def run_self_tests(verbose: bool) -> int:
    failures = 0

    def check(name: str, ok: bool) -> None:
        nonlocal failures
        if ok:
            if verbose:
                sys.stderr.write(f"self-test PASS: {name}\n")
        else:
            failures += 1
            sys.stderr.write(f"self-test FAIL: {name}\n")

    # A fake on-disk oracle so fixtures need no real script files.
    exists_ok = lambda p: p in {"scripts/check_foo.py", "scripts/check_bar.py"}

    def step(sid, run=None, block=False):
        return {"id": sid, "run": run, "run_is_block": block}

    # Conformant: a live checker + its self-test twin.
    conformant = [
        step("check_foo", "python scripts/check_foo.py --verbose --ci"),
        step("check_foo_selftest", "python scripts/check_foo.py --self-test"),
    ]
    check("conformant live+selftest pair passes", audit_steps(conformant, exists_ok) == [])

    # A bare live checker with no flags (the check_skill_redirect_anchors shape).
    check(
        "bare live checker (no flags) passes",
        audit_steps([step("check_foo", "python scripts/check_foo.py")], exists_ok) == [],
    )

    # THE rf2-m6kdb DEFECT: a `_selftest` id whose run drops `--self-test`.
    check(
        "selftest id missing --self-test FIRES",
        len(audit_steps([step("check_foo_selftest", "python scripts/check_foo.py --verbose")], exists_ok)) >= 1,
    )

    # The mirror: a live id whose run carries `--self-test`.
    check(
        "live id carrying --self-test FIRES",
        len(audit_steps([step("check_foo", "python scripts/check_foo.py --self-test")], exists_ok)) >= 1,
    )

    # id stem does not match the script it runs.
    check(
        "id/script stem mismatch FIRES",
        len(audit_steps([step("check_foo", "python scripts/check_bar.py --verbose")], exists_ok)) >= 1,
    )

    # Referenced script does not exist on disk.
    check(
        "missing script file FIRES",
        len(audit_steps([step("check_missing", "python scripts/check_missing.py --verbose")], exists_ok)) >= 1,
    )

    # A block-scalar run the aggregator could not derive a command from.
    check(
        "block-scalar run FIRES",
        len(audit_steps([step("check_foo", None, block=True)], exists_ok)) >= 1,
    )

    # A run that is not a python-scripts invocation at all.
    check(
        "non python-scripts run FIRES",
        len(audit_steps([step("check_foo", "bash -c true")], exists_ok)) >= 1,
    )

    # fallback_command is mode-correct for both shapes.
    check(
        "fallback for a selftest id includes --self-test",
        _has_selftest_flag(fallback_command("check_foo_selftest")),
    )
    check(
        "fallback for a live id omits --self-test",
        not _has_selftest_flag(fallback_command("check_foo")),
    )

    # Against the REAL workflow: parser locates the job, finds the checkers,
    # the live audit is clean, and --emit round-trips a real selftest + live id.
    if WORKFLOW.exists():
        text = WORKFLOW.read_text(encoding="utf-8")
        found, real_steps = parse_checker_steps(text)
        check("parser locates the job in the real workflow", found)
        check("parser finds >= 30 checker steps in the real workflow", len(real_steps) >= 30)
        check("real workflow audit is clean", audit_steps(real_steps) == [])

        sel = next((s["id"] for s in real_steps if s["id"].endswith(SELFTEST_SUFFIX)), None)
        liv = next((s["id"] for s in real_steps if not s["id"].endswith(SELFTEST_SUFFIX)), None)
        check(
            "emit(real selftest id) includes --self-test",
            sel is not None and _has_selftest_flag(emit_command(sel, text) or ""),
        )
        check(
            "emit(real live id) omits --self-test",
            liv is not None and not _has_selftest_flag(emit_command(liv, text) or ""),
        )
    else:
        check("real workflow present for parser self-test", False)

    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    sys.stderr.write("all self-tests passed.\n")
    return 0


def main(argv) -> int:
    if hasattr(sys.stdout, "buffer"):
        sys.stdout = io.TextIOWrapper(
            sys.stdout.buffer, encoding="utf-8", line_buffering=True
        )

    parser = argparse.ArgumentParser(
        description=(
            "Verify the repo-invariant job's aggregator prints a reproduce "
            "command that matches each checker step's real invocation."
        )
    )
    parser.add_argument(
        "--emit",
        metavar="STEP_ID",
        help="Print STEP_ID's exact single-line run command (used by the aggregator).",
    )
    parser.add_argument(
        "--self-test",
        dest="self_test",
        action="store_true",
        help="Run in-memory self-tests proving the mode-mismatch guard fires, then exit.",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Audit the real workflow (this is the default when no mode flag is given).",
    )
    parser.add_argument(
        "--verbose",
        "-v",
        action="store_true",
        help="Print the full checker inventory (or per-test PASS lines under --self-test).",
    )
    parser.add_argument(
        "--ci",
        action="store_true",
        help="Accepted for parity with sibling checkers; has no effect.",
    )
    args = parser.parse_args(list(argv))

    if args.emit is not None:
        if not WORKFLOW.exists():
            sys.stderr.write(f"{WORKFLOW} not found (run from the repo root)\n")
            return 2
        cmd = emit_command(args.emit, WORKFLOW.read_text(encoding="utf-8"))
        if cmd is None:
            sys.stderr.write(
                f"no single-line checker step with id {args.emit!r}\n"
            )
            return 2
        print(cmd)
        return 0

    if args.self_test:
        return run_self_tests(args.verbose)

    return run_live(args.verbose)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
