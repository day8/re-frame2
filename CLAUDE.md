# Project Instructions for AI Agents

This file provides instructions and context for AI coding agents working on this project.

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


## Git Conventions

- **No AI attribution in commits or PRs.** Do not add `Co-Authored-By: Claude ...`, `🤖 Generated with [Claude Code]`, or any similar trailer to commit messages or PR descriptions. Commit and PR text should read as the user's own work.

## Local working files

- **`decision-beads.md` at repo root** — Mike's personal dashboard listing open beads that need his decision. Categorised by urgency; carries Claude's read on each. Update whenever a decision bead is filed, resolved, or surfaced as pending. Never commit; it lives in `.git/info/exclude` (local-only ignore — not `.gitignore`, since the rule is purely Mike's).
- **Timestamp format**: full datetime with timezone, not just date. e.g. `2026-05-09 13:30:57 AUSEST`. Use `date "+%Y-%m-%d %H:%M:%S %Z"` to fetch.

## Workflow

- **Always dispatch beads to a background agent when sensible.** Don't ask permission for clear-cut implementation work, mechanical fixes, or follow-on tasks where the direction is set. Keep the work flowing. Only pause for genuine decisions Mike hasn't made.
- **Keep local `main` in sync with `origin/main`.** After each PR merge, run `git pull --ff-only` so Mike sees the latest code in his checkout. He glances at the working tree to track progress.
- **Stash before pull when needed.** `decision-beads.md` and `.beads/issues.jsonl` may carry uncommitted local edits; stash before pulling and pop after if necessary.

## Build & Test

_Add your build and test commands here_

```bash
# Example:
# npm install
# npm test
```

## Architecture Overview

_Add a brief overview of your project architecture_

## Conventions & Patterns

_Add your project-specific conventions here_
