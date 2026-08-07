---
description: Mayor Loop — bead dispatch pass (≈15m cadence). Cluster many small same-surface beads into one agent; keep large beads solo; parallelize across surfaces; drive the backlog toward 0.
---
MAYOR LOOP (bead dispatch pass).

**GOAL: drive the open backlog as close to 0 as possible**, up to **6 concurrent background workers**, refilling the instant a slot frees. Dispatch ALL clear-and-unblocked beads regardless of priority (P3/P4 included).

**SOLO vs CLUSTER — by PRIORITY first, then size:**
- **P1 → ALWAYS SOLO.** A dedicated worker, its own PR. A P1 gets focused attention and merges on its own green — never bundled where a cluster-sibling could block it.
- **P2 → SOLO by default.** Lean solo; only cluster several P2s when they are genuinely small, same-surface, and low-risk.
- **P3 / P4 that are small + same-surface → CLUSTER.** This is the primary clustering target: bundle the many small low-priority same-surface beads into **ONE cluster agent** (Shape 2) that works them in priority order → one PR, so you don't handle dozens of trivia serially.
- **Any LARGE bead → SOLO**, whatever its priority (a feature, a deep / multi-file fix — anything a worker spends its whole context on). Never pad a meaty bead into a cluster; never bundle two large beads (the agent times out).
- **A measurement / benchmark window → SOLO, Shape 6** — picked by KIND, not size, and never folded into a cluster. It is the one shape where the worker must NOT iterate until green: the controls arbitrate, a refusal is a correct deliverable, the rig does not change mid-window, and the runs go strictly one at a time.

Different surfaces run as **parallel** agents; same-surface work never runs as two concurrent workers (they merge-conflict). The serial exceptions: an EPIC deliberately structured serially, and a single tightly-coupled module whose core files (e.g. one big handler file) are touched by many beads — that surface is ITS OWN serial lane (sequence its workers as successive PRs, later ones rebasing on the earlier merges — never blind `--theirs`/`--ours`). Note the interaction: solo P1/P2 on a *coupled* surface still cannot run in parallel — sequence them one at a time on that surface (or, for a tight coupled set, one cluster-lane is the pragmatic call). Keep a cluster to ~3-6 small beads; if a surface has more, run successive cluster-PRs (each opens with what it finished + lists remaining). Genuinely-separable surfaces (`tools/xray`, `docs/*`, a distinct artefact dir, `examples/`) parallelize cleanly.

**Count current load first.** In-flight workers = active worker worktrees under the `re-frame2-worktrees` sibling (`git worktree list`) + running background tasks. Dispatch until 6 are busy OR the dispatchable queue is exhausted.

**Find + group.** Run `bd ready`. Filter OUT (NOT dispatchable): operator-decision beads, EPICs, release-coupled / v1.x, hot-zone-conflicting beads (spec/* + deps.edn + shadow-cljs.edn + .github/workflows/* — the fixed list; one toucher at a time, never parallel), and anything gated/blocked. Honor any active operator pause. Bucket the survivors by file-surface; within each bucket apply the SOLO-vs-CLUSTER rule above — solo every P1 (and any large bead / by-default P2), cluster the small P3/P4 remainder — respecting that a coupled surface serializes its workers. BEFORE dispatching, grep for the alleged broken symbol / missing file — if already landed, close as verified-duplicate; spot exact-title duplicates and consolidate.

**Dispatch** each cluster per dispatch-prompt-template.md (Shape 2): a dedicated worktree under the worktree-root (`re-frame2-worktrees` sibling — derive via `git worktree list`, NEVER hardcode), branch `worker/<surface>-cluster`; paste the WORKTREE BOUNDARY block VERBATIM; inject the pre-alpha-masterpiece stance; give the ordered bead list (smallest-cleanup → biggest-correctness) with `bd show <id>` as authoritative; an explicit file-ownership fence + enumerate other in-flight clusters' surfaces; mandatory surface-gate menu + a `## Quality gates` PR section; "never edit the mayor checkout", "do not merge PRs"; the no-`git stash` guard; claim-each-then-commit-per-bead; partial-PR-on-timeout (open with DONE beads + list remaining, never a half-bead uncommitted); worker may close its own beads after opening the PR.

Dispatch immediately on clear; don't queue for a later tick. When the dispatchable queue is exhausted below 6, say so in one line and stop.
