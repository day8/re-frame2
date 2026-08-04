# Worker dispatch — canonical prompt shapes

Terse, copy-adaptable shapes for delegating bounded work to background workers.
Assumes a capable agent. Placeholders:

- `<MAYOR_CHECKOUT>` — the mayor's primary checkout (absolute path)
- `<WORKTREE_ROOT>` — sibling dir holding worker worktrees (derive via `git worktree list`; never hardcode a path)
- `<ASSIGNED_WORKTREE>` — this worker's worktree (subdir of `<WORKTREE_ROOT>`)
- `<BEAD_ID>` — the tracker id

> **Project-specifics live with the project, not here.** The stance, the hot-zone
> file list, the surface→gate matrix, the pre-checkin command, and the worktree
> root are facts about *one* repo — keep them in that repo's agent-instructions
> (this repo: `CLAUDE.md` + `TESTING.md`). This file is the reusable, OS-neutral
> method; pull the concrete values from there at dispatch time.

## Worktree boundary — paste verbatim into every editing dispatch

**The concept (hard-won).** A worker edits only its assigned worktree, never the
mayor checkout. Shell `cwd` is not enough protection: some edit tools resolve a
relative path against the agent's session root rather than the git root, so a
write can land in the mayor checkout *even after a start-of-session guard
"passed"* — the leak happens mid-session in one tool call. A guard script can be
fooled by that cwd resolution; the real backstop is the worker re-verifying its
git root before every edit. New-file leaks are the worst case: a brand-new
gitignored file routed into the mayor checkout shows nothing in the worker's own
`git status`, so it fails silently — check the mayor side explicitly.

```text
WORKTREE BOUNDARY — MANDATORY
Your worktree:  <ASSIGNED_WORKTREE>
Mayor checkout: <MAYOR_CHECKOUT>   ← never edit this.

Before EVERY edit, confirm you are in your worktree:
  git -C <ASSIGNED_WORKTREE> rev-parse --show-toplevel   → must print <ASSIGNED_WORKTREE>
Use ABSOLUTE paths under <ASSIGNED_WORKTREE> for every edit/write. A
start-of-session guard is NOT sufficient — verify per edit.

After your first edit, and after writing any NEW file, confirm it landed in your
worktree and NOT the mayor checkout (check BOTH trees — a new gitignored file
leaking into the mayor checkout is invisible in your own `git status`). If
anything landed outside your worktree: STOP, report both paths, do not
repair/commit/push — let the mayor decide.

Do NOT `git stash` — stashes are repo-global and surface in other workers'
worktrees, cross-contaminating them. Commit to your branch instead.

If you create a `node_modules` symlink/junction in your worktree, remove the
LINK (never its target) before you report done: a later `git worktree remove`
follows it and deletes the shared tree it points at.
```

A project may add a **mayor-side commit guard** (a pre-commit hook in the mayor
checkout that refuses commits touching worker-owned surfaces) so a bypassed
edit-guard is caught from the other side. Install per the project's hook scripts.

## Common preamble (every dispatch)

```text
You are implementing <BEAD_ID> in <project + one-line description>.
<Project stance — from the operator; e.g. pre-alpha / production-stable / refactor-only.>
Do NOT link gitignored working files (the ai/ tree, findings docs) from committed
docs — the strict-docs link validator fails the build in cascade. Inline a
one-sentence summary instead.
```

## Quality gates — the discipline

Every editing dispatch runs the project's pre-checkin gate spine before opening a
PR, and lists what ran in a PR-body section headed **exactly** `## Quality gates`
(a verbatim heading is a contract for automated PR audits) with pass/fail counts.
Two hard-won rules:

- **Gate the transitive surface, not just the file you changed.** A public-surface
  change breaks its *consumers*, not itself — gate every artefact reachable from
  the diff through `:require`/import edges. (The concrete surface→gate matrix and
  how to discover consumers live in the project's `TESTING.md`/`CLAUDE.md`.)
- **Local-green ≠ CI.** "Green locally" usually means the subset the worker ran;
  the red gate is one it skipped (integration/live, a linter, a drift-check). The
  merge decision is CI's, not the worker's hand-off. So the brief must be able to
  say *which* required checks the local spine omits — and say it by citing one
  path, not by re-listing them, because the required set moves. Make the project
  derive that list rather than document it: in this repo it is
  `python scripts/check_fast_pr_gap.py --list`, catalogued under
  `TESTING.md` § *Required checks the fast-PR spine does not run*, and the spine
  prints the digest on its own PASS line. Two shapes make a hand-written list
  wrong within a week — a **second or third required status context** the local
  runner has no lane in at all (linters, docs builds), and a required check that
  is a **step inside a job the runner does run**, which no skipped-tier
  enumeration can see and which reports under that job's name however little it
  resembles what the step does.
- **Never nominate a gate that does not cover the surface being edited.** Site
  builds, linters and test runners all carry exclusion lists, and a gate run over
  an excluded path exits green having verified nothing — the same fail-open defect
  worth hunting anywhere else, except it is now the brief telling the worker to
  trust it. Read the exclusion config before naming the gate. Where a surface
  genuinely has no automated coverage, the honest brief says *no automated gate
  covers this surface; the worker verifies it by hand and says so in the PR body* —
  and the PR body then reports what was checked and the counts.

A skipped gate needs a one-line PR-body reason (e.g. "tool not installed locally;
relying on CI"). A silent skip fails review.

### How a gate is run, not just which one

Four rules about the mechanics. Each is here because it cost real hours, and
none of them is obvious from the gate command itself.

- **Foreground, to completion.** A worker that backgrounds a long gate and ends
  its turn waiting for the completion notification can wait forever: the
  notification does not always arrive, and a turn that has ended has nothing
  left to wake. The worker then reports "standing by" through idle tick after
  idle tick while its branch sits unmoved — four such incidents in a single day,
  every one recovered intact the moment somebody asked it for a status. If you
  background a gate anyway, **poll its log on a timer**; never end a turn
  waiting to be woken.
- **Invoke a backgrounded gate by its ABSOLUTE path.** The gate scripts derive
  their repo root from `${BASH_SOURCE[0]}`, which is relative when the
  invocation is — and a `cd <worktree> && sh scripts/…` does not reliably keep
  that `cd` once backgrounded, so the run adopts whatever cwd the shell really
  has. One did exactly that inside *another live worker's* checkout: it took
  that tree as its spine root, its diff root and its classifier input, then
  reported the resulting verdict as its own. A complete, internally consistent
  run about somebody else's work is far harder to catch than a broken one, so
  every gate now prints `gate root: <path>` as its first line — **check that
  line against your worktree before believing any colour.**
- **Never pipe a gate through `tail`, `head` or `grep`.** A pipeline's exit
  status is its *last* command's, so a red runner reads green and the PR claims
  a pass it never got. Redirect to a log file, echo the runner's own exit code
  explicitly, and quote that number in the PR body.
- **Put *every* gate artefact where git ignores it** — the log and the exit-code
  file both. `*.log`, `*.exit` and `*-exit.txt` all are, so this leaves nothing
  behind:

  ```bash
  sh <WORKTREE_ROOT>/scripts/test-fast-pr.sh > gate-fastpr.log 2>&1; echo "$?" > gate-fastpr.exit
  ```

  Saying only "put the log somewhere ignored" is the trap: a worker satisfies it
  and still strands the exit file, which for a while was itself untracked. One
  leftover is enough — `bench-logs/`, `PRBODY.md`, an unignored exit file — and
  `git worktree remove` refuses the tree from then on. Nine worktrees
  accumulated exactly that way before anyone worked out why they would not reap.
  Cheapest fix is to not create the residue.

## Choosing solo vs cluster

Pick the shape by **priority first, then size** — don't reflexively dispatch
one-worker-per-bead, and don't reflexively bundle everything:

- **P1 → always SOLO** (Shape 1). A high-priority bead gets a dedicated worker and
  its own PR, so it merges on its own green and is never blocked by a cluster-sibling.
- **P2 → SOLO by default.** Cluster several P2s only when they are genuinely small,
  same-surface, and low-risk.
- **Many small low-priority (P3/P4) same-surface beads → CLUSTER** (Shape 2). This is
  the primary clustering case: it stops you handling dozens of trivia serially.
- **Any LARGE bead → SOLO**, whatever its priority (a feature, a deep / multi-file
  fix). Never pad a meaty bead into a cluster; never bundle two large beads.

**One agent owns a surface; surfaces run in parallel.** That is how you avoid serial
handling: same-surface beads ride one agent (which never collides with itself), while
genuinely-separable surfaces dispatch as concurrent agents. Two workers never share a
surface — they merge-conflict and can silently revert each other.

**The serial exceptions** (where same-surface work is *meant* to be sequential): an
EPIC deliberately structured serially; and a single tightly-coupled module whose core
files are touched by many beads — that surface is its own serial lane (sequence its
PRs, later ones rebasing on the earlier merges; never blind `--theirs`/`--ours`). On a
coupled surface, even solo P1/P2 work cannot run in parallel — sequence it one at a
time, or fold a tight coupled set into one cluster-lane.

## Dispatch shapes

**Shape 1 — Solo.** One bead → one PR. Bead id + verbatim title; 2–4 paragraphs of
context with `file:line` citations; numbered concrete steps; worktree
`<WORKTREE_ROOT>/<desc>-<BEAD_ID>`, branch `worker/<desc>-<BEAD_ID>`; the boundary
block; claim the bead; gates with exact commands; push + `gh pr create` titled
`<scope>(<artefact>): <summary> (<BEAD_ID>)` with the `## Quality gates` section;
report PR URL + per-step summary + test deltas. *A coverage/rigour pass must add
≥1 adversarial/negative case per surface — assertion-count growth alone only
exercises the happy path.*

**Shape 2 — Cluster.** Several small same-surface beads → one PR (see *Choosing solo
vs cluster* above for when — chiefly the small P3/P4 remainder, not P1s). Order commits
smallest-cleanup → biggest-correctness-fix (a failing bead must not strand the small
wins); claim each bead before its commit (history mirrors tracker state → a
stalled cluster leaves a clean partial trail); gates after each commit + full
regression after all. Disjoint-surface "small-misc" clusters are valid at the tail
of a drain — the binding rule is hot-zone parallelism, not strict same-surface.
Keep a cluster to ~3–6 small beads; beyond that, run successive cluster-PRs (each
opens with what it finished + lists the remainder, never a half-bead uncommitted).

**Shape 3 — Audit (read-only).** A finding, not a fix. Goal + surface paths +
prior findings to avoid re-discovering; boundary block; write the findings doc to
the gitignored working tree FIRST (never commit it, never link it from committed
files); file follow-on beads one at a time, appending each id to the audit bead's
notes so progress survives a timeout; close with verdict + severity counts +
cross-refs; no PR by default. The mayor may reorganize/reject findings — not every
finding is actioned.

**Shape 4 — Cluster reviewer (read-only, no dispatch).** Shapes the next wave. List
in-flight workers + their surfaces (don't recommend touching those); enumerate
recently-filed beads (`git log -p --since='35 minutes ago' -- <tracker-file>`) +
the ready queue; per bead decide (A) add to an in-flight cluster / (B) new cluster
(3+ beads, shared non-in-flight surface) / (C) solo (P0/P1 correctness, >250 LoC,
decision-resolved, cross-cutting) / (D) defer; output the net next-dispatch shape
in 2–3 sentences. Do NOT change tracker state.

**Shape 5 — Fix a PR's failing CI.** A red check that isn't structurally
irrelevant. Failing-check name + log lines; 2–3 root-cause hypotheses; worktree off
the EXISTING branch (not a new one); boundary block; **run the ACTUAL failing gate
locally** (not a proxy that already passed); fix surgically (or file a follow-on if
it's deeper than scope + the stance allows a safe-out); push to the existing branch
(never main); update its `## Quality gates`. *Never `--admin` past a failing
touched-surface gate.* Diagnosis often beats the failure log — test the hypothesis
before fixing.

## Failure modes these shapes close

- Back-compat shims by default → stance explicit in every preamble.
- Same-file races between concurrent workers → enumerate in-flight surfaces.
- Edits leaking into the mayor checkout (esp. silent new-file leaks) → boundary block + post-write both-trees check.
- Cross-worktree contamination via `git stash` → no-stash rule (stashes are repo-global).
- Worktree cleanup deleting *through* a `node_modules` link into the shared tree it points at → the worker unlinks before reporting done, and the cleanup path disarms before removing.
- "Green locally" merged into a red CI gate → gate the transitive surface; merge on CI, not the hand-off; a real failure gets a fix-worker, never `--admin`.
- A passing synthetic test that routes around the real bug → reproduce the actual failing path.
- Clusters split that should be one PR (or vice-versa) → cluster reviewer pre-validates shape.
- Stalled workers losing analysis → findings-first + one-bead-at-a-time tracker creates.
- Re-discovering known issues → name recent landings + prior findings.
- Generic prompts → require `file:line` citations + concrete fix sketches.

---

*Record three or four exemplary dispatches per project (a solo, a cluster, an
audit, a CI-fix) — a few good examples teach a new mayor more than thirty
mediocre ones. The concrete gate matrix, hot-zone list, guard/install scripts,
and cache-sharing setup are project-specifics; they live in the project's
agent-instructions + TESTING docs, not in this method file.*
