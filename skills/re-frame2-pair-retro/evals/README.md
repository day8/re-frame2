# `re-frame2-pair-retro` skill — eval harness

This directory holds the trigger-accuracy fixtures for the `re-frame2-pair-retro`
meta-skill (`skills/re-frame2-pair-retro/SKILL.md`). The single `evals.json`
file scores the skill's **activation boundary**: which prompts should trigger a
`re-frame2-pair` retrospective and which should route elsewhere (vocab-only
retros, live `re-frame2-pair` debugging, the `re-frame2-improver` static
critique, greenfield `re-frame2-setup`, Story-recorder retros, etc.).

## Repo-maintenance artifact, not shipped

`evals/` is a **repo-maintenance artifact** — it is deliberately **not** part of
the distributable skill package. `skills/re-frame2-pair-retro/package.json`'s
`files` allow-list omits `evals/` (and `spec/`) on purpose: a packaged-skill
consumer runs the skill, they do not re-run its description-optimisation loop, so
shipping the fixtures would only bloat the tarball with material that points back
at the monorepo's maintenance workflow. The fixtures live and run from a full
re-frame2 clone, alongside the sibling `skills/re-frame2/evals/` and
`skills/re-frame2-setup/evals/` they mirror. `npm pack --dry-run` from the skill
directory lists no `evals/` files — that is by design, and it matches the
`re-frame2`, `re-frame2-setup`, and `re-frame2-pair` siblings. (The
`re-frame2-improver` and `re-frame2-xray` siblings make the opposite, equally
valid, choice — they carry `evals/` in `files` so a vendored copy can re-run its
own gate; either stance is fine as long as the skill's docs and its `files` array
agree.)

## Convention

The fixtures follow Anthropic's `skill-creator` convention, documented in
[`anthropics/skills/skills/skill-creator/SKILL.md`](https://github.com/anthropics/skills/blob/main/skills/skill-creator/SKILL.md)
and the schema in
[`anthropics/skills/skills/skill-creator/references/schemas.md`](https://github.com/anthropics/skills/blob/main/skills/skill-creator/references/schemas.md).
The same shape is described in Anthropic's public best-practices guide:
[*Skill authoring best practices — Build evaluations first*](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/best-practices#build-evaluations-first).

A single `evals.json` holds a **trigger-only** fixture list (`schema_version`
`"1"`). Each entry carries:

- `id` — unique integer.
- `name` — short kebab-case slug, **unique** across the corpus; the per-run
  directory name when the harness runs (a duplicate slug would collide per-run
  directories / name-keyed reports). The repo's
  `scripts/check_skill_eval_docs.py` drift gate enforces both `id` and `name`
  uniqueness for every skill's `evals.json`.
- `should_trigger` — the expected activation decision (`true` for prompts that
  should fire the skill, `false` for prompts that should route elsewhere).
- `prompt` — a self-contained user message that exercises the boundary.
- `rationale` — present on the interesting negatives, recording why the prompt
  must NOT trigger (which sibling skill owns it instead).

The positives target retro-on-a-pair-session prompts (including the harder
post-error post-mortem branch); the negatives target vocab-only retros, the
adjacent skills, the mid-pair error the user wants fixed (stays in
`re-frame2-pair`), and out-of-scope Story-recorder retros.

## How to run

The skill-creator description-optimisation loop ([SKILL.md
§"Running and evaluating test cases"](https://github.com/anthropics/skills/blob/main/skills/skill-creator/SKILL.md))
is the reference: score the skill's activation decision against each entry's
`should_trigger`, and tune the frontmatter `description` until train/held-out
trigger accuracy holds. The harness is intentionally tool-agnostic —
`evals.json` is just data; any runner that respects the schema works.
