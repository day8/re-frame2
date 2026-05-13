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
writing the eighteen-tool dispatcher rather than rebuilding the
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
**injected-runtime** install hook's
`day8.re-frame2-causa.runtime/current-origin` dynamic var, per
Lock #11's two-namespace split). An agent that genuinely wants
to simulate a user click passes `:origin :app` explicitly — the
override is one keyword.

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

**Amended 2026-05-14 (rf2-3we2k).** [Lock #12](#lock-12--subscription-info-parity-with-pair2-mcp)
adds `subscription-info` as the eighteenth tool, placed in band
**Streaming** (so the band moves 2 → 3) for cross-server parity
with pair2-mcp. The closed-set posture and the per-tool Lock-entry
discipline this lock established are unchanged; Lock #12 is the
audit trail for the addition.

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

**Seventeen tools across five bands** at original-lock time;
**eighteen tools across five bands** after the 2026-05-14
amendment (Lock #12). The catalogue is closed-set on purpose —
additions are deliberate (a Lock entry here, a discussion).
`eval-cljs` absorbs the long tail.

### Why

- **One tool per Causa panel is the symmetry.** Causa's pitch
  is "the cascade you can see"; Causa-MCP's pitch is "the
  cascade your agent can read." Seventeen tools map cleanly to
  seventeen Causa surfaces (9 read + 3 mutate + 2 stream + 1
  escape hatch + 2 meta). The eighteenth (`subscription-info`)
  doesn't map to a panel — it maps to a *cross-server slot*
  shipped by pair2-mcp's streaming surface, which Lock #12
  promotes to first-class cross-server-symmetric parity.
  Together: seventeen panel mappings + one cross-server parity
  entry = eighteen tools. The agent reading
  `tools/list` sees a near-one-to-one map plus the parity slot.
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
cardinality and the closed-set policy. Amended 2026-05-14 by
[Lock #12](#lock-12--subscription-info-parity-with-pair2-mcp)
to add `subscription-info` (eighteenth tool, band Streaming).

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
- **The shape is `re-frame2-<tool>-mcp`, deliberately.** Causa-MCP
  picks `@day8/re-frame2-causa-mcp` — the `2` rides on the
  framework name, not the tool name. story-mcp already follows
  the same shape (`day8/re-frame2-story-mcp`, per
  [`tools/story-mcp/deps.edn`](../../story-mcp/deps.edn) and
  [`tools/story-mcp/spec/000-Vision.md`](../../story-mcp/spec/000-Vision.md));
  future MCP artefacts inherit this shape, not pair2-mcp's. The
  symmetry posture (per
  [`Principles.md`](./Principles.md) §"Tight token budget per
  response" and §"Cross-server alignment", and Spec
  [`Principles.md`](../../../spec/Principles.md)) is that agents
  "learn the slot on one server, get the same on the others" —
  the maven/npm coord is one of those slots.
- **Pair2-mcp diverges; the divergence is historical, not
  normative.** pair2-mcp's coord is `@day8/re-frame-pair2-mcp`
  — bare `re-frame` framework, the `2` carried by the tool name
  as `pair2`. That shape pre-dates the framework's `re-frame` →
  `re-frame2` rename and was locked-in by published artefacts
  (`npm install -g @day8/re-frame-pair2-mcp` in
  [`tools/pair2-mcp/README.md`](../../pair2-mcp/README.md), the
  `skills/re-frame-pair2/scripts/*.sh` shims, the maven coord
  `day8/re-frame-pair2-mcp` in
  [`tools/pair2-mcp/deps.edn`](../../pair2-mcp/deps.edn))
  before causa-mcp's coord was picked. Causa-MCP launches
  post-rename and picks the post-rename shape; the divergence
  is intentional and one-directional — **new MCP artefacts adopt
  Causa-MCP's shape (`re-frame2-<tool>-mcp`), not pair2-mcp's
  (`re-frame-<tool>2-mcp`).** A separate v1.0-major-bump bead
  may rename pair2-mcp's coord to eliminate the divergence
  permanently (tracked at rf2-5ky6c); that's a published-artefact
  migration, not a pre-impl spec concern, and out of scope for
  this lock.
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
  ([`tools/pair2-mcp/README.md`](../../pair2-mcp/README.md))
  — the diverging precedent (`@day8/re-frame-pair2-mcp`), locked
  in by published artefacts before the framework's
  `re-frame` → `re-frame2` rename.
- story-mcp's coord
  ([`tools/story-mcp/deps.edn`](../../story-mcp/deps.edn),
  [`tools/story-mcp/spec/000-Vision.md`](../../story-mcp/spec/000-Vision.md))
  — `day8/re-frame2-story-mcp`, the shape Causa-MCP shares and
  future MCP artefacts inherit.
- rf2-l7skx (this revision, 2026-05-14) — the audit-driven
  reconcile that pinned the divergence explicitly. Surfaced by
  rf2-m9yoi (the rf2-22my5 / rf2-c9b90 follow-on audit).

### Date amended

2026-05-14 (Mike). Lock #6 was silent on the coord-shape
divergence from pair2-mcp at first locking (2026-05-12); the
rf2-m9yoi audit surfaced the silence as a §Q3 finding and
rf2-l7skx amended the Why subsection to pin the divergence
explicitly. The pick is unchanged
(`@day8/re-frame2-causa-mcp`); the amendment is reasoning-trail
only.

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

## Lock #11 — MCP-server-ns and injected-runtime-ns are distinct

**Locked 2026-05-14 (Mike).** **The MCP-server-side code lives
under `day8.re-frame2-causa-mcp.*` (Node-only); the
injected-runtime-side code lives under
`day8.re-frame2-causa.runtime` (browser-only, parented by
Causa-the-panel's existing root and loaded via its `:preloads`
classpath).** The two namespaces never share a process and never
share a classpath; they communicate only via eval-form strings
sent over the bencode-framed nREPL bridge.

### Question

Spec scaffold authoring (rf2-22my5, PR #823) shipped with a
single namespace `re-frame2-causa-mcp.runtime` covering both the
MCP-server's code and the browser-side runtime accessors the
server eval-targets. The pair2-mcp template, on which the
Causa-MCP architecture is explicitly modelled (Locks #1–#3, #8),
splits the two cleanly into `re-frame-pair2-mcp.*` (Node-side,
`tools/pair2-mcp/src/`) and `re-frame-pair2.runtime` (browser-side,
`skills/re-frame-pair2/preload/`). Should Causa-MCP's spec follow
the same split, or collapse the two into one (and document the
dual role)?

### Options considered

- **Option A — split the namespaces.** Mirror pair2-mcp exactly:
  Node-side server code at `day8.re-frame2-causa-mcp.*`,
  browser-side injected runtime at `day8.re-frame2-causa.runtime`
  (riding Causa-the-panel's `day8.re-frame2-causa.*` root because
  the runtime is loaded by Causa's preload, not by a separate
  MCP-only preload).
- **Option B — collapse the namespaces.** Keep the single
  `re-frame2-causa-mcp.runtime` ns and document the dual role:
  "the same namespace is loaded server-side in the Node MCP
  process and runtime-side in the consumer app's browser; the
  runtime-side functions are gated by environment-detection".
- **Option C — split differently.** Server-side at
  `re-frame2-causa-mcp.*`, browser-side at
  `re-frame2-causa.runtime` (drop the `day8.` Maven-style prefix
  on both to match Causa-the-panel's pair2-mcp inheritance ns
  shape).

### Pick

**Option A.** Two namespaces, two locations, two roles, parented
exactly the way pair2-mcp parents its analogous pair:

| Side | Root ns | Lives in | Loaded by |
|---|---|---|---|
| MCP server (Node) | `day8.re-frame2-causa-mcp.*` | `tools/causa-mcp/src/` | `npx @day8/re-frame2-causa-mcp` |
| Injected runtime (browser) | `day8.re-frame2-causa.runtime` | Causa-the-panel preload | shadow-cljs `:devtools :preloads` |

The `day8.` Maven-style prefix is kept on both because
Causa-the-panel already uses `day8.re-frame2-causa.*` for its
own browser-side code (per
[`tools/causa/src/day8/re_frame2_causa/`](../../causa/src/day8/re_frame2_causa/))
and the injected runtime rides the same preload as Causa-the-panel.
The MCP server's own code is parented `day8.re-frame2-causa-mcp.*`
matching the maven coord `day8/re-frame2-causa-mcp` (Lock #6) and
the npm coord `@day8/re-frame2-causa-mcp` (Lock #6).

### Why

- **Bundle isolation is the load-bearing rule.** Per
  [`tools/README.md`](../../README.md), MCP-server code must
  never reach a consumer app's preload classpath. A single-ns
  conflation forces the consumer's preload to pull
  `@modelcontextprotocol/sdk` + the bencode framer + the
  Node-side stdio plumbing into the browser bundle — wreckage
  Causa-the-panel already pays lint gates to avoid. The split
  enforces the isolation at the `:require` line rather than at
  review time.
- **Mirroring pair2-mcp is free architectural alignment.**
  Pair2-mcp's `re-frame-pair2-mcp.*` / `re-frame-pair2.runtime`
  split is the proven precedent (rf2-7dvg cut the inject step
  in favour of `:preloads`, locking the two-ns shape). Causa-MCP
  inheriting Locks #1–#3 + #8 from pair2-mcp implies the same
  bundle-shape inheritance; collapsing the namespaces would
  invert that inheritance silently.
- **Reviewer/implementer ergonomics.** A code-review comment
  asking "is this op a server-side or runtime-side concern?"
  becomes mechanical on a two-ns layout: read the namespace,
  done. On a single-ns layout, the answer requires reading the
  whole function body and reasoning about which globals exist
  in which environment.
- **The runtime parented by Causa, not the MCP server, is the
  right shape.** The runtime *is* a browser-side artefact; its
  natural home is alongside the panel's other browser-side
  preloads. Parenting it `day8.re-frame2-causa-mcp.*` would
  suggest it ships *with* the MCP server (Node-side), which is
  wrong: the runtime ships with Causa-the-panel, and the MCP
  server's eval forms address it by name once it's already
  loaded.
- **Option C (drop the `day8.` prefix) was rejected** because
  Causa-the-panel's own browser-side code uses
  `day8.re-frame2-causa.*` (e.g. `day8.re-frame2-causa.preload`,
  `day8.re-frame2-causa.trace-bus`); the injected runtime
  sharing that root is what makes it a natural Causa-panel
  preload. Pair2-mcp's choice to drop the day8 prefix
  (`re-frame-pair2.runtime` instead of
  `day8.re-frame-pair2.runtime`) was a pre-existing
  inconsistency the Causa-MCP folder doesn't have to inherit;
  Causa-the-panel's `day8.` prefix is the local convention.
- **Option B (collapse and document dual role) was rejected**
  because environment-detection at function-call time replaces
  a static layout invariant with a runtime check. Static
  layout is cheaper, lint-detectable, and impossible to break
  by accident.

### Date locked

2026-05-14 (Mike). Locked in rf2-c9b90 (this revision),
following the rf2-m9yoi audit which surfaced the conflation in
the 2026-05-13 spec scaffold (rf2-22my5, PR #823). Pre-dates
`tools/causa-mcp/src/` by design — the same "bake-before-impl"
calculus as Locks #9 and #10.

### Trail-of-thought citations

- pair2-mcp source layout:
  [`tools/pair2-mcp/src/re_frame_pair2_mcp/`](../../pair2-mcp/src/re_frame_pair2_mcp/)
  (server-side: `server.cljs`, `tools.cljs`, `cache.cljs`,
  `nrepl.cljs`) and
  [`skills/re-frame-pair2/preload/re_frame_pair2/runtime.cljs`](../../../skills/re-frame-pair2/preload/re_frame_pair2/runtime.cljs)
  (browser-side) — the precedent.
- pair2-mcp Principles §"Tool consumes the framework" — names
  `re-frame-pair2.runtime` as the injected-runtime ns, distinct
  from the MCP-server-side code that consumes it.
- Causa-the-panel ns layout:
  [`tools/causa/src/day8/re_frame2_causa/`](../../causa/src/day8/re_frame2_causa/)
  — the `day8.re-frame2-causa.*` umbrella the injected runtime
  parents under.
- [`tools/README.md`](../../README.md) §Bundle isolation — the
  rule the split enforces.
- rf2-m9yoi (the audit that surfaced the conflation in
  rf2-22my5's spec scaffold).

---

## Lock #12 — `subscription-info` parity with pair2-mcp

**Locked 2026-05-14 (Mike).** **`subscription-info` is the
eighteenth Causa-MCP tool, in band Streaming.** A pure-read
diagnostic over the in-memory subscriptions registry; no queue
drain; mirrors pair2-mcp's identically-named tool slot for
cross-server symmetry. Lock #5's seventeen-across-five-bands
shape is amended to eighteen-across-five-bands; band Streaming
moves 2 → 3 tools (`subscribe`, `unsubscribe`,
`subscription-info`).

### Question

Pair2-mcp ships **three** streaming tools (`subscribe`,
`unsubscribe`, **`subscription-info`** — per
[`tools/pair2-mcp/src/re_frame_pair2_mcp/tools.cljs`](../../pair2-mcp/src/re_frame_pair2_mcp/tools.cljs)).
Causa-MCP's spec scaffold (Lock #5, 2026-05-12) describes
**two** streaming tools (`subscribe`, `unsubscribe`) and is
silent on the third. Either Causa-MCP genuinely lacks the
`subscription-info` surface (a cross-server-parity hole) or it
bundles the call into another tool (e.g. `subscribe` with a
`:mode :info` arg) without saying so. Which?

### Options considered

- **Option A — eighteen tools, name the eighteenth.** Add
  `subscription-info` as its own catalogue entry; place it in
  band Streaming alongside `subscribe` / `unsubscribe` so the
  band-grouping matches pair2-mcp's
  [tools.cljs docstring](../../pair2-mcp/src/re_frame_pair2_mcp/tools.cljs)
  catalogue. Streaming band goes 2 → 3; total goes 17 → 18.
- **Option B — bundle into `subscribe`.** Add a `:mode :info`
  arg to `subscribe`; when set, the call returns the
  subscriptions registry view instead of opening a stream.
  Catalogue stays at seventeen.
- **Option C — leave the surface off causa-mcp.** Agents who
  want the diagnostic write `eval-cljs
  "(day8.re-frame2-causa.runtime/subscription-info)"`.
  Catalogue stays at seventeen; the escape hatch absorbs the
  divergence.

### Pick

**Option A — eighteen tools, name the eighteenth in band
Streaming.**

### Why

- **Cross-server symmetry is load-bearing.**
  [`Principles.md` §Privacy](./Principles.md) and §Tight token
  budget both spell out the contract: "an agent that learns the
  slot on one server gets the same slot on the others." A
  diagnostic the agent learns to call on pair2-mcp must be
  findable in `tools/list` on causa-mcp under the same name.
  Option B (bundle into `subscribe`) hides the diagnostic behind
  an arg that the agent has to know exists; Option C (leave to
  `eval-cljs`) hides it behind a CLJS form the agent has to
  hand-render. Both options break the discoverability contract
  the symmetry posture rests on.
- **`subscription-info` is a distinct op, not a mode of
  `subscribe`.** `subscribe` opens a long-lived
  `notifications/progress` stream; `subscription-info` is a
  single pure-read snapshot over the in-memory subscriptions
  registry. Conflating them muddles the semantics on the
  pair2-mcp side
  (`subscribe` returns a stream sub-id; `subscribe {:mode :info}`
  would return a vector of entries — different return shapes for
  the same tool name, the kind of polymorphism the closed-set
  catalogue discipline exists to avoid). The pair2-mcp
  implementer split them deliberately (rf2-zjz9q); causa-mcp
  inherits the split, not the conflation.
- **Streaming is the right band for the eighteenth.** The
  diagnostic is *about* streams: its return shape lists per-sub
  queue depth, drop counts, overflow reason — fields that exist
  only because of streaming. Putting it next to `subscribe` and
  `unsubscribe` matches pair2-mcp's grouping (per
  [tools.cljs docstring](../../pair2-mcp/src/re_frame_pair2_mcp/tools.cljs)
  catalogue table) so an agent navigating either server's
  `tools/list` finds the three streaming tools side-by-side.
  The alternative — placing it in Meta alongside `discover-app`
  / `tail-build` — would group it with session-lifecycle tools,
  which it isn't (it's per-stream diagnostic, not per-session).
- **Eighteen is still inside the closed-set ceiling.** Lock #5's
  closed-set discipline is *deliberate-additions-with-Lock-
  entries*, not *seventeen-is-magical*. The
  agent-confusion-as-cardinality-grows concern (Lock #5 §Why)
  is real, but seventeen vs eighteen is one step — well inside
  the "named tools an agent can hold in context" ceiling Lock #5
  draws around the high teens.
- **The eighteenth maps to a cross-server slot, not a Causa
  panel.** Lock #5's one-tool-per-Causa-panel symmetry is
  preserved: seventeen tools still map one-to-one to seventeen
  Causa panels. The eighteenth is a cross-server-parity entry,
  not a panel mapping — Lock #5 §Why is amended in-place to name
  the distinction. Future additions follow the same shape: a
  panel mapping OR a cross-server-symmetric slot already shipped
  by a sibling MCP server; either way, a Lock entry here.
- **`eval-cljs` as a fallback (Option C) is the wrong shape for
  *diagnostic discoverability*.** The escape hatch is for the
  long tail of one-off agent needs (Lock #5 §Why); putting a
  recurrent cross-server-shared diagnostic behind it would be
  exactly the "if a particular `eval-cljs` call becomes
  recurrent, that's the signal to promote it" pattern Lock #5
  describes — applied pre-impl by this lock.
- **Implementation cost is near-zero.** `subscription-info`'s
  pair2-mcp implementation
  ([`tools/pair2-mcp/src/re_frame_pair2_mcp/tools/subscription_info.cljs`](../../pair2-mcp/src/re_frame_pair2_mcp/tools/subscription_info.cljs))
  is 52 lines: a `cljs-eval` of
  `(<runtime-ns>/subscription-info {:topic :sub-id})` with
  Clojure-side predicate splicing. The causa-mcp port is the
  same shape with `day8.re-frame2-causa.runtime/subscription-info`
  as the eval target. The runtime-side accessor lives in
  Causa-the-panel's preload classpath alongside the other
  seventeen accessors (Lock #11) — adding it costs ~30 lines on
  the runtime side and ~50 on the MCP-server side. The savings
  in agent-side `eval-cljs` round-trips dwarf the addition cost
  the first time an agent calls it.

### Date locked

2026-05-14 (Mike). Locked in rf2-3we2k, the spec decision the
rf2-m9yoi audit surfaced (§A6: "Causa-MCP genuinely lacks the
`subscription-info` surface (likely oversight); add it as the
eighteenth tool, retaining 'eighteen' arithmetic and reframing
the phantom 'Compatibility' band as Streaming (3 tools instead
of 2)"). The rf2-22my5 count-of-things lift (PR #823) is the
prior-art comparator: at that lock the count was 17 because
this surface was missing; this lock fixes the gap by adding the
eighteenth rather than letting the discoverability hole stand.

### Trail-of-thought citations

- pair2-mcp source layout:
  [`tools/pair2-mcp/src/re_frame_pair2_mcp/tools/subscription_info.cljs`](../../pair2-mcp/src/re_frame_pair2_mcp/tools/subscription_info.cljs)
  (the implementation Causa-MCP will port);
  [`tools/pair2-mcp/src/re_frame_pair2_mcp/tools.cljs`](../../pair2-mcp/src/re_frame_pair2_mcp/tools.cljs)
  (the catalogue docstring that places it in the streaming
  group);
  [`tools/pair2-mcp/src/re_frame_pair2_mcp/tools/descriptors.cljs`](../../pair2-mcp/src/re_frame_pair2_mcp/tools/descriptors.cljs)
  §`subscription-info` (the descriptor + input schema the
  causa-mcp catalogue will mirror).
- pair2-mcp lineage: **rf2-zjz9q** filed the original
  `subscription-info` tool on pair2-mcp; **rf2-hq49** is the
  streaming substrate it diagnoses against; **rf2-rvyzy** is the
  token-budget lock the diagnostic helps an agent tune (queue
  drop reasons help size `:max-buffered-events` /
  `:max-buffered-bytes` for the next call).
- Lock #5 (this doc) — the cardinality lock this lock amends.
- Lock #11 (this doc) — the two-ns split that determines where
  the runtime-side accessor lands
  (`day8.re-frame2-causa.runtime`, rides the panel's preload).
- rf2-m9yoi audit findings doc
  (`ai/findings/refactor-audit-tools-causa-mcp-2026-05-14.md`
  §A6) — the surface this lock answers.
- rf2-22my5 (PR #823) — the count-of-things lift; this lock is
  the "if the answer is 18: amend rf2-22my5" branch the audit
  flagged.

---

## Summary table

| # | Question | Pick | Date |
|---|---|---|---|
| 1 | Implementation language | **ClojureScript + shadow-cljs → Node** (inherited from pair2-mcp Lock #1) | 2026-05-12 |
| 2 | Agent-host transport | **MCP over stdio** (inherited from pair2-mcp Lock #2) | 2026-05-12 |
| 3 | Connection model | **Single persistent nREPL socket** (inherited from pair2-mcp Lock #3) | 2026-05-12 |
| 4 | Origin tagging | **Default-on, opt-out per call — `:origin :causa-mcp`** | 2026-05-12 |
| 5 | Tool catalogue | **Eighteen tools across five bands; closed-set + `eval-cljs` escape valve** (originally seventeen at 2026-05-12 lock time; amended 2026-05-14 by Lock #12 to add `subscription-info` for pair2-mcp parity, band Streaming 2 → 3) | 2026-05-12 (amended 2026-05-14) |
| 6 | npm package coord | **`@day8/re-frame2-causa-mcp`** (shape `re-frame2-<tool>-mcp`; story-mcp matches; pair2-mcp's `re-frame-<tool>2-mcp` shape is a pre-rename divergence pinned 2026-05-14 by rf2-l7skx) | 2026-05-12 (amended 2026-05-14) |
| 7 | Chrome DevTools MCP posture | **Co-install, stay in lane** | 2026-05-12 |
| 8 | bencode pinning | **`bencode@~2.0.3`** (inherited from pair2-mcp Lock #5) | 2026-05-12 |
| 9 | Wire-protocol budget posture | **Six mechanisms baked into spec before impl** (cap + slicing + pagination + lazy-summary + dedup + size-elision; mechanism #6 added by Lock #10) | 2026-05-13 |
| 10 | Size-elision marker shape | **`:rf.size/large-elided` is the sixth mechanism; shape normative across MCP triplet; sensitive wins on composition** | 2026-05-13 |
| 11 | Namespace split | **MCP-server-side at `day8.re-frame2-causa-mcp.*` (Node); injected-runtime-side at `day8.re-frame2-causa.runtime` (browser, rides Causa-the-panel's preload). Mirrors pair2-mcp's `re-frame-pair2-mcp.*` / `re-frame-pair2.runtime` split.** | 2026-05-14 |
| 12 | `subscription-info` parity | **`subscription-info` is the eighteenth tool, band Streaming. Pure-read diagnostic over the in-memory subscriptions registry; mirrors pair2-mcp's identically-named slot for cross-server symmetry. Amends Lock #5.** | 2026-05-14 |

These twelve locks define Causa-MCP's pre-implementation surface.
They were extracted from
[`tools/causa/spec/010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
and [Causa's own DESIGN-RATIONALE](../../causa/spec/DESIGN-RATIONALE.md)
[Lock #6 — MCP timing](../../causa/spec/DESIGN-RATIONALE.md#lock-6--mcp-timing)
when this spec folder was stood up. When implementation work
begins, additional locks (e.g. specific test-runner choice,
specific deps-vendor choice, etc.) will land here.
