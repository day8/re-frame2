# DESIGN-RATIONALE

> Implements the [Tool-Pair contract](../../../spec/Tool-Pair.md) —
> the locks below operate within that contract; where a decision
> intersects a Tool-Pair primitive, the spec there is the source of
> truth.

The direction-setting decisions that shape pair2-mcp. Each entry
captures:

- **The question** that was being decided.
- **Options considered.**
- **The pick** that was locked.
- **The why** — the reasoning trail.
- **Date locked** + the locker.

This doc is the load-bearing artefact of the per-tool spec
convention. It exists so that a future contributor (human or AI) can
read it and not re-ask the same questions. Where the spec text reads
"per lock #N in DESIGN-RATIONALE.md", this is where to come.

---

## Lock #1 — Implementation language

**Locked 2026-05-12 (Mike).** **ClojureScript compiled via
shadow-cljs to a Node `:node-script` target.**

### Question

What language and runtime hosts the MCP server's process?

### Options considered

- **ClojureScript + shadow-cljs → Node.** Compile a `:node-script`
  artefact; end users install Node only; the compiled output is
  plain JS. The team runs shadow-cljs daily.
- **JVM Clojure.** A fat-jar that the agent host launches via
  `java -jar`. Same language as the framework's reference; reuses
  the project's Clojure dep set directly.
- **`nbb` (Node + babashka-flavoured SCI).** Smaller dep surface; no
  build step. ClojureScript syntax, eval'd at runtime.
- **Pure babashka.** Same shape as the bash-shim chain's babashka
  runner. Project-local familiarity.
- **JavaScript / TypeScript directly.** Idiomatic for an npm MCP
  server; the SDK's reference impl is in TS.

### Pick

**ClojureScript + shadow-cljs → Node.**

### Why

- **End-user install cost.** Node is widely installed; the JVM is
  not (Mac/Win/Linux dev machines may or may not have a JDK). End
  users running `npm install -g @day8/re-frame-pair2-mcp` are paying
  zero install cost — Node is already there for shadow-cljs.
- **Cold start.** A JVM-Clojure server pays a 1–2s cold-start cost
  every time the agent host launches the subprocess. Node + a
  pre-compiled `.js` artefact starts in ~50ms. Latency matters
  because the agent host can re-launch the subprocess across IDE
  restarts.
- **Tooling maturity.** `nbb` is exciting but the agent host /
  shadow-cljs / npm MCP SDK interop space is more battle-tested
  through plain Node. Hitting a `nbb`-specific edge case during a
  pair-coding session would be costly.
- **Same-language win.** The team writes ClojureScript daily;
  shadow-cljs is in the build path of every re-frame2 dev's repo.
  One more `:node-script` build target is trivial. JavaScript /
  TypeScript would mean a second language and toolchain.
- **npm MCP SDK is reachable.** The `@modelcontextprotocol/sdk`
  npm package works fine via shadow-cljs's CommonJS interop;
  `StdioServerTransport` provides the framing without us rolling
  our own.

### Date locked

2026-05-12 (Mike). Pilot established viability earlier the same
week; lock was on the pilot's results once bencode round-trip and
stdio JSON-RPC were both proven.

### Trail-of-thought citations

- rf2-5b8e PR #423 (the toolchain pilot — `pilot/` directory is
  preserved as the smoke harness).
- Mike's 2026-05-12 locking comment on the PR.

---

## Lock #2 — Agent-host transport

**Locked 2026-05-12 (Mike).** **MCP over stdio.** No custom
WebSocket protocol.

### Question

How does pair2-mcp speak to agent hosts? MCP, a custom WebSocket,
an HTTP API, or some other shape?

### Options considered

- **MCP over stdio.** The agent host launches the server as a
  subprocess; newline-delimited JSON-RPC 2.0 over stdin/stdout per
  the [MCP stdio transport spec](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports).
- **Custom WebSocket protocol.** A pair2-specific wire format; the
  agent host opens a WebSocket; pair2-mcp listens on a known port.
- **HTTP REST endpoints.** Each op is an HTTP endpoint; agent
  hosts POST JSON.
- **MCP over WebSocket.** MCP semantics, WebSocket transport (per
  the MCP spec's alternate transport).

### Pick

**MCP over stdio.**

### Why

- **Host-agnostic.** MCP is the contract every modern agent host
  (Claude Code, Cursor, Copilot) already speaks. Implementing MCP
  once works for all of them. A custom WebSocket would require each
  host to learn pair2's wire shape — N integrations instead of one.
- **Stdio is the lowest-friction transport.** No port allocation,
  no firewall negotiation, no reconnect logic. The agent host owns
  process lifecycle; when the host exits, the server exits.
- **The HTTP REST option** doesn't earn its keep — it has the same
  surface as JSON-RPC but without the request/response correlation
  semantics, and it requires a port.
- **WebSocket-as-transport (MCP-flavoured)** adds operational
  complexity (port, reconnect, security) for benefits the local
  pair-coding workflow doesn't need.

### Date locked

2026-05-12 (Mike).

### Trail-of-thought citations

- rf2-5b8e PR #423 description.
- The pilot proved stdio + JSON-RPC + bencode round-trip end-to-end
  before this lock; the WebSocket alternative was never built.

---

## Lock #3 — Connection model

**Locked 2026-05-12 (Mike).** **Single persistent nREPL socket** for
the lifetime of the session. Reconnect-per-op is rejected.

### Question

Does pair2-mcp open a fresh nREPL connection per op (matching the
bash-shim chain's shape) or hold one persistent socket for the
session?

### Options considered

- **Reconnect-per-op.** Match the bash-shim chain: open a socket,
  run the op, close it. No connection state to manage.
- **Single persistent socket.** Open on first need; hold for the
  session; multiplex ops by UUID id.
- **Connection pool.** A small pool of pre-warmed sockets, rotating
  by op. Useful only if there's concurrency.

### Pick

**Single persistent socket.** Multiplexed by op-id (`{id →
resolve-fn}` pending map).

### Why

- **Latency.** The bash-shim chain pays ~200–500ms per op for fresh
  TCP connect + nREPL handshake. A persistent socket pays that
  cost *once per session* — and a typical pair2 session fires
  dozens to hundreds of ops. Break-even is one op.
- **Interactive feel.** Per-op latency drops from ~700ms to ~5–50ms.
  This is the difference between *batch* (the shim shape) and
  *interactive* (the MCP shape). The MCP server feels like
  pair-coding; the shim chain feels like running a script.
- **Connection state is cheap.** One socket, one pending map, one
  reader loop. The bencode framing has a known footgun (see
  `002-nREPL-Transport.md`) which is a one-time implementation
  cost.
- **Pool not needed.** The MCP server invokes tools sequentially;
  there's no concurrency to amortise. A pool would add complexity
  without benefit.

### Date locked

2026-05-12 (Mike).

### Trail-of-thought citations

- rf2-5b8e PR #423 description.
- The bash-shim chain's per-op latency budget was the load-bearing
  motivation for building pair2-mcp at all.

---

## Lock #4 — Tool catalogue cardinality

**Locked 2026-05-12 (Mike).** **Seven ops at v0.1.0**, mirroring the
bash-shim catalogue exactly. The catalogue has since grown to **fourteen
ops** via successive additive drops; see *Subsequent evolution* below.

### Question

How many MCP tools does pair2-mcp expose, and which ones?

### Options considered

- **Mirror the bash-shim ops** (`discover-app`, `eval-cljs`,
  `dispatch`, `trace-window`, `watch-epochs`, `tail-build`).
  (Pre-rf2-7dvg also included `inject-runtime`; that op was cut along
  with the per-session inject step.)
- **Lift some shim helpers into ops** (e.g. a `re-inject` shortcut,
  a `current-frame` accessor, an `assert-runtime` helper). Wider
  surface; smaller per-op work.
- **Decompose `dispatch`** into `dispatch-queued`, `dispatch-sync`,
  `dispatch-trace` — three ops instead of one with mode flags.
- **Add new ops** (e.g. `subs/cache`, `registrar/list`,
  `registrar/describe`, `app-db/snapshot`, `app-db/get`,
  `app-db/reset`) covering surfaces the shims don't.

### Pick

**Seven ops — mirror the bash-shim catalogue.** Identical
vocabulary, identical contract semantics.

### Why

- **Identical contract eases migration.** Agents and skills that
  already understand the seven shim ops carry their understanding
  directly to the MCP server. Skill `SKILL.md` files don't have to
  be rewritten; the only difference is transport.
- **Mode flags over op decomposition.** `dispatch` with `sync` /
  `trace` flags is the existing shim shape and survives in MCP.
  Three sibling ops would inflate the catalogue without gaining
  expressiveness.
- **Additional ops are deferrable.** Surfaces like `subs/cache`,
  `app-db/snapshot`, `registrar/list` are valuable but every one
  needs a runtime accessor, an op schema, and a test. Shipping the
  shim-mirror first proves the transport; expansion lands later
  when load-bearing demand exists. A `dispatch` + `eval-cljs`
  combo already covers many of these cases for v1.
- **Bounded surface eases reasoning.** Seven ops is small enough
  that the agent host's `tools/list` response is comprehensible at
  a glance; the user can read it and know what the server does.

### Date locked

2026-05-12 (Mike).

### Trail-of-thought citations

- rf2-5b8e PR #423 description.
- The bash-shim catalogue had stabilised at seven ops over multiple
  iterations before the MCP port; the cardinality was already
  validated by daily use.

### Subsequent evolution

The original seven-op cardinality has been amended twice. The lock's
reasoning (mirror the bash-shim catalogue; mode flags over op
decomposition; bounded surface) still holds — both amendments are
*additive* per-need extensions, not a rejection of the lock.

- **rf2-7dvg** cut `inject-runtime`: the runtime now ships into the
  consumer app via shadow-cljs `:devtools :preloads`, so the inject
  op is gone. The bash-shim catalogue dropped to six; the MCP
  catalogue followed.
- **rf2-x70e** added `snapshot`: a coarse-grained per-frame state read
  composed server-side over the existing per-slice runtime readers.
  Earns its keep as a mega-op for investigate-X workflows that would
  otherwise chain 5–10 individual `eval-cljs` reads. No bash-shim
  equivalent; the shim chain stays at six.
- **rf2-hq49** added `subscribe` / `unsubscribe`: streaming
  subscription on the trace + epoch bus, layered over MCP's
  `notifications/progress` mechanism. Push-mode replacement for the
  polling-shaped `watch-epochs`. No bash-shim equivalent.

Net: pair2-mcp ships **fourteen ops** (`discover-app`, `eval-cljs`,
`dispatch`, `trace-window`, `watch-epochs`, `tail-build`, `snapshot`,
`get-path`, `subscribe`, `unsubscribe`, `subscription-info`,
`handler-meta`, `registry-list`, `get-pair2-instructions`). The bash
shims ship six. The shim catalogue is a strict subset of the MCP
catalogue, with identical names and arg shapes for every overlapping
op.

Post-Lock additions accumulated as follows:

- **rf2-zjz9q** added `subscription-info` — the "what streams are
  open?" diagnostic peer for the streaming pair, picked under the
  bare-noun read shape (the cross-MCP `list-subscriptions` rename is
  the future-conformant home per
  [`tools/mcp-conformance/NAMING.md`](../../mcp-conformance/NAMING.md)
  Story-mcp Lock #12 / rf2-3we2k).
- **rf2-fnpqg** added `get-pair2-instructions` — the
  agent-onboarding text blob read once at session start. Mirrors
  story-mcp's `get-story-instructions` under the cross-MCP `get-`
  verb.
- **rf2-cibp8 / rf2-pctf8** added `handler-meta` and `registry-list`
  — the registrar-introspection pair. `handler-meta {kind id}` returns
  the registration-metadata map (`:source-coord`, `:doc`, `:tags`,
  any reg-`*`-emitted slots) so agents can answer "where is `:user/login`
  defined?" without a wide-authority `eval-cljs` round-trip;
  `registry-list {kind}` is the discovery peer that enumerates every
  registered id under a kind. Both route through the existing
  `re-frame-pair2.runtime` registrar primitives (and `(rf/machines)`
  for the `:machine` kind per Spec 005 §Querying machines); both are
  `:cacheable? true` since the registrar is stable across a session.

---

## Lock #5 — bencode pinning

**Locked 2026-05-12 (Mike, during pilot).** **`bencode@~2.0.3`**
pinned via npm. bencode@4+ is rejected.

### Question

Which bencode npm package handles nREPL framing?

### Options considered

- **`bencode@~2.0.3`.** CommonJS export shape; works with
  shadow-cljs's CommonJS interop out of the box.
- **`bencode@4+`.** ESM-only; newer maintainer release line.
- **Roll our own bencode codec.** ~150 LoC of straightforward
  parsing; zero dependency.

### Pick

**`bencode@~2.0.3`**, pinned with the tilde to admit patch updates
only.

### Why

- **shadow-cljs CommonJS shim.** `bencode@4+` is ESM-only and
  immediately fails with `Error: No "exports" main defined` when
  loaded via shadow-cljs's `:node-script` target. The CommonJS
  shape of `bencode@2.x` works without ceremony.
- **bencode footgun discovered during pilot.** `bencode@2`'s
  `bencode.decode.bytes` attribute is unreliable — sometimes set to
  the *full* buffer length even when only the first frame was
  decoded. The correct accessor is `bencode.decode.position`. This
  is captured in `002-nREPL-Transport.md` § Bencode framing; the
  pin to a known-good version prevents drift.
- **Rolling our own** is tempting but the codec is just enough work
  that getting it wrong (especially around dict ordering and binary
  payloads) would introduce a class of bugs that the npm package
  has already solved. Pinning is the lower-effort risk-mitigation.

### Date locked

Pilot phase (early rf2-5b8e). The pin landed when the bencode
position-vs-bytes footgun was hit; locked at PR #423 merge.

### Trail-of-thought citations

- `tools/pair2-mcp/spec/002-nREPL-Transport.md` § Bencode framing.
- rf2-5b8e PR #423 description (the position-vs-bytes diagnosis).
- The pilot's `nrepl_test.cljs` unit tests around partial-frame
  decoding.

---

## Lock #6 — Bash-shim deprecation cadence

**Locked 2026-05-12 (Mike).** **Both surfaces ship side-by-side.**
No removal scheduled; shims remain working with deprecation notice.

### Question

When do the bash shims under `skills/re-frame-pair2/scripts/` get
removed?

### Options considered

- **Remove the shims at MCP-server ship.** One transport going
  forward; clean break.
- **Mark deprecated; remove next major.** Explicit removal
  schedule.
- **Ship side-by-side indefinitely.** Both work; migration is
  opt-in per session.

### Pick

**Side-by-side, no removal scheduled.** Shim headers carry a
deprecation notice pointing to the MCP server; nothing else
changes.

### Why

- **Migration risk is asymmetric.** Removing the shims could break
  existing runbooks, skill docs, and personal workflows that
  reference them. The benefit of removal (less code) is small
  compared to the cost (someone's session breaking on a Tuesday).
- **Overlapping op vocabulary.** Six of the fourteen MCP tools
  (`discover-app`, `eval-cljs`, `dispatch`, `trace-window`,
  `watch-epochs`, `tail-build`) mirror the six bash shims exactly,
  with identical names and arg shapes. The remaining eight
  (`snapshot`, `get-path`, `subscribe`, `unsubscribe`,
  `subscription-info`, `handler-meta`, `registry-list`,
  `get-pair2-instructions`) are MCP-only additions per Lock #4's
  *Subsequent evolution* note — they have no shim equivalent.
  Agents can mix calls in the same workflow during transition —
  no all-or-nothing switch is required.
- **MCP server isn't proven across the team yet.** Side-by-side
  shipping gives the MCP server time to accumulate trust before
  becoming the only path.
- **Future removal is cheap.** If/when the MCP server is the
  obvious answer for every consumer, the shims come out in a
  later drop. The decision is reversible.

### Date locked

2026-05-12 (Mike).

### Trail-of-thought citations

- rf2-5b8e PR #423 description.
- `tools/pair2-mcp/README.md` § Back-compat with the bash shims.

---

## Lock #7 — Wire-boundary token cap (egress-centralised + pluggable strategy + truncate-with-marker)

**Locked 2026-05-13 (Mike).** **Enforce the 5K-token response cap
at the `invoke` (wire) boundary, as a single wrapper around every
per-tool function, dispatched on a `:strategy` keyword. Over-budget
payloads are replaced with a `{:rf.mcp/overflow {...}}` marker —
not silently truncated, not refused with an error.**

### Question

pair2-mcp tool responses can balloon — `snapshot` over a rich
`app-db`, `trace-window` over a busy session, `eval-cljs` returning
a large value. Where in the stack should the token cap be enforced,
how should it be configured per-tool without scattering cap-logic,
and what shape should an over-budget response take?

### Options considered

- **(a) Per-tool enforcement.** Each tool function knows its own
  cap, runs its own check, returns its own overflow shape.
  Maximum locality; each tool owns its response budget end-to-end.
- **(b) Egress-centralised + pluggable strategy (CHOSEN).** A
  single wrapper at the `invoke` boundary applies the cap to the
  serialised MCP `{:content [...]}` shape after the per-tool
  function resolves. The wrapper dispatches on a `:strategy`
  keyword so new trim mechanisms (path slicing, lazy summary,
  diff encoding, structural dedup, pagination) plug in without a
  rebuild. Today only `:truncate-with-marker` lives in the
  wrapper; the other mechanisms compose either as input-shape
  concerns at the tool surface or as additional strategy values.
- **(c) Caller-side enforcement.** The agent declares and
  enforces its own budget; pair2-mcp returns whatever the tool
  produced. Smallest server surface.
- **Overflow shape sub-decision: silent truncation.** Trim the
  payload to fit; ship the head bytes; no marker.
- **Overflow shape sub-decision: refuse-with-error.** Return a
  JSON-RPC error code; the call has no payload, the agent retries
  with narrower args.
- **Overflow shape sub-decision: truncate + structured marker
  (CHOSEN).** Replace the over-budget payload with
  `{:rf.mcp/overflow {:limit :reached :token-count … :cap-tokens
  … :tool … :hint …}}`. The marker is the only over-budget shape.

### Pick

**(b) egress-centralised + pluggable strategy, with
truncate-with-marker as the only shipped strategy.** Token rule
is `(quot (count s) 4)` applied to the serialised response,
summed across every `:text` slot of the assembled `:content`
vector. Default cap 5000 tokens. Every tool accepts a `max-tokens`
arg (integer; `0` disables the cap for explicit escape).

### Why

- **Centralised composition.** Per-tool enforcement (option a)
  scatters cap-logic across the tool catalogue. Every new tool
  reinvents the budget check; every new trim mechanism (path
  slicing, lazy summary, diff encoding, dedup) requires editing
  every tool to opt in. The egress wrapper is the single
  composition point: a strategy keyword swaps the trim algorithm
  without touching any per-tool function.
- **Tool functions stay shape-pure.** Per-tool functions emit
  their natural data shape; serialisation, sizing, and trim are
  the wrapper's concern. This preserves the testability of each
  tool's response shape without the noise of cap-arithmetic.
- **Caller-side rejected.** Option (c) only works if the budget
  is enforced before the agent host has already parsed the
  response — by which point the agent context is already
  corrupted. The cap exists precisely because the agent can't
  unsee a 50K-token blob; making the agent the enforcer defeats
  the purpose.
- **Silent truncation rejected.** A truncated EDN/JSON payload is
  a malformed payload — the agent host's parser either fails or
  silently consumes a partial structure. Both outcomes are worse
  than the marker: parse failure is a hard stop on the call;
  silent partial-consume corrupts the agent's model of the world
  with no signal that it happened.
- **Refuse-with-error rejected.** An error response carries no
  payload the agent can use to decide what to do next. The
  marker carries the original token count, the cap that tripped,
  the tool name, and a tool-specific hint — enough for the agent
  to either narrow args automatically or surface a meaningful
  message. The marker is a *structured retry signal*, not a
  failure.
- **Token rule is deliberately cheap.** `(quot (count s) 4)` is
  the published Anthropic English/EDN rule-of-thumb. It's not
  exact; the goal is a bounded wire payload, not a precise
  meter. The cheap rule lets the wrapper run on every response
  without measurable cost.
- **Cumulative across `:content` slots.** Multi-part responses
  share one cumulative budget, not per-part. This is the only
  way the cap composes with multi-content tools (e.g. a snapshot
  that ships a text part plus a resource link).
- **The strategy seam is the forward-compatibility surface.**
  The five in-flight wire-protocol beads — rf2-tygdv (path
  slicing on `snapshot`/`get-path`), rf2-1wdzp (diff-encode
  `:db-after`), rf2-obpa9 (structural dedup), rf2-u2029 (lazy
  `:summary` mode), rf2-kbqq3 (opaque-cursor pagination) — all
  compose without rebuilding the wrapper. Path slicing and
  lazy-summary land as input-shape concerns at the tool
  surface; diff-encoding lives at the tool surface; dedup and
  alternate trim algorithms slot in as additional `:strategy`
  values. The cap stays the backstop; the mechanisms keep the
  common case well inside it.

### Date locked

2026-05-13 (Mike). Locked at rf2-rvyzy PR #641 merge. The cap
itself landed in [`Principles.md`](Principles.md#tight-token-budget-per-response)
with MUST wording; this lock captures the comparative reasoning
that the principle alone doesn't carry. Causa-MCP's parallel
[Lock #9 — Wire-protocol budget posture](../../causa-mcp/spec/DESIGN-RATIONALE.md#lock-9--wire-protocol-budget-posture-the-mechanism-cascade)
(rf2-lwgg8) bakes the same five mechanisms into the Causa-MCP
spec before its impl exists, deliberately aligned with the
posture locked here.

### Trail-of-thought citations

- rf2-rvyzy PR #641 — the `tools.cljs` invoke-boundary wrapper.
- [`tools/pair2-mcp/spec/Principles.md`](Principles.md#tight-token-budget-per-response)
  §"Tight token budget per response" — the normative MUST
  wording for the cap, the token rule, the override knob, and
  the overflow shape.
- [`tools/pair2-mcp/spec/003-Tool-Catalogue.md`](003-Tool-Catalogue.md)
  §The universal `max-tokens` arg — the per-tool surface for
  the override.
- [`tools/causa-mcp/spec/DESIGN-RATIONALE.md`](../../causa-mcp/spec/DESIGN-RATIONALE.md#lock-9--wire-protocol-budget-posture-the-mechanism-cascade)
  Lock #9 — the aligned posture on the Causa-MCP side; the two
  servers share the `:rf.mcp/overflow` / `:rf.mcp/summary` /
  `:rf.mcp/dedup-table` reserved keys.
- `ai/findings/wire-protocol-bigapp-20260513-1541.md` — the
  source investigation (local-only) that motivated baking the
  posture into spec instead of leaving it as in-flight impl
  drift.

---

## Summary table

| # | Question | Pick | Date |
|---|---|---|---|
| 1 | Implementation language | **ClojureScript + shadow-cljs → Node** | 2026-05-12 |
| 2 | Agent-host transport | **MCP over stdio** | 2026-05-12 |
| 3 | Connection model | **Single persistent nREPL socket** | 2026-05-12 |
| 4 | Tool catalogue cardinality | **Seven ops at v0.1.0; grown to fourteen** (mirror the shim catalogue + `snapshot` + `get-path` + `subscribe`/`unsubscribe`/`subscription-info` + `handler-meta`/`registry-list` + `get-pair2-instructions`) | 2026-05-12 |
| 5 | bencode pinning | **`bencode@~2.0.3`** (CommonJS; position-not-bytes) | 2026-05-12 |
| 6 | Bash-shim deprecation | **Side-by-side, no removal scheduled** | 2026-05-12 |
| 7 | Wire-boundary token cap | **Egress-centralised wrapper + pluggable `:strategy` + truncate-with-`{:rf.mcp/overflow …}`-marker** | 2026-05-13 |

These seven locks together define pair2-mcp's shipped surface.
Anything outside these decisions is up for design discussion;
anything inside is direction-set and shipped. Lock #4's
cardinality has since grown additively from seven to fourteen
(see its *Subsequent evolution* note); the load-bearing direction
— mirror the shim catalogue, prefer mode flags over op decomposition,
keep the surface bounded — still holds. Lock #7 sets the wire-budget
posture that subsequent trim-mechanism beads (path slicing,
diff encoding, lazy summary, structural dedup, pagination)
compose against without rebuilding the wrapper.
