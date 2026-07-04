# Streaming subscriptions — push-mode trace and epoch buses

True server-pushed events from the running re-frame2 app, delivered as
`notifications/progress` notifications on a long-running MCP `tools/call`.
MCP tools: `subscribe` and `unsubscribe`; runtime-side implementation:
`re-frame2-pair.runtime/subscribe!` / `drain-subscription!` / `unsubscribe!`.

## Contents

- [When to use this vs. `watch-epochs`](#when-to-use-this-vs-watch-epochs)
- [Topic vocabulary](#topic-vocabulary)
- [Filter shape per topic](#filter-shape-per-topic)
- [Progress-notification correlation](#progress-notification-correlation)
- [Termination](#termination)
- [Privacy posture](#privacy-posture)
- [Worked invocation](#worked-invocation)
- [Diagnostics — what streams are currently registered?](#diagnostics--what-streams-are-currently-registered)

## When to use this vs. `watch-epochs`

Two transports cover the same buses; pick by interaction shape.

| Want | Reach for |
|---|---|
| Live narration while the user interacts; report events the moment they fire | `subscribe` (push-mode) |
| Finite window summary at the end (e.g. "show me everything in the next 30s") | `watch-epochs` (pull-mode): poll in a loop, advancing `since-id` to each response's `:head-id`, until your window elapses. |
| Stream until a fixed number of matches, then summarise | `subscribe` with `max-events` |
| Agent host doesn't surface `notifications/progress` to the model | `watch-epochs` (pull-mode) |

`subscribe` is the **push-mode** path; the long-running tools/call holds open until termination, emitting each batch of matching events as a `notifications/progress` tick. `watch-epochs` is the **pull-mode** path; call it repeatedly with the last `:epoch-id` seen, draining matches each time. Otherwise identical — both consume the same runtime sub queues.

## Topic vocabulary

Five topics, two underlying buses.

| Topic | Bus | Returns |
|---|---|---|
| `:trace` | raw trace stream | every trace event matching `:filter` |
| `:epoch` | assembled-epoch bus | every `:rf/epoch-record` matching `:filter` |
| `:fx` | raw trace stream | sugar for `:topic :trace :filter {:op-type :rf.fx ...}` |
| `:error` | raw trace stream | sugar for `:topic :trace :filter {:op-type :error ...}` |
| `:frameless` | raw trace stream | trace events with **no** `:rf.trace/dispatch-id` — registration emits, REPL evals, lifecycle outside any cascade (per Tool-Pair §Frameless trace events) |

The `:fx` and `:error` topics are convenience sugar — they pre-pin the `:op-type` filter so you can layer additional trace-vocab keys on top.

**Event-bundle vs flat delivery.** On `:trace` / `:fx` / `:error` each tick's matched events ship **grouped by `:rf.trace/dispatch-id` into event bundles** (matching the `(re-frame.trace.tooling/trace-buffer frame-id)` shape — `:dispatch-id :frame :event :dispatched :handler :fx :effects :subs :renders :other :trace-events :parent-dispatch-id`); the progress payload's load slot is `:cascades`. `:epoch` and `:frameless` ship flat as `:events`. **Frameless events NEVER ride the event-bundle topics** — opt into the `:frameless` topic explicitly to see registration / REPL / lifecycle events that belong to no run.

Use `:epoch` for assembled cascades (with their `:sub-runs` / `:renders` / `:effects` projections); use `:trace` (or its sugar) for raw trace-event detail (handler timings, registry traces, sub-cache events — the things the projection drops).

## Filter shape per topic

The filter map is `nil` (no filter) or a topic-specific map. Server-side normalisation happens on the runtime via `compose-trace-filter` (trace family) or `epoch-matches?` (epoch family).

### `:trace` / `:fx` / `:error` / `:frameless` — trace-buffer filter vocab

Mirrors `(re-frame.trace.tooling/trace-buffer)` per Spec 009. Recognised keys:

- `:operation` — exact trace operation keyword (e.g. `:rf.event/dispatched`)
- `:op-type` — broad category: `:rf.event` `:rf.sub` `:rf.fx` `:rf.view` `:rf.registry` `:rf.frame` `:rf.machine` `:error` `:warning` `:info`
- `:frame` — frame id (e.g. `:rf/default`)
- `:severity` — alias for `:op-type`, restricted to `:error` `:warning` `:info` (no `:debug`; the spelling is `:warning`, not `:warn`)
- `:event-id` — exact event id keyword
- `:handler-id` — exact handler id keyword (e.g. for `:rf.sub/run` traces)
- `:source` — `:tags.source` value (trigger kind: `:ui` `:frame-init` `:machine-spawn` `:machine-action` `:always` `:after-timer` `:fx-dispatch` `:fx-dispatch-later` `:http` `:router` `:ssr-hydration` `:test` `:tool` `:websocket` `:repl` `:unknown` `:other` — see `spec/Spec-Schemas.md §:rf/dispatch-envelope`)
- `:origin` — `:tags.origin` value (actor: `:app` `:pair` `:story` `:test`)
- `:dispatch-id` — exact dispatch-id; combine with `cascade-of` for tree drills
- `:since-ms` / `:between` — time-window keys (see [ops.md](ops.md) `trace/buffer`)

See [ops.md `trace/buffer`](ops.md#trace) for the full vocab.

### `:epoch` — epoch-matches? predicate vocab

Mirrors the `watch-epochs` pull-mode pred map. Recognised keys:

- `:event-id` — exact match against the epoch's `:event-id`
- `:event-id-prefix` — `str/starts-with?` match (so `:cart` matches `:cart/apply-coupon`)
- `:effects` — fx-id appearing in the `:effects` projection
- `:touches-path` — **non-nil convenience filter**: matches when the path resolves to a `some?` (non-nil) value in either `:db-before` or `:db-after`. This is *not* EP-0012 path-presence: a present-`nil` value (key exists, value is `nil`) and a removal (present-then-absent, `assoc → dissoc`) both read as `nil` on both sides and are therefore **missed**. When you are debugging exactly those identity-significant transitions (nil writes, removals — the ones EP-0012 makes meaningful), don't rely on `:touches-path`; use a presence-aware lookup or read the full epoch diff instead.
- `:sub-ran` — sub-id or first element of `:query-v` appearing in `:sub-runs`
- `:render` — render-key (stringified) appearing in `:renders`
- `:origin` — `:rf.event/origin` tag on the trigger event's `:rf.event/dispatched` trace
- `:frame` — frame id
- `:timing-ms` — server-side wall-clock filter on the cascade's elapsed-ms. Number `N` is sugar for `>= N`; strings `">100"`, `"<=50"`, `">=100"`, `"<200"`, `"=42"` set the comparator. Derived from the `:rf.event/run-start` / `:rf.event/run-end` trace pair on `:time` — spans first run-start to last run-end so synchronously-dispatched same-cascade chains roll up. Use this for "alert me on slow events"; the filter rides server-side so non-matching epochs never cross the wire.

## Progress-notification correlation

The MCP client passes a `progressToken` on the `tools/call` for `subscribe`; each batch the server emits as a `notifications/progress` notification carries that same token, so the host can correlate notifications to the originating call. The progress payload looks like:

```json
{
  "progressToken": "<client-supplied>",
  "progress": <tick-number>,
  "message": "<EDN-printed map — see below>",
  "_meta": {
    "data": {
      "dropped-events":  <count>,
      "dropped-bytes":   <count>,
      "overflow-reason": ":max-buffered-events" | ":max-buffered-bytes" | null
    }
  }
}
```

The structured drop counts live under **`_meta.data`**, not a top-level `data` slot — the MCP SDK strips unknown top-level progress params but preserves `_meta`. `overflow-reason` carries the stringified EDN keyword of the budget that tripped on this tick (`null` when no eviction happened).

The `message` slot is an EDN-printed **map** (not a bare vector). It carries `:sub-id` plus the delivered batch under exactly one topic-dependent slot:

- `:cascades` — on the event-bundle topics (`:trace` / `:fx` / `:error`): a vector of event bundles, each matching the `(rf/trace-buffer frame-id)` shape (`:dispatch-id :frame :event :dispatched :handler :fx :effects :subs :renders :other :trace-events :parent-dispatch-id`).
- `:events` — on the flat topics (`:epoch` / `:frameless`): a flat vector (`:rf/epoch-record` maps for `:epoch`; raw trace events for `:frameless`).

So a `:epoch` tick's `message` reads as `{:sub-id "<uuid>" :events [<epoch-record> ...] :dedup <bool> :dropped-events <n> :dropped-bytes <n>}`, and a `:trace`/`:fx`/`:error` tick reads as `{:sub-id "<uuid>" :cascades [<bundle> ...] :dedup <bool> ...}`. The `:dedup` flag signals whether the slot was structurally deduped (reconstruct via `(de-dupe.core/expand cache-map)`); `:overflow-reason` rides the map too when a budget tripped. The agent reads `message` directly; capable hosts can additionally inspect `_meta.data` for the structured counts.

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

Byte+event buffer budget: the runtime queue is bounded by an OR-combined pair — `max-buffered-events` (default 500) and `max-buffered-bytes` (default 5_000_000, ~5 MB pr-str char count). On overflow the OLDEST queued events are evicted (drop-oldest FIFO); count/bytes/reason surface on the next `notifications/progress` tick and the final summary. The byte budget is the load-bearing bound; the event budget is a coarse backstop for chatty-filter overruns. Tune `max-buffered-bytes` when `:overflow-reason :max-buffered-bytes` keeps tripping — a large-payload storm.

## Privacy posture

Per Spec 009 §Privacy, framework-published listener integrations MUST default-suppress `:sensitive? true` events before they cross the LLM boundary. The re-frame2-pair streaming forwarder enforces this on both the runtime side (subscription queue dispatch drops sensitive events before they ever enqueue) and the MCP side (the server strips any that slip through).

Opt back in per-call with the `subscribe` wire arg `include-sensitive: true` (no `?` — the wire arg drops the `?` the runtime `configure-privacy!` opt and walker option keep), honoured only when the server was launched with `--allow-sensitive-reads`. Dropped count surfaces as `:dropped-sensitive` on each progress payload (when non-zero) and on the final summary.

See [vocabulary.md §Privacy posture](vocabulary.md#privacy-posture--sensitive-and-the-raw-eval-carve-out) for the full posture and how to opt in app-wide.

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

## Diagnostics — what streams are currently registered?

The `list-streams` MCP tool reports every open streaming-tap subscription without draining its queue:

```
mcp__re-frame2-pair__list-streams {}
```

Returns `{:ok? true :subs [{:id :topic :filter :queue-depth :queue-bytes :dropped-events :dropped-bytes :overflow-reason :created-at}]}`. Useful when a probe seems to have gone quiet — confirm the sub is still registered (and that `queue-depth` / `queue-bytes` isn't piling up against a dead consumer) before assuming the bus is dry. A non-nil `:overflow-reason` indicates the queue has been evicting older events to stay inside its budget.

Optional filters: pass `topic` (one of `trace` / `epoch` / `fx` / `error` / `frameless`) to narrow to a single topic, or `sub-id` to look up a specific stream — e.g. `mcp__re-frame2-pair__list-streams {topic: "epoch"}` or `mcp__re-frame2-pair__list-streams {sub-id: "<uuid>"}`.

> **Note:** `list-streams` is the streaming-tap diagnostic. It is distinct from `list-subscriptions`, which reports the **live reactive sub-cache** for a frame (the answer to "what reactive subscriptions are active?", matching `snapshot :sub-cache`). The two answer different questions.

### `get-stream-controls` — "why was my stream denied / quiet / terminated?"

`list-streams` reads the **runtime** streaming-tap registry (what trace/epoch/fx streams are open in the browser). `get-stream-controls` reads the **other side** — the MCP **server's** resource controller: effective caps, active stream slots vs limit, token-bucket pressure, abuse-window count vs threshold. Reach for it when a `subscribe` is **refused**, has gone **silent**, or **terminated** unexpectedly and you need to know whether a server-side cap (not the runtime) is the cause.

```
mcp__re-frame2-pair__get-stream-controls {}
```

It reads the server's resource-control atoms **in-process — no nREPL round-trip** — so it answers **even when the runtime is down** (exactly when you're diagnosing a stalled stream and `list-streams` can't reach the browser). It carries **no event payloads or app-db data** (control state only), so it is unconditionally safe — **no `--allow-sensitive-reads` gate**.

Returns `{:ok? true :config {<the four caps>} :concurrent-streams {:active :limit :at-capacity?} :rate-limit {:capacity :tokens :initialized? :throttling?} :abuse-window {:count :threshold :window-ms :tripped?} :cross-check <hint>}`. Read it against `list-streams`:

- `:concurrent-streams :at-capacity? true` → a new `subscribe` is refused because every slot is taken — `unsubscribe` an idle stream (or raise `--max-concurrent-streams`) first.
- `:rate-limit :throttling? true` → the token bucket is empty (a chatty filter is firing faster than `--max-events-per-sec`); narrow the `filter` / `pred`.
- `:abuse-window :tripped? true` → the abuse threshold tripped; the server is shedding load.
- **Cross-check `:concurrent-streams :active` against the `list-streams` row count.** A server `:active` count with **no** matching `list-streams` row signals a **leaked server slot**; the reverse (a runtime row with no server slot) signals a **stale runtime subscription**.

The server-side cap knobs (`--max-concurrent-streams`, `--max-events-per-sec`, `--abuse-overflow-threshold`, …) live in [`mcp-transport.md`](mcp-transport.md); `get-stream-controls` is how you read their *effective* values mid-session.
