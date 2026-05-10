# The Mayor Method — how I work with AI

## Why this exists

The claim that [re-frame2](https://github.com/day8/re-frame2) is "AI first" has met with skepticism, frustration, disbelief, and in one case anger. This document is my response: a write-up of the method that produced it. The proof of the method is the repository it shipped — see the Outcome section at the bottom. Read the method, look at the work, decide for yourself.

## The method

This is the workflow I use for non-trivial AI-assisted projects.

The method is derived from a deep dive into [gastown's approach][gastown]. I don't use gastown directly, but the shape of this workflow — the orchestrator role, the spec-first discipline, the dispatched workers — comes directly from studying theirs. Credit lands there; any awkwardness in this write-up is mine.

I borrow gastown's term for the orchestrator role: **the mayor** — a figure who orchestrates without doing the work, has overview without losing it, and makes the decisions that keep the city running. That's the role one Claude session plays in my setup.

The short version is at the bottom; here's the long-form first.

[gastown]: https://github.com/gastownhall/gastown

## Setup

1. **Get a [Claude Max plan][claude-max].** This method dispatches a lot of background agents. On per-token API pricing the costs spiral fast — a subscription is the only sane way to run it. The 5x tier is a reasonable starting point; I run two 20x plans — one for personal projects, one for work.

2. **Open a Claude session and paste it the setup prompt at the bottom of this doc** (in the [TL;DR](#tldr) section). That session becomes your **mayor** — it installs [beads][beads], writes the standing rules into `CLAUDE.md`, and from then on it's the only session you talk to directly.

That's the entire setup.

[claude-max]: https://www.anthropic.com/pricing
[beads]: https://github.com/gastownhall/beads

## What the mayor does

The mayor has one job: stay oriented across the whole project. It does this by **not** doing the work itself.

The mayor talks to you. Reads context. Dispatches background agents. Surfaces decisions for you to make. Updates the map (more on that below). Closes out PRs.

The mayor does **not** write code in the foreground, run long test suites, do open-ended exploration, or chew through implementation problems. Those are exactly the activities that burn context windows. If the mayor does them, it stops being able to brief you on the wider project.

Background agents do the heavy lifting. They get a tight, complete prompt. They burn their context window on one task. They report back. They are disposable; the mayor is not.

## Specs are the work

For any AI work, the spec/prompt is everything. Quality of output ≈ quality of spec. There's no exception to this.

So the workflow inverts the conventional "write code → debug → maybe document" loop:

1. Iterate the spec until it's right.
2. Generate the code.
3. If something's wrong: don't iterate the code — iterate the spec, regenerate.

You will spend most of your time on specs. Code becomes a by-product.

## My rule: if the AI makes a mistake, that's on me

If the agent produces wrong code, the cause is almost always upstream in the spec I wrote — not a flaw in the model's execution. So I treat every AI mistake as evidence that my spec was incomplete, ambiguous, or contradictory. The fix is to go back to the spec and tighten it, not to argue with the agent or hand-patch the output.

This rule does two useful things. It keeps me focused on the leverage point (the spec) instead of the visible-but-ineffective lever (the code). And it forces honesty about what I actually asked for — which is almost never as clear as it felt at the time.

When you adopt this rule consistently, your specs get sharper fast. Within a few cycles you start writing specs that *predict* the kinds of mistakes a vague spec would have caused, and pre-empt them.

## How to iterate a spec with the mayor

Tell the mayor: *"I want to write a spec for X. Write the file at `/specs/X.md`. We'll iterate on it together."*

Then drive the iteration with prompts like these:

- **Interview me.** *"Don't write the spec yet. Ask me whatever questions you need to write a complete spec. Just ask."*
- **Find gaps.** *"Where is this spec incomplete? What scenarios doesn't it cover?"*
- **Find ambiguity.** *"Where could two readers reasonably interpret this differently? What words am I using that don't mean the same thing to everyone?"*
- **Propose alternatives.** *"What other approaches could solve this? What are their trade-offs?"*
- **State the problem and the solution clearly.** *"Restate the problem in two sentences. Restate your solution in two sentences. If either is hard, the spec isn't ready."*
- **Background audit.** *"Dispatch a background agent to look for reasons the spec might be wrong — bad assumptions, missing prior art, contradictions elsewhere in the repo. Append findings to the end of the doc."*
- **Codebase sweep.** *"Dispatch a background agent to review the codebase looking for anything I might have missed — existing patterns, edge cases, conventions, callers — that the spec should account for. Append findings to the bottom of the spec document."*

Read each response. Push back. Edit the spec. Repeat.

You'll know the spec is ready when:
- The mayor stops finding gaps.
- The background audit returns "no concerns I can find."
- You can read the spec aloud without stumbling.

## On format: RFCs are your friend

If you don't know how to structure a spec, model it on an [IETF RFC][rfc]. Tell the mayor to help you. The pieces that pay off fastest:

- **A Terminology section** that defines every word that could be misread, before it's used.
- **[RFC 2119][rfc-2119] keywords** — `MUST`, `SHOULD`, `MAY`, `MUST NOT`, `SHOULD NOT` — used consistently throughout. They look pedantic. They eliminate entire classes of ambiguity.
- **A Considerations section** at the end (security, backwards compatibility, performance, edge cases). It forces you to think about what could go wrong before the implementation does.

Right now, spec discipline is harder for me than code discipline. I'm learning.

[rfc]: https://www.rfc-editor.org/rfc/rfc7322
[rfc-2119]: https://www.rfc-editor.org/rfc/rfc2119

## Only then: implement

Once the spec is good, you say:

> *"Create beads to implement what we've speced. Action them with background agents working on a branch."*

The mayor files beads, dispatches background agents, watches them complete, surfaces results.

Background agents will often find new things that need work — ambiguities, contradictions, missing cases. They file new beads when this happens. Sometimes the right thing is to fix it inline; sometimes the right thing is to bounce it back to you. Both happen often. The system is working as intended.

## The map

Ask the mayor:

> *"Maintain a document — `/spec/<project>-map.md` or similar — that summarises and categorises every open bead. Update it on every signal: bead filed, bead dispatched, PR merged, decision made. I'll keep this file open in my editor at all times."*

This is your **map**. It's not a status report. It's a navigation tool.

When you sit down at the keyboard, you read the map first. It tells you:
- What's in flight
- What's blocked, on what
- What's waiting on a decision from you
- What's ready to dispatch
- What just landed

The map is what makes the whole method work. Without it, you lose track of what's happening in 15 parallel workstreams. With it, you re-orient in 30 seconds.

When the map shows a decision-pending bead, ask the mayor:

> *"Tell me more about bead rf2-Xhss. Explain my options."*

The mayor walks you through. You decide. The mayor records the decision as standing guidance in `CLAUDE.md` (so future sessions and future agents inherit it), files any follow-up beads, dispatches anything ready to dispatch, and updates the map.

You move on. The work flows.

## Talking to the mayor

The mayor is a pull interface, not a push one. You ask; it answers. You decide; it executes.

Useful prompts:

- *"Status?"* — current state across all in-flight work.
- *"Dispatch what's ready."*
- *"Tell me about bead X. What are my options?"*
- *"What decisions are blocking progress?"*
- *"Pull main; merge any PRs that are ready; update the map."*

The mayor should never silently start doing work itself. It should say: *"Here's what I'd dispatch a background agent to do — should I?"* If your mayor wanders off and starts coding, redirect it. The mayor that codes loses context; the mayor that orchestrates doesn't.

## Cross-review with another model

I use Codex (OpenAI) for second-opinion reviews. The prompt is:

> *"Review XYZ. For each actionable finding, file a bead — but phrase it as a suggestion to be considered, not a command."*

That gives me a second opinion without letting the second opinion overwrite the first. Claude — the model I trust most for *deciding* in this codebase — gets the suggestions as input. It weighs them and decides.

The point isn't that the other model is wrong. The point is: the model you trust most for decisions is the one whose decisions land. Other models contribute findings, not decisions.

## Review Points 

Every now and again, at certain checkpoints, I put this to the mayor:

```
For this repo, spawn agents to do each of the following independently …
  Review with an eye to efficiency (but not at the expense of clarity)
  Review for completeness
  Review for correctness
  Review for clarity and simplicity
  Review for Best Practice
  Review with an eye to test coverage and rigour
  Review comments and explanation. 
  Review for documentation updates, including READMEs and changelogs. 
  Review for backwards compatibility 

Create beads for each actionable observation. Action beads using background workers.
```

Using multiple lenses is a nice way to flush out problems.

Often the first line of that prompt has narrower scope:
```
Regarding the recent commits (the body of work recently undertaken): 
```

The beads database is invaluable to the mayor AND the background worker agents doing the review. 

## What this gives you

After a few weeks of working this way:

- The mayor knows your project. It remembers what you decided, what you considered, what you flagged for later. It can brief you in 30 seconds on 15 in-flight workstreams.
- Background agents do the heavy lifting. They burn context windows; the mayor doesn't.
- The map tells you where to spend your next 30 minutes.
- Specs accumulate as the project's structural memory. When you onboard someone — human or AI — they read the specs.
- You write less code by hand than you'd expect. You write a lot more specs.


## Outcome

In the last 5 days, I wrote 60K lines of code/specs/tests/examples/adapters — see this repo. I challenge you to find slop. I don't do that every week, obviously, but that's what's possible. And it can be utterly exhilarating. I've wanted to do this project for 10 years.  I almost wept with joy at the beauty of [this state machine](https://github.com/day8/re-frame2/blob/main/spec/Pattern-WebSocket.md#worked-example--connection-machine):  


## TL;DR

1. Install beads.
2. Run one Claude session as your **mayor**. Keep it open. It orchestrates, never does work directly.
3. **Specs are the work.** Iterate the spec hard. Get the mayor to interview you, find gaps, name ambiguities, propose alternatives, dispatch a background audit. Live in `/specs/`. **When the AI makes a mistake, that's on me for not getting the spec right.**
4. Only when the spec is right: *"Create beads to implement this. Action them with background agents on a branch."*
5. Maintain a **map document** in `/spec/`. Keep it open. It's your navigation tool. Decisions surface there; you make them; the mayor records them in `CLAUDE.md`.
6. Talk to the mayor in pull style: *"Tell me about bead X. What are my options?"*
7. Use a second model (Codex, etc.) for cross-review. Have it file beads as **suggestions**, not commands.

**Or — paste this single prompt to a Claude session and it does most of the setup for you. The session you paste it into becomes the mayor.**

> *"I'm setting up the 'mayor method' on this project. The method has two roles: ONE Claude session orchestrates and stays oriented across the whole project — the **mayor** — and MANY background-agent sessions are dispatched to do the actual work. The mayor talks to me and dispatches; the workers execute. **You are the mayor.***
>
> *Step 1 — install beads. Read the install docs at https://github.com/gastownhall/beads/blob/main/docs/INSTALLING.md, run the CLI install (Homebrew or curl-pipe-bash), then `bd init` in the project root, then `bd setup claude` to wire up the SessionStart / PreCompact hooks. Verify with `bd ready`.*
>
> *Step 2 — update `CLAUDE.md` (create it if absent) to add the following standing rules. These must apply to every future session — both the mayor and any background agent — not just this conversation:*
>
> *- Maintain a `project-map.md` at the repo root that summarises and categorises every open bead. Update it on every signal — bead filed, bead dispatched, PR merged, decision made.*
> *- Action any open bead where the direction is already set and no operator input is required: dispatch it to a background agent on its own branch.*
> *- After every PR merge, run `git pull --ff-only` so the local main stays current.*
> *- When dispatching multiple beads at once, sequence them to minimise merge conflicts: beads touching the same hot-zone files run sequentially, not in parallel; beads on isolated surfaces (single-artefact dirs, new files, test-only dirs) can run in parallel.*
> *- When writing or refining spec documents, human understanding comes first — but where appropriate, use [IETF RFC](https://www.rfc-editor.org/rfc/rfc7322) structure and [RFC 2119](https://www.rfc-editor.org/rfc/rfc2119) keywords (`MUST`, `SHOULD`, `MAY`, `MUST NOT`, `SHOULD NOT`) for normative passages that need to be unambiguous.*
>
> *When setup is complete, confirm by saying 'I am the mayor' and report what you did."*

That's the method.
