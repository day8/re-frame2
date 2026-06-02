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
  merge decision is CI's, not the worker's hand-off.

A skipped gate needs a one-line PR-body reason (e.g. "tool not installed locally;
relying on CI"). A silent skip fails review.

## Dispatch shapes

**Shape 1 — Solo.** One bead → one PR. Bead id + verbatim title; 2–4 paragraphs of
context with `file:line` citations; numbered concrete steps; worktree
`<WORKTREE_ROOT>/<desc>-<BEAD_ID>`, branch `worker/<desc>-<BEAD_ID>`; the boundary
block; claim the bead; gates with exact commands; push + `gh pr create` titled
`<scope>(<artefact>): <summary> (<BEAD_ID>)` with the `## Quality gates` section;
report PR URL + per-step summary + test deltas. *A coverage/rigour pass must add
≥1 adversarial/negative case per surface — assertion-count growth alone only
exercises the happy path.*

**Shape 2 — Cluster.** 3–12 beads on a shared surface → one PR. Order commits
smallest-cleanup → biggest-correctness-fix (a failing P1 must not strand the small
wins); claim each bead before its commit (history mirrors tracker state → a
stalled cluster leaves a clean partial trail); gates after each commit + full
regression after all. Disjoint-surface "small-misc" clusters are valid at the tail
of a drain — the binding rule is hot-zone parallelism, not strict same-surface.
Keep bundles to ~3–4 beads; larger ones tend to time out.

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
