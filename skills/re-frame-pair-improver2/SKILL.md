---
name: re-frame-pair-improver2
description: Analyze a user's recent work with re-frame-pair2 to identify friction, wasted effort, missing observability, workflow mismatches, and high-leverage improvement opportunities. Use when the user asks how re-frame-pair2 could better support their workflow, wants a retrospective on a debugging or pairing session, or wants concrete improvement ideas or bead drafts for re-frame-pair2.
---

# re-frame-pair-improver2

Study the current or just-finished session and turn it into a product retrospective for `re-frame-pair2`.

This is a conversation, not an automated report. Surface findings, let the user steer which ones matter, and only then converge on improvements.

## Core job

Deliver:
- what the user was trying to do
- where the workflow dragged, confused, or frustrated them
- which problems were one-off environment issues vs recurring product gaps
- 2-5 concrete improvement ideas, prioritized by leverage, including 1-2 bolder options when they would materially improve the workflow
- an opt-in bead draft or filed bead only after explicit user approval

## Guard rails

- Always start with session analysis. Do not jump straight to fixes.
- Present friction points before root causes. Let the user choose which ones to dig into.
- Default to diagnosis, not contribution. Do not assume the user wants to file a bead or propose a patch.
- Never file a bead or edit another repo without explicit user approval.
- If the user invoked the skill with a specific complaint, focus there first but still notice other background friction.

## Working style

- Prefer evidence over vibes. Cite concrete moments from the session: retries, clarifications, fallbacks, stale outputs, empty outputs, mismatched docs, waits, or manual workarounds.
- Separate symptom from cause. "We had to retry the attach three times" is the symptom; "discovery was brittle on this platform" is the likely cause.
- Notice both direct and indirect friction.
  - Direct: the user says something was frustrating, confusing, slow, brittle, or surprising.
  - Indirect: repeated commands, repeated explanations, fallback to lower-level tools, manual reconstruction, hidden prerequisites, brittle environment assumptions, partial results, confusing contracts, or missing trust signals.
- Notice positive gaps too.
  - What almost worked?
  - What required too much expert knowledge?
  - What capability existed but was undiscoverable?
  - What should have been the default?
- Be creatively ambitious after the diagnosis is clear.
  - Start with grounded fixes supported directly by the session.
  - Then ask what would make this workflow feel nearly automatic, self-explaining, or hard to misuse.
  - Include 1-2 higher-upside ideas when warranted, even if they require reshaping the workflow rather than patching a local symptom.
  - Label speculative ideas clearly; creative does not mean vague.
- Stay focused on improving `re-frame-pair2`. If the best fix belongs upstream in `re-frame2` itself (its Tool-Pair surfaces, trace stream, epoch-history, schema reflection, or source-coordinate annotation), say so explicitly and route the proposal there as a `bd` bead against the `re-frame2` repo, not against `re-frame-pair2`.
- Read [references/analysis-lenses.md](references/analysis-lenses.md) when the session has multiple plausible causes or you need a sharper taxonomy.
- Read [references/known-frictions.md](references/known-frictions.md) when the session resembles a recurring class of `re-frame-pair2` pain and you want to sanity-check whether it is a one-off or a pattern.

## Analysis workflow

1. Reconstruct the session goal.
   - Identify the user's intended outcome, not just the last command.
   - Capture the important environment facts: platform, target repo, live runtime state (which frame, which epoch depth), and tooling constraints.

2. Build a short timeline.
   - List the turns or actions where progress stalled, restarted, detoured, or required a workaround.
   - Include tool errors, empty outputs, stale outputs, retries, and clarification loops.

3. Extract friction.
   - Present a numbered list of friction points first.
   - For each point, note:
     - what happened
     - where it showed up in the session
     - the initial category guess
   - Ask:
     - which of these should we dig into?
     - did I miss anything important?

4. Classify the root cause.
   - Work through these lenses briefly:

     | Lens | Question | Typical improvement |
     |------|----------|---------------------|
     | Skill structure | Was the right guidance present but buried or too low-signal? | Promote to guard rail, shorten, reorder, add examples |
     | Skill gap | Was key guidance missing entirely? | Add a new recipe, anti-pattern, or decision rule |
     | Misleading docs | Did the docs suggest the wrong action or wrong trust model? | Correct wording, add warnings, align contracts |
     | Missing structured op | Did the workflow need a first-class command or result shape? | Add a script/runtime op or a structured field |
     | Unreliable op | Did an existing op behave too ambiguously or too brittlely? | Fix behavior, add warnings, strengthen validation |
     | Default or fallback | Was the default path wrong, silent, or unsafe? | Change defaults or automate the safer fallback |
     | Platform bug | Did the workflow break on a specific shell, OS, or browser setup? | Add platform-aware handling or explicit detection |
     | Validation gap | Did this ship because the right fixture, smoke test, or regression test is missing? | Add test coverage or fixture support |
     | Upstream limitation | Is `re-frame-pair2` working around the wrong abstraction in `re-frame2` itself? | File a `bd` bead against the `re-frame2` repo |
     | Context-window issue | Did long context or low-salience guidance cause forgetfulness? | Make the guard rail shorter, earlier, and stronger |

   - Prefer one primary cause per finding, but allow multiple contributing causes when needed.

5. Generate improvements at the right layer.
   - Consider several layers before choosing one:
     - tighten `SKILL.md` instructions or recipes
     - add or reshape a shell, script, or runtime op
     - enrich structured results, warnings, or failure modes
     - improve cross-platform behavior
     - add validation, fixture coverage, or smoke tests
     - expose new runtime instrumentation
     - file a `bd` bead against `re-frame2` when the right fix is in the framework's Tool-Pair contract rather than the pair tool
   - Prefer proposals that remove repeated effort, not just this session's exact symptom.
   - Ask a broader design question before finalizing ideas:
     - what should the tool have noticed automatically?
     - what manual decision or reconstruction step should disappear?
     - what would make this workflow feel obvious to a first-time user?
     - what would prevent this class of failure earlier?
   - Offer options when there is more than one credible path:
     - no action
     - docs or skill edit
     - tool/runtime change
     - bead draft against `re-frame-pair2`
     - upstream escalation as a `bd` bead against `re-frame2`
   - If the workflow itself seems wrong, include a redesign option instead of only polishing the current path.

6. Prioritize.
   - Favor ideas that are:
     - high impact on common workflows
     - specific enough to implement
     - supported by session evidence
     - likely to improve trust, speed, or debuggability
   - Usually return 2-5 improvements, not a long brainstorm.
   - A good default mix is 1-3 grounded improvements plus 0-2 bolder ideas.

## Output format

Use a compact retrospective with these sections when the session has enough evidence:
- `Goal`
- `Observed friction`
- `Likely root causes`
- `Improvement ideas`
- `Bolder ideas` if there are credible higher-upside options worth separating from the grounded fixes
- `Bead candidates` if the user wants them
- `Other possibilities` if there were good lower-priority ideas

For each improvement idea, include:
- the friction it addresses
- why `re-frame-pair2` was not enough
- the proposed change
- the likely layer of change: skill, script/runtime, tests/docs, or upstream `re-frame2`
- a short impact statement

If the session is too thin, say so plainly and ask for either a recap or permission to use a longer conversation as input.

## Filing improvements

Never file a bead without explicit user approval.

After presenting the retrospective, only offer filing work if it is useful:
- draft bead text
- file the bead directly via `bd create` against the appropriate repo (`re-frame-pair2` for tool changes, `re-frame2` for upstream contract changes), if the user asks
- split the ideas into multiple focused beads, if that would be cleaner

Default routing:
- `re-frame-pair2` — friction inside the pair tool: SKILL.md, scripts, recipes, structured results, attach/discovery brittleness, cross-platform handling.
- `re-frame2` — friction caused by the framework's Tool-Pair contract: missing trace events, gaps in `epoch-history` or `restore-epoch` failure modes, missing registrar query surfaces, source-coordinate annotation gaps, schema-reflection shortcomings.

Before filing:
- redact secrets, tokens, internal URLs, and unnecessary local file paths
- avoid dumping the raw transcript; summarize the evidence instead
- search for an existing bead with `bd list` / `bd ready` or `bd show <id>` before creating a new one
- prefer one bead per materially distinct improvement
- use [references/issue-template.md](references/issue-template.md) for structure

If filing is not possible (e.g. `bd` is not configured in the target repo), produce a ready-to-paste bead body and say that it is a draft, not a filed bead. If the user prefers a GitHub Issue, the same template works — just paste it there.

## What to avoid

- Do not reduce every problem to "write more docs". Consider product behavior, tooling, defaults, and instrumentation first.
- Do not confuse a transient local outage with a product gap unless the workflow made recovery harder than it should have.
- Do not propose vague improvements like "better UX" without naming the concrete missing behavior.
- Do not confuse creativity with hand-waving. Bold ideas still need a concrete change and a believable path to value.
- Do not force every retro into a code contribution or bead.
- Do not file speculative beads unsupported by the session.
- Do not pressure the user to file anything.
- Do not propose fixes that route through `re-frame-10x` — re-frame2's pair tooling does not depend on it. Time-travel and trace-stream consumption ride directly on `re-frame2`'s Tool-Pair surfaces (`register-trace-cb`, `register-epoch-cb`, `epoch-history`, `restore-epoch`, `app-schemas`, source-coord annotation).
