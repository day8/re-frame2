# The Mayor Method

Most people use AI coding tools as if the chat window were the whole team.

They ask one session to design the feature, read the repo, write the patch, debug
the tests, do some investigation, remember the decisions, open the PR, review the
PR, and then somehow still know what happened four hours later. This works for toy
tasks and demos. It falls apart on real projects, for the utterly boring reason
that one context window is not a project-management system.

The Mayor Method is the workflow I use for non-trivial AI-assisted work.

## Inspiration

The inspiration is [Gastown](https://github.com/gastownhall/gastown). I do not use
Gastown directly, but the shape came from studying it. Credit lands there.

## TLDR

Prompt engineering and context management are still the keys.

- One long-lived AI session is the **mayor**.
- Many short-lived AI sessions are **workers**.
- [Beads](https://github.com/gastownhall/beads) tracks the work.
- Prompts are treated very seriously.
- Git worktrees isolate workers.
- Chat and the tracker hold the calls waiting on me.
- I make the important calls.

The rest is good discipline.

## The mayor does not code

The mayor's job is to stay oriented and to coordinate.

It talks to you. It knows you, your processes, and your goals. It files tracker
items. It dispatches background workers. It reviews their output. It merges PRs
when CI is green. It records decisions.

It must **not** burn its context window implementing features.

This is the part everyone struggles with, because watching the mayor code feels
productive. It is like asking the air-traffic controller to leave the tower and
help unload bags. For five minutes, sure, a few bags move. Then the planes start
doing interesting things.

Workers do the work. They get a tight brief, a worktree, and one bounded task.
They spend their context window on that task, report back, and become disposable.
The mayor remains.

If your mayor checkout has a pre-commit hook that refuses commits to worker-owned
paths, that hook will fire on the mayor's own "quick fix". That is the hook
working. File the item with the fix written out, and dispatch it.

## Prompts are the work

An AI implementation is only as good as the prompt behind it. So do not leave the
prompt in chat. Put it in `ai/prompts/` and iterate on it.

The workflow is:

1. Write the prompt.
2. Stress-test the prompt.
3. Fix the prompt.
4. Fix the prompt some more.
5. Turn the prompt into a tracker item.
6. Get a background agent to action the item.

If the AI does the wrong thing, that's on you.

You are dealing with a 12-year-old savant. It can do a staggeringly good job if it
is given the right guidance. If it does the wrong thing, you didn't get the
guidance right.

### How to write a prompt with the mayor

Start with:

> I want to write an RFC-grade implementation prompt for X. Create
> `ai/prompts/X.md`. Do not implement anything yet. Interview me until the problem
> is crystal clear.

Then make the mayor work:

- Ask it where the prompt is ambiguous.
- Ask it which cases are missing.
- Ask it what the repo already does in nearby areas.
- Ask it what could go wrong.
- Ask it to restate the problem in two sentences.

A terminology section is usually worth it. So is a list of in-scope and
out-of-scope changes.

The prompt is ready when a worker can read it cold and know what to do, and when
you can read it aloud without internally adding "...well, obviously I meant..."
after every second paragraph.

## The `ai` directory

Keep AI working material out of the product tree:

- `ai/prompts/` — durable AI instructions: implementation, decision, review.
- `ai/findings/` — audits, research notes, design drafts, second opinions.
  **Gitignored.** Never commit one. Convert actionable findings into tracker items,
  spec, or docs.
- `ai/extended-context/` — durable project context not obvious from the code, kept
  locally. **Gitignored**, so it is not available from a clean clone. The mayor
  consults it on bootstrap and grows it on retrospectives.
- `ai/decisions/` — one file per decision, written only when you ask for a durable
  record of a specific call. Ordinary holds awaiting you live in chat and on their
  tracker items, not in an index file.

One warning about that tree, learned the hard way. Because git cannot see it, a
worker can finish a piece of analysis, write it there, and cite it by a path no
maintainer has. Two audits were lost that way here, and a mayor re-ran an entire
three-design programme in one day for want of looking there first. Working notes
stay local; the *conclusion* gets promoted into whatever tracked record already
owns the surface.

## The tracker is the work queue

Every real piece of work becomes a tracker item.

A good item says:

- what is wrong or missing;
- where to look, with `file:line` where possible;
- what should change — a sketch is fine, a fix is great;
- what counts as done;
- what tests or checks matter;
- what not to touch.

Workers do not get vibes. They get items. Vague items produce vague PRs.

Beads also has memories: `bd remember` stores project-shaped insights that outlive
the current mayor, and `bd memories <topic>` retrieves them. Use them for the
operations knowledge a fresh mayor would otherwise rediscover at 2 a.m.

## Briefs are where the errors are

This is the finding I would most want to have had at the start.

Across one long session, workers corrected the mayor's stated premises about twenty
times. Not on style — on facts the mayor asserted and had not checked. Counts that
had drifted between filing and dispatch. A file asserted to carry a claim it did not
carry. A defect described as silent whose error was raised loudly at source and
discarded one layer up.

Workers caught every one, and nothing reached the trunk. But each cost a full worker
cycle, and together they cost more than any worker-side failure in the same period.

So guarding the mayor's context is necessary and not sufficient. The mayor's scarce
output is an accurate brief. Checking a claim while writing takes seconds. Discovering
it was wrong takes a worker's whole context.

Put this sentence in every brief:

> This brief's premises are claims, not findings. Check each at source before acting
> on it. A verified "already fixed" or "the premise does not hold" is a complete and
> good deliverable.

It caught more errors than anything else in the method. Without it, a worker handed a
task that should not exist will invent one that does.

## You still own the hard calls

The mayor can explain options. Workers can explore options. Another model can review
options.

But policy calls, product calls, taste calls, and "what kind of project is this trying
to be?" calls belong to me.

The mayor should surface those clearly:

> Item X is blocked on a design choice. Option A is smaller. Option B is cleaner.
> Option C is safer but changes the public surface. My recommendation is B because ...

Then I decide, and the mayor records the decision on the item.

That recording step is not paperwork. It is how future agents inherit your judgment
instead of rediscovering the same argument at 2 a.m.

One correction worth making early. A mayor can over-deliver on deference: one was
holding nine items "for the operator" when three were genuinely operator calls, and the
rest were already answered by the project's stated stance. Ask which items on that list
actually need you.

## PRs are the gate

Workers may open PRs. The mayor merges them.

Before merging, the mayor checks that the diff matches the item, that scope did not
sprawl, that failure output stays actionable, and that CI is green — on all five clauses
of the merge criterion, which are set out in [`loops.md`](loops.md). "No failures" is not
one of the clauses and is not green: an empty rollup reports no failures too.

There is no bypass. An administrative override is for the host's own mergeability
recompute lag, which is not a check at all, and only once every clause is already met.

After merge, the mayor pulls and verifies the tree rather than the line git printed.
Then it closes the item with a concrete reason.

This is the difference between "a lot of agents did things" and "the project advanced."

## Reopened items are usually the system working

If your process audits merged changes, expect closed items to reappear. Twelve did in one
session, and every one was a legitimate audit reopening the item that owned a residual.

The rule is short: **read the item's notes before re-closing anything.** The instinct to
re-close a "reverted" item destroys a real finding and looks like tidiness.

Expect chains. A fix lands, its audit reopens the item for a second carrier, that fix's
audit reopens for a third. Two or three rounds converge.

And the audit reads your merges, not just the workers' diffs. One repair introduced a
false claim about how a tool resolves its paths. The mayor merged it believing it correct;
the audit of that merge caught it. If you have an audit step, it is checking you as well.

## Cross-review

Use another model for second opinions. Do not let it become a second mayor.

The useful prompt is:

> Review XXX — recent check-ins, repo security, design. For each actionable issue, file or
> propose a tracker item as a suggestion. Do not implement. Do not override existing
> decisions.

Different models notice different things. That is useful. But one authority must decide
what lands, or the project becomes a committee made of weather.

## Checkpoints

Every so often, stop and run two reviews.

**First**, ask the mayor for a retrospective:

> What information not already recorded in the code or the tracker would have been helpful
> to have had before we started this session? What's not obvious from the code alone?
> Capture it in `ai/extended-context/` if it is not already there. Do not create a
> duplicate. Structure it like an AI skill, with front matter and then a body. Give the file
> an expressive name; long is fine. Itemise it in the README.

**Second**, ask the mayor to spawn independent reviewers against recent commits:

> Regarding the recent commits, spawn agents to review independently for:
> - performance hot spots, but not at the expense of clarity;
> - completeness;
> - correctness;
> - clarity and simplicity;
> - best practice;
> - test coverage and rigour;
> - comments and explanation;
> - documentation updates, including READMEs and changelogs;
> - backwards compatibility, where it matters.
>
> Create tracker items for each actionable observation. Then cluster them by surface area.

Different lenses find different issues.

## Ready to run it

If you want to try the method, the pasteable prompt is [`bootstrap.md`](bootstrap.md). Paste
it into a fresh AI session as your opening message; the mayor takes it from there.

Three siblings carry the operational detail:

- [`bootstrap.md`](bootstrap.md) — the opening prompt, and the hard-won list of things that
  bite.
- [`loops.md`](loops.md) — the five standing loops, and the merge criterion in full.
- [`dispatch-prompt-template.md`](dispatch-prompt-template.md) — the worker-prompt shapes, the
  worktree-boundary block, and the gate-mechanics block. The last two go into every editing
  dispatch verbatim.

None of those three is specific to this repository. The concrete values they need — your gate
command, your tracker's commands, your hot-zone file list — belong in your own project's
agent-instructions file.

## Warnings

You'll need to be in yolo mode. Sandbox appropriately.

This is not free. You will spend tokens, a lot of them, and you'll need a Claude Max plan, 5x
or better.

Also: this is a single-player method. Teams need more protocol, more explicit ownership, and
probably less cowboy orchestration.

But for one person trying to move a serious project quickly without losing the plot, it works.

The mayor does not make the project good. You still have to do that. It just keeps the city
from burning down while the workers build it.
