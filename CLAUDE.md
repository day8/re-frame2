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

## Local working files (the `ai/` tree)

Per `docs/the-mayor-method.md`, the `ai/` directory at repo root holds all AI working artefacts. **The whole tree is local-only** (`/ai/` is in `.gitignore`); never `git add` anything under it.

- **`ai/map.md`** — Mike's personal dashboard listing open beads that need his decision. Categorised by urgency; carries Claude's read on each. Update whenever a decision bead is filed, resolved, or surfaced as pending.
- **`ai/findings/`** — exploratory work, audits, design drafts, research notes. Agents writing findings docs put them here.
- **`ai/specs/`** — playground for fine-tuning super-prompts. Disposable; not the same as the committed normative `/spec/` tree.
- **Timestamp format**: full datetime with timezone, not just date. e.g. `2026-05-09 13:30:57 AUSEST`. Use `date "+%Y-%m-%d %H:%M:%S %Z"` to fetch.

## Workflow

- **Always dispatch beads to a background agent when sensible.** Don't ask permission for clear-cut implementation work, mechanical fixes, or follow-on tasks where the direction is set. Keep the work flowing. Only pause for genuine decisions Mike hasn't made.
- **Worker worktree guard is mandatory before edits.** Background workers must run `powershell -ExecutionPolicy Bypass -File scripts/assert-worker-worktree.ps1` from their intended checkout before editing, and report the printed `WORKTREE_ROOT`. The guard refuses the mayor checkout (`C:\Users\miket\code\re-frame2`) and any root outside `C:\Users\miket\code\re-frame2-worktrees`. This mitigates observed harness path-resolution leaks; it is not a root-cause fix for the external edit/write bug.
- **Minimise merge conflicts when dispatching.** Hot-zone files (`spec/Conventions.md`, `spec/MIGRATION.md`, `spec/API.md`, `spec/Tool-Pair.md`, `spec/Spec-Schemas.md`, `spec/009-Instrumentation.md`, `spec/006-ReactiveSubstrate.md`, `spec/005-StateMachines.md`, `spec/002-Frames.md`, top-level `implementation/deps.edn`, `implementation/shadow-cljs.edn`, `.github/workflows/*`) are sequential, never parallel — two beads touching the same hot file = sequence them, second waits for the first's PR to merge. Isolated surfaces (single-artefact `implementation/<feature>/src/`, new-file additions, test-only dirs `implementation/<feature>/test/`, `examples/<substrate>/<example>/`) are safe to parallel.
- **Keep `ai/map.md` up to date.** Update on every signal: a bead is filed, dispatched, completes, or has its PR merged. Move closed beads to "Resolved this session." Refresh the timestamp using `date "+%Y-%m-%d %H:%M:%S %Z"`. The doc is local-only; never commit it. Mike reads this to track state in one glance — staleness is a navigation tax.
- **Pull `main` from `origin` immediately after every PR merge.** Run `git pull --ff-only` as the very next step after `gh pr merge ... --rebase --admin --delete-branch`. No exceptions, no batching multiple merges before pulling. Mike glances at his local working tree to track progress; staleness leaves him with a wrong picture and breaks subsequent dispatches that worktree off `origin/main`. Same rule applies whether merge happened seconds ago or while another agent was running.
- **Stash before pull when needed.** `.beads/issues.jsonl` may carry uncommitted local edits; stash before pulling and pop after if necessary. (`ai/map.md` is local-only and won't show up in `git status`.)

## Build & Test

The CLJS reference implementation builds and tests run from `implementation/`. shadow-cljs is the build tool; npm scripts in `implementation/package.json` are the canonical entry points.

```bash
# From repo root:
scripts/test-fast-pr.sh                # fast pre-checkin spine
scripts/test-jvm-implementation.sh     # all implementation JVM artefacts
scripts/test-jvm-tools.sh              # tool JVM artefacts
scripts/test-rigorous-local.sh         # expensive local/release-sized sweep

# From implementation/:
npm install                          # one-time, installs shadow-cljs + react
npm run test:cljs                    # node-runtime CLJS tests (fast, default gate)
npm run test:browser                 # browser tests via Playwright
npm run test:elision                 # production elision probe
npm run test:perf-bundle             # perf-budget bundle check
npm run test:bundle-isolation        # tools must not leak into production bundles
npm run test:reagent-slim:bundle-isolation # slim must not pull stock Reagent/react-dom/server
npm run test:examples                # examples test runner
npm run story:build                  # build the story artefact
```

Per-artefact tests run from each artefact directory via `clojure -M:test` (see e.g. `tools/story/deps.edn` `:test` alias). The canonical matrix and PR/nightly/release split lives in `TESTING.md`; workflow gates live in `.github/workflows/`.

Docs build from repo root with `mkdocs build --strict` (config in `mkdocs.yml`).

## Architecture Overview

**The spec is the artefact; the code is downstream.** The normative description of re-frame2 lives in [`spec/`](spec/) (~22K lines across 35+ documents); [`implementation/`](implementation/) is a CLJS reference that validates the spec end-to-end. See the repo-root [`README.md`](README.md) for the marketing-voice introduction and the project-layout map, and [`spec/README.md`](spec/README.md) for the spec index.

Top-level layout:

- `spec/` — the specification (primary artefact; AI-targeted)
- `implementation/` — CLJS reference: `core/` + per-substrate `adapters/` (Reagent, UIx, Helix) + per-feature artefacts (machines, schemas, …). The top-level `implementation/shadow-cljs.edn` + `implementation/deps.edn` coordinate the cross-artefact classpath.
- `tools/` — dev/inspection tools that consume the Spec 009 instrumentation API and Tool-Pair contract (`story/`, `story-mcp/`, `pair2-mcp/`, `causa/`, `template/`). Bundle-isolated from production builds; nothing in `implementation/` may `:require` from `tools/`.
- `examples/` — worked examples per substrate.
- `docs/` — human-facing guide (`docs/guide/`) and operational docs.
- `skills/` — Claude Code skills for re-frame2 workflows.

## Conventions & Patterns

Normative conventions are catalogued in [`spec/Conventions.md`](spec/Conventions.md) — reserved namespaces (the `:rf/*` single-root scheme), reserved fx-ids, reserved app-db keys, the feature-modularity id-prefix convention, and packaging conventions. [`spec/Principles.md`](spec/Principles.md) carries the nine AI-first practical principles. [`spec/Ownership.md`](spec/Ownership.md) maps every contract surface to its owning spec — the "where does X live?" reference.

Hot-zone files (sequential, never parallel — see the Workflow section above for the list) and isolated surfaces (safe to parallel) are documented in the dispatch rules above; new beads should respect that split to minimise merge conflicts.
