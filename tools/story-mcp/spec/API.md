# Story-MCP — Public API (Tool Surface)

> Consolidated tool surface. Each tool's input schema + output shape +
> error mode. Cross-references back to the category doc where the
> contract is spelled out in prose.

All tools dispatch through `re-frame.story-mcp.server`'s `tools/call`
handler; their definitions live in
`re-frame.story-mcp.tools/tool-registry`.

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

### `list-assertions`

**Input.** `{}`.

**Output.** `{:assertions [{:id keyword :arity ... :semantics "..."}]}`.

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
- [`002-Tool-Registry.md`](002-Tool-Registry.md) — the 16 tools in
  prose.
- [`003-Write-Surface-Gating.md`](003-Write-Surface-Gating.md) —
  write-gate behaviour.
- [`tools/story/spec/API.md`](../../story/spec/API.md) — Story core's
  public API that this jar consumes.
