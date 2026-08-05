#!/usr/bin/env python3
"""Every gate command must have a scheduled home, or say why it does not.

rf2-6ckzl remedy clause 4 / rf2-eegpw.  `implementation/package.json` is where
a gate is DEFINED; a workflow is where it RUNS.  Nothing held those two in
step, and the gap is invisible from either side: `npm run test:perf-bundle`
existed, did real work, and appeared in NO workflow, so the perf-bundle
positive control was never once executed by CI.  It was found only because
somebody happened to go looking.  That is the class this checker closes — not
one gate, the class.

It is the same defect shape as the classifier hole beside it (rf2-6ckzl) and
the silent nightly above it (rf2-6sg25): something reports success over a case
it never exercised.  A gate that runs nowhere reports nothing, and a
`package.json` full of `test:*` entries reads, to anyone auditing coverage, as
if it does.

THE RULE, NOT A LIST
--------------------
Every `test:*` / `bench:*` script in `implementation/package.json` must either

  (a) be reachable from a `npm run` in some `.github/workflows/*.yml` — direct,
      or through another npm script's command body (the closure is walked, so
      `test:script-policy` chaining thirty-odd checkers counts for all of
      them); or

  (b) carry an entry in `DISPOSITIONS` below, declaring WHY, in a kind whose
      premise this script then CHECKS.

A script that is neither is a hard failure.  Adding a gate to `package.json`
and forgetting to schedule it therefore cannot be silent any more, which is the
whole point: the next `test:perf-bundle` is caught on arrival rather than on
the next audit.

WHY THE DISPOSITIONS ARE CHECKED RATHER THAN BELIEVED
-----------------------------------------------------
A reason is a claim about the world, and claims rot — the sibling guard
`implementation/scripts/_rigorous-local-inventory.test.cjs` learnt this the
hard way (a pin reading "never in the nightly sweep" stayed in the file for six
weeks after a commit put it there).  So `covered-by` names the covering script
and this checker asserts THAT one is scheduled; `ci-runs-it-directly` names a
literal the workflow corpus must still contain.  Only `not-a-gate` is a bare
declaration, because "this produces records rather than a verdict" is not a
fact about the CI graph.

`unscheduled` is the honest kind: a gate that genuinely runs nowhere, named,
with the bead that will give it a home.  It is deliberately NOT an error, and
deliberately NOT stale-checked when the gate later gains a scheduled home:
that transition is the hole CLOSING, and reddening main for it would punish the
fix.  Deleting the entry afterwards is tidy-up, not an obligation.

Usage:
  check-gate-scheduling.py [--repo-root DIR] [--verbose]
  check-gate-scheduling.py --self-test [--verbose]
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys

NPM_RUN_RE = re.compile(r"npm run (?:--silent )?([A-Za-z0-9:._-]+)")
GATE_PREFIXES = ("test:", "bench:")

# Every gate command that is NOT reachable from a workflow, and why.  Each
# entry's `kind` is a CHECKED claim — see the module docstring.
#
#   covered-by          `by` is a scheduled npm script whose run exercises the
#                       same teeth.  CHECKED: `by` must itself be scheduled.
#   ci-runs-it-directly the workflow runs this gate's teeth without going
#                       through `npm run`.  CHECKED: `probe` must appear in the
#                       workflow corpus.
#   not-a-gate          produces artefacts or records; a non-zero exit is not a
#                       verdict about the tree.  Declaration only.
#   unscheduled         a real gate that really runs nowhere.  CHECKED: `bead`
#                       must be named.
DISPOSITIONS: dict[str, dict] = {
    "test:security": {
        "kind": "covered-by",
        "by": "test:cljs",
        "why": "`:node-test-security` selects `-security-cljs-test$`, a strict "
               "subset of the consolidated `:node-test` build's `cljs-test$`. "
               "The same namespaces run in the scheduled consolidated lane; "
               "this script is the cheap focused surface for local iteration",
    },
    "test:testbed-support": {
        "kind": "covered-by",
        "by": "test:cljs",
        "why": "`:node-test-testbed-support` selects "
               "`^re-frame\\.testbed\\..+-cljs-test$` — again a strict subset "
               "of `cljs-test$`, so the scheduled consolidated lane runs them",
    },
    "test:ui": {
        "kind": "covered-by",
        "by": "test:cljs",
        "why": "`:node-test-ui` selects `^re-frame\\.ui\\..+-cljs-test$`, a "
               "strict subset of `cljs-test$`. Its other half, "
               "`test:ui-isolation`, has its own scheduled step in test.yml's "
               "`cljs` job",
    },
    "test:cljs-isolation": {
        "kind": "ci-runs-it-directly",
        "probe": "node scripts/check-per-ns-isolation.cjs",
        "why": "test.yml's `cljs` job compiles the node-test bundle and then "
               "runs the per-namespace isolation checker against it directly, "
               "rather than paying for the recompile this script would",
    },
    "test:mcp-conformance": {
        "kind": "ci-runs-it-directly",
        "probe": "mcp-conformance-re-frame2-pair",
        "why": "an operator-facing wrapper that chains six MCP-conformance "
               "gates PR CI already runs as six separate jobs — the script's "
               "own header says so. It exists for a local one-command run",
    },
    "bench:hicasso": {
        "kind": "not-a-gate",
        "why": "a benchmark runner: it produces measurement records, not a "
               "verdict about the tree. Its sibling `bench:freehand-browser` "
               "DOES yield a verdict, which is why that one is scheduled "
               "(freehand-bench.yml) and pinned into the local rigorous sweep",
    },
    "test:perf-bundle": {
        "kind": "unscheduled",
        "bead": "rf2-eegpw",
        "why": "THE GATE THAT RAISED THIS CHECKER. It releases "
               ":examples/counter and :examples/counter-perf and greps the "
               "perf-OFF bundle for the absence, and the perf-ON twin for the "
               "presence, of the mark-and-measure call sites — and it has "
               "never run in CI. rf2-eegpw wires it into test.yml as "
               "`cljs-perf-bundle`; delete this entry when that lands",
    },
    "test:schemas-bundle": {
        "kind": "unscheduled",
        "bead": "rf2-6ckzl",
        "why": "releases `schemas-bundle-probe` + `schemas-bundle-probe-malli` "
               "and runs check-schemas-bundle.cjs. That checker is referenced "
               "by this npm script and by nothing else in the repo, so the "
               "malli-boundary bundle claim it asserts has no scheduled home",
    },
    "test:ui-warm-watch": {
        "kind": "unscheduled",
        "bead": "rf2-6ckzl",
        "why": "runs check-ui-warm-watch.cjs, which likewise is referenced by "
               "this npm script and by nothing else in the repo",
    },
    "test:cljs-perf-emit-nightly": {
        "kind": "unscheduled",
        "bead": "rf2-6ckzl",
        "why": "the sharpest instance: a build named for the nightly that is "
               "in no nightly. `:node-test-perf-nightly` selects "
               "`-emit-nightly-test$`, which `cljs-test$` does NOT match, so "
               "the `*_emit_nightly_test.cljs` namespaces run in this lane "
               "alone — and this lane runs nowhere",
    },
}

KINDS = {"covered-by", "ci-runs-it-directly", "not-a-gate", "unscheduled"}


def scheduled_scripts(scripts: dict[str, str], workflow_text: str) -> set[str]:
    """Every npm script reachable from a `npm run` in the workflow corpus,
    walked transitively through script bodies."""
    seeds = [s for s in NPM_RUN_RE.findall(workflow_text) if s in scripts]
    reached = set(seeds)
    queue = list(seeds)
    while queue:
        for child in NPM_RUN_RE.findall(scripts.get(queue.pop(), "")):
            if child in scripts and child not in reached:
                reached.add(child)
                queue.append(child)
    return reached


def audit(scripts: dict[str, str], workflow_text: str,
          dispositions: dict[str, dict]) -> list[str]:
    """Return the list of violations; empty means clean."""
    problems: list[str] = []
    scheduled = scheduled_scripts(scripts, workflow_text)
    gates = [s for s in scripts if s.startswith(GATE_PREFIXES)]

    for name, entry in dispositions.items():
        if name not in scripts:
            problems.append(
                f"{name}: DISPOSITIONS names a script that no longer exists in "
                f"implementation/package.json — delete the entry")
            continue
        kind = entry.get("kind")
        if kind not in KINDS:
            problems.append(
                f"{name}: unknown kind `{kind}` — must be one of "
                f"{'/'.join(sorted(KINDS))}")
            continue
        if kind == "covered-by":
            by = entry.get("by")
            if by not in scripts:
                problems.append(f"{name}: `by: {by}` is not a package.json script")
            elif by not in scheduled:
                problems.append(
                    f"{name}: pinned `covered-by: {by}`, but `{by}` is not "
                    f"scheduled by any workflow either — the cover is gone and "
                    f"this gate now runs nowhere")
        elif kind == "ci-runs-it-directly":
            probe = entry.get("probe") or ""
            if probe not in workflow_text:
                problems.append(
                    f"{name}: pinned `ci-runs-it-directly` on the probe "
                    f"`{probe}`, which no longer appears in any workflow — "
                    f"CI has stopped running this gate's teeth")
        elif kind == "unscheduled":
            if not entry.get("bead"):
                problems.append(
                    f"{name}: pinned `unscheduled` without a `bead` — a known "
                    f"hole has to be tracked somewhere")

    for gate in sorted(gates):
        if gate in scheduled or gate in dispositions:
            continue
        problems.append(
            f"{gate}: defined in implementation/package.json, invoked by no "
            f"workflow, and carrying no DISPOSITIONS entry. Either schedule it "
            f"or declare why it needs no schedule "
            f"(.github/scripts/check-gate-scheduling.py)")
    return problems


def load(repo_root: pathlib.Path) -> tuple[dict[str, str], str]:
    pkg = json.loads((repo_root / "implementation" / "package.json")
                     .read_text(encoding="utf-8"))
    wf_dir = repo_root / ".github" / "workflows"
    text = "\n".join(
        p.read_text(encoding="utf-8")
        for p in sorted(wf_dir.iterdir())
        if p.suffix in (".yml", ".yaml")
    )
    return pkg.get("scripts", {}), text


def run_check(repo_root: pathlib.Path, verbose: bool) -> int:
    scripts, workflow_text = load(repo_root)
    scheduled = scheduled_scripts(scripts, workflow_text)
    gates = sorted(s for s in scripts if s.startswith(GATE_PREFIXES))
    if verbose:
        for gate in gates:
            if gate in scheduled:
                state = "scheduled"
            elif gate in DISPOSITIONS:
                state = DISPOSITIONS[gate]["kind"]
            else:
                state = "UNDECLARED"
            print(f"  {state:20s} {gate}")
    problems = audit(scripts, workflow_text, DISPOSITIONS)
    if problems:
        print("gate-scheduling: FAIL")
        for p in problems:
            print(f"  - {p}")
        return 1
    unscheduled = [g for g in gates if g not in scheduled]
    print(f"gate-scheduling: {len(gates)} gate commands, "
          f"{len(gates) - len(unscheduled)} scheduled, "
          f"{len(unscheduled)} declared "
          f"({sum(1 for g in unscheduled if DISPOSITIONS[g]['kind'] == 'unscheduled')} "
          f"of them known holes with beads).")
    return 0


def self_test(verbose: bool) -> int:
    failures: list[str] = []

    def check(name: str, cond: bool, detail: str = "") -> None:
        if cond:
            if verbose:
                print(f"PASS {name}")
        else:
            failures.append(name)
            print(f"FAIL {name}{': ' + detail if detail else ''}")

    scripts = {
        "test:a": "node a.cjs",
        "test:b": "node b.cjs",
        "test:chain": "npm run test:a && npm run test:b",
        "test:orphan": "node orphan.cjs",
        "bench:thing": "node bench.cjs",
        "build:thing": "shadow-cljs release thing",
    }
    wf = "run: npm run test:chain\nrun: node teeth.cjs\n"

    reached = scheduled_scripts(scripts, wf)
    check("the closure walks through a chaining script",
          reached == {"test:chain", "test:a", "test:b"}, repr(reached))
    check("a non-gate prefix is never required to be scheduled",
          "build:thing" not in audit(scripts, wf, {
              "test:orphan": {"kind": "not-a-gate", "why": "x"},
              "bench:thing": {"kind": "not-a-gate", "why": "x"}}))

    # THE RATCHET: an undeclared, unscheduled gate is a hard failure.
    bare = audit(scripts, wf, {})
    check("an undeclared unscheduled gate FAILS",
          any("test:orphan" in p for p in bare) and any("bench:thing" in p for p in bare),
          repr(bare))
    check("...and a scheduled gate does not",
          not any(p.startswith("test:a") or p.startswith("test:chain") for p in bare))

    ok = audit(scripts, wf, {
        "test:orphan": {"kind": "covered-by", "by": "test:a", "why": "x"},
        "bench:thing": {"kind": "not-a-gate", "why": "x"}})
    check("a declared gate with a live cover passes", ok == [], repr(ok))

    gone = audit(scripts, "run: node teeth.cjs\n", {
        "test:orphan": {"kind": "covered-by", "by": "test:a", "why": "x"},
        "bench:thing": {"kind": "not-a-gate", "why": "x"}})
    check("a `covered-by` whose cover left CI FAILS",
          any("the cover is gone" in p for p in gone), repr(gone))

    probe_gone = audit(scripts, wf, {
        "test:orphan": {"kind": "ci-runs-it-directly", "probe": "node absent.cjs", "why": "x"},
        "bench:thing": {"kind": "not-a-gate", "why": "x"}})
    check("a `ci-runs-it-directly` whose probe left the workflows FAILS",
          any("stopped running this gate's teeth" in p for p in probe_gone))
    probe_ok = audit(scripts, wf, {
        "test:orphan": {"kind": "ci-runs-it-directly", "probe": "node teeth.cjs", "why": "x"},
        "bench:thing": {"kind": "not-a-gate", "why": "x"}})
    check("...and one whose probe is still there passes", probe_ok == [])

    beadless = audit(scripts, wf, {
        "test:orphan": {"kind": "unscheduled", "why": "x"},
        "bench:thing": {"kind": "not-a-gate", "why": "x"}})
    check("an `unscheduled` hole with no bead FAILS",
          any("has to be tracked somewhere" in p for p in beadless))

    phantom = audit(scripts, wf, {
        "test:vanished": {"kind": "not-a-gate", "why": "x"},
        "test:orphan": {"kind": "not-a-gate", "why": "x"},
        "bench:thing": {"kind": "not-a-gate", "why": "x"}})
    check("a DISPOSITIONS entry for a deleted script FAILS",
          any("no longer exists" in p for p in phantom))

    bad_kind = audit(scripts, wf, {
        "test:orphan": {"kind": "because-i-said-so", "why": "x"},
        "bench:thing": {"kind": "not-a-gate", "why": "x"}})
    check("an unknown kind FAILS", any("unknown kind" in p for p in bad_kind))

    # An `unscheduled` entry that LATER gains a schedule must NOT go red: that
    # transition is the hole closing, and reddening main would punish the fix.
    closed = audit({**scripts, "test:orphan": "node orphan.cjs"},
                   wf + "run: npm run test:orphan\n",
                   {"test:orphan": {"kind": "unscheduled", "bead": "rf2-x", "why": "x"},
                    "bench:thing": {"kind": "not-a-gate", "why": "x"}})
    check("an `unscheduled` gate that GAINS a schedule stays green",
          closed == [], repr(closed))

    if failures:
        print(f"\ncheck-gate-scheduling self-test: {len(failures)} failed "
              f"({', '.join(failures)}).")
        return 1
    print("check-gate-scheduling self-test: all checks passed.")
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--repo-root", default=None)
    p.add_argument("--self-test", action="store_true")
    p.add_argument("--verbose", "-v", action="store_true")
    args = p.parse_args()
    if args.self_test:
        return self_test(args.verbose)
    root = (pathlib.Path(args.repo_root) if args.repo_root
            else pathlib.Path(__file__).resolve().parents[2])
    return run_check(root, args.verbose)


if __name__ == "__main__":
    sys.exit(main())
