# Story — MCP Surface

> The boundary between Story (`day8/re-frame2-story`) and the
> separate-jar agent surface (`day8/re-frame2-story-mcp`, at
> `tools/story-mcp/`). What surfaces Story exposes for the MCP jar to
> consume; the late-bind `reg-story-panel` contract Story uses for
> tooling embeds. The wire-protocol / tool-registry details live in
> [`tools/story-mcp/spec/`](../../story-mcp/spec/).

## Architecture

```
┌─────────────────────────────┐         ┌───────────────────────────┐
│ Agent (Claude / Cursor /    │ stdio + │ tools/story-mcp/          │
│ Copilot)                    │ JSON-RPC│ day8/re-frame2-story-mcp  │
└─────────────────────────────┘ <────► │ - tool definitions         │
                                       │ - schema validation        │
                                       │ - bridges to ↓             │
                                       └──────┬────────────────────┘
                                              │ in-process or pair-style
                                              ▼
                                       ┌───────────────────────────┐
                                       │ tools/story/ runtime      │
                                       │ - registry queries        │
                                       │ - run-variant             │
                                       │ - snapshot-identity       │
                                       │ - variant->edn            │
                                       └───────────────────────────┘
```

The MCP server connects to a running app's story runtime via the
existing Tool-Pair primitives (see
[`spec/Tool-Pair.md`](../../../spec/Tool-Pair.md)): nREPL-attached
process, the agent reads the registry over the wire, runs variants,
reads results back. The MCP server itself runs in the agent's
process; the story runtime runs in the app.

## Story's public read primitives (consumed by MCP)

```clojure
;; Public read primitives, in re-frame.story
(handlers kind)                                  ; spec/001-mirror; per Story kind
(handler-meta kind id)
(ids kind) (registered? kind id)
(variants-of story-id) (variants-with-tags qtags)
(variant->edn variant-id) (workspace->edn workspace-id)
(list-tags) (list-modes) canonical-tags
(run-variant variant-id opts)                    ; see 002-Runtime
(reset-variant variant-id opts) (watch-variant variant-id callback)
(snapshot-identity variant-id opts)
(read-assertions variant-id) (assertions-passing? result)
(canonical-assertion-ids)
(variant-share-url variant-id base-url opts)     ; QR share (005-SOTA-Features)
(registered-substrates)                          ; CLJS-only
```

Story's core jar exposes these without depending on stdio / JSON-RPC.
The MCP jar consumes them via the Tool-Pair bridge.

## Story's public write primitives (consumed by MCP write surface)

```clojure
;; Public write primitives — used by MCP's gated write surface
;; AND by hot-reload tooling / fixture loaders that synthesise registrations.
(reg-story*       id body)   (reg-variant*     id body)
(reg-workspace*   id body)   (reg-mode*        id body)
(reg-story-panel* id body)   (reg-decorator*   id body)
(reg-tag*         id body)
(unregister! kind id) (clear-kind! kind) (clear-all!)
```

The MCP `register-variant` / `unregister-variant` tools route through
these; the MCP jar's gating gates *its own* exposure of these, not
Story's surface. Story always exposes the helpers; the MCP jar
decides whether to surface them to agents.

## Why a separate jar

The MCP server depends on transport machinery (stdio adapter,
JSON-RPC framing, asynchronous-handler runtime) that the vast
majority of Story consumers never load. Splitting at the jar boundary
keeps the Story core lean and lets the MCP surface evolve on its own
cadence. The pattern mirrors `tools/machines-viz/` vs.
`tools/machines-viz-mcp/` (per [`tools/README.md`](../../README.md)).

See [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) §separate-mcp-jar
for the full reasoning.

## Late-bind `reg-story-panel` contract

The `reg-story-panel` surface is the single hook through which
tooling embeds itself into the Story chrome. Five rules govern panel
hosting; the full statement lives in
[`003-Render-Shell.md`](003-Render-Shell.md) §Panel registration
contract.

Summary as it pertains to the MCP surface:

1. **`:render` is a `:view` id.** Late-bind via `(rf/view ...)`. The
   actual view can register from a different artefact.
2. **Placement is one of five slots.** `:right` / `:left` /
   `:bottom` / `:top` / `:modal`.
3. **Visibility flows through `:panel-visibility`.** The shell's
   on/off switch keyed by panel id.
4. **Author calls `reg-story-panel` from anywhere.** Built-in panels
   register from `install-canonical-vocabulary!`; third-party tooling
   (Causa's epoch view, future statechart-viz panels) registers from
   its own boot.
5. **The Causa embed is the canonical late-bind example.** Stub view
   ships with Story; Causa registers the live view under the same
   `:rf.story.panel/epoch-view` id when present; shell picks
   Causa's view automatically.

The MCP jar consumes neither the panel host nor the view ids
directly; it consumes the registry data. But the same contract is
what allows the *MCP* to expose new panels to Story-the-tool via
agent action: an agent calls `register-variant` *plus*
`register-story-panel` (when the write surface is open) to ship a
panel.

## What ships in the MCP jar vs. Story core

Story core deliberately carries **NO** stdio / JSON-RPC dependency.
The MCP jar:

- Declares Cheshire (or equivalent JSON codec).
- Owns the `initialize` / `tools/list` / `tools/call` / `ping` /
  `shutdown` dispatcher.
- Owns the newline-delimited JSON-RPC over stdio transport.
- Owns the protocol-version pin.
- Owns the 16-tool registry (Dev / Docs / Testing / Write).
- Owns the `:rf.story-mcp/allow-writes?` config gate.

Story core owns:

- The seven `reg-*` macros (and their `*`-suffix runtime helpers).
- The four-phase runtime.
- The render shell (when CLJS is the runtime).
- The trace bus and panel registrations.
- The snapshot-identity computation.
- The canonical seven `:rf.assert/*` events.

Stage 7's `tools/story-mcp/` is a thin adapter: takes JSON-RPC
requests, calls Story's public CLJS / CLJC functions, serialises
responses back over stdio. Zero agent-specific logic lives in
`tools/story/`.

## Stage markers — independent cadence

Story's own `re-frame.story/stage` advances as Story's surface
extends (e.g. `:sota-features` after Stage 6). The MCP jar carries
its own `re-frame.story-mcp.config/stage = :mcp` — the two artefacts
have **independent stage progression** and ship at **independent
cadence** per [`tools/README.md`](../../README.md).

## Cross-references

- [`tools/story-mcp/spec/`](../../story-mcp/spec/) — the MCP jar's
  own spec folder (wire protocol, tool registry, write-surface
  gating, design rationale).
- [`spec/Tool-Pair.md`](../../../spec/Tool-Pair.md) — the runtime
  contract for pair-shaped AI tools.
- [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) §separate-mcp-jar —
  why the split.
