# Story — MCP Surface

> The boundary between Story (`day8/re-frame2-story`) and the
> separate-jar agent surface (`day8/re-frame2-story-mcp`, at
> `tools/story-mcp/`). What surfaces Story exposes for the MCP jar to
> consume; the late-bind `reg-story-panel` contract Story uses for
> tooling embeds. The wire-protocol / tool-registry details live in
> [`tools/story-mcp/spec/`](../../story-mcp/spec/).

See [`020-Story-UI-Inspector-And-Xray.md`](020-Story-UI-Inspector-And-Xray.md)
§1.3 for the human-UI / MCP / skill mirroring crosswalk: human-visible
Story operations are the product source of truth, and the MCP tools
expose gated/structured versions over the same variant/plan/result/
evidence model — no second artifact model.

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
(registrations kind)                             ; spec/001-mirror; per Story kind
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
(variant-share-url variant-id base-url opts)     ; share URL (005-SOTA-Features)
(registered-substrates)                          ; CLJS-only
```

Story's core jar exposes these without depending on stdio / JSON-RPC.
The MCP jar consumes them via the Tool-Pair bridge.

### Wire-elision boundary — core is real-values-in, real-values-out

Story core returns **marks-as-data**: the registered bodies and
per-frame snapshots travel unchanged across the read primitives
above, with `:sensitive` / `:large` declarations carried alongside as
declarative metadata. The wire-elision substitution to `:rf/redacted`
/ `:rf.size/large-elided` happens at the **MCP jar's egress boundary**, NOT in
Story core — every tool-response payload the MCP jar emits is passed
through `re-frame.elision/elide-wire-value` before it crosses the
JSON-RPC wire (per
[spec/015 §3. MCP wire transport](../../../spec/015-Data-Classification.md#in-scope--the-five-observation-points-marks-must-guard)
and [`tools/mcp-base/spec/elision.md`](../../mcp-base/spec/elision.md)).

The split keeps Story's read surface composable. The MCP jar is the
wire owner; egress is where elision lands. **What elision MEANS at that
boundary depends on the payload class** — the threat model
([`spec/015-Data-Classification.md`](../../../spec/015-Data-Classification.md))
scopes the marks to the OBSERVED runtime, not to authored registration
data, so the egress classifies each payload as runtime/captured VALUE
(scrubbed) vs. author-published static metadata (intentionally public):

- **Runtime / captured VALUE slots — PATH-projected by default
  (EP-0025 fail-open).** Every slot that carries observed runtime state or
  a captured/plan-resolved value is run through the egress projectors:
  `re-frame.core/elide-wire-value` for `:app-db`, and
  `re-frame.core/project-egress`'s `:rf.observe/derived-tree` record for
  derived / non-live trees. Both are PATH-BASED, on the `:sensitive` and
  `:large` PEER axes: a value AT a classified app-db path redacts to
  `:rf/redacted` / elides to the `:rf.size/large-elided` marker, in the
  `:app-db` slot AND in any derived slot WHERE the value still occupies
  that path (a derived slot whose shape mirrors the app-db — e.g. an
  `:effective-args {:token …}` slice with `[:token]` classified, or a
  `:db-seed` mirroring app-db). **EP-0025 removed the value-match
  (taint-by-equality) engine** (§"What is removed": value-match is
  propagation/taint by another name, which a hygiene helper does not
  earn). A value RE-KEYED to a position the classification path cannot
  reach — a token copied into rendered hiccup at `[1 :value]`, into a
  `:network` reply, into a captured-event payload, into an axe-core node
  `:html` — is NOT covered and ships **RAW (INTENDED FAIL-OPEN)**. A
  consumer that needs a value redacted in a derived tree must classify its
  app-db PATH so the value lands AT that path before it is re-surfaced.
  This path-projection covers the live-state tools' `:app-db` /
  `:rendered-hiccup` / `:snapshot` / evidence slots and assertion records
  (`preview-variant` / `run-variant` / `read-failures`),
  `read-a11y-violations`'s `:violations` (axe-core nodes), AND the
  non-live value-bearing slots: `explain-variant`'s plan-RESOLVED
  `:effective-args` / `:args` / `:substitutions` / `:network` /
  `:db-seed` / `:sub-overrides` / `:setup-order` / `:script-order`, and
  `record-as-variant`'s `:captured` event vectors + the `:play-snippet`
  text. Plan step STRUCTURE is always preserved. The shared
  `--allow-sensitive-reads` + per-call `:include-sensitive` opt-in is the
  one documented escape hatch (gate closed ⇒ the opt-in is omitted from
  `tools/list` and silently ignored at egress); note that the opt-in only
  governs values that path-redact — a re-keyed copy already ships raw.
- **Author-published STATIC metadata — intentionally public, NOT
  scrubbed.** The docs-discovery surfaces return the catalogue an author
  publishes: `get-story` / `get-variant` / `variant->edn` bodies,
  `list-stories` / `list-modes` / `list-decorators` / `list-tags` /
  `list-assertions` enumerations, the markdown render, and the
  `explain` map's plan-STRUCTURE slots (`:source-chain` /
  `:parent-chain` / `:compose` / `:merge` / `:strict-conflicts` /
  `:tags` / `:platforms` / …). These
  are registration-time authoring prose, not runtime/user state, so they
  cross unredacted by design — scrubbing them would only degrade the
  discovery UX without protecting any secret. NOTE: `:setup-order` /
  `:script-order` are NOT in this list — although their step STRUCTURE
  (which fx ids, in which order) is discovery metadata, `substitute-args`
  injects resolved arg VALUES into the step payloads at plan-compile
  time, so the post-substitution sequences are value-bearing and scrubbed
  (rf2-q8ebq.1). The value-only redaction preserves the public structure
  while redacting the embedded secrets. Registry-wide enumerations
  (modes, decorators) are not frame-keyed and carry no runtime values;
  their `:args` / `:app-db-patch` / `:response` slots are the author's
  own published fixture data.
- **Crosses the wire raw** — a future IN-PROCESS consumer that calls the
  read primitives directly (without going through the MCP jar) gets real
  values; this is by design so on-box devtool surfaces can read the same
  data unredacted. The wire-egress classification above is the MCP jar's
  responsibility, not Story core's.

Story core's contract is **real-values-in, real-values-out**;
elision is the MCP jar's responsibility. The §Privacy posture in
[`000-Vision.md`](000-Vision.md) §5 carries the same split as a
Vision-level statement. The per-tool scrubbed-vs-public classification
is single-sourced with
[`tools/story-mcp/spec/002-Tool-Registry.md`](../../story-mcp/spec/002-Tool-Registry.md)
§Wire-egress privacy posture.

## Story's public write primitives (consumed by MCP write surface)

```clojure
;; Public write primitives — used by MCP's gated write surface
;; AND by hot-reload tooling / fixture loaders that synthesise registrations.
(reg-story*       id body)   (reg-variant*     id body)
(reg-fragment*    id body)   (reg-check*       id body)
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
   (Xray's epoch view, future statechart-viz panels) registers from
   its own boot.
5. **Late-bind is the contract.** A `:render` id can be registered
   from any artefact on the classpath — Story panels resolve via
   `(rf/view <render-id>)` at render time, so the view-author and the
   panel-registrant need not live in the same jar. (Xray itself does
   NOT use `reg-story-panel`; per 003-Render-Shell.md §Panel-host the
   Xray surface has its own per-panel mount contract. Late-bind still
   governs the other panels.)

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
- Owns the 20-tool registry (Dev / Docs / Testing / Write). The run/read
  tools (`run-variant` / `read-failures` / `preview-variant`) return the
  UNIFIED `re-frame.story.result/run-result` shape the human Story UI
  reads — top-level `:status` ∈ `#{:pass :fail :cannot-run :error}`,
  unified assertion records, `:checks`, evidence slots — never a parallel
  agent-only verdict vocabulary (rf2-ba86n.17). `explain-variant` mirrors
  the human Explain panel over the shipped `story/explain` data API. The
  canonical name list is the `tools/story-mcp/test/fixtures/tool-names.json`
  fixture.
- Owns the `:rf.story-mcp/allow-writes?` config gate.

Story core owns:

- The nine `reg-*` macros (and their nine `*`-suffix runtime helpers).
- The four-phase runtime.
- The render shell (when CLJS is the runtime).
- The trace bus and panel registrations.
- The snapshot-identity computation.
- The eight canonical `:rf.assert/*` ids — seven dispatched as `reg-event` plus the tape-evaluated `:rf.assert/schema-error`.

Stage 7's `tools/story-mcp/` is a thin adapter: takes JSON-RPC
requests, calls Story's public CLJS / CLJC functions, serialises
responses back over stdio. Zero agent-specific logic lives in
`tools/story/`.

## Independent cadence

Story and the MCP jar ship at **independent cadence** per
[`tools/README.md`](../../README.md). The MCP jar carries its own
`re-frame.story-mcp.config/stage = :mcp` sentinel; Story's own
loaded-surface marker was removed (rf2-mobwk) — a single-value
sentinel carried no discriminator information.

## Cross-references

- [`tools/story-mcp/spec/`](../../story-mcp/spec/) — the MCP jar's
  own spec folder (wire protocol, tool registry, write-surface
  gating, design rationale).
- [`spec/Tool-Pair.md`](../../../spec/Tool-Pair.md) — the runtime
  contract for pair-shaped AI tools.
- [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) §separate-mcp-jar —
  why the split.
