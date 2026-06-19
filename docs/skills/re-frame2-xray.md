# re-frame2-xray

> A read-only tour of **Xray**, the re-frame2 in-app devtools panel. Answers three questions and only three: *how do I launch Xray?*, *which tab — across its Dynamic and Static modes — shows X?*, and *what's the chrome around the tabs for?*

## What it does

The `re-frame2-xray` skill is a tour guide for [Xray](../xray/index.md), the human-facing devtools panel that ships with re-frame2. Xray is preloaded into dev builds via shadow-cljs `:preloads` and renders true-inline on the right side of the host app; production builds elide it entirely through the `interop/debug-enabled?` gate.

The skill answers three questions:

1. **How do I launch Xray?** — the inline panel, the overlay fallback (`(xray/open-overlay!)`, for hosts that can't give Xray a layout column), the pop-out (`(xray/popout!)`), the programmatic `(xray/init! opts)` path, the wired hotkeys, and the Dynamic ↔ Static mode toggle.
2. **Which tab shows X?** — a one-line purpose for each tab, across both modes.
3. **What's the chrome around the tabs for?** — the first-screen navigation primitives: time-travel inspect / `Reset`-rewind, the filter pills, the command palette, and the Settings popup.

## Two modes

Xray runs in one of two modes, flipped by the L1 mode pill or `Cmd/Ctrl+Shift+M`:

- **Dynamic** — the event-coupled spine (4-layer chrome). Every tab is a lens on the *one focused event*. 9 tabs: **Epoch · app-db · Views · Trace · Machine · Routes · Resources · Graph · Modules** (mnemonics `e a v t m r s g u`) — the core six plus the cross-feature **Resources** (declarative server-state), **Graph** (Xray's UI over the EP-0014 derivation/process graph), and **Modules** (its EP-0023 `image → frame → event stream` lens — which image loaded which frame — with the retained-internal EP-0013 realm / module substrate below) lenses. There is **no Issues tab** — issues surface inline (in the Epoch cascade, the L2 event-row pink-wash, and the always-on issues-ribbon signal).
- **Static** — event-INDEPENDENT registry browse (3-layer chrome, no spine). Every tab is a catalogue of what's *registered* in the picked frame's **realm** (the registrar is realm-owned, not frame-owned — in the common single-realm app this is just the picked frame's registrations). 5 tabs: **Machines · Routes · Schemas · Flows · Interceptors**.

When the user wants to inspect a single dispatch, that's Dynamic; when they want to browse the whole registry, that's Static.

## Wired hotkeys

Four hotkey families have keydown listeners installed:

| Key | Scope | Action |
|---|---|---|
| `Ctrl+Shift+C` | global | Toggle the Xray shell. |
| `Cmd/Ctrl+Shift+M` | global | Toggle mode — Dynamic ↔ Static. |
| `Cmd/Ctrl+K` | global | Open the command palette. |
| `Space` `L` `j` `k` `G` `,`/`s` `Esc` | focus-gated | Spine + chrome shortcuts (only inside the shell, off editable fields). |

## When to reach for it

Load this skill when the user wants to *read* the Xray panel — "open Xray", "where is X in Xray", "which Xray tab shows…", "Xray Static mode", "browse registered machines/routes/schemas in Xray", "Ctrl+Shift+C", "Xray popout", "Xray machine inspector", "Xray Modules tab", "what realms/frames are installed in Xray".

Do **not** use this skill for:

- **Driving** Xray from a live runtime (dispatch, mutate `app-db`, hot-swap, time-travel) → use [re-frame2-pair](re-frame2-pair.md). Xray owns the *seeing*; re-frame2-pair owns the *driving*.
- Writing new application code → use [re-frame2](re-frame2.md).
- Implementing Xray itself → the spec under `tools/xray/spec/` is the source of truth (no implementor skill yet).

## Where the skill lives

- Source: [`skills/re-frame2-xray/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-xray)
- `SKILL.md`: [`skills/re-frame2-xray/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-xray/SKILL.md)
- Reference leaves: [`skills/re-frame2-xray/references/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-xray/references) — `launch-modes.md` (launch decision tree + hotkeys) and `panels.md` (the full tab tour across both modes).
- Xray source + spec: [`tools/xray/`](https://github.com/day8/re-frame2/tree/main/tools/xray).
- Human-facing Xray guide: [Xray](../xray/index.md).
- Live-runtime companion skill: [`re-frame2-pair`](re-frame2-pair.md).
