# 003-Tool-Catalogue

> Implements the [Tool-Pair contract](../../../spec/Tool-Pair.md) —
> each MCP tool below routes through one or more of the Tool-Pair
> primitives (`get-frame-db`, `epoch-history`, `register-trace-cb!`,
> `register-epoch-cb!`, `restore-epoch`, `reset-frame-db!`,
> `dispatch`, `dispatch-sync`).

The fourteen MCP tools. (The registrar-introspection pair `handler-meta`
+ `registry-list` (rf2-cibp8 / rf2-pctf8) ships in the live registry —
[`src/re_frame_pair2_mcp/tools/registry.cljs`](../src/re_frame_pair2_mcp/tools/registry.cljs) —
but their full per-tool catalogue entries have not yet been migrated
into this file; spec/impl drift tracked by rf2-m9yoi.)

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
vocabulary — an agent that learned the slot on a sibling server
sees the same slot here.

## Universal: size-elision on `:app-db` slots

Every tool that surfaces `:app-db` — `snapshot` (each frame's
`:app-db` slice) and `get-path` (the resolved value) — runs
the slot through `re-frame.core/elide-wire-value` (rf2-v9tw2)
server-side before the EDN crosses the wire (see
[`Principles.md` §Size-elision wire markers](Principles.md#size-elision-wire-markers-rf2-urjnc)).
Each affected tool accepts an `elision` arg (boolean,
default `true`). Schema-driven `:large? true` slots are
substituted with

```clojure
{:rf.size/large-elided
 {:path   [<segment>...]
  :bytes  <int>
  :type   :map | :vector | :set | :string | :scalar
  :reason :schema
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

## Universal: app-installed `:redact-fn` on epoch consumers

Every tool that ships `:rf/epoch-record` values — `dispatch`
(trace mode), `trace-window`, `watch-epochs`, `snapshot` (the
`:epochs` slot of each frame), and `subscribe` (the `epoch`
event-kind) — delivers whatever shape the framework's
app-installed `:redact-fn` produced (per [Tool-Pair §Time-travel
§Redaction hook](../../../spec/Tool-Pair.md#time-travel-epoch-snapshots-and-undo)
and [Security §Epoch privacy posture](../../../spec/Security.md#epoch-privacy-posture--raw-in-process-records-vs-projected-egress)).
When the consuming app has called `(rf/configure :epoch-history
{:redact-fn (fn [record] …)})`, the runtime invokes the fn
**once per assembled record at build-time** (between
`build-record` and ring-append / listener fan-out) — so the
per-frame ring buffer, every `register-epoch-cb!` listener, and
the records pair2-mcp egresses all see the same redacted shape.
Tools cannot recover raw shapes from the wire: any slot the fn
rewrote ships as `:rf/redacted` (the reserved sentinel, per
[Spec-Schemas §`:rf/epoch-record`](../../../spec/Spec-Schemas.md#rfepoch-record))
or whatever app-chosen shape the fn substituted. Agents that
pattern-match on `:db-before` / `:db-after` / `:trigger-event` /
`:trace-events` MUST tolerate `:rf/redacted` (and arbitrary
app-supplied shapes) at every leaf.

The `:rf.epoch/sensitive?` rollup is computed from the raw
record's schema-declared sensitive leaves **before** the
`:redact-fn` runs, so it remains an accurate signal even when
the fn erases the leaves it keyed on — `--allow-raw-state OFF`
strips records that carry the rollup regardless of what the fn
did to the underlying slots.

## Universal: `:typicalTokens` on every tool descriptor

Every MCP tool descriptor emitted by `tools/list` carries a
`:typicalTokens` slot (rf2-6sddv) — an informational ballpark of the
response-payload size in tokens that AI clients use to budget calls
and pick size-conscious args (`max-tokens`, `cache`, `cursor`)
without trial-and-error. The slot is a hint, not a contract: the
real cap is the per-call wire-cap enforced by §Universal:
wire-boundary token cap above. Worst-case-shape; narrowing on tool-
specific args (`limit`, `path`, `:timing-ms` for watch-epochs) shrinks
the actual payload roughly proportional to the match rate.

Each entry lists its declared `:typicalTokens` value in the tool
descriptor — read the body of each tool below to see the budget
hint for that surface. The number lives alongside `:name`,
`:description`, and `:inputSchema` in the JSON-RPC `tools/list`
response.

## Universal: per-session response cache

Every read tool — `snapshot`, `get-path`, `trace-window`,
`watch-epochs`, `discover-app` — opts into an 8-slot LRU
keyed on `(tool, args-fingerprint)` (see
[`Principles.md` §Per-session response cache](Principles.md#per-session-response-cache-rf2-3rt1f)).
Each tool accepts a universal `cache` arg (boolean, default
`false`). When `true` and the result's hash matches the prior
call for the same `(tool, args)`, the full payload is replaced
with a tiny marker:

```clojure
{:rf.mcp/cache-hit
 {:hash            <integer>
  :unchanged-since <ms-since-epoch>
  :tool            "<tool-name>"
  :via             :result-hash | :precheck
  :hint            "<agent-host instruction string>"}}
```

The `:via` slot tells the agent host which cache path produced
the hit:

- **`:result-hash`** (rf2-3rt1f) — the original post-eval path.
  The tool ran server-side; the result's text was hashed; the
  hash matched the stored entry for `(tool, args)`. The MCP
  server saved the **wire bytes** but paid the full nREPL
  round-trip and the local transform pipeline.
- **`:precheck`** (rf2-36xod, rf2-9pe31) — the pre-eval
  short-circuit. One cheap bencode round-trip asked the runtime
  for `(re-frame-pair2.runtime/app-db-hash frame)` — an O(1)
  accessor over the runtime's per-frame cached hash, kept
  current by its epoch listener (rf2-9pe31); the hash matched
  the stored `:precheck-hash`. The MCP server saved **both** the
  wire bytes AND the full tool eval + transform pipeline. The
  tool body was never invoked.

Same wire vocabulary, different cost saved. Agent hosts that
diagnose latency / token usage can branch on `:via` — a
`:precheck` hit is the cheapest possible response in the
catalogue.

The agent host already has the byte-identical bytes from the
prior `tools/call`; re-shipping doubles the conversation cost
for no new information. On a hash miss (state moved on), the
fresh payload is returned and the new hash is stored. Capacity
is 8 — sized for the typical "inspect, dispatch one thing,
inspect again" rhythm; least-recently-used entries are evicted
first. Cache lifetime is the MCP server process (= one MCP
session per the [persistent-socket principle](Principles.md#single-persistent-nrepl-socket));
no cross-process leak, no manual invalidation.

Action tools (`dispatch`, `eval-cljs`, `tail-build`) and
streaming tools (`subscribe`, `unsubscribe`) bypass the cache —
their return value is the result of an action, not a read.
`:isError` results bypass too; a transient failure must not
mask a future successful read.

The marker key `:rf.mcp/cache-hit` matches the cross-MCP wire-
vocabulary family (`:rf.mcp/overflow`, `:rf.mcp/dedup-table`,
`:rf.mcp/summary`, `:rf.size/large-elided`). Agents that
learned the slot family see one more slot.

The cache saves wire bytes, not the nREPL round-trip — the
tool still runs server-side and the result is built locally.
The byte saving is the one the bead targets: a typical
"inspect, dispatch, inspect" workflow today re-ships the full
app-db on the second inspect; with the cache it ships ~100
bytes. Saving the round-trip too needs a server-side hash
precheck and is filed as a follow-on bead.

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

## Universal: server launch flags

Two default-OFF boot gates control authority surfaces (rf2-cxx5s,
rf2-c2dtu). Operators pass them as MCP-server CLI flags:

| Flag                | Default | Effect when ON |
|---------------------|---------|----------------|
| `--allow-eval`      | OFF     | Enables `eval-cljs`. Without the flag, `eval-cljs` returns `{:ok? false :reason :rf.error/eval-cljs-disabled}` without touching the nREPL socket. |
| `--allow-raw-state` | OFF     | Honours caller-supplied `:include-sensitive true` and `:elision false` on direct-read tools (`snapshot`, `get-path`, `subscribe`, `trace-window`, `watch-epochs`). Also signals the preload runtime to ship verbatim payloads through `app-db-reset!`'s `tap>` emission. |

When `--allow-raw-state` is OFF (the published-build default), the
direct-read tools above:

1. Force `:include-sensitive false` on every call. Caller-supplied
   `:include-sensitive true` is dropped before reaching the walker —
   declared-sensitive slots in `:app-db` / `:sub-cache` reads return
   the `:rf/redacted` sentinel; sensitive trace events / epochs are
   stripped from streaming payloads.
2. Force `:elision true` on every call. Caller-supplied
   `:elision false` is dropped — large slots return the
   `:rf.size/large-elided` marker.
3. Signal the preload runtime via
   `(re-frame-pair2.runtime/configure-raw-state! {:allow-raw-state? false})`
   once per build per server lifetime. The runtime's `app-db-reset!`
   then wraps both `:previous` and `:next` slots in the `tap>` payload
   through `re-frame.core/elide-wire-value` — the same redaction the
   wire path applies — so any registered tap consumer sees the
   pre-redacted shape rather than the raw state.

Operators who need raw state for offline debug opt in at server launch
by passing `--allow-raw-state`. The per-call args then win again
(`:include-sensitive true` / `:elision false` pass through to the
walker unchanged).

Symmetric with story-mcp's `--allow-sensitive-reads` (rf2-uaymx) and
causa-mcp's `--allow-eval` (rf2-zyoj2 — same gate as pair2-mcp's
`eval-cljs`). The same pattern across MCP servers gives operators one
posture vocabulary.

## Universal: server resource controls (streaming surfaces)

Four operator-configurable integer caps bound the server's exposure
to a runaway or hostile client of the streaming `subscribe` surface
(rf2-3ijbl, follow-on to the rf2-7adwg MEDIUM finding). Each cap has
a documented default, an override CLI flag (`--<name>=N`), and an
override env var (`<ENV_NAME>=N`). CLI flags win over env vars on
conflict. Values must be positive integers; non-positive or
unparseable values fall back to the default silently.

| Cap                          | Default | CLI flag                          | Env var                                          |
|------------------------------|---------|-----------------------------------|--------------------------------------------------|
| max-concurrent-streams       | 10      | `--max-concurrent-streams=N`      | `RE_FRAME_PAIR2_MCP_MAX_STREAMS`                 |
| max-events-per-sec           | 100     | `--max-events-per-sec=N`          | `RE_FRAME_PAIR2_MCP_MAX_EVENTS_PER_SEC`          |
| abuse-overflow-threshold     | 50      | `--abuse-overflow-threshold=N`    | `RE_FRAME_PAIR2_MCP_ABUSE_OVERFLOW_THRESHOLD`    |
| abuse-window-ms              | 10000   | `--abuse-window-ms=N`             | `RE_FRAME_PAIR2_MCP_ABUSE_WINDOW_MS`             |

### Concurrent-stream cap

`subscribe` calls allocate a runtime-side queue + a server-side poll
loop. The cap bounds the number of simultaneously-open streams per
MCP session (= per server process). When the cap is reached, the
next `subscribe` call rejects WITHOUT touching the nREPL socket:

```clojure
{:ok?    false
 :reason :rf.error/concurrent-stream-limit
 :limit  10
 :active 10
 :hint   "max-concurrent-streams cap reached. Close an existing
          subscription (via the `unsubscribe` tool or by cancelling
          its `tools/call`) before opening another, or raise the
          cap with --max-concurrent-streams=N at server launch."}
```

The slot is released on every stream-termination path (client
cancel, `unsubscribe`, `:max-events` / `:max-ms` / `:sub-gone` /
`:rf.error/stream-abuse-detected`, probe / signal / subscribe-eval
failure).

### Per-session event rate-limit

A session-wide token bucket caps the rate of progress-notification
ticks emitted across all open streams. Refill rate = bucket capacity
= `max-events-per-sec`. Excess ticks are silently dropped (the
runtime-side queue still holds the events; subsequent ticks drain
them when tokens refill). The `tools/call` final summary surfaces
the cumulative count as `:rate-dropped` (omitted when zero).

Token-bucket over leaky-bucket: streaming trace events are bursty
by nature (one event triggers a cascade of fx + sub-runs + renders
in one drain). Token-bucket allows brief bursts up to the cap while
still bounding the long-run rate.

### Disconnect-on-abuse heuristic

Whenever a drain reports `:overflow-reason` non-nil (the runtime's
per-sub queue evicted), the server records the overflow on a
session-wide rolling window of length `abuse-window-ms`. When the
count over the window exceeds `abuse-overflow-threshold`, the stream
terminates with `:reason :rf.error/stream-abuse-detected` and a
stderr log line. The default (50 overflows in 10s ≈ sustained
5/sec eviction) indicates the consumer can't keep up; continuing
the stream burns CPU + wire bandwidth.

The abuse window is session-wide (not per-stream): a hostile client
that opens one abusive stream, hits the threshold, then opens
another starts with a non-empty window. Resetting requires either
ending the session (closing the MCP-server process) or letting the
window expire naturally.

### Symmetric with sibling DoS bounds

Mirrors story-mcp's rf2-g9fje DoS-bounds shape (JSON frame size,
timeout caps, cancellation) — same posture vocabulary across MCP
servers: operator-configurable bounds with documented defaults,
structured rejection envelopes, indicator-field counters on the
result. The `:rf.error/*` keyword vocabulary stays consistent
across the cross-MCP error surface.

## eval-cljs

Evaluate a CLJS form in the connected browser runtime via
`shadow.cljs.devtools.api/cljs-eval`. Returns the EDN value.

**Args**: `form` (string, required), `build` (string, optional).

**Launch-flag gate**: `--allow-eval` (rf2-cxx5s). Default OFF; calls
return `{:ok? false :reason :rf.error/eval-cljs-disabled ...}` without
touching the nREPL socket. See §Universal: server launch flags.

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

## Universal: cursor pagination on epoch slices

The two tools that ship unbounded epoch vectors — `trace-window` and
`watch-epochs` — accept `:limit` (int, default 50) and `:cursor`
(opaque string). Pages over a stale ring surface as a structured
error rather than silently restarting (see
[`Principles.md` §Pagination](Principles.md#per-tool-budget-discipline)).

```clojure
{:ok?                 true
 :limit               50              ; the cap that bounded this page
 :count               50              ; items in this page
 :epochs              [...]           ; the page itself
 :has-more?           true|false
 :estimated-remaining N                ; remaining matches in current ring
 :next-cursor         "<base64-edn>" | nil}
```

The cursor is opaque on the wire — agents pass `:next-cursor` back as
`:cursor` on the next call. Default `:limit` (50) is sized to fit the
5K-token wire-cap after diff-encode (rf2-1wdzp) and dedup (rf2-obpa9).
The cursor's payload (base64-encoded EDN; subject to change behind the
opaque boundary) carries the last-emitted epoch-id plus sticky window
fields (`:ms`, `:until-ms`, `:frame`) so subsequent pages see the same
window the first call did — fresh epochs landing during pagination
don't sneak in mid-iteration.

### Cursor staleness

The runtime's epoch ring is bounded. If the cursor's epoch-id has
rotated out between calls (or the cursor is malformed), the response
is:

```clojure
{:ok?          false
 :reason       :rf.mcp/cursor-stale
 :tool         "trace-window" | "watch-epochs"
 :requested-id <id>
 :head-id      <current-head>
 :hint         "..."}
```

Agents pattern-match on `:reason :rf.mcp/cursor-stale` and either drop
the cursor and restart, or widen the window (`watch-epochs` accepts a
larger pred filter; `trace-window` accepts a larger `ms`).

## trace-window

Return `:rf/epoch-record`s that landed in the last N ms for the
operating frame.

**Args**: `ms` (integer, default 1000 — sticky across cursor pagination,
encoded in the cursor on the first call), `frame` (string),
`limit` (int, default 50 — see §Cursor pagination above),
`cursor` (string, opaque continuation token — see §Cursor pagination
above), `epochs-mode` (string — `"diff"` (default) or `"full"`, see
§Diff-encoded `:db-after` below), `dedup` (boolean, default `true` —
see §Structural dedup at the top of this catalogue), `build` (string).

**Returns**: `{:ok? true :window-ms N :until-ms T :count K :limit L :epochs-mode :diff|:full :epochs [...] :has-more? bool :estimated-remaining N :next-cursor "<base64>"|nil}`.

### Diff-encoded `:db-after` (rf2-1wdzp + rf2-qeous)

By default (`epochs-mode "diff"`), each epoch's `:db-after` is replaced
with a path-headed cluster projection of a path-keyed structural diff
against its own `:db-before`:

```clojure
{:db-before <full-app-db>
 :db-after  {:rf.mcp/diff-from :db-before
             :sections [{:section-path [:cart :items]
                         :section-kind :modified
                         :patches      [[[:cart :items 0 :qty] :assoc 2]]}
                        {:section-path [:checkout :state]
                         :section-kind :modified
                         :patches      [[[:checkout :state] :assoc :paying]]}
                        ...]}}
```

Each section heads N patches with a breadcrumb path
(`:section-path`) plus a cluster-intent summary (`:section-kind`,
one of `:added` / `:removed` / `:modified`) — what the agent reads to
answer "what did this cascade do?" without re-clustering flat
triples. The per-section `:patches` slot carries the leaf-level
detail; decoding flattens them back to one ordered patch list and
applies via `apply-patches`. The diff is intra-record (each record
encodes against its own `:db-before`); records are self-contained
and decodable without reference to siblings. Round-trip is exact.
Pass `epochs-mode "full"` for the legacy full-pair shape — only
needed if your workflow drives time-travel restore off the wire
response rather than via the runtime (the framework's
`rf/restore-epoch` path is the canonical restore surface).

See [`Principles.md` §Diff-encoded `:db-after`](Principles.md#diff-encoded-db-after-on-epoch-slices-rf2-1wdzp)
for the full wire shape, decoder algorithm, and design rationale. The
same `epochs-mode` arg and wire shape apply to `watch-epochs` and to
the `:epochs` slice of `snapshot`.

## watch-epochs

Pull-mode poll for matching epochs added after a given epoch-id.
This is the MCP equivalent of the bash `watch-epochs.sh` script's
poll loop — but MCP isn't streaming, so callers that want a tight
loop should call us repeatedly with the same `since-id`.

**Args**: `since-id` (string, optional — omit to start fresh; supplanted
by `cursor` when both are supplied), `pred` (object, optional predicate
filter, keys from: `:event-id`, `:event-id-prefix`, `:effects`,
`:touches-path`, `:sub-ran`, `:render`, `:origin`, `:frame`,
`:timing-ms`), `frame`,
`limit` (int, default 50 — see §Cursor pagination above), `cursor`
(string, opaque continuation token — see §Cursor pagination above),
`epochs-mode` (string — `"diff"` (default) or `"full"`, see
`trace-window` §Diff-encoded `:db-after`), `dedup` (boolean,
default `true` — see §Structural dedup at the top of this
catalogue), `build`.

`:timing-ms` (rf2-r3azh) — server-side wall-clock filter on the
cascade's elapsed-ms (derived from the `:event/run-start` /
`:event/run-end` trace pair on `:time`; spans first run-start to last
run-end so synchronously-dispatched same-cascade chains roll up). The
filter rides server-side so non-matching epochs never cross the wire
— `typicalTokens` is the worst case; narrowing on `:timing-ms` (e.g.
`">100"` to surface only slow events) shrinks the payload roughly
proportional to the match rate. Accepts either a number (sugar for
`>= N`) or a comparison string `">N"` / `">=N"` / `"<N"` / `"<=N"` /
`"=N"`. Epochs whose `:trace-events` slot was elided (long-aged ring
entries) carry no derivable timing and never match a numeric
threshold.

**Returns**: `{:ok? true :matches [...] :limit L :count K :head-id "..." :id-aged-out? bool :epochs-mode :diff|:full :has-more? bool :estimated-remaining N :next-cursor "<base64>"|nil}`.

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
strings — path-slicing for the `:app-db` slice, rf2-tygdv), `mode`
(string — `"summary"` (default) or `"full"` — global lazy-summary
default for every rich slice; see §Lazy-summary mode below, rf2-u2029),
`modes` (object — per-slice override of `mode`, e.g.
`{"app-db": "full", "epochs": "summary"}`; takes precedence over the
global `mode` arg, rf2-u2029), `epochs-mode` (string — `"diff"`
(default) or `"full"`, see `trace-window` §Diff-encoded `:db-after`;
controls the `:epochs` slice's wire shape, rf2-1wdzp), `dedup`
(boolean, default `true` — applies structural dedup per-frame to each
`:epochs` slot; see §Structural dedup at the top of this catalogue),
`elision` (boolean, default `true` — applies the size-elision walker
to each frame's `:app-db` slice; see §Size-elision at the top of this
catalogue, rf2-urjnc), `build` (string).

**Returns**:

```clojure
{:ok? true
 :frames :all|[<frame-id>...]
 :include [:app-db :sub-cache :machines :epochs :traces]
 :mode :summary | :full | :path-sliced
 :slice-modes {:app-db    :summary | :full | :path-sliced
               :sub-cache :summary | :full
               :machines  :summary | :full
               :epochs    :summary | :full
               :traces    :summary | :full}
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

Each slice in `:snapshot` is either the raw payload (when its resolved
mode is `:full` or, for `:app-db`, when a `path` arg is supplied) or a
`{:rf.mcp/summary {:type :map|:vector|:set|:seq|:scalar :keys [...]
:count N :bytes ~B}}` marker (when its resolved mode is `:summary` —
the default). The top-level `:mode` echoes the snapshot's primary
posture for backward compatibility; the per-slice `:slice-modes` map
tells the agent which slices it can drill into without a second call.

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

### Lazy-summary mode (rf2-u2029)

Every rich slice in the snapshot response defaults to a
`{:rf.mcp/summary {:type ... :keys [...] :count N :bytes ~B}}`
marker — the top-level shape without committing the token budget.
The default snapshot call (no `mode`, no `path`) returns summary
markers for all five slices. A 1MB-app-db / 10-epoch-history
discovery snapshot collapses from tens of millions of tokens to
under 500. Agents drill into the slice they actually need via one
of three opt-ins:

- **Global `mode "full"`**: every rich slice expands to its raw
  payload. Equivalent to the pre-rf2-u2029 default. The wire cap
  (rf2-rvyzy) becomes the backstop.
- **Per-slice `modes {"epochs": "full"}`** (and equivalents): expand
  only the named slice; others stay summarised. Per-slice override
  beats the global `mode` arg. Slice names match the `include` arg's
  vocabulary: `app-db`, `sub-cache`, `machines`, `epochs`, `traces`.
- **`path` arg** (`:app-db` slice only): return the subtree at the
  requested path. Path-slicing supersedes the slice-level mode for
  `:app-db` — a `path` arg always wins.

The `:mode` slot in the response echoes the snapshot's primary posture
(`:summary` | `:full` | `:path-sliced`). The `:slice-modes` map gives
the per-slice resolution so the agent can pattern-match on which
slices are markers vs raw payloads without re-deriving the choice
from the request shape.

The summary marker's `:bytes` hint is a cheap APPROXIMATION
(rf2-qta8j) — `entry-count × per-entry-constant`, not a precise
serialised byte count. The marker's whole point is to avoid
serialising the deep value (a 54MB app-db slice would otherwise burn
a 54MB string allocation per summary just to compute one integer);
agents needing a precise byte count walk the drill-down result
directly. The marker is computed AFTER diff-encoding and dedup so
the entry count reflects the post-shrink top-level shape. A map
with more than 64 top-level keys truncates the `:keys` list and
flags `:keys-truncated? true` so the marker itself can never blow
the wire cap.

### `:app-db` slice modes (rf2-tygdv)

The `:app-db` slice has three response postures:

- **`:mode :summary`** (default, no `path`, no `mode` override): the
  `:app-db` slice is the `{:rf.mcp/summary ...}` marker described
  above (rf2-tygdv landed this for `:app-db`; rf2-u2029 generalised
  to every rich slice).
- **`:mode :full`** (no `path`, `mode "full"` or `modes {"app-db":
  "full"}`): the full slice — equivalent to passing root path `[]`.
- **`:mode :path-sliced`** (with `path`): the `:app-db` slice is the
  subtree at `(get-in db path)`. An out-of-range path surfaces
  per-frame in the top-level `:path-not-found` map with the
  deepest-valid-prefix attached so the agent can re-aim.

Path vocabulary matches `get-in`: a vector of keys / indices. EDN
strings (`":cart"`, `"0"`, `"-1"`) are parsed by the reader; non-EDN
strings (`"bare-key"`) stay as map-key strings. Same vocabulary as
the `get-path` tool below and as Causa-MCP's `:path` mechanism — one
shape across the tool family.

The other slices (`:sub-cache`, `:machines`, `:epochs`, `:traces`)
follow the same `mode` / `modes` opt-in shape. Pass a smaller
`include` to drop slices entirely (e.g. `{:frames "all" :include
["app-db" "epochs"]}` for a quick "state + recent history" probe).
Per-op fine-grain reads (`get-path` against the app-db, `eval-cljs`
against `runtime/sub-cache`, etc.) stay available — they're the
right surface when you genuinely need one slice for one frame.
`snapshot` is the right surface when you don't know yet which slice
carries the answer; the lazy-summary default keeps that discovery
workflow inside the wire cap by construction.

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
summary `{:ok? true :sub-id :delivered N :dropped-events N
:dropped-bytes M :overflow-reason <kw> :ticks K :reason
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
| `:sensitive?`    | `(:sensitive? ev)` — boolean. **Default forwarder posture:** events with `:sensitive? true` are dropped at the MCP boundary before any data reaches the agent surface (per [spec/009 §Privacy / sensitive data](../../../spec/009-Instrumentation.md#privacy--sensitive-data-in-traces)). The runtime stamps the flag on every trace event emitted inside a `:sensitive? true` registration's handler scope. Opt back in per-call with `include-sensitive true` (an MCP tool arg on `trace-window`, `watch-epochs`, `snapshot`, `subscribe`). Dropped count surfaces as `:dropped-sensitive` on the result / progress payload when non-zero. |

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
| `:timing-ms`         | Cascade elapsed-ms (first `:event/run-start` → last `:event/run-end` on `:time`) matches the threshold. Number `N` is sugar for `>= N`; strings `">N"` / `">=N"` / `"<N"` / `"<=N"` / `"=N"` set the comparator. Epochs with no derivable timing never match (rf2-r3azh). |

### Args

- `topic` (string, **required**) — one of `"trace"`, `"epoch"`, `"fx"`,
  `"error"`.
- `filter` (object **or** string, optional) — filter map. Accepted as
  a JSON object or an EDN-encoded string. EDN is preferred when the
  filter carries keywords or namespaced ids (a JSON object can't
  carry `:cart/add` natively).
- `max-buffered-events` (integer, default `500`) — runtime-side queue
  cap in EVENTS. OR-combined with `max-buffered-bytes` — whichever
  budget trips first evicts. On overflow the OLDEST events are
  evicted (drop-oldest FIFO); the count and which budget tripped
  surface on the next progress tick as `:dropped-events` and
  `:overflow-reason :max-buffered-events`.
- `max-buffered-bytes` (integer, default `5_000_000` ≈ 5 MB) —
  runtime-side queue cap in BYTES (pr-str char count, the same
  unit as the wire-boundary cap). Same drop-oldest policy; reports
  `:dropped-bytes` and `:overflow-reason :max-buffered-bytes`. This
  exists (rf2-ho4ve) because an event-count-only budget can't bound
  memory pressure under large payloads — 500 small events fit in a
  few KB, while 500 large events can be tens of MB. The byte budget
  is the load-bearing bound; the event budget is a coarse backstop.
- `poll-ms` (integer, default `100`) — server-side poll cadence. The
  MCP server polls the runtime's drain at this interval and emits a
  progress notification per non-empty batch.
- `max-ms` (integer, default `0` = unbounded) — hard upper-bound on
  how long the subscription stays open. `0` = stay open until the
  client cancels.
- `max-events` (integer, default `0` = unbounded) — terminate after
  this many events have been delivered.
- `include-sensitive` (boolean, default `false`) — opt back in to
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
    "message": "{:sub-id \"...\" :events [...] :dropped-events 0 :dropped-bytes 0}",
    "_meta": {
      "data": {
        "dropped-events": 0,                    // events evicted this tick
        "dropped-bytes":  0,                    // bytes evicted this tick (pr-str)
        "overflow-reason": null                 // ":max-buffered-events" | ":max-buffered-bytes" | null
      }
    }
  }
}
```

`message` is an EDN-printed string carrying the event batch — the
same shape the runtime's `drain-subscription!` returns. Capable MCP
clients can also inspect the `_meta.data` slot for the structured drop
counts. `_meta` is used because the official MCP SDK preserves it in
progress callbacks while stripping unknown top-level progress fields.
`overflow-reason` carries the stringified EDN keyword of the
budget that tripped LAST (`":max-buffered-events"` or
`":max-buffered-bytes"` — see [`Principles.md` §Streaming subscribe
byte+event budget](Principles.md#streaming-subscribe-byteevent-budget-rf2-ho4ve)
for the policy). `null` when no eviction happened on this tick.

On termination, the `tools/call` result is

```clojure
{:ok? true
 :sub-id <uuid>
 :topic  <keyword>
 :delivered      <integer>
 :dropped-events <integer>   ; total events evicted from the runtime queue
 :dropped-bytes  <integer>   ; total bytes evicted
 :overflow-reason :max-buffered-events | :max-buffered-bytes | (key absent)
 :rate-dropped   <integer>   ; ticks silenced by the per-session rate cap (omitted when zero)
 :ticks     <integer>
 :reason    :aborted | :sub-gone | :max-ms-reached | :max-events-reached |
            :rf.error/stream-abuse-detected}
```

`:reason` is `:aborted` when the client cancelled the call,
`:sub-gone` when the runtime's subscription disappeared (typically a
full page reload, or an `unsubscribe` op fired separately),
`:max-ms-reached` / `:max-events-reached` when the caller's
upper-bounds fire, or `:rf.error/stream-abuse-detected` when the
session's rolling-window overflow count exceeded
`abuse-overflow-threshold` (rf2-3ijbl — see [§Universal: server
resource controls](#universal-server-resource-controls-streaming-surfaces)).

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
4. **Abuse detected (rf2-3ijbl)** — sustained queue overflow exceeded
   `abuse-overflow-threshold` over `abuse-window-ms`. The stream
   terminates with `:reason :rf.error/stream-abuse-detected` and a
   stderr log line; the operator can raise the threshold via
   `--abuse-overflow-threshold=N` if the workload legitimately
   produces high overflow rates.

### Failure modes

- `:reason :unknown-topic` if `topic` is missing or not one of the
  four. Surfaced as `isError: true`.
- `:reason :runtime-not-preloaded` if the preload hasn't run.
- `:reason :subscribe-failed` on any other failure during subscribe.
- `:reason :rf.error/concurrent-stream-limit` if the session already
  has `max-concurrent-streams` open subscriptions. Surfaced as
  `isError: true` WITHOUT touching the nREPL socket. The error
  envelope carries `:limit` / `:active` / `:hint` for the operator
  to act on. See [§Universal: server resource controls](#universal-server-resource-controls-streaming-surfaces).

### Diagnostics

When a stream seems quiet or stalled, the `subscription-info` tool
below lists every currently-registered subscription with its
queue-depth, drop counts, and overflow-reason — without draining
queues. Use it to confirm the sub is still alive and to check
whether the byte / event budget is evicting under pressure.

## unsubscribe

Close a streaming subscription out-of-band. Idempotent — closing an
unknown sub-id returns `{:ok? true :sub-id <id> :existed? false}`
rather than an error. Useful when an MCP client wants to stop a
stream without cancelling the `tools/call` directly (e.g. when the
agent host can't propagate cancellation cleanly).

**Args**: `sub-id` (string, **required**), `build` (string).

**Returns**: `{:ok? true :sub-id <id> :existed? <bool>}`.

## subscription-info

Diagnostic listing of currently-registered streaming subscriptions —
the "what streams are open right now?" surface. Pure read over the
runtime's `subscriptions` atom; **does NOT drain any queues** and
does NOT alter the stream contents that `subscribe` will see on its
next tick. Wraps the `re-frame-pair2.runtime/subscription-info`
runtime fn directly (one cheap nREPL eval — no `eval-cljs`
round-trip needed). Useful when a streaming probe seems to have gone
quiet: confirm the sub is still registered, inspect `:queue-depth` /
`:queue-bytes` for evidence of a stuck consumer, or check
`:overflow-reason` for budget pressure that needs tuning on the next
`subscribe` call.

Unlike the other read tools, `subscription-info` reads the runtime's
internal subscription registry rather than routing through one of the
Tool-Pair primitives listed in the intro — its peer surface is the
streaming registry that `subscribe` / `unsubscribe` mutate, not the
frame-db / epoch-history / trace-buffer surfaces.

**Args** (all optional):

- `topic` (string, optional) — narrow to one topic. One of `"trace"`,
  `"epoch"`, `"fx"`, `"error"`.
- `sub-id` (string, optional) — return only the sub with this uuid
  (the uuid returned by `subscribe`). Convenient for "is this
  specific stream still alive?" checks.
- `build` (string, optional, default `"app"`) — shadow-cljs build id.

Both filters compose with AND: passing both `topic` and `sub-id`
returns the sub only if it matches on both axes.

**Returns**:

```clojure
{:ok? true
 :subs [{:id              <uuid-string>
         :topic           :trace | :epoch | :fx | :error
         :filter          <filter-map-as-supplied-to-subscribe>
         :queue-depth     <integer>       ; events buffered server-side
         :queue-bytes     <integer>       ; pr-str chars buffered server-side
         :dropped-events  <integer>       ; cumulative drops by event-budget
         :dropped-bytes   <integer>       ; cumulative drops by byte-budget
         :overflow-reason :max-buffered-events | :max-buffered-bytes | nil
         :created-at      <ms-since-epoch>}
        ...]}
```

`:subs` is an empty vector when no streams are open (or when the
filters match nothing) — never `:ok? false` for the empty case. A
non-nil `:overflow-reason` is the load-bearing signal: the queue has
been evicting older events under the byte or event budget configured
on its `subscribe` call. Tune `max-buffered-events` /
`max-buffered-bytes` on the next `subscribe` call when this fires
unexpectedly; see `subscribe` above for the budget vocabulary and
[`Principles.md` §Streaming subscribe byte+event budget](Principles.md#streaming-subscribe-byteevent-budget-rf2-ho4ve)
for the policy.

The output is **not** routed through the universal dedup / elision /
cache pipeline at the top of this catalogue — the payload is
already a small flat vector of metadata records (no `:app-db`
slices, no event vectors), so the wire-cap is the only universal
that applies. `subscription-info` does NOT carry sensitive event
bodies; only registration metadata crosses the wire.

`:reason :runtime-not-preloaded` if the preload hasn't run;
`:reason :subscription-info-failed` (with `:message`) on any other
failure.

A future causa-mcp peer will ship `list-subscriptions` — same
diagnostic shape, NAMING.md-conformant verb in causa-mcp's
catalogue.

## get-pair2-instructions

Agent-onboarding text (rf2-fnpqg). Returns an inline prose summary
of pair2-mcp's tool catalogue, the EDN posture, the `:origin :pair`
tagged-mutation convention, the streaming `subscribe` semantics,
and the wire-boundary pipeline (precheck → elision → diff-encode
→ dedup → cap).

Mirrors story-mcp's `get-story-instructions` — agent hosts call
this at session start to orient before the first real op. No nREPL
round-trip; the text is a `def` in the compiled `.js` bundle so
the call is one MCP frame and zero socket bytes. The cache layer
(`cache.cljs`) marks this tool `cacheable? true` since the text is
a pure-data function of the bundle.

**Args**: none recognised. `:additionalProperties false`.

**Returns**:

```clojure
{:ok? true
 :tool "get-pair2-instructions"
 :text "<prose>"}
```

The `:text` slot is a single string the agent host renders
verbatim. It carries no `:rf.size/large-elided` markers (no app-db
slot), no `:rf.mcp/dedup-table` (no repeated subtrees), and no
streaming machinery — just text.

Maintenance: the text lives in
`tools/get_pair2_instructions.cljs` as the `instructions-text` def.
Edit it when the catalogue grows or shrinks. The structural peer
is the `re-frame-pair2-mcp.tools/tool-descriptors` docstring;
keep the two in lockstep when adding or removing tools.
