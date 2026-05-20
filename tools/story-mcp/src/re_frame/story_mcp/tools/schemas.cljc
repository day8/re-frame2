(ns re-frame.story-mcp.tools.schemas
  "Recurring JSON-schema fragments + the schema-prop-injection helpers
  used by every tool's `:inputSchema`. Kept in its own ns so each
  category's descriptor list (`dev/descriptors`, `docs/descriptors`,
  …) can require these without circling through `registry`.")

(def kw-or-string
  "Recurring fragment — accept either string-form keywords
  (`\":story.foo/bar\"`) or the bare-name form (`\"story.foo/bar\"`)."
  {:type "string"
   :description "A Clojure keyword id, as a string. Either `:story.foo/bar` or `story.foo/bar` is accepted."})

(def max-tokens-schema
  "Recurring fragment — every tool accepts a per-call `:max-tokens`
  override of the wire-boundary cap (rf2-rvyzy / rf2-zavp5). `0`
  disables the cap; default is
  `re-frame.mcp-base.overflow/default-max-tokens` (5000)."
  {:type "integer" :minimum 0
   :description "Per-call wire-boundary token cap. 0 disables; default 5000 (per `spec/Cross-Cutting-Designs.md §3`)."})

(def include-sensitive-schema
  "Recurring fragment — every tool that surfaces a live `:app-db`
  slice or assertion accumulator accepts the cross-MCP
  `:include-sensitive` opt-in (rf2-73wuj). Default false:
  declared-sensitive paths land `:rf/redacted` via `elide-wire-value`,
  and assertion records stamped `:sensitive? true` are dropped via
  `strip-sensitive`. Pass true to forward the raw values; per the
  cross-MCP convention from `re-frame.mcp-base.sensitive`.

  Honoured only when the server was started with
  `--allow-sensitive-reads` (rf2-g9fje). When that operator-only gate
  is closed, the slot is omitted from `tools/list` advertisements (so
  agents don't even see it) and any caller-supplied value is silently
  ignored at egress.

  Wire-key shape: the property key is `:include-sensitive` (no `?`).
  The Anthropic Messages API constrains tool input-schema property
  keys to `^[a-zA-Z0-9_.-]{1,64}$` — the trailing `?` Clojure-idiomatic
  for booleans is rejected at the host, so the wire form drops it. The
  predicate FUNCTION `helpers/include-sensitive?` retains the `?` (the
  Clojure idiom belongs on the predicate, not on the data key whose
  wire form disallows it).

  This is the canonical rule for the **input-schema property side**
  (rf2-pmwgn): every boolean tool input property MUST omit the
  trailing `?` (today: `:include-sensitive` and `:write-back`).
  Response-payload keys (in `structuredContent`) are NOT bound by the
  Anthropic regex and retain the Clojure-idiomatic `?` — that's why
  `:passing?`, `:registered?`, `:unregistered?`, `:written-back?`,
  `:has-wrap?` survive on the response side."
  {:type "boolean"
   :description (str "Opt in to forwarding sensitive `:app-db` slots and "
                     "assertion records. Default false (declared-sensitive paths "
                     "return `:rf/redacted`; assertion records stamped "
                     "`:sensitive? true` are dropped). Per spec/Tool-Pair.md "
                     "§Direct-read privacy posture. Honoured only when the "
                     "server was started with `--allow-sensitive-reads`.")})

(defn with-max-tokens
  "Inject the `:max-tokens` slot into a tool's `:properties` map. Every
  tool inherits the slot so the cap is uniformly overrideable per call."
  [props]
  (assoc props :max-tokens max-tokens-schema))

;; ---------------------------------------------------------------------------
;; Pagination schema fragments (rf2-76sf6)
;;
;; spec/Principles.md §'Tight token budget' MUST: every read tool whose
;; return size is a function of registry size MUST accept `:limit` and
;; return a `:cursor` for continuation. The Docs `list-*` tools share
;; the same pagination contract — define the schema fragments once
;; here and inject via `with-pagination`.
;; ---------------------------------------------------------------------------

(def limit-schema
  "Recurring fragment — `list-*` tools accept a `:limit` arg to bound
  the per-page entry count. Default 25 per
  `re-frame.story-mcp.tools.cursor/default-limit`; clamped to 200 per
  `max-limit`."
  {:type "integer" :minimum 1 :maximum 200
   :description (str "Per-page entry count. Default 25; clamped to 200. "
                     "Pair with `:cursor` for continuation across pages. "
                     "Per spec/Principles.md §'Tight token budget' the "
                     "default keeps the response under the wire cap.")})

(def cursor-schema
  "Recurring fragment — `list-*` tools accept an opaque `:cursor`
  string for continuation. The agent passes the response's
  `:next-cursor` value back verbatim on the next call; the encoding is
  an implementation detail (today: base64 of an EDN map; subject to
  change). When the underlying registry changes between cursor mint
  and deref, the server returns `:rf.mcp/cursor-stale` and the agent
  must drop the cursor + restart."
  {:type "string"
   :description (str "Opaque continuation token from a previous list-* call's "
                     ":next-cursor slot. Agents pass the value back verbatim. "
                     "On registry change between pages the server returns "
                     ":rf.mcp/cursor-stale; drop the cursor and restart.")})

(defn with-pagination
  "Inject `:limit` and `:cursor` slots into a tool's `:properties`
  map. Used by every Docs `list-*` tool per spec/Principles.md
  §'Tight token budget' (rf2-76sf6).

  The `get-*` tools (`get-story`, `get-variant`, `get-docs-markdown`,
  `variant->edn`) are NOT paginated — their return is a single record
  bounded by the registered body's size, not a function of registry
  size. Per the Principles MUST: 'any read tool whose return size is
  a function of registry size' — single-record reads are bounded
  separately by the wire-boundary cap (`:max-tokens` + the
  `:rf.mcp/overflow` marker)."
  [props]
  (assoc props
         :limit  limit-schema
         :cursor cursor-schema))

(defn with-include-sensitive
  "Inject the `:include-sensitive` slot into a tool's `:properties`
  map. Used by tools that surface a live `:app-db` slice or assertion
  accumulator (`preview-variant`, `run-variant`, `read-failures`).

  The slot is baked into the static descriptor at load time and
  stripped at `tools/list` time by `registry/tool-descriptors` when the
  server's `--allow-sensitive-reads` gate is closed (rf2-g9fje) — so a
  closed gate hides the opt-in from agents entirely, and an open gate
  surfaces it verbatim.

  Wire-key shape: the property key is `:include-sensitive` (no `?`)
  to satisfy Anthropic's `^[a-zA-Z0-9_.-]{1,64}$` constraint on tool
  input-schema property keys; see `include-sensitive-schema`."
  [props]
  (assoc props :include-sensitive include-sensitive-schema))

;; ---------------------------------------------------------------------------
;; outputSchema fragments (rf2-3l3be)
;;
;; Story-mcp tools route through `helpers/text-result` / `error-result`,
;; both of which emit a dual-slot envelope (rf2-vw4sq) — `:content` plus
;; `:structuredContent`. The structuredContent slot carries the EDN
;; payload as a JS-coerced object; this fragment describes its shape so
;; agent hosts can validate the result client-side.
;;
;; MCP-spec constraint: the official `ListToolsResultSchema` (Zod) pins
;; `outputSchema.type` to the literal string \"object\" and rejects any
;; other shape (notably top-level `oneOf` / `anyOf` — those are NOT
;; valid MCP outputSchemas). Per spec we describe one flat object
;; envelope, permissive on tool-specific slots, and document the
;; wire-bounded marker alternatives in the prose description (the
;; catalogue prose at spec/002-Tool-Registry.md is the canonical
;; per-tool source).
;; ---------------------------------------------------------------------------

(def default-output-schema
  "Default outputSchema for story-mcp tools — a flat object envelope.
  Permissive (`additionalProperties: true`) so per-tool payload slots
  ride without us re-enumerating them; the catalogue prose carries
  the per-tool shape definitively. The wire-bounded `:rf.mcp/overflow`
  marker (rf2-rvyzy) lands as an extra top-level key when the cap
  fires; it satisfies `additionalProperties` and is documented in
  prose rather than encoded as a `oneOf` alternative (the MCP
  outputSchema contract pins `type` to the literal \"object\" and
  rejects top-level alternation)."
  {:type "object"
   :additionalProperties true
   :properties {"isError" {:type "boolean"
                            :description "Present and true on error envelopes (per MCP §Error Handling)."}
                "rf.mcp/overflow" {:type "object"
                                   :additionalProperties true
                                   :description (str "Wire-bounded marker (rf2-rvyzy). Present iff the wire-boundary "
                                                     "token cap fired and replaced the tool's normal payload; carries "
                                                     ":dropped-bytes etc.")}}
   :description (str "Result envelope: a structuredContent object carrying the tool's payload. "
                     "Optional :isError on the error path; optional :rf.mcp/overflow marker when "
                     "the cap step fires. See spec/002-Tool-Registry.md for the per-tool payload shape.")})

;; ---------------------------------------------------------------------------
;; Tool annotations (rf2-94p8q)
;;
;; MCP `tools/list` advertises per-tool annotation hints so agent hosts
;; (Claude Code, Continue, …) can auto-approve reads and gate writes
;; behind a permission ceremony. Four slots — `readOnlyHint`,
;; `destructiveHint`, `idempotentHint`, `openWorldHint` — per
;; mcp_best_practices.md.
;;
;; Story-mcp matrix (per the bead rf2-94p8q, refined per rf2-8h778):
;;
;;   - READ-ONLY tools: get-story-instructions, list-substrates,
;;     list-stories, get-story, get-variant, list-tags, list-modes,
;;     list-decorators, list-assertions, get-docs-markdown, variant->edn,
;;     snapshot-identity, run-a11y, read-failures.
;;
;;   - DESTRUCTIVE tools: preview-variant (dispatches events into a
;;     variant's frame via the same `story/run-variant` lifecycle as
;;     run-variant — rf2-8h778), run-variant, register-variant,
;;     unregister-variant, record-as-variant.
;; ---------------------------------------------------------------------------

(def read-only-annotations
  "Annotations for pure-read tools — agent hosts can auto-approve.
  story-mcp reads do NOT reach external state (everything runs JVM-
  side off the story registry); `:openWorldHint false` reflects that.
  Reads against the same registry are idempotent."
  {:readOnlyHint   true
   :idempotentHint true
   :openWorldHint  false})

(def destructive-write-annotations
  "Annotations for mutating tools (register / unregister / record).
  `:destructiveHint true` so agent hosts gate behind explicit
  confirmation. `:openWorldHint false` — writes land in the JVM-side
  story registry, not an external system."
  {:destructiveHint true
   :openWorldHint   false})

(def run-variant-annotations
  "Annotations for the variant-lifecycle tools (`run-variant` and
  `preview-variant`) — both execute a variant's four-phase lifecycle
  which dispatches events into the variant's frame. Per
  spec/Tool-Pair.md §Direct-read privacy posture the run is a write
  to the runtime, so `:destructiveHint true`. The events fire inside
  the JVM process — `:openWorldHint false`.

  rf2-8h778: `preview-variant` originally shipped with
  `read-only-annotations`; the audit (rf2-3pn6c Finding #2) caught
  the asymmetry — both tools call `(story/run-variant vk opts)` and
  derive their result from the lifecycle outcome. The annotations
  must match the side-effect surface, not the verb gloss
  (`preview-variant`'s rendered URL output) at the wire."
  {:destructiveHint true
   :openWorldHint   false})

(def write-gated-output-schema
  "outputSchema for write-surface tools (`register-variant`,
  `unregister-variant`, `record-as-variant` with `:write-back true`).
  Includes the gated-error shape — when the operator-only
  `--allow-writes` flag is closed, the tool returns
  `{:isError true :structuredContent {:gated true :tool \"<name>\"}}`.

  Single flat object envelope (MCP outputSchema pins `type` to
  \"object\" and disallows top-level alternation); the success / error
  / gated-error variants ride on the optional slots described below
  plus `additionalProperties: true` for per-tool payload."
  {:type "object"
   :additionalProperties true
   :properties {"isError" {:type "boolean"
                            :description "Present and true on error envelopes (per MCP §Error Handling)."}
                "gated"   {:type "boolean"
                            :description "Present and true when the operator-only --allow-writes gate is closed."}
                "tool"    {:type "string"
                            :description "Name of the tool whose write was gated; present iff :gated true."}
                "rf.mcp/overflow" {:type "object"
                                   :additionalProperties true
                                   :description "Wire-bounded marker (rf2-rvyzy); present iff the cap step fired."}}
   :description (str "Result envelope: success / error / gated-error map (the write surface is "
                     "default-off behind --allow-writes; closed gate emits {:gated true :tool ...}). "
                     "See spec/003-Write-Surface-Gating.md.")})
