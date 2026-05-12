# 010-MCP-Server

Causa ships an MCP server as a separate jar at `tools/causa-mcp/`. The
server exposes Causa's surfaces as MCP tools so AI agents (Claude
Code, Cursor, Copilot) can read and write a live re-frame2 runtime
without opening Causa's UI.

The architecture mirrors `tools/pair2-mcp/` (per
[`tools/pair2-mcp/spec/`](../../pair2-mcp/spec/)): a Node-based stdio
JSON-RPC server, written in ClojureScript, compiled via shadow-cljs
to a single `.js` file. One persistent nREPL socket per session.
Different tool catalogue.

## Every MCP-driven mutation leaves a visible footprint

A defining property of Causa-MCP — and a first-class differentiator
against generalist agent surfaces like Chrome DevTools MCP's
`evaluate_script` — is that **every dispatch, every `reset-frame-db`,
every `restore-epoch` issued through this server is tagged
`:origin :causa-mcp` on the trace bus**. The agent driving the server
leaves an audit trail; its mutations are filterable, separable from
the user's, and distinguishable from `:app` / `:pair` / `:story` /
`:test` mutations in the same session.

Chrome's `evaluate_script` is untagged: an agent that hand-rolls
`js/window.dispatch(...)` is **indistinguishable** in the trace from
a real user click. Causa-MCP's tag inversion (`:origin :causa-mcp` by
default; opt out at your peril) means a post-incident audit can ask
"what did the agent do?" and get a complete, filterable answer via
`get-trace-buffer {:filter {:origin :causa-mcp}}`. See
[§Origin tagging](#origin-tagging) below for the full convention.

## Why a separate jar

Causa-the-panel is human-facing; Causa-MCP is agent-facing. Humans
and agents ship as distinct jars so the MCP server can be loaded
without dragging the entire Causa UI into the classpath; the two
surfaces split:

- **`tools/causa/`** — `day8/re-frame2-causa`. The DOM-injected panel.
  Pulled by `:preloads`. Browser-side artefact.
- **`tools/causa-mcp/`** — `day8/re-frame2-causa-mcp`. The MCP server.
  Pulled by `npm install`. Node-side artefact, attached over nREPL.

Both consume the same re-frame2 instrumentation surface (Spec 009
trace bus, Tool-Pair epoch history, registrar query API). Neither
depends on the other.

## Transport

Same as `tools/pair2-mcp/spec/001-Wire-Protocol.md`:

- Newline-delimited JSON-RPC 2.0 over stdio per the
  [MCP stdio transport spec](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports).
- One message per line on stdin/stdout. UTF-8. No embedded newlines.
- npm `@modelcontextprotocol/sdk`'s `StdioServerTransport` provides
  the framing.
- stdout reserved for MCP messages; stderr free-form.

Causa-MCP follows the same lifecycle:

1. Agent host launches the server as a subprocess.
2. Server responds to `initialize` with `protocolVersion`,
   `capabilities`, `serverInfo`.
3. `tools/list`, `tools/call` dispatch via the SDK's
   `setRequestHandler`.
4. Shutdown: client closes stdin → Node EOF → process exits.

## nREPL bridge

Same as `tools/pair2-mcp/spec/002-nREPL-Transport.md`:

- One TCP socket to `127.0.0.1:<nrepl-port>` held for the session.
- bencode framing via `bencode@2.0.x` (pinned for CommonJS
  compatibility).
- Multiplexing by UUID id over the socket's `pending` map.
- Port discovery via `$SHADOW_CLJS_NREPL_PORT` → `target/shadow-cljs/nrepl.port`
  → `.shadow-cljs/nrepl.port` → `.nrepl-port`.
- Degraded boot if no port is reachable; tools return
  `{:ok? false :reason :nrepl-port-not-found}`.

Causa-MCP injects a small runtime sentinel (`re-frame2-causa-mcp.runtime/session-id`)
that tools probe via `cljs-eval`. If the var is empty (e.g., after a
browser reload), the MCP server re-ships the runtime namespace before
the actual op runs. Mirrors the bash-shim chain's
`runtime-already-injected?` check (and the pair2-mcp implementation).

## Tool catalogue

Eighteen MCP tools across four bands: inspection (read-only),
mutation (user-confirmed equivalents in the UI), streaming
(`notifications/progress`-shaped), and meta (session-lifecycle).
Each maps to a Causa surface; together they let an agent observe,
dispatch, time-travel, stream, and inspect.

### Inspection (read-only)

| Tool | Mirrors Causa panel | Returns |
|---|---|---|
| `get-trace-buffer` | Trace timeline | A slice of the trace stream by filter. Forwards directly to `(rf/trace-buffer opts)`; recognised filter keys are the canonical [Spec 009 §Filter vocabulary](../../../spec/009-Instrumentation.md#filter-vocabulary) — `:operation`, `:op-type`, `:since`, `:frame`, `:severity`, `:event-id`, `:handler-id`, `:source`, `:origin`, `:dispatch-id`, `:since-ms`, `:between`, `:pred`. |
| `get-epoch-history` | Time-travel scrubber | Per-frame epoch history (vector of `:rf/epoch-record`). |
| `get-app-db` | App-db inspector | Current value at a frame, optionally at a path. |
| `get-app-db-diff` | App-db inspector (slice view) | The slice diff for a named epoch (`{:added [...] :modified [...] :removed [...]}`). |
| `get-machine-state` | Machine inspector | Current snapshot for a named machine. |
| `get-machine-list` | Machine inspector | List of registered machines per frame, with current state. |
| `get-issues` | Issues ribbon | Recent errors / warnings / schema violations / hydration mismatches. |
| `get-handlers` | (Settings → registry browser) | Registered handlers' metadata (`:doc`, source coords). |
| `get-source-coord` | Click-to-source | Source coord for a given id (handler, view, machine state, sub). |

### Mutation (user-confirmed equivalents in the UI)

| Tool | Mirrors Causa surface | Behaviour |
|---|---|---|
| `restore-epoch` | Bottom-rail Rewind button | Rewinds a frame's `app-db` to the named epoch's `:db-after`. Returns the six failure modes structurally. |
| `reset-frame-db` | "Try anyway" affordance on schema mismatch | Injects state, bypassing cascade; schema-validates against current schemas. |
| `dispatch` | Right-click → Re-dispatch | Fires an event tagged `:origin :causa-mcp` (so a separate trace tag distinguishes MCP-issued from in-panel-issued mutations). |

### Why `dispatch` is in the catalogue

Causa-MCP is **the agent face of Causa**. An agent driving Causa-MCP
is the user's delegate; the user has given consent (by enabling the
MCP server and giving the agent a key). Dispatch from the agent is
no more dangerous than dispatch from the agent driving
`tools/pair2-mcp/` — and we already ship that.

The `:origin :causa-mcp` tag means every dispatch from the MCP
surface is **visibly distinguishable** in the trace stream — Causa's
event log shows them in a distinct colour (per
[`007-UX-IA.md`](./007-UX-IA.md) §Colour system), and post-session
filters surface them as a group.

### Streaming (push-mode)

Request-response is the wrong shape for "tell me when the next
`:cart/checkout` event fires." Causa-MCP mirrors `tools/pair2-mcp/`'s
streaming surface (per
[`pair2-mcp/spec/003-Tool-Catalogue.md`](../../pair2-mcp/spec/003-Tool-Catalogue.md)
§subscribe), reusing the rf2-hq49 substrate. The `tools/call` request
stays open for the lifetime of the subscription; each batch of
matching events is emitted as a `notifications/progress` notification
correlated to the original call via `extra._meta.progressToken`. The
final `tools/call` result is a summary
`{:ok? true :sub-id <uuid> :delivered N :overflow N :ticks K :reason
<terminated-reason>}`.

| Tool | Topics | Returns |
|---|---|---|
| `subscribe` | `:trace`, `:epoch`, `:fx`, `:error` | Stream of events matching `filter`; topic-mediated base filter overlaid by user filter (user wins on conflict). Filter vocabulary mirrors [Spec 009 §Filter vocabulary](../../../spec/009-Instrumentation.md#filter-vocabulary) for `:trace` / `:fx` / `:error`; mirrors `watch-epochs` for `:epoch`. |
| `unsubscribe` | (n/a) | Idempotent close. Returns `{:ok? true :sub-id <id> :existed? <bool>}`. Useful when the agent host can't propagate `tools/call` cancellation cleanly. |

Termination paths: client cancel → `:reason :aborted`; out-of-band
`unsubscribe` → `:reason :sub-gone`; cap reached → `:reason
:max-ms-reached` or `:reason :max-events-reached`. Per-event matches
inherit the same `:origin :causa-mcp` filter axis as the rest of the
catalogue — an agent can subscribe to *just* its own mutation
footprint via `subscribe {:topic :trace :filter {:origin :causa-mcp}}`.

### Escape hatch

| Tool | Purpose | Returns |
|---|---|---|
| `eval-cljs` | Send an arbitrary CLJS form for evaluation in the connected browser runtime via `shadow.cljs.devtools.api/cljs-eval`. The escape hatch when the agent needs to peek somewhere the catalogue doesn't cover. Any side-effect the form triggers (a `dispatch`, a `reg-event-db`, etc.) is tagged `:origin :causa-mcp` on the trace bus, the same way the catalogue tools tag their effects. Mirrors `tools/pair2-mcp/`'s `eval-cljs`. | `{:ok? true :value <edn-value>}` on success; `{:ok? false :reason :eval-error :message "..."}` on failure; `:reason :runtime-not-preloaded` if the preload hasn't run. |

Args: `form` (string, required — EDN-encoded CLJS form), `frame`
(string, optional — operating frame for any dispatches the form
triggers), `build` (string, optional).

`eval-cljs` is the **deliberate escape valve**: the catalogue is
closed-set on purpose (twelve named surfaces map to twelve Causa
panels), but agents occasionally need to reach for something not yet
catalogued. Rather than refuse them and force a workaround through
Chrome MCP's `evaluate_script` (which loses the `:origin` tag), we
ship the escape hatch first-class. If a particular `eval-cljs` call
becomes recurrent, that's the signal to promote it to its own
catalogue entry.

### Meta (session lifecycle)

| Tool | Purpose | Returns |
|---|---|---|
| `discover-app` | First-call sanity check. Verify the shadow-cljs nREPL is reachable, confirm the `re-frame2-causa-mcp.runtime` namespace was loaded by the consumer's `:preloads`, return a health summary. Run first every session — replaces the ad-hoc `get-trace-buffer {limit: 10}` probe documented in earlier drafts. Mirrors `tools/pair2-mcp/`'s `discover-app`. | `{:ok? true :debug-enabled? <bool> :frames [<frame-id>...] :coord-annotation-enabled? <bool> :build-id <string>}` or `{:ok? false :reason :runtime-not-preloaded}` (most common precondition failure on a fresh app) / `:reason :nrepl-port-not-found`. |
| `tail-build` | Wait for a hot-reload to land. Polls a probe form until its value changes from its pre-call value, then returns. Times out after `wait-ms`. Necessary for "I just edited a file; wait for the new code to be live before dispatching." Mirrors `tools/pair2-mcp/`'s `tail-build`. | `{:ok? true :t <ms> :soft? false}` on a real change; `{:ok? false :reason :timed-out}` on timeout. If `probe` is omitted, falls back to a 300ms soft delay. |

Args for `discover-app`: `build` (string, optional, default `"app"`).

Args for `tail-build`: `probe` (string, optional — a CLJS form whose
value should change after the reload), `wait-ms` (integer, default
`5000`), `build` (string, optional).

## Args and return shapes

Each tool's args / returns follow the same EDN-encoded JSON pattern
as `tools/pair2-mcp/`:

```jsonc
// Example: get-app-db
{ "method": "tools/call",
  "params": {
    "name": "get-app-db",
    "arguments": {
      "frame": ":app/main",
      "path": "[:cart :items]"
    } } }

// Response:
{ "content": [{ "type": "text",
                "text": "{:ok? true :value [{:id 7 :qty 1} {:id 22 :qty 1}]}" }],
  "isError": false }
```

Tool-execution errors use `isError: true` with a structured EDN
error map in `content[0].text` — never a protocol-level error.

The full schema of each tool's args is bundled in
`tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools.cljs` (matching
the layout in `tools/pair2-mcp/src/re_frame_pair2_mcp/tools.cljs`)
and surfaced via `tools/list` in MCP-standard JSONSchema form.

## Lifecycle and reconnect

Same as pair2-mcp. A full page reload destroys the CLJS runtime in
the browser but leaves the JVM-side nREPL socket intact. Every tool
that needs the runtime calls `ensure-runtime!`:

1. `cljs-eval` `re-frame2-causa-mcp.runtime/session-id`.
2. If a non-blank string comes back, the runtime is live; proceed.
3. Otherwise, slurp the canonical `runtime.cljs` (shipped with the
   MCP jar) and ship it through `cljs-eval`. Then proceed.

No manual reconnect step is needed — page reload → next tool call →
automatic re-inject → op proceeds.

## Compared to pair2-mcp

| Axis | `tools/pair2-mcp/` | `tools/causa-mcp/` |
|---|---|---|
| **Audience** | Editor-side AI workflows (pair-programming). | Debugger-side AI workflows (inspection, time-travel). |
| **Surface** | 9 tools focused on dispatch / eval / hot-swap / trace, plus streaming. | 18 tools focused on inspection (graph, app-db, machine, issues) + restore / reset / dispatch + streaming + escape hatch + session lifecycle. |
| **`:origin` tag** | `:pair` | `:causa-mcp` |
| **Runtime injection** | `re-frame-pair2.runtime` | `re-frame2-causa-mcp.runtime` |
| **Implementation** | shadow-cljs `:node-script`, npm-published. | Same. |
| **MCP transport** | stdio JSON-RPC 2.0. | Same. |

Both can coexist in the same session. The agent picks the right tool
for the workflow: pair2 for "build/edit/test"; causa-mcp for
"inspect/debug/time-travel."

## Install + configure

```bash
npm install -g @day8/re-frame2-causa-mcp
```

```jsonc
// ~/.claude/settings.json
{
  "mcpServers": {
    "causa": {
      "command": "re-frame2-causa-mcp",
      "env": { "SHADOW_CLJS_BUILD_ID": "app" }
    }
  }
}
```

First-call sanity check (use `discover-app`, the dedicated
session-probe tool):

```text
Agent: tools/call discover-app {}
Server: {:ok? true
         :debug-enabled? true
         :frames [:rf/default :app/main]
         :coord-annotation-enabled? true
         :build-id "app"}
```

If `discover-app` returns `:ok? false :reason :runtime-not-preloaded`,
the consumer's `shadow-cljs.edn` is missing the
`re-frame2-causa-mcp.runtime` preload — see the skill's SKILL.md
§Setup. If `:reason :nrepl-port-not-found`, the shadow-cljs build
isn't running.

## Origin tagging

Every Causa-MCP-driven side-effect on the trace bus carries
`:tags :origin :causa-mcp`. The tag is set at the entry point of each
mutating tool (`dispatch`, `reset-frame-db`, `restore-epoch`) and at
the boundary of `eval-cljs` (any dispatch the eval'd form triggers
inherits the tag — the runtime install hook reads
`re-frame2-causa-mcp.runtime/current-origin` for the dynamic extent
of the eval call).

This is **the convention**, not a suggestion. It costs nothing at
the call site and the audit-trail payoff is substantial:

| Question | Trace filter |
|---|---|
| What did the agent do this session? | `get-trace-buffer {:filter {:origin :causa-mcp}}` |
| Did the agent or the user fire `:cart/checkout`? | `get-trace-buffer {:filter {:event-id :cart/checkout}}` then read `:tags :origin` on each match |
| Stream just my own mutations as they happen | `subscribe {:topic :trace :filter {:origin :causa-mcp}}` |
| All non-agent traffic | `get-trace-buffer {:filter {:origin :app}}` (or `:pair` / `:story` / `:test`) |

### Origin vocabulary

`:origin` is a closed-set tag with five values across the re-frame2
ecosystem:

| Value | Source |
|---|---|
| `:app` | The application itself — user clicks, timers, normal dispatch. The default when no MCP / story / test driver is in play. |
| `:pair` | `tools/pair2-mcp/` — editor-side AI workflows (build/edit/test). |
| `:causa-mcp` | This server — debugger-side AI workflows (inspect/time-travel). |
| `:story` | Tool-Pair stories playing back in the browser. |
| `:test` | Test runs (cljs.test, kaocha, etc.). |

The Causa panel uses the `:origin` axis directly in its [colour
system](./007-UX-IA.md#colour-system) — agent-driven events render
in a distinct hue per origin, so a user scrubbing the timeline can
see at-a-glance whether a frame's bug was authored by a human, a
test, or an agent. This is the surface property the
`evaluate_script`-shaped tools in other MCP servers cannot match:
they don't even *have* a structured trace bus to tag.

## Chrome DevTools MCP co-install

Causa-MCP and [Chrome DevTools MCP](https://github.com/ChromeDevTools/chrome-devtools-mcp)
(v0.26.0, May 2026 — 46 tools across 8 categories) are
**complementary, not competitive**. Causa-MCP owns the *app-domain*
(re-frame2 events, subs, machines, app-db, epochs); Chrome MCP owns
the *browser-substrate* (screenshots, DOM, network, memory,
performance). An agent debugging a real bug typically wants both.

We recommend agent host setups load both, side-by-side.

### Quick guide

| Question | Server |
|---|---|
| What did the **app** do? | Causa-MCP |
| What did the **browser** do? | Chrome MCP |
| What does the **user see**? | Chrome MCP (`take_screenshot`) |
| What is the **DOM / accessibility tree**? | Chrome MCP (`take_snapshot`) |
| What **HTTP responses** came back? | Chrome MCP (`list_network_requests`, `get_network_request`) |
| Where is the **memory leak**? | Chrome MCP (`take_memory_snapshot`) |
| **Why did this event fire**? | Causa-MCP (`get-trace-buffer` with `:dispatch-id` / `:parent-dispatch-id`) |
| What did **app-db look like** at frame N? | Causa-MCP (`get-app-db`) |
| What **changed in state** when this event ran? | Causa-MCP (`get-app-db-diff`) |
| **Rewind** to a previous state? | Causa-MCP (`restore-epoch`) |
| Watch for the **next `:cart/checkout`** event? | Causa-MCP (`subscribe`) |

### Co-install snippet

```jsonc
// ~/.claude/settings.json
{
  "mcpServers": {
    "causa": {
      "command": "re-frame2-causa-mcp",
      "env": { "SHADOW_CLJS_BUILD_ID": "app" }
    },
    "chrome-devtools": {
      "command": "npx",
      "args": ["-y", "chrome-devtools-mcp@latest"]
    }
  }
}
```

The two servers don't talk to each other (Causa-MCP attaches over
nREPL to shadow-cljs; Chrome MCP attaches over CDP to a Chrome
process). The agent host multiplexes between them — and re-frame2's
`:origin` tagging is what lets the agent reconcile cross-server
observations: a Chrome MCP `evaluate_script` mutation will show up
in Causa-MCP's trace bus tagged `:origin :app` (because it bypasses
the Causa-MCP entry points), distinguishing it from native
Causa-MCP dispatches.

## Per-tool comparison with Chrome DevTools MCP

The table below maps each Causa-MCP tool to its closest Chrome
DevTools MCP equivalent, with a winner-per-row and a notes column.
"(gap — Chrome)" rows are surfaces Chrome ships that Causa-MCP
deliberately doesn't reach for (they're better served by co-install
than by reinvention). "(gap — Causa)" rows are surfaces Causa-MCP
ships that Chrome can't match.

| Causa-MCP tool | Chrome DevTools MCP equivalent | Winner | Notes |
|---|---|---|---|
| `discover-app` | `list_pages` (partial) | **Causa** | Causa probes the re-frame2 runtime: frames live, build-id, debug flag, coord-annotation status. Chrome lists open browser tabs — orthogonal concern. |
| `get-trace-buffer` | `list_console_messages` | **Causa** | Structured filter vocabulary (`:operation`, `:dispatch-id`, `:parent-dispatch-id`, `:source-coord`, `:origin`, `:since-ms`, `:between`, `:pred`) vs flat string log. Chrome's console is unstructured and app-blind. |
| `get-epoch-history` | (none) | **Causa** | No Chrome surface for per-event domain-state delta. Closest is `performance_start_trace` — but that's pixel-frame timeline, not state-frame timeline. |
| `get-app-db` | `evaluate_script` (manual) | **Causa** | Chrome agents hand-roll JS to walk the React/Redux state tree; re-frame's CLJS objects are opaque without the runtime. Causa-MCP returns EDN, optionally at `:path`. |
| `get-app-db-diff` | (none) | **Causa** | No Chrome surface for "what changed in state between two events." Diff is a unique Causa affordance. |
| `get-machine-state` | (none) | **Causa** | No FSM inspection on Chrome's side. Causa exposes the [Machine Inspector](./003-Machine-Inspector.md) over MCP. |
| `get-machine-list` | (none) | **Causa** | Same — no equivalent. |
| `get-issues` | `list_console_messages` (filtered `error`) | **Causa** | Chrome surfaces console errors; Causa surfaces *typed* issues (schema violations, hydration mismatches, error-boundary catches) with `:source-coord`. |
| `get-handlers` | (none) | **Causa** | Registry browsing is unique to Causa. |
| `get-source-coord` | (none) | **Causa** | Click-to-source via id; no equivalent because Chrome has no per-handler registry to coord. |
| `restore-epoch` | (none) | **Causa** | Time-travel is unique to Causa-MCP across the entire surveyed 2026 MCP landscape. |
| `reset-frame-db` | `evaluate_script` (manual) | **Causa** | Hand-rolled `app-db` injection works but loses Causa's schema-validate-and-bypass semantics. |
| `dispatch` | `evaluate_script` (manual) | **Causa** | Hand-rolled `js/window.dispatch(...)` is untagged — indistinguishable from a user click. Causa-MCP's `:origin :causa-mcp` makes mutations distinguishable in the trace stream. |
| `eval-cljs` | `evaluate_script` | **Tied** | Both ship escape hatches. Chrome's is JS-only; Causa-MCP's is CLJS-form-aware and `:origin`-tags any side-effects. |
| `subscribe` / `unsubscribe` | (none) | **Causa** | `notifications/progress` streaming over the structured trace bus. Chrome MCP is request-response only. |
| `tail-build` | (none) | **Causa** | Hot-reload-await for shadow-cljs workflows. Chrome MCP has no build-system concept. |
| (gap — Chrome) `take_screenshot` | `take_screenshot` | **Chrome** | Pixel ground truth for render bugs. **Co-install case** — don't reinvent CDP. |
| (gap — Chrome) `take_snapshot` (a11y tree) | `take_snapshot` | **Chrome** | Stable element ids for click/fill workflows. Co-install. |
| (gap — Chrome) `list_network_requests` / `get_network_request` | Same | **Chrome** | Wire-level HTTP. Causa-MCP sees `:fx :http` *effects* but not the response body. Co-install. |
| (gap — Chrome) `performance_start_trace` / `performance_analyze_insight` | Same | **Chrome** | Long-task and layout-shift profiling. Co-install. |
| (gap — Chrome) `take_memory_snapshot` | Same | **Chrome** | Heap profiling for sub-cache / closure leaks. **High-value co-install case** — re-frame2 sub-caches are exactly the kind of structure that leaks. |

### The structured-trace differentiator

The single most important row in the table above is
`get-trace-buffer` vs `list_console_messages`. Chrome's console is a
flat string log; an agent chasing a re-frame2 dispatch bug must
regex out event ids, hand-parse stack traces for handler line
numbers, and has no path to "what was app-db at the time" or "did a
sub re-run unexpectedly?" Causa-MCP's filter vocabulary
(`:dispatch-id`, `:parent-dispatch-id`, `:source-coord`,
`:operation`, `:origin`, `:event-id`, `:handler-id`, `:since-ms`,
`:between`, `:pred`) makes every domain question a filter axis, not
a regex.

That structured-trace bus is also what powers `subscribe` /
`unsubscribe`, `:origin` tagging, `get-app-db-diff`, and the
epoch-history time-travel substrate. It's the foundation; the
catalogue is its surface.

## What this doesn't do

- **No GUI rendering.** The MCP server has no UI; it speaks JSON-RPC
  to its agent host. The Causa panel is unaffected (and need not be
  open) for the MCP server to work.
- **No telemetry.** The server transmits nothing to Day8.
- **No automatic re-dispatch.** Dispatches go through the same gates
  as pair2-mcp; the agent is the user's delegate.
- **No conversation history.** The MCP server is stateless across
  tool calls; conversation state belongs to the agent host.

## Spec scope

This doc is the **contract**, not the implementation. The
implementation details (shadow-cljs build, the bencode pin, the
specific tools.cljs shape) follow `tools/pair2-mcp/spec/`'s structure
and prose; replicating them here would be redundant. When
`tools/causa-mcp/` lands, it will carry its own `spec/` folder with
the four pair2-mcp-shape files (000-Vision, 001-Wire-Protocol,
002-nREPL-Transport, 003-Tool-Catalogue) parameterised for Causa.
