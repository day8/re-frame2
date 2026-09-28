# Xray

Something just happened in your re-frame2 app. A button was clicked, a handler ran, a subscription recomputed, a view rendered, a machine moved, a route changed, or a schema check failed. Xray is the devtools panel that shows you that cascade as one record, in the page beside your app.

Xray reads what the framework already records: the trace stream, the epoch history, source coordinates, the registries and each frame's state. It adds no second runtime and does not change how your app runs.

![Xray open beside the standard-epochs testbed after five steps, with the Epoch tab showing one event's dispatch, coeffects, handler and app-db change](../images/xray/xray-tutorial-shell.png)

## The one-minute model

Every event that finishes produces an **epoch**: the event, app-db before and after, the effects that ran, the subscriptions that recomputed, the views that rendered, and the trace records emitted along the way. An epoch is the unit you debug.

Xray's screen is built around that:

- The top ribbon chooses the frame, the mode, filters and settings.
- The event list shows recent epochs for the selected frame.
- The tab strip chooses which view of the epoch you want.
- The detail panel shows that view.

Click an event and the event tabs (Epoch, app-db, Views, Trace, Machine and Routes) all show that one epoch. The other tabs read the app's current structure rather than one event. [2. Panel tour](02-panel-tour.md) walks through each tab.

## Two modes

**Dynamic** mode answers "what just happened?" It has the event list, and its tabs show the event you pick.

**Static** mode answers "what exists in this app?" It drops the event list and lists what is registered: machines, routes, schemas, flows and interceptors.

## When not to use Xray

Xray is a diagnostic reader. It is not a replacement for your editor, your tests, Story, or the browser's performance tools, and it does not ship in production.

It does not write your app-db, except when you press **Reset** to restore a past epoch, and it does not wrap or patch your handlers. What it does is answer the questions you ask when a re-frame2 app behaves strangely:

- What event started this?
- What state changed?
- What effects ran?
- Which subscriptions and views reacted?
- Where in the source should I look next?

## Xray, Story And Tests

Xray has no pass or fail view of its own. A verdict comes from your test runner or from Story's Tests tab. Xray shows the runtime behind the verdict: which events ran, what they changed, and what the trace recorded.

- When a Story variant fails, the Tests tab names the failing assertion and its Evidence panel links each step to Xray. [Xray inside Story](02-panel-tour.md#xray-inside-story) shows the embedded panel.
- Tests on the JVM or in Node run without Xray. They read the same epoch records with [`rf/epoch-history`](../api/re-frame.epoch.md#epoch-history).
- A bug you watched happen in Xray becomes a regression test by replaying its events. [From a past epoch to a test](03-time-travel.md#from-a-past-epoch-to-a-test) shows where to read them.
