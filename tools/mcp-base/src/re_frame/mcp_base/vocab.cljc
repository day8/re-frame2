(ns re-frame.mcp-base.vocab
  "Cross-MCP wire-vocabulary constants. One place to learn — and pin —
  the namespaced keys that the agent learns once and recognises across
  every MCP tool in the re-frame2 triplet (pair2-mcp, story-mcp,
  causa-mcp).

  ## Why a vocab ns

  Every wire mechanism (overflow cap, cursor pagination, structural
  dedup, diff-encode, size-elision) decorates the payload with a
  marker key. The marker keys MUST stay byte-identical across the
  triplet — an agent that learns `:rf.mcp/overflow` on pair2-mcp must
  see the same key shape from story-mcp / causa-mcp. Defining the
  keys here, once, prevents per-consumer drift and gives a single
  search target when the vocabulary grows.

  ## Two namespaces

  `:rf.mcp/*` — per-tool wire-mechanism markers (overflow, cursor,
                dedup-table, diff-from, cache-hit, cursor-stale).
                Owned by the MCP servers; not part of the framework
                runtime vocabulary.

  `:rf.size/*` — size-elision markers (large-elided, threshold, opts).
                 Owned jointly with the framework's
                 `rf/elide-wire-value` walker (Conventions §Reserved
                 namespaces; Spec 009 §Size elision in traces). The
                 walker emits the marker; MCP servers re-emit it on
                 the wire.

  ## JSON-RPC error codes

  Per JSON-RPC 2.0 §5.1 and MCP's reuse of them — the same numeric
  codes apply across the triplet. Story-mcp emits them via Cheshire;
  pair2-mcp would emit them via the npm MCP SDK if it surfaced
  JSON-RPC-level errors directly (today it uses the SDK's
  `isError: true` tool-result shape, but the codes still pin the
  cross-consumer protocol if a future bead lifts them).")

;; ---------------------------------------------------------------------------
;; :rf.mcp/* — per-tool wire mechanism markers
;; ---------------------------------------------------------------------------

(def overflow-key
  "Top-level marker on a response that exceeded the per-call token cap.
  Shape (per pair2-mcp's `apply-cap`):
    `{:rf.mcp/overflow {:limit :reached :token-count N :cap-tokens M
                        :tool \"<name>\" :hint \"...\"}}`.
  Per rf2-rvyzy."
  :rf.mcp/overflow)

(def dedup-table-key
  "Top-level marker on a structurally-deduped payload. The value is the
  de-dupe library's flat cache map; the agent expands locally via
  `de-dupe.core/expand`.
  Shape: `{:rf.mcp/dedup-table <cache-map>}`. Per rf2-obpa9.

  causa-mcp uses the same key for its mechanism-5 dedup
  (cross-MCP-conventions, per causa-mcp/spec/Principles.md §5)."
  :rf.mcp/dedup-table)

(def diff-from-key
  "Marker on an epoch's `:db-after` slot indicating it is a structural
  diff against a sibling slot. The value names the source slot
  (`:db-before`). Shape:
    `{:db-after {:rf.mcp/diff-from :db-before :patches [...]}}`.
  Per rf2-1wdzp."
  :rf.mcp/diff-from)

(def cursor-stale-reason
  "Structured error-result `:reason` value indicating the cursor's
  epoch-id is no longer in the runtime ring. Per rf2-kbqq3.

  Agents pattern-match on this `:reason` to either drop the cursor
  and restart, or widen the window to recover."
  :rf.mcp/cursor-stale)

(def cache-hit-key
  "Top-level marker on a response that short-circuited via the
  per-session cache (rf2-3rt1f, rf2-36xod). Shape:
    `{:rf.mcp/cache-hit {:tool ... :digest ... :hint ...}}`. The agent
  re-uses the previously-shipped payload (the marker is content-free —
  the agent host correlates by cache key)."
  :rf.mcp/cache-hit)

(def summary-key
  "Top-level marker on a lazy-summary response (snapshot mode
  `:summary`). Per rf2-tygdv / rf2-u2029. Shape:
    `{:rf.mcp/summary {...tree-summary...}}`. The summary is a
  deliberately small tree-keyed projection; agents drill in via
  `get-path` once they know the key of interest."
  :rf.mcp/summary)

;; ---------------------------------------------------------------------------
;; :rf.size/* — size-elision markers
;; ---------------------------------------------------------------------------

(def large-elided-key
  "Marker substituted for an over-threshold leaf (or a declared-large
  registry-path) by the framework's `rf/elide-wire-value` walker.
  Shape (per Spec 009 §Size elision in traces):
    `{:rf.size/large-elided {:bytes N :type \"...\"
                             :handle [:rf.elision/at <path>]}}`.
  Agents drill back into the slot via `get-path` using the handle's
  path. Reserved per Conventions §Reserved namespaces. Per rf2-urjnc."
  :rf.size/large-elided)

(def elision-handle-key
  "First slot in the elision handle vector. The vector shape
  `[:rf.elision/at <path>]` is the cross-MCP convention for re-fetch
  by path. Reserved per Conventions §Reserved namespaces."
  :rf.elision/at)

(def include-large-opt
  "The framework `rf/elide-wire-value` walker opt that controls
  whether large leaves emit the marker (`false` ⇒ emit marker;
  `true` ⇒ pass through). MCP servers surface this as the high-level
  `:elision` boolean MCP arg; the underlying knob is this."
  :rf.size/include-large?)

(def include-sensitive-opt
  "The framework opt that controls whether sensitive payloads emit
  the marker. Surfaced by MCP servers as the `:include-sensitive?`
  arg (the same name as the high-level filter — see
  `re-frame.mcp-base.sensitive`)."
  :rf.size/include-sensitive?)

(def include-digests-opt
  "Framework opt: whether elided values include a content digest. Per
  Spec 009 §Size elision in traces."
  :rf.size/include-digests?)

(def threshold-bytes-opt
  "Framework opt: byte threshold above which an auto-detected leaf is
  elided. Per Spec 009 §Size elision in traces."
  :rf.size/threshold-bytes)

;; ---------------------------------------------------------------------------
;; JSON-RPC 2.0 error codes (per §5.1; reused by MCP per the spec)
;; ---------------------------------------------------------------------------

(def ^:const code-parse-error
  "Malformed JSON on the wire. JSON-RPC 2.0 §5.1."
  -32700)

(def ^:const code-invalid-request
  "Not a valid JSON-RPC request envelope."
  -32600)

(def ^:const code-method-not-found
  "Unknown method (or unknown tool, when a `tools/call` resolves to
  no registered handler)."
  -32601)

(def ^:const code-invalid-params
  "Method recognised; params shape wrong."
  -32602)

(def ^:const code-internal-error
  "Server-side fault. Tool-execution errors should NOT use this code —
  they use the MCP tool-result shape with `isError: true` so the agent
  client can surface the failure to the LLM without aborting the
  conversation."
  -32603)
