# `re-frame2` skill — eval harness

This directory holds the evaluation harness for the `re-frame2` authoring skill
(`skills/re-frame2/SKILL.md` and its leaves). It exists to gate v1.0 of the
skill: before we publish, every eval here should pass against a fresh Claude
session loaded with the skill.

## Convention

The harness follows Anthropic's `skill-creator` convention, documented in
[`anthropics/skills/skills/skill-creator/SKILL.md`](https://github.com/anthropics/skills/blob/main/skills/skill-creator/SKILL.md)
and the schema in
[`anthropics/skills/skills/skill-creator/references/schemas.md`](https://github.com/anthropics/skills/blob/main/skills/skill-creator/references/schemas.md).
The same shape is described in Anthropic's public best-practices guide:
[*Skill authoring best practices — Build evaluations first*](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/best-practices#build-evaluations-first).

A single `evals.json` file holds the eval list. Per Anthropic's schema:

- `skill_name` — must match the skill's frontmatter (`re-frame2`).
- `evals[]` — one entry per scenario. Each entry has:
  - `id` — unique integer.
  - `name` — short kebab-case slug; used as the per-run directory name when
    the harness is executed.
  - `prompt` — the user message that exercises the skill.
  - `expected_output` — a human-readable description of what success looks
    like (not parsed; it's there to make manual review fast).
  - `files` — optional list of input files (empty here; every prompt is
    self-contained).
  - `expectations` — a list of objectively verifiable statements. The
    grader (human or scripted) checks each one against the run's output and
    transcript. This is the field that produces the pass/fail signal.

Two harness extensions on top of the base schema:

- `dimension` — `discovery` | `recipe-correctness` | `routing-correctness`.
  Lets coverage be measured even when an eval contributes to more than one.
- `schema_version` — `"1"`. Bump when the eval shape changes in a way that
  breaks readers.

## Coverage

Six evals, covering the three dimensions the bead called for:

| ID | Name | Dimension | What it probes |
|---:|---|---|---|
| 1 | `discovery-no-name-mention-http-lifecycle` | discovery | Prompt mentions ClojureScript / Reagent and a fetch-with-revalidate problem but never says "re-frame2". Does the skill trigger from the description alone, and does the agent produce a Pattern-RemoteData slice with the `:loading` vs `:fetching` split? |
| 2 | `discovery-state-machine-websocket-lifecycle` | discovery | Prompt mentions a persistent connection with reconnect, backoff, queueing — no skill name. Does the skill trigger, and does the agent load `patterns/websocket.md` + `reference/state-machines/reg-machine.md` and use hierarchical compound state with `:invoke`? |
| 3 | `recipe-correctness-form-submit-lifecycle` | recipe-correctness | Login-form prompt. Does the output have the 7-key Pattern-Forms slice, the `:touched` ∨ `:submit-attempted?` gating rule, the two-channel error model (structured `:errors` vs transport `:submit-error`), and dual `reg-app-schema` registrations (slice schema + draft-value schema)? |
| 4 | `recipe-correctness-state-machine-http-region` | recipe-correctness | "Register a state machine for an HTTP request lifecycle." Does the output use `reg-machine` with the five canonical states (`:idle :loading :fetching :loaded :error`), `:tags` for an `:in-flight` query, keyword-referenced actions in the top-level table (inspectability bias), action effect-maps `{:data ...}`, and the `re-frame.machines` artefact require? |
| 5 | `routing-correctness-v1-migration` | routing-correctness | Prompt is a v1→v2 migration question. Does the agent route to `SKILL-REDIRECT.md` → *Migration from re-frame v1* rather than improvising migration deltas from training memory? |
| 6 | `routing-correctness-greenfield-scaffold` | routing-correctness | Prompt is "start a new re-frame2 project from scratch". Does the agent defer to the sibling `re-frame2-setup` skill rather than improvising `deps.edn` / `shadow-cljs.edn` from the authoring skill? |

Three is Anthropic's minimum. Six gives two evals per dimension, so any single
eval can flake without the dimension going dark.

## How to run

The skill-creator workflow ([SKILL.md
§"Running and evaluating test cases"](https://github.com/anthropics/skills/blob/main/skills/skill-creator/SKILL.md))
is the reference. The short version:

1. For each eval, spawn two Claude sessions in parallel:
   - **with-skill** — `skills/re-frame2/SKILL.md` is loaded.
   - **baseline** — no skill loaded (just plain Claude).
2. Capture the agent's response, the tool-call transcript, and any files it
   produces.
3. Grade each `expectations[]` entry against the captured output — pass / fail
   with one-line evidence. Programmatic checks (grep the response for the
   listed canonical tokens) handle most of them; the routing evals need a
   short human read of the transcript.
4. Aggregate into `benchmark.json` per the
   [skill-creator schema](https://github.com/anthropics/skills/blob/main/skills/skill-creator/references/schemas.md#benchmarkjson)
   and review the with-skill vs baseline delta. The skill is worth its tokens
   only if with-skill consistently outperforms baseline on `expectations`
   pass-rate.

The harness is intentionally tool-agnostic — `evals.json` is just data. Any
runner that respects the schema works. If we adopt skill-creator's runner
directly, point it at this directory and the workspace can live alongside
(`skills/re-frame2-workspace/`) without polluting the skill itself.

## What "pass" means for v1.0

For v1.0 release of `skills/re-frame2/`:

- Every eval's `expectations[]` pass rate ≥ **0.80** with-skill across three
  runs.
- The with-skill vs baseline pass-rate delta is **strictly positive** for at
  least one eval per dimension. (If baseline matches with-skill, the skill is
  not earning its tokens for that dimension.)
- No eval shows pathological behaviour (the agent ignoring the skill, hitting
  a recursion limit, or reading `>3` leaves for a single prompt).

If an eval consistently fails, the fix usually lives in the leaf, not the
eval — that's the whole point of evaluation-driven development. File a follow-
up bead against the leaf rather than weakening the assertion.
