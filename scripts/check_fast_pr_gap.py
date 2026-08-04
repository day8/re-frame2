#!/usr/bin/env python3
"""Name the REQUIRED checks that `scripts/test-fast-pr.sh` does not run (rf2-13zre).

WHY THIS EXISTS.  A worker runs the fast-PR spine, reads `PASS fast PR spine`,
opens its PR, and CI goes red on a check the spine never ran.  It happened three
times on 2026-08-04, each time on a DIFFERENT check, each time costing a round
trip -- and each discovery taught nobody, because nothing wrote it down.

The spine's own PASS line already enumerates the tiers it SKIPPED by diff
classification, and its header names the broad families it has no tier for.
Neither can see the class that actually ambushed people:

    A REQUIRED CHECK CAN BE A **STEP INSIDE A JOB THE SPINE BELIEVES IT COVERS.**

The sharpest instance is the EP-0036 donor-boundary check.  It is the last step
of test.yml's `jvm-freehand` job, so it reports under the display name
"JVM freehand (clojure -M:test)" -- and it is not a test at all, it is a
`git grep` for `re-frame.ui` over `implementation/freehand`.  The spine runs that
job's `clojure -M:test` and nothing else, so the tier is not skipped, the step is
not a suite, and a worker reading the red goes looking for a failing test that
does not exist.  Job-granularity comparison cannot see it.  This gate compares at
STEP granularity, and it names the CHECK rather than the job.

HOW IT DECIDES, and why the failure direction is the safe one.

  * The REQUIRED set is derived, never listed: the three aggregator jobs whose
    display names are the branch ruleset's required contexts --
    `All required checks passed` (test.yml), `Lint required` (lint.yml) and
    `Docs required` (docs.yml) -- each name their gating jobs in `needs:`.  A new
    required job appears here the moment it is added to an aggregator.

  * Each required job's GATE STEPS are derived: its `run:` steps minus the
    recognised toolchain-setup shapes (`npm ci`, `pip install`, the Clojure CLI
    installer, `playwright install`, ...).  A step whose body mixes setup with
    anything else is a gate, not setup -- misreading a gate as setup would HIDE
    it, so the setup patterns are anchored and whole-body.

  * COVERAGE is decided by `SPINE_LANES` below: eleven command signatures, one
    per lane the spine actually has.  Everything a signature does not match is
    reported as unrun.  That is the safe direction by construction: a new CI job
    or a new step inside an existing one is reported as UNRUN until somebody
    teaches this file otherwise, and the failure mode of being wrong is a worker
    running one check too many.

  * The python-checker lane needs no signature maintenance at all.  Both sides
    invoke `python scripts/check_*.py` in an identical parseable shape, so the
    spine's own source is read and the set difference derived.  That difference
    is where most of the gap turned out to live: CI's `Repo invariant checks` job
    runs 37 checkers and `verify-readme-links` another 11, of which the spine
    runs a subset -- THIRTEEN required checker scripts had no local lane at all,
    every one of them cheap pure-Python, none of them named anywhere.

  * REPRODUCE COMMANDS ARE READ FROM THE STEP, never written here -- the same
    discipline `check_ci_reproduce_commands.py` enforces for the invariant job's
    failure report.  No required gate step in any of the three workflows
    interpolates a `${{ }}` expression, so each step's `run:` body IS the local
    command.  The clj-kondo version pin is derived the same way, from that job's
    own installer step: prose would go stale on the next bump, and a local
    clj-kondo of a different version PROVES NOTHING (the 2025.10.23 binary
    installed on this project's Windows checkout reports 0 errors on the line CI
    fails at 2026.04.15, and 10 different errors CI does not).

THE DRIFT RATCHET (`--check`, the default).  A signature in `SPINE_LANES` that
stops matching any required gate step is a FAILURE: it means the spine still
claims a lane for a check CI renamed, moved or deleted, and the claim is now
false.  That is the whole anti-staleness mechanism -- a hand-written list of
gaps would rot silently, and this territory changed twice on the day the bead was
filed.

    python scripts/check_fast_pr_gap.py              audit the map (default)
    python scripts/check_fast_pr_gap.py --list       full report + local commands
    python scripts/check_fast_pr_gap.py --brief      the digest the spine prints
    python scripts/check_fast_pr_gap.py --self-test  prove the guard fires

Stdlib only, on purpose: CI's `verify-skill-mcp-drift` job installs no pip
packages, so a PyYAML dependency would have to be added to a job that has
deliberately stayed dependency-free.  The line-based parser below is the same
shape `check_ci_reproduce_commands.py` and `check_jvm_lane_rosters.py` use.
"""

from __future__ import annotations

import argparse
import io
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

SPINE = "scripts/test-fast-pr.sh"
IMPL_ROSTER = "scripts/test-jvm-implementation.sh"

# workflow file -> (aggregator job id, the required-status-context display name)
AGGREGATORS = {
    ".github/workflows/test.yml": ("all-required-passed", "All required checks passed"),
    ".github/workflows/lint.yml": ("lint-required", "Lint required"),
    ".github/workflows/docs.yml": ("docs-required", "Docs required"),
}

# Sanity floors.  A parser that silently returns nothing would make this gate
# report a clean, empty, entirely fictional map.
MIN_REQUIRED_JOBS = {
    ".github/workflows/test.yml": 40,
    ".github/workflows/lint.yml": 3,
    ".github/workflows/docs.yml": 2,
}
MIN_SPINE_CHECKERS = 10


# ---------------------------------------------------------------------------
# Toolchain setup, not a gate.
#
# Anchored and applied to the WHOLE run body: a body is setup only when every
# one of its command lines is setup.  Mistaking a gate for setup hides it, which
# is the defect this file exists to prevent, so the list stays narrow.
# ---------------------------------------------------------------------------
SETUP_PATTERNS = (
    r"^npm ci$",
    r"^npm install$",
    r"^pip install\b",
    # Written both bare and quoted across the workflows
    # (`"$GITHUB_WORKSPACE/.github/scripts/install-clojure-cli.sh"`), so the
    # trailing quote is optional -- a missed quote here re-reports the Clojure
    # installer as an unrun gate in all twenty-three JVM jobs.
    r"install-clojure-cli\.sh[\"']?$",
    r"^npx playwright install\b",
    r"^sudo apt-get\b",
    r"^apt-get\b",
    r"^curl .*install-clj-kondo",
    r"^chmod \+x install-clj-kondo$",
    r"^sudo \./install-clj-kondo\b",
    r"^clj-kondo --version$",
)
_SETUP_RE = tuple(re.compile(p) for p in SETUP_PATTERNS)


# ---------------------------------------------------------------------------
# THE SPINE'S LANES.  One entry per thing `scripts/test-fast-pr.sh` actually
# runs.  A required gate step matching none of these is reported as unrun.
#
# `command` matches the step's NORMALISED body (continuations joined, multiple
# command lines joined with " ; ", whitespace collapsed).  `working_dir` may be
# the sentinel "@impl-roster", meaning "the job's working-directory is an
# artefact on scripts/test-jvm-implementation.sh" -- that one rule covers all
# twenty-three per-artefact JVM jobs without naming any of them, and it is what
# makes `jvm-freehand`'s SECOND gate step fall out of the derivation as unrun
# rather than having to be remembered.
# ---------------------------------------------------------------------------
class Lane:
    def __init__(self, key: str, command: str, why: str, working_dir: str | None = None):
        self.key = key
        self.command = re.compile(command)
        self.why = why
        self.working_dir = working_dir

    def matches(self, step: "Step", on_impl_roster) -> bool:
        if not self.command.match(step.command):
            return False
        if self.working_dir == "@impl-roster":
            return step.working_directory is not None and on_impl_roster(step.working_directory)
        if self.working_dir is not None:
            return step.working_directory == self.working_dir
        return True


SPINE_LANES = (
    Lane(
        "jvm-artefact-suite",
        r"^clojure -M:test$",
        "spine JVM tier: implementation/core plus every artefact the diff touched "
        "(the PASS line names the artefacts it skipped)",
        working_dir="@impl-roster",
    ),
    Lane(
        "changed-surface-classifier",
        r"^\./\.github/scripts/report-changed-surfaces\.sh$",
        "the spine delegates its whole tier decision to this same script",
    ),
    Lane(
        "version-lockstep",
        r"^\./\.github/scripts/verify-version-lockstep\.sh$",
        "spine always-on: 'lockstep version drift'",
    ),
    Lane(
        "spine-self-test",
        r"^bash scripts/_test_fixtures/test_fast_pr_docs_gate/run-self-test\.sh$",
        "spine self-test -- ARMED ONLY when the spine's own tree is in the diff",
    ),
    Lane(
        "js-harness-self-tests",
        r"^npm run test:script-policy && npm run test:script-helpers$",
        "spine node tier: 'implementation JS harness helpers' -- run CHAINED, as CI does",
    ),
    Lane(
        "cljs-node-test",
        r"^npx shadow-cljs compile node-test ; node out/node-test\.js$",
        "spine node tier: `npm run test:cljs`",
    ),
    Lane(
        "per-ns-isolation-self-test",
        r"^node scripts/check-per-ns-isolation\.cjs --self-test$",
        "spine node tier: 'per-ns isolation self-test'",
    ),
    Lane(
        "per-ns-isolation",
        r"^node scripts/check-per-ns-isolation\.cjs$",
        "spine node tier: 'per-ns test isolation'",
    ),
    Lane(
        "hicasso-compile",
        r"^npm run test:hicasso-compile$",
        "spine node tier: 'hicasso bench-lane compile'",
    ),
    Lane(
        "mkdocs-strict",
        r"^mkdocs build --strict$",
        "spine docs tier -- SOFT-SKIPS (loudly) where mkdocs resolves neither as a "
        "console script nor as `python -m mkdocs`",
    ),
    # The python-checker lane carries no signature of its own: coverage is
    # derived per-invocation from the spine's source.  It is listed here so
    # `--check` can assert the derivation still finds checkers on both sides.
    Lane(
        "python-checker",
        r"^python scripts/check_[A-Za-z0-9_]+\.py\b",
        "spine always-on / docs tier -- matched per script + mode against the spine's source",
    ),
)

_PY_CHECKER_RE = re.compile(r"^python scripts/(check_[A-Za-z0-9_]+)\.py\b(.*)$")
# The spine invokes its checkers through the derived root, one per line.
_SPINE_CHECKER_RE = re.compile(
    r'python\s+"\$spine_root/scripts/(check_[A-Za-z0-9_]+)\.py"(.*)$'
)
_KONDO_PIN_RE = re.compile(r"install-clj-kondo --version (\S+)")


def _mode(flags: str) -> str:
    return "self-test" if re.search(r"(?:^|\s)--self-test(?:\s|=|$)", flags) else "live"


# ---------------------------------------------------------------------------
# Workflow parsing.  Line-based and deliberately small: jobs at two-space
# indent, job keys at four, steps at `      - `, step keys at eight, block
# scalars at ten or more.
# ---------------------------------------------------------------------------
_JOB_RE = re.compile(r"^  ([A-Za-z0-9_][A-Za-z0-9_.-]*):\s*$")
_JOBKEY_RE = re.compile(r"^    ([A-Za-z0-9_-]+):\s*(.*?)\s*$")
_LISTITEM_RE = re.compile(r"^      - (\S+)\s*$")
_STEP_START_RE = re.compile(r"^      - (?:([A-Za-z0-9_-]+):\s*(.*?)\s*)?$")
_STEPKEY_RE = re.compile(r"^        ([A-Za-z0-9_-]+):\s*(.*?)\s*$")
_WORKDIR_RE = re.compile(r"^        working-directory:\s*(\S+)\s*$")
_BLOCK_RE = re.compile(r"^[|>][-+0-9]*$")


# `${{ github.workspace }}` IS the repo root, so a step declaring it needs no
# `cd` at all -- and printing the raw expression as a local command would be
# useless. This is the one workflow expression that reaches a required gate
# step's location (the EP-0036 donor grep uses it); no required gate step's
# `run:` body interpolates an expression at all.
_WORKSPACE_RE = re.compile(r"^\$\{\{\s*github\.workspace\s*\}\}$")


class Step:
    def __init__(self, name, run, uses, working_directory):
        self.name = name
        self.run = run
        self.uses = uses
        # A step that declares its own `working-directory` has OVERRIDDEN the
        # job default; record that, so the job-default backfill below cannot
        # quietly put the default back after `${{ github.workspace }}` resolved
        # to "the repo root". That is exactly the shape the EP-0036 donor grep
        # uses to escape `jvm-freehand`'s `implementation/freehand` default.
        self.declared_wd = working_directory is not None
        if working_directory and _WORKSPACE_RE.match(working_directory.strip("'\"")):
            working_directory = None
        self.working_directory = working_directory
        self.command = normalise(run) if run else ""

    @property
    def is_setup(self) -> bool:
        """True only when EVERY command line in the body is a setup shape."""
        if not self.run:
            return False
        lines = [ln.strip() for ln in join_continuations(self.run) if ln.strip()]
        if not lines:
            return False
        return all(any(rx.search(ln) for rx in _SETUP_RE) for ln in lines)


class Job:
    def __init__(self, job_id, workflow):
        self.job_id = job_id
        self.workflow = workflow
        self.name = job_id
        self.needs: list[str] = []
        self.working_directory: str | None = None
        self.steps: list[Step] = []

    @property
    def gate_steps(self) -> list[Step]:
        return [s for s in self.steps if s.run and not s.is_setup]


def join_continuations(body: str) -> list[str]:
    """Fold trailing-backslash continuations into single logical lines."""
    out: list[str] = []
    pending = ""
    for raw in body.splitlines():
        line = raw.rstrip()
        if line.endswith("\\"):
            pending += line[:-1].strip() + " "
            continue
        out.append((pending + line.strip()).strip())
        pending = ""
    if pending:
        out.append(pending.strip())
    return out


def normalise(body: str) -> str:
    """A run body as one comparable command string."""
    lines = [ln for ln in join_continuations(body) if ln and not ln.startswith("#")]
    return " ; ".join(" ".join(ln.split()) for ln in lines)


def parse_workflow(text: str, workflow: str) -> dict[str, Job]:
    lines = text.splitlines()
    jobs: dict[str, Job] = {}
    cur: Job | None = None
    section: str | None = None      # "needs" | "steps" | None
    step: dict | None = None
    block_key: str | None = None
    block_lines: list[str] = []
    block_indent = 0
    in_defaults_run = False

    def close_block():
        nonlocal block_key, block_lines
        if step is not None and block_key:
            step[block_key] = "\n".join(block_lines)
        block_key, block_lines = None, []

    def close_step():
        nonlocal step
        close_block()
        if step is not None and cur is not None:
            cur.steps.append(
                Step(
                    step.get("name"),
                    step.get("run"),
                    step.get("uses"),
                    step.get("working-directory"),
                )
            )
        step = None

    for line in lines:
        # An open block scalar swallows every line indented past its opener.
        if block_key is not None:
            if not line.strip():
                block_lines.append("")
                continue
            if len(line) - len(line.lstrip()) >= block_indent:
                block_lines.append(line[block_indent:])
                continue
            close_block()

        m = _JOB_RE.match(line)
        if m:
            close_step()
            cur = Job(m.group(1), workflow)
            jobs[cur.job_id] = cur
            section = None
            in_defaults_run = False
            continue
        if cur is None:
            continue

        if section == "steps":
            m = _STEP_START_RE.match(line)
            if m:
                close_step()
                step = {}
                key, value = m.group(1), m.group(2)
                if key:
                    if value and _BLOCK_RE.match(value):
                        block_key, block_lines, block_indent = key, [], 10
                    else:
                        step[key] = value
                continue
            if step is not None:
                m = _STEPKEY_RE.match(line)
                if m:
                    key, value = m.group(1), m.group(2)
                    if value and _BLOCK_RE.match(value):
                        block_key, block_lines, block_indent = key, [], 10
                    else:
                        step[key] = value
                    continue
                if _WORKDIR_RE.match(line):
                    step["working-directory"] = _WORKDIR_RE.match(line).group(1)
                    continue

        if section == "needs":
            m = _LISTITEM_RE.match(line)
            if m:
                cur.needs.append(m.group(1))
                continue
            if line.strip().startswith("#") or not line.strip():
                continue

        m = _JOBKEY_RE.match(line)
        if m:
            close_step()
            key, value = m.group(1), m.group(2)
            section = None
            in_defaults_run = False
            if key == "name" and value:
                cur.name = value.strip("'\"")
            elif key == "needs":
                if value:
                    cur.needs.extend(
                        v.strip() for v in value.strip("[]").split(",") if v.strip()
                    )
                else:
                    section = "needs"
            elif key == "steps":
                section = "steps"
            elif key == "defaults":
                in_defaults_run = True
            continue

        if in_defaults_run:
            m = _WORKDIR_RE.match(line)
            if m:
                cur.working_directory = m.group(1)

    close_step()
    # `defaults.run.working-directory` can be read after the steps in file
    # order, so apply the job default to every step that did not declare one.
    for job in jobs.values():
        for s in job.steps:
            if not s.declared_wd:
                s.working_directory = job.working_directory
    return jobs


def parse_impl_roster(text: str) -> set[str]:
    """Artefact paths from the `artefacts=( ... )` bash array."""
    out: set[str] = set()
    inside = False
    for line in text.splitlines():
        if not inside:
            if re.match(r"^[A-Za-z_][A-Za-z0-9_]*=\(\s*$", line):
                inside = True
            continue
        if line.strip() == ")":
            break
        out.update(line.split("#", 1)[0].split())
    return out


def parse_spine_checkers(text: str) -> set[tuple[str, str]]:
    """(script stem, mode) for every `python scripts/check_*.py` the spine runs."""
    found: set[tuple[str, str]] = set()
    for line in text.splitlines():
        m = _SPINE_CHECKER_RE.search(line)
        if m:
            found.add((m.group(1), _mode(m.group(2))))
    return found


# ---------------------------------------------------------------------------
# The map
# ---------------------------------------------------------------------------
class Finding:
    """One required gate step the spine does not run."""

    def __init__(self, workflow, job, step):
        self.workflow = workflow
        self.job = job
        self.step = step


class GapMap:
    def __init__(self, workflows, impl_roster, spine_checkers):
        self.workflows = workflows            # path -> {job_id: Job}
        self.impl_roster = impl_roster
        self.spine_checkers = spine_checkers
        self.required: list[tuple[str, Job]] = []
        self.matched_lanes: set[str] = set()
        self.partial: list[tuple[Job, list[Step]]] = []   # job, uncovered steps
        self.ci_only: list[Job] = []
        self.covered: list[Job] = []
        self.problems: list[str] = []
        self.kondo_pin: str | None = None
        self._build()

    def _on_impl_roster(self, path: str) -> bool:
        return path in self.impl_roster

    def covers(self, step: Step) -> bool:
        m = _PY_CHECKER_RE.match(step.command)
        if m:
            self.matched_lanes.add("python-checker")
            return (m.group(1), _mode(m.group(2))) in self.spine_checkers
        for lane in SPINE_LANES:
            if lane.key == "python-checker":
                continue
            if lane.matches(step, self._on_impl_roster):
                self.matched_lanes.add(lane.key)
                return True
        return False

    def _build(self):
        for path, (agg_id, context) in AGGREGATORS.items():
            # A workflow the caller did not supply is out of scope, not a defect:
            # `load()` refuses to build a map missing any of the three, so only
            # the self-test's single-workflow fixtures ever take this branch.
            jobs = self.workflows.get(path)
            if jobs is None:
                continue
            agg = jobs.get(agg_id)
            if agg is None:
                self.problems.append(
                    f"{path}: aggregator job `{agg_id}` not found -- the required "
                    f"status context '{context}' cannot be derived"
                )
                continue
            if len(agg.needs) < MIN_REQUIRED_JOBS[path]:
                self.problems.append(
                    f"{path}: `{agg_id}` lists only {len(agg.needs)} required job(s); "
                    f"expected at least {MIN_REQUIRED_JOBS[path]} -- the parser is "
                    f"almost certainly broken"
                )
            for job_id in agg.needs:
                job = jobs.get(job_id)
                if job is None:
                    self.problems.append(
                        f"{path}: `{agg_id}` needs `{job_id}`, which is not a job in "
                        f"this workflow"
                    )
                    continue
                self.required.append((path, job))

        for path, job in self.required:
            gates = job.gate_steps
            uncovered = [s for s in gates if not self.covers(s)]
            if not gates:
                continue
            if not uncovered:
                self.covered.append(job)
            elif len(uncovered) == len(gates):
                self.ci_only.append(job)
            else:
                self.partial.append((job, uncovered))

        # The clj-kondo pin, read from the job's own installer step.
        for path, job in self.required:
            for s in job.steps:
                if s.run:
                    m = _KONDO_PIN_RE.search(normalise(s.run))
                    if m:
                        self.kondo_pin = m.group(1)
        if self.kondo_pin is None:
            self.problems.append(
                "the clj-kondo version pin could not be read from any required job's "
                "installer step (expected `install-clj-kondo --version <pin>`); the "
                "report would omit the pin, and a local clj-kondo of another version "
                "proves nothing"
            )

        for lane in SPINE_LANES:
            if lane.key not in self.matched_lanes:
                self.problems.append(
                    f"spine lane `{lane.key}` matches no required gate step any more. "
                    f"The spine still runs it ({lane.why}), so either CI renamed / "
                    f"moved / dropped that check -- in which case update the signature "
                    f"in SPINE_LANES -- or the parser broke."
                )

        if len(self.spine_checkers) < MIN_SPINE_CHECKERS:
            self.problems.append(
                f"only {len(self.spine_checkers)} `python scripts/check_*.py` "
                f"invocation(s) parsed out of {SPINE}; expected at least "
                f"{MIN_SPINE_CHECKERS}. Every CI checker would be over-reported as "
                f"unrun."
            )


# ---------------------------------------------------------------------------
# reporting
# ---------------------------------------------------------------------------
def _wrap(items: list[str], width: int, indent: str) -> list[str]:
    out, line = [], ""
    for item in items:
        candidate = f"{line}, {item}" if line else item
        if len(candidate) + len(indent) > width and line:
            out.append(indent + line + ",")
            line = item
        else:
            line = candidate
    if line:
        out.append(indent + line)
    return out


def _repro(step: Step, indent: str, max_lines: int = 8) -> list[str]:
    """The local command, READ FROM THE STEP -- never written out here.

    No required gate step interpolates a `${{ }}` expression, so a step's `run:`
    body is runnable as-is from the repo root (or from its working-directory).
    A long body is truncated with a pointer at the workflow that owns it.
    """
    body = [ln for ln in step.run.splitlines() if ln.strip()]
    if len(body) == 1:
        prefix = f"cd {step.working_directory} && " if step.working_directory else ""
        return [f"{indent}$ {prefix}{body[0].strip()}"]
    out = []
    if step.working_directory:
        out.append(f"{indent}$ cd {step.working_directory}")
    for i, ln in enumerate(body[:max_lines]):
        out.append(f"{indent}{'$ ' if i == 0 and not step.working_directory else '  '}{ln}")
    if len(body) > max_lines:
        owner = getattr(step, "_wf", "the workflow")
        out.append(f"{indent}  ... ({len(body) - max_lines} more lines -- see {owner})")
    return out


def report(gap: GapMap, brief: bool) -> list[str]:
    out: list[str] = []
    n = sum(len(u) for _, u in gap.partial) + sum(len(j.gate_steps) for j in gap.ci_only)
    out.append("")
    out.append(
        f"REQUIRED CHECKS THIS SPINE DOES NOT RUN ({n}) "
        f"-- derived by scripts/check_fast_pr_gap.py"
    )
    out.append(
        "  Green here is not green in CI. The three required status contexts are "
        "`All required"
    )
    out.append(
        "  checks passed` (test.yml), `Lint required` (lint.yml) and `Docs required` "
        "(docs.yml)."
    )
    # Not a to-do list.  Many of these are surface-gated in CI and will skip on a
    # diff that does not reach them; the claim is only that NONE of them is
    # decided by this spine, so a green here says nothing about any of them.
    out.append(
        "  This is not a to-do list: most are surface-gated in CI and skip on a diff "
        "that does not"
    )
    out.append(
        "  reach them. The claim is narrower and harder -- NONE of them is decided "
        "here."
    )

    if gap.partial:
        out.append("")
        out.append(
            "  (A) STEPS INSIDE JOBS THIS SPINE DOES RUN. These report under the JOB's "
            "name, so a"
        )
        out.append(
            "      red here names a test suite for something that may not be a test at "
            "all."
        )
        for job, uncovered in gap.partial:
            out.append(f"      {job.workflow.rsplit('/', 1)[-1]}  job \"{job.name}\"")
            checkers = [s for s in uncovered if _PY_CHECKER_RE.match(s.command)]
            others = [s for s in uncovered if not _PY_CHECKER_RE.match(s.command)]
            for s in others:
                out.append(f"        - step \"{s.name}\"")
                if brief:
                    cmd = s.command
                    out.append(
                        f"            runs: {cmd[:110]}{' ...' if len(cmd) > 110 else ''}"
                    )
                else:
                    out.extend(_repro(s, "            "))
            if checkers:
                names = sorted(
                    {
                        f"{_PY_CHECKER_RE.match(s.command).group(1)}"
                        f"{' --self-test' if _mode(_PY_CHECKER_RE.match(s.command).group(2)) == 'self-test' else ''}"
                        for s in checkers
                    }
                )
                out.append(
                    f"        - {len(checkers)} `python scripts/check_*.py` "
                    f"invocation(s) with no local lane:"
                )
                out.extend(_wrap(names, 96, "            "))
                out.append(
                    "            (each is pure-Python and runs from the repo root as "
                    "`python scripts/<name>.py [--self-test]`)"
                )

    if gap.ci_only:
        out.append("")
        out.append("  (B) REQUIRED JOBS WITH NO LANE IN THIS SPINE AT ALL.")
        for path, (_, context) in AGGREGATORS.items():
            jobs = [j for j in gap.ci_only if j.workflow == path]
            if not jobs:
                continue
            short = path.rsplit("/", 1)[-1]
            plural = "job" if len(jobs) == 1 else "jobs"
            out.append(
                f"      {short} -- required context `{context}` ({len(jobs)} {plural})"
            )
            if brief:
                out.extend(_wrap([j.job_id for j in jobs], 96, "        "))
            else:
                for job in jobs:
                    out.append(f"        - {job.job_id}: \"{job.name}\"")
                    for s in job.gate_steps:
                        out.append(f"            step \"{s.name}\"")
                        out.extend(_repro(s, "              "))
            if short == "lint.yml" and gap.kondo_pin:
                out.append(
                    f"        NOTE clj-kondo: CI pins {gap.kondo_pin}. A pass from a "
                    f"differently-versioned"
                )
                out.append(
                    "             local binary PROVES NOTHING -- versions disagree about "
                    "which findings"
                )
                out.append(
                    "             are errors. Install the pin, or read the red in CI: "
                    "the job fails on"
                )
                out.append("             ERROR level only; warnings stay informational.")

    if brief:
        out.append("")
        out.append(
            "  Local command for each of the above: "
            "`python scripts/check_fast_pr_gap.py --list`"
        )
    out.append("")
    return out


# ---------------------------------------------------------------------------
# modes
# ---------------------------------------------------------------------------
def load(root: Path) -> GapMap:
    workflows = {}
    for path in AGGREGATORS:
        f = root / path
        if not f.exists():
            raise SystemExit(f"FAIL: {path} not found (run from the repo root)")
        workflows[path] = parse_workflow(f.read_text(encoding="utf-8"), path)
    roster = parse_impl_roster((root / IMPL_ROSTER).read_text(encoding="utf-8"))
    checkers = parse_spine_checkers((root / SPINE).read_text(encoding="utf-8"))
    gap = GapMap(workflows, roster, checkers)
    # `_repro` names the owning workflow when it truncates a long body.
    for path, job in gap.required:
        for s in job.steps:
            s._wf = path
    return gap


def run_check(verbose: bool) -> int:
    gap = load(REPO_ROOT)
    if gap.problems:
        sys.stderr.write(
            f"{len(gap.problems)} problem(s) in the fast-PR gap map:\n"
        )
        for p in gap.problems:
            sys.stderr.write(f"  - {p}\n")
        return 1
    n = sum(len(u) for _, u in gap.partial) + sum(len(j.gate_steps) for j in gap.ci_only)
    if verbose:
        print("\n".join(report(gap, brief=False)))
    print(
        f"fast-PR gap map clean: {len(gap.required)} required jobs across "
        f"{len(AGGREGATORS)} workflows; {len(gap.covered)} fully covered by the spine, "
        f"{len(gap.partial)} partly, {len(gap.ci_only)} not at all "
        f"({n} required checks unrun locally)."
    )
    return 0


def run_report(brief: bool) -> int:
    gap = load(REPO_ROOT)
    print("\n".join(report(gap, brief=brief)))
    return 1 if gap.problems else 0


# ---------------------------------------------------------------------------
# self-test
# ---------------------------------------------------------------------------
_FIXTURE = """\
name: fixture
jobs:
  setup-only:
    name: Setup only
    steps:
      - uses: actions/checkout@v6
      - name: npm install
        run: npm ci

  jvm-thing:
    name: JVM thing (clojure -M:test)
    defaults:
      run:
        working-directory: implementation/thing
    steps:
      - name: Set up Clojure CLI
        run: $GITHUB_WORKSPACE/.github/scripts/install-clojure-cli.sh
      - name: Run JVM tests (thing artefact)
        run: clojure -M:test
      - name: Assert no dependency on the donor
        run: |
          set -euo pipefail
          if git grep -n -e 're-frame\\.ui' -- implementation/thing; then
            exit 1
          fi

  invariants:
    name: Repo invariant checks
    steps:
      - name: Covered checker
        id: check_covered
        run: python scripts/check_covered.py --verbose --ci
      - name: Uncovered checker
        id: check_uncovered
        run: python scripts/check_uncovered.py --verbose --ci
      - name: Covered checker self-test
        id: check_covered_selftest
        run: python scripts/check_covered.py --self-test

  browser-only:
    name: Browser lane
    defaults:
      run:
        working-directory: implementation
    steps:
      - name: Run the browser gate
        run: npm run test:browser

  all-required-passed:
    name: All required checks passed
    needs:
      - setup-only
      # a comment between entries
      - jvm-thing
      - invariants
      - browser-only
    steps:
      - run: echo ok
"""


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

    jobs = parse_workflow(_FIXTURE, "fixture.yml")

    check("parser finds every job", set(jobs) == {
        "setup-only", "jvm-thing", "invariants", "browser-only", "all-required-passed"})
    check("parser reads a job display name", jobs["jvm-thing"].name == "JVM thing (clojure -M:test)")
    check(
        "parser reads defaults.run.working-directory",
        jobs["jvm-thing"].working_directory == "implementation/thing",
    )
    check(
        "parser reads a block-list `needs:` past interleaved comments",
        jobs["all-required-passed"].needs == ["setup-only", "jvm-thing", "invariants", "browser-only"],
    )
    check("parser reads an inline `needs:`", parse_workflow(
        "jobs:\n  a:\n    needs: [x, y]\n    steps:\n      - run: echo\n", "f.yml"
    )["a"].needs == ["x", "y"])
    check("parser reads a scalar `needs:`", parse_workflow(
        "jobs:\n  a:\n    needs: detect\n    steps:\n      - run: echo\n", "f.yml"
    )["a"].needs == ["detect"])

    # Setup recognition: `npm ci` alone is setup; the Clojure installer is setup;
    # a body mixing setup with anything else is a GATE (hiding one is the defect).
    check("a bare `npm ci` step is setup", jobs["setup-only"].gate_steps == [])
    check(
        "the Clojure CLI installer is setup, the suite is not",
        [s.name for s in jobs["jvm-thing"].gate_steps]
        == ["Run JVM tests (thing artefact)", "Assert no dependency on the donor"],
    )
    # The installer is written BOTH ways across the workflows; a pattern that
    # only matches the bare form re-reports it as an unrun gate in every JVM job.
    check(
        "the Clojure CLI installer is setup in its QUOTED form too",
        parse_workflow(
            'jobs:\n  a:\n    steps:\n      - name: Set up Clojure CLI\n'
            '        run: "$GITHUB_WORKSPACE/.github/scripts/install-clojure-cli.sh"\n',
            "f.yml",
        )["a"].gate_steps == [],
    )
    mixed = parse_workflow(
        "jobs:\n  a:\n    steps:\n      - name: b\n        run: |\n"
        "          npm ci\n          npm run build\n", "f.yml"
    )["a"]
    check("a body mixing `npm ci` with a build is a GATE, not setup", len(mixed.gate_steps) == 1)

    # Block scalars and continuations normalise into one comparable command.
    donor = jobs["jvm-thing"].gate_steps[1]
    check("block-scalar run body is captured", "git grep" in donor.command)
    check(
        "backslash continuations fold into one logical line",
        normalise("clj-kondo \\\n  --parallel \\\n  --lint implementation")
        == "clj-kondo --parallel --lint implementation",
    )
    check(
        "multiple command lines join with ' ; '",
        normalise("npx shadow-cljs compile node-test\nnode out/node-test.js")
        == "npx shadow-cljs compile node-test ; node out/node-test.js",
    )

    # The map itself, over the fixture.
    gap = GapMap(
        {".github/workflows/test.yml": jobs},
        impl_roster={"implementation/thing"},
        spine_checkers={("check_covered", "live"), ("check_covered", "self-test")},
    )
    # Only test.yml is present in the fixture, so the other two aggregators and
    # the kondo pin are legitimately absent -- ignore those problems here.
    fixture_partial = {j.job_id: [s.name for s in u] for j, u in gap.partial}
    check(
        "THE ITEM-3 CLASS: a non-test step inside a covered JVM job is reported",
        fixture_partial.get("jvm-thing") == ["Assert no dependency on the donor"],
    )
    check(
        "a checker the spine does not invoke is reported; one it does is not",
        fixture_partial.get("invariants") == ["Uncovered checker"],
    )
    check(
        "a job with no covered step at all is CI-only",
        [j.job_id for j in gap.ci_only] == ["browser-only"],
    )
    check(
        "a setup-only required job is neither covered nor reported",
        "setup-only" not in fixture_partial
        and "setup-only" not in {j.job_id for j in gap.ci_only},
    )
    check(
        "the impl-roster rule covers `clojure -M:test` in a rostered dir",
        "jvm-artefact-suite" in gap.matched_lanes,
    )
    off_roster = GapMap(
        {".github/workflows/test.yml": jobs},
        impl_roster=set(),
        spine_checkers={("check_covered", "live"), ("check_covered", "self-test")},
    )
    check(
        "`clojure -M:test` OFF the roster is NOT covered (no local lane)",
        "jvm-thing" in {j.job_id for j in off_roster.ci_only},
    )
    check(
        "a checker mode mismatch is a gap: live-only spine vs a CI --self-test",
        "Covered checker self-test"
        in [
            s.name
            for j, u in GapMap(
                {".github/workflows/test.yml": jobs},
                impl_roster={"implementation/thing"},
                spine_checkers={("check_covered", "live")},
            ).partial
            for s in u
            if j.job_id == "invariants"
        ],
    )

    # THE RATCHET: a lane that matches nothing must be a problem.
    empty = GapMap(
        {".github/workflows/test.yml": parse_workflow(
            "jobs:\n  agg:\n    name: All required checks passed\n"
            "    needs:\n      - browser-only\n    steps:\n      - run: echo\n"
            "  browser-only:\n    name: B\n    steps:\n      - name: g\n"
            "        run: npm run test:browser\n", "f.yml")},
        impl_roster=set(),
        spine_checkers={("check_covered", "live")},
    )
    check(
        "a SPINE_LANES signature matching no required step FIRES",
        any("matches no required gate step" in p for p in empty.problems),
    )
    check(
        "a `needs:` entry naming no real job FIRES",
        any(
            "which is not a job in this workflow" in p
            for p in GapMap(
                {".github/workflows/test.yml": parse_workflow(
                    "jobs:\n  all-required-passed:\n    name: A\n    needs:\n      - ghost\n"
                    "    steps:\n      - run: echo\n", "f.yml")},
                impl_roster=set(),
                spine_checkers=set(),
            ).problems
        ),
    )
    check(
        "an empty spine-checker parse FIRES rather than over-reporting silently",
        any("expected at least" in p and SPINE in p for p in empty.problems),
    )

    check("the kondo pin regex reads a real pin", _KONDO_PIN_RE.search(
        "sudo ./install-clj-kondo --version 2026.04.15") is not None)

    # `${{ github.workspace }}` is the repo root -- the EP-0036 donor grep
    # declares it, and echoing the raw expression as a `cd` target would print a
    # command nobody can run.
    ws = parse_workflow(
        "jobs:\n  a:\n    steps:\n      - name: g\n"
        "        working-directory: ${{ github.workspace }}\n"
        "        run: git grep -n foo\n",
        "f.yml",
    )["a"].gate_steps[0]
    check("`${{ github.workspace }}` resolves to the repo root, not a literal cd",
          ws.working_directory is None
          and _repro(ws, "") == ["$ git grep -n foo"])
    # ... and the job default must not be put back over it (the EP-0036 shape).
    ws_override = parse_workflow(
        "jobs:\n  a:\n    defaults:\n      run:\n"
        "        working-directory: implementation/freehand\n"
        "    steps:\n      - name: g\n"
        "        working-directory: ${{ github.workspace }}\n"
        "        run: git grep -n foo\n",
        "f.yml",
    )["a"]
    check(
        "a step's own working-directory beats the job default after backfill",
        ws_override.gate_steps[0].working_directory is None,
    )

    # Against the REAL repo: the map builds clean and reports a non-trivial gap.
    real = load(REPO_ROOT)
    check("real repo: the gap map has no problems", real.problems == [])
    check("real repo: >= 60 required jobs discovered", len(real.required) >= 60)
    check("real repo: at least one PARTIAL job (the item-3 class)", len(real.partial) >= 1)
    check(
        "real repo: the EP-0036 donor grep is reported as an unrun STEP",
        any(
            job.job_id == "jvm-freehand"
            and any("git grep" in s.command for s in uncovered)
            for job, uncovered in real.partial
        ),
    )
    check("real repo: the clj-kondo pin was derived", bool(real.kondo_pin))
    check(
        "real repo: the chained JS harness gate IS covered (the spine runs it chained)",
        "js-harness-self-tests" in real.matched_lanes,
    )
    if verbose:
        sys.stderr.write("\n".join(report(real, brief=False)) + "\n")

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
            "Name the required CI checks scripts/test-fast-pr.sh does not run, at "
            "step granularity, with the local command for each."
        )
    )
    parser.add_argument("--list", action="store_true",
                        help="Print the full report, with a local command per check.")
    parser.add_argument("--brief", action="store_true",
                        help="Print the digest the spine appends to its PASS line.")
    parser.add_argument("--self-test", dest="self_test", action="store_true",
                        help="Run in-memory self-tests proving the guard fires, then exit.")
    parser.add_argument("--check", action="store_true",
                        help="Audit the map (the default when no mode flag is given).")
    parser.add_argument("--verbose", "-v", action="store_true")
    parser.add_argument("--ci", action="store_true",
                        help="Accepted for parity with sibling checkers; has no effect.")
    args = parser.parse_args(list(argv))

    if args.self_test:
        return run_self_tests(args.verbose)
    if args.list or args.brief:
        return run_report(brief=args.brief and not args.list)
    return run_check(args.verbose)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
