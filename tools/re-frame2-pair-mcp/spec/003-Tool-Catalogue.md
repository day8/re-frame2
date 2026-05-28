# 003-Tool-Catalogue

> Implements the [Tool-Pair contract](../../../spec/Tool-Pair.md) —
> each MCP tool below routes through one or more of the Tool-Pair
> primitives (`get-frame-db`, `epoch-history`, `register-listener!`,
> `register-epoch-listener!`, `restore-epoch`, `reset-frame-db!`,
> `dispatch`, `dispatch-sync`).

The seventeen MCP tools. All seventeen are catalogued below; the
registrar-introspection pair `handler-meta` + `list-handlers` (rf2-cibp8
/ rf2-pctf8 — `list-handlers` renamed from `registry-list` per
rf2-4y595 for NAMING.md `list-<things>` conformance), the write pair
`restore-epoch` + `reset-frame-db` (rf2-ee38b.18 — the Tool-Pair
time-travel + state-injection primitives, gated behind `--allow-writes`),
and `dispatch-dry-run` (rf2-17hvp — simulate a cascade without
committing) live in the live registry at
[`src/re_frame2_pair_mcp/tools/registry.cljs`](../src/re_frame2_pair_mcp/tools/registry.cljs)
and have full per-tool sections here.

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
the shape is shared across re-frame2-pair-mcp and story-mcp.

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
per-frame ring buffer, every `register-epoch-listener!` listener, and
the records re-frame2-pair-mcp egresses all see the same redacted shape.
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
the fn erases the leaves it keyed on — `--allow-sensitive-reads OFF`
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
  for `(re-frame2-pair.runtime/app-db-hash frame)` — an O(1)
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

## Universal: `:reason` keyword vocabulary (`:ok? false` responses)

Every `{:ok? false ...}` response carries a `:reason` keyword. The
catalogue uses **three deliberate namespacing dialects** — they look
similar at a glance but signal different categories of failure. An
agent host pattern-matching on `:reason` should treat the dialect as
load-bearing: each carries different recovery semantics.

| Dialect       | Meaning                                                            | Example reasons                                                                              |
|---------------|--------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| **Bare**      | Per-call validation / runtime failure (the normal tool body ran)   | `:invalid-kind`, `:missing-path`, `:not-an-event-vector`, `:path-not-found`, `:unknown-tool`, `:runtime-not-preloaded`, `:nrepl-unreachable`, `:build-not-running`, `:no-runtime-connected`, `:runtime-loaded-but-preload-missing`, `:eval-error`, `:timed-out`, `:probe-errored`, `:<verb>-failed` (e.g. `:snapshot-failed`, `:dispatch-failed`) |
| `:rf.error/*` | Operator-gated denial OR shared cross-MCP error vocabulary — the server refused **without touching nREPL** because a boot-flag / resource cap rejected the call before the tool body ran, OR the call ran but failed in a way that warrants the shared cross-MCP error vocabulary (rf2-xn4f9: `:rf.error/eval-cljs-rejected` + `:rf.error/eval-cljs-timeout` are bare-shaped per-call failures but adopt the namespace so an agent host can pattern-match the eval-cljs error cluster as one family) | `:rf.error/eval-cljs-disabled`, `:rf.error/eval-cljs-rejected`, `:rf.error/eval-cljs-timeout`, `:rf.error/concurrent-stream-limit`, `:rf.error/stream-abuse-detected` |
| `:rf.mcp/*`   | Wire-replacement-marker family (otherwise reserved for substitution markers like `:rf.mcp/overflow`, `:rf.mcp/dedup-table`, `:rf.mcp/cache-hit`, `:rf.mcp/summary`, `:rf.mcp/diff-from`). One carve-out as a `:reason` value: `:rf.mcp/cursor-stale` — cursor-staleness is detected at the wire boundary itself (the cursor envelope), not via tool body or boot gate, so it shares the `:rf.mcp/*` prefix with the rest of the wire-boundary vocabulary | `:rf.mcp/cursor-stale` (the only `:rf.mcp/*` `:reason` value) |

### Rationale for the split

**Why `:rf.error/*` and not bare for operator-gated denials?** The
`:rf.error/*` namespace carries a distinct recovery shape: the operator
must change a server-launch flag (`--allow-sensitive-reads`,
`--allow-writes`, or remove a `--no-eval` opt-out) or raise an integer
cap (`--max-concurrent-streams`) before the call will succeed. Bare
reasons are recoverable by adjusting per-call args; `:rf.error/*`
reasons are recoverable only by adjusting server configuration. An
agent host that sees `:rf.error/eval-cljs-disabled` knows to surface a
setup hint ("ask the operator to relaunch without `--no-eval`") rather
than retry with different args.

**Why `:rf.mcp/cursor-stale` and not `:rf.error/cursor-stale`?**
`:rf.mcp/*` is the wire-vocabulary family — every other `:rf.mcp/*`
keyword (`:rf.mcp/overflow`, `:rf.mcp/dedup-table`, `:rf.mcp/cache-hit`,
`:rf.mcp/summary`, `:rf.mcp/diff-from`) is a substitution marker that
replaces a normal payload at the wire boundary. Cursor-staleness is
detected at the same boundary (the cursor envelope, before the tool
body runs) and shares the wire-vocabulary lineage even though it
appears in the `:reason` slot rather than as a payload substitution.
Migrating it to `:rf.error/*` would break the cross-MCP recognition
("everything `:rf.mcp/*` is wire-boundary vocabulary, regardless of
which slot it lives in") that an agent host learns once on a sibling
server.

**Why bare for per-call validation?** Short, readable, no namespace
ceremony for the common case. Bare reasons dominate the surface
(~32 distinct keywords) because per-call validation failures are
the common path; the dialect cost would tax the common case.

### Recognition rules for agent hosts

- A bare reason ⇒ try different args (path, frame, event vector, …).
- A `:rf.error/*` reason ⇒ surface a server-config hint; do not retry
  with different args.
- `:rf.mcp/cursor-stale` ⇒ drop the cursor; restart pagination from
  the head (same as cross-MCP cursor-staleness on story-mcp).

## discover-app

Verify the shadow-cljs nREPL is reachable, confirm the
`re-frame2-pair.runtime` namespace was loaded by the consumer's
shadow-cljs `:devtools :preloads`, and return a health summary. Run
first every session.

**Args**: `build` (string, optional, default `"app"`).

**Returns**: an `:ok? true` map with `:debug-enabled?`, `:frames`,
`:coord-annotation-enabled?`, `:build-id`. On success the resolved
build-id is cached on the conn-atom (`:resolved-build-id`, rf2-l9ixp);
subsequent tool calls may omit the `:build` arg — see
[`API.md` §Build-id resolution](./API.md#build-id-resolution).

On a precondition failure the response is `:ok? false` with a
`:reason` keyword. The runtime ships into the app via shadow-cljs
`:preloads`; the server probes
`js/globalThis.__re_frame2_pair_runtime` (the load-time mirror the
preload installs). When the marker is missing the failure-path
diagnostic ladder (rf2-7tgfk; impl:
[`src/re_frame2_pair_mcp/tools/probe.cljs`](../src/re_frame2_pair_mcp/tools/probe.cljs)
`diagnose-preload-failure!`, lines 181-271) walks four rungs to name
the underlying cause rather than always blaming the preload:

### Failure-path diagnostic ladder (rf2-7tgfk)

| `:reason` | Fires when | Hint shape |
|---|---|---|
| `:nrepl-unreachable` | JVM `jvm-eval` round-trip fails — the nREPL socket is dead even though the MCP server is up. Most often: the shadow-cljs JVM stopped, or restarted and left the MCP server holding a stale socket. (probe.cljs lines 207-210) | "Restart `shadow-cljs watch` and retry; the MCP server reconnects on the next tool call." |
| `:build-not-running` | nREPL is reachable but shadow's `active-builds` doesn't include the targeted build. Carries `:running-builds` enumerating what IS up. Almost always a `--build` typo or operator targeted the wrong dev build. (probe.cljs lines 215-219) | "shadow-cljs is running `[<other-build>]` but not `<target>`. Pass `--build=<other-build>` (or set `SHADOW_CLJS_BUILD_ID`)…" |
| `:no-runtime-connected` | Build IS running but the cljs-eval round-trip returns blank — no browser tab has connected, or the tab's WebSocket has dropped. Carries `:running-builds`. (probe.cljs lines 233-237) | "build `<id>` is running but no CLJS runtime is currently connected… Open the app in a browser tab — or if a tab IS open, reload the page so the runtime reconnects." |
| `:runtime-loaded-but-preload-missing` | A CLJS runtime is alive but the `__re_frame2_pair_runtime` marker is absent. The original meaning of the legacy `:runtime-not-preloaded` reason — the preload entry IS what to add. (probe.cljs lines 242-245) | "re-frame2-pair.runtime is not loaded into this build. Add the preload entry to your shadow-cljs.edn… See skills/re-frame2-pair/SKILL.md (§Setup)." |

Each rung carries `:build` (the targeted id) plus a targeted
`:hint`; `:build-not-running` and `:no-runtime-connected` also carry
`:running-builds` so the operator's next move is one keystroke
away.

The ladder costs one extra JVM round-trip (active-builds
enumeration) plus a CLJS-eval discriminator on the failure path —
~50ms total. The probe cache (rf2-sjpx0) means a healthy session pays
nothing.

### Fallback: `:runtime-not-preloaded`

When the ladder itself errors (e.g. a transient nREPL failure mid-
diagnosis), the response degrades to the original blanket
`:reason :runtime-not-preloaded` with the generic preload hint
(probe.cljs lines 266-271). Reserved as the degradation case; the
ladder's four named reasons cover the common path.

There is no fallback inject path; see the skill's SKILL.md §Setup for
the two-line preload entry.

## Universal: server launch flags

Three boot gates control authority surfaces. Two are default-OFF
opt-ins (`--allow-sensitive-reads`, `--allow-writes`); one is a
default-ON opt-out (`--no-eval`). Operators pass them as MCP-server
CLI flags:

| Flag                      | Default       | Effect when set |
|---------------------------|---------------|------------------|
| `--no-eval`               | absent (eval-cljs ON) | Disables `eval-cljs` (rf2-a0z0h; inverts the prior rf2-cxx5s default-OFF posture). Default is eval-cljs ENABLED — it is the REPL primitive of a pair-debug session. With this flag, `eval-cljs` returns `{:ok? false :reason :rf.error/eval-cljs-disabled}` without touching the nREPL socket. |
| `--allow-sensitive-reads` | OFF           | Honours caller-supplied `:include-sensitive true` and `:elision false` on direct-read tools (`snapshot`, `get-path`, `subscribe`, `trace-window`, `watch-epochs`). Also signals the preload runtime to ship verbatim payloads through `app-db-reset!`'s `tap>` emission. Canonical cross-MCP flag name shared with story-mcp (rf2-2x3ql). |
| `--allow-writes`          | OFF           | Enables the state-mutating tools `restore-epoch` (time-travel undo) and `reset-frame-db` (state injection). Without the flag, both return `{:ok? false :reason :rf.error/writes-disabled}` without touching the nREPL socket. `dispatch` (which drives the application's own handlers) is unaffected. The descriptors still appear in `tools/list`; the gate is enforced at `tools/call` time. Note: this gate protects the named-write audit trail; it does NOT defend against eval-driven writes (eval-cljs can express the same writes), so for a true read-only posture compose with `--no-eval`. |

When `--allow-sensitive-reads` is OFF (the published-build default), the
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
   `(re-frame2-pair.runtime/configure-raw-state! {:allow-raw-state? false})`
   once per build per server lifetime. The runtime's `app-db-reset!`
   then wraps both `:previous` and `:next` slots in the `tap>` payload
   through `re-frame.core/elide-wire-value` — the same redaction the
   wire path applies — so any registered tap consumer sees the
   pre-redacted shape rather than the raw state.

Operators who need raw state for offline debug opt in at server launch
by passing `--allow-sensitive-reads`. The per-call args then win again
(`:include-sensitive true` / `:elision false` pass through to the
walker unchanged).

Symmetric with story-mcp's `--allow-sensitive-reads` (rf2-uaymx /
rf2-g9fje). The same canonical flag name across MCP servers (rf2-2x3ql)
gives operators one posture vocabulary.

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
| max-concurrent-streams       | 10      | `--max-concurrent-streams=N`      | `RE_FRAME2_PAIR_MCP_MAX_STREAMS`                 |
| max-events-per-sec           | 100     | `--max-events-per-sec=N`          | `RE_FRAME2_PAIR_MCP_MAX_EVENTS_PER_SEC`          |
| abuse-overflow-threshold     | 50      | `--abuse-overflow-threshold=N`    | `RE_FRAME2_PAIR_MCP_ABUSE_OVERFLOW_THRESHOLD`    |
| abuse-window-ms              | 10000   | `--abuse-window-ms=N`             | `RE_FRAME2_PAIR_MCP_ABUSE_WINDOW_MS`             |

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

**Args**:

| Arg          | Type     | Required | Default | Notes |
|--------------|----------|----------|---------|-------|
| `form`       | string   | yes      | —       | The CLJS form to evaluate. |
| `build`      | string   | no       | env / `:app` | shadow-cljs build id. |
| `frame`      | string   | no       | ambient (`:rf/default`) | rf2-ntuzf: operating frame for the form's lexical scope. Wraps the form in `(re-frame.core/with-frame <frame> <form>)` so `(rf/subscribe ...)` / `(rf/dispatch ...)` inside the form resolve against the named frame. Joins the family of frame-aware ops (`dispatch`, `snapshot`, `get-path`, …). |
| `await`      | boolean  | no       | `false` | Opt-in (rf2-xn4f9): if the form returns a thenable, await it server-side instead of `pr-str`'ing the Promise object. |
| `timeout-ms` | integer  | no       | `5000`  | Maximum ms to wait when `:await true`. Ignored when `:await false`. Caller-controlled because async form costs vary widely. |

### Frame targeting (rf2-ntuzf)

Every other structured op (`dispatch`, `snapshot`, `get-path`,
`trace-window`, `watch-epochs`, `subscribe`, `reset-frame-db`) accepts
a `:frame` arg that targets a named frame. Pre-rf2-ntuzf `eval-cljs`
did NOT — its form ran against the MCP server's ambient frame
context (`:rf/default`), so `(rf/subscribe ...)` / `(rf/dispatch ...)`
inside the supplied form silently targeted `:rf/default` even in a
multi-frame app. The workaround was wrapping the form by hand
(`(re-frame.core/with-frame :rf/xray (rf/subscribe ...))`), easy to
forget; when forgotten the probe read the wrong frame's state and
reported wrong data.

The `:frame` arg closes that footgun. The server wraps the supplied
form in `(re-frame.core/with-frame <frame> <user-form>)` before
sending it over nREPL. `with-frame` is the framework's lexical frame-
binding macro (Spec 002 §with-frame); `*current-frame*` is bound to
the named frame for the form's dynamic extent, so any
`(rf/subscribe ...)` / `(rf/dispatch ...)` / `(rf/current-frame)`
inside the form resolves against the requested frame.

**Composes with `:await`.** The `with-frame` wrap is the outer-most
form; the await mailbox sentinel rides through the wrap unchanged.
Note that `with-frame`'s lexical binding only lasts for the form's
SYNCHRONOUS evaluation — once a Promise resolves on a later tick, the
binding is gone (Spec 002 §with-frame: async closures must capture via
`bound-fn` / `dispatcher` / `subscriber`). Most ad-hoc probes don't
hit this; long-running async forms that need to dispatch in a `.then`
callback should capture the frame explicitly.

**Returns**: success envelope additionally carries `:frame <frame-kw>`
when `:frame` was supplied, mirroring how `dispatch` echoes its
frame-id.

**Launch-flag gate**: `--no-eval` (rf2-a0z0h; inverts the prior
rf2-cxx5s default-OFF posture). Default is eval-cljs ENABLED — it is
the REPL primitive of a pair-debug session. With the `--no-eval`
opt-out, calls return `{:ok? false :reason :rf.error/eval-cljs-disabled ...}`
without touching the nREPL socket. See §Universal: server launch flags.

**Returns**: `{:ok? true :value <edn-value>}` on success;
`{:ok? false :reason :eval-error :message "..."}` on failure.
`:reason :runtime-not-preloaded` if the runtime preload hasn't run.

### Promise-awaiting (rf2-xn4f9)

`shadow.cljs.devtools.api/cljs-eval` captures the form's **synchronous**
return value and `pr-str`'s it. When the form returns a JS Promise —
the synchronous return IS the Promise object, and `pr-str` produces
`"#object[Promise [object Promise]]"`: a string saying "I'm a Promise"
with no access to the eventually-resolved value. The historical
workaround was a two-call mailbox dance — stash on `js/window`, return
a sentinel, read the global on a second call, poll until resolved.

When the caller passes `:await true`, the server automates that dance.
The form is wrapped browser-side; thenable returns wire up a mailbox
on `js/globalThis.__rf2pair_await__`, the wrapper records the resolved
value (or rejection reason) into the mailbox, and the server polls the
mailbox until the status flips off `:pending` or `:timeout-ms` elapses.
Non-thenable returns pass through unchanged on the wrapper's
synchronous arm — zero round-trips beyond today's behaviour.

**Behaviour table (`:await true`):**

| Form returns                        | Wire result |
|-------------------------------------|-------------|
| Non-thenable value `v`              | `{:ok? true :value v :build <id>}` (identical to `:await false`) |
| Thenable that resolves to `v`       | `{:ok? true :value v :build <id>}` |
| Thenable that rejects with `e`      | `{:ok? false :reason :rf.error/eval-cljs-rejected :rejection "<pr-str of e>" :build <id>}` |
| Thenable that doesn't settle within `:timeout-ms` | `{:ok? false :reason :rf.error/eval-cljs-timeout :timeout-ms n :build <id>}` |

**Why opt-in (not always-on).** Two principled reasons (the
back-compat argument doesn't bind in pre-alpha):

  - **Promise pass-through is sometimes intentional.** Some forms
    deliberately return a Promise object to hand off to other code;
    auto-awaiting would change the contract for those callers.
  - **Timeout policy should be caller-controlled.** The caller knows
    whether their async work is a 5ms `Promise.resolve` or a multi-
    second layout computation; the server shouldn't pick.

The default `:await false` preserves today's semantics — the form's
synchronous return is `pr-str`'d and returned verbatim. Callers
who DO want to wait on a Promise opt in explicitly and pick the
deadline.

**Reserved reason keywords introduced by this surface:**

  - `:rf.error/eval-cljs-rejected` — the awaited Promise rejected;
    `:rejection` carries the `pr-str` of the rejection value
    (preserving any data carried on an `ex-info`, etc.).
  - `:rf.error/eval-cljs-timeout` — the awaited Promise didn't settle
    within `:timeout-ms`; `:timeout-ms` echoes the deadline that
    expired. The server fires a best-effort discard of the orphaned
    mailbox slot so a late resolution doesn't accumulate on
    `js/globalThis`.
  - `:rf.error/eval-cljs-mailbox-missing` — defensive: the wrapper
    installed a mailbox but the poll found nothing there. Indicates a
    wire-shape regression or a page reload destroying the mailbox
    between the wrap and the first poll. Should not occur in practice.
  - `:rf.error/eval-cljs-await-wrap-failed` — defensive: the wrapper's
    synchronous return wasn't one of the two expected sentinels.
    Indicates a regression in the wrap-form emitter. Should not occur
    in practice.

## Universal: cascade summary on state-mutating tools (rf2-6yqdl)

`dispatch`, `reset-frame-db`, and `restore-epoch` each surface a
`:cascade-summary` slot in their success response — a compact
projection of the assembled `:rf/epoch-record` answering the universal
"what did my call just do?" question in one round-trip, with no
follow-on `watch-epochs` / `trace-window` correlation needed.

The shape MIRRORS the assembled-epoch projection that
`register-epoch-listener!` consumers already know (Spec 009 §Epoch
records) — operators familiar with `watch-epochs` see the same
vocabulary in dispatch responses. The projection is intentionally
LOSSY (counts instead of full vectors, depth-1 path summary instead of
the raw `:db-before`/`:db-after` pair) so the cascade-summary rides
under the 5K-token wire-cap without further elision. Operators who
want the full epoch read `(rf/epoch-history)` or run `trace-window` /
`watch-epochs` for the same id.

### Slot inventory

Every slot is optional — absent when the underlying signal is empty.
For example, synthetic `:rf.epoch/db-replaced` epochs from
`reset-frame-db` carry no `:event-vector` (no dispatch happened); a
restore's `:cascade-summary` carries an additional `:restore? true`
marker so consumers can branch.

| Slot                  | Type                              | Notes |
|-----------------------|-----------------------------------|-------|
| `:epoch-id`           | `:any` (integer in ref runtime)   | The assembled epoch's id. |
| `:event-id`           | keyword                           | The triggering event-id. Absent on synthetic / halted-trigger-less paths. |
| `:event-vector`       | vector                            | The original dispatch vector. Absent on synthetic paths. |
| `:frame`              | keyword                           | The frame-id the cascade settled in. |
| `:outcome`            | `:ok` / `:blocked` / `:error`     | The consumer-facing tier per `outcome->consumer-facing`. |
| `:db-diff`            | `{:changed-paths [...] :added-paths [...] :removed-paths [...]}` | Depth-1 path summary of the db delta. Each path is a one-key vector (e.g. `[:cart]`); drill in via `get-path`. |
| `:fx-fired`           | vector of fx-ids                  | Distinct fx-ids fired this cascade. Duplicates collapsed. |
| `:subs-recomputed`    | integer                           | Count of unique sub-runs in this cascade. |
| `:renders`            | integer                           | Count of render emits in this cascade. |
| `:machine-transitions`| vector of `{:machine-id :from :to :phase}` | Absent when no machine activity. |
| `:elapsed-ms`         | number                            | Wall-clock elapsed-ms from `:rf.event/run-start` to `:rf.event/run-end`. |
| `:sensitive?`         | `true`                            | Present only when the epoch's `:rf.epoch/sensitive?` rollup is true. Consumers branch on absent-slot patterns in `:db-diff`. |
| `:restore?`           | `true`                            | Present only on `restore-epoch` responses. Signals the summary projects the TARGET epoch, with `:db-diff` computed from the pre-restore live db. |

### `:unreplayable-effects` (restore-epoch only)

A successful `restore-epoch` additionally surfaces an
`:unreplayable-effects` vector — every fx the ORIGINAL cascade fired
that the restore cannot undo (http requests already sent, navigation
already pushed, storage already written). Programmers reading "I just
rewound" need to know which side-effects already escaped the framework.

```clojure
{:ok? true :restored? true :epoch-id 7
 :cascade-summary {... :restore? true}
 :unreplayable-effects [{:fx-id :http :coord [:my.app.cart 87 4]}
                        {:fx-id :navigate}]}
```

### Pending cascades (queued dispatch)

Queued `dispatch` (the default `sync? false` `trace? false` mode) may
return BEFORE the cascade drains — the goog.async tick fires later.
When the operating frame's epoch-history head did NOT advance during
the call, the response carries `:cascade-summary-pending? true` and
`:before-epoch-id <prior-head>` instead of `:cascade-summary`. Pollers
read `watch-epochs {:since-id <before-epoch-id>}` for the eventual
settlement. Sync / trace modes never see this — `dispatch-sync` drains
in line.

### Production builds

The entire epoch-record path elides under
`re-frame.interop/debug-enabled? false`; cascade-summary inherits that
elision by construction. Production deploys see neither cascade nor
the trace stream — the dev/prod boundary is the same as `watch-epochs`.

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

**Returns**: the runtime's response, merged with `:mode`. Per rf2-6yqdl
every successful dispatch surfaces a `:cascade-summary` slot — see
§Universal: cascade summary on state-mutating tools above for the
shape and the `:cascade-summary-pending?` behaviour on queued mode.
Trace mode additionally carries the full `:epoch` (the verbatim
assembled record) alongside the compact summary.

## dispatch-dry-run

Simulate a re-frame2 cascade WITHOUT committing it (rf2-17hvp). Full
reducer + interceptor chain runs, schema validation fires, machine
transitions simulate, sub-runs and renders are recorded — but NO fx
execute (every registered fx is redirected to a recording stub via
`:fx-overrides`) and the framework rolls back the app-db via
`restore-epoch`. The fundamental "experiment without consequences"
primitive: every fx the cascade WOULD have fired is enumerated in
`:would-fire-effects` (with its args), so the operator reasons about
real-world impact without paying it.

### Why this is NOT `--allow-writes`-gated

Dry-run's contract IS "no observable effect". The fx-override set
redirects every registered fx to a recording stub, so no http /
navigation / persisted-write side-effect escapes. The framework's
`restore-epoch` rewinds the app-db and trims the assembled would-be
epoch from the ring. There is no state change for the
`--allow-writes` gate to protect against; pairing dry-run behind that
gate would force the operator to opt INTO writes to experiment with
NOT writing, which inverts the gate's intent.

### How it works (no framework hack required)

The framework's existing `:fx-overrides` seam (Spec 002 §Per-frame and
per-call overrides) and the existing `restore-epoch` primitive
(Tool-Pair §Time-travel) compose into a true dry-run with no internal
framework entry point needed:

1. Snapshot the head epoch-id (the rollback target).
2. Build an `:fx-overrides` map redirecting every registered fx to a
   recording fn-value stub. Each stub captures its own original
   fx-id, appends `{:fx-id ... :args ...}` to a recording atom, and
   returns nil — short-circuiting the would-be side-effect.
3. `dispatch-sync` — the reducer + interceptor chain run normally
   (schema validation, machine-step machinery, sub re-evaluation, all
   live here); the cascade ASSEMBLES a real epoch on the ring.
4. Read the new head epoch — this IS the cascade-summary source.
5. `restore-epoch` back to the pre-call head. The framework's
   canonical undo gesture rewinds db and trims the would-be epoch
   from history.

The recorded fx calls AND the would-be epoch's cascade-summary
project together into the response shape.

### Edge cases

- **`:dispatch` / `:dispatch-later`** are caught by the override and
  recorded; the recursive dispatch never happens. This matches the
  bead's `:max-effect-chain-depth 1` default: simulate this event's
  reducer + its direct fx + LIST what those fx would dispatch (don't
  simulate that next level).
- **Schema violation** — the reducer's schema check fires the same
  way; the epoch settles with the violation in `:trace-events`,
  cascade-summary surfaces it via `:outcome :error`.
- **Machine transitions** — the machine-step machinery runs (pure
  data per Spec 005); transitions appear in cascade-summary's
  `:machine-transitions` slot. Machine-fired fx (timer schedules,
  spawn/destroy) are stubbed.
- **Frame mismatch** — the runtime fails with `:reason
  :ambiguous-frame` before the override set is built; no rollback
  needed.
- **Listener fan-out** — `register-listener!` / `register-epoch-
  listener!` consumers DO see the would-be epoch land between step 3
  and step 5. This is a documented limitation: the framework has no
  "private dispatch" primitive. Production builds elide the entire
  listener path anyway; dev-tier listeners observing a phantom epoch
  is acceptable in exchange for the simpler composition. A follow-on
  bead can elevate this to a first-class framework primitive once
  the cost is justified.

### Composes with `:fx-overrides`

The caller MAY pass an `:fx-overrides` map that PRE-stubs some fx
(e.g. redirecting `:rf.http/managed` to a canned stub-handler for the
experiment). User-supplied overrides win on conflict — the recorder
fires only for fx the caller did NOT pre-stub. This lets the
experimenter compose realistic conditions ("what would happen if the
http call resolved to this response?") without losing the dry-run's
roll-back guarantee.

**Args**: `event` (string, required — EDN-encoded event vector),
`frame` (string, e.g. `":foo"`; defaults to the operating frame),
`fx-overrides` (object — user-supplied overrides composed on top of
the dry-run recorder set), `build` (string).

**Returns** (success):

```clojure
{:ok?                       true
 :dry-run?                  true
 :rolled-back?              true
 :event                     [:cart/checkout]
 :frame                     :rf/default
 :before-epoch-id           42
 :cascade-summary           {... per §Universal: cascade summary ...}
 :would-fire-effects        [{:fx-id :http :args {:url ...}}
                             {:fx-id :navigate :args [:order-confirmation]}]
 :db-state-after-simulation {...}}
```

The cascade-summary slot uses the same shape as `dispatch` /
`restore-epoch` / `reset-frame-db` (rf2-6yqdl); operators read one
vocabulary across all four. `:db-state-after-simulation` is the would-
be `:db-after` of the rolled-back epoch — surfaced verbatim (subject
to the normal size-elision / sensitive-paths pipeline at the wire
boundary) so the operator can inspect what the post-dispatch db
WOULD have been without re-running through `snapshot`.

**Returns** (failure):

- `:reason :no-epoch-recorded` — epoch-history empty / frame
  unregistered / `interop/debug-enabled?` false. No rollback needed.
- `:reason :no-new-epoch` — `dispatch-sync` returned but the head did
  not advance (the reducer rejected the event or an interceptor
  early-returned). No rollback needed.

The arg-parse failure modes (`:missing-event`, `:invalid-event-edn`,
`:not-an-event-vector`) mirror `dispatch` exactly — the EDN-data
posture (rf2-vflrg) is the same security gate.

## restore-epoch

Time-travel undo — rewind a frame's `app-db` to a recorded prior
epoch. The canonical pair-tool undo gesture per
[`Tool-Pair.md` §Time-travel](../../../spec/Tool-Pair.md#time-travel);
wraps the `restore-epoch` Tool-Pair write primitive
(`(rf/restore-epoch frame-id epoch-id)`). Walk the ring with
`trace-window` / `snapshot` (`:epochs` slice) to pick a target
`:epoch-id`, then rewind to it.

**Launch-flag gate (rf2-ee38b.18)**: `--allow-writes`. Default OFF;
calls return `{:ok? false :reason :rf.error/writes-disabled}` without
touching the nREPL socket. A write surface — replacing `app-db`
wholesale is qualitatively more powerful than `dispatch` (which drives
the app's own handlers).

**`epoch-id` is `:any`**: parsed as EDN, NOT assumed `string?`. The
reference epoch runtime emits **integer** epoch-ids (Spec-Schemas
declares `:epoch-id` as `:any`), so an integer id `7` is passed as the
string `"7"` and reads back as the number 7. The same `:any` contract
drives the cursor-pagination fix.

**Args**: `epoch-id` (string, required — EDN id), `frame` (string,
e.g. `":foo"`; defaults to the operating frame), `build` (string).

**Returns**: `{:ok? true :restored? true :epoch-id <id> :frame <id>
:cascade-summary {... :restore? true} :unreplayable-effects [...]}`
on success — per rf2-6yqdl the cascade-summary projects the TARGET
epoch with `:db-diff` computed from the pre-restore live db; the
`:unreplayable-effects` vector enumerates fx the original cascade
fired that the restore cannot undo. See §Universal: cascade summary
on state-mutating tools for the shape. Returns
`{:ok? false :restored? false :reason :restore-rejected ...}` when
the id is not in the ring or a drain is in flight (the documented
`:rf.epoch/*` failure modes per
[`Tool-Pair.md` §Restore failure modes](../../../spec/Tool-Pair.md#time-travel)).
The `app-db` is unchanged on failure.

## reset-frame-db

State injection — replace a frame's `app-db` with an arbitrary EDN
value the runtime never recorded; the explicit JSON-loaded-bug-repro
case per
[`Tool-Pair.md` §Pair-tool writes](../../../spec/Tool-Pair.md#pair-tool-writes--state-injection).
Wraps the `reset-frame-db!` Tool-Pair write primitive
(`(rf/reset-frame-db! frame-id new-db)`): bypasses the dispatch loop,
replaces the container directly, and records a synthetic
`:rf/epoch-record` (`:event-id :rf.epoch/db-replaced`) so a later
`restore-epoch` can rewind past the injection.

**Launch-flag gate (rf2-ee38b.18)**: `--allow-writes` (the same gate as
`restore-epoch`). Default OFF.

**`db` is EDN data, not host source**: parsed as EDN and emitted into
the runtime call via the normal `pr-str` path (no `rt-raw` splice) —
the same injection-closing posture `dispatch` takes (rf2-vflrg). A
prompt-injected `(println :pwn)` string reads as a list literal (data),
never executed.

**Args**: `db` (string, required — EDN app-db value), `frame` (string,
e.g. `":foo"`; defaults to the operating frame), `build` (string).

**Returns**: the runtime's `app-db-reset!` envelope —
`{:ok? true :frame <id> :epoch-id <synthetic-id> :cascade-summary
{:event-id :rf.epoch/db-replaced :db-diff {...} :fx-fired [] ...}}`
on success. Per rf2-6yqdl the cascade-summary projects the synthetic
`:rf.epoch/db-replaced` epoch the framework just recorded (Tool-Pair
§Pair-tool writes); `:fx-fired` is empty because state injection
bypasses the dispatch loop entirely. See §Universal: cascade summary
on state-mutating tools for the full shape. Returns `{:ok? false
:reason :reset-rejected ...}` on no-such-frame / drain-in-flight /
app-schema mismatch (the documented `:rf.epoch/*` failure modes). The
`app-db` is unchanged on failure.

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

When the window surfaces zero matches but the per-frame epoch-history
is non-empty, the envelope additionally carries an `:advisory` slot —
see §Empty-result advisory below.

### Empty-result advisory (rf2-fb4hn)

When the response would carry `:count 0` but the operating frame's
per-frame epoch-history is non-empty, the envelope carries an
`:advisory` slot distinguishing "nothing happened" from "events exist
but fell outside the time window" (impl:
[`src/re_frame2_pair_mcp/tools/trace_window.cljs`](../src/re_frame2_pair_mcp/tools/trace_window.cljs)
lines 130-142):

```clojure
{:advisory {:reason            :window-excludes-history
            :frame             <frame-id>
            :epochs-in-history N
            :window-ms         N
            :hint              "N epochs exist in the per-frame history
                                but none fell inside the last <window>ms
                                window. Widen :ms (e.g. 60000), or use
                                `snapshot :include [:epochs]` to inspect
                                the full history."}}
```

The advisory fires when **both** ratchets hold: `:count` is zero (the
slot operators read to decide "nothing happened") and the per-frame
ring depth is positive (events exist). Without it the operator
routinely misread an empty window as "the MCP isn't capturing my
events" — the 2026-05-25 pair-debug session referenced in PR #2120
named the misread cost. With it, the operator either widens `:ms` or
pivots to `snapshot :include [:epochs]` for unbounded historical
inspection.

The slot is omitted when matches exist or when the frame's history is
genuinely empty (a fresh runtime with no dispatches). Agents
pattern-match presence to decide whether the empty result needs a
follow-up call.

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
cascade's elapsed-ms (derived from the `:rf.event/run-start` /
`:rf.event/run-end` trace pair on `:time`; spans first run-start to last
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

When the call surfaces zero matches but the per-frame epoch-history is
non-empty, the envelope additionally carries an `:advisory` slot —
see §Empty-result advisory below.

Each match has its `:db-after` diff-encoded against its own
`:db-before` by default (rf2-1wdzp); pass `epochs-mode "full"` for
the legacy full-pair shape. See `trace-window` above for the wire
shape and rationale.

### Empty-result advisory (rf2-fb4hn)

When the response would carry `:count 0` but the operating frame's
per-frame epoch-history is non-empty, the envelope carries an
`:advisory` slot distinguishing two distinct empty-result causes
(impl: [`src/re_frame2_pair_mcp/tools/watch_epochs.cljs`](../src/re_frame2_pair_mcp/tools/watch_epochs.cljs)
lines 139-165):

**Case A — `:no-events-since-id`** (zero `:count`, zero new epochs
since `:since-id`, non-empty history). The caller's `:since-id` sits
at or past the head; no events have landed since.

```clojure
{:advisory {:reason            :no-events-since-id
            :frame             <frame-id>
            :epochs-in-history N
            :requested-id      <since-id>
            :hint              "Per-frame history holds N epochs but
                                none have landed since the supplied
                                :since-id. Dispatch an event to advance
                                the head, or omit :since-id to see the
                                full ring."}}
```

**Case B — `:pred-excludes-history`** (zero `:count`, positive new
epochs since `:since-id`). Events landed but `:pred` filtered them
all out.

```clojure
{:advisory {:reason            :pred-excludes-history
            :frame             <frame-id>
            :epochs-in-history N
            :epochs-since-id   N
            :hint              "N epochs landed since the requested id
                                but the :pred filter excluded all of
                                them. Drop / widen :pred, or use
                                trace-window for an unfiltered view."}}
```

The two cases share the impl's pattern: ratchet on `:count 0` first,
then pick the explainer from runtime-side `:since-count` /
`:history-count` counts so the tool body picks the right advisory from
one nREPL round-trip. The advisory is omitted when matches exist or
when the frame's history is genuinely empty. Agents pattern-match on
`:reason` inside the advisory to choose the next step — restart with
no `:since-id`, widen the `:pred`, or pivot to `trace-window` for an
unfiltered view.

## tail-build

Wait for a hot-reload to land by polling a probe form until its
value changes from its pre-call value. Times out after `wait-ms`.

**Args**: `probe` (string — a CLJS form whose value should change
after the reload), `wait-ms` (integer, default 5000), `build` (string).

**Returns**: one of four envelope shapes (rf2-36awg; impl:
[`src/re_frame2_pair_mcp/tools/tail_build.cljs`](../src/re_frame2_pair_mcp/tools/tail_build.cljs)
lines 79-137):

1. **Success (probe supplied) — probe value changed within the
   deadline** (lines 117-123):

    ```clojure
    {:ok?          true
     :t            <ms>
     :soft?        false
     :probe-values {:initial <v>   ; pre-poll value of the probe form
                    :final   <v>}} ; final value at completion
    ```

   `:probe-values` confirms the comparison drove completion — callers
   read both ends rather than guessing which transition fired.

2. **Timeout (probe supplied) — value never differed from initial
   within `wait-ms`** (lines 105-111):

    ```clojure
    {:ok?          false
     :reason       :timed-out
     :timed-out?   true
     :probe-values {:initial <v>   ; pre-poll value of the probe form
                    :final   <v>}  ; last value the polling loop saw
     :note         "Probe value did not change within wait-ms. Possible
                    causes: (a) compile error in shadow stalled the
                    rebuild, (b) probe form returns the same value
                    before and after the reload, (c) probe form errored
                    — check :probe-values to disambiguate."}
    ```

   With both ends visible the operator distinguishes "compile didn't
   land" from "probe form returns the same value before and after"
   without an extra round-trip.

3. **Probe errored on initial evaluation** (lines 134-137):

    ```clojure
    {:ok?         false
     :reason      :probe-errored
     :probe-error "<stringified exception message>"
     :note        "Probe form raised an exception on every iteration.
                   The form is likely malformed."}
    ```

   Distinct from `:timed-out` — the probe form itself threw, so no
   before/after delta could be measured. Almost always a malformed
   probe (typo, dotted-form host interop against a missing var, etc.).

4. **No probe supplied — soft delay** (lines 79-84):

    ```clojure
    {:ok?   true
     :t     <ms>
     :soft? true
     :note  "No probe supplied; waited a 300ms fixed delay."}
    ```

   Matches the bash shim's behaviour. The `:probe-values` slot does
   NOT appear in this envelope; tests pin its absence.

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
the `get-path` tool below and as Xray-MCP's `:path` mechanism — one
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

| Topic       | What gets pushed                                                                                                |
|-------------|-----------------------------------------------------------------------------------------------------------------|
| `trace`     | Every raw trace event matching `filter` — grouped into cascade bundles by `:rf.trace/dispatch-id` (rf2-mscih).  |
| `epoch`     | Every assembled `:rf/epoch-record` matching `filter`. Already cascade-shaped by construction.                   |
| `fx`        | Sugar — `topic :trace` with base filter `{:op-type :rf.fx}`. Cascade-bundle delivery as for `:trace`.            |
| `error`     | Sugar — `topic :trace` with base filter `{:op-type :error}`. Cascade-bundle delivery as for `:trace`.            |
| `frameless` | Every trace event matching `filter` whose `:rf.trace/dispatch-id` tag is absent — registration emits, REPL evals, lifecycle outside any cascade (per [Tool-Pair.md §Frameless trace events — live channel only](../../../spec/Tool-Pair.md#frameless-trace-events--live-channel-only)). Single-event delivery. |

User-supplied filter keys win over the topic's base filter on conflict
— the topic is a default, not a lock. So `subscribe {:topic :fx
:filter {:op-type :info}}` actually streams `:info` traces (the user
filter wins). Don't do this — but the substrate doesn't refuse it.

### Cascade-bundle wire format (rf2-mscih)

On the cascade-bundle topics (`:trace`, `:fx`, `:error`) every
progress payload's `:cascades` slot is a vector of cascade bundles
keyed by `:dispatch-id`. Each bundle matches the framework's
`(rf/trace-buffer frame-id)` shape per [spec/009 §Cascade projection](../../../spec/009-Instrumentation.md#cascade-projection-group-cascades--domino-bucket)
and [Tool-Pair.md §Reading the per-frame trace ring](../../../spec/Tool-Pair.md#reading-the-per-frame-trace-ring--cascade-bundles--flat-opt-in):

```clojure
{:dispatch-id        <id>                  ; cascade id
 :frame              <frame-id or nil>
 :event              <event-vector or nil> ; from :rf.event/dispatched :tags
 :dispatched         <trace-event or nil>  ; full :rf.event/dispatched event
 :handler            <trace-event or nil>  ; :rf.event/run-end emit (last wins)
 :fx                 <trace-event or nil>  ; :rf.fx/do-fx
 :effects            [<trace-event> ...]   ; :op-type :rf.fx (other operations)
 :subs               [<trace-event> ...]   ; :rf.sub/run + :rf.sub/skip + :rf.sub/create
 :renders            [<trace-event> ...]   ; :rf.view/render
 :other              [<trace-event> ...]   ; everything else (errors, machine, …)
 :trace-events       [<trace-event> ...]   ; raw events for the cascade
 :parent-dispatch-id <id or nil>}          ; causal-parent link
```

One tick = one drain's worth of cascade bundles. A cascade is the
"atomic" delivery unit — consumers can reason about cause→effect at
the granularity of `:dispatch-id` without re-folding flat-event
streams (the pre-rf2-mscih posture).

Events with no `:rf.trace/dispatch-id` tag (registration emits, REPL
evals, lifecycle outside any cascade) NEVER ride the cascade-bundle
topics; the framework filters them at the dispatch gate. Consumers
that need them subscribe to `:frameless` explicitly.

The `:dropped-events` / `:dropped-bytes` / `:overflow-reason`
counters on the progress payload count the raw queued events that
were EVICTED by the byte+event budget — not cascades. A non-zero
`:dropped-events` means consumers reconstructing a cascade should
tolerate partially-truncated bundles (some of the cascade's
constituent events may have aged out).

### Cross-frame cascade reconstruction

A cascade can fan out across frames per [spec/002 §Cross-frame
dispatch](../../../spec/002-Frames.md). Every emit on every frame
shares the same `:rf.trace/dispatch-id`, so the runtime emits one
bundle per `(frame, dispatch-id)` pair per drain. Consumers that
watch multiple frames merge by `:dispatch-id` to reconstruct the
cross-frame view (per [Tool-Pair.md §Cross-frame cascade
reconstruction](../../../spec/Tool-Pair.md#cross-frame-cascade-reconstruction--merge-by-dispatch-id)).
In practice, each cascade lives in exactly one frame (re-frame2 does
not route a single dispatch across multiple frames per [Spec 002
§Routing](../../../spec/002-Frames.md#routing-the-dispatch-envelope)),
so the multi-frame view is interleaved rather than overlapping;
`dispatch-id` ordering renders the correct turn-by-turn timeline.

### Frameless channel

The `:frameless` topic delivers events whose `:rf.trace/dispatch-id`
tag is absent — registration / hot-reload / REPL emits that never
rode a dispatch cascade. The progress payload's load slot is
`:events` (flat); the cascade-bundle shape doesn't apply (there is
no cascade to bundle).

Frameless events bypass every ring per [Tool-Pair.md §Frameless
trace events](../../../spec/Tool-Pair.md#frameless-trace-events--live-channel-only)
— they stream live to listeners only. The `:frameless` topic is the
MCP-side surface for that live channel; consumers MUST opt in
explicitly per the framework's "separate channel" ruling
(rf2-g1b2m-B3).

### Cursor staleness on cascade-bundle streams

Streaming subscriptions are forward-only — there is no cursor to
become stale. The `:rf.mcp/cursor-stale` reason value applies to
the cursor-paginated tools (`trace-window`, `watch-epochs`) whose
cursors key on `:epoch-id` in `epoch-history`. The cascade-bundle
wire format reshape (rf2-mscih) does NOT introduce a new cursor
surface; it changes the unit of streaming delivery and the
per-tick payload slot.

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
| `:event-id`      | `(get-in ev [:tags :rf.trace/event-id])`                          |
| `:handler-id`    | `(get-in ev [:tags :handler-id])`                                 |
| `:source`        | `(:source ev)` or `(get-in ev [:tags :source])` — one of `:rf/dispatch-envelope`'s `:source` enum (`:ui` / `:after-timer` / `:http` / `:repl` / `:machine-action` / `:machine-spawn` / `:fx-dispatch` / `:fx-dispatch-later` / `:always` / `:frame-init` / `:ssr-hydration` / `:test` / `:unknown` / `:other`). See [Spec-Schemas §`:rf/dispatch-envelope`](../../../spec/Spec-Schemas.md#rfdispatch-envelope). |
| `:origin`        | `(get-in ev [:tags :rf.event/origin])` — `:app` / `:pair` / `:story` / `:test`. |
| `:dispatch-id`   | `(get-in ev [:tags :rf.trace/dispatch-id])`                       |
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
| `:origin`            | One of the `:rf.event/dispatched` traces has `(:tags :rf.event/origin)` = `origin`. |
| `:frame`             | `(= frame (:frame e))`                                        |
| `:timing-ms`         | Cascade elapsed-ms (first `:rf.event/run-start` → last `:rf.event/run-end` on `:time`) matches the threshold. Number `N` is sugar for `>= N`; strings `">N"` / `">=N"` / `"<N"` / `"<=N"` / `"=N"` set the comparator. Epochs with no derivable timing never match (rf2-r3azh). |

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
    "message": "{:sub-id \"...\" :cascades [...] :dropped-events 0 :dropped-bytes 0}",
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
same shape the runtime's `drain-subscription!` returns. The
payload-slot name reflects the topic's wire shape (rf2-mscih):

- `:cascades` — vector of cascade bundles, on cascade-bundle topics
  (`:trace` / `:fx` / `:error`). See §Cascade-bundle wire format above.
- `:events` — flat vector, on `:epoch` (one `:rf/epoch-record` per
  entry) and `:frameless` (one trace event per entry).

Capable MCP clients can also inspect the `_meta.data` slot for the
structured drop counts. `_meta` is used because the official MCP SDK
preserves it in progress callbacks while stripping unknown top-level
progress fields. `overflow-reason` carries the stringified EDN
keyword of the budget that tripped LAST (`":max-buffered-events"` or
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

When a stream seems quiet or stalled, the `list-subscriptions` tool
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

## list-subscriptions

Diagnostic listing of currently-registered streaming subscriptions —
the "what streams are open right now?" surface. Pure read over the
runtime's `subscriptions` atom; **does NOT drain any queues** and
does NOT alter the stream contents that `subscribe` will see on its
next tick. Wraps the `re-frame2-pair.runtime/subscription-info`
runtime fn directly (one cheap nREPL eval — no `eval-cljs`
round-trip needed; the runtime fn keeps its historical name).
Useful when a streaming probe seems to have gone quiet: confirm the
sub is still registered, inspect `:queue-depth` / `:queue-bytes` for
evidence of a stuck consumer, or check `:overflow-reason` for budget
pressure that needs tuning on the next `subscribe` call.

Unlike the other read tools, `list-subscriptions` reads the runtime's
internal subscription registry rather than routing through one of the
Tool-Pair primitives listed in the intro — its peer surface is the
streaming registry that `subscribe` / `unsubscribe` mutate, not the
frame-db / epoch-history / trace-buffer surfaces.

**Naming note**: renamed from `subscription-info` per rf2-4y595 —
NAMING.md catalogues `list-<things>` as the canonical enumeration
verb. No back-compat shim; the old name hard-errors with
`:unknown-tool`.

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
that applies. `list-subscriptions` does NOT carry sensitive event
bodies; only registration metadata crosses the wire.

`:reason :runtime-not-preloaded` if the preload hasn't run;
`:reason :list-subscriptions-failed` (with `:message`) on any other
failure.

## handler-meta

Return the registration-metadata map for a registered handler — the
"where is `:user/login` defined?" / "what does sub `:current-user` look
like?" surface (rf2-pctf8 / rf2-cibp8). Direct introspection on the
registrar; no wide-authority `eval-cljs` round-trip needed.

Source-coord keys (`:ns` / `:line` / `:column` / `:file`) are merged
flat onto the top-level result per
[`spec/Spec-Schemas.md` §`:rf/source-coord-meta`](../../../spec/Spec-Schemas.md#rfsource-coord-meta).
The wire pipeline (rf2-cibp8) decorates a usable source-coord shape
with an `:rf.source/uri` string so the AI host can render a clickable
jump-to-editor link.

**Args**: `kind` (string, **required** — one of `event` / `sub` /
`fx` / `cofx` / `view` / `frame` / `route` / `flow` / `head` /
`error-projector` / `machine`), `id` (string, **required** — EDN-encoded
keyword or composite vector), `build` (string, optional).

**Supported kinds**: the closed v1 registrar set (per Spec 001
§Registry model). App-db schemas are **not** a registrar kind
(rf2-cq1ak) — their metadata lives in the schemas artefact's per-frame
side-table, queried via `rf/app-schemas` / `rf/app-schema-meta-at`.
The ten registrar kinds route through
`(re-frame2-pair.runtime/registrar-describe kind id)`. The `:machine`
kind routes through `(re-frame.core/machine-meta id)` instead —
machines are registered as `:event` handlers carrying `:rf/machine?
true` (Spec 005 §Querying machines), and `machine-meta` unwraps that
slot to surface the spec.

**Returns** on a hit:

```clojure
{:ok?              true
 :kind             :event | :sub | :fx | :cofx | :view | :frame |
                   :route | :flow | :head | :error-projector | :machine
 :id               <registered-id>
 :ns               my.app.user
 :line             42
 :column           1
 :file             "src/my/app/user.cljs"
 :rf.source/uri    "file:///abs/path/to/src/my/app/user.cljs#L42"
 :doc              "<docstring or nil>"
 :tags             #{...}                 ; if any
 ...custom-slots-emitted-by-the-reg-macro}
```

On a miss:

```clojure
{:ok?    false
 :reason :not-registered
 :kind   <requested-kind>
 :id     <requested-id>}
```

**Error envelopes** (short-circuit before touching nREPL):

- `{:ok? false :reason :invalid-kind :kind <raw> :hint "kind must be one of: event, sub, ..."}` — unrecognised / missing `kind` arg.
- `{:ok? false :reason :missing-id}` — `id` arg absent or blank.
- `{:ok? false :reason :invalid-id-edn :id <raw> :hint "..."}` — `id` failed `cljs.reader/read-string`.
- `{:ok? false :reason :handler-meta-failed :message "..."}` — runtime threw.

**Composite-key subs**: pass the vector form as a string —
`{:kind "sub" :id "[:rf/composite [:items :by-id 42]]"}`.

**Why not `eval-cljs`**: `eval-cljs` is wide-authority by design
(launch-flag-gated). The re-frame2-pair contract is "structured tools
for the common case, eval-cljs for the unknown unknowns";
`handler-meta` covers the most-frequent introspection ask with a
narrow surface the agent can rely on across runtimes and editor-config
postures.

**Source**: rf2-cibp8 (the source-coord uri decoration) + rf2-pctf8
(the introspection pair).

## list-handlers

Return every registered id under a kind — the discovery surface that
pairs with `handler-meta`. Agents call `list-handlers` first to find
out what's registered (per kind), then drill in with `handler-meta`
on a specific `(kind, id)` pair.

**Args**: `kind` (string, **required** — same enum as `handler-meta`),
`build` (string, optional).

**Supported kinds**: same closed v1 registrar set as `handler-meta`
(per rf2-cq1ak app-db schemas are not a registrar kind — use
`rf/app-schemas` for those; plus the virtual `:machine` kind). The
ten registrar kinds lift the id vector off the registrar's per-kind
map via `(re-frame2-pair.runtime/registrar-list kind)`. The
`:machine` kind wraps `(re-frame.core/machines)` — every event
handler flagged `:rf/machine? true` (Spec 005 §Querying machines).

**Returns**:

```clojure
{:ok?   true
 :kind  :event | :sub | :fx | :cofx | :view | :frame |
        :route | :flow | :head | :error-projector | :machine
 :ids   [<id> ...]
 :count <integer>}
```

The id vector is **sorted** (string / keyword / symbol ordering) so
the list shape is stable across calls. Empty `:ids` returns
`{:ok? true :kind k :ids [] :count 0}` — never `:ok? false` for the
empty case.

**Error envelopes**:

- `{:ok? false :reason :invalid-kind :kind <raw> :hint "..."}` — unrecognised / missing `kind` arg.
- `{:ok? false :reason :list-handlers-failed :message "..."}` — runtime threw.

**Pair with `handler-meta`**: typical agent workflow is
`list-handlers {kind "event"}` → pick an id → `handler-meta {kind
"event" :id "<picked>"}` for the full registration-metadata payload.

**Naming note**: renamed from `registry-list` per rf2-4y595 — NAMING.md
catalogues `<noun>-list` as **rejected** in favour of the canonical
`list-<things>` prefix shape (the runtime's `(rf/registry-list kind)`
accessor keeps its name because the runtime is a separate naming
surface). No back-compat shim; the old name hard-errors with
`:unknown-tool`.

**Source**: rf2-pctf8.

## get-re-frame2-pair-instructions

Agent-onboarding text (rf2-fnpqg). Returns an inline prose summary
of re-frame2-pair-mcp's tool catalogue, the EDN posture, the `:origin :pair`
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
 :tool "get-re-frame2-pair-instructions"
 :text "<prose>"}
```

The `:text` slot is a single string the agent host renders
verbatim. It carries no `:rf.size/large-elided` markers (no app-db
slot), no `:rf.mcp/dedup-table` (no repeated subtrees), and no
streaming machinery — just text.

Maintenance: the text lives in
`tools/get_re_frame2_pair_instructions.cljs` as the `instructions-text` def.
Edit it when the catalogue grows or shrinks. The structural peer
is the `re-frame2-pair-mcp.tools/tool-descriptors` docstring;
keep the two in lockstep when adding or removing tools.
