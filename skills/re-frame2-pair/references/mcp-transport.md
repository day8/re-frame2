# MCP transport — preferred path

The re-frame2-pair ops are reachable two ways:

1. **MCP server** (preferred) — a persistent stdio JSON-RPC server
   that holds one nREPL socket open for the whole session. Per-op
   latency ~5–50ms.
2. **Bash shims** (legacy, deprecated) — `scripts/*.sh` under this
   skill, each spawning bash → babashka → fresh nREPL connect per
   call. Per-op latency ~700ms. Kept for back-compat.

Pick the MCP server whenever it's available. If the agent host
doesn't have it configured, fall back to the bash shims; the
op semantics are identical.

## Install / configure (one-time)

```bash
npm install -g @day8/re-frame2-pair-mcp
```

Then add to your Claude Code settings:

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
1. `$SHADOW_CLJS_NREPL_PORT`
2. `target/shadow-cljs/nrepl.port`
3. `.shadow-cljs/nrepl.port`
4. `.nrepl-port`

## Bash-shim → MCP tool mapping

| Bash shim                         | MCP tool       | Same args? |
|-----------------------------------|----------------|------------|
| `scripts/discover-app.sh`         | `discover-app` | yes (optional `build`) |
| `scripts/eval-cljs.sh '<form>'`   | `eval-cljs`    | pass form as `{form: "..."}` |
| `scripts/dispatch.sh '<event>' --sync --frame :foo` | `dispatch` | `{event: "...", sync: true, frame: ":foo"}` |
| `scripts/trace-window.sh 1000`    | `trace-window` | `{ms: 1000}` |
| `scripts/watch-epochs.sh --event-id-prefix :cart` | `watch-epochs` | `{pred: {"event-id-prefix": ":cart"}}` (pull-mode — call repeatedly with `since-id`) |
| `scripts/tail-build.sh --probe '<form>'` | `tail-build` | `{probe: "..."}` |
| _(none — MCP-only)_                | `snapshot`     | `{frames: "all"\|[":rf/default"...], include: ["app-db","sub-cache","machines","epochs","traces"], path: "[:cart :items]"}` — default `:app-db` mode is **`:summary`** (tree-summary marker, not the full value); pass `path` to slice. Root `path: "[]"` opts back into the full slice. See [`ops.md` §Read](ops.md#read). |
| _(none — MCP-only)_                | `get-path`     | `{path: "[:cart :items 0 :sku]", frame: ":rf/default"}` — targeted-read primitive. Returns `{ok? true :exists? true :value <subtree>}` or `{ok? false :reason :path-not-found :deepest-valid-prefix [...]}`. `:exists?` distinguishes a path that legitimately points at `nil` from a missing path. |
| _(none — MCP-only, push-mode)_     | `subscribe`    | `{topic: "trace"\|"epoch"\|"fx"\|"error", filter: {...}, max-events: 0, max-ms: 0}` — emits `notifications/progress` ticks; resolves on cancel / `max-events` / `max-ms` / `unsubscribe`. See `references/streaming-subscriptions.md`. |
| _(none — MCP-only)_                | `unsubscribe`  | `{sub-id: "<uuid>"}` — idempotent close. |
| _(none — MCP-only)_                | `list-subscriptions` | `{}` (or `{topic: "epoch"}` / `{sub-id: "<uuid>"}`) — list active streaming subscriptions with `:queue-depth`, `:dropped-events`, `:overflow-reason` without draining queues. Diagnostic for "is my probe still alive?". |
| _(none — MCP-only)_                | `list-handlers` | `{kind: "event"}` — discovery: every id registered under one kind. `{:ok? true :kind :event :ids [...] :count n}`, ids sorted. Kinds: `event` / `sub` / `fx` / `cofx` / `view` / `frame` / `route` / `flow` / `head` / `error-projector` / `machine`. Prefer over a `registrar-list` eval. See [`ops.md` §Read](ops.md#read). |
| _(none — MCP-only)_                | `handler-meta` | `{kind: "event", id: ":user/login"}` — drill: registration metadata for one id (source-coord + `:doc` + `:tags` + an `:rf.source/uri` clickable jump-to-editor link). `{:ok? false :reason :not-registered ...}` on a miss. Composite sub ids pass as the vector-string form. Prefer over a `registrar-describe` eval. See [`ops.md` §Read](ops.md#read). |
| _(none — MCP-only)_                | `get-re-frame2-pair-instructions` | `{}` — inline agent-onboarding text (tool catalogue, EDN posture, tagged-mutation conventions, streaming semantics, wire pipeline). No nREPL round-trip. Optionally call at session start to orient before the first real op. |

The `subscribe` / `unsubscribe` pair is the **push-mode** replacement for `watch-epochs`. Each batch of matching events arrives as a `notifications/progress` notification correlated by the call's `progressToken`; the tool's final result is a summary. Use `subscribe` whenever you want a live narration; fall back to `watch-epochs` (pull-mode) when the agent host doesn't surface progress notifications to the model.

(`inject-runtime` is gone — the runtime ships into the app via
shadow-cljs `:devtools :preloads`. See `SKILL.md` §Setup.)

## When to use `snapshot` vs the per-op reads

`snapshot` is the **coarse-grained mega-op** for investigate-X
workflows that would otherwise chain 5-10 individual reads
(`app-db/snapshot` + `subs/cache` + `machines/list` + `epoch/history`
+ `trace/buffer`, etc.). Each per-op read is its own bencode
round-trip plus Claude-think latency; `snapshot` collapses the whole
thing into one round-trip.

Use `snapshot` when:

- You're starting a post-mortem and don't yet know which slice
  carries the answer.
- You want a fixed reference point — same call, same shape, several
  hypotheses to test against it.
- You need cross-slice context (e.g. "what was app-db at the same
  moment the trace ring shows this event?").

Use the per-op reads when:

- You know exactly which slice you want and only need that slice.
- You want a path-scoped value (`runtime/app-db-at [:deep :path]`).
- You want a derived projection (`runtime/find-where`, `cascade-of`,
  `last-pair-epoch`) — `snapshot` returns raw history; projections
  still need their dedicated forms via `eval-cljs`.

`snapshot` accepts `include` to subset the slices —
`{include: ["app-db","epochs"]}` returns just those two — and
`frames` to pick a subset of frame-ids — `{frames: [":stories"]}`.

**`:app-db` slice modes.** The `:app-db` slice no longer defaults to the full value. Two modes:

- **`:summary` (default, no `path`)** — the `:app-db` slot is a `{:rf.mcp/summary {:type :map :keys [...] :count ... :bytes ...}}` marker carrying the top-level shape without committing the token budget. Map-key lists over 64 entries get truncated and flagged `:keys-truncated? true` so the marker itself can never blow the wire cap.
- **`:path-sliced` (with `path`)** — the slot is `(get-in db path)`. Out-of-range paths surface per-frame in a top-level `:path-not-found` map with `:deepest-valid-prefix` so the agent can re-aim without a binary search.
- **Root path `path: "[]"`** — explicit request for the full `:app-db`, equivalent to the legacy default. The wire cap is then the backstop.

The other slices (`:sub-cache`, `:machines`, `:epochs`, `:traces`) pass through unchanged.

Use `get-path` (next section) when you already know the addressed subtree — it's a single-slice round-trip rather than the multi-slice composition `snapshot` does.

## Preload probe (no inject step)

Both transports handle preload verification the same way: every op
that needs the in-browser runtime first probes
`js/globalThis.__re_frame2_pair_runtime` — the load-time marker the
preload installs. If the marker is missing the op refuses with
`{:ok? false :reason :runtime-not-preloaded :hint "..."}`. A full
page refresh drops the runtime, but the preload re-installs it on
the next bundle load; no manual reconnect step.

## When the MCP server is degraded

If shadow-cljs isn't running yet when the MCP server boots, it still
answers `tools/list` but every `tools/call` returns

```edn
{:ok? false :reason :nrepl-port-not-found :hint "..."}
```

Start shadow-cljs and retry — the server picks up the port on the
next call.

## Why two transports

The MCP server is the structural shift. The bash shims stay for
back-compat — older skill installations that haven't migrated, agent
hosts without MCP support, ad-hoc shell scripting. Both consume the
same `re-frame2-pair.runtime` namespace; the runtime contract is
transport-agnostic.
