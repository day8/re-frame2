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
├── .claude-plugin/
│   └── plugin.json                   Claude Code plugin metadata.
├── reference/
│   ├── fundamentals/                 Frames, events/fx, subs, views, schemas, the cycle, conventions.
│   ├── state-machines/               reg-machine, regions, tags, invoke, cancellation, decision tree.
│   ├── tooling/                      Stories, routing.
│   └── cross-cutting/                Testing, API cheatsheet.
├── patterns/                         One leaf per canonical pattern.
└── decision-trees/                   "Which pattern?" and "Which state shape?"
```

Leaf files are populated by follow-on beads off the parent rf2-qumf. This bead (rf2-eipb) seeds the structure and `SKILL.md`; downstream beads fill `reference/`, `patterns/`, and `decision-trees/`.

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

**Scaffolding.** This bead (rf2-eipb) seeds the directory structure and the router `SKILL.md`. The leaf files under `reference/`, `patterns/`, and `decision-trees/` are populated by follow-on beads — see rf2-qumf for the bead roster.

## License

MIT.
