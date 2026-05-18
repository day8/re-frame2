(ns re-frame.mcp-base.elision
  "Wire-boundary elision-marker walker (rf2-9fz64).

  Per `spec/009` §Size elision in traces, the framework's
  `rf/elide-wire-value` walker substitutes over-threshold leaves with
  a `{:rf.size/large-elided {...}}` marker before the payload leaves
  the runtime. Every MCP tool that returns a tree-typed payload
  surfaces a scalar count of those substitutions on its response
  envelope (the unqualified `:elided-large` slot — see
  `vocab/elided-large-key` and Conventions §Cross-MCP indicator-field
  vocabulary, MUST-level per rf2-2499j).

  ## Why this ns

  `vocab.cljc` is the constants catalogue — namespaced keys, JSON-RPC
  codes, envelope-slot names. A runtime tree-walker is not a constant
  and was crowding the catalogue's mandate. The walker lives here so
  the elision concern has a clear home; sibling elision-side runtime
  fns land here too as the vocabulary grows.

  ## Cousin to `re-frame.mcp-base.sensitive`

  `sensitive/strip-sensitive` returns `[kept dropped-count]` for the
  `:dropped-sensitive` envelope slot; `count-elided-markers` returns
  the integer for the `:elided-large` slot. Both indicators ride the
  response envelope together per the cross-MCP indicator-field
  parity (one without the other is the round-2 audit fix the
  conformance gate enforces).

  ## Cross-platform

  Pure-data tree walk; loads identically into JVM (story-mcp /
  causa-mcp) and CLJS (re-frame2-pair-mcp). No transport, no runtime, no
  framework dep — the walker only inspects the wire shape."
  (:require [re-frame.mcp-base.vocab :as vocab]))

(defn count-elided-markers
  "Walk `v` and count every `{:rf.size/large-elided ...}` marker it
  contains. The walker is shallow at the marker boundary — once a
  marker is found, its body is NOT recursed into (marker bodies are
  scalar metadata, not tree-shaped). Recurses through maps, vectors,
  sets, and seqs; treats every other value as a leaf.

  Returns an integer ≥ 0. Cheap on the common path (post-elision
  payload with no markers ⇒ one full walk producing zero).

  Counterpart to `re-frame.mcp-base.sensitive/strip-sensitive`'s
  `dropped-count` return — both indicators ride the response envelope
  per Conventions §Cross-MCP indicator-field vocabulary."
  [v]
  (cond
    (map? v)
    (if (contains? v vocab/large-elided-key)
      1
      (reduce-kv (fn [n _k child] (+ n (count-elided-markers child))) 0 v))

    (or (vector? v) (set? v) (seq? v))
    (reduce (fn [n child] (+ n (count-elided-markers child))) 0 v)

    :else 0))
