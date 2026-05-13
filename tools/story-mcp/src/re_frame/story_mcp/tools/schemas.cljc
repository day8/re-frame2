(ns re-frame.story-mcp.tools.schemas
  "Recurring JSON-schema fragments + the schema-prop-injection helpers
  used by every tool's `:inputSchema`. Kept in its own ns so each
  category's descriptor list (`dev/descriptors`, `docs/descriptors`,
  …) can require these without circling through `registry`."
  (:refer-clojure :exclude [name]))

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
  `:include-sensitive?` opt-in (rf2-73wuj). Default false:
  declared-sensitive paths land `:rf/redacted` via `elide-wire-value`,
  and assertion records stamped `:sensitive? true` are dropped via
  `strip-sensitive`. Pass true to forward the raw values; per the
  cross-MCP convention from `re-frame.mcp-base.sensitive`."
  {:type "boolean"
   :description "Opt in to forwarding sensitive `:app-db` slots and assertion records. Default false (declared-sensitive paths return `:rf/redacted`; assertion records stamped `:sensitive? true` are dropped). Per spec/Tool-Pair.md §Direct-read privacy posture."})

(defn with-max-tokens
  "Inject the `:max-tokens` slot into a tool's `:properties` map. Every
  tool inherits the slot so the cap is uniformly overrideable per call."
  [props]
  (assoc props :max-tokens max-tokens-schema))

(defn with-include-sensitive
  "Inject the `:include-sensitive?` slot into a tool's `:properties`
  map. Used by tools that surface a live `:app-db` slice or assertion
  accumulator (`preview-variant`, `run-variant`, `read-failures`)."
  [props]
  (assoc props :include-sensitive? include-sensitive-schema))
