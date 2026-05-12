# Skills

> Five Claude Code skills that travel with the re-frame2 repo — for authoring code, bootstrapping a project, migrating from v1, pair-programming with a running app, and running a retrospective on a pairing session.

A **skill** is a small package of agent-shaped instructions plus optional scripts and reference leaves. When you load a skill into Claude Code (or any other Anthropic-skill-compatible agent), the model picks up its system prompt and its operating contract — so the same conversation that was *"help me write a re-frame2 event handler"* becomes a focused interaction that knows the canonical shapes, the cardinal rules, and where the depth lives.

re-frame2 ships five skills, colocated under [`skills/`](https://github.com/day8/re-frame2/tree/main/skills) in this repo. Each one is self-contained: its own `SKILL.md`, its own `reference/` leaves, its own packaging metadata.

## How to load a skill

The exact mechanics depend on the agent you're driving. In **Claude Code**, install a skill by copying its directory into a project-level `.claude/skills/<name>/` (or globally into `~/.claude/skills/<name>/`). Once installed, the skill's `description` triggers it whenever the conversation mentions one of its surfaces — you usually don't need to invoke it explicitly. You can also force-load it with `/skill <name>` if the agent's launcher supports slash-commands for skills.

The repo's [`SKILL-REDIRECT.md`](https://github.com/day8/re-frame2/blob/main/SKILL-REDIRECT.md) is the single deep-dive index — every skill points at it for spec-corpus depth and EP rationale.

## The five skills

| Skill | Pitch |
|---|---|
| [**re-frame2** (authoring)](re-frame2.md) | Write re-frame2 ClojureScript code — events, subs, fx, machines, schemas, stories, routes, and the canonical patterns. |
| [**re-frame2-setup**](re-frame2-setup.md) | Bootstrap a fresh re-frame2 ClojureScript project from nothing. Walks the author to a working counter under `shadow-cljs watch`. |
| [**re-frame-migration** (v1→v2)](re-frame-migration.md) | Migrate an existing re-frame v1.x codebase to re-frame2. Applies the mechanical `M-rules` automatically; flags judgment calls. |
| [**re-frame-pair2**](re-frame-pair2.md) | Pair-program with a live, running re-frame2 app. Dispatch events, inspect `app-db`, walk epochs, hot-swap handlers — all via Tool-Pair contract. |
| [**re-frame-pair-improver2**](re-frame-pair-improver2.md) | Retrospect a `re-frame-pair2` session. Surfaces friction; proposes targeted improvements; routes upstream beads to re-frame2 when the cause is framework-shaped. |

## Picking the right one

A quick decision flow:

- **Starting from nothing?** → `re-frame2-setup`. When the counter mounts, switch to `re-frame2`.
- **Existing v1 codebase?** → `re-frame-migration`. When the migration report is signed off, switch to `re-frame2`.
- **Writing new code in an existing v2 project?** → `re-frame2`.
- **Debugging or pairing with a running v2 app?** → `re-frame-pair2`.
- **Just finished a pairing session and noticed friction?** → `re-frame-pair-improver2`.

If a question spans more than one skill, pick the one whose **entry trigger** matches and let it route — every skill has a *"when NOT to use this"* table that hands off cleanly.

## Where each one lives

| Skill | Source tree |
|---|---|
| `re-frame2` | [`skills/re-frame2/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2) |
| `re-frame2-setup` | [`skills/re-frame2-setup/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-setup) |
| `re-frame-migration` | [`skills/re-frame-migration/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame-migration) |
| `re-frame-pair2` | [`skills/re-frame-pair2/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame-pair2) |
| `re-frame-pair-improver2` | [`skills/re-frame-pair-improver2/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame-pair-improver2) |

Each sub-page on this tab is a brief discoverability entry-ramp — pitch, triggers, kickoff shape, link to the skill's own `SKILL.md`. The authoritative content always lives in the skill's source tree.
