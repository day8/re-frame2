# tools/pair2-mcp/

`@day8/re-frame-pair2-mcp` — the **MCP (Model Context Protocol) server**
that pair-programs with a live re-frame2 application over a persistent
nREPL connection.

This is the structural successor to the bash-shim → babashka → nREPL
chain under `skills/re-frame-pair2/scripts/`. The shims still ship for
back-compat, but new sessions should prefer the MCP server.

## What it is

A Node-based stdio JSON-RPC server (written in ClojureScript, compiled
via shadow-cljs to a single `.js` file) that exposes the nine pair2
ops as MCP tools. AI agents (Claude Code, Cursor, Copilot) launch it
as a subprocess; one persistent nREPL socket is held for the lifetime
of the session.

Per-op latency drops from ~700ms (bash startup + babashka startup +
fresh nREPL connect per call) to ~5–50ms (one bencode round-trip on
the open socket). The first-connect inject step is also gone (rf2-7dvg):
the runtime ships into the consumer app via shadow-cljs `:preloads`,
so `discover-app` is just a marker probe rather than a hundreds-of-ms
cljs-eval compile.

## Tool surface

| MCP tool       | Bash-shim equivalent      | What it does |
|----------------|---------------------------|--------------|
| `discover-app` | `discover-app.sh`         | Verify shadow-cljs nREPL is reachable, probe the preloaded pair2 runtime marker, return a health summary. Run first every session. |
| `eval-cljs`    | `eval-cljs.sh`            | Evaluate a CLJS form via shadow-cljs's `cljs-eval`. Returns the EDN value. |
| `dispatch`     | `dispatch.sh`             | Fire a re-frame2 event with `:origin :pair`. Modes: queued, sync, trace. Frame and fx-overrides supported. |
| `trace-window` | `trace-window.sh`         | Return the epochs that landed in the last N ms. |
| `watch-epochs` | `watch-epochs.sh`         | Pull-mode poll for matching epochs added after a given epoch-id. Predicate keys: `:event-id`, `:event-id-prefix`, `:effects`, `:touches-path`, `:sub-ran`, `:render`, `:origin`, `:frame`. |
| `tail-build`   | `tail-build.sh`           | Wait for a hot-reload to land by polling a probe form until its value changes. |
| `snapshot`     | _(new — no bash equivalent)_ | Coarse-grained per-frame state read in one round-trip. Returns a map keyed by frame-id with `:app-db`, `:sub-cache`, `:machines`, `:epochs`, `:traces` slices. Prefer for investigate-X workflows over chaining 5-10 individual reads. The `:app-db` slice defaults to a tree-summary marker (rf2-tygdv); drill down with `path`. |
| `get-path`     | _(new — no bash equivalent)_ | Read a single value at `path` from a frame's app-db (rf2-tygdv). Minimal targeted-read primitive; server-side `get-in` so only the addressed subtree crosses the wire. Distinguishes a path that points at `nil` from a path that doesn't resolve, and attaches `deepest-valid-prefix` on misses so the agent can re-aim. |
| `subscribe`    | _(new — no bash equivalent)_ | Streaming subscription on the trace / epoch bus (rf2-hq49). Push-mode replacement for `watch-epochs`; each matching event arrives as a `notifications/progress` notification. Topics: `trace`, `epoch`, `fx`, `error`. |
| `unsubscribe`  | _(new — no bash equivalent)_ | Close a streaming subscription out-of-band. Idempotent — closing an unknown sub-id returns `:existed? false` rather than an error. |

## Quick start

### Install

```bash
npm install -g @day8/re-frame-pair2-mcp
```

(or use `npx @day8/re-frame-pair2-mcp` for one-off runs).

### Configure Claude Code

Add to your `~/.claude/settings.json` (or per-project `.claude/settings.json`):

```json
{
  "mcpServers": {
    "re-frame-pair2": {
      "command": "re-frame-pair2-mcp",
      "env": {
        "SHADOW_CLJS_BUILD_ID": "app"
      }
    }
  }
}
```

The server auto-discovers the nREPL port from (in order):

1. `$SHADOW_CLJS_NREPL_PORT` env var
2. `target/shadow-cljs/nrepl.port`
3. `.shadow-cljs/nrepl.port`
4. `.nrepl-port`

### First call

```text
Agent: tools/call eval-cljs {form: "@(rf/subscribe [:user/email])"}
Server: {:ok? true :value "alice@example.com"}
```

Or, with the pair2 skill loaded, just describe the task — the skill's
SKILL.md teaches the agent which tool to call. See
[`../../skills/re-frame-pair2/SKILL.md`](../../skills/re-frame-pair2/SKILL.md).

### Snapshot — one call instead of five

For investigate-X workflows (post-mortems, "what state is the app in
right now?", "what changed between these two epochs?"):

```text
Agent: tools/call snapshot {frames: "all"}
Server: {:ok? true
         :frames :all
         :include [:app-db :sub-cache :machines :epochs :traces]
         :snapshot {:rf/default {:app-db {...}
                                 :sub-cache {[:cart/total] {:value 42 ...}}
                                 :machines {:ids [:auth] :state {:auth {...}}}
                                 :epochs [{:epoch-id "..." ...} ...]
                                 :traces [{:operation :event/dispatched ...}]}}}
```

Subset what you need with `include`:

```text
Agent: tools/call snapshot {frames: ["rf/default"], include: ["app-db", "epochs"]}
```

Per-op reads (`eval-cljs` against `runtime/app-db-at`, etc.) remain
available — they're still the right call when you genuinely need one
slice for one frame. `snapshot` is the right surface when you don't
yet know which slice carries the answer.

## How preload probing works (rf2-7dvg)

A full page reload in the browser destroys the CLJS runtime but
leaves the nREPL socket on the JVM side intact. shadow-cljs re-runs
the consumer's `:preloads` as part of the next bundle load, so the
`re-frame-pair2.runtime` namespace reappears in the new realm
together with the load-time marker at
`js/globalThis.__re_frame_pair2_runtime`.

Every tool that needs the runtime calls `ensure-runtime!` first; the
probe is one bencode round-trip on the persistent socket. Missing
marker → structured `:reason :runtime-not-preloaded` error with the
setup hint. There is no cljs-eval inject fallback (rf2-7dvg cut it
for pre-alpha simplicity).

## Spec

The contract lives in [`spec/`](./spec/):

| File | Covers |
|------|--------|
| [`spec/000-Vision.md`](./spec/000-Vision.md) | What this server is, why it replaces the bash-shim chain. |
| [`spec/001-Wire-Protocol.md`](./spec/001-Wire-Protocol.md) | JSON-RPC 2.0 over stdio; lifecycle; tool dispatch. |
| [`spec/002-nREPL-Transport.md`](./spec/002-nREPL-Transport.md) | Persistent socket, bencode framing, sentinel-based reconnect. |
| [`spec/003-Tool-Catalogue.md`](./spec/003-Tool-Catalogue.md) | The nine tools (seven per-op + the `snapshot` mega-op + the streaming `subscribe` / `unsubscribe` pair), their argument schemas, EDN result shape. |

## Development

```bash
# Install deps
npm install

# Compile production build
npm run build      # → out/server.js

# Watch mode
npm run watch

# Unit tests (cljs)
npm test

# Stdio integration test (no nREPL needed — exercises the degraded path)
npm run stdio-roundtrip

# Live-nREPL integration test (requires an nREPL running on $NREPL_TEST_PORT)
NREPL_TEST_PORT=17777 node test/live-nrepl.js
```

## Implementation language

ClojureScript compiled via shadow-cljs to a `:node-script` target.
End users install Node only; the compiled output is plain JS. The
language pick is locked — see the bead notes on the implementing PR.

`pilot/` contains the original toolchain pilot — a minimal MCP server
with two tools (`ping` and `nrepl-ping`) used to verify the
shadow-cljs + npm MCP SDK + bencode round-trip worked before the full
port. Kept for reference and as a stripped-down smoke harness.

## File layout

```
tools/pair2-mcp/
├── README.md                                 ; this file
├── package.json                              ; npm package
├── shadow-cljs.edn                           ; build config
├── spec/                                     ; contract
├── pilot/                                    ; pre-port toolchain pilot
└── src/re_frame_pair2_mcp/
    ├── nrepl.cljs                            ; persistent socket + bencode
    ├── tools.cljs                            ; the 9 MCP tools (7 per-op + snapshot mega-op + subscribe/unsubscribe streaming pair)
    └── server.cljs                           ; stdio JSON-RPC entry point
└── test/
    ├── re_frame_pair2_mcp/nrepl_test.cljs    ; bencode framing unit tests
    ├── stdio-roundtrip.js                    ; stdio integration test
    └── live-nrepl.js                         ; live-nREPL integration test
```

## Back-compat with the bash shims

The shims under `skills/re-frame-pair2/scripts/` still work and are
not slated for removal in this drop. Their headers carry a
deprecation notice pointing here. Migration is opt-in per session;
agents can mix shim calls and MCP tool calls in the same workflow
during the transition.
