---
description: Mayor Loop — bead dispatch pass (≈15m cadence). Cluster many small same-surface beads into one agent; keep large beads solo; parallelize across surfaces; drive the backlog toward 0.
---
MAYOR LOOP (bead dispatch pass).

**`docs/the-mayor-method/loops.md` §2 is the body of this loop** — read-the-newest-note-first,
the fence filters, counts-are-claims, saturation, and routing a finding onto a live worker's
file. **`dispatch-prompt-template.md` owns brief construction** — solo-versus-cluster, the six
shapes, the worktree-boundary block, the gate-mechanics block, the common preamble and the
fence-derivation rules. Neither is restated here. **This file pins the concrete commands, paths
and gates for this repository.**

Drive the open backlog toward 0, up to **6 concurrent background workers**, refilling the
instant a slot frees; dispatch P3/P4 too. Dispatch immediately on clear — don't queue for a
later tick. Honor any active operator pause. When the dispatchable queue is exhausted below 6,
say so in one line and stop.

## Count the load

In-flight workers = worker worktrees under the `re-frame2-worktrees` sibling
(`git worktree list` — derive it, never hardcode) plus running background tasks.

**Before filling that ceiling, establish whether a measurement window is live and classify its
estimand** (`dispatch-prompt-template.md`, Shape 6). While a CLOCK window runs the six-worker
target is SUSPENDED, not trimmed: dispatch only work that cannot load the box — documentation,
YAML, tracker prose, the cheap python checkers — and say so in one line rather than silently
under-saturating. **Every dispatch issued in that window carries a clause reserving the
machine**, naming what the worker may not run — no `scripts/test-fast-pr.sh`, no shadow-cljs,
no browser, no Playwright, no npm build, no JVM gate — and saying that *if the only gate
covering your surface is a heavy one, STOP AND REPORT* is a correct outcome. Without that
second half the worker runs the heavy gate anyway to satisfy the surface-gate menu the same
brief hands it. ALLOCATION windows are exempt and must not consume a drain.

## Find and group

`bd ready`. Filter out operator-decision beads, EPICs, release-coupled or v1.x work, hot-zone
collisions and anything gated.

**The hot-zone roster is `CLAUDE.md`'s, under *Minimise merge conflicts when dispatching*.**
Read it there, not from memory. It ENUMERATES NAMED FILES, so `spec/*` is not it: a remembered
glob is over-broad on `spec/` (serialising pages nobody holds, costing coverage) and under-broad
on everything CLAUDE.md lists outside it, including the pair it marks one-toucher by
construction.

Bucket the survivors by file-surface, then pick the shape per `dispatch-prompt-template.md`.

### Verifying a census before you dispatch it

**Exclude the tracker export.** `.beads/issues.jsonl` is a full-database export carrying every
title, description and comment, so it matches almost any identifier a census greps for. Run
`git grep ... -- ':!.beads'`, in the brief as much as in your own check. One tree-wide grep
returned 124 KB of tracker rows before a single real hit, and a worker reported ~33 files
carrying a token that sits in 16.

**Run the census against the committed tree, not the working tree.** `core.autocrlf` is true
here, so a CR precedes every line ending in any file `.gitattributes` does not pin, and a
pattern ending in `$` straight after literal text matches NOTHING. Name the tree object — this
loop already requires the census to run at `origin/main` HEAD, and committed blobs are LF:

```bash
git grep -lE '^\(ns re-frame\.core$' origin/main -- ':!.beads'      # the ONE file
git grep -lP '^\(ns re-frame\.core\r?$'                             # only if a working-tree search is needed
```

**Repair a false zero by making the pattern exact, never by loosening it.** Dropping the `$` or
appending `.*$` turns an exact line into a PREFIX: the same pattern unanchored returns seventeen
files, sixteen of them `re-frame.core-http`, `re-frame.core-ssr` and other namespaces that merely
share the prefix. A count too high sends a worker somewhere real; a zero CLOSES a bead. Control
both directions — a positive control rules out a DEAD pattern and nothing else, so also read the
HIT LIST and check the prefix-sharing near-misses are absent from it.

**Ask how else the thing counted could be SPELLED, and widen once.** Measured on live command
lines into the deleted freehand tree: the absolute spelling found 29 lines in 16 files and the
`cd implementation`-relative spelling of the same command found 10 more in 6 — file sets
DISJOINT, so six whole pages were invisible to a census with a live positive control that
re-ran clean at HEAD every time.

## Dispatch

Dedicated worktree under the `re-frame2-worktrees` sibling; branch `worker/<surface>` or
`worker/<surface>-cluster`; `bd show <id>` authoritative over the brief.

**Paste three blocks VERBATIM**, each from the one file that owns it:

| block | source |
|---|---|
| PROJECT STANCE | `.claude/commands/mayor-posture.md` |
| WORKTREE BOUNDARY | `dispatch-prompt-template.md`, *The worktree boundary block* |
| GATE MECHANICS | `dispatch-prompt-template.md`, *Quality gates — how a gate is run* |

Extract mechanically, anchored to the block's opening and closing TEXT, then paste the result
into the dispatch — see *Pasting a block* in `dispatch-prompt-template.md`. Referencing a brief
file by path is out: a worker that skims it has not received the block, and nothing in the
transcript says so.

**Derive the fence at dispatch time**, never from memory of who you dispatched and never from
what you BRIEFED them to touch:

```bash
gh pr list --state open --json number,headRefName,files    # PAGINATES AT 100 -- a round 100 is the tell
git diff --name-only origin/main...worker/<branch>          # three-dot; two-dot names every unrebased branch
git status --porcelain                                      # inside the worktree: uncommitted work is invisible to the diff above
```

Read each survivor's PR state — a diff alone cannot separate a live unpushed worker from a
merged leftover whose fork point predates its own merge. A worktree clean on both sources has
not started yet rather than owning nothing. **The one carve-out to "never from memory" is a peer
dispatched in the SAME message**: all four sources are blind at once, so name it from what you
just dispatched and flag it as *dispatched alongside you, may not exist yet; re-derive at
start-up*.

Then: the surface-gate menu; a `## Quality gates` PR section; `scripts/check_fast_pr_gap.py` as
the ONE path the brief cites for the fast-spine-versus-required-CI gap (`--list` derives it at
step granularity — TESTING.md:121) **plus** a requirement that the PR body state whether the
spine ran at all and which targeted gates ran directly, because the derived gap is a fact about
the SPINE and not a census of what this worker did; "never edit the mayor checkout"; "do not
merge PRs"; the no-`git stash` guard; claim-each-then-commit-per-bead; partial-PR-on-timeout;
worker may close its own beads after opening the PR.

### Nominating a markdown link gate — by PATH, never by job name

`CLAUDE.md` owns the split under *Build & Test*, at "Link validation is split across two
gates". A gate handed a path outside its own roots walks nothing and exits 0, so a wrong
nomination returns a GREEN rather than an error. The CI job is named `verify-readme-links`
while the gate that actually walks the READMEs is the other one.

`.claude/commands/*.md` — THIS file included — is `scripts/check_readme_links.py --ci`'s, via
the arm that resolves the path-shaped references such a file carries.

**Before naming either gate, read that gate's roots AT SOURCE and write in the brief why they
cover the path.** Knowing the split is demonstrably not enough — `check_doc_slugs` SOUNDS like
the general documentation gate and a file full of prose FEELS like its territory, and the same
mayor made the same misnomination on consecutive days. Open `scripts/check_doc_slugs.py`, read
its `DEFAULT_ROOTS` tuple and the `tools/*/spec` tier `_iter_markdown` adds beneath it. Root
membership beats file shape in both directions: a broken link in `skills/reagent-migration/README.md`
left `check_readme_links.py --ci` at exit 0 and turned `check_doc_slugs.py` RED. The obligation
is the WRITING — a mayor who has to give the reason has to go and look.

**The fence carve-out is the second half of that nomination.** Both gates share one extractor
and strip fenced code blocks before reading a link, so for an edit CONFINED to a fence the brief
says neither gate is its gate and the worker hand-checks and reports. **But the exception is a
CONSTANT, not a prose list**: read `FENCED_DOC_LINK_TREES` in `scripts/check_doc_slugs.py` at
source before writing the clause — in those trees a doc link inside a fence IS reported.
Quoting today's value is fine; a hand-written pair of tree names as the definition is the drift
this repository has been bitten by twice.

**The same clause governs any claim about which gate or surface covers a path.** For the
changed-surface classifier the deciding source is `.github/scripts/report-changed-surfaces.sh`
run over the actual changed paths.
