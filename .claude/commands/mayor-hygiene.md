---
description: Mayor Loop — worktree hygiene (≈60m cadence). Prune merged worktrees/branches; never touch open-PR worktrees.
---
MAYOR LOOP (worktree hygiene). Run `git worktree list`, `git branch --list 'worker/*'`, `git branch -r --list 'origin/worker/*'`. For each worker worktree: verify its PR via `gh pr view <#> --json state` — only if state==MERGED, prune it. **Never remove a worktree with a bare `git worktree remove`.** Worker worktrees junction `implementation/node_modules` at the mayor checkout's REAL `node_modules`, and `git worktree remove` follows that reparse point and deletes THROUGH it — the mayor's real `node_modules` is emptied, `git` still exits 0, and every gate run from the mayor checkout then fails looking like something else entirely. Recovery is `npm ci --prefix implementation` from the mayor checkout. This procedure used to spell the safe sequence out as prose and the class recurred anyway, so the sequence now lives in a script that does it for you:

```sh
sh scripts/remove-worker-worktree.sh <worktree-path> [<worktree-path>...]
# Windows: powershell -ExecutionPolicy Bypass -File scripts/remove-worker-worktree.ps1 <path>
```

It snapshots every real `node_modules` in the mayor checkout, unlinks each link under the worktree (the LINK only — never a recursive delete), verifies each is gone, THEN calls `git worktree remove`, then re-checks the snapshot and **fails loudly if any count dropped**, naming the recovery command. Add `--force` for a dirty worktree, `--dry-run` to see what it would do, `--self-test` to prove the disarm against a throwaway junction before you trust it. Read its output: `DISARMED=` lines say a junction was found and neutralised, `NO_LINKS=` says the worktree had none (a spec-only or fresh worktree legitimately does not), and `CANARY_BEFORE=`/`CANARY_AFTER=` must match. On Windows a shadow-cljs/Node JVM may still hold a file lock and `REMOVE_FAILED=` comes back — that is harmless, the links are already disarmed, so leave it locked, note it, and retry later.

Delete merged local + origin worker branches (NEVER delete a branch whose PR is not verifiably MERGED). Then `git remote prune origin` for stale tracking refs and clear any stray stashes. NEVER touch a worktree whose PR is still open. Report what was pruned, what was left locked, and which (if any) needs a process-kill or reboot to clear.
