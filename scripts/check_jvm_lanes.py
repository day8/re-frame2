#!/usr/bin/env python3
"""Hold scripts/jvm-lanes.txt against the ground truth on both sides of it.

The rows file is the one list of JVM lanes. The classifier builds the legs of
test.yml's `jvm` matrix job from it, and the local rosters, the fast-PR spine,
the nightly slow loop and the test-lane bijection gate all read it. A lane
missing from the file is what none of those readers can see, so this gate
compares the rows with the two things they describe.

  THE ARTEFACTS.  Every tracked deps.edn under implementation/, tools/ or
  migration/ that defines a `:test` alias has a row, or an entry in NO_ROW
  below saying where it runs instead. Every alias a row runs is defined by
  the row's deps.edn. A row is `slow` exactly when its deps.edn defines
  `:slow-test`, and runs `test:prod-gate` exactly when its deps.edn defines
  `:prod-gate`, so a posture the artefact defines cannot go unscheduled.

  TEST.YML.  A `matrix` row needs the `jvm` matrix job, and
  `all-required-passed` must need that job. A row that names a hand-written
  job needs that job to exist, to run each of the row's aliases in the row's
  directory, to be gated on the row's predicate, and to be required. Every
  required job that runs `clojure -M:` in a directory holding a deps.edn has a
  row for that directory. Every predicate is an output of
  `detect_changed_surfaces`, or `always`.

    scripts/check_jvm_lanes.py                    check the repo
    scripts/check_jvm_lanes.py --verbose          ... and list every row
    scripts/check_jvm_lanes.py --self-test        prove each rule fires
    scripts/check_jvm_lanes.py --verify-legs      check one classification's legs

`--verify-legs` runs in test.yml's `detect_changed_surfaces` job, after the
classifier, with that step's outputs as JSON in CLASSIFIER_OUTPUTS (locally,
`--outputs FILE` takes the classifier's `key=value` lines instead). It fails
when a predicate fired and a leg of its row is missing from `jvm_legs`, or when
`jvm_legs` holds a leg that no fired row accounts for. It is a second
implementation of the leg rule, in a second language, so a defect in the
classifier's leg builder turns the run red instead of skipping a lane.

Stdlib only.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_test_lane_bijection import match_delimiter, read_map_entries, strip_edn_comments  # noqa: E402
from jvm_lanes import ROWS_FILE, RowsError, parse_rows  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parent.parent
WORKFLOW = ".github/workflows/test.yml"
AGGREGATOR = "all-required-passed"
DETECT = "detect_changed_surfaces"
MATRIX_DIR = "${{ matrix.leg.dir }}"
LANE_TREES = ("implementation/", "tools/", "migration/")

# A deps.edn with a `:test` alias and no row, and where its tests run instead.
# Each entry is a ruling, not a backlog: an artefact whose tests run nowhere
# gets a row, never an entry here.
NO_ROW = {
    "implementation": "the aggregate `:test` alias spans every artefact at once; "
                      "each artefact's own row is its lane",
    "implementation/scripts/api-manifest": "lint.yml's `api-manifest` job runs it "
                                           "beside the manifest checks it tests",
    "tools/re-frame2-pair-mcp": "its tests are CLJS and `npm test` is their verdict; "
                                "its `:test` alias deliberately refuses to run",
}

JOB_ID = re.compile(r"^  ([A-Za-z0-9_][A-Za-z0-9_.-]*):\s*$")
CLOJURE_ALIAS = re.compile(r"clojure -M:([A-Za-z0-9_.:-]+)")
DETECT_OUTPUT = re.compile(r"^      ([a-z0-9_]+): \$\{\{ steps\.detect\.outputs\.([a-z0-9_]+) \}\}\s*$")


# ---------------------------------------------------------------------------
# reading


def alias_names(deps_text: str) -> set:
    """The keys of a deps.edn's `:aliases` map, without the colon."""
    body = strip_edn_comments(deps_text)
    m = re.search(r":aliases\s*\{", body)
    if not m:
        return set()
    open_idx = m.end() - 1
    inner = body[open_idx + 1:match_delimiter(body, open_idx) - 1]
    return {key.lstrip(":") for key, _ in read_map_entries(inner)}


def parse_jobs(text: str) -> dict:
    """Job id -> its body lines, for every job under the workflow's `jobs:`."""
    lines = text.splitlines()
    try:
        start = next(i for i, line in enumerate(lines) if line.rstrip() == "jobs:")
    except StopIteration:
        return {}
    jobs, current, body = {}, None, []
    for line in lines[start + 1:]:
        m = JOB_ID.match(line)
        if m:
            if current is not None:
                jobs[current] = body
            current, body = m.group(1), []
        elif current is not None:
            body.append(line)
    if current is not None:
        jobs[current] = body
    return jobs


def default_workdir(body) -> str | None:
    """The job's `defaults.run.working-directory`: the one before `steps:`."""
    for line in body:
        stripped = line.strip()
        if stripped == "steps:":
            return None
        if stripped.startswith("working-directory:"):
            return stripped.split(":", 1)[1].strip()
    return None


def job_key(body, key: str) -> str | None:
    """A job-level scalar such as `if:`, read at exactly four spaces."""
    for line in body:
        if line.startswith(f"    {key}:"):
            return line.split(":", 1)[1].strip()
    return None


def parse_needs(body) -> list:
    needs, inside = [], False
    for line in body:
        stripped = line.strip()
        if line.startswith("    needs:"):
            value = stripped.split(":", 1)[1].strip()
            if value:
                return [v.strip() for v in value.strip("[]").split(",") if v.strip()]
            inside = True
            continue
        if not inside:
            continue
        if stripped.startswith("- "):
            needs.append(stripped[2:].strip())
        elif stripped and not stripped.startswith("#"):
            break
    return needs


def clojure_aliases(body) -> set:
    """Every alias a job body runs as `clojure -M:<alias>`, prose excluded."""
    found = set()
    for line in body:
        stripped = line.strip()
        if stripped.startswith("#") or stripped.lstrip("- ").startswith("name:"):
            continue
        for m in CLOJURE_ALIAS.finditer(stripped):
            found.add(m.group(1).strip())
    return found


def detect_outputs(jobs: dict) -> set:
    return {m.group(1) for line in jobs.get(DETECT, []) if (m := DETECT_OUTPUT.match(line))}


def tracked_deps_edn(root: Path) -> list:
    """Repo-relative paths of every tracked deps.edn in the lane trees."""
    out = subprocess.run(["git", "-C", str(root), "ls-files", "-z"],
                         capture_output=True, check=True).stdout.decode("utf-8")
    return sorted(p for p in out.split("\0")
                  if p.startswith(LANE_TREES) and p.rsplit("/", 1)[-1] == "deps.edn")


# ---------------------------------------------------------------------------
# rules


def check(root: Path, rows, workflow_text: str, deps_files) -> list:
    failures = []
    jobs = parse_jobs(workflow_text)
    required = set(parse_needs(jobs.get(AGGREGATOR, [])))
    outputs = detect_outputs(jobs)
    if not jobs:
        return [f"{WORKFLOW}: no `jobs:` parsed"]
    if AGGREGATOR not in jobs:
        failures.append(f"{WORKFLOW}: no `{AGGREGATOR}` job, so nothing is required")

    by_dir = {}
    for row in rows:
        if row.dir in by_dir:
            failures.append(f"{ROWS_FILE}:{row.line}: {row.dir} has two rows")
        by_dir[row.dir] = row

    # The artefacts -> the rows.
    for rel in deps_files:
        directory = rel.rsplit("/", 1)[0] if "/" in rel else "."
        names = alias_names((root / rel).read_text(encoding="utf-8"))
        if "test" in names and directory not in by_dir and directory not in NO_ROW:
            failures.append(
                f"{rel} defines a `:test` alias and {directory} has no row in {ROWS_FILE}: "
                f"no lane runs it. Add a row, or an entry in NO_ROW naming where it runs.")

    # The rows -> the artefacts.
    for row in rows:
        where = f"{ROWS_FILE}:{row.line} ({row.dir})"
        deps = root / row.dir / "deps.edn"
        if not deps.is_file():
            failures.append(f"{where}: no deps.edn at {row.dir}")
            continue
        names = alias_names(deps.read_text(encoding="utf-8"))
        if not row.aliases or row.aliases[0] != "test":
            failures.append(f"{where}: the aliases start with `test`, which the local rosters run")
        for alias in row.aliases:
            missing = [part for part in alias.split(":") if part not in names]
            if missing:
                failures.append(f"{where}: runs `clojure -M:{alias}`, but its deps.edn "
                                f"defines no {', '.join(':' + p for p in missing)}")
        if row.slow != ("slow-test" in names):
            failures.append(
                f"{where}: the deps.edn {'defines' if 'slow-test' in names else 'defines no'} "
                f"`:slow-test`, so the slow field must be `{'slow' if 'slow-test' in names else '-'}`")
        if ("prod-gate" in names) != ("test:prod-gate" in row.aliases):
            failures.append(
                f"{where}: the deps.edn {'defines' if 'prod-gate' in names else 'defines no'} "
                f"`:prod-gate`, so the row {'must' if 'prod-gate' in names else 'must not'} "
                f"run `test:prod-gate`")
        if row.predicate != "always" and row.predicate not in outputs:
            failures.append(f"{where}: `{row.predicate}` is not an output of {DETECT}, "
                            f"so nothing can ever fire it")

    # The rows -> test.yml.
    matrix_jobs = [j for j, body in jobs.items() if default_workdir(body) == MATRIX_DIR]
    if any(row.matrix for row in rows):
        if len(matrix_jobs) != 1:
            failures.append(f"{WORKFLOW}: expected one job running in `{MATRIX_DIR}`, "
                            f"found {matrix_jobs or 'none'}")
        elif matrix_jobs[0] not in required:
            failures.append(f"{WORKFLOW}: the matrix job `{matrix_jobs[0]}` is not in "
                            f"{AGGREGATOR}'s needs:, so every matrix leg is advisory")
    for row in rows:
        if row.matrix:
            continue
        where = f"{ROWS_FILE}:{row.line} ({row.dir})"
        body = jobs.get(row.ci)
        if body is None:
            failures.append(f"{where}: names job `{row.ci}`, which {WORKFLOW} does not have")
            continue
        if default_workdir(body) != row.dir:
            failures.append(f"{where}: job `{row.ci}` runs in {default_workdir(body)}, not {row.dir}")
        ran = clojure_aliases(body)
        for alias in row.aliases:
            if alias not in ran:
                failures.append(f"{where}: job `{row.ci}` never runs `clojure -M:{alias}`")
        gate = job_key(body, "if") or ""
        if row.predicate != "always" and f"outputs.{row.predicate} == 'true'" not in gate:
            failures.append(f"{where}: job `{row.ci}` is not gated on `{row.predicate}`, "
                            f"so the predicate this row names does not schedule it")
        if row.ci not in required:
            failures.append(f"{where}: job `{row.ci}` is not in {AGGREGATOR}'s needs:, so it is advisory")

    # test.yml -> the rows.
    for job_id in sorted(required):
        body = jobs.get(job_id, [])
        workdir = default_workdir(body)
        if not workdir or "${{" in workdir or not clojure_aliases(body):
            continue
        if workdir not in by_dir and (root / workdir / "deps.edn").is_file():
            failures.append(f"{WORKFLOW}: required job `{job_id}` runs `clojure -M:` in "
                            f"{workdir}, which has no row in {ROWS_FILE}")
    return failures


def verify_legs(rows, outputs: dict) -> list:
    """The classifier's `jvm_legs` against the rows its predicates fired."""
    problems = []
    try:
        legs = json.loads(outputs.get("jvm_legs", ""))
        got = [(leg["dir"], leg["alias"]) for leg in legs]
    except (ValueError, TypeError, KeyError) as e:
        return [f"jvm_legs is not a JSON array of {{dir, alias}} legs ({e}): "
                f"{outputs.get('jvm_legs')!r}"]
    want = []
    for row in rows:
        if row.predicate != "always" and row.predicate not in outputs:
            problems.append(f"{ROWS_FILE}:{row.line}: the classifier emitted no "
                            f"`{row.predicate}` output")
            continue
        fired = row.predicate == "always" or outputs[row.predicate] == "true"
        if row.matrix and fired:
            want.extend((row.dir, alias) for alias in row.aliases)
    for leg in want:
        if leg not in got:
            problems.append(f"{leg[0]} ({leg[1]}): its predicate fired and the leg is "
                            f"missing from jvm_legs")
    for leg in got:
        if leg not in want:
            problems.append(f"{leg[0]} ({leg[1]}): in jvm_legs, but no fired row accounts for it")
    if len(set(got)) != len(got):
        problems.append("jvm_legs repeats a leg")
    return problems


# ---------------------------------------------------------------------------
# self-test


SELF_TEST_ROWS = """\
# dir         aliases              slow  predicate          ci
impl/a        test,test:prod-gate  slow  implementation_jvm matrix
impl/b        test                 -     always             matrix
tools/c       test,integration     -     tools_jvm          jvm-tools-c
"""

SELF_TEST_DEPS = {
    "impl/a/deps.edn": '{:aliases {:test {} :prod-gate {} :slow-test {}}}\n',
    "impl/b/deps.edn": '{:aliases {:test {:main-opts ["-m" "x"]} ;; :prod-gate {}\n}}\n',
    "tools/c/deps.edn": '{:aliases {:test {} :integration {}}}\n',
}

SELF_TEST_WORKFLOW = """\
jobs:
  detect_changed_surfaces:
    outputs:
      implementation_jvm: ${{ steps.detect.outputs.implementation_jvm }}
      tools_jvm: ${{ steps.detect.outputs.tools_jvm }}
  jvm:
    defaults:
      run:
        working-directory: ${{ matrix.leg.dir }}
    steps:
      - run: clojure -M:${{ matrix.leg.alias }}
  jvm-tools-c:
    if: needs.detect_changed_surfaces.outputs.tools_jvm == 'true'
    defaults:
      run:
        working-directory: tools/c
    steps:
      - run: clojure -M:test
      - run: clojure -M:integration
  all-required-passed:
    needs:
      - detect_changed_surfaces
      - jvm
      - jvm-tools-c
"""


def run_self_test(verbose: bool) -> int:
    cases = []

    def case(name, expect, *, rows=SELF_TEST_ROWS, workflow=SELF_TEST_WORKFLOW, extra=None):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            files = dict(SELF_TEST_DEPS, **(extra or {}))
            for rel, text in files.items():
                (root / rel).parent.mkdir(parents=True, exist_ok=True)
                (root / rel).write_text(text, encoding="utf-8")
            deps_files = sorted(rel for rel in files if rel.startswith(("impl/", "tools/")))
            failures = check(root, parse_rows(rows), workflow, deps_files)
        # A plant may trip a second rule too; what each case proves is that its
        # own rule fires, and the green case proves none fires on a sound tree.
        passed = failures == [] if expect is None else any(expect in f for f in failures)
        cases.append((name, passed, failures))

    case("a consistent tree passes", None)
    case("a planted deps.edn with a :test alias and no row reds",
         "has no row", extra={"impl/planted/deps.edn": "{:aliases {:test {}}}\n"})
    case("a row whose deps.edn lacks an alias it runs reds", "defines no :integration",
         rows=SELF_TEST_ROWS, extra={"tools/c/deps.edn": "{:aliases {:test {}}}\n"})
    case("a slow field that disagrees with :slow-test reds", "the slow field must be `slow`",
         rows=SELF_TEST_ROWS.replace("slow  implementation_jvm", "-     implementation_jvm"))
    case("a :prod-gate alias with no prod-gate leg reds", "must run `test:prod-gate`",
         rows=SELF_TEST_ROWS.replace("test,test:prod-gate", "test               "))
    case("a predicate no output declares reds", "is not an output",
         rows=SELF_TEST_ROWS.replace("tools_jvm          jvm", "tools_jmv          jvm"))
    case("two rows for one directory red", "has two rows",
         rows=SELF_TEST_ROWS + "impl/b test - always matrix\n")
    case("matrix rows with no matrix job red", "expected one job running",
         workflow=SELF_TEST_WORKFLOW.replace("${{ matrix.leg.dir }}", "impl/b"))
    case("a matrix job outside the aggregator's needs reds", "every matrix leg is advisory",
         workflow=SELF_TEST_WORKFLOW.replace("      - jvm\n", ""))
    case("a hand-written job that skips one of the row's aliases reds",
         "never runs `clojure -M:integration`",
         workflow=SELF_TEST_WORKFLOW.replace("      - run: clojure -M:integration\n", ""))
    case("a hand-written job gated on another output reds", "is not gated on `tools_jvm`",
         workflow=SELF_TEST_WORKFLOW.replace("outputs.tools_jvm == 'true'",
                                             "outputs.implementation_jvm == 'true'"))
    case("a required JVM job in a directory with no row reds", "has no row in",
         workflow=SELF_TEST_WORKFLOW.replace("working-directory: tools/c", "working-directory: tools/d"),
         extra={"tools/d/deps.edn": "{:aliases {:main {}}}\n"})

    rows = parse_rows(SELF_TEST_ROWS)
    every = {"implementation_jvm": "true", "tools_jvm": "true",
             "jvm_legs": json.dumps([{"dir": "impl/a", "alias": "test"},
                                     {"dir": "impl/a", "alias": "test:prod-gate"},
                                     {"dir": "impl/b", "alias": "test"}])}
    for name, outputs, expect in (
        ("--verify-legs passes legs that match the fired rows", every, None),
        ("--verify-legs reds a fired predicate whose leg is missing",
         dict(every, jvm_legs=json.dumps([{"dir": "impl/b", "alias": "test"}])), "is missing"),
        ("--verify-legs reds a leg no fired row accounts for",
         dict(every, implementation_jvm="false"), "no fired row accounts"),
        ("--verify-legs reds an `always` row with no leg",
         dict(every, implementation_jvm="false", jvm_legs="[]"), "impl/b (test)"),
        ("--verify-legs reds a predicate the classifier never emitted",
         {k: v for k, v in every.items() if k != "tools_jvm"}, "emitted no `tools_jvm`"),
    ):
        problems = verify_legs(rows, outputs)
        passed = problems == [] if expect is None else any(expect in p for p in problems)
        cases.append((name, passed, problems))

    ok = all(passed for _, passed, _ in cases)
    for name, passed, detail in cases:
        if verbose or not passed:
            print(f"  {'ok  ' if passed else 'FAIL'} {name}")
            if not passed:
                for line in detail:
                    print(f"         {line}")
    print(f"{'PASS' if ok else 'FAIL'} check_jvm_lanes self-test: "
          f"{sum(1 for _, p, _ in cases if p)}/{len(cases)} cases")
    return 0 if ok else 1


# ---------------------------------------------------------------------------


def read_outputs_file(path: str) -> dict:
    outputs = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            outputs[key.strip()] = value.strip()
    return outputs


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--verbose", action="store_true", help="list every row")
    parser.add_argument("--self-test", action="store_true", help="prove each rule fires")
    parser.add_argument("--verify-legs", action="store_true",
                        help="check one classification's jvm_legs against its predicates")
    parser.add_argument("--outputs", help="with --verify-legs: the classifier's key=value output")
    parser.add_argument("--repo-root", default=str(REPO_ROOT))
    args = parser.parse_args(argv)

    if args.self_test:
        return run_self_test(args.verbose)

    root = Path(args.repo_root)
    try:
        rows = parse_rows((root / ROWS_FILE).read_text(encoding="utf-8"))
    except (OSError, RowsError) as e:
        print(f"FAIL {e}")
        return 1

    if args.verify_legs:
        if args.outputs:
            outputs = read_outputs_file(args.outputs)
        else:
            outputs = json.loads(os.environ.get("CLASSIFIER_OUTPUTS") or "{}")
        problems = verify_legs(rows, outputs)
        if problems:
            print(f"FAIL the classifier's JVM legs: {len(problems)} problem(s)")
            for p in problems:
                print(f"  {p}")
            return 1
        print(f"PASS the classifier's {len(json.loads(outputs['jvm_legs']))} JVM leg(s) "
              f"match the rows its predicates fired")
        return 0

    failures = check(root, rows, (root / WORKFLOW).read_text(encoding="utf-8"),
                     tracked_deps_edn(root))
    if args.verbose:
        for row in rows:
            print(f"  {row.dir:<40} {','.join(row.aliases):<22} "
                  f"{'slow' if row.slow else '-':<5} {row.predicate:<27} {row.ci}")
    if failures:
        print(f"FAIL JVM lanes vs ground truth: {len(failures)} violation(s)")
        for failure in failures:
            print(f"  {failure}")
        return 1
    print(f"PASS JVM lanes vs ground truth: {len(rows)} rows, "
          f"{sum(1 for r in rows if r.matrix)} on the matrix, {len(NO_ROW)} declared "
          f"elsewhere; every deps.edn :test alias is a lane, and every lane is in test.yml")
    return 0


if __name__ == "__main__":
    sys.exit(main())
