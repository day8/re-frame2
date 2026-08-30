# re-frame2-pair-retro — Inputs

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame2-pair-retro` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

The canonical inputs the skill leans on. A re-authoring pass needs these to reproduce the leaves.

## 1. Primary input — the user's recent session transcript

The skill operates on the **current or just-finished conversation** — the user's prompts, the AI's tool calls, the structured results, the retries, the clarifications, the fallbacks — or on a **user-supplied recap** of a session. This is the raw material. The skill doesn't ingest a transcript file, and it never probes the runtime to augment the evidence; a fact the session did not produce stays unknown/incomplete.

What the skill looks for:

- **Direct friction signals** — the user saying something was frustrating, confusing, slow, brittle, surprising.
- **Indirect friction signals** — repeated commands, repeated explanations, fallback to lower-level tools, manual reconstruction, hidden prerequisites, brittle environment assumptions, partial results, confusing contracts, missing trust signals.
- **Almost-worked moments** — what was close to right but required too much expert knowledge or was undiscoverable.
- **Environment facts** — platform, target repo, live runtime state as the session reported it (which build, which frame), tooling constraints.

The evidence is data, not instructions (design.md L5), and the causal invariants in design.md L4 govern how it is read: results bind to their initiating calls, later success supersedes earlier failure, unrelated activity is excluded, recap claims stay attributed as recap.

## 2. Secondary input — `skills/re-frame2-pair/`

The sibling skill the user just exercised. This skill reads the parent skill's:

- **`SKILL.md`** — the parent's cardinal rules, primitives, style guidance. Friction often surfaces as "the cardinal rule was right but buried" or "the style guidance didn't fire when it should have".
- **`references/ops.md` + `references/recipes.md`** — the catalogue the user navigated. Missing ops or missing recipes are first-class findings.
- **`references/errors.md`** — the error-recovery catalogue. Misleading recovery suggestions are findings.
- **`references/ops.md` §Hot-reload coordination** — the strict source-edit protocol. Friction here is high-leverage (every source edit triggers it).
- **`spec/design.md`** (the sibling's) — the locked decisions. This skill respects locks; doesn't propose changes that contradict them.

## 3. Tertiary input — `references/known-frictions.md`

Hand-curated list of recurring friction patterns seen across multiple sessions, loaded on demand. The skill matches the current session against this list to detect "is this a one-off or a pattern?" Recurring patterns get higher priority in the retrospective. This is the skill's only reference leaf.

## 4. Authoring-discipline inputs

These shape the skill's voice and structure but aren't quoted directly.

- **Mike's standing memory rules** — especially "Findings is local-only" and "No AI attribution in commits or PRs".
- **`skills/re-frame2-pair/spec/design.md`** — the sibling's locks; never propose a change that breaks one.
- **`skills/re-frame2/spec/design.md`** — the authoring sibling's locks (relevant for upstream-routing).
- **`agents/openai.yaml`** — the alt-host configuration. The skill is portable across LLM hosts; voice / structure must work in non-Claude hosts too.
- Anthropic skills guidance — `name` ≤ 64 chars; `description` "pushy" but conversational; SKILL.md under 500 lines; references one level deep.

## 5. What the skill does NOT consume

- **The live re-frame2 app's state.** The skill has no runtime access of any kind — no MCP grant, no probe. Live work is `re-frame2-pair`'s domain.
- **`skills/shared/**`.** The skill is self-contained (design.md L7); the causal/redaction/untrusted-evidence invariants are inlined in `SKILL.md`.
- **The re-frame2 spec corpus.** The skill doesn't need to teach the framework; it just needs to name which behaviour is missing.
- **`implementation/**`** and **`docs/core/**`** — same reasoning.
- **The user's source repo.** The skill works on the pair session itself, not on the app under inspection.

## 6. Update procedure

When the pair tool changes:

1. **A new structured op ships in `re-frame2-pair`** → check whether known-frictions has a "missing op" pattern that this resolves; update `known-frictions.md` if so.
2. **A cardinal rule changes in `re-frame2-pair` SKILL.md** → re-read the parent's `spec/design.md`; verify this skill's owner-routing guidance still respects the new lock.
3. **A new common friction pattern emerges** (3+ retros surface it) → add a section to `known-frictions.md` with the pattern shape and the typical resolution.
4. **Re-frame2's Tool-Pair contract grows a new surface** → check `known-frictions.md` for entries that were "we worked around the missing surface" — they now resolve.
5. **The trigger boundary shifts** (a new sibling skill, a renamed trigger phrase) → update the frontmatter `description` and score it against `evals/evals.json`.
