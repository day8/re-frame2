# re-frame2-causa

> ↑ [`skills/`](../) — index of all re-frame2 skills.

`re-frame2-causa` is a Claude Code **tour skill** for [Causa](https://github.com/day8/re-frame2/tree/main/tools/causa) — the re-frame2 in-app devtools panel. It answers two questions, and only two:

1. **How do I launch Causa?** — the inline panel, the pop-out, the programmatic `init!`, the wired hotkeys, and the Dynamic ↔ Static mode toggle.
2. **Which tab shows X?** — a one-line purpose for each tab across both modes: the 8 Dynamic event-spine tabs and the 5 Static registry-browse tabs.

Workflow procedures (find-wrong-sub, scrub-bad-epoch, click-to-source, redaction-indicator semantics) are out of scope for this iteration — see `SKILL.md` §Out of scope for what to do when one of those comes up.

## What Causa is

An in-app true-inline devtools panel for re-frame2 applications, preloaded into dev builds via shadow-cljs `:preloads`. Causa consumes re-frame2's instrumentation surface (Spec 009 trace bus, Tool-Pair epoch history, the registrar query API) — it adds nothing the framework didn't already expose. Production builds elide the entire surface through the universal `interop/debug-enabled?` gate.

Causa is the **human-facing** panel; for an AI agent surface against the running app, see [`re-frame2-pair`](../re-frame2-pair/) (the raw nREPL pair-programming companion).

## Repo contents

- `SKILL.md` — the skill itself
- `references/launch-modes.md` — full launch-mode decision tree (preload vs `init!`, suppress-auto-open, `:rf.causa/layout-host-selector`, host-CSS-variable resize, pop-out lifecycle, wired hotkeys)
- `references/panels.md` — the full tab tour in depth (8 Dynamic event-spine tabs + 5 Static registry-browse tabs, shared components, iconography, deeper "open it when…" guidance)
- `evals/evals.json` — trigger-eval fixtures (should-trigger + should-not-trigger entries, per skill-creator's description-optimisation contract)
- `.claude-plugin/plugin.json` — Claude Code Plugin packaging metadata
- `package.json` — npm packaging metadata (skill is also distributable as an Agent Skill)

## Relationship to other skills

- [`re-frame2-pair`](../re-frame2-pair/) — drives Causa programmatically from a live REPL. Causa owns the *seeing*; re-frame2-pair owns the *driving*.
- [`re-frame2`](../re-frame2/) — authors host application code. The host app provides the `[data-rf-causa-host]` column Causa mounts into.
- [`re-frame2-setup`](../re-frame2-setup/) — bootstraps a fresh re-frame2 project. The setup skill ensures the dev build is configured so Causa's `:preloads` entry can mount on first run.

This skill does **not** depend on or reference `re-frame-10x`. Causa is the structural successor — re-frame2's Tool-Pair surfaces (`register-listener!`, `register-epoch-listener!`, `epoch-history`, `restore-epoch`, `app-schemas`) replace the v1 reliance on the 10x dev tool entirely.

## Status

Pre-alpha. The Causa surface itself is pre-alpha (some tabs are partial — the Machines tab renders through the shared xyflow styling under `panels/machines/`, still stabilising; Schemas / Hydration only render when the relevant feature is wired into the host; several Static tabs carry placeholder beads). The skill hedges accordingly: when a user asks about an in-progress surface it says so and points at the spec.

A future `re-frame2-causa-implementor` sibling skill is deferred to post-alpha until the Causa surface stabilises.

## Install

`re-frame2-causa` ships as part of the [`day8/re-frame2`](https://github.com/day8/re-frame2) monorepo. Clone re-frame2 and reference the skill from `skills/re-frame2-causa/`.

### Install the skill in Claude Code

#### Global — for you, across any repo

Clone the re-frame2 repo somewhere stable, then symlink the skill subdirectory into your user Claude config:

```bash
git clone https://github.com/day8/re-frame2.git ~/src/re-frame2
mkdir -p ~/.claude/skills
ln -s ~/src/re-frame2/skills/re-frame2-causa ~/.claude/skills/re-frame2-causa
```

#### Project-local — for your whole team via the repo

Copy the skill into the project's own `.claude/skills/re-frame2-causa/` and commit it. Teammates who clone the repo and open Claude Code there get the same pinned version.

```bash
cd your-re-frame2-project
cp -r /path/to/re-frame2/skills/re-frame2-causa .claude/skills/re-frame2-causa
git add .claude/skills/re-frame2-causa
```

#### Which to choose

- **Global** if you're the only person using Claude Code here, or you want one install shared across repos.
- **Project-local** if your team wants one pinned, shared version.
- **Both** is fine — the project-local install takes precedence when both are present.

## License

MIT
