# re-frame2 (skill)

> ↑ [`skills/`](../) — index of all six re-frame2 skills.

A `Skill` that helps `Claude Code` (and any Claude Agent SDK harness) **author re-frame2 ClojureScript applications**. Companion to [`re-frame2-pair`](../re-frame2-pair/) (which targets running apps) and [`re-frame2-setup`](../re-frame2-setup/) (which bootstraps new projects from scratch).

This skill carries the recipes, decision rules, and canonical declarations Claude needs to write idiomatic re-frame2 code on the first attempt — events, subscriptions, effects, frames, state machines, schemas, stories, routing, and the canonical patterns (RemoteData, Forms, Boot, WebSocket, NineStates, ManagedHTTP, AsyncEffect, LongRunningWork, StaleDetection).

## Scope

| In scope | Out of scope |
|---|---|
| Writing new re-frame2 code (`.cljs` / `.cljc`) | Greenfield project bootstrap → `re-frame2-setup` |
| Choosing between slice / region / machine | Inspecting a running app → `re-frame2-pair` |
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
├── references/
│   ├── fundamentals/                 events, fx, cofx, subs, flows, frames, schemas, event-state-cycle, project-structure.
│   ├── state-machines/               reg-machine, regions, tags, spawn, cancellation.
│   ├── tooling/                      stories, routing, story-recorder, story-mcp-loop, causa.
│   └── cross-cutting/                testing, api-cheatsheet, privacy-and-elision, production-observability, ssr-authoring.
├── patterns/                         One leaf per canonical pattern (9 leaves).
└── decision-trees/                   pick-a-pattern, slice-or-machine.
```

The leaves cover fundamentals, state-machines, tooling, cross-cutting, project-structure, the nine canonical patterns, and the two decision trees. Footers pin each leaf to the implementation it derives from, re-verified after refactors.

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
- [re-frame2-pair](../re-frame2-pair/) — live-app inspection
- [SKILL-REDIRECT.md](../../SKILL-REDIRECT.md) — canonical pointer table for deep-dive content

## Status

**Alpha.** All scaffolding and leaves are populated: `references/fundamentals/` (including the Flows leaf), `references/state-machines/`, `references/tooling/`, `references/cross-cutting/`, the nine canonical patterns under `patterns/`, and both decision trees (`pick-a-pattern`, `slice-or-machine`). The loading map is reconciled and the derived-from-implementation footers are pinned at main `89bd9c3`. All worked examples the leaves cite — including `examples/reagent/{websocket,long_running_work,flows}/` — have shipped; the evals harness is the only in-flight item and is not a blocker for skill use.

## License

MIT.
