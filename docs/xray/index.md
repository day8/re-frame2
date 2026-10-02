# Xray

Use Xray when you can reproduce a problem in a running re-frame2 app and need
to see which event, state change, subscription or view caused it. This guide
teaches you to investigate an interaction and turn what you find into a test.

[![Xray beside the example app: 1 chooses the frame and controls, 2 selects an event, 3 selects a tab, and 4 explains the selected event.](../images/xray/xray-tutorial-shell.png)](../images/xray/xray-tutorial-shell.png)

A completed event normally has an **epoch**: a record of its state before and
after, its effects, and the subscriptions and views that reacted. Select an
event in the event list (2), then read **Epoch** in the detail panel (4).
Open **app-db** for the changed values or **Views** for the rendering work.

Selecting an older event changes the evidence you see. Your app keeps running.
Some panels, including Resources and Graph, show the running app's current
structure; they do not rewind with the selection.

Xray's **Dynamic** mode investigates activity. **Static** mode browses registered
machines, routes, schemas, flows and interceptors without selecting an event.
Neither mode gives a test verdict. [Story runs a reproducible scenario and
checks its assertions](14-story-and-tests.md); Xray explains the runtime behind
that result.

## When to use another tool

For a repeatable component state or interaction, define a Story variant. For
an automated regression, keep the assertion in a test. Use React DevTools or
the browser's performance tools to investigate React commits and browser
paint; Xray cannot measure those from an event record.

Xray belongs in a development build. Its inspection panels read your app;
**Reset** is the explicit action that restores a retained frame snapshot.
