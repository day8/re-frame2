# 4. Trace Stream

You read the Epoch tab and still need the raw facts. This chapter explains the Trace tab: an epoch-scoped flat list of runtime records, useful for ordering, exact operation names, payloads, durations, source jumps, and cases where the friendly panels summarize too much.

## Epoch First, Trace Second

Start with Epoch because it is written for humans. It groups the cascade into meaningful phases.

Drop to Trace when you need:

- exact ordering of runtime operations;
- the original operation keyword;
- a precise payload;
- a duration row;
- source coordinates;
- a row that a friendlier panel compressed into a summary.

The Trace tab is not a punishment box. It is the same story at a lower level.

![The Trace tab for :standard-epochs/increment-fx, one row per record from the dispatch through the handler and its effects](../images/xray/xray-tutorial-trace.png)

## What Trace Shows

Trace is scoped to the focused epoch. If the spine is focused on `:standard-epochs/increment-fx`, Trace shows records from that cascade. Pick another event row and Trace rebinds.

Each row is shaped for scanning, in six columns:

- **Δt**: milliseconds since the epoch's first record.
- **Stage**: the Epoch tab's step names, such as DISPATCH, EVENT HANDLER, FLOW, EFFECT HANDLERS, SUBSCRIPTIONS and VIEWS.
- **Area**: a badge naming the runtime area: EVENT, COEFFECT, DB, FX, FLOW, SUB, VIEW, MACHINE, ROUTING, RESOURCE, EPOCH, ERROR or WARNING.
- **What**: the operation that happened.
- **Target**: the event id, sub id, view id, path, fx id, machine id, or route id.
- **Duration**: present where the substrate has timing.

The left edge colour follows the Epoch stage, so the raw list still has a story line. An error or warning row takes its severity colour instead.

Click a row to open the raw trace record beneath it, and click again to close it. A row that changed app-db lists the changed paths under it. A **↗** after the target opens the code that emitted the record in your editor.

When a machine destroys a child actor, the teardown aborts whatever the child had in flight, and those aborts scatter across the list. The row recording the destroy carries a **⟲** button; click it, or right-click the row, to open the cancellation cascade in one view: the decision that triggered the destroy, the teardown, and each request or timer it aborted, in order. Click an entry to jump to its trace row, and press Esc to close the view.

## A Tiny Listener

Xray is the complete UI over the trace bus, but the bus is public substrate. A small tool can listen too:

```clojure
(require '[re-frame.core :as rf])

(defn install-mini-trace! []
  (rf/register-listener! :trace
    :my-tool/trace-printer
    (fn [trace]
      (js/console.log
        (pr-str {:operation (:operation trace)
                 :frame     (get-in trace [:tags :frame])})))))
```

The first argument names the stream: `:trace` for raw trace records, or `:epoch` for assembled epochs. [Write a listener](../core/observability.md#write-a-listener) in the observability guide covers both. That is the same architectural move Xray makes, just with a smaller presentation. Xray does more because it also reads epoch history, source coordinates, registries, frames, and panel-specific projections.

## Why The Trace Tab Stays Flat

Trace is not another nested dashboard. The nested explanation lives in Epoch. The flat feed is valuable precisely because it stays close to the wire.

When something looks suspicious in a friendly panel, Trace lets you answer:

- Did that operation really emit?
- Did it happen before or after the handler?
- Was the value elided or redacted?
- Which source coordinate emitted it?
- How many related records are in this epoch?

That is enough. If you need a higher-level explanation, go back to the lens that owns that domain.
