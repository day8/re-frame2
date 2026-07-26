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
| `post-merge` | Advisory: warn after a MERGING or FAST-FORWARDING pull when the hooks on disk no longer match this directory. **rf2-zt65l.** |
| `post-rewrite` | Advisory: the same warning after a REBASING pull (`git pull --rebase` with a local commit), which never reaches `post-merge`. **rf2-zt65l.** |
| `pre-commit` | Refuse commits in the MAYOR checkout that touch worker-tracked surfaces. **rf2-ydl2p.** |
| `pre-commit` | Refuse commits in a WORKER worktree that touch the beads DATABASE. **rf2-ia8o7.** |
| `pre-commit` | Refuse a commit from ANY worktree that would empty `.beads/issues.jsonl` or lose more than a tenth of it. **rf2-or8te.** |
| `lib/check-stale-mcp-binary.sh` | POSIX-sh library used by `post-merge`. |
| `lib/check-hook-install-staleness.sh` | POSIX-sh library used by `post-merge` **and** `post-rewrite` (rf2-zt65l) — one advisory, one home. |
| `lib/check-mayor-commit-boundary.sh` | POSIX-sh library used by `pre-commit` (rf2-ydl2p). |
| `lib/check-beads-boundary.sh` | POSIX-sh library carrying two checks over one file: `check_beads_boundary` (rf2-ia8o7), used by `pre-commit` **and** by `scripts/check-beads-pr-boundary.sh`, the CI arm; and `check_beads_truncation` (rf2-or8te), used by `pre-commit` alone. |
| `test-pre-commit.sh` | Library unit tests, sandboxed end-to-end smoke for all three pre-commit blocks, the CI arm, the installer, real pulls of both shapes, and the checkpoint helper. |

The unit of installation is a marker **block**, not a hook: `pre-commit`
carries three of them. The installers key their registries on block id
(`mayor-commit-boundary`, `worker-beads-boundary`, `beads-truncation-floor`,
`mcp-staleness`, `hook-staleness`, `hook-staleness-rebase`) so a hook can grow
another block — or the set can grow another hook — without either installer
changing shape.

Hook sources carry no `.sh` extension, so `.gitattributes` pins each of them to
`eol=lf` by name. A new hook here needs a line there too, or a Windows checkout
(`core.autocrlf=true`) CRLF-rewrites the source and `--check` never again agrees
with the LF-normalised installed copy.

## Installing, and staying installed

One command, from anywhere in the repo:

```sh
sh scripts/install-git-hooks.sh                                        # POSIX
powershell -ExecutionPolicy Bypass -File scripts/install-git-hooks.ps1  # Windows
```

It is idempotent, takes about a second, and only rewrites the blocks it owns
(beads-managed segments from `bd hooks install` are left alone).

**Once per clone is enough.** The installer writes to `<git-common-dir>/hooks`,
and linked worktrees share that directory — no `core.hooksPath` indirection,
nothing per-worktree. A worktree created a month after the install is guarded
the moment it exists. That is asserted, not assumed: `test-pre-commit.sh`
layer 6 adds a worktree after installing and checks it resolves the same hooks
directory.

To ask whether a checkout is currently guarded:

```sh
sh scripts/install-git-hooks.sh --check   # exit 0 = installed and current
```

### Why there is a staleness advisory (rf2-zt65l)

The hooks on disk are **copies**. `git pull` updates the sources here and
leaves the copies untouched, so every change to this directory is a fresh
opportunity for the two to diverge silently — and they did. The beads
boundary block (rf2-ia8o7) landed on 2026-07-22; the mayor checkout, and
therefore all sixteen worktrees sharing its hooks directory, went on running
a 2026-06-01 copy that did not contain it. For seven weeks the guard was
documented, tested, and absent. Nothing was ever going to say so, because
nothing ran the installer and nothing compared the two.

The advisory closes that loop at the moment the drift is created: after a pull
it runs `--check` and, when that fails, prints the repair command. It is
advisory — it never fails the pull — and it is silent on a healthy checkout,
because an advisory that fires on ordinary work gets muted.

CI cannot cover this. The gate that matters here runs on a developer's
laptop, and the only thing that can notice a stale copy is the machine
holding it.

#### It takes two hooks, because git splits the pull

`git pull` is not one code path, and the advisory first shipped on only one of
them:

| Pull | What git does | Hook |
|------|---------------|------|
| `git pull`, `git pull --ff-only` | merge or fast-forward | `post-merge` |
| `git pull --rebase`, no local commit | fast-forward through git's merge shortcut | `post-merge` |
| `git pull --rebase`, local commit to replay | a real rebase | `post-rewrite` |

A rebase never invokes `post-merge`. So for as long as the advisory lived only
there, it was silent on exactly the completion path `AGENTS.md` and `CLAUDE.md`
mandate for every worker — reproduced in two throwaway clones during the
rf2-zt65l audit (PR #6921): the `--rebase` pull landed hook drift and printed
nothing, while the `--no-rebase` control printed the warning. `post-rewrite`
(argument `rebase`) is git's hook for that path, and it now carries the same
block, from the same library.

`post-rewrite` also fires for `git commit --amend`, which lands nothing from
upstream and so can create no drift; the block checks its `$1` and stays quiet
there. It drains stdin (git writes the old/new sha pairs to it), so any block
added after it must take the list from `$_rf2_rewrites`.

Layer 7 of `test-pre-commit.sh` drives real `git pull`s of all three shapes
above. It has to: layer 6 calls the hook directly, and calling a hook directly
proves the message is right while saying nothing about whether git ever runs it.
That gap is precisely where the seven-week silence hid a second time.

## The two boundaries, and the floor under them

The first two `pre-commit` blocks are mirror images of one another. The third
asks a different question of the same file:

| Block | Fires in | Refuses |
|-------|----------|---------|
| rf2-ydl2p | the **mayor** checkout | worker-tracked surfaces (source, spec, docs, scripts) |
| rf2-ia8o7 | every **worker** worktree | the beads **database** (`.beads/issues.jsonl` and friends) |
| rf2-or8te | **every** worktree, mayor included | a staged tracker that has lost more than a tenth of HEAD's rows |

The first two say: source flows mayor → worker, tracker state flows
worker → mayor, and neither crosses back. The third says: and when the tracker
does flow, it arrives whole.

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

## The truncation floor (rf2-or8te)

The boundary above decides **who** may commit the tracker database. It says
nothing about **what** is in it, and twice the answer has been "nothing":

| Date | Commit | Damage |
|------|--------|--------|
| 2026-06-10 | `7aea52459` | 7172 rows deleted |
| 2026-07-26 | `4d8042d80d` | 2573 rows deleted |

Both were a plain `git add` of the JSONL caught mid-rewrite, and both came from
the **mayor checkout** — the one place the block above deliberately no-ops,
because committing the tracker there is the intended flow. Afterwards the
working tree, the index and HEAD were all empty and `git status` was **clean**,
so nothing on screen said anything was wrong.

`scripts/beads-checkpoint.sh` has refused exactly this since the first
incident: it re-exports from Dolt rather than trusting the working file,
refuses an empty export outright, and refuses anything below 90% of HEAD. That
guard is sound and unchanged. **Neither commit went through it.** So the same
floor, with the same thresholds, now also lives in the hook — the one place no
committer can route around.

### An empty export is a REGENERATION event

The Dolt database is the source of truth and was never at risk: `bd list`
worked throughout the 2026-07-26 incident, and one `sh scripts/beads-checkpoint.sh`
rebuilt 2576 rows over a HEAD of 0. Recovery is one command.

**Do not restore an older export from git history.** That time-travels the
tracker — beads closed since the older export reopen, beads filed since vanish.
It is the rf2-ia8o7 failure mode arriving by the repair path. The refusal
message says so, because the instinct at the moment of the incident is exactly
the wrong one.

### A genuine mass delete is the operator's call

The floor refuses one, and **names the escape** in the message:

```sh
bd status              # inspect the shrink
git commit --no-verify # then commit it by hand
```

That mirrors the checkpoint script's own answer — "refuse and say so; a genuine
mass delete is rare enough to commit by hand". A deliberate `bd gc` is rare and
worth one extra flag; an emptied export is neither rare nor deliberate.

### What it deliberately does not do

It does not run in the CI arm. A mayor checkpoint goes to `main` directly and
never appears in a pull-request diff, so neither incident could have been seen
there, and a guard is warranted only where the failure is silent and has
already happened.

It does not fire when HEAD carries no rows. A fresh clone must meet the tracker
before it meets the guard, and a HEAD with nothing in it can lose nothing.

### The CI arm

`scripts/check-beads-pr-boundary.sh` applies the same classifier to a PR
diff, so the local hook and the CI gate cannot drift:

```sh
sh scripts/check-beads-pr-boundary.sh origin/main   # pre-flight a branch
```

It enforces on `pull_request` only — pushes to `main` **are** the mayor's
checkpoint flow. A missing base ref fails closed: a gate that cannot see
the diff certifies nothing.

Pass the **base branch**, not a precomputed branch point. The script diffs
from `git merge-base BASE HEAD` to `HEAD` — the changes your branch
*introduced* (rf2-5z20y). A two-endpoint `git diff BASE HEAD` would report
every path where the two trees differ, including paths only `main` moved,
and since the mayor checkpoints `.beads/issues.jsonl` on essentially every
loop tick, every branch older than the last checkpoint would be blamed for
contamination it never committed. An unresolvable merge base — usually a
shallow clone that lacks the branch point — fails closed too.

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

From rf2-zt65l, driving the real installer against a throwaway repo — the
layers above stage the hook by hand, so without these the installer had no
coverage at all:

9. Fresh checkout installs clean; `--check` then certifies it
10. Every registered block reaches the installed hooks (a partial install
    was the bead's actual failure mode)
11. A worktree created *after* the install shares the primary's hooks dir
12. From that inherited install, a worker tracker commit is refused …
13. … and an ordinary source commit from the same worktree is not
14. Strip a block from the installed hook: `--check` fails and `post-merge`
    says so; re-running the installer repairs it and the advisory goes quiet

From the rf2-zt65l audit reopen, driving real pulls between a throwaway upstream
and a clone — the layer above calls the hook by hand, which cannot tell you
whether git calls it:

15. A `git pull --rebase` with a local commit really rebases …
16. … and reports the hook drift it just landed (the audit's finding: it used
    to print nothing at all)
17. A rebasing pull that lands no hook change stays silent
18. A merging pull still reports drift — the rebase arm is an addition, not a
    migration

From rf2-51uz1, on the other side of the same boundary — the checkpoint helper
(`scripts/beads-checkpoint.sh`), against a stub `bd`:

19. A `bd close` that exists only in the database survives the standard
    `git checkout HEAD -- .beads` cleanup, because the checkpoint re-exports
20. `--pre-pull` refuses while the working export is ahead of HEAD, and is
    silent once it is not
21. A failed or empty export commits nothing and leaves the tracker untouched
22. A memory reorder is not a change, so it is not a commit
23. A worker worktree is refused: the tracker database is the mayor's to commit

From rf2-or8te, driving the floor from the sandbox's **primary** worktree —
the checkout both incidents came from, and the one every layer above no-ops in:

24. An emptied tracker staged by plain `git add` is refused, and the message
    carries the two row counts, the regeneration rule, the repair and the escape
25. A shrink past the 1/10 floor is refused too, and is not mislabelled an
    empty export
26. An export at exactly 9/10 of HEAD commits normally — the floor is pinned at
    the threshold, not at a comfortable margin
27. `git commit --no-verify` lands a deliberate mass delete: the escape the
    message names actually works
28. A tracker over a 0-row HEAD, and a commit that stages no tracker path at
    all, are both untouched

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
- **rf2-zt65l** — the seven-week gap between that boundary landing here and
  reaching anybody's `.git/hooks`; source of the staleness advisory, its
  rebase-path arm, and the installer's own test layers.
- **rf2-or8te** — the truncation floor, added after an empty export reached
  main a second time (`4d8042d80d`, 2026-07-26) by a path the checkpoint
  script's own guard could not see.
- **rf2-51uz1 / `scripts/beads-checkpoint.sh`** — the mirror-image fault on the
  mayor's side: `git checkout HEAD -- .beads` before a pull reverts a `bd close`
  the last export-commit missed, and a checkpoint that trusts the working file
  writes the revert back. The helper re-exports from the database instead, and
  `--pre-pull` says whether clearing `.beads` is safe yet.
- **`CLAUDE.md` > Beads durability** — the operator-facing rules,
  including the merge-side rule (never `--theirs`/`--ours` on `.beads`;
  resolve then regenerate).
- **`docs/the-mayor-method/`** — the worker dispatch contract this hook
  enforces.
