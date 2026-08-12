# 003-Tool-Catalogue

> Implements the [Tool-Pair contract](../../../spec/Tool-Pair.md) —
> each MCP tool below routes through one or more of the Tool-Pair
> primitives (`app-db-value`, `epoch-history`, `register-listener!`,
> `register-epoch-listener!`, `restore-epoch`, `replace-frame-state!`,
> `dispatch`, `dispatch-sync`).

The MCP tools (the live registry is the canonical count — see
[`src/re_frame2_pair_mcp/tools/registry.cljs`](../src/re_frame2_pair_mcp/tools/registry.cljs)).
All are catalogued below; the
read-side ops `read-sub` (rf2-3bu3d.7 — the validated one-shot
subscription read, no-silent-swallow parity with `dispatch`) and
`orient` (rf2-3bu3d.8 — the app-shape orientation summary, one
round-trip first-contact on an unfamiliar app), the streaming-control
diagnostic `get-stream-controls` (rf2-a0kxsb — the server-side
resource-control read), the
registrar-introspection pair `handler-meta` + `list-handlers` (rf2-cibp8
/ rf2-pctf8 — `list-handlers` renamed from `registry-list` per
rf2-4y595 for NAMING.md `list-<things>` conformance; both grow an
EP-0023 `:frame` arity per rf2-srobm0), the image-generation read
`describe-image` (rf2-srobm0 — the EP-0023 Use-Case 7 read over a frame's
running image generation), the operating-frame
trio `set-operating-frame` + `reset-operating-frame` + `get-operating-frame`
(rf2-zomfq — the [Tool-Pair §Tool-surface obligations][tsobl] ops that
surface the session frame pin, the escape from tier-4 `:ambiguous-frame`;
the public address is the **frame** id per EP-0023's `image -> frame ->
event stream` model — there is no realm coordinate, the EP-0013 realm
substrate was deleted in full under EP-0024),
the write pair `restore-epoch` + `replace-app-db` (rf2-ee38b.18 — the
Tool-Pair time-travel + state-injection primitives, gated behind
`--allow-writes`), `dispatch-dry-run` (rf2-17hvp — simulate a cascade without
committing), the view-plane reads `read-dom` (rf2-nfjil) + the typed
`ui/read` op `read-ui` (rf2-3bu3d.1 — rendered content + producing
entity, riding the view-id↔DOM map), and the signal
recorder `record` + `read-recording` + `watch-until` (rf2-zo4b9 — the
first-class recorder for races + watch sessions) live in the live
registry at
[`src/re_frame2_pair_mcp/tools/registry.cljs`](../src/re_frame2_pair_mcp/tools/registry.cljs)
and have full per-tool sections here.

[tsobl]: ../../../spec/Tool-Pair.md#tool-surface-obligations

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

The cache map is the codec's flat output. Agents
reconstruct with `(re-frame.mcp-base.dedup/expand cache-map)` — one
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
handle's path, or pass `elision false` to opt large content back
in and receive the un-elided value. Note `elision false` only
governs the LARGE-slot toggle — it does NOT reveal
declared-`:sensitive?` slots, which still redact to `:rf/redacted`
unless the caller also passes `include-sensitive true` under the
`--allow-sensitive-reads` gate (EP-0015 fail-closed, rf2-t55hxg.13).
Markers fire BEFORE the
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
(trace AND settle modes, rf2-8fin7.3), `trace-window`,
`watch-epochs`, `snapshot` (the `:epochs` slot of each frame), and
`subscribe` (the `epoch` event-kind) — delivers whatever shape the
framework's app-installed `:redact-fn` produced (per [Tool-Pair §Time-travel
§Redaction hook](../../../spec/Tool-Pair.md#time-travel-epoch-snapshots-and-undo)
and [Security §Epoch privacy posture](../../../spec/Security.md#epoch-privacy-posture--raw-in-process-records-vs-projected-egress)).
When the consuming app has called `(rf/configure! {:epoch-history
{:redact-fn (fn [record] …)}})`, the per-frame ring buffer and
every `:epoch`-stream (`register-listener! :epoch`) listener still
retain the **raw** assembled record (causal replay material) — the
`:redact-fn` never runs at `build-record` / ring-append / listener
fan-out. It runs projection-side, at off-box egress **only**: every
record re-frame2-pair-mcp ships is first routed through
`projected-record`, which applies the built-in frame/profile
projection and THEN invokes `:redact-fn` once per projection call. So
the redacted shape is what crosses the MCP wire, while the in-process
ring and listeners stay raw for exact replay.
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

### Framework-default projection (the `:redact-fn`-independent backstop, rf2-8fin7.1)

The app-installed `:redact-fn` is **optional** — an app that
declared a slot `:sensitive?` in its app-db schema but installed
no `:redact-fn` relies on the framework's own off-box projection,
not the hook. For the pull-mode epoch tools the framework backstop
is `re-frame.core/projected-record` (the single normative
off-box-egress emission site, [Security §Epoch privacy
posture](../../../spec/Security.md#epoch-privacy-posture--raw-in-process-records-vs-projected-egress)):
each egressed record is routed through it server-side under the
`--allow-sensitive-reads OFF` default, so a schema-declared
sensitive **slot** sitting inside a **non-sensitive** epoch's
`:db-before` / `:db-after` lands as `:rf/redacted` — even though
the whole-epoch sensitivity rollup is false and the `:redact-fn`
was never installed.

`snapshot`'s `:epochs` slot is held to the SAME projection
contract as `trace-window` / `watch-epochs` (rf2-6wvh5): every
epoch record in the slice is mapped through `projected-record`
server-side when the slice expands to `:full`, gated by the
`:include-sensitive` two-key opt-in (the launch flag AND the
per-call arg). Before rf2-8fin7.1 the `:epochs` slice was
`:redact-fn`-only — the client-side sensitive scrub merely DROPS
whole epochs stamped `:rf.epoch/sensitive? true` and never
redacts a sensitive slot inside a non-sensitive epoch — so a
`:redact-fn`-less app leaked the slot off-box under the OFF
default. The projection closes that asymmetry; `snapshot :epochs`
now carries the same fail-closed posture as the cursor-paged
epoch tools.

`dispatch`'s epoch egress is held to the SAME projection +
boot-gate posture (rf2-8fin7.3). Three paths egress epoch-derived
sensitive payloads under the `--allow-sensitive-reads OFF` default,
and all three are now gated:

- `trace` (`dispatch-and-collect`) and `settle`
  (`dispatch-and-settle!`) return the raw `:epoch` (+ `:render-events`
  for settle). Both route the result's epoch slots through
  `re-frame.core/projected-record` server-side under the OFF default,
  gated by the `:include-sensitive` two-key opt-in (the launch flag
  AND the per-call arg) — identical to `trace-window` / `watch-epochs`
  / `snapshot :epochs`. This projection runs on BOTH transports of the
  epoch-bearing modes: the synchronous (non-await) path AND the
  `await-render` path (where an explicit `trace` still resolves to
  `dispatch-and-collect`'s raw `:epoch`) — the await-render epoch
  projects the same way, so it never crosses the wire un-projected
  (rf2-6klf02). The `:include-sensitive` arg parses through the safe
  `args/parse-bool-arg` (the cross-MCP accept-shape parser), so a string
  `"false"` over the JSON wire stays FALSE — it never fail-opens to a raw
  read under `--allow-sensitive-reads` (rf2-66ippe).
- The cascade-summary `:event-vector` (the raw `:trigger-event`) FAILS
  CLOSED on its ARGS for EVERY epoch under the OFF default — the head
  `<event-id>` keyword is retained while every positional / map arg
  redacts to `:rf/redacted`, so `[:login "topsecret"]` egresses as
  `[:login :rf/redacted]`. This is the same projection the framework's
  `projected-record` applies to a record's `:trigger-event` slot
  (rf2-nm611o, `epoch/tool_pair.cljc` §`elide-trigger-event-slot`): the
  event args are registration-owned transient payloads the app-db
  classification walker cannot prove safe, so a secret carried IN the
  vector redacts whether or not the epoch is declared
  `:rf.epoch/sensitive?`. Keying the redaction to the
  `:rf.epoch/sensitive?` rollup ALONE leaked a non-declared
  trigger-event's secret off-box (rf2-6klf02). `dispatch` issues
  `configure-raw-state!` (`raw-state/signal-runtime!`) between the
  preload probe and the dispatch eval — the same prelude every
  state-emitting tool wears — so the runtime's `raw-state-config` flips
  out of its permissive `{:allow-raw-state? true}` default and the
  fail-close fires. Before rf2-8fin7.3 `dispatch` was the lone
  state-emitting tool that never signalled, so a FIRST-in-session
  sensitive dispatch shipped its raw event vector off-box even under
  the OFF gate. Posture parity with `dispatch-dry-run`.

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
streaming tools (`subscribe`, `unsubscribe`, `list-streams`,
`get-stream-controls`) bypass the cache — their return value is the
result of an action / a read of the volatile streaming-tap registry,
not frame state. `get-operating-frame` bypasses for the same reason:
its resolved triple is a function of the live **frame registry** plus
the per-session **pin**, both of which can move WITHOUT an app-db
mutation and WITHOUT a `set-operating-frame` / `reset-operating-frame`
call (a frame mount/unmount, or a runtime reload with a different live
frame set). The cache key cannot fold the registry/pin in, and the
result-hash cache only flushes on an explicit operating-frame mutation
— so caching it could serve a stale `:rf.mcp/cache-hit` for byte-
identical empty args, masking a newly ambiguous session or a newly
available app frame. It is non-cacheable.
(`list-subscriptions` opts IN — it reads the live reactive sub-cache,
a pure function of frame state, just like `snapshot`; rf2-qicji.)
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
| **Bare**      | Per-call validation / runtime failure (the normal tool body ran)   | `:invalid-kind`, `:missing-path`, `:not-an-event-vector`, `:path-not-found`, `:no-such-frame`, `:reserved-tool-frame`, `:no-new-epoch`, `:unknown-tool`, `:runtime-not-preloaded`, `:nrepl-unreachable`, `:build-not-running`, `:no-runtime-connected`, `:runtime-loaded-but-preload-missing`, `:port-unresolved`, `:eval-error`, `:timed-out`, `:probe-errored`, `:<verb>-failed` (e.g. `:snapshot-failed`, `:dispatch-failed`) |
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

#### `:ambiguous-frame` carries enriched diagnostics

A multi-frame session with no resolvable operating frame refuses every
read/mutate op with `:reason :ambiguous-frame`. Beyond the bare reason
the envelope carries the context an agent needs to recover in one step
(no `frames-list` / `discover-app` round-trip):

| Slot                | Meaning                                                                 |
|---------------------|-------------------------------------------------------------------------|
| `:operation`        | The op that refused (`:dispatch`, `:dispatch-dry-run`, `:read-sub`, `:subs-sample`, `:sub-cache-info`, `:record`). |
| `:event` / `:query` | The event-vector (dispatch) or query-vector (sub reads) the op was about to act on, when known. |
| `:available-frames` | The registered **app** frames — the exact set the caller may pin or pass. |
| `:selected-frame`   | The current session pin (nil = none) — whether a prior `select-frame!` is in effect. |
| `:hint`             | The human sentence + the concrete fix (pass `frame`, or pin via `select-frame!` / `set-operating-frame`). |

`:reason :ambiguous-frame` remains the SOLE machine discriminator (a
bare-dialect reason); the additional slots are additive context. An
agent pins one of `:available-frames` and retries, or passes it as the
`frame` arg.

**Every `:ok? false` response is `isError: true`.** A known-tool failure —
whatever the dialect of its `:reason` — is returned with the `tools/call`
result's `isError: true` flag set (API §Result shape; 001-Wire-Protocol
§JSON-RPC error codes). This includes the runtime / preflight / transport
rejections that ride back through the shared `probe/err->result` path
(`:no-runtime-for-build`, `:runtime-loaded-but-preload-missing`, the
`:<verb>-failed` fallbacks): they are NOT success-shaped values. The one
deliberate exception is a tool's normal terminal-but-empty outcome that an
agent reads as data rather than a fault — e.g. `watch-until`'s
`:watch-timeout` (the predicate simply did not hold in the window) — which
is documented as non-`isError` at each such tool. Keeping failures
`isError` is also what keeps them out of the response cache (cache
eligibility bypasses `isError` results), so a transient failure can never
be cached and mask a later successful read.

#### `:unknown-tool` recovery hint (rf2-tkmik)

The bare `:unknown-tool` reason — emitted when a `tools/call` names a
tool absent from the registry (a typo, or a removed alias such as the
pre-rf2-4y595 `registry-list`) — carries the recovery affordances every
other honest `:ok? false` envelope on this surface does:

```clojure
{:ok? false
 :reason :unknown-tool
 :tool "<the bad name>"
 :hint "unknown tool `<name>`; did you mean `<nearest>`? call `tools/list` to see the available tools."
 :available-tools ["discover-app" "eval-cljs" …]   ;; the live catalogue, registry order
 :did-you-mean "<nearest>"}                         ;; present only when a near edit-distance match exists
```

`:available-tools` is the same name set `tools/list` enumerates;
`:did-you-mean` (and the `did you mean …` clause inside `:hint`) appears
only when the bad name is within a small edit distance of a real tool.
A model that typo'd a name self-corrects from the envelope without a
`tools/list` round-trip.

The registry-membership check is a **pre-connection** guard (rf2-4mc6q1):
the server rejects an unregistered name with this envelope BEFORE it runs
nREPL port discovery — symmetric with the gated-write pre-connection
refusal (rf2-wz66k7). "Does this tool exist?" is a pure function of the
static registry, so a typo or removed alias is diagnosable on a fresh or
misconfigured session with no live app. Without this guard the lazy
discovery step rejects first (`:nrepl-port-not-found`) and masks the
unknown name behind a transport error, hiding the recovery affordances
above for exactly the case they were built for.

## discover-app

Verify the shadow-cljs nREPL is reachable, confirm the
`re-frame2-pair.runtime` namespace was loaded by the consumer's
shadow-cljs `:devtools :preloads`, and return a health summary. Run
first every session.

**Args**: `build` (string, optional) and `port` (integer, optional).
`build` is colon-tolerant (rf2-8ohwv) — `"examples/step-deck"` and
`":examples/step-deck"` resolve to the same build id; a doubled colon
never reaches the resolver. `build` is also **suffix-forgiving**
(rf2-qda59): a short tail (`"machine-epochs"`) that uniquely names a
running build resolves to its canonical namespaced id
(`:examples/machine-epochs`) — and the same forgiving resolution applies
to **every** op, not just discover-app, so a name that connected via
discover-app works verbatim on the read/action ops. Two builds sharing
the tail stay ambiguous; a no-match id falls through to the diagnostic
ladder. See [`API.md` §Build-id resolution](./API.md#build-id-resolution).

**URL/port → build (rf2-fyf0h).** A pair session naturally starts from
the browser URL of the open tab (e.g. `http://localhost:8031/counter`),
but discover-app speaks build-ids. Pass the URL's port as `port` and
discover-app resolves the serving build from the shadow-cljs `:dev-http`
map — which binds a port to a list of file roots; the serving build is
the one whose `:output-dir` is among those roots (`8031` →
`:examples/step-deck` in this repo). No manual grep of `shadow-cljs.edn`.
A `port` that maps to no build returns `:ok? false :reason
:port-unresolved` (loud, not a silent fall-through to `:app` — the
operator asked for that port), and — per the §"Every `:ok? false`
response is `isError: true`" rule above — it rides as an **`isError`
result** (rf2-bcayt7), the same envelope its sibling discover-app
precondition failures use. Keeping it `isError` also keeps an unresolved
port out of the response cache, so it can never mask a later valid
port→build mapping. An explicit `build` arg wins over `port`.

**Single-build auto-selection (rf2-v70kv).** When you omit `build` and
**exactly one** shadow-cljs build is running, discover-app auto-selects
it (rather than defaulting to `:app`, which fails by construction on any
checkout whose running watch isn't `:app`). The result echoes
`:auto-selected-build <id>` and prepends an auto-selection sentence to
`:note`, so the choice is visible — never silent. When **zero or many**
builds run, discover-app keeps the `:app` default and the failure-path
diagnostic ladder surfaces `:build-not-running` with the running-builds
list (the multi-build path stays ambiguous-and-loud — discover-app does
**not** guess a most-recently-active build). An explicit `build` arg (or
a build cached by a prior discover-app) is honoured verbatim and skips
auto-selection.

**Returns**: an `:ok? true` map with `:debug-enabled?`, `:frames`,
`:coord-annotation-enabled?`, `:build-id`, and a canonical `:build`
(rf2-8t3ct — the same value as `:build-id`, echoed under the **input
arg name** so a caller copies it straight back into a later tool's
`:build` slot; round-trippable, since `:build`-arg coercion reads both
`"examples/step-deck"` and `":examples/step-deck"` back to the same
keyword). On success the resolved build-id is cached on the conn-atom
(`:resolved-build-id`, rf2-l9ixp) — the session-sticky target;
subsequent tool calls may omit the `:build` arg — see
[`API.md` §Build-id resolution](./API.md#build-id-resolution). The
read-family ops (`orient`, `read-dom`, `read-ui`, `eval-cljs`) echo the
resolved `:build` on their result so the operating target stays visible
even when implicitly selected (rf2-fmho5).

**Freshness / liveness token (rf2-ertqw, rf2-jkwu4, rf2-646lr).** Every
`:ok? true` discover-app payload carries `:freshness {:liveness <verdict>
:hint <str> …}` — the browser-half (runtime-instance-id + load time)
merged with the JVM-half build-worker state (monotonic compile-cycle +
last-flush + WS heartbeat age), cross-checked to one `:liveness` verdict:
`:fresh` / `:stale-build` / `:no-runtime` / `:unknown`. The JVM-half read
is **retried once** before a `:unknown` degrade — a nil first read is
most often a transient socket hiccup, not a genuinely-unreadable old
shadow (rf2-jkwu4). For every **non-`:fresh`** verdict the `:hint` is
**actionable**: it names the EXACT `http://localhost:<port>` the human
reloads (when discover-app was called with a `:port`, which also rides on
the token). For `:unknown` the hint names the **dominant cause** —
MULTIPLE / ZOMBIE shadow-cljs JVMs (rf2-646lr): a stale watch Ctrl-C'd
without freeing its ports leaves an orphan JVM, and the nREPL socket can
reach a runtime whose build worker lives in a *different* JVM, so the
worker lookup misses even while reads still work. The remediation it
names is `npx shadow-cljs stop` (which kills all shadow JVMs and frees
the orphan ports, where Ctrl-C does not) followed by exactly **one**
`shadow-cljs watch <build>`, a reload, and a re-run of discover-app. The
agent cannot reload a browser or stop a JVM itself, so a non-`:fresh`
verdict is an early, crisp human-in-the-loop instruction — relay the
`:hint` rather than firing reads that will return blank. A `:stale-build`
verdict is also promoted to a top-level `:warning :stale-build`.

**Id representation (rf2-cg37y).** Every build/frame id discover-app
surfaces — `:build-id`, `:frames`, and the diagnostic `:running-builds`
— is a **full keyword** (`:rf/default`, `:examples/step-deck`) in the
canonical EDN `:content` text slot, and the embedded `:note` / `:hint`
strings re-state them in the same colon form. One representation
throughout that slot, so a first-time caller never has to guess which
field uses which form. The sibling `:structuredContent` JSON slot is the
documented lossy projection (`clj->js` drops the leading colon —
`["rf/default"]`); it is the SDK-friendly fallback, not the canonical
form. Read the EDN text when you want the id exactly as you would type
it back into a `:frame` arg.

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
| `:build-not-running` | nREPL is reachable but shadow's `active-builds` doesn't include the targeted build (after suffix resolution, rf2-qda59 — so a typo, never a mere short-tail). Carries `:running-builds` enumerating what IS up, plus `:running-builds-arg-forms` — each in the round-trippable `:build` arg form (rf2-qda59). (probe.cljs `diagnose-preload-failure!`) | "shadow-cljs is running `[":other-build"]` but not `:<target>`. Pass `--build=:other-build` (or set `SHADOW_CLJS_BUILD_ID`)…" |
| `:no-runtime-connected` | Build IS running but the cljs-eval round-trip returns blank — no browser tab has connected, or the tab's WebSocket has dropped. Carries `:running-builds`. (probe.cljs lines 233-237) | "build `<id>` is running but no CLJS runtime is currently connected… Open the app in a browser tab — or if a tab IS open, reload the page so the runtime reconnects." |
| `:runtime-loaded-but-preload-missing` | A CLJS runtime is alive but the `__re_frame2_pair_runtime` marker is absent. The original meaning of the legacy `:runtime-not-preloaded` reason — the preload entry IS what to add. (probe.cljs lines 242-245) | "re-frame2-pair.runtime is not loaded into this build. Add the preload entry to your shadow-cljs.edn… See skills/re-frame2-pair/SKILL.md (§Setup)." |

Each rung carries `:build` (the targeted id) plus a targeted
`:hint`; `:build-not-running` and `:no-runtime-connected` also carry
`:running-builds` (and `:build-not-running` a sibling
`:running-builds-arg-forms` in the paste-ready `:build` arg form,
rf2-qda59) so the operator's next move is one keystroke away.

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

## orient

> Implemented by `tools.orient`; routes through the runtime `orient`
> fn, which composes `health` + `app-frame-ids` + `registrar-list` +
> `app-db-value` top-keys + `rf/machines`. rf2-3bu3d.8.

**App-shape orientation summary in one round-trip** — the first-contact
answer to "what is this app and what can I drive?". When an agent
connects to an **unfamiliar** app, orienting otherwise took several
calls: `discover-app` (frames / health) + `snapshot` summary (app-db
top-keys) + `list-handlers` (event-ids) + `list-subscriptions` (sub-ids)
+ machines. `orient` composes those into one compact map by **reusing
the existing introspection surfaces** — no parallel implementation.
Most valuable precisely for devs **not** working on re-frame2 tooling:
they hand the agent an arbitrary app and the agent needs a fast map of
it.

The summary shape:

```clojure
{:ok? true
 :liveness {:debug-enabled?      bool
            :frame-count         N
            :app-frame-count     N
            :ambiguous-frame?    bool
            :runtime-instance-id <uuid>}
 :frames   {:all [...] :app [...] :operating <id>}      ; public frame address (EP-0023)
 :app-db-top-keys {<app-frame-id> [<top-level key> ...]}
 :registry {:basis :frame|:process :frame <id>?  ; rf2-srobm0 — see below
            :counts {<kind> N ...}    ; every v1 registrar kind
            :events [...] :subs [...] :fx [...]}  ; full ids for the 3 navigable kinds
 :machines [...]}
```

Compact + **summarized by design** (respects the wire cap): registrar
**counts** for every v1 kind, the full sorted **id vectors** for the
three most navigable kinds (`:event` / `:sub` / `:fx`), and per-app-frame
app-db **top-keys** — *not* the full app-db. Drill via the existing
`list-handlers` / `list-subscriptions` / `snapshot` / `get-path` /
`read-sub` ops.

**Frame-rebased registry (EP-0023, rf2-srobm0).** The `:registry` slot is
**re-based on the operating frame's resolved image generation** when a single
operating frame resolves — the **selected universe** that frame actually runs
(the same `(kind, id)` can resolve differently per frame), keyed off the
frame's generation rather than the process-wide registrar union. `:basis :frame`
+ `:frame <id>` name the resolution. It **falls back** to `:basis :process` (the
process-wide registrar counts) in a multi-frame ambiguous session (no operating
frame resolves) or against a core predating the EP-0023
frame-image-generation reads. Drill a frame's full per-kind ids with
`list-handlers {:frame … :kind …}` or its whole generation with
`describe-image {:frame …}`.

Reserved `:rf/*` **tool frames** (Xray's `:rf/xray`, SSR slots, …) are
split out of `:frames` (`:app` vs `:all`) and **excluded** from
`:app-db-top-keys` (the rf2-3bu3d.6 posture) so a first-contact
orientation doesn't overflow on tool-frame inspection state. The full
freshness/liveness token stays on `discover-app`; `orient` carries
enough of it (`:debug-enabled?`, the frame counts, `:ambiguous-frame?`)
to know the read is trustworthy.

**Frame addressing (EP-0023 §Surface dispositions).** The public addressing
surface is the **frame** (`:all` / `:app` / `:operating` — EP-0023's
`image -> frame -> event stream`). There is no realm coordinate: the EP-0013
realm / app-value / install substrate was **deleted in full** (no public facade
under EP-0023, removed outright by EP-0024 — there is no `re-frame.realm`
namespace, no `realm-ids`, and no `re-frame.frame/frame-realm`; see framework
[`spec/Spec-Schemas.md` §`:rf/realm`](../../../spec/Spec-Schemas.md)). The
former `:realms` / `:operating-realm` slots are gone with the substrate they
read; `:frames` carries only the frame-id surface (`:all` / `:app` /
`:operating`). `:app` lists the app (non-tool) frames; `:operating` is the
frame tier-3 sole-frame resolution scopes to.

Read-only + idempotent across same-state calls — cacheable like the
other read tools. `:reason :orient-failed` (with `:message`) on a
runtime/eval failure; the standard preload diagnostics otherwise.

## Universal: server launch flags

Three boot gates control authority surfaces. Two are default-OFF
opt-ins (`--allow-sensitive-reads`, `--allow-writes`); one is a
default-ON opt-out (`--no-eval`). Operators pass them as MCP-server
CLI flags:

| Flag                      | Default       | Effect when set |
|---------------------------|---------------|------------------|
| `--no-eval`               | absent (eval-cljs ON) | Disables `eval-cljs` (rf2-a0z0h; inverts the prior rf2-cxx5s default-OFF posture). Default is eval-cljs ENABLED — it is the REPL primitive of a pair-debug session. With this flag, `eval-cljs` returns `{:ok? false :reason :rf.error/eval-cljs-disabled}` without touching the nREPL socket. |
| `--allow-sensitive-reads` | OFF           | Honours caller-supplied `:include-sensitive true` and `:elision false` on every off-box value-egress surface — the direct-read tools (`snapshot`, `get-path`, `read-sub`, `list-subscriptions :include-values`, `subscribe`, `trace-window`, `watch-epochs`), the signal recorders (`record`/`read-recording`, `watch-until`), `dispatch`'s epoch-bearing `:trace`/`:settle` modes (rf2-olvr5 / rf2-m9duxl), AND `dispatch-dry-run` (rf2-z7roa), whose `:db-state-after-simulation` + `:would-fire-effects[*].args` slots are app-db/fx-derived egress. Also signals the preload runtime to ship verbatim payloads through `app-db-reset!`'s `tap>` emission. The control-state-only diagnostics (`get-stream-controls`, `list-streams`) carry no payloads and are ungated. Canonical cross-MCP flag name shared with story-mcp (rf2-2x3ql). |
| `--allow-writes`          | OFF           | Enables the state-mutating tools `restore-epoch` (time-travel undo) and `replace-app-db` (state injection). Without the flag, both return `{:ok? false :reason :rf.error/writes-disabled}` without touching the nREPL socket. `dispatch` (which drives the application's own handlers) is unaffected. The descriptors still appear in `tools/list`; the gate is enforced at `tools/call` time. Note: this gate protects the named-write audit trail; it does NOT defend against eval-driven writes (eval-cljs can express the same writes), so for a true read-only posture compose with `--no-eval`. |

### Launch-config validation (rf2-a0kxsb)

The launch flags above — plus the resource-control flags / env vars in
[§Universal: server resource controls](#universal-server-resource-controls-streaming-surfaces)
— are parsed against a **declared schema** (boolean flags, valued flags,
resource-control flags, resource env vars). The parsers themselves stay
permissive (a `--*` token the server doesn't understand is plucked
past, not fatal — node / shadow-cljs pass their own argv prelude), but a
separate validation pass scans the same argv at boot and emits an
**explicit structured stderr diagnostic** for any misconfigured input,
**before the transport announces readiness**. Each diagnostic names the
rejected input and the effective fallback:

| Issue              | Trigger | Diagnostic |
|--------------------|---------|------------|
| `:removed-flag`    | A renamed / removed legacy name (`--allow-raw-state` → `--allow-sensitive-reads`; `--allow-eval`, now eval defaults ON) | Names the replacement — pre-alpha, no silent no-op for a stale `~/.claude.json`. |
| `:unknown-flag`    | A `--*` token matching no known flag (a typo like `--no-eavl`) | "ignored — not a recognised launch flag". |
| `:missing-value`   | A valued flag (`--port-file` / `--http-port`) present with no value | Names the fall-through to default discovery / behaviour. |
| `:malformed-value` | `--http-port` non-numeric, or a resource-control flag whose value isn't a positive integer | Names the fall-back to the documented default. |
| `:malformed-env`   | A resource env var set to a blank / non-numeric / non-positive value | Names the fall-back to the documented default. |

The validator **warns, it does not hard-fail**: a hard boot-fail would
make the server vanish from the agent host (the operator never sees
*why*), whereas an explicit stderr line names the problem AND lets a
working (default-posture) server still come up. The rationale: a
one-character typo in a safety flag silently changes session posture
(`--no-eavl` leaves `eval-cljs` enabled because eval defaults on; a
misspelled `--port-file` falls through to discovery; an invalid resource
cap reverts to default). The diagnostic makes that mismatch visible in
the boot log instead of leaving the operator to discover it the hard
way.

When `--allow-sensitive-reads` is OFF (the published-build default), the
off-box read surfaces above — and `dispatch-dry-run`'s egress slots
(rf2-z7roa) — :

1. Force `:include-sensitive false` on every call. Caller-supplied
   `:include-sensitive true` is dropped before reaching the walker —
   declared-sensitive slots in `:app-db` / `:sub-cache` reads (and
   `dispatch-dry-run`'s `:db-state-after-simulation` /
   `:would-fire-effects[*].args`) return the `:rf/redacted` sentinel;
   sensitive trace events / epochs are stripped from streaming
   payloads. The `:cascade-summary` `:event-vector` slot — which copies
   the epoch's raw `:trigger-event` — FAILS CLOSED on its ARGS: the head
   `<event-id>` keyword is retained while every arg redacts to
   `:rf/redacted` (`[:login "topsecret"]` → `[:login :rf/redacted]`),
   for EVERY epoch — sensitive or not (rf2-6nks4 + rf2-nm611o +
   rf2-6klf02). The event args are registration-owned transient payloads
   the app-db classification walker cannot prove safe, so a secret carried
   IN the dispatch vector redacts regardless of the epoch's
   `:rf.epoch/sensitive?` rollup — keying it to the rollup alone leaked a
   non-declared trigger-event's secret (rf2-6klf02). This is the same
   projection `projected-record` applies to a record's `:trigger-event`
   slot (`epoch/tool_pair.cljc` §`elide-trigger-event-slot`). It is
   applied SERVER-SIDE inside the runtime projection
   (`restore-cascade-summary` / `cascade-summary`) because the
   trigger-event is not addressed by an app-db path the elision registry
   classifies. It therefore covers `restore-epoch`, `dispatch-dry-run`
   (whose `:cascade-summary` is otherwise NOT walked, being a counts-only
   projection), AND `dispatch` itself (rf2-8fin7.3) — every tool whose
   cascade-summary copies a dispatched `:trigger-event`. For `dispatch`
   the signal is load-bearing: a FIRST-in-session sensitive dispatch
   would otherwise run with the runtime still at its permissive
   `{:allow-raw-state? true}` default, so the `:event-vector` redaction
   would not fire and the raw event vector would ship off-box.
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

   This signal is issued by every tool that taps / egresses app-db,
   between the preload probe and the first state-emitting eval — the
   read surfaces, `dispatch-dry-run`, `restore-epoch`, `replace-app-db`,
   AND `dispatch` (rf2-z7roa / rf2-6nks4 / rf2-8fin7.3). `replace-app-db` in
   particular MUST issue it before its `app-db-reset!` call so the FIRST
   reset of a `--allow-writes` session cannot tap raw app-db ahead of
   the posture landing. `dispatch` issues it so the runtime redacts the
   DEFAULT cascade-summary's `:event-vector` on a first-in-session
   sensitive dispatch (rf2-8fin7.3) — without the signal that path
   ships the raw event vector under the OFF gate. `restore-epoch` issues it so the runtime
   redacts a sensitive target epoch's `:cascade-summary` `:event-vector`
   before building the projection. The
   per-build signal cache is marked successful ONLY after the
   `configure-raw-state!` eval resolves, never speculatively — a
   concurrent first caller awaits the same in-flight signal rather than
   racing ahead.

Operators who need raw state for offline debug opt in at server launch
by passing `--allow-sensitive-reads`. The per-call args then win again,
but the two axes are independent and the walker FAILS CLOSED (EP-0015,
rf2-t55hxg.13): `:elision false` opts large content back in but does NOT,
on its own, reveal declared-`:sensitive?` slots — those still redact to
`:rf/redacted` unless the caller also passes `:include-sensitive true`.
Only the deliberate full-raw combination (`:elision false` AND
`:include-sensitive true`) passes a value through the walker untouched.

Symmetric with story-mcp's `--allow-sensitive-reads` (rf2-uaymx /
rf2-g9fje). The same canonical flag name across MCP servers (rf2-2x3ql)
gives operators one posture vocabulary.

## Universal: server resource controls (streaming surfaces)

Four operator-configurable integer caps bound the server's exposure
to a runaway or hostile client of the streaming `subscribe` surface
(rf2-3ijbl, follow-on to the rf2-7adwg MEDIUM finding). Each cap has
a documented default, an override CLI flag (`--<name>=N`), and an
override env var (`<ENV_NAME>=N`). CLI flags win over env vars on
conflict. Values must be positive integers; a non-positive or
unparseable value falls back to the default — and, since rf2-a0kxsb,
that fall-back is **named at boot** by the launch-config validator
(`:malformed-value` / `:malformed-env`) rather than discarded silently.
See [§Launch-config validation](#launch-config-validation-rf2-a0kxsb).

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
= `max-events-per-sec`. The bucket is checked once per poll cycle
**before** the destructive drain: when a token is available the cycle
drains + emits; when the bucket is empty the cycle is **deferred** —
the server does NOT drain, so the runtime-side queue stays intact and
its events ride a later cycle once a token refills. Deferral, not
loss: no queued event is discarded by the rate cap (the runtime's own
per-sub queue budget still bounds memory via drop-oldest if a consumer
never catches up). The `tools/call` final summary surfaces the
cumulative count of deferred cycles as `:rate-dropped` (omitted when
zero) — a "cap was tripped, consider raising `--max-events-per-sec`"
signal, not a lost-event tally.

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

### Observability (rf2-a0kxsb)

All three gates enforce server-side, but the controller's live state —
effective caps, active slot count, token-bucket pressure, abuse-window
count — was historically reachable only through the rejection envelopes
themselves, the passive boot banner, and code-level test seams. The
[`get-stream-controls`](#get-stream-controls) tool surfaces that state
as a first-class read-only MCP diagnostic: it answers "why was my stream
denied / why is it quiet / why did it terminate?" by reporting the
controller's current beliefs. It reads the resource-control atoms
**in-process** (no nREPL round-trip), so it answers even when the
runtime is down — exactly when an operator is diagnosing a stalled
stream. Cross-check its `:concurrent-streams :active` against the
[`list-streams`](#list-streams) row count: a server `:active` with no
matching runtime row signals a leaked server slot; the reverse signals
a stale runtime subscription.

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
`trace-window`, `watch-epochs`, `subscribe`, `replace-app-db`) accepts
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
`(rf/subscribe ...)` / `(rf/dispatch ...)` / `(rf/current-frame-id)`
inside the form resolves against the requested frame.

**Composes with `:await`.** The `with-frame` wrap is the outer-most
form; the await mailbox sentinel rides through the wrap unchanged.
Note that `with-frame`'s lexical binding only lasts for the form's
SYNCHRONOUS evaluation — once a Promise resolves on a later tick, the
binding is gone (Spec 002 §with-frame: async closures must capture a
frame api via `(rf/capture-frame)` — the ONE public carry primitive).
Most ad-hoc probes don't hit this; long-running async forms that need
to dispatch in a `.then` callback should capture the frame explicitly.

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

`dispatch`, `replace-app-db`, and `restore-epoch` each surface a
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
`replace-app-db` carry no `:event-vector` (no dispatch happened); a
restore's `:cascade-summary` carries an additional `:restore? true`
marker so consumers can branch.

| Slot                  | Type                              | Notes |
|-----------------------|-----------------------------------|-------|
| `:epoch-id`           | `:any` (integer in ref runtime)   | The assembled epoch's id. |
| `:event-id`           | keyword                           | The triggering event-id. Absent on synthetic / halted-trigger-less paths. |
| `:event-vector`       | vector or `:rf/redacted`          | The original dispatch vector. Absent on synthetic paths. **Redacted to `:rf/redacted` when the source epoch is `:rf.epoch/sensitive? true` and `--allow-sensitive-reads` is OFF** (rf2-6nks4) — the raw trigger-event payload (auth tokens, passwords, …) must not cross the off-box boundary under the default posture. Rides verbatim only when the operator opted in via `--allow-sensitive-reads`. |
| `:frame`              | keyword                           | The frame-id the cascade settled in. |
| `:outcome`            | `:ok` / `:blocked` / `:error`     | The consumer-facing tier per `outcome->consumer-facing`. Forced `:error` when the cascade contained a thrown handler / machine action — see §Contained throws below. |
| `:db-diff`            | `{:changed-paths [...] :added-paths [...] :removed-paths [...]}` | Depth-1 path summary of the db delta. Each path is a one-key vector (e.g. `[:cart]`); drill in via `get-path`. |
| `:fx-fired`           | vector of fx-ids                  | Distinct fx-ids fired this cascade. Duplicates collapsed. |
| `:errors`             | vector of `{:operation :message? :machine-id? :action-id?}` | Present only when the cascade contained a thrown handler / machine action (rf2-hhkbb). One compact descriptor per `:rf.error/*` trace op; `:message` carries the exception's `.getMessage` when stamped, `:machine-id` / `:action-id` the machine attribution of a machine-action throw. Drill into the full exception (stack / ex-data) via the epoch's `:trace-events` or the Xray Epoch panel. See §Contained throws below. |
| `:subs-recomputed`    | integer                           | Count of unique sub-runs in this cascade. |
| `:renders`            | integer                           | Count of render emits in this cascade. |
| `:machine-transitions`| vector of `{:machine-id :from :to :phase}` | Absent when no machine activity. |
| `:elapsed-ms`         | number                            | Wall-clock elapsed-ms from `:rf.event/run-start` to `:rf.event/run-end`. |
| `:sensitive?`         | `true`                            | Present only when the epoch's `:rf.epoch/sensitive?` rollup is true. Consumers branch on absent-slot patterns in `:db-diff`. |
| `:restore?`           | `true`                            | Present only on `restore-epoch` responses. Signals the summary projects the TARGET epoch, with `:db-diff` computed from the pre-restore live db. |

### Contained throws (rf2-hhkbb)

A thrown handler or machine action does **not** halt the cascade: the
interceptor error-capture seam contains the throw, the epoch settles
`:outcome :ok`, and the exception rides the trace stream under a
`:rf.error/*` op (e.g. a `:*` wildcard machine action throwing
`:rf.error/machine-action-exception`, the xstate-v5 "fail loudly on
unknown" idiom). Because the aborted action commits no `:db` and fires no
fx, a naïve summary would read the epoch as `:outcome :ok` + a clean
no-op — a silent-green-on-error trap for any non-visual consumer.

The cascade-summary closes that gap: when `:trace-events` carries a
contained cascade exception it surfaces the throws under `:errors`,
forces `:outcome :error`, and `dispatch`'s `dispatch-consequence!`
projection reports `:no-op? false` (a throw is never a no-op). The set of
`:rf.error/*` ops that count mirrors Xray's `cascade-exception-ops` so the
structured summary and the human Epoch panel (rf2-4yrr6 pink card) agree
on what a throw is. A genuine unhandled-event no-op (no exception trace)
keeps `:outcome :ok` + `:no-op? true`; a clean cascade stays `:ok`.

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
`sync` (bool), `trace` (bool), `settle` (bool, rf2-vk79g — the most
complete single-call shape; dispatch → synchronous render flush →
settled epoch), `await-render` (bool, rf2-gfu33), `queued` (bool,
rf2-3bu3d.2 — async transport-ack shape), `timeout-ms` (integer,
default `5000` — render-settle deadline), `frame` (string, e.g.
`":foo"`), `fx-overrides` (object, e.g. `{:http :stub-http}`),
`interceptor-overrides` (object, e.g. `{:auth/required :story/skip-auth}`;
rf2-m7x0qb — see *Envelope override re-supply* below), `cofx` (string —
EDN map of scripted recordable coeffects, e.g.
`"{:rf/time-ms 1781078400123}"`; rf2-q6s1nb / EP-0010 + EP-0017 — see
*Reproducible dispatch* below), `replay` (bool, default `false` —
re-drive the recorded event under `:rf.cofx/mint-policy :strict`; see
*Strict replay* below), `include-sensitive` (bool, default `false` — ship the
raw `:epoch` / `:event-vector` verbatim; honoured ONLY under
`--allow-sensitive-reads`, rf2-8fin7.3), `build` (string).

The `frame` arg is colon-tolerant (rf2-ldfnx): both the documented
colon-prefixed id (`":rf/xray"` — the form discover-app surfaces, §Id
representation) and the bare-name form (`"rf/xray"`) coerce to the same
`:rf/xray`. It routes through the shared `fresh-keyword` coercion, the
SAME path `eval-cljs` / `snapshot` / `get-path` / `replace-app-db` use,
so a frame-targeted dispatch lands on the named frame in a multi-frame
app. (The pre-rf2-ldfnx shape used raw `(keyword …)`, which minted the
malformed `::rf/xray` from the colon-prefixed form — a frame that
matched no registered frame, so the dispatch silently no-op'd while the
tool reported `{:mode :sync}`. That silent wrong-success is fixed.)

**Returns**: the runtime's response, merged with `:mode`. Per rf2-6yqdl
every successful dispatch surfaces a `:cascade-summary` slot — see
§Universal: cascade summary on state-mutating tools above for the
shape and the `:cascade-summary-pending?` behaviour on queued mode.
Trace mode additionally carries the full `:epoch` (the verbatim
assembled record) alongside the compact summary; settle mode carries
the settled `:epoch` plus `:render-events`.

**Epoch-egress privacy (rf2-8fin7.3).** `dispatch` egresses
epoch-derived sensitive payloads on THREE paths, all governed by the
`--allow-sensitive-reads` boot gate (OFF by default), with posture
parity to `dispatch-dry-run`:

1. **`trace` `:epoch`** (`dispatch-and-collect`) and **`settle`
   `:epoch` + `:render-events`** (`dispatch-and-settle!`) route the
   result's epoch slots through `re-frame.core/projected-record`
   server-side under the OFF default — sensitive leaves land as
   `:rf/redacted`, large slots elide, the cascade stays structurally
   useful (db deltas, fx-fired, sub-runs, renders, event-id all
   survive). Raw rides only when the operator launched with
   `--allow-sensitive-reads` AND passed `:include-sensitive true` (the
   two-key opt-in shared with the pull-mode epoch tools). This mirrors
   `trace-window` / `watch-epochs` / `snapshot :epochs` exactly.
2. **The DEFAULT cascade-summary `:event-vector`** (any mode — it
   copies the epoch's raw `:trigger-event`, e.g.
   `[:auth/sign-in {:password "…"}]`) redacts to `:rf/redacted` for a
   sensitive epoch. `dispatch` issues `configure-raw-state!`
   (`raw-state/signal-runtime!`) between the preload probe and the
   dispatch eval, so the runtime's `raw-state-config` flips out of its
   permissive `{:allow-raw-state? true}` default and
   `redact-sensitive-event-vector` fires. Before rf2-8fin7.3 `dispatch`
   was the lone state-emitting tool that never signalled, so a
   FIRST-in-session sensitive dispatch shipped its raw event vector
   off-box even under the OFF gate — a fail-open default, not a
   trace-mode-only leak. See §Universal: cascade summary (the
   `:event-vector` redaction) + §`--allow-sensitive-reads` above, and
   [Security §Epoch privacy posture](../../../spec/Security.md#epoch-privacy-posture--raw-in-process-records-vs-projected-egress)
   / [Tool-Pair §Time-travel](../../../spec/Tool-Pair.md#time-travel-epoch-snapshots-and-undo).

A non-sensitive epoch / event is byte-identical gated or not — there
is no friction where there is nothing to protect.

**Success-vs-error contract (rf2-ldfnx).** The `:mode` slot is the
caller's signal that the dispatch took effect, so it appears ONLY on a
genuine landing. When the runtime reports the dispatch did NOT land —
the named frame could not be targeted (head did not advance:
`:reason :no-new-epoch`), epoch-history was empty (frame destroyed /
recording disabled: `:reason :no-epoch-recorded`), or the frame was
ambiguous (`:reason :ambiguous-frame`) — the tool surfaces a structured
ERROR envelope (`:isError true`) carrying the runtime's verbatim
`:ok? false` / `:reason` / `:hint`, with NO `:mode` slot. It never
reports `{:mode …}` over a no-op.

### Reproducible dispatch — `cofx` (rf2-q6s1nb / EP-0010 + EP-0017)

By default a dispatched event's wall-clock time and other recorded causal
facts are stamped freshly by the runtime, so the resulting state differs
run-to-run — an agent cannot replay a fixture and assert the SAME output.
The `cofx` arg closes that: it accepts an EDN map of scripted recordable
coeffects that is threaded into the dispatch envelope under the public
`:rf.cofx` opts key.

> EP-0017 renamed this surface from `world-inputs` / `:rf.world/inputs`
> (nested, key `:time-ms`) to `cofx` / `:rf.cofx` (flat, key
> `:rf/time-ms`) — the recorded map IS the recordable grade of coeffect.

`:rf.cofx` is an optional key of the `:rf/dispatch-opts` schema
([Spec-Schemas §:rf.cofx](../../../spec/Spec-Schemas.md#rfcofx)), and the
router PRESERVES a caller-supplied map verbatim — it fills only the
framework-required `:rf/time-ms` when absent and never overwrites a
supplied value ([002 §Envelope stamping](../../../spec/002-Frames.md#envelope-stamping)).
So a dispatch with `cofx "{:rf/time-ms 1781078400123}"` makes every
durable wall-clock read (entity `:created-at`, resource `:loaded-at`,
machine snapshot times, the epoch record's causal time, …) read that
exact value; owner-qualified recordable facts (the app's `:counter/delta`,
a subsystem's `:rf.route/location`, …) ride through the same way. This is
the agent-replay-determinism affordance EP-0010 names for the
tool-dispatch helpers (Ref-Impl step 4).

**Wire shape.** The arg is an EDN STRING (data, not host source — the
same gate as `event`, rf2-vflrg). It MUST read as a map, and `:rf/time-ms`,
when present, MUST be an integer (epoch milliseconds). A non-map /
unreadable value returns `:reason :invalid-cofx`; a non-integer
`:rf/time-ms` returns `:reason :invalid-cofx-time-ms` — both short-
circuit to an `:isError` envelope BEFORE the dispatch eval, so a typo
never reaches the runtime's deeper `:rf/dispatch-opts` validation. Omit
the arg for the ordinary live path (the runtime stamps `:rf/time-ms`).
`cofx` composes with every mode (`sync` / `trace` / `settle` /
`await-render` / `queued`) and with `frame` / `fx-overrides` /
`interceptor-overrides`.

### Strict replay — `replay` (rf2-v52xsr / EP-0017 §6 / Tool-Pair §Replay)

`cofx` ALONE is the **live** scripted-coeffect path: the dispatch runs
under the router's `:live` default
([002 §Mint policies](../../../spec/002-Frames.md#mint-policies)), so a
declared recordable fact that is **absent** from the supplied token is
freshly **minted** (a generator runs / a host read fires). That is the
right behaviour for *constructing* a deterministic fixture — pin the time,
let the rest be generated — but it is the **wrong** behaviour for
*replaying* one. A replay re-drives a recorded event through the app's own
handlers to reproduce the recorded run; if a fact the original run consumed
is missing from the record, a `:live` replay mints a *different* value and
the replay silently diverges — the exact failure the recording discipline
exists to kill.

`replay true` is the named strict-replay affordance. It threads
`:rf.cofx/mint-policy :strict` into the dispatch opts ALONGSIDE the recorded
`:rf.cofx`, so:

- a recorded fact **present** on the token is re-presented verbatim
  (supplied values win — replay re-presents, exactly as EP-0010 requires);
- a declared recordable fact **absent** from the record is
  `:rf.error/missing-required-cofx` — the strict policy runs **no generator
  and performs no host read**, so an incomplete record fails **loudly**
  rather than minting a divergent value.

Per [Tool-Pair §Replay](../../../spec/Tool-Pair.md#replay-mint-policy) a
pair tool that re-dispatches a recorded event ALWAYS pairs the recorded
cofx with strict; the strictness is a property of the **replay gesture**,
not of any frame preset. The per-call `:rf.cofx/mint-policy :strict` is the
most-specific binding point and WINS over a frame whose config would
otherwise be `:live`, so replay can target a production `:live` frame and
still be strict. `replay` is strict even with **no** `cofx` token (an empty
record still halts on any declared recordable fact). Omit `replay` (with or
without `cofx`) for the ordinary live path; set it to **assert** a fixture
reproduces.

### Envelope override re-supply — `interceptor-overrides` (rf2-m7x0qb / Tool-Pair §Replay)

`replay` alone hard-wires the strict `:rf.cofx` policy, but a **faithful**
strict replay needs more than the recorded coeffects: per
[Tool-Pair §Replay](../../../spec/Tool-Pair.md#replay-mint-policy)
(rf2-yigokd), the recorded envelope's own per-call `:fx-overrides` /
`:interceptor-overrides` must ride alongside `:rf.cofx`. `:interceptor-overrides`
edits the pre-commit interceptor chain — a fold-changing fact exactly like
`:rf.cofx` — so a run recorded with an active override would otherwise
replay under a **different** effective chain and could silently commit a
different `:db-after`.

The `:rf/epoch-record` ([Spec-Schemas §:rf/epoch-record](../../../spec/Spec-Schemas.md#rfepoch-record))
carries these as bare `:fx-overrides` / `:interceptor-overrides` slots —
spelled identically to the dispatch-opts keys precisely so a replaying
tool can re-supply them with no key translation. To replay a recorded
epoch faithfully, read its `:fx-overrides` / `:interceptor-overrides` off
the record and pass them straight through as the `fx-overrides` /
`interceptor-overrides` dispatch args, alongside `replay true` and the
recorded `cofx`:

```
dispatch {event "[:cart/checkout]"
          replay true
          cofx "{:rf/time-ms 1781078400123}"
          fx-overrides {":http": ":stub-http"}
          interceptor-overrides {":audit/record-event": null}}
```

**Wire shape.** `fx-overrides` and `interceptor-overrides` are BOTH
threaded unconditionally whenever supplied — ordinary per-call
substitution/removal, not gated on `replay`. `interceptor-overrides` is a
colon-tolerant JSON object mirroring `:fx-overrides`'s wire posture, but
generalised to the `InterceptorRef` vocabulary
([Spec-Schemas §:rf/interceptor-ref](../../../spec/Spec-Schemas.md#rfinterceptor-ref-the-interceptor-reference-ep-0022)):
each key / non-null value is EITHER a bare colon-tolerant keyword id
(`":auth/required"`) OR a bracket-shaped EDN `"[id arg]"` string for a
parameterized `:factory` ref (`"[:rf.interceptor/path [:cart]]"`) — a JSON
object literal can only carry string keys, so a parameterized ref rides as
its bracket-shaped EDN string. `null` is the documented remove-this-
interceptor sentinel. A malformed key/replacement returns
`:reason :rf.error/interceptor-override-invalid`, mirroring the runtime's
own chain-assembly rejection for the same malformed shape.

**Fail-loud on the `:rf/fn-override` sentinel.** A recorded `:fx-overrides`
entry can carry the opaque `:rf/fn-override` marker
(`re-frame.router/serializable-fx-overrides`'s stand-in for a CLJS-only
fn-valued override the router could not serialize — fns are never EDN).
Re-supplying that literal sentinel as an `fx-overrides` value would
otherwise coerce as if it were a genuine keyword redirect to a
(non-existent) fx named `:rf/fn-override`. The record is **UNREPLAYABLE**
under `:strict` in that case: `dispatch` refuses with
`:reason :rf.error/unreplayable-fx-override` (the same incomplete-record
class as `:rf.error/missing-required-cofx`) rather than silently
dispatching without the fn-valued override the original run had active.
The check applies to every `fx-overrides` call, replay or not — the
sentinel can never be a legitimate override target.

`:interceptor-overrides` needs no such marker: EP-0022 retired
value-valued replacements, so every recorded entry is already an EDN
ref-or-`nil` and re-presents verbatim.

### Render-settle — `:await-render` (rf2-gfu33)

`dispatch-sync` returns once the handler has committed app-db, but the
substrate (Reagent / the React spine) re-renders on a LATER tick — so
"dispatch then observe the DOM" previously needed a manual
`requestAnimationFrame` dance inside `eval-cljs`. The `:await-render`
option collapses `dispatch → observe` into one deterministic step: the
tool resolves only AFTER the substrate has flushed the new state to the
DOM and the next paint is scheduled.

**Substrate-agnostic flush via the adapter contract.** The flush is NOT
a Reagent API call. The generated runtime form calls
`re-frame.interop/after-render` — the framework's render-settle
primitive, which routes through the `:adapter/after-render` late-bind
hook (Spec 006 §Substrate adapter contract). Each adapter publishes its
substrate-native impl: Reagent maps it to `r/after-render` (post-commit),
the UIx spine to a `React.useLayoutEffect`-backed queue drain
(post-commit / pre-paint, rf2-334d9), plain-atom / SSR to `next-tick`.
`after-render` fires once the DOM reflects the new state; the form then
chains ONE `requestAnimationFrame` so resolution lands at the paint
boundary (environments without rAF — headless / SSR — resolve straight
off the after-render callback). The MCP server therefore never names
Reagent or UIx.

**Wire shape.** `:await-render` forces synchronous dispatch (the cascade
must commit before the render can settle against the new state), so the
result is the `pair-dispatch-sync!` envelope (`:cascade-summary`,
`:epoch-id`, …) with `:mode :sync :settled? true` merged in. The runtime
form's synchronous return is a browser-side Promise; the server awaits
it through the shared await mailbox (the same plumbing `eval-cljs :await`
uses — `re-frame2-pair-mcp.tools.await-promise`). The rf2-ldfnx
success-vs-error contract holds through the settle path: a runtime
`:ok? false` (frame untargetable) still surfaces as an `:isError`
envelope with no `:mode` slot. A render-settle that doesn't complete
within `:timeout-ms` (default `5000`) returns
`:reason :rf.error/dispatch-await-render-timeout`.

| `await-render`? | dispatch fn | resolves after |
|-----------------|-------------|----------------|
| false (default) | per `sync`/`trace` mode | handler commit (today's semantics) |
| true            | `pair-dispatch-sync!` (forced; `trace` still wins for the assembled epoch) | substrate flush (`after-render`) + next paint (`requestAnimationFrame`) |

## dispatch-dry-run

Simulate a re-frame2 cascade WITHOUT committing it (rf2-17hvp). Full
reducer + interceptor chain runs, schema validation fires, machine
transitions simulate, sub-runs and renders are recorded — but NO fx
execute and the framework rolls back the app-db via `restore-epoch`.
The fundamental "experiment without consequences" primitive: every fx
the cascade WOULD have fired is enumerated in `:would-fire-effects`
(with its args), so the operator reasons about real-world impact
without paying it.

### Why this is NOT `--allow-writes`-gated

Dry-run's contract IS "no observable effect", and that guarantee is
STRUCTURAL (rf2-j538f7.39). The framework's dry-run effect sink
(`re-frame.fx/*effect-sink*`) intercepts at the single universal
effect executor (`do-fx`), BEFORE any per-fx override resolution,
reserved-fx dispatch, or user-handler invoke — RECORDING every
source-ordered `[fx-id args]` and running NO body. So no http /
navigation / persisted write, machine spawn/destroy, flow
register/clear, nav-token, or image-only inline fx escapes. The
framework's `restore-epoch` then rewinds the app-db and trims the
assembled would-be epoch from the ring. There is no state change for
the `--allow-writes` gate to protect against; pairing dry-run behind
that gate would force the operator to opt INTO writes to experiment
with NOT writing, which inverts the gate's intent.

### How it works (the effect sink)

The framework's dry-run effect SINK and the existing `restore-epoch`
primitive (Tool-Pair §Time-travel) compose into a dry-run that is
structurally unable to execute an effect:

1. Snapshot the head epoch-id (the rollback target).
2. Bind `re-frame.fx/*effect-sink*` to a fresh atom. `do-fx` — the
   SINGLE effect executor every fx flows through (the event fx walk, a
   machine exit-cascade walk, a resource-release walk) — RECORDS each
   well-shaped `[fx-id args]` entry and SKIPS execution, BEFORE any
   override / reserved-fx / handler runs. This is the whole no-effect
   guarantee: it covers reject-tier reserved fx and image-only inline
   fx WITHOUT enumerating the registrar (the old override-recording
   composition missed both — the security hole this bead closed).
3. `dispatch-sync` — the reducer + interceptor chain run normally
   (schema validation, machine-step machinery, sub re-evaluation, all
   live here); the cascade ASSEMBLES a real epoch on the ring.
4. Read the new head epoch — this IS the cascade-summary source.
5. `restore-epoch` back to the pre-call head. The framework's
   canonical undo gesture rewinds db and trims the would-be epoch
   from history. No handler ran before this, so there is nothing
   external to unwind.

The recorded fx calls AND the would-be epoch's cascade-summary
project together into the response shape.

### Edge cases

- **`:dispatch` / `:dispatch-later`** are recorded by the sink; the
  recursive dispatch never happens. This matches the bead's
  `:max-effect-chain-depth 1` default: simulate this event's reducer +
  its direct fx + LIST what those fx would dispatch (don't simulate
  that next level).
- **Schema violation** — the reducer's schema check fires the same
  way; the epoch settles with the violation in `:trace-events`,
  cascade-summary surfaces it via `:outcome :error`.
- **Machine transitions** — the machine-step machinery runs (pure
  data per Spec 005); transitions appear in cascade-summary's
  `:machine-transitions` slot. Machine-fired fx (timer schedules,
  spawn/destroy) are RECORDED + SKIPPED by the sink — the reject-tier
  spawn/destroy bodies never install or clear runtime state.
- **Frame mismatch** — the runtime fails with `:reason
  :ambiguous-frame` before the dispatch; no rollback needed.
- **Listener fan-out** — `register-listener!` / `register-epoch-
  listener!` consumers DO see the would-be epoch land between step 3
  and step 5. This is a documented limitation: the framework has no
  "private dispatch" primitive. Production builds elide the entire
  listener path anyway; dev-tier listeners observing a phantom epoch
  is acceptable in exchange for the simpler composition.

### Rejects `:fx-overrides` (rf2-j538f7.39)

A caller `:fx-overrides` is REJECTED loudly (`:reason
:fx-overrides-unsupported`, an isError). Because the effect sink
records + skips every fx BEFORE override resolution, an override could
only "compose realistic conditions" by executing a handler body — the
exact thing dry-run must not do. To try a canned http stub, use
`dispatch` (not dry-run) with `:fx-overrides` and roll back yourself.

### Composes with `cofx` — scripted recordable coeffects (rf2-3q7gep · EP-0017)

EP-0017 makes `:rf.cofx` a first-class dispatch edge for exact
recordable facts (`:rf/time-ms`, provided boundary facts, and
app/subsystem recordable facts; see the `dispatch` §"Reproducible
dispatch — `cofx`" section above for the full model).
Like `dispatch`, `dispatch-dry-run` accepts an optional `cofx`
EDN-map arg threaded into the simulated dispatch opts under the flat
`:rf.cofx` key. The router PRESERVES the supplied map verbatim (filling
only `:rf/time-ms` when absent), so a dry-run of a time-dependent or
provided-cofx event runs against the EXACT causal token the operator
intends to test — rather than the dry-run stamping a fresh `:rf/time-ms`
or failing `:rf.error/missing-required-cofx` for a provided fact that a
real `dispatch`/`replay` would supply. `:db-state-after-simulation`
becomes deterministic for the scripted token.

The arg uses the SAME `args/parse-cofx` gate as `dispatch`: a non-map
/ unreadable value returns `:reason :invalid-cofx`; a non-integer
`:rf/time-ms` returns `:reason :invalid-cofx-time-ms` — both short-
circuit to an `isError` BEFORE the eval, the dry-run never lands. (The
strict-replay `replay` gesture is a `dispatch`-only affordance; dry-run
exposes the live scripted-coeffect path only.)

### Privacy: an AI-facing read surface (rf2-z7roa)

Dry-run mutates nothing, but it IS an off-box read surface: the
happy-path envelope returns the would-be app-db verbatim under
`:db-state-after-simulation` and each recorded fx call's args under
`:would-fire-effects[*].args`. Reducers / fx routinely derive tokens,
auth headers, or other declared-`:sensitive?` / oversize values from
app-db, so an unredacted dry-run could leak those to the model even
though the `--allow-sensitive-reads` default is OFF.

It is therefore governed by the SAME `--allow-sensitive-reads` posture
as the direct-read surfaces (`snapshot` / `get-path` / `subscribe`;
see §`--allow-sensitive-reads`), reusing the existing model rather than
minting a new confirmation gate:

- **Default (gate OFF)**: `:db-state-after-simulation` and every
  `:would-fire-effects[*].args` slot are run through the
  size/sensitive elision walker (`re-frame.core/elide-wire-value`)
  server-side, before the EDN crosses the wire. Large slots collapse
  to `:rf.size/large-elided` markers; declared-`:sensitive?` slots
  redact to `:rf/redacted`. The per-call `elision false` /
  `include-sensitive true` knobs are forced safe.
- **Gate ON (`--allow-sensitive-reads`)**: the per-call `elision` /
  `include-sensitive` knobs win again, but the two axes are
  independent and the walker FAILS CLOSED (EP-0015, rf2-t55hxg.13).
  `elision false` opts large content back IN (`:rf.size/include-large?
  true`) but, on its own, does NOT reveal sensitive slots — a
  declared-`:sensitive?` slot still redacts to `:rf/redacted`. Revealing
  sensitive data requires the explicit per-call `include-sensitive true`
  opt-in. Only the deliberate full-raw combination — `elision false` AND
  `include-sensitive true` — ships the raw simulation details verbatim
  (the trusted-local `:rf.egress/local-raw` boundary).
- `:cascade-summary` is a depth-bounded projection (path lists +
  counts, not verbatim values) so it rides through unwalked, the same
  as `dispatch` / `replace-app-db` / `restore-epoch` (rf2-6yqdl).

Like the read surfaces, the tool also issues `configure-raw-state!`
(`raw-state/signal-runtime!`) between the preload probe and the eval,
so the runtime's tap-emitting surfaces (the dry-run's internal
`restore-epoch` rollback) sit in the gated posture too — not just the
wire payload.

**Args**: `event` (string, required — EDN-encoded event vector),
`frame` (string, e.g. `":foo"`; defaults to the operating frame),
`fx-overrides` (object — user-supplied overrides composed on top of
the dry-run recorder set), `cofx` (string — EDN map of scripted
recordable coeffects threaded into the simulated dispatch as
`:rf.cofx`, e.g. `"{:rf/time-ms 1781078400123}"`; see §Composes with
`cofx`), `elision` (boolean, default `true` —
applies the elision walker to `:db-state-after-simulation` +
`:would-fire-effects[*].args`; honoured as `false` only under
`--allow-sensitive-reads`), `include-sensitive` (boolean, default
`false` — pass declared-`:sensitive?` slots verbatim; honoured only
under `--allow-sensitive-reads`), `build` (string).

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
 :db-state-after-simulation {...}
 :elision                   true}
```

The cascade-summary slot uses the same shape as `dispatch` /
`restore-epoch` / `replace-app-db` (rf2-6yqdl); operators read one
vocabulary across all four. `:db-state-after-simulation` is the would-
be `:db-after` of the rolled-back epoch — surfaced through the elision
walker BY DEFAULT (rf2-z7roa; see §Privacy above) so the operator can
inspect what the post-dispatch db WOULD have been without re-running
through `snapshot`, while declared-sensitive / oversize slots stay
redacted unless the operator opted in. The `:elision` slot echoes the
effective walker state; `:elided-large` reports the marker count when
non-zero.

**Returns** (failure — all `isError: true`, rf2-wdxyx3 finding 2):

- `:reason :no-epoch-recorded` — epoch-history empty / frame
  unregistered / `interop/debug-enabled?` false. The dry-run did NOT
  land; no rollback needed.
- `:reason :no-new-epoch` — `dispatch-sync` returned but the head did
  not advance (the reducer rejected the event or an interceptor
  early-returned). The dry-run did NOT land; no rollback needed.
- `:reason :rollback-failed` (rf2-glg4uo) — the simulation LANDED and a
  would-be epoch assembled, but `restore-epoch` returned `false`, so the
  rollback did NOT complete: the would-be db IS now the live app-db and
  a spurious epoch remains at the ring head. This is the one failure
  where the dry-run left the live app **mutated**, so it is the most
  important to surface — a dry-run that silently mutated the live app
  and reported green would defeat the tool's entire "no observable
  effect" contract. The envelope carries `:rolled-back? false`,
  `:before-epoch-id`, and a `:hint` for manual re-restore
  (`(rf/restore-epoch! <frame> <before-epoch-id>)`). Rare but reachable:
  a nil `before-id` on a frame's first epoch-recording event, or a tiny
  `:epoch-history` ring (e.g. `:depth 1`) that evicts the rollback
  target. The MCP tool routes it to `isError: true` on BOTH `:ok?
  false` AND a belt-and-braces `(false? (:rolled-back? result))`
  boundary check — defence-in-depth so that even a degraded/older
  runtime that mis-reported `:ok? true` cannot ride green over a
  `:rolled-back? false` envelope.

The arg-parse failure modes (`:missing-event`, `:invalid-event-edn`,
`:not-an-event-vector`) mirror `dispatch` exactly — the EDN-data
posture (rf2-vflrg) is the same security gate.

**Annotation honesty (rf2-glg4uo)**: the descriptor keeps
`readOnlyHint` + `idempotentHint`. Those hints describe the tool's
CONTRACT — a read-only simulation — and a host uses them to auto-approve
the call. The `:rollback-failed` edge is the one path where the tool
does mutate, but it is now surfaced LOUDLY as `isError: true` rather
than as a silent green envelope, so the host is not misled: it
auto-approves the probe (correct for the ~always read-only case) and, on
the should-not-occur rollback failure, receives a RED result that names
the mutation and how to undo it. Flipping the hints to `destructiveHint`
would gate every genuinely-read-only dry-run behind the same approval
ceremony as `dispatch` — a real usability regression to signal an edge
that is already loud — so the honest, proportionate posture is: advisory
hints unchanged, failure loud.

## restore-epoch

Time-travel undo — rewind a frame's whole **frame-state** (BOTH the
app-db and runtime-db partitions) to a recorded prior epoch's
`:frame-state-after` value, reinstalled atomically via
`replace-frame-state!`. Machine snapshots, the route slice, elision
declarations, and SSR metadata are revived alongside app-db, not just
the app-db projection (EP-0001, Mike ruling #2). The canonical
pair-tool undo gesture per
[`Tool-Pair.md` §Time-travel](../../../spec/Tool-Pair.md#time-travel);
wraps the `restore-epoch` Tool-Pair write primitive
(`(rf/restore-epoch! frame-id epoch-id)`). Walk the ring with
`trace-window` / `snapshot` (`:epochs` slice) to pick a target
`:epoch-id`, then rewind to it.

**Launch-flag gate (rf2-ee38b.18)**: `--allow-writes`. Default OFF;
calls return `{:ok? false :reason :rf.error/writes-disabled}` without
touching the nREPL socket. A write surface — replacing the whole
frame-state wholesale is qualitatively more powerful than `dispatch`
(which drives the app's own handlers).

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
The `app-db` is unchanged on failure. A rejected restore is **not** a
terminal-empty outcome — the write did not land — so it rides with
`isError: true` per the §"Every `:ok? false` response is `isError: true`"
rule (rf2-or8s29); the host must not read it as a landed write.

## replace-app-db

State injection — replace a frame's `app-db` with an arbitrary EDN
value the runtime never recorded; the explicit JSON-loaded-bug-repro
case per
[`Tool-Pair.md` §Pair-tool writes](../../../spec/Tool-Pair.md#pair-tool-writes--state-injection).
Wraps the `replace-frame-state!` Tool-Pair write primitive as an
app-only partial map (`(rf/replace-frame-state! frame-id {:rf.db/app
new-db})`): bypasses the dispatch loop, replaces the container
directly, and records a synthetic `:rf/epoch-record` (`:event-id
:rf.epoch/db-replaced`) so a later `restore-epoch` can rewind past
the injection.

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
app-schema mismatch (the documented `:rf.epoch/*` failure modes), and
`{:ok? false :reason :unexpected-shape ...}` for a degraded runtime
that returns a non-envelope value. The `app-db` is unchanged on
failure. A rejected replace is **not** a terminal-empty outcome — the
injection did not land — so it rides with `isError: true` per the
§"Every `:ok? false` response is `isError: true`" rule (rf2-or8s29);
the host must not read it as a landed write.

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
don't sneak in mid-iteration. `watch-epochs` additionally stickies its
`:pred` filter in the cursor (rf2-mb17rj): a continuation that passes
back only `:cursor` keeps filtering by the first call's predicate.
Without this the second page would silently degrade to match-all and
return every epoch after the watermark unfiltered, with an envelope
indistinguishable from a correctly-filtered page.

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
encoded in the cursor on the first call), `frame` (string — frame-id,
colon-tolerant: `"rf/default"` and `":rf/default"` resolve identically,
rf2-lbm21), `limit` (int, default 50 — see §Cursor pagination above),
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
`rf/restore-epoch!` path is the canonical restore surface).

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
filter — sticky across cursor pagination, encoded in the cursor on the
first call (rf2-mb17rj), so a continuation passing back only `:cursor`
keeps the same filter; keys from: `:event-id`, `:event-id-prefix`,
`:effects`, `:touches-path`, `:sub-ran`, `:render`, `:origin`, `:frame`,
`:timing-ms`), `frame` (string — frame-id, colon-tolerant:
`"rf/default"` and `":rf/default"` resolve identically, rf2-lbm21),
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
readers (`app-db-value`, `sub-cache`, `machines` + frame-local
`[:rf.runtime/machines :snapshots]` in runtime-db, `epoch-history`, `trace-buffer`); no parallel
implementation.

**Args**: `frames` (string `"all"` or array of frame-id strings like
`":rf/default"`; **default = APP frames only** — reserved `:rf/*` TOOL
frames (`:rf/xray`, an SSR slot, …) are excluded so a first
investigate-read doesn't overflow on tool-frame inspection state, per
rf2-3bu3d.6. `:rf/default` is an app frame and is retained. Pass `"all"`
to include tool frames, or name a tool frame explicitly, e.g.
`[":rf/xray"]`. When tool frames are excluded the response carries a
`:note` naming them), `include` (array of slice names —
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
 :frames [<app-frame-id>...]|:all|[<frame-id>...]  ; default scope echoes the resolved app frames
 :note "Default scope = app frames only; excluded reserved :rf/* tool frame(s): [...]. ..."  ; only when tool frames were excluded
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
(`rf/machines`) with the per-frame state stash at `[:rf.runtime/machines :snapshots]` in
the frame's runtime-db partition (per Spec 005). The `:traces` slice filters the
retain-N trace ring buffer by `:frame`. Other slices delegate
verbatim to the public per-slice surface.

### `:epochs` slice modes (rf2-1wdzp)

Each epoch in the `:epochs` slice has its `:db-after` diff-encoded
against its own `:db-before` by default — `pr-str` doesn't preserve
structural sharing across records, so the legacy full-pair shape
otherwise carries ~2× app-db per record. Pass `epochs-mode "full"` for
the legacy shape (rare — only needed if you drive time-travel restore
off the wire response rather than via `rf/restore-epoch!`). See
`trace-window` above for the wire shape and rationale.

**Full-mode record cap (rf2-lbm21).** When the `:epochs` slice resolves
to `:full` lazy-summary mode (global `mode "full"` or per-slice `modes
{"epochs": "full"}`), the slice ships each record **verbatim** —
`:db-before` plus a full `:db-after` — so an unbounded 50-record
history pr-strs to ~50× the app-db and trips the global wire cap,
replacing the **whole** snapshot with an `:rf.mcp/overflow` marker (and
losing every other slice). To keep full mode usable, the slice is
default-capped to the **most-recent 10 records** (the tail of the
chronological history — "what just happened"). When records are
dropped, the frame map carries a sibling
`:rf.mcp/epochs-capped {:shown 10 :total <m> :dropped <d> :kept
:most-recent :hint ...}` marker so the truncation is explicit, never
silent. An agent that needs the full history pages via `trace-window`
/ `watch-epochs` (cursor-bounded) or fetches one record by id via
`restore-epoch`. The cap is a no-op in `:summary` mode (the slice is
already a one-line marker) and `:diff` `epochs-mode` (each `:db-after`
is already a small patch).

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
(rf2-qta8j, sampled rf2-lbm21) — `entry-count × per-entry-bytes`,
where `per-entry-bytes` is **sampled** from one representative entry
(`pr-str` of the first element / one map value), not a precise
serialised byte count. The marker's whole point is to avoid
serialising the deep value across all N entries (a 54MB app-db slice
would otherwise burn a 54MB string allocation per summary just to
compute one integer); agents needing a precise byte count walk the
drill-down result directly. Sampling reads one entry's depth so the
hint reflects the **full-expansion cost** — the prior flat per-entry
constant under-reported a slice of deep entries by ~1000× (the
`:epochs` slice — a vector of records each carrying a `:db-before` /
`:db-after` app-db pair — estimated 160 bytes while the full
expansion ran to ~171K tokens, so an agent reading the hint to
decide whether to `mode full` blew its budget). The sample cost is
`O(one-entry-depth)`, independent of `:count`. The marker is computed
AFTER diff-encoding and dedup so the entry count reflects the
post-shrink top-level shape. A map with more than 64 top-level keys
truncates the `:keys` list and flags `:keys-truncated? true` so the
marker itself can never blow the wire cap.

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
:sku]"` — or JSON array of segment strings), `paths` (string — an
EDN-encoded vector of path vectors, e.g. `"[[:cart :total] [:user
:id]]"` — or JSON array whose entries are EDN path strings / segment
arrays; the batch surface, rf2-lbm21), `frame` (string — frame-id,
default operating frame), `elision` (boolean, default `true` — applies
the size-elision walker to the resolved value(s); see §Size-elision at
the top of this catalogue, rf2-urjnc), `build` (string). `path` and
`paths` are **mutually exclusive** — supply exactly one.

**Returns** on success (singular `path`):

```clojure
{:ok?     true
 :exists? true
 :path    [<segment>...]
 :value   <subtree>            ; may be `:rf.size/large-elided` marker when elision applies
 :elision true | false
 :frame   <frame-id>}          ; only when frame arg was supplied
```

**Returns** on success (plural `paths` — batch read, rf2-lbm21):

```clojure
{:ok?     true
 :results {[<segment>...] {:exists? true  :value <subtree>}   ; elided per-value as above
           [<segment>...] {:exists? false :value nil}}        ; path didn't resolve
 :elision true | false
 :frame   <frame-id>}          ; only when frame arg was supplied
```

The batch surface resolves the frame's `app-db` once and reads every
path against it in a single round-trip — N targeted reads without N
calls. Each value is elided exactly as the singular surface does;
`:results` is keyed by the caller's path vectors so the agent
correlates hits without re-deriving order. An unresolved path carries
`:exists? false` (never a `:path-not-found` envelope — the batch is a
whole-or-nothing success).

When the path doesn't resolve, the failure rides as an **`isError`
result** (`:ok? false`, per §Result envelopes — a known-tool execution
failure is never a silent success, rf2-wdxyx3):

```clojure
{:ok?                  false
 :reason               :path-not-found
 :path                 [<segment>...]
 :deepest-valid-prefix [<segment>...]
 :frame                <frame-id>}     ; only when frame arg was supplied
```

`:exists?` distinguishes a path that legitimately points at a `nil`
value (`:exists? true :value nil`, an `isError: false` success) from a
path that doesn't resolve (`:ok? false :reason :path-not-found`,
`isError: true`). The deepest-valid-prefix lets the agent re-aim without
a binary search. Because the miss surfaces through the error channel it
is never response-cached (cache eligibility keys off `isError`), so a
later successful read of the same path is not masked by a stale failure.

When `elision` is enabled (default), a declared / schema-`:large?`
path or an over-threshold leaf returns a `:rf.size/large-elided`
marker (with a `:handle [:rf.elision/at <path>]` fetch handle) in
place of the raw bytes. Drill into a non-elided child by re-calling
with a deeper `path`. Pass `elision false` to opt large content back
in. Note `elision false` governs only the LARGE-slot toggle — it does
NOT reveal declared-`:sensitive?` slots, which still redact to
`:rf/redacted` unless the caller also passes `include-sensitive true`
under `--allow-sensitive-reads` (EP-0015 fail-closed, rf2-t55hxg.13).

`get-path` is the read-by-path surface for when `snapshot`'s
`:summary` mode tells the agent which key carries the answer.
`snapshot {... :path [...]}` is the equivalent surface when the agent
wants several slices in the same round-trip; both share the same
`:path` vocabulary.

`:reason :runtime-not-preloaded` if the preload hasn't run;
`:reason :missing-path` if neither `path` nor `paths` was supplied;
`:reason :path-and-paths-both-supplied` if both were supplied;
`:reason :empty-paths` if `paths` was an empty vector;
`:reason :get-path-failed` (with `:message`) on any other failure.

## read-sub

> Implemented by `tools.read-sub`; routes through the runtime
> `read-sub!` (validate → resolve frame → subscribe + deref once) +
> `re-frame.core/elide-wire-value` at the wire boundary. rf2-3bu3d.7.

Read a **subscription value** — the single most common read on any
re-frame2 app — **validated**, with **no-silent-swallow parity with
`dispatch`**. Prefer this over the raw `eval-cljs`
`@(re-frame.core/subscribe [:foo args])` incantation (stringly,
**un-validated** — a typo'd sub-id silently subscribes to a
non-existent sub and returns nil/garbage — and un-elided), and over the
`snapshot` `:sub-cache` slice (which only sees subs **already
materialised** in the reactive cache).

The `sub` arg is parsed as EDN **once** server-side and MUST be a
`vector?` (`[:current-user]`, `[:user/by-id 42]`). Host-form source is
rejected with `:reason :not-a-sub-vector`, unreadable input with
`:reason :invalid-sub-edn` — the same data-not-source gate `dispatch`
applies to `event` (rf2-vflrg). The sub-id (the vector head) is
**validated against the live `:sub` registrar** (the read-side mirror
of `dispatch`'s event-id validation, rf2-3bu3d.3): an unknown id returns
the structured `:reason :unknown-id` with `:nearest` matches and does
**not** subscribe — never a silent nil. A sub handler that throws while
computing returns `:reason :sub-error` carrying the message — a
structured error, not a bare nil.

Frame targeting mirrors every other read op: optional `:frame` resolves
the operating frame (explicit → session pin → sole app frame); a
multi-frame session with no selection returns `:reason :ambiguous-frame`
rather than silently reading `:rf/default`.

Privacy / elision matches `snapshot`'s `:sub-cache` slice and
`get-path` (per [Tool-Pair §Direct-read privacy
posture](../../../spec/Tool-Pair.md#direct-read-privacy-posture-for-sub-cache-and-get-path)):
the value is run through `re-frame.core/elide-wire-value` server-side —
declared-sensitive values redact to `:rf/redacted`, declared-large
values elide to `:rf.size/large-elided`. `elision false` /
`include-sensitive true` (honoured only under `--allow-sensitive-reads`)
opt back in along independent axes, and the walker FAILS CLOSED
(EP-0015, rf2-t55hxg.13): `elision false` opts large content in but does
NOT reveal sensitive values; `include-sensitive true` is required to
pass declared-sensitive values through. Only both together ship the
fully raw value (the walker is skipped entirely).

Success: `{:ok? true :query-v <v> :frame <id> :value <elided-value>
:elision <bool>}`. The structured failures (`:not-a-sub-vector` /
`:invalid-sub-edn` / `:missing-sub` / `:unknown-id` / `:ambiguous-frame`
/ `:sub-error`) ride back as `:isError` envelopes.

## read-dom

**Raw DOM plane** READ (rf2-nfjil): query the **rendered DOM** by CSS
selector and return matched count + per-node `{:tag :text :attrs}`.
Multi-node, exact, **no re-frame2 awareness** — "what does this exact
node SAY?". The data-plane reads (`snapshot` / `get-path` /
`trace-window` / `list-subscriptions`) answer "what's in app-db and the
trace?"; `read-dom` answers "what did the app actually put on screen?" —
the read needed for "did the UI update?" and "what does the rendered
node / attribute say?". It generalises the source-coord reads
(`handler-meta`'s `:rf.mcp/source-uri`, which return where a handler is
*defined*) to rendered-*content* reads (what's on screen *now*).

**The two DOM-read planes** (rf2-q0r7e). `read-dom` (this op) is the
**raw DOM plane** above; `read-ui` (below) is the **re-frame2 view
plane** — it rides the `data-rf-view` map to return content PLUS the
producing entity (view-id / source-coord / subs-read / render-key). They
are NOT duplicates: pick `read-dom` for raw content across N matched
nodes by selector, `read-ui` for a view's content AND its re-frame2
provenance in one round-trip. Both route through the SAME runtime ns
(`re-frame2-pair.runtime/dom-read` and `…/ui-read`) via the SAME
`eval-form` plumbing, sharing one per-node projection (`node->content`),
so a fix or a regression-guard on the shared form covers both — neither
can silently break alone (the rf2-w2mjm failure, where read-dom's
separately-inlined eval form nilled out while read-ui stayed green).

Named with the catalogued `read-<thing>` verb (NAMING.md §The verb
table): a cheap reflection of already-rendered state — the render
already happened; this is a no-recompute read of its output.

**Pairs with `dispatch {:await-render true}`** (rf2-gfu33): that op
resolves only after the substrate has flushed new state to the DOM, so
`dispatch → settle → read-dom` is a deterministic three-step observe
with no manual `requestAnimationFrame` dance.

**Read-only by construction.** The runtime fn
(`re-frame2-pair.runtime/dom-read`) calls `querySelectorAll` and reads
`textContent` / attribute strings only — it never assigns a property,
dispatches an event, or mutates a node. The descriptor carries the
read-only annotations so hosts auto-approve.

**Capped at the source.** The per-node text cap (`max-text`) and the
matched-node `limit` are applied **browser-side** (inside the runtime
fn), so only bounded EDN crosses the wire — a 5 MB `<pre>` blob never
leaves the tab. Over-cap text is replaced with the framework's
size-elision marker shape
`{:rf.size/large-elided {:type :dom-text :chars N :preview "..."}}` —
the same convention `get-path` / `snapshot` emit for over-threshold
app-db slots (see §Size-elision, rf2-urjnc). The wire-boundary token
cap (§top of this catalogue) remains the backstop.

**Args**: `selector` (string — CSS selector, required), `sub-selector`
(string — CSS selector run *relative to each matched node*
(`node.querySelectorAll`) to narrow a coarse match to its inner parts;
optional), `limit` (integer — max matched nodes returned, default 50;
excess nodes drop and `:truncated?` flips true), `max-text` (integer —
per-node `textContent` character cap, default 2000), `attrs` (array of
attribute-name strings to include; when omitted a curated structural
set rides *plus* a `data-*` / `aria-*` prefix sweep — the re-frame2
view-plane idiom for surfacing rendered state), `build` (string).

**Returns** on success:

```clojure
{:ok?          true
 :selector     "<selector>"
 :sub-selector "<sub>"           ; only when supplied
 :count        <total matches>   ; full tally, pre-:limit
 :truncated?   <bool>            ; true iff count > returned nodes
 :nodes [{:tag   "div"            ; lower-case tag name
          :text  "Count: 3"       ; textContent, capped (or :rf.size/large-elided marker)
          :attrs {"id" "c" "data-count" "3" ...}}
         ...]}
```

When nothing matched (no app mounted, or the selector matched no
element): `{:ok? true :count 0 :nodes []}`.

Every `:ok? true` result also echoes the resolved `:build` keyword
(rf2-8t3ct / rf2-fmho5) so the operating target stays visible even when
implicitly selected from the session-sticky cache.

`:reason :missing-selector` if `selector` was omitted / blank (no
nREPL round-trip);
`:reason :rf.error/read-dom-bad-selector` (with `:message`) when
`querySelectorAll` throws on a malformed selector;
`:reason :rf.error/read-dom-no-document` when the eval target has no
`js/document` (headless / server-side);
`:reason :rf.error/read-dom-blank-result` (rf2-r5erl) when the browser
eval came back **blank** (no map envelope — the runtime didn't answer,
e.g. a dropped WebSocket or a preload wiped by a full page refresh).
Since rf2-q0r7e read-dom calls the preloaded runtime fn (the same shared
plumbing read-ui uses) rather than inlining an eval string, an
unresolved-alias miss can no longer nil the form out — a blank result
now means the runtime genuinely didn't answer, not a form bug. This is
returned as a normal `{:ok? false}` tool result; it is NEVER a
host-level transport failure. (Earlier a blank eval threaded `nil` into
the result envelope, whose `null` `structuredContent` failed the SDK's
`outputSchema` validation with `expected record at structuredContent,
received null` — bypassing this error contract entirely. `wire/ok-text`
/ `err-text` now guarantee a non-null `structuredContent` record, and
read-dom maps a blank result to this reason.);
`:reason :runtime-not-preloaded` if the preload hasn't run;
`:reason :read-dom-failed` (with `:message`) on any other failure.

## read-ui

The typed **`ui/read`** op (a.k.a. `view/rendered`, rf2-3bu3d.1) — the
**re-frame2 view plane**, the sibling of `read-dom`'s raw DOM plane (see
§read-dom §The two DOM-read planes). Where `read-dom` needs an explicit
CSS selector and returns only raw content with no re-frame2 awareness,
`read-ui` rides the **view-id↔DOM map** and returns the rendered subtree
**plus the re-frame2 entity that produced it**, in one round-trip: given
a **view-id** (or a point / CSS selector), `{:via … :entity {…} :content
{…}}`. It answers the most common UI-pairing question — "what does the
thing I'm looking at SHOW, and what produced it?" — on **any** re-frame2
app with **zero testids**.

**Shared DOM-read core** (rf2-q0r7e). `read-ui` and `read-dom` route
through the SAME runtime ns via the SAME `eval-form` plumbing
(`re-frame2-pair.runtime/ui-read` and `…/dom-read`) and share one
per-node projection (`node->content`: tag + capped text + attribute
map). The view plane layers entity-resolution + privacy elision ON TOP
of that shared core; the raw DOM plane returns the core directly. A fix
or a regression-guard on the shared form covers both ops.

**Riding the view↔DOM map.** The browser-side work
(`re-frame2-pair.runtime/ui-read`) reuses the mapping the substrate
adapter already maintains: every registered view's rendered root carries
`data-rf-view="<id>"` (Spec 006 §View tagging contract; Spec-Schemas
§`:rf/view-id-attr`) — the **same** attribute the Xray pink
hover-highlight resolves (`apply-view-highlight!`) — and the sibling
`data-rf2-source-coord` carries the source coord. `read-ui` reads those
attributes directly; it never guesses a selector and never
re-implements view discovery.

Named with the catalogued `read-<thing>` verb (NAMING.md §The verb
table), so it lands with zero catalogue churn — a no-recompute read of
state the substrate already rendered.

**Three entry points → one entity** (precedence `view-id` > `point` >
`selector`): `:view-id` resolves `[data-rf-view='<id>']` directly;
`:point {:x :y}` runs `elementFromPoint` then walks up to the nearest
tagged ancestor (the producing view — "what's under the cursor?");
`:selector` runs `querySelector` then walks up to the view. The
`:entity` slot is the headline — `:view-id`, `:source-coord` (`{:ns
:handler-id :line :col}` from the attribute, augmented with `:file` via
`(rf/handler-meta :view <id>)`), `:render-key` (a stable node hash), and
`:subs-read` (the frame's live materialised sub-cache query-vectors).

**Privacy — elide like `snapshot` / `get-path`.** The rendered `:text`
is routed through `re-frame.core/elide-wire-value` with off-box defaults
(see §Size-elision; Tool-Pair §Direct-read privacy posture) — a
declared-large blob collapses to `:rf.size/large-elided` rather than
shipping raw user DOM text unconditionally. A hard per-node `max-text`
cap (default 2000) trims the common case before the walker runs.

**Read-only by construction.** Only `textContent` / attribute strings /
`elementFromPoint` / `querySelector` are read — never a write, a
dispatch, or a node mutation. The descriptor carries the read-only
annotations so hosts auto-approve.

**Args**: pass exactly one of `view-id` (string — registry view id, e.g.
`":my.app/header"`), `point` (object `{x N y N}`), `selector` (string —
CSS selector); plus `max-text` (integer — per-node `textContent` char
cap, default 2000), `frame` (string — operating frame for the
`:subs-read` slice + elision registry), `build` (string).

**Returns** on success:

```clojure
{:ok?    true
 :via    :view-id            ; | :point | :selector
 :entity {:view-id      :my.app/counter
          :source-coord {:ns "my.app" :handler-id "counter"
                         :line 42 :col 3 :file "/abs/my/app.cljs"}
          :render-key   8123
          :subs-read    [[:count] [:user]]}
 :content {:tag   "div"
           :text  "Count: 3"  ; capped + elided (or :rf.size/large-elided marker)
           :attrs {"class" "counter" "data-count" "3"}}}
```

A portal / fragment leaf with no tagged view ancestor still returns
`:content`, with `:entity {:view-id nil :reason :no-tagged-view-root}`.

`:reason :no-target-arg` if no entry point was supplied (no nREPL
round-trip);
`:reason :no-element` (with `:via`) when the entry point matched
nothing;
`:reason :no-document` when the eval target has no `js/document`
(headless / server-side);
`:reason :rf.error/ui-read-bad-selector` (with `:message`) on a
malformed CSS selector;
`:reason :runtime-not-preloaded` if the preload hasn't run;
`:reason :rf.error/read-ui-failed` (with `:message`) on any other
failure.

## The Hicasso evidence door — read-mounted-boundaries / read-read-attribution / explain-render

Three read-only projections of the **adapter-neutral Hicasso evidence
door** (`re-frame.hicasso.tool`) — the same evidence Xray's Hicasso tab
reads, exposed from a **running** re-frame2 app so a pairing agent
inspects what is mounted, who reads what, and which reads moved WITHOUT
reaching a private React / cell / scheduler handle. Every read answers
inside the four-axis evidence projection (`:scope`, `:basis`,
`:complete?`, `:loss`), **versioned** (stamps `:schema`, and `:read`
naming which read answered), **deterministic**, **serializable**, and
egresses only bounded plain data — no cell or React object crosses the
wire, and **no read value at all**.

| MCP wire tool             | `re-frame.hicasso.tool` read | arg |
|---------------------------|------------------------------|-----|
| `read-mounted-boundaries` | `read-mounted-boundaries`    | —   |
| `read-read-attribution`   | `read-read-attribution`      | —   |
| `explain-render`          | `explain-render`             | —   |

The wire names and the framework fn names agree exactly, and every read
is **nullary** — this runtime has no id to narrow by, which is the next
section.

### Three, because the door answers a different question set

This family replaced five tools aimed at `re-frame.freehand.tool`
(rf2-n3mb), and that was **not a rename**. Freehand published a view
registry and a compiler manifest, so it could answer
`read-view-manifest`, `read-view-dependencies` and
`read-view-event-sites` — static questions about a view named by its
declared id, answerable *before* mount.

**Hicasso mints no boundary identity and keeps no registry.** A
registration is its read set, React's notifier and the acquired cells;
`:view` and `:source` are stated as `:unknown` under an `:opaque` naming
projection, permanently and by design, because naming every live
boundary would need a registry or a field on the priced registration —
a standing memory cost the producer will not levy for a panel's benefit.
So there is no id to ask about and no manifest to read. **Those three
questions are unanswerable from this door**, which is a fact about the
provider rather than a shortfall here, and they are not shipped as tools
that would answer them with a fabricated emptiness.

What replaces them is a different way in. A boundary is keyed by its
**edge set**, so `read-read-attribution` — *which boundaries read this
subscription* — is how an agent gets from a subscription it can name to
a boundary it cannot, and `:reads` on every mounted row is the forward
direction of the same edge.

`re-frame.hicasso.tool/read-intents` is deliberately **not** shipped as a
fourth tool: it folds Spec 009's retained event ring, which is the
question `trace-window` already answers under richer projection, with
cursor pagination and the elision walker. A second, thinner window read
under a new name would be surface without a question of its own.

### Door presence — direct eval, no preload coupling

Unlike the `read-ui` / `read-dom` wrapper pattern, these tools do **not**
route through a `re-frame2-pair.runtime` fn. `re-frame.hicasso.tool`
lives in `day8/re-frame2-hicasso`, and **nothing in `re-frame.hicasso`
requires it** — that is how the substrate keeps the door out of a
production build entirely. An app on the Reagent or UIx adapter never has
it at all. Requiring it in the generic preload would make the preload
uncompilable in those apps.

So each tool evals a self-contained form that **resolves** the door at
runtime — `cljs.core/find-ns-obj` on the namespace, `unchecked-get` on
the munged read — and calls it only when present. Its absence is
surfaced honestly as `:reason :evidence-tier-unavailable` — *tolerate
absent evidence explicitly*, not a fabricated emptiness.

**The form must never reference a door var as a symbol** (rf2-t2ec).
It once did, behind a `cljs.core/exists?` guard, and the guard was fine
while the branch it guarded was not: shadow's analyzer resolves every
form it compiles before any of it runs, so against an app that has never
loaded `re-frame.hicasso.tool` the whole eval came back
`:rf.error/eval-cljs-compile-error` with a raw `:undeclared-var`
warning. The one population `:evidence-tier-unavailable` is written for
was the one population that could not reach it. A runtime lookup asks the
question the rung is actually about — *is this namespace loaded* — and
the analyzer has nothing to reject.

### The coupling is a wire STRING, and it has a witness

`re-frame2-pair-mcp.tools.hicasso-tool/tier-ns` holds the string
`"re-frame.hicasso.tool"`, and it is interpolated into every form these
tools emit. Pair has **no `:require` of the provider and no `deps.edn`
coordinate**, so no compiler, no clj-kondo run and no classpath scan can
see this dependency: an audit reports Pair clean of the provider while
every tool in this family calls it.

`test/re_frame2_pair_mcp/hicasso_wire_test.cljs` is what holds the other
side. It parses the reader name out of an **actually emitted form** and
asserts the provider's own source publishes it as a public `defn`, and
that `consumed-evidence-schema` equals the literal
`re-frame.hicasso.evidence/schema` stamps. The conformance corpus cannot
do this: its stubs are keyed on the wire string, so a form naming a read
no provider publishes matches a stub just as happily as a real one.

### Envelope and the schema gate

The eval form resolves to an `{:ok? …}` envelope, which the shared
`versioned-envelope-result` gates against `consumed-evidence-schema` —
currently `:re-frame.hicasso.evidence/v2` — before it reaches the wire:

- `{:ok? true …projection…}` — the projection, `:schema` **matching**,
  forwarded verbatim;
- `{:ok? false :reason :evidence-tier-version-mismatch :expected …
  :actual …}` — stamped a schema this build was not written against.
  Pair connects to an arbitrary running app, so the producer's stamp
  cannot define support. `re-frame.hicasso.evidence` states there is no
  v1 acceptance path and no compatibility adapter, so this gate is the
  consumer half of a boundary the producer means literally;
- `{:ok? false :reason :evidence-tier-unavailable}` —
  `re-frame.hicasso.tool` is not loaded;
- `{:ok? false :reason :evidence-tier-inactive}` — every read answers
  `nil` under `:advanced` with `goog.DEBUG` false. The door is dev-only;
- `{:ok? false :reason :evidence-tier-error :message …}` — the read
  threw.

Every `:ok? false` rides `isError: true` (§*Every `:ok? false` response
is `isError: true`*), so a degraded read is never cached and never
masquerades as a successful answer.

### Sensitive-data projection

These reads carry **no read value and no event vector**, at any
classification. That is uniform rather than declared: EP-0025's model is
fail-open, so a door that shipped an undeclared event's arguments while
promising *no application data* would be making the promise falsely.
Query vectors ride **pre-projected** through
`re-frame.elision/elide-wire-value` on the producer side, including
inside every exported boundary key. So this family needs no elision
walker of its own — the projection is done before the value reaches the
wire, and a second walk here would imply the first is not trusted.

### read-mounted-boundaries

Every Hicasso boundary **mounted right now**. No arg, deliberately: the
question is *what is mounted*.

`:complete? true` is a claim about **under-reporting** and it is exact —
a boundary whose body read nothing still claims an entry, so it is
counted where the cell table cannot see it at all. An **empty** roster
says exactly one thing: no boundary holds a live read edge right now. It
does not say nothing is retained above (an Activity-hidden subtree that
released its reads leaves the same empty census as an unmounted one),
and a row is **not** proof the boundary is on screen (a
Suspense-fallback-hidden subtree stays subscribed). The `:host`
projection states both.

```clojure
;; read-mounted-boundaries {}
{:ok? true :schema :re-frame.hicasso.evidence/v2
 :producer :re-frame/hicasso :read :mounted-boundaries
 :scope :mounted-boundaries :basis :observation :complete? true :loss nil
 :boundaries [{:boundary {:parent nil :key [[:app/main :todo [:todo 7]]]}
               :view :unknown :source :unknown
               :instances 3 :read-orders 1 :frame :app/main
               :reads [{:sub-id :todo :query [:todo 7]
                        :frame-id :app/main :epoch 4}]}]
 :generation 12
 :naming {:basis :opaque :complete? false :view :unknown :source :unknown}
 :host   {:basis :host-opaque :complete? false
          :commit :unknown :paint :unknown}}
```

### read-read-attribution

Which boundaries read each subscription — **the reverse edge, exactly**.
No arg.

This is the one read that is exact without qualification: it prints a
table rather than folding a window or naming what it cannot see. Every
cell's reader array *is* the reverse edge, maintained by the same commit
and cleanup that acquire and release the reference, so there is no
derivation to be stale.

It is also **the way in**. Boundaries carry no name here, so an agent
that can name a subscription uses this read to reach the boundary it
cannot name, then takes the `:key` onward to `explain-render`.
`:readers` are the same keys `read-mounted-boundaries` states, derived
by the same total order, so the two rosters join with no correlation
step. A key nothing holds is **absent** rather than present with zero
readers — it is not a subscription with no readers, it is one this
runtime is not holding.

```clojure
;; read-read-attribution {}
{:ok? true :schema :re-frame.hicasso.evidence/v2 :read :read-attribution
 :scope :read-edges :basis :observation :complete? true :loss nil
 :edges [{:sub-id :todo :query [:todo 7] :frame-id :app/main
          :epoch 4 :fan-out 3
          :readers [{:parent nil :key [[:app/main :todo [:todo 7]]]}]}]
 :host  {:basis :host-opaque :complete? false}}
```

### explain-render

Which of a boundary's reads moved most recently, and which retained runs
**could** have driven it. No arg: it spans every mounted boundary.

**Two halves, never blended.** *Proven*: `:latest-reads` names the reads
standing at the boundary's own `:peak-epoch`, read off the cells' epoch
stamps, and `:snapshot` is the exact sum React compares — so *which of
my reads moved* has an answer. *Uncorrelated*: the commit seam records no
cascade id, so `:cause` is `:unknown` **structurally** — not
occasionally, not when the ring is short, and not fixable with a bigger
ring — and `:candidates` are the retained runs that recomputed a
subscription this boundary reads, offered as **leads**. Presenting a lead
as a cause is exactly the fabrication `:uncorrelated` exists to refuse.

**The two loss reasons are drivable.** `{:reason :uncorrelated}` means
the lead search really ran, so `:candidates []` is an honest survey
result. `{:reason :cap}` means the boundary's own frames had an empty
window, so no search happened and `:candidates` is `:unknown` — an `[]`
in that state would be the fail-open shape the schema refuses. Both
halves are scoped to the boundary's **own** frames, and a candidate must
match a read on `[frame-id sub-id]`, never on the sub-id alone.

Whether the boundary then **ran** is `:host-opaque` — a notification
delivered is not a render performed, and React's memo comparator and
scheduler sit above this runtime.

```clojure
;; explain-render {}
{:ok? true :schema :re-frame.hicasso.evidence/v2 :read :explain-render
 :scope :mounted-boundaries :basis :observation
 :complete? false :loss {:reason :uncorrelated :dropped :unknown}
 :explanations [{:boundary {:parent nil :key [[:app/main :todo [:todo 7]]]}
                 :frame :app/main :instances 1
                 :snapshot 9 :peak-epoch 5
                 :latest-reads [{:sub-id :todo :query [:todo 7]
                                 :frame-id :app/main}]
                 :cause :unknown
                 :candidates [{:dispatch-id 41 :event-id :todo/toggle
                               :frame-id :app/main :sub-id :todo}]
                 :basis :observation :complete? false
                 :loss {:reason :uncorrelated :dropped :unknown}}]
 :window {:frames [:app/main] :retained-runs 12}
 :host   {:basis :host-opaque :complete? false}}
```

## record

First-class **signal recorder** (rf2-zo4b9) — the canonical move for
intermittent / human-in-the-loop bugs (the rf2-yng0y render-timing
race, only reproducible under real mouse input): install a read-only
observer over a heterogeneous signal-set, let the human interact, read
the change-log back via `read-recording`. Pre-bead this was hand-built
each session (a `requestAnimationFrame` loop pushing focus + DOM into
`window.__zoombug`) — decisive but bespoke and footgun-prone. `record`
first-classes the rAF/dedup/teardown machinery in the runtime.

`record` **returns immediately** with a `:recording-id`; the recording
runs in the background. The runtime samples every signal once per
animation frame, records each **change** (structural `=` against the
last value) with a `:t` timestamp + a rAF `:frame` counter, **dedups**
(a steady signal yields one baseline entry, not one-per-frame), and
**tears itself down** at the stop condition. A `requestAnimationFrame`-
absent target (headless / SSR) falls back to `next-tick`; a drop-oldest
ring (`max-entries`, default 2000) bounds memory on a forgotten
recording.

A bare-verb **mega-op** (NAMING.md Lock #8) — like `snapshot`, it spans
multiple observable kinds. Distinct from the `record-as-` prefix
(story-mcp's capture-as-artefact); bare `record` installs a live
change-log observer.

**Read-only by construction.** Every signal sampler only reads (`get-in`
/ subscribe-deref / `querySelector` text+attr / `activeElement`) — a
recording never dispatches, never mutates app-db, never writes the DOM.

**Signal shapes** (each a map naming one observable):

| Signal | Samples |
|---|---|
| `{:app-db [path]}` | `(get-in app-db path)` for the frame |
| `{:sub [query-v]}` | current deref of the subscription |
| `{:dom "sel"}` / `{:dom "sel" :attr "name"}` | first matching node's `textContent`, or that attribute |
| `{:focus true}` | a stable descriptor of `document.activeElement` (`:tag` / `:id` / `:class` / `:name` / `:rf2-src`) — the focus-slot |

**Stop condition** (`stop` arg; first key to trip wins; defaults to
`{:ms 30000}` when omitted): `:ms` (wall-clock), `:changes` (total
change-entry count), `:pred` (a **data** predicate map over the
positional sample map `{<signal-index> <value>}` — compiled to a pure
value-comparison fn, no host source crosses the wire; same shapes as
`watch-until`'s `pred`, below).

**Args**: `signals` (vector of signal maps / one bare signal map; EDN
string or JSON array; required, non-empty), `stop` (map; JSON object or
EDN string), `max-entries` (integer, default 2000), `elision` (boolean,
default true), `include-sensitive` (boolean, default false), `frame`
(string — operating frame for `:app-db` / `:sub` signals), `build`
(string).

**Privacy** (rf2-8fin7.2): the `:app-db` / `:sub` sample **values** are
walked by `re-frame.core/elide-wire-value` at sample time, **before**
they enter the change-log read back by `read-recording` — declared-`:large?`
slots collapse to `{:rf.size/large-elided ...}` markers and
declared-`:sensitive?` leaves redact to `:rf/redacted`, the same off-box
posture as `snapshot` / `get-path`. `elision` (size axis) and
`include-sensitive` (sensitive axis) are honoured only under
`--allow-sensitive-reads`; otherwise forced safe (`elision true`,
`include-sensitive false`). `:dom` / `:focus` signals are content reads,
not app-db-rooted, and ride unwalked.

**Returns**: `{:ok? true :recording-id "rec-<uuid>" :signals [...]
:frame <id> :stop {...}}`. `:reason :no-signals` when `signals` is empty;
`:reason :invalid-stop-edn` when the `stop` arg was supplied as an EDN
string that failed to parse — or read clean but was not a map, e.g.
`"[:ms 5000]"` (rf2-e2i29). Surfaced as `isError: true` WITHOUT touching
the nREPL socket; the error echoes the offending `:given` string. A
malformed `stop` is rejected up front rather than silently collapsing to
the default wall-clock window — the same honest-error posture
`subscribe`'s `:invalid-filter-edn` adopts. `:reason :ambiguous-frame`
when an `:app-db` / `:sub` signal needs a frame but none resolves
(multi-frame session, no selection) — this runtime refusal rides
`isError: true` too (rf2-5m2oi1), per the universal `:ok? false` rule,
not a success-shaped envelope.

## read-recording

Read back a recording's change-log (rf2-zo4b9) — the diagnostic re-read
paired with `record`, under the catalogued `read-<thing>` verb.

**Args**: `recording-id` (string, required), `drain` (boolean, default
false — return the buffered change-entries **and clear them**, so the
next read sees only subsequent changes: the poll→consume→repeat
live-watch idiom; the recording keeps running either way), `stop`
(boolean, default false — tear the recording down after reading:
read-and-close in one round-trip), `build` (string).

**Returns**:

```clojure
{:ok?            true
 :recording-id   "rec-<uuid>"
 :status         :recording           ; or :stopped
 :stopped-reason :ms                  ; :ms / :changes / :predicate / nil
 :frames-sampled 900                  ; rAF ticks since start
 :count          3
 :entries [{:i 0 :signal {:focus true}        ; one entry per CHANGE
            :value {:tag "input" :id "q"}
            :t 1712... :frame 0}              ; :frame = rAF counter
           ...]}
```

Each entry is one **change** — the moment signal `:i` took a new value.
Two signals that changed on the same paint share a `:frame`.

**Error envelopes** (all `isError: true`, per the §"Every `:ok? false`
response is `isError: true`" rule — rf2-5m2oi1): `:reason
:missing-recording-id` if omitted/blank (client-side short-circuit,
before the socket); `:reason :no-such-recording` for an unknown/expired
recording-id (the runtime refusal — so the host can route recovery
through the error channel). A legitimate empty read is `{:ok? true …}`
(a real map), so a normal empty drain is never mislabelled an error.

## watch-until

Block until a predicate over a signal holds (rf2-zo4b9) — the
**blocking** counterpart to `record` ("wait until focus lands on the
modal" / "wait until `[:upload :status]` flips to `:done`"). Introduces
the `watch-<thing>` prefix (NAMING.md Lock #8): distinct from `tail-`
(which awaits an *external* state change via a probe-value delta),
`watch-` blocks on a **predicate over an in-runtime signal-set**.

Like `tail-build`, the server polls a cheap runtime read on a fixed
cadence (~100 ms) until the condition trips or `timeout-ms` (default
30000) elapses — no rAF loop, no browser-side mailbox. Each poll evals
one form that samples the signal-set (`sample-signals`) and applies the
compiled predicate **server-side**, returning `{:held? bool :sample {...}
:t <ms>}`.

**Signals** use the same vocabulary as `record`. **`pred`** is a **data**
predicate map (no host source crosses the wire; same injection-closing
posture as `dispatch` / `replace-app-db`), matched against the positional
sample map `{<signal-index> <value>}`:

| Predicate | Holds when |
|---|---|
| `{:signal 0 :equals <v>}` | sample 0 equals `<v>` |
| `{:signal 0 :changed true}` | sample 0 is non-nil |
| `{:signal 0 :path [...] :equals <v>}` | `(get-in (sample 0) path)` equals `<v>` |
| `{:signal 0 :contains <substr>}` | `(str (sample 0))` includes `<substr>` |
| `{:signal 0}` | sample 0 took any non-nil value |

**Read-only by construction** — the runtime sampler only reads.

**Args**: `signals` (required, non-empty), `pred` (required — without one
the watch can only time out), `timeout-ms` (positive-millisecond integer,
default 30000), `elision` (boolean, default true), `include-sensitive`
(boolean, default false), `frame` (string), `build` (string).

`timeout-ms` is validated up front against the same positive-millisecond
contract the other timeout-aware tools use (`tail-build :wait-ms`,
`eval-cljs` / `dispatch` `:timeout-ms`). A malformed, zero, negative, or
fractional value short-circuits to an `isError` envelope
(`:reason :invalid-numeric-arg`, naming the offending arg) **before** the
runtime preflight — it is never silently rewritten to the default. An
omitted `timeout-ms` uses the documented 30000 default.

**Privacy** (rf2-8fin7.2): the `:app-db` / `:sub` values returned in the
`:sample` (on hold) and `:last-sample` (on timeout) slots are walked by
`re-frame.core/elide-wire-value` server-side — same off-box posture as
`snapshot` / `get-path` / `record`. The predicate itself evaluates over
the **unwalked** values, so elision never changes whether the watch trips
— only what the returned sample shows. `elision` / `include-sensitive`
are honoured only under `--allow-sensitive-reads`; otherwise forced safe.

**Returns** on the first poll where the predicate holds: `{:ok? true
:held? true :elapsed-ms <n> :sample {<i> <v>} :t <ms>}`. On timeout:
`{:ok? false :reason :watch-timeout :timed-out? true :timeout-ms <n>
:last-sample {...}}` — `:last-sample` is the final reading, to see how
close the condition got. The timeout is the watch's normal terminal
outcome (the predicate did not hold in time), not a tool fault, so it
rides as a non-`isError` success-shaped result.

**Error envelopes** (all `isError: true`, short-circuit before the runtime
preflight): `:reason :no-signals` / `:reason :missing-pred` on the
respective omission; `:reason :invalid-numeric-arg` (naming the arg) on a
malformed / zero / negative / fractional `timeout-ms`. A runtime /
preflight rejection (no live runtime for the build) rides back via the
shared `probe/err->result` path as an `isError` result too.

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
| `trace`     | Every raw trace event matching `filter` — grouped into event bundles keyed by `:frame` + `:dispatch-id`; consumers may merge by `:dispatch-id` (rf2-mscih).  |
| `epoch`     | Every assembled `:rf/epoch-record` matching `filter`. Already event-bundle-shaped by construction.                   |
| `fx`        | Sugar — `topic :trace` with base filter `{:op-type :rf.fx}`. Event-bundle delivery as for `:trace`.            |
| `error`     | Sugar — `topic :trace` with base filter `{:op-type :error}`. Event-bundle delivery as for `:trace`.            |
| `frameless` | Every trace event matching `filter` whose `:rf.trace/dispatch-id` tag is absent — registration emits, REPL evals, lifecycle outside any cascade (per [Tool-Pair.md §Frameless trace events — live channel only](../../../spec/Tool-Pair.md#frameless-trace-events--live-channel-only)). Single-event delivery. |

User-supplied filter keys win over the topic's base filter on conflict
— the topic is a default, not a lock. So `subscribe {:topic :fx
:filter {:op-type :info}}` actually streams `:info` traces (the user
filter wins). Don't do this — but the substrate doesn't refuse it.

### Event-bundle wire format (rf2-mscih)

On the event-bundle topics (`:trace`, `:fx`, `:error`) every
progress payload's `:event-bundles` slot is a vector of event bundles
keyed by `:dispatch-id`. Each bundle matches the framework's
`(rf/trace-buffer frame-id)` shape per [spec/009 §Event-bundle projection](../../../spec/009-Instrumentation.md#event-bundle-projection-group-by-event--domino-bucket)
and [Tool-Pair.md §Reading the per-frame trace ring](../../../spec/Tool-Pair.md#reading-the-per-frame-trace-ring--event-bundles--flat-opt-in):

```clojure
{:dispatch-id        <id>                  ; dispatch id
 :frame              <frame-id or nil>
 :event              <event-vector or nil> ; from :rf.event/dispatched :tags
 :dispatched         <trace-event or nil>  ; full :rf.event/dispatched event
 :handler            <trace-event or nil>  ; :rf.event/run-end emit (last wins)
 :fx                 <trace-event or nil>  ; :rf.fx/do-fx
 :effects            [<trace-event> ...]   ; :op-type :rf.fx (other operations)
 :subs               [<trace-event> ...]   ; :rf.sub/run + :rf.sub/skip + :rf.sub/create
 :renders            [<trace-event> ...]   ; :rf.view/render
 :other              [<trace-event> ...]   ; everything else (errors, machine, …)
 :trace-events       [<trace-event> ...]   ; raw events for the run
 :parent-dispatch-id <id or nil>}          ; causal-parent link
```

One tick = one drain's worth of event bundles. An event bundle is the
"atomic" delivery unit — consumers can reason about cause→effect at
the granularity of `:dispatch-id` without re-folding flat-event
streams (the pre-rf2-mscih posture).

Each bundle is grouped by `(frame, dispatch-id)` — `:dispatch-id` is
unique only *within* a frame, so a bundle's `:trace-events` are scoped
to its own frame (the framework projection `group-by-event-with-events`
keys on `[frame dispatch-id]`; consumers MUST NOT re-derive the grouping
with a weaker `:dispatch-id`-only key). Trace-event elision is likewise
**per-bundle-frame**: each bundle is run through the egress walker against
*its own* `:frame`'s declared sensitive/large registry (the operating
frame is only a fallback for genuinely frameless values). Eliding a
foreign-frame bundle against one shared operating frame would mis-redact
across the per-frame EP-0015 classification — the under-redaction
direction leaks declared-sensitive slots off-box.

Events with no `:rf.trace/dispatch-id` tag (registration emits, REPL
evals, lifecycle outside any cascade) NEVER ride the event-bundle
topics; the framework filters them at the dispatch gate. Consumers
that need them subscribe to `:frameless` explicitly.

The `:dropped-events` / `:dropped-bytes` / `:overflow-reason`
counters on the progress payload count the raw queued events that
were EVICTED by the byte+event budget — not event bundles. A non-zero
`:dropped-events` means consumers reconstructing an event bundle should
tolerate partially-truncated bundles (some of the run's
constituent events may have aged out).

### Cross-frame event reconstruction

A dispatched event can fan out across frames per [spec/002 §Cross-frame
dispatch](../../../spec/002-Frames.md). Every emit on every frame
shares the same `:rf.trace/dispatch-id`, so the runtime emits one
bundle per `(frame, dispatch-id)` pair per drain. Consumers that
watch multiple frames merge by `:dispatch-id` to reconstruct the
cross-frame view (per [Tool-Pair.md §Cross-frame run
reconstruction](../../../spec/Tool-Pair.md#cross-frame-run-reconstruction--merge-by-dispatch-id)).
In practice, each event bundle lives in exactly one frame (re-frame2 does
not route a single dispatch across multiple frames per [Spec 002
§Routing](../../../spec/002-Frames.md#routing-the-dispatch-envelope)),
so the multi-frame view is interleaved rather than overlapping;
`dispatch-id` ordering renders the correct turn-by-turn timeline.

### Frameless channel

The `:frameless` topic delivers events whose `:rf.trace/dispatch-id`
tag is absent — registration / hot-reload / REPL emits that never
rode a dispatch. The progress payload's load slot is
`:events` (flat); the event-bundle shape doesn't apply (there is
no event to bundle).

Frameless events bypass every ring per [Tool-Pair.md §Frameless
trace events](../../../spec/Tool-Pair.md#frameless-trace-events--live-channel-only)
— they stream live to listeners only. The `:frameless` topic is the
MCP-side surface for that live channel; consumers MUST opt in
explicitly per the framework's "separate channel" ruling
(rf2-g1b2m-B3).

### Cursor staleness on event-bundle streams

Streaming subscriptions are forward-only — there is no cursor to
become stale. The `:rf.mcp/cursor-stale` reason value applies to
the cursor-paginated tools (`trace-window`, `watch-epochs`) whose
cursors key on `:epoch-id` in `epoch-history`. The event-bundle
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
  runtime-side queue cap in UTF-8 BYTES of each event's `pr-str`
  form, the same unit as the wire-boundary cap. Same drop-oldest
  policy; reports
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
  non-zero) and the final summary. Honoured only under
  `--allow-sensitive-reads`; otherwise forced `false` (rf2-c2dtu).
- `elision` (boolean, default `true`) — apply the size/sensitive
  elision walker (`re-frame.core/elide-wire-value`, rf2-vr2hn) to each
  streamed event's payload **values** server-side, before the batch
  crosses the wire — declared-`:large?` slots collapse to
  `{:rf.size/large-elided ...}` markers and declared-`:sensitive?`
  leaves redact to `:rf/redacted`, the same walker `snapshot` /
  `get-path` / `record` use. Pass `false` to stream raw values;
  honoured only under `--allow-sensitive-reads`, otherwise forced
  `true`. **Orthogonal** to `include-sensitive`: that governs
  whole-event drop, `elision` governs per-value walking of the events
  that DO ride — a gate-ON caller wanting full-raw streamed events
  passes BOTH `elision false` and `include-sensitive true`.
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
    "message": "{:sub-id \"...\" :event-bundles [...] :dropped-events 0 :dropped-bytes 0}",
    "_meta": {
      "data": {
        "dropped-events": 0,                    // events evicted this tick
        "dropped-bytes":  0,                    // UTF-8 bytes of pr-str evicted this tick
        "overflow-reason": null                 // ":max-buffered-events" | ":max-buffered-bytes" | null
      }
    }
  }
}
```

`message` is an EDN-printed string carrying the event batch — the
same shape the runtime's `drain-subscription!` returns. The
payload-slot name reflects the topic's wire shape (rf2-mscih):

- `:event-bundles` — vector of event bundles, on event-bundle topics
  (`:trace` / `:fx` / `:error`). See §Event-bundle wire format above.
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
 :rate-dropped   <integer>   ; poll cycles DEFERRED by the per-session rate cap — events stayed queued for a later cycle, not lost (omitted when zero)
 :ticks     <integer>
 :reason    :aborted | :sub-gone | :max-ms-reached | :max-events-reached |
            :rf.error/stream-abuse-detected | :rf.error/drain-failed}
```

`:reason` is `:aborted` when the client cancelled the call,
`:sub-gone` when the runtime's subscription disappeared (typically a
full page reload, or an `unsubscribe` op fired separately),
`:max-ms-reached` / `:max-events-reached` when the caller's
upper-bounds fire, `:rf.error/stream-abuse-detected` when the
session's rolling-window overflow count exceeded
`abuse-overflow-threshold` (rf2-3ijbl — see [§Universal: server
resource controls](#universal-server-resource-controls-streaming-surfaces)),
or `:rf.error/drain-failed` when consecutive nREPL drain rejections
exceeded the internal retry cap (rf2-ajhwbm — a permanently-dead
connection, e.g. shadow-cljs restarted onto a new port mid-session).

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
5. **Dead connection (rf2-ajhwbm)** — the drain eval rejects (nREPL
   round-trip failure) on `max-consecutive-drain-errors` (5)
   consecutive poll cycles in a row. A single rejection is treated as
   a transient hiccup and the loop backs off and retries; only a
   SUSTAINED run of rejections — the signature of a connection that
   is never coming back, e.g. shadow-cljs restarted onto a new port —
   terminates the stream with `:reason :rf.error/drain-failed`. Any
   successful drain in between resets the counter, so isolated blips
   never accumulate toward the cap. Without this cap, `max-ms`
   defaults to 0 (unbounded), so a dead connection would otherwise
   poll (and reject) forever, leaking the concurrent-stream slot for
   the rest of the session.

### Failure modes

- `:reason :unknown-topic` if `topic` is missing or not one of the
  four. Surfaced as `isError: true`.
- `:reason :invalid-filter-edn` if the `filter` arg was supplied as an
  EDN string that failed to parse (rf2-5kbkl). Surfaced as
  `isError: true` WITHOUT touching the nREPL socket or reserving a
  stream slot; the error echoes the offending `:given` string. A
  malformed filter is rejected up front rather than streamed as a
  nonsense filter that would silently match nothing.
- `:reason :runtime-not-preloaded` if the preload hasn't run.
- `:reason :subscribe-failed` on any other failure during subscribe.
- A runtime `subscribe!` that returns `{:ok? false …}` AFTER the stream
  slot is reserved (e.g. `:unknown-topic`, or a future resource-cap /
  frame-resolution refusal) releases the reserved slot and rides back with
  `isError: true` — the failure is never shipped as a success-shaped
  envelope (rf2-yeuqhr), per the universal `:ok? false` rule.
- `:reason :rf.error/concurrent-stream-limit` if the session already
  has `max-concurrent-streams` open subscriptions. Surfaced as
  `isError: true` WITHOUT touching the nREPL socket. The error
  envelope carries `:limit` / `:active` / `:hint` for the operator
  to act on. See [§Universal: server resource controls](#universal-server-resource-controls-streaming-surfaces).

### Diagnostics

When a stream seems quiet or stalled, the `list-streams` tool
below lists every currently-registered streaming subscription with its
queue-depth, drop counts, and overflow-reason — without draining
queues. Use it to confirm the sub is still alive and to check
whether the byte / event budget is evicting under pressure.
(For reactive subscriptions — the per-frame sub-cache — use
`list-subscriptions` instead; it reads a different surface.)

## unsubscribe

Close a streaming subscription out-of-band. Idempotent — closing an
unknown sub-id returns `{:ok? true :sub-id <id> :existed? false}`
rather than an error. Useful when an MCP client wants to stop a
stream without cancelling the `tools/call` directly (e.g. when the
agent host can't propagate cancellation cleanly).

**Args**: `sub-id` (string, **required**), `build` (string).

**Returns**: `{:ok? true :sub-id <id> :existed? <bool>}`.

## list-subscriptions

List the **live reactive subscriptions** materialised in a frame's
per-frame sub-cache — the "what subscriptions are currently active?"
surface. Reads the **same source** the `snapshot` tool's `:sub-cache`
slice reads (`re-frame.subs.tooling/sub-cache-snapshot`, via the
runtime's `sub-cache` fn → the runtime's `sub-cache-info` projection),
so the two never disagree. Routes through the same Tool-Pair sub-cache
read surface listed in the intro, not the streaming-tap registry.

The reactive cache is **ref-counted and live**: an entry appears the
moment a view subscribes and **disappears** when the last consumer
disposes the reaction — so a sub that's been disposed (its view
unmounted, no other subscribers) no longer shows up here.

> **rf2-qicji — wrong-source → right-source.** Before rf2-qicji this
> tool wrapped `re-frame2-pair.runtime/subscription-info`, which reads
> the **streaming-tap registry** (the trace / epoch / fx / error queues
> opened by `subscribe`), NOT the reactive sub-cache. That registry is
> empty unless a streaming `subscribe` is open, so
> `list-subscriptions {frame :rf/default}` returned `{:subs []}` even
> when the frame had live reactive subscriptions — a false-empty
> correctness bug (live evidence: `snapshot :sub-cache` showed
> `[["mounted?"]]` for the same frame while this tool said `[]`). The
> fix repoints `list-subscriptions` at the reactive sub-cache; the
> streaming-tap diagnostic kept its behaviour and moved to the
> accurately-named [`list-streams`](#list-streams) tool. No back-compat
> shim (pre-alpha).

**Args** (all optional):

- `frame` (string, optional) — the frame to read. Accepts bare names
  (`"rf/default"`) or EDN-shaped strings (`":rf/default"`). Defaults to
  the operating frame (per the runtime's frame resolution, shared with
  `snapshot` / `get-path`); a multi-frame session with no selection
  returns `{:ok? false :reason :ambiguous-frame}` rather than silently
  reading `:rf/default`.
- `include-values` (boolean, optional, default `false`) — when `false`,
  only the query-vectors ride the wire (the cheap "what's subscribed"
  read); when `true`, each entry also carries `:value` (the current
  deref) and `:ref-count`.
- `build` (string, optional, default `"app"`) — shadow-cljs build id.

**Returns** (`:include-values false`, the default):

```clojure
{:ok?   true
 :frame :rf/default
 :count <integer>
 :subs  [<query-v> ...]}          ; sorted by pr-str; stable across calls
```

**Returns** (`:include-values true`):

```clojure
{:ok?   true
 :frame :rf/default
 :count <integer>
 :subs  [{:query-v   <query-vector>
          :value     <current-deref>
          :ref-count <integer>}
         ...]}
```

`:subs` is an empty vector when nothing is subscribed in the frame —
never `:ok? false` for the empty case. Genuine emptiness is the runtime's
OWN `{:ok? true … :subs []}` MAP (a real answer). The query-vectors are
sorted (by `pr-str`) so the listing is stable across calls.

A **blank / non-map** eval is NOT emptiness (rf2-21vvfs): it means the
runtime did not answer (the browser tab closed / navigated in the narrow
race between the liveness re-check and the sub-cache drain). That degraded
read surfaces as `{:ok? false :reason :unexpected-shape :value …}` with
`isError: true` — never a fabricated `{:ok? true :subs []}` masking a dead
read as "no subscriptions".

`list-subscriptions` opts INTO the per-session response cache (it reads
the live reactive sub-cache, a pure function of frame state, just like
`snapshot`). The `:value` slot (under `include-values true`) is the
verbatim current deref; per the [§Universal: size-elision][1] /
[Tool-Pair §Direct-read privacy posture for sub-cache][2] contract a
production read of a sensitive / large value follows the same posture
the `snapshot :sub-cache` slice does (the default-off `--allow-sensitive-reads`
gate applies to verbatim values surfaced off-box).

`:reason :runtime-not-preloaded` if the preload hasn't run;
`:reason :ambiguous-frame` in a multi-frame session with no selected
frame; `:reason :unexpected-shape` on a blank/non-map degraded eval (see
above); `:reason :list-subscriptions-failed` (with `:message`) on any
other failure. All ride `isError: true` per the universal `:ok? false`
rule (rf2-21vvfs).

[1]: #universal-size-elision-on-app-db-slots
[2]: ../../../spec/Tool-Pair.md#how-ai-tools-attach

## list-streams

Diagnostic listing of currently-registered **streaming-tap**
subscriptions — the "what streams are open right now?" surface (the
streaming diagnostic [`list-subscriptions`](#list-subscriptions)
formerly carried, before rf2-qicji repointed that tool at the reactive
sub-cache). Pure read over the runtime's `subscriptions` atom — the
trace / epoch / fx / error / frameless queues opened by `subscribe`
and torn down by `unsubscribe`. **Does NOT drain any queues** and does
NOT alter the stream contents that `subscribe` will see on its next
tick. Wraps the `re-frame2-pair.runtime/subscription-info` runtime fn
directly (one cheap nREPL eval — no `eval-cljs` round-trip needed; the
runtime fn keeps its historical name).
Useful when a streaming probe seems to have gone quiet: confirm the
sub is still registered, inspect `:queue-depth` / `:queue-bytes` for
evidence of a stuck consumer, or check `:overflow-reason` for budget
pressure that needs tuning on the next `subscribe` call.

Unlike the other read tools, `list-streams` reads the runtime's
internal subscription registry rather than routing through one of the
Tool-Pair primitives listed in the intro — its peer surface is the
streaming registry that `subscribe` / `unsubscribe` mutate, not the
app-db-value / epoch-history / trace-buffer / sub-cache surfaces.

> **NOT the reactive sub-cache.** For "what reactive subscriptions are
> currently active in a frame?" use
> [`list-subscriptions`](#list-subscriptions) (or `snapshot :sub-cache`)
> — that reads the live per-frame reactive cache. `list-streams` reads
> the MCP streaming-tap registry, a different concept entirely.

**Args** (all optional):

- `topic` (string, optional) — narrow to one topic. One of `"trace"`,
  `"epoch"`, `"fx"`, `"error"`, `"frameless"`.
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
         :topic           :trace | :epoch | :fx | :error | :frameless
         :filter          <filter-map-as-supplied-to-subscribe>
         :queue-depth     <integer>       ; events buffered server-side
         :queue-bytes     <integer>       ; UTF-8 bytes of pr-str buffered server-side
         :dropped-events  <integer>       ; cumulative drops by event-budget
         :dropped-bytes   <integer>       ; cumulative drops by byte-budget
         :overflow-reason :max-buffered-events | :max-buffered-bytes | nil
         :created-at      <ms-since-epoch>}
        ...]}
```

`:subs` is an empty vector when no streams are open (or when the
filters match nothing) — never `:ok? false` for the empty case. Genuine
emptiness is the runtime's OWN `{:ok? true :subs []}` MAP. A **blank /
non-map** eval is NOT emptiness (rf2-21vvfs): on the very tool that
diagnoses a dead / quiet stream, a degraded read (the browser tab closed
mid-race) surfaces as `{:ok? false :reason :unexpected-shape :value …}`
with `isError: true` — never a fabricated `{:ok? true :subs []}` reporting
a false-clean "zero streams". A
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
that applies. `list-streams` does NOT carry sensitive event
bodies; only registration metadata crosses the wire.

`:reason :runtime-not-preloaded` if the preload hasn't run;
`:reason :unexpected-shape` on a blank/non-map degraded eval (see above);
`:reason :list-streams-failed` (with `:message`) on any other
failure. All ride `isError: true` per the universal `:ok? false` rule
(rf2-21vvfs).

## get-stream-controls

Report the **server-side** streaming resource-control state (rf2-a0kxsb)
— the "why was my stream denied / why is it quiet / why did it
terminate?" diagnostic. Where [`list-streams`](#list-streams) reads the
**runtime** streaming-tap registry (what trace/epoch/fx streams are open
in the browser), `get-stream-controls` reports what the **server's
resource controller** currently believes: the effective caps, the active
slot count versus the limit, the token-bucket pressure, and the
abuse-window count versus the threshold (see
[§Universal: server resource controls](#universal-server-resource-controls-streaming-surfaces)).

Reads the resource-control atoms **in-process** — **no nREPL
round-trip** — so it answers even when the runtime is down (exactly when
an operator is most likely diagnosing a denied or stalled stream). It is
the only read tool whose `:openWorldHint` is `false`: the state is
server-local, the read never leaves the process.

Because it needs no connection, the server dispatches it at the
**pre-connection** boundary (rf2-6amhbt) — BEFORE `ensure-connection!`,
symmetric with the unknown-tool (rf2-4mc6q1) and gated-write
(rf2-wz66k7) pre-connection guards. So on a stock / degraded install with
no nREPL port it returns its `:ok? true` payload rather than the shared
`:nrepl-port-not-found` discovery error — the "answers even when the
runtime is down" claim holds at the real MCP boundary, not merely at the
tool-body layer. `get-re-frame2-pair-instructions` shares this
closed-world pre-connection dispatch.

**Privacy**: the payload is control state only — caps, counts, bucket
pressure. No event payloads, no app-db data, so it is unconditionally
safe (no `--allow-sensitive-reads` gate).

**Args** (all optional):

- `build` (string, optional) — accepted for shape uniformity; ignored
  (the state is server-local, not per-build).

**Returns**:

```clojure
{:ok?    true
 :config {:max-concurrent-streams   10
          :max-events-per-sec       100
          :abuse-overflow-threshold 50
          :abuse-window-ms          10000}
 :concurrent-streams {:active       <integer>
                      :limit        <integer>
                      :at-capacity? <bool>}     ; true ⇒ next subscribe is denied
 :rate-limit {:capacity     <integer>           ; = :max-events-per-sec
              :tokens       <float>             ; tokens remaining in the bucket
              :initialized? <bool>              ; false until the first poll cycle
              :throttling?  <bool>}             ; true ⇒ < 1 token, next cycle defers
 :abuse-window {:count     <integer>            ; overflows in the rolling window
                :threshold <integer>            ; = :abuse-overflow-threshold
                :window-ms <integer>            ; = :abuse-window-ms
                :tripped?  <bool>}              ; true ⇒ count exceeded threshold
 :cross-check <hint-string>}
```

**Cross-check with `list-streams`**: compare `:concurrent-streams
:active` here against the `list-streams` row count. They SHOULD agree.
A server `:active` with NO matching `list-streams` row signals a
**leaked server slot** (a stream that died without releasing its slot);
the reverse signals a **stale runtime subscription**. `get-stream-controls`
reports the server count and the cross-check hint but does NOT call the
runtime itself (that would re-introduce the nREPL dependency it exists
to avoid) — run `list-streams` to complete the cross-check.

The token bucket is **lazily initialised** on the first poll cycle, so
on a fresh session `:rate-limit :initialized?` is `false` and `:tokens`
reports the full capacity (the lazy-init state) rather than a confusing
`nil`.

Always `:ok? true` — there is no failure mode for an in-process atom
read.

## handler-meta

Return the registration-metadata map for a registered handler — the
"where is `:user/login` defined?" / "what does sub `:current-user` look
like?" surface (rf2-pctf8 / rf2-cibp8). Direct introspection on the
registrar; no wide-authority `eval-cljs` round-trip needed.

Source-coord keys (`:ns` / `:line` / `:column` / `:file`) are merged
flat onto the top-level result per
[`spec/Spec-Schemas.md` §`:rf/source-coord-meta`](../../../spec/Spec-Schemas.md#rfsource-coord-meta).
The wire pipeline (rf2-cibp8) decorates a usable source-coord shape
with an `:rf.mcp/source-uri` string so the AI host can render a clickable
jump-to-editor link.

**Source as data — `:rf.handler/source`** (rf2-1tyxh). A coordinate tells
an agent where a handler lives; on `kind=event` the response carries the
handler itself. A dev build's registration metadata includes
`:rf.handler/source` — the `pr-str` of the whole `(reg-event …)` form as
registered, per
[`spec/009-Instrumentation.md` §`:rf.handler/source`](../../../spec/009-Instrumentation.md#rfhandlersource--debug-gated-handler-form-source-capture).
Read it before resolving `:file` / `:line` against a checkout: the agent
may not have one, and even when it does, the runtime's answer is the one
that is actually loaded. The slot arrives by **passthrough** —
`(re-frame2-pair.runtime/registrar-describe kind id)` drops the live
`:handler-fn` and passes every other key through, and this tool
whitelists nothing — so it needs no per-key wiring here. Three limits
bound it:

- **Dev-only.** Capture is DEBUG-gated at both the macro and the
  registrar, so under `:advanced` + `goog.DEBUG=false` neither the slot
  nor the source bytes reach the bundle (JVM builds are always-on). A
  production runtime answers with coordinates and no body.
- **`reg-event` scope.** No other kind on the enum above (`sub`, `fx`,
  `cofx`, `interceptor`, `view`, `frame`, `route`, `flow`, `head`,
  `error-projector`, `resource`, `mutation`, `resource-scope`) carries the
  slot — fall back to `:rf.mcp/source-uri` and the filesystem
  there. Machine guards and actions do carry the same key, but
  through the framework's derived `:machine-guard` / `:machine-action`
  handler-meta kinds (Spec 005), which this tool's `kind` enum does not
  expose.
- **Current, not historical.** The value is read live off the registry
  entry that exists *now*. After a hot reload it describes the handler as
  re-registered, not the one that ran in an older epoch — so pair it with
  `handler-fn-hash` (or `tail-build`'s probe) when the question is
  whether the code changed under you.

**Args**: `kind` (string, **required** — one of `event` / `sub` /
`fx` / `cofx` / `interceptor` / `view` / `frame` / `route` / `flow` / `head` /
`error-projector` / `resource` / `mutation` / `resource-scope` /
`machine`), `id` (string, **required** — EDN-encoded
keyword or composite vector), `frame` (string, optional — a frame id keyword;
the EP-0023 forward direction, see below), `build` (string, optional).

**No realm coordinate** (EP-0023 / EP-0024). The public model is
`image -> frame -> event stream`. The EP-0013 realm / app-value / install
substrate was **deleted in full** (no public facade under EP-0023, removed
outright by EP-0024 — there is no `re-frame.realm` namespace; see framework
[`spec/Spec-Schemas.md` §`:rf/realm`](../../../spec/Spec-Schemas.md)). There is
no `realm` arg: a registrar query is either the bare **process-global**
registrar read or, with `frame`, the **frame-targeted** read below.

**Frame-targeting — the forward direction** (EP-0023, rf2-srobm0). A frame's
inspectable registration set is its **resolved image generation**: the same
`(kind, id)` may resolve **differently per frame** (two frames running
different images each resolve their own descriptor). The optional `frame` arg
re-keys the lookup through **that frame's running image generation** rather
than the process-global registrar — routing through the per-frame runtime fn
`(re-frame2-pair.runtime/frame-registrar-describe frame kind id)`, which
consumes the **public** facade read `(re-frame.core/handler-meta {:frame f
:kind k :id id})` (rf2-wkw8na). The result carries the resolved descriptor's
`:rf.provenance/ns` + inline/image + `:standard` facts plus a normalized
`:rf.image/coordinate` rollup naming **which source won** the resolution:

```clojure
{:source :registered :ns "my.app.events"}      ; a registered descriptor
{:source :inline :image :app/img :inline [..]} ; an image inline section
{:source :standard}                            ; a framework standard
```

`frame` is **not valid with `kind=machine`** (machines are not in the image
generation resolver — they derive from `:event` handlers; Spec 005), and
that combination returns `{:ok? false :reason :frame-unsupported-for-machine
…}`. Omit `frame` for the byte-identical process-global-registrar path.
`list-subscriptions` and `describe-image` are the per-frame siblings.

**Supported kinds**: the closed v1 registrar set (per Spec 001
§Registry model), including the three resources-artefact kinds
`:resource` / `:mutation` / `:resource-scope` (EP-0016 / rf2-f8s9g6)
and the `:interceptor` kind (EP-0022 — `reg-interceptor`; its meta
surfaces the registered `:rf/interceptor-descriptor`).
App-db schemas are **not** a registrar kind
(rf2-cq1ak) — their metadata lives in the schemas artefact's per-frame
side-table, queried via `rf/app-schemas` / `rf/app-schema-meta-at`.
The fourteen registrar kinds route through
`(re-frame2-pair.runtime/registrar-describe kind id)`. For a
`:resource-scope` the meta surfaces the named scope resolver's declared
`:inputs` map + `:whole-db?` cost (the EP-0016 disposition-2
inspectability promise — which app facts decide a cache scope);
`:resource` / `:mutation` surface their scope policy + declared cache
consequences. `registrar-describe`'s `strip-fns` (rf2-f8s9g6) replaces
the nested handler fns (`:request` / `:tags` / `:invalidates` /
`:populates` / `:resolve`) with the readable `:rf/fn` sentinel so the
meta is EDN-clean on the wire. The `:machine`
kind routes through `(re-frame.core/machine-meta id)` instead —
machines are registered as `:event` handlers carrying `:rf/machine?
true` (Spec 005 §Querying machines), and `machine-meta` unwraps that
slot to surface the spec.

**Returns** on a hit:

```clojure
{:ok?              true
 :kind             :event | :sub | :fx | :cofx | :interceptor | :view | :frame |
                   :route | :flow | :head | :error-projector |
                   :resource | :mutation | :resource-scope | :machine
 :id               <registered-id>
 :ns               my.app.user
 :line             42
 :column           1
 :file             "src/my/app/user.cljs"
 :rf.mcp/source-uri    "file:///abs/path/to/src/my/app/user.cljs#L42"
 :doc              "<docstring or nil>"
 :tags             #{...}                 ; if any
 :rf.handler/source "(reg-event :user/login (fn [{:keys [db]} [_ creds]] …))"
                                          ; kind=event, dev builds only
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
- `{:ok? false :reason :frame-unsupported-for-machine :frame <f> :kind :machine :hint "..."}` — `frame` given with `kind=machine` (machines are not in the image generation resolver).
- `{:ok? false :reason :handler-meta-failed :message "..."}` — runtime threw (including the facade's `:rf.error/frame-no-generation` when `frame` names no live frame carrying a generation).

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
`frame` (string, optional — a frame id keyword; the EP-0023 forward
direction), `build` (string, optional).

**Frame-targeting** (EP-0023, rf2-srobm0): same contract as `handler-meta`'s
`frame` arg. The optional `frame` arg enumerates **only the ids that frame's
running image generation carries** for the kind — routing through
`(re-frame2-pair.runtime/frame-registrar-list frame kind)`, which consumes
the public `(re-frame.core/registrations {:frame f :kind k})` read — its keys are the registered ids (rf2-i4hk4b retired the `handler-ids` projection it used to call).
This is the **selected universe** that frame actually runs, not the
process-wide namespace-union the flat registrar holds. `frame` is not valid with
`kind=machine` (`:frame-unsupported-for-machine`). The resolved frame is
stamped onto the response as `:frame`. Omit it for the process-global-registrar
path.

**No realm coordinate** (EP-0023 / EP-0024): same contract as `handler-meta`.
There is no `realm` arg — the EP-0013 realm substrate was deleted in full
(there is no `re-frame.realm` namespace). An enumeration is either the bare
**process-global** registrar read or, with `frame`, the frame-targeted read.

**Supported kinds**: same closed v1 registrar set as `handler-meta`
(including the resources-artefact `:resource` / `:mutation` /
`:resource-scope` kinds — EP-0016 / rf2-f8s9g6 — and the `:interceptor`
kind — EP-0022; per rf2-cq1ak app-db
schemas are not a registrar kind — use `rf/app-schemas` for those; plus
the virtual `:machine` kind). The fourteen
registrar kinds lift the id vector off the registrar's per-kind
map via `(re-frame2-pair.runtime/registrar-list kind)` —
`list-handlers {kind "resource-scope"}` enumerates a resources app's
named scope resolvers, `list-handlers {kind "interceptor"}` the ids
registered via `reg-interceptor`. The
`:machine` kind wraps `(re-frame.core/machines)` — every event
handler flagged `:rf/machine? true` (Spec 005 §Querying machines).

**Returns**:

```clojure
{:ok?   true
 :kind  :event | :sub | :fx | :cofx | :interceptor | :view | :frame |
        :route | :flow | :head | :error-projector |
        :resource | :mutation | :resource-scope | :machine
 :ids   [<id> ...]
 :count <integer>}
```

The id vector is **sorted** (string / keyword / symbol ordering) so
the list shape is stable across calls. Empty `:ids` returns
`{:ok? true :kind k :ids [] :count 0}` — never `:ok? false` for the
empty case.

**Error envelopes**:

- `{:ok? false :reason :invalid-kind :kind <raw> :hint "..."}` — unrecognised / missing `kind` arg.
- `{:ok? false :reason :frame-unsupported-for-machine :frame <f> :kind :machine :hint "..."}` — `frame` given with `kind=machine`.
- `{:ok? false :reason :list-handlers-failed :message "..."}` — runtime threw (including `:rf.error/frame-no-generation`).

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

## describe-image

Describe the **image generation** a frame is running — the EP-0023
**Use-Case 7** read (Ref-Plan item 17, rf2-srobm0). Answers "what behaviour
does **this** frame run, and where did each piece come from?" in one
round-trip. A frame runs a composed **image** (selected registrations from
one-or-more namespaces / inline sections, plus framework standards); the same
`(kind, id)` can resolve differently per frame, so the **selected universe** a
frame actually runs is what an agent driving it should see — not the
process-wide registrar union.

Routes through the runtime preload's `describe-image`, which reads **only** the
public facade `(re-frame.core/frame-generation frame)` read (rf2-wkw8na).
EP-0023 forbids tools from consuming `re-frame.live-frame` /
`re-frame.image-assembly` internals directly; the facade re-surfaces the
behaviour through the public read.

**Args** (all optional): `frame` (string — a frame id keyword; defaults to the
operating frame), `include-ns` (boolean, default false — gates the
per-registration provenance map), `build` (string).

Frame resolution mirrors every read op: omit `frame` to use the operating
frame; a multi-frame session with no selection returns
`{:ok? false :reason :ambiguous-frame …}` rather than silently reading
`:rf/default`.

**Returns**:

```clojure
{:ok?      true
 :frame    <frame-id>
 :images   [<image-id> ...]        ; the composed image ids
 :kinds    [<kind> ...]            ; registrar kinds present (sorted)
 :counts   {<kind> N ...}          ; selected-registration count per kind
 :registrations {[<kind> <id>] <coordinate> ...}}  ; only with :include-ns true
```

> EP-0026 (rf2-dlvmpc) removed image-declared host capabilities end-to-end —
> there is no `:rf.gen/requires`, so `describe-image` no longer returns a
> `:requires` capability set.

The `:registrations` slot (only with `include-ns true`) maps each selected
`(kind, id)` to its provenance/standard **coordinate** — `{:source
:registered :ns "..."}` / `{:source :inline :image <id> :inline [..]}` /
`{:source :standard}` — so an agent sees **which source won** each
resolution. It is OFF by default because the full resolver can be large;
`:counts` plus a per-kind `list-handlers {:frame ...}` drill cover the common
case.

A `(kind, id)` simply being **absent** from the resolver is a
**missing-registration** — drill `:counts` or `list-handlers {:frame ...}` to
see what each frame actually resolves. (EP-0026 retired image-declared host
capabilities, so there is no longer a missing-**capability** discriminator on
this read.)

**Error envelopes** (all `isError: true`, per the §"Every `:ok? false`
response is `isError: true`" rule — rf2-01jwrq):

- `{:ok? false :reason :ambiguous-frame …}` — multi-frame session, no `frame` and no session pin.
- `{:ok? false :reason :describe-image-failed :message "..."}` — runtime threw (including `:rf.error/frame-no-generation` when an explicit `frame` names no live frame carrying a generation).
- `{:ok? false :reason :unexpected-shape :value …}` — the runtime eval came back blank/non-map (a degraded read — dropped WebSocket / navigated tab).

Because `describe-image` is `:cacheable?` and the response cache bypasses
`isError` results, keeping the `:ambiguous-frame` refusal `isError: true`
also keeps it OUT of the cache — a transient ambiguous-frame can never be
cached and mask a later valid read.

**Drill-down**: `describe-image` is the per-frame overview; drill a specific
`(kind, id)` with `handler-meta {:frame … :kind … :id …}` for the full
per-frame registration metadata, or enumerate a kind with `list-handlers
{:frame … :kind …}`.

**Source**: rf2-srobm0.

## set-operating-frame / reset-operating-frame / get-operating-frame

The three operating-frame ops the [Tool-Pair contract][tsobl] mandates
(§Tool-surface obligations) for any pair-shaped tool surface. They are
the MCP-side surfacing of the runtime's **session frame pin** — tier 2
of the [operating-frame resolution table][resolve] — and are the escape
from the tier-4 `:ambiguous-frame` refusal a multi-frame app otherwise
traps an agent in.

[resolve]: ../../../spec/Tool-Pair.md#operating-frame--multi-frame-resolution

### Why these ship (rf2-zomfq)

re-frame2 is multi-frame (Spec 002). Every frame-targeted op (`dispatch`,
`snapshot`, `get-path`, `subscribe`, `list-subscriptions`, …) resolves an
*operating frame*: explicit per-call `frame` (tier 1) → **session pin
(tier 2)** → sole-registered frame (tier 3) → nil (tier 4, ambiguous).
When two-plus frames are registered and the call omits `frame` and no
session pin is set, resolution lands at tier 4 and the op REFUSES with
`{:ok? false :reason :ambiguous-frame}` rather than guess (a write that
lands in the wrong frame is unrecoverable without `restore-epoch`).

Before rf2-zomfq the runtime exposed `select-frame!` / `current-frame` /
`frames-list`, but **no MCP tool wired them onto the wire** — so tier 2
was unreachable from the tool surface and a multi-frame agent had to
thread `frame` through every single call forever, defeating the
implicit-until-reset UX the contract designed. These three ops close that
gap.

### set-operating-frame

Pin the session's operating frame — the **public address** is the frame id
(EP-0023's `image -> frame -> event stream` model targets a frame; the old
EP-0013 `(realm, frame)` two-part address collapses to one public frame-id
space).

**Args**: `frame` (string, **required** — bare `"stories"` or EDN-shaped
`":stories"`), `build` (string, optional). An absent `frame` returns
`:missing-frame`.

Per Tool-Pair §Tool-surface obligations, set **validates** that `frame`
names a currently-registered frame; an unknown frame returns the
`isError` envelope `{:ok? false :reason :no-such-frame :frame <id>
:frames [...]}` (the registered list rides along so the agent can
retarget). Validation and the pin write are one eval form — the
membership check reads `(re-frame2-pair.runtime/frames-list)` and, on a
hit, calls `(select-frame! <id>)` and returns the fresh map; no
check-then-act race across round-trips.

There is **no realm pin and no realm coordinate**. The EP-0013 realm
substrate was deleted in full (no public facade under EP-0023, removed outright
by EP-0024 — there is no `re-frame.realm` namespace; see framework
[`spec/Spec-Schemas.md` §`:rf/realm`](../../../spec/Spec-Schemas.md)). The
operating read reports the frame-only addressing surface (see
§get-operating-frame).

A **reserved `:rf/*` tool frame** (Xray's `:rf/xray`, an SSR slot — see
Tool-Pair §Reserved tool frames) is **refused as an operating-frame
pin** with `{:ok? false :reason :reserved-tool-frame :frame <id>}`,
short-circuiting before any nREPL round-trip (rf2-wdxyx3 finding 1). A
reserved frame is a devtool surface, not the app the operator pairs
against; were it pinnable, the runtime's `current-frame` resolver would
return it at tier 2 and a later no-`:frame` root read would resolve a
wholesale read through it — re-opening the context-window overflow the
wholesale-read guard closes. Closing the pin removes the bypass at its
source: a reserved frame is never the operating frame. Targeted (sliced)
reads of a tool frame remain available via the per-call `:frame` arg.
`:rf/default` is an app frame and is pinnable normally.

**Returns** the resolved `frames-list` map on success (the frame-only
addressing surface — no realm coordinate):

```clojure
{:ok?             true
 :frames          [<frame-id> ...]   ;; all registered (public address surface)
 :app-frames      [<frame-id> ...]   ;; tool frames removed
 :selected        <frame-id>         ;; the just-pinned frame (tier-2 pin)
 :operating       <frame-id>}        ;; full resolution — now the pinned frame
```

**Error envelopes** (all `isError: true`):

- `{:ok? false :reason :missing-frame :hint "..."}` — `frame` not supplied.
- `{:ok? false :reason :reserved-tool-frame :frame <id> :hint "..."}` — `frame` is a reserved `:rf/*` tool frame (refused before nREPL; rf2-wdxyx3).
- `{:ok? false :reason :no-such-frame :frame <id> :frames [...] :hint "..."}` — `frame` not registered.
- `{:ok? false :reason :set-operating-frame-failed :message "..."}` — runtime threw.

### reset-operating-frame

Clear the session's frame pin.

**Args**: `build` (string, optional). No `frame`.

Routes through `(select-frame! nil)` then re-reads the `frames-list` map so
the returned shape reflects the cleared pin. After reset, subsequent ops
resolve at tier 3 / 4 again. Idempotent — resetting when nothing is pinned is a
no-op that returns the same map.

**Returns**:

```clojure
{:ok?       true
 :frames    [<frame-id> ...]
 :selected  nil                ;; frame pin cleared
 :operating <frame-id|nil>}    ;; tier-3 sole-frame, or nil (ambiguous)
```

### get-operating-frame

Inspect the operating frame — the **read** op. Routes through
`(re-frame2-pair.runtime/frames-list)`, the same accessor `discover-app`
consults, so the two never disagree.

**Args**: `build` (string, optional).

**Returns** the normative shape (per [Tool-Pair §Tool-surface
obligations][tsobl] — `:frames` / `:selected` / `:operating`, the public
frame addressing surface). There is no realm coordinate (the EP-0013 realm
substrate was deleted in full under EP-0024 — see framework
[`spec/Spec-Schemas.md` §`:rf/realm`](../../../spec/Spec-Schemas.md)):

```clojure
{:ok?        true
 :frames     [<frame-id> ...]   ;; (rf/frame-ids) — all registered (public address)
 :app-frames [<frame-id> ...]   ;; tool frames removed
 :selected   <frame-id|nil>     ;; tier-2 session frame pin (nil = unset)
 :operating  <frame-id|nil>}    ;; full resolution (nil = AMBIGUOUS)
```

`:operating nil` means ambiguous: two-plus app frames and no pin, so a
frame-targeted op without a per-call `frame` WILL refuse. The shape lets a
caller render "you have pinned X" (`:selected`) and "writes will go to X"
(`:operating`).

### How they escape `:ambiguous-frame`

A frame-consuming op with no per-call `frame` reads the session pin via
the runtime's `current-frame` resolver. `set-operating-frame` writes that
pin; from then on `current-frame` returns the pinned id at tier 2 and the
op proceeds against it instead of refusing. `reset-operating-frame`
clears the pin, returning the session to the tier-3/4 default posture.
The pin lives in the runtime preload's `selected-frame` atom — the SAME
atom every frame-consuming op consults — so set / get / reset can never
disagree about where it lives.

### Annotations

`set-operating-frame` / `reset-operating-frame` carry
`:idempotentHint true` + `:openWorldHint true` (they write *session*
state over nREPL — NOT `:readOnlyHint`, NOT `:destructiveHint`: a wrong
pin is corrected by another set / reset, never an irreversible app
effect). `get-operating-frame` is a pure read on the standard
idempotent-read-only annotation set.

**Source**: rf2-zomfq.

## get-re-frame2-pair-instructions

Agent-onboarding text (rf2-fnpqg). Returns inline prose: a
`## Routing rules` section of six rules naming which tool to reach
for at each decision, then the conventions — the EDN posture, the
`:origin :pair` tagged-mutation convention, the streaming
`subscribe` semantics, and the wire-boundary pipeline (precheck →
elision → diff-encode → dedup → cap).

**It does not enumerate the tools** (rf2-wyza). It used to — a
33-entry `## Tool catalogue` that was 75% of the blob and the only
section indexed by the tool count, leaving 112 tokens of margin under
the wire cap. That enumeration duplicated the `tools/list`
descriptors an agent already holds from the handshake, and a name
that misses is answered live by the `:unknown-tool` hint, which folds
`registry/tool-names` into the error. What no per-tool descriptor can
carry is CROSS-tool judgement — `read-sub`'s own description does say
"PREFER this over raw eval-cljs", but that preference sits inside its
~2,400-character paragraph, invisible until you already chose to read
about `read-sub`. The routing rules are that missing global
first-contact index, and nothing else. The full per-tool reference is
this document.

Mirrors story-mcp's `get-story-instructions` — agent hosts call
this at session start to orient before the first real op. No nREPL
round-trip; the text is a `def` in the compiled `.js` bundle so
the call is one MCP frame and zero socket bytes. The cache layer
(`cache.cljs`) marks this tool `cacheable? true` since the text is
a pure-data function of the bundle.

Like `get-stream-controls`, it is a **closed-world** tool: the server
dispatches it at the **pre-connection** boundary (rf2-6amhbt), BEFORE
`ensure-connection!`, so an agent orienting on a fresh / degraded session
with no shadow build running gets the onboarding text rather than a
`:nrepl-port-not-found` discovery error.

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
**Adding a tool does not oblige an edit there** — that obligation went
with the catalogue. Edit it when a routing JUDGEMENT changes: a new
tool that supersedes an older route, or a stated preference that
turned out to be wrong. `test/re_frame2_pair_mcp/onboarding_routing_test.cljs`
guards the one failure the prose can still cause — a rule naming a
tool that is not registered, which the agent would then call and get
`:unknown-tool` for.

**The prose has a hard budget, and it is the wire cap** (rf2-3dmj). The
response egresses through the universal wire-boundary cap like any
other, so once it exceeds `default-max-tokens` the entire payload is
replaced by the `{:rf.mcp/overflow ...}` marker — the first call an agent
makes on a fresh session returns no onboarding text at all, and the
marker's "re-call with narrower args" hint cannot help, because this
tool takes no narrowing args. So the budget is enforced where the prose
is written: `test/re_frame2_pair_mcp/instructions_budget_test.cljs` runs
the production `cap/apply-cap` over the real result and fails with the
budget, the current usage and the remaining margin named in the message.

Note the exchange rate when estimating headroom. `wire/ok-text` writes
the same payload into BOTH `:content[0].text` (the `pr-str` EDN) and
`:structuredContent` (the JSON projection), and the cap counts both —
correctly, since both ride the wire. The prose is therefore measured
**twice**: one character of prose costs ~0.5 tokens of budget, so the
effective prose budget is about half the nominal cap. Sizing a draft
from the raw character count of `instructions-text` is wrong by a factor
of two.
