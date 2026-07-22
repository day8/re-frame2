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
| `pre-commit` | Refuse commits in a WORKER worktree that touch the beads DATABASE. **rf2-ia8o7.** |
| `lib/check-stale-mcp-binary.sh` | POSIX-sh library used by `post-merge`. |
| `lib/check-mayor-commit-boundary.sh` | POSIX-sh library used by `pre-commit` (rf2-ydl2p). |
| `lib/check-beads-boundary.sh` | POSIX-sh library used by `pre-commit` (rf2-ia8o7) **and** by `scripts/check-beads-pr-boundary.sh`, the CI arm. |
| `test-pre-commit.sh` | Library unit tests + sandboxed end-to-end smoke for both pre-commit blocks. |

The unit of installation is a marker **block**, not a hook: `pre-commit`
carries two of them. The installers key their registries on block id
(`mayor-commit-boundary`, `worker-beads-boundary`, `mcp-staleness`) so a
hook can grow another block without either installer changing shape.

## The two boundaries

The `pre-commit` blocks are mirror images of one another:

| Block | Fires in | Refuses |
|-------|----------|---------|
| rf2-ydl2p | the **mayor** checkout | worker-tracked surfaces (source, spec, docs, scripts) |
| rf2-ia8o7 | every **worker** worktree | the beads **database** (`.beads/issues.jsonl` and friends) |

Together they say: source flows mayor → worker, tracker state flows
worker → mayor, and neither crosses back.

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

## The worker beads boundary (rf2-ia8o7)

`.beads/issues.jsonl` is a full-database export, and `bd` rewrites it in
**every** checkout. A worker worktree therefore carries a snapshot of the
tracker as it stood when that worktree was created; committing it
**time-travels the tracker**, reopening beads closed since and deleting
beads filed since. PR #6677 landed exactly that — 135 insertions / 136
deletions of pure collateral.

### Activation: derived, not marked

This block is the mirror of rf2-ydl2p, and it deliberately does **not**
use a marker file. It activates wherever the checkout is *not* the
primary worktree, derived from `git worktree list --porcelain` (git
always lists the main worktree first) and overridable with
`RF2_MAYOR_ROOT`, matching `scripts/assert-worker-worktree.sh`.

A marker would be wrong here. The mayor block fails **open** without its
marker, which is safe — a fresh clone simply gets no guard. Deriving
instead means a fresh clone that has never run the installer is correctly
treated as primary rather than being locked out of its own tracker. The
derivation likewise fails open, with a warning, if it cannot decide: a
guard that cannot tell where it is must not brick every commit in the
repository. The CI arm is the backstop.

### Permitted vs refused paths

An **allow-list**, like its sibling. The permitted set is the small,
human-authored beads config surface; everything else under `.beads/` is
database-derived and refused.

**Permitted from any worktree:** `.beads/README.md`, `.beads/config.yaml`,
`.beads/.gitignore`, `.beads/hooks/**`.

**Refused outside the mayor checkout:** `.beads/issues.jsonl`,
`.beads/metadata.json`, and anything else under `.beads/` — including
artefacts that do not exist yet (`.beads/events.jsonl` when events-export
is enabled, `.beads/dolt/**`, `.beads/*.db`). That is the point of an
allow-list: nobody has to remember to extend a deny-list.

### Why not `git update-index --skip-worktree`?

The bead originally proposed setting `--skip-worktree` on
`.beads/issues.jsonl` in every new worker checkout. Measured, that is
strictly worse than refusing the commit: it hides the local edit from
`git status` (clean tree) while `git pull --rebase` still refuses to
advance over it —

```
error: Your local changes to the following files would be overwritten by merge:
	.beads/issues.jsonl
Please commit your changes or stash them before you merge.
Aborting
```

— leaving HEAD frozen at a stale base with **nothing in `git status`** to
explain why. This repo has already been bitten by that silent-pull-abort
shape. A loud refusal at commit time, naming the file and the remedy, is
the better trade. `test-pre-commit.sh` carries a regression test asserting
the remedy text never recommends `skip-worktree` again.

### The CI arm

`scripts/check-beads-pr-boundary.sh` applies the same classifier to a PR
diff, so the local hook and the CI gate cannot drift:

```sh
sh scripts/check-beads-pr-boundary.sh origin/main   # pre-flight a branch
```

It enforces on `pull_request` only — pushes to `main` **are** the mayor's
checkpoint flow. A missing base ref fails closed: a gate that cannot see
the diff certifies nothing.

## Testing

```sh
# Library unit tests + sandboxed end-to-end smoke, both blocks.
sh scripts/git-hooks/test-pre-commit.sh
```

The smoke test builds a throwaway repo + worktree pair under `$TMPDIR`,
installs the hook + marker manually, and exercises every acceptance
scenario from both beads.

From rf2-ydl2p:

1. Mayor commit with only `.beads/issues.jsonl` staged → passes
2. Mayor commit with `tools/xray/foo.cljs` staged → refused
3. Worker worktree commit with source staged → passes (hook no-op)
4. Mayor commit with mixed staged paths → refused (any-refused triggers)

From rf2-ia8o7:

5. Worker commit staging `.beads/issues.jsonl` → refused, message names the file
6. Worker commit touching nothing under `.beads/` → passes
7. Worker commit staging `.beads/config.yaml` → passes (allow-listed)
8. Mayor commit staging `.beads/issues.jsonl` → passes (guard no-ops in primary)

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
- **rf2-ia8o7 (PR #6677)** — the stale worker-snapshot incident that
  motivated the beads boundary.
- **`CLAUDE.md` > Beads durability** — the operator-facing rules,
  including the merge-side rule (never `--theirs`/`--ours` on `.beads`;
  resolve then regenerate).
- **`docs/the-mayor-method/`** — the worker dispatch contract this hook
  enforces.
