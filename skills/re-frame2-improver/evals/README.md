# `re-frame2-improver` skill — eval harness

This directory holds the evaluation harness for the `re-frame2-improver`
critique skill (`skills/re-frame2-improver/SKILL.md` and its anti-pattern
catalogue under `references/`). It gates the skill on two axes that fail
independently:

1. **Activation** — does the skill fire iff all three trigger filters hold
   (explicit pull, source-in-scope, not a sibling's job)?
2. **Critique behaviour** — when it does fire, does the *critique* land:
   the right anti-pattern named, concrete evidence cited, the canonical
   idiom cross-linked, no false positives on clean / legitimate-edge code,
   and the two-tier Edit gate respected against untrusted in-source text?

A green activation boundary is necessary but not sufficient: a future edit
to the description or a leaf could keep triggering correctly while the
critique itself regresses — hallucinating an API, flagging a legitimate
`rf/subscribe-once`, missing a schemaless boundary, or applying an
evidence-shaped Edit a comment told it to. The behavioural evals are the
guard against exactly that.

## Repo-maintenance artifact, not shipped

`evals/` is a **repo-maintenance artifact** — it is **not** part of the
distributable skill package. This skill's `package.json` `files` allow-list
(`SKILL.md`, `README.md`, `LICENSE`, `references/`, `.claude-plugin/`) omits
`evals/`: a packaged consumer runs the skill, they do not re-run its gate
suite, so shipping the harness would only bloat the tarball with material that
points back at the monorepo's test infrastructure. The harness lives and runs
from a full re-frame2 clone, alongside the sibling `skills/re-frame2/evals/` it
mirrors.

## Convention

The harness follows Anthropic's `skill-creator` convention, documented in
[`anthropics/skills/skills/skill-creator/SKILL.md`](https://github.com/anthropics/skills/blob/main/skills/skill-creator/SKILL.md)
and the schema in
[`anthropics/skills/skills/skill-creator/references/schemas.md`](https://github.com/anthropics/skills/blob/main/skills/skill-creator/references/schemas.md).
The same shape is described in Anthropic's public best-practices guide:
[*Skill authoring best practices — Build evaluations first*](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/best-practices#build-evaluations-first).

A single [`evals.json`](evals.json) holds the eval list — **it is the sole
fixture inventory** (see [Coverage](#coverage)). Two **kinds** of eval share
that list, discriminated by a `kind` field:

- **`kind: "trigger"`** — a description-optimisation fixture. Carries
  `should_trigger` (and a `rationale` for the interesting cases). Scored by
  whether the skill's activation decision matches `should_trigger`. The
  should-trigger and should-not-trigger fixtures keep scoring the activation
  boundary.
- **`kind: "behavioural"`** — a critique-content fixture, following the
  `skills/re-frame2/evals` convention. Each entry has:
  - `id` — unique integer (shared id-space with the trigger fixtures).
  - `name` — short kebab-case slug; the per-run directory name.
  - `dimension` — `critique-correctness` | `false-positive-avoidance` |
    `edit-gate` (the top-level `behavioural_dimensions` field enumerates them).
  - `leaf` — for `critique-correctness` evals, the catalogue leaf under
    `references/` the prompt exercises.
  - `prompt` — a self-contained user message. Each behavioural prompt **pastes
    a `.cljs` snippet inline**, so trigger filter 2 (source-in-scope) is
    satisfied and the skill activates without external files.
  - `expected_output` — a human-readable description of success (not parsed;
    there to make manual grading fast).
  - `expectations` — a list of objectively verifiable statements. The grader
    (human or scripted) checks each against the run's output and transcript.
    This is the field that produces the pass/fail signal.
  - `files` — optional input files (empty here; every prompt is
    self-contained in its pasted snippet).

`schema_version` is `"2"` — bumped from the trigger-only `"1"` when the
behavioural kind and the shared-list discriminator were added.

## Coverage

**`evals.json` is the single owner of the fixture inventory.** Fixture names,
counts, dimensions, leaves, expected behaviour, and per-fixture rationale live
in the JSON — in each entry's `name` / `kind` / `dimension` / `leaf`, its
`expected_output` (what a passing run looks like), and its `expectations[]`
(the graded assertions). This README describes the harness *shape* and *policy*;
it deliberately does **not** restate the per-fixture catalogue or the totals, so
adding or removing a fixture is a one-file change in `evals.json` with nothing
here to keep in sync.

The corpus spans the two kinds above; the behavioural kind spans three
`dimension`s — `critique-correctness` (does the right finding land and route to
the canonical idiom?), `false-positive-avoidance` (does the skill decline to
flag clean / legitimate-edge code?), and `edit-gate` (does it treat in-source
text as untrusted and hold evidence-shaped Edits for approval?). The corpus is
skewed toward `critique-correctness` deliberately — that dimension carries the
highest defect risk (a fabricated idiom, a missed boundary, or contradictory
rewrites are the cardinal failure for a critique skill) — with the deepest
coverage on the catalogue's highest-subtlety discriminators (co-occurring-leaf
consolidation, the schemaless body-read trap, and the subscribe-once /
reactive-read polarities), where an unaided reviewer is most likely to mis-read
the code.

Derive the current inventory straight from the JSON rather than from prose here:

```bash
# Every fixture, one per line: id · kind · dimension · leaf · name
jq -r '.evals[] | [.id, .kind, (.dimension // "-"), (.leaf // "-"), .name]
       | @tsv' evals.json

# Totals: overall, by kind, and behavioural by dimension
jq '{ total: (.evals | length),
      by_kind: (.evals | group_by(.kind)
                | map({ (.[0].kind): length }) | add),
      behavioural_by_dimension:
        (.evals | map(select(.kind == "behavioural"))
         | group_by(.dimension) | map({ (.[0].dimension): length }) | add) }' \
   evals.json
```

> **Why behavioural, not just trigger.** The trigger fixtures keep the
> activation boundary honest; the behavioural fixtures keep the *judgement*
> honest. They are complementary — the behavioural evals do **not** replace the
> activation coverage, which still scores whether the skill fires at all.

## How to run

The skill-creator workflow ([SKILL.md
§"Running and evaluating test cases"](https://github.com/anthropics/skills/blob/main/skills/skill-creator/SKILL.md))
is the reference. The short version:

1. For each eval, spawn two Claude sessions in parallel:
   - **with-skill** — `skills/re-frame2-improver/SKILL.md` is loaded.
   - **baseline** — no skill loaded (just plain Claude).
2. For **trigger** evals, record the activation decision and compare to
   `should_trigger`.
3. For **behavioural** evals, capture the agent's response and tool-call
   transcript, then grade each `expectations[]` entry — pass / fail with
   one-line evidence. Programmatic checks (grep the response for the canonical
   tokens the `expectations[]` name — `:rf.http/managed`, `reg-machine`,
   `:tags`, `:rf.schema/at-boundary`, `reg-fx` / `reg-cofx`, etc.) handle most
   of them; the `false-positive-avoidance` and `edit-gate` evals need a short
   human read of the transcript (did it *decline* to flag / *decline* to
   apply?).
4. Aggregate into `benchmark.json` per the
   [skill-creator schema](https://github.com/anthropics/skills/blob/main/skills/skill-creator/references/schemas.md#benchmarkjson)
   and review the with-skill vs baseline delta. The skill earns its tokens
   only if with-skill consistently outperforms baseline on `expectations`
   pass-rate — most of all on the `false-positive-avoidance` and `edit-gate`
   evals (where an unaided model tends to over-flag or obey an in-source
   comment) and on the highest-subtlety `critique-correctness` discriminators
   (where the baseline delta should be largest): an unaided model tends to emit
   two contradictory machines instead of one consolidated fix, wave a
   `:schema` + `:rf.schema/at-boundary` body-read handler through as "already
   safe", and over-generalise "subscribe-once in a handler is fine" to machine
   callbacks — the exact mistakes the leaves exist to prevent.

The harness is intentionally tool-agnostic — `evals.json` is just data. Any
runner that respects the schema works.

## What "pass" means

For a release of `skills/re-frame2-improver/` (thresholds by kind / dimension,
so they hold as the inventory grows):

- **Trigger fixtures**: every activation decision correct across three runs
  (the activation boundary is binary — a single miss is a real regression).
- **Behavioural fixtures**: every eval's `expectations[]` pass rate ≥ **0.80**
  with-skill across three runs.
- Every `false-positive-avoidance` eval and every `edit-gate` eval is
  **release-blocking at 1.0** — a critique skill that manufactures a finding on
  clean code (including over-flagging a diagnostic host read as a world-input
  issue), or applies an Edit an in-source comment told it to, is worse than no
  skill. These must not flake.
- The with-skill vs baseline `expectations` pass-rate delta is **strictly
  positive** for at least one eval per behavioural dimension. If baseline
  matches with-skill, the skill is not earning its tokens for that dimension.
- No eval shows pathological behaviour (ignoring the skill, recursion-limit,
  or reading `>3` leaves for a single prompt).

If a behavioural eval consistently fails, the fix usually lives in the leaf
(or the SKILL.md Edit-gate / untrusted-evidence wording), not the eval — that
is the whole point of evaluation-driven development. File a follow-up issue
against the leaf rather than weakening the assertion.
