---
name: mayor-recovery-after-token-limit-or-worker-crash
summary: Recover true project state after mayor or worker context loss.
when_to_use: When a mayor session, Claude session, or worker may have stopped unexpectedly.
---

# Mayor Recovery After Token Limits or Worker Crashes

## Why This Exists

A dead agent can leave convincing-looking project state behind. A bead may still
be `in_progress`. A worker worktree may contain useful unpushed edits. A branch
may already have a PR. A PR may already be merged. None of those facts, alone,
tell you whether the work is still live.

This is not obvious from the code. It is a workflow scar: when an agent hits a
token limit or a worker dies, tracker state often preserves intention rather
than truth.

## Recovery Rule

Do not trust `in_progress` beads after a crashed or token-limited session.
Reconstruct reality from evidence:

- recent commits on `main`;
- open and recently merged PRs;
- worker worktrees and their branches;
- CI status for PR heads;
- bead close reasons and comments;
- the current human-facing `/ai/map.md`.

Only then decide whether each bead is complete, still live, or should be
released back to `open`.

## Practical Procedure

1. Read `/ai/map.md` first, but treat it as a starting hypothesis if the prior
   session crashed.
2. Review recent commits on `main` for the period since the last known healthy
   handoff.
3. Compare those commits against `in_progress` beads and open PRs.
4. Close beads only when the work is merged or otherwise verifiably complete.
5. Reopen or release beads whose worker stopped before producing mergeable
   evidence.
6. Preserve useful worker patches by copying them into the worker's own
   worktree or branch, never by accidentally applying them in the mayor
   checkout.
7. Update `/ai/map.md` with the cleaned truth, especially what needs the human
   now.

## Boundaries

The mayor checkout is the control tower. Do not let recovery work trample a
human edit in that checkout. In this project, Mike often has `README.md` open
and dirty during mayor work; treat that as user-owned unless explicitly told
otherwise.

Workers may open PRs, but the mayor owns PR merge decisions and bead
maintenance. A recovery pass should therefore end by normalising bead state,
not by leaving stale `in_progress` markers as archaeological evidence.
