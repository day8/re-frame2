# re-frame2-setup — Design

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame2-setup` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

The design rationale and locked decisions for the `re-frame2-setup` skill. A future agent could re-author this skill from this folder alone.

## 1. Goal

Help an author **bootstrap a fresh re-frame2 ClojureScript project**. The author starts with nothing — or close to it — and ends with a working browser app that compiles under `shadow-cljs watch` and mounts a counter. From there, the author switches to the main `re-frame2` skill for application-code authoring.

The success criterion: `npx shadow-cljs watch app` compiles cleanly, the browser shows a counter, clicking `+` / `-` updates it. The skill stops at that point — anything beyond setup is another skill's job.

## 2. Pillars (locked, derived from `re-frame2`'s four pillars)

The same four pillars as the `re-frame2` skill, scoped to greenfield bootstrap:

1. **Correctness — recipes over explanations.** Walks through the exact deps, the exact `shadow-cljs.edn` shape, the exact entry-namespace contract. The author copies the recipe; doesn't re-derive it. **Q14 lock applies: NO verification module** — the author runs the build; the skill doesn't.
2. **Idiomaticness — verified against `examples/reagent/counter/` and the canonical artefacts.** The first-counter shape mirrors `examples/reagent/counter/core.cljs` trimmed for greenfield. Dep coords match what `day8/re-frame2` ships.
3. **Context economy — `SKILL.md` is a router; four one-level-deep leaves carry the depth.** SKILL.md walks the seven-step canonical path; leaves carry per-step depth.
4. **Assume training knowledge.** The author knows `deps.edn`, `npm`, `shadow-cljs`, what a Reagent component is. The skill teaches only the **re-frame2-specific wiring** — which artefacts to add, the `rf/init!` contract, the order of operations between adapter install and React mount.

## 3. Locked decisions

These are not up for re-litigation. A future authoring pass MUST preserve them unless explicitly unlocked by Mike.

### L1 — Never hardcode artefact versions in suggestions written to disk

re-frame2 ships ten Maven artefacts in lockstep at a single VERSION. Versions change. The skill points the author at `reference/deps-versions.md` for lookup; the cardinal rule lives in SKILL.md so it's read on every load. Hardcoded versions in suggestions are a documented anti-pattern.

### L2 — All ten artefacts ship at the same VERSION

The author picks the VERSION once; every `day8/re-frame2-*` dep gets that same version. Mixing versions across artefacts is unsupported. This rule lands in both SKILL.md and `reference/deps-versions.md`.

### L3 — Only add the per-feature artefacts the author actually uses

Core (`day8/re-frame2`) and adapter (`day8/re-frame2-reagent`) are mandatory on day one. Per-feature artefacts (`-schemas`, `-machines`, `-routing`, `-flows`, `-http`, `-ssr`, `-epoch`) come in **only when the author starts using the feature**. The skill resists the temptation to "add them all defensively" — pay-as-you-go is the contract.

### L4 — The Reagent adapter is the default reference substrate

Unless the author explicitly says UIx or Helix, scaffold against Reagent. The skill does not branch into multi-substrate decision trees at greenfield — Reagent v2 is the canonical default.

### L5 — Don't write tests for the author

The skill stops at "the counter mounts". Anything after that — events, subs, machines, schemas, test-authoring — is the main `re-frame2` skill (which itself defers test-writing to the author per its own Q14 lock).

### L6 — Q14 — NO verification module

Consistent with the `re-frame2` skill: no `reference/verify.md`, no "verify before claiming done" hard rule. The Done checklist in SKILL.md lists the conditions; the author confirms. The skill never asserts completion.

### L7 — No bead-ids in user-facing skill content

`SKILL.md` + `reference/` carry no `rf2-XXXX` references. The `spec/` folder may; user-facing leaves do not.

### L8 — Findings stay local

Per Mike's standing memory rule "Findings is local-only" — never commit `ai/` or `findings/`. This skill's commits contain only `skills/re-frame2-setup/**`.

### L9 — Single-import contract from day one

The first-counter recipe imports `re-frame.core` as `rf` and `re-frame.adapter.reagent` as `reagent-adapter`. No private-namespace requires; no `re-frame.db` style reach-ins. The contract the author starts with is the contract the main `re-frame2` skill enforces from there on.

### L10 — Routing table for "anything beyond setup"

SKILL.md ends with an explicit "When the author asks anything beyond setup" routing table. Every adjacent skill is listed by name. The author leaves this skill confidently for the next one rather than stretching this skill to cover authoring questions.

## 4. Audience and scope

### In scope

- Authors starting a new directory (or an existing CLJS project) that needs re-frame2 wiring.
- The seven canonical steps: discover versions → `deps.edn` → `package.json` → `shadow-cljs.edn` → entry ns → first counter → run.
- Reagent v2 as the default substrate.
- Troubleshooting the four most common build failures (classpath miss, missing react, missing `rf/init!`, missing `<div id="app">`).

### Out of scope

- Migrating from re-frame v1 → `skills/re-frame-migration/`.
- Authoring application code beyond the first counter → `skills/re-frame2/`.
- Live-runtime debugging → `skills/re-frame-pair2/`.
- Building re-frame2 in a different host language → `skills/re-frame2-implementor/`.
- Non-Reagent substrates (UIx, Helix) at greenfield — the skill scaffolds against Reagent; substrate-switch is a separate conversation.
- Writing tests, registering events, subs, machines, schemas — all the main `re-frame2` skill's job.

## 5. File structure (locked)

```
skills/re-frame2-setup/
├── SKILL.md                       (router; ~170 lines)
├── README.md                      (human-facing intro)
├── LICENSE                        (MIT)
├── package.json                   (npm metadata)
├── .claude-plugin/plugin.json     (Claude Code plugin metadata)
├── reference/
│   ├── deps-versions.md           (~120 lines; lockstep VERSION discipline)
│   ├── shadow-cljs.md             (~100 lines; build shape, index.html)
│   ├── entry-namespace.md         (~120 lines; rf/init! + Reagent root contract)
│   └── first-counter.md           (~110 lines; end-to-end worked example)
└── spec/
    ├── design.md                  (this file)
    ├── inputs.md                  (canonical inputs)
    └── authoring-prompt.md        (one-shot reauthor prompt)
```

SKILL.md (~170) + 4 reference leaves (~450) + 3 spec files (~250) ≈ ~870 LoC across 10 files. Typical greenfield session reads SKILL.md + 2 reference leaves = ~370 LoC.

## 6. Discovery surface (frontmatter `description`)

The `description` is "pushy" and lists every greenfield-trigger phrase: *"start a re-frame2 project"*, *"scaffold re-frame2"*, *"how do I set up re-frame2"*, *"add re-frame2 to my repo"*, *"give me a hello-world re-frame2 app"*. The description explicitly handles off-task routing: once the counter mounts, the author switches to `re-frame2` for code-writing or `re-frame-pair2` for live pair-programming.

## 7. Anti-patterns the skill explicitly resists

- **Hardcoding artefact versions in suggestions** — L1 cardinal rule.
- **Mixing versions across the ten artefacts** — L2 cardinal rule.
- **Adding per-feature artefacts defensively** — L3 cardinal rule + `reference/deps-versions.md`'s "pay-as-you-go" framing.
- **Branching into UIx/Helix at greenfield** — L4. Substrate-switch is a separate conversation.
- **Writing tests for the author** — L5 cardinal rule.
- **Drifting into application-code authoring** — L10 routing table at the end of SKILL.md.

## 8. Why this design diverges from `re-frame2`

- **No patterns/ directory.** Setup is one workflow, not a library of recipes.
- **No decision-trees/ directory.** The only decision is "which per-feature artefacts do I need on day one?" and lives inline in `reference/deps-versions.md`.
- **No examples-map.md.** The one example is the first counter, inlined in `reference/first-counter.md`.
- **Routing table at the end of SKILL.md.** The skill is the *entry point* into the family — the explicit routing-on-exit table tells the author where to go next.

## 9. Open questions (deferred to Mike)

### OQ1 — Should the skill cover UIx / Helix greenfield?

Currently L4 makes Reagent the only greenfield path. A future v0.2 could add `reference/entry-namespace-uix.md` and `reference/entry-namespace-helix.md` once those substrates have a steady greenfield user-base. Status: deferred until there's evidence of demand.

### OQ2 — Should the skill ship a runnable `setup.bb` script?

A `bb`-driven mechanical scaffolder ("generate the four files for me") would shorten the setup loop. Status: deferred — the manual walkthrough is small enough that the agent's session can apply it inline without dedicated tooling.

### OQ3 — Should troubleshooting move to its own leaf?

Currently inlined at the end of SKILL.md (`Troubleshooting`). If it grows beyond ~30 lines, promote to `reference/troubleshooting.md`. Status: monitored; not a blocker.
