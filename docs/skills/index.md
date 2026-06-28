# Skills

> Eight Claude Code skills that travel with the re-frame2 repo — for authoring code, critiquing existing code, bootstrapping a project, migrating from v1, building a new re-frame2 implementation, touring the Xray devtools panel, pair-programming with a running app, and running a retrospective on a pairing session.

A **skill** is a small package of agent-shaped instructions plus optional scripts and reference leaves. When you load a skill into Claude Code (or any other Anthropic-skill-compatible agent), the model picks up its system prompt and its operating contract — so the same conversation that was *"help me write a re-frame2 event handler"* becomes a focused interaction that knows the canonical shapes, the cardinal rules, and where the depth lives.

re-frame2 ships eight skills, colocated under [`skills/`](https://github.com/day8/re-frame2/tree/main/skills) in this repo. Each one is self-contained: its own `SKILL.md`, its own `reference/` leaves, its own packaging metadata.

## How to load a skill

The exact mechanics depend on the agent you're driving. In **Claude Code**, a skill lives at a project-level `.claude/skills/<name>/` or globally at `~/.claude/skills/<name>/`. **Link the repo directory in rather than copying it** — a copy snapshots the skill and then silently drifts as the repo is maintained, so Claude Code ends up loading a stale skill. From a re-frame2 clone, the cross-platform installer links every skill into `~/.claude/skills/` in one step (symlinks on macOS/Linux, directory junctions on Windows — no admin needed):

```bash
scripts/install-skills.sh                                              # macOS / Linux
powershell -ExecutionPolicy Bypass -File scripts/install-skills.ps1    # Windows
```

It is idempotent and refuses to clobber a non-link copy without `--force`/`-Force`. See [`CONTRIBUTING.md`](https://github.com/day8/re-frame2/blob/main/CONTRIBUTING.md#skills--link-dont-copy) for the full setup. Once installed, the skill's `description` triggers it whenever the conversation mentions one of its surfaces — you usually don't need to invoke it explicitly. You can also force-load it with `/skill <name>` if the agent's launcher supports slash-commands for skills.

The repo's [`SKILL-REDIRECT.md`](https://github.com/day8/re-frame2/blob/main/SKILL-REDIRECT.md) is the deep-dive index for the **spec-consuming** skills — they point at it for spec-corpus depth and EP rationale. (Two skills route their deep-dives elsewhere: `re-frame2-xray` cites its own `tools/xray/spec/*` tree, and `re-frame2-improver` routes to `skills/re-frame2/patterns/` + `spec/`.)

## The eight skills

| Skill | Pitch |
|---|---|
| [**re-frame2** (authoring)](re-frame2.md) | Write re-frame2 ClojureScript code — events, subs, fx, machines, schemas, stories, routes, and the canonical patterns. |
| [**re-frame2-improver**](re-frame2-improver.md) | Critique **existing** re-frame2 code against an anti-pattern catalogue. Explicit-pull-only; surfaces findings cross-linked to canonical idioms, may propose inline fixes. |
| [**re-frame2-setup**](re-frame2-setup.md) | Bootstrap a fresh re-frame2 ClojureScript project from nothing. Walks the author to a working counter under `shadow-cljs watch`. |
| [**re-frame-migration** (v1→v2)](re-frame-migration.md) | Migrate an existing re-frame v1.x codebase to re-frame2. Applies the mechanical `M-rules` automatically; flags judgment calls. |
| [**re-frame2-implementor**](re-frame2-implementor.md) | Build a new re-frame2 implementation in a different host language or substrate. Two-phase workflow — Phase 1 locks the decisions; Phase 2 walks the spec corpus with conformance as the acceptance test. |
| [**re-frame2-xray**](re-frame2-xray.md) | Read-only tour of the **Xray** devtools panel — how to launch it and which tab, across its Dynamic event-spine and Static registry-browse modes, shows X. |
| [**re-frame2-pair**](re-frame2-pair.md) | Pair-program with a live, running re-frame2 app. Dispatch events, inspect `app-db`, walk epochs, hot-swap handlers — all via Tool-Pair contract. |
| [**re-frame2-pair-retro**](re-frame2-pair-retro.md) | Retrospect a `re-frame2-pair` session. Surfaces friction; proposes targeted improvements; routes upstream GitHub issues to re-frame2 when the cause is framework-shaped. |

## Picking the right one

A quick decision flow:

- **Starting from nothing?** → `re-frame2-setup`. When the counter mounts, switch to `re-frame2`.
- **Existing v1 codebase?** → `re-frame-migration`. When the migration report is signed off, switch to `re-frame2`.
- **Writing new code in an existing v2 project?** → `re-frame2`.
- **Critiquing existing v2 code on explicit pull (anti-pattern audit)?** → `re-frame2-improver`.
- **Building a NEW re-frame2 implementation in a different host language or substrate?** → `re-frame2-implementor`.
- **Touring the Xray devtools panel — how to launch it, or which tab / mode shows X?** → `re-frame2-xray`.
- **Debugging or pairing with a running v2 app?** → `re-frame2-pair`.
- **Just finished a pairing session and noticed friction?** → `re-frame2-pair-retro`.

If a question spans more than one skill, pick the one whose **entry trigger** matches and let it route — every skill has a *"when NOT to use this"* table that hands off cleanly.

## Where each one lives

| Skill | Source tree |
|---|---|
| `re-frame2` | [`skills/re-frame2/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2) |
| `re-frame2-improver` | [`skills/re-frame2-improver/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-improver) |
| `re-frame2-setup` | [`skills/re-frame2-setup/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-setup) |
| `re-frame-migration` | [`skills/re-frame-migration/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame-migration) |
| `re-frame2-implementor` | [`skills/re-frame2-implementor/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-implementor) |
| `re-frame2-xray` | [`skills/re-frame2-xray/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-xray) |
| `re-frame2-pair` | [`skills/re-frame2-pair/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-pair) |
| `re-frame2-pair-retro` | [`skills/re-frame2-pair-retro/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-pair-retro) |

Each sub-page on this tab is a brief discoverability entry-ramp — pitch, triggers, kickoff shape, link to the skill's own `SKILL.md`. The authoritative content always lives in the skill's source tree.
