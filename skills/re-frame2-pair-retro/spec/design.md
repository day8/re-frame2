# re-frame2-pair-retro — Design

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame2-pair-retro` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

The design rationale and locked decisions for the `re-frame2-pair-retro` skill. A future agent could re-author this skill from this folder alone.

## 1. Goal

Help a user **retrospect on a re-frame2-pair session** and turn it into a structured product retrospective. The output is a friction analysis, a classification of root causes, and 2-5 concrete improvement ideas — optionally accompanied by a draft bead the user can file against `re-frame2-pair` (or `re-frame2` itself, if the root cause is upstream).

The skill's success criterion: after a session with `re-frame2-pair`, the user invokes this skill and walks away with a clear list of friction points, a credible classification of each, and prioritised improvement ideas — with no speculative beads filed without explicit approval.

## 2. Pillars (locked)

1. **Diagnosis before contribution.** The skill defaults to analysis, not patches. Surfacing findings is the deliverable; filing beads is opt-in and requires explicit user approval.
2. **Evidence over vibes.** Every friction point cites a concrete moment in the session — retries, clarifications, fallbacks to lower-level tools, stale outputs, empty outputs, mismatched docs, waits, manual workarounds. *"That was annoying"* without an evidence trail isn't actionable.
3. **Right layer of fix.** A friction might belong in the skill prose, in a structured op, in a runtime instrument, in a default, in a test, or upstream in `re-frame2` itself. The skill walks all the layers before proposing one.
4. **Creativity after the diagnosis.** Once the friction is named, the skill is permitted (encouraged) to propose **bolder** ideas — workflow redesigns, automated detection, "what would make this feel automatic" — clearly labelled as such.

## 3. Locked decisions

These are not up for re-litigation. A future authoring pass MUST preserve them unless explicitly unlocked by Mike.

### L1 — No re-frame-10x routing

Per the parent `re-frame2-pair` skill's L2: re-frame2's pair tooling does not depend on re-frame-10x. The improver MUST NOT propose fixes that route through 10x — time-travel and trace-stream consumption ride directly on `re-frame2`'s Tool-Pair surfaces (`register-listener!`, `register-epoch-listener!`, `epoch-history`, `restore-epoch`, `app-schemas`, source-coord annotation). This is a cardinal "What to avoid" rule.

### L2 — Never file a GitHub issue without explicit user approval

The skill drafts issue text on request; it does not file issues autonomously. After presenting the retrospective, the skill offers to file *only if asked*. Filing is opt-in, not opt-out. This is a cardinal guard-rail.

**Tracker boundary.** Filings target the **target repo's GitHub issues** (`day8/re-frame2-pair` for tool-side friction; `day8/re-frame2` for upstream framework friction). `bd` (beads) is the re-frame2 monorepo's internal tracker and is never invoked from a published skill. Bodies pass via stdin / here-doc — `gh issue create --body "$(cat /tmp/issue-body.md)"` — never inline interpolation of transcript-derived text. See `skills/README.md` §Published-skill `allowed-tools` baseline for the canonical shape.

### L3 — Route the fix to the right repo

- **`re-frame2-pair`** — friction inside the pair tool: SKILL.md, scripts/MCP tools, recipes, structured results, attach/discovery brittleness, cross-platform handling.
- **`re-frame2`** — friction caused by the framework's Tool-Pair contract: missing trace events, gaps in `epoch-history` / `restore-epoch` failure modes, missing registrar query surfaces, source-coordinate annotation gaps, schema-reflection shortcomings.

The skill is explicit about which repo each draft bead targets. Mis-routing wastes the maintainer's time.

### L4 — Diagnosis-first workflow

The skill walks a six-step analysis: reconstruct goal → build timeline → extract friction → classify root cause → generate improvements at the right layer → prioritise. The order matters; jumping to step 5 without 1-3 produces speculative beads unsupported by session evidence.

### L5 — Use the analysis-lenses taxonomy

`references/analysis-lenses.md` defines nine root-cause lenses (`docs/discoverability` / `workflow-gap` / `missing-op` / `unreliable-op` / `default/fallback` / `platform-bug` / `validation-gap` / `upstream-gap` / `out-of-scope`). Each lens has a question to ask and a typical improvement shape. The skill walks these lenses **briefly** for each finding; doesn't force every finding through every lens.

### L6 — Bolder ideas are labelled

After the grounded fixes, the skill is allowed (and encouraged) to include 1-2 bolder ideas — workflow redesigns, "what would make this feel obvious to a first-time user" — but they are clearly labelled `Bolder ideas` (separate output section) so the user can triage them differently. Bolder ≠ vague; even speculative ideas need a concrete change and a believable path to value.

### L7 — Compact retrospective output shape

When the session has enough evidence, the retro uses these sections:
- `Goal`
- `Observed friction` (numbered list, presented first for user steering)
- `Likely root causes` (one primary per finding; multiple contributors allowed)
- `Improvement ideas` (2-5 grounded; bolder ideas separated)
- `Bolder ideas` (if any)
- `Issue candidates` (only if user asks)
- `Other possibilities` (low-priority leftovers)

If the session is too thin, the skill says so plainly and asks for either a recap or permission to use a longer conversation as input.

### L8 — Improvement ideas carry layer + impact

For each idea, the skill names:
- the friction it addresses
- why `re-frame2-pair` was not enough today
- the proposed change
- the layer of change (skill / script / runtime / tests / docs / upstream `re-frame2`)
- a short impact statement

This forces the improvement to be specific enough to act on.

### L9 — Use `references/known-frictions.md` for pattern matching

When a session resembles a recurring class of pain, the skill consults `references/known-frictions.md` to check whether it's a one-off or a recurring pattern. Recurring patterns get higher priority in the retrospective.

### L10 — No bead-ids in user-facing skill content

`SKILL.md` + `references/` carry no `rf2-XXXX` references. Draft beads written by the skill cite ids the user asks about; the skill itself does not embed ids in its prose. The `spec/` folder may; user-facing content does not.

### L11 — Findings stay local

Per Mike's standing memory rule "Findings is local-only" — never commit `ai/` or `findings/`. This skill's commits contain only `skills/re-frame2-pair-retro/**`.

### L12 — Redact secrets before filing

Bead drafts redact secrets, tokens, internal URLs, and unnecessary local file paths. The skill summarises the evidence rather than dumping raw transcript.

## 4. Audience and scope

### In scope

- Users finishing a `re-frame2-pair` session who want a structured retrospective.
- Friction analysis: direct (user complaints) + indirect (repeated commands, fallback to lower-level tools, manual reconstruction).
- Classification across the nine lenses in `references/analysis-lenses.md`.
- Drafting beads against `re-frame2-pair` (tool changes) or `re-frame2` (upstream contract changes).
- Spotting recurring patterns via `references/known-frictions.md`.

### Out of scope

- Filing beads autonomously — L2.
- Routing through re-frame-10x — L1.
- Diagnosing the user's *application code* (that's `re-frame2-pair`'s job, in a live session).
- Authoring re-frame2 application code — `skills/re-frame2/`.
- Setting up a project — `skills/re-frame2-setup/`.
- Migrating from v1 — `skills/re-frame-migration/`.

## 5. File structure (locked)

```
skills/re-frame2-pair-retro/
├── SKILL.md (~170 lines; the conversation guide + workflow)
├── README.md (human-facing intro)
├── LICENSE (MIT)
├── package.json (npm metadata)
├── .claude-plugin/plugin.json (Claude Code plugin metadata)
├── agents/
│ └── openai.yaml (alt-host config — kept for cross-LLM operation)
├── references/
│ ├── analysis-lenses.md (~140 lines; nine root-cause lenses + improvement shapes)
│ ├── known-frictions.md (~120 lines; recurring re-frame2-pair pain patterns)
│ └── issue-template.md (~90 lines; bead-body template, redaction rules)
└── spec/
 ├── design.md (this file)
 ├── inputs.md (canonical inputs)
 └── authoring-prompt.md (one-shot reauthor prompt)
```

SKILL.md (~170) + 3 references (~350) + spec (~300) ≈ ~820 LoC. Typical session reads SKILL.md (~170) + at most one or two references (`analysis-lenses.md` when classification is hard, `known-frictions.md` when the session smells recurring) = ~310-450 LoC.

## 6. Discovery surface (frontmatter `description`)

The `description` triggers on two canonical paths: (a) **explicit pull** — retrospective / improvement / friction phrases like *"retro on this pair session"*, *"what went wrong with my pair session"*, *"review my re-frame2-pair session"*, *"draft a bead for re-frame2-pair"*; (b) **post-error within a re-frame2-pair session** — after a stack trace, failed dispatch, red CI, or `:on-error` policy fire during live re-frame2-pair work, to post-mortem the immediate firefight. Acceptable evidence is either a concrete `re-frame2-pair` session in the conversation or a user-supplied recap / summary of one. The framing is conversational — discriminates against the live-app `re-frame2-pair` skill (which triggers on dispatch / app-db / epoch verbs), the authoring `re-frame2` skill (which triggers on `reg-*` surfaces), and the framework-pattern critique `re-frame2-improver` skill (which triggers on re-frame2 code shape, not pair-tool sessions).

## 7. Anti-patterns the skill explicitly resists

- **Routing fixes through re-frame-10x** — L1 cardinal rule.
- **Filing beads without explicit approval** — L2 cardinal rule.
- **Reducing every problem to "write more docs"** — L5 lenses include skill structure, structured op, default-or-fallback, validation, upstream — not just docs.
- **Confusing a transient local outage with a product gap** — L4 step 2 (build timeline) separates one-off env issues from recurring patterns.
- **Proposing vague improvements** ("better UX") without naming the concrete missing behaviour — L8 forces specifics.
- **Confusing creativity with hand-waving** — L6 bolder ideas need a concrete change and a believable path to value.
- **Forcing every retro into a code contribution** — L2 again; diagnosis is the deliverable.
- **Pressuring the user to file anything** — L2 again.

## 8. Working style (meta-process)

The pillars in §2 govern *what* the skill delivers; the rules below govern *how* the AI conducts the retro conversation. They lock the diagnostic posture so the skill doesn't degenerate into vibes-driven editorialising or solutionism.

- **Evidence over vibes.** Cite concrete moments: retries, clarifications, fallbacks, stale outputs, empty outputs, waits, manual workarounds. Re-states pillar 2 as an operational rule per finding.
- **Symptom vs cause.** *"We had to retry the attach three times"* is the symptom; *"discovery was brittle on this platform"* is the likely cause. The skill names both; the bead targets the cause.
- **Direct and indirect friction.** Direct = the user says something was frustrating. Indirect = repeated commands, repeated explanations, fallback to lower-level tools, manual reconstruction, hidden prerequisites, partial results, confusing contracts. Both count as evidence.
- **Positive gaps too.** What almost worked? What required too much expert knowledge? What capability existed but was undiscoverable? What should have been the default? Negative-space evidence is as load-bearing as failure evidence.
- **Be creatively ambitious after diagnosis.** Start with grounded fixes; then ask what would make this workflow feel nearly automatic or hard to misuse. Include 1-2 higher-upside ideas when warranted, even if they reshape the workflow rather than patching a local symptom. Operationalises pillar 4 + L6.

SKILL.md cross-links here rather than reciting these rules inline — keeps the orchestrator lean per `skills/README.md` §Leaf size discipline's per-leaf size ceiling.

## 9. Why this design diverges from `re-frame2-pair`

- **No structured-op catalogue.** This skill doesn't operate on a live app; it operates on a session transcript.
- **Read-only `allowed-tools` block in frontmatter.** The skill is conversational and diagnose-first, so it ships an explicit allow-list — `Read`, `Grep`, `Glob`, plus `Bash(gh issue list *)` / `Bash(gh issue view *)` / `Bash(gh issue create *)`. It deliberately omits `Edit` and `Write`: the skill never rewrites source, in this repo or another — friction routes to GitHub issues against the target repo, not edits. `gh issue create` is granted but approval-gated (L2). `bd` is the re-frame2 monorepo's internal tracker and is never invoked from a published skill.
- **No connect-first rule.** No live runtime to connect to.
- **`agents/openai.yaml` is included.** The skill is portable across LLM hosts; the openai config carries the routing for non-Claude hosts.
- **No `scripts/` directory.** The skill doesn't ship runtime tooling.

## 10. Open questions (deferred to Mike)

### OQ1 — Should the skill ship a "session capture" helper?

Currently the skill reads the in-conversation transcript. A future helper could snapshot the session to disk (redacted) for offline review. Status: deferred — no clear demand yet.

### OQ2 — Should `known-frictions.md` carry severity tagging?

Currently it lists patterns; ranking by "how often this surfaces" would help triage. Status: deferred — needs evidence from filed beads to rank credibly.

### OQ3 — Should the skill include a "post-mortem of a post-mortem" lens?

If the same friction surfaces across multiple retros, the skill could spot it. Currently the user does this manually by reading `references/known-frictions.md`. Status: deferred until the volume of retros makes the manual approach unwieldy.
