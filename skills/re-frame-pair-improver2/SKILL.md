---
name: re-frame-pair-improver2
description: >
  Retrospect on a **just-finished** `re-frame-pair2` session and turn it
  into prioritised improvement ideas for the `re-frame-pair2` skill,
  scripts, MCP surface, or upstream `re-frame2` Tool-Pair contract.
  Surfaces friction, wasted effort, missing observability, workflow
  mismatches, and high-leverage product gaps; optionally drafts a `bd`
  bead the user can file. Use only when there is a concrete recent
  `re-frame-pair2` session to retrospect on — turns where the user
  attached to a running app, dispatched events, walked traces or epochs,
  hot-swapped a handler, or time-travelled with `restore-epoch` — and
  the user explicitly asks to improve `re-frame-pair2` or wants an
  opt-in bead draft. **Do not use** for ordinary `re-frame-pair2`
  operation (use `re-frame-pair2` itself), generic debugging
  retrospectives with no pair-tool involvement, authoring re-frame2 app
  code (use `re-frame2`), spec or architecture discussion, or framework
  feedback unrelated to the pair tool. Vocabulary matches alone
  ("retro", "what went wrong", "how could this be better") do not
  justify activation — a real `re-frame-pair2` session must have
  occurred in this conversation or be summarised by the user.
---

# re-frame-pair-improver2

Turns the current or just-finished session into a product retrospective for `re-frame-pair2`. This is a conversation, not an automated report: surface findings, let the user steer which ones matter, then converge on improvements.

## Core job

Deliver:
- what the user was trying to do
- where the workflow dragged, confused, or frustrated them
- which problems were one-off environment issues vs recurring product gaps
- 2-5 concrete improvement ideas, prioritized by leverage, including 1-2 bolder options when they would materially improve the workflow
- an opt-in bead draft or filed bead only after explicit user approval

## Guard rails

- **Always start with session analysis.** Do not jump to fixes.
- **Friction points before root causes.** Let the user pick which ones to dig into.
- **Default to diagnosis, not contribution.** Do not assume the user wants to file a bead or propose a patch.
- **Never file a bead or edit another repo without explicit user approval.**
- **Stay focused on improving `re-frame-pair2`.** If the right fix is upstream in `re-frame2` (Tool-Pair surfaces, trace stream, epoch-history, schema reflection, source-coord annotation), say so and route the proposal to a `bd` bead against the `re-frame2` repo, not `re-frame-pair2`.
- **Do not propose fixes via `re-frame-10x`.** v2's pair tooling does not depend on it. Time-travel and trace-stream consumption ride directly on `re-frame2`'s Tool-Pair surfaces (`register-trace-cb!`, `register-epoch-cb!`, `epoch-history`, `restore-epoch`, `app-schemas`, source-coord annotation).

## When NOT to use this skill

Activation precondition is strict: a concrete `re-frame-pair2` session must have already occurred (in this conversation, or as a user-supplied recap). Decline and route elsewhere when:

- No `re-frame-pair2` session happened. Generic debugging retrospectives, post-mortems on shell sessions, IDE workflows, or test-suite runs are out of scope — there is no pair-tool surface to improve.
- The user is mid-session and still trying to make progress. Switch back to `re-frame-pair2`; retrospect after the work, not during it.
- The user is authoring re-frame2 app code (handlers, subs, frames, schemas) without a live runtime involved. Route to `re-frame2`.
- The feedback is about the `re-frame2` framework spec, architecture, or design — not the pair-tool experience. Either route to `re-frame2` directly or file a `bd` bead against the `re-frame2` repo without invoking this skill.
- The user wants help fixing a bug in their own app (not in `re-frame-pair2`). Route to `re-frame-pair2`.
- Vocabulary alone matches ("retro", "what went wrong", "improve workflow") but no pair-tool session is on the table. Ask the user to confirm the session before activating; if there is none, decline.

When in doubt, ask: *"Was there a `re-frame-pair2` session you want me to retrospect on?"* Decline rather than fabricate evidence.

## Working style (short form)

- **Evidence over vibes.** Cite concrete moments: retries, clarifications, fallbacks, stale outputs, empty outputs, waits, manual workarounds.
- **Symptom vs cause.** *"We had to retry the attach three times"* is the symptom; *"discovery was brittle on this platform"* is the likely cause.
- **Direct and indirect friction.** The user says something was frustrating *or* you see repeated commands, repeated explanations, fallback to lower-level tools, manual reconstruction, hidden prerequisites, partial results, confusing contracts.
- **Positive gaps too.** What almost worked? What required too much expert knowledge? What capability existed but was undiscoverable? What should have been the default?
- **Be creatively ambitious after diagnosis.** Start with grounded fixes; then ask what would make this workflow feel nearly automatic or hard to misuse. Include 1-2 higher-upside ideas when warranted, even if they reshape the workflow rather than patching a local symptom. Label speculative ideas clearly.

## Analysis workflow

1. **Reconstruct the session goal.** The user's intended outcome, plus environment facts (platform, target repo, live runtime state, tooling constraints).
2. **Build a short timeline.** Turns where progress stalled, restarted, detoured, required a workaround. Tool errors, empty/stale outputs, retries, clarification loops.
3. **Extract friction.** Numbered list first. For each: what happened, where it appeared, initial category guess. Ask which to dig into and what was missed.
4. **Classify the root cause.** Pick one primary cause per finding from the canonical taxonomy in [`references/analysis-lenses.md` §Root-cause categories](references/analysis-lenses.md#root-cause-categories) (single source of truth — do not redefine inline). Allow multiple contributing causes when needed.
5. **Generate improvements at the right layer** — skill wording, structured op, runtime surface, cross-platform behavior, validation/fixture, instrumentation, or an upstream `re-frame2` bead. Prefer proposals that remove repeated effort, not just this session's exact symptom. Offer options: no action / docs / tool change / pair2 bead / upstream re-frame2 bead.
6. **Prioritize.** Favor high-impact, specific, evidence-supported, trust-improving ideas. Return 2-5; default mix is 1-3 grounded + 0-2 bolder.

Load [`references/analysis-lenses.md`](references/analysis-lenses.md) when the session has multiple plausible causes or you want a sharper taxonomy. Load [`references/known-frictions.md`](references/known-frictions.md) when the session resembles a recurring class of pain and you want to sanity-check one-off vs pattern.

## Output format

Compact retrospective sections (when the session has enough evidence):

- `Goal`
- `Observed friction`
- `Likely root causes`
- `Improvement ideas`
- `Bolder ideas` — for credible higher-upside options worth separating from grounded fixes
- `Bead candidates` — only if the user wants them
- `Other possibilities` — good lower-priority ideas

For each improvement idea: the friction it addresses; why `re-frame-pair2` wasn't enough; the proposed change; the likely layer (skill / script-runtime / tests-docs / upstream `re-frame2`); a short impact statement.

If the session is too thin, say so plainly and ask for a recap or permission to use a longer conversation as input.

## Filing improvements

Never file a bead without explicit user approval. After presenting the retrospective, offer filing work only if useful: draft bead text, file via `bd create` against the appropriate repo, or split into multiple focused beads.

**Routing.** `re-frame-pair2` — friction in the pair tool (SKILL.md, scripts, recipes, structured results, attach/discovery, cross-platform). `re-frame2` — friction caused by the framework's Tool-Pair contract (missing trace events, gaps in `epoch-history` / `restore-epoch` failure modes, missing registrar query surfaces, source-coord annotation gaps, schema-reflection shortcomings).

**Before filing.** Redact secrets, tokens, internal URLs, unnecessary local paths. Don't dump the raw transcript; summarize the evidence. Search for an existing bead first (`bd list` / `bd ready` / `bd show <id>`). Prefer one bead per materially distinct improvement. Use [`references/issue-template.md`](references/issue-template.md) for structure.

If `bd` is not configured in the target repo, produce a ready-to-paste body and say it's a draft, not a filed bead. The same template works as a GitHub Issue.

## Anti-patterns

- Don't reduce every problem to "write more docs". Consider product behavior, tooling, defaults, instrumentation first.
- Don't confuse a transient local outage with a product gap unless the workflow made recovery harder than it should have.
- Don't propose vague improvements like "better UX" without naming the concrete missing behavior.
- Don't force every retro into a code contribution or bead, or pressure the user to file anything.
- Don't file speculative beads unsupported by the session.

## Reference files

- [`references/analysis-lenses.md`](references/analysis-lenses.md) — friction signals (generic + re-frame2-specific), root-cause categories, improvement patterns, routing decisions, prioritization.
- [`references/known-frictions.md`](references/known-frictions.md) — recurring classes of `re-frame-pair2` pain; sanity-check one-off vs pattern.
- [`references/issue-template.md`](references/issue-template.md) — bead/issue body template.
