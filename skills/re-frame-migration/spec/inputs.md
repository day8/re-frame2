# re-frame-migration — Inputs

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame-migration` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

The canonical inputs the skill leans on. A re-authoring pass needs these to reproduce the leaves.

## 1. Primary input — `migration/from-re-frame-v1/README.md`

Path: `migration/from-re-frame-v1/README.md` in the re-frame2 repo.

**This is the source of truth.** Every M-rule and O-rule referenced in the skill's leaves comes from this doc. The skill's job is to **route**, **sequence**, and **operationalise** these rules — not to duplicate them.

Structure of MIGRATION.md the skill depends on:

- **Part 1** — the rule corpus. M-rules (required, `M-0` onward) plus O-rules (opt-in, `O-1` onward). Do not pin a hard numeric range here — it is a guaranteed drift point; the corpus grows. The skill's own [`references/breaking-changes.md`](../references/breaking-changes.md) carries the live index of every rule. Each rule has: type (A/B/hybrid), "What to look for", "What to do", "Why", and inline rule-id cross-refs. The "What stays the same" section near the end of Part 1 is also load-bearing — the skill cites it.
- **Part 2** — the AI-agent execution procedure. Written in second person to an agent performing the migration. Carries: "Your task", "How to apply rules — Type A vs Type B", "Verification steps", "What you must not do", "Output format for your report", "Maintainer note". The skill's kickoff prompt and output-format leaf both reference Part 2 directly.

When MIGRATION.md adds a new rule, the skill's `breaking-changes.md` index needs a new row. When MIGRATION.md changes a rule's type (Type A → Type B or vice versa), the skill's `sequencing.md` may need to move the rule between groups. When a new per-feature artefact is split out, the skill's `setup.md` "pay-as-you-go" table needs a row. Drift between MIGRATION.md and the skill is the maintenance burden; periodic audits (manual or beadwise) keep them aligned.

## 2. Secondary input — `docs/core/25-from-re-frame-v1.md`

The v1→v2 chapter in the narrative guide (the runtime leaves `xray-replaces-10x.md` and `README.md` link to this path).

This is the **human-facing** v1→v2 chapter in the narrative guide. It's not normative for the skill — it's pedagogy aimed at humans reading the guide. Useful as a sanity check that the skill's framing matches the guide's framing. Useful as a place to link to from the kickoff prompt ("if you want the human-readable overview before kicking off the agent, read this chapter first").

## 3. Tertiary inputs

These shape the skill's discipline but aren't quoted directly.

- **The four-pillar design rationale + Q14 lock** — inherited from the `re-frame2` skill family (the leaf-loading shape, the four pillars, and the Q14 lock — refined for this skill so it never *executes* builds/tests/smoke yet does teach + gate "done" on a read-only boot smoke-test; see [`design.md` §L3](design.md)). The original derivation lived in a local-only `ai/findings/` exploration doc (gitignored, not in-repo); the pillars and the refined Q14 lock are reproduced in full in [`design.md` §2 and §3 (L3)](design.md) so this `spec/` folder is self-contained.
- **`skills/re-frame2/SKILL.md`** + **`skills/re-frame2/references/**`** — the canonical example of the authoring pattern. Voice, structure, density, "load-bearing-rules" style all mirror this skill.
- **`skills/re-frame2-setup/SKILL.md`** + **`skills/re-frame2-setup/references/**`** — the closest structural analogue. Per-build-tool detail, the `LICENSE`/`package.json`/`.claude-plugin/plugin.json` triad, the README shape.
- **`docs/the-mayor-method/`** — the methodology context for how Mike works with AI. Influences the "the user runs their tests; the skill teaches them what's likely to break" framing (Q14 lock).
- **`SKILL-REDIRECT.md`** (repo root) — the canonical pointer table for AI skills. The migration skill references it as the place for full-API URLs and EP design rationale (rather than duplicating URLs in the leaves).

## 4. Anthropic skill conventions

Pulled from `https://code.claude.com/docs/en/skills` at authoring time.

The skill conforms to:

- `name` field ≤ 64 chars; lowercase + numbers + hyphens (`re-frame-migration`).
- `description` field is the discovery surface; "pushy" with explicit "use this skill whenever the user mentions" language; lists every v1 surface that should trigger.
- SKILL.md body well under 500 lines.
- Reference files one level deep from SKILL.md; no SKILL → A → B chains.
- Forward slashes in paths.
- Avoid time-sensitive content (deferred to dep-version lookups via `setup.md` rather than hardcoded VERSIONs).
- Per-tool `spec/` folder (per Mike's standing rule).

## 5. What the skill does NOT consume

These are deliberately out of the loop:

- **`spec/000-Vision.md`** through **`spec/014-HTTPRequests.md`** (the EP corpus) — the skill doesn't teach re-frame2; the `re-frame2` skill does. The migration skill assumes the author knows v2 conceptually (or will read the EPs separately).
- **`implementation/**`** — implementation is the ground truth for the `re-frame2` skill (what surfaces exist, what their signatures are). For the migration skill, MIGRATION.md is the ground truth, and MIGRATION.md is itself verified against implementation. The migration skill is downstream of that verification.
- **`examples/**`** — worked examples are for authoring new code, not for migrating. The migration skill does not point at examples.
- **The narrative guide** (other than `docs/core/25-from-re-frame-v1.md`) — too discursive for the migration agent's needs.

## 6. Update procedure

When MIGRATION.md changes, the skill needs targeted updates. The audit shape:

1. **New rule added** to MIGRATION.md → add a row to `references/breaking-changes.md` and pick its place in `references/sequencing.md`. If the rule is Type A, add a shape to whichever of `references/auto-call-site-rewrites.md` (per-call-site) or `references/auto-cross-cutting.md` (cross-cutting) fits the rule's shape. If Type B, add a walkthrough to whichever of `references/guided-handlers-state.md` (handlers / views / db-seeding) or `references/guided-interceptors-subs.md` (interceptors / subs / payloads) fits the rule's surface.
2. **Rule's type changed** (A → B or B → A) → move it between the relevant `auto-*.md` and `guided-*.md` leaves and update its row in `breaking-changes.md`.
3. **Rule removed / strikethrough'd** → mark its row in `breaking-changes.md` with strikethrough; remove the shape / walkthrough.
4. **New per-feature artefact** split out → add a row to `setup.md`'s pay-as-you-go table and to `auto-cross-cutting.md`'s per-feature add table.
5. **MIGRATION.md Part 2 changes** (the execution procedure) → audit `references/kickoff-prompt.md` and `references/output-format.md` for any phrasing that referenced the old shape.

The skill's `breaking-changes.md` is the integration point — every M-rule and O-rule in MIGRATION.md gets a row. A periodic audit greps both files and reports rules-in-MIGRATION-not-in-skill.

This section is the **mechanical** procedure: a known corpus change has happened, and these steps say which leaf to touch. It does not cover how you *discover* what the skill is missing in the first place, nor how to judge whether a candidate finding earns a change. That discovery layer — drive the skill against a real app, harvest friction (reaching runtime, where the high-value gaps hide), and clear each candidate against the generic-gate / routing / field-evidence quality bar — lives in [`improving.md`](improving.md).
