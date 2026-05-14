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

This is the downstream-EPs-consume-foundation rule applied to tools:
story-mcp surfaces what Story already emits and registers; it does
not invent new substrates. Concretely, when implementation surfaces
a runtime gap (e.g., "I want to list registered modes but Story
doesn't expose a public accessor"), file a `bd` bead against Story
or `spec/007-Stories.md`. Don't bolt a parallel surface onto the
MCP server.

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
and is the same shape pair2 takes (`re-frame-pair2` runtime vs.
`re-frame-pair2-mcp` adapter).

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
`re-frame.story-mcp.config/mcp-protocol-version`. Floating-version
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

The two write tools (`register-variant`, `unregister-variant`) sit
behind `re-frame.story-mcp.config/allow-writes?` (default `false`).
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

The motivation is the 2026 trend axis. Microsoft's April 2026
recommendation (Playwright CLI **over** Playwright MCP for
coding agents) was driven by MCP responses being roughly 4×
larger in tokens than the equivalent CLI output. Anthropic's
own router-SKILL guidance lands at the same ~5k ceiling. An
agent host with a 200k context window can absorb a handful of
20k tool returns, but the realistic working session fires
dozens of tool calls — story-mcp's `run-variant` (whose
output is the variant's rendered identity plus assertion
results) and `list-variants` on a populous library are the
exposed surfaces here. A single oversized response burns the
budget the agent needs for the next ten ops.

The discipline applies across three axes:

- **Pagination / cursor for unbounded surfaces.**
  `list-variants`, `list-modes`, `list-assertions`, and any
  read tool whose return size is a function of registry size
  MUST accept a `:limit` argument and return a `:cursor` for
  continuation. The default `:limit` MUST keep the response
  under the cap. No unbounded list responses; no "best-effort"
  omission of pagination.
- **Summarisation modes for rich payloads.** Ops with rich
  per-item shape (`run-variant`, `snapshot-identity`,
  `variant->edn`) MUST expose a `:mode` argument with at
  least `:count` (return totals / pass-fail counts only),
  `:sample` (return a bounded prefix or stratified sample
  with sizes attached), and `:full` (return everything,
  paginated). The default MUST be `:sample` for any op whose
  `:full` payload can exceed the cap. The self-healing loop
  (run → read-failures → fix) naturally biases towards
  failure-only payloads, which is the `:sample` mode under a
  failure filter.
- **Streaming over batch where appropriate.** If story-mcp
  later grows a streaming tool (e.g., a long-running batch
  variant run), each notification MUST stay under the cap;
  the agent host meters consumption. Batching is reserved
  for ops whose payload is naturally bounded and small.

The cap is enforced at the runtime boundary, not just
documented. Each tool's reference entry in
[`002-Tool-Registry.md`](002-Tool-Registry.md) carries a
**typical-token** hint (e.g., `~0.8k`, `~3k under :sample`)
and a **cap-reached** behaviour note (truncate-with-cursor,
return `isError: true` with `:reason :budget-exceeded` and a
hint to narrow the filter or switch mode). The hints surface
in `list-tools` so the agent can plan ahead.

This is the load-bearing budget posture for story-mcp's
agent-host workflow: keep the per-op cost predictable, push
the agent to ask for what it actually needs, and never let a
single op blow the session.

## Tool verbs follow the cross-MCP convention

Tool names in story-mcp's catalogue pick from the verb table at
[`tools/mcp-conformance/NAMING.md`](../../mcp-conformance/NAMING.md)
(rf2-mzf1r) — the canonical home for the cross-MCP verb vocabulary
shared with pair2-mcp and (planned) causa-mcp. The shared verbs the
triplet pins are `get-` / `list-` / `read-` / `discover-` /
`restore-` / `reset-` / `register-` / `unregister-` / `run-` /
`preview-` / `record-as-` / `tail-` plus the bare universals
`dispatch`, `eval-cljs`, `subscribe`, `unsubscribe`. Story-mcp does
NOT ship `dispatch`, `eval-cljs`, or the streaming pair — its
mutation surface is `register-variant` / `unregister-variant` and
its runtime is JVM-side without a browser eval substrate.

Story-mcp's nineteen current tools are conformant. Two grandfathered
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
  Tool names and shapes are stable; the 19-tool surface is small on
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
