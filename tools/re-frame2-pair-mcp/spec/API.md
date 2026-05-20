# API

> Implements the [Tool-Pair contract](../../../spec/Tool-Pair.md) —
> the framework-side contract for pair-shaped AI tools that re-frame2-pair-mcp
> exposes over MCP/stdio.

The consolidated user-facing surface. One-stop reference for
installing, configuring, launching, and calling re-frame2-pair-mcp.

This doc is a **reference**; the normative contracts live in the
per-area specs (000–003). Where the two drift, the per-area spec
wins.

## Installation

### npm — global install

```bash
npm install -g @day8/re-frame2-pair-mcp
```

Provides the `re-frame2-pair-mcp` binary on `$PATH`.

### npm — one-off via npx

```bash
npx @day8/re-frame2-pair-mcp
```

No persistent install; useful for trying the server out and for
CI configurations.

### Project-local install

```bash
npm install --save-dev @day8/re-frame2-pair-mcp
```

The binary lands under `node_modules/.bin/re-frame2-pair-mcp`; the
agent host's `command` slot points there.

## Configuration

### Claude Code

Add to `~/.claude/settings.json` (or per-project
`.claude/settings.json`):

```json
{
  "mcpServers": {
    "re-frame2-pair": {
      "command": "re-frame2-pair-mcp",
      "env": {
        "SHADOW_CLJS_BUILD_ID": "app"
      }
    }
  }
}
```

### Cursor / Copilot / other MCP-capable hosts

Same `command` + `env` shape, registered through the host's MCP
configuration mechanism. The stdio JSON-RPC framing is host-agnostic
per the [MCP transport spec](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports).

### Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `SHADOW_CLJS_BUILD_ID` | `"app"` | Default build id passed to `cljs-eval`. Overridable per-op via the `build` argument. |
| `SHADOW_CLJS_NREPL_PORT` | (unset) | Explicit nREPL port; takes precedence over port-file discovery. |
| `RE_FRAME2_PAIR_MCP_MAX_STREAMS` | `10` | Max concurrent open streaming subscriptions per session (rf2-3ijbl). |
| `RE_FRAME2_PAIR_MCP_MAX_EVENTS_PER_SEC` | `100` | Per-session rate-limit on progress-notification ticks (rf2-3ijbl). |
| `RE_FRAME2_PAIR_MCP_ABUSE_OVERFLOW_THRESHOLD` | `50` | Overflow events over the rolling window beyond which a stream is terminated for abuse (rf2-3ijbl). |
| `RE_FRAME2_PAIR_MCP_ABUSE_WINDOW_MS` | `10000` | Rolling-window length for abuse detection, in milliseconds (rf2-3ijbl). |

### Launch flags

| Flag | Default | Purpose |
|---|---|---|
| `--allow-eval`            | OFF | Enable the `eval-cljs` tool (rf2-cxx5s). Without the flag, `eval-cljs` calls return `{:ok? false :reason :rf.error/eval-cljs-disabled}` without touching the nREPL socket. |
| `--allow-sensitive-reads` | OFF | Honour caller-supplied `:include-sensitive true` and `:elision false` on direct-read tools (`snapshot`, `get-path`, `subscribe`, `trace-window`, `watch-epochs`), and ship verbatim payloads through the preload's `app-db-reset!` `tap>` emission. Without the flag, sensitive slots redact and large slots elide before any payload crosses the wire — and the `tap>` payloads route through `re-frame.core/elide-wire-value` before any registered tap consumer sees them (rf2-c2dtu). Canonical cross-MCP flag name shared with story-mcp (rf2-2x3ql). |
| `--max-concurrent-streams=N` | `10` | Resource control: cap concurrent open streaming subscriptions per session (rf2-3ijbl). CLI value wins over the matching env var. |
| `--max-events-per-sec=N` | `100` | Resource control: token-bucket rate-limit on progress-notification ticks emitted across all open streams (rf2-3ijbl). Excess ticks tally as `:rate-dropped` on the final summary. |
| `--abuse-overflow-threshold=N` | `50` | Resource control: rolling-window overflow count beyond which the offending stream terminates with `:reason :rf.error/stream-abuse-detected` (rf2-3ijbl). |
| `--abuse-window-ms=N` | `10000` | Resource control: abuse-detection window length in milliseconds (rf2-3ijbl). |

Boolean flags + integer caps pass after the binary name:

```json
{
  "mcpServers": {
    "re-frame2-pair": {
      "command": "re-frame2-pair-mcp",
      "args": ["--allow-eval", "--allow-sensitive-reads",
               "--max-concurrent-streams=20",
               "--max-events-per-sec=200"]
    }
  }
}
```

The normative contract for both gates lives in
[`003-Tool-Catalogue.md` §Universal: server launch flags](./003-Tool-Catalogue.md#universal-server-launch-flags);
the resource-control caps live in
[`003-Tool-Catalogue.md` §Universal: server resource controls](./003-Tool-Catalogue.md#universal-server-resource-controls-streaming-surfaces).

### nREPL port discovery

The server walks the following sources in order:

1. `$SHADOW_CLJS_NREPL_PORT` env var.
2. `target/shadow-cljs/nrepl.port` (shadow-cljs's standard location).
3. `.shadow-cljs/nrepl.port`.
4. `.nrepl-port` (generic nREPL convention).

If none resolve, the server boots in degraded mode (per
[`001-Wire-Protocol.md`](./001-Wire-Protocol.md) §Degraded boot) and
returns a structured error on every `tools/call` until the port
becomes resolvable. No restart needed once shadow-cljs comes up.

## Launch

### As an MCP subprocess (the usual path)

The agent host launches the server when it needs to call a re-frame2-pair
tool. No manual start.

### Standalone (for debugging / smoke tests)

```bash
re-frame2-pair-mcp
```

Reads JSON-RPC messages on stdin; writes responses on stdout. Type
`Ctrl+D` (EOF) to close.

### From source

```bash
git clone https://github.com/day8/re-frame2.git
cd re-frame2/tools/re-frame2-pair-mcp
npm install
npm run build      # → out/server.js
node out/server.js
```

## Tool surface

The fourteen tools, in the order a typical session uses them. Argument
schemas and result shapes are specified in
[`003-Tool-Catalogue.md`](./003-Tool-Catalogue.md).

| Tool | Purpose |
|---|---|
| `discover-app` | Health-check the runtime; verify the shadow-cljs `:preloads` entry landed. Run first every session. |
| `eval-cljs` | Evaluate a CLJS form; returns the EDN value. |
| `dispatch` | Fire a re-frame event with `:origin :pair`. Modes: queued, sync, trace. |
| `trace-window` | Return epoch records from the last N ms. |
| `watch-epochs` | Pull-mode poll for matching epochs since a given id. |
| `tail-build` | Wait for a hot-reload to land by polling a probe form. |
| `snapshot`   | Coarse-grained per-frame state read in one round-trip. Returns `:app-db` + `:sub-cache` + `:machines` + `:epochs` + `:traces` slices for every (or a subset of) frame(s). Mega-op for investigate-X workflows. |
| `get-path` | Direct slice read at a path inside `app-db`. The deep-read peer of `snapshot`; agents drill in once a `:rf.mcp/summary` or elision marker names the path of interest. |
| `subscribe` | Streaming subscription on the trace / epoch bus (rf2-hq49). Push-mode replacement for `watch-epochs`; each matching event arrives as a `notifications/progress` notification. Topics: `trace`, `epoch`, `fx`, `error`. |
| `unsubscribe` | Close a streaming subscription out-of-band. Idempotent. |
| `list-subscriptions` | Diagnostic peer for `subscribe` / `unsubscribe` (rf2-zjz9q; renamed from `subscription-info` per rf2-4y595) — "what streams are open?" snapshot of the active subscription set. |
| `handler-meta` | Return the registration-metadata map for a registered handler — `:source-coord`, `:doc`, `:tags`, and any custom slots from the reg-`*` macro. Supported kinds: event, sub, fx, cofx, view, frame, machine. Answer "where is `:user/login` defined?" without an `eval-cljs` round-trip (rf2-cibp8). |
| `list-handlers` | Discovery peer of `handler-meta` — return every registered id under a kind. Sorted, stable shape. Same seven supported kinds (rf2-pctf8; renamed from `registry-list` per rf2-4y595). |
| `get-re-frame2-pair-instructions` | Returns the agent-onboarding text — how re-frame2-pair connects, how `:origin :pair` works, the canonical workflow per dispatch / eval / snapshot. Read once at session start (rf2-fnpqg). |

(Pre-rf2-7dvg drops also exposed `inject-runtime`. That tool is gone:
the runtime ships into consumer apps via shadow-cljs `:devtools
:preloads` now. See the skill's SKILL.md §Setup.)

Each tool's JSONSchema is surfaced via `tools/list` per the MCP
spec.

## Mode flags (dispatch)

The `dispatch` tool has three modes selected by the `sync` / `trace`
flags:

| `sync` | `trace` | Mode | Runtime call |
|---|---|---|---|
| `false` | `false` | queued | `rf/dispatch` |
| `true` | `false` | sync | `rf/dispatch-sync` |
| any | `true` | trace | sync + returns `:rf/epoch-record` |

The trace mode is the workhorse for agent loops: dispatch, see what
fired, decide next step. See
[`003-Tool-Catalogue.md`](./003-Tool-Catalogue.md) § `dispatch`.

## Result shape

All tools return EDN inside the MCP `tools/call` `content` text
slot. The canonical shape:

```clojure
{:ok?    true | false
 :value  <op-specific result, when :ok? true>
 :reason <keyword, when :ok? false>
 :message "..." (optional human-readable elaboration)
 ...op-specific keys}
```

Tool-execution failures (a known tool returning `:ok? false`) use
`isError: true` per the MCP spec's error-handling guidance; they are
NOT protocol-level errors. Protocol-level errors (bad JSON, unknown
method) use JSON-RPC error codes — see
[`001-Wire-Protocol.md`](./001-Wire-Protocol.md) § JSON-RPC error
codes.

## First call (smoke test)

After installing the server and configuring Claude Code:

```text
Agent: tools/call discover-app {}
Server: {:ok? true
         :debug-enabled? true
         :frames [:app/main]
         :coord-annotation-enabled? true
         :build-id "app"}
```

If the health summary comes back `:ok? true`, the connection is
live. Subsequent tool calls reuse the same socket.

## Skill-driven calls

With the re-frame2-pair skill loaded (see
[`../../../skills/re-frame2-pair/SKILL.md`](../../../skills/re-frame2-pair/SKILL.md)),
agents describe the task in plain language and the skill's
`SKILL.md` teaches the model which tool to call. The MCP server is
the transport; the skill is the playbook.

## Runtime surfaces consumed (not exposed)

re-frame2-pair-mcp **consumes** the framework + runtime; it does not expose
analogues. Listed here for reference:

| Surface | Source | What re-frame2-pair-mcp reads / writes |
|---|---|---|
| `js/globalThis.__re_frame2_pair_runtime` | preload/re_frame2_pair/runtime.cljs | Load-time marker probed by `ensure-runtime!`. |
| `re-frame2-pair.runtime/session-id` | preload/re_frame2_pair/runtime.cljs | Per-session UUID; mirrored on the global marker. |
| `re-frame2-pair.runtime/dispatch!` | preload/re_frame2_pair/runtime.cljs | Queued / sync / trace dispatch. |
| `re-frame2-pair.runtime/trace-window` | preload/re_frame2_pair/runtime.cljs | Last-N-ms epoch lookback. |
| `re-frame2-pair.runtime/watch-epochs` | preload/re_frame2_pair/runtime.cljs | Poll for epochs after id. |
| `re-frame2-pair.runtime/probe` | preload/re_frame2_pair/runtime.cljs | Hot-reload landed signal. |
| `re-frame2-pair.runtime/snapshot-state` | preload/re_frame2_pair/runtime.cljs | Per-frame slice composer fed by `:include` / `:frames` opts; backs the `snapshot` MCP tool. |
| `re-frame2-pair.runtime/subscribe!` / `drain-subscription!` / `unsubscribe!` | preload/re_frame2_pair/runtime.cljs | Per-subscription filtered queue on the trace + epoch bus; backs the `subscribe` MCP tool (rf2-hq49). |
| `shadow.cljs.devtools.api/cljs-eval` | shadow-cljs | The CLJS bridge over the JVM-side nREPL socket. |
| `:rf/epoch-record` | framework | The epoch record shape returned by trace mode. |
| `:origin :pair` (in event tags) | framework | Pair2's dispatches surface in the trace stream distinguishably. |

## Back-compat: the bash shims

The bash shims under `skills/re-frame2-pair/scripts/` continue to
work; they are not slated for removal. The op vocabulary is
identical between the two surfaces.

| Bash shim | MCP tool |
|---|---|
| `discover-app.sh` | `discover-app` |
| `eval-cljs.sh` | `eval-cljs` |
| `dispatch.sh` | `dispatch` |
| `trace-window.sh` | `trace-window` |
| `watch-epochs.sh` | `watch-epochs` |
| `tail-build.sh` | `tail-build` |
| _(none — MCP-only)_ | `snapshot` |
| _(none — MCP-only)_ | `subscribe` / `unsubscribe` |

The `snapshot` mega-op has no bash equivalent — it's a coarse-grained
composition of the existing per-slice runtime readers, shipped as
part of the rf2-x70e drop to cut round-trips for investigate-X
workflows. Agents that need its semantics under the bash shim chain
the per-op reads (`app-db/snapshot` + `subs/cache` + `machines/list`
+ `epoch/history` + `trace/buffer`) in sequence.

Agents may mix shim calls and MCP tool calls in the same workflow
during the transition. New sessions should prefer the MCP server for
latency reasons (see
[`Principles.md`](./Principles.md) § Single persistent nREPL socket).

## Versioning

`@day8/re-frame2-pair-mcp` follows semver. Major changes break the
tool surface (an op removed or its arg shape changed). Minor adds an
op or expands an optional arg. Patch fixes a bug without changing
the contract.

The framework runtime dep (`re-frame2-pair.runtime`) is the
companion artefact; their majors track together when the op
vocabulary changes.

## What this doesn't expose

- **No new framework primitives.** No new registries, no new
  dispatch types, no new effect substrates. The fourteen ops route
  through existing `re-frame2-pair.runtime` surfaces. See
  [`Principles.md`](./Principles.md) § Tool consumes the framework.
- **No remote-attach protocol.** re-frame2-pair-mcp is stdio-only; the agent
  host owns the network plumbing. A custom WebSocket protocol was
  considered and rejected (per
  [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md) § Lock #2).
- **No "private" surfaces.** All public ops are listed in
  [`003-Tool-Catalogue.md`](./003-Tool-Catalogue.md); there are no
  hidden tools, no internal-only endpoints.
- **No raw streaming transport.** MCP isn't a streaming protocol per
  se. The streaming-shaped tools (`subscribe`, `unsubscribe`, rf2-hq49)
  layer over MCP's `notifications/progress` mechanism: the server polls
  the runtime's drain at `poll-ms` and emits one progress notification
  per non-empty batch. The poll cadence is well below the agent loop's
  perceptual threshold; the `tools/call` stays open for the lifetime
  of the subscription and resolves on cancel / `unsubscribe` /
  caller-supplied caps.
