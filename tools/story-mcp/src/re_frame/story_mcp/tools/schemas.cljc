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
  wire form disallows it)."
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
