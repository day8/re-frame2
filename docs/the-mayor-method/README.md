# The Mayor Method

Most people use AI coding tools as if the chat window were the whole team.

They ask one session to design the feature, read the repo, write the patch,
debug the tests, do some investigation, remember the decisions, open the PR, review the PR, and then
somehow still know what happened four hours later. This works for toy tasks
and demos. It falls apart on real projects, for the utterly boring reason that one
context window is not a project-management system.

The Mayor Method is the workflow I use for non-trivial AI-assisted work. 

## Inspiration 

The inspiration is [Gastown](https://github.com/gastownhall/gastown). I do not
use Gastown directly, but the shape came from studying it. Credit lands there.

## TLDR

Prompt engineering and context management are still the keys. 

- One long-lived AI session is the **mayor**.
- Many short-lived AI sessions are **workers**.
- [Beads](https://github.com/gastownhall/beads) are used to work.
- Prompts are treated very seriously.
- Git worktrees isolate workers.
- `/ai/dashboard.md` keeps me oriented.
- I make the important calls.

The rest is good discipline.


## The mayor does not code

The mayor's job is to stay oriented and to coordinate.

It talks to you. It knows you, your processes, and your goals. It files beads.
It dispatches background workers. It reviews their output. It merges PRs when
CI is green. It records decisions in beads.

I guard the mayor's context like a jealous lover. It must **not** burn its
context window implementing features.

This is the part everyone struggles with, because watching the mayor code
feels productive. It is not. It is like asking the air-traffic controller to
leave the tower and help unload bags. For five minutes, sure, a few bags
move. Then the planes start doing interesting things.

Workers do the work. They get a tight brief, a worktree, and one bounded
task. They spend their context window on that task, report back, and become
disposable. The mayor remains.

## Set the project's stance once

Every project has a stance. Pre-alpha, production-stable, refactor-only,
greenfield, perf-critical, hostile-input-paranoid. Whatever it is, *every
worker prompt* must carry it in the preamble.

Without an explicit stance, workers default to "preserve all behaviour just
in case", which adds shims, aliases, deprecation paths, and TODOs. Cruft
accumulates faster than you can review it.

With it, workers reach for the elegant cut. Live evidence: telling workers
"pre-alpha, no back-compat" produced a string of clean deletions of dead
APIs that I'd have spent days arguing into otherwise.

Pick your stance, write it down, and inject it into every dispatch.

## Prompts are the work

An AI implementation is only as good as the prompt behind it. So do not
leave the prompt in chat. Put it in `/ai/prompts/` and iterate on it.

Good use of AIs is all about prompt engineering and context management.
Nothing much has changed there for two years.

The workflow is:

1. Write the prompt.
2. Stress-test the prompt.
3. Fix the prompt.
4. Fix the prompt some more.
5. Turn the prompt into a bead.
6. Get a background agent to action the bead.

If the AI does the wrong thing, that's on you.

You are dealing with a 12-year-old savant. It can do a staggeringly good job
if it is given the right guidance. If it does the wrong thing, you didn't
get the guidance right.

## How to write a prompt with the mayor

Put implementation prompts under `/ai/prompts/`. Some will be spec-like.
The point is that they are durable instructions for an AI, not chat exhaust.

Start with:

> I want to write an RFC-grade implementation prompt for X. Create
> `/ai/prompts/X.md`. Do not implement anything yet. Interview me until the
> problem is crystal clear.

Then make the mayor work:

- Ask it where the prompt is ambiguous.
- Ask it which cases are missing.
- Ask it what the repo already does in nearby areas.
- Ask it what could go wrong.
- Ask it to restate the problem in two sentences.

A terminology section is usually worth it. So is a list of in-scope and
out-of-scope changes.

The prompt is ready when a worker can read it cold and know what to do, and
when you can read it aloud without internally adding "...well, obviously I
meant..." after every second paragraph.

## The `/ai` directory

Keep AI working material out of the product tree:

- `/ai/prompts/` — durable AI instructions: implementation, decision, review.
- `/ai/findings/` — audits, research notes, design drafts, second opinions.
  **Gitignored.** Never commit one. Convert actionable findings into beads,
  spec, or docs.
- `/ai/extended-context/` — durable project context not obvious from code.
  The mayor consults it on bootstrap and contributes on retrospectives.
- `/ai/dashboard.md` — my dashboard.

## Beads are the work queue

Use [beads](https://github.com/gastownhall/beads) for task tracking. Every
real piece of work becomes a bead.

A good bead says:

- what is wrong or missing;
- where to look (file:line where possible);
- what should change (a sketch is fine; a fix is great);
- what counts as done;
- what tests or checks matter;
- what not to touch.

Workers do not get vibes. They get beads. Vague beads produce vague PRs.

Beads also have memories: `bd remember` stores project-shaped insights that
outlive the current mayor; `bd memories <topic>` retrieves them. Use them
for the operations knowledge a fresh mayor would otherwise rediscover at
2 a.m.

## Workers need worktrees

Each worker gets its own git worktree, and the mayor's checkout is the
control tower.

The reliable pattern: shell commands use `cd` inside the assigned worktree;
edit-tool paths explicitly walk into the assigned worktree (relative paths
without the worktree prefix can leak into the mayor checkout because
patch-style edit tools have no `workdir`).

The full mandatory worker prompt block — including the path-resolution
guard, the per-edit toplevel check, and the post-edit verification — lives
in [`dispatch-prompt-template.md`](./dispatch-prompt-template.md) §"Worktree
boundary". Paste it verbatim into every editing-worker dispatch.

## Hot-zone files: sequential, never parallel

Some files are race magnets — top-level build config, central spec docs,
shared registries, public-API tables. Two workers editing those at once is
two rebases later.

Maintain a hot-zone list. Forbid parallel dispatch on hot-zone files.
Inside one cluster PR, sequence hot-file edits across commits so the diff
per commit reads clean.

## Tell every worker who else is in the room

Worktrees prevent file leakage. They do not coordinate which *surface* a
worker is writing.

Every dispatch must enumerate the other in-flight workers and their
surfaces:

> Concurrent workers on disjoint surfaces:
> - <bead-id> on `<artefact-path-A>`
> - <bead-id> on `<file-path-B>`
> - <bead-id> on `<hot-zone-file>` (hot-zone — sequential)
> None overlap your surface (`<your-artefact-path>`).

The receiving worker pattern-matches for collisions during implementation
and stops cleanly if it finds one.

## Cluster when surfaces overlap

When 3+ open beads target the same surface, dispatch ONE worker for the
cluster. One PR, commits ordered so hot files are sequenced inside. Aim
for 8-12 beads per cluster.

Reserve solo dispatches for: P0/P1 correctness, structural refactors over
~250 LoC, decision-resolved work where the decision must stay auditable in
git history, and cross-cutting changes that span surfaces.

Why: hot-file sequencing inside one PR means zero rebases instead of N-1
rebases with parallel solo dispatches. Cross-bead context often surfaces
unifications the bead system missed. One cluster also means one review pass.

Inside a cluster commit ordering matters: smallest+safest first, biggest
correctness fix last. If the P1 fix breaks something, the small refactors
land cleanly first.

Claim the next bead before each commit (`bd update <bead-id> --claim
--status=in_progress`). That keeps bd state and commit history mirroring
each other; a stalled cluster leaves a clean partial trail instead of a
smear.

## The audit-then-cluster loop

This is the highest-leverage shape the methodology produces.

1. Dispatch an audit worker on a surface. Audits are read-only — they
   produce a findings doc and a fistful of file:line-precise follow-on
   beads with severity tags.
2. The follow-on beads naturally cluster on the audited surface.
3. Dispatch a cluster worker that ships them as one PR.

Quality compounds. Audits catch real bugs (and over time, audits of
post-cluster state catch bugs the cluster itself introduced — round 2 is
worth running).

Audit workers must write the findings doc *before* filing the first bead.
Watchdog timeouts will otherwise lose all the analysis.

## You still own the hard calls

The mayor can explain options. Workers can explore options. Another model
can review options.

But policy calls, product calls, taste calls, and "what kind of project
is this trying to be?" calls belong to me.

The mayor should surface those decisions clearly:

> Bead X is blocked on a design choice. Option A is smaller. Option B is
> cleaner. Option C is safer but changes the public surface. My
> recommendation is B because ...

Then I decide, and the mayor records the decision in the bead.

That recording step is not paperwork. It is how future agents inherit your
judgment instead of rediscovering the same argument at 2 a.m.

## PRs are the gate

Workers may open PRs. The mayor merges them.

Before merging, the mayor checks:

- the diff matches the bead;
- scope did not sprawl;
- failure output remains actionable;
- tests or CI are green;
- bead state will be updated after merge.

After merge, the mayor pulls main, closes the bead with a concrete reason,
and updates `/ai/dashboard.md`.

This is the difference between "a lot of agents did things" and "the
project advanced."

**Merge trap:** `gh pr merge --delete-branch` fails when the worker
worktree still holds the branch. Use `gh pr merge --squash --admin` (no
`--delete-branch`), then `git push origin --delete <branch>`, then leave
local cleanup until the worker is closed and the worktree is verifiably
clean. Full sequence in [`bootstrap.md`](./bootstrap.md) (the PR-merge
`/loop` block).

## Cross-review

Use another model for second opinions. Do not let it become a second
mayor.

The useful prompt is:

> Review this. For each actionable issue, file or propose a bead as a
> suggestion. Do not implement. Do not override existing decisions.

Different models notice different things. That is useful. But one
authority must decide what lands, or the project becomes a committee made
of weather.

## Checkpoints

Every so often, stop and run two reviews.

**First**, ask the mayor for a retrospective:

> What information not already recorded in the code or beads would have
> been helpful to have had before we started this session? What's not
> obvious from the code alone? Capture that information in a file within
> `/ai/extended-context/` if it is not already present. Ensure you are not
> creating a duplicate. Structure your insight like an AI Skill, with
> front matter and then a body. Give the file a good expressive name;
> long is fine. Itemise it in the README. Also store the punchiest
> insights as `bd remember` entries so a fresh mayor finds them on prime.

**Second**, ask the mayor to spawn independent reviewers against recent
commits:

> Regarding the recent commits, spawn agents to review independently for:
> - performance, but not at the expense of clarity;
> - completeness;
> - correctness;
> - clarity and simplicity;
> - best practice;
> - test coverage and rigour;
> - comments and explanation;
> - documentation updates, including READMEs and changelogs;
> - backwards compatibility, where it matters.
>
> Create beads for each actionable observation. Then cluster beads by
> surface area for potential actioning. Update `/ai/dashboard.md` with a terse
> summary so I see what the results are.

Different lenses find different issues.

## Standing prompts

The cron prompts I register with the scheduler are defined in
[`bootstrap.md`](./bootstrap.md). Five `/loop` blocks: bead dispatch,
clustering review, worktree hygiene, PR merge, and dashboard.md upkeep. Each
carries its own operating manual inline (short-circuit rules,
phase-transition behaviour, `--admin` discipline, the Windows-worktree
merge trap recovery sequence). Register them once with your local
scheduler; let the cadence carry the loop.

## Patterns validated under fire

A long live session — 114 PRs landed in a few hours, ~60 beads closed —
surfaced these techniques. Each saved real time or avoided real waste.

**Check before dispatching.** Before spawning a worker on a bug bead,
grep the codebase for the alleged broken symbol. Often the fix landed
weeks ago and the bead is stale. Close as "verified-duplicate of
PR #NNNN" with the proof trail in the close reason. Cheaper than a
worker, and the audit trail beats an empty PR.

**`--admin` on irrelevant gates.** When a PR's stuck pending check is a
browser sweep for a surface the PR doesn't touch (e.g., a test-only PR
waiting on a feature-area browser gate that exercises code the diff
doesn't change), merge with `--admin`. The gate exists to catch
regressions in code the PR doesn't change. Confirm structural
irrelevance first.

**Aggressive parallel cluster waves** work when the cold backlog has 20+
disjoint actionable beads. The session's biggest unlock was a 5-cluster
dispatch covering 28 beads → 5 PRs in ~30 minutes. Hot-zone files
sequenced inside each PR; zero rebases.

**Disjoint-surface "small-misc" clusters are valid** at the tail of a
drain. The 8–12 sweet spot targets cohesion-rich same-surface work, but
when only 3 small isolated items remain, one PR with 3 commits beats 3
solo dispatches. The binding rule is hot-zone parallelism, not "same
surface".

**Decision-resolved work goes solo, with the decision in the PR body.**
When the operator picks between options (e.g., `:spec/at-boundary` vs
`:spec/strict`), the worker's PR body records the date and the options
considered. The decision becomes auditable in git history. This is the
one case where solo (not cluster) dispatch is the right call — clustering
buries the decision context.

**Hand-roll boilerplate-prone prose.** Pasting Contributor Covenant text
into a CODE_OF_CONDUCT.md tripped a content filter mid-task. Hand-rolling
a brief CoC in the project's voice (a) avoided the filter, and (b) was
more on-brand. Boilerplate is rarely the right answer when the project
has a defined voice.

**Update the map on every signal — not on a clock.** The dashboard
(`ai/dashboard.md`) is how the operator tracks state. Stale data is a navigation
tax. After every PR merge, every worker return, every cluster dispatch,
every decision-resolution — refresh the timestamp and the affected
sections. Don't batch updates.

**Skip the redundant triage agent when steady.** After ~5 consecutive
HOLD verdicts on a quiescent backlog, additional triage-agent dispatches
just confirm what you already know. The cron prompt's short-circuit
(above) handles this — direct-check `bd list` and report HOLD without
spawning research.

**The dispatch engine has two phases.** *Push phase* (cold backlog rich)
runs 4–6 workers in parallel and grinds the backlog down hard. *Decision
phase* (cold backlog drained) sits idle until the operator answers queued
decisions. The transition is sharp — once same-surface clusters are
exhausted, triage rounds will return HOLD until the operator acts. This is
correct, not a stall.

## Ready to run it

If you've read this far and want to actually try the method, the
pasteable prompt is [`bootstrap.md`](./bootstrap.md). It's terse —
deliberately — and it expects you've absorbed the philosophy above
first. Paste it into a fresh AI session as your opening message;
the mayor takes it from there.

Two siblings carry the operational detail you'll need once the mayor
is running:

- [`dispatch-prompt-template.md`](./dispatch-prompt-template.md) — the
  canonical worker-prompt shapes (solo / cluster / audit /
  cluster-reviewer / CI-fix) and the worktree-boundary block that must
  go into every editing dispatch verbatim.
- [`bootstrap.md`](./bootstrap.md) — re-read on cadence; it carries
  the five `/loop` blocks the mayor registers with its scheduler,
  each with its own inline operating manual.

## The mayor can forget

After a while, the mayor forgets. So you remind it. Put the bootstrap
prompt in a file and ask it to schedule a re-read every hour.

## Warnings

You'll need to be in yolo mode. Sandbox appropriately.

This is not free. You will spend tokens, a lot of them, and you'll need
a Claude Max plan, 5x or better.

Also: this is a single-player method. Teams need more protocol, more
explicit ownership, and probably less cowboy orchestration.

But for one person trying to move a serious project quickly without
losing the plot, it works.

The mayor does not make the project good. You still have to do that.

It just keeps the city from burning down while the workers build it.
