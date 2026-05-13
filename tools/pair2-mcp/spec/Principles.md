# Principles

The load-bearing principles. When a design call has two reasonable
options, these are the tie-breakers. Implementers and contributors
should be able to read this doc and reach the same answers
pair2-mcp already reached.

These are downstream of the framework's [Principles](../../../spec/Principles.md);
they are *pair2-mcp-specific*. Where they overlap the framework's
principles, this doc cites instead of repeating.

## Tool consumes the framework; doesn't extend it

pair2-mcp is a **downstream consumer** of re-frame2's existing
surfaces. It must not add:

- New registries.
- New dispatch types.
- New effect substrates.
- New component substrates.

The nine ops route through the existing `re-frame-pair2.runtime`
namespace via `cljs-eval`. Nothing new is registered against the
framework; nothing new is introduced into a consumer app's runtime.

This is the downstream-EPs-consume-foundation rule applied to tools:
tools observe and exercise what the framework emits and registers;
they do not invent new substrates.

Concretely: when implementation surfaces a runtime gap (e.g., "I
want to inspect the epoch ring but the runtime doesn't expose a
read accessor"), file a `bd` bead against `re-frame-pair2.runtime`
or the relevant framework spec. Don't bolt a parallel surface onto
the MCP server.

## MCP, not an IDE plugin

The agent-host integration contract is **Model Context Protocol over
stdio**, not a per-editor extension.

By implementing MCP, this artefact works with every MCP-capable host
(Claude Code, Cursor, Copilot, and whatever lands next) without
per-host plumbing. The cost of one stdio JSON-RPC server is paid
once; the alternative — N editor extensions, each re-implementing
the same tool surface — pays the cost N times and ages worse.

A custom WebSocket protocol was considered and rejected for the same
reason: it would require every agent host to learn pair2's wire
shape. MCP already exists; pair2-mcp speaks it.

## Single persistent nREPL socket

One TCP socket to the shadow-cljs nREPL is opened on first need and
held for the lifetime of the session. Subsequent ops reuse the
socket without reconnecting.

The break-even versus a reconnect-per-op design is one op — and a
typical pair2 session fires dozens to hundreds. Per-op latency drops
from ~700ms (bash startup + babashka startup + fresh nREPL connect
per call) to ~5–50ms (one bencode round-trip on the open socket).

The persistent-socket choice is what makes the MCP server feel
*interactive* rather than *batch*. It is the load-bearing latency
decision.

Ops carry a UUID `id`; the connection multiplexes incoming bencode
frames against a `{id → resolve-fn}` pending map. Concurrent ops
are correct in principle even though the MCP server currently
invokes tools sequentially.

## Stage-marker-independent

pair2-mcp must work against any conforming re-frame2 runtime. It
does not depend on a specific shadow-cljs build configuration, a
specific stage marker, or a specific re-frame2 release line.

Concretely:

- The `build` argument defaults to `"app"` but is configurable on
  every op (and via the `SHADOW_CLJS_BUILD_ID` env var).
- Port discovery walks `$SHADOW_CLJS_NREPL_PORT` → standard shadow
  paths → `.nrepl-port`. Any of them satisfy the contract.
- Runtime presence is marker-detected (`js/globalThis.__re_frame_pair2_runtime`),
  not version-pinned. The runtime ships into the consumer app via
  shadow-cljs `:devtools :preloads`; a missing marker resolves to a
  structured `:reason :runtime-not-preloaded` error with the setup
  hint, no cljs-eval inject fallback (rf2-7dvg).
- The runtime contract is the shape of the nine ops, not a specific
  framework version.

A project that adopts a non-default build id, a custom nREPL port,
or a slightly different shadow layout still gets a working
pair2-mcp without code changes.

## Degraded boot, not failed boot

If the nREPL port can't be resolved at startup (no port file, no
env var, shadow-cljs not running yet), the server still boots and
answers `tools/list`. Every `tools/call` returns a structured
`:ok? false :reason :nrepl-port-not-found` error so the agent host
can surface the problem and the user can start shadow-cljs without
restarting the server.

The agent-host workflow is *don't make the user restart anything*.
The server's job is to keep working through the gaps and surface a
useful error when the runtime isn't ready.

## Bash-shim back-compat preserved

The bash shims under `skills/re-frame-pair2/scripts/` continue to
work and are not slated for removal. Their headers carry a
deprecation notice pointing here; migration is opt-in per session.

The migration discipline is *additive, not destructive*. Agents can
mix shim calls and MCP tool calls in the same workflow during the
transition. Existing skill docs and runbooks that reference the
shims keep working; nothing breaks because the MCP server shipped.

The op vocabulary overlaps cleanly: the bash shims cover six of the
nine canonical pair2 ops (`discover-app`, `eval-cljs`, `dispatch`,
`trace-window`, `watch-epochs`, `tail-build`), with identical names
and arg shapes — only the transport differs. The MCP-only additions
(`snapshot`, plus the streaming pair `subscribe` / `unsubscribe`) have
no shim equivalent. This is what makes the back-compat tractable: the
overlap is contract-identical; the plumbing underneath is different.

## Tight token budget per response

Each MCP tool response is bounded at **≤ 5,000 tokens** by
default. The cap is normative AND enforced: every `tools/call`
response passes through a wire-boundary check before egress,
and over-budget payloads are replaced — not silently truncated —
with a structured `{:rf.mcp/overflow ...}` marker.

The motivation is the 2026 trend axis. Microsoft's April 2026
recommendation (Playwright CLI **over** Playwright MCP for
coding agents) was driven by MCP responses being roughly 4×
larger in tokens than the equivalent CLI output. Anthropic's
own router-SKILL guidance lands at the same ~5k ceiling. An
agent host with a 200k context window can absorb a handful of
20k tool returns, but the realistic working session fires
dozens of tool calls — pair2-mcp's `snapshot` mega-op and the
`subscribe` streaming pair are the exposed surfaces here. A
single oversized response burns the budget the agent needs
for the next ten ops.

### The wire-boundary cap (enforced, rf2-rvyzy)

The cap is enforced in `tools.cljs` at the `invoke` boundary,
applied as the final step after every per-tool function
resolves. Per-tool functions emit the same shapes they always
did; the cap is a property of the egress, not of each tool's
internals.

- **Token rule**: `token-estimate s = (quot (count s) 4)` —
  cheap character→token approximation aligned with the
  published Anthropic rule-of-thumb for English / EDN. Not
  exact; the goal is a bounded wire payload, not a precise
  per-token meter.
- **Per serialised response**: the cap applies to the sum of
  every `:text` slot in the assembled MCP
  `{:content [{:type "text" :text ...} ...]}` shape.
  Multi-part responses share one cumulative budget rather
  than per-key.
- **Default cap**: `5000` tokens.
- **Per-call override**: every tool accepts a `max-tokens`
  MCP arg — integer cap, `0` disables (escape hatch for
  callers that have already paginated or genuinely need the
  full payload). The knob surfaces in `tools/list` so clients
  can discover it.
- **Overflow shape**: an over-budget payload is replaced with

  ```clojure
  {:rf.mcp/overflow
   {:limit       :reached
    :token-count <integer>   ; estimate of the original (over-budget) payload
    :cap-tokens  <integer>   ; the cap that tripped
    :tool        "<tool-name>"
    :hint        "<tool-specific next-step hint>"}}
  ```

  The agent host MUST treat `{:rf.mcp/overflow {:limit :reached}}`
  as a structured retry signal — narrow args, drop slices, or
  pass `max-tokens 0` if the full payload is genuinely needed.
- **Pluggable strategy**: the wrapper dispatches on a
  `:strategy` keyword. Today only `:truncate-with-marker` is
  implemented (replace the payload with the overflow marker).
  Path-slicing and lazy-summary already landed (rf2-tygdv) but
  as per-tool input-shape concerns: the `snapshot` and
  `get-path` tools accept a `:path` arg and default to a
  `{:rf.mcp/summary ...}` response for the unbounded `:app-db`
  slice, so the cap stays a backstop rather than the primary
  mechanism for the common case. Diff-encoded `:db-after`
  (rf2-1wdzp) and structural dedup (rf2-obpa9) also live at the
  tool surface — see the dedicated mechanisms below. They run
  BEFORE the cap so the wrapper measures the already-shrunk
  payload.
- **Silent truncation is not allowed**: a payload that exceeds
  the cap MUST NOT be shipped in any partial form that would
  let the agent host parse it as a valid response. The marker
  is the only over-budget response shape.

### Per-tool budget discipline

In addition to the wire-cap (a backstop), tools are designed to
stay inside the budget by construction:

- **Pagination / cursor for unbounded surfaces.** Any op that
  returns a list whose size is a function of runtime state
  (`trace-window`, `subscribe` epoch batches, `handlers`
  listings under `discover-app`) MUST accept a `:limit`
  argument and return a `:cursor` for continuation. The
  default `:limit` MUST keep the response under the cap. No
  unbounded list responses; no "best-effort" omission of
  pagination.
- **Summarisation modes for rich payloads.** Ops with rich
  per-item shape (`snapshot`, `trace-window`, `discover-app`)
  MUST expose a `:mode` (or equivalent) argument with at
  least `:count` (return totals only), `:sample` (return a
  bounded prefix or stratified sample with sizes attached),
  and `:full` (return everything, paginated). The default
  MUST be `:sample` for any op whose `:full` payload can
  exceed the cap. Agents opt into `:full` when they actually
  need it.
- **Streaming over batch where appropriate.** `subscribe`
  returns one event per JSON-RPC notification, not a buffered
  batch. The cap applies per notification; the agent host
  meters consumption. Batching is reserved for ops whose
  payload is naturally bounded and small.

Each op's reference entry in
[`003-Tool-Catalogue.md`](003-Tool-Catalogue.md) carries a
**typical-token** hint (e.g., `~1.2k`, `~3k under :sample`)
and a **cap-reached** behaviour note (the structured-overflow
marker described above, optionally with tool-specific hint
text). The hints surface in `list-tools` so the agent can
plan ahead.

This is the load-bearing budget posture for pair2-mcp's
agent-host workflow: keep the per-op cost predictable, push
the agent to ask for what it actually needs, and never let a
single op blow the session.

### Diff-encoded `:db-after` on epoch slices (rf2-1wdzp)

Every `:rf/epoch-record` carries `:db-before` and `:db-after`
— two near-identical full app-db snapshots
([`spec/Spec-Schemas.md` §`:rf/epoch-record`](../../../spec/Spec-Schemas.md)).
`pr-str` doesn't preserve structural sharing across records, so
on the wire the pair serialises as 2× app-db per epoch. The
default epoch-history depth (50) times the default app-db
snapshot rate means the `:epochs` slice of `snapshot` can hit
up to 100× app-db in raw wire bytes — the single biggest
wire-byte cost flagged in the rf2-jlq5j findings doc.

**The transform**. At the MCP wire boundary, every epoch
record passing through `trace-window`, `watch-epochs`, or the
`:epochs` slice of `snapshot` has its `:db-after` replaced
with a path-keyed structural diff against its own `:db-before`:

```clojure
;; Wire shape
{:db-before <full-app-db>
 :db-after  {:rf.mcp/diff-from :db-before
             :patches [[<path> :assoc <new-value>]
                       [<path> :dissoc]
                       ...]}}
```

A patch is a 2- or 3-element vector. `[path :assoc v]` records
a new or changed leaf; `[path :dissoc]` records a key that
disappeared. The decoder applies patches in order via
`assoc-in` / `update-in`. Vectors and scalars are treated as
leaves (replaced wholesale via `:assoc`); element-wise vector
diff doesn't help for the typical app-db where vector values
are short. Round-trip is exact: `decode(encode(record)) =
record` for every record the runtime can produce.

**Why path-keyed patches, not `clojure.data/diff`**. The
parallel-vector sparse form `clojure.data/diff` produces for
vector diffs (with `nil` placeholders meaning \"common at this
position\") is lossy once you only carry one half plus the
original — you can't tell `nil` (the leaf value `nil`) apart
from `nil` (the no-change sentinel). Path-keyed patches are
unambiguous for any value the runtime can produce.

**Why intra-record, not inter-record**. Each epoch's
`:db-after` is encoded against the SAME record's `:db-before`
— not against the previous epoch's `:db-after`. Records remain
self-contained and decodable without reference to siblings.
The slice can be reordered, paginated, filtered, or dropped
without breaking decode.

**`epochs-mode` arg**. Every tool that ships epoch records
accepts an `epochs-mode` arg:

- `\"diff\"` (default) — the path-keyed structural diff shape
  above. Smaller wire payload by 1-2 orders of magnitude in
  the typical case (large app-db, small per-event change).
- `\"full\"` (opt-in) — legacy full-pair shape, both
  `:db-before` and `:db-after` carried verbatim. Required
  for agent workflows that drive time-travel restore directly
  off the wire response rather than via the runtime's
  `rf/restore-epoch` path; the framework's restore path stays
  the canonical surface, so this is the rare case.

The selected mode surfaces on the tool's result as
`:epochs-mode :diff | :full` so the agent host can
pattern-match without re-reading the request.

**Wire-byte impact**. For a 1MB app-db with a typical
single-key change per epoch, the diff-encoded `:db-after`
shrinks to dozens of bytes (the patch alone). The `:db-before`
reference stays per-record (~1MB each); a 10-epoch slice goes
from ~20MB to ~10MB. Combined with the `:app-db` slice's
`:summary` default (rf2-tygdv), the agent's typical
`investigate-X` workflow stays well inside the 5,000-token
cap without further drill-down. The `:db-before` reference is
deliberately NOT deduplicated by this mechanism — diff-encoding
keeps each record self-contained — but the next mechanism in
this pipeline (structural dedup, rf2-obpa9) DOES collapse the
shared `:db-before` references at the slice boundary via an
explicit substitution table; round-trip remains exact via the
de-dupe library's companion `expand` function.

**Cross-MCP vocabulary**. The `:rf.mcp/diff-from` marker key
follows the `:rf.mcp/*` namespace convention shared with
`:rf.mcp/overflow` (rf2-rvyzy), `:rf.mcp/summary` (rf2-tygdv),
and causa-mcp's `:rf.mcp/dedup-table` (rf2-lwgg8 mechanism 5).
Agents recognise the family once and pattern-match on the
prefix.

### Structural dedup (rf2-obpa9)

The fourth wire-protocol mechanism. Persistent data structures
share subtrees in memory; `pr-str` flattens the sharing and
writes every shared node out in full. After diff-encoding
(above) collapses each `:db-after`, the dominant remaining cost
is the per-record `:db-before` reference — a 10-epoch window
over a 1MB app-db is still ~10MB on the wire. Structural dedup
collapses repeated subtrees by emitting them once into a
substitution table and replacing later occurrences with
references.

**The transform**. After diff-encoding, every epoch slice
shipping through `trace-window`, `watch-epochs`, or the
`:epochs` slot of `snapshot` is passed through
[`day8/de-dupe`](https://github.com/day8/de-dupe)
(MIT, ClojureScript, alive 2026; pinned via git-coord in
[`deps.edn`](../deps.edn)) and wrapped in the cross-MCP
substitution-table marker:

```clojure
;; Wire shape
{:rf.mcp/dedup-table
 {:de-dupe.cache/cache-0 <root-with-refs>
  :de-dupe.cache/cache-1 <shared-subtree>
  :de-dupe.cache/cache-2 <shared-subtree>
  ...}}
```

The cache map is the de-dupe library's flat output: cache-0 is
the root structure (with namespaced-symbol references to other
cache entries inline), and the remaining entries are the
extracted shared subtrees. The agent host reconstructs by
calling `(de-dupe.core/expand cache-map)` — one library call,
exact round-trip. The marker key
(`:rf.mcp/dedup-table`) matches the cross-MCP vocabulary
declared in
[causa-mcp `Principles.md` §5 Structural dedup](../../causa-mcp/spec/Principles.md);
an agent that learned the slot on causa-mcp sees the same slot
here.

**Why `de-dupe-eq` (equality), not `de-dupe` (identity)**. Data
arrives at the MCP server via bencode over nREPL; CLJS values
reconstructed on the way through don't share identity with the
runtime's in-memory originals. Equality is what makes the
cross-record share-pooling actually fire on the wire boundary.

**Where in the pipeline**. Dedup runs AFTER diff-encoding and
BEFORE the wire-cap check (rf2-rvyzy). Order matters: the
diff-encoder shrinks each record's `:db-after`; the deduper
then pools repeated subtrees across records (most importantly
the `:db-before` reference, which is the dominant cost after
diff-encoding). The wire-cap then measures the deduped payload,
not the raw, so dedup gets to shrink first.

**Table reset policy**. The cache is built **per dedup call**
(per `:epochs` slice, per subscribe-tick events vector).
Cross-call carry-over would require a stateful per-subscription
cache and a wire shape that references entries from previous
frames — a non-trivial protocol change for a marginal gain (the
dominant within-call savings are already captured). If a future
findings doc shows cross-tick share-pooling matters, that is a
separate bead.

**Idempotence on no-dedup-opportunity**. A payload with no
repeated subtrees deduplicates to a one-entry cache — the wire
shape is slightly larger than the input. The encoder
short-circuits on nil / empty-collection / scalar inputs so the
marker only appears when there's actual work to undo.

**`dedup` arg**. Every tool that ships epoch slices or events
accepts a `dedup` arg (boolean, default `true`). Passing
`false` skips the wrap — useful for ad-hoc reads when the
agent host hasn't been taught to call `expand`, or for
round-trip debugging.

**Wire-byte impact**. Measured: a 10-epoch window over a
256-key map (each value a 256-char string ⇒ 683KB raw)
compresses to 72KB after dedup — a 89.5% reduction. The exact
ratio depends on shape (subtree cardinality, map fan-out,
value sizes); the floor is **≥50%** on any payload where the
`:db-before` reference is shared across multiple records, which
is the rule rather than the exception for diff-encoded epoch
slices.

**Subscribe streaming**. The `subscribe` tool's
`notifications/progress` frames apply the same wrap per-tick.
The cache is per-tick (no cross-tick refs); each `:events`
vector in the progress payload is a self-contained deduped
blob.

## Backed by the framework's principles

When in doubt, defer to the framework's [Principles](../../../spec/Principles.md):

- **Regularity over cleverness** — one obvious way to do a thing.
  The nine op names and shapes are stable.
- **Named things over anonymous things** — every op has a stable
  name; every reason keyword in an `:ok? false` response is stable.
- **Public query surfaces** — pair2-mcp reads only what the
  framework's public registrar, trace bus, and epoch-history
  surfaces expose, through the `re-frame-pair2.runtime` namespace.
- **Deterministic execution** — a `dispatch` op with the same args
  produces the same effect; no hidden state in the server beyond
  the connection and the pending-map.
- **No core.async** — per [`feedback_no_core_async`](../../../AGENTS.md),
  pair2-mcp does not pull core.async as a dependency or use it as a
  building block. The async return-shape is JavaScript Promises
  (the Node host's native async substrate).

pair2-mcp is a downstream artefact of the framework's AI-first
discipline. The principles above are what *pair2-mcp adds* over the
framework's baseline; everything below is inherited.
