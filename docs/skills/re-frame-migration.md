# re-frame-migration (v1 → v2)

> Migrates an existing re-frame v1.x ClojureScript codebase to re-frame2. Swaps the coord, applies mechanical (Type A) rewrites automatically, and flags judgment-call (Type B) call sites for human review.

## What it does

The `re-frame-migration` skill walks a six-phase migration: **orient** (read the dep file, skim the rule index), **bump M-0** (swap `re-frame/re-frame` → `day8/re-frame2` + a substrate adapter; stop and compile — most codebases need nothing more), **sweep for breakage** against the M-rules, **verify** (compile, tests, smoke), **optional opt-in modernisations** (only if the author asked), and **report** in the format `migration/from-re-frame-v1/README.md` Part 2 specifies.

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

A paste-ready kickoff prompt ships with the skill at [`skills/re-frame-migration/references/kickoff-prompt.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/references/kickoff-prompt.md). The author opens a fresh Claude Code session in the root of their v1 project and pastes it verbatim. The session loads the skill on its own and walks the six phases autonomously, surfacing decisions back to the author at the Type B checkpoints.

Excerpted shape (full text in the kickoff file):

> *I'm migrating this ClojureScript codebase from re-frame v1.x to re-frame2. Walk the migration end-to-end per the `re-frame-migration` skill in this session. Phase 1 — Orient. Read the dep file, confirm we're actually on `re-frame/re-frame` today, identify the substrate. Phase 2 — Apply M-0. Swap the coord. **Stop.** Most codebases require no further changes — verify that before doing more work. Phase 3 — If anything broke, sweep the failures against the M-rules. Apply Type A without asking; Type B asks first. Phase 4 — Re-verify. Phase 5 — Do NOT apply opt-in modernisations unless I explicitly ask. Phase 6 — Report (under 300 words).*

The kickoff prompt also names two common amendments — *"also modernise"* (walk the `O-N` rules) and *"migrate in feature-branch slices"* (one commit per rule-group).

## Where the skill lives

- Source: [`skills/re-frame-migration/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame-migration)
- `SKILL.md`: [`skills/re-frame-migration/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/SKILL.md)
- Kickoff prompt: [`skills/re-frame-migration/references/kickoff-prompt.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/references/kickoff-prompt.md)
- Reference leaves: [`skills/re-frame-migration/references/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame-migration/reference) — `setup.md` (M-0 in operational detail), `breaking-changes.md` (one-page index of every rule by trigger surface), `sequencing.md`, `auto-call-site-rewrites.md` (Type A — per-call-site mechanical rewrites), `auto-cross-cutting.md` (Type A — cross-cutting renames / views / init / artefacts), `guided-handlers-state.md` (Type B — handler / view / db-seeding walkthroughs), `guided-interceptors-subs.md` (Type B — interceptor / sub / payload walkthroughs), `output-format.md` (the migration-report shape).
- Authoritative rule corpus: [`migration/from-re-frame-v1/README.md`](https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/README.md).
- Narrative companion: [Guide chapter 18 — From re-frame v1](../guide/18-from-re-frame-v1.md).
