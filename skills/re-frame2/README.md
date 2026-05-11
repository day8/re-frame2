# re-frame2 (skill)

A `Skill` that helps `Claude Code` (and any Claude Agent SDK harness) **author re-frame2 ClojureScript applications**. Companion to [`re-frame-pair2`](../re-frame-pair2/) (which targets running apps) and `re-frame2-setup` (which bootstraps new projects from scratch).

This skill carries the recipes, decision rules, and canonical declarations Claude needs to write idiomatic re-frame2 code on the first attempt — events, subscriptions, effects, frames, state machines, schemas, stories, routing, and the canonical patterns (RemoteData, Forms, Boot, WebSocket, NineStates, ManagedHTTP, AsyncEffect, LongRunningWork, StaleDetection).

## Scope

| In scope | Out of scope |
|---|---|
| Writing new re-frame2 code (`.cljs` / `.cljc`) | Greenfield project bootstrap → `re-frame2-setup` |
| Choosing between slice / region / machine | Inspecting a running app → `re-frame-pair2` |
| Picking a canonical pattern | Migrating a v1 app → `SKILL-REDIRECT.md` |
| Composing patterns | Full API reference / EP rationale → `SKILL-REDIRECT.md` |

## Layout

```
skills/re-frame2/
├── SKILL.md                          The router. Loaded when the skill activates.
├── README.md                         This file.
├── LICENSE                           MIT.
├── package.json                      npm distribution metadata.
├── examples-map.md                   One-paragraph index of every worked example.
├── .claude-plugin/
│   └── plugin.json                   Claude Code plugin metadata.
├── reference/
│   ├── fundamentals/                 events, fx, cofx, subs, frames, schemas, event-state-cycle, project-structure.
│   ├── state-machines/               reg-machine, regions, tags, invoke, cancellation.
│   ├── tooling/                      Stories, routing.
│   └── cross-cutting/                Testing, API cheatsheet.
├── patterns/                         One leaf per canonical pattern (9 leaves).
└── decision-trees/                   pick-a-pattern, slice-or-machine.
```

The scaffolding was seeded by rf2-eipb. The leaves were authored by follow-on beads off the parent rf2-qumf (rf2-9tuz fundamentals, rf2-a04c state-machines, rf2-4yc1 tooling, rf2-0tkn cross-cutting, rf2-w5tc project-structure, rf2-p6ut / rf2-60kv / rf2-e57j pattern batches, rf2-g6fh decision-trees). The integration pass (rf2-l086) reconciles the loading map and adds derived-from-implementation footers.

## Install

`re-frame2` ships as part of the [`day8/re-frame2`](https://github.com/day8/re-frame2) monorepo. There is no separate npm registry entry yet — clone the repo and reference the skill from `skills/re-frame2/`.

### Global — for you, across any re-frame2 project

Symlink (or copy) the skill into your user Claude config:

```bash
git clone https://github.com/day8/re-frame2.git
ln -s "$(pwd)/re-frame2/skills/re-frame2" ~/.claude/skills/re-frame2
```

### Project-local — for your team via the repo

Copy the skill into the project's own `.claude/skills/re-frame2/` and commit it:

```bash
cd your-re-frame2-project
cp -r /path/to/re-frame2/skills/re-frame2 .claude/skills/re-frame2
git add .claude/skills/re-frame2
```

## How it activates

The skill's `description` triggers on natural-language references to re-frame2 surfaces — `reg-event-*`, `reg-sub`, `reg-fx`, `reg-machine`, `dispatch`, `subscribe`, `app-db`, frames, regions, tags, and the canonical pattern names. You do not need to name the skill explicitly. If you want to force it:

```
/re-frame2
```

…or mention it in a prompt:

> Using re-frame2, write a `reg-machine` for the login lifecycle with a parallel region for password-visibility toggling.

## Cross-link

- [re-frame2 monorepo](https://github.com/day8/re-frame2)
- [re-frame-pair2](../re-frame-pair2/) — live-app inspection
- [SKILL-REDIRECT.md](../../SKILL-REDIRECT.md) — canonical pointer table for deep-dive content

## Status

**Alpha.** Scaffolding + leaves are in place; the integration pass (rf2-l086) reconciled the loading map and added derived-from-implementation footers pinned at main `89bd9c3`. The boot example (rf2-dsm2) is now linked; evals harness (rf2-p3qg) and `examples/reagent/{websocket,long_running_work}/` worked examples remain in flight.

## License

MIT.
