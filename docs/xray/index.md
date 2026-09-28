# Xray

Something just happened in your re-frame2 app. A button was clicked, a handler ran, a subscription recomputed, a view rendered, a machine moved, a route changed, or a schema check failed. Xray is the devtools panel that shows you that cascade as one record, in the page beside your app.

Xray reads what the framework already records: the trace stream, the epoch history, source coordinates, the registries and each frame's state. It adds no second runtime and does not change how your app runs.

![The standard-epochs testbed with Xray open beside it, and Xray's four parts numbered: 1 the ribbon, 2 the event list, 3 the tab strip, 4 the detail panel](../images/xray/xray-tutorial-shell.png)

## The one-minute model

Every event that finishes produces an **epoch**: the event, app-db before and after, the effects that ran, the subscriptions that recomputed, the views that rendered, and the trace records emitted along the way. An epoch is the unit you debug.

Xray's screen is built around that. The event list (2 in the screenshot) shows recent epochs. Click one, and the tabs (3) show that epoch in the detail panel (4): the Epoch tab explains the whole event step by step, and the other tabs show its state changes, its re-renders, its raw trace, and what it did to machines and routes. The ribbon (1) chooses the frame, the mode, filters and settings.

## Two modes

**Dynamic** mode answers "what just happened?" It has the event list, and its tabs show the event you pick.

**Static** mode answers "what exists in this app?" It drops the event list and lists what is registered: machines, routes, schemas, flows and interceptors.

## When not to use Xray

Xray is a diagnostic reader. It is not a replacement for your editor, your tests, Story, or the browser's performance tools, and it does not ship in production. It gives no pass or fail verdict: [14. Xray, Story and tests](14-story-and-tests.md) shows how it works beside the tools that do.

It does not write your app-db, except when you press **Reset** to restore a past epoch, and it does not wrap or patch your handlers. What it does is answer the questions you ask when a re-frame2 app behaves strangely:

- What event started this?
- What state changed?
- What effects ran?
- Which subscriptions and views reacted?
- Where in the source should I look next?
