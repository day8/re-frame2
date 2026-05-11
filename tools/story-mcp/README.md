# tools/story-mcp/

`day8/re-frame2-story-mcp` — the **MCP (Model Context Protocol) agent
surface** for re-frame2-story. Lands as Stage 7 of the Story epic
(`rf2-tgci`); design contract is [`tools/story/IMPL-SPEC.md` §7](../story/IMPL-SPEC.md).

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

# Open the write surface (defaults to off — IMPL-SPEC §7.3):
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

## Tool registry

The tool set splits into four categories per IMPL-SPEC §7.2 + §7.3.

### Dev — for agents helping build new stories

| Tool                         | Semantics                                                                                       |
|------------------------------|-------------------------------------------------------------------------------------------------|
| `get-story-instructions`     | Return Story's authoring conventions (the seven `reg-*` macros, hard rules, lifecycle).         |
| `preview-variant`            | Given `:variant-id`, run the canvas pipeline and return state + assertions + a sharable URL.    |
| `list-substrates`            | The substrates registered via `register-substrate!` (Reagent canonical; UIx / Helix opt-in).    |

### Docs — for agents reading the story library

| Tool                | Semantics                                                                |
|---------------------|--------------------------------------------------------------------------|
| `list-stories`      | All registered stories (optional `:tags` filter).                        |
| `get-story`         | One story's full body + its variant ids.                                 |
| `get-variant`       | One variant's resolved EDN body.                                         |
| `list-tags`         | Canonical tags + project-custom tags.                                    |
| `list-modes`        | Registered modes (Chromatic-style saved arg tuples).                     |
| `list-assertions`   | The seven canonical `:rf.assert/*` vocab + arity docs.                   |
| `variant->edn`      | Round-trippable EDN of a variant (text-only result for byte stability).  |

### Testing — for agents running stories headlessly

| Tool                | Semantics                                                                              |
|---------------------|----------------------------------------------------------------------------------------|
| `run-variant`       | Execute the four-phase lifecycle; return result map (`:frame :app-db :assertions ...`). |
| `snapshot-identity` | Content-hash of (variant × args × decorators × loaders × substrate × modes).           |
| `run-a11y`          | Read axe-core violations for a variant (CLJS panel writes; this tool reads).           |
| `read-failures`     | Recent assertion failures accumulated against the variant frame.                       |

### Write — v1.1, dev-only, gated

| Tool                  | Semantics                                                                              |
|-----------------------|----------------------------------------------------------------------------------------|
| `register-variant`    | Programmatically register a variant. **Gated** behind `--allow-writes` (default off).  |
| `unregister-variant`  | Symmetric to `register-variant`. Same gate.                                            |

The write gate exists because an agent that can register variants on
demand activates the self-healing loop (write story → run → read
failures → fix). That power must be opt-in, not default; CI runs
should always leave it closed.

## Protocol

- Transport: **stdio**, newline-delimited JSON-RPC 2.0 per [MCP
  2025-06-18 §Transports](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports).
- Capabilities advertised: `tools` (with `listChanged: false`).
- Methods handled: `initialize`, `tools/list`, `tools/call`, `ping`,
  `shutdown`, `notifications/initialized` (silent accept).
- Unknown methods → `-32601 method-not-found`.
- Malformed JSON → `-32700 parse-error` with `id: null` (the loop
  survives and continues reading).

## File layout

```
tools/story-mcp/
├── deps.edn                                      ; coord day8/re-frame2-story-mcp
├── README.md                                     ; this file
└── src/re_frame/story_mcp/
    ├── config.cljc                               ; protocol-version + allow-writes? gate
    ├── protocol.cljc                             ; JSON-RPC envelope + frame I/O
    ├── tools.cljc                                ; 16 tool implementations + registry
    └── server.cljc                               ; dispatcher + -main + run-loop
└── test/re_frame/story_mcp/
    ├── protocol_test.clj                         ; wire-format coverage
    └── tools_test.clj                            ; per-tool semantics + dispatcher + run-loop
```

## See also

- [`tools/story/IMPL-SPEC.md`](../story/IMPL-SPEC.md) §7 — the contract.
- [`tools/README.md`](../README.md) — per-tool jar convention + bundle isolation.
- [`spec/007-Stories.md`](../../spec/007-Stories.md) — the spec the
  Story runtime implements (this jar is one of its consumers).
- [`spec/Tool-Pair.md`](../../spec/Tool-Pair.md) — the runtime contract
  for pair-shaped AI tools (Story-MCP follows it implicitly via the
  Story public surface).
