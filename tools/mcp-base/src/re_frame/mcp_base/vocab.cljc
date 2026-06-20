(ns re-frame.mcp-base.vocab
  "Cross-MCP wire-vocabulary constants. One place to learn — and pin —
  the namespaced keys that the agent learns once and recognises across
  every MCP tool in the re-frame2 pair (re-frame2-pair-mcp,
  story-mcp).

  ## Why a vocab ns

  Every wire mechanism (overflow cap, cursor pagination, structural
  dedup, diff-encode, size-elision) decorates the payload with a
  marker key. The marker keys MUST stay byte-identical across the
  pair — an agent that learns `:rf.mcp/overflow` on re-frame2-pair-mcp must
  see the same key shape from story-mcp. Defining the keys here,
  once, prevents per-consumer drift and gives a single search target
  when the vocabulary grows.

  ## Two namespaces plus envelope slots

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

  Unqualified envelope slots — `:dropped-sensitive` /
  `:elided-large` — are scalar suppression counters that ride the
  envelope (MUST-level per Conventions §Cross-MCP indicator-field
  vocabulary). The rationale for keeping them unqualified lives at
  their defs below.

  ## JSON-RPC error codes

  Per JSON-RPC 2.0 §5.1 and MCP's reuse of them — the same numeric
  codes apply across both servers. Story-mcp emits them via Cheshire;
  re-frame2-pair-mcp would emit them via the npm MCP SDK if it surfaced
  JSON-RPC-level errors directly (today it uses the SDK's
  `isError: true` tool-result shape, but the codes still pin the
  cross-consumer protocol if a future bead lifts them).")

;; ---------------------------------------------------------------------------
;; :rf.mcp/* — per-tool wire mechanism markers
;; ---------------------------------------------------------------------------

(def overflow-key
  "Top-level marker on a response that exceeded the per-call token cap.
  Shape (per the shared `mcp-base.cap/apply-cap` → `overflow/overflow-payload`
  builder, emitted by BOTH re-frame2-pair-mcp and story-mcp):
    `{:rf.mcp/overflow {:limit :reached :token-count N :cap-tokens M
                        :tool \"<name>\" :hint \"...\"}}`."
  :rf.mcp/overflow)

(def dedup-table-key
  "Top-level marker on a structurally-deduped payload. The value is the
  de-dupe library's flat cache map; the agent expands locally via
  `de-dupe.core/expand`.
  Shape: `{:rf.mcp/dedup-table <cache-map>}`."
  :rf.mcp/dedup-table)

(def diff-from-key
  "Marker on an epoch's `:db-after` slot indicating it is a structural
  diff against a sibling slot. The value names the source slot
  (`:db-before`). Shape (path-headed cluster projection):
    `{:db-after {:rf.mcp/diff-from :db-before
                 :sections [{:section-path [...] :section-kind <kw>
                             :patches [...]} ...]}}`.
  See `re-frame.mcp-base.diff-encode` and
  `re-frame.mcp-base.section-grouping`."
  :rf.mcp/diff-from)

(def cursor-stale-reason
  "Structured error-result `:reason` value indicating the cursor's
  epoch-id is no longer in the runtime ring.

  Agents pattern-match on this `:reason` to either drop the cursor
  and restart, or widen the window to recover."
  :rf.mcp/cursor-stale)

(def cache-hit-key
  "Top-level marker on a response that short-circuited via the
  per-session cache. Shape:
    `{:rf.mcp/cache-hit {:tool ... :digest ... :hint ...}}`. The agent
  re-uses the previously-shipped payload (the marker is content-free —
  the agent host correlates by cache key)."
  :rf.mcp/cache-hit)

(def summary-key
  "Top-level marker on a lazy-summary response (snapshot mode
  `:summary`). Shape:
    `{:rf.mcp/summary {...tree-summary...}}`. The summary is a
  deliberately small tree-keyed projection; agents drill in via
  `get-path` once they know the key of interest."
  :rf.mcp/summary)

(def invalid-arg-key
  "Top-level marker on an error-result rejecting a malformed per-call
  argument at the wire boundary. Shape:
    `{:rf.mcp/invalid-arg {:arg <kw> :value <supplied> :hint <str>}}`.

  Unlike `:rf.mcp/cursor-stale` (a `:reason` value on a generic
  `{:ok? false}` envelope), this is a TOP-LEVEL wrapper carried as the
  payload of an `isError: true` tool-result — an honest, recoverable
  rejection the agent reads and corrects, not a server fault.

  First emitter: `cap/max-tokens` rejects a NEGATIVE
  `:max-tokens` arg here rather than resolving it to a negative cap
  (which would over-trip `apply-cap` and lock the agent out of every
  response). The cross-MCP `:rf.mcp/*` namespace reserves the key for
  any future per-call arg-validation rejection."
  :rf.mcp/invalid-arg)

(def result-key
  "Top-level discriminator key for a wire-FIDELITY typed result envelope.
  Where the size markers above shrink an over-budget
  payload, this one TYPES the outcome of an evaluation so a genuine
  `nil`, a thrown eval-error, and an unserializable value
  (`#object`/`#js`/Function) stop collapsing to a bare `null`. Shape:
    `{:rf.mcp/result <tag> ...}` where `<tag>` is one of `:value`
    (`:value <v>`), `:nil` (no extra slots), `:eval-error`
    (`:reason :ex :message :ex-data`), or `:unserializable`
    (`:type :preview`).

  Emitted by the runtime-side classifier wrap in re-frame2-pair-mcp
  (`tools/result_envelope.cljs`) for `eval-cljs` / `handler-meta`; the
  server projects each tag onto the tool's `:ok?` vocabulary. Composes
  with — never bypasses — the size machinery above: the codec tags the
  outcome; the elision walker still runs over the `:value` payload it
  carries. Per [Tool-Pair §Wire fidelity](../../../../spec/Tool-Pair.md)."
  :rf.mcp/result)

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
  path. Reserved per Conventions §Reserved namespaces."
  :rf.size/large-elided)

(def redacted-sentinel
  "Literal value substituted **in-place** for a sensitive leaf by the
  framework's `rf/elide-wire-value` walker and by the `redact-interceptor`
  interceptor. Unlike `:rf.size/large-elided`, this is a **scalar
  sentinel** — there is no map payload, no `:handle`, no re-fetch
  affordance. The value is gone; the agent sees `:rf/redacted` and
  MUST NOT attempt to recover it.

  Per spec/Tool-Pair.md §Direct-read privacy posture: `elide-wire-value`
  is the single normative emission site for this sentinel (sensitive)
  and for `:rf.size/large-elided` (oversize). When both predicates
  match at a leaf the **sensitive drop wins** — the size marker is
  suppressed because its `:path` / `:bytes` / `:digest` slots would
  leak metadata about the redacted value. Reserved per
  Conventions §Reserved namespaces (`:rf/*` single-root scheme)."
  :rf/redacted)

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
  the marker. Surfaced by every MCP server uniformly as the
  `:include-sensitive` wire-arg (story-mcp and re-frame2-pair-mcp
  alike). Per the Anthropic tool-input-schema regex
  `^[a-zA-Z0-9_.-]{1,64}$`, wire-keys MUST omit the trailing `?`.
  The walker option keyword (this one, `:rf.size/include-sensitive?`)
  is a NAMESPACED framework key — internal, not a wire-key — so it
  retains the predicate `?`. The predicate FUNCTION name
  `include-sensitive?` (see `re-frame.mcp-base.sensitive`) likewise
  retains `?` — the idiom belongs on predicates."
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
;; Envelope indicator-field slots (Conventions §Cross-MCP indicator-field
;; vocabulary; Spec 009 §Size elision in traces — Indicator field on tool
;; responses). MUST-level pin per Conventions §Cross-MCP indicator-field
;; vocabulary: every tool that returns a
;; structured response map and walks a tree-typed payload carries BOTH
;; counts on the envelope (omit when zero, alongside the qualified wire
;; markers `:rf/redacted` / `:rf.size/large-elided` at the leaf-
;; substitution site).
;;
;; Unqualified keys — they ride alongside the tool's own unqualified
;; envelope slots (`{:trace [...] :dropped-sensitive 3}`,
;; `{:db {...} :elided-large 2}`). Mixing namespaced and unqualified
;; envelope keys would split the response shape across two conventions
;; and burn agent-host pattern-match budget for no information gain.
;; The wire markers at the leaf-substitution site stay namespaced —
;; they are addressable values the agent re-fetches; these counts are
;; scalar summaries on the envelope.
;; ---------------------------------------------------------------------------

(def dropped-sensitive-key
  "Envelope-slot key for the count of `:sensitive? true` leaves dropped
  at the wire boundary. Unqualified per Conventions §Cross-MCP
  indicator-field vocabulary. Omit the slot when the count is zero.
  Mirror of `[● REDACTED N]` on on-box dev-tool surfaces."
  :dropped-sensitive)

(def elided-large-key
  "Envelope-slot key for the count of leaves replaced with the
  `:rf.size/large-elided` marker at the wire boundary. Unqualified
  per Conventions §Cross-MCP indicator-field vocabulary. Omit the
  slot when the count is zero. Mirror of `[● ELIDED N]` on on-box
  dev-tool surfaces. Per Spec 009 §Size elision in traces —
  Indicator field on tool responses (MUST-level).

  The walker that produces the count lives in
  `re-frame.mcp-base.elision/count-elided-markers` — the count is a
  runtime tree-walk, not a constant, so it sits alongside the key in
  a sibling ns."
  :elided-large)

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
