---
description: Mayor Loop — worktree hygiene (≈60m cadence). Prune merged worktrees/branches; never touch open-PR worktrees.
---
MAYOR LOOP (worktree hygiene). Run `git worktree list`, `git branch --list 'worker/*'`, `git branch -r --list 'origin/worker/*'`. For each worker worktree: verify its PR via `gh pr view <#> --json state` — only if state==MERGED, prune it. **Never remove a worktree with a bare `git worktree remove`.** Worker worktrees junction `implementation/node_modules` at the mayor checkout's REAL `node_modules`, and `git worktree remove` follows that reparse point and deletes THROUGH it — the mayor's real `node_modules` is emptied, `git` still exits 0, and every gate run from the mayor checkout then fails looking like something else entirely. Recovery is `npm ci --prefix implementation` from the mayor checkout. This procedure used to spell the safe sequence out as prose and the class recurred anyway, so the sequence now lives in a script that does it for you:

```sh
sh scripts/remove-worker-worktree.sh <worktree-path> [<worktree-path>...]
# Windows: powershell -ExecutionPolicy Bypass -File scripts/remove-worker-worktree.ps1 <path>
```

It snapshots every real `node_modules` in the mayor checkout, unlinks each link under the worktree (the LINK only — never a recursive delete), verifies each is gone, THEN calls `git worktree remove`, then re-checks the snapshot and **fails loudly if the signature moved**, naming the recovery command. Add `--force` for a dirty worktree, `--dry-run` to see what it would do, `--self-test` to prove the disarm against a throwaway junction before you trust it. Read its output: `DISARMED=` lines say a junction was found and neutralised, `NO_LINKS=` says the worktree had none (a spec-only or fresh worktree legitimately does not), and `CANARY_BEFORE=`/`CANARY_AFTER=` must match — each is `<immediate-entries>/<recursive-files>/<sentinels>`, e.g. `103/2933/2`, because a top-level count alone cannot see files vanishing from under packages whose directories survive.

**A failed removal has modes that are not interchangeable** (rf2-p0m6m — nine worktrees were read as locked, waited out for two days, and were all simply dirty):

- `REMOVE_REFUSED_DIRTY=` — the tree holds modified or untracked files and git refuses. The script prints each one tagged `[build output]` or `[KEEP]`. **Waiting never clears this**; nothing is locked and waiting does not add a flag.
- `REMOVE_REFUSED_KEEP=` — `--force-disposable` stopped because the tree holds something that is not build output. Nothing was deleted.
- `REMOVE_FAILED=` — the tree is *clean* and a live shadow-cljs/Node process still holds a handle. That one is genuinely transient: note it and retry later.

**Sweep with `--force-disposable`, never a blanket `--force`.** It force-removes only when every untracked path is build or gate output (`logs/`, `bench-logs/`, `out/`, `.shadow-cljs/`, `*.log`), and refuses any tree holding a modified tracked file, a note or a draft. That guard is not hypothetical: when it was written, `band-ymi6j` held four `ladder-*.md` analysis files for an **open** bead and three other worktrees carried uncommitted source and doc edits — a blanket `--force` would have destroyed all of it silently, which is far worse than a worktree that will not reap. Keep `--force` for a specific tree you have looked at and decided about.

`--dry-run` reports the whole partition up front — `WOULD_REMOVE=` (clean), `WOULD_NEED_FORCE=` (all build output, sweepable) or `WOULD_REFUSE_KEEP=` (needs a human first) — so survey before you sweep, and hand the `[KEEP]` trees back to the operator rather than deciding for them.

Delete merged local + origin worker branches (NEVER delete a branch whose PR is not verifiably MERGED). Then `git remote prune origin` for stale tracking refs and clear any stray stashes. NEVER touch a worktree whose PR is still open. Report what was pruned, what was left locked, and which (if any) needs a process-kill or reboot to clear.
