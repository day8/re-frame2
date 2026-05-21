# MCP surface

This chapter is about the **Story ↔ MCP boundary** — the surfaces Story exposes for the separate `tools/story-mcp/` jar to consume when an agent (Claude / Cursor / Copilot) drives Story over JSON-RPC. The core of it is **two parallel surface bundles** — Story's public *read* primitives (the registry-query family plus `run-variant` / `snapshot-identity` / `variant->edn`) and Story's public *write* primitives (the `*`-suffix registration helpers plus `unregister!` / `clear-kind!` / `clear-all!`). The MCP jar consumes both; Story core stays free of stdio / JSON-RPC concerns.

The architectural split is principled: Story core never depends on `tools/story-mcp/`. The MCP jar attaches to a running app via the Tool-Pair primitives — nREPL-attached process, the agent reads the registry over the wire, runs variants, reads results back. The MCP server itself runs in the agent's process; the Story runtime runs in the app.

```
┌─────────────────────────────┐         ┌───────────────────────────┐
│ Agent (Claude / Cursor /    │ stdio + │ tools/story-mcp/          │
│ Copilot)                    │ JSON-RPC│ day8/re-frame2-story-mcp  │
└─────────────────────────────┘ <────── │ - tool definitions         │
                                       │ - schema validation         │
                                       │ - bridges to ↓              │
                                       └──────┬─────────────────────┘
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

## Public read primitives

Story's core jar exposes these without depending on stdio / JSON-RPC. The MCP jar consumes them via the Tool-Pair bridge. The full per-fn contracts live in [Registration](registration.md) and [Runtime](runtime.md) — this section lists which surfaces are consumed by the MCP read path.

| Fn | Signature | MCP tool |
|---|---|---|
| `registrations` | `(registrations kind)` | `list-stories` / `list-variants` / `list-workspaces` / `list-tags` / `list-modes` |
| `handler-meta` | `(handler-meta kind id)` | `get-story` / `get-variant` / `get-workspace` / `get-decorator` body reads |
| `ids` | `(ids kind)` | Discovery |
| `registered?` | `(registered? kind id)` | Discovery |
| `variants-of` | `(variants-of story-id)` | `list-variants` filtered to one story |
| `variants-with-tags` | `(variants-with-tags qtags)` | Tag-filtered variant queries |
| `variant->edn` | `(variant->edn variant-id)` | `get-variant` — the full body as serialisable EDN |
| `workspace->edn` | `(workspace->edn workspace-id)` | `get-workspace` — the full body as serialisable EDN |
| `list-tags` | `(list-tags)` | Tag inventory |
| `list-modes` | `(list-modes)` | Mode inventory |
| `canonical-tags` | Var (set) | The seven canonical tags surfaced to agents |
| `run-variant` | `(run-variant variant-id opts)` | `run-variant` — materialise the variant, return result |
| `reset-variant` | `(reset-variant variant-id opts)` | `reset-variant` |
| `watch-variant` | `(watch-variant variant-id callback)` | `watch-variant` — live updates |
| `snapshot-identity` | `(snapshot-identity variant-id opts)` | `snapshot-identity` — content-hash for visual-regression keying |
| `read-assertions` | `(read-assertions variant-id)` | `get-assertions` — the assertion vector after play |
| `assertions-passing?` | `(assertions-passing? result)` | `tests-passing?` — single-boolean projection |
| `canonical-assertion-ids` | `(canonical-assertion-ids)` | Discovery |
| `variant-share-url` | `(variant-share-url variant-id base-url opts)` | `share-variant` — QR-code surface |
| `registered-substrates` | `(registered-substrates)` | CLJS-only. Substrate inventory. |

The MCP jar wraps each public Story fn in a JSON-RPC tool. The wrapping is mechanical: pull the args off the JSON-RPC request, call the Story fn, project the result through the wire-elision walker (see below), serialise the result back.

## Wire-elision boundary — core is real-values-in, real-values-out

Story core returns **marks-as-data**: the registered bodies and per-frame snapshots travel unchanged across the read primitives above, with `:sensitive` / `:large` declarations carried alongside as declarative metadata. The wire-elision substitution to `:rf/redacted` / `:rf/large` happens at the **MCP jar's egress boundary**, NOT in Story core — every tool-response payload the MCP jar emits is passed through `re-frame.elision/elide-wire-value` before it crosses the JSON-RPC wire.

The split keeps Story's read surface composable:

- **Crosses the wire elided** — every payload returned by a story-mcp tool (`get-variant`, `run-variant`, `snapshot-identity`, registry reads, recorder output). The MCP jar is the wire owner; egress is where elision lands.
- **Crosses the wire raw** — nothing the MCP jar emits. A future in-process consumer that calls the read primitives directly (without going through the MCP jar) gets real values; this is by design so on-box devtool surfaces can read the same data unredacted.

Story core's contract is **real-values-in, real-values-out**; elision is the MCP jar's responsibility. The same split governs Causa's runtime seam — the framework / Causa / Story emit; tools consume; the contract is the data shape, not the call shape.

## Public write primitives

The MCP jar's gated agent-write surface routes through these. Story always exposes the helpers; the MCP jar decides whether to surface them to agents.

| Fn | Signature | MCP tool |
|---|---|---|
| `reg-story*` | `(reg-story* id body)` | `register-story` |
| `reg-variant*` | `(reg-variant* id body)` | `register-variant` — the headline write tool (agent generates a variant from canvas interaction) |
| `reg-workspace*` | `(reg-workspace* id body)` | `register-workspace` |
| `reg-mode*` | `(reg-mode* id body)` | `register-mode` |
| `reg-story-panel*` | `(reg-story-panel* id body)` | `register-story-panel` |
| `reg-decorator*` | `(reg-decorator* id body)` | `register-decorator` |
| `reg-tag*` | `(reg-tag* id body)` | `register-tag` |
| `unregister!` | `(unregister! kind id)` | `unregister` |
| `clear-kind!` | `(clear-kind! kind)` | `clear-kind` (rare; mostly used by test fixtures) |
| `clear-all!` | `(clear-all!)` | `clear-all` (very rare; used by test isolation) |

The MCP jar's `:rf.story-mcp/allow-writes?` config gate determines whether these tools are advertised over the agent surface. Dev builds default the gate to `false`; teams that want agent-driven variant generation flip it explicitly. Story core's helpers are unchanged either way — Story always exposes the surface, the MCP jar decides whether to surface it to agents.

## What ships in the MCP jar vs. Story core

A clean split, no overlap:

| Surface | Where |
|---|---|
| `initialize` / `tools/list` / `tools/call` / `ping` / `shutdown` dispatcher | `tools/story-mcp/` |
| Newline-delimited JSON-RPC over stdio | `tools/story-mcp/` |
| Cheshire (or equivalent JSON codec) | `tools/story-mcp/` |
| Protocol-version pin | `tools/story-mcp/` |
| The 16-tool registry (Dev / Docs / Testing / Write) | `tools/story-mcp/` |
| The `:rf.story-mcp/allow-writes?` gate | `tools/story-mcp/` |
| The seven `reg-*` macros (and their `*`-suffix runtime helpers) | `tools/story/` |
| The four-phase runtime | `tools/story/` |
| The render shell (when CLJS is the runtime) | `tools/story/` |
| The trace bus and panel registrations | `tools/story/` |
| The snapshot-identity computation | `tools/story/` |
| The canonical seven `:rf.assert/*` events | `tools/story/` |

`tools/story-mcp/` is a thin adapter: takes JSON-RPC requests, calls Story's public CLJS / CLJC functions, serialises responses back over stdio. Zero agent-specific logic lives in `tools/story/`.

## Stage markers — independent cadence

Story's own `re-frame.story/stage` advances as Story's surface extends. The MCP jar carries its own `re-frame.story-mcp.config/stage = :mcp` — the two artefacts have **independent stage progression** and ship at **independent cadence**. A consumer reading the stage marker reads it from the right artefact: Story's surface from `re-frame.story/stage`; the MCP jar's surface from `re-frame.story-mcp.config/stage`.

## Late-bind `reg-story-panel` contract

The `reg-story-panel` surface is the single hook through which tooling embeds itself into the Story chrome. Five rules govern panel hosting (the full statement lives in the [`003-Render-Shell.md`](https://github.com/day8/re-frame2/blob/main/tools/story/spec/003-Render-Shell.md) spec doc); the summary as it pertains to the MCP surface:

1. **`:render` is a `:view` id.** Late-bind via `(rf/view ...)`. The actual view can register from a different artefact than the panel registration itself.
2. **Placement is one of five slots** — `:right` / `:left` / `:bottom` / `:top` / `:modal`.
3. **Visibility flows through `:panel-visibility`** — the shell's on/off switch keyed by panel id.
4. **Author calls `reg-story-panel` from anywhere.** Built-in panels register from the canonical-vocabulary auto-install; third-party tooling (Causa's epoch view, future statechart-viz panels) registers from its own boot.
5. **The Causa embed is the canonical late-bind example.** A stub view ships with Story; Causa registers the live view under the same `:rf.story.panel/epoch-view` id when present; the shell picks Causa's view automatically.

The MCP jar consumes neither the panel host nor the view ids directly; it consumes the registry data. But the same contract is what allows the *MCP* to expose new panels to Story-the-tool via agent action: an agent calls `register-variant` *plus* `register-story-panel` (when the write surface is open) to ship a panel.

## Why a separate jar

The MCP server depends on transport machinery (stdio adapter, JSON-RPC framing, asynchronous-handler runtime) that the vast majority of Story consumers never load. Splitting at the jar boundary keeps the Story core lean and lets the MCP surface evolve on its own cadence. The pattern mirrors `tools/causa/` vs. `tools/re-frame2-pair-mcp/` (the runtime seam vs. the MCP server that consumes it).

A typical agent's interaction with Story over the MCP surface:

```
1. list-stories                  — registrations :story
2. list-variants                 — registrations :variant
3. get-variant :s.c/at-five      — variant->edn :s.c/at-five
4. run-variant :s.c/at-five      — run-variant :s.c/at-five {}
5. get-assertions :s.c/at-five   — read-assertions :s.c/at-five
6. snapshot-identity :s.c/at-five — snapshot-identity :s.c/at-five {}
7. register-variant :s.c/at-six  — reg-variant* :s.c/at-six body (if write surface gated open)
```

Every read crosses the wire elided; every write goes through the gate. Story core stays composable, the MCP jar stays focused, the contract is the data shape on both sides.

## See also

- [Registration](registration.md) — the `*`-suffix runtime helpers the write surface consumes.
- [Runtime](runtime.md) — `run-variant` / `snapshot-identity` / `read-assertions` and the four-phase lifecycle the MCP `run-variant` tool calls.
- [Play scripts](play-script.md) — the `:play-script` body shape an agent emits via the `register-variant` write tool.
- [Reference](reference.md) — the full symbol table for `Ctrl-F` use.
- [Framework API — Schemas and data classification](../../api/08-schemas.md) — `elide-wire-value`, the framework primitive the MCP jar's egress boundary calls.
- [Causa runtime seam](../../causa/api/runtime-seam.md) — the parallel Tool-Pair contract for Causa-the-panel; same emit-and-consume discipline.
- Normative spec — [`tools/story-mcp/spec/`](https://github.com/day8/re-frame2/tree/main/tools/story-mcp/spec) (the MCP jar's own spec folder: wire protocol, tool registry, write-surface gating).
