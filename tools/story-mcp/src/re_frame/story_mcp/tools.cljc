(ns re-frame.story-mcp.tools
  "MCP tool implementations — public façade. Each tool reads from /
  writes to `re-frame.story`'s public surface; nothing here registers
  new framework primitives or touches Story's internals.

  Per IMPL-SPEC §7.2 the tool surface splits into three categories
  matching Storybook MCP's Dev / Docs / Testing shape, plus a gated
  v1.1 write surface from §7.3. This ns is the thin façade — the
  per-category handlers live under `re-frame.story-mcp.tools.*`
  (split per the rf2-zkca8 leaf-size ceiling, rf2-3ukix):

    - `tools.helpers`  — result builders, args coercers, scrubbers.
    - `tools.dev`      — `get-story-instructions`, `preview-variant`,
                         `list-substrates`.
    - `tools.docs`     — `list-stories`, `get-story`, `get-variant`,
                         `list-tags`, `list-modes`, `list-assertions`,
                         `variant->edn`.
    - `tools.testing`  — `run-variant`, `snapshot-identity`,
                         `run-a11y`, `read-failures`.
    - `tools.write`    — gated: `register-variant`,
                         `unregister-variant`.
    - `tools.recorder` — gated: `record-as-variant` (rf2-luhdu —
                         bridges the recorder pipeline across MCP).
    - `tools.schemas`  — recurring JSON-schema fragments
                         (`kw-or-string`, `max-tokens-schema`,
                         `include-sensitive-schema`) + the
                         `with-max-tokens` / `with-include-sensitive`
                         injection helpers.
    - `tools.registry` — `tool-registry`, `tool-descriptors`,
                         `tool-by-name` (the descriptor data + lookup;
                         each category ns owns its own `descriptors`
                         def and registry concatenates them in order).
    - `tools.cap`      — `invoke-tool` (the dispatcher + wire-boundary
                         token-cap enforcement).

  ## Tool registry

  Each tool is a map:

      {:name        \"<dash-separated-name>\"
       :description \"<one-line semantics>\"
       :category    :dev | :docs | :testing | :write
       :inputSchema { ... JSON schema ... }
       :handler     (fn [args] result-map-or-error)}

  The handler returns either:

  - A success map `{:content [{:type \"text\" :text \"...\"}] :structuredContent {...}}`
    — wrapped by `tools/call` into a JSON-RPC result.
  - An error map `{:content [{:type \"text\" :text \"error msg\"}] :isError true}`
    — for tool-execution errors (the tool ran but failed).

  Protocol-level errors (unknown tool name, malformed `arguments`) live
  on the dispatcher in `re-frame.story-mcp.server`.

  ## Why not call into Story's internals directly?

  Story's `re-frame.story` ns is the contract. Per IMPL-SPEC §7.4
  Story's core jar exposes `handlers`, `handler-meta`, `run-variant`,
  `variant->edn`, `snapshot-identity`, `list-tags`, `list-modes`, the
  Stage 5 assertion helpers, and `variant-share-url`. Nothing here
  reaches past that public surface.

  ## Wire-egress privacy (rf2-73wuj)

  Per spec/Tool-Pair.md §Direct-read privacy posture (lines 544-566)
  every pair-shaped tool surfacing live frame state MUST route the
  value through `re-frame.core/elide-wire-value` before the value
  crosses the wire egress. Two surfaces in story-mcp ship live-state
  reads:

    - `preview-variant` + `run-variant` return the variant frame's
      `:app-db` slice (`re-frame.story.runtime/record-result-map` —
      the slot is `(rf/get-frame-db variant-id)` verbatim).
    - `read-failures` returns the variant frame's
      `:rf.story/assertions` accumulator.

  The walker reads the live `[:rf/elision :declarations]` and
  `[:rf/elision :runtime-flagged]` registries from the named frame's
  app-db, so the `:frame variant-id` opts slot is load-bearing — the
  per-variant registry is the one that gets consulted. Off-box
  defaults apply: `:rf.size/include-sensitive?` and
  `:rf.size/include-large?` both default `false`. The cross-MCP
  `:include-sensitive?` arg (per rf2-vw4sq and the shared
  `re-frame.mcp-base.sensitive` convention) is the documented opt-in
  escape hatch — every tool that ships an `:app-db` or
  assertion-accumulator surface inherits the same arg name an agent
  learns once.

  The assertion accumulator is a vector of records (not a map). It
  rides `strip-sensitive` (the trace-event filter from
  `mcp-base.sensitive`) because the records use the same
  `:sensitive?` top-level stamping convention as trace events — when
  an assertion fires inside the scope of a registration declared
  `:sensitive? true`, the record will carry the stamp. The walker
  handles the registry-driven path elision; the filter handles the
  per-record stamp. The two compose."
  (:require [re-frame.story-mcp.tools.cap :as cap]
            [re-frame.story-mcp.tools.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Public surface — the server dispatcher (`re-frame.story-mcp.server`)
;; and the test suite (`tools_test.clj`) reach for these names.
;; ---------------------------------------------------------------------------

(def tool-registry
  "See `re-frame.story-mcp.tools.registry/tool-registry`."
  registry/tool-registry)

(defn tool-descriptors
  "See `re-frame.story-mcp.tools.registry/tool-descriptors`."
  []
  (registry/tool-descriptors))

(defn tool-by-name
  "See `re-frame.story-mcp.tools.registry/tool-by-name`."
  [tool-name]
  (registry/tool-by-name tool-name))

(defn invoke-tool
  "See `re-frame.story-mcp.tools.cap/invoke-tool`."
  [tool-name arguments]
  (cap/invoke-tool tool-name arguments))

;; The cap-enforcement test (`cap-honours-default-when-omitted`) reaches
;; for `#'tools/sum-text-tokens` via the var. Re-expose it here so the
;; test contract survives the split (`tools_test.clj` line 859).
(def sum-text-tokens
  "See `re-frame.story-mcp.tools.cap/sum-text-tokens`."
  cap/sum-text-tokens)
