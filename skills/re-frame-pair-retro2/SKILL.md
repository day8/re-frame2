---
name: re-frame-pair-retro2
description: >
  Retrospect on a `re-frame-pair2` session and turn it into prioritised
  improvement ideas for the `re-frame-pair2` skill, scripts, MCP
  surface, or upstream `re-frame2` Tool-Pair contract. Surfaces
  friction, wasted effort, missing observability, workflow mismatches,
  and high-leverage product gaps; optionally drafts a GitHub issue
  the user can file against the target repo. Activates on two
  triggers: (a) **explicit pull** — user asks for a retrospective
  on a recent pair session ("retro on this pair session", "what went
  wrong with my pair session", "review my re-frame-pair2 session",
  "draft an issue about that"); or (b)
  **post-error within a pair2 session** — after a stack trace, failed
  dispatch, red CI, or `:on-error` policy fire during live pair2 work,
  to post-mortem the immediate firefight. Acceptable evidence is
  either a concrete `re-frame-pair2` session in this conversation —
  turns where the user attached to a running app, dispatched events,
  walked traces or epochs, hot-swapped a handler, or time-travelled
  with `restore-epoch` — **or** a user-supplied recap / summary of
  such a session. **Do not use** for ordinary `re-frame-pair2`
  operation (use `re-frame-pair2` itself), generic debugging
  retrospectives with no pair-tool involvement, authoring re-frame2
  app code (use `re-frame2`), critique of re-frame2 patterns / idioms
  in code (use `re-frame2-improver`), spec or architecture
  discussion, or framework feedback unrelated to the pair tool.
  Vocabulary matches alone ("retro", "what went wrong", "how could
  this be better", "any improvements?") do not justify activation —
  a real `re-frame-pair2` session must have occurred in this
  conversation or be summarised by the user as a recap.
allowed-tools:
  - Bash(gh issue *)
  - Bash(gh pr *)
  - Read
  - Edit
  - Write
  - Grep
  - Glob
  - mcp__re-frame-pair2__discover-app
---

# re-frame-pair-retro2

Turns a `re-frame-pair2` session — the one happening now (post-error), a just-finished one, or one summarised by the user as a recap — into a product retrospective for `re-frame-pair2`. This is a conversation, not an automated report: surface findings, let the user steer which ones matter, then converge on improvements.

## Core job

Deliver:
- what the user was trying to do
- where the workflow dragged, confused, or frustrated them
- which problems were one-off environment issues vs recurring product gaps
- 2-5 concrete improvement ideas, prioritized by leverage, including 1-2 bolder options when they would materially improve the workflow
- an opt-in GitHub-issue draft or filed issue only after explicit user approval

## Guard rails

- **Always start with session analysis.** Do not jump to fixes.
- **Friction points before root causes.** Let the user pick which ones to dig into.
- **Default to diagnosis, not contribution.** Do not assume the user wants to file a GitHub issue or propose a patch.
- **Never file a GitHub issue or edit another repo without explicit user approval.**
- **Stay focused on improving `re-frame-pair2`.** If the right fix is upstream in `re-frame2` (Tool-Pair surfaces, trace stream, epoch-history, schema reflection, source-coord annotation), say so and route the proposal to a GitHub issue against the `re-frame2` repo, not `re-frame-pair2`.
- **Tracker boundary — file GitHub issues, never `bd` beads.** `bd` is the re-frame2 monorepo's internal tracker; skills consumed downstream file against the target repo's GitHub issues via `gh issue create`. See the shell-safety pattern below.
- **Do not propose fixes via `re-frame-10x`.** v2's pair tooling does not depend on it. Time-travel and trace-stream consumption ride directly on `re-frame2`'s Tool-Pair surfaces (`register-trace-cb!`, `register-epoch-cb!`, `epoch-history`, `restore-epoch`, `app-schemas`, source-coord annotation).

## When NOT to use this skill

**Activation precondition**: a `re-frame-pair2` session must be available as evidence — either occurring in this conversation, or supplied by the user as a recap / summary. If no pair-tool surface was exercised and the user has not described one, decline — there is nothing to retrospect on.

**Story recorder-session retros are out of scope.** Retrospectives on a Story Test Codegen recording (rf2-5fc15 — clicks/fills captured as a `:play` body) are NOT this skill's concern. Those retros belong in `re-frame-pair2`'s variant-refinement workflow (the recorder output is a `:play` snippet to refine against a frame, not a pair-session friction trace). If the user asks to "retro on my recorded play sequence" or similar, decline and route to `re-frame-pair2`.

Routing decisions (mid-session pair work, app-authoring without a live runtime, framework / spec feedback, app-bug help, vocabulary-only matches) follow the matrix at [`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source) and §Disqualifiers.

When in doubt, ask: *"Was there a `re-frame-pair2` session you want me to retrospect on? If you can paste a short recap I can work from that."* Decline rather than fabricate evidence.

## Working style

Diagnostic posture rules (evidence over vibes; symptom vs cause; direct/indirect friction; positive gaps; creatively ambitious *after* diagnosis) live in [`spec/design.md` §8 Working style](spec/design.md#8-working-style-meta-process). Apply them per finding.

## Analysis workflow

Load [`../shared/retro-protocol.md`](../shared/retro-protocol.md) — the seven-step diagnosis-first workflow, evidence-citation discipline, layer-routing rules, and opt-in issue-filing protocol shared with `re-frame2-improver`. The protocol leaf is the normative source for the workflow shape; the steps below are the pair2-retro specialisation.

1. **Reconstruct the session goal.** The user's intended outcome, plus environment facts (platform, target repo, live runtime state, tooling constraints).
2. **Build a short timeline.** Turns where progress stalled, restarted, detoured, required a workaround. Tool errors, empty/stale outputs, retries, clarification loops.
3. **Extract friction.** Numbered list first. For each: what happened, where it appeared, initial category guess. Ask which to dig into and what was missed.
4. **Classify the root cause.** Pick one primary cause per finding from the canonical taxonomy in [`references/analysis-lenses.md` §Root-cause categories](references/analysis-lenses.md#root-cause-categories) (single source of truth — do not redefine inline). Allow multiple contributing causes when needed.
5. **Generate improvements at the right layer** — skill wording, structured op, runtime surface, cross-platform behavior, validation/fixture, instrumentation, or an upstream `re-frame2` GitHub issue. Prefer proposals that remove repeated effort, not just this session's exact symptom. Offer options: no action / docs / tool change / pair2 issue / upstream re-frame2 issue.
6. **Prioritize.** Favor high-impact, specific, evidence-supported, trust-improving ideas. Return 2-5; default mix is 1-3 grounded + 0-2 bolder.

Load [`references/analysis-lenses.md`](references/analysis-lenses.md) when the session has multiple plausible causes or you want a sharper taxonomy — including the `:on-error` policy lens when the session touched a frame's `:on-error` slot (inspecting it, hot-swapping it, or chasing why an error wasn't recovered the expected way). Load [`references/known-frictions.md`](references/known-frictions.md) when the session resembles a recurring class of pain and you want to sanity-check one-off vs pattern.

## Output format

Compact retrospective sections (when the session has enough evidence):

- `Goal`
- `Observed friction`
- `Likely root causes`
- `Improvement ideas`
- `Bolder ideas` — for credible higher-upside options worth separating from grounded fixes
- `Issue candidates` — only if the user wants them
- `Other possibilities` — good lower-priority ideas

For each improvement idea: the friction it addresses; why `re-frame-pair2` wasn't enough; the proposed change; the likely layer (skill / script-runtime / tests-docs / upstream `re-frame2`); a short impact statement.

If the session is too thin, say so plainly and ask for a recap or permission to use a longer conversation as input.

## Filing improvements

Never file a GitHub issue without explicit user approval. After presenting the retrospective, offer filing work only if useful: draft issue text, file via `gh issue create` against the appropriate repo, or split into multiple focused issues.

**Routing.** `re-frame-pair2` — friction in the pair tool (SKILL.md, scripts, recipes, structured results, attach/discovery, cross-platform). `re-frame2` — friction caused by the framework's Tool-Pair contract (missing trace events, gaps in `epoch-history` / `restore-epoch` failure modes, missing registrar query surfaces, source-coord annotation gaps, schema-reflection shortcomings).

**Before filing.** Redact secrets, tokens, internal URLs, unnecessary local paths. Don't dump the raw transcript; summarize the evidence. Search for an existing issue first (`gh issue list --search "<keywords>"`). Prefer one issue per materially distinct improvement. Use [`references/issue-template.md`](references/issue-template.md) for structure.

**Shell safety — transcript-derived text.** Issue bodies drawn from the session transcript can carry shell metacharacters (`$`, backticks, `\`, embedded quotes) the user never sees but the shell would happily expand. Always write the body to a file first and pass it via `--body "$(cat …)"`, never as an interpolated string. Canonical shape:

```bash
cat > /tmp/issue-body.md <<'EOF'
…body drawn from the retro transcript…
EOF
gh issue create --title "<short title>" --body "$(cat /tmp/issue-body.md)"
```

Single-quoted here-doc delimiter (`<<'EOF'`) so `$`, `` ` ``, and `\` inside the body stay literal. This matches the skills-baseline shell-safety pattern in [`../README.md` §Published-skill `allowed-tools` baseline](../README.md#published-skill-allowed-tools-baseline-security-policy).

**Tracker boundary.** `bd` (beads) is the re-frame2 monorepo's internal tracker; this skill never invokes `bd`. Cross-repo side effects target the **target repo's GitHub issues** — `re-frame-pair2` issues for pair-tool friction, `re-frame2` issues for upstream / framework friction.

## Anti-patterns

- Don't reduce every problem to "write more docs". Consider product behavior, tooling, defaults, instrumentation first.
- Don't confuse a transient local outage with a product gap unless the workflow made recovery harder than it should have.
- Don't propose vague improvements like "better UX" without naming the concrete missing behavior.
- Don't force every retro into a code contribution or GitHub issue, or pressure the user to file anything.
- Don't file speculative issues unsupported by the session.

## Reference files

- [`../shared/retro-protocol.md`](../shared/retro-protocol.md) — shared retro protocol (seven-step diagnosis-first workflow, evidence-citation discipline, layer-routing rules, opt-in issue-filing protocol). Extracted by rf2-dhe9v; consumed by both this skill and `re-frame2-improver`.
- [`references/analysis-lenses.md`](references/analysis-lenses.md) — friction signals (generic + re-frame2-specific), root-cause categories, improvement patterns, routing decisions, prioritization.
- [`references/known-frictions.md`](references/known-frictions.md) — recurring classes of `re-frame-pair2` pain; sanity-check one-off vs pattern.
- [`references/issue-template.md`](references/issue-template.md) — GitHub-issue body template (+ shell-safety pattern for transcript-derived bodies).
