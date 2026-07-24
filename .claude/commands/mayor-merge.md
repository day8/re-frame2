---
description: Mayor Loop — merge green PRs (signal-driven + ≈30m cadence). On most signals, sweep open PRs and merge any green; never block on a CI watch.
---
MAYOR LOOP (merge PRs).

**Run this on MOST signals — not just the cadence.** Any time you're re-invoked (a worker completing, an operator message, a loop tick), do a quick open-PR sweep and merge whatever is already green. Checking is cheap; it keeps the pipeline flowing.

**NEVER block the session waiting on CI.** Do NOT launch `gh pr checks <pr> --watch` (it occupies a slot and stalls you). Use a ONE-SHOT `gh pr checks <pr>` per open PR: merge the ones that are 0-fail/0-pending NOW; for any still-pending PR, leave it and re-check on the NEXT signal — the steady stream of worker-completions plus the cadence will catch it without a babysitting script.

Run `gh pr list --state open`. For each PR: confirm CI green, the diff matches its bead, scope did not sprawl, failure output stays actionable, and a `## Quality gates` section is present. Merge on green. Use `--admin` ONLY when a pending check is structurally irrelevant to the diff — name the gate, name why the diff cannot affect it, then merge; a failing test on the touched surface is NEVER an --admin candidate (and `MERGEABLE=UNKNOWN` is GitHub's recompute lag, which `--admin` may bypass once CI is 0-fail/0-pending). NEVER `git push origin --delete <branch>` before the merge succeeds (it auto-closes the PR). Verify state==MERGED via `gh pr view` before any branch cleanup. After EACH merge (don't batch): `git pull --ff-only` immediately, verify the worker closed its bead (close it with a concrete cross-ref reason if not), and record the decision in the bead. A real, repeated touched-surface failure is NOT a flake — dispatch a fix-worker to the same branch (run the ACTUAL failing gate, not a local proxy), never --admin past it. Honor any active operator pause. If no open PRs, say so and stop.
