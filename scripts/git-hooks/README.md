# scripts/git-hooks/

Source of truth for the re-frame2-managed git hooks plus their library
helpers and tests. The installer (`scripts/install-git-hooks.sh` /
`.ps1`) stages the marker-bracketed blocks below into the repo's hooks
directory (`<git-common-dir>/hooks/`) — idempotently and without
disturbing beads-managed segments (`bd hooks install`).

## Contents

| Path | Purpose |
|------|---------|
| `post-merge` | Advisory: warn after `git pull` brings down MCP-source changes that invalidate the local server binary. **rf2-6jj3r.** |
| `pre-commit` | Refuse commits in the MAYOR checkout that touch worker-tracked surfaces. **rf2-ydl2p.** |
| `lib/check-stale-mcp-binary.sh` | POSIX-sh library used by `post-merge`. |
| `lib/check-mayor-commit-boundary.sh` | POSIX-sh library used by `pre-commit`. |
| `test-pre-commit.sh` | Library unit tests + sandboxed end-to-end smoke for the pre-commit hook. |

## The mayor-marker pattern (pre-commit hook)

The `pre-commit` hook is **mayor-only**. It MUST be a no-op in worker
worktrees. Activation is gated by a marker file at
`<git-common-dir>/mayor-marker`:

- **Mayor checkout** — `git rev-parse --git-dir` returns the common dir
  (e.g. `<mayor-checkout>/.git`). The marker lives there,
  so the hook activates and runs the commit-boundary check.

- **Worker worktrees** — `git rev-parse --git-dir` returns a
  per-worktree git dir at `<common>/worktrees/<name>/`. No marker file
  is present there, so the hook short-circuits to a no-op.

This is the canonical way to distinguish "primary worktree" from
"linked worktrees" without hardcoding paths. Worker hooks share the
common hooks directory via `git worktree add`'s defaults, but each
worker has its own per-worktree git dir — exactly what we need.

### Why a marker file and not a hardcoded path?

The bead (rf2-ydl2p) considered two options:

- **A.** Hardcode the mayor checkout's absolute path.
  Simple; fragile if the repo is cloned elsewhere; OS-coupled.
- **B.** Mark the mayor checkout with a sentinel file (this design).
  Portable, self-documenting, easy to disable (just delete the file).

We picked **B**.

### Permitted vs refused surfaces

The hook uses an **allow-list**: any staged path not in the permitted
list is refused.

**Permitted in mayor commits:**

| Path | Reason |
|------|--------|
| `.beads/issues.jsonl` | bd closure / state edits |
| `MEMORY.md` | optional operator-memory file at repo root (if present) |

**Refused in mayor commits** (anything else, including):

- `implementation/` — framework + adapter src/test
- `tools/` — devtools (xray, story, machines-viz, re-frame2-pair-mcp, ...)
- `spec/` — spec tree
- `docs/` — guide + operational docs
- `examples/`
- `skills/`
- `scripts/` — the scripts themselves (workers commit changes to scripts via worktrees too)
- `migration/`
- `testbeds/`, `README.md`, top-level files — any path NOT in the
  permitted allow-list

`.gitignore`'d paths cannot normally reach the staged diff (git
excludes them before `git add` even sees them). A `git add --force`
bypass would still be caught here because gitignore membership is not
part of the allow-list — that is deliberate.

### Disabling the hook

If you need to disable the hook temporarily (e.g. to investigate a
false positive), delete the marker:

```sh
rm "$(git rev-parse --git-common-dir)/mayor-marker"
```

The hook then no-ops and the mayor checkout behaves like a worker
worktree from the hook's POV. Re-running the installer reinstates the
marker.

For a single commit, `git commit --no-verify` also bypasses the hook
(standard git behaviour; reserved for genuine operator overrides).

## Testing

```sh
# Library unit tests + sandboxed end-to-end smoke.
sh scripts/git-hooks/test-pre-commit.sh
```

The smoke test builds a throwaway repo + worktree pair under
`$TMPDIR`, installs the hook + marker manually, and exercises all four
acceptance scenarios from rf2-ydl2p:

1. Mayor commit with only `.beads/issues.jsonl` staged → passes
2. Mayor commit with `tools/xray/foo.cljs` staged → refused
3. Worker worktree commit with source staged → passes (hook no-op)
4. Mayor commit with mixed staged paths → refused (any-refused triggers)

## Discovery context

The pre-commit hook landed in response to rf2-oswhk (#2136),
2026-05-25: a worker tool call committed to the mayor checkout's local
main because of a PowerShell cwd leak — `cwd` defaulted to the project
implementation directory rather than tracking a prior `cd`. The worker
noticed, recovered, and re-committed via Bash in the worker worktree.
The bogus commit carried only a bd `--claim` flip — no source damage —
but it COULD have carried real worker-tracked source diffs.

The existing `scripts/assert-worker-worktree.ps1` catches **edit**
attempts (workers gate edits on `WORKTREE_ROOT`). It does NOT catch
**commit** attempts. The pre-commit hook closes that gap from the
other side: even if a worker bypasses the edit guard via some side
channel, the mayor's pre-commit will refuse the commit.

## Cross-refs

- **rf2-6jj3r (#2113)** — post-merge stale-binary hook + installer
  pattern this hook extends.
- **rf2-oswhk (#2136)** — near-mishap that surfaced the gap.
- **`scripts/assert-worker-worktree.ps1`** — the edit-time complement.
- **`docs/the-mayor-method/`** — the worker dispatch contract this hook
  enforces.
