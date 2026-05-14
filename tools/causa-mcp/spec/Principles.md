# Principles

The load-bearing principles for Causa-MCP. When a design call
has two reasonable options, these are the tie-breakers.
Implementers and contributors should be able to read this doc
and reach the same answers Causa-MCP already reached.

These are downstream of the framework's
[Principles](../../../spec/Principles.md) and of Causa's
[Principles](../../causa/spec/Principles.md) (Causa-MCP is
Causa's agent face — the panel's principles apply transitively
to anything Causa-MCP does). The principles below are what
*Causa-MCP adds* on top.

## Tool consumes the framework; doesn't extend it

Causa-MCP is a **downstream consumer** of re-frame2's existing
surfaces. It must not add:

- New registries.
- New dispatch types.
- New effect substrates.
- New component substrates.

The eighteen tools route through the existing
**injected-runtime namespace** (`day8.re-frame2-causa.runtime`,
which rides Causa-the-panel's preload — see
[`000-Vision.md` §Two namespaces, two sides](./000-Vision.md#two-namespaces-two-sides))
via `cljs-eval`. The MCP-server-side code (`day8.re-frame2-causa-mcp.*`,
Node-only) is the *renderer* of those eval forms; nothing new is
registered against the framework on the browser side, and the
Node-side server is invisible to the consumer app's runtime.

This is the
[downstream-EPs-consume-foundation rule](../../../spec/Principles.md)
applied to tools: tools observe and exercise what the framework
emits and registers; they do not invent new substrates.

Concretely: when implementation surfaces a runtime gap (e.g.
"I want to inspect cross-frame causality but the trace bus
doesn't expose `:parent-frame`"), file a `bd` bead against
[`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md)
or the relevant Causa spec. Don't bolt a parallel surface onto
the MCP server.

## MCP-server-ns and injected-runtime-ns are distinct

Causa-MCP's code lives on two sides of an stdio JSON-RPC pipe;
each side has its own namespace root, and **the spec calls them
out separately so the implementation can too** (same posture as
pair2-mcp's `re-frame-pair2-mcp.*` / `re-frame-pair2.runtime`
split). The full table — including which side each ns lives in,
what loads it, and what it's responsible for — is at
[`000-Vision.md` §Two namespaces, two sides](./000-Vision.md#two-namespaces-two-sides).

The tie-breaker this principle hands implementers and reviewers:

- **MCP-server-side concern?** Code goes under
  `day8.re-frame2-causa-mcp.*`, lives in `tools/causa-mcp/src/`,
  ships only inside the Node-side `:node-script` artefact, is
  never `:require`-d from a browser bundle. Examples: stdio
  framing, bencode/nREPL bridge, port discovery, eval-form
  rendering, the `subscribe` notification pump, agent-host
  capability negotiation.
- **Injected-runtime-side concern?** Code goes under
  `day8.re-frame2-causa.runtime` (parented by Causa-the-panel's
  `day8.re-frame2-causa.*` root, because the runtime rides the
  panel's `:preloads`), lives in the panel's preload classpath,
  is the eval target of the MCP server's rendered forms.
  Examples: per-frame state accessors backing the eighteen
  tools, the `current-origin` dynamic var, the `session-id`
  marker, hot-reload re-inject probes.

A surface that *needs* to straddle both sides — e.g. a wire
schema, an MCP marker keyword like `:rf.mcp/overflow`, the
verb catalogue at
[`tools/mcp-conformance/NAMING.md`](../../mcp-conformance/NAMING.md) —
is owned by the cross-cutting `tools/mcp-conformance/` spec
folder, not duplicated into either ns.

**Bundle isolation forbids the conflation.** Per
[`tools/README.md`](../../README.md), nothing in `implementation/`
or a consumer app's preload classpath may pull MCP-server code.
If a single namespace held both roles, the consumer app's
preload would pull the Node-side
`@modelcontextprotocol/sdk` import + the bencode wire framer +
the stdio plumbing — wreckage the
[`tools/causa/`](../../causa/) artefact already pays the lint
gate to avoid. Two roots, two locations, two roles. Reviewers
catch the leak at the `:require` line; the spec catches it at
the architecture line.

The principle has zero impact on the eighteen-tool catalogue
(per [`DESIGN-RATIONALE.md` Lock #5](./DESIGN-RATIONALE.md), as
amended by [Lock #12](./DESIGN-RATIONALE.md)) and zero impact on
the MCP wire shape (per Locks #1–#3). It's a naming + layout
rule that costs nothing at the spec level and saves a "wait,
which side am I writing?" pause at every impl PR.

Captured as Lock #11 in [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md).

## Origin tagging is the convention, not a suggestion

Every Causa-MCP-driven side-effect on the trace bus carries
`:tags :origin :causa-mcp`. The tag is set at the entry point of
each mutating tool (`dispatch`, `reset-frame-db`,
`restore-epoch`) and at the boundary of `eval-cljs` (any
dispatch the eval'd form triggers inherits the tag — the
**injected-runtime** install hook reads
`day8.re-frame2-causa.runtime/current-origin` for the dynamic
extent of the eval call; the MCP-server side
(`day8.re-frame2-causa-mcp.*`) renders the binding form, the
browser side holds the dynamic var — see
[`000-Vision.md` §Two namespaces, two sides](./000-Vision.md#two-namespaces-two-sides)).

This is the **load-bearing differentiator** against generalist
agent surfaces (Chrome DevTools MCP's `evaluate_script` is
untagged — an agent hand-rolling `js/window.dispatch(...)` is
indistinguishable from a real user click). It costs nothing at
the call site and the audit-trail payoff is substantial:

| Question | Trace filter |
|---|---|
| What did the agent do this session? | `get-trace-buffer {:filter {:origin :causa-mcp}}` |
| Did the agent or the user fire `:cart/checkout`? | `get-trace-buffer {:filter {:event-id :cart/checkout}}` then read `:tags :origin` on each match |
| Stream just my own mutations as they happen | `subscribe {:topic :trace :filter {:origin :causa-mcp}}` |

The full origin vocabulary (`:app` / `:pair` / `:causa-mcp` /
`:story` / `:test`) is the
[Spec 009 §Origin axis](../../../spec/009-Instrumentation.md);
Causa-MCP's job is to honour it.

### Boundary semantics of `eval-cljs` origin tagging

The `eval-cljs` origin tag is set by `binding` the
`day8.re-frame2-causa.runtime/current-origin` dynamic var around
the evaluation of the eval'd form. The binding's extent is the
**synchronous body** of the eval call only. Any side-effect the
form schedules asynchronously — `(js/setTimeout #(dispatch [...]) 100)`,
`(go (>! chan ...))`, a Promise callback, an event listener
registered inside the eval — fires its dispatch **outside** that
dynamic extent and so does not inherit `:origin :causa-mcp`.

**Picked posture.** Causa-MCP accepts the boundary as
synchronous-only. Async dispatches launched from `eval-cljs`
land tagged according to the runtime's ambient origin at the
moment they fire (typically `:app`, or `:rf/unknown` if the
substrate fires the callback without a dispatch wrapper). This
is a known incompleteness of the audit trail — not a privacy
or authority leak, only a tagging-coverage gap.

**Why this posture and not async-substrate patching.** Two
alternatives were considered: (a) reject async forms before
eval (impractical — the static analysis is broad and brittle),
(b) patch the async substrate (`js/setTimeout`, `cljs.core.async`
go-loops, Promise resolution) to capture and replay the dynamic
var. Option (c)-as-picked here mirrors the precedent
`tools/pair2-mcp/` already ships (no setTimeout wrapper in
`tools/pair2-mcp/src/re_frame_pair2_mcp/tools.cljs`); the
runtime-modification cost of (b) is high and the threat model
(prompt-injected agent smuggling untagged async dispatches)
does not justify the substrate-rewrite blast radius pre-alpha.

**Mitigation for the impl pass.** The `current-origin` `binding`
MUST wrap the synchronous eval body — that is the load-bearing
contract the impl pass tests pin (see
[`findings/MUST-inventory.md`](./findings/MUST-inventory.md) row
I6). The async-escape gap is documented as known incomplete,
not silently shipped. A future framework-side change that adds
origin-preserving wrappers around the standard async substrate
would compose under the same `current-origin` contract without
re-spec'ing this section.

Captured as Lock #4 in [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md).

## EDN canonical; JSON the wire

Causa-MCP's args and returns travel as MCP-shaped JSON but
**the canonical form is EDN-encoded strings inside the JSON
text payload**. The catalogue ships with EDN-encoded `filter`
maps, EDN-encoded `path` arguments, EDN-encoded return values.

Why: re-frame2's runtime speaks EDN. A `:filter` map of
`{:operation :event :origin :causa-mcp :since-ms 5000}` cannot
round-trip through JSON without losing keyword shapes. Encoding
the filter as an EDN string inside the JSON payload preserves
the runtime's vocabulary; the server parses with
`cljs.reader/read-string` at the runtime boundary.

The convention is the same shape pair2-mcp uses; see
[`tools/pair2-mcp/spec/003-Tool-Catalogue.md`](../../pair2-mcp/spec/003-Tool-Catalogue.md)
for the wire-format details.

## MCP, not an IDE plugin

The agent-host integration contract is **Model Context Protocol
over stdio**, not a per-editor extension.

By implementing MCP, this artefact works with every MCP-capable
host (Claude Code, Cursor, Copilot, and whatever lands next)
without per-host plumbing. The cost of one stdio JSON-RPC server
is paid once; the alternative — N editor extensions, each
re-implementing the same tool surface — pays the cost N times
and ages worse.

A custom WebSocket protocol was considered and rejected for the
same reason in pair2-mcp; Causa-MCP inherits the lock.

Captured as Lock #2 in [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md).

## Single persistent nREPL socket

One TCP socket to the shadow-cljs nREPL is opened on first need
and held for the lifetime of the session. Subsequent ops reuse
the socket without reconnecting.

Same break-even calculus as pair2-mcp: per-op latency drops from
~700ms (bash startup + babashka startup + fresh nREPL connect
per call) to ~5–50ms (one bencode round-trip on the open socket).
A typical Causa-MCP session fires dozens to hundreds of ops.

Captured as Lock #3 in [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md).

## Stage-marker-independent

Causa-MCP must work against any conforming re-frame2 runtime. It
does not depend on a specific shadow-cljs build configuration, a
specific stage marker, or a specific re-frame2 release line.

Concretely:

- The `build` argument defaults to `"app"` but is configurable
  on every op (and via the `SHADOW_CLJS_BUILD_ID` env var).
- Port discovery walks `$SHADOW_CLJS_NREPL_PORT` → standard
  shadow paths → `.nrepl-port`. Any of them satisfy the contract.
- Injected-runtime presence is marker-detected (the runtime
  exposes `day8.re-frame2-causa.runtime/session-id` from the
  browser side); a missing sentinel triggers automatic
  re-injection on the next op rather than a failed boot. The
  detection happens MCP-server-side
  (`day8.re-frame2-causa-mcp.*`); the marker lives in the
  browser. The two-ns split (per
  [`000-Vision.md` §Two namespaces, two sides](./000-Vision.md#two-namespaces-two-sides))
  is what lets the server reason about the runtime's absence
  without itself having a `runtime/session-id` to test.
- The runtime contract is the shape of the eighteen tool
  accessors `day8.re-frame2-causa.runtime` exposes, not a
  specific framework version.

A project that adopts a non-default build id, a custom nREPL
port, or a slightly different shadow layout still gets a working
Causa-MCP without code changes.

## Degraded boot, not failed boot

If the nREPL port can't be resolved at startup (no port file, no
env var, shadow-cljs not running yet), the server still boots
and answers `tools/list`. Every `tools/call` returns a
structured `:ok? false :reason :nrepl-port-not-found` error so
the agent host can surface the problem and the user can start
shadow-cljs without restarting the server.

If the runtime hasn't been preloaded, the first mutating /
inspecting tool call returns
`:ok? false :reason :runtime-not-preloaded` with a setup hint
pointing at the consumer's `shadow-cljs.edn` `:preloads` block.

The agent-host workflow is *don't make the user restart
anything*. The server's job is to keep working through the gaps
and surface a useful error when the runtime isn't ready.

Same posture as pair2-mcp.

## Read-mostly catalogue; mutate via the in-panel-equivalent gates

The catalogue is read-mostly — see
[`README.md` §Canonical counts](./README.md#canonical-counts) for
the band split. The mutating tools (`restore-epoch`,
`reset-frame-db`, `dispatch`) mirror the in-panel right-click
affordances the human user already has — Causa-MCP doesn't
introduce a *new* mutation surface, it gives the agent the same
surface the human has.

This is the symmetry the privacy + consent model rests on: the
user enabling the MCP server is the user *delegating their
in-panel rights* to the agent. The audit trail (origin tagging,
above) makes the delegation visible.

The escape hatch (`eval-cljs`) is the deliberate exception — it
lets agents reach for surfaces the catalogue doesn't yet cover.
Any side-effect the eval'd form triggers inherits the
`:origin :causa-mcp` tag (the runtime install hook handles the
dynamic-extent rebind). If a particular `eval-cljs` call becomes
recurrent, that's the signal to promote it to its own catalogue
entry.

## Closed-set tool catalogue, deliberate escape valve

The catalogue is **closed-set on purpose**: eighteen named
surfaces map to eighteen Causa-side affordances (seventeen panel
mirrors plus `list-subscriptions`, the diagnostic that enumerates
open streaming subscriptions — added by
[`DESIGN-RATIONALE.md` Lock #12](./DESIGN-RATIONALE.md) on
2026-05-14 to close the parity gap with pair2-mcp's
`subscription-info` impl). Adding tools is a deliberate act (a
`bd` bead, a discussion, a Lock entry in
[`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md)) — Lock #12 is the
example. Drift is prevented by the discipline that every new
tool maps to an existing Causa surface or a load-bearing
cross-server symmetry; the framework's instrumentation contract
is the rate-limit.

The `eval-cljs` escape valve absorbs the long tail (per the
section above). Refusing to ship an escape hatch would force
agents through Chrome MCP's `evaluate_script` (which loses the
`:origin` tag) — a worse outcome for the audit trail.

Captured as Lock #5 in [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md).

## Wire pipeline — privacy + token budget + streaming

The wire-pipeline rules (privacy default-drop, the six-mechanism
token-budget cascade, and the streaming-over-batch cross-cut) live
in [`004-Wire-Pipeline.md`](./004-Wire-Pipeline.md) — the canonical
home for what crosses the MCP stdio channel. They were split out of
this doc (rf2-erimb) once the section grew large enough to deserve
its own scaffold, mirroring the pair2-mcp-shape capability files.
Highlights this doc relies on as principles:

- **Privacy default-drop**: events with `:sensitive? true` are
  suppressed at the MCP boundary by default; opt back in per call
  with `:include-sensitive? true` (cross-server slot — same key on
  pair2-mcp + story-mcp + causa-mcp). Full normative wording lives
  at [`004-Wire-Pipeline.md` §"Privacy: default-drop `:sensitive?`
  events at the MCP boundary"](./004-Wire-Pipeline.md#privacy-default-drop-sensitive-events-at-the-mcp-boundary).
- **Token budget**: every tool response is bounded at ≤5,000 tokens
  by default; the six-mechanism cascade (cap, path slicing, cursor
  pagination, lazy summary, structural dedup, size elision) is the
  load-bearing budget posture. Catalogue entries (in
  `003-Tool-Catalogue.md` when impl lands) MUST declare which
  mechanisms apply, the typical-token hint, and the cap-reached
  behaviour. Full normative wording at
  [`004-Wire-Pipeline.md` §"Tight token budget per response"](./004-Wire-Pipeline.md#tight-token-budget-per-response).
- **Streaming**: the `subscribe` stream returns one event per JSON-RPC
  `notifications/progress`, with the cap applied per notification —
  per [`004-Wire-Pipeline.md` §"Streaming over batch (cross-cut)"](./004-Wire-Pipeline.md#streaming-over-batch-cross-cut).

The cross-server contract — default cap, override slot name
(`max-tokens`), overflow marker key (`:rf.mcp/overflow`),
agent-host retry contract, and chained-budget rules — lives at
[`tools/mcp-conformance/TOKEN-BUDGETS.md`](../../mcp-conformance/TOKEN-BUDGETS.md).

## Bash-shim back-compat is *not* a goal

Unlike pair2-mcp (which mirrors a pre-existing bash-shim
catalogue under `skills/re-frame-pair2/scripts/`), Causa-MCP
starts greenfield. There is no shim chain to deprecate, no
"side-by-side cadence" lock, no transition period. The MCP
surface *is* the agent-driven surface for Causa.

This is a clean break by accident, not by design: pair2
predated MCP and had to absorb the migration; Causa launches
with MCP-the-pattern already established by pair2-mcp. The
historical context is in
[`tools/pair2-mcp/spec/DESIGN-RATIONALE.md`](../../pair2-mcp/spec/DESIGN-RATIONALE.md)
Lock #6.

## Tool verbs follow the cross-MCP convention

Tool names in Causa-MCP's planned catalogue pick from the verb table
at [`tools/mcp-conformance/NAMING.md`](../../mcp-conformance/NAMING.md)
(rf2-mzf1r) — the canonical home for the cross-MCP verb vocabulary
shared with pair2-mcp and story-mcp. The shared verbs the triplet
pins are `get-` / `list-` / `read-` / `discover-` / `restore-` /
`reset-` / `register-` / `unregister-` / `run-` / `preview-` /
`record-as-` / `tail-` plus the bare universals `dispatch`,
`eval-cljs`, `subscribe`, `unsubscribe`.

Causa-MCP's spec'd catalogue (per
[`tools/causa/spec/010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
§"Tool catalogue") leans heavily on `get-<thing>` for
filter-addressed slice reads (`get-trace-buffer`, `get-epoch-history`,
`get-app-db`, `get-app-db-diff`, `get-machine-state`,
`get-machine-list`, `get-issues`, `get-handlers`, `get-source-coord`)
plus the mutating triple (`restore-epoch`, `reset-frame-db`,
`dispatch`), the streaming triple (`subscribe`, `unsubscribe`,
`list-subscriptions`), the escape hatch, and the meta tools
(`discover-app`, `tail-build`). All eighteen are conformant to the
canonical table.

This pin is **load-bearing for the impl pass**: when
`tools/causa-mcp/src/` lands and the catalogue prose migrates from
`tools/causa/spec/010-MCP-Server.md` to
`tools/causa-mcp/spec/003-Tool-Catalogue.md`, the verb pick is
already locked. New catalogue entries land against an existing verb,
or via a Lock entry in [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md)
plus an extension to the canonical table.

## Backed by Causa's and the framework's principles

When in doubt, defer to the principles upstream:

- **Causa's [Principles](../../causa/spec/Principles.md)** —
  "Read-only by default, mutate by confirmation" applies to the
  MCP surface too; the mutation tools mirror the in-panel
  right-click affordances, and the user's consent model is the
  same (enabling the MCP server is enabling the mutations).
- **Framework's [Principles](../../../spec/Principles.md)** —
  regularity over cleverness, named things over anonymous
  things, public query surfaces, deterministic execution, no
  core.async (the async return-shape is JavaScript Promises;
  the Node host's native async substrate).

Causa-MCP is a downstream artefact of Causa's design and the
framework's AI-first discipline. The principles above are what
*Causa-MCP adds* on top of both baselines; everything below is
inherited.
