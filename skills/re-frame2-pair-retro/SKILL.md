---
name: re-frame2-pair-retro
description: >
 Retrospect on a `re-frame2-pair` session and turn it into prioritised
 improvement ideas for the pair skill, scripts, MCP surface, or upstream
 `re-frame2` Tool-Pair contract; optionally drafts a GitHub issue the
 user can file. Activates on two triggers: (a) **explicit pull** — user
 asks for a retrospective on a recent pair session ("retro on this pair
 session", "what went wrong with my pair session", "review my
 re-frame2-pair session", "draft an issue about that"); or (b)
 **post-error within a re-frame2-pair session** — after a stack trace,
 failed dispatch, red CI, or a runtime error during live pair
 work, to post-mortem the firefight. Requires evidence: a concrete
 `re-frame2-pair` session in this conversation (turns where the user
 attached, dispatched, walked traces/epochs, hot-swapped, or
 time-travelled), **or** a user-supplied recap of one. **Not** for
 ordinary `re-frame2-pair` operation, nor for code/spec/framework work
 the body's routing matrix sends elsewhere. Vocabulary matches alone
 ("retro", "what went wrong", "any improvements?") do not justify
 activation — a real pair session must have occurred or be recapped.
allowed-tools:
 - Read
 - Grep
 - Glob
 - Write
 - Bash(gh issue list *)
 - Bash(gh issue view *)
 - Bash(gh issue create *)
 # Read-only label detection: pass `--label` only for labels the repo
 # defines (an unknown label fails the whole create); else no-label
 # baseline — see references/issue-template.md.
 - Bash(gh label list *)
 # Opt-in live-runtime probe (NOT default). The single re-frame2-pair MCP
 # tool granted, read-only; use-gated to an in-conversation live session
 # already attached AND a user-confirmed probe. Captures build id/health/
 # session sentinel; with the server's `tools/list` (an MCP protocol
 # method, implicit once any MCP tool is granted) sanity-checks tool
 # availability. Recap-only/offline retros never probe — see §Guard rails
 # and references/known-frictions.md §Tool-catalogue.
 - mcp__re-frame2-pair__discover-app
---

# re-frame2-pair-retro

Turns a `re-frame2-pair` session — the one happening now (post-error), a just-finished one, or one summarised by the user as a recap — into a product retrospective for `re-frame2-pair`. This is a conversation, not an automated report: surface findings, let the user steer which ones matter, then converge on improvements.

## Two entry modes

The two triggers enter the workflow differently:

- **Explicit pull** — the user asked for a retro ("retro on this session", "draft an issue about that"). Run the analysis workflow below directly.
- **Post-error post-mortem** — a stack trace, failed dispatch, red CI, or an `:rf.error/*` event fired during live re-frame2-pair work and you are reaching for this skill unprompted. The user has *not* asked for a retrospective, so **do not dump the full seven-section output mid-firefight.** First confirm the fire is out — fixing the immediate runtime failure is `re-frame2-pair`'s job (route there). Then *offer* the retro in one line ("Want me to retro on what made that error hard to chase?") and run the workflow only on a yes. Its subject is the *workflow friction* the firefight exposed (why the error was hard to find, recover, or trust), never the application bug. If the user declines, stop — a post-error trigger is an offer, not an obligation.

When you cannot tell which mode you are in, treat it as post-error: offer rather than assume.

## When NOT to use this skill

**Activation precondition**: a `re-frame2-pair` session must be available as evidence — occurring in this conversation, or supplied by the user as a recap. If no pair-tool surface was exercised and the user has not described one, decline.

**Story recorder-session retros are out of scope.** A retro on a Story Test Codegen recording belongs in `re-frame2-pair`'s variant-refinement workflow (the recorder output is a `:play-script` snippet to refine against a frame, not a pair-session friction trace). If the user asks to "retro on my recorded play sequence" or similar, decline and route to `re-frame2-pair`.

Routing decisions (mid-session pair work, app-authoring without a live runtime, framework / spec feedback, app-bug help, vocabulary-only matches) follow the matrix at [`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source) and §Disqualifiers.

When in doubt, ask: *"Was there a `re-frame2-pair` session you want me to retrospect on? If you can paste a short recap I can work from that."* Decline rather than fabricate evidence.

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
- **Default to diagnosis, not contribution.** Do not assume the user wants to file a GitHub issue or propose a patch. The default tool grant is read-only — `Read`, `Grep`, `Glob`, plus `gh issue list` / `gh issue view`. Mutation (`gh issue create`) is granted but gated by the approval rule below.
- **Never file a GitHub issue without explicit user approval.** Drafting issue text is fine; running `gh issue create` is not, until the user has seen the draft and said go. The skill carries no `Edit` — source rewrites in another repo are out of scope; route those as issue suggestions. Its only `Write` use is composing the issue body for `--body-file`; the shell-safety mechanics (per-filing OS-temp body file, safe-alphabet `--title`) live in §Filing improvements and [`../shared/issue-filing.md`](../shared/issue-filing.md).
- **Live-runtime probes are opt-in.** The default path is transcript-only; the skill does not probe the live runtime by default. The allow-list grants exactly one re-frame2-pair MCP tool — the read-only `mcp__re-frame2-pair__discover-app` — **use-gated, not default**: reach for it only when the retro is tied to an in-conversation live session already attached AND the user has confirmed a probe. Any deeper live work (dispatch, app-db read, epoch walk) is pair-programming, not a retro — route to the `re-frame2-pair` skill and reason from the transcript here. Recap-only/offline retros never probe.
- **Stay focused on improving `re-frame2-pair`.** If the right fix is upstream in `re-frame2` — a gap in a Tool-Pair surface from [`../shared/tool-pair-surfaces.md`](../shared/tool-pair-surfaces.md) — say so, name the specific surface (not "the contract"), and route the proposal to a GitHub issue against `re-frame2`.
- **Tracker boundary — file GitHub issues, never `bd` beads.** `bd` is the re-frame2 monorepo's internal tracker; skills consumed downstream file against the target repo's GitHub issues via `gh issue create`. The full filing recipe (tracker boundary, file-after-approval, search-before-file, shell-safe `--body-file`, redaction reminder, body shape) lives in [`../shared/issue-filing.md`](../shared/issue-filing.md); §Filing improvements below is the re-frame2-pair-retro specialisation of it.
- **Do not propose fixes via `re-frame-10x`.** v2's pair tooling does not depend on it. Time-travel and trace-stream consumption ride directly on `re-frame2`'s Tool-Pair surfaces — the canonical surface enumeration and the "supersedes re-frame-10x" claim live in [`../shared/tool-pair-surfaces.md`](../shared/tool-pair-surfaces.md).

## Working style

Diagnostic posture rules (evidence over vibes; symptom vs cause; direct/indirect friction; positive gaps; creatively ambitious *after* diagnosis) live in [`references/working-style.md`](references/working-style.md). Apply them per finding.

## Analysis workflow

Load [`../shared/retro-protocol.md`](../shared/retro-protocol.md) — the normative seven-step diagnosis-first workflow, evidence-citation discipline, layer-routing rules, and opt-in issue-filing protocol shared with `re-frame2-improver`. The six steps below are the re-frame2-pair-retro specialisation; the protocol's step 3 "route to detection rule" is inlined via the lens cross-links (steps 4-5 below) and step 7 "voice" via [`references/working-style.md`](references/working-style.md).

1. **Reconstruct the session goal.** The user's intended outcome, plus environment facts (platform, target repo, live runtime state, tooling constraints).
2. **Build a short timeline.** Turns where progress stalled, restarted, detoured, required a workaround. Tool errors, empty/stale outputs, retries, clarification loops.
3. **Extract friction.** Numbered list first. For each: what happened, where it appeared, initial category guess. Ask which to dig into and what was missed.
4. **Classify the root cause.** Pick one primary cause per finding from the canonical taxonomy in [`references/analysis-lenses.md` §Root-cause categories](references/analysis-lenses.md#root-cause-categories) (single source of truth — do not redefine inline). Allow multiple contributing causes when needed.
5. **Generate improvements at the right layer** — skill wording, structured op, runtime surface, cross-platform behavior, validation/fixture, instrumentation, or an upstream `re-frame2` GitHub issue. Prefer proposals that remove repeated effort, not just this session's exact symptom. Offer options: no action / docs / tool change / re-frame2-pair issue / upstream re-frame2 issue.
6. **Prioritize.** Favor high-impact, specific, evidence-supported, trust-improving ideas. Return 2-5; default mix is 1-3 grounded + 0-2 bolder.

Load [`references/analysis-lenses.md`](references/analysis-lenses.md) when the session has multiple plausible causes or you want a sharper taxonomy — including the error-observability lens when the session chased an error (why it fired, where it surfaced, or why the framework's typed recovery wasn't what the user expected). Load [`references/known-frictions.md`](references/known-frictions.md) when the session resembles a recurring class of pain and you want to sanity-check one-off vs pattern.

## Output format

Compact retrospective sections (when the session has enough evidence):

- `Goal`
- `Observed friction`
- `Likely root causes`
- `Improvement ideas`
- `Bolder ideas` — for credible higher-upside options worth separating from grounded fixes
- `Issue candidates` — only if the user wants them
- `Other possibilities` — good lower-priority ideas

For each improvement idea: the friction it addresses; why `re-frame2-pair` wasn't enough; the proposed change; the likely layer (skill / script-runtime / tests-docs / upstream `re-frame2`); a short impact statement.

If the session is too thin, say so plainly and ask for a recap or permission to use a longer conversation as input.

## Filing improvements

Filing is a **two-step, approval-gated** mode — distinct from the default diagnose-only mode:

1. **Default mode (no approval needed).** Read the transcript, surface findings, draft issue text inline in the conversation. Use `gh issue list` / `gh issue view` to check whether an existing issue already covers the friction (the default tool grant permits these read paths).
2. **Filing mode (explicit approval required).** Only after the user has seen a draft and explicitly says "file it" (or equivalent), invoke `gh issue create` against the appropriate repo. The skill MUST NOT run `gh issue create` on its own initiative — `Bash(gh issue create *)` is granted solely to enable this user-approved transition. Offer filing only if useful, and split into multiple focused issues when the findings warrant it.

**Routing.** Both kinds of friction file against `day8/re-frame2` (the monorepo that ships the pair tool), distinguished — *when the labels exist* — by the `pair-mcp` label. **Pair-tool friction** (optional `--label pair-mcp`) — friction in the pair tool itself (SKILL.md, scripts, recipes, structured results, attach/discovery, cross-platform). **Framework friction** (no `pair-mcp` label) — friction caused by the framework's Tool-Pair contract; name the specific surface from [`../shared/tool-pair-surfaces.md`](../shared/tool-pair-surfaces.md) rather than re-spelling the list here. **Labels are optional taxonomy, not a filing precondition** — `gh issue create` fails the whole command on an unknown `--label`, and the target repo may not define `retro` / `pair-mcp` / `upstream-from-re-frame2-pair`. Always carry the tool-vs-framework distinction in the title + body; pass a `--label` only after confirming the repo defines it (detect with `gh label list`), and fall back to a no-label `gh issue create` so the handoff lands regardless. See [`references/issue-template.md` §Filing with `gh issue create`](references/issue-template.md).

**Filing mechanics — shared recipe.** The procedural rules (redact secrets / tokens / internal URLs / unnecessary local paths, don't dump the raw transcript, search for an existing issue before filing, one issue per materially distinct improvement, the shell-safe `Write`-tool + `--body-file` pattern for the body, and the shell-safe **title** rule — author `--title` from a safe alphabet, never paste an evidence-derived string into it, since `gh` has no `--title-file`) live once in [`../shared/issue-filing.md`](../shared/issue-filing.md). Follow it; this skill adds the routing above and the body skeleton in [`references/issue-template.md`](references/issue-template.md) (the worked `gh issue create` example with re-frame2-pair-retro's label scheme).

## Anti-patterns

- Don't reduce every problem to "write more docs". Consider product behavior, tooling, defaults, instrumentation first.
- Don't confuse a transient local outage with a product gap unless the workflow made recovery harder than it should have.
- Don't propose vague improvements like "better UX" without naming the concrete missing behavior.
- Don't force every retro into a code contribution or GitHub issue, or pressure the user to file anything.
- Don't file speculative issues unsupported by the session.

## Reference files

- [`../shared/retro-protocol.md`](../shared/retro-protocol.md) — shared retro protocol (seven-step diagnosis-first workflow, evidence-citation discipline, layer-routing rules, opt-in issue-filing protocol); shared with `re-frame2-improver`.
- [`../shared/issue-filing.md`](../shared/issue-filing.md) — shared issue-filing recipe (file-after-approval, tracker boundary, search-before-file, shell-safe `--body-file`, redaction reminder, body shape).
- [`../shared/tool-pair-surfaces.md`](../shared/tool-pair-surfaces.md) — canonical enumeration of the Tool-Pair surfaces an upstream finding routes to, and the "supersedes re-frame-10x" claim.
- [`references/analysis-lenses.md`](references/analysis-lenses.md) — friction signals (generic + re-frame2-specific), root-cause categories, improvement patterns, routing decisions, prioritization.
- [`references/known-frictions.md`](references/known-frictions.md) — recurring classes of `re-frame2-pair` pain; sanity-check one-off vs pattern.
- [`references/issue-template.md`](references/issue-template.md) — GitHub-issue body template (+ shell-safety pattern for transcript-derived bodies).
- [`references/working-style.md`](references/working-style.md) — diagnostic-posture rules (evidence over vibes, symptom vs cause, direct/indirect friction, positive gaps, creativity after diagnosis); applied per finding.
