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

> Review XXX (recent check-ins? repo security? design?). For each actionable issue, file or propose a bead as a
> suggestion. Do not implement. Do not override existing decisions. Frame the bead you create as a suggestion

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
> long is fine. Itemise it in the README.

**Second**, ask the mayor to spawn independent reviewers against recent
commits:

> Regarding the recent commits, spawn agents to review independently for:
> - identify hot spots for performance, and see if they can be improved, but not at the expense of clarity;
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
> surface area for potential actioning. 

Different lenses find different issues.

## Standing prompts

The cron prompts I register with the scheduler are defined in
[`bootstrap.md`](./bootstrap.md). Five `/loop` blocks: bead dispatch,
clustering review, worktree hygiene, PR merge, and dashboard.md upkeep. Each
carries its own operating manual inline (short-circuit rules,
phase-transition behaviour, `--admin` discipline, the Windows-worktree
merge trap recovery sequence). Register them once with your local
scheduler; let the cadence carry the loop.



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
