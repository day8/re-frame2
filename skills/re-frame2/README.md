# re-frame2 (skill)

> ↑ [`skills/`](..) — index of all eight re-frame2 skills.

A `Skill` that helps `Claude Code` (and any Claude Agent SDK harness) **author re-frame2 ClojureScript applications**. Companion to [`re-frame2-pair`](../re-frame2-pair) (which targets running apps) and [`re-frame2-setup`](../re-frame2-setup) (which bootstraps new projects from scratch).

This skill carries the recipes, decision rules, and canonical declarations Claude needs to write idiomatic re-frame2 code on the first attempt — events, subscriptions, effects, frames, state machines, schemas, stories, routing, and the canonical patterns (RemoteData, Resources, Forms, Boot, WebSocket, NineStates, ManagedHTTP, AsyncEffect, LongRunningWork, StaleDetection, ReusableComponents, StatefulComponents, FormAction, SSR-Loaders).

## Scope

| In scope | Out of scope |
|---|---|
| Writing new re-frame2 code (`.cljs` / `.cljc`) | Greenfield project bootstrap → `re-frame2-setup` |
| Choosing between slice / region / machine | Inspecting a running app → `re-frame2-pair` |
| Picking a canonical pattern | Migrating a v1 app → [`re-frame-migration`](../re-frame-migration) |
| Composing patterns | Full API reference / EP rationale → `SKILL-REDIRECT.md` |

## Layout

```
skills/re-frame2/
├── SKILL.md                          The router. Loaded when the skill activates.
├── README.md                         This file.
├── LICENSE                           MIT.
├── package.json                      Package metadata. Marked `private` — the skill installs from a repo checkout, not npm.
├── examples-map.md                   One-paragraph index of every worked example.
├── .claude-plugin/
│   └── plugin.json                   Claude Code plugin metadata.
├── references/
│   ├── fundamentals/                 events, fx, cofx, subs, views, flows, frames, schemas, event-state-cycle, project-structure.
│   ├── state-machines/               reg-machine, regions, tags, spawn, history, cancellation.
│   ├── tooling/                      stories, routing, story-recorder, story-mcp-loop, xray.
│   └── cross-cutting/                testing, api-cheatsheet, privacy-and-elision, production-observability, ssr-authoring, path-and-identity.
├── patterns/                         One leaf per canonical pattern (14 leaves).
├── decision-trees/                   pick-a-pattern, slice-or-machine.
├── spec/                             Skill-internal meta-docs (design, inputs, authoring-prompt). Not loaded at runtime.
└── evals/                            Eval harness. Repo-maintenance artifact; not shipped with the skill.
```

The leaves cover fundamentals, state-machines, tooling, cross-cutting, project-structure, the fourteen canonical patterns, and the two decision trees. Footers pin each leaf to the implementation it derives from, re-verified after refactors.

## Install

`re-frame2` is distributed with the [`day8/re-frame2`](https://github.com/day8/re-frame2) monorepo. The two supported channels are a **repo checkout** (git clone + link) and a **Claude Code marketplace plugin** — it is **not** published to npm (the `package.json` is marked `private`). Clone the repo and **link** the skill into `~/.claude/skills/` (Claude Code loads skills from there).

**Link, never copy.** A copy snapshots the skill and then drifts as the repo is maintained — Claude Code keeps loading the stale copy. Use the cross-platform installer, which links *every* skill in the monorepo so the active skill is the repo source by construction:

```bash
git clone https://github.com/day8/re-frame2.git
cd re-frame2
scripts/install-skills.sh                                              # macOS / Linux (symlinks)
powershell -ExecutionPolicy Bypass -File scripts/install-skills.ps1    # Windows (junctions, no admin)
```

The installer is idempotent, refuses to clobber a non-link copy without `--force` (`-Force`), and supports `--check` (`-Check`) to verify the links. See [`skills/README.md` §Installing (link, never copy)](../README.md#installing-link-never-copy) for the full rationale. To make the skill available to a team through a project repo, link from a checkout the team shares rather than committing a `cp -r` snapshot that will go stale.

## How it activates

The skill's `description` triggers on natural-language references to re-frame2 surfaces — `reg-event`, `reg-sub`, `reg-fx`, `reg-machine`, `reg-interceptor`, `dispatch`, `subscribe`, `app-db`, frames, regions, tags, and the canonical pattern names. You do not need to name the skill explicitly. If you want to force it:

```
/re-frame2
```

…or mention it in a prompt:

> Using re-frame2, write a `reg-machine` for the login lifecycle with a parallel region for password-visibility toggling.

## Cross-link

- [re-frame2 monorepo](https://github.com/day8/re-frame2)
- [re-frame2-pair](../re-frame2-pair) — live-app inspection
- [SKILL-REDIRECT.md](../../SKILL-REDIRECT.md) — canonical pointer table for deep-dive content

## Status

**Alpha.** The skill covers `references/fundamentals/`, `references/state-machines/`, `references/tooling/`, `references/cross-cutting/`, the fourteen canonical patterns under `patterns/`, and both decision trees (`pick-a-pattern`, `slice-or-machine`). The `evals/` harness is a **repo-maintenance artifact** — not shipped with the skill (see [`evals/README.md` §Repo-maintenance artifact](evals/README.md); `package.json`'s `files` allow-list omits it; run the harness from a monorepo clone).

## License

MIT.
