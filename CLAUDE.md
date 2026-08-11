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

## Beads durability

`.beads/issues.jsonl` is a full-database export, not an ordinary source file. `bd` rewrites it in **every** checkout, so each worker worktree carries a snapshot of the tracker as it stood when that worktree was created. Committing that snapshot **time-travels the tracker**: beads closed since it reopen, beads filed since it vanish. PR #6677 landed exactly that — 135 insertions / 136 deletions of pure collateral.

- **The tracker database is the mayor's to commit.** Worker branches carry code, spec, docs and tests — never `.beads/issues.jsonl`, `.beads/metadata.json`, or any other database-derived path. The human-authored config (`.beads/README.md`, `.beads/config.yaml`, `.beads/.gitignore`, `.beads/hooks/**`) is fair game from anywhere. A pre-commit hook enforces this in linked worktrees **once installed** — run `sh scripts/install-git-hooks.sh` (or `scripts/install-git-hooks.ps1`) once per clone; worktrees share the primary checkout's hooks directory, so one run arms them all, and `--check` reports whether a checkout is currently guarded. `scripts/check-beads-pr-boundary.sh` is the CI arm. See `scripts/git-hooks/README.md`.
- **Never resolve a `.beads` conflict with `--theirs` or `--ours`.** Both sides are whole-database exports, so picking one wholesale discards every bead the other side recorded. Resolve then **regenerate**: take the incoming file (`git checkout --theirs .beads/issues.jsonl`), re-import it, and let `bd` re-export from the merged database.
- **Checkpoint first, then clear `.beads`, then pull — and never stash.** An uncommitted `.beads/issues.jsonl` makes `git pull` abort ("local changes would be overwritten"), silently freezing HEAD at a stale base, so the file does have to be cleared. But clearing it *after* a `bd close` or `bd create` reverts the export to its pre-close state, and the next checkpoint writes that revert back over the database — the close evaporates (rf2-51uz1: rf2-5e8zv was reopened exactly this way, and commit `e80786e007` records three more re-closed by hand). The order is therefore fixed, and a checkout of `.beads` must never follow a tracker mutation within a tick:

  ```bash
  sh scripts/beads-checkpoint.sh   # export from the database, verify, commit
  git checkout HEAD -- .beads      # now safe: HEAD carries the tracker
  git pull --rebase
  ```

  `sh scripts/beads-checkpoint.sh --pre-pull` (or `-PrePull` on the `.ps1`) answers "would clearing `.beads` throw tracker state away?" — exit 0 means the checkout is safe. **Do not `git stash`**: stashes are repo-global and contaminate every other worktree in flight.

  **This sequence answers one abort message and only that one.** A pull that aborts with `Not possible to fast-forward` is the other cause — the checkpoint you just made has diverged from a racing push — and clearing an already-clean `.beads` is inert against it. Branch on the message; both branches are set out in `.claude/commands/mayor-merge.md`.
- **A checkpoint re-exports; it never trusts the working file.** `scripts/beads-checkpoint.sh` (POSIX; `.ps1` sibling for Windows) runs `bd export`, refuses an empty or badly shrunken result, and commits only `.beads/issues.jsonl`. That ordering is what makes a reverted file unreachable: the export is regenerated from Dolt before anything reads it. A hand-rolled `git add .beads/issues.jsonl && git commit` cannot make that guarantee — it commits whatever the last checkout left behind.
- **The same floor is in the pre-commit hook, so a plain `git add` cannot route around it (rf2-or8te).** The checkpoint's guard only protects commits that go through the checkpoint, and twice an empty export reached main by a path that did not: `7aea52459` (2026-06-10, 7172 rows) and `4d8042d80d` (2026-07-26, 2573 rows), both plain `git add` from the mayor checkout, both leaving `git status` clean afterwards. The hook now refuses any staged `.beads/issues.jsonl` below 90% of HEAD, from every worktree. **An empty export is a REGENERATION event, not a data-loss one** — the Dolt database is the source of truth, `sh scripts/beads-checkpoint.sh` rebuilds it, and restoring an older export from git history would time-travel the tracker instead. For a genuine mass delete the message names the escape: inspect with `bd status`, then `git commit --no-verify`.
- **Do not set `git update-index --skip-worktree` on it.** It hides the local edit from `git status` but still blocks `git pull` — the same frozen HEAD, now with nothing on screen to explain it. Stage explicit paths instead of `git add -A`.

## Git Conventions

- **No AI attribution in commits or PRs.** Do not add `Co-Authored-By: Claude ...`, `🤖 Generated with [Claude Code]`, or any similar trailer to commit messages or PR descriptions. Commit and PR text should read as the user's own work.

## Local working files (the `ai/` tree)

Per `docs/the-mayor-method/`, the `ai/` directory at repo root holds all AI working artefacts. **The tree is local-only by default** (`/ai/` is in `.gitignore`) — don't `git add` under it unless the file is already tracked. A few trees are deliberately tracked right now as a temporary exception; `git ls-files ai/` is the roster, never a list in this file. **The end state is that nothing under `/ai/` is tracked.** Treat today's exceptions as transitional — never as licence to force-add more.

- **Do not maintain `ai/decisions.md`.** The decisions index file is retired (Mike, 2026-07-17): don't create or refresh it. Surface holds awaiting Mike (review gates, operator-run actions, held beads) in chat and on the beads themselves. Per-decision dossiers, when Mike asks for them, go in `ai/decisions/` as one file per decision.
- **`ai/findings/`** — exploratory work, audits, design drafts, research notes. Agents writing findings docs put them here.
- **`ai/specs/`** — playground for fine-tuning super-prompts. Disposable; not the same as the committed normative `/spec/` tree.
- **Timestamp format**: full datetime with timezone, not just date. e.g. `2026-05-09 13:30:57 AUSEST`. Use `date "+%Y-%m-%d %H:%M:%S %Z"` to fetch.

## Workflow

- **Always dispatch beads to a background agent when sensible.** Don't ask permission for clear-cut implementation work, mechanical fixes, or follow-on tasks where the direction is set. Keep the work flowing. Only pause for genuine decisions Mike hasn't made.
- **Worker worktree guard is mandatory before edits.** Background workers must run the guard from their intended checkout before editing, and report the printed `WORKTREE_ROOT`. Use `sh scripts/assert-worker-worktree.sh` (POSIX primary) or `powershell -ExecutionPolicy Bypass -File scripts/assert-worker-worktree.ps1` (Windows). The guard derives the mayor checkout as the repository's primary worktree (`git worktree list`) and the worktree parent as its `re-frame2-worktrees` sibling — no hardcoded paths; both overridable via `RF2_MAYOR_ROOT` / `RF2_WORKTREE_PARENT`. It refuses the mayor checkout and any root outside the worktree parent. This mitigates observed harness path-resolution leaks; it is not a root-cause fix for the external edit/write bug.
- **Remove a worker worktree only via `scripts/remove-worker-worktree.sh`** (POSIX primary; `powershell -ExecutionPolicy Bypass -File scripts/remove-worker-worktree.ps1` on Windows). A worker worktree junctions `implementation/node_modules` at the mayor checkout's real one, and a bare `git worktree remove` follows that junction and empties the mayor's real `node_modules` — exit 0, silently, breaking every local build until `npm ci --prefix implementation` restores it. It has happened twice. The script unlinks the link first, removes the worktree second, then re-checks the mayor's `node_modules` counts and fails loudly if any dropped. **A worker that creates the junction removes it as its last act before reporting done**, so hygiene never meets one.
- **Minimise merge conflicts when dispatching.** Hot-zone files (`spec/Conventions.md`, `migration/from-re-frame-v1/README.md`, `spec/API.md`, `spec/Tool-Pair.md`, `spec/Spec-Schemas.md`, `spec/009-Instrumentation.md`, `spec/006-ReactiveSubstrate.md`, `spec/005-StateMachines.md`, `spec/004-Views.md`, `spec/002-Frames.md`, top-level `implementation/deps.edn`, `implementation/shadow-cljs.edn`, `.github/workflows/*`) are sequential, never parallel — two beads touching the same hot file = sequence them, second waits for the first's PR to merge. Isolated surfaces (single-artefact `implementation/<feature>/src/`, new-file additions, test-only dirs `implementation/<feature>/test/`, `examples/<substrate>/<example>/`) are safe to parallel.
- **Do not maintain `ai/dashboard.md`.** The dashboard is retired (Mike, 2026-06-20): don't create or refresh it. (`ai/decisions.md` is likewise retired — see the Local working files section above.)
- **Pull `main` from `origin` immediately after every PR merge.** Run `git pull --ff-only origin main` as the very next step after `gh pr merge --rebase` — name the remote and branch, because the bare form silently no-ops when the asynchronous post-merge audit push races it. No exceptions, no batching multiple merges before pulling. **When that pull ABORTS, the message names which of two causes it is, and their remedies are not interchangeable**: `local changes would be overwritten` is an uncommitted `.beads/issues.jsonl` (checkpoint, `git checkout HEAD -- .beads`, pull — see the bullet below), while `Not possible to fast-forward` is your own committed checkpoint diverging from that same racing push, for which the checkout is inert and naming the remote no help — `git pull --rebase origin main`, then verify. The full drill and its verification list live in `.claude/commands/mayor-merge.md`; don't restate them here. Then verify the tree rather than the line `git pull` printed: `git rev-parse HEAD` must equal `git rev-parse origin/main` — movement is the wrong test, and one mismatch is not yet evidence either, because that same audit push can land between the two reads, so read both refs again before believing it. Mike glances at his local working tree to track progress; staleness leaves him with a wrong picture and breaks subsequent dispatches that worktree off `origin/main`. Same rule applies whether merge happened seconds ago or while another agent was running.
- **Checkpoint, then clear `.beads`, then pull — never stash.** `.beads/issues.jsonl` carries uncommitted tracker state that aborts the pull with `local changes would be overwritten`, so it must be cleared; but clearing it after a `bd close` reverts that close, and the next checkpoint writes the revert back. (That message only — a `Not possible to fast-forward` abort is the other cause named in the bullet above, and this remedy does nothing for it.) Run `sh scripts/beads-checkpoint.sh` (it re-exports from the Dolt database rather than trusting the working file), *then* `git checkout HEAD -- .beads`, *then* pull. Stashes are repo-global and contaminate every worktree in flight — see [Beads durability](#beads-durability) for the full sequence and the `--pre-pull` pre-flight. (The `ai/` tree is local-only and won't show up in `git status`.)

## Build & Test

The CLJS reference implementation builds and tests run from `implementation/`. shadow-cljs is the build tool; npm scripts in `implementation/package.json` are the canonical entry points.

```bash
# From repo root:
scripts/test-fast-pr.sh                # fast pre-checkin spine
scripts/test-jvm-implementation.sh     # all implementation JVM artefacts
scripts/test-jvm-tools.sh              # tool JVM artefacts
scripts/test-rigorous-local.sh         # expensive local/release-sized sweep
```

Per-artefact tests run from each artefact directory via `clojure -M:test` (see e.g. `tools/story/deps.edn` `:test` alias). The canonical matrix and PR/nightly/release split lives in `TESTING.md`; workflow gates live in `.github/workflows/`.

**Examples are test-free (locked 2026-05-19, rf2-8cevm).** No `*.spec.cjs` may live under `examples/`. Browser smoke coverage is one adapter-level smoke per shipped adapter at `implementation/adapters/<name>/testbed/spec.cjs` plus the re-frame.ui smoke at `implementation/ui/testbed/spec.cjs` (rf2-nojiwy) — each mount + dispatch + assert. Real-regression coverage lives in substrate contract tests (`npm run test:cljs`), the Xray feature-matrix gate (`npm run test:xray-feature-gate`), bundle-isolation, the perf-bundle gate, and mcp-conformance. Framework testbeds (`tools/xray/testbeds/`, top-level `testbeds/`) carry their own non-adapter spec.cjs for cross-cutting surfaces (parallel-frames isolation, perf-API live counterpart, SSR, etc.).

Docs build from repo root with `mkdocs build --strict` (config in `mkdocs.yml`).

**`mkdocs build --strict` does not cover `docs/design/**`** — `mkdocs.yml`'s `exclude_docs` block deliberately keeps `design/freehand/` and `design/hicasso/` out of the site (they are working design records, not user-facing documentation), so never nominate it as the gate for an edit confined to that tree. `scripts/check_doc_slugs.py` *does* validate the tree's markdown link targets and heading anchors (it runs in the fast-PR spine and in `docs.yml`), and `scripts/check_provenance_pins.py` validates changed pages under `docs/design/hicasso/` as its own `docs.yml` job; nothing validates its tables, rendering or nav, so **the worker verifies anchors and table column counts by hand and says so in the PR body**.

## Architecture Overview

**The spec is the artefact; the code is downstream.** The normative description of re-frame2 lives in [`spec/`](spec/) (~22K lines across 35+ documents); [`implementation/`](implementation/) is a CLJS reference that validates the spec end-to-end. See the repo-root [`README.md`](README.md) for the marketing-voice introduction and the project-layout map, and [`spec/README.md`](spec/README.md) for the spec index.

Status that the directory tree does not tell you: under `implementation/`, `ui/` (re-frame.ui) is the **EXPERIMENTAL** compiled-view substrate, offered alongside the `adapters/` (Reagent, reagent-slim, UIx), which are **first-class and actively supported**.

`tools/` holds dev/inspection tools consuming the Spec 009 instrumentation API and Tool-Pair contract. They are bundle-isolated from production builds: **nothing in `implementation/` may `:require` from `tools/`.**

## Conventions & Patterns

Normative conventions are catalogued in [`spec/Conventions.md`](spec/Conventions.md) — reserved namespaces (the `:rf/*` single-root scheme), reserved fx-ids, reserved app-db keys, the feature-modularity id-prefix convention, and packaging conventions. [`spec/Principles.md`](spec/Principles.md) carries the nine AI-first practical principles. [`spec/Ownership.md`](spec/Ownership.md) maps every contract surface to its owning spec — the "where does X live?" reference.
