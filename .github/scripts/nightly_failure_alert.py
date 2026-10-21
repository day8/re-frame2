#!/usr/bin/env python3
"""Tell somebody when the nightly reds — and stop talking once they know.

`.github/workflows/expensive-tests.yml` is the system of record for every gate
deliberately kept off the PR critical path (TESTING.md says so), and it has no
notification of its own: without this script the only way to learn it is red is
to open the Actions tab.  Over one measured window of thirty runs, eighteen
were red, thirteen of them CONSECUTIVE.  A gate that catches, reports to
nobody, and is believed anyway is the same fail-open shape as an instrument
that never fires — one tier up.

THE THRESHOLD, AND THE MEASUREMENT BEHIND IT
--------------------------------------------
An alert that fires every red night would send eighteen notifications in those
thirty nights, and would be muted inside a week; a muted alert is the original
fail-open in a new suit.  So each rule below is measured against that real
history with `--replay 30`, and each earns its place by cutting notifications
that carry no news.

  1. FIRST RED OPENS A TRACKING ISSUE.  Loud.  (18 -> 18 so far.)

  2. WHILE IT IS OPEN, ONLY A *NEW FAILING STEP* IS LOUD.  The unit is not the
     run, it is the FAILURE SIGNATURE: the sorted set of `<job> -> <step>` pairs
     that actually went red.  Same set as last night -> the body's counter and
     run link are edited in place, and GitHub sends nothing for a body edit.
     A signature that SHRINKS is good news and is silent too.  This is what
     collapses a 13-night outage: in the replay it costs three notifications,
     each a genuinely new step joining the failure — never a restatement.
     (18 -> 14.)

  3. RECOVERY CLOSES THE ISSUE ONLY AFTER `--close-after` CONSECUTIVE GREENS,
     DEFAULT 3.  Rule 2 alone is not enough, as the replay shows: an
     intermittent gate (the Xray feature matrix, in that history) fails one
     night, passes the next, fails again — so closing on the first green would
     reopen two nights later, and every flap would cost a close AND an open.
     That accounts for ten of the remaining fourteen notifications.  Three is
     read off the same data: the green runs between two flake failures are
     runs of 1 and 2, and every genuine recovery runs 3 or more green.  So 3 is
     the smallest K that sits above the flake and below the recoveries.
     (14 -> 8.)

Net: eighteen red nights, EIGHT notifications, and not one of them a
restatement of something already reported.  Re-measure with `--replay` before
changing any of the three.

WHAT IT CANNOT SILENTLY DROP
----------------------------
An empty signature is read as GREEN, so every way of reaching one without
having actually observed a pass is a fail-open, and there are three.

A job that failed but reports no failed STEP — infrastructure failure, runner
loss, a job-level timeout that kills the run before a step records a conclusion
— is synthesised into the signature as `<job> -> (job <conclusion>, no failing
step)` and alerted on like any other.

A job that never reached a verdict at all — `cancelled`, `startup_failure` —
is the same fail-open by a different door, and worse, because it is silent in
BOTH directions: three cancelled nights in a row would have advanced the green
streak and CLOSED an open tracking issue, marking a system of record recovered
that was never observed to recover.  Such a job contributes `<job> -> (job
<conclusion>, no verdict)`.  The classification is `FAILED` and `ABORTED`
below; the exclusion of `skipped` from both is as deliberate as the inclusions
and is argued there.

A job that has not FINISHED — still `in_progress` or `queued` when this
script reads the run — has no verdict yet either.  This door is the easiest
to leave open, because this script's own job is always still running when it
reads, so "ignore unfinished jobs" looks like the way to keep it out of its
own verdict.  It is set aside alone, identified exactly: the unfinished job of
THIS run (`GITHUB_RUN_ID`) on THIS runner (`RUNNER_NAME`), since a runner
executes one job at a time.  Every other unfinished job contributes `<job> ->
(job <status>, no verdict)`.  The workflow's alerting job `needs:` every gate,
so a gate the API still reports running is a record lagging a job that has
ended — a job cancelled at its timeout can read `in_progress` for seconds
afterwards — and reading it as a pass is how a hung, cancelled night counts
toward closing the issue.  The lag can run to minutes: the run's job listing
has reported a gate `in_progress` sixteen minutes after it passed.  So each job
the listing reports unfinished is first re-read on its own, for up to a minute
(`settle_unfinished`); one that re-reads finished is graded by that record and
its listing is logged as STALE, never as a red step, and only a job still
unfinished after the last re-read contributes the `no verdict` entry.

The run's own conclusion is the backstop: a run that ended `cancelled` or
`startup_failure` is never green, whatever its jobs report, and contributes
`(run) -> (run <conclusion>, no verdict)` when nothing else is red.  A run has
no conclusion until every job, this script's included, has finished, so the
backstop bites under `--replay` and a `--run-id` of a finished run, never in
the live job.  `--self-test` pins every clause, in both directions.

THE CHANNEL IS ONE FUNCTION
---------------------------
The channel is deliberately replaceable.  A GitHub issue is what this
implements because it needs no secret beyond the run's own `GITHUB_TOKEN`, it
dedupes naturally (one open issue, edited in place), it carries the state this
script needs so nothing external has to be kept in step with it, and assigning
it reaches a human.  Everything channel-specific lives in `apply_action`;
swapping in Slack or email is a change to that function and to nothing else.
The decision layer — `derive_signature` and `decide` — is pure, is the whole of
the interesting logic, and is what `--self-test` covers.

Usage:
  nightly_failure_alert.py --repo O/R --run-id N [--assignee LOGIN]
  nightly_failure_alert.py --repo O/R --run-id N --dry-run  # decide, mutate nothing
  nightly_failure_alert.py --repo O/R --replay 30           # what would it have sent?
  nightly_failure_alert.py --self-test                      # the guard has teeth
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time

LABEL = "nightly-red"
TITLE = "Nightly `expensive tests` is red"
SEP = " -> "
CLOSE_AFTER_DEFAULT = 3

MARKER_RE = re.compile(
    r"<!--\s*nightly-alert:v1\s+signature=(?P<sig>[0-9a-f]{12})"
    r"\s+reds=(?P<reds>\d+)\s+greens=(?P<greens>\d+)\s*-->"
)

# A gate RAN and did not pass.  Applies to steps and to jobs.
FAILED = ("failure", "timed_out")

# A gate did NOT run to a verdict, because something stopped it.  Terminal, so
# there is nothing further to wait for, and NOT a pass — which is the whole
# point: everything absent from both tuples is read as green, so a conclusion
# that means "we never found out" has to be named here or it silently becomes
# "we found out it was fine".  `cancelled` and `startup_failure` are the two
# that occur here; the list is a statement of the rule, not a patch for one
# observed case.
#
# `skipped` is deliberately NOT in either tuple, and that is the load-bearing
# exclusion.  A skipped job is the ordinary consequence of a `needs:`
# dependency that did not succeed, and a skipped STEP is what every remaining
# step of a cancelled job reports.  Counting `skipped` would red the nightly on
# runs that are behaving exactly as designed, and an alert that fires on
# ordinary nights gets muted — which is the original fail-open in a new suit.
ABORTED = ("cancelled", "startup_failure")

# How long a job the run's listing reports unfinished is re-read before it is
# believed (`settle_unfinished`): five reads, the first immediate, then 15 s
# apart — a minute at most, inside the alerting job's ten-minute timeout.
REREAD_ROUNDS = 5
REREAD_DELAY_S = 15


# ---------------------------------------------------------------------------
# Pure decision layer — no network, no side effects.
# ---------------------------------------------------------------------------

def is_own_job(job: dict, own_runner: str | None) -> bool:
    """Is `job` the one this script is running in?  A runner executes one job
    at a time, so the unfinished job on this script's runner is this script's
    job, exactly.  With no runner identity — a run other than the one this
    process belongs to, or any local run — no job is."""
    return (bool(own_runner) and job.get("status") != "completed"
            and job.get("runner_name") == own_runner)


def derive_signature(jobs: list[dict], own_runner: str | None = None,
                     run_conclusion: str | None = None) -> list[str]:
    """The sorted `<job> -> <step>` pairs that went red in this run.

    An empty list means "nothing failed" and must never mean "we could not
    tell", so a completed job that failed with no failed step is synthesised in
    rather than dropped, and so is every job that has not finished, except this
    script's own (`is_own_job`).  `run_conclusion` is the backstop: a run that
    ended without a verdict is never green, whatever its jobs report.
    """
    entries: set[str] = set()
    for job in jobs:
        status = job.get("status")
        if status != "completed":
            if not is_own_job(job, own_runner):
                entries.add(f"{job['name']}{SEP}(job {status}, no verdict)")
            continue
        conclusion = job.get("conclusion")
        if conclusion in ABORTED:
            # No verdict at all.  Its steps are deliberately NOT read: the
            # remaining steps of a cancelled job all report `skipped`, and the
            # cheapest way to guarantee `skipped` can never leak into a
            # signature is to never look at them.  One entry per aborted job,
            # naming the abort — distinct from any failing-step entry, so a
            # night the run was killed cannot masquerade as the same failure as
            # the night before and be edited in silently.
            entries.add(f"{job['name']}{SEP}(job {conclusion}, no verdict)")
            continue
        if conclusion not in FAILED:
            continue
        steps = [s for s in (job.get("steps") or []) if s.get("conclusion") in FAILED]
        if steps:
            for step in steps:
                entries.add(f"{job['name']}{SEP}{step['name']}")
        else:
            entries.add(f"{job['name']}{SEP}(job {conclusion}, no failing step)")
    # Only when the jobs read green: an aborted run always has an aborted or
    # unfinished job behind it, and doubling that entry would change the
    # signature of every cancelled night of a known failure into a loud one.
    if not entries and run_conclusion in ABORTED:
        entries.add(f"(run){SEP}(run {run_conclusion}, no verdict)")
    return sorted(entries)


def settle_unfinished(jobs: list[dict], own_runner: str | None, reread, sleep,
                      rounds: int = REREAD_ROUNDS,
                      delay: float = REREAD_DELAY_S) -> tuple[list[dict], list[str]]:
    """Re-read each job the run's listing reports unfinished, this script's own
    aside, until it reads finished: at most `rounds` re-reads, the first
    immediate and the rest `delay` seconds apart.

    Returns the jobs with each re-read record in place of the listed one, and
    a line per job whose listing was STALE — reported unfinished, re-read
    finished.  The re-read record replaces the listed one whole, so a job that
    finished red is graded by its own steps.  A job still unfinished after the
    last round keeps its unfinished record, and `derive_signature` reds it.
    """
    jobs = list(jobs)
    listed = [job.get("status") for job in jobs]
    stale: list[str] = []
    for n in range(rounds):
        pending = [i for i, job in enumerate(jobs)
                   if job.get("status") != "completed" and not is_own_job(job, own_runner)]
        if not pending:
            break
        if n:
            sleep(delay)
        for i in pending:
            jobs[i] = reread(jobs[i])
            if jobs[i].get("status") == "completed":
                stale.append(f"{jobs[i]['name']} (listed {listed[i]}, re-read "
                             f"completed {jobs[i].get('conclusion')})")
    return jobs, stale


def signature_hash(signature: list[str]) -> str:
    return hashlib.sha256("\n".join(signature).encode("utf-8")).hexdigest()[:12]


def parse_marker(body: str | None) -> tuple[str | None, int, int]:
    """Recover (signature hash, red count, consecutive greens) from an issue
    body.  All the state this script needs lives on the issue itself, so there
    is no second store to drift."""
    if not body:
        return None, 0, 0
    m = MARKER_RE.search(body)
    if not m:
        return None, 0, 0
    return m.group("sig"), int(m.group("reds")), int(m.group("greens"))


def render_marker(sig_hash: str, reds: int, greens: int) -> str:
    return f"<!-- nightly-alert:v1 signature={sig_hash} reds={reds} greens={greens} -->"


def signature_from_body(body: str | None) -> list[str]:
    """The failing-step list this script last wrote into the issue body — the
    counterpart of the `- \\`entry\\`` lines `render_body` emits."""
    if not body:
        return []
    return re.findall(r"^- `(.+)`$", body, flags=re.MULTILINE)


def render_body(signature: list[str], detail: dict, run_url: str, close_after: int) -> str:
    reds, greens = detail["reds"], detail["greens"]
    lines = [
        render_marker(detail["sig_hash"], reds, greens),
        "",
    ]
    if greens:
        runs = "run" if greens == 1 else "runs"
        lines += [
            f"The nightly `expensive tests` workflow has been GREEN for the last "
            f"{greens} {runs}. This issue closes automatically after "
            f"{close_after} consecutive green runs — the gap exists so an "
            f"intermittent gate that passes tonight does not close and reopen "
            f"the issue every other night.",
            "",
            f"Latest run: {run_url}",
            "",
            "### Last known failing steps",
        ]
    else:
        runs = "run" if reds == 1 else "runs"
        lines += [
            f"The nightly `expensive tests` workflow is **red** — {reds} red "
            f"{runs} since this issue was opened.",
            "",
            f"Latest red run: {run_url}",
            "",
            "### Failing steps",
        ]
    lines += [""] + [f"- `{entry}`" for entry in signature] + [
        "",
        "---",
        "",
        "Opened, edited and closed by `.github/scripts/nightly_failure_alert.py`. "
        "It is edited in place — which notifies nobody — while the "
        "same steps keep failing, and comments only when a step that was not "
        "failing starts to. Run `python .github/scripts/nightly_failure_alert.py "
        "--repo <owner/name> --replay 30` to see what it would have sent over the "
        "last thirty runs.",
    ]
    return "\n".join(lines)


def render_change_comment(gained: list[str], signature: list[str], run_url: str) -> str:
    lines = [
        "A nightly step that was not failing is now failing.",
        "",
        f"Run: {run_url}",
        "",
        "**Newly failing**",
        "",
    ]
    lines += [f"- `{e}`" for e in gained]
    lines += ["", "**Everything currently failing**", ""]
    lines += [f"- `{e}`" for e in signature]
    return "\n".join(lines)


def decide(signature: list[str], issue: dict | None,
           close_after: int = CLOSE_AFTER_DEFAULT) -> tuple[str, dict]:
    """(action, detail) for this run.  `detail['notifies']` says whether a human
    hears about it.  Actions:

      open    first red, no tracking issue          -> NOTIFIES
      change  a step that was passing now fails     -> NOTIFIES
      shrink  the failure set got smaller           -> silent body edit
      touch   the same failure set as last run      -> silent body edit
      hold    green, but not yet `close_after` in a row -> silent body edit
      close   green for `close_after` runs running  -> NOTIFIES
      none    green with no tracking issue          -> nothing at all
    """
    prev_hash, reds, greens = parse_marker(issue.get("body") if issue else None)
    prev_sig = signature_from_body(issue.get("body") if issue else None)

    if not signature:
        if issue is None:
            return "none", {"sig_hash": signature_hash([]), "reds": 0, "greens": 0,
                            "signature": [], "notifies": False}
        greens += 1
        detail = {"sig_hash": prev_hash or signature_hash([]), "reds": reds,
                  "greens": greens, "signature": prev_sig}
        if greens >= close_after:
            detail["notifies"] = True
            return "close", detail
        detail["notifies"] = False
        return "hold", detail

    sig_hash = signature_hash(signature)
    if issue is None:
        return "open", {"sig_hash": sig_hash, "reds": 1, "greens": 0, "notifies": True}

    detail = {"sig_hash": sig_hash, "reds": reds + 1, "greens": 0}
    if prev_hash == sig_hash:
        detail["notifies"] = False
        return "touch", detail
    gained = [e for e in signature if e not in prev_sig]
    detail["gained"] = gained
    detail["notifies"] = bool(gained)
    return ("change" if gained else "shrink"), detail


# ---------------------------------------------------------------------------
# GitHub layer.
# ---------------------------------------------------------------------------

def gh(args: list[str], check: bool = True) -> str:
    proc = subprocess.run(
        ["gh", *args], capture_output=True, text=True, encoding="utf-8", errors="replace"
    )
    if check and proc.returncode != 0:
        raise RuntimeError(
            f"`gh {' '.join(args)}` exited {proc.returncode}\n{proc.stderr.strip()}"
        )
    return proc.stdout


def fetch_jobs(repo: str, run_id: str) -> list[dict]:
    out = gh(["api", f"repos/{repo}/actions/runs/{run_id}/jobs?per_page=100",
              "--paginate", "-q", ".jobs[] | @json"])
    return [json.loads(line) for line in out.splitlines() if line.strip()]


def fetch_job(repo: str, job_id: int) -> dict:
    return json.loads(gh(["api", f"repos/{repo}/actions/jobs/{job_id}"]))


def fetch_run_conclusion(repo: str, run_id: str) -> str | None:
    out = gh(["api", f"repos/{repo}/actions/runs/{run_id}", "-q", ".conclusion"]).strip()
    return out if out and out != "null" else None


def fetch_open_issues(repo: str) -> list[dict]:
    """Every open tracking issue, oldest first.

    Deliberately NOT `?labels=nightly-red`. That index is EVENTUALLY
    CONSISTENT: two invocations three seconds apart can both read "no tracking
    issue" and both open one, because the label-filtered list has not caught up
    with the first create. The consequence is not cosmetic — two issues, and
    the streak and signature state on them diverges — so the unfiltered list is
    the read and the label is matched here.

    The unfiltered list lags too, by a comparable few seconds, so this narrows
    the window rather than closing it. Two runs of
    this workflow are a day apart (cron) or minutes apart at the very closest
    (the post-merge canary), both orders of magnitude beyond that window, so a
    lock is not worth building. `collapse_duplicates` below is the net for
    whatever does slip through, and it is self-healing rather than merely
    diagnostic.
    """
    out = gh(["api", f"repos/{repo}/issues?state=open&per_page=100", "--paginate",
              "-q", ".[] | @json"])
    issues = [json.loads(line) for line in out.splitlines() if line.strip()]
    issues = [
        i for i in issues
        if "pull_request" not in i
        and any(lbl.get("name") == LABEL for lbl in (i.get("labels") or []))
    ]
    issues.sort(key=lambda i: i["number"])
    return issues


def collapse_duplicates(repo: str, issues: list[dict]) -> dict | None:
    """Adopt the oldest tracking issue and close any others.

    Belt to the braces above: whatever produced a second issue — the index lag,
    a hand-filed duplicate, two runs racing — the state must not split across
    two of them, and a stray one must not sit open forever contradicting the
    real one.
    """
    if not issues:
        return None
    keep, extras = issues[0], issues[1:]
    for extra in extras:
        gh(["api", "-X", "POST", f"repos/{repo}/issues/{extra['number']}/comments",
            "-f", f"body=Duplicate nightly tracking issue — #{keep['number']} is "
                  f"the one this workflow maintains. Closing this one so the "
                  f"failure history stays in one place."])
        gh(["api", "-X", "PATCH", f"repos/{repo}/issues/{extra['number']}",
            "-f", "state=closed"])
    return keep


def ensure_label(repo: str) -> None:
    # 422 == the label already exists, which IS the desired end state.
    gh(["api", "-X", "POST", f"repos/{repo}/labels",
        "-f", f"name={LABEL}", "-f", "color=b60205",
        "-f", "description=The nightly expensive-tests workflow is failing"],
       check=False)


def apply_action(repo: str, action: str, detail: dict, signature: list[str],
                 issue: dict | None, run_url: str, assignee: str | None,
                 close_after: int) -> None:
    """Everything channel-specific is here.  Swap GitHub issues for Slack or
    email by rewriting this function; the decision layer above is untouched."""
    if action == "none":
        return

    body_signature = signature or detail.get("signature") or []
    body = render_body(body_signature, detail, run_url, close_after)

    if action == "open":
        ensure_label(repo)
        args = ["api", "-X", "POST", f"repos/{repo}/issues",
                "-f", f"title={TITLE}", "-f", f"body={body}", "-f", f"labels[]={LABEL}"]
        if assignee:
            args += ["-f", f"assignees[]={assignee}"]
        gh(args)
        return

    number = issue["number"]
    if action == "close":
        gh(["api", "-X", "POST", f"repos/{repo}/issues/{number}/comments",
            "-f", f"body=The nightly has been green for {detail['greens']} runs "
                  f"running — {run_url}\n\nClosing."])
        gh(["api", "-X", "PATCH", f"repos/{repo}/issues/{number}",
            "-f", "state=closed"])
        return

    gh(["api", "-X", "PATCH", f"repos/{repo}/issues/{number}", "-f", f"body={body}"])
    if action == "change":
        gh(["api", "-X", "POST", f"repos/{repo}/issues/{number}/comments",
            "-f", f"body={render_change_comment(detail['gained'], signature, run_url)}"])


def run_live(repo: str, run_id: str, assignee: str | None, dry_run: bool,
             close_after: int) -> int:
    jobs = fetch_jobs(repo, run_id)
    # This script's own job exists only in the run it is running in.
    own_runner = (os.environ.get("RUNNER_NAME") or None
                  if os.environ.get("GITHUB_RUN_ID") == str(run_id) else None)
    jobs, stale = settle_unfinished(jobs, own_runner,
                                    lambda job: fetch_job(repo, job["id"]), time.sleep)
    signature = derive_signature(jobs, own_runner, fetch_run_conclusion(repo, run_id))
    open_issues = fetch_open_issues(repo)
    issue = open_issues[0] if open_issues else None
    action, detail = decide(signature, issue, close_after)
    run_url = f"https://github.com/{repo}/actions/runs/{run_id}"

    print(f"nightly-alert: run={run_id} failing-steps={len(signature)} "
          f"action={action} notifies={'yes' if detail['notifies'] else 'no'}")
    for entry in signature:
        print(f"  RED  {entry}")
    for entry in stale:
        print(f"  STALE listing, graded by its re-read: {entry}")
    # Said either way, so a run whose own job went unrecognised — and so
    # reads red, naming itself as unfinished — explains itself in its log.
    mine = [job["name"] for job in jobs if is_own_job(job, own_runner)]
    if mine:
        print(f"  own job, not graded: {', '.join(mine)} (runner {own_runner})")
    elif own_runner:
        print(f"  own job: no unfinished job of this run is on runner {own_runner}")
    if issue:
        print(f"  tracking issue: #{issue['number']}"
              + (f" (+{len(open_issues) - 1} duplicate(s) to close)"
                 if len(open_issues) > 1 else ""))
    if dry_run:
        print("  (--dry-run: nothing was created, edited or closed)")
        return 0
    collapse_duplicates(repo, open_issues)
    apply_action(repo, action, detail, signature, issue, run_url, assignee, close_after)
    return 0


def run_replay(repo: str, limit: int, close_after: int) -> int:
    """Walk the real run history oldest-first and report what this script WOULD
    have sent, beside what a naive per-run alert would have sent.  This is the
    evidence the thresholds are measured against, not an illustration of them."""
    out = gh(["run", "list", "--workflow", "expensive-tests.yml", "--limit", str(limit),
              "--json", "databaseId,createdAt,conclusion"])
    runs = list(reversed(json.loads(out)))
    issue: dict | None = None
    loud = reds = 0
    for entry in runs:
        run_id = str(entry["databaseId"])
        signature = derive_signature(fetch_jobs(repo, run_id),
                                     run_conclusion=entry.get("conclusion") or None)
        reds += 1 if signature else 0
        action, detail = decide(signature, issue, close_after)
        loud += 1 if detail["notifies"] else 0
        head = "; ".join(e.split(SEP)[-1] for e in signature[:2])
        if len(signature) > 2:
            head += f"  (+{len(signature) - 2} more)"
        print(f"{entry['createdAt'][:10]}  {'RED  ' if signature else 'green'}  "
              f"{action:6s} {'NOTIFY' if detail['notifies'] else '      '}  {head}")
        if action == "close":
            issue = None
        elif action != "none":
            body = render_body(signature or detail.get("signature") or [], detail,
                               f"https://github.com/{repo}/actions/runs/{run_id}",
                               close_after)
            issue = {"number": 0, "body": body}
    print()
    print(f"replayed {len(runs)} runs, {reds} red, close-after={close_after}")
    print(f"  naive per-run alert:     {reds} notifications")
    print(f"  this script:             {loud} notifications")
    return 0


# ---------------------------------------------------------------------------
# Self-test.
# ---------------------------------------------------------------------------

def self_test(verbose: bool) -> int:
    failures: list[str] = []

    def check(name: str, cond: bool, detail: str = "") -> None:
        if cond:
            if verbose:
                print(f"PASS {name}")
        else:
            failures.append(name)
            print(f"FAIL {name}{': ' + detail if detail else ''}")

    def job(name, conclusion, steps, status="completed", runner=None):
        return {"name": name, "status": status, "conclusion": conclusion,
                "runner_name": runner,
                "steps": [{"name": n, "conclusion": c} for n, c in steps]}

    def issue_after(action_detail, signature, close_after=CLOSE_AFTER_DEFAULT):
        detail = action_detail[1]
        return {"number": 1,
                "body": render_body(signature or detail.get("signature") or [],
                                    detail, "u", close_after)}

    # --- signature derivation -------------------------------------------------
    check("a wholly green run has an empty signature",
          derive_signature([job("A", "success", [("one", "success")])]) == [])
    red = [job("A", "success", [("one", "success")]),
           job("Gates", "failure", [("Gate x", "success"), ("Gate y", "failure")])]
    check("only the failing step of a failing job is in the signature",
          derive_signature(red) == [f"Gates{SEP}Gate y"], repr(derive_signature(red)))
    check("a timed_out step counts as failing",
          derive_signature([job("G", "failure", [("y", "failure"), ("z", "timed_out")])])
          == [f"G{SEP}y", f"G{SEP}z"])
    # THE FAIL-OPEN GUARD.
    headless = derive_signature([job("mcp-live", "failure", [("install", "success")])])
    check("a failed job with NO failed step is still red",
          headless == [f"mcp-live{SEP}(job failure, no failing step)"], repr(headless))

    # THE SAME FAIL-OPEN BY THE OTHER DOOR: a job that reached no verdict.
    cancelled = derive_signature([job("Gates", "cancelled",
                                      [("Gate x", "cancelled"), ("Gate y", "skipped")])])
    check("a CANCELLED job is red, and names the abort rather than a step",
          cancelled == [f"Gates{SEP}(job cancelled, no verdict)"], repr(cancelled))
    check("...so the `skipped` steps a cancellation leaves behind cannot leak in",
          not any("Gate y" in e for e in cancelled), repr(cancelled))
    startup = derive_signature([job("Gates", "startup_failure", [])])
    check("a STARTUP_FAILURE job is red too (the rule is not cancellation-specific)",
          startup == [f"Gates{SEP}(job startup_failure, no verdict)"], repr(startup))

    # THE OTHER DIRECTION, and the more expensive mistake of the two: classifying `skipped` would red the nightly on ordinary nights and
    # get the whole alert muted.  A skipped job is the normal consequence of a
    # `needs:` dependency that did not succeed — already reported by the job
    # that actually failed.
    # Both shapes at once: the STEPS after a failing step skip, and a whole
    # dependent JOB skips.  Neither is news.
    with_skipped = derive_signature([
        job("Gates", "failure", [("Gate x", "success"), ("Gate y", "failure"),
                                 ("Gate z", "skipped")]),
        job("downstream", "skipped", [("Publish", "skipped")]),
    ])
    check("a benign SKIPPED dependent is NOT red (only the job that failed is)",
          with_skipped == [f"Gates{SEP}Gate y"], repr(with_skipped))
    all_green = derive_signature([job("A", "success", [("one", "success")]),
                                  job("downstream", "skipped", [("Publish", "skipped")])])
    check("...and a green run with a skipped dependent stays wholly green",
          all_green == [], repr(all_green))

    # --- an unfinished job has no verdict either ------------------------------
    # This script's own job is always still running when it reads the run, so
    # it alone is set aside, identified exactly: this run, this runner.  Every
    # other unfinished job is red, because the alerter `needs:` every gate — one
    # the API still reports running is a record lagging a job that has ended,
    # the way a job cancelled at its timeout can read `in_progress` for seconds
    # afterwards.
    me = "GitHub Actions 9"
    own = job("Nightly result", None, [], status="in_progress", runner=me)
    check("this script's OWN in-progress job is ignored",
          derive_signature([own], own_runner=me) == [])
    hung = job("Browser", None, [], status="in_progress", runner="GitHub Actions 7")
    as_seen = derive_signature([job("A", "success", [("one", "success")]), hung, own],
                               own_runner=me)
    check("ANOTHER job still in_progress is red, and named as unfinished",
          as_seen == [f"Browser{SEP}(job in_progress, no verdict)"], repr(as_seen))
    queued = derive_signature([job("Late", None, [], status="queued"), own],
                              own_runner=me)
    check("...and so is a job still queued, which has no runner at all",
          queued == [f"Late{SEP}(job queued, no verdict)"], repr(queued))
    anon = derive_signature([own])
    check("with no runner identity nothing is own, so every unfinished job is red",
          anon == [f"Nightly result{SEP}(job in_progress, no verdict)"], repr(anon))

    # --- a lagging listing is re-read before it is believed -------------------
    def rereader(*records):
        """A re-read answering `records` in turn, then the last one forever."""
        calls: list[str] = []

        def reread(listed):
            calls.append(listed["name"])
            return records[min(len(calls), len(records)) - 1]
        return reread, calls

    lagging = job("MCP live", None, [], status="in_progress", runner="GitHub Actions 7")
    reread, calls = rereader(job("MCP live", "success", [("conformance", "success")]))
    naps: list[int] = []
    settled, stale = settle_unfinished([job("A", "success", [("one", "success")]), lagging, own],
                                       me, reread, naps.append)
    check("a job LISTED in_progress that re-reads as passed is graded green",
          derive_signature(settled, own_runner=me) == [], repr(settled))
    check("...and its listing is reported as stale, never as a red step",
          stale == ["MCP live (listed in_progress, re-read completed success)"], repr(stale))
    check("...after one immediate re-read, and this script's own job is never re-read",
          calls == ["MCP live"] and naps == [], repr((calls, naps)))
    for verdict, steps, expected in [
            ("failure", [("conformance", "failure")], f"MCP live{SEP}conformance"),
            ("cancelled", [("conformance", "cancelled")], f"MCP live{SEP}(job cancelled, no verdict)")]:
        reread, _ = rereader(job("MCP live", verdict, steps))
        settled, _ = settle_unfinished([lagging, own], me, reread, naps.append)
        check(f"a lagging job that re-reads as {verdict.upper()} is graded by that record, red",
              derive_signature(settled, own_runner=me) == [expected], repr(settled))
    reread, calls = rereader(lagging)
    naps = []
    settled, stale = settle_unfinished([lagging, own], me, reread, naps.append, rounds=3, delay=7)
    check("a job STILL in_progress after every re-read stays red, named as unfinished",
          derive_signature(settled, own_runner=me) == [f"MCP live{SEP}(job in_progress, no verdict)"]
          and stale == [], repr((settled, stale)))
    check("...and the re-reading is bounded: one per round, with the waits between rounds",
          calls == ["MCP live"] * 3 and naps == [7, 7], repr((calls, naps)))

    # A run that ended without a verdict is never green, whatever its jobs say.
    all_pass = [job("A", "success", [("one", "success")])]
    cancelled_run = derive_signature(all_pass, run_conclusion="cancelled")
    check("a CANCELLED run is red even when every job reads green",
          cancelled_run == [f"(run){SEP}(run cancelled, no verdict)"], repr(cancelled_run))
    startup_run = derive_signature(all_pass, run_conclusion="startup_failure")
    check("...and so is a STARTUP_FAILURE run",
          startup_run == [f"(run){SEP}(run startup_failure, no verdict)"], repr(startup_run))
    already_red = derive_signature([job("Gates", "cancelled", [])], run_conclusion="cancelled")
    check("a run its jobs already red keeps their signature (the run entry adds nothing)",
          already_red == [f"Gates{SEP}(job cancelled, no verdict)"], repr(already_red))
    check("a successful or unfinished run conclusion adds nothing",
          derive_signature(all_pass, run_conclusion="success") == []
          and derive_signature(all_pass, run_conclusion=None) == [])
    check("the signature is order-independent",
          derive_signature([job("G", "failure", [("b", "failure"), ("a", "failure")])])
          == derive_signature([job("G", "failure", [("a", "failure"), ("b", "failure")])]))

    # --- decision table -------------------------------------------------------
    sig = [f"Gates{SEP}Gate y"]
    grew = sig + [f"Slow{SEP}routing"]
    swapped = [f"Gates{SEP}Gate z"]

    opened = decide(sig, None)
    check("red + no issue                    -> open, LOUD",
          opened == ("open", {"sig_hash": signature_hash(sig), "reds": 1,
                              "greens": 0, "notifies": True}), repr(opened))
    iss = issue_after(opened, sig)

    same = decide(sig, iss)
    check("red + identical signature         -> touch, silent",
          same[0] == "touch" and not same[1]["notifies"])
    check("...and the red counter advances", same[1]["reds"] == 2)

    bigger = decide(grew, iss)
    check("red + a NEW failing step          -> change, LOUD",
          bigger[0] == "change" and bigger[1]["notifies"])
    check("...and the comment names only what is new",
          bigger[1]["gained"] == [f"Slow{SEP}routing"])

    smaller = decide(sig, issue_after(decide(grew, iss), grew))
    check("red + a SHRINKING signature       -> shrink, silent (good news)",
          smaller[0] == "shrink" and not smaller[1]["notifies"], repr(smaller))

    swap = decide(swapped, iss)
    check("red + a swapped signature         -> change, LOUD",
          swap[0] == "change" and swap[1]["notifies"])

    nothing = decide([], None)
    check("green + no issue                  -> none, silent",
          nothing[0] == "none" and not nothing[1]["notifies"])

    # --- flap damping: the rule the replay justifies -------------------------
    g1 = decide([], iss)
    check("green 1 of 3 + open issue         -> hold, silent",
          g1[0] == "hold" and not g1[1]["notifies"])
    check("...and the held body still names the last known failing steps",
          signature_from_body(issue_after(g1, [])["body"]) == sig)
    g2 = decide([], issue_after(g1, []))
    check("green 2 of 3                      -> hold, silent", g2[0] == "hold")
    g3 = decide([], issue_after(g2, []))
    check("green 3 of 3                      -> close, LOUD",
          g3[0] == "close" and g3[1]["notifies"], repr(g3))
    # A flake that reds again before the third green must NOT have closed.
    back = decide(sig, issue_after(g2, []))
    check("a flake that reds on green-3 night reuses the SAME issue, silently",
          back[0] == "touch" and not back[1]["notifies"], repr(back))

    # --- a cancelled night is not a green night ------------------------------
    # Without this rule, `derive_signature` would return [] for a cancelled run
    # and `decide([])` would read that as green.  Two consequences, and they are
    # asserted SEPARATELY because they fail independently: a cancelled run
    # would record no incident at all, and — the dangerous one — three of them
    # in a row would close an open tracking issue, marking a system of record
    # recovered that was never observed to recover.
    cancelled_sig = derive_signature(
        [job("Gates", "cancelled", [("Gate x", "cancelled"), ("Gate y", "skipped")])])
    check("a cancelled run has a signature at all (it is not green)",
          cancelled_sig != [], repr(cancelled_sig))

    from_scratch = decide(cancelled_sig, None)
    check("cancelled + no issue              -> open, LOUD (never `none`)",
          from_scratch[0] == "open" and from_scratch[1]["notifies"], repr(from_scratch))

    # DIRECTION 1: it cannot count toward recovery.
    after_cancel = decide(cancelled_sig, iss)
    check("a CANCELLED night does not advance the green streak",
          after_cancel[1]["greens"] == 0, repr(after_cancel))
    check("...it reads as a changed failure, LOUD (the run never gave a verdict)",
          after_cancel[0] == "change" and after_cancel[1]["notifies"], repr(after_cancel))

    # DIRECTION 2: it cannot close an open nightly-red issue.  The sequence
    # that would close it: one real red, then `close_after` cancellations.
    iss_c, actions = issue_after(decide(sig, None), sig), []
    for _ in range(CLOSE_AFTER_DEFAULT + 1):
        a = decide(cancelled_sig, iss_c)
        actions.append(a[0])
        iss_c = issue_after(a, cancelled_sig)
    check(f"{CLOSE_AFTER_DEFAULT + 1} CANCELLED nights running NEVER close the issue",
          "close" not in actions, repr(actions))
    check("...and only the first is loud; the rest are silent restatements",
          actions == ["change"] + ["touch"] * CLOSE_AFTER_DEFAULT, repr(actions))

    # --- neither an unfinished job nor an aborted run advances the streak ----
    # The exact shape that reads green when unfinished jobs are ignored: an open
    # issue one green into its streak, then a night whose gates all passed but
    # one, still reported running beside this script's own job.
    one_green = issue_after(decide([], iss), [])
    for label, night in [("another job unfinished", as_seen),
                         ("the run cancelled", cancelled_run)]:
        verdict = decide(night, one_green)
        check(f"a night with {label} does not advance the green streak",
              verdict[0] not in ("hold", "close") and verdict[1]["greens"] == 0,
              repr(verdict))

    # --- the two measured claims, as assertions ------------------------------
    iss2, loud = None, 0
    for _ in range(13):                       # 13 identical red nights
        a = decide(sig, iss2)
        loud += 1 if a[1]["notifies"] else 0
        iss2 = issue_after(a, sig)
    for _ in range(CLOSE_AFTER_DEFAULT):      # then a genuine recovery
        a = decide([], iss2)
        loud += 1 if a[1]["notifies"] else 0
        iss2 = None if a[0] == "close" else issue_after(a, [])
    check("13 identical red nights + a recovery == 2 notifications",
          loud == 2, f"got {loud}")

    iss3, loud = None, 0
    for red_night in [True, False, True, False, True, False]:  # a 50% flake
        a = decide(sig if red_night else [], iss3)
        loud += 1 if a[1]["notifies"] else 0
        iss3 = None if a[0] == "close" else issue_after(a, sig if red_night else [])
    check("a six-run 50% flake == 1 notification (not six)", loud == 1, f"got {loud}")

    # --- the duplicate hazard of an eventually consistent label index --------
    labelled = {"labels": [{"name": LABEL}]}
    unlabelled = {"labels": [{"name": "bug"}]}
    listing = [
        {"number": 9, **unlabelled},
        {"number": 11, **labelled},
        {"number": 12, "pull_request": {}, **labelled},
        {"number": 10, **labelled},
    ]
    kept = [i for i in listing
            if "pull_request" not in i
            and any(l["name"] == LABEL for l in i["labels"])]
    kept.sort(key=lambda i: i["number"])
    check("the tracking-issue read matches on the label and skips PRs",
          [i["number"] for i in kept] == [10, 11], repr(kept))
    check("...and the OLDEST is the one adopted, so duplicates cannot split state",
          kept[0]["number"] == 10)

    # --- marker round-trip ----------------------------------------------------
    body = render_body(sig, {"sig_hash": signature_hash(sig), "reds": 7, "greens": 0},
                       "u", CLOSE_AFTER_DEFAULT)
    check("the marker round-trips through a rendered body",
          parse_marker(body) == (signature_hash(sig), 7, 0))
    check("a body with no marker reads as a fresh signature",
          parse_marker("no marker here") == (None, 0, 0))
    check("the signature round-trips through a rendered body",
          signature_from_body(body) == sig)

    if failures:
        print(f"\nnightly_failure_alert self-test: {len(failures)} failed "
              f"({', '.join(failures)}).")
        return 1
    print("nightly_failure_alert self-test: all checks passed.")
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--repo", help="owner/name")
    p.add_argument("--run-id", help="the workflow run to report on")
    p.add_argument("--assignee", default=None,
                   help="GitHub login to assign a newly opened issue to (omit for none)")
    p.add_argument("--close-after", type=int, default=CLOSE_AFTER_DEFAULT, metavar="K",
                   help=f"consecutive green runs before the issue closes "
                        f"(default {CLOSE_AFTER_DEFAULT}; see the module docstring)")
    p.add_argument("--dry-run", action="store_true",
                   help="decide and print; create, edit and close nothing")
    p.add_argument("--replay", type=int, metavar="N",
                   help="replay the last N real runs and count the notifications")
    p.add_argument("--self-test", action="store_true")
    p.add_argument("--verbose", "-v", action="store_true")
    args = p.parse_args()

    if args.self_test:
        return self_test(args.verbose)
    if args.replay:
        if not args.repo:
            p.error("--replay needs --repo")
        return run_replay(args.repo, args.replay, args.close_after)
    if not args.repo or not args.run_id:
        p.error("--repo and --run-id are required (or use --self-test / --replay)")
    return run_live(args.repo, args.run_id, args.assignee, args.dry_run, args.close_after)


if __name__ == "__main__":
    sys.exit(main())
