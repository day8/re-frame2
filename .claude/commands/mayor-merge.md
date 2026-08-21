---
description: Mayor Loop — merge green PRs (signal-driven + ≈30m cadence). On most signals, sweep open PRs and merge any green; never block on a CI watch.
---
MAYOR LOOP (merge PRs).

**`docs/the-mayor-method/loops.md` §1 is the body of this loop** — the five clauses and
why each exists, the draft filter, merge-the-queue-first, the after-each-merge drill, and
the three not-green cases. Read it there; it is not restated here. **This file pins the
concrete commands and this repository's measured numbers, and nothing else.**

Run on MOST signals, not just the cadence. **Never** `gh pr checks <pr> --watch` — one-shot
queries only, merge what passes now, re-check the rest next signal. Honor any operator
pause. If no open PRs, say so and stop.

## Sweep

```bash
gh pr list --state open --json number,title,isDraft   # isDraft true -> SKIP, re-check next signal
gh pr view <pr> --json headRefName,headRefOid         # resolve the head first; clauses 4 and 5 key to it
```

## The five clauses, as commands

**1 — band.** `gh pr checks <pr> --json state --jq 'length'`

**88 as measured 2026-08-16 13:59 +1000.** This is a MEASUREMENT, not a constant: it has
moved ten times in this programme, twice within forty minutes, and the most recent move was
the first DOWNWARD one — PR #8322 retired the `re-frame.ui` and `re-frame.freehand` trees
and took twelve check rows with them — so "the band only grows" will make you hunt for rows
that no longer exist. A stale-LOW band fails OPEN. The one command above is the whole
measurement, so re-measure on any tick where a merge is actually in question, on PRs merged
AFTER the last workflow change. When a total disagrees, **diff the check NAMES against a PR
you have measured** rather than comparing totals — band-minus-one is a stale base
(`gh pr update-branch`), an in-flight run whose rollup row does not exist yet (wait), or a
genuine short rollup, and only the names tell them apart. Band-plus-one is correct for a PR
that adds a required job. `scripts/check_fast_pr_gap.py --list` heads the same matrix from
the other side — **94** required checks the fast spine does not run, as of #8322.

Say "check ROWS" or "jobs", never the two welded together: a job carrying a `strategy.matrix`
is ONE job id and SEVERAL rows, a job that never runs on a PR still contributes a SKIPPED row,
and the two counts move by different amounts (#8322 moved 11 job ids and 12 rows).

**2 — states.** `gh pr checks <pr> --json name,state,bucket` — every `state` in
{`SUCCESS`, `SKIPPED`}. The field is `state`; this command has no `conclusion` field.

**3 — nothing nonterminal.** Require `COMPLETED`; never enumerate the blocking states.

**4 — runs at this head.**

```bash
gh run list --branch <headRefName> --commit <headRefOid> --limit 25 --json status,headSha,name
```

Test `(.status|ascii_upcase) != "COMPLETED"`. `gh pr checks` returns UPPERCASE and
`gh run list` lowercase; the unfolded comparison counts every finished run as nonterminal and
blocks the whole queue in lockstep. Pass the FULL 40-character oid — an abbreviated one
matches nothing, returns `[]`, and passes this clause vacuously.

**5 — matrix.** New job KEYS on the trunk since the merge base:

```bash
git diff <merge-base> origin/main -- .github/workflows/ | grep -cE '^\+  [a-z][a-z0-9_-]*:[[:space:]]*$'
```

**The two leading spaces are the test.** Job keys nest under `jobs:`, so the unindented form
matches nothing at all and is a constant zero that fails OPEN — it read 0 on all twenty of the
last `.github/workflows/` commits, including the five that genuinely added a job. Its own false
positives are the `on:` trigger keys, and those miss safe.

**`git diff`, never `gh pr diff` with a pathspec.** `gh pr diff` accepts one argument, rejects
the second on stderr, returns an empty stdout, and `grep -c` then prints a reassuring `0`.

Zero is necessary and not sufficient. Most of `test.yml` is gated on the
`detect_changed_surfaces` classifier, so a widened path glob flips an existing row from
SKIPPED to executed under an unchanged name, a `strategy.matrix` arm adds rows to an existing
job, and a `name:` change relabels one — none of which adds a job key and none of which moves
the band. **On zero, read the intervening workflow diff and confirm it is STEP-ONLY** before
skipping `gh pr update-branch`. Exercise any replacement discriminator against a change whose
answer is known NON-ZERO; a discriminator only ever tested where the answer is zero is untested.

## Merging

Read the file list against the bead — `git diff --name-only origin/main...<headRefName>`, the
three-dot form. Confirm a `## Quality gates` section is present.

`gh pr merge --rebase` — this repo rebases; `--squash` would collapse a worker's commits into
one. **No `--delete-branch`**: this repository sets `delete_branch_on_merge`, so the server
deletes the head branch as the merge lands, while the flag abandons the remote deletion
whenever a worktree still holds the branch locally and orphans the ref. `git push origin
--delete <branch>` survives only as the post-`MERGED` fallback for a ref the server will not
touch. Verify `state == MERGED` via `gh pr view` before any branch cleanup, and never delete a
branch before the merge succeeds — it auto-closes the PR.

`Base branch was modified` → `gh pr update-branch`, then re-check. During a burst it is a real
race, because a post-merge hook pushes `chore(beads): audit merged PR NNNN` to `main`
asynchronously: merge the whole queue first, then give the straggler an empty window.

The ONLY `--admin` this loop sanctions is `MERGEABLE=UNKNOWN`, GitHub's own mergeability
recompute lag. It is not a check, so it bypasses no clause, and it still waits for all five.

## After each merge — immediately, never batched

```bash
git fetch origin main && git merge --ff-only origin/main
git rev-parse HEAD; git rev-parse origin/main      # must be EQUAL; re-read both before believing a mismatch
```

**Never `git pull --ff-only origin main`**, and never wire either behind a pipe. The pull form
takes its merge target from `FETCH_HEAD`, a scratch file any concurrent git process in this
checkout rewrites, and a contaminated one does not fail honestly — measured on git 2.53, it
fast-forwarded the trunk onto a feature branch in one arrangement and in another aborted wearing
the *second* message below on a clean, zero-ahead tree where that remedy is inert. `origin/main`
is a ref, written atomically, so it cannot be captured that way. Both aborts below reproduce
verbatim under the merge form, so nothing is lost. `loops.md` §1, *After each merge*, has the
full argument.

An aborted fast-forward has two causes and the message says which — naming the remote cures the
silent no-op but neither abort, so read the text before reaching for the familiar fix:

* `local changes would be overwritten` — uncommitted tracker state. `sh scripts/beads-checkpoint.sh`,
  *then* `git checkout HEAD -- .beads`, *then* retry. That order and no other (CLAUDE.md,
  *Beads durability*).
* `Not possible to fast-forward` — your own checkpoint commit against the audit-hook commits
  `origin` gained while you made it, and the common case now that the checkpoint COMMITS.
  `.beads` is already clean, so the checkout above is inert. `git pull --rebase origin main`,
  **then `git push`** — the rebase replays your checkpoint on top and leaves you AHEAD BY ONE,
  which is the expected intermediate state rather than a second failure, and the equality check
  cannot pass until the push lands. If the audit-hook writer wins the race again the push is
  rejected: fetch, rebase, push again.

Verify that outcome by FOUR reads rather than the message: no rebase in progress
(`.git/rebase-merge` and `.git/rebase-apply` both absent), `git status` clean, `HEAD` equal to
`origin/main`, and the tracker row count equal in the working `.beads/issues.jsonl` and at `HEAD`.

Then verify the worker closed its bead (close it with a concrete cross-ref if not) and record
the decision on it. If anything was ROUTED into this PR, verify it landed in the diff before
closing its owner.

## Not green

A real, repeated touched-surface failure gets a fix-worker on the EXISTING branch running the
ACTUAL failing gate. Never `--admin`.

A failure BEFORE any repository code ran names itself in the failing step — an action download
returning 429, a runner setup step dying — so no second observation is needed:
`gh run rerun <id> --failed`, and hold the re-run to the same five clauses. When SEVERAL PRs
fail that way rather than one job twice, fleet size is the cause and the mayor says so.

A check that never TERMINATES is the third case. Read the clock against that job's own measured
cost — `gh run view <id> --json jobs` carries `startedAt`/`completedAt` per job, so three recent
successful runs on `main` give the baseline. Once a run has been re-run, `gh run view` serves
only the LATEST attempt; ask for the one you mean:

```bash
gh api repos/{owner}/{repo}/actions/runs/<id>/attempts/1/jobs --paginate
```

A timeout kill reports as `cancelled`, so compare elapsed against the job's declared
`timeout-minutes`: landing ON the cap is a timeout kill, well inside it is a superseding push
or a hand cancel. Then discriminate — UNRELATED jobs overrunning in the same run is a runner
wedge (`gh run cancel <id>`, `gh run rerun <id>`, re-check on the same five clauses); ONE job
overrunning alone on a surface the diff touches is a hang the change introduced, and takes a
fix-worker.
