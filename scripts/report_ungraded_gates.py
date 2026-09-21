#!/usr/bin/env python3
r"""Which path-gated CI surfaces have not been armed on trunk, and for how long?

rf2-5dihy.  A REPORT, not a gate.  It exits 0 on every answer it can compute and
is deliberately unwireable into CI: there is no failing exit status to key on.

THE DEFECT IT ANSWERS
---------------------
A path-gated gate can stop grading trunk without anything saying so, and a green
that never looked is typographically identical to a green that passed.  The check
column is COMPLETE -- the job is present and green -- so a rollup census reads at
band and settles, and "is trunk green?" answers yes, truthfully, about a gate
that graded nothing.  The cost lands on the next PR whose diff happens to arm the
gate: it goes red, its own diff is innocent, and it inherits the blame for a
regression that landed days earlier.

That is not hypothetical.  The Story static-export gate
(`test.yml` -> `Story/Xray browser gates (PR-smoke)` -> the `story_static_gate`
step) last actually executed on trunk at `a2173cdcf5`, 2026-09-18.  A regression
landed at `58bd56635b` on 2026-09-20, and the first thing to notice was PR
#10139, whose entire diff is two lines inside comments.

WHAT IT ASKS, AND WHAT IT DOES NOT
----------------------------------
It asks the SCHEDULING question -- *was this surface armed?* -- and nothing else.
It does not ask whether a gate PASSED.  For a path-gated matrix those are
genuinely different questions, and scheduling is the one nothing could answer:

  * "Which gates are RED, and for how long?" already has a home.  The nightly
    sweep runs every gate unconditionally (`if: ${{ !cancelled() }}`, each with
    its own step result), and `.github/scripts/nightly_failure_alert.py`
    maintains a single tracking issue naming the FAILING STEPS and counting the
    consecutive red runs.  That surface works; consult it first.
  * "Which gates have not GRADED TRUNK, and for how long?" had no home at all.
    The nightly cannot answer it, because on the nightly nothing is skipped --
    "ungraded" is a property of the PR/push-time filtered matrix alone.

So this script is the second half, and the two are complementary rather than
redundant.  The nightly bounds the damage at 24 hours; this bounds nothing and
reports.  If it disagrees with the CI record, the CI record wins -- the last
section below is how to ask it.

HOW IT DECIDES, AND WHY IT HAS NO ROSTER TO KEEP IN SYNC
--------------------------------------------------------
A step gated on `needs.detect_changed_surfaces.outputs.<surface> == 'true'` runs
exactly when the classifier says that surface is armed.  The classifier is a pure
function of PATHS -- `.github/scripts/report-changed-surfaces.sh` -- so the whole
question is answerable from git history with no network, no auth and no API
budget, by asking the classifier the same thing CI asks it.

Nothing here is enumerated by hand, which is the property that keeps it from
becoming one more mechanism going quiet:

  * THE SURFACE LIST is read from `report-changed-surfaces.sh --all`, which emits
    every output it knows.  A surface added to the classifier appears here on its
    next run, with no edit.
  * THE PUSH FILTER is read from `.github/workflows/test.yml`'s own
    `on: push: paths-ignore:` block.  A push whose every file is ignored produces
    no run of that workflow at all, so such a commit must not be credited with
    arming anything.  Editing that list in the workflow updates this script.

The classifier is invoked as a black box and never modified.  It and its mirror
`implementation/scripts/_changed-surfaces.test.cjs` are hot-zone and one-toucher
by construction; this reads them and touches neither.

A SURFACE IS NOT A JOB -- READ THE CONDITION, NOT THE NAME
-----------------------------------------------------------
This reports SURFACES.  A job or step is gated on a boolean COMBINATION of them,
so "surface X unarmed for three days" does NOT by itself mean the job whose name
resembles X did not run.  Measured on trunk push `765c370308`: `ssr_node` read
false, yet `Node implementation/ssr-node (bounded SSR service suite)` ran and
passed -- because its condition is
`implementation_jvm == 'true' || ssr_node == 'true'` and the other disjunct was
true.  The prediction was right; reading the job name as the surface would have
made it look wrong.

So map a reported surface to the steps whose `if:` actually NAMES it:

    grep -n "outputs.<surface>" .github/workflows/test.yml

A step gated on that surface ALONE is ungraded for exactly as long as this
report says.  A step gated on a disjunction is ungraded only while every
disjunct is unarmed.

WHY THE ANSWER IS APPROXIMATE, AND IN WHICH DIRECTION
-----------------------------------------------------
CI classifies a PUSH, using `github.event.before` as its base; this walks
COMMITS.  The two differ on a multi-commit push, and they differ in the safe
direction: a commit's own diff is a subset of its push's diff, so a surface the
push armed can read unarmed here.  That over-reports staleness -- a false alarm,
not a false all-clear.

The `paths-ignore` modelling runs the other way and is why it is modelled at all:
`TESTING.md` is a `mark_all` trigger, so a TESTING.md-only push would otherwise
be credited with arming every surface while in fact running no workflow.  Glob
matching here is deliberately generous, which again over-reports staleness.

Treat a reported gap as a QUESTION -- the answer is one API call away, and the
script prints the exact command.

MEASURED AGAINST GROUND TRUTH (2026-09-21, three cases, both directions)
------------------------------------------------------------------------
Each row is the classifier's prediction for `story_static_gate` beside the
step-level conclusion the GitHub jobs API reports for that trunk push's run.
A one-directional check would not have been a control: it takes a TRUE and a
FALSE to show the instrument discriminates rather than answering one way.

    a4e90ebaee  predicted false  ->  step "skipped"   (run 35546132986)
    a2173cdcf5  predicted true   ->  step "success"   (run 35277995669)
    c8188b6c20  predicted true   ->  step "success"   (run 34816272492)

3 of 3 agree.  `c8188b6c20` carries the sharper control inside one job: the
static-export step reads `success` while the feature-load step beside it reads
`skipped`, and the classifier separates them the same way -- so the agreement is
per-STEP and not merely per-job.

`--self-test` re-runs those three predictions against the live classifier.  They
are historical facts about immutable commits and settled runs, so the pin cannot
rot; what it catches is the classifier's arming rules moving out from under this
script.

CONFIRMING A REPORTED GAP AGAINST THE CI RECORD
------------------------------------------------
Step-level conclusions come from the jobs API, never from the run conclusion --
a run is red if ANY step failed, which says nothing about the step you care
about, and a green run can carry a skipped step.  Pass the FULL 40-character
head oid; an abbreviated or hand-extended one matches nothing and returns `[]`,
which reads exactly like "no runs".

    SHA=$(git rev-parse <commit>)
    RID=$(gh api "repos/<owner>/<repo>/actions/runs?head_sha=$SHA&per_page=20" \
            --jq '.workflow_runs[] | select(.name=="tests" and .event=="push") | .id' | head -1)
    gh api "repos/<owner>/<repo>/actions/runs/$RID/jobs?per_page=100" \
            --jq '.jobs[] | "\(.name)\n" + ([.steps[] | "   \(.name) => \(.conclusion)"] | join("\n"))'

USAGE
-----
    python scripts/report_ungraded_gates.py
    python scripts/report_ungraded_gates.py --since a4e90ebaee
    python scripts/report_ungraded_gates.py --surface story_static_gate --limit 800
    python scripts/report_ungraded_gates.py --self-test

Exit codes:  0 = reported (whatever it found);  2 = invocation / setup error.
There is deliberately no 1.
"""

from __future__ import annotations

import argparse
import datetime as _dt
import fnmatch
import shutil
import subprocess
import sys
from pathlib import Path

CLASSIFIER = Path(".github/scripts/report-changed-surfaces.sh")
TEST_WORKFLOW = Path(".github/workflows/test.yml")

# The three ground-truth predictions documented above.  Historical facts about
# immutable commits and settled runs -- see the module docstring for the
# step-level conclusions they were checked against.
SELF_TEST_CASES = [
    ("a4e90ebaee", "story_static_gate", False, "step skipped, run 35546132986"),
    ("a2173cdcf5", "story_static_gate", True, "step success, run 35277995669"),
    ("c8188b6c20", "story_static_gate", True, "step success, run 34816272492"),
]


def die(msg: str) -> "None":
    print("report_ungraded_gates: %s" % msg, file=sys.stderr)
    raise SystemExit(2)


def repo_root() -> Path:
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--show-toplevel"],
            capture_output=True, text=True, check=True,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError) as exc:
        die("not inside a git repository (%s)" % exc)
    return Path(out)


def sh_exe() -> str:
    exe = shutil.which("sh") or shutil.which("bash")
    if not exe:
        die("no POSIX shell on PATH; the classifier is a bash script")
    return exe


def classify(root: Path, shell: str, paths: "list[str]") -> "dict[str, bool]":
    """Ask the classifier which surfaces these paths arm.  Black box, unmodified."""
    if not paths:
        return {}
    proc = subprocess.run(
        [shell, str(CLASSIFIER)] + paths,
        cwd=str(root), capture_output=True, text=True,
    )
    if proc.returncode != 0:
        die("classifier exited %d: %s" % (proc.returncode, proc.stderr.strip()[:400]))
    out = {}
    for line in proc.stdout.replace("\r\n", "\n").split("\n"):
        if "=" in line:
            key, _, val = line.partition("=")
            out[key.strip()] = (val.strip() == "true")
    return out


def all_surfaces(root: Path, shell: str) -> "list[str]":
    """The canonical surface list, read off the classifier rather than listed here."""
    proc = subprocess.run(
        [shell, str(CLASSIFIER), "--all"],
        cwd=str(root), capture_output=True, text=True,
    )
    if proc.returncode != 0:
        die("classifier --all exited %d" % proc.returncode)
    names = []
    for line in proc.stdout.replace("\r\n", "\n").split("\n"):
        if "=" in line:
            names.append(line.partition("=")[0].strip())
    if not names:
        die("classifier --all emitted no surfaces")
    return names


def push_ignored_globs(root: Path) -> "list[str]":
    """Read test.yml's own `on: push: paths-ignore:` list.  Not a roster here."""
    text = (root / TEST_WORKFLOW).read_text(encoding="utf-8", errors="replace")
    lines = text.replace("\r\n", "\n").split("\n")
    globs: "list[str]" = []
    in_push = False
    in_ignore = False
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        indent = len(line) - len(line.lstrip(" "))
        if indent == 0 and stripped.startswith("jobs:"):
            break
        if indent == 2 and stripped.startswith("push:"):
            in_push, in_ignore = True, False
            continue
        if indent == 2 and not stripped.startswith("push:"):
            in_push, in_ignore = False, False
            continue
        if in_push and indent == 4:
            in_ignore = stripped.startswith("paths-ignore:")
            continue
        if in_push and in_ignore and stripped.startswith("- "):
            globs.append(stripped[2:].strip().strip("'\""))
    return globs


def path_is_ignored(path: str, globs: "list[str]") -> bool:
    """Generous match: over-matching over-reports staleness, the safe direction."""
    for g in globs:
        candidates = {g, g.replace("**/", ""), g.replace("**", "*")}
        for cand in candidates:
            if fnmatch.fnmatch(path, cand):
                return True
        if not any(ch in g for ch in "*?["):
            if path == g or path.startswith(g.rstrip("/") + "/"):
                return True
    return False


def walk_trunk(root: Path, rev_range: str, limit: int):
    """One `git log` for the whole window: (sha, iso_date, subject, [files])."""
    # A PRINTABLE record separator, not a NUL: a real NUL byte inside a
    # subprocess ARGUMENT is rejected outright by Windows CreateProcess
    # ("ValueError: embedded null character"), even though git is perfectly
    # happy to EMIT one. The `%x00` field separators below are expanded by git
    # into its output and never travel in the argument, so they are fine.
    sep = "<<<RUG-COMMIT>>>"
    fmt = sep + "%H%x00%cI%x00%s"
    cmd = ["git", "log", "--first-parent", "--no-renames", "--name-only", "--format=" + fmt]
    if limit > 0:
        cmd.append("-n%d" % limit)
    cmd.append(rev_range)
    proc = subprocess.run(cmd, cwd=str(root), capture_output=True, text=True)
    if proc.returncode != 0:
        die("git log failed: %s" % proc.stderr.strip()[:400])
    for chunk in proc.stdout.replace("\r\n", "\n").split(sep):
        if not chunk.strip():
            continue
        head, _, body = chunk.partition("\n")
        parts = head.split("\x00")
        if len(parts) < 3:
            continue
        files = [f for f in body.split("\n") if f.strip()]
        yield parts[0], parts[1], parts[2], files


def age_of(iso: str, now: _dt.datetime) -> str:
    try:
        then = _dt.datetime.fromisoformat(iso)
    except ValueError:
        return "?"
    delta = now - then
    days, rem = delta.days, delta.seconds
    if days > 0:
        return "%dd %dh" % (days, rem // 3600)
    if rem >= 3600:
        return "%dh %dm" % (rem // 3600, (rem % 3600) // 60)
    return "%dm" % (rem // 60)


def run_self_test(root: Path, shell: str) -> int:
    print("SELF-TEST -- three settled trunk pushes, predictions vs the CI record")
    print("(see the module docstring for the step-level conclusions these pin)\n")
    bad = 0
    for rev, surface, expected, evidence in SELF_TEST_CASES:
        probe = subprocess.run(
            ["git", "rev-parse", "--verify", "--quiet", rev + "^{commit}"],
            cwd=str(root), capture_output=True, text=True,
        )
        if probe.returncode != 0:
            print("  SKIP  %s -- not present in this checkout" % rev)
            continue
        diff = subprocess.run(
            ["git", "diff", "--no-renames", "--name-only", rev + "^", rev],
            cwd=str(root), capture_output=True, text=True,
        )
        files = [f for f in diff.stdout.replace("\r\n", "\n").split("\n") if f.strip()]
        got = classify(root, shell, files).get(surface, False)
        ok = (got == expected)
        bad += 0 if ok else 1
        print("  %-5s %s  %s  predicted=%-5s expected=%-5s  (%s)"
              % ("OK" if ok else "DRIFT", rev, surface, str(got).lower(),
                 str(expected).lower(), evidence))
    print()
    if bad:
        print("%d of %d predictions DRIFTED. The classifier's arming rules have moved."
              % (bad, len(SELF_TEST_CASES)))
        print("That does not make this script wrong -- re-confirm against the jobs API")
        print("(command in the module docstring) and update the pins to what it says.")
    else:
        print("All predictions still agree with the recorded CI behaviour.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Report which path-gated CI surfaces have not been armed on trunk.",
    )
    ap.add_argument("--branch", default="origin/main",
                    help="trunk ref to walk (default: origin/main)")
    ap.add_argument("--since", default=None,
                    help="report surfaces unarmed between this revision and the trunk tip")
    ap.add_argument("--surface", action="append", default=None,
                    help="restrict the report to this surface (repeatable)")
    ap.add_argument("--limit", type=int, default=600,
                    help="how many trunk commits to walk back (default: 600; 0 = all)")
    ap.add_argument("--self-test", action="store_true",
                    help="re-check the recorded ground-truth predictions and exit")
    args = ap.parse_args()

    root = repo_root()
    if not (root / CLASSIFIER).exists():
        die("classifier not found at %s" % CLASSIFIER)
    shell = sh_exe()

    if args.self_test:
        return run_self_test(root, shell)

    surfaces = all_surfaces(root, shell)
    if args.surface:
        unknown = [s for s in args.surface if s not in surfaces]
        if unknown:
            die("unknown surface(s): %s (the classifier emits: %s)"
                % (", ".join(unknown), ", ".join(surfaces)))
        surfaces = list(args.surface)

    if args.since:
        probe = subprocess.run(
            ["git", "rev-parse", "--verify", "--quiet", args.since + "^{commit}"],
            cwd=str(root), capture_output=True, text=True,
        )
        if probe.returncode != 0:
            die("--since revision %r does not resolve" % args.since)
        rev_range = "%s..%s" % (args.since, args.branch)
        limit = 0
    else:
        rev_range = args.branch
        limit = args.limit

    ignored = push_ignored_globs(root)
    now = _dt.datetime.now(_dt.timezone.utc).astimezone()

    pending = set(surfaces)
    found: "dict[str, tuple[str, str, str, int]]" = {}
    walked = 0
    skipped_no_run = 0

    for sha, iso, subject, files in walk_trunk(root, rev_range, limit):
        walked += 1
        if not files:
            continue
        if all(path_is_ignored(f, ignored) for f in files):
            skipped_no_run += 1
            continue
        armed = classify(root, shell, files)
        for surf in list(pending):
            if armed.get(surf):
                found[surf] = (sha, iso, subject, walked - 1)
                pending.discard(surf)
        if not pending:
            break

    scope = ("%s..%s" % (args.since, args.branch)) if args.since else (
        "%s (newest %d commits)" % (args.branch, walked))
    print("Trunk grading report -- which path-gated surfaces has trunk armed, and when?")
    print("scope: %s   walked: %d commits   skipped as no-run (paths-ignore): %d"
          % (scope, walked, skipped_no_run))
    print("This is the SCHEDULING question. A surface last armed long ago has not been")
    print("GRADED since, however green the check column looks. It says nothing about")
    print("whether the gate passed -- for red gates see the nightly tracking issue")
    print("maintained by .github/scripts/nightly_failure_alert.py.")
    print()

    width = max(len(s) for s in surfaces)
    print("%-*s  %-12s  %-25s  %-10s  %s" % (width, "SURFACE", "LAST ARMED", "WHEN", "AGE", "COMMITS AGO"))
    print("-" * (width + 66))

    stale = []
    for surf in surfaces:
        if surf in found:
            sha, iso, _subject, ago = found[surf]
            print("%-*s  %-12s  %-25s  %-10s  %d"
                  % (width, surf, sha[:12], iso, age_of(iso, now), ago))
        else:
            label = "NOT since %s" % args.since if args.since else "NOT in window"
            print("%-*s  %-12s  %-25s  %-10s  %s"
                  % (width, surf, "--", label, ">" + str(walked), ">" + str(walked)))
            stale.append(surf)

    if stale:
        print()
        print("UNARMED across the whole scope (%d): %s" % (len(stale), ", ".join(stale)))
        print("Each is a QUESTION, not a verdict -- this walks commits where CI")
        print("classifies pushes, which over-reports staleness. Confirm one against the")
        print("jobs API with the command in this script's module docstring.")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())
