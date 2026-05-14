# Story-MCP — Tool Registry

> The 19 tools the server exposes, across four categories — Dev (3),
> Docs (9), Testing (4), Write (3). One section per category, one
> paragraph per tool. The wire-shape for each tool (input schema,
> output shape) lives in [`API.md`](API.md); this document is the
> orientation read. The canonical 19-tool name list ships as the
> shared fixture `test/fixtures/tool-names.json` (rf2-36upq TE7); JVM
> and Node test corpora compare against it so the spec text and the
> running registry can't drift independently.

Two deferred tool sections (`subscribe` / `unsubscribe` and
`evaluate-cljs`) appear at the end of the Docs category for forward-
visibility only; they are NOT in the shipped 19 and are explicitly
flagged "Status: deferred to a future drop."

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
returns the post-pipeline state plus a sharable URL.

Wire-egress posture: `:app-db` is routed through
`re-frame.core/elide-wire-value` against the variant frame's
`[:rf/elision]` registry; declared-sensitive paths land
`:rf/redacted` by default. Pass `:include-sensitive? true` to opt
out — BUT the opt-in is honoured only when the server was started
with `--allow-sensitive-reads` (rf2-g9fje); when that gate is
closed (the default), the `:include-sensitive?` slot is omitted
from the `tools/list` schema entirely and any caller-supplied
value is silently ignored at egress.

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

Nine introspection tools — the seven core read primitives
(`list-stories`, `get-story`, `get-variant`, `list-tags`,
`list-modes`, `list-assertions`, `variant->edn`) plus
`list-decorators` (rf2-mqp1u) and `get-docs-markdown` (rf2-i0kyy)
filled in during Stage 7 polish.

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

### `list-decorators` (rf2-mqp1u)

Read-only `(rf/handlers :decorator)` enumeration. Each entry carries
`:id`, `:kind`, `:doc` plus the kind-specific pure-data slots —
`:has-wrap?` for `:hiccup` decorators (the closure itself doesn't
transport over MCP); `:init` + `:app-db-patch` for `:frame-setup`;
`:fx-id` + `:response` for `:fx-override`. The read-only peer of the
deferred `register-decorator` write surface — closures don't
transport, so the write side stays out of scope at v1, but the read
side is cheap and lets an agent enumerate the decoration vocabulary
the same way it enumerates tags / modes / assertions. Optional
`:kind` arg narrows to one kind.

### `list-assertions`

The canonical seven `:rf.assert/*` events with arity + semantics
docs.

### `variant->edn`

Canonical EDN form, text-only result for byte-stable round-tripping
(content is text, not JSON, to avoid lossy JSON encoding of EDN).

### Deferred Docs tools (not part of the shipped 19)

The two sections below sketch additions that are NOT in the
shipped registry — they appear here for forward-visibility while the
implementation is pending.

### `subscribe` / `unsubscribe` (rf2-p8u13, deferred)

**Status: deferred to a future drop.** Pair2-MCP has streaming
`subscribe` / `unsubscribe` — long-running `tools/call` that emits
matching events as `notifications/progress` notifications (push
mode). Story-MCP's read tools are all pull-mode today. An agent
watching for "the next time variant X fails an assertion" or "the
next variant registered" has to poll `run-variant` / `list-stories`
repeatedly.

**v2 sketch (not implemented).** Add `subscribe` / `unsubscribe`
mirroring pair2-mcp's shape — same wire-protocol slot
(`notifications/progress` correlated by the call's
`progressToken`), same idempotent `unsubscribe`, same
`subscription-info` peer for the "what streams are open?"
diagnostic.

Topics to expose:

- `:next-variant-failure` — fire when any variant assertion fails
  (filter by `:variant-id` to narrow to one).
- `:variant-registered` — fire on `reg-variant` (filter by
  `:story-id-prefix` to scope to one story or a subtree).
- `:mode-changed` — fire when the active mode set changes.
- `:story-reloaded` — fire on hot-reload (boundary aligns with
  `re-frame.story/clear-all!` + re-registration).

Open questions: how Story-side state changes (run-variant
assertions, registrar mutations) surface as observable events
without the runtime's epoch ring (pair2-mcp's substrate is rich;
Story-MCP runs on the JVM with no equivalent today), whether the
streaming machinery shares an abstraction with pair2-mcp's
`subscribe` (likely yes — extracting the queue + progress-callback
plumbing into a shared `tools/mcp-base/streaming` ns is the
implementation-first step), and what the wire-cap / dedup posture
looks like for these payloads (assertion records and variant
bodies are bounded, so the per-tick cap likely matches pair2-mcp's
5,000-token default without further per-tool tuning).

### `evaluate-cljs` (rf2-vilu3, deferred)

**Status: deferred to a future drop.** Pair2-MCP has `eval-cljs`
(arbitrary form, evaluated in the connected CLJS runtime).
Story-MCP doesn't. An agent that needs to peek at a Story-side
slice the curated tool surface doesn't expose has no recourse but
to file an RFE.

**v2 sketch (not implemented).** Add `evaluate-cljs` /
`evaluate-cljs-in-story` MCP tool. Bridges the JVM-side story-mcp
through to a running CLJS Story session over the same nREPL
transport pair2-mcp uses today. The same posture:

- Bounded — `max-tokens` cap, no `:tools/list` discoverability of
  the escape hatch in production deploys (gate on
  `:rf.story/expert-mode? true` in `config.cljc`).
- Opt-in — explicit `--allow-evaluate-cljs` CLI flag mirroring
  the existing `--allow-writes` posture for write tools (per
  IMPL-SPEC §7.3 / `003-Write-Surface-Gating.md`).
- Tagged — every fired event / fx carries `:origin :story-mcp`
  so the runtime distinguishes agent-driven slices from user-driven
  ones.

Open questions: which Story session does the form attach to (the
implicit "active variant frame"? all frames? caller picks?), how
the JVM-standalone deploy degrades when no CLJS session is reachable
(today: `list-substrates` returns `[]`; `evaluate-cljs` would need
the same posture), and whether the existing pair2-mcp `eval-cljs`
satisfies the need when a session co-installs both servers (likely
yes, which is the argument for keeping this deferred until the
single-server need materialises in the wild).

### `get-docs-markdown` (rf2-i0kyy)

GitHub-flavoured Markdown projection of a story's documentation —
the story `:doc` + per-variant `:doc` + args / argtypes / tags /
decorators composed into one paste-ready string. Sibling to
`get-story` (which returns the same content as EDN); the difference
is the egress shape an agent host wants when surfacing docs to a
human collaborator (issue tracker, chat, README excerpt). The
markdown rides both the wire-canonical `:content` text slot and a
structured `:markdown` slot for hosts that distinguish.

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

Assertion records carrying `:sensitive? true` are dropped at egress
by default; `:include-sensitive? true` opts back in subject to the
same `--allow-sensitive-reads` boot gate as `preview-variant` /
`run-variant`.

## Sensitive-read boot gate (`--allow-sensitive-reads`, rf2-g9fje)

The three tools that surface live frame state (`preview-variant`,
`run-variant`, `read-failures`) all accept a per-call
`:include-sensitive?` boolean to opt out of the default redaction
posture (see [`tools/Tool-Pair.md`](../../../spec/Tool-Pair.md)
§Direct-read privacy posture). Per the rf2-uaymx (b) decision that
opt-in is itself gated by a server-side boot flag, mirroring the
`--allow-eval` posture pair2-mcp uses for `eval-cljs` (rf2-zyoj2):

| Path | Mechanism |
|---|---|
| CLI flag | `--allow-sensitive-reads` |
| JVM sysprop | `-Drf.story-mcp.allow-sensitive-reads=true` |
| Env var | `RF_STORY_MCP_ALLOW_SENSITIVE_READS=true` |

Closed by default. When closed:

- `tools/list` omits the `:include-sensitive?` slot from the input
  schemas of the three affected tools — agents never see an opt-in
  they couldn't exercise.
- The wire-egress scrubbers silently ignore any caller-supplied
  `:include-sensitive? true` — declared-sensitive `:app-db` paths
  remain `:rf/redacted`; assertion records stamped `:sensitive?
  true` remain dropped.
- The server logs one line at boot:
  `Sensitive reads: gated (default; pass --allow-sensitive-reads to opt in)`.

When open, the per-call `:include-sensitive? true` is honoured as
documented — raw values cross the wire, the operator has signed
off on the egress posture by passing the flag.

## Write — v1.1, dev-only, gated

Three write tools. `register-variant` and `unregister-variant` are
both gated behind `re-frame.story-mcp.config/allow-writes?` per
[`003-Write-Surface-Gating.md`](003-Write-Surface-Gating.md);
`record-as-variant` is ungated for the recording path and gated only
when `:write-back?` is set.

### `register-variant`

Invokes `re-frame.story/reg-variant*` (the public programmatic
helper). Input `:body` may be a map (preferred) or an EDN-encoded
string.

### `unregister-variant`

Invokes `re-frame.story/unregister! :variant <id>`. Symmetric to
`register-variant`. Same gate.

### `record-as-variant`

Bridges the recorder primitives (`start-recording!` → `stop-recording!`
→ `gen-play-snippet`) across the MCP boundary. The agent calls the tool
naming an existing variant id; the server starts a recording against
that variant's frame, blocks for `:duration-ms` while the agent (or
human-in-canvas) drives dispatches, stops the recording, and returns
the `(reg-variant ...)` snippet `gen-play-snippet` emits.

Filter layers are inherited verbatim from
`re-frame.story.recorder/recordable-event?` — op-type `:event/dispatched`,
frame scope match against the recording target, and an internal-namespace
skip (`:rf.assert/*`, `:rf.story/*`, `:re-frame.story.*`). The tool
does not expose a free-form filter knob; the recorder owns that
contract per
[`tools/story/spec/005-SOTA-Features.md`](../../story/spec/005-SOTA-Features.md)
§Test Codegen.

Optional `:write-back?` re-registers the source variant with
`:play <captured-events>` via `reg-variant*` (preserving the
existing `:component`, `:args`, `:decorators`, etc.). This branch is
gated behind the same `allow-writes?` flag as `register-variant`; the
read-only path (snippet only) needs no gate.

`:new-variant-id` lets the write-back land under a different id (the
default is to overwrite the source). `:extends` defaults to the source
variant so the emitted snippet re-uses its `:component` / `:args` /
`:decorators` rather than duplicating them.

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
- [`tools/story/spec/005-SOTA-Features.md`](../../story/spec/005-SOTA-Features.md) §Test Codegen — the recorder primitives `record-as-variant` wraps.
- [`tools/story/spec/006-MCP-Surface.md`](../../story/spec/006-MCP-Surface.md) —
  Story's side of the read/write primitives.
