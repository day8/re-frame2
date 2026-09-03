# Principles

The load-bearing principles. When a design call has two reasonable
options, these are the tie-breakers. Implementers and contributors
should be able to read this doc and reach the same answers
story-mcp already reached.

These are downstream of the framework's [Principles](../../../spec/Principles.md)
and Story's own [Principles](../../story/spec/Principles.md); they
are *story-mcp-specific*. Where they overlap, this doc cites instead
of repeating.

## Thin adapter — consumes Story, doesn't extend the framework

story-mcp is a **downstream consumer** of Story's public read and
(gated) write surface. It must not add:

- New registries.
- New dispatch types.
- New effect substrates.
- New component substrates.

Every tool call routes through Story's existing public API —
`re-frame.story/registrations`, `re-frame.story/run-variant`,
`re-frame.story/snapshot-identity`, `re-frame.story/variant->edn`,
the `*`-suffix runtime helpers (`reg-variant*`, `unregister!`).
Nothing here registers new framework primitives.

Story-MCP surfaces what Story already emits and registers; it does not
invent new substrates. When the implementation exposes a runtime gap,
extend Story's public surface rather than bolting a parallel registry or
query path onto the MCP server.

## Transport-machinery isolation — separate jar from Story

story-mcp ships as `day8/re-frame2-story-mcp`, a distinct artefact
from `day8/re-frame2-story`. The split is load-bearing:

- The MCP server's transport dependencies (stdio adapter, JSON-RPC
  framing, JSON codec) are consumed only by agent hosts. The vast
  majority of Story consumers never load them.
- Splitting at the jar boundary keeps Story's core lean and keeps
  story-mcp's churn off Story's release cadence.
- The dependency arrow flows tool → implementation; story-mcp is on
  a separate classpath root and is never reachable from production
  CLJS bundles.

The pattern mirrors `tools/machines-viz/` vs.
`tools/machines-viz-mcp/` (per [`tools/README.md`](../../README.md))
and is the same shape re-frame2-pair takes (`re-frame2-pair` runtime vs.
`re-frame2-pair-mcp` adapter).

## MCP-over-stdio, not an IDE plugin

The agent-host integration contract is **Model Context Protocol over
stdio**, not a per-editor extension and not a HTTP server.

By implementing MCP, story-mcp works with every MCP-capable host
(Claude Code, Cursor, Copilot, and whatever lands next) without
per-host plumbing. The cost of one stdio JSON-RPC server is paid
once; N editor extensions would pay the cost N times and age worse.

Stdio also keeps the security posture trivial: the agent host
launches the server as a subprocess; there is no listening port, no
auth surface, no CORS dance. stderr is reserved for diagnostics;
stdout carries only protocol traffic.

## Same-runtime Story access

Tool handlers call `re-frame.story` directly in the server JVM. The
stdio transport connects the agent host to Story-MCP; it does not connect
Story-MCP to a browser. There is no implicit nREPL or Tool-Pair hop.

This boundary makes host capability explicit: JVM/CLJC Story operations
use registrations and frames in the server process, while browser-only
surfaces (the CLJS substrate registry, the a11y-panel violations atom)
are UNREACHABLE and return a machine-readable capability-unavailable error
(`:rf.error/story-mcp-capability-unavailable`) rather than a false-empty
success. Capability absence is represented separately from an empty
answer — an agent must never read 'the host cannot look' as 'the answer
is empty' (rf2-3fc89f.21). A future remote bridge would supply the
provider seam these reads gate on — a new transport contract, not an
implementation detail of the current server.

## Stage-marker independence

story-mcp carries its own stage constant
(`re-frame.story-mcp.config/stage = :mcp`) that advances when *its*
surface extends. Story's own `re-frame.story/stage` advances on its
own cadence.

The two artefacts have **independent stage progression**: a release
of Story does not force a release of story-mcp, and vice versa. The
MCP server can ship `:mcp` at v1 while Story is at `:sota-features`
at v1 — the constants serve different runtimes.

Concretely: a story-mcp release that adds a new tool advances `:mcp`
and ships on its own; Story-side work doesn't block it. A Story
release that extends the registry (e.g. a new canonical assertion)
is picked up automatically by `list-assertions` without a story-mcp
release.

## Protocol version pinned

The MCP protocol revision is pinned at `2025-06-18` in
`re-frame.story-mcp.config/protocol-version`. Floating-version
semantics ("whatever the client speaks") are rejected:

- **Predictability for agents.** An agent that connects to one
  story-mcp instance can rely on the protocol shape being identical
  at every other instance of the same version.
- **Upgrade auditing.** Bumping the pin is a deliberate change with
  a corresponding commit; the diff makes clear what the protocol
  change is. Floating would let drift accumulate silently.
- **Tested surface.** The protocol tests target the pinned version.
  Floating would expand the test matrix.

Future MCP versions land as a deliberate bump of the constant,
accompanied by updates to whichever methods or shapes the new version
changes.

## Write surface gated by default

The Write category — `register-variant` and
`unregister-variant` — sits behind
`re-frame.story-mcp.config/allow-writes?` (default `false`).
Both are gated outright.
Three input paths flip the gate at boot: `--allow-writes` CLI flag,
`-Drf.story-mcp.allow-writes=true` sysprop,
`RF_STORY_MCP_ALLOW_WRITES=true` env var. Read tools are never
gated.

The default-off posture is the right one for three reasons:

- **CI safety.** A CI run that accidentally opens the write surface
  could mutate the registry mid-test. Default-off keeps CI safe.
- **Host trust.** Some dev hosts want read-only pair-coding (the
  agent reads the library; the human writes the code). Default-on
  would force-impose the wrong shape.
- **Audit clarity.** Opening the gate is a deliberate operator
  action; the presence of the flag in the launch command is
  auditable.

The self-healing loop (write → run → read-failures → fix) is the
value-add, but it must be opt-in.

## Clean errors over silent failures

Every recoverable error path returns a structured, agent-actionable
response. The run-loop survives every recoverable error class:

- Malformed JSON → `-32700`, continue reading.
- Unknown method → `-32601`, continue reading.
- Tool dispatch exception → `-32603` with the exception message,
  continue reading.
- Gated write call → `isError: true` with the documented hint
  ("restart with `--allow-writes`"), **not** `-32601`.
- Tool-execution failure (unknown variant id, schema failure) →
  `isError: true` with a structured `:reason`, not a protocol
  error.

The distinction matters: JSON-RPC error codes signal "the server is
broken"; `isError: true` on a tool result signals "the tool ran and
the call failed for a documented reason". Conflating the two would
make agents think the server is broken when it is in fact
configured.

## Self-contained jar — no external resources at boot

The agent-onboarding text returned by `get-story-instructions` lives
inline in the source (`re-frame.story-mcp.tools.dev/story-instructions-text`),
not in an external resource file. There is no `io/resource` lookup
at boot, no classpath scan, no native-image packaging quirk.

The principle generalises: anything story-mcp needs at boot is
either compiled into the jar or supplied at launch (CLI flag,
sysprop, env var). The server is a single artefact that boots
deterministically from its own jar plus the launch-time
configuration. No surprise file reads, no environment-dependent
discovery dance.

## Tight token budget per response

Each MCP tool response is bounded at **≤ 5,000 tokens** by
default. The cap is normative, not aspirational: a tool that
cannot answer inside the budget MUST trim, summarise, or
paginate rather than over-spend.

The cross-server contract — default cap, override slot name
(`max-tokens`), overflow marker key (`:rf.mcp/overflow`),
agent-host retry contract, and chained-budget rules when an agent
attaches the triplet in one session — lives at
[`tools/mcp-conformance/TOKEN-BUDGETS.md`](../../mcp-conformance/TOKEN-BUDGETS.md).
The three-axis discipline below is story-mcp's expansion of that
contract.

An agent session makes many calls, and Story's rich run results and
registry listings can grow quickly. A per-response cap keeps one call
from consuming the context needed for the rest of the workflow.

The discipline applies across three axes:

- **Pagination / cursor for unbounded surfaces.**
  `list-stories`, `list-modes`, `list-decorators`,
  `list-assertions`, `list-tags`, and any read tool whose return
  size is a function of registry size MUST accept a `:limit`
  argument and return a `:cursor` for continuation. The default
  `:limit` MUST keep the response under the cap. No unbounded
  list responses; no "best-effort" omission of pagination.

  The implementation uses a default `:limit` of 25 and maximum 200,
  base64-encoded opaque cursor with whole-set fingerprint for
  staleness detection); see
  `tools/story-mcp/src/re_frame/story_mcp/tools/cursor.cljc`. The
  `get-*` / `<thing>->edn` tools are exempt — their return is a
  single record bounded by the body size, not a function of
  registry size; the wire-boundary cap catches overruns there.
- **Cap + dedup + per-call override for rich payloads.** Ops with
  rich per-item shape (`preview-variant`, `run-variant`)
  re-key the same value into multiple derived
  slots (`:app-db` + `:rendered-hiccup` + `:snapshot`). Those carry
  `:dedup-eligible? true`: their `:structuredContent` is run through
  `re-frame.mcp-base.dedup` (collapsing repeated subtrees into a flat cache map
  under the cross-MCP `{:rf.mcp/dedup-table …}` marker) BEFORE the
  token-cap measures the payload. The per-call `:max-tokens` override
  (integer cap; `0` disables; absent ⇒ the `mcp-base.overflow`
  default) and the per-call `:dedup` boolean (default `true`) let a
  caller tune the budget. When the post-dedup payload still exceeds
  the cap, the wire pipeline replaces it with the structured
  `{:rf.mcp/overflow …}` marker carrying a per-tool next-step hint —
  it does NOT silently truncate. The self-healing loop
  (run → read-failures → fix) naturally biases towards failure-only
  payloads via `read-failures`' `:failures` filter.
- **Streaming over batch where appropriate.** If story-mcp
  later grows a streaming tool (e.g., a long-running batch
  variant run), each notification MUST stay under the cap;
  the agent host meters consumption. Batching is reserved
  for ops whose payload is naturally bounded and small.

The cap is enforced at the runtime boundary
(`re-frame.story-mcp.tools.wire-pipeline/invoke-tool` →
`re-frame.mcp-base.cap/apply-cap`), not just documented. Each tool's
descriptor carries a `:typicalTokens` hint (the committed
`tool-descriptors.edn` records it per row), surfaced in `tools/list`
so the agent can plan ahead. When a response exceeds the cap the
payload is replaced with the `{:rf.mcp/overflow …}` marker carrying
the tool-specific next-step hint (raise `:max-tokens`, narrow a
filter, shorten `:duration-ms`, …) — a structured signal the agent
acts on, never a silent truncation.

This is the load-bearing budget posture for story-mcp's
agent-host workflow: keep the per-op cost predictable, push
the agent to ask for what it actually needs, and never let a
single op blow the session.

## Structural dedup at the wire boundary

Two tools — `preview-variant` and `run-variant` —
pass their `:structuredContent` payload through `re-frame.mcp-base.dedup` before
the wire-boundary token-cap check. Repeated subtrees in the payload —
the same `:app-db` slice reappearing in `:rendered-hiccup` and
`:snapshot`
— collapse into a flat cache map keyed by `de-dupe.cache/cache-N`
namespaced symbols.

### Selective by design

Dedup is applied only where it pays for itself. The eligibility
contract is the descriptor flag `:dedup-eligible? true`, asserted at
load time by `tools/story-mcp/test/re_frame/story_mcp/tools/dedup_test.clj`'s
`descriptor-dedup-eligibility-matches-the-documented-set`. The eligible
set is exactly `preview-variant` and `run-variant`; adding another
eligible tool requires updating both the descriptor AND that test's
canonical set; the friction is deliberate (mirrors pair-mcp's selective
`dedup-property` assignment in `descriptors_data.cljs`).

Every other tool ships a small, bespoke shape — `list-stories`,
`get-story`, `register-variant`, the docs / read-failures family —
where the cache-of-one wrap would add bytes for zero compression. Those
tools emit raw `:structuredContent` and carry no `:dedup` slot in their
input schema.

### Wire shape

A deduped payload is wrapped in the cross-MCP marker:
`{:rf.mcp/dedup-table <cache-map>}`. The key is sourced from
`re-frame.mcp-base.vocab/dedup-table-key` — byte-identical with
re-frame2-pair-mcp's emissions, so an agent host that learned the
slot on either server reconstructs the payload uniformly via
`(re-frame.mcp-base.dedup/expand cache-map)`. Per `tools/mcp-conformance/wire-vocab/`
the marker key is a cross-MCP reserved literal under the `:rf.mcp/*`
single-root scheme (Conventions §Reserved namespaces).

### When dedup runs

At the dispatch boundary (`re-frame.story-mcp.tools.wire-pipeline/invoke-tool`),
after the handler emits its result map and before
`re-frame.mcp-base.cap/apply-cap` measures it. The ordering is
load-bearing: dedup shrinks first so the cap sees the post-dedup
size; running the cap first would replace a payload with an overflow
marker that dedup would have brought under-budget. Same invariant as
re-frame2-pair-mcp's wire-pipeline.

### Opt-out

The `:dedup` MCP arg (boolean, default `true`) skips dedup when
`false`. Useful for ad-hoc reads when the agent host hasn't been
taught to call `expand`. Lives on dedup-eligible tools' `:inputSchema`
via `schemas/with-dedup`. Cross-MCP shape with re-frame2-pair-mcp's
arg — same name, same default, same semantics; an agent that learned
the arg on either server uses it uniformly here.

### Why `de-dupe-eq` not `de-dupe`

Most subtrees the story-mcp surface emits are equality-shared rather
than identity-shared — assertion records and rendered hiccup are
synthesised fresh per run, not interned. `de-dupe-eq` is the
equality-based variant that actually fires on these cross-record
duplicates. Same rationale as re-frame2-pair-mcp's choice;
documented identically at the call site.

### Idempotence on no-dedup-opportunity

A payload with no repeated subtrees deduplicates to a one-entry
cache (`{:de-dupe.cache/cache-0 <root>}`) whose wire shape is
slightly larger than the input. The encoder short-circuits the wrap
in that case via an `empty-payload?` guard — empty collections,
scalars, and nil pass through unchanged.

### Error envelopes skip dedup

Tool-execution errors (`{:isError true ...}`) carry small bespoke
structuredContent payloads — `:rf.error` plus `:tool` /
`:exception` / `:data`. Wrapping that under `:rf.mcp/dedup-table`
loses the friendly inspection shape for zero compression win, so
`invoke-tool` skips dedup on error results.

## Tool verbs follow the cross-MCP convention

Tool names in story-mcp's catalogue pick from the verb table at
[`tools/mcp-conformance/NAMING.md`](../../mcp-conformance/NAMING.md),
the canonical home for the cross-MCP verb vocabulary
shared with re-frame2-pair-mcp. The shared verbs the
pair pins are `get-` / `list-` / `read-` / `discover-` /
`restore-` / `reset-` / `register-` / `unregister-` / `run-` /
`preview-` / `record-as-` / `tail-` plus the bare universals
`dispatch`, `eval-cljs`, `subscribe`, `unsubscribe`. Story-mcp does
NOT ship `dispatch`, `eval-cljs`, or the streaming pair — its
mutation surface is `register-variant` / `unregister-variant` and
its runtime is JVM-side without a browser eval substrate.

Story-MCP's current tools are conformant. Two documented
deviations carry explicit catalogue exceptions in
[`NAMING.md`](../../mcp-conformance/NAMING.md):

- **`variant->edn`** — Clojure-idiomatic projection arrow; the
  cross-MCP table accepts `<thing>->edn` as a canonical-form
  serialiser shape distinct from `get-<thing>-edn`.
- **`snapshot-identity`** — bare-noun read of a content hash; the
  cross-MCP table accepts bare-noun reads when the return is a
  single primitive.

New tools land against an existing verb, or via a Lock entry in
[`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md) plus an extension
to the canonical table.

## Backed by the framework's principles

When in doubt, defer to the framework's [Principles](../../../spec/Principles.md)
and Story's [Principles](../../story/spec/Principles.md):

- **Regularity over cleverness** — one obvious way to do a thing.
  Tool names and shapes are stable; the surface is small on
  purpose.
- **Named things over anonymous things** — every tool has a stable
  name; every error reason keyword is stable.
- **Public query surfaces** — story-mcp reads only what Story's
  public API exposes. No registrar pokes; no internal-namespace
  reaches.
- **EDN-first** — variant bodies cross the wire as EDN (canonical
  form for `variant->edn`; preferred map form for `register-variant`).
  The data-only constraint from Story is preserved across the JSON
  boundary.
- **No core.async** — per [`feedback_no_core_async`](../../../AGENTS.md),
  story-mcp does not pull core.async as a dependency or use it as a
  building block.

story-mcp is a downstream adapter on Story's surface. The principles
above are what *story-mcp adds* over the Story baseline; everything
else is inherited.
