# re-frame2-setup — Design

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame2-setup` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

The design rationale and locked decisions for the `re-frame2-setup` skill. A future agent could re-author this skill from this folder alone.

## 1. Goal

Help an author **bootstrap a fresh re-frame2 ClojureScript project**. The author starts with nothing — or close to it — and ends with a working browser app that compiles under `shadow-cljs watch` and mounts a counter. From there, the author switches to the main `re-frame2` skill for application-code authoring.

The success criterion: `npx shadow-cljs watch app` compiles cleanly, the browser shows a counter, clicking `+1` increments it. The skill stops at that point — anything beyond setup is another skill's job.

## 2. Pillars (locked, derived from `re-frame2`'s four pillars)

The same four pillars as the `re-frame2` skill, scoped to greenfield bootstrap:

1. **Correctness — recipes over explanations.** Walks through the exact deps, the exact `shadow-cljs.edn` shape, the exact entry-namespace contract. The author copies the recipe; doesn't re-derive it. **Q14 lock applies: NO verification module** — the author runs the build; the skill doesn't.
2. **Idiomaticness — verified against `examples/core/counter/` and the canonical artefacts.** The first-counter shape mirrors `examples/core/counter/core.cljs` trimmed for greenfield. Dep coords match what `day8/re-frame2` ships.
3. **Context economy — `SKILL.md` is a router; four one-level-deep leaves carry the depth.** SKILL.md walks the seven-step canonical path; leaves carry per-step depth.
4. **Assume training knowledge.** The author knows `deps.edn`, `npm`, `shadow-cljs`, what a Reagent component is. The skill teaches only the **re-frame2-specific wiring** — which artefacts to add, the `rf/init!` contract, the order of operations between adapter install and React mount.

## 3. Locked decisions

These are not up for re-litigation. A future authoring pass MUST preserve them unless explicitly unlocked by Mike.

### L1 — Never hardcode the re-frame2 artefact VERSION in suggestions written to disk

re-frame2 ships eleven Maven artefacts in lockstep at a single VERSION (core + 7 per-feature + 3 per-adapter per `spec/Conventions.md`; `day8/re-frame2-xray` rides the same line). The re-frame2 VERSION changes. The skill leaves it as `<VERSION>` and points the author at `references/deps-versions.md` for lookup; the cardinal rule lives in SKILL.md so it's read on every load. Hardcoded re-frame2 VERSIONs in suggestions are a documented anti-pattern. (The non-re-frame2 pins — Clojure/ClojureScript/Reagent — are pinned to concrete versions matching the generator template, since those are slow-moving and the template fixes them.)

### L2 — All eleven artefacts ship at the same VERSION

The author picks the VERSION once; every `day8/re-frame2-*` dep (including `-xray`) gets that same version. Mixing versions across artefacts is unsupported. This rule lands in both SKILL.md and `references/deps-versions.md`.

### L3 — Day-one shape matches the generator template; remaining per-feature artefacts are pay-as-you-go

The day-one deps match the deps-new template: core (`day8/re-frame2`), the Reagent adapter (`day8/re-frame2-reagent`), `-schemas` (the starter app attaches a whole-app-db schema), and `-xray` (the in-app devtools panel, Xray-priority by default), plus an explicit `reagent/reagent` pin. The remaining per-feature artefacts (`-machines`, `-routing`, `-flows`, `-http`, `-ssr`, `-epoch`) come in **only when the author starts using the feature**. The skill resists "add them all defensively" — pay-as-you-go is the contract for everything past the day-one shape.

### L4 — The Reagent adapter is the default reference substrate; UIx/Helix get the two-substitution recipe

Unless the author explicitly says UIx or Helix, scaffold against Reagent — Reagent v2 is the canonical default. The skill does not branch into a full multi-substrate decision tree at greenfield, but it **does** cover UIx/Helix greenfield: `references/entry-namespace.md` §UIx / Helix greenfield gives the adapter substitutions (deps swap + entry-ns root API + substrate views) plus worked UIx/Helix code, and points at the generator template's complete `_uix/` / `_helix/` variants. SKILL.md cardinal rule 4 and README.md §"What it deliberately does NOT cover" carry the same pointer.

### L5 — Don't write tests for the author

The skill stops at "the counter mounts". Anything after that — events, subs, machines, schemas, test-authoring — is the main `re-frame2` skill (which itself defers test-writing to the author per its own Q14 lock).

### L6 — Q14 — NO verification module

Consistent with the `re-frame2` skill: no `references/verify.md`, no "verify before claiming done" hard rule. The Done checklist in SKILL.md lists the conditions; the author confirms. The skill never asserts completion.

### L7 — No bead-ids in user-facing skill content

`SKILL.md` + `references/` carry no `rf2-XXXX` references. The `spec/` folder may; user-facing leaves do not.

### L8 — Findings stay local

Per Mike's standing memory rule "Findings is local-only" — never commit `ai/` or `findings/`. This skill's commits contain only `skills/re-frame2-setup/**`.

### L9 — Single-import contract from day one

The first-counter recipe imports `re-frame.core` as `rf` and `re-frame.adapter.reagent` as `reagent-adapter`. No private-namespace requires; no `re-frame.db` style reach-ins. The contract the author starts with is the contract the main `re-frame2` skill enforces from there on.

### L10 — Clean hand-off on exit; cross-skill routing is single-sourced in `skills/README.md`

SKILL.md ends with a hand-off paragraph (to `re-frame2` for code-writing, `re-frame2-xray` for the panel, `re-frame2-pair` for live REPL) plus an "anything else → `re-frame2` / `SKILL-REDIRECT.md`" line. It carries **no** per-skill routing table — cross-skill routing is single-sourced in [`skills/README.md` §Skill routing](../../README.md#skill-routing--single-source) per the family convention. The author leaves this skill confidently for the next one rather than stretching it to cover authoring questions.

## 4. Audience and scope

### In scope

- Authors starting a new directory (or an existing CLJS project) that needs re-frame2 wiring.
- The seven canonical steps: discover versions → `deps.edn` → `package.json` → `shadow-cljs.edn` → entry ns → first counter → run.
- Reagent v2 as the default substrate.
- Troubleshooting the common build failures (SKILL.md's Troubleshooting section: missing `.cljs` namespace vs missing npm React, missing `rf/init!`, missing `<div id="app">`, `:init-fn` mismatch, Xray host missing, and more).

### Out of scope

- Migrating from re-frame v1 → `skills/re-frame-migration/`.
- Authoring application code beyond the first counter → `skills/re-frame2/`.
- Live-runtime debugging → `skills/re-frame2-pair/`.
- Building re-frame2 in a different host language → `skills/re-frame2-implementor/`.
- Full multi-substrate decision trees at greenfield — Reagent is the default. UIx/Helix greenfield is **in scope** at the recipe level: `references/entry-namespace.md` §UIx / Helix greenfield gives the two adapter substitutions plus the generator-template pointer (see L4).
- Writing tests, registering events, subs, machines, schemas — all the main `re-frame2` skill's job.

## 5. File structure (locked)

```
skills/re-frame2-setup/
├── SKILL.md                       (router; the seven-step canonical path)
├── README.md                      (human-facing intro)
├── LICENSE                        (MIT)
├── package.json                   (npm metadata)
├── .claude-plugin/plugin.json     (Claude Code plugin metadata)
├── references/
│   ├── deps-versions.md           (lockstep VERSION discipline; deps.edn / package.json)
│   ├── shadow-cljs.md             (build shape, index.html, CSP)
│   ├── entry-namespace.md         (rf/init! + Reagent root contract + UIx/Helix greenfield)
│   └── first-counter.md           (end-to-end worked example)
├── spec/
│   ├── design.md                  (this file)
│   ├── inputs.md                  (canonical inputs)
│   └── authoring-prompt.md        (one-shot reauthor prompt)
├── tests/
│   └── setup_drift_test.clj       (structural drift guard; Babashka)
└── evals/
    └── evals.json                 (trigger-accuracy fixture)
```

Each reference leaf targets ≤16 KB per the family leaf-size discipline ([`skills/README.md` §Leaf size discipline](../../README.md#leaf-size-discipline)); `entry-namespace.md` runs slightly over because it carries the full UIx/Helix greenfield recipe, which the `check_skill_setup_counter_drift.py` guard pins to that file (so it cannot be split into its own leaf). A typical greenfield session reads SKILL.md + 2 reference leaves. `spec/`, `tests/`, and `evals/` are excluded from the npm `files` array by design.

## 6. Discovery surface (frontmatter `description`)

The `description` is "pushy" and lists the greenfield-trigger phrases the shipped frontmatter carries: *"start a re-frame2 project"*, *"scaffold re-frame2"*, *"hello-world re-frame2 app"*, *"new re-frame2 app"*, plus a build failure on a freshly-scaffolded project tracing to missing `re-frame.core` / `re-frame.adapter.reagent` wiring. It explicitly handles off-task routing: once the counter mounts, the author switches to `re-frame2` for code-writing or `re-frame2-pair` for live pair-programming. (It deliberately omits the ambiguous *"add re-frame2 to my repo"* — that phrasing also matches the non-trivial-existing-app case the skill routes away.)

## 7. Anti-patterns the skill explicitly resists

- **Hardcoding artefact versions in suggestions** — L1 cardinal rule.
- **Mixing versions across the eleven artefacts** — L2 cardinal rule.
- **Adding per-feature artefacts defensively** — L3 cardinal rule + `references/deps-versions.md`'s "pay-as-you-go" framing.
- **A full multi-substrate decision tree at greenfield** — L4. Reagent is the default; UIx/Helix are a documented two-substitution delta, not a branching interview.
- **Writing tests for the author** — L5 cardinal rule.
- **Drifting into application-code authoring** — L5/L10; the exit hand-off routes past-setup work to `re-frame2`.

## 8. Why this design diverges from `re-frame2`

- **No patterns/ directory.** Setup is one workflow, not a library of recipes.
- **No decision-trees/ directory.** The only decision is "which per-feature artefacts do I need on day one?" and lives inline in `references/deps-versions.md`.
- **No examples-map.md.** The one example is the first counter, inlined in `references/first-counter.md`.
- **A clean exit hand-off, not a per-skill routing table.** The skill is the *entry point* into the family; SKILL.md ends with a hand-off paragraph pointing at the next skill, while cross-skill routing is single-sourced in `skills/README.md` (see L10).

## 9a. Testing & drift guards

The skill's load-bearing snippets are compile-tested and drift-guarded in re-frame2's CI:

- **`setup-skill-scaffold-compiles-test`** (`tools/template/test/day8/re_frame2_template/emitted_test_run_test.clj`) — materialises the fenced code blocks straight from the skill markdown (`references/first-counter.md` → `src/your_app/core.cljs`; `references/shadow-cljs.md` → `shadow-cljs.edn` + `index.html` + `css/app.css`), rewrites framework coords to `:local/root`, links `node_modules`, and runs `clojure -M:shadow compile app`. Behind the `RF2_TEMPLATE_RUN_EMITTED_TESTS=1` gate (runs in the `jvm-tools-template` CI job). It proves the snippets compile against in-repo coords — **not** that a published Clojars/git coordinate resolves from a fresh project (that buildability gate stays deferred to publication).
- **`scripts/check_skill_setup_counter_drift.py`** — repo-level Python gate (`verify-skill-mcp-drift` CI job): counter-id vocabulary containment (first-counter.md ↔ entry-namespace.md ↔ template), the `:init-fn` hot-reload lifecycle wording, and Spec 006 adapter-key vocabulary.
- **`tests/setup_drift_test.clj`** — skill-local Babashka structural guard (`skills-structural` CI job): locks the build-discipline framing, UIx/Helix template-pin parity, the right-side Xray host shape, the CSP dev/prod split, npx-qualified commands, the publication-state coordinate branch, the loud schema-missing contract, the day-one Xray preload, the user-run-generator framing, and the public entry-ramp docs (docs-site page + skills index). Run locally with `bb tests/setup_drift_test.clj`.

Both prose guards assert the skill teaches the right shapes; broader real-regression coverage of the wiring lives in the substrate contract tests (`npm run test:cljs`).

## 9. Open questions (deferred to Mike)

### OQ1 — Should the skill cover UIx / Helix greenfield? — RESOLVED (done)

**Resolved: yes, at the recipe level.** Rather than separate `entry-namespace-uix.md` / `-helix.md` leaves, the shipped skill folds UIx/Helix greenfield into `references/entry-namespace.md` §UIx / Helix greenfield — the two adapter substitutions (deps swap + entry-ns root API), a worked UIx `core.cljs`, and a pointer to the generator template's complete `_uix/` / `_helix/` variants. Reagent stays the default; UIx/Helix are a documented two-substitution delta, not a full decision tree. See L4.

### OQ2 — Should the skill ship a runnable `setup.bb` script?

A `bb`-driven mechanical scaffolder ("generate the four files for me") would shorten the setup loop. Status: deferred — the manual walkthrough is small enough that the agent's session can apply it inline without dedicated tooling.

### OQ3 — Should troubleshooting move to its own leaf?

Currently inlined at the end of SKILL.md (`Troubleshooting`). If it grows beyond ~30 lines, promote to `references/troubleshooting.md`. Status: monitored; not a blocker.
