---
description: Mayor Loop — worktree hygiene (≈60m cadence). Prune merged branches; reap a worktree only once its agent has reported.
---
MAYOR LOOP (worktree hygiene). Run `git worktree list`, `git branch --list 'worker/*'`, `git branch -r --list 'origin/worker/*'`.

**Reap a worktree only once its owning agent has delivered a completion report.** Not on merge, not on cleanliness, not on quiet. Six proxies for "this worker is finished" were adopted in a single session and every one of them killed a live gate run — merge state most of all, because a merged PR is the worker's last *reported* act, not its last act, and it may already be mid-gate on a second. Twice the wreckage surfaced as a red naming real, present files as missing, and was read as a genuine regression. The signals that have never lied are reads rather than inferences: the agent's own completion report, and `SendMessage`'s response shape (*"queued for delivery"* = alive; *"had no active task; resumed from transcript"* = idle). Waiting costs one stale directory. **And one worker is not one worktree** — a worker may build a second for its gate run, so an unfamiliar worktree belongs to someone until its agent reports. (The full six, and how each failed, are in `docs/the-mayor-method/bootstrap.md`; do not add a seventh.)

**Never remove a worktree with a bare `git worktree remove`.** Worker worktrees junction `implementation/node_modules` at the mayor checkout's REAL `node_modules`, and `git worktree remove` follows that reparse point and deletes THROUGH it — the mayor's real `node_modules` is emptied, `git` still exits 0, and every gate run from the mayor checkout then fails looking like something else entirely. Recovery is `npm ci --prefix implementation` from the mayor checkout. This procedure used to spell the safe sequence out as prose and the class recurred anyway, so the sequence now lives in a script that does it for you:

```sh
sh scripts/remove-worker-worktree.sh <worktree-path> [<worktree-path>...]
# Windows: powershell -ExecutionPolicy Bypass -File scripts/remove-worker-worktree.ps1 <path>
```

It snapshots every real `node_modules` in the mayor checkout, unlinks each link under the worktree (the LINK only — never a recursive delete), verifies each is gone, THEN calls `git worktree remove`, then re-checks the snapshot and **fails loudly if the signature moved**, naming the recovery command. Add `--force` for a dirty worktree, `--dry-run` to see what it would do, `--self-test` to prove the disarm against a throwaway junction before you trust it. Read its output: `DISARMED=` lines say a junction was found and neutralised, `NO_LINKS=` says the worktree had none (a spec-only or fresh worktree legitimately does not), and `CANARY_BEFORE=`/`CANARY_AFTER=` must match — each is `<immediate-entries>/<recursive-files>/<sentinels>`, e.g. `103/2933/2`, because a top-level count alone cannot see files vanishing from under packages whose directories survive.

**A failed removal has modes that are not interchangeable** (rf2-p0m6m — nine worktrees were read as locked, waited out for two days, and were all simply dirty):

- `REMOVE_REFUSED_DIRTY=` — the tree holds modified or untracked files and git refuses. The script prints each one tagged `[build output]` or `[KEEP]`. **Waiting never clears this**; nothing is locked and waiting does not add a flag.
- `REMOVE_REFUSED_KEEP=` — `--force-disposable` stopped because the tree holds something that is not build output. Nothing was deleted.
- `REMOVE_FAILED=` — the tree is *clean and intact* and a live shadow-cljs/Node process still holds a handle. That one is genuinely transient: note it and retry later.
- `REMOVE_PARTIAL=` — a **husk**: git deregistered the worktree and deleted its `.git`, then hit the lock and stopped. `git worktree list` no longer shows it, `git worktree prune` has nothing to clear, and `git status` inside it *fails and prints nothing*, which is exactly why a clean/dirty check reads a husk as clean. Retrying is futile; the script has already disarmed the junction, so an ordinary `rm -rf <path>` once the lock clears is all that remains. **A partial removal kills a running gate exactly as a complete one does** — never read it as "nothing happened".

Exit 1 means the worktree is INTACT and this script is still the right tool; **exit 3** means it is a husk and no longer is.

**Sweep with `--force-disposable`, never a blanket `--force`.** It force-removes only when every untracked path is build or gate output (`logs/`, `bench-logs/`, `out/`, `.shadow-cljs/`, `*.log`), and refuses any tree holding a modified tracked file, a note or a draft. That guard is not hypothetical: when it was written, `band-ymi6j` held four `ladder-*.md` analysis files for an **open** bead and three other worktrees carried uncommitted source and doc edits — a blanket `--force` would have destroyed all of it silently, which is far worse than a worktree that will not reap. Keep `--force` for a specific tree you have looked at and decided about.

`--dry-run` reports the whole partition up front — `WOULD_REMOVE=` (clean), `WOULD_NEED_FORCE=` (all build output, sweepable) or `WOULD_REFUSE_KEEP=` (needs a human first) — so survey before you sweep, and hand the `[KEEP]` trees back to the operator rather than deciding for them.

Delete merged local + origin worker branches (NEVER delete a branch whose PR is not verifiably MERGED). Then `git remote prune origin` for stale tracking refs and clear any stray stashes. **Merge state is the test for a branch, never for a worktree** — conflating the two is how the reaping rule above went wrong. An open PR is one more reason to leave a worktree alone; the test is still its agent's report. Report what was pruned, what was left locked, and which (if any) needs a process-kill or reboot to clear.
