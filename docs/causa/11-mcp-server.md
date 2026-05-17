# 11. MCP-server panel

re-frame2 ships three Model Context Protocol servers — [`pair2-mcp`](https://github.com/day8/re-frame2/tree/main/tools/pair2-mcp), [`story-mcp`](https://github.com/day8/re-frame2/tree/main/tools/story-mcp), and [`causa-mcp`](https://github.com/day8/re-frame2/tree/main/tools/causa-mcp). Each exposes one tool's surface as an MCP tool catalogue an AI agent host (Claude Code, Cursor, Copilot) can call.

The MCP panel inside Causa is the discovery surface: an inventory of what's wired, which tools are connected, and what they can do from there. It's the panel you'd open before pointing an external agent at the running app.

## Status, today

- **`pair2-mcp`** — ships. The Causa panel shows its connection status and the running session, if any.
- **`story-mcp`** — ships. Its sister tool [Story](../story/index.md) has its own MCP panel (covered there); from Causa's MCP panel you can see whether story-mcp is up.
- **`causa-mcp`** — ships. Eighteen tools across five bands (Inspection, Mutation, Streaming, Meta, Escape hatch); catalogue at [`tools/causa-mcp/spec/004-Tools-Catalogue.md`](https://github.com/day8/re-frame2/blob/main/tools/causa-mcp/spec/004-Tools-Catalogue.md). This panel hosts the read-surface inventory and the write-gate UI.

The panel renders the tool catalogue as a per-band inventory. It also surfaces the *shared wire vocabulary* under `:rf.mcp/*`: the three servers commit to a common envelope shape, error taxonomy, and id grammar so cross-server agent flows (open Causa, read an epoch, drive a Story variant, capture an assertion) can compose.

## What an MCP-driven Causa session looks like

With `causa-mcp` running, an external agent host calls a tool catalogue rather than driving the UI directly. The shape:

```text
Agent → list-panels → returns: ["event-detail", "time-travel", "trace", ...]
Agent → read-epoch :epoch-id 42 → returns: {:event-id ... :db-after ... :sub-runs [...]}
Agent → query-trace {:op-type :error :since-epoch 40} → returns: [...]
```

Every shape is data; every call is logged on the wire. The agent never reaches into Causa's internal state — it consumes the same surfaces a human would consume via the UI. That's the contract that lets Causa, Story, and the pair tool coexist as parallel agent surfaces without coordinating internals.

## Write gates

`pair2-mcp` and `story-mcp` both have a write surface (`hot-swap-handler`, `register-variant`, etc.) gated behind a startup `--allow-writes` flag. `causa-mcp` follows the same pattern — read-shaped tools are always on; write-shaped tools require explicit gating.

The MCP panel inside Causa surfaces the write-gate state for every connected server. You can see at a glance whether the agent host the user just connected has write authority or read-only authority. (Causa itself has no writes today; this surface is for the sibling servers.)

## Shared vocabulary

The three MCP servers share a wire vocabulary under `:rf.mcp/*`. Three loaded keywords:

- `:rf.mcp/tool-id` — every tool's id is namespaced and stable across servers.
- `:rf.mcp/envelope` — the request/response envelope shape (`:request-id`, `:tool-id`, `:args`, `:result`, `:error`).
- `:rf.mcp/error` — the error taxonomy (`:rf.mcp.error/not-allowed`, `:rf.mcp.error/unknown-tool`, `:rf.mcp.error/schema-violation`, etc.).

Cross-server vocabulary conformance is gated in CI by [`tools/mcp-conformance/`](https://github.com/day8/re-frame2/tree/main/tools/mcp-conformance) — a tool that registers an envelope with non-conformant shapes fails the build. The vocabulary's stability is a load-bearing property of the triplet.

## When the panel matters today

Two things you'd open it for *now*, before `causa-mcp` lands:

1. **Sanity-check the pair tool connection.** If `re-frame-pair2` reports "no session," the panel confirms whether `pair2-mcp` is reachable, which side of the connection failed, and what the last successful handshake looked like.
2. **Drive a story-mcp interaction from the running app's context.** When an agent driving Story wants to know "what's the user's current `app-db` state?" before scaffolding a variant, the same agent host can connect to `pair2-mcp` first, read the state, and feed it to a Story `register-variant` call. The Causa MCP panel makes the bridge visible.

Three things it will matter for once `causa-mcp` is live:

1. **AI agents reading Causa's surfaces without driving the panel UI.** Headless agent flows ship today via `re-frame-pair2`; Causa's surfaces are an additional read-source.
2. **Conformance recording.** A captured agent session can be replayed against a re-frame2 build to assert "the panel surfaces this agent expected to see are still there." The MCP envelope is the wire-level contract that survives.
3. **Cross-tool composition.** The agent host can route across pair → causa → story in one session, using the shared envelope for context-passing.

This is the chapter that's most *spec-as-of-shipping*. The next pass through the tutorial will rewrite it once `causa-mcp` is on disk.

---

That's the tour. You've seen:

- The panel walkthrough — Event detail, Time travel, Trace, Click-to-source, Schemas, Hydration, Machines, App-DB, MCP.
- The single observation surface they all share.
- The contracts the framework commits to — trace bus, epoch records, source coords, machine indices.
- The escape hatches — pair tools for writes, the Performance API for production timing, the off-box trace pipeline for APM.

If you want to revisit the architectural pitch end-to-end, return to the [welcome page](index.md). If you want to *drive* the app rather than observe it, head to [`re-frame-pair2`](../skills/re-frame-pair2.md). If you want to catalogue your components' visible states, head to [Story](../story/index.md).

Or just close this tab, open your app, press `Ctrl+Shift+C`, and click around. That's how Causa is meant to be learned.
