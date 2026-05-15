# The Mayor Method

Most people use AI coding tools as if the chat window were the whole team.

They ask one session to design the feature, read the repo, write the patch,
debug the tests, remember the decisions, open the PR, review the PR, and then
somehow still know what happened four hours later. This works for toy tasks
and demos. It falls apart on real projects, for the boring reason that one
context window is not a project-management system.

The Mayor Method is the workflow I use for non-trivial AI-assisted work. It
produced a large amount of re-frame2 in a short burst, but the point is not
speed theater. The point is control.

The inspiration is [Gastown](https://github.com/gastownhall/gastown). I do not
use Gastown directly, but the shape came from studying it. Credit lands there.

The short version:

- One long-lived AI session is the **mayor**.
- Many short-lived AI sessions are **workers**.
- Prompts are treated as serious working artefacts.
- [Beads](https://github.com/gastownhall/beads) track all work.
- Git worktrees isolate workers.
- `/ai/map.md` keeps me oriented.
- I make the important calls.

That is basically it. The rest is discipline.

## The Mayor Does Not Code

The mayor's job is to stay oriented and to coordinate.

It talks to you. It knows you, your processes, and your goals. It files beads.
It dispatches background workers. It reviews their output. It merges PRs when
CI is green. It records decisions in beads.

I guard the mayor's context like a jealous lover. It must **not** burn its
context window implementing features.

This is the part everyone struggles with, because watching the mayor code feels
productive. It is not. It is like asking the air-traffic controller to leave
the tower and help unload bags. For five minutes, sure, a few bags move. Then
the planes start doing interesting things.

Workers do the work. They get a tight brief, a branch or worktree, and one
bounded task. They spend their context window on that task, report back, and
become disposable. The mayor remains.

## Prompts Are the Work

An AI implementation is only as good as the prompt behind it. So do not leave
the prompt in chat. Put it in `/ai/prompts/` and iterate on it.

Good use of AIs is all about prompt engineering and context management. Nothing
much has changed there for two years.

The workflow is:

1. Write the prompt.
2. Stress-test the prompt.
3. Fix the prompt.
4. Fix the prompt some more.
5. Turn the prompt into a bead.
6. Get a background agent to action the bead.

If the AI does the wrong thing, that's on you.

You are dealing with a 12-year-old savant. It can do a staggeringly good job if
it is given the right guidance. If it does the wrong thing, you didn't get
the guidance right.

## How to Write a Prompt With the Mayor

Put implementation prompts under `/ai/prompts/`. Some will be spec-like. The
point is that they are durable instructions for an AI, not chat exhaust.

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
- Ask it to dispatch a worker to audit the prompt from the outside.

Use RFC habits where they help. A terminology section is usually worth it.
RFC 2119 words like `MUST`, `SHOULD`, and `MAY` are ugly and useful. They make
hand-wavy prose less hand-wavy.

The prompt is ready when a worker can read it cold and know what to do, and when
you can read it aloud without internally adding "...well, obviously I meant..."
after every second paragraph.

## The `/ai` Directory

Keep AI working material out of the product tree:

- `/ai/prompts/` contains durable AI instructions: implementation prompts,
  decision prompts, and review prompts.
- `/ai/findings/` contains audits, research notes, design drafts, and
  second-opinion reviews.
- `/ai/extended-context/` contains durable project context that is not obvious
  from the code alone.
- `/ai/map.md` is the human's navigation file.

## Beads Are the Work Queue

Use [beads](https://github.com/gastownhall/beads) for task tracking. Every real
piece of work becomes a bead.

A good bead says:

- what is wrong or missing;
- where to look;
- what should change;
- what counts as done;
- what tests or checks matter;
- what not to touch.

Workers do not get vibes. They get beads.

## Workers Need Worktrees

Each worker gets its own git worktree, and the mayor's checkout is the control
tower.

Worker prompt rule:

> You are working in `PATH_TO_WORKTREE`. Do not edit the mayor checkout. You
> are not alone in this repo. Do not revert unrelated changes. Push your branch
> and report back. Do not merge PRs or close beads; the mayor owns that.

## You Still Own the Hard Calls

The mayor can explain options. Workers can explore options. Another model can
review options.

But policy calls, product calls, taste calls, and "what kind of project is this
trying to be?" calls belong to the human.

The mayor should surface those decisions clearly:

> Bead X is blocked on a design choice. Option A is smaller. Option B is
> cleaner. Option C is safer but changes the public surface. My recommendation
> is B because ...

Then the human decides, and the mayor records the decision in the bead.

That recording step is not paperwork. It is how future agents inherit your
judgment instead of rediscovering the same argument at 2 a.m.

## PRs Are the Gate

Workers may open PRs. The mayor merges them.

Before merging, the mayor checks:

- the diff matches the bead;
- scope did not sprawl;
- failure output remains actionable;
- tests or CI are green;
- bead state will be updated after merge.

After merge, the mayor pulls main, closes the bead with a concrete reason, and
updates `/ai/map.md`.

This is the difference between "a lot of agents did things" and "the project
advanced."

## Cross-Review

Use another model for second opinions. Do not let it become a second mayor.

The useful prompt is:

> Review this. For each actionable issue, file or propose a bead as a
> suggestion. Do not implement. Do not override existing decisions.

Different models notice different things. That is useful. But one authority
must decide what lands, or the project becomes a committee made of weather.

## Checkpoints

Every so often, I stop and run two reviews.

**First**, I ask the mayor to run a retrospective via a prompt like this:

> What information not already recorded in the code or beads would it have been
> helpful for you to have had before we started this session? What's not obvious
> from the code alone? Capture that information in a file within
> `/ai/extended-context/` if it is not already present. Ensure you are not
> creating a duplicate. Structure your insight like an AI Skill, with front
> matter and then a body. Give the file a good expressive name; long is fine.
> Itemise it in the README.

**Second**, I ask the mayor to spawn independent reviewers against the recent commits:

```text
Regarding the recent commits, spawn agents to review independently for:
- performance, but not at the expense of clarity;
- completeness;
- correctness;
- clarity and simplicity;
- best practice;
- test coverage and rigour;
- comments and explanation;
- documentation updates, including READMEs and changelogs;
- backwards compatibility, where it matters.

Create beads for each actionable observation. Action accepted beads using
background workers.
```

Different lenses find different issues.

## Minimal Setup Prompt

Paste this into a fresh AI session at the root of a repo. That session becomes
the mayor.

```text
You are the mayor for this repository.

Your job is orchestration, not implementation. Preserve your context. Do not
write code directly unless the task is tiny or emergency cleanup. Dispatch
bounded work to background workers in their own git worktrees.

Set up and use beads:
- Beads lives at https://github.com/gastownhall/beads.
- Run `bd prime` and follow the repo's bead workflow.
- Track all real work in beads.
- Close beads only after the work is merged or otherwise verifiably complete.
- Record decisions and close reasons concretely.

Maintain `/ai/map.md` for the human, not for yourself:
- Put the timestamp at the top.
- Immediately after the timestamp, put the one-line resume command for this
  session. If running under Codex, use:
  `codex resume <session-id> "Read /ai/map.md and continue from the top."`
  Use the exact session id if available; otherwise use:
  `codex resume --last "Read /ai/map.md and continue from the top."`
  If this is a Claude session, use the equivalent:
  `claude --resume <session-id>`.
- After the resume line, put "What needs the human now": decisions, blockers,
  files they are editing, and anything unsafe to touch.
- Only after that, summarise in-flight work, open PRs, recent merges, cleanup,
  and interesting context.
- Keep it short enough that a returning human can re-orient in 30 seconds.
- Update it whenever beads, PRs, CI, or decisions change.

Use `/ai/prompts/`, `/ai/findings/`, and `/ai/extended-context/`:
- Prompts are durable AI instructions taken seriously.
- Findings are exploratory reports, audits, research notes, and alternatives.
- Extended context is for facts the next session will not infer from code.

When dispatching a worker:
- Create or specify a dedicated git worktree.
- Give the worker one bounded task and a clear write scope.
- Tell it it is not alone in the repo.
- Tell it not to edit the mayor checkout.
- Tell it not to merge PRs or close beads.
- Require tests/checks and a final report with changed files, commands run,
  branch/PR, and risks.

PR rule:
- Workers may open PRs.
- The mayor reviews and merges only after CI is green and scope is correct.
- After merge, pull main, close beads with concrete reasons, update the map,
  commit/push tracker changes.

Human decision rule:
- Surface design/product/security/taste decisions explicitly.
- Explain options and trade-offs.
- Recommend when useful, but let the human decide.
- Record the decision in beads or prompts.

Confirm by saying: "I am the mayor."
```

## The mayor can forget

After a while, the mayor forgets. So you need to remind it. Put the prompt above in
a file and ask it to schedule a reread every hour.

## Warnings

This is not free. You will spend tokens, a lot of them, and you'll need a
Claude Max plan, 5x or better.

Also: this is a single-player method. Teams need more protocol, more explicit
ownership, and probably less cowboy orchestration.

But for one person trying to move a serious project quickly without losing the
plot, it works.

The mayor does not make the project good. You still have to do that.

It just keeps the city from burning down while the workers build it.
