# Story-MCP — Public API (Tool Surface)

> Consolidated tool surface. Each tool's input schema + output shape +
> error mode. Cross-references back to the category doc where the
> contract is spelled out in prose.

All tools dispatch through `re-frame.story-mcp.server`'s `tools/call`
handler; their definitions live in
`re-frame.story-mcp.tools.registry/tool-registry`.

## Dev tools

### `get-story-instructions`

**Input.** `{}` — no arguments.

**Output.** `{:content [{:type :text :text "..."}]}` — the
agent-onboarding text.

**Spec.** [`002-Tool-Registry.md`](002-Tool-Registry.md) §Dev.

### `preview-variant`

**Input.**

```clojure
{:variant-id      keyword (required)
 :substrate       keyword (optional)
 :active-modes    [keyword] (optional)
 :cell-overrides  {keyword any} (optional)
 :base-url        string (optional)}
```

**Output.**

```clojure
{:lifecycle      :ok | :failed-loaders | :failed-events | :failed-play
 :share-url      string
 :app-db         map
 :assertions     [map]
 :rendered-hiccup [vector]
 :snapshot       {:variant-id ..., :mode ..., :substrate ..., :content-hash ...}
 :elapsed-ms     number
 :effective-args map}
```

**Errors.** `isError: true` when `:variant-id` is not registered.

**Spec.** [`002-Tool-Registry.md`](002-Tool-Registry.md) §Dev.

### `list-substrates`

**Input.** `{}`.

**Output.** `{:substrates [keyword]}`. JVM-standalone returns `[]`.

## Docs tools

### `list-stories`

**Input.** `{:tags [keyword] (optional)}`.

**Output.** `{:stories [{:id keyword :doc string ...}]}`.

**Spec.** [`002-Tool-Registry.md`](002-Tool-Registry.md) §Docs.

### `get-story`

**Input.** `{:story-id keyword (required)}`.

**Output.** Full story body + child variant ids.

**Errors.** `isError: true` when `:story-id` is not registered.

### `get-variant`

**Input.** `{:variant-id keyword (required)}`.

**Output.** Variant body (canonical EDN form as text plus
`structuredContent` JSON projection).

**Errors.** `isError: true` when `:variant-id` is not registered.

### `list-tags`

**Input.** `{}`.

**Output.** `{:canonical [...] :project [...]}`.

### `list-modes`

**Input.** `{}`.

**Output.** `{:modes [{:id keyword :args map ...}]}`.

### `list-decorators` (rf2-mqp1u)

**Input.** `{:kind "hiccup" | "frame-setup" | "fx-override" (optional)}`.

**Output.** `{:decorators [{:id keyword :kind keyword :doc string ...}]}`.
Per-kind slots: `:has-wrap?` (hiccup, never the closure itself);
`:init` + `:app-db-patch` (frame-setup); `:fx-id` + `:response`
(fx-override).

### `list-assertions`

**Input.** `{}`.

**Output.** `{:assertions [{:id keyword :arity ... :semantics "..."}]}`.

### `get-docs-markdown` (rf2-i0kyy)

**Input.** `{:story-id keyword (required)}`.

**Output.** `{:story-id keyword :markdown string :variants [keyword ...]}`.
The `:markdown` slot rides the wire-canonical `:content` text slot
verbatim; structured content carries the same string for hosts
that surface it separately.

**Errors.** `isError: true` when `:story-id` is not registered.

### `variant->edn`

**Input.** `{:variant-id keyword (required)}`.

**Output.** Text content of canonical EDN form (text-only, no JSON
projection — byte stability matters for round-tripping).

## Testing tools

### `run-variant`

**Input.**

```clojure
{:variant-id     keyword (required)
 :substrate      keyword (optional)
 :active-modes   [keyword] (optional)
 :cell-overrides {keyword any} (optional)
 :timeout-ms     number (optional)}
```

**Output.**

```clojure
{:frame           keyword
 :app-db          map
 :assertions      [map]
 :rendered-hiccup [vector]
 :elapsed-ms      number
 :snapshot        map
 :lifecycle       keyword
 :passing?        boolean}
```

**Errors.** `isError: true` on unknown variant id, timeout, or
unrecoverable exception.

**Spec.** [`002-Tool-Registry.md`](002-Tool-Registry.md) §Testing.

### `snapshot-identity`

**Input.**

```clojure
{:variant-id   keyword (required)
 :substrate    keyword (optional)
 :active-modes [keyword] (optional)}
```

**Output.**

```clojure
{:variant-id   keyword
 :mode         keyword | nil
 :substrate    keyword | nil
 :content-hash string}
```

### `run-a11y`

**Input.** `{:variant-id keyword (required)}`.

**Output.** `{:violations [map] :hint? string}`. JVM-standalone
hosts return `{:violations [] :hint "axe-core requires the in-browser panel."}`.

### `read-failures`

**Input.** `{:variant-id keyword (required)}`.

**Output.** `{:assertions [map] :passing? boolean}`. No re-run.

## Write tools (gated)

Both tools require `:rf.story-mcp/allow-writes?` to be true; see
[`003-Write-Surface-Gating.md`](003-Write-Surface-Gating.md).

### `register-variant`

**Input.**

```clojure
{:variant-id keyword (required)
 :body       map | string (required — map preferred; string is EDN)}
```

**Output.** `{:registered? true :variant-id ...}` on success.

**Errors.**
- `isError: true` when gate is closed (`{:gated true :reason "..."}`).
- `isError: true` when `:body` fails `:rf/variant` schema validation.
- `isError: true` when the parent story is not registered.

### `unregister-variant`

**Input.** `{:variant-id keyword (required)}`.

**Output.** `{:unregistered? true :variant-id ...}` on success.

**Errors.** `isError: true` when gate is closed; `isError: true`
when variant is not registered.

### `record-as-variant`

Bridges `re-frame.story`'s recorder primitives (per
[`tools/story/spec/005-SOTA-Features.md`](../../story/spec/005-SOTA-Features.md)
§Test Codegen) across the MCP boundary.

**Input.**

```clojure
{:variant-id     keyword (required)
 :duration-ms    integer (optional, default 0)    ; ms to block between start and stop
 :new-variant-id keyword (optional)                ; defaults to :variant-id
 :doc            string  (optional)                ; embedded in snippet
 :extends        keyword (optional)                ; defaults to :variant-id
 :alias          string  (optional, default "story")
 :write-back?    boolean (optional, default false) ; re-register with :play <captured>
}
```

**Output.**

```clojure
{:variant-id           keyword           ; the source variant id
 :play-snippet         string            ; (reg-variant ...) form, read-string-able
 :recorded-event-count integer
 :duration-ms          integer           ; actual ms blocked
 :captured             [event-vec]       ; the recorded event vectors
 :written-back?        boolean
 :new-variant-id       keyword (when :written-back? is true)}
```

**Errors.**
- `isError: true` when the source `:variant-id` is not registered.
- `isError: true` when `:write-back?` is true but the gate is closed
  (`{:gated true}` in `structuredContent`).
- `isError: true` when the write-back `reg-variant*` call fails (shape
  validation, unresolved `:extends`, etc.).

Filter layers (op-type `:event/dispatched`, frame scope, internal-ns
skip) are inherited from the recorder; this tool does not expose a
free-form filter knob.

**Spec.** [`002-Tool-Registry.md`](002-Tool-Registry.md) §Write.

## Protocol-level methods

Not tools per se, but documented here for completeness:

| Method | Input | Output |
|---|---|---|
| `initialize` | `{:protocolVersion str :capabilities map :clientInfo map}` | `{:protocolVersion str :capabilities map :serverInfo map}` |
| `tools/list` | `{}` | `{:tools [tool-descriptor]}` |
| `tools/call` | `{:name str :arguments map}` | `{:content [...] :structuredContent map :isError bool}` |
| `ping` | `{}` | `{}` |
| `shutdown` | `{}` | `{}` |
| `notifications/initialized` | `{}` | (no response) |

Full wire details in
[`001-Wire-Protocol.md`](001-Wire-Protocol.md).

## Cross-references

- [`000-Vision.md`](000-Vision.md) — what this jar is for.
- [`001-Wire-Protocol.md`](001-Wire-Protocol.md) — JSON-RPC envelope
  + framing.
- [`002-Tool-Registry.md`](002-Tool-Registry.md) — the 19 tools in
  prose.
- [`003-Write-Surface-Gating.md`](003-Write-Surface-Gating.md) —
  write-gate behaviour.
- [`tools/story/spec/API.md`](../../story/spec/API.md) — Story core's
  public API that this jar consumes.
