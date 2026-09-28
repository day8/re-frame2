# 4. Trace stream

Epoch has told you the story of an event, but you need the exact records underneath it. The Trace tab lists the focused epoch's runtime records in the order they were emitted, with nothing summarised.

![The Trace tab for :standard-epochs/increment-fx, one row per record from the dispatch through the handler and its effects](../images/xray/xray-tutorial-trace.png)

## When to open Trace

Start with Epoch, which groups the event into steps. Open Trace when you need:

- the exact order of runtime operations, for example whether something happened before or after the handler;
- the original operation keyword;
- a precise payload, or whether a value was elided or redacted;
- a duration;
- the source coordinate that emitted a record;
- a record that Epoch or another tab compressed into a summary.

## What Trace shows

Trace shows the focused epoch. If `:standard-epochs/increment-fx` is focused, Trace shows the records from that event. Pick another event and Trace follows.

Each row has six columns:

- **Δt**: milliseconds since the epoch's first record.
- **Stage**: the Epoch tab's step names, such as DISPATCH, EVENT HANDLER, FLOW, EFFECT HANDLERS, SUBSCRIPTIONS and VIEWS.
- **Area**: a badge naming the runtime area: EVENT, COEFFECT, DB, FX, FLOW, SUB, VIEW, MACHINE, ROUTING, RESOURCE, EPOCH, ERROR or WARNING.
- **What**: the operation that happened.
- **Target**: the event id, sub id, view id, path, fx id, machine id, or route id.
- **Duration**: present where the substrate has timing.

The left edge of each row takes its Epoch step's colour, so you can see where each step starts and ends. An error or warning row takes its severity colour instead.

Click a row to open the raw trace record beneath it, and click again to close it. A row that changed app-db lists the changed paths under it. A **↗** after the target opens the code that emitted the record in your editor.

## Cancellation cascades

When a machine destroys a child actor, the teardown aborts whatever the child had in flight, and those aborts are spread across the list. The row recording the destroy carries a **⟲** button; click it, or right-click the row, to open the cancellation cascade in one view: the decision that triggered the destroy, the teardown, and each request or timer it aborted, in order. Click an entry to jump to its trace row, and press Esc to close the view.

## Reading the trace from your own code

Xray reads the same trace stream your code can listen to. To log or collect trace records yourself, for example in a test, register a listener; [Write a listener](../core/observability.md#write-a-listener) in the observability guide shows how.
