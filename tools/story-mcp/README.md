# tools/story-mcp/

`day8/re-frame2-story-mcp` — the **MCP (Model Context Protocol) agent
surface** for re-frame2-story. Lands as Stage 7 of the Story epic
(`rf2-tgci`); design contract is [`spec/`](./spec/).

## What it is

A JVM-side stdio JSON-RPC server that exposes Story's read (and gated
write) surface as MCP tools. AI agents (Claude Code, Cursor, Copilot)
launch the server as a subprocess, perform the `initialize` handshake,
then call tools like `list-stories`, `run-variant`, `snapshot-identity`,
`get-story-instructions` to navigate / drive / introspect the Story
library.

## What it isn't

- **Not** an IDE plugin. Agent hosts (Claude Code etc.) bring the MCP
  client side; this artefact is the server.
- **Not** part of Story's authoring runtime. It reads from
  `re-frame.story`'s public query API and dispatches to its public
  runtime fns; nothing here registers new framework primitives.
- **Not** reachable from production CLJS bundles. Per
  [`tools/README.md`](../README.md) the dependency arrow flows
  tool → implementation; this jar is on a separate classpath root.

## Quick start

```bash
# Run the server (stdio transport). The agent host typically invokes
# this for you; you rarely run it by hand.
cd tools/story-mcp
clojure -M -m re-frame.story-mcp.server

# Open the write surface (defaults to off):
clojure -M -m re-frame.story-mcp.server --allow-writes
# or
RF_STORY_MCP_ALLOW_WRITES=true clojure -M -m re-frame.story-mcp.server
# or
clojure -J-Drf.story-mcp.allow-writes=true -M -m re-frame.story-mcp.server
```

Tests:

```bash
cd tools/story-mcp
clojure -M:test
```

## Where the depth lives

Per the per-tool spec-folder convention in
[`tools/README.md`](../README.md), the substantive contract for
this jar is decomposed into [`spec/`](./spec/):

| File | Covers |
|---|---|
| [`spec/000-Vision.md`](./spec/000-Vision.md) | What this jar is, why it's separate from Story. |
| [`spec/001-Wire-Protocol.md`](./spec/001-Wire-Protocol.md) | JSON-RPC 2.0 over stdio; `initialize`; `tools/list`; `tools/call`; protocol-version pin. |
| [`spec/002-Tool-Registry.md`](./spec/002-Tool-Registry.md) | The 16 tools across Dev / Docs / Testing / Write categories. |
| [`spec/003-Write-Surface-Gating.md`](./spec/003-Write-Surface-Gating.md) | The `allow-writes?` config; what's gated; how the gate fails. |
| [`spec/API.md`](./spec/API.md) | Consolidated tool surface (each tool's input/output schemas). |
| [`spec/DESIGN-RATIONALE.md`](./spec/DESIGN-RATIONALE.md) | Why Cheshire over data.json; why stage-marker is independent; why protocol-version pinned. |

The four categories, at a glance:

- **Dev** — `get-story-instructions`, `preview-variant`, `list-substrates`.
- **Docs** — `list-stories`, `get-story`, `get-variant`, `list-tags`,
  `list-modes`, `list-assertions`, `variant->edn`.
- **Testing** — `run-variant`, `snapshot-identity`, `run-a11y`,
  `read-failures`.
- **Write** (gated) — `register-variant`, `unregister-variant`.

## File layout

```
tools/story-mcp/
├── deps.edn                                      ; coord day8/re-frame2-story-mcp
├── README.md                                     ; this file
├── spec/                                         ; the contract; see above
└── src/re_frame/story_mcp/
    ├── config.cljc                               ; protocol-version + allow-writes? gate
    ├── protocol.cljc                             ; JSON-RPC envelope + frame I/O
    ├── server.cljc                               ; dispatcher + -main + run-loop
    └── tools/                                    ; tool implementations (rf2-3ukix split)
        ├── cap.cljc                              ; invoke-tool dispatcher + token-cap egress
        ├── registry.cljc                         ; tool-registry + descriptors + by-name
        ├── helpers.cljc                          ; result builders, args coercers, scrubbers
        ├── schemas.cljc                          ; recurring JSON-schema fragments
        ├── dev.cljc                              ; get-story-instructions, preview-variant, list-substrates
        ├── docs.cljc                             ; list-stories, get-story, get-variant, list-tags, list-modes, list-assertions, variant->edn
        ├── testing.cljc                          ; run-variant, snapshot-identity, run-a11y, read-failures
        ├── write.cljc                            ; gated: register-variant, unregister-variant
        └── recorder.cljc                         ; gated: record-as-variant
└── test/re_frame/story_mcp/
    ├── protocol_test.clj                         ; wire-format coverage
    └── tools_test.clj                            ; per-tool semantics + dispatcher + run-loop
```

## See also

- [`spec/`](./spec/) — this jar's contract.
- [`tools/story/spec/006-MCP-Surface.md`](../story/spec/006-MCP-Surface.md) —
  Story's side of the boundary.
- [`tools/README.md`](../README.md) — per-tool jar convention + bundle
  isolation.
- [`spec/007-Stories.md`](../../spec/007-Stories.md) — the spec the
  Story runtime implements (this jar is one of its consumers).
- [`spec/Tool-Pair.md`](../../spec/Tool-Pair.md) — the runtime contract
  for pair-shaped AI tools (Story-MCP follows it implicitly via the
  Story public surface).
