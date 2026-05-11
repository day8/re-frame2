# Story-MCP — Tool Registry

> The 16 tools the server exposes, across four categories — Dev,
> Docs, Testing, Write. One section per category, one paragraph per
> tool. The wire-shape for each tool (input schema, output shape)
> lives in [`API.md`](API.md); this document is the orientation read.

The toolset split borrows the Storybook MCP shape (Dev / Docs /
Testing) and adds the gated Write surface for the self-healing loop.
Per
[`tools/story/spec/006-MCP-Surface.md`](../../story/spec/006-MCP-Surface.md)
the boundary between Story core and this jar is: Story core exposes
the read primitives; this jar packages them as MCP tools.

## Dev — for agents helping build new stories

Three tools that help an agent get its bearings before generating
new content.

### `get-story-instructions`

Returns the agent-onboarding text: how stories are authored, the
EDN-first constraint, the canonical variant body keys, the seven
canonical assertions, the four-phase lifecycle, the inclusion-tag
vocabulary. Mirrors Storybook MCP's `get-storybook-story-instructions`
in intent.

The text lives inline in the jar (single string, no external
resource read at boot) so the jar is self-contained.

### `preview-variant`

Given `:variant-id` (plus optional `:substrate`, `:active-modes`,
`:cell-overrides`, `:base-url`), runs the canvas pipeline and
returns the post-pipeline state plus a sharable URL:

```clojure
{:lifecycle      :ok | :failed-loaders | :failed-events | ...
 :share-url      "..."
 :app-db         {...}
 :assertions     [...]
 :rendered-hiccup [...]
 :snapshot       {...}
 :elapsed-ms     ...
 :effective-args {...}}
```

Differs from `run-variant` in semantics: `preview-variant` is the
"show me what this would look like" call; `run-variant` (in the
Testing category) is the "execute and report pass/fail" call.

### `list-substrates`

Returns the set registered via
`re-frame.story/register-substrate!` (Reagent canonical; UIx / Helix
opt-in per host). JVM-standalone hosts return `[]`.

## Docs — for agents reading the story library

Seven introspection tools.

### `list-stories`

`(rf/handlers :story)` enumeration, optionally filtered by tag-set
intersection (`{:tags [...]}`).

### `get-story`

Full story metadata + child variant ids.

### `get-variant`

Full variant body (as EDN, plus the `structuredContent` JSON
projection). The EDN form is the canonical artefact contract from
[`tools/story/spec/001-Authoring.md`](../../story/spec/001-Authoring.md)
§reg-variant.

### `list-tags`

Canonical + project-custom tags split.

### `list-modes`

`(rf/handlers :mode)` enumeration.

### `list-assertions`

The canonical seven `:rf.assert/*` events with arity + semantics
docs.

### `variant->edn`

Canonical EDN form, text-only result for byte-stable round-tripping
(content is text, not JSON, to avoid lossy JSON encoding of EDN).

## Testing — for agents running stories headlessly

Four execution tools.

### `run-variant`

Full lifecycle invocation; returns

```clojure
{:frame :app-db :assertions :rendered-hiccup :elapsed-ms :snapshot :lifecycle :passing?}
```

Inputs: `{:variant-id ... :substrate? ... :active-modes? ... :cell-overrides? ... :timeout-ms?}`.

The `:passing?` boolean is the headline "did this pass?" answer —
truthy when every assertion in the play sequence passed.

### `snapshot-identity`

Content hash of `(variant × args × decorators × loaders × substrate ×
modes)`. The agent uses this to skip cells unchanged since a
previous run, or to key downstream pixel-diff services.

### `run-a11y`

axe-core results (delegates to
`re-frame.story.ui.a11y/violations-by-frame`, the panel data from
Stage 6). JVM-standalone hosts return an empty list + a documented
hint that axe-core requires the in-browser panel.

### `read-failures`

Diagnostic for the variant's accumulated `:rf.story/assertions`
accumulator (no re-run). Useful for agents that want to inspect the
last-run state without paying the cost of a fresh `run-variant`.

## Write — v1.1, dev-only, gated

Two write tools, both gated behind
`re-frame.story-mcp.config/allow-writes?` per
[`003-Write-Surface-Gating.md`](003-Write-Surface-Gating.md).

### `register-variant`

Invokes `re-frame.story/reg-variant*` (the public programmatic
helper). Input `:body` may be a map (preferred) or an EDN-encoded
string.

### `unregister-variant`

Invokes `re-frame.story/unregister! :variant <id>`. Symmetric to
`register-variant`. Same gate.

The agent's self-healing loop (write story → run → read failures →
fix) activates with the write surface; without it the loop is
read-only.

## What's NOT in the registry

Each of these is a deliberate omission:

- **No `register-story`** at v1.1. The agent registers a story by
  inference: it calls `register-variant` against a variant id whose
  `:story.<path>` parent doesn't yet exist; Story's reg-variant
  helper raises if the parent isn't there. The agent then *also*
  needs to land the parent story — which it does by registering its
  variants under the parent, in order, with `:doc` etc. attached to
  the first one. (When v1.1 ships and the loop matures, a
  `register-story` tool may follow.)
- **No `register-decorator`** at v1.1. Decorators carry closures
  (`:wrap` slot) which JSON-RPC can't transport. A future shape
  would invoke a registered re-frame.story.* helper by id.
- **No `register-tag`** / `register-mode`. Same reasoning: the
  registration ceremony is small enough that the dev landing them
  inline is fine. The agent's value-add is variant generation, not
  taxonomy.

## Cross-references

- [`001-Wire-Protocol.md`](001-Wire-Protocol.md) — how each tool is
  invoked over the wire.
- [`003-Write-Surface-Gating.md`](003-Write-Surface-Gating.md) —
  how the Write category gates.
- [`API.md`](API.md) — per-tool input / output schemas.
- [`tools/story/spec/006-MCP-Surface.md`](../../story/spec/006-MCP-Surface.md) —
  Story's side of the read/write primitives.
