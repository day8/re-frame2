# `re-frame2-mcp-base` — shared primitives for the MCP triplet

`day8/re-frame2-mcp-base` is the CLJC library that holds the
genuinely-cross-cutting primitives consumed by every MCP server in
the re-frame2 tool triplet:

- `tools/pair2-mcp/` (CLJS / Node — runs over nREPL to a browser app)
- `tools/story-mcp/` (JVM / Clojure — bridges to `tools/story/`)
- `tools/causa-mcp/` (planned; spec lives at `tools/causa-mcp/spec/`)

The factoring landed under [rf2-vw4sq][bead]. The per-namespace
contract expansion (rf2-643ia) brings every namespace below up to
the "one-shot-able from spec" bar.

[bead]: https://github.com/day8/re-frame2/issues/rf2-vw4sq

## What lives here

Seven namespaces under `re-frame.mcp-base.*`:

| ns           | Lines | Surface                                                         |
|--------------|-------|-----------------------------------------------------------------|
| `vocab`      | 228   | `:rf.mcp/*` + `:rf.size/*` marker keys + envelope slots + JSON-RPC error codes. |
| `sensitive`  | 117   | spec/009 §Privacy default-suppress filter (`sensitive-event?`, `strip-sensitive`, `scrub-snapshot`). |
| `elision`    |  60   | Wire-boundary `:rf.size/large-elided` walker (`count-elided-markers`, rf2-9fz64). |
| `args`       | 127   | Argument coercion helpers (`parse-boolean`, `parse-positive-int`, `parse-keyword`, `parse-mode`, …). |
| `diff-encode`| 286   | Path-keyed structural diff for epoch `:db-after` slots (rf2-1wdzp). |
| `overflow`   |  60   | Overflow-marker payload SHAPE builder + per-tool hint table (rf2-rvyzy). |
| `cap`        | 192   | Wire-boundary token-budget cap pipeline + `ResultIO` protocol (rf2-eyelu). |

All `.cljc`, so consumers compile them under their own platform —
pair2-mcp's shadow-cljs node build, story-mcp / causa-mcp's JVM
classpath. The library's `deps.edn` carries only
`org.clojure/clojure`; no consumer-side runtime deps.

## What deliberately does NOT live here

The bead's scope holds the line at primitives that are truly
identical across the triplet's wire / privacy / size surfaces.
Three categories stay consumer-side:

1. **Wire transport.** story-mcp uses Cheshire for JSON-RPC over
   stdin/stdout; pair2-mcp uses the npm `@modelcontextprotocol/sdk`'s
   stdio transport. The framing is different by language; there's
   nothing useful to share here.

2. **Cursor base64 codec.** The cursor *shape* (`{:v 1 :after-id ...
   :ms ... :until-ms ... :frame ...}`) is conventional — but
   pair2-mcp uses `js/Buffer.from … "base64"` and a JVM consumer
   would use `java.util.Base64`. Factoring the codec means a
   protocol-shaped helper per platform; not worth the indirection
   until causa-mcp lands and demonstrates the third call site.

3. **Tool registries.** Each MCP server's tool catalogue is domain-
   specific. The base provides building blocks; it does NOT
   prescribe how the registry is shaped.

## `vocab` — wire-vocabulary constants

The `vocab` ns is the single source of truth for the marker keys an
agent learns once and recognises across every MCP it talks to. A
rename here is a wire-protocol break; the cross-MCP conformance gate
under `tools/mcp-conformance/wire-vocab/` fails loud when that
happens.

### Two namespaces + envelope slots

`:rf.mcp/*` — per-tool wire-mechanism markers. Owned by the MCP
servers; not part of the framework runtime vocabulary.

`:rf.size/*` — size-elision markers. Owned jointly with the
framework's `rf/elide-wire-value` walker (Conventions §Reserved
namespaces; Spec 009 §Size elision in traces).

Unqualified envelope slots — `:dropped-sensitive`, `:elided-large` —
are per-call scalar counters summarising the walker's suppression
count. Per Conventions §Cross-MCP indicator-field vocabulary and
Spec 009 §Size elision in traces (Indicator field on tool responses,
MUST-level per rf2-2499j).

### Marker catalogue (`:rf.mcp/*`)

| Var | Key | Shape | Source bead |
|---|---|---|---|
| `overflow-key` | `:rf.mcp/overflow` | `{:limit :reached :token-count N :cap-tokens M :tool "…" :hint "…"}` | rf2-rvyzy |
| `dedup-table-key` | `:rf.mcp/dedup-table` | `{<cache-map>}` (de-dupe library) | rf2-obpa9 |
| `diff-from-key` | `:rf.mcp/diff-from` | Slot pointer keyword (`:db-before`) | rf2-1wdzp |
| `cursor-stale-reason` | `:rf.mcp/cursor-stale` | Error-result `:reason` value | rf2-kbqq3 |
| `cache-hit-key` | `:rf.mcp/cache-hit` | `{:tool … :digest … :hint …}` (content-free; agent host correlates by cache key) | rf2-3rt1f / rf2-36xod |
| `summary-key` | `:rf.mcp/summary` | `{<tree-summary>}` (lazy-summary projection) | rf2-tygdv / rf2-u2029 |

### Marker catalogue (`:rf.size/*`)

| Var | Key | Role | Source |
|---|---|---|---|
| `large-elided-key` | `:rf.size/large-elided` | Substituted for an over-threshold leaf (or declared-large slot). | Spec 009 §Size elision |
| `redacted-sentinel` | `:rf.size/redacted` | In-place sentinel for a `:sensitive?` leaf when the walker is configured to mask. | Spec 009 §Privacy |
| `elision-handle-key` | First slot in the elision handle vector | Vector-shaped handle for follow-up `get-path` calls. | rf2-9fz64 |
| `include-large-opt` / `include-sensitive-opt` / `include-digests-opt` / `threshold-bytes-opt` | (framework-side opts) | Knobs `rf/elide-wire-value` honours when the consumer relays a wire request to the walker. | Spec 009 §Size elision |

### Envelope counter slots

| Var | Slot | Counts |
|---|---|---|
| `dropped-sensitive-key` | `:dropped-sensitive` | `:sensitive? true` leaves dropped this call. |
| `elided-large-key` | `:elided-large` | Leaves replaced with the `:rf.size/large-elided` marker. |

Both slots ride the response envelope alongside the tool's
unqualified slots. **Indicator-field parity** is MUST-level: if one
slot is emitted, the other must be too (the round-2 audit fix the
conformance gate enforces).

### JSON-RPC error codes (per JSON-RPC 2.0 §5.1)

The same numeric codes apply across the triplet. Owned constants:
`code-parse-error` (-32700), `code-invalid-request` (-32600),
`code-method-not-found` (-32601), `code-invalid-params` (-32602),
`code-internal-error` (-32603). Story-mcp emits them via Cheshire;
pair2-mcp emits via the npm MCP SDK's `isError: true` tool-result
shape, but the codes still pin the cross-consumer protocol surface.

## `sensitive` — Spec 009 §Privacy default-suppress filter

Framework-published forwarders — Sentry / Honeybadger, pair2 server,
Story-MCP, Causa-MCP — MUST default-drop trace events whose
registration declared `:sensitive? true`. The runtime stamps the
flag at the top level of every emitted trace event inside such a
registration's handler scope; the forwarder's job is to gate egress
on it before any data crosses the trust boundary.

### Surface

- `sensitive-event?` (predicate over a trace-event map). Conservative
  — only the literal `true` value drops. Mirrors the Spec-Schemas
  contract: `:sensitive?` is typed boolean.
- `strip-sensitive` — walks a coll of trace events, returns
  `[kept dropped-count]`. The count feeds the
  `:dropped-sensitive` envelope slot.
- `scrub-snapshot` — recurses through a snapshot payload (snapshot
  mode `:full` / `:summary`) and removes any `:sensitive?`-stamped
  sub-tree.

### Cross-server arg-vocabulary convention

The opt-in arg name **`:include-sensitive?`** is the fixed, cross-
server vocabulary an agent learns once. Every MCP tool that surfaces
trace-like data MUST accept this arg, default it to false, and feed
it to `strip-sensitive` (and any analogous walker that recurses
through snapshot slices).

### Zero-dep rationale

Pair2-mcp is a CLJS Node bundle (no `re-frame.trace` on its
classpath); story-mcp / causa-mcp are JVM-side and DO have the
framework primitive available. The predicate here
(`(and (map? ev) (true? (:sensitive? ev)))`) is conservative and
identical to `re-frame.privacy/sensitive?`. Consumers that want to
bind to the framework primitive (story-mcp does, for code-review
locality) alias the surface in their own ns and delegate through
here.

## `elision` — wire-boundary `:rf.size/large-elided` walker

Per Spec 009 §Size elision in traces, the framework's
`rf/elide-wire-value` walker substitutes over-threshold leaves with
a `{:rf.size/large-elided {…}}` marker before the payload leaves
the runtime. Every MCP tool that returns a tree-typed payload
surfaces a scalar count of those substitutions on its response
envelope (the `:elided-large` slot — see `vocab/elided-large-key`).

### Surface

- `count-elided-markers` — walks a value and returns the integer for
  the `:elided-large` envelope slot. **Shallow at the marker
  boundary** — once a marker map is found, its body is NOT recursed
  into (marker bodies are summaries; they shouldn't double-count).

### Cousin to `sensitive`

`sensitive/strip-sensitive` returns `[kept dropped-count]` for the
`:dropped-sensitive` slot; `count-elided-markers` returns the
integer for `:elided-large`. Both indicators ride the response
envelope together per the cross-MCP indicator-field parity (one
without the other is the round-2 audit fix the conformance gate
enforces).

### Cross-platform

Pure-data tree walk; loads identically into JVM (story-mcp /
causa-mcp) and CLJS (pair2-mcp). No transport, no runtime, no
framework dep.

## `args` — argument coercion helpers

Parsers take an ALREADY-RESOLVED raw value (extracted by the
consumer from its platform-specific args object: a JS object for
pair2-mcp, a Clojure map for story-mcp / causa-mcp) and normalise
it into the Clojure-side type the tool body expects.

### Cross-server convention

Argument names and default postures are a cross-MCP convention. An
agent that learns `:dedup` defaults true on pair2-mcp must see the
same default everywhere. These parsers encode the defaults so they
can't drift across consumers.

### Surface

| Parser | Accepts | Output | Notes |
|---|---|---|---|
| `parse-boolean` | bools, strings (`"true"`/`"false"`/`"1"`/`"0"`/`"yes"`/`"no"`/`"y"`/`"n"`/`"on"`/`"off"`, case-insensitive), keywords (`:true`/`:false`), nil | boolean | Unrecognised → `default`. Call-sites wrap to bake the default. |
| `parse-positive-int` | ints, parsable strings | positive int or `default` | Strictly positive; zero falls to `default`. |
| `parse-non-negative-int` | ints, parsable strings | non-negative int or `default` | Zero allowed. |
| `parse-keyword` | keywords, strings (leading `:` optional), nil | keyword or `default` | The `:` prefix is stripped on string input. |
| `parse-mode` | enum-shaped strings / keywords | one of an allowed set, otherwise `default` | Bakes an `allowed-modes` set; rejected values fall to `default`. |

The rejection posture (default-suppress vs default-allow) is named
at the call-site by passing the appropriate `default` — the parser
itself is policy-free.

## `diff-encode` — path-keyed structural diff (rf2-1wdzp)

Each `:rf/epoch-record` carries `:db-before` and `:db-after` —
near-identical full app-db snapshots. `pr-str` doesn't preserve
structural sharing, so on the wire the pair is roughly 2× app-db
per epoch; a 50-epoch default `:epochs` slice ⇒ up to 100× app-db.

### What the transform does

Replaces `:db-after` with a path-keyed structural diff against
`:db-before`:

```clojure
{:db-before <full>
 :db-after  {:rf.mcp/diff-from :db-before
             :patches [[<path> :assoc <new-value>]
                       [<path> :dissoc]]}}
```

A patch is a 2- or 3-element vector — `[path :assoc v]` for new or
changed leaves, `[path :dissoc]` for keys that disappeared. The
decoder applies each patch in order via `assoc-in` / `update-in` /
`dissoc-in` to reconstruct `:db-after`.

### Why patches, not `clojure.data/diff`

`clojure.data/diff`'s parallel-vector sparse form (with `nil`
placeholders meaning "common at this position") loses information
once you only carry one half plus the original — you can't tell
`nil` (the leaf value `nil`) apart from `nil` (the no-change
sentinel). Path-keyed patches are unambiguous for any value the
runtime can produce.

### Self-contained records

The diff is intra-record (each epoch's `:db-after` is encoded against
the SAME record's `:db-before`); records remain self-contained and
decodable without reference to siblings. The slice can be reordered,
paginated, or filtered without breaking decode.

### Cross-MCP vocabulary

The `:rf.mcp/diff-from` marker key lives in `vocab.cljc`; the same
shape applies wherever an MCP tool ships an epoch-shaped record.
Agents pattern-match on the marker to invoke their local decoder.

## `overflow` — overflow-marker shape builder (rf2-rvyzy)

Owns the SHAPE of the overflow marker (the
`{:rf.mcp/overflow {:limit :reached :token-count … :cap-tokens …
:tool … :hint …}}` map). The cap-enforcement glue (counting
tokens, replacing the payload) lives in `cap.cljc`.

### Surface

- `default-max-tokens` (const, **5000**) — the convention's
  documented cap. Sized for a typical 5K-token MCP response envelope
  after diff-encode + dedup.
- `token-estimate s` ⇒ `(quot (count s) 4)`. Cheap character→token
  approximation aligned with Anthropic's rule-of-thumb for English /
  EDN. Not exact; the goal is a bounded wire payload, not a precise
  meter.
- `overflow-hint-fallback` — generic hint used when a tool isn't
  listed in the per-tool hint table.
- `overflow-marker` — builder fn; returns the canonical map shape.

### Hint table

Per-tool overflow hints live with the consumer (pair2-mcp ships its
`overflow-hints` table, story-mcp ships its own). The builder
delegates to the consumer's hint resolver via a small adapter; the
shape (a `tool→hint` map with `overflow-hint-fallback` as the
fallback) is the convention. The cross-MCP conformance gate
(`wire-vocab/`) pins the marker SHAPE; the hint text is consumer-
authored.

## `cap` — wire-boundary token-budget cap pipeline (rf2-eyelu)

Owns the ALGORITHM that drives the overflow marker into a result.
Until rf2-eyelu this pipeline was duplicated near-identically in
pair2-mcp (CLJS, `#js {:content #js [...]}`-shaped results) and
story-mcp (CLJ, `{:content [...] :structuredContent ...}`-shaped
results). The only structural difference between the two
implementations was the SHAPE of the result map and the platform-
appropriate accessor used to read its `:text` slots — algorithm
identical.

### Algorithm

1. Sum the cumulative `overflow/token-estimate` across every
   `:text` slot in the result's `:content` vector.
2. Compare against the per-call cap (`:max-tokens` MCP arg, default
   `overflow/default-max-tokens`, `0` disables).
3. Under-budget responses pass through unchanged; over-budget
   responses are replaced with a fresh result carrying the
   `:rf.mcp/overflow` marker.

### Per-server specialisation hook — the `ResultIO` protocol

Each consumer reifies `ResultIO` with two methods:

- `(content-texts io result)` ⇒ seq of strings, the `:text`-slot
  values inside `result`'s content vector. The platform-specific
  accessor (`:text` / `j/get :text`) lives behind this method.
- `(build-overflow-result io marker original-result)` ⇒ a fresh
  result map / object carrying the overflow marker, shaped for the
  consumer's transport.

The cap pipeline calls these two methods; everything else is shared.
Adding a third consumer is a single reify, not a code copy.

### Recursion safety

The overflow marker itself MUST fit under the cap. The conformance
harness (`tools/mcp-conformance/test/live-pair2-overflow.js`)
asserts this on every cap-trigger; if a future bead grows the
marker, the test surfaces the regression before it ships.

## Cross-MCP vocabulary as a versioned contract

The marker keys + envelope slots + JSON-RPC codes are a **wire-
protocol contract**. A rename here breaks every connected agent.
Two layers of protection:

1. **The cross-MCP conformance gate** at
   `tools/mcp-conformance/wire-vocab/` pins the canonical Malli
   schema for every reserved `:rf.mcp/*` / `:rf.size/large-elided` /
   `:rf.elision/at` marker and asserts that fixtures + source text
   from every emitting server conform. Any rename or shape drift
   fails the JVM test corpus.
2. **The marker-key vars in `vocab.cljc`** are the single
   reference point — every server reads them via `(:require ...)`
   rather than re-typing the keyword literal. A grep for
   `:rf.mcp/overflow` shows exactly one defining occurrence;
   everywhere else is a `vocab/overflow-key` reference.

## Adding to the base

Two rules:

1. **It must be implemented somewhere already.** This artefact is
   for factoring duplication, not for landing speculative shared
   surfaces. New primitives land in a consumer first; if a second
   consumer needs the same code, lift it then.

2. **It must be pure CLJC with no consumer-side deps.** The base's
   `deps.edn` carries only `org.clojure/clojure`. If your primitive
   needs cheshire / re-frame.trace / shadow-cljs / js-interop, it
   belongs in its consumer, not here. (story-mcp's `sensitive.cljc`
   ns keeps a thin local alias over `re-frame.privacy/sensitive?`
   for code-review locality — the predicate itself lives here.)

## See also

- [`tools/pair2-mcp/spec/Principles.md`](../../pair2-mcp/spec/Principles.md)
  — pair2-mcp's principles, downstream consumer of these primitives.
- [`tools/causa-mcp/spec/Principles.md`](../../causa-mcp/spec/Principles.md)
  (defers to [`004-Wire-Pipeline.md`](../../causa-mcp/spec/004-Wire-Pipeline.md))
  — causa-mcp's wire pipeline, the third consumer.
- [`tools/mcp-conformance/wire-vocab/`](../../mcp-conformance/wire-vocab/)
  — the JVM-side cross-MCP conformance corpus that pins this ns's
  marker SHAPE across every consumer.
- [`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md)
  §Size elision in traces — the framework primitive this base's
  `elision` ns counts the output of.
- [`spec/Conventions.md`](../../../spec/Conventions.md) §Cross-MCP
  indicator-field vocabulary — the MUST-level parity between the
  `:dropped-sensitive` and `:elided-large` envelope slots.
