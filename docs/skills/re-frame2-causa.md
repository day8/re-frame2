# re-frame2-causa

> A read-only tour of **Causa**, the re-frame2 in-app devtools panel. Answers two questions and only two: *how do I launch Causa?* and *which tab — across its Dynamic and Static modes — shows X?*

## What it does

The `re-frame2-causa` skill is a tour guide for [Causa](../causa/index.md), the human-facing devtools panel that ships with re-frame2. Causa is preloaded into dev builds via shadow-cljs `:preloads` and renders true-inline on the right side of the host app; production builds elide it entirely through the `interop/debug-enabled?` gate.

The skill answers two questions:

1. **How do I launch Causa?** — the inline panel, the pop-out (`(causa/popout!)`), the programmatic `(causa/init! opts)` path, the wired hotkeys, and the Dynamic ↔ Static mode toggle.
2. **Which tab shows X?** — a one-line purpose for each tab, across both modes.

## Two modes

Causa runs in one of two modes, flipped by the L1 mode pill or `Cmd/Ctrl+Shift+M`:

- **Dynamic** — the event-coupled spine (4-layer chrome). Every tab is a lens on the *one focused event*. 8 tabs: **Event · App DB · View · Trace · Machines · Machines Canvas · Routing · Issues** (mnemonics `e a v t m c r i`).
- **Static** — event-INDEPENDENT registry browse (3-layer chrome, no spine). Every tab is a catalogue of what's *registered* in the picked frame. 5 tabs: **Machines · Routes · Schemas · Flows · Interceptors**.

When the user wants to inspect a single dispatch, that's Dynamic; when they want to browse the whole registry, that's Static.

## Wired hotkeys

Four hotkey families have keydown listeners installed:

| Key | Scope | Action |
|---|---|---|
| `Ctrl+Shift+C` | global | Toggle the Causa shell. |
| `Cmd/Ctrl+Shift+M` | global | Toggle mode — Dynamic ↔ Static. |
| `Cmd/Ctrl+K` | global | Open the command palette. |
| `Space` `L` `j` `k` `G` `,`/`s` `Esc` | focus-gated | Spine + chrome shortcuts (only inside the shell, off editable fields). |

## When to reach for it

Load this skill when the user wants to *read* the Causa panel — "open Causa", "where is X in Causa", "which Causa tab shows…", "Causa Static mode", "browse registered machines/routes/schemas in Causa", "Ctrl+Shift+C", "Causa popout", "Causa machine inspector".

Do **not** use this skill for:

- **Driving** Causa from a live runtime (dispatch, mutate `app-db`, hot-swap, time-travel) → use [re-frame2-pair](re-frame2-pair.md). Causa owns the *seeing*; re-frame2-pair owns the *driving*.
- Writing new application code → use [re-frame2](re-frame2.md).
- Implementing Causa itself → the spec under `tools/causa/spec/` is the source of truth (no implementor skill yet).

## Where the skill lives

- Source: [`skills/re-frame2-causa/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-causa)
- `SKILL.md`: [`skills/re-frame2-causa/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-causa/SKILL.md)
- Reference leaves: [`skills/re-frame2-causa/references/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-causa/references) — `launch-modes.md` (launch decision tree + hotkeys) and `panels.md` (the full tab tour across both modes).
- Causa source + spec: [`tools/causa/`](https://github.com/day8/re-frame2/tree/main/tools/causa).
- Human-facing Causa guide: [Causa](../causa/index.md).
- Live-runtime companion skill: [`re-frame2-pair`](re-frame2-pair.md).
