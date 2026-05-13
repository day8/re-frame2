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

## Universal: structural dedup on epoch slices

Every tool that ships epoch slices or events vectors —
`snapshot` (the `:epochs` slot of each frame), `trace-window`,
`watch-epochs`, and `subscribe` (per-tick `:events` vector) —
applies structural dedup after diff-encoding and before the
wire-cap check (see
[`Principles.md` §Structural dedup](Principles.md#structural-dedup-rf2-obpa9)).
Each affected tool accepts a `dedup` arg (boolean, default
`true`). Deduped payloads are wrapped as

```clojure
{:rf.mcp/dedup-table
 {:de-dupe.cache/cache-0 <root-with-refs>
  :de-dupe.cache/cache-1 <shared-subtree>
  ...}}
```

The cache map is `day8/de-dupe`'s flat output. Agents
reconstruct with `(de-dupe.core/expand cache-map)` — one
library call, exact round-trip. Pass `dedup false` to skip the
wrap (e.g. for ad-hoc reads when the agent host hasn't been
taught to call `expand`).

The marker key `:rf.mcp/dedup-table` matches the cross-MCP
vocabulary declared in
[causa-mcp `Principles.md` §5](../../causa-mcp/spec/Principles.md) —
an agent that learned the slot on causa-mcp sees the same slot
here.

## Universal: size-elision on `:app-db` slots

Every tool that surfaces `:app-db` — `snapshot` (each frame's
`:app-db` slice) and `get-path` (the resolved value) — runs
the slot through `re-frame.core/elide-wire-value` (rf2-v9tw2)
server-side before the EDN crosses the wire (see
[`Principles.md` §Size-elision wire markers](Principles.md#size-elision-wire-markers-rf2-urjnc)).
Each affected tool accepts an `elision` arg (boolean,
default `true`). Declared paths
(`rf/declare-large-path!`), schema-driven paths (`:large?
true`), and over-threshold leaves are substituted with

```clojure
{:rf.size/large-elided
 {:path   [<segment>...]
  :bytes  <int>
  :type   :map | :vector | :set | :string | :scalar
  :reason :declared | :schema | :runtime-flagged
  :hint   <string-or-nil>
  :handle [:rf.elision/at <path>]}}
```

The substitution is at the elided slot — small siblings ride
verbatim. Agents drill into the slot via `get-path` using the
handle's path, or pass `elision false` to bypass the walker
and receive the raw value. Markers fire BEFORE the
path-slicing / diff-encode / dedup / wire-cap pipeline, so
cap measures post-elision bytes — a single declared-large
slot can no longer blow the cap on its own.

The marker key `:rf.size/large-elided` and the handle
vocabulary `[:rf.elision/at <path>]` are reserved per
[`Conventions §Reserved namespaces`](../../../spec/Conventions.md)
and [`Spec 009 §Size elision in traces`](../../../spec/009-Instrumentation.md);
the shape is shared across pair2-mcp, story-mcp, and causa-mcp.

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
`epochs-mode` (string — `"diff"` (default) or `"full"`, see §Diff-encoded
`:db-after` below), `dedup` (boolean, default `true` — see §Structural
dedup at the top of this catalogue), `build` (string).

**Returns**: `{:ok? true :window-ms N :count K :epochs-mode :diff|:full :epochs [...]}`.

### Diff-encoded `:db-after` (rf2-1wdzp)

By default (`epochs-mode "diff"`), each epoch's `:db-after` is replaced
with a path-keyed structural diff against its own `:db-before`:

```clojure
{:db-before <full-app-db>
 :db-after  {:rf.mcp/diff-from :db-before
             :patches [[<path> :assoc <new-value>]
                       [<path> :dissoc]
                       ...]}}
```

The diff is intra-record (each record encodes against its own
`:db-before`); records are self-contained and decodable without
reference to siblings. Round-trip is exact. Pass `epochs-mode "full"`
for the legacy full-pair shape — only needed if your workflow drives
time-travel restore off the wire response rather than via the runtime
(the framework's `rf/restore-epoch` path is the canonical restore
surface).

See [`Principles.md` §Diff-encoded `:db-after`](Principles.md#diff-encoded-db-after-on-epoch-slices-rf2-1wdzp)
for the full wire shape, decoder algorithm, and design rationale. The
same `epochs-mode` arg and wire shape apply to `watch-epochs` and to
the `:epochs` slice of `snapshot`.

## watch-epochs

Pull-mode poll for matching epochs added after a given epoch-id.
This is the MCP equivalent of the bash `watch-epochs.sh` script's
poll loop — but MCP isn't streaming, so callers that want a tight
loop should call us repeatedly with the same `since-id`.

**Args**: `since-id` (string, optional — omit to start fresh),
`pred` (object, optional predicate filter, keys from:
`:event-id`, `:event-id-prefix`, `:effects`, `:touches-path`,
`:sub-ran`, `:render`, `:origin`, `:frame`), `frame`,
`epochs-mode` (string — `"diff"` (default) or `"full"`, see
`trace-window` §Diff-encoded `:db-after`), `dedup` (boolean,
default `true` — see §Structural dedup at the top of this
catalogue), `build`.

**Returns**: `{:ok? true :matches [...] :head-id "..." :id-aged-out? bool :epochs-mode :diff|:full}`.

Each match has its `:db-after` diff-encoded against its own
`:db-before` by default (rf2-1wdzp); pass `epochs-mode "full"` for
the legacy full-pair shape. See `trace-window` above for the wire
shape and rationale.

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
default all five), `path` (EDN-encoded vector or JSON array of segment
strings — path-slicing for the `:app-db` slice, rf2-tygdv),
`epochs-mode` (string — `"diff"` (default) or `"full"`, see `trace-window`
§Diff-encoded `:db-after`; controls the `:epochs` slice's wire shape,
rf2-1wdzp), `dedup` (boolean, default `true` — applies structural dedup
per-frame to each `:epochs` slot; see §Structural dedup at the top of
this catalogue), `elision` (boolean, default `true` — applies the
size-elision walker to each frame's `:app-db` slice; see §Size-elision
at the top of this catalogue, rf2-urjnc), `build` (string).

**Returns**:

```clojure
{:ok? true
 :frames :all|[<frame-id>...]
 :include [:app-db :sub-cache :machines :epochs :traces]
 :mode :summary | :path-sliced
 :epochs-mode :diff | :full
 :dedup   true | false
 :elision true | false
 :path  [<segment>...]              ; only when `path` arg was supplied
 :snapshot {<frame-id> {:app-db    <slice>          ; large slots → :rf.size/large-elided marker
                        :sub-cache {<query-v> {:value v :ref-count n}}
                        :machines  {:ids [<machine-id>...]
                                    :state {<machine-id> <snapshot>}}
                        :epochs    [<:rf/epoch-record> ...]
                        :traces    [<trace-event> ...]}
            ...}
 :path-not-found {<frame-id> {:exists? false
                              :deepest-valid-prefix [...]}}  ; when present}
```

The `:machines` slice combines the global registrar's machine-id list
(`rf/machines`) with the per-frame state stash at `[:rf/machines]` in
the frame's `app-db` (per Spec 005). The `:traces` slice filters the
retain-N trace ring buffer by `:frame`. Other slices delegate
verbatim to the public per-slice surface.

### `:epochs` slice modes (rf2-1wdzp)

Each epoch in the `:epochs` slice has its `:db-after` diff-encoded
against its own `:db-before` by default — `pr-str` doesn't preserve
structural sharing across records, so the legacy full-pair shape
otherwise carries ~2× app-db per record. Pass `epochs-mode "full"` for
the legacy shape (rare — only needed if you drive time-travel restore
off the wire response rather than via `rf/restore-epoch`). See
`trace-window` above for the wire shape and rationale.

### `:app-db` slice modes (rf2-tygdv)

The `:app-db` slice has two response modes governed by the `path` arg:

- **`:mode :summary`** (default, no `path`): the `:app-db` slice is
  replaced with a `{:rf.mcp/summary {:type :map :keys [...] :count
  ... :bytes ~...}}` marker — the top-level shape without committing
  the token budget. A map with more than 64 top-level keys truncates
  the `:keys` list and flags `:keys-truncated? true` so the marker
  itself can never blow the wire cap. The agent drills down with a
  follow-up call carrying `path`, or with the `get-path` tool.
- **`:mode :path-sliced`** (with `path`): the `:app-db` slice is the
  subtree at `(get-in db path)`. An out-of-range path surfaces
  per-frame in the top-level `:path-not-found` map with the
  deepest-valid-prefix attached so the agent can re-aim.
- **Root path `[]`**: the agent explicitly asks for the full `:app-db`
  — equivalent to the legacy default. The wire cap then becomes the
  backstop.

Path vocabulary matches `get-in`: a vector of keys / indices. EDN
strings (`":cart"`, `"0"`, `"-1"`) are parsed by the reader; non-EDN
strings (`"bare-key"`) stay as map-key strings. Same vocabulary as
the `get-path` tool below and as Causa-MCP's `:path` mechanism — one
shape across the tool family.

The other slices (`:sub-cache`, `:machines`, `:epochs`, `:traces`)
pass through unchanged. Pass a smaller `include` to subset (e.g.
`{:frames "all" :include ["app-db" "epochs"]}` for a quick "state +
recent history" probe). Per-op fine-grain reads (`get-path` against
the app-db, `eval-cljs` against `runtime/sub-cache`, etc.) stay
available — they're the right surface when you genuinely need one
slice for one frame. `snapshot` is the right surface when you don't
know yet which slice carries the answer.

`:reason :runtime-not-preloaded` if the preload hasn't run;
`:reason :snapshot-failed` (with `:message`) on any other failure.

## get-path

Read a single value at `path` from a frame's `app-db`. Minimal
primitive for targeted reads — the agent already knows the path.
Server-side `(get-in db path)`; only the addressed subtree crosses
the wire (rf2-tygdv).

**Args**: `path` (string — EDN-encoded vector, e.g. `"[:cart :items 0
:sku]"` — or JSON array of segment strings; required), `frame`
(string — frame-id, default operating frame), `elision` (boolean,
default `true` — applies the size-elision walker to the resolved
value; see §Size-elision at the top of this catalogue, rf2-urjnc),
`build` (string).

**Returns** on success:

```clojure
{:ok?     true
 :exists? true
 :path    [<segment>...]
 :value   <subtree>            ; may be `:rf.size/large-elided` marker when elision applies
 :elision true | false
 :frame   <frame-id>}          ; only when frame arg was supplied
```

When the path doesn't resolve:

```clojure
{:ok?                  false
 :reason               :path-not-found
 :path                 [<segment>...]
 :deepest-valid-prefix [<segment>...]
 :frame                <frame-id>}     ; only when frame arg was supplied
```

`:exists?` distinguishes a path that legitimately points at a `nil`
value (`:exists? true :value nil`) from a path that doesn't resolve
(`:ok? false :reason :path-not-found`). The deepest-valid-prefix lets
the agent re-aim without a binary search.

When `elision` is enabled (default), a declared / schema-`:large?`
path or an over-threshold leaf returns a `:rf.size/large-elided`
marker (with a `:handle [:rf.elision/at <path>]` fetch handle) in
place of the raw bytes. Drill into a non-elided child by re-calling
with a deeper `path`. Pass `elision false` to bypass the walker.

`get-path` is the read-by-path surface for when `snapshot`'s
`:summary` mode tells the agent which key carries the answer.
`snapshot {... :path [...]}` is the equivalent surface when the agent
wants several slices in the same round-trip; both share the same
`:path` vocabulary.

`:reason :runtime-not-preloaded` if the preload hasn't run;
`:reason :missing-path` if `path` was omitted;
`:reason :get-path-failed` (with `:message`) on any other failure.

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
- `dedup` (boolean, default `true`) — apply structural dedup
  (rf2-obpa9) to each progress payload's `:events` vector. See
  §Structural dedup at the top of this catalogue. The cache is
  per-tick (each `notifications/progress` frame carries its own
  table; no cross-tick refs). Pass `false` to skip.
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
