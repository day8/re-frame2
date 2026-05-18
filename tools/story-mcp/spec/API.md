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
{:variant-id        keyword (required)
 :substrate         keyword (optional)
 :active-modes      [keyword] (optional)
 :cell-overrides    {keyword any} (optional)
 :base-url          string (optional)
 :include-sensitive boolean (optional, gated — see below)}
```

`:include-sensitive` is honoured ONLY when the server was started
with `--allow-sensitive-reads` (rf2-g9fje); when that boot gate is
closed (the default) the slot is omitted from `tools/list` and any
caller-supplied value is silently ignored at egress.

The wire-key shape (`:include-sensitive`, no `?`) satisfies the
Anthropic Messages API regex on tool input-schema property keys:
`^[a-zA-Z0-9_.-]{1,64}$`. The trailing `?` Clojure-idiomatic for
booleans is rejected at the host, so the wire form drops it. The
predicate FUNCTION `helpers/include-sensitive?` retains its `?` —
the idiom belongs on the predicate, not on the data key whose wire
form disallows it.

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
{:variant-id        keyword (required)
 :substrate         keyword (optional)
 :active-modes      [keyword] (optional)
 :cell-overrides    {keyword any} (optional)
 :timeout-ms        number  (optional, capped at 30000)
 :include-sensitive boolean (optional, gated — see `preview-variant`)}
```

`:timeout-ms` is clamped to 30 s (matches `:rf.http/timeout-ms`
baseline per rf2-it1cd; rf2-g9fje). `:include-sensitive` follows
the same `--allow-sensitive-reads` gate as `preview-variant`.

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

**Input.**

```clojure
{:variant-id        keyword (required)
 :include-sensitive boolean (optional, gated — see `preview-variant`)}
```

**Output.** `{:assertions [map] :passing? boolean}`. No re-run.

`:include-sensitive` follows the same `--allow-sensitive-reads`
boot gate as `preview-variant` / `run-variant`. Assertion records
stamped `:sensitive? true` are dropped at egress by default.

## Write tools (gated)

Both tools require `:rf.story-mcp/allow-writes?` to be true; see
[`003-Write-Surface-Gating.md`](003-Write-Surface-Gating.md).

### `register-variant`

**Input.**

```clojure
{:variant-id keyword (required)
 :body       map | string (required — map preferred; string is EDN)}
```

When `:body` is a string, it's parsed as EDN under a hardened policy
(rf2-g9fje):

- **No custom tagged literals.** `:readers {}` is empty and the `:default`
  handler throws — `#inst` / `#uuid` (EDN built-ins) parse normally;
  any other `#<tag> ...` form is rejected. The `#=(...)` read-eval form
  is rejected by `clojure.edn` at the dispatch-macro level (never
  evaluated).
- **64 KB payload ceiling.** A legitimate variant body is well under 1 KB;
  abusive payloads return an `isError: true` `"must be a map or a valid
  EDN string"` result before `edn/read-string` walks the input.
- **64-level depth ceiling.** Variant bodies top out at 3-4 levels;
  deeper inputs short-circuit cleanly.

The JSON-object form is preferred when keywords aren't structurally
required in the body — the EDN-string form exists for callers whose
body shape (e.g. `:tags #{:dev}`) can't round-trip through JSON.

**Output.** `{:registered? true :variant-id ...}` on success.

**Errors.**
- `isError: true` when gate is closed (`{:gated true :reason "..."}`).
- `isError: true` when `:body` fails `:rf/variant` schema validation.
- `isError: true` when `:body` is a string that fails the EDN reader
  hardening above (`"must be a map or a valid EDN string"`).
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
