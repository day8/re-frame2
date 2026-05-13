# DESIGN-RATIONALE

The direction-setting decisions that shape Causa-MCP. Each entry
captures:

- **The question** that was being decided.
- **Options considered.**
- **The pick** that was locked.
- **The why** — the reasoning trail.
- **Date locked** + the locker.

This doc is the load-bearing artefact of the per-tool spec
convention. It exists so that a future contributor (human or
AI) can read it and not re-ask the same questions. Where the
spec text reads "per Lock #N in DESIGN-RATIONALE.md", this is
where to come.

The locks below were accumulated inside
[`tools/causa/spec/010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
and Causa's own
[DESIGN-RATIONALE](../../causa/spec/DESIGN-RATIONALE.md)
(specifically [Lock #6 — MCP timing](../../causa/spec/DESIGN-RATIONALE.md#lock-6--mcp-timing))
before this folder existed. Lifting them here gives them a
proper home and prevents them being silently rewritten when
implementation work begins.

The locks are downstream of (and reuse most of)
[`tools/pair2-mcp/spec/DESIGN-RATIONALE.md`](../../pair2-mcp/spec/DESIGN-RATIONALE.md).
Where a Causa-MCP lock is identical to a pair2-mcp lock, the
entry below cites and points instead of duplicating.

---

## Lock #1 — Implementation language

**Locked 2026-05-12 (Mike).** **ClojureScript compiled via
shadow-cljs to a Node `:node-script` target.** Same as
pair2-mcp.

### Question

What language and runtime hosts the Causa-MCP server's process?

### Options considered

Same set as pair2-mcp Lock #1:

- ClojureScript + shadow-cljs → Node.
- JVM Clojure.
- `nbb` (Node + babashka-flavoured SCI).
- Pure babashka.
- JavaScript / TypeScript directly.

### Pick

**ClojureScript + shadow-cljs → Node.**

### Why

Inherited from pair2-mcp's Lock #1 (see
[`tools/pair2-mcp/spec/DESIGN-RATIONALE.md`](../../pair2-mcp/spec/DESIGN-RATIONALE.md)
#lock-1--implementation-language). The reasoning is identical
— Node's install cost is zero for shadow-cljs users; cold-start
is ~50ms vs JVM's 1–2s; the team writes ClojureScript daily; the
npm MCP SDK works fine through shadow-cljs's CommonJS interop.

Causa-MCP additionally benefits from sharing the toolchain with
pair2-mcp: forking the project layout (`deps.edn`,
`shadow-cljs.edn`, `pilot/` smoke harness, runtime ns
conventions) means the implementation cost is dominated by
writing the seventeen-tool dispatcher rather than rebuilding the
transport plumbing.

### Date locked

2026-05-12 (Mike). Locked at the same time as Causa's
[Lock #6 — MCP timing](../../causa/spec/DESIGN-RATIONALE.md#lock-6--mcp-timing)
reversal — the v1.0 MCP-ship decision implicitly committed to
the pair2-mcp template's language stack.

### Trail-of-thought citations

- pair2-mcp PR #423 (the toolchain pilot).
- Causa
  [`010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
  §Transport ("Same as `tools/pair2-mcp/spec/001-Wire-Protocol.md`").

---

## Lock #2 — Agent-host transport

**Locked 2026-05-12 (Mike).** **MCP over stdio.** No custom
WebSocket protocol. Same as pair2-mcp Lock #2.

### Question

How does Causa-MCP speak to agent hosts? MCP, a custom
WebSocket, HTTP, or some other shape?

### Pick

**MCP over stdio** (newline-delimited JSON-RPC 2.0 per the
[MCP stdio transport spec](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)).

### Why

Inherited from pair2-mcp's Lock #2. MCP is the contract every
modern agent host (Claude Code, Cursor, Copilot) already speaks;
implementing MCP once works for all of them. Stdio is the
lowest-friction transport (no port allocation, no firewall
negotiation, no reconnect logic).

The Causa-specific reinforcement: the
[Hybrid launch-modes lock](../../causa/spec/DESIGN-RATIONALE.md#lock-9--launch-modes)
explicitly rejected a custom WebSocket remote-attach protocol
for Causa. MCP-over-stdio absorbed the remote-attach concern.
Inverting that here (a custom Causa-MCP transport) would
re-introduce the protocol-versioning + security + reconnect-logic
surface Lock #9 ruled out.

### Date locked

2026-05-12 (Mike).

### Trail-of-thought citations

- pair2-mcp PR #423 description.
- Causa
  [`DESIGN-RATIONALE.md` Lock #9 — Launch modes](../../causa/spec/DESIGN-RATIONALE.md#lock-9--launch-modes).
- Causa
  [`010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
  §Transport.

---

## Lock #3 — Connection model

**Locked 2026-05-12 (Mike).** **Single persistent nREPL socket**
for the lifetime of the session. Same as pair2-mcp Lock #3.

### Question

Does Causa-MCP open a fresh nREPL connection per op or hold one
persistent socket for the session?

### Pick

**Single persistent socket.** Multiplexed by op-id
(`{id → resolve-fn}` pending map).

### Why

Inherited from pair2-mcp's Lock #3 (see
[`tools/pair2-mcp/spec/DESIGN-RATIONALE.md`](../../pair2-mcp/spec/DESIGN-RATIONALE.md)
#lock-3--connection-model). The latency calculus is identical;
Causa-MCP sessions are even longer-running than pair2-mcp's (an
agent debugging a bug walks the trace stream over many minutes,
firing many tools), so the persistent-socket win is larger.

Streaming makes the persistent socket non-optional: the
`subscribe` / `unsubscribe` pair (per
[`tools/causa/spec/010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
§Streaming) holds a `tools/call` open for the lifetime of the
subscription and emits `notifications/progress` over it.
Reconnect-per-op is incompatible with the streaming surface.

### Date locked

2026-05-12 (Mike).

### Trail-of-thought citations

- pair2-mcp PR #423 description.
- Causa
  [`010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
  §nREPL bridge ("Same as `tools/pair2-mcp/spec/002-nREPL-Transport.md`").

---

## Lock #4 — Origin tagging is the convention

**Locked 2026-05-12 (Mike).** **Every Causa-MCP-driven
side-effect is tagged `:origin :causa-mcp` on the trace bus.**
The tag is set at the entry point of every mutating tool and at
the boundary of `eval-cljs`; opt-out is a per-call argument that
costs the agent a deliberate ceremony.

### Question

Should Causa-MCP tag its mutations on the trace bus, and if so
with what discipline (default-on / default-off / mandatory)?

### Options considered

- **Default-off.** Tools have an `:origin :causa-mcp` argument
  the agent passes when it wants the tag; absent argument means
  untagged.
- **Default-on, opt-out per call.** The server sets the tag
  automatically; the agent can override per call with
  `{:origin :app}` (e.g. when simulating a user click for
  reproducibility).
- **Mandatory.** No opt-out at all.
- **Drop the tag entirely.** Match Chrome MCP's
  `evaluate_script` — agent mutations are indistinguishable from
  user mutations.

### Pick

**Default-on, opt-out per call.** The tag is set at the entry
point of every mutating tool (`dispatch`, `reset-frame-db`,
`restore-epoch`) and at the boundary of `eval-cljs` (any
dispatch the eval'd form triggers inherits the tag via the
runtime install hook's
`re-frame2-causa-mcp.runtime/current-origin` dynamic var). An
agent that genuinely wants to simulate a user click passes
`:origin :app` explicitly — the override is one keyword.

### Why

- **Audit trail is the load-bearing differentiator.** This is
  the single property that distinguishes Causa-MCP from
  generalist agent surfaces (Chrome MCP's `evaluate_script`).
  A post-incident audit asking "what did the agent do?" must
  return a complete, filterable answer; default-off would mean
  agents who forget to tag are silently invisible.
- **Opt-out, not opt-in.** Default-on means the agent has to
  *try* to be invisible. The ceremony cost (one keyword) makes
  the opt-out a deliberate act, surfaceable in the prompt
  history: "the agent ran `dispatch` with `:origin :app` here —
  why?"
- **The override is necessary, not optional.** A skill that's
  reproducing a user-shaped bug from a session log needs to fire
  the dispatch tagged `:origin :app` so the replay matches the
  original trace. Mandatory tagging would force the agent
  through `eval-cljs` (which itself tags as `:causa-mcp` — there
  is no escape hatch). Per-call override is the correct knob.
- **Dropping the tag would be malpractice.** Causa's whole point
  is the structured trace bus; the `:origin` axis is part of
  Spec 009's vocabulary. Causa-MCP is the place that vocabulary
  earns its keep against agent surfaces.

### Date locked

2026-05-12 (Mike). Implicit in Causa's
[Lock #6 — MCP timing](../../causa/spec/DESIGN-RATIONALE.md#lock-6--mcp-timing)
on the same day, made explicit in the
[`010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
§Origin tagging section. This lock lifts the orphan to its
proper home.

### Trail-of-thought citations

- Causa
  [`010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
  §Every MCP-driven mutation leaves a visible footprint.
- Causa
  [`010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
  §Origin tagging (the full vocabulary).
- [Spec 009 §Filter vocabulary](../../../spec/009-Instrumentation.md)
  — the `:origin` axis the tag plugs into.
- pair2-mcp's `:origin :pair` tag (the precedent, less
  load-bearing because pair2-mcp is editor-side rather than
  debugger-side).

---

## Lock #5 — Tool catalogue cardinality and shape

**Locked 2026-05-12 (Mike).** **Seventeen tools across five
bands** (inspection / mutation / streaming / escape hatch / meta).
The catalogue is closed-set; additions require a Lock entry
here. `eval-cljs` is the deliberate escape valve.

### Question

How many MCP tools does Causa-MCP expose, and which ones?

### Options considered

- **Mirror pair2-mcp** (9 tools — `discover-app`, `eval-cljs`,
  `dispatch`, `trace-window`, `watch-epochs`, `tail-build`,
  `snapshot`, `subscribe`, `unsubscribe`). Smallest surface;
  agent has to compose for Causa-shaped questions.
- **Hand-rolled minimal set** (~6 tools — `get-trace`,
  `get-app-db`, `dispatch`, `restore-epoch`, `eval-cljs`,
  `discover-app`). Less surface, less to maintain.
- **Seventeen tools mapped to Causa panels** (per
  [`tools/causa/spec/010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
  §Tool catalogue). One tool per Causa-visible affordance plus
  the streaming pair, the escape hatch, and the meta tools.
- **Open-ended catalogue.** Add tools as new surfaces ship;
  expand freely.

### Pick

**Seventeen tools across five bands.** The catalogue is
closed-set on purpose — additions are deliberate (a Lock entry
here, a discussion). `eval-cljs` absorbs the long tail.

### Why

- **One tool per Causa panel is the symmetry.** Causa's pitch
  is "the cascade you can see"; Causa-MCP's pitch is "the
  cascade your agent can read." Seventeen tools map cleanly to
  seventeen Causa surfaces (9 read + 3 mutate + 2 stream + 1
  escape hatch + 2 meta). The agent reading
  `tools/list` sees a one-to-one map.
- **Pair2-mcp's catalogue is editor-side; Causa's is
  debugger-side.** Mirroring pair2 wholesale would leave
  agents writing Chrome MCP `evaluate_script` blocks for
  Causa-shaped questions (`get-app-db-diff`,
  `get-machine-list`, `get-issues`) — losing the `:origin` tag
  and the structured trace's filter vocabulary.
- **Closed-set keeps the surface comprehensible.** Seventeen
  named tools is at the upper edge of what an agent can hold in
  context comfortably. Beyond that, agent confusion (calling
  the wrong tool) starts to dominate; closed-set with an
  escape valve is the right balance.
- **The escape valve is the steam vent.** `eval-cljs` ships
  first-class precisely so the catalogue can stay closed-set.
  If a particular `eval-cljs` pattern recurs, that's the
  signal to promote it to its own catalogue entry — but the
  promotion is a deliberate Lock entry, not a silent merge.

### Date locked

2026-05-12 (Mike). The seventeen-tool catalogue was assembled
during Causa's
[`010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
authoring on 2026-05-12; the lock entry below pins the
cardinality and the closed-set policy.

### Trail-of-thought citations

- Causa
  [`010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
  §Tool catalogue (the full enumeration with signatures and
  return shapes).
- Causa
  [`010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
  §Per-tool comparison with Chrome DevTools MCP (the table
  motivating each tool's existence).
- pair2-mcp's
  [Lock #4 — Tool catalogue cardinality](../../pair2-mcp/spec/DESIGN-RATIONALE.md#lock-4--tool-catalogue-cardinality)
  (the precedent at smaller cardinality).

---

## Lock #6 — npm package coord

**Locked 2026-05-12 (Mike).** **`@day8/re-frame2-causa-mcp`.**
The coord matches the existing
[`tools/causa/README.md`](../../causa/README.md) §MCP
advertisement and the Causa
[`010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
§Install snippet.

### Question

What's the npm package name for Causa-MCP, and does it match the
maven coord shape?

### Options considered

- **`@day8/re-frame2-causa-mcp`.** Matches the maven coord
  `day8/re-frame2-causa-mcp`. Mirrors pair2-mcp's
  `@day8/re-frame-pair2-mcp` shape.
- **`@day8/causa-mcp`.** Shorter; drops the `re-frame2-` prefix.
- **`causa-mcp`.** No scope; matches Chrome DevTools MCP's
  `chrome-devtools-mcp` shape.
- **`@re-frame2/causa-mcp`.** Different scope.

### Pick

**`@day8/re-frame2-causa-mcp`.** The maven coord is
`day8/re-frame2-causa-mcp`; the npm coord matches by convention.

### Why

- **Brand consistency.** Day8 ships the framework, the panel,
  and the agent surface under the `day8/re-frame2-*` namespace.
  Dropping the `re-frame2-` prefix on npm would suggest Causa
  is framework-agnostic; it isn't.
- **Pair2-mcp precedent.** pair2-mcp uses
  `@day8/re-frame-pair2-mcp` (matching the maven
  `day8/re-frame-pair2-mcp`); Causa-MCP mirrors the shape.
- **Search-distinguishable.** `causa-mcp` (no scope) is at risk
  of collision with whatever else "causa" comes to mean in the
  npm ecosystem. The scoped name is unambiguous.
- **The coord is already advertised.** Causa's
  [`README.md`](../../causa/README.md) §MCP and
  [`010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
  §Install both reference `@day8/re-frame2-causa-mcp` in
  install snippets users may copy-paste. Inverting the lock now
  would orphan those snippets.

### Date locked

2026-05-12 (Mike). The coord was used in the
[`010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
§Install + configure section on 2026-05-12; this lock pins it
so the npm coord doesn't drift when implementation lands.

### Trail-of-thought citations

- Causa
  [`README.md`](../../causa/README.md) §MCP (`npm install -g @day8/re-frame2-causa-mcp`).
- Causa
  [`010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
  §Install + configure.
- pair2-mcp's npm coord
  ([`tools/pair2-mcp/README.md`](../../pair2-mcp/README.md)).

---

## Lock #7 — Chrome DevTools MCP is complementary, not competitive

**Locked 2026-05-12 (Mike).** **Co-install over compete.**
Causa-MCP and Chrome DevTools MCP load side-by-side; the agent
host multiplexes. Causa-MCP does not reinvent Chrome's
browser-substrate surfaces (screenshots, DOM, network, memory,
performance).

### Question

When Causa-MCP overlaps with Chrome DevTools MCP (e.g.
`get-trace-buffer` vs `list_console_messages`), what's the
posture — compete, or co-install?

### Options considered

- **Compete: ship a fuller Causa-MCP catalogue.** Add
  screenshot capture, DOM inspection, network log reading.
  One MCP server, all the answers.
- **Co-install: stay in lane.** Causa-MCP owns the app-domain
  (re-frame2 events, subs, machines, app-db, epochs). Chrome
  MCP owns the browser-substrate. The agent host loads both.
- **Disjoint cards.** Causa-MCP only loads if Chrome MCP isn't
  detected (single-MCP-active mode).

### Pick

**Co-install.** Both servers load; the agent picks the right
tool per question. The agent host's `mcpServers` config holds
both entries.

### Why

- **Stay in lane.** Causa-MCP's expertise is the structured
  trace bus + epoch history + machine snapshots + schema
  violations. Chrome MCP's expertise is the browser substrate.
  Each is best-of-class in its lane; mashing them together
  would dilute both.
- **The structured-trace differentiator only wins by staying
  narrow.** The whole reason `get-trace-buffer` beats
  `list_console_messages` is that Causa-MCP knows the trace
  bus's vocabulary (`:dispatch-id`, `:source-coord`, `:origin`).
  Reinventing screenshots would steal effort from sharpening
  that edge.
- **The `:origin` tag works across servers.** A Chrome MCP
  `evaluate_script` mutation shows up in Causa-MCP's trace bus
  tagged `:origin :app` (it bypasses Causa-MCP's entry points);
  Causa-MCP's own dispatches show up tagged `:origin :causa-mcp`.
  The agent host's multiplex preserves the audit trail.
- **The agent host already multiplexes.** Claude Code, Cursor,
  Copilot all load multiple `mcpServers` entries. Co-install is
  the path of least friction.

### Date locked

2026-05-12 (Mike). Encoded in the
[`010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
§Chrome DevTools MCP co-install section on 2026-05-12.

### Trail-of-thought citations

- Causa
  [`010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
  §Chrome DevTools MCP co-install.
- Causa
  [`010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
  §Per-tool comparison with Chrome DevTools MCP (winner-per-row
  table).

---

## Lock #8 — bencode pinning

**Locked 2026-05-12 (Mike).** **`bencode@~2.0.3`** pinned via
npm. Same as pair2-mcp Lock #5.

### Question

Which bencode npm package handles nREPL framing for Causa-MCP?

### Pick

**`bencode@~2.0.3`**, pinned with the tilde to admit patch
updates only.

### Why

Inherited from pair2-mcp's Lock #5 (see
[`tools/pair2-mcp/spec/DESIGN-RATIONALE.md`](../../pair2-mcp/spec/DESIGN-RATIONALE.md)
#lock-5--bencode-pinning). `bencode@4+` is ESM-only and fails
under shadow-cljs's `:node-script` CommonJS interop;
`bencode@2`'s `position`-vs-`bytes` footgun is documented in
pair2-mcp's `002-nREPL-Transport.md`.

Causa-MCP inherits the pin directly — same shadow-cljs build
target, same CommonJS interop, same partial-frame decoding logic.

### Date locked

2026-05-12 (Mike). The lock is implicitly committed by the
"same as pair2-mcp" framing in Causa's
[`010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
§nREPL bridge. This lock makes the inheritance explicit so the
pin doesn't silently drift on a future `npm audit` pass.

### Trail-of-thought citations

- pair2-mcp's
  [Lock #5 — bencode pinning](../../pair2-mcp/spec/DESIGN-RATIONALE.md#lock-5--bencode-pinning).
- [`tools/pair2-mcp/spec/002-nREPL-Transport.md`](../../pair2-mcp/spec/002-nREPL-Transport.md)
  §Bencode framing.

---

## Lock #9 — Wire-protocol budget posture (the mechanism cascade)

**Locked 2026-05-13 (Mike).** **Five normative mechanisms bake
the token cap into the spec before implementation begins:**
(1) 5K-token budget cap with `:max-tokens` override and a
`{:rf.mcp/overflow {...}}` overflow marker; (2) `:path`
slicing on rich-value tools, with tree-summary as the default;
(3) opaque-cursor pagination with `:limit` defaults that fit
the cap; (4) lazy `:summary` as the default mode for rich
values, with `:sample` and `:full` opt-ins; (5) optional
structural dedup via [`day8/de-dupe`](https://github.com/day8/de-dupe)
for trace-shaped bursts.

### Question

Causa-MCP is spec-only today (no `src/`). pair2-mcp shipped its
5K cap (Principles §"Tight token budget per response") *after*
its impl already existed; retrofitting the cap into running
code is expensive (rf2-rvyzy + 10 sibling beads in flight as of
2026-05-13). Should Causa-MCP bake the entire wire-protocol
posture into its spec **before** implementation begins, so the
impl is born compliant — or wait until implementation surfaces
the same drift?

### Pick

**Bake all five mechanisms into the spec now.** The
[`Principles.md`](./Principles.md) §"Tight token budget per
response" section enumerates each mechanism with normative MUST
wording, a reserved `:rf.mcp/overflow` / `:rf.mcp/summary` /
`:rf.mcp/dedup-table` payload shape, and a catalogue-entry
contract binding `003-Tool-Catalogue.md` when it lands. No tool
ships without declaring which mechanisms apply, its
typical-token hint, its cap-reached behaviour, and its default
`:mode` / `:limit` / `:dedup?` values.

### Why

- **Greenfield is the rare cheap window.** pair2-mcp's drift
  cost (rf2-rvyzy + siblings) is the comparator. Locking the
  five mechanisms into the spec before any code exists means
  the impl is born compliant — no retrofit pass, no parallel
  code paths during transition.
- **The five mechanisms compose.** Cap (1) is the gate; slicing
  (2), pagination (3), and lazy-summary (4) are the trim
  strategies; dedup (5) is the on-wire compression that buys
  margin on top. Each mechanism alone is insufficient
  (pagination doesn't help a single 50KB map; slicing doesn't
  help an unbounded trace buffer; summary doesn't help a 10K
  sample of small events that share structure). All five
  together cover the failure modes Causa-MCP's catalogue can
  produce.
- **Cross-server alignment.** pair2-mcp's
  [`Principles.md`](../../pair2-mcp/spec/Principles.md) §"Tight
  token budget per response" already declares the cap and the
  three-axis discipline (pagination / summary / streaming);
  Causa-MCP's wording aligns deliberately so an agent learning
  the slot on one server gets the same slot on the others. The
  reserved `:rf.mcp/overflow` / `:rf.mcp/summary` keys are
  cross-server reservations, not Causa-MCP-private vocabulary.
  Where pair2-mcp's wording is silent (path slicing, structural
  dedup, `:max-tokens` override, the overflow marker shape),
  Causa-MCP's spec is the forward-locking site — a follow-up
  alignment bead may lift the wording back into pair2-mcp.
- **Structural dedup is the new mechanism.** Mechanisms 1–4 are
  refinements of pair2-mcp's three-axis discipline. Mechanism 5
  (`day8/de-dupe` substitution-table for trace bursts) is novel
  to Causa-MCP because Causa's catalogue is trace-bus-heavy and
  trace events share structural prefixes 3–5× under typical
  load. Dedup is opt-out, not opt-in, so the default agent
  experience is the compressed wire.
- **The catalogue-entry contract is the enforcement.** A
  principle without a per-tool slot is aspirational. Binding
  every catalogue entry to declare its mechanism set turns the
  cap into a compile-time-equivalent constraint: a tool entry
  missing the slots fails review, not runtime.

### Date locked

2026-05-13 (Mike). Locked in this revision (rf2-lwgg8) to
forestall the same drift pair2-mcp is paying down. Pre-dates
`tools/causa-mcp/src/` by design.

### Trail-of-thought citations

- pair2-mcp
  [`Principles.md`](../../pair2-mcp/spec/Principles.md)
  §"Tight token budget per response" — the precedent at three
  axes.
- pair2-mcp `tools.cljs` retrofit beads (rf2-rvyzy and 10
  siblings, in flight 2026-05-13) — the cost of not locking
  before impl.
- `ai/findings/wire-protocol-bigapp-20260513-1541.md`
  (recommendation #10, local-only) — the source-of-truth
  investigation that motivated baking before impl.
- [`day8/de-dupe`](https://github.com/day8/de-dupe) — the
  substitution-table substrate for mechanism 5; proven on
  re-frame-10x's epoch payloads.
- [Spec 009](../../../spec/009-Instrumentation.md) — the trace
  bus whose burst-shape mechanism 5 compresses.

---

## Lock #10 — Size-elision marker shape is normative across MCP servers

**Locked 2026-05-13 (Mike).** **`:rf.size/large-elided` is the
sixth wire-protocol mechanism, and the marker shape is
normative across the MCP triplet (pair2-mcp, story-mcp,
causa-mcp).** The marker substitutes per-value inside any
tree-typed payload; sensitive-drop wins over large-elide when
both predicates match. Per-call opt-out is `:include-large?`
(default `false`); the `:elided-large` indicator field rides
every consumer-facing tool response that walks a tree-typed
payload.

### Question

Lock #9 baked five wire-protocol mechanisms before impl began.
spec/009-Instrumentation.md (rf2-hmmx7) and spec/Conventions.md
subsequently landed `:rf.size/large-elided` as a per-value
substitution marker, distinct from the four `:rf.mcp/*`
top-level markers (cap / summary / diff / dedup). pair2-mcp's
Principles.md picked up the marker as its fifth mechanism
(rf2-urjnc). Should Causa-MCP promote it to the sixth
mechanism in `Principles.md` before impl begins, on the same
"bake-before-impl" calculus as Lock #9 — or wait until impl
surfaces the same drift?

### Pick

**Promote `:rf.size/large-elided` to mechanism #6 in
`Principles.md`.** Bake the marker shape, the `:sensitive?`
composition rule ("sensitive wins"), the `:include-large?`
opt-out slot, the `:elided-large` indicator field, and the
pipeline order ("elision runs first") into the spec before
`tools/causa-mcp/src/` exists.

### Why

- **Same calculus as Lock #9.** Greenfield is the rare cheap
  window. pair2-mcp added the marker after impl shipped
  (rf2-urjnc) and is paying the retrofit cost (the
  `elide-wire-value` walker now has to be plumbed into every
  existing tool's eval-form generator). Causa-MCP is spec-only
  today; locking the sixth mechanism alongside the first five
  means the impl is born compliant — no retrofit pass.
- **The shape is already normative upstream.** spec/009
  §"Size elision in traces" and spec/Spec-Schemas
  §`:rf/elision-marker` are the source-of-truth normative
  catalogue. Lock #10 promotes that normative shape into the
  Causa-MCP Principles.md so the catalogue contract
  (`003-Tool-Catalogue.md`, when it lands) has a per-mechanism
  slot to bind every tool to.
- **The composition rule is load-bearing.** "Sensitive wins"
  isn't an obvious default — naively, a value matching both
  predicates could emit a marker carrying a `:path` /
  `:bytes` / `:digest` triple, leaking signal an audit
  mustn't see. The spec ([Spec 009 §Composition](../../../spec/009-Instrumentation.md))
  locks the cascade `(and sensitive? large?) → ::drop`
  upstream; Lock #10 lifts that rule into Causa-MCP's
  principles so the impl can't accidentally invert it.
- **Cross-server alignment.** pair2-mcp's
  [`Principles.md` §"Size-elision wire markers"](../../pair2-mcp/spec/Principles.md#size-elision-wire-markers-rf2-urjnc)
  declares the same marker shape, the same handle vocabulary,
  the same opt-out arg name (`:include-large?`). An agent that
  learns the slot on pair2-mcp gets the same slot on
  causa-mcp. Lock #10 is the Causa-MCP-side pin.
- **The `:include-large?` opt-out is the deliberate slot.**
  Default-off, opt-in. Same posture as `:include-sensitive?`
  (Lock #4-adjacent privacy gate) — the shape rhymes so the
  agent's mental model carries across both axes.

### Date locked

2026-05-13 (Mike). Locked in rf2-knshj, following rf2-04ozp's
alignment pass (PR #709) that brought causa-mcp's existing
references to `:rf.size/large-elided` in line with pair2-mcp's
wording. This lock promotes the marker from "referenced in
the streaming section" to "named sixth mechanism with full
catalogue contract".

### Trail-of-thought citations

- [Spec 009 §Size elision in traces](../../../spec/009-Instrumentation.md)
  (rf2-hmmx7) — the normative source-of-truth for the marker
  shape, the policy keys, and the composition cascade.
- [Spec-Schemas §`:rf/elision-marker`](../../../spec/Spec-Schemas.md#rfelision-marker)
  — the schema-level catalogue entry.
- [Conventions §Reserved namespaces](../../../spec/Conventions.md#reserved-namespaces-framework-owned)
  — the `:rf.size/*` and `:rf.elision/*` reservations.
- pair2-mcp
  [`Principles.md` §"Size-elision wire markers"](../../pair2-mcp/spec/Principles.md#size-elision-wire-markers-rf2-urjnc)
  (rf2-urjnc) — the precedent consumer; same shape on the
  wire.
- causa-mcp alignment pass (rf2-04ozp, PR #709) — the
  preceding edit that landed the marker references in the
  streaming section and in §"Tight token budget per
  response"; Lock #10 promotes those references to a top-level
  mechanism.

---

## Summary table

| # | Question | Pick | Date |
|---|---|---|---|
| 1 | Implementation language | **ClojureScript + shadow-cljs → Node** (inherited from pair2-mcp Lock #1) | 2026-05-12 |
| 2 | Agent-host transport | **MCP over stdio** (inherited from pair2-mcp Lock #2) | 2026-05-12 |
| 3 | Connection model | **Single persistent nREPL socket** (inherited from pair2-mcp Lock #3) | 2026-05-12 |
| 4 | Origin tagging | **Default-on, opt-out per call — `:origin :causa-mcp`** | 2026-05-12 |
| 5 | Tool catalogue | **Seventeen tools across five bands; closed-set + `eval-cljs` escape valve** | 2026-05-12 |
| 6 | npm package coord | **`@day8/re-frame2-causa-mcp`** | 2026-05-12 |
| 7 | Chrome DevTools MCP posture | **Co-install, stay in lane** | 2026-05-12 |
| 8 | bencode pinning | **`bencode@~2.0.3`** (inherited from pair2-mcp Lock #5) | 2026-05-12 |
| 9 | Wire-protocol budget posture | **Six mechanisms baked into spec before impl** (cap + slicing + pagination + lazy-summary + dedup + size-elision; mechanism #6 added by Lock #10) | 2026-05-13 |
| 10 | Size-elision marker shape | **`:rf.size/large-elided` is the sixth mechanism; shape normative across MCP triplet; sensitive wins on composition** | 2026-05-13 |

These ten locks define Causa-MCP's pre-implementation surface.
They were extracted from
[`tools/causa/spec/010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
and [Causa's own DESIGN-RATIONALE](../../causa/spec/DESIGN-RATIONALE.md)
[Lock #6 — MCP timing](../../causa/spec/DESIGN-RATIONALE.md#lock-6--mcp-timing)
when this spec folder was stood up. When implementation work
begins, additional locks (e.g. specific test-runner choice,
specific deps-vendor choice, etc.) will land here.
