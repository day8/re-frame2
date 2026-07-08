# `re-frame2` skill — eval harness

This directory holds the evaluation harness for the `re-frame2` authoring skill
(`skills/re-frame2/SKILL.md` and its leaves). It exists to gate v1.0 of the
skill: before we publish, every eval here should pass against a fresh Claude
session loaded with the skill.

## Repo-maintenance artifact, not shipped

`evals/` is a **repo-maintenance artifact** — it is **not** part of
the distributable skill package. `skills/re-frame2/package.json`'s `files`
allow-list omits `evals/`: a packaged-skill consumer runs the skill,
they do not re-run its gate suite, so shipping the harness would only bloat the
tarball with material that points back at the monorepo's test infrastructure.
The harness lives and runs from a full re-frame2 clone (where
`scripts/check_skill_eval_docs.py` and any future runner can reach it). The
top-level `skills/re-frame2/README.md` §Status notes the harness has landed
"under `evals/`" as a repo fact — that link resolves in a clone, which is the
only supported way to run the evals. It is intentionally absent from the
published package.

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

Eleven evals, covering the three dimensions:

| ID | Name | Dimension | What it probes |
|---:|---|---|---|
| 1 | `discovery-no-name-mention-http-lifecycle` | discovery | Prompt mentions ClojureScript / Reagent and a fetch-with-revalidate problem but never says "re-frame2". Does the skill trigger from the description alone, and does the agent produce a Pattern-RemoteData slice with the `:loading` vs `:fetching` split? |
| 2 | `discovery-state-machine-websocket-lifecycle` | discovery | Prompt mentions a persistent connection with reconnect, backoff, queueing — no skill name. Does the skill trigger, and does the agent load `patterns/websocket.md` + `references/state-machines/reg-machine.md` and use hierarchical compound state with `:spawn`? |
| 3 | `recipe-correctness-form-submit-lifecycle` | recipe-correctness | Login-form prompt. Does the output have the 7-key Pattern-Forms slice, the `:touched` ∨ `:submit-attempted?` gating rule, the two-channel error model (structured `:errors` vs transport `:submit-error`), and dual `reg-app-schema` registrations (slice schema + draft-value schema)? |
| 4 | `recipe-correctness-state-machine-http-region` | recipe-correctness | "Register a state machine for an HTTP request lifecycle." Does the output use `reg-machine` with the five canonical states (`:idle :loading :fetching :loaded :error`), `:tags` for an `:in-flight` query, keyword-referenced actions in the top-level table (inspectability bias), action effect-maps `{:data ...}`, and the `re-frame.machines` artefact require? |
| 5 | `routing-correctness-v1-migration` | routing-correctness | Prompt is a v1→v2 migration question. Does the agent hand off to the dedicated `re-frame-migration` skill (per the single-source routing table in `skills/README.md` and this skill's SKILL.md disqualifier) rather than improvising migration deltas from training memory? |
| 6 | `routing-correctness-greenfield-scaffold` | routing-correctness | Prompt is "start a new re-frame2 project from scratch". Does the agent defer to the sibling `re-frame2-setup` skill rather than improvising `deps.edn` / `shadow-cljs.edn` from the authoring skill? |
| 7 | `recipe-correctness-story-recorder-sensitive-login` | recipe-correctness | Story-recorder privacy prompt (login + 2FA into a `:script`). Does the agent teach the CURRENT (path-based, fail-open) owner-classification contract — recorder filters off the emitted trace event's `:sensitive?`, stamped by the runtime from OWNER-declared path classification (a durable app-db secret via the writing event's `:sensitive` classification effect; the transient 2FA payload key in the submit handler's REGISTRATION `:sensitive` metadata), NOT handler metadata — and EXPLICITLY REJECT handler-meta `{:sensitive? true}` (a no-op) and the retired `redact-interceptor` / `add-marks` / frame `:sensitive {:app-db …}` annotation / schema-attached app-db marks as the mechanism? |
| 8 | `recipe-correctness-story-variant-authoring-handoff` | recipe-correctness | Story variant authoring through the hybrid split. The prompt asks to author a variant AND "run it and keep iterating against the running library". Does the agent author with only the tools `re-frame2` is allow-listed for (`register-variant` / `preview-variant` / `get-variant` / `explain-variant`), and **hand off** the run/self-heal loop (`run-variant` / `read-failures`) to `re-frame2-pair` rather than claiming to call tools it cannot reach? Fails if the documented loop requires run-side tools unavailable to this skill. |
| 9 | `recipe-correctness-resource-scoped-read-lifecycle` | recipe-correctness | Tenant-scoped billing-summary read shared across three screens (`patterns/resources.md`). Does the output register it with `reg-resource` under a REQUIRED fail-closed `:scope` (a `reg-resource-scope` named resolver referenced `{:from-db …}`, NOT `:rf.scope/global`, never omitted), read it PASSIVELY via a `[:rf/resource …]` sub, and CAUSE the fetch from a route `:resources` entry / `[:rf.resource/ensure …]` (owner + cause) — never from a view — while clearing the old scope causally on tenant switch (`resolve-resource-scope` + `:rf.resource/clear-scope`, no `:snapshot-db`) and never hand-rolling the cache key (CEDN-1)? |
| 10 | `recipe-correctness-mutation-reply-to-workflow` | recipe-correctness | Article-save flow with post-success navigate + toast + field-error folding, concurrent saves (`patterns/resources-mutations.md`). Does the output use `reg-mutation` via `[:rf.mutation/execute …]` keyed by `:instance`, and keep the two axes apart — cache consequences (`:invalidates` list / `:populates` detail) DECLARATIVE on `reg-mutation`, app workflow in a call-site `:reply-to` handler (a causal event target reading the appended `{:status :value :error}` reply map, NOT a callback, NOT registration-level) — rejecting workflow-on-`reg-mutation` and the component-watcher idiom? |
| 11 | `recipe-correctness-mutation-optimistic-mixed-scope` | recipe-correctness | Optimistic favorite (instant heart/count, clean rollback) invalidating a global article fact AND the session-scoped feed (`patterns/resources-mutations.md`). Does the output declare an `:optimistic` / `:optimistic-tags` plan (params / old-data, NO `result` arg) relying on the runtime-recorded inverse (no hand-written rollback), express the mixed-scope invalidation as per-target DESCRIPTORS (`{:scope :rf.scope/global …}` + `{:scope {:from-db …} :tags #{[:feed]}}`) rather than `:cross-scope? true`, and never `assoc` into `:rf.runtime/resources`? |

Three is Anthropic's minimum. Eleven gives **seven** recipe-correctness evals
and **two** each for discovery and routing-correctness, so every dimension keeps
multi-eval coverage and any single eval can flake without the dimension going
dark. The skew toward recipe-correctness reflects that dimension's higher defect
risk (idiom drift in produced code) — including the Resources/mutations recipe
surface (evals 9–11), the most idiom-dense recipe area the skill teaches: the
fail-closed mandatory `:scope`, the passive-view / causal-fetch split, call-site
`:reply-to` workflow vs declarative cache consequences, and optimistic plans with
runtime-recorded inverses — plus the Story-recorder privacy contract eval 7
guards and the Story authoring/run boundary eval 8 guards.

> **Staying in sync.** The table above, the eval count in this paragraph, and
> the per-dimension breakdown are checked against `evals.json` by
> `scripts/check_skill_eval_docs.py` (run it after adding or renaming an eval).
> The gate fails if the README count, the dimension tallies, or the set of eval
> names drifts from the JSON — so a new eval cannot silently stale these docs.

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
eval — that's the whole point of evaluation-driven development. Fix the leaf
rather than weakening the assertion.
