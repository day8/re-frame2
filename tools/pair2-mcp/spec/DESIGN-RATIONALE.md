# DESIGN-RATIONALE

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
bash-shim catalogue exactly. The catalogue has since grown to **nine
ops** via two additive drops; see *Subsequent evolution* below.

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

Net: pair2-mcp ships **nine ops** (`discover-app`, `eval-cljs`,
`dispatch`, `trace-window`, `watch-epochs`, `tail-build`, `snapshot`,
`subscribe`, `unsubscribe`). The bash shims ship six. The shim
catalogue is a strict subset of the MCP catalogue, with identical
names and arg shapes for every overlapping op.

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
- **Overlapping op vocabulary.** Six of the nine MCP tools
  (`discover-app`, `eval-cljs`, `dispatch`, `trace-window`,
  `watch-epochs`, `tail-build`) mirror the six bash shims exactly,
  with identical names and arg shapes. The remaining three
  (`snapshot`, `subscribe`, `unsubscribe`) are MCP-only additions
  per Lock #4's *Subsequent evolution* note — they have no shim
  equivalent. Agents can mix calls in the same workflow during
  transition — no all-or-nothing switch is required.
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

## Summary table

| # | Question | Pick | Date |
|---|---|---|---|
| 1 | Implementation language | **ClojureScript + shadow-cljs → Node** | 2026-05-12 |
| 2 | Agent-host transport | **MCP over stdio** | 2026-05-12 |
| 3 | Connection model | **Single persistent nREPL socket** | 2026-05-12 |
| 4 | Tool catalogue cardinality | **Seven ops at v0.1.0; grown to nine** (mirror the shim catalogue + `snapshot` + `subscribe`/`unsubscribe`) | 2026-05-12 |
| 5 | bencode pinning | **`bencode@~2.0.3`** (CommonJS; position-not-bytes) | 2026-05-12 |
| 6 | Bash-shim deprecation | **Side-by-side, no removal scheduled** | 2026-05-12 |

These six locks together define pair2-mcp's v0.1.0 surface. Anything
outside these decisions is up for design discussion; anything inside
is direction-set and shipped. Lock #4's cardinality has since grown
additively (see its *Subsequent evolution* note); the load-bearing
direction — mirror the shim catalogue, prefer mode flags over op
decomposition, keep the surface bounded — still holds.
