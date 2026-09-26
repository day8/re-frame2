# re-frame-migration (v1 → v2)

> Migrates an existing re-frame v1.x ClojureScript codebase to re-frame2. Swaps the coord, applies mechanical (Type A) rewrites automatically, and flags judgment-call (Type B) call sites for human review.

## What it does

The `re-frame-migration` skill walks **two pre-flight phases plus six**. The pre-flight pair runs before any dep edit or compile — an inventory-and-plan pass over the v1 add-on libraries and app features, then a React-19 / Reagent-2 floor gate that surfaces a component library with no React-19 story as an explicit go/no-go rather than a mid-compile surprise — and the six that follow carry the project from orienting in the dep file through to the written report, with the dependency bump deliberately first and alone, because most codebases need nothing more. The phases are listed, in order and in full, in [`skills/re-frame-migration/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/SKILL.md) §The migration workflow.

The authoritative breaking-change list lives in [`migration/from-re-frame-v1/README.md`](https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/README.md). The skill consumes it; it never duplicates or invents rules. **Type A** rewrites (mechanical, unambiguous, observably identical) are applied without asking. **Type B** rewrites (timing-sensitive, dynamic call sites, behaviour-changes-at-the-edge) are flagged with the relevant rule cited, and the skill waits for the author's decision before rewriting.

JVM-side test runners are in scope — re-frame2 preserves `re-frame.interop` and JVM-side test runs. The smallest correct diff is the rule; no stylistic refactoring, no renames the author didn't ask for.

## When to reach for it

Load this skill when **any** of these are true:

- The author has an existing re-frame v1.x project and wants to move to re-frame2.
- The author mentions migrating, upgrading, porting, or v1→v2 in a re-frame context.
- The build fails after a dep bump and the cause looks v1-shaped — missing private namespaces, removed interceptors, `dispatch-with`, `re-frame.alpha`, the old effect-map keys.
- The author asks *"what breaks?"* / *"what changes?"* / *"is my v1 code compatible?"*.

Do **not** use this skill for:

- Greenfield setup → use [re-frame2-setup](re-frame2-setup.md).
- Writing v2 application code → use [re-frame2](re-frame2.md).
- Inspecting / debugging a running v2 app → use [re-frame2-pair](re-frame2-pair.md).

## Kickoff

The kickoff needs two values from you, and the skill stops and asks if either is missing:

- **A pinned local checkout of re-frame2** — a path and the commit or tag it should be at. The skill reads the rule corpus from that checkout, never from GitHub at runtime, and before reading verifies with three read-only `git` checks that `HEAD` matches the pin, that `origin` names `day8/re-frame2`, and that the pinned commit carries the multi-artefact layout (`implementation/core/deps.edn`, `implementation/adapters`). An older single-artefact commit passes the first two and fails the third.
- **The re-frame2 version to land on** — used verbatim in every dependency coordinate; the skill never picks "latest".

A paste-ready kickoff prompt ships with the skill at [`skills/re-frame-migration/references/kickoff-prompt.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/references/kickoff-prompt.md). The author opens a fresh Claude Code session in the root of their v1 project and pastes it verbatim. The session loads the skill on its own and walks the two pre-flight phases plus the six autonomously, surfacing decisions back to the author at the Type B checkpoints.

Excerpted shape (full text in the kickoff file):

> *I'm migrating this ClojureScript codebase from re-frame v1.x to re-frame2. Walk the migration end-to-end per the `re-frame-migration` skill in this session. … Walk the skill's full workflow IN ORDER — the two pre-flight phases (Phase 0a INVENTORY-AND-PLAN, then Phase 0b the React-19 / Reagent-2 floor gate), then Phases 1–6 — exactly as `SKILL.md` and its reference leaves define each. … Apply Type A (mechanical) rules without asking … For Type B (judgment) rules, identify every affected site, explain the risk, and WAIT for my approval before rewriting … "Compiles" is NOT the done-bar … Done = compile + tests + the boot smoke-test … all clean AND every Type B decision resolved. … Phase 5 opt-in `O-N` modernisations stay OFF unless I explicitly ask … Phase 6 is a <300-word report. … Begin with Phase 0a — the inventory-and-plan pre-flight (NOT Phase 1).*

The kickoff prompt also names two common amendments — *"also modernise"* (walk the `O-N` rules) and *"migrate in feature-branch slices"* (one commit per rule-group).

## When it stops

- **The floor gate says NO-GO.** A component library with neither a declared React-19 release nor an empirical runtime pass stops the migration before any dependency edit, with four options for you to choose from: wait for a release, replace the library, vendor or patch it, or force React 19 and verify at runtime. Toolchain and CI-browser bumps are never NO-GOs; they ride into the dependency swap.
- **A call site matches no rule.** The skill stops and asks rather than inventing a rewrite. A genuinely ambiguous rule becomes an upstream issue against `day8/re-frame2`: it searches for an existing one first and announces the filing to you before creating anything.
- **Type B sites.** The judgment calls are collected and presented as one batch at the end of the sweep, and nothing in that batch is rewritten until you decide.
- **No runtime to drive.** Compile and tests are the skill's to run, but the done-bar is the boot smoke-test: it drives that itself through a connected `re-frame2-pair` MCP or shadow-cljs nREPL, and otherwise hands you a short checklist ([`references/runtime-smoke-test.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/references/runtime-smoke-test.md)) and reports the smoke as pending rather than the migration as complete.
- **re-frame-10x in the project.** Replacing it with Xray is a required deliverable with no rule id of its own: the 10x preload is dropped at the dependency swap so the compile can run, and Xray is mounted once the app boots on re-frame2 ([`references/xray-replaces-10x.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/references/xray-replaces-10x.md)).

The failures v2 moves from compile time to run time — the ones a clean compile does not catch — are listed with their symptoms in [`references/silent-runtime-failures.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/references/silent-runtime-failures.md).

## Where the skill lives

- Source: [`skills/re-frame-migration/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame-migration)
- `SKILL.md`: [`skills/re-frame-migration/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/SKILL.md)
- Kickoff prompt: [`skills/re-frame-migration/references/kickoff-prompt.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/references/kickoff-prompt.md)
- Reference leaves: [`skills/re-frame-migration/references/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame-migration/references) — the skill [`README.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/README.md) §Layout lists every leaf.
- Authoritative rule corpus: [`migration/from-re-frame-v1/README.md`](https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/README.md).
- Narrative companion: the guide's [From re-frame v1](../core/25-from-re-frame-v1.md).
