# `re-frame2-pair-retro` skill — eval harness

This directory holds the trigger-accuracy eval fixtures for the
`re-frame2-pair-retro` meta-skill (`skills/re-frame2-pair-retro/SKILL.md`):

- `evals.json` — trigger-accuracy fixtures. Scores the skill's
  activation boundary: which prompts should trigger a `re-frame2-pair`
  retrospective and which should route elsewhere (vocab-only retros, live
  `re-frame2-pair` debugging, the `re-frame2-improver` static critique,
  greenfield `re-frame2-setup`, Story-recorder retros, and so on).

Behavioural verification is manual by design. The skill's runtime
contract — a clear session completes in one response; delayed results stay
bound to their initiating calls; later success supersedes earlier failure;
missing results stay unknown/incomplete; unrelated activity stays excluded;
two plausible sessions get one ask — is exercised by replaying
representative scenarios against a fresh session and reading the output.
There is no automated scorer and none should be added: the previous
regex-based session-evidence scorer accepted keyword soup before it was
repaired, and the repair cost more than the coverage was worth.

One deliberately narrow exception sits outside this directory:
`tests/duplicate_search_test.clj` (run `bb tests/duplicate_search_test.clj`
from the skill root) pins the §Issue drafts duplicate-search **command
contract** — the prescribed `gh issue list` query stays narrow to
`day8/re-frame2` and explicitly `--state all` (gh defaults to open-only,
which hides a closed owner), a discovered closed owner links instead of
twinning, and a failed query reads as "not checked", never "no duplicate".
It extracts the prescribed argv from `SKILL.md` verbatim and models gh's
documented state filtering over a fixed fixture set — a command-contract
pin, not a session-evidence scorer, and like everything here it is
repo-maintenance material the published package does not ship.

## Repo-maintenance artifact, not shipped

`evals/` is a repo-maintenance artifact — it is deliberately not part of
the distributable skill package. `skills/re-frame2-pair-retro/package.json`'s
`files` allow-list omits `evals/` (and `spec/`) on purpose: a packaged-skill
consumer runs the skill, they do not re-run its description-optimisation
loop, so shipping the fixtures would only bloat the tarball with material
that points back at the monorepo's maintenance workflow. The fixtures live
and run from a full re-frame2 clone, alongside the sibling
`skills/re-frame2/evals/` and `skills/re-frame2-setup/evals/` they mirror.
`npm pack --dry-run` from the skill directory lists no `evals/` files —
that is by design.

## Convention

**The wrapper is a repository convention, not an upstream schema.**
`schema_version` `"1"` names *this repo's* shape — an object
`{skill_name, schema_version, convention, notes, evals: […]}` carrying
trigger fields — shared with the sibling `skills/re-frame2/evals/` and
`skills/re-frame2-setup/evals/` corpora. Anthropic's `skill-creator`
defines **two different** formats, and this file is neither of them
verbatim (checked against upstream commit `3d5951151859`):

- **Task evaluation** —
  [`references/schemas.md` §evals.json](https://github.com/anthropics/skills/blob/3d59511518591fa82e6cfcf0438d68dd5dad3e76/skills/skill-creator/references/schemas.md#evalsjson)
  is an object `{skill_name, evals: [{id, prompt, expected_output, files?, expectations}]}`.
  It carries no `should_trigger` at all, so it cannot express a trigger corpus.
- **Trigger evaluation (description optimisation)** —
  [`SKILL.md` §Description Optimization](https://github.com/anthropics/skills/blob/3d59511518591fa82e6cfcf0438d68dd5dad3e76/skills/skill-creator/SKILL.md#description-optimization)
  takes a **top-level JSON list** of `{"query": …, "should_trigger": …}` objects.
  [`scripts/run_eval.py`](https://github.com/anthropics/skills/blob/3d59511518591fa82e6cfcf0438d68dd5dad3e76/skills/skill-creator/scripts/run_eval.py)
  iterates the loaded document directly and reads `item["query"]`.

This corpus borrows the task format's *wrapper* and fills it with trigger
fields, so it matches neither: handed to the trigger runner whole it dies
on `item["query"]` with a `TypeError` (the top level is an object, so
iteration yields key strings), and merely unwrapped to `payload["evals"]`
it dies with `KeyError: 'query'` (the field here is named `prompt`).
[§How to run](#how-to-run) carries the one-line conversion — run it, do not
hand `evals.json` to the loop directly.

Anthropic's public best-practices guide describes the evaluations-first
practice both formats serve:
[Skill authoring best practices — Build evaluations first](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/best-practices#build-evaluations-first).

Each entry in `evals` carries:

- `id` — unique integer
- `name` — short kebab-case slug, unique across the corpus; the per-run
  directory name when the harness runs (a duplicate slug would collide per-run
  directories / name-keyed reports). The repo's
  `scripts/check_skill_eval_docs.py` drift gate enforces both `id` and `name`
  uniqueness for every skill's `evals.json`.
- `should_trigger` — the expected activation decision (`true` for prompts that
  should fire the skill, `false` for prompts that should route elsewhere)
- `prompt` — a self-contained user message that exercises the boundary
- `rationale` — present on the interesting negatives, recording why the prompt
  must NOT trigger (which sibling skill owns it instead)

The positives target retro-on-a-pair-session prompts (including the harder
post-error post-mortem branch); the negatives target vocab-only retros, the
adjacent skills, the mid-pair error the user wants fixed (stays in
`re-frame2-pair`), and out-of-scope Story-recorder retros. The post-error
positives (ids 4, 17, 18) score only whether an explicit post-mortem request
activates the skill: trigger (b) proper — the unprompted one-line offer after
a pair tool result — is not a prompt a `should_trigger` corpus can express, so
it is verified by manual replay.

## How to run

The skill-creator description-optimisation loop
([SKILL.md §Description Optimization](https://github.com/anthropics/skills/blob/3d59511518591fa82e6cfcf0438d68dd5dad3e76/skills/skill-creator/SKILL.md#description-optimization))
is the reference: score the skill's activation decision against each entry's
`should_trigger`, and tune the frontmatter `description` until train/held-out
trigger accuracy holds.

**Convert first.** Per [§Convention](#convention) the loop reads a top-level
list of `query` / `should_trigger` objects, so drop this file's wrapper and
rename `prompt` → `query`:

```bash
jq '[.evals[] | {query: .prompt, should_trigger: .should_trigger}]' \
  evals/evals.json > trigger-eval.json
```

That emits one item per fixture, every prompt string and boolean label
preserved verbatim and nothing else — `id`, `name` and `rationale` are local
bookkeeping the loop neither reads nor needs. Feed the converted file:

```bash
python -m scripts.run_loop \
  --eval-set <abs-path>/trigger-eval.json \
  --skill-path <abs-path>/skills/re-frame2-pair-retro \
  --model <model-id> --max-iterations 5 --verbose
```

Write `trigger-eval.json` somewhere scratch, not into this directory. The
harness is otherwise tool-agnostic — `evals.json` is just data; any runner
fed the converted shape works.
