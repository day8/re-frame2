# 003-Tool-Catalogue

The nine MCP tools.

## Universal: wire-boundary token cap

Every `tools/call` response passes through the wire-boundary cap
enforced in `tools.cljs` (see
[`Principles.md` §Tight token budget](Principles.md#tight-token-budget-per-response)).
Each tool accepts a universal `max-tokens` arg — integer cap in
tokens, default `5000`, `0` disables. Over-budget payloads are
replaced with a structured marker:

```clojure
{:rf.mcp/overflow
 {:limit       :reached
  :token-count <integer>
  :cap-tokens  <integer>
  :tool        "<tool-name>"
  :hint        "<tool-specific next-step hint>"}}
```

The marker is the only over-budget response shape — silent truncation
is not allowed. Agents pattern-match on `:rf.mcp/overflow` and either
narrow their args or pass `max-tokens 0` for the rare case where the
full payload is genuinely needed.

## discover-app

Verify the shadow-cljs nREPL is reachable, confirm the
`re-frame-pair2.runtime` namespace was loaded by the consumer's
shadow-cljs `:devtools :preloads`, and return a health summary. Run
first every session.

**Args**: `build` (string, optional, default `"app"`).

**Returns**: an `:ok? true` map with `:debug-enabled?`, `:frames`,
`:coord-annotation-enabled?`, `:build-id`. Or `:ok? false` with a
`:reason` keyword if a precondition fails. The most common
precondition failure on a fresh app is
`:reason :runtime-not-preloaded` — the runtime ships into the app
via shadow-cljs `:preloads`; the server probes
`js/globalThis.__re_frame_pair2_runtime` (the load-time mirror the
preload installs) and refuses with a setup hint when missing. There
is no fallback inject path; see the skill's SKILL.md §Setup for the
two-line preload entry.

## eval-cljs

Evaluate a CLJS form in the connected browser runtime via
`shadow.cljs.devtools.api/cljs-eval`. Returns the EDN value.

**Args**: `form` (string, required), `build` (string, optional).

**Returns**: `{:ok? true :value <edn-value>}` on success;
`{:ok? false :reason :eval-error :message "..."}` on failure.
`:reason :runtime-not-preloaded` if the runtime preload hasn't run.

## dispatch

Fire a re-frame2 event tagged with `:origin :pair`. Three modes:

| `sync`? | `trace`? | Mode |
|---------|----------|------|
| false   | false    | queued (`rf/dispatch`) |
| true    | false    | sync (`rf/dispatch-sync`) |
| any     | true     | trace (synchronous, returns the assembled `:rf/epoch-record`) |

**Args**: `event` (string, required — EDN-encoded event vector),
`sync` (bool), `trace` (bool), `frame` (string, e.g. `":foo"`),
`fx-overrides` (object, e.g. `{:http :stub-http}`), `build` (string).

**Returns**: the runtime's response, merged with `:mode`.

## trace-window

Return `:rf/epoch-record`s that landed in the last N ms for the
operating frame.

**Args**: `ms` (integer, default 1000), `frame` (string),
`build` (string).

**Returns**: `{:ok? true :window-ms N :count K :epochs [...]}`.

## watch-epochs

Pull-mode poll for matching epochs added after a given epoch-id.
This is the MCP equivalent of the bash `watch-epochs.sh` script's
poll loop — but MCP isn't streaming, so callers that want a tight
loop should call us repeatedly with the same `since-id`.

**Args**: `since-id` (string, optional — omit to start fresh),
`pred` (object, optional predicate filter, keys from:
`:event-id`, `:event-id-prefix`, `:effects`, `:touches-path`,
`:sub-ran`, `:render`, `:origin`, `:frame`), `frame`, `build`.

**Returns**: `{:ok? true :matches [...] :head-id "..." :id-aged-out? bool}`.

## tail-build

Wait for a hot-reload to land by polling a probe form until its
value changes from its pre-call value. Times out after `wait-ms`.

**Args**: `probe` (string — a CLJS form whose value should change
after the reload), `wait-ms` (integer, default 5000), `build` (string).

**Returns**: `{:ok? true :t <ms> :soft? false}` on a real change, or
`{:ok? false :reason :timed-out}` on timeout. If `probe` is omitted,
falls back to a 300ms soft delay (matches the bash version).

## snapshot

Coarse-grained per-frame state read in **one round-trip**. The mega-op
for investigate-X workflows that would otherwise chain 5-10 individual
reads. Server-side composition over the existing per-slice runtime
readers (`get-frame-db`, `sub-cache`, `machines` + frame-local
`[:rf/machines]`, `epoch-history`, `trace-buffer`); no parallel
implementation.

**Args**: `frames` (string `"all"` or array of frame-id strings like
`":rf/default"`, default `"all"`), `include` (array of slice names —
subset of `["app-db" "sub-cache" "machines" "epochs" "traces"]`,
default all five), `build` (string).

**Returns**:

```clojure
{:ok? true
 :frames :all|[<frame-id>...]
 :include [:app-db :sub-cache :machines :epochs :traces]
 :snapshot {<frame-id> {:app-db    {...}
                        :sub-cache {<query-v> {:value v :ref-count n}}
                        :machines  {:ids [<machine-id>...]
                                    :state {<machine-id> <snapshot>}}
                        :epochs    [<:rf/epoch-record> ...]
                        :traces    [<trace-event> ...]}
            ...}}
```

The `:machines` slice combines the global registrar's machine-id list
(`rf/machines`) with the per-frame state stash at `[:rf/machines]` in
the frame's `app-db` (per Spec 005). The `:traces` slice filters the
retain-N trace ring buffer by `:frame`. Other slices delegate
verbatim to the public per-slice surface.

Pass a smaller `include` to subset (e.g.
`{:frames "all" :include ["app-db" "epochs"]}` for a quick
"state + recent history" probe). Per-op fine-grain reads (`eval-cljs`
against `runtime/app-db-at`, `runtime/sub-cache`, etc.) stay
available — they're the right surface when you genuinely need one
slice for one frame. `snapshot` is the right surface when you don't
know yet which slice carries the answer.

`:reason :runtime-not-preloaded` if the preload hasn't run;
`:reason :snapshot-failed` (with `:message`) on any other failure.

## subscribe

Streaming subscription on the trace or epoch bus (rf2-hq49). Push-mode
replacement for the polling-shaped `watch-epochs` op. The MCP
`tools/call` request stays open for the lifetime of the subscription;
each batch of matching events is emitted as a
`notifications/progress` notification correlated to the original call
via `extra._meta.progressToken`. The final `tools/call` result is a
summary `{:ok? true :sub-id :delivered N :overflow N :ticks K :reason
<terminated-reason>}`.

### Topics

| Topic    | What gets pushed                                                  |
|----------|-------------------------------------------------------------------|
| `trace`  | Every raw trace event matching `filter`.                          |
| `epoch`  | Every assembled `:rf/epoch-record` matching `filter`.             |
| `fx`     | Sugar — `topic :trace` with base filter `{:op-type :fx}`.         |
| `error`  | Sugar — `topic :trace` with base filter `{:op-type :error}`.      |

User-supplied filter keys win over the topic's base filter on conflict
— the topic is a default, not a lock. So `subscribe {:topic :fx
:filter {:op-type :info}}` actually streams `:info` traces (the user
filter wins). Don't do this — but the substrate doesn't refuse it.

### Filter vocabulary

For `topic` of `:trace`, `:fx`, or `:error`, the filter map mirrors the
`(re-frame.core/trace-buffer opts)` filter vocabulary (rf2-97ah0).
Recognised keys (all AND-compose; absent key means "no constraint on
that axis"):

| Key              | Match against (`ev` is the event)                                 |
|------------------|-------------------------------------------------------------------|
| `:operation`     | `(= operation (:operation ev))`                                   |
| `:op-type`       | `(= op-type (:op-type ev))`                                       |
| `:severity`      | Alias for `:op-type`, restricted to `:error` / `:warning` / `:info`. |
| `:frame`         | `(:frame ev)` or `(get-in ev [:tags :frame])`                     |
| `:event-id`      | `(get-in ev [:tags :event-id])`                                   |
| `:handler-id`    | `(get-in ev [:tags :handler-id])`                                 |
| `:source`        | `(:source ev)` or `(get-in ev [:tags :source])` — one of `:ui` / `:timer` / `:http` / `:repl` / `:machine` / `:ssr-hydration`. |
| `:origin`        | `(get-in ev [:tags :origin])` — `:app` / `:pair` / `:story` / `:test`. |
| `:dispatch-id`   | `(get-in ev [:tags :dispatch-id])`                                |
| `:since-ms`      | `(> (:time ev) since-ms)` — strict-greater-than host-clock ms.    |
| `:between`       | `[t0 t1]` — `(<= t0 (:time ev) t1)` host-clock ms.                |
| `:sensitive?`    | `(:sensitive? ev)` — boolean. **Default forwarder posture:** events with `:sensitive? true` are dropped at the MCP boundary before any data reaches the agent surface (per [spec/009 §Privacy / sensitive data](../../../spec/009-Instrumentation.md#privacy--sensitive-data-in-traces)). The runtime stamps the flag on every trace event emitted inside a `:sensitive? true` registration's handler scope. Opt back in per-call with `include-sensitive? true` (an MCP tool arg on `trace-window`, `watch-epochs`, `snapshot`, `subscribe`). Dropped count surfaces as `:dropped-sensitive` on the result / progress payload when non-zero. |

For `topic :epoch`, the filter map mirrors `epoch-matches?` (same
vocab `watch-epochs` already accepts):

| Key                  | Match against (`e` is the `:rf/epoch-record`)                 |
|----------------------|---------------------------------------------------------------|
| `:event-id`          | `(:event-id e)`                                               |
| `:event-id-prefix`   | `(str/starts-with? (str event-id) (str prefix))`              |
| `:effects`           | `(some #(= effects (:fx-id %)) (:effects e))`                 |
| `:touches-path`      | `(:db-before e)` or `(:db-after e)` carries something at path |
| `:sub-ran`           | `(some #(or (= sub-ran (:sub-id %)) (= sub-ran (first (:query-v %)))) (:sub-runs e))` |
| `:render`            | `(some #(= render (str (:render-key %))) (:renders e))`       |
| `:origin`            | One of the `:event/dispatched` traces has `(:tags :origin)` = `origin`. |
| `:frame`             | `(= frame (:frame e))`                                        |

### Args

- `topic` (string, **required**) — one of `"trace"`, `"epoch"`, `"fx"`,
  `"error"`.
- `filter` (object **or** string, optional) — filter map. Accepted as
  a JSON object or an EDN-encoded string. EDN is preferred when the
  filter carries keywords or namespaced ids (a JSON object can't
  carry `:cart/add` natively).
- `max-buffered` (integer, default `500`) — runtime-side queue cap.
  When full, new events are dropped and the count is reported in
  `:overflow`. The dropped events are the *newest* — keeping the
  oldest lets you reconstruct the start of a storm.
- `poll-ms` (integer, default `100`) — server-side poll cadence. The
  MCP server polls the runtime's drain at this interval and emits a
  progress notification per non-empty batch.
- `max-ms` (integer, default `0` = unbounded) — hard upper-bound on
  how long the subscription stays open. `0` = stay open until the
  client cancels.
- `max-events` (integer, default `0` = unbounded) — terminate after
  this many events have been delivered.
- `include-sensitive?` (boolean, default `false`) — opt back in to
  forwarding events carrying `:sensitive? true`. Per [spec/009
  §Privacy](../../../spec/009-Instrumentation.md#privacy--sensitive-data-in-traces)
  the forwarder default-drops these events at the MCP boundary; pass
  `true` to disable the gate for this subscription. Dropped count
  surfaces as `:dropped-sensitive` on each progress payload (when
  non-zero) and the final summary.
- `build` (string, default `"app"`) — shadow-cljs build id.

### Returns

While the subscription is open, each non-empty batch tick emits

```jsonc
{
  "method": "notifications/progress",
  "params": {
    "progressToken": "<token>",  // echoed from the call's _meta
    "progress": <tick-number>,   // monotonic, 1-based
    "message": "{:sub-id \"...\" :events [...] :overflow 0}",
    "data": { "overflow": 0 }
  }
}
```

`message` is an EDN-printed string carrying the event batch — the
same shape the runtime's `drain-subscription!` returns. Capable MCP
clients can also inspect `data.overflow` for the count of dropped
events on this tick.

On termination, the `tools/call` result is

```clojure
{:ok? true
 :sub-id <uuid>
 :topic  <keyword>
 :delivered <integer>
 :overflow  <integer>
 :ticks     <integer>
 :reason    :aborted | :sub-gone | :max-ms-reached | :max-events-reached}
```

`:reason` is `:aborted` when the client cancelled the call,
`:sub-gone` when the runtime's subscription disappeared (typically a
full page reload, or an `unsubscribe` op fired separately),
`:max-ms-reached` / `:max-events-reached` when the caller's
upper-bounds fire.

### Termination paths

1. **Client cancel** — the MCP client cancels the `tools/call`. The
   server's `extra.signal` AbortSignal fires; the poll loop notices
   on its next tick, evaluates `unsubscribe!` against the runtime,
   and resolves with `:reason :aborted`.
2. **Out-of-band `unsubscribe`** — a separate MCP call to the
   `unsubscribe` tool removes the sub from the runtime registry.
   The next drain returns `:gone? true`; the poll loop resolves
   with `:reason :sub-gone`.
3. **Cap reached** — `max-ms` or `max-events` is exceeded.

### Failure modes

- `:reason :unknown-topic` if `topic` is missing or not one of the
  four. Surfaced as `isError: true`.
- `:reason :runtime-not-preloaded` if the preload hasn't run.
- `:reason :subscribe-failed` on any other failure during subscribe.

## unsubscribe

Close a streaming subscription out-of-band. Idempotent — closing an
unknown sub-id returns `{:ok? true :sub-id <id> :existed? false}`
rather than an error. Useful when an MCP client wants to stop a
stream without cancelling the `tools/call` directly (e.g. when the
agent host can't propagate cancellation cleanly).

**Args**: `sub-id` (string, **required**), `build` (string).

**Returns**: `{:ok? true :sub-id <id> :existed? <bool>}`.
