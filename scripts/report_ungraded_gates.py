#!/usr/bin/env python3
r"""Which path-gated CI surfaces have not been armed on trunk, and for how long?

rf2-5dihy.  A REPORT, not a gate.  It exits 0 on every answer it can compute and
is deliberately unwireable into CI: there is no failing exit status to key on.
(`--self-test` is the one mode that can fail -- see Exit codes at the foot.)

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

EVERY ROW IS A CANDIDATE, NEVER AN OBSERVATION (rf2-xbig3)
-----------------------------------------------------------
CI classifies a PUSH -- the range `github.event.before..github.sha`, which
`test.yml` passes as CHANGED_SURFACES_BASE_REF.  This walks COMMITS and
classifies each one's own parent diff.  On a multi-commit push those are
different questions, and NOTHING IN A GIT CHECKOUT RECORDS WHERE PUSHES BEGAN
AND ENDED, so this script cannot answer the one CI asks.  Every row is therefore
an INFERENCE about a commit -- `commit` in the BASIS column -- and never an
observation of a push.

An earlier version of this docstring claimed the two differ only in the safe
direction: "a commit's own diff is a subset of its push's diff, so a surface the
push armed can read unarmed here ... a false alarm, not a false all-clear."
THAT CLAIM IS FALSE, AND IT IS BACKWARDS.  Changes that CANCEL within one push
leave the push diff while remaining in both parents' diffs.  The counterexample
`--self-test` now builds and runs:

    baseline   contains implementation/scripts/check-story-static.cjs
    A          edits it
    B          restores it byte-for-byte, and changes bench/fresco/... only

The push diff `baseline..B` is the bench path alone, which arms nothing, so CI
did NOT schedule `story_static_gate`.  Both A's and B's parent diffs contain the
gate file and arm it.  The pre-fix report printed B as LAST ARMED / COMMITS AGO
0 with no caveat, rendered identically to a genuine single-commit arming -- a
FALSE ALL-CLEAR, inside the report written to expose exactly such a gap.

THE UNCERTAINTY RUNS BOTH WAYS, BY TWO DIFFERENT MECHANISMS
------------------------------------------------------------
Toward a FALSE ALL-CLEAR (over-crediting) -- the commit/push mismatch above, and
it runs ONLY this way, which is why the old claim was not merely unproven:

    A file appears in an aggregate diff only if some commit in the range
    touched it, so the push's file set is a SUBSET of the union of its commits'
    file sets -- the containment is the other way round.  The classifier only
    ever turns surfaces ON as it walks paths (measured: 211 `=true` assignments
    and no disarming assignment after its init block; classify(A + B) ==
    classify(A) | classify(B) on 45 of 45 path pairs), so it is monotone.
    Monotone over a superset: every surface a push armed is armed by at least
    one of that push's own commits, and the walk credits the newest such commit.
    It cannot MISS a push's arming.  It can, and does, credit arming the push
    never had.

Toward a FALSE ALARM (over-reporting staleness) -- two other mechanisms, which
the old text folded into the first and so mislabelled:

  * `paths-ignore` modelling.  `TESTING.md` is a `mark_all` trigger, so a
    TESTING.md-only push would otherwise be credited with arming every surface
    while in fact running no workflow at all.  `path_is_ignored` matches
    generously, so it can skip a commit CI would have run.
  * Window truncation.  `--limit` and `--since` bound the walk, so a surface
    last armed before the window reads as never armed.

WHAT IS DONE ABOUT IT, AND WHAT DELIBERATELY IS NOT
----------------------------------------------------
Two things, both offline and both cheap:

  * EVERY ROW IS LABELLED.  BASIS reads `commit`: inferred from that commit's
    own parent diff.  There is no `push` basis, because there is nothing offline
    to read one from -- see below.  The caveat prints on EVERY run, the
    all-green one included, which is precisely where a false all-clear lands.
  * AGGREGATION-FRAGILE ROWS ARE FLAGGED `commit!`.  For each credited commit C
    the report re-classifies the aggregate `C~k..C` for k = 2..`--probe-depth`.
    If some k does not arm the surface, the credit is REFUTED for a push of that
    shape.  One classifier call per (commit, k), memoised, ~40ms each.  It
    catches the counterexample above at k=2.

    A flag is not proof the credit is wrong: C may have been pushed alone, in
    which case its own diff IS the push diff.  It says a plausible push shape
    exists in which the surface was not armed.  Absence of a flag is likewise
    not proof the credit is right -- the real push may be deeper than
    `--probe-depth`.

WHY THERE IS NO `push` BASIS: PUSH BOUNDARIES ARE NOT IN A CHECKOUT
--------------------------------------------------------------------
The tempting source is `git reflog show origin/main`, and it does not work.
Measured 2026-09-21:

  * Its entries are FETCH boundaries, not push boundaries.  2 of 39 steps
    spanned more than one trunk commit, both labelled `fetch origin main:
    fast-forward` -- one fetch coalescing several remote pushes, which is the
    same aggregation error again, now wearing an authoritative label.
  * A FRESH CLONE HAS NONE.  A clone of a 4-commit trunk reported 0
    `origin/main` reflog entries.  That is what CI's `actions/checkout` and
    every other maintainer has, so a reflog-based report would credit nothing
    and call every surface unarmed -- a different wrong answer, not a right one.
  * It is per-clone local state under the git COMMON dir, shared between one
    clone's worktrees and present in no other clone.  It is also pruned.

The honest sources are the Actions event payload (`github.event.before`) and the
API, and this item asks for neither: the report's whole value is that it is
free, offline and read-only.  If you DO know a push's boundaries you do not need
this script -- ask the classifier the same thing CI asks it:

    sh .github/scripts/report-changed-surfaces.sh \
       $(git diff --no-renames --name-only <before> <after>)

Treat every reported gap, and every reported arming, as a QUESTION.

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
script.  They pin the SINGLE-COMMIT case and establish nothing whatever about
push-boundary equivalence -- all three passed alongside the counterexample above,
which is why `--self-test` also builds that counterexample as a fixture (case 4)
and asserts both halves of it, plus a direct single-commit positive control.

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
    python scripts/report_ungraded_gates.py --probe-depth 20   # deeper fragility probe
    python scripts/report_ungraded_gates.py --probe-depth 0    # disable it
    python scripts/report_ungraded_gates.py --self-test

Exit codes.  THE REPORT: 0 = reported, whatever it found; 2 = invocation / setup
error; deliberately no 1, so nothing can key a gate on what it found.
`--self-test` is the one exception and exits 1 when a pin has drifted or a
regression check has failed -- a self-check that cannot fail is the defect this
script exists to report, one level down.
"""

from __future__ import annotations

import argparse
import datetime as _dt
import fnmatch
import os
import shutil
import stat
import subprocess
import sys
import tempfile
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


def aggregate_arms(root: Path, shell: str, sha: str, depth: int,
                   memo: "dict[tuple[str, int], dict[str, bool] | None]") -> "dict[str, bool] | None":
    """Classify `<sha>~depth..<sha>` -- "had this commit arrived in a push of
    `depth` commits ending here, what would CI have armed?"

    None means the range does not exist (history shorter than `depth`), which is
    not the same as "armed nothing" and must not be read as a refutation.
    """
    key = (sha, depth)
    if key in memo:
        return memo[key]
    base = subprocess.run(
        ["git", "rev-parse", "--verify", "--quiet", "%s~%d^{commit}" % (sha, depth)],
        cwd=str(root), capture_output=True, text=True,
    )
    if base.returncode != 0:
        memo[key] = None
        return None
    diff = subprocess.run(
        ["git", "diff", "--no-renames", "--name-only", base.stdout.strip(), sha],
        cwd=str(root), capture_output=True, text=True,
    )
    if diff.returncode != 0:
        memo[key] = None
        return None
    files = [f for f in diff.stdout.replace("\r\n", "\n").split("\n") if f.strip()]
    result = classify(root, shell, files) if files else {}
    memo[key] = result
    return result


def cancellation_depth(root: Path, shell: str, sha: str, surface: str, max_depth: int,
                       memo) -> "int | None":
    """Smallest k >= 2 for which the aggregate `<sha>~k..<sha>` does NOT arm
    `surface`, or None if it survives every probed aggregation.

    A hit REFUTES the commit-level credit for a push of that shape: the edit
    cancelled against a neighbour, so CI -- which classifies the whole push --
    never scheduled the surface.  It does not refute a push of ONE commit, where
    the commit's own diff IS the push diff, so a hit is a doubt with a shape
    rather than a verdict.  See the docstring section on rf2-xbig3.
    """
    for k in range(2, max_depth + 1):
        armed = aggregate_arms(root, shell, sha, k, memo)
        if armed is None:
            return None  # history exhausted; absence of evidence, not evidence
        if not armed.get(surface):
            return k
    return None


def survey(root: Path, shell: str, surfaces: "list[str]", rev_range: str, limit: int,
           ignored: "list[str]"):
    """Walk trunk newest-first, crediting each surface to the newest commit whose
    OWN parent diff arms it.  Returns (found, pending, walked, skipped_no_run).

    `found[surface] = (sha, iso, subject, commits_ago)` is a CANDIDATE, not an
    observation -- see the module docstring.  Factored out of `main` so that
    `--self-test` exercises this exact code path against its fixture rather than
    a re-implementation of it.
    """
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
    return found, pending, walked, skipped_no_run


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


GATE_PATH = "implementation/scripts/check-story-static.cjs"
BENCH_PATH = "bench/fresco/audit-fixture.txt"
GATE_X = "// baseline contents of the story static gate\nmodule.exports = { v: 1 };\n"
GATE_Y = "// EDITED by commit A\nmodule.exports = { v: 2 };\n"
GATE_Z = "// EDITED by commit D, the positive control\nmodule.exports = { v: 3 };\n"


def _rmtree(path: str) -> None:
    """Best effort; a cleanup failure must never fail the self-test."""
    for base, dirs, files in os.walk(path):
        for name in dirs + files:
            try:
                os.chmod(os.path.join(base, name), stat.S_IWRITE)
            except OSError:
                pass
    shutil.rmtree(path, ignore_errors=True)


def _fx_git(repo: str, *args: str) -> "subprocess.CompletedProcess[str]":
    proc = subprocess.run(["git"] + list(args), cwd=repo, capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError("git %s -> %d: %s" % (" ".join(args), proc.returncode, proc.stderr))
    return proc


def _fx_write(repo: str, rel: str, content: str) -> None:
    dest = os.path.join(repo, *rel.split("/"))
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest, "wb") as fh:
        fh.write(content.encode("utf-8"))


def build_cancellation_fixture(root: Path) -> "tuple[str, str, str, str, str]":
    """An isolated repo reproducing rf2-xbig3: an edit and its byte-for-byte
    restoration inside one push, plus an unrelated surviving change.

    Carries the LIVE classifier and the LIVE test.yml, so the case tracks this
    repository rather than a snapshot of it.  Offline, stdlib only.

        baseline  gate script present
        A         edits it
        B         restores it exactly, changes only an unrelated bench path
        D         positive control: a direct single-commit gate-path change
    """
    repo = tempfile.mkdtemp(prefix="rug-selftest-")
    _fx_git(repo, "init", "-q", "-b", "main")
    for rel in (str(CLASSIFIER).replace(os.sep, "/"), str(TEST_WORKFLOW).replace(os.sep, "/")):
        dst = os.path.join(repo, *rel.split("/"))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copyfile(str(root / rel), dst)

    def commit(msg: str) -> str:
        _fx_git(repo, "add", "-A")
        _fx_git(repo, "-c", "user.name=rug-selftest", "-c", "user.email=rug@invalid",
                "-c", "commit.gpgsign=false", "commit", "-q", "-m", msg)
        return _fx_git(repo, "rev-parse", "HEAD").stdout.strip()

    _fx_write(repo, GATE_PATH, GATE_X)
    _fx_write(repo, "README.md", "fixture\n")
    baseline = commit("baseline")
    _fx_write(repo, GATE_PATH, GATE_Y)
    a = commit("A: edit the story static gate script")
    _fx_write(repo, GATE_PATH, GATE_X)
    _fx_write(repo, BENCH_PATH, "unrelated surviving change\n")
    b = commit("B: restore the gate script; change only an unrelated bench path")
    _fx_write(repo, GATE_PATH, GATE_Z)
    d = commit("D: positive control, direct single-commit gate-path change")
    return repo, baseline, a, b, d


def _fx_diff_files(repo: str, lo: str, hi: str) -> "list[str]":
    out = _fx_git(repo, "diff", "--no-renames", "--name-only", lo, hi).stdout
    return [f for f in out.replace("\r\n", "\n").split("\n") if f.strip()]


def run_cancellation_case(root: Path, shell: str) -> int:
    """rf2-xbig3 regression: an edit cancelled inside one push must not be
    reported as confirmed arming, and a genuine single-commit arming must not be
    flagged.  BOTH halves are the test -- a fix that flagged every row would
    satisfy the first and fail the second.
    """
    surface = "story_static_gate"
    print("  CASE 4 -- edit-and-restore inside one push (rf2-xbig3)")
    try:
        repo, baseline, a, b, d = build_cancellation_fixture(root)
    except (OSError, RuntimeError) as exc:
        print("    SKIP  could not build the fixture: %s" % exc)
        return 0
    bad = 0
    try:
        fx = Path(repo)
        ignored = push_ignored_globs(fx)

        checks: "list[tuple[str, bool, str]]" = []

        # The restore must be byte-for-byte, or the fixture is not the case.
        blob_base = _fx_git(repo, "rev-parse", "%s:%s" % (baseline, GATE_PATH)).stdout.strip()
        blob_b = _fx_git(repo, "rev-parse", "%s:%s" % (b, GATE_PATH)).stdout.strip()
        blob_a = _fx_git(repo, "rev-parse", "%s:%s" % (a, GATE_PATH)).stdout.strip()
        checks.append(("restore is byte-for-byte (blob at B == blob at baseline != A)",
                       blob_base == blob_b != blob_a, "%s/%s/%s"
                       % (blob_base[:8], blob_a[:8], blob_b[:8])))

        # The push CI would classify: before=baseline, after=B.
        push_files = _fx_diff_files(repo, baseline, b)
        push_armed = classify(fx, shell, push_files).get(surface, False)
        checks.append(("aggregate baseline..B does NOT arm %s" % surface,
                       push_armed is False, "files=%r" % (push_files,)))

        # ...while BOTH intermediate parent diffs do. That is the trap.
        for name, rev in (("A", a), ("B", b)):
            got = classify(fx, shell, _fx_diff_files(repo, rev + "^", rev)).get(surface, False)
            checks.append(("parent diff of %s DOES arm %s (the trap)" % (name, surface),
                           got is True, "armed=%s" % got))

        # The walk still produces a candidate -- it is meant to, and B is it.
        found, _pending, _walked, _skipped = survey(
            fx, shell, [surface], "%s..%s" % (baseline, b), 0, ignored)
        credited = found.get(surface, (None,))[0]
        checks.append(("walk credits B as the candidate", credited == b,
                       "credited=%s" % (credited[:10] if credited else None)))

        # THE REGRESSION. Pre-fix this row printed LAST ARMED / COMMITS AGO 0
        # with no caveat, rendered identically to a real arming.
        memo: "dict[tuple[str, int], dict[str, bool] | None]" = {}
        k = cancellation_depth(fx, shell, b, surface, 8, memo) if credited else None
        checks.append(("candidate B is flagged aggregation-fragile at k=2", k == 2,
                       "cancellation_depth=%s" % k))

        # POSITIVE CONTROL, same fixture: a direct single-commit gate-path change
        # must classify true, be credited, and NOT be flagged.
        ctl_files = _fx_diff_files(repo, b, d)
        ctl_armed = classify(fx, shell, ctl_files).get(surface, False)
        checks.append(("control: aggregate B..D DOES arm %s" % surface,
                       ctl_armed is True, "files=%r" % (ctl_files,)))
        found_c, _p, _w, _s = survey(fx, shell, [surface], "%s..%s" % (b, d), 0, ignored)
        credited_c = found_c.get(surface, (None,))[0]
        checks.append(("control: walk credits D", credited_c == d,
                       "credited=%s" % (credited_c[:10] if credited_c else None)))
        memo_c: "dict[tuple[str, int], dict[str, bool] | None]" = {}
        k_c = cancellation_depth(fx, shell, d, surface, 8, memo_c) if credited_c else "n/a"
        checks.append(("control: D is NOT flagged", k_c is None,
                       "cancellation_depth=%s" % k_c))

        for label, ok, detail in checks:
            bad += 0 if ok else 1
            print("    %-5s %s  [%s]" % ("OK" if ok else "FAIL", label, detail))
    finally:
        _rmtree(repo)
    return bad


def run_self_test(root: Path, shell: str) -> int:
    print("SELF-TEST -- three settled trunk pushes, predictions vs the CI record")
    print("(see the module docstring for the step-level conclusions these pin)")
    print("These pin the SINGLE-COMMIT case only. Case 4 below pins the push-boundary")
    print("counterexample they cannot see -- all three passed alongside it.\n")
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

    print()
    regressions = run_cancellation_case(root, shell)
    print()
    if regressions:
        print("CASE 4 FAILED (%d checks). The push-boundary correction has regressed:"
              % regressions)
        print("a commit-level candidate is again being reported as confirmed arming, or")
        print("a genuine single-commit arming is being flagged. See rf2-xbig3.")
    else:
        print("CASE 4 passes: the cancelled edit is flagged, the genuine arming is not.")
    print()
    print("A green self-test is NECESSARY AND NOWHERE NEAR SUFFICIENT. Cases 1-3 are")
    print("single-commit pins and case 4 is one synthetic push shape; none of them")
    print("establishes that a reported candidate matches the push CI actually ran.")
    # `--self-test` is the ONE mode with a failing status. The REPORT still exits 0
    # on every answer it can compute, so nothing can key a gate on what it found --
    # but a self-check that cannot fail is the very defect this script exists to
    # report, one level down.
    return 1 if (bad or regressions) else 0


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
    ap.add_argument("--probe-depth", type=int, default=8,
                    help="how deep to probe a candidate's arming for cancellation "
                         "against its neighbours (default: 8; 0 or 1 disables)")
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

    found, _pending, walked, skipped_no_run = survey(
        root, shell, surfaces, rev_range, limit, ignored)

    # Fragility probe. Memoised on (sha, depth), so surfaces credited to the same
    # commit -- the common case, since a `mark_all` trigger credits nearly all of
    # them at once -- share one classification per depth.
    memo: "dict[tuple[str, int], dict[str, bool] | None]" = {}
    cancelled: "dict[str, int]" = {}
    if args.probe_depth >= 2:
        for surf, (sha, _iso, _subject, _ago) in found.items():
            k = cancellation_depth(root, shell, sha, surf, args.probe_depth, memo)
            if k is not None:
                cancelled[surf] = k

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
    print("EVERY ROW IS A CANDIDATE, NOT AN OBSERVATION. CI classifies a PUSH; this walks")
    print("COMMITS, and a checkout records no push boundaries. BASIS `commit` means")
    print("inferred from that commit's own parent diff. There is no `push` basis offline.")
    print()

    width = max(len(s) for s in surfaces)
    print("%-*s  %-12s  %-25s  %-9s  %-11s  %s"
          % (width, "SURFACE", "CANDIDATE", "WHEN", "AGE", "COMMITS AGO", "BASIS"))
    print("-" * (width + 72))

    stale = []
    for surf in surfaces:
        if surf in found:
            sha, iso, _subject, ago = found[surf]
            basis = "commit!" if surf in cancelled else "commit"
            print("%-*s  %-12s  %-25s  %-9s  %-11d  %s"
                  % (width, surf, sha[:12], iso, age_of(iso, now), ago, basis))
        else:
            label = "NOT since %s" % args.since if args.since else "NOT in window"
            print("%-*s  %-12s  %-25s  %-9s  %-11s  %s"
                  % (width, surf, "--", label, ">" + str(walked), ">" + str(walked), "--"))
            stale.append(surf)

    if cancelled:
        print()
        print("AGGREGATION-FRAGILE (%d) -- `commit!` above. The candidate's arming edit does"
              % len(cancelled))
        print("NOT survive being aggregated with its neighbours, so had it arrived in a push")
        print("that also contained them, CI would have classified that push as NOT arming it:")
        for surf in sorted(cancelled):
            sha = found[surf][0]
            k = cancelled[surf]
            print("  %-*s  vanishes over %s~%d..%s  (a push of %d commits)"
                  % (width, surf, sha[:12], k, sha[:12], k))
        print("Not a verdict either way: a commit pushed ALONE has its own diff as the push")
        print("diff, so the credit may still stand. It is a doubt with a shape.")

    if stale:
        print()
        print("UNARMED across the whole scope (%d): %s" % (len(stale), ", ".join(stale)))

    print()
    print("HOW THIS CAN BE WRONG, IN BOTH DIRECTIONS -- read before acting on any row:")
    print("  FALSE ALL-CLEAR (a row that should not be here). Changes that cancel within")
    print("    one push leave the push diff but stay in each commit's parent diff, so a")
    print("    surface CI never scheduled can be credited.")
    if args.probe_depth >= 2:
        print("    `commit!` flags the cases this run could refute at depth <= %d; a deeper"
              % args.probe_depth)
        print("    push is not probed, so an unflagged row is not a cleared one.")
    else:
        print("    THE FRAGILITY PROBE IS OFF (--probe-depth %d), so NO row was checked for"
              % args.probe_depth)
        print("    this at all and no row can read `commit!`. Re-run without it.")
    print("  FALSE ALARM (a row reported unarmed that was armed). The paths-ignore match")
    print("    is deliberately generous, so a commit CI would have run can be skipped; and")
    print("    the window (--since/--limit) truncates, so arming before it is invisible.")
    print("Confirm any row against the jobs API -- command in the module docstring -- or,")
    print("when you know a push's boundaries, ask the classifier what CI asked it:")
    print("  sh .github/scripts/report-changed-surfaces.sh \\")
    print("     $(git diff --no-renames --name-only <before> <after>)")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())
