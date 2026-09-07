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

Build from a clone using [From source](#from-source), then configure the
host to run the compiled `out/server.js` with Node. See the
[tool README's installation status](../README.md#install) before using
the package-name launch forms; npm installation requires publication.

## Configuration

### Claude Code

Add the server to `.mcp.json` at the project root (project scope), merging
with any existing `mcpServers` entries. User- or local-scoped registrations
use `claude mcp add`; see [Claude Code's MCP configuration](https://code.claude.com/docs/en/mcp).
From a clone:

```json
{
  "mcpServers": {
    "re-frame2-pair": {
      "command": "node",
      "args": ["<repo>/tools/re-frame2-pair-mcp/out/server.js"],
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
| `SHADOW_CLJS_BUILD_ID` | `"app"` | Final-fallback build id passed to `cljs-eval`. See §Build-id resolution below for the full precedence ladder. |
| `SHADOW_CLJS_NREPL_PORT` | (unset) | Explicit nREPL port; follows `--port-file` and precedes automatic port-file discovery. |

### Build-id resolution

Every tool call needs a shadow-cljs build id to route over the nREPL
socket. The server walks **three sources in precedence order** (rf2-l9ixp;
impl: [`src/re_frame2_pair_mcp/tools/wire.cljs`](../src/re_frame2_pair_mcp/tools/wire.cljs)
`arg-build`, lines 125-146):

1. **Explicit `:build` MCP arg on the call.** Operator override always
   wins; no surprise from the cache. The arg is **colon-tolerant**
   (rf2-8ohwv): `"examples/step-deck"` and `":examples/step-deck"`
   resolve to the same keyword — coerced via
   `re-frame.mcp-base.args/fresh-keyword`, which strips a leading colon
   before interning. A bare `keyword` on the colon form would mint the
   malformed `::examples/step-deck` and probe a build that doesn't
   exist; that footgun is closed.

   The arg is also **suffix-forgiving** (rf2-qda59). shadow build ids are
   namespaced keywords (`:examples/machine-epochs`); an operator reading
   the app — or copying a name out of an error / chat — naturally reaches
   for the short tail (`machine-epochs`). A `:build` arg that names a
   running build by a **unique name-suffix** resolves to the canonical
   running id, so the same forgiving resolution applies to **every** op,
   not just `discover-app`. The match rule is deterministic — exact
   keyword match first, then a unique tail match; **two builds sharing
   the tail stay ambiguous** and a no-match id passes through unchanged so
   the diagnostic ladder still fires (never a silent wrong-build pick).
   Resolution runs once per session at the pipeline's first step
   (`tools.cljs` `canonicalize-build-step`) and caches the
   suffix→canonical alias on the conn-atom (`:build-alias`), so it costs
   at most one `active-builds` round-trip per distinct build name and is
   free for an already-exact / already-resolved id
   (impl: [`src/.../tools/probe.cljs`](../src/re_frame2_pair_mcp/tools/probe.cljs)
   `canonicalize-build!` / `match-running-build`).
2. **Session-scoped `:resolved-build-id` cache on the conn-atom — the
   sticky operating build.** Populated two ways:
   - by `discover-app` after a successful preload probe
     (`wire/mark-resolved-build-id!`; call sites at
     `src/.../tools/discover_app.cljs`), and
   - by an explicit `:build` arg on **any other** tool call, written
     back at the `invoke` boundary via `wire/stick-build!` (rf2-lbm21).
     The first call that names a build sets the session-sticky default;
     every subsequent call may omit `:build`. `arg-build` itself stays a
     pure read — the write lives at the single dispatch chokepoint so
     `discover-app` (which calls `arg-build` to *probe* a build, and
     caches only on a passing health check) is never forced to cache a
     build that turned out unusable.
   Removes the friction of repeating `build: foo` on every op.
3. **`SHADOW_CLJS_BUILD_ID` env var, defaulting to `:app`.** The
   final fallback when neither (1) nor (2) is available.

**Invalidation.** The cache resets on `nrepl/connect!`
(`src/re_frame2_pair_mcp/nrepl.cljs` line 398) and `nrepl/close!`
(line 416) — the operator may relaunch shadow-cljs against a different
build id, so the next reconnect starts with no cached resolution.

**Consequence for callers.** After **any** call that names a build —
`discover-app {build: "my-app"}`, or a first `snapshot {build:
"my-app"}` — subsequent tool calls in the same session may omit the
`:build` arg even when `SHADOW_CLJS_BUILD_ID` is unset and would
otherwise default to `:app`. The sticky build carries through; a later
explicit `:build` both routes that call and re-points the sticky
default. Multi-build workspaces re-specify `:build` when switching the
target.

**Round-trippable canonical ids (rf2-8t3ct).** The canonical build id
is the **keyword** (`:examples/step-deck`). `discover-app` echoes it
under both `:build-id` and `:build` (the latter matching the *input arg
name*), and the read-family ops (`orient`, `read-dom`, `read-ui`,
`eval-cljs`) echo the resolved `:build` on their result. A value copied
out of any of those slots works unchanged as a later `:build` arg. The
alias axes are both **deterministic** (source (1) above): colon-tolerance
— bare-qualified and colon-prefixed string forms read back to the same
keyword — and the unique-suffix match, which resolves a short tail to
exactly one running build or stays ambiguous. The registry is the finite
set of shadow-cljs running builds; there is no fuzzy / most-recent
heuristic.

**Per-session scope, never a process-global (rf2-fmho5).** The sticky
`:resolved-build-id` lives on the **conn-atom**, which is created
per-server-instance (`nrepl/make-conn`, held in `server.cljs`'s
`session-state`). Under the MCP stdio model each client spawns its own
server process — so the sticky target is scoped to that one
connection/session and can never leak across clients. When no session
target exists and the bare `:app` default isn't running while several
builds are, the eval-path resolver (`probe/resolve-build!`) rejects with
a structured `:no-runtime-for-build` enumerating `:running-builds`, and
the plain read path surfaces the same candidate list via the diagnostic
ladder's `:build-not-running` rung — a clear ambiguous-target error
listing candidates, never a silent wrong-build pick or a host failure.

**Round-trippable candidate list (rf2-qda59).** Both error envelopes
carry `:running-builds` (the keyword vector) **and** a sibling
`:running-builds-arg-forms` — each running build rendered in exactly the
string form the `:build` arg accepts back (`":examples/machine-epochs"`).
Every hint string uses the same colon form. So an operator can copy a
build straight out of the error into a `:build` arg and it resolves —
the list names the valid set in a form that pastes back, closing the
round-trip trap where the error printed names it then rejected.

Distinct from the per-session **response** cache (rf2-3rt1f, see
[`Principles.md`](./Principles.md) § Per-session response cache) —
that one memoises full response payloads by (tool, args) hash within a
session; this cache memoises only the resolved build-id.

### Launch flags

| Flag | Default | Purpose |
|---|---|---|
| `--no-eval`               | absent (eval-cljs ON) | Opt OUT of the `eval-cljs` tool (rf2-a0z0h; inverts the prior rf2-cxx5s default-OFF posture). Default is eval-cljs ENABLED — it is the REPL primitive of a pair-debug session. With this flag, `eval-cljs` calls return `{:ok? false :reason :rf.error/eval-cljs-disabled}` without touching the nREPL socket. |
| `--allow-sensitive-reads` | OFF | Enables each value-egress tool's documented per-call disclosure knobs; it is not a blanket raw-payload bypass. See the [canonical launch-gate contract](./003-Tool-Catalogue.md#universal-server-launch-flags) for independent app-db, epoch, effect-argument, and runtime-tap rules. |
| `--allow-writes` | OFF | Enable the state-mutating tools `restore-epoch` (time-travel undo) and `replace-app-db` (state injection), rf2-ee38b.18. Without the flag, both return `{:ok? false :reason :rf.error/writes-disabled}` without touching the nREPL socket. `dispatch` (which drives the app's own handlers) is unaffected. Note: this gate protects the named-write audit trail; it does NOT defend against eval-driven writes (eval-cljs can express the same writes). `--no-eval` additionally removes eval-driven writes, but neither flag disables `dispatch` or `replay-epoch`; the combination is not a read-only mode. |

Flags pass after the binary name:

```json
{
  "mcpServers": {
    "re-frame2-pair": {
      "command": "re-frame2-pair-mcp",
      "args": ["--no-eval", "--allow-sensitive-reads"]
    }
  }
}
```

The normative contract for both gates lives in
[`003-Tool-Catalogue.md` §Universal: server launch flags](./003-Tool-Catalogue.md#universal-server-launch-flags).

### nREPL port discovery

The normative cascade lives in
[`002-nREPL-Transport.md` §Port discovery](./002-nREPL-Transport.md);
it wins on any drift. The server resolves the port lazily on the first
tool call (not at boot — `roots/list` can only be issued after the MCP
`initialize` handshake), walking the following sources in precedence
order:

1. `--port-file <path>` launch flag — explicit, cwd-independent override.
2. `$SHADOW_CLJS_NREPL_PORT` env var.
3. MCP `roots/list` walk — ask the client for its workspace roots, walk
   each for `shadow-cljs.edn`, then test the standard port-file candidates
   relative to that project; multiple live matches drive
   `elicitation/create`.
4. Shadow HTTP probe — `GET http://127.0.0.1:9630/api/project-info`
   yields `:project-home`, then read `target/shadow-cljs/nrepl.port` /
   `.shadow-cljs/nrepl.port` / `.nrepl-port` against that root
   (`--http-port` overrides the default 9630).
5. CWD-relative scan of the same three candidates (legacy fallback).

If none resolve, the server boots in degraded mode (per
[`001-Wire-Protocol.md`](./001-Wire-Protocol.md) §Degraded boot) and
returns a structured error on app-facing calls until the port becomes
resolvable; subsequent calls retry discovery. Server-local tools and
pre-connection guards remain available. No restart is needed once
shadow-cljs comes up.

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

The canonical [tool catalogue](./003-Tool-Catalogue.md) owns the complete
inventory, argument schemas, result shapes, and cross-tool contracts.
The running server also publishes its descriptors through `tools/list`.

## Mode flags (dispatch)

`dispatch` defaults to a synchronous, compact consequence response.
Async transport acknowledgement is explicit: `queued true`, without a
synchronous mode selected. `trace true` returns a projected epoch;
`settle true` additionally performs the synchronous render flush and
takes precedence over the other modes. `await-render true` forces a
synchronous dispatch and waits for the adapter's after-render callback
plus one animation frame, unless `settle` is selected.

See [dispatch](./003-Tool-Catalogue.md#dispatch) for the authoritative
precedence table, response fields, and render-settle semantics.

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
| `re-frame2-pair.runtime/dispatch-consequence!` | preload/re_frame2_pair/runtime.cljs | Default synchronous dispatch and compact consequence response. |
| `re-frame2-pair.runtime/pair-dispatch!`, `dispatch-and-collect`, `dispatch-and-settle!` | preload/re_frame2_pair/runtime.cljs | Explicit queued, trace, and settle modes respectively. |
| `re-frame2-pair.runtime/trace-window` | preload/re_frame2_pair/runtime.cljs | Last-N-ms epoch lookback. |
| `re-frame2-pair.runtime/watch-epochs` | preload/re_frame2_pair/runtime.cljs | Poll for epochs after id. |
| `re-frame2-pair.runtime/snapshot-state` | preload/re_frame2_pair/runtime.cljs | Per-frame slice composer fed by `:include` / `:frames` opts; backs the `snapshot` MCP tool. |
| `shadow.cljs.devtools.api/cljs-eval` | shadow-cljs | The CLJS bridge over the JVM-side nREPL socket. |
| `:rf/epoch-record` | framework | The epoch record shape returned by trace mode. |
| `:rf.event/origin :pair` (in event tags) | framework | The pair tool's dispatches surface in the trace stream distinguishably. |

## The retired bash shims

**There is no second transport.** The bash shims under
`skills/re-frame2-pair/scripts/` were deleted on 2026-07-11 under
rf2-dduetj (commit `31594b44c3`); that directory holds no files. MCP
is the only way to invoke any op, and there are no shim calls to mix
with tool calls.

The mapping is retained for reading old runbooks, which may still name
a script that no longer exists. The op vocabulary was identical across
the two surfaces, so each retired script's name reads directly as its
MCP tool:

| Retired bash shim | MCP tool |
|---|---|
| `discover-app.sh` | `discover-app` |
| `eval-cljs.sh` | `eval-cljs` |
| `dispatch.sh` | `dispatch` |
| `trace-window.sh` | `trace-window` |
| `watch-epochs.sh` | `watch-epochs` |
| `tail-build.sh` | `tail-build` |
| _(never had one — MCP-only)_ | `snapshot` |

The `snapshot` mega-op never had a bash equivalent — it's a
coarse-grained composition of the existing per-slice runtime readers,
shipped as part of the rf2-x70e drop to cut round-trips for
investigate-X workflows. Under the shim chain its semantics required
the per-op reads (`app-db/snapshot` + `subs/cache` + `machines/list`
+ `epoch/history` + `trace/buffer`) in sequence.

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
  dispatch types, no new effect substrates. Every op routes
  through existing `re-frame2-pair.runtime` surfaces (canonical
  count: [`003-Tool-Catalogue.md`](./003-Tool-Catalogue.md)). See
  [`Principles.md`](./Principles.md) § Tool consumes the framework.
- **No remote-attach protocol.** re-frame2-pair-mcp is stdio-only; the agent
  host owns the network plumbing. A custom WebSocket protocol was
  considered and rejected (per
  [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md) § Lock #2).
- **No "private" surfaces.** All public ops are listed in
  [`003-Tool-Catalogue.md`](./003-Tool-Catalogue.md); there are no
  hidden tools, no internal-only endpoints.
- **No raw streaming transport.** MCP isn't a streaming protocol per
  se, and this server ships no push channel: every observation arrives
  as a completed tool result (the push-mode `subscribe` / `unsubscribe`
  pair that once layered over `notifications/progress` was retired
  under rf2-ahjbc).
