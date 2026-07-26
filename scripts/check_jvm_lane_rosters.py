#!/usr/bin/env python3
"""JVM artefact roster <-> test.yml required-job bijection (rf2-as6bg).

WHY THIS EXISTS.  Every JVM artefact in this repo is supposed to be reachable
two ways: from a developer's machine, via one of the two roster scripts

    scripts/test-jvm-implementation.sh      the implementation/ artefacts
    scripts/test-jvm-tools.sh               the tools/ artefacts

and at PR time, via a job in `.github/workflows/test.yml` that runs
`clojure -M:test` in that artefact's directory AND is listed in the
`all-required-passed` aggregator's `needs:` (a job absent from that list is
advisory, whatever its own gate says).

Nothing asserted that the two agreed, and they twice did not.  `tools/xray`,
`tools/machines-viz`, `tools/story`, ... were on the local roster while
`tools/machines-viz` and `tools/testbed-support` had no CI job at all — 632 +
32 tests whose only lane was somebody remembering to run the script
(rf2-as6bg).  Conversely `tools/template` had a CI job and sat on no roster, so
a template-only change had no local JVM lane.  Both directions were repaired by
hand; neither repair left anything behind that would notice the next one.

The test-lane bijection gate (`check_test_lane_bijection.py`, rf2-4hc9p) cannot
see this class.  Its universe is files on a lane's classpath, and it READS the
rosters to discover the JVM lanes — so an artefact missing from a roster is not
a violation to it, it is simply not a lane.  The blind spot is structural: the
gate that finds orphan FILES takes the roster as ground truth, so the roster is
exactly what it cannot check.  This gate checks the roster.

    scripts/check_jvm_lane_rosters.py             check the repo
    scripts/check_jvm_lane_rosters.py --verbose   ... and list every pairing
    scripts/check_jvm_lane_rosters.py --self-test prove the gate fires

THE TWO RULES

  R1  ROSTERED, NOT GATED.  Every artefact on a roster script must be run by a
      required test.yml job -- a job whose `defaults.run.working-directory` is
      that artefact, whose body invokes `clojure -M:test`, and whose id appears
      in `all-required-passed`'s `needs:`.

  R2  GATED, NOT ROSTERED.  Every required test.yml job that runs
      `clojure -M:test` in a directory carrying a `deps.edn` must have that
      directory on one of the rosters, so the suite has a local lane too.

Both rosters and the workflow are READ, never listed here: a roster inside this
script would be the staleness class the gate exists to catch.  The only listed
thing is the baseline below, and every entry in it names the bead that clears
it.

THE BASELINE, now EMPTY (rf2-ftmh0).  Two drifts predated this gate, both
outside the fence of the change that added it, and both were recorded rather
than fixed so the gate could land ahead of its companion work — the same
pattern as `check_skill_mcp_drift.py`'s `_BASELINE`.  Both are now repaired on
main, so the rule the gate states is unqualified: every roster entry is
required in CI and every required JVM job is rostered, with no exceptions.
Re-populating `BASELINE` suppresses a real violation, so an entry must name the
bead that clears it and be trimmed when that bead lands.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

ROSTER_SCRIPTS = (
    "scripts/test-jvm-implementation.sh",
    "scripts/test-jvm-tools.sh",
)
WORKFLOW = ".github/workflows/test.yml"
AGGREGATOR_JOB = "all-required-passed"
JVM_RUNNER = "clojure -M:test"

# artefact path -> why it is not yet a violation.  Trim on resolution.
#
# EMPTY, and meant to stay that way (rf2-ftmh0).  The two entries this gate
# shipped with are both fixed on main: `implementation/adapters/test-react` now
# has the required `jvm-adapters-test-react` job, and
# `implementation/freehand/scaffold-smoke` is on
# `scripts/test-jvm-implementation.sh`.  Adding an entry back is a deliberate
# act -- it suppresses a real violation -- so it must name the bead that clears
# it and be trimmed when that bead lands.  A baseline that never empties has
# become a permanent exemption, which is the failure mode the repo already
# fought over the Tags-column suppression set (rf2-zk1xu).
BASELINE: dict[str, str] = {}

ARRAY_OPEN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*=\(\s*$")
JOB_ID = re.compile(r"^  ([A-Za-z0-9_][A-Za-z0-9_.-]*):\s*$")


# ---------------------------------------------------------------------------
# parsing


def parse_roster(text: str) -> list[str]:
    """Artefact paths from a `name=( ... )` bash array, comments stripped.

    The rosters are plain path lists carrying heavy per-entry commentary, so a
    whole-line or trailing `#` is the only quoting concern.
    """
    artefacts: list[str] = []
    inside = False
    for line in text.splitlines():
        if not inside:
            if ARRAY_OPEN.match(line):
                inside = True
            continue
        if line.strip() == ")":
            inside = False
            continue
        artefacts.extend(line.split("#", 1)[0].split())
    return artefacts


def parse_jobs(text: str) -> dict[str, list[str]]:
    """Job id -> its body lines, for every job under the workflow's `jobs:`."""
    lines = text.splitlines()
    try:
        start = next(i for i, line in enumerate(lines) if line.rstrip() == "jobs:")
    except StopIteration:
        raise SystemExit(f"FAIL {WORKFLOW}: no top-level `jobs:` key")

    jobs: dict[str, list[str]] = {}
    current: str | None = None
    body: list[str] = []
    for line in lines[start + 1 :]:
        match = JOB_ID.match(line)
        if match:
            if current is not None:
                jobs[current] = body
            current, body = match.group(1), []
        elif current is not None:
            body.append(line)
    if current is not None:
        jobs[current] = body
    return jobs


def default_workdir(body: list[str]) -> str | None:
    """The job's `defaults.run.working-directory`, i.e. the one before `steps:`.

    A per-STEP `working-directory:` is deliberately not read: it says where one
    step ran, not what the job is about, and several jobs set it to
    `implementation` for an `npm ci` while testing an artefact elsewhere.
    """
    for line in body:
        stripped = line.strip()
        if stripped == "steps:":
            return None
        if stripped.startswith("working-directory:"):
            return stripped.split(":", 1)[1].strip()
    return None


def parse_needs(body: list[str]) -> list[str]:
    """The `needs:` list of a job body."""
    needs: list[str] = []
    inside = False
    for line in body:
        stripped = line.strip()
        if stripped == "needs:":
            inside = True
            continue
        if not inside:
            continue
        if stripped.startswith("- "):
            needs.append(stripped[2:].strip())
        elif stripped and not stripped.startswith("#"):
            break
    return needs


# ---------------------------------------------------------------------------
# rules


def runs_jvm_tests(body: list[str]) -> bool:
    """Does this job body actually INVOKE the JVM runner?

    Prose is excluded, and it has to be: a `#` comment block sits between two
    jobs and parses as a tail of the FIRST one, so the comment introducing the
    tools section ("... (clojure -M:test) and out-of-consolidated-tree ...")
    read as the preceding browser-smoke job running the JVM runner in
    `implementation/`.  `name:` is excluded for the same reason -- every one of
    these jobs is titled `JVM <artefact> (clojure -M:test)`, so a title alone
    would be evidence of nothing.  What is left is `run:`, in both its
    single-line and block forms.
    """
    for line in body:
        stripped = line.strip()
        if stripped.startswith("#") or stripped.lstrip("- ").startswith("name:"):
            continue
        if JVM_RUNNER in stripped:
            return True
    return False


def jvm_jobs(jobs: dict[str, list[str]]) -> dict[str, str]:
    """Job id -> artefact directory, for jobs that run the JVM test runner."""
    found: dict[str, str] = {}
    for job_id, body in jobs.items():
        if not runs_jvm_tests(body):
            continue
        workdir = default_workdir(body)
        if workdir and "${{" not in workdir:
            found[job_id] = workdir
    return found


def check(
    rosters: dict[str, list[str]],
    jobs: dict[str, list[str]],
    has_deps_edn=lambda path: (REPO_ROOT / path / "deps.edn").is_file(),
) -> tuple[list[str], list[str]]:
    """Return (failures, pairings). Empty failures means the bijection holds."""
    if AGGREGATOR_JOB not in jobs:
        return ([f"{WORKFLOW}: no `{AGGREGATOR_JOB}` job -- nothing is required"], [])

    required = set(parse_needs(jobs[AGGREGATOR_JOB]))
    gated = jvm_jobs(jobs)
    rostered = {path: script for script, paths in rosters.items() for path in paths}

    failures: list[str] = []
    pairings: list[str] = []

    # R1 -- rostered, not gated.
    for path, script in sorted(rostered.items()):
        matches = sorted(job for job, wd in gated.items() if wd == path)
        req = [job for job in matches if job in required]
        if req:
            pairings.append(f"  {path:<45} {script} <-> {', '.join(req)}")
        elif path in BASELINE:
            pairings.append(f"  {path:<45} BASELINED -- {BASELINE[path]}")
        elif matches:
            failures.append(
                f"R1 {path}: on {script} and gated by {', '.join(matches)}, but no "
                f"such job is in {AGGREGATOR_JOB}'s needs: -- the job is advisory, "
                f"so a merge can break the suite without ever running it"
            )
        else:
            failures.append(
                f"R1 {path}: on {script}, but no {WORKFLOW} job runs "
                f"`{JVM_RUNNER}` there -- its only lane is somebody remembering "
                f"to run the script"
            )

    # R2 -- gated, not rostered.
    for job_id, path in sorted(gated.items()):
        if path in rostered or job_id not in required:
            continue
        if path in BASELINE:
            pairings.append(f"  {path:<45} BASELINED -- {BASELINE[path]}")
            continue
        if not has_deps_edn(path):
            continue
        failures.append(
            f"R2 {path}: required job `{job_id}` runs `{JVM_RUNNER}` there, but "
            f"the directory is on neither roster -- a change to it has no local "
            f"JVM lane at all"
        )

    return failures, pairings


# ---------------------------------------------------------------------------
# self-test


SELF_TEST_WORKFLOW = """\
name: whatever
jobs:
  detect:
    runs-on: ubuntu-latest
  jvm-good:
    defaults:
      run:
        working-directory: tools/good
    steps:
      - run: clojure -M:test
  jvm-advisory:
    defaults:
      run:
        working-directory: tools/advisory
    steps:
      - run: clojure -M:test
  jvm-unrostered:
    defaults:
      run:
        working-directory: tools/unrostered
    steps:
      - working-directory: implementation
        run: npm ci
      - run: clojure -M:test
  all-required-passed:
    needs:
      - detect
      - jvm-good
      - jvm-unrostered
    steps:
      - run: echo ok
"""

SELF_TEST_ROSTER = """\
#!/usr/bin/env bash
set -euo pipefail

tools=(
  tools/good
  # a comment that mentions tools/decoy and must not be read as an entry
  tools/advisory
  tools/ungated
)

for tool in "${tools[@]}"; do
  (cd "$tool" && clojure -M:test)
done
"""


def self_test(verbose: bool) -> int:
    jobs = parse_jobs(SELF_TEST_WORKFLOW)
    roster = parse_roster(SELF_TEST_ROSTER)
    cases: list[tuple[str, bool]] = []

    def expect(label: str, ok: bool) -> None:
        cases.append((label, ok))

    expect(
        "roster parser reads entries and ignores comment prose",
        roster == ["tools/good", "tools/advisory", "tools/ungated"],
    )
    expect("job parser finds every job", set(jobs) == {
        "detect", "jvm-good", "jvm-advisory", "jvm-unrostered", "all-required-passed",
    })
    expect(
        "default workdir ignores per-step working-directory",
        default_workdir(jobs["jvm-unrostered"]) == "tools/unrostered",
    )
    expect(
        "needs parser reads the aggregator list",
        parse_needs(jobs["all-required-passed"]) == ["detect", "jvm-good", "jvm-unrostered"],
    )

    failures, _ = check({"roster.sh": roster}, jobs, has_deps_edn=lambda _: True)
    expect("a rostered artefact with no job fires R1", any(
        f.startswith("R1 tools/ungated") for f in failures))
    expect("a rostered artefact whose job is advisory fires R1", any(
        f.startswith("R1 tools/advisory") and AGGREGATOR_JOB in f for f in failures))
    expect("a required job on no roster fires R2", any(
        f.startswith("R2 tools/unrostered") for f in failures))
    expect("a correctly paired artefact fires nothing", not any(
        "tools/good" in f for f in failures))
    expect("the gate fires exactly three times on the fixture", len(failures) == 3)

    ok = all(passed for _, passed in cases)
    for label, passed in cases:
        if verbose or not passed:
            print(f"  {'ok  ' if passed else 'FAIL'} {label}")
    print(
        f"{'PASS' if ok else 'FAIL'} check_jvm_lane_rosters self-test: "
        f"{sum(1 for _, p in cases if p)}/{len(cases)} cases"
    )
    return 0 if ok else 1


# ---------------------------------------------------------------------------


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--verbose", action="store_true", help="list every pairing")
    parser.add_argument("--self-test", action="store_true", help="prove the gate fires")
    args = parser.parse_args()

    if args.self_test:
        return self_test(args.verbose)

    rosters = {
        script: parse_roster((REPO_ROOT / script).read_text(encoding="utf-8"))
        for script in ROSTER_SCRIPTS
    }
    jobs = parse_jobs((REPO_ROOT / WORKFLOW).read_text(encoding="utf-8"))
    failures, pairings = check(rosters, jobs)

    if args.verbose:
        for line in pairings:
            print(line)

    total = sum(len(paths) for paths in rosters.values())
    if failures:
        print(f"FAIL JVM roster <-> CI bijection: {len(failures)} violation(s)")
        for failure in failures:
            print(f"  {failure}")
        return 1

    print(
        f"PASS JVM roster <-> CI bijection: {total} rostered artefacts, "
        f"{len(BASELINE)} baselined, every roster entry required in CI and "
        f"every required JVM job rostered"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
