# Principles

> Implements the [Tool-Pair contract](../../../spec/Tool-Pair.md) —
> these principles tie-break design decisions within the bounds that
> contract sets for pair-shaped AI tools.

The load-bearing principles. When a design call has two reasonable
options, these are the tie-breakers. Implementers and contributors
should be able to read this doc and reach the same answers
re-frame2-pair-mcp already reached.

These are downstream of the framework's [Principles](../../../spec/Principles.md);
they are *re-frame2-pair-mcp-specific*. Where they overlap the framework's
principles, this doc cites instead of repeating.

## Tool consumes the framework; doesn't extend it

re-frame2-pair-mcp is a **downstream consumer** of re-frame2's existing
surfaces. It must not add:

- New registries.
- New dispatch types.
- New effect substrates.
- New component substrates.

The fourteen ops route through the existing `re-frame2-pair.runtime`
namespace via `cljs-eval`. Nothing new is registered against the
framework; nothing new is introduced into a consumer app's runtime.

This is the downstream-EPs-consume-foundation rule applied to tools:
tools observe and exercise what the framework emits and registers;
they do not invent new substrates.

Concretely: when implementation surfaces a runtime gap (e.g., "I
want to inspect the epoch ring but the runtime doesn't expose a
read accessor"), file a `bd` bead against `re-frame2-pair.runtime`
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
reason: it would require every agent host to learn re-frame2-pair's wire
shape. MCP already exists; re-frame2-pair-mcp speaks it.

## Single persistent nREPL socket

One TCP socket to the shadow-cljs nREPL is opened on first need and
held for the lifetime of the session. Subsequent ops reuse the
socket without reconnecting.

The break-even versus a reconnect-per-op design is one op — and a
typical re-frame2-pair session fires dozens to hundreds. Per-op latency drops
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

re-frame2-pair-mcp must work against any conforming re-frame2 runtime. It
does not depend on a specific shadow-cljs build configuration, a
specific stage marker, or a specific re-frame2 release line.

Concretely:

- The `build` argument defaults to `"app"` but is configurable on
  every op (and via the `SHADOW_CLJS_BUILD_ID` env var).
- Port discovery walks `$SHADOW_CLJS_NREPL_PORT` → standard shadow
  paths → `.nrepl-port`. Any of them satisfy the contract.
- Runtime presence is marker-detected (`js/globalThis.__re_frame2_pair_runtime`),
  not version-pinned. The runtime ships into the consumer app via
  shadow-cljs `:devtools :preloads`; a missing marker resolves to a
  structured `:reason :runtime-not-preloaded` error with the setup
  hint, no cljs-eval inject fallback (rf2-7dvg).
- The runtime contract is the shape of the fourteen ops, not a specific
  framework version.

A project that adopts a non-default build id, a custom nREPL port,
or a slightly different shadow layout still gets a working
re-frame2-pair-mcp without code changes.

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

The bash shims under `skills/re-frame2-pair/scripts/` continue to
work and are not slated for removal. Their headers carry a
deprecation notice pointing here; migration is opt-in per session.

The migration discipline is *additive, not destructive*. Agents can
mix shim calls and MCP tool calls in the same workflow during the
transition. Existing skill docs and runbooks that reference the
shims keep working; nothing breaks because the MCP server shipped.

The op vocabulary overlaps cleanly: the bash shims cover six of the
fourteen canonical re-frame2-pair ops (`discover-app`, `eval-cljs`, `dispatch`,
`trace-window`, `watch-epochs`, `tail-build`), with identical names
and arg shapes — only the transport differs. The MCP-only additions
(`snapshot`, `get-path`, the streaming triad
`subscribe` / `unsubscribe` / `list-subscriptions`, the
registrar-introspection pair `handler-meta` / `list-handlers`, and
`get-re-frame2-pair-instructions`) have no shim equivalent. This is what makes
the back-compat tractable: the overlap is contract-identical; the
plumbing underneath is different.

## Tight token budget per response

Each MCP tool response is bounded at **≤ 5,000 tokens** by
default. The cap is normative AND enforced: every `tools/call`
response passes through a wire-boundary check before egress,
and over-budget payloads are replaced — not silently truncated —
with a structured `{:rf.mcp/overflow ...}` marker.

The cross-server contract — default cap, override slot name,
overflow marker key, agent-host retry contract, and chained-budget
rules when an agent attaches the triplet in one session — lives at
[`tools/mcp-conformance/TOKEN-BUDGETS.md`](../../mcp-conformance/TOKEN-BUDGETS.md).
The eight mechanisms below are re-frame2-pair-mcp's expansion of that
contract.

The motivation is the 2026 trend axis. Microsoft's April 2026
recommendation (Playwright CLI **over** Playwright MCP for
coding agents) was driven by MCP responses being roughly 4×
larger in tokens than the equivalent CLI output. Anthropic's
own router-SKILL guidance lands at the same ~5k ceiling. An
agent host with a 200k context window can absorb a handful of
20k tool returns, but the realistic working session fires
dozens of tool calls — re-frame2-pair-mcp's `snapshot` mega-op and the
`subscribe` streaming pair are the exposed surfaces here. A
single oversized response burns the budget the agent needs
for the next ten ops.

The discipline rests on **eight normative mechanisms**.
Mechanisms 1-6 align deliberately with the cross-MCP wire
pipeline so that an agent learning the slot on one server
gets the same slot on the others; mechanisms 7-8 are
re-frame2-pair-mcp-specific (epoch-record diff encoding and
streaming-subscribe budgets).
Every catalogue entry in
[`003-Tool-Catalogue.md`](003-Tool-Catalogue.md) declares
which mechanisms apply, with a **typical-token** hint
(`~1.2k`, `~3k under :sample`) and a **cap-reached**
behaviour note. The hints surface in `list-tools` so the
agent can plan ahead. The cap is enforced at the wire
boundary, not just documented.

The eight mechanisms in re-frame2-pair-mcp:

1. **Token budget cap** (§"The wire-boundary cap") — 5,000-token
   default + `max-tokens` per-call override + `{:rf.mcp/overflow ...}`
   marker.
2. **Path slicing** (§"Path slicing") — `:path` arg + tree-summary
   default on rich snapshot slices.
3. **Cursor pagination** (§"Per-tool budget discipline" /
   pagination bullet) — `:limit` + `:cursor` for unbounded
   sequence-returning ops.
4. **Lazy summary** (§"Per-tool budget discipline" /
   summarisation-modes bullet) — `:mode :summary` default for
   rich payloads + `{:rf.mcp/summary ...}` shape.
5. **Structural dedup** (§"Structural dedup") — `day8/de-dupe`
   substitution table + `{:rf.mcp/dedup-table ...}` wire shape.
6. **Size-elision wire markers** (§"Size-elision wire markers") —
   the framework's `elide-wire-value` walker substitutes
   `{:rf.size/large-elided ...}` markers for declared-large
   slots.
7. **Diff-encoded `:db-after`** (§"Diff-encoded `:db-after`") —
   path-keyed structural patches against the same record's
   `:db-before`. *re-frame2-pair-mcp-specific*: addresses the
   epoch-record pair shape.
8. **Streaming subscribe byte+event budget** (§"Streaming
   subscribe byte+event budget") — runtime-side queue feeding
   `subscribe` carries OR-combined event-count + byte caps.
   *re-frame2-pair-mcp-specific*.

The reserved `:rf.mcp/*` and `:rf.size/*` keyword namespaces
are catalogued at
[`spec/Conventions.md §Reserved namespaces (framework-owned)`](../../../spec/Conventions.md#reserved-namespaces-framework-owned)
— the same `:rf.mcp/overflow`, `:rf.mcp/summary`,
`:rf.mcp/dedup-table`, `:rf.mcp/diff-from`, and
`:rf.size/large-elided` keys appear on the wire of every
re-frame2 MCP server.

The subsections below run in **pipeline order**
(wire-boundary cap → path slicing → per-tool budget → diff
encoding → dedup → size elision → streaming budget), not
mechanism-number order — the mechanism numbers track the
cross-server catalogue identity, while the file order
matches the order each transform runs at the wire boundary.
Each section heading carries its mechanism number for
cross-reference.

### The wire-boundary cap (enforced, rf2-rvyzy)

Mechanism 1 — the token-budget-cap peer of the cross-MCP
wire pipeline. Cap, override slot, and overflow-marker
reserved key are identical cross-server; the marker's
internal slot shape differs (see callout below).

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

  The arg name is fixed cross-server: **`max-tokens`** as the
  JSON-RPC `arguments` key (string, kebab-case — the wire
  shape an agent host sends); **`:max-tokens`** as the parsed
  CLJS keyword inside the runtime. The pairing is normative —
  re-frame2-pair-mcp and story-mcp both parse the same wire
  slot to the same in-process key. Per
  [TOKEN-BUDGETS.md](../../mcp-conformance/TOKEN-BUDGETS.md).
- **Overflow shape**: a tool that would exceed the cap MUST NOT
  silently truncate. Instead it MUST return a structured
  overflow marker as the entire payload:

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
  The `:rf.mcp/overflow` key is reserved cross-server
  (re-frame2-pair-mcp, story-mcp use the same key) so an agent
  that recognises it once recognises it everywhere.
- **Pluggable strategy**: the wrapper dispatches on a
  `:strategy` keyword. Today only `:truncate-with-marker` is
  implemented (replace the payload with the overflow marker).
  Path-slicing and lazy-summary (rf2-tygdv, generalised to
  every rich snapshot slice in rf2-u2029), diff-encoded
  `:db-after` (rf2-1wdzp), structural dedup (rf2-obpa9), and
  size-elision wire markers (rf2-urjnc) all live at the tool
  surface — see the dedicated mechanisms below. They run
  BEFORE the cap so the wrapper measures the already-shrunk
  payload.
- **Silent truncation is not allowed**: a payload that exceeds
  the cap MUST NOT be shipped in any partial form that would
  let the agent host parse it as a valid response. The marker
  is the only over-budget response shape.

### Path slicing (rf2-tygdv, generalised in rf2-u2029)

Mechanism 2 — the path-slicing peer of the cross-MCP wire
pipeline. Argument name (`:path`), EDN encoding, default-summary
behaviour, and `:path-not-found` error are identical
cross-server.

Tools returning rich nested values (`snapshot`, `get-path`)
MUST accept an optional `:path` argument — an EDN-encoded
vector of keys (e.g. `"[:cart :items 3 :sku]"`) addressing a
subtree.

The default behaviour **without** a `:path` argument on a
rich-shape op MUST be a tree-summary (per the lazy-summary
mechanism), not the full payload. With `:path`, the tool
returns the addressed subtree subject to the remaining
mechanisms (still budgeted, still summarised at the leaf if
rich). Out-of-range paths return `:ok? false :reason :path-not-found`
with the deepest valid prefix attached so the agent can
re-aim. This is the same slicing convention sibling MCP
servers adopt; an agent that learned the slot on one server
sees the same slot here.

Concretely on re-frame2-pair-mcp's catalogue: `snapshot` accepts a
top-level `:path` arg (drilling into the chosen slice) and a
`:mode` arg (`:summary` default, `:sample`, `:full`) plus a
per-slice `:modes` override map; `get-path` takes a `:path`
arg for the targeted-read surface. The discovery workflow ("I
don't know which slice carries the answer") stays inside the
cap by construction rather than relying on the cap as a
backstop. The same wire-shape vocabulary (`{:rf.mcp/summary
{:type :map :keys [...] :counts {...} :bytes ~N}}` on
summarised slices) is shared with story-mcp under mechanism
4 (lazy summary) — single learning step on the agent side.

### Per-tool budget discipline

Covers mechanisms 3 (cursor pagination) and 4 (lazy summary)
together — the per-tool-shape-discipline peers of the cross-MCP
wire pipeline. `:cursor` / `:limit` slot names, `:mode :summary`
default, and the `{:rf.mcp/summary {:type ... :keys ... :counts ...
:bytes ...}}` shape are identical cross-server.

In addition to the wire-cap (a backstop), tools are designed to
stay inside the budget by construction:

- **Pagination / cursor for unbounded surfaces.** Any op that
  returns a list whose size is a function of runtime state
  (`trace-window`, `subscribe` epoch batches, `registrations`
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

**Catalogue-entry contract (normative).** Every tool entry in
[`003-Tool-Catalogue.md`](003-Tool-Catalogue.md) MUST declare:

1. **Which of the eight mechanisms apply** to the tool (cap,
   path-slice, cursor, lazy-summary, dedup, elision, diff-
   encode, streaming-budget). A tool that ships a tree-typed
   payload but doesn't apply mechanisms 4 / 6 (lazy-summary /
   size-elision) is the load-bearing exception that has to be
   called out in the entry.
2. **The `:typicalTokens` hint** — the worst-case ballpark in
   tokens. AI clients budget calls and pick size-conscious
   args (`max-tokens`, `cache`, `cursor`) without trial-and-
   error using this slot.
3. **The cap-reached behaviour** — what the tool returns when
   the post-shrink payload would still trip the cap. Default
   is `{:rf.mcp/overflow ...}` with the tool-specific hint
   string; tools that paginate (`trace-window`, `watch-epochs`)
   instead emit `:has-more? true` + `:next-cursor` and never
   trip the cap on a single page.
4. **The default mode / limit / dedup / elision values** for
   every applicable knob. No tool ships without these slots.

The contract aligns with the cross-MCP wire-pipeline
catalogue-entry contract; sibling catalogues use the same
hint-slot vocabulary so an agent's per-tool budget projections
work uniformly across servers.

This is the load-bearing budget posture for re-frame2-pair-mcp's
agent-host workflow: keep the per-op cost predictable, push
the agent to ask for what it actually needs, and never let a
single op blow the session.

### Diff-encoded `:db-after` on epoch slices (rf2-1wdzp)

Mechanism 7 — re-frame2-pair-mcp-specific. The cross-server
vocabulary (`:rf.mcp/diff-from`, the `[<path> :assoc <v>]` /
`[<path> :dissoc]` patch grammar) is pinned here for any future
sibling that adopts the same diff-encoding shape.

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
with a path-headed cluster projection of a path-keyed
structural diff against its own `:db-before` (rf2-qeous):

```clojure
;; Wire shape
{:db-before <full-app-db>
 :db-after  {:rf.mcp/diff-from :db-before
             :sections [{:section-path [:cart :items]
                         :section-kind :modified
                         :patches      [[[:cart :items 0 :qty]      :assoc 2]
                                        [[:cart :items 0 :discount] :assoc 0.1]]}
                        {:section-path [:checkout :state]
                         :section-kind :modified
                         :patches      [[[:checkout :state] :assoc :paying]]}
                        ...]}}
```

Each section heads N patches with a breadcrumb path
(`:section-path`) and a cluster-intent summary
(`:section-kind`, one of `:added` / `:removed` / `:modified`).
A patch is a 2- or 3-element vector — `[path :assoc v]` records
a new or changed leaf; `[path :dissoc]` records a key that
disappeared. The decoder flattens every section's `:patches`
back to one ordered patch list and applies via `assoc-in` /
`update-in`. Vectors and scalars are treated as leaves
(replaced wholesale via `:assoc`); element-wise vector diff
doesn't help for the typical app-db where vector values are
short. Round-trip is exact: `decode(encode(record)) = record`
for every record the runtime can produce.

**Why sections-per-cluster** (rf2-qeous). Agent queries like
"what did this cascade do?" want scoped cluster summaries
keyed by path. The flat patch list (the predecessor shape)
forced agents to re-cluster mentally. The sections projection
mirrors Causa's panel sections-per-cluster decomposition
(rf2-gfxmk Phase 1 of rf2-abts7) so the on-box developer
surface and the off-box agent surface read the same cascade
shape.

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
and `:rf.mcp/dedup-table` (rf2-lwgg8 mechanism 5). Agents
recognise the family once and pattern-match on the prefix.

### Structural dedup (rf2-obpa9)

Mechanism 5 — the structural-dedup peer of the cross-MCP
wire pipeline. The wire shape (`:rf.mcp/dedup-table`
substitution table) is identical; the algorithm
([`day8/de-dupe`](https://github.com/day8/de-dupe)) is shared.
An agent that learned the slot on a sibling server sees the
same slot here.

Persistent data structures share subtrees in memory; `pr-str`
flattens the sharing and writes every shared node out in full.
After diff-encoding
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
(`:rf.mcp/dedup-table`) matches the cross-MCP vocabulary —
an agent that learned the slot on a sibling server sees the
same slot here.

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

### Size-elision wire markers (rf2-urjnc)

Mechanism 6 — the size-elision peer of the cross-MCP wire
pipeline. The marker shape (`{:rf.size/large-elided {:path ...
:bytes ... :type ... :reason ... :hint ... :handle
[:rf.elision/at ...]}}`) is reserved cross-server per
[`spec/Conventions.md §Reserved namespaces`](../../../spec/Conventions.md#reserved-namespaces-framework-owned)
and emitted by the same framework walker
(`rf/elide-wire-value`) in every server. An agent that learned
the slot on a sibling server sees the same slot here.

After diff-encoding
(rf2-1wdzp) collapses each `:db-after` and dedup (rf2-obpa9)
pools repeated subtrees, a single large slot — say a 100KB
uploaded PDF base64 on `[:user :uploaded-pdf]` — still rides
the wire verbatim. The framework's size-elision walker
(`re-frame.core/elide-wire-value`, rf2-v9tw2) substitutes
such slots with a marker carrying a fetch handle.

**The transform**. Each frame's `:app-db` slice (in
`snapshot`) and each get-path resolved value (in `get-path`)
is run through the walker server-side, inside the eval form
sent over nREPL. The walker reads the per-frame
`[:rf/elision]` registry — populated at boot from
`:large? true` schema metadata (rf2-nwv63) — and substitutes
registered paths:

```clojure
;; Wire shape (substitution at a single elidable slot)
{:rf.size/large-elided
 {:path   [<segment>...]            ; address of the elided slot
  :bytes  <int>                     ; pr-str byte count
  :type   :map | :vector | :set | :string | :scalar
  :reason :schema
  :hint   <string-or-nil>           ; from the registry entry
  :handle [:rf.elision/at <path>]}} ; agent re-fetches via get-path
```

The marker is a SUBSTITUTION at the elided slot — not a
wrapper around the response. A 1MB app-db with a 100KB
`:large?` slot at `[:user :uploaded-pdf]` returns the
small siblings verbatim and the marker at the elided slot.
The walker recurses past containers and only elides at the
declared path (or at a leaf over threshold) — drilling INTO
the elided subtree via `get-path [:user :uploaded-pdf
:metadata]` returns the small metadata directly.

**Why server-side, not wire-side**. The walker reads the
`[:rf/elision]` registry from the live app-db. The MCP server
is a Node process that doesn't have direct access to the
running re-frame frame; the registry is reachable only inside
the eval form. The walker is the single normative emission
site for the marker — per Spec API §`elide-wire-value`,
per-tool reimplementation is prohibited. So re-frame2-pair-mcp's
snapshot and get-path tools include the walker call in the
EDN form they send over nREPL.

**Where in the pipeline**. Elision runs FIRST. The downstream
pipeline (`scrub-sensitive` → `slice-app-db-in-snapshot` →
`diff-encode-epochs` → `dedup-epochs` → wire-cap) operates on
the post-elision payload. Cap measures post-elision bytes —
a single declared-`:large?` slot can no longer blow the cap
on its own. The cap stays a backstop, not the primary
mechanism for the common case.

**Composition**:

- *Path-slicing × elision*. The walker substitutes at the
  declared path; the path-slicer then drills into the
  already-substituted value. A `snapshot {:path [:user
  :uploaded-pdf]}` against a declared-large path returns
  the marker. A `snapshot {:path [:user :uploaded-pdf
  :metadata]}` against a non-elided child returns the small
  metadata directly — the walker emits at containers it
  recognises in the registry, not at every descendent.
- *Diff-encode × elision*. Elision applies to the `:app-db`
  slice only. The `:epochs` slice (where diff-encode lives)
  is unaffected; the `:db-before` reference inside each
  epoch record is the dedup mechanism's domain, not the
  elision mechanism's.
- *Cap × elision*. Cap measures post-elision payload. When
  elision is on and a `:large?` path matches, the response
  shrinks to the marker and cap stays a backstop. When
  elision is off, the raw payload rides and cap may still
  trip — that's the rf2-rvyzy fallback.

**`elision` arg**. Every tool that surfaces `:app-db`
(snapshot, get-path) accepts an `elision` arg (boolean,
default `true`). Pass `false` to bypass the walker entirely —
useful for agents with explicit override permission that
need the full payload (e.g. a debug session inspecting the
elided slot itself, or a runtime that hasn't been taught the
registry shape yet). The default-on posture matches the
privacy / dedup defaults: shrink first, opt out explicitly.

**Cross-MCP vocabulary**. The marker key
`:rf.size/large-elided` and the handle vocabulary
`[:rf.elision/at <path>]` are reserved per
[`Conventions §Reserved namespaces / app-db keys / fx-ids`](../../../spec/Conventions.md)
and [`Spec 009 §Size elision in traces`](../../../spec/009-Instrumentation.md).
The shape is shared across re-frame2-pair-mcp and story-mcp —
an agent that learned the slot on a sibling server sees the same
slot here. The `:rf.size/*` family sits alongside the
`:rf.mcp/*` family (per-tool wire-mechanism markers like
`:rf.mcp/overflow`, `:rf.mcp/summary`, `:rf.mcp/diff-from`,
`:rf.mcp/dedup-table`).

**Wire-byte impact**. A 100KB declared `:large?` slot in a
1MB app-db compresses to ~150-byte marker on the wire — a
99.985% reduction at that slot. The rest of the slice rides
verbatim; the dominant cost falls to the surrounding small
siblings. Combined with path-slicing (`:summary` default on
snapshot), the typical `investigate-X` workflow stays well
inside the 5,000-token cap without further drill-down.

### Streaming subscribe byte+event budget (rf2-ho4ve)

Mechanism 8 — re-frame2-pair-mcp-specific, applied at the *upstream*
edge — the runtime-side queue feeding the `subscribe` tool —
rather than at the wire boundary itself.

re-frame2-pair-mcp ships a runtime-side OR-combined event-count
+ byte budget. The motivation is memory pressure: an
event-count-only buffer (`:max-buffered
500`) is misleading when event size varies by orders of
magnitude. Five small trace events fit in ~500 bytes; five
overflowed epoch records with diff-encoded 1MB app-db
references can hit 5MB. The bound the operator cares about
is bytes, not events.

**The budget**. Every active subscription carries an
OR-combined pair on the runtime side:

- `:max-buffered-events` — integer event-count cap, default
  500. The coarse backstop.
- `:max-buffered-bytes` — integer pr-str byte cap, default
  5_000_000 (~5 MB). The load-bearing bound. The unit
  matches the wire-cap's `pr-str` character count discipline
  (`token-estimate = (quot bytes 4)`), so the budgets across
  the upstream-queue and the egress-wire stay coherent.

The runtime's `enqueue!` admits the new event first, then
evicts from the FRONT of the queue until BOTH budgets hold.
Drop-OLDEST FIFO is the only sensible policy for a byte
budget — a single fat newcomer can require evicting an
arbitrary number of small predecessors, and there's no way to
know that on admission without already having admitted it.

**Eviction reporting**. On every `drain-subscription!` the
runtime returns:

```clojure
{:events          [...]                       ; queued events
 :dropped-events  <integer>                   ; evicted events since last drain
 :dropped-bytes   <integer>                   ; evicted bytes since last drain
 :overflow-reason :max-buffered-events
                 | :max-buffered-bytes
                 | nil                        ; which budget tripped LAST
 :gone?           <boolean>}
```

The counters reset on drain — each tick reports the delta
since the previous tick. `:overflow-reason` is the LAST budget
that tripped (bytes wins on a same-enqueue tie because the
byte budget tripping signals a large-payload storm — the
event-budget tripping in isolation is the easy case).

The MCP server forwards these on every `notifications/progress`
frame's `:_meta.data` slot (with the keyword stringified per JSON-RPC
constraints) and accumulates them onto the final tools/call summary.
The `_meta` wrapper is intentional: the official MCP SDK preserves it
in progress callbacks while stripping unknown top-level progress
fields. The agent host pattern-matches on `:overflow-reason` to decide
which budget to raise — bytes-bound storms call for
`max-buffered-bytes` (or a tighter `filter`); event-bound
storms call for `max-buffered-events`.

**Why two budgets, not just one**. The byte budget alone
suffices in principle (bytes is what hurts), but the event
budget is cheap to maintain and a useful coarse cap against
runaway subscriptions with a pathologically chatty filter —
"please don't keep more than 500 events even if they're
small". The event budget rarely trips in practice for normal
filters but is the backstop for the chatty-filter case the
byte budget can't catch (lots of tiny events, none over
budget individually but together swamp drain throughput).

**Cross-MCP vocabulary**. The `:overflow-reason` keywords
(`:max-buffered-events`, `:max-buffered-bytes`) sit in the
runtime's own namespace because they're a property of the
upstream queue, not a wire marker. The MCP wire payload's
`:_meta.data` slot stringifies them for JSON-RPC. The
`:dropped-events` / `:dropped-bytes` field names mirror the
existing `:dropped-sensitive` slot already used for the
privacy filter (Spec 009 §Privacy / rf2-3cted) — the agent
host learns one shape: "dropped count + the reason it was
dropped".

## Per-session response cache (rf2-3rt1f)

**The principle**. The common re-frame2-pair-mcp workflow rhythm is
*inspect state → dispatch one event → inspect state again*.
The second inspect is byte-identical to the first when nothing
else mutated, yet today the wire pays the full app-db cost
twice. A small per-session cache keyed on a result-hash
collapses the second pay to a sub-100-byte marker.

**Mechanism**. Each MCP server process holds an 8-slot LRU,
keyed by `(tool, args-fingerprint)`. Each entry records the
hash of the prior response's serialised text plus the
`:sent-at` timestamp of first emission. On every read-tool
invocation:

1. Run the tool normally — compute the MCP result.
2. Hash the result's `:text` slots.
3. Look up `(tool, args-fingerprint)` in the LRU.
   - **Miss**: store `{:hash h :sent-at now :tool t}`; return
     the original result unchanged.
   - **Hit, matching hash**: emit
     ```clojure
     {:rf.mcp/cache-hit
      {:hash            h
       :unchanged-since <ms>
       :tool            <name>
       :hint            "<agent-host instruction string>"}}
     ```
     instead of the full payload. Touch the entry to the tail
     (LRU bookkeeping).
   - **Hit, different hash**: state moved on; store the new
     hash + `:sent-at`, return the fresh result.

**Why hash the result, not app-db directly**. The bead
proposed `(hash app-db)`. The framing here is one step
downstream: by the time the result is built, it has been
path-sliced, summarised, diff-encoded, deduped, scrubbed.
Two calls with the same args against the same upstream state
produce the same serialised text — so hashing the text catches
the same hit and is robust against every transform in the wire
pipeline. One mechanism covers every read tool uniformly
instead of per-tool hash strategies.

**Scope**. The cache saves wire bytes, not the nREPL round-
trip — the tool still runs server-side and the result is
built locally. The byte saving is the one the bead targets.
Saving the round-trip too needs a server-side hash precheck
(precompute `(hash app-db)` in the runtime, ship the hash
first, only ship the body on miss) — out of scope for
rf2-3rt1f, filed as a follow-on bead.

**Why opt-in by default**. Agent hosts that haven't been
taught the `:rf.mcp/cache-hit` marker shape would receive a
sub-100-byte marker instead of the expected payload on the
second call and either error out or, worse, silently confuse
their internal model. Default-`false` matches the `dedup`
default before its flip (rf2-obpa9 left dedup opt-out until
host coverage was confirmed). Once the marker shape is in the
re-frame2-pair skill catalogue and agents pattern-match on it
routinely, the default can flip.

**Why 8 slots**. Sized for the typical session's
"inspect different frames + a couple of get-path calls"
working set, not for full coverage. An LRU at 8 fits the
session's hot working set without retaining state long enough
to mask a slow background mutation. Capacity is a `def`-level
constant; future ergonomics work may surface it as an env
var.

**Bypass policy**. Action tools (`dispatch`, `eval-cljs`,
`tail-build`) bypass — their return value is the result of an
action, not a read. Streaming tools (`subscribe`,
`unsubscribe`) bypass — they emit progress notifications, not
single payloads. `:isError` results bypass — a transient
failure must not mask a future successful read.

**Cross-MCP vocabulary**. `:rf.mcp/cache-hit` is the wire
marker name. It joins the `:rf.mcp/*` family already declared
for `:rf.mcp/overflow` (rf2-rvyzy), `:rf.mcp/dedup-table`
(rf2-obpa9), `:rf.mcp/summary` (rf2-tygdv). The agent learns
the namespace once and recognises every marker.

## Tool verbs follow the cross-MCP convention

Tool names in re-frame2-pair-mcp's catalogue pick from the verb table at
[`tools/mcp-conformance/NAMING.md`](../../mcp-conformance/NAMING.md)
(rf2-mzf1r) — the canonical home for the cross-MCP verb vocabulary
shared with story-mcp. The shared verbs the pair pins are
`get-` / `list-` / `read-` / `discover-` /
`restore-` / `reset-` / `register-` / `unregister-` / `run-` /
`preview-` / `record-as-` / `tail-` plus the bare universals
`dispatch`, `eval-cljs`, `subscribe`, `unsubscribe`, plus the
mega-op bare verbs (`snapshot`, `trace-window`, `watch-epochs`)
reserved for derived projections that span multiple registry kinds.

Pair2-mcp's fourteen current tools (`discover-app`, `eval-cljs`,
`dispatch`, `tail-build`, `snapshot`, `trace-window`, `watch-epochs`,
`get-path`, `subscribe`, `unsubscribe`, `list-subscriptions`,
`handler-meta`, `list-handlers`, `get-re-frame2-pair-instructions`) are all
conformant against existing verbs after the rf2-4y595 rename
(`subscription-info` → `list-subscriptions`, `registry-list` →
`list-handlers` — see NAMING.md's audit table). New tools land
against an existing verb, or via a Lock entry in
[`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md) plus an extension to
the canonical table.

## Backed by the framework's principles

When in doubt, defer to the framework's [Principles](../../../spec/Principles.md):

- **Regularity over cleverness** — one obvious way to do a thing.
  The fourteen op names and shapes are stable.
- **Named things over anonymous things** — every op has a stable
  name; every reason keyword in an `:ok? false` response is stable.
- **Public query surfaces** — re-frame2-pair-mcp reads only what the
  framework's public registrar, trace bus, and epoch-history
  surfaces expose, through the `re-frame2-pair.runtime` namespace.
- **Deterministic execution** — a `dispatch` op with the same args
  produces the same effect; no hidden state in the server beyond
  the connection and the pending-map.
- **No core.async** — per [`feedback_no_core_async`](../../../AGENTS.md),
  re-frame2-pair-mcp does not pull core.async as a dependency or use it as a
  building block. The async return-shape is JavaScript Promises
  (the Node host's native async substrate).

re-frame2-pair-mcp is a downstream artefact of the framework's AI-first
discipline. The principles above are what *re-frame2-pair-mcp adds* over the
framework's baseline; everything below is inherited.
