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
| `SHADOW_CLJS_BUILD_ID` | `"app"` | Final-fallback build id passed to `cljs-eval`. See §Build-id resolution below for the full precedence ladder. |
| `SHADOW_CLJS_NREPL_PORT` | (unset) | Explicit nREPL port; takes precedence over port-file discovery. |
| `RE_FRAME2_PAIR_MCP_MAX_STREAMS` | `10` | Max concurrent open streaming subscriptions per session (rf2-3ijbl). |
| `RE_FRAME2_PAIR_MCP_MAX_EVENTS_PER_SEC` | `100` | Per-session rate-limit on progress-notification ticks (rf2-3ijbl). |
| `RE_FRAME2_PAIR_MCP_ABUSE_OVERFLOW_THRESHOLD` | `50` | Overflow events over the rolling window beyond which a stream is terminated for abuse (rf2-3ijbl). |
| `RE_FRAME2_PAIR_MCP_ABUSE_WINDOW_MS` | `10000` | Rolling-window length for abuse detection, in milliseconds (rf2-3ijbl). |

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
| `--allow-sensitive-reads` | OFF | Honour caller-supplied `:include-sensitive true` and `:elision false` on direct-read tools (`snapshot`, `get-path`, `subscribe`, `trace-window`, `watch-epochs`), and ship verbatim payloads through the preload's `app-db-reset!` `tap>` emission. Without the flag, sensitive slots redact and large slots elide before any payload crosses the wire — and the `tap>` payloads route through `re-frame.core/elide-wire-value` before any registered tap consumer sees them (rf2-c2dtu). Canonical cross-MCP flag name shared with story-mcp (rf2-2x3ql). |
| `--allow-writes` | OFF | Enable the state-mutating tools `restore-epoch` (time-travel undo) and `replace-app-db` (state injection), rf2-ee38b.18. Without the flag, both return `{:ok? false :reason :rf.error/writes-disabled}` without touching the nREPL socket. `dispatch` (which drives the app's own handlers) is unaffected. Note: this gate protects the named-write audit trail; it does NOT defend against eval-driven writes (eval-cljs can express the same writes), so for a true read-only posture compose with `--no-eval`. |
| `--max-concurrent-streams=N` | `10` | Resource control: cap concurrent open streaming subscriptions per session (rf2-3ijbl). CLI value wins over the matching env var. |
| `--max-events-per-sec=N` | `100` | Resource control: token-bucket rate-limit on progress-notification ticks emitted across all open streams (rf2-3ijbl). Checked before the destructive drain: a denied cycle is deferred (the runtime queue is preserved for a later cycle, no event loss — rf2-uvfph) and tallied as `:rate-dropped` on the final summary. |
| `--abuse-overflow-threshold=N` | `50` | Resource control: rolling-window overflow count beyond which the offending stream terminates with `:reason :rf.error/stream-abuse-detected` (rf2-3ijbl). |
| `--abuse-window-ms=N` | `10000` | Resource control: abuse-detection window length in milliseconds (rf2-3ijbl). |

Boolean flags + integer caps pass after the binary name:

```json
{
  "mcpServers": {
    "re-frame2-pair": {
      "command": "re-frame2-pair-mcp",
      "args": ["--no-eval", "--allow-sensitive-reads",
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

The normative cascade lives in
[`002-nREPL-Transport.md` §Port discovery](./002-nREPL-Transport.md);
it wins on any drift. The server resolves the port lazily on the first
tool call (not at boot — `roots/list` can only be issued after the MCP
`initialize` handshake), walking the following sources in precedence
order:

1. `--port-file <path>` launch flag — explicit, cwd-independent override.
2. `$SHADOW_CLJS_NREPL_PORT` env var.
3. MCP `roots/list` walk — ask the client for its workspace roots, walk
   each for `shadow-cljs.edn`, pair with the adjacent
   `.shadow-cljs/nrepl.port`; multiple matches drive `elicitation/create`.
4. Shadow HTTP probe — `GET http://127.0.0.1:9630/api/project-info`
   yields `:project-home`, then read `target/shadow-cljs/nrepl.port` /
   `.shadow-cljs/nrepl.port` / `.nrepl-port` against that root
   (`--http-port` overrides the default 9630).
5. CWD-relative scan of the same three candidates (legacy fallback).

If none resolve, the server boots in degraded mode (per
[`001-Wire-Protocol.md`](./001-Wire-Protocol.md) §Degraded boot) and
returns a structured error on every `tools/call` until the port
becomes resolvable; subsequent calls retry discovery. No restart needed
once shadow-cljs comes up.

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

The full tool surface, in the order a typical session uses them
(canonical count: [`003-Tool-Catalogue.md`](./003-Tool-Catalogue.md)).
Argument schemas and result shapes are specified there.

| Tool | Purpose |
|---|---|
| `discover-app` | Health-check the runtime; verify the shadow-cljs `:preloads` entry landed. Run first every session. |
| `eval-cljs` | Evaluate a CLJS form; returns the EDN value. |
| `dispatch` | Fire a re-frame event with `:origin :pair`. Modes: queued, sync, trace. |
| `restore-epoch` | Time-travel undo — rewind a frame's whole frame-state (BOTH app-db and runtime-db) to a recorded prior epoch's `:frame-state-after` via `replace-frame-state!`; machines / routes / elision / SSR metadata revive alongside app-db (Tool-Pair §Time-travel). `epoch-id` is EDN (the runtime emits integer ids). **Gated behind `--allow-writes`** (rf2-ee38b.18). |
| `replace-app-db` | State injection — replace a frame's `app-db` with an arbitrary EDN value the runtime never recorded; the JSON-loaded-bug-repro case (Tool-Pair §Pair-tool writes). Records a synthetic epoch. **Gated behind `--allow-writes`** (rf2-ee38b.18). |
| `trace-window` | Return epoch records from the last N ms. |
| `watch-epochs` | Pull-mode poll for matching epochs since a given id. |
| `tail-build` | Wait for a hot-reload to land by polling a probe form. |
| `snapshot`   | Coarse-grained per-frame state read in one round-trip. Returns `:app-db` + `:sub-cache` + `:machines` + `:epochs` + `:traces` slices for every (or a subset of) frame(s). Mega-op for investigate-X workflows. **Partition-aware off-box redaction (EP-0001 rf2-jj1xer · Mike ruling #14):** the `:app-db` / `:sub-cache` slices route through `re-frame.core/elide-wire-value` (per-slot sensitive / large elision); the `:machines` slice is **runtime-db-partition** state (machine snapshots moved to runtime-db in rf2-vzld77) and is **redacted to `:rf/redacted` off-box by default** — it ships only when the operator opted in to richer reads via the trusted-local `--allow-sensitive-reads` gate (`include-sensitive`). |
| `get-path` | Direct slice read at a path inside `app-db`. The deep-read peer of `snapshot`; agents drill in once a `:rf.mcp/summary` or elision marker names the path of interest. |
| `read-dom` | View-plane read — query the **rendered DOM** by CSS selector; returns matched count + per-node `{:tag :text :attrs}` as EDN (rf2-nfjil). Read-only; per-node text + matched-node count capped browser-side (over-cap text → `:rf.size/large-elided` marker). Optional `sub-selector` scopes to descendants of each match; `attrs` picks attributes (default: structural set + `data-*` / `aria-*` sweep). Answers "did the UI update?" / "what does the rendered node say?". Pairs with `dispatch {:await-render true}` for deterministic `dispatch → settle → read`. |
| `read-ui` | The typed **`ui/read`** op (rf2-3bu3d.1) — the complement to `read-dom`. Given a **view-id** (or a `point` / `selector`), return the rendered subtree **plus the producing re-frame2 entity** (`:view-id`, `:source-coord`, `:render-key`, `:subs-read`) in one round-trip. Rides the view-id↔DOM map (`data-rf-view="<id>"` — the same attribute the Xray hover-highlight uses), so it works on **any** re-frame2 app with **zero testids**. Read-only; `:text` elided like `snapshot` / `get-path` (`max-text` cap → `:rf.size/large-elided`). Answers "what does the thing I'm looking at SHOW, and what produced it?". |
| `record` | First-class **signal recorder** (rf2-zo4b9) — install a read-only observer over a signal-set (`:app-db [path]` / `:sub [query-v]` / `:dom "sel"` / `:focus true`) with a stop condition (`:ms` / `:changes` / `:pred`), let the human interact, then read the change-log back. Returns immediately with a `:recording-id`; the runtime samples each signal per animation frame, records each change with a timestamp, dedups, and tears itself down at the stop condition — the rAF/dedup/teardown footguns solved once. The canonical move for intermittent / human-in-the-loop bugs (the rf2-yng0y render-timing race). |
| `read-recording` | Read back a recording's change-log (rf2-zo4b9) — paired with `record`. `drain true` consumes the buffered entries (the live-watch poll→consume→repeat idiom); `stop true` reads-and-closes. Returns `:status` + per-change `:entries` with `:t` timestamps + rAF `:frame` counters. |
| `watch-until` | Block until a **predicate over a signal holds** (rf2-zo4b9) — the blocking counterpart to `record` ("wait until `[:upload :status]` flips to `:done`"). Server-polls (~100 ms, like `tail-build`) until the data predicate (`{:signal 0 :equals <v>}` / `:changed` / `:path` / `:contains`) holds or `timeout-ms` elapses. Returns the satisfying `:sample`, or `:reason :watch-timeout` with the final `:last-sample`. |
| `subscribe` | Streaming subscription on the trace / epoch bus (rf2-hq49). Push-mode replacement for `watch-epochs`; each matching event arrives as a `notifications/progress` notification. Topics: `trace`, `epoch`, `fx`, `error`. |
| `unsubscribe` | Close a streaming subscription out-of-band. Idempotent. |
| `list-subscriptions` | List the **live reactive sub-cache** for a frame — "what subscriptions are active?" — reading the same source as `snapshot :sub-cache` (rf2-qicji). Returns the cached query-vectors (reflecting disposal); optional `include-values` adds value + ref-count. |
| `list-streams` | Diagnostic peer for `subscribe` / `unsubscribe` — "what streaming taps are open?" snapshot of the active streaming-subscription set (rf2-qicji; the streaming diagnostic `list-subscriptions` formerly carried, wrapping `subscription-info`). |
| `handler-meta` | Return the registration-metadata map for a registered handler — `:source-coord`, `:doc`, `:tags`, and any custom slots from the reg-`*` macro. Fifteen accepted kinds: the fourteen registrar-backed kinds (event, sub, fx, cofx, interceptor, view, frame, route, flow, head, error-projector, resource, mutation, resource-scope — the three resources-artefact kinds are EP-0016 / rf2-f8s9g6; `interceptor` is EP-0022) plus the virtual `machine` kind. Answer "where is `:user/login` defined?" without an `eval-cljs` round-trip (rf2-cibp8). |
| `list-handlers` | Discovery peer of `handler-meta` — return every registered id under a kind. Sorted, stable shape. Same fifteen accepted kinds as `handler-meta`: fourteen registrar-backed kinds plus the virtual `machine` kind (rf2-pctf8; renamed from `registry-list` per rf2-4y595). |
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

The `await-render` flag (rf2-gfu33) is orthogonal to the mode table: set
it and the tool resolves only AFTER the substrate has flushed the new
state to the DOM and the next paint is scheduled, so `dispatch → observe`
is one deterministic step. It forces synchronous dispatch and routes the
flush through the substrate-agnostic `re-frame.interop/after-render`
adapter primitive (Spec 006) + one `requestAnimationFrame`. See
[`003-Tool-Catalogue.md`](./003-Tool-Catalogue.md) § dispatch §
Render-settle.

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
| `:rf.event/origin :pair` (in event tags) | framework | The pair tool's dispatches surface in the trace stream distinguishably. |

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
  se. The streaming-shaped tools (`subscribe`, `unsubscribe`, rf2-hq49)
  layer over MCP's `notifications/progress` mechanism: the server polls
  the runtime's drain at `poll-ms` and emits one progress notification
  per non-empty batch. The poll cadence is well below the agent loop's
  perceptual threshold; the `tools/call` stays open for the lifetime
  of the subscription and resolves on cancel / `unsubscribe` /
  caller-supplied caps.
