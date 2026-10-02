# Agent Instructions

This project uses **bd** (beads) for issue tracking. Run `bd prime` for full workflow context.

These are the rules every agent follows, whichever harness it runs in. Claude Code reads them through `CLAUDE.md`, which imports this file and adds the project stance and the coordinator's rules. **Before any tracker write or any git operation touching `.beads`, read `CLAUDE.md` § Beads durability** — a wrong move there silently reverts other agents' tracker state.

## Worker Worktree Guard

Before making edits as a worker, run the guard from your intended checkout and report the printed `WORKTREE_ROOT` in your final handoff:

```bash
sh scripts/assert-worker-worktree.sh                                          # POSIX (primary)
powershell -ExecutionPolicy Bypass -File scripts/assert-worker-worktree.ps1   # Windows
```

It refuses the mayor checkout (the repository's primary worktree) and any root outside its `re-frame2-worktrees` sibling; both are overridable via `RF2_MAYOR_ROOT` / `RF2_WORKTREE_PARENT`. If the guard fails, stop and switch to the correct worktree before editing.

## Worktrees and the `node_modules` Junction

Prefer `npm ci --prefix implementation` inside your worktree: nothing shared means nothing to delete through. If you instead point `<worktree>/implementation/node_modules` at the mayor checkout's real one — a directory junction on Windows — anything that deletes *through* that link empties the mayor's `node_modules` silently (exit 0), breaking every local build until `npm ci --prefix implementation` restores it. A bare `git worktree remove` does exactly that, and so does `rm -rf` on a Windows junction.

- **If you create the link, unlink it as your last act before reporting done.** From PowerShell, `[System.IO.Directory]::Delete('<path>', $false)` (create one with `New-Item -ItemType Junction`); from Git Bash, a plain `rm <path>` **without** `-r`. **Never `cmd /c` from Git Bash** — it drops into an interactive prompt, runs nothing, and exits 0.
- **Verify the effect, not the exit code**: the link is gone, and the mayor's `node_modules` still counts what it did — with `ls -A` or `find <dir> -mindepth 1 -maxdepth 1`, since bare `ls` hides `.bin`.
- **Remove a worktree only through the script, and hand it the ABSOLUTE path** — a bare name resolves against your current directory and is refused:

```bash
sh scripts/remove-worker-worktree.sh <absolute-worktree-path>
# Windows: powershell -ExecutionPolicy Bypass -File scripts/remove-worker-worktree.ps1 <absolute-worktree-path>
```

It unlinks first, removes second, and fails loudly if the mayor's `node_modules` lost anything. A single untracked leftover in the tree makes the removal refuse it, which is one reason gate artefacts go on ignored paths (below).

## The shadow-cljs Build Lane Is Machine-Wide Exclusive

Two shadow-cljs runs anywhere on this machine at once **wedge silently** — no error, no exit file, no output, just two builds that never finish — so each worker sees what looks like a hang on its own gate. **Nearly every script in `implementation/package.json` enters shadow-cljs** (all but `test:fresco-invariants`, `test:fresco-lint`, `test:scripts` and `test:ssr-node` when last measured), many of them through `.cjs` wrappers that resolve `shadow-cljs/cli/runner.js`, or through a JVM `shadow.cljs.devtools.cli` call — so ask what a command ENTERS, never whether its name or launcher says shadow-cljs. `npm run test:cljs` takes the lane exactly as `npm run test:browser` does.

- **Run in the lane only when the coordinator has granted it.** An announcement notifies; it does not acquire.
- **Told to yield, kill any run already started** — a half-overlapped run is as wedged as a full one. Identify your processes by ANCESTRY: note the PID you launched and walk its descendants through `ParentProcessId`, kill that tree children-first from PowerShell, then read every remaining `node.exe`/`java.exe` row with its parent chain. A worktree-path filter over command lines sees only part of one build. Delete the partial log so nobody reads it as a verdict.
- **If you overlapped, even briefly, say so** — the holder then re-runs rather than debugging a result, and re-takes any baseline captured in the window.

## Running Gates

- **Run a gate by ABSOLUTE path.** The shell gates (every `scripts/test-*.sh`) derive their root from `${BASH_SOURCE[0]}`, and a backgrounded `cd <worktree> && sh scripts/…` does not reliably keep its `cd` — one such run graded another live worker's checkout and reported that verdict as its own. Those gates print `gate root: <path>` first; check it against your worktree before believing any colour.
- **Gates that print no banner** (the `scripts/check_*.py` gates and their `check-*.sh` siblings) pin their root to the script's own location, so a relative script path under a leaked cwd runs a *sibling's copy* against the sibling's tree. Give the absolute path, and prove the run read your tree by planting a fault in a line you are already editing: it must go red. A green sabotage run is a reason to stop, not a pass.
- **Never pipe a gate through `tail`, `head` or `grep`** — a pipeline's exit status is its last command's, so a red runner reads green. Redirect to a file, capture the runner's own exit code, and quote that number in the PR body.
- **Name every artefact — the log and the exit-code file — with your worktree AND the attempt number, on an ignored path** (`*.log`, `*.exit` and `*-exit.txt` all are):

```bash
sh <WORKTREE_ROOT>/scripts/test-fast-pr.sh > gate-fastpr-<worktree>-1.log 2>&1; echo "$?" > gate-fastpr-<worktree>-1.exit
```

  Bump the number on every re-run (`-2`, `-3`) and never write one twice. The worktree name stops a peer sharing the scratchpad from overwriting your exit code with theirs; the attempt number stops a surviving child of a killed run from splicing into your new log (the tell is a NUL hole, or two summary lines). A foreground call dies at ten minutes and the spine needs about twenty-five, so kill-and-restart is routine. Confirm a scratch file is your own before believing it.

## Build & Test

The CLJS reference implementation builds and tests run from `implementation/`. shadow-cljs is the build tool; npm scripts in `implementation/package.json` are the canonical entry points.

```bash
# From repo root:
scripts/test-fast-pr.sh                # fast pre-checkin spine
scripts/test-jvm-implementation.sh     # all implementation JVM artefacts
scripts/test-jvm-tools.sh              # tool JVM artefacts
scripts/test-rigorous-local.sh         # expensive local/release-sized sweep
```

Per-artefact tests run from each artefact directory via `clojure -M:test` (see e.g. `tools/story/deps.edn` `:test` alias). The canonical matrix and PR/nightly/release split lives in `TESTING.md`; workflow gates live in `.github/workflows/`. Docs build from repo root with `mkdocs build --strict` (config in `mkdocs.yml`) — or `python -m mkdocs build --strict` where the console script is not on PATH, which shows up as exit 127, a missing tool rather than broken docs.

**Examples are test-free**: no `*.spec.cjs` may live under `examples/`. Browser smoke coverage is one adapter-level smoke per shipped adapter — mount + dispatch + assert — at two filenames under two runners: `implementation/adapters/{reagent,uix}/testbed/spec.cjs` via `npm run test:adapter-smokes`, and `implementation/adapters/reagent-slim/testbed/smoke.cjs` via `npm run test:reagent-slim:smoke` (`adapters/test-react/` is local-test-only and deliberately unsmoked). Framework testbeds (`tools/xray/testbeds/`, top-level `testbeds/`) are covered by a derived compile sweep that names no build — ask it with `node implementation/scripts/check-examples-compile.cjs --list` rather than grepping for a build id — plus per-PR unit tests and the Xray feature-matrix gate; their old `spec.cjs` files were migrated into unit tests deliberately, so don't restore them. Real-regression coverage lives in substrate contract tests (`npm run test:cljs`), the Xray feature-matrix gate (`npm run test:xray-feature-gate`), bundle-isolation, the perf-bundle gate, and mcp-conformance.

## Which Gate Covers Which Edit

A green is evidence only if the gate could have failed on your change, and **a gate cannot fail on a file containing none of what it inspects** — a link gate on a page with no links, an edit confined to a fenced block, a lane that never compiles the file. Check that before nominating one; where no gate covers the edit, verify by hand and say so in the PR body.

| Your edit | Gate | What its green does not cover |
|---|---|---|
| `docs/**` outside `mkdocs.yml`'s `exclude_docs`, plus `spec/**` and `migration/**` | `mkdocs build --strict` (links resolve in the built site) **and** `scripts/check_doc_slugs.py` (link targets and heading anchors) | They are complementary: the strict build never validates anchors (MkDocs grades them INFO). `spec/` and `migration/` are staged into the build by `mkdocs_hooks.py` on every invocation, so a bare local strict run covers them. A page in a `spec/` subdirectory cannot reach `docs/` by a relative link, and only the strict build catches that. |
| A relative link from a docs-gate page (`docs/`, `spec/`, `migration/`, `skills/`, `tools/*/spec/`) to anything outside those trees — a source file, an example, a directory | `check_doc_slugs.py`, which requires every relative target to exist, whatever its kind | `mkdocs build --strict` never sees these: `mkdocs_hooks.py` rewrites them to GitHub URLs before the build validates links. Existence only — a `#L12` line fragment on a source file is not graded. |
| `docs/design/**`, and anything else `exclude_docs` names | `check_doc_slugs.py`; plus `scripts/check_provenance_pins.py` for `docs/design/fresco/` | The strict build sees none of it — read `exclude_docs` itself, not a list in prose. Provenance-pins sees only files git knows about (`git add` is enough); `0 pages inspected` means it checked nothing. |
| Repo-root markdown, and markdown beside source (`implementation/**`, `examples/**`, `tools/**` outside their `spec/` trees) | `scripts/check_readme_links.py --ci` | `check_doc_slugs.py` exits 0 on these. The split is by ROOT, not filename — a `README.md` under `docs/`, `spec/`, `migration/` or `skills/` is the docs gate's. CI's `verify-readme-links` job runs both, so nominate by path, not by job name. |
| A GitHub `blob/` or `tree/` URL into this repo on `main`, or a `day8.github.io/re-frame2/` site URL | `check_doc_slugs.py` grades both; `check_readme_links.py` grades site URLs only | A GitHub cite in root markdown or a README beside source is checked by hand. |
| A `findings/` directory under the docs-gate roots — `spec/findings/`, `tools/*/spec/findings/` | none | `check_doc_slugs.py` skips every directory named `findings`, and `check_readme_links.py` leaves those roots to it. Check links by hand and say so. |
| A link inside a fenced code block | none — both gates strip fences first | Except in `docs/design/fresco/` and `docs/the-mayor-method/`, where a doc link inside a fence is itself reported. |
| Code inside a `#?(:cljs …)` branch of a `.cljc` file | `npm run test:cljs`, or the shadow build that includes the namespace | JVM lanes READ that branch but never compile it: a read error goes red there, while a malformed `defn`, a bad arity or a dangling reference stays green. So a syntax-error plant is not a valid positive control, and clj-kondo is no substitute. Say which branch you changed; if `:cljs` and you rely on CI, say so. |
| An adapter's published late-bind hook set | `npm run test:cljs` **and** `scripts/test-jvm-implementation.sh` | The CLJS publication pins are `-cljs-test` namespaces, which only the node lane loads (the browser lane loads `-dom-cljs-test`); the JVM `late_bind_drift_test.clj` pins the directory against the publication sites. |

Nothing validates markdown prose, tables, rendering or nav — verify anchors and table column counts by hand and say so in the PR body. The link gates also run inside the fast-PR spine's documentation tier, which is classifier-gated, so cite CI for them rather than a local spine pass.

## Non-Interactive Shell Commands

**ALWAYS use non-interactive flags** — `cp -f`, `mv -f`, `rm -f`, `rm -rf`, `cp -rf`; `-y` for package managers; `-o BatchMode=yes` for `ssh`/`scp`. Commands aliased to `-i` hang an agent indefinitely on a y/n prompt.

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
   git fetch origin main && git rebase origin/main
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
