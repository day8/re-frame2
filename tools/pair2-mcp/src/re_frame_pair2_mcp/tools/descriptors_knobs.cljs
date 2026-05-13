(ns re-frame-pair2-mcp.tools.descriptors-knobs
  "Tool-descriptor knob properties + the `with-*-knob` splicers that
  attach them.

  Each tool descriptor exposes a stable schema via `tools/list`. A
  handful of knobs are universal (wire-cap, cache) and pinned here so
  every descriptor inherits the same shape via composition rather than
  via copy-paste."
  (:require [re-frame-pair2-mcp.cache :as cache]
            [re-frame-pair2-mcp.tools.cap :as cap]))

(def max-tokens-property
  {:type        "integer"
   :description (str "Wire-boundary token-budget cap (default "
                     cap/default-max-tokens
                     "). Per spec/Principles.md §Tight token budget, "
                     "responses serialising over this estimate are "
                     "replaced with an `{:rf.mcp/overflow ...}` "
                     "marker. Pass 0 to disable the cap.")})

(def limit-property
  "Per-tool descriptor slot for the `:limit` cursor-pagination knob
  (rf2-kbqq3). Applied to surfaces that ship epoch vectors and would
  otherwise blow the wire-cap on a single call: `trace-window` and
  `watch-epochs`. Default 50 — sized to fit the 5K-token cap after
  diff-encode (rf2-1wdzp) + dedup (rf2-obpa9)."
  {:type        "integer"
   :description (str "Maximum number of epoch records in the response "
                     "(default 50). The default is sized to fit the "
                     "5K-token wire-cap (rf2-rvyzy) with diff-encode + "
                     "dedup active. When more records remain, "
                     "`:next-cursor` is non-nil and `:has-more? true`; "
                     "pass the cursor back to fetch the next page.")})

(def cursor-property
  "Per-tool descriptor slot for the opaque `:cursor` continuation token
  (rf2-kbqq3). Applied to `trace-window` and `watch-epochs`."
  {:type        "string"
   :description (str "Opaque cursor returned by a previous call's "
                     "`:next-cursor`. Pass back verbatim to fetch the "
                     "next page. A cursor whose epoch-id has aged out "
                     "of the runtime ring surfaces as "
                     "`{:ok? false :reason :rf.mcp/cursor-stale ...}` — "
                     "drop the cursor and restart, or widen the window.")})

(def dedup-property
  "Per-tool descriptor slot for the `:dedup` opt-out (rf2-obpa9).
  Applied to surfaces that ship epoch slices (`snapshot`,
  `trace-window`, `watch-epochs`) and to the `subscribe` streaming
  channel — the surfaces where repeated subtrees dominate the wire
  cost. Default `true`."
  {:type        "boolean"
   :description (str "Apply structural dedup (day8/de-dupe) to the "
                     "epoch slice / event vector before the wire-cap "
                     "check. Default true. When deduped, the slot is "
                     "wrapped as `{:rf.mcp/dedup-table <cache-map>}` "
                     "and the agent host reconstructs via "
                     "`(de-dupe.core/expand cache-map)`. Pass false "
                     "to skip dedup — useful for ad-hoc reads when "
                     "the agent host hasn't been taught to call "
                     "`expand`.")})

(def elision-property
  "Per-tool descriptor slot for the `:elision` opt-out (rf2-urjnc).
  Applied to surfaces that surface `:app-db` slots (`snapshot` and
  `get-path`) — the surfaces where a declared-`:large?` slot or an
  over-threshold leaf can blow the wire cap on its own. Default
  `true`."
  {:type        "boolean"
   :description (str "Apply the size-elision walker "
                     "(`re-frame.core/elide-wire-value`, rf2-v9tw2) "
                     "to the `:app-db` slot server-side, before the "
                     "EDN crosses the wire. Default true. Declared "
                     "(`rf/declare-large-path!`) or schema-driven "
                     "(`:large? true`) paths get substituted with a "
                     "`{:rf.size/large-elided {:path [...] :bytes N "
                     ":type ... :handle [:rf.elision/at <path>]}}` "
                     "marker; the agent re-fetches via `get-path` "
                     "using the handle's path. Auto-detect fires on "
                     "leaf strings over the configured "
                     "`:rf.size/threshold-bytes`. Pass false to "
                     "bypass elision and receive the raw value — "
                     "useful when the agent has explicit override "
                     "permission for the slot (e.g. debugging the "
                     "elided value itself).")})

(def cache-property
  "Per-tool descriptor slot for the `:cache` opt-in (rf2-3rt1f).
  Applied to read-tool descriptors via `with-cache-knob`. Default
  `false` — opt-in until the agent host has been taught the
  `:rf.mcp/cache-hit` marker shape."
  {:type        "boolean"
   :description (str "Consult the per-session response cache. Default "
                     "false. When true and the result's hash matches "
                     "the prior call for this (tool, args), the full "
                     "payload is replaced with a "
                     "`{:rf.mcp/cache-hit {:hash <h> "
                     ":unchanged-since <ms> :tool <t> :hint <s>}}` "
                     "marker — the agent host already has the byte-"
                     "identical payload from the prior call. Cache is "
                     "an 8-slot LRU keyed by (tool, args-fingerprint); "
                     "lifetime is the MCP server process (= one "
                     "session per persistent-socket principle). Read "
                     "tools only (snapshot, get-path, trace-window, "
                     "watch-epochs, discover-app); action tools "
                     "(dispatch, eval-cljs, tail-build) and streaming "
                     "tools (subscribe) bypass.")})

(defn with-budget-knob
  "Splice `max-tokens` into a tool descriptor's inputSchema.properties.
  No-op if the descriptor already declares it (forward-compat)."
  [desc]
  (let [props (get-in desc [:inputSchema :properties])]
    (if (contains? props :max-tokens)
      desc
      (assoc-in desc [:inputSchema :properties :max-tokens]
                max-tokens-property))))

(defn with-cache-knob
  "Splice `cache` into a tool descriptor's inputSchema.properties — but
  only for the read tools that consult `cache/apply-cache`. Action
  tools and streaming tools don't list the knob because it has no
  effect there (bypassed in `cache/cacheable?`)."
  [desc]
  (let [name  (:name desc)
        props (get-in desc [:inputSchema :properties])]
    (if (or (contains? props :cache)
            (not (cache/cacheable? name)))
      desc
      (assoc-in desc [:inputSchema :properties :cache]
                cache-property))))
