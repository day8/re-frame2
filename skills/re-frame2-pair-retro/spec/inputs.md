# re-frame2-pair-retro — Inputs

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame2-pair-retro` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

The canonical inputs the skill leans on. A re-authoring pass needs these to reproduce the leaves.

## 1. Primary input — the user's recent session transcript

The skill operates on the **current or just-finished conversation** — the user's prompts, the AI's tool calls, the structured results, the retries, the clarifications, the fallbacks. This is the raw material. The skill doesn't ingest a transcript file; it reads context that's already in the conversation.

What the skill looks for:

- **Direct friction signals** — the user saying something was frustrating, confusing, slow, brittle, surprising.
- **Indirect friction signals** — repeated commands, repeated explanations, fallback to lower-level tools (e.g. `repl/eval` when a structured op should have worked), manual reconstruction (the user explaining what they were trying to do twice), hidden prerequisites, brittle environment assumptions, partial results, confusing contracts, missing trust signals.
- **Almost-worked moments** — what was close to right but required too much expert knowledge or was undiscoverable.
- **Environment facts** — platform, target repo, live runtime state (which frame, which epoch depth), tooling constraints.

## 2. Secondary input — `skills/re-frame2-pair/`

The sibling skill the user just exercised. The improver reads the parent skill's:

- **`SKILL.md`** — the parent's cardinal rules, primitives, style guidance. Friction often surfaces as "the cardinal rule was right but buried" or "the style guidance didn't fire when it should have".
- **`references/ops.md` + `references/recipes.md`** — the catalogue the user navigated. Missing ops or missing recipes are first-class findings.
- **`references/errors.md`** — the error-recovery catalogue. Misleading recovery suggestions are findings.
- **`references/ops.md` §Hot-reload coordination** — the strict source-edit protocol (folded into the op catalogue, not a standalone leaf). Friction here is high-leverage (every source edit triggers it).
- **`scripts/`** + **MCP server** — the transport surface. Brittleness here breaks every session.
- **`spec/design.md`** (this skill's neighbour) — the locked decisions. The improver respects locks; doesn't propose changes that contradict them.

## 3. Tertiary input — `references/analysis-lenses.md`

The nine root-cause lenses the skill walks during classification — the canonical names live in `references/analysis-lenses.md §Root-cause categories`; this table mirrors them:

| Lens | Question | Typical improvement |
|------|----------|---------------------|
| `docs/discoverability` | The feature or prerequisite existed, but could the user find or trust it? | Promote to guard rail, correct wording, add warnings, align contracts |
| `workflow-gap` | Did the instructions / recipes guide the user through a common task? | Add a recipe, anti-pattern, decision rule |
| `missing-op` | Did the workflow need a first-class operation that does not exist? | Add a script/runtime op or structured field |
| `unreliable-op` | Was an existing operation too brittle or ambiguous? | Fix behavior, add warnings, strengthen validation |
| `default/fallback` | Was the default behavior wrong, silent, or unsafe? | Change defaults or automate the safer fallback |
| `platform-bug` | Did the workflow break on a specific OS / shell / browser? | Add platform-aware handling or explicit detection |
| `validation-gap` | Did this ship because the repo lacks the right smoke test, fixture, or warning? | Add test coverage or fixture support |
| `upstream-gap` | Does the best fix belong in `re-frame2` itself (Tool-Pair contract, instrumentation, schema reflection, epoch machinery, source-coord)? | File a GitHub issue against `day8/re-frame2` |
| `out-of-scope` | Did the user want something `re-frame2-pair` should probably not own? | Route elsewhere; decline cleanly |

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
- **`skills/re-frame2-pair/spec/design.md`** — the sibling's locks. The improver references these when proposing changes; never proposes a change that breaks a lock.
- **`skills/re-frame2/spec/design.md`** — the parent skill's locks (relevant for upstream-routing).
- **`agents/openai.yaml`** — the alt-host configuration. The skill is portable across LLM hosts; voice / structure must work in non-Claude hosts too.
- Anthropic skills guidance — `name` ≤ 64 chars; `description` "pushy" but conversational; SKILL.md under 500 lines; references one level deep.

## 7. What the skill does NOT consume

- **The live re-frame2 app's state.** That's the `re-frame2-pair` skill's domain. The improver works on session transcripts, not on `app-db` snapshots.
- **The re-frame2 spec corpus.** The improver doesn't need to teach the framework; it just needs to know which surface is missing.
- **`implementation/**`** — same reasoning.
- **`docs/guide/**`** — the narrative guide is for application authors learning re-frame2. The improver works one level up.
- **The user's source repo.** The improver works on the pair-session itself, not on the app under inspection.

## 8. Update procedure

When the pair tool changes:

1. **A new structured op ships in `re-frame2-pair`** → check whether known-frictions has a "missing op" pattern that this resolves; update `known-frictions.md` if so.
2. **A bash shim is retired / migrated to MCP** → `known-frictions.md` may have entries about shim brittleness; update to reflect.
3. **A cardinal rule changes in `re-frame2-pair` SKILL.md** → re-read the parent's `spec/design.md`; verify the improver's lens-routing still respects the new lock.
4. **A new common friction pattern emerges** (3+ retros surface it) → add a row to `known-frictions.md` with the pattern shape and the typical resolution.
5. **A new analysis lens is named** → add to `analysis-lenses.md` with the canonical question and improvement shape.
6. **The bead-filing process changes** (e.g. `bd` tool surface changes) → update `issue-template.md`.
7. **Re-frame2's Tool-Pair contract grows a new surface** (e.g. a new `register-*-cb` hook) → the improver may need to know about it for upstream-routing decisions; check `known-frictions.md` for entries that were "we worked around the missing surface" — they now resolve.
