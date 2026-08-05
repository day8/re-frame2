#!/usr/bin/env python3
"""Tell somebody when the nightly reds — and stop talking once they know.

rf2-6sg25.  `.github/workflows/expensive-tests.yml` is the system of record for
every gate deliberately kept off the PR critical path (TESTING.md says so), and
until this script existed it had NO notification of any kind: the only way to
learn it was red was to open the Actions tab.  Measured over the thirty runs
ending 2026-08-04, eighteen were red, and 2026-07-09 through 2026-07-21 was
THIRTEEN CONSECUTIVE RED NIGHTS.  Nobody was told.  A gate that catches,
reports to nobody, and is believed anyway is the same fail-open shape as an
instrument that never fires — one tier up.

THE THRESHOLD, AND HOW IT WAS ARRIVED AT
----------------------------------------
An alert that fires every red night would have sent eighteen notifications in
thirty nights, and would have been muted inside a week; a muted alert is the
original fail-open in a new suit.  So the rules below were not chosen and then
justified — each one was measured against that real history with `--replay 30`,
and the ones that did not pay for themselves were dropped.

  1. FIRST RED OPENS A TRACKING ISSUE.  Loud.  (18 -> 18 so far.)

  2. WHILE IT IS OPEN, ONLY A *NEW FAILING STEP* IS LOUD.  The unit is not the
     run, it is the FAILURE SIGNATURE: the sorted set of `<job> -> <step>` pairs
     that actually went red.  Same set as last night -> the body's counter and
     run link are edited in place, and GitHub sends nothing for a body edit.
     A signature that SHRINKS is good news and is silent too.  This is what
     collapses the 13-night outage: it cost three notifications, and all three
     were a genuinely new step joining the failure — never a restatement.
     (18 -> 14.)

  3. RECOVERY CLOSES THE ISSUE ONLY AFTER `--close-after` CONSECUTIVE GREENS,
     DEFAULT 3.  Rule 2 alone was not enough, and the replay is what showed it:
     the Xray feature-matrix flake (rf2-7jevo) fails one night, passes the next,
     fails again — so closing on the first green meant reopening two nights
     later, and every flap cost a close AND an open.  That is where ten of the
     remaining fourteen notifications came from.  Three is read off the same
     data: the green runs between two flake failures were runs of 1 and 2, and
     every genuine recovery ran 3 or more green.  So 3 is the smallest K that
     sits above the flake and below the recoveries.  (14 -> 8.)

Net: eighteen red nights, EIGHT notifications, and not one of them a
restatement of something already reported.  Re-measure with `--replay` before
changing any of the three.

WHAT IT CANNOT SILENTLY DROP
----------------------------
A job that failed but reports no failed STEP — infrastructure failure, runner
loss, a job-level timeout that kills the run before a step records a conclusion
— would otherwise produce an empty signature and be classified GREEN.  That is
the exact fail-open this exercise is about, so such a job is synthesised into
the signature as `<job> -> (job <conclusion>, no failing step)` and alerted on
like any other.  `--self-test` pins it.

THE CHANNEL IS ONE FUNCTION
---------------------------
rf2-6sg25 left the channel to Mike deliberately.  A GitHub issue is what this
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
import re
import subprocess
import sys

LABEL = "nightly-red"
TITLE = "Nightly `expensive tests` is red"
SEP = " -> "
CLOSE_AFTER_DEFAULT = 3

MARKER_RE = re.compile(
    r"<!--\s*nightly-alert:v1\s+signature=(?P<sig>[0-9a-f]{12})"
    r"\s+reds=(?P<reds>\d+)\s+greens=(?P<greens>\d+)\s*-->"
)

FAILED = ("failure", "timed_out")
LOUD = ("open", "change", "close")


# ---------------------------------------------------------------------------
# Pure decision layer — no network, no side effects.
# ---------------------------------------------------------------------------

def derive_signature(jobs: list[dict]) -> list[str]:
    """The sorted `<job> -> <step>` pairs that went red in this run.

    An empty list means "nothing failed" and must never mean "we could not
    tell", so a completed job that failed with no failed step is synthesised in
    rather than dropped.  Jobs that are not `completed` are skipped, which is
    also how this script's own still-running job stays out of its own verdict.
    """
    entries: set[str] = set()
    for job in jobs:
        if job.get("status") != "completed":
            continue
        if job.get("conclusion") not in FAILED:
            continue
        steps = [s for s in (job.get("steps") or []) if s.get("conclusion") in FAILED]
        if steps:
            for step in steps:
                entries.add(f"{job['name']}{SEP}{step['name']}")
        else:
            entries.add(f"{job['name']}{SEP}(job {job.get('conclusion')}, no failing step)")
    return sorted(entries)


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
        "Opened, edited and closed by `.github/scripts/nightly_failure_alert.py` "
        "(rf2-6sg25). It is edited in place — which notifies nobody — while the "
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


def fetch_open_issue(repo: str) -> dict | None:
    out = gh(["api", f"repos/{repo}/issues?state=open&labels={LABEL}&per_page=100",
              "-q", ".[] | @json"])
    issues = [json.loads(line) for line in out.splitlines() if line.strip()]
    issues = [i for i in issues if "pull_request" not in i]
    issues.sort(key=lambda i: i["number"])
    return issues[0] if issues else None


def ensure_label(repo: str) -> None:
    # 422 == the label already exists, which IS the desired end state.
    gh(["api", "-X", "POST", f"repos/{repo}/labels",
        "-f", f"name={LABEL}", "-f", "color=b60205",
        "-f", "description=The nightly expensive-tests workflow is failing (rf2-6sg25)"],
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
    signature = derive_signature(fetch_jobs(repo, run_id))
    issue = fetch_open_issue(repo)
    action, detail = decide(signature, issue, close_after)
    run_url = f"https://github.com/{repo}/actions/runs/{run_id}"

    print(f"nightly-alert: run={run_id} failing-steps={len(signature)} "
          f"action={action} notifies={'yes' if detail['notifies'] else 'no'}")
    for entry in signature:
        print(f"  RED  {entry}")
    if issue:
        print(f"  tracking issue: #{issue['number']}")
    if dry_run:
        print("  (--dry-run: nothing was created, edited or closed)")
        return 0
    apply_action(repo, action, detail, signature, issue, run_url, assignee, close_after)
    return 0


def run_replay(repo: str, limit: int, close_after: int) -> int:
    """Walk the real run history oldest-first and report what this script WOULD
    have sent, beside what a naive per-run alert would have sent.  This is the
    evidence the thresholds were chosen against, not an illustration of them."""
    out = gh(["run", "list", "--workflow", "expensive-tests.yml", "--limit", str(limit),
              "--json", "databaseId,createdAt"])
    runs = list(reversed(json.loads(out)))
    issue: dict | None = None
    loud = reds = 0
    for entry in runs:
        run_id = str(entry["databaseId"])
        signature = derive_signature(fetch_jobs(repo, run_id))
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

    def job(name, conclusion, steps, status="completed"):
        return {"name": name, "status": status, "conclusion": conclusion,
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
    check("an in-progress job (this script's own) is ignored",
          derive_signature([job("notify", None, [], status="in_progress")]) == [])
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

    # --- flap damping: the rule the replay forced ----------------------------
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
