# 010-MCP-Server

Causa ships an MCP server as a separate jar at `tools/causa-mcp/`. The
server exposes Causa's surfaces as MCP tools so AI agents (Claude
Code, Cursor, Copilot) can read and write a live re-frame2 runtime
without opening Causa's UI.

> **Canonical spec home.** The per-tool spec scaffold for the MCP
> server lives at [`tools/causa-mcp/spec/`](../../causa-mcp/spec/) —
> see [`000-Vision.md`](../../causa-mcp/spec/000-Vision.md),
> [`Principles.md`](../../causa-mcp/spec/Principles.md), and
> [`DESIGN-RATIONALE.md`](../../causa-mcp/spec/DESIGN-RATIONALE.md).
> The design locks accumulated in this file have been lifted there;
> citations point to their canonical homes. This file remains the
> **catalogue prose** (the eighteen tools enumerated with signatures
> and return shapes); when implementation lands and
> `tools/causa-mcp/spec/003-Tool-Catalogue.md` is authored, the
> catalogue prose migrates there and this file shrinks to a pointer.

The architecture mirrors `tools/pair2-mcp/` (per
[`tools/pair2-mcp/spec/`](../../pair2-mcp/spec/)): a Node-based stdio
JSON-RPC server, written in ClojureScript, compiled via shadow-cljs
to a single `.js` file (per
[`tools/causa-mcp/spec/DESIGN-RATIONALE.md`](../../causa-mcp/spec/DESIGN-RATIONALE.md#lock-1--implementation-language)
Lock #1, [Lock #2](../../causa-mcp/spec/DESIGN-RATIONALE.md#lock-2--agent-host-transport)).
One persistent nREPL socket per session (per
[Lock #3](../../causa-mcp/spec/DESIGN-RATIONALE.md#lock-3--connection-model)).
Different tool catalogue.

## Every MCP-driven mutation leaves a visible footprint

A defining property of Causa-MCP — and a first-class differentiator
against generalist agent surfaces like Chrome DevTools MCP's
`evaluate_script` — is that **every dispatch, every `reset-frame-db`,
every `restore-epoch` issued through this server is tagged
`:origin :causa-mcp` on the trace bus** (per
[`tools/causa-mcp/spec/DESIGN-RATIONALE.md`](../../causa-mcp/spec/DESIGN-RATIONALE.md#lock-4--origin-tagging-is-the-convention)
Lock #4 and
[`tools/causa-mcp/spec/Principles.md`](../../causa-mcp/spec/Principles.md)
§Origin tagging is the convention, not a suggestion). The agent
driving the server leaves an audit trail; its mutations are
filterable, separable from the user's, and distinguishable from
`:app` / `:pair` / `:story` / `:test` mutations in the same session.

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
  The npm coord is locked at
  [`tools/causa-mcp/spec/DESIGN-RATIONALE.md`](../../causa-mcp/spec/DESIGN-RATIONALE.md#lock-6--npm-package-coord)
  Lock #6.

Both consume the same re-frame2 instrumentation surface (Spec 009
trace bus, Tool-Pair epoch history, registrar query API). Neither
depends on the other. Further detail on the split lives at
[`tools/causa-mcp/spec/000-Vision.md`](../../causa-mcp/spec/000-Vision.md)
§Why a separate jar.

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

- One TCP socket to `127.0.0.1:<nrepl-port>` held for the session
  (per
  [`tools/causa-mcp/spec/DESIGN-RATIONALE.md`](../../causa-mcp/spec/DESIGN-RATIONALE.md#lock-3--connection-model)
  Lock #3).
- bencode framing via `bencode@2.0.x` (pinned for CommonJS
  compatibility — per
  [`tools/causa-mcp/spec/DESIGN-RATIONALE.md`](../../causa-mcp/spec/DESIGN-RATIONALE.md#lock-8--bencode-pinning)
  Lock #8).
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

Eighteen MCP tools across five bands: inspection (read-only),
mutation (user-confirmed equivalents in the UI), streaming
(`notifications/progress`-shaped + the diagnostic),
escape hatch (`eval-cljs`), and meta (session-lifecycle). Each
maps to a Causa surface (or the cross-server symmetry posture);
together they let an agent observe, dispatch, time-travel,
stream, and inspect.

The eighteen-tool cardinality and closed-set policy are locked at
[`tools/causa-mcp/spec/DESIGN-RATIONALE.md`](../../causa-mcp/spec/DESIGN-RATIONALE.md#lock-5--tool-catalogue-cardinality-and-shape)
Lock #5 (as amended by Lock #12 on 2026-05-14, which added
`list-subscriptions` to close the parity gap with pair2-mcp's
`subscription-info` impl); the closed-set discipline and
`eval-cljs` escape valve posture live at
[`tools/causa-mcp/spec/Principles.md`](../../causa-mcp/spec/Principles.md)
§Closed-set tool catalogue, deliberate escape valve. The
**catalogue prose itself** (the tables below) remains here until
implementation lands and migrates it to
`tools/causa-mcp/spec/003-Tool-Catalogue.md`.

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

| Tool | Topics / Args | Returns |
|---|---|---|
| `subscribe` | `:trace`, `:epoch`, `:fx`, `:error` | Stream of events matching `filter`; topic-mediated base filter overlaid by user filter (user wins on conflict). Filter vocabulary mirrors [Spec 009 §Filter vocabulary](../../../spec/009-Instrumentation.md#filter-vocabulary) for `:trace` / `:fx` / `:error`; mirrors `watch-epochs` for `:epoch`. |
| `unsubscribe` | (n/a) | Idempotent close. Returns `{:ok? true :sub-id <id> :existed? <bool>}`. Useful when the agent host can't propagate `tools/call` cancellation cleanly. |
| `list-subscriptions` | `:topic` (keyword, optional), `:sub-id` (uuid string, optional) | Request-response (NOT streaming) diagnostic enumerating active subscriptions. Returns `{:ok? true :subs [{:id :topic :filter :queue-depth :queue-bytes :dropped-events :dropped-bytes :overflow-reason :created-at} ...]}`. Empty `:subs` when no streams are open / the filter matches nothing. Useful when a streaming probe seems to have gone quiet (confirm the sub is still registered; read `:queue-depth` / `:overflow-reason` for evidence of a dead consumer). Mirrors pair2-mcp's `subscription-info` workflow (rf2-zjz9q) under a NAMING.md-conformant verb (per [`tools/causa-mcp/spec/DESIGN-RATIONALE.md` Lock #12](../../causa-mcp/spec/DESIGN-RATIONALE.md)). |

Termination paths (for `subscribe`): client cancel → `:reason
:aborted`; out-of-band `unsubscribe` → `:reason :sub-gone`; cap
reached → `:reason :max-ms-reached` or `:reason
:max-events-reached`. Per-event matches inherit the same
`:origin :causa-mcp` filter axis as the rest of the catalogue —
an agent can subscribe to *just* its own mutation footprint via
`subscribe {:topic :trace :filter {:origin :causa-mcp}}`.

### Escape hatch

| Tool | Purpose | Returns |
|---|---|---|
| `eval-cljs` | Send an arbitrary CLJS form for evaluation in the connected browser runtime via `shadow.cljs.devtools.api/cljs-eval`. The escape hatch when the agent needs to peek somewhere the catalogue doesn't cover. Any side-effect the form triggers (a `dispatch`, a `reg-event-db`, etc.) is tagged `:origin :causa-mcp` on the trace bus, the same way the catalogue tools tag their effects. Mirrors `tools/pair2-mcp/`'s `eval-cljs`. | `{:ok? true :value <edn-value>}` on success; `{:ok? false :reason :eval-error :message "..."}` on failure; `:reason :runtime-not-preloaded` if the preload hasn't run. |

Args: `form` (string, required — EDN-encoded CLJS form), `frame`
(string, optional — operating frame for any dispatches the form
triggers), `build` (string, optional).

`eval-cljs` is the **deliberate escape valve**: the catalogue is
closed-set on purpose (seventeen named surfaces alongside this one
escape hatch), but agents occasionally need to reach for something
not yet catalogued. Rather than refuse them and force a workaround through
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
| **Surface** | 9 tools focused on dispatch / eval / hot-swap / trace, plus streaming. | 18 tools focused on inspection (graph, app-db, machine, issues) + restore / reset / dispatch + streaming (incl. `list-subscriptions` diagnostic) + escape hatch + session lifecycle. |
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
**complementary, not competitive** (per
[`tools/causa-mcp/spec/DESIGN-RATIONALE.md`](../../causa-mcp/spec/DESIGN-RATIONALE.md#lock-7--chrome-devtools-mcp-is-complementary-not-competitive)
Lock #7). Causa-MCP owns the *app-domain* (re-frame2 events, subs,
machines, app-db, epochs); Chrome MCP owns the *browser-substrate*
(screenshots, DOM, network, memory, performance). An agent debugging
a real bug typically wants both.

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

## Panel-side consumer

The Causa **MCP Server panel** is the Causa-side mirror of this
server: a sidebar surface that filters the trace-buffer to events
tagged `:tags :origin :causa-mcp` and renders them as a read-only,
scrollable, timestamped ribbon. Where the catalogue above is the
**agent-facing** contract (JSON-RPC over stdio), this section is the
**human-facing** contract (the panel a programmer flips to when they
want to know "what is the AI doing in my app right now?").

The panel reads the trace-buffer; the buffer is the truth. There is
no separate detection loop, no sentinel probe, no health endpoint —
"agent attached" is the boolean "at least one `:origin :causa-mcp`
event has landed this session." This matches the
trace-bus-is-truth posture of every other Causa panel (per
[`013-Trace-Bus.md`](./013-Trace-Bus.md)).

### Panel surface (MUST)

A dedicated sidebar panel keyed `:mcp-server` MUST ship in the
Causa shell's left-rail panel list. The panel renders:

- a **header strip** — title, agent-attached badge, total / rendered
  counts, op-type chip row with histogram counts, a `since-ms`
  numeric input, and a "Clear filters" affordance;
- an inline **Settings sub-pane** — origin colour swatch + the
  cross-panel highlight toggle (per §Cross-panel highlight toggle
  below);
- the **feed** — one row per `:origin :causa-mcp` trace event, in
  reverse chronological order (newest first, parity with the Issues
  ribbon), with `timestamp · op-type · operation · tool · description
  · source-coord` slotted across six columns.

Click a row → the row's `:dispatch-id` is selected via
`:rf.causa/select-dispatch-id` and the active panel pivots to
`:event-detail` for the cascade. Rows whose underlying event carries
no `:dispatch-id` (registry-time emits, lifecycle traces) are
non-clickable.

The registry surface (subscriptions, events, the
`:rf.causa/mcp-server` composite) is enumerated in
[`014-Registry-Catalogue.md`](./014-Registry-Catalogue.md)
§MCP Server panel.

### Locked decisions

Three design questions surfaced during implementation; each is locked
here so the panel is one-shottable from spec alone.

#### Decision (a) — Sidebar panel: MUST

A dedicated `:mcp-server` sidebar panel **MUST** ship, distinct from
the Trace panel.

**Rationale.** The Trace panel is the all-origins surface; an
`:origin :causa-mcp` view forced into it as a filter chip would make
the agent-watching workflow a multi-click setup every session. A
dedicated panel gives the user a single entry point ("what is the
agent doing?") and parallels every other Phase-5 Causa panel
(Issues, Trace, Performance, Subscriptions, …) — each owns its
question.

**Alternatives rejected.**

- *Settings-only* (a flag on the Trace panel) — forces a hunt across
  the Trace panel with the origin filter manually set every session;
  loses the dedicated empty-state copy ("no agent activity observed")
  that orients first-time users.
- *Compose into Trace as a saved-view tab* — a per-tab origin filter
  cannot carry the panel-specific affordances (origin swatch,
  cross-panel highlight toggle, agent-attached badge) without
  bloating the Trace panel's surface.

#### Decision (b) — Origin colour for `:causa-mcp`: SHOULD be cyan-500 `#06B6D4`

The `:origin :causa-mcp` axis **SHOULD** render in cyan-500 `#06B6D4`
across every Causa surface that paints an origin marker (panel
swatches, trace-row hue, causality-graph node-border tint).

**Rationale.** The `:origin` axis already has two locked colours in
[`007-UX-IA.md`](./007-UX-IA.md) §Colour system — indigo `#5570FF`
for `:pair`, lighter cyan `#43C3D0` for `:story` / `:test`. The
`:causa-mcp` axis needs a hue that (1) reads as **cyan-family** so
the AI-driven axes cluster visually against the indigo `:pair` axis
and the warm `:app` axis, and (2) is **distinguishable from the
existing lighter cyan** at a glance. Tailwind cyan-500 `#06B6D4` is
a deeper, more saturated cyan than the existing `:cyan` token; it
clears AA contrast against `bg-2` (`#1B1E24`) and pairs naturally
with the existing accent palette.

`SHOULD` rather than `MUST` because the colour table itself is
owned by [`007-UX-IA.md`](./007-UX-IA.md) §Colour system; this spec
fixes the value for v1 and the follow-on amendment to 007-UX-IA's
Colour-system table promotes the same hex into the shared token map
so every panel that paints an `:origin` swatch reads one source of
truth. Until then the value lives at
`mcp_server_helpers.cljc/causa-mcp-origin-colour`.

**Alternatives rejected.**

- *Re-use indigo `#5570FF`* — collides with `:pair`'s locked hue;
  two AI-driven axes sharing a colour defeats the
  per-origin-at-a-glance affordance.
- *Re-use the existing `#43C3D0` cyan* — collides with `:story` /
  `:test`; a programmer scrubbing a session with a story playing
  back would not be able to tell agent and story dispatches apart.
- *Pick magenta `#E879F9` (the AI co-pilot highlight)* — reads as
  highlight, not as origin; would compete with the co-pilot's own
  affordance.

#### Decision (c) — Bidirectional Causa→agent surface: MUST NOT (v1)

A bidirectional surface — Causa pushing **into** the agent
(attention hints, "look here," session-state callbacks) — **MUST
NOT** ship in v1. The panel is **read-only**: events flow agent → bus
→ panel; nothing flows panel → agent.

**Rationale.** Any "Causa-to-agent" channel is a
**causa-mcp-jar implementation concern**, not a Causa-panel concern.
The agent is the user's delegate over MCP; if the user wants to
nudge the agent they speak to the agent directly through the agent
host (Claude Code, Cursor, Copilot). A panel-injected back-channel
would (1) require a new transport (panel → MCP server, not the other
way round), (2) violate the §Lifecycle and reconnect contract (the
agent host owns the session), and (3) couple the panel's lifecycle
to the agent's in a way that breaks the
"panel works without the MCP server attached" property.

`MUST NOT` rather than `MAY (deferred)` because a back-channel
silently introduced as a follow-on would invalidate audit-trail
guarantees: the `:origin :causa-mcp` tag is a closed-set per
[Lock #4](../../causa-mcp/spec/DESIGN-RATIONALE.md#lock-4--origin-tagging-is-the-convention),
and a panel-driven event tagged that way would be **mis-attributed**
(it came from the user, not the agent).

**Alternatives rejected.**

- *Session-attention hints* (panel highlights → agent prompt
  augmentation) — better served by the agent host's own context-pack
  mechanism; not Causa's surface.
- *"Pause the agent" button* — a stop-the-agent control belongs in
  the agent host, not in Causa. Causa MAY surface the activity feed;
  it MUST NOT issue commands back.

If a future bead motivates re-opening this lock, the change MUST go
through a new `:origin` value (e.g. `:causa-panel`) so the
five-value origin vocabulary in §Origin vocabulary stays
unambiguous — never by overloading `:causa-mcp`.

### Cross-panel highlight toggle

The panel ships a Settings-strip checkbox bound to
`:rf.causa/mcp-origin-filter-enabled?` (default `false`, opt-in).
The flag is a **cross-panel hint**:

- The MCP-Server panel itself MUST honour the flag for its own
  rendering as a no-op (the panel is already origin-filtered by
  construction).
- The Trace, Causality-Graph, and Event-detail panels **MAY**
  consult the flag to **dim or de-emphasise** non-`:origin
  :causa-mcp` rows / nodes / cascades when it is enabled. "Dim"
  here means the existing colour token rendered at reduced opacity
  (`0.45`) plus a non-coloured non-agent badge; the layout MUST NOT
  change.
- No panel MUST consume the flag; consuming it is opt-in per panel.

**Rationale.** A user who wants to follow the agent across the whole
Causa surface ("ride the agent's session") doesn't want to flip a
filter chip in three different panels. A single toggle in the MCP-
Server panel — the agent's home surface — is the natural locus. The
flag is `MAY`, not `MUST`, because the dimming affordance is a
display device, not a semantic gate; a panel that hasn't yet wired
it is still spec-correct, and consumers that DO honour it inherit
the same closed-set `:origin` value enumerated in §Origin vocabulary.

The registry entry (default `false`, sub `:rf.causa/mcp-origin-filter-enabled?`,
event `:rf.causa/toggle-mcp-origin-filter`) is enumerated under
[`014-Registry-Catalogue.md`](./014-Registry-Catalogue.md)
§MCP Server panel.

### Algorithm

The feed projection is a pure data → data pipeline: trace-buffer
events → per-row records → filter-applied rows → empty-state
classification → composite map the view consumes. The pipeline is
JVM-runnable so it can be tested without a DOM (the helpers ns is
`.cljc`, exercised under `clojure -M:test`); the view emits hiccup.
Nothing in the algorithm reaches into the substrate or mutates
runtime state.

#### Input model

The projection consumes three inputs, indexed once per call:

- **`events`** — the raw trace-buffer slice exposed by
  `(rf/trace-buffer)`, per
  [Spec 009 §The trace event model](../../../spec/009-Instrumentation.md#the-trace-event-model). Each
  event MUST carry `:id`, `:time`, `:op-type`, `:operation`, and
  `:tags`; the projection consults `[:tags :origin]`, `[:tags :tool]`,
  `[:tags :event]`, `[:tags :reason]`, `[:tags :sub-id]`,
  `[:tags :fx-id]`, `[:tags :dispatch-id]`, and
  `[:rf.trace/trigger-handler :source-coord]`.
- **`filters`** — the current filter state, a
  `{:op-types #{...} :since-ms <ms>}` map. Both axes are independent;
  `nil` `:since-ms` and empty `:op-types` disable their axes.
- **`now`** — wall-clock ms, helper-injected so unit tests don't
  depend on the system clock. Pure-ish — abstracted via
  `now-ms` so JVM tests can `with-redefs`.

#### Pipeline

1. **Project rows.** Walk `events`; for each event with
   `[:tags :origin] = :causa-mcp`, emit a row record
   `{:id :time :op-type :operation :origin :tool :description
   :source-coord :dispatch-id :raw}`. Non-`:causa-mcp` events MUST
   be dropped at this step; the panel is `:origin`-exclusive by
   construction. The `:description` slot is computed by reading
   `[:tags :event]` → `[:tags :tool]` → `[:tags :reason]` →
   `[:tags :exception-message]` → `[:tags :sub-id]` →
   `[:tags :fx-id]` in priority order (first non-nil wins),
   prepended with `(str operation) " — "`; if every slot is nil the
   row's `:description` is `(str operation)` alone.
2. **Apply filters.** For each row, check `passes-op-type?` (in
   active set, or set is empty) AND `passes-since?` (`:time` within
   `since-ms` of `now`, or either bound is nil). Rows that fail
   either axis are dropped. Both axes are AND-composed; an
   axis-empty row passes the axis trivially.
3. **Reverse for display.** The view orientation is newest-first
   (parity with the Issues ribbon); the projection reverses the
   filtered vector before returning.
4. **Classify empty state.** A three-way enum drives the view's
   empty-state branch:
   - `:no-activity` — no `:causa-mcp` events at all in the buffer
     (`(empty? all)`). The "no agent activity observed" empty state
     paints with pointer copy about origin-tagging discipline.
   - `:no-matches` — events exist but filters hide them all
     (`(seq all)` ∧ `(empty? filtered)`). The "no matches" empty
     state paints with a Clear-filters affordance.
   - `nil` — at least one row survived; render the feed.
5. **Emit composite.** Return `{:rows :total :rendered
   :op-type-counts :distinct-op-types :filters :agent-attached?
   :empty-kind}`. `:total` is the pre-filter count; `:rendered` is
   the post-filter count; `:op-type-counts` is the histogram over
   pre-filter rows (so chip counts don't change when the user
   toggles a chip); `:agent-attached?` is `(boolean (seq all))`.

#### Pull-only detection — no probe

The panel **MUST NOT** issue a separate sentinel probe ("is the
runtime injected?"). The MCP server itself probes its runtime per
§Lifecycle and reconnect; the panel reads the trace-buffer and
treats "saw at least one `:origin :causa-mcp` event" as the proxy
for "agent attached this session." This is simpler than a parallel
detector loop, matches every other Causa panel's posture, and stays
correct under reconnect: a page reload empties the buffer, the
panel returns to `:no-activity`, the next agent action populates
the buffer and the badge flips back.

The detection is **session-scoped**, not real-time-presence-scoped:
a closed agent connection that has performed work this session
still reads as "attached" until the buffer ages the events out.
This is the desired semantics — the panel surfaces "what has the
agent done?", not "is a TCP socket open?"; the latter is a server
concern (per §Lifecycle and reconnect).

#### Distinct op-types — first-seen order

The op-type chip row is populated from the distinct `:op-type`
values present in the *pre-filter* row set, in first-seen
(chronological) order. The first-seen-order rule is MUST: an
alphabetical sort would re-order chips between renders as new
op-types arrive, breaking muscle memory. The histogram counts
on each chip likewise track the pre-filter set so the user can
read "how many of this op-type exist" independently of the
active filter.

#### JVM behaviour

The helpers ns is `.cljc`; the panel-side reader path is unused on
JVM (there is no DOM, no `rf/subscribe`). The pure helpers
(`project-row`, `apply-filters`, `passes-op-type?`, `passes-since?`,
`project-feed`, `agent-attached?`) MUST be total on JVM with
synthesised event vectors so the unit-test target exercises them
without the runtime — the test target `clojure -M:test` is the
canonical gate for the algebra.

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

This doc is the **contract** between Causa-the-panel and its
agent-facing MCP surface — the catalogue prose, the panel-to-tool
map, and the cross-server posture vs. Chrome DevTools MCP. It is
**not** the implementation, and (as of the
[`tools/causa-mcp/spec/`](../../causa-mcp/spec/) scaffold landing)
it is no longer the canonical home for the design decisions either.

The MCP server's per-tool spec scaffold now lives at
[`tools/causa-mcp/spec/`](../../causa-mcp/spec/):

- [`000-Vision.md`](../../causa-mcp/spec/000-Vision.md) — what
  Causa-MCP is, what it isn't, relationships to Causa, pair2-mcp,
  and Chrome DevTools MCP.
- [`Principles.md`](../../causa-mcp/spec/Principles.md) — the
  load-bearing tie-breakers (origin tagging is the convention;
  EDN canonical; closed-set catalogue with `eval-cljs` escape valve;
  stage-marker-independent; degraded boot).
- [`DESIGN-RATIONALE.md`](../../causa-mcp/spec/DESIGN-RATIONALE.md)
  — ten locks (implementation language, transport, connection
  model, origin tagging, catalogue cardinality, npm coord, Chrome
  MCP posture, bencode pin, wire-protocol budget posture,
  size-elision marker shape) with question / options / pick / why /
  date locked.

The four pair2-mcp-shape capability files
([`001-Wire-Protocol.md`](../../pair2-mcp/spec/001-Wire-Protocol.md),
[`002-nREPL-Transport.md`](../../pair2-mcp/spec/002-nREPL-Transport.md),
[`003-Tool-Catalogue.md`](../../pair2-mcp/spec/003-Tool-Catalogue.md),
plus an `API.md`) will land at implementation time and complete the
parameterisation. Until then, the catalogue prose stays here; the
locks are cited above.

The implementation details (shadow-cljs build, the bencode pin, the
specific tools.cljs shape) follow `tools/pair2-mcp/spec/`'s structure
and prose; replicating them here would be redundant.
