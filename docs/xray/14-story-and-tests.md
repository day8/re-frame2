# 14. Xray, Story and tests

A Story variant or a test failed, and you want to see what the app did. Xray gives no pass or fail verdict of its own: the verdict comes from your test runner or from Story's Tests tab. Xray shows the runtime behind it: which events ran, what they changed, and what the trace recorded.

## From a failing variant into Xray

Story runs each variant's script and checks its assertions. When one fails:

1. Story's **Tests** tab names the failing assertion; **show detail** gives the expected and actual values.
2. **open in Evidence →** opens the run's narrative in Story's right rail, with the failing step selected. Each step lists the events it dispatched, with the epoch each one produced.
3. Each of those events carries **Xray: Epoch**, **Xray: App-db** and **Xray: Trace** links. A link points the Xray panel in the rail at that tab and that event's epoch.

From there you read the epoch as you would in Xray anywhere else. [Xray, earned at failure](../story/06-xray-earned-at-failure.md) in the Story guide follows one failure through these steps, with screenshots.

## Xray in Story's right rail

Story embeds Xray in its right rail, one panel at a time. A row of chips picks the panel (Epoch, App-db, Views, Trace, Machines or Routing), under a strip of the selected variant's recent events. Click an event in the strip to focus the panel on that epoch. A story or variant can choose the panel the rail opens on; [Xray in the right rail](../story/06-xray-earned-at-failure.md#xray-in-the-right-rail) in the Story guide shows how.

Each variant runs in its own frame, registered under the variant's id, and the rail watches the selected variant's frame. Select another variant and Xray follows it. **Pop out** opens the full Xray shell, with every tab, in a second window.

Story switches off Xray's keyboard shortcuts so its own keys keep working. Inside Story, and in a shell popped out from it, use the mouse.

Story points Xray at a frame, an epoch and a tab with `focus!`, which any host can call. [Focusing a panel from a host](api/mount-control.md#focusing-a-panel-from-a-host) lists its keys.

## Tests without Xray

Tests on the JVM or in Node run without Xray. They read the same epoch records Xray shows, with [`rf/epoch-history`](../api/re-frame.epoch.md#epoch-history): the event, app-db before and after, the effects and the trace. So a test can assert on anything you saw in Xray.

Treat a schema violation as a failure even when the final app-db looks right; the runtime may have rolled the bad write back. [Violations in tests](06-schema-timeline.md#violations-in-tests) explains why, and how Story counts them.

## From a bug you saw in Xray to a test

A bug you reproduced with Xray open is a list of events, and replaying that list in a test rebuilds the same state. [From a past epoch to a test](03-time-travel.md#from-a-past-epoch-to-a-test) shows where Xray shows each event's vector and the coeffect values it recorded, and links the test that replays them.
