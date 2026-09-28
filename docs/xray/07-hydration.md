# 7. SSR hydration

Your page was rendered on the server and now hydrates in the browser. Xray shows the hydration event itself. A hydration mismatch is reported elsewhere, and this chapter says where. [When the renders disagree](../ssr/concepts.md#when-the-renders-disagree) in the SSR guide explains how a mismatch is detected and what the runtime does about it.

## The hydrate epoch

Hydration is an ordinary event. The client dispatches `:rf/hydrate`, which installs the server's state into app-db, and Xray lists it in the event list like any other event. Its Epoch tab shows the state landing in app-db, the effects it ran, and the subscriptions that computed their first values. The app-db tab shows the state the client's first render reads.

![The Epoch tab for an :rf/hydrate event, showing the state it installs, its effects and the subscriptions it computes](../images/xray/xray-tutorial-hydration.png)

## Where a mismatch is reported

A hydration mismatch is not an event, and it belongs to no epoch. The client checks for it after its first render, outside any event, so Xray's event list and Trace tab never show it. It goes to:

- trace listeners, as the trace `:rf.ssr/hydration-mismatch`, carrying the server's and the client's render hashes, the frame, the `:failing-id` and the recovery the runtime took. [Write a listener](../core/observability.md#write-a-listener) in the observability guide shows how to register one.
- the frame's `:observability :errors` sinks, as an error record under the same id. That record is sent in every build. In a development build, a record that no sink handles is printed to the console. [Declare a sink](../core/observability.md#consuming-production-telemetry-declare-a-sink) in the observability guide shows how.

The hashes tell you that the renders diverged, not which node. By default the client's render replaces the server's markup and the page keeps working. Set `:ssr {:on-mismatch :hard-error}` on the frame to make the check throw instead, which suits CI.

The testbed in the screenshot registers a trace listener that dispatches the mismatch report into app-db. That is why `:ssr-hydration-mismatch.core/record-mismatch` follows `:rf/hydrate` in its event list, and why the report shows up in Xray at all.

## What to look for

The server and the client rendered different HTML, so either they had different state or a view did not render the same state the same way. The usual causes:

- A view used `js/Date`, random, or browser state during render.
- Server and client seeded different app-db values.
- The route matched differently on the client.
- A subscription returned `nil` on the server and real data on the client.
- A classified value was redacted or elided before one side expected it.

Xray shows the client's side. Read the `:rf/hydrate` epoch for the state the client started from, and compare it with the state the server rendered. Fix the source of the difference; do not patch the rendered HTML afterwards.
