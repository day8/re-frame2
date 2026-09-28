# 7. SSR Hydration

You server-rendered a page and now it hydrates in the browser. This chapter shows what Xray shows of hydration, and where a hydration mismatch is reported instead. [When the renders disagree](../ssr/concepts.md#when-the-renders-disagree) in the SSR guide explains how a mismatch is detected and what the runtime does about it.

## The Hydrate Epoch

Hydration is an ordinary event. The client dispatches `:rf/hydrate`, which installs the server's state into app-db, and Xray lists it in the event list like any other event. Its Epoch tab shows the state landing in app-db, the effects it ran, and the subscriptions that computed their first values. The app-db tab shows the state the client's first render reads.

![The Epoch tab for an :rf/hydrate event, showing the state it installs, its effects and the subscriptions it computes](../images/xray/xray-tutorial-hydration.png)

## Where A Mismatch Is Reported

A hydration mismatch is not an event, and it belongs to no epoch. The client checks for it after its first render, outside any event, so Xray's event list and Trace tab never show it. It goes to:

- trace listeners, as the trace `:rf.ssr/hydration-mismatch`, carrying the server's and the client's render hashes, the frame, the `:failing-id` and the recovery the runtime took. [4. Trace stream](04-trace-stream.md) shows how to register a listener.
- the frame's `:observability :errors` sinks, as an error record under the same id. That record is sent in every build. In a development build, a record that no sink handles is printed to the console. [Declare a sink](../core/observability.md#consuming-production-telemetry-declare-a-sink) in the observability guide shows how.

The hashes tell you that the renders diverged, not which node. By default the client's render replaces the server's markup and the page keeps working. Set `:ssr {:on-mismatch :hard-error}` on the frame to make the check throw instead, which suits CI.

The testbed in the screenshot registers a trace listener that dispatches the mismatch report into app-db. That is why `:ssr-hydration-mismatch.core/record-mismatch` follows `:rf/hydrate` in its event list, and why the report shows up in Xray at all.

## What To Look For

The common causes are boring, which is good news:

- A view used `js/Date`, random, or browser state during render.
- Server and client seeded different app-db values.
- The route matched differently on the client.
- A subscription returned `nil` on the server and real data on the client.
- A classified value was redacted or elided before one side expected it.

Fix the source of nondeterminism. Do not patch the rendered HTML after the fact.

## Xray And The SSR Rule

The SSR rule is the same rule the guide teaches for client views: render is a function of state. If the server and client have the same state and the view is deterministic, the renders agree. If not, React is left trying to reconcile two different stories.

Xray shows the client's side of that comparison. Read the `:rf/hydrate` epoch for the state the client started from, and compare it with the state the server rendered.
