# Streaming subscriptions — push-mode trace and epoch buses

True server-pushed events from the running re-frame2 app, delivered as
`notifications/progress` notifications on a long-running MCP `tools/call`.
The MCP tools are `subscribe` and `unsubscribe`; the runtime-side
implementation lives in `re-frame2-pair.runtime/subscribe!` /
`drain-subscription!` / `unsubscribe!`.

## Contents

- [When to use this vs. `watch-epochs`](#when-to-use-this-vs-watch-epochs)
- [Topic vocabulary](#topic-vocabulary)
- [Filter shape per topic](#filter-shape-per-topic)
- [Progress-notification correlation](#progress-notification-correlation)
- [Termination](#termination)
- [Privacy posture](#privacy-posture)
- [Worked invocation](#worked-invocation)
- [Diagnostics — what's currently registered?](#diagnostics--whats-currently-registered)

## When to use this vs. `watch-epochs`

Two transports cover the same buses; pick by interaction shape.

| Want | Reach for |
|---|---|
| Live narration while the user interacts; report events the moment they fire | `subscribe` (push-mode) |
| Finite window summary at the end (e.g. "show me everything in the next 30s") | `watch-epochs` (pull-mode) with `since-id` polling. Legacy fallback: the `scripts/watch-epochs.sh --window-ms` shim. |
| Stream until a fixed number of matches, then summarise | `subscribe` with `max-events` |
| Agent host doesn't surface `notifications/progress` to the model | `watch-epochs` (pull-mode) |

`subscribe` is the **push-mode** path; the long-running tools/call holds open until termination and emits each batch of matching events as a `notifications/progress` tick. `watch-epochs` is the **pull-mode** path; you call it repeatedly with the last `:epoch-id` you've seen and drain matches each time. Op semantics are otherwise identical — both consume the same runtime sub queues.

## Topic vocabulary

Four topics, two underlying buses.

| Topic | Bus | Returns |
|---|---|---|
| `:trace` | raw trace stream | every trace event matching `:filter` |
| `:epoch` | assembled-epoch bus | every `:rf/epoch-record` matching `:filter` |
| `:fx` | raw trace stream | sugar for `:topic :trace :filter {:op-type :fx ...}` |
| `:error` | raw trace stream | sugar for `:topic :trace :filter {:op-type :error ...}` |

The `:fx` and `:error` topics are convenience sugar — they pre-pin the `:op-type` filter so you can layer additional trace-vocab keys on top.

Use `:epoch` whenever you want assembled cascades (with their `:sub-runs` / `:renders` / `:effects` projections); use `:trace` (or its sugar) when you need raw trace-event detail (handler timings, registry traces, sub-cache events, the things the projection drops).

## Filter shape per topic

The filter map is `nil` (no filter) or a topic-specific map. Server-side normalisation happens on the runtime via `compose-trace-filter` (trace family) or `epoch-matches?` (epoch family).

### `:trace` / `:fx` / `:error` — trace-buffer filter vocab

Mirrors `(rf/trace-buffer)` per Spec 009. Recognised keys:

- `:operation` — exact trace operation keyword (e.g. `:event/dispatched`)
- `:op-type` — broad category: `:event` `:sub` `:fx` `:render` `:cofx` `:error` `:registry` `:internal`
- `:frame` — frame id (e.g. `:rf/default`)
- `:severity` — `:debug` `:info` `:warn` `:error`
- `:event-id` — exact event id keyword
- `:handler-id` — exact handler id keyword (e.g. for `:sub/run` traces)
- `:source` — `:tags.source` value
- `:origin` — `:tags.origin` value (`:pair` `:app` `:ui` `:timer` `:http`)
- `:dispatch-id` — exact dispatch-id; combine with `cascade-of` for tree drills
- `:since-ms` / `:between` — time-window keys (see [ops.md](ops.md) `trace/buffer`)

See [ops.md `trace/buffer`](ops.md#trace) for the full vocab.

### `:epoch` — epoch-matches? predicate vocab

Mirrors the `watch-epochs` pull-mode pred map. Recognised keys:

- `:event-id` — exact match against the epoch's `:event-id`
- `:event-id-prefix` — `str/starts-with?` match (so `:cart` matches `:cart/apply-coupon`)
- `:effects` — fx-id appearing in the `:effects` projection
- `:touches-path` — vector path that resolves to a non-nil value in either `:db-before` or `:db-after`
- `:sub-ran` — sub-id or first element of `:query-v` appearing in `:sub-runs`
- `:render` — render-key (stringified) appearing in `:renders`
- `:origin` — `:origin` tag on the trigger event's `:event/dispatched` trace
- `:frame` — frame id
- `:timing-ms` — server-side wall-clock filter on the cascade's elapsed-ms (rf2-r3azh). Number `N` is sugar for `>= N`; strings `">100"`, `"<=50"`, `">=100"`, `"<200"`, `"=42"` set the comparator. Derived from the `:event/run-start` / `:event/run-end` trace pair on `:time` — spans first run-start to last run-end so synchronously-dispatched same-cascade chains roll up. Use this for "alert me on slow events"; the filter rides server-side so non-matching epochs never cross the wire.

## Progress-notification correlation

The MCP client passes a `progressToken` on the `tools/call` for `subscribe`; each batch the server emits as a `notifications/progress` notification carries that same token, so the host can correlate notifications to the originating call. The progress payload looks like:

```json
{
  "progressToken": "<client-supplied>",
  "progress": <tick-number>,
  "message": "<EDN-printed batch of events>",
  "data": {
    "dropped-events":  <count>,
    "dropped-bytes":   <count>,
    "overflow-reason": ":max-buffered-events" | ":max-buffered-bytes" | null
  }
}
```

The `message` slot is an EDN-printed string of the batch (a vector of events for `:trace` topics, a vector of `:rf/epoch-record` maps for `:epoch`). The agent reads `message` directly; capable hosts can additionally inspect `data` for structured counts. `overflow-reason` carries the stringified EDN keyword of the budget that tripped on this tick (`null` when no eviction happened).

When sensitive events are dropped, the payload carries an extra `:dropped-sensitive` count; see [Privacy posture](#privacy-posture) below.

## Termination

A subscription terminates — and the originating `tools/call` resolves with a summary — when any of the following fires (first wins):

| Reason | Trigger |
|---|---|
| `:aborted` | The MCP client cancels the `tools/call` (user interrupt, host shutdown) |
| `:max-events-reached` | `max-events > 0` and the delivered-count reached it |
| `:max-ms-reached` | `max-ms > 0` and that many ms elapsed |
| `:sub-gone` | The runtime-side sub was removed externally (e.g. an `unsubscribe` call with this `sub-id`, or a full-page reload that dropped the runtime) |

`unsubscribe` is **idempotent** — closing an unknown `sub-id` returns `:existed? false` rather than erroring. Safe to call as a cleanup hook even if you don't know whether the subscription is still live.

The final summary the call resolves with:

```edn
{:ok? true
 :sub-id   "<uuid>"
 :topic    :epoch
 :delivered      <count>
 :dropped-events <count>   ;; events evicted from the runtime queue
 :dropped-bytes  <count>   ;; bytes evicted alongside (pr-str char count)
 :ticks          <count>
 :reason         :max-events-reached  ;; or one of the four above
 ;; optional, only when overflow eviction occurred:
 :overflow-reason :max-buffered-events  ;; or :max-buffered-bytes
 ;; optional, only when sensitive drops occurred:
 :dropped-sensitive <count>}
```

The byte+event buffer budget (rf2-ho4ve): the runtime queue is bounded by an OR-combined pair — `max-buffered-events` (default 500) and `max-buffered-bytes` (default 5_000_000, ~5 MB pr-str char count). On overflow the OLDEST queued events are evicted (drop-oldest FIFO) and the count/bytes/reason surface on the next `notifications/progress` tick and the final summary. The byte budget is the load-bearing bound; the event budget is a coarse backstop for chatty-filter overruns. Tune `max-buffered-bytes` when `:overflow-reason :max-buffered-bytes` keeps tripping — that's a large-payload storm.

## Privacy posture

Per Spec 009 §Privacy, framework-published listener integrations MUST default-suppress `:sensitive? true` events before they cross the LLM boundary. The re-frame2-pair streaming forwarder enforces this on both the runtime side (subscription queue dispatch drops sensitive events before they ever enqueue) and the MCP side (the server strips any that slip through).

Opt back in per-call with `include-sensitive?: true`. Dropped count surfaces as `:dropped-sensitive` on each progress payload (when non-zero) and on the final summary.

See [vocabulary.md §Privacy posture](vocabulary.md#privacy-posture--sensitive-and-the-streaming-surface) for the full posture and how to opt in app-wide.

## Worked invocation

Narrate the next 5 cart-prefixed dispatches:

```
mcp__re-frame2-pair__subscribe {
  topic: "epoch",
  filter: {":event-id-prefix": ":cart/"},
  max-events: 5
}
```

The call returns a `sub-id` on first response, emits a `notifications/progress` for each batch, and resolves with `:reason :max-events-reached` after the fifth event. No explicit `unsubscribe` needed in this shape — `max-events` closes the sub.

For an open-ended live narration, omit `max-events` and `max-ms`; close manually with `unsubscribe` when the user moves on:

```
mcp__re-frame2-pair__unsubscribe {sub-id: "<the uuid from the subscribe response>"}
```

## Diagnostics — what's currently registered?

The `subscription-info` MCP tool reports every open subscription without draining its queue:

```
mcp__re-frame2-pair__subscription-info {}
```

Returns `{:ok? true :subs [{:id :topic :filter :queue-depth :queue-bytes :dropped-events :dropped-bytes :overflow-reason :created-at}]}`. Useful when a probe seems to have gone quiet — confirm the sub is still registered (and that `queue-depth` / `queue-bytes` isn't piling up against a dead consumer) before assuming the bus is dry. A non-nil `:overflow-reason` indicates the queue has been evicting older events to stay inside its budget.

Optional filters: pass `topic` (one of `trace` / `epoch` / `fx` / `error`) to narrow to a single topic, or `sub-id` to look up a specific stream — e.g. `mcp__re-frame2-pair__subscription-info {topic: "epoch"}` or `mcp__re-frame2-pair__subscription-info {sub-id: "<uuid>"}`.
