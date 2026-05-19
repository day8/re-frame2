# tools/re-frame2-pair-mcp/

`@day8/re-frame2-pair-mcp` — the **MCP (Model Context Protocol) server**
that pair-programs with a live re-frame2 application over a persistent
nREPL connection.

This is the structural successor to the bash-shim → babashka → nREPL
chain under `skills/re-frame2-pair/scripts/`. The shims still ship for
back-compat, but new sessions should prefer the MCP server.

## What it is

A Node-based stdio JSON-RPC server (written in ClojureScript, compiled
via shadow-cljs to a single `.js` file) that exposes the fourteen re-frame2-pair
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
| `discover-app` | `discover-app.sh`         | Verify shadow-cljs nREPL is reachable, probe the preloaded re-frame2-pair runtime marker, return a health summary. Run first every session. |
| `eval-cljs`    | `eval-cljs.sh`            | Evaluate a CLJS form via shadow-cljs's `cljs-eval`. Returns the EDN value. |
| `dispatch`     | `dispatch.sh`             | Fire a re-frame2 event with `:origin :pair`. Modes: queued, sync, trace. Frame and fx-overrides supported. |
| `trace-window` | `trace-window.sh`         | Return the epochs that landed in the last N ms. Cursor-paginated (`:limit` / `:cursor`, default limit 50). |
| `watch-epochs` | `watch-epochs.sh`         | Pull-mode poll for matching epochs added after a given epoch-id. Predicate keys: `:event-id`, `:event-id-prefix`, `:effects`, `:touches-path`, `:sub-ran`, `:render`, `:origin`, `:frame`, `:timing-ms` (number or `">N"` / `">=N"` / `"<N"` / `"<=N"` / `"=N"` — server-side wall-clock filter, rf2-r3azh). Cursor-paginated (`:limit` / `:cursor`, default limit 50). |
| `tail-build`   | `tail-build.sh`           | Wait for a hot-reload to land by polling a probe form until its value changes. |
| `snapshot`     | _(new — no bash equivalent)_ | Coarse-grained per-frame state read in one round-trip. Returns a map keyed by frame-id with `:app-db`, `:sub-cache`, `:machines`, `:epochs`, `:traces` slices. Prefer for investigate-X workflows over chaining 5-10 individual reads. The `:app-db` slice defaults to a tree-summary marker (rf2-tygdv); drill down with `path`. |
| `get-path`     | _(new — no bash equivalent)_ | Read a single value at `path` from a frame's app-db (rf2-tygdv). Minimal targeted-read primitive; server-side `get-in` so only the addressed subtree crosses the wire. Distinguishes a path that points at `nil` from a path that doesn't resolve, and attaches `deepest-valid-prefix` on misses so the agent can re-aim. |
| `subscribe`    | _(new — no bash equivalent)_ | Streaming subscription on the trace / epoch bus (rf2-hq49). Push-mode replacement for `watch-epochs`; each matching event arrives as a `notifications/progress` notification. Topics: `trace`, `epoch`, `fx`, `error`. |
| `unsubscribe`  | _(new — no bash equivalent)_ | Close a streaming subscription out-of-band. Idempotent — closing an unknown sub-id returns `:existed? false` rather than an error. |
| `list-subscriptions` | _(new — no bash equivalent)_ | List active streaming subscriptions with per-sub queue depth, drop counts, and `:overflow-reason` (rf2-zjz9q; renamed from `subscription-info` per rf2-4y595). Diagnostic for "what streams are open?" / "is my probe still alive?" — wraps the runtime fn directly so AI clients don't need an `eval-cljs` round-trip. Optional `topic` / `sub-id` filters. |
| `get-re-frame2-pair-instructions` | _(new — no bash equivalent)_ | Return the agent-onboarding prose for re-frame2-pair-mcp (rf2-fnpqg): tool catalogue, EDN posture, tagged-mutation conventions, streaming subscribe semantics, wire-boundary pipeline. Inline text, no nREPL round-trip — call at session start to orient. Mirrors story-mcp's `get-story-instructions`. |

## Quick start

### Install

```bash
npm install -g @day8/re-frame2-pair-mcp
```

(or use `npx @day8/re-frame2-pair-mcp` for one-off runs).

### Configure Claude Code

Add to your `~/.claude/settings.json` (or per-project `.claude/settings.json`):

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

The server auto-discovers the nREPL port from (in order):

1. `$SHADOW_CLJS_NREPL_PORT` env var
2. `target/shadow-cljs/nrepl.port`
3. `.shadow-cljs/nrepl.port`
4. `.nrepl-port`

### Launch flags

| Flag                  | Default | What it does                                                                         |
|-----------------------|---------|--------------------------------------------------------------------------------------|
| `--allow-eval`        | OFF     | Enable the `eval-cljs` tool. Default-OFF gate (rf2-cxx5s); see "eval-cljs gate" below. |
| `--allow-raw-state`   | OFF     | Honour caller-supplied `:include-sensitive true` and `:elision false` on direct-read tools (`snapshot` / `get-path` / `subscribe` / `trace-window` / `watch-epochs`). Default-OFF gate (rf2-c2dtu); see "raw-state gate" below. |

#### eval-cljs gate (rf2-cxx5s)

`eval-cljs` executes arbitrary CLJS / Clojure source against the live
runtime — qualitatively different authority from the named-mutation
tools (`dispatch`, etc.) which mirror in-panel affordances. Published
builds ship the tool DISABLED; the operator opts in at server launch:

```json
{
  "mcpServers": {
    "re-frame2-pair": {
      "command": "re-frame2-pair-mcp",
      "args": ["--allow-eval"]
    }
  }
}
```

Without the flag, calls to `eval-cljs` return the structured error
`{:ok? false :reason :rf.error/eval-cljs-disabled ...}` without
touching the nREPL socket. Same posture as causa-mcp's split (rf2-zyoj2).

#### raw-state gate (rf2-c2dtu)

The direct-read surfaces (`snapshot`, `get-path`, `subscribe`,
`trace-window`, `watch-epochs`) can return verbatim slices of a live
app's state. Spec 009 §Privacy mandates default-suppression: sensitive
slots redact and large slots elide before any payload crosses the
LLM-facing wire. Published builds ship with the gate **OFF**:

- A caller's `:include-sensitive true` is overridden to `false`.
- A caller's `:elision false` is overridden to `true`.
- The preload runtime's `app-db-reset!` taps default-elide both
  `:previous` and `:next` payloads through `re-frame.core/elide-wire-value`
  before any tap consumer sees them.

Operators who need raw state for offline debug opt in at server launch:

```json
{
  "mcpServers": {
    "re-frame2-pair": {
      "command": "re-frame2-pair-mcp",
      "args": ["--allow-raw-state"]
    }
  }
}
```

With the flag, the per-call args win again — `:include-sensitive true`
and `:elision false` ride through to the walker unchanged. Same
architecture as `--allow-eval` (rf2-zyoj2) and story-mcp's in-flight
`--allow-sensitive-reads` (rf2-uaymx).

### First call

```text
Agent: tools/call eval-cljs {form: "@(rf/subscribe [:user/email])"}
Server: {:ok? true :value "alice@example.com"}
```

Or, with the re-frame2-pair skill loaded, just describe the task — the skill's
SKILL.md teaches the agent which tool to call. See
[`../../skills/re-frame2-pair/SKILL.md`](../../skills/re-frame2-pair/SKILL.md).

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
`re-frame2-pair.runtime` namespace reappears in the new realm
together with the load-time marker at
`js/globalThis.__re_frame2_pair_runtime`.

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
| [`spec/003-Tool-Catalogue.md`](./spec/003-Tool-Catalogue.md) | The fourteen tools (the original per-op set + the `snapshot` mega-op + the streaming `subscribe` / `unsubscribe` / `list-subscriptions` triad + `get-path` direct-read + the `handler-meta` / `list-handlers` registrar-introspection pair + `get-re-frame2-pair-instructions` agent-onboarding), their argument schemas, EDN result shape. |

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
tools/re-frame2-pair-mcp/
├── README.md                                 ; this file
├── package.json                              ; npm package
├── shadow-cljs.edn                           ; build config
├── spec/                                     ; contract
├── pilot/                                    ; pre-port toolchain pilot
└── src/re_frame2_pair_mcp/
    ├── nrepl.cljs                            ; persistent socket + bencode
    ├── tools.cljs                            ; the fourteen MCP tools (per-op + snapshot + get-path + subscribe/unsubscribe/list-subscriptions + get-re-frame2-pair-instructions)
    └── server.cljs                           ; stdio JSON-RPC entry point
└── test/
    ├── re_frame2_pair_mcp/nrepl_test.cljs    ; bencode framing unit tests
    ├── stdio-roundtrip.js                    ; stdio integration test
    └── live-nrepl.js                         ; live-nREPL integration test
```

## Co-install with browser-substrate MCP servers (rf2-gj1kr)

re-frame2-pair-mcp is intentionally **re-frame2-runtime-only** — every tool
routes through one of the eight [Tool-Pair primitives](../../spec/Tool-Pair.md)
on the JVM-side nREPL socket. It does **not** drive the browser
directly. The absence of "click this button" / "screenshot this
viewport" / "navigate to this URL" tools is by design: browser
substrate is the concern of a peer MCP server.

For a fuller agent workflow, co-install one of the browser-substrate
MCP servers alongside re-frame2-pair-mcp. Each layer carries its own slice of
the surface:

| Layer | Server | Tools |
|---|---|---|
| Browser substrate | [Chrome DevTools MCP](https://github.com/anthropics/chrome-devtools-mcp) or [Playwright MCP](https://github.com/microsoft/playwright-mcp) | Click, type, navigate, screenshot, viewport |
| re-frame2 runtime | **re-frame2-pair-mcp** (this artefact) | `dispatch`, `snapshot`, `get-path`, `subscribe`, `eval-cljs`, … |

The split mirrors the framing in [causa-mcp's
DESIGN-RATIONALE](../causa-mcp/spec/DESIGN-RATIONALE.md) — browser-substrate
ops and re-frame2-runtime ops are different contracts, and bundling
them into one server would force every re-frame2 developer to take
on the heavyweight Chromium dep just to read `app-db`.

Example session: the browser MCP clicks a button → re-frame2-pair-mcp's
`subscribe` receives the resulting `:rf/epoch-record` → the agent
inspects the new app-db slice via `get-path`. Each server stays
single-purpose; the agent host glues them at the workflow level.

## Concurrent agents (rf2-hrcoj)

**v1 posture: single-agent per session.** Today's re-frame2-pair-mcp assumes
one agent host (Claude Code, Cursor, or Copilot) per running server
process. The shared mutable state — the nREPL connection, the
per-session response cache (`cache.cljs`), and the active
subscription registry — is **not** partitioned by agent.

Two agents attaching to the same re-frame2-pair-mcp instance simultaneously
work today (no lock-out), but they will see each other's
side-effects: a `dispatch` from agent A may show up in agent B's
`watch-epochs` poll; a `subscribe` from agent A counts against
agent B's `list-subscriptions`. For pre-alpha this is the documented
behaviour, not a bug — single-agent is the expected workflow.

**v2 sketch (not implemented; deferred).** Multi-agent semantics
would need: agent-scoped session ids on every `tools/call`,
per-agent subscription tables, optional lock-out on mutating ops
(`dispatch` with `:trace` mode + `eval-cljs` + `tail-build`), and
either per-agent response caches or a shared cache keyed by the
agent's request hash. The cache layer (`cache.cljs`) already keys
by request-args hash, which makes the shared-cache path the
likely first step.

If a workflow needs concurrent-agent isolation **today**, the
recourse is **one re-frame2-pair-mcp instance per agent host**, each
holding its own nREPL socket — shadow-cljs's nREPL supports
multiple concurrent clients.

## Record / replay session (rf2-f9acs, deferred)

**Status: deferred to a future drop.** Playwright MCP can record a
session (every click + viewport state) into a replayable artefact;
re-frame2-pair-mcp has no peer surface today. The existing surfaces give
agents push-mode visibility (`subscribe`) and pull-mode replay over
the epoch ring (`watch-epochs`, `trace-window`), but neither
persists across server lifetimes.

**v2 sketch (not implemented).** Two paired tools would round it
out:

- `record-session` — start capturing every `tools/call` (and its
  result envelope) into a session log keyed by an opaque
  session-id. Default off; opt-in per session. The log is plain EDN
  on disk so an agent can audit it out-of-band.
- `replay-session` — given a session-id and a target nREPL
  connection, re-issue each recorded call in order. Side-effects
  fire for real (same `dispatch` / `eval-cljs` path); useful for
  AI-assisted regression debugging where "this bug happened in the
  cell three replays ago" needs to be re-staged on demand.

Open questions before implementation: which calls record (all of
them, or only mutating ones?), session-log eviction (size cap?
time cap? per-host?), and how replay interacts with the
per-session response cache (replay a cache-hit verbatim, or
invalidate?). Filed as a follow-on RFE rather than a P2 because the
existing pull-mode / push-mode surfaces cover the high-frequency
debug-loop need; this is the cold-storage slot.

## Back-compat with the bash shims

The shims under `skills/re-frame2-pair/scripts/` still work and are
not slated for removal in this drop. Their headers carry a
deprecation notice pointing here. Migration is opt-in per session;
agents can mix shim calls and MCP tool calls in the same workflow
during the transition.
