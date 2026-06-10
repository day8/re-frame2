---
description: Mayor Loop — bead dispatch pass (≈15m cadence). Saturate up to 6 concurrent background workers; drive the open backlog as close to 0 as possible.
---
MAYOR LOOP (bead dispatch pass).

**GOAL: drive the open backlog as close to 0 as possible.** Saturate **up to 6 concurrent background workers** and refill the instant a slot frees. Dispatch ALL clear-and-unblocked beads regardless of priority — P3/P4 included, not just the high ones. The only reason to stop short of 6 is that the dispatchable queue is genuinely empty (quiescent is a valid state — don't manufacture work).

**Count current load first.** In-flight workers = active worker worktrees under the `re-frame2-worktrees` sibling (`git worktree list`) + any running background tasks. Dispatch new workers until 6 are busy OR the dispatchable queue is exhausted.

**Find work.** Run `bd ready`. Filter OUT (NOT dispatchable): operator-decision beads, EPICs, release-coupled / v1.x, hot-zone-conflicting beads (spec/* + deps.edn + shadow-cljs.edn + .github/workflows/* — the fixed list; sequence these, never parallel two on the same hot file), and anything gated/blocked. Honor any active operator pause (a hold supersedes this loop until its release condition is met). BEFORE dispatching, grep for the alleged broken symbol / missing file — if already landed, close as verified-duplicate.

**Dispatch** each clear-and-unblocked bead per dispatch-prompt-template.md: a dedicated worktree under the worktree-root (the `re-frame2-worktrees` sibling of the mayor checkout — derive via `git worktree list`, NEVER hardcode a path), branch worker/<desc>-<bead>; paste the WORKTREE BOUNDARY block VERBATIM; inject the pre-alpha-masterpiece stance into the preamble; enumerate other in-flight workers + their write surfaces so the receiver pattern-matches for collisions; mandatory surface-gate menu + a `## Quality gates` PR section; "never edit the mayor checkout", "do not merge PRs"; worker may close its own bead after opening the PR. Also paste the no-`git stash` guard (stashes are repo-global and leak across worktrees).

Dispatch immediately on clear; don't queue for a later tick. Keep dispatching until 6 workers are busy or nothing is dispatchable. When the queue is exhausted below 6, say so in one line and stop.
