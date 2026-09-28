# 3. Time-Travel Scrubbing

You need to look backward without losing the live app. This chapter explains Xray's time-travel model: the event spine is the scrubber, focusing a past row pins every lens to it, and following the newest event lets them move on again.

## The Event Spine Is The Timeline

Click an event row in the spine. Xray focuses that epoch. The detail panels now show app-db, trace rows, views, machine transitions, and route activity for that selected epoch.

The app can continue running while you inspect the past. Xray stays on the selected row until you move focus or follow the head.

## Following And Focused

Think of focus as having two postures:

- **Following**: Xray follows the newest epoch for the selected frame.
- **Focused**: Xray is pinned to an older epoch.

You pin an older epoch by clicking its event row or stepping backward. You return to following the newest from the ribbon controls.

The ribbon's **‹** (or `j`) steps to the previous event and **›** (or `k`) to the next. **»** (or `l`, or Shift+G) follows the newest event again. While an older row is focused, a strip under the list reads "↓ N newer events — » to follow"; click it to return to the newest. Space pauses and resumes following without focusing a row.

The important detail is that this is panel focus, not a magical fork of your app. The focused epoch is the record every tab reads. If you choose an older row, app-db, Views, Trace, Machine, and Routes all agree on that older row.

![An older event focused in the event list, with the strip under it reading ↓ 3 newer events — » to follow](../images/xray/xray-tutorial-epoch.png)

## Inspecting A Past Event

On the standard-epochs testbed:

1. Press **⏭ Step** three times. Each step adds two rows: `:standard-epochs/run-step`, then the event that step runs.
2. Click the first `:standard-epochs/run-step` row in Xray's event spine.
3. Open app-db. `:step` reads `0 ← was nil`: the state as the first step left it, not the `2` the third step left.
4. Open Trace. You see the trace records for that epoch.
5. Press **»**. The panels jump back to the newest epoch.

This is the heart of Xray: one historical selection drives every lens.

## Restore Versus Inspect

Inspecting a past epoch is read-only. It changes Xray's focus, not your app.

Rewinding the app is a stronger operation. The runtime can restore an epoch's canonical frame-state (`:frame-state-after`) into the target frame — app-db and runtime-db reinstalled together as one atomic frame-state, so machine snapshots and the route slice rewind alongside application state, not just the app-db partition. (The epoch record's `:db-before` / `:db-after` are optional app-db projections of that frame-state, not the restore target.) Restore is an explicit act — it changes the observed app. In day-to-day debugging, you usually inspect first. Restore only when you deliberately want to put the app back into that state.

The restore operation is a runtime primitive, not an Xray trick. Xray gives you the context; re-frame2 owns the state transition.

The **Reset ↺** button at the right end of the tab bar calls that primitive for the focused epoch, on the frame Xray is observing. It asks for no confirmation. It is disabled until an event is focused. When the runtime refuses the restore, the bar shows "Reset failed — epoch unavailable (see Trace)", and the Trace tab holds the reason. [`restore-epoch!`](../api/re-frame.epoch.md#restore-epoch) lists them: for example, an epoch whose event failed cannot be restored, and nor can one that has left the history.

## How Far Back You Can Go

Xray keeps the newest 50 epochs per frame. Change that with the **Epoch history** setting on Settings' General tab. Focus an event whose epoch has left the history and the panels say so; the Epoch tab reads "The selected epoch was evicted from the history buffer. Pick a more recent event."

## From A Past Epoch To A Test

A bug you reproduced with Xray open is a list of events, and replaying that list in a test rebuilds the same state. Focus each event in turn: the Epoch tab shows its event vector under DISPATCH and the coeffect values it recorded under RECORDABLE COEFFECTS. [Replay a bug as a regression test](../core/testing/pipeline-runs.md#replay-a-bug-as-a-regression-test) shows the test that replays them.

## Filters Keep The Spine Useful

Real apps produce noise. Xray's filter pills let you include or exclude event ids so the spine stays readable.

Good filter habits:

- Exclude high-volume housekeeping events when chasing a user action.
- Include only one feature namespace when debugging a focused flow.
- Clear filters before deciding an event did not happen.
- Remember that an event whose epoch errored stays in the list even when a filter would hide it. A warning does not keep a row visible.

A pattern is an event id or part of one:

| Pattern | Matches |
| --- | --- |
| `:auth/login` | exactly that event id |
| `:auth/*` | every id in the `auth` namespace |
| `:auth` | the id `:auth` and every id in the `auth` namespace |
| `login` | every id containing `login` |

With no IN pills, the spine shows everything no OUT pill matches. With IN pills, it shows only events matching one of them, minus the OUT matches. An event whose epoch errored stays in the list even when a pill or a mute would hide it; its tooltip reads "⚠ shown because it errored — a filter would normally hide it". The `:rf.xray/filters-auto-hide-error-overrides?` setting turns that off ([Configuration keys](api/config-keys.md#filters-cluster)).

Pills last for the page's life. A reload starts unfiltered, unless the host seeds a baseline set with `:rf.xray/filters`. Mutes, made from a row's right-click menu, are listed under the ribbon's 🔇 count.

The goal is not to hide complexity forever. It is to remove unrelated motion long enough to read the cascade you care about.
