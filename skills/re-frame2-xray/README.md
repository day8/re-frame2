# re-frame2-xray

> ↑ [`skills/`](https://github.com/day8/re-frame2/tree/main/skills) — index of all re-frame2 skills.

`re-frame2-xray` is a Claude Code tour skill for [Xray](https://github.com/day8/re-frame2/tree/main/tools/xray) — the re-frame2 in-app devtools panel. It is question-first: a concrete debugging question lands on the one visible mode/tab to open first, with the reason and the first interaction. It answers 3 questions, and only 3:

1. How do I launch Xray? — the inline panel, the overlay fallback (`open-overlay!`, for hosts that can't give Xray a layout column), the pop-out, the programmatic `init!`, the wired hotkeys, and the Dynamic ↔ Static mode toggle.
2. Which tab shows X? — a route card from the evidence sought (one dispatch, changed state, renders, raw ordering, machines/routes, server state, structure, registered definitions) to one first surface, across the 10 Dynamic event-spine tabs (Epoch · app-db · Views · Trace · Machine · Routes · Resources · Graph · Frames · Hicasso) and the 5 Static registry-browse tabs. Dynamic is the shell mode, not a uniform data scope: six tabs are focused-epoch lenses, Resources is mixed, and Graph, Frames and Hicasso read live structure and do not rebind to the selected epoch. The canonical inventory + scope matrix live in `references/panels.md`.
3. What's the chrome around the tabs for? — the first-screen navigation primitives: time-travel inspect / `Reset`-rewind, the filter-pill cluster, the command palette, and the Settings popup.

Workflow procedures (find-wrong-sub, scrub-bad-epoch, click-to-source, redaction-marker semantics) are out of scope — see `SKILL.md` §Out of scope for what to do when one of those comes up.

## What Xray is

An in-app true-inline devtools panel for re-frame2 applications, preloaded into dev builds via shadow-cljs `:preloads`. Xray consumes re-frame2's instrumentation surface (Spec 009 trace bus, Tool-Pair epoch history, the registrar query API) — it adds nothing the framework didn't already expose. The preload's `interop/debug-enabled?` gate is what keeps the surface out of production builds — it wraps the preload's boot block and nothing else. The programmatic `init!` path is not behind it, so a host that installs Xray from app code keeps the require out of its own release build.

Xray is the human-facing panel; when the user asks an agent to inspect or change the running app — read-only included — that is [`re-frame2-pair`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-pair), the agent-facing runtime companion. The boundary is human panel vs agent runtime, not read vs write.

## Repo contents

- `SKILL.md` — the question-first router: the actor fork, the route card (question → first surface), the launch quick-reference, chrome one-liners, and the leaf-loading guide
- `references/launch-modes.md` — the launch decision tree (preload, `:rf.xray/layout-host-selector`, host-CSS-variable resize, suppress-auto-open, missing-host recovery, the `open-overlay!` no-layout-host fallback)
- `references/launch-programmatic.md` — driving Xray from code: `init!` opts and the `focus!` deep-link command
- `references/launch-lifecycle.md` — pop-out lifecycle, the wired hotkey contract, hidden-state semantics, disabling Xray, production posture
- `references/panels.md` — the compact canonical tab inventory (10 Dynamic + 5 Static), the scope matrix, and the panel → content-home mapping for surfaces that are not their own tab
- `references/panels-epoch.md` · `panels-state.md` · `panels-domains.md` · `panels-resources.md` · `panels-structure.md` — one leaf per panel family (the Epoch cascade + Trace + issues; app-db + Views; Machine + Routes; Resources; Graph + Frames + Hicasso) — a deep question loads only its family
- `references/chrome.md` — the first-screen chrome inventory in depth (LIVE/RETRO, time-travel rewind, filter pills, command-palette sources, the Settings-popup tabs, the Snapshot app-db redaction contract)
- `references/shared-components.md` — the components every L4 panel reuses (`edn-inspector/render-node`, `focus_resolver`) + the tab-icon / L2-badge / cross-panel-arrow glyph reference
- `evals/evals.json` — eval fixtures (trigger accuracy + answer-quality/route-quality assertions for the high-drift launch / chrome / tab-routing prompts)
- `evals/README.md` — the eval harness: coverage table, schema, and how to run the answer-quality checks
- `.claude-plugin/plugin.json` — Claude Code Plugin packaging metadata
- `package.json` — npm packaging metadata (skill is also distributable as an Agent Skill)

## Relationship to other skills

- [`re-frame2-pair`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-pair) — the agent-facing runtime companion: reads and drives the running app (read-sub, get-path, snapshots, trace/epoch reads, dispatch, hot-swap). Xray is the human's panel; Pair is the agent's runtime access, read or write.
- [`re-frame2`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2) — authors host application code. The host app provides the `[data-rf-xray-host]` column Xray mounts into.
- [`re-frame2-setup`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-setup) — bootstraps a fresh re-frame2 project. The setup skill ensures the dev build is configured so Xray's `:preloads` entry can mount on first run.

This skill does not depend on or reference `re-frame-10x` — Xray is its structural successor (re-frame2's Tool-Pair surfaces replace the v1 reliance on the 10x dev tool entirely; [`spec/Tool-Pair.md` §Implications for downstream tools](https://github.com/day8/re-frame2/blob/main/spec/Tool-Pair.md#implications-for-downstream-tools) owns that contract).

## Status

Pre-alpha; some surfaces are still stabilising — the Machine tab renders through the shared machines-viz MachineChart mounted via `panels/machine_canvas.cljs`; the Static Machines Sim engine; and the schema / hydration inline rows, which populate only when the host wired those features. The Static catalogues themselves are full registry browsers, not stubs. The skill hedges accordingly — see `SKILL.md` §Style guidance (Pre-alpha hedge).

There is no `re-frame2-xray-implementor` sibling skill; for implementing Xray itself, the spec is the answer.

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

Link, never `cp -r`. A copy snapshots the skill and then drifts as the upstream is maintained — Claude Code keeps loading the stale copy, and fixes/evals never reach your team. Link your project's `.claude/skills/re-frame2-xray/` to the cloned re-frame2 source so the active skill is the upstream by construction:

```bash
cd your-re-frame2-project
mkdir -p .claude/skills
ln -s ~/src/re-frame2/skills/re-frame2-xray .claude/skills/re-frame2-xray   # macOS / Linux (symlink)
```

```powershell
# Windows (junction, no admin):
New-Item -ItemType Junction -Path .claude\skills\re-frame2-xray -Target $HOME\src\re-frame2\skills\re-frame2-xray
```

A symlink/junction is not committable in a portable way, so don't `git add` it; instead document the one-liner above in your project's README (or vendor with a deliberate update procedure — see below) so each teammate links on clone. This mirrors the central skill-install contract — see [`skills/README.md` §Installing (link, never copy)](https://github.com/day8/re-frame2/blob/main/skills/README.md#installing-link-never-copy) and the repo's `scripts/install-skills.sh` / `scripts/install-skills.ps1` installer, which links every skill in one idempotent pass.

If you must vendor a pinned copy (for example a fully offline team that can't reference the upstream clone), treat it as a deliberate pinned fork: `cp -r` the skill, record the upstream commit you copied from, and re-run the copy whenever you pull upstream fixes. Don't reach for `cp -r` as the default — it silently drifts.

#### Which to choose

- global if you're the only person using Claude Code here, or you want one install shared across repos
- project-local (linked) if your team wants one shared install tracking the upstream source
- vendored (pinned fork) only when an offline team can't reference an upstream clone — and only with an explicit update procedure
- both global + project-local is fine — the project-local install takes precedence when both are present

## License

MIT
