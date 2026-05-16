# 000-Vision: Causa MCP server

Causa-MCP is the **agent face of Causa**. Where
[`tools/causa/`](../../causa/) ships Causa-the-panel for human
debuggers, this artefact ships an MCP server so AI agents
(Claude Code, Cursor, Copilot, and any other host that speaks
Model Context Protocol) can drive the same surfaces — read the
trace bus, walk the epoch history, dispatch into a frame,
rewind to a named epoch, subscribe to live trace events — over
stdio JSON-RPC, without opening Causa's UI.

This spec folder is the per-tool normative contract for
`tools/causa-mcp/`. The design decisions originally accumulated
inside [`tools/causa/spec/010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
have their permanent home here, and the eighteen-tool catalogue
ships in [`004-Tools-Catalogue.md`](./004-Tools-Catalogue.md)
alongside [`004-Wire-Pipeline.md`](./004-Wire-Pipeline.md) as the
load-bearing normative pair.

## Why a separate jar

Causa-the-panel is human-facing; Causa-MCP is agent-facing.
Humans and agents ship as distinct jars so the MCP server can be
loaded without dragging the entire Causa UI into the classpath;
the two surfaces split:

- **[`tools/causa/`](../../causa/)** — `day8/re-frame2-causa`. The
  DOM-injected panel. Pulled by shadow-cljs `:preloads`.
  Browser-side artefact.
- **`tools/causa-mcp/`** — `day8/re-frame2-causa-mcp`. The MCP
  server. Pulled by `npm install`. Node-side artefact, attached
  over nREPL.

Both consume the same re-frame2 instrumentation surface
([Spec 009 trace bus](../../../spec/009-Instrumentation.md),
[Tool-Pair epoch history](../../../spec/Tool-Pair.md), registrar
query API). Neither depends on the other.

The separation is the same shape `tools/story/` and
`tools/story-mcp/` use: a human-facing panel and an agent-facing
surface ship as distinct jars so the agent jar can be loaded
without the UI dependencies.

## What it is

A Node-based stdio JSON-RPC server, written in ClojureScript,
compiled via shadow-cljs to a single `.js` artefact. AI agents
launch it as a subprocess; one persistent nREPL socket is held
for the lifetime of the session; eighteen Causa-shaped tools are
exposed as MCP tools.

The architecture mirrors [`tools/pair2-mcp/`](../../pair2-mcp/)
exactly:

- Same `@modelcontextprotocol/sdk` `StdioServerTransport`.
- Same bencode-pinned nREPL bridge (per
  [`tools/pair2-mcp/spec/002-nREPL-Transport.md`](../../pair2-mcp/spec/002-nREPL-Transport.md)).
- Same port-discovery walk (`$SHADOW_CLJS_NREPL_PORT` → standard
  shadow paths → `.nrepl-port`).
- Same **two-namespace split** between the Node-side MCP server
  and the browser-side injected runtime (per
  [§Two namespaces, two sides](#two-namespaces-two-sides) below);
  a small sentinel namespace re-ships on browser reload.
- Same degraded-boot policy: tools return structured
  `{:ok? false :reason ...}` errors rather than refusing to start.

Different tool catalogue. Different `:origin` tag.

## Two namespaces, two sides

Causa-MCP's code lives on two sides of an stdio JSON-RPC pipe.
Each side has its own namespace root; the spec keeps the two
roles separate because the implementation will too. The split
mirrors pair2-mcp exactly (see
[`tools/pair2-mcp/`](../../pair2-mcp/)'s
`re-frame-pair2-mcp.*` / `re-frame-pair2.runtime` split).

| Side | Root ns | Lives in | Loaded by | Role |
|---|---|---|---|---|
| **MCP server** (Node process) | `day8.re-frame2-causa-mcp.*` | [`tools/causa-mcp/src/`](../src/) | `npx @day8/re-frame2-causa-mcp` (the agent host spawns the subprocess) | Speaks MCP/JSON-RPC over stdio; speaks nREPL/bencode to shadow-cljs; renders eval forms that target the injected runtime. |
| **Injected runtime** (browser eval target) | `day8.re-frame2-causa.runtime` | the [Causa](../../causa/) panel's preload classpath | shadow-cljs `:devtools :preloads` (rides Causa-the-panel's existing preload) | Lives in the consumer app's runtime; exposes the eighteen tool-shaped accessors the server's eval forms call; carries the `current-origin` dynamic var that stamps `:origin :causa-mcp` on mutations. |

**The two namespaces never share a JVM / Node process.** The MCP
server runs on Node; the injected runtime runs in the browser.
They communicate exclusively via eval-form strings travelling
over the bencode-framed nREPL bridge — the server **renders** an
EDN form addressed at `day8.re-frame2-causa.runtime/<accessor>`,
shadow-cljs evaluates it inside the browser tab, the return
value comes back over the same socket. There is no shared state,
no shared classpath, no shared dep — only a contract on the
shape of the eighteen accessors.

**Why two roots, not one.** Conflating the two roles into a
single namespace (the obvious wrong default) creates two
problems. (1) It forces the consumer app's preload classpath to
pull in MCP-server code (the Node-side `@modelcontextprotocol/sdk`
imports, the bencode wire format, the stdio plumbing) that has
no business existing inside a browser bundle, even one gated by
`:preloads` — the bundle-isolation rule
([`tools/README.md`](../../README.md)) forbids it. (2) It muddles
the question "is this op a server-side or runtime-side
concern?" at code-review time — a question pair2-mcp's split
already answers cleanly. Two roots, two locations, two roles.

**Cross-side coupling is one-way.** The MCP server depends on
the runtime's accessor signatures (the contract); the runtime is
independent of the server (it can be loaded standalone via
Causa-the-panel's preload without the MCP server running at all,
which is exactly the position before `npx @day8/re-frame2-causa-mcp`
is launched). The dependency arrow flows server → runtime
contract.

**Why the runtime lives under `day8.re-frame2-causa.*`.** Because
the runtime is browser-side and rides Causa-the-panel's
preload; the panel already owns `day8.re-frame2-causa.*` for the
in-app surface. The MCP server's own code stays at
`day8.re-frame2-causa-mcp.*` (matching the maven coord
`day8/re-frame2-causa-mcp` and the npm coord
`@day8/re-frame2-causa-mcp` per
[`DESIGN-RATIONALE.md` Lock #6](./DESIGN-RATIONALE.md)).
Pair2-mcp does the equivalent: server code under
`re-frame-pair2-mcp.*`, injected runtime under
`re-frame-pair2.runtime` — the runtime ns is parented by the
**injected** artefact's root, not the **MCP-server** artefact's
root.

## What it isn't

- **Not** a new re-frame2 runtime extension. Tools route through
  the existing Causa instrumentation surfaces via `cljs-eval`;
  nothing new is registered against the framework.
- **Not** a Causa panel. The MCP server speaks JSON-RPC; the
  Causa UI is a separate artefact and need not be open for the
  MCP server to work.
- **Not** part of any production bundle. Per
  [`tools/README.md`](../../README.md), the dependency arrow flows
  tool → implementation; this artefact lives on a separate npm
  classpath and is invisible to consumer apps.
- **Not** a generalist agent surface. The tool catalogue is
  closed-set and shaped to Causa's panels; the deliberate escape
  valve is `eval-cljs`, which preserves the `:origin` tag.

## MCP catalogue summary

Eighteen tools across five bands per the
[canonical counts in `README.md`](./README.md#canonical-counts).

**The band enumeration with tool names, signatures, return
shapes, and example flows lives upstream at
[`tools/causa/spec/010-MCP-Server.md` §Tool catalogue](../../causa/spec/010-MCP-Server.md#tool-catalogue).**
Cite that section; do not restate it here. The five band
subsections (Inspection / Mutation / Streaming / Escape hatch /
Meta) carry the per-tool detail; the eighteenth-tool addition
(`list-subscriptions` in the Streaming band) is locked at
[`DESIGN-RATIONALE.md` Lock #12](./DESIGN-RATIONALE.md).

The per-tool detail for those eighteen tools lives in
[`004-Tools-Catalogue.md`](./004-Tools-Catalogue.md) — the
pair2-mcp-shape companion to `Principles.md` and
`DESIGN-RATIONALE.md`;
[`tools/causa/spec/010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
carries the Causa-panel-side prose for the same set.

Each tool maps to a Causa-side affordance (the seventeen panel
mirrors plus the streaming-registry diagnostic); together they
let an agent observe, dispatch, time-travel, stream, and inspect.

## Tool-Pair binding — the four epoch-shaped tools

Four of the eighteen tools route exclusively through
[`spec/Tool-Pair.md`](../../../spec/Tool-Pair.md)'s time-travel
surface — they are the **epoch-shaped tools**: `get-epoch-history`,
`restore-epoch`, `get-app-db-diff`, and `subscribe :epoch`. The
remaining fourteen consume Spec 009's trace bus, the registrar
query API, or the per-frame `app-db` reader directly; only these
four bind to Tool-Pair as their canonical contract source.

This subsection pins **which Tool-Pair section each tool binds
to**, upstream of [`004-Tools-Catalogue.md`](./004-Tools-Catalogue.md).
The catalogue's per-tool entries cite these bindings rather than
re-deriving the contract; drift between the catalogue prose and
Tool-Pair is caught here, not three documents later. The pattern
mirrors how
[`tools/pair2-mcp/spec/003-Tool-Catalogue.md`](../../pair2-mcp/spec/003-Tool-Catalogue.md)
cross-references its framework anchors.

| Tool | Tool-Pair binding | What the binding pins |
|---|---|---|
| `get-epoch-history` | [§Time-travel: epoch snapshots and undo](../../../spec/Tool-Pair.md#time-travel-epoch-snapshots-and-undo) — Recording, Ordering, Bounded history, Query; [`:rf/epoch-record` schema](../../../spec/Spec-Schemas.md#rfepoch-record) | The vector shape returned by `(rf/epoch-history frame-id)`, the per-record key set (`:epoch-id`, `:frame`, `:committed-at`, `:event-id`, `:trigger-event`, `:db-before`, `:db-after`, `:trace-events`, `:sub-runs`, `:renders`, `:effects`), the oldest-first ordering, the default depth (50) and the `(rf/configure :epoch-history {:depth N})` knob. The MCP tool's `{:cursor ...}` pagination wraps the same vector; the budget mechanisms ride [§Wire-protocol mechanisms](../../../spec/Tool-Pair.md#wire-protocol-mechanisms-mcp-tool-layer-not-framework) and [`004-Wire-Pipeline.md`](./004-Wire-Pipeline.md). |
| `restore-epoch` | [§Time-travel — Restore](../../../spec/Tool-Pair.md#time-travel-epoch-snapshots-and-undo); the six-row restore-failure table; [§Surface behaviour against destroyed frames](../../../spec/Tool-Pair.md#surface-behaviour-against-destroyed-frames) | The `(rf/restore-epoch frame-id epoch-id)` call shape, the `:rf.epoch/restored` success op, and the **closed-set** failure surface — `:rf.error/no-such-handler` (kind `:frame`), `:rf.epoch/restore-unknown-epoch`, `:rf.epoch/restore-schema-mismatch`, `:rf.epoch/restore-missing-handler`, `:rf.epoch/restore-version-mismatch`, `:rf.epoch/restore-during-drain`. The MCP tool's return shape (`{:ok? false :reason <op> :tags {...}}` on failure) is a structured projection of those six rows; new failure rows are additive and require a Spec-ulation increment on Tool-Pair *first*, the MCP tool *second*. |
| `get-app-db-diff` | [§Time-travel — Recording](../../../spec/Tool-Pair.md#time-travel-epoch-snapshots-and-undo) (the `:db-before` / `:db-after` slots); [`:rf/epoch-record` schema](../../../spec/Spec-Schemas.md#rfepoch-record); [§Wire-protocol mechanisms — diff-encoded `:db-after`](../../../spec/Tool-Pair.md#wire-protocol-mechanisms-mcp-tool-layer-not-framework) | The two per-epoch app-db snapshots the tool diffs (`:db-before` is the value *before* the cascade ran, `:db-after` is the value *after* drain-settle). The diff envelope (path-keyed `:added` / `:removed` / `:changed` slots) is the MCP-server layer's concern, not the framework's; Tool-Pair pins the **inputs** to the diff. Sensitive paths in either snapshot ride [§Direct-read privacy posture](../../../spec/Tool-Pair.md#direct-read-privacy-posture-for-sub-cache-and-get-path) — `rf/elide-wire-value` scrubs at the wire boundary before the diff lands in the response. |
| `subscribe :epoch` | [§Subscribing to assembled epoch records](../../../spec/Tool-Pair.md#subscribing-to-assembled-epoch-records); [§Surface behaviour against destroyed frames](../../../spec/Tool-Pair.md#surface-behaviour-against-destroyed-frames) (listener silencing) | The `register-epoch-cb!` listener contract — one callback per drain-settle, fully-shaped `:rf/epoch-record` per delivery, listener exceptions are caught and **not** auto-evicting, re-entrant dispatch from a callback is legal. The one-shot `:rf.epoch.cb/silenced-on-frame-destroy` trace lands on the agent's stream when an observed frame is destroyed; the MCP subscription bridges that trace through its envelope so the agent's loop terminates deterministically. The streaming byte+event budget lives in [`004-Wire-Pipeline.md`](./004-Wire-Pipeline.md). |

**Why these four, not the other fourteen.** The remaining
tools bind to different framework contracts: the inspection band
(`get-app-db`, `get-handlers`, `get-machine-list`,
`get-machine-state`, `get-issues`, `get-trace-buffer`,
`get-source-coord`, `list-subscriptions`, `get-handler-source`)
binds to Spec 002's registrar query API and Spec 009's trace
buffer; the mutation band (`dispatch`, `reset-frame-db`,
`hot-swap`) binds to Spec 002's dispatch / state-injection
surfaces and Spec 001's hot-reload semantics; the streaming band
otherwise (`subscribe :trace`, `subscribe :error`) binds to Spec
009's listener API; the escape hatch (`eval-cljs`) binds to
[§REPL-eval](../../../spec/Tool-Pair.md#repl-eval) only loosely;
the meta band (`list-tools`, `session-info`) binds to nothing
framework-side. Tool-Pair is the **time-travel** contract — and
only four tools time-travel.

**Sibling contracts that ride alongside, not through, Tool-Pair.**
The runtime fxs `:rf.ssr/check-version` and
`:rf.ssr/check-schema-digest` (per
[Spec 011 §SSR fx surface](../../../spec/011-SSR.md), landed
rf2-69ad2) supply the **schema-digest** value that
`restore-epoch`'s `:rf.epoch/restore-schema-mismatch` row carries
(`:schema-digest-recorded` / `:schema-digest-current`). Causa-MCP
does not consume the SSR fxs directly — the digest reaches the
tool through the `:rf.epoch/restore-schema-mismatch` trace — but
agents debugging an SSR-hydration mismatch combine
`get-epoch-history` (to read the recorded digest) with
`restore-epoch`'s failure trace (to read the live digest) to
isolate where the schema drifted. The binding cite stays on
Tool-Pair's restore-failure table; the SSR fxs are a sibling
producer of one of its fields, not a separate binding.

**How the catalogue cites these rows.** Each of the four
per-tool entries in [`004-Tools-Catalogue.md`](./004-Tools-Catalogue.md)
cites the binding row above and adds the MCP-specific wire shape
(the input arg map, the response envelope, the error projection).
The binding row itself stays in this Vision doc as the **single
anchor** the catalogue points back to — Tool-Pair drift can be
caught by reading this subsection alone, without re-folding the
catalogue.

## Every MCP-driven mutation leaves a visible footprint

The defining property of Causa-MCP — and a first-class
differentiator against generalist agent surfaces like Chrome
DevTools MCP's `evaluate_script` — is that **every dispatch,
every `reset-frame-db`, every `restore-epoch` issued through this
server is tagged `:origin :causa-mcp` on the trace bus**. Chrome's
`evaluate_script` is untagged; an agent that hand-rolls
`js/window.dispatch(...)` is indistinguishable in the trace from
a real user click. Causa-MCP's tag inversion makes the
post-incident question "what did the agent do?" answerable via a
single filter.

The discipline (default-on, per-call opt-out, scope, the full
`:app` / `:pair` / `:causa-mcp` / `:story` / `:test` vocabulary,
worked trace-filter examples) lives in
[`Principles.md` §"Origin tagging is the convention, not a
suggestion"](./Principles.md#origin-tagging-is-the-convention-not-a-suggestion);
the lock is
[`DESIGN-RATIONALE.md` Lock #4](./DESIGN-RATIONALE.md#lock-4--origin-tagging-is-the-convention).

## Relationship to Causa

Causa-MCP **consumes** Causa's runtime surfaces via the shared
[Spec 009](../../../spec/009-Instrumentation.md) instrumentation
contract:

| Causa panel | Causa-MCP tool(s) |
|---|---|
| Trace timeline | `get-trace-buffer`, `subscribe :trace` |
| Time-travel scrubber | `get-epoch-history`, `restore-epoch`, `subscribe :epoch` |
| App-db inspector | `get-app-db`, `get-app-db-diff` |
| Machine inspector | `get-machine-state`, `get-machine-list` |
| Issues ribbon | `get-issues`, `subscribe :error` |
| Settings → registry browser | `get-handlers`, `get-source-coord` |
| Right-click → Re-dispatch | `dispatch` |
| "Try anyway" on schema mismatch | `reset-frame-db` |
| Bottom-rail Rewind button | `restore-epoch` |

Neither artefact depends on the other at the classpath. The
Causa panel works without the MCP server; the MCP server works
without the panel. The two coexist in the same session — agents
can drive Causa-MCP while a human inspects the Causa panel; both
see the same runtime, and the `:origin` tag is what lets a human
scrubbing the timeline see at-a-glance which mutations the agent
authored.

## Relationship to `tools/pair2-mcp/`

Causa-MCP is the **debugger-side** counterpart to pair2-mcp's
**editor-side** workflow:

| Axis | [`tools/pair2-mcp/`](../../pair2-mcp/) | `tools/causa-mcp/` |
|---|---|---|
| Audience | Editor-side AI workflows (build/edit/test). | Debugger-side AI workflows (inspect/time-travel). |
| Surface | 14 tools (editor-side: discover/eval/dispatch/snapshot/trace + streaming pair + registrar-introspection pair + onboarding). | 18 tools (inspection + mutation + streaming + escape hatch + meta). |
| `:origin` tag | `:pair` | `:causa-mcp` |
| MCP-server ns (Node-side) | `re-frame-pair2-mcp.*` (e.g. `re-frame-pair2-mcp.server`, `.tools`, `.nrepl`, `.cache`) | `day8.re-frame2-causa-mcp.*` (e.g. `.server`, `.tools`, `.nrepl`, `.elision`, `.privacy`, `.token-cap`) |
| Injected-runtime ns (browser-side) | `re-frame-pair2.runtime` | `day8.re-frame2-causa.runtime` (rides Causa's preload) |
| Implementation | shadow-cljs `:node-script`, npm-published. | Same. |
| MCP transport | stdio JSON-RPC 2.0. | Same. |

Both can coexist in the same session — the agent picks the right
tool for the workflow (pair2-mcp for "build/edit/test";
causa-mcp for "inspect/debug/time-travel"). They don't talk to
each other; the agent host multiplexes.

## Relationship to Chrome DevTools MCP

[Chrome DevTools MCP](https://github.com/ChromeDevTools/chrome-devtools-mcp)
owns the **browser-substrate** (screenshots, DOM, network,
memory, performance, 46 tools across 8 categories as of v0.26.0,
May 2026). Causa-MCP owns the **app-domain** (re-frame2 events,
subs, machines, app-db, epochs). They are **complementary, not
competitive** — an agent debugging a real bug typically wants
both, and the agent host's `mcpServers` config loads them
side-by-side.

The defining differentiator on the rows that overlap (e.g.
`get-trace-buffer` vs Chrome's `list_console_messages`) is
**structured trace**. Chrome's console is a flat string log;
Causa-MCP's trace bus carries `:dispatch-id`,
`:parent-dispatch-id`, `:source-coord`, `:origin`, `:event-id`,
`:handler-id`, `:since-ms`, `:between`, `:pred` as filter axes.
Every domain question is a filter, not a regex.

Per-tool comparison and the co-install snippet are in
[`tools/causa/spec/010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
§Chrome DevTools MCP co-install.

## Why ClojureScript + shadow-cljs → Node

Same answer as pair2-mcp (per
[`tools/pair2-mcp/spec/DESIGN-RATIONALE.md`](../../pair2-mcp/spec/DESIGN-RATIONALE.md)
Lock #1). The team runs shadow-cljs daily; one more
`:node-script` target is trivial; the npm MCP SDK is reachable
via js-interop; end users install Node only. JVM Clojure was
rejected for startup cost; nbb / pure babashka for tooling
maturity; foreign-language stacks for ecosystem mismatch.

Captured as Lock #1 in [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md).

## Why a server, not an IDE plugin

MCP is the agent-host's contract for tool integration — one
stdio JSON-RPC server works with every MCP-capable host (Claude
Code, Cursor, Copilot, etc.) without per-host plumbing. The
tie-breaker and the rejected alternatives live at
[`Principles.md` §"MCP, not an IDE plugin"](./Principles.md#mcp-not-an-ide-plugin);
captured as Lock #2 in [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md).

## Status

**Catalogue complete; eighteen tools shipped.** The Inspection
band (T-Insp, rf2-8xzoe.14..22), Mutation band (T-Mut,
rf2-8xzoe.23..25), Streaming band (T-Stream, rf2-8xzoe.26..28),
and Meta band (T-Meta, rf2-8xzoe.29..32) are all live. The
catalogue lives in [`004-Tools-Catalogue.md`](./004-Tools-Catalogue.md);
the wire-pipeline contracts in [`004-Wire-Pipeline.md`](./004-Wire-Pipeline.md);
the locked design decisions in [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md);
the load-bearing tie-breakers in [`Principles.md`](./Principles.md).

Sibling cross-references:

- [`tools/causa/spec/010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
  describes the same MCP contract from Causa-the-panel's side —
  user-facing install / configure prose lives there alongside the
  band enumeration; this folder owns the agent-facing normative
  catalogue.
- [`tools/causa/README.md`](../../causa/README.md) §MCP
  advertises `npm install -g @day8/re-frame2-causa-mcp` — the
  npm coord pinned by [`DESIGN-RATIONALE.md` Lock #4](./DESIGN-RATIONALE.md).
