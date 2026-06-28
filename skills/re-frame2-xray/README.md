# re-frame2-xray

> ↑ [`skills/`](..) — index of all re-frame2 skills.

`re-frame2-xray` is a Claude Code **tour skill** for [Xray](https://github.com/day8/re-frame2/tree/main/tools/xray) — the re-frame2 in-app devtools panel. It answers three questions, and only three:

1. **How do I launch Xray?** — the inline panel, the overlay fallback (`open-overlay!`, for hosts that can't give Xray a layout column), the pop-out, the programmatic `init!`, the wired hotkeys, and the Dynamic ↔ Static mode toggle.
2. **Which tab shows X?** — a one-line purpose for each tab across both modes: the 9 Dynamic event-spine tabs (Epoch · app-db · Views · Trace · Machine · Routes · Resources · Graph · Modules) and the 5 Static registry-browse tabs. The **Graph** tab is Xray's UI over the EP-0014 derivation/process graph; the **Modules** tab (rendered in the tab bar as **Frames**) is its EP-0023 `image -> frame -> event stream` lens (which image loaded which frame, how a frame resolves its registrations — no realm / app / module browse dimension; an L4-only tab — focusable, no standalone mount facade). The underlying graph accessor stays internal (no `re-frame.core` facade export).
3. **What's the chrome around the tabs for?** — the first-screen navigation primitives: time-travel inspect / `Reset`-rewind, the filter-pill cluster, the command palette, and the Settings popup.

Workflow procedures (find-wrong-sub, scrub-bad-epoch, click-to-source, redaction-marker semantics) are out of scope — see `SKILL.md` §Out of scope for what to do when one of those comes up.

## What Xray is

An in-app true-inline devtools panel for re-frame2 applications, preloaded into dev builds via shadow-cljs `:preloads`. Xray consumes re-frame2's instrumentation surface (Spec 009 trace bus, Tool-Pair epoch history, the registrar query API) — it adds nothing the framework didn't already expose. Production builds elide the entire surface through the universal `interop/debug-enabled?` gate.

Xray is the **human-facing** panel; for an AI agent surface against the running app, see [`re-frame2-pair`](../re-frame2-pair) (the raw nREPL pair-programming companion).

## Repo contents

- `SKILL.md` — the compact tour/router (launch quick-reference, mode/tab chooser, chrome one-liners, and the leaf-loading guide)
- `references/launch-modes.md` — full launch-mode decision tree (preload vs `init!`, suppress-auto-open, `:rf.xray/layout-host-selector`, host-CSS-variable resize, the `open-overlay!` no-layout-host fallback, pop-out lifecycle, wired hotkeys)
- `references/panels.md` — the full tab tour in depth (9 Dynamic event-spine tabs + 5 Static registry-browse tabs, deeper "open it when…" guidance, the panel → content-home mapping for surfaces that are not their own tab)
- `references/chrome.md` — the first-screen chrome inventory in depth (LIVE/RETRO, time-travel rewind, filter pills, command-palette sources, the Settings-popup tabs, the Snapshot app-db redaction contract)
- `references/shared-components.md` — the components every L4 panel reuses (`edn-inspector/render-node`, `film_strip/header`, `focus_resolver`) + the tab-icon / L2-badge / cross-panel-arrow glyph reference
- `evals/evals.json` — eval fixtures (trigger accuracy + answer-quality assertions for the high-drift launch / chrome / tab-routing prompts)
- `evals/README.md` — the eval harness: coverage table, schema, and how to run the answer-quality checks
- `.claude-plugin/plugin.json` — Claude Code Plugin packaging metadata
- `package.json` — npm packaging metadata (skill is also distributable as an Agent Skill)

## Relationship to other skills

- [`re-frame2-pair`](../re-frame2-pair) — drives Xray programmatically from a live REPL. Xray owns the *seeing*; re-frame2-pair owns the *driving*.
- [`re-frame2`](../re-frame2) — authors host application code. The host app provides the `[data-rf-xray-host]` column Xray mounts into.
- [`re-frame2-setup`](../re-frame2-setup) — bootstraps a fresh re-frame2 project. The setup skill ensures the dev build is configured so Xray's `:preloads` entry can mount on first run.

This skill does **not** depend on or reference `re-frame-10x` — Xray is its structural successor (re-frame2's Tool-Pair surfaces replace the v1 reliance on the 10x dev tool entirely). The surface enumeration + "supersedes re-frame-10x" claim live once in [`../shared/tool-pair-surfaces.md`](../shared/tool-pair-surfaces.md) (§Supersedes re-frame-10x); cite it rather than restating here.

## Status

Pre-alpha. Some tabs are partial: the Machines tab renders through the shared xyflow styling under `panels/machines/` (still stabilising); Schemas / Hydration only render when the relevant feature is wired into the host; the Static Machines Sim engine is still stabilising. The Static catalogues themselves are full registry browsers, not stubs. The skill hedges accordingly: when a user asks about an in-progress surface it says so and points at the spec.

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

**Link, never `cp -r`.** A copy snapshots the skill and then drifts as the upstream is maintained — Claude Code keeps loading the stale copy, and fixes/evals never reach your team. Link your project's `.claude/skills/re-frame2-xray/` to the cloned re-frame2 source so the active skill is the upstream by construction:

```bash
cd your-re-frame2-project
mkdir -p .claude/skills
ln -s ~/src/re-frame2/skills/re-frame2-xray .claude/skills/re-frame2-xray   # macOS / Linux (symlink)
```

```powershell
# Windows (junction, no admin):
New-Item -ItemType Junction -Path .claude\skills\re-frame2-xray -Target $HOME\src\re-frame2\skills\re-frame2-xray
```

A symlink/junction is not committable in a portable way, so don't `git add` it; instead document the one-liner above in your project's README (or vendor with a deliberate update procedure — see below) so each teammate links on clone. This mirrors the central skill-install contract — see [`skills/README.md` §Installing (link, never copy)](../README.md#installing-link-never-copy) and the repo's `scripts/install-skills.sh` / `scripts/install-skills.ps1` installer, which links *every* skill in one idempotent pass.

**If you must vendor a pinned copy** (e.g. a fully offline team that can't reference the upstream clone), treat it as a deliberate pinned fork: `cp -r` the skill, record the upstream commit you copied from, and re-run the copy whenever you pull upstream fixes. Don't reach for `cp -r` as the default — it silently drifts.

#### Which to choose

- **Global** if you're the only person using Claude Code here, or you want one install shared across repos.
- **Project-local (linked)** if your team wants one shared install tracking the upstream source.
- **Vendored (pinned fork)** only when an offline team can't reference an upstream clone — and only with an explicit update procedure.
- **Both global + project-local** is fine — the project-local install takes precedence when both are present.

## License

MIT
