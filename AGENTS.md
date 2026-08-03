# Agent Instructions

This project uses **bd** (beads) for issue tracking. Run `bd prime` for full workflow context.

## Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work atomically
bd close <id>         # Complete work
bd dolt push          # Push beads data to remote
```

## Worker Worktree Guard

Before making edits as a worker, verify you are in the dedicated worktree, not
the mayor checkout:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/assert-worker-worktree.ps1
```

Report the printed `WORKTREE_ROOT` in your final handoff. If the guard fails,
stop and switch to the correct worktree before editing.

## The `node_modules` Junction — Remove the Link, Never Its Target

A worker worktree cannot compile without a `node_modules`, so the convention
here is to point `<worktree>/implementation/node_modules` at the mayor
checkout's **real** one — a directory junction on Windows, a symlink
elsewhere. Anything that deletes *through* that link destroys shared state: a
bare `git worktree remove` walks the tree it is deleting, follows the reparse
point, and empties the mayor's real `node_modules` — exit 0, silently, breaking
every local build in the repo until `npm ci --prefix implementation` restores
it. That has happened twice. The measured hazard is the **Windows directory
junction**, including under Git Bash, where `rm -rf` on the junction deletes
through it as well; a plain POSIX symlink given as an `rm -rf` argument is
unlinked rather than traversed.

So if you create the link, unlink it as your last act before reporting done —
`cmd /c rmdir <path>` on Windows, `rm` **without** `-r` elsewhere. Both unlink
on every platform, which is why they are the instruction everywhere rather than
only where the difference bites. And never remove a worktree by hand: the
removal scripts disarm every link first, remove second, and fail loudly if the
mayor's `node_modules` lost anything.

```bash
sh scripts/remove-worker-worktree.sh <worktree-path>   # POSIX (primary)
# Windows: powershell -ExecutionPolicy Bypass -File scripts/remove-worker-worktree.ps1 <worktree-path>
```

## Gate Artefacts Go Where Git Ignores Them

Never pipe a gate through `tail`, `head` or `grep` — a pipeline's exit status is
its *last* command's, so a red runner reads green. Redirect to a file, capture
the runner's own exit code, and quote that number in the PR body. **Every**
artefact that produces — the log *and* the exit-code file — must land on an
ignored path; `*.log`, `*.exit` and `*-exit.txt` all are:

```bash
sh scripts/test-fast-pr.sh > gate-fastpr.log 2>&1; echo "$?" > gate-fastpr.exit
```

A single untracked leftover makes `git worktree remove` refuse the tree from
then on, and nine worktrees accumulated exactly that way before anyone worked
out why they would not reap.

## Non-Interactive Shell Commands

**ALWAYS use non-interactive flags** with file operations to avoid hanging on confirmation prompts.

Shell commands like `cp`, `mv`, and `rm` may be aliased to include `-i` (interactive) mode on some systems, causing the agent to hang indefinitely waiting for y/n input.

**Use these forms instead:**
```bash
# Force overwrite without prompting
cp -f source dest           # NOT: cp source dest
mv -f source dest           # NOT: mv source dest
rm -f file                  # NOT: rm file

# For recursive operations
rm -rf directory            # NOT: rm -r directory
cp -rf source dest          # NOT: cp -r source dest
```

**Other commands that may prompt:**
- `scp` - use `-o BatchMode=yes` for non-interactive
- `ssh` - use `-o BatchMode=yes` to fail instead of prompting
- `apt-get` - use `-y` flag
- `brew` - use `HOMEBREW_NO_AUTO_UPDATE=1` env var

## Docs Gates — What Covers `docs/design/**`

`mkdocs build --strict` does **not** cover `docs/design/**`. `mkdocs.yml`'s
`exclude_docs` block deliberately keeps `design/freehand/` and `design/hicasso/`
out of the site, so never nominate it as the gate for an edit confined to that
tree — it will exit 0 having verified nothing. `scripts/check_doc_slugs.py` does
cover it: markdown link targets and heading anchors, in the fast-PR spine and in
`docs.yml`. Nothing checks tables, rendering, or nav, so verify anchors and table
column counts by hand and say so in the PR body.

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:ca08a54f -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

## Session Completion

**When ending a work session**, you MUST complete ALL steps below. Work is NOT complete until `git push` succeeds.

**MANDATORY WORKFLOW:**

1. **File issues for remaining work** - Create issues for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **PUSH TO REMOTE** - This is MANDATORY:
   ```bash
   git pull --rebase
   bd dolt push
   git push
   git status  # MUST show "up to date with origin"
   ```
5. **Clean up** - Clear stashes, prune remote branches
6. **Verify** - All changes committed AND pushed
7. **Hand off** - Provide context for next session

**CRITICAL RULES:**
- Work is NOT complete until `git push` succeeds
- NEVER stop before pushing - that leaves work stranded locally
- NEVER say "ready to push when you are" - YOU must push
- If push fails, resolve and retry until it succeeds
<!-- END BEADS INTEGRATION -->
