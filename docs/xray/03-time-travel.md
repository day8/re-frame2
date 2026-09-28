# 3. Time-travel scrubbing

The bug happened three events ago and the app has moved on. You want to look at that earlier event without stopping the app or losing your place. In Xray the event list is the scrubber: focusing a past event points every event tab at it, and following the newest event lets them move on again.

## Following and focused

Xray is always in one of two states:

- **Following**: the tabs show the newest epoch for the selected frame, and move on as new events arrive.
- **Focused**: the tabs stay on an older epoch you picked.

You focus an older epoch by clicking its event (1 in the screenshot) or by stepping back. The ribbon's **‹** (or `j`) steps to the previous event and **›** (or `k`) to the next, and **»** (or `l`, or Shift+G) follows the newest event again (3). While an older event is focused, a strip under the list reads "↓ N newer events — » to follow" (2); click it to return to the newest. Space pauses and resumes following without focusing an event.

Focus changes what Xray shows, not your app. The app keeps running, and every event tab (Epoch, app-db, Views, Trace, Machine and Routes) shows the focused epoch.

![Xray with an older event focused, numbered: 1 the focused event, 2 the strip reading ↓ 3 newer events — » to follow, 3 the step and follow buttons, 4 the Epoch tab's numbered steps, 5 the Reset button](../images/xray/xray-tutorial-epoch.png)

## Inspecting a past event

On the standard-epochs testbed:

1. Press **⏭ Step** three times. Each step adds two events: `:standard-epochs/run-step`, then the event that step runs.
2. Click the first `:standard-epochs/run-step` event in Xray's event list.
3. Open app-db. `:step` reads `0 ← was nil`: the state as the first step left it, not the `2` the third step left.
4. Open Trace. It lists the trace records for that epoch.
5. Press **»**. The tabs jump back to the newest epoch.

## Restoring a past epoch

Looking at a past epoch changes nothing in your app. Restoring one does: **Reset ↺**, at the right end of the tab bar (5 in the screenshot above), puts the frame back into the state the focused event left it in. That includes app-db, machine snapshots and the current route, which are restored together.

Reset asks for no confirmation, and it is disabled until an event is focused. It calls the runtime's [`restore-epoch!`](../api/re-frame.epoch.md#restore-epoch) on the frame Xray is observing. When the runtime refuses, the bar shows "Reset failed — epoch unavailable (see Trace)" and the Trace tab holds the reason: for example, an epoch whose event failed cannot be restored, and neither can one that has left the history.

Inspect first. Restore only when you want the app itself back in that state, for example to try a fixed handler from the same starting point.

## How far back you can go

Xray keeps the newest 50 epochs per frame. Change that with the **Epoch history** setting on Settings' General tab. Focus an event whose epoch has left the history and the tabs say so; the Epoch tab reads "The selected epoch was evicted from the history buffer. Pick a more recent event."

## From a past epoch to a test

A bug you reproduced with Xray open is a list of events, and replaying that list in a test rebuilds the same state. Focus each event in turn: the Epoch tab shows its event vector under DISPATCH and the coeffect values it recorded under RECORDABLE COEFFECTS. [Replay a bug as a regression test](../core/testing/pipeline-runs.md#replay-a-bug-as-a-regression-test) shows the test that replays them.
