# re-frame-pair-improver2 — Inputs

The canonical inputs the skill leans on. A re-authoring pass needs these to reproduce the leaves.

## 1. Primary input — the user's recent session transcript

The skill operates on the **current or just-finished conversation** — the user's prompts, the AI's tool calls, the structured results, the retries, the clarifications, the fallbacks. This is the raw material. The skill doesn't ingest a transcript file; it reads context that's already in the conversation.

What the skill looks for:

- **Direct friction signals** — the user saying something was frustrating, confusing, slow, brittle, surprising.
- **Indirect friction signals** — repeated commands, repeated explanations, fallback to lower-level tools (e.g. `repl/eval` when a structured op should have worked), manual reconstruction (the user explaining what they were trying to do twice), hidden prerequisites, brittle environment assumptions, partial results, confusing contracts, missing trust signals.
- **Almost-worked moments** — what was close to right but required too much expert knowledge or was undiscoverable.
- **Environment facts** — platform, target repo, live runtime state (which frame, which epoch depth), tooling constraints.

## 2. Secondary input — `skills/re-frame-pair2/`

The sibling skill the user just exercised. The improver reads the parent skill's:

- **`SKILL.md`** — the parent's cardinal rules, primitives, style guidance. Friction often surfaces as "the cardinal rule was right but buried" or "the style guidance didn't fire when it should have".
- **`references/ops.md` + `references/recipes.md`** — the catalogue the user navigated. Missing ops or missing recipes are first-class findings.
- **`references/errors.md`** — the error-recovery catalogue. Misleading recovery suggestions are findings.
- **`references/hot-reload-protocol.md`** — the strict source-edit protocol. Friction here is high-leverage (every source edit triggers it).
- **`scripts/`** + **MCP server** — the transport surface. Brittleness here breaks every session.
- **`spec/design.md`** (this skill's neighbour) — the locked decisions. The improver respects locks; doesn't propose changes that contradict them.

## 3. Tertiary input — `references/analysis-lenses.md`

The ten root-cause lenses the skill walks during classification:

| Lens | Question | Typical improvement |
|------|----------|---------------------|
| Skill structure | Was the right guidance present but buried? | Promote to guard rail, shorten, reorder, add examples |
| Skill gap | Was key guidance missing entirely? | Add a recipe, anti-pattern, decision rule |
| Misleading docs | Did docs suggest the wrong action or trust model? | Correct wording, add warnings, align contracts |
| Missing structured op | Did the workflow need a first-class op? | Add a script/runtime op or structured field |
| Unreliable op | Did an op behave ambiguously or brittlely? | Fix behavior, add warnings, strengthen validation |
| Default or fallback | Was the default path wrong, silent, or unsafe? | Change defaults or automate the safer fallback |
| Platform bug | Did the workflow break on a specific shell/OS/browser? | Add platform-aware handling or explicit detection |
| Validation gap | Did this ship because the right fixture/test is missing? | Add test coverage or fixture support |
| Upstream limitation | Is `re-frame-pair2` working around the wrong abstraction in `re-frame2`? | File a `bd` bead against `re-frame2` |
| Context-window issue | Did long context or low-salience guidance cause forgetfulness? | Make the guard rail shorter, earlier, stronger |

This is the canonical taxonomy; the improver doesn't invent ad-hoc categories.

## 4. Tertiary input — `references/known-frictions.md`

Hand-curated list of recurring friction patterns the skill has seen across multiple sessions. The improver matches the current session against this list to detect "is this a one-off or a pattern?" Recurring patterns get higher priority in the retrospective.

## 5. Tertiary input — `references/issue-template.md`

The bead-body template the skill uses when the user asks for a draft. Carries:

- The friction summary (1-2 sentences).
- The evidence (session moments — redacted).
- The proposed change.
- The layer (skill / script / runtime / tests / docs / upstream).
- The impact statement.

Redaction rules are baked in (no secrets, tokens, internal URLs, unnecessary local paths).

## 6. Authoring-discipline inputs

These shape the skill's voice and structure but aren't quoted directly.

- **Mike's standing memory rules** — especially "Findings is local-only", "No AI attribution in commits or PRs", "Always dispatch, don't ask" (the improver does NOT auto-dispatch; L2 explicit guard-rail).
- **`skills/re-frame-pair2/spec/design.md`** — the sibling's locks. The improver references these when proposing changes; never proposes a change that breaks a lock.
- **`skills/re-frame2/spec/design.md`** — the parent skill's locks (relevant for upstream-routing).
- **`agents/openai.yaml`** — the alt-host configuration. The skill is portable across LLM hosts; voice / structure must work in non-Claude hosts too.
- Anthropic skills guidance — `name` ≤ 64 chars; `description` "pushy" but conversational; SKILL.md under 500 lines; references one level deep.

## 7. What the skill does NOT consume

- **The live re-frame2 app's state.** That's the `re-frame-pair2` skill's domain. The improver works on session transcripts, not on `app-db` snapshots.
- **The re-frame2 spec corpus.** The improver doesn't need to teach the framework; it just needs to know which surface is missing.
- **`implementation/**`** — same reasoning.
- **`docs/guide/**`** — the narrative guide is for application authors learning re-frame2. The improver works one level up.
- **The user's source repo.** The improver works on the pair-session itself, not on the app under inspection.

## 8. Update procedure

When the pair tool changes:

1. **A new structured op ships in `re-frame-pair2`** → check whether known-frictions has a "missing op" pattern that this resolves; update `known-frictions.md` if so.
2. **A bash shim is retired / migrated to MCP** → `known-frictions.md` may have entries about shim brittleness; update to reflect.
3. **A cardinal rule changes in `re-frame-pair2` SKILL.md** → re-read the parent's `spec/design.md`; verify the improver's lens-routing still respects the new lock.
4. **A new common friction pattern emerges** (3+ retros surface it) → add a row to `known-frictions.md` with the pattern shape and the typical resolution.
5. **A new analysis lens is named** → add to `analysis-lenses.md` with the canonical question and improvement shape.
6. **The bead-filing process changes** (e.g. `bd` tool surface changes) → update `issue-template.md`.
7. **Re-frame2's Tool-Pair contract grows a new surface** (e.g. a new `register-*-cb` hook) → the improver may need to know about it for upstream-routing decisions; check `known-frictions.md` for entries that were "we worked around the missing surface" — they now resolve.
