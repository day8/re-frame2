---
description: Mayor Loop — posture reread + reassert, then the stranded sweep (≈60m cadence). One-line confirmation unless drift.
---
MAYOR LOOP (posture reread + reassert, then the stranded sweep).

**`docs/the-mayor-method/loops.md` §4 is the body of BOTH halves** — why a stance is never
summarised, why the in-progress list is empty by construction, and why each derived liveness
signal misleads. It is the one place that reasoning lives; it is not restated here. **This file
is the single source of the STANCE block, and pins the concrete commands.** Also re-read
`docs/the-mayor-method/bootstrap.md` and `dispatch-prompt-template.md`.

## The stance

**Paste this block VERBATIM into every dispatch preamble. Do not summarise it.**

> **PROJECT STANCE** — pre-alpha, aiming at a masterpiece. ELEGANCE, POWER, CLARITY, CORRECTNESS, GOOD PRACTICE. But NOT over-engineering and NOT gold-plating. We TRUST THE PROGRAMMER. The goal is high productivity for them and the AI they use: a library with excellent ergonomics, a minimal API, excellent tools, low friction. No back-compat shims; performance and completeness serve the lenses rather than outranking them. We do not need to litigate every last fine detail or drown in minutiae — reject over-engineering and nag-diagnostics at triage, and CLOSE minutiae rather than actioning it. An audit finding is a CLAIM, not automatically work; stale, moot and over-engineered remedies are rejected rather than dispatched. A source-located REFUSAL is an acceptable and possibly correct deliverable.

## Voice

The operator wants GOV.UK (GDS) house style in CHAT replies — plain English, short sentences,
active voice, one idea per sentence, front-loaded, numerals for all numbers including 1 to 9,
no latinisms (write "for example", not "e.g."), no metaphor, and bead ids, paths and commands
kept exact. CHAT ONLY: bead text, PR bodies and documents keep their own register.

**Check that at source before "correcting" it — it has been filed as a bug against this line
twice, and both filings read a superseded record.** `bd memories "gds"` returns the record that
governs, `operator-wants-govuk-gds-house-style` (2026-08-15). It REFINES the earlier
ASD-STE100 records rather than reversing them, so there is no contradiction to resolve, and it
is the operator restating a preference, which is exactly how the earlier record said the
question could be reopened. `feedback_voice_tight_not_terse` (2026-07-13) still governs DOCS
prose. If the operator's own voice line in the scheduler prompt disagrees with this paragraph,
the prompt is the copy to change — `loops.md` §4 deliberately leaves naming the voice to here.

## Reassert

One line: orchestration-not-implementation — the mayor does not code; guard context, dispatch
bounded work to background workers in their own worktrees, edit directly only for tiny fixes
and emergency cleanup.

If recent dispatches have drifted — mayor coding, missing worktree-boundary block, stance
absent or PARAPHRASED rather than pasted, `--admin` misuse, minutiae actioned instead of
closed, an audit finding dispatched without its premise checked at source — flag it and name
the dispatch. Otherwise "posture holding" is enough.

## The stranded sweep — this loop's second half

**Start from the worktrees, not from the tracker.** Dispatches here do not claim their beads,
so `bd list --status in_progress --limit 0 --flat --no-pager` is routinely EMPTY while workers
run, and a "0 in progress, sweep clean" report is not evidence of anything.

```bash
git worktree list
find <worktree> -newermt '20 minutes ago' -type f | grep -v '/\.git/' | wc -l
```

Read the newest filenames, not just the count: paths under `.cpcache`, `.scratch` or
`implementation/out` mean a gate is running, a tracked source or doc file means the worker is
editing, near-zero means a strand. Cross-check processes started recently (`java`, `node`).
Where a bead IS marked in-progress, read it too.

**BEFORE STEP 1, READ THE HARNESS'S OWN RUNNING-AGENT REPORT.** It names each live agent with
its current action — *"Polling `gate-fastpr-ymlparse-cb7hs-3.log`"*, *"Compiling browser-test
with shadow-cljs"* — and arrives unprompted, observing the AGENT where every test below
observes its OUTPUT. Where it is present it settles liveness and you stop; three live workers
were once judged stranded on three different derived signatures while it was on screen for all
three. Step 1 is the FALLBACK for when it is absent — after a `/clear`, or for a worker someone
else dispatched.

1. **Has the branch TIP SHA moved since last tick?** `git rev-parse worker/<name>`, against the
   SHA you recorded. **Never a commit count, never `%ar`.** Read the LOCAL ref, not
   `git ls-remote`: every worker worktree here is a LINKED worktree sharing one `.git`, so the
   local ref moves the instant a worker commits while `origin` moves only when it pushes — the
   remote lags, and it lags in the direction that reads as death.
   `git log -1 --format='%aI %cI' <ref>` gives both clocks. **`%cI` for LIVENESS; `%aI` when you
   DATE a commit in prose** — `loops.md` §4 owns why, and this loop once "corrected" a right
   date into a wrong one and had to retract (rf2-r0hq5).
2. **Is there a live task to message?** Message before you redispatch. `SendMessage`'s response
   shape answers it: *"queued for delivery"* = alive; *"had no active task; resumed from
   transcript"* = idle.
3. **Only when there is no live task AND no tip movement:** push whatever commits the branch
   carries, then `bd update <id> --status open` with a note saying what was found and what was
   salvaged, and redispatch. **Read the TERMINATION REASON first** — when it names a quota or
   rate limit with a reset time, redispatch is not available and every attempt spends another
   call. Leave the bead open with the reason and the reset time, and STOP DISPATCHING. Merging
   is unaffected and `gh pr merge` spends no agent quota, so the order is salvage, then merge
   everything green, then stop.

**A MOVED SHA says alive; a STILL SHA authorises nothing on its own.** Reaping is
`.claude/commands/mayor-hygiene.md`'s question and its test is still the agent's own report.
**Never build a commit from someone else's uncommitted work.**
