# re-frame2-pair-retro — Design

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame2-pair-retro` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

The design rationale and locked decisions for the `re-frame2-pair-retro` skill. A future agent could re-author this skill from this folder alone.

## 1. Goal

Help a user **retrospect on a re-frame2-pair session** and turn it into a structured product retrospective. The output is a friction analysis, a classification of root causes, and 2-5 concrete improvement ideas — optionally accompanied by a draft GitHub issue the user can file against `day8/re-frame2` (the monorepo that ships the pair tool alongside the framework). The tool-vs-framework distinction is carried in the title + body; an optional `pair-mcp` label reinforces tool-side friction **only when the repo defines it** (labels are optional taxonomy, never a filing precondition — see L3).

The skill's success criterion: after a session with `re-frame2-pair`, the user invokes this skill and walks away with a clear list of friction points, a credible classification of each, and prioritised improvement ideas — with no speculative GitHub issues filed without explicit approval.

## 2. Pillars (locked)

1. **Diagnosis before contribution.** The skill defaults to analysis, not patches. Surfacing findings is the deliverable; filing GitHub issues is opt-in and requires explicit user approval.
2. **Evidence over vibes.** Every friction point cites a concrete moment in the session — retries, clarifications, fallbacks to lower-level tools, stale outputs, empty outputs, mismatched docs, waits, manual workarounds. *"That was annoying"* without an evidence trail isn't actionable.
3. **Right layer of fix.** A friction might belong in the skill prose, in a structured op, in a runtime instrument, in a default, in a test, or upstream in `re-frame2` itself. The skill walks all the layers before proposing one.
4. **Creativity after the diagnosis.** Once the friction is named, the skill is permitted (encouraged) to propose **bolder** ideas — workflow redesigns, automated detection, "what would make this feel automatic" — clearly labelled as such.

## 3. Locked decisions

These are not up for re-litigation. A future authoring pass MUST preserve them unless explicitly unlocked by Mike.

### L1 — No re-frame-10x routing

Per the parent `re-frame2-pair` skill's L2: re-frame2's pair tooling does not depend on re-frame-10x. This skill MUST NOT propose fixes that route through 10x — time-travel and trace-stream consumption ride directly on `re-frame2`'s Tool-Pair surfaces. The authoritative, current surface-family enumeration lives in [`../../shared/tool-pair-surfaces.md`](../../shared/tool-pair-surfaces.md) (trace stream, registrar query API, epoch-history / restore, the four state-injection mutators, schema reflection, source-coord annotation, direct reads, render-driving / dispatch-settle, view-plane reads, the signal recorder, the operating-frame trio) — name the matching family from there rather than re-spelling an abbreviated subset here. This is a cardinal "What to avoid" rule.

### L2 — Never file a GitHub issue without explicit user approval

The skill drafts issue text on request; it does not file issues autonomously. After presenting the retrospective, the skill offers to file *only if asked*. Filing is opt-in, not opt-out. This is a cardinal guard-rail.

**Tracker boundary.** Filings target **`day8/re-frame2`'s GitHub issues** — the pair tool ships inside that monorepo (`skills/re-frame2-pair/` + `tools/re-frame2-pair-mcp/`), so both tool-side and framework friction file there, distinguished primarily in the title + body (an optional `pair-mcp` label reinforces tool-side friction only when the repo defines it — labels are never a filing precondition; see L3). `bd` (beads) is the re-frame2 monorepo's internal tracker and is never invoked from a published skill. The body is composed to a **fresh, per-filing temp file in the host OS's temp directory** with the `Write` tool — a nonce-carrying `$env:TEMP\…` on Windows or `${TMPDIR:-/tmp}/…` on POSIX, never a fixed `/tmp/issue-body.md` (which breaks on hosts without `/tmp` and lets concurrent filings collide) — and passed via `gh`'s native `--body-file` flag, never inline interpolation of transcript-derived text. See `skills/README.md` §Published-skill `allowed-tools` baseline for the canonical shape.

### L3 — Route the fix to the right layer

Both kinds of friction file against `day8/re-frame2`. The distinction is carried **primarily in the title + body**; a label optionally reinforces it **only when the repo defines it**:

- **pair-tool friction** (optional `--label pair-mcp`) — friction inside the pair tool: SKILL.md, scripts/MCP tools, recipes, structured results, attach/discovery brittleness, cross-platform handling.
- **framework friction** (optional `--label upstream-from-re-frame2-pair`, no `pair-mcp`) — friction caused by the framework's Tool-Pair contract: missing trace events, gaps in `epoch-history` / `restore-epoch` failure modes, missing registrar query surfaces, source-coordinate annotation gaps, schema-reflection shortcomings.

**Labels are optional taxonomy, never a filing precondition.** `gh issue create` fails the whole command on an unknown `--label`, and a consumer's repo (or this one, today) may not define `retro` / `pair-mcp` / `upstream-from-re-frame2-pair`. So the baseline filing command carries **no `--label`** and always succeeds; a label is added only after `gh label list` confirms it exists, passing only the present tokens, and a labelled create that fails an unknown-label check re-runs the no-label baseline so the issue still lands. The skill is explicit about the routing in the title + body regardless.

### L4 — Diagnosis-first workflow

The skill walks a six-step analysis: reconstruct goal → build timeline → extract friction → classify root cause → generate improvements at the right layer → prioritise. The order matters; jumping to step 5 without 1-3 produces speculative issues unsupported by session evidence.

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

### L10 — No internal `bd`/`rf2-XXXX` ids in user-facing skill content

`SKILL.md` + `references/` carry no `rf2-XXXX` references. Draft GitHub issues written by the skill cite ids the user asks about; the skill itself does not embed internal `bd` ids in its prose. The `spec/` folder may; user-facing content does not.

### L11 — Findings stay local

Per Mike's standing memory rule "Findings is local-only" — never commit `ai/` or `findings/`. This skill's commits contain only `skills/re-frame2-pair-retro/**`.

### L12 — Redact secrets before filing

GitHub-issue drafts redact secrets, tokens, internal URLs, and unnecessary local file paths. The skill summarises the evidence rather than dumping raw transcript.

## 4. Audience and scope

### In scope

- Users finishing a `re-frame2-pair` session who want a structured retrospective.
- Friction analysis: direct (user complaints) + indirect (repeated commands, fallback to lower-level tools, manual reconstruction).
- Classification across the nine lenses in `references/analysis-lenses.md`.
- Drafting GitHub issues against `day8/re-frame2`, distinguished in the title + body (an optional `pair-mcp` label reinforces tool changes only when the repo defines it — labels are never a filing precondition; see L3).
- Spotting recurring patterns via `references/known-frictions.md`.

### Out of scope

- Filing GitHub issues autonomously — L2.
- Routing through re-frame-10x — L1.
- Diagnosing the user's *application code* (that's `re-frame2-pair`'s job, in a live session).
- Authoring re-frame2 application code — `skills/re-frame2/`.
- Setting up a project — `skills/re-frame2-setup/`.
- Migrating from v1 — `skills/re-frame-migration/`.

## 5. File structure (locked)

```
skills/re-frame2-pair-retro/
├── SKILL.md (the conversation guide + workflow)
├── README.md (human-facing intro)
├── LICENSE (MIT)
├── package.json (npm metadata)
├── .claude-plugin/plugin.json (Claude Code plugin metadata)
├── agents/
│ └── openai.yaml (alt-host config — kept for cross-LLM operation)
├── evals/                          # repo-maintenance artifact; excluded from the npm `files` array
│ └── evals.json (trigger-accuracy fixtures — which prompts should / should not activate)
├── references/
│ ├── analysis-lenses.md (nine root-cause lenses + improvement shapes)
│ ├── known-frictions.md (recurring re-frame2-pair pain patterns)
│ ├── issue-template.md (GitHub-issue-body template, redaction rules)
│ └── working-style.md (diagnostic-posture rules; shipped operational home of §8)
└── spec/
 ├── design.md (this file)
 ├── inputs.md (canonical inputs)
 └── authoring-prompt.md (one-shot reauthor prompt)
```

Keep SKILL.md compact (well under Anthropic's 500-line ceiling) and each reference focused. A typical session reads SKILL.md plus at most one or two references (`analysis-lenses.md` when classification is hard, `known-frictions.md` when the session smells recurring) — never the whole tree.

## 6. Discovery surface (frontmatter `description`)

The `description` triggers on two canonical paths: (a) **explicit pull** — retrospective / improvement / friction phrases like *"retro on this pair session"*, *"what went wrong with my pair session"*, *"review my re-frame2-pair session"*, *"draft an issue about that"*; (b) **post-error within a re-frame2-pair session** — after a stack trace, failed dispatch, red CI, or an `:rf.error/*` event firing during live re-frame2-pair work, to post-mortem the immediate firefight. Acceptable evidence is either a concrete `re-frame2-pair` session in the conversation or a user-supplied recap / summary of one. The framing is conversational — discriminates against the live-app `re-frame2-pair` skill (which triggers on dispatch / app-db / epoch verbs), the authoring `re-frame2` skill (which triggers on `reg-*` surfaces), and the framework-pattern critique `re-frame2-improver` skill (which triggers on re-frame2 code shape, not pair-tool sessions).

## 7. Anti-patterns the skill explicitly resists

- **Routing fixes through re-frame-10x** — L1 cardinal rule.
- **Filing GitHub issues without explicit approval** — L2 cardinal rule.
- **Reducing every problem to "write more docs"** — L5 lenses include skill structure, structured op, default-or-fallback, validation, upstream — not just docs.
- **Confusing a transient local outage with a product gap** — L4 step 2 (build timeline) separates one-off env issues from recurring patterns.
- **Proposing vague improvements** ("better UX") without naming the concrete missing behaviour — L8 forces specifics.
- **Confusing creativity with hand-waving** — L6 bolder ideas need a concrete change and a believable path to value.
- **Forcing every retro into a code contribution** — L2 again; diagnosis is the deliverable.
- **Pressuring the user to file anything** — L2 again.

## 8. Working style (meta-process)

The pillars in §2 govern *what* the skill delivers; the **diagnostic-posture rules** govern *how* the AI conducts the retro conversation — evidence over vibes, symptom vs cause, direct/indirect friction, positive gaps, and creative ambition only after diagnosis. They lock the posture so the skill doesn't degenerate into vibes-driven editorialising or solutionism.

Their normative, **shipped** home is [`references/working-style.md`](../references/working-style.md) — a packaged `references/` leaf that `SKILL.md` cross-links and that loads during normal operation. This section keeps **no synced copy**: the rules were moved out of `spec/` precisely because `spec/` is excluded from the npm package / plugin bundle, so an operational `SKILL.md → spec/design.md §8` link would break in every packaged / plugin / vendored install. For the rules themselves, read the leaf.

## 9. Why this design diverges from `re-frame2-pair`

- **No structured-op catalogue.** This skill doesn't operate on a live app; it operates on a session transcript.
- **Diagnose-first `allowed-tools` block in frontmatter.** The skill is conversational and diagnose-first, so it ships an explicit allow-list. The precise contract is: `Read`, `Grep`, `Glob`, `Write` (solely to compose a fresh, per-filing OS-temp body file for `gh issue create --body-file`), `Bash(gh issue list *)`, `Bash(gh issue view *)`, `Bash(gh issue create *)`, `Bash(gh label list *)`, and one read-only MCP tool, `mcp__re-frame2-pair__discover-app`. It deliberately omits `Edit` and carries no `Bash(bd *)`: the skill never rewrites source, in this repo or another — friction routes to GitHub issues against the target repo, not edits. The one `Write` use is the shell-safety path — composing the transcript-derived issue body to a temp file so `--body-file` reads it verbatim and no shell expansion ever touches it (see [`../../shared/issue-filing.md` §Shell-safety](../../shared/issue-filing.md) and `skills/README.md` §Published-skill `allowed-tools` baseline). Removing `Write` would break the only documented no-shell-interpolation path for transcript-derived bodies. `Bash(gh label list *)` is read-only and exists for the optional-label degrade path: the skill detects which labels the target repo defines before passing any `--label`, and falls back to a no-label `gh issue create` so filing never fails on a missing label. `gh issue create` is granted but approval-gated (L2). `bd` is the re-frame2 monorepo's internal tracker and is never invoked from a published skill.
- **Single read-only MCP grant, use-gated to the opt-in probe.** Unlike the parent `re-frame2-pair` skill (which allow-lists the full live-runtime MCP surface), this skill grants exactly one re-frame2-pair MCP tool: the read-only `mcp__re-frame2-pair__discover-app`. SKILL.md §Guard rails and `references/known-frictions.md` §Tool-catalogue document an opt-in live probe that names this tool plus the server's `tools/list` (an MCP protocol method, implicitly available once any MCP tool is granted) — so the allow-list must carry it or the documented probe is unactionable in a published invocation. The grant is **use-gated**, not a default: it is reachable only when the retro is tied to an already-attached in-conversation live session and the user has confirmed a probe. Any deeper live work (dispatch, app-db read, epoch walk) routes to `re-frame2-pair`, never widens this grant. `check_skill_mcp_drift.py` enforces the symmetry: this skill's mapping marks every other re-frame2-pair tool `intentional_server_only`, so the gate fails if the prose names an opt-in tool the allow-list omits, or if the allow-list grows a phantom.
- **No connect-first rule.** Even with the single opt-in `discover-app` grant, the skill never connects first: the default path is transcript-only, and the probe is a confirmed exception, not an entry step.
- **`agents/openai.yaml` is included.** The skill is portable across LLM hosts; the openai config carries the routing for non-Claude hosts.
- **No `scripts/` directory.** The skill doesn't ship runtime tooling.

## 10. Open questions (deferred to Mike)

### OQ1 — Should the skill ship a "session capture" helper?

Currently the skill reads the in-conversation transcript. A future helper could snapshot the session to disk (redacted) for offline review. Status: deferred — no clear demand yet.

### OQ2 — Should `known-frictions.md` carry severity tagging?

Currently it lists patterns; ranking by "how often this surfaces" would help triage. Status: deferred — needs evidence from filed issues to rank credibly.

### OQ3 — Should the skill include a "post-mortem of a post-mortem" lens?

If the same friction surfaces across multiple retros, the skill could spot it. Currently the user does this manually by reading `references/known-frictions.md`. Status: deferred until the volume of retros makes the manual approach unwieldy.
