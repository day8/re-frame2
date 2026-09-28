# 9. The app-db Tab

You know which event ran; now you need to know what state changed. This chapter teaches the app-db tab: the frame's state as the focused event left it, with that event's changes marked, its read-only posture, and when to prefer Trace or Epoch instead.

## Changes Marked In Place

The tree is app-db as the focused event left it. Each value the event changed is marked `← was` with its old value, and the maps above every change are expanded for you, so the changes are what you see first.

![The app-db tab after a flow event: :base and :derived are marked with their old values](../images/xray/xray-tutorial-app-db.png)

Your own keys sit in the top **app-db** card. The framework's runtime state follows in cards of its own: one per machine instance under `:rf/machines` and `:rf/spawned`, then `:rf/route`, `:rf/pending-navigation` and `:rf/elision`. A card appears only when that state exists.

Use app-db when you are asking:

- Did the handler write the path I expected?
- Did a runtime-managed slice change?
- Did a rollback preserve the old state?
- Did a route, machine, or flow put data where I think it did?
- Did a large or sensitive value get elided before display?

## Read-Only By Design

Xray does not edit app-db values. That is a feature, not a missing form field.

The app-db tab is evidence. If you want to change state, dispatch an event, use a test frame, restore an epoch deliberately with Reset, or use the pair/MCP surface with the appropriate write permission. Xray keeps the diagnostic panel honest by not becoming an ad hoc state editor.

## Zoom Into A Path

Double-click a map or vector, or press Enter on it, to zoom in. The breadcrumb above the tree leads back up, and Esc zooms out one level. Zooming keeps a large app-db manageable, because you can stay at the meaningful boundary instead of expanding everything.

Good app-db debugging usually looks like this:

1. Open the focused event in Epoch.
2. Move to app-db for the marked changes.
3. Zoom into only the suspicious path.
4. Check downstream subscriptions in Views if the UI still looks wrong.
5. Drop to Trace if you need exact operation order.

## Runtime-Owned State

Machines and routing keep their state in the frame's runtime state, beside your app-db rather than inside it. The cards below the **app-db** card show it. You read that state, but you do not write it from a handler.

That matters in Xray because the tab can show you framework-owned process state without implying that your handlers should write it directly. If a runtime card changed, ask which event or effect asked the runtime to advance the process.

A flow's output is different: it is written into your app-db, at the path the flow declares, so it shows in the **app-db** card like `:derived` above. Resource state is not shown here; the Resources tab covers it.

## Redaction And Elision

Sensitive and large values are rendered through the same classification rules used by the rest of the tooling. A redaction chip or a size marker is not a broken diff. It is the system refusing to spray secrets or huge payloads through the tool surface.

A value declared sensitive shows as a **● redacted** chip, and a very long string is shortened to a size marker. Because both sides of a changed sensitive value read redacted, the tree cannot show that it changed; a chip reading "N redacted paths modified" says so instead.

The path and the surrounding context should still be enough to tell you what kind of value changed and where to look next.
