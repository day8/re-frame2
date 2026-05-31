# re-frame2-xray

> ↑ [`skills/`](../) — index of all re-frame2 skills.

`re-frame2-xray` is a Claude Code **tour skill** for [Xray](https://github.com/day8/re-frame2/tree/main/tools/xray) — the re-frame2 in-app devtools panel. It answers two questions, and only two:

1. **How do I launch Xray?** — the inline panel, the pop-out, the programmatic `init!`, the wired hotkeys, and the Dynamic ↔ Static mode toggle.
2. **Which tab shows X?** — a one-line purpose for each tab across both modes: the 6 Dynamic event-spine tabs (Epoch · app-db · Views · Trace · Machine · Routes) and the 5 Static registry-browse tabs.

Workflow procedures (find-wrong-sub, scrub-bad-epoch, click-to-source, redaction-marker semantics) are out of scope for this iteration — see `SKILL.md` §Out of scope for what to do when one of those comes up.

## What Xray is

An in-app true-inline devtools panel for re-frame2 applications, preloaded into dev builds via shadow-cljs `:preloads`. Xray consumes re-frame2's instrumentation surface (Spec 009 trace bus, Tool-Pair epoch history, the registrar query API) — it adds nothing the framework didn't already expose. Production builds elide the entire surface through the universal `interop/debug-enabled?` gate.

Xray is the **human-facing** panel; for an AI agent surface against the running app, see [`re-frame2-pair`](../re-frame2-pair/) (the raw nREPL pair-programming companion).

## Repo contents

- `SKILL.md` — the skill itself
- `references/launch-modes.md` — full launch-mode decision tree (preload vs `init!`, suppress-auto-open, `:rf.xray/layout-host-selector`, host-CSS-variable resize, pop-out lifecycle, wired hotkeys)
- `references/panels.md` — the full tab tour in depth (6 Dynamic event-spine tabs + 5 Static registry-browse tabs, deeper "open it when…" guidance)
- `references/shared-components.md` — the components every L4 panel reuses (`edn-inspector/render-node`, `film_strip/header`, `focus_resolver`) + the tab-icon / L2-badge / cross-panel-arrow glyph reference
- `evals/evals.json` — trigger-eval fixtures (should-trigger + should-not-trigger entries, per skill-creator's description-optimisation contract)
- `.claude-plugin/plugin.json` — Claude Code Plugin packaging metadata
- `package.json` — npm packaging metadata (skill is also distributable as an Agent Skill)

## Relationship to other skills

- [`re-frame2-pair`](../re-frame2-pair/) — drives Xray programmatically from a live REPL. Xray owns the *seeing*; re-frame2-pair owns the *driving*.
- [`re-frame2`](../re-frame2/) — authors host application code. The host app provides the `[data-rf-xray-host]` column Xray mounts into.
- [`re-frame2-setup`](../re-frame2-setup/) — bootstraps a fresh re-frame2 project. The setup skill ensures the dev build is configured so Xray's `:preloads` entry can mount on first run.

This skill does **not** depend on or reference `re-frame-10x`. Xray is the structural successor — re-frame2's Tool-Pair surfaces replace the v1 reliance on the 10x dev tool entirely. The surface enumeration and the "supersedes re-frame-10x" claim live once in [`../shared/tool-pair-surfaces.md`](../shared/tool-pair-surfaces.md) (§Supersedes re-frame-10x); cite it rather than restating the surface list here.

## Status

Pre-alpha. The Xray surface itself is pre-alpha (some tabs are partial — the Machines tab renders through the shared xyflow styling under `panels/machines/`, still stabilising; Schemas / Hydration only render when the relevant feature is wired into the host; the Static Machines Sim engine is still stabilising). The Static catalogues themselves are full registry browsers, not stubs. The skill hedges accordingly: when a user asks about an in-progress surface it says so and points at the spec.

A future `re-frame2-xray-implementor` sibling skill is deferred to post-alpha until the Xray surface stabilises.

## Install

`re-frame2-xray` ships as part of the [`day8/re-frame2`](https://github.com/day8/re-frame2) monorepo. Clone re-frame2 and reference the skill from `skills/re-frame2-xray/`.

### Install the skill in Claude Code

#### Global — for you, across any repo

Clone the re-frame2 repo somewhere stable, then symlink the skill subdirectory into your user Claude config:

```bash
git clone https://github.com/day8/re-frame2.git ~/src/re-frame2
mkdir -p ~/.claude/skills
ln -s ~/src/re-frame2/skills/re-frame2-xray ~/.claude/skills/re-frame2-xray
```

#### Project-local — for your whole team via the repo

Copy the skill into the project's own `.claude/skills/re-frame2-xray/` and commit it. Teammates who clone the repo and open Claude Code there get the same pinned version.

```bash
cd your-re-frame2-project
cp -r /path/to/re-frame2/skills/re-frame2-xray .claude/skills/re-frame2-xray
git add .claude/skills/re-frame2-xray
```

#### Which to choose

- **Global** if you're the only person using Claude Code here, or you want one install shared across repos.
- **Project-local** if your team wants one pinned, shared version.
- **Both** is fine — the project-local install takes precedence when both are present.

## License

MIT
