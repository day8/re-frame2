# Follow a request and its reply

A request was issued, but the expected state never appeared. Inspect the
managed-effect card, then follow the delivered reply into its own event.
The request event and reply event have separate epochs.

## Read the managed-effect card

Open the issuing event's **Epoch** or **Trace** evidence and its managed-effect
details. HTTP, WebSocket, spawned-machine, server and managed-flow work use
the same card shape where their instrumentation provides it.

Read the sections according to the question:

| Section | What to check |
| --- | --- |
| **Request** | Actual effect arguments: URL, method, params or work identity |
| **Wire timing** | Retained lifecycle timing; blank fields do not mean zero |
| **Response** | Retained completion or failure payload |
| **Reply target** | The event the caller configured to receive a result |
| **App-db slice touched**, when present | Writes belonging to this event, rather than a later reply |

For HTTP, the issuing epoch cannot by itself explain what the reply wrote.
Click **→ reply ↗** when present, then read that event's Epoch and app-db.
This is the step that distinguishes “the request succeeded” from “the reply
handler installed the expected state”.

The **OVERRIDDEN** chip means an effect override replaced or redirected the
handler. In a Story variant that stubs HTTP, this can be exactly what the
scenario intended. Compare the declared stub with the captured request before
assuming the real network ran.

## Reduce the exchange to its relevant events

Right-click the **correlation** chip to filter to the exchange's issuing and
reply events, or right-click the effect id to find events that triggered that
effect. The filter retains spawning ancestors so the original interaction is
still available.

A correlation filter does not include every outside-event completion row.
Enable the ungrouped row and inspect Trace when you need transport activity
that occurred outside a handler.

## Troubleshooting

| Status or symptom | Meaning | Action |
| --- | --- | --- |
| **ISSUED · no completion in this capture** | No matching terminal trace is retained | Check active work and repeat the request; it may be pending or its completion may have been evicted |
| **ERROR** | A failure was retained | Expand Response; distinguish a transport error, HTTP failure and decoding failure |
| **CANCELLED** | Work was cancelled | Read the cause; actor destruction or an explicit abort may be expected |
| **STALE** | The result could no longer deliver | Check whether a newer request superseded it or its owner was destroyed |
| No **→ reply ↗** | No delivered reply bundle is retained | Check the terminal status and capture window before blaming the reply handler |
| Reply delivered, state still wrong | The reply's event ran with an unexpected input or write | Follow the reply and inspect its Epoch and app-db |

The [HTTP outcome reference](api/diagnostics.md#managed-http-outcomes)
enumerates the displayed statuses. For implementation changes such as a
timeout or retry policy, use the [HTTP guide](../async/http.md).
