# 9. The app-db tab

You know which event ran; now you need to know what state it changed. The app-db tab shows the frame's state as the focused event left it, with that event's changes marked.

## Changes marked in place

The tree is app-db as the focused event left it. Each value the event changed is marked `← was` with its old value (2 in the screenshot), and the maps above every change are expanded for you, so the changes are what you see first.

![The app-db tab after a flow event, numbered: 1 the app-db card, 2 :base and :derived marked with their old values](../images/xray/xray-tutorial-app-db.png)

Use app-db when you are asking:

- Did the handler write the path I expected?
- Did a runtime-managed slice change?
- Did a rollback preserve the old state?
- Did a route, machine, or flow put data where I think it did?
- Did a large or sensitive value get elided before display?

## Your state and the runtime's state

Your own keys sit in the top **app-db** card (1). A flow's output is written into your app-db, at the path the flow declares, so it shows there too, like `:derived` above.

Machines and routing keep their state beside your app-db rather than inside it, in the frame's runtime state. It follows in cards of its own: one per machine instance under `:rf/machines` and `:rf/spawned`, then `:rf/route`, `:rf/pending-navigation` and `:rf/elision`. A card appears only when that state exists. Your handlers read this state but never write it directly, so when a runtime card changed, look for the event or effect that asked the runtime to advance the machine or the route.

Resource state is not shown here; [13. Resources](13-resources.md) covers it.

## Zoom into a path

Double-click a map or vector, or press Enter on it, to zoom in. The breadcrumb above the tree leads back up, and Esc zooms out one level. Zooming keeps a large app-db manageable, because you can stay at the part that matters instead of expanding everything.

A typical pass:

1. Read the focused event in Epoch.
2. Open app-db for the marked changes.
3. Zoom into the suspicious path.
4. If the UI still looks wrong, check the subscriptions that read it in Views.

## Read-only

The app-db tab does not edit values. To change state, dispatch an event, use a test frame, restore an epoch with **Reset** ([Restoring a past epoch](03-time-travel.md#restoring-a-past-epoch)), or use the pair/MCP surface with the appropriate write permission.

## Redaction and elision

Sensitive and large values are rendered under the same classification rules as the rest of the tooling. A value declared sensitive shows as a **● redacted** chip, and a very long string is shortened to a size marker. Neither is a broken diff.

Because both sides of a changed sensitive value read redacted, the tree cannot show that it changed; a chip reading "N redacted paths modified" says so instead. The path and the surrounding values are usually enough to tell you what kind of value changed and where to look next.
