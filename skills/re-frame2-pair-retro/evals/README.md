# `re-frame2-pair-retro` skill — eval harness

This directory holds two eval harnesses for the `re-frame2-pair-retro`
meta-skill (`skills/re-frame2-pair-retro/SKILL.md`):

1. **`evals.json` — trigger-accuracy fixtures.** Scores the skill's
   **activation boundary**: which prompts should trigger a `re-frame2-pair`
   retrospective and which should route elsewhere (vocab-only retros, live
   `re-frame2-pair` debugging, the `re-frame2-improver` static critique,
   greenfield `re-frame2-setup`, Story-recorder retros, etc.).
2. **`session-evidence-evals.json` + `score-session-evidence-eval.clj` —
   behavioural session-evidence eval.** Scores agent **runtime behaviour**
   against the `SKILL.md` §Session-evidence contract: does the retro bound to
   one causally-ordered session, build a causal ledger (not a transcript-order
   list), exclude unrelated worker/CI/shell/code-review/app-authoring activity,
   mark superseded state as superseded, mark partial results unknown/incomplete,
   and — when two plausible sessions are present — ask which to review rather
   than merging them? Its document-runnable fixture is
   [`fixtures/session-evidence-scoping.md`](fixtures/session-evidence-scoping.md).

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

## Behavioural session-evidence eval

`session-evidence-evals.json` scores agent **runtime behaviour** — the
tool-call sequence and the emitted text — against the `SKILL.md`
§Session-evidence contract, distinct from the trigger-accuracy `evals.json`
above (which scores only the activation decision). The agent-execution step
(replay a fixture against a fresh skill session and capture the transcript) is
manual/opt-in — no Claude-in-the-loop CI harness exists yet — but scoring is
automated:

1. Replay [`fixtures/session-evidence-scoping.md`](fixtures/session-evidence-scoping.md)
   (Scenario A, then Scenario B) against a fresh `re-frame2-pair-retro`
   session and capture the transcript per `session-evidence-evals.json`
   §harness.transcript_schema (`{"eval_id": N, "tool_calls": [...], "output":
   "..."}`).
2. Score it: `bb skills/re-frame2-pair-retro/evals/score-session-evidence-eval.clj <transcript.json>`.
3. Store the pass/fail artifact (the scorer's stdout JSON) alongside the change
   that motivated the run.

`bb .../score-session-evidence-eval.clj --self-test` validates the manifest +
scorer on every run as a cheap guard that the eval machinery itself hasn't
rotted. The always-on structural guard for the contract prose is
`skills/shared/tests/retro_protocol_test.clj`, which pins the load-bearing
phrasings of the contract and runs this scorer's `--self-test`. Not every
clause is deterministically scoreable from the transcript; the manifest's
`scored_vs_manual` block names the clauses that stay human-review-only.

Like `evals.json`, this harness is a **repo-maintenance artifact** — it is
**not shipped** (the `files` allow-list in `package.json` omits `evals/`), so a
packaged-skill consumer never carries it.
