# re-frame2-xray

> A read-only tour of **Xray**, the re-frame2 in-app devtools panel. Answers three questions and only three: *how do I launch Xray?*, *which tab — across its Dynamic and Static modes — shows X?*, and *what's the chrome around the tabs for?*

## What it does

The `re-frame2-xray` skill is a tour guide for [Xray](../xray/index.md), the human-facing devtools panel that ships with re-frame2. Xray is preloaded into dev builds via shadow-cljs `:preloads` and renders true-inline on the right side of the host app. It stays out of a release build by **build placement** — `:preloads` is dev build configuration — rather than by a gate inside Xray: the manual `init!` / mount path carries no `goog.DEBUG` gate, so a host installing Xray from app code keeps the `:require` and the calls in a dev-only namespace.

The skill answers three questions:

1. **How do I launch Xray?** — the inline panel, the overlay fallback (`(xray/open-overlay!)`, for hosts that can't give Xray a layout column), the pop-out (`(xray/popout!)`), the programmatic `(xray/init! opts)` path, the wired hotkeys, and the Dynamic ↔ Static mode toggle.
2. **Which tab shows X?** — a route card from the evidence sought (one dispatch, changed state, renders, raw ordering, machines/routes, server state, structure, registered definitions) to one first surface: the visible mode/tab, the reason, and the first interaction.
3. **What's the chrome around the tabs for?** — the first-screen navigation primitives: time-travel inspect / `Reset`-rewind, the filter pills, the command palette, and the Settings popup.

## Two modes

Xray runs in one of two modes, flipped by the L1 mode pill or `Cmd/Ctrl+Shift+M`:

- **Dynamic** — the event-coupled spine (4-layer chrome). 10 tabs: **Epoch · app-db · Views · Trace · Machine · Routes · Resources · Graph · Frames · Hicasso**. Dynamic names the *shell*, not a uniform data scope: six tabs are lenses on the one focused event, Resources is mixed, and Graph, Frames and Hicasso browse live structure and do **not** rebind when you pick an epoch. There is **no Issues tab** — issues surface inline.
- **Static** — event-INDEPENDENT registry browse (3-layer chrome, no spine). 5 tabs: **Machines · Routes · Schemas · Flows · Interceptors** — catalogues of what's *registered*.

When the user wants to inspect a single dispatch, that's Dynamic; when they want to browse the whole registry, that's Static. The canonical tab inventory and scope matrix live in the skill package, at [`skills/re-frame2-xray/references/panels.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-xray/references/panels.md) — this page summarizes the role and defers the anatomy to that authority.

## Wired hotkeys

Four hotkey families have keydown listeners installed:

| Key | Scope | Action |
|---|---|---|
| `Ctrl+Shift+C` | global | Toggle the Xray shell. |
| `Cmd/Ctrl+Shift+M` | global | Toggle mode — Dynamic ↔ Static. |
| `Cmd/Ctrl+K` | global | Open the command palette. |
| `Space` `L` `j` `k` `G` `,`/`s` | focus-gated | Spine + chrome shortcuts (only inside the shell, off editable fields). `Esc` is modal-local, not a wired spine key. |

## When to reach for it

Load this skill when the user wants to *read* the Xray panel — "open Xray", "where is X in Xray", "which Xray tab shows…", "Xray Static mode", "browse registered machines/routes/schemas in Xray", "Ctrl+Shift+C", "Xray popout", "Xray machine inspector", "Xray Frames tab", "which images loaded which frames in Xray", "Xray Hicasso tab", "why did this boundary re-render".

Do **not** use this skill for:

- **Agent runtime access** — the user asking the agent to inspect or change the running app, read-only included (read a sub, get a path, snapshot state, walk traces, dispatch, hot-swap) → use [re-frame2-pair](re-frame2-pair.md). The boundary is human panel vs agent runtime, not read vs write.
- Writing new application code → use [re-frame2](re-frame2.md).
- Implementing Xray itself → the spec under `tools/xray/spec/` is the source of truth (no implementor skill yet).

## Where the skill lives

- Source: [`skills/re-frame2-xray/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-xray)
- `SKILL.md`: [`skills/re-frame2-xray/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-xray/SKILL.md)
- Reference leaves: [`skills/re-frame2-xray/references/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-xray/references) — `launch-modes.md` (launch decision tree + hotkeys), `panels.md` (the canonical tab inventory + scope matrix), the per-family depth leaves (`panels-epoch.md`, `panels-state.md`, `panels-domains.md`, `panels-resources.md`, `panels-structure.md`), and `chrome.md` (the first-screen chrome).
- Xray source + spec: [`tools/xray/`](https://github.com/day8/re-frame2/tree/main/tools/xray).
- Human-facing Xray guide: [Xray](../xray/index.md).
- Live-runtime companion skill: [`re-frame2-pair`](re-frame2-pair.md).
