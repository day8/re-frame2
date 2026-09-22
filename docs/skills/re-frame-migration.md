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

A paste-ready kickoff prompt ships with the skill at [`skills/re-frame-migration/references/kickoff-prompt.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/references/kickoff-prompt.md). The author opens a fresh Claude Code session in the root of their v1 project and pastes it verbatim. The session loads the skill on its own and walks the two pre-flight phases plus the six autonomously, surfacing decisions back to the author at the Type B checkpoints.

Excerpted shape (full text in the kickoff file):

> *I'm migrating this ClojureScript codebase from re-frame v1.x to re-frame2. Walk the migration end-to-end per the `re-frame-migration` skill in this session. … Walk the skill's full workflow IN ORDER — the two pre-flight phases (Phase 0a INVENTORY-AND-PLAN, then Phase 0b the React-19 / Reagent-2 floor gate), then Phases 1–6 — exactly as `SKILL.md` and its reference leaves define each. … Apply Type A (mechanical) rules without asking … For Type B (judgment) rules, identify every affected site, explain the risk, and WAIT for my approval before rewriting … "Compiles" is NOT the done-bar … Done = compile + tests + the boot smoke-test … all clean AND every Type B decision resolved. … Phase 5 opt-in `O-N` modernisations stay OFF unless I explicitly ask … Phase 6 is a <300-word report. … Begin with Phase 0a — the inventory-and-plan pre-flight (NOT Phase 1).*

The kickoff prompt also names two common amendments — *"also modernise"* (walk the `O-N` rules) and *"migrate in feature-branch slices"* (one commit per rule-group).

## Where the skill lives

- Source: [`skills/re-frame-migration/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame-migration)
- `SKILL.md`: [`skills/re-frame-migration/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/SKILL.md)
- Kickoff prompt: [`skills/re-frame-migration/references/kickoff-prompt.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/references/kickoff-prompt.md)
- Reference leaves: [`skills/re-frame-migration/references/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame-migration/references) — the skill [`README.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/README.md) §Layout lists every leaf.
- Authoritative rule corpus: [`migration/from-re-frame-v1/README.md`](https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/README.md).
- Narrative companion: the guide's [From re-frame v1](../core/25-from-re-frame-v1.md).
