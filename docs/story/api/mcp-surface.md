# MCP surface

This chapter is about the **Story ↔ MCP boundary** — the surfaces Story exposes for the separate `tools/story-mcp/` jar to consume when an agent (Claude / Cursor / Copilot) drives Story over JSON-RPC. The core of it is **two parallel surface bundles** — Story's public *read* primitives (the registry-query family plus `run-variant` / `snapshot-identity` / `variant->edn`) and Story's public *write* primitives (the `*`-suffix registration helpers plus `unregister!` / `clear-kind!` / `clear-all!`). The MCP jar consumes both; Story core stays free of stdio / JSON-RPC concerns.

The architectural split is principled: Story core never depends on `tools/story-mcp/`. The normative statement of everything below is [`tools/story/spec/006-MCP-Surface.md`](https://github.com/day8/re-frame2/blob/main/tools/story/spec/006-MCP-Surface.md) (Story's side of the boundary) and [`tools/story-mcp/spec/002-Tool-Registry.md`](https://github.com/day8/re-frame2/blob/main/tools/story-mcp/spec/002-Tool-Registry.md) (the jar's side); this chapter is the orientation read.

## Host execution model — one JVM, no browser bridge

The stdio server and the Story runtime it calls share **one JVM process**. The agent launches the server as a subprocess, and every tool call lands directly on `re-frame.story`'s public CLJC surface *in that same process*. Registrations, runs, and snapshots are process-local. Neither jar carries an nREPL, a socket, or any JVM-to-browser transport.

```
┌─────────────────────────────┐         ┌───────────────────────────┐
│ Agent (Claude / Cursor /    │ stdio + │ tools/story-mcp/          │
│ Copilot)                    │ JSON-RPC│ day8/re-frame2-story-mcp  │
└─────────────────────────────┘ <────►  │ - tool definitions        │
                                        │ - schema validation       │
                                        │ - wire egress             │
                                        └──────┬────────────────────┘
                                               │ direct, in-process calls
                                               │ (same JVM)
                                               ▼
                                        ┌───────────────────────────┐
                                        │ tools/story/ runtime      │
                                        │ - registry queries        │
                                        │ - run-variant             │
                                        │ - snapshot-identity       │
                                        │ - variant->edn            │
                                        └───────────────────────────┘
```

### Two surfaces, one live door

Agent access to Story splits across two artefacts, by host:

- **Story-MCP (`tools/story-mcp/`) — the headless JVM surface.** Docs discovery, authoring guidance, gated writes, and headless variant runs against the Story registry *in the server's own process*.
- **The pair (`tools/re-frame2-pair-mcp/`) — the live browser host.** Inspecting or driving the Story runtime inside a *running app* goes through the pair's nREPL / `cljs-eval` channel, which evaluates `re-frame.story/*` calls in the attached CLJS runtime and so reaches the registrations the browser heap actually holds. See the [re-frame2-pair skill](../../skills/re-frame2-pair.md).

Story-MCP does not own — and must not imply — a live-app connection. There is deliberately **one live door**, and it is the pair; Story ships no second transport.

The consequence is visible in the tool surface. CLJS-only state — registered browser substrates, the a11y-violations atom, and above all the registry of a running browser app — is not reachable from the JVM server. `list-substrates` and `read-a11y-violations` therefore return an explicit capability-unavailable error (`isError true`, `:rf.error/story-mcp-capability-unavailable`) rather than a false-empty `[]`. **The host reports that it cannot look; it never reports that the answer is empty.**

## The tool registry at a glance

The jar exposes **19 tools** across four categories. This is the orientation map only — the per-tool wire shape, input schema and result contract are the registry spec's, and the canonical name list ships as the shared fixture `tools/story-mcp/test/fixtures/tool-names.json`, which the JVM and Node test corpora both compare against so spec text and running registry cannot drift.

| Category | Tools |
|---|---|
| **Dev** (3) | `get-story-instructions`, `preview-variant`, `list-substrates` |
| **Docs** (10) | `list-stories`, `get-story`, `get-variant`, `variant->edn`, `list-tags`, `list-modes`, `list-decorators`, `list-assertions`, `get-docs-markdown`, `explain-variant` |
| **Testing** (4) | `run-variant`, `snapshot-identity`, `read-failures`, `read-a11y-violations` |
| **Write** (2, gated) | `register-variant`, `unregister-variant` |

`run-variant`, `read-failures` and `preview-variant` return the **same unified run-result** the human Story UI reads — a top-level `:status` in `#{:pass :fail :cannot-run :error}`, unified assertion records, `:checks`, and the evidence slots. There is no parallel agent-only verdict vocabulary, and `:cannot-run` is a distinct third outcome an agent must handle as "not runnable here" rather than as a failure.

## Public read primitives

Story's core jar exposes these without depending on stdio / JSON-RPC. The MCP jar consumes them via direct, in-process calls; a pair-attached agent consumes the *same* surface by evaluating the same calls in the live browser runtime. The full per-fn contracts live in [Registration](registration.md) and [Runtime](runtime.md).

```clojure
;; Public read primitives, in re-frame.story
(registrations kind)  (handler-meta kind id)
(ids kind)            (registered? kind id)
(variants-of story-id)             (variants-with-tags qtags)
(variant->edn variant-id)          (workspace->edn workspace-id)
(list-tags) (list-modes) canonical-tags
(run-variant variant-id opts)      (reset-variant variant-id opts)
(watch-variant variant-id callback)
(snapshot-identity variant-id opts)
(read-assertions variant-id)       (assertions-passing? result)
(canonical-assertion-ids)
(variant-share-url variant-id base-url opts)
(registered-substrates)            ; CLJS-only
```

Story's read surface is deliberately wider than the tool registry: several of these primitives have no MCP tool of their own and exist for in-process consumers, the Story UI, and pair-attached evaluation. **Read this list as Story's public API, not as a tool map** — the tool map is the table above.

## Wire-egress boundary — core is real-values-in, real-values-out

Story core returns **marks-as-data**: registered bodies and per-frame snapshots travel unchanged across the read primitives, with `:sensitive` / `:large` declarations carried alongside as declarative metadata. The substitution to `:rf/redacted` / `:rf.size/large-elided` happens at the **MCP jar's egress boundary**, not in Story core.

**Egress is not uniform.** What elision *means* at that boundary depends on the payload class, because the threat model scopes the marks to the *observed runtime*, not to authored registration data:

- **Runtime / captured value — path-projected by default.** `:app-db`, `:snapshot`, `:effective-args`, evidence slots and assertion records on `preview-variant` / `run-variant` / `read-failures`, plus `read-a11y-violations`'s `:violations`. A value at a declared-`:sensitive` path becomes `:rf/redacted`; a value at a declared-`:large` path becomes the `:rf.size/large-elided` marker. Projection is **path-based**: a value *re-keyed* to a position the classification path cannot reach ships raw, by design. To redact a value a derived tree re-surfaces, classify its app-db path.
- **Author-published static metadata — intentionally public, not scrubbed.** `get-story` / `get-variant` / `variant->edn` bodies, the `list-*` enumerations, `get-docs-markdown`, and the whole of `explain-variant`'s `:explain` map. These are registration-time authoring prose, not runtime or user state; scrubbing them would degrade discovery without protecting a secret.
- **Crosses the wire raw.** An in-process consumer that calls the read primitives directly, without going through the MCP jar, gets real values — so on-box devtool surfaces read the same data unredacted.

Three tools (`preview-variant`, `run-variant`, `read-failures`) carry scalar `:dropped-sensitive` / `:elided-large` indicators alongside their own result slots, each omitted when its count is zero, so an agent can tell that a payload was filtered and by how much.

The one documented opt-out is `--allow-sensitive-reads` at boot plus a per-call `:include-sensitive`. With the boot gate closed — the default — the `:include-sensitive` slot is omitted from the `tools/list` schema entirely and any caller-supplied value is ignored at egress.

Story core's contract stays **real-values-in, real-values-out**; egress classification is the MCP jar's responsibility. The same split governs Xray's runtime seam: the framework, Xray and Story emit; tools consume; the contract is the data shape, not the call shape.

## Public write primitives

Story always exposes these helpers — the MCP jar's gate governs *its own* exposure of them, not Story's surface. They are also what hot-reload tooling and fixture loaders use to synthesise registrations.

```clojure
;; Public write primitives, in re-frame.story
(reg-story*       id body)   (reg-variant*     id body)
(reg-fragment*    id body)   (reg-check*       id body)
(reg-workspace*   id body)   (reg-mode*        id body)
(reg-story-panel* id body)   (reg-decorator*   id body)
(reg-tag*         id body)
(unregister! kind id) (clear-kind! kind) (clear-all!)
```

**Only two of these reach the agent.** The MCP write surface is `register-variant` (→ `reg-variant*`) and `unregister-variant` (→ `unregister! :variant`), both behind `re-frame.story-mcp.config/allow-writes?`, which defaults closed. The rest of the write surface is deliberately absent from the registry rather than merely ungated: there is no `register-story` (the agent registers a story by landing its variants under the parent), no `register-decorator` (decorators carry closures in `:wrap`, which JSON-RPC cannot transport), and no `register-tag` / `register-mode` (the ceremony is small enough to land inline; the agent's value-add is variant generation, not taxonomy).

## What ships in the MCP jar vs. Story core

A clean split, no overlap:

| Surface | Where |
|---|---|
| `initialize` / `tools/list` / `tools/call` / `ping` / `shutdown` dispatcher | `tools/story-mcp/` |
| Newline-delimited JSON-RPC over stdio | `tools/story-mcp/` |
| Cheshire (or equivalent JSON codec) | `tools/story-mcp/` |
| Protocol-version pin | `tools/story-mcp/` |
| The 19-tool registry (Dev / Docs / Testing / Write) | `tools/story-mcp/` |
| The wire-egress classification and its indicator counts | `tools/story-mcp/` |
| The `:rf.story-mcp/allow-writes?` gate | `tools/story-mcp/` |
| The nine `reg-*` macros (and their `*`-suffix runtime helpers) | `tools/story/` |
| The four-phase runtime | `tools/story/` |
| The render shell (when CLJS is the runtime) | `tools/story/` |
| The trace bus and panel registrations | `tools/story/` |
| The snapshot-identity computation | `tools/story/` |
| The canonical `:rf.assert/*` ids | `tools/story/` |
| The recorder and its `:script` translators | `tools/story/` |

`tools/story-mcp/` is a thin adapter: takes JSON-RPC requests, calls Story's public CLJS / CLJC functions, serialises responses back over stdio. Zero agent-specific logic lives in `tools/story/`.

Interactive canvas recording is a Story-core and browser surface, not an MCP one. The recorder primitives and the `:script` translators live in `tools/story/`; an agent that wants to record a live app drives that runtime through the pair.

## Independent cadence

Story and the MCP jar ship at **independent cadence**. The MCP jar carries its own `re-frame.story-mcp.config/stage = :mcp` sentinel. Story's own loaded-surface marker was removed — a single-value sentinel carried no discriminator information — so read the jar's surface from the jar's own marker and Story's surface from its published API.

## Late-bind `reg-story-panel` contract

The `reg-story-panel` surface is the single hook through which tooling embeds itself into the Story chrome. Five rules govern panel hosting (the full statement lives in the [`003-Render-Shell.md`](https://github.com/day8/re-frame2/blob/main/tools/story/spec/003-Render-Shell.md) spec doc); the summary as it pertains to this boundary:

1. **`:render` is a `:view` id.** Late-bind via `(rf/view ...)`. The actual view can register from a different artefact than the panel registration itself.
2. **Placement is one of five slots** — `:right` / `:left` / `:bottom` / `:top` / `:modal`.
3. **Visibility flows through `:panel-visibility`** — the shell's on/off switch keyed by panel id.
4. **Author calls `reg-story-panel` from anywhere.** Built-in panels register from the canonical-vocabulary auto-install; third-party tooling (Xray's epoch view, future statechart-viz panels) registers from its own boot.
5. **Late-bind is the contract.** A `:render` id can be registered from any artefact on the classpath, so the view-author and the panel-registrant need not live in the same jar.

Panel hosting is a **Story-core contract, not an agent one**: there is no `register-story-panel` tool, and the MCP jar consumes neither the panel host nor the view ids. Tooling embeds a panel by calling `reg-story-panel*` from its own boot — which is how Xray's epoch view and the other built-ins arrive.

## Why a separate jar

The MCP server depends on transport machinery (stdio adapter, JSON-RPC framing, asynchronous-handler runtime) that the vast majority of Story consumers never load. Splitting at the jar boundary keeps the Story core lean and lets the MCP surface evolve on its own cadence. The pattern mirrors `tools/machines-viz/` vs. `tools/machines-viz-mcp/`.

A typical agent's headless interaction with Story over the MCP surface:

```
1. get-story-instructions        — how variants are authored here
2. list-stories                  — the catalogue
3. get-variant :s.c/at-five      — the authored body, raw
4. explain-variant :s.c/at-five  — the resolved plan, no run
5. run-variant :s.c/at-five      — execute; :status is the verdict
6. read-failures :s.c/at-five    — the failing assertions, scrubbed
7. snapshot-identity :s.c/at-five — content-hash for visual-regression keying
8. register-variant :s.c/at-six  — reg-variant* (only if writes are gated open)
```

Every runtime-value payload crosses the wire path-projected; authored metadata crosses raw; every write goes through the gate. Story core stays composable, the MCP jar stays focused, and the contract is the data shape on both sides.

## See also

- [Registration](registration.md) — the `*`-suffix runtime helpers the write surface consumes.
- [Runtime](runtime.md) — `run-variant` / `snapshot-identity` / `read-assertions` and the four-phase lifecycle the MCP `run-variant` tool calls.
- [Scripts](script.md) — the `:script` body shape a `register-variant` call emits.
- [Reference](reference.md) — the full symbol table for `Ctrl-F` use.
- [Framework API — Schemas and data classification](../../api/re-frame.schemas.md) — `elide-wire-value`, the framework primitive the MCP jar's egress boundary calls.
- [re-frame2-pair MCP server](https://github.com/day8/re-frame2/blob/main/tools/re-frame2-pair-mcp/README.md) — the sibling live-app Tool-Pair contract; same emit-and-consume discipline. (Xray is the human panel and carries no agent seam — rf2-7htk7.)
- [re-frame2-pair skill](../../skills/re-frame2-pair.md) — the live-browser door, for driving a running app's Story runtime.
- Normative spec — [`tools/story-mcp/spec/`](https://github.com/day8/re-frame2/tree/main/tools/story-mcp/spec) (wire protocol, tool registry, write-surface gating) and [`tools/story/spec/006-MCP-Surface.md`](https://github.com/day8/re-frame2/blob/main/tools/story/spec/006-MCP-Surface.md) (Story's side of the boundary).
