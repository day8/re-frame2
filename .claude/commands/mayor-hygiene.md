---
description: Mayor Loop — worktree hygiene (≈60m cadence). Prune merged branches; reap a worktree only once its agent has reported.
---
MAYOR LOOP (worktree hygiene).

**`docs/the-mayor-method/loops.md` §5 is the body of this loop** — the six discredited reap
proxies, the removal modes, the husk rule, and why patch-equivalence is the branch test. §4's
*stranded sweep* owns every liveness discriminator, and `.claude/commands/mayor-posture.md`
carries the operational block for it. Neither is restated here. **This file pins the concrete
commands.**

```bash
git worktree list
git branch --list 'worker/*'
git branch -r --list 'origin/worker/*'
```

## Reaping

**Only the agent's own completion report authorises a reap**, and it must SAY the work is
finished — *can you quote the sentence?* `SendMessage`'s response shape is the other read
that has never lied: *"queued for delivery"* = alive, so the worktree stays;
*"had no active task; resumed from transcript"* = idle. A gate the worker backgrounded is
still that worker running, and since the harness hard-kills a foreground command at ten
minutes while the spine needs about twenty-five, long gates are ALWAYS backgrounded here.
Do not add a seventh proxy; no script can check this and none tries.

**Record `<worktree-path>`, `<agentId>`, `<timestamp>` tab-separated in
`ai/worktree-agent-ids.tsv` as each agent is dispatched** — `/ai/` is gitignored, so it costs
no repo churn. Across a `/clear`, `SendMessage` does NOT resolve a pre-clear agent (measured:
eight for eight *"No transcript found"*), but the transcript the id names is intact at
`~/.claude/projects/<project-slug>/<session-uuid>/subagents/agent-<agentId>.jsonl` — read its
LAST assistant message and apply the unchanged test. **Do not read `tasks/<agentId>.output`**:
it is empty for finished current-session agents too, so its emptiness says nothing, and one
was a hardlink to the real transcript, which is what made the wrong reading self-consistent.

**A reap is machine work.** `scripts/remove-worker-worktree.sh` snapshots every real
`node_modules` and walks each tree unlinking junctions — thousands of file operations per
tree — so do not run one while a CLOCK measurement window is in flight. Deferring costs one
tick. ALLOCATION windows are exempt.

## Removing

**Never a bare `git worktree remove`.** Worker worktrees junction `implementation/node_modules`
at the mayor checkout's REAL one; `git worktree remove` follows the reparse point, deletes
THROUGH it, exits 0, and every later gate fails looking like something else. Recovery is
`npm ci --prefix implementation` from the mayor checkout.

```sh
sh scripts/remove-worker-worktree.sh <worktree-path> [<worktree-path>...]
# Windows: powershell -ExecutionPolicy Bypass -File scripts/remove-worker-worktree.ps1 <path>
```

Flags: `--dry-run` partitions the whole set up front (`WOULD_REMOVE=` clean,
`WOULD_NEED_FORCE=` all build output, `WOULD_REFUSE_KEEP=` needs a human) — survey before you
sweep and hand the `[KEEP]` trees back to the operator. **Sweep with `--force-disposable`,
never a blanket `--force`**; keep `--force` for one tree you have looked at. `--husk` for a
path git has already let go of, passed ALONE and never sprayed across a sweep list.
`--self-test` proves the disarm, the husk detector and the refusals against fixtures.

Read the output: `DISARMED=` a junction was found and neutralised; `NO_LINKS=` the tree had
none (legitimate for a spec-only or fresh worktree); `CANARY_BEFORE=`/`CANARY_AFTER=` must
MATCH, each being `<immediate-entries>/<recursive-files>/<sentinels>`, e.g. `103/2933/2`.

Refusal modes are not interchangeable:

- `REMOVE_REFUSED_DIRTY=` — modified or untracked files, each printed `[build output]` or
  `[KEEP]`. **Waiting never clears this.**
- `REMOVE_REFUSED_KEEP=` — `--force-disposable` stopped on something that is not build output.
  Nothing was deleted.
- `REMOVE_FAILED=` — clean and intact, a live shadow-cljs/Node process holds a handle.
  Genuinely transient: note it and retry.
- `REMOVE_PARTIAL=` — a husk. `git worktree list` no longer shows it, `git worktree prune` has
  nothing to clear, and `git status` inside it fails and prints nothing, which is why a
  clean/dirty check reads a husk as clean. The junction is already disarmed, so `rm -rf <path>`
  once the lock clears is all that remains. **A partial removal kills a running gate exactly as
  a complete one does.**
- `REFUSED_UNREGISTERED=` / `REFUSED_NOT_A_HUSK=` / `REFUSED_HUSK_IS_REGISTERED=` — the script
  declined to identify the path. Nothing touched. `HUSK_PROVENANCE=` prints what it did
  establish.

Exit 1 means nothing was destroyed. Exit 3 is a claim about provenance.

## Branches

Delete merged local and origin worker branches — never one whose PR is not verifiably MERGED.
Establish that by exact `headRefName` equality, never `--search "head:<branch>"`, which
substring-matches and ranked a sibling first:

```sh
gh pr list --state merged --limit 60 --json number,state,headRefName \
  --jq '.[]|select(.headRefName=="worker/<branch>")|"\(.number) \(.state)"'
```

**"Is this branch's work already on main?" is the question cleanup turns on, and this
repository merges by REBASE**, so ancestry and the ahead count both read wrong. The test is
`git cherry origin/main worker/<name>` — `-` is upstream, `+` is genuinely new.

Then `git remote prune origin` and clear any stray stashes. **Merge state is the test for a
branch, never for a worktree.** Report what was pruned, what was left locked, and which (if
any) needs a process-kill or reboot to clear.
