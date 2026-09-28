# 3. Time-travel scrubbing

The bug happened three events ago and the app has moved on. You want to look at that earlier event without stopping the app or losing your place. In Xray the event list is the scrubber: focusing a past event points every event tab at it, and following the newest event lets them move on again.

## Following and focused

Xray is always in one of two states:

- **Following**: the tabs show the newest epoch for the selected frame, and move on as new events arrive.
- **Focused**: the tabs stay on an older epoch you picked.

You focus an older epoch by clicking its event or by stepping back. The ribbon's **‹** (or `j`) steps to the previous event and **›** (or `k`) to the next. **»** (or `l`, or Shift+G) follows the newest event again. While an older event is focused, a strip under the list reads "↓ N newer events — » to follow"; click it to return to the newest. Space pauses and resumes following without focusing an event.

Focus changes what Xray shows, not your app. The app keeps running, and every event tab (Epoch, app-db, Views, Trace, Machine and Routes) shows the focused epoch.

![An older event focused in the event list, with the strip under it reading ↓ 3 newer events — » to follow](../images/xray/xray-tutorial-epoch.png)

## Inspecting a past event

On the standard-epochs testbed:

1. Press **⏭ Step** three times. Each step adds two events: `:standard-epochs/run-step`, then the event that step runs.
2. Click the first `:standard-epochs/run-step` event in Xray's event list.
3. Open app-db. `:step` reads `0 ← was nil`: the state as the first step left it, not the `2` the third step left.
4. Open Trace. It lists the trace records for that epoch.
5. Press **»**. The tabs jump back to the newest epoch.

## Restoring a past epoch

Looking at a past epoch changes nothing in your app. Restoring one does: **Reset ↺**, at the right end of the tab bar, puts the frame back into the state the focused event left it in. That includes app-db, machine snapshots and the current route, which are restored together.

Reset asks for no confirmation, and it is disabled until an event is focused. It calls the runtime's [`restore-epoch!`](../api/re-frame.epoch.md#restore-epoch) on the frame Xray is observing. When the runtime refuses, the bar shows "Reset failed — epoch unavailable (see Trace)" and the Trace tab holds the reason: for example, an epoch whose event failed cannot be restored, and neither can one that has left the history.

Inspect first. Restore only when you want the app itself back in that state, for example to try a fixed handler from the same starting point.

## How far back you can go

Xray keeps the newest 50 epochs per frame. Change that with the **Epoch history** setting on Settings' General tab. Focus an event whose epoch has left the history and the tabs say so; the Epoch tab reads "The selected epoch was evicted from the history buffer. Pick a more recent event."

## From a past epoch to a test

A bug you reproduced with Xray open is a list of events, and replaying that list in a test rebuilds the same state. Focus each event in turn: the Epoch tab shows its event vector under DISPATCH and the coeffect values it recorded under RECORDABLE COEFFECTS. [Replay a bug as a regression test](../core/testing/pipeline-runs.md#replay-a-bug-as-a-regression-test) shows the test that replays them.

## Filters keep the spine useful

Real apps produce noise. Xray's filter pills let you include or exclude event ids so the event list stays readable.

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

With no IN pills, the list shows everything no OUT pill matches. With IN pills, it shows only events matching one of them, minus the OUT matches. An event whose epoch errored stays in the list even when a pill or a mute would hide it; its tooltip reads "⚠ shown because it errored — a filter would normally hide it". The `:rf.xray/filters-auto-hide-error-overrides?` setting turns that off ([Configuration keys](api/config-keys.md#filters-cluster)).

Pills last for the page's life. A reload starts unfiltered, unless the host seeds a baseline set with `:rf.xray/filters`. Mutes, made from a row's right-click menu, are listed under the ribbon's 🔇 count.
