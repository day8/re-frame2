# re-frame2-xray

> Answers questions about **Xray**, re-frame2's in-app devtools panel: how to launch it, which tab shows what you are looking for, and what the controls around the tabs do.

## What it does

The skill answers three kinds of question about [Xray](../xray/index.md), the devtools panel for humans that ships with re-frame2:

1. **How do I launch Xray?** — the inline panel, the overlay fallback (`(xray/open-overlay!)`, for hosts that can't give Xray a layout column), the pop-out (`(xray/popout!)`), the programmatic `(xray/init! opts)` path, `(xray/focus! …)` to jump to a tab from code, the hotkeys, and the Dynamic ↔ Static mode toggle. There is no mobile launch mode.
2. **Which tab shows X?** — from the evidence you want (one dispatch, changed state, renders, raw ordering, machines and routes, server state, structure, registered definitions) to the one place to look first.
3. **What are the controls around the tabs for?** — the frame picker, time-travel inspect and `Reset` rewind, the filter pills, the command palette, and the Settings popup.

An answer names the mode and tab to open first, why, and the first thing to click — *"Dynamic → Views: pick the event, then read the render-cause chips"* — plus a second place to look only when it is the natural next step. It lists every tab only when you ask for the inventory. It reads nothing from your running app; it only reads its own reference notes.

## Two modes

Xray runs in one of two modes, switched by the mode pill or a hotkey. **Dynamic** is for inspecting a single dispatch, though some of its tabs browse live structure and do not change when you pick a different event. **Static** browses what is *registered* rather than what just happened. The full tab inventory is in [`references/panels.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-xray/references/panels.md), and the hotkeys in [`references/launch-lifecycle.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-xray/references/launch-lifecycle.md).

## When to reach for it

Use it when you want to *read* the Xray panel yourself: how to get it on screen, why it never appeared, which Dynamic tab or Static catalogue shows what you are after, or what a control around the tabs does. The `description` in its [`SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-xray/SKILL.md) is the text the agent matches your request against.

Every skill is listed under [Which skill do I want?](index.md#which-skill-do-i-want). The ones most easily confused with this one:

- **Asking the agent to look at or change the running app**, read-only included → [re-frame2-pair](re-frame2-pair.md). The line is a human reading the panel versus the agent reading the runtime, not read versus write.
- Implementing Xray itself → no skill yet; the spec under `tools/xray/spec/` is the source of truth.

## Kickoff

Ask the question you have about the panel — *"which Xray tab shows why this view re-rendered?"*, *"how do I pop Xray out onto a second monitor?"* — or type `/re-frame2-xray`.

## When the panel does not appear

Xray is loaded into dev builds through shadow-cljs `:preloads` and opens inline, in a column your app's layout provides and marks with `data-rf-xray-host`. It stays out of release builds because `:preloads` is dev build configuration — the manual `init!` / mount path has no `goog.DEBUG` check of its own. So if you install Xray from app code instead, keep the `:require` and the calls in a dev-only namespace.

The commonest launch failure is a preload that is in place and a page that loaded with no inline panel. Xray logs the reason to the console and reports it at `window.day8.re_frame2_xray.status()`, under `:diagnostic :reason`:

| Reason | Cause | Fix |
|---|---|---|
| `:missing-layout-host` | Nothing matched `[data-rf-xray-host]`. | Add the layout column, point the selector elsewhere, or use `(xray/open-overlay!)`. |
| `:no-substrate-adapter` | The host never called `rf/init!`. | Call the app's own `(rf/init! adapter)` before it renders; Xray's `init!` is not the fix. |
| `:auto-open-disabled` | The host set `:rf.xray/auto-open? false`. Not a failure. | Open it yourself (`Ctrl+Shift+C` or `(xray/open!)`), or drop the setting. |

The skill works through these with [`references/launch-modes.md` §Launch diagnostics](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-xray/references/launch-modes.md#launch-diagnostics).

## Where the skill lives

- Source: [`skills/re-frame2-xray/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-xray)
- `SKILL.md`: [`skills/re-frame2-xray/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-xray/SKILL.md) — its §Which reference leaf to load section names the note for each question.
- Xray source and spec: [`tools/xray/`](https://github.com/day8/re-frame2/tree/main/tools/xray).
- The Xray guide: [Xray](../xray/index.md).
