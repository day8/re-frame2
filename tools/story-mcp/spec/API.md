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
 :timeout-ms        number  (optional, default 10000, capped at 30000)
 :include-sensitive boolean (optional, gated — see below)}
```

`:timeout-ms` is the JVM blocking ceiling for the lifecycle run.
`preview-variant` blocks on the SAME `story/run-variant` lifecycle as
`run-variant`, so it exposes the SAME tunable knob (rf2-ovmc5e): default
10 s, hard ceiling 30 s (matches `:rf.http/timeout-ms` per rf2-it1cd),
caller values above the ceiling clamp DOWN rather than reject. Both
tools resolve it through the shared `tools.args/resolve-timeout-ms`
helper so their blocking policy cannot drift.

`:include-sensitive` is honoured ONLY when the server was started
with `--allow-sensitive-reads` (rf2-g9fje); when that boot gate is
closed (the default) the slot is omitted from `tools/list` and any
caller-supplied value is silently ignored at egress.

The wire-key shape (`:include-sensitive`, no `?`) satisfies the
Anthropic Messages API regex on tool input-schema property keys:
`^[a-zA-Z0-9_.-]{1,64}$`. The trailing `?` Clojure-idiomatic for
booleans is rejected at the host, so the wire form drops it. The
predicate FUNCTION `args/include-sensitive?` retains its `?` —
the idiom belongs on the predicate, not on the data key whose wire
form disallows it.

**Output.** The unified run-result + the preview-specific slots
(rf2-ba86n.17 — `preview-variant` speaks the SAME result vocabulary as
`run-variant`):

```clojure
{:status         :pass | :fail | :cannot-run | :error  ; the unified verdict
 :lifecycle      :ready | :error                        ; loader STATE (not the verdict)
 :share-url      string
 :app-db         map
 :assertions     [map]    ; unified records, each with a derived :status
 :checks         [map]
 :rendered-hiccup [vector]
 :snapshot       {:variant-id ..., :mode ..., :substrate ..., :content-hash ...}
 :elapsed-ms     number
 :effective-args map}
```

`:status` is the verdict; `:lifecycle` is the loader-lifecycle STATE,
not the verdict (the retired `:passing?` boolean is gone).

**Errors.** `isError: true` when `:variant-id` is not registered.

**Spec.** [`002-Tool-Registry.md`](002-Tool-Registry.md) §Dev.

### `list-substrates`

**Input.** `{}`.

**Output.** `{:substrates [keyword]}`. JVM-standalone returns `[]`.

## Docs tools

### Pagination (rf2-76sf6)

Every `list-*` tool accepts the cross-MCP pagination contract
per spec/Principles.md §"Tight token budget":

```clojure
{:limit  integer (optional, default 25, max 200)
 :cursor string  (optional opaque continuation token)}
```

The cursor is an opaque base64-encoded EDN map whose internal shape
(today: `{:v 1 :offset N :total N :sig "<digest>"}`) is an
implementation detail; agents pass the value back verbatim. The
encoding lives in
`tools/story-mcp/src/re_frame/story_mcp/tools/cursor.cljc`.

When the entry count fits on one page (≤ `:limit`), the response is
the bare tool payload — no pagination metadata, byte-identical to
the pre-rf2-76sf6 shape. When pagination kicks in, the response
adds:

```clojure
{:total        integer    ; whole-set count at cursor-mint time
 :limit        integer
 :has-more?    boolean
 :next-cursor  string | nil}  ; nil on the final page
```

If the underlying registry materially changes between cursor mint
and cursor deref (e.g. a `register-variant` lands between two pages
of `list-stories`), the server returns
`{:isError true :structuredContent {:reason :rf.mcp/cursor-stale :tool "..."}}`
— the same vocab pair-mcp uses for ring-rotation staleness. The
agent restarts pagination from offset 0.

The cursor `:offset` and `:total` are validated as **natural integers**
with `:offset ≤ :total` at decode time. A forged or hand-edited cursor
that violates this range (a negative offset, or an offset past the
total) is treated as malformed and recovers through the SAME
`:rf.mcp/cursor-stale` envelope — it never feeds the slice a bad index
(which would throw a generic handler exception) nor silently returns an
empty page that skips registry rows (rf2-to3q7). This is the wire-
boundary range gate on the opaque cursor; the `:sig` fingerprint
separately catches a registry that changed under a structurally-valid
cursor.

The `get-*` / `<thing>->edn` tools are NOT paginated — their return
is a single record bounded by the registered body's size, not a
function of registry size. Wire-budget overruns are caught by the
top-level cap via `:max-tokens` + the `:rf.mcp/overflow` marker.

### `list-stories`

**Input.** `{:tags [keyword] (optional)
                :limit integer (optional, default 25)
                :cursor string (optional)}`.

**Output.** `{:stories [{:id keyword :doc string ...}]}`, plus the
pagination metadata when active (see "Pagination" above).

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

**Input.** `{:limit integer (optional) :cursor string (optional)}`.

**Output.** `{:canonical [...] :custom [...] :all [...]}`. The
`:canonical` set is the bounded 12-entry canonical-tag vector (the
seven spec/007 inclusion tags plus the five rf2-k1k87 `:state/*`
magnitude tags) and is always returned in full. `:custom` (project-registered tags)
and `:all` (the union) are paginated together per the contract
above when the custom-tag count exceeds `:limit`.

### `list-modes`

**Input.** `{:limit integer (optional) :cursor string (optional)}`.

**Output.** `{:modes [{:id keyword :args map ...}]}`, plus
pagination metadata when active.

### `list-decorators` (rf2-mqp1u)

**Input.** `{:kind "hiccup" | "frame-setup" | "fx-override" (optional)
                :limit integer (optional) :cursor string (optional)}`.

**Output.** `{:decorators [{:id keyword :kind keyword :doc string ...}]}`,
plus pagination metadata when active. Per-kind slots: `:has-wrap?`
(hiccup, never the closure itself); `:init` + `:app-db-patch`
(frame-setup); `:fx-id` + `:response` (fx-override).

The `:kind` filter is an **enum** — a SUPPLIED value outside
`{"hiccup" "frame-setup" "fx-override"}` returns an `isError: true`
diagnostic (`:rf.error :rf.story-mcp/unknown-decorator-kind`), NOT a
silent widen to the full catalogue (rf2-cdavyf). Resolving a typo
(`"hicup"`) to `nil` and treating `nil` as no-filter used to return
EVERY decorator, hiding the caller's mistake behind a successful-looking
result. An ABSENT `:kind` (the slot was never sent) is the legitimate
no-filter path; only a present-but-unrecognised value rejects. The
unrecognised string never mints a fresh JVM keyword (it short-circuits
through `safe-keyword`).

When a `:kind` filter is applied, the cursor's fingerprint is over
the filtered id-set — so a kind-filter change between pages reads
as a stale cursor (different fingerprint).

### `list-assertions`

**Input.** `{:limit integer (optional) :cursor string (optional)}`.

**Output.** `{:canonical [{:id :payload :semantics}] :registered [keyword ...]}`.
The `:canonical` doc vector (the 8-assertion documentation: the seven
dispatched canonical assertions plus the tape-evaluated
`:rf.assert/schema-error`) is bounded and always returned in full.
`:registered` is the FULL vocabulary the Story plan compiler accepts
(`re-frame.story.assertions/known-assertion-ids`, rf2-4sgak) — the eight
canonical ids plus the DOM (`:rf.assert/dom-*`), visual/a11y
(`:rf.assert/visual-snapshot` / `:rf.assert/a11y` /
`:rf.assert/a11y-structural`), and reactive-count
(`:rf.assert/caused` / `:rf.assert/no-cascade-rerender`) families — and
is paginated per the contract above.

### `get-docs-markdown` (rf2-i0kyy)

**Input.** `{:story-id keyword (required)}`.

**Output.** `{:story-id keyword :markdown string :variants [keyword ...]}`.
The `:markdown` slot rides the wire-canonical `:content` text slot
verbatim; structured content carries the same string for hosts
that surface it separately.

**Errors.** `isError: true` when `:story-id` is not registered.

### `variant->edn`

**Input.** `{:variant-id keyword (required)}`.

**Output.** Canonical EDN form in the wire-canonical `:content` text
slot (the byte-stable `pr-str` form — keyword keys preserved — for
agents that want strict EDN diffing) PLUS a matching
`structuredContent` carrying the same body map (rf2-vyacl). The
descriptor declares an `:outputSchema`, and the official MCP SDK's
high-level `callTool` rejects an outputSchema-declaring tool that
returns no `structuredContent` (JSON-RPC -32600), so the structured
slot is emitted alongside the text. The text slot remains the
byte-stable source of truth for round-tripping.

**Errors.** `isError: true` when `:variant-id` is not registered.

### `explain-variant` (rf2-ba86n.17)

**Input.** `{:variant-id keyword (required)
                :include-sensitive boolean (optional, gated — see `preview-variant`)}`.

**Output.** `{:variant-id keyword :explain map}` — the variant-plan
`:explain` projection (spec/017 §Explain API), a thin mirror over the
shipped `re-frame.story/explain` data API (the agent mirror of the human
Explain panel). `:explain` carries `:source-chain` / `:parent-chain`,
`:compose`, `:strict-conflicts`, the per-field `:merge` rules, `:args` /
`:substitutions` / `:effective-args`, `:view-args-schema` /
`:view-args-validation`, `:network`, `:sub-overrides`, `:fidelity`,
`:setup-order` / `:script-order`, `:checks` / `:assertions`,
`:required-runner`, `:platforms`, `:tags`.

Plan-derived data — no run, no live `:app-db` slice — but the plan
RESOLVES author args into runtime VALUES, so the value-bearing slots
(`:effective-args` / `:args` / `:substitutions` / `:network` route
replies / `:db-seed` / `:sub-overrides` override values / `:setup-order`
+ `:script-order` step payloads) are PATH-projected against the variant
frame's classification at egress (rf2-12f2q, rf2-q8ebq.1; EP-0025 fail-open)
via the shared `egress/scrub-explain-values` step — on BOTH egress axes
(EP-0015 peer axes). EP-0025 removed value-match: a value AT a classified
path WITHIN a slot redacts (a `:db-seed` mirroring app-db reaches its path),
but a value RE-KEYED to a non-matching position ships RAW (fail-open) —
classify the app-db PATH to redact a value before it is re-surfaced. The
SAME PATH-based projection the live tools apply. The remaining plan-STRUCTURE slots
(`:source-chain` / `:parent-chain` / `:compose` / `:merge` /
`:strict-conflicts` / `:tags` / …) are author-published discovery
metadata and pass through unredacted. Pass `:include-sensitive true`
to opt out (gated by `--allow-sensitive-reads`, same posture as
`preview-variant`). See [`002-Tool-Registry.md`](002-Tool-Registry.md)
§`explain-variant` for the full value-vs-structure split.

**Pre-frame egress (rf2-tag30h; EP-0025 fail-open).** `explain-variant`
is a no-run path: a caller can read it BEFORE any `run-variant` /
`preview-variant` allocates the variant frame. The classification PATHS are
durable frame state, live from `reg-frame` time, so the explain slots are
PATH-walked pre-run. EP-0025 removed the value-match candidate-union that
used to derive secrets from the plan's own `:db-seed`: a slot is now
redacted only where a value sits AT a classified path WITHIN it. A
`:db-seed` that mirrors the app-db shape redacts at its matching path even
pre-frame; a secret RE-KEYED into `:effective-args` / `:network` / a step
payload at a non-matching position ships RAW (fail-open). Classify the
app-db PATH to redact a value before it is re-surfaced.

**Errors.** `isError: true` when `:variant-id` is not registered.

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

**Output.** The unified run-result (rf2-ba86n.17 clean break — the same
shape the human Story UI reads; `re-frame.story.result/run-result`):

```clojure
{:status             :pass | :fail | :cannot-run | :error  ; the verdict
 :frame              keyword
 :assertions         [map]    ; unified records, each with a derived :status
 :checks             [map]
 :consumed-selectors #{...}
 :schema-violations  [map]    ; evidence-slot projections (.4)
 :warnings           [map]
 :effects            [map]
 :sub-runs           [map]
 :renders            [map]
 :narrative          map
 :app-db             map
 :rendered-hiccup    [vector]
 :snapshot           map
 :elapsed-ms         number}
```

`:status` is the headline verdict. The retired `:passing?` boolean and
`:lifecycle` *verdict* are gone — `:status` is the one verdict, and only
it can express `:cannot-run` (the runner could not even attempt the plan;
handle as "not runnable here", not a fail). A `:cannot-run` slot carries
the refusals when present. On an unrecoverable exception / timeout the
tool mints the SAME unified shape with `:status :error` rather than a
special-case payload.

**Errors.** `isError: true` on unknown variant id (the four-verdict
`:status :error` covers in-run failures within a successful envelope).

**Spec.** [`002-Tool-Registry.md`](002-Tool-Registry.md) §Testing.

### `snapshot-identity`

**Input.**

```clojure
{:variant-id     keyword (required)
 :substrate      keyword (optional)
 :active-modes   [keyword] (optional)
 :cell-overrides {keyword any} (optional)}
```

`:cell-overrides` is identity-bearing: it perturbs the `:content-hash`
via the resolved `:effective-args` (Story's `resolve-args` merges
overrides after mode args). Two cells differing only by an override
produce distinct hashes — the same tuple input `run-variant` /
`preview-variant` / `variant-share-url` accept.

**Output.**

```clojure
{:variant-id   keyword
 :mode         keyword | nil
 :substrate    keyword | nil
 :content-hash string}
```

### `read-a11y-violations`

READS the axe-core violations a variant's in-browser a11y panel has
already accumulated — it does NOT execute axe-core. A diagnostic
re-read of stored panel state (the `read-` no-recompute vocabulary, the
sibling of `read-failures`); calling it neither runs a fresh check nor
proves the variant accessible.

**Input.**

```clojure
{:variant-id        keyword (required)
 :include-sensitive boolean (optional, gated — see `preview-variant`)}
```

The `:violations` vec is live runtime DOM state — each axe-core node
carries `:html` (the violating element's outerHTML), so a value
rendered into the DOM lands verbatim there. axe DOM nodes are an
inherently RE-KEYED runtime payload class, so `:violations` route through
the named re-keyed-runtime egress exception (rf2-jwggld) — the same one
`record-as-variant`'s event vectors take. Under a LIVE variant frame
**EP-0025 FAIL-OPEN** holds: a value rendered into a node `:html` is a
RE-KEYED DOM position the classification path cannot reach, so it ships
RAW — value-match was removed; classify the app-db PATH to redact a value
before it reaches the DOM. Under a NON-LIVE frame (common in the JVM tool
process) the nodes ship raw under the documented carve-out — path-scrub is a
no-op even live, so fail-closing would destroy the tool with zero
leak-delta. `:include-sensitive true` opts out, following the
same `--allow-sensitive-reads` boot gate as `preview-variant`
(rf2-g9fje) — one of the six value-surfacing tools that carry the
opt-in (the others: `preview-variant`, `run-variant`, `read-failures`,
`explain-variant`, `record-as-variant`).

**Output.** `{:variant-id keyword :violations [map] :note string|nil}`.
The shared-process (CLJS co-hosted) deploy returns the accumulated
violations + `:note nil`; JVM-standalone hosts can't run axe-core and
return `{:variant-id <kw> :violations [] :note "a11y is CLJS-only; this
JVM-standalone deploy can't run axe-core. Run the panel in-browser; the
violations atom is read by this tool."}`.

### `read-failures`

**Input.**

```clojure
{:variant-id        keyword (required)
 :include-sensitive boolean (optional, gated — see `preview-variant`)}
```

**Output.** The unified assertion records `run-variant` emits, plus the
aggregate verdict (rf2-ba86n.17):

```clojure
{:variant-id keyword            ; the frame the failures came from
 :status     :pass | :fail | :cannot-run | :error  ; aggregate verdict
 :total      integer            ; total assertion records seen post-scrub
 :failures   [{:assertion :passed? :status ...}]
                                ; records whose :status is :fail / :error
 :assertions [{:assertion :passed? :status ...}]}  ; ALL records, unified
```

No re-run. Each record is normalized to carry a derived `:status`;
`:status` is the aggregate verdict over the records (the ONE rule:
`:error` > `:fail` > `:cannot-run` > `:pass`). `:failures` is filtered to
the genuine failure statuses (`:fail` / `:error`) — a `:cannot-run`
record is not a failure (the runner proved nothing) and surfaces via the
run-level `:status`. The retired `:passing?` boolean is gone. `:total` is
the count of all records (including passed) so an agent can distinguish
"we have records and they're all green" from "no assertions ran" without
re-running. This is a re-READ of the accumulator (no epoch tape), so
`:status` is the assertion-record aggregate only — re-run via
`run-variant` for the full run verdict (the agreement floor + refusals).
Pinned by the end-to-end conformance harness at
`tools/mcp-conformance/test/end-to-end-story.cjs` (asserts on the
`:status` + `:total` + `:failures` slots, locking the shape).

`:include-sensitive` follows the same `--allow-sensitive-reads`
boot gate as `preview-variant` / `run-variant`. Assertion records
stamped `:sensitive? true` are dropped at egress by default; the
`:status` aggregate runs against the SCRUBBED vec so an
agent's view of the verdict is consistent with the records it
actually sees (a dropped sensitive failure does not quietly flip
`:status` to `:pass`).

## Write tools (gated)

Three tools form the Write category. `register-variant` and
`unregister-variant` require `:rf.story-mcp/allow-writes?` to be true
outright. `record-as-variant` is **partially** gated: the read-only
recording path (snippet only) is ungated, and only the `:write-back`
re-registration sits behind the same flag. See
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
 :write-back     boolean (optional, default false) ; re-register under the public :script authoring slot with <captured>
}
```

The input-schema property key is `:write-back` (no `?`) per the
Anthropic `^[a-zA-Z0-9_.-]{1,64}$` regex on tool input-property
names (rf2-pmwgn) — the same wire-key rule that motivates
`:include-sensitive` (no `?`). Response-payload keys are not bound
by the regex; the structuredContent slot `:written-back?` retains
the `?` per Clojure idiom.

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
- `isError: true` when `:write-back` is true but the gate is closed
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
- [`002-Tool-Registry.md`](002-Tool-Registry.md) — the 20 tools in
  prose.
- [`003-Write-Surface-Gating.md`](003-Write-Surface-Gating.md) —
  write-gate behaviour.
- [`tools/story/spec/API.md`](../../story/spec/API.md) — Story core's
  public API that this jar consumes.
