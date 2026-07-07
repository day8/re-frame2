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
 # Read-only label detection — labels are optional; see §Filing improvements.
 - Bash(gh label list *)
 # Opt-in, use-gated live-runtime probe (NOT default) — see §Guard rails.
 - mcp__re-frame2-pair__discover-app
---

# re-frame2-pair-retro

Turns a `re-frame2-pair` session — the one happening now (post-error), a just-finished one, or one summarised by the user as a recap — into a product retrospective for `re-frame2-pair`. This is a conversation, not an automated report: surface findings, let the user steer which ones matter, then converge on improvements.

## Two entry modes

The two triggers enter the workflow differently:

- **Explicit pull** — the user asked for a retro ("retro on this session", "draft an issue about that"). Run the analysis workflow below directly.
- **Post-error post-mortem** — a stack trace, failed dispatch, red CI, or an `:rf.error/*` event fired during live re-frame2-pair work and you are reaching for this skill unprompted. The user has *not* asked for a retrospective, so **do not dump the full retro output mid-firefight.** First confirm the fire is out — fixing the immediate runtime failure is `re-frame2-pair`'s job (route there). Then *offer* the retro in one line ("Want me to retro on what made that error hard to chase?") and run the workflow only on a yes. Its subject is the *workflow friction* the firefight exposed (why the error was hard to find, recover, or trust), never the application bug. If the user declines, stop — a post-error trigger is an offer, not an obligation.

When you cannot tell which mode you are in, treat it as post-error: offer rather than assume.

## When NOT to use this skill

**Story recorder-session retros are out of scope.** A retro on a Story Test Codegen recording belongs in `re-frame2-pair`'s variant-refinement workflow (the recorder output is a `:play-script` snippet to refine against a frame, not a pair-session friction trace). If the user asks to "retro on my recorded play sequence" or similar, decline and route to `re-frame2-pair`.

Routing decisions (mid-session pair work, app-authoring without a live runtime, framework / spec feedback, app-bug help, vocabulary-only matches) follow the matrix at [`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source) and §Disqualifiers. The activation precondition — a real `re-frame2-pair` session must have occurred or be recapped — lives in the frontmatter description and that matrix.

When in doubt, ask: *"Was there a `re-frame2-pair` session you want me to retrospect on? If you can paste a short recap I can work from that."* Decline rather than fabricate evidence.

## Guard rails

- **Always start with session analysis.** Do not jump to fixes. Surface friction points before root causes, and let the user pick which ones to dig into.
- **Default to diagnosis, not contribution.** Do not assume the user wants to file a GitHub issue or propose a patch. The default tool grant is read-only — `Read`, `Grep`, `Glob`, plus `gh issue list` / `gh issue view`. Mutation (`gh issue create`) is granted but gated by the approval rule below.
- **Never file a GitHub issue without explicit user approval.** Drafting issue text is fine; running `gh issue create` is not, until the user has seen the draft and said go. The skill carries no `Edit` — source rewrites in another repo are out of scope; route those as issue suggestions. Its only `Write` use is composing the issue body for `--body-file` (the shell-safety mechanics live in [`../shared/issue-filing.md`](../shared/issue-filing.md); the operational gate is §Filing improvements below).
- **Live-runtime probes are opt-in.** The default path is transcript-only; the skill does not probe the live runtime by default. The allow-list grants exactly one re-frame2-pair MCP tool — the read-only `mcp__re-frame2-pair__discover-app` — **use-gated, not default**: reach for it only when the retro is tied to an in-conversation live session already attached AND the user has confirmed a probe. It captures build id/health/session sentinel and (with the server's `tools/list`) sanity-checks tool availability. Any deeper live work (dispatch, app-db read, epoch walk) is pair-programming, not a retro — route to the `re-frame2-pair` skill and reason from the transcript here. Recap-only/offline retros never probe.
- **Stay focused on improving `re-frame2-pair`.** If the right fix is upstream in `re-frame2` — a gap in a Tool-Pair surface from [`../shared/tool-pair-surfaces.md`](../shared/tool-pair-surfaces.md) — say so, name the specific surface (not "the contract"), and route the proposal to a GitHub issue against `re-frame2`.
- **Tracker boundary — file GitHub issues, never `bd` beads.** `bd` is the re-frame2 monorepo's internal tracker; skills consumed downstream file against the target repo's GitHub issues via `gh issue create`. The full filing recipe lives in [`../shared/issue-filing.md`](../shared/issue-filing.md); §Filing improvements below is the re-frame2-pair-retro specialisation.
- **Do not propose fixes via `re-frame-10x`.** v2's pair tooling does not depend on it. Time-travel and trace-stream consumption ride directly on `re-frame2`'s Tool-Pair surfaces — the canonical surface enumeration and the "supersedes re-frame-10x" claim live in [`../shared/tool-pair-surfaces.md`](../shared/tool-pair-surfaces.md).

## Working style

Diagnostic posture rules (evidence over vibes; symptom vs cause; direct/indirect friction; positive gaps; creatively ambitious *after* diagnosis) live in [`references/working-style.md`](references/working-style.md). Apply them per finding.

## Analysis workflow and output shape

Load [`../shared/retro-protocol.md`](../shared/retro-protocol.md) for the workflow shape — the diagnosis-first steps (read the evidence → identify friction candidates → route to the detection rule → surface findings with concrete evidence → cross-link the canonical fix → opt-in issue-filing → confident, no-hedging voice), plus the evidence-citation discipline, the untrusted-evidence and universal-redaction boundaries, the layer-routing rules, and the seven-section output shape. It is shared with `re-frame2-improver`; the pair-retro specialisation is below.

Pair-retro deltas:

- **Reconstruct the session goal and a short timeline first** — the intended outcome, environment facts (platform, target repo, live runtime state, tooling constraints), and the turns where progress stalled, restarted, detoured, or needed a workaround (tool errors, empty/stale outputs, retries, clarification loops). Present the friction as a numbered list before classifying, and ask which to dig into.
- **Route each finding through the catalogues.** Classify one primary root cause from the canonical taxonomy in [`references/analysis-lenses.md` §Root-cause categories](references/analysis-lenses.md#root-cause-categories) (single source — do not redefine inline). Pattern-match recurring friction against [`references/known-frictions.md`](references/known-frictions.md) to tell a one-off from a product gap — including its error-observability class when the session chased an error (why it fired, where it surfaced, or why the framework's typed recovery wasn't what the user expected).
- **Generate improvements at the right layer** — skill wording, structured op, runtime surface, cross-platform behavior, validation/fixture, instrumentation, or an upstream `re-frame2` GitHub issue. Prefer proposals that remove repeated effort, not just this session's exact symptom.
- **Output** uses the protocol's seven sections under these labels: `Goal`; `Observed friction` (numbered, first, for user steering); `Likely root causes` (one primary per finding, contributors allowed); `Improvement ideas` (2-5; default mix 1-3 grounded + 0-2 bolder, each carrying the friction it addresses, why `re-frame2-pair` wasn't enough, the proposed change, the layer, and a one-line impact); `Bolder ideas`; `Issue candidates` (only if the user wants them); `Other possibilities`. If the session is too thin, say so plainly and ask for a recap or permission to use a longer conversation as input.

## Filing improvements

Filing is a **two-step, approval-gated** mode — distinct from the default diagnose-only mode:

1. **Default mode (no approval needed).** Read the transcript, surface findings, draft issue text inline in the conversation. Use `gh issue list` / `gh issue view` to check whether an existing issue already covers the friction.
2. **Filing mode (explicit approval required).** Only after the user has seen a draft and explicitly says "file it" (or equivalent), invoke `gh issue create` against the appropriate repo. Never run `gh issue create` on your own initiative — `Bash(gh issue create *)` is granted solely to enable this user-approved transition. Offer filing only if useful, and split into multiple focused issues when the findings warrant it.

**Routing.** Both pair-tool friction and framework friction file against `day8/re-frame2` (the monorepo that ships the pair tool); carry the tool-vs-framework distinction in the title + body, naming the specific surface from [`../shared/tool-pair-surfaces.md`](../shared/tool-pair-surfaces.md) for a framework gap. The full pre-drafting routing decision is [`references/issue-template.md` §Routing first](references/issue-template.md).

**Labels are optional taxonomy, not a filing precondition.** `gh issue create` fails the whole command on an unknown `--label`, and the target repo may not define `retro` / `pair-mcp` / `upstream-from-re-frame2-pair`. Detect with `gh label list`, pass a `--label` only after confirming the repo defines it, and fall back to a no-label `gh issue create` so the handoff always lands. This is the canonical statement of the rule; the operational degrade steps are in [`references/issue-template.md` §Filing with `gh issue create`](references/issue-template.md).

**Filing mechanics.** The shared shell-safety core — search-before-file, the `Write`-tool + `--body-file` body path (a fresh per-filing OS-temp file), the safe-alphabet `--title` / `--search`, and redaction — lives once in [`../shared/issue-filing.md`](../shared/issue-filing.md); the body skeleton is [`references/issue-template.md`](references/issue-template.md).

## Anti-patterns

- Don't reduce every problem to "write more docs". Consider product behavior, tooling, defaults, instrumentation first.
- Don't confuse a transient local outage with a product gap unless the workflow made recovery harder than it should have.
- Don't propose vague improvements like "better UX" without naming the concrete missing behavior.
- Don't file speculative issues unsupported by the session, or pressure the user to file anything.
